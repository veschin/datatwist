# nREPL Middleware Design

## 1. Architecture Overview

The DataTwist nREPL middleware follows the Piggieback pattern: it sits in the nREPL middleware chain above `interruptible-eval` and intercepts eval messages. When a session is marked as a DataTwist session, eval requests route through `datatwist.parser/parse` and `datatwist.evaluator/eval-node` instead of Clojure's `clojure.core/eval`.

```
nREPL client (CIDER / Calva / Conjure / CLI)
    |
    v
wrap-datatwist-eval  (this middleware)
    |
    +-- DT session? --yes--> parser/parse -> evaluator/eval-expr -> respond
    |
    +-- no  ---------> pass to next handler (standard Clojure eval)
```

DataTwist values are standard JVM objects (maps with keyword keys, vectors, longs, doubles, strings), so no serialization adapter is needed. The middleware formats results as strings for `:value` responses using `pr-str`.

The middleware lives in `plugins/datatwist-nrepl/` as a separate artifact with its own `deps.edn`. It depends on the core `datatwist` library as a git or local dep.

## 2. Middleware Chain

### Registration

The middleware descriptor is defined via `nrepl.middleware/set-descriptor!`:

```clojure
(ns datatwist.nrepl.middleware
  (:require [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.transport :as transport]))

(set-descriptor! #'wrap-datatwist-eval
  {:requires #{"clone" "close" "describe"}
   :expects  #{"eval"}
   :handles  {"eval"                   {}
              "complete"               {}
              "info"                   {}
              "lookup"                 {}
              "inspect-start"          {}
              "inspect-push"           {}
              "inspect-pop"            {}
              "load-file"              {}
              "inspect-pipeline-step"  {}}})
```

The `:expects #{"eval"}` declaration tells nREPL to place this middleware above `interruptible-eval` in the stack. The middleware must be listed **after** `cider-nrepl/cider-middleware` in the `--middleware` vector so it gets first crack at eval messages.

### Dispatch logic

The middleware checks two things before intercepting:

1. The nREPL session has a `:datatwist/env` key (set on first DT eval, or via an explicit `"init-datatwist"` op).
2. The op is one of the handled ops listed above.

If either condition is false, the message passes through to the next handler unchanged.

### Handled ops

| Op | Source | Description |
|---|---|---|
| `eval` | BDD Section 2, 3 | Parse + evaluate DT code, persist env |
| `complete` | BDD Section 4 | Completion candidates from stdlib + session bindings |
| `info` / `lookup` | cider-nrepl convention | Docstring / metadata for a symbol |
| `inspect-start` | BDD Section 5 | Begin value inspection |
| `inspect-push` | BDD Section 5 | Drill into nested value |
| `inspect-pop` | BDD Section 5 | Return to parent |
| `load-file` | BDD Section 6 | Read and evaluate a `.dt` file |
| `inspect-pipeline-step` | PRD | Return cached sample for a pipeline step |

## 3. Session Environment

### Storage

Each nREPL session is a Clojure atom containing a map. The middleware stores the DataTwist environment under a namespaced key:

```clojure
;; Inside the session atom:
{:datatwist/env   {"name" "Alice", "double" #<fn>, ...}  ;; env.clj map
 :datatwist/inspector nil}                                ;; orchard inspector state
```

On first eval, if `:datatwist/env` is absent, the middleware initializes it from `stdlib/default-env`. This gives every new session the full standard library.

### Eval flow with environment threading

The critical difference from `evaluate` / `evaluate-strict` (which create a fresh `default-env` on every call) is that the middleware must:

1. Read the current env from the session atom.
2. Parse the input via `parser/parse`.
3. Call `eval-expr` on the parsed AST with the session env. `eval-expr` returns `[value new-env]`, threading bindings through statements.
4. Write `new-env` back to the session atom.
5. Return the value.

This requires a new public function in `evaluator.clj`:

```clojure
(defn evaluate-with-env
  "Parse and evaluate DataTwist source, using the given environment.
   Returns [value new-env]. Throws structured DT errors on parse
   or runtime failure."
  [input env]
  (when-not (comment-or-whitespace-only? input)
    (let [ast (parser/parse input)]
      (if (insta/failure? ast)
        (throw (errors/parse-failure->dt-error ast input))
        (binding [*source* input]
          ;; eval-expr returns [value env'], threading bindings
          (try
            (eval-expr ast env)
            (catch clojure.lang.ExceptionInfo e (throw e))
            (catch ArithmeticException e
              (throw (ex-info (str "Type error: " (.getMessage e))
                              {:dt/error true :code "DT-T003"
                               :category "ARITHMETIC ERROR"
                               :source input})))
            ;; ... same catch clauses as evaluate-strict
            ))))))
```

This is the only change to the core library. Everything else lives in the plugin.

### Session lifecycle

| Event | Action |
|---|---|
| First DT eval in session | Initialize `:datatwist/env` from `stdlib/default-env` |
| Subsequent evals | Read env, evaluate, write updated env back |
| Failed eval | **Do not update env.** Session env stays at its pre-eval state (BDD: "session environment is unchanged from before the failing eval") |
| Session close (`"close"` op) | nREPL garbage-collects the session atom. No explicit cleanup needed -- the env map is just data, no resources to release |

### Isolation

Sessions are isolated by design: each nREPL session has its own atom, so `:datatwist/env` in session A is invisible to session B. This satisfies the BDD scenario "Bindings in one session do not leak into another session."

## 4. Op Specifications

### `eval`

Evaluate DataTwist source code. Multi-line input is treated as a program (sequence of statements); the last expression's value is returned.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"eval"` | yes | |
| `:code` | string | yes | DataTwist source code |
| `:session` | string | yes | nREPL session ID |
| `:id` | string | yes | Message ID |

**Response (success):**

| Field | Type | Description |
|---|---|---|
| `:value` | string | `pr-str` of the result value |
| `:ns` | string | Always `"datatwist.user"` (cosmetic, for client compatibility) |
| `:status` | set | `#{"done"}` |

**Response (error):**

| Field | Type | Description |
|---|---|---|
| `:err` | string | Rendered error string from `error-renderer/render-exception` |
| `:ex` | string | Error code, e.g. `"DT-P001"` |
| `:status` | set | `#{"done" "eval-error"}` |

Stdout output from side-effect functions (e.g., `tap!`, `print!`) is captured and sent as `:out` messages before the final `:value`/`:err` response.

### `complete`

Return completion candidates matching a prefix. Sources: stdlib function names + all bindings in the session environment.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"complete"` | yes | |
| `:prefix` | string | yes | Prefix to match |
| `:session` | string | yes | nREPL session ID |

**Response:**

| Field | Type | Description |
|---|---|---|
| `:completions` | list of maps | Each: `{:candidate "filter" :type "function"}` |
| `:status` | set | `#{"done"}` |

Candidate type is determined by the value in the env: if it implements `IFn`, type is `"function"`; otherwise `"var"`.

### `info` / `lookup`

Return documentation for a stdlib function or session binding.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"info"` or `"lookup"` | yes | |
| `:sym` | string | yes | Symbol name |
| `:session` | string | yes | nREPL session ID |

**Response:**

| Field | Type | Description |
|---|---|---|
| `:info` | map | `{:name "filter" :doc "..." :arglists "..."}` |
| `:status` | set | `#{"done"}` |

For stdlib functions, documentation is pulled from a static registry (metadata map in the plugin, since DataTwist functions don't carry Clojure metadata). For user bindings, the response includes the current value's type.

### `inspect-start`

Evaluate a DT expression and begin inspecting the result value.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"inspect-start"` | yes | |
| `:code` | string | yes | DataTwist expression to evaluate and inspect |
| `:session` | string | yes | nREPL session ID |

**Response:**

| Field | Type | Description |
|---|---|---|
| `:value` | string | Rendered inspection output (customized: object keys use postfix colon `name:` not `:name`) |
| `:status` | set | `#{"done"}` |

### `inspect-push`

Drill into a nested element at the given index.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"inspect-push"` | yes | |
| `:idx` | integer | yes | Index of the element to drill into |
| `:session` | string | yes | nREPL session ID |

**Response:** Same shape as `inspect-start`.

### `inspect-pop`

Return to the parent element after drilling in.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"inspect-pop"` | yes | |
| `:session` | string | yes | nREPL session ID |

**Response:** Same shape as `inspect-start`.

### `load-file`

Read a `.dt` file from disk and evaluate it sequentially. All bindings defined in the file persist in the session.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"load-file"` | yes | |
| `:file` | string | yes | Absolute or project-relative path to the `.dt` file |
| `:session` | string | yes | nREPL session ID |

**Response:** Same as `eval`. The `:value` is the last expression's result. Parse errors include the line number within the file.

### `inspect-pipeline-step`

Return cached sample data for a specific pipeline step. Does not re-evaluate -- reads from the DTPipeline step cache.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `:op` | `"inspect-pipeline-step"` | yes | |
| `:file` | string | yes | Source file path |
| `:line` | integer | yes | Line number of the pipeline |
| `:step-index` | integer | yes | 0-based step index |
| `:session` | string | yes | nREPL session ID |

**Response:**

| Field | Type | Description |
|---|---|---|
| `:value` | string | Rendered sample data (pr-str of the cached step result) |
| `:step-label` | string | Source code string for that pipeline step |
| `:row-count` | integer | Number of rows in the step result (if collection) |
| `:status` | set | `#{"done"}` |

This op depends on the DTPipeline reification (Feature 8, partially implemented). Until DTPipeline step caching is complete, this op returns `{:status #{"done" "no-cache"}}`.

## 5. Error Mapping

All DataTwist errors flow through the same path: exceptions with `{:dt/error true}` in their ex-data.

### Error categories to nREPL responses

| DT Error Code | Category | nREPL Mapping |
|---|---|---|
| `DT-P0XX` | Parse errors | `:err` with rendered parse error, `:ex "DT-P0XX"` |
| `DT-R0XX` | Runtime errors | `:err` with rendered runtime error, `:ex "DT-R0XX"` |
| `DT-T0XX` | Type errors | `:err` with rendered type error, `:ex "DT-T0XX"` |
| `DT-D0XX` | Data warnings | Not errors; attached as `:out` messages (warnings don't abort eval) |

### Rendering

The middleware uses `error-renderer/render-exception` to produce the `:err` string:

```clojure
(require '[datatwist.error-renderer :as renderer])

(defn- handle-dt-error [transport msg e]
  (let [err-data (ex-data e)
        rendered (renderer/render-exception e {:file (:file msg "repl")})]
    (transport/send transport
      (merge (select-keys msg [:id :session])
             {:err    rendered
              :ex     (or (:code err-data) "DT-R000")
              :status #{"done" "eval-error"}}))))
```

Color is disabled by default in nREPL responses (`*use-color*` defaults to `false`). Clients that support ANSI (terminal nREPL) can request colored output via a session option.

### Env rollback on error

When eval throws, the session env is **not updated**. The middleware wraps the eval+env-update in a try/catch:

```clojure
(let [env-before (get-session-env session)]
  (try
    (let [[value new-env] (evaluator/evaluate-with-env code env-before)]
      (set-session-env! session new-env)
      (send-value transport msg value))
    (catch Exception e
      ;; env stays at env-before -- no update
      (handle-dt-error transport msg e))))
```

## 6. Dependencies

The plugin `deps.edn`:

```clojure
{:paths ["src"]
 :deps {nrepl/nrepl           {:mvn/version "1.3.0"}
        cider/cider-nrepl     {:mvn/version "0.50.2"}
        cider/orchard         {:mvn/version "0.27.1"}
        io.github.datatwist/datatwist {:local/root "../.."}}
 :aliases
 {:dev {:extra-deps {nrepl/bencode {:mvn/version "1.2.0"}}
        :extra-paths ["test"]}}}
```

| Dependency | Purpose |
|---|---|
| `nrepl/nrepl` | nREPL server, transport, middleware protocol |
| `cider/cider-nrepl` | CIDER middleware (completion, info, inspector infrastructure) |
| `cider/orchard` | `orchard.inspect` for value inspection. DT values are JVM values, so orchard works directly. Only customization: render keyword keys as postfix colon |
| `datatwist` (local) | Core parser, evaluator, stdlib, error system |
| `nrepl/bencode` (dev) | For integration tests that speak raw nREPL bencode |

## 7. Implementation Plan

### Phase 1: Core eval loop (BDD Sections 1-3)

Scope: Connection, evaluation, session persistence.

1. Add `evaluate-with-env` to `evaluator.clj` -- the only core library change.
2. Create `datatwist.nrepl.middleware` namespace with `wrap-datatwist-eval`.
3. Implement `eval` op: parse, evaluate with session env, persist env on success, rollback on error.
4. Implement session initialization (`:datatwist/env` from `stdlib/default-env`).
5. Implement stdout capture for side-effect functions (bind `*out*` to a StringWriter, flush as `:out` messages).
6. Wire up server startup: `deps.edn` alias, `.nrepl-port` file.

Deliverable: Can connect from nREPL CLI, evaluate DT expressions, bindings persist across evals, errors return `:err` responses.

### Phase 2: Completion and info (BDD Section 4)

Scope: Code completion and documentation lookup.

1. Implement `complete` op: scan stdlib keys + session env keys for prefix matches.
2. Implement `info`/`lookup` ops: static docstring registry for stdlib, type info for user bindings.
3. Candidate type classification (`IFn` check).

Deliverable: CIDER/Calva show completion popup and eldoc for DT functions.

### Phase 3: Inspector (BDD Section 5)

Scope: Value inspection with postfix-colon key rendering.

1. Integrate `orchard.inspect` for `inspect-start`, `inspect-push`, `inspect-pop`.
2. Custom renderer that converts `:keyword` display to `keyword:` (DT syntax).
3. Store inspector state in session under `:datatwist/inspector`.

Deliverable: Can inspect maps, vectors, nested structures from CIDER inspector.

### Phase 4: Load file and pipeline inspection (BDD Section 6 + PRD)

Scope: File loading and pipeline step inspection.

1. Implement `load-file` op: read `.dt` file, evaluate with session env, return last value.
2. Implement `inspect-pipeline-step` op (stub until DTPipeline caching is complete in Feature 8).
3. Parse errors in loaded files include file path and line numbers.

Deliverable: Can load `.dt` files from editor. Pipeline step inspection ready for when DTPipeline ships.

### Phase 5: Error handling hardening (BDD Section 7)

Scope: Edge cases and robustness.

1. Verify env rollback on all error types (parse, runtime, type).
2. Verify session survives errors (subsequent evals work).
3. Integration tests with raw bencode client.
4. Test concurrent sessions with independent environments.

Deliverable: All 25 BDD scenarios pass. Middleware ready for use with CIDER, Calva, and Conjure.

## Appendix: File Layout

```
plugins/datatwist-nrepl/
  deps.edn
  src/
    datatwist/nrepl/
      middleware.clj        ;; wrap-datatwist-eval, op dispatch, session env management
      eval.clj              ;; eval op handler, stdout capture, env threading
      completion.clj        ;; complete op handler, candidate generation
      info.clj              ;; info/lookup op handler, docstring registry
      inspector.clj         ;; inspect-start/push/pop, orchard integration, key rendering
      pipeline_inspect.clj  ;; inspect-pipeline-step op handler
  test/
    datatwist/nrepl/
      middleware_test.clj   ;; integration tests with embedded nREPL server
      eval_test.clj
      completion_test.clj
      inspector_test.clj
```
