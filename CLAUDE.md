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

## Context Window Rules

- NEVER read large files (>100 lines) in their entirety — they won't fit in context.
- Always use `offset`/`limit` parameters when reading files, or use Grep to find specific sections.
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

### Parser Pipeline

- `src/datatwist/parser.clj` — Parser: Instaparse parser, `parse`, `parse-error?`
- `src/datatwist/evaluator.clj` — Tree-walking evaluator: `eval-dt`, `eval-dt-last`
- `resources/datatwist.grammar` — Instaparse EBNF grammar (~190 lines)

The grammar uses **manual whitespace** (`_` = optional, `__` = required). No `:auto-whitespace`. Keywords are hidden via `<>` angle brackets. Comments use `//`.

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

**Exception: `parser_test.clj`** does not use `test_helpers.clj`. It has its own helpers (`parses?`, `parse-fails?`, `ast`, `simplify`) that test the grammar directly via `instaparse.core`, without going through `eval-dt`.

All other test files use helpers from `test/datatwist/test_helpers.clj`:
- `eval-dt` — Evaluate a single DataTwist expression
- `eval-dt-last` — Evaluate multiple lines, return last result (for binding scenarios)
- `parse-error?` — Assert syntax is rejected by the parser
- `throws?` / `throws-type?` — Assert runtime exceptions
- `type-of` — Return the JVM class of an evaluated expression

### BDD Specifications

`bdd/` contains 9 Gherkin `.feature` files (numbered 1–9) that serve as the authoritative language specification.

## Current Status

**506 tests, 1188 assertions, 0 failures, 0 errors.** Grammar and evaluator are complete. Features 1–7 fully implemented. Features 8–9 (lazy eval, error reporting) have BDD + test stubs but no evaluator support yet.

## Key Language Design Decisions

- Assignment uses `is` (not `=`); equality uses `=`
- Functions: `[params -> body]` (square brackets only)
- Pipe operator `|>` is pipe-first: `data |> f args` = `f(data, args)`
- `_` is context-overloaded: pipeline current element, pattern default, destructure skip
- Side-effect functions end with `!` and are passthrough (return their first argument)
- Object keys use postfix colon: `{name: "Alice"}`
- Nil-tolerant: `nil.field` returns `nil`, arithmetic coerces nil to identity element
- Objects = Clojure maps with keyword keys; lists = Clojure vectors
