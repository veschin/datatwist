Feature: Lazy Evaluation, Data Sources & Pipeline Introspection

  DataTwist pipelines are lazy by default. Building a pipeline constructs
  a computation plan -- it does NOT execute. Execution happens only when
  a terminal operation is called, or when the REPL auto-samples for preview.

  Core principles (PRD Section 8 and 10, BACKLOG, design docs):
    - Everything is lazy: pipelines build plans (DTPipeline record), not results
    - Laziness is invisible to users: auto-materialization at all user-facing
      boundaries (REPL, str, tap!, save!, =, error messages)
    - REPL shows micro-samples (~100 rows) for instant preview
    - Equal speed on 10 and 10,000,000 elements
    - tap! is the ONLY pipeline debug probe (three modes: bare, labeled, lambda)
    - inspect, log!, print/println are NOT pipeline debug tools
    - force! is the sole materialization function (passthrough, forces full execution)
    - count, first, reduce are regular terminal functions (not passthrough)
    - save!, into! are passthrough side-effect functions (!) that also materialize
    - Data sources (DB, files) are first-class pipeline sources (Phase 3+)
    - Pipelines compile to Clojure lazy-seq; transducer fusion is a Phase 2+ opt.

  # ===========================================================================
  # PHASE 1: CORE LAZINESS
  # ===========================================================================
  # - Lazy sequences for filter, map, take, drop, distinct, flatten, concat, zip
  # - force! materialization
  # - Lazy pipelines: DTPipeline record, not eager reduce
  # - Auto-materialization at user-facing boundaries (=, str, tap!, REPL)
  # - Eager barriers: sort, sort-by, group-by, reverse (need all elements)
  # - range, repeat, iterate, cycle produce lazy seqs
  # ===========================================================================

  # ---------------------------------------------------------------------------
  # Lazy pipeline construction
  # ---------------------------------------------------------------------------

  Scenario: A pipeline without a terminal operation is lazy and does not execute
    Given the source code:
      """
      data is [1 2 3 4 5 6 7 8 9 10]
      result is data |> filter _ > 5 |> map _ * 2
      """
    Then result is a lazy pipeline (DTPipeline) -- no filtering or mapping yet

  Scenario: Chaining multiple lazy operations builds a deeper plan
    Given the source code:
      """
      users is get-all-users
      result is users
        |> filter _.active
        |> map {name: _.name email: _.email}
        |> take 100
      """
    Then result is a lazy pipeline plan with three steps
    And no filtering, mapping, or taking has been performed yet

  Scenario: Binding a lazy pipeline to a name does not force evaluation
    Given the source code:
      """
      step1 is data |> filter _.active
      step2 is step1 |> map _.name
      """
    Then step1 and step2 are both lazy
    And no element has been processed

  Scenario: filter, map, take, drop, distinct, flatten return lazy sequences
    Given the source code:
      """
      numbers is [1 2 3 4 5 6 7 8 9 10]
      result is numbers |> filter [n -> n % 2 = 0] |> map [n -> n * 10] |> take 3
      """
    Then result is lazy until a terminal operation forces it
    And only 3 elements flow through map once the result is forced

  Scenario: Lazy pipeline over a large in-memory range does not materialize all elements
    Given the source code:
      """
      numbers is range 1 1000000
      evens is numbers |> filter [n -> n % 2 = 0]
      """
    Then evens is lazy
    And it has NOT materialized 1,000,000 elements in memory

  Scenario: Nil source in a pipeline produces an empty collection
    Given the source code:
      """
      result is nil |> filter _ > 0 |> force!
      """
    Then result is []
    And no error is raised

  # ---------------------------------------------------------------------------
  # Eager operations as implicit materialization barriers
  # sort, sort-by, group-by, reverse need all elements -- they are eager,
  # but their OUTPUT can feed new lazy downstream operations.
  # ---------------------------------------------------------------------------

  Scenario: sort-by is an eager barrier -- it realizes its input before sorting
    Given the source code:
      """
      result is [3 1 4 1 5 9] |> sort-by _ |> take 3 |> force!
      """
    Then sort-by realizes the full input sequence before sorting
    And take 3 operates on the concrete sorted result
    And result is [1 1 3]

  Scenario: group-by realizes its input before grouping
    Given the source code:
      """
      data is [{region: "EU" value: 1} {region: "US" value: 2} {region: "EU" value: 3}]
      grouped is data |> group-by _.region
      """
    Then grouped is a concrete map (not a lazy pipeline)
    And grouped has keys "EU" and "US"

  # ---------------------------------------------------------------------------
  # Materialization functions
  # force!  -- sole materialization function: passthrough (! function, returns data)
  # count, first, reduce -- regular terminal functions (return computed results)
  # ---------------------------------------------------------------------------

  Scenario: force! on an already-materialized collection is a no-op
    Given the source code:
      """
      items is [1 2 3]
      result is items |> force!
      """
    Then result equals [1 2 3]

  Scenario: force! materializes a lazy pipeline and returns the data (passthrough)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> map _ * 10 |> force!
      """
    Then force! triggers full evaluation of the pipeline
    And result is [30 40 50]
    And force! returns the materialized data so the pipeline continues (passthrough)

  Scenario: force! is useful for materializing once before multiple downstream uses
    Given the source code:
      """
      processed is data
        |> filter _.active
        |> map {name: _.name score: _.score * 2}
        |> force!
      processed |> save! "output1.json"
      processed |> save! "output2.json"
      """
    Then force! materializes the pipeline once into a concrete collection
    And both save! calls operate on the already-materialized data
    And the source data is accessed only once

  Scenario: count forces full traversal and returns exact count
    Given the source code:
      """
      data is range 1 10000001
      n is data |> filter [x -> x % 7 = 0] |> count
      """
    Then n is the exact count of multiples of 7 in range 1..10000000
    And the entire pipeline was traversed to count every element

  Scenario: count on an in-memory collection returns exact count
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
    And only elements up to the first match were evaluated

  Scenario: reduce folds the pipeline into a single scalar value
    Given the source code:
      """
      result is [1 2 3 4 5] |> reduce [a b -> a + b] 0
      """
    Then the pipeline is fully executed
    And result is 15

  # ---------------------------------------------------------------------------
  # Auto-materialization at user-facing boundaries
  # The user never sees LazySeq@... Laziness is internal only.
  # ---------------------------------------------------------------------------

  Scenario: Equality comparison auto-materializes a lazy sequence
    Given the source code:
      """
      result is [1 2 3 4 5] |> filter _ > 2
      check is result = [3 4 5]
      """
    Then check is true
    And the lazy sequence was auto-materialized for the = comparison
    And the user never sees a LazySeq reference

  Scenario: str auto-materializes a lazy sequence for string conversion
    Given the source code:
      """
      result is [1 2 3] |> map _ * 2
      s is str result
      """
    Then s is a string representation of [2 4 6]
    And the lazy sequence was auto-materialized during string conversion

  Scenario: Error messages include materialized values, not lazy references
    Given a pipeline that produces an error on a lazy sequence
    Then the error message contains actual data values
    And the error message does not contain "LazySeq@" or similar JVM references

  # ---------------------------------------------------------------------------
  # Infinite sequence generators
  # range, repeat, iterate, cycle produce lazy sequences.
  # Must use take or first eventually to avoid infinite evaluation.
  # ---------------------------------------------------------------------------

  Scenario: range with start and end is lazy
    Given the source code:
      """
      nums is range 1 1000000
      result is nums |> take 5 |> force!
      """
    Then result is [1 2 3 4 5]
    And the remaining 999,995 elements were never computed

  Scenario: range with one argument produces a lazy range from zero
    Given the source code:
      """
      result is range 5 |> force!
      """
    Then result is [0 1 2 3 4]

  Scenario: range with no upper bound produces an infinite lazy sequence
    Given the source code:
      """
      naturals is range 1
      result is naturals |> take 4 |> force!
      """
    Then naturals is an infinite lazy sequence starting at 1
    And result is [1 2 3 4]

  Scenario: repeat with count produces a bounded lazy sequence
    Given the source code:
      """
      xs is repeat 5 "x"
      result is xs |> force!
      """
    Then result is ["x" "x" "x" "x" "x"]

  Scenario: repeat without count produces an infinite lazy sequence
    Given the source code:
      """
      xs is repeat "hello"
      result is xs |> take 3 |> force!
      """
    Then xs is infinite
    And result is ["hello" "hello" "hello"]

  Scenario: iterate builds an infinite sequence by repeatedly applying a function
    Given the source code:
      """
      powers is iterate [n -> n * 2] 1
      result is powers |> take 5 |> force!
      """
    Then result is [1 2 4 8 16]
    And powers is an infinite lazy sequence

  Scenario: cycle produces an infinite repeating sequence from a finite collection
    Given the source code:
      """
      xs is cycle [1 2 3]
      result is xs |> take 7 |> force!
      """
    Then result is [1 2 3 1 2 3 1]

  # ===========================================================================
  # PHASE 2: EXPLORATION & DEBUG
  # ===========================================================================
  # - tap! three modes: bare, labeled, lambda
  # - autotap! wraps every subsequent pipeline step
  # - REPL micro-sampling and display protocol
  # - Reified pipeline: DTPipeline record with step metadata and sample cache
  # - Exploration functions: describe, schema, sample, freq, histogram, explain
  # - System constants: SAMPLE_SIZE, MAX_COLLECT_ROWS, DESCRIBE_SAMPLE_SIZE, PRINT_WIDTH
  # - set! dtw.CONSTANT value configures global runtime settings
  # - dtw.CONSTANT reads a system constant (dot-access)
  # - dtw.inspect returns cached sample data at a specific pipeline step
  # ===========================================================================

  # ---------------------------------------------------------------------------
  # tap! -- the ONLY pipeline debug probe
  # inspect, log!, print, println are NOT pipeline debug tools.
  # tap! is passthrough: data flows through unchanged.
  # ---------------------------------------------------------------------------

  Scenario: tap! bare mode shows a sample of data at its pipeline position
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

  Scenario: tap! labeled mode prints a header then shows the sample
    Given the source code:
      """
      users
      |> filter _.active
      |> tap! "after filter"
      |> map _.name
      |> tap! "after map"
      """
    Then the first tap! output begins with "--- after filter ---"
    And the second tap! output begins with "--- after map ---"
    And data flows through unchanged after each tap!

  Scenario: tap! lambda mode applies the function to the sample for display only
    Given the source code:
      """
      users
      |> filter _.active
      |> tap! [d -> format "found %s items" (count d)]
      |> map _.name
      """
    Then the tap! output shows the formatted string with the item count
    And the pipeline data is NOT affected by the lambda
    And the pipeline continues with the original data after tap!

  Scenario: tap! takes a micro-sample and does not force full evaluation
    Given a pipeline over 10,000,000 rows:
      """
      huge-dataset
      |> filter _.valid
      |> tap!
      |> map _.name
      """
    Then tap! displays approximately SAMPLE_SIZE rows (default 100)
    And tap! does NOT force evaluation of all 10,000,000 rows
    And the response is instant regardless of dataset size

  Scenario: tap! returns its input unchanged (passthrough semantics)
    Given the source code:
      """
      data is [1 2 3 4 5]
      result is data |> filter _ > 2 |> tap! |> map _ * 10 |> force!
      """
    Then result is [30 40 50]
    And tap! had no effect on the pipeline result

  Scenario: Multiple tap! calls each show data at their respective pipeline point
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
    And each shows data at its respective point in the pipeline
    And the final result is the sum of completed order totals

  Scenario: inspect is not a pipeline debug tool
    Given the source code uses inspect in a pipeline:
      """
      data |> filter _.active |> inspect |> map _.name
      """
    Then a parse or runtime error is raised
    And the hint suggests using tap! instead

  Scenario: log! is not a pipeline debug tool
    Given the source code uses log! in a pipeline:
      """
      data |> filter _.active |> log! "step" |> map _.name
      """
    Then a parse or runtime error is raised
    And the hint suggests using tap! "step" instead

  # ---------------------------------------------------------------------------
  # autotap! -- wraps every subsequent pipeline step with tap! output
  # Place once at the start; instruments all steps automatically.
  # Output format: first line is function label [fn], second line is sample.
  # ---------------------------------------------------------------------------

  Scenario: autotap! placed at start instruments every subsequent step
    Given the source code:
      """
      users
      |> autotap!
      |> filter _.active
      |> map {name: _.name}
      |> sort-by _.name
      """
    Then tap! output is shown for the filter step with label "[filter _.active]"
    And tap! output is shown for the map step with label "[map {name: _.name}]"
    And tap! output is shown for the sort-by step with label "[sort-by _.name]"
    And data flows through unchanged to produce the final sorted result

  Scenario: autotap! output format is function label on first line, sample on second
    Given a pipeline with autotap!:
      """
      data |> autotap! |> filter _.x > 0 |> map _.y
      """
    Then the filter step output has "[filter _.x > 0]" on the first line
    And the sample data follows on the second line
    And the map step output has "[map _.y]" on the first line

  Scenario: autotap! is equivalent to inserting tap! before each step
    Given the source code:
      """
      data |> autotap! |> filter _.active |> map _.name
      """
    Then the behavior is equivalent to:
      """
      data |> tap! |> filter _.active |> tap! |> map _.name
      """
    And the final result is the same as without autotap!

  # ---------------------------------------------------------------------------
  # REPL micro-sampling
  # REPL auto-samples the first SAMPLE_SIZE rows for instant preview.
  # User never sees LazySeq@... in the REPL output.
  # ---------------------------------------------------------------------------

  Scenario: REPL auto-samples a lazy pipeline for instant preview
    Given a lazy pipeline:
      """
      users |> filter _.active |> sort-by _.score
      """
    When entered in the REPL
    Then the REPL displays a preview of approximately SAMPLE_SIZE rows (default 100)
    And the REPL displays an estimated total count (e.g., "~7,500 rows")
    And the REPL response is instant regardless of data size

  Scenario: REPL preview shows tabular format for collections of objects
    Given a lazy pipeline:
      """
      users |> filter _.active |> map {name: _.name score: _.score}
      """
    When entered in the REPL
    Then the REPL displays a tabular preview with column headers
    And it indicates the total estimated row count
    And it shows only the first SAMPLE_SIZE rows (or fewer if that is all there is)

  Scenario: REPL shows full result for small collections that fit within sample size
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

  Scenario: REPL uses first-N sampling strategy by default
    Given a lazy pipeline over an ordered source
    When the REPL samples it
    Then the first N elements of the pipeline output are taken (first-N, not random)
    And sampling preserves pipeline ordering (filter, sort, etc. apply first)

  Scenario: REPL does not display LazySeq references
    Given any lazy pipeline expression
    When entered in the REPL
    Then the output never contains "LazySeq@" or any JVM object reference
    And the output is always a human-readable table or value

  # ---------------------------------------------------------------------------
  # Reified pipeline: DTPipeline record
  # |> builds a DTPipeline, not just a lazy seq. Steps have labels, source locs,
  # and sample caches (populated on first execution).
  # ---------------------------------------------------------------------------

  Scenario: Pipeline is a first-class DTPipeline value that can be bound to a name
    Given the source code:
      """
      plan is data |> filter _.active |> map _.name |> sort-by _
      """
    Then plan is a runtime DTPipeline object (not a materialized collection)
    And plan can be passed to functions or reused

  Scenario: DTPipeline retains metadata about each step
    Given the pipeline:
      """
      users
      |> filter _.status = "active"
      |> map {name: _.name}
      |> sort-by _.name
      """
    Then the pipeline object has a source (users) and three steps
    And each step has a label describing the operation (e.g., "filter _.status = \"active\"")
    And each step has source location metadata (line number)

  Scenario: DTPipeline caches a sample at each step boundary after first execution
    Given the pipeline:
      """
      plan is users
        |> filter _.active
        |> map {name: _.name}
      plan |> force!
      """
    Then after force! triggers evaluation, each step's sample is cached
    And the cache holds up to SAMPLE_SIZE rows per step
    And subsequent dtw.inspect calls use the cached samples without re-executing

  Scenario: Reassigning a pipeline name invalidates the old pipeline cache
    Given the source code:
      """
      plan is data |> filter _.x
      plan |> force!
      plan is data |> filter _.y
      """
    Then the second assignment creates a new DTPipeline with an empty cache
    And the old pipeline's cache is discarded

  # ---------------------------------------------------------------------------
  # dtw.inspect -- programmatic step inspection (for scripts and CI)
  # Returns cached sample for a specific step without re-running the pipeline.
  # IDE uses nREPL op inspect-pipeline-step; dtw.inspect is the programmatic equiv.
  # ---------------------------------------------------------------------------

  Scenario: dtw.inspect returns sample data after a specific pipeline step
    Given the source code:
      """
      plan is users
        |> filter _.status = "active"
        |> map {name: _.name}
        |> sort-by _.name
      plan |> force!
      step-sample is dtw.inspect plan 1 100
      """
    Then step-sample contains up to 100 rows of data as seen after step 1 (filter)
    And dtw.inspect uses the cached sample (no re-execution)

  Scenario: dtw.inspect on an un-executed pipeline triggers execution first
    Given the source code:
      """
      plan is data |> filter _.active |> map _.name
      step-sample is dtw.inspect plan 0 50
      """
    Then the pipeline is executed once to populate the cache
    And step-sample contains up to 50 rows from after step 0 (filter)

  # ---------------------------------------------------------------------------
  # Exploration functions: describe, schema, sample, freq, histogram, explain
  # describe, schema, histogram operate on a sample (do not force full eval).
  # freq forces full evaluation (needs exact counts).
  # explain inspects the pipeline plan without accessing data.
  # ---------------------------------------------------------------------------

  Scenario: describe shows statistical summary of a dataset using a sample
    Given the source code:
      """
      data is [
        {name: "Alice" age: 30 score: 95}
        {name: "Bob"   age: 25 score: 72}
        {name: "Carol" age: 35 score: 88}
      ]
      result is data |> describe
      """
    Then result is a structured map of per-column statistics (type, min, max, mean, nulls)
    And describe samples at most DESCRIBE_SAMPLE_SIZE rows (default 1000)
    And describe does NOT force evaluation of the entire dataset

  Scenario: describe with explicit sample size override
    Given the source code:
      """
      data |> describe 5000
      """
    Then describe uses a 5000-row sample (overriding the default DESCRIBE_SAMPLE_SIZE)

  Scenario: schema shows column names and inferred types using a small sample
    Given the source code:
      """
      data is [{name: "Alice" age: 30} {name: "Bob" age: 25}]
      result is data |> schema
      """
    Then result is a collection of maps with keys "name" and "type" per column
    And schema samples at most SAMPLE_SIZE rows to infer types
    And schema does NOT force evaluation of the entire dataset

  Scenario: sample returns N randomly selected elements
    Given the source code:
      """
      result is data |> sample 20
      """
    Then result contains exactly 20 elements
    And sample forces partial materialization (it is NOT lazy)

  Scenario: freq shows exact frequency table for a field (forces full evaluation)
    Given the source code:
      """
      data is [{region: "EU"} {region: "US"} {region: "EU"} {region: "EU"}]
      result is data |> freq _.region
      """
    Then result is a collection of maps with value, count, and percentage
    And freq forces full evaluation of the pipeline to count all elements exactly
    And the EU entry shows count 3 and pct 75.0

  Scenario: histogram shows distribution of a numeric field using a sample
    Given the source code:
      """
      data |> histogram _.age
      """
    Then histogram returns a structured map with bins and counts
    And bin boundaries are automatically determined
    And histogram uses at most DESCRIBE_SAMPLE_SIZE rows (default 1000)
    And histogram does NOT force evaluation of the entire dataset

  Scenario: explain shows the pipeline execution plan without accessing data
    Given the source code:
      """
      data
      |> filter _.region = "EU"
      |> sort-by _.amount
      |> take 10
      |> explain
      """
    Then explain displays a human-readable execution plan showing the steps
    And no data is read or evaluated

  # ---------------------------------------------------------------------------
  # System constants and configuration
  # SAMPLE_SIZE, MAX_COLLECT_ROWS, DESCRIBE_SAMPLE_SIZE, PRINT_WIDTH
  # set! dtw.CONSTANT value and dtw.CONSTANT use symbol keys (uppercase), not string keys.
  # ---------------------------------------------------------------------------

  Scenario: SAMPLE_SIZE constant has default value 100
    Given the source code:
      """
      n is SAMPLE_SIZE
      """
    Then n is 100

  Scenario: set! dtw.CONSTANT changes a system constant and dot-access reads it back
    Given the source code:
      """
      set! dtw.SAMPLE_SIZE 200
      n is dtw.SAMPLE_SIZE
      """
    Then n is 200
    And the SAMPLE_SIZE setting is updated globally for the session

  Scenario: SAMPLE_SIZE affects how many rows tap! and REPL preview show
    Given the source code:
      """
      set! dtw.SAMPLE_SIZE 50
      data |> tap!
      """
    Then tap! displays at most 50 rows (the new SAMPLE_SIZE)

  Scenario: DESCRIBE_SAMPLE_SIZE has default value 1000
    Given the source code:
      """
      n is DESCRIBE_SAMPLE_SIZE
      """
    Then n is 1000

  Scenario: PRINT_WIDTH has default value 120
    Given the source code:
      """
      n is PRINT_WIDTH
      """
    Then n is 120

  Scenario: MAX_COLLECT_ROWS has default value nil (unlimited)
    Given the source code:
      """
      n is MAX_COLLECT_ROWS
      """
    Then n is nil
    And force! on a large collection has no row limit by default

  Scenario: Setting MAX_COLLECT_ROWS enforces a safety cap on force!
    Given the source code:
      """
      set! dtw.MAX_COLLECT_ROWS 10000
      result is big-data |> force!
      """
    Then force! returns at most 10,000 rows even if the source has more
    And a warning is displayed that the result was truncated at MAX_COLLECT_ROWS

  Scenario: set! dtw.CONSTANT with an unknown constant raises an error with hint
    Given the source code:
      """
      set! dtw.UNKNOWN_KEY 42
      """
    Then an error is raised
    And the error message lists the valid constant names

  # ===========================================================================
  # PHASE 3: DATA SOURCES (stubs -- not yet implemented)
  # ===========================================================================
  # - connect, table, query for databases (PostgreSQL, etc.)
  # - read-csv, read-json, read-jsonl, read-lines, read-parquet for files
  # - save!, into!, close! for output and connection management
  # ===========================================================================

  @stub
  Scenario: Connect to a PostgreSQL database
    Given the source code:
      """
      db is connect "postgres://localhost:5432/mydb"
      """
    Then db is a database connection object (not a collection)
    And db can be used as a pipeline source via table or query

  @stub
  Scenario: Connect with explicit credentials
    Given the source code:
      """
      db is connect "postgres://localhost/mydb" {
        user: "admin"
        password: "secret"
        pool-size: 10
      }
      """
    Then db is a connection with the specified credentials
    And the pool size is configured to 10

  @stub
  Scenario: Reference a database table as a lazy data source
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      users is db |> table "users"
      """
    Then users is a lazy reference to the "users" table
    And no SQL query has been executed yet

  @stub
  Scenario: Pipeline over a database table is lazy until a terminal operation
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      active-users is db |> table "users" |> filter _.active |> sort-by _.name
      """
    Then active-users is a lazy query plan
    And no SQL has been executed

  @stub
  Scenario: Raw SQL query as a lazy data source
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      results is db |> query "SELECT id, name, score FROM users WHERE active = true"
      """
    Then results is a lazy sequence of maps (one per row)
    And the query is not executed until materialization

  @stub
  Scenario: Database query with parameterized SQL
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      results is db |> query "SELECT * FROM users WHERE age > ? AND city = ?" [18 "Moscow"]
      """
    Then the query uses parameterized SQL (preventing SQL injection)
    And results is a lazy sequence of maps

  @stub
  Scenario: Table source materializes to a full scan on force!
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      all-users is db |> table "users" |> force!
      """
    Then a SELECT * FROM users query is executed
    And all-users is a vector of maps in memory

  @stub
  Scenario: close! explicitly releases a database connection
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> filter _.active |> force!
      close! db
      """
    Then the connection is released after close!
    And subsequent operations on db raise a connection-closed error

  @stub
  Scenario: Read CSV file as lazy sequence of maps
    Given the source code:
      """
      data is read-csv "sales.csv"
      """
    Then data is a lazy sequence of maps (one per row)
    And the first row of the CSV is used as column names (keys)
    And no rows beyond the header have been read yet

  @stub
  Scenario: Read CSV with explicit options
    Given the source code:
      """
      data is read-csv "data.tsv" {separator: "\t" header: true encoding: "UTF-8"}
      """
    Then data is a lazy sequence of maps
    And the tab character is used as the field separator

  @stub
  Scenario: Read CSV without headers produces vectors instead of maps
    Given the source code:
      """
      data is read-csv "raw.csv" {header: false}
      """
    Then data is a lazy sequence of vectors (one per row)

  @stub
  Scenario: Read JSON file as lazy sequence (array) or single map (object)
    Given the source code:
      """
      data is read-json "data.json"
      """
    Then if the file contains a JSON array, data is a lazy sequence of elements
    And if the file contains a JSON object, data is a single map

  @stub
  Scenario: Read JSON lines (newline-delimited JSON) as lazy sequence of maps
    Given the source code:
      """
      data is read-jsonl "events.jsonl"
      """
    Then data is a lazy sequence of maps (one per line)
    And the file is streamed line by line (not loaded fully into memory)

  @stub
  Scenario: Read text file as lazy sequence of lines
    Given the source code:
      """
      lines is read-lines "logfile.txt"
      """
    Then lines is a lazy sequence of strings (one per line)

  @stub
  Scenario: Read Parquet file as lazy columnar source
    Given the source code:
      """
      data is read-parquet "warehouse.parquet"
      """
    Then data is a lazy source backed by columnar Parquet reading

  @stub
  Scenario: save! writes pipeline output to a file and returns data (passthrough)
    Given the source code:
      """
      data |> filter _.active |> map _.name |> save! "output.json"
      """
    Then the pipeline is fully executed
    And the resulting names are written to output.json
    And save! returns the data that was written (passthrough -- pipeline continues)

  @stub
  Scenario: save! supports multiple file formats by extension
    Given the source code:
      """
      data |> save! "output.csv"
      data |> save! "output.json"
      data |> save! "output.parquet"
      """
    Then each call writes data in the format implied by the file extension
    And CSV produces comma-separated values with a header row
    And JSON produces a JSON array
    And Parquet produces a columnar Parquet file

  @stub
  Scenario: into! inserts pipeline output into a database table and returns data (passthrough)
    Given the source code:
      """
      db is connect "postgres://localhost/mydb"
      data |> filter _.active |> into! db "results"
      """
    Then the pipeline is fully executed
    And each resulting row is inserted into the "results" table
    And into! returns the data that was inserted (passthrough)

  @stub
  Scenario: Streaming pipeline processes large file without unbounded memory use
    Given the source code:
      """
      read-csv "10gb-file.csv"
      |> filter _.region = "EU"
      |> map {id: _.id value: _.amount * _.rate}
      |> save! "eu-values.csv"
      """
    Then the file is processed in a streaming fashion row by row
    And memory usage stays bounded regardless of input file size

  @stub
  Scenario: Lazy pipeline reuse -- file sources re-open on each materialization
    Given the source code:
      """
      data is read-csv "f.csv"
      a is data |> filter _.x |> force!
      b is data |> filter _.y |> force!
      """
    Then both a and b are successfully materialized
    And the file is re-read for each materialization (not cached from first traversal)

  @stub
  Scenario: Connection failure raises a descriptive error
    Given the source code:
      """
      db is connect "postgres://nonexistent-host/mydb"
      """
    When the connection is attempted
    Then an error is raised with the connection details in the message

  @stub
  Scenario: File not found raises an error when pipeline is first evaluated
    Given the source code:
      """
      data is read-csv "nonexistent.csv"
      data |> force!
      """
    When the pipeline is materialized
    Then an error is raised containing "not found" and the filename

  @stub
  Scenario: Non-existent field access in a pipeline is nil-tolerant
    Given the source code:
      """
      data is read-csv "data.csv"
      result is data |> filter _.nonexistent-column > 5 |> force!
      """
    Then nonexistent-column access returns nil for each row
    And the filter excludes all rows (nil > 5 is nil, which is falsy)
    And result is an empty collection
    And no error is raised

  # ===========================================================================
  # PHASE 4+: PUSHDOWN, TRANSDUCERS, NREPL, LSP (future -- design only)
  # ===========================================================================
  # - SQL push-down: filter -> WHERE, sort-by -> ORDER BY, take -> LIMIT,
  #   map {fields} -> SELECT columns, count -> COUNT(*)
  # - Transducer fusion: consecutive filter/map/take/drop fused into single xf
  # - nREPL op: inspect-pipeline-step (returns cached step sample, no re-eval)
  # - IDE overlay: step number, row count, sample table on each |>
  # - schema for database tables uses DB metadata (not data sampling)
  # ===========================================================================

  @stub
  Scenario: filter pushes down to SQL WHERE clause
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> filter _.age > 18 |> force!
      """
    Then the filter is translated to SQL WHERE age > 18
    And the filter is not applied in Clojure

  @stub
  Scenario: sort-by pushes down to SQL ORDER BY
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> sort-by _.name |> force!
      """
    Then sort-by is translated to SQL ORDER BY name ASC

  @stub
  Scenario: take pushes down to SQL LIMIT
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> take 10 |> force!
      """
    Then take is translated to SQL LIMIT 10

  @stub
  Scenario: map with field selection pushes down to SQL SELECT
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users" |> map {name: _.name age: _.age} |> force!
      """
    Then map is translated to SQL SELECT name, age

  @stub
  Scenario: count on a database source pushes down to SQL COUNT(*)
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      n is db |> table "users" |> filter _.active |> count
      """
    Then the query is SELECT COUNT(*) FROM users WHERE active = true
    And no rows are transferred from the database

  @stub
  Scenario: Push-down stops at non-translatable operations and resumes in Clojure
    Given a database pipeline with a custom function:
      """
      db is connect "postgres://localhost/mydb"
      result is db |> table "users"
        |> filter _.active
        |> map [u -> {name: u.name score: custom-score-fn u}]
        |> sort-by _.score
        |> force!
      """
    Then filter _.active is pushed to SQL WHERE active = true
    And the map with custom-score-fn is executed in Clojure (not pushable)
    And sort-by _.score is executed in Clojure after the non-pushable step

  @stub
  Scenario: explain on a database pipeline shows generated SQL and execution plan
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users"
        |> filter _.active
        |> sort-by _.name
        |> take 10
        |> explain
      """
    Then explain displays the generated SQL query
    And explain shows estimated row counts per step
    And no query is executed against the database

  @stub
  Scenario: schema for a database table uses database metadata (not row sampling)
    Given a database pipeline:
      """
      db is connect "postgres://localhost/mydb"
      db |> table "users" |> schema
      """
    Then schema queries the database metadata (information_schema or equivalent)
    And it displays actual database column types without reading data rows

  @stub
  Scenario: nREPL op inspect-pipeline-step returns cached step sample by index
    Given a reified pipeline that has been executed
    When the IDE sends inspect-pipeline-step with step-index 1
    Then the cached sample for step 1 is returned without re-evaluating the pipeline
    And the sample is invalidated and recomputed if the pipeline is re-evaluated
