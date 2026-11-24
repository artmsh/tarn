(ns tarn.part5
  (:require [tarn.ast :refer [s both eq ge where group fold]]
            [tarn.sql :refer [to-sql]]
            [tarn.run :refer [run]]
            [tarn.rewrite :refer [reassoc factor nodes functional? push-eq]]
            [tarn.cps :as cps]
            [tarn.part2 :as p2]
            [tarn.part3 :as p3]
            [tarn.part4 :as p4 :refer [flat]]
            [tarn.chinook :as ch]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; five more leaves: two junction-shaped, one backwards, one identity column
(def functional-leaves
  "Column leaves: one value per key. :playlist-tracks and :invoice-lines are
   junction-shaped, many per key, so they are absent on purpose. Absent means
   not functional, and factor then refuses."
  #{:line :invoice :line-invoice :line-track :invoice-customer :invoice-total :country
    :track-name :track-album :album-artist :artist-name
    :playlist :playlist-name :line-id})

(def pk-leaves
  "Leaves whose key column is the table's primary key: one row per key. The
   printer may only merge two leaves of one table into a single scan when this
   holds, otherwise the join on x pairs every row of a key with every other."
  #{:line :invoice :line-invoice :line-track :invoice-customer :invoice-total :country
    :track-name :track-album :album-artist :artist-name
    :playlist :playlist-name :line-id})

(def schema
  (into {}
        (map (fn [[k v]] [k (cond-> v
                              (functional-leaves k) (assoc :functional true)
                              (pk-leaves k) (assoc :pk true))]))
        (merge p4/schema
         {:playlist        {:table "Playlist"      :key "PlaylistId" :val "PlaylistId" :entry true}
          :playlist-name   {:table "Playlist"      :key "PlaylistId" :val "Name"}
          :playlist-tracks {:table "PlaylistTrack" :key "PlaylistId" :val "TrackId" :ref "Track"}
          :invoice-lines   {:table "InvoiceLine"   :key "InvoiceId"  :val "InvoiceLineId" :ref "InvoiceLine"}
          :line-id         {:table "InvoiceLine"   :key "InvoiceLineId" :val "InvoiceLineId"}})))

(def brazil     p4/brazilian)
(def as-written p4/bought-in-brazil)
(def factored   (->> as-written reassoc (factor schema)))
;; factored => [:s [:where :line …] [:s :line-track [:both :track-name [:s :track-album [:s :album-artist :artist-name]]]]]

(def brazilian-music (-> :playlist (where (-> :playlist-name (eq "Brazilian Music")))))
(def pl-as-written (-> brazilian-music (s (both (-> :playlist-tracks (s :track-name))
                                                  (-> :playlist-tracks (s :track-album))))))
(def pl-factored (->> pl-as-written reassoc (factor schema)))

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

(defn time-ms
  "Mean ms per call over n calls, after a warm-up."
  [f n]
  (dotimes [_ (* 4 n)] (f))
  (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) n 1e6)))

(defn median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn push-eq-report
  "Before and after push-eq: rows (both interpreters), nodes, SELECTs, plan,
   and time, interleaved in this JVM, median of 5 rounds."
  []
  (doseq [[label tree] [["brazilian " brazil] ["as-written" as-written] ["factored  " factored] ["flipped   " flipped]]]
    (let [pushed (push-eq tree)
          run-c  #(cps/size (run schema rels ids %))
          q      #(to-sql schema %)
          run-s  #(count (jdbc/execute! ch/ds (q %) {:builder-fn rs/as-arrays}))
          sel    #(count (re-seq #"SELECT" (first (q %))))
          rounds (repeatedly 5 (fn [] [(time-ms #(run-c tree) 400) (time-ms #(run-c pushed) 400)
                                       (time-ms #(run-s tree) 150) (time-ms #(run-s pushed) 150)]))
          col    (fn [i] (median (map #(nth % i) rounds)))]
      (println label "changed:" (not= tree pushed)
               "| rows closures:" (= (flat (run schema rels ids tree)) (flat (run schema rels ids pushed)))
               "sql:" (= (sql-rows tree) (sql-rows pushed))
               "| nodes" (nodes tree) "->" (nodes pushed) "SELECTs" (sel tree) "->" (sel pushed)
               "| plan same:" (= (plan tree) (plan pushed))
               (format "| closures %.4f -> %.4f ms, sqlite %.3f -> %.3f ms" (col 0) (col 1) (col 2) (col 3))))))

(defn probe-report
  "Probes per run next to milliseconds, for the three trees, and the ratios."
  []
  (let [rows (for [[label tree] [["as written" as-written] ["factored  " factored] ["flipped   " flipped]]]
               (let [f #(cps/size (run schema rels ids tree))
                     c (cps/count-probes f)]
                 (assoc c :label label :ms (time-ms f 1000))))
        tot #(+ (:column %) (:many %) (:member %))
        [a fa fl] rows]
    (doseq [r rows]
      (println (format "probes, %s: %,d (column %,d, many %,d, member %,d) %.3f ms, rows %,d"
                       (:label r) (tot r) (:column r) (:many r) (:member r) (:ms r) (:result r))))
    (println (format "ratio as written / flipped: probes %.2f, time %.2f | as written / factored: probes %.2f, time %.2f"
                     (double (/ (tot a) (tot fl))) (/ (:ms a) (:ms fl))
                     (double (/ (tot a) (tot fa))) (/ (:ms a) (:ms fa))))))

(defn -main [& _]
  (println "as written:" (nodes as-written) "nodes, factored:" (nodes factored) "nodes, flipped:" (nodes flipped) "nodes")
  (println "factored tree:" factored)
  (println "same rows, closures:" (= (rows-of as-written) (rows-of factored))
           "sql:" (= (sql-rows-of as-written) (sql-rows-of factored)))
  (let [sql-count #(count (rest (jdbc/execute! ch/ds (to-sql schema %) {:builder-fn rs/as-arrays})))]
    (println "playlist as written:" (cps/size (run schema rels ids pl-as-written))
             "factored:" (cps/size (run schema rels ids pl-factored))
             "| sql:" (sql-count pl-as-written) (sql-count pl-factored)))
  (println "factor leaves pl-as-written alone:" (= pl-as-written pl-factored)
           "| functional?: line-track" (functional? schema :line-track)
           "playlist-tracks" (functional? schema :playlist-tracks)
           "[:s :line-invoice :invoice-lines]" (functional? schema [:s :line-invoice :invoice-lines]))
  (println "factor still factors brazil:" (not= as-written factored) (= 20 (nodes as-written)) (= 18 (nodes factored)))
  (let [meant (-> brazilian-music (s :playlist-tracks) (s (both :track-name :track-album)))
        sql-count #(count (rest (jdbc/execute! ch/ds (to-sql schema %) {:builder-fn rs/as-arrays})))]
    (println "playlist, the tracks written once:" (cps/size (run schema rels ids meant)) "| sql:" (sql-count meant)))
  (println "flipped, same rows:" (= (rows-of as-written) (rows-of flipped)) (= (sql-rows-of as-written) (sql-rows-of flipped)))
  (println "plan steps reading Track, as written / factored / flipped:"
           (reads "Track" as-written) (reads "Track" factored) (reads "Track" flipped))
  (push-eq-report)
  (probe-report)
  (doseq [[label tree] [["as written" as-written] ["factored  " factored] ["flipped   " flipped]]]
    (let [q (to-sql schema tree)]
      (spit (str "generated-" (.replace (.trim ^String label) " " "-") ".sql") (str (.replace ^String (first q) "?" "'Brazil'") ";\n"))
      (p2/bench (str "closures, " label) #(cps/size (run schema rels ids tree)))
      (p2/bench (str "sqlite,   " label) #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays}))))))
