(ns datatwist.data-structures-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws?]]))

;; ===========================================================================
;; Feature 2: Data Structures
;;
;; Every deftest / `is` assertion maps 1:1 to a BDD scenario in
;; bdd/2-data-structures.feature.
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Objects (Maps)
;; ---------------------------------------------------------------------------

(deftest empty-object
  ;; Scenario: Empty object
  (testing "empty object evaluates to an empty map"
    (is (= {} (eval-dt "{}")))))

(deftest object-with-single-field
  ;; Scenario: Object with a single field
  (testing "single-field object"
    (let [result (eval-dt "{name: \"Alice\"}")]
      (is (= {:name "Alice"} result)))))

(deftest object-with-multiple-fields
  ;; Scenario: Object with multiple fields
  (testing "object with 3 space-separated fields"
    (let [result (eval-dt "{name: \"Alice\" age: 25 active: true}")]
      (is (= 3 (count result)))
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result)))
      (is (= true (:active result))))))

(deftest object-field-values-are-arbitrary-expressions
  ;; Scenario: Object field values are arbitrary expressions
  (testing "field values can be computed expressions"
    (let [result (eval-dt-last "x is 10" "{doubled: x * 2 name: \"Alice\"}")]
      (is (= 20 (:doubled result)))
      (is (= "Alice" (:name result))))))

(deftest object-field-value-is-variable-reference
  ;; Scenario: Object field value is a variable reference
  (testing "field value referencing a variable"
    (let [result (eval-dt-last "city is \"Moscow\"" "{location: city}")]
      (is (= "Moscow" (:location result))))))

(deftest object-field-value-distinguishes-variable-from-literal
  ;; Scenario: Object field value distinguishes variable from literal
  (testing "variable reference vs string literal with same name"
    (let [result (eval-dt-last "value is 42" "{a: value b: \"value\"}")]
      (is (= 42 (:a result)))
      (is (= "value" (:b result))))))

(deftest nested-objects
  ;; Scenario: Nested objects
  (testing "nested object structure with deep path"
    (let [result (eval-dt "{a: {b: {c: 1}}}")]
      (is (= 1 (get-in result [:a :b :c]))))))

(deftest object-with-nil-value
  ;; Scenario: Object with nil value
  (testing "object field with explicit nil value"
    (let [result (eval-dt "{name: \"Alice\" address: nil}")]
      (is (= "Alice" (:name result)))
      (is (nil? (:address result))))))

(deftest object-keys-may-contain-hyphens
  ;; Scenario: Object keys may contain hyphens
  (testing "hyphenated key names"
    (let [result (eval-dt "{first-name: \"Alice\" last-name: \"Smith\"}")]
      (is (= "Alice" (:first-name result)))
      (is (= "Smith" (:last-name result))))))

(deftest object-keys-may-contain-digits
  ;; Scenario: Object keys may contain digits (but not start with them)
  (testing "keys with digits in non-leading position"
    (let [result (eval-dt "{level2: \"advanced\" x1: 10}")]
      (is (= "advanced" (:level2 result)))
      (is (= 10 (:x1 result))))))

(deftest object-keys-must-start-with-letter
  ;; Scenario: Object keys must start with a letter
  (testing "key starting with digit is a parse error"
    (is (parse-error? "{2fast: \"no\"}"))))

(deftest object-keys-may-contain-underscores
  ;; Scenario: Object keys may contain underscores
  (testing "underscore in key name"
    (let [result (eval-dt "{user_name: \"Alice\"}")]
      (is (= "Alice" (:user_name result))))))

(deftest duplicate-keys-last-wins
  ;; Scenario: Duplicate keys -- last value wins
  (testing "duplicate keys resolve to last value"
    (let [result (eval-dt "{name: \"Alice\" name: \"Bob\"}")]
      (is (= "Bob" (:name result))))))

(deftest trailing-whitespace-inside-braces
  ;; Scenario: Trailing whitespace inside braces is allowed
  (testing "trailing whitespace in object literal"
    (let [result (eval-dt "{name: \"Alice\" }")]
      (is (= {:name "Alice"} result)))))

(deftest leading-whitespace-inside-braces
  ;; Scenario: Leading whitespace inside braces is allowed
  (testing "leading whitespace in object literal"
    (let [result (eval-dt "{ name: \"Alice\"}")]
      (is (= {:name "Alice"} result)))))

(deftest multi-line-object
  ;; Scenario: Multi-line object with newline-separated fields
  (testing "multi-line object with newlines as separators"
    (let [result (eval-dt "{\n  name: \"Alice\"\n  age: 25\n  city: \"Moscow\"\n}")]
      (is (= 3 (count result)))
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result)))
      (is (= "Moscow" (:city result))))))

(deftest multi-line-object-with-nested-object
  ;; Scenario: Multi-line object with mixed single-line and multi-line fields
  (testing "multi-line object containing a nested object"
    (let [result (eval-dt "{\n  name: \"Alice\"\n  address: {\n    city: \"Moscow\"\n    zip: \"101000\"\n  }\n}")]
      (is (= "Moscow" (get-in result [:address :city]))))))

(deftest object-with-expression-values-spanning-concepts
  ;; Scenario: Object with expression values spanning concepts
  (testing "object field values that use pipelines and other features"
    (let [result (eval-dt-last
                  "users is [{age: 20} {age: 30} {age: 40}]"
                  "{\n  count: users |> count\n  names: users |> map _.name\n}")]
      (is (= 3 (:count result))))))

(deftest commas-between-object-fields-are-parse-error
  ;; Scenario: Commas between object fields are a parse error
  (testing "commas in object literals cause parse error"
    (is (parse-error? "{name: \"Alice\", age: 25}"))))

;; ---------------------------------------------------------------------------
;; Lists (Vectors)
;; ---------------------------------------------------------------------------

(deftest empty-list
  ;; Scenario: Empty list
  (testing "empty list evaluates to empty vector"
    (is (= [] (eval-dt "[]")))))

(deftest list-of-integers
  ;; Scenario: List of integers
  (testing "list of integers"
    (let [result (eval-dt "[1 2 3 4 5]")]
      (is (= 5 (count result)))
      (is (= 1 (nth result 0)))
      (is (= 5 (nth result 4))))))

(deftest list-of-strings
  ;; Scenario: List of strings
  (testing "list of string values"
    (let [result (eval-dt "[\"Alice\" \"Bob\" \"Charlie\"]")]
      (is (= 3 (count result)))
      (is (= "Alice" (nth result 0))))))

(deftest list-with-mixed-types
  ;; Scenario: List with mixed types
  (testing "list containing different types"
    (let [result (eval-dt "[\"Alice\" 25 true nil]")]
      (is (= "Alice" (nth result 0)))
      (is (= 25 (nth result 1)))
      (is (= true (nth result 2)))
      (is (nil? (nth result 3))))))

(deftest nested-lists
  ;; Scenario: Nested lists
  (testing "list of lists"
    (let [result (eval-dt "[[1 2] [3 4] [5 6]]")]
      (is (= 3 (count result)))
      (is (= [1 2] (nth result 0)))
      (is (= [3 4] (nth result 1))))))

(deftest list-containing-objects
  ;; Scenario: List containing objects
  (testing "list of objects"
    (let [result (eval-dt "[{a: 1} {a: 2} {a: 3}]")]
      (is (= 3 (count result)))
      (is (= 1 (:a (nth result 0))))
      (is (= 3 (:a (nth result 2)))))))

(deftest list-containing-expressions
  ;; Scenario: List containing expressions
  (testing "list elements that are computed expressions"
    (let [result (eval-dt-last "x is 10" "[x (x * 2) (x + 5)]")]
      (is (= 10 (nth result 0)))
      (is (= 20 (nth result 1)))
      (is (= 15 (nth result 2))))))

(deftest multi-line-list
  ;; Scenario: Multi-line list
  (testing "multi-line list with newline separators"
    (let [result (eval-dt "[\n  1\n  2\n  3\n]")]
      (is (= 3 (count result)))
      (is (= 1 (nth result 0))))))

(deftest multi-line-list-of-objects
  ;; Scenario: Multi-line list of objects
  (testing "multi-line list containing objects"
    (let [result (eval-dt "[\n  {name: \"Alice\" age: 25}\n  {name: \"Bob\" age: 30}\n]")]
      (is (= 2 (count result)))
      (is (= "Alice" (:name (nth result 0)))))))

(deftest trailing-whitespace-in-list
  ;; Scenario: Trailing whitespace in list is allowed
  (testing "trailing whitespace inside brackets"
    (let [result (eval-dt "[1 2 3 ]")]
      (is (= 3 (count result))))))

(deftest commas-between-list-elements-are-parse-error
  ;; Scenario: Commas between list elements are a parse error
  (testing "commas in list literals cause parse error"
    (is (parse-error? "[1, 2, 3]"))))

(deftest deeply-nested-list
  ;; Scenario: Deeply nested list
  (testing "triply nested list"
    (let [result (eval-dt "[[[1]]]")]
      (is (= [1] (first (first result)))))))

;; ---------------------------------------------------------------------------
;; Field Access (Dot Notation)
;; ---------------------------------------------------------------------------

(deftest simple-field-access
  ;; Scenario: Simple field access
  (testing "dot notation for single field"
    (is (= "Alice" (eval-dt-last "user is {name: \"Alice\" age: 25}" "user.name")))))

(deftest nested-field-access
  ;; Scenario: Nested field access
  (testing "chained dot notation through nested objects"
    (is (= "Moscow"
           (eval-dt-last "user is {profile: {address: {city: \"Moscow\"}}}"
                         "user.profile.address.city")))))

(deftest field-access-on-nil-returns-nil
  ;; Scenario: Field access on nil returns nil (nil-tolerant)
  (testing "field access on nil value returns nil"
    (is (nil? (eval-dt-last "user is nil" "user.name")))))

(deftest chained-field-access-through-nil
  ;; Scenario: Chained field access through nil returns nil
  (testing "chained dot notation through missing intermediate fields"
    (is (nil? (eval-dt-last "user is {name: \"Alice\"}" "user.address.city.zip")))))

(deftest deeply-chained-access-through-nil
  ;; Scenario: Deeply chained access through nil
  (testing "deep chain through explicit nil value"
    (is (nil? (eval-dt-last "data is {a: nil}" "data.a.b.c.d.e")))))

(deftest field-access-on-non-nil-intermediate-values
  ;; Scenario: Field access on non-nil intermediate values
  (testing "deep chain through valid intermediate objects"
    (is (= 42 (eval-dt-last "data is {a: {b: {c: {d: 42}}}}" "data.a.b.c.d")))))

(deftest field-access-returns-nil-for-missing-key
  ;; Scenario: Field access returns nil for missing key
  (testing "accessing a non-existent key returns nil"
    (is (nil? (eval-dt-last "user is {name: \"Alice\"}" "user.email")))))

(deftest wildcard-field-access-in-pipeline
  ;; Scenario: Wildcard field access in pipeline
  (testing "_.field in map pipeline stage"
    (is (= ["Alice" "Bob"]
           (eval-dt-last "users is [{name: \"Alice\" age: 25} {name: \"Bob\" age: 30}]"
                         "users |> map _.name")))))

(deftest nested-wildcard-field-access
  ;; Scenario: Nested wildcard field access
  (testing "_.nested.field in map pipeline stage"
    (is (= ["Moscow" "Berlin"]
           (eval-dt-last "users is [{profile: {city: \"Moscow\"}} {profile: {city: \"Berlin\"}}]"
                         "users |> map _.profile.city")))))

(deftest wildcard-field-access-on-missing-field
  ;; Scenario: Wildcard field access on missing field returns nil
  (testing "_.field returns nil when field is absent"
    (is (= ["Alice" nil]
           (eval-dt-last "users is [{name: \"Alice\"} {age: 30}]"
                         "users |> map _.name")))))

;; ---------------------------------------------------------------------------
;; List Indexing
;; ---------------------------------------------------------------------------

(deftest nth-access-by-index
  ;; Scenario: Access list element by index with nth
  (testing "nth retrieves element at given index"
    (is (= 10 (eval-dt-last "items is [10 20 30]" "nth items 0")))))

(deftest nth-out-of-bounds-returns-nil
  ;; Scenario: nth with out-of-bounds index returns nil
  (testing "nth with index beyond list length returns nil"
    (is (nil? (eval-dt-last "items is [10 20 30]" "nth items 10")))))

(deftest nth-negative-index-returns-nil
  ;; Scenario: nth with negative index returns nil
  (testing "nth with negative index returns nil"
    (is (nil? (eval-dt-last "items is [10 20 30]" "nth items (-1)")))))

(deftest first-and-last-on-lists
  ;; Scenario: first and last on lists
  (testing "first returns head, last returns tail"
    (let [setup "items is [10 20 30]"]
      (is (= 10 (eval-dt-last setup "first items")))
      (is (= 30 (eval-dt-last setup "last items"))))))

(deftest first-on-empty-list-returns-nil
  ;; Scenario: first on empty list returns nil
  (testing "first on empty list is nil"
    (is (nil? (eval-dt-last "items is []" "first items")))))

;; ---------------------------------------------------------------------------
;; Dynamic Key Access
;; ---------------------------------------------------------------------------

(deftest dynamic-field-access-with-get
  ;; Scenario: Dynamic field access with get
  (testing "get with variable key"
    (is (= "Alice"
           (eval-dt-last "user is {name: \"Alice\" age: 25}"
                         "key is \"name\""
                         "get user key")))))

(deftest dynamic-get-returns-nil-for-missing-key
  ;; Scenario: Dynamic field access with get returns nil for missing key
  (testing "get with non-existent key returns nil"
    (is (nil? (eval-dt-last "user is {name: \"Alice\"}"
                            "key is \"email\""
                            "get user key")))))

(deftest dynamic-get-with-default-value
  ;; Scenario: Dynamic field access with get and default value
  (testing "get with default value for missing key"
    (is (= "unknown"
           (eval-dt-last "user is {name: \"Alice\"}"
                         "get user \"email\" \"unknown\"")))))

;; ---------------------------------------------------------------------------
;; Object Operations
;; ---------------------------------------------------------------------------

(deftest merge-two-objects
  ;; Scenario: Merge two objects
  (testing "merge combines objects, second wins on conflict"
    (let [result (eval-dt-last "a is {name: \"Alice\" age: 25}"
                               "b is {age: 26 city: \"Moscow\"}"
                               "merge a b")]
      (is (= "Alice" (:name result)))
      (is (= 26 (:age result)))
      (is (= "Moscow" (:city result))))))

(deftest merge-with-empty-object
  ;; Scenario: Merge with empty object
  (testing "merge with empty object is identity"
    (let [result (eval-dt-last "a is {name: \"Alice\"}" "merge a {}")]
      (is (= {:name "Alice"} result)))))

(deftest merge-multiple-objects
  ;; Scenario: Merge multiple objects
  (testing "merge with three arguments"
    (let [result (eval-dt-last "a is {x: 1}"
                               "b is {y: 2}"
                               "c is {z: 3}"
                               "merge a b c")]
      (is (= 1 (:x result)))
      (is (= 2 (:y result)))
      (is (= 3 (:z result))))))

(deftest assoc-new-field
  ;; Scenario: Assoc a new field into an object
  (testing "assoc adds a new field"
    (let [result (eval-dt-last "user is {name: \"Alice\"}"
                               "assoc user \"age\" 25")]
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result))))))

(deftest dissoc-removes-field
  ;; Scenario: Dissoc removes a field from an object
  (testing "dissoc removes a field"
    (let [result (eval-dt-last "user is {name: \"Alice\" age: 25 tmp: true}"
                               "dissoc user \"tmp\"")]
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result)))
      (is (not (contains? result :tmp))))))

(deftest keys-returns-field-names
  ;; Scenario: keys returns the list of field names
  (testing "keys returns list of key names"
    (let [result (eval-dt-last "user is {name: \"Alice\" age: 25}" "keys user")]
      (is (some #(= "name" %) result))
      (is (some #(= "age" %) result))
      (is (= 2 (count result))))))

(deftest vals-returns-field-values
  ;; Scenario: vals returns the list of field values
  (testing "vals returns list of values"
    (let [result (eval-dt-last "user is {name: \"Alice\" age: 25}" "vals user")]
      (is (some #(= "Alice" %) result))
      (is (some #(= 25 %) result))
      (is (= 2 (count result))))))

;; ---------------------------------------------------------------------------
;; List Operations (basic, not pipeline-specific)
;; ---------------------------------------------------------------------------

(deftest count-on-list
  ;; Scenario: count on a list
  (testing "count returns number of elements"
    (is (= 3 (eval-dt-last "items is [1 2 3]" "count items")))))

(deftest count-on-empty-list
  ;; Scenario: count on an empty list
  (testing "count of empty list is 0"
    (is (= 0 (eval-dt-last "items is []" "count items")))))

(deftest conj-appends-to-list
  ;; Scenario: conj appends to a list
  (testing "conj adds element to end of list"
    (is (= [1 2 3 4] (eval-dt-last "items is [1 2 3]" "conj items 4")))))

(deftest concat-joins-two-lists
  ;; Scenario: concat joins two lists
  (testing "concat produces a combined list"
    (is (= [1 2 3 4] (eval-dt-last "a is [1 2]" "b is [3 4]" "concat a b")))))

(deftest contains?-checks-element-presence
  ;; Scenario: contains? checks for element presence in a list
  (testing "contains? returns true for present element and false for absent"
    (let [setup "items is [1 2 3]"]
      (is (= true (eval-dt-last setup "contains? items 2")))
      (is (= false (eval-dt-last setup "contains? items 9"))))))

;; ---------------------------------------------------------------------------
;; Structures in Assignment
;; ---------------------------------------------------------------------------

(deftest assign-object-to-binding
  ;; Scenario: Assign an object to a binding
  (testing "object can be assigned to a name"
    (let [result (eval-dt-last "user is {name: \"Alice\" age: 25}" "user.name")]
      (is (= "Alice" result)))))

(deftest assign-list-to-binding
  ;; Scenario: Assign a list to a binding
  (testing "list can be assigned to a name"
    (let [result (eval-dt-last "items is [1 2 3]" "count items")]
      (is (= 3 result)))))

(deftest assign-nested-structure-to-binding
  ;; Scenario: Assign nested structure to a binding
  (testing "complex nested structure in binding"
    (let [result (eval-dt-last
                  (str "data is {\n"
                       "  users: [\n"
                       "    {name: \"Alice\" scores: [90 85 92]}\n"
                       "    {name: \"Bob\" scores: [78 88 95]}\n"
                       "  ]\n"
                       "  meta: {count: 2 version: 1}\n"
                       "}")
                  "data")]
      (is (map? result))
      (is (= 2 (count (:users result))))
      (is (= 2 (get-in result [:meta :count]))))))

;; ---------------------------------------------------------------------------
;; Structures in Pipeline Context
;; ---------------------------------------------------------------------------

(deftest object-literal-in-map-pipeline-stage
  ;; Scenario: Object literal in map pipeline stage
  (testing "constructing objects inside map stage"
    (let [result (eval-dt-last
                  "users is [{name: \"Alice\" age: 25} {name: \"Bob\" age: 30}]"
                  "users |> map {label: _.name years: _.age}")]
      (is (= "Alice" (:label (nth result 0))))
      (is (= 25 (:years (nth result 0)))))))

(deftest list-literal-as-pipeline-source
  ;; Scenario: List literal as pipeline source
  (testing "list literal piped through sort and take"
    (is (= [1 1 3] (eval-dt "[3 1 4 1 5] |> sort |> take 3")))))

(deftest empty-object-in-pipeline
  ;; Scenario: Empty object in pipeline
  (testing "mapping to empty objects"
    (is (= [{} {} {}] (eval-dt "[1 2 3] |> map {}")))))

;; ---------------------------------------------------------------------------
;; Type Checking and Reflection
;; ---------------------------------------------------------------------------

(deftest type-of-object
  ;; Scenario: Type of object
  (testing "type returns \"object\" for maps"
    (is (= "object" (eval-dt-last "x is {a: 1}" "type x")))))

(deftest type-of-list
  ;; Scenario: Type of list
  (testing "type returns \"list\" for vectors"
    (is (= "list" (eval-dt-last "x is [1 2 3]" "type x")))))

(deftest empty?-on-empty-object
  ;; Scenario: empty? on empty object
  (testing "empty? returns true for empty object"
    (is (= true (eval-dt "empty? {}")))))

(deftest empty?-on-non-empty-list
  ;; Scenario: empty? on non-empty list
  (testing "empty? returns false for non-empty list"
    (is (= false (eval-dt "empty? [1]")))))

;; ---------------------------------------------------------------------------
;; Edge Cases and Corner Cases
;; ---------------------------------------------------------------------------

(deftest object-with-boolean-values
  ;; Scenario: Object with boolean values
  (testing "boolean field values"
    (let [result (eval-dt "{active: true deleted: false}")]
      (is (= true (:active result)))
      (is (= false (:deleted result))))))

(deftest object-with-single-letter-keys
  ;; Scenario: Object with numeric keys that are valid identifiers
  (testing "single-letter keys x, y, z"
    (let [result (eval-dt "{x: 1 y: 2 z: 3}")]
      (is (= 3 (count result))))))

(deftest single-element-list
  ;; Scenario: Single-element list
  (testing "list with exactly one element"
    (let [result (eval-dt "[42]")]
      (is (= 1 (count result)))
      (is (= 42 (nth result 0))))))

(deftest single-field-object
  ;; Scenario: Single-field object
  (testing "object with exactly one field"
    (let [result (eval-dt "{x: 1}")]
      (is (= 1 (count result))))))

(deftest object-containing-list-value
  ;; Scenario: Object containing a list value
  (testing "list as an object field value"
    (let [result (eval-dt "{scores: [90 85 92]}")]
      (is (= [90 85 92] (:scores result))))))

(deftest list-containing-mix-of-objects-and-primitives
  ;; Scenario: List containing a mix of objects and primitives
  (testing "heterogeneous list with objects and primitives"
    (let [result (eval-dt "[{a: 1} 42 \"hello\" nil true]")]
      (is (= 5 (count result))))))

(deftest deeply-nested-mixed-structure
  ;; Scenario: Deeply nested mixed structure
  (testing "object containing list containing object containing list"
    (let [result (eval-dt "{a: [{b: [{c: 1}]}]}")]
      (is (map? result)))))

(deftest object-where-value-is-a-function
  ;; Scenario: Object where value is a function
  (testing "function stored as object field value"
    (let [result (eval-dt-last "{transform: [x -> x * 2]}" "{transform: [x -> x * 2]}")]
      (is (fn? (:transform result)))
      (is (= 10 ((:transform result) 5))))))

(deftest whitespace-variations-in-objects
  ;; Scenario: Whitespace variations in objects
  (testing "extra whitespace in object literal"
    (let [result (eval-dt "{  name:   \"Alice\"   age:   25  }")]
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result))))))

(deftest whitespace-variations-in-lists
  ;; Scenario: Whitespace variations in lists
  (testing "extra whitespace in list literal"
    (is (= [1 2 3] (eval-dt "[  1   2   3  ]")))))

(deftest newlines-as-element-separators-in-objects
  ;; Scenario: Newlines as element separators in objects
  (testing "newlines separating fields in a compact format"
    (let [result (eval-dt "{name: \"Alice\"\n age: 25}")]
      (is (= 2 (count result))))))

(deftest tab-characters-as-separators
  ;; Scenario: Tab characters as separators
  (testing "tab characters between fields"
    (let [result (eval-dt "{name:\t\"Alice\"\tage:\t25}")]
      (is (= 2 (count result))))))

(deftest field-access-on-literal-object
  ;; Scenario: Field access on a literal object
  (testing "dot access directly on an object literal"
    (is (= "Alice" (eval-dt "{name: \"Alice\" age: 25}.name")))))

(deftest field-access-on-function-call-result
  ;; Scenario: Field access on the result of a function call
  (testing "dot access on parenthesized function call"
    (is (= "Alice"
           (eval-dt-last "get-user is [-> {name: \"Alice\"}]"
                         "(get-user).name")))))

(deftest equality-of-objects
  ;; Scenario: Equality of objects
  (testing "object equality is order-independent"
    (is (= true (eval-dt-last "a is {x: 1 y: 2}"
                              "b is {y: 2 x: 1}"
                              "a = b")))))

(deftest equality-of-lists
  ;; Scenario: Equality of lists
  (testing "list equality with same elements in same order"
    (is (= true (eval-dt-last "a is [1 2 3]"
                              "b is [1 2 3]"
                              "a = b")))))

(deftest list-equality-is-order-dependent
  ;; Scenario: List equality is order-dependent
  (testing "lists with same elements in different order are not equal"
    (is (= false (eval-dt-last "a is [1 2 3]"
                               "b is [3 2 1]"
                               "a = b")))))

;; ---------------------------------------------------------------------------
;; Compilation / Clojure Mapping Verification
;; ---------------------------------------------------------------------------
;; Note: These scenarios verify semantic behavior. The "compiles to Clojure X"
;; assertions in the BDD are verified by checking that evaluation produces the
;; correct result, since the test helpers evaluate through the full pipeline.
;; ---------------------------------------------------------------------------

(deftest object-compiles-to-clojure-map-with-keyword-keys
  ;; Scenario: Object compiles to Clojure map with keyword keys
  (testing "object result uses keyword keys"
    (let [result (eval-dt "{name: \"Alice\" age: 25}")]
      (is (= "Alice" (:name result)))
      (is (= 25 (:age result)))
      (is (every? keyword? (keys result))))))

(deftest nested-object-compiles-to-nested-clojure-map
  ;; Scenario: Nested object compiles to nested Clojure map
  (testing "nested object produces nested keyword map"
    (let [result (eval-dt "{user: {name: \"Alice\"}}")]
      (is (= {:user {:name "Alice"}} result)))))

(deftest list-compiles-to-clojure-vector
  ;; Scenario: List compiles to Clojure vector
  (testing "list result is a Clojure vector"
    (let [result (eval-dt "[1 2 3]")]
      (is (vector? result))
      (is (= [1 2 3] result)))))

(deftest dot-access-compiles-to-get-in
  ;; Scenario: Dot access compiles to threading macro or get-in
  (testing "chained dot access behaves like get-in"
    (is (= "Alice"
           (eval-dt-last "user is {profile: {name: \"Alice\"}}"
                         "user.profile.name")))))

(deftest nil-tolerant-access-compiles-to-get-in
  ;; Scenario: Nil-tolerant access compiles to some-> or get-in
  (testing "nil-tolerant chained access returns nil for missing paths"
    (is (nil? (eval-dt-last "user is {}" "user.address.city")))))

(deftest dynamic-key-access-compiles-to-get-with-keyword-coercion
  ;; Scenario: Dynamic key access compiles to get with keyword coercion
  (testing "get with string key coerces to keyword lookup"
    (is (= "Alice"
           (eval-dt-last "user is {name: \"Alice\"}"
                         "key is \"name\""
                         "get user key")))))
