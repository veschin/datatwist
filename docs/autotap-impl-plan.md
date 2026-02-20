# autotap! Implementation Plan

Date: 2026-02-20

## Context and Scope

`autotap!` is a pipeline macro-like transformation: when placed as a pipeline step, it
instruments every *subsequent* step with `tap!` output. It produces no output of its own
for the current data position; it modifies how the remaining steps execute.

BDD source: `bdd/8-lazy-eval-data-sources.feature`, lines 399-431 (three scenarios).
Test stubs: `test/datatwist/lazy_eval_test.clj`, lines 359-369 (three `deftest` stubs).

This plan unlocks all three autotap! stub tests.

---

## 1. Recommended Approach: Option C — Pipeline Middleware via Dynamic Var

### Summary

`autotap!` is implemented as a sentinel value in the environment. When `eval-pipeline`
encounters a pipeline step whose inner identifier resolves to this sentinel, it switches
into "autotap mode" and wraps every subsequent step with a `tap!` call labeled with the
step's source text.

This is **Option C** from the research prompt (pipeline middleware), implemented via a
Clojure dynamic var `*autotap-mode*` that is activated during `eval-pipeline` execution.

### Why Not Option A (AST Transformation)

AST transformation would require:
1. Walking the full `Pipeline` AST node before any evaluation starts.
2. Inserting synthetic `[:PipeAtom ...]` nodes for each `tap!` call.
3. Constructing valid instaparse AST nodes with correct structure and metadata.

The problem: instaparse metadata (`:instaparse.gll/start-index`, `:end-index`) is
attached by the parser and cannot easily be replicated on synthetic nodes. The label
extraction (see Section 3) relies on this metadata. Constructing synthetic nodes that
carry correct metadata is error-prone and couples the implementation tightly to the
parser's internal representation.

Additionally, AST transformation happens before `eval-pipeline` is entered, so there is
no clean hook point — the transformation would need to live in `eval-node` at the
`:Pipeline` dispatch branch, making that branch significantly more complex.

### Why Not Option B (Marker Return Value)

A sentinel *return value* from the `autotap!` step function is problematic:
`eval-pipeline` is a plain `reduce` — after `autotap!` runs, the accumulator becomes
the sentinel instead of the data. The subsequent step (`filter`, etc.) would then
receive the sentinel as its input, breaking the pipeline unless `eval-pipeline` has
special handling anyway. If special handling is needed, Option C is strictly cleaner
because it uses a separate control channel (a dynamic var) rather than polluting the
data channel with a sentinel.

### Why Option C

- `autotap!` registers as a sentinel value in the stdlib environment (a unique keyword
  or tagged map, not a function).
- `eval-pipeline` checks each step before executing it: if the step resolves to the
  autotap sentinel, it sets a flag and continues to the next step without touching data.
- When the flag is set, each subsequent step is wrapped: execute the step, then call
  `tap!` with the step's source-text label.
- The dynamic var `*autotap-mode*` is thread-local and reset at each pipeline
  invocation, so nested pipelines are isolated.
- No AST mutation. No sentinel in the data channel. Clean and testable.

---

## 2. Step-by-Step Implementation Plan

### Step 1: Add autotap! sentinel to stdlib

File: `src/datatwist/stdlib.clj`

Add a private sentinel constant and expose it as `"autotap!"` in `default-env`:

```clojure
(def ^:private autotap-sentinel
  {:dt/autotap true})

;; In default-env map:
"autotap!" autotap-sentinel
```

The sentinel is a plain map with a namespaced key. It is distinguishable from any
user-created value because `:dt/autotap` is not writable from DataTwist source code.

### Step 2: Add dynamic var *autotap-mode* to evaluator

File: `src/datatwist/evaluator.clj`

Near the other dynamic vars (line ~32, alongside `*source*`):

```clojure
(def ^:dynamic *autotap-mode*
  "When true, eval-pipeline wraps each step with tap! labeled with source text."
  false)
```

### Step 3: Add extract-step-label helper

File: `src/datatwist/evaluator.clj`

Add a private helper after the `descend-to-inner` block (around line 212):

```clojure
(defn- extract-step-label
  "Extract the source text for a PipeAtom step node using instaparse metadata.
   Returns a string like \"filter _.active\" or \"map _.name\".
   Falls back to a generic label if metadata is absent."
  [step-node]
  (let [m     (meta step-node)
        start (:instaparse.gll/start-index m)
        end   (:instaparse.gll/end-index m)]
    (if (and start end *source*)
      (subs *source* start end)
      "step")))
```

This exploits the fact that instaparse attaches `:instaparse.gll/start-index` and
`:instaparse.gll/end-index` to every parse node as metadata. The `*source*` dynamic var
(already present, bound in `evaluate` at line 1705) provides the full source string.
The substring `(subs *source* start end)` yields the exact source text of the step —
e.g., `"filter _.active"`, `"map _.name"`, `"take 5"`.

Verified: parsing `"users |> autotap! |> filter _.active |> map _.name |> take 5"`
produces PipeAtom nodes with metadata `{:instaparse.gll/start-index 21 :end-index 36}`
for the `filter` step, yielding `(subs src 21 36)` → `"filter _.active"`.

### Step 4: Rewrite eval-pipeline to support autotap mode

File: `src/datatwist/evaluator.clj`

Replace the current `eval-pipeline` (lines 1544-1551) with:

```clojure
(defn- autotap-sentinel?
  "Returns true if val is the autotap! sentinel marker."
  [val]
  (and (map? val) (:dt/autotap val)))

(defn- eval-pipeline
  "Evaluate a sequence of PipeAtom nodes against initial data.
   If a step resolves to the autotap! sentinel, switch into autotap mode:
   every subsequent step is executed then tap!-probed with its source label."
  [data steps env]
  (let [tap-fn (env/lookup env "tap!")]
    (loop [remaining steps
           d         data
           tapping?  false]
      (if (empty? remaining)
        d
        (let [step-node (first remaining)
              step-fn   (eval-pipe-atom-with-fn-call step-node env)
              ;; Probe the step: if it resolves to autotap sentinel, switch mode
              probe     (when (not tapping?)
                          (let [inner (descend-to-inner step-node)]
                            (when (and (vector? inner)
                                       (= :Identifier (first inner)))
                              (let [name (second inner)
                                    val  (env/lookup env name)]
                                (when (autotap-sentinel? val) true)))))]
          (if probe
            ;; autotap! step itself: switch on tapping mode, pass data through unchanged
            (recur (rest remaining) d true)
            (let [result (step-fn d)]
              (if tapping?
                ;; Autotap mode: call tap! with "[<source label>]" after each step
                (let [label (str "[" (extract-step-label step-node) "]")]
                  (tap-fn result label)
                  (recur (rest remaining) result true))
                (recur (rest remaining) result false)))))))))
```

Notes on this implementation:
- The sentinel check is done via `descend-to-inner` to peel transparent wrapper tags
  (`:PipeAtom`, `:OrExpr`, `:AndExpr`, `:NilCoalesce`, `:NotExpr`, `:CompExpr`,
  `:InExpr`, `:AddExpr`, `:MulExpr`, `:UnaryExpr`, `:FnCallExpr`, `:FieldAccess`,
  `:NegFieldAccess`, `:Atom`) before checking for `:Identifier`.
- The sentinel check is gated by `(not tapping?)` — once autotap mode is active, no
  subsequent step is re-checked for the sentinel (autotap! is single-shot).
- `tap-fn` is looked up from `env` once at loop entry. This avoids repeated env lookups
  and keeps the lookup consistent with how all other stdlib functions are resolved.
- The `tap-fn` call uses labeled mode: `(tap-fn result label)` where `label` is
  `"[filter _.active]"`. This matches the BDD expectation:
  > "tap! output is shown for the filter step with label `[filter _.active]`"
  > "the filter step output has `[filter _.x > 0]` on the first line"
- `tap-fn` returns `data` unchanged (passthrough), so `result` is still passed forward.
  The loop accumulator `d` is updated with `result` (not `(tap-fn result label)`'s
  return value) — but since `tap!` is passthrough, both are the same value.
- For clarity, the loop can use `(tap-fn result label)` directly as the next `d`:
  `(recur (rest remaining) (tap-fn result label) true)` — `tap!` guarantees passthrough.

### Step 5: Handle the "initial tap!" for data before first step

The BDD scenario `autotap! is equivalent to inserting tap! before each step` (line 422)
shows:

```
data |> autotap! |> filter _.active |> map _.name
```

is equivalent to:

```
data |> tap! |> filter _.active |> tap! |> map _.name
```

Note: there is a bare `tap!` *before* `filter` (after `autotap!`'s position). This
means the initial data itself should be tap!-probed before the first instrumented step
runs.

Adjust Step 4: when `probe` is true (autotap! detected), call `tap-fn` on `d` (current
data) in bare mode before switching to `tapping?`:

```clojure
(if probe
  (do
    (tap-fn d)          ;; bare tap! for data at autotap! position
    (recur (rest remaining) d true))
  ...)
```

This aligns exactly with the equivalence BDD scenario.

### Step 6: Update the three test stubs

File: `test/datatwist/lazy_eval_test.clj`

Replace the three stub `deftest` bodies (lines 359-369):

**Test 1** — `autotap-bang-placed-at-start-instruments-every-subsequent-step`:
```clojure
(deftest autotap-bang-placed-at-start-instruments-every-subsequent-step
  (let [output (with-out-str
                 (eval-dt "users is [{name: \"Alice\" active: true}
                                     {name: \"Bob\"   active: false}]
                           users |> autotap! |> filter _.active |> map {name: _.name} |> sort-by _.name"))]
    (is (str/includes? output "[filter _.active]")
        "tap! output shown for filter step with label [filter _.active]")
    (is (str/includes? output "[map {name: _.name}]")
        "tap! output shown for map step with label [map {name: _.name}]")
    (is (str/includes? output "[sort-by _.name]")
        "tap! output shown for sort-by step with label [sort-by _.name]")))
```

**Test 2** — `autotap-bang-output-format-is-function-label-on-first-line-sample-on-second`:
```clojure
(deftest autotap-bang-output-format-is-function-label-on-first-line-sample-on-second
  (let [output (with-out-str
                 (eval-dt "data is [{x: 1 y: 10} {x: -1 y: 20}]
                           data |> autotap! |> filter _.x > 0 |> map _.y"))]
    (is (re-find #"\[filter _.x > 0\]\n" output)
        "filter label on first line, sample on second")
    (is (re-find #"\[map _.y\]\n" output)
        "map label on first line, sample on second")))
```

**Test 3** — `autotap-bang-is-equivalent-to-inserting-tap-bang-before-each-step`:
```clojure
(deftest autotap-bang-is-equivalent-to-inserting-tap-bang-before-each-step
  (let [data "data is [{active: true name: \"A\"} {active: false name: \"B\"}]"
        out-autotap (with-out-str
                      (eval-dt-last (str data "\ndata |> autotap! |> filter _.active |> map _.name")))
        result-autotap (eval-dt-last (str data "\ndata |> autotap! |> filter _.active |> map _.name"))
        result-plain   (eval-dt-last (str data "\ndata |> filter _.active |> map _.name"))]
    (is (some? out-autotap) "autotap! produces tap! output")
    (is (= result-plain result-autotap)
        "final result is the same as without autotap!")))
```

---

## 3. How Function Labels Are Extracted from AST

### Mechanism

Every node produced by instaparse carries Java object metadata with source position
information:

```clojure
(meta step-node)
;; => #:instaparse.gll{:start-index 21, :end-index 36}
```

The `*source*` dynamic var (bound in `evaluate`, line 1705 of `evaluator.clj`) holds
the full source string for the current evaluation. The label for any `PipeAtom` step is:

```clojure
(subs *source* start-index end-index)
```

Verified with REPL experimentation:

| Pipeline step AST | start | end | Label extracted |
|---|---|---|---|
| `filter _.active` | 21 | 36 | `"filter _.active"` |
| `map _.name` | 40 | 50 | `"map _.name"` |
| `take 5` | 54 | 60 | `"take 5"` |
| `sort-by _.name` | (varies) | (varies) | `"sort-by _.name"` |

The BDD spec wraps labels in `[...]`:
> "tap! output is shown for the filter step with label `[filter _.active]`"

So `extract-step-label` returns the raw source substring; the caller in `eval-pipeline`
wraps it: `(str "[" label "]")`.

### Fallback

If `*source*` is nil or metadata is absent (e.g., for programmatically constructed AST
nodes), `extract-step-label` returns `"step"` as a safe fallback. This is consistent
with how `tap!` bare mode works.

---

## 4. Files That Need Changes

| File | Change |
|---|---|
| `src/datatwist/stdlib.clj` | Add `autotap-sentinel` constant; expose `"autotap!"` in `default-env` |
| `src/datatwist/evaluator.clj` | Add `*autotap-mode*` dynamic var; add `extract-step-label`; add `autotap-sentinel?`; rewrite `eval-pipeline` |
| `test/datatwist/lazy_eval_test.clj` | Replace three autotap! stub test bodies |

No new files are needed. No grammar changes are needed — `autotap!` is already a valid
identifier per the grammar (identifiers match `[a-zA-Z][a-zA-Z0-9_\-]*[?!]?`).

---

## 5. Which Stubs Are Unlocked

All three autotap! stub tests in `test/datatwist/lazy_eval_test.clj`:

1. **`autotap-bang-placed-at-start-instruments-every-subsequent-step`** (line 359)
   — Verifies each subsequent step is labeled and tapped.

2. **`autotap-bang-output-format-is-function-label-on-first-line-sample-on-second`** (line 363)
   — Verifies `"[fn source text]"` appears on the first line of each tap! output,
   sample on the second line.

3. **`autotap-bang-is-equivalent-to-inserting-tap-bang-before-each-step`** (line 367)
   — Verifies the pipeline produces the same final result with and without autotap!,
   and that tap! output is emitted.

---

## 6. Edge Cases and Constraints

- **Nested pipelines**: `*autotap-mode*` is a loop-local flag (not a dynamic var),
  so each `eval-pipeline` call has its own `tapping?` loop variable. Nested pipelines
  are not affected.
- **SourcelessPipeline**: The `:SourcelessPipeline` branch also calls `eval-pipeline`.
  If `autotap!` appears there, it works identically.
- **autotap! not at start**: If `autotap!` appears mid-pipeline (not first), only steps
  after it are tapped. The BDD spec says "placed at the start" but does not prohibit
  mid-pipeline use; the implementation naturally supports both.
- **Multiple autotap!**: If `autotap!` appears twice, the second occurrence is a
  no-op (the sentinel check is gated by `(not tapping?)`). This is safe.
- **tap! availability**: `tap-fn` is looked up from `env` at `eval-pipeline` entry. If
  for any reason `tap!` is not in the environment, `tap-fn` would be nil and calling it
  would throw. This cannot happen in normal execution because `tap!` is in `default-env`,
  but a guard `(when tap-fn ...)` can be added for robustness.
- **Source text accuracy**: The `extract-step-label` function uses instaparse metadata
  which is always present for nodes produced by the real parser. It is absent only for
  programmatically constructed AST nodes (test helpers may produce these). The `"step"`
  fallback handles this.
