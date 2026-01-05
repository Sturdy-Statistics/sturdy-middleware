(ns sturdy.middleware.cache-control
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]
   [sturdy.middleware.util :as u]))

(set! *warn-on-reflection* true)

(def ^:private expires-epoch
  "HTTP-date in the past. Used for conservative no-store behavior."
  "Thu, 01 Jan 1970 00:00:00 GMT")

;; With session-cookie auth, many responses are user-specific:
;; dashboards, “My account,” etc. If any of those were cached and
;; reused for another user, you’d leak data. These headers guard you
;; against:
;;
;; 1. Browser cache showing old/private content: (e.g., using
;;    Back/Forward after logout). no-store is the strongest
;;    protection.
;;
;; 2. Intermediary caches (CDN/proxy) mixing users: private forbids
;;    shared caches. Vary: Cookie ensures that if you ever make
;;    something cacheable, caches won’t coalesce across users.
;;
;; 3. Redirects that set/clear cookies: Caches sometimes mishandle
;;    redirects; no-store is a safe default when you’re mutating auth
;;    state.

(defn with-nostore
  "Add headers that prevent caching of sensitive or user-specific responses.
   Call this LAST in your threading pipeline so it wins over any earlier headers."
  [response]
  (-> response
      ;; No caching anywhere (browser, proxy, CDN).
      (resp/header "Cache-Control" "private, no-store, max-age=0, must-revalidate")
      ;; Legacy HTTP/1.0 caches.
      (resp/header "Pragma" "no-cache")
      ;; Conservative HTTP-date in the past.
      (resp/header "Expires" expires-epoch)
      ;; Optional: common CDN convention (harmless if ignored).
      (resp/header "Surrogate-Control" "no-store")
      ;; Avoid leaking URL path/query to other origins.
      (resp/header "Referrer-Policy" "no-referrer")))

(defn wrap-nostore [handler]
  (fn [request]
    (when-let [response (handler request)]
      (with-nostore response))))

(defn wrap-nostore-when
  "Apply no-store only when (pred req resp) is truthy."
  ([handler] (wrap-nostore-when handler (constantly true)))
  ([handler pred]
   (fn [request]
     (when-let [response (handler request)]
       (if (pred request response) (with-nostore response) response)))))

(defn wrap-nostore-on-errors [handler]
  (letfn [(pred [request response]
            (or (u/status-error? response)
                (u/method-mutable? request)))]
    (wrap-nostore-when handler pred)))

(defn add-vary-token
  "Ensure Vary contains TOKEN once (case-insensitive)."
  [vary-val token]
  (u/add-header-token vary-val token))

;; Practical note: Vary: Cookie often kills cache hit rates because
;; cookies differ per user; CDNs may effectively stop caching those
;; routes. That’s fine for authenticated pages, but for public pages
;; it’s better to strip cookies at the edge than to vary on them.

(defn with-vary-cookie [response]
  (when response
    (resp/header response
                 "Vary"
                 (add-vary-token (get-in response [:headers "Vary"]) "Cookie"))))

(defn wrap-with-vary-cookie
  "Middleware that adds 'Vary: Cookie' to the response header."
  [handler]
  (fn [req]
    (let [response (handler req)]
      (with-vary-cookie response))))
