(ns datatwist.test-helpers
  (:require [datatwist.parser :as parser]))

(defn eval-dt
  "Evaluate a DataTwist expression string and return the result.
   Shorthand for parser/eval-dt, used in all test files."
  [input]
  (parser/eval-dt input))

(defn parse-error?
  "Returns true if the input is rejected by the parser.
   Used to test that invalid syntax produces parse errors."
  [input]
  (parser/parse-error? input))

(defn type-of
  "Evaluate a DataTwist expression and return its Clojure type (class)."
  [input]
  (let [result (eval-dt input)]
    (when (some? result)
      (class result))))

(defn eval-dt-last
  "Evaluate multiple DataTwist lines and return the value of the last expression.
   Useful for scenarios with bindings: (eval-dt-last \"x is 5\" \"x + 1\") => 6"
  [& lines]
  (eval-dt (clojure.string/join "\n" lines)))

(defn throws?
  "Returns true if evaluating the DataTwist expression throws an exception."
  [input]
  (try
    (eval-dt input)
    false
    (catch Exception _
      true)))

(defn throws-type?
  "Returns true if evaluating the DataTwist expression throws an exception of the given type."
  [input exception-class]
  (try
    (eval-dt input)
    false
    (catch Exception e
      (instance? exception-class e))))

(defn silent-eval-dt
  "Evaluate a DataTwist expression, suppressing any stdout produced (e.g. by tap!, log!, print)."
  [expr]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      (eval-dt expr))))

(defn silent-eval-dt-last
  "Evaluate multiple DataTwist lines, suppressing stdout, and return the last result."
  [& exprs]
  (let [sw (java.io.StringWriter.)]
    (binding [*out* sw]
      (apply eval-dt-last exprs))))

(defn silent-throws?
  "Returns true if evaluating the DataTwist expression throws an exception, suppressing stdout."
  [input]
  (try
    (silent-eval-dt input)
    false
    (catch Exception _
      true)))
