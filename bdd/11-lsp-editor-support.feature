Feature: LSP Editor Support
  DataTwist provides IDE tooling via two independent stacks:
    - Stack A (interactive): nREPL middleware + Emacs mode for eval-at-point and inspection
    - Stack B (static): Tree-sitter grammar + TypeScript LSP server for all editors
  This feature covers observable editor behaviors delivered by Stack B (LSP) and
  the parts of Stack A visible in the editor (inline result overlays, inspector UI).

  Key design constraints:
    - The LSP server uses Tree-sitter for all parsing; it never invokes the JVM evaluator
    - Diagnostics report parse errors using DataTwist syntax (not Clojure), e.g. "Use 'is' for assignment"
    - Completion ranks stdlib functions above local bindings above object fields
    - Hover shows DataTwist syntax for types, not JVM types (e.g. "String" not "java.lang.String")

  # ===========================================================================
  # SECTION 1: Syntax Highlighting
  # ===========================================================================

  Scenario: Keywords are highlighted as keyword tokens
    Given the file contains "name is \"Alice\""
    Then "is" has the highlight scope "keyword.operator.datatwist"
    And "name" has the highlight scope "variable.other.binding.datatwist"

  Scenario: Control keywords are highlighted distinctly
    Given the file contains "try ... catch e -> ..."
    Then "try" has the highlight scope "keyword.control.datatwist"
    And "catch" has the highlight scope "keyword.control.datatwist"

  Scenario: String literals are highlighted as strings
    Given the file contains "greeting is \"hello world\""
    Then "\"hello world\"" has the highlight scope "string.quoted.double.datatwist"

  Scenario: Numeric literals are highlighted as constants
    Given the file contains "count is 42"
    Then "42" has the highlight scope "constant.numeric.datatwist"

  Scenario: Boolean and nil literals are highlighted as language constants
    Given the file contains "active is true\nmissing is nil"
    Then "true" has the highlight scope "constant.language.boolean.datatwist"
    And "nil" has the highlight scope "constant.language.nil.datatwist"

  Scenario: Line comments are highlighted as comments
    Given the file contains "// this is a comment"
    Then the entire line has the highlight scope "comment.line.double-slash.datatwist"

  Scenario: Pipe operator is highlighted as a pipe operator
    Given the file contains "users |> filter _.active"
    Then "|>" has the highlight scope "keyword.operator.pipe.datatwist"

  Scenario: Object keys use a tag scope
    Given the file contains "{name: \"Alice\" age: 25}"
    Then "name:" has the highlight scope "entity.name.tag.datatwist"
    And "age:" has the highlight scope "entity.name.tag.datatwist"

  Scenario: Side-effect functions are highlighted distinctly
    Given the file contains "log! data \"saving\""
    Then "log!" has the highlight scope "entity.name.function.side-effect.datatwist"

  Scenario: Wildcard is highlighted as a language variable
    Given the file contains "users |> filter _.active"
    Then "_" has the highlight scope "variable.language.wildcard.datatwist"

  # ===========================================================================
  # SECTION 2: Autocomplete
  # ===========================================================================

  Scenario: Stdlib functions appear in completion at top level
    Given the cursor is at the start of a new line
    When the user types "fi"
    Then the completion list includes "filter"
    And the completion list includes "find"
    And each entry is labeled as a function with its arity

  Scenario: Completion includes locally bound names from the current file
    Given the file contains:
      """
      users is [{name: "Alice"} {name: "Bob"}]
      use
      """
    And the cursor is at the end of "use"
    Then the completion list includes "users"

  Scenario: Field completion after dot operator
    Given the file contains:
      """
      user is {name: "Alice" age: 25}
      user.
      """
    And the cursor is positioned after "user."
    Then the completion list includes "name"
    And the completion list includes "age"
    And the entries do not include stdlib function names

  Scenario: Completion after pipe operator offers callable functions
    Given the file contains "users |> "
    And the cursor is after "|> "
    Then the completion list includes "filter"
    And the completion list includes "map"
    And the completion list includes "sort-by"

  Scenario: Completion respects scope — inner binding shadows outer
    Given the file contains:
      """
      x is 10
      result is [x ->
        x
      """
    And the cursor is at the final "x"
    Then the completion list includes "x" (the function parameter)
    And the parameter "x" ranks above the outer binding "x"

  # ===========================================================================
  # SECTION 3: Hover
  # ===========================================================================

  Scenario: Hovering a stdlib function shows its signature
    Given the file contains "filter _.active users"
    When the user hovers over "filter"
    Then the hover popup shows the signature "filter [predicate collection]"
    And the hover popup includes a description of what filter does

  Scenario: Hovering a bound name shows its inferred type
    Given the file contains:
      """
      name is "Alice"
      name
      """
    When the user hovers over the second "name"
    Then the hover popup shows "name: String"

  Scenario: Hovering a function definition shows its parameter names
    Given the file contains "double is [x -> x * 2]"
    When the user hovers over "double"
    Then the hover popup shows "double [x]"

  Scenario: Hovering nil shows nil type information
    Given the file contains "result is nil"
    When the user hovers over "nil"
    Then the hover popup shows "nil"

  # ===========================================================================
  # SECTION 4: Go-to-Definition
  # ===========================================================================

  Scenario: Go-to-definition on a bound name jumps to its is-binding
    Given the file contains:
      """
      greeting is "hello"
      greeting
      """
    When the user invokes go-to-definition on the second "greeting"
    Then the cursor moves to the "greeting is" binding on line 1

  Scenario: Go-to-definition on a function name jumps to its definition
    Given the file contains:
      """
      double is [x -> x * 2]
      result is double 5
      """
    When the user invokes go-to-definition on "double" in "double 5"
    Then the cursor moves to "double is [x -> x * 2]" on line 1

  Scenario: Go-to-definition on a parameter jumps to its declaration in the function head
    Given the file contains "add is [a b -> a + b]"
    When the user invokes go-to-definition on "a" in the body "a + b"
    Then the cursor moves to "a" in the parameter list "[a b -> ...]"

  Scenario: Go-to-definition on an undefined name produces a diagnostic instead of navigating
    Given the file contains "unknown-name"
    When the user invokes go-to-definition on "unknown-name"
    Then no navigation occurs
    And a diagnostic message is shown: "undefined name: unknown-name"

  # ===========================================================================
  # SECTION 5: Error Diagnostics
  # ===========================================================================

  Scenario: Parse error is shown as an inline diagnostic
    Given the file contains "x = 42"
    Then line 1 has an error diagnostic
    And the diagnostic message contains "Use 'is' for assignment"

  Scenario: Unclosed bracket produces a diagnostic at the bracket location
    Given the file contains "[1 2 3"
    Then the diagnostic is located at the opening "["
    And the diagnostic message contains "unclosed"

  Scenario: Multiple parse errors are all reported
    Given the file contains:
      """
      a = 1
      b = 2
      """
    Then there are two error diagnostics
    And each diagnostic is on its respective line

  Scenario: Valid code produces no diagnostics
    Given the file contains:
      """
      users is [{name: "Alice" active: true}]
      result is users |> filter _.active |> map _.name
      """
    Then there are no diagnostics

  Scenario: Diagnostics clear when the parse error is fixed
    Given the file contains "x = 42" which produces a diagnostic
    When the user changes the line to "x is 42"
    Then the diagnostic on line 1 is removed

  # ===========================================================================
  # SECTION 6: Signature Help
  # ===========================================================================

  Scenario: Signature help shows parameter hints inside a function call
    Given the file contains "filter "
    And the cursor is positioned after "filter "
    When the editor requests textDocument/signatureHelp
    Then the response shows signature "filter [predicate collection]"
    And "predicate" is marked as the active parameter
    And each parameter has a tab-stop annotation

  # ===========================================================================
  # SECTION 7: Pipeline Step Inspection via Hover
  # ===========================================================================

  Scenario: Hovering a pipeline step shows sample data for that step
    Given the file contains:
      """
      users is [{name: "Alice" active: true} {name: "Bob" active: false}]
      users |> filter _.active |> map _.name
      """
    When the user hovers over "filter" in the pipeline
    Then the hover popup shows sample data after applying "filter _.active"
    And the sample data is formatted in DataTwist syntax
