(ns io.gamayun.elastidiff
  (:gen-class)
  (:require [cheshire.core :as json]
            [clj-http.lite.client :as http]
            [clojure.core.async :as async :refer [<! >!]]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [lambdaisland.deep-diff2 :as ddiff]
            [lambdaisland.uri :refer [uri]])
  (:import (com.github.difflib DiffUtils UnifiedDiffUtils)
           (com.github.difflib.text DiffRowGenerator)))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Printing

(def diff-printer
  (ddiff/printer {:color-scheme
                  {:lambdaisland.deep-diff2.printer-impl/deletion  [:bg-red :black]
                   :lambdaisland.deep-diff2.printer-impl/insertion [:bg-green :black]
                   :lambdaisland.deep-diff2.printer-impl/other     [:none]

                   ;; syntax elements
                   :delimiter nil
                   :tag       nil

                   ;; primitive values
                   :nil       nil
                   :boolean   nil
                   :number    nil
                   :string    nil
                   :character nil
                   :keyword   nil
                   :symbol    nil

                   ;; special types
                   :function-symbol nil
                   :class-delimiter nil
                   :class-name      nil}}))

(defn edn-diff
  "Print a diff between two objects."
  [src dst]
  (ddiff/pretty-print (ddiff/diff src dst) diff-printer))

(def json-printer
  (json/create-pretty-printer
   (assoc json/default-pretty-print-options
          :indent-arrays? true)))

(defn text-diff
  "Print a textual diff between two objects."
  [src dst]
  (let [src-lines (str/split-lines (json/encode src {:pretty json-printer}))
        dst-lines (str/split-lines (json/encode dst {:pretty json-printer}))
        patch   (DiffUtils/diff src-lines dst-lines)
        diff    (UnifiedDiffUtils/generateUnifiedDiff "src" "dst" src-lines patch 1)]
    (println (str/join "\n" diff))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scrolling

(defn ensure-scheme [url]
  (if (re-matches #"https?://.+" url)
    url
    (str "http://" url)))

(defn update-url
  [url changes]
  (str (merge (uri url) changes)))

(defn append-path
  [url addition]
  (let [url (uri url)]
    (str (update url :path str addition))))

(defn search-query
  "Build a search query"
  [{:keys [size sort filter includes excludes]}]
  (let [default {:sort    "_id"
                 :query   {:bool {:filter {:match_all {}}}}
                 :_source {:includes "*"}}]
    (cond-> default
      (some? size)
      (assoc :size size)

      (some? sort)
      (assoc :sort sort)

      (some? filter)
      (assoc-in [:query :bool :filter] filter)

      (some? includes)
      (assoc-in [:_source :includes] includes)

      (some? excludes)
      (assoc-in [:_source :excludes] excludes))))

(defn http-error-status
  [^clojure.lang.ExceptionInfo e]
  (:status (ex-data e)))

(defn http-error-body
  [^clojure.lang.ExceptionInfo e]
  (:body (ex-data e)))

(defn scroller
  "Creates a scroller and returns a `chan` of every document in an
  index.

  Options:
  * `scroll` - The duration for how long the search context should remain alive.
  * `sort`   - How to traverse the index.
  * `size`   - The size of the batch and channel to create.
  * `source` - The fields to return from Elasticsearch"
  [url {:keys [size scroll] :as opts}]
  (let [search-url (update-url url {:query (str "scroll=" scroll)})
        out-ch     (async/chan size)]
    (async/go-loop [url search-url
                    params (search-query opts)]
      (let [resp (try (http/post url {:body (json/encode params), :headers {"content-type" "application/json"}})
                      (catch clojure.lang.ExceptionInfo e
                        (println (format "%d: %s" (http-error-status e) url))
                        (println (http-error-body e))
                        (System/exit 1))
                      (catch Exception e
                        (println e)
                        (System/exit 1)))
            body (-> resp :body (json/decode true))
            scroll-id (:_scroll_id body)]
        (if-let [hits (seq (-> body :hits :hits))]
          (do
            (doseq [hit hits] (>! out-ch hit))
            (recur (update-url url {:query nil :path "/_search/scroll"})
                   {:scroll scroll, :scroll_id scroll-id}))
          ;; once we've consume the entire result set, we place an
          ;; infinite number of empty documents
          (async/onto-chan! out-ch (repeat {})))))
    out-ch))

(defn diff-compare
  [x y]
  (case [(some? x) (some? y)]
    [true true]   (compare x y)
    [true false]  -1
    [false true]  1
    [false false] 0))

(defn differ
  [ch-a ch-b {:keys [print-diff print-changed? print-added? print-deleted? print-unchanged?]}]
  (let [done (async/chan)]
    (async/go-loop [hit-a (<! ch-a), hit-b (<! ch-b)]
      (let [{id-a :_id, source-a :_source} hit-a
            {id-b :_id, source-b :_source} hit-b
            order (diff-compare id-a id-b)]

        (cond
          ;; we have consumed the entirety of both indexes, put a
          ;; message on the done channel so the program will exit
          (= {} hit-a hit-b)
          (>! done true)

          ;; looking at the same document on both indexes and they are
          ;; the same
          (and (= order 0) (= source-a source-b))
          (do (when print-unchanged?
                (println "Same:" id-a))
              (recur (<! ch-a) (<! ch-b)))

          ;; looking at the same document on both indexes but they are different
          (and (= order 0) (not= source-a source-b))
          (do (when print-changed?
                (println "Changed:" id-a)
                (print-diff source-a source-b))
              (recur (<! ch-a) (<! ch-b)))

          ;; document is missing from index b
          (< order 0)
          (do (when print-deleted?
                (println "Deleted:" id-a))
              (recur (<! ch-a) hit-b))

          ;; document is missing from index a
          (> order 0)
          (do (when print-added?
                (println "Added:" id-b))
              (recur hit-a (<! ch-b))))))

    done))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI

(defn accm-in-src [m k v] (assoc-in m [:src k] v))
(defn accm-in-dst [m k v] (assoc-in m [:dst k] v))
(defn parse-output [type]
  (case type
    "edn"  edn-diff
    "text" text-diff
    :else  (throw (IllegalAccessException. (str "Invalid output: " type)))))

(def cli-options
  ;; An option with a required argument
  [["-h" "--help"  "Prints this help." :id :help?]
   ["-c" "--[no-]print-changed" "Control printing diffs when documents have different data."
    :default true, :id :print-changed?]
   ["-a" "--[no-]print-added" "Control printing when documents are missing from src but exist in dst."
    :default true, :id :print-added?]
   ["-d" "--[no-]print-deleted" "Control printing when documents are missing from dst but exit in src."
    :default true, :id :print-deleted?]
   ["-u" "--[no-]print-unchanged" "Control printing when documents are the same."
    :default false, :id :print-unchanged?]
   [nil  "--size SIZE" "How many documents to fetch from Elasticsearch in a single request."
    :default 100]
   [nil  "--scroll-time SCROLL" "The duration to keep the scroll context open."
    :id :scroll
    :default "1m"]
   ["-o" "--output FORMAT" "The kind of diff to print. It can be either `text` or `edn` (structural)."
    :id :print-diff
    :default text-diff
    :default-desc "text"
    :parse-fn parse-output]
   ;; src
   [nil "--src-sort SORT" "How to sort the source index."
    :default-desc "_id"
    :id :sort
    :assoc-fn accm-in-src]
   [nil "--src-filter FITLER" "A filter to constrain the result set."
    :id :filter
    :parse-fn #(json/decode % true)
    :assoc-fn accm-in-src]
   [nil "--src-includes INCLUDES" "Elasticsearch source filter includes spec."
    :default-desc "*"
    :id :includes
    :assoc-fn accm-in-src]
   [nil "--src-excludes EXCLUDES" "Elasticsearch source filter excludes spec."
    :id :excludes,
    :assoc-fn accm-in-src]
   ;; dst
   [nil "--dst-sort SORT" "How to sort the source index."
    :id :sort, :assoc-fn accm-in-dst]
   [nil "--dst-filter FITLER" "A filter to constrain the result set."
    :id :filter
    :assoc-fn accm-in-dst
    :parse-fn #(json/decode % true)]
   [nil "--dst-includes INCLUDES" "Elasticsearch source filter includes spec."
    :default-desc "*"
    :id :includes
    :assoc-fn accm-in-dst]
   [nil "--dst-excludes EXLUDES" "Elasticsearch source filter excludes spec."
    :id :excludes
    :assoc-fn accm-in-dst]])

(defn usage
  [options-summary]
  (->> ["Usage: elastidiff [options] src-url dst-url"
        ""
        "The URL should be fully qualified and include the index. Example:"
        "   http://my-es-host.com:9200/my-index"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))


(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (some? errors)
      (do (println errors)
          (System/exit 1))

      (:help? options)
      (do (println (usage summary))
          (System/exit 0))

      (not= 2 (count arguments))
      (do (println "Must provide two URLs to compare.")
          (System/exit 1))

      :else
      (let [src-url (-> (first arguments) ensure-scheme (append-path "/_search"))
            src-opts (-> options (dissoc :dst) (merge (:src options)))
            src-scroller (scroller src-url src-opts)

            dst-url (-> (second arguments) ensure-scheme (append-path "/_search"))
            dst-opts (-> options (dissoc :src) (merge (:dst options)))
            dst-scroller (scroller dst-url dst-opts)

            done (differ src-scroller dst-scroller options)]
        
        ;; park until we can read a message off the done channel, then exit
        (async/<!! done)))))
