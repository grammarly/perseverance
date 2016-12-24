(ns perseverance.core
  "Flexible retry utility for unreliable operations."
  (:import java.io.IOException
           clojure.lang.ExceptionInfo))

(def ^:dynamic *retry-contexts*
  "Dynamic variable that contains a stack of retry contexts. Each context is a
  map that consists of a strategy, a map of error tokens to strategies, and an
  optional selector."
  ())

;; Raise site

(defn- maybe-handle-exception
  "If `og-ex`, the exception caught, is the one that might be retried (based on
  `opts`), and the retry context is set for this exception, handle the exception
  and produce an according delay."
  [og-ex attempt error-token opts]
  (let [wrapped-ex (if-let [ex-wrapper (:ex-wrapper opts)]
                     (ex-wrapper og-ex)
                     (ex-info "Retriable code failed." {:tag (:tag opts)
                                                        :e og-ex
                                                        :token error-token}))
        {strat-map :strategies-map, log-fn :log-fn, :as ctx}
        (some #(if-let [selector (:selector %)]
                 ;; If the retry context has a selector, check
                 ;; if the selector matches the exception.
                 ;; Otherwise, the context always matches.
                 (when (selector wrapped-ex) %)
                 %)
              *retry-contexts*)]
    ;; If no context matched the error, throw it further.
    (when-not ctx
      (throw og-ex))
    (when-not (get @strat-map error-token)
      (swap! strat-map assoc error-token (:strategy ctx)))
    (let [strategy (get @strat-map error-token)
          delay (strategy attempt)]
      (when-not delay ; Strategy said we should stop retrying.
        (throw wrapped-ex))
      (log-fn wrapped-ex attempt delay)
      (Thread/sleep delay)
      error-token)))

(defmacro retriable
  "Signals to `retry` macro when exception is raised by the `body` so that it
  can be retried.

  `opts` can contain the following keys:

  - `:catch` - list of exception classes that are handled. Defaults to
  `[java.io.Exception]`.

  - `:tag` - a tag to attach to the raised exception. Later `retry` can filter
  by this tag which errors it wants to retry.

  - `:ex-wrapper` - a function that is called on the originally raised exception
  and returns a wrapped exception object. This can be used for even more
  specific control by `retry`. If this option specified, `:tag` is ignored."
  {:style/indent 1}
  [opts & body]
  (let [error-token (gensym "error-token")
        attempt (gensym "attempt")]
    `(let [~error-token (Object.)]
       (loop [~attempt 1]
         (let [result#
               (try (do ~@body)
                    ~@(map (fn [etype]
                             `(catch ~etype ex#
                                (#'maybe-handle-exception
                                 ex# ~attempt ~error-token
                                 ~(select-keys opts [:tag :ex-wrapper]))))
                           (or (:catch opts) [IOException])))]
           (if (identical? result# ~error-token)
             (recur (inc ~attempt))
             result#))))))

;; Handle site

(defn constant-retry-strategy
  "Create a retry strategy that returns same `delay` for each attempt. If
  `max-count` is specified, the strategy returns nil after so many attempts."
  ([delay]
   (constant-retry-strategy delay nil))
  ([delay max-count]
   (fn [attempt]
     (when-not (and max-count (> attempt max-count))
       delay))))

(defn progressive-retry-strategy
  "Create a retry strategy that returns a raising delay. First `stable-length`
  attempts have `initial-delay`, each next attempt the delay is increased by
  `multiplier` times. Delay cannot be bigger than `max-delay`. If `max-count` is
  specified and reached, nil is returned."
  [& {:keys [initial-delay stable-length multiplier max-delay max-count]
      :or {initial-delay 500, stable-length 3, multiplier 2, max-delay 60000}}]
  (fn [attempt]
    (when-not (and max-count (> attempt max-count))
      (if (<= attempt stable-length)
        initial-delay
        (* initial-delay (int (Math/pow multiplier (- attempt stable-length))))))))

(defn- default-log-fn
  "Prints a message to stdout that an error happened and going to be retried."
  [wrapped-ex attempt delay]
  (println (format "%s, retrying in %.1f seconds..."
                   (:e (ex-data wrapped-ex))
                   (/ delay 1000.0))))

(defmacro retry
  "Wraps `body` in a context that will cause all matching `retriable` blocks to
  retry when an error happens.

  - `strategy` specifies how long to wait between retries (see
  `constant-retry-strategy` and `progressive-retry-strategy`). By default, the
  default progressive strategy is used.

  - `selector` defines whether this retry context catches a particular failure.
  Selector can either be a keyword (then it is matched against a tag provided by
  `retriable`) or a predicate on a wrapped exception.

  - `log-fn` is a function of the wrapped exception, attempt, and delay. It is
  called every time the retry happens. The default `log-fn` prints the message
  to standard output."
  {:style/indent 1}
  [{:keys [strategy selector log-fn]} & body]
  `(binding [*retry-contexts*
             (cons {:strategy ~(or strategy `(progressive-retry-strategy))
                    :strategies-map (atom {})
                    :selector ~(if (keyword? selector)
                                 `(fn [ex#] (= (:tag (ex-data ex#)) ~selector))
                                 selector)
                    :log-fn ~(or log-fn `#'default-log-fn)}
                   *retry-contexts*)]
     ~@body))
