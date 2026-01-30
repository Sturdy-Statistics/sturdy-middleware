(ns sturdy.middleware.util-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.middleware.util :as u]))

(deftest method-mutable-basic
  (testing "mutable methods"
    (doseq [m [:post :put :patch :delete]]
      (is (true? (u/method-mutable? {:request-method m})))))

  (testing "non-mutable or missing"
    (doseq [m [:get :head :options :trace nil]]
      (is (false? (u/method-mutable? {:request-method m}))))
    (is (false? (u/method-mutable? {})))))

(deftest status-error-basic
  (testing "errors"
    (doseq [s [400 401 403 404 500 503]]
      (is (true? (u/status-error? {:status s})))))

  (testing "non-errors"
    (doseq [s [100 200 204 301 302 399]]
      (is (false? (u/status-error? {:status s})))))

  (testing "missing / non-numeric"
    (is (false? (u/status-error? {})))
    (is (false? (u/status-error? {:status nil})))
    (is (false? (u/status-error? {:status "500"})))))

(deftest add-header-token-core
  (testing "preserves star by default"
    (is (= "*" (u/add-header-token "*" "Cookie"))))

  (testing "can disable star preservation"
    ;; When star preservation is disabled, treat \"*\" like a normal token list.
    ;; That means adding 'Cookie' yields \"*, Cookie\" (canonical formatting).
    (is (= "*, Cookie"
           (u/add-header-token "*" "Cookie" {:preserve-star? false}))))

  (testing "blank/nil -> token"
    (is (= "Cookie" (u/add-header-token nil "Cookie")))
    (is (= "Cookie" (u/add-header-token "" "Cookie")))
    (is (= "Cookie" (u/add-header-token "   " "Cookie"))))

  (testing "append at end, preserve order, canonical formatting"
    (is (= "foo, Cookie" (u/add-header-token "foo" "Cookie")))
    (is (= "foo, bar, Cookie" (u/add-header-token " foo,  bar " "Cookie"))))

  (testing "case-insensitive idempotence + normalization"
    (is (= "Foo, cookie" (u/add-header-token "Foo,cookie" "Cookie")))
    (is (= "foo, bar" (u/add-header-token "foo,  bar" "bar")))))

(deftest normalize-ip-ish-test
  (testing "nil/blank/unknown"
    (is (nil? (#'u/normalize-ip-ish nil)))
    (is (nil? (#'u/normalize-ip-ish "")))
    (is (nil? (#'u/normalize-ip-ish "   ")))
    (is (nil? (#'u/normalize-ip-ish "unknown")))
    (is (nil? (#'u/normalize-ip-ish " UnKnOwN "))))
  (testing "basic trimming/lowercasing"
    (is (= "1.2.3.4" (#'u/normalize-ip-ish " 1.2.3.4 "))))
  (testing "ipv4 with port"
    (is (= "1.2.3.4" (#'u/normalize-ip-ish "1.2.3.4:1234"))))
  (testing "ipv6 bracket form"
    (is (= "2001:db8::1" (#'u/normalize-ip-ish "[2001:db8::1]"))))
  (testing "does not strip ipv6 colons"
    (is (= "2001:db8::1" (#'u/normalize-ip-ish "2001:db8::1")))))

(deftest parse-xff-chain-test
  (is (= [] (#'u/parse-xff-chain nil)))
  (is (= [] (#'u/parse-xff-chain "")))
  (is (= ["1.1.1.1"] (#'u/parse-xff-chain " 1.1.1.1 ")))
  (is (= ["1.1.1.1" "2.2.2.2"]
         (#'u/parse-xff-chain "1.1.1.1, 2.2.2.2")))
  (is (= ["1.1.1.1" "2.2.2.2"]
         (#'u/parse-xff-chain "unknown, 1.1.1.1, , 2.2.2.2"))))

(deftest request-ip-test
  (testing "trust-proxies? false uses remote-addr"
    (is (= {:ip "9.9.9.9" :source :remote-addr :xff-chain ["1.1.1.1"]}
           (u/request-ip {:remote-addr "9.9.9.9"
                          :headers {"x-forwarded-for" "1.1.1.1"}}
                         {:trust-proxies? false}))))

  (testing "cf-connecting-ip wins when trust-proxies? true"
    (is (= {:ip "1.1.1.1" :source :cf-connecting-ip :xff-chain ["2.2.2.2" "3.3.3.3"]}
           (u/request-ip {:remote-addr "9.9.9.9"
                          :headers {"cf-connecting-ip" "1.1.1.1"
                                    "x-forwarded-for" "2.2.2.2, 3.3.3.3"}}))))

  (testing "falls back to xff left-most"
    (is (= {:ip "2.2.2.2" :source :x-forwarded-for :xff-chain ["2.2.2.2" "3.3.3.3"]}
           (u/request-ip {:remote-addr "9.9.9.9"
                          :headers {"x-forwarded-for" "2.2.2.2, 3.3.3.3"}}))))

  (testing "falls back to remote-addr if no trusted headers"
    (is (= {:ip "9.9.9.9" :source :remote-addr :xff-chain []}
           (u/request-ip {:remote-addr "9.9.9.9" :headers {}})))))
