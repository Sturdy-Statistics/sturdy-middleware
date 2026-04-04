(ns sturdy.middleware.request-size-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.middleware.request-size :as rs]))

(deftest wrap-max-request-size-behavior
  (let [handler (fn [_] {:status 200 :headers {} :body "ok"})
        mw      (rs/wrap-max-request-size handler 1024)]

    (testing "allows when no body is intended (missing length and tx-encoding)"
      (is (= 200 (:status (mw {:headers {}})))))

    (testing "allows when Content-Length <= max"
      (is (= 200 (:status (mw {:headers {"content-length" "1024"}}))))
      (is (= 200 (:status (mw {:headers {"content-length" "100"}})))))

    (testing "rejects 411 when Content-Length is invalid (Fail Closed)"
      (is (= 411 (:status (mw {:headers {"content-length" "nope"}})))))

    (testing "rejects 411 when Transfer-Encoding is chunked (DoS Protection)"
      (let [resp (mw {:headers {"transfer-encoding" "chunked"}})]
        (is (= 411 (:status resp)))
        (is (= "close" (get-in resp [:headers "Connection"])))))

    (testing "rejects 413 when Content-Length > max (uses render hook)"
      (let [seen (atom nil)]
        (binding [rs/*render-too-large*
                  (fn [ctx]
                    (reset! seen ctx)
                    "ERR")]
          (let [resp (mw {:headers {"content-length" "8192"}
                          :request-id "abc"})]
            (is (= 413 (:status resp)))
            (is (= "close" (get-in resp [:headers "Connection"])))
            ;; verify the hook got useful context
            (is (= 413 (:code @seen)))
            (is (= "abc" (:request-id @seen)))
            (is (= 1024 (:max-upload-bytes @seen)))))))

    (testing "rejects 411 missing length uses *render-length-required* hook"
      (let [seen (atom nil)]
        (binding [rs/*render-length-required*
                  (fn [ctx]
                    (reset! seen ctx)
                    "ERR-411")]
          (let [resp (mw {:headers {"transfer-encoding" "chunked"}
                          :request-id "def"})]
            (is (= 411 (:status resp)))
            (is (= 411 (:code @seen)))
            (is (= "def" (:request-id @seen)))))))))
