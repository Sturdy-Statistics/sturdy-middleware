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

(deftest same-origin-falls-back-to-server-name-and-port
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (testing "POST passes without Host when Origin matches server-name and non-default port"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :server-name "example.com"
                               :server-port 8443
                               :headers {"origin" "https://example.com:8443"}})))))))

(deftest same-origin-normalizes-default-host-ports
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (testing "HTTPS Host may include default port when Origin omits it"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com:443"
                                         "origin" "https://example.com"}})))))

    (testing "HTTP Host may include default port when Origin omits it"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :http
                               :headers {"host" "example.com:80"
                                         "origin" "http://example.com"}})))))

    (testing "Origin may include HTTPS default port when Host omits it"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://example.com:443"}})))))

    (testing "Origin may include HTTP default port when Host omits it"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :http
                               :headers {"host" "example.com"
                                         "origin" "http://example.com:80"}})))))

    (testing "HTTPS non-default Host port still must match Origin"
      (is (= 403 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com:8443"
                                         "origin" "https://example.com"}})))))

    (testing "HTTPS non-default Origin port still must match Host"
      (is (= 403 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://example.com:8443"}})))))))

(deftest same-origin-compares-hostnames-case-insensitively
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (testing "mixed-case Host matches a lowercase Origin"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "EXAMPLE.com"
                                         "origin" "https://example.com"}})))))

    (testing "mixed-case Origin matches a lowercase Host"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://ExAmPlE.CoM"}})))))

    (testing "case-insensitive comparison retains default-port normalization"
      (is (= 200 (:status (mw {:request-method :post
                               :scheme :https
                               :headers {"host" "EXAMPLE.com:443"
                                         "origin" "https://example.COM"}})))))))

(deftest proxy-scheme
  (let [mw (o/wrap-require-same-origin-strict ok-handler)]
    (is (= 200 (:status (mw {:request-method :post
                             :scheme :http
                             :headers {"host" "example.com"
                                       "x-forwarded-proto" "https"
                                       "origin" "https://example.com"}}))))))
