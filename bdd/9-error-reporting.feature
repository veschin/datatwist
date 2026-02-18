Feature: Error Reporting & Developer Experience
  DataTwist targets data analysts, not systems programmers. Errors must be
  maximally informative: no Java/Clojure stack traces, immediate pointer to the
  exact source location, and an immediate suggestion of what to fix.

  Philosophy:
    - Elm/Rust-style: "here is what went wrong, here is where, here is how to fix it"
    - Every error includes: source location (file:line:column), code snippet with
      underline, human-readable explanation, and a contextual hint
    - Warnings are distinct from errors: warnings do not halt execution
    - Errors in REPL are shown inline with color; errors in file execution show
      file path, line, column, and surrounding lines
    - No raw Java exceptions, ClassCastException, NullPointerException, or
      Clojure stack traces are ever shown to the user
    - Data-aware errors: quantified ("3 of 100 rows had nil"), not abstract
    - Error codes (DT-PXXX for parse, DT-TXXX for type, DT-DXXX for data,
      DT-CXXX for connection, DT-RXXX for runtime) for documentation lookup

  Background:
    Given the DataTwist compiler is available
    And error reporting is in human-friendly mode

  # ===========================================================================
  # SECTION 1: Parse Errors (Syntax) -- detected at parse time by Instaparse
  # ===========================================================================

  Scenario: Parse error - unexpected end of expression after operator
    Given the DataTwist source:
      """
      users |> filter _.age >
      """
    When it is parsed
    Then a parse error is produced
    And the error points to line 1, column 28
    And the error message contains "Unexpected end of expression"
    And the error snippet shows:
      """
        1 | users |> filter _.age >
                                   ^^^
      """
    And the hint says "Expected a value after '>' (number, string, identifier, or expression)"
    And the hint includes an example: "users |> filter _.age > 18"
    And the error code is "DT-P001"

  Scenario: Parse error - unclosed string literal
    Given the DataTwist source:
      """
      name is "Alice
      """
    When it is parsed
    Then a parse error is produced
    And the error points to line 1, column 9
    And the error message contains "Unclosed string literal"
    And the error snippet shows:
      """
        1 | name is "Alice
                    ^~~~~~
      """
    And the hint says "Add a closing '\"' at the end of the string"
    And the error code is "DT-P002"

  Scenario: Parse error - unclosed object literal
    Given the DataTwist source:
      """
      user is {name: "Alice" age: 25
      next-thing is 42
      """
    When it is parsed
    Then a parse error is produced
    And the error points to line 1
    And the error message contains "Unclosed '{'"
    And the error snippet shows:
      """
        1 | user is {name: "Alice" age: 25
                    ^
        2 | next-thing is 42
      """
    And the hint says "Add a closing '}' to complete the object"
    And the error code is "DT-P003"

  Scenario: Parse error - unclosed list literal
    Given the DataTwist source:
      """
      items is [1 2 3
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Unclosed '['"
    And the hint says "Add a closing ']' to complete the list"
    And the error code is "DT-P004"

  Scenario: Parse error - missing arrow in guard branch
    Given the DataTwist source:
      """
      tier is
        | amount > 1000 "gold"
        | _ -> "bronze"
      """
    When it is parsed
    Then a parse error is produced
    And the error points to line 2
    And the error message contains "Expected '->' after guard condition"
    And the error snippet shows:
      """
        2 |   | amount > 1000 "gold"
                              ^^^^^^
      """
    And the hint says "Add '->' between the condition and the result: | amount > 1000 -> \"gold\""
    And the error code is "DT-P005"

  Scenario: Parse error - missing expression after pipe operator
    Given the DataTwist source:
      """
      data |>
      """
    When it is parsed
    Then a parse error is produced
    And the error points to line 1, after "|>"
    And the error message contains "Expected a function or expression after '|>'"
    And the hint says "Add a function to apply: data |> count"
    And the error code is "DT-P006"

  Scenario: Parse error - double pipe operators
    Given the DataTwist source:
      """
      data |> |> count
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Unexpected '|>' -- expected a function name"
    And the hint says "Remove the extra '|>' or add a function between them"
    And the error code is "DT-P007"

  Scenario: Parse error - reserved word used as identifier
    Given the DataTwist source:
      """
      true is 42
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "'true' is a reserved word and cannot be used as a name"
    And the hint says "Choose a different name, for example: is-true is 42"
    And the error code is "DT-P008"

  Scenario: Parse error - leading dot in float literal
    Given the DataTwist source:
      """
      x is .5
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Invalid number literal"
    And the hint says "Use '0.5' instead of '.5'"
    And the error code is "DT-P009"

  Scenario: Parse error - missing whitespace around binary operator
    Given the DataTwist source:
      """
      x is 2+3
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Unexpected characters"
    And the hint says "Add spaces around operators: 2 + 3"
    And the error code is "DT-P010"

  Scenario: Parse error - unclosed parenthesis
    Given the DataTwist source:
      """
      x is (2 + 3 * 4
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Unclosed '('"
    And the hint says "Add a closing ')' to complete the expression"
    And the error code is "DT-P011"

  Scenario: Parse error - empty parentheses
    Given the DataTwist source:
      """
      x is ()
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Empty parentheses are not a valid expression"
    And the hint says "Put an expression inside: (2 + 3) or remove the parentheses"
    And the error code is "DT-P012"

  Scenario: Parse error - lambda missing arrow
    Given the DataTwist source:
      """
      double is [x x * 2]
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "Expected '->' in function definition"
    And the hint says "Add '->' between parameters and body: [x -> x * 2]"
    And the error code is "DT-P013"

  Scenario: Parse error - require after code
    Given the DataTwist source:
      """
      x is 42
      require clojure.string as str
      """
    When it is parsed
    Then a parse error is produced
    And the error message contains "'require' must appear at the top of the file"
    And the hint says "Move all 'require' statements before any other code"
    And the error code is "DT-P014"

  Scenario: Parse error shows surrounding context lines
    Given the DataTwist file "pipeline.dtw" with source:
      """
      users is load-data "users.csv"

      active is users
        |> filter _.active
        |> map {name: _.name age:}
        |> sort-by _.age
      """
    When the file is parsed
    Then a parse error is produced
    And the error output includes:
      """
      pipeline.dtw:5:30 -- Parse error [DT-P015]

        Unexpected '}' -- expected a value after ':'

        3 | active is users
        4 |   |> filter _.active
        5 |   |> map {name: _.name age:}
                                       ^
        6 |   |> sort-by _.age

        Hint: Provide a value for the 'age' field: age: _.age
      """

  Scenario: Parse error - multiple errors reported in one pass
    Given the DataTwist source:
      """
      x is "hello
      y is 1 +
      z is (3 * 4
      """
    When it is parsed
    Then at least 1 parse error is produced
    And the first error reported points to the earliest source location
    # NOTE: Whether the parser recovers and reports multiple errors is
    # implementation-dependent. Instaparse reports the farthest match
    # failure. Error recovery is a stretch goal.

  # ===========================================================================
  # SECTION 2: Type Errors (Runtime)
  # ===========================================================================

  Scenario: Type error - string plus number
    Given the DataTwist source:
      """
      result is "hello" + 5
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot add string and number"
    And the error snippet shows:
      """
        1 | result is "hello" + 5
                      -------   -
                      string    number
      """
    And the hint says "To concatenate, convert number to string: \"hello\" + (str 5)"
    And an alternative hint says "Or use format: format \"%s%d\" \"hello\" 5"
    And the error code is "DT-T001"

  Scenario: Type error - boolean in arithmetic
    Given the DataTwist source:
      """
      x is true + 1
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot add boolean and number"
    And the error code is "DT-T001"

  Scenario: Type error - ordering comparison between incompatible types
    Given the DataTwist source:
      """
      result is "hello" > 5
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot compare string and number with '>'"
    And the error snippet shows:
      """
        1 | result is "hello" > 5
                      -------   -
                      string    number
      """
    And the hint says "Ordering comparisons require the same type on both sides"
    And the error code is "DT-T002"

  Scenario: Type error - string repetition with multiply
    Given the DataTwist source:
      """
      result is "ha" * 3
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot multiply string and number"
    And the hint says "String repetition is not supported. Use a function if needed."
    And the error code is "DT-T001"

  Scenario: Type error - nil in arithmetic
    Given the DataTwist source:
      """
      x is 5
      y is nil
      result is x + y
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot add number and nil"
    And the error snippet shows:
      """
        3 | result is x + y
                      -   -
                      5   nil
      """
    And the hint says "Value 'y' is nil. Use nil coalescing: x + (y ?? 0)"
    And the error code is "DT-T003"

  Scenario: Type error - calling a non-function value
    Given the DataTwist source:
      """
      x is 42
      result is x "hello"
      """
    When it is evaluated
    Then a type error is produced
    And the error message contains "Cannot call 42 as a function"
    And the error snippet shows:
      """
        2 | result is x "hello"
                      -
                      number (42)
      """
    And the hint says "'x' is a number, not a function. Did you mean to use an operator?"
    And the error code is "DT-T004"

  Scenario: Type error - division by zero
    Given the DataTwist source:
      """
      result is 10 / 0
      """
    When it is evaluated
    Then a runtime error is produced
    And the error message contains "Division by zero"
    And the error snippet shows:
      """
        1 | result is 10 / 0
                            -
                            zero
      """
    And the hint says "The divisor must not be zero. Check the value or add a guard."
    And the error code is "DT-T005"

  Scenario: Type error shows runtime values of identifiers
    Given the DataTwist source:
      """
      price is get-price item
      tax is get-tax region
      total is price + tax
      """
    When price evaluates to "29.99" (a string) and tax evaluates to 3.50
    Then a type error is produced on line 3
    And the error message contains "Cannot add string and number"
    And the error snippet shows the actual runtime values:
      """
        3 | total is price + tax
                     -----   ---
                     "29.99"  3.5
                     string   number
      """
    And the hint says "price is a string. Convert it first: (to-number price) + tax"

  # ===========================================================================
  # SECTION 3: Pipeline Errors (Wrong Types Flowing Through)
  # ===========================================================================

  Scenario: Pipeline error - filter on non-collection
    Given the DataTwist source:
      """
      result is 42 |> filter _.active
      """
    When it is evaluated
    Then a pipeline error is produced
    And the error message contains "Cannot filter a number"
    And the error snippet shows:
      """
        1 | result is 42 |> filter _.active
                     --     ------
                     number  expects a collection
      """
    And the hint says "filter works on collections (lists or query results): [1 2 3] |> filter _ > 1"
    And the error code is "DT-R001"

  Scenario: Pipeline error - map on non-collection
    Given the DataTwist source:
      """
      result is "hello" |> map _.name
      """
    When it is evaluated
    Then a pipeline error is produced
    And the error message contains "Cannot map over a string"
    And the hint says "map works on collections. To transform a string, use string functions."
    And the error code is "DT-R001"

  Scenario: Pipeline error - sort-by on non-collection
    Given the DataTwist source:
      """
      result is 42 |> sort-by _.name
      """
    When it is evaluated
    Then a pipeline error is produced
    And the error message contains "Cannot sort a number"
    And the error code is "DT-R001"

  Scenario: Pipeline error points to the failing step in a multi-step pipeline
    Given the DataTwist source:
      """
      result is users
        |> filter _.active
        |> count
        |> filter _.name
        |> take 5
      """
    When it is evaluated and count returns 42 (a number)
    Then a pipeline error is produced at step 4 (filter on line 4)
    And the error message contains "Cannot filter a number"
    And the error snippet highlights line 4:
      """
        2 |   |> filter _.active
        3 |   |> count
        4 |   |> filter _.name      <-- error here
                  ------
                  received: 42 (number), expected: collection
        5 |   |> take 5
      """
    And the hint says "count returns a number. You cannot filter a number. Did you mean to filter before counting?"

  Scenario: Pipeline error - wrong number of arguments
    Given the DataTwist source:
      """
      result is users |> sort-by
      """
    When it is evaluated
    Then an error is produced
    And the error message contains "sort-by requires a key function"
    And the hint says "Provide a key: users |> sort-by _.name"
    And the error code is "DT-R002"

  # ===========================================================================
  # SECTION 4: Nil / Data Errors (Warnings and Runtime)
  # ===========================================================================

  Scenario: Nil warning - nil values detected in pipeline map
    Given the DataTwist source:
      """
      result is users |> map _.address.city |> sort-by _
      """
    When it is evaluated and some users have nil address
    Then a warning is produced (not a hard error)
    And the warning message contains "Nil values detected in pipeline"
    And the warning identifies the step: "map _.address.city"
    And the warning quantifies the problem: "3 of 100 sampled rows returned nil"
    And the warning shows sample nil rows:
      """
        Sample nil rows:
        | row 7  | _.address = nil |
        | row 23 | _.address = nil |
        | row 91 | _.address = nil |
      """
    And the hint says "Filter out nils first: users |> filter _.address != nil |> map _.address.city"
    And an alternative hint says "Or provide default: users |> map (_.address.city ?? \"unknown\")"
    And the warning code is "DT-D001"

  Scenario: Nil warning - sort-by with nil keys
    Given the DataTwist source:
      """
      result is users |> sort-by _.age
      """
    When it is evaluated and some users have nil age
    Then a warning is produced
    And the warning message contains "Nil sort keys detected"
    And the warning quantifies: "5 of 200 sampled rows had nil sort key"
    And the hint says "Rows with nil keys will be placed at the end. Filter nils first if this is not desired."
    And the warning code is "DT-D002"

  Scenario: Nil warning - nil in group-by key
    Given the DataTwist source:
      """
      result is orders |> group-by _.region
      """
    When it is evaluated and some orders have nil region
    Then a warning is produced
    And the warning message contains "Nil group keys detected"
    And the warning quantifies the nil count
    And the hint says "Rows with nil key will be grouped under nil. Filter nils first if undesired."
    And the warning code is "DT-D003"

  Scenario: Nil error - nil arithmetic in pipeline map
    Given the DataTwist source:
      """
      totals is orders |> map _.price * _.quantity
      """
    When it is evaluated and some orders have nil price
    Then a type error is produced
    And the error message contains "Cannot multiply nil and number"
    And the error identifies the specific row that caused the failure
    And the error shows context:
      """
        Failing row (index 7): {name: "Widget" price: nil quantity: 5}
      """
    And the hint says "Some rows have nil price. Use default: (_.price ?? 0) * _.quantity"
    And the error code is "DT-T003"

  Scenario: Nil warning severity is configurable
    Given the DataTwist configuration has nil-warnings set to "error"
    And the DataTwist source:
      """
      result is users |> map _.address.city
      """
    When it is evaluated and some users have nil address
    Then a hard error is produced instead of a warning
    And the error message is the same as the warning message
    # Configuration allows upgrading warnings to errors for strict mode.

  # ===========================================================================
  # SECTION 5: Data Source / Connection Errors
  # ===========================================================================

  Scenario: Connection error - database connection refused
    Given the DataTwist source:
      """
      db is connect "postgres://localhost/mydb"
      """
    When it is evaluated and the database is not running
    Then a connection error is produced
    And the error message contains "Connection failed"
    And the error shows the resolved connection details:
      """
        postgres://localhost:5432/mydb
        Error: connection refused
      """
    And the hints include:
      | Is PostgreSQL running? Check with: pg_isready          |
      | Check hostname and port                                |
      | Check that the database 'mydb' exists                  |
    And the error code is "DT-C001"

  Scenario: Connection error - authentication failure
    Given the DataTwist source:
      """
      db is connect "postgres://user:wrong@localhost/mydb"
      """
    When it is evaluated and authentication fails
    Then a connection error is produced
    And the error message contains "Authentication failed"
    And the error does NOT echo the password in the output
    And the hints include:
      | Check username and password                            |
      | Verify pg_hba.conf allows this connection              |
    And the error code is "DT-C002"

  Scenario: Connection error - file not found for CSV
    Given the DataTwist source:
      """
      data is read-csv "sales-data.csv"
      """
    When it is evaluated and the file does not exist
    Then a connection error is produced
    And the error message contains "File not found: sales-data.csv"
    And the error shows the resolved absolute path
    And the hints include:
      | Check the file path -- is it relative to the working directory? |
      | Current working directory: /home/user/project                  |
    And the error code is "DT-C003"

  Scenario: Connection error - malformed connection string
    Given the DataTwist source:
      """
      db is connect "not-a-valid-url"
      """
    When it is evaluated
    Then a connection error is produced
    And the error message contains "Invalid connection string"
    And the hint says "Expected format: postgres://user:pass@host:port/database"
    And the error code is "DT-C004"

  Scenario: Connection error - table not found
    Given the DataTwist source:
      """
      db is connect "postgres://localhost/mydb"
      users is db |> table "nonexistent_table"
      """
    When it is evaluated
    Then a connection error is produced
    And the error message contains "Table 'nonexistent_table' not found"
    And the hint says "Available tables: users, orders, products (showing first 10)"
    And the error code is "DT-C005"

  # ===========================================================================
  # SECTION 6: Pattern Match Errors
  # ===========================================================================

  Scenario: Non-exhaustive pattern match warning
    Given the DataTwist source:
      """
      classify is [x ->
        | {type: "a"} -> "alpha"
        | {type: "b"} -> "beta"
      ]
      """
    When it is compiled
    Then a warning is produced
    And the warning message contains "Non-exhaustive pattern match"
    And the warning shows:
      """
        2 branches defined, no default case.
        Will return nil if input does not match any pattern.
      """
    And the hint says "Add a catch-all: | _ -> \"unknown\""
    And the warning code is "DT-R003"

  Scenario: Pattern match failure at runtime
    Given the DataTwist source:
      """
      classify is [x ->
        | {type: "a"} -> "alpha"
        | {type: "b"} -> "beta"
      ]
      result is classify {type: "c"}
      """
    When it is evaluated
    Then the result is nil
    And a warning is emitted at runtime:
      """
        No pattern matched for input: {type: "c"}

        Defined patterns:
          | {type: "a"} -> ...
          | {type: "b"} -> ...

        Hint: Add a catch-all: | _ -> "unknown"
      """
    And the warning code is "DT-R004"

  Scenario: Pattern match with unreachable branch warning
    Given the DataTwist source:
      """
      classify is [x ->
        | _ -> "default"
        | {type: "a"} -> "alpha"
      ]
      """
    When it is compiled
    Then a warning is produced
    And the warning message contains "Unreachable pattern"
    And the warning points to line 3
    And the warning shows:
      """
        3 |   | {type: "a"} -> "alpha"
              ~~~~~~~~~~~~~~~~~~~~~~~~~~
        This branch will never be reached because the catch-all '_' on line 2
        matches everything.
      """
    And the hint says "Move the specific pattern before the catch-all, or remove it"
    And the warning code is "DT-R005"

  # ===========================================================================
  # SECTION 7: Binding / Scope Errors
  # ===========================================================================

  Scenario: Undefined identifier error
    Given the DataTwist source:
      """
      result is users |> filter _.active |> count
      """
    When it is evaluated and "users" is not defined
    Then a runtime error is produced
    And the error message contains "Undefined name: users"
    And the error points to line 1, column 12
    And the error snippet shows:
      """
        1 | result is users |> filter _.active |> count
                      ~~~~~
      """
    And the hint says "Did you forget to define 'users'? Example: users is load-data \"users.csv\""
    And the error code is "DT-R006"

  Scenario: Undefined identifier with similar name suggestion
    Given the DataTwist source:
      """
      username is "Alice"
      result is user-name
      """
    When it is evaluated
    Then a runtime error is produced
    And the error message contains "Undefined name: user-name"
    And the hint says "Did you mean 'username'?"
    And the error code is "DT-R006"

  Scenario: Undefined identifier - typo in function name
    Given the DataTwist source:
      """
      result is users |> filtre _.active
      """
    When it is evaluated
    Then a runtime error is produced
    And the error message contains "Undefined name: filtre"
    And the hint says "Did you mean 'filter'?"
    And the error code is "DT-R006"

  # ===========================================================================
  # SECTION 8: Destructuring Errors
  # ===========================================================================

  Scenario: Destructuring error - not enough values in list
    Given the DataTwist source:
      """
      [a b c] is [1 2]
      """
    When it is evaluated
    Then a runtime error is produced
    And the error message contains "Not enough values to destructure"
    And the error shows:
      """
        1 | [a b c] is [1 2]
             -------    -----
             3 names    2 values
      """
    And the hint says "The list has 2 values but the pattern expects 3"
    And the error code is "DT-R007"

  Scenario: Destructuring error - expected object got non-object
    Given the DataTwist source:
      """
      {name age} is "not an object"
      """
    When it is evaluated
    Then a runtime error is produced
    And the error message contains "Cannot destructure string as object"
    And the hint says "Expected an object with keys 'name' and 'age', got string"
    And the error code is "DT-R008"

  # ===========================================================================
  # SECTION 9: REPL-Specific Error Presentation
  # ===========================================================================

  Scenario: REPL shows error inline with color
    Given the REPL session
    When the user enters "\"hello\" + 5"
    Then the REPL displays the error with:
      | red text for the error header and marker symbols     |
      | white/default text for the source snippet            |
      | cyan underlines pointing to the problem locations    |
      | green text for the hint                              |
    And no Java stack trace is shown

  Scenario: REPL preserves previous valid state after error
    Given the REPL session
    When the user enters "x is 42"
    And the user enters "x + \"hello\""
    Then the REPL displays a type error
    And "x" is still bound to 42
    And the user can enter "x + 8" and get 50

  Scenario: REPL error shows expression context without file path
    Given the REPL session
    When the user enters "1 / 0"
    Then the error output does NOT include a file path
    And the error output shows the expression inline:
      """
        1 / 0
            -
        Division by zero
      """

  Scenario: REPL multi-line input error points to correct line
    Given the REPL session
    When the user enters a multi-line expression:
      """
      users
        |> filter _.active
        |> map _.name +
      """
    Then the error points to line 3 of the input
    And the error message contains "Unexpected end of expression"

  # ===========================================================================
  # SECTION 10: File Execution Error Presentation
  # ===========================================================================

  Scenario: File execution error shows file path and line
    Given the DataTwist file "process.dtw" with source:
      """
      data is read-csv "input.csv"

      result is data
        |> filter _.active
        |> map _.score * 2
        |> sort-by _.score
      """
    When the file is executed and some rows have nil score
    Then the error output includes the file path:
      """
      process.dtw:5:10 -- Type error [DT-T003]
      """
    And the error shows surrounding lines with line numbers

  Scenario: File execution error in imported/required code
    Given the DataTwist file "main.dtw" that uses functions from "helpers.dtw"
    When an error occurs inside a function defined in "helpers.dtw"
    Then the error shows the call chain:
      """
        helpers.dtw:12:5 -- Type error [DT-T001]
          Cannot add string and number

        Called from:
          main.dtw:8:18  result is data |> transform-row
      """
    And no Clojure/Java frames are shown

  # ===========================================================================
  # SECTION 11: Error Recovery Suggestions
  # ===========================================================================

  Scenario: Suggestion - common operator confusion (= vs is)
    Given the DataTwist source:
      """
      x = 42
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use 'is' for assignment: x is 42. '=' is the equality operator."
    And the error code is "DT-P016"

  Scenario: Suggestion - using comma as separator
    Given the DataTwist source:
      """
      items is [1, 2, 3]
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "DataTwist uses spaces, not commas, to separate list items: [1 2 3]"
    And the error code is "DT-P017"

  Scenario: Suggestion - using comma in object literal
    Given the DataTwist source:
      """
      user is {name: "Alice", age: 25}
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "DataTwist uses spaces, not commas, between object fields: {name: \"Alice\" age: 25}"
    And the error code is "DT-P017"

  Scenario: Suggestion - using := for assignment
    Given the DataTwist source:
      """
      x := 42
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use 'is' for assignment: x is 42"
    And the error code is "DT-P016"

  Scenario: Suggestion - using => instead of ->
    Given the DataTwist source:
      """
      double is [x => x * 2]
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use '->' for function arrows, not '=>': [x -> x * 2]"
    And the error code is "DT-P018"

  Scenario: Suggestion - using && or || for logical operators
    Given the DataTwist source:
      """
      result is x > 5 && y < 10
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use 'and' instead of '&&': x > 5 and y < 10"
    And the error code is "DT-P019"

  Scenario: Suggestion - using ! for not
    Given the DataTwist source:
      """
      result is !active
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use 'not' instead of '!': not active"
    And the error code is "DT-P019"

  Scenario: Suggestion - using . pipe instead of |>
    Given the DataTwist source:
      """
      result is users.filter(_.active).count()
      """
    When it is parsed
    Then a parse error is produced
    And the hint says "Use the pipe operator for chaining: users |> filter _.active |> count"
    And the error code is "DT-P020"

  # ===========================================================================
  # SECTION 12: Warnings vs Errors Distinction
  # ===========================================================================

  Scenario: Warnings do not halt execution
    Given the DataTwist source:
      """
      classify is [x ->
        | {type: "a"} -> "alpha"
        | {type: "b"} -> "beta"
      ]
      result is classify {type: "a"}
      """
    When it is evaluated
    Then a warning about non-exhaustive pattern match is emitted during compilation
    But execution continues
    And the result is "alpha"

  Scenario: Errors halt execution
    Given the DataTwist source:
      """
      x is "hello" + 5
      y is x + 1
      """
    When it is evaluated
    Then a type error is produced on line 1
    And line 2 is never evaluated

  Scenario: Multiple warnings are collected and shown together
    Given the DataTwist source with multiple potential issues:
      """
      f is [x -> | x > 0 -> "positive"]
      g is [x -> | x = "a" -> 1]
      """
    When it is compiled
    Then two warnings about non-exhaustive patterns are collected
    And both are shown, ordered by line number

  Scenario: Warning count summary in file mode
    Given the DataTwist file "analysis.dtw" that produces 3 warnings
    When the file is executed
    Then after the output, a summary is shown:
      """
      3 warnings generated. Run with --strict to treat warnings as errors.
      """

  # ===========================================================================
  # SECTION 13: Error Codes and Documentation
  # ===========================================================================

  Scenario: Every error has a unique code
    Given any DataTwist error message
    Then it includes an error code in the format "DT-XNNN"
    Where X is a category letter:
      | P | Parse errors (syntax)              |
      | T | Type errors                        |
      | D | Data errors (nil, schema)          |
      | C | Connection/data source errors      |
      | R | Runtime errors (pipeline, scope)   |
    And NNN is a three-digit number

  Scenario: Error code links to documentation
    Given a DataTwist error with code "DT-T001"
    Then the error footer includes:
      """
      See: https://datatwist.dev/errors/DT-T001
      """

  # ===========================================================================
  # SECTION 14: Stack Trace Translation
  # ===========================================================================

  Scenario: Java ClassCastException is translated to DataTwist type error
    Given the DataTwist source:
      """
      result is "hello" + 5
      """
    When the underlying Clojure code throws a ClassCastException
    Then the error is caught and translated
    And the user sees a DataTwist type error, not a Java exception
    And the word "ClassCastException" does NOT appear in the output

  Scenario: Java NullPointerException is translated to nil error
    Given the DataTwist source triggers a NullPointerException in compiled Clojure
    Then the error is caught and translated to a DataTwist nil error
    And the word "NullPointerException" does NOT appear in the output
    And the error points to the DataTwist source line, not the Clojure code

  Scenario: Clojure ArithmeticException is translated
    Given the DataTwist source:
      """
      result is 10 / 0
      """
    When the underlying Clojure throws an ArithmeticException
    Then the user sees "Division by zero" with source location
    And the word "ArithmeticException" does NOT appear in the output

  Scenario: Clojure IllegalArgumentException is translated
    Given the DataTwist source triggers an IllegalArgumentException
    Then the error is translated to a descriptive DataTwist runtime error
    And the original Clojure exception message is extracted and reformulated

  Scenario: Unknown exceptions fall back to generic error with context
    Given the DataTwist source triggers an unexpected exception type
    Then the error shows:
      """
        Internal error at <source location>

        <translated message from the exception>

        This may be a bug in DataTwist. Please report it.
        Debug info: <exception class and message for bug reports>
      """
    And no stack trace is shown by default
    And the user can run with --debug flag to see the full stack trace

  # ===========================================================================
  # SECTION 15: Data-Aware Error Reporting
  # ===========================================================================

  Scenario: Sampling-based nil detection in pipeline
    Given a pipeline that processes 10000 rows
    When the runtime samples approximately 100 rows for diagnostics
    And 5 sampled rows produce nil in a map step
    Then a warning is produced
    And the warning estimates: "approximately 500 of 10000 rows may have nil values (5% sampled)"
    And the warning shows 3 sample rows (not all 5)

  Scenario: Schema mismatch detection in pipeline
    Given the DataTwist source:
      """
      result is users |> map {name: _.name email: _.email}
      """
    When some user records do not have an "email" field
    Then a warning is produced
    And the warning says "Missing field 'email' in some rows"
    And the warning quantifies: "12 of 100 sampled rows had nil for _.email"
    And the hint says "Use default: _.email ?? \"no-email\""
    And the warning code is "DT-D004"

  Scenario: Type heterogeneity warning in collection
    Given the DataTwist source:
      """
      result is data |> map _.score |> sum
      """
    When some scores are strings and some are numbers
    Then a type error is produced when sum encounters a string
    And the error says "Cannot sum: expected all numbers but found string at row 15"
    And the error shows the offending value:
      """
        Row 15: _.score = "N/A" (string)
      """
    And the hint says "Filter or convert non-numeric values first: data |> filter (number? _.score) |> map _.score |> sum"
    And the error code is "DT-T006"

  # ===========================================================================
  # SECTION 16: Try-Catch Interaction with Error Reporting
  # ===========================================================================

  Scenario: Caught errors do not produce user-visible error output
    Given the DataTwist source:
      """
      result is try
        10 / 0
      catch err -> "division failed"
      """
    When it is evaluated
    Then no error is shown to the user
    And the result is "division failed"

  Scenario: Uncaught errors inside try still show friendly messages
    Given the DataTwist source:
      """
      result is try
        "hello" + 5
      catch java.io.IOException err -> "io error"
      """
    When it is evaluated and the type error is not an IOException
    Then a friendly type error is shown (not a raw Java exception)
    And the error points to line 2

  Scenario: Error in catch handler is reported with context
    Given the DataTwist source:
      """
      result is try
        risky-operation data
      catch err ->
        err.message + 42
      """
    When the catch handler itself throws a type error
    Then the error output shows:
      """
        Error in catch handler at line 4

        4 |   err.message + 42
              -----------   --
              string         number

        The original error was caught, but the handler also failed.
      """

  # ===========================================================================
  # SECTION 17: Multi-Line Error Context
  # ===========================================================================

  Scenario: Error in multi-line pipeline shows surrounding lines
    Given the DataTwist file "report.dtw" with source:
      """
      require next.jdbc as jdbc

      db is connect "postgres://localhost/analytics"

      report is db
        |> table "events"
        |> filter _.timestamp > "2024-01-01"
        |> group-by _.category
        |> map {category: _.key count: _.value |> count total: _.value |> map _.amount |> sum}
        |> sort-by _.count
      """
    When an error occurs on line 9
    Then the error shows lines 7 through 10 (at least 2 lines before and 1 after)
    And the error-producing line is highlighted

  Scenario: Error context does not show more than 5 surrounding lines
    Given a DataTwist file with 200 lines
    When an error occurs on line 100
    Then the error shows at most 5 context lines (e.g., lines 97-102)
    And line 100 is marked as the error line

  Scenario: Error at line 1 does not try to show negative line numbers
    Given the DataTwist source:
      """
      x is "broken
      """
    When it is parsed
    Then the error context starts at line 1
    And no lines with negative or zero numbers are shown

  # ===========================================================================
  # SECTION 18: Compile-Time vs Runtime Error Classification
  # ===========================================================================

  Scenario: Parse errors are compile-time
    Given the DataTwist source has a syntax error
    Then the error is detected before any code is evaluated
    And the error is classified as "compile-time"

  Scenario: Non-exhaustive pattern warnings are compile-time
    Given the DataTwist source has a pattern match without default
    Then the warning is emitted during compilation
    And no code is evaluated to produce this warning

  Scenario: Unreachable branch warnings are compile-time
    Given the DataTwist source has a catch-all before specific patterns
    Then the warning is emitted during compilation

  Scenario: Type errors are runtime
    Given the DataTwist source "\"hello\" + 5"
    Then the error is detected during evaluation
    And it is classified as "runtime"
    # NOTE: Static type checking is not in scope for v1.
    # DataTwist is dynamically typed (like Clojure).

  Scenario: Nil data warnings are runtime
    Given a pipeline that encounters nil values
    Then the warning is emitted during evaluation
    And it is classified as "runtime"

  Scenario: Connection errors are runtime
    Given a connect expression for a database
    Then the error is detected when the connection is attempted
    And it is classified as "runtime"

  # ===========================================================================
  # SECTION 19: Debug Mode
  # ===========================================================================

  Scenario: Debug flag shows additional information
    Given the DataTwist source triggers a runtime error
    When execution is run with the --debug flag
    Then the error output includes the full Clojure exception chain
    And the compiled Clojure code snippet is shown
    And the Clojure stack trace is included after the friendly error
    And the additional info is clearly separated:
      """
      --- Debug info (--debug) ---
      Compiled Clojure: (+ "hello" 5)
      Exception: java.lang.ClassCastException: ...
      Stack trace:
        ...
      """

  Scenario: Without debug flag, no internal details are shown
    Given the DataTwist source triggers a runtime error
    When execution is run without the --debug flag
    Then no Clojure code is shown
    And no Java class names are shown
    And no stack traces are shown


# =============================================================================
# OPEN QUESTIONS
# =============================================================================
#
# Q1: Error recovery in parser -- how many errors to report at once?
#     Instaparse reports a single failure (the farthest match). To report
#     multiple errors, we would need a recovery strategy (skip to next
#     newline, try again). This is complex.
#     RECOMMENDATION: Start with single error per parse attempt. In REPL,
#     this is fine (one expression at a time). For files, the user fixes
#     one error, re-runs, sees the next. Multi-error reporting is a v2 goal.
#
# Q2: How deep should similar-name suggestions go?
#     "Did you mean 'filter'?" when user typed "filtre" -- this requires
#     a dictionary of known names (built-ins + user-defined bindings) and
#     a string distance algorithm (Levenshtein, Jaro-Winkler).
#     RECOMMENDATION: Use Levenshtein distance <= 2 for suggestions.
#     Include both built-in function names and user-defined bindings in
#     the search space.
#
# Q3: Should warnings be suppressible per-line?
#     Like `// @suppress DT-D001` or `// noinspection nil-warning`.
#     RECOMMENDATION: Defer to v2. For v1, warnings are always shown.
#     The --strict flag upgrades them to errors. No per-line suppression.
#
# Q4: How to track source locations through Clojure compilation?
#     DataTwist source is parsed to AST with locations, then compiled to
#     Clojure forms. We need to attach source metadata to Clojure forms
#     so that runtime exceptions can be mapped back.
#     APPROACH: Use Clojure metadata on forms:
#       (with-meta (+ a b) {:dt/line 3 :dt/col 12 :dt/file "script.dtw"})
#     At runtime, catch exceptions, walk the call stack, find our metadata.
#     ALTERNATIVE: Maintain a source map (compiled position -> source position).
#     RECOMMENDATION: Both. Metadata on forms for simple cases, source map
#     for complex cases where metadata is lost (e.g., macro expansion).
#
# Q5: Sampling rate for data-aware warnings -- configurable?
#     Default: sample 100 rows from collections > 100 elements.
#     For collections <= 100: check all.
#     Should users be able to tune this? `--sample-size 500`?
#     RECOMMENDATION: Yes, via config. Default 100 is good. Expose
#     --sample-size flag for power users. REPL always uses default.
#
# Q6: Should we detect infinite loops / long-running pipelines?
#     A pipeline on a huge lazy seq that never terminates.
#     RECOMMENDATION: Not for v1. The REPL uses sampling (micro-sample
#     of ~100 elements) which naturally avoids this. For file execution,
#     users can ctrl-C. Timeout detection is a v2 feature.
#
# Q7: Error internationalization (i18n)?
#     All error messages in English for v1. I18n is a v2+ concern.
#     Error codes make i18n easier (translate by code).
#     RECOMMENDATION: Defer. English only for v1.
#
# Q8: Should errors suggest imports?
#     If user calls `str/upper-case` without `require clojure.string as str`,
#     should the error say "Did you forget: require clojure.string as str"?
#     RECOMMENDATION: Yes, for known Clojure namespaces. Maintain a lookup
#     table of common aliases (str -> clojure.string, set -> clojure.set, etc.)
#
# Q9: How to handle errors in lazy sequences?
#     `data |> map [x -> x / 0]` -- the error occurs lazily when elements
#     are realized, not when the pipeline is defined.
#     RECOMMENDATION: When an element fails during lazy realization, catch
#     the error, attach the element index and value, and report it with
#     context: "Error at row N while evaluating map step."
#     For REPL (which samples), errors in sampling are caught and reported
#     with the sample context.
#
# Q10: Color output -- should it be auto-detected or opt-in?
#     RECOMMENDATION: Auto-detect. If stdout is a TTY, use ANSI colors.
#     If piped/redirected, use plain text. Provide --color=always/never/auto
#     flag. Default: auto.
#
# =============================================================================
# CLOJURE IMPLEMENTATION NOTES
# =============================================================================
#
# 1. INSTAPARSE FAILURE TRANSFORMATION
#    Instaparse returns failure objects with :index, :line, :column,
#    :text, :reason (expected set). Transform these into friendly messages:
#
#    (defn friendly-parse-error [failure source]
#      (let [{:keys [line column text reason]} (insta/get-failure failure)]
#        ;; Map Instaparse expected tokens to human-readable descriptions
#        ;; e.g., :number -> "a number", :string -> "a string literal"
#        ;; Build snippet with underline at column position
#        ;; Add contextual hints based on what was expected vs what was found
#        ))
#
# 2. RUNTIME EXCEPTION CATCHING
#    Wrap all compiled Clojure evaluation in a try-catch that catches
#    Throwable, inspects the exception type, and maps to DT error:
#
#    Exception Type               -> DT Error
#    ClassCastException           -> DT-T001 (type mismatch)
#    NullPointerException         -> DT-T003 (nil in operation)
#    ArithmeticException          -> DT-T005 (division by zero)
#    IllegalArgumentException     -> DT-R002 (wrong arguments)
#    IndexOutOfBoundsException    -> DT-R009 (index out of range)
#    StackOverflowError           -> DT-R010 (infinite recursion)
#    java.io.FileNotFoundException -> DT-C003 (file not found)
#    java.sql.SQLException        -> DT-C001/C002 (database error)
#    clojure.lang.ExceptionInfo   -> inspect ex-data for structured errors
#
# 3. SOURCE LOCATION TRACKING
#    During AST -> Clojure compilation, attach metadata to every form:
#
#    (defn emit-with-meta [clj-form ast-node]
#      (with-meta clj-form
#        {:dt/line (:line ast-node)
#         :dt/col  (:col ast-node)
#         :dt/file *current-file*}))
#
#    At runtime, when catching exceptions:
#    a) Check if the thrown form has :dt/* metadata
#    b) If not, walk the Clojure stack trace, find frames matching
#       our generated namespace, and look up source positions from
#       the source map
#
# 4. SOURCE MAP DATA STRUCTURE
#    Maintained during compilation. Maps Clojure code positions to
#    DataTwist source positions:
#
#    {:clj-ns "datatwist.user.script"
#     :mappings [{:clj-line 5 :dt-line 3 :dt-col 12 :dt-file "script.dtw"}
#                {:clj-line 6 :dt-line 3 :dt-col 25 :dt-file "script.dtw"}
#                ...]}
#
# 5. NIL SAMPLING IMPLEMENTATION
#    For data-aware warnings, inject sampling checks at pipeline steps:
#
#    (defn sampled-map [coll f]
#      (let [sample (take 100 coll)
#            results (mapv f sample)
#            nil-count (count (filter nil? results))]
#        (when (pos? nil-count)
#          (emit-warning! :DT-D001
#            {:nil-count nil-count
#             :sample-size (count sample)
#             :nil-rows (take 3 (keep-indexed
#                         (fn [i r] (when (nil? r) {:index i :input (nth sample i)}))
#                         results))}))
#        (map f coll)))  ;; return the actual lazy operation
#
# 6. FRIENDLY NAME SUGGESTION (Levenshtein)
#    Use clojure.core or a small utility for edit distance:
#
#    (defn suggest-name [unknown-name known-names]
#      (->> known-names
#           (map (fn [name] {:name name :dist (levenshtein unknown-name name)}))
#           (filter #(<= (:dist %) 2))
#           (sort-by :dist)
#           first
#           :name))
#
# 7. ERROR FORMATTING
#    Central formatting function that produces the error string:
#
#    (defn format-error [{:keys [code severity message file line col
#                                snippet underlines hints]}]
#      (str (when file (str file ":" line ":" col " -- "))
#           (case severity :error "Error" :warning "Warning")
#           " [" code "]\n\n"
#           "  " message "\n\n"
#           (format-snippet snippet line underlines) "\n\n"
#           (format-hints hints) "\n\n"
#           "See: https://datatwist.dev/errors/" code))
#
# 8. ANSI COLOR SUPPORT
#    (def colors
#      {:error   "\033[31m"  ;; red
#       :warning "\033[33m"  ;; yellow
#       :hint    "\033[32m"  ;; green
#       :code    "\033[36m"  ;; cyan (underlines)
#       :reset   "\033[0m"})
#
#    Wrap formatting in color codes when (System/console) is non-nil
#    or when --color=always is specified.
#
# 9. PIPELINE STEP TRACKING
#    Each pipeline step should carry metadata about its position in the
#    pipeline. When a step fails, the runtime can report which step
#    number and which function failed:
#
#    (defn pipeline-step [step-num step-name f]
#      (fn [data]
#        (try (f data)
#          (catch Exception e
#            (throw (ex-info "Pipeline step failed"
#                     {:dt/step step-num
#                      :dt/step-name step-name
#                      :dt/input-type (type data)
#                      :dt/input-value (if (coll? data) (str "<" (type data) " of " (count data) ">") data)}
#                     e))))))
#
# 10. COMMON MISTAKE LOOKUP TABLE
#     A static map from detected patterns to error codes and hints:
#
#     {"," in list/object  -> DT-P017 "Use spaces, not commas"
#      "=" at start of line -> DT-P016 "Use 'is' for assignment"
#      ":=" anywhere       -> DT-P016 "Use 'is' for assignment"
#      "=>" in lambda      -> DT-P018 "Use '->' not '=>'"
#      "&&" or "||"        -> DT-P019 "Use 'and'/'or'"
#      "!" prefix          -> DT-P019 "Use 'not'"
#      ".method().method()" -> DT-P020 "Use |> for chaining"}
#
# =============================================================================
# CORNER CASES
# =============================================================================
#
# C1: Error inside a deeply nested pipeline (3+ levels of nesting)
#     Show the full nesting path:
#       main pipeline, step 2 (map) > nested pipeline, step 1 (filter) > error
#     Keep it flat, do not show a tree.
#
# C2: Error in a lambda passed to map that processes 10000 rows --
#     where does the error point?
#     Answer: It points to the lambda definition in source code, plus
#     annotates with the row index and value that triggered the failure.
#     "Error at row 7523 while evaluating: [x -> x.price * x.qty]"
#
# C3: Error message for very long expressions -- truncation
#     If a source line is > 120 characters, truncate with "..." in the
#     snippet display. Show enough context around the error column.
#     Example: "...filter _.very-long-field-name > some-really-long-expression..."
#
# C4: Unicode in error messages and source snippets
#     Column positions must account for multi-byte characters.
#     The underline (^^^) must align correctly with the problematic text
#     even when the line contains Unicode characters.
#     Use character count, not byte count, for column positions.
#
# C5: Error in eval/REPL with previously defined bindings
#     The error should show the current expression being evaluated,
#     not any previously successful expressions. But it should reference
#     previously defined bindings in hints ("'users' was defined on line 3
#     as type list<object>").
#
# C6: Multiple errors in a single pipeline step
#     For example, `map _.price * _.qty` where row 5 has nil price
#     and row 8 has string qty. After the first error, should we continue
#     to find more? RECOMMENDATION: No, stop at first error. Data sampling
#     happens before the full run (to generate warnings), but once a hard
#     error occurs, halt. The user fixes one problem at a time.
#
# C7: Error in a try-catch handler that itself has a try-catch
#     Nested error handling should work naturally. The inner try-catch
#     catches its own errors. If the outer catch handler fails, that
#     error propagates up and is reported at the outer level.
#
# C8: Error pointing to generated/synthetic code
#     When _ is desugared to (fn [x] ...), the source location should
#     point to the _ in the original source, not to the generated lambda.
#     The source map must track this desugaring.
#
# C9: Very large error context (object with 50 fields printed in error)
#     When showing "Failing row: {field1: ... field50: ...}", truncate
#     the display to show only relevant fields (the ones referenced in
#     the failing expression) plus a count of omitted fields.
#     Example: "Failing row: {price: nil quantity: 5 ...and 48 more fields}"
#
# C10: Concurrent/parallel pipeline errors
#      If DataTwist supports parallel processing (pmap), errors from
#      multiple threads need to be collected and reported coherently.
#      RECOMMENDATION: Defer parallel execution to v2. For v1, all
#      execution is sequential. No concurrent error concerns.
#
# C11: Error during require/import at startup
#      "require unknown.namespace as ns" -- FileNotFoundException from
#      Clojure should be translated to:
#        "Namespace 'unknown.namespace' not found"
#        Hint: "Check the namespace name. Is the library on the classpath?"
#
# C12: Stack overflow from infinite recursion
#      Detect StackOverflowError and translate to:
#        "Maximum recursion depth exceeded"
#        Hint: "Your function may be calling itself infinitely.
#               Check the base case of your recursion."
#      Show the recursive function name if possible.
#
# C13: Out of memory errors
#      Detect OutOfMemoryError and translate to:
#        "Out of memory"
#        Hint: "The dataset may be too large to fit in memory.
#               Use lazy pipelines or process in chunks.
#               Current JVM heap: 512MB. Increase with -Xmx flag."
#
# C14: Error during lazy evaluation far from original definition
#      `data is huge-file |> map [x -> x / 0]`
#      `data |> take 5`  ;; error happens HERE, not at definition
#      The error should point to the map lambda (line 1) even though
#      it was triggered by the take call (line 2). Show both:
#        "Error in lazy pipeline defined at line 1, triggered at line 2"
#
# C15: REPL history -- can we show "this error was similar to your
#      last error"? Not for v1, but an interesting DX feature for v2.
