(ns datatwist.interop-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error?
                                            throws? throws-type? type-of]]))

;; ==========================================================================
;; Feature 7: Clojure Interop, Comments, Try-Catch, Nil Semantics, Misc
;; BDD Source: bdd/7-interop-misc.feature
;; ==========================================================================

;; --------------------------------------------------------------------------
;; SECTION 1: COMMENTS
;; --------------------------------------------------------------------------

(deftest comment-at-top-of-file
  (testing "Scenario: Single-line comment at top of file"
    (is (= 42 (eval-dt-last "// This is a comment" "x is 42" "x")))))

(deftest comment-after-code-on-same-line
  (testing "Scenario: Single-line comment after code on same line"
    (is (= 42 (eval-dt-last "x is 42 // the answer" "x")))))

(deftest comment-between-pipeline-steps
  (testing "Scenario: Comment between pipeline steps"
    (is (= [2 4] (eval-dt-last
                  "items is [1 2 3 4 5]"
                  "result is items\n  // only evens\n  |> filter [n -> n % 2 = 0]"
                  "result")))))

(deftest multiple-consecutive-comment-lines
  (testing "Scenario: Multiple consecutive comment lines"
    (is (= [1 2 3] (eval-dt-last
                    "// Section: data processing\n// Author: team\n// Date: 2026-01-15\ndata is [1 2 3]"
                    "data")))))

(deftest comment-inside-object-literal
  (testing "Scenario: Comment on otherwise blank line inside object literal"
    (is (= "Alice" (eval-dt-last
                    "user is {\n  name: \"Alice\"\n  // age will be added later\n  status: \"active\"\n}"
                    "user.name")))
    (is (= "active" (eval-dt-last
                     "user is {\n  name: \"Alice\"\n  // age will be added later\n  status: \"active\"\n}"
                     "user.status")))))

(deftest comment-inside-list-literal
  (testing "Scenario: Comment inside list literal"
    (is (= [1 2 3] (eval-dt-last
                    "items is [\n  1\n  // middle values\n  2\n  3\n]"
                    "items")))))

(deftest only-comment-produces-no-form
  (testing "Scenario: A line containing only a comment produces no top-level form"
    (is (nil? (eval-dt "// just a comment, nothing else")))))

(deftest double-slash-inside-string-is-not-comment
  (testing "Scenario: Double-slash inside a string literal is not treated as a comment"
    (is (= "https://example.com"
           (eval-dt-last "url is \"https://example.com\"" "url")))))

;; --------------------------------------------------------------------------
;; SECTION 2: CLOJURE INTEROP
;; --------------------------------------------------------------------------

;; --- 2A: Direct qualified Clojure function calls ---

(deftest call-qualified-clojure-function-directly
  (testing "Scenario: Call a qualified Clojure function directly"
    (is (= "HELLO" (eval-dt-last "result is clojure.string/upper-case \"hello\"" "result")))))

(deftest qualified-clojure-function-in-pipeline
  (testing "Scenario: Qualified Clojure function in a pipeline"
    (is (= "HELLO" (eval-dt-last
                    "result is \"hello\"\n  |> clojure.string/upper-case"
                    "result")))))

(deftest qualified-clojure-function-with-multiple-arguments
  (testing "Scenario: Qualified Clojure function with multiple arguments"
    (is (= true (eval-dt-last
                 "result is clojure.string/starts-with? \"hello world\" \"hello\""
                 "result")))))

;; --- 2B: require with alias ---

(deftest require-clojure-namespace-with-alias
  (testing "Scenario: Require a Clojure namespace with alias"
    (is (= "HELLO" (eval-dt-last
                    "require clojure.string as str"
                    "result is str/upper-case \"hello\""
                    "result")))))

(deftest aliased-namespace-function-with-multiple-arguments
  (testing "Scenario: Use aliased namespace function with multiple arguments"
    (is (= "a, b, c" (eval-dt-last
                      "require clojure.string as str"
                      "result is str/join \", \" [\"a\" \"b\" \"c\"]"
                      "result")))))

(deftest use-aliased-namespace-in-pipeline
  (testing "Scenario: Use aliased namespace in pipeline"
    (is (= "HELLO WORLD" (eval-dt-last
                          "require clojure.string as str"
                          "result is \"hello world\"\n  |> str/upper-case"
                          "result")))))

(deftest require-must-appear-before-other-code
  (testing "Scenario: Require must appear before other code"
    (is (= "ALICE" (eval-dt-last
                    "require clojure.string as str"
                    "name is str/upper-case \"alice\""
                    "name")))))

(deftest require-after-code-is-parse-error
  (testing "Scenario: Require after code is a parse error"
    (is (parse-error? "x is 42\nrequire clojure.string as str"))))

;; --- 2C: Java interop ---

(deftest call-java-instance-method
  (testing "Scenario: Call a Java instance method"
    (is (= "HELLO" (eval-dt-last "result is .toUpperCase \"hello\"" "result")))))

(deftest java-instance-method-in-pipeline
  (testing "Scenario: Java instance method in pipeline"
    (is (= "HELLO" (eval-dt-last
                    "result is \"hello\"\n  |> .toUpperCase"
                    "result")))))

(deftest java-instance-method-with-argument
  (testing "Scenario: Java instance method with argument"
    (is (= true (eval-dt-last "result is .contains \"hello world\" \"world\"" "result")))))

(deftest java-static-method-call
  (testing "Scenario: Java static method call"
    (is (= 1024.0 (eval-dt-last "result is Math/pow 2 10" "result")))))

(deftest java-static-field-access
  (testing "Scenario: Java static field access"
    (let [pi (eval-dt-last "pi is Math/PI" "pi")]
      (is (< (Math/abs (- pi Math/PI)) 0.0001)))))

(deftest java-constructor-via-dot-suffix
  (testing "Scenario: Java constructor via dot suffix"
    (is (instance? java.lang.StringBuilder
                   (eval-dt-last "sb is java.lang.StringBuilder." "sb")))))

;; --- 2D: Keywords and collection interop ---

(deftest explicit-keyword-used-with-get
  (testing "Scenario: Explicit keyword literal used with get"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 25}"
                    "result is get user :name"
                    "result")))))

(deftest object-keys-are-keywords-under-the-hood
  (testing "Scenario: Object keys are keywords under the hood"
    (is (= "Alice" (eval-dt-last
                    "user is {name: \"Alice\" age: 25}"
                    "result is get user :name"
                    "result")))))

(deftest datatlist-is-clojure-vector-at-runtime
  (testing "Scenario: DataTwist list is a Clojure vector at runtime"
    (is (= clojure.lang.PersistentVector
           (type-of "[1 2 3]")))))

(deftest dataobject-is-clojure-map-at-runtime
  (testing "Scenario: DataTwist object is a Clojure persistent map at runtime"
    (is (= clojure.lang.PersistentArrayMap
           (type-of "{name: \"Alice\"}")))))

(deftest pass-datatlist-to-clojure-function
  (testing "Scenario: Pass DataTwist list directly to Clojure function"
    (is (= "hello world" (eval-dt-last
                          "require clojure.string as str"
                          "words is [\"hello\" \"world\"]"
                          "result is str/join \" \" words"
                          "result")))))

;; --------------------------------------------------------------------------
;; SECTION 3: TRY-CATCH
;; --------------------------------------------------------------------------

(deftest try-catch-returns-handler-value-on-exception
  (testing "Scenario: Simple try-catch returns handler value on exception"
    (is (= "caught" (eval-dt-last
                     "result is try\n  clojure.lang.RT/nth [] 99\ncatch err -> \"caught\""
                     "result")))))

(deftest try-catch-returns-body-value-when-no-exception
  (testing "Scenario: Try-catch returns try-body value when no exception"
    (is (= 42 (eval-dt "try 42 catch err -> -1")))))

(deftest try-catch-error-message-access
  (testing "Scenario: Try-catch with error message access via .message"
    (let [result (eval-dt-last
                  "result is try\n  clojure.lang.RT/nth [] 99\ncatch err ->\n  err.message"
                  "result")]
      (is (string? result))
      (is (some? result)))))

(deftest try-catch-with-specific-exception-type
  (testing "Scenario: Try-catch with specific exception type"
    (is (= "not found" (eval-dt-last
                        "result is try\n  java.lang.Integer/parseInt \"not-a-number\"\ncatch java.lang.NumberFormatException err ->\n  \"not found\"\ncatch err ->\n  \"unknown\""
                        "result")))))

(deftest catch-unmatched-typed-handler-falls-through-to-generic
  (testing "Scenario: Catch with unmatched typed handler falls through to generic catch"
    (is (= "fallback" (eval-dt-last
                       "result is try\n  java.lang.Integer/parseInt \"bad\"\ncatch java.io.IOException err ->\n  \"io error\"\ncatch err ->\n  \"fallback\""
                       "result")))))

(deftest try-catch-with-finally-clause
  (testing "Scenario: Try-catch with finally clause runs finally on success"
    (is (= 42 (eval-dt-last
               "side is try\n  42\ncatch err -> -1\nfinally 0"
               "side")))))

(deftest try-catch-as-expression-in-binding
  (testing "Scenario: Try-catch as expression in binding"
    (is (= 0 (eval-dt-last
              "result is try\n  java.lang.Integer/parseInt \"bad\"\ncatch err -> 0"
              "result")))))

(deftest try-catch-in-pipeline-inside-function
  (testing "Scenario: Try-catch in pipeline inside a function"
    (is (= 0 (eval-dt-last
              "safe-parse is [x -> try java.lang.Integer/parseInt x catch err -> 0]"
              "result is safe-parse \"bad\""
              "result")))))

(deftest try-catch-with-wildcard-binding-ignores-error
  (testing "Scenario: Try-catch with wildcard binding ignores error"
    (is (= -1 (eval-dt "try java.lang.Integer/parseInt \"bad\" catch _ -> -1")))))

(deftest try-with-nil-expression-does-not-throw
  (testing "Scenario: Try with nil expression does not throw"
    (is (nil? (eval-dt "try nil catch err -> \"caught\"")))))

(deftest try-catch-with-ex-data-access
  (testing "Scenario: Try-catch with ex-data access"
    (let [result (eval-dt-last
                  "result is try\n  java.lang.Integer/parseInt \"bad\"\ncatch err ->\n  {caught: true message: err.message}"
                  "result")]
      (is (= true (:caught result))))))

;; --------------------------------------------------------------------------
;; SECTION 4: NIL SEMANTICS
;; --------------------------------------------------------------------------

;; --- 4A: Nil-tolerant field access ---

(deftest field-access-on-nil-returns-nil
  (testing "Scenario: Field access on nil returns nil"
    (is (nil? (eval-dt "nil.name")))))

(deftest deep-chained-field-access-on-nil-returns-nil
  (testing "Scenario: Deep chained field access on nil returns nil"
    (is (nil? (eval-dt-last
               "user is nil"
               "result is user.profile.address.city"
               "result")))))

(deftest field-access-where-intermediate-is-nil-returns-nil
  (testing "Scenario: Field access where intermediate field is nil returns nil"
    (is (nil? (eval-dt-last
               "user is {name: \"Alice\" address: nil}"
               "result is user.address.city"
               "result")))))

(deftest field-access-on-object-with-missing-key-returns-nil
  (testing "Scenario: Field access on object with missing key returns nil"
    (is (nil? (eval-dt-last
               "user is {name: \"Alice\"}"
               "result is user.age"
               "result")))))

(deftest nil-chain-propagates-through-multiple-accesses
  (testing "Scenario: Nil chain propagates through multiple accesses"
    (is (nil? (eval-dt-last "result is nil.a.b.c" "result")))))

;; --- 4B: Nil in arithmetic ---

(deftest nil-plus-number-coerces-to-zero
  (testing "Scenario: Nil plus number coerces nil to zero"
    (is (= 5 (eval-dt "nil + 5")))))

(deftest nil-plus-string-coerces-to-empty-string
  (testing "Scenario: Nil plus string coerces nil to empty string"
    (is (= "hi" (eval-dt "nil + \"hi\"")))))

(deftest nil-times-number-coerces-to-zero
  (testing "Scenario: Nil times number coerces nil to zero"
    (is (= 0 (eval-dt "nil * 3")))))

(deftest nil-chain-into-arithmetic-via-nil-coercion
  (testing "Scenario: Nil chain into arithmetic via nil coercion"
    (is (= 1 (eval-dt-last "result is nil.name + 1" "result")))))

;; --- 4C: Nil in comparison ---

(deftest nil-equals-nil
  (testing "Scenario: Nil equals nil"
    (is (= true (eval-dt "nil = nil")))))

(deftest nil-not-equals-a-value
  (testing "Scenario: Nil not-equals a value"
    (is (= true (eval-dt "nil != 5")))))

(deftest value-not-equals-nil
  (testing "Scenario: Value not-equals nil"
    (is (= true (eval-dt "5 != nil")))))

(deftest value-equals-nil-is-false
  (testing "Scenario: Value equals nil is false"
    (is (= false (eval-dt "5 = nil")))))

(deftest nil-greater-than-value-is-nil
  (testing "Scenario: Nil greater-than a value — three-valued, nil (unknown)"
    (is (nil? (eval-dt "nil > 5")))))

(deftest nil-less-than-value-is-nil
  (testing "Scenario: Nil less-than a value — three-valued, nil (unknown)"
    (is (nil? (eval-dt "nil < 5")))))

(deftest nil-greater-or-equal-is-nil
  (testing "Scenario: Nil greater-or-equal — three-valued, nil (unknown)"
    (is (nil? (eval-dt "nil >= 0")))))

(deftest nil-less-or-equal-is-nil
  (testing "Scenario: Nil less-or-equal — three-valued, nil (unknown)"
    (is (nil? (eval-dt "nil <= 0")))))

;; --- 4D: Nil in logical operators ---

(deftest nil-is-falsy-in-conditional-context
  (testing "Scenario: Nil is falsy in conditional context"
    (is (= "falsy" (eval-dt-last
                    "result is\n  | nil -> \"truthy\"\n  | _   -> \"falsy\""
                    "result")))))

(deftest nil-and-true-returns-nil
  (testing "Scenario: Nil and true returns nil (short-circuit)"
    (is (nil? (eval-dt "nil and true")))))

(deftest true-and-nil-returns-nil
  (testing "Scenario: True and nil returns nil"
    (is (nil? (eval-dt "true and nil")))))

(deftest nil-or-true-returns-true
  (testing "Scenario: Nil or true returns true"
    (is (= true (eval-dt "nil or true")))))

(deftest nil-or-false-returns-false
  (testing "Scenario: Nil or false returns false"
    (is (= false (eval-dt "nil or false")))))

(deftest nil-or-nil-returns-nil
  (testing "Scenario: Nil or nil returns nil"
    (is (nil? (eval-dt "nil or nil")))))

(deftest false-is-distinct-from-nil
  (testing "Scenario: False is distinct from nil"
    (is (= false (eval-dt "false = nil")))))

;; --- 4E: Truthiness ---

(deftest zero-is-truthy
  (testing "Scenario: Zero is truthy"
    (is (= "truthy" (eval-dt-last
                     "result is\n  | 0 -> \"truthy\"\n  | _ -> \"falsy\""
                     "result")))))

(deftest empty-string-is-truthy
  (testing "Scenario: Empty string is truthy"
    (is (= "truthy" (eval-dt-last
                     "result is\n  | \"\" -> \"truthy\"\n  | _  -> \"falsy\""
                     "result")))))

(deftest empty-list-is-truthy
  (testing "Scenario: Empty list is truthy"
    (is (= "truthy" (eval-dt-last
                     "result is\n  | [] -> \"truthy\"\n  | _  -> \"falsy\""
                     "result")))))

(deftest empty-object-is-truthy
  (testing "Scenario: Empty object is truthy"
    (is (= "truthy" (eval-dt-last
                     "result is\n  | {} -> \"truthy\"\n  | _  -> \"falsy\""
                     "result")))))

;; --- 4F: Nil coalescing operator `??` ---

(deftest nil-coalescing-with-nil-left-returns-default
  (testing "Scenario: Nil coalescing with nil left side returns default"
    (is (= "anonymous" (eval-dt-last
                        "name is nil ?? \"anonymous\""
                        "name")))))

(deftest nil-coalescing-with-non-nil-left-returns-left
  (testing "Scenario: Nil coalescing with non-nil left side returns left side"
    (is (= "Alice" (eval-dt-last
                    "name is \"Alice\" ?? \"anonymous\""
                    "name")))))

(deftest nil-coalescing-does-not-trigger-on-false
  (testing "Scenario: Nil coalescing does not trigger on false"
    (is (= false (eval-dt-last
                  "flag is false ?? true"
                  "flag")))))

(deftest nil-coalescing-does-not-trigger-on-zero
  (testing "Scenario: Nil coalescing does not trigger on zero"
    (is (= 0 (eval-dt-last
              "n is 0 ?? 42"
              "n")))))

(deftest nil-coalescing-does-not-trigger-on-empty-string
  (testing "Scenario: Nil coalescing does not trigger on empty string"
    (is (= "" (eval-dt-last
               "s is \"\" ?? \"default\""
               "s")))))

(deftest nil-coalescing-chains-through-multiple-nils
  (testing "Scenario: Nil coalescing chains through multiple nils"
    (is (= "fallback" (eval-dt-last
                       "result is nil ?? nil ?? \"fallback\""
                       "result")))))

(deftest nil-coalescing-chain-all-nil-returns-nil
  (testing "Scenario: Nil coalescing chain all nil returns nil"
    (is (nil? (eval-dt-last
               "result is nil ?? nil ?? nil"
               "result")))))

(deftest nil-coalescing-with-nil-field-access
  (testing "Scenario: Nil coalescing with nil field access"
    (is (= "unknown" (eval-dt-last
                      "user is {name: nil}"
                      "display is user.name ?? \"unknown\""
                      "display")))))

;; --- 4G: Nil in pipelines ---

(deftest nil-piped-into-filter-returns-empty-collection
  (testing "Scenario: Nil piped into filter returns empty collection"
    (is (= [] (eval-dt-last
               "result is nil |> filter _.active"
               "result")))))

(deftest nil-piped-into-map-returns-empty-collection
  (testing "Scenario: Nil piped into map returns empty collection"
    (is (= [] (eval-dt-last
               "result is nil |> map _.name"
               "result")))))

(deftest nil-piped-into-count-returns-zero
  (testing "Scenario: Nil piped into nil-aware function"
    (is (= 0 (eval-dt-last
              "result is nil |> count"
              "result")))))

;; --- 4H: Empty collection vs nil ---

(deftest empty-list-is-not-nil
  (testing "Scenario: Empty list is not nil"
    (is (= false (eval-dt "[] = nil")))))

(deftest nil-coalescing-does-not-trigger-on-empty-list
  (testing "Scenario: Nil coalescing does not trigger on empty list"
    (is (= [] (eval-dt-last
               "items is []"
               "result is items ?? [1 2 3]"
               "result")))))

(deftest nil-coalescing-does-not-trigger-on-empty-object
  (testing "Scenario: Nil coalescing does not trigger on empty object"
    (is (= {} (eval-dt-last
               "data is {}"
               "result is data ?? {default: true}"
               "result")))))

;; --------------------------------------------------------------------------
;; SECTION 5: FORMAT FUNCTION
;; --------------------------------------------------------------------------

(deftest format-with-string-substitution
  (testing "Scenario: Format with string substitution"
    (is (= "Hello, Alice!" (eval-dt-last
                            "result is format \"Hello, %s!\" \"Alice\""
                            "result")))))

(deftest format-with-integer-substitution
  (testing "Scenario: Format with integer substitution"
    (is (= "age: 25" (eval-dt-last
                      "result is format \"age: %d\" 25"
                      "result")))))

(deftest format-with-float-precision
  (testing "Scenario: Format with float precision"
    (is (= "Price: $19.99" (eval-dt-last
                            "result is format \"Price: $%.2f\" 19.99"
                            "result")))))

(deftest format-with-multiple-arguments
  (testing "Scenario: Format with multiple arguments"
    (is (= "Alice is 30 years old"
           (eval-dt-last
            "name is \"Alice\""
            "age is 30"
            "result is format \"%s is %d years old\" name age"
            "result")))))

(deftest format-with-nil-argument-produces-null-string
  (testing "Scenario: Format with nil argument produces \"null\" string"
    (is (= "value: null" (eval-dt-last
                          "result is format \"value: %s\" nil"
                          "result")))))

(deftest format-in-a-function-body
  (testing "Scenario: Format in a function body"
    (is (= "Hello, Bob!" (eval-dt-last
                          "greet is [name -> format \"Hello, %s!\" name]"
                          "result is greet \"Bob\""
                          "result")))))

;; --------------------------------------------------------------------------
;; SECTION 6: PROGRAM STRUCTURE
;; --------------------------------------------------------------------------

(deftest multiple-top-level-bindings-evaluated-in-sequence
  (testing "Scenario: Multiple top-level bindings evaluated in sequence"
    (is (= 3 (eval-dt-last "x is 1" "y is 2" "z is x + y" "z")))))

(deftest blank-lines-between-top-level-forms-are-allowed
  (testing "Scenario: Blank lines between top-level forms are allowed"
    (is (= 3 (eval-dt "x is 1\n\ny is 2\n\nz is x + y\nz")))))

(deftest last-expression-is-program-result
  (testing "Scenario: Last expression is the program result"
    (is (= 2 (eval-dt "data is [1 2 3 4 5]\ndata |> filter [x -> x > 3] |> count")))))

(deftest file-with-only-expression-returns-its-value
  (testing "Scenario: File with only an expression returns its value"
    (is (= 6 (eval-dt "1 + 2 + 3")))))

(deftest function-body-with-internal-bindings-returns-final-expression
  (testing "Scenario: Function body with internal bindings returns final expression"
    (is (= 11 (eval-dt-last
               "process is [data ->\n  doubled is data * 2\n  doubled + 1\n]"
               "result is process 5"
               "result")))))

;; --------------------------------------------------------------------------
;; SECTION 7: INTEGRATION SCENARIOS
;; --------------------------------------------------------------------------

(deftest interop-with-nil-coalescing-and-format
  (testing "Scenario: Interop with nil coalescing and format"
    (is (= "Hello, guest!" (eval-dt-last
                            "require clojure.string as str"
                            "name is nil"
                            "display is format \"Hello, %s!\" (name ?? \"guest\")"
                            "display")))))

(deftest try-catch-combined-with-nil-coalescing
  (testing "Scenario: Try-catch combined with nil coalescing"
    (is (= -1 (eval-dt-last
               "result is try\n  java.lang.Integer/parseInt \"bad\"\ncatch err -> nil"
               "safe is result ?? -1"
               "safe")))))

(deftest qualified-clojure-call-combined-with-nil-coalescing
  (testing "Scenario: Qualified Clojure call combined with nil coalescing"
    (is (= "" (eval-dt-last
               "input is nil"
               "result is (input ?? \"\") |> clojure.string/upper-case"
               "result")))))

(deftest java-interop-inside-pipeline-function
  (testing "Scenario: Java interop inside a pipeline function"
    (is (= ["HELLO" "WORLD"]
           (eval-dt-last
            "result is [\"hello\" \"world\"]\n  |> map [s -> .toUpperCase s]"
            "result")))))

;; ---------------------------------------------------------------------------
;; Section 4I: Nil Handling Functions (fill-nil, skip-nil)
;; ---------------------------------------------------------------------------

(deftest fill-nil-replaces-nil-elements-in-list
  (testing "Scenario: fill-nil replaces nil elements in a list with a default"
    (is (= [1 0 3 0 5] (eval-dt "[1 nil 3 nil 5] |> fill-nil 0")))))

(deftest fill-nil-on-scalar-nil-returns-default
  (testing "Scenario: fill-nil on a scalar nil returns the default"
    (is (= 0 (eval-dt "nil |> fill-nil 0")))))

(deftest fill-nil-on-map-replaces-nil-valued-keys
  (testing "Scenario: fill-nil on an object replaces nil-valued fields"
    (is (= {:a 1 :b 0 :c 3}
           (eval-dt "{a: 1  b: nil  c: 3} |> fill-nil 0")))))

(deftest fill-nil-on-list-with-no-nils-returns-unchanged
  (testing "Scenario: fill-nil on a list with no nils returns the list unchanged"
    (is (= [1 2 3] (eval-dt "[1 2 3] |> fill-nil 0")))))

(deftest skip-nil-removes-nil-elements-from-list
  (testing "Scenario: skip-nil removes nil elements from a list"
    (is (= [1 3 5] (eval-dt "[1 nil 3 nil 5] |> skip-nil")))))

(deftest skip-nil-on-empty-list-returns-empty
  (testing "Scenario: skip-nil on an empty list returns empty list"
    (is (= [] (eval-dt "[] |> skip-nil")))))

(deftest skip-nil-on-nil-source-returns-empty
  (testing "Scenario: skip-nil on a nil source returns empty list"
    (is (= [] (eval-dt "nil |> skip-nil")))))

(deftest skip-nil-on-map-removes-nil-valued-keys
  (testing "Scenario: skip-nil on an object removes keys with nil values"
    (is (= {:a 1 :c 3}
           (eval-dt "{a: 1  b: nil  c: 3} |> skip-nil")))))

(deftest skip-nil-in-pipeline-chain
  (testing "Scenario: skip-nil used in a pipeline to clean data before sum"
    (is (= 6 (eval-dt "[1 nil 2 nil 3] |> skip-nil |> sum")))))
