Feature: Error Reporting
  DataTwist targets data analysts, not systems programmers. Errors must be
  maximally informative: no Java/Clojure stack traces, immediate pointer to the
  exact source location, and an immediate suggestion of what to fix.

  Philosophy (from PRD section 9):
    - Elm/Rust-style: "here is what went wrong, here is where, here is how to fix it"
    - Every error includes: source location, code snippet with underline,
      human-readable explanation, and a contextual hint
    - Warnings are distinct from errors: warnings do not halt execution
    - No raw Java exceptions, ClassCastException, NullPointerException, or
      Clojure stack traces are ever shown to the user
    - Data-aware errors: quantified ("3 of 100 rows had nil"), not abstract
    - Error codes: DT-PXXX (parse), DT-TXXX (type), DT-RXXX (runtime),
      DT-DXXX (data), DT-CXXX (connection) for documentation lookup

  Background:
    Given the DataTwist evaluator is available

  # ===========================================================================
  # SECTION 1: Parse Errors — detected at parse time (before evaluation)
  # Source: PRD section 9, "Elm/Rust-style errors"
  # ===========================================================================

  Scenario: Parse error - unexpected end of expression after operator
    Given the DataTwist source "users |> filter _.age >"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message contains "Unexpected end of expression"
    And the error snippet shows a pointer (^^^) at the end of the line
    And the hint suggests a value after '>'
    And no Java or Clojure exception names appear in the error output

  Scenario: Parse error - unclosed string literal
    Given the DataTwist source "name is \"Alice"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the unclosed string

  Scenario: Parse error - unclosed object literal
    Given the DataTwist source "user is {name: \"Alice\" age: 25"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the unclosed '{'

  Scenario: Parse error - unclosed list literal
    Given the DataTwist source "items is [1 2 3"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the unclosed '['

  Scenario: Parse error - missing arrow in guard branch
    Given the DataTwist source:
      """
      tier is
        | amount > 1000 "gold"
        | _ -> "bronze"
      """
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the missing '->'
    And the hint shows the correct syntax with '->'

  Scenario: Parse error - missing expression after pipe operator
    Given the DataTwist source "data |>"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the missing expression after '|>'
    And the hint shows an example like "data |> count"

  Scenario: Parse error - double pipe operators
    Given the DataTwist source "data |> |> count"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the unexpected '|>'

  Scenario: Parse error - lambda missing arrow
    Given the DataTwist source "double is [x x * 2]"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the error message mentions the missing '->' in the function definition
    And the hint shows the correct syntax: [x -> x * 2]

  # ===========================================================================
  # SECTION 2: Common Mistake Detection (Parse-time)
  # Source: PRD section 9, "Common mistake detection"
  # ===========================================================================

  Scenario: Common mistake - using = for assignment instead of is
    Given the DataTwist source "x = 42"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says to use 'is' for assignment: x is 42

  Scenario: Common mistake - using := for assignment
    Given the DataTwist source "x := 42"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says to use 'is' for assignment

  Scenario: Common mistake - using => instead of -> in lambda
    Given the DataTwist source "double is [x => x * 2]"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says to use '->' not '=>': [x -> x * 2]

  Scenario: Common mistake - using && for logical and
    Given the DataTwist source "result is x > 5 && y < 10"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says to use 'and' instead of '&&'

  Scenario: Common mistake - using ! for logical not
    Given the DataTwist source "result is !active"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says to use 'not' instead of '!'

  Scenario: Common mistake - using comma as list separator
    Given the DataTwist source "items is [1, 2, 3]"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says DataTwist uses spaces not commas to separate list items: [1 2 3]

  Scenario: Common mistake - using comma as object field separator
    Given the DataTwist source "user is {name: \"Alice\", age: 25}"
    When it is parsed
    Then a parse error is produced
    And the error code starts with "DT-P"
    And the hint says DataTwist uses spaces not commas between object fields

  # ===========================================================================
  # SECTION 3: Error Message Format
  # Source: PRD section 9, Elm/Rust-style format with snippet and underline
  # ===========================================================================

  Scenario: Error output includes a source snippet with an underline pointer
    Given the DataTwist source "users |> filter _.age >"
    When it is parsed
    Then a parse error is produced
    And the error output contains the source line
    And the error output contains a pointer character (^ or ~) under the error position
    And the error output contains a hint

  Scenario: Error output does not contain Java class names
    Given the DataTwist source "\"hello\" + 5"
    When it is evaluated
    Then a type error is produced
    And the error output does not contain "ClassCastException"
    And the error output does not contain "java."
    And the error output does not contain "clojure."

  Scenario: Error output does not contain a Java stack trace
    Given the DataTwist source "10 / 0"
    When it is evaluated
    Then an error is produced
    And the error output does not contain "at java."
    And the error output does not contain "at clojure."

  Scenario: Error code is in DT-XNNN format
    Given any DataTwist error
    Then the error includes a code matching the pattern DT-P, DT-T, DT-R, DT-D, or DT-C
    And the code is followed by a three-digit number

  # ===========================================================================
  # SECTION 4: Type Errors (Runtime)
  # Source: PRD section 9, error codes DT-TXXX
  # ===========================================================================

  Scenario: Type error - adding string and number
    Given the DataTwist source "result is \"hello\" + 5"
    When it is evaluated
    Then a type error is produced
    And the error code starts with "DT-T"
    And the error message mentions string and number type mismatch
    And the error output does not contain "ClassCastException"

  Scenario: Type error - adding boolean and number
    Given the DataTwist source "x is true + 1"
    When it is evaluated
    Then a type error is produced
    And the error code starts with "DT-T"
    And the error message mentions boolean and number type mismatch

  Scenario: Type error - ordering comparison between incompatible types
    Given the DataTwist source "result is \"hello\" > 5"
    When it is evaluated
    Then a type error is produced
    And the error code starts with "DT-T"
    And the error message mentions the incompatible types for comparison

  Scenario: Type error - division by zero
    Given the DataTwist source "result is 10 / 0"
    When it is evaluated
    Then an error is produced
    And the error code starts with "DT-T"
    And the error message contains "Division by zero"
    And the error output does not contain "ArithmeticException"

  Scenario: Type error - nil in arithmetic where coercion does not apply
    Given the DataTwist source:
      """
      x is 5
      y is nil
      result is x + y
      """
    When it is evaluated
    Then the result is 5
    # PRD: "nil + 5" = 5 (nil coerces to identity element 0 for numbers)
    # So x + nil = 5, no error. If nil coercion is not implemented as a type error,
    # this documents the nil coercion behaviour instead.

  # ===========================================================================
  # SECTION 5: Runtime Errors — undefined names, destructuring
  # Source: PRD section 9, error codes DT-RXXX
  # ===========================================================================

  Scenario: Runtime error - undefined identifier
    Given the DataTwist source "result is users |> filter _.active |> count"
    When it is evaluated and "users" is not defined
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message contains "Undefined" and "users"

  Scenario: Runtime error - undefined identifier with similar name suggestion
    Given the DataTwist source:
      """
      username is "Alice"
      result is user-name
      """
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message contains "Undefined" and "user-name"
    And the hint suggests the similarly-named identifier "username"

  Scenario: Runtime error - undefined function name with typo
    Given the DataTwist source "result is users |> filtre _.active"
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message contains "Undefined" and "filtre"
    And the hint suggests "filter"

  Scenario: Runtime error - pipeline function applied to wrong type
    Given the DataTwist source "result is 42 |> filter _.active"
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message mentions that filter expects a collection

  Scenario: Runtime error - map over non-collection
    Given the DataTwist source "result is \"hello\" |> map _.name"
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message mentions that map expects a collection

  Scenario: Runtime error - list destructuring with not enough values
    Given the DataTwist source "[a b c] is [1 2]"
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message mentions the mismatch between pattern size and value count

  Scenario: Runtime error - object destructuring of non-object
    Given the DataTwist source "{name age} is \"not an object\""
    When it is evaluated
    Then a runtime error is produced
    And the error code starts with "DT-R"
    And the error message mentions that a string cannot be destructured as an object

  # ===========================================================================
  # SECTION 6: Data-Aware Warnings (nil prevalence)
  # Source: PRD section 9, "Data-aware warnings (unique to DataTwist)"
  # ===========================================================================

  Scenario: Data warning - nil values detected in pipeline map step
    Given the DataTwist source "result is users |> map _.address.city"
    When it is evaluated and some users have nil address
    Then a warning is produced (execution continues)
    And the warning code starts with "DT-D"
    And the warning mentions nil values were detected
    And the warning quantifies the nil count (e.g., "3 of 100 sampled rows")
    And the hint suggests filtering nils: users |> filter _.address != nil |> map _.address.city
    And an alternative hint suggests nil coalescing: _.address.city ?? "unknown"

  Scenario: Data warning - execution continues after nil warning
    Given the DataTwist source "result is users |> map _.address.city"
    When it is evaluated and some users have nil address
    Then a warning is produced
    But the pipeline still returns results (nil warning does not halt execution)

  Scenario: Data warning - nil in sort-by key
    Given the DataTwist source "result is users |> sort-by _.age"
    When it is evaluated and some users have nil age
    Then a warning is produced
    And the warning code starts with "DT-D"
    And the warning mentions nil sort keys

  Scenario: Data warning - nil in group-by key
    Given the DataTwist source "result is orders |> group-by _.region"
    When it is evaluated and some orders have nil region
    Then a warning is produced
    And the warning code starts with "DT-D"
    And the warning mentions nil group keys

  # ===========================================================================
  # SECTION 7: Java/Clojure Exception Translation
  # Source: PRD section 9, "No Java/Clojure stack traces"
  # ===========================================================================

  Scenario: ClassCastException is translated to a DataTwist type error
    Given the DataTwist source "result is \"hello\" + 5"
    When the underlying Clojure code throws a ClassCastException
    Then the user sees a DataTwist type error message
    And the word "ClassCastException" does NOT appear in the output
    And the error code starts with "DT-T"

  Scenario: ArithmeticException is translated to a DataTwist error
    Given the DataTwist source "result is 10 / 0"
    When the underlying Clojure throws an ArithmeticException
    Then the user sees "Division by zero" with a source location
    And the word "ArithmeticException" does NOT appear in the output

  Scenario: NullPointerException is translated to a DataTwist nil error
    Given the DataTwist source triggers a NullPointerException
    When it is evaluated
    Then the error is caught and translated
    And the word "NullPointerException" does NOT appear in the output

  # ===========================================================================
  # SECTION 8: Connection / Data Source Errors
  # Source: PRD section 9, error codes DT-CXXX; PRD section 8 (data sources)
  # ===========================================================================

  Scenario: Connection error - file not found for CSV
    Given the DataTwist source "data is read-csv \"nonexistent-file.csv\""
    When it is evaluated and the file does not exist
    Then a connection error is produced
    And the error code starts with "DT-C"
    And the error message mentions the file was not found
    And no Java FileNotFoundException message appears in the output

  Scenario: Connection error - database connection failure
    Given the DataTwist source "db is connect \"postgres://localhost/mydb\""
    When it is evaluated and the database is not running
    Then a connection error is produced
    And the error code starts with "DT-C"
    And the error message contains "Connection failed" or "connection refused"
    And no raw Java SQL exception appears in the output

  # ===========================================================================
  # SECTION 9: Errors vs Warnings Distinction
  # Source: PRD section 9
  # ===========================================================================

  Scenario: Errors halt execution
    Given the DataTwist source:
      """
      x is "hello" + 5
      y is x + 1
      """
    When it is evaluated
    Then a type error is produced on the first line
    And the second line is never evaluated (execution halted)

  Scenario: Warnings do not halt execution
    Given the DataTwist source "result is users |> map _.address.city"
    When it is evaluated and some users have nil address
    Then a warning is produced
    And execution continues and a result is returned
