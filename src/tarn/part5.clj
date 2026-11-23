(ns tarn.part5
  (:require [tarn.ast :refer [s both eq ge where group fold]]
            [tarn.sql :refer [to-sql]]
            [tarn.run :refer [run]]
            [tarn.rewrite :refer [reassoc factor nodes]]
            [tarn.cps :as cps]
            [tarn.part2 :as p2]
            [tarn.part3 :as p3]
            [tarn.part4 :as p4 :refer [flat]]
            [tarn.chinook :as ch]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def brazil     p4/brazilian)
(def as-written p4/bought-in-brazil)
(def factored   (-> as-written reassoc factor))
;; factored => [:s [:where :line …] [:s :line-track [:both :track-name [:s :track-album [:s :album-artist :artist-name]]]]]

(def brazilian-music (-> :playlist (where (-> :playlist-name (eq "Brazilian Music")))))
(def pl-as-written (-> brazilian-music (s (both (-> :playlist-tracks (s :track-name))
                                                  (-> :playlist-tracks (s :track-album))))))
(def pl-factored (-> pl-as-written reassoc factor))

;; five more leaves: two junction-shaped, one backwards, one identity column
(def base-schema
  (merge p4/schema
         {:playlist        {:table "Playlist"      :key "PlaylistId" :val "PlaylistId" :entry true}
          :playlist-name   {:table "Playlist"      :key "PlaylistId" :val "Name"}
          :playlist-tracks {:table "PlaylistTrack" :key "PlaylistId" :val "TrackId" :ref "Track"}
          :invoice-lines   {:table "InvoiceLine"   :key "InvoiceId"  :val "InvoiceLineId" :ref "InvoiceLine"}
          :line-id         {:table "InvoiceLine"   :key "InvoiceLineId" :val "InvoiceLineId"}}))

(def pk-leaves
  "Leaves whose key column is the table's primary key: one row per key. The
   printer may only merge two leaves of one table into a single scan when this
   holds, otherwise the join on x pairs every row of a key with every other."
  #{:line :invoice :line-invoice :line-track :invoice-customer :invoice-total :country
    :track-name :track-album :album-artist :artist-name
    :playlist :playlist-name :line-id})

(def schema
  (into {} (map (fn [[k v]] [k (cond-> v (pk-leaves k) (assoc :pk true))])) base-schema))

(def rels
  (merge p4/rels
         {:playlist p2/playlist :playlist-name p2/playlist-name :playlist-tracks p2/playlist-tracks
          :invoice-lines (cps/index (cps/inv p2/line-invoice))
          :line-id (cps/column (mapv :invoicelineid ch/line-rows))}))

(def ids (assoc p4/ids "InvoiceLine" (mapv :invoicelineid ch/line-rows)))

;; join order: start from the 35 Brazilian invoices, walk backwards to their lines
(def flipped
  (-> :invoice (where brazil)
      (s :invoice-lines)
      (s (both :line-id (both (-> :line-track (s :track-name))
                              (-> :line-track (s :track-album) (s :album-artist) (s :artist-name)))))))

(defn rows-of
  "Both shapes of the Brazil answer, as #{[line-id track artist]}."
  [tree]
  (into #{} (map (fn [r] (vec (take-last 3 r)))) (flat (run schema rels ids tree))))

(defn sql-rows
  "Part 4's via-sql-rows, against this part's wider schema."
  [tree]
  (let [rows (jdbc/execute! ch/ds (to-sql schema tree) {:builder-fn rs/as-unqualified-lower-maps})]
    (into #{} (map (fn [r] (into [(:x r)] (map r (map #(keyword (str "v" %)) (range 1 (count r))))))) rows)))

(defn sql-rows-of [tree]
  (into #{} (map (fn [r] (vec (take-last 3 r)))) (sql-rows tree)))

(defn plan
  "EXPLAIN QUERY PLAN for a tree, as the detail strings."
  [tree]
  (let [q (to-sql schema tree)]
    (mapv :detail (jdbc/execute! ch/ds (into [(str "EXPLAIN QUERY PLAN " (first q))] (rest q))
                                 {:builder-fn rs/as-unqualified-lower-maps}))))

(defn reads
  "How many plan steps touch table (SCAN or SEARCH)."
  [table tree]
  (count (filter #(re-find (re-pattern (str "^(SCAN|SEARCH) " table "\\b")) %) (plan tree))))

(defn -main [& _]
  (println "as written:" (nodes as-written) "nodes, factored:" (nodes factored) "nodes, flipped:" (nodes flipped) "nodes")
  (println "factored tree:" factored)
  (println "same rows, closures:" (= (rows-of as-written) (rows-of factored))
           "sql:" (= (sql-rows-of as-written) (sql-rows-of factored)))
  (let [sql-count #(count (rest (jdbc/execute! ch/ds (to-sql schema %) {:builder-fn rs/as-arrays})))]
    (println "playlist as written:" (cps/size (run schema rels ids pl-as-written))
             "factored:" (cps/size (run schema rels ids pl-factored))
             "| sql:" (sql-count pl-as-written) (sql-count pl-factored)))
  (println "flipped, same rows:" (= (rows-of as-written) (rows-of flipped)) (= (sql-rows-of as-written) (sql-rows-of flipped)))
  (println "plan steps reading Track, as written / factored / flipped:"
           (reads "Track" as-written) (reads "Track" factored) (reads "Track" flipped))
  (doseq [[label tree] [["as written" as-written] ["factored  " factored] ["flipped   " flipped]]]
    (let [q (to-sql schema tree)]
      (spit (str "generated-" (.replace (.trim ^String label) " " "-") ".sql") (str (.replace ^String (first q) "?" "'Brazil'") ";\n"))
      (p2/bench (str "closures, " label) #(cps/size (run schema rels ids tree)))
      (p2/bench (str "sqlite,   " label) #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays}))))))
