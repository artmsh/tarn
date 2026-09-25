(ns tarn.core)

(defn index
  "Turn a relation into a lookup: {y [z z ...]} keeps every pair,
   so many-valued relations survive."
  [t]
  (persistent!
   (reduce (fn [m [y z]] (assoc! m y (conj (get m y []) z)))
           (transient {}) t)))

(defn s
  "Composition. r maps x->y, t maps y->z; result maps x->z."
  [r t]
  (let [idx (index t)]
    (for [[x y] r, z (idx y)] [x z])))

(defn both
  "Product on the key. r maps x->y, t maps x->z; result maps x->[y z]."
  [r t]
  (let [idx (index t)]
    (for [[x y] r, z (idx x)] [x [y z]])))

(defn eq
  "Predicate: keep pairs whose value is v."
  [r v]
  (filter (fn [[_ y]] (= y v)) r))

(defn where
  "Restriction (left semijoin): keep pairs of r whose value is a key of t."
  [r t]
  (let [keys (into #{} (map first) t)]
    (filter (fn [[_ y]] (contains? keys y)) r)))
