(ns user
  (:require [clojure.tools.namespace.repl :as repl]
            [com.stuartsierra.component :as component]
            [inferenceql.auto-modeling.webapp :as webapp]
            [inferenceql.auto-modeling.webapp.dvc :as dvc]))

(def system nil)

(defn nilsafe
  "Returns a function that calls f on its argument if its argument is not nil."
  [f]
  (fn [x]
    (when x
      (f x))))

(defn init
  "Constructs the current development system."
  []
  (alter-var-root #'system (fn [_] (webapp/new-system "../inferenceql.auto-modeling"))))

(defn start
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system (nilsafe component/stop)))

(defn go
  "Initializes the current development system and starts it running."
  []
  (init)
  (start))

(defn reset []
  (stop)
  (repl/refresh :after 'user/go))

(comment

  (-> (:dvc system) :state deref :process :exit)

  (go)
  (reset)
  (stop)
  (start)

  system

  (dvc/start-run! (:dvc system))

  (dvc/stop-run! (:dvc system))

  (dvc/running? (:dvc system))

  (dvc/pipeline-status (:dvc system))

  ,)
