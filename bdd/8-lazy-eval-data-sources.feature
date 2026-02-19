Feature: Lazy Evaluation, Data Sources & REPL Micro-sampling
  DataTwist pipelines are lazy by default. Building a pipeline constructs
  a computation plan -- it does NOT execute. Execution happens only when
  a materialization function is called, or when the REPL auto-samples
  for preview.

  Core principles (from PRD Section 8 and 10):
    - Everything is lazy: pipelines build plans, not results
    - REPL shows micro-samples (~100 rows) for instant preview
    - Equal speed on 10 and 10,000,000 elements
    - Audit at any point with tap!
    - ! suffix = side-effect functions that return their first argument (passthrough)
      These are: log!, tap!, save!, into!, close!, force!
    - Regular functions (count, collect, first, reduce) trigger evaluation but
      return computed results -- they are NOT passthrough
    - force! materializes a lazy pipeline and returns the data (passthrough)
    - Data sources (DB, files) are first-class pipeline sources
    - Pipelines compile to Clojure lazy-seq and transducers

  # ===========================================================================
  # SECTION 1: LAZY PIPELINE CONSTRUCTION
  # ===========================================================================
  #
  # Pipelines without a materializing function produce a lazy value.
  # No computation is performed until something forces evaluation.
  # This is the foundational invariant of the language runtime.
  # ===========================================================================

  Scenario: A pipeline without materialization is lazy and does not execute
    Given the source code:
      """
      data is [1 2 3 4 5 6 7 8 9 10]
      result is data |> filter _ > 5 |> map _ * 2
      """
    Then result is a lazy pipeline plan
    And no filtering or mapping has been performed yet
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

  Scenario: Lazy pipelines over in-memory collections use Clojure lazy-seq
    Given the source code:
      """
      numbers is range 1 1000000
      evens is numbers |> filter [n -> n % 2 = 0]
      """
    Then evens is a Clojure lazy sequence
    And it has NOT materialized 1,000,000 elements in memory

  Scenario: Nil source in a pipeline produces an empty collection
    Given the source code:
      """
      result is nil |> filter _ > 0 |> collect
      """
    Then result is []
    And no error is raised

  # ===========================================================================
  # SECTION 2: MATERIALIZATION FUNCTIONS
  # ===========================================================================
  #
  # These functions force execution of the pipeline:
  #   - collect, count, first, reduce -- regular functions, return computed results
  #   - save!, into!, force! -- side-effect ! functions, return their input (passthrough)
  #
  # The ! convention means: side-effect + passthrough (returns first argument).
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

  Scenario: collect on an already-materialized collection is a no-op
    Given the source code:
      """
      items is [1 2 3]
      result is items |> collect
      """
    Then result equals [1 2 3]
    And no extra work is performed since items is already in memory

  Scenario: count forces full traversal and returns exact count
    Given the source code:
      """
      data is range 1 10000001
      n is data |> filter [x -> x % 7 = 0] |> count
      """
    Then n is the exact count of multiples of 7 in range 1..10000000
    And the entire pipeline was executed to count every element

  Scenario: count on an in-memory collection returns exact count instantly
    Given the source code:
      """
      items is [1 2 3 4 5]
      n is items |> count
      """
    Then n is 5

  Scenario: first forces evaluation until one element is found
    Given the source code:
      """
      data is [{score: 95} {score: 70} {score: 88}]
      result is data |> filter _.score > 90 |> first
      """
    Then result is {score: 95}
    And only elements up to and including the first match were evaluated

  Scenario: reduce folds the pipeline into a single value
    Given the source code:
      """
      total is [10 20 30] |> map _.amount |> reduce [a b -> a + b] 0
      """
    Then the pipeline is fully executed
    And total is the sum of all amounts

  Scenario: reduce with explicit initial value
    Given the source code:
      """
      result is [1 2 3 4 5] |> reduce [a b -> a + b] 0
      """
    Then result is 15

  Scenario: force! materializes a lazy pipeline and returns the data (passthrough)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> map _ * 10 |> force!
      """
    Then force! triggers full evaluation of the pipeline
    And force! returns the materialized data (passthrough -- the data flows through)
    And result is [30 40 50]

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

  Scenario: save! writes pipeline output to a file and returns the data (passthrough)
    Given the source code:
      """
      data |> filter _.active |> map _.name |> save! "output.json"
      """
    Then the pipeline is fully executed
    And the resulting names are written to output.json
    And save! returns the data that was written (passthrough semantics)

  Scenario: save! supports multiple file formats determined by file extension
    Given the source code:
      """
      data |> save! "output.csv"
      data |> save! "output.json"
      data |> save! "output.parquet"
      """
    Then each line writes data in the format implied by the file extension
    And CSV produces comma-separated values with a header row
    And JSON produces a JSON array
    And Parquet produces a columnar Parquet file

  Scenario: into! inserts pipeline output into a database table and returns data (passthrough)
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      data |> filter _.active |> into! db "results"
      """
    Then the pipeline is fully executed
    And each resulting row is inserted into the "results" table
    And into! returns the data that was inserted (passthrough semantics)

  Scenario: Chaining after a materialization function starts a new pipeline
    Given the source code:
      """
      materialized is data |> filter _.active |> collect
      result is materialized |> map _.name |> count
      """
    Then materialized is a concrete vector (not lazy)
    And the second pipeline operates on the materialized vector
    And result is the exact count of names

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
    And the REPL displays an estimated total count (e.g., "~7,500 rows")
    And the REPL response is instant (sub-second) regardless of data size

  Scenario: REPL preview shows tabular format for collections of objects
    Given a lazy pipeline:
      """
      users |> filter _.active |> map {name: _.name score: _.score}
      """
    When entered in the REPL
    Then the REPL displays a tabular preview with column headers
    And it indicates the total estimated row count
    And it shows only the first 100 rows (or fewer if that is all there is)

  Scenario: REPL shows full result for small collections that fit in sample
    Given a lazy pipeline:
      """
      [1 2 3 4 5] |> filter _ > 2
      """
    When entered in the REPL
    Then the REPL displays the full result: [3 4 5]
    And no row-count estimate is shown because all elements fit in the sample

  Scenario: REPL preview for a scalar result shows the value directly
    Given the expression:
      """
      users |> filter _.active |> count
      """
    When entered in the REPL
    Then the REPL displays the exact count (a scalar integer)
    And no micro-sampling is performed because count returns a scalar

  Scenario: REPL preview uses first-N sampling strategy by default
    Given a lazy pipeline over an ordered source
    When the REPL samples it
    Then the first N elements of the pipeline output are taken
    And sampling preserves pipeline ordering (filter, sort, etc. apply first)
    And no random sampling is performed by default

  # ===========================================================================
  # SECTION 4: TAP! -- INLINE PIPELINE DEBUGGING
  # ===========================================================================
  #
  # tap! is a passthrough side-effect function that shows a sample of
  # data at any point in a pipeline. It follows ! semantics: data flows
  # through unchanged. tap! samples -- it does NOT force full evaluation.
  # ===========================================================================

  Scenario: tap! shows data at a pipeline step and passes it through unchanged
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
    And data flows through unchanged

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

  Scenario: tap! returns its input unchanged (passthrough)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> tap! |> map _ * 10 |> collect
      """
    Then result is [30 40 50]
    And tap! had no effect on the pipeline result

  Scenario: tap! with a transformation function for focused inspection
    Given the source code:
      """
      users
      |> filter _.active
      |> tap! [data -> data |> map _.score |> describe]
      |> map _.name
      """
    Then tap! applies the function to the sample for display purposes only
    And the pipeline data is NOT affected by the tap function
    And the pipeline continues with the original data after tap!

  Scenario: Multiple tap! calls in one pipeline each show data at their respective point
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
  # connect creates a connection to an external data source.
  # table and query create lazy references to database data.
  # Operations are pushed down to SQL where possible.
  # ===========================================================================

  Scenario: Connect to a PostgreSQL database
    Given the source code:
      """
      db is connect "postgres://localhost:5432/mydb"
      """
    Then db is a database connection object
    And db is NOT a collection -- it is a source handle

  Scenario: Connect with explicit credentials object
    Given the source code:
      """
      db is connect "postgres://localhost/mydb" {
        user: "admin"
        password: "secret"
        pool-size: 10
      }
      """
    Then db is a database connection with the specified credentials
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

  Scenario: Pipeline over a database table is lazy until materialized
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      active-users is db |> table "users" |> filter _.active |> sort-by _.name
      """
    Then active-users is a lazy query plan
    And no SQL has been executed

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

  Scenario: Table source materializes to a full scan on collect
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      all-users is db |> table "users" |> collect
      """
    Then a SELECT * FROM users query is executed
    And all-users is a vector of maps in memory
    And each map represents one row with keyword keys

  Scenario: close! explicitly releases a database connection
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> filter _.active |> collect
      close! db
      """
    Then the connection is shut down after close!
    And subsequent operations on db raise an error

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

  Scenario: Read CSV with explicit options
    Given the source code:
      """
      data is read-csv "data.tsv" {separator: "\t" header: true encoding: "UTF-8"}
      """
    Then data is a lazy sequence of maps
    And the tab character is used as the field separator
    And the first row is treated as headers

  Scenario: Read CSV without headers produces vectors
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

  Scenario: Read Parquet file as lazy columnar source
    Given the source code:
      """
      data is read-parquet "warehouse.parquet"
      """
    Then data is a lazy source backed by columnar Parquet reading
    And column pruning is supported (only referenced columns are read)

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

  # ===========================================================================
  # SECTION 7: SQL PUSH-DOWN OPTIMIZATION
  # ===========================================================================
  #
  # When a pipeline operates on a database source, the compiler/runtime
  # attempts to push operations down to SQL for efficient execution.
  # Push-down stops at the first operation that cannot be translated to SQL.
  # PRD Tier 1 push-down: filter -> WHERE, sort-by -> ORDER BY,
  #   take -> LIMIT, count -> COUNT(*), map {fields} -> SELECT columns.
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

  Scenario: count on a database source pushes down to SQL COUNT(*)
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

  Scenario: Combined push-down for filter, sort, and limit in one SQL query
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
    And all operations are pushed to a single SQL query

  Scenario: Push-down stops at non-translatable pipeline operations
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users"
        |> filter _.active
        |> map [u -> {name: u.name score: custom-score-fn u}]
        |> sort-by _.score
        |> collect
      """
    Then filter _.active is pushed to SQL WHERE active = true
    And the map with custom-score-fn is executed in Clojure (not pushable)
    And sort-by _.score is executed in Clojure (after the non-pushable step)

  Scenario: explain shows the execution plan without executing the query
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users"
        |> filter _.active
        |> sort-by _.name
        |> take 10
        |> explain
      """
    Then explain displays the generated SQL and estimated row counts
    And no query is executed against the database

  # ===========================================================================
  # SECTION 8: EXPLORE / DESCRIBE FUNCTIONS
  # ===========================================================================
  #
  # Exploration functions provide statistical summaries and quick views
  # of data. They operate on samples for speed where possible.
  # PRD stdlib: describe, schema, sample, histogram, freq, explain
  # ===========================================================================

  Scenario: describe shows statistical summary of a dataset
    Given the source code:
      """
      data is read-csv "sales.csv"
      data |> describe
      """
    Then describe displays column statistics (type, non-null %, min, max, mean, distinct)
    And describe samples the data (it does not scan the entire dataset)
    And describe returns a structured data value (a map)

  Scenario: schema shows column names and inferred types only
    Given the source code:
      """
      data is read-csv "sales.csv"
      data |> schema
      """
    Then schema displays each column name and its inferred type
    And schema examines only a small sample to infer types
    And schema returns a structured data value

  Scenario: schema for a database table uses database metadata
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users" |> schema
      """
    Then schema queries the database metadata (not the data rows)
    And it displays actual database column types

  Scenario: sample returns N elements from the data
    Given the source code:
      """
      data |> sample 20
      """
    Then 20 elements are returned
    And sample forces partial materialization (it is NOT lazy)

  Scenario: freq shows frequency table for a field
    Given the source code:
      """
      data |> freq _.region
      """
    Then freq displays each distinct value and its count and percentage
    And freq returns a structured data value (a collection of maps)

  Scenario: histogram shows ASCII histogram for a numeric field
    Given the source code:
      """
      data |> histogram _.age
      """
    Then histogram displays an ASCII distribution of the age values
    And bin sizes are automatically determined
    And histogram is based on a sample for large datasets

  Scenario: explain on a file-backed pipeline shows the execution plan
    Given the source code:
      """
      read-csv "sales.csv"
      |> filter _.region = "EU"
      |> sort-by _.amount
      |> take 10
      |> explain
      """
    Then explain shows the steps in the pipeline plan
    And no data is read from the file

  # ===========================================================================
  # SECTION 9: PIPELINE AS FIRST-CLASS RUNTIME OBJECT
  # ===========================================================================
  #
  # From PRD Section 10: Pipeline is not syntax sugar -- it is an inspectable
  # runtime construct. It has source, steps, metadata (source locations,
  # labels), and can be inspected at any step via dtw/inspect.
  # ===========================================================================

  Scenario: Pipeline is a first-class value that can be bound to a name
    Given the source code:
      """
      plan is data |> filter _.active |> map _.name |> sort-by _
      """
    Then plan is a runtime pipeline object (not a materialized collection)
    And plan can be passed to functions or reused

  Scenario: Pipeline retains metadata about each step
    Given the pipeline:
      """
      users
      |> filter _.status = "active"
      |> map {name: _.name}
      |> sort-by _.name
      """
    Then the pipeline object has a source (users) and three steps
    And each step has a label describing the operation
    And each step has source location metadata (line number)

  Scenario: dtw/inspect returns sample data after a specific pipeline step
    Given the source code:
      """
      plan is users
        |> filter _.status = "active"
        |> map {name: _.name}
        |> sort-by _.name
      sample is dtw/inspect plan 1 100
      """
    Then sample contains up to 100 rows of data as seen after step 1 (filter)
    And the full pipeline is NOT executed -- only the first step is sampled

  Scenario: Pipeline lazy evaluation is transparent to the user
    Given the source code:
      """
      a is data |> filter _.x |> map _.y
      b is a
      """
    Then a and b reference the same lazy pipeline object
    And evaluating a and evaluating b produce the same results

  # ===========================================================================
  # SECTION 10: ERROR HANDLING FOR DATA SOURCES
  # ===========================================================================

  Scenario: Connection failure raises a descriptive error
    Given the source code:
      """
      db is connect "postgres://nonexistent-host/mydb"
      """
    When the connection is attempted
    Then an error is raised with a message containing the host name
    And the error can be caught with try-catch

  Scenario: File not found raises an error when pipeline is first evaluated
    Given the source code:
      """
      data is read-csv "nonexistent.csv"
      data |> collect
      """
    When the pipeline is materialized
    Then an error is raised with a message containing "not found" and the filename
    And the error is a java.io.FileNotFoundException under the hood

  Scenario: Query timeout on database source raises an error
    Given the source code:
      """
      db is connect "postgres://localhost/mydb" {query-timeout: 5000}
      result is db |> query "SELECT * FROM huge_table CROSS JOIN another_table" |> collect
      """
    When the query exceeds 5000ms
    Then an error is raised with a message containing "timeout"

  Scenario: Schema mismatch is nil-tolerant (non-existent field returns nil)
    Given the source code:
      """
      data is read-csv "data.csv"
      result is data |> filter _.nonexistent-column > 5 |> collect
      """
    Then nonexistent-column access returns nil for each row (nil-tolerant)
    And the filter excludes all rows (nil > 5 is falsy)
    And result is an empty collection
    And no error is raised

  # ===========================================================================
  # SECTION 11: INTEGRATION SCENARIOS
  # ===========================================================================

  Scenario: Full ETL pipeline from database to file
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"

      db |> table "orders"
        |> filter _.status = "completed"
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

  Scenario: Streaming pipeline processes large file without unbounded memory use
    Given the source code:
      """
      read-csv "10gb-file.csv"
      |> filter _.region = "EU"
      |> map {id: _.id value: _.amount * _.rate}
      |> save! "eu-values.csv"
      """
    Then the file is processed in a streaming fashion
    And memory usage stays bounded regardless of the input file size

  Scenario: Lazy pipeline reuse -- file sources re-open on each materialization
    Given the source code:
      """
      data is read-csv "f.csv"
      a is data |> filter _.x |> collect
      b is data |> filter _.y |> collect
      """
    Then both a and b are successfully materialized
    And the file is re-read for each materialization (not cached from first traversal)
    And a and b contain independently filtered results

  # ===========================================================================
  # SECTION 12: INFINITE SEQUENCE GENERATORS AND CONFIGURATION
  # ===========================================================================
  #
  # repeat, iterate, cycle produce infinite lazy sequences.
  # dtw/set! and dtw/get configure the global runtime (sample sizes, etc.).
  # All are source generators or configuration -- they are NOT pipeline steps.
  # ===========================================================================

  Scenario: repeat with count produces a bounded lazy sequence
    Given the source code:
      """
      xs is repeat 5 "x"
      result is xs |> collect
      """
    Then result is ["x" "x" "x" "x" "x"]
    And result has exactly 5 elements

  Scenario: repeat without count produces an infinite lazy sequence
    Given the source code:
      """
      xs is repeat "hello"
      result is xs |> take 3 |> collect
      """
    Then xs is a lazy (infinite) sequence
    And result is ["hello" "hello" "hello"]

  Scenario: iterate builds an infinite sequence by applying a function repeatedly
    Given the source code:
      """
      powers is iterate [n -> n * 2] 1
      result is powers |> take 5 |> collect
      """
    Then result is [1 2 4 8 16]
    And powers is an infinite lazy sequence starting at 1 and doubling each step

  Scenario: cycle produces an infinite repeating sequence from a collection
    Given the source code:
      """
      xs is cycle [1 2 3]
      result is xs |> take 7 |> collect
      """
    Then result is [1 2 3 1 2 3 1]
    And xs is an infinite lazy sequence cycling through [1 2 3]

  Scenario: dtw/set! and dtw/get configure global runtime settings
    Given the source code:
      """
      dtw/set! "sample-size" 200
      n is dtw/get "sample-size"
      """
    Then n is 200
    And the sample-size setting is updated globally
    And dtw/set! returns the value that was set
