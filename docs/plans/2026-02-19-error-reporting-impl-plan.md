# Error Reporting Implementation Plan

**Date:** 2026-02-19
**Feature:** BDD Feature 9 — Error Reporting
**Source of truth:** `bdd/9-error-reporting.feature` (38 scenarios, 9 sections)

---

## 1. Current State

### 1.1 Existing Research

`docs/error-reporting-research.md` (468 lines) provides a complete design document:
- Survey of 5 best-in-class error systems (Elm, Rust, Zig, Gleam, Roc)
- 9 common principles distilled from the survey
- A concrete 5-section error message template (header, prose, snippet, expected/actual, hint)
- Error code scheme (`DT-XNNN` with P/T/R/D/C prefixes)
- Source snippet display rules (line-numbered gutter, `^` underline, multi-line elision)
- Hint system design (Levenshtein "did you mean" + hard-coded common-mistake patterns)
- ANSI color scheme (8 standard colors, `NO_COLOR` suppression)
- 5 mock-ups showing exact expected output for common errors
- Clojure implementation notes (error map structure, rendering separation, exception translation table)

### 1.2 BDD Specification

`bdd/9-error-reporting.feature` defines **38 scenarios** across 9 sections:

| Section | Count | Coverage |
|---------|-------|----------|
| 1. Parse errors (syntax) | 8 | Unclosed literals, missing arrows, double pipes, truncated expressions |
| 2. Common mistake detection | 7 | `=`/`:=` for assignment, `=>` for `->`, `&&`/`!`, commas |
| 3. Error message format | 4 | Source snippet, no Java names, no stack traces, DT-XNNN format |
| 4. Type errors (runtime) | 5 | String+number, boolean+number, comparison mismatch, division by zero, nil coercion |
| 5. Runtime errors | 7 | Undefined identifier, typo suggestion, pipeline type mismatch, destructuring |
| 6. Data-aware warnings | 4 | Nil in map, nil in sort-by, nil in group-by, execution continues |
| 7. Java exception translation | 3 | ClassCastException, ArithmeticException, NullPointerException |
| 8. Connection/data source errors | 2 | File not found (CSV), database connection failure |
| 9. Errors vs warnings distinction | 2 | Errors halt, warnings don't |

### 1.3 Test Stubs

`test/datatwist/error_reporting_test.clj` contains **38 `deftest` blocks** (1:1 with BDD scenarios) plus 3 local helper functions:
- `error-msg` — evaluate, return exception message string
- `error-data` — evaluate, return `ex-data` map from `ExceptionInfo`
- `no-java-names?` — check absence of Java/Clojure class names in message

**Test status:** Many tests already pass today because the evaluator throws `ex-info` with sensible messages. The gaps are:
- No structured `:code` field in all error paths (only `DT-R001`, `DT-T001`, `DT-T002` exist)
- No `:hint` field in all error paths (only arithmetic/comparison have hints)
- No `:source-line`, `:line`, `:col-start`, `:col-end` fields
- No `^` underline rendering
- No Elm/Rust-style formatted output
- No warning emission system (data warnings)
- No common-mistake detector for parse failures
- No Java exception translation boundary
- No `read-csv` or `connect` functions in stdlib (connection errors untestable)

### 1.4 Current Error Handling in the Evaluator

The evaluator (`src/datatwist/evaluator.clj`, ~1513 lines) already has:

| What exists | Where | Error code |
|---|---|---|
| Levenshtein distance function | Lines 41-63 | - |
| `levenshtein-suggest` (threshold: `max(2, len/3)`) | Lines 65-75 | - |
| `dt-type-name` (human-readable type names) | Lines 81-94 | - |
| Undefined identifier with "Did you mean" | Line 248 | `DT-R001` |
| Comparison type mismatch | Line 398 | `DT-T002` |
| Addition type mismatch | Line 427 | `DT-T001` |
| Subtraction type mismatch | Line 437 | `DT-T001` |
| Multiplication type mismatch | Line 461 | `DT-T001` |
| Division by zero (raw `ArithmeticException`) | Line 471 | **None** |
| No matching arity | Line 910 | **None** |
| Object destructuring of non-object | Line 702 | **None** |
| Pipeline step not a function | Line 1344 | **None** |
| Cannot call nil as a function | Line 32 | **None** |
| Not a function | Line 34 | **None** |

**Key observation:** The evaluator already uses `ex-info` maps with `:code` and `:hint` in some places, but not consistently. Many error sites throw `ex-info` without a code or hint. Division by zero throws a raw `ArithmeticException` (not `ex-info`).

### 1.5 Current Parse Error Handling

`src/datatwist/parser.clj` (24 lines) is minimal:
- `parse` returns the Instaparse parse tree or failure object
- `parse-error?` returns `true` if `insta/failure?`
- `evaluate` in the evaluator silently returns `nil` on parse failure

**No parse error formatting exists.** Instaparse failures contain structured data (line, column, expected terminals) but this is never extracted or presented to the user.

### 1.6 Grammar

`resources/datatwist.grammar` already has `try`/`catch`/`finally` as reserved keywords (line 184-185). No grammar changes are needed for error reporting.

---

## 2. Gap Analysis

### What the BDD requires vs. what currently exists

| BDD Requirement | Status | Gap |
|---|---|---|
| Parse errors produce DT-P codes | NOT IMPLEMENTED | `parse-error?` returns boolean; no structured error data |
| Common mistake detection (7 patterns) | NOT IMPLEMENTED | No pattern detector runs after parse failure |
| Error snippet with `^` underline | NOT IMPLEMENTED | No rendering layer exists |
| No Java class names in output | PARTIAL | `ex-info` messages don't contain Java names, but `ArithmeticException` at line 471 leaks raw Java exception type |
| No Java stack traces | PARTIAL | Same as above |
| DT-XNNN format codes on all errors | PARTIAL | Only 3 of ~12 error sites have codes |
| Type errors have DT-T codes | PARTIAL | `DT-T001` and `DT-T002` exist; division by zero has no code |
| Runtime errors have DT-R codes | PARTIAL | `DT-R001` exists; arity, pipeline, destructuring errors have no code |
| Undefined identifier suggests similar name | IMPLEMENTED | `levenshtein-suggest` works at line 247 |
| Data warnings (DT-D) for nil prevalence | NOT IMPLEMENTED | No warning system; pipeline is nil-tolerant but silent |
| Warnings don't halt execution | PARTIAL | Nil tolerance means no error, but also no warning emission |
| Connection errors (DT-C) | NOT IMPLEMENTED | `read-csv` and `connect` functions don't exist in stdlib |
| Errors halt execution | IMPLEMENTED | `ex-info` propagates and halts |
| JSON error output mode | NOT SPECIFIED | Research doc mentions it; BDD does not require it |
| `:hint` on all errors | NOT IMPLEMENTED | Only arithmetic/comparison errors have hints |
| `:source-line`, `:line`, `:col-start`, `:col-end` | NOT IMPLEMENTED | No source location tracking |

---

## 3. Error Code Taxonomy

Based on BDD scenarios and the research document's proposed ranges:

### DT-PXXX — Parse Errors

| Code | Scenario | Message pattern |
|---|---|---|
| DT-P001 | Generic parse failure (fallback) | "I could not parse this expression" |
| DT-P002 | Unexpected end after operator | "Unexpected end of expression after `>`" |
| DT-P003 | Unclosed string literal | "Unclosed string literal — missing closing `\"`" |
| DT-P004 | Unclosed object literal | "Unclosed object — missing closing `}`" |
| DT-P005 | Unclosed list literal | "Unclosed list — missing closing `]`" |
| DT-P006 | Missing `->` in guard branch | "Missing `->` in guard branch" |
| DT-P007 | Missing expression after `\|>` | "Missing expression after `\|>`" |
| DT-P008 | Double pipe operators | "Unexpected `\|>` — did you mean to write a function?" |
| DT-P009 | Missing `->` in lambda | "Missing `->` in function definition" |
| DT-P020 | `x = expr` (assignment with `=`) | "DataTwist uses `is` for assignment, not `=`" |
| DT-P021 | `x := expr` | "DataTwist uses `is` for assignment, not `:=`" |
| DT-P022 | `[x => body]` (fat arrow in lambda) | "Use `->` not `=>` in function definitions" |
| DT-P023 | `&&` for logical and | "Use `and` instead of `&&`" |
| DT-P024 | `!expr` for logical not | "Use `not` instead of `!`" |
| DT-P025 | Comma in list `[1, 2, 3]` | "DataTwist uses spaces, not commas, to separate list items" |
| DT-P026 | Comma in object `{k: v, k: v}` | "DataTwist uses spaces, not commas, between object fields" |

### DT-TXXX — Type Errors

| Code | Scenario | Message pattern |
|---|---|---|
| DT-T001 | Arithmetic type mismatch (generic) | "Cannot add/subtract/multiply `<type>` and `<type>`" |
| DT-T002 | Comparison type mismatch | "Cannot compare `<type>` with `<type>`" |
| DT-T003 | Division by zero | "Division by zero" |

### DT-RXXX — Runtime Errors

| Code | Scenario | Message pattern |
|---|---|---|
| DT-R001 | Undefined identifier | "Undefined identifier: `<name>`" |
| DT-R002 | Pipeline step is not a function | "Pipeline step is not a function" |
| DT-R003 | Cannot call nil as a function | "Cannot call nil as a function" |
| DT-R004 | Not a function | "Value is not callable" |
| DT-R005 | No matching arity | "No matching arity" |
| DT-R006 | Object destructuring of non-object | "Cannot destructure `<type>` as an object" |
| DT-R010 | filter/map on non-collection | "`filter` expects a list, got `<type>`" |

### DT-DXXX — Data Warnings

| Code | Scenario | Message pattern |
|---|---|---|
| DT-D001 | Nil values in pipeline map | "N of M rows had nil at `<path>`" |
| DT-D002 | Nil sort-by keys | "N of M rows had nil sort key" |
| DT-D003 | Nil group-by keys | "N of M rows had nil group key" |

### DT-CXXX — Connection Errors

| Code | Scenario | Message pattern |
|---|---|---|
| DT-C001 | File not found | "File not found: `<path>`" |
| DT-C002 | Database connection failure | "Connection failed: `<uri>`" |

---

## 4. Implementation Steps

### Step 1: Error Data Structures and Registry

**New file:** `src/datatwist/errors.clj`

Define the canonical error map shape and a registry of all error codes:

```clj
(ns datatwist.errors)

;; Canonical error map shape (thrown via ex-info):
;; {:dt/error   true          ; marker — distinguishes DT errors from others
;;  :code       "DT-T001"     ; error code
;;  :category   "TYPE MISMATCH"  ; ALL CAPS, 2-4 words
;;  :message    "I can't add..." ; first-person prose
;;  :source     "\"hello\" + 5"  ; the offending source line(s)
;;  :line       1              ; 1-based line number
;;  :col-start  1              ; 1-based column start
;;  :col-end    14             ; 1-based column end
;;  :hint       "Try ..."     ; optional actionable suggestion
;;  :level      :error         ; :error or :warning
;; }

(defn dt-error
  "Create a DataTwist error map and throw it as ex-info."
  [{:keys [code category message source line col-start col-end hint] :as data}]
  (throw (ex-info message (merge {:dt/error true :level :error} data))))

(defn dt-warning
  "Create a DataTwist warning map. Does NOT throw. Returns the map."
  [data]
  (merge {:dt/error true :level :warning} data))

;; Error code registry — maps code to category and default message template
(def error-registry { ... })
```

**Complexity:** Low. Pure data definitions.
**Files changed:** New file only.

### Step 2: Add `:code` and `:hint` to All Existing Error Sites

Systematically update every `throw (ex-info ...)` call in `evaluator.clj` to include `:dt/error true`, `:code`, `:category`, and `:hint`.

**Specific sites to update:**

| Line | Current | Needed |
|---|---|---|
| 32 | "Cannot call nil as a function" | Add `:code "DT-R003"`, `:category "RUNTIME ERROR"`, `:hint` |
| 34 | "Not a function" | Add `:code "DT-R004"`, `:hint` |
| 248 | Undefined identifier | Already has `DT-R001`; add `:category "UNDEFINED IDENTIFIER"`, `:dt/error true` |
| 398 | Comparison type mismatch | Already has `DT-T002`; add `:category "TYPE MISMATCH"`, `:dt/error true` |
| 427 | Addition type mismatch | Already has `DT-T001`; add `:category`, `:dt/error true` |
| 437 | Subtraction type mismatch | Same as above |
| 461 | Multiplication type mismatch | Same as above |
| 471 | `ArithmeticException.` "Divide by zero" | **Replace** with `ex-info` + `DT-T003` |
| 702 | Object destructuring | Add `:code "DT-R006"`, `:hint` |
| 910 | No matching arity | Add `:code "DT-R005"`, `:hint` |
| 1344 | Pipeline step not a function | Add `:code "DT-R002"`, `:hint` |

**Critical fix:** Line 471 throws a raw `ArithmeticException` (required by `literals-test`). The error reporting tests require no `ArithmeticException` string in the output. Resolution: wrap in `ex-info` with `:code "DT-T003"` and message `"Division by zero"`. Check and update `literals_test.clj` if it asserts the exact exception type — if so, update it to check `ex-info` instead.

**Complexity:** Medium. ~12 sites, each straightforward.
**Files changed:** `src/datatwist/evaluator.clj`, possibly `test/datatwist/literals_test.clj`.

### Step 3: Java Exception Translation Boundary

Wrap the top-level `evaluate` function in a try-catch that translates raw Java exceptions into DataTwist error maps:

```clj
(defn evaluate [input]
  (when-not (comment-or-whitespace-only? input)
    (let [ast (parser/parse input)]
      (if (insta/failure? ast)
        ;; Parse failure path — see Step 5
        (throw (parse-failure->dt-error ast input))
        ;; Evaluation path — catch Java leaks
        (try
          (eval-node ast (stdlib/default-env))
          (catch clojure.lang.ExceptionInfo e
            ;; Already a DT error — re-throw as-is
            (throw e))
          (catch ArithmeticException e
            (throw (ex-info "Division by zero"
                            {:dt/error true :code "DT-T003"
                             :category "ARITHMETIC ERROR"
                             :source input})))
          (catch ClassCastException e
            (throw (ex-info (str "Type mismatch: " (.getMessage e))
                            {:dt/error true :code "DT-T001"
                             :category "TYPE MISMATCH"
                             :source input})))
          (catch NullPointerException e
            (throw (ex-info "Nil value where a value was required"
                            {:dt/error true :code "DT-R020"
                             :category "NIL ERROR"
                             :source input}))))))))
```

**Important:** This boundary ensures that even if a code path misses an explicit `ex-info`, no raw Java exception escapes to the user.

**Complexity:** Medium. Must be careful not to swallow legitimate exceptions.
**Files changed:** `src/datatwist/evaluator.clj`.

### Step 4: Source Location Tracking

Instaparse attaches metadata to parse tree nodes. The evaluator needs to propagate this through evaluation so errors can reference the source location.

**Approach A (recommended — simpler):** Since DataTwist programs are typically short, store the full source string in a dynamic var `*source*` and compute line/col from character offsets when an error is thrown.

```clj
(def ^:dynamic *source* nil)

;; In evaluate:
(binding [*source* input]
  (eval-node ast env))

;; When throwing an error, compute location:
(defn- char-offset->line-col [source offset]
  (let [before (subs source 0 (min offset (count source)))
        line   (inc (count (filter #(= % \newline) before)))
        col    (inc (- (count before) (.lastIndexOf before "\n")))]
    {:line line :col col}))
```

**Approach B (richer but harder):** Thread Instaparse metadata through the evaluator. Each AST node from Instaparse has `{:instaparse.gll/start-index N :instaparse.gll/end-index M}` in metadata. Preserve this through `eval-node` dispatch and attach to errors.

**Recommendation:** Start with Approach A. It covers all BDD scenarios. Approach B can be added later if needed for multi-expression error spans.

**Complexity:** Low-Medium. Approach A requires 1 dynamic var + 1 helper function + updating error sites.
**Files changed:** `src/datatwist/evaluator.clj`, `src/datatwist/errors.clj`.

### Step 5: Parse Error Formatting (Common Mistake Detector)

**New function in `src/datatwist/errors.clj`:** `parse-failure->dt-error`

When parsing fails, run a sequence of regex detectors against the input string. If a pattern matches, emit a specific DT-P code with a targeted hint. If none match, extract the failure location from Instaparse and emit a generic DT-P001.

**Pattern detector table (from research doc section 3.4):**

```clj
(def common-mistakes
  [{:pattern #"^(\w+)\s*=\s*"      :code "DT-P020" :hint "Use 'is' for assignment: %s is ..."}
   {:pattern #"^(\w+)\s*:=\s*"     :code "DT-P021" :hint "Use 'is' for assignment: %s is ..."}
   {:pattern #"\[.*?=>"            :code "DT-P022" :hint "Use '->' not '=>': [x -> x * 2]"}
   {:pattern #"&&"                 :code "DT-P023" :hint "Use 'and' instead of '&&'"}
   {:pattern #"^.*!\w"             :code "DT-P024" :hint "Use 'not' instead of '!'"}
   {:pattern #"\[.*?,.*?\]"        :code "DT-P025" :hint "DataTwist uses spaces: [1 2 3]"}
   {:pattern #"\{.*?,.*?\}"        :code "DT-P026" :hint "DataTwist uses spaces: {k: v k: v}"}])
```

**Instaparse failure extraction:**

```clj
(require '[instaparse.core :as insta])

;; Instaparse failure objects support:
;; (insta/get-failure result) -> {:index N :line L :column C :text "..." :reason [...]}
;; Each reason entry: {:tag :regexp/:string/:nt, :expecting "..."}
```

Use `insta/get-failure` to extract the failure position, then format with source snippet.

**Important behavioral change:** Currently `evaluate` returns `nil` on parse failure. The BDD tests call `parse-error?` (which checks `insta/failure?`) and `throws?` (which checks for exceptions). For error reporting to work, `evaluate` must **throw** on parse failure, not return nil. This means:
- `parse-error?` continues to work (it doesn't call `evaluate`)
- `throws?` will return `true` for parse errors (currently returns `false` because `evaluate` returns `nil`)
- Tests in `error_reporting_test.clj` that call `error-msg` or `error-data` on parse-error sources will actually get structured error data

**Risk:** Some existing tests may depend on `evaluate` returning `nil` for invalid input. Grep for calls to `evaluate` and `eval-dt` with deliberately invalid input.

**Complexity:** Medium-High. Regex patterns need careful tuning to avoid false positives.
**Files changed:** `src/datatwist/errors.clj` (new), `src/datatwist/evaluator.clj` (evaluate function).

### Step 6: Error Rendering (Elm/Rust-style Output)

**New file:** `src/datatwist/error_renderer.clj`

Pure function `render-error` that takes a DT error map and returns a formatted string.

Template from research doc:
```
-- <CATEGORY> [<CODE>] ---------------------------------------- <file>:<line> --

<message>

 <line>|  <source line>
      |  <^^^^ underline>

Hint: <hint text>
```

**Sub-functions:**
- `render-header` — category, code, file, line, padded with `--` to 80 cols
- `render-snippet` — source line(s) with gutter, underline at col-start..col-end
- `render-hint` — prefixed with "Hint: "
- `render-error` — assembles all sections

**ANSI color support:**
- Dynamic var `*use-color*` (default: `(some? (System/console))`)
- Respects `NO_COLOR` and `DT_NO_COLOR` env vars
- Tests bind `*use-color*` to `false`

**Complexity:** Medium. String formatting, no algorithmic difficulty.
**Files changed:** New file `src/datatwist/error_renderer.clj`.

### Step 7: Warning Emission System

Data-aware warnings require a side-channel: execution continues, but warnings are accumulated.

**Design:**
```clj
(def ^:dynamic *warnings* nil)

(defn emit-warning! [warning-map]
  (when *warnings*
    (swap! *warnings* conj warning-map)))

;; In evaluate:
(binding [*warnings* (atom [])]
  (let [result (eval-node ast env)
        ws     @*warnings*]
    ;; Return result; warnings available via metadata or separate channel
    (with-meta {:result result :warnings ws} {:dt/warnings ws})))
```

**Where to emit warnings:**
- `dt-map` in `stdlib.clj`: after mapping, count nil results. If >0, emit DT-D001.
- `dt-sort-by` in `stdlib.clj`: count nil sort keys. If >0, emit DT-D002.
- `dt-group-by` in `stdlib.clj`: count nil group keys. If >0, emit DT-D003.

**Test contract:** The BDD says warnings don't halt execution and the pipeline returns results. Tests check:
1. `(not (throws? ...))` — already passes (nil-tolerant)
2. Warning was emitted — needs new test helper `(warnings-for source)` that returns the list of warning maps

**Important:** The current test stubs in `error_reporting_test.clj` for data warnings (lines 315-339) test that execution doesn't throw and returns correct results. They pass today because of nil tolerance. The missing piece is testing that a warning was actually emitted, which requires the warning infrastructure.

**Complexity:** Medium. Requires threading a dynamic var through stdlib functions.
**Files changed:** `src/datatwist/evaluator.clj`, `src/datatwist/stdlib.clj`, `src/datatwist/errors.clj`, `test/datatwist/test_helpers.clj`.

### Step 8: Connection Error Stubs (DT-CXXX)

The BDD specifies two connection error scenarios:
1. `read-csv "nonexistent-file.csv"` — file not found
2. `connect "postgres://..."` — database connection failure

Neither `read-csv` nor `connect` exists in the stdlib. These are Feature 8 (data sources) functions.

**Minimal approach for error reporting:** Add stub functions to stdlib that throw structured DT-C errors:

```clj
;; In stdlib.clj default-env:
"read-csv" (fn [path]
             (if (.exists (java.io.File. (str path)))
               (throw (ex-info "read-csv not yet implemented" {:code "DT-C001"}))
               (throw (ex-info (str "File not found: " path)
                               {:dt/error true :code "DT-C001"
                                :category "FILE NOT FOUND"
                                :hint (str "Check the file path: " path)}))))

"connect"  (fn [uri]
             (throw (ex-info (str "Connection failed: " uri)
                             {:dt/error true :code "DT-C002"
                              :category "CONNECTION ERROR"
                              :hint "Check that the database is running and the URI is correct."})))
```

**Note:** Full implementation of `read-csv` and `connect` is out of scope for error reporting. The stubs satisfy the BDD's error behavior contract.

**Complexity:** Low.
**Files changed:** `src/datatwist/stdlib.clj`.

### Step 9: Update Test Stubs to Assert Structured Error Data

Many test stubs use `(when (some? data) ...)` guards, meaning they pass vacuously today. Once the error infrastructure is in place, strengthen these:

- Replace `(when (some? data) ...)` with `(is (some? data) ...)` — make the assertion mandatory
- Add checks for `:code`, `:hint`, `:category` in `error-data`
- Add test helper `(error-code source)` — returns the `:code` from `ex-data`
- Add test helper `(warnings-for source)` — evaluates and returns accumulated warnings

**Complexity:** Low-Medium. Mechanical updates.
**Files changed:** `test/datatwist/error_reporting_test.clj`, `test/datatwist/test_helpers.clj`.

---

## 5. Dependencies (Implementation Order)

```
Step 1: Error data structures          (no dependencies)
    |
    v
Step 2: Add codes to all error sites   (depends on Step 1)
    |
    v
Step 3: Java exception boundary        (depends on Step 1)
    |
    v
Step 4: Source location tracking        (depends on Step 1)
    |
    v
Step 5: Parse error formatting          (depends on Steps 1, 4)
    |
    v
Step 6: Error rendering                 (depends on Steps 1, 4)
    |
    v
Step 7: Warning system                  (depends on Step 1; independent of 5-6)
    |
    v
Step 8: Connection error stubs          (independent; can be done anytime)
    |
    v
Step 9: Strengthen test assertions      (depends on all above)
```

**Parallelizable:** Steps 7 and 8 are independent of Steps 5-6. Steps 2 and 3 can be done in parallel.

**Suggested implementation order for a single agent:**
1. Step 1 (errors.clj)
2. Step 2 + Step 3 (evaluator error sites + boundary)
3. Step 8 (connection stubs — quick win)
4. Step 4 (source location)
5. Step 5 (parse error formatting)
6. Step 7 (warning system)
7. Step 6 (rendering — can be deferred if not tested)
8. Step 9 (strengthen tests)

---

## 6. Risks

### 6.1 Division-by-zero exception type change

**Risk:** `test/datatwist/literals_test.clj` may assert `ArithmeticException` specifically for `10 / 0`. Changing it to `ex-info` will break that test.

**Mitigation:** Confirmed: `test/datatwist/literals_test.clj` lines 154-155 assert `(throws-type? "5 / 0" ArithmeticException)` and lines 167-168 assert `(throws-type? "10 % 0" ArithmeticException)`. Both must be updated to `(throws-type? "..." clojure.lang.ExceptionInfo)` or relaxed to `(throws? "...")`. The modulo-by-zero path at evaluator line 474-476 also needs the same `ex-info` treatment as division.

### 6.2 `evaluate` returning nil vs throwing on parse failure

**Risk:** Changing `evaluate` to throw on parse failure may break callers that depend on the `nil` return. The REPL, demo runner, and any code calling `parser/eval-dt` would be affected.

**Mitigation:** Add a separate entry point `evaluate-with-errors` that throws on parse failure. Or make the existing `evaluate` throw, and update all callers. The latter is preferred because the BDD requires errors to be visible.

### 6.3 Instaparse failure format instability

**Risk:** `insta/get-failure` returns a map whose shape is not formally documented. The `:line`, `:column`, and `:reason` fields may vary between Instaparse versions.

**Mitigation:** Pin Instaparse at 1.5.0 (already done in `deps.edn`). Write unit tests for the failure extraction function.

### 6.4 Warning accumulation performance

**Risk:** The `*warnings*` atom could accumulate many warnings in large pipelines (e.g., mapping over 100K rows where 50K are nil).

**Mitigation:** Cap warnings at a configurable limit (e.g., 10). Emit a final "and N more..." summary.

### 6.5 Regex-based common-mistake detection false positives

**Risk:** Patterns like `#"^(\w+)\s*=\s*"` could match valid DataTwist in edge cases (e.g., inside strings or comments).

**Mitigation:** Only run the detector on the raw input when parsing has already failed. Since the grammar rejected it, false positives from valid code are impossible. But we should still test edge cases like `"x = 42"` (a string containing `=`).

### 6.6 Color code contamination in test output

**Risk:** If `*use-color*` is not bound to `false` in tests, ANSI escape codes will pollute test assertions.

**Mitigation:** The rendering layer is separate from the error-throwing layer. Tests that check `ex-data` maps never go through the renderer. Tests that check rendered output explicitly bind `*use-color*` to `false`.

---

## 7. Estimated Scope

### Files to Create

| File | Purpose | Approx lines |
|---|---|---|
| `src/datatwist/errors.clj` | Error data structures, registry, `dt-error`, `dt-warning`, common-mistake detector, parse failure translator | ~200 |
| `src/datatwist/error_renderer.clj` | Elm/Rust-style formatted output, ANSI colors, source snippet rendering | ~150 |

### Files to Modify

| File | Changes | Approx diff |
|---|---|---|
| `src/datatwist/evaluator.clj` | Add codes to ~12 error sites, Java exception boundary in `evaluate`, `*source*` dynamic var, `*warnings*` var | ~80 lines changed |
| `src/datatwist/stdlib.clj` | Add `read-csv`/`connect` stubs, add warning emission to `dt-map`/`dt-sort-by`/`dt-group-by` | ~40 lines added |
| `test/datatwist/test_helpers.clj` | Add `error-code`, `error-output`, `warnings-for` helpers | ~20 lines added |
| `test/datatwist/error_reporting_test.clj` | Strengthen `when` guards to `is` assertions, add warning-checking tests | ~30 lines changed |
| `test/datatwist/literals_test.clj` | Update division-by-zero assertion if it checks `ArithmeticException` | ~3 lines |

### Total Estimated Effort

- ~500 new lines of Clojure
- ~150 lines of modifications
- 38 BDD scenarios to satisfy
- Feature can be split into 3-4 implementation sessions

### Test Verification

After implementation, run:
```bash
# Full suite — must remain at 0 failures
make test

# Targeted error reporting tests
clj -M -e "(require 'clojure.test 'datatwist.error-reporting-test) (clojure.test/run-tests 'datatwist.error-reporting-test)"
```

All 506+ existing tests must continue to pass. The 38 error reporting tests should move from "vacuously passing" to "substantively passing."
