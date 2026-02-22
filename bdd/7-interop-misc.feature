Feature: Clojure Interop, Comments, Try-Catch, Nil Semantics, and Miscellaneous
  As a DataTwist user
  I want seamless Clojure/JVM interop, clear error handling,
  predictable nil behavior, comments, and string formatting
  So that I can build robust data pipelines on the JVM ecosystem

  # ============================================================
  # SECTION 1: COMMENTS
  # ============================================================
  #
  # PRD: "Comments: `//` -- Universal, no conflict with language constructs"
  # PRD: Comments use `//`.
  # Grammar uses `//` for line comments; they are stripped during parsing
  # and do not appear in the AST.
  # No block comments. Use multiple `//` lines.
  # ============================================================

  Scenario: Single-line comment at top of file
    Given the DataTwist source
      """
      // This is a comment
      x is 42
      """
    When it is evaluated
    Then the value of "x" is 42

  Scenario: Single-line comment after code on same line
    Given the DataTwist source
      """
      x is 42 // the answer
      """
    When it is evaluated
    Then the value of "x" is 42

  Scenario: Comment between pipeline steps
    Given the DataTwist source
      """
      items is [1 2 3 4 5]
      result is items
        // only evens
        |> filter [n -> n % 2 = 0]
      """
    When it is evaluated
    Then the value of "result" is [2 4]

  Scenario: Multiple consecutive comment lines
    Given the DataTwist source
      """
      // Section: data processing
      // Author: team
      // Date: 2026-01-15
      data is [1 2 3]
      """
    When it is evaluated
    Then the value of "data" is [1 2 3]

  Scenario: Comment on otherwise blank line inside object literal
    Given the DataTwist source
      """
      user is {
        name: "Alice"
        // age will be added later
        status: "active"
      }
      """
    When it is evaluated
    Then the value of "user.name" is "Alice"
    And the value of "user.status" is "active"

  Scenario: Comment inside list literal
    Given the DataTwist source
      """
      items is [
        1
        // middle values
        2
        3
      ]
      """
    When it is evaluated
    Then the value of "items" is [1 2 3]

  Scenario: A line containing only a comment produces no top-level form
    Given the DataTwist source
      """
      // just a comment, nothing else
      """
    When it is evaluated
    Then the program result is nil

  Scenario: Double-slash inside a string literal is not treated as a comment
    Given the DataTwist source
      """
      url is "https://example.com"
      """
    When it is evaluated
    Then the value of "url" is "https://example.com"

  # ============================================================
  # SECTION 2: CLOJURE INTEROP
  # ============================================================
  #
  # PRD Section 7 defines three interop mechanisms:
  #
  # (A) Direct qualified calls:
  #     clojure.string/upper-case "hello"
  #     Namespaced identifiers (containing /) are treated as
  #     direct Clojure var references.
  #
  # (B) `require` with alias:
  #     require clojure.string as str
  #     str/upper-case "hello"
  #
  # (C) Java interop:
  #     .method object       -- instance method (.toUpperCase "hello")
  #     Class/staticMethod   -- static method (Math/pow 2 10)
  #     ClassName.           -- constructor (java.util.Date.)
  #
  # (D) Keywords: `:keyword` syntax supported for interop.
  #     Object keys are keywords under the hood.
  # ============================================================

  # --- 2A: Direct qualified Clojure function calls ---

  Scenario: Call a qualified Clojure function directly
    Given the DataTwist source
      """
      result is clojure.string/upper-case "hello"
      """
    When it is evaluated
    Then the value of "result" is "HELLO"

  Scenario: Qualified Clojure function in a pipeline
    Given the DataTwist source
      """
      result is "hello"
        |> clojure.string/upper-case
      """
    When it is evaluated
    Then the value of "result" is "HELLO"

  Scenario: Qualified Clojure function with multiple arguments
    Given the DataTwist source
      """
      result is clojure.string/starts-with? "hello world" "hello"
      """
    When it is evaluated
    Then the value of "result" is true

  # --- 2B: require with alias ---

  Scenario: Require a Clojure namespace with alias
    Given the DataTwist source
      """
      require clojure.string as str
      result is str/upper-case "hello"
      """
    When it is evaluated
    Then the value of "result" is "HELLO"

  Scenario: Use aliased namespace function with multiple arguments
    Given the DataTwist source
      """
      require clojure.string as str
      result is str/join ", " ["a" "b" "c"]
      """
    When it is evaluated
    Then the value of "result" is "a, b, c"

  Scenario: Use aliased namespace in pipeline
    Given the DataTwist source
      """
      require clojure.string as str
      result is "hello world"
        |> str/upper-case
      """
    When it is evaluated
    Then the value of "result" is "HELLO WORLD"

  Scenario: Require must appear before other code
    Given the DataTwist source
      """
      require clojure.string as str
      name is str/upper-case "alice"
      """
    When it is evaluated
    Then the value of "name" is "ALICE"

  Scenario: Require after code is a parse error
    Given the DataTwist source
      """
      x is 42
      require clojure.string as str
      """
    When it is parsed
    Then a parse error is raised

  # --- 2C: Java interop ---

  Scenario: Call a Java instance method
    Given the DataTwist source
      """
      result is .toUpperCase "hello"
      """
    When it is evaluated
    Then the value of "result" is "HELLO"

  Scenario: Java instance method in pipeline
    Given the DataTwist source
      """
      result is "hello"
        |> .toUpperCase
      """
    When it is evaluated
    Then the value of "result" is "HELLO"

  Scenario: Java instance method with argument
    Given the DataTwist source
      """
      result is .contains "hello world" "world"
      """
    When it is evaluated
    Then the value of "result" is true

  Scenario: Java static method call
    Given the DataTwist source
      """
      result is Math/pow 2 10
      """
    When it is evaluated
    Then the value of "result" is 1024.0

  Scenario: Java static field access
    Given the DataTwist source
      """
      pi is Math/PI
      """
    When it is evaluated
    Then the value of "pi" is approximately 3.14159

  Scenario: Java constructor via dot suffix
    Given the DataTwist source
      """
      sb is java.lang.StringBuilder.
      """
    When it is evaluated
    Then the value of "sb" is a java.lang.StringBuilder instance

  # --- 2D: Keywords ---

  Scenario: Explicit keyword literal used with get
    Given the DataTwist source
      """
      user is {name: "Alice" age: 25}
      result is get user :name
      """
    When it is evaluated
    Then the value of "result" is "Alice"

  Scenario: Object keys are keywords under the hood
    Given the DataTwist source
      """
      user is {name: "Alice" age: 25}
      result is get user :name
      """
    When it is evaluated
    Then the value of "result" is "Alice"

  Scenario: DataTwist list is a Clojure vector at runtime
    Given the DataTwist source
      """
      items is [1 2 3]
      """
    When it is evaluated
    Then the runtime type of "items" is clojure.lang.PersistentVector

  Scenario: DataTwist object is a Clojure persistent map at runtime
    Given the DataTwist source
      """
      user is {name: "Alice"}
      """
    When it is evaluated
    Then the runtime type of "user" is clojure.lang.PersistentArrayMap

  Scenario: Pass DataTwist list directly to Clojure function
    Given the DataTwist source
      """
      require clojure.string as str
      words is ["hello" "world"]
      result is str/join " " words
      """
    When it is evaluated
    Then the value of "result" is "hello world"

  # ============================================================
  # SECTION 3: TRY-CATCH
  # ============================================================
  #
  # PRD: try-catch syntax:
  #   try <expr> catch <binding> -> <handler>
  #   try <expr> catch <Type> <binding> -> <handler>
  #   try <expr> catch <Type> <binding> -> <h1> catch <binding> -> <fallback>
  #   try <expr> catch <binding> -> <handler> finally <expr>
  #
  # Try-catch is an expression -- it returns a value.
  # `err` is a Clojure exception. Access .message field (= .getMessage).
  # `_` as catch binding ignores the error.
  # PRD corner case C5: `try` without `catch` (try-finally) is valid.
  # ============================================================

  Scenario: Simple try-catch returns handler value on exception
    Given the DataTwist source
      """
      result is try
        clojure.lang.RT/nth [] 99
      catch err -> "caught"
      """
    When it is evaluated
    Then the value of "result" is "caught"

  Scenario: Try-catch returns try-body value when no exception
    Given the DataTwist source
      """
      result is try 42 catch err -> -1
      """
    When it is evaluated
    Then the value of "result" is 42

  Scenario: Try-catch with error message access via .message
    Given the DataTwist source
      """
      result is try
        clojure.lang.RT/nth [] 99
      catch err ->
        err.message
      """
    When it is evaluated
    Then the value of "result" is a non-nil string

  Scenario: Try-catch with specific exception type
    Given the DataTwist source
      """
      result is try
        java.lang.Integer/parseInt "not-a-number"
      catch java.lang.NumberFormatException err ->
        "not found"
      catch err ->
        "unknown"
      """
    When it is evaluated
    Then the value of "result" is "not found"

  Scenario: Catch with unmatched typed handler falls through to generic catch
    Given the DataTwist source
      """
      result is try
        java.lang.Integer/parseInt "bad"
      catch java.io.IOException err ->
        "io error"
      catch err ->
        "fallback"
      """
    When it is evaluated
    Then the value of "result" is "fallback"

  Scenario: Try-catch with finally clause runs finally on success
    Given the DataTwist source
      """
      side is try
        42
      catch err -> -1
      finally 0
      """
    When it is evaluated
    Then the value of "side" is 42

  Scenario: Try-catch as expression in binding
    Given the DataTwist source
      """
      result is try
        java.lang.Integer/parseInt "bad"
      catch err -> 0
      """
    When it is evaluated
    Then the value of "result" is 0

  Scenario: Try-catch in pipeline inside a function
    Given the DataTwist source
      """
      safe-parse is [x -> try java.lang.Integer/parseInt x catch err -> 0]
      result is safe-parse "bad"
      """
    When it is evaluated
    Then the value of "result" is 0

  Scenario: Try-catch with wildcard binding ignores error
    Given the DataTwist source
      """
      result is try java.lang.Integer/parseInt "bad" catch _ -> -1
      """
    When it is evaluated
    Then the value of "result" is -1

  Scenario: Try with nil expression does not throw
    Given the DataTwist source
      """
      result is try nil catch err -> "caught"
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Try-catch with ex-data access
    Given the DataTwist source
      """
      result is try
        java.lang.Integer/parseInt "bad"
      catch err ->
        {caught: true message: err.message}
      """
    When it is evaluated
    Then the value of "result.caught" is true

  # ============================================================
  # SECTION 4: NIL SEMANTICS
  # ============================================================
  #
  # PRD Nil Semantics table:
  #   nil.field        => nil   (nil-tolerant field access)
  #   nil.a.b.c        => nil   (chain propagates)
  #   nil = nil        => true
  #   nil != 5         => true
  #   nil > 5          => false (no ordering for nil)
  #   nil and x        => nil   (short-circuit, Clojure)
  #   nil or x         => x     (short-circuit, Clojure)
  #   value ?? default => default if value is nil (nil coalescing)
  #   nil + 5          => 5     (nil coerces to identity element 0 for numbers)
  #   nil + "hi"       => "hi"  (nil coerces to identity element "" for strings)
  #   nil * 5          => 0     (nil coerces to 0)
  #   nil |> filter _  => []    (nil source = empty collection)
  #
  # Truthiness: only nil and false are falsy. 0, "", [], {} are truthy.
  # ============================================================

  # --- 4A: Nil-tolerant field access ---

  Scenario: Field access on nil returns nil
    Given the DataTwist expression "nil.name"
    When it is evaluated
    Then the result is nil

  Scenario: Deep chained field access on nil returns nil
    Given the DataTwist source
      """
      user is nil
      result is user.profile.address.city
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Field access where intermediate field is nil returns nil
    Given the DataTwist source
      """
      user is {name: "Alice" address: nil}
      result is user.address.city
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Field access on object with missing key returns nil
    Given the DataTwist source
      """
      user is {name: "Alice"}
      result is user.age
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Nil chain propagates through multiple accesses
    Given the DataTwist source
      """
      result is nil.a.b.c
      """
    When it is evaluated
    Then the value of "result" is nil

  # --- 4B: Nil in arithmetic ---

  Scenario: Nil plus number coerces nil to zero
    Given the DataTwist expression "nil + 5"
    When it is evaluated
    Then the result is 5

  Scenario: Nil plus string coerces nil to empty string
    Given the DataTwist expression "nil + \"hi\""
    When it is evaluated
    Then the result is "hi"

  Scenario: Nil times number coerces nil to zero
    Given the DataTwist expression "nil * 3"
    When it is evaluated
    Then the result is 0

  Scenario: Nil chain into arithmetic via nil coercion
    Given the DataTwist source
      """
      result is nil.name + 1
      """
    When it is evaluated
    Then the value of "result" is 1

  # --- 4C: Nil in comparison ---

  Scenario: Nil equals nil
    Given the DataTwist expression "nil = nil"
    When it is evaluated
    Then the result is true

  Scenario: Nil not-equals a value
    Given the DataTwist expression "nil != 5"
    When it is evaluated
    Then the result is true

  Scenario: Value not-equals nil
    Given the DataTwist expression "5 != nil"
    When it is evaluated
    Then the result is true

  Scenario: Value equals nil is false
    Given the DataTwist expression "5 = nil"
    When it is evaluated
    Then the result is false

  Scenario: Nil greater-than a value is nil (three-valued)
    Given the DataTwist expression "nil > 5"
    When it is evaluated
    Then the result is nil

  Scenario: Nil less-than a value is nil (three-valued)
    Given the DataTwist expression "nil < 5"
    When it is evaluated
    Then the result is nil

  Scenario: Nil greater-or-equal is nil (three-valued)
    Given the DataTwist expression "nil >= 0"
    When it is evaluated
    Then the result is nil

  Scenario: Nil less-or-equal is nil (three-valued)
    Given the DataTwist expression "nil <= 0"
    When it is evaluated
    Then the result is nil

  # --- 4D: Nil in logical operators ---

  Scenario: Nil is falsy in conditional context
    Given the DataTwist source
      """
      result is
        | nil -> "truthy"
        | _   -> "falsy"
      """
    When it is evaluated
    Then the value of "result" is "falsy"

  Scenario: Nil and true returns nil (short-circuit)
    Given the DataTwist expression "nil and true"
    When it is evaluated
    Then the result is nil

  Scenario: True and nil returns nil
    Given the DataTwist expression "true and nil"
    When it is evaluated
    Then the result is nil

  Scenario: Nil or true returns true
    Given the DataTwist expression "nil or true"
    When it is evaluated
    Then the result is true

  Scenario: Nil or false returns false
    Given the DataTwist expression "nil or false"
    When it is evaluated
    Then the result is false

  Scenario: Nil or nil returns nil
    Given the DataTwist expression "nil or nil"
    When it is evaluated
    Then the result is nil

  Scenario: False is distinct from nil
    Given the DataTwist expression "false = nil"
    When it is evaluated
    Then the result is false

  # --- 4E: Truthiness (Clojure-compatible) ---
  # PRD: "Truthiness: only nil and false are falsy. 0, "", [], {} are truthy (Clojure semantics)."

  Scenario: Zero is truthy
    Given the DataTwist source
      """
      result is
        | 0 -> "truthy"
        | _ -> "falsy"
      """
    When it is evaluated
    Then the value of "result" is "truthy"

  Scenario: Empty string is truthy
    Given the DataTwist source
      """
      result is
        | "" -> "truthy"
        | _  -> "falsy"
      """
    When it is evaluated
    Then the value of "result" is "truthy"

  Scenario: Empty list is truthy
    Given the DataTwist source
      """
      result is
        | [] -> "truthy"
        | _  -> "falsy"
      """
    When it is evaluated
    Then the value of "result" is "truthy"

  Scenario: Empty object is truthy
    Given the DataTwist source
      """
      result is
        | {} -> "truthy"
        | _  -> "falsy"
      """
    When it is evaluated
    Then the value of "result" is "truthy"

  # --- 4F: Nil coalescing operator `??` ---
  # PRD: "`??` -- `value ?? default` -- triggers on nil only"

  Scenario: Nil coalescing with nil left side returns default
    Given the DataTwist source
      """
      name is nil ?? "anonymous"
      """
    When it is evaluated
    Then the value of "name" is "anonymous"

  Scenario: Nil coalescing with non-nil left side returns left side
    Given the DataTwist source
      """
      name is "Alice" ?? "anonymous"
      """
    When it is evaluated
    Then the value of "name" is "Alice"

  Scenario: Nil coalescing does not trigger on false
    Given the DataTwist source
      """
      flag is false ?? true
      """
    When it is evaluated
    Then the value of "flag" is false

  Scenario: Nil coalescing does not trigger on zero
    Given the DataTwist source
      """
      n is 0 ?? 42
      """
    When it is evaluated
    Then the value of "n" is 0

  Scenario: Nil coalescing does not trigger on empty string
    Given the DataTwist source
      """
      s is "" ?? "default"
      """
    When it is evaluated
    Then the value of "s" is ""

  Scenario: Nil coalescing chains through multiple nils
    Given the DataTwist source
      """
      result is nil ?? nil ?? "fallback"
      """
    When it is evaluated
    Then the value of "result" is "fallback"

  Scenario: Nil coalescing chain all nil returns nil
    Given the DataTwist source
      """
      result is nil ?? nil ?? nil
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Nil coalescing with nil field access
    Given the DataTwist source
      """
      user is {name: nil}
      display is user.name ?? "unknown"
      """
    When it is evaluated
    Then the value of "display" is "unknown"

  # --- 4G: Nil in pipelines ---
  # PRD: "nil |> filter _ => [] (nil source = empty collection)"

  Scenario: Nil piped into filter returns empty collection
    Given the DataTwist source
      """
      result is nil |> filter _.active
      """
    When it is evaluated
    Then the value of "result" is []

  Scenario: Nil piped into map returns empty collection
    Given the DataTwist source
      """
      result is nil |> map _.name
      """
    When it is evaluated
    Then the value of "result" is []

  Scenario: Nil piped into nil-aware function
    Given the DataTwist source
      """
      result is nil |> count
      """
    When it is evaluated
    Then the value of "result" is 0

  # --- 4H: Empty collection vs nil ---

  Scenario: Empty list is not nil
    Given the DataTwist expression "[] = nil"
    When it is evaluated
    Then the result is false

  Scenario: Nil coalescing does not trigger on empty list
    Given the DataTwist source
      """
      items is []
      result is items ?? [1 2 3]
      """
    When it is evaluated
    Then the value of "result" is []

  Scenario: Nil coalescing does not trigger on empty object
    Given the DataTwist source
      """
      data is {}
      result is data ?? {default: true}
      """
    When it is evaluated
    Then the value of "result" is {}

  # ============================================================
  # SECTION 5: FORMAT FUNCTION
  # ============================================================
  #
  # PRD: "Strings: Plain + `format`. No interpolation."
  # PRD example: `greet is [name -> format "Hello, %s!" name]`
  # format maps to Clojure/Java String.format: %s, %d, %f, %.Nf.
  # ============================================================

  Scenario: Format with string substitution
    Given the DataTwist source
      """
      result is format "Hello, %s!" "Alice"
      """
    When it is evaluated
    Then the value of "result" is "Hello, Alice!"

  Scenario: Format with integer substitution
    Given the DataTwist source
      """
      result is format "age: %d" 25
      """
    When it is evaluated
    Then the value of "result" is "age: 25"

  Scenario: Format with float precision
    Given the DataTwist source
      """
      result is format "Price: $%.2f" 19.99
      """
    When it is evaluated
    Then the value of "result" is "Price: $19.99"

  Scenario: Format with multiple arguments
    Given the DataTwist source
      """
      name is "Alice"
      age is 30
      result is format "%s is %d years old" name age
      """
    When it is evaluated
    Then the value of "result" is "Alice is 30 years old"

  Scenario: Format with nil argument produces "null" string
    Given the DataTwist source
      """
      result is format "value: %s" nil
      """
    When it is evaluated
    Then the value of "result" is "value: null"

  Scenario: Format in a function body
    Given the DataTwist source
      """
      greet is [name -> format "Hello, %s!" name]
      result is greet "Bob"
      """
    When it is evaluated
    Then the value of "result" is "Hello, Bob!"

  # ============================================================
  # SECTION 6: PROGRAM STRUCTURE
  # ============================================================
  #
  # PRD: "A DataTwist program is a sequence of top-level forms."
  # "Top-level forms: `require`, `name is expr`, bare expressions."
  # "Last expression = program result"
  # "Function bodies: sequential `is` bindings + final expression"
  # "No `main` function required."
  # ============================================================

  # --- 6A: Top-level forms ---

  Scenario: Multiple top-level bindings evaluated in sequence
    Given the DataTwist source
      """
      x is 1
      y is 2
      z is x + y
      """
    When it is evaluated
    Then the value of "z" is 3

  Scenario: Blank lines between top-level forms are allowed
    Given the DataTwist source
      """
      x is 1

      y is 2

      z is x + y
      """
    When it is evaluated
    Then the value of "z" is 3

  Scenario: Last expression is the program result
    Given the DataTwist source
      """
      data is [1 2 3 4 5]
      data |> filter [x -> x > 3] |> count
      """
    When it is evaluated
    Then the program result is 2

  Scenario: File with only an expression returns its value
    Given the DataTwist source
      """
      1 + 2 + 3
      """
    When it is evaluated
    Then the program result is 6

  Scenario: Function body with internal bindings returns final expression
    Given the DataTwist source
      """
      process is [data ->
        doubled is data * 2
        doubled + 1
      ]
      result is process 5
      """
    When it is evaluated
    Then the value of "result" is 11

  # ============================================================
  # SECTION 7: INTEGRATION SCENARIOS
  # ============================================================

  Scenario: Interop with nil coalescing and format
    Given the DataTwist source
      """
      require clojure.string as str
      name is nil
      display is format "Hello, %s!" (name ?? "guest")
      """
    When it is evaluated
    Then the value of "display" is "Hello, guest!"

  Scenario: Try-catch combined with nil coalescing
    Given the DataTwist source
      """
      result is try
        java.lang.Integer/parseInt "bad"
      catch err -> nil
      safe is result ?? -1
      """
    When it is evaluated
    Then the value of "safe" is -1

  Scenario: Qualified Clojure call combined with nil coalescing
    Given the DataTwist source
      """
      input is nil
      result is (input ?? "") |> clojure.string/upper-case
      """
    When it is evaluated
    Then the value of "result" is ""

  Scenario: Java interop inside a pipeline function
    Given the DataTwist source
      """
      result is ["hello" "world"]
        |> map [s -> .toUpperCase s]
      """
    When it is evaluated
    Then the value of "result" is ["HELLO" "WORLD"]

  ## Section 4I: Nil Handling Functions

  Scenario: fill-nil replaces nil elements in a list with a default
    Given the DataTwist expression "[1 nil 3 nil 5] |> fill-nil 0"
    When it is evaluated
    Then the result is [1, 0, 3, 0, 5]

  Scenario: fill-nil on a scalar nil returns the default
    Given the DataTwist expression "nil |> fill-nil 0"
    When it is evaluated
    Then the result is 0

  Scenario: fill-nil on an object replaces nil-valued fields
    Given the DataTwist expression "{a: 1 b: nil c: 3} |> fill-nil 0"
    When it is evaluated
    Then the result is an object with a = 1, b = 0, c = 3

  Scenario: fill-nil on a list with no nils returns unchanged
    Given the DataTwist expression "[1 2 3] |> fill-nil 0"
    When it is evaluated
    Then the result is [1, 2, 3]

  Scenario: skip-nil removes nil elements from a list
    Given the DataTwist expression "[1 nil 3 nil 5] |> skip-nil"
    When it is evaluated
    Then the result is [1, 3, 5]

  Scenario: skip-nil on an empty list returns empty list
    Given the DataTwist expression "[] |> skip-nil"
    When it is evaluated
    Then the result is []

  Scenario: skip-nil on nil returns empty list
    Given the DataTwist expression "nil |> skip-nil"
    When it is evaluated
    Then the result is []

  Scenario: skip-nil on an object removes nil-valued keys
    Given the DataTwist expression "{a: 1 b: nil c: 3} |> skip-nil"
    When it is evaluated
    Then the result has keys a and c only

  Scenario: skip-nil in pipeline chain
    Given the DataTwist expression "[1 nil 2 nil 3] |> skip-nil |> sum"
    When it is evaluated
    Then the result is 6
