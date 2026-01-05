(ns sturdy.middleware.request-id
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(def ^:private id-re
  #"(?i)^[A-Za-z0-9._-]{8,128}$") ; allow-list, length-capped

(defn- safe-id [x]
  (let [s (some-> x str string/trim)]
    (when-not (string/blank? s)
      (let [s (subs s 0 (min 128 (count s)))]
        (when (re-matches id-re s) s)))))

(def ^:private traceparent-re
  ;; W3C traceparent: version-traceid-spanid-flags
  #"(?i)^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$")

(defn- parse-traceparent [v]
  (when-let [v' (some-> v str string/trim)]
   (when-let [[_ tid] (re-matches traceparent-re v')]
     tid)))

(defn- incoming-id
  "Return the first trusted incoming id found in headers."
  [headers header-names]
  (some (fn [name]
          (when-let [raw (get headers name)]
            (case (string/lower-case name)
              "traceparent" (parse-traceparent raw)
              (safe-id raw))))
        header-names))

(defn wrap-request-id
  "Attach a request id to req/resp.

   Options:
   :header-names   vector of header names to trust (default common ones)
   :response-name  header name to set on the response (default \"X-Request-Id\")
   :id-fn          0-arg fn to generate an id (default random UUID)

   Notes:
   - If a trusted incoming header is present and passes validation, it is used.
   - If \"traceparent\" is trusted, the W3C trace-id (32 hex chars) is used."
  ([handler] (wrap-request-id handler {}))
  ([handler {:keys [header-names response-name id-fn]
             :or   {header-names ["x-request-id" "x-correlation-id" "traceparent"]
                    response-name "X-Request-Id"
                    id-fn        #(str (random-uuid))}}]
   (fn [req]
     (let [incoming (incoming-id (:headers req) header-names)
           rid      (or (safe-id incoming)
                        (safe-id (id-fn))
                        (str (random-uuid)))
           req'     (assoc req :request-id rid)
           resp     (handler req')]
       ;; nil-preserving
       (when resp
         (update resp :headers #(assoc (or % {}) response-name rid)))))))
