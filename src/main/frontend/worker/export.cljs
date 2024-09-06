(ns frontend.worker.export
  "Export data"
  (:require [datascript.core :as d]
            [frontend.common.file.core :as common-file]
            [logseq.db :as ldb]
            [logseq.graph-parser.property :as gp-property]
            [logseq.outliner.tree :as otree]
            [cljs-bean.core :as bean]
            [logseq.db.sqlite.util :as sqlite-util]
            [clojure.string :as string]))

(defn- safe-keywordize
  [block]
  (update block :block/properties
          (fn [properties]
            (when (seq properties)
              (->> (filter (fn [[k _v]]
                             (gp-property/valid-property-name? (str k))) properties)
                   (into {}))))))

(defn get-all-pages
  "Get all pages and their children blocks."
  [repo db]
  (->> (d/q '[:find (pull ?b [*])
              :in $
              :where
              [?b :block/title]
              [?b :block/name]] db)

       (map (fn [[page]]
              (let [whiteboard? (ldb/whiteboard? page)
                    blocks (ldb/get-page-blocks db (:db/id page))
                    blocks' (if whiteboard?
                              blocks
                              (map (fn [b]
                                     (let [b' (if (seq (:block/properties b))
                                                (update b :block/title
                                                        (fn [content]
                                                          (gp-property/remove-properties (:block/format b) content)))
                                                b)]
                                       (safe-keywordize b'))) blocks))
                    children (if whiteboard?
                               blocks'
                               (otree/blocks->vec-tree repo db blocks' (:db/id page)))
                    page' (safe-keywordize page)]
                (assoc page' :block/children children))))))

(defn get-all-page->content
  [repo db]
  (->> (d/datoms db :avet :block/name)
       (map (fn [d]
              (let [e (d/entity db (:e d))]
                [(:block/title e)
                 (common-file/block->content repo db (:block/uuid e) {} {})])))))

(defn get-debug-datoms
  [^Object db]
  (some->> (.exec db #js {:sql "select content from kvs"
                          :rowMode "array"})
           bean/->clj
           (mapcat (fn [result]
                     (let [result (sqlite-util/transit-read (first result))]
                       (when (map? result)
                         (:keys result)))))
           (group-by first)
           (mapcat (fn [[_id col]]
                     (let [type (some (fn [[_e a v _t]]
                                        (when (= a :block/type)
                                          v)) col)
                           ident (some (fn [[_e a v _t]]
                                         (when (= a :db/ident)
                                           v)) col)]
                       (map
                        (fn [[e a v t]]
                          (cond
                            (and (contains? #{:block/title :block/name} a)
                                 (or
                                  ;; normal page or block
                                  (not (contains? #{"class" "property" "journal" "closed value" "hidden"} type))
                                  ;; class/property created by user
                                  (and ident
                                       (contains? #{"class" "property"} type)
                                       (not (string/starts-with? (namespace ident) "logseq")))))
                            [e a (str "debug " e) t]

                            (= a :block/uuid)
                            [e a (str v) t]

                            :else
                            [e a v t]))
                        col))))
           (distinct)
           (sort-by first)))
