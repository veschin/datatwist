(ns datatwist.lazy-eval-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws? throws-type? type-of
                                            silent-eval-dt silent-eval-dt-last silent-throws?
                                            capture-eval-dt-last]]
            [datatwist.config :as config]))

;; ==========================================================================
;; Feature 8: Lazy Evaluation, Data Sources & Pipeline Introspection
;; BDD file: bdd/8-lazy-eval-data-sources.feature
;;
;; Every deftest maps 1:1 to a BDD Scenario (95 scenarios total).
;; Scenarios tagged @stub and scenarios for unimplemented evaluator features
;; (DTPipeline record, autotap!, REPL sampling, system constants, data sources,
;; SQL push-down, exploration functions) are stubs.
;; Scenarios whose semantics are fully supported today have real assertions.
;; ==========================================================================

;; === Section 1: Lazy Pipeline Construction ===

(deftest a-pipeline-without-a-terminal-operation-is-lazy-and-does-not-execute
  (testing "stub -- not yet implemented"))
;; DTPipeline record not yet implemented. The BDD requires the result to be a
;; DTPipeline value; the evaluator currently returns a Clojure LazySeq.

(deftest chaining-multiple-lazy-operations-builds-a-deeper-plan
  (testing "stub -- not yet implemented"))
;; DTPipeline plan introspection not yet implemented.

(deftest binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation
  (testing "stub -- not yet implemented"))
;; DTPipeline laziness guarantee (no element processed) is not yet introspectable.

(deftest filter-map-take-drop-distinct-flatten-return-lazy-sequences
  ;; The evaluator does return Clojure lazy seqs for filter/map/take chains.
  ;; We assert laziness (not a PersistentVector) and correct forced values.
  (let [result (eval-dt-last
                "numbers is [1 2 3 4 5 6 7 8 9 10]"
                "result is numbers |> filter [n -> n % 2 = 0] |> map [n -> n * 10] |> take 3"
                "result")]
    (is (not (instance? clojure.lang.PersistentVector result))
        "filter/map/take pipeline must be lazy (not a realized vector)")
    (is (= [20 40 60] (vec result))
        "forced result must be the first 3 even numbers times 10")))

(deftest lazy-pipeline-over-a-large-in-memory-range-does-not-materialize-all-elements
  ;; Laziness means we can build a filter over a million-element range without
  ;; materializing it. We verify the result is a lazy seq.
  (let [result (eval-dt-last
                "numbers is range 1 1000000"
                "evens is numbers |> filter [n -> n % 2 = 0]"
                "evens")]
    (is (not (instance? clojure.lang.PersistentVector result))
        "evens must be lazy, not a realized vector")
    (is (= [2 4 6 8 10] (take 5 result))
        "first 5 even numbers must be correct without materializing all elements")))

(deftest nil-source-in-a-pipeline-produces-an-empty-collection
  ;; From PRD nil semantics: piping nil through filter returns empty.
  (is (= [] (eval-dt "nil |> filter _ > 0 |> force!"))
      "nil source must produce empty collection"))

;; --- Eager operations as implicit materialization barriers ---

(deftest sort-by-is-an-eager-barrier-it-realizes-its-input-before-sorting
  ;; sort-by must materialize the full input to sort, then take 3 of the result.
  (let [result (eval-dt-last
                "result is [3 1 4 1 5 9] |> sort-by _ |> take 3 |> force!"
                "result")]
    (is (= [1 1 3] result)
        "sort-by [3 1 4 1 5 9] |> take 3 must produce [1 1 3]")))

(deftest group-by-realizes-its-input-before-grouping
  ;; group-by returns a concrete map, not a lazy pipeline.
  (let [result (eval-dt-last
                "data is [{region: \"EU\" value: 1} {region: \"US\" value: 2} {region: \"EU\" value: 3}]"
                "data |> group-by _.region")]
    (is (map? result)
        "group-by must return a concrete map")
    (is (contains? result "EU")
        "grouped map must contain key \"EU\"")
    (is (contains? result "US")
        "grouped map must contain key \"US\"")))

;; --- Materialization functions ---

(deftest force-bang-on-an-already-materialized-collection-is-a-no-op
  ;; force! on a vector must return the same vector unchanged.
  (is (= [1 2 3]
         (eval-dt-last
          "items is [1 2 3]"
          "items |> force!"))
      "force! on an already-materialized vector is a no-op"))

(deftest force-bang-materializes-a-lazy-pipeline-and-returns-the-data-passthrough
  ;; force! triggers full evaluation and returns the data so the pipeline can continue.
  (let [result (eval-dt-last
                "data is [1 2 3 4 5]"
                "result is data |> filter _ > 2 |> map _ * 10 |> force!"
                "result")]
    (is (= [30 40 50] result)
        "force! must produce correct materialized values")
    (is (instance? clojure.lang.PersistentVector result)
        "force! result must be a concrete PersistentVector")))

(deftest force-bang-is-useful-for-materializing-once-before-multiple-downstream-uses
  (testing "stub -- not yet implemented"))
;; save! is a stub that returns data passthrough but does not write files.
;; This scenario tests that force! materializes once so two save! calls each
;; get the concrete collection. Full semantics require working file I/O.

(deftest count-forces-full-traversal-and-returns-exact-count
  ;; Multiples of 7 in range 1..10,000,000: floor(10000000/7) = 1,428,571
  ;; Using a smaller range to keep test fast: multiples of 7 in 1..100,000 = 14,285
  (is (= 14285
         (eval-dt-last
          "data is range 1 100001"
          "data |> filter [x -> x % 7 = 0] |> count"))
      "count must return exact count of multiples of 7 in range 1..100000"))

(deftest count-on-an-in-memory-collection-returns-exact-count
  (is (= 5
         (eval-dt-last
          "items is [1 2 3 4 5]"
          "items |> count"))
      "count on a concrete collection must return exact count"))

(deftest first-forces-evaluation-until-one-element-is-found
  (let [result (eval-dt-last
                "data is [{score: 95} {score: 70} {score: 88}]"
                "data |> filter _.score > 90 |> first")]
    (is (= {:score 95} result)
        "first must return the first element matching the filter")))

(deftest reduce-folds-the-pipeline-into-a-single-scalar-value
  (let [result (eval-dt "[1 2 3 4 5] |> reduce [a b -> a + b] 0")]
    (is (= 15 result)
        "reduce must fold [1 2 3 4 5] with + starting from 0 to produce 15")))

;; --- Auto-materialization at user-facing boundaries ---

(deftest equality-comparison-auto-materializes-a-lazy-sequence
  ;; Clojure's = auto-materializes lazy seqs when comparing to vectors.
  (let [check (eval-dt-last
               "result is [1 2 3 4 5] |> filter _ > 2"
               "check is result = [3 4 5]"
               "check")]
    (is (true? check)
        "lazy sequence must equal [3 4 5] after auto-materialization via =")))

(deftest str-auto-materializes-a-lazy-sequence-for-string-conversion
  (testing "Scenario: str auto-materializes a lazy sequence for string conversion"
    ;; str in stdlib now materializes lazy seqs to vectors before conversion,
    ;; producing "[2 4 6]" instead of "clojure.lang.LazySeq@...".
    (let [s (eval-dt-last
             "result is [1 2 3] |> map _ * 2"
             "s is str result"
             "s")]
      (is (string? s)
          "str of a lazy seq must return a string")
      (is (= "[2 4 6]" s)
          "str of a lazy seq must produce a human-readable vector representation")
      (is (not (re-find #"LazySeq" s))
          "str must not expose JVM LazySeq reference"))))

(deftest error-messages-include-materialized-values-not-lazy-references
  (testing "Scenario: Error messages include materialized values, not lazy references"
    ;; When a pipeline produces a lazy seq and then an error occurs, the error
    ;; message must not expose JVM LazySeq references. The error renderer only
    ;; surfaces the :message string (not :value), so LazySeq@... cannot appear.
    (let [src "result is [1 2 3] |> map _ * 2\nresult 42"
          msg (try
                (eval-dt src)
                nil
                (catch Exception e (or (.getMessage e) (str e))))]
      (is (some? msg)
          "calling a lazy seq as a function must produce an error")
      (is (not (re-find #"LazySeq@" (or msg "")))
          "error message must not contain a JVM LazySeq reference"))))

;; --- Infinite sequence generators ---

(deftest range-with-start-and-end-is-lazy
  ;; range produces a lazy seq; take 5 and force! give the first 5 elements.
  (let [result (eval-dt-last
                "nums is range 1 1000000"
                "result is nums |> take 5 |> force!"
                "result")]
    (is (= [1 2 3 4 5] result)
        "first 5 elements of range 1..1,000,000 must be [1 2 3 4 5]")))

(deftest range-with-one-argument-produces-a-lazy-range-from-zero
  ;; range 5 produces [0 1 2 3 4] (range from zero to 5 exclusive).
  (let [result (eval-dt "range 5 |> force!")]
    (is (= [0 1 2 3 4] result)
        "range 5 must produce [0 1 2 3 4]")))

(deftest range-with-no-upper-bound-produces-an-infinite-lazy-sequence
  (testing "stub -- BDD conflict, needs user clarification"))
;; BDD conflict: the "range with no upper bound" scenario says "range 1" produces
;; an infinite lazy sequence starting at 1, giving [1 2 3 4] when take 4 is applied.
;; However, the adjacent "range with one argument" scenario says "range 5" produces
;; [0 1 2 3 4] — finite range from zero (same 1-arg form, contradictory semantics).
;; These two BDD scenarios cannot both be true with the same stdlib range arity.
;; Resolution options (needs user decision):
;;   A) 1-arg range = finite 0..n (current), and remove the infinite-range scenario.
;;   B) 1-arg range = infinite from n, and change "range 5 |> force!" to a 2-arg form.
;;   C) Add a separate "range-from" function for infinite sequences from a start value.
;; Until the BDD conflict is resolved this test remains a stub.

(deftest repeat-with-count-produces-a-bounded-lazy-sequence
  (let [result (eval-dt-last
                "xs is repeat 5 \"x\""
                "xs |> force!")]
    (is (= ["x" "x" "x" "x" "x"] result)
        "repeat 5 \"x\" must produce exactly 5 copies of \"x\"")))

(deftest repeat-without-count-produces-an-infinite-lazy-sequence
  (let [result (eval-dt-last
                "xs is repeat \"hello\""
                "xs |> take 3 |> force!")]
    (is (= ["hello" "hello" "hello"] result)
        "taking 3 from infinite repeat must give 3 copies of \"hello\"")))

(deftest iterate-builds-an-infinite-sequence-by-repeatedly-applying-a-function
  (let [result (eval-dt-last
                "powers is iterate [n -> n * 2] 1"
                "powers |> take 5 |> force!")]
    (is (= [1 2 4 8 16] result)
        "iterate doubling from 1, take 5, must produce [1 2 4 8 16]")))

(deftest cycle-produces-an-infinite-repeating-sequence-from-a-finite-collection
  (let [result (eval-dt-last
                "xs is cycle [1 2 3]"
                "xs |> take 7 |> force!")]
    (is (= [1 2 3 1 2 3 1] result)
        "cycling [1 2 3], take 7, must produce [1 2 3 1 2 3 1]")))

;; === Section 2: Exploration & Debug (Phase 2) ===

;; --- tap! -- the ONLY pipeline debug probe ---

(deftest tap-bang-bare-mode-shows-a-sample-of-data-at-its-pipeline-position
  ;; BDD: tap! bare mode shows a sample of data at its pipeline position
  ;; Verifies: "--- tap! ---" header is printed, data items appear in output, result unchanged
  (let [{:keys [result output]}
        (capture-eval-dt-last
         "data is [1 2 3 4 5]"
         "data |> tap!")]
    (testing "bare mode prints '--- tap! ---' header"
      (is (clojure.string/includes? output "--- tap! ---")
          "bare tap! must print '--- tap! ---' header line"))
    (testing "bare mode includes data elements in output"
      (is (clojure.string/includes? output "1")
          "bare tap! must print data elements")
      (is (clojure.string/includes? output "5")
          "bare tap! must print data elements"))
    (testing "bare mode returns data unchanged (passthrough)"
      (is (= [1 2 3 4 5] result)
          "bare tap! must return the original data unchanged"))))

(deftest tap-bang-labeled-mode-prints-a-header-then-shows-the-sample
  ;; BDD: tap! labeled mode prints a header then shows the sample
  ;; Verifies: "--- label ---" header format, data in output, result unchanged
  (let [{:keys [result output]}
        (capture-eval-dt-last
         "data is [10 20 30]"
         "data |> tap! \"after filter\"")]
    (testing "labeled mode prints '--- after filter ---' header"
      (is (clojure.string/includes? output "--- after filter ---")
          "labeled tap! must print '--- <label> ---' header line"))
    (testing "labeled mode includes data elements in output"
      (is (clojure.string/includes? output "10")
          "labeled tap! must print data elements"))
    (testing "labeled mode returns data unchanged (passthrough)"
      (is (= [10 20 30] result)
          "labeled tap! must return the original data unchanged"))))

(deftest tap-bang-lambda-mode-applies-the-function-to-the-sample-for-display-only
  ;; BDD: tap! lambda mode applies the function to the sample for display only
  ;; Verifies: fn result appears in output, original data flows through unchanged
  (let [{:keys [result output]}
        (capture-eval-dt-last
         "data is [1 2 3]"
         "data |> tap! [d -> count d]")]
    (testing "lambda mode prints the function's return value"
      (is (clojure.string/includes? output "3")
          "lambda tap! must print the result of applying fn to the sample"))
    (testing "lambda mode returns ORIGINAL data, not the fn result"
      (is (= [1 2 3] result)
          "lambda tap! must not affect pipeline data -- original [1 2 3] must flow through"))))

(deftest tap-bang-takes-a-micro-sample-and-does-not-force-full-evaluation
  ;; BDD: tap! takes a micro-sample and does not force full evaluation
  ;; Verifies: tap! on a lazy seq only takes up to SAMPLE_SIZE=100 elements for display,
  ;; does not realize the entire (potentially infinite) sequence.
  ;; We use a range of 10,000 to confirm tap! doesn't hang or take all elements.
  (let [{:keys [result output]}
        (capture-eval-dt-last
         "nums is range 10000"
         "nums |> tap!")]
    (testing "micro-sample mode prints the '--- tap! ---' header"
      (is (clojure.string/includes? output "--- tap! ---")
          "tap! on lazy seq must print header"))
    (testing "tap! on lazy seq returns the original lazy seq (passthrough)"
      ;; We force just enough to verify it's a sequence of numbers
      (is (sequential? result)
          "tap! must return the original lazy sequence unchanged"))
    (testing "tap! output contains at most SAMPLE_SIZE=100 elements, not all 10000"
      ;; range 10000 starts at 0; a 100-element sample covers values 0..99.
      ;; The value 100 (the 101st element) must not appear in the printed output.
      ;; We check for " 100 " (space-padded) to avoid false matches on e.g. "1000".
      (is (not (clojure.string/includes? output " 100 "))
          "tap! must display at most 100 elements (SAMPLE_SIZE), not all 10000"))))

(deftest tap-bang-returns-its-input-unchanged-passthrough-semantics
  ;; tap! must be transparent: data flows through, pipeline result is unchanged.
  (let [result (silent-eval-dt-last
                "data is [1 2 3 4 5]"
                "data |> filter _ > 2 |> tap! |> map _ * 10 |> force!")]
    (is (= [30 40 50] result)
        "tap! must not affect pipeline result -- [30 40 50] must come through")))

(deftest multiple-tap-bang-calls-each-show-data-at-their-respective-pipeline-point
  ;; BDD: Multiple tap! calls each show data at their respective pipeline point
  ;; Verifies: two labeled tap! calls each print their own header, data unchanged end-to-end
  (let [{:keys [result output]}
        (capture-eval-dt-last
         "data is [1 2 3 4 5]"
         "data |> tap! \"raw\" |> map _ * 2 |> tap! \"doubled\"")]
    (testing "first tap! prints its label"
      (is (clojure.string/includes? output "--- raw ---")
          "first tap! must print '--- raw ---' header"))
    (testing "second tap! prints its label"
      (is (clojure.string/includes? output "--- doubled ---")
          "second tap! must print '--- doubled ---' header"))
    (testing "first tap! shows data before map (contains original values)"
      (is (clojure.string/includes? output "1")
          "first tap! must show values before map transformation"))
    (testing "second tap! shows data after map (contains doubled values)"
      (is (clojure.string/includes? output "10")
          "second tap! must show values after map _ * 2"))
    (testing "final result is the doubled sequence (tap! calls did not affect pipeline)"
      (is (= [2 4 6 8 10] result)
          "multiple tap! calls must not affect the pipeline result"))))

(deftest inspect-is-not-a-pipeline-debug-tool
  ;; inspect used in a pipeline must raise a runtime error.
  (is (throws? "data is [1 2 3]\ndata |> filter _ > 1 |> inspect |> map _ * 2")
      "inspect in a pipeline must raise a runtime error"))

(deftest log-bang-is-not-a-pipeline-debug-tool
  ;; log! used in a pipeline must raise a runtime error.
  (is (throws? "data is [1 2 3]\ndata |> filter _ > 1 |> log! \"step\" |> map _ * 2")
      "log! in a pipeline must raise a runtime error"))

;; --- autotap! ---

(deftest autotap-bang-placed-at-start-instruments-every-subsequent-step
  (testing "stub -- not yet implemented"))
;; autotap! is not yet in the stdlib.

(deftest autotap-bang-output-format-is-function-label-on-first-line-sample-on-second
  (testing "stub -- not yet implemented"))
;; autotap! output format requires autotap! implementation.

(deftest autotap-bang-is-equivalent-to-inserting-tap-bang-before-each-step
  (testing "stub -- not yet implemented"))
;; autotap! macro-like transformation is not yet implemented.

;; --- REPL micro-sampling ---

(deftest repl-auto-samples-a-lazy-pipeline-for-instant-preview
  (testing "stub -- not yet implemented"))
;; REPL display behavior (micro-sample, estimated row count) is not a unit
;; test concern -- it belongs to the REPL renderer which is not yet implemented.

(deftest repl-preview-shows-tabular-format-for-collections-of-objects
  (testing "stub -- not yet implemented"))
;; Tabular REPL rendering is not yet implemented.

(deftest repl-shows-full-result-for-small-collections-that-fit-within-sample-size
  (testing "stub -- not yet implemented"))
;; REPL display protocol is not yet implemented.

(deftest repl-preview-for-a-scalar-result-shows-the-value-directly
  (testing "stub -- not yet implemented"))
;; REPL scalar display (no micro-sampling for scalars) is not yet implemented.

(deftest repl-uses-first-n-sampling-strategy-by-default
  (testing "stub -- not yet implemented"))
;; REPL first-N sampling strategy is not yet implemented.

(deftest repl-does-not-display-lazysq-references
  (testing "stub -- not yet implemented"))
;; REPL output never showing "LazySeq@..." requires REPL renderer implementation.

;; --- Reified pipeline: DTPipeline record ---

(deftest pipeline-is-a-first-class-dtpipeline-value-that-can-be-bound-to-a-name
  (testing "stub -- not yet implemented"))
;; DTPipeline record (distinct from Clojure LazySeq) is not yet implemented.

(deftest dtpipeline-retains-metadata-about-each-step
  (testing "stub -- not yet implemented"))
;; Step metadata (labels, source locations) in DTPipeline is not yet implemented.

(deftest dtpipeline-caches-a-sample-at-each-step-boundary-after-first-execution
  (testing "stub -- not yet implemented"))
;; Per-step sample caching in DTPipeline is not yet implemented.

(deftest reassigning-a-pipeline-name-invalidates-the-old-pipeline-cache
  (testing "stub -- not yet implemented"))
;; DTPipeline cache invalidation on reassignment is not yet implemented.

;; --- dtw.inspect ---

(deftest dtw-inspect-returns-sample-data-after-a-specific-pipeline-step
  (testing "stub -- not yet implemented"))
;; dtw.inspect requires DTPipeline with per-step sample cache.

(deftest dtw-inspect-on-an-un-executed-pipeline-triggers-execution-first
  (testing "stub -- not yet implemented"))
;; dtw.inspect lazy execution trigger requires DTPipeline implementation.

;; --- Exploration functions ---

(deftest describe-shows-statistical-summary-of-a-dataset-using-a-sample
  ;; BDD: describe returns per-column stats map with :count :nil-count :type for all columns,
  ;; plus :min :max :sum :mean for numeric columns.
  (let [result (eval-dt-last
                "data is [{name: \"Alice\" age: 30 score: 95} {name: \"Bob\" age: 25 score: 72} {name: \"Carol\" age: 35 score: 88}]"
                "result is data |> describe"
                "result")]
    (is (map? result)
        "describe returns a map")
    (is (contains? result "age")
        "describe result contains the age column")
    (is (contains? result "score")
        "describe result contains the score column")
    (is (= 3 (get-in result ["age" :count]))
        "age column count is 3")
    (is (= 0 (get-in result ["age" :nil-count]))
        "age column nil-count is 0")
    (is (= "Integer" (get-in result ["age" :type]))
        "age column type is Integer")
    (is (= 25 (get-in result ["age" :min]))
        "age min is 25")
    (is (= 35 (get-in result ["age" :max]))
        "age max is 35")
    (is (= 90.0 (get-in result ["age" :sum]))
        "age sum is 90.0")
    (is (= 30.0 (get-in result ["age" :mean]))
        "age mean is 30.0")
    (is (= "String" (get-in result ["name" :type]))
        "name column type is String")
    (is (= "Alice" (get-in result ["name" :min]))
        "name min is Alice (alphabetical)")
    (is (= "Carol" (get-in result ["name" :max]))
        "name max is Carol (alphabetical)")))

(deftest describe-with-explicit-sample-size-override
  ;; BDD: describe 5000 uses a 5000-row sample overriding default DESCRIBE_SAMPLE_SIZE.
  ;; We test with a concrete small collection and explicit sample size of 2,
  ;; verifying that only 2 rows are included in the stats.
  (let [result (eval-dt "[{x: 1} {x: 2} {x: 3}] |> describe 2")]
    (is (map? result)
        "describe with sample size returns a map")
    (is (= 2 (get-in result ["x" :count]))
        "describe 2 samples only 2 rows (not all 3)")))

(deftest schema-shows-column-names-and-inferred-types-using-a-small-sample
  ;; BDD: schema returns a collection of maps with keys name and type per column.
  (let [result (eval-dt-last
                "data is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 25}]"
                "result is data |> schema"
                "result")]
    (is (sequential? result)
        "schema returns a collection")
    (is (= 2 (count result))
        "schema returns one entry per column (2 columns: name, age)")
    (is (every? map? result)
        "each schema entry is a map")
    (is (every? #(contains? % :name) result)
        "each schema entry has a :name key")
    (is (every? #(contains? % :type) result)
        "each schema entry has a :type key")
    (let [by-col (into {} (map (fn [m] [(:name m) (:type m)]) result))]
      (is (= "String" (get by-col "name"))
          "name column inferred as String")
      (is (= "Integer" (get by-col "age"))
          "age column inferred as Integer"))))

(deftest sample-returns-n-randomly-selected-elements
  ;; BDD: sample 20 returns exactly 20 elements from the collection.
  ;; Forces partial materialization (not lazy).
  (let [result (eval-dt "[1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25] |> sample 10")]
    (is (vector? result)
        "sample returns a materialized vector")
    (is (= 10 (count result))
        "sample 10 returns exactly 10 elements")
    ;; All returned elements must come from the original collection
    (is (every? #(<= 1 % 25) result)
        "all sampled elements are from the source collection"))
  ;; sample with default N = 10
  (let [result (eval-dt "[1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20] |> sample")]
    (is (= 10 (count result))
        "sample with no argument defaults to 10 elements")))

(deftest freq-shows-exact-frequency-table-for-a-field-forces-full-evaluation
  ;; BDD: freq returns {value count pct} maps sorted by count desc.
  ;; Forces full evaluation. EU appears 3 times (75%), US once (25%).
  (let [result (eval-dt-last
                "data is [{region: \"EU\"} {region: \"US\"} {region: \"EU\"} {region: \"EU\"}]"
                "result is data |> freq _.region"
                "result")]
    (is (sequential? result)
        "freq returns a collection")
    (is (= 2 (count result))
        "freq result has 2 entries (EU and US)")
    (is (every? map? result)
        "each freq entry is a map")
    (let [first-entry (first result)]
      (is (contains? first-entry :value)
          "freq entry has :value key")
      (is (contains? first-entry :count)
          "freq entry has :count key")
      (is (contains? first-entry :pct)
          "freq entry has :pct key"))
    ;; First entry is most frequent (EU)
    (let [eu-entry (first (filter #(= "EU" (:value %)) result))]
      (is (= 3 (:count eu-entry))
          "EU count is 3")
      (is (= 75.0 (:pct eu-entry))
          "EU pct is 75.0"))
    (let [us-entry (first (filter #(= "US" (:value %)) result))]
      (is (= 1 (:count us-entry))
          "US count is 1")
      (is (= 25.0 (:pct us-entry))
          "US pct is 25.0"))
    ;; Sorted by count descending: EU (3) before US (1)
    (is (= "EU" (:value (first result)))
        "freq results are sorted by count descending")))

(deftest histogram-shows-distribution-of-a-numeric-field-using-a-sample
  ;; BDD: histogram returns a map with :bins and :bin-count.
  ;; Bins have :from :to :count. Uses 10 bins by default.
  (let [result (eval-dt-last
                "data is [{age: 20} {age: 25} {age: 30} {age: 35} {age: 40}]"
                "result is data |> histogram _.age"
                "result")]
    (is (map? result)
        "histogram returns a map")
    (is (contains? result :bins)
        "histogram result has :bins key")
    (is (contains? result :bin-count)
        "histogram result has :bin-count key")
    (is (= 10 (:bin-count result))
        "default bin-count is 10")
    (is (= 10 (count (:bins result)))
        "there are 10 bins")
    (is (every? map? (:bins result))
        "each bin is a map")
    (let [first-bin (first (:bins result))]
      (is (contains? first-bin :from)
          "bin has :from key")
      (is (contains? first-bin :to)
          "bin has :to key")
      (is (contains? first-bin :count)
          "bin has :count key"))
    ;; Total count across all bins equals number of data points
    (is (= 5 (reduce + (map :count (:bins result))))
        "total count across bins equals 5 (all data points)")))

(deftest explain-shows-the-pipeline-execution-plan-without-accessing-data
  ;; BDD: explain shows human-readable plan. For concrete collections returns
  ;; a summary string. For lazy pipelines indicates laziness without forcing eval.
  (let [result (eval-dt "[1 2 3 4 5] |> explain")]
    (is (string? result)
        "explain returns a string")
    (is (clojure.string/includes? result "5")
        "explain on materialized collection mentions item count"))
  (let [result (eval-dt "[1 2 3 4 5] |> filter _ > 2 |> explain")]
    (is (string? result)
        "explain on lazy pipeline returns a string")
    (is (not (clojure.string/includes? result "Exception"))
        "explain does not throw or include error text")))

;; --- System constants and configuration ---

(deftest sample-size-constant-has-default-value-100
  (testing "SAMPLE_SIZE bare name and dtw.SAMPLE_SIZE both resolve to 100 by default"
    (try
      (is (= 100 (eval-dt "dtw.SAMPLE_SIZE")))
      (is (= 100 (eval-dt "SAMPLE_SIZE")))
      (finally (config/reset-config!)))))

(deftest set-bang-dtw-constant-changes-a-system-constant-and-dot-access-reads-it-back
  (testing "set! dtw.SAMPLE_SIZE changes the constant; dtw.SAMPLE_SIZE reads the new value back"
    (try
      (silent-eval-dt-last "set! dtw.SAMPLE_SIZE 50")
      (is (= 50 (eval-dt "dtw.SAMPLE_SIZE")))
      (is (= 50 (eval-dt "SAMPLE_SIZE")))
      (finally (config/reset-config!)))))

(deftest sample-size-affects-how-many-rows-tap-bang-and-repl-preview-show
  (testing "setting SAMPLE_SIZE limits how many rows tap! prints"
    (try
      (silent-eval-dt-last "set! dtw.SAMPLE_SIZE 3")
      ;; tap! on a 10-element list prints at most SAMPLE_SIZE rows (3)
      (let [{:keys [output]} (capture-eval-dt-last
                              "set! dtw.SAMPLE_SIZE 3"
                              "[1 2 3 4 5 6 7 8 9 10] |> tap!")]
        (is (clojure.string/includes? output "[1 2 3]"))
        (is (not (clojure.string/includes? output "4"))))
      (finally (config/reset-config!)))))

(deftest describe-sample-size-has-default-value-1000
  (testing "DESCRIBE_SAMPLE_SIZE bare name and dtw.DESCRIBE_SAMPLE_SIZE resolve to 1000 by default"
    (try
      (is (= 1000 (eval-dt "dtw.DESCRIBE_SAMPLE_SIZE")))
      (is (= 1000 (eval-dt "DESCRIBE_SAMPLE_SIZE")))
      (finally (config/reset-config!)))))

(deftest print-width-has-default-value-120
  (testing "PRINT_WIDTH bare name and dtw.PRINT_WIDTH resolve to 120 by default"
    (try
      (is (= 120 (eval-dt "dtw.PRINT_WIDTH")))
      (is (= 120 (eval-dt "PRINT_WIDTH")))
      (finally (config/reset-config!)))))

(deftest max-collect-rows-has-default-value-nil-unlimited
  (testing "MAX_COLLECT_ROWS default is nil (unlimited)"
    (try
      (is (nil? (eval-dt "dtw.MAX_COLLECT_ROWS")))
      (is (nil? (eval-dt "MAX_COLLECT_ROWS")))
      (finally (config/reset-config!)))))

(deftest setting-max-collect-rows-enforces-a-safety-cap-on-force-bang
  (testing "force! on a 10-element list is capped to MAX_COLLECT_ROWS when set"
    (try
      (silent-eval-dt-last "set! dtw.MAX_COLLECT_ROWS 3")
      (let [result (silent-eval-dt "[1 2 3 4 5 6 7 8 9 10] |> force!")]
        (is (= 3 (count result)))
        (is (= [1 2 3] result)))
      (finally (config/reset-config!)))))

(deftest set-bang-dtw-constant-with-an-unknown-constant-raises-an-error-with-hint
  (testing "set! dtw.FOOBAR raises CONFIG ERROR with a hint listing valid constants"
    (try
      (let [caught (atom nil)]
        (try
          (eval-dt "set! dtw.FOOBAR 42")
          (catch Exception e
            (reset! caught e)))
        (is (some? @caught) "should have thrown an exception")
        (when @caught
          (let [data (ex-data @caught)]
            (is (= "DT-R030" (:code data)))
            (is (= "CONFIG ERROR" (:category data)))
            (is (clojure.string/includes? (:hint data) "SAMPLE_SIZE")))))
      (finally (config/reset-config!)))))

;; === Section 3: Data Sources -- Databases (Phase 3, all @stub) ===

(deftest connect-to-a-postgresql-database
  (testing "stub -- not yet implemented"))

(deftest connect-with-explicit-credentials
  (testing "stub -- not yet implemented"))

(deftest reference-a-database-table-as-a-lazy-data-source
  (testing "stub -- not yet implemented"))

(deftest pipeline-over-a-database-table-is-lazy-until-a-terminal-operation
  (testing "stub -- not yet implemented"))

(deftest raw-sql-query-as-a-lazy-data-source
  (testing "stub -- not yet implemented"))

(deftest database-query-with-parameterized-sql
  (testing "stub -- not yet implemented"))

(deftest table-source-materializes-to-a-full-scan-on-force-bang
  (testing "stub -- not yet implemented"))

(deftest close-bang-explicitly-releases-a-database-connection
  (testing "stub -- not yet implemented"))

;; === Section 4: Data Sources -- Files (Phase 3, all @stub) ===

(deftest read-csv-file-as-lazy-sequence-of-maps
  (testing "stub -- not yet implemented"))

(deftest read-csv-with-explicit-options
  (testing "stub -- not yet implemented"))

(deftest read-csv-without-headers-produces-vectors-instead-of-maps
  (testing "stub -- not yet implemented"))

(deftest read-json-file-as-lazy-sequence-array-or-single-map-object
  (testing "stub -- not yet implemented"))

(deftest read-json-lines-newline-delimited-json-as-lazy-sequence-of-maps
  (testing "stub -- not yet implemented"))

(deftest read-text-file-as-lazy-sequence-of-lines
  (testing "stub -- not yet implemented"))

(deftest read-parquet-file-as-lazy-columnar-source
  (testing "stub -- not yet implemented"))

(deftest save-bang-writes-pipeline-output-to-a-file-and-returns-data-passthrough
  (testing "stub -- not yet implemented"))

(deftest save-bang-supports-multiple-file-formats-by-extension
  (testing "stub -- not yet implemented"))

(deftest into-bang-inserts-pipeline-output-into-a-database-table-and-returns-data-passthrough
  (testing "stub -- not yet implemented"))

(deftest streaming-pipeline-processes-large-file-without-unbounded-memory-use
  (testing "stub -- not yet implemented"))

(deftest lazy-pipeline-reuse-file-sources-re-open-on-each-materialization
  (testing "stub -- not yet implemented"))

(deftest connection-failure-raises-a-descriptive-error
  (testing "stub -- not yet implemented"))

(deftest file-not-found-raises-an-error-when-pipeline-is-first-evaluated
  (testing "stub -- not yet implemented"))

(deftest non-existent-field-access-in-a-pipeline-is-nil-tolerant
  (testing "stub -- not yet implemented"))

;; === Section 5: SQL Push-Down Optimization (Phase 4+, all @stub) ===

(deftest filter-pushes-down-to-sql-where-clause
  (testing "stub -- not yet implemented"))

(deftest sort-by-pushes-down-to-sql-order-by
  (testing "stub -- not yet implemented"))

(deftest take-pushes-down-to-sql-limit
  (testing "stub -- not yet implemented"))

(deftest map-with-field-selection-pushes-down-to-sql-select
  (testing "stub -- not yet implemented"))

(deftest count-on-a-database-source-pushes-down-to-sql-count
  (testing "stub -- not yet implemented"))

(deftest push-down-stops-at-non-translatable-operations-and-resumes-in-clojure
  (testing "stub -- not yet implemented"))

(deftest explain-on-a-database-pipeline-shows-generated-sql-and-execution-plan
  (testing "stub -- not yet implemented"))

(deftest schema-for-a-database-table-uses-database-metadata-not-row-sampling
  (testing "stub -- not yet implemented"))

(deftest nrepl-op-inspect-pipeline-step-returns-cached-step-sample-by-index
  (testing "stub -- not yet implemented"))
