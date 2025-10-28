(ns grammar
  (:require [instaparse.core :as insta]
            [clojure.string :as str]))

(def parser-with-indent (insta/parser (slurp "datatwist.grammar")))
