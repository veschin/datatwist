(ns datatwist.nrepl.middleware
  "nREPL middleware that intercepts eval messages and routes them through
   the DataTwist parser and evaluator. Follows the Piggieback pattern:
   sits above interruptible-eval in the middleware chain."
  (:require [nrepl.middleware :refer [set-descriptor!]]
            [nrepl.misc :refer [response-for]]
            [nrepl.transport :as transport]
            [datatwist.evaluator :as evaluator]
            [datatwist.stdlib :as stdlib]
            [datatwist.error-renderer :as renderer])
  (:import [java.io StringWriter]))

;; ---------------------------------------------------------------------------
;; Session environment helpers
;; ---------------------------------------------------------------------------

(def ^:private session-env-key ::env)

(defn- get-session-env
  "Read the DataTwist environment from the nREPL session atom.
   Returns nil if no DT env has been initialized yet."
  [session]
  (get @session session-env-key))

(defn- set-session-env!
  "Write the DataTwist environment into the nREPL session atom."
  [session env]
  (swap! session assoc session-env-key env))

(defn- ensure-session-env!
  "If the session has no DT env yet, initialize it from stdlib/default-env.
   Returns the current (possibly just-initialized) env."
  [session]
  (or (get-session-env session)
      (let [env (stdlib/default-env)]
        (set-session-env! session env)
        env)))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(defn- handle-dt-error
  "Send an nREPL error response for a DataTwist exception."
  [transport msg e]
  (let [err-data (ex-data e)
        rendered (renderer/render-exception e {:file "nrepl"})]
    (transport/send transport
                    (response-for msg
                                  {:err    rendered
                                   :ex     (or (:code err-data) "DT-R000")
                                   :status #{"done" "eval-error"}}))))

;; ---------------------------------------------------------------------------
;; Eval op
;; ---------------------------------------------------------------------------

(defn- handle-eval
  "Handle an nREPL eval op: parse and evaluate DataTwist code,
   persist updated env on success, leave env unchanged on error."
  [transport {:keys [code] :as msg} session]
  (let [env-before (ensure-session-env! session)]
    (try
      ;; Capture stdout from side-effect functions (tap!, print!, etc.)
      (let [stdout-writer (StringWriter.)
            result        (binding [*out* stdout-writer]
                            (evaluator/evaluate-with-env code env-before))
            stdout-str    (.toString stdout-writer)
            value         (:value result)
            new-env       (:env result)]
        ;; Send captured stdout as :out message if any
        (when (seq stdout-str)
          (transport/send transport
                          (response-for msg {:out stdout-str})))
        ;; Update session env on success
        (set-session-env! session new-env)
        ;; Send the value
        (transport/send transport
                        (response-for msg
                                      {:value  (pr-str value)
                                       :ns     "datatwist.user"
                                       :status #{"done"}})))
      (catch Exception e
        ;; Env stays at env-before -- no update on error
        (handle-dt-error transport msg e)))))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(defn wrap-datatwist-eval
  "nREPL middleware that intercepts eval messages for DataTwist sessions.
   A session becomes a DT session on first eval (auto-initialized).
   Non-eval ops pass through to the next handler."
  [handler]
  (fn [{:keys [op session transport] :as msg}]
    (case op
      "eval"
      (handle-eval transport msg session)

      ;; All other ops pass through
      (handler msg))))

(set-descriptor! #'wrap-datatwist-eval
                 {:requires #{"clone" "close" "describe"}
                  :expects  #{"eval"}
                  :handles  {"eval" {:doc "Evaluate DataTwist source code"
                                     :requires {"code" "The DataTwist source to evaluate"}
                                     :returns {"value" "The pr-str of the evaluation result"
                                               "ns" "Always datatwist.user"}}}})
