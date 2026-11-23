(ns tarn.part3
  (:require [tarn.cps :refer [s both eq filt where column ids size inv index group fold gather fmap sorted-fold sort-by-key from-pairs-sorted]]
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
  (bench "revenue, folded per call     " #(size (-> invoice (group (s invoice-customer country)) (s invoice-total) (fold 0.0 +))))
  (let [h (into {} revenue) sf (into {} (sorted-fold revenue-sorted 0.0 +))]
    (println "sorted-fold == fold (same keys, sums within 1e-9):" (and (= (set (keys h)) (set (keys sf)))
                                                                         (every? #(< (Math/abs (- (h %) (sf %))) 1e-9) (keys h))))
    (println "sorted-fold first 3 keys:" (map first (take 3 (into [] (sorted-fold revenue-sorted 0.0 +))))))
  (bench "hash fold, per call          " #(size (fold revenue-stream 0.0 +)))
  (bench "sorted-fold, presorted input " #(size (sorted-fold revenue-sorted 0.0 +)))
  (bench "sort-by-key + sorted-fold    " #(size (sorted-fold (sort-by-key revenue-stream) 0.0 +)))
  (bench "sort-by-key alone            " #(size (sort-by-key revenue-stream))))
