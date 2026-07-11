(ns sturdy.middleware.integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.middleware.cache-control :refer [wrap-nostore
                                            wrap-nostore-on-errors]]
   [sturdy.middleware.origin :refer [wrap-require-same-origin]]
   [sturdy.middleware.request-id :as request-id]
   [sturdy.middleware.request-size :refer [wrap-max-request-size]]))

(deftest outer-request-id-covers-the-entire-middleware-stack
  (let [received-request-id (atom nil)
        handler (-> (fn [request]
                      (reset! received-request-id (:request-id request))
                      {:status 200 :headers {} :body "ok"})
                    (#(wrap-max-request-size % (* 10 1024 1024)))
                    wrap-require-same-origin
                    wrap-nostore-on-errors
                    wrap-nostore
                    (#(request-id/wrap-request-id
                       % {:id-fn (constantly "test-request-id")})))]
    (testing "an early same-origin rejection retains request correlation"
      (let [response (handler {:request-method :post
                               :scheme :https
                               :headers {"host" "example.com"
                                         "origin" "https://evil.example"}})]
        (is (= 403 (:status response)))
        (is (= "test-request-id"
               (get-in response [:headers "X-Request-Id"])))))

    (testing "the application receives the request ID on the inward path"
      (handler {:request-method :get
                :scheme :https
                :headers {"host" "example.com"}})
      (is (= "test-request-id" @received-request-id)))))
