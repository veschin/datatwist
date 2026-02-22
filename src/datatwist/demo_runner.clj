(ns datatwist.demo-runner
  "File-based demo runner for DataTwist.
   Reads .dt files from resources/examples/, parses sections and @expect
   annotations, evaluates expressions in order with shared context, and
   prints formatted terminal output.
   Run with: clj -M -m datatwist.demo-runner"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [instaparse.core :as insta]
            [datatwist.parser :as parser]
            [datatwist.evaluator :as evaluator]
            [datatwist.stdlib :as stdlib]
            [datatwist.error-renderer :as renderer]))

;; ---------------------------------------------------------------------------
;; ANSI color constants
;; ---------------------------------------------------------------------------

(def ^:private RESET     "\033[0m")
(def ^:private BOLD      "\033[1m")
(def ^:private DIM       "\033[2m")
(def ^:private MAGENTA   "\033[95m")
(def ^:private CYAN      "\033[96m")
(def ^:private GREEN     "\033[92m")
(def ^:private RED       "\033[91m")
(def ^:private YELLOW    "\033[93m")
(def ^:private WHITE     "\033[97m")
(def ^:private DIM-WHITE "\033[37m")

;; ---------------------------------------------------------------------------
;; File Loading
;; ---------------------------------------------------------------------------

(defn load-dt-file
  "Read a .dt file by filename from resources/examples/. Returns content as a string.
   filename may be a bare name like \"demo-basics.dt\" or a full path like
   \"resources/examples/demo-basics.dt\".
   Throws ex-info with :type :file-not-found if the file does not exist."
  [filename]
  (let [;; Resolve path: if the caller passes just a name, prepend the directory
        path (if (str/includes? filename "/")
               filename
               (str "resources/examples/" filename))
        f    (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "File not found: " path)
                      {:type :file-not-found :path path})))
    (slurp f)))

;; ---------------------------------------------------------------------------
;; Parsing — Section Markers
;; ---------------------------------------------------------------------------

(defn- section-marker? [line]
  (re-matches #"//\s*@section\s+.*" (str/trim line)))

(defn- section-title [line]
  (second (re-find #"//\s*@section\s+(.*)" (str/trim line))))

(defn parse-sections
  "Parse file content into a vector of section maps.
   Each map has:
     :title       — string section title, or nil for the default (pre-first-marker) section
     :lines       — vector of raw content lines belonging to the section

   Lines before the first @section marker form a default unnamed section only
   if they contain at least one non-blank, non-section-comment line."
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining    lines
           current-title nil
           current-lines []
           sections      []]
      (if (empty? remaining)
        ;; Flush the last section
        (let [final (conj sections {:title current-title :lines current-lines})]
          ;; Drop leading default section if it has no content
          (if (and (nil? (:title (first final)))
                   (every? #(or (str/blank? %) (str/starts-with? (str/trim %) "//")) (:lines (first final))))
            (rest final)
            final))
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (if (section-marker? line)
            ;; Save current section (if we've accumulated one), start new
            (let [new-sections (conj sections {:title current-title :lines current-lines})]
              (recur rest-lines
                     (section-title line)
                     []
                     new-sections))
            (recur rest-lines
                   current-title
                   (conj current-lines line)
                   sections)))))))

;; ---------------------------------------------------------------------------
;; Parsing — Expression Extraction
;; ---------------------------------------------------------------------------

(defn- expect-annotation? [line]
  (re-matches #"//\s*@expect\s+.*" (str/trim line)))

(defn- expect-value [line]
  (second (re-find #"//\s*@expect\s+(.*)" (str/trim line))))

(defn- plain-comment? [line]
  (str/starts-with? (str/trim line) "//"))

(defn- bracket-depth
  "Count net unmatched open brackets in a string, ignoring quoted content."
  [s]
  (loop [chars  (seq s)
         depth  0
         in-str false
         escape false]
    (if (empty? chars)
      depth
      (let [c (first chars)]
        (cond
          escape       (recur (rest chars) depth in-str false)
          (= c \\)     (recur (rest chars) depth in-str true)
          (= c \")     (recur (rest chars) depth (not in-str) false)
          in-str       (recur (rest chars) depth in-str false)
          (= c \[)     (recur (rest chars) (inc depth) false false)
          (= c \])     (recur (rest chars) (dec depth) false false)
          :else        (recur (rest chars) depth false false))))))

(defn extract-expressions
  "Given a sequence of raw lines (from one section), return a vector of
   expression maps. Each map has:
     :expr     — the expression string to evaluate
     :expected — (optional) the expected string value from a preceding @expect annotation

   Rules:
     - Blank lines are skipped (unless inside a multi-line expression)
     - Plain // comment lines (not @expect) are skipped
     - // @expect <value> lines are attached as :expected to the NEXT expression
     - Lines with unbalanced [ are joined with following lines until balanced
     - All other lines are complete single-line expressions"
  [lines]
  (loop [remaining   lines
         pending-exp nil   ; pending @expect string
         multi-acc   nil   ; nil = not collecting; vector = lines of current multi-line expr
         multi-depth 0
         result      []]
    (if (empty? remaining)
      ;; Flush any dangling multi-line expression
      (if (seq multi-acc)
        (let [expr  (str/join "\n" multi-acc)
              entry (cond-> {:expr expr}
                      pending-exp (assoc :expected pending-exp))]
          (conj result entry))
        result)
      (let [line       (first remaining)
            rest-lines (rest remaining)
            trimmed    (str/trim line)]
        (cond
          ;; Inside multi-line expression — keep collecting
          (some? multi-acc)
          (let [new-depth (+ multi-depth (bracket-depth line))
                new-acc   (conj multi-acc line)]
            (if (<= new-depth 0)
              ;; Expression complete
              (let [expr  (str/join "\n" new-acc)
                    entry (cond-> {:expr expr}
                            pending-exp (assoc :expected pending-exp))]
                (recur rest-lines nil nil 0 (conj result entry)))
              ;; Still collecting
              (recur rest-lines pending-exp new-acc new-depth result)))

          ;; Blank line — skip
          (str/blank? trimmed)
          (recur rest-lines pending-exp nil 0 result)

          ;; @expect annotation — save for next expression
          (expect-annotation? trimmed)
          (recur rest-lines (expect-value trimmed) nil 0 result)

          ;; Plain comment (not @expect) — skip
          (plain-comment? trimmed)
          (recur rest-lines nil nil 0 result)

          ;; Expression line: check bracket balance
          :else
          (let [depth (bracket-depth line)]
            (if (> depth 0)
              ;; Multi-line expression starts here
              (recur rest-lines pending-exp [line] depth result)
              ;; Single-line expression
              (let [entry (cond-> {:expr trimmed}
                            pending-exp (assoc :expected pending-exp))]
                (recur rest-lines nil nil 0 (conj result entry))))))))))

;; ---------------------------------------------------------------------------
;; Expression Evaluation
;; ---------------------------------------------------------------------------

(defn run-expression
  "Evaluate a single DataTwist expression string in the given environment.
   Returns a map with:
     :result — the evaluated value (if successful)
     :error  — the exception (if evaluation threw)
     :env    — the updated environment (carries new bindings forward)"
  [expr env]
  (try
    (let [ast (parser/parse expr)]
      (if (insta/failure? ast)
        {:error  (ex-info (str "Parse error in: " expr) {:type :parse-error :expr expr})
         :env    env}
        (let [[val new-env] (evaluator/eval-expr ast env)]
          {:result val
           :env    new-env})))
    (catch Exception e
      {:error e
       :env   env})))

;; ---------------------------------------------------------------------------
;; Value Formatter
;; ---------------------------------------------------------------------------

(declare format-dt-value)

(defn- format-dt-value
  "Render a Clojure value as DataTwist surface syntax."
  [v]
  (cond
    (nil? v)     "nil"
    (string? v)  (pr-str v)
    (boolean? v) (str v)
    (number? v)  (str v)
    (fn? v)      "<function>"
    ;; Vectors and any sequential collection (lazy seqs from map/filter/etc.)
    (or (vector? v) (sequential? v))
    (str "[" (str/join " " (map format-dt-value v)) "]")
    (map? v)    (str "{"
                     (str/join " "
                               (map (fn [[k val]]
                                      (str (name k) ": " (format-dt-value val)))
                                    v))
                     "}")
    :else       (str v)))

;; ---------------------------------------------------------------------------
;; @expect Validation
;; ---------------------------------------------------------------------------

(defn- check-expect
  "Compare an actual result to an expected string.
   Returns :pass if they match, :fail otherwise.
   The expected string is compared against the formatted value of the result."
  [actual expected-str]
  (let [actual-str (format-dt-value actual)]
    (if (= actual-str expected-str)
      :pass
      :fail)))

;; ---------------------------------------------------------------------------
;; Output formatting helpers
;; ---------------------------------------------------------------------------

(def ^:private CONTENT-WIDTH 60)

(defn- repeat-str [n s] (apply str (repeat n s)))

(defn- print-header []
  (let [title "DataTwist  —  Language Demo"
        pad-l "    "
        pad-r "    "
        inner (str pad-l title pad-r)
        w     (count inner)
        top    (str "  " MAGENTA "╭" (repeat-str w "─") "╮" RESET)
        mid    (str "  " DIM MAGENTA "│" RESET " " BOLD WHITE (str pad-l title) RESET " " DIM-WHITE pad-r DIM MAGENTA "│" RESET)
        bottom (str "  " MAGENTA "╰" (repeat-str w "─") "╯" RESET)]
    (println)
    (println top)
    (println mid)
    (println bottom)))

(defn- print-section-header [title]
  (when title
    (let [rule (repeat-str (+ (count title) 2) "─")]
      (println)
      (println (str "  " BOLD MAGENTA "  " title RESET))
      (println (str "  " DIM MAGENTA "  " rule RESET))
      (println))))

(defn- print-expr-line [expr]
  (println (str "    " DIM CYAN "▸" RESET "  " WHITE expr RESET)))

(defn- print-result [value]
  (println (str "      " GREEN "→ " (format-dt-value value) RESET)))

(defn- print-error [^Exception e]
  (println (renderer/render-exception e)))

(defn- print-expect-status [status expected actual-str]
  (if (= status :pass)
    (println (str "      " GREEN "✓  @expect " expected RESET))
    (println (str "      " RED "✗  @expect " expected "  (got: " actual-str ")" RESET))))

;; ---------------------------------------------------------------------------
;; run-file — orchestrator
;; ---------------------------------------------------------------------------

(defn run-file
  "Load and evaluate a .dt file. Expressions are evaluated in document order
   with a shared environment so bindings carry forward. @expect annotations
   are validated after evaluation. Errors are caught per-expression.

   Returns a map with:
     :results — vector of result maps (one per expression)
       each result map has:
         :expr     — the expression string
         :result   — the evaluated value (if ok)
         :error    — the exception (if error)
         :expected — the expected string (if @expect was present)
         :check    — :pass or :fail (only when :expected is present)
     :sections — the parsed sections (for inspection)"
  [filename]
  (let [content  (load-dt-file filename)
        sections (parse-sections content)]

    (println)
    (print-header)

    (let [;; Accumulate all results across all sections
          {:keys [all-results _env]}
          (reduce
           (fn [{:keys [env all-results]} {:keys [title lines]}]
             (let [exprs (extract-expressions lines)]
               (when (or title (seq exprs))
                 (print-section-header title))
               (reduce
                (fn [{:keys [env all-results]} {:keys [expr expected]}]
                  (print-expr-line expr)
                  (let [run-result (run-expression expr env)
                        result-val (:result run-result)
                        error      (:error run-result)
                        new-env    (:env run-result)
                        check      (when (and expected (not error))
                                     (check-expect result-val expected))
                        entry      (cond-> {:expr expr}
                                     (contains? run-result :result) (assoc :result result-val)
                                     error    (assoc :error error)
                                     expected (assoc :expected expected)
                                     check    (assoc :check check))]
                    (if error
                      (print-error error)
                      (print-result result-val))
                    (when check
                      (print-expect-status check expected (format-dt-value result-val)))
                    (println)
                    {:env        new-env
                     :all-results (conj all-results entry)}))
                {:env env :all-results all-results}
                exprs)))
           {:env (stdlib/default-env) :all-results []}
           sections)]

      (println (str "  " DIM MAGENTA (repeat-str (+ CONTENT-WIDTH 4) "─") RESET))
      (println (str "  " DIM-WHITE "  DataTwist v0.1  ·  pipe-first functional data processing" RESET))
      (println (str "  " DIM "  Built on Clojure/JVM  ·  Instaparse grammar" RESET))
      (println)

      {:results  all-results
       :sections sections})))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Entry point for the demo runner."
  [& _args]
  (run-file "demo-basics.dt"))
