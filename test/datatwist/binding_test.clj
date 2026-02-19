(ns datatwist.binding-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? type-of throws?]]))

;; ==========================================================================
;; Feature 5: Binding & Destructuring with `is`
;; BDD Source: bdd/5-binding-destructuring.feature
;; ==========================================================================

;; --------------------------------------------------------------------------
;; Simple Binding
;; --------------------------------------------------------------------------

(deftest bind-a-literal-integer
  (testing "Scenario: Bind a literal integer"
    (is (= 42 (eval-dt-last "x is 42" "x")))))

(deftest bind-a-literal-string
  (testing "Scenario: Bind a literal string"
    (is (= "Alice" (eval-dt-last "name is \"Alice\"" "name")))))

(deftest bind-a-boolean
  (testing "Scenario: Bind a boolean"
    (is (= true (eval-dt-last "active is true" "active")))))

(deftest bind-nil-explicitly
  (testing "Scenario: Bind nil explicitly"
    (is (nil? (eval-dt-last "nothing is nil" "nothing")))))

(deftest bind-to-an-expression-result
  (testing "Scenario: Bind to an expression result"
    (is (= 7 (eval-dt-last "total is 3 + 4" "total")))))

(deftest bind-to-a-pipeline-result
  (testing "Scenario: Bind to a pipeline result"
    (is (= 2 (eval-dt-last
              "users is [{name: \"Alice\" active: true} {name: \"Bob\" active: false} {name: \"Carol\" active: true}]"
              "result is users |> filter _.active |> count"
              "result")))))

(deftest bind-to-a-function-definition
  (testing "Scenario: Bind to a function definition"
    (is (= 10 (eval-dt-last "double is [x -> x * 2]" "double 5")))))

(deftest bind-to-an-object-literal
  (testing "Scenario: Bind to an object literal"
    (is (= "Alice" (eval-dt-last "user is {name: \"Alice\" age: 30}" "user.name")))))

(deftest bind-to-a-list-literal
  (testing "Scenario: Bind to a list literal"
    (is (= [1 2 3 4 5] (eval-dt-last "nums is [1 2 3 4 5]" "nums")))))

;; --------------------------------------------------------------------------
;; Object Destructuring -- Basic
;; --------------------------------------------------------------------------

(deftest destructure-object-keys-into-same-name-bindings
  (testing "Scenario: Destructure object keys into same-name bindings"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name age} is user"
                    "name")))
    (is (= 30 (eval-dt-last
               "user is {name: \"Alice\" age: 30}"
               "{name age} is user"
               "age")))))

(deftest parser-distinguishes-destructuring-from-object-literal
  (testing "Scenario: Parser distinguishes destructuring pattern from object literal"
    ;; {name age} on left of `is` should parse as a destructuring pattern,
    ;; not as an object literal. If it parsed as a literal, evaluation would fail.
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name age} is user"
                    "name")))))

(deftest destructure-with-renamed-keys
  (testing "Scenario: Destructure with renamed keys"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name: n age: a} is user"
                    "n")))
    (is (= 30 (eval-dt-last
               "user is {name: \"Alice\" age: 30}"
               "{name: n age: a} is user"
               "a")))))

(deftest rename-syntax-is-context-dependent
  (testing "Scenario: Rename syntax is context-dependent"
    ;; On the right of `is`, {name: n} is an object literal
    (is (= 42 (eval-dt-last
               "n is 42"
               "obj is {name: n}"
               "obj.name")))
    ;; On the left of `is`, {name: n} is a destructuring rename
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\"}"
                    "{name: n} is user"
                    "n")))))

;; --------------------------------------------------------------------------
;; Object Destructuring -- Defaults with `?`
;; --------------------------------------------------------------------------

(deftest destructure-with-default-values
  (testing "Scenario: Destructure with default values"
    (is (= "anon" (eval-dt-last
                   "user is {}"
                   "{name ? \"anon\" age ? 0} is user"
                   "name")))
    (is (= 0 (eval-dt-last
              "user is {}"
              "{name ? \"anon\" age ? 0} is user"
              "age")))))

(deftest default-is-used-when-key-is-missing
  (testing "Scenario: Default is used when key is missing from source object"
    (is (= "anon" (eval-dt-last
                   "user is {}"
                   "{name ? \"anon\"} is user"
                   "name")))))

(deftest default-is-not-used-when-key-exists-but-value-is-nil
  (testing "Scenario: Default is NOT used when key exists but value is nil"
    ;; Follows Clojure :or semantics -- defaults apply only to missing keys
    (is (nil? (eval-dt-last
               "user is {name: nil}"
               "{name ? \"anon\"} is user"
               "name")))))

(deftest default-with-complex-expression
  (testing "Scenario: Default with complex expression"
    (is (= 30000 (eval-dt-last
                  "config is {}"
                  "{timeout ? 30 * 1000} is config"
                  "timeout")))))

;; --------------------------------------------------------------------------
;; Object Destructuring -- Whole Binding with `as`
;; --------------------------------------------------------------------------

(deftest destructure-object-with-as-whole-binding
  (testing "Scenario: Destructure object and retain whole value with `as`"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name age} as u is user"
                    "name")))
    (is (= 30 (eval-dt-last
               "user is {name: \"Alice\" age: 30}"
               "{name age} as u is user"
               "age")))
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name age} as u is user"
                    "u.name")))))

(deftest as-combined-with-rename-and-defaults
  (testing "Scenario: `as` combined with rename and defaults"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\"}"
                    "{name: n age ? 0} as u is user"
                    "n")))
    (is (= 0 (eval-dt-last
              "user is {name: \"Alice\"}"
              "{name: n age ? 0} as u is user"
              "age")))
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\"}"
                    "{name: n age ? 0} as u is user"
                    "u.name")))))

;; --------------------------------------------------------------------------
;; Object Destructuring -- Nested
;; --------------------------------------------------------------------------

(deftest nested-object-destructuring-one-level-deep
  (testing "Scenario: Nested object destructuring one level deep"
    (is (= "Portland" (eval-dt-last
                       "user is {name: \"Alice\" address: {city: \"Portland\" country: \"US\"}}"
                       "{address: {city country}} is user"
                       "city")))
    (is (= "US" (eval-dt-last
                 "user is {name: \"Alice\" address: {city: \"Portland\" country: \"US\"}}"
                 "{address: {city country}} is user"
                 "country")))))

(deftest nested-object-destructuring-two-levels-deep
  (testing "Scenario: Nested object destructuring two levels deep"
    (is (= 42 (eval-dt-last
               "deep is {a: {b: {c: 42}}}"
               "{a: {b: {c}}} is deep"
               "c")))))

(deftest nested-destructuring-with-rename-at-leaf
  (testing "Scenario: Nested destructuring with rename at leaf"
    (is (= "Portland" (eval-dt-last
                       "user is {address: {city: \"Portland\"}}"
                       "{address: {city: c}} is user"
                       "c")))))

(deftest nested-destructuring-with-nil-tolerance
  (testing "Scenario: Nested destructuring with nil-tolerance"
    ;; user has no address field, so nested city should be nil
    (is (nil? (eval-dt-last
               "user is {name: \"Alice\"}"
               "{address: {city}} is user"
               "city")))))

(deftest deeply-nested-destructuring-has-no-artificial-limit
  (testing "Scenario: Deeply nested destructuring has no artificial limit"
    (is (= 99 (eval-dt-last
               "data is {a: {b: {c: {d: {e: 99}}}}}"
               "{a: {b: {c: {d: {e}}}}} is data"
               "e")))))

;; --------------------------------------------------------------------------
;; List Destructuring -- Basic
;; --------------------------------------------------------------------------

(deftest destructure-list-into-positional-bindings
  (testing "Scenario: Destructure list into positional bindings"
    (is (= 1 (eval-dt-last "[a b c] is [1 2 3]" "a")))
    (is (= 2 (eval-dt-last "[a b c] is [1 2 3]" "b")))
    (is (= 3 (eval-dt-last "[a b c] is [1 2 3]" "c")))))

(deftest list-destructuring-with-rest
  (testing "Scenario: List destructuring with rest using `&`"
    (is (= 1 (eval-dt-last "[first & rest] is [1 2 3 4 5]" "first")))
    (is (= [2 3 4 5] (eval-dt-last "[first & rest] is [1 2 3 4 5]" "rest")))))

(deftest list-destructuring-rest-on-empty-tail
  (testing "Scenario: List destructuring with `& rest` on empty tail"
    (is (= 1 (eval-dt-last "[only & rest] is [1]" "only")))
    (is (nil? (eval-dt-last "[only & rest] is [1]" "rest")))))

(deftest list-pattern-shorter-than-source
  (testing "Scenario: List pattern is shorter than source -- extra elements ignored"
    (is (= 1 (eval-dt-last "[a b] is [1 2 3 4 5]" "a")))
    (is (= 2 (eval-dt-last "[a b] is [1 2 3 4 5]" "b")))))

(deftest list-pattern-longer-than-source
  (testing "Scenario: List pattern is longer than source -- excess bindings are nil"
    (is (= 1 (eval-dt-last "[a b c] is [1 2]" "a")))
    (is (= 2 (eval-dt-last "[a b c] is [1 2]" "b")))
    (is (nil? (eval-dt-last "[a b c] is [1 2]" "c")))))

;; --------------------------------------------------------------------------
;; List Destructuring -- Skip with `_`
;; --------------------------------------------------------------------------

(deftest skip-positions-with-underscore
  (testing "Scenario: Skip positions with underscore"
    (is (= 2 (eval-dt-last "[_ second _] is [1 2 3]" "second")))))

(deftest skip-first-element-capture-rest
  (testing "Scenario: Skip first element, capture rest"
    (is (= [2 3 4] (eval-dt-last "[_ & tail] is [1 2 3 4]" "tail")))))

(deftest multiple-underscores-in-a-row
  (testing "Scenario: Multiple underscores in a row"
    (is (= 4 (eval-dt-last "[_ _ _ fourth] is [1 2 3 4]" "fourth")))))

;; --------------------------------------------------------------------------
;; List Destructuring -- Whole Binding with `as`
;; --------------------------------------------------------------------------

(deftest list-destructuring-with-as
  (testing "Scenario: List destructuring with `as` for whole binding"
    (is (= 1 (eval-dt-last
              "items is [1 2 3 4 5]"
              "[head & tail] as all is items"
              "head")))
    (is (= [2 3 4 5] (eval-dt-last
                      "items is [1 2 3 4 5]"
                      "[head & tail] as all is items"
                      "tail")))
    (is (= [1 2 3 4 5] (eval-dt-last
                        "items is [1 2 3 4 5]"
                        "[head & tail] as all is items"
                        "all")))))

(deftest list-as-with-skip-pattern
  (testing "Scenario: List `as` with skip pattern"
    (is (= 2 (eval-dt-last
              "data is [1 2 3]"
              "[_ second] as original is data"
              "second")))
    (is (= [1 2 3] (eval-dt-last
                    "data is [1 2 3]"
                    "[_ second] as original is data"
                    "original")))))

;; --------------------------------------------------------------------------
;; Combined Object + List Destructuring
;; --------------------------------------------------------------------------

(deftest object-with-nested-list-destructuring
  (testing "Scenario: Object with nested list destructuring"
    (is (= "Alice" (eval-dt-last
                    "player is {name: \"Alice\" scores: [95 88 72]}"
                    "{name scores: [best & rest]} is player"
                    "name")))
    (is (= 95 (eval-dt-last
               "player is {name: \"Alice\" scores: [95 88 72]}"
               "{name scores: [best & rest]} is player"
               "best")))
    (is (= [88 72] (eval-dt-last
                    "player is {name: \"Alice\" scores: [95 88 72]}"
                    "{name scores: [best & rest]} is player"
                    "rest")))))

(deftest object-with-nested-list-and-defaults
  (testing "Scenario: Object with nested list that has defaults"
    (is (= "anon" (eval-dt-last
                   "player is {scores: [95 88]}"
                   "{name ? \"anon\" scores: [best & rest]} is player"
                   "name")))
    (is (= 95 (eval-dt-last
               "player is {scores: [95 88]}"
               "{name ? \"anon\" scores: [best & rest]} is player"
               "best")))))

(deftest list-of-objects-destructuring-in-pipeline
  (testing "Scenario: List of objects destructuring in pipeline context"
    (is (= ["Alice" "Bob"] (eval-dt-last
                            "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 25}]"
                            "users |> map [{name age} -> name]")))))

;; --------------------------------------------------------------------------
;; Destructuring in Function Parameters
;; --------------------------------------------------------------------------

(deftest function-parameter-with-object-destructuring
  (testing "Scenario: Function parameter with object destructuring"
    (is (= "Hello, Alice!" (eval-dt-last
                            "greet is [{name} -> format \"Hello, %s!\" name]"
                            "greet {name: \"Alice\"}")))))

(deftest function-with-two-destructured-parameters
  (testing "Scenario: Function with two destructured parameters"
    (is (= 55 (eval-dt-last
               "add-ages is [{age: a1} {age: a2} -> a1 + a2]"
               "add-ages {age: 30} {age: 25}")))))

(deftest function-parameter-with-list-destructuring
  (testing "Scenario: Function parameter with list destructuring"
    (is (= 1 (eval-dt-last
              "head is [[first & _] -> first]"
              "head [1 2 3]")))))

(deftest destructuring-in-anonymous-function-within-pipeline
  (testing "Scenario: Destructuring in anonymous function within pipeline"
    (is (= ["Alice is 30" "Bob is 25"]
           (eval-dt-last
            "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 25}]"
            "users |> map [{name age} -> format \"%s is %d\" name age]")))))

(deftest mixed-plain-and-destructured-parameters
  (testing "Scenario: Mixed plain and destructured parameters"
    (is (= "INFO: Alice (30)" (eval-dt-last
                               "process is [label {name age} -> format \"%s: %s (%d)\" label name age]"
                               "process \"INFO\" {name: \"Alice\" age: 30}")))))

;; --------------------------------------------------------------------------
;; Scope, Shadowing, and Rebinding
;; --------------------------------------------------------------------------

(deftest top-level-binding-creates-a-def
  (testing "Scenario: Top-level binding creates a def"
    (is (= 42 (eval-dt-last "x is 42" "x")))))

(deftest binding-inside-function-body-creates-lexical-scope
  (testing "Scenario: Binding inside a function body creates lexical scope"
    (is (= 6 (eval-dt-last
              "compute is [data -> n is count data; n * 2]"
              "compute [1 2 3]")))))

(deftest multiple-sequential-bindings-in-function-body
  (testing "Scenario: Multiple sequential bindings in function body"
    (is (= 2 (eval-dt-last
              "process is [data -> filtered is data |> filter _.active; n is count filtered; n]"
              "process [{name: \"A\" active: true} {name: \"B\" active: false} {name: \"C\" active: true}]")))))

(deftest inner-scope-shadows-outer-binding
  (testing "Scenario: Inner scope shadows outer binding"
    (is (= 6 (eval-dt-last
              "x is 10"
              "f is [x -> x + 1]"
              "f 5")))
    (is (= 10 (eval-dt-last
               "x is 10"
               "f is [x -> x + 1]"
               "x")))))

(deftest rebinding-at-top-level-redefines-the-var
  (testing "Scenario: Rebinding at top-level redefines the var"
    (is (= 10 (eval-dt-last "x is 5" "x is 10" "x")))))

(deftest rebinding-inside-function-body-shadows-previous-local
  (testing "Scenario: Rebinding inside function body shadows previous local"
    (is (= 2 (eval-dt-last
              "f is [-> x is 1; x is x + 1; x]"
              "f")))))

;; --------------------------------------------------------------------------
;; Destructuring in Pipeline Context
;; --------------------------------------------------------------------------

(deftest destructuring-in-map-within-pipeline
  (testing "Scenario: Destructuring in map within pipeline"
    (is (= [{:display "Alice" :years 30} {:display "Bob" :years 25}]
           (eval-dt-last
            "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 25}]"
            "users |> map [{name age} -> {display: name years: age}]")))))

(deftest destructuring-in-filter-within-pipeline
  (testing "Scenario: Destructuring in filter within pipeline"
    (is (= [{:name "Alice" :age 30}]
           (eval-dt-last
            "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 15}]"
            "users |> filter [{age} -> age > 18]")))))

(deftest binding-pipeline-result-with-destructuring
  (testing "Scenario: Binding pipeline result with destructuring"
    (is (= 42 (eval-dt-last
               "data is {total: 42 items: [1 2 3]}"
               "{total items} is data"
               "total")))
    (is (= [1 2 3] (eval-dt-last
                    "data is {total: 42 items: [1 2 3]}"
                    "{total items} is data"
                    "items")))))

;; --------------------------------------------------------------------------
;; Edge Cases and Error Behavior
;; --------------------------------------------------------------------------

(deftest destructuring-from-nil-source-yields-nil
  (testing "Scenario: Destructuring from nil source yields nil for all bindings"
    (is (nil? (eval-dt-last "data is nil" "{name age} is data" "name")))
    (is (nil? (eval-dt-last "data is nil" "{name age} is data" "age")))))

(deftest list-destructuring-from-nil-yields-nil
  (testing "Scenario: List destructuring from nil yields nil"
    (is (nil? (eval-dt-last "items is nil" "[a b c] is items" "a")))
    (is (nil? (eval-dt-last "items is nil" "[a b c] is items" "b")))
    (is (nil? (eval-dt-last "items is nil" "[a b c] is items" "c")))))

(deftest object-destructuring-from-a-list-is-not-an-error
  (testing "Scenario: Object destructuring from a list is not an error"
    ;; A vector has no :name key -- Clojure does not error, just returns nil
    (is (nil? (eval-dt-last "data is [1 2 3]" "{name} is data" "name")))))

(deftest list-destructuring-from-an-object-yields-nil
  (testing "Scenario: List destructuring from an object yields nil elements"
    ;; Maps are not sequential -- Clojure destructuring produces nil
    (is (nil? (eval-dt-last "data is {name: \"Alice\"}" "[a b] is data" "a")))
    (is (nil? (eval-dt-last "data is {name: \"Alice\"}" "[a b] is data" "b")))))

(deftest underscore-is-not-a-valid-binding-name
  (testing "Scenario: Underscore is not a valid binding name"
    (is (or (parse-error? "_ is 42")
            (throws? "_ is 42")))))

(deftest empty-destructuring-pattern-is-a-parse-error
  (testing "Scenario: Empty destructuring pattern is a parse error"
    (is (parse-error? "{} is user"))))

(deftest ampersand-must-be-followed-by-exactly-one-identifier
  (testing "Scenario: `&` must be followed by exactly one identifier in list destructuring"
    (is (parse-error? "[a & b c] is items"))))

(deftest ampersand-at-start-of-list-pattern-is-valid
  (testing "Scenario: `&` cannot appear at the start of a list pattern -- but it is valid"
    ;; [& rest] is valid -- rest gets the entire list
    (is (= [1 2 3] (eval-dt-last
                    "items is [1 2 3]"
                    "[& rest] is items"
                    "rest")))))

(deftest as-must-be-followed-by-a-single-identifier
  (testing "Scenario: `as` must be followed by a single identifier"
    (is (parse-error? "{name} as is user"))))

(deftest duplicate-binding-names-in-same-destructuring-level
  (testing "Scenario: Duplicate binding names in same destructuring level"
    ;; Compilation produces a warning or the last binding wins
    (is (or (throws? "{name name} is user")
            ;; If it does not throw, at least it should not crash
            (some? (eval-dt-last
                    "user is {name: \"Alice\"}"
                    "{name name} is user"
                    "name"))))))

;; --------------------------------------------------------------------------
;; Rest in Object Destructuring
;; --------------------------------------------------------------------------

(deftest object-rest-is-not-supported
  (testing "Scenario: Object rest is not supported"
    ;; `&` rest syntax is for list destructuring only
    (is (parse-error? "{name & rest} is user"))))

;; --------------------------------------------------------------------------
;; Syntax Disambiguation Rules
;; --------------------------------------------------------------------------

(deftest left-of-is-object-pattern-is-destructuring
  (testing "Scenario: `{name age}` on left of `is` is a destructuring pattern"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 30}"
                    "{name age} is user"
                    "name")))))

(deftest right-of-is-object-literal-with-colon-values
  (testing "Scenario: `{name: \"Alice\" age: 25}` on right of `is` is an object literal"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 25}"
                    "user.name")))))

(deftest name-colon-n-is-context-dependent
  (testing "Scenario: `{name: n}` is context-dependent"
    ;; On the right of `is` -> object literal with key name and value from variable n
    (is (= 42 (eval-dt-last
               "n is 42"
               "result is {name: n}"
               "result.name")))
    ;; On the left of `is` -> destructuring pattern renaming name to n
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\"}"
                    "{name: n} is user"
                    "n")))))

(deftest list-literal-on-right-of-is
  (testing "Scenario: `[1 2 3]` on right of `is` is a list literal"
    (is (= [1 2 3] (eval-dt-last "nums is [1 2 3]" "nums")))))

(deftest list-pattern-on-left-of-is-is-destructuring
  (testing "Scenario: `[a b c]` on left of `is` is a destructuring pattern"
    (is (= 1 (eval-dt-last
              "items is [10 20 30]"
              "[a b c] is items"
              "a")))
    (is (= 20 (eval-dt-last
               "items is [10 20 30]"
               "[a b c] is items"
               "b")))
    (is (= 30 (eval-dt-last
               "items is [10 20 30]"
               "[a b c] is items"
               "c")))))
