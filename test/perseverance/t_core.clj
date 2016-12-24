(ns perseverance.t-core
  (:require [clojure.test :refer :all]
            [perseverance.core :refer :all])
  (:import java.net.SocketTimeoutException
           java.io.IOException
           clojure.lang.ExceptionInfo))

(deftest constant-retry-strategy-test
  (is (= [100 100 100 100 100 100 100 100 100 100]
         (map (constant-retry-strategy 100) (range 1 11)))
      "Constant strategy returns same delay for each attempt.")

  (testing "Starts returning nils after max-count attempts."
    (is (= [1000 1000 1000]
           (map (constant-retry-strategy 1000 5) (range 1 4))))

    (is (= [10 10 10 10 10 nil nil nil nil nil]
           (map (constant-retry-strategy 10 5) (range 1 11))))))

(deftest progressive-retry-strategy-test
  (is (= [1 1 1 2 4 8 16 32 64 128]
         (map (progressive-retry-strategy :initial-delay 1 :stable-length 3
                                          :multiplier 2 :max-delay 1000)
              (range 1 11))))

  (is (= [1 2 4 8 16 32 64 128 256 512]
         (map (progressive-retry-strategy :initial-delay 1 :stable-length 1
                                          :multiplier 2 :max-delay 100)
              (range 1 11))))

  (is (= [1 2 4 8 16 nil nil nil nil nil]
         (map (progressive-retry-strategy :initial-delay 1 :stable-length 1
                                          :multiplier 2 :max-delay 1000
                                          :max-count 5)
              (range 1 11)))))

(defn make-dial-up []
  (let [state (atom {:good? true, :left 3, :data 1})]
    (fn []
      (let [{:keys [good? left data]} @state]
        (if (= left 0)
          (swap! state #(-> % (update-in [:good?] not)
                            (assoc :left (inc (rand-int 5)))))
          (swap! state update-in [:left] dec))
        (cond (> data 100) :eof
              good? (do (swap! state update-in [:data] inc) data)
              :else (throw (SocketTimeoutException. "pshhhh-ft-ft")))))))

(deftest retriable-test
  (is (thrown-with-msg? SocketTimeoutException #"pshhhh"
                        (doall (repeatedly 10 (make-dial-up))))
      "Dial-up is unreliable.")

  (let [dial-up (make-dial-up)]
    (is (thrown-with-msg? SocketTimeoutException #"pshhhh"
                          (doall (repeatedly 10 #(retriable {} (dial-up)))))
        "still fails with exception if unhandled")))

(deftest retry-test
  (is (= [1 2 3 4 5 6 7 8 9 10]
         (retry {:strategy (constant-retry-strategy 0), :log-fn (fn [& _])}
           (let [dial-up (make-dial-up)]
             (doall (repeatedly 10 #(retriable {} (dial-up)))))))
      "makes network robust")

  (testing "log-fn reports each failed attempt"
    (let [dial-up (make-dial-up)
          log (atom [])]
      (retry {:strategy (constant-retry-strategy 0)
              :log-fn (fn [e a delay] (swap! log conj {:e e, :a a :delay delay}))}
        (doall (repeatedly 10 #(retriable {} (dial-up)))))
      (is (every? (partial = 0) (map :delay @log)))
      (is (every? pos? (map :a @log)))
      (is (every? (partial instance? ExceptionInfo) (map :e @log)))))

  (let [dial-up (make-dial-up)
        before (System/currentTimeMillis)]
    (retry {:strategy (constant-retry-strategy 50), :log-fn (fn [& _])}
      (doall (repeatedly 11 #(retriable {} (dial-up))))
      (is (>= (System/currentTimeMillis) before)
          "waits before retrying")))

  (let [dial-up (make-dial-up)]
    (retry {:strategy (constant-retry-strategy 0), :log-fn (fn [& _])}
      (is (= (range 1 101)
             (doall (take-while #(not= % :eof)
                                (repeatedly #(retriable {} (dial-up))))))
          "will download all data in the end"))))

(deftest complicated-test
  ;; Fake function that returns a list of files, but fails the first three times.
  (let [cnt (atom 0)]
    (defn list-s3-files [& [reset?]]
      (if reset?
        (reset! cnt 0)
        (do (when (< @cnt 3)
              (swap! cnt inc)
              (throw (RuntimeException. "Failed to connect to S3.")))
            (range 10)))))

  (is (thrown-with-msg? RuntimeException #"Failed to connect to S3"
                        (list-s3-files)))
  (list-s3-files :reset)

  ;; Fake function that imitates downloading a file with 50/50 probability.
  (defn download-one-file [x]
    (if (> (rand) 0.5)
      x
      (throw (IOException. "Failed to download a file."))))

  (is (thrown-with-msg? IOException #"Failed to download a file."
                        (doall (repeatedly 100 #(download-one-file 0)))))

  ;; Let's wrap the previous function in retriable.
  (defn download-one-file-safe [x]
    (retriable {} (download-one-file x)))

  ;; A function that downloads all files.
  (defn download-all-files []
    (let [files (retriable {:catch [RuntimeException]
                            :tag ::list-files}
                  (list-s3-files))]
      (mapv download-one-file-safe files)))

  ;; Calling without retry will fail.
  (is (thrown? Exception (download-all-files)))
  (list-s3-files :reset)

  (is (thrown? IOException
               (retry {:strategy (constant-retry-strategy 0), :log-fn (fn [& _])
                       :selector ::list-files}
                 (download-all-files)))
      "We covered only ::list-files, files downloading will fail.")
  (list-s3-files :reset)

  (is (= (range 10)
         (retry {:strategy (constant-retry-strategy 0), :log-fn (fn [& _])
                 :selector (fn [ex] (or (instance? RuntimeException (:e (ex-data ex)))
                                        (instance? IOException (:e (ex-data ex)))))}
           (download-all-files)))
      "Successfully downloads all files.")
  (list-s3-files :reset)

  (is (= (range 10)
         (retry {:strategy (constant-retry-strategy 0), :log-fn (fn [& _])}
           (download-all-files)))
      "Not specifying a selector covers all retriable cases.")
  (list-s3-files :reset)

  (testing "multi-level retry contexts"
    (let [state (atom {:caught-by-outer 0, :caught-by-inner 0})]
      (retry
          {:strategy (constant-retry-strategy 0),
           :log-fn (fn [& _] (swap! state update-in [:caught-by-outer] inc))}
        (retry {:strategy (constant-retry-strategy 0),
                :log-fn (fn [& _] (swap! state update-in [:caught-by-inner] inc))
                :selector (fn [ex] (instance? IOException (:e (ex-data ex))))}
          (download-all-files)
          (is (pos? (:caught-by-outer @state)))
          (is (pos? (:caught-by-inner @state))))))))
