# DataTwist Backlog

## Current — Grammar Fixes

Evaluator complete. Remaining 4 failures + 14 errors are all grammar/test issues:

- [ ] Fix 3 tests using `;` — replace with `\n` (BDD spec says "Semicolons: NOT used")
- [ ] Negative number literals in lists and function args: `[-10 50]`, `nth items -1` (3 errors)
- [ ] `_` as binding target: `_ is {active: false}` (2 errors)
- [ ] Chained comparisons: `1 < 2 < 3` (1 error)
- [ ] Underscore number separators: `1_000_000` (1 error)
- [ ] Sourceless pipeline inside list context (1 error)
- [ ] `clj/` qualified names with multiple slashes (1 error)
- [ ] Regex literal `#","` not in grammar (1 error)
- [ ] Pipeline operator precedence: `sum > 100` inside pipe step (1 error)
- [ ] Test expectation mismatches: division Double vs Integer, typos (3 failures)

---

## P0 — GraalVM Native Binary

Build DataTwist as a standalone native binary via GraalVM `native-image`. This is critical for distribution and adoption — users shouldn't need a JVM installed.

- [ ] Add GraalVM native-image build config (`native-image.properties`, reflection config)
- [ ] Create CLI entry point (`-main` with arg parsing: file input, REPL, `--eval`)
- [ ] Handle Instaparse reflection hints for GraalVM (Instaparse uses `eval` internally — may need AOT or workaround)
- [ ] Add `make native` build target
- [ ] CI pipeline for building linux/macos/windows binaries
- [ ] Investigate startup time — target <50ms for CLI feel
- [ ] Consider shipping as uberjar fallback for platforms without native-image support

**Risks:** Instaparse relies on `clojure.core/eval` for parser generation which is problematic for GraalVM. Options: (1) AOT-compile the parser at build time, (2) switch to a GraalVM-compatible parser generator, (3) pre-generate the parser as data and load it.

---

## P1 — Pushdown Optimization

Push computation closer to data sources. Instead of loading all data into memory and filtering/transforming in DataTwist, push predicates and projections down to the source (database, API, file system).

### Phase 1: AST Analysis
- [ ] Build an AST analyzer that identifies pushdown-eligible operations
- [ ] Classify operations: filter predicates, field projections, sort, limit/offset, aggregations
- [ ] Detect pipeline segments that can be pushed down vs. must stay local

### Phase 2: Pushdown Protocol
- [ ] Define a `Pushdown` protocol/interface that data sources implement
- [ ] Operations: `:filter`, `:project`, `:sort`, `:limit`, `:aggregate`
- [ ] Each source declares which operations it supports
- [ ] Fallback: unsupported operations execute locally (transparent to user)

### Phase 3: Whole-Block Pushdown
- [ ] Push entire syntactic blocks (pipeline segments, guard blocks) as a unit
- [ ] Example: `data |> filter _.age > 18 |> sort-by _.name |> take 10` → single query
- [ ] Detect boundaries where pushdown must stop (user-defined functions, side effects, joins across sources)

### Phase 4: Source Implementations
- [ ] SQL pushdown (generate WHERE, SELECT, ORDER BY, LIMIT)
- [ ] REST API pushdown (query parameters, pagination)
- [ ] File system pushdown (glob patterns, streaming line filters)

---

## P1 — Module System & Connectors

Extend the language with importable modules and data source connectors. The goal is a plugin ecosystem where connectors can be distributed independently.

### Core Module System
- [ ] `require` already parses — implement module resolution (file-based, classpath, registry)
- [ ] Module search paths: `./modules/`, project `deps.edn`, global registry
- [ ] Module caching: load once, share across requires
- [ ] Circular dependency detection
- [ ] Module-level scope: private bindings (not exported) vs public API

### Connector Architecture
- [ ] Define connector interface: `connect`, `query`, `close`, schema introspection
- [ ] Connectors register as modules: `require db.postgres as pg`
- [ ] Connector lifecycle management (connection pooling, cleanup)
- [ ] Schema-aware autocomplete data for tooling

### Built-in Connectors (candidates)
- [ ] `db.postgres` — PostgreSQL via JDBC
- [ ] `db.sqlite` — SQLite (good for local/embedded use)
- [ ] `http` — HTTP client (GET/POST/PUT/DELETE, JSON auto-parse)
- [ ] `fs` — File system (read/write CSV, JSON, EDN, line-delimited)
- [ ] `csv` — CSV parsing with header inference

### Connector + Pushdown Integration
- [ ] Connectors that support pushdown implement the Pushdown protocol
- [ ] `pg.query "users" |> filter _.age > 18` → generates `SELECT * FROM users WHERE age > 18`
- [ ] Mixed pipelines: pushdown what you can, stream the rest

---

## P2 — REPL & Developer Experience

- [ ] Interactive REPL with readline support
- [ ] REPL history and tab completion
- [ ] Pretty-printed output (tables for lists of objects, syntax-highlighted values)
- [ ] `--watch` mode: re-run script on file change
- [ ] Error messages with source locations and suggestions

### Editor SDK / LSP

- [ ] LSP server for editor integration (syntax highlighting, go-to-definition, autocomplete)
- [ ] **Function signature hints with placeholder values** — при вводе `sort-by` показывать inline hint типа `sort-by ·field· ·asc/desc·`, для `filter` → `filter ·predicate·`, для `take` → `take ·n·`. Каждая stdlib-функция должна иметь metadata с именами параметров и примерами значений.
- [ ] Snippet-style placeholders: Tab между параметрами, dropdown для enum-like аргументов (asc/desc, true/false)
- [ ] Hover documentation: описание функции + пример использования + тип аргументов
- [ ] Autocomplete с контекстом: после `|>` предлагать collection-функции, после `.` предлагать поля из известной структуры
- [ ] Go-to-definition для `is`-биндингов и `require`-алиасов

---

## P2 — Performance & Streaming

- [ ] Lazy evaluation for large collections (don't materialize intermediate results)
- [ ] Streaming pipelines: process elements one-at-a-time where possible
- [ ] Parallel `map`/`filter` via `pmap` or virtual threads
- [ ] Benchmark suite with representative workloads
- [ ] Memory profiling for large datasets

---

## P3 — Language Extensions

- [ ] String interpolation: `"Hello {name}"`
- [ ] Multi-line strings / heredocs
- [ ] Date/time literals and operations
- [ ] Regular expression literals and match
- [ ] `match` expression (more expressive than guards)
- [ ] Spread operator in objects: `{...base, name: "new"}`
- [ ] Optional type annotations for documentation and tooling
