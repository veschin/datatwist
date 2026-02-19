# Lazy Evaluation: Pipeline Introspection & Step Caching Research

Date: 2026-02-19

## 1. Executive Summary

This document redesigns the DataTwist pipeline from an eager `reduce`-based evaluator to a **reified pipeline object** that remembers its steps, caches per-step data samples, and integrates with IDE tooling for interactive inspection.

**Key architectural decisions:**

1. **Reified pipeline**: `|>` produces a `DTPipeline` record containing a steps vector, cached samples per step, source metadata, and a lazy evaluation thunk. Terminal operations (`collect`, `count`, `first`, `reduce`) trigger actual computation.
2. **Auto-caching**: Each step caches its SAMPLE_SIZE sample immediately after execution. Total memory overhead is negligible (~200KB for 10 steps at 100 rows/200 bytes each).
3. **No compression needed**: At the scale of cached samples, Clojure's structural sharing already provides sufficient memory efficiency. Compression adds latency and complexity for no measurable gain.
4. **`tap!` as sole debug probe**: `tap!` replaces `inspect`, `log!`, and `print` in the pipeline vocabulary. It supports bare invocation (show sample), string label, or lambda for custom formatting.
5. **Constants as uppercase symbols**: `SAMPLE_SIZE`, `MAX_COLLECT_ROWS`, `DESCRIBE_SAMPLE_SIZE` are pre-bound symbols that resolve to mutable config values, settable via `dtw/set!`.
6. **IDE step inspection via nREPL**: An `inspect-pipeline-step` nREPL op returns cached sample data for any step, enabling overlay display in Emacs/CIDER and VS Code/Calva.

**Impact on current implementation**: The evaluator's `eval-pipeline` function must change from `reduce`-based eager chaining to a pipeline-building function that returns a `DTPipeline` record. The grammar requires no changes. The stdlib gains `tap!` redesign and constants. Roughly 150-200 lines of new code in evaluator + pipeline namespace, 50-100 lines in stdlib changes.

---

## 2. Reified Pipeline Design

### 2.1 What "Reified" Means

Currently, `eval-pipeline` uses `reduce` to chain step functions eagerly:

```clojure
;; Current: evaluator.clj lines 1401-1408
(defn- eval-pipeline [data steps env]
  (reduce (fn [d step-node]
            (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
              (step-fn d)))
          data
          steps))
```

This evaluates each step immediately and discards intermediate results. The pipeline is "transparent" -- it leaves no trace of its structure at runtime.

A **reified pipeline** is a first-class runtime object that:
- Knows its steps (source code labels, source locations, step functions)
- Caches samples of data at each step boundary
- Defers actual computation until a terminal operation demands it
- Can be inspected, explained, and optimized

### 2.2 The DTPipeline Record

```clojure
(defrecord DTPipeline
  [source        ;; The initial data (collection, lazy seq, DataSource, etc.)
   steps         ;; Vector of PipelineStep records
   env           ;; Captured lexical environment
   sample-cache  ;; Atom: vector of cached samples, one per step + source
   realized?     ;; Atom: boolean, has the pipeline been fully forced?
   metadata])    ;; {:loc {:line N :col N}, :file "...", :created-at <inst>}

(defrecord PipelineStep
  [step-fn       ;; fn :: data -> result (the compiled step)
   ast-node      ;; The PipeAtom AST node (for explain)
   label         ;; String: human-readable description, e.g. "filter _.active"
   loc           ;; {:line N :col N} source location
   index])       ;; 0-based step number
```

**Sample cache structure**: The `sample-cache` atom holds a vector of `(count steps + 1)` entries (source + each step output). Each entry is either `nil` (not yet cached) or a vector of up to SAMPLE_SIZE rows.

```clojure
;; Example: 3-step pipeline
;; sample-cache = [source-sample, step-0-sample, step-1-sample, step-2-sample]
;;              = [[{:name "Alice" ...} ...], [{:name "Active1" ...} ...], ...]
```

### 2.3 How Lazy Evaluation Interacts with Reification

The pipeline object is built eagerly (step functions are compiled, metadata is captured), but **data does not flow through the steps until a terminal operation triggers it.**

**Build phase** (eager, happens at `|>` parse time):
1. Parse `data |> filter _.active |> map _.name |> sort-by _`
2. Evaluate `data` to get the source collection
3. Compile each PipeAtom into a step function via `eval-pipe-atom-with-fn-call`
4. Construct a `DTPipeline` record with the source, steps, and an empty sample cache
5. Return the `DTPipeline` object (no data flows yet)

**Execute phase** (lazy, triggered by terminal ops):
1. A terminal operation (`collect`, `count`, `first`, `reduce`) receives a `DTPipeline`
2. It walks the steps sequentially, feeding source through each step-fn
3. At each step boundary, it captures a sample (first SAMPLE_SIZE rows) into the cache
4. It returns the final result

**Key insight**: In Phase 1, steps still execute eagerly once triggered (Clojure lazy sequences handle per-element laziness). The reification adds **structure** (steps, labels, cache), not additional laziness. In Phase 2, transducer fusion can optimize the execution while preserving the reified structure for inspection.

### 2.4 Terminal Operations Trigger Evaluation

```clojure
(defn realize-pipeline
  "Execute a DTPipeline, caching samples at each step. Returns final result."
  [pipeline]
  (let [{:keys [source steps sample-cache]} pipeline
        sample-size (config/get :SAMPLE_SIZE)]
    ;; Cache source sample
    (swap! sample-cache assoc 0 (vec (take sample-size source)))
    ;; Walk steps
    (loop [data source
           idx  0]
      (if (>= idx (count steps))
        data  ;; Final result
        (let [step    (nth steps idx)
              result  ((:step-fn step) data)
              sample  (vec (take sample-size
                            (if (sequential? result) result [result])))]
          (swap! sample-cache assoc (inc idx) sample)
          (recur result (inc idx)))))))
```

Terminal operations detect `DTPipeline` and call `realize-pipeline`:

```clojure
(defn dt-collect [x]
  (if (instance? DTPipeline x)
    (vec (realize-pipeline x))
    (vec x)))

(defn dt-count [x]
  (if (instance? DTPipeline x)
    (count (realize-pipeline x))
    (count x)))

(defn dt-first [x]
  (if (instance? DTPipeline x)
    (first (realize-pipeline x))
    (first x)))
```

### 2.5 Precedent: How Spark, Polars, and dbt Handle This

**Apache Spark**:
- Transformations (map, filter, flatMap, join, groupBy) are lazy -- they build a DAG of operations but execute nothing.
- Actions (collect, count, first, saveAsTextFile, reduce) trigger execution.
- The Catalyst optimizer generates an unresolved logical plan, resolves it, optimizes it (predicate pushdown, projection pruning, join reordering), and generates physical execution plans.
- Spark UI shows per-stage data: input/output rows, shuffle read/write, task duration.
- `df.explain()` prints the logical and physical plans as text.

Sources: [Apache Spark Architecture](https://www.datacamp.com/blog/apache-spark-architecture), [Spark Execution Plan Tutorial](https://www.developerindian.com/articles/understanding-spark-execution-plan-a-complete-tutorial), [Spark Execution Planning](https://medium.com/@aj.patil9292/understanding-spark-execution-planning-from-code-to-cluster-66ea1bd372df)

**Polars**:
- `LazyFrame` builds a deferred query plan; `.collect()` materializes it.
- `.explain()` prints the optimized query plan showing predicate pushdown, projection pushdown.
- `.describe_plan()` prints the unoptimized plan; `.describe_optimized_plan()` prints the optimized plan; `.show_graph()` renders as Graphviz.
- Key optimization: Polars can scan data "backwards" from the terminal -- if only 10 rows are needed, it pushes `LIMIT 10` to the scan.

Sources: [Polars Lazy API Guide](https://docs.pola.rs/user-guide/concepts/lazy-api/), [polars.LazyFrame.explain](https://docs.pola.rs/api/python/stable/reference/lazyframe/api/polars.LazyFrame.explain.html), [Real Python: Polars LazyFrame](https://realpython.com/polars-lazyframe/)

**dbt**:
- Uses a `manifest.json` from a previous run to understand project state (models, tests, dependencies).
- `--defer` flag compares current state against prior manifest, only re-executes changed models.
- Not directly analogous to DataTwist (dbt is a build tool, not a runtime), but the concept of a **manifest that describes pipeline structure** is relevant to `explain`.

Sources: [dbt Defer](https://docs.getdbt.com/docs/cloud/about-cloud-develop-defer)

### 2.6 Clojure Transducers: Zero-Copy Pipeline Composition

Transducers compose transformations without intermediate collections:

```clojure
;; Traditional: 3 intermediate lazy sequences
(->> coll (map f) (filter g) (take n))

;; Transducer: zero intermediate collections
(sequence (comp (map f) (filter g) (take n)) coll)
```

Key properties:
- `comp` composes transducers left-to-right (the first transducer listed is the first applied)
- `sequence` applies a transducer lazily, returning a lazy sequence
- `eduction` creates a reducible, replayable view without caching -- useful for "inspect and continue"
- Transducers are ~10x faster than chained lazy sequences (source: clojure-goes-fast benchmarks: ~44us/6KB vs ~410us/480KB)

**Relevance to DataTwist**: In Phase 2, consecutive fusible steps (filter, map, take, drop, distinct) can be detected at pipeline-build time and compiled to a single transducer. The reified pipeline still records individual steps for inspection, but execution fuses them. `eduction` is useful for step inspection: `(eduction (comp step-1-xf step-2-xf) source)` gives a replayable view of data after step 2 without caching.

Sources: [Clojure Transducers Reference](https://clojure.org/reference/transducers), [Grammarly ETL with Transducers](https://www.grammarly.com/blog/engineering/building-etl-pipelines-with-clojure-and-transducers/), [Grokking Transducers](https://dev.solita.fi/2021/10/14/grokking-clojure-transducers.html)

### 2.7 Recommended Pipeline Data Structure

```clojure
;; Construction: at |> parse time
(defn build-pipeline [source step-nodes env]
  (let [steps (vec (map-indexed
                     (fn [idx step-node]
                       (->PipelineStep
                         (eval-pipe-atom-with-fn-call step-node env)
                         step-node
                         (step-label step-node)  ;; extract human-readable label from AST
                         (step-loc step-node)     ;; extract {:line :col} from AST meta
                         idx))
                     step-nodes))]
    (->DTPipeline
      source
      steps
      env
      (atom (vec (repeat (inc (count steps)) nil)))  ;; sample-cache
      (atom false)                                    ;; realized?
      {:created-at (java.time.Instant/now)})))
```

**The grammar does NOT change.** The `Pipeline` rule still produces `PipeAtom (_ <'|>'> _ PipeAtom)+`. The change is in `eval-node` for the `:Pipeline` tag: instead of calling `eval-pipeline` (which reduces eagerly), it calls `build-pipeline` (which returns a `DTPipeline`).

---

## 3. Step Caching with Compression

### 3.1 Python's Compact Dict (PEP 412)

The user referenced "Python's compact dict" -- this refers to two related CPython optimizations:

**PEP 412 (Python 3.3): Key-Sharing Dictionaries.**
- Instance `__dict__` dictionaries that share the same keys (all instances of a class) share a single keys table and only allocate separate values arrays.
- Memory reduction: ~50% for attribute dictionaries, 10-20% overall for OOP programs.
- Mechanism: the type (class) caches a keys table; each instance's `__dict__` points to that shared keys table plus its own compact values array.

**CPython 3.6 Compact Dict (Raymond Hettinger proposal, first implemented by PyPy):**
- Hash table stores indices (1/2/4/8 bytes each depending on size) pointing into a dense keys+values table.
- 20-25% less memory vs Python 3.5 dicts.
- Side effect: preserves insertion order.

Sources: [PEP 412](https://peps.python.org/pep-0412/), [New dict in Python 3.6 (Speaker Deck)](https://speakerdeck.com/methane/new-dict-implementation-in-python-3-dot-6)

**Relevance to DataTwist**: PEP 412's key-sharing is directly analogous to Clojure's **structural sharing** in persistent data structures. When DataTwist caches 100 maps that all have the same keys (rows from the same table), Clojure's persistent hash maps already share the key structure. A vector of 100 `{:name "..." :age N :city "..."}` maps shares the keyword objects and much of the trie structure. This is the JVM equivalent of PEP 412 -- it happens automatically.

### 3.2 JVM Compression Options

**Compressed OOPs (Ordinary Object Pointers):**
- JVM automatically compresses 64-bit object pointers to 32-bit when heap < 32GB.
- Object alignment at 8-byte boundaries means 3 bits can be reconstructed from alignment.
- Enabled by default since Java 7 for heaps < 32GB.
- Not user-controllable per-object; it is a JVM-wide setting.

Sources: [Baeldung: Compressed OOPs](https://www.baeldung.com/jvm-compressed-oops), [OpenJDK Wiki: CompressedOops](https://wiki.openjdk.org/display/HotSpot/CompressedOops)

**LZ4-java:**
- Compression: >500 MB/s per core. Decompression: multiple GB/s per core.
- Compression ratio: ~0.58 (42% space saving).
- Minimal memory overhead; hash-table-based, good for small blocks.
- Java binding: `net.jpountz.lz4/lz4-java` (community fork active after original project discontinued).

**Snappy:**
- Google's compression targeting sub-microsecond latencies.
- Similar ratio to LZ4 (~0.58), slightly faster compression, slightly slower decompression.
- Java binding: `org.xerial.snappy/snappy-java`.

**Nippy (Clojure serialization with compression):**
- Fastest known Clojure serialization library. 10-15x faster than `tools.reader.edn`, 40% smaller output.
- Built-in compression options: LZ4 (default), Snappy, LZMA2.
- LZ4 via Nippy: compress 240ms, decompress 30ms (reference benchmark).
- `(nippy/freeze data)` -> byte array; `(nippy/thaw bytes)` -> original data.

Sources: [Nippy GitHub](https://github.com/taoensso/nippy), [LZ4-java GitHub](https://github.com/lz4/lz4-java), [Java Compression Performance](https://dkomanov.medium.com/java-compression-performance-fb373078cfde)

### 3.3 Memory Budget Calculation

Parameters:
- `SAMPLE_SIZE` = 100 rows
- Average row = 200 bytes (a map with 5-10 fields of mixed types)
- Typical pipeline = 10 steps

**Per-step cache**: 100 rows * 200 bytes = 20,000 bytes = ~20 KB
**Total cache for 10 steps + source**: 11 * 20 KB = ~220 KB
**With Clojure structural sharing**: Adjacent steps often produce rows that share keys and partial values. Realistic overhead is 50-70% of the naive calculation = ~130-155 KB.

**Compression analysis (if we compressed with LZ4 via Nippy):**
- 220 KB raw -> ~128 KB compressed (0.58 ratio) = ~92 KB saved
- Compress time: <0.1ms (220KB at 500 MB/s)
- Decompress time: <0.05ms (at multi-GB/s)
- Added dependency: `com.taoensso/nippy`
- Added code complexity: freeze/thaw at every cache read/write

**Verdict: Compression is NOT needed.** Saving ~92 KB per pipeline at the cost of a new dependency and serialization overhead on every cache access is not justified. 220 KB is trivial -- smaller than a single JPEG image. Even with 100 concurrent pipelines (unlikely in a REPL-centric tool), that is 22 MB.

### 3.4 Recommended Approach: Plain Clojure Vectors

```clojure
;; Cache a sample at step boundary
(defn cache-step-sample! [pipeline step-idx data]
  (let [sample-size (config/get :SAMPLE_SIZE)
        sample (vec (take sample-size
                      (if (sequential? data) data [data])))]
    (swap! (:sample-cache pipeline) assoc step-idx sample)))

;; Retrieve cached sample
(defn get-step-sample [pipeline step-idx]
  (get @(:sample-cache pipeline) step-idx))
```

**No serialization, no compression, no off-heap storage.** Just a vector of vectors in a Clojure atom. Structural sharing handles the rest.

If a future use case requires caching samples from very wide rows (1000+ columns, multi-KB per row), the solution is to reduce SAMPLE_SIZE, not to add compression.

---

## 4. tap! Redesign

### 4.1 Design Decisions

- `tap!` is the **ONLY** debug probe. Remove `inspect`, `log!`, `print` from pipeline vocabulary.
- `tap!` is a passthrough side-effect function (returns its first argument).
- `tap!` is a regular pipeline step in the reified pipeline -- it gets cached like any other step.

### 4.2 API

**Bare invocation** (no args beyond data):
```
data |> filter _.active |> tap! |> map _.name
```
Behavior: Print a sample of the current data (first SAMPLE_SIZE rows), formatted as a table. Return data unchanged.

**String label**:
```
data |> filter _.active |> tap! "after filter"
```
Behavior: Print `--- after filter ---` header, then sample table. Return data unchanged.

**Lambda for custom formatting**:
```
data |> filter _.active |> tap! [d -> str "found " (count d) " items"]
```
Behavior: Apply the lambda to the sample, print the result. Return original data unchanged.

### 4.3 The Format String Question

The PRD explicitly says: **"No interpolation"** for strings. The language uses `format` with `%s` placeholders (C-style printf):

```
greet is [name -> format "Hello, %s!" name]
```

Therefore `tap! "found {count} items"` with `{}` interpolation **violates PRD**. The alternatives:

**Option A: Lambda (recommended)**
```
data |> tap! [d -> format "found %s items" (count d)]
```
Consistent with existing `format` function, no new syntax, fully general.

**Option B: Tap with label only, lambda for everything else**
```
data |> tap! "after filter"   // label only, no interpolation
data |> tap! [d -> ...]       // lambda for custom display
```

**Option C: Special tap! format syntax**
Not recommended -- would require grammar changes and create an inconsistency with the rest of the language.

**Recommendation: Option A.** The lambda syntax `[d -> format "found %s items" (count d)]` is idiomatic DataTwist, requires no grammar changes, and is fully general. The slight verbosity is acceptable for a debug probe.

### 4.4 tap! Interaction with Reified Pipeline

In the reified pipeline, `tap!` is a step like any other. It:
1. Has a step function that prints and returns data
2. Has an AST node and label (`"tap!"` or `"tap! \"after filter\""`)
3. Gets its sample cached in the pipeline's sample-cache
4. Can be inspected via `dtw/inspect` like any step

```clojure
;; tap! step function
(defn dt-tap!
  ([data]
   (let [sample-size (config/get :SAMPLE_SIZE)
         sample (vec (take sample-size
                       (if (sequential? data) data [data])))]
     (print-table sample)
     data))
  ([data label-or-fn]
   (cond
     (string? label-or-fn)
     (do (println (str "--- " label-or-fn " ---"))
         (dt-tap! data))

     (fn? label-or-fn)
     (let [sample-size (config/get :SAMPLE_SIZE)
           sample (if (sequential? data)
                    (take sample-size data)
                    data)]
       (println (label-or-fn sample))
       data))))
```

**Key property preserved**: `tap!` returns the original data reference, not the sample. Downstream steps operate on the full data. The sample is for display only.

### 4.5 Removed Functions

The following are **removed** from the pipeline vocabulary:
- `inspect` -- replaced by `tap!` (programmatic) and `dtw/inspect` (IDE)
- `log!` -- replaced by `tap! "label"` (labels serve the logging use case)
- `print` / `println` -- these are general-purpose, not pipeline debug tools. They remain available as standalone functions but are not pipeline-idiomatic.

---

## 5. Constants Design

### 5.1 Uppercase Symbols

The user's decision: system constants use `ALL_CAPS` naming convention. These are pre-bound symbols in the DataTwist environment, not string keys.

| Constant | Default | Purpose |
|---|---|---|
| `SAMPLE_SIZE` | 100 | REPL preview, tap! sample, step cache sample |
| `MAX_COLLECT_ROWS` | nil (unlimited) | Safety cap for `collect` |
| `DESCRIBE_SAMPLE_SIZE` | 1000 | Sample size for `describe`, `histogram` |
| `PRINT_WIDTH` | 120 | Table display width in REPL |

### 5.2 Mutability Model: Mutable Config Values, Not True Constants

These should be **mutable config values** (like environment variables), not true constants. Rationale:

1. Users need to adjust `SAMPLE_SIZE` for different datasets (wide rows need smaller samples).
2. CI/batch scripts need to set `MAX_COLLECT_ROWS` as a safety net.
3. The PRD already specifies `dtw/set!` and `dtw/get` for configuration.

**True constants (immutable)** would require a different mechanism (e.g., `defconst`) and would prevent runtime tuning. This is a data exploration tool -- flexibility matters more than immutability guarantees.

### 5.3 Implementation

```clojure
;; In datatwist.config namespace
(def ^:private config-defaults
  {:SAMPLE_SIZE           100
   :MAX_COLLECT_ROWS      nil
   :DESCRIBE_SAMPLE_SIZE  1000
   :PRINT_WIDTH           120})

(def ^:private config-state (atom config-defaults))

(defn get-config [k]
  (get @config-state k))

(defn set-config! [k v]
  (if (contains? config-defaults k)
    (swap! config-state assoc k v)
    (throw (ex-info (str "Unknown config key: " k)
                    {:dt/error true :code "DT-R003" :category "RUNTIME ERROR"
                     :hint (str "Valid config keys: " (keys config-defaults))}))))
```

### 5.4 How dtw/set! and dtw/get Work with Uppercase Symbols

In the DataTwist evaluator, `SAMPLE_SIZE` resolves as a symbol lookup. Two options:

**Option A: Symbols resolve to config keys directly.**
`SAMPLE_SIZE` in DataTwist code resolves to `(config/get :SAMPLE_SIZE)` at eval time. `dtw/set! SAMPLE_SIZE 200` calls `(config/set! :SAMPLE_SIZE 200)`.

This requires special handling in the evaluator for `ALL_CAPS` identifiers -- they bypass normal environment lookup and go to config.

**Option B: Pre-bind in global environment.**
At startup, bind `SAMPLE_SIZE` -> `100`, `MAX_COLLECT_ROWS` -> `nil`, etc. in the global env. `dtw/set! SAMPLE_SIZE 200` mutates the global binding. `dtw/get SAMPLE_SIZE` reads it.

This uses existing binding infrastructure but makes constants mutable bindings (which is what they are).

**Recommendation: Option B (pre-bind in global env).** It requires no special evaluator logic for uppercase names. The `dtw/set!` function takes a symbol (not a string), looks it up in the global env, and updates it. The convention (ALL_CAPS = system config) is purely stylistic.

In the stdlib registration:

```clojure
;; Pre-bound constants in the global environment
(def system-constants
  {"SAMPLE_SIZE"          100
   "MAX_COLLECT_ROWS"     nil
   "DESCRIBE_SAMPLE_SIZE" 1000
   "PRINT_WIDTH"          120})

;; dtw/set! takes a symbol name and new value
"dtw/set!" (fn [sym-name val]
             (if (contains? system-constants sym-name)
               (do (swap! config-state assoc (keyword sym-name) val)
                   val)
               (throw ...)))

;; dtw/get takes a symbol name, returns current value
"dtw/get" (fn [sym-name]
            (get @config-state (keyword sym-name)))
```

In DataTwist code:
```
dtw/set! SAMPLE_SIZE 200        // set sample size to 200
dtw/get SAMPLE_SIZE             // returns 200
SAMPLE_SIZE                     // also returns 200 (reads from env)
```

### 5.5 Distinguishing User Bindings from Constants

Convention only -- no enforcement:
- `ALL_CAPS` = system constants (mutable config)
- `camelCase` or `snake_case` = user bindings (immutable per `is` semantics)

The evaluator does NOT prevent `x is SAMPLE_SIZE` or `SAMPLE_SIZE is 42` (the latter would shadow the config value in the local scope). This is acceptable: shadowing is a standard scoping behavior, and a linter can warn about it.

---

## 6. IDE Step Inspection

### 6.1 How CIDER's Inspector Works

CIDER's inspector uses the `orchard.inspect` library with an nREPL protocol:

| Op | Request | Response |
|---|---|---|
| `inspect-start` | `{:op "inspect-start" :code "expr"}` | Rendered inspector view |
| `inspect-push` | `{:op "inspect-push" :idx 2}` | Drill into child at index 2 |
| `inspect-pop` | `{:op "inspect-pop"}` | Back to parent |
| `inspect-refresh` | `{:op "inspect-refresh"}` | Re-render current view |

The inspector renders values as a vector of rendering instructions (strings with special markers for clickable elements). The Emacs client interprets these and renders them in an `*cider-inspect*` buffer.

CIDER's inline overlay mechanism (`cider-overlays.el`) displays `=> result` after evaluated expressions using Emacs overlay objects with `after-string` properties.

Sources: [CIDER Inspector Docs](https://docs.cider.mx/cider/debugging/inspector.html), [cider-nrepl inspect.clj](https://github.com/clojure-emacs/cider-nrepl/blob/master/src/cider/nrepl/middleware/inspect.clj), [nREPL Middleware Setup](https://docs.cider.mx/cider/basics/middleware_setup.html)

### 6.2 Spark UI Step Data Inspection

Spark's web UI shows per-stage information:
- Input/output rows per stage
- Shuffle read/write sizes
- Task duration distribution
- DAG visualization showing stage dependencies
- `df.explain()` prints logical + physical plans as formatted text (EXPLAIN FORMATTED, EXTENDED, CODEGEN modes available)

The key insight: Spark shows **summary statistics** per stage (row counts, sizes), not actual data samples. Data preview requires explicit `.show(n)` calls.

Sources: [Spark EXPLAIN](https://spark.apache.org/docs/latest/sql-ref-syntax-qry-explain.html), [Spark Execution Stages](https://medium.com/@vigneshbw2002/understanding-spark-execution-logical-plan-physical-plan-actions-transformations-spark-ui-and-c55cdce875db)

### 6.3 Proposed nREPL Op: `inspect-pipeline-step`

```clojure
;; nREPL request
{:op      "inspect-pipeline-step"
 :session "..."
 :file    "pipeline.dtw"         ;; file containing the pipeline
 :line    5                      ;; line of the |> to inspect (or step index)
 :step    2                      ;; 0-based step index (alternative to line)
 :sample-size 50}                ;; optional override (default: SAMPLE_SIZE)

;; nREPL response
{:status    "done"
 :step      2
 :label     "map _.name"
 :loc       {:line 5 :col 4}
 :row-count 100                  ;; number of rows in cached sample
 :sample    [{:name "Alice"} {:name "Bob"} ...]  ;; serialized sample rows
 :columns   ["name"]             ;; inferred column names
 :types     {"name" "string"}}   ;; inferred column types
```

**Resolution strategy**:
1. The nREPL middleware maintains a registry of recently evaluated pipelines (keyed by file + line).
2. When `inspect-pipeline-step` arrives, look up the pipeline by file/line.
3. If the pipeline has a cached sample for the requested step, return it immediately.
4. If not cached (pipeline not yet evaluated), evaluate up to the requested step and cache.

### 6.4 Overlay Display in Emacs

```elisp
;; When cursor is on or near a |> operator:
;; 1. Send inspect-pipeline-step request for that line
;; 2. Display overlay showing step info

(defun datatwist--inspect-pipeline-step ()
  "Inspect the pipeline step at point."
  (interactive)
  (let* ((line (line-number-at-pos))
         (response (nrepl-sync-request:eval
                    (format "(dtw/inspect-step %d)" line))))
    (datatwist--display-step-overlay
      (nrepl-dict-get response "step")
      (nrepl-dict-get response "label")
      (nrepl-dict-get response "row-count")
      (nrepl-dict-get response "sample"))))

;; Overlay format:
;; users                          => step 0: source, 10000 rows
;; |> filter _.status = "active"  => step 1: 7500 rows [{name: "Alice" ...} ...]
;; |> map {name: _.name}          => step 2: 7500 rows, 1 col [{name: "Alice"} ...]
;; |> sort-by _.name              => step 3: 7500 rows (sorted)
```

### 6.5 VS Code / Calva Integration

Calva uses nREPL (same as CIDER). The `inspect-pipeline-step` op works identically. VS Code extensions can display results as:
- Code lens annotations above each `|>` line
- Hover tooltips showing sample data as a table
- A dedicated "Pipeline Inspector" panel showing all steps with expandable samples

### 6.6 Interaction Model

1. **Automatic**: After evaluating a pipeline expression, the IDE automatically fetches and displays step summaries as overlays on each `|>` line.
2. **On-demand**: Click/hover on a `|>` to see the full sample data in an inspector buffer.
3. **Refresh**: Re-evaluate the pipeline and update all overlays.
4. **Navigate**: Click a row in the sample to inspect it in the CIDER inspector (drill-down via `inspect-push`).

**Important**: The cached samples make this **instant** -- no re-evaluation needed to inspect any step. This is the primary UX advantage of auto-caching.

---

## 7. Revised API Surface

### 7.1 What Functions Exist

**Pipeline construction** (returns DTPipeline):
- `|>` -- pipe operator, builds reified pipeline

**Terminal operations** (trigger evaluation, return results):
- `collect` -- materialize to vector
- `count` -- count elements
- `first` -- first element
- `reduce` -- reduce with function and initial value
- `force!` -- materialize passthrough (returns data, keeps pipeline flowing)
- `save!` -- write to file, passthrough
- `into!` -- insert into target, passthrough

**Debug probe** (only one):
- `tap!` -- sample and print, passthrough. Args: none, string label, or lambda.

**Exploration** (operate on sample by default):
- `describe` -- statistical summary (samples DESCRIBE_SAMPLE_SIZE rows)
- `schema` -- column names and types (samples SAMPLE_SIZE rows)
- `histogram` -- frequency distribution (samples DESCRIBE_SAMPLE_SIZE rows)
- `freq` -- exact frequency table (forces full evaluation)
- `sample` -- random N rows
- `explain` -- print execution plan (no data access)

**Configuration**:
- `dtw/set!` -- set a system constant
- `dtw/get` -- get a system constant
- `dtw/inspect` -- inspect pipeline step sample (programmatic, for scripts/CI)

**System constants** (pre-bound uppercase symbols):
- `SAMPLE_SIZE` (100)
- `MAX_COLLECT_ROWS` (nil)
- `DESCRIBE_SAMPLE_SIZE` (1000)
- `PRINT_WIDTH` (120)

### 7.2 What Was Removed

| Removed | Replacement |
|---|---|
| `inspect` (as pipeline function) | `tap!` (programmatic) + `dtw/inspect` (IDE/script) |
| `log!` | `tap! "label"` |
| `print` / `println` in pipeline context | `tap!` or `tap! [d -> ...]` |

### 7.3 What Changed

| Function | Old Behavior | New Behavior |
|---|---|---|
| `tap!` | Primitive println + passthrough | Full debug probe: auto-sample, label, lambda, cached in pipeline |
| `dtw/set!` / `dtw/get` | String keys: `dtw/set! "sample-size" 200` | Symbol keys: `dtw/set! SAMPLE_SIZE 200` |
| `collect`, `count`, `first`, `reduce` | Operate on vectors/lazy seqs | Also detect DTPipeline and trigger realization |

---

## 8. Impact on Current Implementation

### 8.1 Evaluator Changes (src/datatwist/evaluator.clj)

**Current `:Pipeline` handler** (lines 632-637):
```clojure
(= :Pipeline tag)
(let [source-node (first children)
      steps       (rest children)
      data        (eval-node source-node env)]
  (eval-pipeline data steps env))
```

**New `:Pipeline` handler**:
```clojure
(= :Pipeline tag)
(let [source-node (first children)
      steps       (rest children)
      data        (eval-node source-node env)]
  (build-pipeline data steps env))  ;; Returns DTPipeline, does not execute
```

**`eval-pipeline` becomes `realize-pipeline`**: The current `eval-pipeline` function (lines 1401-1408) is renamed and adapted to walk the pipeline, execute steps, and cache samples.

**`eval-pipe-atom-with-fn-call`** (lines 1320-1399) is unchanged -- it still compiles a PipeAtom to a step function. The difference is that these step functions are stored in the `DTPipeline` record instead of being immediately applied.

**`:SourcelessPipeline` handler** (lines 639-643) must also produce a `DTPipeline`-returning lambda:
```clojure
(= :SourcelessPipeline tag)
(let [steps children]
  (fn [data]
    (build-pipeline data steps env)))
```

**Auto-coercion**: Terminal operations, REPL display, and `=` comparison must detect `DTPipeline` and realize it. This requires a protocol or multimethod dispatch:

```clojure
(defprotocol Realizable
  (realize [this]))

(extend-protocol Realizable
  DTPipeline
  (realize [p] (realize-pipeline p))

  Object
  (realize [x] x)

  nil
  (realize [_] nil))
```

### 8.2 Stdlib Changes (src/datatwist/stdlib.clj)

1. **`tap!` redesign**: Replace current 19-line implementation with the new version (section 4.4 above).
2. **Remove `log!`**: Or deprecate it (keep it working but undocumented).
3. **Terminal operations** (`collect`, `count`, `first`, `reduce`, `force!`): Add `DTPipeline` detection.
4. **Add system constants** to the global environment.
5. **`dtw/set!` and `dtw/get`**: Change from string keys to symbol/keyword keys.

### 8.3 New Namespace: src/datatwist/pipeline.clj

Contains:
- `DTPipeline` and `PipelineStep` record definitions
- `build-pipeline` function
- `realize-pipeline` function
- `cache-step-sample!` and `get-step-sample` utility functions
- `explain-pipeline` function (returns human-readable step listing)
- `Realizable` protocol

### 8.4 New Namespace: src/datatwist/config.clj

Contains:
- Config atom and defaults
- `get-config`, `set-config!` functions
- Environment variable overrides at startup

### 8.5 Grammar Changes

**None.** The grammar is unchanged. Pipeline reification is entirely a runtime concern. `|>` still produces `Pipeline = PipeAtom (_ <'|>'> _ PipeAtom)+` in the AST.

### 8.6 Test Impact

- **Features 1-7 tests**: Should continue passing. The `DTPipeline` auto-realizes when compared with `=` (Clojure equality on a realized result vs expected vector). However, tests that check `(instance? PersistentVector result)` will fail if the pipeline returns a `DTPipeline`. Mitigation: ensure `realize` is called in `eval-dt` test helper, or make `DTPipeline` implement `Seqable`/`Sequential` so `(= [1 2 3] pipeline)` works.
- **Feature 8 lazy eval tests**: These are the primary beneficiary. The `DTPipeline` record directly supports the BDD scenarios in Section 9 (pipeline as first-class runtime object) and Section 4 (tap!).
- **Parser tests**: Unaffected (parser tests do not go through evaluator).

### 8.7 Backward Compatibility Concern

The biggest risk: code that expects `data |> filter f |> map g` to return a vector or lazy seq will now receive a `DTPipeline`. Solutions:

1. **Auto-realize at expression boundaries**: If a pipeline result is used in a non-pipeline context (arithmetic, binding, function argument), auto-realize it. This preserves backward compatibility but loses the lazy/inspectable property.
2. **Make DTPipeline implement core interfaces**: Implement `Seqable`, `Sequential`, `Counted`, `IFn` on `DTPipeline` so it behaves like a collection transparently. Accessing it as a seq triggers realization.
3. **Delay reification to IDE mode only**: In non-IDE mode (batch, script), `|>` evaluates eagerly as before. In IDE/REPL mode, `|>` produces `DTPipeline`. This avoids breaking batch scripts.

**Recommendation: Option 2.** Implement `Seqable` on `DTPipeline` so that `(seq pipeline)` calls `realize-pipeline` and returns the result as a sequence. This means `(= [1 2 3] pipeline)` works, `(first pipeline)` works, `(map f pipeline)` works -- all by transparently realizing. The reified structure is preserved for `dtw/inspect` and `explain` until first access.

```clojure
(defrecord DTPipeline [source steps env sample-cache realized? metadata]
  clojure.lang.Seqable
  (seq [this]
    (seq (realize-pipeline this)))

  clojure.lang.Sequential  ;; marker interface

  clojure.lang.Counted
  (count [this]
    (count (realize-pipeline this))))
```

---

## 9. Open Questions

### Q1: When to auto-realize vs. preserve laziness?

If `DTPipeline` implements `Seqable`, any seq operation triggers full realization. This means `data |> filter f |> map g |> take 10` would realize all of `filter f |> map g` before taking 10. This defeats laziness.

**Possible solution**: `realize-pipeline` returns a lazy sequence (each step wraps the previous in a lazy transformation). The sample cache captures samples during lazy traversal. This preserves the Phase 1 lazy-seq behavior while adding caching.

### Q2: Should `tap!` print immediately or buffer for IDE?

In REPL/script mode, `tap!` should print to stdout immediately. In IDE mode, `tap!` output could be captured and sent to the IDE as structured data instead of printing. Should there be a `tap!` mode switch, or should IDE rely solely on `dtw/inspect`?

### Q3: Step labels -- how to generate?

The `step-label` function needs to extract a human-readable description from the PipeAtom AST node. Options:
- Reconstruct source text from AST (fragile, requires an AST-to-source printer)
- Use source locations to read the original source file (requires file access)
- Store source text in AST metadata during parsing (cleanest, but requires parser changes)

### Q4: Pipeline registry for nREPL

The nREPL `inspect-pipeline-step` op needs to find pipelines by file+line. Should the evaluator maintain a global registry of recently evaluated pipelines? Memory management: how many pipelines to retain? LRU cache?

### Q5: SourcelessPipeline interaction

`|> filter f |> map g` (without a source) returns a function. In the reified model, should it return a `DTPipeline`-factory (a function that, given data, produces a `DTPipeline`)? Or just a regular function?

### Q6: Transducer fusion with step caching

In Phase 2, if steps are fused into a transducer, how do we still cache per-step samples? Options:
- Fuse for execution but keep unfused steps for inspection (dual representation)
- Insert sample-capture points between fused transducer steps (possible with `(comp xf1 (map #(do (cache! %) %)) xf2)`)
- Only fuse when inspection is not active (performance mode vs. debug mode)

### Q7: Thread safety of sample cache

The `sample-cache` atom is safe for concurrent reads, but concurrent pipeline evaluations (unlikely in a REPL but possible in scripts) could race on writes. `swap!` on the atom is atomic, so this is safe for individual step updates. However, the overall evaluation (walking all steps) is not atomic. Is this acceptable?

**Likely yes** -- concurrent evaluation of the same pipeline object is an unusual pattern, and the worst case is that a cached sample is overwritten by a slightly different realization (which is functionally equivalent).
