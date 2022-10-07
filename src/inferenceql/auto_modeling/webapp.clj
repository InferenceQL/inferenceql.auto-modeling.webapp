(ns inferenceql.auto-modeling.webapp
  (:require [babashka.fs :as fs]
            [clojure.java.browse :as browse]
            [com.stuartsierra.component :as component]
            [inferenceql.auto-modeling.webapp.dvc :as dvc]
            [inferenceql.auto-modeling.webapp.server :as server]))

(defn new-system
  [root]
  (let [root (if-not root
               (fs/cwd)
               (-> root
                   (fs/path)
                   (fs/expand-home)
                   (fs/canonicalize)))]
    (-> (component/system-map
         :dvc (dvc/dvc (str (fs/path root "dvc.yaml")))
         :web-server (server/jetty-server :port 8080))
        (component/system-using
         {:web-server [:dvc]}))))

(defn run
  [{:keys [root]}]
  (component/start (new-system root))
  (browse/browse-url "http://localhost:8081"))
