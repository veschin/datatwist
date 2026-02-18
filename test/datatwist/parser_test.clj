(ns datatwist.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [datatwist.parser :as parser]
            [instaparse.core :as insta]))

;; ==========================================================================
;; Parser Test Helpers
;; ==========================================================================

(defn parses?
  "Returns true if input parses successfully."
  [input]
  (not (insta/failure? (parser/parse input))))

(defn parse-fails?
  "Returns true if input fails to parse."
  [input]
  (insta/failure? (parser/parse input)))

(defn ast
  "Returns the raw parse tree."
  [input]
  (parser/parse input))

(defn simplify
  "Collapse single-child wrapper nodes in the AST.
   [:A [:B [:C x]]] -> [:C x] when A and B each have exactly one child.
   Keeps multi-child nodes intact to preserve meaningful structure.

   This relies on semantic nodes (UnaryExpr, NotExpr, FnDef, etc.) having
   their operator/keyword visible in the AST (e.g. [:UnaryExpr \"-\" [:Integer \"42\"]])
   so they have 2+ children and won't be collapsed. Pure precedence wrappers
   (OrExpr, AddExpr, etc.) with a single child are just pass-through."
  [tree]
  (if (not (vector? tree))
    tree
    (let [children (mapv simplify (rest tree))]
      (if (and (= 1 (count children))
               (vector? (first children))
               (keyword? (ffirst children)))
        (first children)
        (into [(first tree)] children)))))

(defn sast
  "Parse and simplify — returns collapsed AST for readable assertions."
  [input]
  (simplify (ast input)))

(defn ast-tag
  "Returns the tag of the outermost meaningful node (after simplification)."
  [input]
  (first (sast input)))

(defn tree-contains-tag?
  "Returns true if AST tree (vector) contains a node with the given tag."
  [tree tag]
  (when (vector? tree)
    (or (= tag (first tree))
        (some #(tree-contains-tag? % tag) (rest tree)))))

;; ==========================================================================
;; SECTION 1: Integer Literals
;; ==========================================================================

(deftest parse-integer-literals
  (testing "simple positive integer"
    (is (parses? "42"))
    (is (= [:Integer "42"] (sast "42"))))

  (testing "zero"
    (is (parses? "0"))
    (is (= [:Integer "0"] (sast "0"))))

  (testing "large number (Long max)"
    (is (parses? "9223372036854775807"))
    (is (= [:Integer "9223372036854775807"] (sast "9223372036854775807"))))

  (testing "number beyond Long range still parses as Integer token"
    (is (parses? "9223372036854775808"))
    (is (= :Integer (ast-tag "9223372036854775808"))))

  (testing "multiple digits"
    (is (parses? "12345"))
    (is (= [:Integer "12345"] (sast "12345"))))

  (testing "scientific notation is NOT a number — parses as Integer + Identifier"
    (is (parses? "1e10"))
    (is (not= :Float (ast-tag "1e10"))))

  (testing "underscore separators are NOT supported as single token"
    (is (not= [:Integer "1_000_000"] (sast "1_000_000")))))

;; ==========================================================================
;; SECTION 2: Float Literals
;; ==========================================================================

(deftest parse-float-literals
  (testing "simple decimal"
    (is (parses? "3.14"))
    (is (= [:Float "3.14"] (sast "3.14"))))

  (testing "zero point something"
    (is (parses? "0.5"))
    (is (= [:Float "0.5"] (sast "0.5"))))

  (testing "leading dot is NOT valid"
    (is (parse-fails? ".5")))

  (testing "trailing dot is NOT valid"
    (is (parse-fails? "5.")))

  (testing "negative float parses as unary minus + float"
    (is (parses? "-0.001"))
    (let [tree (sast "-0.001")]
      (is (= :UnaryExpr (first tree)))
      (is (= "-" (second tree)))
      (is (= [:Float "0.001"] (nth tree 2 nil))))))

;; ==========================================================================
;; SECTION 3: String Literals
;; ==========================================================================

(deftest parse-string-literals
  (testing "simple string"
    (is (parses? "\"hello world\""))
    (is (= [:String "hello world"] (sast "\"hello world\""))))

  (testing "empty string"
    (is (parses? "\"\""))
    (is (= [:String ""] (sast "\"\""))))

  (testing "string with escape sequences"
    (is (parses? "\"line1\\nline2\"")))

  (testing "string with escaped quotes"
    (is (parses? "\"she said \\\"hi\\\"\"")))

  (testing "unclosed string is a parse error"
    (is (parse-fails? "\"hello")))

  (testing "no string interpolation — ${} is literal text"
    (is (parses? "\"Hello ${name}\""))
    (is (= :String (ast-tag "\"Hello ${name}\"")))))

;; ==========================================================================
;; SECTION 4: Boolean Literals
;; ==========================================================================

(deftest parse-boolean-literals
  (testing "true"
    (is (parses? "true"))
    (is (= [:Boolean "true"] (sast "true"))))

  (testing "false"
    (is (parses? "false"))
    (is (= [:Boolean "false"] (sast "false"))))

  (testing "true is not parsed as identifier"
    (is (= :Boolean (ast-tag "true")))
    (is (not= :Identifier (ast-tag "true"))))

  (testing "false is not parsed as identifier"
    (is (= :Boolean (ast-tag "false")))
    (is (not= :Identifier (ast-tag "false")))))

;; ==========================================================================
;; SECTION 5: Nil Literal
;; ==========================================================================

(deftest parse-nil-literal
  (testing "nil"
    (is (parses? "nil"))
    (is (= [:Nil "nil"] (sast "nil"))))

  (testing "nil is not parsed as identifier"
    (is (= :Nil (ast-tag "nil")))
    (is (not= :Identifier (ast-tag "nil")))))

;; ==========================================================================
;; SECTION 6: Identifiers
;; ==========================================================================

(deftest parse-identifiers
  (testing "simple identifier"
    (is (parses? "name"))
    (is (= [:Identifier "name"] (sast "name"))))

  (testing "single letter"
    (is (parses? "x"))
    (is (= [:Identifier "x"] (sast "x"))))

  (testing "hyphenated identifier"
    (is (parses? "user-name"))
    (is (= [:Identifier "user-name"] (sast "user-name"))))

  (testing "identifier with underscore"
    (is (parses? "user_name"))
    (is (= [:Identifier "user_name"] (sast "user_name"))))

  (testing "identifier with digits"
    (is (parses? "x1"))
    (is (= [:Identifier "x1"] (sast "x1")))
    (is (parses? "level2"))
    (is (= [:Identifier "level2"] (sast "level2"))))

  (testing "predicate identifier (trailing ?)"
    (is (parses? "even?"))
    (is (= [:Identifier "even?"] (sast "even?"))))

  (testing "side-effect identifier (trailing !)"
    (is (parses? "log!"))
    (is (= [:Identifier "log!"] (sast "log!"))))

  (testing "identifier cannot start with digit"
    (is (not= :Identifier (ast-tag "2fast")))))

(deftest parse-reserved-words-not-identifiers
  (testing "true is Boolean, not Identifier"
    (is (= :Boolean (ast-tag "true"))))

  (testing "false is Boolean, not Identifier"
    (is (= :Boolean (ast-tag "false"))))

  (testing "nil is Nil, not Identifier"
    (is (= :Nil (ast-tag "nil"))))

  ;; Bare reserved words without operands should fail to parse as a program
  (testing "and is not an identifier"
    (is (parse-fails? "and")))

  (testing "or is not an identifier"
    (is (parse-fails? "or")))

  (testing "not alone needs an operand"
    (is (parse-fails? "not")))

  (testing "is is not an identifier"
    (is (parse-fails? "is")))

  (testing "in is not an identifier"
    (is (parse-fails? "in")))

  ;; Words that START with a reserved word but continue ARE valid identifiers
  (testing "prefix of reserved word IS a valid identifier"
    (is (= :Identifier (ast-tag "android")))
    (is (= :Identifier (ast-tag "notify")))
    (is (= :Identifier (ast-tag "isoformat")))
    (is (= :Identifier (ast-tag "inbound")))
    (is (= :Identifier (ast-tag "trueness")))
    (is (= :Identifier (ast-tag "falsehood")))
    (is (= :Identifier (ast-tag "nilable")))))

;; ==========================================================================
;; SECTION 7: Wildcard _
;; ==========================================================================

(deftest parse-wildcard
  (testing "bare underscore"
    (is (parses? "_"))
    (is (= :Wildcard (ast-tag "_"))))

  (testing "underscore is NOT an identifier"
    (is (not= :Identifier (ast-tag "_")))))

;; ==========================================================================
;; SECTION 8: Arithmetic Operators
;; ==========================================================================

(deftest parse-addition
  (testing "simple addition"
    (is (parses? "2 + 3"))
    (let [tree (sast "2 + 3")]
      (is (= :AddExpr (first tree)))
      (is (= [:Integer "2"] (nth tree 1)))
      (is (= [:AddOp "+"] (nth tree 2)))
      (is (= [:Integer "3"] (nth tree 3)))))

  (testing "chained addition is flat"
    (is (parses? "1 + 2 + 3"))
    (let [tree (sast "1 + 2 + 3")]
      (is (= :AddExpr (first tree)))
      ;; flat structure: [:AddExpr int op int op int]
      (is (= 6 (count tree))))))

(deftest parse-subtraction
  (testing "simple subtraction"
    (is (parses? "10 - 3"))
    (let [tree (sast "10 - 3")]
      (is (= :AddExpr (first tree)))
      ;; Use 3-arity nth to avoid IndexOutOfBounds when grammar
      ;; incorrectly parses "10 - 3" as two expressions instead of subtraction
      (is (= [:Integer "10"] (nth tree 1 nil)))
      (is (= [:AddOp "-"] (nth tree 2 nil)))
      (is (= [:Integer "3"] (nth tree 3 nil))))))

(deftest parse-multiplication
  (testing "simple multiplication"
    (is (parses? "4 * 5"))
    (let [tree (sast "4 * 5")]
      (is (= :MulExpr (first tree)))
      (is (= [:MulOp "*"] (nth tree 2))))))

(deftest parse-division
  (testing "simple division"
    (is (parses? "10 / 2"))
    (let [tree (sast "10 / 2")]
      (is (= :MulExpr (first tree)))
      (is (= [:MulOp "/"] (nth tree 2))))))

(deftest parse-modulo
  (testing "modulo operator"
    (is (parses? "10 % 3"))
    (let [tree (sast "10 % 3")]
      (is (= :MulExpr (first tree)))
      (is (= [:MulOp "%"] (nth tree 2))))))

(deftest parse-string-concatenation
  (testing "string + string is valid (same AddExpr, eval decides semantics)"
    (is (parses? "\"hello\" + \" world\""))
    (is (= :AddExpr (ast-tag "\"hello\" + \" world\"")))))

;; ==========================================================================
;; SECTION 9: Operator Precedence
;; ==========================================================================

(deftest parse-arithmetic-precedence
  (testing "multiplication before addition: 2 + 3 * 4"
    (let [tree (sast "2 + 3 * 4")]
      (is (= :AddExpr (first tree)))
      (is (= [:Integer "2"] (nth tree 1)))
      (is (= :MulExpr (first (nth tree 3))))))

  (testing "parentheses override precedence: (2 + 3) * 4"
    (let [tree (sast "(2 + 3) * 4")]
      (is (= :MulExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))
      (is (= [:Integer "4"] (nth tree 3)))))

  (testing "nested parentheses"
    (is (parses? "((2 + 3) * (4 - 1))")))

  (testing "modulo same precedence as * /"
    (let [tree (sast "10 + 7 % 3")]
      (is (= :AddExpr (first tree)))
      (is (= :MulExpr (first (nth tree 3)))))))

(deftest parse-comparison-after-arithmetic
  (testing "comparison wraps arithmetic: 2 + 3 > 4"
    (let [tree (sast "2 + 3 > 4")]
      (is (= :CompExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))
      (is (= [:CompOp ">"] (nth tree 2)))
      (is (= [:Integer "4"] (nth tree 3))))))

(deftest parse-logical-after-comparison
  (testing "and wraps comparisons: 5 > 3 and 2 < 4"
    (let [tree (sast "5 > 3 and 2 < 4")]
      (is (= :AndExpr (first tree)))
      (is (= :CompExpr (first (nth tree 1))))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "or wraps and: true and false or true"
    (let [tree (sast "true and false or true")]
      (is (= :OrExpr (first tree)))
      (is (= :AndExpr (first (nth tree 1))))))

  (testing "full precedence chain: 2 + 3 * 4 > 10 and not false"
    (is (parses? "2 + 3 * 4 > 10 and not false"))
    (let [tree (sast "2 + 3 * 4 > 10 and not false")]
      (is (= :AndExpr (first tree))))))

;; ==========================================================================
;; SECTION 10: Unary Minus
;; ==========================================================================

(deftest parse-unary-minus
  ;; Grammar keeps "-" visible: [:UnaryExpr "-" child]
  ;; This distinguishes negation from pass-through in simplify.
  (testing "negative integer literal"
    (is (parses? "-42"))
    (let [tree (sast "-42")]
      (is (= :UnaryExpr (first tree)))
      (is (= "-" (second tree)))
      (is (= [:Integer "42"] (nth tree 2 nil)))))

  (testing "negative float"
    (is (parses? "-3.14"))
    (let [tree (sast "-3.14")]
      (is (= :UnaryExpr (first tree)))
      (is (= "-" (second tree)))
      (is (= [:Float "3.14"] (nth tree 2 nil)))))

  (testing "negation of identifier"
    (is (parses? "-x"))
    (let [tree (sast "-x")]
      (is (= :UnaryExpr (first tree)))
      (is (= [:Identifier "x"] (nth tree 2 nil)))))

  (testing "negation of parenthesized expression"
    (is (parses? "-(3 + 4)"))
    (let [tree (sast "-(3 + 4)")]
      (is (= :UnaryExpr (first tree)))
      (is (= :AddExpr (first (nth tree 2 [:_placeholder]))))))

  ;; BDD spec: "- 10" (unary minus with space) is a parse error.
  ;; Requires grammar to suppress auto-whitespace inside UnaryExpr.
  (testing "unary minus with space is parse error"
    (is (parse-fails? "- 10")))

  (testing "double negative is parse error"
    (is (parse-fails? "--5"))))

;; ==========================================================================
;; SECTION 11: Comparison Operators
;; ==========================================================================

(deftest parse-comparison-operators
  (testing "equality"
    (is (parses? "5 = 5"))
    (let [tree (sast "5 = 5")]
      (is (= :CompExpr (first tree)))
      (is (= [:Integer "5"] (nth tree 1)))
      (is (= [:CompOp "="] (nth tree 2)))
      (is (= [:Integer "5"] (nth tree 3)))))

  (testing "inequality"
    (is (parses? "5 != 3"))
    (is (= [:CompOp "!="] (nth (sast "5 != 3") 2))))

  (testing "greater than"
    (is (parses? "5 > 3"))
    (is (= [:CompOp ">"] (nth (sast "5 > 3") 2))))

  (testing "less than"
    (is (parses? "3 < 5"))
    (is (= [:CompOp "<"] (nth (sast "3 < 5") 2))))

  (testing "greater than or equal"
    (is (parses? "5 >= 5"))
    (is (= [:CompOp ">="] (nth (sast "5 >= 5") 2))))

  (testing "less than or equal"
    (is (parses? "5 <= 5"))
    (is (= [:CompOp "<="] (nth (sast "5 <= 5") 2))))

  (testing "string comparison"
    (is (parses? "\"apple\" < \"banana\""))
    (is (= :CompExpr (ast-tag "\"apple\" < \"banana\""))))

  (testing "comparison with nil"
    (is (parses? "5 > nil"))
    (is (= :CompExpr (ast-tag "5 > nil"))))

  (testing "comparison is non-associative — chaining is parse error"
    (is (parse-fails? "1 < 2 < 3"))))

;; ==========================================================================
;; SECTION 12: Logical Operators
;; ==========================================================================

(deftest parse-logical-and
  (testing "simple and"
    (is (parses? "true and false"))
    (let [tree (sast "true and false")]
      (is (= :AndExpr (first tree)))
      (is (= [:Boolean "true"] (nth tree 1)))
      (is (= [:Boolean "false"] (nth tree 2)))))

  (testing "chained and is flat"
    (is (parses? "a and b and c"))
    (let [tree (sast "a and b and c")]
      (is (= :AndExpr (first tree)))
      (is (= 4 (count tree))))))

(deftest parse-logical-or
  (testing "simple or"
    (is (parses? "false or true"))
    (let [tree (sast "false or true")]
      (is (= :OrExpr (first tree)))
      (is (= [:Boolean "false"] (nth tree 1)))
      (is (= [:Boolean "true"] (nth tree 2)))))

  (testing "chained or is flat"
    (is (parses? "a or b or c"))
    (let [tree (sast "a or b or c")]
      (is (= :OrExpr (first tree)))
      (is (= 4 (count tree))))))

(deftest parse-logical-not
  ;; Grammar keeps "not" visible: [:NotExpr "not" child]
  ;; This distinguishes negation from pass-through in simplify.
  (testing "not true"
    (is (parses? "not true"))
    (let [tree (sast "not true")]
      (is (= :NotExpr (first tree)))
      (is (= "not" (second tree)))
      (is (= [:Boolean "true"] (nth tree 2 nil)))))

  (testing "not false"
    (is (parses? "not false"))
    (is (= :NotExpr (ast-tag "not false"))))

  ;; PRD precedence: comparisons > not > ?? > and > or
  ;; So "not 5 > 3" = not(5 > 3)
  (testing "not wraps comparison (not has lower precedence)"
    (let [tree (sast "not 5 > 3")]
      (is (= :NotExpr (first tree)))
      (is (= "not" (second tree)))
      (is (= :CompExpr (first (nth tree 2 [:_placeholder]))))))

  (testing "not with parenthesized expression"
    (is (parses? "not (5 > 3)"))
    (is (= :NotExpr (ast-tag "not (5 > 3)")))))

(deftest parse-logical-precedence
  ;; PRD: not > ?? > and > or

  (testing "and binds tighter than or: true and false or true"
    ;; -> OrExpr(AndExpr(true, false), true)
    (let [tree (sast "true and false or true")]
      (is (= :OrExpr (first tree)))
      (is (= :AndExpr (first (nth tree 1 nil))))
      (is (= [:Boolean "true"] (nth tree 2 nil)))))

  (testing "not > and > or: not true or false"
    ;; -> OrExpr(NotExpr(not, true), false)
    (let [tree (sast "not true or false")]
      (is (= :OrExpr (first tree)))
      (is (= :NotExpr (first (nth tree 1 nil))))))

  (testing "not > and: not true and false"
    ;; -> AndExpr(NotExpr(not, true), false)
    (let [tree (sast "not true and false")]
      (is (= :AndExpr (first tree)))
      (is (= :NotExpr (first (nth tree 1 nil)))))))

;; ==========================================================================
;; SECTION 13: In Operator
;; ==========================================================================

(deftest parse-in-operator
  (testing "value in identifier"
    (is (parses? "\"x\" in tags"))
    (let [tree (sast "\"x\" in tags")]
      (is (= :InExpr (first tree)))
      (is (= [:String "x"] (nth tree 1)))
      (is (= [:Identifier "tags"] (nth tree 2)))))

  ;; PRD precedence: + - > in > comparisons
  (testing "in has lower precedence than arithmetic"
    (let [tree (sast "x + 1 in list")]
      (is (= :InExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))))

  (testing "in has higher precedence than comparison"
    (let [tree (sast "\"x\" in tags = true")]
      (is (= :CompExpr (first tree)))
      (is (= :InExpr (first (nth tree 1))))))

  (testing "nil in collection"
    (is (parses? "nil in items"))
    (is (= :InExpr (ast-tag "nil in items"))))

  (testing "key in object"
    (is (parses? "\"name\" in user"))
    (is (= :InExpr (ast-tag "\"name\" in user")))))

;; ==========================================================================
;; SECTION 14: Nil Coalescing (??)
;; ==========================================================================

(deftest parse-nil-coalescing
  (testing "simple nil coalescing"
    (is (parses? "value ?? default"))
    (let [tree (sast "value ?? default")]
      (is (= :NilCoalesce (first tree)))
      (is (= [:Identifier "value"] (nth tree 1)))
      (is (= [:Identifier "default"] (nth tree 2)))))

  (testing "chained nil coalescing is flat"
    (is (parses? "a ?? b ?? c"))
    (let [tree (sast "a ?? b ?? c")]
      (is (= :NilCoalesce (first tree)))
      (is (= 4 (count tree)))))

  ;; PRD precedence: comparisons > not > ?? > and > or
  ;; So "x > 5 ?? false" = NilCoalesce(CompExpr(x > 5), false)
  (testing "?? wraps comparison result (comp > not > ??)"
    (let [tree (sast "x > 5 ?? false")]
      (is (= :NilCoalesce (first tree)))
      (is (= :CompExpr (first (nth tree 1)))))))

;; ==========================================================================
;; SECTION 15: Parenthesized Expressions
;; ==========================================================================

(deftest parse-parenthesized-expressions
  (testing "simple grouping — parens are transparent"
    (is (parses? "(42)"))
    (is (= [:Integer "42"] (sast "(42)"))))

  (testing "arithmetic grouping"
    (is (parses? "(2 + 3)"))
    (is (= :AddExpr (ast-tag "(2 + 3)"))))

  (testing "nested parentheses collapse"
    (is (parses? "((42))"))
    (is (= [:Integer "42"] (sast "((42))"))))

  (testing "deep nesting"
    (is (parses? "(((2 + 3)))")))

  (testing "empty parentheses are a parse error"
    (is (parse-fails? "()"))))

;; ==========================================================================
;; SECTION 16: Comments
;; ==========================================================================

(deftest parse-comments
  (testing "expression followed by line comment"
    (is (parses? "42 // this is a comment"))
    (is (= [:Integer "42"] (sast "42 // this is a comment"))))

  (testing "comment before expression"
    (is (parses? "// header comment\n42")))

  (testing "comment between expressions"
    (is (parses? "x is 5\n// separator\ny is 10")))

  ;; Comments are whitespace. Program = Expr+ requires >= 1 expression.
  ;; A file with only comments has zero expressions → parse error.
  (testing "comment-only input is a parse error (no expressions)"
    (is (parse-fails? "// just a comment")))

  (testing "comment does not appear in AST"
    (let [tree (ast "42 // a comment")]
      (is (not (tree-contains-tag? tree :Comment))))))

;; ==========================================================================
;; SECTION 17: Data Structures — Objects
;; ==========================================================================

(deftest parse-empty-object
  (testing "empty object"
    (is (parses? "{}"))
    (is (= :Object (ast-tag "{}")))))

(deftest parse-object-with-fields
  (testing "single field"
    (is (parses? "{name: \"Alice\"}"))
    (is (= :Object (ast-tag "{name: \"Alice\"}"))))

  (testing "multiple space-separated fields"
    (is (parses? "{name: \"Alice\" age: 25 active: true}"))
    (is (= :Object (ast-tag "{name: \"Alice\" age: 25 active: true}"))))

  (testing "hyphenated keys"
    (is (parses? "{first-name: \"Alice\" last-name: \"Smith\"}")))

  (testing "keys with underscore"
    (is (parses? "{user_name: \"Alice\"}")))

  (testing "keys with digits"
    (is (parses? "{level2: \"advanced\" x1: 10}")))

  (testing "nested objects"
    (is (parses? "{a: {b: {c: 1}}}")))

  (testing "object with nil value"
    (is (parses? "{name: \"Alice\" address: nil}")))

  (testing "object with expression values"
    (is (parses? "{doubled: x * 2 name: \"Alice\"}")))

  (testing "function as object value"
    (is (parses? "{transform: [x -> x * 2]}")))

  (testing "multi-line object"
    (is (parses? "{\n  name: \"Alice\"\n  age: 25\n}")))

  (testing "extra whitespace inside braces"
    (is (parses? "{  name:   \"Alice\"   age:   25  }")))

  (testing "duplicate keys parse successfully (last wins at eval time)"
    (is (parses? "{name: \"Alice\" name: \"Bob\"}"))))

(deftest parse-object-negative-cases
  (testing "commas between key:value fields are a parse error"
    (is (parse-fails? "{name: \"Alice\", age: 25}")))

  (testing "key starting with digit is a parse error"
    (is (parse-fails? "{2fast: \"no\"}"))))

;; ==========================================================================
;; SECTION 18: Data Structures — Lists
;; ==========================================================================

(deftest parse-empty-list
  (testing "empty list"
    (is (parses? "[]"))
    (is (= :List (ast-tag "[]")))))

(deftest parse-list-with-elements
  (testing "integer list"
    (is (parses? "[1 2 3 4 5]"))
    (is (= :List (ast-tag "[1 2 3 4 5]"))))

  (testing "string list"
    (is (parses? "[\"Alice\" \"Bob\" \"Charlie\"]")))

  (testing "mixed types"
    (is (parses? "[\"Alice\" 25 true nil]")))

  (testing "nested lists"
    (is (parses? "[[1 2] [3 4] [5 6]]")))

  (testing "list containing objects"
    (is (parses? "[{a: 1} {a: 2} {a: 3}]")))

  (testing "multi-line list"
    (is (parses? "[\n  1\n  2\n  3\n]")))

  (testing "extra whitespace"
    (is (parses? "[  1   2   3  ]")))

  (testing "single element"
    (is (parses? "[42]")))

  (testing "deeply nested"
    (is (parses? "[[[1]]]"))))

(deftest parse-list-negative-cases
  (testing "commas between elements are a parse error"
    (is (parse-fails? "[1, 2, 3]"))))

;; ==========================================================================
;; SECTION 19: Field Access (Dot Notation)
;; ==========================================================================

(deftest parse-field-access
  (testing "simple field access"
    (is (parses? "user.name"))
    (is (= :FieldAccess (ast-tag "user.name"))))

  (testing "chained field access"
    (is (parses? "user.profile.address.city"))
    (is (= :FieldAccess (ast-tag "user.profile.address.city"))))

  (testing "wildcard field access"
    (is (parses? "_.name"))
    (is (= :FieldAccess (ast-tag "_.name"))))

  (testing "deeply nested wildcard access"
    (is (parses? "_.profile.address.city"))
    (is (= :FieldAccess (ast-tag "_.profile.address.city"))))

  (testing "field access on literal object"
    (is (parses? "{name: \"Alice\"}.name")))

  (testing "field access on parenthesized expression"
    (is (parses? "(get-user).name"))))

;; ==========================================================================
;; SECTION 20: Binding (is)
;; ==========================================================================

(deftest parse-simple-binding
  (testing "bind integer"
    (is (parses? "x is 42"))
    (is (= :Binding (ast-tag "x is 42"))))

  (testing "bind string"
    (is (parses? "name is \"Alice\""))
    (is (= :Binding (ast-tag "name is \"Alice\""))))

  (testing "bind boolean"
    (is (parses? "active is true")))

  (testing "bind nil"
    (is (parses? "nothing is nil")))

  (testing "bind to expression"
    (is (parses? "total is 3 + 4"))
    (is (= :Binding (ast-tag "total is 3 + 4"))))

  (testing "bind to object"
    (is (parses? "user is {name: \"Alice\" age: 30}")))

  (testing "bind to list"
    (is (parses? "nums is [1 2 3 4 5]")))

  (testing "bind function"
    (is (parses? "double is [x -> x * 2]")))

  (testing "bind pipeline result"
    (is (parses? "result is users |> filter _.active |> count")))

  (testing "hyphenated binding name"
    (is (parses? "my-var is 10")))

  (testing "predicate binding"
    (is (parses? "even? is [n -> n % 2 = 0]"))))

(deftest parse-binding-negative-cases
  (testing "reserved word as binding target — true"
    (is (parse-fails? "true is 5")))

  (testing "reserved word as binding target — false"
    (is (parse-fails? "false is 5")))

  (testing "reserved word as binding target — nil"
    (is (parse-fails? "nil is 42")))

  (testing "underscore as binding target"
    (is (parse-fails? "_ is 42"))))

;; ==========================================================================
;; SECTION 21: Functions
;; ==========================================================================

(deftest parse-function-definition
  (testing "single-parameter function"
    (is (parses? "[x -> x * 2]"))
    (is (= :FnDef (ast-tag "[x -> x * 2]"))))

  (testing "multi-parameter function"
    (is (parses? "[a b -> a + b]"))
    (is (= :FnDef (ast-tag "[a b -> a + b]"))))

  (testing "zero-parameter function"
    (is (parses? "[-> 42]"))
    (is (= :FnDef (ast-tag "[-> 42]"))))

  (testing "complex body expression"
    (is (parses? "[a b c x -> a * x * x + b * x + c]")))

  (testing "function with string body"
    (is (parses? "[name -> format \"Hello, %s!\" name]")))

  (testing "predicate function"
    (is (parses? "[n -> n % 2 = 0]")))

  (testing "variadic function"
    (is (parses? "[a b & rest -> rest]"))
    (is (= :FnDef (ast-tag "[a b & rest -> rest]"))))

  (testing "variadic with only rest"
    (is (parses? "[& nums -> nums]")))

  (testing "function returning object"
    (is (parses? "[x y -> {x: x y: y}]")))

  (testing "function returning list"
    (is (parses? "[a b -> [a b]]")))

  (testing "nested function (closure)"
    (is (parses? "[n -> [x -> x + n]]")))

  (testing "deeply nested closure"
    (is (parses? "[factor -> [offset -> [x -> x * factor + offset]]]"))))

(deftest parse-multi-expression-function-body
  (testing "function body with is-bindings and return value"
    (is (parses? "[a b ->\n  a2 is a * a\n  b2 is b * b\n  a2 + b2\n]")))

  (testing "function body with binding and pipeline"
    (is (parses? "[data ->\n  filtered is data |> filter _.active\n  total is filtered |> count\n  {items: filtered total: total}\n]"))))

(deftest parse-function-call
  (testing "simple function call"
    (is (parses? "double 5"))
    (is (= :FnCall (ast-tag "double 5"))))

  (testing "multi-arg function call"
    (is (parses? "add 3 4"))
    (is (= :FnCall (ast-tag "add 3 4"))))

  (testing "zero-arg function call with parens"
    (is (parses? "f()")))

  (testing "function call with expression arg in parens"
    (is (parses? "f (2 + 3)")))

  (testing "function call with string arg"
    (is (parses? "format \"Hello, %s!\" name")))

  (testing "higher-order: calling result of function call"
    (is (parses? "(make-adder 5) 10"))))

(deftest parse-list-vs-function-disambiguation
  (testing "[] is an empty list, not a function"
    (is (= :List (ast-tag "[]"))))

  (testing "[1 2 3] is a list"
    (is (= :List (ast-tag "[1 2 3]"))))

  (testing "[x -> x] is a function (has arrow)"
    (is (= :FnDef (ast-tag "[x -> x]"))))

  (testing "[-> 42] is a zero-param function"
    (is (= :FnDef (ast-tag "[-> 42]"))))

  (testing "[42] is a single-element list"
    (is (= :List (ast-tag "[42]"))))

  (testing "[x] is a single-element list (identifier as element)"
    (is (= :List (ast-tag "[x]")))))

(deftest parse-destructuring-in-function-params
  (testing "object destructuring in params"
    (is (parses? "[{name} -> format \"Hello, %s!\" name]")))

  (testing "object destructuring with rename in params"
    (is (parses? "[{age: a1} {age: a2} -> a1 + a2]")))

  (testing "list destructuring in params"
    (is (parses? "[[first & _] -> first]")))

  (testing "mixed regular and destructured params"
    (is (parses? "[label {name age} -> label]")))

  (testing "destructured param in pipeline lambda"
    (is (parses? "users |> map [{name age} -> {display: name years: age}]"))))

(deftest parse-partial-application
  (testing "partial application via partial function"
    (is (parses? "add5 is partial add 5"))))

;; ==========================================================================
;; SECTION 22: Pipeline (|>)
;; ==========================================================================

(deftest parse-pipeline
  (testing "single step pipeline"
    (is (parses? "[1 2 3] |> count"))
    (is (= :Pipeline (ast-tag "[1 2 3] |> count"))))

  (testing "multi-step inline pipeline"
    (is (parses? "users |> filter _.active |> count"))
    (is (= :Pipeline (ast-tag "users |> filter _.active |> count"))))

  (testing "pipeline step with arguments"
    (is (parses? "data |> take 10"))
    (is (= :Pipeline (ast-tag "data |> take 10"))))

  (testing "pipeline step with multiple arguments"
    (is (parses? "text |> replace \"old\" \"new\"")))

  (testing "pipeline step with no arguments"
    (is (parses? "items |> reverse |> distinct")))

  (testing "multi-line pipeline (|> at line start)"
    (is (parses? "users\n|> filter _.active\n|> map _.name\n|> sort")))

  (testing "pipeline with anonymous function"
    (is (parses? "users |> filter [u -> u.age > 18]")))

  (testing "pipeline with wildcard expression"
    (is (parses? "users |> filter _.age > 18")))

  (testing "pipeline with bare wildcard (whole element)"
    (is (parses? "items |> filter _ != nil")))

  (testing "pipeline with wildcard in arithmetic"
    (is (parses? "numbers |> map _ * 2 + 1")))

  (testing "pipeline result assigned with is"
    (is (parses? "result is users |> filter _.active |> count")))

  (testing "pipeline result destructured"
    (is (parses? "[first & rest] is items |> sort |> reverse"))))

(deftest parse-pipeline-negative-cases
  (testing "empty pipeline — no step after |>"
    (is (parse-fails? "data |>")))

  (testing "consecutive pipes with no function"
    (is (parse-fails? "data |> |> count"))))

(deftest parse-sourceless-pipeline
  (testing "sourceless pipeline creates reusable transformer"
    (is (parses? "|> filter _.active |> map _.name |> sort")))

  (testing "sourceless pipeline assigned with is"
    (is (parses? "normalize is |> filter _.active |> map _.name |> sort")))

  (testing "applying a named pipeline"
    (is (parses? "users |> normalize"))))

(deftest parse-nested-pipeline
  (testing "nested pipeline in map"
    (is (parses? "users\n|> map {\n  name: _.name\n  top-scores: _.scores |> filter [s -> s > 80] |> take 3\n}"))))

(deftest parse-pipeline-with-side-effects
  (testing "side-effect functions in pipeline are passthrough"
    (is (parses? "data\n|> log! \"start\"\n|> process\n|> log! \"end\"\n|> save! \"output.json\""))))

;; ==========================================================================
;; SECTION 23: Pattern Matching — Guards
;; ==========================================================================

(deftest parse-guard-expression
  (testing "simple guard block"
    (is (parses? "| x > 5 -> \"big\" | _ -> \"small\"")))

  (testing "multi-line guard block"
    (is (parses? "| amount > 1000 -> \"gold\"\n| amount > 100 -> \"silver\"\n| _ -> \"bronze\"")))

  (testing "guard with logical operators"
    (is (parses? "| role = \"admin\" or role = \"superadmin\" -> \"full\" | _ -> \"read\"")))

  (testing "guard with and-chained conditions"
    (is (parses? "| age >= 0 and age < 13 -> \"child\"\n| age >= 13 and age < 18 -> \"teen\"\n| _ -> \"adult\"")))

  (testing "guard with function call in condition"
    (is (parses? "| even? x -> \"even\" | _ -> \"odd\"")))

  (testing "guard as value of is"
    (is (parses? "tier is\n  | amount > 1000 -> \"gold\"\n  | _ -> \"bronze\"")))

  (testing "single-line guard after is"
    (is (parses? "tier is | x > 5 -> \"high\" | _ -> \"low\"")))

  (testing "guard result can be an expression"
    (is (parses? "| x > 0 -> x * 2 + 1 | _ -> 0")))

  (testing "guard result can be an object"
    (is (parses? "| x > 0 -> {value: x} | _ -> {value: 0}")))

  (testing "guard result can be a list"
    (is (parses? "| x > 0 -> [x] | _ -> []"))))

(deftest parse-guard-in-function
  (testing "function body with guards"
    (is (parses? "[x ->\n  | x > 0 -> \"positive\"\n  | _ -> \"non-positive\"\n]")))

  (testing "function with complex guard body"
    (is (parses? "[x ->\n  | x > 0 -> \"positive\"\n  | x = 0 -> \"zero\"\n  | _ -> \"negative\"\n]"))))

(deftest parse-guard-in-object-field
  (testing "guard as object field value in pipeline"
    (is (parses? "users |> map {\n  name: _.name\n  tier:\n    | _.spending > 1000 -> \"gold\"\n    | _ -> \"bronze\"\n}"))))

(deftest parse-structural-pattern
  (testing "object pattern"
    (is (parses? "| {type: \"book\"} -> \"book\" | _ -> \"other\"")))

  (testing "object pattern with multiple fields"
    (is (parses? "| {type: \"book\" format: \"hardcover\"} -> \"hardcover book\" | _ -> \"other\"")))

  (testing "empty list pattern"
    (is (parses? "| [] -> \"empty\" | _ -> \"nonempty\"")))

  (testing "single-element list pattern"
    (is (parses? "| [x] -> \"single\" | _ -> \"other\"")))

  (testing "list pattern with rest"
    (is (parses? "| [x & rest] -> \"many\" | _ -> \"other\"")))

  (testing "pattern with when clause"
    (is (parses? "| {type: \"book\" pages: p} when p > 500 -> \"epic\" | _ -> \"book\"")))

  (testing "when clause with compound condition"
    (is (parses? "| {type: \"movie\" rating: r year: y} when r > 8 and y > 2000 -> \"modern-classic\" | _ -> \"other\"")))

  (testing "nil pattern"
    (is (parses? "| nil -> \"nothing\" | _ -> \"something\"")))

  (testing "literal integer patterns"
    (is (parses? "| 0 -> \"zero\" | 1 -> \"one\" | 42 -> \"answer\" | _ -> \"other\"")))

  (testing "literal string patterns"
    (is (parses? "| \"ok\" -> \"success\" | \"error\" -> \"failure\" | _ -> \"unknown\"")))

  (testing "literal boolean patterns"
    (is (parses? "| true -> \"yes\" | false -> \"no\"")))

  (testing "wildcard in structural pattern matches any value"
    (is (parses? "| {type: _} -> \"has-type\" | _ -> \"no-type\"")))

  (testing "variable binding in pattern"
    (is (parses? "| {name: n} -> format \"Hello, %s!\" n | _ -> \"unknown\"")))

  (testing "nested object pattern"
    (is (parses? "| {address: {city: c}} -> c | _ -> \"unknown\"")))

  (testing "list-of-objects pattern"
    (is (parses? "| [{name: n} & _] -> n | _ -> \"empty\"")))

  (testing "literal value in object field pattern"
    (is (parses? "| {status: 200} -> \"ok\" | {status: 404} -> \"not found\" | _ -> \"error\""))))

(deftest parse-structural-pattern-negative-cases
  (testing "nested guards are NOT allowed"
    (is (parse-fails? "| x > 0 -> | x > 10 -> \"big\" | _ -> \"small\" | _ -> \"negative\""))))

;; ==========================================================================
;; SECTION 24: Destructuring
;; ==========================================================================

(deftest parse-object-destructuring
  (testing "simple object destructuring"
    (is (parses? "{name age} is user")))

  (testing "destructuring with rename"
    (is (parses? "{name: n age: a} is user")))

  (testing "destructuring with defaults"
    (is (parses? "{name ? \"anon\" age ? 0} is user")))

  (testing "destructuring with as (whole binding)"
    (is (parses? "{name age} as u is user")))

  (testing "nested object destructuring"
    (is (parses? "{address: {city country}} is user")))

  (testing "two-level nesting"
    (is (parses? "{a: {b: {c}}} is deep")))

  (testing "combined rename + default + as"
    (is (parses? "{name: n age ? 0} as u is user"))))

(deftest parse-list-destructuring
  (testing "simple list destructuring"
    (is (parses? "[a b c] is [1 2 3]")))

  (testing "list destructuring with rest"
    (is (parses? "[first & rest] is items")))

  (testing "list destructuring with skip"
    (is (parses? "[_ _ third] is items")))

  (testing "skip first, capture rest"
    (is (parses? "[_ & tail] is [1 2 3 4]")))

  (testing "multiple underscores"
    (is (parses? "[_ _ _ fourth] is [1 2 3 4]")))

  (testing "list destructuring with as"
    (is (parses? "[head & tail] as all is items"))))

(deftest parse-combined-destructuring
  (testing "object + list combined"
    (is (parses? "{name scores: [best & rest]} is player")))

  (testing "list of objects, destructure first"
    (is (parses? "[{name age} & others] is users"))))

(deftest parse-destructuring-negative-cases
  (testing "empty destructuring pattern is parse error"
    (is (parse-fails? "{} is user")))

  (testing "& must be followed by exactly one identifier"
    (is (parse-fails? "[a & b c] is items")))

  (testing "object rest is not supported"
    (is (parse-fails? "{name & rest} is user"))))

;; ==========================================================================
;; SECTION 25: Multi-expression Program
;; ==========================================================================

(deftest parse-multi-expression-program
  (testing "multiple expressions separated by newlines"
    (is (parses? "x is 42\nx + 1")))

  (testing "binding then use"
    (is (parses? "double is [x -> x * 2]\ndouble 5")))

  (testing "multiple bindings"
    (is (parses? "x is 5\ny is 10\nx + y")))

  (testing "binding then pipeline"
    (is (parses? "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 17}]\nusers |> filter _.age > 18 |> count")))

  (testing "last expression is program result"
    (is (parses? "x is 42\ny is x + 1\ny"))))

;; ==========================================================================
;; SECTION 26: Edge Cases and Parse Errors
;; ==========================================================================

(deftest parse-whitespace-requirements
  ;; PRD: "Whitespace required around operators"
  (testing "operators require whitespace"
    (is (parse-fails? "2+3")))

  (testing "identifier with hyphen is single token, not subtraction"
    (is (parses? "my-var"))
    (is (= :Identifier (ast-tag "my-var"))))

  (testing "hyphen with spaces is subtraction"
    (is (parses? "my - var"))
    (let [tree (sast "my - var")]
      (is (= :AddExpr (first tree))))))

(deftest parse-various-errors
  (testing "multiple operators without operands"
    (is (parse-fails? "5 + + 3")))

  (testing "empty input is a parse error"
    (is (parse-fails? "")))

  (testing "just whitespace is a parse error"
    (is (parse-fails? "   ")))

  (testing "pipe operator inside string is literal text"
    (is (= :String (ast-tag "\"use |> for pipes\"")))))

;; ==========================================================================
;; SECTION 27: Function Call with Parens
;; ==========================================================================

(deftest parse-function-call-with-parens
  (testing "zero-arg call"
    (is (parses? "answer()")))

  (testing "parenthesized call then field access"
    (is (parses? "(get-user).name")))

  (testing "bare identifier is reference, not call"
    (is (= :Identifier (ast-tag "answer")))))

;; ==========================================================================
;; SECTION 28: Composition Operators
;; ==========================================================================

(deftest parse-composition-operators
  (testing "left-to-right composition >>"
    (is (parses? "double >> inc"))
    (is (= :Compose (ast-tag "double >> inc"))))

  (testing "right-to-left composition <<"
    (is (parses? "double << inc"))
    (is (= :Compose (ast-tag "double << inc"))))

  (testing "chained composition"
    (is (parses? "step1 >> step2 >> step3")))

  (testing "composition assigned with is"
    (is (parses? "transform is double >> inc >> abs"))))

;; ==========================================================================
;; SECTION 29: Try-Catch
;; ==========================================================================

(deftest parse-try-catch
  (testing "basic try-catch"
    (is (parses? "try\n  risky-op\ncatch err -> default-val")))

  (testing "try-catch with typed exception"
    (is (parses? "try\n  read-file\ncatch java.io.FileNotFoundException e -> \"not found\"\ncatch _ -> \"unknown\"")))

  (testing "try-catch-finally"
    (is (parses? "try\n  risky\ncatch err -> []\nfinally\n  cleanup")))

  (testing "try-catch assigned with is"
    (is (parses? "data is try\n  read-csv \"data.csv\"\ncatch err -> []"))))

;; ==========================================================================
;; SECTION 30: Require / Import & Interop
;; ==========================================================================

(deftest parse-require
  (testing "require with alias"
    (is (parses? "require clojure.string as str")))

  (testing "qualified function call"
    (is (parses? "clojure.string/upper-case \"hello\"")))

  (testing "aliased qualified call"
    (is (parses? "str/upper-case \"hello\""))))

(deftest parse-java-interop
  (testing "instance method call"
    (is (parses? ".method object")))

  (testing "static method call"
    (is (parses? "Math/abs -5")))

  (testing "constructor"
    (is (parses? "ArrayList. 10"))))

(deftest parse-keyword-syntax
  (testing "keyword literal"
    (is (parses? ":status"))
    (is (= :Keyword (ast-tag ":status"))))

  (testing "keyword with hyphen"
    (is (parses? ":first-name"))))

;; ==========================================================================
;; SECTION 31: Object Field Operations (+/- prefixes)
;; ==========================================================================

(deftest parse-field-operations
  (testing "add field with + prefix"
    (is (parses? "{+score: _.age * 2}")))

  (testing "remove field with - prefix"
    (is (parses? "{-tmp}")))

  (testing "mixed + and -"
    (is (parses? "{+score: _.age * 2 -tmp}")))

  (testing "forward-referencing in field operations"
    (is (parses? "{+tax: _.price * 0.1 +total: _.price + tax}")))

  ;; Shorthand uses commas between bare identifiers (no colons).
  ;; Distinct from commas between key:value pairs which are invalid.
  (testing "object shorthand with commas"
    (is (parses? "{name, age}")))

  (testing "shorthand mixed with explicit field"
    (is (parses? "{name, age, city: _.address.city}")))

  (testing "plain object = new structure (no +/- prefix)"
    (is (parses? "{name: _.name age: _.age}"))))

;; ==========================================================================
;; SECTION 32: Multi-arity Functions
;; ==========================================================================

(deftest parse-multi-arity-function
  (testing "function with two arities"
    (is (parses? "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]")))

  (testing "function with three arities"
    (is (parses? "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]\n  [first last -> format \"Hello, %s %s!\" first last]"))))

;; ==========================================================================
;; SECTION 33: Recur
;; ==========================================================================

(deftest parse-recur
  (testing "recur in function body with guards"
    (is (parses? "[n acc ->\n  | n <= 1 -> acc\n  | _ -> recur (n - 1) (acc * n)\n]")))

  (testing "recur with single argument"
    (is (parses? "[n ->\n  | n <= 0 -> 0\n  | _ -> n + recur (n - 1)\n]"))))
