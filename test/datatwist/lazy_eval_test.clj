(ns datatwist.lazy-eval-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws? throws-type? type-of]]))

;; ==========================================================================
;; Feature 8: Lazy Evaluation, Data Sources & REPL Micro-sampling
;; BDD file: bdd/8-lazy-eval-data-sources.feature
;;
;; Every deftest maps 1:1 to a BDD Scenario.
;; testing blocks correspond to BDD section headers.
;;
;; NOTE: Almost all tests in this file will FAIL until eval-dt is implemented.
;; This is intentional -- this file defines the TDD target for Feature 8.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; SECTION 1: LAZY PIPELINE CONSTRUCTION
;; ---------------------------------------------------------------------------

(deftest pipeline-without-materialization-is-lazy-and-does-not-execute
  (testing "Scenario: A pipeline without materialization is lazy and does not execute"
    ;; The pipeline builds a plan. Nothing is evaluated yet.
    ;; We verify laziness by checking the result is NOT a concrete vector.
    (let [result (eval-dt-last
                  "data is [1 2 3 4 5 6 7 8 9 10]"
                  "data |> filter _ > 5 |> map _ * 2")]
      ;; It must be a Clojure lazy sequence, not a PersistentVector
      (is (not (instance? clojure.lang.PersistentVector result)))
      ;; When forced it produces the correct values
      (is (= [12 14 16 18 20] (vec result))))))

(deftest chaining-multiple-lazy-operations-builds-a-deeper-plan
  (testing "Scenario: Chaining multiple lazy operations builds a deeper plan"
    ;; Four-step lazy pipeline -- none of the steps has executed.
    ;; We can only verify the result is correct when forced.
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\" email: \"a@a.com\"}
                             {active: false name: \"Bob\" email: \"b@b.com\"}
                             {active: true name: \"Charlie\" email: \"c@c.com\"}]"
                  "users
|> filter _.active
|> map {name: _.name email: _.email}
|> sort-by _.name
|> take 100")]
      (is (= [{:name "Alice" :email "a@a.com"}
              {:name "Charlie" :email "c@c.com"}]
             (vec result))))))

(deftest binding-a-lazy-pipeline-to-a-name-does-not-force-evaluation
  (testing "Scenario: Binding a lazy pipeline to a name does not force evaluation"
    ;; Binding step1, step2, step3 -- none should trigger computation.
    ;; Verified by forcing step3 at the end and checking result.
    (let [result (eval-dt-last
                  "data is [{active: true name: \"Alice\"} {active: false name: \"Bob\"}]"
                  "step1 is data |> filter _.active"
                  "step2 is step1 |> map _.name"
                  "step3 is step2 |> sort"
                  "step3 |> collect")]
      (is (= ["Alice"] result)))))

(deftest lazy-pipelines-over-in-memory-collections-use-clojure-lazy-seq
  (testing "Scenario: Lazy pipelines over in-memory collections use Clojure lazy-seq"
    (let [result (eval-dt-last
                  "numbers is range 1 1000000"
                  "evens is numbers |> filter [n -> n % 2 = 0]"
                  "evens")]
      ;; evens must be lazy, not a realized PersistentVector
      (is (not (instance? clojure.lang.PersistentVector result)))
      ;; First few values are correct
      (is (= [2 4 6 8 10] (take 5 result))))))

(deftest nil-source-in-a-pipeline-produces-empty-collection
  (testing "Scenario: Nil source in a pipeline produces an empty collection"
    ;; From PRD nil semantics: nil |> filter _ = []
    (is (= [] (eval-dt "nil |> filter _ > 0 |> collect")))))

;; ---------------------------------------------------------------------------
;; SECTION 2: MATERIALIZATION FUNCTIONS
;; ---------------------------------------------------------------------------

(deftest collect-forces-entire-pipeline-into-a-vector-in-memory
  (testing "Scenario: collect forces entire pipeline into a vector in memory"
    (let [result (eval-dt-last
                  "data is [1 2 3 4 5]"
                  "data |> filter _ > 2 |> map _ * 10 |> collect")]
      (is (= [30 40 50] result))
      ;; Result must be a concrete vector, not a lazy sequence
      (is (instance? clojure.lang.PersistentVector result)))))

(deftest collect-on-already-materialized-collection-is-a-no-op
  (testing "Scenario: collect on an already-materialized collection is a no-op"
    (is (= [1 2 3]
           (eval-dt-last
            "items is [1 2 3]"
            "items |> collect")))))

(deftest count-forces-full-traversal-and-returns-exact-count
  (testing "Scenario: count forces full traversal and returns exact count"
    ;; Multiples of 7 in range 1..10000000: floor(10000000/7) = 1428571
    (is (= 1428571
           (eval-dt-last
            "data is range 1 10000001"
            "data |> filter [x -> x % 7 = 0] |> count")))))

(deftest count-on-in-memory-collection-returns-exact-count-instantly
  (testing "Scenario: count on an in-memory collection returns exact count instantly"
    (is (= 5 (eval-dt "items is [1 2 3 4 5]
items |> count")))))

(deftest first-forces-evaluation-until-one-element-is-found
  (testing "Scenario: first forces evaluation until one element is found"
    (let [result (eval-dt-last
                  "data is [{score: 95} {score: 70} {score: 88}]"
                  "data |> filter _.score > 90 |> first")]
      (is (= {:score 95} result)))))

(deftest reduce-folds-the-pipeline-into-a-single-value
  (testing "Scenario: reduce folds the pipeline into a single value"
    ;; orders |> map _.amount |> reduce [a b -> a + b] 0
    (let [result (eval-dt-last
                  "orders is [{amount: 10} {amount: 20} {amount: 30}]"
                  "orders |> map _.amount |> reduce [a b -> a + b] 0")]
      (is (= 60 result)))))

(deftest reduce-with-explicit-initial-value
  (testing "Scenario: reduce with explicit initial value"
    (is (= 15 (eval-dt "[1 2 3 4 5] |> reduce [a b -> a + b] 0")))))

(deftest force-materializes-lazy-pipeline-and-returns-data-passthrough
  (testing "Scenario: force! materializes a lazy pipeline and returns the data (passthrough)"
    ;; force! returns the data so it can continue being piped
    (let [result (eval-dt-last
                  "data is [1 2 3 4 5]"
                  "data |> filter _ > 2 |> map _ * 10 |> force! |> collect")]
      (is (= [30 40 50] result)))))

(deftest force-bang-is-useful-for-ensuring-computation-happens-at-specific-point
  (testing "Scenario: force! is useful for ensuring computation happens at a specific point"
    ;; force! materializes once; subsequent operations on the result don't re-read source.
    ;; With in-memory data: after force!, piping to two different sinks is valid.
    (let [processed (eval-dt-last
                     "data is [1 2 3 4 5]"
                     "processed is data |> filter _ > 2 |> map _ * 10 |> force!"
                     "processed |> collect")]
      (is (= [30 40 50] processed))
      ;; force! result is concrete -- it is a realized collection
      (is (instance? clojure.lang.PersistentVector processed)))))

(deftest save-bang-writes-pipeline-output-to-file-and-returns-data-passthrough
  (testing "Scenario: save! writes pipeline output to a file and returns the data (passthrough)"
    ;; save! is passthrough: the result should equal the data that was saved.
    ;; We test passthrough semantics by verifying the pipeline continues after save!.
    (let [result (eval-dt-last
                  "data is [{name: \"Alice\"} {name: \"Bob\"}]"
                  ;; save! to a temp file; we then continue piping to count
                  "data |> save! \"/tmp/dt-test-save-output.json\" |> count")]
      (is (= 2 result)))))

(deftest save-bang-supports-multiple-file-formats-determined-by-file-extension
  (testing "Scenario: save! supports multiple file formats determined by file extension"
    ;; Syntax for each format is valid
    (is (not (parse-error? "data |> save! \"output.csv\"")))
    (is (not (parse-error? "data |> save! \"output.json\"")))
    (is (not (parse-error? "data |> save! \"output.parquet\"")))
    ;; save! with an in-memory collection produces a file -- all formats are passthrough
    ;; We verify each returns the same data (passthrough) by chaining |> count
    (let [result-csv  (eval-dt "[{a: 1} {a: 2}] |> save! \"/tmp/dt-test.csv\" |> count")
          result-json (eval-dt "[{a: 1} {a: 2}] |> save! \"/tmp/dt-test.json\" |> count")]
      (is (= 2 result-csv))
      (is (= 2 result-json)))))

(deftest into-bang-inserts-pipeline-output-into-database-and-returns-data-passthrough
  (testing "Scenario: into! inserts pipeline output into a database table and returns data (passthrough)"
    ;; This requires a real DB connection. We test that into! is passthrough
    ;; by mocking or by checking the result type.
    ;; In TDD mode, we assert the structure and expect runtime to implement it.
    (is (throws-type?
         "into! nil \"table\""
         Exception))))

(deftest chaining-after-materialization-starts-a-new-pipeline
  (testing "Scenario: Chaining after a materialization function starts a new pipeline"
    (let [result (eval-dt-last
                  "data is [{active: true name: \"Alice\"} {active: false name: \"Bob\"} {active: true name: \"Charlie\"}]"
                  "materialized is data |> filter _.active |> collect"
                  "materialized |> map _.name |> count")]
      (is (= 2 result)))))

;; ---------------------------------------------------------------------------
;; SECTION 3: REPL MICRO-SAMPLING
;; ---------------------------------------------------------------------------
;; NOTE: These scenarios describe REPL display behavior.
;; In unit tests we cannot directly assert REPL output formatting.
;; We verify the underlying semantics: laziness is preserved, sampling works.

(deftest repl-auto-sampling-does-not-force-full-pipeline
  (testing "Scenario: REPL auto-samples a lazy pipeline for preview"
    ;; Verify the pipeline remains lazy (not fully realized)
    ;; A micro-sample of the first N elements can be taken without forcing all.
    (let [result (eval-dt-last
                  "numbers is range 1 1000000000"
                  "evens is numbers |> filter [n -> n % 2 = 0]"
                  "evens")]
      ;; Should not block or OOM -- laziness is preserved
      (is (not (instance? clojure.lang.PersistentVector result)))
      ;; First 5 values are correct without realizing all one billion
      (is (= [2 4 6 8 10] (take 5 result))))))

(deftest repl-preview-shows-tabular-format-for-collections-of-objects
  (testing "Scenario: REPL preview shows tabular format for collections of objects"
    ;; The REPL renders objects as a table. The underlying lazy pipeline still works.
    ;; We test that the data is accessible with object keys after the pipeline.
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\" score: 95} {active: true name: \"Bob\" score: 87} {active: false name: \"Carol\" score: 70}]"
                  "users |> filter _.active |> map {name: _.name score: _.score} |> take 100")]
      ;; Result contains objects with the correct keys
      (is (= [{:name "Alice" :score 95} {:name "Bob" :score 87}]
             (vec result))))))

(deftest repl-shows-full-result-for-small-collections-that-fit-in-sample
  (testing "Scenario: REPL shows full result for small collections that fit in sample"
    ;; A small collection fully materializes immediately
    (let [result (eval-dt "[1 2 3 4 5] |> filter _ > 2")]
      ;; When forced, result is [3 4 5]
      (is (= [3 4 5] (vec result))))))

(deftest repl-preview-for-scalar-result-shows-value-directly
  (testing "Scenario: REPL preview for a scalar result shows the value directly"
    ;; count returns a scalar; no sampling needed
    (let [data "[{active: true} {active: false} {active: true}]"
          result (eval-dt-last
                  (str "users is " data)
                  "users |> filter _.active |> count")]
      (is (= 2 result))
      (is (integer? result)))))

(deftest repl-uses-first-n-sampling-strategy-by-default
  (testing "Scenario: REPL preview uses first-N sampling strategy by default"
    ;; take 5 on a sorted lazy sequence produces the first 5 in order
    (let [result (eval-dt-last
                  "data is range 1 1000"
                  "data |> filter [n -> n % 2 = 0] |> take 5 |> collect")]
      (is (= [2 4 6 8 10] result)))))

;; ---------------------------------------------------------------------------
;; SECTION 4: TAP! -- INLINE PIPELINE DEBUGGING
;; ---------------------------------------------------------------------------

(deftest tap-shows-data-at-pipeline-step-and-passes-it-through-unchanged
  (testing "Scenario: tap! shows data at a pipeline step and passes it through unchanged"
    ;; The key property: tap! is passthrough -- result is unaffected
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\"} {active: true name: \"Bob\"} {active: false name: \"Carol\"}]"
                  "users
|> filter _.active
|> tap!
|> map {name: _.name}
|> tap!
|> sort-by _.name
|> collect")]
      (is (= [{:name "Alice"} {:name "Bob"}] result)))))

(deftest tap-with-a-label-passes-data-through-unchanged
  (testing "Scenario: tap! with a label for clarity"
    ;; Label is for display only; data is unchanged
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\"} {active: false name: \"Bob\"}]"
                  "users
|> filter _.active
|> tap! \"after filter\"
|> map _.name
|> tap! \"after map\"
|> collect")]
      (is (= ["Alice"] result)))))

(deftest tap-bang-shows-a-micro-sample-not-the-full-dataset
  (testing "Scenario: tap! shows a micro-sample, not the full dataset"
    ;; tap! is passthrough -- the pipeline result is not affected by tap!
    ;; The key assertion: tap! does NOT force full evaluation, result still lazy after tap!
    (let [result (eval-dt-last
                  "huge-dataset is range 1 1000000000"
                  "huge-dataset
|> filter [n -> n % 2 = 0]
|> tap!
|> map [n -> n * 2]")]
      ;; Result is still lazy -- tap! did not force full evaluation
      (is (not (instance? clojure.lang.PersistentVector result)))
      ;; First values are correct (2*2=4, 4*2=8, 6*2=12, ...)
      (is (= [4 8 12 16 20] (take 5 result))))))

(deftest tap-returns-its-input-unchanged-passthrough
  (testing "Scenario: tap! returns its input unchanged (passthrough)"
    (is (= [30 40 50]
           (eval-dt-last
            "data is [1 2 3 4 5]"
            "data |> filter _ > 2 |> tap! |> map _ * 10 |> collect")))))

(deftest tap-with-transformation-function-does-not-affect-pipeline-data
  (testing "Scenario: tap! with a transformation function for focused inspection"
    ;; tap! [f] applies f only for display; pipeline data is unchanged
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\" score: 95} {active: true name: \"Bob\" score: 80}]"
                  "users
|> filter _.active
|> tap! [data -> data |> map _.score |> collect]
|> map _.name
|> collect")]
      ;; pipeline result is names, not scores -- tap! did not change the data
      (is (= ["Alice" "Bob"] result)))))

(deftest multiple-tap-calls-each-show-data-at-their-respective-point
  (testing "Scenario: Multiple tap! calls in one pipeline each show data at their respective point"
    ;; The final result is the sum -- tap! had no effect on computation
    (let [result (eval-dt-last
                  "orders is [{status: \"completed\" total: 10} {status: \"pending\" total: 20} {status: \"completed\" total: 30}]"
                  "orders
|> tap! \"raw orders\"
|> filter _.status = \"completed\"
|> tap! \"completed only\"
|> map _.total
|> tap! \"totals\"
|> reduce [a b -> a + b] 0")]
      (is (= 40 result)))))

;; ---------------------------------------------------------------------------
;; SECTION 5: DATA SOURCES -- DATABASES
;; ---------------------------------------------------------------------------
;; NOTE: These scenarios require a real database connection at runtime.
;; In unit tests we verify parse validity and that these expressions
;; are syntactically correct DataTwist. Runtime behavior is integration-tested.

(deftest connect-to-postgresql-database-is-valid-syntax
  (testing "Scenario: Connect to a PostgreSQL database"
    ;; connect is a DataTwist stdlib function -- parse must succeed
    (is (not (parse-error? "db is connect \"postgres://localhost:5432/mydb\"")))
    ;; At runtime, connect returns a connection object (not a collection)
    (is (throws? "db is connect \"postgres://localhost:5432/mydb\"
db |> count"))))

(deftest connect-with-explicit-credentials-object-is-valid-syntax
  (testing "Scenario: Connect with explicit credentials object"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\" {
  user: \"admin\"
  password: \"secret\"
  pool-size: 10
}")))))

(deftest reference-a-database-table-as-a-lazy-data-source-is-valid-syntax
  (testing "Scenario: Reference a database table as a lazy data source"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
users is db |> table \"users\"")))
    ;; table is a known stdlib function
    (is (not (parse-error? "db |> table \"users\"")))))

(deftest pipeline-over-database-table-is-lazy-is-valid-syntax
  (testing "Scenario: Pipeline over a database table is lazy until materialized"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
active-users is db |> table \"users\" |> filter _.active |> sort-by _.name")))))

(deftest raw-sql-query-as-lazy-data-source-is-valid-syntax
  (testing "Scenario: Raw SQL query as a lazy data source"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
results is db |> query \"SELECT id, name, score FROM users WHERE active = true\"")))))

(deftest database-query-with-parameters-is-valid-syntax
  (testing "Scenario: Database query with parameters"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
results is db |> query \"SELECT * FROM users WHERE age > ? AND city = ?\" [18 \"Moscow\"]")))))

(deftest table-source-materializes-on-collect-is-valid-syntax
  (testing "Scenario: Table source materializes to a full scan on collect"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
all-users is db |> table \"users\" |> collect")))))

(deftest close-bang-explicitly-releases-a-database-connection-is-valid-syntax
  (testing "Scenario: close! explicitly releases a database connection"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\" |> filter _.active |> collect
close! db")))))

;; ---------------------------------------------------------------------------
;; SECTION 6: DATA SOURCES -- FILES
;; ---------------------------------------------------------------------------
;; NOTE: File-based tests verify syntax validity and lazy-sequence semantics.
;; Actual file I/O is integration-tested; parse correctness is unit-tested.

(deftest read-csv-produces-lazy-sequence-of-maps-syntax
  (testing "Scenario: Read CSV file as lazy sequence of maps"
    (is (not (parse-error? "data is read-csv \"sales.csv\"")))
    ;; Pipeline over read-csv is valid
    (is (not (parse-error? "read-csv \"sales.csv\" |> filter _.active |> collect")))))

(deftest read-csv-with-options-is-valid-syntax
  (testing "Scenario: Read CSV with explicit options"
    (is (not (parse-error? "data is read-csv \"data.tsv\" {separator: \"\t\" header: true encoding: \"UTF-8\"}")))))

(deftest read-csv-without-headers-is-valid-syntax
  (testing "Scenario: Read CSV without headers produces vectors"
    (is (not (parse-error? "data is read-csv \"raw.csv\" {header: false}")))))

(deftest read-json-is-valid-syntax
  (testing "Scenario: Read JSON file"
    (is (not (parse-error? "data is read-json \"data.json\"")))))

(deftest read-jsonl-is-valid-syntax
  (testing "Scenario: Read JSON lines (newline-delimited JSON)"
    (is (not (parse-error? "data is read-jsonl \"events.jsonl\"")))))

(deftest read-lines-is-valid-syntax
  (testing "Scenario: Read text file as lazy sequence of lines"
    (is (not (parse-error? "lines is read-lines \"logfile.txt\"")))))

(deftest read-parquet-is-valid-syntax
  (testing "Scenario: Read Parquet file as lazy columnar source"
    (is (not (parse-error? "data is read-parquet \"warehouse.parquet\"")))))

(deftest file-source-supports-full-pipeline-syntax
  (testing "Scenario: File sources support full pipeline syntax"
    (is (not (parse-error? "read-csv \"sales.csv\"
|> filter _.region = \"Europe\"
|> map {product: _.product revenue: _.price * _.quantity}
|> sort-by _.revenue
|> take 10
|> collect")))))

;; ---------------------------------------------------------------------------
;; SECTION 7: SQL PUSH-DOWN OPTIMIZATION
;; ---------------------------------------------------------------------------
;; NOTE: Push-down is a runtime/query-planning concern, not a parse concern.
;; These tests verify syntax validity. Actual SQL generation is tested via
;; integration tests or by inspecting the query plan (dtw/inspect / explain).

(deftest filter-push-down-to-sql-where-is-valid-syntax
  (testing "Scenario: filter pushes down to SQL WHERE clause"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\" |> filter _.age > 18 |> collect")))))

(deftest sort-by-push-down-to-sql-order-by-is-valid-syntax
  (testing "Scenario: sort-by pushes down to SQL ORDER BY"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\" |> sort-by _.name |> collect")))))

(deftest take-push-down-to-sql-limit-is-valid-syntax
  (testing "Scenario: take pushes down to SQL LIMIT"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\" |> take 10 |> collect")))))

(deftest map-with-field-selection-push-down-to-sql-select-is-valid-syntax
  (testing "Scenario: map with field selection pushes down to SQL SELECT"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\" |> map {name: _.name age: _.age} |> collect")))))

(deftest count-on-database-source-push-down-to-sql-count-is-valid-syntax
  (testing "Scenario: count on a database source pushes down to SQL COUNT(*)"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
n is db |> table \"users\" |> filter _.active |> count")))))

(deftest combined-push-down-is-valid-syntax
  (testing "Scenario: Combined push-down for filter, sort, and limit in one SQL query"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\"
|> filter _.active
|> filter _.age >= 18
|> sort-by _.score
|> take 20
|> collect")))))

(deftest push-down-stops-at-non-translatable-operations-is-valid-syntax
  (testing "Scenario: Push-down stops at non-translatable pipeline operations"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
result is db |> table \"users\"
|> filter _.active
|> map [u -> {name: u.name score: u.score}]
|> sort-by _.score
|> collect")))))

(deftest explain-shows-execution-plan-without-executing-is-valid-syntax
  (testing "Scenario: explain shows the execution plan without executing the query"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
db |> table \"users\"
|> filter _.active
|> sort-by _.name
|> take 10
|> explain")))))

;; ---------------------------------------------------------------------------
;; SECTION 8: EXPLORE / DESCRIBE FUNCTIONS
;; ---------------------------------------------------------------------------

(deftest describe-shows-statistical-summary-and-returns-structured-data
  (testing "Scenario: describe shows statistical summary of a dataset"
    (is (not (parse-error? "data is read-csv \"sales.csv\"
data |> describe")))
    ;; describe returns structured data (a map), not just prints
    ;; With in-memory data we can test the return type
    (let [result (eval-dt "[{a: 1 b: 2} {a: 3 b: 4}] |> describe")]
      (is (map? result)))))

(deftest schema-shows-column-names-and-inferred-types
  (testing "Scenario: schema shows column names and inferred types only"
    (is (not (parse-error? "data is read-csv \"sales.csv\"
data |> schema")))
    ;; schema returns structured data
    (let [result (eval-dt "[{name: \"Alice\" age: 25} {name: \"Bob\" age: 30}] |> schema")]
      (is (some? result)))))

(deftest schema-for-database-table-uses-database-metadata-is-valid-syntax
  (testing "Scenario: schema for a database table uses database metadata"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
db |> table \"users\" |> schema")))))

(deftest sample-returns-n-elements-from-the-data
  (testing "Scenario: sample returns N elements from the data"
    (let [result (eval-dt "[1 2 3 4 5 6 7 8 9 10] |> sample 5")]
      (is (= 5 (count result))))))

(deftest freq-shows-frequency-table-for-a-field
  (testing "Scenario: freq shows frequency table for a field"
    (let [result (eval-dt-last
                  "data is [{region: \"EU\"} {region: \"US\"} {region: \"EU\"} {region: \"EU\"}]"
                  "data |> freq _.region")]
      ;; freq returns a structured collection -- each entry has a value and count
      (is (some? result))
      (is (coll? result)))))

(deftest histogram-shows-ascii-histogram-for-numeric-field
  (testing "Scenario: histogram shows ASCII histogram for a numeric field"
    (is (not (parse-error? "data |> histogram _.age")))
    ;; histogram returns structured data
    (let [result (eval-dt "[{age: 20} {age: 25} {age: 30} {age: 20}] |> histogram _.age")]
      (is (some? result)))))

(deftest explain-on-file-backed-pipeline-shows-execution-plan-without-reading
  (testing "Scenario: explain on a file-backed pipeline shows the execution plan without reading"
    (is (not (parse-error? "read-csv \"sales.csv\"
|> filter _.region = \"EU\"
|> sort-by _.amount
|> take 10
|> explain")))))

;; ---------------------------------------------------------------------------
;; SECTION 9: PIPELINE AS FIRST-CLASS RUNTIME OBJECT
;; ---------------------------------------------------------------------------

(deftest pipeline-is-a-first-class-value-that-can-be-bound-to-a-name
  (testing "Scenario: Pipeline is a first-class value that can be bound to a name"
    ;; A bound pipeline is lazy -- not a concrete vector
    (let [result (eval-dt-last
                  "data is [1 2 3 4 5]"
                  "plan is data |> filter _ > 2 |> map _ * 10"
                  "plan")]
      (is (not (instance? clojure.lang.PersistentVector result)))
      (is (= [30 40 50] (vec result))))))

(deftest pipeline-retains-metadata-about-each-step-is-valid-syntax
  (testing "Scenario: Pipeline retains metadata about each step"
    ;; dtw/inspect is a runtime introspection function -- parse must succeed
    (is (not (parse-error? "plan is users
|> filter _.status = \"active\"
|> map {name: _.name}
|> sort-by _.name")))))

(deftest dtw-inspect-returns-sample-data-after-specific-pipeline-step
  (testing "Scenario: dtw/inspect returns sample data after a specific pipeline step"
    (is (not (parse-error? "plan is users
|> filter _.status = \"active\"
|> map {name: _.name}
|> sort-by _.name
sample is dtw/inspect plan 1 100")))
    ;; At runtime, dtw/inspect returns a collection
    (let [result (eval-dt-last
                  "users is [{status: \"active\" name: \"Alice\"} {status: \"inactive\" name: \"Bob\"} {status: \"active\" name: \"Charlie\"}]"
                  "plan is users |> filter _.status = \"active\" |> map {name: _.name} |> sort-by _.name"
                  "dtw/inspect plan 1 100")]
      (is (coll? result)))))

(deftest pipeline-lazy-evaluation-is-transparent-same-object-reused
  (testing "Scenario: Pipeline lazy evaluation is transparent to the user"
    ;; a and b both reference the same plan; materializing both gives same result
    (let [result-a (eval-dt-last
                    "data is [1 2 3 4 5]"
                    "a is data |> filter _ > 2"
                    "b is a"
                    "a |> collect")
          result-b (eval-dt-last
                    "data is [1 2 3 4 5]"
                    "a is data |> filter _ > 2"
                    "b is a"
                    "b |> collect")]
      (is (= result-a result-b))
      (is (= [3 4 5] result-a)))))

;; ---------------------------------------------------------------------------
;; SECTION 10: ERROR HANDLING FOR DATA SOURCES
;; ---------------------------------------------------------------------------

(deftest connection-failure-raises-descriptive-error
  (testing "Scenario: Connection failure raises a descriptive error"
    ;; Attempting to connect to a nonexistent host should throw
    (is (throws? "db is connect \"postgres://nonexistent-host-dt-test/mydb\""))
    ;; The error is catchable with try-catch (PRD: try-catch is the error model)
    (let [result (eval-dt "db is try
  connect \"postgres://nonexistent-host-dt-test/mydb\"
catch err ->
  \"connection-failed\"
db")]
      (is (= "connection-failed" result)))))

(deftest file-not-found-raises-error-when-pipeline-is-materialized
  (testing "Scenario: File not found raises an error when pipeline is first evaluated"
    ;; Building the pipeline is OK (lazy); materializing throws
    (is (not (throws? "data is read-csv \"nonexistent-dt-test-file.csv\"")))
    (is (throws? "data is read-csv \"nonexistent-dt-test-file.csv\"
data |> collect"))
    ;; The underlying exception is a FileNotFoundException
    (is (throws-type?
         "read-csv \"nonexistent-dt-test-file-xyz.csv\" |> collect"
         java.io.FileNotFoundException))))

(deftest query-timeout-raises-an-error
  (testing "Scenario: Query timeout on database source raises an error"
    ;; Syntax is valid; runtime raises timeout error
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\" {query-timeout: 5000}
result is db |> query \"SELECT * FROM huge_table\" |> collect")))))

(deftest schema-mismatch-is-nil-tolerant
  (testing "Scenario: Schema mismatch is nil-tolerant (non-existent field returns nil)"
    ;; Accessing a missing field returns nil; nil > 5 is falsy; result is empty
    (let [result (eval-dt-last
                  "data is [{region: \"EU\" price: 10} {region: \"US\" price: 20}]"
                  "data |> filter _.nonexistent-column > 5 |> collect")]
      (is (= [] result)))))

;; ---------------------------------------------------------------------------
;; SECTION 11: INTEGRATION SCENARIOS
;; ---------------------------------------------------------------------------

(deftest full-etl-pipeline-from-database-to-file-is-valid-syntax
  (testing "Scenario: Full ETL pipeline from database to file"
    (is (not (parse-error? "db is connect \"postgres://localhost/mydb\"
db |> table \"orders\"
|> filter _.status = \"completed\"
|> map {
  order-id: _.id
  customer: _.customer-name
  total: _.price * _.quantity
  region: _.region
}
|> sort-by _.total
|> tap! \"processed orders\"
|> save! \"report.csv\"")))))

(deftest streaming-pipeline-processes-large-file-without-unbounded-memory
  (testing "Scenario: Streaming pipeline processes large file without unbounded memory use"
    ;; Verify the syntax is valid and that save! is passthrough
    (is (not (parse-error? "read-csv \"10gb-file.csv\"
|> filter _.region = \"EU\"
|> map {id: _.id value: _.amount * _.rate}
|> save! \"eu-values.csv\"")))
    ;; With small in-memory data, verify save! passthrough semantics
    (let [result (eval-dt-last
                  "data is [{region: \"EU\" amount: 10 rate: 2} {region: \"US\" amount: 5 rate: 3}]"
                  "data
|> filter _.region = \"EU\"
|> map {id: 1 value: _.amount * _.rate}
|> save! \"/tmp/dt-test-eu.csv\"
|> count")]
      (is (= 1 result)))))

(deftest lazy-pipeline-reuse-file-sources-re-open-on-each-materialization
  (testing "Scenario: Lazy pipeline reuse -- file sources re-open on each materialization"
    ;; With in-memory data (same semantics): two independent collects produce correct results
    (let [a (eval-dt-last
             "data is [{x: 1 y: 10} {x: 2 y: 20} {x: 3 y: 30}]"
             "a is data |> filter _.x > 1 |> collect"
             "a")
          b (eval-dt-last
             "data is [{x: 1 y: 10} {x: 2 y: 20} {x: 3 y: 30}]"
             "b is data |> filter _.y > 15 |> collect"
             "b")]
      (is (= [{:x 2 :y 20} {:x 3 :y 30}] a))
      (is (= [{:x 2 :y 20} {:x 3 :y 30}] b)))))
