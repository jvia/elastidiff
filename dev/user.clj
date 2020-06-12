(ns user
  (:require [cheshire.core :as json]
            [clj-http.lite.client :as http]
            [clojure.java.io :as io]))

(def host "http://localhost:9200")

(defn index-docs [index docs]
  (doseq [{:keys [id] :as doc} docs]
    (let [url (format "%s/%s/_doc/%d" host index id)]
      (println url)
      (http/put url
                {:headers {"content-type" "application/json"}
                 :body    (json/encode doc)}))))

(defn index []
  (let [src (json/decode (slurp "dev/src.json") true)
        dst (json/decode (slurp "dev/dst.json") true)]
    (index-docs "src" src)
    (index-docs "dst" dst)))
