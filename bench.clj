(require '[clojure.java.shell :refer [sh]]
         '[datatwist.parser :as parser])

(defn native-eval [expr]
  (:out (sh "./datatwist" "eval" "-e" expr)))

(defn jvm-eval [expr]
  (let [r (parser/eval-dt expr)]
    (pr-str r)))

(defn bench-both [label expr]
  (println (format "\n### %s" label))
  (println (format "  expr: %s" expr))
  ;; warmup
  (dotimes [_ 2] (jvm-eval expr))
  (dotimes [_ 2] (native-eval expr))
  ;; JVM
  (print "  JVM:    ")
  (flush)
  (let [start (System/nanoTime)
        _ (dotimes [_ 10] (jvm-eval expr))
        ms (/ (- (System/nanoTime) start) 1e6)]
    (println (format "%.1f ms total, %.2f ms/run" ms (/ ms 10))))
  ;; Native
  (print "  Native: ")
  (flush)
  (let [start (System/nanoTime)
        _ (dotimes [_ 10] (native-eval expr))
        ms (/ (- (System/nanoTime) start) 1e6)]
    (println (format "%.1f ms total, %.2f ms/run" ms (/ ms 10)))))

(println "=== DataTwist: Native vs JVM (internal time, 10 runs each) ===")

(bench-both "Arithmetic"
            "1 + 2 * 3 - 4 / 2 + 10 % 3")

(bench-both "Range 10K"
            "range 1 10000")

(bench-both "Map 10K"
            "range 1 10000 |> map [x -> x * 2]")

(bench-both "Filter 10K"
            "range 1 10000 |> filter [x -> x % 2 = 0]")

(bench-both "Reduce 10K"
            "range 1 10000 |> reduce [a b -> a + b] 0")

(bench-both "Sort 10K"
            "range 1 10000 |> reverse |> sort")

(bench-both "Chained pipeline 10K (map+filter+reduce)"
            "range 1 10000 |> map [x -> x * 2] |> filter [x -> x > 5000] |> reduce [a b -> a + b] 0")

(bench-both "10K objects |> filter |> map"
            "range 1 10000 |> map [i -> {id: i name: \"user\" age: i % 80}] |> filter [u -> u.age > 60] |> map [u -> u.id]")

(bench-both "Closures 10K"
            "add is [x -> [y -> x + y]]\nplus5 is add 5\nrange 1 10000 |> map plus5")

(bench-both "Guards 10K"
            "classify is [x ->\n  | x > 75 -> \"high\"\n  | x > 25 -> \"mid\"\n  | true -> \"low\"\n]\nrange 1 10000 |> map [i -> i % 100] |> map classify")

(bench-both "Nested pipelines 100x100"
            "range 1 100 |> map [x -> range 1 100 |> map [y -> x * y] |> reduce [a b -> a + b] 0]")

(println "\n=== Done ===")
