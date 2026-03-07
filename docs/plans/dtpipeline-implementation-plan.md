# DTPipeline Implementation Plan (T-1 / T-2)

Date: 2026-03-07

## 0. Relationship to Prior Work

An earlier plan exists at `docs/dtpipeline-impl-plan.md` (2026-02-20). This document supersedes it with updated context, an explicit dependency on T-1 (lazy-eval redo), a sharper impact analysis, and concrete code-change recipes.

The research doc `docs/lazy-eval-introspection-research.md` covers the theoretical underpinnings (Spark/Polars/dbt comparisons, transducer fusion, memory budgets). The design doc `docs/lazy-eval-design.md` contains the 10 Q&A design decisions. Both remain the canonical references; this plan is the "how to build it" complement.

---

## 1. What DTPipeline Is

DTPipeline is a **reified pipeline record** -- a first-class runtime object returned by every `|>` expression. Instead of eagerly reducing data through step functions (the current behavior in `evaluator.clj` `eval-pipeline`, lines 1560-1585), the `|>` operator builds a plan that:

1. Remembers its source data and each step (function, label, source location)
2. Defers execution until a terminal operation demands results
3. Caches a sample at each step boundary after first execution
4. Supports programmatic inspection via `dtw.inspect`

**What it is NOT**: DTPipeline does not add new laziness at the element level. Clojure lazy sequences already handle that. DTPipeline adds **structural introspection** -- you can ask "what are the steps?" and "what did data look like after step 2?" without re-running the pipeline.

---

## 2. Record Design

### 2.1 File: `src/datatwist/pipeline.clj` (NEW)

```clojure
(ns datatwist.pipeline
  (:require [datatwist.config :as config]))

(defrecord PipelineStep
  [step-fn    ;; fn :: data -> result (compiled by eval-pipe-atom-with-fn-call)
   ast-node   ;; raw PipeAtom AST node (for explain, label regeneration)
   label      ;; String: "filter _.active", "map _.name", etc.
   loc        ;; {:line N :col N} or nil (from Instaparse metadata)
   index])    ;; 0-based position in the pipeline

(defrecord DTPipeline
  [source        ;; Initial data value (collection, lazy seq, future DataSource)
   steps         ;; PersistentVector of PipelineStep records
   env           ;; Captured lexical environment
   sample-cache  ;; Atom<PersistentVector>: (count steps + 1) entries, nil or vec of rows
                 ;;   index 0 = source sample, index k+1 = sample after step k
   result-cache  ;; Atom: nil or the fully realized final result
   realized?     ;; Atom<boolean>: true after first full execution
   meta-info])   ;; {:created-at <Instant>}
```

**Why `result-cache`**: The earlier plan noted a gap -- `realized?` was set but there was no cached final result, so calling `force!` twice would re-execute. Adding `result-cache` (Atom, initially nil) solves double-evaluation without any cleverness. When `realize-pipeline` runs, it stores the final result in `result-cache`. Subsequent calls return the cached value.

### 2.2 Why defrecord, not deftype

`defrecord` gives us `IPersistentMap`, `toString`, `equals`, `hashCode` for free. The mutable state (caches, realized flag) lives in atoms stored as field values -- idiomatic Clojure. `deftype` would require implementing every protocol by hand for no benefit.

### 2.3 Protocols / Interfaces to Implement

DTPipeline must behave like a collection at Clojure interop boundaries so existing tests and stdlib code don't break.

| Interface | Purpose | Implementation |
|---|---|---|
| `clojure.lang.Seqable` | `(seq pipeline)` works | Force, return `(seq realized-vec)` |
| `clojure.lang.Sequential` | Marker: `(sequential? pipeline)` returns true | Marker only, no methods |
| `clojure.lang.Counted` | `(count pipeline)` works at JVM level | Force, return `(count realized-vec)` |
| `clojure.lang.IPersistentCollection` | `(.equiv pipeline other)` for `=` | Force, compare realized values |
| `Object.equals` | Java equality | Force, delegate to realized vec |
| `Object.hashCode` | Java hash | Force, delegate to realized vec |
| `Object.toString` | `(str pipeline)` | Force, return `(str realized-vec)` |

**Critical: override `equiv`/`equals` to force and compare by value.** Without this, `(= [1 2 3] pipeline)` would compare record fields instead of data, breaking ~7 tests in `pipeline_test.clj` and all equality assertions across the test suite.

Implementation pattern (in `pipeline.clj`, after the defrecord):

```clojure
;; DTPipeline must be defined as deftype (not defrecord) to override equals/hashCode,
;; OR we use defrecord + extend-protocol for Seqable/Sequential.
;;
;; Actually: defrecord does NOT allow overriding Object methods.
;; Switch to deftype with explicit ILookup for field access,
;; OR use a wrapper approach.
;;
;; RECOMMENDED: Use deftype that implements the needed interfaces directly.
;; See Section 2.4 for the deftype skeleton.
```

### 2.4 deftype vs defrecord: The equals Problem

`defrecord` generates `equals`/`hashCode` based on field-by-field comparison. You **cannot override** `equals` on a defrecord -- it is baked in by the compiler. This is a problem because we need `equals` to force materialization and compare by data value.

**Solution**: Use `deftype` with explicit protocol implementations.

```clojure
(deftype DTPipeline
  [source steps env sample-cache result-cache realized? meta-info]

  clojure.lang.Seqable
  (seq [this] (seq (realize-pipeline this)))

  clojure.lang.Sequential  ;; marker interface

  clojure.lang.Counted
  (count [this] (clojure.core/count (realize-pipeline this)))

  clojure.lang.IPersistentCollection
  (equiv [this other] (.equals (realize-pipeline this) other))
  (empty [_] (clojure.lang.PersistentVector/EMPTY))
  (cons [this o] (.cons ^clojure.lang.IPersistentCollection (realize-pipeline this) o))

  clojure.lang.ILookup  ;; so (:steps pipeline) works
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k not-found]
    (case k
      :source source
      :steps steps
      :env env
      :sample-cache sample-cache
      :result-cache result-cache
      :realized? realized?
      :meta-info meta-info
      not-found))

  Object
  (equals [this other]
    (if (instance? DTPipeline other)
      (.equals (realize-pipeline this) (realize-pipeline other))
      (.equals (realize-pipeline this) other)))
  (hashCode [this]
    (.hashCode (realize-pipeline this)))
  (toString [this]
    (str (realize-pipeline this))))
```

**Trade-off**: `deftype` requires more boilerplate, but it's the only way to get correct equality semantics. Field access via keywords (`:steps`, `:source`) is supported by implementing `ILookup`.

---

## 3. How `|>` Compilation Changes

### 3.1 Current Code (evaluator.clj, lines 760-770)

```clojure
;; --- Pipeline ---
(= :Pipeline tag)
(let [source-node (first children)
      steps       (rest children)
      data        (eval-node source-node env)]
  (eval-pipeline data steps env))

;; --- SourcelessPipeline ---
(= :SourcelessPipeline tag)
(let [steps children]
  (fn [data]
    (eval-pipeline data steps env)))
```

`eval-pipeline` (lines 1560-1585) is a `loop/recur` that eagerly runs each step, with autotap support.

### 3.2 New Code

```clojure
;; --- Pipeline ---
(= :Pipeline tag)
(let [source-node (first children)
      step-nodes  (rest children)
      data        (eval-node source-node env)]
  (pipeline/build-pipeline data step-nodes env eval-pipe-atom-with-fn-call))

;; --- SourcelessPipeline ---
(= :SourcelessPipeline tag)
(let [steps children]
  (fn [data]
    (pipeline/build-pipeline data steps env eval-pipe-atom-with-fn-call)))
```

### 3.3 build-pipeline (in pipeline.clj)

```clojure
(defn build-pipeline [source step-nodes env step-compiler]
  (let [steps (vec (map-indexed
                     (fn [idx step-node]
                       (->PipelineStep
                         (step-compiler step-node env)
                         step-node
                         (extract-label step-node)
                         (extract-loc step-node)
                         idx))
                     step-nodes))]
    (->DTPipeline
      source
      steps
      env
      (atom (vec (repeat (inc (count steps)) nil)))  ; sample-cache
      (atom nil)                                      ; result-cache
      (atom false)                                    ; realized?
      {:created-at (java.time.Instant/now)})))
```

### 3.4 Circular Dependency Avoidance

`pipeline.clj` needs `eval-pipe-atom-with-fn-call` from `evaluator.clj`. `evaluator.clj` needs `build-pipeline` from `pipeline.clj`. Classic circular dependency.

**Solution**: Pass `eval-pipe-atom-with-fn-call` as a parameter (`step-compiler`) to `build-pipeline`. No circular require. `pipeline.clj` requires only `datatwist.config`.

### 3.5 autotap! Handling in DTPipeline

The current `eval-pipeline` has inline autotap detection -- it checks if a step is the `autotap!` identifier and switches to tapping mode.

For DTPipeline, autotap must be handled at **build time** (when constructing steps) or at **realize time** (when executing steps). Options:

**Option A (recommended): Handle at realize time.** Store the raw step-nodes in PipelineStep. During `realize-pipeline`, detect autotap sentinels and inject tap calls. This preserves the current behavior without duplicating logic.

**Option B: Handle at build time.** Filter out the autotap node, mark a `tapping-from` index in the DTPipeline metadata, and instrument step functions during construction. Cleaner but requires restructuring the autotap logic.

For Phase 1, use Option A. The `realize-pipeline` function mirrors the current `eval-pipeline` loop:

```clojure
(defn realize-pipeline [pipeline]
  (if-let [cached @(.result-cache pipeline)]
    cached
    (let [source      (.source pipeline)
          steps       (.steps pipeline)
          sample-size (config/get-config :SAMPLE_SIZE)
          tap-fn      (when (.env pipeline)
                        (datatwist.env/lookup (.env pipeline) "tap!"))
          ;; Cache source sample
          _ (swap! (.sample-cache pipeline) assoc 0
                   (vec (take sample-size
                              (if (sequential? source) source [source]))))
          result
          (loop [data      source
                 idx       0
                 tapping?  false]
            (if (>= idx (count steps))
              data
              (let [step (nth steps idx)]
                (if (autotap-step? step)
                  ;; autotap sentinel: tap current data, switch to tapping mode
                  (do (when tap-fn (tap-fn data))
                      (recur data (inc idx) true))
                  (let [result ((.step-fn step) data)
                        sample (vec (take sample-size
                                         (if (sequential? result) result [result])))]
                    (swap! (.sample-cache pipeline) assoc (inc idx) sample)
                    (when (and tapping? tap-fn)
                      (tap-fn result (.label step)))
                    (recur result (inc idx) tapping?))))))]
      (reset! (.result-cache pipeline) result)
      (reset! (.realized? pipeline) true)
      result)))
```

### 3.6 `*source*` Dynamic Var

`extract-step-label` (evaluator.clj line 219) uses `*source*` to extract label text from the raw source string via Instaparse metadata. Since `build-pipeline` is called from within `eval-node`, which runs inside `(binding [*source* input] ...)`, the dynamic var is available at build time.

`extract-label` in `pipeline.clj` needs access to `*source*`. Options:
1. Pass `*source*` as a parameter to `build-pipeline`
2. Have `pipeline.clj` reference `evaluator/*source*` directly (creates a dependency)
3. Move `extract-step-label` to `pipeline.clj` and pass the source string

**Recommended**: Option 1. Pass `*source*` as a string parameter.

---

## 4. Terminal Operations

### 4.1 Which Functions Are Terminals

From PRD Section 8 and design doc Q5:

| Terminal | Current location in stdlib | Change needed |
|---|---|---|
| `force!` | Inline fn, line 655 | Dispatch on DTPipeline |
| `count` | Raw `clojure.core/count`, line 544 | Replace with `dt-count` |
| `first` | Raw `clojure.core/first`, line 545 | Replace with `dt-first` |
| `last` | Raw `clojure.core/last`, line 546 | Replace with `dt-last` |
| `reduce` | `dt-reduce` (existing) | Add DTPipeline arm |
| `sort` | `dt-sort` (existing, returns vec) | Add DTPipeline arm (force input) |
| `sort-by` | `dt-sort-by` (existing, returns vec) | Add DTPipeline arm (force input) |
| `group-by` | `dt-group-by` (existing) | Add DTPipeline arm (force input) |
| `reverse` | Direct Clojure `reverse` wrapped | Add DTPipeline arm |

### 4.2 Implementation Pattern

All terminal functions follow the same pattern:

```clojure
(defn dt-count [x]
  (if (instance? datatwist.pipeline.DTPipeline x)
    (count (pipeline/realize-pipeline x))
    (count x)))
```

For `force!`, which is passthrough (returns the data):

```clojure
(defn dt-force! [data]
  (let [result (if (instance? datatwist.pipeline.DTPipeline data)
                 (vec (pipeline/realize-pipeline data))
                 (if (vector? data) data (vec data)))
        limit  (config/get-config :MAX_COLLECT_ROWS)]
    (if (and limit (> (count result) limit))
      (do (println (str "WARNING: Result truncated to " limit " rows (MAX_COLLECT_ROWS)"))
          (subvec result 0 limit))
      result)))
```

### 4.3 Eager Barriers (sort, sort-by, group-by, reverse)

These are not "terminal" in the sense of ending the pipeline -- they consume their input fully and produce a new concrete collection. In the current code, they already call `(vec ...)` on their results. With DTPipeline, they need to force the input:

```clojure
(defn dt-sort [coll]
  (let [data (if (instance? DTPipeline coll) (realize-pipeline coll) coll)]
    (vec (sort data))))
```

After an eager barrier, the result is a vector. If it feeds into further pipeline steps, those steps will see a vector, not a DTPipeline. This is correct -- the DTPipeline only wraps the full `|>` expression, and intermediate forcing produces concrete data within the pipeline's step execution.

### 4.4 `str` Conversion

```clojure
"str" (fn [v]
        (cond
          (instance? DTPipeline v) (str (vec (realize-pipeline v)))
          (and (sequential? v) (not (vector? v))) (str (vec v))
          :else (str v)))
```

### 4.5 `=` via Clojure's DataTwist evaluator

The DataTwist `=` operator goes through `eval-node` for `:CompareExpr` (evaluator.clj lines ~520-530). It calls Clojure's `=` on the evaluated operands. Since DTPipeline overrides `Object.equals` to force and compare, `plan = [1 2 3]` in DataTwist will work correctly.

---

## 5. Step Caching and dtw.inspect

### 5.1 Cache Structure

`sample-cache` is an Atom holding a vector of `(count steps + 1)` entries:

```
index 0     = sample of source data (before any step)
index 1     = sample after step 0
index 2     = sample after step 1
...
index N     = sample after step N-1 (= final result sample)
```

Each entry is `nil` (not cached) or a `PersistentVector` of up to `SAMPLE_SIZE` rows (default 100).

### 5.2 When Samples Are Captured

During `realize-pipeline`, after each step executes, the result is sampled:

```clojure
(let [sample (vec (take sample-size
                       (if (sequential? result) result [result])))]
  (swap! (.sample-cache pipeline) assoc (inc idx) sample))
```

Non-sequential results (scalars, maps) are wrapped in a single-element vector.

### 5.3 dtw.inspect

`dtw.inspect` is a stdlib function:

```clojure
"dtw.inspect" (fn [pipeline step-idx sample-size]
                (when-not (instance? DTPipeline pipeline)
                  (throw (ex-info "dtw.inspect requires a DTPipeline"
                                  {:dt/error true :code "DT-R003"
                                   :category "RUNTIME ERROR"
                                   :hint "Bind a pipeline with `is` first."})))
                (pipeline/inspect-step pipeline step-idx sample-size))
```

In `pipeline.clj`:

```clojure
(defn inspect-step [pipeline step-idx requested-size]
  ;; Force if not yet realized
  (when-not @(.realized? pipeline)
    (realize-pipeline pipeline))
  ;; Return from cache, capped to requested size
  (let [cache-idx (inc step-idx)  ;; step 0 is at cache index 1
        cached    (get @(.sample-cache pipeline) cache-idx)]
    (if cached
      (vec (take requested-size cached))
      (throw (ex-info (str "No cache entry for step " step-idx)
                      {:dt/error true :code "DT-R003"
                       :step-idx step-idx
                       :cache-size (count @(.sample-cache pipeline))})))))
```

### 5.4 Cache Invalidation

No explicit invalidation needed. When a user writes:

```
plan is data |> filter _.x
plan |> force!
plan is data |> filter _.y
```

The second `is` binding creates a new DTPipeline with a fresh `sample-cache` atom. The old record becomes unreachable and is GC'd. This satisfies the BDD scenario "Reassigning a pipeline name invalidates the old pipeline cache" (BDD line 527).

### 5.5 explain

`dt-explain` in stdlib already has a DTPipeline stub. Update:

```clojure
(defn- dt-explain [data]
  (cond
    (instance? DTPipeline data)
    (let [steps (.steps data)]
      (str "Pipeline (" (count steps) " steps):\n"
           "  source\n"
           (clojure.string/join "\n"
             (map #(str "  |> " (.label %)) steps))))
    (vector? data)
    (str "Materialized collection of " (count data) " items")
    (sequential? data)
    "Lazy sequence (not a DTPipeline)"
    :else
    (str "Value: " (dt-type-of data))))
```

---

## 6. Impact Analysis: What Breaks

### 6.1 Tests That Will Break

**pipeline_test.clj** (~724 lines, ~50 deftests): Most tests end with a terminal operation (`count`, `first`, `last`, `sort |> first`). About 7 tests directly compare pipeline results with vectors using `(= [...] (eval-dt "... |> filter/map/..."))`. These will work IF the DTPipeline `equals` override is implemented correctly. If `equals` is not overridden, these 7 tests break.

Specific lines in pipeline_test.clj:
- Line 219: `(= [4 5] (eval-dt "[1 2 3 4 5] |> filter _ > 3"))`
- Line 223: `(= [10 20 30] (eval-dt "[1 2 3] |> map _ * 10"))`
- Line 245: `(= [1 2 3] (eval-dt "[1 2 3 4 5] |> take 3"))`
- Line 249: `(= [3 4 5] (eval-dt "[1 2 3 4 5] |> drop 2"))`
- Line 277: `(= [1 2 3] (eval-dt "[1 2 2 3 3 3] |> distinct"))`
- Line 281: `(= [1 2 3 4 5] (eval-dt "[[1 2] [3 4] [5]] |> flatten"))`
- Line 285: `(= [3 2 1] (eval-dt "[1 2 3] |> reverse"))`

**lazy_eval_test.clj** (lines 41, 53, 103): Type assertions.
- Line 41: `(not (instance? PersistentVector result))` -- remains true (DTPipeline is not PersistentVector). SAFE.
- Line 53: Same. SAFE.
- Line 103: `(instance? PersistentVector result)` after `force!` -- will remain true IF `force!` returns a vector. SAFE if dt-force! is implemented correctly.

**data_structures_test.clj** (line 659): `(vector? (eval-dt "[1 2 3]"))` -- this evaluates a list literal, NOT a pipeline. SAFE -- no pipeline involved.

**Other test files**: All tests that evaluate DataTwist expressions ending with pipelines use `(= expected result)`. As long as DTPipeline equality works, they pass. Grep across all test files shows no `(vector? ...)` assertions on pipeline results except the ones listed above.

### 6.2 Stdlib Code That Branches on `sequential?`

About 15 places in `stdlib.clj` check `(sequential? x)`:
- `dt-type-of` (line 17): Returns "list" for sequential values. DTPipeline implementing `Sequential` keeps this correct.
- `dt-contains?` (line 57): Iterates over sequential colls. DTPipeline implementing `Seqable` makes `some` work.
- `dt-filter` (line 190): Calls `(filter pred coll)`. Clojure's `filter` calls `seq` on its argument. DTPipeline implementing `Seqable` handles this.
- `dt-map` (line 222): Same pattern as filter.
- `tap!` (lines 683, 694, 700): Takes a sample from sequential data. Works via `Seqable`.
- `str` (line 595): Needs explicit DTPipeline check (see Section 4.4).

**Verdict**: Implementing `Sequential` + `Seqable` on DTPipeline makes all `sequential?` and `seq`-based operations work transparently. Only `str` needs an explicit DTPipeline check because it should print the forced result, not `"datatwist.pipeline.DTPipeline@..."`.

### 6.3 evaluator.clj Internal Usage

The evaluator itself doesn't branch on `sequential?` for pipeline results. The `eval-pipeline` function is replaced, and its callers (the `:Pipeline` and `:SourcelessPipeline` cases) call `build-pipeline` instead. No other evaluator code touches pipeline results by type.

### 6.4 GraalVM Native Image

The project has a GraalVM native-image build (`build.clj`, `Makefile` native target, `resources/META-INF/native-image/`). `deftype` with protocol implementations may need reflection config entries. Check after implementation and add to `reflect-config.json` if needed.

---

## 7. Phased Implementation

### Phase 1: Core DTPipeline Record (T-2, estimated 12-17 hours)

**Step 1: Create `src/datatwist/pipeline.clj`** (complexity: medium, ~150 lines)

- Define `PipelineStep` defrecord
- Define `DTPipeline` deftype with all interface implementations (Seqable, Sequential, Counted, IPersistentCollection, ILookup, Object equals/hashCode/toString)
- Implement `realize-pipeline` with sample caching and autotap support
- Implement `inspect-step`
- Implement `extract-label` (reuse logic from evaluator.clj `extract-step-label`)
- Implement `extract-loc` (Instaparse metadata to `{:line :col}`)
- Implement `build-pipeline`
- Unit test these functions directly in REPL without evaluator integration

**Step 2: Wire into evaluator.clj** (complexity: low, ~15 lines changed)

- Replace `:Pipeline` case body (lines 760-764) to call `pipeline/build-pipeline`
- Replace `:SourcelessPipeline` case body (lines 766-770)
- Pass `eval-pipe-atom-with-fn-call` as parameter
- Add `(:require [datatwist.pipeline :as pipeline])` to ns
- Keep `eval-pipeline` as dead code temporarily (can remove in cleanup)
- Run `pipeline_test.clj` -- expect failures if equals not yet working
- Run `lazy_eval_test.clj` -- expect stub tests to still be stubs

**Step 3: Update stdlib terminal functions** (complexity: low, ~60 lines changed)

- Replace `count` with `dt-count` (DTPipeline dispatch)
- Replace `first` with `dt-first`
- Replace `last` with `dt-last`
- Update `force!` inline fn with DTPipeline dispatch
- Update `dt-reduce` with DTPipeline arm
- Update `dt-sort`, `dt-sort-by`, `dt-group-by` with DTPipeline input forcing
- Update `str` with DTPipeline arm
- Update `dt-explain` with DTPipeline step display
- Add `(:require [datatwist.pipeline :as pipeline])` to stdlib ns
- Run `make test` -- all existing tests should pass

**Step 4: Add `dtw.inspect` to stdlib** (complexity: low, ~15 lines)

- Add `"dtw.inspect"` entry in `default-env`
- Validate arguments (pipeline must be DTPipeline, step-idx must be int, sample-size must be int)

**Step 5: Replace stub tests** (complexity: medium, ~100 lines)

Replace the 9 DTPipeline-related stub tests in `lazy_eval_test.clj` with real assertions:

| Stub test (line) | BDD scenario (line) | What to assert |
|---|---|---|
| `a-pipeline-without-a-terminal-operation-is-lazy-and-does-not-execute` (21) | 36 | `(instance? DTPipeline result)`, `(false? @(.realized? result))` |
| `chaining-multiple-lazy-operations-builds-a-deeper-plan` (26) | 44 | `(= 3 (count (.steps result)))` |
| `binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation` (30) | BDD implicit | `(false? @(.realized? result))` |
| `pipeline-is-a-first-class-dtpipeline-value...` (425) | 495 | `(instance? DTPipeline result)` |
| `dtpipeline-retains-metadata-about-each-step` (429) | 503 | Step count, step labels contain expected text |
| `dtpipeline-caches-a-sample-at-each-step-boundary...` (433) | 515 | Cache entries non-nil after force |
| `reassigning-a-pipeline-name-invalidates...` (437) | 527 | New pipeline has nil cache entries |
| `dtw-inspect-returns-sample-data...` (443) | 543 | Returns vector, correct size |
| `dtw-inspect-on-an-un-executed-pipeline...` (447) | 556 | Triggers execution, returns sample |

Also update the 3 Phase 1 laziness tests (lines 21-32) to check for DTPipeline instead of generic lazy seq.

**Step 6: Fix any type assertion breakage** (complexity: low, ~5 lines)

- Audit for `(vector? result)` or `(instance? PersistentVector result)` assertions on pipeline outputs
- Expected: no breakage if equals override works. But verify with `make test`.

### Phase 2: Transducer Fusion (future, not in T-1/T-2 scope)

Consecutive fusible steps (filter, map, take, drop, distinct) detected at build time, compiled to a single transducer via `comp`. Non-fusible steps (sort-by, group-by) act as fusion barriers. Blocked on Phase 1 completion.

### Phase 3: Push-down Optimization (future, blocked on Phase 1 + DataSource protocol)

When the pipeline source is a DataSource (DB connection, file), pushable operations (filter, sort, limit) are translated to SQL or file-seek operations. Design doc at `docs/pushdown-design.md`.

---

## 8. Dependency: T-1 (Lazy-Eval Redo)

T-1 is "redo impl for lazy-eval" and has priority A (higher than T-2's priority B). The current lazy-eval implementation has partial coverage: `filter`, `map`, `take`, `drop` return Clojure lazy seqs, but `force!`, `collect`, `range`, `repeat`, `iterate`, `cycle` and other stdlib functions were added independently.

**T-2 depends on T-1** because:

1. DTPipeline wraps the pipeline result. If the underlying step functions don't produce lazy seqs (some still `vec`-wrap), the DTPipeline's laziness guarantee is violated.
2. Several stdlib functions referenced by T-2 (like `collect`) may be missing or incomplete until T-1 finishes.
3. Test stubs from the lazy-eval BDD feature need the Phase 1 lazy-seq infrastructure to be solid before layering DTPipeline on top.

**Recommended order**: Complete T-1 first (ensure all collection-returning stdlib functions produce lazy seqs), then implement T-2 (DTPipeline record) on top.

If they must be parallelized: T-2's `pipeline.clj` can be written and unit-tested in isolation (using hand-built step functions), but integration with the evaluator should wait for T-1 to stabilize.

---

## 9. Complexity Estimates

| Step | New/changed lines | Effort | Risk |
|---|---|---|---|
| 1. `pipeline.clj` (DTPipeline deftype + realize + inspect) | ~180 new | 6-8 hours | Medium (deftype interface boilerplate, autotap porting) |
| 2. Evaluator wiring | ~15 changed | 1 hour | Low |
| 3. Stdlib terminal updates | ~60 changed | 2-3 hours | Low (mechanical dispatch) |
| 4. `dtw.inspect` in stdlib | ~15 new | 30 min | Low |
| 5. Replace stub tests | ~120 new | 3-4 hours | Medium (test assertions need precise type checks) |
| 6. Fix type breakage | ~5-10 changed | 30 min | Low |
| **Total** | **~400 lines** | **~14-18 hours** | |

---

## 10. File Inventory

| File | Action | Description |
|---|---|---|
| `src/datatwist/pipeline.clj` | CREATE | DTPipeline deftype, PipelineStep defrecord, realize-pipeline, inspect-step, build-pipeline, label/loc extraction |
| `src/datatwist/evaluator.clj` | MODIFY | `:Pipeline` and `:SourcelessPipeline` cases call `build-pipeline`; add pipeline require |
| `src/datatwist/stdlib.clj` | MODIFY | DTPipeline-aware count, first, last, force!, reduce, sort, sort-by, group-by, str, explain; add dtw.inspect |
| `test/datatwist/lazy_eval_test.clj` | MODIFY | Replace 9+ stub tests with real assertions |
| `resources/datatwist.grammar` | UNCHANGED | Pipeline grammar already correct |
| `src/datatwist/parser.clj` | UNCHANGED | No changes needed |

---

## 11. Open Questions

1. **Should SourcelessPipeline also return a DTPipeline?** Currently `|> filter _.x |> map _.y` (no source) returns a function. With DTPipeline, it could return a "partial pipeline" that becomes a DTPipeline when applied. For Phase 1, keep it as a function -- it's simpler and matches the current behavior where sourceless pipelines are used as higher-order function arguments.

2. **Should `(type-of pipeline)` return "pipeline" or "list"?** The BDD says DTPipeline is a "first-class value" that can be "passed to functions or reused." The `dt-type-of` function checks `sequential?` first, which would return "list" for DTPipeline. This may be confusing. Consider adding a DTPipeline check before the sequential check to return "pipeline" as a distinct type.

3. **Thread safety of sample-cache and result-cache atoms**: Multiple threads calling `force!` on the same DTPipeline will both enter `realize-pipeline`. The `if-let [cached ...]` check at the top of `realize-pipeline` prevents double-execution in the common case, but there's a race window. For Phase 1, this is acceptable -- DataTwist is single-threaded. For Phase 2, consider using `delay` or `locking`.

4. **GraalVM reflection config**: The `deftype` may need entries in `resources/META-INF/native-image/reflect-config.json`. Test after implementation.
