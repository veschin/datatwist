# DataTwist Backlog

Status legend: `🔬 research` `📝 bdd/tests` `🚧 in progress` `⏳ waiting` (blocked/needs user decision)

---

## P0 — Critical Path

### GraalVM Native Image Build `🔬 research`

Build DataTwist as a standalone native binary via GraalVM `native-image`. Critical for distribution.

- [ ] Create CLI entry point (`-main` with `:gen-class`)
- [ ] defparser macro for AOT grammar compilation
- [ ] Add GraalVM native-image build config (reflection config, resource config)
- [ ] Add `graal-build-time` dependency
- [ ] `build.clj` with tools.build
- [ ] Add `make native` and `make uberjar` build targets
- [ ] CI pipeline for building linux/macos binaries
- [ ] Investigate startup time — target <50ms
- [ ] Ship uberjar as fallback
- [ ] Handle Java interop scope for native mode

---

## P1 — High Priority

### Lazy Evaluation `🚧 in progress`

Design locked. BDD and test stubs exist; evaluator support not yet implemented.

BDD: `bdd/8-lazy-evaluation.feature`, Tests: `test/datatwist/lazy_eval_test.clj` (76 TDD stubs)
Design doc: `docs/lazy-eval-design.md`

Note: basic lazy sequences (map, filter, take, drop, distinct, flatten, concat, range, repeat) are done. The tasks below are the remaining evaluator-level work.

- [ ] Streaming pipelines with backpressure
- [ ] Short-circuit evaluation in guards and boolean ops
- [ ] `take`/`drop` on infinite sequences

#### Design decisions (locked)

- **Laziness is invisible to user**: lazy seqs auto-materialize at any user-facing boundary (REPL, str, save!, tap!, `=`). User NEVER sees `LazySeq@...`. Laziness only exists between pipeline steps for performance.
- **`force!`** is the only explicit materialization function (not `collect`).
- **`tap!`** is fully safe, never mutates data. Three modes: bare (`tap!`), labeled (`tap! "label"`), lambda (`tap! [d -> format "found %s items" (count d)]`). Output format: first line is function label `[fn]`, second line is sample data.
- **`autotap!`** is a macro-like transformation: inserts `tap!` between each pipeline step. Implementation: runtime transformation at the pipeline level. Research done → `docs/autotap-impl-plan.md`.
- **Reified pipeline**: `|>` builds a `DTPipeline` record that remembers steps and caches samples per step.
- **Full introspection**: cursor on ANY expression → sample result. Cursor on ANY variable → sample of that variable's values. On-demand, cached — no re-execution.
- **Conditional sampling**: `#sample {predicate}` reader macro — filter sample data. `SAMPLE_ATTEMPTS` controls retries. Can set sample size to zero.
- **Bi-directional traversal**: step forward/backward through pipeline computations, drill into collections.
- **Cache management**: everything cached by default. `invalidate-cache!` for explicit reset. Cache at end of pipeline → whole pipeline re-executes.
- **No blocking ever**: streaming-first; notification when sample is ready for inspection.
- **Auto-materialize contexts**: REPL output, `str`/concat, `tap!`, `save!`, `=` comparison, error messages — all auto-force with configurable limit.

#### autotap! `📝 bdd/tests`

Research done → `docs/autotap-impl-plan.md`. Ready for BDD and implementation.

- [ ] BDD scenarios for `autotap!` in `bdd/8-lazy-evaluation.feature`
- [ ] Tests in `test/datatwist/lazy_eval_test.clj`
- [ ] Evaluator support: runtime pipeline transformation inserting `tap!` between each step

#### DTPipeline Reified Pipeline `📝 bdd/tests`

Research done → `docs/dtpipeline-impl-plan.md`. Ready for BDD and implementation.

- [ ] `DTPipeline` record type — reified pipeline with steps and metadata
- [ ] `dtw/pipeline` and `dtw/step` compiler targets for `|>` operator
- [ ] `dtw/inspect pipeline step sample-size` — inspect a specific pipeline step
- [ ] `inspect-pipeline-step` nREPL op: `{:op "inspect-pipeline-step" :file "..." :line N :step-index K}` → cached sample
- [ ] IDE overlay integration — sample result per `|>` step
- [ ] BDD scenarios and tests

### Pushdown Optimization `⏳ waiting`

Blocked on DTPipeline implementation. Design doc: `docs/pushdown-design.md`.

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

#### Design decisions (locked)

- **Clojure-style namespaces + require**:
  ```
  require math              // everything under math namespace
  require math.{sin cos}    // specific functions
  math/sin 3.14             // qualified access
  ```
- **Connector interface** (preliminary): minimal 5 functions — `connect`, `query`, `close!`, `tables`, `schema`. Not ready to go deeper yet.
- **Connection pool** (preliminary): `connect` creates a connection pool internally (HikariCP). User doesn't manage the pool. Default pool size configurable via `dtw.POOL_SIZE` (default: 5).

Tasks:
- [ ] Namespace resolution (file-based, classpath, registry)
- [ ] `require` with qualified access and selective import
- [ ] Namespace caching, circular dependency detection
- [ ] Connector interface: `connect`, `query`, `close!`, `tables`, `schema`
- [ ] Built-in connectors: postgres, sqlite, http, fs, csv
- [ ] Connector + Pushdown integration
- [ ] Connection pool management (HikariCP) — transparent, configurable via `dtw.POOL_SIZE`

### Data Sources `📝 bdd/tests`

Research done → `docs/data-sources-plan.md`. File and database connectors are specified; BDD and implementation remain.

#### File sources

- [ ] `read-csv "path"` — lazy CSV reader
- [ ] `read-json "path"` — lazy JSON reader
- [ ] `read-jsonl "path"` — lazy newline-delimited JSON reader
- [ ] `read-lines "path"` — lazy line reader
- [ ] `read-parquet "path"` — lazy Parquet reader
- [ ] `save! collection "path"` — write collection to file (format inferred from extension)

#### Database sources

- [ ] `connect <profile>` — open database connection (returns connection object)
- [ ] `query conn "sql"` — execute SQL, return lazy result set
- [ ] `close! conn` — release connection/pool
- [ ] `tables conn` — list tables
- [ ] `schema conn "table"` — infer schema from table
- [ ] `into! conn "table" collection` — bulk insert

### nREPL & Editor Integration `🔬 research`

Target: CIDER-like experience for DataTwist. Plugins live in `plugins/` (future separate repos).

#### Design decisions (locked)

- **Reference implementation**: CIDER is the reference. Emacs-first.
- **MVP scope**: eval sub-expression at cursor + inspect drill-down (like CIDER inspector). Nothing more for MVP.

Tasks:
- [ ] nREPL server — middleware for DataTwist eval on top of Clojure nREPL
- [ ] Eval sub-expression — parse sub-expr at cursor position, eval in current context
- [ ] Inspector — drill-down into nested objects/lists (like CIDER inspector)
- [ ] `datatwist-mode` for Emacs — CIDER-like package (nREPL connection, eval, inspect, overlay results)
- [ ] Inline result display — result next to expression (CIDER overlays style)
- [ ] `inspect-pipeline-step` nREPL op — request: `{:op "inspect-pipeline-step" :file :line :step-index}`, response: cached sample

### Async & Parallel Execution `🔬 research`

#### Design decisions (locked)

- **NO BLOCKING EVER**: stream or wait; notification system when sample is ready. The unit of the language is a sample — get/transform/save data.
- **Rethink traditional parallelism**: the language works with samples (batches), so async is the natural model. No explicit `pmap`/`pfilter` — the runtime decides based on sample size and operation type.

Tasks:
- [ ] Streaming-first: data arrives as stream, never blocks
- [ ] Notification system: "sample ready" events for IDE/REPL
- [ ] Parallel map/filter: automatic parallelization based on sample size + op type
- [ ] Pipeline-level parallelism: independent branches execute concurrently
- [ ] Error propagation across async boundaries
- [ ] Backpressure and resource limits (thread pool, connection pool)
- [ ] Integration with JVM virtual threads (Project Loom)

### DataTwist Daemon `🔬 research`

Persistent background server that IDE, CLI, and tools connect to. Eliminates JVM startup cost, holds project state, caches, samples.

#### Architecture questions to investigate

- **Pro**: shared state (sample caches, connection pools, autodoc type data) persists between invocations
- **Pro**: IDE connects to live process with data context
- **Pro**: hot reload on file changes
- **Con**: another process to manage (start/stop/crash recovery)
- **Con**: GraalVM binary may already be fast enough — do we really need persistent state?
- **Question**: can we get the same benefits with a simpler model? (e.g. cache files on disk, lazy daemon that starts on first IDE connection)

#### Preliminary design (needs validation)

- **Two-tier architecture**: global daemon (cross-project, always running) + per-project process (spawned per-project, knows project context — loaded files, connection profiles, sample caches).
- **`datatwist daemon start/stop/status`** CLI subcommands
- **Hot reload**: daemon watches project files, reloads on change

Tasks:
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

### CLI Subcommands & TUI `🔬 research`

- [ ] CLI subcommands: `datatwist run`, `datatwist repl`, `datatwist eval`, `datatwist fmt`, `datatwist lint`, `datatwist test`, `datatwist webui`
- [ ] `datatwist creds add <project> <key>` — add credential to pass store (`datatwist/<project>/<key>`)
- [ ] `datatwist creds list [project]` — list credentials
- [ ] `datatwist creds remove <project> <key>` — remove credential
- [ ] TUI framework (charmbracelet/gum style) — visual interactive prompts, selection, spinners
- [ ] Platform-specific feature matrix: which subcommands available on linux/macos/windows
- [ ] `datatwist connect` — interactive TUI wizard for setting up data sources
- [ ] Source type registry: each connector declares its required fields (host, port, db, auth, etc.)
- [ ] Connection testing: verify connectivity before saving profile
- [ ] Profile storage: save/load connection profiles (`~/.datatwist/connections/` or project-local — TBD)
- [ ] `datatwist connect list` — show saved profiles
- [ ] `datatwist connect test <profile>` — re-test a saved connection
- [ ] File source wizard: interactive file picker for CSV/JSON/Parquet, detect format and schema
- [ ] `connect "profile-name"` in scripts — resolve saved profile, return connection object

### Benchmarking & Analytics System `🔬 research`

Note: `describe`, `schema`, `freq`, `histogram`, `sample`, `explain` are already done. The remaining items are the performance measurement tools.

- [ ] `measure!` — time execution of expression or pipeline, return `{result time-ms}`
- [ ] `profile!` — detailed breakdown of pipeline: time per step, rows per step, memory per step
- [ ] `compare!` — A/B benchmark: run two expressions N times, report mean/median/p99 with statistical significance
- [ ] `benchmark!` — run expression N times, report min/max/mean/stddev/percentiles
- [ ] Volume analytics: data sizes in bytes/KB/MB, row counts, column counts
- [ ] Memory profiling: track allocations per pipeline step
- [ ] Output formats: table (REPL), JSON (programmatic), chart (IDE)
- [ ] Integration with `tap!` — benchmark results as tap output

### String Pattern Destructuring (`#p`) `📝 bdd/tests`

Design locked. Tier 1 (simple capture), brace escaping, wildcard `{_}`, and guard integration are done. Tier 2 and Tier 3 remain.

Tier 2 research → `docs/pattern-phase2-plan.md`.

#### Design decisions (locked)

- `#p` reader macro (not `#pattern` — shorter)
- `{var}` with no constraint captures up to next literal (smart default)
- Short type hints: `:d` (digits), `:w` (word), `:N` (exact N chars)
- Full constraint syntax inside `{}` for complex cases: `many`, `maybe`, `not`, `N..M`, alternation `|`
- `{{` / `}}` for literal brace escaping (standard: Python, Rust, C#) — done
- Patterns are first-class values (can bind with `is`, pass to functions)
- Works in guards — extends existing pattern matching — done
- Captures become object fields — natural for pipeline processing
- Regex `#"..."` remains as escape hatch for edge cases

#### Tier 2 — Type hints `📝 bdd/tests`

Research done. Ready for BDD and implementation.

```
#p"{y:4d}-{m:2d}-{d:2d}"         ; ISO date
#p"{a:d}.{b:d}.{c:d}.{d:d}"      ; digits-only octets
#p"{code:3}-{rest}"               ; exactly 3 chars then rest
```

- [ ] Parser support for `:d`, `:w`, `:N` type hint syntax inside `{var:hint}`
- [ ] Compilation of type hints to regex character classes / quantifiers
- [ ] BDD scenarios and tests for Tier 2

#### Tier 3 — Full constraint mini-language `🔬 research`

```
#p"{proto: 'http' maybe 's'}://{host: many (not '/:')}/{path: rest}"
```

- [ ] Design the constraint mini-language grammar
- [ ] Parser support for `many`, `maybe`, `not`, `N..M`, alternation `|`
- [ ] Compilation to `java.util.regex.Pattern` (via Regal or direct)
- [ ] BDD scenarios and tests for Tier 3

### Error Reporting `🚧 in progress`

BDD: `bdd/9-error-reporting.feature`, Tests: `test/datatwist/error_reporting_test.clj` (42 TDD stubs)
Research doc: `docs/error-reporting-research.md`

Done: error code system (DT-PXXX/TXXX/RXXX/DXXX/CXXX), Elm/Rust-style messages with source snippets and hints, "did you mean?" fuzzy matching, `WARNINGS_AS_ERRORS` constant.

Remaining:

- [ ] Expected token hints in parse errors (what the parser expected at the failure point)
- [ ] Suppress Java/Clojure stack traces from user output
- [ ] Data-aware warnings (nil prevalence, common mistakes) — non-blocking by default
- [ ] JSON error output: `{:code "DT-T001" :message "..." :hint "..." :line N :col N}`
- [ ] Error code registry (`docs/error-codes.md`): catalog of all codes with descriptions, examples, fix suggestions

#### Design decisions (locked)

- **Both expected tokens AND "did you mean?" fuzzy matching** in parse error messages.
- **Warnings are non-blocking**: warnings print but execution continues.
- **`WARNINGS_AS_ERRORS` constant**: set to enable strict mode where warnings fail execution.

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

### Credentials & Network Configuration `⏳ waiting`

#### Design decisions (locked)

- **Three namespaces, clearly separated**: `env` for OS environment variables only, `pass` for secrets from password-store, `dtw` for all DataTwist internal config.
- **`env` module**: `env.HOME`, `env.PATH`, `env.DB_URL` — OS environment variables only.
- **`pass` module**: `pass.myproject.db-host` maps to `pass show datatwist/myproject/db-host`. Flat structure — one value per key, returns a string.
- **Leaf path returns string, directory path returns object**: accessing a non-leaf path returns an object with all keys as fields.
- **`dtw` module**: all DataTwist internal config — settings (`dtw.SAMPLE_SIZE`, `dtw.POOL_SIZE`), saved connection profiles (`dtw.connections.prod-db`), VPN configs (`dtw.vpn.prod-vpn`), proxy configs (`dtw.proxy.prod-proxy`).

```
env.HOME                    ; => "/home/user"
{db-host, db-pass} is pass.myproject
dtw.SAMPLE_SIZE             ; => 10
db is connect dtw.connections.prod-db dtw.vpn.prod-vpn
```

Tasks:
- [ ] `pass` module — resolve credentials via `pass show datatwist/<project>/<key>`, returns string
- [ ] `env` module — OS environment variable access only
- [ ] `dtw` module — DataTwist internal config namespace (settings, connection profiles, VPN, proxy)
- [ ] `dtw.connections.*` — saved connection profile storage and resolution
- [ ] `dtw.vpn.*` — VPN configuration profiles
- [ ] `dtw.proxy.*` — proxy configuration profiles (SOCKS5, HTTP)
- [ ] `connect` accepts profile + optional network config (VPN or proxy)

### Network Tunneling `🔬 research`

Per-connection VPN/proxy routing at the language level. Any `connect` can route through a tunnel profile.

#### Design decisions (locked)

- All tunnels reduce to SOCKS5 proxy on localhost. DataTwist manages tunnel lifecycle (spawn on first use, share across connections with same profile, teardown on last `close!` or script exit).
- Syntax: `connect <profile> <tunnel-profile>` — tunnel is the optional second argument.

```
db is connect dtw.connections.prod-db dtw.vpn.work
api is connect dtw.connections.api dtw.proxy.corp-socks
local is connect dtw.connections.local-db
```

| Type | Mechanism | Root? | Complexity |
|---|---|---|---|
| SOCKS5/HTTP proxy | JVM native `java.net.Proxy` per-socket | No | Trivial |
| SSH tunnel | `ssh -D <port>` → SOCKS5 | No | Simple |
| OpenConnect | `openconnect --script-tun --script "ocproxy -D <port>"` → SOCKS5 | Maybe | Medium |
| WireGuard | `wireguard-go` + `tunsocks` → SOCKS5 | No | Hard |
| OpenVPN | `openvpn` + `tunsocks` → SOCKS5 | Maybe | Hard |

Research questions:
- [ ] ocproxy / tunsocks feasibility — JVM integration, lifecycle management, error handling
- [ ] Credential flow for interactive auth (TOTP, MFA) — `pass` integration, interactive TUI prompt, or callback
- [ ] Connection pool interaction — does HikariCP support per-connection SOCKS proxy?
- [ ] Platform support — Linux primary, macOS secondary, Windows TBD
- [ ] Tunnel health checks and reconnection strategy
- [ ] Security: tunnel process isolation, credential exposure surface
- [ ] Environment-based overrides: `DT_PROFILE=work` selects default connection profile
- [ ] Keyring integration: macOS Keychain, Linux secret-service (lower priority than pass)
- [ ] Config format decision (TOML, EDN, or DataTwist syntax?), encryption strategy (GPG vs OS keyring)

### Configuration System `🔬 research`

How DataTwist settings, project config, and runtime options are defined, stored, loaded, and overridden.

Note: `config.clj`, `set!` special form, `dtw.*` sentinel, `get-config`, and runtime constants (`SAMPLE_SIZE`, `MAX_COLLECT_ROWS`, `DESCRIBE_SAMPLE_SIZE`, `PRINT_WIDTH`) are done. The remaining work is file-based config, layering, and project discovery.

#### Questions to resolve `⏳ waiting`

- Where does config live? `~/.datatwist/config.dt` (global), `.datatwist/config.dt` (project), or both with merge?
- Config file format: DataTwist syntax (`.dt`), EDN, TOML?
- Layering/priority: CLI flags > env vars > project config > global config > defaults?
- How does `dtw.*` namespace load its values at startup?
- Project discovery: how does DataTwist know which project it's in? (`.datatwist/` directory marker?)
- Runtime mutability via `set! dtw.KEY` — persisted or session-only?
- Validation: schema for config values? Type checking? Error on unknown keys?

Tasks:
- [ ] Config file format and location design
- [ ] Config layering: defaults → global → project → env vars → CLI flags
- [ ] `dtw.*` namespace backed by config system — reads from config files at startup
- [ ] `set! dtw.KEY --persist` or `datatwist config set KEY VALUE` — write back to config file
- [ ] Project discovery: `.datatwist/` directory as project root marker
- [ ] `datatwist init` CLI command — create `.datatwist/` with default config
- [ ] `datatwist config list` — show effective config with source (default/global/project/env/cli)
- [ ] Config schema + validation: known keys, types, ranges
- [ ] Integration with `pass` and `env` modules

### Docker & Kubernetes Sources `🔬 research`

Query Docker containers and Kubernetes resources as data sources.

- [ ] Docker connector: `docker is connect "docker://local"` — list/inspect containers, images, networks
- [ ] `docker |> query "containers" |> filter _.status = "running"`
- [ ] Kubernetes connector: `k8s is connect "k8s://context-name"` — pods, services, deployments, configmaps
- [ ] `k8s |> query "pods" |> filter _.namespace = "production"`
- [ ] Credential storage for Docker registries and K8s contexts via `pass` module
- [ ] Read logs: `docker |> logs "container-name" |> filter (contains _ "ERROR")`

### Documentation System & Autodoc `🔬 research`

#### Design decisions (locked)

- **`dtw` is a plain object** (Lua-style): `dtw`, `dtw.connections`, `dtw.proxy` — all readable/writable objects.
- **`doc` function**: `doc filter` returns documentation for any function (built-in or user-defined).
- **`@doc` annotation**: attach docstring to user functions: `double is [x -> x * 2] @doc "Doubles a number"`.
- **Autodoc**: runtime type inference from samples — observe actual argument types and return types, generate signatures automatically. Not static types — observation of real data.

Tasks:
- [ ] `@doc "..."` annotation syntax — attach docstring to `is` bindings
- [ ] `doc` function — retrieve documentation for any symbol
- [ ] Built-in function docs — docstrings for all stdlib functions
- [ ] Autodoc: infer argument types from sample data (Number, String, List, Object, Function, etc.)
- [ ] Autodoc: infer return type from evaluation results
- [ ] Autodoc: generate function signatures like `filter : [a -> Bool] -> [a] -> [a]`
- [ ] Autodoc: collect usage examples from REPL/script history
- [ ] Integration with nREPL — `doc` operation returns formatted docs to IDE
- [ ] Integration with LSP — hover documentation, signature hints

### Standard Library Gaps `⏳ waiting`

Functions specified in PRD but not yet implemented. Needs user decisions on some semantics.

- [ ] `fill-nil` — fill nil values with a default
- [ ] `skip-nil` — remove nil entries from collections
- [ ] `coerce` — type coercion function
- [ ] `join` / `left-join` / `inner-join` / `outer-join` — multi-source join operations
- [ ] `define` — user-defined function declaration (PRD stdlib section)

Pending questions: exact semantics of `coerce` (target types? error on failure?), join key syntax, `define` vs `is [fn -> ...]` distinction.

### Lazy Range `⏳ waiting`

`range-from N` for infinite sequences starting at N. Research → `docs/range-semantics-decision.md`.

Pending question: should `range-from N` produce an infinite lazy sequence (no end), or should it require an explicit `take`? What is the syntax — `range-from 1`, `range 1 ..`, or something else?

---

## P3 — Future

### Reader Macros

#### Design decisions (locked)

- `#` prefix like Clojure for reader macros.

Tasks:
- [ ] `#sample {predicate}` — conditional sampling: filter what data enters sample. Re-samples if predicate doesn't match. `SAMPLE_ATTEMPTS` controls max retries.
- [ ] Reader macro dispatch system (extensible `#name` syntax)
- [ ] `#dbg`-style debugging support

### HTTP Sources & Web Data `🔬 research`

Not a simple `http!` call — needs proper design. Key considerations: JSON responses work natively as data; HTML parsing also needed. Auth varieties (Bearer, Basic, API keys, OAuth) must all be supported. Secrets via credentials module.

- [ ] HTTP client with auth: Bearer, Basic, API key, OAuth
- [ ] JSON responses work natively as DataTwist data (no explicit parse-json needed)
- [ ] HTML parsing / scraping support
- [ ] Integration with credentials module for secrets (no inline secrets)
- [ ] Response formats: json, html, text, markdown
- [ ] Pipeline integration: `fetch! "api.com/users" |> filter _.active`
- [ ] Auth configuration API, session management, retries

### Language Extensions

#### Design decisions (locked)

- **String interpolation**: `#s"Hello {name}"` reader macro. Concern: scope visibility must be clear.
- **Multiline strings**: undecided.
- **Formatter**: opinionated, one canonical style, zero configuration (like `gofmt`). Rules are future work.

Tasks:
- [ ] String interpolation: `#s"Hello {name}"` reader macro — needs BDD, tests, implementation
- [ ] Multi-line strings / heredocs (`"""..."""`) — needs PRD design decision
- [ ] Date/time literals and operations
- [ ] Spread operator in objects: `{...base name: "new"}` — needs PRD design decision
- [ ] Optional type annotations for tooling
- [ ] Formatter: indentation, line width, pipeline alignment, guard alignment
- [ ] Formatter: function ordering/ranking (by dependency? alphabetical?)
- [ ] `datatwist fmt --doc`: auto-generate `@doc` annotations from sample type inference
- [ ] IDE integration: format + doc on save
- [ ] Source file round-trip: parse → AST → format → write back without losing comments or structure

### Cache Management

#### Design decisions (preliminary)

- `invalidate-cache!` command for manual reset. Global `dtw.AUTO_INVALIDATE` setting. Eviction policy details TBD.

Tasks:
- [ ] `invalidate-cache!` — manual cache reset for an expression/pipeline
- [ ] `dtw.AUTO_INVALIDATE` global setting — automatic invalidation on source change
- [ ] Per-expression cache invalidation
- [ ] Pipeline-level invalidation (invalidate end → whole pipeline re-executes)
- [ ] Cache size limits and eviction policy (TBD)

### tap! Output Channels

#### Design decisions (locked)

- `tap!` output is redirectable like Clojure's `*out*`.

Tasks:
- [ ] Default: console/REPL
- [ ] `with-tap-out "file.log" [-> pipeline]` — redirect to file
- [ ] IDE channel: send to overlay instead of console
- [ ] Custom: `set! dtw.TAP_OUT [data -> send-to-slack data]`

### Editor Quick Wins

- [ ] Bi-directional traversal through pipeline steps (forward/backward with cached data)
- [ ] Sub-step introspection: inspect steps within steps (nested pipelines)
- [ ] Notebook mode — scratch buffer with persistent results (like Org-babel)
- [ ] Data visualization in REPL — tables, charts for collection data
