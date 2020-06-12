(ns io.gamayun.elastidiff-test
  (:require [clojure.test :refer :all]
            [io.gamayun.elastidiff :as sut]))

(deftest update-url
  (is (= "http://foo.com?q=bar"
         (sut/update-url "http://foo.com" {:query "q=bar"})))
  (is (= "http://foo.com"
         (sut/update-url "http://foo.com?q=bar" {:query nil}))))

(deftest append-path
  (is (= "http://host:9200/index/_search?scroll=1m"
         (sut/append-path "http://host:9200/index?scroll=1m" "/_search"))))

(deftest search-query
  (are [opts query] (= query (sut/search-query opts))
    {}
    {:sort    "_id"
     :query   {:bool {:filter {:match_all {}}}}
     :_source {:includes "*"}}

    {:size 10, :sort "date", :filter "{\"genres\": \"sci-fi\"}"}
    {:query   {:bool {:filter "{\"genres\": \"sci-fi\"}"}}
     :sort    "date"
     :_source {:includes "*"}
     :size    10}))
