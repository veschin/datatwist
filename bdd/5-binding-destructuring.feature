Feature: Binding & Destructuring with `is`

  DataTwist uses `is` for all bindings and destructuring.
  `is` does not conflict with `=` which is reserved for equality comparison.
  Destructuring has full parity with Clojure: `&` for rest, `?` for defaults,
  `as` for whole-value binding. Missing fields yield nil (nil-tolerant).

  # --------------------------------------------------------------------------
  # Simple Binding
  # --------------------------------------------------------------------------

  Scenario: Bind a literal integer
    Given the DataTwist source
      """
      x is 42
      """
    When it compiles to Clojure
    Then the output is
      """
      (def x 42)
      """
    And `x` evaluates to 42

  Scenario: Bind a literal string
    Given the DataTwist source
      """
      name is "Alice"
      """
    When it compiles to Clojure
    Then the output is
      """
      (def name "Alice")
      """
    And `name` evaluates to "Alice"

  Scenario: Bind a boolean
    Given the DataTwist source
      """
      active is true
      """
    When it compiles to Clojure
    Then the output is
      """
      (def active true)
      """

  Scenario: Bind nil explicitly
    Given the DataTwist source
      """
      nothing is nil
      """
    When it compiles to Clojure
    Then the output is
      """
      (def nothing nil)
      """

  Scenario: Bind to an expression result
    Given the DataTwist source
      """
      total is 3 + 4
      """
    When it compiles to Clojure
    Then the output is
      """
      (def total (+ 3 4))
      """
    And `total` evaluates to 7

  Scenario: Bind to a pipeline result
    Given the DataTwist source
      """
      result is users |> filter _.active |> count
      """
    When it compiles to Clojure
    Then the output is
      """
      (def result (-> users (filter (fn [x] (:active x))) count))
      """

  Scenario: Bind to a function definition
    Given the DataTwist source
      """
      double is [x -> x * 2]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def double (fn [x] (* x 2)))
      """

  Scenario: Bind to an object literal
    Given the DataTwist source
      """
      user is {name: "Alice" age: 30}
      """
    When it compiles to Clojure
    Then the output is
      """
      (def user {:name "Alice" :age 30})
      """

  Scenario: Bind to a list literal
    Given the DataTwist source
      """
      nums is [1 2 3 4 5]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def nums [1 2 3 4 5])
      """

  # --------------------------------------------------------------------------
  # Object Destructuring -- Basic
  # --------------------------------------------------------------------------

  Scenario: Destructure object keys into same-name bindings
    Given the DataTwist source
      """
      {name age} is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [name age]} user] ...)
      """
    And `name` evaluates to the value of `(:name user)`
    And `age` evaluates to the value of `(:age user)`

  Scenario: Parser distinguishes destructuring pattern from object literal
    Given the DataTwist source
      """
      {name age} is user
      """
    Then `{name age}` is parsed as a destructuring pattern
    Because it appears on the left side of `is`
    And it contains bare identifiers without `:` value syntax

  Scenario: Destructure with renamed keys
    Given the DataTwist source
      """
      {name: n age: a} is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{n :name a :age} user] ...)
      """
    And `n` evaluates to the value of `(:name user)`
    And `a` evaluates to the value of `(:age user)`

  Scenario: Rename syntax is context-dependent
    Given the DataTwist source for an object literal
      """
      obj is {name: n}
      """
    Then `{name: n}` is parsed as an object literal where key is `name` and value is the variable `n`
    Given the DataTwist source for destructuring
      """
      {name: n} is user
      """
    Then `{name: n}` is parsed as a destructuring pattern renaming `name` to `n`
    Because context (left vs right of `is`) determines the interpretation

  # --------------------------------------------------------------------------
  # Object Destructuring -- Defaults with `?`
  # --------------------------------------------------------------------------

  Scenario: Destructure with default values
    Given the DataTwist source
      """
      {name ? "anon" age ? 0} is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [name age] :or {name "anon" age 0}} user] ...)
      """

  Scenario: Default is used when key is missing from source object
    Given `user` is `{}`
    And the DataTwist source
      """
      {name ? "anon"} is user
      """
    Then `name` evaluates to "anon"

  Scenario: Default is NOT used when key exists but value is nil
    Given `user` is `{name: nil}`
    And the DataTwist source
      """
      {name ? "anon"} is user
      """
    Then `name` evaluates to nil
    Because DataTwist follows Clojure `:or` semantics -- defaults apply only to missing keys, not nil values

  Scenario: Default with complex expression
    Given the DataTwist source
      """
      {timeout ? 30 * 1000} is config
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [timeout] :or {timeout (* 30 1000)}} config] ...)
      """

  # --------------------------------------------------------------------------
  # Object Destructuring -- Whole Binding with `as`
  # --------------------------------------------------------------------------

  Scenario: Destructure object and retain whole value with `as`
    Given the DataTwist source
      """
      {name age} as u is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [name age] :as u} user] ...)
      """
    And `name` evaluates to `(:name user)`
    And `age` evaluates to `(:age user)`
    And `u` evaluates to the entire `user` object

  Scenario: `as` combined with rename and defaults
    Given the DataTwist source
      """
      {name: n age ? 0} as u is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{n :name :keys [age] :or {age 0} :as u} user] ...)
      """

  # --------------------------------------------------------------------------
  # Object Destructuring -- Nested
  # --------------------------------------------------------------------------

  Scenario: Nested object destructuring one level deep
    Given the DataTwist source
      """
      {address: {city country}} is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [address]} user
            {:keys [city country]} address] ...)
      """
    And `city` evaluates to `(:city (:address user))`
    And `country` evaluates to `(:country (:address user))`

  Scenario: Nested object destructuring two levels deep
    Given the DataTwist source
      """
      {a: {b: {c}}} is deep
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [a]} deep
            {:keys [b]} a
            {:keys [c]} b] ...)
      """

  Scenario: Nested destructuring with rename at leaf
    Given the DataTwist source
      """
      {address: {city: c}} is user
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [address]} user
            {c :city} address] ...)
      """

  Scenario: Nested destructuring with nil-tolerance
    Given `user` is `{name: "Alice"}`
    And the DataTwist source
      """
      {address: {city}} is user
      """
    Then `city` evaluates to nil
    Because `user.address` is nil, so destructuring yields nil for all nested keys

  Scenario: Deeply nested destructuring has no artificial limit
    Given the DataTwist source
      """
      {a: {b: {c: {d: {e}}}}} is data
      """
    Then it parses successfully
    And compilation produces valid Clojure

  # --------------------------------------------------------------------------
  # List Destructuring -- Basic
  # --------------------------------------------------------------------------

  Scenario: Destructure list into positional bindings
    Given the DataTwist source
      """
      [a b c] is [1 2 3]
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [[a b c] [1 2 3]] ...)
      """
    And `a` evaluates to 1
    And `b` evaluates to 2
    And `c` evaluates to 3

  Scenario: List destructuring with rest using `&`
    Given the DataTwist source
      """
      [first & rest] is [1 2 3 4 5]
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [[first & rest] [1 2 3 4 5]] ...)
      """
    And `first` evaluates to 1
    And `rest` evaluates to [2 3 4 5]

  Scenario: List destructuring with `& rest` on empty tail
    Given the DataTwist source
      """
      [only & rest] is [1]
      """
    Then `only` evaluates to 1
    And `rest` evaluates to nil

  Scenario: List pattern is shorter than source -- extra elements ignored
    Given the DataTwist source
      """
      [a b] is [1 2 3 4 5]
      """
    Then `a` evaluates to 1
    And `b` evaluates to 2

  Scenario: List pattern is longer than source -- excess bindings are nil
    Given the DataTwist source
      """
      [a b c] is [1 2]
      """
    Then `a` evaluates to 1
    And `b` evaluates to 2
    And `c` evaluates to nil
    Because Clojure sequential destructuring pads with nil

  # --------------------------------------------------------------------------
  # List Destructuring -- Skip with `_`
  # --------------------------------------------------------------------------

  Scenario: Skip positions with underscore
    Given the DataTwist source
      """
      [_ second _] is [1 2 3]
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [[_ second _] [1 2 3]] ...)
      """
    And `second` evaluates to 2
    And `_` is not introduced as a named binding

  Scenario: Skip first element, capture rest
    Given the DataTwist source
      """
      [_ & tail] is [1 2 3 4]
      """
    Then `tail` evaluates to [2 3 4]

  Scenario: Multiple underscores in a row
    Given the DataTwist source
      """
      [_ _ _ fourth] is [1 2 3 4]
      """
    Then `fourth` evaluates to 4

  # --------------------------------------------------------------------------
  # List Destructuring -- Whole Binding with `as`
  # --------------------------------------------------------------------------

  Scenario: List destructuring with `as` for whole binding
    Given the DataTwist source
      """
      [head & tail] as all is items
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [[head & tail :as all] items] ...)
      """
    And `head` evaluates to the first element of `items`
    And `tail` evaluates to the rest of `items`
    And `all` evaluates to the entire `items` list

  Scenario: List `as` with skip pattern
    Given the DataTwist source
      """
      [_ second] as original is data
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [[_ second :as original] data] ...)
      """
    And `second` evaluates to the second element
    And `original` evaluates to the entire `data` list

  # --------------------------------------------------------------------------
  # Combined Object + List Destructuring
  # --------------------------------------------------------------------------

  Scenario: Object with nested list destructuring
    Given the DataTwist source
      """
      {name scores: [best & rest]} is player
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [name] [best & rest] :scores} player] ...)
      """
    And `name` evaluates to `(:name player)`
    And `best` evaluates to `(first (:scores player))`
    And `rest` evaluates to `(rest (:scores player))`

  Scenario: Object with nested list that has defaults
    Given the DataTwist source
      """
      {name ? "anon" scores: [best & rest]} is player
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [name] [best & rest] :scores :or {name "anon"}} player] ...)
      """

  Scenario: List of objects destructuring in pipeline context
    Given the DataTwist source
      """
      users |> map [{name age} -> name]
      """
    When it compiles to Clojure
    Then the output is
      """
      (-> users (map (fn [{:keys [name age]}] name)))
      """

  # --------------------------------------------------------------------------
  # Destructuring in Function Parameters
  # --------------------------------------------------------------------------

  Scenario: Function parameter with object destructuring
    Given the DataTwist source
      """
      greet is [{name} -> format "Hello, %s!" name]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def greet (fn [{:keys [name]}] (format "Hello, %s!" name)))
      """

  Scenario: Function with two destructured parameters
    Given the DataTwist source
      """
      add-ages is [{age: a1} {age: a2} -> a1 + a2]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def add-ages (fn [{a1 :age} {a2 :age}] (+ a1 a2)))
      """

  Scenario: Function parameter with list destructuring
    Given the DataTwist source
      """
      head is [[first & _] -> first]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def head (fn [[first & _]] first))
      """

  Scenario: Destructuring in anonymous function within pipeline
    Given the DataTwist source
      """
      users |> map [{name age} -> format "%s is %d" name age]
      """
    When it compiles to Clojure
    Then the output is
      """
      (-> users (map (fn [{:keys [name age]}] (format "%s is %d" name age))))
      """

  Scenario: Mixed plain and destructured parameters
    Given the DataTwist source
      """
      process is [label {name age} -> format "%s: %s (%d)" label name age]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def process (fn [label {:keys [name age]}] (format "%s: %s (%d)" label name age)))
      """

  # --------------------------------------------------------------------------
  # Scope, Shadowing, and Rebinding
  # --------------------------------------------------------------------------

  Scenario: Top-level binding creates a def
    Given the DataTwist source
      """
      x is 42
      """
    When it compiles to Clojure
    Then the output is
      """
      (def x 42)
      """

  Scenario: Binding inside a function body creates lexical scope
    Given the DataTwist source
      """
      compute is [data ->
        n is count data
        n * 2
      ]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def compute (fn [data] (let [n (count data)] (* n 2))))
      """
    And `n` is only visible inside the function body

  Scenario: Multiple sequential bindings in function body
    Given the DataTwist source
      """
      process is [data ->
        filtered is data |> filter _.active
        n is count filtered
        {count: n items: filtered}
      ]
      """
    When it compiles to Clojure
    Then the output is
      """
      (def process (fn [data]
        (let [filtered (-> data (filter (fn [x] (:active x))))
              n (count filtered)]
          {:count n :items filtered})))
      """

  Scenario: Inner scope shadows outer binding
    Given the DataTwist source
      """
      x is 10
      f is [x -> x + 1]
      """
    When it compiles to Clojure
    Then calling `f 5` evaluates to 6
    And the outer `x` remains 10
    Because function parameter `x` shadows the top-level `x`

  Scenario: Rebinding at top-level redefines the var
    Given the DataTwist source
      """
      x is 5
      x is 10
      """
    When it compiles to Clojure
    Then `x` evaluates to 10
    Because top-level `is` compiles to `def`, which is re-assignable in Clojure

  Scenario: Rebinding inside function body shadows previous local
    Given the DataTwist source
      """
      f is [->
        x is 1
        x is x + 1
        x
      ]
      """
    When it compiles to Clojure
    Then calling `f` evaluates to 2
    Because the second `is` inside a function creates a new `let` binding that shadows the first

  # --------------------------------------------------------------------------
  # Destructuring in Pipeline Context
  # --------------------------------------------------------------------------

  Scenario: Destructuring in map within pipeline
    Given the DataTwist source
      """
      users |> map [{name age} -> {display: name years: age}]
      """
    When it compiles to Clojure
    Then the output is
      """
      (-> users (map (fn [{:keys [name age]}] {:display name :years age})))
      """

  Scenario: Destructuring in filter within pipeline
    Given the DataTwist source
      """
      users |> filter [{age} -> age > 18]
      """
    When it compiles to Clojure
    Then the output is
      """
      (-> users (filter (fn [{:keys [age]}] (> age 18))))
      """

  Scenario: Binding pipeline result with destructuring
    Given the DataTwist source
      """
      {total items} is data |> process |> summarize
      """
    When it compiles to Clojure
    Then the output is
      """
      (let [{:keys [total items]} (-> data process summarize)] ...)
      """

  # --------------------------------------------------------------------------
  # Edge Cases and Error Behavior
  # --------------------------------------------------------------------------

  Scenario: Destructuring from nil source yields nil for all bindings
    Given `data` is `nil`
    And the DataTwist source
      """
      {name age} is data
      """
    Then `name` evaluates to nil
    And `age` evaluates to nil
    Because destructuring nil in Clojure yields nil for all keys

  Scenario: List destructuring from nil yields nil
    Given `items` is `nil`
    And the DataTwist source
      """
      [a b c] is items
      """
    Then `a` evaluates to nil
    And `b` evaluates to nil
    And `c` evaluates to nil

  Scenario: Object destructuring from a list is not an error
    Given `data` is `[1 2 3]`
    And the DataTwist source
      """
      {name} is data
      """
    Then `name` evaluates to nil
    Because a vector has no `:name` key -- Clojure does not error, just returns nil

  Scenario: List destructuring from an object yields nil elements
    Given `data` is `{name: "Alice"}`
    And the DataTwist source
      """
      [a b] is data
      """
    Then `a` evaluates to nil
    And `b` evaluates to nil
    Because maps are not sequential -- Clojure destructuring produces nil

  Scenario: Underscore is not a valid binding name
    Given the DataTwist source
      """
      _ is 42
      """
    Then parsing fails or compilation produces a warning
    Because `_` is reserved for skip/wildcard, not as a binding target

  Scenario: Empty destructuring pattern is a parse error
    Given the DataTwist source
      """
      {} is user
      """
    Then parsing fails
    Because an empty destructuring pattern binds nothing and is likely a mistake

  Scenario: `&` must be followed by exactly one identifier in list destructuring
    Given the DataTwist source
      """
      [a & b c] is items
      """
    Then parsing fails
    Because `&` collects all remaining elements into one binding

  Scenario: `&` cannot appear at the start of a list pattern
    Given the DataTwist source
      """
      [& rest] is items
      """
    Then it parses successfully
    And `rest` evaluates to the entire list
    Because `&` with no preceding bindings means "collect all"

  Scenario: `as` must be followed by a single identifier
    Given the DataTwist source
      """
      {name} as is user
      """
    Then parsing fails
    Because `as` requires an identifier to bind the whole value to

  Scenario: Duplicate binding names in same destructuring level
    Given the DataTwist source
      """
      {name name} is user
      """
    Then compilation produces a warning
    Because duplicate keys in a destructuring pattern are ambiguous

  # --------------------------------------------------------------------------
  # Rest in Object Destructuring
  # --------------------------------------------------------------------------

  Scenario: Object rest is not supported
    Given the DataTwist source
      """
      {name & rest} is user
      """
    Then parsing fails
    Because `&` rest syntax is for list (sequential) destructuring only
    And object "rest of keys" can be achieved via `dissoc` in a pipeline

  # --------------------------------------------------------------------------
  # Syntax Disambiguation Rules
  # --------------------------------------------------------------------------

  Scenario: `{name age}` on left of `is` is a destructuring pattern
    Given the DataTwist source
      """
      {name age} is user
      """
    Then the parser produces a destructuring node
    And `{name age}` contains bare identifiers (no colon-value pairs)
    And it appears on the left side of `is`

  Scenario: `{name: "Alice" age: 25}` on right of `is` is an object literal
    Given the DataTwist source
      """
      user is {name: "Alice" age: 25}
      """
    Then the parser produces an object literal node
    And `{name: "Alice" age: 25}` contains colon-value pairs

  Scenario: `{name: n}` is context-dependent
    Given the DataTwist source on the right of `is`
      """
      result is {name: n}
      """
    Then `{name: n}` is an object literal with key `name` and value from variable `n`
    Given the DataTwist source on the left of `is`
      """
      {name: n} is user
      """
    Then `{name: n}` is a destructuring pattern renaming `name` to `n`

  Scenario: `[1 2 3]` on right of `is` is a list literal
    Given the DataTwist source
      """
      nums is [1 2 3]
      """
    Then `[1 2 3]` is parsed as a list literal

  Scenario: `[a b c]` on left of `is` is a destructuring pattern
    Given the DataTwist source
      """
      [a b c] is items
      """
    Then `[a b c]` is parsed as a list destructuring pattern


# ##########################################################################
# COMMENTS -- Open Questions, Clojure Mapping, and Corner Cases
# ##########################################################################
#
# ---- Clojure Compilation Mapping ----
#
#   DataTwist                              Clojure
#   -------------------------------------- ------------------------------------------
#   x is 42                                (def x 42)           -- top-level
#                                          (let [x 42] ...)     -- inside function
#   {name age} is user                     (let [{:keys [name age]} user] ...)
#   {name: n} is user                      (let [{n :name} user] ...)
#   {name ? "anon"} is user                (let [{:keys [name] :or {name "anon"}} user] ...)
#   {name age} as u is user                (let [{:keys [name age] :as u} user] ...)
#   [a b c] is items                       (let [[a b c] items] ...)
#   [a & rest] is items                    (let [[a & rest] items] ...)
#   [h & t] as all is items                (let [[h & t :as all] items] ...)
#   {name scores: [best & r]} is player    (let [{:keys [name] [best & r] :scores} player] ...)
#   [{name} -> name] (fn param)            (fn [{:keys [name]}] name)
#   [_ second _] is [1 2 3]               (let [[_ second _] [1 2 3]] ...)
#
# ---- Open Question: Pattern vs Literal Disambiguation ----
#
#   The grammar must determine whether `{...}` and `[...]` on the left side
#   of `is` are destructuring patterns or expressions.
#
#   Proposed rule: the LEFT side of `is` is always a binding target.
#   - A bare identifier: simple binding.
#   - `{...}`: object destructuring pattern.
#   - `[...]`: list destructuring pattern.
#   - Anything else on the left of `is` is a parse error.
#
#   Inside `{...}` on the left:
#   - Bare identifier `name` -> same-name key binding
#   - `name: n` -> renamed binding (Clojure: {n :name})
#   - `name ? val` -> default value (Clojure: {:or {name val}})
#   - `name: n ? val` -> rename + default
#   - `name: {nested}` -> nested destructuring
#
#   This means `{name: n}` has different AST nodes depending on position:
#   - Left of `is`: rename-binding node
#   - Right of `is` (or standalone): object-literal field node
#   The parser can handle this because `is` is a clear syntactic boundary.
#
# ---- Open Question: `?` Default Semantics ----
#
#   Decision: follow Clojure `:or` semantics exactly.
#   - `{name ? "anon"} is user` applies default ONLY when key `:name` is
#     absent from the map (not present at all).
#   - If `:name` exists with value `nil`, the binding gets `nil`, not "anon".
#   - This matches Clojure `{:keys [name] :or {name "anon"}}`.
#   - Rationale: nil-tolerance is a core design value. nil is a valid value.
#     Treating nil as "missing" would break that contract.
#
#   If users need "nil-or-missing" defaults, they can use explicit logic:
#     name is user.name or "anon"
#   (assuming `or` short-circuits on nil/false, which is Clojure's behavior)
#
# ---- Open Question: Rebinding / Mutability ----
#
#   Proposal: `is` always creates a new binding.
#   - Top-level: compiles to `(def ...)`. Repeated `def` in Clojure redefines
#     the var. This is acceptable for REPL-driven development.
#   - Inside function body: compiles to nested `(let ...)`. Each `is` creates
#     a new lexical scope that shadows the previous one. This is immutable --
#     the previous binding still exists but is unreachable.
#   - No "mutation" in the imperative sense. `x is x + 1` inside a function
#     creates a new `x` shadowing the old one.
#
# ---- Open Question: Scope Rules ----
#
#   - Top-level `is` -> `(def ...)` in current namespace.
#   - Inside function body `is` -> `(let [...] ...)`, lexically scoped.
#   - Function parameters -> lexically scoped to function body.
#   - Shadowing is allowed: inner `x` hides outer `x`.
#   - Closures capture outer bindings (standard lexical closure behavior).
#
# ---- Open Question: Object Rest (`&` in maps) ----
#
#   Decision: NOT supported. Clojure does not have a native "rest of map"
#   destructuring. The `:keys` form extracts named keys; there is no
#   complement operation in `let` bindings.
#
#   Users who need "remaining keys" should use:
#     known is {name age} is user
#     rest is user |> dissoc "name" "age"
#   Or a utility function.
#
# ---- Open Question: Nested Destructuring Depth ----
#
#   No artificial limit. The grammar is recursive, so
#   `{a: {b: {c: {d: {e}}}}} is data` should parse and compile.
#   However, deeply nested destructuring suggests the data model could be
#   simplified. Linting/warnings for depth > 3 may be useful in the future.
#
# ---- Open Question: Empty Pattern `{}` or `[]` ----
#
#   Proposal: `{} is x` and `[] is x` are parse errors.
#   Empty patterns bind nothing and are almost certainly mistakes.
#   If the user wants to assert structure without binding, pattern matching
#   (Feature Area 6) is the right tool.
#
# ---- Open Question: `_` Semantics in Destructuring ----
#
#   `_` in list destructuring means "skip this position".
#   - Multiple `_` in one pattern are independent -- each discards one element.
#   - `_` does NOT introduce a binding.
#   - `_ is 42` is an error -- `_` cannot be a binding target.
#   - This matches Clojure convention where `_` is idiomatically used for
#     ignored bindings.
#
# ---- Open Question: `& rest` with Zero Preceding Elements ----
#
#   `[& rest] is items` is valid and `rest` gets the entire list.
#   This is consistent with Clojure: `(let [[& rest] items] ...)`.
#
# ---- Corner Case: Destructuring + Pipeline Binding ----
#
#   `{name age} is data |> transform` -- what binds to what?
#   Proposal: `is` has the lowest precedence after pipeline.
#   So this means: `{name age} is (data |> transform)`.
#   The pipeline runs first, then the result is destructured.
#
# ---- Corner Case: `as` Placement ----
#
#   `as` goes between the destructuring pattern and `is`:
#     {name age} as u is user
#     [h & t] as all is items
#
#   This reads naturally: "name and age AS u IS user".
#   `as` cannot appear without a preceding destructuring pattern.
#   `x as y is 42` is a parse error -- simple binding does not need `as`.
#
# ---- Corner Case: Destructuring in `let`-like Blocks ----
#
#   DataTwist does not have a separate `let` keyword. Multiple `is` lines
#   inside a function body serve the same purpose:
#
#     process is [data ->
#       {name age} is data
#       filtered is data.items |> filter _.active
#       {name: name count: count filtered}
#     ]
#
#   Compiles to nested `let` bindings in Clojure.
#
# ---- Corner Case: Keyword Collision ----
#
#   `is`, `as`, `and`, `or`, `not` are keywords. They cannot be used as
#   binding names. `{is: x} is data` should fail because `is` is not a
#   valid identifier for a key name in destructuring context.
#   (But `{is-valid: x} is data` would work since `is-valid` is a
#   valid identifier.)
#
# ---- Corner Case: Destructuring Return Value ----
#
#   In Clojure, `let` returns its body expression. DataTwist `is` bindings
#   inside a function body must be followed by a body expression (the last
#   expression in the function is the return value).
#
#   Top-level destructuring `{name age} is user` compiles to `(let ...)`.
#   What is the "return value" at top-level? For REPL: the last bound value.
#   For compiled modules: no return value needed (side-effect of binding).
#
# ---- Future Consideration: `if-is` / Conditional Destructuring ----
#
#   Not in scope for this feature area, but worth noting:
#   Clojure has `if-let` and `when-let` for conditional binding.
#   DataTwist may want something like:
#     if {name} is get-user id -> format "Hello %s" name
#   This would be designed in a separate feature area.
#
