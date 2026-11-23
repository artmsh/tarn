(ns tarn.part3
  (:require [next.jdbc] [next.jdbc.result-set] [tarn.cps :refer [s both eq filt where column ids size inv index group fold gather fmap distinct-count cmin]]
            [tarn.part2 :refer [artist line artist-name album-artist track-name track-album
                                country invoice-customer line-invoice line-track brazilian bench]]
            [tarn.chinook :as ch]))

(def invoice       (tarn.part2/id ch/invoice-rows :invoiceid))
(def invoice-total (tarn.part2/col ch/invoice-rows :total))

(def brazil-lines (-> line (where (-> line-invoice (s brazilian)))))
(def line-artist  (-> line-track (s track-album) (s album-artist)))

(def per-artist
  (-> brazil-lines (group line-artist) (fold 0 (fn [n _] (inc n)))))

(def big-in-brazil
  (-> per-artist (filt #(>= % 9)) (both artist-name)))

(def revenue
  (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0.0 +)))

(def distinct-tracks-per-country
  (-> line (group (-> line-invoice (s invoice-customer) (s country))) (s line-track) (distinct-count)))

(def invoice-date (tarn.part2/col ch/invoice-rows :invoicedate))

(defn first-order-after
  "Per country, the earliest InvoiceDate (fold with cmin), keeping those later than cutoff."
  [cutoff]
  (-> invoice (group (s invoice-customer country)) (s invoice-date)
      (fold nil (fn [m d] (if (nil? m) d (cmin m d))))
      (filt #(pos? (compare % cutoff)))))

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
  (bench "revenue, folded per call     " #(size (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0.0 +))))
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
  (bench "same via gather + fmap min   " #(size (-> invoice (group (s invoice-customer country)) (s invoice-date) (gather) (fmap (fn [ds] (reduce cmin ds))) (filt (fn [d] (pos? (compare d "2010-01-01"))))))))
