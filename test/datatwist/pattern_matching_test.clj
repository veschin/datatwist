(ns datatwist.pattern-matching-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws?]]))

;; ==========================================================================
;; Feature 6: Pattern Matching & Guards
;;
;; Every test maps 1:1 to a scenario in bdd/6-pattern-matching.feature.
;; Tests are grouped by `testing` blocks matching BDD sections.
;; ==========================================================================

;; ---------------------------------------------------------------------------
;; SECTION 1: Guard expressions (boolean conditions)
;; ---------------------------------------------------------------------------

(deftest guard-expressions

  (testing "Simple guard expression with binding"
    ;; Scenario: Simple guard expression with binding
    ;; amount = 500 -> "silver" (second branch matches)
    (is (= "silver"
           (eval-dt-last
            "amount is 500"
            "tier is"
            "  | amount > 1000 -> \"gold\""
            "  | amount > 100  -> \"silver\""
            "  | _             -> \"bronze\""))))

  (testing "Guard expression evaluates first matching branch"
    ;; Scenario: Guard expression evaluates first matching branch
    ;; x = 50 -> "big" (x > 10 matches before x > 0)
    (is (= "big"
           (eval-dt-last
            "x is 50"
            "label is"
            "  | x > 100 -> \"huge\""
            "  | x > 10  -> \"big\""
            "  | x > 0   -> \"small\""
            "  | _       -> \"zero-or-negative\""))))

  (testing "Guard with logical operators"
    ;; Scenario: Guard with logical operators
    ;; role = "editor", active = true -> "write"
    (is (= "write"
           (eval-dt-last
            "role is \"editor\""
            "active is true"
            "access is"
            "  | role = \"admin\" or role = \"superadmin\" -> \"full\""
            "  | role = \"editor\" and active            -> \"write\""
            "  | _                                     -> \"read\""))))

  (testing "Guard with comparison chains"
    ;; Scenario: Guard with comparison chains
    ;; age = 15 -> "teen"
    (is (= "teen"
           (eval-dt-last
            "age is 15"
            "bucket is"
            "  | age >= 0 and age < 13  -> \"child\""
            "  | age >= 13 and age < 18 -> \"teen\""
            "  | age >= 18              -> \"adult\""
            "  | _                      -> \"invalid\""))))

  (testing "Guard falls through to default"
    ;; Scenario: Guard falls through to default
    ;; x = 3, no branch matches except default -> "small"
    (is (= "small"
           (eval-dt-last
            "x is 3"
            "result is"
            "  | x > 100 -> \"big\""
            "  | _       -> \"small\""))))

  (testing "Guard with function call in condition"
    ;; Scenario: Guard with function call in condition
    ;; x = 7 -> even? 7 = false -> "odd"
    (is (= "odd"
           (eval-dt-last
            "x is 7"
            "even? is [n -> n % 2 = 0]"
            "label is"
            "  | even? x -> \"even\""
            "  | _       -> \"odd\""))))

  (testing "Single-line guard expression after is"
    ;; Scenario: Single-line guard expression after is
    ;; x = 10 -> "high"
    (is (= "high"
           (eval-dt-last
            "x is 10"
            "tier is | x > 5 -> \"high\" | _ -> \"low\"")))))

;; ---------------------------------------------------------------------------
;; SECTION 2: Guards in object fields
;; ---------------------------------------------------------------------------

(deftest guards-in-object-fields

  (testing "Guard as object field value (multi-line)"
    ;; Scenario: Guard as object field value (multi-line)
    ;; amount = 2000 -> tier = "gold"
    (is (= "gold"
           (get
            (eval-dt-last
             "amount is 2000"
             "result is {"
             "  tier:"
             "    | amount > 1000 -> \"gold\""
             "    | _             -> \"bronze\""
             "}")
            :tier))))

  (testing "Guard as object field value (inline)"
    ;; Scenario: Guard as object field value (inline)
    ;; amount = 50 -> tier = "bronze"
    (is (= "bronze"
           (get
            (eval-dt-last
             "amount is 50"
             "result is {tier: | amount > 1000 -> \"gold\" | _ -> \"bronze\"}")
            :tier))))

  (testing "Multiple guard fields in one object"
    ;; Scenario: Multiple guard fields in one object
    ;; spending = 2000 -> tier = "gold", age = 15 -> risk = "minor"
    (let [result (eval-dt-last
                  "spending is 2000"
                  "age is 15"
                  "result is {"
                  "  tier:"
                  "    | spending > 1000 -> \"gold\""
                  "    | _               -> \"bronze\""
                  "  risk:"
                  "    | age < 18 -> \"minor\""
                  "    | _        -> \"standard\""
                  "}")]
      (is (= "gold" (get result :tier)))
      (is (= "minor" (get result :risk))))))

;; ---------------------------------------------------------------------------
;; SECTION 3: Guards inside pipelines
;; ---------------------------------------------------------------------------

(deftest guards-in-pipelines

  (testing "Guard in pipeline map"
    ;; Scenario: Guard in pipeline map
    ;; users = [{name: "Alice" spending: 1500} {name: "Bob" spending: 200}]
    ;; -> [{name: "Alice" tier: "gold"} {name: "Bob" tier: "bronze"}]
    (let [result (eval-dt-last
                  "users is [{name: \"Alice\" spending: 1500} {name: \"Bob\" spending: 200}]"
                  "users |> map {"
                  "  name: _.name"
                  "  tier:"
                  "    | _.spending > 1000 -> \"gold\""
                  "    | _                 -> \"bronze\""
                  "}")]
      (is (= [{:name "Alice" :tier "gold"}
              {:name "Bob" :tier "bronze"}]
             result))))

  (testing "Guard in pipeline map using wildcard access"
    ;; Scenario: Guard in pipeline map using wildcard access
    ;; orders with total/paid combinations
    (let [result (eval-dt-last
                  "orders is [{total: 600 paid: true} {total: 600 paid: false} {total: 50 paid: true}]"
                  "orders |> map {"
                  "  status:"
                  "    | _.total > 500 and _.paid -> \"confirmed\""
                  "    | _.total > 500            -> \"pending-payment\""
                  "    | _                        -> \"small-order\""
                  "}")]
      (is (= [{:status "confirmed"}
              {:status "pending-payment"}
              {:status "small-order"}]
             result)))))

;; ---------------------------------------------------------------------------
;; SECTION 4: Structural pattern matching
;; ---------------------------------------------------------------------------

(deftest structural-pattern-matching

  (testing "Structural match on object type field"
    ;; Scenario: Structural match on object type field
    ;; {type: "book" title: "Dune"} -> "book"
    (is (= "book"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\"}  -> \"book\""
            "  | {type: \"movie\"} -> \"movie\""
            "  | _               -> \"unknown\""
            "]"
            "classify {type: \"book\" title: \"Dune\"}"))))

  (testing "Structural match on object with multiple fields"
    ;; Scenario: Structural match on object with multiple fields
    ;; {type: "book" format: "hardcover" pages: 300} -> "hardcover-book"
    (is (= "hardcover-book"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\" format: \"hardcover\"} -> \"hardcover-book\""
            "  | {type: \"book\"}                     -> \"book\""
            "  | _                                  -> \"other\""
            "]"
            "classify {type: \"book\" format: \"hardcover\" pages: 300}"))))

  (testing "Structural match on list patterns"
    ;; Scenario: Structural match on list patterns
    ;; [1 2 3 4] -> "collection" (matches [x & rest])
    (is (= "collection"
           (eval-dt-last
            "describe is [data ->"
            "  | []        -> \"empty\""
            "  | [x]       -> \"single\""
            "  | [x y]     -> \"pair\""
            "  | [x & rest] -> \"collection\""
            "  | _         -> \"not a list\""
            "]"
            "describe [1 2 3 4]"))))

  (testing "Structural match on empty list"
    ;; Scenario: Structural match on empty list
    ;; [] -> "empty"
    (is (= "empty"
           (eval-dt-last
            "describe is [data ->"
            "  | [] -> \"empty\""
            "  | _  -> \"non-empty\""
            "]"
            "describe []"))))

  (testing "Structural match on single-element list"
    ;; Scenario: Structural match on single-element list
    ;; [42] -> "single"
    (is (= "single"
           (eval-dt-last
            "describe is [data ->"
            "  | [x] -> \"single\""
            "  | _   -> \"other\""
            "]"
            "describe [42]"))))

  (testing "Structural match distinguishes object from list"
    ;; Scenario: Structural match distinguishes object from list
    ;; {type: "x"} -> "has-type-field", [1 2 3] -> "list"
    (let [what-fn (str "what is [data ->\n"
                       "  | {type: _} -> \"has-type-field\"\n"
                       "  | [_ & _]   -> \"list\"\n"
                       "  | _         -> \"something-else\"\n"
                       "]")]
      (is (= "has-type-field"
             (eval-dt-last what-fn "what {type: \"x\"}")))
      (is (= "list"
             (eval-dt-last what-fn "what [1 2 3]"))))))

;; ---------------------------------------------------------------------------
;; SECTION 5: Literal patterns
;; ---------------------------------------------------------------------------

(deftest literal-patterns

  (testing "Match on integer literal"
    ;; Scenario: Match on integer literal
    ;; 42 -> "answer"
    (is (= "answer"
           (eval-dt-last
            "describe is [n ->"
            "  | 0  -> \"zero\""
            "  | 1  -> \"one\""
            "  | 42 -> \"answer\""
            "  | _  -> \"other\""
            "]"
            "describe 42"))))

  (testing "Match on string literal"
    ;; Scenario: Match on string literal
    ;; "error" -> "failure"
    (is (= "failure"
           (eval-dt-last
            "respond is [status ->"
            "  | \"ok\"    -> \"success\""
            "  | \"error\" -> \"failure\""
            "  | _       -> \"unknown\""
            "]"
            "respond \"error\""))))

  (testing "Match on boolean literal"
    ;; Scenario: Match on boolean literal
    ;; true -> "yes"
    (is (= "yes"
           (eval-dt-last
            "describe is [flag ->"
            "  | true  -> \"yes\""
            "  | false -> \"no\""
            "  | _     -> \"not a boolean\""
            "]"
            "describe true"))))

  (testing "Match on nil"
    ;; Scenario: Match on nil
    ;; nil -> "nothing"
    (is (= "nothing"
           (eval-dt-last
            "safe is [val ->"
            "  | nil -> \"nothing\""
            "  | _   -> \"something\""
            "]"
            "safe nil")))))

;; ---------------------------------------------------------------------------
;; SECTION 6: Variable binding in structural patterns
;; ---------------------------------------------------------------------------

(deftest variable-binding-in-patterns

  (testing "Bind variable from object pattern"
    ;; Scenario: Bind variable from object pattern
    ;; {name: "Alice" age: 30} -> "Hello, Alice!"
    (is (= "Hello, Alice!"
           (eval-dt-last
            "greet is [person ->"
            "  | {name: n} -> format \"Hello, %s!\" n"
            "  | _         -> \"Hello, stranger!\""
            "]"
            "greet {name: \"Alice\" age: 30}"))))

  (testing "Bind multiple variables from object pattern"
    ;; Scenario: Bind multiple variables from object pattern
    ;; {name: "Widget" price: 9.99} -> "Widget costs 9.99"
    (is (= "Widget costs 9.99"
           (eval-dt-last
            "summary is [item ->"
            "  | {name: n price: p} -> format \"%s costs %s\" n p"
            "  | _                  -> \"unknown item\""
            "]"
            "summary {name: \"Widget\" price: 9.99}"))))

  (testing "Bind variable from list pattern"
    ;; Scenario: Bind variable from list pattern
    ;; [10 20 30] -> first = 10
    (is (= 10
           (eval-dt-last
            "head is [xs ->"
            "  | [first & _] -> first"
            "  | _           -> nil"
            "]"
            "head [10 20 30]"))))

  (testing "Bind head and tail from list"
    ;; Scenario: Bind head and tail from list
    ;; [1 2 3] -> {head: 1 tail: [2 3]}
    (is (= {:head 1 :tail [2 3]}
           (eval-dt-last
            "parts is [xs ->"
            "  | [h & t] -> {head: h tail: t}"
            "  | _       -> {head: nil tail: []}"
            "]"
            "parts [1 2 3]")))))

;; ---------------------------------------------------------------------------
;; SECTION 7: when clause (guard after structural pattern)
;; ---------------------------------------------------------------------------

(deftest when-clause

  (testing "Structural pattern with when guard"
    ;; Scenario: Structural pattern with when guard
    ;; {type: "book" pages: 800} -> "epic" (pages > 500)
    (is (= "epic"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\" pages: p} when p > 500 -> \"epic\""
            "  | {type: \"book\"}                        -> \"book\""
            "  | _                                     -> \"other\""
            "]"
            "classify {type: \"book\" pages: 800}"))))

  (testing "when guard fails, falls through to next branch"
    ;; Scenario: when guard fails, falls through to next branch
    ;; {type: "book" pages: 200} -> "book" (pages <= 500, first branch fails)
    (is (= "book"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\" pages: p} when p > 500 -> \"epic\""
            "  | {type: \"book\"}                        -> \"book\""
            "  | _                                     -> \"other\""
            "]"
            "classify {type: \"book\" pages: 200}"))))

  (testing "when guard with multiple conditions"
    ;; Scenario: when guard with multiple conditions
    ;; {type: "movie" rating: 9 year: 2010} -> "modern-classic"
    (is (= "modern-classic"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"movie\" rating: r year: y} when r > 8 and y > 2000 -> \"modern-classic\""
            "  | {type: \"movie\" rating: r} when r > 8                      -> \"classic\""
            "  | {type: \"movie\"}                                            -> \"movie\""
            "  | _                                                          -> \"other\""
            "]"
            "classify {type: \"movie\" rating: 9 year: 2010}"))))

  (testing "when guard using bound variables from pattern"
    ;; Scenario: when guard using bound variables from pattern
    ;; {name: "Bob" age: 25} -> "Bob is adult"
    ;; {name: "Eve" age: 12} -> "Eve is minor"
    (let [analyze-fn (str "analyze is [record ->\n"
                          "  | {name: n age: a} when a > 18 -> format \"%s is adult\" n\n"
                          "  | {name: n age: a}             -> format \"%s is minor\" n\n"
                          "  | _                            -> \"no name\"\n"
                          "]")]
      (is (= "Bob is adult"
             (eval-dt-last analyze-fn "analyze {name: \"Bob\" age: 25}")))
      (is (= "Eve is minor"
             (eval-dt-last analyze-fn "analyze {name: \"Eve\" age: 12}"))))))

;; ---------------------------------------------------------------------------
;; SECTION 8: Nested structural patterns
;; ---------------------------------------------------------------------------

(deftest nested-structural-patterns

  (testing "Nested object pattern"
    ;; Scenario: Nested object pattern
    ;; {name: "Alice" address: {city: "Moscow" zip: "101000"}} -> "Moscow"
    (is (= "Moscow"
           (eval-dt-last
            "city-of is [person ->"
            "  | {address: {city: c}} -> c"
            "  | _                    -> \"unknown\""
            "]"
            "city-of {name: \"Alice\" address: {city: \"Moscow\" zip: \"101000\"}}"))))

  (testing "Object pattern with nested list"
    ;; Scenario: Object pattern with nested list
    ;; {name: "post" tags: ["clojure" "jvm"]} -> "clojure"
    (is (= "clojure"
           (eval-dt-last
            "first-tag is [item ->"
            "  | {tags: [t & _]} -> t"
            "  | _               -> \"untagged\""
            "]"
            "first-tag {name: \"post\" tags: [\"clojure\" \"jvm\"]}"))))

  (testing "List of objects structural match"
    ;; Scenario: List of objects structural match
    ;; [{name: "Alice"} {name: "Bob"}] -> "Alice"
    (is (= "Alice"
           (eval-dt-last
            "first-name is [data ->"
            "  | [{name: n} & _] -> n"
            "  | _               -> \"nobody\""
            "]"
            "first-name [{name: \"Alice\"} {name: \"Bob\"}]")))))

;; ---------------------------------------------------------------------------
;; SECTION 9: Pattern matching inside function bodies
;; ---------------------------------------------------------------------------

(deftest pattern-matching-in-functions

  (testing "Pattern matching as function body"
    ;; Scenario: Pattern matching as function body
    ;; {type: "movie" title: "Arrival"} -> "movie"
    (is (= "movie"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\"}  -> \"book\""
            "  | {type: \"movie\"} -> \"movie\""
            "  | nil             -> \"nothing\""
            "  | _               -> \"unknown\""
            "]"
            "classify {type: \"movie\" title: \"Arrival\"}"))))

  (testing "Function with guard (not structural)"
    ;; Scenario: Function with guard (not structural)
    ;; abs(-5) = 5, abs(3) = 3
    (let [abs-fn (str "abs is [x ->\n"
                      "  | x >= 0 -> x\n"
                      "  | _      -> 0 - x\n"
                      "]")]
      (is (= 5 (eval-dt-last abs-fn "abs (0 - 5)")))
      (is (= 3 (eval-dt-last abs-fn "abs 3")))))

  (testing "Function with mixed guard and structural branches"
    ;; Scenario: Function with mixed guard and structural branches
    ;; nil -> "nil-input", {error: "timeout"} -> "Error: timeout", {data: 42} -> "ok"
    (let [process-fn (str "process is [input ->\n"
                          "  | nil          -> \"nil-input\"\n"
                          "  | {error: msg} -> format \"Error: %s\" msg\n"
                          "  | _            -> \"ok\"\n"
                          "]")]
      (is (= "nil-input"
             (eval-dt-last process-fn "process nil")))
      (is (= "Error: timeout"
             (eval-dt-last process-fn "process {error: \"timeout\"}")))
      (is (= "ok"
             (eval-dt-last process-fn "process {data: 42}"))))))

;; ---------------------------------------------------------------------------
;; SECTION 10: Default / catch-all
;; ---------------------------------------------------------------------------

(deftest default-catch-all

  (testing "Default branch with underscore"
    ;; Scenario: Default branch with underscore
    ;; safe-div(10, 0) = nil, safe-div(10, 2) = 5
    (let [safe-div-fn (str "safe-div is [a b ->\n"
                           "  | b = 0 -> nil\n"
                           "  | _     -> a / b\n"
                           "]")]
      (is (nil? (eval-dt-last safe-div-fn "safe-div 10 0")))
      (is (= 5.0 (eval-dt-last safe-div-fn "safe-div 10 2")))))

  (testing "Default branch fires when no other branch matches"
    ;; Scenario: Default branch fires when no other branch matches
    ;; "hamster" -> "unknown species"
    (is (= "unknown species"
           (eval-dt-last
            "identify is [x ->"
            "  | \"cat\"  -> \"feline\""
            "  | \"dog\"  -> \"canine\""
            "  | _      -> \"unknown species\""
            "]"
            "identify \"hamster\"")))))

;; ---------------------------------------------------------------------------
;; SECTION 11: First-match semantics (order matters)
;; ---------------------------------------------------------------------------

(deftest first-match-semantics

  (testing "First matching branch wins"
    ;; Scenario: First matching branch wins
    ;; {type: "book"} -> "matched-first" (not "matched-second")
    (is (= "matched-first"
           (eval-dt-last
            "classify is [data ->"
            "  | {type: \"book\"} -> \"matched-first\""
            "  | {type: \"book\"} -> \"matched-second\""
            "  | _              -> \"default\""
            "]"
            "classify {type: \"book\"}"))))

  (testing "More specific pattern should be listed before general"
    ;; Scenario: More specific pattern should be listed before general
    ;; Tests all four fallthrough levels
    (let [classify-fn (str "classify is [data ->\n"
                           "  | {type: \"book\" pages: p} when p > 500 -> \"epic-book\"\n"
                           "  | {type: \"book\"}                        -> \"regular-book\"\n"
                           "  | {type: _}                             -> \"has-type\"\n"
                           "  | _                                     -> \"anything\"\n"
                           "]")]
      (is (= "epic-book"
             (eval-dt-last classify-fn "classify {type: \"book\" pages: 800}")))
      (is (= "regular-book"
             (eval-dt-last classify-fn "classify {type: \"book\" pages: 100}")))
      (is (= "has-type"
             (eval-dt-last classify-fn "classify {type: \"vinyl\"}")))
      (is (= "anything"
             (eval-dt-last classify-fn "classify 42"))))))

;; ---------------------------------------------------------------------------
;; SECTION 12: Multi-line and formatting
;; ---------------------------------------------------------------------------

(deftest multi-line-and-formatting

  (testing "Each guard branch on its own line"
    ;; Scenario: Each guard branch on its own line
    ;; Should parse successfully
    (is (not (parse-error?
              (str "tier is\n"
                   "  | amount > 1000 -> \"gold\"\n"
                   "  | amount > 100  -> \"silver\"\n"
                   "  | _             -> \"bronze\"")))))

  (testing "All branches on one line"
    ;; Scenario: All branches on one line
    ;; Should parse successfully
    (is (not (parse-error?
              "tier is | amount > 1000 -> \"gold\" | amount > 100 -> \"silver\" | _ -> \"bronze\""))))

  (testing "Guard block indented inside object field"
    ;; Scenario: Guard block indented inside object field
    ;; Should parse successfully
    (is (not (parse-error?
              (str "result is {\n"
                   "  tier:\n"
                   "    | _.spending > 1000 -> \"gold\"\n"
                   "    | _.spending > 100  -> \"silver\"\n"
                   "    | _                 -> \"bronze\"\n"
                   "  name: _.name\n"
                   "}"))))))

;; ---------------------------------------------------------------------------
;; SECTION 13: Interaction with pipe operator
;; ---------------------------------------------------------------------------

(deftest interaction-with-pipe-operator

  (testing "Pipeline result feeds into guard via binding"
    ;; Scenario: Pipeline result feeds into guard via binding
    ;; orders = [{amount: 300} {amount: 400} {amount: 500}]
    ;; total = 1200 -> "gold"
    (is (= "gold"
           (eval-dt-last
            "orders is [{amount: 300} {amount: 400} {amount: 500}]"
            "total is orders |> map _.amount |> sum"
            "tier is"
            "  | total > 1000 -> \"gold\""
            "  | _            -> \"bronze\""))))

  (testing "Guard expression as argument to pipeline map"
    ;; Scenario: Guard expression as argument to pipeline map
    ;; items = [{weight: 60} {weight: 20}] -> ["heavy" "light"]
    (is (= ["heavy" "light"]
           (eval-dt-last
            "items is [{weight: 60} {weight: 20}]"
            "items |> map [item ->"
            "  | item.weight > 50 -> \"heavy\""
            "  | _                -> \"light\""
            "]")))))

;; ---------------------------------------------------------------------------
;; SECTION 14: Exhaustiveness
;; ---------------------------------------------------------------------------

(deftest exhaustiveness

  (testing "Guard block without default produces a warning"
    ;; Scenario: Guard block without default produces a warning
    ;; When no branch matches, result is nil
    (is (nil? (eval-dt-last
               "x is -1"
               "label is"
               "  | x > 10 -> \"big\""
               "  | x > 0  -> \"small\""))))

  (testing "Guard block with default produces no warning"
    ;; Scenario: Guard block with default produces no warning
    ;; Should compile and evaluate without issue
    (is (= "small"
           (eval-dt-last
            "x is 3"
            "label is"
            "  | x > 10 -> \"big\""
            "  | _      -> \"small\"")))))

;; ---------------------------------------------------------------------------
;; SECTION 15: Edge cases and corner cases
;; ---------------------------------------------------------------------------

(deftest edge-cases

  (testing "Empty object pattern matches any object"
    ;; Scenario: Empty object pattern matches any object
    ;; {name: "Alice"} -> "is-object", 42 -> "not-object"
    (let [is-obj-fn (str "is-obj is [data ->\n"
                         "  | {} -> \"is-object\"\n"
                         "  | _  -> \"not-object\"\n"
                         "]")]
      (is (= "is-object"
             (eval-dt-last is-obj-fn "is-obj {name: \"Alice\"}")))
      (is (= "not-object"
             (eval-dt-last is-obj-fn "is-obj 42")))))

  (testing "Nil input to structural match"
    ;; Scenario: Nil input to structural match
    ;; nil -> "nil"
    (is (= "nil"
           (eval-dt-last
            "check is [data ->"
            "  | nil         -> \"nil\""
            "  | {type: _}   -> \"has-type\""
            "  | _           -> \"other\""
            "]"
            "check nil"))))

  (testing "Deeply nested nil-tolerant access in guard"
    ;; Scenario: Deeply nested nil-tolerant access in guard
    ;; data = {config: nil} -> nil-safe access -> "default-mode"
    (is (= "default-mode"
           (eval-dt-last
            "data is {config: nil}"
            "result is"
            "  | data.config.settings.theme = \"dark\" -> \"dark-mode\""
            "  | _                                   -> \"default-mode\""))))

  (testing "Guard condition referencing undefined variable evaluates to nil"
    ;; Scenario: Guard condition referencing undefined variable evaluates to nil
    ;; unknown-var is undefined -> nil > 10 is false -> "fallback"
    (is (= "fallback"
           (eval-dt-last
            "result is"
            "  | unknown-var > 10 -> \"big\""
            "  | _                -> \"fallback\""))))

  (testing "Guard with result expression containing function call"
    ;; Scenario: Guard with result expression containing function call
    ;; {name: "Alice" age: 30} -> "Mr/Ms Alice"
    (is (= "Mr/Ms Alice"
           (eval-dt-last
            "greet is [person ->"
            "  | {name: n age: a} when a >= 18 -> format \"Mr/Ms %s\" n"
            "  | {name: n}                     -> format \"Young %s\" n"
            "  | _                             -> \"stranger\""
            "]"
            "greet {name: \"Alice\" age: 30}"))))

  (testing "Guard result is a complex expression"
    ;; Scenario: Guard result is a complex expression
    ;; 5 -> 5 * 2 + 1 = 11
    (is (= 11
           (eval-dt-last
            "compute is [x ->"
            "  | x > 0 -> x * 2 + 1"
            "  | _     -> 0"
            "]"
            "compute 5"))))

  (testing "Guard result is an object"
    ;; Scenario: Guard result is an object
    ;; 5 -> {status: "positive" value: 5}
    (is (= {:status "positive" :value 5}
           (eval-dt-last
            "wrap is [val ->"
            "  | val > 0 -> {status: \"positive\" value: val}"
            "  | _       -> {status: \"non-positive\" value: 0}"
            "]"
            "wrap 5"))))

  (testing "Guard result is a list"
    ;; Scenario: Guard result is a list
    ;; nil -> [], 42 -> [42]
    (let [to-list-fn (str "to-list is [val ->\n"
                          "  | nil -> []\n"
                          "  | _   -> [val]\n"
                          "]")]
      (is (= [] (eval-dt-last to-list-fn "to-list nil")))
      (is (= [42] (eval-dt-last to-list-fn "to-list 42")))))

  (testing "Nested guards are not allowed (parse error)"
    ;; Scenario: Nested guards are not allowed (parse error)
    (is (parse-error?
         (str "result is\n"
              "  | x > 5 ->\n"
              "    | x > 10 -> \"very big\"\n"
              "    | _      -> \"big\"\n"
              "  | _ -> \"small\""))))

  (testing "Pattern matching with rest binding in list"
    ;; Scenario: Pattern matching with rest binding in list
    ;; [1 2 3 4 5] -> "many"
    (is (= "many"
           (eval-dt-last
            "len-class is [xs ->"
            "  | []          -> \"empty\""
            "  | [_]         -> \"one\""
            "  | [_ _]       -> \"two\""
            "  | [_ _ & _]   -> \"many\""
            "]"
            "len-class [1 2 3 4 5]"))))

  (testing "Structural match with literal value in object"
    ;; Scenario: Structural match with literal value in object
    ;; {status: 404 body: ""} -> "not-found"
    ;; {status: 503 body: "unavailable"} -> "server-error"
    (let [check-fn (str "check-status is [resp ->\n"
                        "  | {status: 200}          -> \"ok\"\n"
                        "  | {status: 404}          -> \"not-found\"\n"
                        "  | {status: s} when s >= 500 -> \"server-error\"\n"
                        "  | _                      -> \"other\"\n"
                        "]")]
      (is (= "not-found"
             (eval-dt-last check-fn "check-status {status: 404 body: \"\"}")))
      (is (= "server-error"
             (eval-dt-last check-fn "check-status {status: 503 body: \"unavailable\"}"))))))
