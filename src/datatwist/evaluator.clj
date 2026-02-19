(ns datatwist.evaluator
  (:require [instaparse.core :as insta]
            [datatwist.env :as env]
            [datatwist.stdlib :as stdlib]
            [datatwist.parser :as parser]))

;; ---------------------------------------------------------------------------
;; Forward declarations (all private helpers)
;; ---------------------------------------------------------------------------

(declare eval-node eval-expr)
(declare eval-fndef eval-multi-arity)
(declare parse-params bind-params)
(declare eval-body make-clj-fn)
(declare bind-destruct-obj bind-destruct-list)
(declare eval-guard-block)
(declare eval-pipeline eval-pipe-atom-with-fn-call)
(declare eval-try-catch)

;; Dynamic flag to indicate Constructor is being evaluated in a callable context.
;; When true, Constructor returns a fn; when false, it invokes immediately (0 args).
(def ^:dynamic *constructor-callable?* false)

;; ---------------------------------------------------------------------------
;; Helper: apply a callable to arguments
;; ---------------------------------------------------------------------------

(defn- apply-fn
  "Call f with args. Handles Clojure fns."
  [f args]
  (when (nil? f)
    (throw (ex-info "Cannot call nil as a function" {:args args})))
  (when-not (fn? f)
    (throw (ex-info "Not a function" {:value f :type (type f)})))
  (apply f args))

;; ---------------------------------------------------------------------------
;; Transparent wrappers
;; ---------------------------------------------------------------------------

(def ^:private transparent-tags
  #{:Expr :CodeExpr :PipeExpr :PipeAtom :OrExpr :AndExpr :NotExpr :NilCoalesce
    :CompExpr :InExpr :AddExpr :MulExpr :UnaryExpr :FnCallExpr
    :FieldAccess :Atom})

;; ---------------------------------------------------------------------------
;; Wildcard detection
;; ---------------------------------------------------------------------------

(defn- contains-wildcard?
  "Returns true if the AST node contains a Wildcard anywhere,
   NOT descending into FnDef nodes (which have their own _ scope)."
  [node]
  (cond
    (not (vector? node)) false
    (= :Wildcard (first node)) true
    ;; Don't descend into FnDef — _ inside a function is the function's own scope
    (= :FnDef (first node)) false
    :else (some contains-wildcard? (rest node))))

;; ---------------------------------------------------------------------------
;; Descent helper
;; ---------------------------------------------------------------------------

(defn- descend-to-inner
  "Descend through single-child transparent wrappers to find the real node."
  [node]
  (if (and (vector? node)
           (contains? transparent-tags (first node))
           (= 1 (count (rest node))))
    (recur (second node))
    node))

;; ---------------------------------------------------------------------------
;; Object entry evaluation
;; ---------------------------------------------------------------------------

(defn- has-add-remove-fields?
  "Check if object children contain AddField or RemoveField entries."
  [children]
  (some (fn [entry]
          (when (and (vector? entry) (= :StandardEntry (first entry)))
            (let [part (first (rest entry))]
              (and (vector? part)
                   (#{:AddField :RemoveField} (first part))))))
        children))

(defn- eval-object-entries
  "Evaluate StandardEntry children, threading both the map accumulator AND env.
   Returns [result-map env'] to support forward-referencing in AddField."
  [children env init-map]
  (reduce
   (fn [[m e] entry]
     (if (not= :StandardEntry (first entry))
       [m e]
       (let [part (first (rest entry))]
         (cond
           ;; AddField: {+score: expr}
           (and (vector? part) (= :AddField (first part)))
           (let [key-name (second (second part))
                 key      (keyword key-name)
                 val      (eval-node (nth part 2) e)
                 new-env  (env/bind e key-name val)]
             [(assoc m key val) new-env])

           ;; RemoveField: {-tmp}
           (and (vector? part) (= :RemoveField (first part)))
           (let [key (keyword (second (second part)))]
             [(if (map? m) (dissoc m key) m) e])

           ;; Regular: {name: expr}
           (and (vector? part) (= :Identifier (first part)))
           (let [key-name (second part)
                 key      (keyword key-name)
                 val      (eval-node (second (rest entry)) e)
                 new-env  (env/bind e key-name val)]
             [(assoc m key val) new-env])

           :else [m e]))))
   [init-map env]
   children))

;; ---------------------------------------------------------------------------
;; Core evaluator: eval-node
;; ---------------------------------------------------------------------------

(defn eval-node
  "Evaluate an AST node and return its value. Env is not modified."
  [node env]
  (if-not (vector? node)
    node
    (let [tag      (first node)
          children (vec (rest node))]
      (cond

        ;; --- Program ---
        (= :Program tag)
        (let [[val _] (reduce (fn [[_v e] child]
                                (eval-expr child e))
                              [nil env]
                              children)]
          val)

        ;; --- Transparent wrappers with 1 child ---
        (and (contains? transparent-tags tag)
             (= 1 (count children)))
        (eval-node (first children) env)

        ;; --- Literals ---
        (= :Integer tag)
        (let [s (first children)]
          (try (Long/parseLong s)
               (catch NumberFormatException _
                 (bigint s))))

        (= :Float tag)
        (Double/parseDouble (first children))

        (= :String tag)
        (let [s (first children)]
          (-> s
              (.replace "\\n" "\n")
              (.replace "\\t" "\t")
              (.replace "\\r" "\r")
              (.replace "\\\"" "\"")
              (.replace "\\\\" "\\")))

        (= :Boolean tag)
        (= "true" (first children))

        (= :Nil tag)
        nil

        (= :Keyword tag)
        (keyword (subs (first children) 1))

        (= :Wildcard tag)
        (env/lookup env "_")

        (= :Identifier tag)
        (env/lookup env (first children))

        (= :ParenExpr tag)
        (eval-node (first children) env)

        (= :Atom tag)
        (eval-node (first children) env)

        ;; --- QualifiedName: clj/some.ns/fn, alias/fn, or Java ClassName/member ---
        (= :QualifiedName tag)
        (let [name-str (first children)
              ;; Expand alias prefix if registered via `require ... as alias`
              aliases   (env/lookup env "__aliases__")
              expanded  (if (and aliases (.contains name-str "/"))
                          (let [slash-idx (.indexOf name-str "/")
                                prefix    (subs name-str 0 slash-idx)
                                suffix    (subs name-str (inc slash-idx))]
                            (if-let [full-ns (get aliases prefix)]
                              (str full-ns "/" suffix)
                              name-str))
                          name-str)
              ;; Try to resolve as Java static field/method.
              ;; Detects ClassName/member (e.g. Math/PI) or fully-qualified java.lang.Integer/parseInt.
              ;; Uses Class/forName, falling back if not a Java class.
              java-static
              (when (.contains expanded "/")
                (let [slash-idx (.indexOf expanded "/")
                      cls-part  (subs expanded 0 slash-idx)
                      mbr-part  (subs expanded (inc slash-idx))
                      ;; Expand short class names to java.lang.* if they start with uppercase
                      full-cls  (if (and (not (.contains cls-part "."))
                                         (Character/isUpperCase (.charAt cls-part 0)))
                                  (str "java.lang." cls-part)
                                  cls-part)]
                  (try
                    (let [cls (Class/forName full-cls)]
                      ;; Try field first, then wrap as static method fn
                      (try
                        (clojure.lang.Reflector/getStaticField cls mbr-part)
                        (catch Exception _
                          (fn [& args]
                            (clojure.lang.Reflector/invokeStaticMethod
                             cls mbr-part (into-array Object args))))))
                    (catch Exception _ nil))))]
          (or java-static
              (stdlib/resolve-qualified expanded)
              (env/lookup env name-str)))

        ;; --- InstanceMethod: .methodName ---
        (= :InstanceMethod tag)
        (let [method-name (subs (first children) 1)]
          (fn [obj & args]
            (clojure.lang.Reflector/invokeInstanceMethod
             obj method-name (into-array Object args))))

        ;; --- Constructor: SomeClass. ---
        (= :Constructor tag)
        (let [class-name (subs (first children) 0 (dec (count (first children))))]
          (if *constructor-callable?*
            ;; In a FnCall context: return a fn to be called with args
            (fn [& args]
              (clojure.lang.Reflector/invokeConstructor
               (Class/forName class-name)
               (into-array Object args)))
            ;; Standalone value: invoke immediately with zero args
            (clojure.lang.Reflector/invokeConstructor
             (Class/forName class-name)
             (into-array Object []))))

        ;; --- NotExpr ---
        (= :NotExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          ;; ["not" child]: first child is string "not", second is the expr
          (not (eval-node (second children) env)))

        ;; --- OrExpr: multiple children are operands (KW-OR hidden) ---
        (= :OrExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          ;; Like Clojure's `or`: return first truthy, or last value if all falsy
          (loop [remaining children]
            (let [val (eval-node (first remaining) env)
                  more (rest remaining)]
              (if (or val (empty? more))
                val
                (recur more)))))

        ;; --- AndExpr: multiple children are operands (KW-AND hidden) ---
        (= :AndExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (loop [remaining children
                 last-val  nil]
            (if (empty? remaining)
              last-val
              (let [val (eval-node (first remaining) env)]
                (if-not val val (recur (rest remaining) val))))))

        ;; --- NilCoalesce: a ?? b ?? c ---
        (= :NilCoalesce tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (loop [remaining children]
            (if (= 1 (count remaining))
              (eval-node (first remaining) env)
              (let [val (eval-node (first remaining) env)]
                (if (some? val) val (recur (rest remaining)))))))

        ;; --- InExpr: elem in coll ---
        (= :InExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (let [elem (eval-node (first children) env)
                coll (eval-node (second children) env)]
            (cond
              (nil? coll)         nil
              (map? coll)         (contains? coll (if (string? elem) (keyword elem) elem))
              (sequential? coll)  (boolean (some #(= elem %) coll))
              (set? coll)         (contains? coll elem)
              :else               false)))

        ;; --- CompExpr: a op b ---
        (= :CompExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (let [left  (eval-node (first children) env)
                op    (second (second children))
                right (eval-node (nth children 2) env)]
            (letfn [(both-numbers? [l r]
                      (and (number? l) (number? r)))
                    (both-strings? [l r]
                      (and (string? l) (string? r)))
                    (numeric-equal [l r]
                      (if (both-numbers? l r)
                        (== l r)
                        (= l r)))
                    (ordering-compare [l r cmp-fn]
                      ;; Three-valued logic: nil in ordering → nil (unknown)
                      ;; Like SQL NULL: nil > 5 = nil, nil < 5 = nil
                      ;; nil is falsy, so filter _.age > 18 drops nil ages
                      (cond
                        (or (nil? l) (nil? r)) nil
                        (both-numbers? l r)    (cmp-fn l r)
                        (both-strings? l r)    (cmp-fn (compare l r) 0)
                        :else (throw (ex-info "Cannot compare" {:left l :right r}))))]
              (case op
                "="  (numeric-equal left right)
                "!=" (not (numeric-equal left right))
                ">"  (ordering-compare left right >)
                "<"  (ordering-compare left right <)
                ">=" (ordering-compare left right >=)
                "<=" (ordering-compare left right <=)))))

;; --- AddExpr ---
        (= :AddExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (let [pairs (partition 2 (rest children))
                init  (eval-node (first children) env)]
            (reduce (fn [left [op-node right-node]]
                      (let [op    (second op-node)
                            right (eval-node right-node env)]
                        (case op
                          "+" (cond
                                (and (nil? left) (nil? right)) 0
                                (nil? left)  right
                                (nil? right) left
                                (and (string? left) (string? right)) (str left right)
                                (and (number? left) (number? right)) (+' left right)
                                :else (throw (ex-info "Cannot add" {:left left :right right})))
                          "-" (cond
                                (and (nil? left) (nil? right)) 0
                                (nil? left)  (-' right)
                                (nil? right) left
                                (and (number? left) (number? right)) (-' left right)
                                :else (throw (ex-info "Cannot subtract" {:left left :right right}))))))
                    init
                    pairs)))

        ;; --- MulExpr ---
        (= :MulExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (let [pairs (partition 2 (rest children))
                init  (eval-node (first children) env)]
            (reduce (fn [left [op-node right-node]]
                      (let [op    (second op-node)
                            right (eval-node right-node env)]
                        (case op
                          "*" (cond
                                (or (nil? left) (nil? right))
                                ;; nil * X = 0 (preserves float if other is float)
                                (let [other (if (nil? left) right left)]
                                  (if (float? other) 0.0 0))
                                (and (number? left) (number? right)) (*' left right)
                                :else (throw (ex-info "Cannot multiply" {:left left :right right})))
                          "/" (let [l-raw (if (nil? left) 0 left)
                                    r-raw (if (nil? right) 0 right)]
                                ;; Integer zero divisor throws ArithmeticException
                                (if (and (integer? r-raw) (zero? r-raw))
                                  (throw (ArithmeticException. "Divide by zero"))
                                  ;; Division always returns Double
                                  (/ (double l-raw) (double r-raw))))
                          "%" (let [l (if (nil? left)  0 left)
                                    r (if (nil? right) 0 right)]
                                (mod l r)))))
                    init
                    pairs)))

        ;; --- UnaryExpr ---
        (= :UnaryExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          ;; ["-" FnCallExpr]
          (let [val (eval-node (second children) env)]
            (if (nil? val) 0 (-' val))))

        ;; --- FnCallExpr: dispatch to FnCall or FieldAccess ---
        (= :FnCallExpr tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (eval-node (first children) env))

        ;; --- FnCall ---
        (= :FnCall tag)
        (let [call-target (first children)
              call-args   (rest children)
              ;; Evaluate call target with constructor-callable? = true so that
              ;; Constructor nodes return a fn rather than auto-invoking with 0 args.
              f           (binding [*constructor-callable?* true]
                            (eval-node call-target env))
              args        (mapv #(eval-node (second %) env) call-args)]
          (apply-fn f args))

        ;; --- Recur ---
        (= :Recur tag)
        (let [args (mapv #(eval-node (second %) env) children)]
          (throw (ex-info "::recur" {:args args})))

        ;; --- CallTarget ---
        (= :CallTarget tag)
        (let [base       (eval-node (first children) env)
              field-names (rest children)]
          (reduce (fn [obj fn-node]
                    (let [fname (second fn-node)]
                      (when (some? obj)
                        (get obj (keyword fname)))))
                  base
                  field-names))

        ;; --- FieldAccess ---
        (= :FieldAccess tag)
        (if (= 1 (count children))
          (eval-node (first children) env)
          (let [base-node (first children)
                obj       (eval-node base-node env)
                fields    (rest children)
                ;; If base is a ParenExpr and obj is a function, auto-call it
                ;; This handles `(get-user).name` → call get-user() then access .name
                resolved  (let [base-inner (descend-to-inner base-node)]
                            (if (and (fn? obj)
                                     (vector? base-inner)
                                     (= :ParenExpr (first base-inner)))
                              (apply-fn obj [])
                              obj))]
            (reduce (fn [val fn-node]
                      (let [fname (second fn-node)]
                        (when (some? val)
                          (cond
                            ;; Map access
                            (map? val)
                            (get val (keyword fname))
                            ;; Exception: .message -> .getMessage() (empty string if nil)
                            (and (instance? Throwable val) (= fname "message"))
                            (or (.getMessage ^Throwable val) "")
                            ;; Exception: .type -> class name
                            (and (instance? Throwable val) (= fname "type"))
                            (.getSimpleName (.getClass val))
                            ;; Try map access as fallback for other objects
                            :else
                            (try
                              (get val (keyword fname))
                              (catch Exception _ nil))))))
                    resolved
                    fields)))

        ;; --- Object literal ---
        (= :Object tag)
        (if (empty? children)
          {}
          (let [first-child (first children)]
            (if (and (vector? first-child)
                     (= :ShorthandContent (first first-child)))
              ;; ShorthandContent = Identifier (<','> ShorthandEntry)+
              ;; The object in shorthand notation {name, age} copies _.name and _.age
              (let [sc-children (rest first-child)
                    ;; sc-children: [Identifier ShorthandEntry ShorthandEntry ...]
                    ;; leading Identifier is the first shorthand field
                    leading-id  (first sc-children)
                    rest-entries (rest sc-children)]
                (let [ctx (env/lookup env "_")]
                  ;; First: add the leading identifier
                  (let [base-map (let [k (keyword (second leading-id))]
                                   {k (get (or ctx {}) k)})]
                    (reduce (fn [m se]
                              ;; ShorthandEntry = Identifier ':' PipeExpr / Identifier
                              (let [se-children (rest se)]
                                (if (= 1 (count se-children))
                                  ;; Bare: {name, age} -> copy _.age
                                  (let [k (keyword (second (first se-children)))]
                                    (assoc m k (get (or ctx {}) k)))
                                  ;; With value: {name, age: expr}
                                  (let [k   (keyword (second (first se-children)))
                                        val (eval-node (second se-children) env)]
                                    (assoc m k val)))))
                            base-map
                            rest-entries))))
              ;; StandardContent
              (let [init (if (has-add-remove-fields? children)
                           (or (env/lookup env "_") {})
                           {})
                    [result _] (eval-object-entries children env init)]
                result))))

        ;; --- List literal ---
        (= :List tag)
        (if (empty? children)
          []
          (mapv #(eval-node % env) children))

        ;; --- FnDef: [params -> body] ---
        (= :FnDef tag)
        (eval-fndef children env)

        ;; --- MultiArityFn ---
        (= :MultiArityFn tag)
        (eval-multi-arity children env)

        ;; --- Pipeline ---
        (= :Pipeline tag)
        (let [source-node (first children)
              steps       (rest children)
              data        (eval-node source-node env)]
          (eval-pipeline data steps env))

        ;; --- SourcelessPipeline ---
        (= :SourcelessPipeline tag)
        (let [steps children]
          (fn [data]
            (eval-pipeline data steps env)))

        ;; --- Compose ---
        (= :Compose tag)
        (let [;; children: [OrExpr ComposeOp OrExpr ...]
              fns-and-ops (partition-all 2 children)
              first-fn    (eval-node (first children) env)
              pairs       (partition 2 (rest children))]
          (reduce (fn [f-so-far [op-node next-fn-node]]
                    (let [op      (second op-node)
                          next-fn (eval-node next-fn-node env)]
                      (case op
                        ">>" (fn [x] (next-fn (f-so-far x)))
                        "<<" (fn [x] (f-so-far (next-fn x))))))
                  first-fn
                  pairs))

        ;; --- GuardBlock ---
        (= :GuardBlock tag)
        (eval-guard-block children env)

        ;; --- Binding (in eval-node context: just return RHS value) ---
        (= :Binding tag)
        (eval-node (second children) env)

        ;; --- Expr/PipeExpr/PipeAtom: multi-child versions ---
        (or (= :Expr tag) (= :CodeExpr tag) (= :PipeExpr tag) (= :PipeAtom tag))
        (eval-node (first children) env)

        ;; --- Require (stub) ---
        (= :Require tag)
        nil

        ;; --- TryCatch ---
        (= :TryCatch tag)
        (eval-try-catch children env)

        ;; --- Fallthrough ---
        :else
        (do
          (when (System/getProperty "dt.debug")
            (println "WARNING: unhandled AST tag" tag "children:" children))
          nil)))))

;; ---------------------------------------------------------------------------
;; Function definition
;; ---------------------------------------------------------------------------

(defn- parse-params
  "Parse FnParams children ([:FnParam ...]) into param descriptors."
  [param-nodes]
  (mapv (fn [param-node]
          (when (and (vector? param-node) (= :FnParam (first param-node)))
            (let [inner (second param-node)]
              (when (vector? inner)
                (case (first inner)
                  :Identifier          {:type :normal :name (second inner)}
                  :RestParam           {:type :rest   :name (second (second inner))}
                  :Wildcard            {:type :wildcard}
                  :DestructObjPattern  {:type :destruct-obj  :node inner}
                  :DestructListPattern {:type :destruct-list :node inner}
                  {:type :normal :name (str inner)})))))
        param-nodes))

(defn- count-fixed-params [params]
  (count (filter #(and (some? %) (not= :rest (:type %))) params)))

(defn- has-rest? [params]
  (boolean (some #(and (some? %) (= :rest (:type %))) params)))

(defn- bind-destruct-obj
  "Bind object destructuring pattern to val. Returns updated env."
  [pattern-node val env]
  (let [fields (rest pattern-node)]
    (reduce (fn [e field]
              (let [field-tag      (first field)
                    field-children (rest field)]
                (if (not= :DestructObjField field-tag)
                  e
                  (let [id-node       (first field-children)
                        id-name       (second id-node)
                        rest-children (rest field-children)]
                    (if (empty? rest-children)
                      ;; Shorthand: {name} -> bind name = val.name
                      (let [field-val (get (or val {}) (keyword id-name))]
                        (env/bind e id-name field-val))
                      (let [sub (first rest-children)]
                        (cond
                          ;; {field: SubPattern}
                          (and (vector? sub) (= :DestructSubPattern (first sub)))
                          (let [sub-inner  (second sub)
                                field-val  (get (or val {}) (keyword id-name))]
                            (case (first sub-inner)
                              :Identifier          (env/bind e (second sub-inner) field-val)
                              :DestructObjPattern  (bind-destruct-obj sub-inner field-val e)
                              :DestructListPattern (bind-destruct-list sub-inner field-val e)
                              (env/bind e id-name field-val)))
                          ;; {field ? default} — use default only when KEY is absent
                          :else
                          (let [m           (or val {})
                                field-kw    (keyword id-name)
                                has-key?    (contains? m field-kw)
                                field-val   (get m field-kw)
                                result-val  (if has-key? field-val (eval-node sub e))]
                            (env/bind e id-name result-val)))))))))
            env
            fields)))

(defn- bind-destruct-list
  "Bind list destructuring pattern to val. Returns updated env.
   If val is not sequential, binds all variables to nil."
  [pattern-node val env]
  (if (and (some? val) (not (sequential? val)))
    ;; Non-list value: bind all pattern vars to nil
    (let [elems-node    (second pattern-node)
          elem-children (rest elems-node)
          rest-binding  (first (filter #(and (vector? %) (= :RestBinding (first %))) elem-children))
          regular-elems (filter #(and (vector? %) (= :DestructListElem (first %))) elem-children)
          e (reduce (fn [e elem-node]
                      (let [inner (second elem-node)]
                        (if (and (vector? inner) (= :Identifier (first inner)))
                          (env/bind e (second inner) nil)
                          e)))
                    env regular-elems)]
      (if rest-binding
        (let [rest-inner (second rest-binding)]
          (if (and (vector? rest-inner) (= :Identifier (first rest-inner)))
            (env/bind e (second rest-inner) nil)
            e))
        e))
    ;; Normal: sequential or nil
    (let [elems-node (second pattern-node)
          coll       (or val [])]
      (let [elem-children  (rest elems-node)
            rest-binding   (first (filter #(and (vector? %) (= :RestBinding (first %))) elem-children))
            regular-elems  (filter #(and (vector? %) (= :DestructListElem (first %))) elem-children)]
        (let [e (reduce-kv
                 (fn [e idx elem-node]
                   (let [inner      (second elem-node)
                         val-at-idx (nth coll idx nil)]
                     (case (first inner)
                       :Identifier          (env/bind e (second inner) val-at-idx)
                       :Wildcard            e
                       :DestructObjPattern  (bind-destruct-obj inner val-at-idx e)
                       :DestructListPattern (bind-destruct-list inner val-at-idx e)
                       e)))
                 env
                 (vec regular-elems))]
          (if rest-binding
            (let [rest-inner (second rest-binding)
                  rest-val   (let [r (drop (count regular-elems) coll)]
                               (when (seq r) (vec r)))
                  rest-name  (case (first rest-inner)
                               :Identifier (second rest-inner)
                               :Wildcard   nil
                               nil)]
              (if rest-name
                (env/bind e rest-name rest-val)
                e))
            e))))))

(defn- bind-params
  "Bind arguments to param descriptors, returning updated env."
  [params args env]
  (loop [ps params
         as (vec args)
         e  env]
    (if (empty? ps)
      e
      (let [p (first ps)]
        (cond
          (nil? p)
          (recur (rest ps) (rest as) e)

          (= :rest (:type p))
          (env/bind e (:name p) (vec as))

          (= :wildcard (:type p))
          (recur (rest ps) (rest as) e)

          (= :destruct-obj (:type p))
          (let [new-env (bind-destruct-obj (:node p) (first as) e)]
            (recur (rest ps) (rest as) new-env))

          (= :destruct-list (:type p))
          (let [new-env (bind-destruct-list (:node p) (first as) e)]
            (recur (rest ps) (rest as) new-env))

          :else
          (recur (rest ps) (rest as) (env/bind e (:name p) (first as))))))))

(defn- eval-body
  "Evaluate a FnBody node, threading env through bindings. Returns final value.
   Takes params and closure-env for TCO: when recur is caught, rebind params
   and restart the body loop without growing the stack."
  [body-node fn-env params closure-env]
  (loop [exprs    (rest body-node)
         curr-env fn-env]
    (let [expr (first exprs)]
      (if (= 1 (count exprs))
        ;; Last expression: evaluate and handle recur
        (let [result
              (try
                [:done (let [[val _] (eval-expr expr curr-env)] val)]
                (catch clojure.lang.ExceptionInfo e
                  (if (= "::recur" (.getMessage e))
                    [:recur (:args (ex-data e))]
                    (throw e))))]
          (if (= :done (first result))
            (second result)
            ;; Recur: restart body with new args — NO new stack frame!
            (let [new-args    (second result)
                  new-fn-env  (bind-params (or params []) (vec new-args) closure-env)
                  first-param (first (or params []))
                  new-fn-env  (if (and first-param
                                       (= :normal (:type first-param))
                                       (not (nil? (first new-args))))
                                (env/bind new-fn-env "_" (first new-args))
                                new-fn-env)]
              (recur (rest body-node) new-fn-env))))
        ;; Not last: eval for side effects, thread bindings
        (let [[_val new-env] (eval-expr expr curr-env)]
          (recur (rest exprs) new-env))))))

(defn- make-clj-fn
  "Create a Clojure function from parsed params and a body AST node.

   Also binds _ to the first argument when the first param is a named
   identifier (not wildcard). This enables literal pattern matching inside
   functions: `[n -> | 42 -> 'answer' | _ -> 'other']` can use `_ = 42`
   guards because `_` is bound to `n`'s value.

   recur is handled by eval-body directly (TCO without stack growth)."
  [params body-node closure-env]
  (fn [& args]
    (let [fn-env      (bind-params (or params []) (vec args) closure-env)
          first-param (first (or params []))
          fn-env      (if (and first-param
                               (= :normal (:type first-param)))
                        (env/bind fn-env "_" (first args))
                        fn-env)]
      (eval-body body-node fn-env params closure-env))))

(defn- eval-fndef
  "Evaluate a FnDef node's children into a Clojure fn."
  [children closure-env]
  (let [has-params  (and (not (empty? children))
                         (vector? (first children))
                         (= :FnParams (first (first children))))
        params-node (when has-params (first children))
        body-node   (if has-params (second children) (first children))
        params      (when params-node (parse-params (rest params-node)))]
    (make-clj-fn params body-node closure-env)))

(defn- eval-multi-arity
  "Evaluate a MultiArityFn: FnDef (_ FnDef)+  =>  dispatching fn."
  [children closure-env]
  (let [fndefs  (filter #(and (vector? %) (= :FnDef (first %))) children)
        arities (mapv (fn [fndef]
                        (let [fndef-children (rest fndef)
                              has-params     (and (not (empty? fndef-children))
                                                  (vector? (first fndef-children))
                                                  (= :FnParams (first (first fndef-children))))
                              params-node    (when has-params (first fndef-children))
                              body-node      (if has-params (second fndef-children) (first fndef-children))
                              params         (when params-node (parse-params (rest params-node)))
                              fixed-count    (if params (count-fixed-params params) 0)
                              rest?          (if params (has-rest? params) false)]
                          {:params params :body body-node :fixed fixed-count :rest? rest?}))
                      fndefs)
        dispatch (fn [& args]
                   (let [n     (count args)
                         match (or (first (filter #(and (not (:rest? %))
                                                        (= n (:fixed %)))
                                                  arities))
                                   (first (filter #(and (:rest? %)
                                                        (>= n (:fixed %)))
                                                  arities)))]
                     (when-not match
                       (throw (ex-info "No matching arity"
                                       {:args-count n :arities (mapv :fixed arities)})))
                     (let [{:keys [params body]} match
                           fn-env      (bind-params (or params []) (vec args) closure-env)
                           first-param (first (or params []))
                           fn-env      (if (and first-param
                                                (= :normal (:type first-param)))
                                         (env/bind fn-env "_" (first args))
                                         fn-env)]
                       (eval-body body fn-env params closure-env))))]
    dispatch))

;; ---------------------------------------------------------------------------
;; Guard block evaluation
;; ---------------------------------------------------------------------------

(defn- match-obj-pattern
  "Try to match an object pattern (from Object literal or DestructObjPattern) against `ctx`.
   Returns [matched? new-env] where new-env has any newly bound pattern variables."
  [pattern-node ctx env]
  (cond
    ;; Not a map: no match
    (not (map? ctx))
    [false env]

    ;; DestructObjPattern: {name: n age: a} — variable capture only
    ;; All required fields must EXIST in the map for the pattern to match
    (= :DestructObjPattern (first pattern-node))
    (let [fields (rest pattern-node)
          ;; Check that all pattern fields exist in ctx
          all-exist? (every? (fn [field]
                               (when (and (vector? field) (= :DestructObjField (first field)))
                                 (let [field-children (rest field)
                                       id-node        (first field-children)
                                       field-kw       (keyword (second id-node))]
                                   (contains? ctx field-kw))))
                             fields)]
      (if all-exist?
        (let [new-env (bind-destruct-obj pattern-node ctx env)]
          [true new-env])
        [false env]))

    ;; Object literal: {type: "book" name: n} — mix of literal checks and captures
    ;; Each StandardEntry is checked:
    ;; - Literal value (Integer/String/Boolean/Nil/Keyword) → must match exactly
    ;; - Identifier value → bind the identifier to the field value
    (= :Object (first pattern-node))
    (let [entries (rest pattern-node)]
      (loop [entries-remaining entries
             current-env       env
             all-match?        true]
        (if (not all-match?)
          [false env]
          (if (empty? entries-remaining)
            [true current-env]
            (let [entry (first entries-remaining)]
              (if (not= :StandardEntry (first entry))
                (recur (rest entries-remaining) current-env all-match?)
                (let [entry-children (rest entry)
                      key-node       (first entry-children)
                      val-node       (second entry-children)
                      field-kw       (keyword (second key-node))
                      field-val      (get ctx field-kw)]
                  (cond
                    ;; No value node = shorthand {name} — bind name = ctx.name
                    (nil? val-node)
                    (recur (rest entries-remaining)
                           (env/bind current-env (second key-node) field-val)
                           all-match?)

                    ;; Value is an identifier → bind it to the field value
                    ;; Field must EXIST in the map (key present)
                    (= :Identifier (first (descend-to-inner val-node)))
                    (let [var-name (second (descend-to-inner val-node))]
                      (if (contains? ctx field-kw)
                        (recur (rest entries-remaining)
                               (env/bind current-env var-name field-val)
                               all-match?)
                        ;; Field absent: pattern fails
                        (recur (rest entries-remaining) current-env false)))

                    ;; Value is a Wildcard → field must exist
                    (= :Wildcard (first (descend-to-inner val-node)))
                    (if (contains? ctx field-kw)
                      (recur (rest entries-remaining) current-env all-match?)
                      (recur (rest entries-remaining) current-env false))

                    ;; Value is a literal → must match exactly
                    (contains? #{:Integer :Float :String :Boolean :Nil :Keyword}
                               (first (descend-to-inner val-node)))
                    (let [lit-val (eval-node val-node env)]
                      (recur (rest entries-remaining)
                             current-env
                             (if (and (nil? lit-val) (nil? field-val))
                               true
                               (= field-val lit-val))))

                    ;; Nested pattern (Object/DestructObjPattern)
                    (= :Object (first (descend-to-inner val-node)))
                    (let [[sub-match? sub-env] (match-obj-pattern
                                                (descend-to-inner val-node)
                                                field-val
                                                current-env)]
                      (recur (rest entries-remaining)
                             (if sub-match? sub-env current-env)
                             (and all-match? sub-match?)))

                    ;; Otherwise: eval and compare (guard expression in value)
                    :else
                    (let [expected (eval-node val-node env)]
                      (recur (rest entries-remaining)
                             current-env
                             (= field-val expected)))))))))))

    :else [false env]))

(defn- match-list-pattern
  "Try to match a list pattern against `ctx`.
   Pattern can be:
   - DestructListPattern: [x], [x y], [x & rest], [x & _], []
   - List literal: [], [x], where x is Identifier (variable)
   Returns [matched? new-env]."
  [pattern-node ctx env]
  (cond
    (not (sequential? ctx)) [false env]

    ;; DestructListPattern: [x], [x y], [x & rest]
    (= :DestructListPattern (first pattern-node))
    (let [elems-node     (second pattern-node)
          elem-children  (rest elems-node)
          rest-binding   (first (filter #(and (vector? %) (= :RestBinding (first %))) elem-children))
          regular-elems  (vec (filter #(and (vector? %) (= :DestructListElem (first %))) elem-children))
          fixed-count    (count regular-elems)
          data-count     (count ctx)]
      ;; Without rest-binding: must match exactly
      ;; With rest-binding: must have at least fixed-count elements
      (if (if rest-binding
            (>= data-count fixed-count)
            (= data-count fixed-count))
        (let [bound-env (bind-destruct-list pattern-node ctx env)]
          [true bound-env])
        [false env]))

    ;; List literal: [] or [x] where x is Identifier
    (= :List (first pattern-node))
    (let [elems (rest pattern-node)]
      (if (empty? elems)
        ;; Empty list pattern []
        [(empty? ctx) env]
        ;; Non-empty: elements are FieldAccess nodes containing Identifiers/Wildcards
        (let [data-count  (count ctx)
              elem-count  (count elems)]
          (if (not= data-count elem-count)
            [false env]
            (loop [remaining-elems elems
                   idx             0
                   current-env     env]
              (if (empty? remaining-elems)
                [true current-env]
                (let [elem-node (first remaining-elems)
                      inner     (descend-to-inner elem-node)
                      data-val  (nth ctx idx nil)]
                  (cond
                    ;; Wildcard → skip
                    (= :Wildcard (first inner))
                    (recur (rest remaining-elems) (inc idx) current-env)

                    ;; Identifier → bind
                    (= :Identifier (first inner))
                    (recur (rest remaining-elems) (inc idx)
                           (env/bind current-env (second inner) data-val))

                    ;; Literal → must match
                    (contains? #{:Integer :Float :String :Boolean :Nil} (first inner))
                    (if (= data-val (eval-node inner env))
                      (recur (rest remaining-elems) (inc idx) current-env)
                      [false env])

                    :else
                    (recur (rest remaining-elems) (inc idx) current-env)))))))))

    :else [false env]))

(defn- eval-guard-block
  "Evaluate a GuardBlock: sequence of GuardArm nodes.
   Returns nil if no arm matches (exhaustiveness is optional)."
  [arms env]
  (loop [remaining arms]
    (if (empty? remaining)
      ;; No arm matched: return nil
      nil
      (let [arm     (first remaining)
            arm-tag (first arm)]
        (if (not= :GuardArm arm-tag)
          ;; Skip non-GuardArm children
          (recur (rest remaining))
          ;; Process GuardArm = '|' GuardPattern (when OrExpr)? '->' OrExpr
          ;; children: [GuardPattern, <when OrExpr>?, OrExpr]
          (let [arm-children  (rest arm)
                pattern-node  (first arm-children)
                rest-children (rest arm-children)
                ;; when clause is present if there are 2+ remaining children
                [when-node result-node]
                (if (> (count rest-children) 1)
                  [(first rest-children) (second rest-children)]
                  [nil (first rest-children)])]
            ;; Evaluate the guard pattern
            (let [guard-inner     (second pattern-node)  ; inner of GuardPattern
                  ;; Descend to innermost node to determine pattern type
                  guard-innermost (descend-to-inner guard-inner)
                  ctx             (env/lookup env "_")
                  ;; _ is explicitly bound only when the key exists in the env map.
                  ;; This distinguishes pipeline/function context (where _ is bound to
                  ;; the current element) from standalone guard blocks (no context).
                  ctx-bound?      (contains? env "_")

                  ;; Try to match pattern, returning [matched? new-env-with-bindings]
                  [matches? match-env]
                  (cond
                    ;; Wildcard: | _ -> default arm (bare wildcard, not containing wildcards)
                    (and (vector? guard-innermost) (= :Wildcard (first guard-innermost)))
                    [true env]

                    ;; Structural DestructListPattern: [x], [x y], [x & rest]
                    (and (vector? guard-inner)
                         (= :DestructListPattern (first guard-inner)))
                    (match-list-pattern guard-inner ctx env)

                    ;; Structural DestructObjPattern: {name: n} — variable capture
                    (and (vector? guard-inner)
                         (= :DestructObjPattern (first guard-inner)))
                    (match-obj-pattern guard-inner ctx env)

                    ;; List literal pattern: [] or [x y z]
                    ;; When _ is bound: match against ctx. Otherwise: match if empty? is truthy.
                    (and (vector? guard-innermost)
                         (= :List (first guard-innermost)))
                    (if ctx-bound?
                      (match-list-pattern guard-innermost ctx env)
                      ;; Standalone: evaluate list literal and check DataTwist truthiness
                      ;; ([] is truthy — only nil and false are falsy)
                      (let [lit (eval-node guard-innermost env)]
                        [(not (or (nil? lit) (false? lit))) env]))

                    ;; Object literal pattern: {type: "book" name: n}
                    ;; When _ is bound: match against ctx. Otherwise: truthy check.
                    (and (vector? guard-innermost)
                         (= :Object (first guard-innermost)))
                    (if ctx-bound?
                      (match-obj-pattern guard-innermost ctx env)
                      (let [lit (eval-node guard-innermost env)]
                        [(not (or (nil? lit) (false? lit))) env]))

                    ;; Bare literal pattern: | 42 / | "ok" / | true / | nil
                    ;; When _ is bound: compare against ctx (equality match).
                    ;; When _ is not bound: evaluate as DataTwist boolean condition
                    ;; (DataTwist truthiness: only nil and false are falsy).
                    (and (vector? guard-innermost)
                         (contains? #{:Integer :Float :String :Boolean :Nil}
                                    (first guard-innermost)))
                    (let [lit (eval-node guard-innermost env)]
                      (if ctx-bound?
                        [(if (and (nil? lit) (nil? ctx))
                           true
                           (= ctx lit))
                         env]
                        ;; Standalone: DataTwist truthiness — only nil and false are falsy
                        [(not (or (nil? lit) (false? lit))) env]))

                    ;; Boolean expression guard: evaluate the inner expression
                    :else
                    [(eval-node guard-inner env) env])

                  when-ok?
                  (or (nil? when-node)
                      (eval-node when-node match-env))]
              (if (and matches? when-ok?)
                (eval-node result-node match-env)
                (recur (rest remaining))))))))))

;; ---------------------------------------------------------------------------
;; Try-Catch evaluation
;; ---------------------------------------------------------------------------

(defn- eval-try-catch
  "Evaluate a TryCatch node: try Expr CatchClause+ FinallyClause?"
  [children env]
  (let [try-expr       (first children)
        rest-clauses   (rest children)
        catch-clauses  (filter #(and (vector? %) (= :CatchClause (first %))) rest-clauses)
        finally-clause (first (filter #(and (vector? %) (= :FinallyClause (first %))) rest-clauses))]
    (try
      (let [[val _] (eval-expr try-expr env)]
        val)
      (catch Exception e
        (loop [clauses catch-clauses]
          (if (empty? clauses)
            (throw e)
            (let [clause         (first clauses)
                  ;; CatchClause = CatchTarget '->' Expr
                  clause-children (rest clause)
                  catch-target    (first clause-children)
                  catch-expr      (second clause-children)
                  target-children (rest catch-target)
                  matched?
                  (cond
                    ;; Wildcard: catch all
                    (and (= 1 (count target-children))
                         (vector? (first target-children))
                         (= :Wildcard (first (first target-children))))
                    true
                    ;; Bare identifier: catch all
                    (and (= 1 (count target-children))
                         (vector? (first target-children))
                         (= :Identifier (first (first target-children))))
                    true
                    ;; DotName Identifier: check class
                    (= 2 (count target-children))
                    (try
                      (instance? (Class/forName (second (first target-children))) e)
                      (catch ClassNotFoundException _ false))
                    :else false)]
              (if matched?
                (let [bound-env
                      (cond
                        (and (= 1 (count target-children))
                             (vector? (first target-children))
                             (= :Identifier (first (first target-children))))
                        (env/bind env (second (first target-children)) e)
                        (= 2 (count target-children))
                        (env/bind env (second (second target-children)) e)
                        :else env)]
                  (let [[val _] (eval-expr catch-expr bound-env)]
                    val))
                (recur (rest clauses)))))))
      (finally
        (when finally-clause
          (let [finally-expr (second (rest finally-clause))]
            (eval-node finally-expr env)))))))

;; ---------------------------------------------------------------------------
;; Pipeline evaluation
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Pipeline step: extract-pipeline-fn
;; ---------------------------------------------------------------------------

(defn- replace-fncall-with-expr
  "Walk the AST and replace the first FnCall node that matches `target-fncall`
   with `replacement-node`. Returns the modified tree."
  [tree target-fncall replacement-node]
  (cond
    (identical? tree target-fncall) replacement-node
    (not (vector? tree)) tree
    :else
    (let [tag (first tree)
          children (rest tree)
          new-children (mapv #(replace-fncall-with-expr % target-fncall replacement-node) children)]
      (into [tag] new-children))))

(defn- find-pipeline-fncall
  "Find the FnCall node where the function is a known identifier (not wildcard)
   and at least one CallArg contains a wildcard. Returns the FnCall node or nil."
  [node]
  (cond
    (not (vector? node)) nil
    (= :FnCall (first node))
    (let [children (rest node)
          call-target (first children)
          call-args (rest children)]
      ;; Check: target is an Identifier/QualifiedName/etc. (not wildcard)
      ;; AND at least one arg contains wildcard (or is a bare wildcard)
      (if (and (vector? call-target)
               (not= :Wildcard (first call-target))
               (some contains-wildcard? call-args))
        node
        ;; Recurse into children
        (some find-pipeline-fncall (rest node))))
    :else
    (some find-pipeline-fncall (rest node))))

(defn- eval-pipe-atom-with-fn-call
  "Evaluate a PipeAtom. Returns a step fn :: data -> result.

   Pipeline semantics:
   - No wildcard: evaluate step, if fn apply to data, else error
   - FnCall with no wildcard args but with extra args: inject data as first arg
   - Contains wildcard:
     - Find the 'pipeline FnCall' (FnCall with bare _ or _.field arg)
     - Extract the pipeline function
     - Replace FnCall with _ in surrounding expression to get predicate AST
     - Create predicate lambda fn[x -> eval predicate with _=x]
     - Call: pipeline_fn(data, predicate_lambda, extra_args...)
   - Step is an object literal with wildcards: map over data"
  [pipe-atom-node env]
  (let [inner (descend-to-inner pipe-atom-node)]
    (if (not (contains-wildcard? pipe-atom-node))
      ;; No wildcard
      (if (and (vector? inner) (= :FnCall (first inner)))
        ;; FnCall with no wildcard: inject data, pass extra args
        ;; Object/List literal args become template functions (Smart Map semantics)
        (let [call-children (rest inner)
              call-target   (first call-children)
              call-args     (rest call-children)
              f             (eval-node call-target env)
              ;; Evaluate each arg: if it's an Object/List/FnDef literal and not a fn,
              ;; wrap as a template function (evaluates with _ = element)
              extra-args    (mapv (fn [call-arg]
                                    (let [arg-inner  (descend-to-inner (second call-arg))
                                          arg-val    (eval-node (second call-arg) env)
                                          ;; Object or FnDef literal → wrap as template
                                          template?  (and (vector? arg-inner)
                                                          (#{:Object :FnDef :List} (first arg-inner)))]
                                      (if (and template? (not (fn? arg-val)))
                                        ;; Object/List literal: create fn[x -> eval(literal with _=x)]
                                        (fn [x] (eval-node (second call-arg) (env/bind env "_" x)))
                                        arg-val)))
                                  call-args)]
          (fn [data]
            (apply-fn f (into [data] extra-args))))
        ;; Plain value/function: apply to data
        (let [val (eval-node pipe-atom-node env)]
          (fn [data]
            (if (fn? val)
              (apply-fn val [data])
              (throw (ex-info "Pipeline step is not a function" {:value val}))))))
      ;; Contains wildcard
      (let [pipeline-fncall (find-pipeline-fncall pipe-atom-node)]
        (if pipeline-fncall
          ;; Found a pipeline FnCall: extract fn, build predicate from surrounding AST
          (let [fncall-children  (rest pipeline-fncall)
                call-target      (first fncall-children)
                call-args        (rest fncall-children)
                pipeline-fn      (eval-node call-target env)
                ;; The wildcard-containing arg from the FnCall — its inner expression
                ;; CallArg = <'('> PipeExpr <')'> / PipeExpr
                ;; We want the content of the first wildcard-containing CallArg
                wildcard-arg     (first (filter contains-wildcard? call-args))
                wildcard-expr    (second wildcard-arg) ; the PipeExpr inside CallArg
                ;; Build predicate AST: replace the FnCall with the wildcard expression
                ;; so `filter _ > 3` becomes `_ > 3` (a predicate on _)
                predicate-ast    (replace-fncall-with-expr pipe-atom-node pipeline-fncall wildcard-expr)
                predicate-fn     (fn [x] (eval-node predicate-ast (env/bind env "_" x)))
                ;; Extra args: non-wildcard args to the FnCall (before pipe-first injection)
                extra-args       (mapv #(eval-node (second %) env)
                                       (remove contains-wildcard? call-args))]
            (fn [data]
              ;; Inject data first, then predicate lambda, then extra args
              (apply-fn pipeline-fn (into [data predicate-fn] extra-args))))
          ;; No pipeline FnCall found — whole step is a predicate applied to data
          ;; This handles cases like `{}` object literal in map, or bare expressions
          (if (and (vector? inner) (= :Object (first inner)))
            ;; Object literal step: apply to data (Smart Map semantics need data as _)
            (fn [data]
              (eval-node pipe-atom-node (env/bind env "_" data)))
            ;; Fallback: wrap whole step as fn of _
            (fn [data]
              (eval-node pipe-atom-node (env/bind env "_" data)))))))))

(defn- eval-pipeline
  "Evaluate a sequence of PipeAtom nodes against initial data."
  [data steps env]
  (reduce (fn [d step-node]
            (let [step-fn (eval-pipe-atom-with-fn-call step-node env)]
              (step-fn d)))
          data
          steps))

;; ---------------------------------------------------------------------------
;; eval-expr: returns [value env'] for binding propagation
;; ---------------------------------------------------------------------------

(defn eval-expr
  "Evaluate a node, returning [value env'].
   Binding nodes update env'. Everything else keeps env unchanged."
  [node env]
  (if-not (vector? node)
    [node env]
    (let [tag      (first node)
          children (vec (rest node))]
      (case tag
        :Program
        (reduce (fn [[_v e] child]
                  (eval-expr child e))
                [nil env]
                children)

        :Binding
        (let [target     (first children)
              value-expr (second children)]
          (case (first target)
            :Identifier
            (let [name       (second target)
                  value-tag  (first (descend-to-inner value-expr))
                  ;; For FnDef and MultiArityFn: letrec semantics so the function
                  ;; can see its own name in scope (enabling named recursion).
                  raw-val
                  (if (contains? #{:FnDef :MultiArityFn} value-tag)
                    ;; Letrec: evaluate with a forward-reference to self in env
                    (let [self-cell (atom nil)
                          letrec-env (env/bind env name (fn [& args] (apply @self-cell args)))
                          fn-val (eval-node value-expr letrec-env)]
                      (reset! self-cell fn-val)
                      fn-val)
                    ;; Normal: evaluate without self-reference
                    (eval-node value-expr env))
                  ;; Bang ! auto-wrapping: functions bound to names ending in !
                  ;; execute the original fn for side effects, then return first arg
                  val (if (and (.endsWith ^String name "!")
                               (fn? raw-val))
                        (fn [& args]
                          (apply raw-val args)
                          (first args))
                        raw-val)
                  new-env (env/bind env name val)]
              [val new-env])

            :DestructPattern
            (let [val     (eval-node value-expr env)
                  dp      target
                  dp-inner (second dp)]
              (case (first dp-inner)
                :DestructObjPattern
                (let [bound-env (bind-destruct-obj dp-inner val env)
                      ;; as clause: DestructPattern = pattern (KW-AS Identifier)?
                      as-id     (when (> (count (rest dp)) 1)
                                  (second (second (rest dp))))]
                  [val (if as-id (env/bind bound-env as-id val) bound-env)])

                :DestructListPattern
                (let [bound-env (bind-destruct-list dp-inner val env)
                      as-id     (when (> (count (rest dp)) 1)
                                  (second (second (rest dp))))]
                  [val (if as-id (env/bind bound-env as-id val) bound-env)])

                [(eval-node value-expr env) env]))

            [(eval-node value-expr env) env]))

        ;; Transparent wrappers: recurse so inner bindings propagate
        :Expr
        (if (= 1 (count children))
          (eval-expr (first children) env)
          [(eval-node node env) env])

        ;; CodeExpr is the same as Expr but excludes Require (grammar-level constraint)
        :CodeExpr
        (if (= 1 (count children))
          (eval-expr (first children) env)
          [(eval-node node env) env])

        :PipeExpr
        (if (= 1 (count children))
          (eval-expr (first children) env)
          [(eval-node node env) env])

        ;; Require: load a Clojure namespace and register alias in env.
        ;; Grammar: Require = <KW-REQUIRE> __ DotName __ <KW-AS> __ Identifier
        ;; children: [DotName, Identifier]
        :Require
        (let [dot-name-node  (first children)
              alias-node     (second children)
              ns-str         (second dot-name-node)   ; DotName = "clojure.string"
              alias-str      (second alias-node)       ; Identifier = "str"
              _              (try (clojure.core/require (symbol ns-str))
                                  (catch Exception _ nil))
              aliases        (or (env/lookup env "__aliases__") {})
              new-aliases    (assoc aliases alias-str ns-str)
              new-env        (env/bind env "__aliases__" new-aliases)]
          [nil new-env])

        ;; Default: eval, don't change env
        [(eval-node node env) env]))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn- comment-or-whitespace-only?
  "Returns true if the input contains only whitespace and // comments."
  [input]
  (let [stripped (.replaceAll input "//[^\n]*" "")]
    (clojure.string/blank? stripped)))

(defn evaluate
  "Parse and evaluate DataTwist source code. Returns the result."
  [input]
  ;; A comment-only or whitespace-only program produces no form → nil.
  (when-not (comment-or-whitespace-only? input)
    (let [ast (parser/parse input)]
      (when (insta/failure? ast)
        (throw (ex-info "Parse error" {:input input :failure ast})))
      (eval-node ast (stdlib/default-env)))))
