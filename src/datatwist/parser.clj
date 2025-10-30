(ns datatwist.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

(def parser-with-indent
  (insta/parser
   (slurp "resources/nonexistent-grammar.grammar")
   :auto-whitespace :standard))

(comment

;; cd /home/veschin/work/datatwist && make test
  )
