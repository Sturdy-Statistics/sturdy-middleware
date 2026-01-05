(ns sturdy.middleware.robots
  (:require
   [ring.util.response :as resp]
   [sturdy.middleware.util :as u]))

(defn with-noindex
  "Add/extend X-Robots-Tag with conservative no-index directives (idempotent)."
  [response]
  (when response
    (resp/header response
                 "X-Robots-Tag"
                 (-> (get-in response [:headers "X-Robots-Tag"])
                     (u/add-header-token "noindex")
                     (u/add-header-token "noimageindex")
                     (u/add-header-token "noarchive")
                     (u/add-header-token "nosnippet")))))
