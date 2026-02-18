Feature: Lazy Evaluation, Data Sources, REPL Audit & Micro-sampling
  DataTwist pipelines are lazy by default. Building a pipeline constructs
  a computation plan -- it does NOT execute. Execution happens only when
  a materialization function is called, or when the REPL auto-samples
  for preview.

  Core principles:
    - Everything is lazy: pipelines build plans, not results
    - REPL shows micro-samples (~100 rows) for instant preview
    - Equal speed on 10 and 10,000,000 elements
    - Audit at any point with `tap!`
    - `!` suffix = side-effect functions that return their first argument (passthrough)
      Only: log!, tap!, save!, into!, close!, force!
    - Regular functions (count, collect, first, reduce) trigger evaluation but
      are NOT side-effects -- they return computed results
    - `force!` materializes a lazy pipeline and returns the data (passthrough)
    - Data sources (DB, files, S3, APIs) are first-class pipeline sources
    - Compiles to Clojure lazy-seq, transducers, and reducers

  # ===========================================================================
  # SECTION 1: LAZY PIPELINE CONSTRUCTION
  # ===========================================================================
  #
  # Pipelines without a materializing function produce a lazy value.
  # No computation is performed until something forces evaluation.
  # This is the foundational invariant of the language runtime.
  # ===========================================================================

  Scenario: A pipeline without materialization is lazy -- nothing executes
    Given the source code:
      """
      data is [1 2 3 4 5 6 7 8 9 10]
      result is data |> filter _ > 5 |> map _ * 2
      """
    Then result is a lazy pipeline plan
    And no filtering or mapping has been performed yet
    And it compiles to Clojure:
      """
      (def data [1 2 3 4 5 6 7 8 9 10])
      (def result (-> data (filter (fn [x] (> x 5))) (map (fn [x] (* x 2)))))
      """
    And result is a Clojure lazy sequence

  Scenario: Chaining multiple lazy operations builds a deeper plan
    Given the source code:
      """
      users is get-all-users
      result is users
        |> filter _.active
        |> map {name: _.name email: _.email}
        |> sort-by _.name
        |> take 100
      """
    Then result is a lazy pipeline plan with four steps
    And get-all-users has not been fully realized
    And no filtering, mapping, sorting, or taking has been performed

  Scenario: Binding a lazy pipeline to a name does not force evaluation
    Given the source code:
      """
      step1 is data |> filter _.active
      step2 is step1 |> map _.name
      step3 is step2 |> sort
      """
    Then step1, step2, and step3 are all lazy
    And no element has been processed
    And each binding holds a reference to a computation plan

  Scenario: Lazy pipelines over in-memory collections use Clojure lazy-seq
    Given the source code:
      """
      numbers is range 1 1000000
      evens is numbers |> filter [n -> n % 2 = 0]
      """
    Then evens is a Clojure lazy sequence
    And it has NOT materialized 1,000,000 elements in memory
    And it compiles to Clojure:
      """
      (def numbers (range 1 1000000))
      (def evens (-> numbers (filter (fn [n] (= (mod n 2) 0)))))
      """

  Scenario: Lazy pipeline preserves reference identity across reads
    Given the source code:
      """
      plan is data |> filter _.x |> map _.y
      a is plan
      b is plan
      """
    Then a and b reference the same lazy pipeline object
    And evaluating a and b produces the same results

  # ===========================================================================
  # SECTION 2: MATERIALIZATION FUNCTIONS
  # ===========================================================================
  #
  # These functions force execution of the pipeline:
  #   - collect, count, first, reduce -- regular functions, return computed results
  #   - save!, into!, force! -- side-effect `!` functions, return their input (passthrough)
  #
  # The `!` convention means: side-effect + passthrough (returns first argument).
  # Regular materialization functions return computed values (not passthrough).
  # ===========================================================================

  Scenario: collect forces entire pipeline into a vector in memory
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> map _ * 10 |> collect
      """
    Then the pipeline is fully executed
    And result is [30 40 50]
    And result is a Clojure PersistentVector (not a lazy sequence)
    And it compiles to Clojure:
      """
      (def result (-> [1 2 3 4 5]
                      (filter (fn [x] (> x 2)))
                      (map (fn [x] (* x 10)))
                      vec))
      """

  Scenario: count forces full traversal and returns exact count
    Given the source code:
      """
      data is range 1 10000001
      n is data |> filter [x -> x % 7 = 0] |> count
      """
    Then n is the exact count of multiples of 7 in range 1..10000000
    And the entire pipeline was executed to count every element
    And it compiles to Clojure:
      """
      (def n (-> (range 1 10000001)
                 (filter (fn [x] (= (mod x 7) 0)))
                 count))
      """

  Scenario: first forces evaluation until one element is found
    Given the source code:
      """
      result is data |> filter _.score > 90 |> first
      """
    Then result is the first element where score > 90
    And only elements up to and including the first match were evaluated
    And it compiles to Clojure:
      """
      (def result (-> data (filter (fn [x] (> (:score x) 90))) first))
      """

  Scenario: save! writes pipeline output to a file
    Given the source code:
      """
      data |> filter _.active |> map _.name |> save! "output.json"
      """
    Then the pipeline is fully executed
    And the resulting names are written to output.json
    And save! returns the data that was written (passthrough semantics)
    And it compiles to Clojure:
      """
      (-> data
          (filter (fn [x] (:active x)))
          (map (fn [x] (:name x)))
          (doto (dt/save "output.json")))
      """

  Scenario: save! supports multiple file formats
    Given the source code:
      """
      data |> save! "output.csv"
      data |> save! "output.json"
      data |> save! "output.edn"
      data |> save! "output.parquet"
      """
    Then each line writes data in the format implied by the file extension
    And CSV produces comma-separated values with a header row
    And JSON produces a JSON array
    And EDN produces Clojure EDN data
    And Parquet produces a columnar Parquet file

  Scenario: into! inserts pipeline output into a database table
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      data |> filter _.active |> into! db "results"
      """
    Then the pipeline is fully executed
    And each resulting row is inserted into the "results" table
    And into! returns the data that was inserted (passthrough semantics)
    And it compiles to Clojure:
      """
      (-> data
          (filter (fn [x] (:active x)))
          (doto (dt/into-db db "results")))
      """

  Scenario: reduce folds the pipeline into a single value
    Given the source code:
      """
      total is orders |> map _.amount |> reduce [a b -> a + b] 0
      """
    Then the pipeline is fully executed
    And total is the sum of all order amounts
    And it compiles to Clojure:
      """
      (def total (-> orders
                     (map (fn [x] (:amount x)))
                     (reduce (fn [a b] (+ a b)) 0)))
      """

  Scenario: force! materializes a lazy pipeline and returns the data (passthrough)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> map _ * 10 |> force! |> tap! "materialized"
      """
    Then force! triggers full evaluation of the pipeline
    And force! returns the materialized data (passthrough -- the data flows through)
    And tap! receives the materialized data [30 40 50]
    And result is [30 40 50]
    And it compiles to Clojure:
      """
      (def result (-> [1 2 3 4 5]
                      (filter (fn [x] (> x 2)))
                      (map (fn [x] (* x 10)))
                      (doto doall)
                      (doto (dt/tap "materialized"))))
      """

  Scenario: force! is useful for ensuring computation happens at a specific point
    Given the source code:
      """
      data is read-csv "input.csv"
      processed is data
        |> filter _.active
        |> map {name: _.name score: _.score * 2}
        |> force!
      processed |> save! "output1.csv"
      processed |> save! "output2.csv"
      """
    Then force! materializes the pipeline once
    And both save! calls operate on the materialized data
    And the CSV file is read only once (not re-read for each save!)

  Scenario: Chaining after a materialization function starts a new pipeline
    Given the source code:
      """
      materialized is data |> filter _.active |> collect
      result is materialized |> map _.name |> count
      """
    Then materialized is a concrete vector (not lazy)
    And the second pipeline operates on the materialized vector
    And result is the exact count of names

  Scenario: collect on an already-materialized collection is a no-op
    Given the source code:
      """
      items is [1 2 3]
      result is items |> collect
      """
    Then result equals [1 2 3]
    And no extra work is performed since items is already in memory

  # ===========================================================================
  # SECTION 3: REPL MICRO-SAMPLING
  # ===========================================================================
  #
  # When the REPL evaluates a lazy pipeline, it does NOT force the entire
  # pipeline. Instead, it takes a micro-sample (default: 100 elements)
  # and displays a preview with an estimated total count.
  # This guarantees instant REPL response regardless of data size.
  # ===========================================================================

  Scenario: REPL auto-samples a lazy pipeline for preview
    Given a lazy pipeline:
      """
      users |> filter _.active |> sort-by _.score
      """
    When entered in the REPL
    Then the REPL displays a preview of approximately 100 rows
    And the REPL displays an estimated total count: "~7,500 rows"
    And the REPL response is instant (sub-second) regardless of data size

  Scenario: REPL preview shows tabular format for collections of objects
    Given a lazy pipeline:
      """
      users |> filter _.active |> map {name: _.name score: _.score}
      """
    When entered in the REPL
    Then the REPL displays output resembling:
      """
      lazy<pipeline> ~7,500 rows (showing first 100)
      | name    | score |
      |---------|-------|
      | Alice   | 95    |
      | Bob     | 87    |
      | Charlie | 82    |
      | ...     |       |
      (100 of ~7,500 shown)
      """

  Scenario: REPL preview shows list format for non-object collections
    Given a lazy pipeline:
      """
      range 1 10000 |> filter [n -> n % 3 = 0]
      """
    When entered in the REPL
    Then the REPL displays output resembling:
      """
      lazy<pipeline> ~3,333 rows (showing first 100)
      [3 6 9 12 15 18 21 24 27 30 ... ]
      (100 of ~3,333 shown)
      """

  Scenario: REPL shows exact count for small collections
    Given a lazy pipeline:
      """
      [1 2 3 4 5] |> filter _ > 2
      """
    When entered in the REPL
    Then the REPL displays the full result since it fits in the sample:
      """
      [3 4 5]
      """
    And no "~N rows" estimate is shown because all elements are available

  Scenario: REPL preview for a single value (not a collection) shows the value
    Given the expression:
      """
      users |> filter _.active |> count
      """
    When entered in the REPL
    Then the REPL displays the exact count (e.g., 7500)
    And no sampling is performed because count returns a scalar

  Scenario: REPL preview sample size is configurable
    Given the REPL configuration:
      """
      config! {sample-size: 500}
      """
    And a lazy pipeline:
      """
      users |> filter _.active
      """
    When entered in the REPL
    Then the REPL displays a preview of approximately 500 rows
    And the configuration persists for the REPL session

  Scenario: REPL preview uses first-N sampling strategy by default
    Given a lazy pipeline over an ordered source
    When the REPL samples it
    Then the first N elements of the pipeline output are taken
    And sampling preserves pipeline ordering (filter, sort, etc. apply first)
    And no random sampling is performed by default

  Scenario: REPL preview caches the sample for the current expression
    Given a lazy pipeline:
      """
      result is users |> filter _.active
      """
    When entered in the REPL
    And result is evaluated a second time:
      """
      result
      """
    Then the same sample is returned without re-executing the pipeline
    And the cache is invalidated when the source data changes or the binding is redefined

  # ===========================================================================
  # SECTION 4: TAP! -- INLINE PIPELINE DEBUGGING
  # ===========================================================================
  #
  # `tap!` is a passthrough side-effect function that shows a sample of
  # data at any point in a pipeline. It follows `!` semantics: data flows
  # through unchanged. `tap!` samples -- it does NOT force full evaluation.
  # ===========================================================================

  Scenario: tap! shows data at a pipeline step and passes it through
    Given the source code:
      """
      users
      |> filter _.active
      |> tap!
      |> map {name: _.name}
      |> tap!
      |> sort-by _.name
      """
    Then the first tap! shows a sample of active users (all fields)
    And the second tap! shows a sample of {name: ...} objects
    And data flows through both tap! calls unchanged
    And the pipeline result is sorted {name: ...} objects

  Scenario: tap! with a label for clarity
    Given the source code:
      """
      users
      |> filter _.active
      |> tap! "after filter"
      |> map _.name
      |> tap! "after map"
      """
    Then the first tap! output is labeled "after filter"
    And the second tap! output is labeled "after map"
    And it compiles to Clojure:
      """
      (-> users
          (filter (fn [x] (:active x)))
          (doto (dt/tap "after filter"))
          (map (fn [x] (:name x)))
          (doto (dt/tap "after map")))
      """

  Scenario: tap! shows a micro-sample, not the full dataset
    Given a pipeline over 10,000,000 rows:
      """
      huge-dataset
      |> filter _.valid
      |> tap!
      |> map _.name
      """
    Then tap! displays approximately 100 rows (the default sample size)
    And tap! does NOT force evaluation of all 10,000,000 rows
    And tap! response is instant regardless of dataset size

  Scenario: tap! displays tabular format for objects
    Given a pipeline:
      """
      users |> filter _.active |> tap!
      """
    Then tap! output resembles:
      """
      [tap!] ~7,500 rows (sample: 100)
      | id | name    | active | score |
      |----|---------|--------|-------|
      | 1  | Alice   | true   | 95    |
      | 2  | Bob     | true   | 87    |
      | ...                           |
      """

  Scenario: tap! with a transformation function for focused inspection
    Given the source code:
      """
      users
      |> filter _.active
      |> tap! [data -> data |> map _.score |> describe]
      |> map _.name
      """
    Then tap! applies the function to the sample for display purposes
    And the pipeline data is NOT affected by the tap function
    And the pipeline continues with the original data (not the transformed view)

  Scenario: tap! in production code is configurable (no-op or sink)
    Given the runtime configuration:
      """
      config! {tap-enabled: false}
      """
    And a pipeline:
      """
      data |> tap! "debug" |> process
      """
    Then tap! is a no-op (does not print anything)
    And data flows through unchanged
    And there is no performance overhead from disabled tap!

  Scenario: tap! returns its input unchanged (passthrough)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> tap! |> map _ * 10 |> collect
      """
    Then result is [30 40 50]
    And tap! had no effect on the pipeline result

  Scenario: Multiple tap! calls in one pipeline
    Given the source code:
      """
      orders
      |> tap! "raw orders"
      |> filter _.status = "completed"
      |> tap! "completed only"
      |> map _.total
      |> tap! "totals"
      |> reduce [a b -> a + b] 0
      """
    Then three separate tap! outputs are shown
    And each shows data at its respective pipeline point
    And the final result is the sum of completed order totals

  # ===========================================================================
  # SECTION 5: DATA SOURCES -- DATABASES
  # ===========================================================================
  #
  # `connect` creates a connection to an external data source.
  # `table` and `query` create lazy references to database data.
  # Operations are pushed down to SQL where possible.
  # ===========================================================================

  Scenario: Connect to a PostgreSQL database
    Given the source code:
      """
      db is connect "postgres://localhost:5432/mydb"
      """
    Then db is a database connection object
    And db is NOT a collection -- it is a source handle
    And it compiles to Clojure:
      """
      (def db (dt/connect "postgres://localhost:5432/mydb"))
      """
    And under the hood, a connection pool (e.g., HikariCP) is created

  Scenario: Connect with explicit credentials
    Given the source code:
      """
      db is connect "postgres://localhost/mydb" {
        user: "admin"
        password: env "DB_PASSWORD"
        pool-size: 10
      }
      """
    Then db is a database connection with the specified credentials
    And the password is read from the environment variable DB_PASSWORD
    And the connection pool size is set to 10

  Scenario: Reference a database table as a lazy data source
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      users is db |> table "users"
      """
    Then users is a lazy reference to the "users" table
    And no SQL query has been executed yet
    And users can be used as a pipeline source

  Scenario: Pipeline over a database table is lazy
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      active-users is db |> table "users" |> filter _.active |> sort-by _.name
      """
    Then active-users is a lazy query plan
    And no SQL has been executed
    And the REPL shows a micro-sample when active-users is evaluated

  Scenario: Raw SQL query as a lazy data source
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      results is db |> query "SELECT id, name, score FROM users WHERE active = true"
      """
    Then results is a lazy sequence of maps (one per row)
    And the query is not executed until materialization or sampling
    And each map has keys matching the column names: id, name, score

  Scenario: Database query with parameters
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      results is db |> query "SELECT * FROM users WHERE age > ? AND city = ?" [18 "Moscow"]
      """
    Then the query uses parameterized SQL (preventing SQL injection)
    And results is a lazy sequence of maps
    And it compiles to Clojure:
      """
      (def results (dt/query db "SELECT * FROM users WHERE age > ? AND city = ?" [18 "Moscow"]))
      """

  Scenario: Table source materializes to a full scan on collect
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      all-users is db |> table "users" |> collect
      """
    Then a SELECT * FROM users query is executed
    And all-users is a vector of maps in memory
    And each map represents one row with keyword keys

  # ===========================================================================
  # SECTION 6: DATA SOURCES -- FILES
  # ===========================================================================
  #
  # File sources are lazy streaming readers. They do NOT load the entire
  # file into memory. Each read function returns a lazy sequence of elements.
  # ===========================================================================

  Scenario: Read CSV file as lazy sequence of maps
    Given the source code:
      """
      data is read-csv "sales.csv"
      """
    Then data is a lazy sequence of maps
    And the first row of the CSV is used as column names (keys)
    And no rows beyond the header have been read yet
    And it compiles to Clojure:
      """
      (def data (dt/read-csv "sales.csv"))
      """

  Scenario: Read CSV with explicit options
    Given the source code:
      """
      data is read-csv "data.tsv" {separator: "\t" header: true encoding: "UTF-8"}
      """
    Then data is a lazy sequence of maps
    And the tab character is used as the field separator
    And the first row is treated as headers

  Scenario: Read CSV without headers
    Given the source code:
      """
      data is read-csv "raw.csv" {header: false}
      """
    Then data is a lazy sequence of vectors (not maps)
    And each vector contains the values of one row

  Scenario: Read JSON file
    Given the source code:
      """
      data is read-json "data.json"
      """
    Then if the file contains a JSON array, data is a lazy sequence of elements
    And if the file contains a JSON object, data is a single map
    And it compiles to Clojure:
      """
      (def data (dt/read-json "data.json"))
      """

  Scenario: Read JSON lines (newline-delimited JSON)
    Given the source code:
      """
      data is read-jsonl "events.jsonl"
      """
    Then data is a lazy sequence of maps (one per line)
    And each line is parsed as a separate JSON object
    And the file is streamed line by line (not loaded fully into memory)

  Scenario: Read text file as lazy sequence of lines
    Given the source code:
      """
      lines is read-lines "logfile.txt"
      """
    Then lines is a lazy sequence of strings (one per line)
    And it compiles to Clojure:
      """
      (def lines (dt/read-lines "logfile.txt"))
      """

  Scenario: File sources support full pipeline syntax
    Given the source code:
      """
      read-csv "sales.csv"
      |> filter _.region = "Europe"
      |> map {product: _.product revenue: _.price * _.quantity}
      |> sort-by _.revenue
      |> take 10
      |> collect
      """
    Then the CSV is streamed row by row
    And only rows matching the filter are kept in memory
    And the pipeline produces the top 10 European products by revenue

  Scenario: Read Parquet file as lazy columnar source
    Given the source code:
      """
      data is read-parquet "warehouse.parquet"
      """
    Then data is a lazy source backed by columnar Parquet reading
    And column pruning is supported (only referenced columns are read)

  # ===========================================================================
  # SECTION 7: DATA SOURCES -- S3 AND HTTP
  # ===========================================================================

  Scenario: Connect to S3 bucket
    Given the source code:
      """
      bucket is connect "s3://my-data-bucket/"
      """
    Then bucket is an S3 client handle
    And AWS credentials are resolved from the standard chain (env, profile, IAM role)

  Scenario: Read file from S3
    Given the source code:
      """
      bucket is connect "s3://my-data-bucket/"
      data is bucket |> read-csv "data/sales-2024.csv"
      """
    Then data is a lazy sequence of maps streamed from S3
    And the file is not downloaded in full before processing begins

  Scenario: List files in S3 prefix
    Given the source code:
      """
      bucket is connect "s3://my-data-bucket/"
      files is bucket |> ls "data/2024/"
      """
    Then files is a lazy sequence of file metadata objects
    And each has keys: key, size, last-modified

  Scenario: HTTP API as data source
    Given the source code:
      """
      api is connect "https://api.example.com" {
        headers: {authorization: "Bearer " + env "API_TOKEN"}
      }
      users is api |> get "/users" |> _.data
      """
    Then users is parsed from the JSON response body
    And the .data field is extracted from the response object

  Scenario: Paginated HTTP API with lazy iteration
    Given the source code:
      """
      api is connect "https://api.example.com"
      all-users is api |> paginate "/users" {page-param: "page" per-page: 100}
      """
    Then all-users is a lazy sequence that fetches pages on demand
    And the first page is fetched immediately
    And subsequent pages are fetched only as elements are consumed

  # ===========================================================================
  # SECTION 8: SQL PUSH-DOWN OPTIMIZATION
  # ===========================================================================
  #
  # When a pipeline operates on a database source, the compiler/runtime
  # attempts to push operations down to SQL for efficient execution.
  # Push-down stops at the first operation that cannot be translated to SQL.
  # ===========================================================================

  Scenario: filter pushes down to SQL WHERE clause
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> filter _.age > 18 |> collect
      """
    Then the executed SQL is:
      """
      SELECT * FROM users WHERE age > 18
      """
    And the filter is NOT applied in Clojure (it was pushed to the database)

  Scenario: sort-by pushes down to SQL ORDER BY
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> sort-by _.name |> collect
      """
    Then the executed SQL is:
      """
      SELECT * FROM users ORDER BY name ASC
      """

  Scenario: take pushes down to SQL LIMIT
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> take 10 |> collect
      """
    Then the executed SQL is:
      """
      SELECT * FROM users LIMIT 10
      """

  Scenario: map with field selection pushes down to SQL SELECT
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> map {name: _.name age: _.age} |> collect
      """
    Then the executed SQL is:
      """
      SELECT name, age FROM users
      """

  Scenario: count on a database source pushes down to SQL COUNT
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      n is db |> table "users" |> filter _.active |> count
      """
    Then the executed SQL is:
      """
      SELECT COUNT(*) FROM users WHERE active = true
      """
    And n is the integer result of the COUNT query
    And no rows are transferred from the database

  Scenario: group-by pushes down to SQL GROUP BY
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "orders"
        |> group-by _.region
        |> map {region: _.key count: _.value |> count}
        |> collect
      """
    Then the executed SQL is approximately:
      """
      SELECT region, COUNT(*) AS count FROM orders GROUP BY region
      """

  Scenario: Combined push-down for filter + sort + limit
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users"
        |> filter _.active
        |> filter _.age >= 18
        |> sort-by _.score
        |> take 20
        |> collect
      """
    Then the executed SQL is:
      """
      SELECT * FROM users WHERE active = true AND age >= 18 ORDER BY score ASC LIMIT 20
      """
    And all four operations are pushed to a single SQL query

  Scenario: Push-down stops at non-translatable operations
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users"
        |> filter _.active
        |> map [u -> {name: u.name score: custom-score-fn u}]
        |> sort-by _.score
        |> collect
      """
    Then filter _.active is pushed to SQL:
      """
      SELECT * FROM users WHERE active = true
      """
    And the map with custom-score-fn is executed in Clojure (not pushable)
    And sort-by _.score is executed in Clojure (after the non-pushable step)

  Scenario: explain shows the generated SQL without executing
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users"
        |> filter _.active
        |> sort-by _.name
        |> take 10
        |> explain
      """
    Then the REPL displays:
      """
      SQL: SELECT * FROM users WHERE active = true ORDER BY name ASC LIMIT 10
      Estimated rows: ~7,500
      Push-down: filter, sort-by, take
      Local ops: (none)
      """
    And no query is executed against the database

  # ===========================================================================
  # SECTION 9: PIPELINE FUSION (TRANSDUCERS)
  # ===========================================================================
  #
  # Consecutive map/filter operations can be fused into a single pass
  # using Clojure transducers. This eliminates intermediate lazy sequences
  # and reduces memory allocation.
  # ===========================================================================

  Scenario: Consecutive filter and map fuse into a single transducer pass
    Given the source code:
      """
      result is data
        |> filter _.active
        |> map _.name
        |> filter _ != nil
        |> collect
      """
    Then the pipeline MAY be optimized using transducers
    And it is semantically equivalent to three separate lazy operations
    And the transducer version compiles to Clojure:
      """
      (def result (into []
                    (comp
                      (filter (fn [x] (:active x)))
                      (map (fn [x] (:name x)))
                      (filter (fn [x] (not= x nil))))
                    data))
      """

  Scenario: Transducer fusion is transparent -- same result either way
    Given the source code:
      """
      a is data |> filter _ > 5 |> map _ * 2 |> collect
      """
    Then whether the compiler uses transducers or lazy sequences
    And the result is identical
    And the choice is a performance optimization only

  Scenario: Pipeline with sort-by breaks transducer fusion
    Given the source code:
      """
      data
      |> filter _.active
      |> sort-by _.score
      |> map _.name
      |> take 10
      |> collect
      """
    Then filter _.active and sort-by _.score cannot be fused
    Because sort-by requires all input before producing output
    And map _.name and take 10 may fuse after the sort completes

  # ===========================================================================
  # SECTION 10: DESCRIBE / EXPLORE FUNCTIONS
  # ===========================================================================
  #
  # Exploration functions provide statistical summaries and quick views
  # of data. They operate on samples for speed where possible.
  # ===========================================================================

  Scenario: describe shows statistical summary of a dataset
    Given the source code:
      """
      data is read-csv "sales.csv"
      data |> describe
      """
    Then the REPL displays a summary resembling:
      """
      rows: ~50,000
      columns: 6

      | column   | type    | non-null | min   | max     | mean   | distinct |
      |----------|---------|----------|-------|---------|--------|----------|
      | id       | integer | 100%     | 1     | 50000   | 25000  | 50000    |
      | product  | string  | 99.8%    | -     | -       | -      | 342      |
      | region   | string  | 100%     | -     | -       | -      | 5        |
      | price    | float   | 98.5%    | 0.99  | 999.99  | 45.20  | -        |
      | quantity | integer | 100%     | 1     | 1000    | 12     | -        |
      | date     | string  | 100%     | -     | -       | -      | 365      |
      """
    And describe samples the data (it does not scan the entire dataset)

  Scenario: schema shows column names and inferred types only
    Given the source code:
      """
      data is read-csv "sales.csv"
      data |> schema
      """
    Then the REPL displays:
      """
      | column   | type    |
      |----------|---------|
      | id       | integer |
      | product  | string  |
      | region   | string  |
      | price    | float   |
      | quantity | integer |
      | date     | string  |
      """
    And schema examines only a small sample to infer types

  Scenario: schema for a database table uses database metadata
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users" |> schema
      """
    Then schema queries the database metadata (not the data)
    And it displays actual database column types (varchar, integer, etc.)

  Scenario: sample returns N random elements
    Given the source code:
      """
      data |> sample 20
      """
    Then 20 randomly selected elements are returned
    And the sampling is efficient (does not require full scan for indexed sources)
    And sample forces partial materialization (it is NOT lazy)

  Scenario: head returns the first N elements
    Given the source code:
      """
      data |> head 5
      """
    Then the first 5 elements are returned
    And it is equivalent to take 5 |> collect
    And head forces materialization of those 5 elements

  Scenario: tail returns the last N elements
    Given the source code:
      """
      data |> tail 5
      """
    Then the last 5 elements are returned
    And for database sources, this pushes down as ORDER BY ... DESC LIMIT 5
    And tail forces materialization

  Scenario: freq shows frequency table for a field
    Given the source code:
      """
      data |> freq _.region
      """
    Then the REPL displays:
      """
      | region        | count | pct    |
      |---------------|-------|--------|
      | Europe        | 15230 | 30.5%  |
      | North America | 12890 | 25.8%  |
      | Asia          | 11540 | 23.1%  |
      | South America |  6780 | 13.6%  |
      | Africa        |  3560 |  7.1%  |
      """
    And freq may scan the full dataset or use sampling depending on source size

  Scenario: histogram shows ASCII histogram for a numeric field
    Given the source code:
      """
      data |> histogram _.age
      """
    Then the REPL displays an ASCII histogram of the age distribution
    And bin sizes are automatically determined
    And the histogram is based on a sample for large datasets

  # ===========================================================================
  # SECTION 11: COUNT BEHAVIOR
  # ===========================================================================
  #
  # `count` is a regular function (no `!`). It returns the count of elements.
  # For database sources, count pushes down to SQL COUNT(*).
  # For in-memory data, count returns the exact count.
  # For lazy pipelines over files, count forces full traversal.
  #
  # Design decision: there is no `count!`. count is always `count`.
  # The runtime is smart about how it computes the count based on the source.
  # ===========================================================================

  Scenario: count on a database source pushes down to SQL
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      users is db |> table "users"
      n is users |> count
      """
    Then n is the exact count (from SELECT COUNT(*))
    And the count is computed efficiently by the database

  Scenario: count on in-memory collections returns exact count
    Given the source code:
      """
      items is [1 2 3 4 5]
      n is items |> count
      """
    Then n is 5 (exact)
    And count on an already-materialized collection is always exact

  Scenario: count on a filtered lazy pipeline forces full traversal
    Given the source code:
      """
      data is read-csv "huge.csv"
      n is data |> filter _.active |> count
      """
    Then n is the exact number of active rows
    And the entire file was streamed and filtered to count

  # ===========================================================================
  # SECTION 12: ESTIMATION STRATEGIES FOR DIFFERENT SOURCES
  # ===========================================================================

  Scenario: Estimate row count for database sources uses EXPLAIN
    Given a database table source:
      """
      db |> table "users" |> filter _.active
      """
    Then the estimated count uses the database query planner (EXPLAIN)
    And the estimate is fast (no data scan)

  Scenario: Estimate row count for database sources uses COUNT(*)
    Given a database table source without filters:
      """
      db |> table "users"
      """
    Then the estimated count may use table statistics or COUNT(*)
    And database-specific optimizations are used (pg_stat for PostgreSQL)

  Scenario: Estimate row count for CSV files uses file size heuristic
    Given a CSV file source:
      """
      read-csv "large-data.csv"
      """
    Then the estimated row count is calculated as:
      file-size / average-row-size (sampled from first 1000 rows)
    And the estimate is fast (reads only the beginning of the file)

  Scenario: Estimate row count for JSON lines uses file size heuristic
    Given a JSONL file source:
      """
      read-jsonl "events.jsonl"
      """
    Then the estimated row count is calculated similarly to CSV
    And average line size is sampled from the beginning of the file

  # ===========================================================================
  # SECTION 13: MEMORY SAFETY AND HEAD RETENTION
  # ===========================================================================
  #
  # Lazy sequences in Clojure can hold references to the head of a
  # sequence, preventing garbage collection. DataTwist must handle this
  # carefully to avoid memory leaks in long pipelines.
  # ===========================================================================

  Scenario: Pipeline does not hold reference to head after consumption
    Given the source code:
      """
      data is read-csv "huge.csv"
      data |> filter _.active |> map _.name |> save! "names.txt"
      """
    Then as rows are processed and written, earlier rows are GC-eligible
    And memory usage stays constant regardless of file size (streaming)

  Scenario: collect on very large data triggers a warning
    Given the source code:
      """
      data is read-csv "10-million-rows.csv"
      all is data |> collect
      """
    Then the runtime issues a warning:
      "Warning: collecting ~10,000,000 rows into memory. Consider using save! or into! for large datasets."
    And execution proceeds (the warning does not block)
    And the warning threshold is configurable

  Scenario: Binding a lazy sequence to a name does not prevent GC of consumed elements
    Given the source code:
      """
      data is read-csv "huge.csv"
      result is data |> filter _.active |> save! "output.csv"
      """
    Then data does not retain processed rows in memory
    And the streaming pipeline keeps memory usage bounded

  Scenario: Chunked sequences (Clojure behavior) are handled transparently
    Given the source code:
      """
      data is range 1 100
      result is data |> filter _ > 50 |> take 5 |> collect
      """
    Then result is [51 52 53 54 55]
    And Clojure's chunked lazy-seq behavior (32 elements at a time) is transparent
    And the user does not need to know about chunking

  # ===========================================================================
  # SECTION 14: SCHEMA DISCOVERY AND TYPE INFERENCE
  # ===========================================================================

  Scenario: Schema discovery from database source uses DB metadata
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users" |> schema
      """
    Then column names, types, nullability, and constraints are read from the database catalog
    And no data rows are queried

  Scenario: Schema inference from CSV samples the first rows
    Given the source code:
      """
      data is read-csv "sales.csv"
      data |> schema
      """
    Then column names come from the header row
    And types are inferred by sampling values (e.g., all-numeric column = integer/float)
    And the inference examines a configurable number of rows (default: 1000)

  Scenario: Schema inference from JSON examines sample objects
    Given the source code:
      """
      data is read-jsonl "events.jsonl"
      data |> schema
      """
    Then keys from sampled JSON objects become column names
    And types are inferred from the values
    And nested objects are represented as type "object"
    And arrays are represented as type "list"

  # ===========================================================================
  # SECTION 15: ERROR HANDLING FOR DATA SOURCES
  # ===========================================================================

  Scenario: Connection failure raises a descriptive error
    Given the source code:
      """
      db is connect "postgres://nonexistent-host/mydb"
      """
    When the connection is attempted
    Then an error is raised with message containing "connection" and the host name
    And the error can be caught with try-catch:
      """
      db is try
        connect "postgres://nonexistent-host/mydb"
      catch err ->
        log! nil (format "Connection failed: %s" err.message)
        nil
      """

  Scenario: File not found raises a descriptive error
    Given the source code:
      """
      data is read-csv "nonexistent.csv"
      """
    When the pipeline is first evaluated (sampled or materialized)
    Then an error is raised with message containing "not found" and the filename
    And the error is a java.io.FileNotFoundException under the hood

  Scenario: Query timeout on database source
    Given the source code:
      """
      db is connect "postgres://localhost/mydb" {query-timeout: 5000}
      result is db |> query "SELECT * FROM huge_table CROSS JOIN another_table" |> collect
      """
    When the query exceeds 5000ms
    Then an error is raised with message containing "timeout"
    And partial results are NOT returned (all-or-nothing)

  Scenario: Permission denied on database table
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      data is db |> table "restricted_table" |> collect
      """
    When the database user lacks SELECT permission
    Then an error is raised with message containing "permission"

  Scenario: Malformed CSV data is handled gracefully
    Given the source code:
      """
      data is read-csv "malformed.csv"
      result is data |> collect
      """
    When a row has a mismatched number of columns
    Then that row is either skipped or an error is raised (configurable)
    And the error message includes the line number

  Scenario: Schema mismatch between expected and actual data
    Given the source code:
      """
      data is read-csv "data.csv"
      result is data |> filter _.nonexistent-column > 5 |> collect
      """
    Then nonexistent-column access returns nil for each row (nil-tolerant)
    And the filter excludes all rows (nil > 5 is falsy)
    And result is an empty collection
    And no error is raised (consistent with nil-tolerance design)

  Scenario: Network error during S3 streaming
    Given the source code:
      """
      bucket is connect "s3://my-bucket/"
      data is bucket |> read-csv "large-file.csv"
      result is data |> filter _.valid |> collect
      """
    When the network connection drops mid-stream
    Then an error is raised with message containing "network" or "S3"
    And partial results are NOT returned
    And the error can be caught with try-catch

  # ===========================================================================
  # SECTION 16: CONNECTION LIFECYCLE AND RESOURCE MANAGEMENT
  # ===========================================================================

  Scenario: Database connections are pooled by default
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      """
    Then db uses a connection pool (HikariCP under the hood)
    And concurrent pipeline executions share the pool
    And connections are returned to the pool after each query

  Scenario: File handles are closed after pipeline consumption
    Given the source code:
      """
      data is read-csv "data.csv"
      result is data |> filter _.active |> collect
      """
    Then the file handle is closed after collect completes
    And no file descriptor leak occurs

  Scenario: close! explicitly releases a connection
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> filter _.active |> collect
      close! db
      """
    Then the connection pool is shut down after close!
    And subsequent operations on db raise an error

  Scenario: Connections in REPL remain open across evaluations
    Given the REPL session:
      """
      db is connect "postgres://localhost/mydb"
      """
    And later:
      """
      db |> table "users" |> head 5
      """
    Then the connection created in the first line is reused
    And the connection pool stays open for the REPL session

  # ===========================================================================
  # SECTION 17: REPL DISPLAY FORMATTING
  # ===========================================================================

  Scenario: REPL displays lazy pipeline with type indicator
    Given a lazy pipeline:
      """
      users |> filter _.active
      """
    When entered in the REPL
    Then the output starts with a type indicator: "lazy<pipeline>"
    And the estimated row count follows: "~7,500 rows"

  Scenario: REPL displays database source with connection info
    Given:
      """
      db is connect "postgres://localhost/mydb"
      db
      """
    When entered in the REPL
    Then the output resembles:
      """
      connection<postgres://localhost/mydb> (pool: 10, active: 1)
      """

  Scenario: REPL displays materialized data directly
    Given:
      """
      [1 2 3] |> filter _ > 1 |> collect
      """
    When entered in the REPL
    Then the output is:
      """
      [2 3]
      """
    And no "lazy<...>" wrapper is shown because the result is materialized

  Scenario: REPL truncates very long single values
    Given a string result longer than 1000 characters
    When displayed in the REPL
    Then it is truncated with "..." and the full length shown
    And the user can use `full!` to display the complete value

  # ===========================================================================
  # SECTION 18: INTEGRATION SCENARIOS
  # ===========================================================================

  Scenario: Full ETL pipeline from database to file
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"

      db |> table "orders"
        |> filter _.status = "completed"
        |> filter _.date >= "2024-01-01"
        |> map {
          order-id: _.id
          customer: _.customer-name
          total: _.price * _.quantity
          region: _.region
        }
        |> sort-by _.total
        |> tap! "processed orders"
        |> save! "report.csv"
      """
    Then the filter and sort are pushed to SQL
    And the map with computed total is executed in Clojure
    And tap! shows a sample of the processed orders
    And save! writes the final results to CSV

  Scenario: Multi-source join pipeline
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"

      users is db |> table "users" |> filter _.active |> collect
      orders is db |> table "orders" |> filter _.year = 2024 |> collect

      user-orders is users |> map [u ->
        {
          name: u.name
          order-count: orders |> filter [o -> o.user-id = u.id] |> count
          total-spent: orders
            |> filter [o -> o.user-id = u.id]
            |> map _.amount
            |> reduce [a b -> a + b] 0
        }
      ] |> collect
      """
    Then users and orders are materialized first (two SQL queries)
    And the join is performed in Clojure (map + filter)
    And user-orders is a vector of maps with order stats

  Scenario: Streaming pipeline processes large file without memory issues
    Given the source code:
      """
      read-csv "10gb-file.csv"
      |> filter _.region = "EU"
      |> map {id: _.id value: _.amount * _.rate}
      |> save! "eu-values.csv"
      """
    Then memory usage stays bounded regardless of the 10GB input size
    And the file is processed in a streaming fashion
    And only one row (or chunk) is in memory at a time

  Scenario: REPL exploratory workflow
    Given the REPL session:
      """
      // Step 1: connect and explore
      db is connect "postgres://localhost/analytics"
      db |> table "events" |> schema
      db |> table "events" |> head 5
      db |> table "events" |> describe

      // Step 2: build a pipeline incrementally
      events is db |> table "events"
      events |> filter _.type = "purchase"
      events |> filter _.type = "purchase" |> freq _.category
      events |> filter _.type = "purchase" |> histogram _.amount

      // Step 3: refine and materialize
      report is events
        |> filter _.type = "purchase"
        |> filter _.amount > 100
        |> map {category: _.category amount: _.amount date: _.date}
        |> sort-by _.date
      report |> count
      report |> save! "purchases.csv"
      """
    Then each REPL line gives instant feedback via micro-sampling
    And the final save! triggers the actual computation
    And the user iterated through exploration without waiting


# ===========================================================================
# OPEN QUESTIONS
# ===========================================================================
#
# Q1: Default micro-sample size -- 100 or 1000?
#     100 is instant for any source. 1000 gives better statistical insight
#     for describe/freq/histogram. Proposal: 100 for REPL preview, 1000
#     for statistical functions (describe, freq, histogram).
#     User can override with config! {sample-size: N}.
#
# Q2: Sampling strategy -- first-N vs random?
#     First-N is simpler, faster, and preserves pipeline ordering.
#     Random sampling is better for statistical representativeness.
#     Proposal: first-N for REPL preview and tap!, random for sample N,
#     first-N-then-extrapolate for count estimation.
#     For DB sources, random sampling can use TABLESAMPLE or ORDER BY RANDOM().
#
# Q3: collect on 10M+ rows -- warning or hard error?
#     Proposal: warning with configurable threshold.
#     Default: warn at 1M rows estimated. Never hard-block -- trust the user.
#     config! {collect-warn-threshold: 5000000} to change.
#
# Q4: Push-down completeness -- which operations can push to SQL?
#     Tier 1 (v1): filter -> WHERE, sort-by -> ORDER BY, take -> LIMIT,
#       count -> COUNT(*), map {fields} -> SELECT columns.
#     Tier 2 (v2): group-by -> GROUP BY, distinct -> DISTINCT,
#       drop -> OFFSET, aggregate functions (sum, avg, min, max).
#     Tier 3 (future): joins, subqueries, window functions.
#     Non-pushable: any pipeline step involving a user-defined Clojure function.
#
# Q5: [RESOLVED] Should `count` (no bang) exist or is it confusing?
#     Decision: just `count` (no `!`). count is a regular function that
#     returns a computed result, not a side-effect. The runtime is smart:
#     DB sources push down to COUNT(*), in-memory data uses O(1) count,
#     lazy pipelines over files force full traversal. No `count!` exists.
#     This is consistent with the design: `!` = side-effect + passthrough.
#     count returns a value, so it is not a `!` function.
#
# Q6: tap! output destination -- REPL only, or also to a log/file?
#     Proposal: REPL by default. configurable via:
#       config! {tap-sink: "file:taps.log"}
#       config! {tap-sink: "repl"} (default)
#       config! {tap-sink: "nrepl"} (for IDE integration)
#     Clojure interop: tap! uses clojure.core/tap> under the hood,
#     so any registered tap handler receives the data.
#
# Q7: How does `connect` handle connection strings vs structured config?
#     URI string for convenience: connect "postgres://host/db"
#     Map for full control: connect {type: "postgres" host: "..." port: 5432 ...}
#     Both should work. URI is parsed into a config map internally.
#
# Q8: Transducer fusion -- automatic or opt-in?
#     Proposal: automatic when the compiler detects fusable sequences.
#     The compiler already knows which built-in functions are transducer-
#     compatible (filter, map, take, drop, distinct, etc.).
#     User-defined functions in pipeline steps break fusion.
#     No explicit opt-in needed -- the optimization is always correct.
#
# Q9: save! format options beyond file extension?
#     Proposal: save! "file.csv" uses extension. For explicit format:
#       data |> save! "output" {format: "csv" delimiter: "\t" header: true}
#     save! "output.csv" {header: false} -- extension + overrides.
#
# Q10: read-* functions -- should they accept URLs directly?
#      read-csv "https://example.com/data.csv" -- fetch and parse.
#      Proposal: YES for http/https URLs. Streaming download + parse.
#      For S3: require connect first for auth, then bucket |> read-csv "path".
#
# Q11: Lazy pipeline reuse -- is a lazy seq consumed or replayable?
#      Clojure lazy-seqs are realized once. If you traverse twice, the
#      second traversal sees cached elements (if head is retained).
#      But file sources should NOT cache (memory!). Proposal:
#      - In-memory sources: Clojure default (cached on realization)
#      - File/DB sources: re-execute on each materialization call
#      - Binding to a name after collect caches the materialized result
#      This means: `data is read-csv "f.csv"` -- each pipeline on data
#      re-opens the file. `data is read-csv "f.csv" |> collect` -- cached.
#
# Q12: Should describe/schema/freq/histogram return data or only print?
#      Proposal: they return structured data AND the REPL pretty-prints it.
#      describe returns {columns: [...] rows: N ...} as a map.
#      This allows: stats is data |> describe; stats.columns |> map _.name
#
# Q13: Credential management for connect
#      Options:
#        (a) Inline: connect "postgres://user:pass@host/db" -- insecure
#        (b) Environment: env "DB_URL" |> connect -- recommended
#        (c) Config file: connect {profile: "production"} -- reads from ~/.datatwist/profiles.edn
#        (d) Vault/secrets manager integration -- future
#      Proposal: support (a), (b), (c). Recommend (b) for production.
#      NEVER store credentials in source code. Warn if password appears in connect string.
#
# Q14: Chunked lazy-seq interaction with sampling
#      Clojure lazy-seqs are chunked (32 at a time for vector-backed sources).
#      When sampling 100 elements, we might realize 128 (4 chunks).
#      This is acceptable and should be transparent to the user.
#      For precise sampling (e.g., DB LIMIT 100), chunking does not apply.
#
# Q15: What happens when a data source schema changes between operations?
#      Example: user runs `data |> describe` then adds a column to the DB,
#      then runs `data |> collect`. The schema from describe is stale.
#      Proposal: each materialization re-queries the source. describe output
#      is a snapshot. No caching of schema across materializations.
#
# ===========================================================================
# CLOJURE COMPILATION MAPPING
# ===========================================================================
#
# DataTwist                                | Clojure
# -----------------------------------------|--------------------------------------------
# connect "postgres://..."                 | (dt/connect "postgres://...")
# connect "postgres://..." {pool-size: 5}  | (dt/connect "postgres://..." {:pool-size 5})
# db |> table "users"                      | (dt/table db "users")
# db |> query "SELECT ..." [params]        | (dt/query db "SELECT ..." [params])
# read-csv "file.csv"                      | (dt/read-csv "file.csv")
# read-csv "f.csv" {separator: "\t"}       | (dt/read-csv "f.csv" {:separator "\t"})
# read-json "file.json"                    | (dt/read-json "file.json")
# read-jsonl "file.jsonl"                  | (dt/read-jsonl "file.jsonl")
# read-lines "file.txt"                    | (dt/read-lines "file.txt")
# read-parquet "file.parquet"              | (dt/read-parquet "file.parquet")
# connect "s3://bucket/"                   | (dt/connect "s3://bucket/")
# connect "https://api.example.com"        | (dt/connect "https://api.example.com")
# data |> collect                          | (vec data)
# data |> count                            | (count data)
# data |> first                            | (first data)
# data |> reduce [a b -> a + b] 0          | (reduce (fn [a b] (+ a b)) 0 data)
# data |> force!                           | (doto data doall) ; or (vec data) with passthrough
# data |> save! "out.json"                 | (doto data (dt/save "out.json"))
# data |> into! db "table"                 | (doto data (dt/into-db db "table"))
# data |> tap!                             | (doto data (dt/tap nil))
# data |> tap! "label"                     | (doto data (dt/tap "label"))
# data |> tap! [d -> d |> describe]        | (doto data (dt/tap (fn [d] (dt/describe d))))
# data |> describe                         | (dt/describe data)
# data |> schema                           | (dt/schema data)
# data |> sample 20                        | (dt/sample data 20)
# data |> head 5                           | (vec (take 5 data))
# data |> tail 5                           | (dt/tail data 5)
# data |> freq _.field                     | (dt/freq data (fn [x] (:field x)))
# data |> histogram _.field                | (dt/histogram data (fn [x] (:field x)))
# data |> explain                          | (dt/explain data)
# close! db                                | (dt/close db)
# config! {sample-size: 500}               | (dt/config! {:sample-size 500})
# env "VAR_NAME"                           | (System/getenv "VAR_NAME")
#
# Push-down mapping (database sources):
# filter _.x > 5                           | WHERE x > 5
# filter _.x = "val"                       | WHERE x = 'val'
# filter (_.a > 1 and _.b = "x")           | WHERE a > 1 AND b = 'x'
# sort-by _.x                              | ORDER BY x ASC
# take N                                   | LIMIT N
# drop N                                   | OFFSET N
# map {a: _.a b: _.b}                      | SELECT a, b
# count                                    | SELECT COUNT(*)
# distinct                                 | SELECT DISTINCT ...
# group-by _.x                             | GROUP BY x
#
# Transducer fusion (when applicable):
# data |> filter f |> map g |> take n      | (into [] (comp (filter f) (map g) (take n)) data)
#
# ===========================================================================
# CORNER CASES
# ===========================================================================
#
# C1: collect on an infinite lazy sequence (e.g., iterate [x -> x + 1] 0).
#     This will run out of memory. The collect-warn-threshold should detect
#     that the estimated count is unknown/infinite and warn immediately.
#     Recommendation: if count cannot be estimated, warn before collect.
#
# C2: save! to a file that already exists.
#     Behavior: overwrite by default. Provide option: save! "f.csv" {append: true}.
#     No silent data loss -- the overwrite is the expected behavior for scripts.
#
# C3: into! with schema mismatch (pipeline produces fields not in the table).
#     Behavior: error from the database. The error should include the column name.
#     Recommendation: validate schema before bulk insert when possible.
#
# C4: read-csv on a binary file.
#     Behavior: garbled data or parse error. The runtime should detect non-text
#     content (e.g., null bytes) and raise a descriptive error.
#
# C5: connect with invalid URI scheme.
#     connect "ftp://host/path" -- FTP not supported.
#     Behavior: error "unsupported scheme: ftp. Supported: postgres, mysql, s3, http, https"
#
# C6: Pipeline over an empty source.
#     read-csv "empty.csv" |> filter _.x |> count -- result is 0.
#     db |> table "empty_table" |> describe -- shows column info with 0 rows.
#     Empty source is NOT an error.
#
# C7: Multiple collect on the same lazy pipeline from a file source.
#     data is read-csv "f.csv"
#     a is data |> filter _.x |> collect
#     b is data |> filter _.y |> collect
#     Both should work -- the file is re-read for each materialization.
#     This is different from Clojure's default lazy-seq caching behavior.
#     dt/read-csv must return a "re-openable" lazy source.
#
# C8: tap! in a pipeline that is never materialized.
#     data |> filter _.x |> tap! |> map _.y -- if result is never used,
#     tap! never fires. tap! only executes when the pipeline is consumed.
#     In the REPL, the auto-sample triggers tap!, so it WILL fire.
#
# C9: Concurrent access to the same database connection from multiple pipelines.
#     The connection pool handles this. Concurrent collect calls share the pool.
#     Each query gets its own connection from the pool.
#
# C10: Push-down with OR conditions.
#      db |> table "users" |> filter (_.role = "admin" or _.age > 30)
#      SQL: WHERE role = 'admin' OR age > 30
#      This should work -- OR is a SQL-translatable operation.
#
# C11: Push-down with `in` operator.
#      db |> table "users" |> filter (_.role in ["admin" "mod"])
#      SQL: WHERE role IN ('admin', 'mod')
#      Proposal: support `in` push-down for literal lists.
#
# C12: Push-down with nil comparisons.
#      db |> table "users" |> filter _.email = nil
#      SQL: WHERE email IS NULL (not WHERE email = NULL)
#      The push-down engine must translate nil equality to IS NULL.
#
# C13: describe on a pipeline with computed fields.
#      data |> map {total: _.price * _.qty} |> describe
#      describe sees the computed "total" field. Type is inferred from
#      the sample values (numeric). The original column names (price, qty)
#      are not visible -- only the projected field names.
#
# C14: close! on an already-closed connection.
#      close! db -- already closed. Behavior: no-op (idempotent).
#      Do not raise an error on double-close.
#
# C15: save! to stdout.
#      data |> save! "-" -- convention: "-" means stdout.
#      Useful for piping DataTwist output to other CLI tools.
#      Proposal: support this convention.
#
# C16: REPL sample cache invalidation.
#      When does the REPL re-sample? Proposal:
#      - Re-sample when the binding is redefined: result is data |> new-pipeline
#      - Re-sample when the source changes (file modified, DB data changed -- hard to detect)
#      - Do NOT re-sample when the same binding is just re-evaluated
#      Practical approach: cache per binding + definition. Redefine = re-sample.
#
# C17: tap! with a label that matches a binding name.
#      tap! "users" -- "users" is just a string label, not a reference to the binding.
#      No conflict with identifier resolution.
#
# C18: Pipeline fusion with take in the middle.
#      data |> filter _.x |> take 100 |> map _.y
#      Fusion: (comp (filter f) (take 100) (map g)) -- valid transducer composition.
#      take is a transducer-compatible operation.
#
# C19: read-csv with very long lines (>1MB per row).
#      Behavior: should work but may be slow. No hard limit on line length.
#      The streaming parser handles one line at a time regardless of size.
#
# C20: Nested lazy pipelines.
#      data |> map [x -> x.items |> filter _.valid |> count]
#      The inner pipeline (x.items |> filter ...) executes eagerly within
#      the map step because count forces it. The outer pipeline is still lazy.
#      Each element's inner pipeline is independent.
