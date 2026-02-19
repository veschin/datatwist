# Changelog

All notable changes to DataTwist are documented here.

## [Unreleased]

### Added
- **Demo runner** — `make demo` showcases all language features with gum/glow-style terminal UI
- **Regex literals** — `#","` syntax, compiles to java.util.regex.Pattern
- **Negative numbers in lists/args** — `[-10 50]` and `nth items -1` work without parentheses
- **Error reporting module** — 41 tests covering parse errors, type errors, runtime errors
  - Undefined identifier detection with Levenshtein did-you-mean suggestions
  - Error codes: DT-R001 (undefined), DT-T001 (type mismatch), DT-T002 (comparison)
  - Hints in ex-data for actionable fix suggestions
  - Java/Clojure stack traces hidden from user output
- **Stdlib guards** — `map` rejects non-collections, `sort-by` handles nil keys gracefully
- **Object destructuring guard** — throws on non-object input (strings, numbers)
- **Grammar: `x = 42` rejection** — common assignment mistake detected at parse level

### Design docs
- `docs/error-reporting-research.md` — Elm/Rust/Zig/Gleam error message survey
- `docs/lazy-eval-design.md` — lazy evaluation architecture, sampling model, transducers

### Changed
- Parse errors return nil instead of throwing (separation of concerns)
- List destructuring remains nil-tolerant for missing positions (bdd/5 spec)
- Test runner includes error-reporting-test (547 total tests)

### Infrastructure
- BACKLOG updated: async/parallel execution, credentials/VPN, git cleanup plan
- CLAUDE.md updated: orchestration rules, model selection guide
