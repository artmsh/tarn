(ns tarn.part6
  "Extensions past Part 5: inv, coalesce, fmap, fold [:argmin] and composite
   keys. Each query runs through both interpreters, closures and SQL, and the
   two must agree. The closure leaves are loaded by running each leaf's own SQL,
   so a composite key looks the same to both."
  (:require [tarn.ast :refer [s both eq where group fold inv coalesce fmap]]
            [tarn.sql :as sql :refer [to-sql]]
            [tarn.run :refer [run]]
            [tarn.cps :as cps]
            [tarn.part4 :as p4 :refer [flat]]
            [tarn.chinook :as ch]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def schema
  (merge p4/schema
         {:album           {:table "Album"         :key "AlbumId" :val "AlbumId"}
          :track           {:table "Track"         :key "TrackId" :val "TrackId"}
          :track-ms        {:table "Track"         :key "TrackId" :val "Milliseconds"}
          :track-composer  {:table "Track"         :key "TrackId" :val "Composer"}
          ;; PlaylistTrack's key is (PlaylistId, TrackId): one leaf per column, keyed by the pair
          :pt              {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val ["PlaylistId" "TrackId"]}
          :pt-playlist     {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val "PlaylistId"}
          :pt-track        {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val "TrackId"}
          ;; the same, packed into one integer key
          :ptp             {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val ["PlaylistId" "TrackId"] :composite :packed}
          :ptp-playlist    {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val "PlaylistId" :composite :packed}
          :ptp-track       {:table "PlaylistTrack" :key ["PlaylistId" "TrackId"] :val "TrackId" :composite :packed}
          :playlist-name   {:table "Playlist"      :key "PlaylistId" :val "Name"}}))

(defn- leaf
  "A leaf as a closure relation: its own SQL, run once, indexed by key."
  [k]
  (cps/many (map (juxt :x :v1)
                 (jdbc/execute! ch/ds (to-sql schema k) {:builder-fn rs/as-unqualified-lower-maps}))))

(def rels (into {} (map (juxt identity leaf)) (keys schema)))

(defn- via-sql-rows
  "part4's via-sql-rows, over this schema."
  [tree]
  (into #{} (map (fn [r] (into [(:x r)] (map r (map #(keyword (str "v" %)) (range 1 (count r)))))))
        (jdbc/execute! ch/ds (to-sql schema tree) {:builder-fn rs/as-unqualified-lower-maps})))

(def queries
  {;; inv, driven and probed: each artist with each of their albums
   "albums by artist (inv)"
   (-> :artist-name (both (inv :album-artist)))
   ;; coalesce: the composer, or the album's artist where Track.Composer is NULL
   "composer or artist (coalesce)"
   (coalesce :track-composer (-> :track-album (s :album-artist) (s :artist-name)))
   ;; fmap :nil?: tracks without a composer, per album
   "no composer per album (fmap)"
   (-> :track (where (-> (fmap :track-composer :nil?) (eq 1))) (group :track-album) (fold [:count]))
   ;; argmin: the shortest track of each album
   "shortest track per album (argmin)"
   (-> :track (group :track-album) (s (both :track :track-ms)) (fold [:argmin]))
   ;; argmin over mixed types, as SQLite orders them: composer text or ms number
   "argmin over mixed types"
   (-> :track (group :track-album) (s (both :track (coalesce :track-composer :track-ms))) (fold [:argmin]))
   ;; composite keys: tracks per playlist, and its first track by name
   "tracks per playlist (composite key)"
   (-> :pt (group :pt-playlist) (fold [:count]) (both :playlist-name))
   "first track per playlist by name (composite key, argmin)"
   (-> :pt (group :pt-playlist) (s (both :pt-track (s :pt-track :track-name))) (fold [:argmin]))
   "first track per playlist by name (packed key, argmin)"
   (-> :ptp (group :ptp-playlist) (s (both :ptp-track (s :ptp-track :track-name))) (fold [:argmin]))
   "packed and text keys agree"
   (-> :ptp (group :ptp-playlist) (fold [:count]) (both (-> :pt (group :pt-playlist) (fold [:count]))))})

(defn -main [& _]
  (let [results (for [[name tree] (sort-by key queries)]
                  (let [a (flat (run schema rels (constantly identity) tree))
                        b (via-sql-rows tree)]
                    (println (format "%-58s closures: %5d sql: %5d equal: %s" name (count a) (count b) (= a b)))
                    (= a b)))]
    (when-not (every? true? (doall results))
      (System/exit 1))))
