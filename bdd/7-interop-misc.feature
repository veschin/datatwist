Feature: Clojure Interop, Comments, Try-Catch, Nil Semantics, and Miscellaneous
  As a DataTwist user
  I want seamless Clojure/JVM interop, clear error handling,
  predictable nil behavior, comments, and string formatting
  So that I can build robust data pipelines on the JVM ecosystem

  # ============================================================
  # SECTION 1: COMMENTS
  # ============================================================
  #
  # Decision: Use `//` for line comments.
  # Rationale:
  #   - `;;` conflicts with Clojure (users may confuse runtime vs source)
  #   - `#` conflicts with potential set literal `#{...}` and regex `#"..."`
  #   - `//` is universally recognized (JS, C, Java, Rust, Go, Kotlin)
  #   - Easy to parse: consume from `//` to end-of-line
  #   - No block comments. Keep it simple. Use multiple `//` lines.
  #   - Comments are STRIPPED during parsing (not in AST).
  #     Tooling can re-parse with comment-preserving mode later.
  # ============================================================

  Scenario: Single-line comment at top of file
    Given the DataTwist source
      """
      // This is a comment
      x is 42
      """
    When it is parsed
    Then it parses successfully
    And the comment is not present in the AST
    And the result is equivalent to Clojure
      """
      (def x 42)
      """

  Scenario: Single-line comment after code on same line
    Given the DataTwist source
      """
      x is 42 // the answer
      """
    When it is parsed
    Then it parses successfully
    And the result is equivalent to Clojure
      """
      (def x 42)
      """

  Scenario: Comment between pipeline steps
    Given the DataTwist source
      """
      result is users
        |> filter _.age > 18
        // only take names
        |> map _.name
      """
    When it is parsed
    Then it parses successfully
    And the comment is not present in the AST

  Scenario: Multiple consecutive comment lines
    Given the DataTwist source
      """
      // Section: data processing
      // Author: team
      // Date: 2026-01-15
      data is [1 2 3]
      """
    When it is parsed
    Then it parses successfully
    And the result contains a binding for "data"

  Scenario: Comment on otherwise blank line inside object literal
    Given the DataTwist source
      """
      user is {
        name: "Alice"
        // age will be added later
        status: "active"
      }
      """
    When it is parsed
    Then it parses successfully

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
    When it is parsed
    Then it parses successfully

  Scenario: A line containing only a comment
    Given the DataTwist source
      """
      // just a comment, nothing else
      """
    When it is parsed
    Then it parses successfully
    And the AST is empty

  # ============================================================
  # SECTION 2: CLOJURE INTEROP
  # ============================================================
  #
  # Decision: THREE mechanisms, layered.
  #
  # (A) Qualified Clojure function calls -- direct:
  #     clojure.string/upper-case "hello"
  #     Namespaced identifiers (containing `/`) are treated as
  #     direct Clojure var references. No prefix needed.
  #
  # (B) `require` for aliased access:
  #     require clojure.string as str
  #     str/upper-case "hello"
  #
  # (C) Java interop via dot-method syntax:
  #     .toUpperCase "hello"         => (.toUpperCase "hello")
  #     .length "hello"              => (.length "hello")
  #     Math/PI                      => Math/PI  (static field)
  #     Math/pow 2 10                => (Math/pow 2 10)
  #
  # Keywords: `:keyword` syntax is supported for interop.
  # DataTwist object keys are keywords under the hood.
  # Explicit `:name` syntax is available when calling Clojure
  # functions that expect keyword arguments.
  #
  # Collections: DataTwist objects ARE Clojure persistent maps
  # with keyword keys. DataTwist lists ARE Clojure vectors.
  # Interop is seamless -- no conversion needed.
  # ============================================================

  # --- 2A: Direct qualified Clojure function calls ---

  Scenario: Call a qualified Clojure function directly
    Given the DataTwist source
      """
      result is clojure.string/upper-case "hello"
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (clojure.string/upper-case "hello"))
      """

  Scenario: Qualified Clojure function in a pipeline
    Given the DataTwist source
      """
      result is "hello world"
        |> clojure.string/upper-case
        |> clojure.string/reverse
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (-> "hello world"
                      clojure.string/upper-case
                      clojure.string/reverse))
      """

  Scenario: Clojure function with multiple arguments in pipeline
    Given the DataTwist source
      """
      result is "hello world"
        |> clojure.string/split #" "
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (-> "hello world"
                      (clojure.string/split #" ")))
      """

  # --- 2B: require with alias ---

  Scenario: Require a Clojure namespace with alias
    Given the DataTwist source
      """
      require clojure.string as str

      result is str/upper-case "hello"
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (require '[clojure.string :as str])
      (def result (str/upper-case "hello"))
      """

  Scenario: Require multiple namespaces
    Given the DataTwist source
      """
      require clojure.string as str
      require clojure.set as cset

      result is str/join ", " (cset/intersection #{1 2 3} #{2 3 4})
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (require '[clojure.string :as str])
      (require '[clojure.set :as cset])
      (def result (str/join ", " (cset/intersection #{1 2 3} #{2 3 4})))
      """

  Scenario: Use aliased namespace in pipeline
    Given the DataTwist source
      """
      require clojure.string as str

      result is "hello world"
        |> str/upper-case
        |> str/reverse
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (require '[clojure.string :as str])
      (def result (-> "hello world"
                      str/upper-case
                      str/reverse))
      """

  # --- 2C: Java interop ---

  Scenario: Call a Java instance method
    Given the DataTwist source
      """
      result is .toUpperCase "hello"
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (.toUpperCase "hello"))
      """

  Scenario: Java instance method in pipeline
    Given the DataTwist source
      """
      result is "hello"
        |> .toUpperCase
        |> .length
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (-> "hello"
                      .toUpperCase
                      .length))
      """

  Scenario: Java static method call
    Given the DataTwist source
      """
      result is Math/pow 2 10
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (Math/pow 2 10))
      """

  Scenario: Java static field access
    Given the DataTwist source
      """
      pi is Math/PI
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def pi Math/PI)
      """

  Scenario: Java constructor via Clojure interop
    Given the DataTwist source
      """
      date is java.util.Date.
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def date (java.util.Date.))
      """

  # --- 2D: Explicit keywords ---

  Scenario: Explicit keyword literal
    Given the DataTwist source
      """
      result is get user :name
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (get user :name))
      """

  Scenario: Keyword as function (Clojure idiom)
    Given the DataTwist source
      """
      result is users
        |> map :name
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (-> users (map :name)))
      """

  Scenario: Object keys are keywords under the hood
    Given the DataTwist source
      """
      user is {name: "Alice" age: 25}
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def user {:name "Alice" :age 25})
      """

  # --- 2E: Seamless collection interop ---

  Scenario: DataTwist list is a Clojure vector
    Given the DataTwist source
      """
      items is [1 2 3]
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def items [1 2 3])
      """
    And the runtime type of "items" is clojure.lang.PersistentVector

  Scenario: DataTwist object is a Clojure map with keyword keys
    Given the DataTwist source
      """
      user is {name: "Alice"}
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def user {:name "Alice"})
      """
    And the runtime type of "user" is clojure.lang.PersistentArrayMap

  Scenario: Pass DataTwist data directly to Clojure functions
    Given the DataTwist source
      """
      require clojure.string as str

      words is ["hello" "world"]
      result is str/join " " words
      """
    When it is compiled and run
    Then the value of "result" is "hello world"

  # ============================================================
  # SECTION 3: TRY-CATCH
  # ============================================================
  #
  # Syntax:
  #   try <expr> catch <binding> -> <handler-expr>
  #   try <expr> catch <Type> <binding> -> <handler-expr>
  #   try <expr> catch <Type> <binding> -> <handler> catch <binding> -> <fallback>
  #   try <expr> catch <binding> -> <handler> finally <expr>
  #
  # `err` is a Clojure exception (java.lang.Throwable).
  # Access fields: err.message (= .getMessage), err.class (= class name string).
  # For structured error data, use `ex-data err` (Clojure ex-info maps).
  #
  # Try-catch is an expression -- it returns a value.
  # ============================================================

  Scenario: Simple try-catch
    Given the DataTwist source
      """
      data is try read-csv "data.csv" catch err -> []
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def data (try (read-csv "data.csv")
                     (catch Exception err [])))
      """

  Scenario: Try-catch with error message access
    Given the DataTwist source
      """
      result is try
        parse-json input
      catch err ->
        {error: true message: err.message}
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (try
                    (parse-json input)
                    (catch Exception err
                      {:error true :message (.getMessage err)})))
      """

  Scenario: Try-catch with specific exception type
    Given the DataTwist source
      """
      result is try
        read-file path
      catch java.io.FileNotFoundException err ->
        {error: "not found" path: path}
      catch err ->
        {error: "unknown" message: err.message}
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (try
                    (read-file path)
                    (catch java.io.FileNotFoundException err
                      {:error "not found" :path path})
                    (catch Exception err
                      {:error "unknown" :message (.getMessage err)})))
      """

  Scenario: Try-catch with finally clause
    Given the DataTwist source
      """
      result is try
        process data
      catch err -> nil
      finally close! connection
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (try
                    (process data)
                    (catch Exception err nil)
                    (finally (close! connection))))
      """

  Scenario: Try-catch as expression in binding
    Given the DataTwist source
      """
      count is try
        items |> filter _.valid |> count
      catch err -> 0
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def count (try
                   (-> items (filter (fn [x] (:valid x))) count)
                   (catch Exception err 0)))
      """

  Scenario: Try-catch in pipeline
    Given the DataTwist source
      """
      result is items
        |> map [x -> try parse-int x catch err -> 0]
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (-> items
                      (map (fn [x] (try (parse-int x)
                                        (catch Exception err 0))))))
      """

  Scenario: Nested try-catch
    Given the DataTwist source
      """
      result is try
        try parse-json raw
        catch err -> try parse-yaml raw
                     catch err -> nil
      catch err -> nil
      """
    When it is compiled
    Then it parses successfully
    And no compile error is raised

  Scenario: Try-catch with wildcard binding (ignore error)
    Given the DataTwist source
      """
      result is try risky-op data catch _ -> default-value
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (try (risky-op data)
                       (catch Exception _ default-value)))
      """

  Scenario: Access ex-data from Clojure ex-info exceptions
    Given the DataTwist source
      """
      result is try
        validate record
      catch err ->
        {type: (ex-data err).type code: (ex-data err).code}
      """
    When it is compiled
    Then it parses successfully

  # ============================================================
  # SECTION 4: NIL SEMANTICS
  # ============================================================
  #
  # DataTwist is a nil-tolerant data language.
  # Core principle: nil propagates quietly through field access
  # and collections, but raises errors in arithmetic.
  #
  # Rules:
  #   1. Field access on nil: nil.anything => nil (safe chaining)
  #   2. Arithmetic with nil: nil + 5 => 5 (nil coerces to identity element: 0 for numbers, "" for strings)
  #   3. Comparison: nil = nil => true; nil > 5 => false; nil != 5 => true
  #   4. Logical: nil is falsy (like Clojure). nil and x => nil. nil or x => x.
  #   5. Pipeline: nil |> f => f(nil) -- no special behavior, f decides
  #   6. Nil coalescing: `??` operator. x ?? default.
  #   7. [] != nil. Empty list is a value. nil is absence of value.
  #   8. Truthiness: nil and false are falsy. 0, "", [] are truthy (like Clojure).
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
      result is user.profile.address.city.zip
      """
    When it is evaluated
    Then the value of "result" is nil

  Scenario: Field access where intermediate is nil returns nil
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

  # --- 4B: Nil in arithmetic ---

  Scenario: Nil in addition coerces to identity
    Given the DataTwist expression "nil + 5"
    When it is evaluated
    Then it evaluates to 5

  Scenario: Nil in subtraction raises error
    Given the DataTwist expression "5 - nil"
    When it is evaluated
    Then an error is raised with message containing "nil"

  Scenario: Nil in multiplication coerces to identity
    Given the DataTwist expression "nil * 3"
    When it is evaluated
    Then it evaluates to 0

  Scenario: Nil in division raises error
    Given the DataTwist expression "10 / nil"
    When it is evaluated
    Then an error is raised with message containing "nil"

  Scenario: Nil in modulo raises error
    Given the DataTwist expression "nil % 2"
    When it is evaluated
    Then an error is raised with message containing "nil"

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

  Scenario: Nil greater-than a value is false
    Given the DataTwist expression "nil > 5"
    When it is evaluated
    Then the result is false

  Scenario: Nil less-than a value is false
    Given the DataTwist expression "nil < 5"
    When it is evaluated
    Then the result is false

  Scenario: Nil greater-or-equal is false
    Given the DataTwist expression "nil >= 0"
    When it is evaluated
    Then the result is false

  Scenario: Nil less-or-equal is false
    Given the DataTwist expression "nil <= 0"
    When it is evaluated
    Then the result is false

  # --- 4D: Nil in logical operators ---

  Scenario: Nil is falsy
    Given the DataTwist source
      """
      result is
        | nil  -> "truthy"
        | _    -> "falsy"
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

  Scenario: False is falsy but distinct from nil
    Given the DataTwist expression "false = nil"
    When it is evaluated
    Then the result is false

  # --- 4E: Truthiness (Clojure-compatible) ---

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

  Scenario: Nil coalescing with nil left side
    Given the DataTwist source
      """
      name is nil ?? "anonymous"
      """
    When it is evaluated
    Then the value of "name" is "anonymous"

  Scenario: Nil coalescing with non-nil left side
    Given the DataTwist source
      """
      name is "Alice" ?? "anonymous"
      """
    When it is evaluated
    Then the value of "name" is "Alice"

  Scenario: Nil coalescing with false left side preserves false
    Given the DataTwist source
      """
      flag is false ?? true
      """
    When it is evaluated
    Then the value of "flag" is false

  Scenario: Nil coalescing chains
    Given the DataTwist source
      """
      result is nil ?? nil ?? "fallback"
      """
    When it is evaluated
    Then the value of "result" is "fallback"

  Scenario: Nil coalescing with field access
    Given the DataTwist source
      """
      user is {name: nil}
      display is user.name ?? user.email ?? "unknown"
      """
    When it is evaluated
    Then the value of "display" is "unknown"

  Scenario: Nil coalescing in pipeline
    Given the DataTwist source
      """
      result is users
        |> find _.id = 42
        |> [u -> u ?? {name: "guest" id: 0}]
      """
    When it is compiled
    Then it parses successfully

  Scenario: Nil coalescing compiles correctly
    Given the DataTwist source
      """
      x is a ?? b
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def x (if (some? a) a b))
      """

  # --- 4G: Nil in pipelines ---

  Scenario: Nil piped into a function calls the function with nil
    Given the DataTwist source
      """
      result is nil |> count
      """
    When it is evaluated
    Then the result is 0
    # count(nil) => 0 in Clojure, which is the correct behavior

  Scenario: Nil piped into filter returns empty list
    Given the DataTwist source
      """
      result is nil |> filter _.active
      """
    When it is evaluated
    Then the value of "result" is []
    # filter on nil seq returns empty seq in Clojure

  Scenario: Nil piped into map returns empty list
    Given the DataTwist source
      """
      result is nil |> map _.name
      """
    When it is evaluated
    Then the value of "result" is []

  # --- 4H: Empty list vs nil ---

  Scenario: Empty list is not nil
    Given the DataTwist expression "[] = nil"
    When it is evaluated
    Then the result is false

  Scenario: Empty list is a value
    Given the DataTwist source
      """
      items is []
      result is items ?? [1 2 3]
      """
    When it is evaluated
    Then the value of "result" is []
    # [] is not nil, so ?? does not trigger

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
  # Decision: Printf-style with `%s`, `%d`, `%f`, `%.Nf`.
  # Rationale:
  #   - Familiar across many languages (C, Java, Python, Clojure)
  #   - Maps directly to Clojure's `(format ...)` / Java's String.format
  #   - No template strings / interpolation -- keeps parser simple
  #   - Positional `{0}` adds complexity without much benefit here
  # ============================================================

  Scenario: Format with string substitution
    Given the DataTwist source
      """
      result is format "Hello, %s!" "Alice"
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (format "Hello, %s!" "Alice"))
      """

  Scenario: Format with multiple arguments
    Given the DataTwist source
      """
      result is format "%s is %d years old" name age
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (format "%s is %d years old" name age))
      """

  Scenario: Format with float precision
    Given the DataTwist source
      """
      result is format "Price: $%.2f" 19.99
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def result (format "Price: $%.2f" 19.99))
      """

  Scenario: Format in pipeline
    Given the DataTwist source
      """
      result is users
        |> map [u -> format "%s (%d)" u.name u.age]
      """
    When it is compiled
    Then it parses successfully

  Scenario: Format with nil argument produces "null" string
    Given the DataTwist source
      """
      result is format "value: %s" nil
      """
    When it is evaluated
    Then the value of "result" is "value: null"
    # Java String.format behavior: nil/%s => "null"

  # ============================================================
  # SECTION 6: MISCELLANEOUS SYNTAX AND PROGRAM STRUCTURE
  # ============================================================
  #
  # Line endings: SIGNIFICANT for pipeline parsing.
  #   Newline + indent signals pipeline continuation.
  #   Newline at top level separates statements.
  #
  # Semicolons: NOT used. Not a separator, not a terminator.
  #
  # Multiple expressions: newline-separated at top level.
  #   Blank lines are allowed between top-level forms.
  #
  # Entry point: No `main` function required.
  #   A .dtw file is a sequence of top-level bindings and expressions.
  #   The last expression's value is the program's result (like a script).
  #   For REPL: each entered expression is evaluated and its value shown.
  #
  # REPL: Shows the value of the last expression. Bindings print nothing
  #   unless the binding's value is explicitly requested.
  # ============================================================

  # --- 6A: Program structure ---

  Scenario: Multiple top-level bindings separated by newlines
    Given the DataTwist source
      """
      x is 1
      y is 2
      z is x + y
      """
    When it is compiled
    Then the result is equivalent to Clojure
      """
      (def x 1)
      (def y 2)
      (def z (+ x y))
      """

  Scenario: Blank lines between top-level forms are allowed
    Given the DataTwist source
      """
      x is 1

      y is 2

      z is x + y
      """
    When it is parsed
    Then it parses successfully
    And the result contains 3 top-level bindings

  Scenario: Last expression is the program result
    Given the DataTwist source
      """
      data is [1 2 3 4 5]
      data |> filter [x -> x > 3] |> count
      """
    When it is evaluated
    Then the program result is 2

  Scenario: File with only an expression (no bindings)
    Given the DataTwist source
      """
      1 + 2 + 3
      """
    When it is evaluated
    Then the program result is 6

  # --- 6B: Line ending significance ---

  Scenario: Newline plus indent continues a pipeline
    Given the DataTwist source
      """
      result is data
        |> filter _.active
        |> count
      """
    When it is parsed
    Then it parses as a single pipeline with 2 steps

  Scenario: Newline without indent starts a new top-level form
    Given the DataTwist source
      """
      a is data |> count
      b is data |> first
      """
    When it is parsed
    Then it parses as 2 separate bindings

  # --- 6C: REPL behavior ---

  Scenario: REPL shows value of expression
    Given the REPL session
    When the user enters "1 + 2"
    Then the REPL displays 3

  Scenario: REPL binding does not auto-print
    Given the REPL session
    When the user enters "x is 42"
    Then the REPL displays nothing
    # Or optionally: displays "x = 42" as confirmation. TBD.

  Scenario: REPL shows last binding value on request
    Given the REPL session
    When the user enters "x is 42"
    And the user enters "x"
    Then the REPL displays 42

  # --- 6D: require must appear at top of file ---

  Scenario: Require statements appear before other code
    Given the DataTwist source
      """
      require clojure.string as str

      name is str/upper-case "hello"
      """
    When it is parsed
    Then it parses successfully

  Scenario: Require after code is a parse error
    Given the DataTwist source
      """
      x is 42
      require clojure.string as str
      """
    When it is parsed
    Then a parse error is raised

  # --- 6E: Trailing whitespace and edge cases ---

  Scenario: Trailing whitespace on lines does not cause errors
    Given the DataTwist source with trailing spaces
      """
      x is 42
      y is 10
      """
    When it is parsed
    Then it parses successfully

  Scenario: Windows-style line endings are accepted
    Given the DataTwist source with CRLF line endings
      """
      x is 42\r\ny is 10\r\n
      """
    When it is parsed
    Then it parses successfully

  Scenario: Tab indentation in pipeline
    Given the DataTwist source with tab indentation
      """
      result is data
      	|> filter _.active
      	|> count
      """
    When it is parsed
    Then it parses successfully

  # ============================================================
  # SECTION 7: INTEGRATION SCENARIOS
  # ============================================================

  Scenario: Real-world data processing with interop and error handling
    Given the DataTwist source
      """
      require clojure.string as str

      process-user is [raw ->
        try
          {
            name: str/trim raw.name ?? "unknown"
            email: str/lower-case raw.email ?? ""
            age: raw.age ?? 0
          }
        catch err ->
          {name: "error" email: "" age: 0}
      ]

      result is raw-users
        |> filter [u -> u != nil]
        |> map process-user
        |> filter _.name != "error"
      """
    When it is compiled
    Then it parses successfully
    And no compile error is raised

  Scenario: Pipeline with nil coalescing and format
    Given the DataTwist source
      """
      result is users
        |> map [u ->
          {
            display: format "%s (%s)" (u.name ?? "anon") (u.role ?? "user")
            active: u.active ?? false
          }
        ]
      """
    When it is compiled
    Then it parses successfully

  Scenario: Error handling with Java exception types
    Given the DataTwist source
      """
      result is try
        .parseInt java.lang.Integer input
      catch java.lang.NumberFormatException err ->
        format "Not a number: %s" input
      catch err ->
        format "Unexpected error: %s" err.message
      """
    When it is compiled
    Then it parses successfully

  Scenario: Full interop round-trip -- DataTwist to Clojure and back
    Given the DataTwist source
      """
      require clojure.string as str

      words is str/split "hello world foo" #" "
      result is words
        |> map str/upper-case
        |> str/join ", "
      """
    When it is evaluated
    Then the value of "result" is "HELLO, WORLD, FOO"


# ============================================================
# OPEN QUESTIONS
# ============================================================
#
# Q1: Should `require` support `:refer` for unqualified access?
#     e.g., `require clojure.string refer [join split]`
#     Then: `join ", " items` instead of `str/join ", " items`.
#     Risk: name collision with DataTwist built-ins.
#     Recommendation: defer. Alias-only is sufficient for v1.
#
# Q2: Should there be a `use` keyword as sugar for require+refer-all?
#     Recommendation: No. Clojure itself deprecated this pattern.
#
# Q3: Regex literals -- `#"pattern"` shown in split example.
#     Should DataTwist support regex literals natively?
#     Or require Clojure interop: `re-pattern "\\d+"`?
#     Recommendation: Support `#"..."` -- it is essential for string
#     processing and maps directly to java.util.regex.Pattern.
#
# Q4: How does `??` interact with `and`/`or` precedence?
#     `a ?? b or c` -- is it `(a ?? b) or c` or `a ?? (b or c)`?
#     Recommendation: `??` binds tighter than `or` but looser than
#     comparison. Precedence: arithmetic > comparison > ?? > and > or.
#
# Q5: Should `try` without `catch` be allowed?
#     e.g., `try expr finally cleanup` (catch-less try-finally).
#     In Clojure this is valid. Recommendation: allow it.
#
# Q6: Should nil-tolerant field access be opt-out?
#     Some users may want strict field access that throws on nil.
#     Possible: `user!.name` for strict access (throws if user is nil).
#     Recommendation: defer. Nil-tolerant by default is the right
#     choice for a data language. Strict access can be added later.
#
# Q7: Calling DataTwist from Clojure -- is this needed now?
#     If DataTwist compiles to Clojure `def` forms in a namespace,
#     Clojure code can `require` that namespace and call functions.
#     Recommendation: defer explicit support. Focus on DataTwist
#     calling Clojure first. The compiled output is already Clojure.
#
# Q8: Should `??` only check for nil, or for nil-and-false (like `or`)?
#     Decision above: `??` checks ONLY for nil (uses `some?`).
#     `false ?? true` => `false` (false is not nil).
#     This differs from `or` where `false or true` => `true`.
#
# Q9: Sets -- `#{1 2 3}` syntax. Needed for interop with Clojure
#     functions that return/expect sets (e.g., clojure.set namespace).
#     Recommendation: add set literals in a future feature area.
#     For now, users can call `set [1 2 3]` to convert.
#
# Q10: Comments -- should `//` be allowed inside strings?
#      `"url: https://example.com"` -- the `//` inside the string
#      must NOT be treated as a comment. The lexer must handle this.
#      This is standard behavior and should be straightforward to
#      implement since string parsing happens before comment stripping.
#
# ============================================================
# CLOJURE MAPPING REFERENCE
# ============================================================
#
# DataTwist                          | Clojure
# -----------------------------------|----------------------------------
# // comment                         | ; comment
# require ns as alias                | (require '[ns :as alias])
# clojure.string/upper-case x        | (clojure.string/upper-case x)
# alias/fn x                         | (alias/fn x)
# .method obj                        | (.method obj)
# Class/staticMethod args            | (Class/staticMethod args)
# Class/FIELD                        | Class/FIELD
# ClassName.                          | (ClassName.)
# :keyword                           | :keyword
# try e catch err -> h               | (try e (catch Exception err h))
# try e catch T err -> h             | (try e (catch T err h))
# try e catch err -> h finally f     | (try e (catch Exception err h) (finally f))
# nil.field                          | (get nil :field) => nil
# a ?? b                             | (if (some? a) a b)
# format "..." args                  | (format "..." args)
# x is 42                            | (def x 42)
# {key: val}                         | {:key val}
# [1 2 3]                            | [1 2 3]
# nil = nil                          | (= nil nil) => true
# nil > 5                            | false (custom, not Clojure default)
# nil + 5                            | 5 (custom, nil coerces to 0)
# nil and x                          | (and nil x) => nil
# nil or x                           | (or nil x) => x
#
# ============================================================
# CORNER CASES AND EDGE BEHAVIORS
# ============================================================
#
# C1: `nil.nil` -- field named "nil" on nil object.
#     Result: nil. The field name "nil" is just an identifier.
#
# C2: `nil ?? nil ?? nil` -- chain of nil coalescing all nil.
#     Result: nil. All alternatives exhausted.
#
# C3: `try nil catch err -> "caught"` -- try with nil expression.
#     Result: nil. No exception thrown. Catch is not triggered.
#
# C4: `try (1 / 0) catch err -> err.message`
#     Result: "Divide by zero" (ArithmeticException message).
#
# C5: `nil |> [x -> x + 1]` -- nil piped into function with arithmetic.
#     Result: 1. nil coerces to 0 in arithmetic, so 0 + 1 = 1.
#
# C6: `{a: 1} ?? {b: 2}` -- non-nil map with coalescing.
#     Result: {a: 1}. The left side is not nil.
#
# C7: `false ?? "default"` -- false is not nil.
#     Result: false. `??` only triggers on nil, not on false.
#
# C8: `0 ?? 42` -- zero is not nil.
#     Result: 0. `??` only triggers on nil.
#
# C9: `"" ?? "default"` -- empty string is not nil.
#     Result: "". `??` only triggers on nil.
#
# C10: `nil.name.length + 1` -- nil chain into arithmetic.
#      `nil.name` => nil, `nil.length` => nil, `nil + 1` => 1. Nil coerces to 0, so 0 + 1 = 1.
#
# C11: Comment inside string literal:
#      `x is "hello // world"` -- the `//` is part of the string.
#      Result: x binds to "hello // world".
#
# C12: `try` with no matching catch type:
#      `try (throw-io-error) catch NumberFormatException e -> "nfe"`
#      Behavior: exception propagates up (not caught). Same as Clojure/Java.
#
# C13: `format` with wrong number of arguments:
#      `format "%s %s" "only-one"` -- runtime error from Java String.format.
#      MissingFormatArgumentException. Can be caught with try-catch.
#
# C14: `err.message` on an exception with no message:
#      Returns nil. (.getMessage ex) returns null in Java for some exceptions.
#
# C15: Deeply nested nil coalescing in pipeline:
#      `data |> map [x -> x.a.b.c ?? x.d.e ?? "none"]`
#      Each `??` is evaluated left to right. If x.a.b.c is nil,
#      try x.d.e. If that is also nil, use "none".
#
# C16: `require` of non-existent namespace:
#      `require nonexistent.ns as ns`
#      Runtime error: FileNotFoundException (namespace not found).
#      Should be reported clearly at compile/load time.
#
# C17: Shadowing a built-in with require alias:
#      `require some.lib as map` -- alias "map" shadows built-in map.
#      Recommendation: warn but allow. User takes responsibility.
#
# C18: `nil |> filter _.active |> count` -- nil into filter into count.
#      filter(nil, ...) => () (empty seq), count(()) => 0.
#      Result: 0. This is natural Clojure behavior.
#
# C19: Multiple `finally` clauses:
#      `try e catch err -> h finally a finally b` -- parse error.
#      Only one `finally` clause is allowed.
#
# C20: `try` as the last expression in a file (program result):
#      `try compute-result catch err -> default-result`
#      The program result is whatever the try-catch evaluates to.
