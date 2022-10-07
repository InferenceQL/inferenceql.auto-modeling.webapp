(ns inferenceql.auto-modeling.webapp.dvc
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [lambdaisland.regal :as regal]))

(defn read-yaml
  [path]
  (yaml/parse-string (slurp path)))

(defn input-stream-atom
  [input-stream]
  (let [out (atom "")]
    (future
      (loop []
        (let [c (.read input-stream)]
          (when (not= c -1)
            (swap! out str (char c))
            (recur)))))
    out))

(declare map->DVC)

(defn dvc
  [path]
  (map->DVC {:yaml-path (-> path
                            (fs/expand-home)
                            (fs/canonicalize)
                            (str))
             :run (atom nil)}))

(defn start-run! [{:keys [yaml-path] :as dvc}]
  (let [working-dir (str (fs/parent yaml-path))
        {:keys [out err] :as process} (process/process "nix-shell --run 'dvc repro -f'"
                                                       {:dir working-dir})
        out (-> (io/reader out)
                (input-stream-atom))
        err (-> (io/reader err)
                (input-stream-atom))]
    (reset! (:run dvc)
            {:yaml (read-yaml yaml-path)
             :process process
             :out out
             :err err})
    dvc))

(defn stop-run!
  [dvc]
  (when-let [process (some-> dvc :run deref :process :proc)]
    (.destroyForcibly process))
  (reset! (:run dvc) nil)
  dvc)

(defn running?
  [dvc]
  (if-let [run @(:run dvc)]
    (nil? (get-in run [:process :exit]))
    false))

(defrecord DVC [yaml-path run]
  component/Lifecycle
  (start [dvc]
    dvc)

  (stop [dvc]
    (stop-run! dvc)))

(defn parse-out
  "Parses DVC output into a map of stages, their output, and whether they have
  completed."
  ([s]
   (parse-out s nil))
  ([s exit-code]
   (let [stage-line [:cat "Running stage '"
                     [:capture [:+ [:not \']]]
                     "':" :newline]
         content-lines [:capture
                        [:+
                         [:cat
                          [:negative-lookahead "Running stage"]
                          [:* :any]
                          :newline]]]
         re (regal/regex
             [:cat
              stage-line
              content-lines
              [:? [:lookahead stage-line]]])]
     (into []
           (map (fn [[_ stage content next-stage]]
                  (let [status (if next-stage
                                 :succeeded
                                 (case exit-code
                                   0 :succeeded
                                   nil :in-progress
                                   :failed))]
                    {:name (keyword stage)
                     :out content
                     :status status})))
           (re-seq re s)))))

(defn update-stages
  [stages out]
  (let [steps (if-not out [] (parse-out out))]
    (->> (reduce (fn [stages {:keys [name] :as step}]
                   (update stages name into step))
                 stages
                 steps))))

(defn ordered-map->vector
  ([m]
   (ordered-map->vector m nil))
  ([m k]
   (reduce-kv (fn [coll k1 v]
                (conj coll
                      (if-not k v (assoc v k k1))))
              []
              m)))

(defn ordered-map->map
  [m]
  (into {} (seq m)))

(defn pipeline-status
  [dvc]
  (let [run @(:run dvc)
        yaml (if run
               (:yaml run)
               (read-yaml (:yaml-path dvc)))
        stages (:stages yaml)
        output-str (when run @(:out run))
        steps (->> (-> stages
                       (update-vals #(if (:status %)
                                       %
                                       (assoc % :status :not-started)))
                       (update-stages output-str)
                       (ordered-map->vector :name))
                   (map ordered-map->map))]
    {:steps steps}))

(comment

  (def test-dvc (dvc "~/projects/inferenceql.auto-modeling/dvc.yaml"))
  (running? test-dvc)
  (start-run! test-dvc)

  (println (:out @(:run test-dvc)))
  (->> (pipeline-status test-dvc)
       (:steps)
       (map (juxt :name :status))
       (clojure.pprint/pprint))

  )
