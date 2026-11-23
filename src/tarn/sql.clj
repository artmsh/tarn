(ns tarn.sql
  "Interpreter 2: print a tarn tree as SQL. Every node becomes one subquery
   with columns x, v1 .. vn."
  (:require [clojure.string :as str]))

(defn- vals* [n] (mapv #(str "v" (inc %)) (range n)))

(declare emit)

(defn- sub [schema node params]
  (let [{:keys [sql n]} (emit schema node params)]
    {:sql (str "(" sql ")") :n n}))

(defn- head
  "A side of a both as [leaf-entry tail]: a bare leaf, or [:s leaf tail]."
  [schema node]
  (cond (keyword? node) [(schema node) nil]
        (and (= :s (first node)) (keyword? (second node))) [(schema (second node)) (nth node 2)]))

(defn- same-table?
  "Both heads read the same table by the same key, and that key is unique
   there (:pk), so a join of the two on x pairs each row with itself only."
  [ha hb]
  (and ha hb
       (every? :pk [ha hb])
       (= (:table ha) (:table hb))
       (= (:key ha) (:key hb))))

(defn- one-scan
  "[:both a b] where a and b each start at a leaf of the same table: one
   SELECT over that table, qualified by the table's own name so that the plan
   says SEARCH Track and not SEARCH r. A tail (the t of [:s leaf t]) is joined on the
   leaf's value column. Returns nil when the rule does not apply."
  [schema a b params]
  (let [[ha ta] (head schema a) [hb tb] (head schema b)]
    (when (same-table? ha hb)
      (let [side (fn [h tail i]
                   (if tail
                     (let [{:keys [sql n]} (sub schema tail params) al (str "t" i)]
                       {:join (format " JOIN %s AS %s ON %s.%s = %s.x" sql al (:table h) (:val h) al)
                        :cols (map #(str al "." % ) (vals* n))})
                     {:join "" :cols [(str (:table h) "." (:val h))]}))
            sa (side ha ta 1) sb (side hb tb 2)
            cols (map-indexed (fn [i c] (str c " AS v" (inc i))) (concat (:cols sa) (:cols sb)))]
        {:sql (format "SELECT %s.%s AS x, %s FROM %s%s%s"
                      (:table ha) (:key ha) (str/join ", " cols) (:table ha) (:join sa) (:join sb))
         :n (count cols)}))))

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
        :both  (or (one-scan schema a b params)
                   (let [r (sub schema a params) t (sub schema b params)
                     rc (map #(str "r." % " AS " %) (vals* (:n r)))
                     tc (map-indexed (fn [i c] (str "t." c " AS v" (+ (:n r) i 1))) (vals* (:n t)))]
                 {:sql (format "SELECT r.x AS x, %s FROM %s AS r JOIN %s AS t ON r.x = t.x"
                               (str/join ", " (concat rc tc)) (:sql r) (:sql t))
                  :n (+ (:n r) (:n t))}))
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
