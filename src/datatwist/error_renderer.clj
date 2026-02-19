(ns datatwist.error-renderer
  "Elm/Rust-style error renderer for DataTwist.

   Produces formatted error output like:
     -- TYPE MISMATCH [DT-T001] ----------------------------------- stdin:3 --

     Cannot add string and integer.

      3 |  \"hello\" + 5
                     ^

     Hint: The + operator works on two numbers or two strings.
           Got string and integer.

   Respects NO_COLOR and DT_NO_COLOR environment variables.
   ANSI colors are enabled by default when running in a terminal.")

;; ---------------------------------------------------------------------------
;; Color support
;; ---------------------------------------------------------------------------

(def ^:dynamic *use-color*
  "When true, ANSI escape codes are included in rendered output.
   Defaults to false (safe for tests and non-terminal output).
   Bind to true for interactive terminal use."
  false)

(defn- no-color?
  "Returns true if the NO_COLOR or DT_NO_COLOR env var is set."
  []
  (or (System/getenv "NO_COLOR")
      (System/getenv "DT_NO_COLOR")))

(defn- use-color?
  "Returns true if color output should be enabled."
  []
  (and *use-color* (not (no-color?))))

(defn- ansi
  "Wrap text in an ANSI escape sequence if color is enabled."
  [code text]
  (if (use-color?)
    (str "\u001b[" code "m" text "\u001b[0m")
    text))

(def ^:private red    #(ansi "31" %))
(def ^:private yellow #(ansi "33" %))
(def ^:private cyan   #(ansi "36" %))
(def ^:private bold   #(ansi "1" %))
(def ^:private dim    #(ansi "2" %))

;; ---------------------------------------------------------------------------
;; Header rendering
;; ---------------------------------------------------------------------------

(defn- render-header
  "Render the error header line:
     -- CATEGORY [CODE] ------------------------------------------ file:line --"
  [{:keys [code category line]} file-hint total-width]
  (let [file-hint (or file-hint "stdin")
        location  (if line (str file-hint ":" line) file-hint)
        code-str  (if code (str " [" code "]") "")
        inner     (str " " (or category "ERROR") code-str " ")
        right     (str " " location " ")
        dashes    (max 0 (- total-width (count inner) (count right) 4))
        left-fill (apply str (repeat dashes "-"))]
    (bold (str "-- " inner left-fill right "--"))))

;; ---------------------------------------------------------------------------
;; Source snippet rendering
;; ---------------------------------------------------------------------------

(defn- render-snippet
  "Render a source code snippet with line number gutter and caret underline.
   Returns a multi-line string."
  [{:keys [source line col-start col-end]}]
  (when (and source (string? source))
    (let [lines        (clojure.string/split-lines source)
          target-line  (or line 1)
          line-idx     (dec target-line)
          source-line  (when (< line-idx (count lines))
                         (nth lines line-idx nil))
          gutter-width (count (str target-line))
          gutter       (fn [n] (format (str "%" gutter-width "d") n))
          sep          (dim " | ")]
      (when source-line
        (let [col-s    (or col-start 1)
              col-e    (or col-end (inc col-s))
              caret    (str (apply str (repeat (dec col-s) " "))
                            (apply str (repeat (max 1 (- col-e col-s)) "^")))
              line-str (str " " (gutter target-line) sep source-line)
              caret-str (str " " (apply str (repeat gutter-width " ")) sep
                             (yellow caret))]
          (str line-str "\n" caret-str))))))

;; ---------------------------------------------------------------------------
;; Hint rendering
;; ---------------------------------------------------------------------------

(defn- render-hint
  "Render the hint section."
  [{:keys [hint]}]
  (when hint
    (str (cyan "Hint:") " " hint)))

;; ---------------------------------------------------------------------------
;; Full error rendering
;; ---------------------------------------------------------------------------

(defn render-error
  "Render a DataTwist error map as an Elm/Rust-style formatted string.

   Options:
     :file       — source file name for the header (default \"stdin\")
     :width      — total line width (default 80)

   Respects *use-color* dynamic var and NO_COLOR env var."
  ([error-map]
   (render-error error-map {}))
  ([error-map {:keys [file width] :or {file "stdin" width 80}}]
   (when (map? error-map)
     (let [header  (render-header error-map file width)
           message (:message error-map)
           snippet (render-snippet error-map)
           hint    (render-hint error-map)
           parts   (remove nil? [header
                                 ""
                                 message
                                 (when snippet "")
                                 snippet
                                 (when hint "")
                                 hint])]
       (clojure.string/join "\n" parts)))))

;; ---------------------------------------------------------------------------
;; JSON error output mode (Step 6)
;; ---------------------------------------------------------------------------

(defn- map->json-str
  "Convert a simple flat map to a JSON string.
   Only handles string/number/boolean/nil values (no nested maps)."
  [m]
  (let [pairs (for [[k v] m
                    :when (contains? #{:code :message :hint :category
                                       :line :col-start :col-end :source} k)
                    :when (some? v)]
                (let [key-str (name k)
                      val-str (cond
                                (string? v)  (str "\"" (clojure.string/replace v "\"" "\\\"") "\"")
                                (number? v)  (str v)
                                (keyword? v) (str "\"" (name v) "\"")
                                :else        (str "\"" v "\""))]
                  (str "  \"" key-str "\": " val-str)))]
    (str "{\n" (clojure.string/join ",\n" pairs) "\n}")))

(defn render-error-json
  "Render a DataTwist error map as a JSON string.
   Includes: code, message, hint, category, line, col-start, col-end, source."
  [error-map]
  (when (map? error-map)
    (map->json-str error-map)))

;; ---------------------------------------------------------------------------
;; Exception → rendered output
;; ---------------------------------------------------------------------------

(defn render-exception
  "Given a caught exception, render it as a formatted error string.
   If it is an ExceptionInfo with :dt/error true, uses render-error.
   Otherwise returns the exception message."
  ([e] (render-exception e {}))
  ([e opts]
   (if (instance? clojure.lang.ExceptionInfo e)
     (let [data (ex-data e)]
       (if (:dt/error data)
         (render-error (assoc data :message (.getMessage e)) opts)
         (.getMessage e)))
     (.getMessage e))))
