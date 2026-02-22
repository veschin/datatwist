(ns datatwist.stdlib
  (:require [datatwist.errors :as errors]
            [datatwist.config :as config]
            [datatwist.pattern-compiler :as pattern-compiler]))

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
    (keyword? v)    "keyword"
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

(defn- dt-median [coll]
  (if (empty? coll)
    nil
    (let [sorted (vec (sort coll))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (let [a (nth sorted (dec mid))
              b (nth sorted mid)]
          (if (and (integer? a) (integer? b) (zero? (rem (+ a b) 2)))
            (quot (+ a b) 2)
            (/ (+ (double a) (double b)) 2.0)))))))

(defn- dt-flatten [coll]
  (apply concat coll))

(defn- dt-distinct [coll]
  (distinct coll))

(defn- dt-reverse [coll]
  (vec (reverse coll)))

;; ---------------------------------------------------------------------------
;; Nil Handling functions
;; ---------------------------------------------------------------------------

(defn- dt-fill-nil
  "Replace nil values with a default. Data-first: (coll, default).
   - sequential: replaces nil elements in the list
   - map: replaces nil-valued map entries
   - scalar nil: returns default
   - non-nil scalar: returns coll unchanged"
  [coll default]
  (cond
    (nil? coll)        default
    (sequential? coll) (mapv #(if (nil? %) default %) coll)
    (map? coll)        (into {} (map (fn [[k v]] [k (if (nil? v) default v)]) coll))
    :else              coll))

(defn- dt-skip-nil
  "Remove nil entries. Data-first: (coll).
   - sequential: removes nil elements, returns vector
   - map: removes keys whose value is nil
   - scalar nil: returns []
   - non-nil scalar: returns coll unchanged"
  [coll]
  (cond
    (nil? coll)        []
    (sequential? coll) (vec (remove nil? coll))
    (map? coll)        (into {} (remove (fn [[_ v]] (nil? v)) coll))
    :else              coll))

(defn- dt-sort [coll]
  (vec (sort coll)))

;; NOTE: All collection-transforming functions use DATA-FIRST argument order
;; to match DataTwist's pipe-first semantics: `data |> map fn` = `map(data, fn)`

(defn- dt-sort-by
  "Sort a collection by a key function. Data-first: (coll, f)
   Nil-tolerant: nil keys sort last; when both keys are nil, falls back to comparing elements."
  [coll f]
  (let [entries (mapv (fn [x] [x (f x)]) coll)]
    (vec (map first
              (sort (fn [[ax ak] [bx bk]]
                      (cond
                        (and (nil? ak) (nil? bk)) (try (compare ax bx) (catch Exception _ 0))
                        (nil? ak) 1
                        (nil? bk) -1
                        :else (compare ak bk)))
                    entries)))))

(defn- dt-take
  "Take first n elements. Data-first: (coll, n)"
  [coll n]
  (take n coll))

(defn- dt-drop
  "Drop first n elements. Data-first: (coll, n)"
  [coll n]
  (drop n coll))

(defn- dt-filter
  "Filter a collection with a predicate. Data-first: (coll, pred)"
  [coll pred]
  (cond
    (nil? coll)        ()
    (sequential? coll) (filter pred coll)
    (map? coll)        (filter pred coll)
    :else (throw (ex-info (str "Cannot filter over " (type coll) ": expected a list")
                          {:value coll}))))

(defn- dt-map
  "Map a function over a collection. Data-first: (coll, f)
   When coll is a map (e.g. from group-by), iterates over entries as {:key k :value v}
   and emits DT-D001 warning (or throws in strict mode) when any result is nil.
   When coll is a sequential, returns a lazy seq — no nil scan (preserves laziness).
   Throws for non-collection inputs."
  [coll f]
  (cond
    (nil? coll)        []
    (map? coll)        (let [results (mapv (fn [[k v]] (f {:key k :value v})) coll)]
                         (when (some nil? results)
                           (errors/dt-warning {:code    "DT-D001"
                                               :message "Nil values encountered in map step — some rows had nil at the accessed path."
                                               :hint    "Some rows had nil at the accessed path. Results may contain nil."}))
                         results)
    (sequential? coll) (map f coll)
    :else (throw (ex-info (str "Cannot map over " (dt-type-of coll) ": expected a list or object")
                          {:dt/error true :code "DT-R010" :category "TYPE MISMATCH"
                           :hint "map expects a list or object. Check the type of the value being piped."
                           :value coll}))))

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
  (apply concat colls))

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
;; Exploration functions (Feature 8)
;; ---------------------------------------------------------------------------

(def ^:private SCHEMA_SAMPLE_SIZE   100)

(defn- numeric? [v]
  (and (some? v) (number? v)))

(defn- infer-type
  "Infer a DataTwist type string from a sampled column of values."
  [vals]
  (let [non-nil (remove nil? vals)]
    (cond
      (empty? non-nil)                             "nil"
      (every? integer? non-nil)                    "Integer"
      (every? number? non-nil)                     "Number"
      (every? string? non-nil)                     "String"
      (every? boolean? non-nil)                    "Boolean"
      (every? map? non-nil)                        "Object"
      (every? sequential? non-nil)                 "List"
      :else                                        "Any")))

(defn- collect-column
  "Given a seq of maps, return a vector of values for `key` (keyword)."
  [rows kw]
  (mapv #(get % kw) rows))

(defn- dt-describe
  "Statistical summary of a collection. Data-first: (coll) or (coll, sample-size).
   Returns a map of column-name -> stats map with :count :nil-count :type.
   For numeric columns also includes :min :max :sum :mean.
   For comparable non-numeric columns includes :min :max."
  ([coll] (dt-describe coll (config/get-config :DESCRIBE_SAMPLE_SIZE)))
  ([coll sample-size]
   (let [rows (vec (take sample-size coll))]
     (when (empty? rows)
       (throw (ex-info "describe requires a non-empty collection" {:value coll})))
     (let [first-row (first rows)
           _ (when-not (map? first-row)
               (throw (ex-info "describe expects a collection of maps (objects)"
                               {:dt/error true :code "DT-R010" :category "TYPE MISMATCH"
                                :hint "describe expects a list of objects. Each element must be an object with fields."
                                :value first-row})))
           column-keys (keys first-row)]
       (into {}
             (for [kw column-keys]
               (let [col-name  (name kw)
                     all-vals  (collect-column rows kw)
                     nil-count (count (filter nil? all-vals))
                     non-nil   (vec (remove nil? all-vals))
                     numeric   (vec (filter numeric? non-nil))
                     cnt       (count all-vals)
                     base      {:count cnt :nil-count nil-count :type (infer-type all-vals)}
                     stats     (cond
                                 (seq numeric)
                                 (assoc base
                                        :min  (apply min numeric)
                                        :max  (apply max numeric)
                                        :sum  (reduce + 0.0 numeric)
                                        :mean (/ (reduce + 0.0 numeric) (count numeric)))
                                 (seq non-nil)
                                 (let [comparable? (and (every? #(instance? Comparable %) non-nil)
                                                        (let [t (class (first non-nil))]
                                                          (every? #(instance? t %) non-nil)))]
                                   (if comparable?
                                     (assoc base
                                            :min (reduce (fn [a b] (if (neg? (compare a b)) a b)) non-nil)
                                            :max (reduce (fn [a b] (if (pos? (compare a b)) a b)) non-nil))
                                     base))
                                 :else base)]
                 [col-name stats])))))))

(defn- dt-schema
  "Infer column names and types from a sample of the collection.
   Returns a vector of {name: \"field\" type: \"Number\"} maps."
  [coll]
  (let [rows (vec (take SCHEMA_SAMPLE_SIZE coll))]
    (when (empty? rows)
      (throw (ex-info "schema requires a non-empty collection" {:value coll})))
    (let [first-row (first rows)
          _ (when-not (map? first-row)
              (throw (ex-info "schema expects a collection of maps (objects)"
                              {:dt/error true :code "DT-R010" :category "TYPE MISMATCH"
                               :hint "schema expects a list of objects. Each element must be an object with fields."
                               :value first-row})))
          column-keys (keys first-row)]
      (vec
       (for [kw column-keys]
         (let [col-vals  (collect-column rows kw)
               col-type  (infer-type col-vals)]
           {:name (name kw) :type col-type}))))))

(defn- dt-sample
  "Return N randomly-selected elements from a collection. Data-first: (coll) or (coll, n).
   Default N is 10. Forces partial materialization."
  ([coll] (dt-sample coll 10))
  ([coll n]
   (vec (take n (shuffle (vec coll))))))

(defn- dt-freq
  "Frequency table for a field. Data-first: (coll, accessor-fn).
   Returns a vector of {value: X count: N pct: P} sorted by count descending.
   Forces full evaluation."
  [coll f]
  (let [all-vals (mapv f (vec coll))
        total    (count all-vals)
        freqs    (frequencies all-vals)]
    (->> freqs
         (map (fn [[v c]]
                {:value v
                 :count c
                 :pct   (if (zero? total)
                          0.0
                          (* 100.0 (/ (double c) total)))}))
         (sort-by :count >)
         vec)))

(defn- dt-histogram
  "Distribution of a numeric field in a collection. Data-first: (coll, accessor-fn).
   Returns a map with :bins (list of {from: X to: Y count: N}) and :bin-count.
   Samples at most DESCRIBE_SAMPLE_SIZE rows."
  [coll f]
  (let [rows   (vec (take (config/get-config :DESCRIBE_SAMPLE_SIZE) coll))
        vals   (vec (remove nil? (map f rows)))]
    (if (empty? vals)
      {:bins [] :bin-count 10}
      (let [mn        (apply min vals)
            mx        (apply max vals)
            bin-count 10
            range-sz  (- mx mn)
            bin-width (if (zero? range-sz)
                        1.0
                        (/ (double range-sz) bin-count))
            bin-idx   (fn [v]
                        (if (zero? range-sz)
                          0
                          (min (dec bin-count)
                               (int (Math/floor (/ (- (double v) mn) bin-width))))))
            counts    (reduce (fn [acc v]
                                (let [i (bin-idx v)]
                                  (update acc i (fnil inc 0))))
                              {}
                              vals)
            bins      (vec
                       (for [i (range bin-count)]
                         {:from  (+ mn (* i bin-width))
                          :to    (+ mn (* (inc i) bin-width))
                          :count (get counts i 0)}))]
        {:bins      bins
         :bin-count bin-count}))))

(defn- dt-explain
  "Show the pipeline execution plan without accessing data.
   For concrete collections returns a summary string.
   For DTPipeline (once implemented) returns a step-by-step plan."
  [data]
  (cond
    (vector? data)
    (str "Materialized collection of " (count data) " items")
    (sequential? data)
    ;; Lazy seq — don't force it; just describe it
    "Lazy pipeline (plan not yet reified as DTPipeline)"
    :else
    (str "Value: " (dt-type-of data))))

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

(defn- resolve-ns-fn
  "Resolve a 'ns.part/fn-name' or bare 'fn-name' string to a Clojure var."
  [name-str]
  (let [last-slash (.lastIndexOf name-str "/")]
    (if (< last-slash 0)
      ;; No slash: treat as clojure.core symbol
      (when-let [v (resolve (symbol name-str))]
        (let [f (deref v)]
          (if (fn? f) (wrap-clj-fn f) f)))
      ;; Has slash: ns.part/fn-name
      (let [ns-part (subs name-str 0 last-slash)
            fn-part (subs name-str (inc last-slash))]
        (try
          (require (symbol ns-part))
          (catch Exception _ nil))
        (when-let [v (resolve (symbol (str ns-part "/" fn-part)))]
          (let [f (deref v)]
            (if (fn? f) (wrap-clj-fn f) f)))))))

(defn resolve-qualified
  "Resolve a DataTwist qualified name like 'clj/clojure.string/upper-case',
   'clj/range', or bare 'clojure.string/upper-case' to a Clojure var.
   Wraps fn results: sequential outputs are coerced to vectors."
  [name-str]
  (if (clojure.string/starts-with? name-str "clj/")
    ;; Strip 'clj/' prefix and resolve the rest
    (resolve-ns-fn (subs name-str 4))
    ;; Direct namespace/fn reference (e.g. clojure.string/upper-case)
    (when (.contains name-str "/")
      (resolve-ns-fn name-str))))

;; ---------------------------------------------------------------------------
;; autotap! sentinel — a marker value placed in the pipeline to activate
;; automatic tap! instrumentation for all subsequent steps.
;; ---------------------------------------------------------------------------

(def autotap-sentinel {:dt/autotap true})

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
   "rest"        rest
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
   "median"      dt-median
   "flatten"     dt-flatten
   "distinct"    dt-distinct
   "reverse"     dt-reverse
   "fill-nil"    dt-fill-nil
   "skip-nil"    dt-skip-nil
   ;; String operations
   "replace"     dt-replace-str
   "split"       dt-split
   "format"      dt-format
   "upper-case"  clojure.string/upper-case
   "lower-case"  clojure.string/lower-case
   "trim"        clojure.string/trim
   "str"         (fn [v] (if (and (sequential? v) (not (vector? v)))
                           (str (vec v))
                           (str v)))
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
                   ([n] (clojure.core/range n))
                   ([start end] (clojure.core/range start end))
                   ([start end step] (clojure.core/range start end step)))
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
   ;; Materialization
   "force!"      (fn [data]
                   (let [limit (config/get-config :MAX_COLLECT_ROWS)]
                     (if limit
                       (let [capped (vec (take (inc limit) data))]
                         (if (> (count capped) limit)
                           (do
                             (println (str "WARNING: Result truncated to " limit
                                           " rows (MAX_COLLECT_ROWS)"))
                             (subvec capped 0 limit))
                           capped))
                       (if (vector? data) data (vec data)))))
   ;; Infinite / lazy sequence generators
   "repeat"      (fn ([v] (clojure.core/repeat v))
                   ([n v] (clojure.core/repeat n v)))
   "iterate"     (fn [f init] (clojure.core/iterate f init))
   "cycle"       (fn [coll] (clojure.core/cycle coll))
   ;; Side-effect builtins (pre-wrapped: return first arg)
   ;; tap! -- the ONLY pipeline debug probe. Always passthrough: returns first arg unchanged.
   ;; SAMPLE_SIZE is read dynamically from config at call time.
   ;; Bare mode   : (tap! data)          -- prints "--- tap! ---" header then sample
   ;; Labeled mode: (tap! data label)    -- prints "--- label ---" header then sample
   ;; Lambda mode : (tap! data fn)       -- applies fn to sample for display only, returns original data
   "tap!"        (fn
                   ([data]
                    ;; Bare mode: print header then sample
                    (let [sample-size (config/get-config :SAMPLE_SIZE)]
                      (println "--- tap! ---")
                      (if (sequential? data)
                        (println (vec (take sample-size data)))
                        (println data))
                      data))
                   ([data label-or-fn]
                    (let [sample-size (config/get-config :SAMPLE_SIZE)]
                      (cond
                        (string? label-or-fn)
                        ;; Labeled mode: print "--- label ---" header then sample
                        (do
                          (println (str "--- " label-or-fn " ---"))
                          (if (sequential? data)
                            (println (vec (take sample-size data)))
                            (println data))
                          data)
                        (fn? label-or-fn)
                        ;; Lambda mode: apply fn to sample for display only, return original data
                        (let [sample (if (sequential? data) (vec (take sample-size data)) data)]
                          (println (label-or-fn sample))
                          data)
                        :else
                        (throw (ex-info "tap! second argument must be a string label or function"
                                        {:dt/error true :code "DT-R010" :category "TYPE MISMATCH"
                                         :message "tap! expects a string label or function as second argument"
                                         :hint "Use tap! \"label\" for labeled mode or tap! [d -> expr] for lambda mode"}))))))
   "autotap!"    autotap-sentinel
   "save!"       (fn [data & _args] data)
   ;; dtw namespace sentinel object — field access reads config dynamically
   ;; e.g. dtw.SAMPLE_SIZE → (config/get-config :SAMPLE_SIZE)
   "dtw"         (reify clojure.lang.ILookup
                   (valAt [_ k] (config/get-config k))
                   (valAt [_ k _not-found] (config/get-config k)))
   ;; Exploration functions (Feature 8)
   "describe"    dt-describe
   "schema"      dt-schema
   "sample"      dt-sample
   "freq"        dt-freq
   "histogram"   dt-histogram
   "explain"     dt-explain
   ;; Data source stubs — throw structured DT-C errors (full impl is Feature 8)
   "read-csv"    (fn [& args]
                   (let [path (first args)
                         f    (java.io.File. (str path))]
                     (if (.exists f)
                       (throw (ex-info "read-csv is not yet fully implemented"
                                       {:dt/error true :code "DT-C001" :category "FILE NOT FOUND"
                                        :hint "The file exists but read-csv is not yet implemented."}))
                       (throw (ex-info (str "File not found: " path)
                                       {:dt/error true :code "DT-C001" :category "FILE NOT FOUND"
                                        :hint (str "Check the file path: " path)})))))
   "connect"     (fn [uri]
                   (throw (ex-info (str "Connection failed: " uri)
                                   {:dt/error true :code "DT-C002" :category "CONNECTION ERROR"
                                    :hint "Check that the database is running and the URI is correct."})))
   ;; Pattern matching functions (Feature 13)
   ;; extract: apply a compiled pattern to a string, return captures map or nil
   "extract"     (fn [input pat]
                   (when (and (string? input) (map? pat) (= :pattern (:dt/type pat)))
                     (pattern-compiler/apply-pattern pat input)))
   ;; match?: boolean test — does the pattern match the string?
   "match?"      (fn [input pat]
                   (boolean (and (string? input)
                                 (map? pat)
                                 (= :pattern (:dt/type pat))
                                 (pattern-compiler/apply-pattern pat input))))})
