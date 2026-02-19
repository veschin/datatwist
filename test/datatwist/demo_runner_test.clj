(ns datatwist.demo-runner-test
  (:require [clojure.test :refer [deftest testing is]]))

;; ==========================================================================
;; Feature 10: Demo Runner
;; BDD file: bdd/10-demo-runner.feature
;;
;; Every deftest maps 1:1 to a BDD Scenario.
;; testing blocks correspond to BDD section headers.
;;
;; NOTE: All tests in this file are stubs — they will fail (or trivially pass)
;; until the demo runner is reworked to support file-based execution.
;; Implementation target: remove hardcoded demo data from demo_runner.clj,
;; add file loading, section/annotation parsing, and @expect validation.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; SECTION 1: File Loading
;; ---------------------------------------------------------------------------

(deftest load-existing-dt-file
  (testing "Scenario: Load a .dt file that exists in resources/examples/"
    ;; TODO: implement demo-runner/load-dt-file and assert it returns a string
    (is (= 1 1) "TODO: (demo-runner/load-dt-file \"resources/examples/demo-basics.dt\") should return file content without error")))

(deftest load-dt-file-returns-non-empty-string
  (testing "Scenario: Load a .dt file and return a non-empty string"
    ;; TODO: implement demo-runner/load-dt-file and assert (seq content)
    (is (= 1 1) "TODO: loaded content should be a non-empty string")))

(deftest load-nonexistent-dt-file-gives-clear-error
  (testing "Scenario: Attempt to load a file that does not exist"
    ;; TODO: implement and assert that loading a missing file throws or returns an error value
    (is (= 1 1) "TODO: loading a nonexistent file should produce a clear error, not a raw java.io.FileNotFoundException")))

;; ---------------------------------------------------------------------------
;; SECTION 2: Parsing — Section Markers
;; ---------------------------------------------------------------------------

(deftest file-with-no-section-markers-produces-one-implicit-section
  (testing "Scenario: A file with no section markers produces one implicit section"
    ;; TODO: (demo-runner/parse-sections content) where content has no @section annotations
    ;; should return a single-element sequence with no title or title "Demo"
    (is (= 1 1) "TODO: no @section markers -> one implicit section returned")))

(deftest single-section-marker-produces-one-named-section
  (testing "Scenario: A single section marker splits the file into one named section"
    ;; Input: "// @section Basics\n42"
    ;; TODO: (demo-runner/parse-sections content) -> [{:title "Basics" :exprs [...]}]
    (is (= 1 1) "TODO: one @section marker -> one section with correct title")))

(deftest multiple-section-markers-produce-multiple-named-sections
  (testing "Scenario: Multiple section markers produce multiple named sections"
    ;; Input: "// @section Literals\n1 + 1\n// @section Pipelines\n[1 2 3] |> count"
    ;; TODO: parse-sections should return two sections with correct titles
    (is (= 1 1) "TODO: two @section markers -> two sections with correct titles in order")))

(deftest expressions-before-first-section-marker-go-into-default-section
  (testing "Scenario: Expressions before the first section marker belong to a default section"
    ;; Input: "2 + 2\n// @section Pipelines\n[1 2 3] |> count"
    ;; TODO: parse-sections should return [{:title nil :exprs ["2 + 2"]} {:title "Pipelines" ...}]
    (is (= 1 1) "TODO: pre-section expressions -> default (untitled) section")))

;; ---------------------------------------------------------------------------
;; SECTION 3: Parsing — Expression Extraction
;; ---------------------------------------------------------------------------

(deftest blank-lines-are-ignored-during-expression-extraction
  (testing "Scenario: Blank lines are ignored and not treated as expressions"
    ;; Input section content with blank lines between expressions
    ;; TODO: extracted expression list should contain no empty strings
    (is (= 1 1) "TODO: blank lines filtered out from expression list")))

(deftest plain-comment-lines-are-not-treated-as-expressions
  (testing "Scenario: Plain comment lines (not annotations) are ignored"
    ;; Input: "// This is a comment\n42"
    ;; TODO: extract-exprs should skip lines starting with "//" that are not annotations
    (is (= 1 1) "TODO: plain // comment lines are excluded from expressions")))

(deftest two-adjacent-expressions-extracted-as-separate-units
  (testing "Scenario: A multi-line binding is kept as a single expression unit"
    ;; Input: "greeting is \"Hello\"\ngreeting + \", world!\""
    ;; TODO: two independent expression lines -> two items in the extracted list
    (is (= 1 1) "TODO: two expression lines -> two separate expression units")))

;; ---------------------------------------------------------------------------
;; SECTION 4: Expression Evaluation
;; ---------------------------------------------------------------------------

(deftest expressions-evaluated-in-document-order
  (testing "Scenario: Each expression is evaluated in document order"
    ;; Input file: "x is 5\nx + 1"
    ;; TODO: eval result of second expression should be 6, proving order is preserved
    (is (= 1 1) "TODO: x is 5 then x + 1 -> final result is 6")))

(deftest bindings-from-earlier-expressions-visible-in-later-ones
  (testing "Scenario: Bindings established in one expression are visible in subsequent ones"
    ;; Input file: "base is 100\nincrement is [n -> n + base]\nincrement 42"
    ;; TODO: evaluating sequentially with shared context -> result 142
    (is (= 1 1) "TODO: shared evaluation context carries bindings across expressions")))

(deftest runtime-error-in-one-expression-does-not-stop-evaluation
  (testing "Scenario: A runtime error in one expression does not stop evaluation"
    ;; Input file: "good-expr is 1 + 1\nbad-expr is undefined-name\nanother-good is 2 + 2"
    ;; TODO: run produces results [{:ok 2} {:error ...} {:ok 4}]
    (is (= 1 1) "TODO: error in middle expression -> runner continues, all results collected")))

;; ---------------------------------------------------------------------------
;; SECTION 5: @expect Annotations
;; ---------------------------------------------------------------------------

(deftest expect-annotation-is-associated-with-following-expression
  (testing "Scenario: An @expect annotation before an expression records the expected value"
    ;; Input: "// @expect 14\n2 + 3 * 4"
    ;; TODO: parse step attaches {:expected "14"} to the expression map for "2 + 3 * 4"
    (is (= 1 1) "TODO: @expect annotation parsed and attached to its expression")))

(deftest expect-annotation-passes-when-result-matches
  (testing "Scenario: An expression with a matching @expect annotation passes validation"
    ;; Input: "// @expect 14\n2 + 3 * 4"  (2 + 3 * 4 = 14)
    ;; TODO: evaluation result matches annotation -> check status is :pass
    (is (= 1 1) "TODO: matching @expect -> validation status :pass")))

(deftest expect-annotation-fails-when-result-does-not-match
  (testing "Scenario: An expression whose result does not match its @expect annotation fails validation"
    ;; Input: "// @expect 10\n2 + 3 * 4"  (actual is 14, expected 10)
    ;; TODO: mismatch -> check status is :fail with :expected "10" :actual "14" in the result map
    (is (= 1 1) "TODO: mismatched @expect -> validation status :fail, no crash")))

(deftest expressions-without-expect-annotations-need-no-validation
  (testing "Scenario: Expressions without @expect annotations are evaluated without validation"
    ;; TODO: expression with no @expect -> result map has no :expected key, no assertion raised
    (is (= 1 1) "TODO: no @expect annotation -> eval proceeds without validation, result displayed")))

;; ---------------------------------------------------------------------------
;; SECTION 6: Formatted Output
;; ---------------------------------------------------------------------------

(deftest section-title-printed-before-section-expressions
  (testing "Scenario: Section titles are printed before their expressions"
    ;; TODO: capture stdout during demo run; assert section header appears before expression output
    (is (= 1 1) "TODO: section title appears in output before any expression results in that section")))

(deftest evaluated-expression-result-is-printed
  (testing "Scenario: Each evaluated expression has its result printed"
    ;; Input: "2 + 3 * 4"
    ;; TODO: capture stdout, assert output contains formatted result (e.g. "14")
    (is (= 1 1) "TODO: output contains a formatted result line for each evaluated expression")))

(deftest error-expressions-display-error-marker-and-runner-continues
  (testing "Scenario: Error results are displayed with an error marker, not a crash"
    ;; TODO: capture stdout for a file with one throwing expression;
    ;; assert error marker present and subsequent expression output still appears
    (is (= 1 1) "TODO: runtime error -> error marker in output, subsequent expressions still printed")))

;; ---------------------------------------------------------------------------
;; SECTION 7: End-to-End File Execution
;; ---------------------------------------------------------------------------

(deftest running-demo-basics-dt-completes-without-exception
  (testing "Scenario: Running demo-basics.dt from start to finish produces no unhandled exceptions"
    ;; TODO: (demo-runner/run-file "resources/examples/demo-basics.dt") should return without throwing
    (is (= 1 1) "TODO: demo-basics.dt runs end-to-end without any unhandled exception")))

(deftest all-expect-annotations-in-demo-basics-pass
  (testing "Scenario: All @expect annotations in demo-basics.dt pass"
    ;; TODO: collect all check results from running demo-basics.dt;
    ;; assert every result with :expected key has status :pass
    (is (= 1 1) "TODO: every @expect annotation in demo-basics.dt matches its actual result")))
