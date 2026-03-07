@feature:functions-closures

Feature: Functions & Closures
  DataTwist uses `[params -> body]` as the single unified syntax for
  all anonymous and named functions. Functions are first-class values,
  compile to Clojure `fn`, and participate in pipe-first pipelines.

  Naming conventions carry semantic weight:
    - Trailing `?` marks predicates (must return boolean)
    - Trailing `!` marks side-effect functions (passthrough / doto semantics)

  # ---------------------------------------------------------------------------
  # Basic function definition
  # ---------------------------------------------------------------------------

  Scenario: Single-parameter function
    Given the source code:
      """
      double is [x -> x * 2]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def double (fn [x] (* x 2)))
      """
    And calling `double 5` returns `10`

  Scenario: Multi-parameter function
    Given the source code:
      """
      add is [a b -> a + b]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def add (fn [a b] (+ a b)))
      """
    And calling `add 3 4` returns `7`

  Scenario: Function with string formatting in body
    Given the source code:
      """
      greet is [name -> format "Hello, %s!" name]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def greet (fn [name] (format "Hello, %s!" name)))
      """
    And calling `greet "Alice"` returns `"Hello, Alice!"`

  Scenario: Function with complex arithmetic body
    Given the source code:
      """
      quadratic is [a b c x -> a * x * x + b * x + c]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def quadratic (fn [a b c x] (+ (* a x x) (* b x) c)))
      """

  # ---------------------------------------------------------------------------
  # Zero-parameter functions
  # ---------------------------------------------------------------------------

  Scenario: Zero-parameter function using arrow-only syntax
    Given the source code:
      """
      answer is [-> 42]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def answer (fn [] 42))
      """
    And calling `answer` returns `42`

  Scenario: Zero-parameter function as thunk / deferred computation
    Given the source code:
      """
      now is [-> current-time]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def now (fn [] (current-time)))
      """
    And each call to `now` may return a different value

  # ---------------------------------------------------------------------------
  # Predicate functions (trailing ?)
  # ---------------------------------------------------------------------------

  Scenario: Simple predicate function
    Given the source code:
      """
      even? is [n -> n % 2 = 0]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def even? (fn [n] (= (mod n 2) 0)))
      """
    And calling `even? 4` returns `true`
    And calling `even? 5` returns `false`

  Scenario: Predicate on object field
    Given the source code:
      """
      active? is [user -> user.status = "active"]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def active? (fn [user] (= (:status user) "active")))
      """

  Scenario: Predicate with compound logic
    Given the source code:
      """
      eligible? is [u -> u.age >= 18 and u.verified = true]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def eligible? (fn [u] (and (>= (:age u) 18) (= (:verified u) true))))
      """

  Scenario: Predicate used as higher-order argument
    Given the source code:
      """
      even? is [n -> n % 2 = 0]
      result is [1 2 3 4 5 6] |> filter even?
      """
    When compiled to Clojure
    Then `result` equals `[2 4 6]`

  # ---------------------------------------------------------------------------
  # Side-effect functions (trailing !)
  # ---------------------------------------------------------------------------

  Scenario: Side-effect function returns first argument (passthrough)
    Given the source code:
      """
      log! is [data msg -> print msg]
      """
    When compiled to Clojure
    Then it produces something equivalent to:
      """
      (def log! (fn [data msg] (print msg) data))
      """
    And calling `log! 42 "value"` prints `"value"` and returns `42`

  Scenario: Side-effect function in pipeline preserves data flow
    Given the source code:
      """
      data
      |> transform
      |> log! "after transform"
      |> save! "output.json"
      |> log! "done"
      """
    When compiled to Clojure
    Then each `!` function receives piped data as first arg
    And each `!` function executes its side effect
    And each `!` function returns its first argument unchanged
    And the pipeline result is the output of `transform`

  Scenario: Bang function with pipe-first argument ordering
    Given the source code:
      """
      data |> log! "msg"
      """
    When compiled to Clojure
    Then it is equivalent to `log! data "msg"`
    And `data` is the first argument (pipe-first)
    And `"msg"` is the second argument
    And the return value is `data`

  Scenario: Bang function called directly (not in pipeline)
    Given the source code:
      """
      result is log! my-data "processing"
      """
    Then `result` equals the value of `my-data`
    And the side effect (printing "processing") was executed

  # ---------------------------------------------------------------------------
  # Closures and lexical scope
  # ---------------------------------------------------------------------------

  Scenario: Inner function captures outer parameter
    Given the source code:
      """
      make-adder is [n -> [x -> x + n]]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def make-adder (fn [n] (fn [x] (+ x n))))
      """
    And calling `(make-adder 5) 3` returns `8`

  Scenario: Closure over binding
    Given the source code:
      """
      base is 100
      offset is [x -> x + base]
      """
    When compiled to Clojure
    Then `offset 10` returns `110`
    And `base` is captured from the enclosing scope

  Scenario: Nested closures with multiple levels
    Given the source code:
      """
      make-scaler is [factor -> [offset -> [x -> x * factor + offset]]]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def make-scaler (fn [factor] (fn [offset] (fn [x] (+ (* x factor) offset)))))
      """
    And calling `((make-scaler 2) 10) 5` returns `20`

  Scenario: Closure captures mutable-like state via atom (interop)
    Given the source code:
      """
      make-counter is [->
        state is atom 0
        [-> swap! state inc]
      ]
      """
    When compiled to Clojure
    Then the returned function increments and returns the counter
    And each call returns the next integer

  # ---------------------------------------------------------------------------
  # Higher-order functions
  # ---------------------------------------------------------------------------

  Scenario: Function that takes a function as argument
    Given the source code:
      """
      apply-twice is [f x -> f (f x)]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def apply-twice (fn [f x] (f (f x))))
      """
    And calling `apply-twice [x -> x * 2] 3` returns `12`

  Scenario: Function that returns a function
    Given the source code:
      """
      multiplier is [n -> [x -> x * n]]
      triple is multiplier 3
      """
    Then calling `triple 7` returns `21`

  Scenario: Passing named function as value
    Given the source code:
      """
      double is [x -> x * 2]
      numbers is [1 2 3 4 5]
      result is numbers |> map double
      """
    Then `result` equals `[2 4 6 8 10]`

  Scenario: Passing predicate as higher-order argument
    Given the source code:
      """
      even? is [n -> n % 2 = 0]
      result is [1 2 3 4 5 6] |> filter even?
      """
    Then `result` equals `[2 4 6]`

  # ---------------------------------------------------------------------------
  # Anonymous functions in pipelines
  # ---------------------------------------------------------------------------

  Scenario: Inline anonymous function in map
    Given the source code:
      """
      result is users |> map [u -> u.name]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def result (map (fn [u] (:name u)) users))
      """

  Scenario: Inline anonymous function in filter
    Given the source code:
      """
      result is users |> filter [u -> u.age > 18]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def result (filter (fn [u] (> (:age u) 18)) users))
      """

  Scenario: Multiple inline functions in a pipeline
    Given the source code:
      """
      result is users
      |> filter [u -> u.active = true]
      |> map [u -> u.name]
      |> sort-by [name -> str/lower-case name]
      """
    When compiled to Clojure
    Then each step uses the inline function as the transformation

  # ---------------------------------------------------------------------------
  # Wildcard `_` as implicit anonymous function
  # ---------------------------------------------------------------------------

  Scenario: Wildcard field access desugars to anonymous function
    Given the source code:
      """
      users |> filter _.age > 18
      """
    When compiled to Clojure
    Then it desugars to:
      """
      users |> filter [x -> x.age > 18]
      """
    And compiles to:
      """
      (filter (fn [x] (> (:age x) 18)) users)
      """

  Scenario: Bare wildcard as identity reference
    Given the source code:
      """
      items |> filter _ != nil
      """
    When compiled to Clojure
    Then it desugars to:
      """
      items |> filter [x -> x != nil]
      """
    And compiles to:
      """
      (filter (fn [x] (not= x nil)) items)
      """

  Scenario: Wildcard in map with object construction
    Given the source code:
      """
      users |> map {name: _.name age: _.age}
      """
    When compiled to Clojure
    Then the entire `{name: _.name age: _.age}` becomes:
      """
      (fn [x] {:name (:name x) :age (:age x)})
      """

  Scenario: Wildcard vs explicit function -- equivalent
    Given the source code:
      """
      a is users |> filter _.age > 18
      b is users |> filter [u -> u.age > 18]
      """
    Then `a` and `b` produce identical results

  Scenario: Nested pipeline wildcard scoping
    Given the source code:
      """
      users |> map {
        name: _.name
        top-scores: _.scores |> filter [s -> s > 80] |> take 3
      }
      """
    Then the outer `_` refers to each user
    And the inner `[s -> ...]` has its own scope
    And there is no ambiguity because the inner pipeline uses an explicit function

  # ---------------------------------------------------------------------------
  # Variadic functions
  # ---------------------------------------------------------------------------

  Scenario: Variadic function with rest parameter
    Given the source code:
      """
      sum-all is [& nums -> nums |> reduce [a b -> a + b] 0]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def sum-all (fn [& nums] (reduce (fn [a b] (+ a b)) 0 nums)))
      """
    And calling `sum-all 1 2 3 4 5` returns `15`

  Scenario: Variadic function with leading fixed parameters
    Given the source code:
      """
      log-all is [level & messages -> messages |> each [m -> print (format "[%s] %s" level m)]]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def log-all (fn [level & messages] (doseq [m messages] (print (format "[%s] %s" level m)))))
      """
    And calling `log-all "INFO" "a" "b" "c"` prints three formatted messages

  # ---------------------------------------------------------------------------
  # Multi-arity functions (arity overloading)
  # ---------------------------------------------------------------------------

  Scenario: Function with multiple arities
    Given the source code:
      """
      greet is
        [-> "Hello, World!"]
        [name -> format "Hello, %s!" name]
        [first last -> format "Hello, %s %s!" first last]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def greet
        (fn
          ([] "Hello, World!")
          ([name] (format "Hello, %s!" name))
          ([first last] (format "Hello, %s %s!" first last))))
      """
    And calling `greet` returns `"Hello, World!"`
    And calling `greet "Alice"` returns `"Hello, Alice!"`
    And calling `greet "Alice" "Smith"` returns `"Hello, Alice Smith!"`

  Scenario: Multi-arity with default-like behavior
    Given the source code:
      """
      range-of is
        [end -> range-of 0 end 1]
        [start end -> range-of start end 1]
        [start end step -> clj/range start end step]
      """
    When compiled to Clojure
    Then lower arities delegate to the full arity

  # ---------------------------------------------------------------------------
  # Recursion
  # ---------------------------------------------------------------------------

  Scenario: Named recursion by self-reference
    Given the source code:
      """
      factorial is [n ->
        | n <= 1 -> 1
        | _      -> n * factorial (n - 1)
      ]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def factorial
        (fn [n]
          (cond
            (<= n 1) 1
            :else (* n (factorial (- n 1))))))
      """
    And calling `factorial 5` returns `120`

  Scenario: Tail recursion with recur
    Given the source code:
      """
      factorial is [n ->
        go is [n acc ->
          | n <= 1 -> acc
          | _      -> recur (n - 1) (acc * n)
        ]
        go n 1
      ]
      """
    When compiled to Clojure
    Then the inner `recur` compiles to Clojure `recur`
    And it does not consume stack for large `n`

  Scenario: Recur in a simple loop-like pattern
    Given the source code:
      """
      count-down is [n ->
        | n <= 0 -> "done"
        | _      -> recur (n - 1)
      ]
      """
    When compiled to Clojure
    Then `recur` maps to Clojure `recur` for TCO
    And calling `count-down 1000000` does not overflow the stack

  # ---------------------------------------------------------------------------
  # Function composition
  # ---------------------------------------------------------------------------

  Scenario: Composition operator >>
    Given the source code:
      """
      double is [x -> x * 2]
      inc is [x -> x + 1]
      double-then-inc is double >> inc
      """
    When compiled to Clojure
    Then it produces:
      """
      (def double-then-inc (comp inc double))
      """
    And calling `double-then-inc 3` returns `7`

  Scenario: Reverse composition operator <<
    Given the source code:
      """
      double is [x -> x * 2]
      inc is [x -> x + 1]
      inc-then-double is double << inc
      """
    When compiled to Clojure
    Then it produces:
      """
      (def inc-then-double (comp double inc))
      """
    And calling `inc-then-double 3` returns `8`

  Scenario: Chained composition
    Given the source code:
      """
      process is parse >> validate >> transform >> serialize
      """
    When compiled to Clojure
    Then it produces:
      """
      (def process (comp serialize transform validate parse))
      """

  # ---------------------------------------------------------------------------
  # Partial application
  # ---------------------------------------------------------------------------

  Scenario: Explicit partial application
    Given the source code:
      """
      add is [a b -> a + b]
      add5 is partial add 5
      """
    When compiled to Clojure
    Then it produces:
      """
      (def add5 (partial add 5))
      """
    And calling `add5 3` returns `8`

  Scenario: Partial application in pipeline
    Given the source code:
      """
      result is [1 2 3] |> map (partial add 10)
      """
    When compiled to Clojure
    Then `result` equals `[11 12 13]`

  # ---------------------------------------------------------------------------
  # Functions as values in data structures
  # ---------------------------------------------------------------------------

  Scenario: Function as object field value
    Given the source code:
      """
      handler is {
        on-click: [e -> process-event e]
        on-hover: [e -> highlight e.target]
        validate: [data -> data.name != nil]
      }
      """
    When compiled to Clojure
    Then it produces:
      """
      (def handler
        {:on-click (fn [e] (process-event e))
         :on-hover (fn [e] (highlight (:target e)))
         :validate (fn [data] (not= (:name data) nil))})
      """

  Scenario: Function in a list
    Given the source code:
      """
      transforms is [
        [x -> x * 2]
        [x -> x + 1]
        [x -> x * x]
      ]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def transforms
        [(fn [x] (* x 2))
         (fn [x] (+ x 1))
         (fn [x] (* x x))])
      """

  Scenario: Retrieving and calling a function from an object
    Given the source code:
      """
      handler is {process: [x -> x * 2]}
      result is handler.process 10
      """
    Then `result` equals `20`

  # ---------------------------------------------------------------------------
  # Multi-expression function bodies
  # ---------------------------------------------------------------------------

  Scenario: Function body is always a single expression
    Given the source code:
      """
      double is [x -> x * 2]
      """
    Then the body `x * 2` is a single expression
    And the function returns the result of that expression

  Scenario: Local bindings inside function body using let-style is
    Given the source code:
      """
      hypotenuse is [a b ->
        a2 is a * a
        b2 is b * b
        sqrt (a2 + b2)
      ]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def hypotenuse
        (fn [a b]
          (let [a2 (* a a)
                b2 (* b b)]
            (Math/sqrt (+ a2 b2)))))
      """

  Scenario: Pipeline inside function body
    Given the source code:
      """
      process-users is [users ->
        users
        |> filter _.active
        |> map _.name
        |> sort
      ]
      """
    When compiled to Clojure
    Then the pipeline is the function body
    And the result of the pipeline is the return value

  Scenario: Multiple bindings then final expression
    Given the source code:
      """
      analyze is [data ->
        cleaned is data |> filter _ != nil
        count is cleaned |> length
        total is cleaned |> reduce [a b -> a + b] 0
        {count: count average: total / count}
      ]
      """
    When compiled to Clojure
    Then it compiles to nested `let` or a single `let` block
    And the final expression `{count: count average: total / count}` is the return value

  # ---------------------------------------------------------------------------
  # Pattern matching inside functions
  # ---------------------------------------------------------------------------

  Scenario: Guard-style pattern match as function body
    Given the source code:
      """
      classify is [data ->
        | data.type = "book" and data.pages > 500 -> "epic"
        | data.type = "book"                      -> "book"
        | data.type = "movie" and data.rating > 8  -> "great film"
        | _                                        -> "unknown"
      ]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def classify
        (fn [data]
          (cond
            (and (= (:type data) "book") (> (:pages data) 500)) "epic"
            (= (:type data) "book") "book"
            (and (= (:type data) "movie") (> (:rating data) 8)) "great film"
            :else "unknown")))
      """

  Scenario: Structural pattern matching as function body
    Given the source code:
      """
      describe is [item ->
        | {type: "book"  pages: p} when p > 500 -> "epic"
        | {type: "book"}                         -> "book"
        | [x]                                    -> "single"
        | [x & rest]                             -> "collection"
        | nil                                    -> "nothing"
        | _                                      -> "unknown"
      ]
      """
    When compiled to Clojure
    Then each `|` clause is a match branch
    And `when` adds a guard to a structural match
    And `_` is the default/else clause

  # ---------------------------------------------------------------------------
  # Pipe-first interaction with functions
  # ---------------------------------------------------------------------------

  Scenario: Pipe-first passes data as first argument
    Given the source code:
      """
      add is [a b -> a + b]
      result is 5 |> add 3
      """
    Then `result` equals `8`
    And `5 |> add 3` is equivalent to `add 5 3`

  Scenario: Pipeline with zero-arg function call
    Given the source code:
      """
      result is data |> count
      """
    When compiled to Clojure
    Then it produces:
      """
      (def result (count data))
      """

  Scenario: Pipeline with multi-arg function
    Given the source code:
      """
      result is data |> take 10
      """
    When compiled to Clojure
    Then it produces:
      """
      (def result (take 10 data))
      """
    And `data` is inserted as the first argument

  # ---------------------------------------------------------------------------
  # Edge cases and interactions
  # ---------------------------------------------------------------------------

  Scenario: Empty list literal vs zero-param function
    Given the source code `[]`
    Then it is parsed as an empty list literal
    And `[-> 42]` is parsed as a zero-param function (because of `->`)

  Scenario: Single-element list vs function ambiguity resolved by arrow
    Given the source code `[x]`
    Then it is parsed as a list containing identifier `x`
    And `[x -> x]` is parsed as an identity function (because of `->`)

  Scenario: Nested function in list position
    Given the source code:
      """
      fns is [[x -> x + 1] [x -> x * 2]]
      """
    Then it is parsed as a list of two functions
    And the `->` disambiguates functions from list elements

  Scenario: Function returning an object
    Given the source code:
      """
      make-point is [x y -> {x: x y: y}]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def make-point (fn [x y] {:x x :y y}))
      """

  Scenario: Function returning a list
    Given the source code:
      """
      pair is [a b -> [a b]]
      """
    When compiled to Clojure
    Then it produces:
      """
      (def pair (fn [a b] [a b]))
      """
    And `[a b]` in the body is a list (no `->` present)

  Scenario: Deeply nested closures and shadowing
    Given the source code:
      """
      outer is [x ->
        mid is [x -> [y -> x + y]]
        mid (x + 1)
      ]
      """
    Then the inner `x` shadows the outer `x`
    And calling `(outer 10) 5` returns `16` because mid receives `11`

  Scenario: Function with no arguments called in expression position
    Given the source code:
      """
      now is [-> current-time]
      ts is now
      """
    Then `ts` is bound to the function `now` itself (not called)
    And `now` must be called explicitly as `now()` or in a pipeline to invoke

  Scenario: Calling zero-arg function explicitly with parens
    Given the source code:
      """
      now is [-> current-time]
      ts is now()
      """
    Then `ts` is the result of calling `now`
    And `now()` compiles to `(now)`

  # ---------------------------------------------------------------------------
  # Disambiguation: list vs function
  # ---------------------------------------------------------------------------

  Scenario: Parser uses `->` to distinguish function from list
    Given the grammar rule for `[...]`
    Then if `->` appears at the top level inside brackets, it is a function
    And if `->` does not appear, it is a list literal
    And this is unambiguous because `->` is not a valid list element


# ===========================================================================
# CLOJURE MAPPING REFERENCE
# ===========================================================================
#
# DataTwist                          | Clojure
# -----------------------------------|----------------------------------------
# [x -> x + 1]                      | (fn [x] (+ x 1))
# [a b -> a + b]                    | (fn [a b] (+ a b))
# [-> 42]                           | (fn [] 42)
# [& args -> ...]                   | (fn [& args] ...)
# [a b & rest -> ...]               | (fn [a b & rest] ...)
# double is [x -> x * 2]            | (def double (fn [x] (* x 2)))
# even? is [n -> n % 2 = 0]         | (def even? (fn [n] (= (mod n 2) 0)))
# log! data "msg"                   | (do (log data "msg") data)  ; returns data
# make-adder is [n -> [x -> x+n]]   | (def make-adder (fn [n] (fn [x] (+ x n))))
# f >> g                            | (comp g f)       ; left-to-right
# f << g                            | (comp f g)       ; right-to-left
# partial add 5                     | (partial add 5)
# data |> f args                    | (f data args)    ; pipe-first
# users |> filter _.age > 18        | (filter (fn [x] (> (:age x) 18)) users)
# {handler: [x -> x * 2]}          | {:handler (fn [x] (* x 2))}
# greet is                          | (def greet
#   [-> "Hello!"]                   |   (fn
#   [name -> ...]                   |     ([] "Hello!")
#                                   |     ([name] ...)))
# recur args                        | (recur args)     ; TCO
#
# Multi-expression body:
# [x ->                             | (fn [x]
#   a is x * 2                      |   (let [a (* x 2)
#   b is a + 1                      |         b (+ a 1)]
#   a + b                           |     (+ a b)))
# ]
#
# ===========================================================================
# OPEN QUESTIONS
# ===========================================================================
#
# Q1: Zero-param syntax: `[-> 42]` vs `[_ -> 42]`?
#     DECISION: `[-> 42]`. The `_` variant would create an unused param.
#     `[-> 42]` maps cleanly to `(fn [] 42)` and mirrors Elixir `fn -> 42 end`.
#     `[_ -> 42]` should mean a function that takes one argument and ignores it.
#
# Q2: How to call zero-arg functions?
#     Bare `now` is a reference to the function value (for passing around).
#     `now()` invokes it. This mirrors Clojure where `now` is a var deref
#     and `(now)` is invocation. Explicit parens `()` needed for zero-arg call.
#     In pipeline position: `data |> count` -- count receives data, so it's
#     not truly zero-arg in that context.
#
# Q3: Implicit partial application vs explicit `partial`?
#     DECISION: Explicit only via `partial`. Implicit currying would make
#     arity errors silent -- `add 5` would return a partial instead of
#     erroring on missing args. Explicit is clearer and matches Clojure.
#
# Q4: `_` in wildcard position -- scope boundaries?
#     `_` binds to the innermost pipeline context. Inside a nested pipeline,
#     `_` refers to the inner element. If you need the outer element, use an
#     explicit function `[outer -> ... |> map [inner -> ...]]`. The compiler
#     should detect `_` usage and wrap the containing expression in `(fn [x] ...)`.
#     Corner case: `_.scores |> filter [s -> s > 80]` inside a map -- the
#     `_` before `|>` refers to the outer pipeline's current element.
#
# Q5: Multi-arity syntax -- how to parse?
#     Proposal: `name is` followed by multiple `[params -> body]` on separate
#     lines (each indented). The parser sees the name bound to multiple
#     bracket-arrow forms and produces a multi-arity fn.
#     Corner case: how to distinguish from a list of functions?
#     Answer: `name is [p -> b] [p2 -> b2]` at top level = multi-arity.
#     `name is [[p -> b] [p2 -> b2]]` with extra brackets = list of functions.
#
# Q6: `recur` -- always available or only in tail position?
#     DECISION: Mirror Clojure. `recur` is only valid in tail position.
#     Compiler should emit an error if recur is used in non-tail position.
#     Named self-recursion (calling function by name) is always allowed
#     but does not guarantee TCO.
#
# Q7: `>>` composition direction?
#     `f >> g` means "f then g" (left-to-right, data flow direction).
#     This is the opposite of Clojure's `comp` which is right-to-left.
#     `>>` compiles to `(comp g f)` -- the compiler reverses the order.
#     `<<` is provided for right-to-left (same direction as `comp`).
#
# Q8: `!` semantics -- what exactly is "first argument"?
#     For `log! data "msg"`: `data` is first positional arg, returned.
#     For `data |> log! "msg"`: pipe-first inserts data as first arg,
#     so it becomes `log! data "msg"`, data is returned.
#     For `data |> save! "file" "format"`: becomes `save! data "file" "format"`,
#     returns data.
#     The compiler wraps the body: `(fn [data ...rest] (original-body) data)`.
#     The `!` convention is enforced by the compiler, not just a naming hint.
#
# Q9: Can `!` functions be nested? `save! (log! data "x") "file"`?
#     Yes. `log! data "x"` returns `data`, so `save! data "file"` gets data.
#     This is natural with passthrough semantics.
#
# Q10: Multi-expression body -- implicit `let` or explicit?
#      DECISION: Implicit. When the function body contains `name is expr`
#      bindings followed by a final expression, the compiler emits a `let`.
#      No explicit `let` keyword needed. This keeps the syntax clean.
#      The last expression in the body is always the return value.
#
# Q11: Can functions have docstrings?
#      TBD. Possible syntax:
#        double is [x -> "Doubles a number" x * 2]  -- ambiguous with string return
#        double is "Doubles a number" [x -> x * 2]   -- before the function
#      Defer to later. Not critical for MVP.
#
# Q12: Anonymous function directly calling itself (IIFE)?
#      `[x -> x * 2] 5` -- is this valid? Should parse as calling the anon
#      function with arg 5. Compiles to `((fn [x] (* x 2)) 5)`.
#      Useful but potentially confusing. Allow it, document it.
#
# ===========================================================================
# CORNER CASES
# ===========================================================================
#
# C1: `[x -> [y -> x + y]]` -- function returning function.
#     Inner `[y -> ...]` is unambiguous because `->` is present.
#     The inner brackets are a function, not a list.
#
# C2: `[f -> f [1 2 3]]` -- function taking f, calling f with a list.
#     `[1 2 3]` has no `->`, so it is a list. Unambiguous.
#
# C3: `[-> [1 2 3]]` -- zero-arg function returning a list.
#     `[-> ...]` is a function. The body `[1 2 3]` is a list. Unambiguous.
#
# C4: `[-> [-> 42]]` -- zero-arg function returning a zero-arg function.
#     Both `->` markers disambiguate. Parses correctly.
#
# C5: `[[x -> x] [y -> y]]` -- list containing two functions.
#     Outer brackets have no top-level `->`, so it is a list.
#     Inner brackets each have `->`, so they are functions. Unambiguous.
#
# C6: `sort-by [x -> x.name] users` -- function arg before data arg.
#     The `[x -> x.name]` is unambiguously a function. Standard call syntax.
#
# C7: Predicate `?` and bang `!` in identifiers that are not functions.
#     `active?` is always treated as an identifier. The `?`/`!` convention
#     is semantic, not syntactic -- the parser does not enforce it.
#     The compiler enforces `!` passthrough semantics at definition site.
#
# C8: `[x -> x |> double |> inc]` -- pipeline inside function body.
#     The `->` starts the function body. The `|>` operators are part of
#     the body expression. No ambiguity.
#
# C9: Shadowing in closures:
#     `[x -> [x -> x + 1]]` -- inner x shadows outer x.
#     Compiler should emit a warning, not an error.
#
# C10: Variadic + destructuring interaction:
#      `[{name age} & rest -> ...]` -- first arg is destructured,
#      remaining args collected. Maps to Clojure:
#      `(fn [{:keys [name age]} & rest] ...)`
#
# C11: Multi-arity with variadic:
#      Only the highest arity can be variadic (Clojure constraint).
#      `name is [x -> ...] [x & rest -> ...]` -- valid.
#      `name is [& args -> ...] [x -> ...]` -- compile error.
#
# C12: `_` used inside an explicit function body:
#      `users |> map [u -> u.scores |> filter _ > 80]`
#      Here `_` inside the explicit function refers to the inner pipeline
#      context (each score), not to `u`. The explicit `[u -> ...]` already
#      captures the outer element. `_` is re-bound to the inner pipeline.
