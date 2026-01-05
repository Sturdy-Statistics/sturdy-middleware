(ns sturdy.middleware.cache-control-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ring.util.response :as resp]
   [sturdy.middleware.cache-control :as c]))

(deftest add-vary-token-behavior
  (testing "nil / blank inputs"
    (is (= "Cookie" (c/add-vary-token nil "Cookie")))
    (is (= "Cookie" (c/add-vary-token "" "Cookie")))
    (is (= "Cookie" (c/add-vary-token "   " "Cookie"))))

  (testing "idempotent, case-insensitive"
    (is (= "cookie" (c/add-vary-token "cookie" "Cookie")))
    (is (= "Foo, cookie" (c/add-vary-token "Foo, cookie" "Cookie")))
    (is (= "Foo, cookie" (c/add-vary-token "Foo,cookie" "Cookie"))))

  (testing "appends with canonical separator when missing"
    (is (= "foo, Cookie" (c/add-vary-token "foo" "Cookie")))
    (is (= "foo, bar, Cookie" (c/add-vary-token "foo, bar" "Cookie"))))

  (testing "trims leading/trailing whitespace of the header value"
    (is (= "foo, Cookie" (c/add-vary-token "  foo  " "Cookie")))
    (is (= "foo, bar, Cookie" (c/add-vary-token " foo,  bar " "Cookie"))))

  (testing "keeps star"
    (is (= "*" (c/add-vary-token "*" "Cookie"))))

  (testing "normalize existing value"
    (is (= "foo, bar" (c/add-vary-token "foo,  bar" "bar")))))

(deftest with-nostore-defaults-and-idempotent
  (let [r1 (-> (resp/response "ok") c/with-nostore)
        r2 (-> r1 c/with-nostore)]
    (testing "Cache-Control"
      (is (= "private, no-store, max-age=0, must-revalidate"
             (get-in r1 [:headers "Cache-Control"])))
      (is (= (get-in r1 [:headers "Cache-Control"])
             (get-in r2 [:headers "Cache-Control"]))))

    (testing "Pragma"
      (is (= "no-cache" (get-in r1 [:headers "Pragma"])))
      (is (= (get-in r1 [:headers "Pragma"])
             (get-in r2 [:headers "Pragma"]))))

    (testing "Expires (HTTP date in the past)"
      (is (string? (get-in r1 [:headers "Expires"])))
      (is (= (get-in r1 [:headers "Expires"])
             (get-in r2 [:headers "Expires"]))))

    (testing "Surrogate-Control"
      (is (= "no-store" (get-in r1 [:headers "Surrogate-Control"])))
      (is (= (get-in r1 [:headers "Surrogate-Control"])
             (get-in r2 [:headers "Surrogate-Control"]))))

    (testing "Referrer-Policy"
      (is (= "no-referrer" (get-in r1 [:headers "Referrer-Policy"])))
      (is (= (get-in r1 [:headers "Referrer-Policy"])
             (get-in r2 [:headers "Referrer-Policy"]))))))

(deftest wrap-nostore-applies-and-preserves-nil
  (let [handler (fn [_] (resp/response "ok"))
        wrapped (c/wrap-nostore handler)
        handler-nil (fn [_] nil)
        wrapped-nil (c/wrap-nostore handler-nil)]
    (is (= "private, no-store, max-age=0, must-revalidate"
           (get-in (wrapped {}) [:headers "Cache-Control"])))
    (is (nil? (wrapped-nil {})))))

(deftest wrap-nostore-when-conditional
  (let [handler (fn [_] (resp/response "ok"))
        always  (c/wrap-nostore-when handler (fn [_ _] true))
        never   (c/wrap-nostore-when handler (fn [_ _] false))]
    (is (= "private, no-store, max-age=0, must-revalidate"
           (get-in (always {}) [:headers "Cache-Control"])))
    (is (nil? (get-in (never {}) [:headers "Cache-Control"])))))

(deftest with-vary-cookie-behavior
  (testing "nil-safe"
    (is (nil? (c/with-vary-cookie nil))))

  (testing "adds Cookie and is idempotent"
    (let [r0 (resp/response "ok")
          r1 (c/with-vary-cookie r0)
          r2 (c/with-vary-cookie r1)]
      (is (= "Cookie" (get-in r1 [:headers "Vary"])))
      (is (= (get-in r1 [:headers "Vary"])
             (get-in r2 [:headers "Vary"])))))

  (testing "preserves star"
    (let [r0 (assoc-in (resp/response "ok") [:headers "Vary"] "*")
          r1 (c/with-vary-cookie r0)]
      (is (= "*" (get-in r1 [:headers "Vary"]))))))

(def ^:private cc
  "private, no-store, max-age=0, must-revalidate")

(deftest wrap-nostore-on-errors-behavior
  (let [base-handler (fn [_] (resp/response "ok"))
        handler      (c/wrap-nostore-on-errors base-handler)]

    (testing "GET 200 -> unchanged (no Cache-Control set by this middleware)"
      (let [resp (handler {:request-method :get})]
        (is (nil? (get-in resp [:headers "Cache-Control"])))))

    (testing "GET 500 -> nostore applied"
      (let [base-handler (fn [_] (assoc (resp/response "ok") :status 500))
            handler      (c/wrap-nostore-on-errors base-handler)
            resp         (handler {:request-method :get})]
        (is (= cc (get-in resp [:headers "Cache-Control"])))))

    (testing "POST 200 -> nostore applied (mutable method)"
      (let [resp (handler {:request-method :post})]
        (is (= cc (get-in resp [:headers "Cache-Control"])))))

    (testing "handler may return nil"
      (let [handler (c/wrap-nostore-on-errors (fn [_] nil))]
        (is (nil? (handler {:request-method :get})))
        (is (nil? (handler {:request-method :post})))))))
