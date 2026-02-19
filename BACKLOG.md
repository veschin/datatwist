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

- [ ] Namespace resolution (file-based, classpath, registry)
- [ ] `require` with qualified access and selective import
- [ ] Namespace caching, circular dependency detection
- [ ] Connector interface: `connect`, `query`, `close!`, `tables`, `schema`
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

- **Separate module** for credentials, not bundled into connectors.
- **`pass` integration**: uses `pass` (password-store) as the credential backend. DataTwist namespace per project in the pass store (e.g. `datatwist/myproject/db-password`).
- **ENV module integration**: works alongside the ENV module for environment variable access. Secrets resolved from pass; non-secret config from ENV.

- [ ] `pass` integration — resolve credentials via password-store with `datatwist/<project>/<key>` namespace convention
- [ ] ENV module — environment variable access, works alongside `pass`
- [ ] Connection profiles: named configs (`work-db`, `prod-api`) with host, port, auth, proxy settings
- [ ] Proxy/VPN-aware connections: per-profile SOCKS5/HTTP proxy
- [ ] `connect` function uses profiles: resolves creds + proxy automatically
- [ ] Environment-based overrides: `DT_PROFILE=work` selects default connection profile
- [ ] Keyring integration: macOS Keychain, Linux secret-service, Windows Credential Manager (lower priority than pass)
- [ ] Design: config format (TOML, EDN, or DataTwist syntax?), encryption strategy (GPG vs OS keyring)

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
- [ ] Built-in formatter (`datatwist fmt`): opinionated auto-format (indentation, line width, pipeline alignment, guard alignment)

### Cache Management

**Decision (preliminary)**: `invalidate-cache!` command for manual reset. Global `AUTO_INVALIDATE` setting. Eviction policy details TBD.

- [ ] `invalidate-cache!` — manual cache reset for an expression/pipeline
- [ ] `AUTO_INVALIDATE` global setting — automatic invalidation on source change
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
