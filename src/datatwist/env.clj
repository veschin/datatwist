(ns datatwist.env)

(defn make-env
  "Create a new environment from a map of string->value bindings."
  [bindings]
  bindings)

(defn lookup
  "Look up a variable. Returns nil if not found (nil-tolerant)."
  [env name]
  (get env name))

(defn bind
  "Bind a single variable in the environment."
  [env name value]
  (assoc env name value))

(defn bind-many
  "Bind multiple variables from a map."
  [env bindings]
  (merge env bindings))
