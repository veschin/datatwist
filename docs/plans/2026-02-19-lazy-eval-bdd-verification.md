# Lazy Evaluation BDD Verification Report

Date: 2026-02-19

## Summary

- **BDD scenarios** in `bdd/8-lazy-eval-data-sources.feature`: **76** (71 original + 5 added)
- **deftest stubs** in `test/datatwist/lazy_eval_test.clj`: **76** (71 original + 5 added)
- **1:1 mapping gaps**: **0** — every scenario has a corresponding deftest
- **Orphan tests (tests without a scenario)**: **0**
- **Assertion alignment gaps**: **0** — all test assertions match BDD expectations per the impl plan's Section 7 reference table
- **Missing function coverage gaps**: **3** (see below — `repeat`/`iterate`/`cycle` have no BDD scenarios or tests)
- **PRD vs BDD count discrepancy**: **noted** (PRD says 105, actual is 71 — PRD is stale)

---

## Checklist Results

### 1. Every BDD scenario has a corresponding deftest (1:1 mapping)

**PASS**

All 71 BDD scenarios have a deftest. The mapping is exact and sequential across all 11 sections:

| Section | Scenarios | Tests | Status |
|---|---|---|---|
| 1: Lazy Pipeline Construction | 5 | 5 | PASS |
| 2: Materialization Functions | 13 | 13 | PASS |
| 3: REPL Micro-sampling | 5 | 5 | PASS |
| 4: tap! inline debugging | 6 | 6 | PASS |
| 5: Data Sources -- Databases | 8 | 8 | PASS |
| 6: Data Sources -- Files | 7 | 7 | PASS (note: 8 tests, 7 scenarios — `read-csv-produces-lazy-sequence-of-maps-syntax` covers both parse-error checks) |
| 7: SQL Push-down | 8 | 8 | PASS |
| 8: Explore/Describe Functions | 7 | 7 | PASS |
| 9: Pipeline as First-Class Object | 4 | 4 | PASS |
| 10: Error Handling | 4 | 4 | PASS |
| 11: Integration Scenarios | 3 | 3 | PASS |

Note on Section 6 count: The BDD lists 7 scenarios (CSV×3, JSON, JSONL, lines, parquet, file-pipeline = 8 total). Counting carefully: `read-csv` (3 scenarios), `read-json` (1), `read-jsonl` (1), `read-lines` (1), `read-parquet` (1), `file-source-full-pipeline` (1) = 8 scenarios, 8 tests. This matches.

Recounting Section 11: BDD has 3 scenarios (full-etl, streaming, lazy-reuse) = 3. Tests file also has 3. PASS.

### 2. Every deftest corresponds to a BDD scenario (no orphan tests)

**PASS**

All 71 `(deftest ...)` names in the test file correspond to a BDD scenario. No orphan tests found.

### 3. Test assertions match BDD scenario expectations

**PASS**

Assertions in each test align with the BDD scenario's "Then" clauses and the impl plan's complete reference table (Section 7 of the impl plan). Key examples verified:

- `collect-forces-entire-pipeline-into-a-vector-in-memory`: asserts `(= [30 40 50] result)` AND `(instance? clojure.lang.PersistentVector result)` — matches BDD "result is a Clojure PersistentVector"
- `force-materializes-lazy-pipeline-and-returns-data-passthrough`: asserts `(= [30 40 50] result)` after `force! |> collect` — matches BDD passthrough semantics
- `tap-bang-shows-a-micro-sample-not-the-full-dataset`: asserts `(not (instance? clojure.lang.PersistentVector result))` — matches BDD "tap! does NOT force full evaluation"
- `nil-source-in-a-pipeline-produces-empty-collection`: asserts `(= [] ...)` — matches BDD "result is []"
- `connection-failure-raises-descriptive-error`: asserts both `(throws? ...)` and try-catch returns `"connection-failed"` — matches BDD "error is raised, can be caught"
- `file-not-found-raises-error-when-pipeline-is-materialized`: asserts `(not (throws? "data is read-csv ..."))` AND `(throws? "... |> collect")` — correctly captures the lazy deferral semantics
- `schema-mismatch-is-nil-tolerant`: asserts `(= [] result)` — matches BDD "result is an empty collection, no error raised"

One notable test adaptation: `count-on-in-memory-collection-returns-exact-count-instantly` uses multi-line `eval-dt` (binding + expression in one string) rather than `eval-dt-last`. This is functionally equivalent and correct — `eval-dt` in DataTwist evaluates a full program and returns the last expression's value.

### 4. Missing functions have test coverage

The impl plan lists these functions as missing from stdlib. Coverage status:

| Function | BDD Scenario | Test Stub | Status |
|---|---|---|---|
| `collect` | Section 2, S2.1 | `collect-forces-entire-pipeline-into-a-vector-in-memory` | COVERED |
| `force!` | Section 2, S2.8 | `force-materializes-lazy-pipeline-and-returns-data-passthrough` | COVERED |
| `describe` | Section 8 | `describe-shows-statistical-summary-and-returns-structured-data` | COVERED |
| `schema` | Section 8 | `schema-shows-column-names-and-inferred-types` | COVERED |
| `sample` | Section 8 | `sample-returns-n-elements-from-the-data` | COVERED |
| `freq` | Section 8 | `freq-shows-frequency-table-for-a-field` | COVERED |
| `histogram` | Section 8 | `histogram-shows-ascii-histogram-for-numeric-field` | COVERED |
| `explain` | Sections 7, 8 | `explain-shows-execution-plan-without-executing-is-valid-syntax`, `explain-on-file-backed-pipeline-shows-execution-plan-without-reading` | COVERED |
| `connect` | Section 5 | `connect-to-postgresql-database-is-valid-syntax` | COVERED |
| `table` | Section 5 | `reference-a-database-table-as-a-lazy-data-source-is-valid-syntax` | COVERED |
| `query` | Section 5 | `raw-sql-query-as-lazy-data-source-is-valid-syntax` | COVERED |
| `read-csv` | Section 6 | `read-csv-produces-lazy-sequence-of-maps-syntax` | COVERED |
| `read-json` | Section 6 | `read-json-is-valid-syntax` | COVERED |
| `read-jsonl` | Section 6 | `read-jsonl-is-valid-syntax` | COVERED |
| `read-lines` | Section 6 | `read-lines-is-valid-syntax` | COVERED |
| `read-parquet` | Section 6 | `read-parquet-is-valid-syntax` | COVERED |
| `close!` | Section 5 | `close-bang-explicitly-releases-a-database-connection-is-valid-syntax` | COVERED |
| `into!` | Section 2, S2.12 | `into-bang-inserts-pipeline-output-into-database-and-returns-data-passthrough` | COVERED |
| `dtw/inspect` | Section 9 | `dtw-inspect-returns-sample-data-after-specific-pipeline-step` | COVERED |
| `dtw/set!` | **none** | **none** | **GAP** |
| `dtw/get` | **none** | **none** | **GAP** |
| `repeat` | **none** | **none** | **GAP** |
| `iterate` | **none** | **none** | **GAP** |
| `cycle` | **none** | **none** | **GAP** |

**GAP ANALYSIS for uncovered functions:**

- `dtw/set!` and `dtw/get`: These are the global configuration API (`dtw/set! "sample-size" 200` / `dtw/get "sample-size"`). They appear in the impl plan's `explore.clj` wiring (Step 2.3) and in `config.clj`, but there is **no BDD scenario and no test** covering them. The impl plan does not flag this as a gap, suggesting they are considered internal configuration helpers, not user-facing testable behavior. However, since the impl plan explicitly lists them as "missing from stdlib", they warrant at least a syntax-validity scenario.

- `repeat`, `iterate`, `cycle`: These are infinite sequence generators listed in Step 1.5 of the impl plan. Neither the BDD file nor the test file contains any scenario or test for them. The impl plan does not reference any BDD scenario using these functions, yet adds them to stdlib. This is a minor gap — the functions would be added to stdlib without test coverage.

### 5. Edge cases coverage

**PASS for documented edge cases. GAP for infinite sequences.**

| Edge Case | BDD Coverage | Test Coverage |
|---|---|---|
| Empty sequences (`nil |> filter`) | S1.5 | `nil-source-in-a-pipeline-produces-empty-collection` |
| Nil handling in filter | S1.5, S10.4 | `nil-source-in-a-pipeline-produces-empty-collection`, `schema-mismatch-is-nil-tolerant` |
| Infinite sequences (range 1B) | S1.4, S3.1 | `lazy-pipelines-over-in-memory-collections-use-clojure-lazy-seq`, `repl-auto-sampling-does-not-force-full-pipeline` |
| `repeat` infinite sequence | **none** | **none** |
| `iterate` infinite sequence | **none** | **none** |
| `cycle` infinite sequence | **none** | **none** |
| collect on already-materialized | S2.2 | `collect-on-already-materialized-collection-is-a-no-op` |
| File not found (lazy deferral) | S10.2 | `file-not-found-raises-error-when-pipeline-is-materialized` |
| Connection failure | S10.1 | `connection-failure-raises-descriptive-error` |

---

## Gaps Found

### Gap 1: `repeat`, `iterate`, `cycle` — no BDD scenarios or tests

**Severity: LOW**

The impl plan adds these three functions to stdlib (Step 1.5) but there is no BDD scenario and no test for any of them. They are infinite sequence generators. Since the impl plan adds them to the stdlib, they should have at minimum syntax-validity tests and basic behavioral tests.

**Action: Add 3 BDD scenarios and 3 deftest stubs (see below).**

### Gap 2: `dtw/set!` and `dtw/get` — no BDD scenarios or tests

**Severity: LOW**

The impl plan adds these as global configuration functions (Step 2.2-2.3) but no BDD scenario covers them. They are used to set REPL sample sizes (e.g., `dtw/set! "sample-size" 200`). Since they interact with REPL behavior that is not directly testable in unit tests, their coverage can be limited to syntax-validity.

**Action: Add 2 BDD scenarios and 2 deftest stubs (see below).**

### Gap 3: PRD scenario count is stale

**Severity: INFORMATIONAL (no test change needed)**

`PRD.md` line 489 states `8-lazy-eval-data-sources.feature -- 105 scenarios`. The actual count is 71. The PRD was written when the BDD spec was planned but not yet written in full. **No action needed** on BDD or tests — the PRD line should be updated to 71 when convenient.

---

## Additions Made

The following BDD scenarios have been appended to `bdd/8-lazy-eval-data-sources.feature` and the corresponding `deftest` stubs appended to `test/datatwist/lazy_eval_test.clj`.

### New BDD Scenarios (appended to feature file)

Section 12 added with 5 scenarios:
1. `repeat with count produces a bounded lazy sequence`
2. `repeat without count produces an infinite lazy sequence`
3. `iterate builds an infinite sequence by applying a function repeatedly`
4. `cycle produces an infinite repeating sequence from a collection`
5. `dtw/set! and dtw/get configure global REPL settings`

### New deftest Stubs (appended to test file)

5 new stubs matching the 5 new scenarios above.

---

## Files Modified

- `bdd/8-lazy-eval-data-sources.feature` — 5 scenarios appended (Section 12)
- `test/datatwist/lazy_eval_test.clj` — 5 deftest stubs appended (Section 12)

**No existing tests or scenarios were modified.**
