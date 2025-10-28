(ns grammar
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

(def parser-with-indent
  (insta/parser
   (slurp "datatwist.grammar")
   :auto-whitespace :standard))

(comment

;; cd /home/veschin/work/datadriven && clj -M -e "(load-file \"datatwist-grammar-tests.clj\") (in-ns 'datatwist-grammar-tests)
;; (run-comprehensive-tests)"
  )
