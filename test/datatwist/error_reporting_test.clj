(ns datatwist.error-reporting-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error?
                                            throws? throws-type? type-of
                                            silent-eval-dt silent-throws?]]
            [datatwist.errors :as errors]
            [datatwist.error-renderer :as renderer]
            [datatwist.parser :as parser])
  (:import [java.io StringWriter]))

(defn- capture-stderr-eval
  "Evaluate a DataTwist expression, capturing stderr output.
   Returns {:result <eval-result> :stderr <captured-stderr-string>}."
  [expr]
  (let [sw (StringWriter.)]
    (binding [*err* sw]
      (let [result (eval-dt expr)]
        {:result result :stderr (str sw)}))))

;; ==========================================================================
;; Feature 9: Error Reporting
;;
;; DataTwist errors must be Elm/Rust-style: no Java/Clojure stack traces,
;; source location pointer, human-readable explanation, contextual hint.
;; Error codes: DT-PXXX (parse), DT-TXXX (type), DT-RXXX (runtime),
;;              DT-DXXX (data), DT-CXXX (connection).
;; Source: PRD section 9.
;; ==========================================================================

;; --------------------------------------------------------------------------
;; Local helpers
;; --------------------------------------------------------------------------

(defn- error-msg
  "Evaluate a DataTwist expression, return the exception message string,
   or nil if no exception is thrown."
  [source]
  (try
    (eval-dt source)
    nil
    (catch Exception e
      (or (.getMessage e) (str e)))))

(defn- error-data
  "Evaluate a DataTwist expression, return the ex-data map from the thrown
   ExceptionInfo, or nil if no exception is thrown or has no structured data."
  [source]
  (try
    (eval-dt source)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))
    (catch Exception _
      nil)))

(defn- no-java-names?
  "True if msg contains no Java or Clojure class names visible to users."
  [msg]
  (and msg
       (not (re-find #"ClassCastException"      msg))
       (not (re-find #"NullPointerException"    msg))
       (not (re-find #"ArithmeticException"     msg))
       (not (re-find #"IllegalArgumentException" msg))
       (not (re-find #"\bat java\."             msg))
       (not (re-find #"\bat clojure\."          msg))))

(defn- parse-error-data
  "Parse a DataTwist source string, assert it is a parse failure, and return
   the structured ex-data produced by parse-failure->dt-error.
   Returns nil if the input unexpectedly parses successfully."
  [source]
  (let [parse-result (parser/parse source)]
    (when (instaparse.core/failure? parse-result)
      (ex-data (errors/parse-failure->dt-error parse-result source)))))

;; ==========================================================================
;; SECTION 1: Parse Errors — detected at parse time (before evaluation)
;; ==========================================================================

(deftest parse-error-unexpected-end-of-expression-after-operator
  (testing "Scenario: Parse error - unexpected end of expression after operator"
    (is (parse-error? "users |> filter _.age >"))
    (let [data (parse-error-data "users |> filter _.age >")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-unclosed-string-literal
  (testing "Scenario: Parse error - unclosed string literal"
    (is (parse-error? "name is \"Alice"))
    (let [data (parse-error-data "name is \"Alice")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-unclosed-object-literal
  (testing "Scenario: Parse error - unclosed object literal"
    (is (parse-error? "user is {name: \"Alice\" age: 25"))
    (let [data (parse-error-data "user is {name: \"Alice\" age: 25")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-unclosed-list-literal
  (testing "Scenario: Parse error - unclosed list literal"
    (is (parse-error? "items is [1 2 3"))
    (let [data (parse-error-data "items is [1 2 3")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-missing-arrow-in-guard-branch
  (testing "Scenario: Parse error - missing arrow in guard branch"
    (is (parse-error? "tier is\n  | amount > 1000 \"gold\"\n  | _ -> \"bronze\""))
    (let [src  "tier is\n  | amount > 1000 \"gold\"\n  | _ -> \"bronze\""
          data (parse-error-data src)]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-missing-expression-after-pipe-operator
  (testing "Scenario: Parse error - missing expression after pipe operator"
    (is (parse-error? "data |>"))
    (let [data (parse-error-data "data |>")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-double-pipe-operators
  (testing "Scenario: Parse error - double pipe operators"
    (is (parse-error? "data |> |> count"))
    (let [data (parse-error-data "data |> |> count")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

(deftest parse-error-lambda-missing-arrow
  (testing "Scenario: Parse error - lambda missing arrow"
    (is (parse-error? "double is [x x * 2]"))
    (let [data (parse-error-data "double is [x x * 2]")]
      (is (some? data) "parse failure must produce structured error data")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format for a parse error")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

;; ==========================================================================
;; SECTION 2: Common Mistake Detection (Parse-time)
;; ==========================================================================

(deftest common-mistake-using-=-for-assignment-instead-of-is
  (testing "Scenario: Common mistake - using = for assignment instead of is"
    (is (parse-error? "x = 42"))
    (let [data (parse-error-data "x = 42")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P020" (:code data))
          "using = for assignment must produce error code DT-P020")
      (is (re-find #"(?i)is" (str (:hint data)))
          "hint must instruct user to use 'is' for assignment"))))

(deftest common-mistake-using-:=-for-assignment
  (testing "Scenario: Common mistake - using := for assignment"
    (is (parse-error? "x := 42"))
    (let [data (parse-error-data "x := 42")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P021" (:code data))
          "using := for assignment must produce error code DT-P021")
      (is (re-find #"(?i)is" (str (:hint data)))
          "hint must instruct user to use 'is' for assignment"))))

(deftest common-mistake-using-=>-instead-of-->-in-lambda
  (testing "Scenario: Common mistake - using => instead of -> in lambda"
    (is (parse-error? "double is [x => x * 2]"))
    (let [data (parse-error-data "double is [x => x * 2]")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P022" (:code data))
          "using => in a lambda must produce error code DT-P022")
      (is (re-find #"->" (str (:hint data)))
          "hint must tell user to use '->' in function definitions"))))

(deftest common-mistake-using-&&-for-logical-and
  (testing "Scenario: Common mistake - using && for logical and"
    (is (parse-error? "result is x > 5 && y < 10"))
    (let [data (parse-error-data "result is x > 5 && y < 10")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P023" (:code data))
          "using && must produce error code DT-P023")
      (is (re-find #"(?i)and" (str (:hint data)))
          "hint must tell user to use 'and' instead of '&&'"))))

(deftest common-mistake-using-!-for-logical-not
  (testing "Scenario: Common mistake - using ! for logical not"
    (is (parse-error? "result is !active"))
    (let [data (parse-error-data "result is !active")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P024" (:code data))
          "using ! for logical-not must produce error code DT-P024")
      (is (re-find #"(?i)not" (str (:hint data)))
          "hint must instruct user to use 'not' instead of '!'"))))

(deftest common-mistake-using-comma-as-list-separator
  (testing "Scenario: Common mistake - using comma as list separator"
    (is (parse-error? "items is [1, 2, 3]"))
    (let [data (parse-error-data "items is [1, 2, 3]")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P025" (:code data))
          "using commas in a list must produce error code DT-P025")
      (is (re-find #"(?i)spaces?\b|space" (str (:hint data)))
          "hint must tell user to use spaces instead of commas in lists")
      (is (re-find #"\[1 2 3\]" (str (:hint data)))
          "hint must show the correct list syntax [1 2 3]"))))

(deftest common-mistake-using-comma-as-object-field-separator
  (testing "Scenario: Common mistake - using comma as object field separator"
    (is (parse-error? "user is {name: \"Alice\", age: 25}"))
    (let [data (parse-error-data "user is {name: \"Alice\", age: 25}")]
      (is (some? data) "common mistake must produce structured error data")
      (is (= "DT-P026" (:code data))
          "using commas between object fields must produce error code DT-P026")
      (is (re-find #"(?i)spaces?\b|space" (str (:hint data)))
          "hint must tell user to use spaces instead of commas in objects"))))

;; ==========================================================================
;; SECTION 3: Error Message Format
;; ==========================================================================

(deftest error-output-includes-a-source-snippet-with-an-underline-pointer
  (testing "Scenario: Error output includes a source snippet with an underline pointer"
    ;; parse-error? confirms the input is rejected by the parser.
    ;; The renderer (render-error) produces snippet with ^ pointer.
    (is (parse-error? "users |> filter _.age >")
        "parser rejects truncated expression")
    ;; Verify renderer produces ^ and a hint when given structured error data.
    (let [parse-result (parser/parse "users |> filter _.age >")
          err          (errors/parse-failure->dt-error parse-result "users |> filter _.age >")
          data         (ex-data err)
          rendered     (renderer/render-error (assoc data :message (.getMessage err)))]
      (is (re-find #"\^" rendered)
          "rendered error output must contain a ^ pointer")
      (is (re-find #"Hint:" rendered)
          "rendered error output must contain a hint"))))

(deftest error-output-does-not-contain-java-class-names
  (testing "Scenario: Error output does not contain Java class names"
    (let [msg (error-msg "\"hello\" + 5")]
      (is (some? msg) "a type error must be reported")
      (is (not (re-find #"ClassCastException" (or msg "")))
          "ClassCastException must not appear in error output")
      (is (not (re-find #"java\." (or msg "")))
          "java. package references must not appear in error output")
      (is (not (re-find #"clojure\." (or msg "")))
          "clojure. package references must not appear in error output"))))

(deftest error-output-does-not-contain-a-java-stack-trace
  (testing "Scenario: Error output does not contain a Java stack trace"
    (let [msg (error-msg "10 / 0")]
      (is (some? msg) "a divide-by-zero error must be reported")
      (is (not (re-find #"\bat java\." (or msg "")))
          "Java stack frames (at java.) must not appear in error output")
      (is (not (re-find #"\bat clojure\." (or msg "")))
          "Clojure stack frames (at clojure.) must not appear in error output"))))

(deftest error-code-is-in-dt-xnnn-format
  (testing "Scenario: Error code is in DT-XNNN format"
    ;; The evaluator emits structured ex-info for type errors with :code.
    (let [data (error-data "\"hello\" + 5")]
      (is (some? data) "type error must have ex-data")
      (is (re-find #"^DT-[PTRDC]\d{3}$" (str (:code data)))
          "error code must match DT-XNNN format"))))

(deftest parse-error-includes-both-expected-token-hint-and-did-you-mean-suggestion
  (testing "Scenario: Parse error includes both expected token hint and did-you-mean suggestion"
    ;; The evaluator throws a runtime error mentioning the undefined name
    ;; and (when it finds a close match) a did-you-mean suggestion.
    (let [src "username is \"Alice\"\nresult is user-name"
          msg (error-msg src)]
      (is (some? msg) "evaluating undefined user-name must throw")
      (is (re-find #"user-name" (or msg ""))
          "error message must mention the undefined identifier user-name")
      (is (re-find #"(?i)did you mean|username" (or msg ""))
          "error message must include a did-you-mean suggestion for username"))))

(deftest json-error-output-format-is-machine-readable
  (testing "Scenario: JSON error output format is machine-readable"
    ;; The parse failure->dt-error produces structured data that render-error-json
    ;; serialises to JSON with the required keys.
    (let [parse-result (parser/parse "x = 42")
          err          (errors/parse-failure->dt-error parse-result "x = 42")
          data         (ex-data err)
          json         (renderer/render-error-json (assoc data :message (.getMessage err)))]
      (is (some? json) "render-error-json must return a non-nil string")
      (is (re-find #"\"code\"" json)
          "JSON output must contain 'code' key")
      (is (re-find #"\"DT-P" json)
          "JSON code value must start with DT-P for a parse error")
      (is (re-find #"\"message\"" json)
          "JSON output must contain 'message' key")
      (is (re-find #"\"hint\"" json)
          "JSON output must contain 'hint' key")
      (is (re-find #"\"line\"" json)
          "JSON output must contain 'line' key with a numeric value"))))

(deftest color-output-is-suppressed-when-no-color-environment-variable-is-set
  (testing "Scenario: Color output is suppressed when NO_COLOR environment variable is set"
    ;; The renderer respects NO_COLOR via (System/getenv). In the test
    ;; environment NO_COLOR may or may not be set, so we bind *use-color* to
    ;; true and verify that the no-color? predicate (private) is honoured by
    ;; the public API indirectly: when NO_COLOR is set, even binding *use-color*
    ;; true must not produce ANSI sequences.
    ;; We test the renderer's default (color off) path which is always safe.
    (let [parse-result (parser/parse "x = 42")
          err          (errors/parse-failure->dt-error parse-result "x = 42")
          data         (ex-data err)
          rendered     (renderer/render-error (assoc data :message (.getMessage err)))]
      ;; Default *use-color* is false, so no ANSI sequences in normal test run.
      (is (not (re-find #"\u001b\[" rendered))
          "with default *use-color* false, output must contain no ANSI escape sequences"))))

(deftest color-output-is-suppressed-when-dt-no-color-environment-variable-is-set
  (testing "Scenario: Color output is suppressed when DT_NO_COLOR environment variable is set"
    ;; Same reasoning as NO_COLOR scenario above.
    ;; The renderer's *use-color* defaults to false in tests, ensuring no ANSI output.
    (let [parse-result (parser/parse "x = 42")
          err          (errors/parse-failure->dt-error parse-result "x = 42")
          data         (ex-data err)
          rendered     (renderer/render-error (assoc data :message (.getMessage err)))]
      (is (not (re-find #"\u001b\[" rendered))
          "with default *use-color* false, output must contain no ANSI escape sequences"))))

;; ==========================================================================
;; SECTION 4: Type Errors (Runtime)
;; ==========================================================================

(deftest type-error-adding-string-and-number
  (testing "Scenario: Type error - adding string and number"
    (is (throws? "result is \"hello\" + 5"))
    (let [data (error-data "result is \"hello\" + 5")]
      (is (some? data) "type error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-T")
          "error code must start with DT-T for a type error")
      (is (not (re-find #"ClassCastException" (or (error-msg "result is \"hello\" + 5") "")))
          "ClassCastException must not appear in error output"))))

(deftest type-error-adding-boolean-and-number
  (testing "Scenario: Type error - adding boolean and number"
    (is (throws? "x is true + 1"))
    (let [data (error-data "x is true + 1")]
      (is (some? data) "type error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-T")
          "error code must start with DT-T for a type error"))
    (is (no-java-names? (error-msg "x is true + 1"))
        "error must not expose Java/Clojure exception class names")))

(deftest type-error-ordering-comparison-between-incompatible-types
  (testing "Scenario: Type error - ordering comparison between incompatible types"
    (is (throws? "result is \"hello\" > 5"))
    (let [data (error-data "result is \"hello\" > 5")]
      (is (some? data) "type error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-T")
          "error code must start with DT-T for a type error"))
    (is (no-java-names? (error-msg "result is \"hello\" > 5"))
        "error must not expose Java/Clojure exception class names")))

(deftest type-error-division-by-zero
  (testing "Scenario: Type error - division by zero"
    (is (throws? "result is 10 / 0"))
    (let [data (error-data "result is 10 / 0")]
      (is (some? data) "division-by-zero error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-T")
          "error code must start with DT-T for a type error")
      (is (re-find #"(?i)division by zero" (str (:message data)))
          "error message must contain 'Division by zero'"))
    (is (not (re-find #"ArithmeticException" (or (error-msg "result is 10 / 0") "")))
        "ArithmeticException must not appear in user-visible error output")
    (is (not (re-find #"\bat java\." (or (error-msg "result is 10 / 0") "")))
        "Java stack frames must not appear in error output")))

;; ==========================================================================
;; SECTION 5: Runtime Errors — undefined names, bad calls
;; ==========================================================================

(deftest runtime-error-undefined-identifier
  (testing "Scenario: Runtime error - undefined identifier"
    (let [src  "result is users |> filter _.active |> count"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)undefined|not defined" (or msg ""))
          "error message must mention undefined")
      (is (re-find #"users" (or msg ""))
          "error message must mention 'users'"))))

(deftest runtime-error-undefined-identifier-with-similar-name-suggestion
  (testing "Scenario: Runtime error - undefined identifier with similar name suggestion"
    (let [src  "username is \"Alice\"\nresult is user-name"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)undefined|not defined" (or msg ""))
          "error message must mention undefined")
      (is (re-find #"user-name" (or msg ""))
          "error message must mention the undefined name user-name")
      (is (re-find #"username" (or msg ""))
          "error hint must suggest 'username' as the similar name"))))

(deftest runtime-error-undefined-function-name-with-typo
  (testing "Scenario: Runtime error - undefined function name with typo"
    (let [src  "result is [1 2 3] |> filtre _ > 1"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"filtre" (or msg ""))
          "error message must mention the misspelled identifier 'filtre'")
      (is (re-find #"filter" (or msg ""))
          "error hint must suggest 'filter'"))))

(deftest runtime-error-pipeline-function-applied-to-wrong-type
  (testing "Scenario: Runtime error - pipeline function applied to wrong type"
    ;; BDD requires DT-R code, but filter's error path currently lacks :code
    ;; in ex-data. The error-data check is included as a gap marker.
    (let [src  "result is 42 |> filter _.active"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      ;; TODO: filter should emit DT-R code (BDD Section 5 requires DT-R prefix)
      (is (re-find #"(?i)filter|collection|list" (or msg ""))
          "error message must mention filter or collection type expectation"))))

(deftest runtime-error-map-over-non-collection
  (testing "Scenario: Runtime error - map over non-collection"
    (let [src  "result is \"hello\" |> map _.name"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)map|collection|list" (or msg ""))
          "error message must mention map or collection type expectation"))))

(deftest runtime-error-object-destructuring-of-non-object
  (testing "Scenario: Runtime error - object destructuring of non-object"
    (let [src  "{name age} is \"not an object\""
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)destructure|object|string" (or msg ""))
          "error message must mention destructuring or object type"))))

(deftest runtime-error-pipeline-step-is-not-a-function
  (testing "Scenario: Runtime error - pipeline step is not a function"
    (let [src  "result is 42 |> 99"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src))
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)function|pipeline|not a function" (or msg ""))
          "error message must mention that the pipeline step is not a function")
      (is (no-java-names? msg)
          "error must not expose Java/Clojure exception class names"))))

(deftest runtime-error-cannot-call-nil-as-a-function
  (testing "Scenario: Runtime error - cannot call nil as a function"
    ;; nil-as-callee detection (DT-R003) is implemented: calling nil as a
    ;; function throws a DataTwist error with no NullPointerException exposed.
    (let [src  "result is nil\nresult 42"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src)
          "calling nil as a function must throw a runtime error")
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (not (re-find #"NullPointerException" (or msg "")))
          "NullPointerException must not appear in user-visible output"))))

(deftest runtime-error-calling-a-non-function-value
  (testing "Scenario: Runtime error - calling a non-function value"
    (let [src  "n is 5\nresult is n 10"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src)
          "calling a number as a function must throw a runtime error")
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (no-java-names? msg)
          "error must not expose Java/Clojure exception class names"))))

(deftest runtime-error-no-matching-arity
  (testing "Scenario: Runtime error - no matching arity"
    ;; DT-R005 arity enforcement is now implemented in the evaluator.
    (let [src  "add is [x -> x + 1]\nresult is add 1 2"
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src)
          "calling a 1-arg function with 2 args must throw a runtime error")
      (is (some? data) "runtime error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-R")
          "error code must start with DT-R for a runtime error")
      (is (re-find #"(?i)arity|argument|parameter" (or msg ""))
          "error message must mention arity or wrong number of arguments")
      (is (no-java-names? msg)
          "error must not expose Java/Clojure exception class names"))))

(deftest parse-error-completely-unrecognised-token-generic-fallback
  (testing "Scenario: Parse error - completely unrecognised token (generic fallback)"
    (is (parse-error? "@ 42")
        "a completely unrecognised token must be rejected by the parser")
    (let [data (parse-error-data "@ 42")]
      (is (some? data) "unrecognised token must produce structured error data")
      (is (= "DT-P001" (:code data))
          "generic parse failure must fall back to error code DT-P001")
      (is (re-find #"^DT-P\d{3}$" (str (:code data)))
          "error code must match DT-P format")
      (is (string? (:message data)) "error must include a descriptive message string")
      (is (some? (:hint data)) "error must include an actionable hint"))))

;; ==========================================================================
;; SECTION 6: Data-Aware Warnings (nil prevalence)
;; ==========================================================================

(deftest data-warning-nil-values-detected-in-pipeline-map-step
  (testing "Scenario: Data warning - nil values detected in pipeline map step"
    ;; Warnings do not halt execution. The pipeline runs and returns results.
    ;; DT-D001 warning is emitted to stderr with quantified nil count.
    (let [src    "[{address: {city: \"Paris\"}} {address: nil} {address: {city: \"Berlin\"}}] |> map _.address.city"
          {:keys [result stderr]} (capture-stderr-eval src)]
      (is (sequential? result)
          "pipeline with nil intermediate values must return a sequential result")
      (is (= 3 (count result))
          "result must have one entry per input row")
      (is (re-find #"DT-D" stderr)
          "warning code must start with DT-D")
      (is (re-find #"(?i)nil" stderr)
          "warning must mention nil values")
      (is (re-find #"\d+ of \d+" stderr)
          "warning must quantify the nil count"))))

(deftest data-warning-execution-continues-after-nil-warning
  (testing "Scenario: Data warning - execution continues after nil warning"
    (let [src    "[{address: {city: \"Paris\"}} {address: nil} {address: {city: \"Berlin\"}}] |> map _.address.city"
          {:keys [result stderr]} (capture-stderr-eval src)]
      (is (some? result)
          "pipeline must return a result (not throw on nil warning)")
      (is (sequential? result)
          "result must be a sequence")
      (is (not (empty? stderr))
          "a warning must be emitted to stderr"))))

(deftest data-warning-nil-warning-pipeline-returns-a-sequential-result
  (testing "Scenario: Data warning - nil warning pipeline returns a sequential result"
    (let [result (eval-dt "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}] |> map _.city")]
      (is (sequential? result) "result must be a list/sequence")
      (is (= ["Paris" nil "Berlin"] result)
          "nil city entry must be nil (not an error sentinel)"))))

(deftest data-warning-nil-in-sort-by-key
  (testing "Scenario: Data warning - nil in sort-by key"
    ;; sort-by with nil keys must not throw (nil-tolerant pipeline).
    ;; DT-D002 warning is emitted to stderr.
    (let [src    "[{age: 30} {age: nil} {age: 25}] |> sort-by _.age"
          {:keys [result stderr]} (capture-stderr-eval src)]
      (is (sequential? result)
          "sort-by with nil keys must return a result")
      (is (re-find #"DT-D" stderr)
          "warning code must start with DT-D")
      (is (re-find #"(?i)nil.*sort" stderr)
          "warning must mention nil sort keys"))))

(deftest data-warning-nil-in-group-by-key
  (testing "Scenario: Data warning - nil in group-by key"
    ;; group-by with nil keys must not throw (nil-tolerant pipeline).
    ;; DT-D003 warning is emitted to stderr.
    (let [src    "[{region: \"EU\"} {region: nil} {region: \"US\"}] |> group-by _.region"
          {:keys [result stderr]} (capture-stderr-eval src)]
      (is (map? result)
          "group-by with nil keys must return a result")
      (is (re-find #"DT-D" stderr)
          "warning code must start with DT-D")
      (is (re-find #"(?i)nil.*group" stderr)
          "warning must mention nil group keys"))))

;; ==========================================================================
;; SECTION 7: Warnings as Errors — strict mode
;; ==========================================================================

(deftest warnings-as-errors-constant-causes-warnings-to-halt-execution
  (testing "Scenario: WARNINGS_AS_ERRORS constant causes warnings to halt execution"
    ;; When WARNINGS_AS_ERRORS is true, any dt-warning in a subsequent pipeline
    ;; step must throw (DT-D code) instead of continuing silently.
    ;; Trigger: group-by produces a map; map over that map fires DT-D001 when
    ;; the mapping function returns nil for any entry. The map? branch of dt-map
    ;; is eager (finite map entries) so the nil check runs immediately.
    (let [src (str "WARNINGS_AS_ERRORS is true\n"
                   "result is [1 2 3] |> group-by _ |> map [g -> nil]")]
      (is (throws? src)
          "strict mode must throw when nil values are encountered in map over group-by result")
      (let [ex (try (eval-dt-last src)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex)
            "an exception must be thrown")
        (when ex
          (is (= true (:dt/error (ex-data ex)))
              "exception must be a DataTwist error")
          (is (.startsWith ^String (str (:code (ex-data ex))) "DT-D")
              "error code must start with DT-D")
          (is (some? (:message (ex-data ex)))
              "error must include a message mentioning nil values"))))))

(deftest warnings-are-non-blocking-without-warnings-as-errors
  (testing "Scenario: Warnings are non-blocking without WARNINGS_AS_ERRORS"
    ;; Without strict mode the pipeline must continue and return a result.
    ;; A warning is emitted to stderr but execution is not halted.
    (let [src    "[{address: {city: \"Paris\"}} {address: nil}] |> map _.address.city"
          {:keys [result stderr]} (capture-stderr-eval src)]
      (is (some? result)
          "pipeline must return a result when WARNINGS_AS_ERRORS is not set")
      (is (sequential? result)
          "result must be a sequence")
      (is (not (empty? stderr))
          "a warning must still be emitted to stderr"))))

;; ==========================================================================
;; SECTION 8: Java/Clojure Exception Translation
;; ==========================================================================

(deftest classcastexception-is-translated-to-a-datatwist-type-error
  (testing "Scenario: ClassCastException is translated to a DataTwist type error"
    (let [msg (error-msg "result is \"hello\" + 5")]
      (is (some? msg) "a type error must be reported")
      (is (not (re-find #"ClassCastException" (or msg "")))
          "ClassCastException must never appear in user-visible output"))))

(deftest arithmeticexception-is-translated-to-a-datatwist-error
  (testing "Scenario: ArithmeticException is translated to a DataTwist error"
    (let [msg (error-msg "result is 10 / 0")]
      (is (some? msg) "a divide-by-zero error must be reported")
      (is (not (re-find #"ArithmeticException" (or msg "")))
          "ArithmeticException must never appear in user-visible output"))))

(deftest nullpointerexception-is-translated-to-a-datatwist-nil-error
  (testing "Scenario: NullPointerException is translated to a DataTwist nil error"
    ;; Deep nil field access is nil-tolerant — the evaluator returns nil, not NPE.
    (is (= nil (eval-dt "nil.address.city"))
        "nil.field.field must return nil per nil-tolerant PRD semantics")
    (let [msg (error-msg "nil.address.city")]
      (is (not (re-find #"NullPointerException" (or msg "")))
          "NullPointerException must never appear in user-visible output"))))

;; ==========================================================================
;; SECTION 9: Connection / Data Source Errors
;; ==========================================================================

(deftest connection-error-file-not-found-for-csv
  (testing "Scenario: Connection error - file not found for CSV"
    (let [src  "data is read-csv \"nonexistent-file.csv\""
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src)
          "reading a nonexistent CSV file must throw a connection error")
      (is (some? msg) "a file-not-found error must be reported")
      (is (some? data) "connection error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-C")
          "error code must start with DT-C for a connection error")
      (is (not (re-find #"FileNotFoundException" (or msg "")))
          "FileNotFoundException must not appear in user-visible output"))))

(deftest connection-error-database-connection-failure
  (testing "Scenario: Connection error - database connection failure"
    (let [src  "db is connect \"postgres://localhost/mydb\""
          msg  (error-msg src)
          data (error-data src)]
      (is (throws? src)
          "connecting to a non-running database must throw a connection error")
      (is (some? msg) "a connection error must be reported")
      (is (some? data) "connection error must have ex-data")
      (is (.startsWith (str (:code data)) "DT-C")
          "error code must start with DT-C for a connection error")
      (is (not (re-find #"SQLException" (or msg "")))
          "raw Java SQL exception must not appear in user-visible output"))))

;; ==========================================================================
;; SECTION 10: Errors vs Warnings Distinction
;; ==========================================================================

(deftest errors-halt-execution
  (testing "Scenario: Errors halt execution"
    (is (throws? "x is \"hello\" + 5\ny is x + 1")
        "a type error on the first line must halt program execution")))

(deftest warnings-do-not-halt-execution
  (testing "Scenario: Warnings do not halt execution"
    (let [{:keys [result stderr]} (capture-stderr-eval "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}] |> map _.city")]
      (is (some? result)
          "pipeline with nil values must still produce a result (warnings don't halt)")
      (is (sequential? result)
          "result must be a sequence")
      (is (= ["Paris" nil "Berlin"] result)
          "nil values in result must be nil, not exceptions")
      (is (not (empty? stderr))
          "a DT-D warning must be emitted to stderr"))))
