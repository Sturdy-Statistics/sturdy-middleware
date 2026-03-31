(ns sturdy.middleware.request-size
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn- bytes->human
  "Convert a byte count to a human-readable string (base-1024)."
  [^long bytes]
  (let [b (double bytes)]
    (cond
      (< b 1024)
      (format "%.0f B" b)

      (< b (* 1024 1024))
      (format "%.1f KB" (/ b 1024))

      (< b (* 1024 1024 1024))
      (format "%.1f MB" (/ b (* 1024 1024)))

      :else
      (format "%.1f GB" (/ b (* 1024 1024 1024))))))

(defn- parse-content-length
  "Parse Content-Length header value to a Long, or nil if missing/invalid."
  [s]
  (when (and s (not (string/blank? s)))
    (try
      (Long/parseLong (str s))
      (catch Exception _
        nil))))

(defn- default-too-large-body
  "Default HTML body for 413 responses. Users can override via *render-too-large*."
  [{:keys [_message _request-id _title _blurb]}]
  (str "<!doctype html>"
       "<html lang=\"en\">"
       "<head>"
       "  <title>Content too large</title>"
       "</head>"
       "<body>"
       "  <p>"
       "    The upload size exceeds our limits.  Please try again with a smaller upload."
       "  </p>"
       "</body>"
       "</html>"))

(def ^:dynamic *render-too-large*
  "Dynamic hook to render the response body when a request is rejected for size.

  Must return either:
  - a Ring response map, OR
  - a body (string / bytes / stream), in which case this middleware will wrap it
    into a Ring response.

  Rebind with `binding` (or `alter-var-root`) in your app to integrate with your
  site-wide error pages."
  (fn [{:keys [_request _max-upload-bytes] :as ctx}]
    ;; default: HTML body string
    (default-too-large-body ctx)))

(defn- default-length-required-body
  "Default HTML body for 411 responses. Users can override via *render-length-required*."
  [{:keys [_message _request-id _title _blurb]}]
  (str "<!doctype html>"
       "<html lang=\"en\">"
       "<head>"
       "  <title>Length Required</title>"
       "</head>"
       "<body>"
       "  <p>"
       "    Requests must have a content length header."
       "  </p>"
       "</body>"
       "</html>"))

(def ^:dynamic *render-length-required*
  "Dynamic hook to render the response body when a request is rejected for not having content length.

  Must return either:
  - a Ring response map, OR
  - a body (string / bytes / stream), in which case this middleware will wrap it
    into a Ring response.

  Rebind with `binding` (or `alter-var-root`) in your app to integrate with your
  site-wide error pages."
  (fn [{:keys [_request _max-upload-bytes] :as ctx}]
    ;; default: HTML body string
    (default-length-required-body ctx)))

(defn length-required-response
  [request max-upload-bytes]
  (let [msg "Requests must have a content length header."
        ctx {:code 411
             :title "Length Required"
             :blurb "Requests must have a content length header."
             :message msg
             :request-id (:request-id request)
             :request request
             :max-upload-bytes max-upload-bytes}
        rendered (*render-length-required* ctx)
        base (if (and (map? rendered) (contains? rendered :status))
               rendered
               (resp/response rendered))]
    (-> base
        (resp/status 411)
        (cond-> (nil? (resp/get-header base "Content-Type"))
          (resp/content-type "text/html; charset=utf-8"))
        (resp/header "Connection" "close"))))

(defn too-large-response
  [request max-upload-bytes]
  (let [msg (format
             "Your upload exceeds our maximum allowed upload size of %s.\nPlease try again with a smaller file."
             (bytes->human max-upload-bytes))
        ctx {:code 413
             :title "Content too large"
             :blurb "Upload failed."
             :message msg
             :request-id (:request-id request)
             :request request
             :max-upload-bytes max-upload-bytes}
        rendered (*render-too-large* ctx)
        ;; Allow render hook to return a full response map or just a body.
        base (if (and (map? rendered) (contains? rendered :status))
               rendered
               (resp/response rendered))]
    (-> base
        (resp/status 413)
        (cond-> (nil? (resp/get-header base "Content-Type"))
          (resp/content-type "text/html; charset=utf-8"))
        (resp/header "Connection" "close"))))

;; (defn wrap-max-request-size
;;   [handler max-upload-bytes]
;;   (fn [request]
;;     (let [len-str (or (get-in request [:headers :content-length])
;;                       (get-in request [:headers "content-length"]))
;;           len     (some-> len-str parse-content-length)]
;;       (if (and len (> len max-upload-bytes))
;;         (too-large-response request max-upload-bytes)
;;         (handler request)))))

(defn wrap-max-request-size
  [handler max-upload-bytes]
  (fn [request]
    (let [len-str  (resp/get-header request "Content-Length")
          len      (some-> len-str parse-content-length)
          tx-enc   (or (resp/get-header request "Transfer-Encoding") "")
          chunked? (-> tx-enc string/lower-case (string/includes? "chunked"))
          ;; A client intends to send a body if it provides either length or chunked encoding
          intends-body? (or (some? len-str) (not (string/blank? tx-enc)))]

      (cond
        ;; 1. Block chunked requests outright to prevent infinite streaming OOMs
        chunked?
        (length-required-response request max-upload-bytes)

        ;; 2. Block if we know it's too big
        (and len (> len max-upload-bytes))
        (too-large-response request max-upload-bytes)

        ;; 3. Block if it implies a body but lacks a parsable length (Fail Closed)
        (and intends-body? (nil? len))
        (length-required-response request max-upload-bytes)

        ;; Safe! (Either no body intended, or valid length within limits)
        :else
        (handler request)))))
