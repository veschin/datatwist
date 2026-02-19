# Audit: BDD-to-Test 1:1 Mapping for Features 8, 9, 10

**Date:** 2026-02-19
**Scope:** Verify every BDD scenario has exactly one `deftest`, and vice versa.

---

## 1. Summary

| Feature | BDD Scenarios | deftests | Mismatches |
|---------|--------------|----------|------------|
| 8 - Lazy Eval & Data Sources | 76 | 76 | 7 naming deviations |
| 9 - Error Reporting | 48 | 48 | 8 naming deviations, 1 structural mismatch |
| 10 - Demo Runner | 22 | 22 | 1 naming deviation |
| **Total** | **146** | **146** | **16 issues** |

Counts are 1:1 across all three features (no orphan tests, no missing tests).
All deviations are naming or structural -- no scenarios are unrepresented.

---

## 2. Test Suite Status

### Main test runner (`make test` / `clj -M -m datatwist.test-runner`)

```
Ran 554 tests containing 1271 assertions.
0 failures, 0 errors.
```

**PASS** -- all 554 tests green.

### Feature 8: `lazy_eval_test.clj` (NOT in test runner)

```
Ran 76 tests containing 95 assertions.
5 failures, 29 errors.
```

This is expected: tests are stubs/TDD targets for unimplemented lazy eval features.

**IMPORTANT:** `datatwist.lazy-eval-test` is NOT registered in `test/datatwist/test_runner.clj`. It is not run by `make test`.

### Feature 9: `error_reporting_test.clj` (in test runner)

Part of the main test suite. All tests pass.

### Feature 10: `demo_runner_test.clj` (NOT in test runner)

```
Ran 22 tests containing 22 assertions.
0 failures, 0 errors.
```

All 22 tests pass because they are trivial stubs (`(is (= 1 1))`).

**IMPORTANT:** `datatwist.demo-runner-test` is NOT registered in `test/datatwist/test_runner.clj`. It is not run by `make test`.

---

## 3. Feature 8: Lazy Eval & Data Sources

**BDD file:** `bdd/8-lazy-eval-data-sources.feature`
**Test file:** `test/datatwist/lazy_eval_test.clj`

### Section 1: Lazy Pipeline Construction (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 1 | A pipeline without materialization is lazy and does not execute | `pipeline-without-materialization-is-lazy-and-does-not-execute` | MATCH |
| 2 | Chaining multiple lazy operations builds a deeper plan | `chaining-multiple-lazy-operations-builds-a-deeper-plan` | MATCH |
| 3 | Binding a lazy pipeline to a name does not force evaluation | `binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation` | MATCH |
| 4 | Lazy pipelines over in-memory collections use Clojure lazy-seq | `lazy-pipelines-over-in-memory-collections-use-clojure-lazy-seq` | MATCH |
| 5 | Nil source in a pipeline produces an empty collection | `nil-source-in-a-pipeline-produces-empty-collection` | MATCH |

### Section 2: Materialization Functions (8 scenarios, 8 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 6 | collect forces entire pipeline into a vector in memory | `collect-forces-entire-pipeline-into-a-vector-in-memory` | MATCH |
| 7 | collect on an already-materialized collection is a no-op | `collect-on-already-materialized-collection-is-a-no-op` | MATCH |
| 8 | count forces full traversal and returns exact count | `count-forces-full-traversal-and-returns-exact-count` | MATCH |
| 9 | count on an in-memory collection returns exact count instantly | `count-on-in-memory-collection-returns-exact-count-instantly` | MATCH |
| 10 | first forces evaluation until one element is found | `first-forces-evaluation-until-one-element-is-found` | MATCH |
| 11 | reduce folds the pipeline into a single value | `reduce-folds-the-pipeline-into-a-single-value` | MATCH |
| 12 | reduce with explicit initial value | `reduce-with-explicit-initial-value` | MATCH |
| 13 | force! materializes a lazy pipeline and returns the data (passthrough) | `force-materializes-lazy-pipeline-and-returns-data-passthrough` | **NAMING** |
| 14 | force! is useful for ensuring computation happens at a specific point | `force-bang-is-useful-for-ensuring-computation-happens-at-specific-point` | **NAMING** |

**Naming note (13):** BDD has `force!` but deftest drops the `!` and uses `force-materializes...` instead of `force-bang-materializes...`. Inconsistent with test 14 which uses `force-bang-...`. The `!` to `-bang` convention is used elsewhere.

### Section 3: Side-Effect Sinks (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 15 | save! writes pipeline output to a file and returns the data (passthrough) | `save-bang-writes-pipeline-output-to-file-and-returns-data-passthrough` | MATCH |
| 16 | save! supports multiple file formats determined by file extension | `save-bang-supports-multiple-file-formats-determined-by-file-extension` | MATCH |
| 17 | into! inserts pipeline output into a database table and returns data (passthrough) | `into-bang-inserts-pipeline-output-into-database-and-returns-data-passthrough` | **NAMING** |
| 18 | Chaining after a materialization function starts a new pipeline | `chaining-after-materialization-starts-a-new-pipeline` | MATCH |

**Naming note (17):** BDD says "database table" but deftest says "database" (drops "table"). Minor.

### Section 4: REPL Micro-sampling (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 19 | REPL auto-samples a lazy pipeline for preview | `repl-auto-sampling-does-not-force-full-pipeline` | **NAMING** |
| 20 | REPL preview shows tabular format for collections of objects | `repl-preview-shows-tabular-format-for-collections-of-objects` | MATCH |
| 21 | REPL shows full result for small collections that fit in sample | `repl-shows-full-result-for-small-collections-that-fit-in-sample` | MATCH |
| 22 | REPL preview for a scalar result shows the value directly | `repl-preview-for-scalar-result-shows-value-directly` | MATCH |
| 23 | REPL preview uses first-N sampling strategy by default | `repl-uses-first-n-sampling-strategy-by-default` | MATCH |

**Naming note (19):** BDD is "auto-samples a lazy pipeline for preview" but deftest is "auto-sampling-does-not-force-full-pipeline". The `testing` string inside correctly references the BDD scenario.

### Section 5: tap! Debugging (6 scenarios, 6 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 24 | tap! shows data at a pipeline step and passes it through unchanged | `tap-shows-data-at-pipeline-step-and-passes-it-through-unchanged` | MATCH |
| 25 | tap! with a label for clarity | `tap-with-a-label-passes-data-through-unchanged` | **NAMING** |
| 26 | tap! shows a micro-sample, not the full dataset | `tap-bang-shows-a-micro-sample-not-the-full-dataset` | MATCH |
| 27 | tap! returns its input unchanged (passthrough) | `tap-returns-its-input-unchanged-passthrough` | MATCH |
| 28 | tap! with a transformation function for focused inspection | `tap-with-transformation-function-does-not-affect-pipeline-data` | **NAMING** |
| 29 | Multiple tap! calls in one pipeline each show data at their respective point | `multiple-tap-calls-each-show-data-at-their-respective-point` | MATCH |

**Naming note (25):** BDD is "tap! with a label for clarity" but deftest is `tap-with-a-label-passes-data-through-unchanged`. The deftest emphasizes the passthrough property rather than the BDD scenario name.

**Naming note (28):** BDD is "tap! with a transformation function for focused inspection" but deftest is `tap-with-transformation-function-does-not-affect-pipeline-data`. Similar: deftest emphasizes a property rather than using BDD name.

### Section 6: Database Sources (8 scenarios, 8 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 30 | Connect to a PostgreSQL database | `connect-to-postgresql-database-is-valid-syntax` | MATCH |
| 31 | Connect with explicit credentials object | `connect-with-explicit-credentials-object-is-valid-syntax` | MATCH |
| 32 | Reference a database table as a lazy data source | `reference-a-database-table-as-a-lazy-data-source-is-valid-syntax` | MATCH |
| 33 | Pipeline over a database table is lazy until materialized | `pipeline-over-database-table-is-lazy-is-valid-syntax` | MATCH |
| 34 | Raw SQL query as a lazy data source | `raw-sql-query-as-lazy-data-source-is-valid-syntax` | MATCH |
| 35 | Database query with parameters | `database-query-with-parameters-is-valid-syntax` | MATCH |
| 36 | Table source materializes to a full scan on collect | `table-source-materializes-on-collect-is-valid-syntax` | MATCH |
| 37 | close! explicitly releases a database connection | `close-bang-explicitly-releases-a-database-connection-is-valid-syntax` | MATCH |

### Section 7: File Sources (8 scenarios, 8 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 38 | Read CSV file as lazy sequence of maps | `read-csv-produces-lazy-sequence-of-maps-syntax` | MATCH |
| 39 | Read CSV with explicit options | `read-csv-with-options-is-valid-syntax` | MATCH |
| 40 | Read CSV without headers produces vectors | `read-csv-without-headers-is-valid-syntax` | MATCH |
| 41 | Read JSON file | `read-json-is-valid-syntax` | MATCH |
| 42 | Read JSON lines (newline-delimited JSON) | `read-jsonl-is-valid-syntax` | MATCH |
| 43 | Read text file as lazy sequence of lines | `read-lines-is-valid-syntax` | MATCH |
| 44 | Read Parquet file as lazy columnar source | `read-parquet-is-valid-syntax` | MATCH |
| 45 | File sources support full pipeline syntax | `file-source-supports-full-pipeline-syntax` | MATCH |

### Section 8: Query Push-down (8 scenarios, 8 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 46 | filter pushes down to SQL WHERE clause | `filter-push-down-to-sql-where-is-valid-syntax` | MATCH |
| 47 | sort-by pushes down to SQL ORDER BY | `sort-by-push-down-to-sql-order-by-is-valid-syntax` | MATCH |
| 48 | take pushes down to SQL LIMIT | `take-push-down-to-sql-limit-is-valid-syntax` | MATCH |
| 49 | map with field selection pushes down to SQL SELECT | `map-with-field-selection-push-down-to-sql-select-is-valid-syntax` | MATCH |
| 50 | count on a database source pushes down to SQL COUNT(*) | `count-on-database-source-push-down-to-sql-count-is-valid-syntax` | MATCH |
| 51 | Combined push-down for filter, sort, and limit in one SQL query | `combined-push-down-is-valid-syntax` | MATCH |
| 52 | Push-down stops at non-translatable pipeline operations | `push-down-stops-at-non-translatable-operations-is-valid-syntax` | MATCH |
| 53 | explain shows the execution plan without executing the query | `explain-shows-execution-plan-without-executing-is-valid-syntax` | MATCH |

### Section 8b: Exploratory Functions (7 scenarios, 7 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 54 | describe shows statistical summary of a dataset | `describe-shows-statistical-summary-and-returns-structured-data` | MATCH |
| 55 | schema shows column names and inferred types only | `schema-shows-column-names-and-inferred-types` | MATCH |
| 56 | schema for a database table uses database metadata | `schema-for-database-table-uses-database-metadata-is-valid-syntax` | MATCH |
| 57 | sample returns N elements from the data | `sample-returns-n-elements-from-the-data` | MATCH |
| 58 | freq shows frequency table for a field | `freq-shows-frequency-table-for-a-field` | MATCH |
| 59 | histogram shows ASCII histogram for a numeric field | `histogram-shows-ascii-histogram-for-numeric-field` | MATCH |
| 60 | explain on a file-backed pipeline shows the execution plan | `explain-on-file-backed-pipeline-shows-execution-plan-without-reading` | **NAMING** |

**Naming note (60):** BDD says "shows the execution plan" but deftest adds "without-reading". The `testing` string correctly references the BDD scenario.

### Section 9: Pipeline as First-Class Object (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 61 | Pipeline is a first-class value that can be bound to a name | `pipeline-is-a-first-class-value-that-can-be-bound-to-a-name` | MATCH |
| 62 | Pipeline retains metadata about each step | `pipeline-retains-metadata-about-each-step-is-valid-syntax` | MATCH |
| 63 | dtw/inspect returns sample data after a specific pipeline step | `dtw-inspect-returns-sample-data-after-specific-pipeline-step` | MATCH |
| 64 | Pipeline lazy evaluation is transparent to the user | `pipeline-lazy-evaluation-is-transparent-same-object-reused` | MATCH |

### Section 10: Error Handling (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 65 | Connection failure raises a descriptive error | `connection-failure-raises-descriptive-error` | MATCH |
| 66 | File not found raises an error when pipeline is first evaluated | `file-not-found-raises-error-when-pipeline-is-materialized` | MATCH |
| 67 | Query timeout on database source raises an error | `query-timeout-raises-an-error` | MATCH |
| 68 | Schema mismatch is nil-tolerant (non-existent field returns nil) | `schema-mismatch-is-nil-tolerant` | MATCH |

### Section 11: End-to-End (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 69 | Full ETL pipeline from database to file | `full-etl-pipeline-from-database-to-file-is-valid-syntax` | MATCH |
| 70 | Streaming pipeline processes large file without unbounded memory use | `streaming-pipeline-processes-large-file-without-unbounded-memory` | MATCH |
| 71 | Lazy pipeline reuse -- file sources re-open on each materialization | `lazy-pipeline-reuse-file-sources-re-open-on-each-materialization` | MATCH |

### Section 12: Lazy Sequence Generators (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 72 | repeat with count produces a bounded lazy sequence | `repeat-with-count-produces-bounded-lazy-sequence` | MATCH |
| 73 | repeat without count produces an infinite lazy sequence | `repeat-without-count-produces-infinite-lazy-sequence` | MATCH |
| 74 | iterate builds an infinite sequence by applying a function repeatedly | `iterate-builds-infinite-sequence-by-applying-function-repeatedly` | MATCH |
| 75 | cycle produces an infinite repeating sequence from a collection | `cycle-produces-infinite-repeating-sequence-from-collection` | MATCH |
| 76 | dtw/set! and dtw/get configure global runtime settings | `dtw-set-and-get-configure-global-runtime-settings` | MATCH |

---

## 4. Feature 9: Error Reporting

**BDD file:** `bdd/9-error-reporting.feature`
**Test file:** `test/datatwist/error_reporting_test.clj`

### Section 1: Parse Errors (8 scenarios, 8 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 1 | Parse error - unexpected end of expression after operator | `parse-error-unexpected-end-after-operator` | MATCH |
| 2 | Parse error - unclosed string literal | `parse-error-unclosed-string-literal` | MATCH |
| 3 | Parse error - unclosed object literal | `parse-error-unclosed-object-literal` | MATCH |
| 4 | Parse error - unclosed list literal | `parse-error-unclosed-list-literal` | MATCH |
| 5 | Parse error - missing arrow in guard branch | `parse-error-missing-arrow-in-guard-branch` | MATCH |
| 6 | Parse error - missing expression after pipe operator | `parse-error-missing-expression-after-pipe` | MATCH |
| 7 | Parse error - double pipe operators | `parse-error-double-pipe-operators` | MATCH |
| 8 | Parse error - lambda missing arrow | `parse-error-lambda-missing-arrow` | MATCH |

### Section 2: Common Mistake Suggestions (7 scenarios, 7 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 9 | Common mistake - using = for assignment instead of is | `common-mistake-equals-for-assignment` | MATCH |
| 10 | Common mistake - using := for assignment | `common-mistake-colon-equals-for-assignment` | MATCH |
| 11 | Common mistake - using => instead of -> in lambda | `common-mistake-fat-arrow-in-lambda` | MATCH |
| 12 | Common mistake - using && for logical and | `common-mistake-ampersand-and-for-logical-and` | MATCH |
| 13 | Common mistake - using ! for logical not | `common-mistake-exclamation-for-logical-not` | MATCH |
| 14 | Common mistake - using comma as list separator | `common-mistake-comma-as-list-separator` | MATCH |
| 15 | Common mistake - using comma as object field separator | `common-mistake-comma-as-object-field-separator` | MATCH |

### Section 3: Error Formatting (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 16 | Error output includes a source snippet with an underline pointer | `error-output-source-snippet-and-hint` | **NAMING** |
| 17 | Error output does not contain Java class names | `error-output-no-java-class-names-on-type-error` | **NAMING** |
| 18 | Error output does not contain a Java stack trace | `error-output-no-java-stack-trace-on-division-by-zero` | **NAMING** |
| 19 | Error code is in DT-XNNN format | `error-code-format` | **NAMING** |

**Naming note (16):** BDD says "includes a source snippet with an underline pointer" but deftest uses `source-snippet-and-hint`. The `testing` string correctly references the BDD scenario.

**Naming note (17-18):** deftests add context suffixes (`-on-type-error`, `-on-division-by-zero`) not present in BDD names. These are acceptable since they clarify which concrete error triggers the test.

**Naming note (19):** BDD is verbose "Error code is in DT-XNNN format" but deftest is terse `error-code-format`.

### Section 4: Type Errors (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 20 | Type error - adding string and number | `type-error-string-plus-number` | MATCH |
| 21 | Type error - adding boolean and number | `type-error-boolean-plus-number` | MATCH |
| 22 | Type error - ordering comparison between incompatible types | `type-error-ordering-comparison-incompatible-types` | MATCH |
| 23 | Type error - division by zero | `type-error-division-by-zero` | MATCH |
| 24 | Type error - nil in arithmetic where coercion does not apply | `nil-arithmetic-coercion-not-an-error` | **NAMING** |

**Naming note (24):** BDD says "Type error - nil in arithmetic where coercion does not apply" but deftest is `nil-arithmetic-coercion-not-an-error`. The test verifies nil arithmetic IS coerced (not an error), reflecting the actual PRD behavior. The BDD scenario name is potentially misleading, but the test correctly implements the expected behavior.

### Section 5: Runtime Errors (7 scenarios, 7 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 25 | Runtime error - undefined identifier | `runtime-error-undefined-identifier` | MATCH |
| 26 | Runtime error - undefined identifier with similar name suggestion | `runtime-error-undefined-identifier-similar-name` | MATCH |
| 27 | Runtime error - undefined function name with typo | `runtime-error-undefined-function-typo` | MATCH |
| 28 | Runtime error - pipeline function applied to wrong type | `runtime-error-filter-on-non-collection` | **NAMING** |
| 29 | Runtime error - map over non-collection | `runtime-error-map-over-non-collection` | MATCH |
| 30 | List destructuring with not enough values binds missing positions to nil | `runtime-error-list-destructuring-not-enough-values` | **NAMING** |
| 31 | Runtime error - object destructuring of non-object | `runtime-error-object-destructuring-of-non-object` | MATCH |

**Naming note (28):** BDD is generic "pipeline function applied to wrong type" but deftest specifies `filter-on-non-collection`. Acceptable narrowing but not exact 1:1 name match.

**Naming note (30):** BDD says "List destructuring with not enough values binds missing positions to nil" (NOT an error) but deftest is named `runtime-error-list-destructuring-not-enough-values` (implies it IS an error). The BDD scenario says nil-binding is the expected behavior, so `runtime-error-` prefix is misleading.

### Section 6: Data Warnings (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 32 | Data warning - nil values detected in pipeline map step | `data-warning-nil-in-pipeline-map-does-not-halt` | MATCH |
| 33 | Data warning - execution continues after nil warning | `data-warning-execution-continues-nil-pipeline-returns-result` | **STRUCTURAL** |
| 34 | Data warning - nil warning pipeline returns a sequential result | `data-warning-execution-continues-after-nil-warning` | **STRUCTURAL** |
| 35 | Data warning - nil in sort-by key | `data-warning-nil-sort-key-does-not-halt` | MATCH |
| 36 | Data warning - nil in group-by key | `data-warning-nil-group-by-key-does-not-halt` | MATCH |

**Structural mismatch (33-34):** The mapping is **swapped**:
- BDD scenario 33 "execution continues after nil warning" maps to deftest `data-warning-execution-continues-nil-pipeline-returns-result` (test #35 in file order)
- BDD scenario 34 "nil warning pipeline returns a sequential result" maps to deftest `data-warning-execution-continues-after-nil-warning` (test #43, labeled "SECTION 6 gap fill")

The `testing` strings inside the tests confirm this cross-mapping:
- `data-warning-execution-continues-nil-pipeline-returns-result` has `testing "Scenario: Data warning - execution continues after nil warning"` (BDD scenario 33)
- `data-warning-execution-continues-after-nil-warning` has `testing "Scenario: Data warning - nil warning pipeline returns a sequential result"` (BDD scenario 34)

So the mapping IS correct (each test covers exactly one scenario), but the **deftest names are swapped** relative to their BDD scenario names. The `testing` strings are the true mapping.

### Section 7: Java Exception Translation (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 37 | ClassCastException is translated to a DataTwist type error | `classcastexception-hidden-from-user` | MATCH |
| 38 | ArithmeticException is translated to a DataTwist error | `arithmeticexception-hidden-from-user` | MATCH |
| 39 | NullPointerException is translated to a DataTwist nil error | `nullpointerexception-hidden-from-user` | MATCH |

### Section 8: Connection Errors (2 scenarios, 2 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 40 | Connection error - file not found for CSV | `connection-error-file-not-found` | MATCH |
| 41 | Connection error - database connection failure | `connection-error-database-connection-failure` | MATCH |

### Section 9: Error vs Warning Semantics (2 scenarios, 2 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 42 | Errors halt execution | `errors-halt-execution` | MATCH |
| 43 | Warnings do not halt execution | `warnings-do-not-halt-execution` | MATCH |

### Section 10: Additional Runtime Errors (5 scenarios, 5 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 44 | Parse error - completely unrecognised token (generic fallback DT-P001) | `parse-error-generic-fallback-unrecognised-token` | MATCH |
| 45 | Runtime error - pipeline step is not a function (DT-R002) | `runtime-error-pipeline-step-not-a-function` | MATCH |
| 46 | Runtime error - cannot call nil as a function (DT-R003) | `runtime-error-cannot-call-nil-as-function` | MATCH |
| 47 | Runtime error - calling a non-function value (DT-R004) | `runtime-error-calling-non-function-value` | MATCH |
| 48 | Runtime error - no matching arity (DT-R005) | `runtime-error-no-matching-arity` | MATCH |

---

## 5. Feature 10: Demo Runner

**BDD file:** `bdd/10-demo-runner.feature`
**Test file:** `test/datatwist/demo_runner_test.clj`

### Section 1: File Loading (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 1 | Load a .dt file that exists in resources/examples/ | `load-existing-dt-file` | MATCH |
| 2 | Load a .dt file and return a non-empty string | `load-dt-file-returns-non-empty-string` | MATCH |
| 3 | Attempt to load a file that does not exist | `load-nonexistent-dt-file-gives-clear-error` | MATCH |

### Section 2: Parsing -- Section Markers (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 4 | A file with no section markers produces one implicit section | `file-with-no-section-markers-produces-one-implicit-section` | MATCH |
| 5 | A single section marker splits the file into one named section | `single-section-marker-produces-one-named-section` | MATCH |
| 6 | Multiple section markers produce multiple named sections | `multiple-section-markers-produce-multiple-named-sections` | MATCH |
| 7 | Expressions before the first section marker belong to a default section | `expressions-before-first-section-marker-go-into-default-section` | MATCH |

### Section 3: Parsing -- Expression Extraction (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 8 | Blank lines are ignored and not treated as expressions | `blank-lines-are-ignored-during-expression-extraction` | MATCH |
| 9 | Plain comment lines (not annotations) are ignored | `plain-comment-lines-are-not-treated-as-expressions` | MATCH |
| 10 | A multi-line binding is kept as a single expression unit | `two-adjacent-expressions-extracted-as-separate-units` | **NAMING** |

**Naming note (10):** BDD scenario is "A multi-line binding is kept as a single expression unit" but deftest is `two-adjacent-expressions-extracted-as-separate-units`. The `testing` string inside correctly references the BDD scenario. The deftest name describes the concrete assertion rather than the BDD scenario name.

### Section 4: Expression Evaluation (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 11 | Each expression is evaluated in document order | `expressions-evaluated-in-document-order` | MATCH |
| 12 | Bindings established in one expression are visible in subsequent ones | `bindings-from-earlier-expressions-visible-in-later-ones` | MATCH |
| 13 | A runtime error in one expression does not stop evaluation | `runtime-error-in-one-expression-does-not-stop-evaluation` | MATCH |

### Section 5: @expect Annotations (4 scenarios, 4 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 14 | An @expect annotation before an expression records the expected value | `expect-annotation-is-associated-with-following-expression` | MATCH |
| 15 | An expression with a matching @expect annotation passes validation | `expect-annotation-passes-when-result-matches` | MATCH |
| 16 | An expression whose result does not match its @expect annotation fails validation | `expect-annotation-fails-when-result-does-not-match` | MATCH |
| 17 | Expressions without @expect annotations are evaluated without validation | `expressions-without-expect-annotations-need-no-validation` | MATCH |

### Section 6: Formatted Output (3 scenarios, 3 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 18 | Section titles are printed before their expressions | `section-title-printed-before-section-expressions` | MATCH |
| 19 | Each evaluated expression has its result printed | `evaluated-expression-result-is-printed` | MATCH |
| 20 | Error results are displayed with an error marker, not a crash | `error-expressions-display-error-marker-and-runner-continues` | MATCH |

### Section 7: End-to-End (2 scenarios, 2 tests)

| # | BDD Scenario | deftest name | Status |
|---|-------------|-------------|--------|
| 21 | Running demo-basics.dt from start to finish produces no unhandled exceptions | `running-demo-basics-dt-completes-without-exception` | MATCH |
| 22 | All @expect annotations in demo-basics.dt pass | `all-expect-annotations-in-demo-basics-pass` | MATCH |

---

## 6. Naming Issues Summary

### Convention violations

All deftest names use kebab-case, which follows the project convention. The `!` suffix is consistently mapped to `-bang` (e.g., `save!` -> `save-bang`, `tap!` -> `tap-bang`), except for one inconsistency:

1. **`force-materializes-lazy-pipeline-and-returns-data-passthrough`** (Feature 8, #13): Missing `-bang` prefix. Should be `force-bang-materializes-lazy-pipeline-and-returns-data-passthrough` to match the pattern used by `force-bang-is-useful-...` (test #14) and all other `!`-suffixed function tests.

### Name deviations (deftest does not literally kebab-case the BDD scenario name)

These are cases where the deftest name diverges from a direct kebab-case conversion of the BDD scenario name. In all cases, the `testing` string inside the deftest correctly references the BDD scenario:

| Feature | # | BDD Scenario (abbreviated) | deftest name issue |
|---------|---|---------------------------|-------------------|
| 8 | 13 | force! materializes... | Missing `-bang` -- inconsistent with test #14 |
| 8 | 17 | into!... database table | Drops "table" |
| 8 | 19 | REPL auto-samples... for preview | Reworded to "does-not-force-full-pipeline" |
| 8 | 25 | tap! with a label for clarity | Reworded to "passes-data-through-unchanged" |
| 8 | 28 | tap! with a transformation function... | Reworded to "does-not-affect-pipeline-data" |
| 8 | 60 | explain on a file-backed pipeline... | Adds "without-reading" |
| 9 | 16 | Error output includes source snippet... | Shortened to "source-snippet-and-hint" |
| 9 | 17 | Error output does not contain Java class names | Adds "-on-type-error" suffix |
| 9 | 18 | Error output does not contain a Java stack trace | Adds "-on-division-by-zero" suffix |
| 9 | 19 | Error code is in DT-XNNN format | Shortened to "error-code-format" |
| 9 | 24 | Type error - nil in arithmetic... | Changed to "nil-arithmetic-coercion-not-an-error" |
| 9 | 28 | pipeline function applied to wrong type | Narrowed to "filter-on-non-collection" |
| 9 | 30 | List destructuring... binds to nil | Misleading "runtime-error-" prefix |
| 9 | 33-34 | execution continues / returns sequential | deftest names swapped vs BDD scenario names |
| 10 | 10 | multi-line binding... single expression unit | Reworded to "two-adjacent-expressions" |

---

## 7. Assertion Issues

### Feature 8 (lazy_eval_test.clj)
- Tests 1-29 (Sections 1-5) have **real assertions** that test actual evaluator behavior. 5 failures and 29 errors occur because lazy-eval features are not yet implemented.
- Tests 30-76 (Sections 6-12) are primarily **parse-only stubs** (`(is (not (parse-error? ...)))`) with a few evaluator-based tests for in-memory data. These test that syntax is valid but not runtime behavior.

### Feature 9 (error_reporting_test.clj)
- All 48 tests have **real assertions** testing actual evaluator behavior. All pass.

### Feature 10 (demo_runner_test.clj)
- All 22 tests are **trivial stubs**: `(is (= 1 1) "TODO: ...")`. They all pass vacuously. No assertion reflects what the BDD scenario expects.

---

## 8. Recommendations

### Critical (before implementation)

1. **Register test namespaces in test runner.** Both `datatwist.lazy-eval-test` and `datatwist.demo-runner-test` are not in `test/datatwist/test_runner.clj`. They are invisible to `make test`. Add them before implementation begins so regressions are caught immediately.

2. **Fix swapped deftest names (Feature 9, tests 33-34).** The deftest names `data-warning-execution-continues-nil-pipeline-returns-result` and `data-warning-execution-continues-after-nil-warning` are swapped relative to their BDD scenarios. Rename to match:
   - `data-warning-execution-continues-after-nil-warning` -> should cover BDD "execution continues after nil warning"
   - `data-warning-nil-warning-pipeline-returns-sequential-result` -> should cover BDD "nil warning pipeline returns a sequential result"

3. **Fix misleading name (Feature 9, test 30).** `runtime-error-list-destructuring-not-enough-values` implies an error is thrown, but the BDD scenario says missing positions bind to nil (not an error). Rename to `list-destructuring-not-enough-values-binds-nil`.

### Recommended (naming consistency)

4. **Fix `force-materializes-...` (Feature 8, test 13).** Rename to `force-bang-materializes-lazy-pipeline-and-returns-data-passthrough` for consistency with the `-bang` convention used everywhere else.

5. **Align reworded test names.** Several deftest names describe test behavior rather than the BDD scenario name. While `testing` strings are correct, deftest names should ideally be a direct kebab-case of the scenario name for easy traceability. Priority candidates:
   - `repl-auto-sampling-does-not-force-full-pipeline` -> `repl-auto-samples-a-lazy-pipeline-for-preview`
   - `tap-with-a-label-passes-data-through-unchanged` -> `tap-bang-with-a-label-for-clarity`
   - `two-adjacent-expressions-extracted-as-separate-units` -> `multi-line-binding-kept-as-single-expression-unit`

### Nice-to-have

6. **Replace Feature 10 stub assertions.** All 22 tests use `(is (= 1 1))` which provides zero validation. Even before implementation, stubs could use `(is false "TODO: not yet implemented")` so they show as pending/failing rather than silently passing. Current stubs give a false sense of coverage.
