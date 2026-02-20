# Config System Implementation Plan

**Feature:** System constants and configuration
**BDD source:** `bdd/8-lazy-eval-data-sources.feature`, lines 643–710
**Test stubs:** `test/datatwist/lazy_eval_test.clj`, lines 518–550
**Status:** Grammar-ready (no grammar changes needed). Evaluator and stdlib changes required.

---

## 1. Grammar Analysis

### Current parsing behavior (verified by REPL)

The grammar already supports all required syntax **without any changes**. Parse results:

```
dtw.SAMPLE_SIZE
  → [:FieldAccess [:Atom [:Identifier "dtw"]] [:FieldName "SAMPLE_SIZE"]]

SAMPLE_SIZE
  → [:FieldAccess [:Atom [:Identifier "SAMPLE_SIZE"]]]

set! dtw.SAMPLE_SIZE 200
  → [:FnCall
       [:CallTarget [:Identifier "set!"]]
       [:CallArg ... [:FieldAccess [:Atom [:Identifier "dtw"]] [:FieldName "SAMPLE_SIZE"]]]
       [:CallArg ... [:Integer "200"]]]
```

Key observations:

- `dtw.SAMPLE_SIZE` parses as a `FieldAccess` node: base `Identifier "dtw"`, then `FieldName "SAMPLE_SIZE"`. It goes through the **existing** `FieldAccess` evaluator path — no new AST node type.
- `SAMPLE_SIZE` (bare) parses as a single `FieldAccess` wrapping a plain `Identifier`. The evaluator reduces to a simple `env` lookup.
- `set!` parses as a regular `FnCall` with identifier `"set!"`. This is not a keyword or special form — it is a first-class function registered in the default environment.
- `dtw.UNKNOWN_KEY` would also parse as `FieldAccess` with base `"dtw"` and field `"UNKNOWN_KEY"`.

**Grammar changes required: none.**

### How `set! dtw.SAMPLE_SIZE 200` evaluates today (without config)

1. Evaluator dispatches to `FnCall`.
2. `CallTarget` resolves `"set!"` — currently `nil` (not in `default-env`), so `apply-fn` throws `DT-R003`.
3. First `CallArg` evaluates `FieldAccess ["dtw", "SAMPLE_SIZE"]`: looks up `"dtw"` in env → `nil` → returns `nil`.
4. Second `CallArg` evaluates `Integer "200"` → `200`.

So `set!` must be registered as a real function that receives two arguments: a **value** (the resolved field access) and a new value. However, this is a write operation on a config key — `set!` cannot receive the *current value* of `dtw.SAMPLE_SIZE`; it needs the **key name** as a string.

**Resolution:** `set!` is a function that receives the key name as a string (not the resolved value). The `FieldAccess` node for `dtw.SAMPLE_SIZE` must **not** be evaluated before passing to `set!`. This requires `set!` to be implemented as a **special form** in the evaluator — similar to how `is` (Binding) works — so it can receive the unevaluated AST of its first argument and extract the field name string from it.

Alternatively (simpler): evaluate `dtw.SAMPLE_SIZE` as a string key `"SAMPLE_SIZE"` when the base is the special `dtw` object, and let `set!` be a normal function `(fn [key new-val] ...)`. This requires `dtw` to be a sentinel object in the environment whose field-access returns the **key name as a string** rather than the value.

See Section 3 for the chosen approach.

---

## 2. Config Storage — `src/datatwist/config.clj`

New file. Single atom, thread-safe via `swap!` / `deref`.

```clojure
(ns datatwist.config)

(def ^:private defaults
  {:SAMPLE_SIZE          100
   :DESCRIBE_SAMPLE_SIZE 1000
   :PRINT_WIDTH          120
   :MAX_COLLECT_ROWS     nil})

(def ^:private state (atom defaults))

(defn get-config
  "Return the current value of config key k (a keyword like :SAMPLE_SIZE)."
  [k]
  (get @state k))

(defn set-config!
  "Set config key k to value v. Throws with hint if k is not a valid key."
  [k v]
  (if (contains? defaults k)
    (swap! state assoc k v)
    (throw (ex-info (str "Unknown config key: " (name k))
                    {:dt/error  true
                     :code      "DT-R030"
                     :category  "CONFIG ERROR"
                     :message   (str "Unknown system constant: " (name k))
                     :hint      (str "Valid constants: "
                                     (clojure.string/join ", " (map name (keys defaults))))}))))

(defn reset-config!
  "Reset all config values to defaults. Used in tests."
  []
  (reset! state defaults))
```

**Design notes:**

- Keys are keywords internally (`:SAMPLE_SIZE`). DataTwist surface syntax uses the bare name `SAMPLE_SIZE` or the qualified form `dtw.SAMPLE_SIZE`.
- `reset-config!` is needed so test isolation works correctly (tests that mutate config must reset afterward).
- Thread safety: `swap!` is atomic. Concurrent reads of `@state` via `get-config` are safe because Clojure atoms provide consistent reads.
- No environment variable override at this stage (documented in `docs/lazy-eval-design.md` as a future enhancement: `DT_SAMPLE_SIZE=500`). Can be added later in `reset-config!` initialization.

---

## 3. Evaluator Integration

### 3.1 The `dtw` sentinel object

The cleanest approach that requires **no special form** in the evaluator:

Bind `"dtw"` in `default-env` to a special map-like object — a Clojure map whose values are the **string key names**, not the config values themselves. Field access on this object returns the key name as a string.

```clojure
;; In default-env
"dtw" {"SAMPLE_SIZE"          "SAMPLE_SIZE"
       "DESCRIBE_SAMPLE_SIZE" "DESCRIBE_SAMPLE_SIZE"
       "PRINT_WIDTH"          "PRINT_WIDTH"
       "MAX_COLLECT_ROWS"     "MAX_COLLECT_ROWS"}
```

When `dtw.SAMPLE_SIZE` is evaluated:
1. `FieldAccess` evaluates base `Identifier "dtw"` → the map above.
2. `get map (keyword "SAMPLE_SIZE")` — but the map uses string keys, not keyword keys.

**Problem:** the existing `FieldAccess` evaluator calls `(get val (keyword fname))` which would produce `nil` for a string-keyed map.

**Better approach:** Make `"dtw"` a Clojure record or deftype that implements `clojure.lang.ILookup` so field access returns the string key name. But that is complex.

**Simplest approach that works:** Make `dtw.SAMPLE_SIZE` access return the config value directly by making `"dtw"` a special namespaced dispatch object. Implement a `dtw-namespace` function that, when looked up with a field name, calls `config/get-config`.

Since `FieldAccess` in the evaluator does `(get val (keyword fname))` for maps, and maps return `nil` for unknown keys — we can use `reify`:

```clojure
;; In stdlib.clj or config.clj
(defn make-dtw-ns []
  (reify
    clojure.lang.ILookup
    (valAt [_ k]
      (config/get-config k))
    (valAt [_ k not-found]
      (if (contains? @config/state k) (config/get-config k) not-found))))
```

But `FieldAccess` passes `(keyword fname)` as the key, so `(valAt _ :SAMPLE_SIZE)` → `(config/get-config :SAMPLE_SIZE)` → `100`. This works.

**Chosen approach: `dtw` namespace object via `reify ILookup`.**

The `set!` function then receives the **resolved value** of `dtw.SAMPLE_SIZE` (which is `100`, the current value) as its first argument — but that is wrong. `set!` needs the **key**, not the current value.

**Revised approach for `set!`:** Implement `set!` as a **special form** in the evaluator. When the evaluator sees a `FnCall` whose `CallTarget` resolves to the string `"set!"`, it does not evaluate the first argument normally. Instead, it inspects the AST of the first `CallArg` to extract the key name.

Specifically: the first argument to `set!` must be either:
- A bare `Identifier` (e.g., `SAMPLE_SIZE`) — key is the identifier name.
- A `FieldAccess` with base `Identifier "dtw"` and a single `FieldName` — key is the field name string.

This extraction happens at the AST level before evaluation, making `set!` a proper special form that mutates config by name.

### 3.2 Special form dispatch for `set!`

In `eval-node`, in the `FnCall` branch, add a check before the generic function call path:

```clojure
;; --- FnCall ---
(= :FnCall tag)
(let [call-target (first children)
      call-args   (rest children)]
  ;; Special form: set! dtw.CONSTANT value  OR  set! CONSTANT value
  (if (set!-call? call-target)
    (eval-set! call-target call-args env)
    ;; ... existing generic FnCall logic ...
    ))
```

Helper predicates (pure AST inspection, no evaluation):

```clojure
(defn- set!-call?
  "Returns true if the CallTarget is the identifier 'set!'."
  [call-target-node]
  (and (vector? call-target-node)
       (= :CallTarget (first call-target-node))
       (let [inner (second call-target-node)]
         (and (vector? inner)
              (= :Identifier (first inner))
              (= "set!" (second inner))))))

(defn- extract-config-key
  "Extract the config key string from a CallArg AST node for set!.
   Accepts bare Identifier (SAMPLE_SIZE) or FieldAccess with dtw base (dtw.SAMPLE_SIZE).
   Returns the key string (e.g. 'SAMPLE_SIZE') or throws."
  [call-arg-node]
  ;; CallArg -> NegFieldAccess -> FieldAccess -> ...
  (let [fa (descend-through call-arg-node #{:CallArg :NegFieldAccess})]
    (cond
      ;; dtw.SAMPLE_SIZE: FieldAccess with 2 children
      (and (= :FieldAccess (first fa))
           (= 2 (count (rest fa)))
           (let [base (second fa)]
             (and (= :Atom (first base))
                  (let [id (second base)]
                    (and (= :Identifier (first id))
                         (= "dtw" (second id)))))))
      (second (nth fa 2))   ;; the FieldName string

      ;; Bare SAMPLE_SIZE: FieldAccess with 1 child wrapping Identifier
      (and (= :FieldAccess (first fa))
           (= 1 (count (rest fa)))
           (let [base (second fa)
                 id   (and (= :Atom (first base)) (second base))]
             (and id (= :Identifier (first id)))))
      (second (second (second fa)))  ;; Identifier string

      :else
      (throw (ex-info "set! first argument must be a system constant name (e.g. SAMPLE_SIZE or dtw.SAMPLE_SIZE)"
                      {:dt/error true :code "DT-R030" :category "CONFIG ERROR"})))))

(defn- eval-set!
  "Evaluate a set! special form. Mutates the config atom."
  [_call-target call-args env]
  (when (not= 2 (count call-args))
    (throw (ex-info "set! requires exactly 2 arguments: a constant name and a new value"
                    {:dt/error true :code "DT-R030" :category "CONFIG ERROR"})))
  (let [key-str   (extract-config-key (first call-args))
        new-val   (eval-node (second call-args) env)]
    (config/set-config! (keyword key-str) new-val)
    new-val))
```

### 3.3 Bare `SAMPLE_SIZE` resolution

`SAMPLE_SIZE` (without `dtw.` prefix) parses as a plain `Identifier`. The evaluator handles `Identifier` via `env/lookup`. Since `SAMPLE_SIZE` must be pre-bound in `default-env`, add these bindings:

```clojure
;; In stdlib/default-env
"SAMPLE_SIZE"          (fn [] (config/get-config :SAMPLE_SIZE))   ;; WRONG: would return fn not value
```

**Problem:** `env/lookup` returns the value at bind time, not dynamically. If we bind `"SAMPLE_SIZE"` to `100` at startup, it never updates when `set!` changes the config atom.

**Correct approach:** Do not bind constants in the env. Instead, add a special Identifier resolution hook: when `env/lookup` returns `nil` for an ALL_CAPS identifier, fall back to `config/get-config`:

In the `Identifier` branch of `eval-node`:

```clojure
(= :Identifier tag)
(let [name (first children)
      val  (env/lookup env name)]
  (if (some? val)
    val
    ;; ALL_CAPS fallback: try config lookup
    (if (re-matches #'[A-Z][A-Z0-9_]*' name)
      (let [cfg-val (config/get-config (keyword name))]
        (if (some? cfg-val)
          cfg-val
          (throw (ex-info (str "Undefined identifier: " name) ...))))
      (throw (ex-info (str "Undefined identifier: " name) ...)))))
```

**Note:** `MAX_COLLECT_ROWS` has a default of `nil`, so `(some? cfg-val)` would return `false` for it even when it IS a valid key. Use `contains?` on the config defaults instead:

```clojure
(if (config/valid-key? (keyword name))
  (config/get-config (keyword name))
  (throw ...))
```

Add to `config.clj`:

```clojure
(defn valid-key? [k] (contains? defaults k))
```

### 3.4 `dtw.SAMPLE_SIZE` read path (without set!)

For read access `dtw.SAMPLE_SIZE`:
1. `FieldAccess` evaluates `Identifier "dtw"` → the `dtw` sentinel object.
2. `get sentinel-obj (keyword "SAMPLE_SIZE")` → `(config/get-config :SAMPLE_SIZE)` → `100`.

This works cleanly if `"dtw"` is bound to a `reify ILookup` object as described in 3.1. The existing `FieldAccess` evaluator code at line 570 handles maps, but the `ILookup` path falls into the `:else` branch which calls `(get val (keyword fname))`. Since `clojure.core/get` works on `ILookup` implementors, this is correct.

### 3.5 Error for unknown constant

`(config/set-config! :UNKNOWN_KEY 42)` throws an `ex-info` with code `"DT-R030"`. The evaluator does not need to catch this — it propagates up through the REPL's standard error handler which renders it via `error_renderer.clj`.

Add `"DT-R030"` to `src/datatwist/errors.clj`:

```clojure
"DT-R030" {:category "CONFIG ERROR"
            :description "Unknown system constant."
            :hint "Valid constants: SAMPLE_SIZE, DESCRIBE_SAMPLE_SIZE, PRINT_WIDTH, MAX_COLLECT_ROWS."}
```

---

## 4. stdlib Integration

### 4.1 `tap!` — use `config/get-config` dynamically

Currently `tap!` in `stdlib.clj` captures `sample-size` as `100` at definition time (line 571). Replace with a dynamic read:

```clojure
"tap!" (fn
         ([data]
          (let [sample-size (config/get-config :SAMPLE_SIZE)]
            (println "--- tap! ---")
            (if (sequential? data)
              (println (vec (take sample-size data)))
              (println data))
            data))
         ([data label-or-fn]
          (let [sample-size (config/get-config :SAMPLE_SIZE)]
            (if (string? label-or-fn)
              (do
                (println (str "--- " label-or-fn " ---"))
                (if (sequential? data)
                  (println (vec (take sample-size data)))
                  (println data))
                data)
              (let [sample (if (sequential? data) (vec (take sample-size data)) data)]
                (println (label-or-fn sample))
                data)))))
```

### 4.2 `force!` — enforce `MAX_COLLECT_ROWS`

Current `force!` (line 559): `(fn [data] (if (vector? data) data (vec data)))`.

Replace with:

```clojure
"force!" (fn [data]
           (let [limit (config/get-config :MAX_COLLECT_ROWS)
                 result (if (vector? data) data (vec data))]
             (if (and limit (> (count result) limit))
               (do
                 (println (str "WARNING: force! result truncated to " limit
                               " rows (MAX_COLLECT_ROWS). Source had " (count result) " rows."))
                 (vec (take limit result)))
               result)))
```

### 4.3 `describe` / `histogram` — use `DESCRIBE_SAMPLE_SIZE`

The exploration functions (`dt-describe`, `dt-histogram`) currently hardcode or ignore sample sizes. When implemented, they should call `(config/get-config :DESCRIBE_SAMPLE_SIZE)` at invocation time rather than capturing the value at definition time.

### 4.4 `PRINT_WIDTH`

The table-rendering path (inside `tap!`, `describe`, `table`) should read `(config/get-config :PRINT_WIDTH)` when formatting output. This is relevant for the REPL display layer; the exact integration point depends on the table renderer (not yet implemented).

### 4.5 stdlib requires

Add `[datatwist.config :as config]` to the `ns` declaration of `src/datatwist/stdlib.clj`.

---

## 5. File Change Summary

| File | Change type | Description |
|---|---|---|
| `src/datatwist/config.clj` | **New file** | Config atom, `get-config`, `set-config!`, `valid-key?`, `reset-config!` |
| `src/datatwist/errors.clj` | Edit | Add `"DT-R030"` config error code |
| `src/datatwist/stdlib.clj` | Edit | Add `config` require; bind `"dtw"` sentinel; update `tap!`, `force!`; remove hardcoded `sample-size 100` |
| `src/datatwist/evaluator.clj` | Edit | Add `config` require; add `set!-call?`, `extract-config-key`, `eval-set!` helpers; add ALL_CAPS fallback in `Identifier` branch |
| `resources/datatwist.grammar` | **No change** | Grammar already supports all required syntax |
| `test/datatwist/lazy_eval_test.clj` | Edit | Implement the 8 config test stubs (currently `"stub -- not yet implemented"`) |

---

## 6. Implementation Order

Dependencies must be respected: config.clj has no dependencies on other DT files, so it goes first.

### Step 1 — `src/datatwist/config.clj` (no dependencies)

Create the file with:
- `defaults` private map: `{:SAMPLE_SIZE 100 :DESCRIBE_SAMPLE_SIZE 1000 :PRINT_WIDTH 120 :MAX_COLLECT_ROWS nil}`
- `state` atom initialized to `defaults`
- `get-config [k]`
- `set-config! [k v]` — validates key, throws `ex-info` with `"DT-R030"` on unknown key
- `valid-key? [k]`
- `reset-config! []`

Verify: `(require 'datatwist.config) (datatwist.config/get-config :SAMPLE_SIZE)` → `100`.

### Step 2 — `src/datatwist/errors.clj`

Add `"DT-R030"` entry. No functional change, just registry completeness.

### Step 3 — `src/datatwist/stdlib.clj`

3a. Add `[datatwist.config :as config]` to `ns` requires.

3b. Bind `"dtw"` sentinel object in `default-env`:
```clojure
"dtw" (reify clojure.lang.ILookup
        (valAt [_ k] (config/get-config k))
        (valAt [_ k _not-found] (config/get-config k)))
```

3c. Update `tap!` to call `(config/get-config :SAMPLE_SIZE)` at call time (not definition time).

3d. Update `force!` to enforce `MAX_COLLECT_ROWS`.

Verify via REPL:
```
clj -M -e "(require 'datatwist.stdlib) (println ((datatwist.stdlib/default-env) \"dtw\"))"
```

### Step 4 — `src/datatwist/evaluator.clj`

4a. Add `[datatwist.config :as config]` to `ns` requires.

4b. Add private helpers `set!-call?`, `extract-config-key`, `eval-set!` near the top of the file (after forward declarations).

4c. In the `FnCall` branch of `eval-node`, add the `set!` special-form check before the generic path.

4d. In the `Identifier` branch of `eval-node`, add ALL_CAPS fallback after normal `env/lookup`. The current Identifier branch is:
```clojure
(= :Identifier tag)
(let [name (first children)]
  (env/lookup env name))
```

Change to:
```clojure
(= :Identifier tag)
(let [name (first children)
      val  (env/lookup env name)]
  (if (some? val)
    val
    (if (config/valid-key? (keyword name))
      (config/get-config (keyword name))
      (throw (ex-info (str "Undefined identifier: " name)
                      {:dt/error true :code "DT-R001" :category "UNDEFINED IDENTIFIER"
                       :message (str "'" name "' is not defined.")
                       :hint "Check the spelling or define the value with `is`."
                       :source *source*})))))
```

**Note on `nil` in env lookup:** `env/lookup` may return `nil` both for "not found" and for valid bindings whose value is `nil`. Check the current `env/lookup` implementation to see if it distinguishes the two cases (e.g. via a sentinel). If not, the ALL_CAPS fallback may incorrectly trigger when a user has bound `SAMPLE_SIZE` to `nil` in their script. Acceptable tradeoff: document that ALL_CAPS names shadow config when explicitly bound.

Verify targeted test run:
```
clj -M -e "(require 'clojure.test 'datatwist.lazy-eval-test) (clojure.test/run-tests 'datatwist.lazy-eval-test)"
```

### Step 5 — `test/datatwist/lazy_eval_test.clj`

Implement the 8 stub tests. Each test must call `config/reset-config!` in a `finally` block to prevent mutation leaking between tests.

BDD scenario → test mapping:

| BDD scenario | Deftest name | Assertion |
|---|---|---|
| SAMPLE_SIZE constant has default value 100 | `sample-size-constant-has-default-value-100` | `(eval-dt-last "n is SAMPLE_SIZE")` → `100` |
| set! dtw.CONSTANT changes constant and dot-access reads it back | `set-bang-dtw-constant-changes-a-system-constant-and-dot-access-reads-it-back` | `(eval-dt-last "set! dtw.SAMPLE_SIZE 200\nn is dtw.SAMPLE_SIZE")` → `200` |
| SAMPLE_SIZE affects tap! rows | `sample-size-affects-how-many-rows-tap-bang-and-repl-preview-show` | After `set! dtw.SAMPLE_SIZE 50`, `tap!` prints at most 50 rows (test via output capture or config read) |
| DESCRIBE_SAMPLE_SIZE has default value 1000 | `describe-sample-size-has-default-value-1000` | `(eval-dt-last "n is DESCRIBE_SAMPLE_SIZE")` → `1000` |
| PRINT_WIDTH has default value 120 | `print-width-has-default-value-120` | `(eval-dt-last "n is PRINT_WIDTH")` → `120` |
| MAX_COLLECT_ROWS has default value nil | `max-collect-rows-has-default-value-nil-unlimited` | `(eval-dt-last "n is MAX_COLLECT_ROWS")` → `nil` |
| Setting MAX_COLLECT_ROWS enforces cap on force! | `setting-max-collect-rows-enforces-a-safety-cap-on-force-bang` | After setting to `10`, `force!` on a 100-element collection returns ≤10 rows |
| set! unknown constant raises error with hint | `set-bang-dtw-constant-with-an-unknown-constant-raises-an-error-with-hint` | `throws?` with message containing the valid key list |

Test isolation template:
```clojure
(deftest sample-size-constant-has-default-value-100
  (try
    (is (= 100 (eval-dt-last "n is SAMPLE_SIZE")))
    (finally (config/reset-config!))))
```

---

## 7. Design Decisions and Rationale

### Why `set!` is a special form (not a regular function)

A regular function receives evaluated arguments. If `set!` were a regular function, `set! dtw.SAMPLE_SIZE 200` would pass the *current value* of `SAMPLE_SIZE` (e.g. `100`) as the first argument — not the key name. The function would have no way to know which config key to update.

Making `set!` a special form (recognized by name in the `FnCall` branch) allows the evaluator to inspect the unevaluated AST of the first argument and extract the key name string. This is the same pattern as how `is` (Binding) works.

### Why ALL_CAPS bare names (not only `dtw.NAME`)

The BDD spec (lines 649–692) shows bare `SAMPLE_SIZE`, `DESCRIBE_SAMPLE_SIZE`, etc. without the `dtw.` prefix in most scenarios. The `dtw.NAME` form is used only for `set!` writes. This dual-form access matches the research doc recommendation (Option B: pre-bind in global env).

### Why `reify ILookup` for the `dtw` object

The existing `FieldAccess` evaluator calls `(get val (keyword fname))`. `clojure.core/get` dispatches to `ILookup.valAt` for types that implement it. A `reify` is the simplest way to make `dtw.X` dynamically read from the config atom without modifying the `FieldAccess` evaluator.

### Why `reset-config!` in tests

The config atom is global (JVM-level singleton). Tests that call `set! dtw.SAMPLE_SIZE 200` will leave the atom mutated, breaking subsequent tests that rely on the default value. `reset-config!` in a `finally` block guarantees isolation without requiring test ordering.

### `MAX_COLLECT_ROWS nil` means unlimited

`config/get-config :MAX_COLLECT_ROWS` returns `nil` by default. In `force!`, check `(when limit ...)` — if `nil`, no truncation. This means `valid-key?` must use `contains?` on the defaults map (not `some?` on the value), because `nil` is a legitimate config value.
