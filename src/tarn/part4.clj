(ns tarn.part4
  (:require [tarn.ast :refer [s both eq ge where group fold]]
            [tarn.sql :refer [to-sql]]
            [tarn.run :refer [run]]
            [tarn.cps :as cps]
            [tarn.part2 :as p2]
            [tarn.part3 :as p3]
            [tarn.chinook :as ch]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; what each leaf is, in SQL terms: a table, a key column, a value column
(def schema
  {:line             {:table "InvoiceLine" :key "InvoiceLineId" :val "InvoiceLineId" :entry true}
   :invoice          {:table "Invoice"     :key "InvoiceId"     :val "InvoiceId"     :entry true}
   :line-invoice     {:table "InvoiceLine" :key "InvoiceLineId" :val "InvoiceId"  :ref "Invoice"}
   :line-track       {:table "InvoiceLine" :key "InvoiceLineId" :val "TrackId"    :ref "Track"}
   :invoice-customer {:table "Invoice"     :key "InvoiceId"     :val "CustomerId" :ref "Customer"}
   :invoice-total    {:table "Invoice"     :key "InvoiceId"     :val "Total"}
   :country          {:table "Customer"    :key "CustomerId"    :val "Country"}
   :track-name       {:table "Track"       :key "TrackId"       :val "Name"}
   :track-album      {:table "Track"       :key "TrackId"       :val "AlbumId"    :ref "Album"}
   :album-artist     {:table "Album"       :key "AlbumId"       :val "ArtistId"   :ref "Artist"}
   :artist-name      {:table "Artist"      :key "ArtistId"      :val "Name"}})

;; row -> id, per table, for the keys that group makes visible
(def ids
  {"Artist" (mapv :artistid ch/artist-rows) "Album" (mapv :albumid ch/album-rows)
   "Track" (mapv :trackid ch/track-rows) "Customer" (mapv :customerid ch/customer-rows)
   "Invoice" (mapv :invoiceid ch/invoice-rows)})

;; what each leaf is, in closure terms: Part 2's loaded relations
(def rels
  {:line p2/line :invoice p3/invoice :line-invoice p2/line-invoice :line-track p2/line-track
   :invoice-customer p2/invoice-customer :invoice-total p3/invoice-total :country p2/country
   :track-name p2/track-name :track-album p2/track-album :album-artist p2/album-artist
   :artist-name p2/artist-name})

(defn flat
  "Closure results nest values as [y z]; SQL rows are flat. Flatten to compare."
  [q]
  (into #{} (map (fn [[x v]] (into [x] (if (vector? v) (flatten v) [v])))) q))

(defn via-sql-rows [tree]
  (let [rows (jdbc/execute! ch/ds (to-sql schema tree) {:builder-fn rs/as-unqualified-lower-maps})]
    (into #{} (map (fn [r] (into [(:x r)] (map r (map #(keyword (str "v" %)) (range 1 (count r))))))) rows)))

(def brazilian
  (-> :invoice-customer (s :country) (eq "Brazil")))

(def bought-in-brazil
  (-> :line
      (where (-> :line-invoice (s brazilian)))
      (s (both (-> :line-track (s :track-name))
               (-> :line-track (s :track-album) (s :album-artist) (s :artist-name))))))

(def big-in-brazil
  (-> :line (where (-> :line-invoice (s brazilian)))
      (group (-> :line-track (s :track-album) (s :album-artist)))
      (fold [:count])
      (ge 9)
      (both :artist-name)))

(def revenue
  (-> :invoice (group (s :invoice-customer :country)) (s :invoice-total) (fold [:sum])))

(defn -main [& _]
  (let [sql (to-sql schema bought-in-brazil)]
    (println "params:" (rest sql))
    (println "sql chars:" (count (first sql)) "SELECTs:" (count (re-seq #"SELECT" (first sql))))
    (spit "generated.sql" (str (.replace ^String (first sql) "?" "'Brazil'") ";\n")))
  (let [a (flat (run schema rels ids bought-in-brazil)) b (via-sql-rows bought-in-brazil)]
    (println "closures:" (count a) "sql:" (count b) "equal:" (= a b)))
  (let [a (flat (run schema rels ids big-in-brazil)) b (via-sql-rows big-in-brazil)]
    (println "big-in-brazil closures:" (count a) "sql:" (count b) "equal:" (= a b))
    (println (sort-by second a)))
  (let [a (flat (run schema rels ids revenue)) b (via-sql-rows revenue)
        r2 (fn [s] (into #{} (map (fn [[c t]] [c (Math/round (* 100.0 t))])) s))]
    (println "revenue closures:" (count a) "sql:" (count b) "equal to the cent:" (= (r2 a) (r2 b))))
  (p2/bench "closures, brazil        " #(cps/size (run schema rels ids bought-in-brazil)))
  (let [q (to-sql schema bought-in-brazil)]
    (p2/bench "sqlite, generated sql   " #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays}))))
  (let [q ["select il.InvoiceLineId, t.Name, ar.Name from InvoiceLine il join Invoice i on il.InvoiceId=i.InvoiceId join Customer c on i.CustomerId=c.CustomerId join Track t on il.TrackId=t.TrackId join Album al on t.AlbumId=al.AlbumId join Artist ar on al.ArtistId=ar.ArtistId where c.Country='Brazil'"]]
    (p2/bench "sqlite, hand-written sql" #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays})))))
