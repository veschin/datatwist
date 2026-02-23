(ns datatwist.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datatwist.parser-test]
            [datatwist.literals-test]
            [datatwist.data-structures-test]
            [datatwist.functions-test]
            [datatwist.pipeline-test]
            [datatwist.binding-test]
            [datatwist.pattern-matching-test]
            [datatwist.interop-test]
            [datatwist.error-reporting-test]
            [datatwist.lazy-eval-test]
            [datatwist.demo-runner-test]
            [datatwist.pattern-destructuring-test]
            [datatwist.main-test]))

(defn -main [& _args]
  (let [result (run-tests
                'datatwist.parser-test
                'datatwist.literals-test
                'datatwist.data-structures-test
                'datatwist.functions-test
                'datatwist.pipeline-test
                'datatwist.binding-test
                'datatwist.pattern-matching-test
                'datatwist.interop-test
                'datatwist.error-reporting-test
                'datatwist.lazy-eval-test
                'datatwist.demo-runner-test
                'datatwist.pattern-destructuring-test
                'datatwist.main-test)]
    (System/exit (if (and (zero? (:fail result))
                          (zero? (:error result)))
                   0
                   1))))
