(ns datatwist.comment-tests
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

;; Test cases for comments
(deftest single-line-comment-tests
  (testing "Single line comments should parse"
    (is (parse-success? ";; This is a comment"))
    (is (parse-success? ";; Another comment with numbers 123"))
    (is (parse-success? ";; Comment with symbols !@#$%"))))

(deftest multi-line-comment-tests
  (testing "Multi-line comments should parse"
    (is (parse-success? "(comment this is a multi-line comment)"))
    (is (parse-success? "(comment nested (parentheses) work)"))
    (is (parse-success? "(comment comment with symbols !@#$%)"))))

(deftest comments-in-objects-tests
  (testing "Comments in objects should parse"
    (is (parse-success? "{\n  ;; field comment\n  name: \"test\"\n  (comment another comment)\n  age: 25\n}"))
    (is (parse-success? "{\n  id: 1\n  ;; user data\n  profile: {\n    email: \"test@example.com\"\n    (comment profile info)\n  }\n}"))))

(deftest comments-in-lists-tests
  (testing "Comments in lists should parse"
    (is (parse-success? "[\n  ;; first item\n  1\n  (comment item comment)\n  2\n  ;; last item\n  3\n]"))
    (is (parse-success? "[\n  \"item1\"\n  ;; separator\n  \"item2\"\n  (comment list comment)\n  \"item3\"\n]"))))

(deftest comments-in-pipelines-tests
  (testing "Comments in pipelines should parse"
    (is (parse-success? "data\n  ;; filter step\n  filter _.age > 18\n  ;; transform step\n  map {name: _.name}\n  (comment pipeline comment)"))
    (is (parse-success? "users\n  filter _.active\n  ;; group by region\n  group-by _.region\n  (comment operation comment)\n  map {count: count _}"))))

(deftest mixed-comments-tests
  (testing "Mixed comment types should parse"
    (is (parse-success? ";; File header comment\n(comment block comment)\ndata = [1 2 3]\n;; Process data\nresult = data\n  filter _ > 1\n  (comment transformation)\n  map _ * 2"))))

(deftest comment-file-test
  (testing "Full comment test file should parse"
    (let [comment-file (slurp "test_resources/comment-test.dtw")
          result (parser comment-file)]
      (is (not (insta/failure? result))
          "Comment test file should parse successfully")
      (when-not (insta/failure? result)
        (println "Comment file parsed successfully!")
        (println "Parse tree contains" (count (flatten result)) "nodes")))))

;; Run tests function
(defn run-comment-tests []
  (println "=== Running DataTwist Comment Tests ===")
  (let [results (run-tests 'datatwist.comment-tests)
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
      (println "✅ All comment tests passed!")
      (println "❌ Some comment tests failed or had errors"))
    results))