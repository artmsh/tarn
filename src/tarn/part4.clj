(ns tarn.part4
  (:require [tarn.ast :refer [s both eq ge gt filt where group fold]]
            [tarn.like :as like]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [tarn.cse :as cse]
            [tarn.sql :as sql :refer [to-sql]]
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
   :invoice-date     {:table "Invoice"     :key "InvoiceId"     :val "InvoiceDate"}
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
   :invoice-customer p2/invoice-customer :invoice-total p3/invoice-total :invoice-date (tarn.part2/col ch/invoice-rows :invoicedate) :country p2/country
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

(def live-in-brazil
  (-> :line (where (-> :line-invoice (s brazilian)))
      (s (-> :line-track (s :track-name) (filt [:like "%Live%"])))))

(defn sqlite3 [sql] (:out (sh/sh "sqlite3" "chinook.sqlite" sql)))

(defn like-check
  "Every LIKE pattern, over every track name: regex vs sqlite3 CLI (the oracle)."
  []
  (doseq [pat ["%Live%" "%live%" "L_ve%" "%(%" "%.%" "%[a-z]%" "%\\%" "%" "" "_" "%é%" "%É%" "%NAÇÃO%" "%Nação%" "a%b" "%$%" "%^%" "%*%" "%+%" "%?%"]]
    (let [esc (str/replace pat "'" "''")
          oracle (into #{} (remove str/blank?) (str/split-lines (sqlite3 (str "select TrackId from Track where Name like '" esc "'"))))
          pred (like/like-pred pat)
          got (into #{} (comp (filter (fn [r] (pred (:name r)))) (map #(str (:trackid %)))) ch/track-rows)]
      (println (format "like %-10s sqlite %4d regex %4d agree %s" (pr-str pat) (count oracle) (count got) (= oracle got))))))

(def big-in-brazil
  (-> :line (where (-> :line-invoice (s brazilian)))
      (group (-> :line-track (s :track-album) (s :album-artist)))
      (fold [:count])
      (ge 9)
      (both :artist-name)))

(def revenue
  (-> :invoice (group (s :invoice-customer :country)) (s :invoice-total) (fold [:sum])))

(def first-order
  (-> :invoice (group (s :invoice-customer :country)) (s :invoice-date) (fold [:min])))

(def first-order-after-2010
  (gt first-order "2010-01-01"))

(defn sqlite3 [sql] (:out (sh/sh "sqlite3" "chinook.sqlite" sql)))

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
    (p2/bench "sqlite, hand-written sql" #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays}))))
  (println "--- ex4-1: [:filt r [:like \"%Live%\"]]")
  (let [t live-in-brazil sql (to-sql schema t)
        a (flat (run schema rels ids t)) b (via-sql-rows t)
        oracle (into #{} (comp (remove str/blank?) (map #(str/split % #"\|" 2))) (str/split-lines (sqlite3 "select il.InvoiceLineId, t.Name from InvoiceLine il join Invoice i using(InvoiceId) join Customer c using(CustomerId) join Track t using(TrackId) where c.Country='Brazil' and t.Name like '%Live%'")))
        o (into #{} (map (fn [[i n]] [(parse-long i) n])) oracle)]
    (println "params:" (rest sql))
    (println "live-in-brazil closures:" (count a) "sql:" (count b) "equal:" (= a b) "sqlite3 CLI:" (count o) "equal:" (= o a))
    (println (sort-by first a)))
  (like-check)
  (println "brazil, case-sensitive (re-find #\"Live\"):"
           (count (filter (fn [[_ n]] (re-find #"Live" n)) (flat (run schema rels ids (-> :line (where (-> :line-invoice (s brazilian))) (s :line-track) (s :track-name)))))) "rows, vs 6 with LIKE semantics")
  (println "case-sensitive re-find '(Live)' would keep" (count (filter #(re-find #"Live" (:name %)) ch/track-rows)) "of" (count ch/track-rows)
           "vs LIKE" (count (filter (comp (like/like-pred "%Live%") :name) ch/track-rows)))
  (println "--- ex4-2: :gt and fold [:min]")
  (let [oracle (fn [having] (into {} (map #(str/split % #"\|")) (remove str/blank? (str/split-lines (sqlite3 (str "select c.Country, min(i.InvoiceDate) from Invoice i join Customer c using(CustomerId) group by c.Country" having ";"))))))
        check (fn [label tree having]
                (let [sql (to-sql schema tree) a (into {} (run schema rels ids tree)) b (into {} (map (juxt first second)) (via-sql-rows tree))
                      o (oracle having)]
                  (println label "closures:" (count a) "sql:" (count b) "sqlite3 CLI:" (count o) "all equal:" (= a b o))
                  sql))
        sql1 (check "per-country min  " first-order "")
        sql2 (check "min > 2010-01-01 " first-order-after-2010 " having min(i.InvoiceDate) > '2010-01-01'")]
    (println "aggregate printed:" (re-find #"MIN\(q\.v1\)" (first sql1)) "| params:" (rest sql2))
    (println "survivors:" (sort (into {} (run schema rels ids first-order-after-2010))))
    (println "cutoff 2010-12-31 survivors:" (cps/size (run schema rels ids (gt first-order "2010-12-31"))))
    (println "core min on strings ->" (try (min "a" "b") (catch Throwable e (.getSimpleName (class e))))))
  (println "--- ex4-3: where as EXISTS vs IN")
  (doseq [[nm tree] [["bought-in-brazil" bought-in-brazil] ["big-in-brazil" big-in-brazil]]
          style [:in :exists]]
    (binding [sql/*where-style* style]
      (let [q (to-sql schema tree) b (via-sql-rows tree)
            a (flat (run schema rels ids tree))]
        (println (format "%-17s %-7s chars %d SELECTs %d rows %d equal to closures: %s"
                         nm style (count (first q)) (count (re-seq #"SELECT" (first q))) (count b) (= a b))))))
  (doseq [style [:in :exists]]
    (binding [sql/*where-style* style]
      (let [q (to-sql schema bought-in-brazil)]
        (println "EXPLAIN QUERY PLAN" style)
        (doseq [r (jdbc/execute! ch/ds (into [(str "EXPLAIN QUERY PLAN " (first q))] (rest q)) {:builder-fn rs/as-unqualified-lower-maps})]
          (println (format "  %3d %3d %s" (:id r) (:parent r) (:detail r))))
        (p2/bench (str "sqlite, " (name style) "           ") #(count (jdbc/execute! ch/ds q {:builder-fn rs/as-arrays}))))))
  (println "--- ex4-4: cse")
  (let [forest {:bought-in-brazil bought-in-brazil :big-in-brazil big-in-brazil :revenue revenue}
        show (fn [m] (doseq [[t locs] m] (println "   " (pr-str t) "x" (count locs) (mapv (fn [l] (vec (take 2 l))) locs))))]
    (doseq [[nm t] forest]
      (let [r (cse/cse {nm t})]
        (println nm "equal subtrees:" (count (:equal-subtrees r)) "| repeated leaves:" (pr-str (keys (:equal-leaves r)))
                 "| shared left operands:" (pr-str (keys (:shared-left r))))
        (show (:shared-left r))))
    (let [r (cse/cse forest)]
      (println "across all three: equal subtrees")
      (show (:equal-subtrees r)))
    (let [r (cse/cse {:dup [:both brazilian brazilian]})]
      (println "synthetic [:both brazilian brazilian]:")
      (show (:equal-subtrees r))))
  (let [q (to-sql schema bought-in-brazil)
        rep (cse/repeated-sql (first q))]
    (println "repeated (SELECT ...) texts in the generated SQL:" (count rep))
    (doseq [[t n] rep] (println "   x" n (count t) "chars:" t)))
  (println "EXPLAIN QUERY PLAN, generated SQL (IN form is Part 4's own; this branch has it)")
  (let [q (to-sql schema bought-in-brazil)]
    (doseq [r (jdbc/execute! ch/ds (into [(str "EXPLAIN QUERY PLAN " (first q))] (rest q)) {:builder-fn rs/as-unqualified-lower-maps})]
      (println (format "  %3d %3d %s" (:id r) (:parent r) (:detail r)))))
  (let [lt "(SELECT InvoiceLineId AS x, TrackId AS v1 FROM InvoiceLine)"
        base (first (to-sql schema bought-in-brazil))
        n (count (re-seq (re-pattern (java.util.regex.Pattern/quote lt)) base))
        with (fn [m] (str "WITH lt AS " m " (SELECT InvoiceLineId AS x, TrackId AS v1 FROM InvoiceLine) "
                          (str/replace base lt "lt")))
        run1 (fn [sql] (jdbc/execute! ch/ds [sql "Brazil"] {:builder-fn rs/as-arrays}))]
    (println "line-track subquery occurrences in the SQL text:" n)
    (doseq [[nm sqlx] [["as generated" base] ["CTE MATERIALIZED" (with "MATERIALIZED")] ["CTE NOT MATERIALIZED" (with "NOT MATERIALIZED")]]]
      (println (format "%-22s rows %d (header excluded) same-as-generated %s" nm (dec (count (run1 sqlx)))
                       (= (set (map vec (rest (run1 base)))) (set (map vec (rest (run1 sqlx)))))))
      (when-not (= nm "as generated")
        (println "  plan:" (str/join " | " (map :detail (jdbc/execute! ch/ds [(str "EXPLAIN QUERY PLAN " sqlx) "Brazil"] {:builder-fn rs/as-unqualified-lower-maps})))))
      (p2/bench (format "%-22s" nm) #(count (run1 sqlx)))))
  ;; closures: how many times does the shared relation get probed/driven?
  (let [n (atom 0)
        counted (let [q (get rels :line-track)]
                  (cps/->Query (fn [k] (swap! n inc) (cps/drive q k))
                               (fn [x k] (swap! n inc) (cps/probe q x k))
                               (:member q)))]
    (cps/size (run schema (assoc rels :line-track counted) ids bought-in-brazil))
    (println "closures: :line-track entered" @n "times in one run of the Brazil query: 190 rows x 2 branches, each an O(1) array lookup")))
