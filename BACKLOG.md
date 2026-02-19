# DataTwist Backlog

## Current — Grammar Fixes `DONE`

506 tests, 0 failures, 0 errors.

- [x] Fix 3 tests using `;` — replaced with `\n`
- [x] `_` as binding target — tests rewritten to use pipeline
- [x] Test expectation mismatches — division Double, typos, lowercase
- [x] Negative number literals in lists and function args: `[-10 50]`, `nth items -1` — NegFieldAccess rule
- [x] Regex literal `#","` — added Regex rule + evaluator (compiles to java.util.regex.Pattern)
- [x] Pipeline operator precedence: `sum > 100` inside pipe step — already works, verified
- ~Chained comparisons `1 < 2 < 3`~ — NOT supported by design (BDD decision)
- ~Underscore number separators `1_000_000`~ — NOT supported in v1 (BDD decision)
- ~Sourceless pipeline inside list `[|> f]`~ — deferred, only needed for `tee` (v2)

---

## Evaluator `DONE`

- [x] Phase 1: Literals + Operators + Simple Binding
- [x] Phase 2: Data Structures + Field Access
- [x] Phase 3: Functions + Closures
- [x] Phase 4: Pipelines
- [x] Phase 5: Destructuring
- [x] Phase 6: Pattern Matching + Guards
- [x] Phase 7: Advanced (recur, compose, try/catch, require, interop)
- [x] Nil semantics: three-valued comparisons, arithmetic coercion
- [x] `require` + namespace aliases
- [x] Java static fields/methods, constructors, exception field access
- [x] Guard truthiness in standalone context

---

## BDD & Tests `DONE`

- [x] Features 1-6: BDD scenarios + test files (407 tests)
- [x] Feature 7: Interop — BDD rewritten to PRD, 95 tests
- [x] Feature 8: Lazy Eval — BDD rewritten to PRD, 71 tests (TDD stubs)
- [x] Feature 9: Error Reporting — BDD rewritten to PRD, 42 tests (TDD stubs)

---

## Design Docs `DONE`

- [x] `docs/lsp-tree-sitter-design.md` — LSP + Tree-sitter architecture
- [x] `docs/pushdown-design.md` — Pushdown optimization research

---

## P0 — GraalVM Native Binary `NOT STARTED`

Build DataTwist as a standalone native binary via GraalVM `native-image`. Critical for distribution.

- [ ] Create CLI entry point (`-main` with arg parsing: file input, REPL, `--eval`)
- [ ] Add GraalVM native-image build config (`native-image.properties`, reflection config)
- [ ] Handle Instaparse reflection hints for GraalVM
- [ ] Add `make native` build target
- [ ] CI pipeline for building linux/macos/windows binaries
- [ ] Investigate startup time — target <50ms for CLI feel
- [ ] Consider shipping as uberjar fallback

**Risks:** Instaparse relies on `clojure.core/eval` for parser generation which is problematic for GraalVM. Options: (1) AOT-compile the parser at build time, (2) switch to a GraalVM-compatible parser generator, (3) pre-generate the parser as data and load it.

---

## P1 — Pushdown Optimization `DESIGNED`

Design doc: `docs/pushdown-design.md`

### Phase 1: Pipeline IR + Classification
- [ ] Translate pipeline AST to flat operation list
- [ ] Classify ops: pushable vs local
- [ ] Find pushdown boundary (longest pushable prefix)

### Phase 2: SQL Source
- [ ] Pushdown protocol (`push-filter`, `push-sort`, `push-limit`, `execute`)
- [ ] Predicate analysis: `_.age > 18` → `{:op :> :field "age" :value 18}`
- [ ] SQL generation: filter→WHERE, sort→ORDER BY, take→LIMIT

### Phase 3: Projection Pushdown
- [ ] Analyze which fields are accessed in pipeline
- [ ] Generate SELECT with specific columns

### Phase 4: Aggregation + Multi-source
- [ ] Sum/count/avg → SQL aggregates
- [ ] Join between pushed sources

---

## P1 — Module System & Connectors `NOT STARTED`

- [ ] Module resolution (file-based, classpath, registry)
- [ ] Module caching, circular dependency detection
- [ ] Connector interface: `connect`, `query`, `close`, schema introspection
- [ ] Built-in connectors: postgres, sqlite, http, fs, csv
- [ ] Connector + Pushdown integration

---

## P1 — Async & Parallel Execution `NOT STARTED`

Built-in parallelization and async support. Any call can be made async — the language handles data dependencies automatically.

- [ ] Async call syntax: fire-and-forget or await-based (`async`, `await` or similar)
- [ ] Parallel map/filter: automatic parallelization of collection operations
- [ ] Data dependency resolution: if data isn't ready yet, wait transparently
- [ ] Pipeline-level parallelism: independent pipeline branches execute concurrently
- [ ] Error propagation across async boundaries
- [ ] Backpressure and resource limits (thread pool, connection pool)
- [ ] Integration with JVM virtual threads (Project Loom)

**Design notes:** Requires deep design work. Key questions:
- What happens when async call needs data that isn't available yet? Block? Return nil? Queue?
- How to express "run these N things in parallel and collect results"?
- How does this interact with pipelines and `|>`?
- Should parallelism be explicit (user opts in) or implicit (runtime decides)?

---

## P2 — REPL & Developer Experience `NOT STARTED`

- [ ] Interactive REPL with readline support
- [ ] REPL history and tab completion
- [ ] Pretty-printed output (tables for lists of objects)
- [ ] `--watch` mode: re-run script on file change
- [ ] Error messages with source locations and suggestions

### Editor SDK / LSP `DESIGNED`

Design doc: `docs/lsp-tree-sitter-design.md`

- [ ] Tree-sitter grammar (grammar.js from Instaparse EBNF)
- [ ] LSP server (TypeScript + Tree-sitter WASM)
- [ ] **Function signature hints**: `sort-by ·field· ·asc/desc·` with tab-stops and dropdowns
- [ ] Hover documentation, context-aware autocomplete
- [ ] Go-to-definition for `is`-bindings and `require`-aliases

---

## P2 — Performance & Streaming `NOT STARTED`

- [ ] Lazy evaluation for large collections
- [ ] Streaming pipelines
- [ ] Parallel `map`/`filter` via virtual threads
- [ ] Benchmark suite
- [ ] Memory profiling

---

## P2 — Error Reporting `NOT STARTED`

BDD: `bdd/9-error-reporting.feature`, Tests: `test/datatwist/error_reporting_test.clj` (42 TDD stubs)

- [ ] Error code system: `DT-PXXX` / `DT-TXXX` / `DT-RXXX`
- [ ] Elm/Rust-style messages with source snippets and hints
- [ ] Suppress Java/Clojure stack traces from user output
- [ ] Data-aware warnings (nil prevalence, common mistakes)
- [ ] JSON error output format: structured `{:code "DT-T001" :message "..." :hint "..." :line N :col N}` for tooling/IDE consumption
- [ ] Error code registry (`docs/error-codes.md`): catalog of all DT-PXXX/TXXX/RXXX/DXXX codes with descriptions, examples, and fix suggestions — single source of truth for renderers

---

## P3 — Language Extensions `NOT STARTED`

- [ ] String interpolation: `"Hello {name}"`
- [ ] Multi-line strings / heredocs
- [ ] Date/time literals and operations
- [ ] Regular expression literals and match
- [ ] Spread operator in objects: `{...base, name: "new"}`
- [ ] Optional type annotations for tooling
