(ns perseverance.t-core
  (:require [clojure.test :refer :all]
            [perseverance.core :refer :all])
  (:import java.net.SocketTimeoutException
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
          (swap! state #(-> % (update :good? not)
                            (assoc :left (inc (rand-int 5)))))
          (swap! state update :left dec))
        (cond (> data 100) :eof
              good? (do (swap! state update :data inc) data)
              :else (throw (SocketTimeoutException. "pshhhh-ft-ft")))))))
(def d (make-dial-up))


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

  (let [dial-up (make-dial-up)
        log (atom [])]
    (retry {:strategy (constant-retry-strategy 0)
            :log-fn (fn [e delay] (swap! log conj {:e e :delay delay}))}
           (doall (repeatedly 10 #(retriable {} (dial-up)))))
    (is (and (every? (partial = 0) (map :delay @log))
             (every? (partial instance? ExceptionInfo) (map :e @log)))
        "log-fn reports each failed attempt"))

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
