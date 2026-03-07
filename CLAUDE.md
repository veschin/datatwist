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
- **After agents finish, Opus MUST review before committing.** Never trust agent summaries blindly. Review checklist:
  1. Read the actual diff (`git diff`) — not just the agent's report
  2. Check edge cases the agent may have missed (escaping, whitespace, nil, empty input, boundary conditions)
  3. Verify BDD coverage matches the implementation — if a feature was added, BDD scenarios for its edge cases must exist
  4. For grammar/parser changes: manually test parsing of edge case inputs
  5. For new stdlib functions: verify argument validation, nil handling, type errors
  6. If changes are significant (>50 lines), dispatch a separate code-review agent before committing
  7. Only commit after review passes. If issues found — fix or re-dispatch agent with specific instructions
- Haiku for trivial tasks (backlog edits, doc updates, file renames) — cheapest model
- Proactively persist cross-session knowledge: if the user shares a design idea, preference, or decision — write it to CLAUDE.md or BACKLOG.md immediately. Don't rely on conversation memory.
- If a task is too large for one session or an idea needs design work — add it to BACKLOG.md with context, don't lose it
- Implementation agents work in feature branches (`feat/<name>`), merge to main when verified. Prevents parallel agents from conflicting on the same files.
- **HARDCODED VALUES AND STUBS ARE FORBIDDEN.** No magic numbers, no placeholder implementations, no silently swallowed errors. If something isn't implemented — it must fail loudly (throw, not return nil). Better to crash and know there's a problem than to silently mask it. This applies to: sample sizes, config values (use config.clj), return values (no fake data), error handling (no empty catch blocks). Stubs that pretend to work are worse than no code at all — they hide the real state of the system.

### Development pipeline

Strict order: **Design → Research → BDD → Tests (strictly from BDD) → Implementation.**

- Each feature goes through all stages sequentially. No skipping.
- BDD scenarios are written from PRD, tests are written strictly from BDD scenarios (1:1 mapping).
- Implementation comes LAST, only after BDD and tests are in place.

### Backlog ↔ PRD consistency

When writing anything to BACKLOG.md, always check it does not contradict PRD.md. If a conflict is found — resolve through the user via `AskUserQuestion` before proceeding. PRD is the source of truth for locked design decisions.

### Design sessions

When reviewing design decisions with the user, use `AskUserQuestion` with multi-select checkboxes — NOT single-option questions. User prefers the form/checklist format where they can select multiple options and add notes to each. Present 2-4 options per topic with short labels and descriptions. Group up to 4 questions per form.

## Context Window Rules

- NEVER read large files (>100 lines) in their entirety — they won't fit in context.
- Always use `offset`/`limit` parameters when reading files, or use Grep to find specific sections.
- **`evaluator.clj` (~1800 lines) and `stdlib.clj` (~660 lines)** are the largest files — always grep first, then read targeted sections.
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
| `src/datatwist/evaluator.clj` | Tree-walking evaluator (~1800 lines). `evaluate`, `eval-node`, `eval-expr`, pipeline/guard/destructuring dispatch. **Largest file — always grep + offset/limit, never read whole.** |
| `src/datatwist/env.clj` | Environment (scoping): `make-env`, `lookup`, `bind`, `bind-many` — simple map-based |
| `src/datatwist/stdlib.clj` | Standard library (~660 lines): built-in functions (`map`, `filter`, `reduce`, `sort-by`, `tap!`, etc.) injected into the default environment |
| `src/datatwist/config.clj` | Runtime config store (`get-config`, `set-config!`, `reset-config!`). Holds `:SAMPLE_SIZE`, `:DESCRIBE_SAMPLE_SIZE`, `:PRINT_WIDTH`, `:MAX_COLLECT_ROWS` |
| `src/datatwist/pattern_compiler.clj` | Compiles `#p"..."` pattern strings to regex-based matchers (`compile-pattern`, `apply-pattern`). Phase 1: simple `{var}` captures |
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
| `error_reporting_test.clj` | `9-error-reporting.feature` | Error codes, messages, rendering |
| `lazy_eval_test.clj` | `8-lazy-eval-data-sources.feature` | Lazy evaluation, data sources (partially implemented — DTPipeline stubs remain) |
| `demo_runner_test.clj` | `10-demo-runner.feature` | Demo runner integration |
| `pattern_destructuring_test.clj` | `13-pattern-destructuring.feature` | `#p"..."` pattern matching, destructuring, guards |

**Exception: `parser_test.clj`** does not use `test_helpers.clj`. It has its own helpers (`parses?`, `parse-fails?`, `ast`, `simplify`) that test the grammar directly via `instaparse.core`, without going through `eval-dt`.

All other test files use helpers from `test/datatwist/test_helpers.clj`:
- `eval-dt` — Evaluate a single DataTwist expression
- `eval-dt-last` — Evaluate multiple lines, return last result (for binding scenarios)
- `parse-error?` — Assert syntax is rejected by the parser
- `throws?` / `throws-type?` — Assert runtime exceptions
- `type-of` — Return the JVM class of an evaluated expression
- `silent-eval-dt` / `silent-eval-dt-last` — Evaluate while suppressing stdout
- `capture-eval-dt-last` — Returns `{:result :output}` map capturing stdout

### BDD Specifications

`bdd/` contains 13 Gherkin `.feature` files (numbered 1–13) that serve as the authoritative language specification. Features 11–12 (LSP, nREPL) are design-only.

## Current Status

Grammar and evaluator are complete. Features 1–7 fully implemented. Feature 9 (error reporting) implemented. Feature 8 (lazy eval) partially implemented — lazy sequences and `force!` work, DTPipeline/push-down stubs remain. Feature 13 (pattern destructuring `#p`) Phase 1 implemented. ~768 deftest blocks across all test files. Features 10–12 (demo runner, LSP, nREPL) are in various stages of design.

## Key Language Design Decisions

- Assignment uses `is` (not `=`); equality uses `=`
- Functions: `[params -> body]` (square brackets only)
- Pipe operator `|>` is pipe-first: `data |> f args` = `f(data, args)`
- `_` is context-overloaded: pipeline current element, pattern default, destructure skip
- Side-effect functions end with `!` and are passthrough (return their first argument)
- Object keys use postfix colon: `{name: "Alice"}`
- Nil-tolerant: `nil.field` returns `nil`, arithmetic coerces nil to identity element
- Objects = Clojure maps with keyword keys; lists = Clojure vectors

<!-- ---ptsd--- -->
# Claude Agent Instructions

## Authority Hierarchy (ENFORCED BY HOOKS)

PTSD (iron law) > User (context provider) > Assistant (executor)

- PTSD decides what CAN and CANNOT be done. Pipeline, gates, validation — non-negotiable.
  Hooks enforce this automatically — writes that violate pipeline are BLOCKED.
- User provides context and requirements. User also follows ptsd rules.
- Assistant executes within ptsd constraints. Writes code, docs, tests on behalf of user.

## Session Start Protocol

EVERY session, BEFORE any work:
1. Run: ptsd context --agent — see full pipeline state
2. Run: ptsd task next --agent — get next task
3. Follow output exactly.

## Commands (always use --agent flag)

- ptsd context --agent              — full pipeline state (auto-injected by hooks)
- ptsd status --agent               — project overview
- ptsd task next --agent            — next task to work on
- ptsd task update <id> --status WIP — mark task in progress
- ptsd validate --agent             — check pipeline before commit
- ptsd feature list --agent         — list all features
- ptsd seed init <id> --agent       — initialize seed directory
- ptsd gate-check --file <path> --agent — check if file write is allowed

## Skills

PTSD pipeline skills are in `.claude/skills/` — auto-loaded when relevant.

| Skill | When to Use |
|-------|------------|
| write-prd | Creating or updating a PRD section |
| write-seed | Creating seed data for a feature |
| write-bdd | Writing Gherkin BDD scenarios |
| write-tests | Writing tests from BDD scenarios |
| write-impl | Implementing to make tests pass |
| create-tasks | Adding tasks to tasks.yaml |
| review-prd | Reviewing PRD before advancing to seed |
| review-seed | Reviewing seed data before advancing to bdd |
| review-bdd | Reviewing BDD before advancing to tests |
| review-tests | Reviewing tests before advancing to impl |
| review-impl | Reviewing implementation after tests pass |
| workflow | Session start or when unsure what to do next |
| adopt | Bootstrapping existing project into PTSD |

Use the corresponding write skill, then review skill at each pipeline stage.

## Pipeline (strict order, no skipping)

PRD → Seed → BDD → Tests → Implementation

Each stage requires review score ≥ 7 before advancing.
Hooks enforce gates automatically — blocked writes show the reason.

## Rules

- NO mocks for internal code. Real tests, real files, temp directories.
- NO garbage files. Every file must link to a feature.
- NO hiding errors. Explain WHY something failed.
- NO over-engineering. Minimum code for the current task.
- ALWAYS run: ptsd validate --agent before committing.
- COMMIT FORMAT: [SCOPE] type: message
  Scopes: PRD, SEED, BDD, TEST, IMPL, TASK, STATUS
  Types: feat, add, fix, refactor, remove, update

## Troubleshooting

When ptsd status/validate shows unexpected results, debug with these steps:

| Symptom | Cause | Fix |
|---------|-------|-----|
| TESTS:0 but test files exist | Tests not mapped to features | `ptsd test map .ptsd/bdd/<id>.feature <test-file>` for each feature |
| BDD:0 but .feature files exist | State hashes empty, SyncState not run | `ptsd status --agent` triggers sync; if still 0, check `.ptsd/bdd/<id>.feature` has `@feature:<id>` tag on line 1 |
| Feature stuck at wrong stage | review-status.yaml stale or stage not advanced | Run `ptsd review <id> <stage> <score>` to advance; check `ptsd context --agent` for blockers |
| "no test files mapped" on `ptsd test run` | Test mapping missing in state.yaml | `ptsd test map .ptsd/bdd/<id>.feature <test-file>` |
| Gate blocks file write | File not in allowed list for current stage | Check `ptsd gate-check --file <path> --agent`; advance feature to correct stage first |
| Validate shows "mock detected" | Test file contains mock/stub patterns | Replace mocks with real file-based tests in temp directories |
| Regression warning on status | Artifact file changed after stage was reviewed | Re-review the stage: `ptsd review <id> <stage> <score>` |

### Debug flow
1. `ptsd context --agent` — shows next action, blockers, stage per feature
2. `ptsd feature show <id> --agent` — shows artifact counts and test stats
3. `ptsd validate --agent` — shows all pipeline violations
4. Check `.ptsd/state.yaml` — hashes, test mappings, stages
5. Check `.ptsd/review-status.yaml` — review verdicts per feature

### Test mapping
Each feature needs: BDD file (`.ptsd/bdd/<id>.feature`) with `@feature:<id>` tag → mapped to test file via `ptsd test map`. Without mapping, ptsd cannot track test results per feature.

## Forbidden

- Mocking internal code
- Skipping pipeline steps
- Hiding errors or pretending something works
- Generating files not linked to a feature
- Using --force, --skip-validation, --no-verify

<!-- ---ptsd--- -->
