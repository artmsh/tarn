(ns tarn.sql
  "Interpreter 2: print a tarn tree as SQL. Every node becomes one subquery
   with columns x, v1 .. vn."
  (:require [clojure.string :as str]))

(defn- vals* [n] (mapv #(str "v" (inc %)) (range n)))

(declare emit)

(defn- sub [schema node params]
  (let [{:keys [sql n]} (emit schema node params)]
    {:sql (str "(" sql ")") :n n}))

(defn emit
  "Returns {:sql string :n value-column-count}. params is an atom of bind values."
  [schema node params]
  (if (keyword? node)
    (let [{:keys [table key val]} (schema node)]
      {:sql (format "SELECT %s AS x, %s AS v1 FROM %s" key val table) :n 1})
    (let [[op a b] node]
      (case op
        :s     (let [r (sub schema a params) t (sub schema b params)
                     cols (str/join ", " (map #(str "t." % " AS " %) (vals* (:n t))))]
                 {:sql (format "SELECT r.x AS x, %s FROM %s AS r JOIN %s AS t ON r.v1 = t.x" cols (:sql r) (:sql t))
                  :n (:n t)})
        :both  (let [r (sub schema a params) t (sub schema b params)
                     rc (map #(str "r." % " AS " %) (vals* (:n r)))
                     tc (map-indexed (fn [i c] (str "t." c " AS v" (+ (:n r) i 1))) (vals* (:n t)))]
                 {:sql (format "SELECT r.x AS x, %s FROM %s AS r JOIN %s AS t ON r.x = t.x"
                               (str/join ", " (concat rc tc)) (:sql r) (:sql t))
                  :n (+ (:n r) (:n t))})
        :eq    (let [r (sub schema a params)] (swap! params conj b)
                 {:sql (format "SELECT * FROM %s AS r WHERE r.v1 = ?" (:sql r)) :n (:n r)})
        :ge    (let [r (sub schema a params)] (swap! params conj b)
                 {:sql (format "SELECT * FROM %s AS r WHERE r.v1 >= ?" (:sql r)) :n (:n r)})
        :where (let [r (sub schema a params) t (sub schema b params)]
                 {:sql (format "SELECT * FROM %s AS r WHERE r.v1 IN (SELECT t.x FROM %s AS t)" (:sql r) (:sql t))
                  :n (:n r)})
        :group (let [r (sub schema a params) t (sub schema b params)]
                 {:sql (format "SELECT t.v1 AS x, r.v1 AS v1 FROM %s AS r JOIN %s AS t ON r.v1 = t.x" (:sql r) (:sql t))
                  :n 1})
        :fold  (let [q (sub schema a params)
                     agg (case (first b) :count "COUNT(*)" :sum "SUM(q.v1)")]
                 {:sql (format "SELECT q.x AS x, %s AS v1 FROM %s AS q GROUP BY q.x" agg (:sql q)) :n 1})))))

(defn to-sql
  "A tree in, [sql & params] out, ready for next.jdbc."
  [schema tree]
  (let [params (atom [])
        {:keys [sql]} (emit schema tree params)]
    (into [sql] @params)))
