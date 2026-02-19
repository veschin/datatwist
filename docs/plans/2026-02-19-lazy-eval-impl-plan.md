# Lazy Evaluation Implementation Plan

Date: 2026-02-19

## 1. Current State

### Design Document

`docs/lazy-eval-design.md` (817 lines) provides a comprehensive design covering:
- Survey of 6 approaches (Clojure lazy-seq, Haskell, Spark, Polars, DuckDB, R/dplyr)
- 10 design decisions (Q1-Q10) covering lazy/eager boundary, sampling, transducers, materialization triggers, infinite sequences, tap!, exploration functions, memory safety, configuration
- Sampling model (first-N, REPL display protocol, DB/file pushdown)
- Architecture (Clojure primitives, stdlib changes, pipeline evaluation, DataSource protocol)
- API surface (materialization, exploration, debugging, infinite sequences, configuration)
- Integration plan (what changes, what does not, test compatibility)
- Risk analysis (7 risks with mitigations)
- 6-phase implementation roadmap

### BDD Specification

`bdd/8-lazy-eval-data-sources.feature` (852 lines) contains **71 scenarios** across 11 sections:

| Section | Scenarios | Topic |
|---|---|---|
| 1 | 5 | Lazy pipeline construction |
| 2 | 13 | Materialization functions (collect, count, first, reduce, force!, save!, into!) |
| 3 | 5 | REPL micro-sampling |
| 4 | 6 | tap! inline pipeline debugging |
| 5 | 8 | Data sources -- databases (connect, table, query, close!) |
| 6 | 7 | Data sources -- files (read-csv, read-json, read-jsonl, read-lines, read-parquet) |
| 7 | 8 | SQL push-down optimization |
| 8 | 7 | Explore/describe functions (describe, schema, sample, freq, histogram, explain) |
| 9 | 4 | Pipeline as first-class runtime object (dtw/inspect) |
| 10 | 4 | Error handling for data sources |
| 11 | 4 | Integration scenarios |

### Test Stubs

`test/datatwist/lazy_eval_test.clj` (683 lines) contains **71 deftest stubs** -- one per BDD scenario. Test categories by implementation approach:

- **Behavioral tests** (assert runtime results via eval-dt/eval-dt-last): ~30 tests in Sections 1-4, 8, 9, 10, 11
- **Syntax-only tests** (assert `not (parse-error? ...)` only): ~30 tests in Sections 5-7 (DB, file I/O, SQL pushdown)
- **Mixed tests** (syntax + partial runtime): ~11 tests that check both parse validity and some runtime behavior

### Grammar Support

The grammar (`resources/datatwist.grammar`) has **no lazy-specific syntax**. The design doc confirms: "The grammar -- no new syntax needed." All lazy evaluation is a runtime concern. Functions like `collect`, `force!`, `tap!`, `range`, `connect`, `read-csv`, etc. are regular function calls in the existing grammar.

### Current Stdlib

`src/datatwist/stdlib.clj` currently wraps all collection-returning functions in `(vec ...)`, making them eager. The following functions exist but return vectors:

- `filter`, `map`, `take`, `drop`, `distinct`, `flatten`, `reverse`, `sort`, `sort-by`, `range`, `rest`, `concat`, `zip`, `partition`
- `count`, `first`, `last`, `reduce` exist and work correctly (these are terminal, not collection-returning)
- `tap!` exists but is primitive: `(fn ([data] (println data) data) ([data f] (f data) data))`
- `save!` exists as a stub: `(fn [data & _args] data)` -- returns data (passthrough) but does no I/O
- `log!` exists and works correctly

**Missing from stdlib**: `collect`, `force!`, `repeat`, `iterate`, `cycle`, `describe`, `schema`, `histogram`, `freq`, `sample`, `explain`, `connect`, `table`, `query`, `read-csv`, `read-json`, `read-jsonl`, `read-lines`, `read-parquet`, `close!`, `into!`, `dtw/set!`, `dtw/get`, `dtw/inspect`

### Evaluator

`src/datatwist/evaluator.clj` -- `eval-pipeline` (line 1378) uses `reduce` to chain step functions. This is already compatible with laziness: when step functions return lazy sequences, the reduce builds a lazy chain without forcing evaluation. **No evaluator changes needed for Phase 1.**

---

## 2. Gap Analysis

### What BDD Requires vs. What Currently Exists

#### Section 1: Lazy Pipeline Construction (5 scenarios) -- MAJOR GAP

**Required**: `data |> filter f |> map g` produces a Clojure lazy sequence (not a PersistentVector).

**Current**: `dt-filter` returns `(vec (filter ...))` and `dt-map` returns `(vec (map ...))` -- both are eager, producing PersistentVector.

**Gap**: Remove `(vec ...)` from `dt-filter`, `dt-map`, `dt-take`, `dt-drop`, `dt-distinct`, `dt-flatten`, `range`, `rest`. This is the core change that enables laziness.

**Test impact**: 5 tests will pass once `vec` wrappers are removed. Tests check `(not (instance? PersistentVector result))` and verify `(= expected (vec result))`.

#### Section 2: Materialization Functions (13 scenarios) -- MODERATE GAP

**Required**: `collect`, `force!`, `save!`, `into!`, `reduce`, `count`, `first` with correct semantics.

**Current**:
- `collect` -- does not exist. **Must add.**
- `force!` -- does not exist. **Must add.**
- `count`, `first`, `last`, `reduce` -- exist and work correctly. No changes needed.
- `save!` -- exists as passthrough stub `(fn [data & _args] data)`. BDD tests that check passthrough semantics will pass. Tests that check actual file I/O require real implementation (Phase 3).
- `into!` -- does not exist. **Must add** (at minimum a stub that throws for nil DB connection).

**Test expectations**:
- `collect-forces-entire-pipeline-into-a-vector-in-memory`: `(= [30 40 50] result)` AND `(instance? PersistentVector result)`
- `force-materializes-lazy-pipeline-and-returns-data-passthrough`: `(= [30 40 50] result)` after `force! |> collect`
- `save-bang-writes-pipeline-output-to-file-and-returns-data-passthrough`: `data |> save! "/tmp/..." |> count` = 2. Requires `save!` to actually write JSON.
- `save-bang-supports-multiple-file-formats`: Tests passthrough via `|> count` after `save!`. Requires CSV and JSON writing.
- `into-bang-inserts-pipeline-output-into-database`: `(throws-type? "into! nil \"table\"" Exception)`

#### Section 3: REPL Micro-sampling (5 scenarios) -- PARTIAL GAP

**Required**: REPL auto-samples lazy pipelines, shows tabular preview, estimates.

**Current**: No REPL integration exists.

**Test approach**: Test stubs verify underlying semantics (laziness preserved, correct values when forced), NOT REPL display formatting. Most of these tests will pass once Section 1 changes are made, because they test that pipelines remain lazy and produce correct results.

**Test expectations**:
- `repl-auto-sampling-does-not-force-full-pipeline`: `range 1 1000000000 |> filter ...` produces lazy seq, `(take 5 result)` = `[2 4 6 8 10]`
- `repl-preview-shows-tabular-format`: pipeline produces correct objects
- `repl-shows-full-result-for-small-collections`: `[1 2 3 4 5] |> filter _ > 2` = `[3 4 5]` when vec'd
- `repl-preview-for-scalar-result`: `count` returns integer
- `repl-uses-first-n-sampling-strategy`: `range 1 1000 |> filter ... |> take 5 |> collect` = `[2 4 6 8 10]`

#### Section 4: tap! (6 scenarios) -- SMALL GAP

**Required**: `tap!` is passthrough, accepts optional label or function, samples (does not force full evaluation).

**Current**: `tap!` exists: `(fn ([data] (println data) data) ([data f] (f data) data))`. This is close but has issues:
1. It `(println data)` which would try to print the entire lazy sequence, forcing evaluation.
2. It does not support string labels distinctly from function arguments.

**Gap**: Update `tap!` to sample with `(take 100 data)` for display and dispatch on label vs function.

**Test expectations**:
- All 6 tests verify passthrough: pipeline result is unchanged after tap!
- `tap-bang-shows-a-micro-sample-not-the-full-dataset`: result must still be lazy after tap! (`(not (instance? PersistentVector result))`)
- `tap-with-transformation-function`: tap! applies function to sample for display only, data unchanged

#### Section 5: Database Sources (8 scenarios) -- SYNTAX ONLY

**Current test approach**: All 8 tests check `(not (parse-error? ...))` only. One test checks `(throws? ...)` for connect to verify runtime error.

**Gap for passing tests**: Need `connect`, `table`, `query`, `close!` in stdlib. For syntax tests, they only need to be recognized as functions (already are if they're in the env). However, `connect` is NOT in the current stdlib. The test `connect-to-postgresql-database-is-valid-syntax` calls `(throws? "db is connect ...")` which requires `connect` to exist as a function (even if it throws).

**Must add**: `connect`, `table`, `query`, `close!` as stubs in stdlib.

#### Section 6: File Sources (7 scenarios) -- PARTIAL GAP

**Test approach**: 6 of 7 tests check `(not (parse-error? ...))` only. Syntax tests will pass if the functions exist in stdlib.

**Must add**: `read-csv`, `read-json`, `read-jsonl`, `read-lines`, `read-parquet` as stubs (or with basic implementation for `read-csv`).

**Special**: `file-not-found-raises-error-when-pipeline-is-materialized` (Section 10) requires `read-csv` to actually attempt file opening on materialization and throw `java.io.FileNotFoundException`.

#### Section 7: SQL Push-down (8 scenarios) -- SYNTAX ONLY

All 8 tests check `(not (parse-error? ...))` only. These will pass automatically once Section 5 stubs (connect, table) exist in stdlib.

#### Section 8: Explore/Describe Functions (7 scenarios) -- MODERATE GAP

**Test approach**: Mixed -- syntax checks plus some runtime assertions.

**Must add**: `describe`, `schema`, `sample`, `freq`, `histogram`, `explain`.

**Test expectations**:
- `describe-shows-statistical-summary`: `(map? (eval-dt "[{a: 1 b: 2} {a: 3 b: 4}] |> describe"))` -- describe must return a map
- `schema-shows-column-names-and-inferred-types`: `(some? (eval-dt "... |> schema"))` -- schema must return something non-nil
- `sample-returns-n-elements`: `(= 5 (count (eval-dt "[1 2 3 4 5 6 7 8 9 10] |> sample 5")))` -- sample must return exactly N elements
- `freq-shows-frequency-table`: `(coll? result)` -- freq must return a collection
- `histogram-shows-ascii-histogram`: `(some? result)` -- histogram must return something non-nil
- `explain-on-file-backed-pipeline`: syntax-only check

#### Section 9: Pipeline as First-Class Object (4 scenarios) -- MODERATE GAP

**Test expectations**:
- `pipeline-is-a-first-class-value`: pipeline result is lazy (not PersistentVector), `(= [30 40 50] (vec result))`
- `pipeline-retains-metadata`: syntax-only check
- `dtw-inspect-returns-sample-data`: requires `dtw/inspect` function. Test calls `(eval-dt-last ... "dtw/inspect plan 1 100")` and asserts `(coll? result)`.
- `pipeline-lazy-evaluation-is-transparent`: two independent collects produce same result `[3 4 5]`

**Must add**: `dtw/inspect` function.

#### Section 10: Error Handling (4 scenarios) -- MODERATE GAP

**Test expectations**:
- `connection-failure-raises-descriptive-error`: `(throws? "db is connect \"postgres://nonexistent-host-dt-test/mydb\"")` -- connect must throw for invalid host. Also tests try-catch wrapping.
- `file-not-found-raises-error`: `read-csv` building a pipeline is lazy (no throw), but `|> collect` throws `java.io.FileNotFoundException`
- `query-timeout-raises-an-error`: syntax-only check
- `schema-mismatch-is-nil-tolerant`: `filter _.nonexistent-column > 5 |> collect` = `[]` -- this should work with current nil-tolerant semantics once laziness is in place

#### Section 11: Integration Scenarios (4 scenarios) -- MODERATE GAP

**Test expectations**:
- `full-etl-pipeline-from-database-to-file`: syntax-only check
- `streaming-pipeline-processes-large-file-without-unbounded-memory`: syntax-only + save! passthrough with count
- `lazy-pipeline-reuse-file-sources`: two independent collects on in-memory data produce correct results
- All depend on prior sections being implemented

---

## 3. Implementation Steps

### Phase 1: Core Laziness (Sections 1-4 tests) -- PRIORITY

**Estimated: 1-2 days. Enables ~30 tests to pass.**

#### Step 1.1: Remove `(vec ...)` wrappers from lazy-compatible stdlib functions

**File**: `src/datatwist/stdlib.clj`

| Function | Current | Change to |
|---|---|---|
| `dt-filter` (line 130) | `(vec (filter pred coll))` | `(filter pred coll)` |
| `dt-map` (line 135) | Sequential branch: `(vec (map f coll))`, Map branch: `(vec (map ...))` | `(map f coll)` / `(map ...)` |
| `dt-take` (line 120) | `(vec (take n coll))` | `(take n coll)` |
| `dt-drop` (line 125) | `(vec (drop n coll))` | `(drop n coll)` |
| `dt-distinct` (line 94) | `(vec (distinct coll))` | `(distinct coll)` |
| `dt-flatten` (line 91) | `(vec (apply concat coll))` | `(apply concat coll)` |
| `range` (line 372) | `(vec (range ...))` in all 3 arities | `(range ...)` |
| `rest` (line 286) | `(comp vec rest)` | `rest` |

**Keep eager** (these need all elements):
- `dt-sort` (line 100): keep `(vec (sort coll))`
- `dt-sort-by` (line 106): keep `(vec ...)`
- `dt-reverse` (line 97): keep `(vec (reverse coll))`
- `dt-group-by` (line 152): keep eager

**Also keep eager** (not collection-returning):
- `dt-concat` (line 167): currently `(vec (apply concat colls))`. Change to `(apply concat colls)` -- concat should be lazy.
- `dt-into` (line 171): `(vec (into target src))` -- keep as-is, it is an explicit materialization into a target.

**Nil handling**: `dt-filter` and `dt-map` must handle nil input. Current `dt-map` already returns `[]` for nil. `dt-filter` currently does `(vec (filter pred coll))` which would NPE on nil. Add: `(if (nil? coll) () (filter pred coll))`.

#### Step 1.2: Add `collect` function

**File**: `src/datatwist/stdlib.clj`

Add to `default-env`:
```clojure
"collect" (fn [coll] (if (vector? coll) coll (vec coll)))
```

#### Step 1.3: Add `force!` function

**File**: `src/datatwist/stdlib.clj`

Add to `default-env`:
```clojure
"force!" (fn [data] (if (vector? data) data (vec data)))
```

Note: `force!` is passthrough -- it returns the materialized data (which is then the "first argument" flowing through the pipeline). The difference from `collect` is semantic intent, not behavior in Phase 1. Both convert to vector.

#### Step 1.4: Update `tap!` function

**File**: `src/datatwist/stdlib.clj`

Replace current tap! (line 389):
```clojure
;; Current:
"tap!" (fn ([data] (println data) data)
           ([data f] (f data) data))

;; New:
"tap!" (fn ([data]
            (let [sample (take 100 data)]
              (doseq [item sample] (println item))
              data))
           ([data label-or-fn]
            (if (string? label-or-fn)
              (do (println (str "--- " label-or-fn " ---"))
                  (let [sample (take 100 data)]
                    (doseq [item sample] (println item))
                    data))
              ;; label-or-fn is a function -- apply to sample for display
              (let [sample (take 100 data)]
                (println (label-or-fn sample))
                data))))
```

Key: `tap!` calls `(take 100 data)` which only realizes 100 elements from a lazy sequence. The original `data` reference is returned unchanged.

#### Step 1.5: Add `repeat` and `iterate` functions

**File**: `src/datatwist/stdlib.clj`

Add to `default-env`:
```clojure
"repeat"  (fn ([v] (repeat v))
              ([n v] (repeat n v)))
"iterate" (fn [f init] (iterate f init))
"cycle"   (fn [coll] (cycle coll))
```

Note: Argument order for `repeat` -- BDD uses `repeat 5 "x"` which is `(repeat 5 "x")` = bounded. But also `repeat "x"` for infinite. The Clojure `repeat` function has arities `(repeat x)` -> infinite and `(repeat n x)` -> bounded. DataTwist's pipe-first means `data |> repeat` doesn't make sense for repeat -- repeat is a source generator, not a pipeline step. So `repeat 5 "x"` maps to `(repeat 5 "x")` directly.

#### Step 1.6: Verify existing tests 1-7 still pass

After Step 1.1, run `make test`. The key concern is:
- `data_structures_test.clj:659`: `(is (vector? (eval-dt "[1 2 3]")))` -- this tests a literal list, NOT a pipeline result. Literal `[1 2 3]` evaluates to a PersistentVector directly (not through dt-filter/dt-map). **This test will still pass.**
- All `(is (= expected (eval-dt ...)))` tests -- Clojure's `=` works across lazy seqs and vectors. **These will still pass.**
- Pipeline tests in `pipeline_test.clj` -- verify they don't check `vector?` on pipeline results. (Grep confirmed: no `vector?` checks in pipeline_test.clj.)

**Risk**: Some tests may do `(is (= [expected] result))` where `result` is now a lazy seq. Clojure `=` handles this: `(= [1 2] (map identity [1 2]))` is true. **Safe.**

**Risk**: Tests that use `result` as input to another function that requires a vector. E.g., `(nth result 0)` works on both. `(get result :key)` works on maps. **Safe.**

**Risk**: `dt-map` nil branch returns `[]` (empty vector). Change this to `()` or `'()` for consistency? The BDD test `nil-source-in-a-pipeline-produces-empty-collection` expects `(= [] result)`. Since `(= [] ())` is true in Clojure, returning `()` is fine. But `collect` on `()` should return `[]`. **Keep `[]` for nil -- it's already materialized and correct.**

### Phase 2: Exploration Functions (Section 8 tests) -- SECONDARY

**Estimated: 2-3 days. Enables ~7 tests to pass.**

#### Step 2.1: Create `src/datatwist/explore.clj`

New file with implementations:

**`dt-describe`**: Takes a collection (data-first). Samples up to 1000 rows. For each key found in the sample, compute: type (inferred from values), count of non-nil, min, max, mean (for numerics), distinct count. Returns a map with `:columns` key containing a vector of per-column stat maps.

```clojure
(defn dt-describe
  ([coll] (dt-describe coll 1000))
  ([coll sample-size]
   (let [sample (vec (take sample-size coll))
         ...]
     {:columns [...]})))
```

**`dt-schema`**: Takes a collection. Samples 100 rows. Infers type per key from first non-nil value. Returns a vector of `{:name "col" :type "string"}` maps.

**`dt-sample`**: Takes `(coll, n)`. Reservoir sampling: traverse up to full collection, maintain reservoir of size N. Returns a vector of N elements.

```clojure
(defn dt-sample [coll n]
  (vec (take n (shuffle (vec (take (* 10 n) coll))))))
```

For Phase 1, a simpler approach: `(vec (take n (shuffle (vec coll))))`. This is eager but correct. Reservoir sampling can be added later.

**`dt-freq`**: Takes `(coll, field-fn)`. Full traversal. Groups by field value, counts, computes percentage. Returns a vector of `{:value v :count c :pct p}` maps, sorted by count descending.

**`dt-histogram`**: Takes `(coll, field-fn)`. Samples 1000 rows. Extracts numeric values. Computes bins (Sturges' rule or fixed 10 bins). Returns map with `:bins` vector.

**`dt-explain`**: Takes a collection/lazy-seq. Returns a string describing the type and estimated size. For Phase 1, a simple implementation: `(str "source: " (type coll) " | elements: " (if (counted? coll) (count coll) "lazy"))`.

#### Step 2.2: Create `src/datatwist/config.clj`

New file with a global config atom:

```clojure
(ns datatwist.config)

(def ^:private defaults
  {"sample-size"          100
   "describe-sample-size" 1000
   "max-collect-rows"     nil
   "print-width"          120})

(def config (atom defaults))

(defn dt-set-config! [key value]
  (swap! config assoc key value)
  value)

(defn dt-get-config [key]
  (get @config key))
```

#### Step 2.3: Wire explore functions into stdlib

**File**: `src/datatwist/stdlib.clj`

Add requires for `datatwist.explore` and `datatwist.config`. Add to `default-env`:

```clojure
"describe"    explore/dt-describe
"schema"      explore/dt-schema
"sample"      explore/dt-sample
"freq"        explore/dt-freq
"histogram"   explore/dt-histogram
"explain"     explore/dt-explain
"dtw/set!"    config/dt-set-config!
"dtw/get"     config/dt-get-config
```

#### Step 2.4: Add `dtw/inspect`

**File**: `src/datatwist/explore.clj` or `src/datatwist/stdlib.clj`

`dtw/inspect` takes `(plan, step-index, sample-size)`. In Phase 1 with lazy sequences (no reified pipeline object), this is approximated: treat the plan as a lazy sequence and sample it.

```clojure
"dtw/inspect" (fn [plan _step-index sample-size]
                (vec (take sample-size plan)))
```

This is a simplification -- full step-by-step inspection requires a reified pipeline object (Phase 5+). The test asserts `(coll? result)` which this satisfies.

### Phase 3: Data Source Stubs (Sections 5-7, 10 tests) -- TERTIARY

**Estimated: 2-4 days. Enables ~25 tests to pass (mostly syntax-only).**

#### Step 3.1: Add DB function stubs to stdlib

**File**: `src/datatwist/stdlib.clj`

```clojure
"connect"  (fn [url & [opts]]
             (throw (ex-info (str "Cannot connect to " url ": database support not yet implemented")
                             {:url url :opts opts})))
"table"    (fn [db table-name]
             (throw (ex-info "table: database support not yet implemented"
                             {:table table-name})))
"query"    (fn [db sql & [params]]
             (throw (ex-info "query: database support not yet implemented"
                             {:sql sql})))
"close!"   (fn [resource] resource)
"into!"    (fn [data db table-name]
             (throw (ex-info "into!: database support not yet implemented"
                             {:table table-name})))
```

These stubs make syntax tests pass (functions exist in env, so parse succeeds) and make runtime tests that expect throws also pass.

#### Step 3.2: Implement basic `read-csv`

**File**: `src/datatwist/io.clj` (new file)

```clojure
(ns datatwist.io
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn dt-read-csv
  "Read a CSV file as a lazy sequence of maps.
   First row is used as headers (keys). Returns lazy seq."
  ([path] (dt-read-csv path {}))
  ([path opts]
   (let [separator (get opts :separator ",")
         has-header (get opts :header true)
         reader (io/reader path)
         lines (line-seq reader)
         parse-line (fn [line] (str/split line (re-pattern separator)))]
     (if has-header
       (let [header (map keyword (parse-line (first lines)))
             data-lines (rest lines)]
         (map (fn [line] (zipmap header (parse-line line))) data-lines))
       (map (fn [line] (vec (parse-line line))) lines)))))
```

This provides a lazy sequence backed by a file reader. The file handle is held open until the sequence is consumed.

#### Step 3.3: Implement other file reader stubs

**File**: `src/datatwist/io.clj`

- `read-json`: Read file, parse as JSON (using `clojure.data.json` or manual parsing). Stub that throws "not implemented" or basic implementation.
- `read-jsonl`: Read file line-by-line, parse each line as JSON. Lazy.
- `read-lines`: `(line-seq (io/reader path))` -- trivially lazy.
- `read-parquet`: Stub that throws "Parquet support requires additional dependencies."

#### Step 3.4: Implement `save!` with actual file I/O

**File**: `src/datatwist/io.clj` or update stub in `src/datatwist/stdlib.clj`

```clojure
(defn dt-save!
  "Write data to a file. Dispatches on file extension.
   Returns data (passthrough)."
  [data path]
  (let [ext (last (str/split path #"\."))]
    (case ext
      "json" (spit path (json/write-str (vec data)))
      "csv"  (write-csv path (vec data))
      (throw (ex-info (str "Unsupported file format: " ext) {:path path}))))
  data)
```

Passthrough: returns `data` after writing.

#### Step 3.5: Wire into stdlib

Add `read-csv`, `read-json`, `read-jsonl`, `read-lines`, `read-parquet`, updated `save!` to `default-env`.

### Phase 4: Error Handling (Section 10 tests)

**Estimated: 0.5 days. Mostly covered by Phase 3.**

- `connection-failure`: Covered by `connect` stub throwing with descriptive message. Test also verifies try-catch: `try connect "..." catch err -> "connection-failed"` -- this requires the existing try-catch evaluator support (Feature 7).
- `file-not-found`: Covered by `read-csv` attempting `(io/reader path)` which throws `FileNotFoundException`. The lazy nature means building the pipeline does NOT throw (because `line-seq` is lazy), but `collect` forces it. Actually, `(io/reader path)` throws eagerly when the file doesn't exist. The test expects `(not (throws? "data is read-csv ..."))` (building is OK) and `(throws? "... |> collect")` (materializing throws). This means `read-csv` must defer the reader opening. Implementation: wrap in `lazy-seq` that opens the file on first element request.
- `schema-mismatch-is-nil-tolerant`: Already works with current nil-tolerant field access. `_.nonexistent-column` returns nil, `nil > 5` is falsy, filter excludes all, collect returns `[]`.

**Important detail for `read-csv` laziness**: The `io/reader` call must be deferred into the lazy sequence:

```clojure
(defn dt-read-csv [path opts]
  (lazy-seq
    (let [reader (io/reader path)   ;; throws FileNotFoundException here
          lines  (line-seq reader)
          ...]
      ...)))
```

This way, `read-csv "nonexistent.csv"` returns a lazy-seq object (no throw), and `collect` on it forces realization, which calls `io/reader`, which throws.

### Phase 5: SQL Push-down (Section 7) -- FUTURE

**Not in scope for initial implementation.** All Section 7 tests are syntax-only and will pass once DB stubs exist (Phase 3). Actual SQL generation requires a query plan compiler, which is a separate design doc (`docs/pushdown-design.md`).

### Phase 6: REPL Integration (Section 3 display) -- FUTURE

**Not in scope for initial implementation.** The underlying semantics (laziness, sampling via `take`) are tested in Sections 1 and 3. REPL display formatting (tabular output, estimates) is a separate concern that requires nREPL middleware.

---

## 4. Dependencies

### Ordering Constraints

```
Phase 1 (Core Laziness)
  |
  +---> Phase 2 (Exploration Functions) -- depends on lazy seqs existing
  |
  +---> Phase 3 (Data Source Stubs) -- depends on lazy seqs for read-csv
  |       |
  |       +---> Phase 4 (Error Handling) -- depends on data source stubs
  |
  +---> Phase 5 (SQL Push-down) -- depends on connect/table stubs
  |
  +---> Phase 6 (REPL Integration) -- depends on lazy seqs, sampling model
```

Phase 1 is the prerequisite for everything. Phases 2, 3 can proceed in parallel after Phase 1.

### External Dependencies

**Current `deps.edn`** has only `instaparse`. New dependencies needed:

- **Phase 2**: None (pure Clojure, no external libs)
- **Phase 3 (file I/O)**: `clojure.data.json` for JSON read/write (or use a manual parser). `clojure.data.csv` for proper CSV parsing.
- **Phase 3 (DB)**: `com.github.seancorfield/next.jdbc` + JDBC drivers. **Not needed for stubs.**
- **Phase 3 (Parquet)**: `org.apache.parquet/parquet-hadoop`. **Not needed for stubs.**

For Phase 1, **zero new dependencies**.

### Internal Dependencies

- `src/datatwist/stdlib.clj` depends on new files:
  - `src/datatwist/explore.clj` (Phase 2)
  - `src/datatwist/config.clj` (Phase 2)
  - `src/datatwist/io.clj` (Phase 3)
- `src/datatwist/evaluator.clj` -- no changes in any phase
- `resources/datatwist.grammar` -- no changes in any phase
- `src/datatwist/parser.clj` -- no changes in any phase

---

## 5. Risks

### R1: Breaking Existing Tests (Severity: LOW)

Removing `(vec ...)` changes return types from PersistentVector to LazySeq. Tests using `(= expected result)` are safe. Only one test in features 1-7 checks `vector?` (`data_structures_test.clj:659`), and it tests a literal list, not a pipeline result.

**Mitigation**: Run `make test` after Step 1.1. If any test fails, it will be due to code that explicitly checks for vector type on pipeline results -- update those tests.

### R2: `dt-map` on Clojure Maps (Severity: MEDIUM)

Current `dt-map` has a branch for `(map? coll)` that does `(vec (map (fn [[k v]] (f ...)) coll))`. Removing `(vec ...)` here returns a lazy seq of `{:key k :value v}` maps. This is correct for laziness but changes behavior: `group-by` returns a map, and `data |> group-by f |> map g` now returns a lazy seq instead of a vector.

**Mitigation**: This is the intended behavior. Tests that do `(= expected result)` will still pass. If any test does `(nth (eval-dt "... |> group-by f |> map g") 0)`, `nth` works on lazy seqs.

### R3: `count` on Lazy Range Performance (Severity: LOW)

The test `count-forces-full-traversal-and-returns-exact-count` does `range 1 10000001 |> filter ... |> count`. With lazy evaluation, this traverses 10M elements. Clojure's `range` returns a `LongRange` which is `Counted` (O(1) count), but after `filter`, it's a lazy seq (O(n) count). The test expects this to work -- it's just slow (~1-2 seconds).

**Mitigation**: Accept the performance. The test verifies correctness, not speed. Can add a timeout if needed.

### R4: Clojure Chunking with `tap!` (Severity: LOW)

`tap!` calls `(take 100 data)`. On a chunked lazy seq, this may realize up to 128 elements (4 chunks of 32). The user sees 100 (take returns exactly 100). The extra realized elements are cached in the lazy seq and reused by downstream operations.

**Mitigation**: This is standard Clojure behavior. No action needed.

### R5: File Handle Leaks with `read-csv` (Severity: MEDIUM)

Lazy sequences backed by `line-seq` + `io/reader` hold the file handle open. If the sequence is never fully consumed and never GC'd, the handle leaks.

**Mitigation for Phase 3**:
- `collect` and `force!` fully consume the sequence (reader closes on exhaustion)
- `close!` explicitly closes the underlying reader (requires tracking the reader in metadata or a wrapper object)
- JVM GC finalizer closes `BufferedReader` eventually
- Document: "Always consume or close file-backed pipelines"

### R6: `save!` Requires `vec` Before Writing (Severity: LOW)

`save!` receives a lazy sequence and must write it to a file. It needs to force evaluation. The current stub `(fn [data & _args] data)` passes tests that only check passthrough. Real implementation must `(vec data)` or stream-write.

**Mitigation**: Implement `save!` to force evaluation during write, then return the original data. Or: `(let [materialized (vec data)] (write-file materialized path) materialized)`.

### R7: `repeat` Argument Order Ambiguity (Severity: LOW)

Clojure's `(repeat n x)` takes count first, value second. DataTwist's pipe-first means `"x" |> repeat 5` would be `(repeat "x" 5)` which is wrong. But `repeat` is typically used as a source generator (`repeat 5 "x"`), not as a pipeline step.

**Mitigation**: Define `repeat` as `(fn ([v] (clojure.core/repeat v)) ([n v] (clojure.core/repeat n v)))`. The 2-arity takes `(n, v)` matching Clojure convention. When used in a pipeline as `"x" |> repeat 5`, the pipe-first inserts `"x"` as first arg: `(repeat "x" 5)` = repeat "x" five times... wait, that's `(clojure.core/repeat "x" 5)` which is wrong (Clojure's repeat is `(repeat n x)`). We need `(fn ([v] (clojure.core/repeat v)) ([n v] (clojure.core/repeat n v)))` where `n` is the count. This means `repeat 5 "x"` in DataTwist becomes `(dt-repeat 5 "x")` which calls `(clojure.core/repeat 5 "x")` -- correct. Pipeline usage `"x" |> repeat` = `(dt-repeat "x")` = infinite repeat of "x" -- also correct.

---

## 6. Estimated Scope

### Files to Change

| File | Phase | Nature of Change |
|---|---|---|
| `src/datatwist/stdlib.clj` | 1, 2, 3 | Remove `vec` wrappers, add new functions |
| `src/datatwist/explore.clj` | 2 | **New file**: describe, schema, sample, freq, histogram, explain |
| `src/datatwist/config.clj` | 2 | **New file**: global config atom |
| `src/datatwist/io.clj` | 3 | **New file**: read-csv, read-json, read-jsonl, read-lines, save! |

### Files NOT Changed

| File | Reason |
|---|---|
| `resources/datatwist.grammar` | No new syntax needed |
| `src/datatwist/parser.clj` | Parser unchanged -- laziness is runtime |
| `src/datatwist/evaluator.clj` | `eval-pipeline` already composes lazily |
| `test/datatwist/lazy_eval_test.clj` | Test stubs are the target, not to be modified |
| All feature 1-7 test files | Should pass without changes |

### Rough Complexity

| Phase | Effort | Tests Enabled | Risk |
|---|---|---|---|
| Phase 1: Core Laziness | 1-2 days | ~30 | Low -- well-understood Clojure patterns |
| Phase 2: Exploration | 2-3 days | ~7 | Low -- pure computation, no I/O |
| Phase 3: Data Source Stubs | 2-4 days | ~25 | Medium -- file I/O, error handling |
| Phase 4: Error Handling | 0.5 days | ~4 | Low -- mostly covered by Phase 3 |
| **Total (Phases 1-4)** | **6-10 days** | **~66/71** | |

The remaining 5 tests that are hard to pass are:
1. REPL display formatting tests (need nREPL middleware)
2. Database integration tests (need real DB or mock)
3. Parquet read (needs external dependency)

### Test Run Command

After each phase, verify with:
```bash
clj -M -e "(require 'clojure.test 'datatwist.lazy-eval-test) (clojure.test/run-tests 'datatwist.lazy-eval-test)"
```

Also verify no regressions:
```bash
make test
```

---

## 7. Test Stub to BDD Mapping (Complete Reference)

Below is every test stub with its BDD scenario and what it expects at runtime. This is the definitive reference for an implementation agent.

### Section 1: Lazy Pipeline Construction

| Test | BDD Scenario | Asserts |
|---|---|---|
| `pipeline-without-materialization-is-lazy-and-does-not-execute` | S1.1 | `(not (instance? PersistentVector result))`, `(= [12 14 16 18 20] (vec result))` |
| `chaining-multiple-lazy-operations-builds-a-deeper-plan` | S1.2 | `(= [{:name "Alice" :email "a@a.com"} {:name "Charlie" :email "c@c.com"}] (vec result))` |
| `binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation` | S1.3 | `(= ["Alice"] result)` after collect |
| `lazy-pipelines-over-in-memory-collections-use-clojure-lazy-seq` | S1.4 | `(not (instance? PersistentVector result))`, `(= [2 4 6 8 10] (take 5 result))` |
| `nil-source-in-a-pipeline-produces-empty-collection` | S1.5 | `(= [] (eval-dt "nil \|> filter _ > 0 \|> collect"))` |

### Section 2: Materialization Functions

| Test | BDD Scenario | Asserts |
|---|---|---|
| `collect-forces-entire-pipeline-into-a-vector-in-memory` | S2.1 | `(= [30 40 50] result)`, `(instance? PersistentVector result)` |
| `collect-on-already-materialized-collection-is-a-no-op` | S2.2 | `(= [1 2 3] result)` |
| `count-forces-full-traversal-and-returns-exact-count` | S2.3 | `(= 1428571 result)` |
| `count-on-in-memory-collection-returns-exact-count-instantly` | S2.4 | `(= 5 result)` |
| `first-forces-evaluation-until-one-element-is-found` | S2.5 | `(= {:score 95} result)` |
| `reduce-folds-the-pipeline-into-a-single-value` | S2.6 | `(= 60 result)` |
| `reduce-with-explicit-initial-value` | S2.7 | `(= 15 result)` |
| `force-materializes-lazy-pipeline-and-returns-data-passthrough` | S2.8 | `(= [30 40 50] result)` after `force! \|> collect` |
| `force-bang-is-useful-for-ensuring-computation-happens-at-specific-point` | S2.9 | `(= [30 40 50] processed)`, `(instance? PersistentVector processed)` |
| `save-bang-writes-pipeline-output-to-file-and-returns-data-passthrough` | S2.10 | `(= 2 result)` -- save! to JSON then count |
| `save-bang-supports-multiple-file-formats-determined-by-file-extension` | S2.11 | parse OK, `(= 2 result-csv)`, `(= 2 result-json)` |
| `into-bang-inserts-pipeline-output-into-database-and-returns-data-passthrough` | S2.12 | `(throws-type? "into! nil \"table\"" Exception)` |
| `chaining-after-materialization-starts-a-new-pipeline` | S2.13 | `(= 2 result)` |

### Section 3: REPL Micro-sampling

| Test | Asserts |
|---|---|
| `repl-auto-sampling-does-not-force-full-pipeline` | `(not (instance? PersistentVector result))`, `(= [2 4 6 8 10] (take 5 result))` |
| `repl-preview-shows-tabular-format-for-collections-of-objects` | `(= [{:name "Alice" :score 95} {:name "Bob" :score 87}] (vec result))` |
| `repl-shows-full-result-for-small-collections-that-fit-in-sample` | `(= [3 4 5] (vec result))` |
| `repl-preview-for-scalar-result-shows-value-directly` | `(= 2 result)`, `(integer? result)` |
| `repl-uses-first-n-sampling-strategy-by-default` | `(= [2 4 6 8 10] result)` |

### Section 4: tap!

| Test | Asserts |
|---|---|
| `tap-shows-data-at-pipeline-step-and-passes-it-through-unchanged` | `(= [{:name "Alice"} {:name "Bob"}] result)` |
| `tap-with-a-label-passes-data-through-unchanged` | `(= ["Alice"] result)` |
| `tap-bang-shows-a-micro-sample-not-the-full-dataset` | `(not (instance? PersistentVector result))`, `(= [4 8 12 16 20] (take 5 result))` |
| `tap-returns-its-input-unchanged-passthrough` | `(= [30 40 50] result)` |
| `tap-with-transformation-function-does-not-affect-pipeline-data` | `(= ["Alice" "Bob"] result)` |
| `multiple-tap-calls-each-show-data-at-their-respective-point` | `(= 40 result)` |

### Section 5: Databases (all syntax-only except connect-throws)

| Test | Asserts |
|---|---|
| `connect-to-postgresql-database-is-valid-syntax` | `(not (parse-error? ...))`, `(throws? ...)` |
| `connect-with-explicit-credentials-object-is-valid-syntax` | `(not (parse-error? ...))` |
| `reference-a-database-table-as-a-lazy-data-source-is-valid-syntax` | `(not (parse-error? ...))` x2 |
| `pipeline-over-database-table-is-lazy-is-valid-syntax` | `(not (parse-error? ...))` |
| `raw-sql-query-as-lazy-data-source-is-valid-syntax` | `(not (parse-error? ...))` |
| `database-query-with-parameters-is-valid-syntax` | `(not (parse-error? ...))` |
| `table-source-materializes-on-collect-is-valid-syntax` | `(not (parse-error? ...))` |
| `close-bang-explicitly-releases-a-database-connection-is-valid-syntax` | `(not (parse-error? ...))` |

### Section 6: Files (all syntax-only)

| Test | Asserts |
|---|---|
| `read-csv-produces-lazy-sequence-of-maps-syntax` | `(not (parse-error? ...))` x2 |
| `read-csv-with-options-is-valid-syntax` | `(not (parse-error? ...))` |
| `read-csv-without-headers-is-valid-syntax` | `(not (parse-error? ...))` |
| `read-json-is-valid-syntax` | `(not (parse-error? ...))` |
| `read-jsonl-is-valid-syntax` | `(not (parse-error? ...))` |
| `read-lines-is-valid-syntax` | `(not (parse-error? ...))` |
| `read-parquet-is-valid-syntax` | `(not (parse-error? ...))` |
| `file-source-supports-full-pipeline-syntax` | `(not (parse-error? ...))` |

### Section 7: SQL Push-down (all syntax-only)

All 8 tests assert `(not (parse-error? ...))` only.

### Section 8: Explore/Describe

| Test | Asserts |
|---|---|
| `describe-shows-statistical-summary-and-returns-structured-data` | `(not (parse-error? ...))`, `(map? result)` |
| `schema-shows-column-names-and-inferred-types` | `(not (parse-error? ...))`, `(some? result)` |
| `schema-for-database-table-uses-database-metadata-is-valid-syntax` | `(not (parse-error? ...))` |
| `sample-returns-n-elements-from-the-data` | `(= 5 (count result))` |
| `freq-shows-frequency-table-for-a-field` | `(some? result)`, `(coll? result)` |
| `histogram-shows-ascii-histogram-for-numeric-field` | `(not (parse-error? ...))`, `(some? result)` |
| `explain-on-file-backed-pipeline-shows-execution-plan-without-reading` | `(not (parse-error? ...))` |

### Section 9: Pipeline as First-Class Object

| Test | Asserts |
|---|---|
| `pipeline-is-a-first-class-value-that-can-be-bound-to-a-name` | `(not (instance? PersistentVector result))`, `(= [30 40 50] (vec result))` |
| `pipeline-retains-metadata-about-each-step-is-valid-syntax` | `(not (parse-error? ...))` |
| `dtw-inspect-returns-sample-data-after-specific-pipeline-step` | `(not (parse-error? ...))`, `(coll? result)` |
| `pipeline-lazy-evaluation-is-transparent-same-object-reused` | `(= result-a result-b)`, `(= [3 4 5] result-a)` |

### Section 10: Error Handling

| Test | Asserts |
|---|---|
| `connection-failure-raises-descriptive-error` | `(throws? "connect ...")`, try-catch returns `"connection-failed"` |
| `file-not-found-raises-error-when-pipeline-is-materialized` | `(not (throws? "data is read-csv ..."))`, `(throws? "... \|> collect")`, `(throws-type? ... FileNotFoundException)` |
| `query-timeout-raises-an-error` | `(not (parse-error? ...))` |
| `schema-mismatch-is-nil-tolerant` | `(= [] result)` |

### Section 11: Integration

| Test | Asserts |
|---|---|
| `full-etl-pipeline-from-database-to-file-is-valid-syntax` | `(not (parse-error? ...))` |
| `streaming-pipeline-processes-large-file-without-unbounded-memory` | `(not (parse-error? ...))`, `(= 1 result)` (save! passthrough + count) |
| `lazy-pipeline-reuse-file-sources-re-open-on-each-materialization` | `(= [...] a)`, `(= [...] b)` -- two independent collects |
