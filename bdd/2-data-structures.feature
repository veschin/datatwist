Feature: Data Structures
  DataTwist provides objects (maps), lists (vectors), and field access as
  first-class data structures. Objects use postfix-colon key syntax, elements
  are space-separated (no commas), and field access uses nil-tolerant dot
  notation. Under the hood objects compile to Clojure maps with keyword keys,
  and lists compile to Clojure vectors.

  # ---------------------------------------------------------------------------
  # Objects (Maps)
  # ---------------------------------------------------------------------------

  Scenario: Empty object
    Given the expression "{}"
    When it is evaluated
    Then the result is an empty object
    And it compiles to Clojure "{}"

  Scenario: Object with a single field
    Given the expression "{name: "Alice"}"
    When it is evaluated
    Then the result is an object with field "name" equal to "Alice"
    And it compiles to Clojure "{:name "Alice"}"

  Scenario: Object with multiple fields
    Given the expression "{name: "Alice" age: 25 active: true}"
    When it is evaluated
    Then the result is an object with 3 fields
    And field "name" equals "Alice"
    And field "age" equals 25
    And field "active" equals true
    And it compiles to Clojure "{:name "Alice" :age 25 :active true}"

  Scenario: Object field values are arbitrary expressions
    Given the binding "x is 10"
    And the expression "{doubled: x * 2 name: "Alice"}"
    When it is evaluated
    Then field "doubled" equals 20
    And field "name" equals "Alice"

  Scenario: Object field value is a variable reference
    Given the binding "city is "Moscow""
    And the expression "{location: city}"
    When it is evaluated
    Then field "location" equals "Moscow"
    And it compiles to Clojure "{:location city}"

  Scenario: Object field value distinguishes variable from literal
    Given the binding "value is 42"
    And the expression "{a: value b: "value"}"
    When it is evaluated
    Then field "a" equals 42
    And field "b" equals "value"

  Scenario: Nested objects
    Given the expression "{a: {b: {c: 1}}}"
    When it is evaluated
    Then following the path a.b.c yields 1
    And it compiles to Clojure "{:a {:b {:c 1}}}"

  Scenario: Object with nil value
    Given the expression "{name: "Alice" address: nil}"
    When it is evaluated
    Then field "name" equals "Alice"
    And field "address" equals nil

  Scenario: Object keys may contain hyphens
    Given the expression "{first-name: "Alice" last-name: "Smith"}"
    When it is evaluated
    Then field "first-name" equals "Alice"
    And it compiles to Clojure "{:first-name "Alice" :last-name "Smith"}"

  Scenario: Object keys may contain digits (but not start with them)
    Given the expression "{level2: "advanced" x1: 10}"
    When it is evaluated
    Then field "level2" equals "advanced"
    And field "x1" equals 10

  Scenario: Object keys must start with a letter
    Given the expression "{2fast: "no"}"
    When it is parsed
    Then a parse error is produced

  Scenario: Object keys may contain underscores
    Given the expression "{user_name: "Alice"}"
    When it is evaluated
    Then field "user_name" equals "Alice"

  Scenario: Duplicate keys -- last value wins
    Given the expression "{name: "Alice" name: "Bob"}"
    When it is evaluated
    Then field "name" equals "Bob"
    And it compiles to Clojure "{:name "Alice" :name "Bob"}"
    # Note: Clojure maps also last-win on duplicate keys.
    # A compiler warning should be emitted for duplicate keys.

  Scenario: Trailing whitespace inside braces is allowed
    Given the expression "{name: "Alice" }"
    When it is evaluated
    Then the result is an object with field "name" equal to "Alice"

  Scenario: Leading whitespace inside braces is allowed
    Given the expression "{ name: "Alice"}"
    When it is evaluated
    Then the result is an object with field "name" equal to "Alice"

  Scenario: Multi-line object with newline-separated fields
    Given the expression:
      """
      {
        name: "Alice"
        age: 25
        city: "Moscow"
      }
      """
    When it is evaluated
    Then the result is an object with 3 fields
    And field "name" equals "Alice"
    And field "age" equals 25
    And field "city" equals "Moscow"

  Scenario: Multi-line object with mixed single-line and multi-line fields
    Given the expression:
      """
      {
        name: "Alice"
        address: {
          city: "Moscow"
          zip: "101000"
        }
      }
      """
    When it is evaluated
    Then following the path address.city yields "Moscow"

  Scenario: Object with expression values spanning concepts
    Given the binding "users is [{age: 20} {age: 30} {age: 40}]"
    And the expression:
      """
      {
        count: users |> count
        names: users |> map _.name
      }
      """
    When it is evaluated
    Then field "count" equals 3

  Scenario: Commas between object fields are a parse error
    Given the expression "{name: "Alice", age: 25}"
    When it is parsed
    Then a parse error is produced

  # ---------------------------------------------------------------------------
  # Lists (Vectors)
  # ---------------------------------------------------------------------------

  Scenario: Empty list
    Given the expression "[]"
    When it is evaluated
    Then the result is an empty list
    And it compiles to Clojure "[]"

  Scenario: List of integers
    Given the expression "[1 2 3 4 5]"
    When it is evaluated
    Then the result is a list with 5 elements
    And element 0 equals 1
    And element 4 equals 5
    And it compiles to Clojure "[1 2 3 4 5]"

  Scenario: List of strings
    Given the expression "["Alice" "Bob" "Charlie"]"
    When it is evaluated
    Then the result is a list with 3 elements
    And element 0 equals "Alice"

  Scenario: List with mixed types
    Given the expression "["Alice" 25 true nil]"
    When it is evaluated
    Then element 0 equals "Alice"
    And element 1 equals 25
    And element 2 equals true
    And element 3 equals nil

  Scenario: Nested lists
    Given the expression "[[1 2] [3 4] [5 6]]"
    When it is evaluated
    Then the result is a list with 3 elements
    And element 0 equals [1 2]
    And element 1 equals [3 4]

  Scenario: List containing objects
    Given the expression "[{a: 1} {a: 2} {a: 3}]"
    When it is evaluated
    Then the result is a list with 3 elements
    And element 0 is an object with field "a" equal to 1
    And element 2 is an object with field "a" equal to 3

  Scenario: List containing expressions
    Given the binding "x is 10"
    And the expression "[x x * 2 x + 5]"
    When it is evaluated
    Then element 0 equals 10
    And element 1 equals 20
    And element 2 equals 15

  Scenario: Multi-line list
    Given the expression:
      """
      [
        1
        2
        3
      ]
      """
    When it is evaluated
    Then the result is a list with 3 elements
    And element 0 equals 1

  Scenario: Multi-line list of objects
    Given the expression:
      """
      [
        {name: "Alice" age: 25}
        {name: "Bob" age: 30}
      ]
      """
    When it is evaluated
    Then the result is a list with 2 elements
    And element 0 is an object with field "name" equal to "Alice"

  Scenario: Trailing whitespace in list is allowed
    Given the expression "[1 2 3 ]"
    When it is evaluated
    Then the result is a list with 3 elements

  Scenario: Commas between list elements are a parse error
    Given the expression "[1, 2, 3]"
    When it is parsed
    Then a parse error is produced

  Scenario: Deeply nested list
    Given the expression "[[[1]]]"
    When it is evaluated
    Then the result is a list whose first element is a list whose first element is [1]

  # ---------------------------------------------------------------------------
  # Field Access (Dot Notation)
  # ---------------------------------------------------------------------------

  Scenario: Simple field access
    Given the binding "user is {name: "Alice" age: 25}"
    And the expression "user.name"
    When it is evaluated
    Then the result is "Alice"
    And it compiles to Clojure "(:name user)"

  Scenario: Nested field access
    Given the binding "user is {profile: {address: {city: "Moscow"}}}"
    And the expression "user.profile.address.city"
    When it is evaluated
    Then the result is "Moscow"
    And it compiles to Clojure "(-> user :profile :address :city)"

  Scenario: Field access on nil returns nil (nil-tolerant)
    Given the binding "user is nil"
    And the expression "user.name"
    When it is evaluated
    Then the result is nil

  Scenario: Chained field access through nil returns nil
    Given the binding "user is {name: "Alice"}"
    And the expression "user.address.city.zip"
    When it is evaluated
    Then the result is nil
    # address is nil, so .city on nil is nil, and .zip on nil is nil

  Scenario: Deeply chained access through nil
    Given the binding "data is {a: nil}"
    And the expression "data.a.b.c.d.e"
    When it is evaluated
    Then the result is nil

  Scenario: Field access on non-nil intermediate values
    Given the binding "data is {a: {b: {c: {d: 42}}}}"
    And the expression "data.a.b.c.d"
    When it is evaluated
    Then the result is 42

  Scenario: Field access returns nil for missing key
    Given the binding "user is {name: "Alice"}"
    And the expression "user.email"
    When it is evaluated
    Then the result is nil

  Scenario: Wildcard field access in pipeline
    Given the binding "users is [{name: "Alice" age: 25} {name: "Bob" age: 30}]"
    And the expression "users |> map _.name"
    When it is evaluated
    Then the result is ["Alice" "Bob"]

  Scenario: Nested wildcard field access
    Given the binding "users is [{profile: {city: "Moscow"}} {profile: {city: "Berlin"}}]"
    And the expression "users |> map _.profile.city"
    When it is evaluated
    Then the result is ["Moscow" "Berlin"]

  Scenario: Wildcard field access on missing field returns nil
    Given the binding "users is [{name: "Alice"} {age: 30}]"
    And the expression "users |> map _.name"
    When it is evaluated
    Then the result is ["Alice" nil]

  # ---------------------------------------------------------------------------
  # List Indexing
  # ---------------------------------------------------------------------------

  Scenario: Access list element by index with nth
    Given the binding "items is [10 20 30]"
    And the expression "nth items 0"
    When it is evaluated
    Then the result is 10

  Scenario: nth with out-of-bounds index returns nil
    Given the binding "items is [10 20 30]"
    And the expression "nth items 10"
    When it is evaluated
    Then the result is nil

  Scenario: nth with negative index returns nil
    Given the binding "items is [10 20 30]"
    And the expression "nth items -1"
    When it is evaluated
    Then the result is nil

  Scenario: first and last on lists
    Given the binding "items is [10 20 30]"
    When the expression "first items" is evaluated
    Then the result is 10
    When the expression "last items" is evaluated
    Then the result is 30

  Scenario: first on empty list returns nil
    Given the binding "items is []"
    And the expression "first items"
    When it is evaluated
    Then the result is nil

  # ---------------------------------------------------------------------------
  # Dynamic Key Access
  # ---------------------------------------------------------------------------

  Scenario: Dynamic field access with get
    Given the binding "user is {name: "Alice" age: 25}"
    And the binding "key is "name""
    And the expression "get user key"
    When it is evaluated
    Then the result is "Alice"
    And it compiles to Clojure "(get user (keyword key))"

  Scenario: Dynamic field access with get returns nil for missing key
    Given the binding "user is {name: "Alice"}"
    And the binding "key is "email""
    And the expression "get user key"
    When it is evaluated
    Then the result is nil

  Scenario: Dynamic field access with get and default value
    Given the binding "user is {name: "Alice"}"
    And the expression "get user "email" "unknown""
    When it is evaluated
    Then the result is "unknown"

  # ---------------------------------------------------------------------------
  # Object Operations
  # ---------------------------------------------------------------------------

  Scenario: Merge two objects
    Given the binding "a is {name: "Alice" age: 25}"
    And the binding "b is {age: 26 city: "Moscow"}"
    And the expression "merge a b"
    When it is evaluated
    Then field "name" equals "Alice"
    And field "age" equals 26
    And field "city" equals "Moscow"
    And it compiles to Clojure "(merge a b)"

  Scenario: Merge with empty object
    Given the binding "a is {name: "Alice"}"
    And the expression "merge a {}"
    When it is evaluated
    Then the result equals "{name: "Alice"}"

  Scenario: Merge multiple objects
    Given the binding "a is {x: 1}"
    And the binding "b is {y: 2}"
    And the binding "c is {z: 3}"
    And the expression "merge a b c"
    When it is evaluated
    Then field "x" equals 1
    And field "y" equals 2
    And field "z" equals 3

  Scenario: Assoc a new field into an object
    Given the binding "user is {name: "Alice"}"
    And the expression "assoc user "age" 25"
    When it is evaluated
    Then field "name" equals "Alice"
    And field "age" equals 25
    And it compiles to Clojure "(assoc user :age 25)"

  Scenario: Dissoc removes a field from an object
    Given the binding "user is {name: "Alice" age: 25 tmp: true}"
    And the expression "dissoc user "tmp""
    When it is evaluated
    Then field "name" equals "Alice"
    And field "age" equals 25
    And the result has no field "tmp"

  Scenario: keys returns the list of field names
    Given the binding "user is {name: "Alice" age: 25}"
    And the expression "keys user"
    When it is evaluated
    Then the result is a list containing "name" and "age"

  Scenario: vals returns the list of field values
    Given the binding "user is {name: "Alice" age: 25}"
    And the expression "vals user"
    When it is evaluated
    Then the result is a list containing "Alice" and 25

  # ---------------------------------------------------------------------------
  # List Operations (basic, not pipeline-specific)
  # ---------------------------------------------------------------------------

  Scenario: count on a list
    Given the binding "items is [1 2 3]"
    And the expression "count items"
    When it is evaluated
    Then the result is 3

  Scenario: count on an empty list
    Given the binding "items is []"
    And the expression "count items"
    When it is evaluated
    Then the result is 0

  Scenario: conj appends to a list
    Given the binding "items is [1 2 3]"
    And the expression "conj items 4"
    When it is evaluated
    Then the result is [1 2 3 4]
    And it compiles to Clojure "(conj items 4)"

  Scenario: concat joins two lists
    Given the binding "a is [1 2]"
    And the binding "b is [3 4]"
    And the expression "concat a b"
    When it is evaluated
    Then the result is [1 2 3 4]

  Scenario: contains? checks for element presence in a list
    Given the binding "items is [1 2 3]"
    When the expression "contains? items 2" is evaluated
    Then the result is true
    When the expression "contains? items 9" is evaluated
    Then the result is false

  # ---------------------------------------------------------------------------
  # Structures in Assignment
  # ---------------------------------------------------------------------------

  Scenario: Assign an object to a binding
    Given the expression "user is {name: "Alice" age: 25}"
    When it is evaluated
    Then "user" is bound to an object with field "name" equal to "Alice"

  Scenario: Assign a list to a binding
    Given the expression "items is [1 2 3]"
    When it is evaluated
    Then "items" is bound to a list with 3 elements

  Scenario: Assign nested structure to a binding
    Given the expression:
      """
      data is {
        users: [
          {name: "Alice" scores: [90 85 92]}
          {name: "Bob" scores: [78 88 95]}
        ]
        meta: {count: 2 version: 1}
      }
      """
    When it is evaluated
    Then "data" is bound to an object
    And following the path data.users yields a list with 2 elements
    And following the path data.meta.count yields 2

  # ---------------------------------------------------------------------------
  # Structures in Pipeline Context
  # ---------------------------------------------------------------------------

  Scenario: Object literal in map pipeline stage
    Given the binding "users is [{name: "Alice" age: 25} {name: "Bob" age: 30}]"
    And the expression "users |> map {label: _.name years: _.age}"
    When it is evaluated
    Then element 0 is an object with field "label" equal to "Alice"
    And element 0 is an object with field "years" equal to 25

  Scenario: List literal as pipeline source
    Given the expression "[3 1 4 1 5] |> sort |> take 3"
    When it is evaluated
    Then the result is [1 1 3]

  Scenario: Empty object in pipeline
    Given the expression "[1 2 3] |> map {}"
    When it is evaluated
    Then the result is [{} {} {}]

  # ---------------------------------------------------------------------------
  # Type Checking and Reflection
  # ---------------------------------------------------------------------------

  Scenario: Type of object
    Given the binding "x is {a: 1}"
    And the expression "type x"
    When it is evaluated
    Then the result is "object"

  Scenario: Type of list
    Given the binding "x is [1 2 3]"
    And the expression "type x"
    When it is evaluated
    Then the result is "list"

  Scenario: empty? on empty object
    Given the expression "empty? {}"
    When it is evaluated
    Then the result is true

  Scenario: empty? on non-empty list
    Given the expression "empty? [1]"
    When it is evaluated
    Then the result is false

  # ---------------------------------------------------------------------------
  # Edge Cases and Corner Cases
  # ---------------------------------------------------------------------------

  Scenario: Object with boolean values
    Given the expression "{active: true deleted: false}"
    When it is evaluated
    Then field "active" equals true
    And field "deleted" equals false

  Scenario: Object with numeric keys that are valid identifiers
    Given the expression "{x: 1 y: 2 z: 3}"
    When it is evaluated
    Then the result is an object with 3 fields

  Scenario: Single-element list
    Given the expression "[42]"
    When it is evaluated
    Then the result is a list with 1 element
    And element 0 equals 42

  Scenario: Single-field object
    Given the expression "{x: 1}"
    When it is evaluated
    Then the result is an object with 1 field

  Scenario: Object containing a list value
    Given the expression "{scores: [90 85 92]}"
    When it is evaluated
    Then field "scores" equals [90 85 92]

  Scenario: List containing a mix of objects and primitives
    Given the expression "[{a: 1} 42 "hello" nil true]"
    When it is evaluated
    Then the result is a list with 5 elements

  Scenario: Deeply nested mixed structure
    Given the expression "{a: [{b: [{c: 1}]}]}"
    When it is evaluated
    Then the result is an object
    # Accessing a[0].b[0].c should yield 1 through appropriate access pattern

  Scenario: Object where value is a function
    Given the expression "{transform: [x -> x * 2]}"
    When it is evaluated
    Then field "transform" is a function
    And calling field "transform" with 5 yields 10

  Scenario: Whitespace variations in objects
    Given the expression "{  name:   "Alice"   age:   25  }"
    When it is evaluated
    Then field "name" equals "Alice"
    And field "age" equals 25

  Scenario: Whitespace variations in lists
    Given the expression "[  1   2   3  ]"
    When it is evaluated
    Then the result is [1 2 3]

  Scenario: Newlines as element separators in objects
    Given the expression:
      """
      {name: "Alice"
       age: 25}
      """
    When it is evaluated
    Then the result is an object with 2 fields

  Scenario: Tab characters as separators
    Given the expression "{name:	"Alice"	age:	25}"
    When it is evaluated
    Then the result is an object with 2 fields

  Scenario: Field access on a literal object
    Given the expression "{name: "Alice" age: 25}.name"
    When it is evaluated
    Then the result is "Alice"

  Scenario: Field access on the result of a function call
    Given the binding "get-user is [-> {name: "Alice"}]"
    And the expression "(get-user).name"
    When it is evaluated
    Then the result is "Alice"

  Scenario: Equality of objects
    Given the binding "a is {x: 1 y: 2}"
    And the binding "b is {y: 2 x: 1}"
    And the expression "a = b"
    When it is evaluated
    Then the result is true
    # Object equality is order-independent (like Clojure maps)

  Scenario: Equality of lists
    Given the binding "a is [1 2 3]"
    And the binding "b is [1 2 3]"
    And the expression "a = b"
    When it is evaluated
    Then the result is true

  Scenario: List equality is order-dependent
    Given the binding "a is [1 2 3]"
    And the binding "b is [3 2 1]"
    And the expression "a = b"
    When it is evaluated
    Then the result is false

  # ---------------------------------------------------------------------------
  # Compilation / Clojure Mapping Verification
  # ---------------------------------------------------------------------------

  Scenario: Object compiles to Clojure map with keyword keys
    Given the expression "{name: "Alice" age: 25}"
    When it is compiled
    Then the Clojure output is "{:name "Alice" :age 25}"

  Scenario: Nested object compiles to nested Clojure map
    Given the expression "{user: {name: "Alice"}}"
    When it is compiled
    Then the Clojure output is "{:user {:name "Alice"}}"

  Scenario: List compiles to Clojure vector
    Given the expression "[1 2 3]"
    When it is compiled
    Then the Clojure output is "[1 2 3]"

  Scenario: Dot access compiles to threading macro or get-in
    Given the binding "user is {profile: {name: "Alice"}}"
    And the expression "user.profile.name"
    When it is compiled
    Then the Clojure output is "(get-in user [:profile :name])"
    # or equivalently "(-> user :profile :name)"

  Scenario: Nil-tolerant access compiles to some-> or get-in
    Given the expression "user.address.city"
    When it is compiled
    Then the Clojure output is "(get-in user [:address :city])"
    # get-in naturally returns nil for missing paths

  Scenario: Dynamic key access compiles to get with keyword coercion
    Given the expression "get user key"
    When it is compiled
    Then the Clojure output is "(get user (keyword key))"


# =============================================================================
# OPEN QUESTIONS
# =============================================================================
#
# Q1: Sets -- should DataTwist support set literals?
#     Option A: No sets. Users use lists and deduplicate via `distinct`.
#     Option B: #{1 2 3} syntax borrowed from Clojure.
#     Option C: A function `set [1 2 3]` that converts a list to a set.
#     Recommendation: Option C (function). Sets are rarely needed in data
#     pipelines, and a conversion function avoids new syntax. If common
#     usage emerges, a literal can be added later.
#
# Q2: Numeric / string keys in objects?
#     The current grammar restricts keys to identifiers (start with letter,
#     may contain letters, digits, hyphens, underscores). This means:
#       - {my-key: 1}     -- allowed
#       - {myKey: 1}      -- allowed
#       - {key123: 1}     -- allowed
#       - {123: "one"}    -- NOT allowed (starts with digit)
#       - {"a b": 1}      -- NOT allowed (string key)
#     For interop with Clojure maps that have string or numeric keys, use
#     the dynamic `get` function with a string argument.
#     Decision needed: Is this restriction acceptable, or do we need string
#     keys like {"Content-Type": "text/html"}?
#
# Q3: Duplicate keys -- warning or silent last-wins?
#     Clojure maps silently take the last value for duplicate keys.
#     Recommendation: Emit a compile-time warning but allow it (last-wins).
#     Rationale: Silent bugs from typos are painful; an error is too strict
#     for generated or templated code.
#
# Q4: Object merge/update syntax -- function only, or syntactic sugar?
#     Current design: `merge a b` (function call).
#     Alternative: `a ++ b` or `a | b` (operator).
#     Recommendation: Stay with `merge` function. Operator sugar can be
#     added later if it proves ergonomic. `assoc` and `dissoc` cover
#     single-field operations.
#
# Q5: List indexing syntax
#     Option A: `nth items 0` (function call, current design)
#     Option B: `items.0` (dot notation with numeric segment)
#     Option C: `items[0]` (bracket syntax, new grammar production)
#     Recommendation: Option A (`nth`). It is consistent with the
#     function-call style, avoids grammar ambiguity with dot-notation
#     (is `.0` a float literal or index?), and avoids introducing bracket
#     indexing which conflicts with list literals and function syntax.
#     If `items.0` is ever desired, it would need a grammar rule to
#     distinguish numeric dot-segments from float suffixes.
#
# Q6: String keys under the hood
#     Object keys always compile to Clojure keyword keys (:name, :age).
#     For interop with Java maps or JSON with arbitrary string keys,
#     should we support:
#       - `get-string user "Content-Type"` (explicit string-key access)
#       - or automatic coercion in `get`?
#     Recommendation: `get` always coerces its key argument to a keyword.
#     A separate `get-raw` or interop function handles string-keyed maps.
#
# Q7: Field access on non-objects
#     What happens with `42.name` or `"hello".length`?
#     Recommendation: Returns nil (consistent with nil-tolerance).
#     Clojure's `(get 42 :name)` returns nil, so this is natural.
#     No special string methods via dot notation -- use functions instead.
#
# Q8: Multi-line delimiters
#     Objects and lists are delimited by { } and [ ] respectively.
#     Multi-line is supported by allowing newlines between elements.
#     No special indentation rules are required -- the closing brace/bracket
#     terminates the structure regardless of indentation.
#     This avoids the complexity of significant-whitespace parsing for
#     data structure interiors.
#
# Q9: Expressions as list elements -- ambiguity
#     `[x + 1 y * 2]` -- is this [x, +, 1, y, *, 2] or [(x+1), (y*2)]?
#     Recommendation: List elements are primary expressions, NOT full
#     arithmetic expressions. To include computed values, use parentheses
#     or bindings:
#       [(x + 1) (y * 2)]      -- parenthesized expressions
#       [result1 result2]       -- pre-bound values
#     This avoids ambiguity without requiring commas.
#
# Q10: Object spread / rest
#     Should DataTwist support object spread like `{...defaults ...overrides}`
#     or `{& defaults name: "override"}`?
#     Recommendation: Defer. `merge` covers the use case. Spread syntax
#     can be added later if destructuring-in-construction proves common.
#
# =============================================================================
# CLOJURE MAPPING REFERENCE
# =============================================================================
#
# DataTwist                          | Clojure
# ------------------------------------|----------------------------------------
# {}                                  | {}
# {name: "Alice" age: 25}            | {:name "Alice" :age 25}
# {a: {b: 1}}                        | {:a {:b 1}}
# []                                  | []
# [1 2 3]                            | [1 2 3]
# [{a: 1} {a: 2}]                    | [{:a 1} {:a 2}]
# user.name                          | (:name user)
# user.profile.address.city          | (get-in user [:profile :address :city])
# get user key                        | (get user (keyword key))
# get user key default                | (get user (keyword key) default)
# merge a b                           | (merge a b)
# assoc user "age" 25                | (assoc user :age 25)
# dissoc user "tmp"                  | (dissoc user :tmp)
# keys obj                            | (keys obj)
# vals obj                            | (vals obj)
# count items                         | (count items)
# nth items 0                         | (nth items 0 nil)
# first items                         | (first items)
# last items                          | (last items)
# conj items 4                        | (conj items 4)
# concat a b                          | (into [] (concat a b))
# contains? items 2                  | (some #{2} items)
# empty? x                            | (empty? x)
# type x                              | (cond (map? x) "object" ...)
# set [1 2 3]                        | (set [1 2 3])
#
# Note: `concat` returns a vector (not a lazy seq) to match DataTwist list
# semantics. `nth` uses a 3-arity call to return nil on out-of-bounds
# instead of throwing.
#
# =============================================================================
# CORNER CASES
# =============================================================================
#
# 1. `{a: {}}` -- object with empty object value. Must parse correctly.
# 2. `{a: []}` -- object with empty list value. Must parse correctly.
# 3. `[{}]` -- list with single empty object. Must parse correctly.
# 4. `[[]]` -- list with single empty list. Must parse correctly.
# 5. `{a: b}` where b is undefined -- should evaluate to {a: nil} (or
#    compile error if strict mode is ever added).
# 6. `user.name.length` -- if name is "Alice", this returns nil (strings
#    are not objects; use `count` for string length).
# 7. `nil.anything` -- returns nil. Never throws.
# 8. `{a: 1}.a.b.c` -- evaluates to nil (1 is not an object).
# 9. Field access vs function call ambiguity: `user.name` is always field
#    access. `name user` is a function call. These are syntactically distinct.
# 10. `{a: not true}` -- `not true` is a logical expression; field "a"
#     should equal false.
# 11. `{f: [x -> x * 2]}` -- object containing a function value. Legal.
# 12. `merge nil {a: 1}` -- should return {a: 1} (Clojure merge ignores nil).
# 13. `get nil "key"` -- returns nil (Clojure get on nil returns nil).
# 14. `count nil` -- returns 0 or nil? Clojure returns 0. Follow Clojure.
# 15. `{a: 1 b: 2 c: 3} |> keys |> sort` -- should produce ["a" "b" "c"].
#     Note: keys returns string representations of the keyword names.
