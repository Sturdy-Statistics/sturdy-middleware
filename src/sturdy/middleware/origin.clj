(ns sturdy.middleware.origin
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]
   [sturdy.middleware.util :as u]))

(set! *warn-on-reflection* true)

(defn- default-port? [scheme port]
  (or (nil? port)
      (and (= "http" scheme) (= 80 port))
      (and (= "https" scheme) (= 443 port))))

(defn- req-host
  "Prefer Host header; else combine :server-name and :server-port."
  [req]
  (or (get-in req [:headers "host"])
      (let [name   (:server-name req)
            port   (:server-port req)
            scheme (name (:scheme req))]

        (if (default-port? scheme port)
          name
          (str name ":" port)))))

(defn- req-scheme
  "Best-effort request scheme. If behind a trusted proxy, allow x-forwarded-proto to override."
  [req]
  (let [scheme (some-> (:scheme req) name)
        xfwd   (some-> (get-in req [:headers "x-forwarded-proto"])
                       str
                       string/trim
                       string/lower-case)]
    (cond
      (= xfwd "https") "https"
      (= xfwd "http")  "http"
      :else            (or scheme "http"))))

(defn- expected-origin [req]
  (str (req-scheme req) "://" (req-host req)))

(defn- same-origin?
  "Returns true if the Origin header matches the expected origin.
   If allow-missing-origin? is true, missing/blank Origin passes."
  [req allow-missing-origin?]
  (let [origin (some-> (get-in req [:headers "origin"]) str string/trim)
        exp    (expected-origin req)]
    (if (string/blank? origin)
      allow-missing-origin?
      (= origin exp))))

(defn- forbidden []
  (-> (resp/response "Forbidden (Not Same-Origin)")
      (resp/content-type "text/plain; charset=utf-8")
      (resp/status 403)))

(defn wrap-require-same-origin-strict
  "Require same-origin on state-changing requests (POST/PUT/PATCH/DELETE).
   Strict mode: missing Origin => forbidden (for mutable requests)."
  [handler]
  (fn [req]
    (if (u/method-mutable? req)
      (if (same-origin? req false)
        (handler req)
        (forbidden))
      (handler req))))

(defn wrap-require-same-origin
  "Require same-origin on state-changing requests (POST/PUT/PATCH/DELETE).
   Tolerant mode: missing Origin => allowed (for mutable requests)."
  [handler]
  (fn [req]
    (if (u/method-mutable? req)
      (if (same-origin? req true)
        (handler req)
        (forbidden))
      (handler req))))
