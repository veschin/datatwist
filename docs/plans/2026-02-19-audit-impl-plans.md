# Implementation Plan Audit Report

**Date:** 2026-02-19
**Auditor:** Claude Opus (automated audit)
**Scope:** Lazy Eval plan, Error Reporting plan, Demo Runner readiness

---

## 1. Summary

| Plan | Verdict | Readiness |
|---|---|---|
| Lazy Eval (`2026-02-19-lazy-eval-impl-plan.md`) | **GO** (with minor fixes) | 92% — actionable as-is for Phases 1-4 |
| Error Reporting (`2026-02-19-error-reporting-impl-plan.md`) | **GO** (with minor fixes) | 90% — actionable as-is for Steps 1-9 |
| Demo Runner | **NEEDS FORMAL PLAN** | 40% — BDD exists, BACKLOG has bullets, but no impl plan |

Both implementation plans are detailed, well-structured, and contain enough code sketches for a Sonnet agent to implement without getting stuck. The issues found are minor — count mismatches, a few missing test mappings, and one cross-plan conflict on `read-csv`/`connect` stubs.

---

## 2. Lazy Eval Plan

### 2.1 Completeness Score: 92%

**BDD reality:** 76 scenarios (not 71 as the plan states). **Test reality:** 78 deftests (not 71).

The plan was written before 5 additional BDD scenarios were added (Section 12: `repeat`, `iterate`, `cycle`, `dtw/set!`, `dtw/get`). The plan *does* cover the implementation for all 5 in Steps 1.5 and 2.2-2.3, but the Section 7 "Test Stub to BDD Mapping" table stops at Section 11 and omits these 5 tests. A Sonnet agent following Section 7 as the definitive reference would miss them.

Additionally, there are 78 deftests vs 76 BDD scenarios, meaning 2 extra tests exist without direct BDD counterparts. These appear to be duplicate/variant stubs (likely for `read-csv` syntax tests that have 2 `(not (parse-error? ...))` assertions in a single BDD scenario).

**All 76 BDD scenarios have a clear implementation path.** No scenario lacks coverage.

### 2.2 Ambiguities Found

**A1: `tap!` on non-sequential data.**
The plan's new `tap!` implementation calls `(take 100 data)` unconditionally. If `data` is a scalar (e.g., an integer from `count`), `(take 100 42)` throws `IllegalArgumentException`. The current `tap!` does `(println data)` which works for any type. The new implementation must handle non-sequential data:
```clojure
(if (sequential? data) (take 100 data) [data])
```
The plan does not mention this edge case.

**A2: `repeat` shadowing Clojure core.**
The plan defines `"repeat" (fn ([v] (repeat v)) ([n v] (repeat n v)))`. Inside the anonymous function body, the call to `repeat` is an infinite self-recursion because the local binding `"repeat"` shadows `clojure.core/repeat`. The code sketch must use fully-qualified `clojure.core/repeat`. Same issue for `iterate` and `cycle`. The plan's Step 1.5 code sketch would cause `StackOverflowError`.

**A3: `force!` semantics vs `collect` — unclear to agent.**
The plan says "`force!` is passthrough — it returns the materialized data" and "the difference from `collect` is semantic intent, not behavior in Phase 1." A Sonnet agent may not understand why two identical functions exist. Should add: "`force!` is a bang-function (side-effect marker) that returns materialized data and is intended for use in mid-pipeline to force computation at a specific point. `collect` is a terminal materializer. Both produce a `PersistentVector` in Phase 1."

**A4: `save!` JSON/CSV writing dependencies.**
The plan's Phase 3 Step 3.4 uses `json/write-str` and `write-csv` without specifying where these come from. The dependencies section mentions `clojure.data.json` and `clojure.data.csv` as potential deps but does not commit to adding them to `deps.edn`. A Sonnet agent implementing Phase 3 would not know whether to add these deps or implement manual serialization.

### 2.3 Missing Details

**M1: `dt-concat` lazy change not in step list.**
The plan's Step 1.1 table says `dt-concat` should change from `(vec (apply concat colls))` to `(apply concat colls)`. But `dt-concat` is listed under "Also keep eager" with the note "Change to `(apply concat colls)` -- concat should be lazy." This is contradictory. The table says keep eager, then says make lazy. The correct answer is: make it lazy (concat is inherently lazy in Clojure). This needs clarification.

**M2: `rest` lazy change — function signature.**
The plan says change `(comp vec rest)` to `rest`. But `rest` in stdlib is bound as `"rest" (comp vec rest)` (line 286 in stdlib.clj). Simply binding `"rest" rest` is correct, but the plan should note that `rest` returns a seq (possibly empty `()`), not a vector. Tests using `(= [] (eval-dt "rest [1]"))` will fail because `(= [] ())` is true in Clojure but `(rest [1])` returns `()`, not `[]`. Actually `(= [] ())` IS true in Clojure, so this is fine. No issue.

**M3: No test command for Section 12 (repeat/iterate/cycle/config).**
The plan gives a test run command for the full lazy eval test file but doesn't call out that Section 12 tests exist.

### 2.4 File Reference Accuracy

All file paths and line numbers verified against the current codebase:
- `src/datatwist/stdlib.clj` line references: **Accurate** (dt-filter at 130-133, dt-map at 135-145, dt-take at 120-123, dt-drop at 125-128, dt-distinct at 94-95, dt-flatten at 91-92, range at 372-375, rest at 286, tap! at 389-390)
- `src/datatwist/evaluator.clj` eval-pipeline at line 1378: **Accurate**
- `src/datatwist/evaluator.clj` evaluate at line 1503: **Accurate**
- `test/datatwist/data_structures_test.clj` line 659 `vector?` check: **Accurate**

### 2.5 Edge Cases

**Covered well:**
- Nil handling in `dt-filter` and `dt-map` (plan addresses both)
- `(= [] result)` across lazy seq and vector types
- Clojure chunking with `tap!`
- File handle leaks with `read-csv`
- `range` performance with `count` after `filter`
- `repeat` argument order ambiguity

**Not covered:**
- What happens when `collect` is called on a map (not a seq)? `(vec {:a 1})` returns `[[:a 1]]`. Should `collect` handle this case?
- What happens when `describe`/`schema`/`freq` receive an empty collection?
- Thread safety of the config atom (`dtw/set!`/`dtw/get`) is not mentioned.

### 2.6 Dependencies

Dependencies are clearly stated. The ordering constraint diagram is correct. Phase 1 has zero new deps. Phases 2-3 external deps are identified but **not resolved** (should `clojure.data.json` be added to `deps.edn`?).

### 2.7 Testing Strategy

Strong. Each phase has:
- A targeted test command: `clj -M -e "(require 'clojure.test 'datatwist.lazy-eval-test) (clojure.test/run-tests 'datatwist.lazy-eval-test)"`
- A regression check: `make test`
- Clear test expectations per BDD scenario in the Section 7 mapping table

**Gap:** Section 12 tests (repeat/iterate/cycle/config) are not in the mapping table.

### 2.8 Recommendations

1. **Fix Section 7 test mapping:** Add Section 12 (repeat, iterate, cycle, dtw/set!, dtw/get) — 5 tests with their assertions.
2. **Fix Step 1.5 code sketch:** Use `clojure.core/repeat`, `clojure.core/iterate`, `clojure.core/cycle` to avoid self-recursion.
3. **Add `tap!` scalar handling:** Guard `(take 100 data)` with `(sequential? data)` check.
4. **Clarify `dt-concat`:** Remove from "Also keep eager" or explicitly state it becomes lazy.
5. **Resolve Phase 3 deps:** Decide on `clojure.data.json` / `clojure.data.csv` and document the `deps.edn` change.
6. **Update counts:** Change "71 scenarios" to "76 scenarios" and "71 deftest stubs" to "78 deftests" in Current State section.

---

## 3. Error Reporting Plan

### 3.1 Completeness Score: 90%

**BDD reality:** 48 scenarios (not 38 as the plan states). **Test reality:** 49 deftests (not 38).

The plan was written before 10 additional BDD scenarios were added in Section 10 ("Additional Runtime Error Coverage — Gap fill") and 1 extra test in Section 6. The plan's error code taxonomy (Section 3) already defines DT-P001, DT-R002, DT-R003, DT-R004, DT-R005 which are exactly the codes needed for the new Section 10 scenarios. So the implementation path exists — it just is not explicitly called out as a step.

The extra Section 6 test (`data-warning-execution-continues-after-nil-warning`) is a dedicated test for BDD scenario "Data warning - execution continues after nil warning" (BDD line 300). The plan's Step 7 covers warning infrastructure but does not map this specific test.

**All 48 BDD scenarios have a clear implementation path** through the plan's 9 steps.

### 3.2 Ambiguities Found

**A1: `evaluate` nil-vs-throw behavioral change.**
This is the highest-risk change in the plan. Currently `evaluate` returns `nil` on parse failure. The plan says it must throw. Risk 6.2 identifies this but the mitigation is vague: "Add a separate entry point `evaluate-with-errors`... Or make the existing `evaluate` throw... The latter is preferred."

A Sonnet agent needs a concrete decision:
- If `evaluate` throws, then `eval-dt` (in test_helpers.clj) also throws, which changes behavior for `throws?` calls on parse errors.
- The plan does not specify how `eval-dt-last` (which joins lines with `\n` and calls `evaluate`) behaves if an intermediate line has a parse error.
- The plan does not audit all existing test files for calls to `eval-dt` with invalid input that currently returns `nil` silently.

**Recommendation:** The plan should explicitly list the callers of `evaluate` and state the expected behavior change for each.

**A2: Warning test infrastructure — `warnings-for` helper shape.**
Step 9 mentions adding `(warnings-for source)` to test_helpers.clj but does not define its return type or how it integrates with the `*warnings*` dynamic var in Step 7. The warning system uses `binding [*warnings* (atom [])]` inside `evaluate`, but `eval-dt` in test_helpers calls `parser/eval-dt` which calls `evaluator/evaluate`. The Sonnet agent needs to know:
- Does `evaluate` return `{:result R :warnings W}` (breaking change) or does it return `R` and stash warnings somewhere?
- How does `warnings-for` access the warnings — via metadata, a separate atom, or a modified return type?

The plan sketches `(with-meta {:result result :warnings ws} {:dt/warnings ws})` in Step 7, but this changes `evaluate`'s return type from a value to a map — which breaks every caller.

**Recommendation:** Clarify the warning return channel. Options: (a) dynamic var read after `evaluate` returns, (b) metadata on result, (c) separate `evaluate-with-warnings` function. Specify which one.

**A3: Parse error regex patterns — capture group usage.**
Step 5 defines `common-mistakes` patterns like `{:pattern #"^(\w+)\s*=\s*" ...}`. The hint uses `%s` format: `"Use 'is' for assignment: %s is ..."`. The `%s` presumably refers to capture group 1, but the plan doesn't show the code that extracts the capture group and formats the hint. A Sonnet agent would need to implement this.

**Recommendation:** Add a code sketch showing how `re-find` results feed into `format`.

**A4: Division by zero — backward compatibility with `literals_test.clj`.**
The plan correctly identifies that lines 155 and 168 of `literals_test.clj` assert `ArithmeticException` type. It recommends updating to `ExceptionInfo` or relaxing to `throws?`. But it does not specify which approach to take. A Sonnet agent would need to choose.

**Recommendation:** State the decision: change both tests to `(throws? ...)` (simplest, forward-compatible). Also address the modulo-by-zero path at evaluator line 474-476 which the plan mentions but does not include in the Step 2 table.

### 3.3 Missing Details

**M1: Modulo by zero not in Step 2 table.**
The plan mentions modulo-by-zero at evaluator line 474-476 in Risk 6.1 but does not include it in the Step 2 "Specific sites to update" table. It must be wrapped in `ex-info` with code `DT-T003` just like division by zero.

**M2: DT-R010 (`filter`/`map` on non-collection) not in BDD.**
The error code taxonomy defines `DT-R010` for "`filter` expects a list, got `<type>`" but there is no BDD scenario requiring this specific code. The BDD has "Runtime error - map over non-collection" (scenario line 266) which currently throws `ex-info` from `dt-map` in stdlib.clj. The plan should clarify whether this error should get code `DT-R010` or remain as-is.

**M3: Source snippet rendering — `col-start` and `col-end` for runtime errors.**
Step 4 (source location tracking) uses Approach A: store full source in `*source*`, compute line/col from character offset. But runtime errors don't have character offsets — they happen during evaluation, not parsing. The plan says "compute line/col from character offsets when an error is thrown" but evaluator error sites (line 32, 248, 398, etc.) don't know the character offset of the source expression they're evaluating.

For the BDD test `error-output-source-snippet-and-hint` (line 151), the assertion is that the error output includes a source snippet. Without source location data, the renderer cannot produce a `^` underline. The plan should clarify: does the renderer fall back to showing the full source line without `^` underline for runtime errors? Or does the evaluator need to thread Instaparse metadata?

**M4: New BDD Section 10 tests not mapped to implementation steps.**
The 5 gap-fill scenarios (DT-P001 fallback, DT-R002 through DT-R005) in BDD Section 10 have corresponding test stubs, but the plan does not mention them as targets. Step 2 does cover the evaluator error sites for R002-R005. Step 5 covers P001 (generic fallback). But a Sonnet agent reading the plan linearly would not know these 5 extra tests exist.

**M5: `error-code-format` test (line 181) checks `:code` field existence.**
This test requires ALL errors to have a `:code` field in `ex-data`. The plan's Step 2 adds codes to evaluator sites, but the test checks that `error-data` returns a map with `:code` matching `#"^DT-[A-Z]\d{3}$"`. If any error path misses a `:code`, this test fails. The plan should list this test explicitly as a cross-cutting verification.

### 3.4 File Reference Accuracy

All file paths and line numbers verified:
- `src/datatwist/evaluator.clj` line 32 (nil-as-function): **Accurate**
- `src/datatwist/evaluator.clj` line 34 (not-a-function): **Accurate**
- `src/datatwist/evaluator.clj` line 248 (undefined identifier): **Accurate**
- `src/datatwist/evaluator.clj` line 398 (comparison type mismatch): **Accurate**
- `src/datatwist/evaluator.clj` line 427 (addition type mismatch): **Verified at line 420-427 area, accurate**
- `src/datatwist/evaluator.clj` line 471 (division by zero `ArithmeticException.`): **Accurate**
- `src/datatwist/evaluator.clj` line 702 (object destructuring): **Accurate**
- `src/datatwist/evaluator.clj` line 910 (no matching arity): **Accurate**
- `src/datatwist/evaluator.clj` line 1344 (pipeline step not a function): **Accurate**
- `test/datatwist/literals_test.clj` lines 155, 168 (ArithmeticException assertions): **Accurate**
- `src/datatwist/parser.clj` (24 lines): **Accurate** (24 lines total)

### 3.5 Edge Cases

**Covered well:**
- Division by zero backward compatibility
- Instaparse failure format stability (pinned at 1.5.0)
- Warning accumulation performance (cap at 10)
- Regex false positives (only run on already-failed parses)
- ANSI color contamination in tests

**Not covered:**
- Multi-line source snippets: if the error is on line 3 of a 5-line program, how many context lines does the renderer show?
- Nested errors: if a pipeline step throws DT-T001 inside a try-catch, does the catch clause see the structured error map?
- Error in `with-meta` return value for warnings: what if the result is a primitive (Long, String) that cannot carry metadata?

### 3.6 Dependencies

Step dependency graph is clear and correct. Parallelization opportunities are identified (Steps 7-8 independent of 5-6). The suggested single-agent order is reasonable.

**Cross-dependency with lazy eval plan:** Step 8 adds `read-csv` and `connect` stubs to stdlib. The lazy eval plan Phase 3 also adds these with different implementations. See Cross-Plan Issues (Section 5).

### 3.7 Testing Strategy

Strong. The plan specifies:
- Targeted test command with correct namespace (`datatwist.error-reporting-test`)
- Full regression check (`make test`)
- A clear note about "vacuously passing" tests moving to "substantively passing"
- Step 9 explicitly addresses strengthening `when` guards to `is` assertions

**Gap:** The 11 additional tests (10 from BDD Section 10, 1 from Section 6 gap-fill) are not mentioned in the plan's test verification section.

### 3.8 Recommendations

1. **Update counts:** Change "38 scenarios" to "48 scenarios" and "38 deftest blocks" to "49 deftests."
2. **Decide `evaluate` throw-vs-nil:** State explicitly: "`evaluate` will throw on parse failure. `eval-dt` in test_helpers inherits this behavior. Existing tests that call `throws?` on invalid syntax will now return `true` (currently return `false`). Grep for `eval-dt` calls with invalid input across all test files to verify no silent regressions."
3. **Clarify warning return channel:** Choose one: dynamic var with `(binding [*warnings* (atom [])] ...)` where a new `evaluate-with-warnings` returns `{:result R :warnings W}`, or `evaluate` returns R and `*warnings*` is read by `warnings-for` directly.
4. **Add modulo-by-zero to Step 2 table:** Evaluator line 474-476, wrap in `ex-info` with `DT-T003`.
5. **Add capture group formatting to Step 5:** Show `(let [[_ name] (re-find pattern input)] (format hint name))`.
6. **Decide on `literals_test.clj` update:** Change both assertions to `(throws? ...)`.
7. **Add Section 10 gap-fill tests to plan scope:** Mention that 5 additional BDD scenarios were added and are covered by existing Steps 2+5.
8. **Clarify source location for runtime errors:** State that Approach A provides the full source line as `:source` but `^` underline is only available for parse errors (where Instaparse provides exact column). Runtime errors show the source line without column-level underline.

---

## 4. Demo Runner

### 4.1 Current State

- **BDD:** `bdd/10-demo-runner.feature` exists with **22 scenarios** across 7 sections (file loading, section markers, expression extraction, evaluation, `@expect` annotations, error handling, formatted output).
- **BACKLOG:** 6 bullet points describing the feature at a high level.
- **No implementation plan exists.**
- **No test stubs exist** (no `test/datatwist/demo_runner_test.clj` found).

### 4.2 Is BDD + BACKLOG Sufficient?

**No.** The BDD is well-specified and the BACKLOG captures the intent, but a Sonnet agent would face several blockers:

1. **No existing `demo_runner.clj` audit:** The BACKLOG says "Remove hardcoded demo data from `demo_runner.clj`" — does this file exist? What does it currently do? The plan should describe the current state.
2. **No file structure decisions:** Should the parser/evaluator be called directly, or should the demo runner go through `eval-dt`? How does "expression-by-expression evaluation with shared context" work when `evaluate` only handles a full program?
3. **No code sketches for section parsing:** The `// @section` and `// @expect` annotations need a parser. Is this regex-based or does it use the grammar?
4. **No formatted output spec:** The BDD says "formatted terminal output" but the exact format is not defined in either the BDD or BACKLOG.
5. **No test stubs:** Unlike Features 8-9, there are no test files for the demo runner.

### 4.3 Recommendation

**Write a formal implementation plan before assigning to a Sonnet agent.** The plan should:
- Audit the current `demo_runner.clj` (if it exists)
- Define the section/expression parser (regex-based, operating on raw text before DataTwist parsing)
- Define how shared evaluation context works (accumulating `env` across expressions)
- Define formatted output structure
- Create test stubs (1:1 with BDD scenarios)
- Estimate effort (likely 2-3 days)

---

## 5. Cross-Plan Issues

### 5.1 `read-csv` and `connect` Stub Conflict (CRITICAL)

Both plans add `read-csv` and `connect` to stdlib:

| Function | Lazy Eval Plan | Error Reporting Plan |
|---|---|---|
| `read-csv` | Phase 3, Step 3.2: Full lazy implementation with `line-seq` + `io/reader`, deferred into `lazy-seq` | Step 8: Stub that throws `DT-C001` "File not found" for missing files, throws "not implemented" otherwise |
| `connect` | Phase 3, Step 3.1: Stub that throws "database support not yet implemented" | Step 8: Stub that throws `DT-C002` "Connection failed" with structured error map |

**Conflict:** If error reporting is implemented first (likely, since it's simpler), the `read-csv` stub will throw immediately. The lazy eval plan requires `read-csv` to return a lazy sequence (no throw on construction) and only throw on materialization. The error reporting stub breaks this contract.

**Resolution:** Implement lazy eval Phase 1 first (core laziness), then error reporting Steps 1-7 (error infrastructure), then lazy eval Phase 3 (data source stubs with lazy `read-csv`), then error reporting Step 8 (structured error codes on the Phase 3 stubs). Or: make the error reporting stub lazily throw by wrapping in `lazy-seq`:
```clojure
"read-csv" (fn [path]
             (lazy-seq
               (if (.exists (java.io.File. (str path)))
                 (throw (ex-info "read-csv not yet implemented" {:code "DT-C001"}))
                 (throw (ex-info (str "File not found: " path)
                                 {:dt/error true :code "DT-C001"})))))
```

### 5.2 `evaluate` Return Type Change

The error reporting plan's Step 7 (warning system) changes `evaluate` to potentially return `{:result R :warnings W}` instead of just `R`. This would break:
- All test helpers in `test_helpers.clj` (`eval-dt`, `eval-dt-last`, `throws?`, etc.)
- The lazy eval plan, which assumes `evaluate` returns the raw value

**Resolution:** As recommended in Section 3.8 item 3, use a dynamic var for warnings rather than changing the return type.

### 5.3 Feature Branch Strategy

Per CLAUDE.md: "Implementation agents work in feature branches (`feat/<name>`)." The two plans should use separate branches:
- `feat/lazy-eval` for the lazy eval plan
- `feat/error-reporting` for the error reporting plan

Since both modify `src/datatwist/stdlib.clj`, merge conflicts are inevitable. **Recommended merge order:** lazy eval Phase 1 first (smaller diff), then error reporting, then lazy eval Phases 2-4.

### 5.4 `throws-type?` Dependency

The lazy eval plan's Section 10 test `file-not-found-raises-error-when-pipeline-is-materialized` asserts `(throws-type? ... FileNotFoundException)`. If the error reporting plan wraps all Java exceptions in `ex-info` (Step 3: Java exception boundary), then `FileNotFoundException` would be wrapped and the `throws-type?` check would fail (it would see `ExceptionInfo` instead).

**Resolution:** The error reporting plan's exception boundary catches `ArithmeticException`, `ClassCastException`, `NullPointerException` — it does NOT catch `FileNotFoundException`. So this is not a conflict currently. But if the boundary is ever expanded, this test would break. Document this dependency.

---

## 6. Final Verdict

| Plan | Verdict | Blocking Items |
|---|---|---|
| **Lazy Eval** | **GO** | Fix Step 1.5 code sketch (self-recursion bug). Add tap! scalar guard. Update counts. |
| **Error Reporting** | **GO** | Decide evaluate throw-vs-nil. Clarify warning return channel. Add modulo-by-zero to Step 2. Update counts. |
| **Demo Runner** | **NEEDS PLAN** | Write a formal implementation plan with test stubs before assigning. |

**Recommended implementation order:**
1. Lazy Eval Phase 1 (core laziness) — enables ~30 tests, unblocks everything
2. Error Reporting Steps 1-6 (error infrastructure, no warnings yet) — enables ~25 tests
3. Lazy Eval Phase 2 (exploration functions) — enables ~7 tests
4. Error Reporting Steps 7-9 (warnings, stubs, test strengthening) — enables ~13 tests
5. Lazy Eval Phase 3-4 (data sources, error handling) — enables ~25 tests
6. Demo Runner (after plan is written)

This order minimizes merge conflicts and ensures each plan's changes are verified before the other plan builds on them.
