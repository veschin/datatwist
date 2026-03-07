Feature: Literals, Types & Operators
  DataTwist is a functional data processing language that compiles to Clojure/JVM.
  This feature covers the foundational building blocks: literal values, their
  underlying Clojure types, arithmetic/comparison/logical operators, and the
  rules governing nil tolerance, type coercion, and operator precedence.

  Design decisions already locked:
    - `=` is equality comparison (NOT assignment)
    - `is` is the assignment keyword
    - `and`, `or`, `not` are logical operators (word-based, not symbolic)
    - Nil-tolerant: field access on nil returns nil, not an error
    - Strings are simple (no interpolation); use `format` for formatting
    - Compiles to Clojure; full JVM interop

  # ===========================================================================
  # SECTION 1: Integer Literals
  # ===========================================================================

  Scenario: Integer literal - simple positive
    Given the expression "42"
    Then it evaluates to 42
    And the Clojure type is java.lang.Long

  Scenario: Integer literal - zero
    Given the expression "0"
    Then it evaluates to 0
    And the Clojure type is java.lang.Long

  Scenario: Integer literal - large number within Long range
    Given the expression "9223372036854775807"
    Then it evaluates to 9223372036854775807
    And the Clojure type is java.lang.Long

  Scenario: Integer literal - overflow beyond Long range promotes to BigInt
    Given the expression "9223372036854775808"
    Then it evaluates to 9223372036854775808N
    And the Clojure type is clojure.lang.BigInt

  Scenario: Negative integer literal - unary minus attached
    Given the expression "-10"
    Then it evaluates to -10
    And the Clojure type is java.lang.Long

  Scenario: Negative integer literal - unary minus with space
    Given the expression "- 10"
    Then it is a parse error
    # DECISION NEEDED: Should `- 10` be valid as unary minus?
    # Recommendation: NO. Unary minus must be attached: `-10`.
    # `- 10` without a left operand is ambiguous (subtraction from what?).

  Scenario: Negative integer literal - double negative
    Given the expression "--5"
    Then it is a parse error
    # No double-negation syntax. Use `0 - (-5)` or a function.

  # ===========================================================================
  # SECTION 2: Float Literals
  # ===========================================================================

  Scenario: Float literal - simple decimal
    Given the expression "3.14"
    Then it evaluates to 3.14
    And the Clojure type is java.lang.Double

  Scenario: Float literal - zero point something
    Given the expression "0.5"
    Then it evaluates to 0.5
    And the Clojure type is java.lang.Double

  Scenario: Float literal - leading dot is NOT valid
    Given the expression ".5"
    Then it is a parse error
    # Require `0.5` for clarity in a data processing context.

  Scenario: Float literal - trailing dot is NOT valid
    Given the expression "5."
    Then it is a parse error
    # Require `5.0` for clarity.

  Scenario: Float literal - negative
    Given the expression "-0.001"
    Then it evaluates to -0.001
    And the Clojure type is java.lang.Double

  Scenario: Scientific notation is NOT supported in v1
    Given the expression "1e10"
    Then it parses as an identifier, not a number
    # DECISION: Scientific notation deferred. If needed, add `1.0e10` syntax later.
    # Rationale: data pipelines rarely need scientific notation in source code;
    # values come from data sources, not literals.

  Scenario: Underscore separators in numbers are NOT supported in v1
    Given the expression "1_000_000"
    Then it parses as an identifier, not a number
    # Could add later for readability in large literals.

  # ===========================================================================
  # SECTION 3: String Literals
  # ===========================================================================

  Scenario: String literal - simple
    Given the expression '"hello world"'
    Then it evaluates to "hello world"
    And the Clojure type is java.lang.String

  Scenario: String literal - empty string
    Given the expression '""'
    Then it evaluates to ""
    And the Clojure type is java.lang.String

  Scenario: String literal - with special characters
    Given the expression '"line1\nline2"'
    Then it evaluates to a string containing a newline
    # DECISION NEEDED: Are escape sequences supported?
    # Recommendation: YES, support standard escapes: \n \t \\ \"
    # Maps naturally to Java/Clojure string escaping.

  Scenario: String literal - with unicode
    Given the expression '"hello"'
    Then it evaluates to "hello"
    # Unicode in source files should just work (UTF-8 source).

  Scenario: String literal - no interpolation
    Given the expression '"Hello ${name}"'
    Then it evaluates to the literal string "Hello ${name}"
    # By design: no interpolation. Use `format "Hello %s" name` instead.

  Scenario: String literal - unclosed string is a parse error
    Given the expression '"hello'
    Then it is a parse error

  Scenario: String literal - multiline strings
    Given the expression '"hello\nworld"'
    Then it evaluates to a two-line string
    # DECISION NEEDED: Allow actual newlines inside double quotes?
    # Recommendation: NO for v1. Use \n escape. Multiline string syntax
    # (triple quotes or heredoc) can be added later if needed.

  # ===========================================================================
  # SECTION 4: Boolean Literals
  # ===========================================================================

  Scenario: Boolean true
    Given the expression "true"
    Then it evaluates to true
    And the Clojure type is java.lang.Boolean

  Scenario: Boolean false
    Given the expression "false"
    Then it evaluates to false
    And the Clojure type is java.lang.Boolean

  Scenario: Boolean keywords are reserved
    Given the binding "true is 5"
    Then it is a parse error
    # `true` and `false` are reserved words, not valid identifiers.

  # ===========================================================================
  # SECTION 5: Nil Literal
  # ===========================================================================

  Scenario: Nil literal
    Given the expression "nil"
    Then it evaluates to nil
    And the Clojure value is nil (null)

  Scenario: Nil is a reserved word
    Given the binding "nil is 42"
    Then it is a parse error

  # ===========================================================================
  # SECTION 6: Arithmetic Operators
  # ===========================================================================

  Scenario: Addition of two integers
    Given the expression "2 + 3"
    Then it evaluates to 5
    And the Clojure type is java.lang.Long

  Scenario: Addition of integer and float promotes to float
    Given the expression "2 + 3.0"
    Then it evaluates to 5.0
    And the Clojure type is java.lang.Double

  Scenario: Subtraction
    Given the expression "10 - 3"
    Then it evaluates to 7

  Scenario: Multiplication
    Given the expression "4 * 5"
    Then it evaluates to 20

  Scenario: Division of two integers - produces ratio or truncates
    Given the expression "5 / 2"
    Then it evaluates to 2.5
    # DECISION: Integer division semantics.
    # Option A: Clojure native `(/ 5 2)` returns Ratio 5/2 (displays as 5/2)
    # Option B: Always produce Double for division => 2.5
    # Option C: Integer division (truncate) => 2
    #
    # RECOMMENDATION: Option B -- always Double.
    # Rationale: DataTwist targets data analysts, not mathematicians.
    # `5 / 2 = 2` would surprise everyone. Clojure Ratios are confusing.
    # Compile `a / b` to `(double (/ a b))` or `(/ (double a) b)`.
    # If integer division is needed, provide `div` or `quot` function.

  Scenario: Division of floats
    Given the expression "7.0 / 2.0"
    Then it evaluates to 3.5

  Scenario: Division by zero - integer
    Given the expression "5 / 0"
    Then it throws an ArithmeticException
    # Clojure: (/ 5 0) => ArithmeticException "Divide by zero"

  Scenario: Division by zero - float
    Given the expression "5.0 / 0.0"
    Then it evaluates to Infinity
    # Clojure/JVM: (/ 5.0 0.0) => Infinity (IEEE 754)
    # DECISION: Let IEEE 754 behavior through, or catch and return nil?
    # Recommendation: Let it through. Infinity is a valid Double value on JVM.

  Scenario: Modulo operator
    Given the expression "10 % 3"
    Then it evaluates to 1
    # Maps to Clojure `(mod 10 3)` => 1
    # NOTE: Clojure `mod` always returns non-negative for positive divisor.
    # Clojure `rem` can return negative. We use `mod` semantics (mathematical modulo).

  Scenario: Modulo with negative dividend
    Given the expression "-10 % 3"
    Then it evaluates to 2
    # Clojure `(mod -10 3)` => 2 (not -1 like Java `%` / Clojure `rem`)
    # This is the mathematical modulo, always non-negative when divisor is positive.

  Scenario: Modulo by zero
    Given the expression "10 % 0"
    Then it throws an ArithmeticException

  Scenario: String concatenation with + operator
    Given the expression '"hello" + " " + "world"'
    Then it evaluates to "hello world"
    # DECISION: Does `+` concatenate strings?
    # Option A: Yes, `+` is overloaded for strings (JavaScript/Python style)
    # Option B: No, use `concat` or `str` function
    #
    # RECOMMENDATION: Option A -- overload `+` for strings.
    # Rationale: Extremely common operation in data pipelines.
    # Compile string `+` to `(str a b)`.
    # This is the one type-specific overload worth having.

  Scenario: String repetition is NOT supported with *
    Given the expression '"ha" * 3'
    Then it throws a type error
    # No implicit string repetition. Use a function if needed.

  # ===========================================================================
  # SECTION 7: Nil Tolerance in Arithmetic
  # ===========================================================================

  Scenario: nil + integer
    Given the expression "nil + 5"
    Then it evaluates to 5
    # nil coerces to 0 (identity element for addition)
    # DECIDED: nil coerces to identity element (0 for numbers, empty string for strings)

  Scenario: integer + nil
    Given the expression "5 + nil"
    Then it evaluates to 5
    # nil coerces to 0, so 5 + 0 = 5

  Scenario: nil * integer
    Given the expression "nil * 5"
    Then it evaluates to 0
    # nil coerces to 0, so 0 * 5 = 0

  Scenario: nil / integer
    Given the expression "nil / 5"
    Then it evaluates to 0.0
    # nil coerces to 0, so 0 / 5 = 0.0

  Scenario: nil % integer
    Given the expression "nil % 3"
    Then it evaluates to 0
    # nil coerces to 0, so 0 % 3 = 0

  Scenario: nil - nil
    Given the expression "nil - nil"
    Then it evaluates to 0
    # nil coerces to 0, so 0 - 0 = 0

  Scenario: Nil in chained arithmetic
    Given the expression "1 + nil + 3"
    Then it evaluates to 4
    # nil coerces to 0: 1 + 0 + 3 = 4

  # ===========================================================================
  # SECTION 8: Comparison Operators
  # ===========================================================================

  Scenario: Equality - same integers
    Given the expression "5 = 5"
    Then it evaluates to true

  Scenario: Equality - different integers
    Given the expression "5 = 6"
    Then it evaluates to false

  Scenario: Equality - integer and float with same value
    Given the expression "5 = 5.0"
    Then it evaluates to true
    # Clojure: (= 5 5.0) is false! But (== 5 5.0) is true.
    # DECISION: Should `=` use Clojure `=` (type-strict) or `==` (numeric)?
    # RECOMMENDATION: Use Clojure `==` for numeric comparisons, `=` for others.
    # Compile: if both operands are numbers, use `==`; otherwise use `=`.
    # Rationale: `5 = 5.0` being false would surprise every data analyst.

  Scenario: Equality - strings
    Given the expression '"hello" = "hello"'
    Then it evaluates to true

  Scenario: Equality - different types
    Given the expression '"5" = 5'
    Then it evaluates to false
    # No implicit type coercion for equality. "5" is not 5.

  Scenario: Equality - nil = nil
    Given the expression "nil = nil"
    Then it evaluates to true
    # Clojure: (= nil nil) => true. Consistent with SQL (NULL IS NULL... but
    # actually SQL says NULL = NULL is NULL). We deviate from SQL here.
    # DECISION: This is the right call. `nil = nil` is true.
    # In SQL you need IS NULL; in DataTwist, `=` handles nil naturally.

  Scenario: Equality - nil = 0
    Given the expression "nil = 0"
    Then it evaluates to false

  Scenario: Equality - nil = false
    Given the expression "nil = false"
    Then it evaluates to false

  Scenario: Equality - nil = empty string
    Given the expression 'nil = ""'
    Then it evaluates to false
    # nil is only equal to nil. No truthy/falsy coercion in equality.

  Scenario: Inequality
    Given the expression "5 != 3"
    Then it evaluates to true

  Scenario: Inequality - nil != nil
    Given the expression "nil != nil"
    Then it evaluates to false

  Scenario: Greater than - integers
    Given the expression "5 > 3"
    Then it evaluates to true

  Scenario: Greater than or equal
    Given the expression "5 >= 5"
    Then it evaluates to true

  Scenario: Less than
    Given the expression "3 < 5"
    Then it evaluates to true

  Scenario: Less than or equal
    Given the expression "5 <= 5"
    Then it evaluates to true

  Scenario: Comparison - integer vs float
    Given the expression "5 > 4.9"
    Then it evaluates to true
    # Cross-numeric comparison should work naturally.

  Scenario: String comparison - lexicographic
    Given the expression '"apple" < "banana"'
    Then it evaluates to true
    # DECISION: Are string comparisons supported with < > <= >= ?
    # RECOMMENDATION: YES. Maps to Clojure `(compare "apple" "banana")`.
    # Natural for sorting and filtering string data.

  Scenario: String comparison - case sensitive
    Given the expression '"Apple" < "apple"'
    Then it evaluates to true
    # Uppercase sorts before lowercase in Unicode/ASCII ordering.
    # This matches Clojure `compare` behavior.

  Scenario: Comparison with nil - ordering comparison returns nil (three-valued logic)
    Given the expression "5 > nil"
    Then it evaluates to nil
    # DECIDED: Comparison with nil returns nil (three-valued logic, nil means "unknown").
    # In a filter context, nil is falsy, so the record is excluded. Correct behavior.

  Scenario: nil > nil
    Given the expression "nil > nil"
    Then it evaluates to nil

  Scenario: Comparison between incompatible types
    Given the expression '"hello" > 5'
    Then it throws a type error
    # Cannot compare string to number with ordering operators.
    # Equality (`=`) returns false for different types; ordering is an error.

  # ===========================================================================
  # SECTION 9: Logical Operators
  # ===========================================================================

  Scenario: Logical and - both true
    Given the expression "true and true"
    Then it evaluates to true

  Scenario: Logical and - one false
    Given the expression "true and false"
    Then it evaluates to false

  Scenario: Logical or - both false
    Given the expression "false or false"
    Then it evaluates to false

  Scenario: Logical or - one true
    Given the expression "false or true"
    Then it evaluates to true

  Scenario: Logical not - true
    Given the expression "not true"
    Then it evaluates to false

  Scenario: Logical not - false
    Given the expression "not false"
    Then it evaluates to true

  Scenario: Logical not with expression
    Given the expression "not (5 > 3)"
    Then it evaluates to false

  Scenario: Logical not without parentheses
    Given the expression "not 5 > 3"
    Then it evaluates to false
    # DECISION: `not` precedence.
    # `not 5 > 3` could mean `(not 5) > 3` or `not (5 > 3)`.
    # RECOMMENDATION: `not` binds tighter than comparison,
    # so `not 5 > 3` means `(not 5) > 3` => `false > 3` => type error.
    #
    # ACTUALLY REVISED: `not` should have LOWEST precedence among unary ops
    # but HIGHER than `and`/`or`. So `not 5 > 3` = `not (5 > 3)` = false.
    # This matches Python behavior and is what users expect.
    # Precedence: arithmetic > comparison > not > and > or

  Scenario: Logical not with field access
    Given the expression "not _.active"
    Then it evaluates to the negation of the active field
    # `not _.active` is natural syntax. No parentheses required.

  Scenario: Short-circuit evaluation - and
    Given the expression "false and (10 / 0 > 1)"
    Then it evaluates to false
    # `and` short-circuits: if left side is false/nil, right side is NOT evaluated.
    # Maps to Clojure `and` which is a macro with short-circuit semantics.

  Scenario: Short-circuit evaluation - or
    Given the expression "true or (10 / 0 > 1)"
    Then it evaluates to true
    # `or` short-circuits: if left side is truthy, right side is NOT evaluated.

  Scenario: Logical and with nil - nil is falsy
    Given the expression "true and nil"
    Then it evaluates to nil
    # Clojure: (and true nil) => nil
    # `and` returns the first falsy value or the last value.
    # nil is falsy in Clojure/DataTwist.

  Scenario: Logical or with nil - nil is falsy
    Given the expression "nil or 5"
    Then it evaluates to 5
    # Clojure: (or nil 5) => 5
    # `or` returns the first truthy value or the last value.
    # This enables the "default value" idiom: `value or default`

  Scenario: Logical and returns actual values, not just booleans
    Given the expression "1 and 2"
    Then it evaluates to 2
    # Clojure: (and 1 2) => 2
    # `and`/`or` return values, not coerced booleans. This is powerful.

  Scenario: Logical or returns actual values, not just booleans
    Given the expression "nil or 0 or false or 42"
    Then it evaluates to 0
    # (or nil 0 false 42) -- 0 is truthy in Clojure! So this returns 0.
    # WAIT: In Clojure, only nil and false are falsy. 0 is truthy.
    # So `nil or 0 or false or 42` => 0 (the first truthy value).
    # DECISION: Follow Clojure truthiness rules.
    # Only nil and false are falsy. 0, "", [] are all truthy.
    # This differs from JavaScript/Python where 0 and "" are falsy.

  Scenario: Zero is truthy
    Given the expression "0 or 42"
    Then it evaluates to 0
    # Clojure truthiness: 0 is truthy. This returns 0, not 42.
    # May surprise JS/Python users but is correct for Clojure semantics.

  Scenario: Empty string is truthy
    Given the expression '"" or "default"'
    Then it evaluates to ""
    # Clojure truthiness: "" is truthy.

  Scenario: Logical not with nil
    Given the expression "not nil"
    Then it evaluates to true
    # Clojure: (not nil) => true. nil is falsy.

  Scenario: Logical not with zero
    Given the expression "not 0"
    Then it evaluates to false
    # Clojure: (not 0) => false. 0 is truthy.

  Scenario: Complex logical expression
    Given the expression "true and false or true"
    Then it evaluates to true
    # Precedence: `and` binds tighter than `or`.
    # (true and false) or true => false or true => true

  Scenario: Complex logical with not
    Given the expression "not true or false"
    Then it evaluates to false
    # Precedence: not > and > or
    # (not true) or false => false or false => false

  # ===========================================================================
  # SECTION 10: Operator Precedence
  # ===========================================================================

  Scenario: Standard math precedence - multiplication before addition
    Given the expression "2 + 3 * 4"
    Then it evaluates to 14
    # NOT 20. Standard math: `2 + (3 * 4)` = 14.

  Scenario: Standard math precedence - division before subtraction
    Given the expression "10 - 6 / 2"
    Then it evaluates to 7.0
    # `10 - (6 / 2)` = 10 - 3.0 = 7.0
    # NOTE: 6/2 = 3.0 (Double) per our division rule, then 10 - 3.0 = 7.0

  Scenario: Parentheses override precedence
    Given the expression "(2 + 3) * 4"
    Then it evaluates to 20

  Scenario: Nested parentheses
    Given the expression "((2 + 3) * (4 - 1))"
    Then it evaluates to 15

  Scenario: Modulo precedence - same as multiplication/division
    Given the expression "10 + 7 % 3"
    Then it evaluates to 11
    # `10 + (7 % 3)` = 10 + 1 = 11
    # % has same precedence as * and /

  Scenario: Comparison after arithmetic
    Given the expression "2 + 3 > 4"
    Then it evaluates to true
    # (2 + 3) > 4 => 5 > 4 => true

  Scenario: Logical after comparison
    Given the expression "5 > 3 and 2 < 4"
    Then it evaluates to true
    # (5 > 3) and (2 < 4) => true and true => true

  Scenario: Full precedence chain
    Given the expression "2 + 3 * 4 > 10 and not false"
    Then it evaluates to true
    # Step by step:
    # 1. 3 * 4 = 12 (multiplication first)
    # 2. 2 + 12 = 14 (addition)
    # 3. 14 > 10 = true (comparison)
    # 4. not false = true (not)
    # 5. true and true = true (and)

  # Full precedence table (highest to lowest):
  # 1. Parentheses: ( )
  # 2. Unary minus: -x
  # 3. Multiplication, division, modulo: * / %
  # 4. Addition, subtraction: + -
  # 5. Comparison: = != > < >= <=
  # 6. not
  # 7. and
  # 8. or

  # ===========================================================================
  # SECTION 11: Type Coercion Rules
  # ===========================================================================

  Scenario: No implicit coercion - string + number is an error
    Given the expression '"count: " + 5'
    Then it throws a type error
    # DECISION: Does string + number auto-coerce?
    # Option A: Yes, coerce number to string ("count: 5") -- JS style
    # Option B: No, type error -- strict
    #
    # RECOMMENDATION: Option B -- type error.
    # Use `format "count: %d" 5` or `"count: " + (str 5)` instead.
    # Rationale: Implicit coercion leads to subtle bugs in data pipelines.
    # `str` function for explicit conversion is clear.
    #
    # HOWEVER: If string + string is supported, this is a common pain point.
    # ALTERNATIVE: Allow string + anything => string concatenation (coerce via str).
    # FINAL DECISION NEEDED from language designer.

  Scenario: No implicit coercion - boolean to number
    Given the expression "true + 1"
    Then it throws a type error
    # No `true = 1` coercion. Use explicit conversion if needed.

  Scenario: Integer to float promotion in mixed arithmetic
    Given the expression "5 + 2.0"
    Then it evaluates to 7.0
    And the Clojure type is java.lang.Double
    # This is the ONLY implicit promotion: int + float => float.
    # Mirrors Clojure/JVM numeric promotion.

  Scenario: Float to integer is never implicit
    Given the expression "5.0"
    Then it evaluates to 5.0
    And the Clojure type is java.lang.Double
    # 5.0 stays Double, never becomes Long implicitly.
    # Use `int` or `round` function for explicit conversion.

  # ===========================================================================
  # SECTION 12: Unary Minus Edge Cases
  # ===========================================================================

  Scenario: Unary minus on literal
    Given the expression "-42"
    Then it evaluates to -42

  Scenario: Unary minus on identifier
    Given the binding "x is 5"
    And the expression "-x"
    Then it evaluates to -5
    # DECISION: Is `-x` valid syntax for negation of a binding?
    # RECOMMENDATION: YES. Parse as unary minus applied to identifier.
    # Compile to `(- x)` in Clojure.

  Scenario: Subtraction vs unary minus - context matters
    Given the binding "x is 10"
    And the expression "x - 3"
    Then it evaluates to 7
    # Binary minus: two operands separated by spaces.

  Scenario: Unary minus in expression
    Given the expression "5 * -3"
    Then it evaluates to -15
    # DECISION: Is this valid? `5 * -3` with unary minus after operator.
    # RECOMMENDATION: YES. After a binary operator, `-` is unary.
    # Alternatively require parentheses: `5 * (-3)`.
    # FINAL CALL NEEDED: requiring parens is safer for the parser.

  Scenario: Negation of parenthesized expression
    Given the expression "-(3 + 4)"
    Then it evaluates to -7

  # ===========================================================================
  # SECTION 13: The `in` Operator
  # ===========================================================================

  Scenario: in operator - value in list
    Given the binding "tags is ['premium' 'active' 'verified']"
    And the expression '"premium" in tags'
    Then it evaluates to true
    # DECISION: Is `in` an operator?
    # RECOMMENDATION: YES. Essential for data filtering.
    # Compile to: `(contains? (set tags) "premium")` or `(some #(= % "premium") tags)`
    # For vectors, `some` is correct. For sets, `contains?` is O(1).

  Scenario: in operator - value not in list
    Given the binding "tags is ['a' 'b']"
    And the expression '"z" in tags'
    Then it evaluates to false

  Scenario: in operator - value in object (checks keys)
    Given the binding 'user is {name: "Alice" age: 25}'
    And the expression '"name" in user'
    Then it evaluates to true
    # `in` on an object checks keys. Compile to `(contains? user :name)`.
    # NOTE: DataTwist uses string-style keys but they compile to keywords.
    # So `"name" in user` checks for `:name` key.

  Scenario: in operator - nil in list
    Given the binding "items is [1 nil 3]"
    And the expression "nil in items"
    Then it evaluates to true

  Scenario: in operator - value in nil
    Given the expression '"x" in nil'
    Then it evaluates to nil
    # Nil tolerance: searching in nil returns nil, not error.

  Scenario: in operator - precedence with not
    Given the binding "tags is ['a' 'b']"
    And the expression 'not "c" in tags'
    Then it evaluates to true
    # `not ("c" in tags)` => not false => true
    # `in` has same precedence as comparison operators.

  # ===========================================================================
  # SECTION 14: Equality Operator Deep Dive
  # ===========================================================================

  Scenario: Structural equality for objects
    Given the expression '{name: "Alice" age: 25} = {name: "Alice" age: 25}'
    Then it evaluates to true
    # Clojure maps use structural equality by default. This just works.

  Scenario: Structural equality for lists
    Given the expression "[1 2 3] = [1 2 3]"
    Then it evaluates to true

  Scenario: Object key order does not matter for equality
    Given the expression '{age: 25 name: "Alice"} = {name: "Alice" age: 25}'
    Then it evaluates to true
    # Clojure maps are unordered. Equality is structural.

  Scenario: Nested structural equality
    Given the expression '{a: {b: [1 2]}} = {a: {b: [1 2]}}'
    Then it evaluates to true

  Scenario: List order matters for equality
    Given the expression "[1 2 3] = [3 2 1]"
    Then it evaluates to false

  # ===========================================================================
  # SECTION 15: Edge Cases and Miscellaneous
  # ===========================================================================

  Scenario: Chained comparisons are NOT supported
    Given the expression "1 < 2 < 3"
    Then it is a parse error
    # DECIDED: Chained comparisons are not valid syntax.
    # The grammar does not allow comparison operators to chain.
    # Users should write `1 < 2 and 2 < 3` explicitly.

  Scenario: Whitespace around operators is required
    Given the expression "2+3"
    Then it is a parse error
    # DECISION: Is whitespace required around binary operators?
    # RECOMMENDATION: YES for readability and to avoid ambiguity with
    # negative numbers and identifiers containing hyphens (e.g., `x-1`
    # is identifier "x-1" not "x minus 1").
    # `2+3` is a parse error. Write `2 + 3`.

  Scenario: Identifier with hyphen vs subtraction
    Given the binding "my-var is 10"
    And the expression "my-var"
    Then it evaluates to 10
    # `my-var` is an identifier (hyphens allowed in identifiers).
    # `my - var` (with spaces) is subtraction.
    # This is why whitespace around operators matters.

  Scenario: Multiple operators without operands
    Given the expression "5 + + 3"
    Then it is a parse error

  Scenario: Empty parentheses
    Given the expression "()"
    Then it is a parse error

  Scenario: Division produces consistent types
    Given the expression "10 / 5"
    Then it evaluates to 2.0
    And the Clojure type is java.lang.Double
    # Even when division is "even", result is always Double.
    # This ensures consistent behavior: division ALWAYS produces Double.

  Scenario: Very large arithmetic does not silently overflow
    Given the expression "9223372036854775807 + 1"
    Then it evaluates to 9223372036854775808N
    # Clojure auto-promotes to BigInt on overflow. DataTwist inherits this.
    # No silent wraparound like Java.

  Scenario: Floating point precision
    Given the expression "0.1 + 0.2"
    Then it evaluates to approximately 0.3
    # IEEE 754: 0.1 + 0.2 = 0.30000000000000004
    # This is inherent to Double. Document it.
    # If exact decimal arithmetic is needed, BigDecimal support can be added later.

  # ===========================================================================
  # SECTION 16: Operator on Nil in Pipelines (Practical Scenarios)
  # ===========================================================================

  Scenario: Nil-tolerant field access in comparison
    Given the binding 'user is {name: "Alice"}'
    And the expression "user.age > 18"
    Then it evaluates to nil
    # user.age is nil (field does not exist, nil-tolerant).
    # nil > 18 => nil (nil propagation in comparison).
    # In a filter, this nil is falsy, so the record is excluded. Correct behavior.

  Scenario: Nil-tolerant field in arithmetic
    Given the binding 'item is {name: "Widget"}'
    And the expression "item.price * 1.1"
    Then it evaluates to 0.0
    # item.price is nil => nil coerces to 0 => 0 * 1.1 = 0.0

  Scenario: Nil-tolerant chained field access in expression
    Given the binding 'data is {user: nil}'
    And the expression "data.user.profile.age + 1"
    Then it evaluates to 1
    # data.user is nil, .profile on nil is nil, .age on nil is nil, nil coerces to 0, 0 + 1 = 1.

  # ===========================================================================
  # SECTION 17: Expressions as Values
  # ===========================================================================

  Scenario: Expression result assigned with is
    Given the binding "result is 2 + 3 * 4"
    Then result evaluates to 14

  Scenario: Comparison result assigned with is
    Given the binding "adult is 25 > 18"
    Then adult evaluates to true

  Scenario: Logical expression result assigned with is
    Given the binding "valid is true and not false"
    Then valid evaluates to true

  Scenario: Parenthesized expression assigned
    Given the binding "x is (2 + 3) * (4 + 5)"
    Then x evaluates to 45

  # ===========================================================================
  # SECTION 18: Regex Literals
  # ===========================================================================

  Scenario: Regex literal compiles to java.util.regex.Pattern
    Given the expression '#","'
    Then the Clojure type is java.util.regex.Pattern

  Scenario: Regex literal - simple comma pattern
    Given the expression '#","'
    Then it evaluates to a regex with pattern ","

  Scenario: Regex literal - empty pattern
    Given the expression '#""'
    Then it evaluates to a regex with pattern ""

  Scenario: Regex literal - digit pattern
    Given the expression '#"\d+"'
    Then it evaluates to a regex with pattern "\d+"

  Scenario: Regex literal - dot-star pattern
    Given the expression '#".*"'
    Then it evaluates to a regex with pattern ".*"

  Scenario: Regex literal assigned with is
    Given the binding 'sep is #","'
    Then sep evaluates to a regex of type java.util.regex.Pattern


# =============================================================================
# OPEN QUESTIONS AND RECOMMENDATIONS
# =============================================================================
#
# Q1: Integer division semantics
#   `5 / 2` = ?
#   RECOMMENDATION: Always produce Double (2.5).
#   Provide `quot` function for integer division, `rem` for remainder.
#   Compile: `(/ a b)` => `(double (/ a b))` when both are integers.
#   STATUS: Needs final decision.
#
# Q2: String + number coercion
#   `"count: " + 5` = "count: 5" or type error?
#   RECOMMENDATION: Type error. Use `format` or explicit `str` conversion.
#   ALTERNATIVE: Allow it for ergonomics. DataTwist is for data people.
#   STATUS: Needs final decision. Ergonomics vs safety tradeoff.
#
# Q3: `in` operator inclusion
#   RECOMMENDATION: YES, include `in` as a binary operator.
#   Extremely common in data filtering: `"premium" in _.tags`
#   Precedence: same level as comparison operators.
#   Compile: `(some #(= % val) coll)` for sequential, `(contains? coll val)` for sets.
#   STATUS: Needs final decision.
#
# Q4: Chained comparisons
#   `1 < x < 10` -- Python style?
#   RECOMMENDATION: NO for v1. Standard left-to-right evaluation.
#   Users write `x > 1 and x < 10`. Add later if demand exists.
#   STATUS: Decided NO for v1.
#
# Q5: Bitwise operations
#   RECOMMENDATION: NO for v1. Data pipelines rarely need bitwise ops.
#   If needed, provide functions: `bit-and`, `bit-or`, `bit-xor`, `bit-shift-left`.
#   These map directly to Clojure's bitwise functions.
#   STATUS: Decided NO for v1 operators. Functions can be added.
#
# Q6: Range syntax (`1..10`)
#   RECOMMENDATION: NO for v1 as operator syntax.
#   Provide `range` function instead: `range 1 10` => [1 2 3 4 5 6 7 8 9].
#   Maps to Clojure `(range 1 10)`.
#   Range syntax is nice sugar but adds parser complexity.
#   STATUS: Decided NO for v1 syntax. Use `range` function.
#
# Q7: `not` syntax variants
#   `not x`, `not(x)`, `not _.active` -- all valid?
#   RECOMMENDATION: `not` is a prefix unary operator. All three are valid:
#   - `not x` => `(not x)`
#   - `not(x)` => `(not x)` (parentheses are just grouping)
#   - `not _.active` => `(not (get _ :active))`
#   STATUS: Decided. `not` is prefix unary, parentheses optional.
#
# Q8: Modulo syntax: `%` or `mod`?
#   RECOMMENDATION: `%` as operator, `mod` available as function alias.
#   `%` is universally recognized. Compiles to Clojure `(mod a b)`.
#   STATUS: Decided. `%` operator.
#
# Q9: Whitespace requirements around operators
#   RECOMMENDATION: REQUIRED. `2+3` is invalid, must be `2 + 3`.
#   Critical because identifiers can contain hyphens (`my-var`).
#   Without this rule, `x-1` is ambiguous: identifier or subtraction?
#   STATUS: Decided. Whitespace required around binary operators.
#
# Q10: `not=` as a single operator?
#   Clojure has `not=`. Should DataTwist?
#   RECOMMENDATION: NO. Use `!=` for not-equal.
#   `not=` looks confusing next to `=` for equality.
#   `!=` is universally understood.
#   STATUS: Decided. `!=` only.
#
# Q11: Exponentiation operator (`**` or `^`)?
#   RECOMMENDATION: NO operator for v1. Use `pow` function.
#   `^` conflicts with Clojure's `bit-xor` (though we don't expose it).
#   `**` is Python-specific and not universal.
#   `pow 2 10` => `(Math/pow 2 10)` is clear enough.
#   STATUS: Decided NO for v1. Use `pow` function.
#
# Q12: Equality operator ambiguity with assignment
#   `=` is equality, `is` is assignment. But what about:
#   `x is y = z` -- is this `x is (y = z)` (assign comparison result)?
#   RECOMMENDATION: YES. `is` has the lowest precedence of all.
#   `x is y = z` means `x is (y = z)`.
#   This is natural because `is` separates the name from the expression.
#   STATUS: Needs confirmation.
#
# =============================================================================
# CLOJURE TYPE MAPPING
# =============================================================================
#
# DataTwist Literal  | Clojure Type          | Example
# -------------------|-----------------------|--------------------------------
# 42                 | java.lang.Long        | 42
# 3.14               | java.lang.Double      | 3.14
# "hello"            | java.lang.String      | "hello"
# true / false       | java.lang.Boolean     | true / false
# nil                | nil                   | nil
# {name: "Alice"}    | clojure.lang.PersistentArrayMap / PersistentHashMap | {:name "Alice"}
# [1 2 3]            | clojure.lang.PersistentVector | [1 2 3]
# 9999999999999999999| clojure.lang.BigInt   | 9999999999999999999N
#
# Numeric promotion rules (inherited from Clojure/JVM):
#   Long + Long => Long (unless overflow => BigInt)
#   Long + Double => Double
#   Long / Long => Double (DataTwist-specific; Clojure returns Ratio)
#   Double + Double => Double
#   Any + nil => identity (DataTwist nil-coercion: nil → 0 for numbers, nil → "" for strings)
#
# =============================================================================
# OPERATOR COMPILATION TO CLOJURE
# =============================================================================
#
# DataTwist  | Clojure               | Notes
# -----------|-----------------------|--------------------------------------
# a + b      | (+ a b)               | Overloaded: (str a b) for strings
# a - b      | (- a b)               |
# a * b      | (* a b)               |
# a / b      | (double (/ a b))      | Always returns Double
# a % b      | (mod a b)             | Mathematical modulo (non-negative)
# -a         | (- a)                 | Unary negation
# a = b      | (= a b) or (== a b)  | == for both-numeric, = otherwise
# a != b     | (not= a b)            |
# a > b      | (> a b)               |
# a < b      | (< a b)               |
# a >= b     | (>= a b)              |
# a <= b     | (<= a b)              |
# a and b    | (and a b)             | Short-circuit, returns values
# a or b     | (or a b)              | Short-circuit, returns values
# not a      | (not a)               |
# a in b     | (dt/contains? b a)    | Custom helper for polymorphic check
#
# Nil-safe wrappers needed:
#   All arithmetic operators need nil-coercion wrappers.
#   Comparison operators need nil-coercion wrappers.
#   Logical operators use Clojure's native nil handling (nil is falsy).
#   Example wrapper:
#     (defn safe+ [a b] (+ (or a 0) (or b 0)))
#
# =============================================================================
# CORNER CASES THAT NEED DESIGN DECISIONS
# =============================================================================
#
# 1. NEGATIVE NUMBER VS SUBTRACTION AMBIGUITY
#    `x-1` => identifier "x-1" (hyphens in identifiers)
#    `x - 1` => subtraction
#    `-1` => negative literal
#    `x -1` => function call `x` with argument `-1`? Or subtraction?
#    RECOMMENDATION: `x -1` is ambiguous. Require explicit:
#    - `x - 1` for subtraction (spaces around operator)
#    - `x(-1)` or `x -1` for function call with negative arg
#    This is a grammar-level challenge. The whitespace rule mostly solves it,
#    but `x -1` (one space, no space) needs careful handling.
#    PARSER RULE: Binary operators require spaces on BOTH sides.
#    `x -1` is parsed as function call (identifier followed by expression).
#
# 2. DIVISION TYPE CONSISTENCY
#    If `10 / 5` produces `2.0` (Double), this may surprise:
#    `[1 2 3] |> map [x -> 10 / x]` => [10.0, 5.0, 3.333...]
#    All results are Double even when "exact". This is a feature, not a bug,
#    because it guarantees type consistency in collections.
#
# 3. BigDecimal SUPPORT
#    For financial data, Double precision is insufficient.
#    Options: (a) Add `M` suffix like Clojure: `3.14M` => BigDecimal
#             (b) Provide `decimal` function: `decimal "3.14"`
#    RECOMMENDATION: Defer to v2. Use Clojure interop if needed now.
#
# 4. NaN AND Infinity
#    `0.0 / 0.0` => NaN, `5.0 / 0.0` => Infinity.
#    These are valid JVM Double values. Questions:
#    - `NaN = NaN` => false (IEEE 754 spec), but is that what users expect?
#    - Should we provide `nan?` and `infinite?` predicates?
#    RECOMMENDATION: Let IEEE 754 through. Provide `nan?` function.
#    `NaN = NaN` is false per IEEE 754 and Clojure. Document it.
#
# 5. OPERATOR CHAINING WITH SAME PRECEDENCE
#    `10 - 5 - 3` => `(10 - 5) - 3` = 2 (left-to-right associativity)
#    `10 / 5 / 2` => `(10 / 5) / 2` = 1.0
#    Confirm: all binary operators are left-associative. YES.
#
# 6. PARENTHESIZED EXPRESSIONS VS FUNCTION CALLS
#    `f(x)` could be function call or `f` times `(x)`.
#    Current grammar: `func(args)` is parenthesized function call syntax.
#    `(expr)` alone is grouping.
#    Rule: identifier immediately followed by `(` (no space) = function call.
#    `f(x)` = call f with x. `f (x)` = also call f with (x) as argument.
#    This is consistent because DataTwist uses juxtaposition for function calls.
#
# 7. EMPTY EXPRESSION
#    What is the value of an empty expression or an empty program?
#    RECOMMENDATION: nil. An empty program evaluates to nil.
#
# 8. TRUTHINESS TABLE FOR REFERENCE
#    Value      | Truthy? | Notes
#    -----------|---------|----------------------------------
#    true       | YES     |
#    false      | NO      |
#    nil        | NO      |
#    0          | YES     | Differs from JS/Python!
#    ""         | YES     | Differs from JS/Python!
#    []         | YES     | Empty list is truthy
#    {}         | YES     | Empty object is truthy
#    42         | YES     |
#    "hello"    | YES     |
#    NaN        | YES     | Clojure: NaN is truthy (not nil/false)
#
# 9. DECIDED: `//` for comments. See PRD design decisions.
#    This feature file does not cover comments but notes the dependency.
