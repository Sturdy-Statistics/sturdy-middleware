(ns sturdy.middleware.origin
  (:require
   [clojure.string :as string]
   [ring.util.response :as resp]
   [sturdy.middleware.util :as u]))

(set! *warn-on-reflection* true)

(defn- default-port?
  "Returns true when `port` is absent or the default for `scheme`."
  [scheme port]
  (or (nil? port)
      (and (= "http" scheme) (= 80 port))
      (and (= "https" scheme) (= 443 port))))

(defn- default-port
  "Returns the default origin port for supported HTTP schemes."
  [scheme]
  (case scheme
    "http" 80
    "https" 443
    nil))

(defn- single-colon?
  "True when `s` has one colon, which marks a host:port form for non-IPv6 hosts."
  [s]
  (= 1 (count (filter #(= \: %) s))))

(defn- normalize-host-default-port
  "Lowercases a Host-style value and removes an explicit default port for `scheme`."
  [host scheme]
  (let [host (string/lower-case host)]
    (if-let [port (default-port scheme)]
      (let [suffix (str ":" port)]
        (cond
          ;; IPv6 address
          (string/starts-with? host "[")
          (let [close-bracket (string/index-of host "]")]
            (if (and close-bracket
                     (= suffix (subs host (inc close-bracket))))
              (subs host 0 (inc close-bracket))
              host))

          ;; domain name
          (and (single-colon? host) (string/ends-with? host suffix))
          (subs host 0 (- (count host) (count suffix)))

          :else
          host))
      host)))

(defn- normalize-origin-default-port
  "Removes an explicit default port from an http/https Origin value."
  [origin]
  (if-let [[_ scheme host] (re-matches #"(https?)://(.+)" origin)]
    (str scheme "://" (normalize-host-default-port host scheme))
    origin))

(defn- req-host
  "Prefer Host header; else combine :server-name and :server-port."
  [req scheme]
  (or (some-> (get-in req [:headers "host"])
              (normalize-host-default-port scheme))
      (let [server-name (:server-name req)
            port        (:server-port req)
            host        (if (default-port? scheme port)
                          server-name
                          (str server-name ":" port))]
        (normalize-host-default-port host scheme))))

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

(defn- expected-origin
  "Builds the normalized origin expected for this request."
  [req]
  (let [scheme (req-scheme req)]
    (str scheme "://" (req-host req scheme))))

(defn- same-origin?
  "Returns true if the Origin header matches the expected origin.
   If allow-missing-origin? is true, missing/blank Origin passes."
  [req allow-missing-origin?]
  (let [origin (some-> (get-in req [:headers "origin"])
                       str
                       string/trim
                       normalize-origin-default-port)
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
