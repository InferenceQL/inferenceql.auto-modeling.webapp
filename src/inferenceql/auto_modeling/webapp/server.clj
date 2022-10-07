(ns inferenceql.auto-modeling.webapp.server
  (:require [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace]
            [com.stuartsierra.component :as component]
            [inferenceql.auto-modeling.webapp.dvc :as dvc]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.format-response :refer [wrap-restful-response]]
            [ring.util.response :as response]))

(defn not-found-handler
  [_request]
  (-> (response/not-found "Not found")
      (response/header "Content-Type" "text/plain")))

(def exception-middleware
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {::exception/default (fn [exception request]
                           {:status  500
                            :exception (with-out-str (stacktrace/print-stack-trace exception))
                            :uri (:uri request)
                            :body "Internal server error"})
     ::exception/wrap (fn [handler e request]
                        (println "ERROR" (pr-str (:uri request)))
                        (stacktrace/print-stack-trace e)
                        (flush)
                        (handler e request))})))

(defn page-handler
  [_]
  (response/response (slurp (io/resource "public/index.html"))))

(defn status-handler
  [dvc]
  (let [qc-opened (atom false)]
    (fn [_]
      (let [{:keys [steps] :as status} (dvc/pipeline-status dvc)]
        (when (and (not @qc-opened)
                   (= :succeeded (:status (last steps))))
          (browse/browse-url "http://localhost:8081/satellites-introduction.adoc")
          (reset! qc-opened true))
        (response/response status)))))

(defn start-handler
  [dvc]
  (fn [_]
    (if (dvc/running? dvc)
      {:status 409 :body "Already started"}
      (do (dvc/start-run! dvc)
          (response/created "/api/dvc" "Run started")))))

(defn stop-handler
  [dvc]
  (fn [_]
    (dvc/stop-run! dvc)
    {:status 204}))

(defn app
  [dvc]
  (ring/ring-handler
   (ring/router
    [["/" {:get page-handler}]
     ["/api" {:middleware [[wrap-restful-format :formats [:json]]
                           [wrap-restful-response]]}
      ["/dvc" {:get (#'status-handler dvc)
               :post (#'start-handler dvc)
               :delete (#'stop-handler dvc)}]]
     ["/js/*" (ring/create-resource-handler {:root "js"})]])
   #'not-found-handler
   {:middleware [exception-middleware]}))

(defrecord JettyServer [port dvc]
  component/Lifecycle

  (start [component]
    (let [handler (#'app dvc)
          jetty-server (jetty/run-jetty handler {:port port :join? false})]
      (assoc component :server jetty-server)))

  (stop [{:keys [server]}]
    (when server
      (.stop server))))

(defn jetty-server
  [& {:as opts}]
  (map->JettyServer opts))
