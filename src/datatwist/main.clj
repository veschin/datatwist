(ns datatwist.main
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [datatwist.parser :as parser]
            [datatwist.error-renderer :as renderer])
  (:gen-class))

(def version "0.1.0")

(def global-opts
  [["-h" "--help" "Show help"]
   ["-v" "--version" "Show version"]
   ["-e" "--expression EXPR" "Expression to evaluate"]])

(defn parse-args
  "Parse CLI arguments into a command map.
   Returns {:command :eval/:run/:repl/:fmt/:help/:version/:error ...}"
  [args]
  (let [{:keys [options arguments errors]} (parse-opts args global-opts
                                                       :in-order true)]
    (cond
      errors
      {:command :error :message (str/join "\n" errors)}

      (:help options)
      {:command :help}

      (:version options)
      {:command :version}

      :else
      (let [subcmd (first arguments)
            rest-args (rest arguments)]
        (case subcmd
          "eval" (let [{:keys [options errors]} (parse-opts rest-args global-opts)]
                   (cond
                     errors {:command :error :message (str/join "\n" errors)}
                     (:expression options) {:command :eval :expression (:expression options)}
                     :else {:command :error :message "eval requires -e <expression>"}))
          "run"  (if-let [file (first rest-args)]
                   {:command :run :file file}
                   {:command :error :message "run requires a file argument"})
          "repl" {:command :repl}
          "fmt"  {:command :fmt}
          nil    {:command :repl}
          {:command :error :message (str "Unknown command: " subcmd)})))))

(defn eval-expression
  "Evaluate a DataTwist expression string. Returns the result."
  [expr]
  (parser/eval-dt expr))

(defn run-file
  "Read and evaluate a DataTwist file. Returns the result."
  [path]
  (let [source (slurp path)]
    (parser/eval-dt source)))

(defn- print-result
  "Print a result value to stdout."
  [result]
  (when (some? result)
    (prn result)))

(defn- usage []
  (str "DataTwist " version "\n"
       "\n"
       "Usage: datatwist <command> [options]\n"
       "\n"
       "Commands:\n"
       "  eval -e <expr>   Evaluate an expression\n"
       "  run <file.dt>    Execute a DataTwist file\n"
       "  repl             Start interactive REPL\n"
       "  fmt              Format DataTwist source (not implemented)\n"
       "\n"
       "Options:\n"
       "  -h, --help       Show this help\n"
       "  -v, --version    Show version\n"
       "\n"
       "Examples:\n"
       "  datatwist eval -e '1 + 2'\n"
       "  datatwist run script.dt\n"
       "  datatwist repl\n"))

(defn- repl-loop
  "Run an interactive read-eval-print loop."
  []
  (println (str "DataTwist " version " REPL"))
  (println "Type an expression and press Enter. Ctrl-D to exit.")
  (print "dt> ")
  (flush)
  (loop []
    (when-let [line (read-line)]
      (let [trimmed (str/trim line)]
        (when-not (str/blank? trimmed)
          (try
            (let [result (parser/eval-dt trimmed)]
              (print-result result))
            (catch clojure.lang.ExceptionInfo e
              (let [data (ex-data e)]
                (if (:dt/error data)
                  (binding [*out* *err*]
                    (println (renderer/render-error data)))
                  (binding [*out* *err*]
                    (println (str "Error: " (.getMessage e)))))))
            (catch Exception e
              (binding [*out* *err*]
                (println (str "Error: " (.getMessage e))))))))
      (print "dt> ")
      (flush)
      (recur))))

(defn -main [& args]
  (let [{:keys [command expression file message]} (parse-args args)]
    (case command
      :eval    (if (parser/parse-error? expression)
                 (do (binding [*out* *err*]
                       (println (str "Parse error in expression: " expression)))
                     (System/exit 2))
                 (try
                   (print-result (eval-expression expression))
                   (System/exit 0)
                   (catch clojure.lang.ExceptionInfo e
                     (let [data (ex-data e)]
                       (if (:dt/error data)
                         (do (binding [*out* *err*]
                               (println (renderer/render-error data)))
                             (System/exit 1))
                         (do (binding [*out* *err*]
                               (println (str "Error: " (.getMessage e))))
                             (System/exit 1)))))
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "Error: " (.getMessage e))))
                     (System/exit 1))))

      :run     (let [source (try (slurp file)
                                 (catch java.io.FileNotFoundException _
                                   (binding [*out* *err*]
                                     (println (str "Error: file not found: " file)))
                                   (System/exit 1)))]
                 (if (parser/parse-error? source)
                   (do (binding [*out* *err*]
                         (println (str "Parse error in file: " file)))
                       (System/exit 2))
                   (try
                     (print-result (parser/eval-dt source))
                     (System/exit 0)
                     (catch clojure.lang.ExceptionInfo e
                       (let [data (ex-data e)]
                         (if (:dt/error data)
                           (do (binding [*out* *err*]
                                 (println (renderer/render-error data)))
                               (System/exit 1))
                           (do (binding [*out* *err*]
                                 (println (str "Error: " (.getMessage e))))
                               (System/exit 1)))))
                     (catch Exception e
                       (binding [*out* *err*]
                         (println (str "Error: " (.getMessage e))))
                       (System/exit 1)))))

      :repl    (repl-loop)

      :fmt     (do (binding [*out* *err*]
                     (println "Error: fmt is not implemented yet"))
                   (System/exit 1))

      :help    (do (println (usage))
                   (System/exit 0))

      :version (do (println (str "datatwist " version))
                   (System/exit 0))

      :error   (do (binding [*out* *err*]
                     (println (str "Error: " message))
                     (println "")
                     (println (usage)))
                   (System/exit 3)))))
