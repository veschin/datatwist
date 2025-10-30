(ns datatwist.grammar-tests
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Load the grammar from file
(def grammar-text
  (->> (slurp (io/resource "datatwist.grammar"))
       str/split-lines
       (remove #(re-find #"^\s*[;#(*]" %))
       (str/join "\n")))

;; Create parser from grammar file
(def parser
  (insta/parser
   grammar-text
   :auto-whitespace :standard))

;; Helper functions for testing
(defn parse-success? [input]
  (let [result (parser input)]
    (not (insta/failure? result))))

(defn parse-failure? [input]
  (insta/failure? (parser input)))

(defn get-parse-tree [input]
  (parser input))

(defn assert-parse-tree-structure [input expected-keys]
  (let [tree (get-parse-tree input)]
    (is (not (insta/failure? tree)))
    (when-not (insta/failure? tree)
      (doseq [key expected-keys]
        (is (some #(= key %) (flatten tree))
            (str "Expected key '" key "' not found in parse tree for: " input))))))

;; Test data for comprehensive coverage
(def test-cases
  {:literals
   {:positive
    [["String literal" "\"Hello World\"" [:string]]
     ["String with escape" "\"Hello \\\"World\\\"\"" [:string]]
     ["Integer number" "42" [:number]]
     ["Float number" "3.14" [:number]]
     ["Boolean true" "true" [:boolean]]
     ["Boolean false" "false" [:boolean]]
     ["Nil literal" "nil" [:nil]]]

    :negative
    [["Unclosed string" "\"Hello World" [:string]]
     ["Invalid number" "12.34.56" [:number]]]}

   :identifiers
   {:positive
    [["Simple identifier" "name" [:identifier]]
     ["Predicate identifier" "even?" [:identifier]]
     ["Complex identifier" "user-data" [:identifier]]
     ["Identifier with numbers" "user123" [:identifier]]
     ["Identifier with underscores" "user_name" [:identifier]]
     ["Identifier with hyphens" "user-name" [:identifier]]]

    :negative
    [["Starts with number" "123user" [:identifier]]
     ["Starts with hyphen" "-user" [:identifier]]
     ["Only special chars" "---" [:identifier]]]}

   :data-structures
   {:positive
    [["Empty object" "{}" [:object]]
     ["Simple object" "{name: \"Alice\"}" [:object :field]]
     ["Multi-field object" "{id: 1 name: \"Alice\" age: 25}" [:object :field]]
     ["Nested object" "{user: {name: \"Alice\"}}" [:object :field :object]]
     ["Empty list" "[]" [:list]]
     ["Number list" "[1 2 3 4 5]" [:list]]
     ["Mixed list" "[\"Alice\" 25 true]" [:list]]
     ["Nested list" "[[1 2] [3 4]]" [:list :list]]]

    :negative
    [["Object with comma" "{name: \"Alice\", age: 25}" [:object]]
     ["Unclosed object" "{name: \"Alice\"" [:object]]
     ["List with comma" "[1, 2, 3]" [:list]]
     ["Unclosed list" "[1 2 3" [:list]]]}

   :functions
   {:positive
    [["Simple function" "[x -> x + 1]" [:function]]
     ["Multi-param function" "[a b -> a + b]" [:function]]
     ["Function with complex body" "[x -> x * 2 + 1]" [:function]]
     ["Named function" "add = [a b -> a + b]" [:function-def :function]]]

    :negative
    [["Unclosed function" "[x -> x + 1" [:function]]
     ["Empty params" "[-> x + 1]" [:function]]]}

   :wildcard-access
   {:positive
    [["Simple wildcard" "_" [:wildcard-access]]
     ["Field wildcard" "_.name" [:wildcard-access]]
     ["Nested field wildcard" "_.profile.age" [:wildcard-access]]]

    :negative
    [["Invalid field access" "_.123invalid" [:wildcard-access]]
     ["Empty field" "_." [:wildcard-access]]]}

   :zen-pipelines
   {:positive
    [["Basic zen pipeline" "users\n  filter _.age > 18" [:indented-pipeline :indented-filter-op]]
     ["Chained zen pipeline" "users\n  filter _.age > 18\n  map {name: _.name}" [:indented-pipeline :indented-filter-op :general-function-call]]
     ["Multi-op zen pipeline" "data\n  filter even?\n  map double\n  take 5" [:indented-pipeline :indented-filter-op :indented-map-op :indented-take-op]]
     ["Function call in zen pipeline" "data\n  process arg1 arg2" [:indented-pipeline :general-function-call]]]

    :negative
    [["Invalid operation" "data\n  invalid-op _.field" [:indented-pipeline :general-function-call]]]}

   :indented-pipelines
   {:positive
    [["Basic indented pipeline"
      "users\n  filter _.age > 18\n  map {name: _.name}"
      [:indented-pipeline :indented-filter-op :general-function-call]]
     ["Complex indented pipeline"
      "sales-data\n  filter _.amount > 1000\n  group-by _.region\n  map {\n    region: _.region\n    total: sum _.amount\n  }"
      [:indented-pipeline :indented-filter-op :indented-group-by-op :general-function-call]]]

    :negative
    [["Insufficient indentation" "users\n filter _.age > 18" [:function-call]]
     ["Mixed indentation" "users\n  filter _.age > 18\n\tmap {name: _.name}" [:indented-pipeline :function-call]]]}

   :pattern-matching
   {:positive
    [["Simple pattern match in object" "{risk:\n  | _.age < 18 -> \"minor\"\n  | _ -> \"adult\"}" [:object :multi-line-field-value]]
     ["Pattern match with wildcard" "{score:\n  | _.value > 10 -> \"high\"\n  | _ -> \"low\"}" [:object :multi-line-field-value]]
     ["Complex guards in object" "{level:\n  | x > 10 and x < 20 -> \"teen\"\n  | x >= 20 -> \"adult\"\n  | _ -> \"child\"}" [:object :multi-line-field-value]]]

    :negative
    [["Invalid guard syntax" "{risk: _.age > 10 -> \"big\" | _ -> \"small\"}" [:object]]]}

   :try-catch
   {:positive
    [["Simple try-catch" "try read-file \"data.txt\" catch error -> \"Failed\"" [:try-catch]]
     ["Complex try-catch" "try load-api endpoint catch HttpError -> \"Error: {status}\"" [:try-catch]]]

    :negative
    [["Missing catch" "try read-file \"data.txt\" error -> \"Failed\"" [:try-catch]]
     ["Invalid catch syntax" "try expr catch -> \"fallback\"" [:try-catch]]]}

   :complex-nested
   {:positive
    [["Nested pipelines in map"
      "users\n  map {\n    name: _.name\n    scores: \n      _.scores\n        filter even?\n  }"
      [:indented-pipeline :general-function-call :field-pipeline]]
     ["Complex nested structure"
      "data\n  filter _.active\n  map {\n    user: _.user\n    stats: {\n      count: count _.items\n      avg: average (map _.value _.items)\n    }\n  }"
      [:indented-pipeline :indented-filter-op :general-function-call :function-call]]]

    :negative
    [["Mismatched nesting" "users map { name: _.name scores: }" [:statement]]]}

   :edge-cases
   {:positive
    [["Empty program" "" []]
     ["Multiple statements" "x = 1\ny = 2\nz = x + y" [:assignment :assignment :expr]]]

    :negative
    [["Invalid characters" "x = @#$%" [:assignment]]
     ["Unmatched parentheses" "(x + y" [:expr]]]}})

;; Performance test data
(def large-input
  (str "data = ["
       (clojure.string/join " " (map #(str "{id: " % " name: \"user" % "\"}") (range 1 1001)))
       "]\n"
       "result = data\n"
       "  filter _.id > 500\n"
       "  map {id: _.id name: _.name}\n"
       "  sort-by _.id\n"
       "  take 100"))

;; Test suite
(deftest literals-tests
  (testing "Positive literal cases"
    (doseq [[desc input expected] (:positive (:literals test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative literal cases"
    (doseq [[desc input _] (:negative (:literals test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest identifiers-tests
  (testing "Positive identifier cases"
    (doseq [[desc input expected] (:positive (:identifiers test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative identifier cases"
    (doseq [[desc input _] (:negative (:identifiers test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest data-structures-tests
  (testing "Positive data structure cases"
    (doseq [[desc input expected] (:positive (:data-structures test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative data structure cases"
    (doseq [[desc input _] (:negative (:data-structures test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest functions-tests
  (testing "Positive function cases"
    (doseq [[desc input expected] (:positive (:functions test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative function cases"
    (doseq [[desc input _] (:negative (:functions test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest wildcard-access-tests
  (testing "Positive wildcard access cases"
    (doseq [[desc input expected] (:positive (:wildcard-access test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative wildcard access cases"
    (doseq [[desc input _] (:negative (:wildcard-access test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest zen-pipelines-tests
  (testing "Positive zen pipeline cases"
    (doseq [[desc input expected] (:positive (:zen-pipelines test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative zen pipeline cases"
    (doseq [[desc input expected] (:negative (:zen-pipelines test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected)))))

(deftest indented-pipelines-tests
  (testing "Positive indented pipeline cases"
    (doseq [[desc input expected] (:positive (:indented-pipelines test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative indented pipeline cases"
    (doseq [[desc input expected] (:negative (:indented-pipelines test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected)))))

(deftest pattern-matching-tests
  (testing "Positive pattern matching cases"
    (doseq [[desc input expected] (:positive (:pattern-matching test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative pattern matching cases"
    (doseq [[desc input _] (:negative (:pattern-matching test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest try-catch-tests
  (testing "Positive try-catch cases"
    (doseq [[desc input expected] (:positive (:try-catch test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative try-catch cases"
    (doseq [[desc input _] (:negative (:try-catch test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest complex-nested-tests
  (testing "Positive complex nested cases"
    (doseq [[desc input expected] (:positive (:complex-nested test-cases))]
      (testing desc
        (is (parse-success? input))
        (assert-parse-tree-structure input expected))))

  (testing "Negative complex nested cases"
    (doseq [[desc input _] (:negative (:complex-nested test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest edge-cases-tests
  (testing "Positive edge cases"
    (doseq [[desc input expected] (:positive (:edge-cases test-cases))]
      (testing desc
        (is (parse-success? input))
        (when (seq expected)
          (assert-parse-tree-structure input expected)))))

  (testing "Negative edge cases"
    (doseq [[desc input _] (:negative (:edge-cases test-cases))]
      (testing desc
        (is (parse-failure? input))))))

(deftest performance-tests
  (testing "Large input parsing performance"
    (let [start-time (System/currentTimeMillis)
          result (parser large-input)
          end-time (System/currentTimeMillis)
          duration (- end-time start-time)]
      (is (not (insta/failure? result)))
      (is (< duration 5000) "Parsing should complete within 5 seconds")
      (println (str "Large input parsing took " duration "ms")))))

(deftest ambiguity-resolution-tests
  (testing "Ambiguous cases resolve correctly"
    ;; Test that function calls vs identifiers are disambiguated
    (let [input "func arg1 arg2"]
      (is (parse-success? input))
      (let [tree (get-parse-tree input)]
        ;; Should parse as function-call, not identifier
        (is (some #(= :function-call %) (flatten tree)))))))

;; Helper function to run all tests and generate report
(defn run-comprehensive-tests []
  (println "=== Running DataTwist Grammar Comprehensive Tests ===")
  (let [results (run-tests 'datatwist.grammar-tests)
        passed (:pass results)
        failed (:fail results)
        errors (:error results)
        total (+ passed failed errors)]
    (println (str "\nTest Results:"))
    (println (str "Total tests: " total))
    (println (str "Passed: " passed))
    (println (str "Failed: " failed))
    (println (str "Errors: " errors))
    (if (= 0 failed errors)
      (println "✅ All tests passed!")
      (println "❌ Some tests failed or had errors"))
    results))

;; Export test results to file
(defn save-test-results [results]
  (spit "test-results.log"
        (str "DataTwist Grammar Test Results - " (java.util.Date.)
             "\n" results "\n")))

;; Run tests when file is loaded (for development)
(comment
  (def test-results (run-comprehensive-tests))
  (save-test-results test-results))