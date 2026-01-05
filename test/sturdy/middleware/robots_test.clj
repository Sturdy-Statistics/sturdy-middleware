(ns sturdy.middleware.robots-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ring.util.response :as resp]
   [sturdy.middleware.robots :as r]))

(deftest with-noindex-behavior
  (testing "nil-safe"
    (is (nil? (r/with-noindex nil))))

  (testing "sets X-Robots-Tag"
    (let [r (r/with-noindex (resp/response "ok"))]
      (is (= "noindex, noimageindex, noarchive, nosnippet"
             (get-in r [:headers "X-Robots-Tag"]))))))

(deftest with-noindex-adds-and-is-idempotent
  (let [r0 (-> (resp/response "ok")
               (assoc-in [:headers "X-Robots-Tag"] "noarchive"))
        r1 (r/with-noindex r0)
        r2 (r/with-noindex r1)]
    (testing "contains directives (no duplicates)"
      (is (= (get-in r1 [:headers "X-Robots-Tag"])
             (get-in r2 [:headers "X-Robots-Tag"])))
      (is (= "noarchive, noindex, noimageindex, nosnippet"
             (get-in r1 [:headers "X-Robots-Tag"]))))))
