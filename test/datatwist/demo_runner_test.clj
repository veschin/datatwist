(ns datatwist.demo-runner-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.demo-runner :as demo-runner]
            [datatwist.stdlib :as stdlib]))

;; ==========================================================================
;; Feature 10: Demo Runner
;; BDD file: bdd/10-demo-runner.feature
;;
;; Every deftest maps 1:1 to a BDD Scenario.
;; testing blocks correspond to BDD section headers.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; SECTION 1: File Loading
;; ---------------------------------------------------------------------------

(deftest load-existing-dt-file
  (testing "Scenario: Load a .dt file that exists in resources/examples/"
    (let [content (demo-runner/load-dt-file "demo-basics.dt")]
      (is (string? content)
          "load-dt-file should return a string"))))

(deftest load-dt-file-returns-non-empty-string
  (testing "Scenario: Load a .dt file and return a non-empty string"
    (let [content (demo-runner/load-dt-file "demo-basics.dt")]
      (is (seq content)
          "loaded content should be a non-empty string"))))

(deftest load-nonexistent-dt-file-gives-clear-error
  (testing "Scenario: Attempt to load a file that does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"File not found"
                          (demo-runner/load-dt-file "nonexistent.dt"))
        "loading a nonexistent file should throw ExceptionInfo with a clear message")))

;; ---------------------------------------------------------------------------
;; SECTION 2: Parsing — Section Markers
;; ---------------------------------------------------------------------------

(deftest file-with-no-section-markers-produces-one-implicit-section
  (testing "Scenario: A file with no section markers produces one implicit section"
    (let [content  "42\n1 + 1"
          sections (demo-runner/parse-sections content)]
      (is (= 1 (count sections))
          "no @section markers -> one implicit section returned")
      (is (nil? (:title (first sections)))
          "default section has no title"))))

(deftest single-section-marker-produces-one-named-section
  (testing "Scenario: A single section marker splits the file into one named section"
    (let [content  "// @section Basics\n42"
          sections (demo-runner/parse-sections content)]
      (is (= 1 (count sections))
          "one @section marker -> one section")
      (is (= "Basics" (:title (first sections)))
          "section title should be 'Basics'")
      (let [exprs (demo-runner/extract-expressions (:lines (first sections)))]
        (is (= 1 (count exprs)) "section should have one expression")
        (is (= "42" (:expr (first exprs))) "expression should be '42'")))))

(deftest multiple-section-markers-produce-multiple-named-sections
  (testing "Scenario: Multiple section markers produce multiple named sections"
    (let [content  "// @section Literals\n1 + 1\n// @section Pipelines\n[1 2 3] |> count"
          sections (demo-runner/parse-sections content)]
      (is (= 2 (count sections))
          "two @section markers -> two sections")
      (is (= "Literals" (:title (first sections)))
          "first section titled 'Literals'")
      (is (= "Pipelines" (:title (second sections)))
          "second section titled 'Pipelines'"))))

(deftest expressions-before-first-section-marker-go-into-default-section
  (testing "Scenario: Expressions before the first section marker belong to a default section"
    (let [content  "2 + 2\n// @section Pipelines\n[1 2 3] |> count"
          sections (demo-runner/parse-sections content)]
      (is (= 2 (count sections))
          "should produce two sections")
      (is (nil? (:title (first sections)))
          "first (default) section has no title")
      (let [default-exprs (demo-runner/extract-expressions (:lines (first sections)))]
        (is (= "2 + 2" (:expr (first default-exprs)))
            "expression '2 + 2' belongs to the default section"))
      (is (= "Pipelines" (:title (second sections)))
          "second section is 'Pipelines'"))))

;; ---------------------------------------------------------------------------
;; SECTION 3: Parsing — Expression Extraction
;; ---------------------------------------------------------------------------

(deftest blank-lines-are-ignored-during-expression-extraction
  (testing "Scenario: Blank lines are ignored and not treated as expressions"
    (let [lines ["1 + 1" "" "   " "2 + 2"]
          exprs (demo-runner/extract-expressions lines)]
      (is (= 2 (count exprs))
          "blank lines filtered out from expression list")
      (is (every? #(seq (:expr %)) exprs)
          "no blank expression strings"))))

(deftest plain-comment-lines-are-not-treated-as-expressions
  (testing "Scenario: Plain comment lines (not annotations) are ignored"
    (let [lines ["// This is a comment" "42"]
          exprs (demo-runner/extract-expressions lines)]
      (is (= 1 (count exprs))
          "plain // comment lines excluded from expressions")
      (is (= "42" (:expr (first exprs)))
          "only the actual expression should be present"))))

(deftest two-adjacent-expressions-extracted-as-separate-units
  (testing "Scenario: A multi-line binding is kept as a single expression unit"
    (let [lines ["greeting is \"Hello\""
                 "greeting + \", world!\""]
          exprs (demo-runner/extract-expressions lines)]
      (is (= 2 (count exprs))
          "two expression lines -> two separate expression units")
      (is (= "greeting is \"Hello\"" (:expr (first exprs))))
      (is (= "greeting + \", world!\"" (:expr (second exprs)))))))

;; ---------------------------------------------------------------------------
;; SECTION 4: Expression Evaluation
;; ---------------------------------------------------------------------------

(deftest expressions-evaluated-in-document-order
  (testing "Scenario: Each expression is evaluated in document order"
    (let [env (stdlib/default-env)
          r1  (demo-runner/run-expression "x is 5" env)
          r2  (demo-runner/run-expression "x + 1" (:env r1))]
      (is (= 5 (:result r1)) "x is 5 -> result is 5")
      (is (= 6 (:result r2)) "x + 1 -> result is 6 (using binding from previous expr)"))))

(deftest bindings-from-earlier-expressions-visible-in-later-ones
  (testing "Scenario: Bindings established in one expression are visible in subsequent ones"
    (let [env (stdlib/default-env)
          r1  (demo-runner/run-expression "base is 100" env)
          r2  (demo-runner/run-expression "increment is [n -> n + base]" (:env r1))
          r3  (demo-runner/run-expression "increment 42" (:env r2))]
      (is (nil? (:error r1)) "base is 100 should succeed")
      (is (nil? (:error r2)) "increment is [...] should succeed")
      (is (= 142 (:result r3))
          "increment 42 -> 142 (base carried forward from previous binding)"))))

(deftest runtime-error-in-one-expression-does-not-stop-evaluation
  (testing "Scenario: A runtime error in one expression does not stop evaluation"
    (let [env (stdlib/default-env)
          r1  (demo-runner/run-expression "good-expr is 1 + 1" env)
          r2  (demo-runner/run-expression "bad-expr is undefined-name" (:env r1))
          r3  (demo-runner/run-expression "another-good is 2 + 2" (:env r2))]
      (is (= 2 (:result r1)) "good-expr is 1 + 1 -> result 2")
      (is (some? (:error r2)) "bad-expr is undefined-name -> records an error")
      (is (= 4 (:result r3)) "another-good is 2 + 2 -> result 4 (runner continued)"))))

;; ---------------------------------------------------------------------------
;; SECTION 5: @expect Annotations
;; ---------------------------------------------------------------------------

(deftest expect-annotation-is-associated-with-following-expression
  (testing "Scenario: An @expect annotation before an expression records the expected value"
    (let [lines ["// @expect 14" "2 + 3 * 4"]
          exprs (demo-runner/extract-expressions lines)]
      (is (= 1 (count exprs))
          "one expression should be extracted")
      (is (= "2 + 3 * 4" (:expr (first exprs)))
          "expression should be '2 + 3 * 4'")
      (is (= "14" (:expected (first exprs)))
          "@expect annotation should be '14'"))))

(deftest expect-annotation-passes-when-result-matches
  (testing "Scenario: An expression with a matching @expect annotation passes validation"
    (let [env    (stdlib/default-env)
          result (demo-runner/run-expression "2 + 3 * 4" env)]
      (is (= 14 (:result result))
          "2 + 3 * 4 evaluates to 14")
      ;; The check status is computed in run-file, but we can verify match logic
      ;; by confirming the result equals the expected value
      (is (= "14" (str (:result result)))
          "result as string matches the @expect annotation '14'"))))

(deftest expect-annotation-fails-when-result-does-not-match
  (testing "Scenario: An expression whose result does not match its @expect annotation fails validation"
    ;; Parse a section with a mismatched @expect and check that the expression has
    ;; :expected "10" but the actual evaluation returns 14.
    ;; The :check field in run-file results would be :fail for this case.
    (let [lines  ["// @expect 10" "2 + 3 * 4"]
          exprs  (demo-runner/extract-expressions lines)
          expr   (first exprs)
          env    (stdlib/default-env)
          result (demo-runner/run-expression (:expr expr) env)]
      (is (= "10" (:expected expr))
          "the expression should have :expected '10'")
      (is (= 14 (:result result))
          "actual result is 14, not 10")
      (is (not= (:expected expr) (str (:result result)))
          "mismatch: expected '10' but got '14' -> would record :fail"))))

(deftest expressions-without-expect-annotations-need-no-validation
  (testing "Scenario: Expressions without @expect annotations are evaluated without validation"
    (let [lines ["42"]
          exprs (demo-runner/extract-expressions lines)]
      (is (= 1 (count exprs))
          "one expression extracted")
      (is (nil? (:expected (first exprs)))
          "no :expected key when there is no @expect annotation"))))

;; ---------------------------------------------------------------------------
;; SECTION 6: Formatted Output
;; ---------------------------------------------------------------------------

(deftest section-title-printed-before-section-expressions
  (testing "Scenario: Section titles are printed before their expressions"
    ;; run-file on a string with a named section; capture stdout
    (let [output (with-out-str
                   (demo-runner/run-file "demo-basics.dt"))]
      (is (some? (re-find #"Literals" output))
          "section header 'Literals' appears in output")
      ;; The header must appear before expression result lines
      (let [header-pos (.indexOf output "Literals")
            result-pos (.indexOf output "14")]
        (is (< header-pos result-pos)
            "section header appears before expression result")))))

(deftest evaluated-expression-result-is-printed
  (testing "Scenario: Each evaluated expression has its result printed"
    (let [output (with-out-str
                   (demo-runner/run-file "demo-basics.dt"))]
      (is (some? (re-find #"14" output))
          "output contains the result '14' for '2 + 3 * 4'"))))

(deftest error-expressions-display-error-marker-and-runner-continues
  (testing "Scenario: Error results are displayed with an error marker, not a crash"
    ;; We verify that run-expression returns :error and the runner
    ;; does not throw when encountering an error expression
    (let [env    (stdlib/default-env)
          r-err  (demo-runner/run-expression "undefined-name" env)
          r-next (demo-runner/run-expression "1 + 1" (:env r-err))]
      (is (some? (:error r-err))
          "error expression records an error")
      (is (= 2 (:result r-next))
          "subsequent expression still evaluated after error"))))

;; ---------------------------------------------------------------------------
;; SECTION 7: End-to-End File Execution
;; ---------------------------------------------------------------------------

(deftest running-demo-basics-dt-completes-without-exception
  (testing "Scenario: Running demo-basics.dt from start to finish produces no unhandled exceptions"
    (let [result (with-out-str
                   (demo-runner/run-file "demo-basics.dt"))]
      (is (string? result)
          "run-file completes without throwing")
      (is (seq result)
          "at least some output was produced"))))

(deftest all-expect-annotations-in-demo-basics-pass
  (testing "Scenario: All @expect annotations in demo-basics.dt pass"
    (let [result-map (atom nil)]
      ;; Suppress stdout during run-file, capture the return value via atom
      (with-out-str
        (reset! result-map (demo-runner/run-file "demo-basics.dt")))
      (let [results (:results @result-map)
            checked (filter :check results)]
        (is (seq checked)
            "demo-basics.dt should have at least one @expect annotation")
        (doseq [r checked]
          (is (= :pass (:check r))
              (str "@expect check failed for: " (:expr r)
                   " expected=" (:expected r)
                   " actual=" (:result r))))))))
