(ns datatwist.parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]))

(def parser
  (insta/parser
   (io/resource "datatwist.grammar")
   :auto-whitespace :standard))

(defn parse
  "Parse DataTwist source code. Returns parse tree or instaparse failure."
  [input]
  (parser input))

(defn eval-dt
  "Parse and evaluate DataTwist source code. Returns the result."
  [input]
  ;; TODO: implement parser + evaluator
  (throw (ex-info "DataTwist eval not implemented yet" {:input input})))

(defn parse-error?
  "Returns true if the input fails to parse."
  [input]
  ;; TODO: implement with real parser
  (throw (ex-info "DataTwist parse-error? not implemented yet" {:input input})))
