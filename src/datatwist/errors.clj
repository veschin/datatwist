(ns datatwist.errors
  "Error data structures, registry, and formatting for DataTwist.

   Canonical error map shape (thrown via ex-info):
   {:dt/error   true          ; marker — distinguishes DT errors from others
    :code       \"DT-T001\"     ; error code (DT-XNNN format)
    :category   \"TYPE MISMATCH\"  ; ALL CAPS, 2-4 words
    :message    \"I can't add...\" ; first-person prose
    :source     \"\\\"hello\\\" + 5\"  ; the offending source line(s)
    :line       1              ; 1-based line number (optional)
    :col-start  1              ; 1-based column start (optional)
    :col-end    14             ; 1-based column end (optional)
    :hint       \"Try ...\"     ; optional actionable suggestion
    :level      :error         ; :error or :warning
   }"
  (:require [instaparse.core :as insta]))

;; ---------------------------------------------------------------------------
;; Error code registry
;; ---------------------------------------------------------------------------

(def error-registry
  "Maps error code to {:category description :hint-template}."
  {;; Parse errors
   "DT-P001" {:category "PARSE ERROR"
              :description "I could not parse this expression."
              :hint "Check the syntax near the error location."}
   "DT-P002" {:category "PARSE ERROR"
              :description "Unexpected end of expression after operator."
              :hint "Add the missing right-hand side of the expression."}
   "DT-P003" {:category "PARSE ERROR"
              :description "Unclosed string literal — missing closing `\"`."
              :hint "Add a closing double-quote to terminate the string."}
   "DT-P004" {:category "PARSE ERROR"
              :description "Unclosed object — missing closing `}`."
              :hint "Add a closing brace `}` to terminate the object literal."}
   "DT-P005" {:category "PARSE ERROR"
              :description "Unclosed list — missing closing `]`."
              :hint "Add a closing bracket `]` to terminate the list literal."}
   "DT-P006" {:category "PARSE ERROR"
              :description "Missing `->` in guard branch."
              :hint "Each guard branch needs an arrow: `| condition -> result`"}
   "DT-P007" {:category "PARSE ERROR"
              :description "Missing expression after `|>`."
              :hint "Add a function or expression after the pipe operator."}
   "DT-P008" {:category "PARSE ERROR"
              :description "Unexpected `|>` — did you mean to write a function?"
              :hint "Remove the extra `|>` or add a function between them."}
   "DT-P009" {:category "PARSE ERROR"
              :description "Missing `->` in function definition."
              :hint "Function syntax is `[params -> body]`."}
   "DT-P020" {:category "COMMON MISTAKE"
              :description "DataTwist uses `is` for assignment, not `=`."
              :hint "Write `x is 42` instead of `x = 42`."}
   "DT-P021" {:category "COMMON MISTAKE"
              :description "DataTwist uses `is` for assignment, not `:=`."
              :hint "Write `x is 42` instead of `x := 42`."}
   "DT-P022" {:category "COMMON MISTAKE"
              :description "Use `->` not `=>` in function definitions."
              :hint "Write `[x -> x * 2]` instead of `[x => x * 2]`."}
   "DT-P023" {:category "COMMON MISTAKE"
              :description "Use `and` instead of `&&`."
              :hint "Write `x > 5 and y < 10` instead of `x > 5 && y < 10`."}
   "DT-P024" {:category "COMMON MISTAKE"
              :description "Use `not` instead of `!`."
              :hint "Write `not active` instead of `!active`."}
   "DT-P025" {:category "COMMON MISTAKE"
              :description "DataTwist uses spaces, not commas, to separate list items."
              :hint "Write `[1 2 3]` instead of `[1, 2, 3]`."}
   "DT-P026" {:category "COMMON MISTAKE"
              :description "DataTwist uses spaces, not commas, between object fields."
              :hint "Write `{k: v k2: v2}` instead of `{k: v, k2: v2}`."}

   ;; Type errors
   "DT-T001" {:category "TYPE MISMATCH"
              :description "Arithmetic operator used on incompatible types."
              :hint "Arithmetic operators work on numbers (or + on strings). Check your types."}
   "DT-T002" {:category "TYPE MISMATCH"
              :description "Comparison operator used on incompatible types."
              :hint "Comparison operators require compatible types (both numbers or both strings)."}
   "DT-T003" {:category "ARITHMETIC ERROR"
              :description "Division by zero."
              :hint "Check that the divisor is not zero before dividing."}

   ;; Runtime errors
   "DT-R001" {:category "UNDEFINED IDENTIFIER"
              :description "The identifier is not defined."
              :hint "Check the spelling or define the value with `is`."}
   "DT-R002" {:category "RUNTIME ERROR"
              :description "Pipeline step is not a function."
              :hint "Each step in a pipeline must be a function or expression that takes a value."}
   "DT-R003" {:category "RUNTIME ERROR"
              :description "Cannot call nil as a function."
              :hint "Check that the function value is defined and not nil."}
   "DT-R004" {:category "RUNTIME ERROR"
              :description "Value is not callable."
              :hint "Only functions can be called. Check the value type."}
   "DT-R005" {:category "ARITY ERROR"
              :description "No matching arity for this function call."
              :hint "Check the number of arguments you are passing."}
   "DT-R006" {:category "DESTRUCTURING ERROR"
              :description "Cannot destructure a non-object value."
              :hint "Object destructuring (`{field}`) requires the value to be an object."}
   "DT-R010" {:category "TYPE MISMATCH"
              :description "Collection operation applied to non-collection."
              :hint "filter and map expect a list. Check the type of the value being piped."}
   "DT-R020" {:category "NIL ERROR"
              :description "Nil value where a value was required."
              :hint "A nil propagated through an operation that requires a non-nil value."}

   ;; Data warnings
   "DT-D001" {:category "DATA WARNING"
              :description "Nil values encountered in map step."
              :hint "Some rows had nil at the accessed path. Results may contain nil."}
   "DT-D002" {:category "DATA WARNING"
              :description "Nil sort keys encountered."
              :hint "Rows with nil sort keys were sorted to the end."}
   "DT-D003" {:category "DATA WARNING"
              :description "Nil group keys encountered."
              :hint "Rows with nil group keys were grouped under nil."}

   ;; Config errors
   "DT-R030" {:category "CONFIG ERROR"
              :description "Unknown system constant."
              :hint "Valid constants: SAMPLE_SIZE, DESCRIBE_SAMPLE_SIZE, PRINT_WIDTH, MAX_COLLECT_ROWS."}

   ;; Connection errors
   "DT-C001" {:category "FILE NOT FOUND"
              :description "File not found."
              :hint "Check the file path and make sure the file exists."}
   "DT-C002" {:category "CONNECTION ERROR"
              :description "Database connection failed."
              :hint "Check that the database is running and the URI is correct."}})

;; ---------------------------------------------------------------------------
;; Error creation functions
;; ---------------------------------------------------------------------------

(defn dt-error
  "Create a DataTwist error and throw it as ex-info.
   data must include at minimum :code and :message.
   Merges defaults from error-registry if available."
  [{:keys [code message] :as data}]
  (let [registry-entry (get error-registry code {})
        category       (or (:category data) (:category registry-entry) "ERROR")
        hint           (or (:hint data) (:hint registry-entry))
        merged         (merge {:dt/error true
                               :level    :error
                               :category category}
                              (when hint {:hint hint})
                              data
                              {:category category})]
    (throw (ex-info message merged))))

;; ---------------------------------------------------------------------------
;; Warnings-as-errors strict mode
;; ---------------------------------------------------------------------------

(def ^:dynamic *warnings-as-errors*
  "When true, dt-warning throws instead of returning a warning map.
   Set via `WARNINGS_AS_ERRORS is true` in DataTwist source code."
  false)

(defn dt-warning
  "Create a DataTwist warning map. Does NOT throw. Returns the map.
   When *warnings-as-errors* is true, throws via dt-error instead."
  [{:keys [code] :as data}]
  (let [registry-entry (get error-registry code {})
        category       (or (:category data) (:category registry-entry) "WARNING")
        warning-map    (merge {:dt/error true :level :warning :category category} data)]
    (if *warnings-as-errors*
      (throw (ex-info (str (:message data "Warning treated as error in strict mode"))
                      (merge warning-map {:level :error})))
      warning-map)))

;; ---------------------------------------------------------------------------
;; Common mistake detector
;; ---------------------------------------------------------------------------

(def ^:private common-mistakes
  "Sequence of {pattern code hint} entries checked against failing source."
  [{:pattern #"^\s*\w[\w-]*\s*:=\s+"
    :code    "DT-P021"
    :hint    "Use 'is' for assignment: `x is 42`"}
   {:pattern #"^\s*\w[\w-]*\s*=\s+[^=]"
    :code    "DT-P020"
    :hint    "Use 'is' for assignment: `x is 42`"}
   {:pattern #"\[.*?=>"
    :code    "DT-P022"
    :hint    "Use '->' not '=>': `[x -> x * 2]`"}
   {:pattern #"&&"
    :code    "DT-P023"
    :hint    "Use 'and' instead of '&&'"}
   {:pattern #"(^|[^!])!\w"
    :code    "DT-P024"
    :hint    "Use 'not' instead of '!'"}
   {:pattern #"\[([^]]*),([^]]*)\]"
    :code    "DT-P025"
    :hint    "DataTwist uses spaces: `[1 2 3]`"}
   {:pattern #"\{([^}]*),([^}]*)\}"
    :code    "DT-P026"
    :hint    "DataTwist uses spaces: `{k: v k2: v2}`"}])

(defn detect-common-mistake
  "Check if source string matches a known common mistake pattern.
   Returns the matching {:code :hint} entry or nil."
  [source]
  (some (fn [{:keys [pattern code hint]}]
          (when (re-find pattern source)
            {:code code :hint hint}))
        common-mistakes))

;; ---------------------------------------------------------------------------
;; Parse failure → DT error
;; ---------------------------------------------------------------------------

(defn parse-failure->dt-error
  "Convert an Instaparse failure object into a DataTwist ex-info.
   Checks for common mistakes first; falls back to DT-P001."
  [failure source]
  (let [mistake   (detect-common-mistake source)
        fail-data (try (instaparse.core/get-failure failure) (catch Exception _ nil))
        line      (or (:line fail-data) 1)
        col       (or (:column fail-data) 1)
        registry-default (fn [code]
                           (get-in error-registry [code :description] "Parse error."))
        code      (or (:code mistake) "DT-P001")
        hint      (or (:hint mistake)
                      (get-in error-registry ["DT-P001" :hint]))
        message   (or (and mistake (registry-default (:code mistake)))
                      (str "I could not parse this expression"
                           (when (and line col)
                             (str " (line " line ", column " col ")"))
                           "."))]
    (ex-info message
             {:dt/error  true
              :level     :error
              :code      code
              :category  (get-in error-registry [code :category] "PARSE ERROR")
              :message   message
              :hint      hint
              :line      line
              :col-start col
              :source    source})))
