(ns tarn.part2
  (:require [tarn.cps :refer [s both eq where column ids many size]]
            [tarn.chinook :as ch]
            [tarn.part1 :as p1]))

(defn col [rows k] (column (mapv k rows)))
(defn id  [rows k] (ids (mapv k rows)))
(defn fk  [rows k target-rows target-k]
  (let [id->row (zipmap (map target-k target-rows) (range))]
    (column (mapv (comp id->row k) rows))))

(def artist   (id ch/artist-rows :artistid))
(def line     (id ch/line-rows :invoicelineid))
(def playlist (id ch/playlist-rows :playlistid))

(def artist-name  (col ch/artist-rows :name))
(def album-artist (fk ch/album-rows :artistid ch/artist-rows :artistid))
(def track-name   (col ch/track-rows :name))
(def track-album  (fk ch/track-rows :albumid ch/album-rows :albumid))
(def country      (col ch/customer-rows :country))
(def invoice-customer (fk ch/invoice-rows :customerid ch/customer-rows :customerid))
(def line-invoice (fk ch/line-rows :invoiceid ch/invoice-rows :invoiceid))
(def line-track   (fk ch/line-rows :trackid ch/track-rows :trackid))
(def playlist-name (col ch/playlist-rows :name))
(def playlist-tracks
  (let [pl (zipmap (map :playlistid ch/playlist-rows) (range))
        tr (zipmap (map :trackid ch/track-rows) (range))]
    (many (map (fn [row] [(pl (:playlistid row)) (tr (:trackid row))]) ch/playlist-track-rows))))

(def brazilian
  (-> invoice-customer (s country) (eq "Brazil")))

(def bought-in-brazil
  (-> line
      (where (-> line-invoice (s brazilian)))
      (s (both (-> line-track (s track-name))
               (-> line-track (s track-album) (s album-artist) (s artist-name))))))

(def ^:private mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn- allocated [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (.getId (Thread/currentThread))))

(defn bench [label f]
  (dotimes [_ 50] (f))
  (let [a0 (allocated) t0 (System/nanoTime)]
    (dotimes [_ 100] (f))
    (println (format "%s: %.2f ms, %,d bytes allocated per run" label
                     (/ (- (System/nanoTime) t0) 100 1e6)
                     (quot (- (allocated) a0) 100)))))

(defn -main [& _]
  (println "brazilian invoices:" (size brazilian))
  (let [res (into [] bought-in-brazil)]
    (println "lines bought in Brazil:" (count res))
    (doseq [p (take 3 (sort-by first res))] (println p))
    (println "distinct artists:" (count (into #{} (map (comp second second)) bought-in-brazil))))
  (println "tracks on Brazilian Music:"
           (size (-> playlist (where (eq playlist-name "Brazilian Music")) (s playlist-tracks) (s track-name))))
  (println "first 2 pairs, reduced early:" (into [] (take 2) bought-in-brazil))
  (bench "part 1, seqs + indexes" #(count (vec (p1/bought-in-brazil))))
  (bench "part 2, drive + probe  " #(size bought-in-brazil)))
