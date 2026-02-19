# datatwist-nrepl

nREPL middleware for DataTwist. Intercepts `eval` messages and routes them
through the DataTwist parser and evaluator (same JVM process), enabling
interactive evaluation from any nREPL-compatible client (CIDER, Calva, Conjure,
nREPL CLI).

## Architecture

The middleware follows the **Piggieback pattern**: it sits above
`interruptible-eval` in the nREPL middleware chain and hijacks eval messages,
routing them to the DataTwist evaluator instead of Clojure's `eval`.

```
nREPL client (CIDER / Calva / Conjure)
    |
    | eval message
    v
datatwist.nrepl.middleware/wrap-datatwist-eval
    |
    | (when session is a DataTwist session)
    v
datatwist.parser/parse  ->  datatwist.evaluator/eval-node
    |
    | result + updated env
    v
nREPL response {:value "..." :status #{"done"}}
```

## Supported nREPL Ops

| Op | Description |
|---|---|
| `eval` | Evaluate DataTwist source code; returns the last expression value |
| `complete` | Return completion candidates (stdlib functions + bound names in session) |
| `info` / `lookup` | Return documentation for a stdlib function or bound name |
| `inspect-start` | Begin inspecting a value; renders the value as a navigable structure |
| `inspect-push` | Drill into a nested element at the given index |
| `inspect-pop` | Return to the parent element |
| `load-file` | Load and evaluate a `.dt` file sequentially |

## Session Environment Persistence

By default, `datatwist.evaluator/eval-node` creates a fresh environment on
every call. The nREPL middleware extends this by storing the environment in the
nREPL session map between evaluations. Bindings defined with `is` persist
within a session:

```
eval: "name is \"Alice\""  => "Alice"
eval: "name"               => "Alice"   (persists from previous eval)
```

A new session starts with the stdlib default environment. Sessions are isolated
from each other.

## Inspector

The inspector reuses `orchard.inspect` (from the CIDER ecosystem) since
DataTwist values are standard JVM values (maps, vectors, longs, strings).
The only customization is rendering object keys as `name:` (postfix colon)
rather than `:name` (Clojure keyword syntax).

## Usage

Add to `deps.edn`:

```clojure
{:deps {nrepl/nrepl {:mvn/version "1.3.0"}
        cider/cider-nrepl {:mvn/version "0.50.2"}
        io.github.datatwist/datatwist-nrepl {:git/tag "v0.1.0" :git/sha "..."}}
 :aliases
 {:nrepl {:main-opts ["-m" "nrepl.cmdline"
                      "--middleware"
                      "[cider.nrepl/cider-middleware,datatwist.nrepl/middleware]"]}}}
```

Start the server:

```bash
clj -M:nrepl
```

The DataTwist middleware must be listed **after** `cider-nrepl/cider-middleware`
in the middleware vector so it intercepts eval messages before CIDER's eval
middleware.

## BDD Specifications

See `../../bdd/12-nrepl-integration.feature` for all acceptance scenarios.
