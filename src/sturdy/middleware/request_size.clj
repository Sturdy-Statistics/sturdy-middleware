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
        ;; content-type only if the hook didn’t set one
        (cond-> (nil? (get-in base [:headers "Content-Type"]))
          (resp/content-type "text/html; charset=utf-8"))
        (resp/header "Connection" "close"))))

(defn wrap-max-request-size
  [handler max-upload-bytes]
  (fn [request]
    (let [len-str (or (get-in request [:headers :content-length])
                      (get-in request [:headers "content-length"]))
          len     (some-> len-str parse-content-length)]
      (if (and len (> len max-upload-bytes))
        (too-large-response request max-upload-bytes)
        (handler request)))))
