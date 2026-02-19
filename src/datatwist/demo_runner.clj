(ns datatwist.demo-runner
  "Terminal demo runner for DataTwist.
   Evaluates curated expressions and prints beautifully formatted output
   with ANSI colors. Run with: clj -M -m datatwist.demo-runner"
  (:require [datatwist.parser :as parser]))

;; ---------------------------------------------------------------------------
;; ANSI color constants — soft pastel palette inspired by Charm gum/glow
;; ---------------------------------------------------------------------------

(def ^:private RESET        "\033[0m")
(def ^:private BOLD         "\033[1m")
(def ^:private DIM          "\033[2m")
(def ^:private ITALIC       "\033[3m")
;; Pastel accents
(def ^:private MAGENTA      "\033[95m")   ;; bright/pastel magenta — primary accent
(def ^:private CYAN         "\033[96m")   ;; bright/pastel cyan — secondary accent
(def ^:private GREEN        "\033[92m")   ;; soft green — results
(def ^:private RED          "\033[91m")   ;; soft red — errors
(def ^:private YELLOW       "\033[93m")   ;; soft yellow — hints/meta
(def ^:private WHITE        "\033[97m")   ;; bright white — code text
(def ^:private DIM-WHITE    "\033[37m")   ;; normal white — subdued text

;; ---------------------------------------------------------------------------
;; DataTwist value formatter
;; Renders Clojure runtime values back into DataTwist surface syntax.
;; ---------------------------------------------------------------------------

(declare format-dt-value)

(defn- format-dt-value
  "Render a Clojure value as DataTwist source syntax."
  [v]
  (cond
    (nil? v)     "nil"
    (string? v)  (pr-str v)
    (boolean? v) (str v)
    (number? v)  (str v)
    (fn? v)      "<function>"
    (vector? v)
    (str "[" (clojure.string/join " " (map format-dt-value v)) "]")
    (map? v)
    (str "{"
         (clojure.string/join
          " "
          (map (fn [[k val]]
                 (str (name k) ": " (format-dt-value val)))
               v))
         "}")
    :else (str v)))

;; ---------------------------------------------------------------------------
;; Layout helpers — gum/glow-inspired: rounded corners, soft borders, whitespace
;; ---------------------------------------------------------------------------

(def ^:private CONTENT-WIDTH 60)

(defn- repeat-str [n s] (apply str (repeat n s)))

(defn- print-header
  "Print a soft rounded banner — gum style."
  []
  (let [title    "DataTwist  —  Language Demo"
        pad-l    "    "
        pad-r    "    "
        inner    (str pad-l title pad-r)
        w        (count inner)
        top      (str "  " MAGENTA "╭" (repeat-str w "─") "╮" RESET)
        mid      (str "  " DIM MAGENTA "│" RESET " " BOLD WHITE (str pad-l title) RESET " " DIM-WHITE pad-r DIM MAGENTA "│" RESET)
        bottom   (str "  " MAGENTA "╰" (repeat-str w "─") "╯" RESET)]
    (println)
    (println top)
    (println mid)
    (println bottom)))

(defn- print-section-header
  "Print a soft section label with a thin rule below."
  [title]
  (let [rule (repeat-str (+ (count title) 2) "─")]
    (println)
    (println (str "  " BOLD MAGENTA "  " title RESET))
    (println (str "  " DIM MAGENTA "  " rule RESET))
    (println)))

;; ---------------------------------------------------------------------------
;; Evaluation helpers
;; ---------------------------------------------------------------------------

(defn- eval-dt-lines
  "Evaluate one or more lines joined by newline. Returns result or throws."
  [& lines]
  (parser/eval-dt (clojure.string/join "\n" lines)))

;; ---------------------------------------------------------------------------
;; Demo item types
;;
;; Each demo item is a map with:
;;   :setup  — vector of setup lines (evaluated silently, no output)
;;   :expr   — the expression string(s) to display and evaluate
;;             can be a string or vector of strings (multi-line display)
;;   :kind   — :eval (show result) | :setup-only (print expr, no result)
;;   :error? — true to expect and show an error
;; ---------------------------------------------------------------------------

(defn- print-expr-lines
  "Print expression lines with a soft ▸ prompt — gum style."
  [lines]
  (doseq [[i line] (map-indexed vector lines)]
    (if (zero? i)
      (println (str "    " DIM CYAN "▸" RESET "  " WHITE line RESET))
      (println (str "       " DIM-WHITE line RESET)))))

(defn- print-result
  "Print a successful result — soft green arrow."
  [value]
  (println (str "      " GREEN "→ " (format-dt-value value) RESET)))

(defn- print-error
  "Print an error with soft red — no bold, calm tone."
  [^Exception e]
  (println (str "      " DIM RED "✗  " (.getMessage e) RESET))
  (when-let [data (and (instance? clojure.lang.ExceptionInfo e) (ex-data e))]
    (when-let [code (:code data)]
      (println (str "         " DIM YELLOW "code: " (str code) RESET)))
    (when-let [hint (:hint data)]
      ;; Wrap hint at ~55 chars for readability
      (let [words  (clojure.string/split hint #"\s+")
            hlines (reduce (fn [acc word]
                             (let [cur (last acc)]
                               (if (> (+ (count cur) (count word) 1) 55)
                                 (conj acc word)
                                 (conj (pop acc) (if (empty? cur) word (str cur " " word))))))
                           [""]
                           words)]
        (doseq [[i ln] (map-indexed vector hlines)]
          (if (zero? i)
            (println (str "         " DIM YELLOW "hint: " ln RESET))
            (println (str "               " DIM ln RESET))))))))

(defn- run-item
  "Execute a single demo item, printing expression and result (or error)."
  [{:keys [setup expr kind error?]}]
  (let [expr-lines   (if (string? expr) [expr] expr)
        all-lines    (concat (or setup []) expr-lines)
        eval-source  (clojure.string/join "\n" all-lines)]

    (print-expr-lines expr-lines)

    (cond
      (= kind :setup-only)
      nil  ;; Just printed the line, no result displayed

      error?
      (try
        (parser/eval-dt eval-source)
        (println (str "      " DIM YELLOW "(no error — expected one)" RESET))
        (catch Exception e
          (print-error e)))

      :else
      (try
        (let [result (parser/eval-dt eval-source)]
          (print-result result))
        (catch Exception e
          (print-error e))))

    (println)))

;; ---------------------------------------------------------------------------
;; Demo sections — the actual showcase content
;; ---------------------------------------------------------------------------

(def ^:private demo-sections
  [{:title "Literals & Operators"
    :items [{:expr "2 + 3 * 4"}
            {:expr "nil ?? 42"}
            {:expr "\"hello\" + \", \" + \"world\""}
            {:expr "true and false or true"}]}

   {:title "Data Structures"
    :items [{:setup ["user is {name: \"Alice\" age: 30}"]
             :expr  "user is {name: \"Alice\" age: 30}"
             :kind  :setup-only}
            {:setup ["user is {name: \"Alice\" age: 30}"]
             :expr  "user.name"}
            {:setup ["company is {hq: {city: \"San Francisco\"}}"]
             :expr  "company is {hq: {city: \"San Francisco\"}}"
             :kind  :setup-only}
            {:setup ["company is {hq: {city: \"San Francisco\"}}"]
             :expr  "company.hq.city"}
            {:setup ["user is {name: \"Alice\"}"]
             :expr  "user.address.city"}]}

   {:title "Functions & Closures"
    :items [{:setup ["double is [x -> x * 2]"]
             :expr  "double is [x -> x * 2]"
             :kind  :setup-only}
            {:setup ["double is [x -> x * 2]"]
             :expr  "double 7"}
            {:setup ["base is 100"
                     "add-base is [x -> x + base]"]
             :expr  ["base is 100"
                     "add-base is [x -> x + base]"]
             :kind  :setup-only}
            {:setup ["base is 100"
                     "add-base is [x -> x + base]"]
             :expr  "add-base 42"}
            {:setup ["apply-twice is [f x -> f (f x)]"
                     "double is [x -> x * 2]"]
             :expr  ["apply-twice is [f x -> f (f x)]"
                     "double is [x -> x * 2]"]
             :kind  :setup-only}
            {:setup ["apply-twice is [f x -> f (f x)]"
                     "double is [x -> x * 2]"]
             :expr  "apply-twice double 5"}
            {:setup ["factorial is [n ->"
                     "  | n <= 1 -> 1"
                     "  | _ -> n * factorial (n - 1)"
                     "]"]
             :expr  ["factorial is [n ->"
                     "  | n <= 1 -> 1"
                     "  | _ -> n * factorial (n - 1)"
                     "]"]
             :kind  :setup-only}
            {:setup ["factorial is [n -> | n <= 1 -> 1 | _ -> n * factorial (n - 1)]"]
             :expr  "factorial 6"}]}

   {:title "Pipelines"
    :items [{:expr "[1 2 3 4 5] |> filter _ > 2 |> map [x -> x * 10]"}
            {:expr "[5 3 1 4 2] |> sort"}
            {:expr "[1 2 3 4 5] |> reduce [acc x -> acc + x] 0"}
            {:setup ["users is [{name: \"Bob\" score: 70} {name: \"Alice\" score: 90} {name: \"Carol\" score: 80}]"]
             :expr  ["users is [{name: \"Bob\" score: 70} {name: \"Alice\" score: 90} {name: \"Carol\" score: 80}]"
                     "|> sort-by _.score |> reverse |> map _.name"]
             :kind  :setup-only}
            {:setup ["users is [{name: \"Bob\" score: 70} {name: \"Alice\" score: 90} {name: \"Carol\" score: 80}]"]
             :expr  "users |> sort-by _.score |> reverse |> map _.name"}]}

   {:title "Binding & Destructuring"
    :items [{:setup ["total is 3 + 4"]
             :expr  "total is 3 + 4"
             :kind  :setup-only}
            {:setup ["total is 3 + 4"]
             :expr  "total"}
            {:setup ["user is {name: \"Alice\" age: 30}"
                     "{name age} is user"]
             :expr  "{name age} is user"
             :kind  :setup-only}
            {:setup ["user is {name: \"Alice\" age: 30}"
                     "{name age} is user"]
             :expr  "name"}
            {:setup ["dave is {name: \"Dave\" department: \"Engineering\"}"
                     "{name: emp-name department: dept} is dave"]
             :expr  "{name: emp-name department: dept} is dave"
             :kind  :setup-only}
            {:setup ["dave is {name: \"Dave\" department: \"Engineering\"}"
                     "{name: emp-name department: dept} is dave"]
             :expr  "emp-name"}
            {:setup ["[a b & rest] is [10 20 30 40 50]"]
             :expr  "[a b & rest] is [10 20 30 40 50]"
             :kind  :setup-only}
            {:setup ["[a b & rest] is [10 20 30 40 50]"]
             :expr  "rest"}]}

   {:title "Pattern Matching"
    :items [{:setup ["score is 85"
                     "grade is"
                     "  | score >= 90 -> \"A\""
                     "  | score >= 80 -> \"B\""
                     "  | score >= 70 -> \"C\""
                     "  | _ -> \"F\""]
             :expr  ["score is 85"
                     "grade is"
                     "  | score >= 90 -> \"A\""
                     "  | score >= 80 -> \"B\""
                     "  | score >= 70 -> \"C\""
                     "  | _ -> \"F\""]
             :kind  :setup-only}
            {:setup ["score is 85"
                     "grade is | score >= 90 -> \"A\" | score >= 80 -> \"B\" | score >= 70 -> \"C\" | _ -> \"F\""]
             :expr  "grade"}
            {:setup ["role is \"editor\""
                     "active is true"
                     "access is"
                     "  | role = \"admin\" or role = \"superadmin\" -> \"full\""
                     "  | role = \"editor\" and active            -> \"write\""
                     "  | _                                     -> \"read\""]
             :expr  ["role is \"editor\""
                     "access is"
                     "  | role = \"admin\" -> \"full\""
                     "  | role = \"editor\" and active -> \"write\""
                     "  | _ -> \"read\""]
             :kind  :setup-only}
            {:setup ["role is \"editor\""
                     "active is true"
                     "access is | role = \"admin\" or role = \"superadmin\" -> \"full\" | role = \"editor\" and active -> \"write\" | _ -> \"read\""]
             :expr  "access"}]}

   {:title "Error Messages"
    :items [{:expr   "nonexistent-var"
             :error? true}
            {:setup  ["user is {name: \"Alice\"}"]
             :expr   "users.name"
             :error? true}
            {:expr   "\"hello\" + 5"
             :error? true}
            {:expr   "5 / 0"
             :error? true}]}])

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Entry point for the demo runner."
  [& _args]
  (println)
  (print-header)

  (doseq [{:keys [title items]} demo-sections]
    (print-section-header title)
    (doseq [item items]
      (run-item item)))

  (println (str "  " DIM MAGENTA (repeat-str (+ CONTENT-WIDTH 4) "─") RESET))
  (println (str "  " DIM-WHITE "  DataTwist v0.1  ·  pipe-first functional data processing" RESET))
  (println (str "  " DIM "  Built on Clojure/JVM  ·  Instaparse grammar" RESET))
  (println))
