(ns sturdy.middleware.util
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn method-mutable? [request]
  (let [m (:request-method request)]
    (boolean
     (#{:post :put :patch :delete} m))))

(defn status-error? [response]
  (let [s (:status response)]
    (boolean
     (and (number? s) (>= s 400)))))

(defn add-header-token
  "Ensure a comma-separated HTTP header contains TOKEN once.

   - Comparison is case-insensitive
   - Output formatting is canonical (\", \")
   - Token order is preserved
   - If the value is \"*\", it is returned unchanged (unless disabled)

   Options:
   - :preserve-star? (default true)"
  ([header-val token]
   (add-header-token header-val token {:preserve-star? true}))
  ([header-val token {:keys [preserve-star?] :or {preserve-star? true}}]
   (let [raw (some-> header-val string/trim)]
     (cond
       (and preserve-star? (= raw "*"))
       "*"

       (or (nil? raw) (string/blank? raw))
       token

       :else
       (let [parts    (->> (string/split raw #",")
                           (map string/trim)
                           (remove string/blank?))
             lc-set   (->> parts (map string/lower-case) set)
             token-lc (string/lower-case token)]
         (if (contains? lc-set token-lc)
           (string/join ", " parts)
           (string/join ", " (conj (vec parts) token))))))))

(defn- normalize-ip-ish [s]
  (when-let [s (some-> s string/trim not-empty)]
    (let [s (string/lower-case s)]
      (when-not (= s "unknown")
        (let [s (string/replace s #"^\[(.*)\]$" "$1")]
          ;; strip :port only for IPv4:port
          (if (re-matches #"\d+\.\d+\.\d+\.\d+:\d+" s)
            (first (string/split s #":" 2))
            s))))))

(defn- parse-xff-chain [xff]
  (->> (string/split (or xff "") #",")
       (map normalize-ip-ish)
       (remove nil?)
       vec))

(defn request-ip
  "Cloudflare -> nginx -> Ring.
   Returns {:ip ... :source ... :xff-chain [...]}
   If trust-proxies? is false, ignores headers and uses :remote-addr.
   If trust-x-real-ip? is true, prioritizes the X-Real-IP header set by Nginx."
  ([req]
   (request-ip req {:trust-proxies? true :trust-x-real-ip? true}))
  ([req {:keys [trust-proxies? trust-x-real-ip?]
         :or {trust-proxies? true
              trust-x-real-ip? true}}]
   (let [hdrs   (:headers req)
         remote (normalize-ip-ish (:remote-addr req))
         x-real (normalize-ip-ish (get hdrs "x-real-ip"))
         cf     (normalize-ip-ish (get hdrs "cf-connecting-ip"))
         xff    (parse-xff-chain (get hdrs "x-forwarded-for"))
         xff0   (first xff)]
     (if (not trust-proxies?)
       {:ip remote :source :remote-addr :xff-chain xff}
       (cond
         ;; 1. Absolute highest priority: Nginx's sanitized X-Real-IP
         (and trust-x-real-ip? x-real)
         {:ip x-real :source :x-real-ip :xff-chain xff}

         ;; 2. Legacy fallback: Direct Cloudflare header (if not using X-Real-IP)
         cf
         {:ip cf     :source :cf-connecting-ip :xff-chain xff}

         ;; 3. Generic proxy fallback
         xff0
         {:ip xff0   :source :x-forwarded-for  :xff-chain xff}

         ;; 4. Direct connection
         :else
         {:ip remote :source :remote-addr :xff-chain xff})))))
