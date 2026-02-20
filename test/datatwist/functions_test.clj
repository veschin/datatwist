(ns datatwist.functions-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? type-of throws?
                                            silent-eval-dt silent-eval-dt-last silent-throws?]]))

;; ==========================================================================
;; Feature 3: Functions & Closures
;;
;; DataTwist uses `[params -> body]` as the single unified syntax for
;; all anonymous and named functions. Functions are first-class values,
;; compile to Clojure `fn`, and participate in pipe-first pipelines.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; Basic function definition
;; ---------------------------------------------------------------------------

(deftest single-parameter-function
  (testing "Scenario: Single-parameter function"
    (testing "defines a function with [x -> x * 2] syntax"
      (is (= 10 (eval-dt-last "double is [x -> x * 2]" "double 5"))))))

(deftest multi-parameter-function
  (testing "Scenario: Multi-parameter function"
    (testing "defines a function with [a b -> a + b] syntax"
      (is (= 7 (eval-dt-last "add is [a b -> a + b]" "add 3 4"))))))

(deftest function-with-string-formatting-in-body
  (testing "Scenario: Function with string formatting in body"
    (testing "format call within function body works"
      (is (= "Hello, Alice!"
             (eval-dt-last "greet is [name -> format \"Hello, %s!\" name]"
                           "greet \"Alice\""))))))

(deftest function-with-complex-arithmetic-body
  (testing "Scenario: Function with complex arithmetic body"
    (testing "quadratic polynomial evaluation: a*x*x + b*x + c"
      ;; quadratic 1 2 3 2 => 1*4 + 2*2 + 3 = 11
      (is (= 11 (eval-dt-last "quadratic is [a b c x -> a * x * x + b * x + c]"
                              "quadratic 1 2 3 2"))))))

;; ---------------------------------------------------------------------------
;; Zero-parameter functions
;; ---------------------------------------------------------------------------

(deftest zero-parameter-function-arrow-only-syntax
  (testing "Scenario: Zero-parameter function using arrow-only syntax"
    (testing "[-> 42] defines a zero-arg function returning 42"
      (is (= 42 (eval-dt-last "answer is [-> 42]" "answer()"))))))

(deftest zero-parameter-function-as-thunk
  (testing "Scenario: Zero-parameter function as thunk / deferred computation"
    (testing "[-> current-time] wraps a deferred computation"
      (let [f-type (type-of "[-> 42]")]
        (is (some? f-type) "A zero-param function literal should produce a value")))))

;; ---------------------------------------------------------------------------
;; Predicate functions (trailing ?)
;; ---------------------------------------------------------------------------

(deftest simple-predicate-function
  (testing "Scenario: Simple predicate function"
    (testing "even? returns true for even numbers, false for odd"
      (is (= true  (eval-dt-last "even? is [n -> n % 2 = 0]" "even? 4")))
      (is (= false (eval-dt-last "even? is [n -> n % 2 = 0]" "even? 5"))))))

(deftest predicate-on-object-field
  (testing "Scenario: Predicate on object field"
    (testing "active? checks user.status = \"active\""
      (is (= true
             (eval-dt-last "active? is [user -> user.status = \"active\"]"
                           "active? {status: \"active\"}"))))))

(deftest predicate-with-compound-logic
  (testing "Scenario: Predicate with compound logic"
    (testing "eligible? uses 'and' to combine conditions"
      (is (= true
             (eval-dt-last "eligible? is [u -> u.age >= 18 and u.verified = true]"
                           "eligible? {age: 21 verified: true}")))
      (is (= false
             (eval-dt-last "eligible? is [u -> u.age >= 18 and u.verified = true]"
                           "eligible? {age: 16 verified: true}"))))))

(deftest predicate-used-as-higher-order-argument
  (testing "Scenario: Predicate used as higher-order argument"
    (testing "filter even? selects even numbers from a list"
      (is (= [2 4 6]
             (eval-dt-last "even? is [n -> n % 2 = 0]"
                           "result is [1 2 3 4 5 6] |> filter even?"
                           "result"))))))

;; ---------------------------------------------------------------------------
;; Side-effect functions (trailing !)
;; ---------------------------------------------------------------------------

(deftest side-effect-function-returns-first-argument
  (testing "Scenario: Side-effect function returns first argument (passthrough)"
    (testing "log! returns its first argument (data) after side-effect"
      (is (= 42 (silent-eval-dt-last "log! is [data msg -> print msg]"
                                     "log! 42 \"value\""))))))

(deftest side-effect-function-in-pipeline-preserves-data-flow
  (testing "Scenario: Side-effect function in pipeline preserves data flow"
    (testing "each ! function in pipeline passes data through unchanged"
      ;; The pipeline result is the output of transform, since ! fns return first arg
      (is (= 20
             (silent-eval-dt-last "transform is [x -> x * 2]"
                                  "log! is [data msg -> print msg]"
                                  "10 |> transform |> log! \"after transform\""))))))

(deftest bang-function-with-pipe-first-argument-ordering
  (testing "Scenario: Bang function with pipe-first argument ordering"
    (testing "data |> log! \"msg\" is equivalent to log! data \"msg\""
      (is (= 42
             (silent-eval-dt-last "log! is [data msg -> print msg]"
                                  "42 |> log! \"msg\""))))))

(deftest bang-function-called-directly
  (testing "Scenario: Bang function called directly (not in pipeline)"
    (testing "result is log! my-data \"processing\" binds result to my-data"
      (is (= 99
             (silent-eval-dt-last "log! is [data msg -> print msg]"
                                  "my-data is 99"
                                  "result is log! my-data \"processing\""
                                  "result"))))))

;; ---------------------------------------------------------------------------
;; Closures and lexical scope
;; ---------------------------------------------------------------------------

(deftest inner-function-captures-outer-parameter
  (testing "Scenario: Inner function captures outer parameter"
    (testing "make-adder returns a closure that adds n"
      (is (= 8
             (eval-dt-last "make-adder is [n -> [x -> x + n]]"
                           "add5 is make-adder 5"
                           "add5 3"))))))

(deftest closure-over-binding
  (testing "Scenario: Closure over binding"
    (testing "function captures base from enclosing scope"
      (is (= 110
             (eval-dt-last "base is 100"
                           "offset is [x -> x + base]"
                           "offset 10"))))))

(deftest nested-closures-with-multiple-levels
  (testing "Scenario: Nested closures with multiple levels"
    (testing "three-level nested closure: make-scaler factor -> offset -> x"
      ;; ((make-scaler 2) 10) 5 => 5 * 2 + 10 = 20
      (is (= 20
             (eval-dt-last "make-scaler is [factor -> [offset -> [x -> x * factor + offset]]]"
                           "scale2 is make-scaler 2"
                           "scale2-10 is scale2 10"
                           "scale2-10 5"))))))

(deftest closure-captures-mutable-state-via-atom
  (testing "Scenario: Closure captures mutable-like state via atom (interop)"
    (testing "make-counter returns a function that increments on each call"
      (let [r1 (eval-dt-last "make-counter is [-> state is atom 0\n[-> swap! state inc]]"
                             "c is make-counter()"
                             "c()")
            r2 (eval-dt-last "make-counter is [-> state is atom 0\n[-> swap! state inc]]"
                             "c is make-counter()"
                             "c()\nc()")]
        (is (= 1 r1) "First call returns 1")
        (is (= 2 r2) "Second call returns 2")))))

;; ---------------------------------------------------------------------------
;; Higher-order functions
;; ---------------------------------------------------------------------------

(deftest function-takes-function-as-argument
  (testing "Scenario: Function that takes a function as argument"
    (testing "apply-twice applies f to x twice"
      (is (= 12
             (eval-dt-last "apply-twice is [f x -> f (f x)]"
                           "apply-twice [x -> x * 2] 3"))))))

(deftest function-returns-function
  (testing "Scenario: Function that returns a function"
    (testing "multiplier returns a closure; triple = multiplier 3"
      (is (= 21
             (eval-dt-last "multiplier is [n -> [x -> x * n]]"
                           "triple is multiplier 3"
                           "triple 7"))))))

(deftest passing-named-function-as-value
  (testing "Scenario: Passing named function as value"
    (testing "map double over a list of numbers"
      (is (= [2 4 6 8 10]
             (eval-dt-last "double is [x -> x * 2]"
                           "numbers is [1 2 3 4 5]"
                           "result is numbers |> map double"
                           "result"))))))

(deftest passing-predicate-as-higher-order-argument
  (testing "Scenario: Passing predicate as higher-order argument"
    (testing "filter even? selects even numbers"
      (is (= [2 4 6]
             (eval-dt-last "even? is [n -> n % 2 = 0]"
                           "result is [1 2 3 4 5 6] |> filter even?"
                           "result"))))))

;; ---------------------------------------------------------------------------
;; Anonymous functions in pipelines
;; ---------------------------------------------------------------------------

(deftest inline-anonymous-function-in-map
  (testing "Scenario: Inline anonymous function in map"
    (testing "users |> map [u -> u.name] extracts names"
      (is (= ["Alice" "Bob"]
             (eval-dt-last "users is [{name: \"Alice\"} {name: \"Bob\"}]"
                           "result is users |> map [u -> u.name]"
                           "result"))))))

(deftest inline-anonymous-function-in-filter
  (testing "Scenario: Inline anonymous function in filter"
    (testing "users |> filter [u -> u.age > 18] selects adults"
      (is (= [{:name "Bob" :age 25}]
             (eval-dt-last "users is [{name: \"Alice\" age: 15} {name: \"Bob\" age: 25}]"
                           "result is users |> filter [u -> u.age > 18]"
                           "result"))))))

(deftest multiple-inline-functions-in-pipeline
  (testing "Scenario: Multiple inline functions in a pipeline"
    (testing "chained filter, map, sort-by with inline functions"
      (is (= ["Alice" "Bob"]
             (eval-dt-last
              "users is [{name: \"Bob\" active: true} {name: \"Alice\" active: true} {name: \"Eve\" active: false}]"
              "result is users |> filter [u -> u.active = true] |> map [u -> u.name] |> sort"
              "result"))))))

;; ---------------------------------------------------------------------------
;; Wildcard `_` as implicit anonymous function
;; ---------------------------------------------------------------------------

(deftest wildcard-field-access-desugars-to-anonymous-function
  (testing "Scenario: Wildcard field access desugars to anonymous function"
    (testing "users |> filter _.age > 18 desugars to [x -> x.age > 18]"
      (is (= [{:name "Bob" :age 25}]
             (eval-dt-last "users is [{name: \"Alice\" age: 15} {name: \"Bob\" age: 25}]"
                           "result is users |> filter _.age > 18"
                           "result"))))))

(deftest bare-wildcard-as-identity-reference
  (testing "Scenario: Bare wildcard as identity reference"
    (testing "items |> filter _ != nil removes nils"
      (is (= [1 2 3]
             (eval-dt-last "items is [1 nil 2 nil 3]"
                           "result is items |> filter _ != nil"
                           "result"))))))

(deftest wildcard-in-map-with-object-construction
  (testing "Scenario: Wildcard in map with object construction"
    (testing "users |> map {name: _.name age: _.age} projects fields"
      (is (= [{:name "Alice" :age 30} {:name "Bob" :age 25}]
             (eval-dt-last
              "users is [{name: \"Alice\" age: 30 role: \"admin\"} {name: \"Bob\" age: 25 role: \"user\"}]"
              "result is users |> map {name: _.name age: _.age}"
              "result"))))))

(deftest wildcard-vs-explicit-function-equivalent
  (testing "Scenario: Wildcard vs explicit function -- equivalent"
    (testing "_.age > 18 and [u -> u.age > 18] produce identical results"
      (let [a (eval-dt-last "users is [{name: \"Alice\" age: 15} {name: \"Bob\" age: 25}]"
                            "a is users |> filter _.age > 18"
                            "a")
            b (eval-dt-last "users is [{name: \"Alice\" age: 15} {name: \"Bob\" age: 25}]"
                            "b is users |> filter [u -> u.age > 18]"
                            "b")]
        (is (= a b))))))

(deftest nested-pipeline-wildcard-scoping
  (testing "Scenario: Nested pipeline wildcard scoping"
    (testing "outer _ refers to each user; inner [s -> ...] has its own scope"
      (is (= [{:name "Alice" :top-scores [95 90 85]}]
             (eval-dt-last
              "users is [{name: \"Alice\" scores: [95 60 90 85 70]}]"
              "result is users |> map {name: _.name top-scores: _.scores |> filter [s -> s > 80] |> take 3}"
              "result"))))))

;; ---------------------------------------------------------------------------
;; Variadic functions
;; ---------------------------------------------------------------------------

(deftest variadic-function-with-rest-parameter
  (testing "Scenario: Variadic function with rest parameter"
    (testing "[& nums -> nums |> reduce ...] collects all args into nums"
      (is (= 15
             (eval-dt-last "sum-all is [& nums -> nums |> reduce [a b -> a + b] 0]"
                           "sum-all 1 2 3 4 5"))))))

(deftest variadic-function-with-leading-fixed-parameters
  (testing "Scenario: Variadic function with leading fixed parameters"
    (testing "[level & messages -> ...] has one fixed and rest variadic"
      ;; We test that the function accepts variable args; side-effect (print) is secondary
      (is (not (silent-throws? (str "log-all is [level & messages -> messages |> each [m -> print (format \"[%s] %s\" level m)]]\n"
                                  "log-all \"INFO\" \"a\" \"b\" \"c\"")))))))

;; ---------------------------------------------------------------------------
;; Multi-arity functions (arity overloading)
;; ---------------------------------------------------------------------------

(deftest function-with-multiple-arities
  (testing "Scenario: Function with multiple arities"
    (testing "greet with 0, 1, and 2 arguments"
      (is (= "Hello, World!"
             (eval-dt-last "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]\n  [first last -> format \"Hello, %s %s!\" first last]"
                           "greet()")))
      (is (= "Hello, Alice!"
             (eval-dt-last "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]\n  [first last -> format \"Hello, %s %s!\" first last]"
                           "greet \"Alice\"")))
      (is (= "Hello, Alice Smith!"
             (eval-dt-last "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]\n  [first last -> format \"Hello, %s %s!\" first last]"
                           "greet \"Alice\" \"Smith\""))))))

(deftest multi-arity-with-default-like-behavior
  (testing "Scenario: Multi-arity with default-like behavior"
    (testing "lower arities delegate to the full arity"
      ;; range-of 5 => range-of 0 5 1 => [0 1 2 3 4]
      (is (= [0 1 2 3 4]
             (eval-dt-last "range-of is\n  [end -> range-of 0 end 1]\n  [start end -> range-of start end 1]\n  [start end step -> clj/range start end step]"
                           "range-of 5")))
      ;; range-of 2 5 => range-of 2 5 1 => [2 3 4]
      (is (= [2 3 4]
             (eval-dt-last "range-of is\n  [end -> range-of 0 end 1]\n  [start end -> range-of start end 1]\n  [start end step -> clj/range start end step]"
                           "range-of 2 5"))))))

;; ---------------------------------------------------------------------------
;; Recursion
;; ---------------------------------------------------------------------------

(deftest named-recursion-by-self-reference
  (testing "Scenario: Named recursion by self-reference"
    (testing "factorial calls itself by name"
      (is (= 120
             (eval-dt-last "factorial is [n ->\n  | n <= 1 -> 1\n  | _ -> n * factorial (n - 1)\n]"
                           "factorial 5"))))))

(deftest tail-recursion-with-recur
  (testing "Scenario: Tail recursion with recur"
    (testing "inner recur compiles to Clojure recur for TCO"
      (is (= 120
             (eval-dt-last
              "factorial is [n ->\n  go is [n acc ->\n    | n <= 1 -> acc\n    | _ -> recur (n - 1) (acc * n)\n  ]\n  go n 1\n]"
              "factorial 5"))))))

(deftest recur-in-simple-loop-like-pattern
  (testing "Scenario: Recur in a simple loop-like pattern"
    (testing "count-down with recur does not overflow the stack for large n"
      (is (= "done"
             (eval-dt-last "count-down is [n ->\n  | n <= 0 -> \"done\"\n  | _ -> recur (n - 1)\n]"
                           "count-down 1000000"))))))

;; ---------------------------------------------------------------------------
;; Function composition
;; ---------------------------------------------------------------------------

(deftest composition-operator-left-to-right
  (testing "Scenario: Composition operator >>"
    (testing "double >> inc applies double first, then inc"
      (is (= 7
             (eval-dt-last "double is [x -> x * 2]"
                           "inc is [x -> x + 1]"
                           "double-then-inc is double >> inc"
                           "double-then-inc 3"))))))

(deftest reverse-composition-operator
  (testing "Scenario: Reverse composition operator <<"
    (testing "double << inc applies inc first, then double"
      (is (= 8
             (eval-dt-last "double is [x -> x * 2]"
                           "inc is [x -> x + 1]"
                           "inc-then-double is double << inc"
                           "inc-then-double 3"))))))

(deftest chained-composition
  (testing "Scenario: Chained composition"
    (testing "process is parse >> validate >> transform >> serialize composes left-to-right"
      ;; Test with simple functions that we can chain
      (is (= 11
             (eval-dt-last "step1 is [x -> x + 1]"
                           "step2 is [x -> x * 2]"
                           "step3 is [x -> x + 1]"
                           "process is step1 >> step2 >> step3"
                           "process 4"))))))

;; ---------------------------------------------------------------------------
;; Partial application
;; ---------------------------------------------------------------------------

(deftest explicit-partial-application
  (testing "Scenario: Explicit partial application"
    (testing "partial add 5 creates a function that adds 5"
      (is (= 8
             (eval-dt-last "add is [a b -> a + b]"
                           "add5 is partial add 5"
                           "add5 3"))))))

(deftest partial-application-in-pipeline
  (testing "Scenario: Partial application in pipeline"
    (testing "[1 2 3] |> map (partial add 10) adds 10 to each element"
      (is (= [11 12 13]
             (eval-dt-last "add is [a b -> a + b]"
                           "result is [1 2 3] |> map (partial add 10)"
                           "result"))))))

;; ---------------------------------------------------------------------------
;; Functions as values in data structures
;; ---------------------------------------------------------------------------

(deftest function-as-object-field-value
  (testing "Scenario: Function as object field value"
    (testing "object fields can hold function values"
      (is (= 20
             (eval-dt-last "handler is {process: [x -> x * 2]}"
                           "handler.process 10"))))))

(deftest function-in-a-list
  (testing "Scenario: Function in a list"
    (testing "list of functions: [[x -> x * 2] [x -> x + 1] [x -> x * x]]"
      ;; Test that the first function in the list works correctly
      (is (= 10
             (eval-dt-last "transforms is [[x -> x * 2] [x -> x + 1] [x -> x * x]]"
                           "f is transforms |> first"
                           "f 5"))))))

(deftest retrieving-and-calling-function-from-object
  (testing "Scenario: Retrieving and calling a function from an object"
    (testing "handler.process 10 returns 20"
      (is (= 20
             (eval-dt-last "handler is {process: [x -> x * 2]}"
                           "result is handler.process 10"
                           "result"))))))

;; ---------------------------------------------------------------------------
;; Multi-expression function bodies
;; ---------------------------------------------------------------------------

(deftest function-body-is-single-expression
  (testing "Scenario: Function body is always a single expression"
    (testing "simple body: [x -> x * 2] returns result of expression"
      (is (= 10 (eval-dt-last "double is [x -> x * 2]" "double 5"))))))

(deftest local-bindings-inside-function-body
  (testing "Scenario: Local bindings inside function body using let-style is"
    (testing "hypotenuse with local bindings a2 and b2"
      (is (= 5.0
             (eval-dt-last "hypotenuse is [a b ->\n  a2 is a * a\n  b2 is b * b\n  sqrt (a2 + b2)\n]"
                           "hypotenuse 3 4"))))))

(deftest pipeline-inside-function-body
  (testing "Scenario: Pipeline inside function body"
    (testing "function body can contain a pipeline as its return expression"
      (is (= ["Alice" "Bob"]
             (eval-dt-last
              "users is [{name: \"Alice\" active: true} {name: \"Bob\" active: true} {name: \"Eve\" active: false}]"
              "process-users is [users ->\n  users\n  |> filter _.active\n  |> map _.name\n  |> sort\n]"
              "process-users users"))))))

(deftest multiple-bindings-then-final-expression
  (testing "Scenario: Multiple bindings then final expression"
    (testing "analyze function uses local bindings before returning an object"
      (let [result (eval-dt-last
                    "analyze is [data ->\n  cleaned is data |> filter _ != nil\n  count is cleaned |> length\n  total is cleaned |> reduce [a b -> a + b] 0\n  {count: count average: total / count}\n]"
                    "analyze [10 nil 20 nil 30]")]
        (is (= 3 (:count result)))
        (is (= 20.0 (:average result)))))))

;; ---------------------------------------------------------------------------
;; Pattern matching inside functions
;; ---------------------------------------------------------------------------

(deftest guard-style-pattern-match-as-function-body
  (testing "Scenario: Guard-style pattern match as function body"
    (testing "classify uses guard-style | conditions"
      (is (= "epic"
             (eval-dt-last "classify is [data ->\n  | data.type = \"book\" and data.pages > 500 -> \"epic\"\n  | data.type = \"book\" -> \"book\"\n  | data.type = \"movie\" and data.rating > 8 -> \"great film\"\n  | _ -> \"unknown\"\n]"
                           "classify {type: \"book\" pages: 600}")))
      (is (= "book"
             (eval-dt-last "classify is [data ->\n  | data.type = \"book\" and data.pages > 500 -> \"epic\"\n  | data.type = \"book\" -> \"book\"\n  | data.type = \"movie\" and data.rating > 8 -> \"great film\"\n  | _ -> \"unknown\"\n]"
                           "classify {type: \"book\" pages: 200}")))
      (is (= "great film"
             (eval-dt-last "classify is [data ->\n  | data.type = \"book\" and data.pages > 500 -> \"epic\"\n  | data.type = \"book\" -> \"book\"\n  | data.type = \"movie\" and data.rating > 8 -> \"great film\"\n  | _ -> \"unknown\"\n]"
                           "classify {type: \"movie\" rating: 9}")))
      (is (= "unknown"
             (eval-dt-last "classify is [data ->\n  | data.type = \"book\" and data.pages > 500 -> \"epic\"\n  | data.type = \"book\" -> \"book\"\n  | data.type = \"movie\" and data.rating > 8 -> \"great film\"\n  | _ -> \"unknown\"\n]"
                           "classify {type: \"song\"}"))))))

(deftest structural-pattern-matching-as-function-body
  (testing "Scenario: Structural pattern matching as function body"
    (testing "describe uses structural patterns with guards"
      (is (= "epic"
             (eval-dt-last "describe is [item ->\n  | {type: \"book\" pages: p} when p > 500 -> \"epic\"\n  | {type: \"book\"} -> \"book\"\n  | [x] -> \"single\"\n  | [x & rest] -> \"collection\"\n  | nil -> \"nothing\"\n  | _ -> \"unknown\"\n]"
                           "describe {type: \"book\" pages: 700}")))
      (is (= "single"
             (eval-dt-last "describe is [item ->\n  | {type: \"book\" pages: p} when p > 500 -> \"epic\"\n  | {type: \"book\"} -> \"book\"\n  | [x] -> \"single\"\n  | [x & rest] -> \"collection\"\n  | nil -> \"nothing\"\n  | _ -> \"unknown\"\n]"
                           "describe [42]")))
      (is (= "nothing"
             (eval-dt-last "describe is [item ->\n  | {type: \"book\" pages: p} when p > 500 -> \"epic\"\n  | {type: \"book\"} -> \"book\"\n  | [x] -> \"single\"\n  | [x & rest] -> \"collection\"\n  | nil -> \"nothing\"\n  | _ -> \"unknown\"\n]"
                           "describe nil"))))))

;; ---------------------------------------------------------------------------
;; Pipe-first interaction with functions
;; ---------------------------------------------------------------------------

(deftest pipe-first-passes-data-as-first-argument
  (testing "Scenario: Pipe-first passes data as first argument"
    (testing "5 |> add 3 is equivalent to add 5 3"
      (is (= 8
             (eval-dt-last "add is [a b -> a + b]"
                           "result is 5 |> add 3"
                           "result"))))))

(deftest pipeline-with-zero-arg-function-call
  (testing "Scenario: Pipeline with zero-arg function call"
    (testing "data |> count compiles to (count data)"
      (is (= 3
             (eval-dt-last "data is [1 2 3]"
                           "result is data |> count"
                           "result"))))))

(deftest pipeline-with-multi-arg-function
  (testing "Scenario: Pipeline with multi-arg function"
    (testing "data |> take 10 inserts data as first argument"
      (is (= [1 2]
             (eval-dt-last "data is [1 2 3 4 5]"
                           "result is data |> take 2"
                           "result"))))))

;; ---------------------------------------------------------------------------
;; Edge cases and interactions
;; ---------------------------------------------------------------------------

(deftest empty-list-literal-vs-zero-param-function
  (testing "Scenario: Empty list literal vs zero-param function"
    (testing "[] is an empty list, [-> 42] is a zero-param function"
      (is (= [] (eval-dt "[]")))
      (is (= 42 (eval-dt-last "f is [-> 42]" "f()"))))))

(deftest single-element-list-vs-function-ambiguity-resolved-by-arrow
  (testing "Scenario: Single-element list vs function ambiguity resolved by arrow"
    (testing "[x] is a list containing x, [x -> x] is an identity function"
      (is (= [5] (eval-dt-last "x is 5" "[x]")))
      (is (= 5   (eval-dt-last "id is [x -> x]" "id 5"))))))

(deftest nested-function-in-list-position
  (testing "Scenario: Nested function in list position"
    (testing "[[x -> x + 1] [x -> x * 2]] is a list of two functions"
      (let [fns-list (eval-dt "[[x -> x + 1] [x -> x * 2]]")]
        (is (= 2 (count fns-list)))
        (is (fn? (first fns-list)))
        (is (fn? (second fns-list)))))))

(deftest function-returning-an-object
  (testing "Scenario: Function returning an object"
    (testing "make-point returns {x: x y: y}"
      (is (= {:x 3 :y 4}
             (eval-dt-last "make-point is [x y -> {x: x y: y}]"
                           "make-point 3 4"))))))

(deftest function-returning-a-list
  (testing "Scenario: Function returning a list"
    (testing "[a b] in body is a list since there is no ->"
      (is (= [1 2]
             (eval-dt-last "pair is [a b -> [a b]]"
                           "pair 1 2"))))))

(deftest deeply-nested-closures-and-shadowing
  (testing "Scenario: Deeply nested closures and shadowing"
    (testing "inner x shadows outer x; (outer 10) 5 returns 16"
      (is (= 16
             (eval-dt-last "outer is [x ->\n  mid is [x -> [y -> x + y]]\n  mid (x + 1)\n]"
                           "f is outer 10"
                           "f 5"))))))

(deftest function-with-no-arguments-called-in-expression-position
  (testing "Scenario: Function with no arguments called in expression position"
    (testing "now (bare) is a reference to the function, not an invocation"
      ;; ts is bound to the function itself, which should be callable
      (is (fn? (eval-dt-last "now is [-> 42]" "ts is now" "ts"))))))

(deftest calling-zero-arg-function-explicitly-with-parens
  (testing "Scenario: Calling zero-arg function explicitly with parens"
    (testing "now() invokes the function and returns the result"
      (is (= 42 (eval-dt-last "now is [-> 42]" "ts is now()" "ts"))))))

;; ---------------------------------------------------------------------------
;; Disambiguation: list vs function
;; ---------------------------------------------------------------------------

(deftest parser-uses-arrow-to-distinguish-function-from-list
  (testing "Scenario: Parser uses -> to distinguish function from list"
    (testing "[] is a list, [-> 42] is a function, [1 2 3] is a list, [x -> x] is a function"
      (is (= []  (eval-dt "[]")))
      (is (fn?   (eval-dt "[-> 42]")))
      (is (= [1 2 3] (eval-dt "[1 2 3]")))
      (is (fn?   (eval-dt "[x -> x]"))))))
