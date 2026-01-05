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
