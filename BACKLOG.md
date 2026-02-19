# DataTwist Backlog

## Current — Git History Cleanup `NOT STARTED`

### Background

The repo has two distinct eras:

**Old era (Oct 26 – Feb 18, 2026-02-18 14:16)** — 25 commits from `cf4ec52` through `4639850`.
This era started with a different language concept ("DataFlow", not DataTwist), used a completely
different grammar approach (no Instaparse, different syntax with `let/in`, `if/then/else`, commas
in function calls, `zen.org` philosophy). Files long since deleted: `design.md`, `.llm_libs/`,
`AGENTS.md`, `zen.org`, `tasks/syntax-refactor.md`, `test_resources/`, old grammar tests
(`grammar_tests.clj`, `comment_tests.clj`, `structure_tests.clj`), old test runner. Commit `da66433`
("Remove old docs, design files, examples and obsolete tests") was the explicit break point.

**New era (Feb 18, 2026-02-18 14:48 onward)** — 18 commits from `a3bdce5` through `d2caaa0` (HEAD).
Starts with `a3bdce5` ("Add PRD specification and BDD feature files"), the commit that introduced the
authoritative PRD.md and all 9 BDD feature files. Everything from `a3bdce5` onward is PRD-driven,
coherent work with a single grammar and Instaparse-based evaluator.

### Commit inventory

| Hash | Date | Message | Era |
|---|---|---|---|
| `cf4ec52` | 2025-10-26 | initial commit (design.md only, DataFlow concept) | OLD |
| `160374c` | 2025-10-28 | Fix function syntax and improve expression precedence | OLD |
| `759d8be` | 2025-10-28 | Major progress on pipeline parsing and keyword handling | OLD |
| `3072ff7` | 2025-10-28 | Fix major grammar issues: pattern matching, try-catch... | OLD |
| `b0a5cce` | 2025-10-28 | Fix pipeline syntax to align with zen.org specification | OLD |
| `d1453b8` | 2025-10-28 | Fix pattern matching and improve pipeline parsing | OLD |
| `923f136` | 2025-10-28 | Add multiline filter and object support | OLD |
| `6b7170b` | 2025-10-28 | Update tests for new grammar structure and achieve main goal | OLD |
| `c3e6161` | 2025-10-28 | Fix major grammar issues: pipelines, try-catch, pattern matching | OLD |
| `d7f0a9a` | 2025-10-28 | Remove invalid tests that don't follow zen philosophy | OLD |
| `f24debe` | 2025-10-28 | Fix boolean and function parsing issues | OLD |
| `da73840` | 2025-10-28 | Fix function calls ambiguity and multiple statements | OLD |
| `da964fe` | 2025-10-28 | Fix multi-line objects in indented-pipelines and zen pipelines | OLD |
| `86c82f1` | 2025-10-28 | Phase 1 optimization: Critical performance improvements | OLD |
| `8dd5c13` | 2025-10-28 | Phase 2 cleanup: Remove redundancy and inconsistencies | OLD |
| `3bdb213` | 2025-10-28 | Phase 3 unification: Complete pipeline operation consolidation | OLD |
| `8f37170` | 2025-10-28 | Phase 4: Complete identifier unification | OLD |
| `a6f7865` | 2025-10-28 | Fix literal/identifier separation | OLD |
| `73f8c95` | 2025-10-28 | Revert to user-identifier approach for pipeline parsing | OLD |
| `5b2d061` | 2025-10-28 | Fix pipeline parsing structure and test expectations | OLD |
| `623d1b1` | 2025-10-30 | Reorganize project structure and enhance DataTwist grammar | OLD |
| `25b2ac3` | 2025-10-30 | Move parser to src/datatwist/parser.clj and add pre-commit hook | OLD |
| `a8b5101` | 2025-10-30 | Test pre-commit hook with error | OLD |
| `43984b0` | 2025-10-30 | Test pre-commit hook with failing test | OLD |
| `e46d3b5` | 2025-10-30 | Remove temporary failing test and finalize pre-commit hook setup | OLD |
| `273c87b` | 2025-10-30 | Refactor pipeline operations to use universal identifiers | OLD |
| `39be2eb` | 2025-10-30 | Refactor grammar: simplify identifiers, numbers, strings... | OLD |
| `73c691e` | 2025-10-30 | Fix Phase 1 test expectations to match actual grammar structure | OLD |
| `e83c0f7` | 2025-10-30 | Phase 2: Implement direct operator nodes | OLD |
| `158fee1` | 2025-10-30 | Phase 2 Complete: Pipeline structure simplification | OLD |
| `896b048` | 2025-10-31 | Phase 3: Final grammar simplification complete | OLD |
| `c863cec` | 2025-10-31 | Fix parenthesized function call syntax - remove commas | OLD |
| `4639850` | 2026-02-18 | Add comprehensive language core tests (TDD) matching all 6 BDD specs | OLD (pre-PRD) |
| `da66433` | 2026-02-18 | Remove old docs, design files, examples and obsolete tests | TRANSITION |
| --- | --- | --- | --- |
| `a3bdce5` | 2026-02-18 | **Add PRD specification and BDD feature files** | NEW ERA START |
| `00bd3f9` | 2026-02-18 | Add CLAUDE.md project instructions and simplify Makefile | NEW |
| `6851df1` | 2026-02-18 | Replace grammar with Instaparse prototype, implement parse-error? | NEW |
| `e304e9f` | 2026-02-18 | Add comprehensive parser tests (TDD) — 68 tests, 440 assertions | NEW |
| `bef2ce3` | 2026-02-19 | Fix 7 grammar issues: List/FnCall disambiguation... | NEW |
| `a15f419` | 2026-02-19 | Refactor parser tests to structural AST assertions | NEW |
| `e0264f1` | 2026-02-19 | Update CLAUDE.md: fix whitespace docs, add current status | NEW |
| `195ca16` | 2026-02-19 | Fix greedy binding bug, clean AST of anti-collapse hacks | NEW |
| `fd5325e` | 2026-02-19 | Implement tree-walking evaluator — 407 tests, 4 failures, 14 errors | NEW |
| `e45bcd1` | 2026-02-19 | Remove obsolete IMPLEMENTATION.md, update BACKLOG | NEW |
| `656e0e7` | 2026-02-19 | Fix evaluator bugs, add BDD/tests for features 7-9, design docs | NEW |
| `484f6e0` | 2026-02-19 | Nil coercion in comparisons: nil → 0 (numbers), "" (strings) | NEW |
| `a8ff90a` | 2026-02-19 | Three-valued nil in comparisons: nil > x = nil (unknown) | NEW |
| `19b9f92` | 2026-02-19 | Grammar fixes: negative numbers in lists/args, regex literals | NEW |
| `2d31233` | 2026-02-19 | Error reporting: undefined identifiers, type error codes... | NEW |
| `d2caaa0` | 2026-02-19 | Docs: error reporting research, backlog update, orchestration rules | NEW (HEAD) |

### Branch inventory

| Branch | Tip | Status |
|---|---|---|
| `master` | `cf4ec52` | Initial commit only — same as root. Safe to delete. |
| `refactor-grammar-phase2` | `c863cec` | Old-era experiment branch. Diverges from `e46d3b5`, adds 7 commits not on `main`. All work superseded. Safe to delete. |
| `fix-remaining-errors` | `e46d3b5` | Points to the old-era tip (same as `main`). Nothing unique. Safe to delete. |
| `main` | `e46d3b5` | Currently at old-era tip. Will become the squashed root after cleanup. |
| `grammar-rewrite` | `d2caaa0` (HEAD) | Active branch with all new-era commits. The branch to keep and rebase. |

### Proposed squash strategy

**Goal:** Replace the 34 old-era commits (`cf4ec52` through `da66433`) with a single "Legacy foundation" commit, then keep the 16 new-era commits (`a3bdce5` through `d2caaa0`) verbatim. The resulting history on `grammar-rewrite` will have 17 commits total.

**Method:** Orphan-branch approach (preferred over interactive rebase for large squashes).

```
Step 1: Create a backup tag before touching anything
  git tag backup/pre-squash-grammar-rewrite d2caaa0
  git tag backup/pre-squash-main e46d3b5

Step 2: Create the squashed "foundation" commit from tree state at da66433
  git checkout --orphan clean-history
  git reset --hard da66433
  git commit --allow-empty -m "Legacy foundation: pre-PRD grammar experiments (Oct–Feb 2026)

  Squashed 34 commits (cf4ec52..da66433) into one. This era explored
  the original 'DataFlow' language concept, built an early Instaparse
  grammar, and established the project structure. All code from this
  era has since been replaced or deleted. Kept for provenance only.

  Original range: cf4ec52 (2025-10-26) through da66433 (2026-02-18)"

Step 3: Cherry-pick the 16 new-era commits onto the clean root
  git cherry-pick a3bdce5 00bd3f9 6851df1 e304e9f bef2ce3 a15f419 \
    e0264f1 195ca16 fd5325e e45bcd1 656e0e7 484f6e0 a8ff90a 19b9f92 \
    2d31233 d2caaa0

Step 4: Point grammar-rewrite to the new clean tip
  git checkout grammar-rewrite
  git reset --hard clean-history

Step 5: Delete the temporary branch
  git branch -d clean-history

Step 6: Force-update main to the squashed foundation commit
  (use the hash of the squashed commit from Step 2)
  git checkout main
  git reset --hard <squashed-foundation-hash>

Step 7: Delete stale branches
  git branch -d master
  git branch -d refactor-grammar-phase2
  git branch -d fix-remaining-errors

Step 8: Force-push everything to origin
  git push --force-with-lease origin main
  git push --force-with-lease origin grammar-rewrite
  git push origin :master :refactor-grammar-phase2 :fix-remaining-errors
```

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| Cherry-pick conflicts during Step 3 | The new-era commits are self-contained (da66433 already deleted the old files). Conflicts unlikely, but resolve manually if they occur and continue with `git cherry-pick --continue`. |
| Losing the backup tags if local repo is damaged | Push tags to origin before starting: `git push origin backup/pre-squash-grammar-rewrite backup/pre-squash-main` |
| Force-push fails on protected branch | Check GitHub branch protection settings for `main` before Step 8. May need to temporarily disable. |
| Someone else has cloned and will have diverged history | Coordinate with all contributors before force-push. This is a solo project so risk is low. |

### Checklist

- [ ] Create and push backup tags for both `grammar-rewrite` and `main`
- [ ] Verify `git log --oneline grammar-rewrite` shows exactly `d2caaa0` at HEAD before starting
- [ ] Create orphan branch and squash old era into single "Legacy foundation" commit
- [ ] Cherry-pick all 16 new-era commits (`a3bdce5` through `d2caaa0`) in order
- [ ] Verify the resulting log has exactly 17 commits
- [ ] Run `make test` on the rebased branch — must pass before touching remote
- [ ] Reset `grammar-rewrite` to the clean tip
- [ ] Reset `main` to the squashed foundation commit
- [ ] Delete stale local branches: `master`, `refactor-grammar-phase2`, `fix-remaining-errors`
- [ ] Force-push `main` and `grammar-rewrite` to origin
- [ ] Delete remote stale branches
- [ ] Verify on GitHub that the history looks correct

---

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

## P2 — Credentials & Network Configuration `NOT STARTED`

Secure credential storage and network configuration at the language level. Connections to databases, APIs, and services should be simple and secure — no plaintext passwords in scripts.

- [ ] Credential store: encrypted local config (`.datatwist/credentials`) or native `pass` integration
- [ ] Connection profiles: named configs (`work-db`, `prod-api`) with host, port, auth, proxy settings
- [ ] Proxy/VPN-aware connections: per-profile proxy (SOCKS5/HTTP), so work VPN and personal don't conflict
- [ ] SOCKS/HTTP proxy support in all network operations (DB connectors, HTTP fetch, etc.)
- [ ] `connect` function uses profiles: `connect "work-db"` resolves creds + proxy automatically
- [ ] Environment-based overrides: `DT_PROFILE=work` selects default connection profile
- [ ] Keyring integration: macOS Keychain, Linux secret-service, Windows Credential Manager

**Design notes:** Key questions:
- Config format: TOML, EDN, or DataTwist's own syntax?
- Encryption: GPG-based (like pass), or OS keyring only?
- How to handle multiple VPN contexts simultaneously? Per-connection proxy routing?
- Should `connect` auto-detect available profiles and suggest?

---

## P3 — Language Extensions `NOT STARTED`

- [ ] String interpolation: `"Hello {name}"`
- [ ] Multi-line strings / heredocs
- [ ] Date/time literals and operations
- [ ] Regular expression literals and match
- [ ] Spread operator in objects: `{...base, name: "new"}`
- [ ] Optional type annotations for tooling
