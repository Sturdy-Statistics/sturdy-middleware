(ns sturdy.middleware.origin-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.middleware.origin :as o]))

(def ok-handler (fn [_] {:status 200 :headers {} :body "ok"}))

(deftest require-same-origin-tolerant
  (let [mw (o/wrap-require-same-origin ok-handler)]
    (testing "GET passes without Origin"
      (is (= 200 (:status (mw {:request-method :get
                               :scheme :https
                               :headers {"host" "example.com"}})))))

    (testing "POST passes when Origin missing (tolerant)"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"}})))))

    (testing "POST forbidden when Origin mismatches"
      (is (= 403 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://evil.com"}})))))

    (testing "POST passes when Origin matches"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://example.com"}})))))))

(deftest require-same-origin-strict
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (testing "POST forbidden when Origin missing (strict)"
      (is (= 403 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"}})))))

    (testing "GET still passes without Origin"
      (is (= 200 (:status (mw {:request-method :get
                               :scheme :https
                               :headers {"host" "example.com"}})))))))

(deftest proxy-scheme
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (is (= 200 (:status (mw {:request-method :post
                             :scheme :http
                             :headers {"host" "example.com"
                                       "x-forwarded-proto" "https"
                                       "origin" "https://example.com"}}))))))
