(ns tarn.cse
  "Part 4, exercise 4: common subexpressions in a tree. A tree is data, so
   equal subtrees are equal values; closures can't be compared at all."
  (:require [clojure.string :as str]))

(defn nodes
  "Every subtree of tree with its path, preorder. Leaves (keywords) included."
  ([tree] (nodes tree []))
  ([tree path]
   (cons [path tree]
         (when (vector? tree)
           (let [[_ a b] tree]
             (concat (nodes a (conj path 1))
                     (when (or (vector? b) (keyword? b)) (nodes b (conj path 2)))))))))

(defn- operand? [x] (or (vector? x) (keyword? x)))

(defn nodes* [tree path]
  (cons [path tree]
        (when (vector? tree)
          (let [[op a b] tree]
            (concat (when (operand? a) (nodes* a (conj path :l)))
                    ;; the third slot of eq/ge/gt/filt/fold is data, not a subtree
                    (when (and (operand? b) (#{:s :both :where :group} op)) (nodes* b (conj path :r))))))))

(defn repeats
  "Subtrees that occur at least twice, as {subtree [path ...]}. Occurrences
   inside a repeated subtree's later copies aren't counted again (the outer
   repeat already implies them). Leaves are reported only when leaves? is true."
  [forest leaves?]
  (let [all (for [[nm tree] forest [p n] (nodes* tree [])
                  :when (or leaves? (vector? n))]
              [n [nm p]])
        by (reduce (fn [m [n loc]] (update m n (fnil conj []) loc)) {} all)]
    (into {} (filter (fn [[_ locs]] (>= (count locs) 2))) by)))

(defn maximal
  "Drop a repeat whose every occurrence lies inside an occurrence of another
   repeat that is strictly bigger."
  [reps]
  (let [inside? (fn [[nm p] [nm2 p2]] (and (= nm nm2) (< (count p2) (count p)) (= p2 (subvec p 0 (count p2)))))]
    (into {} (remove (fn [[_ locs]]
                       (every? (fn [loc] (some (fn [[_ ls2]] (some #(inside? loc %) ls2)) (dissoc reps nil)))
                               locs)))
          reps)))

(defn shared-left
  "Groups of two or more distinct :s/:both nodes with the same left operand:
   the shape `[:s X a]`, `[:s X b]`. Not equal subtrees, but X is computed
   once per node, so X is what a common-subexpression rewrite would share."
  [forest]
  (let [all (for [[nm tree] forest [p n] (nodes* tree [])
                  :when (and (vector? n) (#{:s :both} (first n)))]
              [(second n) [nm p n]])
        by (reduce (fn [m [x loc]] (update m x (fnil conj []) loc)) {} all)]
    (into {} (filter (fn [[_ locs]] (>= (count (distinct (map #(nth % 2) locs))) 2))) by)))

(defn cse
  "Walk a forest ({name tree}), report what repeats. Returns a map; prints nothing."
  [forest]
  {:equal-subtrees (maximal (repeats forest false))
   :equal-leaves   (repeats forest true)
   :shared-left    (shared-left forest)})

;; ---- the same question one level down: what does SQLite see? ---------------

(defn paren-groups
  "Every balanced `(SELECT ...)` substring of a SQL text."
  [^String sql]
  (loop [i 0 stack [] out []]
    (if (= i (count sql))
      out
      (let [c (.charAt sql i)]
        (cond (and (= c \() (.startsWith sql "(SELECT" i)) (recur (inc i) (conj stack i) out)
              (= c \() (recur (inc i) (conj stack -1) out)
              (= c \)) (let [st (peek stack)]
                         (recur (inc i) (pop stack) (if (neg? st) out (conj out (subs sql st (inc i))))))
              :else (recur (inc i) stack out))))))

(defn repeated-sql [sql]
  (->> (paren-groups sql) frequencies (filter (fn [[_ n]] (>= n 2))) (sort-by (comp count key))))
