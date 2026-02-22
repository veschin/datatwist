Feature: Pipeline operator (|>)
  The pipeline operator is the central construct of DataTwist.
  It enables left-to-right data transformation using pipe-first semantics:
    data |> f args  compiles to  f(data, args)

  Pipelines compose through chaining, where the output of each step
  becomes the first argument of the next step. Under the hood,
  pipelines compile to Clojure thread-first (->).

  # ---------------------------------------------------------------------------
  # Basic pipeline mechanics
  # ---------------------------------------------------------------------------

  Scenario: Single-step pipeline passes data as first argument
    Given the expression:
      """
      data |> count
      """
    Then it compiles to Clojure:
      """
      (-> data count)
      """
    And the result is equivalent to calling count(data)

  Scenario: Multi-step inline pipeline chains left-to-right
    Given the expression:
      """
      users |> filter _.active |> count
      """
    Then it compiles to Clojure:
      """
      (-> users (filter (fn [x] (:active x))) count)
      """
    And each step receives the output of the previous step as its first argument

  Scenario: Multi-line pipeline using |> at line start
    Given the expression:
      """
      users
      |> filter _.active
      |> map _.name
      |> sort-by _.name
      |> take 10
      """
    Then it compiles to Clojure:
      """
      (-> users
          (filter (fn [x] (:active x)))
          (map (fn [x] (:name x)))
          (sort-by (fn [x] (:name x)))
          (take 10))
      """

  Scenario: Pipeline step with multiple arguments (pipe-first)
    Given the expression:
      """
      text |> replace "old" "new"
      """
    Then it compiles to Clojure:
      """
      (-> text (clojure.string/replace "old" "new"))
      """
    And the piped value is inserted as the first argument to replace

  Scenario: Pipeline step with no extra arguments
    Given the expression:
      """
      items |> reverse |> flatten |> distinct
      """
    Then each function receives exactly one argument: the piped data
    And it compiles to Clojure:
      """
      (-> items reverse flatten distinct)
      """

  Scenario: Pipeline result is the return value of the last step
    Given the expression:
      """
      [3 1 4 1 5] |> sort |> last
      """
    Then the result is 5

  Scenario: Associativity is left-to-right
    Given the expression:
      """
      a |> b |> c
      """
    Then it is equivalent to:
      """
      (a |> b) |> c
      """
    And NOT equivalent to:
      """
      a |> (b |> c)
      """

  # ---------------------------------------------------------------------------
  # Underscore (_) as pipeline context reference
  # ---------------------------------------------------------------------------

  Scenario: Bare _ refers to the entire current element
    Given the expression:
      """
      items |> filter _ != nil
      """
    Then _ represents each element of items
    And it compiles to Clojure:
      """
      (-> items (filter (fn [x] (not= x nil))))
      """

  Scenario: Dot-access _.field refers to a field on the current element
    Given the expression:
      """
      users |> filter _.age > 18
      """
    Then _.age accesses the "age" field of each element
    And it compiles to Clojure:
      """
      (-> users (filter (fn [x] (> (:age x) 18))))
      """

  Scenario: Deeply nested dot-access _.a.b.c
    Given the expression:
      """
      users |> filter _.profile.address.city = "Moscow"
      """
    Then _.profile.address.city traverses nested fields
    And it compiles to Clojure:
      """
      (-> users (filter (fn [x] (= (get-in x [:profile :address :city]) "Moscow"))))
      """

  Scenario: _ is syntactic sugar for an anonymous function
    Given the expression:
      """
      users |> filter _.age > 18
      """
    Then it is semantically equivalent to:
      """
      users |> filter [u -> u.age > 18]
      """
    And both compile to the same Clojure output

  Scenario: Explicit lambda can be used instead of _
    Given the expression:
      """
      numbers |> filter [n -> n > 5 and n < 100]
      """
    Then the lambda receives each element as its argument
    And it compiles to Clojure:
      """
      (-> numbers (filter (fn [n] (and (> n 5) (< n 100)))))
      """

  Scenario: _ used with comparison operators
    Given the expression:
      """
      scores |> filter _ > 80
      """
    Then _ represents each score value
    And it compiles to Clojure:
      """
      (-> scores (filter (fn [x] (> x 80))))
      """

  Scenario: _ used in arithmetic expressions
    Given the expression:
      """
      numbers |> map _ * 2 + 1
      """
    Then _ represents each number
    And it compiles to Clojure:
      """
      (-> numbers (map (fn [x] (+ (* x 2) 1))))
      """

  # ---------------------------------------------------------------------------
  # Underscore scoping in nested pipelines
  # ---------------------------------------------------------------------------

  Scenario: _ in an inner pipeline refers to the inner context, not the outer
    Given the expression:
      """
      users |> map {name: _.name scores: _.scores |> filter _ > 80}
      """
    Then the outer _ (in _.name and _.scores) refers to each user
    And the inner _ (in filter _ > 80) refers to each score
    And it compiles to Clojure:
      """
      (-> users
          (map (fn [user]
            {:name (:name user)
             :scores (-> (:scores user) (filter (fn [score] (> score 80))))})))
      """

  Scenario: Explicit lambda resolves scoping ambiguity in nested pipelines
    Given the expression:
      """
      users |> map {
        name: _.name
        top-scores: _.scores |> filter [s -> s > 80] |> take 3
      }
      """
    Then [s -> s > 80] explicitly names the inner element
    And _.name and _.scores still refer to the outer user element

  Scenario: Multiple levels of nesting with _
    Given the expression:
      """
      departments
      |> map {
        dept: _.name
        people: _.teams |> map {
          team: _.name
          count: _.members |> count
        }
      }
      """
    Then the first _.name refers to each department
    And the second _.name refers to each team within a department
    And _.members refers to the members field of each team
    And each |> introduces a new scope for _

  Scenario: _ in a nested pipeline inside filter
    Given the expression:
      """
      users |> filter (_.scores |> sum > 100)
      """
    Then _ in _.scores refers to each user
    And sum receives the scores list
    And the result of sum is compared to 100

  # ---------------------------------------------------------------------------
  # Pipeline with binding (is)
  # ---------------------------------------------------------------------------

  Scenario: Pipeline result assigned with is
    Given the expression:
      """
      result is users |> filter _.active |> count
      """
    Then result is bound to the final pipeline output
    And it compiles to Clojure:
      """
      (def result (-> users (filter (fn [x] (:active x))) count))
      """

  Scenario: Intermediate pipeline result saved to a binding
    Given the expression:
      """
      active-users is users |> filter _.active
      count is active-users |> count
      names is active-users |> map _.name
      """
    Then active-users holds the filtered collection
    And count and names derive from the same intermediate result

  Scenario: Pipeline as the right-hand side of is with destructuring
    Given the expression:
      """
      [first & rest] is items |> sort |> reverse
      """
    Then the pipeline runs first, then destructuring applies to the result

  # ---------------------------------------------------------------------------
  # Pipeline as a value (point-free / partial pipeline)
  # ---------------------------------------------------------------------------

  Scenario: Pipeline without a source creates a reusable transformer
    Given the expression:
      """
      normalize is |> filter _.active |> map _.name |> sort
      """
    Then normalize is a function that accepts data and runs the pipeline
    And calling it:
      """
      users |> normalize
      """
    Is equivalent to:
      """
      users |> filter _.active |> map _.name |> sort
      """
    And it compiles to Clojure:
      """
      (def normalize (fn [data] (-> data (filter (fn [x] (:active x))) (map (fn [x] (:name x))) sort)))
      """

  Scenario: Sourceless pipeline used inline
    Given the expression:
      """
      transformer is |> filter _ > 0 |> map [x -> x * 2]
      [-3 -1 0 2 5] |> transformer
      """
    Then the result is [4 10]

  # ---------------------------------------------------------------------------
  # Pipeline with collection operations (built-in functions)
  # ---------------------------------------------------------------------------

  Scenario: filter removes elements that do not match the predicate
    Given the expression:
      """
      [1 2 3 4 5] |> filter _ > 3
      """
    Then the result is [4 5]

  Scenario: map transforms each element
    Given the expression:
      """
      [1 2 3] |> map _ * 10
      """
    Then the result is [10 20 30]

  Scenario: reduce folds a collection into a single value
    Given the expression:
      """
      [1 2 3 4] |> reduce [acc x -> acc + x] 0
      """
    Then the result is 10
    And it compiles to Clojure:
      """
      (-> [1 2 3 4] (reduce (fn [acc x] (+ acc x)) 0))
      """

  Scenario: sort-by orders elements by a key
    Given the expression:
      """
      users |> sort-by _.age
      """
    Then elements are sorted ascending by the age field

  Scenario: group-by partitions a collection by a key
    Given the expression:
      """
      users |> group-by _.role
      """
    Then the result is an object where keys are role values and values are lists
    And it compiles to Clojure:
      """
      (-> users (group-by (fn [x] (:role x))))
      """

  Scenario: take returns the first N elements
    Given the expression:
      """
      [1 2 3 4 5] |> take 3
      """
    Then the result is [1 2 3]

  Scenario: drop removes the first N elements
    Given the expression:
      """
      [1 2 3 4 5] |> drop 2
      """
    Then the result is [3 4 5]

  Scenario: count returns the number of elements
    Given the expression:
      """
      [1 2 3] |> count
      """
    Then the result is 3

  Scenario: sum adds all numeric elements
    Given the expression:
      """
      [10 20 30] |> sum
      """
    Then the result is 60

  Scenario: average computes the arithmetic mean
    Given the expression:
      """
      [10 20 30] |> average
      """
    Then the result is 20

  Scenario: median returns the middle value of a sorted odd-length collection
    Given the expression:
      """
      median [3 1 2]
      """
    Then the result is 2

  Scenario: median returns the average of the two middle values for even-length collection
    Given the expression:
      """
      median [3 1 4 2]
      """
    Then the result is 2.5

  Scenario: median of a single-element collection returns that element
    Given the expression:
      """
      median [42]
      """
    Then the result is 42

  Scenario: median of an empty collection returns nil
    Given the expression:
      """
      median []
      """
    Then the result is nil

  Scenario: median works in a pipeline
    Given the expression:
      """
      [1 2 3 4 5] |> median
      """
    Then the result is 3

  Scenario: distinct removes duplicate elements
    Given the expression:
      """
      [1 2 2 3 3 3] |> distinct
      """
    Then the result is [1 2 3]

  Scenario: flatten flattens one level of nesting
    Given the expression:
      """
      [[1 2] [3 4] [5]] |> flatten
      """
    Then the result is [1 2 3 4 5]

  Scenario: reverse reverses element order
    Given the expression:
      """
      [1 2 3] |> reverse
      """
    Then the result is [3 2 1]

  Scenario: first returns the first element
    Given the expression:
      """
      [10 20 30] |> first
      """
    Then the result is 10

  Scenario: last returns the last element
    Given the expression:
      """
      [10 20 30] |> last
      """
    Then the result is 30

  Scenario: nth returns the element at index N (zero-based)
    Given the expression:
      """
      [10 20 30] |> nth 1
      """
    Then the result is 20

  # ---------------------------------------------------------------------------
  # Chaining multiple built-in operations
  # ---------------------------------------------------------------------------

  Scenario: Real-world pipeline combining filter, map, sort-by, take
    Given the expression:
      """
      users
      |> filter _.age >= 18
      |> filter _.active
      |> map {name: _.name age: _.age}
      |> sort-by _.age
      |> take 10
      """
    Then the pipeline filters, projects, sorts, and limits in order
    And it compiles to Clojure:
      """
      (-> users
          (filter (fn [x] (>= (:age x) 18)))
          (filter (fn [x] (:active x)))
          (map (fn [x] {:name (:name x) :age (:age x)}))
          (sort-by (fn [x] (:age x)))
          (take 10))
      """

  Scenario: Pipeline with group-by followed by map over groups
    Given the expression:
      """
      orders
      |> group-by _.region
      |> map {region: _.key total: _.value |> map _.amount |> sum}
      """
    Then orders are grouped by region
    And each group is mapped to an object with the region name and total amount

  # ---------------------------------------------------------------------------
  # Pipeline with guards and pattern matching
  # ---------------------------------------------------------------------------

  Scenario: Guards inside map within a pipeline
    Given the expression:
      """
      users |> map {
        name: _.name
        tier:
          | _.spending > 1000 -> "gold"
          | _.spending > 100  -> "silver"
          | _                 -> "bronze"
      }
      """
    Then each user gets a tier based on their spending
    And _ in the guard default case matches any value (wildcard)
    And _ in _.spending refers to the current user

  Scenario: Guard as a standalone pipeline step
    Given the expression:
      """
      score
      |> | _ >= 90 -> "A"
         | _ >= 80 -> "B"
         | _ >= 70 -> "C"
         | _       -> "F"
      """
    Then the piped value is matched against the guard clauses
    And _ in each clause refers to the piped score value

  # ---------------------------------------------------------------------------
  # Pipeline with side effects (! functions)
  # ---------------------------------------------------------------------------

  Scenario: Side-effect function passes data through
    Given the expression:
      """
      data
      |> log! "start"
      |> process
      |> log! "end"
      |> save! "output.json"
      """
    Then log! prints a message and returns data unchanged
    And save! writes the data and returns it
    And the pipeline continues uninterrupted through side-effect steps
    And it compiles to Clojure:
      """
      (-> data
          (doto (log "start"))
          process
          (doto (log "end"))
          (doto (save "output.json")))
      """

  Scenario: Side-effect function at the end of a pipeline
    Given the expression:
      """
      users |> filter _.active |> save! "active-users.json"
      """
    Then the result of the pipeline is the filtered list (not the save result)

  # ---------------------------------------------------------------------------
  # Pipeline with user-defined functions
  # ---------------------------------------------------------------------------

  Scenario: User-defined function in a pipeline step
    Given the expression:
      """
      double is [x -> x * 2]
      [1 2 3] |> map double
      """
    Then double is used as the mapping function
    And the result is [2 4 6]

  Scenario: User-defined predicate in a pipeline step
    Given the expression:
      """
      adult? is [u -> u.age >= 18]
      users |> filter adult?
      """
    Then adult? is used as the filter predicate

  Scenario: Pipeline step calls a multi-argument user-defined function
    Given the expression:
      """
      clamp is [lo hi x ->
        | x < lo -> lo
        | x > hi -> hi
        | _      -> x
      ]
      numbers |> map (clamp 0 100 _)
      """
    Then each number is clamped between 0 and 100

  # ---------------------------------------------------------------------------
  # Pipeline with objects (map producing objects)
  # ---------------------------------------------------------------------------

  Scenario: Pipeline step that transforms elements into objects
    Given the expression:
      """
      names |> map {name: _ length: _ |> count}
      """
    Then each name string becomes an object with name and length fields

  Scenario: Pipeline step with nested object construction
    Given the expression:
      """
      users |> map {
        name: _.name
        stats: {
          score-count: _.scores |> count
          top-score: _.scores |> sort |> last
          average: _.scores |> average
        }
      }
      """
    Then each user is transformed into an object with nested stats

  # ---------------------------------------------------------------------------
  # Smart Map: Field Operations
  # ---------------------------------------------------------------------------
  #
  # Smart map field operations:
  # {+score: _.age * 2}                  | (map #(assoc % :score (* (:age %) 2)) data)
  # {-tmp}                               | (map #(dissoc % :tmp) data)
  # {+a: 1 +b: 2 -c}                    | (map #(-> % (assoc :a 1 :b 2) (dissoc :c)) data)
  # {name, age}                          | (map #(select-keys % [:name :age]) data)
  # {+tax: _.p * 0.1 +total: _.p + tax} | (map #(let [tax (* (:p %) 0.1)] (assoc % :tax tax :total (+ (:p %) tax))) data)

  Scenario: Map with + prefix adds field to existing object
    Given the DataTwist expression:
      """
      items is [{name: "Alice" age: 25} {name: "Bob" age: 30}]
      items |> map {+score: _.age * 2}
      """
    When it is evaluated
    Then the result is [{name: "Alice" age: 25 score: 50} {name: "Bob" age: 30 score: 60}]

  Scenario: Map with - prefix removes field from object
    Given the DataTwist expression:
      """
      items is [{name: "Alice" age: 25 tmp: true} {name: "Bob" age: 30 tmp: false}]
      items |> map {-tmp}
      """
    When it is evaluated
    Then the result is [{name: "Alice" age: 25} {name: "Bob" age: 30}]

  Scenario: Map with mixed + and - prefixes
    Given the DataTwist expression:
      """
      items is [{name: "Alice" age: 25 tmp: true}]
      items |> map {+score: _.age * 2  -tmp}
      """
    When it is evaluated
    Then the result is [{name: "Alice" age: 25 score: 50}]

  Scenario: Object shorthand in map
    Given the DataTwist expression:
      """
      items is [{name: "Alice" age: 25 email: "a@b.com"}]
      items |> map {name, age}
      """
    When it is evaluated
    Then the result is [{name: "Alice" age: 25}]
    # Shorthand: {name, age} = {name: _.name age: _.age}

  Scenario: Forward-referencing in map block
    Given the DataTwist expression:
      """
      items is [{price: 100}]
      items |> map {+tax: _.price * 0.1  +total: _.price + tax}
      """
    When it is evaluated
    Then the result is [{price: 100 tax: 10.0 total: 110.0}]
    # Later fields can reference earlier fields defined in the same block

  Scenario: Plain map without prefix creates new object
    Given the DataTwist expression:
      """
      items is [{name: "Alice" age: 25 email: "a@b.com"}]
      items |> map {name: _.name age: _.age}
      """
    When it is evaluated
    Then the result is [{name: "Alice" age: 25}]
    # Without + prefix, map creates a new object (does not keep existing fields)

  # ---------------------------------------------------------------------------
  # Inline vs multi-line equivalence
  # ---------------------------------------------------------------------------

  Scenario: Inline and multi-line pipelines are equivalent
    Given the inline expression:
      """
      data |> filter _.x |> map _.y |> count
      """
    And the multi-line expression:
      """
      data
      |> filter _.x
      |> map _.y
      |> count
      """
    Then both produce identical AST
    And both compile to the same Clojure output

  # ---------------------------------------------------------------------------
  # Pipeline with Clojure interop
  # ---------------------------------------------------------------------------

  Scenario: Pipeline calls a Clojure standard library function
    Given the expression:
      """
      text |> clj/clojure.string/upper-case
      """
    Then the Clojure function is called with text as its argument
    And it compiles to Clojure:
      """
      (-> text clojure.string/upper-case)
      """

  Scenario: Pipeline calls a Clojure function with extra arguments
    Given the expression:
      """
      text |> clj/clojure.string/split #","
      """
    Then split receives text as the first argument and the regex as the second

  # ---------------------------------------------------------------------------
  # Pipeline with predicate functions (?)
  # ---------------------------------------------------------------------------

  Scenario: Predicate function used in filter
    Given the expression:
      """
      even? is [n -> n % 2 = 0]
      [1 2 3 4 5 6] |> filter even?
      """
    Then the result is [2 4 6]

  Scenario: Negated predicate in a pipeline
    Given the expression:
      """
      even? is [n -> n % 2 = 0]
      [1 2 3 4 5 6] |> filter [n -> not (even? n)]
      """
    Then the result is [1 3 5]

  # ---------------------------------------------------------------------------
  # Nil behavior in pipelines
  # ---------------------------------------------------------------------------

  Scenario: Nil source in a pipeline
    Given the expression:
      """
      nil |> count
      """
    Then the result is 0 (nil-tolerant, count of nil is 0)

  Scenario: Pipeline step produces nil
    Given the expression:
      """
      users |> filter _.nonexistent-field |> count
      """
    Then _.nonexistent-field returns nil for each user (nil-tolerant field access)
    And filter removes nil/falsy values
    And count returns 0

  Scenario: Nil propagation through chained field access in pipeline
    Given the expression:
      """
      users |> map _.profile.address.city
      """
    Then if profile, address, or city is nil at any level, the result for that element is nil
    And the pipeline does not throw an error

  # ---------------------------------------------------------------------------
  # Error handling in pipelines
  # ---------------------------------------------------------------------------

  Scenario: Error in a pipeline step propagates as an exception
    Given the expression:
      """
      data |> process |> transform |> save! "out.json"
      """
    When process throws an error
    Then the pipeline stops at the failed step
    And the error propagates to the caller
    And transform and save! are NOT executed

  Scenario: Try-catch wrapping a pipeline
    Given the expression:
      """
      result is try
        data |> process |> transform
      catch err -> []
      """
    Then if any pipeline step throws, the catch clause handles it
    And result is bound to [] on error

  # ---------------------------------------------------------------------------
  # Edge cases and invalid pipelines
  # ---------------------------------------------------------------------------

  Scenario: Empty pipeline (no steps) is a parse error
    Given the expression:
      """
      data |>
      """
    Then it is a syntax error
    And the parser rejects this input

  Scenario: Pipeline operator without a source is a sourceless pipeline
    Given the expression:
      """
      |> filter _.active |> count
      """
    Then it creates a reusable transformer function (partial pipeline)
    And it is equivalent to:
      """
      [data -> data |> filter _.active |> count]
      """

  Scenario: Pipeline with only one value (no pipe operator) is just a value
    Given the expression:
      """
      42
      """
    Then it is just the literal 42, not a pipeline

  Scenario: Consecutive pipe operators with no function is a parse error
    Given the expression:
      """
      data |> |> count
      """
    Then it is a syntax error

  Scenario: Pipe operator inside a string literal is not parsed as pipeline
    Given the expression:
      """
      msg is "use |> for pipes"
      """
    Then |> inside the string is treated as literal text
    And msg is bound to the string "use |> for pipes"

  # ---------------------------------------------------------------------------
  # Pipeline branching and advanced patterns
  # ---------------------------------------------------------------------------

  Scenario: Tee for branching a pipeline into multiple paths
    Given the expression:
      """
      data |> tee [
        |> filter _.active |> save! "active.json"
        |> count |> log! "total"
      ]
      """
    Then tee runs each sub-pipeline on the same data
    And data continues through the main pipeline unchanged after tee

  Scenario: Pipeline result used in multiple downstream bindings
    Given the expression:
      """
      processed is data |> filter _.valid |> sort-by _.date
      recent is processed |> take 10
      oldest is processed |> last
      total is processed |> count
      """
    Then processed is computed once
    And recent, oldest, and total all derive from processed

  # ---------------------------------------------------------------------------
  # Pipeline with complex expressions
  # ---------------------------------------------------------------------------

  Scenario: Pipeline step with logical operators in predicate
    Given the expression:
      """
      users |> filter (_.age > 18 and _.status = "active")
      """
    Then the parenthesized expression is the predicate
    And _ refers to each user

  Scenario: Pipeline step with compound expression using or
    Given the expression:
      """
      users |> filter (_.role = "admin" or _.role = "moderator")
      """
    Then users with either role are kept

  Scenario: Pipeline step with not operator
    Given the expression:
      """
      users |> filter (not _.banned)
      """
    Then users where banned is falsy are kept

  Scenario: Pipeline with map producing arithmetic result
    Given the expression:
      """
      orders |> map _.price * _.quantity
      """
    Then each order is mapped to its total (price times quantity)
    And _ refers to the current order in both occurrences

  # ---------------------------------------------------------------------------
  # Pipeline mixing inline and multi-line forms
  # ---------------------------------------------------------------------------

  Scenario: Pipeline starts inline then continues multi-line
    Given the expression:
      """
      data |> filter _.active
      |> map _.name
      |> count
      """
    Then this is a valid single pipeline with three steps

  # ---------------------------------------------------------------------------
  # Pipeline with higher-order functions
  # ---------------------------------------------------------------------------

  Scenario: Pipeline step is a higher-order function returning a function
    Given the expression:
      """
      make-filter is [field val -> [item -> get item field = val]]
      users |> filter (make-filter "role" "admin")
      """
    Then make-filter returns a predicate function
    And that predicate is used in the filter step

  # ---------------------------------------------------------------------------
  # Pipeline performance and lazy evaluation
  # ---------------------------------------------------------------------------

  Scenario: Pipeline operations are lazy where possible
    Given the expression:
      """
      huge-list |> filter _ > 0 |> map _ * 2 |> take 5
      """
    Then the pipeline should not process the entire huge-list
    And only enough elements to satisfy take 5 are computed
    And it compiles to Clojure lazy sequences via ->

  # ---------------------------------------------------------------------------
  # Compilation mapping to Clojure
  # ---------------------------------------------------------------------------

  Scenario: Pipeline compiles to Clojure thread-first macro
    Given any pipeline expression:
      """
      x |> a |> b arg |> c
      """
    Then it compiles to:
      """
      (-> x a (b arg) c)
      """
    And thread-first inserts x as the first argument at each step

  Scenario: Pipeline with _ compiles to anonymous functions
    Given the expression:
      """
      items |> filter _.active |> map _.name
      """
    Then _ expressions compile to (fn [x] ...) in Clojure
    And it compiles to:
      """
      (-> items
          (filter (fn [x] (:active x)))
          (map (fn [x] (:name x))))
      """

  Scenario: Nested pipeline compiles to nested thread-first
    Given the expression:
      """
      users |> map {name: _.name top: _.scores |> sort |> last}
      """
    Then the inner pipeline compiles to a nested (->)
    And it compiles to:
      """
      (-> users
          (map (fn [u]
            {:name (:name u)
             :top (-> (:scores u) sort last)})))
      """

# ===========================================================================
# OPEN QUESTIONS
# ===========================================================================
#
# Q1: _ scoping -- lexical or dynamic?
#   The current design uses LEXICAL scoping: each |> introduces a new scope.
#   In `users |> map {name: _.name scores: _.scores |> filter _ > 80}`,
#   the inner `_ > 80` refers to each score, not to the user. This is because
#   `|> filter` opens a new pipeline context. But what about:
#     users |> map (_.scores |> filter _ > _.min-score)
#   Here the INNER _ in `_ > _.min-score` -- does _.min-score refer to the
#   score or the user? The rule should be: bare _ and _.field always refer to
#   the INNERMOST pipeline context. To reference the outer scope, use an
#   explicit lambda:
#     users |> map [u -> u.scores |> filter [s -> s > u.min-score]]
#
# Q2: Where exactly is _ valid?
#   Option A: _ is valid ONLY as a direct argument to a pipeline step.
#     users |> filter _.age > 18          -- valid
#     users |> filter (_.age > 18)        -- valid (parenthesized)
#     x is _.name                         -- INVALID (not in a pipeline context)
#   Option B: _ is valid anywhere there is an enclosing pipeline.
#   Recommendation: Option A. _ should desugar to a lambda only in the
#   immediate step of a |> operator. Outside that context, _ is the
#   pattern-matching wildcard (default case) only.
#
# Q3: How does `_` interact with `reduce`?
#   reduce needs two parameters (acc, element). _ can only refer to one.
#   Proposal: reduce ALWAYS requires an explicit lambda:
#     items |> reduce [acc x -> acc + x] 0
#   Using _ in reduce is a compile error:
#     items |> reduce _ + _              -- ERROR: ambiguous
#
# Q4: Pipeline as value -- is `|>` at the start a lambda or composition?
#   `normalize is |> filter _.active |> map _.name`
#   Option A: This is sugar for `[data -> data |> filter _.active |> map _.name]`
#   Option B: This is function composition (comp in Clojure)
#   Recommendation: Option A (lambda wrapping). It is simpler to implement
#   and more predictable. Clojure mapping: (fn [data] (-> data ...))
#
# Q5: Can _ appear multiple times in one pipeline step?
#   `items |> map {original: _ doubled: _ * 2}`
#   Yes. Both _ refer to the same element (the current pipeline value).
#   Compiles to: (fn [x] {:original x :doubled (* x 2)})
#
# Q6: Precedence of |> relative to other operators
#   `a + b |> c` -- is this `(a + b) |> c` or `a + (b |> c)`?
#   Recommendation: |> has the LOWEST precedence of all operators.
#   So `a + b |> c` means `(a + b) |> c`.
#   This matches Elixir behavior.
#
# Q7: Pipeline branching (tee)
#   Should tee be a built-in or a library function?
#   Proposal: Built-in. `data |> tee [branch1 branch2]` runs each branch
#   for side effects and returns data unchanged. Compiles to:
#     (doto data (-> branch1) (-> branch2))
#   This needs more design work. Low priority for v1.
#
# Q8: Empty collection behavior
#   [] |> filter _ > 5       -> []
#   [] |> map _ * 2          -> []
#   [] |> reduce [a b -> a + b] 0 -> 0
#   [] |> first              -> nil
#   [] |> count              -> 0
#   All consistent with Clojure behavior. No surprises.
#
# Q9: Pipeline with non-collection data
#   42 |> [x -> x * 2]       -> 84 (just function application)
#   "hello" |> count          -> 5 (count of string = length)
#   {name: "Alice"} |> _.name -> "Alice" (field access via pipeline)
#   Pipeline works with ANY value, not just collections.
#
# Q10: Thread-first vs thread-last
#   DataTwist uses pipe-first (->), meaning piped data is the FIRST argument.
#   This matches Elixir and is natural for field access and object operations.
#   However, Clojure's map/filter/reduce traditionally use thread-last (->>)
#   because the collection is the LAST argument.
#   Resolution: The compiler should be smart enough to detect collection
#   operations (filter, map, reduce, etc.) and use ->> for those, while
#   using -> for everything else. OR: wrap _ operations in lambdas so
#   argument position does not matter. The lambda approach is simpler and
#   avoids magic.
#
# Q11: `|>` with `is` precedence
#   `result is data |> filter _.x |> count`
#   Does `is` bind tighter than `|>`?
#   It must not: the entire pipeline is the right-hand side of `is`.
#   Parsing rule: `is` has LOWER precedence than `|>`.
#   Actually, `is` should have the LOWEST precedence in the language,
#   so `result is <everything to the right>`.
#
# Q12: What built-in operations are pipeline-aware?
#   These functions should accept a collection as a first argument:
#   filter, map, reduce, sort, sort-by, group-by, take, drop, count,
#   sum, average, distinct, flatten, reverse, first, last, nth,
#   zip, partition, chunk, take-while, drop-while, some, every,
#   find, frequencies, interleave, interpose, concat.
#   Not all need to be in v1. Core set for v1:
#   filter, map, reduce, sort-by, group-by, take, drop, count,
#   sum, average, distinct, flatten, reverse, first, last, nth.
#
# ===========================================================================
# CLOJURE COMPILATION MAPPING
# ===========================================================================
#
# DataTwist                          | Clojure
# -----------------------------------|------------------------------------------
# x |> f                             | (-> x f)
# x |> f a b                         | (-> x (f a b))
# x |> f |> g                        | (-> x f g)
# x |> filter _.a > 5                | (-> x (filter (fn [e] (> (:a e) 5))))
# x |> map _.name                    | (-> x (map (fn [e] (:name e))))
# x |> map {a: _.a b: _.b}           | (-> x (map (fn [e] {:a (:a e) :b (:b e)})))
# x |> reduce [a b -> a + b] 0       | (-> x (reduce (fn [a b] (+ a b)) 0))
# x |> sort-by _.age                 | (-> x (sort-by (fn [e] (:age e))))
# x |> log! "msg"                    | (-> x (doto (log "msg")))
# |> f |> g                          | (fn [d] (-> d f g))
# x |> map {s: _.xs |> filter _ > 0} | (-> x (map (fn [e] {:s (-> (:xs e)
#                                    |   (filter (fn [v] (> v 0))))})))
# x |> count                         | (-> x count)
# x |> take 5                        | (-> x (take 5))
# x |> nth 2                         | (-> x (nth 2))
# x |> group-by _.k                  | (-> x (group-by (fn [e] (:k e))))
#
# Smart map field operations:
# {+score: _.age * 2}                  | (map #(assoc % :score (* (:age %) 2)) data)
# {-tmp}                               | (map #(dissoc % :tmp) data)
# {+a: 1 +b: 2 -c}                    | (map #(-> % (assoc :a 1 :b 2) (dissoc :c)) data)
# {name, age}                          | (map #(select-keys % [:name :age]) data)
# {+tax: _.p * 0.1 +total: _.p + tax} | (map #(let [tax (* (:p %) 0.1)] (assoc % :tax tax :total (+ (:p %) tax))) data)
#
# ===========================================================================
# CORNER CASES
# ===========================================================================
#
# 1. Pipeline of length 1: `data |> count` -- valid, compiles to (-> data count)
# 2. Pipeline source is a literal: `[1 2 3] |> count` -- valid, result is 3
# 3. Pipeline source is a function call: `(get-data) |> filter _.x` -- valid
# 4. Pipeline source is another pipeline: `(a |> b) |> c` -- valid by associativity
# 5. Trailing |> at end of line with no step: `data |>\n` -- parse error
# 6. |> inside a string: `"a |> b"` -- literal text, not a pipeline
# 7. |> inside a lambda: `[x -> x |> f |> g]` -- valid pipeline inside lambda
# 8. Multiple _ in one step: `|> map _ + _` -- same element referenced twice
# 9. _ outside a pipeline: `x is _` -- only valid if _ is the pattern wildcard
# 10. Empty source pipeline: `nil |> filter _.x` -- nil-tolerant, returns empty
# 11. Pipeline that returns nil: `[] |> first` -- result is nil
# 12. Pipeline with type mismatch: `42 |> filter _ > 5` -- runtime error
#     (filter expects a collection; 42 is not iterable). Should this be a
#     compile-time warning?
# 13. Deeply nested pipelines: `a |> map (_.b |> map (_.c |> count))`
#     Each |> resets _. Innermost _ refers to c-level elements.
# 14. Pipeline step is a pipeline variable:
#     `transform is |> filter _.x |> map _.y`
#     `data |> transform |> count`
#     Valid. transform is a function, called with data as argument.
# 15. Pipeline with parenthesized step: `data |> (some-complex-expr)` --
#     the parenthesized expression must evaluate to a function.
# 16. Whitespace sensitivity: `data|>count` (no spaces) -- should this parse?
#     Recommendation: require at least `data |>count` or `data|> count`.
#     Safest: require spaces around |> always.
# 17. Pipeline in an if/guard branch:
#     `result is | condition -> data |> process | _ -> data`
#     The |> in the first branch must bind to `data`, not to the guard.
#     Parsing needs care here.
