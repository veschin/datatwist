(ns datatwist.parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]))

(def parser-with-indent
  (insta/parser
   (io/resource "datatwist.grammar")
   :auto-whitespace :standard))
