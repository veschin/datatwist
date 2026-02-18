# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DataTwist is a functional data processing language built on Clojure/JVM. It compiles DataTwist source code to Clojure via an Instaparse EBNF grammar. The language uses pipe-first semantics (`|>`), `is` for binding, and `[params -> body]` for functions. See `PRD.md` for the complete language specification and design decisions.

## Build & Test Commands

```bash
make test              # Run all tests via clj -M -m datatwist.test-runner
make lint              # Run clj-kondo linter on src/
make clean             # Remove .cpcache/ and .lsp/.cache/

# Run a single test namespace
clj -M -m clojure.test datatwist.literals-test

# Run tests matching a pattern (requires adding cognitect test-runner or similar)
clj -X clojure.test/run-tests :nses '[datatwist.literals-test]'
```

Dependencies are managed via `deps.edn` (Clojure CLI, no Leiningen). The sole external dependency is `instaparse/instaparse 1.5.0`.

## Architecture

### Parser Pipeline

`src/datatwist/parser.clj` — The single source file. Contains:
- `parser` — Instaparse parser built from `resources/datatwist.grammar` (EBNF)
- `parse` — Returns AST (parse tree) or instaparse failure
- `eval-dt` / `parse-error?` — Stubs for the evaluator (not yet implemented)

The grammar file (`resources/datatwist.grammar`) defines the full language syntax using Instaparse's EBNF notation with `:auto-whitespace :standard`.

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

All tests use helpers from `test/datatwist/test_helpers.clj`:
- `eval-dt` — Evaluate a single DataTwist expression
- `eval-dt-last` — Evaluate multiple lines, return last result (for binding scenarios)
- `parse-error?` — Assert syntax is rejected by the parser
- `throws?` / `throws-type?` — Assert runtime exceptions
- `type-of` — Return the JVM class of an evaluated expression

### BDD Specifications

`bdd/` contains 9 Gherkin `.feature` files (numbered 1–9) that serve as the authoritative language specification. Features 7–9 (interop, lazy evaluation, error reporting) do not yet have corresponding test files.

## Key Language Design Decisions

- Assignment uses `is` (not `=`); equality uses `=`
- Functions: `[params -> body]` (square brackets only)
- Pipe operator `|>` is pipe-first: `data |> f args` = `f(data, args)`
- `_` is context-overloaded: pipeline current element, pattern default, destructure skip
- Side-effect functions end with `!` and are passthrough (return their first argument)
- Object keys use postfix colon: `{name: "Alice"}`
- Nil-tolerant: `nil.field` returns `nil`, arithmetic coerces nil to identity element
- Objects = Clojure maps with keyword keys; lists = Clojure vectors
