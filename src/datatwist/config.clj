(ns datatwist.config
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Config storage
;; ---------------------------------------------------------------------------

(def ^:private defaults
  {:SAMPLE_SIZE          100
   :DESCRIBE_SAMPLE_SIZE 1000
   :PRINT_WIDTH          120
   :MAX_COLLECT_ROWS     nil})

(def ^:private state (atom defaults))

(defn get-config
  "Return the current value of config key k (a keyword like :SAMPLE_SIZE)."
  [k]
  (get @state k))

(defn set-config!
  "Set config key k to value v. Throws with hint if k is not a valid key."
  [k v]
  (if (contains? defaults k)
    (swap! state assoc k v)
    (throw (ex-info (str "Unknown system constant: " (name k))
                    {:dt/error true
                     :code     "DT-R030"
                     :category "CONFIG ERROR"
                     :message  (str "Unknown system constant: " (name k))
                     :hint     (str "Valid constants: "
                                    (str/join ", " (map name (keys defaults))))}))))

(defn valid-key?
  "Returns true if k (keyword) is a valid config key."
  [k]
  (contains? defaults k))

(defn reset-config!
  "Reset all config values to defaults. Used in tests."
  []
  (reset! state defaults))
