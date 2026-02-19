# Lazy Evaluation Design for DataTwist

## 1. Survey of Approaches

### 1.1 Clojure Lazy Sequences

Clojure's lazy sequences are the most directly relevant primitive. `map`, `filter`, `take`, etc. return lazy sequences that compute elements on demand. Key characteristics:

**Strengths:**
- Native to the host runtime -- zero impedance mismatch
- Composable: `(take 10 (filter odd? (map inc (range 1e9))))` processes only ~20 elements
- Cached: once realized, elements are retained (retraversal is free)

**Pitfalls:**
- **Chunking**: Clojure lazy sequences realize in chunks of 32 elements. Requesting one element can trigger 32 evaluations. This defeats fine-grained laziness for side-effectful operations.
- **Head retention**: Holding a reference to the head of a lazy sequence while traversing it prevents GC of already-consumed elements. This is the primary cause of OOM in lazy Clojure code.
- **Performance overhead**: Each lazy-seq step allocates intermediate sequence objects. A chain of `(->> coll (map f) (filter g) (take n))` creates 3 intermediate lazy sequences. Benchmarks show ~410us and 480KB garbage for chained lazy ops vs ~44us and 6KB for transducers on equivalent pipelines (source: clojure-goes-fast).

**Transducers** solve the intermediate-collection problem by fusing transformation steps into a single reducing function. `(into [] (comp (map f) (filter g) (take n)) coll)` creates zero intermediate sequences, runs in a single pass, and is ~10x faster than the lazy equivalent. Transducers can also be applied lazily via `(sequence xform coll)` or as reusable eductions via `(eduction xform coll)`.

**Key insight for DataTwist**: Use lazy sequences as the default user-visible abstraction (they "just work" with `take`, `first`, etc.), but compile pipeline chains to transducers internally when the full pipeline is known at parse time.

### 1.2 Haskell Laziness

Haskell is pervasively lazy -- every expression is a thunk until forced to Weak Head Normal Form (WHNF). Lessons:

- **Space leaks** are the dominant problem. A left fold `foldl (+) 0 [1..1e9]` builds a chain of 1 billion thunks before evaluating any of them. The strict variant `foldl'` forces each intermediate result. Haskell added `BangPatterns`, `seq`, and `deepseq` as escape hatches.
- **Strictness annotations** (`!` on data constructor fields) prevent thunk buildup in data structures. This is the inverse of DataTwist's `!` convention (side-effect marker), so there is no syntax conflict, but the concept is relevant: some operations should be strict by default.
- **Lesson**: Pervasive laziness is a footgun. DataTwist should be lazy *at the pipeline level* (collection transformations) but strict *at the expression level* (arithmetic, bindings). This matches Clojure's model: expressions are eager, sequences are lazy.

### 1.3 Spark: Lazy Transformations + Eager Actions

Apache Spark's execution model is the gold standard for large-scale lazy data processing:

- **Transformations** (map, filter, flatMap, join, groupBy) are lazy. They build a DAG (Directed Acyclic Graph) of operations but execute nothing.
- **Actions** (collect, count, first, saveAsTextFile, reduce) trigger execution. The entire DAG is submitted to the scheduler, optimized (Catalyst), and executed.
- **Catalyst optimizer** performs predicate pushdown, projection pruning, join reordering, and constant folding on the logical plan before generating physical execution plans.

**What to steal**: The clean separation between lazy transformations and eager actions. DataTwist's `collect`, `count`, `first`, `reduce`, `save!`, `into!`, `force!` are exactly Spark's "actions". The user never has to think about laziness -- they just write pipelines, and the system figures out when to execute.

### 1.4 Polars: LazyFrame

Polars separates `DataFrame` (eager) from `LazyFrame` (lazy). `LazyFrame` builds a query plan with automatic optimization:

- Predicate pushdown: filters are moved as early as possible
- Projection pushdown: only referenced columns are read
- Type coercion: implicit casting is handled at plan time
- `.collect()` triggers execution and returns a `DataFrame`

**LazyFrame is ~1.15-1.7x faster than DataFrame** (eager) for identical operations, because the optimizer can eliminate redundant work. However, not all operations are available on LazyFrame (e.g., `pivot()` requires full materialization).

**What to steal**: The model where the default path is lazy and `.collect()` is the standard way to materialize. Also the concept that some operations are inherently eager (pivot, sort-by on the full dataset) and the system should communicate this clearly.

### 1.5 DuckDB

DuckDB operates on a pull-based vectorized execution model:

- All queries are lazy: a `SELECT ... LIMIT 10` will not scan the entire table
- **LIMIT pushdown**: When LIMIT < 8192, DuckDB pushes it into the scan operator, reading only the necessary row groups
- **Sampling**: DuckDB supports `USING SAMPLE 10%` with three methods (reservoir, bernoulli, system). Row-count sampling uses reservoir; percentage sampling uses bernoulli or system.
- Row groups are 122,880 rows. Parallelism operates at the row-group level.

**What to steal**: The LIMIT pushdown model (DataTwist's `take` should short-circuit), and the sampling model (multiple strategies, row-count by default for small samples, percentage for large datasets).

### 1.6 R: Promises and dplyr

R has lazy evaluation at the language level: function arguments are "promises" (expression + environment, evaluated on first use). dplyr leverages this for database backends:

- `dplyr::filter()`, `mutate()`, `select()` on a database-backed tibble build a SQL query plan
- `collect()` sends the SQL to the database and returns a local tibble
- `show_query()` displays the generated SQL without executing it (equivalent to DataTwist's `explain`)

**What to steal**: The idea that `collect()` is a standard verb across all backends (in-memory, database, file). Also that `show_query()`/`explain` is essential for debugging.

---

## 2. Design Decisions

### Q1: Lazy vs. Eager Boundary

**Decision: Pipeline-level laziness. Expression-level eagerness.**

**Lazy operations** (return lazy sequences, build no intermediate collections):
- `filter`, `map`, `take`, `drop`, `distinct`, `flatten`, `concat`, `zip`

**Eager operations** (force traversal, return concrete results):
- `sort`, `sort-by`, `group-by`, `reverse` -- these require seeing all elements
- `count`, `first`, `last`, `reduce`, `sum`, `average`, `min`, `max`, `median` -- these are terminal reducers
- `collect` -- explicit materialization to a vector
- `force!` -- materialize and passthrough (for caching in pipeline)
- `save!`, `into!` -- materialize to external sink (passthrough)
- `describe`, `schema`, `histogram`, `freq` -- exploration (operate on sample by default)

**Rationale**: This matches Spark's transformation/action split. The user never has to declare laziness. `data |> filter f |> map g |> take 10` is lazy because none of filter/map/take force evaluation. Appending `|> collect` or `|> count` triggers the work. Eager operations like `sort-by` act as implicit materialization barriers -- they consume the lazy input, produce a concrete sorted result, and downstream operations start a new lazy chain.

### Q2: Sampling Model -- Row Count vs. Byte Size

**Decision: Row-count by default. No byte-based sampling in v1.**

**Rationale**:
- Row-count is universal across all data sources (in-memory, CSV, DB, Parquet). Byte-based sampling depends on serialization format and is meaningless for in-memory collections.
- Every existing tool (pandas `.head()`, DuckDB `LIMIT`, Polars `.head()`, Spark `.show()`) uses row-count.
- Row-count maps directly to `(take n coll)`, which is O(1) setup in Clojure.
- Byte-based sampling would require estimating row sizes, which varies by column types and defeats the purpose of "instant preview."
- DuckDB also defaults to row-count for small samples (reservoir sampling), only using percentage for large-scale statistical sampling.

**Default sample size**: 100 rows. This is enough for a useful preview, small enough to be instant even on slow sources, and matches the REPL display width. Configurable per-session (see Q10).

### Q3: Pipeline Laziness

**Decision: Yes, `data |> filter f |> map g |> take 10` is fully lazy. Only 10 elements flow through filter+map.**

This works automatically because:
1. `filter` returns a Clojure lazy sequence
2. `map` returns a Clojure lazy sequence over the lazy filter result
3. `take 10` returns a lazy sequence that requests at most 10 elements from the chain

When something downstream forces evaluation (e.g., `collect`, `count`, or REPL printing), Clojure's lazy machinery pulls exactly the elements needed. No element passes through `filter` unless it's needed to satisfy `take 10`.

**Caveat**: Clojure chunking means up to 32 elements may be evaluated per chunk. For a `take 10` on a chunked source, approximately 32 elements will be processed through the filter+map chain. This is acceptable -- the chunking overhead is small and the performance benefit of chunk-based allocation is significant.

### Q4: Transducers vs. Lazy Sequences

**Decision: Dual-mode. Lazy sequences by default; transducer fusion as an optimization.**

**Phase 1 (v1.0)**: Use Clojure lazy sequences everywhere. The stdlib `dt-filter`, `dt-map`, `dt-take`, etc. return lazy sequences. This is correct and simple.

**Phase 2 (optimization)**: The evaluator detects pipeline chains and fuses consecutive lazy operations into a transducer. A pipeline `data |> filter f |> map g |> take n |> collect` compiles to:

```clojure
(into [] (comp (filter f) (map g) (take n)) data)
```

This eliminates intermediate lazy sequences and runs ~10x faster. The optimization is transparent -- the user sees the same results.

**Phase 3 (eduction for inspection)**: For `tap!` and `dtw/inspect`, use `eduction` to create inspectable intermediate stages without full materialization. An eduction is re-evaluated on each traversal, which is correct for sampling.

**Rationale**: Starting with lazy sequences is pragmatic -- it's correct, simple, and matches Clojure idioms. Transducer fusion is a performance optimization that can be added later without changing semantics. The BDD tests do not require transducer internals; they only require correct lazy behavior and output.

### Q5: Materialization Triggers

**Decision: Explicit materialization only. The REPL is the sole implicit materializer.**

**Explicit materializers** (user calls these):
| Function | Returns | Behavior |
|---|---|---|
| `collect` | `PersistentVector` | Forces full evaluation, returns concrete vector |
| `count` | `Long` | Forces full traversal, returns count |
| `first` | Element or `nil` | Forces evaluation of first matching element only |
| `last` | Element or `nil` | Forces full traversal, returns last element |
| `reduce` | Scalar | Folds entire collection |
| `sum`, `average`, `min`, `max`, `median` | Scalar | Aggregation (forces full traversal) |
| `force!` | Same data (passthrough) | Materializes into a vector, returns it. `!` = passthrough |
| `save!` | Same data (passthrough) | Writes to file sink, returns data |
| `into!` | Same data (passthrough) | Writes to DB sink, returns data |

**Implicit materializer** (REPL only):
- When the REPL evaluates a lazy sequence, it takes the first 100 elements (configurable) and displays them as a table.
- The lazy sequence itself is not retained -- only the sample is displayed. This prevents head retention.
- If the collection is small (<= sample size), the full result is shown with no estimate.
- If the collection is larger, an estimate is shown: `=> lazy<result> ~N rows`.

**Non-materializers** (remain lazy):
- `sort`, `sort-by`, `group-by`, `reverse` are technically eager (they need all elements), but they produce new collections that can feed lazy downstream operations. Implementation: they realize their input and return a vector/map.

### Q6: Infinite Sequences

**Decision: Yes. `range`, `repeat`, `iterate` produce infinite lazy sequences.**

```
// Infinite range
naturals is range 1 Infinity    // sugar: just range 1 with no upper bound
// or
naturals is iterate [n -> n + 1] 1

// Infinite repeat
zeros is repeat 0

// Must use take or first eventually
first-10-evens is naturals |> filter [n -> n % 2 = 0] |> take 10
```

The existing stdlib `range` already wraps `clojure.core/range`. The change for laziness is to stop wrapping results in `(vec ...)`. The lazy `range` returns a Clojure lazy sequence directly.

**`repeat`** and **`iterate`** are new stdlib additions:
```clojure
"repeat"   (fn ([v] (repeat v))        ;; infinite
               ([n v] (take n (repeat v)))) ;; bounded
"iterate"  (fn [f init] (iterate f init))
```

**Safety**: The REPL micro-sample prevents infinite sequences from hanging. `collect` on an infinite sequence will OOM, but this is user error -- the same as Haskell's `length [1..]`. The REPL should display a warning: `=> lazy<infinite> -- use take to limit`.

### Q7: tap! for Debugging

**Decision: `tap!` takes a micro-sample (default 100 elements) from the data at its pipeline position, displays it, and passes the original data through unchanged.**

Implementation:

```clojure
(defn dt-tap!
  "Passthrough side-effect: sample and display data at a pipeline point.
   Does NOT force full evaluation."
  ([data]
   ;; Take a sample, print it, return original data
   (let [sample (if (counted? data)
                  (take (min 100 (count data)) data)
                  (take 100 data))]
     (println-table sample)
     data))
  ([data label-or-fn]
   (cond
     (string? label-or-fn)
     (do (println (str "--- " label-or-fn " ---"))
         (dt-tap! data))

     (fn? label-or-fn)
     ;; Apply function to sample for display only; data flows through unchanged
     (let [sample (take 100 data)]
       (println (label-or-fn sample))
       data))))
```

**Key property**: `tap!` calls `(take 100 data)` which, on a lazy sequence, only realizes 100 elements (plus chunking overhead). The original `data` reference is returned unchanged. Because Clojure lazy sequences cache realized elements, the 100 elements that `tap!` realized are cached and will not be recomputed when downstream operations request them.

**The tap! function does NOT create a new lazy sequence** -- it passes the original one through. This is critical: `data |> filter f |> tap! |> map g` means `map g` operates on the exact same lazy sequence that `filter f` produced.

### Q8: Schema/Describe/Histogram

**Decision: These exploration functions operate on a sample by default. They do not force full evaluation.**

| Function | Sample behavior | Returns |
|---|---|---|
| `describe` | Samples 1000 rows, computes statistics | Map of per-column stats |
| `schema` | Samples 100 rows, infers types (DB: uses metadata) | Map of column-name -> type |
| `histogram` | Samples 1000 rows, computes bins | Map of bin-ranges and counts |
| `freq` | Forces full evaluation (needs exact counts) | Collection of `{value, count, pct}` |
| `sample` | Takes N random rows (forces partial evaluation) | Vector of N elements |
| `explain` | No data access -- inspects the pipeline plan | String/structured execution plan |

**`freq`** is the exception: frequency tables need exact counts to be useful. If the user wants approximate frequencies, they can `data |> sample 1000 |> freq _.field`.

**`describe`** and **`histogram`** use a larger sample (1000) than the REPL default (100) because statistical summaries need more data for stability. The sample size is a parameter: `data |> describe 5000` overrides the default.

### Q9: Memory Safety

**Decision: Three-pronged approach: (1) avoid head retention, (2) streaming file I/O, (3) documentation.**

**1. Avoid head retention**: The evaluator must never hold a reference to the head of a lazy sequence while traversing it. Concrete rules:
- `eval-pipeline` should NOT bind the lazy intermediate to a local variable that persists during traversal. Instead, each step feeds directly into the next.
- `force!` converts to a vector (breaks the lazy chain, allows GC of the source).
- The REPL does NOT retain the lazy sequence after displaying the sample. It binds the sample (a short vector), not the lazy source.

**2. Streaming file I/O**: `read-csv`, `read-json`, `read-jsonl`, `read-lines` return lazy sequences backed by buffered readers. The reader is closed when the sequence is fully consumed or when `close!` is called. Using `with-open` semantics internally:

```clojure
;; Pseudocode for read-csv
(defn read-csv [path opts]
  (let [reader (io/reader path)
        lines  (line-seq reader)]
    ;; Return lazy seq; reader is closed when seq is exhausted
    ;; or when close! is called on the source handle
    (parse-csv-lazy lines opts)))
```

**3. Documentation**: The DataTwist guide should warn about:
- `collect` on very large datasets (use `force! |> save!` instead)
- Binding a lazy pipeline and traversing it multiple times (file sources re-open; in-memory sources re-traverse)
- Infinite sequences require `take` or `first`

### Q10: Configuration

**Decision: Global default with per-call override. No per-pipeline configuration.**

```
// Global default (set in REPL or config file)
dtw/set! "sample-size" 200          // REPL preview shows 200 rows
dtw/set! "describe-sample-size" 5000 // describe uses 5000-row sample

// Per-call override
data |> describe 1000               // override describe sample size
data |> sample 50                   // explicit 50-element sample
```

**Configuration keys and defaults**:
| Key | Default | Description |
|---|---|---|
| `sample-size` | 100 | REPL micro-sample for preview and tap! |
| `describe-sample-size` | 1000 | Sample size for describe/histogram |
| `max-collect-rows` | nil (unlimited) | Safety cap for collect (nil = no limit) |
| `print-width` | 120 | Table display width in REPL |

**Storage**: A single atom in the `datatwist.config` namespace. `dtw/set!` and `dtw/get` read/write from this atom. Environment variables can override defaults: `DT_SAMPLE_SIZE=500`.

**Rationale**: Per-pipeline configuration adds complexity with minimal benefit. The global default covers 95% of use cases. Per-call overrides handle the rest. Environment variables allow CI/batch scripts to set defaults without code changes.

---

## 3. Sampling Model -- Detailed Design

### 3.1 Sampling Strategy: First-N

The REPL uses **first-N sampling** (not random sampling) by default. This means:

```
data |> filter _.active |> sort-by _.score
// REPL takes first 100 elements of the final sorted, filtered result
```

First-N is chosen because:
1. It preserves pipeline ordering. After `sort-by`, the first N elements are the top-N.
2. It is O(N) -- just `(take N coll)` on the lazy output.
3. Random sampling would require knowing the collection size first, which defeats laziness.

The `sample N` function provides true random sampling for exploration:
```
data |> sample 50    // 50 random elements (requires full traversal)
```

### 3.2 REPL Display Protocol

When the REPL evaluates an expression that produces a lazy sequence:

1. **Type check**: Is the result a lazy sequence / ISeq?
   - If scalar, string, map, or vector: display directly (no sampling)
   - If lazy sequence: proceed to sampling

2. **Take sample**: `(take (inc sample-size) result)` -- take one extra to detect "more data available"
   - If we got <= sample-size elements: display all, no estimate
   - If we got sample-size+1 elements: display sample-size, show "...and more"

3. **Display format**:
   - Collection of maps -> tabular format with column headers
   - Collection of scalars -> bracketed list
   - Mixed -> bracketed list

4. **Estimate** (future, not v1): For database sources, use `COUNT(*)` or table statistics to show an estimated total. For file sources, use file size / average row size. For in-memory, the estimate is "lazy" (unknown until forced).

### 3.3 Sampling and Database Sources

For database-backed pipelines, sampling is pushed down:

```
db |> table "users" |> filter _.active |> sort-by _.name
// REPL sampling generates:
// SELECT * FROM users WHERE active = true ORDER BY name ASC LIMIT 100
```

The pushdown optimizer (see `docs/pushdown-design.md`) handles this. The sample size becomes a LIMIT clause at the SQL level, avoiding transfer of unnecessary rows.

### 3.4 Sampling and File Sources

For file-backed pipelines, the first N rows are read and the reader pauses:

```
read-csv "10gb-file.csv" |> filter _.region = "EU"
// REPL reads rows until 100 EU rows are found, then stops
```

The lazy sequence from `read-csv` is backed by a buffered reader. The reader advances only as `take` requests elements. After 100 elements are displayed, the reader is in a paused state. If the user later calls `collect`, reading resumes from where it stopped (for lazy sequences backed by `line-seq`).

---

## 4. Architecture

### 4.1 Clojure Primitives to Use

| DataTwist concept | Clojure primitive | Phase |
|---|---|---|
| Lazy pipeline result | `clojure.lang.LazySeq` via `lazy-seq`, `map`, `filter`, `take` | Phase 1 |
| Pipeline fusion | `clojure.core/sequence` with composed transducer | Phase 2 |
| Inspectable intermediate | `clojure.core/eduction` | Phase 2 |
| Materialized result | `clojure.lang.PersistentVector` via `(vec ...)` or `(into [] ...)` | Phase 1 |
| Infinite sequence | `clojure.core/iterate`, `clojure.core/repeat`, `clojure.core/range` | Phase 1 |
| Configuration | `clojure.core/atom` | Phase 1 |
| File streaming | `clojure.java.io/reader` + `line-seq` | Phase 1 |
| DB streaming | `next.jdbc/plan` (lazy result set traversal) | Phase 2 |

### 4.2 Required Changes to Stdlib

The primary change is removing `(vec ...)` wrappers from collection-returning functions. Current state:

```clojure
;; CURRENT (eager -- wraps in vec)
(defn- dt-filter [coll pred]  (vec (filter pred coll)))
(defn- dt-map    [coll f]     (vec (map f coll)))
(defn- dt-take   [coll n]     (vec (take n coll)))
(defn- dt-drop   [coll n]     (vec (drop n coll)))
(defn- dt-range  [...]        (vec (range ...)))
```

Required change:

```clojure
;; LAZY (returns lazy sequence)
(defn- dt-filter [coll pred]
  (if (nil? coll) [] (filter pred coll)))

(defn- dt-map [coll f]
  (cond
    (nil? coll)        []
    (map? coll)        (map (fn [[k v]] (f {:key k :value v})) coll)
    (sequential? coll) (map f coll)
    :else (throw ...)))

(defn- dt-take [coll n]
  (take n coll))

(defn- dt-drop [coll n]
  (drop n coll))

;; range returns lazy range
"range" (fn
          ([n]           (range n))
          ([start end]   (range start end))
          ([start end s] (range start end s)))
```

**Eager functions remain eager** (they need all data):
```clojure
(defn- dt-sort    [coll]   (vec (sort coll)))          ;; returns vector
(defn- dt-sort-by [coll f] (vec (sort-by f coll)))     ;; returns vector
(defn- dt-reverse [coll]   (vec (reverse coll)))        ;; returns vector
(defn- dt-collect [coll]   (if (vector? coll) coll (vec coll)))
```

### 4.3 New Stdlib Functions

```clojure
;; Materialization
"collect"   (fn [coll] (if (vector? coll) coll (vec coll)))
"force!"    (fn [data] (let [v (if (vector? data) data (vec data))] v))

;; Infinite sequences
"repeat"    (fn ([v] (repeat v))
               ([n v] (take n (repeat v))))
"iterate"   (fn [f init] (iterate f init))
"cycle"     (fn [coll] (cycle coll))

;; Exploration
"describe"  dt-describe     ;; see Section 5
"schema"    dt-schema
"histogram" dt-histogram
"freq"      dt-freq
"sample"    dt-sample
"explain"   dt-explain

;; Data sources (stubs for Phase 2)
"connect"    dt-connect
"table"      dt-table
"query"      dt-query
"read-csv"   dt-read-csv
"read-json"  dt-read-json
"read-jsonl" dt-read-jsonl
"read-lines" dt-read-lines
"read-parquet" dt-read-parquet
"close!"     dt-close!

;; Configuration
"dtw/set!"  dt-set-config!
"dtw/get"   dt-get-config
"dtw/inspect" dt-inspect
```

### 4.4 Pipeline Evaluation Changes

The current `eval-pipeline` in `evaluator.clj` uses `reduce`:

```clojure
(defn- eval-pipeline [data steps env]
  (reduce (fn [d step-node]
            (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
              (step-fn d)))
          data
          steps))
```

This is already compatible with laziness. When `step-fn` is `dt-filter` (which returns a lazy sequence), the `reduce` eagerly builds the chain of lazy sequences but does not force evaluation. The result of `eval-pipeline` is a lazy sequence (the output of the last step), and nothing is evaluated until something downstream forces it.

**No changes needed to `eval-pipeline` for Phase 1.** The laziness comes from the stdlib functions returning lazy sequences instead of vectors.

For Phase 2 (transducer fusion), `eval-pipeline` would detect chains of fusible operations and compile them to a single transducer application. This is an optimization, not a correctness change.

### 4.5 DataSource Protocol (Phase 2)

```clojure
(defprotocol DataSource
  (source-type [this])        ;; :db, :csv, :json, :parquet, :memory
  (lazy-seq-of [this])        ;; Returns a lazy sequence of rows
  (estimated-count [this])    ;; Estimated row count (or nil if unknown)
  (schema-of [this])          ;; Column names and types
  (pushdown-capable? [this])  ;; Can operations be pushed to the source?
  (close-source! [this]))     ;; Release resources

(defprotocol Pushable
  (push-filter [this pred-ast])   ;; Push a filter predicate
  (push-sort   [this field dir])  ;; Push a sort operation
  (push-limit  [this n])          ;; Push a row limit
  (push-select [this fields])     ;; Push column selection
  (execute     [this]))           ;; Execute and return lazy seq of results
```

Database and file sources implement these protocols. In-memory collections do not (they use standard Clojure lazy sequence operations).

---

## 5. API Surface

### 5.1 Core Materialization Functions

```
// Force to vector
data |> collect                    // [val1 val2 val3 ...]

// Materialize in pipeline (passthrough)
data |> filter f |> force! |> map g |> save! "out.json"

// Count (forces full traversal)
data |> filter f |> count          // 42

// First element (forces minimal evaluation)
data |> filter f |> first          // {name: "Alice"}

// Reduce
data |> map _.score |> reduce [a b -> a + b] 0   // 285
```

### 5.2 Exploration Functions

```
// Statistical summary (samples 1000 rows by default)
data |> describe
// => {columns: [{name: "age" type: "integer" min: 18 max: 65 mean: 32.4 nulls: 3} ...]}

data |> describe 5000              // override sample size

// Schema (samples 100 rows for type inference)
data |> schema
// => [{name: "name" type: "string"} {name: "age" type: "integer"} ...]

// Random sample
data |> sample 20                  // 20 random elements

// Frequency table (forces full traversal)
data |> freq _.status
// => [{value: "active" count: 7500 pct: 75.0} {value: "inactive" count: 2500 pct: 25.0}]

// Histogram (samples 1000 rows)
data |> histogram _.age
// => {bins: [{lo: 18 hi: 25 count: 234} {lo: 25 hi: 35 count: 456} ...]}

// Execution plan (no data access)
data |> explain
// => "scan: users | filter: _.active | sort: _.name ASC | limit: 100"
```

### 5.3 Debugging Functions

```
// tap! -- inline sample display (passthrough)
data |> filter f |> tap! |> map g |> tap! |> sort-by _.score

// tap! with label
data |> filter f |> tap! "after filter"

// tap! with transformation function (display only)
data |> filter f |> tap! [d -> d |> map _.score |> describe]
```

### 5.4 Infinite Sequence Generators

```
range 1 100          // lazy range [1, 100)
range 1              // infinite: 1, 2, 3, ...
repeat "x"           // infinite: "x", "x", "x", ...
repeat 5 "x"         // finite: ["x" "x" "x" "x" "x"]
iterate [n -> n * 2] 1  // infinite: 1, 2, 4, 8, 16, ...
```

### 5.5 Configuration

```
dtw/set! "sample-size" 200
dtw/get "sample-size"              // 200

// Pipeline inspection (for IDE integration)
plan is data |> filter f |> map g |> sort-by _.score
dtw/inspect plan 1 50              // 50-element sample after step 1 (filter)
```

---

## 6. Integration with Existing Pipeline

### 6.1 What Changes

The lazy evaluation system integrates with the current codebase through minimal, focused changes:

**1. `src/datatwist/stdlib.clj`** -- Remove `(vec ...)` wrappers from lazy-compatible functions:
- `dt-filter`: `(vec (filter ...))` -> `(filter ...)`
- `dt-map`: `(vec (map ...))` -> `(map ...)`
- `dt-take`: `(vec (take ...))` -> `(take ...)`
- `dt-drop`: `(vec (drop ...))` -> `(drop ...)`
- `dt-distinct`: `(vec (distinct ...))` -> `(distinct ...)`
- `dt-flatten`: `(vec (apply concat ...))` -> `(apply concat ...)`
- `range`: `(vec (range ...))` -> `(range ...)`
- `rest`: `(comp vec rest)` -> `rest`

**2. `src/datatwist/stdlib.clj`** -- Add new functions:
- `collect`, `force!`, `repeat`, `iterate`, `cycle`
- `describe`, `schema`, `histogram`, `freq`, `sample`, `explain`
- `dtw/set!`, `dtw/get`, `dtw/inspect`

**3. `src/datatwist/evaluator.clj`** -- No changes for Phase 1. The `eval-pipeline` function already works with lazy sequences because `reduce` builds the lazy chain without forcing evaluation.

**4. New file: `src/datatwist/config.clj`** -- Global configuration atom and accessors.

**5. New file: `src/datatwist/explore.clj`** -- Implementation of `describe`, `schema`, `histogram`, `freq`, `explain`.

### 6.2 What Does NOT Change

- The grammar (`resources/datatwist.grammar`) -- no new syntax needed
- The parser (`src/datatwist/parser.clj`) -- lazy evaluation is a runtime concern
- The evaluator's pipeline evaluation logic (`eval-pipeline`, `eval-pipe-atom-with-fn-call`) -- these already compose correctly with lazy sequences
- All existing tests for features 1-7 -- they use `(vec result)` or direct equality, both of which force lazy sequences and compare correctly

### 6.3 Test Compatibility

Existing tests that compare pipeline results with vectors will continue to pass because Clojure's `=` forces lazy sequences for comparison. For example:

```clojure
(is (= [3 4 5] (eval-dt "[1 2 3 4 5] |> filter _ > 2")))
;; eval-dt now returns a lazy seq, but (= [3 4 5] lazy-seq) forces it and compares
```

The only tests that may need updating are those that assert the result type:
```clojure
;; This may fail if dt-filter now returns LazySeq instead of PersistentVector
(is (vector? (eval-dt "[1 2 3] |> filter _ > 1")))
```

These are expected changes -- the lazy eval tests explicitly check for lazy types.

---

## 7. Risk Analysis

### 7.1 Breaking Existing Tests

**Risk**: Removing `(vec ...)` from stdlib functions changes return types from `PersistentVector` to `LazySeq`. Any test that checks `(vector? result)` or `(instance? PersistentVector result)` will break.

**Mitigation**: Audit all test files for type assertions. The pattern `(is (= expected result))` is safe (lazy seqs compare by value). Only explicit type checks need updating. Tests in features 1-7 should primarily use value equality.

**Severity**: Low. These are test-level changes, not behavioral changes.

### 7.2 Head Retention in Evaluator

**Risk**: If the evaluator binds a lazy sequence to a name and then traverses it (e.g., for `count`), the named binding holds the head, preventing GC.

**Example**:
```
big-data is range 1 100000000
big-data |> count    // head retained because "big-data" is in the environment
```

**Mitigation**: This is inherent to any language with named bindings and lazy sequences. Clojure itself has this behavior. For DataTwist:
- Document it: "If you bind a large lazy sequence and then force it, the entire realized sequence stays in memory."
- `force!` converts to a vector, which is a concrete data structure -- but it is also fully in memory.
- For streaming scenarios (file -> filter -> save!), avoid binding the intermediate: `read-csv "f.csv" |> filter f |> save! "out.csv"` does not retain the head because the pipeline is a single expression.

**Severity**: Medium. This is a known footgun in Clojure. DataTwist inherits it.

### 7.3 Chunking Defeats Fine-Grained Laziness

**Risk**: Clojure realizes lazy sequences in chunks of 32. If a `tap!` call expects to see exactly 100 elements, it may trigger 128 (4 chunks) filter evaluations.

**Mitigation**: This is cosmetic -- the user sees 100 elements (because `take 100` returns exactly 100), but the filter processed up to 128 source elements. This is acceptable and consistent with Clojure behavior.

**Severity**: Low. Invisible to the user.

### 7.4 File Reader Lifecycle

**Risk**: Lazy sequences backed by file readers (from `read-csv`, `read-lines`) hold file handles open. If the lazy sequence is never fully consumed, the file handle leaks.

**Mitigation**:
- `close!` explicitly closes the underlying reader
- `collect`, `force!`, `save!` fully consume the sequence, closing the reader
- GC finalizer on the reader (JVM does this for `BufferedReader`)
- Document: "Always consume or close file-backed pipelines"

**Severity**: Medium. File handle leaks are a real concern for long-running REPL sessions.

### 7.5 Double Evaluation of Lazy Pipelines

**Risk**: Binding a lazy pipeline and using it twice causes double evaluation:
```
filtered is data |> filter expensive-fn
filtered |> count    // evaluates filter
filtered |> collect  // evaluates filter AGAIN (if not cached)
```

**Mitigation**: Clojure lazy sequences cache their results. Once an element is realized, it is stored. So `filtered |> count` realizes and caches, then `filtered |> collect` reads from cache. However, for file-backed sources, the source may not support replay. In that case, `force!` should be used to materialize before reuse.

**Severity**: Low for in-memory data (caching handles it). Medium for file sources (need `force!`).

### 7.6 Interaction with sort-by / group-by

**Risk**: `sort-by` is inherently eager -- it must see all elements. In a pipeline like `data |> filter f |> sort-by g |> take 10`, the filter is lazy but sort-by forces full evaluation of the filtered set before take can operate.

**Mitigation**: This is correct behavior and matches user expectations. The optimization (for database sources) is to push sort-by to SQL `ORDER BY` + `LIMIT`. For in-memory sources, the user can:
```
// If only top-10 is needed, consider:
data |> filter f |> take 10 |> sort-by g    // sort only 10 elements
// vs
data |> filter f |> sort-by g |> take 10    // sort ALL, then take 10
```

Document the difference in the DataTwist guide.

**Severity**: Low. This is inherent to sorting and is well-understood.

---

## 8. Implementation Roadmap

### Phase 1: Core Laziness (estimated: 1-2 days)

**Goal**: All BDD Section 1-4 tests pass. Pipeline results are lazy sequences. Materialization functions work correctly.

**Tasks**:
1. **Modify `stdlib.clj`**: Remove `(vec ...)` from `dt-filter`, `dt-map`, `dt-take`, `dt-drop`, `dt-distinct`, `dt-flatten`, `range`, `rest`. Keep `dt-sort`, `dt-sort-by`, `dt-reverse`, `dt-group-by` eager.
2. **Add `collect` to stdlib**: `(fn [coll] (if (vector? coll) coll (vec coll)))`
3. **Add `force!` to stdlib**: `(fn [data] (let [v (vec data)] v))` -- passthrough semantics (returns the materialized vector)
4. **Update `tap!`**: Current implementation already works (`(fn ([data] (println data) data)`). Update to sample: `(take 100 data)` for display, return `data`.
5. **Add `repeat`, `iterate`** to stdlib.
6. **Run BDD Section 1-4 tests**. Fix any test failures.

**Test verification**: Run `clj -M -e "(require 'clojure.test 'datatwist.lazy-eval-test) (clojure.test/run-tests 'datatwist.lazy-eval-test)"` after each change.

### Phase 2: Exploration Functions (estimated: 2-3 days)

**Goal**: BDD Section 8 tests pass. `describe`, `schema`, `sample`, `freq`, `histogram`, `explain` are implemented.

**Tasks**:
1. **Create `src/datatwist/explore.clj`** with implementations of:
   - `dt-describe`: Sample N rows, compute per-column stats (type, min, max, mean, null-count, distinct-count)
   - `dt-schema`: Sample 100 rows, infer types from values
   - `dt-sample`: Reservoir sampling for random N elements
   - `dt-freq`: Full traversal, group by field value, compute count/percentage
   - `dt-histogram`: Sample rows, compute bins for numeric field
   - `dt-explain`: Return a string describing the pipeline steps (v1: simple step listing)
2. **Create `src/datatwist/config.clj`** with global configuration atom
3. **Wire into `stdlib.clj`**
4. **Run BDD Section 8 tests**

### Phase 3: Data Sources (estimated: 3-5 days)

**Goal**: BDD Sections 5-6 tests pass. File and database sources work as lazy sequences.

**Tasks**:
1. **Implement `read-csv`**: Lazy sequence of maps using `clojure.java.io/reader` + `line-seq` + CSV parsing
2. **Implement `read-json`, `read-jsonl`, `read-lines`**: Similar lazy streaming
3. **Implement `read-parquet`**: Requires a Parquet library (e.g., `org.apache.parquet/parquet-hadoop`)
4. **Implement `connect`, `table`, `query`**: Using `next.jdbc` with lazy result set handling via `next.jdbc/plan`
5. **Implement `save!`**: Dispatch on file extension (CSV, JSON, Parquet)
6. **Implement `into!`**: Batch insert via `next.jdbc`
7. **Implement `close!`**: Close connections/readers

### Phase 4: SQL Pushdown (estimated: 3-5 days)

**Goal**: BDD Section 7 tests pass. Pipeline operations on database sources generate optimized SQL.

**Depends on**: `docs/pushdown-design.md` implementation. This phase is detailed in that design doc and is listed here for completeness.

### Phase 5: Transducer Fusion (estimated: 2-3 days)

**Goal**: Performance optimization. Consecutive lazy operations in a pipeline are fused into a single transducer pass.

**Tasks**:
1. **Detect fusible chains**: In `eval-pipeline`, identify consecutive steps where the stdlib function has a transducer arity (filter, map, take, drop, distinct)
2. **Compose transducers**: Build `(comp (filter f) (map g) (take n))`
3. **Apply via `sequence`**: `(sequence composed-xf data)` returns a lazy sequence with fused operations
4. **Benchmark**: Compare fused vs. unfused on large datasets

**This phase is optional for v1.** Lazy sequences already provide correct behavior. Transducer fusion is a performance optimization that can be added transparently.

### Phase 6: REPL Integration (estimated: 2-3 days)

**Goal**: The REPL auto-samples lazy sequences, displays tables, shows estimates.

**Tasks**:
1. **REPL print hook**: Detect lazy sequences in REPL output, sample and display as table
2. **Table formatter**: ASCII table for collections of maps, bracketed list for scalars
3. **Estimate display**: Show "~N rows" when sample is smaller than total
4. **nREPL middleware**: `dtw/inspect` for IDE integration (accepts `{:file :line}`, returns sample)

---

## Sources

- [Clojure's Deadly Sin (head retention, lazy seq pitfalls)](https://clojure-goes-fast.com/blog/clojures-deadly-sin/)
- [Fixing Lazy Sequence and Transducer Performance Issues in Clojure](https://www.mindfulchase.com/explore/troubleshooting-tips/programming-languages/fixing-lazy-sequence-and-transducer-performance-issues-in-clojure.html)
- [Laziness and Chunking in Clojure](https://www.tianxiangxiong.com/2016/11/05/chunking-and-laziness-in-clojure.html)
- [Reducers, Transducers and core.async in Clojure](https://eli.thegreenplace.net/2017/reducers-transducers-and-coreasync-in-clojure/)
- [Defaulting to Transducers](https://dawranliou.com/blog/default-transducers/)
- [Clojure Transducers Reference](https://clojure.org/reference/transducers)
- [Haskell Lazy Evaluation (HaskellWiki)](https://wiki.haskell.org/Lazy_evaluation)
- [Haskell Performance/Strictness](https://wiki.haskell.org/Performance/Strictness)
- [Space Leaks Exploration in Haskell (Stanford)](https://cs.stanford.edu/~sumith/docs/report-spaceleaks.pdf)
- [Polars LazyFrame Documentation](https://docs.pola.rs/py-polars/html/reference/lazyframe/index.html)
- [LazyFrame vs DataFrame in Polars](https://stuffbyyuki.com/lazyframe-vs-dataframe-in-polars-performance-comparison/)
- [DuckDB Optimizers: The Low-Key MVP](https://duckdb.org/2024/11/14/optimizers)
- [DuckDB Samples Documentation](https://duckdb.org/docs/stable/sql/samples)
- [Lazy Evaluation Queries with dplyr](https://smithjd.github.io/sql-pet/chapter-lazy-evaluation-queries.html)
- [On the Design, Implementation, and Use of Laziness in R (arXiv)](https://arxiv.org/pdf/1909.08958)
- [Spark vs Polars Real-life Test Case](https://dataengineeringcentral.substack.com/p/spark-vs-polars-real-life-test-case)
