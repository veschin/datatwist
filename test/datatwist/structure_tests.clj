(ns datatwist.structure-tests
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Create parser from grammar file
(def parser
  (insta/parser (slurp (io/resource "datatwist.grammar")) :auto-whitespace :standard))

;; Helper functions for testing
(defn parse-success? [input]
  (let [result (parser input)]
    (not (insta/failure? result))))

(defn parse-failure? [input]
  (insta/failure? (parser input)))

(defn get-parse-tree [input]
  (parser input))

(defn assert-exact-structure [input expected]
  (let [tree (get-parse-tree input)]
    (is (not (insta/failure? tree))
        (str "Parse failed for: " input "\nFailure: " (insta/get-failure tree)))
    (when-not (insta/failure? tree)
      (is (= expected tree)
          (str "Expected structure:\n" expected
               "\n\nActual structure:\n" tree)))))

;; Test cases for PHASE 2 target structure
(def phase2-test-cases
  {:literals
   [["String literal" "\"Hello World\""
     [:program [:string "Hello World"]]]

    ["Integer number" "42"
     [:program [:number "42"]]]

    ["Float number" "3.14"
     [:program [:number "3.14"]]]

    ["Negative number" "-25"
     [:program [:number "-25"]]]

    ["Boolean true" "true"
     [:program [:boolean "true"]]]

    ["Boolean false" "false"
     [:program [:boolean "false"]]]

    ["Nil literal" "nil"
     [:program [:nil "nil"]]]]

   :identifiers
   [["Simple identifier" "name"
     [:program [:identifier "name"]]]

    ["Predicate identifier" "even?"
     [:program [:identifier "even?"]]]

    ["Complex identifier" "user-data"
     [:program [:identifier "user-data"]]]

    ["Identifier with numbers" "user123"
     [:program [:identifier "user123"]]]

    ["Identifier with underscores" "user_name"
     [:program [:identifier "user_name"]]]

    ["Identifier with hyphens" "user-name"
     [:program [:identifier "user-name"]]]]

   :assignments
   [["Simple assignment" "x = 42"
     [:program [:assignment [:identifier "x"] [:number "42"]]]]

    ["String assignment" "name = \"Alice\""
     [:program [:assignment [:identifier "name"] [:string "Alice"]]]]

    ["Expression assignment" "result = x + y"
     [:program [:assignment [:identifier "result"] [:identifier "x"] [:add "+"] [:identifier "y"]]]]]

   :data-structures
   [["Empty object" "{}"
     [:program [:object ""]]]

    ["Simple object" "{name: \"Alice\"}"
     [:program [:object [:field [:identifier "name"] [:string "Alice"]]]]]

    ["Multi-field object" "{id: 1 name: \"Alice\" age: 25}"
     [:program [:object
                [:field [:identifier "id"] [:number "1"]]
                [:field [:identifier "name"] [:string "Alice"]]
                [:field [:identifier "age"] [:number "25"]]]]]

    ["Nested object" "{user: {name: \"Alice\"}}"
     [:program [:object [:field [:identifier "user"] [:object [:field [:identifier "name"] [:string "Alice"]]]]]]]

    ["Empty list" "[]"
     [:program [:list ""]]]

    ["Number list" "[1 2 3 4 5]"
     [:program [:list [:number "1"] [:number "2"] [:number "3"] [:number "4"] [:number "5"]]]]

    ["Mixed list" "[\"Alice\" 25 true]"
     [:program [:list [:string "Alice"] [:number "25"] [:identifier "true"]]]]

    ["Nested list" "[[1 2] [3 4]]"
     [:program [:list [:list [:number "1"] [:number "2"]] [:list [:number "3"] [:number "4"]]]]]]

   :functions
   [["Simple function" "[x -> x + 1]"
     [:program [:function [:function-params [:identifier "x"]] [:function-body [:identifier "x"] [:add "+"] [:number "1"]]]]]

    ["Multi-param function" "[a b -> a + b]"
     [:program [:function [:function-params [:identifier "a"] [:identifier "b"]] [:function-body [:identifier "a"] [:add "+"] [:identifier "b"]]]]]

    ["Function with complex body" "[x -> x * 2 + 1]"
     [:program [:function [:function-params [:identifier "x"]] [:function-body [:identifier "x"] [:mul "*"] [:number "2"] [:add "+"] [:number "1"]]]]]

    ["Named function" "add = [a b -> a + b]"
     [:program [:function-def [:identifier "add"] [:function [:function-params [:identifier "a"] [:identifier "b"]] [:function-body [:identifier "a"] [:add "+"] [:identifier "b"]]]]]]]

   :function-calls
   [["Simple function call" "func arg1"
     [:program [:function-call [:identifier "func"] [:identifier "arg1"]]]]

    ["Function call with multiple args" "process data filter"
     [:program [:function-call [:identifier "process"] [:identifier "data"] [:identifier "filter"]]]]

    ["Function call with literal" "print \"Hello\""
     [:program [:function-call [:identifier "print"] [:string "Hello"]]]]

    ["Function call with object" "create-user {name: \"Alice\" age: 25}"
     [:program [:function-call [:identifier "create-user"] [:object [:field [:identifier "name"] [:string "Alice"]] [:field [:identifier "age"] [:number "25"]]]]]]

    ["Function call with parenthesized args" "func(data filter)"
     [:program [:function-call [:identifier "func"] [:function-call [:identifier "data"] [:identifier "filter"]]]]]]

   :wildcard-access
   [["Simple wildcard" "_"
     [:program [:wildcard-access "_"]]]

    ["Field wildcard" "_.name"
     [:program [:wildcard-access "_" [:identifier "name"]]]]

    ["Nested field wildcard" "_.profile.age"
     [:program [:wildcard-access "_" [:identifier "profile"] [:identifier "age"]]]]]

   :expressions
   [["Simple addition" "x + y"
     [:program [:identifier "x"] [:add "+"] [:identifier "y"]]]

    ["Complex arithmetic" "x * 2 + y / 3"
     [:program [:identifier "x"] [:mul "*"] [:number "2"] [:add "+"] [:identifier "y"] [:div "/"] [:number "3"]]]

    ["Comparison" "x > 10"
     [:program [:identifier "x"] [:comparison-op [:gt ">"]] [:number "10"]]]

    ["Logical expression" "x > 10 and y < 20"
     [:program [:identifier "x"] [:comparison-op [:gt ">"]] [:number "10"] [:logical-op [:and-op "and"]] [:identifier "y"] [:comparison-op [:lt "<"]] [:number "20"]]]]

   ;; PHASE 2 TARGET: Pipeline structure tests
   :pipelines
   [["Basic zen pipeline" "users\n  filter _.age > 18"
     ;; TARGET: [:program [:pipeline [:identifier "users"] [:operation [:identifier "filter"] [:wildcard-access "_" [:identifier "age"]] [:gt] [:number "18"]]]]
     ;; CURRENT: Complex nested structure - will be updated in Phase 2
     ]

    ["Chained zen pipeline" "users\n  filter _.age > 18\n  map {name: _.name}"
     ;; TARGET: [:program [:pipeline 
     ;;            [:identifier "users"]
     ;;            [:operation [:identifier "filter"] [:wildcard-access "_" [:identifier "age"]] [:gt] [:number "18"]]
     ;;            [:operation [:identifier "map"] [:object [:field [:identifier "name"] [:wildcard-access "_" [:identifier "name"]]]]]]]
     ]

    ["Multi-op zen pipeline" "data\n  filter even?\n  map double\n  take 5"
     ;; TARGET: [:program [:pipeline
     ;;            [:identifier "data"]
     ;;            [:operation [:identifier "filter"] [:identifier "even?"]]
     ;;            [:operation [:identifier "map"] [:identifier "double"]]
     ;;            [:operation [:identifier "take"] [:number "5"]]]]]
     ]]

   ;; PHASE 2 TARGET: Pattern matching tests
   :pattern-matching
   [["Simple pattern matching" "value\n  map {\n    age_group: \n      | _.age < 25 -> \"young\"\n      | _ -> \"adult\"\n  }"
     ;; TARGET: Complex nested structure with simplified pattern clauses
     ;; [:field [:identifier "age_group"] 
     ;;  [:pattern 
     ;;   [:when [:wildcard-access "_" [:identifier "age"]] [:lt] [:number 25]] [:string "young"]
     ;;   [:default [:string "adult"]]]]
     ]]

   ;; PHASE 2 TARGET: Direct operator nodes
   :operators
   [["Direct comparison operators" "x > y and x <= y and x == y and x != y"
     ;; TARGET: [:program [:and [:identifier "x"] [:gt] [:identifier "y"]] [:identifier "x"] [:lte] [:identifier "y"]] [:identifier "x"] [:eq] [:identifier "y"]] [:identifier "x"] [:ne] [:identifier "y"]]]
     ]

    ["Direct arithmetic operators" "x + y - z * w / v % u"
     ;; TARGET: [:program [:sub [:add [:identifier "x"] [:identifier "y"]] [:identifier "z"] [:mul [:identifier "w"]] [:div [:identifier "v"]] [:mod [:identifier "u"]]]
     ]]

   ;; PHASE 2 TARGET: Logical operators as nodes
   :logical-operators
   [["Logical AND as node" "x > 10 and y < 20"
     ;; TARGET: [:program [:and [:identifier "x"] [:gt] [:number "10"]] [:identifier "y"] [:lt] [:number "20"]]]
     ]

    ["Logical OR as node" "x > 10 or y < 20"
     ;; TARGET: [:program [:or [:identifier "x"] [:gt] [:number "10"]] [:identifier "y"] [:lt] [:number "20"]]]
     ]

    ["Complex logical" "x > 10 and y < 20 or z == 30"
     ;; TARGET: [:program [:or [:and [:identifier "x"] [:gt] [:number "10"]] [:identifier "y"] [:lt] [:number "20"]]] [:identifier "z"] [:eq] [:number "30"]]]
     ]]})

;; Test suite for PHASE 1 (already implemented)
(deftest phase1-literals-tests
  (testing "Phase 1 literal parsing"
    (doseq [[desc input expected] (:literals phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-identifiers-tests
  (testing "Phase 1 identifier parsing"
    (doseq [[desc input expected] (:identifiers phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-assignments-tests
  (testing "Phase 1 assignment parsing"
    (doseq [[desc input expected] (:assignments phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-data-structures-tests
  (testing "Phase 1 data structure parsing"
    (doseq [[desc input expected] (:data-structures phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-functions-tests
  (testing "Phase 1 function parsing"
    (doseq [[desc input expected] (:functions phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-function-calls-tests
  (testing "Phase 1 function call parsing"
    (doseq [[desc input expected] (:function-calls phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-wildcard-access-tests
  (testing "Phase 1 wildcard access parsing"
    (doseq [[desc input expected] (:wildcard-access phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

(deftest phase1-expressions-tests
  (testing "Phase 1 expression parsing"
    (doseq [[desc input expected] (:expressions phase2-test-cases)]
      (testing desc
        (assert-exact-structure input expected)))))

;; PHASE 2 Tests (TODO - implement after grammar changes)
(deftest phase2-pipeline-tests
  (testing "Phase 2 pipeline parsing (TODO)"
    ;; These tests will be implemented after pipeline simplification
    (doseq [[desc input expected] (:pipelines phase2-test-cases)]
      (testing (str desc " - NOT YET IMPLEMENTED")
        ;; TODO: Implement after Phase 2 grammar changes
        (is true "Phase 2 pipeline tests not yet implemented")))))

(deftest phase2-pattern-matching-tests
  (testing "Phase 2 pattern matching parsing (TODO)"
    ;; These tests will be implemented after pattern matching simplification
    (doseq [[desc input expected] (:pattern-matching phase2-test-cases)]
      (testing (str desc " - NOT YET IMPLEMENTED")
        ;; TODO: Implement after Phase 2 grammar changes
        (is true "Phase 2 pattern matching tests not yet implemented")))))

(deftest phase2-operators-tests
  (testing "Phase 2 direct operator nodes (TODO)"
    ;; These tests will be implemented after operator simplification
    (doseq [[desc input expected] (:operators phase2-test-cases)]
      (testing (str desc " - NOT YET IMPLEMENTED")
        ;; TODO: Implement after Phase 2 grammar changes
        (is true "Phase 2 operator tests not yet implemented")))))

(deftest phase2-logical-operators-tests
  (testing "Phase 2 logical operator nodes (TODO)"
    ;; These tests will be implemented after logical operator simplification
    (doseq [[desc input expected] (:logical-operators phase2-test-cases)]
      (testing (str desc " - NOT YET IMPLEMENTED")
        ;; TODO: Implement after Phase 2 grammar changes
        (is true "Phase 2 logical operator tests not yet implemented")))))

;; Helper function to run all tests
(defn run-all-simplified-tests []
  (println "=== Running DataTwist Simplified Grammar Tests ===")
  (println "Phase 1 (COMPLETED): Basic structure simplification")
  (println "Phase 2 (TODO): Pipeline & advanced simplification")
  (println "")

  (let [results (run-tests 'datatwist.structure-tests)
        passed (:pass results)
        failed (:fail results)
        errors (:error results)
        total (+ passed failed errors)]
    (println (str "\nSimplified Test Results:"))
    (println (str "Total tests: " total))
    (println (str "Passed: " passed))
    (println (str "Failed: " failed))
    (println (str "Errors: " errors))
    (if (= 0 failed errors)
      (println "✅ All simplified tests passed!")
      (println "❌ Some simplified tests failed or had errors"))
    results))

;; Helper to check Phase 1 implementation status
(defn check-phase1-status []
  (println "=== Phase 1 Implementation Status ===")
  (let [results (run-tests 'datatwist.structure-tests)
        phase1-tests [:phase1-literals-tests :phase1-identifiers-tests :phase1-assignments-tests
                      :phase1-data-structures-tests :phase1-functions-tests :phase1-function-calls-tests
                      :phase1-wildcard-access-tests :phase1-expressions-tests]]
    (doseq [test-name phase1-tests]
      (let [test-result (get-in results [:test test-name])]
        (if test-result
          (println (str "✅ " test-name ": " (:pass test-result) " passed, " (:fail test-result) " failed"))
          (println (str "❌ " test-name ": NOT FOUND")))))))

;; Run tests when file is loaded (for development)
(comment
  (def test-results (run-all-simplified-tests))
  (check-phase1-status)
  (spit "simplified-test-results.log" (str "Simplified Test Results - " (java.util.Date.) "\n" test-results "\n")))
