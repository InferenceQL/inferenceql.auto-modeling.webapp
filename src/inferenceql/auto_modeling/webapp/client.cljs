(ns inferenceql.auto-modeling.webapp.client
  (:require ["@mantine/core" :as mantine]
            ["@mantine/hooks" :as mantine.hooks]
            ["@tabler/icons" :as icons]
            ["react-dom" :as react-dom]
            [goog.labs.format.csv :as csv]
            [helix.core :refer [$ <> defnc]]
            [helix.dom :as dom]
            [helix.hooks :as hooks]
            [lambdaisland.fetch :as fetch]))

(enable-console-print!)

(defnc pipeline
  []
  (let [[steps set-steps!] (hooks/use-state nil)
        start #(fetch/post "/api/dvc" {})
        stop #(fetch/delete "/api/dvc" {})
        update-steps! (fn []
                        (-> (fetch/get "/api/dvc"
                                       {:accept :json
                                        :mode :same-origin
                                        :cache :no-cache})
                            (.then (fn [result]
                                     (let [body (js->clj (:body result)
                                                         :keywordize-keys true)]
                                       (set-steps! (:steps body)))))))]
    (let [interval (mantine.hooks/useInterval update-steps! 1000)]
      (hooks/use-effect
       []
       (.start interval)
       (fn [] (.stop interval))))
    (when steps
      (<>
       ($ mantine/Button {:mb "sm" :mr "sm" :onClick start} "Start")
       ($ mantine/Button {:mb "sm" :mr "sm" :onClick stop :variant "outline"} "Stop")
       (let [percent-complete (/ (count (filter #(= "succeeded" (:status %))
                                                steps))
                                 (count steps))]
         ($ mantine/Progress
            {:value (* percent-complete 100)
             :size "xl"
             :animate true}))))))

(defnc app
  []
  (let [[active set-active!] (hooks/use-state 0)
        [file set-file!] (hooks/use-state nil)
        [text set-text!] (hooks/use-state nil)
        [stat-types set-stat-types!] (hooks/use-state {})
        [null-input set-null-input!] (mantine.hooks/useInputState "")
        [null-values null-handlers] (mantine.hooks/useListState [])
        dissoc-stat-type! (fn [column]
                            (set-stat-types! (dissoc stat-types column)))
        assoc-stat-type! (fn [column stat-type]
                           (set-stat-types! (assoc stat-types column stat-type)))
        [ignored-cols set-ignored-cols!] (hooks/use-state #{})
        set-ignored! (fn [column is-ignored]
                       (if is-ignored
                         (set-ignored-cols! (conj ignored-cols column))
                         (set-ignored-cols! (disj ignored-cols column))))
        valid (case active
                0 (some? text)
                1 true
                2 true
                3 true)
        jump-active! (fn [new-active]
                       (when valid
                         (set-active! new-active)))
        next! #(when valid
                 (set-active! (inc active)))
        prev! #(set-active! (dec active))
        set-file! (fn [file]
                    (set-file! file)
                    (set-text! (some-> file (.text) (.then set-text!))))]
    ($ mantine/MantineProvider
       {:withGlobalStyles true
        :withNormalizeCSS true}
       ($ mantine/Container
          {:size "xl" :p "md"}
          ($ mantine/Center {}
             ($ mantine/Stepper
                {:active active
                 :breakpoint "sm"
                 :onStepClick jump-active!}
                ($ mantine/Stepper.Step
                   {:icon ($ icons/IconFileSpreadsheet {})
                    :label "Choose CSV file"
                    :description "Choose data to be modeled"})
                ($ mantine/Stepper.Step
                   {:icon ($ icons/IconTableOptions {})
                    :label "Identify missing values"
                    :description "List values to be treated as missing"})
                ($ mantine/Stepper.Step
                   {:icon ($ icons/IconZoomQuestion {})
                    :label "Assign types"
                    :description "Assign statistical types to columns"})
                ($ mantine/Stepper.Step
                   {:icon ($ icons/IconSettingsAutomation {})
                    :label "Learn models"
                    :description "Learn model from the data"})))
          ($ mantine/Group {:position "center" :mt "xl" :mb "xl"}
             ($ mantine/Button.Group
                {}
                ($ mantine/Button
                   {:disabled (< active 1)
                    :onClick prev!
                    :variant "default"}
                   "Back")
                ($ mantine/Button
                   {:disabled (or (nil? file)
                                  (not (<= 0 active 2)))
                    :onClick next!
                    :variant "filled"}
                   "Next")))
          ($ mantine/Container
             {:size "xs"}
             (case active
               0 ($ mantine/Container
                    {:size "xs"}
                    ($ mantine/FileInput
                       {:accept "text/csv"
                        :icon ($ icons/IconFileSpreadsheet {})
                        :label "CSV file"
                        :placeholder "Choose a CSV file"
                        :onChange set-file!
                        :value file
                        :required true}))
               ;; https://mantine.dev/hooks/use-list-state/
               1 (<> ($ mantine/Title {:order 2}
                        "Missing placeholders")
                     ($ mantine/Text {:size "sm" :color "dimmed" :mb "md"}
                        "Values to be treated as missing")
                     (map-indexed (fn [index null-value]
                                    ($ mantine/Badge
                                       {:leftSection ($ mantine/ActionIcon
                                                        {:size "xs"
                                                         :color "blue"
                                                         :radius "xl"
                                                         :variant "transparent"}
                                                        ($ icons/IconX {:size 10}))
                                        :onClick #(.remove null-handlers index)
                                        :styles (clj->js {:inner {:text-transform "none"}})
                                        :sx #js {:cursor "pointer"}
                                        :mr "sm"
                                        :mb "sm"}
                                       (dom/pre null-value)))
                                  null-values)
                     (let [handle-submit (fn [_]
                                           (when (seq null-input)
                                             (.append null-handlers null-input)
                                             (set-null-input! "")))]
                       ($ mantine/Group {:spacing "xs"}
                          ($ mantine/TextInput
                             {:onKeyDown (mantine.hooks/getHotkeyHandler
                                          (clj->js [["Enter" handle-submit]]))
                              :onChange set-null-input!
                              :placeholder "Enter a value"
                              :value null-input})
                          ($ mantine/Button
                             {:disabled (empty? null-input)
                              :onClick handle-submit
                              :variant "outline"}
                             "Add"))))
               2 (<> ($ mantine/Title {:order 2}
                        "Statistical types")
                     ($ mantine/SimpleGrid
                        {:sx #js {:gridTemplateColumns "1fr repeat(2, max-content)"}}
                        (let [columns (-> text
                                          (csv/parse)
                                          (first))]
                          (for [column columns]
                            (let [ignored (contains? ignored-cols column)]
                              (<> ($ mantine/Text
                                     {:color (if ignored "dimmed" "black")
                                      :lineClamp 1}
                                     column)
                                  ($ mantine/SegmentedControl
                                     {:disabled ignored
                                      :data (clj->js [{:label "Auto" :value "guess"}
                                                      {:label "Nominal" :value "nominal"}
                                                      {:label "Numerical" :value "numerical"}])
                                      :value (clj->js (get stat-types column "guess"))
                                      :onChange #(if (= "guess" %)
                                                   (dissoc-stat-type! column)
                                                   (assoc-stat-type! column %))
                                      :size "xs"})
                                  ($ mantine/Switch
                                     {:label "Ignore"
                                      :checked ignored
                                      :onChange (fn [event]
                                                  (set-ignored! column
                                                                (-> event
                                                                    (.-currentTarget)
                                                                    (.-checked))))})))))))
               3 ($ pipeline {})))))))

(let [element (js/window.document.querySelector "#app")]
  (react-dom/render ($ app {})
                    element))
