# DataTwist

A functional data processing language on Clojure/JVM.

## Overview

DataTwist compiles to Clojure via an Instaparse EBNF grammar. It provides pipe-first semantics, pattern matching, and nil-tolerant field access in a syntax designed for data pipelines. The runtime is the JVM; all Clojure interop is available.

## Examples

**Pipelines & pattern matching:**
```
users is [
  {name: "Alice" age: 28 active: true}
  {name: "Bob" age: 17 active: true}
  {name: "Carol" age: 35 active: false}
]

users
  |> filter _.active and _.age >= 18
  |> map {name: _.name tier:
    | _.age > 30 -> "senior"
    | _.age > 20 -> "mid"
    | _ -> "junior"
  }
  |> sort-by _.name
; => [{name: "Alice" tier: "mid"}]
```

**Functions & destructuring:**
```
; functions are [params -> body]
double is [x -> x * 2]
[10 20 30] |> map double |> take 2   ; => [20 40]

; object destructuring
{name, age} is {name: "Alice" age: 28 city: "NYC"}
name   ; => "Alice"

; list destructuring with rest
[first, &rest] is [1 2 3 4 5]
rest   ; => [2 3 4 5]
```

**String pattern destructuring (`#p`):**
```
; extract structured data from strings
"alice@example.com" | #p"{user}@{domain}" -> {user, domain}
; => {user: "alice" domain: "example.com"}

; parse logs with named captures
log-fmt is #p"{ip} - [{time}] \"{method} {url}\" {status}"

logs |> map (extract _ log-fmt) |> filter _.status = "500"
```

**Data sources & credentials:**
```
; connect to saved profile
db is connect dtw.connections.prod-db dtw.vpn.prod-vpn

db |> query "SELECT * FROM users"
   |> filter _.active
   |> group-by _.department
   |> map {dept: _.key count: _.value |> length}

; credentials from pass
{db-host, db-pass} is pass.myproject
```

**String interpolation & tap:**
```
name is "world"
greeting is #s"Hello {name}!"   ; => "Hello world!"

; debug pipeline with tap!
data |> filter _.active
     |> tap! "after filter: %s"
     |> sort-by _.name
```

## Features

- Pipe-first operator (`|>`) as the core abstraction
- Functions as `[params -> body]` with closures and multi-arity
- Pattern matching with guards (`| condition -> result`)
- Object and list destructuring with defaults, rest (`&`), and renaming
- `#p"..."` string pattern destructuring — extract data from strings like reverse `format`
- `#s"..."` string interpolation
- Nil-tolerant field access (`user.missing.deep` returns `nil`)
- Nil coalescing (`??`), nil coercion in arithmetic
- Side-effect functions (`tap!`, `save!`) with passthrough semantics
- Data sources: databases, files, Docker, Kubernetes — all queryable via `|>`
- Credentials via `pass` (password-store), config via `dtw.*` namespace
- Lazy evaluation with automatic materialization
- `datatwist fmt` — opinionated formatter, auto-generates `@doc` from runtime types
- Objects (Clojure maps), lists (Clojure vectors)
- `is` for binding, `=` for equality
- `;` comments, `(comment ...)` blocks

## Requirements

- Java 11+ (JVM)
- [Clojure CLI](https://clojure.org/guides/install) (`clj`)

No other dependencies. The project uses `deps.edn` with a single dependency: `instaparse 1.5.0`.

## Usage

```bash
# Run all tests
make test

# Run the language demo
make demo

# Run a single test namespace
clj -M -e "(require 'clojure.test 'datatwist.literals-test) (clojure.test/run-tests 'datatwist.literals-test)"

# Lint
make lint
```

## Project Status

DataTwist is in early development. The grammar and parser are complete. The tree-walking evaluator covers core language features (literals, operators, bindings, functions, pipelines, pattern matching, data structures, nil semantics). Error reporting, lazy evaluation, and Clojure interop are in design.

This is not yet suitable for production use.

## Documentation

- [PRD.md](PRD.md) -- language specification and design decisions
- [CHANGELOG.md](CHANGELOG.md) -- development history
- [bdd/](bdd/) -- Gherkin feature files (authoritative language spec)
- [docs/](docs/) -- design documents (lazy evaluation, error reporting, LSP)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

All participants are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

MIT
