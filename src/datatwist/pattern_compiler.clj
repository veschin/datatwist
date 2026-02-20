(ns datatwist.pattern-compiler
  "Compiles #p\"...\" pattern strings to regex-based matchers.

   Phase 1: simple {var} captures and literal text.
   Phase 2 (future): type-hint shorthands (:d :w :N :Nd).
   Phase 3 (future): full constraint mini-language.

   A compiled pattern is a map:
     {:dt/type :pattern
      :regex   java.util.regex.Pattern (anchored, named groups)
      :names   [\"name1\" \"name2\" ...]   (capture names in order)
      :source  \"original pattern string\"}"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Pattern string parser — Phase 1: {var} captures and literal text
;; ---------------------------------------------------------------------------

(defn- parse-pattern-string
  "Parse a raw pattern string into a segment vector.
   Returns a vector of segment maps, each one of:
     {:type :literal  :text \"...\"}
     {:type :capture  :name \"var\"  :constraint nil}
   Supports {{ and }} as escaped braces."
  [s]
  (let [n (count s)]
    (loop [i        0
           segments []
           lit-buf  (StringBuilder.)]
      (if (>= i n)
        ;; End of string: flush any pending literal
        (let [final-lit (.toString lit-buf)]
          (if (pos? (.length lit-buf))
            (conj segments {:type :literal :text final-lit})
            segments))
        (let [c (nth s i)]
          (cond
            ;; Escaped {{ -> literal {
            (and (= c \{) (< (inc i) n) (= (nth s (inc i)) \{))
            (recur (+ i 2) segments (.append lit-buf \{))

            ;; Escaped }} -> literal }
            (and (= c \}) (< (inc i) n) (= (nth s (inc i)) \}))
            (recur (+ i 2) segments (.append lit-buf \}))

            ;; Start of capture {name} or {name: constraint}
            (= c \{)
            (let [;; Flush accumulated literal
                  seg-so-far (if (pos? (.length lit-buf))
                               (conj segments {:type :literal :text (.toString lit-buf)})
                               segments)
                  ;; Find closing }
                  close-idx  (.indexOf s "}" (inc i))]
              (if (< close-idx 0)
                ;; No closing brace — treat as literal
                (recur (inc i) segments (.append lit-buf c))
                ;; Extract capture content
                (let [content     (subs s (inc i) close-idx)
                      ;; Check for constraint after colon
                      colon-idx   (.indexOf content ":")
                      [cap-name constraint-str]
                      (if (>= colon-idx 0)
                        [(subs content 0 colon-idx)
                         (str/trim (subs content (inc colon-idx)))]
                        [content nil])
                      cap-name (str/trim cap-name)]
                  (recur (inc close-idx)
                         (conj seg-so-far {:type       :capture
                                           :name       cap-name
                                           :constraint constraint-str})
                         (StringBuilder.)))))

            ;; Regular character: accumulate into literal buffer
            :else
            (recur (inc i) segments (.append lit-buf c))))))))

;; ---------------------------------------------------------------------------
;; Compile-time validation
;; ---------------------------------------------------------------------------

(defn- validate-segments!
  "Check for compile-time errors on the parsed segment vector.
   Raises ex-info for:
   - Adjacent unconstrained captures (no literal separator between them)
   Phase 2/3 validations (rest in non-final position, nested quantifiers) deferred."
  [segments source]
  (loop [segs segments
         prev nil]
    (when-let [seg (first segs)]
      (when (and (= :capture (:type prev))
                 (= :capture (:type seg))
                 (nil? (:constraint prev))
                 (nil? (:constraint seg)))
        (throw (ex-info (str "Adjacent unconstrained captures in pattern: " source)
                        {:dt/error true
                         :code     "DT-P010"
                         :category "PATTERN ERROR"
                         :message  (str "Pattern \"" source "\" has adjacent unconstrained captures {"
                                        (:name prev) "} and {" (:name seg)
                                        "}. Add a literal separator between them.")
                         :source   source})))
      (recur (rest segs) seg))))

;; ---------------------------------------------------------------------------
;; Regex builder — Phase 1
;; ---------------------------------------------------------------------------

(defn- wildcard-group-name
  "Return a Java-compatible regex group name for a wildcard capture.
   Java named groups must start with a letter; '_' is invalid.
   We use 'wc' + index as the group name for wildcards."
  [idx]
  (str "wc" idx))

(defn- segment->regex
  "Convert a single segment to a Java regex fragment string.
   For Phase 1:
     :literal -> Pattern/quote the text
     :capture (no constraint) -> named group (?<name>.*?) for non-final captures
                                  (?<name>.*) for last capture in sequence
   The caller handles last-capture distinction.
   seg-idx is the index of this segment (used to make unique wildcard group names)."
  [seg seg-idx is-last?]
  (case (:type seg)
    :literal
    (java.util.regex.Pattern/quote (:text seg))

    :capture
    (let [name  (:name seg)
          ;; Phase 1: no constraint means .*? (non-greedy) except last which is .*
          inner (if is-last? ".*" ".*?")
          ;; Java named groups cannot start with _; use wc<idx> for wildcards
          group-name (if (= name "_")
                       (wildcard-group-name seg-idx)
                       name)]
      (str "(?<" group-name ">" inner ")"))))

(defn- build-regex-str
  "Assemble the full anchored regex string from segments."
  [segments]
  (let [n (count segments)]
    (loop [i   0
           sb  (StringBuilder. "^")]
      (if (>= i n)
        (str sb "$")
        (let [seg      (nth segments i)
              is-last? (= i (dec n))]
          (recur (inc i)
                 (.append sb (segment->regex seg i is-last?))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn compile-pattern
  "Compile a raw pattern string (the content of #p\"...\") into a pattern map:
     {:dt/type     :pattern
      :regex       #\"...\" (anchored Java regex with named groups)
      :names       [\"name1\" \"name2\"]   ; capture names as written in pattern
      :group-names [\"name1\" \"wc2\"]    ; actual regex group names (wildcards renamed)
      :source      raw-string}
   Raises ex-info on compile-time errors."
  [raw-string]
  (let [segments      (parse-pattern-string raw-string)
        _             (validate-segments! segments raw-string)
        ;; Enumerate only capture segments with their original indices
        capture-segs  (keep-indexed (fn [i seg]
                                      (when (= :capture (:type seg))
                                        {:seg seg :idx i}))
                                    segments)
        capture-names (mapv (fn [{:keys [seg]}] (:name seg)) capture-segs)
        ;; Group names: wildcards get wc<idx> to satisfy Java named-group constraints
        group-names   (mapv (fn [{:keys [seg idx]}]
                              (if (= "_" (:name seg))
                                (wildcard-group-name idx)
                                (:name seg)))
                            capture-segs)
        regex-str     (build-regex-str segments)
        compiled      (re-pattern regex-str)]
    {:dt/type     :pattern
     :regex       compiled
     :names       capture-names
     :group-names group-names
     :source      raw-string}))

(defn apply-pattern
  "Apply a compiled pattern to a string input.
   Returns a Clojure map of keyword keys -> string values on match, or nil on no-match.
   Returns nil if input is not a string or if pattern-val is not a compiled pattern."
  [pattern-val input]
  (when (and (map? pattern-val)
             (= :pattern (:dt/type pattern-val))
             (string? input))
    (let [m (re-matcher (:regex pattern-val) input)]
      (when (.matches m)
        ;; Zip capture names (as written) with group names (actual regex).
        ;; Skip wildcard captures (name = "_") — they match but don't bind.
        (into {}
              (for [[cname gname] (map vector (:names pattern-val) (:group-names pattern-val))
                    :when (not= cname "_")]
                [(keyword cname) (.group m gname)]))))))
