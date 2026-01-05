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
