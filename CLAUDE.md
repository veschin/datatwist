# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent Usage

When spawning subagents via Task tool, almost always use Sonnet (`model: "sonnet"`). Reserve Opus for review or complex architectural decisions.

### Agent orchestration patterns

**Research/Design** — Opus agent, run in background. Reads codebase + PRD, writes a design doc to `docs/`. Examples: LSP architecture, pushdown optimization.

**Code review** — Sonnet agents in parallel, one per feature area. Each reads PRD + BDD + evaluator, outputs structured report (implemented/deviations/missing/bugs). Do NOT modify files.

**Implementation** — Sonnet agent with a concrete task list. Must use targeted test runs (`clj -M -e "(require ...)"`) not `make test`. Fix one issue → verify → next.

**BDD + test writing** — Sonnet agents in parallel, one per BDD feature file. PRD is the ONLY source of truth, no invented features. One `deftest` per BDD scenario.

**Key principles:**
- Opus is the orchestrator: minimize own actions, conserve context window. Never read/edit files manually — delegate to agents. Sonnet agents implement (they are cheaper). Opus agents research (they are smarter). Opus orchestrator only dispatches, reviews results, commits.
- Parallelize independent work (reviews, BDD features) — one agent per scope
- Never let an agent run `make test` in a loop — use targeted namespace runs
- Never read/edit files manually when an agent can do it
- Stop stuck agents early, launch fresh with clearer instructions
- After agents finish, Opus reviews results and commits
- Haiku for trivial tasks (backlog edits, doc updates, file renames) — cheapest model
- Proactively persist cross-session knowledge: if the user shares a design idea, preference, or decision — write it to CLAUDE.md or BACKLOG.md immediately. Don't rely on conversation memory.
- If a task is too large for one session or an idea needs design work — add it to BACKLOG.md with context, don't lose it
- Implementation agents work in feature branches (`feat/<name>`), merge to main when verified. Prevents parallel agents from conflicting on the same files.

### Development pipeline

Strict order: **Design → Research → BDD → Tests (strictly from BDD) → Implementation.**

- Each feature goes through all stages sequentially. No skipping.
- BDD scenarios are written from PRD, tests are written strictly from BDD scenarios (1:1 mapping).
- Implementation comes LAST, only after BDD and tests are in place.

### Backlog ↔ PRD consistency

When writing anything to BACKLOG.md, always check it does not contradict PRD.md. If a conflict is found — resolve through the user via `AskUserQuestion` before proceeding. PRD is the source of truth for locked design decisions.

### Key design decisions (lazy eval)

- **Laziness ≠ DTPipeline**: Laziness is the execution model (lazy seqs). DTPipeline is the introspection model (step inspection, caching). Orthogonal concerns — implement independently.
- **`collect` is removed. `force!` is the sole materializer**: forces full pipeline execution, ignores sampling.
- **`log!` is removed. `tap!` is the sole pipeline debug tool** (three modes: bare, labeled, lambda).
- **`autotap!` is a macro-like transformation**: wraps every pipeline step with tap!.

### Design sessions

When reviewing design decisions with the user, use `AskUserQuestion` with multi-select checkboxes — NOT single-option questions. User prefers the form/checklist format where they can select multiple options and add notes to each. Present 2-4 options per topic with short labels and descriptions. Group up to 4 questions per form.

## Context Window Rules

- NEVER read large files (>100 lines) in their entirety — they won't fit in context.
- Always use `offset`/`limit` parameters when reading files, or use Grep to find specific sections.
- **`evaluator.clj` (~1600 lines) and `stdlib.clj` (~435 lines)** are the largest files — always grep first, then read targeted sections.
- For test files: grep for the specific failing test name, then read only that section.

## Project Overview

DataTwist is a functional data processing language built on Clojure/JVM. It compiles DataTwist source code to Clojure via an Instaparse EBNF grammar. The language uses pipe-first semantics (`|>`), `is` for binding, and `[params -> body]` for functions. See `PRD.md` for the complete language specification and design decisions.

## Build & Test Commands

```bash
make test              # Run all tests via clj -M -m datatwist.test-runner
make lint              # Run clj-kondo linter on src/
make clean             # Remove .cpcache/ and .lsp/.cache/

# Run a single test namespace
clj -M -e "(require 'clojure.test 'datatwist.literals-test) (clojure.test/run-tests 'datatwist.literals-test)"
```

Dependencies are managed via `deps.edn` (Clojure CLI, no Leiningen). The sole external dependency is `instaparse/instaparse 1.5.0`.

## Architecture

### Source Files and Data Flow

DataTwist source → grammar (Instaparse EBNF) → parser → AST → evaluator (tree-walk) → Clojure values.

| File | Role |
|---|---|
| `resources/datatwist.grammar` | Instaparse EBNF grammar (~195 lines). Manual whitespace (`_` = optional, `__` = required). No `:auto-whitespace`. Keywords hidden via `<>`. Comments use `//`. |
| `src/datatwist/parser.clj` | Thin wrapper: `parse`, `parse-error?`, `eval-dt` (lazy-requires evaluator) |
| `src/datatwist/evaluator.clj` | Tree-walking evaluator (~1600 lines). `evaluate`, `eval-node`, `eval-expr`, pipeline/guard/destructuring dispatch. **Largest file — always grep + offset/limit, never read whole.** |
| `src/datatwist/env.clj` | Environment (scoping): `make-env`, `lookup`, `bind`, `bind-many` — simple map-based |
| `src/datatwist/stdlib.clj` | Standard library (~435 lines): built-in functions (`map`, `filter`, `reduce`, `sort-by`, `tap!`, etc.) injected into the default environment |
| `src/datatwist/errors.clj` | Error code registry (DT-XNNN format) and canonical error map shape (`{:dt/error true :code :category :message :hint ...}`) |
| `src/datatwist/error_renderer.clj` | Elm/Rust-style error rendering with optional ANSI color. Respects `NO_COLOR`/`DT_NO_COLOR` |
| `src/datatwist/demo_runner.clj` | `make demo` entry point — language showcase |

### Test Structure

Tests follow a strict BDD-to-TDD mapping: each `deftest` corresponds 1:1 to a BDD scenario in `bdd/*.feature`. Test files are organized by language feature area:

| Test file | BDD feature | Language area |
|---|---|---|
| `literals_test.clj` | `1-literals-types-operators.feature` | Numbers, strings, booleans, nil, operators, precedence |
| `data_structures_test.clj` | `2-data-structures.feature` | Objects (maps), lists (vectors), field access |
| `functions_test.clj` | `3-functions-closures.feature` | `[params -> body]`, closures, predicates (`?`), side effects (`!`) |
| `pipeline_test.clj` | `4-pipeline.feature` | `\|>` operator, `_` context, nested pipes, collection ops |
| `binding_test.clj` | `5-binding-destructuring.feature` | `is` binding, object/list destructuring |
| `pattern_matching_test.clj` | `6-pattern-matching.feature` | Guards (`\| expr -> result`), structural matching |
| `interop_test.clj` | `7-interop-misc.feature` | Clojure interop, require, miscellaneous |
| `error_reporting_test.clj` | `9-error-reporting.feature` | Error codes, messages, rendering (stubs — not yet implemented) |
| `lazy_eval_test.clj` | `8-lazy-eval-data-sources.feature` | Lazy evaluation, data sources (stubs — not yet implemented) |
| `demo_runner_test.clj` | `10-demo-runner.feature` | Demo runner integration |

**Exception: `parser_test.clj`** does not use `test_helpers.clj`. It has its own helpers (`parses?`, `parse-fails?`, `ast`, `simplify`) that test the grammar directly via `instaparse.core`, without going through `eval-dt`.

All other test files use helpers from `test/datatwist/test_helpers.clj`:
- `eval-dt` — Evaluate a single DataTwist expression
- `eval-dt-last` — Evaluate multiple lines, return last result (for binding scenarios)
- `parse-error?` — Assert syntax is rejected by the parser
- `throws?` / `throws-type?` — Assert runtime exceptions
- `type-of` — Return the JVM class of an evaluated expression

### BDD Specifications

`bdd/` contains 12 Gherkin `.feature` files (numbered 1–12) that serve as the authoritative language specification. Features 8–9 have BDD + test stubs but no evaluator support yet. Features 11–12 (LSP, nREPL) are design-only.

## Current Status

Grammar and evaluator are complete. Features 1–7 fully implemented (~500+ tests, 0 failures). Features 8–9 (lazy eval, error reporting) have BDD + test stubs but no evaluator support yet. Features 10–12 (demo runner, LSP, nREPL) are in various stages of design.

## Key Language Design Decisions

- Assignment uses `is` (not `=`); equality uses `=`
- Functions: `[params -> body]` (square brackets only)
- Pipe operator `|>` is pipe-first: `data |> f args` = `f(data, args)`
- `_` is context-overloaded: pipeline current element, pattern default, destructure skip
- Side-effect functions end with `!` and are passthrough (return their first argument)
- Object keys use postfix colon: `{name: "Alice"}`
- Nil-tolerant: `nil.field` returns `nil`, arithmetic coerces nil to identity element
- Objects = Clojure maps with keyword keys; lists = Clojure vectors
