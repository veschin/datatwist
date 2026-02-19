(ns datatwist.literals-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error?
                                            type-of throws? throws-type?]]))

;; ==========================================================================
;; SECTION 1: Integer Literals
;; ==========================================================================

(deftest integer-literals
  (testing "Integer literal - simple positive"
    (is (= 42 (eval-dt "42")))
    (is (= java.lang.Long (type-of "42"))))

  (testing "Integer literal - zero"
    (is (= 0 (eval-dt "0")))
    (is (= java.lang.Long (type-of "0"))))

  (testing "Integer literal - large number within Long range"
    (is (= 9223372036854775807 (eval-dt "9223372036854775807")))
    (is (= java.lang.Long (type-of "9223372036854775807"))))

  (testing "Integer literal - overflow beyond Long range promotes to BigInt"
    (is (= 9223372036854775808N (eval-dt "9223372036854775808")))
    (is (= clojure.lang.BigInt (type-of "9223372036854775808"))))

  (testing "Negative integer literal - unary minus attached"
    (is (= -10 (eval-dt "-10")))
    (is (= java.lang.Long (type-of "-10"))))

  (testing "Negative integer literal - unary minus with space is parse error"
    (is (parse-error? "- 10")))

  (testing "Negative integer literal - double negative is parse error"
    (is (parse-error? "--5"))))

;; ==========================================================================
;; SECTION 2: Float Literals
;; ==========================================================================

(deftest float-literals
  (testing "Float literal - simple decimal"
    (is (= 3.14 (eval-dt "3.14")))
    (is (= java.lang.Double (type-of "3.14"))))

  (testing "Float literal - zero point something"
    (is (= 0.5 (eval-dt "0.5")))
    (is (= java.lang.Double (type-of "0.5"))))

  (testing "Float literal - leading dot is NOT valid"
    (is (parse-error? ".5")))

  (testing "Float literal - trailing dot is NOT valid"
    (is (parse-error? "5.")))

  (testing "Float literal - negative"
    (is (= -0.001 (eval-dt "-0.001")))
    (is (= java.lang.Double (type-of "-0.001"))))

  (testing "Scientific notation is NOT supported in v1 - parses as identifier"
    ;; 1e10 should parse as an identifier, not a number.
    ;; Assigning it a value and reading it back confirms identifier behavior.
    (is (= 42 (eval-dt-last "1e10 is 42" "1e10"))))

  (testing "Underscore separators in numbers are NOT supported in v1"
    ;; 1_000_000 starts with a digit so it's not a valid identifier; this is a parse error.
    (is (parse-error? "1_000_000 is 99"))))

;; ==========================================================================
;; SECTION 3: String Literals
;; ==========================================================================

(deftest string-literals
  (testing "String literal - simple"
    (is (= "hello world" (eval-dt "\"hello world\"")))
    (is (= java.lang.String (type-of "\"hello world\""))))

  (testing "String literal - empty string"
    (is (= "" (eval-dt "\"\"")))
    (is (= java.lang.String (type-of "\"\""))))

  (testing "String literal - with special characters (escape sequences)"
    (let [result (eval-dt "\"line1\\nline2\"")]
      (is (string? result))
      (is (.contains ^String result "\n"))))

  (testing "String literal - with unicode"
    (is (= "hello" (eval-dt "\"hello\""))))

  (testing "String literal - no interpolation"
    ;; ${name} is treated as literal text, not interpolated
    (is (= "Hello ${name}" (eval-dt "\"Hello ${name}\""))))

  (testing "String literal - unclosed string is a parse error"
    (is (parse-error? "\"hello")))

  (testing "String literal - multiline strings via escape"
    (let [result (eval-dt "\"hello\\nworld\"")]
      (is (string? result))
      (is (.contains ^String result "\n")))))

;; ==========================================================================
;; SECTION 4: Boolean Literals
;; ==========================================================================

(deftest boolean-literals
  (testing "Boolean true"
    (is (= true (eval-dt "true")))
    (is (= java.lang.Boolean (type-of "true"))))

  (testing "Boolean false"
    (is (= false (eval-dt "false")))
    (is (= java.lang.Boolean (type-of "false"))))

  (testing "Boolean keywords are reserved - cannot assign to true"
    (is (parse-error? "true is 5"))))

;; ==========================================================================
;; SECTION 5: Nil Literal
;; ==========================================================================

(deftest nil-literal
  (testing "Nil literal"
    (is (nil? (eval-dt "nil"))))

  (testing "Nil is a reserved word - cannot assign to nil"
    (is (parse-error? "nil is 42"))))

;; ==========================================================================
;; SECTION 6: Arithmetic Operators
;; ==========================================================================

(deftest arithmetic-operators
  (testing "Addition of two integers"
    (is (= 5 (eval-dt "2 + 3")))
    (is (= java.lang.Long (type-of "2 + 3"))))

  (testing "Addition of integer and float promotes to float"
    (is (= 5.0 (eval-dt "2 + 3.0")))
    (is (= java.lang.Double (type-of "2 + 3.0"))))

  (testing "Subtraction"
    (is (= 7 (eval-dt "10 - 3"))))

  (testing "Multiplication"
    (is (= 20 (eval-dt "4 * 5"))))

  (testing "Division of two integers - always produces Double"
    (is (= 2.5 (eval-dt "5 / 2"))))

  (testing "Division of floats"
    (is (= 3.5 (eval-dt "7.0 / 2.0"))))

  (testing "Division by zero - integer throws ArithmeticException"
    (is (throws-type? "5 / 0" ArithmeticException)))

  (testing "Division by zero - float returns Infinity"
    (is (= Double/POSITIVE_INFINITY (eval-dt "5.0 / 0.0"))))

  (testing "Modulo operator"
    (is (= 1 (eval-dt "10 % 3"))))

  (testing "Modulo with negative dividend - mathematical modulo"
    ;; Clojure (mod -10 3) => 2, not -1
    (is (= 2 (eval-dt "-10 % 3"))))

  (testing "Modulo by zero throws ArithmeticException"
    (is (throws-type? "10 % 0" ArithmeticException)))

  (testing "String concatenation with + operator"
    (is (= "hello world" (eval-dt "\"hello\" + \" \" + \"world\""))))

  (testing "String repetition is NOT supported with *"
    (is (throws? "\"ha\" * 3"))))

;; ==========================================================================
;; SECTION 7: Nil Tolerance in Arithmetic
;; ==========================================================================

(deftest nil-tolerance-arithmetic
  (testing "nil + integer"
    (is (= 5 (eval-dt "nil + 5"))))

  (testing "integer + nil"
    (is (= 5 (eval-dt "5 + nil"))))

  (testing "nil * integer"
    (is (= 0 (eval-dt "nil * 5"))))

  (testing "nil / integer"
    (is (= 0.0 (eval-dt "nil / 5"))))

  (testing "nil % integer"
    (is (= 0 (eval-dt "nil % 3"))))

  (testing "nil - nil"
    (is (= 0 (eval-dt "nil - nil"))))

  (testing "Nil in chained arithmetic"
    (is (= 4 (eval-dt "1 + nil + 3")))))

;; ==========================================================================
;; SECTION 8: Comparison Operators
;; ==========================================================================

(deftest comparison-operators
  (testing "Equality - same integers"
    (is (= true (eval-dt "5 = 5"))))

  (testing "Equality - different integers"
    (is (= false (eval-dt "5 = 6"))))

  (testing "Equality - integer and float with same value"
    ;; Uses numeric equality (==), not type-strict (=)
    (is (= true (eval-dt "5 = 5.0"))))

  (testing "Equality - strings"
    (is (= true (eval-dt "\"hello\" = \"hello\""))))

  (testing "Equality - different types"
    ;; No implicit type coercion for equality
    (is (= false (eval-dt "\"5\" = 5"))))

  (testing "Equality - nil = nil"
    (is (= true (eval-dt "nil = nil"))))

  (testing "Equality - nil = 0"
    (is (= false (eval-dt "nil = 0"))))

  (testing "Equality - nil = false"
    (is (= false (eval-dt "nil = false"))))

  (testing "Equality - nil = empty string"
    (is (= false (eval-dt "nil = \"\""))))

  (testing "Inequality"
    (is (= true (eval-dt "5 != 3"))))

  (testing "Inequality - nil != nil"
    (is (= false (eval-dt "nil != nil"))))

  (testing "Greater than - integers"
    (is (= true (eval-dt "5 > 3"))))

  (testing "Greater than or equal"
    (is (= true (eval-dt "5 >= 5"))))

  (testing "Less than"
    (is (= true (eval-dt "3 < 5"))))

  (testing "Less than or equal"
    (is (= true (eval-dt "5 <= 5"))))

  (testing "Comparison - integer vs float"
    (is (= true (eval-dt "5 > 4.9"))))

  (testing "String comparison - lexicographic"
    (is (= true (eval-dt "\"apple\" < \"banana\""))))

  (testing "String comparison - case sensitive"
    ;; Uppercase sorts before lowercase in Unicode/ASCII
    (is (= true (eval-dt "\"Apple\" < \"apple\""))))

  (testing "Comparison with nil - nil coerces to 0"
    (is (= true (eval-dt "5 > nil"))))

  (testing "nil > nil — both coerce to 0, 0 > 0 is false"
    (is (= false (eval-dt "nil > nil"))))

  (testing "Comparison between incompatible types throws type error"
    (is (throws? "\"hello\" > 5"))))

;; ==========================================================================
;; SECTION 9: Logical Operators
;; ==========================================================================

(deftest logical-operators
  (testing "Logical and - both true"
    (is (= true (eval-dt "true and true"))))

  (testing "Logical and - one false"
    (is (= false (eval-dt "true and false"))))

  (testing "Logical or - both false"
    (is (= false (eval-dt "false or false"))))

  (testing "Logical or - one true"
    (is (= true (eval-dt "false or true"))))

  (testing "Logical not - true"
    (is (= false (eval-dt "not true"))))

  (testing "Logical not - false"
    (is (= true (eval-dt "not false"))))

  (testing "Logical not with expression"
    (is (= false (eval-dt "not (5 > 3)"))))

  (testing "Logical not without parentheses"
    ;; not has lower precedence than comparison: not (5 > 3) => false
    (is (= false (eval-dt "not 5 > 3"))))

  (testing "Logical not with field access"
    ;; not _.active negates the active field
    (is (= true (eval-dt "{active: false} |> not _.active")))
    (is (= false (eval-dt "{active: true} |> not _.active"))))

  (testing "Short-circuit evaluation - and"
    ;; false and (10 / 0 > 1) should NOT evaluate the right side
    (is (= false (eval-dt "false and (10 / 0 > 1)"))))

  (testing "Short-circuit evaluation - or"
    ;; true or (10 / 0 > 1) should NOT evaluate the right side
    (is (= true (eval-dt "true or (10 / 0 > 1)"))))

  (testing "Logical and with nil - nil is falsy"
    ;; Clojure: (and true nil) => nil
    (is (nil? (eval-dt "true and nil"))))

  (testing "Logical or with nil - nil is falsy"
    ;; Clojure: (or nil 5) => 5
    (is (= 5 (eval-dt "nil or 5"))))

  (testing "Logical and returns actual values, not just booleans"
    ;; Clojure: (and 1 2) => 2
    (is (= 2 (eval-dt "1 and 2"))))

  (testing "Logical or returns actual values, not just booleans"
    ;; nil or 0 => 0 (0 is truthy in Clojure), short-circuits here.
    ;; The BDD Then line says 42 but the BDD's own analysis corrects to 0,
    ;; confirmed by the "Zero is truthy" scenario that follows.
    (is (= 0 (eval-dt "nil or 0 or false or 42"))))

  (testing "Zero is truthy"
    ;; 0 is truthy in Clojure; or returns the first truthy value
    (is (= 0 (eval-dt "0 or 42"))))

  (testing "Empty string is truthy"
    ;; "" is truthy in Clojure
    (is (= "" (eval-dt "\"\" or \"default\""))))

  (testing "Logical not with nil"
    ;; (not nil) => true
    (is (= true (eval-dt "not nil"))))

  (testing "Logical not with zero"
    ;; (not 0) => false, because 0 is truthy
    (is (= false (eval-dt "not 0"))))

  (testing "Complex logical expression"
    ;; and binds tighter than or: (true and false) or true => false or true => true
    (is (= true (eval-dt "true and false or true"))))

  (testing "Complex logical with not"
    ;; not > and > or: (not true) or false => false or false => false
    (is (= false (eval-dt "not true or false")))))

;; ==========================================================================
;; SECTION 10: Operator Precedence
;; ==========================================================================

(deftest operator-precedence
  (testing "Standard math precedence - multiplication before addition"
    ;; 2 + (3 * 4) = 14
    (is (= 14 (eval-dt "2 + 3 * 4"))))

  (testing "Standard math precedence - division before subtraction"
    ;; 10 - (6 / 2) = 10 - 3.0 = 7.0
    (is (= 7.0 (eval-dt "10 - 6 / 2"))))

  (testing "Parentheses override precedence"
    (is (= 20 (eval-dt "(2 + 3) * 4"))))

  (testing "Nested parentheses"
    (is (= 15 (eval-dt "((2 + 3) * (4 - 1))"))))

  (testing "Modulo precedence - same as multiplication/division"
    ;; 10 + (7 % 3) = 10 + 1 = 11
    (is (= 11 (eval-dt "10 + 7 % 3"))))

  (testing "Comparison after arithmetic"
    ;; (2 + 3) > 4 => 5 > 4 => true
    (is (= true (eval-dt "2 + 3 > 4"))))

  (testing "Logical after comparison"
    ;; (5 > 3) and (2 < 4) => true and true => true
    (is (= true (eval-dt "5 > 3 and 2 < 4"))))

  (testing "Full precedence chain"
    ;; 3 * 4 = 12, 2 + 12 = 14, 14 > 10 = true, not false = true, true and true = true
    (is (= true (eval-dt "2 + 3 * 4 > 10 and not false")))))

;; ==========================================================================
;; SECTION 11: Type Coercion Rules
;; ==========================================================================

(deftest type-coercion-rules
  (testing "No implicit coercion - string + number is an error"
    (is (throws? "\"count: \" + 5")))

  (testing "No implicit coercion - boolean to number"
    (is (throws? "true + 1")))

  (testing "Integer to float promotion in mixed arithmetic"
    (is (= 7.0 (eval-dt "5 + 2.0")))
    (is (= java.lang.Double (type-of "5 + 2.0"))))

  (testing "Float to integer is never implicit"
    (is (= 5.0 (eval-dt "5.0")))
    (is (= java.lang.Double (type-of "5.0")))))

;; ==========================================================================
;; SECTION 12: Unary Minus Edge Cases
;; ==========================================================================

(deftest unary-minus-edge-cases
  (testing "Unary minus on literal"
    (is (= -42 (eval-dt "-42"))))

  (testing "Unary minus on identifier"
    (is (= -5 (eval-dt-last "x is 5" "-x"))))

  (testing "Subtraction vs unary minus - context matters"
    (is (= 7 (eval-dt-last "x is 10" "x - 3"))))

  (testing "Unary minus in expression"
    ;; 5 * -3 = -15 (unary minus after binary operator)
    (is (= -15 (eval-dt "5 * -3"))))

  (testing "Negation of parenthesized expression"
    (is (= -7 (eval-dt "-(3 + 4)")))))

;; ==========================================================================
;; SECTION 13: The `in` Operator
;; ==========================================================================

(deftest in-operator
  (testing "in operator - value in list"
    (is (= true (eval-dt-last
                 "tags is ['premium' 'active' 'verified']"
                 "\"premium\" in tags"))))

  (testing "in operator - value not in list"
    (is (= false (eval-dt-last
                  "tags is ['a' 'b']"
                  "\"z\" in tags"))))

  (testing "in operator - value in object (checks keys)"
    (is (= true (eval-dt-last
                 "user is {name: \"Alice\" age: 25}"
                 "\"name\" in user"))))

  (testing "in operator - nil in list"
    (is (= true (eval-dt-last
                 "items is [1 nil 3]"
                 "nil in items"))))

  (testing "in operator - value in nil"
    (is (nil? (eval-dt "\"x\" in nil"))))

  (testing "in operator - precedence with not"
    ;; not ("c" in tags) => not false => true
    (is (= true (eval-dt-last
                 "tags is ['a' 'b']"
                 "not \"c\" in tags")))))

;; ==========================================================================
;; SECTION 14: Equality Operator Deep Dive
;; ==========================================================================

(deftest equality-deep-dive
  (testing "Structural equality for objects"
    (is (= true (eval-dt "{name: \"Alice\" age: 25} = {name: \"Alice\" age: 25}"))))

  (testing "Structural equality for lists"
    (is (= true (eval-dt "[1 2 3] = [1 2 3]"))))

  (testing "Object key order does not matter for equality"
    (is (= true (eval-dt "{age: 25 name: \"Alice\"} = {name: \"Alice\" age: 25}"))))

  (testing "Nested structural equality"
    (is (= true (eval-dt "{a: {b: [1 2]}} = {a: {b: [1 2]}}"))))

  (testing "List order matters for equality"
    (is (= false (eval-dt "[1 2 3] = [3 2 1]")))))

;; ==========================================================================
;; SECTION 15: Edge Cases and Miscellaneous
;; ==========================================================================

(deftest edge-cases-misc
  (testing "Chained comparisons are NOT supported"
    ;; CompExpr is non-associative: chained comparisons are a parse error.
    (is (parse-error? "1 < 2 < 3")))

  (testing "Whitespace around operators is required"
    (is (parse-error? "2+3")))

  (testing "Identifier with hyphen vs subtraction"
    ;; my-var is a single identifier; my - var would be subtraction
    (is (= 10 (eval-dt-last "my-var is 10" "my-var"))))

  (testing "Multiple operators without operands"
    (is (parse-error? "5 + + 3")))

  (testing "Empty parentheses"
    (is (parse-error? "()")))

  (testing "Division produces consistent types"
    ;; Even 10 / 5 = 2.0, always Double
    (is (= 2.0 (eval-dt "10 / 5")))
    (is (= java.lang.Double (type-of "10 / 5"))))

  (testing "Very large arithmetic does not silently overflow"
    ;; Clojure auto-promotes to BigInt on overflow
    (is (= 9223372036854775808N (eval-dt "9223372036854775807 + 1"))))

  (testing "Floating point precision"
    ;; IEEE 754: 0.1 + 0.2 is approximately 0.3 but not exactly
    (let [result (eval-dt "0.1 + 0.2")]
      (is (instance? java.lang.Double result))
      (is (< (Math/abs (- result 0.3)) 1e-10)))))

;; ==========================================================================
;; SECTION 16: Operator on Nil in Pipelines (Practical Scenarios)
;; ==========================================================================

(deftest nil-in-pipelines
  (testing "Nil-tolerant field access in comparison"
    ;; user.age is nil (field does not exist), nil coerces to 0 => 0 > 18 => false
    (is (= false (eval-dt-last
                  "user is {name: \"Alice\"}"
                  "user.age > 18"))))

  (testing "Nil-tolerant field in arithmetic"
    ;; item.price is nil => nil coerces to 0 => 0 * 1.1 = 0.0
    (is (= 0.0 (eval-dt-last
                "item is {name: \"Widget\"}"
                "item.price * 1.1"))))

  (testing "Nil-tolerant chained field access in expression"
    ;; data.user is nil, chained access returns nil, nil + 1 = 1
    (is (= 1 (eval-dt-last
              "data is {user: nil}"
              "data.user.profile.age + 1")))))

;; ==========================================================================
;; SECTION 17: Expressions as Values
;; ==========================================================================

(deftest expressions-as-values
  (testing "Expression result assigned with is"
    (is (= 14 (eval-dt-last "result is 2 + 3 * 4" "result"))))

  (testing "Comparison result assigned with is"
    (is (= true (eval-dt-last "adult is 25 > 18" "adult"))))

  (testing "Logical expression result assigned with is"
    (is (= true (eval-dt-last "valid is true and not false" "valid"))))

  (testing "Parenthesized expression assigned"
    (is (= 45 (eval-dt-last "x is (2 + 3) * (4 + 5)" "x")))))
