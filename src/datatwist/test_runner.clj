(ns datatwist.test-runner
  (:require [clojure.test :as test]))

(defn run-all-tests []
  (println "=== DataTwist Complete Test Suite ===")
  (println "")

  ;; Run grammar tests
  (println "1. Running comprehensive grammar tests...")
  (require 'datatwist.grammar-tests)
  (let [grammar-results (test/run-tests 'datatwist.grammar-tests)]
    (println (str "   Grammar tests: " (:pass grammar-results) " passed, "
                  (:fail grammar-results) " failed, " (:error grammar-results) " errors")))

  ;; Run comment tests
  (println "")
  (println "2. Running comment tests...")
  (require 'datatwist.comment-tests)
  (let [comment-results (test/run-tests 'datatwist.comment-tests)]
    (println (str "   Comment tests: " (:pass comment-results) " passed, "
                  (:fail comment-results) " failed, " (:error comment-results) " errors")))

  ;; Test DTW files
  (println "")
  (println "3. Testing DTW files...")
  (require 'datatwist.grammar-tests)
  (let [parser (var-get (resolve 'datatwist.grammar-tests/parser))
        dtw-files ["test_resources/zen-example.dtw"
                   "test_resources/comment-test.dtw"
                   "test_resources/large-sample.dtw"]
        dtw-results (for [file dtw-files]
                      (let [content (slurp file)
                            result (parser content)]
                        (if ((resolve 'instaparse.core/failure?) result)
                          {:file file :status :failed}
                          {:file file :status :success :nodes (count (flatten result))})))
        dtw-passed (count (filter #(= (:status %) :success) dtw-results))
        dtw-failed (count (filter #(= (:status %) :failed) dtw-results))]
    (doseq [result dtw-results]
      (if (= (:status result) :failed)
        (println (str "   ❌ " (:file result) " - FAILED"))
        (println (str "   ✅ " (:file result) " - SUCCESS (" (:nodes result) " nodes)"))))
    (println (str "   DTW files: " dtw-passed " passed, " dtw-failed " failed")))

  ;; Summary
  (println "")
  (println "=== Test Summary ===")
  (let [grammar-results (test/run-tests 'datatwist.grammar-tests)
        comment-results (test/run-tests 'datatwist.comment-tests)
        dtw-files ["test_resources/zen-example.dtw"
                   "test_resources/comment-test.dtw"
                   "test_resources/large-sample.dtw"]
        parser (var-get (resolve 'datatwist.grammar-tests/parser))
        dtw-results (for [file dtw-files]
                      (let [content (slurp file)
                            result (parser content)]
                        (if ((resolve 'instaparse.core/failure?) result)
                          {:file file :status :failed}
                          {:file file :status :success :nodes (count (flatten result))})))
        dtw-passed (count (filter #(= (:status %) :success) dtw-results))
        dtw-failed (count (filter #(= (:status %) :failed) dtw-results))
        total-passed (+ (:pass grammar-results) (:pass comment-results) dtw-passed)
        total-failed (+ (:fail grammar-results) (:fail comment-results) dtw-failed)
        total-errors (+ (:error grammar-results) (:error comment-results))]
    (println (str "Total: " total-passed " passed, " total-failed " failed, " total-errors " errors"))
    (if (= 0 total-failed total-errors)
      (println "🎉 All tests passed!")
      (println "❌ Some tests failed!"))))

(defn -main [& _]
  (run-all-tests))