# DataTwist Backlog

Status legend: `🔬 research` `📝 bdd/tests` `🔍 audit` `🚧 in progress` `✅ done` `⏳ waiting` (blocked/needs user)

---

## P0 — Critical Path

### GraalVM Native Binary `🔬 research`

Build DataTwist as a standalone native binary via GraalVM `native-image`. Critical for distribution. The binary IS the DataTwist CLI — the main entry point for all user-facing workflows.

**Decision (preliminary):** The GraalVM native binary is the DataTwist CLI. Subcommands cover the full lifecycle: run scripts, REPL, format, lint, test, web UI, credential management. TUI experience via a gum-style visual framework (charmbracelet/gum or similar). Need to determine which features are available per platform (e.g., `pass` integration is Unix-only, webui may need JVM fallback).

- [ ] Create CLI entry point (`-main` with arg parsing: file input, REPL, `--eval`)
- [ ] Add GraalVM native-image build config (`native-image.properties`, reflection config)
- [ ] Handle Instaparse reflection hints for GraalVM
- [ ] Add `make native` build target
- [ ] CI pipeline for building linux/macos/windows binaries
- [ ] Investigate startup time — target <50ms
- [ ] Consider shipping as uberjar fallback
- [ ] AOT-compile the parser at build time (Instaparse uses `clojure.core/eval` at parse generation — problematic for GraalVM)
- [ ] CLI subcommands: `datatwist run`, `datatwist repl`, `datatwist eval`, `datatwist fmt`, `datatwist lint`, `datatwist test`, `datatwist webui`
- [ ] `datatwist creds add <project> <key>` — add credential to pass store (`datatwist/<project>/<key>`)
- [ ] `datatwist creds list [project]` — list credentials
- [ ] `datatwist creds remove <project> <key>` — remove credential
- [ ] TUI framework (charmbracelet/gum style) — visual interactive prompts, selection, spinners
- [ ] Platform-specific feature matrix: which subcommands available on linux/macos/windows

**Decision (locked):** `datatwist connect` is an interactive wizard for setting up data source connections. The CLI knows all supported source types (databases, files, APIs, Docker, K8s), guides the user through configuration with TUI prompts (gum-style), tests the connection, and saves it as a named profile. Saved profiles are reusable via `connect "profile-name"` in scripts. Credentials flow through the `pass` module.

- [ ] `datatwist connect` — interactive TUI wizard for setting up data sources
- [ ] Source type registry: each connector declares its required fields (host, port, db, auth, etc.)
- [ ] Connection testing: verify connectivity before saving profile
- [ ] Profile storage: save/load connection profiles (location TBD — `~/.datatwist/connections/` or project-local)
- [ ] `datatwist connect list` — show saved profiles
- [ ] `datatwist connect test <profile>` — re-test a saved connection
- [ ] File source wizard: interactive file picker for CSV/JSON/Parquet, detect format and schema
- [ ] `connect "profile-name"` in scripts — resolve saved profile, return connection object

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
- **`tap!`** is fully safe, never mutates data. Output is redirectable (console, file, IDE, custom). Format: `tap! "format %s" (args)`. Output format: first line is function label `[fn]`, second line is sample data.
- **`autotap!`** placed at start of pipeline, wraps every subsequent step with `tap!` output (function-label on first line + sample on second line)
- **Reified pipeline**: `|>` builds a DTPipeline record that remembers steps + caches samples per step
- **Full introspection**: cursor on ANY expression → sample result. Cursor on ANY variable (e.g. `x` in `[x -> x > 1]`) → sample of that variable's values. On-demand computation. No re-execution — cached.
- **Sub-step introspection**: inspect not just step results but steps within steps (nested pipelines, lambda internals)
- **Conditional sampling**: `#sample {predicate}` reader macro — filter sample data. `SAMPLE_ATTEMPTS` controls retries. Can set sample size to zero.
- **Bi-directional traversal**: step forward/backward through pipeline computations, drill into collections
- **Cache management**: everything cached by default. `invalidate-cache!` for explicit reset. Cache at end of pipeline → whole pipeline re-executes.
- **No blocking ever**: streaming-first, notification when sample ready for inspection
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

### Module System & Connectors `🔬 research`

**Decision (locked)**: Clojure-style namespaces + require.
```
require math              // everything under math namespace
require math.{sin cos}    // specific functions
math/sin 3.14             // qualified access
```

**Decision (preliminary)**: Connector interface — minimal, 5 functions: `connect`, `query`, `close!`, `tables`, `schema`. Not ready to go deeper yet.

**Connection pool (preliminary)**: `connect` creates a connection pool internally (HikariCP). User doesn't manage the pool explicitly. Default pool size configurable via `dtw.POOL_SIZE` (default: 5). Transparent — simple interface, complexity hidden inside.

- [ ] Namespace resolution (file-based, classpath, registry)
- [ ] `require` with qualified access and selective import
- [ ] Namespace caching, circular dependency detection
- [ ] Connector interface: `connect`, `query`, `close!`, `tables`, `schema`
- [ ] Built-in connectors: postgres, sqlite, http, fs, csv
- [ ] Connector + Pushdown integration
- [ ] Connection pool management (HikariCP) — transparent, configurable via `dtw.POOL_SIZE`

### Demo Runner Rework `🔬 research`

- [ ] Demo runner reads and parses `.dt` files from `resources/examples/`
- [ ] Section markers in `.dt` files via comments (`// @section Pipelines`)
- [ ] Expression-by-expression evaluation with formatted output
- [ ] Support `// @expect result` annotations
- [ ] Remove hardcoded demo data from `demo_runner.clj`
- [ ] Remove `demo-glow` Makefile target

### nREPL & Editor Integration `🔬 research`

Target: CIDER-like experience for DataTwist. Plugins live in `plugins/` (future separate repos).

#### Design decisions (locked)

- **Reference implementation**: CIDER is the reference. Emacs-first.
- **MVP scope**: eval sub-expression at cursor + inspect drill-down (like CIDER inspector). Nothing more for MVP.

- [ ] nREPL server — middleware for DataTwist eval on top of Clojure nREPL
- [ ] Eval sub-expression — parse sub-expr at cursor position, eval in current context
- [ ] Inspector — drill-down into nested objects/lists (like CIDER inspector)
- [ ] `datatwist-mode` for Emacs — CIDER-like package (nREPL connection, eval, inspect, overlay results)
- [ ] Inline result display — result next to expression (CIDER overlays style)

### Async & Parallel Execution `🔬 research`

**Decision (locked)**: NO BLOCKING EVER. Stream or wait. Notification system when sample is ready. The unit of the language is a sample — get/transform/save data.

**Decision (locked)**: Rethink traditional parallelism. The language works with samples (batches), so async is the natural model. Everything can be async; parallel where appropriate. No explicit `pmap`/`pfilter` — the runtime decides based on sample size and operation type.

- [ ] Streaming-first: data arrives as stream, never blocks
- [ ] Notification system: "sample ready" events for IDE/REPL
- [ ] Parallel map/filter: automatic parallelization based on sample size + op type (no explicit pmap/pfilter)
- [ ] Pipeline-level parallelism: independent branches execute concurrently
- [ ] Error propagation across async boundaries
- [ ] Backpressure and resource limits (thread pool, connection pool)
- [ ] Integration with JVM virtual threads (Project Loom)

### DataTwist Daemon `🔬 research`

Persistent background server that IDE, CLI, and tools connect to. Eliminates JVM startup cost, holds project state, caches, samples.

#### Design decisions (locked)

- **Two-tier architecture**:
  - **Global daemon** (cross-project): always running, instant REPL access from any project, handles eval requests, doc queries, formatting. One per user.
  - **Project process**: spawned per-project, knows project context — loaded files, connection profiles, sample caches, autodoc type data. Managed by the global daemon.
- **IDE connects to daemon**: no JVM startup per eval. nREPL/LSP talk to the daemon.
- **`datatwist daemon start/stop/status`** CLI subcommands
- **Hot reload**: daemon watches project files, reloads on change
- **Autodoc via daemon**: `fmt --doc` queries the project process for runtime type info from cached samples

#### Tasks

- [ ] Daemon architecture: global daemon + per-project process model
- [ ] `datatwist daemon start` / `stop` / `status` CLI subcommands
- [ ] Socket/port management — daemon listens on Unix socket or TCP
- [ ] Project process lifecycle: spawn on first access, idle timeout, restart
- [ ] nREPL server integration — daemon hosts nREPL endpoint
- [ ] File watcher — hot reload on source changes
- [ ] Sample/cache persistence across daemon restarts
- [ ] `fmt --doc` integration — query daemon for type inference data

---

## P2 — Medium Priority

### String Pattern Destructuring (`#p`) `🔬 research`

**Decision (locked)**: `#p"..."` reader macro for reverse-format string destructuring. Three-tier design:

**Tier 1 — Simple capture:** `{var}` captures between literals (greedy up to next literal, no constraint needed)
```
#p"{user}@{domain}"                                               ; email
#p"{a}.{b}.{c}.{d}"                                               ; IPv4
#p"{ip} - {user} [{time}] \"{method} {url} HTTP/{ver}\" {status} {bytes}"  ; nginx log
```

**Tier 2 — Type hints via `:` shorthand:** `:d` = digits, `:w` = word chars, `:N` = exactly N chars
```
#p"{y:4d}-{m:2d}-{d:2d}"         ; ISO date
#p"{a:d}.{b:d}.{c:d}.{d:d}"      ; digits-only octets
#p"{code:3}-{rest}"               ; exactly 3 chars then rest
```

**Tier 3 — Full constraints for complex logic:**
```
#p"{proto: 'http' maybe 's'}://{host: many (not '/:')}/{path: rest}"
```

**Escaping literal braces:** `{{` → literal `{`, `}}` → literal `}`
```
#p"{{key}}: {value}"    ; matches "{key}: hello" → {value: "hello"}
```

**Integration with existing syntax:**
```
; Guards
input | #p"{name}@{domain}" -> {type: "email", name, domain}
      | #p"{proto}://{host}" -> {type: "url", proto, host}
      | _ -> {type: "unknown"}

; Named patterns via is
date-fmt is #p"{y:4d}-{m:2d}-{d:2d}"
text | date-fmt -> {y, m, d}

; Pipelines
logs |> map (extract _.timestamp date-fmt) |> filter _.m = "01"
```

#### Design decisions (locked)

- `#p` reader macro (not `#pattern` — shorter)
- `{var}` with no constraint captures up to next literal (smart default)
- Short type hints: `:d` (digits), `:w` (word), `:N` (exact N chars)
- Full constraint syntax inside `{}` for complex cases: `many`, `maybe`, `not`, `N..M`, alternation `|`
- `{{` / `}}` for literal brace escaping (standard convention: Python, Rust, C#)
- Patterns are first-class values (can bind with `is`, pass to functions)
- Works in guards — extends existing pattern matching
- Captures become object fields — natural for pipeline processing
- Regex `#"..."` remains as escape hatch for edge cases
- Replaces the previous "Regex Alternatives / Pattern Language" backlog item

#### Implementation tasks

- [ ] Parser support for `#p"..."` reader macro
- [ ] Constraint mini-language parser (inside `{var: ...}`)
- [ ] Compilation to `java.util.regex.Pattern` (via Regal or direct)
- [ ] Integration with guard/pattern matching system
- [ ] `extract` / `match` / `replace` stdlib functions using patterns
- [ ] `{{` / `}}` escaping
- [ ] BDD feature file + tests

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

#### Design decisions (locked)

- **Both expected tokens AND "did you mean?" fuzzy matching** in parse error messages. Not either/or.
- **Warnings are non-blocking**: warnings print but execution continues.
- **`WARNINGS_AS_ERRORS` constant**: set to enable strict mode where warnings fail execution.

- [ ] Error code system: `DT-PXXX` / `DT-TXXX` / `DT-RXXX`
- [ ] Elm/Rust-style messages with source snippets and hints
- [ ] "Did you mean?" fuzzy matching for identifiers and keywords
- [ ] Expected token hints in parse errors
- [ ] Suppress Java/Clojure stack traces from user output
- [ ] Data-aware warnings (nil prevalence, common mistakes) — non-blocking by default
- [ ] `WARNINGS_AS_ERRORS` constant for strict mode
- [ ] JSON error output: `{:code "DT-T001" :message "..." :hint "..." :line N :col N}`
- [ ] Error code registry (`docs/error-codes.md`): catalog of all codes with descriptions, examples, fix suggestions

### Performance & Streaming `⏳ waiting`

- [ ] Benchmark suite
- [ ] Memory profiling
- [ ] Parallel `map`/`filter` via virtual threads

### Credentials & Network Configuration `⏳ waiting`

#### Design decisions (locked)

- **Three namespaces, clearly separated**: `env` for OS environment variables only, `pass` for secrets from password-store, `dtw` for all DataTwist internal config.
- **`env` module**: `env.HOME`, `env.PATH`, `env.DB_URL` — OS environment variables only. No DataTwist settings live here.
- **`pass` module**: `pass.myproject.db-host` maps to `pass show datatwist/myproject/db-host`. Flat structure — one value per key, returns a string. Integrates with the standard Unix `pass` (password-store).
- **Leaf path returns string, directory path returns object**: Accessing a leaf key returns a single string value. Accessing a directory path (non-leaf) returns an object with all keys as fields. Enables standard destructuring.
- **`dtw` module**: all DataTwist internal config — settings/constants (`dtw.SAMPLE_SIZE`, `dtw.POOL_SIZE`), saved connection profiles (`dtw.connections.prod-db`), VPN configurations (`dtw.vpn.prod-vpn`), and proxy configurations (`dtw.proxy.prod-proxy`).

```
; OS environment
env.HOME                    ; => "/home/user"

; Secrets
{db-host, db-pass} is pass.myproject

; DataTwist config
dtw.SAMPLE_SIZE             ; => 10

; Connections with network config
db is connect dtw.connections.prod-db dtw.vpn.prod-vpn
db is connect dtw.connections.prod-db dtw.proxy.prod-proxy
```

- [ ] `pass` module — resolve credentials via `pass show datatwist/<project>/<key>`, flat key/value, returns string
- [ ] `env` module — OS environment variable access only (`env.HOME`, `env.PATH`, `env.DB_URL`)
- [ ] `dtw` module — DataTwist internal config namespace (settings, connection profiles, VPN, proxy)
- [ ] `dtw.connections.*` — saved connection profile storage and resolution
- [ ] `dtw.vpn.*` — VPN configuration profiles (WireGuard, OpenVPN)
- [ ] `dtw.proxy.*` — proxy configuration profiles (SOCKS5, HTTP)
- [ ] `connect` accepts profile + optional network config (VPN or proxy)
- [ ] Environment-based overrides: `DT_PROFILE=work` selects default connection profile
- [ ] Keyring integration: macOS Keychain, Linux secret-service, Windows Credential Manager (lower priority than pass)
- [ ] Design: config format (TOML, EDN, or DataTwist syntax?), encryption strategy (GPG vs OS keyring)

### Docker & Kubernetes Sources `🔬 research`

Query Docker containers and Kubernetes resources as data sources, like database tables.

- [ ] Docker connector: `docker is connect "docker://local"` — list/inspect containers, images, networks
- [ ] `docker |> query "containers" |> filter _.status = "running"`
- [ ] Kubernetes connector: `k8s is connect "k8s://context-name"` — pods, services, deployments, configmaps
- [ ] `k8s |> query "pods" |> filter _.namespace = "production"`
- [ ] Credential storage for Docker registries and K8s contexts via `pass` module
- [ ] Read logs: `docker |> logs "container-name" |> filter (contains _ "ERROR")`
- [ ] Integration with `pass` module for registry/cluster auth

### Documentation System & Autodoc `🔬 research`

Docstrings, inline documentation, and automatic type inference from runtime data.

#### Design decisions (locked)

- **`dtw` is a plain object** (Lua-style): `dtw`, `dtw.connections`, `dtw.proxy` — all readable/writable objects. No special accessor syntax needed.
- **`doc` function**: `doc filter` returns documentation for any function (built-in or user-defined)
- **`@doc` annotation**: attach docstring to user functions: `double is [x -> x * 2] @doc "Doubles a number"`
- **Autodoc**: runtime type inference from samples — observe actual argument types and return types, generate signatures automatically. Not static types — observation of real data.

#### Tasks

- [ ] `@doc "..."` annotation syntax — attach docstring to `is` bindings
- [ ] `doc` function — retrieve documentation for any symbol
- [ ] Built-in function docs — docstrings for all stdlib functions
- [ ] Autodoc: infer argument types from sample data (Number, String, List, Object, Function, etc.)
- [ ] Autodoc: infer return type from evaluation results
- [ ] Autodoc: generate function signatures like `filter : [a -> Bool] -> [a] -> [a]`
- [ ] Autodoc: collect usage examples from REPL/script history
- [ ] Integration with nREPL — `doc` operation returns formatted docs to IDE
- [ ] Integration with LSP — hover documentation, signature hints

---

## P3 — Future

### Reader Macros

**Decision (locked)**: `#` prefix like Clojure for reader macros.

- [ ] `#sample {predicate}` — conditional sampling: filter what data enters sample. Re-samples if predicate doesn't match. `SAMPLE_ATTEMPTS` controls max retries. Can set sample size to zero.
- [ ] `#p"..."` — string pattern destructuring (see P2 item above for full design)
- [ ] Reader macro dispatch system (extensible `#name` syntax)
- [ ] `#dbg`-style debugging support

### HTTP Sources & Web Data

**Decision (preliminary)**: Not a simple `http!` call — needs proper design. Each site/API is unique. Key considerations: JSON responses work natively as data; HTML parsing also needed. Auth varieties (Bearer, Basic, API keys, OAuth) must all be supported. Secrets via ENV/credentials module. No final design yet.

- [ ] HTTP client with auth: Bearer, Basic, API key, OAuth
- [ ] JSON responses work natively as DataTwist data (no explicit parse-json needed)
- [ ] HTML parsing / scraping support
- [ ] Integration with credentials module for secrets (no inline secrets)
- [ ] Response formats: json, html, text, markdown
- [ ] Pipeline integration: `fetch! "api.com/users" |> filter _.active`
- [ ] Design: auth configuration API, session management, retries

### Language Extensions

#### Design decisions (locked)

- **String interpolation**: `#s"Hello {name}"` reader macro. Concern: scope visibility must be clear (which variables are in scope). Multiline strings — undecided yet.

- [x] String interpolation: `#s"Hello {name}"` reader macro — locked design decision (multiline undecided)
- [ ] Multi-line strings / heredocs (`"""..."""`) — undecided
- [ ] Date/time literals and operations
- [ ] Spread operator in objects: `{...base, name: "new"}`
- [ ] Optional type annotations for tooling
- [x] Line comments with `;` (replacing `//`) — locked design decision
- [x] Block comments: `(comment ...)` form — parsed but not evaluated — locked design decision

#### Source-Driven Development: Code as Living Document

The language can modify its own source files — auto-formatting, function ranking, and auto-updating documentation. DataTwist owns its source.

#### Design decisions (locked)

- **Code as living document**: DataTwist owns its source files. `datatwist fmt` is not optional — the canonical format is enforced. Like `gofmt` but going further.
- **Three levels of source modification**:
  1. `datatwist fmt` — opinionated formatting: indentation, pipeline alignment, line width, function ordering. One style, no config.
  2. `datatwist fmt --doc` — run samples through functions, infer types, write `@doc` annotations back into source code. Auto-generated docs from runtime observation.
  3. IDE on-save — format + doc update automatically. File always in canonical form.
- **`@doc` auto-generation**: evaluator runs sample data through each function, observes input/output types, writes signature as `@doc "Number -> Number"` annotation.
- **Formatter rules are future work**: specific rules (line length limits, vector formatting, map indentation, pipeline alignment) will be designed later. The principle is locked: opinionated, one canonical style, zero configuration.

#### Tasks

- [x] Built-in formatter (`datatwist fmt`): opinionated auto-format — locked design decision
- [ ] Formatter: indentation, line width, pipeline alignment, guard alignment
- [ ] Formatter: function ordering/ranking (by dependency? alphabetical?)
- [ ] `datatwist fmt --doc`: auto-generate `@doc` annotations from sample type inference
- [ ] IDE integration: format + doc on save
- [ ] Source file round-trip: parse → AST → format → write back without losing comments or structure

### Cache Management

**Decision (preliminary)**: `invalidate-cache!` command for manual reset. Global `dtw.AUTO_INVALIDATE` setting. Eviction policy details TBD.

- [ ] `invalidate-cache!` — manual cache reset for an expression/pipeline
- [ ] `dtw.AUTO_INVALIDATE` global setting — automatic invalidation on source change
- [ ] Per-expression cache invalidation
- [ ] Pipeline-level invalidation (invalidate end → whole pipeline re-executes)
- [ ] Cache size limits and eviction policy (TBD)

### tap! Output Channels

**Decision (locked)**: `tap!` output is redirectable like Clojure's `*out*`.

- [ ] Default: console/REPL
- [ ] `with-tap-out "file.log" [-> pipeline]` — redirect to file
- [ ] IDE channel: send to overlay instead of console
- [ ] Custom: `dtw/set! TAP_OUT [data -> send-to-slack data]`

### Editor Quick Wins

- [ ] Bi-directional traversal through pipeline steps (forward/backward with cached data)
- [ ] Sub-step introspection: inspect steps within steps (nested pipelines)
- [ ] Notebook mode — scratch buffer with persistent results (like Org-babel)
- [ ] Data visualization in REPL — tables, charts for collection data
