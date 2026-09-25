(ns tarn.chinook
  "Load Chinook from SQLite and binarize it: one relation per column."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ds (jdbc/get-datasource {:dbtype "sqlite" :dbname "chinook.sqlite"}))

(defn rows [table]
  (jdbc/execute! ds [(str "select * from " table)]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn column
  "Row number -> value. The vector *is* the relation; we just enumerate it."
  [rows k]
  (map-indexed vector (map k rows)))

(defn ids
  "Primary key -> row number. The table's entry point."
  [rows k]
  (map-indexed (fn [i row] [(k row) i]) rows))

(defn fk
  "Foreign key column, resolved to row numbers of the target table at load time."
  [rows k target-ids]
  (let [id->row (into {} target-ids)]
    (map-indexed (fn [i row] [i (id->row (k row))]) rows)))

(def artist-rows   (rows "Artist"))
(def album-rows    (rows "Album"))
(def track-rows    (rows "Track"))
(def customer-rows (rows "Customer"))
(def invoice-rows  (rows "Invoice"))
(def line-rows     (rows "InvoiceLine"))

(def artist   (ids artist-rows :artistid))
(def album    (ids album-rows :albumid))
(def track    (ids track-rows :trackid))
(def customer (ids customer-rows :customerid))
(def invoice  (ids invoice-rows :invoiceid))
(def line     (ids line-rows :invoicelineid))

(def artist-name  (column artist-rows :name))
(def album-title  (column album-rows :title))
(def album-artist (fk album-rows :artistid artist))
(def track-name   (column track-rows :name))
(def track-album  (fk track-rows :albumid album))
(def country      (column customer-rows :country))
(def invoice-customer (fk invoice-rows :customerid customer))
(def line-invoice (fk line-rows :invoiceid invoice))
(def line-track   (fk line-rows :trackid track))

(def playlist-rows (rows "Playlist"))
(def playlist-track-rows (rows "PlaylistTrack"))
(def playlist      (ids playlist-rows :playlistid))
(def playlist-name (column playlist-rows :name))
(def playlist-tracks
  (let [pl (into {} playlist) tr (into {} track)]
    (map (fn [row] [(pl (:playlistid row)) (tr (:trackid row))]) playlist-track-rows)))

