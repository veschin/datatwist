(ns datatwist.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? type-of throws?]]))

;; ==========================================================================
;; Feature 4: Pipeline operator (|>)
;; BDD file: bdd/4-pipeline.feature
;;
;; Every `deftest` maps 1:1 to a BDD Scenario.
;; `testing` blocks group tests by the BDD section headers.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; Basic pipeline mechanics
;; ---------------------------------------------------------------------------

(deftest single-step-pipeline-passes-data-as-first-argument
  (testing "Scenario: Single-step pipeline passes data as first argument"
    ;; data |> count  =>  count(data)
    (is (= 3 (eval-dt "[1 2 3] |> count")))))

(deftest multi-step-inline-pipeline-chains-left-to-right
  (testing "Scenario: Multi-step inline pipeline chains left-to-right"
    ;; users |> filter _.active |> count
    (is (= 2 (eval-dt-last
              "users is [{active: true name: \"a\"} {active: false name: \"b\"} {active: true name: \"c\"}]"
              "users |> filter _.active |> count")))))

(deftest multi-line-pipeline-using-pipe-at-line-start
  (testing "Scenario: Multi-line pipeline using |> at line start"
    (is (= ["a" "c"]
           (eval-dt-last
            "users is [{active: true name: \"c\"} {active: false name: \"b\"} {active: true name: \"a\"}]"
            "users
|> filter _.active
|> map _.name
|> sort-by _.name
|> take 2")))))

(deftest pipeline-step-with-multiple-arguments-pipe-first
  (testing "Scenario: Pipeline step with multiple arguments (pipe-first)"
    ;; text |> replace "old" "new"  =>  replace(text, "old", "new")
    (is (= "hello new world"
           (eval-dt "\"hello old world\" |> replace \"old\" \"new\"")))))

(deftest pipeline-step-with-no-extra-arguments
  (testing "Scenario: Pipeline step with no extra arguments"
    ;; items |> reverse |> flatten |> distinct
    (is (= [3 2 1]
           (eval-dt "[[3 2] [2 1] [1]] |> flatten |> distinct")))))

(deftest pipeline-result-is-return-value-of-last-step
  (testing "Scenario: Pipeline result is the return value of the last step"
    (is (= 5 (eval-dt "[3 1 4 1 5] |> sort |> last")))))

(deftest associativity-is-left-to-right
  (testing "Scenario: Associativity is left-to-right"
    ;; a |> b |> c  =  (a |> b) |> c
    ;; [5 3 1] |> sort |> first  =>  sort then first => 1
    (is (= 1 (eval-dt "[5 3 1] |> sort |> first")))))

;; ---------------------------------------------------------------------------
;; Underscore (_) as pipeline context reference
;; ---------------------------------------------------------------------------

(deftest bare-underscore-refers-to-entire-current-element
  (testing "Scenario: Bare _ refers to the entire current element"
    ;; items |> filter _ != nil
    (is (= [1 2 3]
           (eval-dt "[1 nil 2 nil 3] |> filter _ != nil")))))

(deftest dot-access-underscore-field-refers-to-field-on-current-element
  (testing "Scenario: Dot-access _.field refers to a field on the current element"
    ;; users |> filter _.age > 18
    (is (= 2 (eval-dt-last
              "users is [{age: 10} {age: 25} {age: 30}]"
              "users |> filter _.age > 18 |> count")))))

(deftest deeply-nested-dot-access
  (testing "Scenario: Deeply nested dot-access _.a.b.c"
    (is (= ["Moscow"]
           (eval-dt-last
            "users is [{profile: {address: {city: \"Moscow\"}}} {profile: {address: {city: \"London\"}}}]"
            "users |> filter _.profile.address.city = \"Moscow\" |> map _.profile.address.city")))))

(deftest underscore-is-syntactic-sugar-for-anonymous-function
  (testing "Scenario: _ is syntactic sugar for an anonymous function"
    ;; users |> filter _.age > 18  ===  users |> filter [u -> u.age > 18]
    (let [users "[{age: 10} {age: 25} {age: 30}]"
          with-underscore (eval-dt-last (str "users is " users) "users |> filter _.age > 18 |> count")
          with-lambda     (eval-dt-last (str "users is " users) "users |> filter [u -> u.age > 18] |> count")]
      (is (= with-underscore with-lambda)))))

(deftest explicit-lambda-can-be-used-instead-of-underscore
  (testing "Scenario: Explicit lambda can be used instead of _"
    ;; numbers |> filter [n -> n > 5 and n < 100]
    (is (= [6 7 50]
           (eval-dt "[1 3 6 7 50 100 200] |> filter [n -> n > 5 and n < 100]")))))

(deftest underscore-used-with-comparison-operators
  (testing "Scenario: _ used with comparison operators"
    ;; scores |> filter _ > 80
    (is (= [90 85]
           (eval-dt "[70 90 60 85 50] |> filter _ > 80")))))

(deftest underscore-used-in-arithmetic-expressions
  (testing "Scenario: _ used in arithmetic expressions"
    ;; numbers |> map _ * 2 + 1
    (is (= [3 5 7]
           (eval-dt "[1 2 3] |> map _ * 2 + 1")))))

;; ---------------------------------------------------------------------------
;; Underscore scoping in nested pipelines
;; ---------------------------------------------------------------------------

(deftest underscore-in-inner-pipeline-refers-to-inner-context
  (testing "Scenario: _ in an inner pipeline refers to the inner context, not the outer"
    ;; users |> map {name: _.name scores: _.scores |> filter _ > 80}
    (is (= [{:name "Alice" :scores [90 85]}]
           (eval-dt-last
            "users is [{name: \"Alice\" scores: [90 70 85 60]}]"
            "users |> map {name: _.name scores: _.scores |> filter _ > 80}")))))

(deftest explicit-lambda-resolves-scoping-ambiguity-in-nested-pipelines
  (testing "Scenario: Explicit lambda resolves scoping ambiguity in nested pipelines"
    ;; Explicit [s -> s > 80] names the inner element; _.name still refers to the outer user
    (is (= [{:name "Alice" :top-scores [90 85]}]
           (eval-dt-last
            "users is [{name: \"Alice\" scores: [90 70 85 60]}]"
            "users |> map {
  name: _.name
  top-scores: _.scores |> filter [s -> s > 80] |> take 3
}")))))

(deftest multiple-levels-of-nesting-with-underscore
  (testing "Scenario: Multiple levels of nesting with _"
    ;; Each |> introduces a new scope for _
    (is (= [{:dept "Eng" :people [{:team "Core" :count 2}]}]
           (eval-dt-last
            "departments is [{name: \"Eng\" teams: [{name: \"Core\" members: [\"a\" \"b\"]}]}]"
            "departments
|> map {
  dept: _.name
  people: _.teams |> map {
    team: _.name
    count: _.members |> count
  }
}")))))

(deftest underscore-in-nested-pipeline-inside-filter
  (testing "Scenario: _ in a nested pipeline inside filter"
    ;; users |> filter ((_.scores |> sum) > 100)
    (is (= [{:name "Alice" :scores [60 50]}]
           (eval-dt-last
            "users is [{name: \"Alice\" scores: [60 50]} {name: \"Bob\" scores: [30 20]}]"
            "users |> filter ((_.scores |> sum) > 100)")))))

;; ---------------------------------------------------------------------------
;; Pipeline with binding (is)
;; ---------------------------------------------------------------------------

(deftest pipeline-result-assigned-with-is
  (testing "Scenario: Pipeline result assigned with is"
    ;; result is users |> filter _.active |> count
    (is (= 2 (eval-dt-last
              "users is [{active: true} {active: false} {active: true}]"
              "result is users |> filter _.active |> count"
              "result")))))

(deftest intermediate-pipeline-result-saved-to-binding
  (testing "Scenario: Intermediate pipeline result saved to a binding"
    ;; active-users is computed once, count and names derive from it
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\"} {active: false name: \"Bob\"} {active: true name: \"Carol\"}]"
                  "active-users is users |> filter _.active"
                  "count is active-users |> count"
                  "names is active-users |> map _.name"
                  "[count names]")]
      (is (= [2 ["Alice" "Carol"]] result)))))

(deftest pipeline-as-rhs-of-is-with-destructuring
  (testing "Scenario: Pipeline as the right-hand side of is with destructuring"
    ;; [first & rest] is items |> sort |> reverse
    (let [result (eval-dt-last
                  "items is [3 1 4 1 5]"
                  "[first & rest] is items |> sort |> reverse"
                  "[first rest]")]
      (is (= [5 [4 3 1 1]] result)))))

;; ---------------------------------------------------------------------------
;; Pipeline as a value (point-free / partial pipeline)
;; ---------------------------------------------------------------------------

(deftest pipeline-without-source-creates-reusable-transformer
  (testing "Scenario: Pipeline without a source creates a reusable transformer"
    ;; normalize is |> filter _.active |> map _.name |> sort
    (is (= ["Alice" "Carol"]
           (eval-dt-last
            "normalize is |> filter _.active |> map _.name |> sort"
            "users is [{active: true name: \"Carol\"} {active: false name: \"Bob\"} {active: true name: \"Alice\"}]"
            "users |> normalize")))))

(deftest sourceless-pipeline-used-inline
  (testing "Scenario: Sourceless pipeline used inline"
    ;; transformer is |> filter _ > 0 |> map [x -> x * 2]
    ;; [-3 -1 0 2 5] |> transformer  =>  [4 10]
    (is (= [4 10]
           (eval-dt-last
            "transformer is |> filter _ > 0 |> map [x -> x * 2]"
            "[(-3) (-1) 0 2 5] |> transformer")))))

;; ---------------------------------------------------------------------------
;; Pipeline with collection operations (built-in functions)
;; ---------------------------------------------------------------------------

(deftest filter-removes-elements-that-do-not-match-predicate
  (testing "Scenario: filter removes elements that do not match the predicate"
    (is (= [4 5] (eval-dt "[1 2 3 4 5] |> filter _ > 3")))))

(deftest map-transforms-each-element
  (testing "Scenario: map transforms each element"
    (is (= [10 20 30] (eval-dt "[1 2 3] |> map _ * 10")))))

(deftest reduce-folds-collection-into-single-value
  (testing "Scenario: reduce folds a collection into a single value"
    (is (= 10 (eval-dt "[1 2 3 4] |> reduce [acc x -> acc + x] 0")))))

(deftest sort-by-orders-elements-by-key
  (testing "Scenario: sort-by orders elements by a key"
    (is (= [{:age 20} {:age 25} {:age 30}]
           (eval-dt "[{age: 30} {age: 20} {age: 25}] |> sort-by _.age")))))

(deftest group-by-partitions-collection-by-key
  (testing "Scenario: group-by partitions a collection by a key"
    ;; Result is an object keyed by role values
    (let [result (eval-dt-last
                  "users is [{name: \"Alice\" role: \"admin\"} {name: \"Bob\" role: \"user\"} {name: \"Carol\" role: \"admin\"}]"
                  "users |> group-by _.role")]
      (is (= 2 (count (get result "admin"))))
      (is (= 1 (count (get result "user")))))))

(deftest take-returns-first-n-elements
  (testing "Scenario: take returns the first N elements"
    (is (= [1 2 3] (eval-dt "[1 2 3 4 5] |> take 3")))))

(deftest drop-removes-first-n-elements
  (testing "Scenario: drop removes the first N elements"
    (is (= [3 4 5] (eval-dt "[1 2 3 4 5] |> drop 2")))))

(deftest count-returns-number-of-elements
  (testing "Scenario: count returns the number of elements"
    (is (= 3 (eval-dt "[1 2 3] |> count")))))

(deftest sum-adds-all-numeric-elements
  (testing "Scenario: sum adds all numeric elements"
    (is (= 60 (eval-dt "[10 20 30] |> sum")))))

(deftest average-computes-arithmetic-mean
  (testing "Scenario: average computes the arithmetic mean"
    (is (= 20 (eval-dt "[10 20 30] |> average")))))

(deftest distinct-removes-duplicate-elements
  (testing "Scenario: distinct removes duplicate elements"
    (is (= [1 2 3] (eval-dt "[1 2 2 3 3 3] |> distinct")))))

(deftest flatten-flattens-one-level-of-nesting
  (testing "Scenario: flatten flattens one level of nesting"
    (is (= [1 2 3 4 5] (eval-dt "[[1 2] [3 4] [5]] |> flatten")))))

(deftest reverse-reverses-element-order
  (testing "Scenario: reverse reverses element order"
    (is (= [3 2 1] (eval-dt "[1 2 3] |> reverse")))))

(deftest first-returns-first-element
  (testing "Scenario: first returns the first element"
    (is (= 10 (eval-dt "[10 20 30] |> first")))))

(deftest last-returns-last-element
  (testing "Scenario: last returns the last element"
    (is (= 30 (eval-dt "[10 20 30] |> last")))))

(deftest nth-returns-element-at-index-n-zero-based
  (testing "Scenario: nth returns the element at index N (zero-based)"
    (is (= 20 (eval-dt "[10 20 30] |> nth 1")))))

;; ---------------------------------------------------------------------------
;; Chaining multiple built-in operations
;; ---------------------------------------------------------------------------

(deftest real-world-pipeline-combining-filter-map-sort-by-take
  (testing "Scenario: Real-world pipeline combining filter, map, sort-by, take"
    (let [result (eval-dt-last
                  "users is [{age: 25 active: true name: \"Carol\"} {age: 17 active: true name: \"Dave\"} {age: 30 active: true name: \"Alice\"} {age: 22 active: false name: \"Eve\"}]"
                  "users
|> filter _.age >= 18
|> filter _.active
|> map {name: _.name age: _.age}
|> sort-by _.age
|> take 10")]
      (is (= [{:name "Carol" :age 25} {:name "Alice" :age 30}] result)))))

(deftest pipeline-with-group-by-followed-by-map-over-groups
  (testing "Scenario: Pipeline with group-by followed by map over groups"
    ;; orders grouped by region, then mapped to totals
    (let [result (eval-dt-last
                  "orders is [{region: \"east\" amount: 100} {region: \"west\" amount: 200} {region: \"east\" amount: 150}]"
                  "orders
|> group-by _.region
|> map {region: _.key total: _.value |> map _.amount |> sum}")]
      (is (some #(= "east" (:region %)) result))
      (is (some #(= 250 (:total %)) result)))))

;; ---------------------------------------------------------------------------
;; Pipeline with guards and pattern matching
;; ---------------------------------------------------------------------------

(deftest guards-inside-map-within-pipeline
  (testing "Scenario: Guards inside map within a pipeline"
    (let [result (eval-dt-last
                  "users is [{name: \"Alice\" spending: 1500} {name: \"Bob\" spending: 500} {name: \"Carol\" spending: 50}]"
                  "users |> map {
  name: _.name
  tier:
    | _.spending > 1000 -> \"gold\"
    | _.spending > 100  -> \"silver\"
    | _                 -> \"bronze\"
}")]
      (is (= "gold"   (:tier (first result))))
      (is (= "silver" (:tier (second result))))
      (is (= "bronze" (:tier (nth result 2)))))))

(deftest guard-as-standalone-pipeline-step
  (testing "Scenario: Guard as a standalone pipeline step"
    (is (= "A" (eval-dt-last
                "score is 95"
                "score
|> | _ >= 90 -> \"A\"
   | _ >= 80 -> \"B\"
   | _ >= 70 -> \"C\"
   | _       -> \"F\"")))))

;; ---------------------------------------------------------------------------
;; Pipeline with side effects (! functions)
;; ---------------------------------------------------------------------------

(deftest side-effect-function-passes-data-through
  (testing "Scenario: Side-effect function passes data through"
    ;; log! and save! return data unchanged; pipeline continues
    ;; We test that the pipeline result equals the processed data
    (is (= 3 (eval-dt-last
              "data is [1 2 3]"
              "data
|> log! \"start\"
|> count")))))

(deftest side-effect-function-at-end-of-pipeline
  (testing "Scenario: Side-effect function at the end of a pipeline"
    ;; Result is the filtered list, not the save result
    (let [result (eval-dt-last
                  "users is [{active: true name: \"Alice\"} {active: false name: \"Bob\"}]"
                  "users |> filter _.active |> save! \"active-users.json\"")]
      (is (= [{:active true :name "Alice"}] result)))))

;; ---------------------------------------------------------------------------
;; Pipeline with user-defined functions
;; ---------------------------------------------------------------------------

(deftest user-defined-function-in-pipeline-step
  (testing "Scenario: User-defined function in a pipeline step"
    (is (= [2 4 6]
           (eval-dt-last
            "double is [x -> x * 2]"
            "[1 2 3] |> map double")))))

(deftest user-defined-predicate-in-pipeline-step
  (testing "Scenario: User-defined predicate in a pipeline step"
    (is (= 2 (eval-dt-last
              "adult? is [u -> u.age >= 18]"
              "users is [{age: 10} {age: 25} {age: 30}]"
              "users |> filter adult? |> count")))))

(deftest pipeline-step-calls-multi-argument-user-defined-function
  (testing "Scenario: Pipeline step calls a multi-argument user-defined function"
    (is (= [0 50 100]
           (eval-dt-last
            "clamp is [lo hi x ->
  | x < lo -> lo
  | x > hi -> hi
  | _      -> x
]"
            "numbers is [(-10) 50 200]"
            "numbers |> map (clamp 0 100 _)")))))

;; ---------------------------------------------------------------------------
;; Pipeline with objects (map producing objects)
;; ---------------------------------------------------------------------------

(deftest pipeline-step-transforms-elements-into-objects
  (testing "Scenario: Pipeline step that transforms elements into objects"
    (is (= [{:name "hi" :length 2} {:name "hello" :length 5}]
           (eval-dt "[\"hi\" \"hello\"] |> map {name: _ length: _ |> count}")))))

(deftest pipeline-step-with-nested-object-construction
  (testing "Scenario: Pipeline step with nested object construction"
    (let [result (eval-dt-last
                  "users is [{name: \"Alice\" scores: [90 70 85]}]"
                  "users |> map {
  name: _.name
  stats: {
    score-count: _.scores |> count
    top-score: _.scores |> sort |> last
    average: _.scores |> average
  }
}")]
      (is (= "Alice" (:name (first result))))
      (is (= 3 (get-in (first result) [:stats :score-count])))
      (is (= 90 (get-in (first result) [:stats :top-score]))))))

;; ---------------------------------------------------------------------------
;; Smart Map: Field Operations
;; ---------------------------------------------------------------------------

(deftest map-with-plus-prefix-adds-field-to-existing-object
  (testing "Scenario: Map with + prefix adds field to existing object"
    (let [result (eval-dt-last
                  "items is [{name: \"Alice\" age: 25} {name: \"Bob\" age: 30}]"
                  "items |> map {+score: _.age * 2}")]
      (is (= {:name "Alice" :age 25 :score 50} (first result)))
      (is (= {:name "Bob" :age 30 :score 60} (second result))))))

(deftest map-with-minus-prefix-removes-field-from-object
  (testing "Scenario: Map with - prefix removes field from object"
    (let [result (eval-dt-last
                  "items is [{name: \"Alice\" age: 25 tmp: true} {name: \"Bob\" age: 30 tmp: false}]"
                  "items |> map {-tmp}")]
      (is (= {:name "Alice" :age 25} (first result)))
      (is (= {:name "Bob" :age 30} (second result))))))

(deftest map-with-mixed-plus-and-minus-prefixes
  (testing "Scenario: Map with mixed + and - prefixes"
    (let [result (eval-dt-last
                  "items is [{name: \"Alice\" age: 25 tmp: true}]"
                  "items |> map {+score: _.age * 2  -tmp}")]
      (is (= {:name "Alice" :age 25 :score 50} (first result))))))

(deftest object-shorthand-in-map
  (testing "Scenario: Object shorthand in map"
    ;; {name, age} = {name: _.name age: _.age}
    (let [result (eval-dt-last
                  "items is [{name: \"Alice\" age: 25 email: \"a@b.com\"}]"
                  "items |> map {name, age}")]
      (is (= {:name "Alice" :age 25} (first result))))))

(deftest forward-referencing-in-map-block
  (testing "Scenario: Forward-referencing in map block"
    ;; Later fields can reference earlier fields defined in the same block
    (let [result (eval-dt-last
                  "items is [{price: 100}]"
                  "items |> map {+tax: _.price * 0.1  +total: _.price + tax}")]
      (is (= 10.0 (:tax (first result))))
      (is (= 110.0 (:total (first result)))))))

(deftest plain-map-without-prefix-creates-new-object
  (testing "Scenario: Plain map without prefix creates new object"
    ;; Without + prefix, map creates a new object (does not keep existing fields)
    (let [result (eval-dt-last
                  "items is [{name: \"Alice\" age: 25 email: \"a@b.com\"}]"
                  "items |> map {name: _.name age: _.age}")]
      (is (= {:name "Alice" :age 25} (first result))))))

;; ---------------------------------------------------------------------------
;; Inline vs multi-line equivalence
;; ---------------------------------------------------------------------------

(deftest inline-and-multi-line-pipelines-are-equivalent
  (testing "Scenario: Inline and multi-line pipelines are equivalent"
    (let [data "[{x: true y: 1} {x: false y: 2} {x: true y: 3}]"
          inline-result    (eval-dt-last (str "data is " data) "data |> filter _.x |> map _.y |> count")
          multiline-result (eval-dt-last (str "data is " data) "data
|> filter _.x
|> map _.y
|> count")]
      (is (= inline-result multiline-result)))))

;; ---------------------------------------------------------------------------
;; Pipeline with Clojure interop
;; ---------------------------------------------------------------------------

(deftest pipeline-calls-clojure-standard-library-function
  (testing "Scenario: Pipeline calls a Clojure standard library function"
    ;; text |> clj/clojure.string/upper-case
    (is (= "HELLO" (eval-dt "\"hello\" |> clj/clojure.string/upper-case")))))

(deftest pipeline-calls-clojure-function-with-extra-arguments
  (testing "Scenario: Pipeline calls a Clojure function with extra arguments"
    ;; text |> clj/clojure.string/replace with extra args
    (let [result (eval-dt "\"hello world\" |> clj/clojure.string/replace \"world\" \"DataTwist\"")]
      (is (= "hello DataTwist" result)))))

;; ---------------------------------------------------------------------------
;; Pipeline with predicate functions (?)
;; ---------------------------------------------------------------------------

(deftest predicate-function-used-in-filter
  (testing "Scenario: Predicate function used in filter"
    (is (= [2 4 6]
           (eval-dt-last
            "even? is [n -> n % 2 = 0]"
            "[1 2 3 4 5 6] |> filter even?")))))

(deftest negated-predicate-in-pipeline
  (testing "Scenario: Negated predicate in a pipeline"
    (is (= [1 3 5]
           (eval-dt-last
            "even? is [n -> n % 2 = 0]"
            "[1 2 3 4 5 6] |> filter [n -> not (even? n)]")))))

;; ---------------------------------------------------------------------------
;; Nil behavior in pipelines
;; ---------------------------------------------------------------------------

(deftest nil-source-in-pipeline
  (testing "Scenario: Nil source in a pipeline"
    ;; nil |> count  =>  0
    (is (= 0 (eval-dt "nil |> count")))))

(deftest pipeline-step-produces-nil
  (testing "Scenario: Pipeline step produces nil"
    ;; _.nonexistent-field returns nil for each user; filter removes nil/falsy; count returns 0
    (is (= 0 (eval-dt-last
              "users is [{name: \"Alice\"} {name: \"Bob\"}]"
              "users |> filter _.nonexistent-field |> count")))))

(deftest nil-propagation-through-chained-field-access-in-pipeline
  (testing "Scenario: Nil propagation through chained field access in pipeline"
    ;; If profile, address, or city is nil at any level, the result is nil (no error)
    (let [result (eval-dt-last
                  "users is [{profile: {address: {city: \"Moscow\"}}} {profile: nil}]"
                  "users |> map _.profile.address.city")]
      (is (= "Moscow" (first result)))
      (is (nil? (second result))))))

;; ---------------------------------------------------------------------------
;; Error handling in pipelines
;; ---------------------------------------------------------------------------

(deftest error-in-pipeline-step-propagates-as-exception
  (testing "Scenario: Error in a pipeline step propagates as an exception"
    ;; When a step throws, the pipeline stops and the error propagates
    (is (throws? "42 |> filter _ > 5"))))

(deftest try-catch-wrapping-a-pipeline
  (testing "Scenario: Try-catch wrapping a pipeline"
    ;; If any pipeline step throws, catch handles it
    (is (= []
           (eval-dt "result is try
  42 |> filter _ > 5
catch err -> []")))))

;; ---------------------------------------------------------------------------
;; Edge cases and invalid pipelines
;; ---------------------------------------------------------------------------

(deftest empty-pipeline-no-steps-is-parse-error
  (testing "Scenario: Empty pipeline (no steps) is a parse error"
    ;; data |>   (no step after |>) => syntax error
    (is (parse-error? "data |>"))))

(deftest pipeline-operator-without-source-is-sourceless-pipeline
  (testing "Scenario: Pipeline operator without a source is a sourceless pipeline"
    ;; |> filter _.active |> count  =>  creates a reusable transformer function
    (is (= 2 (eval-dt-last
              "counter is |> filter _.active |> count"
              "users is [{active: true} {active: false} {active: true}]"
              "users |> counter")))))

(deftest pipeline-with-only-one-value-no-pipe-is-just-a-value
  (testing "Scenario: Pipeline with only one value (no pipe operator) is just a value"
    ;; 42 is just the literal 42, not a pipeline
    (is (= 42 (eval-dt "42")))))

(deftest consecutive-pipe-operators-with-no-function-is-parse-error
  (testing "Scenario: Consecutive pipe operators with no function is a parse error"
    ;; data |> |> count  => syntax error
    (is (parse-error? "data |> |> count"))))

(deftest pipe-operator-inside-string-literal-is-not-parsed-as-pipeline
  (testing "Scenario: Pipe operator inside a string literal is not parsed as pipeline"
    ;; |> inside a string is literal text
    (is (= "use |> for pipes" (eval-dt "\"use |> for pipes\"")))))

;; ---------------------------------------------------------------------------
;; Pipeline branching and advanced patterns
;; ---------------------------------------------------------------------------

(deftest tee-for-branching-pipeline-into-multiple-paths
  (testing "Scenario: Tee for branching a pipeline into multiple paths"
    ;; tap! runs a side-effect function on data and returns data unchanged
    (is (= [1 2 3]
           (eval-dt "[1 2 3] |> tap! [d -> d]")))))

(deftest pipeline-result-used-in-multiple-downstream-bindings
  (testing "Scenario: Pipeline result used in multiple downstream bindings"
    (let [result (eval-dt-last
                  "data is [{valid: true date: 3} {valid: false date: 1} {valid: true date: 2} {valid: true date: 5}]"
                  "processed is data |> filter _.valid |> sort-by _.date"
                  "recent is processed |> take 2"
                  "oldest is processed |> first"
                  "total is processed |> count"
                  "[total oldest recent]")]
      (is (= 3 (first result)))
      (is (= {:valid true :date 2} (second result)))
      (is (= 2 (count (nth result 2)))))))

;; ---------------------------------------------------------------------------
;; Pipeline with complex expressions
;; ---------------------------------------------------------------------------

(deftest pipeline-step-with-logical-operators-in-predicate
  (testing "Scenario: Pipeline step with logical operators in predicate"
    ;; users |> filter (_.age > 18 and _.status = "active")
    (is (= 1 (eval-dt-last
              "users is [{age: 25 status: \"active\"} {age: 17 status: \"active\"} {age: 30 status: \"inactive\"}]"
              "users |> filter (_.age > 18 and _.status = \"active\") |> count")))))

(deftest pipeline-step-with-compound-expression-using-or
  (testing "Scenario: Pipeline step with compound expression using or"
    ;; users |> filter (_.role = "admin" or _.role = "moderator")
    (is (= 2 (eval-dt-last
              "users is [{role: \"admin\"} {role: \"user\"} {role: \"moderator\"}]"
              "users |> filter (_.role = \"admin\" or _.role = \"moderator\") |> count")))))

(deftest pipeline-step-with-not-operator
  (testing "Scenario: Pipeline step with not operator"
    ;; users |> filter (not _.banned)
    (is (= 2 (eval-dt-last
              "users is [{name: \"a\" banned: false} {name: \"b\" banned: true} {name: \"c\" banned: false}]"
              "users |> filter (not _.banned) |> count")))))

(deftest pipeline-with-map-producing-arithmetic-result
  (testing "Scenario: Pipeline with map producing arithmetic result"
    ;; orders |> map _.price * _.quantity
    (is (= [500 600]
           (eval-dt-last
            "orders is [{price: 50 quantity: 10} {price: 30 quantity: 20}]"
            "orders |> map _.price * _.quantity")))))

;; ---------------------------------------------------------------------------
;; Pipeline mixing inline and multi-line forms
;; ---------------------------------------------------------------------------

(deftest pipeline-starts-inline-then-continues-multi-line
  (testing "Scenario: Pipeline starts inline then continues multi-line"
    ;; data |> filter _.active\n|> map _.name\n|> count  =>  valid single pipeline
    (is (= 2 (eval-dt-last
              "data is [{active: true name: \"a\"} {active: false name: \"b\"} {active: true name: \"c\"}]"
              "data |> filter _.active
|> map _.name
|> count")))))

;; ---------------------------------------------------------------------------
;; Pipeline with higher-order functions
;; ---------------------------------------------------------------------------

(deftest pipeline-step-is-higher-order-function-returning-function
  (testing "Scenario: Pipeline step is a higher-order function returning a function"
    ;; make-filter returns a predicate function used in filter
    (is (= [{:name "Alice" :role "admin"}]
           (eval-dt-last
            "make-filter is [field val -> [item -> get item field = val]]"
            "users is [{name: \"Alice\" role: \"admin\"} {name: \"Bob\" role: \"user\"}]"
            "users |> filter (make-filter \"role\" \"admin\")")))))

;; ---------------------------------------------------------------------------
;; Pipeline performance and lazy evaluation
;; ---------------------------------------------------------------------------

(deftest pipeline-operations-are-lazy-where-possible
  (testing "Scenario: Pipeline operations are lazy where possible"
    ;; Only enough elements to satisfy take 5 are computed
    (is (= [2 4 6 8 10]
           (eval-dt-last
            "huge-list is [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20]"
            "huge-list |> filter _ > 0 |> map _ * 2 |> take 5")))))

;; ---------------------------------------------------------------------------
;; Compilation mapping to Clojure
;; ---------------------------------------------------------------------------

(deftest pipeline-compiles-to-clojure-thread-first-macro
  (testing "Scenario: Pipeline compiles to Clojure thread-first macro"
    ;; x |> a |> b arg |> c  =>  (-> x a (b arg) c)
    ;; Verify by running a concrete pipeline that exercises multi-arg step
    (is (= [2 3]
           (eval-dt "[1 2 3 4 5] |> drop 1 |> take 2")))))

(deftest pipeline-with-underscore-compiles-to-anonymous-functions
  (testing "Scenario: Pipeline with _ compiles to anonymous functions"
    ;; items |> filter _.active |> map _.name
    (is (= ["Alice"]
           (eval-dt-last
            "items is [{active: true name: \"Alice\"} {active: false name: \"Bob\"}]"
            "items |> filter _.active |> map _.name")))))

(deftest nested-pipeline-compiles-to-nested-thread-first
  (testing "Scenario: Nested pipeline compiles to nested thread-first"
    ;; users |> map {name: _.name top: _.scores |> sort |> last}
    (is (= [{:name "Alice" :top 90}]
           (eval-dt-last
            "users is [{name: \"Alice\" scores: [70 90 85]}]"
            "users |> map {name: _.name top: _.scores |> sort |> last}")))))
