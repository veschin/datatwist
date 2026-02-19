(ns datatwist.stdlib)

;; ---------------------------------------------------------------------------
;; Helper functions used by the stdlib
;; ---------------------------------------------------------------------------

(defn- dt-type-of
  "Returns a string describing the DataTwist type of a value."
  [v]
  (cond
    (nil? v)        "nil"
    (map? v)        "object"
    (vector? v)     "list"
    (sequential? v) "list"
    (string? v)     "string"
    (integer? v)    "integer"
    (float? v)      "float"
    (boolean? v)    "boolean"
    (fn? v)         "function"
    :else           (.getName (class v))))

(defn- dt-get
  "Get a value from a map, converting string keys to keywords."
  ([m k]
   (if (nil? m)
     nil
     (let [kw (if (string? k) (keyword k) k)]
       (get m kw))))
  ([m k default]
   (if (nil? m)
     default
     (let [kw (if (string? k) (keyword k) k)
           result (get m kw ::not-found)]
       (if (= result ::not-found) default result)))))

(defn- dt-assoc
  "assoc with string key -> keyword conversion."
  [m k v]
  (assoc m (if (string? k) (keyword k) k) v))

(defn- dt-dissoc
  "dissoc with string key -> keyword conversion."
  [m k]
  (dissoc m (if (string? k) (keyword k) k)))

(defn- dt-contains?
  "Check if a collection contains the value. For maps, checks keyword keys."
  [coll v]
  (cond
    (nil? coll)       false
    (map? coll)       (contains? coll (if (string? v) (keyword v) v))
    (vector? coll)    (boolean (some #(= v %) coll))
    (sequential? coll) (boolean (some #(= v %) coll))
    (set? coll)       (contains? coll v)
    :else             false))

(defn- dt-keys
  "Return map keys as a vector of strings (keyword -> string)."
  [m]
  (when m
    (vec (map name (keys m)))))

(defn- dt-vals
  "Return map values as a vector."
  [m]
  (when m
    (vec (vals m))))

(defn- dt-nth
  "Return element at index, or nil for out of bounds / negative."
  [coll idx]
  (cond
    (nil? coll)    nil
    (neg? idx)     nil
    (>= idx (count coll)) nil
    :else          (nth coll idx nil)))

(defn- dt-sum [coll]
  (if (empty? coll) 0 (reduce +' 0 coll)))

(defn- dt-average [coll]
  (if (empty? coll)
    nil
    (let [s (dt-sum coll)
          n (count coll)]
      (if (and (integer? s) (zero? (rem s n)))
        (quot s n)
        (/ (double s) n)))))

(defn- dt-flatten [coll]
  (vec (apply concat coll)))

(defn- dt-distinct [coll]
  (vec (distinct coll)))

(defn- dt-reverse [coll]
  (vec (reverse coll)))

(defn- dt-sort [coll]
  (vec (sort coll)))

;; NOTE: All collection-transforming functions use DATA-FIRST argument order
;; to match DataTwist's pipe-first semantics: `data |> map fn` = `map(data, fn)`

(defn- dt-sort-by
  "Sort a collection by a key function. Data-first: (coll, f)
   Nil-tolerant: when key fn returns nil, falls back to element itself as key."
  [coll f]
  (vec (sort-by (fn [x] (let [k (f x)] (if (nil? k) x k))) coll)))

(defn- dt-take
  "Take first n elements. Data-first: (coll, n)"
  [coll n]
  (vec (take n coll)))

(defn- dt-drop
  "Drop first n elements. Data-first: (coll, n)"
  [coll n]
  (vec (drop n coll)))

(defn- dt-filter
  "Filter a collection with a predicate. Data-first: (coll, pred)"
  [coll pred]
  (vec (filter pred coll)))

(defn- dt-map
  "Map a function over a collection. Data-first: (coll, f)
   When coll is a map (e.g. from group-by), iterates over entries as {:key k :value v}."
  [coll f]
  (if (map? coll)
    (vec (map (fn [[k v]] (f {:key k :value v})) coll))
    (vec (map f coll))))

(defn- dt-reduce
  "Reduce a collection. (coll, f) or (coll, f, init)"
  ([coll f] (reduce f coll))
  ([coll f init] (reduce f init coll)))

(defn- dt-group-by
  "Group a collection by a key function. Data-first: (coll, f).
   Returns a Clojure map with string keys."
  [coll f]
  (let [grouped (group-by f coll)]
    ;; Convert keys to strings for DataTwist compatibility
    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (vec v)]) grouped))))

(defn- dt-each
  "Apply a side-effecting function to each element. Data-first: (coll, f)."
  [coll f]
  (doseq [item coll] (f item))
  coll)

(defn- dt-concat [& colls]
  (vec (apply concat colls)))

(defn- dt-into [target src]
  (if (vector? target)
    (vec (into target src))
    (into target src)))

(defn- dt-conj [coll v]
  (if (vector? coll)
    (conj coll v)
    (vec (conj (vec coll) v))))

(defn- dt-merge [& maps]
  (apply merge maps))

(defn- dt-select-keys [m ks]
  (select-keys m (map #(if (string? %) (keyword %) %) ks)))

(defn- dt-update [m k f]
  (update m (if (string? k) (keyword k) k) f))

(defn- dt-append [coll v]
  (if (vector? coll)
    (conj coll v)
    (vec (concat coll [v]))))

(defn- dt-prepend [v coll]
  (vec (into [v] coll)))

(defn- dt-replace-str [s from to]
  (clojure.string/replace s from to))

(defn- dt-split [s pattern]
  (vec (clojure.string/split s pattern)))

(defn- dt-format [fmt & args]
  (apply format fmt args))

(defn- dt-print [& args]
  (apply println args))

(defn- dt-not= [a b]
  (not= a b))

(defn- dt-partial [f & args]
  (apply partial f args))

(defn- dt-zip [& colls]
  (vec (apply map vector colls)))

(defn- dt-partition [coll n]
  (vec (map vec (partition n coll))))

(defn- dt-frequencies [coll]
  (frequencies coll))

(defn- dt-join
  ([coll] (clojure.string/join coll))
  ([coll sep] (clojure.string/join sep coll)))

(defn- dt-substring
  ([s start] (subs s start))
  ([s start end] (subs s start end)))

;; ---------------------------------------------------------------------------
;; QualifiedName resolver — handles clj/some.ns/fn-name interop
;; ---------------------------------------------------------------------------

(defn- wrap-clj-fn
  "Wrap a Clojure interop function so that sequential results are coerced to vectors."
  [f]
  (fn [& args]
    (let [result (apply f args)]
      (if (and (sequential? result) (not (vector? result)))
        (vec result)
        result))))

(defn resolve-qualified
  "Resolve a DataTwist qualified name like 'clj/clojure.string/upper-case'
   or 'clj/range' (clojure.core) to a Clojure var.
   Wraps fn results: sequential outputs are coerced to vectors."
  [name-str]
  (when (clojure.string/starts-with? name-str "clj/")
    (let [rest-str   (subs name-str 4)
          last-slash (.lastIndexOf rest-str "/")]
      (if (< last-slash 0)
        ;; No extra namespace: 'clj/range' → clojure.core/range
        (when-let [v (resolve (symbol rest-str))]
          (let [f (deref v)]
            (if (fn? f) (wrap-clj-fn f) f)))
        ;; Has namespace: 'clj/clojure.string/upper-case'
        (let [ns-part (subs rest-str 0 last-slash)
              fn-part (subs rest-str (inc last-slash))]
          (try
            (require (symbol ns-part))
            (catch Exception _ nil))
          (when-let [v (resolve (symbol (str ns-part "/" fn-part)))]
            (let [f (deref v)]
              (if (fn? f) (wrap-clj-fn f) f))))))))

;; ---------------------------------------------------------------------------
;; Default environment
;; ---------------------------------------------------------------------------

(defn default-env
  "Return the default DataTwist environment with all built-in functions."
  []
  {"count"       count
   "first"       first
   "last"        last
   "nth"         dt-nth
   "rest"        (comp vec rest)
   "keys"        dt-keys
   "vals"        dt-vals
   "values"      dt-vals
   "get"         dt-get
   "contains?"   dt-contains?
   "empty?"      empty?
   "merge"       dt-merge
   "assoc"       dt-assoc
   "dissoc"      dt-dissoc
   "conj"        dt-conj
   "concat"      dt-concat
   "into"        dt-into
   "select-keys" dt-select-keys
   "update"      dt-update
   "append"      dt-append
   "prepend"     dt-prepend
   "length"      count
   "size"        count
   "type"        dt-type-of
   "type-of"     dt-type-of
   "not="        dt-not=
   ;; Higher-order collection ops
   "map"         dt-map
   "filter"      dt-filter
   "reduce"      dt-reduce
   "each"        dt-each
   "group-by"    dt-group-by
   "sort"        dt-sort
   "sort-by"     dt-sort-by
   "take"        dt-take
   "drop"        dt-drop
   "sum"         dt-sum
   "average"     dt-average
   "flatten"     dt-flatten
   "distinct"    dt-distinct
   "reverse"     dt-reverse
   ;; String operations
   "replace"     dt-replace-str
   "split"       dt-split
   "format"      dt-format
   "upper-case"  clojure.string/upper-case
   "lower-case"  clojure.string/lower-case
   "trim"        clojure.string/trim
   "str"         str
   "int"         int
   "double"      double
   "str?"        string?
   "int?"        integer?
   "float?"      float?
   "bool?"       boolean?
   "nil?"        nil?
   "fn?"         fn?
   "map?"        map?
   "vec?"        vector?
   ;; I/O
   "print"       dt-print
   "println"     println
   ;; Functional
   "partial"     dt-partial
   "comp"        comp
   "identity"    identity
   "constantly"  constantly
   ;; Clojure interop atoms
   "atom"        atom
   "deref"       deref
   "reset!"      reset!
   "swap!"       swap!
   "inc"         inc
   "dec"         dec
   "max"         max
   "min"         min
   "abs"         #(Math/abs (double %))
   ;; Math
   "sqrt"        #(Math/sqrt (double %))
   "round"       #(Math/round (double %))
   "ceil"        #(long (Math/ceil (double %)))
   "floor"       #(long (Math/floor (double %)))
   "pow"         #(Math/pow (double %1) (double %2))
   "even?"       even?
   "odd?"        odd?
   ;; Type conversion
   "to-string"   str
   "to-int"      #(if (string? %) (Long/parseLong %) (long %))
   "to-float"    #(if (string? %) (Double/parseDouble %) (double %))
   ;; Collection extras
   "range"       (fn
                   ([n] (vec (range n)))
                   ([start end] (vec (range start end)))
                   ([start end step] (vec (range start end step))))
   "apply"       (fn [f coll] (apply f coll))
   "zip"         dt-zip
   "partition"   dt-partition
   "frequencies" dt-frequencies
   "avg"         dt-average
   ;; String extras
   "join"        dt-join
   "starts-with?" clojure.string/starts-with?
   "ends-with?"  clojure.string/ends-with?
   "includes?"   clojure.string/includes?
   "substring"   dt-substring
   ;; Side-effect builtins (pre-wrapped: return first arg)
   "log!"        (fn [data & msgs] (apply println msgs) data)
   "tap!"        (fn [data f] (f data) data)
   "save!"       (fn [data & _args] data)})
