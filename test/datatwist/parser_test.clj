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

(def container-tags
  "Node types that should never collapse even with a single child.
   These represent semantic containers, not precedence wrappers."
  #{:List :Object :FnDef})

(defn simplify
  "Collapse single-child wrapper nodes in the AST.
   [:A [:B [:C x]]] -> [:C x] when A and B each have exactly one child.
   Keeps multi-child nodes intact to preserve meaningful structure.
   Container nodes (List, Object, FnDef) never collapse."
  [tree]
  (if (not (vector? tree))
    tree
    (let [children (mapv simplify (rest tree))]
      (if (and (= 1 (count children))
               (vector? (first children))
               (keyword? (ffirst children))
               (not (container-tags (first tree))))
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
;; Grammar-aware structural validation
;; ==========================================================================

(def grammar-edges
  "Valid parent→child tag relationships for single-child wrapper nodes.
   When a node has exactly one tagged-vector child, this map defines which
   child tags are valid. Used by validate-wrapper-chain to verify the parse
   tree follows the correct precedence/dispatch path."
  {:Program     #{:Expr}
   :Expr        #{:Require :TryCatch :Binding :PipeExpr}
   :PipeExpr    #{:Pipeline :SourcelessPipeline :Compose :GuardBlock :OrExpr}
   :PipeAtom    #{:GuardBlock :OrExpr}
   :OrExpr      #{:AndExpr}
   :AndExpr     #{:NilCoalesce}
   :NilCoalesce #{:NotExpr}
   :NotExpr     #{:CompExpr}
   :CompExpr    #{:InExpr}
   :InExpr      #{:AddExpr}
   :AddExpr     #{:MulExpr}
   :MulExpr     #{:UnaryExpr}
   :UnaryExpr   #{:FnCallExpr}
   :FnCallExpr  #{:FnCall :Recur :FieldAccess}
   :FieldAccess #{:Atom}
   :Atom        #{:Float :Integer :String :Boolean :Nil :Keyword
                  :Object :FnDef :List :InstanceMethod :Constructor
                  :QualifiedName :Wildcard :Identifier :ParenExpr}})

(defn validate-wrapper-chain
  "Walk through single-child wrapper nodes from root, validating each
   parent→child tag relationship against grammar-edges.
   Returns {:valid? bool :node semantic-node :path [tags] :error msg}."
  [tree]
  (loop [node tree
         path []]
    (if-not (vector? node)
      {:valid? false :path path :error "Expected vector node"}
      (let [tag        (first node)
            children   (rest node)
            valid-kids (get grammar-edges tag)]
        (cond
          ;; Not a known wrapper → semantic node, stop
          (nil? valid-kids)
          {:valid? true :node node :path (conj path tag)}

          ;; Single child that is a tagged vector → validate edge
          (and (= 1 (count children))
               (vector? (first children))
               (keyword? (ffirst children)))
          (let [child-tag (ffirst children)]
            (if (contains? valid-kids child-tag)
              (recur (first children) (conj path tag))
              {:valid? false :path (conj path tag)
               :error (str "Invalid edge: " tag " → " child-tag
                           ". Expected one of: " valid-kids)}))

          ;; Multiple children or non-tagged child → semantic node, stop
          :else
          {:valid? true :node node :path (conj path tag)})))))

(defn parses-to
  "Parse input, validate wrapper chain, simplify. Returns simplified tree.
   Throws on parse failure or invalid wrapper chain."
  [input]
  (let [raw (parser/parse input)]
    (when (insta/failure? raw)
      (throw (ex-info (str "Parse failed: " (pr-str input)) {:input input})))
    (let [{:keys [valid? error]} (validate-wrapper-chain raw)]
      (when-not valid?
        (throw (ex-info (str "Invalid wrapper chain for " (pr-str input)
                             ": " error)
                        {:input input})))
      (simplify raw))))

;; ==========================================================================
;; SECTION 1: Integer Literals
;; ==========================================================================

(deftest parse-integer-literals
  (testing "simple positive integer"
    (is (= [:Integer "42"] (parses-to "42"))))

  (testing "zero"
    (is (= [:Integer "0"] (parses-to "0"))))

  (testing "large number (Long max)"
    (is (= [:Integer "9223372036854775807"] (parses-to "9223372036854775807"))))

  (testing "number beyond Long range still parses as Integer token"
    (is (= :Integer (first (parses-to "9223372036854775808")))))

  (testing "multiple digits"
    (is (= [:Integer "12345"] (parses-to "12345"))))

  (testing "scientific notation is NOT a number — parses as Integer + Identifier"
    (is (not= :Float (first (parses-to "1e10")))))

  (testing "underscore separators are NOT supported as single token"
    (is (not= [:Integer "1_000_000"] (parses-to "1_000_000")))))

;; ==========================================================================
;; SECTION 2: Float Literals
;; ==========================================================================

(deftest parse-float-literals
  (testing "simple decimal"
    (is (= [:Float "3.14"] (parses-to "3.14"))))

  (testing "zero point something"
    (is (= [:Float "0.5"] (parses-to "0.5"))))

  (testing "leading dot is NOT valid"
    (is (parse-fails? ".5")))

  (testing "trailing dot is NOT valid"
    (is (parse-fails? "5.")))

  (testing "negative float parses as unary minus + float"
    (is (= [:UnaryExpr "-" [:Float "0.001"]] (parses-to "-0.001")))))

;; ==========================================================================
;; SECTION 3: String Literals
;; ==========================================================================

(deftest parse-string-literals
  (testing "simple string"
    (is (= [:String "hello world"] (parses-to "\"hello world\""))))

  (testing "empty string"
    (is (= [:String ""] (parses-to "\"\""))))

  (testing "string with escape sequences"
    (is (= :String (first (parses-to "\"line1\\nline2\"")))))

  (testing "string with escaped quotes"
    (is (= :String (first (parses-to "\"she said \\\"hi\\\"\"")))))

  (testing "unclosed string is a parse error"
    (is (parse-fails? "\"hello")))

  (testing "no string interpolation — ${} is literal text"
    (is (= :String (first (parses-to "\"Hello ${name}\""))))))

;; ==========================================================================
;; SECTION 4: Boolean Literals
;; ==========================================================================

(deftest parse-boolean-literals
  (testing "true"
    (is (= [:Boolean "true"] (parses-to "true"))))

  (testing "false"
    (is (= [:Boolean "false"] (parses-to "false"))))

  (testing "true is not parsed as identifier"
    (is (= :Boolean (first (parses-to "true")))))

  (testing "false is not parsed as identifier"
    (is (= :Boolean (first (parses-to "false"))))))

;; ==========================================================================
;; SECTION 5: Nil Literal
;; ==========================================================================

(deftest parse-nil-literal
  (testing "nil"
    (is (= [:Nil "nil"] (parses-to "nil"))))

  (testing "nil is not parsed as identifier"
    (is (= :Nil (first (parses-to "nil"))))))

;; ==========================================================================
;; SECTION 6: Identifiers
;; ==========================================================================

(deftest parse-identifiers
  (testing "simple identifier"
    (is (= [:Identifier "name"] (parses-to "name"))))

  (testing "single letter"
    (is (= [:Identifier "x"] (parses-to "x"))))

  (testing "hyphenated identifier"
    (is (= [:Identifier "user-name"] (parses-to "user-name"))))

  (testing "identifier with underscore"
    (is (= [:Identifier "user_name"] (parses-to "user_name"))))

  (testing "identifier with digits"
    (is (= [:Identifier "x1"] (parses-to "x1")))
    (is (= [:Identifier "level2"] (parses-to "level2"))))

  (testing "predicate identifier (trailing ?)"
    (is (= [:Identifier "even?"] (parses-to "even?"))))

  (testing "side-effect identifier (trailing !)"
    (is (= [:Identifier "log!"] (parses-to "log!"))))

  (testing "identifier cannot start with digit"
    (is (not= :Identifier (first (parses-to "2fast"))))))

(deftest parse-reserved-words-not-identifiers
  (testing "true is Boolean, not Identifier"
    (is (= :Boolean (first (parses-to "true")))))

  (testing "false is Boolean, not Identifier"
    (is (= :Boolean (first (parses-to "false")))))

  (testing "nil is Nil, not Identifier"
    (is (= :Nil (first (parses-to "nil")))))

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
    (is (= :Identifier (first (parses-to "android"))))
    (is (= :Identifier (first (parses-to "notify"))))
    (is (= :Identifier (first (parses-to "isoformat"))))
    (is (= :Identifier (first (parses-to "inbound"))))
    (is (= :Identifier (first (parses-to "trueness"))))
    (is (= :Identifier (first (parses-to "falsehood"))))
    (is (= :Identifier (first (parses-to "nilable"))))))

;; ==========================================================================
;; SECTION 7: Wildcard _
;; ==========================================================================

(deftest parse-wildcard
  (testing "bare underscore"
    (is (= [:Wildcard "_"] (parses-to "_"))))

  (testing "underscore is NOT an identifier"
    (is (not= :Identifier (first (parses-to "_"))))))

;; ==========================================================================
;; SECTION 8: Arithmetic Operators
;; ==========================================================================

(deftest parse-addition
  (testing "simple addition"
    (is (= [:AddExpr [:Integer "2"] [:AddOp "+"] [:Integer "3"]]
           (parses-to "2 + 3"))))

  (testing "chained addition is flat"
    (let [tree (parses-to "1 + 2 + 3")]
      (is (= :AddExpr (first tree)))
      ;; flat structure: [:AddExpr int op int op int]
      (is (= 6 (count tree))))))

(deftest parse-subtraction
  (testing "simple subtraction"
    (is (= [:AddExpr [:Integer "10"] [:AddOp "-"] [:Integer "3"]]
           (parses-to "10 - 3")))))

(deftest parse-multiplication
  (testing "simple multiplication"
    (is (= [:MulExpr [:Integer "4"] [:MulOp "*"] [:Integer "5"]]
           (parses-to "4 * 5")))))

(deftest parse-division
  (testing "simple division"
    (is (= [:MulExpr [:Integer "10"] [:MulOp "/"] [:Integer "2"]]
           (parses-to "10 / 2")))))

(deftest parse-modulo
  (testing "modulo operator"
    (is (= [:MulExpr [:Integer "10"] [:MulOp "%"] [:Integer "3"]]
           (parses-to "10 % 3")))))

(deftest parse-string-concatenation
  (testing "string + string is valid (same AddExpr, eval decides semantics)"
    (is (= :AddExpr (first (parses-to "\"hello\" + \" world\""))))))

;; ==========================================================================
;; SECTION 9: Operator Precedence
;; ==========================================================================

(deftest parse-arithmetic-precedence
  (testing "multiplication before addition: 2 + 3 * 4"
    (let [tree (parses-to "2 + 3 * 4")]
      (is (= :AddExpr (first tree)))
      (is (= [:Integer "2"] (nth tree 1)))
      (is (= :MulExpr (first (nth tree 3))))))

  (testing "parentheses override precedence: (2 + 3) * 4"
    (let [tree (parses-to "(2 + 3) * 4")]
      (is (= :MulExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))
      (is (= [:Integer "4"] (nth tree 3)))))

  (testing "nested parentheses"
    (is (parses-to "((2 + 3) * (4 - 1))")))

  (testing "modulo same precedence as * /"
    (let [tree (parses-to "10 + 7 % 3")]
      (is (= :AddExpr (first tree)))
      (is (= :MulExpr (first (nth tree 3)))))))

(deftest parse-comparison-after-arithmetic
  (testing "comparison wraps arithmetic: 2 + 3 > 4"
    (let [tree (parses-to "2 + 3 > 4")]
      (is (= :CompExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))
      (is (= [:CompOp ">"] (nth tree 2)))
      (is (= [:Integer "4"] (nth tree 3))))))

(deftest parse-logical-after-comparison
  (testing "and wraps comparisons: 5 > 3 and 2 < 4"
    (let [tree (parses-to "5 > 3 and 2 < 4")]
      (is (= :AndExpr (first tree)))
      (is (= :CompExpr (first (nth tree 1))))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "or wraps and: true and false or true"
    (let [tree (parses-to "true and false or true")]
      (is (= :OrExpr (first tree)))
      (is (= :AndExpr (first (nth tree 1))))))

  (testing "full precedence chain: 2 + 3 * 4 > 10 and not false"
    (let [tree (parses-to "2 + 3 * 4 > 10 and not false")]
      (is (= :AndExpr (first tree))))))

;; ==========================================================================
;; SECTION 10: Unary Minus
;; ==========================================================================

(deftest parse-unary-minus
  (testing "negative integer literal"
    (is (= [:UnaryExpr "-" [:Integer "42"]] (parses-to "-42"))))

  (testing "negative float"
    (is (= [:UnaryExpr "-" [:Float "3.14"]] (parses-to "-3.14"))))

  (testing "negation of identifier"
    (is (= [:UnaryExpr "-" [:Identifier "x"]] (parses-to "-x"))))

  (testing "negation of parenthesized expression"
    (let [tree (parses-to "-(3 + 4)")]
      (is (= :UnaryExpr (first tree)))
      (is (= :AddExpr (first (nth tree 2))))))

  (testing "unary minus with space is parse error"
    (is (parse-fails? "- 10")))

  (testing "double negative is parse error"
    (is (parse-fails? "--5"))))

;; ==========================================================================
;; SECTION 11: Comparison Operators
;; ==========================================================================

(deftest parse-comparison-operators
  (testing "equality"
    (is (= [:CompExpr [:Integer "5"] [:CompOp "="] [:Integer "5"]]
           (parses-to "5 = 5"))))

  (testing "inequality"
    (is (= [:CompOp "!="] (nth (parses-to "5 != 3") 2))))

  (testing "greater than"
    (is (= [:CompOp ">"] (nth (parses-to "5 > 3") 2))))

  (testing "less than"
    (is (= [:CompOp "<"] (nth (parses-to "3 < 5") 2))))

  (testing "greater than or equal"
    (is (= [:CompOp ">="] (nth (parses-to "5 >= 5") 2))))

  (testing "less than or equal"
    (is (= [:CompOp "<="] (nth (parses-to "5 <= 5") 2))))

  (testing "string comparison"
    (is (= :CompExpr (first (parses-to "\"apple\" < \"banana\"")))))

  (testing "comparison with nil"
    (is (= :CompExpr (first (parses-to "5 > nil")))))

  (testing "comparison is non-associative — chaining is parse error"
    (is (parse-fails? "1 < 2 < 3"))))

;; ==========================================================================
;; SECTION 12: Logical Operators
;; ==========================================================================

(deftest parse-logical-and
  (testing "simple and"
    (is (= [:AndExpr [:Boolean "true"] [:Boolean "false"]]
           (parses-to "true and false"))))

  (testing "chained and is flat"
    (let [tree (parses-to "a and b and c")]
      (is (= :AndExpr (first tree)))
      (is (= 4 (count tree))))))

(deftest parse-logical-or
  (testing "simple or"
    (is (= [:OrExpr [:Boolean "false"] [:Boolean "true"]]
           (parses-to "false or true"))))

  (testing "chained or is flat"
    (let [tree (parses-to "a or b or c")]
      (is (= :OrExpr (first tree)))
      (is (= 4 (count tree))))))

(deftest parse-logical-not
  (testing "not true"
    (is (= [:NotExpr "not" [:Boolean "true"]] (parses-to "not true"))))

  (testing "not false"
    (is (= :NotExpr (first (parses-to "not false")))))

  ;; PRD precedence: comparisons > not > ?? > and > or
  ;; So "not 5 > 3" = not(5 > 3)
  (testing "not wraps comparison (not has lower precedence)"
    (let [tree (parses-to "not 5 > 3")]
      (is (= :NotExpr (first tree)))
      (is (= "not" (second tree)))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "not with parenthesized expression"
    (is (= :NotExpr (first (parses-to "not (5 > 3)"))))))

(deftest parse-logical-precedence
  ;; PRD: not > ?? > and > or

  (testing "and binds tighter than or: true and false or true"
    ;; -> OrExpr(AndExpr(true, false), true)
    (let [tree (parses-to "true and false or true")]
      (is (= :OrExpr (first tree)))
      (is (= :AndExpr (first (nth tree 1))))
      (is (= [:Boolean "true"] (nth tree 2)))))

  (testing "not > and > or: not true or false"
    ;; -> OrExpr(NotExpr(not, true), false)
    (let [tree (parses-to "not true or false")]
      (is (= :OrExpr (first tree)))
      (is (= :NotExpr (first (nth tree 1))))))

  (testing "not > and: not true and false"
    ;; -> AndExpr(NotExpr(not, true), false)
    (let [tree (parses-to "not true and false")]
      (is (= :AndExpr (first tree)))
      (is (= :NotExpr (first (nth tree 1)))))))

;; ==========================================================================
;; SECTION 13: In Operator
;; ==========================================================================

(deftest parse-in-operator
  (testing "value in identifier"
    (is (= [:InExpr [:String "x"] [:Identifier "tags"]]
           (parses-to "\"x\" in tags"))))

  ;; PRD precedence: + - > in > comparisons
  (testing "in has lower precedence than arithmetic"
    (let [tree (parses-to "x + 1 in list")]
      (is (= :InExpr (first tree)))
      (is (= :AddExpr (first (nth tree 1))))))

  (testing "in has higher precedence than comparison"
    (let [tree (parses-to "\"x\" in tags = true")]
      (is (= :CompExpr (first tree)))
      (is (= :InExpr (first (nth tree 1))))))

  (testing "nil in collection"
    (is (= :InExpr (first (parses-to "nil in items")))))

  (testing "key in object"
    (is (= :InExpr (first (parses-to "\"name\" in user"))))))

;; ==========================================================================
;; SECTION 14: Nil Coalescing (??)
;; ==========================================================================

(deftest parse-nil-coalescing
  (testing "simple nil coalescing"
    (is (= [:NilCoalesce [:Identifier "value"] [:Identifier "default"]]
           (parses-to "value ?? default"))))

  (testing "chained nil coalescing is flat"
    (let [tree (parses-to "a ?? b ?? c")]
      (is (= :NilCoalesce (first tree)))
      (is (= 4 (count tree)))))

  ;; PRD precedence: comparisons > not > ?? > and > or
  ;; So "x > 5 ?? false" = NilCoalesce(CompExpr(x > 5), false)
  (testing "?? wraps comparison result (comp > not > ??)"
    (let [tree (parses-to "x > 5 ?? false")]
      (is (= :NilCoalesce (first tree)))
      (is (= :CompExpr (first (nth tree 1)))))))

;; ==========================================================================
;; SECTION 15: Parenthesized Expressions
;; ==========================================================================

(deftest parse-parenthesized-expressions
  (testing "simple grouping — parens are transparent"
    (is (= [:Integer "42"] (parses-to "(42)"))))

  (testing "arithmetic grouping"
    (is (= :AddExpr (first (parses-to "(2 + 3)")))))

  (testing "nested parentheses collapse"
    (is (= [:Integer "42"] (parses-to "((42))"))))

  (testing "deep nesting"
    (is (parses-to "(((2 + 3)))")))

  (testing "empty parentheses are a parse error"
    (is (parse-fails? "()"))))

;; ==========================================================================
;; SECTION 16: Comments
;; ==========================================================================

(deftest parse-comments
  (testing "expression followed by line comment"
    (is (= [:Integer "42"] (parses-to "42 // this is a comment"))))

  (testing "comment before expression"
    (is (parses-to "// header comment\n42")))

  (testing "comment between expressions"
    (is (parses-to "x is 5\n// separator\ny is 10")))

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
    (is (= :Object (first (parses-to "{}"))))))

(deftest parse-object-with-fields
  (testing "single field"
    (is (= [:Object [:StandardEntry [:Identifier "name"] [:String "Alice"]]]
           (parses-to "{name: \"Alice\"}"))))

  (testing "multiple space-separated fields"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "age"] [:Integer "25"]]
            [:StandardEntry [:Identifier "active"] [:Boolean "true"]]]
           (parses-to "{name: \"Alice\" age: 25 active: true}"))))

  (testing "hyphenated keys"
    (is (= [:Object
            [:StandardEntry [:Identifier "first-name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "last-name"] [:String "Smith"]]]
           (parses-to "{first-name: \"Alice\" last-name: \"Smith\"}"))))

  (testing "keys with underscore"
    (is (= [:Object [:StandardEntry [:Identifier "user_name"] [:String "Alice"]]]
           (parses-to "{user_name: \"Alice\"}"))))

  (testing "keys with digits"
    (is (= [:Object
            [:StandardEntry [:Identifier "level2"] [:String "advanced"]]
            [:StandardEntry [:Identifier "x1"] [:Integer "10"]]]
           (parses-to "{level2: \"advanced\" x1: 10}"))))

  (testing "nested objects"
    (is (= [:Object [:StandardEntry [:Identifier "a"]
                     [:Object [:StandardEntry [:Identifier "b"]
                               [:Object [:StandardEntry [:Identifier "c"] [:Integer "1"]]]]]]]
           (parses-to "{a: {b: {c: 1}}}"))))

  (testing "object with nil value"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "address"] [:Nil "nil"]]]
           (parses-to "{name: \"Alice\" address: nil}"))))

  (testing "object with expression values"
    (let [tree (parses-to "{doubled: x * 2 name: \"Alice\"}")]
      (is (= :Object (first tree)))
      (is (= :StandardEntry (first (nth tree 1))))
      (is (= :StandardEntry (first (nth tree 2))))))

  (testing "function as object value"
    (let [tree (parses-to "{transform: [x -> x * 2]}")]
      (is (= :Object (first tree)))
      (is (= :FnDef (first (nth (nth tree 1) 2))))))

  (testing "multi-line object"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "age"] [:Integer "25"]]]
           (parses-to "{\n  name: \"Alice\"\n  age: 25\n}"))))

  (testing "extra whitespace inside braces"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "age"] [:Integer "25"]]]
           (parses-to "{  name:   \"Alice\"   age:   25  }"))))

  (testing "duplicate keys parse successfully (last wins at eval time)"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:String "Alice"]]
            [:StandardEntry [:Identifier "name"] [:String "Bob"]]]
           (parses-to "{name: \"Alice\" name: \"Bob\"}")))))

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
    (is (= :List (first (parses-to "[]"))))))

(deftest parse-list-with-elements
  (testing "integer list"
    (is (= [:List [:Integer "1"] [:Integer "2"] [:Integer "3"] [:Integer "4"] [:Integer "5"]]
           (parses-to "[1 2 3 4 5]"))))

  (testing "string list"
    (is (= [:List [:String "Alice"] [:String "Bob"] [:String "Charlie"]]
           (parses-to "[\"Alice\" \"Bob\" \"Charlie\"]"))))

  (testing "mixed types"
    (is (= [:List [:String "Alice"] [:Integer "25"] [:Boolean "true"] [:Nil "nil"]]
           (parses-to "[\"Alice\" 25 true nil]"))))

  (testing "nested lists"
    (is (= [:List
            [:List [:Integer "1"] [:Integer "2"]]
            [:List [:Integer "3"] [:Integer "4"]]
            [:List [:Integer "5"] [:Integer "6"]]]
           (parses-to "[[1 2] [3 4] [5 6]]"))))

  (testing "list containing objects"
    (is (= [:List
            [:Object [:StandardEntry [:Identifier "a"] [:Integer "1"]]]
            [:Object [:StandardEntry [:Identifier "a"] [:Integer "2"]]]
            [:Object [:StandardEntry [:Identifier "a"] [:Integer "3"]]]]
           (parses-to "[{a: 1} {a: 2} {a: 3}]"))))

  (testing "multi-line list"
    (is (= [:List [:Integer "1"] [:Integer "2"] [:Integer "3"]]
           (parses-to "[\n  1\n  2\n  3\n]"))))

  (testing "extra whitespace"
    (is (= [:List [:Integer "1"] [:Integer "2"] [:Integer "3"]]
           (parses-to "[  1   2   3  ]"))))

  (testing "single element"
    (is (= [:List [:Integer "42"]]
           (parses-to "[42]"))))

  (testing "deeply nested"
    (is (= [:List [:List [:List [:Integer "1"]]]]
           (parses-to "[[[1]]]")))))

(deftest parse-list-negative-cases
  (testing "commas between elements are a parse error"
    (is (parse-fails? "[1, 2, 3]"))))

;; ==========================================================================
;; SECTION 19: Field Access (Dot Notation)
;; ==========================================================================

(deftest parse-field-access
  (testing "simple field access"
    (is (= [:FieldAccess [:Identifier "user"] [:FieldName "name"]]
           (parses-to "user.name"))))

  (testing "chained field access"
    (is (= [:FieldAccess [:Identifier "user"] [:FieldName "profile"] [:FieldName "address"] [:FieldName "city"]]
           (parses-to "user.profile.address.city"))))

  (testing "wildcard field access"
    (is (= [:FieldAccess [:Wildcard "_"] [:FieldName "name"]]
           (parses-to "_.name"))))

  (testing "deeply nested wildcard access"
    (is (= [:FieldAccess [:Wildcard "_"] [:FieldName "profile"] [:FieldName "address"] [:FieldName "city"]]
           (parses-to "_.profile.address.city"))))

  (testing "field access on literal object"
    (is (= [:FieldAccess [:Object [:StandardEntry [:Identifier "name"] [:String "Alice"]]] [:FieldName "name"]]
           (parses-to "{name: \"Alice\"}.name"))))

  (testing "field access on parenthesized expression"
    (is (= [:FieldAccess [:Identifier "get-user"] [:FieldName "name"]]
           (parses-to "(get-user).name")))))

;; ==========================================================================
;; SECTION 20: Binding (is)
;; ==========================================================================

(deftest parse-simple-binding
  (testing "bind integer"
    (is (= [:Binding [:Identifier "x"] [:Integer "42"]]
           (parses-to "x is 42"))))

  (testing "bind string"
    (is (= [:Binding [:Identifier "name"] [:String "Alice"]]
           (parses-to "name is \"Alice\""))))

  (testing "bind boolean"
    (is (= [:Binding [:Identifier "active"] [:Boolean "true"]]
           (parses-to "active is true"))))

  (testing "bind nil"
    (is (= [:Binding [:Identifier "nothing"] [:Nil "nil"]]
           (parses-to "nothing is nil"))))

  (testing "bind to expression"
    (is (= [:Binding [:Identifier "total"] [:AddExpr [:Integer "3"] [:AddOp "+"] [:Integer "4"]]]
           (parses-to "total is 3 + 4"))))

  (testing "bind to object"
    (is (= [:Binding [:Identifier "user"]
            [:Object
             [:StandardEntry [:Identifier "name"] [:String "Alice"]]
             [:StandardEntry [:Identifier "age"] [:Integer "30"]]]]
           (parses-to "user is {name: \"Alice\" age: 30}"))))

  (testing "bind to list"
    (is (= [:Binding [:Identifier "nums"]
            [:List [:Integer "1"] [:Integer "2"] [:Integer "3"] [:Integer "4"] [:Integer "5"]]]
           (parses-to "nums is [1 2 3 4 5]"))))

  (testing "bind function"
    (is (= [:Binding [:Identifier "double"]
            [:FnDef [:Identifier "x"] [:MulExpr [:Identifier "x"] [:MulOp "*"] [:Integer "2"]]]]
           (parses-to "double is [x -> x * 2]"))))

  (testing "bind pipeline result"
    (is (= [:Binding [:Identifier "result"]
            [:Pipeline [:Identifier "users"]
             [:FnCall [:Identifier "filter"] [:FieldAccess [:Wildcard "_"] [:FieldName "active"]]]
             [:Identifier "count"]]]
           (parses-to "result is users |> filter _.active |> count"))))

  (testing "hyphenated binding name"
    (is (= [:Binding [:Identifier "my-var"] [:Integer "10"]]
           (parses-to "my-var is 10"))))

  (testing "predicate binding"
    (let [tree (parses-to "even? is [n -> n % 2 = 0]")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "even?"] (nth tree 1)))
      (is (= :FnDef (first (nth tree 2)))))))

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
    (is (= [:FnDef [:Identifier "x"] [:MulExpr [:Identifier "x"] [:MulOp "*"] [:Integer "2"]]]
           (parses-to "[x -> x * 2]"))))

  (testing "multi-parameter function"
    (is (= [:FnDef [:FnParams [:Identifier "a"] [:Identifier "b"]]
            [:AddExpr [:Identifier "a"] [:AddOp "+"] [:Identifier "b"]]]
           (parses-to "[a b -> a + b]"))))

  (testing "zero-parameter function"
    (is (= [:FnDef [:Integer "42"]]
           (parses-to "[-> 42]"))))

  (testing "complex body expression"
    (let [tree (parses-to "[a b c x -> a * x * x + b * x + c]")]
      (is (= :FnDef (first tree)))
      (is (= :FnParams (first (nth tree 1))))
      (is (= :AddExpr (first (nth tree 2))))))

  (testing "function with string body"
    (is (= [:FnDef [:Identifier "name"]
            [:FnCall [:Identifier "format"] [:String "Hello, %s!"] [:Identifier "name"]]]
           (parses-to "[name -> format \"Hello, %s!\" name]"))))

  (testing "predicate function"
    (let [tree (parses-to "[n -> n % 2 = 0]")]
      (is (= :FnDef (first tree)))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "variadic function"
    (is (= [:FnDef [:FnParams [:Identifier "a"] [:Identifier "b"] [:Identifier "rest"]]
            [:Identifier "rest"]]
           (parses-to "[a b & rest -> rest]"))))

  (testing "variadic with only rest"
    (is (= [:FnDef [:Identifier "nums"] [:Identifier "nums"]]
           (parses-to "[& nums -> nums]"))))

  (testing "function returning object"
    (is (= [:FnDef [:FnParams [:Identifier "x"] [:Identifier "y"]]
            [:Object
             [:StandardEntry [:Identifier "x"] [:Identifier "x"]]
             [:StandardEntry [:Identifier "y"] [:Identifier "y"]]]]
           (parses-to "[x y -> {x: x y: y}]"))))

  (testing "function returning list"
    (is (= [:FnDef [:FnParams [:Identifier "a"] [:Identifier "b"]]
            [:List [:Identifier "a"] [:Identifier "b"]]]
           (parses-to "[a b -> [a b]]"))))

  (testing "nested function (closure)"
    (is (= [:FnDef [:Identifier "n"]
            [:FnDef [:Identifier "x"] [:AddExpr [:Identifier "x"] [:AddOp "+"] [:Identifier "n"]]]]
           (parses-to "[n -> [x -> x + n]]"))))

  (testing "deeply nested closure"
    (let [tree (parses-to "[factor -> [offset -> [x -> x * factor + offset]]]")]
      (is (= :FnDef (first tree)))
      (is (= :FnDef (first (nth tree 2))))
      (is (= :FnDef (first (nth (nth tree 2) 2)))))))

(deftest parse-multi-expression-function-body
  (testing "function body with is-bindings and return value"
    (let [tree (parses-to "[a b ->\n  a2 is a * a\n  b2 is b * b\n  a2 + b2\n]")]
      (is (= :FnDef (first tree)))
      (is (= :FnParams (first (nth tree 1))))
      (is (= :FnBody (first (nth tree 2))))))

  (testing "function body with binding and pipeline"
    (let [tree (parses-to "[data ->\n  filtered is data |> filter _.active\n  total is filtered |> count\n  {items: filtered total: total}\n]")]
      (is (= :FnDef (first tree)))
      (is (= :FnBody (first (nth tree 2)))))))

(deftest parse-function-call
  (testing "simple function call"
    (is (= [:FnCall [:Identifier "double"] [:Integer "5"]]
           (parses-to "double 5"))))

  (testing "multi-arg function call"
    (is (= [:FnCall [:Identifier "add"] [:Integer "3"] [:Integer "4"]]
           (parses-to "add 3 4"))))

  ;; FnCall with zero args via parens: CallTarget <'('> _ <')'>
  ;; Parens are hidden, only CallTarget remains → single child → collapses to Identifier
  (testing "zero-arg function call with parens"
    (is (= [:Identifier "f"] (parses-to "f()"))))

  (testing "function call with expression arg in parens"
    (is (= [:FnCall [:Identifier "f"] [:AddExpr [:Integer "2"] [:AddOp "+"] [:Integer "3"]]]
           (parses-to "f (2 + 3)"))))

  (testing "function call with string arg"
    (is (= [:FnCall [:Identifier "format"] [:String "Hello, %s!"] [:Identifier "name"]]
           (parses-to "format \"Hello, %s!\" name"))))

  (testing "higher-order: calling result of function call"
    (let [tree (parses-to "(make-adder 5) 10")]
      (is (= :FnCall (first tree))))))

(deftest parse-list-vs-function-disambiguation
  (testing "[] is an empty list, not a function"
    (is (= :List (first (parses-to "[]")))))

  (testing "[1 2 3] is a list"
    (is (= :List (first (parses-to "[1 2 3]")))))

  (testing "[x -> x] is a function (has arrow)"
    (is (= :FnDef (first (parses-to "[x -> x]")))))

  (testing "[-> 42] is a zero-param function"
    (is (= :FnDef (first (parses-to "[-> 42]")))))

  (testing "[42] is a single-element list"
    (is (= :List (first (parses-to "[42]")))))

  (testing "[x] is a single-element list (identifier as element)"
    (is (= :List (first (parses-to "[x]"))))))

(deftest parse-destructuring-in-function-params
  ;; Single-field {name} destructuring collapses through DestructObjPattern → Identifier
  (testing "object destructuring in params"
    (is (= [:FnDef [:Identifier "name"]
            [:FnCall [:Identifier "format"] [:String "Hello, %s!"] [:Identifier "name"]]]
           (parses-to "[{name} -> format \"Hello, %s!\" name]"))))

  (testing "object destructuring with rename in params"
    (let [tree (parses-to "[{age: a1} {age: a2} -> a1 + a2]")]
      (is (= :FnDef (first tree)))
      (is (= :FnParams (first (nth tree 1))))))

  (testing "list destructuring in params"
    (let [tree (parses-to "[[first & _] -> first]")]
      (is (= :FnDef (first tree)))
      (is (= :DestructListElems (first (nth tree 1))))))

  (testing "mixed regular and destructured params"
    (let [tree (parses-to "[label {name age} -> label]")]
      (is (= :FnDef (first tree)))
      (is (= :FnParams (first (nth tree 1))))))

  (testing "destructured param in pipeline lambda"
    (let [tree (parses-to "users |> map [{name age} -> {display: name years: age}]")]
      (is (= :Pipeline (first tree))))))

(deftest parse-partial-application
  (testing "partial application via partial function"
    (let [tree (parses-to "add5 is partial add 5")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "add5"] (nth tree 1)))
      (is (= :FnCall (first (nth tree 2)))))))

;; ==========================================================================
;; SECTION 22: Pipeline (|>)
;; ==========================================================================

(deftest parse-pipeline
  (testing "single step pipeline"
    (is (= [:Pipeline [:List [:Integer "1"] [:Integer "2"] [:Integer "3"]] [:Identifier "count"]]
           (parses-to "[1 2 3] |> count"))))

  (testing "multi-step inline pipeline"
    (is (= [:Pipeline [:Identifier "users"]
            [:FnCall [:Identifier "filter"] [:FieldAccess [:Wildcard "_"] [:FieldName "active"]]]
            [:Identifier "count"]]
           (parses-to "users |> filter _.active |> count"))))

  (testing "pipeline step with arguments"
    (is (= [:Pipeline [:Identifier "data"] [:FnCall [:Identifier "take"] [:Integer "10"]]]
           (parses-to "data |> take 10"))))

  (testing "pipeline step with multiple arguments"
    (is (= [:Pipeline [:Identifier "text"]
            [:FnCall [:Identifier "replace"] [:String "old"] [:String "new"]]]
           (parses-to "text |> replace \"old\" \"new\""))))

  (testing "pipeline step with no arguments"
    (is (= [:Pipeline [:Identifier "items"] [:Identifier "reverse"] [:Identifier "distinct"]]
           (parses-to "items |> reverse |> distinct"))))

  (testing "multi-line pipeline (|> at line start)"
    (let [tree (parses-to "users\n|> filter _.active\n|> map _.name\n|> sort")]
      (is (= :Pipeline (first tree)))
      (is (= 5 (count tree)))))

  (testing "pipeline with anonymous function"
    (let [tree (parses-to "users |> filter [u -> u.age > 18]")]
      (is (= :Pipeline (first tree)))
      (is (= :FnCall (first (nth tree 2))))
      (is (= :FnDef (first (nth (nth tree 2) 2))))))

  (testing "pipeline with wildcard expression"
    (let [tree (parses-to "users |> filter _.age > 18")]
      (is (= :Pipeline (first tree)))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "pipeline with bare wildcard (whole element)"
    (let [tree (parses-to "items |> filter _ != nil")]
      (is (= :Pipeline (first tree)))
      (is (= :CompExpr (first (nth tree 2))))))

  (testing "pipeline with wildcard in arithmetic"
    (let [tree (parses-to "numbers |> map _ * 2 + 1")]
      (is (= :Pipeline (first tree)))
      (is (= :AddExpr (first (nth tree 2))))))

  (testing "pipeline result assigned with is"
    (is (= [:Binding [:Identifier "result"]
            [:Pipeline [:Identifier "users"]
             [:FnCall [:Identifier "filter"] [:FieldAccess [:Wildcard "_"] [:FieldName "active"]]]
             [:Identifier "count"]]]
           (parses-to "result is users |> filter _.active |> count"))))

  (testing "pipeline result destructured"
    (let [tree (parses-to "[first & rest] is items |> sort |> reverse")]
      (is (= :Binding (first tree)))
      (is (= :Pipeline (first (nth tree 2)))))))

(deftest parse-pipeline-negative-cases
  (testing "empty pipeline — no step after |>"
    (is (parse-fails? "data |>")))

  (testing "consecutive pipes with no function"
    (is (parse-fails? "data |> |> count"))))

(deftest parse-sourceless-pipeline
  (testing "sourceless pipeline creates reusable transformer"
    (is (= [:SourcelessPipeline
            [:FnCall [:Identifier "filter"] [:FieldAccess [:Wildcard "_"] [:FieldName "active"]]]
            [:FnCall [:Identifier "map"] [:FieldAccess [:Wildcard "_"] [:FieldName "name"]]]
            [:Identifier "sort"]]
           (parses-to "|> filter _.active |> map _.name |> sort"))))

  (testing "sourceless pipeline assigned with is"
    (let [tree (parses-to "normalize is |> filter _.active |> map _.name |> sort")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "normalize"] (nth tree 1)))
      (is (= :SourcelessPipeline (first (nth tree 2))))))

  (testing "applying a named pipeline"
    (is (= [:Pipeline [:Identifier "users"] [:Identifier "normalize"]]
           (parses-to "users |> normalize")))))

(deftest parse-nested-pipeline
  (testing "nested pipeline in map"
    (let [tree (parses-to "users\n|> map {\n  name: _.name\n  top-scores: _.scores |> filter [s -> s > 80] |> take 3\n}")]
      (is (= :Pipeline (first tree)))
      (is (= [:Identifier "users"] (nth tree 1)))
      (is (= :FnCall (first (nth tree 2)))))))

(deftest parse-pipeline-with-side-effects
  (testing "side-effect functions in pipeline are passthrough"
    (let [tree (parses-to "data\n|> log! \"start\"\n|> process\n|> log! \"end\"\n|> save! \"output.json\"")]
      (is (= :Pipeline (first tree)))
      (is (= 6 (count tree)))
      (is (= [:Identifier "data"] (nth tree 1))))))

;; ==========================================================================
;; SECTION 23: Pattern Matching — Guards
;; ==========================================================================

(deftest parse-guard-expression
  (testing "simple guard block"
    (is (= [:GuardBlock
            [:GuardArm [:CompExpr [:Identifier "x"] [:CompOp ">"] [:Integer "5"]] [:String "big"]]
            [:GuardArm [:Wildcard "_"] [:String "small"]]]
           (parses-to "| x > 5 -> \"big\" | _ -> \"small\""))))

  (testing "multi-line guard block"
    (let [tree (parses-to "| amount > 1000 -> \"gold\"\n| amount > 100 -> \"silver\"\n| _ -> \"bronze\"")]
      (is (= :GuardBlock (first tree)))
      (is (= 4 (count tree)))))

  (testing "guard with logical operators"
    (let [tree (parses-to "| role = \"admin\" or role = \"superadmin\" -> \"full\" | _ -> \"read\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :OrExpr (first (nth (nth tree 1) 1))))))

  (testing "guard with and-chained conditions"
    (let [tree (parses-to "| age >= 0 and age < 13 -> \"child\"\n| age >= 13 and age < 18 -> \"teen\"\n| _ -> \"adult\"")]
      (is (= :GuardBlock (first tree)))
      (is (= 4 (count tree)))
      (is (= :AndExpr (first (nth (nth tree 1) 1))))))

  (testing "guard with function call in condition"
    (let [tree (parses-to "| even? x -> \"even\" | _ -> \"odd\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :FnCall (first (nth (nth tree 1) 1))))))

  (testing "guard as value of is"
    (let [tree (parses-to "tier is\n  | amount > 1000 -> \"gold\"\n  | _ -> \"bronze\"")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "tier"] (nth tree 1)))
      (is (= :GuardBlock (first (nth tree 2))))))

  (testing "single-line guard after is"
    (let [tree (parses-to "tier is | x > 5 -> \"high\" | _ -> \"low\"")]
      (is (= :Binding (first tree)))
      (is (= :GuardBlock (first (nth tree 2))))))

  (testing "guard result can be an expression"
    (let [tree (parses-to "| x > 0 -> x * 2 + 1 | _ -> 0")]
      (is (= :GuardBlock (first tree)))
      (is (= :AddExpr (first (nth (nth tree 1) 2))))))

  (testing "guard result can be an object"
    (let [tree (parses-to "| x > 0 -> {value: x} | _ -> {value: 0}")]
      (is (= :GuardBlock (first tree)))
      (is (= :Object (first (nth (nth tree 1) 2))))))

  (testing "guard result can be a list"
    (let [tree (parses-to "| x > 0 -> [x] | _ -> []")]
      (is (= :GuardBlock (first tree)))
      (is (= :List (first (nth (nth tree 1) 2)))))))

(deftest parse-guard-in-function
  (testing "function body with guards"
    (let [tree (parses-to "[x ->\n  | x > 0 -> \"positive\"\n  | _ -> \"non-positive\"\n]")]
      (is (= :FnDef (first tree)))
      (is (= [:Identifier "x"] (nth tree 1)))
      (is (= :GuardBlock (first (nth tree 2))))))

  (testing "function with complex guard body"
    (let [tree (parses-to "[x ->\n  | x > 0 -> \"positive\"\n  | x = 0 -> \"zero\"\n  | _ -> \"negative\"\n]")]
      (is (= :FnDef (first tree)))
      (is (= :GuardBlock (first (nth tree 2))))
      (is (= 4 (count (nth tree 2)))))))

(deftest parse-guard-in-object-field
  (testing "guard as object field value in pipeline"
    (let [tree (parses-to "users |> map {\n  name: _.name\n  tier:\n    | _.spending > 1000 -> \"gold\"\n    | _ -> \"bronze\"\n}")]
      (is (= :Pipeline (first tree)))
      (is (= [:Identifier "users"] (nth tree 1))))))

(deftest parse-structural-pattern
  ;; Guard patterns: {obj} parses as Object via OrExpr, [] as List,
  ;; only multi-element destructuring survives simplify
  (testing "object pattern"
    (let [tree (parses-to "| {type: \"book\"} -> \"book\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :Object (first (nth (nth tree 1) 1))))))

  (testing "object pattern with multiple fields"
    (let [tree (parses-to "| {type: \"book\" format: \"hardcover\"} -> \"hardcover book\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :Object (first (nth (nth tree 1) 1))))))

  (testing "empty list pattern"
    (let [tree (parses-to "| [] -> \"empty\" | _ -> \"nonempty\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :List (first (nth (nth tree 1) 1))))))

  (testing "single-element list pattern — [x] collapses to Identifier"
    (let [tree (parses-to "| [x] -> \"single\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= [:Identifier "x"] (nth (nth tree 1) 1)))))

  (testing "list pattern with rest"
    (let [tree (parses-to "| [x & rest] -> \"many\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :DestructListElems (first (nth (nth tree 1) 1))))))

  (testing "pattern with when clause"
    (let [tree (parses-to "| {type: \"book\" pages: p} when p > 500 -> \"epic\" | _ -> \"book\"")]
      (is (= :GuardBlock (first tree)))
      ;; First arm: pattern + when-condition + result = 3 children in GuardArm
      (is (= 4 (count (nth tree 1))))))

  (testing "when clause with compound condition"
    (let [tree (parses-to "| {type: \"movie\" rating: r year: y} when r > 8 and y > 2000 -> \"modern-classic\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :AndExpr (first (nth (nth tree 1) 2))))))

  (testing "nil pattern"
    (let [tree (parses-to "| nil -> \"nothing\" | _ -> \"something\"")]
      (is (= :GuardBlock (first tree)))
      (is (= [:Nil "nil"] (nth (nth tree 1) 1)))))

  (testing "literal integer patterns"
    (let [tree (parses-to "| 0 -> \"zero\" | 1 -> \"one\" | 42 -> \"answer\" | _ -> \"other\"")]
      (is (= :GuardBlock (first tree)))
      (is (= 5 (count tree)))
      (is (= [:Integer "0"] (nth (nth tree 1) 1)))))

  (testing "literal string patterns"
    (let [tree (parses-to "| \"ok\" -> \"success\" | \"error\" -> \"failure\" | _ -> \"unknown\"")]
      (is (= :GuardBlock (first tree)))
      (is (= [:String "ok"] (nth (nth tree 1) 1)))))

  (testing "literal boolean patterns"
    (let [tree (parses-to "| true -> \"yes\" | false -> \"no\"")]
      (is (= :GuardBlock (first tree)))
      (is (= [:Boolean "true"] (nth (nth tree 1) 1)))))

  (testing "wildcard in structural pattern matches any value"
    (let [tree (parses-to "| {type: _} -> \"has-type\" | _ -> \"no-type\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :Object (first (nth (nth tree 1) 1))))))

  (testing "variable binding in pattern — single {name: n} collapses to DestructObjField"
    (let [tree (parses-to "| {name: n} -> format \"Hello, %s!\" n | _ -> \"unknown\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :DestructObjField (first (nth (nth tree 1) 1))))))

  (testing "nested object pattern — single field collapses"
    (let [tree (parses-to "| {address: {city: c}} -> c | _ -> \"unknown\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :DestructObjField (first (nth (nth tree 1) 1))))))

  (testing "list-of-objects pattern"
    (let [tree (parses-to "| [{name: n} & _] -> n | _ -> \"empty\"")]
      (is (= :GuardBlock (first tree)))
      (is (= :DestructListElems (first (nth (nth tree 1) 1))))))

  (testing "literal value in object field pattern"
    (let [tree (parses-to "| {status: 200} -> \"ok\" | {status: 404} -> \"not found\" | _ -> \"error\"")]
      (is (= :GuardBlock (first tree)))
      (is (= 4 (count tree))))))

(deftest parse-structural-pattern-negative-cases
  (testing "nested guards are NOT allowed"
    (is (parse-fails? "| x > 0 -> | x > 10 -> \"big\" | _ -> \"small\" | _ -> \"negative\""))))

;; ==========================================================================
;; SECTION 24: Destructuring
;; ==========================================================================

(deftest parse-object-destructuring
  (testing "simple object destructuring"
    (is (= [:Binding [:DestructObjPattern [:Identifier "name"] [:Identifier "age"]] [:Identifier "user"]]
           (parses-to "{name age} is user"))))

  (testing "destructuring with rename"
    (is (= [:Binding
            [:DestructObjPattern
             [:DestructObjField [:Identifier "name"] [:Identifier "n"]]
             [:DestructObjField [:Identifier "age"] [:Identifier "a"]]]
            [:Identifier "user"]]
           (parses-to "{name: n age: a} is user"))))

  (testing "destructuring with defaults"
    (let [tree (parses-to "{name ? \"anon\" age ? 0} is user")]
      (is (= :Binding (first tree)))
      (is (= :DestructObjPattern (first (nth tree 1))))))

  (testing "destructuring with as (whole binding)"
    (let [tree (parses-to "{name age} as u is user")]
      (is (= :Binding (first tree)))
      (is (= :DestructPattern (first (nth tree 1))))))

  ;; Single-field destructuring collapses through DestructObjPattern → DestructObjField
  (testing "nested object destructuring"
    (is (= [:Binding
            [:DestructObjField [:Identifier "address"] [:DestructObjPattern [:Identifier "city"] [:Identifier "country"]]]
            [:Identifier "user"]]
           (parses-to "{address: {city country}} is user"))))

  (testing "two-level nesting"
    (is (= [:Binding
            [:DestructObjField [:Identifier "a"] [:DestructObjField [:Identifier "b"] [:Identifier "c"]]]
            [:Identifier "deep"]]
           (parses-to "{a: {b: {c}}} is deep"))))

  (testing "combined rename + default + as"
    (let [tree (parses-to "{name: n age ? 0} as u is user")]
      (is (= :Binding (first tree)))
      (is (= :DestructPattern (first (nth tree 1)))))))

(deftest parse-list-destructuring
  (testing "simple list destructuring"
    (is (= [:Binding
            [:DestructListElems [:Identifier "a"] [:Identifier "b"] [:Identifier "c"]]
            [:List [:Integer "1"] [:Integer "2"] [:Integer "3"]]]
           (parses-to "[a b c] is [1 2 3]"))))

  (testing "list destructuring with rest"
    (is (= [:Binding
            [:DestructListElems [:Identifier "first"] [:Identifier "rest"]]
            [:Identifier "items"]]
           (parses-to "[first & rest] is items"))))

  (testing "list destructuring with skip"
    (let [tree (parses-to "[_ _ third] is items")]
      (is (= :Binding (first tree)))
      (is (= :DestructListElems (first (nth tree 1))))))

  (testing "skip first, capture rest"
    (let [tree (parses-to "[_ & tail] is [1 2 3 4]")]
      (is (= :Binding (first tree)))
      (is (= :DestructListElems (first (nth tree 1))))))

  (testing "multiple underscores"
    (let [tree (parses-to "[_ _ _ fourth] is [1 2 3 4]")]
      (is (= :Binding (first tree)))
      (is (= :DestructListElems (first (nth tree 1))))))

  (testing "list destructuring with as"
    (let [tree (parses-to "[head & tail] as all is items")]
      (is (= :Binding (first tree)))
      (is (= :DestructPattern (first (nth tree 1)))))))

(deftest parse-combined-destructuring
  (testing "object + list combined"
    (is (= [:Binding
            [:DestructObjPattern [:Identifier "name"]
             [:DestructObjField [:Identifier "scores"] [:DestructListElems [:Identifier "best"] [:Identifier "rest"]]]]
            [:Identifier "player"]]
           (parses-to "{name scores: [best & rest]} is player"))))

  (testing "list of objects, destructure first"
    (let [tree (parses-to "[{name age} & others] is users")]
      (is (= :Binding (first tree)))
      (is (= :DestructListElems (first (nth tree 1)))))))

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
    (let [tree (parses-to "x is 42\nx + 1")]
      (is (= :Program (first tree)))
      (is (= :Binding (first (nth tree 1))))
      (is (= :AddExpr (first (nth tree 2))))))

  (testing "binding then use"
    (let [tree (parses-to "double is [x -> x * 2]\ndouble 5")]
      (is (= :Program (first tree)))
      (is (= :Binding (first (nth tree 1))))
      (is (= :FnCall (first (nth tree 2))))))

  (testing "multiple bindings"
    (let [tree (parses-to "x is 5\ny is 10\nx + y")]
      (is (= :Program (first tree)))
      (is (= 3 (dec (count tree))))  ;; 3 expressions
      (is (= :Binding (first (nth tree 1))))
      (is (= :Binding (first (nth tree 2))))
      (is (= :AddExpr (first (nth tree 3))))))

  (testing "binding then pipeline"
    (let [tree (parses-to "users is [{name: \"Alice\" age: 30} {name: \"Bob\" age: 17}]\nusers |> filter _.age > 18 |> count")]
      (is (= :Program (first tree)))
      (is (= :Binding (first (nth tree 1))))
      (is (= :Pipeline (first (nth tree 2))))))

  (testing "last expression is program result"
    (let [tree (parses-to "x is 42\ny is x + 1\ny")]
      (is (= :Program (first tree)))
      (is (= 3 (dec (count tree))))  ;; 3 expressions
      (is (= :Identifier (first (nth tree 3)))))))

;; ==========================================================================
;; SECTION 26: Edge Cases and Parse Errors
;; ==========================================================================

(deftest parse-whitespace-requirements
  ;; PRD: "Whitespace required around operators"
  (testing "operators require whitespace"
    (is (parse-fails? "2+3")))

  (testing "identifier with hyphen is single token, not subtraction"
    (is (= :Identifier (first (parses-to "my-var")))))

  (testing "hyphen with spaces is subtraction"
    (is (= :AddExpr (first (parses-to "my - var"))))))

(deftest parse-various-errors
  (testing "multiple operators without operands"
    (is (parse-fails? "5 + + 3")))

  (testing "empty input is a parse error"
    (is (parse-fails? "")))

  (testing "just whitespace is a parse error"
    (is (parse-fails? "   ")))

  (testing "pipe operator inside string is literal text"
    (is (= :String (first (parses-to "\"use |> for pipes\""))))))

;; ==========================================================================
;; SECTION 27: Function Call with Parens
;; ==========================================================================

(deftest parse-function-call-with-parens
  ;; FnCall with zero args via parens: CallTarget <'('> _ <')'>
  ;; Parens are hidden, only CallTarget remains → single child → collapses
  (testing "zero-arg call — collapses to Identifier (hidden parens)"
    (is (= [:Identifier "answer"] (parses-to "answer()"))))

  (testing "parenthesized call then field access"
    (is (= [:FieldAccess [:Identifier "get-user"] [:FieldName "name"]]
           (parses-to "(get-user).name"))))

  (testing "bare identifier is reference, not call"
    (is (= [:Identifier "answer"] (parses-to "answer")))))

;; ==========================================================================
;; SECTION 28: Composition Operators
;; ==========================================================================

(deftest parse-composition-operators
  (testing "left-to-right composition >>"
    (is (= [:Compose [:Identifier "double"] [:ComposeOp ">>"] [:Identifier "inc"]]
           (parses-to "double >> inc"))))

  (testing "right-to-left composition <<"
    (is (= [:Compose [:Identifier "double"] [:ComposeOp "<<"] [:Identifier "inc"]]
           (parses-to "double << inc"))))

  (testing "chained composition"
    (is (= [:Compose [:Identifier "step1"] [:ComposeOp ">>"] [:Identifier "step2"] [:ComposeOp ">>"] [:Identifier "step3"]]
           (parses-to "step1 >> step2 >> step3"))))

  (testing "composition assigned with is"
    (let [tree (parses-to "transform is double >> inc >> abs")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "transform"] (nth tree 1)))
      (is (= :Compose (first (nth tree 2)))))))

;; ==========================================================================
;; SECTION 29: Try-Catch
;; ==========================================================================

(deftest parse-try-catch
  (testing "basic try-catch"
    (is (= [:TryCatch [:Identifier "risky-op"]
            [:CatchClause [:Identifier "err"] [:Identifier "default-val"]]]
           (parses-to "try\n  risky-op\ncatch err -> default-val"))))

  (testing "try-catch with typed exception"
    (let [tree (parses-to "try\n  read-file\ncatch java.io.FileNotFoundException e -> \"not found\"\ncatch _ -> \"unknown\"")]
      (is (= :TryCatch (first tree)))
      (is (= [:Identifier "read-file"] (nth tree 1)))
      (is (= :CatchClause (first (nth tree 2))))
      (is (= :CatchClause (first (nth tree 3))))))

  ;; FinallyClause has single child → collapses to its body
  (testing "try-catch-finally"
    (is (= [:TryCatch [:Identifier "risky"]
            [:CatchClause [:Identifier "err"] [:List]]
            [:Identifier "cleanup"]]
           (parses-to "try\n  risky\ncatch err -> []\nfinally\n  cleanup"))))

  (testing "try-catch assigned with is"
    (let [tree (parses-to "data is try\n  read-csv \"data.csv\"\ncatch err -> []")]
      (is (= :Binding (first tree)))
      (is (= [:Identifier "data"] (nth tree 1)))
      (is (= :TryCatch (first (nth tree 2)))))))

;; ==========================================================================
;; SECTION 30: Require / Import & Interop
;; ==========================================================================

(deftest parse-require
  (testing "require with alias"
    (is (= [:Require [:DotName "clojure.string"] [:Identifier "str"]]
           (parses-to "require clojure.string as str"))))

  (testing "qualified function call"
    (is (= [:FnCall [:QualifiedName "clojure.string/upper-case"] [:String "hello"]]
           (parses-to "clojure.string/upper-case \"hello\""))))

  (testing "aliased qualified call"
    (is (= [:FnCall [:QualifiedName "str/upper-case"] [:String "hello"]]
           (parses-to "str/upper-case \"hello\"")))))

(deftest parse-java-interop
  (testing "instance method call"
    (is (= [:FnCall [:InstanceMethod ".method"] [:Identifier "object"]]
           (parses-to ".method object"))))

  (testing "static method call — parses as two expressions (QualifiedName + UnaryExpr)"
    (let [tree (parses-to "Math/abs -5")]
      (is (= :Program (first tree)))
      (is (= [:QualifiedName "Math/abs"] (nth tree 1)))
      (is (= [:UnaryExpr "-" [:Integer "5"]] (nth tree 2)))))

  (testing "constructor"
    (is (= [:FnCall [:Constructor "ArrayList."] [:Integer "10"]]
           (parses-to "ArrayList. 10")))))

(deftest parse-keyword-syntax
  (testing "keyword literal"
    (is (= [:Keyword ":status"] (parses-to ":status"))))

  (testing "keyword with hyphen"
    (is (= [:Keyword ":first-name"] (parses-to ":first-name")))))

;; ==========================================================================
;; SECTION 31: Object Field Operations (+/- prefixes)
;; ==========================================================================

(deftest parse-field-operations
  (testing "add field with + prefix"
    (is (= [:Object [:AddField [:Identifier "score"]
                     [:MulExpr [:FieldAccess [:Wildcard "_"] [:FieldName "age"]] [:MulOp "*"] [:Integer "2"]]]]
           (parses-to "{+score: _.age * 2}"))))

  (testing "remove field with - prefix"
    (is (= [:Object [:Identifier "tmp"]]
           (parses-to "{-tmp}"))))

  (testing "mixed + and -"
    (is (= [:Object
            [:AddField [:Identifier "score"]
             [:MulExpr [:FieldAccess [:Wildcard "_"] [:FieldName "age"]] [:MulOp "*"] [:Integer "2"]]]
            [:Identifier "tmp"]]
           (parses-to "{+score: _.age * 2 -tmp}"))))

  (testing "forward-referencing in field operations"
    (let [tree (parses-to "{+tax: _.price * 0.1 +total: _.price + tax}")]
      (is (= :Object (first tree)))
      (is (= :AddField (first (nth tree 1))))))

  ;; Shorthand uses commas between bare identifiers (no colons).
  ;; Distinct from commas between key:value pairs which are invalid.
  (testing "object shorthand with commas"
    (is (= [:Object [:ShorthandContent [:Identifier "name"] [:Identifier "age"]]]
           (parses-to "{name, age}"))))

  (testing "shorthand mixed with explicit field"
    (is (= [:Object
            [:ShorthandContent [:Identifier "name"] [:Identifier "age"]
             [:ShorthandEntry [:Identifier "city"] [:FieldAccess [:Wildcard "_"] [:FieldName "address"] [:FieldName "city"]]]]]
           (parses-to "{name, age, city: _.address.city}"))))

  (testing "plain object = new structure (no +/- prefix)"
    (is (= [:Object
            [:StandardEntry [:Identifier "name"] [:FieldAccess [:Wildcard "_"] [:FieldName "name"]]]
            [:StandardEntry [:Identifier "age"] [:FieldAccess [:Wildcard "_"] [:FieldName "age"]]]]
           (parses-to "{name: _.name age: _.age}")))))

;; ==========================================================================
;; SECTION 32: Multi-arity Functions
;; ==========================================================================

(deftest parse-multi-arity-function
  (testing "function with two arities"
    (is (= [:Binding [:Identifier "greet"]
            [:MultiArityFn
             [:FnDef [:String "Hello, World!"]]
             [:FnDef [:Identifier "name"]
              [:FnCall [:Identifier "format"] [:String "Hello, %s!"] [:Identifier "name"]]]]]
           (parses-to "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]"))))

  (testing "function with three arities"
    (let [tree (parses-to "greet is\n  [-> \"Hello, World!\"]\n  [name -> format \"Hello, %s!\" name]\n  [first last -> format \"Hello, %s %s!\" first last]")]
      (is (= :Binding (first tree)))
      (is (= :MultiArityFn (first (nth tree 2))))
      (is (= 4 (count (nth tree 2)))))))

;; ==========================================================================
;; SECTION 33: Recur
;; ==========================================================================

(deftest parse-recur
  (testing "recur in function body with guards"
    (let [tree (parses-to "[n acc ->\n  | n <= 1 -> acc\n  | _ -> recur (n - 1) (acc * n)\n]")]
      (is (= :FnDef (first tree)))
      (is (= :FnParams (first (nth tree 1))))
      (is (= :GuardBlock (first (nth tree 2))))
      ;; Second guard arm result contains Recur
      (let [arm2 (nth (nth tree 2) 2)]
        (is (= :GuardArm (first arm2)))
        (is (= :Recur (first (nth arm2 2)))))))

  (testing "recur with single argument"
    (let [tree (parses-to "[n ->\n  | n <= 0 -> 0\n  | _ -> n + recur (n - 1)\n]")]
      (is (= :FnDef (first tree)))
      (is (= :GuardBlock (first (nth tree 2)))))))
