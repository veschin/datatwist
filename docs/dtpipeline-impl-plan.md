# DTPipeline Implementation Plan

Date: 2026-02-20

## Context and Scope

DataTwist pipelines currently execute eagerly via `eval-pipeline` (a `reduce` in
`evaluator.clj`, lines 1417-1424). Each `|>` step runs immediately and discards
its intermediate result. The evaluator returns the raw Clojure value produced by the
last step — a `LazySeq`, a `PersistentVector`, a scalar, etc.

The design (documented in `docs/lazy-eval-introspection-research.md`) requires a
**DTPipeline record**: a first-class runtime object returned by every `|>` expression
that carries step metadata, a per-step sample cache, and deferred evaluation. This
plan covers exactly what to build and in what order to satisfy the 9 BDD scenarios
in `bdd/8-lazy-eval-data-sources.feature` (lines 495-563) and their stub tests in
`test/datatwist/lazy_eval_test.clj` (lines 329-353).

**Orthogonality note** (from `CLAUDE.md` key design decisions): Laziness and DTPipeline
are independent concerns. Lazy sequences (`dt-filter`, `dt-map` etc. already return
lazy seqs) are the *execution model*. DTPipeline is the *introspection model*. Phase 1
lazy-seq changes are already partially implemented. This plan covers DTPipeline only.

---

## 1. Record Definition

### 1.1 Records

Two `defrecord` types in a new file `src/datatwist/pipeline.clj`:

```clojure
(defrecord PipelineStep
  [step-fn    ;; fn :: data -> result (compiled step, from eval-pipe-atom-with-fn-call)
   ast-node   ;; the PipeAtom AST node (for explain and label extraction)
   label      ;; String: human-readable, e.g. "filter _.active" or "map _.name"
   loc        ;; {:line N :col N} — source location from Instaparse metadata
   index])    ;; 0-based step number within the pipeline

(defrecord DTPipeline
  [source        ;; The initial data value (collection, lazy seq, DataSource, etc.)
   steps         ;; Persistent vector of PipelineStep records
   env           ;; Captured lexical environment (for explain, re-execution)
   sample-cache  ;; Atom: vector of (count steps + 1) entries, nil or vector of rows
                 ;;   index 0 = source sample
                 ;;   index k = sample after step (k-1)
   realized?     ;; Atom<boolean>: true once the pipeline has been fully forced
   meta-info])   ;; Plain map: {:created-at <inst> :source-text "..."}
```

### 1.2 defrecord vs deftype

Use `defrecord`. Reasons:

- `defrecord` automatically generates `equals`, `hashCode`, `toString`, and implements
  `IPersistentMap` so fields are accessible via keywords. This covers the auto-materialize
  requirement for `=` without any additional protocol implementations.
- `defrecord` fields are immutable. The two mutable cells (`sample-cache` and `realized?`)
  are `atom`s stored as field values, which is idiomatic.
- `deftype` would require manually implementing every protocol and map interface. The
  added control is unnecessary for this use case.

### 1.3 Protocols to Implement

The BDD requires that `force!`, `count`, `first`, `reduce`, and `=` all work on a
`DTPipeline`. The table below lists what each operation needs and how to satisfy it.

| Operation | Required behavior | How to satisfy |
|---|---|---|
| `force!` | Materialize to vector; return it | `defn dt-force! [x]` dispatches on `DTPipeline` |
| `count` | Force full traversal; return element count | `defn dt-count [x]` dispatches |
| `first` | Force until first element; return it | `defn dt-first [x]` dispatches |
| `reduce` | Fold over realized data | `defn dt-reduce [x f]` dispatches |
| `=` | Compare by value after materialization | `defrecord` inherits `IPersistentMap` equality; override `equals` to materialize |
| `str` | Human-readable string of realized data | Override `toString` to materialize |
| `sequential?` / `seqable?` | So existing code does not crash on `(sequential? pipeline)` | Implement `clojure.lang.Seqable` |

**Protocol choice**: Do NOT implement `clojure.lang.ISeq` (that would make DTPipeline
look like a sequence, which can cause accidental auto-realization). Instead, implement
`clojure.lang.Seqable` so that `(seq pipeline)` works (triggers materialization once).

Implement `clojure.lang.IPersistentCollection` so `count` works without dispatch logic
in every stdlib function. Override `equiv` (Clojure's equality primitive) to force and
compare realized values.

Implement `Object/equals` and `Object/hashCode` by forcing and delegating to the
realized vector.

Implement `Object/toString` by forcing and returning `(str realized-vec)`.

These implementations live in `src/datatwist/pipeline.clj` as `deftype` protocol
extensions or as `defrecord` method overrides using `reify`.

**Recommended pattern** — extend the record with `extend-protocol` after definition:

```clojure
(extend-protocol clojure.lang.Seqable
  DTPipeline
  (seq [this] (seq (realize-pipeline this))))
```

---

## 2. Evaluator Changes

### 2.1 Current `eval-pipeline`

Location: `src/datatwist/evaluator.clj`, lines 1417-1424.

```clojure
;; CURRENT
(defn- eval-pipeline [data steps env]
  (reduce (fn [d step-node]
            (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
              (step-fn d)))
          data
          steps))
```

This reduce runs all steps immediately. It must be replaced by a pipeline builder.

### 2.2 New `build-pipeline`

Replace the body of the `:Pipeline` case in `eval-node` (lines 638-643) with a call
to `pipeline/build-pipeline` instead of `eval-pipeline`:

```clojure
;; In eval-node, :Pipeline case (currently lines 638-643)
(= :Pipeline tag)
(let [source-node (first children)
      step-nodes  (rest children)
      data        (eval-node source-node env)]
  (pipeline/build-pipeline data step-nodes env))
```

`build-pipeline` in `src/datatwist/pipeline.clj`:

```clojure
(defn build-pipeline [source step-nodes env]
  (let [steps (vec (map-indexed
                     (fn [idx step-node]
                       (->PipelineStep
                         (eval-pipe-atom-with-fn-call step-node env)
                         step-node
                         (step-label step-node)
                         (step-loc step-node)
                         idx))
                     step-nodes))]
    (->DTPipeline
      source
      steps
      env
      (atom (vec (repeat (inc (count steps)) nil)))  ;; sample-cache: nil = not yet cached
      (atom false)                                    ;; realized?
      {:created-at (java.time.Instant/now)})))
```

`eval-pipe-atom-with-fn-call` stays unchanged — it is still the step compiler.

**Forward declaration**: `pipeline.clj` needs `eval-pipe-atom-with-fn-call`. To avoid
a circular dependency (`evaluator.clj` requires `pipeline.clj`, which needs something
from `evaluator.clj`), pass `eval-pipe-atom-with-fn-call` as an argument to
`build-pipeline` rather than requiring `evaluator`:

```clojure
;; In evaluator.clj, call site
(pipeline/build-pipeline data step-nodes env eval-pipe-atom-with-fn-call)

;; In pipeline.clj
(defn build-pipeline [source step-nodes env step-compiler]
  ...)
```

### 2.3 SourcelessPipeline stays unchanged

`SourcelessPipeline` (lines 645-649) returns a `fn [data -> ...]`. When that function
is called (e.g., assigned to a name and later applied), it will call `build-pipeline`
internally if needed. For now, SourcelessPipeline produces a plain function — that is
correct and does not need to change for this phase.

### 2.4 Auto-materialize vs stay lazy

`DTPipeline` is NOT auto-materialized in the evaluator. The design decision from
`lazy-eval-design.md` section Q5: "Explicit materialization only. The REPL is the
sole implicit materializer."

The only cases where a `DTPipeline` is forced are:
1. Explicit terminal operation: `force!`, `collect`, `count`, `first`, `reduce`.
2. `=` comparison (Clojure forces sequences for equality).
3. `str` conversion.
4. `dtw/inspect` (triggers partial or full execution to populate cache).
5. REPL auto-sample (future: phase 6 in `lazy-eval-design.md`).

### 2.5 Terminal operation detection

There is no AST-level detection of terminal operations in the evaluator. Terminal ops
are detected at the stdlib function level — each terminal function checks
`(instance? DTPipeline x)` and calls `realize-pipeline` if true. This keeps the
evaluator ignorant of `DTPipeline`.

**The evaluator does not need to classify steps as "terminal" at parse time.** The
dispatch happens at the Clojure function level when the terminal function runs.

---

## 3. Step Descriptors

### 3.1 Fields per step

Each `PipelineStep` carries:

| Field | Type | Source | Example |
|---|---|---|---|
| `step-fn` | `fn` | `eval-pipe-atom-with-fn-call` | compiled filter lambda |
| `ast-node` | vector (Instaparse AST) | raw `PipeAtom` node from parser | `[:PipeAtom [:FnCall ...]]` |
| `label` | String | extracted from `ast-node` | `"filter _.active"` |
| `loc` | map `{:line N :col N}` | Instaparse metadata on `ast-node` | `{:line 3 :col 4}` |
| `index` | int | `map-indexed` counter | `0`, `1`, `2` |

### 3.2 Label extraction

`step-label` walks the AST node and reconstructs a human-readable string.
Implementation strategy — use a simple recursive pretty-printer on the AST:

```clojure
(defn step-label [pipe-atom-node]
  ;; Walk the PipeAtom node, reconstruct source text from identifiers
  ;; For FnCall: "fn-name arg1 arg2 ..."
  ;; For Wildcard expression: "_op val"
  ;; Fallback: "<step N>"
  (try
    (ast->label (descend-to-inner pipe-atom-node))
    (catch Exception _ "<step>")))
```

The label is best-effort. It does not need to be identical to source text — it is for
display in `explain` and IDE overlays. A simple approach: concatenate identifier
strings found in the AST node with spaces, truncated to 60 characters.

### 3.3 Source location extraction

Instaparse attaches `:instaparse.gll/start-index` and `:instaparse.gll/end-index`
metadata to each node. Convert these to line/column using the source string:

```clojure
(defn step-loc [pipe-atom-node source-text]
  (let [start (-> pipe-atom-node meta :instaparse.gll/start-index)]
    (when (and start source-text)
      (let [before (subs source-text 0 start)
            line   (inc (count (filter #(= \newline %) before)))
            col    (- start (or (clojure.string/last-index-of before "\n") -1) 1)]
        {:line line :col col}))))
```

If metadata is absent (e.g., the node was constructed programmatically), `step-loc`
returns `nil`. The `PipelineStep` `loc` field accepts `nil`.

**Note**: `*source*` is a dynamic var bound in `evaluate` (lines 1556, 1594). Pass it
via dynamic binding or as a parameter to `build-pipeline` to enable `step-loc`.

---

## 4. Sample Caching

### 4.1 Cache structure

The `sample-cache` atom holds a vector of `(count steps + 1)` entries:

```
index 0     = source sample (data before any step)
index k+1   = sample after step k (0-based)
```

Each entry is `nil` (not yet cached) or a `PersistentVector` of up to `SAMPLE_SIZE`
rows.

### 4.2 When to compute samples

Samples are computed during `realize-pipeline` — when a terminal operation triggers
execution. The walk through steps is the natural place to capture samples:

```clojure
(defn realize-pipeline [pipeline]
  (let [{:keys [source steps sample-cache]} pipeline
        sample-size (config/get :SAMPLE_SIZE)]
    ;; Cache source sample (index 0)
    (swap! sample-cache assoc 0
           (vec (take sample-size
                      (if (sequential? source) source [source]))))
    ;; Walk steps, cache each boundary
    (loop [data source
           idx  0]
      (if (>= idx (count steps))
        data
        (let [step-fn (:step-fn (nth steps idx))
              result  (step-fn data)
              sample  (vec (take sample-size
                                 (if (sequential? result) result [result])))]
          (swap! sample-cache assoc (inc idx) sample)
          (recur result (inc idx)))))))
```

`dtw/inspect` can trigger partial execution up to a specific step index if the
pipeline is not yet realized:

```clojure
(defn inspect-step [pipeline step-idx sample-size]
  (let [cached (get @(:sample-cache pipeline) (inc step-idx))]
    (if (some? cached)
      (vec (take sample-size cached))
      ;; Not cached: run the pipeline to populate cache, then return
      (do (realize-pipeline pipeline)
          (vec (take sample-size
                     (get @(:sample-cache pipeline) (inc step-idx))))))))
```

### 4.3 Cache invalidation

Cache invalidation happens at the DataTwist language level, not at the record level.
When a user writes:

```
plan is data |> filter _.x
plan |> force!
plan is data |> filter _.y   // second assignment
```

The second `is` binding creates a NEW `DTPipeline` record with a fresh empty
`sample-cache` atom. The old record (with its populated cache) is no longer reachable
from `plan`. GC handles it. No explicit invalidation is needed.

**There is no mechanism to mutate an existing `DTPipeline` record** (fields are
immutable except for the atom contents). Re-binding the name in `env` simply
replaces the record reference with a new one. The BDD scenario ("Reassigning a
pipeline name invalidates the old pipeline cache") is satisfied by this semantics.

### 4.4 Sample size

Default: `SAMPLE_SIZE = 100`. Sourced from `datatwist.config/get :SAMPLE_SIZE`.
`dtw/inspect` accepts an explicit `sample-size` argument that overrides the default
for that call only — it does NOT affect what is stored in the cache (the cache always
stores up to `SAMPLE_SIZE` rows).

---

## 5. Integration Points

### 5.1 Terminal stdlib functions

All must be updated to dispatch on `DTPipeline`:

| Function | Current implementation | Change needed |
|---|---|---|
| `force!` | `(fn [data] (if (vector? data) data (vec data)))` | Add: `(if (instance? DTPipeline data) (vec (realize-pipeline data)) ...)` |
| `count` | Clojure's `count` (works on vectors, lazy seqs) | Replace with `dt-count`: dispatches DTPipeline |
| `first` | Clojure's `first` | Replace with `dt-first` |
| `reduce` | `dt-reduce` | Add DTPipeline arm |
| `collect` | Not yet in stdlib (from design doc it equals `force!`) | Same as `force!` |
| `sort-by` | Eagerly materializes with `vec` | Needs DTPipeline arm |
| `group-by` | Eager | Needs DTPipeline arm |

**Implementation pattern** (uniform across all):

```clojure
(defn dt-count [x]
  (cond
    (instance? datatwist.pipeline.DTPipeline x) (count (realize-pipeline x))
    :else (count x)))
```

Register in `default-env` in `stdlib.clj`:
```clojure
"count" dt-count
"first" dt-first
;; etc.
```

**`force!`** is special: the design says it is a passthrough (returns the data it
materializes). So:

```clojure
(defn dt-force! [x]
  (if (instance? datatwist.pipeline.DTPipeline x)
    (vec (pipeline/realize-pipeline x))
    (if (vector? x) x (vec x))))
```

### 5.2 `=` comparison

Clojure's `=` on a `defrecord` uses the generated `equiv` method, which compares
field by field. This would compare `DTPipeline` records structurally (source, steps,
cache atoms — not the data values).

To make `plan = [...]` work as the BDD expects (auto-materialize and compare by
value), override `equiv` in the record:

```clojure
;; After defrecord DTPipeline, extend Clojure equality
(defmethod clojure.core/= [DTPipeline java.lang.Object]
  [this other]
  (= (realize-pipeline this) other))
```

This is done via `extend-protocol clojure.lang.IPersistentCollection` or by
implementing `equiv` in the record definition.

**Simpler alternative**: override `Object.equals` in the `defrecord` body:
```clojure
Object
(equals [this other]
  (.equals (vec (realize-pipeline this)) other))
```

This makes `(= pipeline [1 2 3])` work correctly. The BDD scenario
"Equality comparison auto-materializes a lazy sequence" (line 192 in the feature
file) tests this for plain lazy seqs, which already works via Clojure's native `=`.
For DTPipeline the same scenario applies.

### 5.3 `str` conversion

The stdlib `str` function (line 499 of `stdlib.clj`) currently handles lazy seqs:
```clojure
"str" (fn [v] (if (and (sequential? v) (not (vector? v)))
                (str (vec v))
                (str v)))
```

Add a DTPipeline arm:
```clojure
"str" (fn [v]
        (cond
          (instance? datatwist.pipeline.DTPipeline v)
          (str (vec (pipeline/realize-pipeline v)))
          (and (sequential? v) (not (vector? v)))
          (str (vec v))
          :else (str v)))
```

### 5.4 `dtw/inspect`

`dtw/inspect plan step-idx sample-size` is a stdlib function:

```clojure
"dtw/inspect" (fn [pipeline step-idx sample-size]
                (if (instance? datatwist.pipeline.DTPipeline pipeline)
                  (pipeline/inspect-step pipeline step-idx sample-size)
                  (throw (ex-info "dtw/inspect requires a DTPipeline value"
                                  {:dt/error true :code "DT-R003"
                                   :category "RUNTIME ERROR"
                                   :hint "Use dtw/inspect on a pipeline bound with is, e.g. plan is data |> filter f"}))))
```

### 5.5 `tap!` interaction

`tap!` is already a stdlib function (lines 571-595 of `stdlib.clj`). When `tap!`
appears inside a pipeline, the DTPipeline's `realize-pipeline` will call each step's
`step-fn` in order. The `tap!` step function will print its sample and return the
data unchanged — exactly as designed. No changes needed to `tap!` for DTPipeline
integration.

### 5.6 `explain`

`dt-explain` in `stdlib.clj` (lines 386-398) currently returns a static string for
lazy seqs. Update to handle `DTPipeline`:

```clojure
(defn- dt-explain [data]
  (if (instance? datatwist.pipeline.DTPipeline data)
    (let [{:keys [steps]} data]
      (str "Pipeline: source"
           (apply str (map #(str " |> " (:label %)) steps))))
    (cond
      (vector? data) (str "Materialized collection of " (count data) " items")
      (sequential? data) "Lazy sequence (not a DTPipeline)"
      :else (str "Value: " (dt-type-of data)))))
```

---

## 6. Risk Assessment

### 6.1 Tests that may break

**High confidence safe** (value-equality comparisons tolerate lazy seqs and DTPipeline
equally once materialized):
- `pipeline_test.clj` — all assertions use `(= [...] result)` or `(eval-dt "...")`.
  Clojure's `=` forces lazy seqs. If DTPipeline overrides `equals` to force itself,
  these remain correct.

**Potentially broken** (type assertions):
- `test/datatwist/data_structures_test.clj` line 659: `(is (vector? result))` — if
  `result` comes from a pipeline, it will now be a DTPipeline. Fix: wrap with
  `force!` or change assertion to check value rather than type.
- `test/datatwist/interop_test.clj` line 164: `(is (= clojure.lang.PersistentVector ...))` —
  needs review; may be testing interop output (likely fine).
- `test/datatwist/lazy_eval_test.clj` lines 40-42: already tests `(not (instance?
  clojure.lang.PersistentVector result))` — will remain true (DTPipeline is not a
  vector). The `(= [20 40 60] (vec result))` call will work if DTPipeline implements
  `Seqable`.

**Stub tests that will become real** (the 9 DTPipeline scenarios):
- Lines 329-353 of `lazy_eval_test.clj` are `(testing "stub -- not yet implemented")`.
  After implementation, these stubs must be replaced with real assertions.

### 6.2 Backward compatibility strategy

1. **`(= expected pipeline-result)` continues to work** because DTPipeline overrides
   `equals` to materialize. Existing tests that compare pipeline results with vectors
   do not need to change.
2. **`(vec result)` still works** because DTPipeline implements `Seqable`, so `(seq
   pipeline)` works, and `(vec (seq pipeline))` produces the materialized vector.
3. **`(sequential? pipeline)` must return true** so existing code paths that branch on
   `sequential?` treat DTPipeline as a sequence-like thing. Implement
   `clojure.lang.Sequential` marker interface on the record.
4. **`(count pipeline)`** — if `count` in stdlib is replaced by `dt-count`, this
   works. If any code calls Clojure's native `count` directly on a DTPipeline, it
   will fail unless DTPipeline implements `clojure.lang.Counted`. Consider implementing
   `Counted` with forced realization.

**Tests in features 1-7 that use pipeline output**: All pass through `(= expected
result)`. Safe because DTPipeline auto-materializes for `=`.

### 6.3 Potential runtime issues

**Head retention**: `realize-pipeline` runs `(loop [data source ...])`. If `source` is
a lazy seq backed by a file, the head is retained in the `source` field of the record
until the record is GC'd. This is inherent and documented in `lazy-eval-design.md`
section 7.2. No mitigation needed for v1.

**Double evaluation**: If `force!` is called twice on the same `DTPipeline`, the
`realized?` atom is `true` after the first call. Update `realize-pipeline` to return
cached final result if already realized:

```clojure
(defn realize-pipeline [pipeline]
  (if @(:realized? pipeline)
    ;; Already realized: return cached final result
    ;; (the last step's sample is an approximation; for full data we need to re-run)
    ;; DECISION: cache the final result vector in an additional atom field,
    ;; or simply re-run (lazy seqs are cached by Clojure anyway for in-memory sources)
    (run-steps pipeline)
    (do (reset! (:realized? pipeline) true)
        (run-steps pipeline))))
```

**Circular dependency**: `evaluator.clj` will require `pipeline.clj`, and
`pipeline.clj` needs `eval-pipe-atom-with-fn-call` from `evaluator.clj`. Resolve
by passing the compiler function as an argument (as described in section 2.2).

---

## 7. Implementation Order

The steps below are ordered from least to most risky. Each step is independently
verifiable.

### Step 1 — Create `src/datatwist/pipeline.clj` (complexity: low)

- Define `PipelineStep` record.
- Define `DTPipeline` record with `source`, `steps`, `env`, `sample-cache`,
  `realized?`, `meta-info`.
- Implement `realize-pipeline` (the sequential walk that calls each step-fn and
  populates the cache).
- Implement `inspect-step` (check cache, trigger realize if empty, return sample).
- Implement `step-label` (AST-to-string best-effort pretty printer).
- Implement `step-loc` (Instaparse metadata extraction).
- No evaluator or stdlib changes yet.
- **Verify**: Unit-test `pipeline.clj` functions directly in a REPL without involving
  the evaluator.

### Step 2 — Wire `build-pipeline` into `evaluator.clj` (complexity: medium)

- Replace the `:Pipeline` case body in `eval-node` (lines 638-643) to call
  `pipeline/build-pipeline` instead of `eval-pipeline`.
- Pass `eval-pipe-atom-with-fn-call` as a parameter.
- Keep `eval-pipeline` as a private helper for any internal use (or remove it).
- **Verify**: Run existing tests. Pipelines now return DTPipeline. Equality tests
  will fail until step 3. Specifically run:
  ```bash
  clj -M -e "(require 'clojure.test 'datatwist.pipeline-test) (clojure.test/run-tests 'datatwist.pipeline-test)"
  ```
  Expected: failures on assertions that compare pipeline results directly (because
  DTPipeline does not yet implement `equals`).

### Step 3 — Implement protocol extensions on DTPipeline (complexity: medium)

- Implement `Object.equals` / `equiv` to force and compare realized values.
- Implement `Object.toString` to force and return string.
- Implement `clojure.lang.Seqable` so `seq` forces and returns a seq.
- Implement `clojure.lang.Sequential` marker interface.
- Implement `clojure.lang.Counted` so native `count` works (forces realization).
- **Verify**: Re-run `pipeline_test.clj`. All existing assertions must pass. The
  DTPipeline now behaves like a seq-like value for comparison purposes.

### Step 4 — Update stdlib terminal functions (complexity: low)

- Replace `"count"`, `"first"`, `"force!"` in `default-env` with DTPipeline-aware
  versions (`dt-count`, `dt-first`, `dt-force!`).
- Add `"collect"` (alias for `dt-force!` returning vector).
- Update `dt-reduce` to dispatch on DTPipeline.
- Update `dt-sort-by`, `dt-group-by` to dispatch on DTPipeline.
- Update `"str"` to dispatch on DTPipeline.
- Update `dt-explain` to show pipeline steps.
- **Verify**: Run all test suites.
  ```bash
  make test
  ```

### Step 5 — Add `dtw/inspect` to stdlib (complexity: low)

- Add `"dtw/inspect"` to `default-env` pointing to `pipeline/inspect-step`.
- **Verify**: Run `lazy_eval_test.clj` (the stub tests for `dtw-inspect-*`).
  These stubs should now be replaceable with real assertions.

### Step 6 — Replace stub tests with real assertions (complexity: medium)

Update the 9 stub `deftest`s in `test/datatwist/lazy_eval_test.clj` to real
assertions. Each test corresponds 1:1 to a BDD scenario:

| Test name (line) | BDD scenario | Assertion |
|---|---|---|
| `pipeline-is-a-first-class-dtpipeline-value...` (329) | line 495 | `(instance? DTPipeline result)` |
| `dtpipeline-retains-metadata-about-each-step` (333) | line 503 | step count, step labels |
| `dtpipeline-caches-a-sample...` (337) | line 515 | cache not nil after force! |
| `reassigning-a-pipeline-name-invalidates...` (341) | line 527 | new pipeline has nil cache |
| `dtw-inspect-returns-sample-data...` (347) | line 543 | sample is vector of rows |
| `dtw-inspect-on-an-un-executed-pipeline...` (351) | line 556 | triggers execution, returns sample |

Also update:
- `a-pipeline-without-a-terminal-operation-is-lazy-and-does-not-execute` (line 20):
  assert `(instance? DTPipeline result)`.
- `chaining-multiple-lazy-operations-builds-a-deeper-plan` (line 25):
  assert step count is 3.
- `binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation` (line 29):
  assert both step1 and step2 are DTPipeline and their `realized?` atoms are false.

- **Verify**: All 9 (plus 3) previously-stub tests now pass.

### Step 7 — Fix any type assertions in features 1-7 tests (complexity: low)

- Audit `data_structures_test.clj`, `interop_test.clj` for `(vector? result)` or
  `instance?` checks on pipeline results.
- Wrap the pipeline evaluation with `force!` in those test expressions, or change
  assertions to use value equality.
- **Verify**: `make test` — 0 failures.

---

## File Inventory

| File | Action | Purpose |
|---|---|---|
| `src/datatwist/pipeline.clj` | CREATE | DTPipeline + PipelineStep records, realize-pipeline, inspect-step, step-label, step-loc |
| `src/datatwist/evaluator.clj` | MODIFY | `:Pipeline` case calls `build-pipeline`; add `pipeline.clj` require |
| `src/datatwist/stdlib.clj` | MODIFY | DTPipeline-aware `dt-count`, `dt-first`, `dt-force!`, `dt-reduce`, `str`, `dt-explain`; add `dtw/inspect` |
| `test/datatwist/lazy_eval_test.clj` | MODIFY | Replace 9+ stub tests with real assertions |
| `test/datatwist/data_structures_test.clj` | MODIFY (minor) | Fix any `vector?` type assertions on pipeline results |

Grammar (`resources/datatwist.grammar`) and parser (`src/datatwist/parser.clj`) are
**unchanged**. The `Pipeline` grammar rule already produces the correct AST shape
(`PipeAtom (_ <'|>'> _ PipeAtom)+`). The change is entirely in the evaluator's
response to that AST.

---

## Complexity Estimate

| Step | Estimated lines of new code | Estimated effort |
|---|---|---|
| 1. `pipeline.clj` creation | ~150 lines | 4-6 hours |
| 2. Evaluator wiring | ~10 lines changed | 1 hour |
| 3. Protocol extensions | ~40 lines | 2-3 hours |
| 4. Stdlib terminal functions | ~50 lines changed | 2-3 hours |
| 5. `dtw/inspect` in stdlib | ~10 lines | 30 min |
| 6. Replace stub tests | ~80 lines | 2-3 hours |
| 7. Fix type assertions | ~10 lines | 30 min |
| **Total** | **~350 lines net** | **~12-17 hours** |
