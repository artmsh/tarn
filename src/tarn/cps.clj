(ns tarn.cps
  "Part 2: the same four operators in continuation-passing style.
   A query no longer returns pairs. It is driven, or probed."
  (:import (clojure.lang IReduceInit)))

(defrecord Query [drive probe member]
  IReduceInit
  (reduce [_ f init]
    (let [acc (volatile! init)]
      (drive (fn [x y]
               (when-not (reduced? @acc)
                 (vswap! acc f [x y]))))
      (unreduced @acc))))

(defn drive   [q k]   ((:drive q) k))
(defn probe   [q x k] ((:probe q) x k))
(defn member? [q x]   ((:member q) x))

(defn- probe-any
  "true if any value under x satisfies pred."
  [q x pred]
  (let [hit (volatile! false)]
    (probe q x (fn [y] (when (pred y) (vreset! hit true))))
    @hit))

(defn column
  "row# -> value, backed by the vector itself."
  [v]
  (let [n (count v)
        in? (fn [x] (and (int? x) (< -1 x n)))]
    (->Query (fn [k] (dotimes [i n] (k i (nth v i))))
             (fn [x k] (when (in? x) (k (nth v x))))
             in?)))

(defn ids
  "id -> row#. The one map we build, and we build it at load, not per query."
  [id-vec]
  (let [n (count id-vec)
        id->row (zipmap id-vec (range))]
    (->Query (fn [k] (dotimes [i n] (k (nth id-vec i) i)))
             (fn [x k] (when-some [r (id->row x)] (k r)))
             (fn [x] (contains? id->row x)))))

(defn many
  "key -> several values, from pairs. Indexed once."
  [pairs]
  (let [idx (persistent!
             (reduce (fn [m [y z]] (assoc! m y (conj (get m y []) z)))
                     (transient {}) pairs))]
    (->Query (fn [k] (doseq [[y zs] idx, z zs] (k y z)))
             (fn [x k] (doseq [z (idx x)] (k z)))
             (fn [x] (contains? idx x)))))

(defn s
  "Composition. Drive the left side, probe the right."
  [r t]
  (->Query (fn [k] (drive r (fn [x y] (probe t y (fn [z] (k x z))))))
           (fn [x k] (probe r x (fn [y] (probe t y k))))
           (fn [x] (probe-any r x #(member? t %)))))

(defn both
  "Product on the key. Drive the left side, probe the right at the same key."
  [r t]
  (->Query (fn [k] (drive r (fn [x y] (probe t x (fn [z] (k x [y z]))))))
           (fn [x k] (probe r x (fn [y] (probe t x (fn [z] (k [y z]))))))
           (fn [x] (and (member? r x) (member? t x)))))

(defn filt
  "General predicate on the value."
  [r pred]
  (->Query (fn [k] (drive r (fn [x y] (when (pred y) (k x y)))))
           (fn [x k] (probe r x (fn [y] (when (pred y) (k y)))))
           (fn [x] (probe-any r x pred))))

(defn eq [r v] (filt r #(= % v)))

(defn where
  "Restriction. Keep pairs whose value is a member of t. No key set is built."
  [r t]
  (->Query (fn [k] (drive r (fn [x y] (when (member? t y) (k x y)))))
           (fn [x k] (probe r x (fn [y] (when (member? t y) (k y)))))
           (fn [x] (probe-any r x #(member? t %)))))

(defn size [q] (reduce (fn [n _] (inc n)) 0 q))

;; ---- Part 3: turning around, grouping, folding ---------------------------

(defn inv
  "Flip a relation. Drive-only: you can stream it, you can't probe it."
  [q]
  (->Query (fn [k] (drive q (fn [x y] (k y x))))
           (fn [_ _] (throw (ex-info "inv is drive-only; use (index (inv q)) to probe it" {})))
           (fn [_] (throw (ex-info "inv is drive-only; use (index (inv q)) to member it" {})))))

(defn index
  "Materialize any query into a many, once. The probe-able form of inv."
  [q]
  (many (into [] q)))

(defn group
  "r.group_by(t): drive the set r, probe the key relation t at each row,
   emit (key, row). Drive-only, like inv."
  [r t]
  (->Query (fn [k] (drive r (fn [_ x] (probe t x (fn [g] (k g x))))))
           (fn [_ _] (throw (ex-info "group is drive-only; fold it first" {})))
           (fn [_] (throw (ex-info "group is drive-only; fold it first" {})))))

(defn from-map
  "key -> value, backed by a map. What every fold returns."
  [m]
  (->Query (fn [k] (reduce-kv (fn [_ x y] (k x y) nil) nil m))
           (fn [x k] (when-some [v (find m x)] (k (val v))))
           (fn [x] (contains? m x))))

(defn fold
  "Hash aggregate: one map entry per key, op folds the values in."
  [q init op]
  (from-map
   (persistent!
    (reduce (fn [m [x y]] (assoc! m x (op (get m x init) y)))
            (transient {}) q))))

(defn gather
  "key -> vector of every value. fold with conj."
  [q]
  (fold q [] conj))

(defn fmap
  "r.map(f): apply f to every value. Named fmap because core owns map."
  [r f]
  (->Query (fn [k] (drive r (fn [x y] (k x (f y)))))
           (fn [x k] (probe r x (fn [y] (k (f y)))))
           (:member r)))
