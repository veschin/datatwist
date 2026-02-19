# DataTwist Backlog

Status legend: `🔬 research` `📝 bdd/tests` `🔍 audit` `🚧 in progress` `✅ done` `⏳ waiting` (blocked/needs user)

---

## P0 — Critical Path

### GraalVM Native Binary `🔬 research`

Build DataTwist as a standalone native binary via GraalVM `native-image`. Critical for distribution.

- [ ] Create CLI entry point (`-main` with arg parsing: file input, REPL, `--eval`)
- [ ] Add GraalVM native-image build config (`native-image.properties`, reflection config)
- [ ] Handle Instaparse reflection hints for GraalVM
- [ ] Add `make native` build target
- [ ] CI pipeline for building linux/macos/windows binaries
- [ ] Investigate startup time — target <50ms
- [ ] Consider shipping as uberjar fallback
- [ ] AOT-compile the parser at build time (Instaparse uses `clojure.core/eval` at parse generation — problematic for GraalVM)

---

## P1 — High Priority

### Lazy Evaluation `🔬 research` — NEEDS RE-RESEARCH

BDD: `bdd/8-lazy-evaluation.feature`, Tests: `test/datatwist/lazy_eval_test.clj` (76 TDD stubs)
Design doc: `docs/lazy-eval-design.md`

- [ ] Lazy sequences for large collections
- [ ] Streaming pipelines
- [ ] Short-circuit evaluation in guards and boolean ops
- [ ] `take`/`drop` on infinite sequences

#### Design decisions (locked)

- **Laziness is invisible to user**: lazy seqs auto-materialize at any user-facing boundary (REPL, str, save!, tap!, `=`). User NEVER sees `LazySeq@...`. Laziness only exists between pipeline steps for performance.
- **Remove**: `inspect`, `log!`, `print`, `collect` from pipeline vocabulary
- **`force!`** is the only materialization function (not `collect`)
- **`tap!`** syntax: `tap! _data_ "format %s" (args)` — print before data, passthrough. No lambda needed.
- **Reified pipeline**: `|>` builds a DTPipeline record that remembers steps + caches samples per step
- **Full introspection**: cursor on ANY expression → sample result. Cursor on ANY variable (e.g. `x` in `[x -> x > 1]`) → sample of that variable's values. On-demand computation.
- **Conditional sampling**: `#sample {name: "Alex"}` — predicate-based sample filtering (like `#dbg` in Clojure)
- **Bi-directional traversal**: step forward/backward through pipeline computations
- **Compression + batching + streaming**: real data can be huge, design for it from day 1
- **Constants**: `SAMPLE_SIZE`, `MAX_COLLECT_ROWS`, `DESCRIBE_SAMPLE_SIZE` — uppercase symbols, mutable config values
- **Auto-materialize contexts**: REPL output, `str`/concat, `tap!`, `save!`, `=` comparison, error messages — all auto-force with configurable limit

### Pushdown Optimization `⏳ waiting`

Design doc: `docs/pushdown-design.md`. Blocked: gap analysis after lazy eval.

- [ ] Translate pipeline AST to flat operation list
- [ ] Classify ops: pushable vs local
- [ ] Find pushdown boundary (longest pushable prefix)
- [ ] Pushdown protocol (`push-filter`, `push-sort`, `push-limit`, `execute`)
- [ ] Predicate analysis: `_.age > 18` → `{:op :> :field "age" :value 18}`
- [ ] SQL generation: filter→WHERE, sort→ORDER BY, take→LIMIT
- [ ] Projection pushdown: analyze field access, generate SELECT with specific columns
- [ ] Aggregation pushdown: sum/count/avg → SQL aggregates
- [ ] Join between pushed sources

### Module System & Connectors `⏳ waiting`

Needs design decisions on module resolution strategy. Blocked: needs user.

- [ ] Module resolution (file-based, classpath, registry)
- [ ] Module caching, circular dependency detection
- [ ] Connector interface: `connect`, `query`, `close`, schema introspection
- [ ] Built-in connectors: postgres, sqlite, http, fs, csv
- [ ] Connector + Pushdown integration

### Demo Runner Rework `🔬 research`

- [ ] Demo runner reads and parses `.dt` files from `resources/examples/`
- [ ] Section markers in `.dt` files via comments (`// @section Pipelines`)
- [ ] Expression-by-expression evaluation with formatted output
- [ ] Support `// @expect result` annotations
- [ ] Remove hardcoded demo data from `demo_runner.clj`
- [ ] Remove `demo-glow` Makefile target

### nREPL & Editor Integration `🔬 research`

Target: CIDER-like experience for DataTwist. Plugins live in `plugins/` (future separate repos).

- [ ] nREPL server — middleware for DataTwist eval on top of Clojure nREPL
- [ ] Eval sub-expression — parse sub-expr at cursor position, eval in current context
- [ ] Inspector — drill-down into nested objects/lists (like CIDER inspector)
- [ ] `datatwist-mode` for Emacs — CIDER-like package (nREPL connection, eval, inspect, overlay results)
- [ ] Inline result display — result next to expression (CIDER overlays style)

### Async & Parallel Execution `⏳ waiting`

Needs design decisions (explicit vs implicit). Blocked: needs user.

- [ ] Async call syntax: fire-and-forget or await-based
- [ ] Parallel map/filter: automatic parallelization of collection operations
- [ ] Data dependency resolution: transparent blocking when data not ready
- [ ] Pipeline-level parallelism: independent branches execute concurrently
- [ ] Error propagation across async boundaries
- [ ] Backpressure and resource limits (thread pool, connection pool)
- [ ] Integration with JVM virtual threads (Project Loom)
- [ ] Design: explicit opt-in vs implicit runtime parallelism

---

## P2 — Medium Priority

### REPL & Developer Experience `⏳ waiting`

Depends on nREPL & Editor Integration research.

- [ ] Interactive REPL with readline support
- [ ] REPL history and tab completion
- [ ] Pretty-printed output (tables for lists of objects)
- [ ] `--watch` mode: re-run script on file change
- [ ] Error messages with source locations and suggestions

### LSP / Editor Support `🔬 research`

Design doc: `docs/lsp-tree-sitter-design.md`. Plugins live in `plugins/lsp/`.

- [ ] Tree-sitter grammar (`grammar.js` from Instaparse EBNF)
- [ ] LSP server (TypeScript + Tree-sitter WASM)
- [ ] TextMate grammar — quick-win syntax highlighting for VS Code/Sublime/GitHub
- [ ] Function signature hints with tab-stops
- [ ] Hover documentation, context-aware autocomplete
- [ ] Go-to-definition for `is`-bindings and `require`-aliases
- [ ] Eldoc-style function signatures in Emacs

### Error Reporting `🔬 research`

BDD: `bdd/9-error-reporting.feature`, Tests: `test/datatwist/error_reporting_test.clj` (42 TDD stubs)
Research doc: `docs/error-reporting-research.md`

- [ ] Error code system: `DT-PXXX` / `DT-TXXX` / `DT-RXXX`
- [ ] Elm/Rust-style messages with source snippets and hints
- [ ] Suppress Java/Clojure stack traces from user output
- [ ] Data-aware warnings (nil prevalence, common mistakes)
- [ ] JSON error output: `{:code "DT-T001" :message "..." :hint "..." :line N :col N}`
- [ ] Error code registry (`docs/error-codes.md`): catalog of all codes with descriptions, examples, fix suggestions

### Performance & Streaming `⏳ waiting`

- [ ] Benchmark suite
- [ ] Memory profiling
- [ ] Parallel `map`/`filter` via virtual threads

### Credentials & Network Configuration `⏳ waiting`

Needs design decisions. Blocked: needs user.

- [ ] Credential store: encrypted local config (`.datatwist/credentials`) or native `pass` integration
- [ ] Connection profiles: named configs (`work-db`, `prod-api`) with host, port, auth, proxy settings
- [ ] Proxy/VPN-aware connections: per-profile SOCKS5/HTTP proxy
- [ ] `connect` function uses profiles: resolves creds + proxy automatically
- [ ] Environment-based overrides: `DT_PROFILE=work` selects default connection profile
- [ ] Keyring integration: macOS Keychain, Linux secret-service, Windows Credential Manager
- [ ] Design: config format (TOML, EDN, or DataTwist syntax?), encryption strategy (GPG vs OS keyring)

---

## P3 — Future

### Language Extensions

- [ ] String interpolation: `"Hello {name}"`
- [ ] Multi-line strings / heredocs (`"""..."""`)
- [ ] Date/time literals and operations
- [ ] Spread operator in objects: `{...base, name: "new"}`
- [ ] Optional type annotations for tooling
- [ ] Block comments: `(comment ...)` form — parsed but not evaluated
- [ ] Line comments with `;;` (in addition to `//`)
- [ ] Built-in formatter (`datatwist fmt`): opinionated auto-format (indentation, line width, pipeline alignment, guard alignment)

### Editor Quick Wins

- [ ] Debugger / step-through evaluation
- [ ] Notebook mode — scratch buffer with persistent results (like Org-babel)
- [ ] Data visualization in REPL — tables, charts for collection data
