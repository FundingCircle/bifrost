(ns uswitch.bifrost.main
  (:require [uswitch.bifrost.system :refer (make-system)]
            [uswitch.bifrost.version :refer (current-version current-build-number)]
            [com.stuartsierra.component :refer (start)]
            [clojure.tools.logging :refer (info)]
            [clojure.tools.cli :refer (parse-opts)]
            [metrics.gauges :refer (gauge)])
  (:gen-class))

(defn wait! []
  (let [s (java.util.concurrent.Semaphore. 0)]
    (.acquire s)))

(def cli-options
  [["-c" "--config CONFIG" "Path to EDN configuration file"
    :default "./etc/config.edn"
    :validate [string?]]
   ["-h" "--help"]])

(defn partial-interpose [s]
  (let [first-char (first s)
        length (- (count s) 2)
        interposed (clojure.string/join (repeat length \X))
        last-char (last s)]
    (str first-char interposed last-char)))


(defn interpose-credentials [{:keys [access-key secret-key] :as credentials}]
  (-> credentials
      (update :access-key partial-interpose)
      (update :secret-key partial-interpose)))

(defn credentials
  [{:keys [access-key secret-key]}]
  {:access-key (or (System/getenv "AWS_ACCESS_KEY_ID") access-key)
   :secret-key (or (System/getenv "AWS_SECRET_ACCESS_KEY") secret-key)})

(defn -main [& args]
  (let [{:keys [options summary]} (parse-opts args cli-options)]
    (when (:help options)
      (println summary)
      (System/exit 0))
    (let [{:keys [config]} options
          config (-> config (slurp) (read-string) (update-in [:credentials] credentials))]
      (info "Bifrost" (current-version))
      (when (current-build-number)
        (gauge "build-number" (current-build-number)))
      (info "Starting Bifrost with config" (update-in config [:credentials] interpose-credentials))
      (start (make-system config))
      (wait!))))
