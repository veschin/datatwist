(ns datatwist.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datatwist.literals-test]
            [datatwist.data-structures-test]
            [datatwist.functions-test]
            [datatwist.pipeline-test]
            [datatwist.binding-test]
            [datatwist.pattern-matching-test]))

(defn -main [& _args]
  (let [result (run-tests
                'datatwist.literals-test
                'datatwist.data-structures-test
                'datatwist.functions-test
                'datatwist.pipeline-test
                'datatwist.binding-test
                'datatwist.pattern-matching-test)]
    (System/exit (if (and (zero? (:fail result))
                          (zero? (:error result)))
                   0
                   1))))
