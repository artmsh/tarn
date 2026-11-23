(ns tarn.part1
  (:require [tarn.core :refer [s both eq where index filt]]
            [tarn.chinook :refer :all]))

(defn brazilian []
  (-> invoice-customer (s country) (eq "Brazil")))

(defn bought-in-brazil []
  (-> line
      (where (-> line-invoice (s (brazilian))))
      (s (both (-> line-track (s track-name))
               (-> line-track (s track-album) (s album-artist) (s artist-name))))))

(defn long-track-lines []
  (-> line
      (where (-> line-track (s (filt track-milliseconds #(> % 600000)))))))

(defn -main [& _]
  (println "lines for tracks > 10 min:" (count (long-track-lines)))
  (println "artists:" (count artist) "albums:" (count album) "tracks:" (count track))
  (println "invoice lines:" (count line) "customers:" (count customer) "invoices:" (count invoice))
  (println "brazilian invoices:" (count (brazilian)))
  (let [res (vec (bought-in-brazil))]
    (println "lines bought in Brazil:" (count res))
    (doseq [p (take 3 (sort-by first res))] (println p))
    (println "distinct artists:" (count (distinct (map (comp second second) res))))
    (println "top:" (take 3 (sort-by (juxt (comp - val) key) (frequencies (map (comp second second) res))))))
  (dotimes [_ 50] (count (vec (bought-in-brazil))))
  (let [t0 (System/nanoTime)]
    (dotimes [_ 100] (count (vec (bought-in-brazil))))
    (println (format "100 runs: %.2f ms each" (/ (- (System/nanoTime) t0) 100 1e6)))))

    
