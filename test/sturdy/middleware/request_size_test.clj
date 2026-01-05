(ns sturdy.middleware.request-size-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.middleware.request-size :as rs]))

(deftest wrap-max-request-size-behavior
  (let [handler (fn [_] {:status 200 :headers {} :body "ok"})
        mw      (rs/wrap-max-request-size handler 1024)]

    (testing "allows when Content-Length is missing"
      (is (= 200 (:status (mw {:headers {}})))))

    (testing "allows when Content-Length is invalid"
      (is (= 200 (:status (mw {:headers {"content-length" "nope"}})))))

    (testing "allows when Content-Length <= max"
      (is (= 200 (:status (mw {:headers {"content-length" "1024"}}))))
      (is (= 200 (:status (mw {:headers {"content-length" "100"}})))))

    (testing "rejects when Content-Length > max (uses render hook)"
      (let [seen (atom nil)]
        (binding [rs/*render-too-large*
                  (fn [ctx]
                    (reset! seen ctx)
                    "ERR")]
          (let [resp (mw {:headers {"content-length" "1025"}
                          :request-id "abc"})]
            (is (= 413 (:status resp)))
            (is (= "close" (get-in resp [:headers "Connection"])))
            ;; verify the hook got useful context
            (is (= 413 (:code @seen)))
            (is (= "abc" (:request-id @seen)))
            (is (= 1024 (:max-upload-bytes @seen)))))))

    (testing "accepts keyword header key too"
      (is (= 200 (:status (mw {:headers {:content-length "10"}})))))))
