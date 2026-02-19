(ns datatwist.parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]))

(def parser
  (insta/parser
   (io/resource "datatwist.grammar")))

(defn parse
  "Parse DataTwist source code. Returns parse tree or instaparse failure."
  [input]
  (parser input))

(defn eval-dt
  "Parse and evaluate DataTwist source code. Returns the result."
  [input]
  ;; Require lazily to avoid circular deps at load time
  ((requiring-resolve 'datatwist.evaluator/evaluate) input))

(defn parse-error?
  "Returns true if the input fails to parse."
  [input]
  (insta/failure? (parser input)))
