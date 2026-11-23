(ns tarn.part2
  (:require [tarn.cps :as cps :refer [s both eq filt where column ids dense-ids many size from-pairs exists?]]
            [tarn.core :as core]
            [tarn.chinook :as ch]
            [tarn.part1 :as p1]))

(defn col [rows k] (column (mapv k rows)))
(defn dense!
  "Chinook ids are row# + 1. Check it once (a scan, no allocation) so the
   arithmetic below is a fact about this file, not a hope."
  [rows k]
  (dotimes [i (count rows)]
    (assert (= (k (nth rows i)) (inc i)) (str "id not dense at row " i)))
  rows)

(defn id  [rows k] (dense-ids (count (dense! rows k))))
(defn fk  [rows k target-rows target-k]
  (dense! target-rows target-k)
  (column (mapv (comp dec k) rows)))

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
  (let [_ (dense! ch/playlist-rows :playlistid)
        _ (dense! ch/track-rows :trackid)]
    (many (map (fn [row] [(dec (:playlistid row)) (dec (:trackid row))]) ch/playlist-track-rows))))

(def brazilian
  (-> invoice-customer (s country) (eq "Brazil")))

(def bought-in-brazil
  (-> line
      (where (-> line-invoice (s brazilian)))
      (s (both (-> line-track (s track-name))
               (-> line-track (s track-album) (s album-artist) (s artist-name))))))

;; Same inputs through Part 1's core/where and Part 2's cps/where.
;; Inputs are Part 1 relations (seqs of pairs); the Part 2 side wraps each in from-pairs.
(defn check-where-agrees []
  (let [brazil (core/eq (core/s ch/invoice-customer ch/country) "Brazil")]
    (doseq [[label r t] [["line-invoice | brazilian"     ch/line-invoice brazil]
                         ["line-track | track-album"     ch/line-track ch/track-album]
                         ["playlist-tracks | line-track" ch/playlist-tracks ch/line-track]
                         ["track-album | empty"          ch/track-album []]]]
      (let [p1-res (core/where r t)
            p2-res (into [] (where (from-pairs r) (from-pairs t)))]
        (assert (= (set p1-res) (set p2-res)) label)
        (assert (= (count p1-res) (count p2-res)) label)
        (println (format "where agrees: %-30s %5d pairs (set =, count =)" label (count p1-res)))))))

;; ex2-4: did anyone in Brazil ever buy a Deep Purple track?
(defn by-artist [q artist] (filt q (fn [[_ a]] (= a artist))))

(defn- counting
  "Wrap a leaf's drive with a counter: how many rows the scan visited."
  [q n]
  (assoc q :drive (fn [k] ((:drive q) (fn [x y] (swap! n inc) (k x y))))))

(defn bought-in-brazil-via [line*]
  (-> line*
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

(defn deep-purple-demo []
  (let [n (atom 0)
        q (by-artist (bought-in-brazil-via (counting line n)) "Deep Purple")]
    (reset! n 0) (println "size    :" (size q) "rows -> lines scanned:" @n)
    (reset! n 0) (println "exists? :" (exists? q) "-> lines scanned:" @n)
    (println "absent artist (expect false):" (exists? (by-artist bought-in-brazil "Nobody Band")))
    (bench "size (Deep Purple)   " #(size (by-artist bought-in-brazil "Deep Purple")))
    (bench "exists? (Deep Purple)" #(exists? (by-artist bought-in-brazil "Deep Purple")))))

(defn -main [& _]
  (check-where-agrees)
  (deep-purple-demo)
  (println "brazilian invoices:" (size brazilian))
  (let [res (into [] bought-in-brazil)]
    (println "lines bought in Brazil:" (count res))
    (doseq [p (take 3 (sort-by first res))] (println p))
    (println "distinct artists:" (count (into #{} (map (comp second second)) bought-in-brazil))))
  (println "tracks on Brazilian Music:"
           (size (-> playlist (where (eq playlist-name "Brazilian Music")) (s playlist-tracks) (s track-name))))
  (println "first 2 pairs, reduced early:" (into [] (take 2) bought-in-brazil))
  (bench "take 2 (reduced early)" #(into [] (take 2) bought-in-brazil))
  (bench "part 1, seqs + indexes" #(count (vec (p1/bought-in-brazil))))
  (bench "part 2, drive + probe  " #(size bought-in-brazil)))
