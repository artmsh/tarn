(ns tarn.part3
  (:require [next.jdbc] [next.jdbc.result-set] [tarn.cps :refer [s both eq filt where column ids size inv index group fold gather fmap]]
            [tarn.part2 :refer [artist line artist-name album-artist track-name track-album
                                country invoice-customer line-invoice line-track brazilian bench]]
            [tarn.chinook :as ch]))

(def invoice       (tarn.part2/id ch/invoice-rows :invoiceid))
(def invoice-total (tarn.part2/col ch/invoice-rows (comp bigdec :total)))  ; Double -> BigDecimal at load

(def brazil-lines (-> line (where (-> line-invoice (s brazilian)))))
(def line-artist  (-> line-track (s track-album) (s album-artist)))

(def per-artist
  (-> brazil-lines (group line-artist) (fold 0 (fn [n _] (inc n)))))

(def big-in-brazil
  (-> per-artist (filt #(>= % 9)) (both artist-name)))

(def revenue
  (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0M +)))

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
  (println "Total class from jdbc:" (class (:total (first ch/invoice-rows))) "(bigdec 0.99) =" (bigdec 0.99) "(bigdec 1.98) =" (bigdec 1.98))
  (let [usa (get (into {} revenue) "USA")
        oracle (:s (next.jdbc/execute-one! ch/ds ["select sum(cast(round(i.Total*100) as integer)) as s from Invoice i join Customer c using(CustomerId) where c.Country='USA'"]
                                            {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps}))]
    (println "USA total:" usa (class usa) "== 523.06M:" (= usa 523.06M) "oracle cents:" oracle (= usa (/ (bigdec oracle) 100M))))
  (println "all 24 totals exact to 2 dp:" (every? (fn [[_ t]] (= t (.setScale ^BigDecimal t 2))) (into [] revenue))))
