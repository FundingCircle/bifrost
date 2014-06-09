(ns uswitch.bifrost.s3
  (:require [com.stuartsierra.component :refer (Lifecycle system-map using)]
            [clojure.tools.logging :refer (info warn error)]
            [clojure.core.async :refer (<! go-loop chan close! alts! timeout)]
            [clojure.java.io :refer (file)]
            [aws.sdk.s3 :refer (put-object bucket-exists? create-bucket)]
            [metrics.timers :refer (time! timer)]
            [clj-kafka.zk :refer (committed-offset set-offset!)]
            [uswitch.bifrost.util :refer (close-channels)]
            [uswitch.bifrost.async :refer (observable-chan)]))

(defn generate-key [consumer-group-id topic partition first-offset]
  (format "%s/%s/partition=%s/%s.baldr.gz"
          consumer-group-id
          topic
          partition
          (format "%010d" first-offset)))

(defn upload-to-s3 [credentials bucket consumer-group-id topic partition first-offset file-path]
  (let [f (file file-path)]
    (if (.exists f)
      (let [key      (generate-key consumer-group-id topic partition first-offset)
            dest-url (str "s3n://" bucket "/" key)]
        (info "Uploading" file-path "to" dest-url)
        (time! (timer (str topic "-s3-upload-time"))
               (put-object credentials bucket key f))
        (info "Finished uploading" dest-url))
      (warn "Unable to find file" file-path))))

(defn progress-s3-upload
  "Performs a step through uploading a file to S3. Returns {:goto :pause}"
  [state
   credentials bucket consumer-properties
   topic partition
   first-offset last-offset
   file-path]
  (case state
    nil          {:goto :upload-file}
    :upload-file (try (upload-to-s3 credentials bucket (consumer-properties "group.id")
                                    topic partition
                                    first-offset
                                    file-path)
                      {:goto :commit}
                      (catch Exception e
                        (error e "Error whilst uploading to S3. Retrying in 15s.")
                        {:goto  :upload-file
                         :pause (* 15 1000)}))
    :commit      (try
                   (set-offset! consumer-properties (consumer-properties "group.id") topic partition last-offset)
                   (info "Committed offset information to ZooKeeper" topic partition (inc last-offset))
                   {:goto :delete}
                   (catch Exception e
                     (error e "Unable to commit offset to ZooKeeper. Retrying in 15s.")
                     {:goto :commit
                      :pause (* 15 1000)}))
    :delete      (try
                   (info "Deleting file" file-path)
                   (if (.delete (file file-path))
                     (info "Deleted" file-path)
                     (info "Unable to delete" file-path))
                   {:goto :done}
                   (catch Exception e
                     (error e "Error while deleting file" file-path)
                     {:goto :done}))))

;; TODO: uploaders-n is ignored for now. We need to create loops for
;; each topic, partition to be able to fan out.
(defrecord S3Upload [credentials bucket consumer-properties uploaders-n
                     rotated-event-ch]
  Lifecycle
  (start [this]

    (info "Starting S3Upload component.")
    (when-not (bucket-exists? credentials bucket)
      (info "Creating" bucket "bucket")
      (create-bucket credentials bucket))

    (let [control-ch (chan)]

      (go-loop
       []
       (let [[v c] (alts! [control-ch rotated-event-ch])]
         (if (or (= control-ch c) (nil? v))

           (info "Terminating S3 uploader")

           (let [{:keys [topic partition file-path first-offset last-offset]} v]
             (info "Starting S3 upload of" file-path)
             (loop [state nil]
               (let [{:keys [goto pause]} (progress-s3-upload state
                                                              credentials bucket consumer-properties
                                                              topic partition
                                                              first-offset last-offset
                                                              file-path)
                     [v c] (alts! [control-ch (timeout (or pause 0))])]
                 (if (= c control-ch)
                   (info "Terminating S3 uploader during upload")
                   (if (= :done goto)
                     (info "Terminating stepping S3 upload machine.")
                     (recur goto)))))
             (info "Done uploading to S3:" file-path)
             (recur)))))
      (assoc this :control-ch control-ch)))
  (stop [this]
    (close-channels this :control-ch)))

(defn s3-upload [config]
  (map->S3Upload (select-keys config [:credentials :bucket :consumer-properties :uploaders-n])))

(defn s3-system [config]
  (system-map :uploader (using (s3-upload config)
                               [:rotated-event-ch])))
