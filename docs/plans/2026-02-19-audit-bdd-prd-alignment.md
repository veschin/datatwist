# BDD-PRD Alignment Audit Report

**Date:** 2026-02-19
**Scope:** BDD features 8-12 vs PRD.md and design documents
**Auditor:** Claude Opus 4.6 (automated audit agent)

---

## 1. Summary

### Scenario Counts

| Feature | File | Scenarios |
|---|---|---|
| 1: Literals, Types, Operators | `1-literals-types-operators.feature` | 131 |
| 2: Data Structures | `2-data-structures.feature` | 94 |
| 3: Functions & Closures | `3-functions-closures.feature` | 63 |
| 4: Pipeline | `4-pipeline.feature` | 83 |
| 5: Binding & Destructuring | `5-binding-destructuring.feature` | 67 |
| 6: Pattern Matching | `6-pattern-matching.feature` | 58 |
| 7: Interop & Misc | `7-interop-misc.feature` | 95 |
| 8: Lazy Eval & Data Sources | `8-lazy-eval-data-sources.feature` | 76 |
| 9: Error Reporting | `9-error-reporting.feature` | 48 |
| 10: Demo Runner | `10-demo-runner.feature` | 22 |
| 11: LSP Editor Support | `11-lsp-editor-support.feature` | 37 |
| 12: nREPL Integration | `12-nrepl-integration.feature` | 31 |
| **Total** | | **805** |

### PRD Claim vs Actual

The PRD (line 482-490) claims the following scenario counts:
- Feature 8: 105 scenarios -- **Actual: 76** (PRD is stale, already noted in lazy-eval-bdd-verification)
- Feature 9: 89 scenarios -- **Actual: 48** (PRD is stale)
- Features 10-12: **Not listed in PRD** at all

**Overall alignment verdict:** Features 8-9 are well-aligned with the PRD. Features 10-12 are not mentioned in the PRD but are well-grounded in design documents. Several specific gaps and contradictions are documented below.

---

## 2. Per-Feature Analysis

### Feature 8: Lazy Evaluation, Data Sources & REPL Micro-sampling

**Source of truth:** PRD.md sections 8 and 10; `docs/lazy-eval-design.md`
**BDD scenarios:** 76 across 12 sections
**PRD coverage estimate:** ~85%

#### Alignment: Strong

The BDD file covers all major PRD concepts:
- Lazy pipeline construction (Section 1, 5 scenarios)
- Materialization functions: `collect`, `count`, `first`, `reduce`, `force!`, `save!`, `into!` (Section 2, 13 scenarios)
- REPL micro-sampling (Section 3, 5 scenarios)
- `tap!` inline debugging (Section 4, 6 scenarios)
- Database sources: `connect`, `table`, `query`, `close!` (Section 5, 8 scenarios)
- File sources: `read-csv`, `read-json`, `read-jsonl`, `read-lines`, `read-parquet` (Section 6, 8 scenarios)
- SQL push-down optimization (Section 7, 8 scenarios)
- Explore/describe functions (Section 8, 7 scenarios)
- Pipeline as first-class runtime object (Section 9, 4 scenarios)
- Error handling for data sources (Section 10, 4 scenarios)
- Integration scenarios (Section 11, 3 scenarios)
- Infinite sequences and configuration (Section 12, 5 scenarios)

#### Contradictions

1. **`force!` semantics -- passthrough vs return value.** The PRD Design Decisions table (line 26) says: "`force!` materializes lazy pipeline. `count`, `collect` are regular functions." The BDD (line 16) correctly specifies `force!` as passthrough. However, the design doc (`lazy-eval-design.md`, line 153) says `force!` returns "Same data (passthrough)" while the BDD scenario on line 158 says "force! returns the materialized data (passthrough -- the data flows through)." These are consistent with each other and the PRD. **No contradiction found.**

2. **`freq` forces full evaluation.** The design doc (`lazy-eval-design.md`, Q8, line 237) says `freq` "Forces full evaluation (needs exact counts)." The BDD does not specify whether `freq` is lazy or eager. The BDD scenario (line 688) only says "freq displays each distinct value and its count and percentage." This is not a contradiction but is an under-specification in the BDD -- the eagerness property of `freq` is not tested.

#### Gaps (PRD mentions, BDD does not cover)

1. **`last` as a materialization trigger.** The design doc (`lazy-eval-design.md`, line 150) lists `last` as an explicit materializer that "Forces full traversal, returns last element." There is **no BDD scenario** testing `last` as a materialization function in feature 8. This is a coverage gap.

2. **Aggregation functions as materializers.** The design doc (line 152) lists `sum`, `average`, `min`, `max`, `median` as terminal reducers that force full traversal. The BDD has **no scenarios** testing these as materialization triggers. (They may be covered in feature 4/pipeline, but their lazy-evaluation semantics are not specified.)

3. **`join`, `left-join`, `inner-join`, `outer-join`.** The PRD stdlib (line 477) lists these as Multi-Source functions. No BDD scenarios exist for join operations on lazy data sources. This is a significant functional gap for a data processing language.

4. **`fill-nil`, `skip-nil`, `coerce`.** Listed in PRD stdlib (line 473) under Nil Handling. No BDD coverage in any feature file.

5. **`cycle` as an infinite sequence generator.** While `cycle` has a BDD scenario (line 889), neither `range` with a single argument (infinite range) nor edge cases like `collect` on an infinite sequence (expected OOM/warning) are tested. The design doc (Q6, line 192) specifies the REPL should display a warning for infinite sequences.

6. **`dtw/set!` / `dtw/get` configuration keys.** The BDD (line 898-906) only tests `sample-size`. The design doc (lines 286-291) specifies four configuration keys: `sample-size`, `describe-sample-size`, `max-collect-rows`, `print-width`. Only one of four is tested.

7. **REPL display of infinite sequence warning.** Design doc Q6 says the REPL should display "lazy\<infinite\> -- use take to limit." No BDD scenario tests this.

8. **`describe` with sample size override.** Design doc (line 243) says `data |> describe 5000` overrides the default sample size. No BDD scenario tests this per-call override.

#### Over-specification (BDD goes beyond PRD)

1. **SQL push-down with exact SQL strings.** BDD Section 7 specifies exact SQL output (e.g., "SELECT * FROM users WHERE age > 18"). The PRD only mentions push-down at a high level ("filter/sort -> WHERE/ORDER BY in SQL"). The exact SQL format is an implementation detail that could make tests brittle. However, this specificity is useful for ensuring correct push-down behavior, and the `docs/pushdown-design.md` likely supports this level of detail. **Rating: acceptable over-specification.**

---

### Feature 9: Error Reporting

**Source of truth:** PRD.md section 9; `docs/error-reporting-research.md`
**BDD scenarios:** 48 across 10 sections
**PRD coverage estimate:** ~90%

#### Alignment: Strong

The BDD file covers all major PRD error reporting concepts:
- Parse errors with error codes (DT-P) (Section 1, 8 scenarios)
- Common mistake detection (Section 2, 7 scenarios)
- Error message format with snippets and pointers (Section 3, 4 scenarios)
- Type errors (DT-T) (Section 4, 5 scenarios)
- Runtime errors (DT-R) with suggestions (Section 5, 7 scenarios)
- Data-aware warnings (DT-D) (Section 6, 5 scenarios)
- Java/Clojure exception translation (Section 7, 3 scenarios)
- Connection/data source errors (DT-C) (Section 8, 2 scenarios)
- Error vs warning distinction (Section 9, 2 scenarios)
- Additional runtime error coverage from impl plan (Section 10, 5 scenarios)

#### Contradictions

1. **Nil in arithmetic -- error vs coercion.** BDD scenario at line 214-225 tests `x + y` where `y is nil` and expects the result to be 5, correctly matching PRD nil coercion semantics (`nil + 5 = 5`). The scenario comment (line 223) correctly acknowledges this is not a type error but a coercion. **No contradiction -- well-handled.**

2. **`is` scope -- BDD says "keyword.operator" but error reporting uses "assignment."** In BDD feature 11 (LSP), `is` gets scope `keyword.operator.datatwist`. In feature 9 (error reporting), common mistake scenarios describe `is` as "assignment." The design doc TextMate grammar (line 709) uses `keyword.operator.binding.datatwist` which is more specific. The BDD feature 11 uses the less-specific `keyword.operator.datatwist`. **Minor inconsistency -- see cross-feature section.**

#### Gaps (PRD mentions, BDD does not cover)

1. **Error code format validation.** BDD scenario at line 174-177 says "the error includes a code matching the pattern DT-P, DT-T, DT-R, DT-D, or DT-C" and "the code is followed by a three-digit number." This is good but there is no scenario testing that the error code is included in the formatted output header as specified in the design doc (line 182): `-- CATEGORY [DT-CODE] ------`. The header format is not tested.

2. **First-person prose voice.** The design doc (line 199) specifies first-person voice ("I see...", "I found..."). The BDD scenarios do not verify the prose voice. This is an implementation style detail but could be tested.

3. **Multi-line error snippet display.** The design doc (line 237-243) specifies how multi-line errors should be rendered with `...` elision. No BDD scenario tests multi-line error display.

4. **Color scheme.** The design doc (lines 290-310) specifies ANSI color codes for error output. No BDD scenario tests color rendering (which is reasonable -- colors should be tested at the unit level, not BDD level). However, the design doc also mentions `NO_COLOR` / `DT_NO_COLOR` env var suppression. No scenario tests this.

5. **`summarize` aggregation errors.** The PRD lists `summarize` in the stdlib but no error scenario tests what happens when `summarize` receives invalid input.

#### Over-specification

None identified. All BDD scenarios are grounded in PRD or design doc content.

---

### Feature 10: Demo Runner

**Source of truth:** No PRD section. This feature is a development/demo tooling concern.
**BDD scenarios:** 22 across 7 sections
**Design doc coverage estimate:** N/A (no dedicated design doc)

#### Alignment: Standalone feature

The PRD does not mention a demo runner at all. This feature file is self-contained and describes a file-based evaluation system for `.dt` files. It is a developer-facing tool, not a language feature.

#### Assessment

The 22 scenarios are well-structured and internally consistent:
- File loading (3 scenarios)
- Section marker parsing with `// @section` (4 scenarios)
- Expression extraction (3 scenarios)
- Sequential evaluation with shared context (3 scenarios)
- `// @expect` annotation validation (4 scenarios)
- Formatted output (3 scenarios)
- End-to-end execution (2 scenarios)

#### Gaps

1. **No PRD backing.** This feature has no PRD section and no design doc. While the BDD file is internally consistent, there is no higher-level specification to audit against. **Risk: low** -- this is tooling, not language semantics.

2. **Multi-line expression handling.** The BDD mentions that "a multi-line binding is kept as a single expression unit" (scenario at line 93-100) but does not specify how the parser determines multi-line expression boundaries. Since DataTwist uses continuation lines (e.g., `|>` on the next line), the demo runner's expression-splitting logic may be non-trivial.

3. **`@expect` format for non-scalar values.** BDD scenario at line 141-145 shows `// @expect 14` for a numeric result. There is no scenario for expected values that are objects, lists, strings, or nil.

#### Over-specification

None. The BDD is appropriately scoped for a demo tool.

---

### Feature 11: LSP Editor Support

**Source of truth:** `docs/ide-tooling-research.md`; `docs/lsp-tree-sitter-design.md`
**BDD scenarios:** 37 across 7 sections
**Design doc coverage estimate:** ~65%

#### Alignment: Moderate

The BDD covers:
- Syntax highlighting scopes (Section 1, 9 scenarios)
- Autocomplete (Section 2, 5 scenarios)
- Hover (Section 3, 4 scenarios)
- Go-to-definition (Section 4, 4 scenarios)
- Error diagnostics (Section 5, 5 scenarios)
- Eval-at-point via nREPL (Section 6, 4 scenarios)
- Data inspector (Section 7, 5 scenarios)

#### Contradictions

1. **`is` highlight scope mismatch.** BDD scenario (line 20) says `is` has scope `keyword.operator.datatwist`. The design doc TextMate grammar (line 709-710) assigns `is` the scope `keyword.operator.binding.datatwist`. The BDD uses a less-specific scope name. This is a **contradiction** -- the BDD should use `keyword.operator.binding.datatwist` to match the design doc's TextMate grammar and to distinguish `is` from other operators like `and`, `or`, `not` which also fall under `keyword.operator`.

2. **`name` binding scope.** BDD scenario (line 21) says `name` in `name is "Alice"` has scope `variable.other.binding.datatwist`. The design doc (line 615) says identifiers after `is` have scope `variable.other.binding.datatwist`. **Consistent.**

#### Gaps (Design doc mentions, BDD does not cover)

1. **Signature help.** The design doc (line 553) lists `textDocument/signatureHelp` as a "Must have" capability for the LSP MVP. There is **no BDD scenario** for signature help (e.g., showing parameter info when typing a function call). **Significant gap.**

2. **Document symbols.** The design doc (line 556) lists `textDocument/documentSymbol` as "Nice to have." No BDD scenario. Low priority but still a gap.

3. **TextMate grammar fallback.** The design doc discusses TextMate grammar as Phase 1 (1-2 days, instant syntax highlighting). The BDD specifies Tree-sitter scopes but there is no scenario verifying TextMate fallback behavior. Not critical.

4. **Structural navigation.** The design doc (line 223) mentions structural navigation (next/previous expression, up/down in tree) via Tree-sitter. No BDD scenario tests this.

5. **`require` / `as` keyword highlighting.** The design doc (line 604) lists `require` and `as` with scope `keyword.control.import.datatwist`. No BDD scenario tests import keyword highlighting.

6. **`:keyword` literal highlighting.** The design doc (line 617) lists `:keyword` with scope `constant.other.keyword.datatwist`. No BDD scenario.

7. **Eval-at-point edge cases.** The design doc (lines 165-199) describes evaluable units (binding, pipeline, call expression, binary expression, function definition, literal, program). The BDD only tests three eval-at-point scenarios (binding, pipeline, literal). Missing: function definition eval, call expression eval, multi-expression eval.

#### Over-specification

1. **Inspector drilldown scenarios in LSP feature.** Sections 6 (eval-at-point) and 7 (data inspector) are functionally nREPL features (they require a live JVM evaluator), not LSP features. The feature file header acknowledges this ("the parts of Stack A visible in the editor"), but mixing Stack A (nREPL) and Stack B (LSP) behaviors in one feature file may cause confusion during implementation. **Recommendation: clearly label which scenarios are Stack A vs Stack B.**

---

### Feature 12: nREPL Integration

**Source of truth:** `docs/ide-tooling-research.md` (section 2)
**BDD scenarios:** 31 across 7 sections
**Design doc coverage estimate:** ~75%

#### Alignment: Good

The BDD covers:
- Connection (3 scenarios)
- Evaluation: simple, arithmetic, binding, pipeline, function (8 scenarios)
- Session persistence and isolation (5 scenarios)
- Completion (4 scenarios)
- Inspector: inspect-start, inspect-push, inspect-pop (5 scenarios)
- Load file (3 scenarios)
- Error handling (3 scenarios)

#### Contradictions

None identified. All BDD scenarios are consistent with the design doc's nREPL architecture.

#### Gaps (Design doc mentions, BDD does not cover)

1. **`info` / `lookup` op.** The design doc (line 111) lists `info` / `lookup` as a required op for "Symbol documentation -- return metadata for stdlib functions." No BDD scenario tests this. **Significant gap** -- users will expect to look up function docs in the REPL.

2. **Interrupt/cancel eval.** The design doc discusses `interruptible-eval` middleware (line 69). No BDD scenario tests interrupting a long-running evaluation. This is important for UX.

3. **`stdout` / `stderr` capture.** The nREPL protocol includes `:out` and `:err` streams. No scenario tests that `log!` or `tap!` output appears in the `:out` stream. This is critical for the REPL experience.

4. **Multiple response messages.** In nREPL, a single eval can produce multiple response messages (`:out`, `:value`, `:status`). No scenario tests the multi-message response flow. The current scenarios assume a single response.

5. **Session environment details.** The design doc (lines 152-163) details how session environments must persist the evaluator's env across evals. The BDD tests this functionally (binding persistence) but does not test edge cases like: what happens when a binding fails -- does the partial environment persist?

6. **`inspect-refresh` and `inspect-set-page-size`.** The design doc (lines 292-293) lists these as inspector ops. No BDD scenario.

7. **DataTwist session detection.** The design doc (line 100) says the middleware detects DataTwist sessions via `datatwist-session?`. No BDD scenario tests that non-DataTwist eval messages fall through to the default handler.

8. **Welcome response format.** BDD scenario (line 32) expects `:versions containing "nrepl" and "datatwist"`. The nREPL protocol's actual clone/describe response format may differ. This should be verified against the nREPL spec to ensure correctness.

#### Over-specification

None. The BDD scenarios are appropriately scoped.

---

## 3. Cross-Feature Issues

### 3.1 `is` Scope Name Inconsistency (Feature 11 vs Design Doc)

BDD feature 11 (line 20): `"is" has the highlight scope "keyword.operator.datatwist"`
Design doc TextMate grammar (line 709): `keyword.operator.binding.datatwist`

The TextMate grammar in the design doc uses the more specific `.binding` suffix. The BDD uses the generic `keyword.operator`. This means `is` would have the same scope as `and`, `or`, `not`, and `in` -- preventing theme differentiation. **Fix in feature 11.**

### 3.2 Inspector Duplicated Between Feature 11 and Feature 12

Feature 11 (LSP) Section 7 specifies an inspector with 5 scenarios.
Feature 12 (nREPL) Section 5 specifies the same inspector behaviors with 5 scenarios.

The functionality is identical (inspect-start, inspect-push, inspect-pop, list inspection, postfix colon keys). This creates confusion about which feature owns the inspector. The inspector is fundamentally an nREPL feature (it requires eval) -- it belongs in feature 12. Feature 11 should reference feature 12 or remove the duplication.

**Impact: 5 duplicate scenarios across features 11 and 12.**

### 3.3 Feature 8 Error Scenarios Overlap with Feature 9

Feature 8 Section 10 has 4 error scenarios (connection failure, file not found, query timeout, schema mismatch). Feature 9 Section 8 has 2 connection error scenarios (file not found, database connection failure). There is overlap in:
- File not found: Feature 8 line 776 vs Feature 9 line 357
- Database connection: Feature 8 line 767 vs Feature 9 line 365

The feature 8 scenarios focus on error recoverability (try-catch, nil-tolerance). The feature 9 scenarios focus on error formatting (error codes, no Java traces). They are complementary, not truly duplicated, but the overlap should be noted to avoid redundant test implementations.

### 3.4 PRD Scenario Count Claims Are Stale

The PRD (lines 482-490) claims:
- Feature 8: 105 scenarios -- Actual: 76
- Feature 9: 89 scenarios -- Actual: 48
- Features 10-12: Not mentioned

The PRD's BDD Feature Files section should be updated to reflect actual counts and include features 10-12.

### 3.5 `tap!` Behavior Specified in Both Feature 8 and Feature 4

Feature 8 has a dedicated `tap!` section (Section 4, 6 scenarios). Feature 4 (pipeline) may also reference `tap!`. The authoritative specification for `tap!` should be feature 8 since it relates to lazy evaluation semantics.

---

## 4. Recommendations

### Critical (Must fix before implementation)

1. **Feature 11: Fix `is` scope name** from `keyword.operator.datatwist` to `keyword.operator.binding.datatwist` to match the design doc TextMate grammar.

2. **Feature 12: Add `info`/`lookup` op scenarios.** Users will need function documentation in the REPL. At minimum: lookup a stdlib function, lookup a user-defined binding.

3. **Feature 11: Add signature help scenarios.** The design doc lists this as "Must have" for the LSP MVP. At minimum: show parameter names when cursor is inside a function call.

4. **Feature 12: Add `stdout`/`stderr` capture scenario.** Test that `log!` output appears in the `:out` stream. This is fundamental to REPL usability.

### Important (Should fix before implementation)

5. **Feature 8: Add `last` materialization scenario.** The design doc lists `last` as an explicit materializer. A simple scenario testing `data |> filter f |> last` is needed.

6. **Feature 8: Add aggregation materialization scenarios.** At least one scenario for `sum`, `average`, or `min`/`max` as materialization triggers.

7. **Feature 8: Add `dtw/set!` scenarios for other config keys.** At least test `describe-sample-size` and `max-collect-rows`.

8. **Feature 11: Deduplicate inspector from feature 12.** Move inspector scenarios to feature 12 only, or have feature 11 reference feature 12 explicitly.

9. **Update PRD scenario counts.** The BDD Feature Files section in PRD.md is stale and should be updated to reflect actual counts.

10. **Feature 12: Add interrupt/cancel eval scenario.** Long-running evaluations must be interruptible.

### Nice to Have (Low priority)

11. Feature 8: Add scenarios for `join`/`left-join` operations on lazy sources.
12. Feature 8: Add REPL infinite-sequence warning scenario.
13. Feature 9: Add multi-line error snippet display scenario.
14. Feature 10: Add `@expect` scenarios for non-scalar values (objects, lists).
15. Feature 11: Add `require`/`as` keyword highlighting scenario.
16. Feature 12: Add `inspect-refresh` and `inspect-set-page-size` scenarios.

---

## 5. Verdict

| Feature | Verdict | Rationale |
|---|---|---|
| 8: Lazy Eval & Data Sources | **GO** | 85% PRD coverage, no contradictions, gaps are minor (missing `last`, aggregation triggers). Well-aligned with design doc. |
| 9: Error Reporting | **GO** | 90% PRD coverage, no contradictions, well-structured error taxonomy. Minor formatting gaps. |
| 10: Demo Runner | **GO** | No PRD backing but internally consistent. Low-risk tooling feature. |
| 11: LSP Editor Support | **CONDITIONAL GO** | `is` scope name contradiction must be fixed. Signature help gap is significant (design doc "Must have"). Inspector duplication with feature 12 should be resolved. |
| 12: nREPL Integration | **CONDITIONAL GO** | Missing `info`/`lookup` op and `stdout` capture scenarios are significant for REPL usability. Must add before implementation. |

**Overall: CONDITIONAL GO.** Features 8, 9, 10 are ready for implementation. Features 11 and 12 need the critical fixes listed above before implementation begins. Estimated remediation effort: 1-2 hours of BDD authoring.
