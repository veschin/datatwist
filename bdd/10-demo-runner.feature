Feature: Demo Runner
  The Demo Runner is a file-based evaluation system for DataTwist.
  It reads `.dt` files from `resources/examples/`, parses them into
  sections and expressions, evaluates each expression in order, and
  produces formatted terminal output.

  Design decisions:
    - `.dt` files are the single source of truth for demo content
    - Hardcoded demo data is removed from `demo_runner.clj`
    - Section markers use `// @section <Title>` comment annotations
    - Expected-result annotations use `// @expect <value>` on the line before an expression
    - Evaluation is expression-by-expression, sharing a single context (bindings carry across lines)
    - `// @expect` failures are reported as assertion errors, not crashes
    - Expressions that throw are displayed with an error marker; the runner continues

  # ===========================================================================
  # SECTION 1: File Loading
  # ===========================================================================

  Scenario: Load a .dt file that exists in resources/examples/
    Given the demo file "resources/examples/demo-basics.dt" exists
    When the demo runner loads the file
    Then it reads the raw text content of the file without error

  Scenario: Load a .dt file and return a non-empty string
    Given the demo file "resources/examples/demo-basics.dt" contains at least one line
    When the demo runner loads the file
    Then the returned content is a non-empty string

  Scenario: Attempt to load a file that does not exist
    Given no file exists at path "resources/examples/nonexistent.dt"
    When the demo runner attempts to load the file
    Then it returns a file-not-found error or throws with a clear message

  # ===========================================================================
  # SECTION 2: Parsing — Section Markers
  # ===========================================================================

  Scenario: A file with no section markers produces one implicit section
    Given a .dt file containing only expressions and no "// @section" comments
    When the demo runner parses the file into sections
    Then it returns exactly one section
    And that section has no title (or the default title "Demo")

  Scenario: A single section marker splits the file into one named section
    Given a .dt file containing:
      """
      // @section Basics
      42
      """
    When the demo runner parses the file
    Then it returns one section with title "Basics"
    And that section contains the expression "42"

  Scenario: Multiple section markers produce multiple named sections
    Given a .dt file containing:
      """
      // @section Literals
      1 + 1
      // @section Pipelines
      [1 2 3] |> count
      """
    When the demo runner parses the file
    Then it returns two sections
    And the first section is titled "Literals"
    And the second section is titled "Pipelines"

  Scenario: Expressions before the first section marker belong to a default section
    Given a .dt file containing:
      """
      2 + 2
      // @section Pipelines
      [1 2 3] |> count
      """
    When the demo runner parses the file
    Then the expression "2 + 2" belongs to a default (untitled) section
    And "Pipelines" is a separate section

  # ===========================================================================
  # SECTION 3: Parsing — Expression Extraction
  # ===========================================================================

  Scenario: Blank lines are ignored and not treated as expressions
    Given a .dt file with blank lines between expressions
    When the demo runner extracts expressions from a section
    Then blank lines do not appear as expressions in the output

  Scenario: Plain comment lines (not annotations) are ignored
    Given a .dt file containing the comment "// This is a comment"
    When the demo runner extracts expressions
    Then the comment line is not included as an expression to evaluate

  Scenario: A multi-line binding is kept as a single expression unit
    Given a .dt file containing:
      """
      greeting is "Hello"
      greeting + ", world!"
      """
    When the demo runner extracts expressions from a section
    Then it returns two separate expression units in order

  # ===========================================================================
  # SECTION 4: Expression Evaluation
  # ===========================================================================

  Scenario: Each expression is evaluated in document order
    Given a .dt file with expressions in order: "x is 5", then "x + 1"
    When the demo runner evaluates the file
    Then "x is 5" is evaluated first
    And "x + 1" evaluates to 6 (using the binding from the previous expression)

  Scenario: Bindings established in one expression are visible in subsequent ones
    Given a .dt file containing:
      """
      base is 100
      increment is [n -> n + base]
      increment 42
      """
    When the demo runner evaluates the file sequentially
    Then the final result of "increment 42" is 142

  Scenario: A runtime error in one expression does not stop evaluation
    Given a .dt file containing:
      """
      good-expr is 1 + 1
      bad-expr is undefined-name
      another-good is 2 + 2
      """
    When the demo runner evaluates the file
    Then "good-expr is 1 + 1" succeeds and produces 2
    And "bad-expr is undefined-name" records an error
    And "another-good is 2 + 2" still succeeds and produces 4

  # ===========================================================================
  # SECTION 5: @expect Annotations
  # ===========================================================================

  Scenario: An @expect annotation before an expression records the expected value
    Given a .dt file containing:
      """
      // @expect 14
      2 + 3 * 4
      """
    When the demo runner parses the file
    Then the expression "2 + 3 * 4" has an associated expected value of "14"

  Scenario: An expression with a matching @expect annotation passes validation
    Given a .dt file containing:
      """
      // @expect 14
      2 + 3 * 4
      """
    When the demo runner evaluates the file
    Then the @expect check passes for that expression

  Scenario: An expression whose result does not match its @expect annotation fails validation
    Given a .dt file containing:
      """
      // @expect 10
      2 + 3 * 4
      """
    When the demo runner evaluates the file
    Then the @expect check fails for that expression
    And the failure is recorded (not a crash) with the expected and actual values

  Scenario: Expressions without @expect annotations are evaluated without validation
    Given a .dt file containing an expression with no preceding @expect comment
    When the demo runner evaluates the file
    Then the expression is evaluated and its result is displayed
    And no assertion error is raised for missing expectations

  # ===========================================================================
  # SECTION 6: Formatted Output
  # ===========================================================================

  Scenario: Section titles are printed before their expressions
    Given a .dt file with a section titled "Pipelines"
    When the demo runner runs and prints output
    Then the section header "Pipelines" appears in the output before any expressions in that section

  Scenario: Each evaluated expression has its result printed
    Given a .dt file containing the expression "2 + 3 * 4"
    When the demo runner runs
    Then the output contains a formatted result line for "2 + 3 * 4"

  Scenario: Error results are displayed with an error marker, not a crash
    Given a .dt file containing an expression that will throw at runtime
    When the demo runner runs
    Then the output contains an error indicator for that expression
    And subsequent expressions still appear in the output

  # ===========================================================================
  # SECTION 7: End-to-End File Execution
  # ===========================================================================

  Scenario: Running demo-basics.dt from start to finish produces no unhandled exceptions
    Given the file "resources/examples/demo-basics.dt" exists and is valid
    When the demo runner executes the full file
    Then the run completes without throwing
    And at least one result line is printed

  Scenario: All @expect annotations in demo-basics.dt pass
    Given the file "resources/examples/demo-basics.dt" contains @expect annotations
    When the demo runner evaluates the file and checks all annotations
    Then every @expect annotation matches its expression's actual result
