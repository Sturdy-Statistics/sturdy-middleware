(ns sturdy.middleware.request-id-test
  (:require
   [clojure.test :refer [deftest is]]
   [sturdy.middleware.request-id :as rid]))

(deftest wrap-request-id-uses-incoming-x-request-id
  (let [handler (fn [req] {:status 200 :headers {} :body (:request-id req)})
        mw      (rid/wrap-request-id handler)
        resp    (mw {:headers {"x-request-id" "abcDEF_12-34"}})]
    (is (= 200 (:status resp)))
    (is (= "abcDEF_12-34" (:body resp)))
    (is (= "abcDEF_12-34" (get-in resp [:headers "X-Request-Id"])))))

(deftest wrap-request-id-rejects-bad-incoming-id
  (let [handler (fn [req] {:status 200 :headers {} :body (:request-id req)})
        mw      (rid/wrap-request-id handler {:id-fn (constantly "good_id-123")})
        resp    (mw {:headers {"x-request-id" "not ok!!!"}})]
    (is (= "good_id-123" (:body resp)))
    (is (= "good_id-123" (get-in resp [:headers "X-Request-Id"])))))

(deftest wrap-request-id-traceparent
  (let [handler (fn [req] {:status 200 :headers {} :body (:request-id req)})
        mw      (rid/wrap-request-id handler)
        tp      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        resp    (mw {:headers {"traceparent" tp}})]
    (is (= "4bf92f3577b34da6a3ce929d0e0e4736" (:body resp)))
    (is (= "4bf92f3577b34da6a3ce929d0e0e4736" (get-in resp [:headers "X-Request-Id"])))))

(deftest wrap-request-id-fallback-and-nil-response
  (let [mw (rid/wrap-request-id (fn [_] nil))]
    (is (nil? (mw {:headers {"x-request-id" "abc_def-1234"}}))))

  (let [handler (fn [req] {:status 200 :headers {} :body (:request-id req)})
        ;; id-fn returns invalid -> should fall back to uuid (non-empty)
        mw      (rid/wrap-request-id handler {:id-fn (constantly "!!!")})
        resp    (mw {:headers {}})]
    (is (= 200 (:status resp)))
    (is (string? (:body resp)))
    (is (<= 8 (count (:body resp))))
    (is (= (:body resp) (get-in resp [:headers "X-Request-Id"])))))
