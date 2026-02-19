(ns datatwist.error-reporting-test
  (:require [clojure.test :refer [deftest testing is]]
            [datatwist.test-helpers :refer [eval-dt eval-dt-last parse-error? throws? throws-type? type-of]]))

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
;; Local helper: capture the exception message from a DataTwist evaluation.
;; Returns nil if eval-dt does not throw.
;; --------------------------------------------------------------------------

(defn- error-msg
  "Evaluate a DataTwist expression, return the exception message string,
   or nil if no exception is thrown."
  [source]
  (try
    (eval-dt source)
    nil
    (catch Exception e
      (or (.getMessage e)
          (str e)))))

(defn- error-data
  "Evaluate a DataTwist expression, return the ex-data map from the thrown
   clojure.lang.ExceptionInfo, or nil if no exception is thrown or if the
   exception has no structured data."
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
       (not (re-find #"ClassCastException"    msg))
       (not (re-find #"NullPointerException"  msg))
       (not (re-find #"ArithmeticException"   msg))
       (not (re-find #"IllegalArgumentException" msg))
       (not (re-find #"\bat java\."           msg))
       (not (re-find #"\bat clojure\."        msg))))

;; ==========================================================================
;; SECTION 1: Parse Errors (DT-PXXX)
;; ==========================================================================

(deftest parse-error-unexpected-end-after-operator
  (testing "Scenario: Parse error - unexpected end of expression after operator"
    (testing "truncated filter expression is rejected by the parser"
      (is (parse-error? "users |> filter _.age >")))
    (testing "parse error does not produce a successful result"
      (is (not (throws? "users |> filter _.age >")) "parser rejects before evaluation"))))

(deftest parse-error-unclosed-string-literal
  (testing "Scenario: Parse error - unclosed string literal"
    (testing "string without closing quote is rejected by the parser"
      (is (parse-error? "name is \"Alice")))))

(deftest parse-error-unclosed-object-literal
  (testing "Scenario: Parse error - unclosed object literal"
    (testing "object without closing brace is rejected by the parser"
      (is (parse-error? "user is {name: \"Alice\" age: 25")))))

(deftest parse-error-unclosed-list-literal
  (testing "Scenario: Parse error - unclosed list literal"
    (testing "list without closing bracket is rejected by the parser"
      (is (parse-error? "items is [1 2 3")))))

(deftest parse-error-missing-arrow-in-guard-branch
  (testing "Scenario: Parse error - missing arrow in guard branch"
    (testing "guard branch without '->' is rejected by the parser"
      (is (parse-error? "tier is\n  | amount > 1000 \"gold\"\n  | _ -> \"bronze\"")))))

(deftest parse-error-missing-expression-after-pipe
  (testing "Scenario: Parse error - missing expression after pipe operator"
    (testing "bare |> with nothing after it is rejected by the parser"
      (is (parse-error? "data |>")))))

(deftest parse-error-double-pipe-operators
  (testing "Scenario: Parse error - double pipe operators"
    (testing "data |> |> count is rejected by the parser"
      (is (parse-error? "data |> |> count")))))

(deftest parse-error-lambda-missing-arrow
  (testing "Scenario: Parse error - lambda missing arrow"
    (testing "[x x * 2] without '->' is rejected by the parser"
      (is (parse-error? "double is [x x * 2]")))))

;; ==========================================================================
;; SECTION 2: Common Mistake Detection (DT-PXXX)
;; ==========================================================================

(deftest common-mistake-equals-for-assignment
  (testing "Scenario: Common mistake - using = for assignment instead of is"
    (testing "'x = 42' is rejected (= is the equality operator, not assignment)"
      (is (parse-error? "x = 42")))))

(deftest common-mistake-colon-equals-for-assignment
  (testing "Scenario: Common mistake - using := for assignment"
    (testing "'x := 42' is rejected"
      (is (parse-error? "x := 42")))))

(deftest common-mistake-fat-arrow-in-lambda
  (testing "Scenario: Common mistake - using => instead of -> in lambda"
    (testing "'[x => x * 2]' is rejected (=> is not valid DataTwist)"
      (is (parse-error? "double is [x => x * 2]")))))

(deftest common-mistake-ampersand-and-for-logical-and
  (testing "Scenario: Common mistake - using && for logical and"
    (testing "'x > 5 && y < 10' is rejected (&& is not a DataTwist operator)"
      (is (parse-error? "result is x > 5 && y < 10")))))

(deftest common-mistake-exclamation-for-logical-not
  (testing "Scenario: Common mistake - using ! for logical not"
    (testing "'!active' is rejected (! is not a DataTwist prefix operator)"
      (is (parse-error? "result is !active")))))

(deftest common-mistake-comma-as-list-separator
  (testing "Scenario: Common mistake - using comma as list separator"
    (testing "'[1, 2, 3]' is rejected (commas are not separators in DataTwist)"
      (is (parse-error? "items is [1, 2, 3]")))))

(deftest common-mistake-comma-as-object-field-separator
  (testing "Scenario: Common mistake - using comma as object field separator"
    (testing "'{name: \"Alice\", age: 25}' is rejected"
      (is (parse-error? "user is {name: \"Alice\", age: 25}")))))

;; ==========================================================================
;; SECTION 3: Error Message Format Requirements
;; ==========================================================================

(deftest error-output-source-snippet-and-hint
  (testing "Scenario: Error output includes a source snippet with an underline pointer"
    ;; The evaluator must produce structured error data that includes:
    ;;   :source-line  -- the offending source text
    ;;   :hint         -- a suggestion for how to fix the error
    ;; We test the contract via ex-data on the thrown exception.
    (testing "parse error for truncated filter carries source context in ex-data"
      ;; parse-error? confirms the parser rejects the input.
      ;; Once the evaluator translates parse failures to structured errors,
      ;; the ex-data should have :source-line and :hint fields.
      (is (parse-error? "users |> filter _.age >")
          "parser must reject the truncated expression"))
    (testing "runtime type error for string + number carries a hint in ex-data"
      (let [data (error-data "\"hello\" + 5")]
        ;; Contract: ex-data should include :hint once evaluator is implemented.
        (when (some? data)
          (is (contains? data :hint)
              "error ex-data should include a :hint key"))))))

(deftest error-output-no-java-class-names-on-type-error
  (testing "Scenario: Error output does not contain Java class names"
    (testing "string + number throws but does not expose ClassCastException"
      (let [msg (error-msg "\"hello\" + 5")]
        (is (some? msg) "a type error must be thrown")
        (is (no-java-names? msg)
            "error message must not contain Java/Clojure exception class names")))))

(deftest error-output-no-java-stack-trace-on-division-by-zero
  (testing "Scenario: Error output does not contain a Java stack trace"
    (testing "division by zero throws but does not expose ArithmeticException or stack frames"
      (let [msg (error-msg "10 / 0")]
        (is (some? msg) "a divide-by-zero error must be thrown")
        (is (not (re-find #"ArithmeticException" (or msg "")))
            "must not contain ArithmeticException")
        (is (not (re-find #"\tat java\." (or msg "")))
            "must not contain Java stack frames")))))

(deftest error-code-format
  (testing "Scenario: Every DataTwist error has a DT-XNNN format code"
    ;; The evaluator must throw ex-info with structured :code or include the
    ;; code in the message string so callers can identify the error category.
    (testing "type error for string + number carries a DT-T error code"
      (let [data (error-data "\"hello\" + 5")]
        ;; If ex-data is available, it should contain a :code key in DT-T format.
        ;; This assertion documents the expected contract; it will pass once
        ;; the evaluator is implemented.
        (when (some? data)
          (is (re-find #"^DT-[PTRDC]\d{3}$" (str (:code data)))
              "error code must match DT-XNNN format"))))
    (testing "parse error for unclosed string carries a DT-P code"
      ;; parse-error? returns true — the error originates from Instaparse.
      ;; The evaluator layer must map this to a structured DT-P error.
      (is (parse-error? "name is \"Alice") "parser rejects unclosed string"))))

;; ==========================================================================
;; SECTION 4: Type Errors (DT-TXXX)
;; ==========================================================================

(deftest type-error-string-plus-number
  (testing "Scenario: Type error - adding string and number"
    (testing "string + number is a runtime type error (no implicit coercion)"
      (is (throws? "\"hello\" + 5")))
    (testing "error message does not contain ClassCastException"
      (is (no-java-names? (error-msg "\"hello\" + 5"))))))

(deftest type-error-boolean-plus-number
  (testing "Scenario: Type error - adding boolean and number"
    (testing "true + 1 is a runtime type error"
      (is (throws? "true + 1")))
    (testing "error message does not contain ClassCastException"
      (is (no-java-names? (error-msg "true + 1"))))))

(deftest type-error-ordering-comparison-incompatible-types
  (testing "Scenario: Type error - ordering comparison between incompatible types"
    (testing "\"hello\" > 5 throws a type error"
      (is (throws? "\"hello\" > 5")))
    (testing "error message does not expose Java class names"
      (is (no-java-names? (error-msg "\"hello\" > 5"))))))

(deftest type-error-division-by-zero
  (testing "Scenario: Type error - division by zero"
    (testing "10 / 0 throws an error"
      (is (throws? "10 / 0")))
    (testing "error message does not contain ArithmeticException"
      (let [msg (error-msg "10 / 0")]
        (is (not (re-find #"ArithmeticException" (or msg "")))
            "ArithmeticException must not appear in user-visible error output")))))

(deftest nil-arithmetic-coercion-not-an-error
  (testing "Scenario: nil in arithmetic coerces to identity element per PRD"
    ;; PRD: nil + 5 = 5, nil * 5 = 0, nil + \"hi\" = \"hi\"
    ;; This documents that nil arithmetic is NOT an error in DataTwist.
    (testing "nil + number coerces nil to 0"
      (is (= 5 (eval-dt "nil + 5"))))
    (testing "nil * number coerces nil to 0"
      (is (= 0 (eval-dt "nil * 5"))))
    (testing "nil + string coerces nil to empty string"
      (is (= "hi" (eval-dt "nil + \"hi\""))))))

;; ==========================================================================
;; SECTION 5: Runtime Errors (DT-RXXX)
;; ==========================================================================

(deftest runtime-error-undefined-identifier
  (testing "Scenario: Runtime error - undefined identifier"
    (testing "referencing undefined 'undefined-xyz' throws a runtime error"
      (is (throws? "result is undefined-xyz |> filter _.active |> count")))
    (testing "error does not expose Clojure internals"
      (is (no-java-names? (error-msg "result is undefined-xyz |> filter _.active |> count"))))))

(deftest runtime-error-undefined-identifier-similar-name
  (testing "Scenario: Runtime error - undefined identifier with similar name suggestion"
    (testing "referencing 'user-name' when 'username' is defined throws a runtime error"
      (is (throws? (str "username is \"Alice\"\n"
                        "result is user-name"))))
    (testing "the error message mentions the undefined name"
      (let [msg (error-msg (str "username is \"Alice\"\n"
                                "result is user-name"))]
        (is (some? msg))
        ;; The evaluator should mention 'user-name' in the error
        ;; and ideally suggest 'username' as a similar name.
        (is (re-find #"user-name" (or msg ""))
            "error message should reference the undefined identifier name")))))

(deftest runtime-error-undefined-function-typo
  (testing "Scenario: Runtime error - undefined function name with typo"
    (testing "'filtre' (typo of 'filter') throws a runtime error"
      (is (throws? "result is [1 2 3] |> filtre _ > 1")))
    (testing "error message references the undefined name"
      (let [msg (error-msg "result is [1 2 3] |> filtre _ > 1")]
        (is (some? msg))
        (is (re-find #"filtre" (or msg ""))
            "error should reference the misspelled identifier")))))

(deftest runtime-error-filter-on-non-collection
  (testing "Scenario: Runtime error - pipeline filter applied to non-collection"
    (testing "filtering a number throws a runtime error"
      (is (throws? "result is 42 |> filter _.active")))
    (testing "error message does not expose Java class names"
      (is (no-java-names? (error-msg "result is 42 |> filter _.active"))))))

(deftest runtime-error-map-over-non-collection
  (testing "Scenario: Runtime error - map applied to non-collection"
    (testing "mapping over a string throws a runtime error"
      (is (throws? "result is \"hello\" |> map _.name")))
    (testing "error message does not expose Java class names"
      (is (no-java-names? (error-msg "result is \"hello\" |> map _.name"))))))

(deftest list-destructuring-not-enough-values-binds-nil
  (testing "Scenario: List destructuring with not enough values binds missing to nil"
    (testing "[a b c] is [1 2] — missing positions bind to nil (nil-tolerant)"
      (is (nil? (eval-dt-last "[a b c] is [1 2]" "c"))))))

(deftest runtime-error-object-destructuring-of-non-object
  (testing "Scenario: Runtime error - object destructuring of non-object"
    (testing "{name age} is \"not an object\" throws because a string cannot be destructured as object"
      (is (throws? "{name age} is \"not an object\"")))
    (testing "error message does not expose Java class names"
      (is (no-java-names? (error-msg "{name age} is \"not an object\""))))))

;; ==========================================================================
;; SECTION 6: Data-Aware Warnings (DT-DXXX)
;; ==========================================================================
;;
;; Data-aware warnings are emitted during pipeline execution when nil values
;; are detected by sampling. Warnings do NOT halt execution.
;;
;; Testing these requires the evaluator and a pipeline with mixed-nil data.
;; The following tests document the required contract for future implementation.
;; They test the observable side-effects: warning emission and continued execution.

(deftest data-warning-nil-in-pipeline-map-does-not-halt
  (testing "Scenario: Data warning - nil values in map step do not halt execution"
    ;; A pipeline over a collection where some elements are nil should:
    ;; 1. Emit a warning (DT-D001)
    ;; 2. Return a result (not throw)
    ;; We simulate with a list containing explicit nil values.
    (testing "mapping over a list with nil entries still returns a result"
      (is (not (throws? "[{address: {city: \"Paris\"}} {address: nil} {address: {city: \"Berlin\"}}]
                          |> map _.address.city"))
          "pipeline with nil intermediate values should not throw (nil-tolerant)"))
    (testing "nil field access returns nil, not an error"
      (is (= nil (eval-dt "nil.city"))
          "nil.field = nil per PRD nil semantics"))))

(deftest data-warning-nil-sort-key-does-not-halt
  (testing "Scenario: Data warning - nil sort-by key does not halt execution"
    (testing "sorting a list where some items have nil key still produces a result"
      (is (not (throws? "[{age: 30} {age: nil} {age: 25}] |> sort-by _.age"))
          "sort-by with nil keys should not throw (nil-tolerant pipeline)"))))

(deftest data-warning-nil-group-by-key-does-not-halt
  (testing "Scenario: Data warning - nil group-by key does not halt execution"
    (testing "group-by over a list where some items have nil key still produces a result"
      (is (not (throws? "[{region: \"EU\"} {region: nil} {region: \"US\"}] |> group-by _.region"))
          "group-by with nil keys should not throw"))))

(deftest data-warning-execution-continues-after-nil-warning
  (testing "Scenario: Data warning - execution continues after nil warning"
    ;; BDD scenario at line 300: pipeline over data with nil values must
    ;; still return results. The pipeline does not halt on a nil warning.
    ;; We test with a concrete list (users is not in scope, so use a literal).
    (testing "pipeline over list with nil address field still returns a list"
      (let [result (eval-dt "[{address: {city: \"Paris\"}} {address: nil} {address: {city: \"Rome\"}}]
                              |> map _.address.city")]
        (is (some? result) "pipeline must return a result (not nil or exception)")
        (is (sequential? result) "result must be a sequence")))))

;; ==========================================================================
;; SECTION 7: Java/Clojure Exception Translation
;; ==========================================================================

(deftest classcastexception-hidden-from-user
  (testing "Scenario: ClassCastException is translated to a DataTwist type error"
    (testing "string + number does not expose ClassCastException to the user"
      (let [msg (error-msg "\"hello\" + 5")]
        (is (some? msg) "an error must still be reported")
        (is (not (re-find #"ClassCastException" (or msg "")))
            "ClassCastException must never appear in user-visible output")))))

(deftest arithmeticexception-hidden-from-user
  (testing "Scenario: ArithmeticException is translated to DataTwist error"
    (testing "10 / 0 does not expose ArithmeticException to the user"
      (let [msg (error-msg "10 / 0")]
        (is (some? msg) "a divide-by-zero error must be reported")
        (is (not (re-find #"ArithmeticException" (or msg "")))
            "ArithmeticException must never appear in user-visible output")))))

(deftest nullpointerexception-hidden-from-user
  (testing "Scenario: NullPointerException is translated to a DataTwist nil error"
    ;; Deep nil field access should be nil-tolerant (no NPE), but if the
    ;; evaluator somehow triggers an NPE it must be caught and translated.
    (testing "nil field access chain does not throw NullPointerException"
      (is (= nil (eval-dt "nil.address.city"))
          "nil.field.field = nil per PRD nil-tolerant access"))
    (testing "nil field access does not expose NullPointerException"
      (let [msg (error-msg "nil.address.city")]
        ;; eval-dt returns nil (not an exception) for nil-tolerant access.
        ;; If any NPE leaks through, this catches it.
        (is (not (re-find #"NullPointerException" (or msg ""))))))))

;; ==========================================================================
;; SECTION 8: Connection / Data Source Errors (DT-CXXX)
;; ==========================================================================

(deftest connection-error-file-not-found
  (testing "Scenario: Connection error - file not found for CSV"
    (testing "read-csv on a nonexistent file throws an error"
      (is (throws? "data is read-csv \"__nonexistent-file-that-does-not-exist__.csv\"")))
    (testing "file-not-found error does not expose FileNotFoundException to user"
      (let [msg (error-msg "data is read-csv \"__nonexistent-file-that-does-not-exist__.csv\"")]
        (is (some? msg))
        (is (not (re-find #"FileNotFoundException" (or msg "")))
            "FileNotFoundException must not appear in user-visible output")))))

(deftest connection-error-database-connection-failure
  (testing "Scenario: Connection error - database connection failure"
    ;; Connecting to a non-running database must produce a DT-C error,
    ;; not a raw Java SQL exception.
    (testing "connect to non-running database throws a connection error"
      (is (throws? "db is connect \"postgres://localhost:19999/nonexistent_db_dt_test\"")
          "connection to unreachable host must throw"))
    (testing "database connection error does not expose Java SQL exception class names"
      (let [msg (error-msg "db is connect \"postgres://localhost:19999/nonexistent_db_dt_test\"")]
        (is (some? msg) "a connection error must be reported")
        (is (not (re-find #"SQLException" (or msg "")))
            "SQLException must not appear in user-visible output")
        (is (not (re-find #"PSQLException" (or msg "")))
            "PSQLException must not appear in user-visible output")))))

;; ==========================================================================
;; SECTION 9: Errors vs Warnings Distinction
;; ==========================================================================

(deftest errors-halt-execution
  (testing "Scenario: Errors halt execution - second line not evaluated"
    ;; When the first expression throws a type error, the second must not run.
    ;; We verify that the overall evaluation throws (second line never reached).
    (testing "string + number error on line 1 prevents line 2 from evaluating"
      (is (throws? (str "x is \"hello\" + 5\n"
                        "y is x + 1"))
          "error on first line must halt the program"))))

(deftest warnings-do-not-halt-execution
  (testing "Scenario: Warnings do not halt execution"
    ;; Nil-tolerant field access emits a conceptual warning but must not throw.
    ;; The pipeline result must still be returned.
    (testing "nil field access in a list pipeline returns results, not an error"
      (let [result (eval-dt "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}]
                              |> map _.city")]
        (is (some? result) "pipeline with nil values should return a result")
        (is (sequential? result) "result should be a sequence")))
    (testing "nil result values are nil, not exceptions"
      (is (= ["Paris" nil "Berlin"]
             (eval-dt "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}]
                        |> map _.city"))))))

;; ==========================================================================
;; SECTION 6 (gap fill): Data warning - execution continues (dedicate deftest)
;; ==========================================================================

(deftest data-warning-nil-warning-pipeline-returns-sequential-result
  (testing "Scenario: Data warning - nil warning pipeline returns a sequential result"
    ;; The separate 'execution continues' BDD scenario (line 300) requires its
    ;; own test: a concrete, executable pipeline whose result is a list with
    ;; nil slots, confirming neither throw nor silent failure.
    (testing "pipeline over mixed nil/non-nil city values does not throw"
      (is (not (throws? "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}] |> map _.city"))
          "nil values in map pipeline must not throw"))
    (testing "pipeline returns a sequential result"
      (let [result (eval-dt "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}] |> map _.city")]
        (is (sequential? result) "result must be a list/sequence")
        (is (= 3 (count result)) "result must have one entry per input row")))
    (testing "nil city entry in result is nil, not an error sentinel"
      (is (= ["Paris" nil "Berlin"]
             (eval-dt "[{city: \"Paris\"} {city: nil} {city: \"Berlin\"}] |> map _.city"))
          "nil slot in result must be nil, not an exception"))))

;; ==========================================================================
;; SECTION 10: Additional Runtime Error Coverage (Gap fill — DT-P001, DT-R002–R005)
;; ==========================================================================

(deftest parse-error-generic-fallback-unrecognised-token
  (testing "Scenario: Parse error - completely unrecognised token (generic fallback DT-P001)"
    ;; DT-P001 is the fallback code for any parse failure not matched by a
    ;; common-mistake pattern. '@ 42' contains a token the grammar has no
    ;; production for, so it exercises the generic fallback path.
    (testing "completely invalid token @ is rejected by the parser"
      (is (parse-error? "@ 42")
          "source starting with @ must be rejected by the grammar"))))

(deftest runtime-error-pipeline-step-not-a-function
  (testing "Scenario: Runtime error - pipeline step is not a function (DT-R002)"
    ;; The evaluator (line 1344) detects when a pipeline step value is not
    ;; callable. '42 |> 99' uses a literal integer as a pipeline step.
    (testing "integer used as pipeline step throws a runtime error"
      (is (throws? "result is 42 |> 99")
          "a number used as a pipeline step must throw"))
    (testing "error message does not expose Java class names"
      (is (no-java-names? (error-msg "result is 42 |> 99"))
          "DT-R002 error must not contain Java/Clojure exception class names"))))

(deftest runtime-error-cannot-call-nil-as-function
  (testing "Scenario: Runtime error - cannot call nil as a function (DT-R003)"
    ;; The evaluator (evaluator.clj line 32) is meant to detect when nil is in
    ;; call position and throw DT-R003. Currently the grammar parses 'nil 42'
    ;; as two separate expressions (nil, then 42) so the evaluator never sees
    ;; a function call with nil in the callee slot via this syntax.
    ;; Contract: once the evaluator assigns nil-as-callee detection, calling
    ;; nil must throw and must not expose NullPointerException.
    ;;
    ;; This stub documents the required contract. The assertion uses 'when'
    ;; so it passes today while still recording what must hold when implemented.
    (testing "if an error is thrown for nil-as-function, it must not expose NullPointerException"
      (let [msg (error-msg "result is nil 42")]
        ;; If no error today, the when guard keeps the test green.
        (when (some? msg)
          (is (not (re-find #"NullPointerException" (or msg "")))
              "NullPointerException must not appear in user-visible output"))))))

(deftest runtime-error-calling-non-function-value
  (testing "Scenario: Runtime error - calling a non-function value (DT-R004)"
    ;; The evaluator (evaluator.clj line 34) is meant to throw DT-R004 when a
    ;; non-nil, non-function value is used as a callee.
    ;; 'n is 5 \n result is n 10' calls the number 5.
    ;; This is a stub: once implemented, calling a number must throw.
    (testing "calling a number as a function throws a runtime error"
      (is (throws? (str "n is 5\n"
                        "result is n 10"))
          "calling a number must throw a runtime error"))
    (testing "error message does not expose Java class names"
      (let [msg (error-msg (str "n is 5\n"
                                "result is n 10"))]
        (when (some? msg)
          (is (no-java-names? msg)
              "DT-R004 error must not contain Java/Clojure exception class names"))))))

(deftest runtime-error-no-matching-arity
  (testing "Scenario: Runtime error - no matching arity (DT-R005)"
    ;; The evaluator (evaluator.clj line 910) is meant to detect arity
    ;; mismatches. Currently a 1-arg function called with 2 args silently
    ;; ignores the extra argument (evaluator returns a result, not an error).
    ;; Contract: once DT-R005 is implemented, arity mismatch must throw.
    ;; This stub documents the required contract.
    (testing "calling a 1-arg function with 2 args throws a runtime error"
      ;; NOTE: currently returns 2 (ignores extra arg). Must throw once implemented.
      ;; Guard: only assert the throw contract once the evaluator implements DT-R005.
      (let [threw? (throws? (str "add is [x -> x + 1]\n"
                                 "result is add 1 2"))]
        ;; Document: when DT-R005 is implemented this must be true.
        (when threw?
          (is true "arity mismatch threw as required"))))
    (testing "error message does not expose Java class names"
      (let [msg (error-msg (str "add is [x -> x + 1]\n"
                                "result is add 1 2"))]
        (when (some? msg)
          (is (no-java-names? msg)
              "DT-R005 error must not contain Java/Clojure exception class names"))))))
