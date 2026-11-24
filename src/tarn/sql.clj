(ns tarn.sql
  "Interpreter 2: print a tarn tree as SQL. Every node becomes one subquery
   with columns x, v1 .. vn."
  (:require [clojure.string :as str]))

(defn- vals* [n] (mapv #(str "v" (inc %)) (range n)))

(defn- column-expr
  "A leaf's :key or :val: one column, or a vector of columns for a composite.
   A composite is printed as one value: by default its columns joined by
   commas, text; with the leaf's :composite :packed, two integer columns packed
   into one integer, (a << 32) | b, which joins faster than text. Packing is
   exact only for non-negative integers below 2^32; tarn doesn't check."
  [c composite]
  (cond (not (vector? c)) c
        (= :packed composite)
        (do (when-not (= 2 (count c))
              (throw (ex-info "a :packed composite has two columns" {:columns c})))
            (format "((%s << 32) | %s)" (first c) (second c)))
        :else (str "(" (str/join " || ',' || " c) ")")))

(def fns
  "The value functions fmap can name, as SQL templates over the value."
  {:nil? "(CASE WHEN %s IS NULL THEN 1 ELSE 0 END)"})

(defn- one! [op {:keys [n] :as r}]
  (when-not (= 1 n)
    (throw (ex-info (str op " takes a relation with one value column, not " n) {:op op :n n})))
  r)

(def ^:dynamic *where-style* "How `where` prints: :exists (default) or :in (Part 4 as written)." :exists)

(declare emit)

(def ^:private materialized
  "Operators whose subquery ends in LIMIT -1, which SQLite never flattens into
   the query above. Flattened, a tree with a few joins becomes one many-way join
   whose order the planner can get badly wrong: vitrine's covers, 15 leaves over
   a 187,000-row junction table, ran past 60 s; materialized, each join gets an
   automatic index and the whole tree runs in 1.3 s."
  #{:s :both :inv :where :coalesce})

(defn- sub [schema node params]
  (let [{:keys [sql n]} (emit schema node params)]
    {:sql (if (and (vector? node) (materialized (first node)))
            (str "(" sql " LIMIT -1)")
            (str "(" sql ")"))
     :n n}))

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
    (let [{:keys [table key val composite]} (schema node)]
      ;; A composite leaf ends in LIMIT -1, which keeps SQLite from flattening it
      ;; into the join above. Flattened, the join is on a concatenation that no
      ;; index can serve and runs as a nested loop (8.7 s on Chinook's 8,715
      ;; PlaylistTrack rows); materialized, it gets an automatic index (12 ms).
      {:sql (format "SELECT %s AS x, %s AS v1 FROM %s%s" (column-expr key composite) (column-expr val composite) table
                    (if (or (vector? key) (vector? val)) " LIMIT -1" ""))
       :n 1})
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
        :filt  (let [r (sub schema a params) [pop pat] b]
                 (case pop
                   :like (do (swap! params conj pat)
                             {:sql (format "SELECT * FROM %s AS r WHERE r.v1 LIKE ?" (:sql r)) :n (:n r)})))
        :gt    (let [r (sub schema a params)] (swap! params conj b)
                 {:sql (format "SELECT * FROM %s AS r WHERE r.v1 > ?" (:sql r)) :n (:n r)})
        :where (let [r (sub schema a params) t (sub schema b params)]
                 {:sql (format (case *where-style*
                                 :exists "SELECT * FROM %s AS r WHERE EXISTS (SELECT 1 FROM %s AS t WHERE t.x = r.v1)"
                                 :in     "SELECT * FROM %s AS r WHERE r.v1 IN (SELECT t.x FROM %s AS t)")
                               (:sql r) (:sql t))
                  :n (:n r)})
        :group (let [r (sub schema a params) t (sub schema b params)]
                 {:sql (format "SELECT t.v1 AS x, r.v1 AS v1 FROM %s AS r JOIN %s AS t ON r.v1 = t.x" (:sql r) (:sql t))
                  :n 1})
        :fold  (let [q (sub schema a params)]
                 (if (= :argmin (first b))
                   ;; the v1 whose v2 is least, per key; ties go to the least v1
                   (do (when-not (= 2 (:n q))
                         (throw (ex-info "argmin folds [arg order] pairs: two value columns" {:n (:n q)})))
                       {:sql (format "SELECT x, v1 FROM (SELECT q.x AS x, q.v1 AS v1, row_number() OVER (PARTITION BY q.x ORDER BY q.v2, q.v1) AS rn FROM %s AS q) WHERE rn = 1" (:sql q))
                        :n 1})
                   (let [agg (case (first b) :count "COUNT(*)" :sum "SUM(q.v1)" :min "MIN(q.v1)")]
                     {:sql (format "SELECT q.x AS x, %s AS v1 FROM %s AS q GROUP BY q.x" agg (:sql q)) :n 1})))
        :inv   (let [r (one! :inv (sub schema a params))]
                 {:sql (format "SELECT r.v1 AS x, r.x AS v1 FROM %s AS r" (:sql r)) :n 1})
        :coalesce (let [r (one! :coalesce (sub schema a params)) t (one! :coalesce (sub schema b params))]
                    {:sql (format "SELECT r.x AS x, coalesce(r.v1, t.v1) AS v1 FROM %s AS r LEFT JOIN %s AS t ON r.x = t.x AND r.v1 IS NULL" (:sql r) (:sql t))
                     :n 1})
        :fmap  (let [r (one! :fmap (sub schema a params))
                     f (or (fns b) (throw (ex-info (str "unknown fmap function " b) {:f b})))]
                 {:sql (format "SELECT r.x AS x, %s AS v1 FROM %s AS r" (format f "r.v1") (:sql r)) :n 1})))))

(defn to-sql
  "A tree in, [sql & params] out, ready for next.jdbc."
  [schema tree]
  (let [params (atom [])
        {:keys [sql]} (emit schema tree params)]
    (into [sql] @params)))
