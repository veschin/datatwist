# DataTwist Changelog

## DATATWIST-1: Docs & Infrastructure
- `4a3c3cc` Legacy foundation: pre-PRD grammar experiments (Oct–Feb 2026)
- `800cd6f` Add CLAUDE.md project instructions and simplify Makefile

## DATATWIST-2: Language Specification & BDD
- `81c815c` Add PRD specification and BDD feature files

## DATATWIST-3: Parser & Grammar
- `5a96683` Replace grammar with Instaparse prototype, implement parse-error?
- `e19c86f` Add comprehensive parser tests (TDD) — 68 tests, 440 assertions
- `dc79b04` Fix 7 grammar issues: List/FnCall disambiguation, Object/FnDef collapse, guards, require, catch, binding
- `1314d4a` Refactor parser tests to structural AST assertions with grammar-aware validation
- `f4b1432` Update CLAUDE.md: fix whitespace docs, add current status
- `7941e42` Fix greedy binding bug, clean AST of anti-collapse hacks

## DATATWIST-4: Evaluator Implementation
- `522ae8c` Implement tree-walking evaluator — 407 tests, 4 failures, 14 errors
- `961bfbd` Remove obsolete IMPLEMENTATION.md, update BACKLOG with detailed grammar issues
- `5da49a8` Fix evaluator bugs, add BDD/tests for features 7-9, design docs

## DATATWIST-5: Nil Semantics
- `1fd6aa6` Nil coercion in comparisons: nil → 0 (numbers), "" (strings)
- `e974bc3` Three-valued nil in comparisons: nil > x = nil (unknown)

## DATATWIST-6: Grammar Edge-Case Fixes
- `611b1a6` Grammar fixes: negative numbers in lists/args, regex literals, reject x = 42

## DATATWIST-7: Error Reporting
- `bb710c7` Error reporting: undefined identifiers, type error codes, stdlib guards
- `e70ebe8` Docs: error reporting research, backlog update, orchestration rules

## DATATWIST-8: Demo & DX
- `ef1ad38` Demo runner: gum/glow-style showcase with make demo

## DATATWIST-9: Lazy Evaluation Design
- `0e0e32f` Design doc: lazy evaluation — sampling, transducers, Spark/Polars patterns

## DATATWIST-10: Backlog & Orchestration
- `bf7ca9d` Backlog: async execution, credentials/VPN, git cleanup plan, orchestration rules
- `e3f7a1e` Add CHANGELOG.md, feature branch rule in orchestration
