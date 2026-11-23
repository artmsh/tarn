(ns tarn.part3
  (:require [next.jdbc] [next.jdbc.result-set] [tarn.cps :refer [s both eq filt where column ids size inv index group fold gather fmap distinct-count cmin sorted-fold sort-by-key from-pairs-sorted dense-fold]]
            [tarn.part2 :refer [artist line artist-name album-artist track-name track-album
                                country invoice-customer line-invoice line-track brazilian bench]]
            [tarn.chinook :as ch]))

(def invoice       (tarn.part2/id ch/invoice-rows :invoiceid))
(def invoice-total (tarn.part2/col ch/invoice-rows (comp bigdec :total)))  ; Double -> BigDecimal at load

(def brazil-lines (-> line (where (-> line-invoice (s brazilian)))))
(def line-artist  (-> line-track (s track-album) (s album-artist)))

(def per-artist
  (-> brazil-lines (group line-artist) (fold 0 (fn [n _] (inc n)))))

(def per-artist-dense
  (-> brazil-lines (group line-artist) (dense-fold (count ch/artist-rows) 0 (fn [n _] (inc n)))))

(def big-in-brazil
  (-> per-artist (filt #(>= % 9)) (both artist-name)))

(def revenue
  (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0M +)))

(def distinct-tracks-per-country
  (-> line (group (-> line-invoice (s invoice-customer) (s country))) (s line-track) (distinct-count)))

(def invoice-date (tarn.part2/col ch/invoice-rows :invoicedate))

(defn first-order-after
  "Per country, the earliest InvoiceDate (fold with cmin), keeping those later than cutoff."
  [cutoff]
  (-> invoice (group (s invoice-customer country)) (s invoice-date)
      (fold nil (fn [m d] (if (nil? m) d (cmin m d))))
      (filt #(pos? (compare % cutoff)))))

(def revenue-stream
  (-> invoice (group (s invoice-customer country)) (s invoice-total)))
(def revenue-sorted (sort-by-key revenue-stream))   ; sorted once, at load

(def artist-albums (index (inv album-artist)))

(defn -main [& _]
  (println "groups:" (size per-artist))
  (doseq [[_ [n name]] (sort-by (fn [[_ [n name]]] [(- n) name]) (into [] big-in-brazil))]
    (println (format "%-28s %d" name n)))
  (println "countries:" (size revenue))
  (doseq [[c t] (take 3 (sort-by (comp - second) (into [] revenue)))]
    (println (format "%-16s %.2f" c t)))
  (println "artists with albums:" (size (-> artist (where artist-albums))))
  (println "most albums:" (first (sort-by (fn [[_ [n _]]] (- n)) (into [] (-> artist-albums (fold 0 (fn [n _] (inc n))) (both artist-name))))))
  (println "nil group key:" (into [] (-> (ids [10 11 12]) (group (column [0 nil 0])) (gather))))
  (bench "per-artist, materialized once" #(size per-artist))
  (bench "per-artist, folded per call  " #(size (-> brazil-lines (group line-artist) (fold 0 (fn [n _] (inc n))))))
  (bench "revenue, folded per call     " #(size (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0M +))))
  (let [got (into {} distinct-tracks-per-country)
        oracle (into {} (map (juxt :country :n))
                     (next.jdbc/execute! ch/ds ["select c.Country as country, count(distinct il.TrackId) as n from InvoiceLine il join Invoice i using(InvoiceId) join Customer c using(CustomerId) group by c.Country"]
                                         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (doseq [c ["USA" "Brazil" "Germany"]] (println "distinct tracks bought," c (got c) "sqlite" (oracle c)))
    (println "all countries match sqlite:" (= got oracle) (count got)))
  (bench "distinct-count per call      " #(size (-> line (group (-> line-invoice (s invoice-customer) (s country))) (s line-track) (distinct-count))))
  (println "InvoiceDate class:" (class (first (map :invoicedate ch/invoice-rows))))
  (println "(min \"a\" \"b\") ->" (try (min "a" "b") (catch Throwable e (.getSimpleName (class e)))))
  (doseq [cutoff ["2010-01-01" "2010-12-31" "2009-12-31"]]
    (let [got (into {} (first-order-after cutoff))
          oracle (into {} (map (juxt :country :d))
                       (next.jdbc/execute! ch/ds ["select c.Country as country, min(i.InvoiceDate) as d from Invoice i join Customer c using(CustomerId) group by c.Country having min(i.InvoiceDate) > ?" cutoff]
                                           {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
      (println "first order >" cutoff ":" (count got) "countries, match sqlite:" (= got oracle) (into (sorted-map) got))))
  (bench "first-order-after, fold cmin " #(size (first-order-after "2010-01-01")))
  (bench "same via gather + fmap min   " #(size (-> invoice (group (s invoice-customer country)) (s invoice-date) (gather) (fmap (fn [ds] (reduce cmin ds))) (filt (fn [d] (pos? (compare d "2010-01-01")))))))
  (let [h (into {} revenue) sf (into {} (sorted-fold revenue-sorted 0M +))]
    (println "sorted-fold == fold (same keys, sums within 1e-9):" (and (= (set (keys h)) (set (keys sf)))
                                                                         (every? #(< (Math/abs (- (h %) (sf %))) 1e-9) (keys h))))
    (println "sorted-fold first 3 keys:" (map first (take 3 (into [] (sorted-fold revenue-sorted 0M +))))))
  (bench "hash fold, per call          " #(size (fold revenue-stream 0M +)))
  (bench "sorted-fold, presorted input " #(size (sorted-fold revenue-sorted 0M +)))
  (bench "sort-by-key + sorted-fold    " #(size (sorted-fold (sort-by-key revenue-stream) 0M +)))
  (bench "sort-by-key alone            " #(size (sort-by-key revenue-stream)))
  (println "dense == map fold:" (= (into {} per-artist) (into {} per-artist-dense)) (size per-artist-dense) "groups of" (count ch/artist-rows) "slots")
  (bench "per-artist, dense per call   " #(size (-> brazil-lines (group line-artist) (dense-fold 275 0 (fn [n _] (inc n))))))
  (bench "per-artist, map per call     " #(size (-> brazil-lines (group line-artist) (fold 0 (fn [n _] (inc n))))))
  (println "Total class from jdbc:" (class (:total (first ch/invoice-rows))) "(bigdec 0.99) =" (bigdec 0.99) "(bigdec 1.98) =" (bigdec 1.98))
  (let [usa (get (into {} revenue) "USA")
        oracle (:s (next.jdbc/execute-one! ch/ds ["select sum(cast(round(i.Total*100) as integer)) as s from Invoice i join Customer c using(CustomerId) where c.Country='USA'"]
                                            {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (println "USA total:" usa (class usa) "== 523.06M:" (= usa 523.06M) "oracle cents:" oracle (= usa (/ (bigdec oracle) 100M))))
  (println "all 24 totals exact to 2 dp:" (every? (fn [[_ t]] (= t (.setScale ^BigDecimal t 2))) (into [] revenue))))
