(ns tarn.run
  "Interpreter 1: a tree in, Part 2/3 closures out."
  (:require [tarn.cps :as cps]
            [tarn.like :as like]))

(def fns
  "The value functions fmap can name, as Clojure functions. Same names and
   results as tarn.sql/fns."
  {:nil? #(if (nil? %) 1 0)})

(defn compile*
  "rels maps leaf keywords to loaded Query leaves."
  [rels node]
  (if (keyword? node)
    (or (rels node) (throw (ex-info (str "unknown relation " node) {:node node})))
    (let [[op a b] node
          c #(compile* rels %)]
      (case op
        :s     (cps/s (c a) (c b))
        :both  (cps/both (c a) (c b))
        :eq    (cps/eq (c a) b)
        :ge    (cps/filt (c a) #(>= % b))
        :filt  (case (first b)
                 :like (cps/filt (c a) (like/like-pred (second b))))
        :gt    (cps/filt (c a) #(pos? (compare % b)))   ; compare, not >: values may be strings
        :where (cps/where (c a) (c b))
        :group (cps/group (c a) (c b))
        :fold  (case (first b)
                 :count  (cps/fold (c a) 0 (fn [n _] (inc n)))
                 :sum    (cps/fold (c a) 0.0 +)
                 ;; clojure.core/min throws on strings; nil is the empty-group marker
                 :min    (cps/fold (c a) nil (fn [m d] (cond (nil? m) d (neg? (compare d m)) d :else m)))
                 :argmin (cps/argmin (c a)))
        :inv   (cps/index (cps/inv (c a))) ; materialized, so it can be probed
        :coalesce (cps/coalesce (c a) (c b))
        :fmap  (cps/fmap (c a) (or (fns b) (throw (ex-info (str "unknown fmap function " b) {:f b}))))))))

(defn- ref-of
  "Which table a node's values are ids of, per the schema. nil if not a key."
  [schema node]
  (if (keyword? node)
    (:ref (schema node))
    (let [[op a b] node]
      (case op
        (:s :group) (ref-of schema b)
        (:eq :ge :gt :filt :where :fold :coalesce) (ref-of schema a)
        (:both :inv :fmap) nil))))

(defn key-ref
  "Which table's rows the closure result is keyed by. nil when the keys are
   already ids, i.e. the tree is rooted at an entry relation."
  [schema node]
  (if (keyword? node)
    (let [{:keys [table entry]} (schema node)] (when-not entry table))
    (let [[op a b] node]
      (case op
        :group (ref-of schema b)
        :inv   (ref-of schema a)
        (key-ref schema a)))))

(defn- rekey
  "Drive q, translating each key through f. Exit-only: nothing downstream
   may probe it, because downstream still speaks row numbers."
  [q f]
  (cps/->Query (fn [k] (cps/drive q (fn [x y] (k (f x) y)))) nil nil))

(defn run
  "A tree, its leaves, and per-table row -> id vectors. Keys come out as ids,
   the way SQL would print them."
  [schema rels ids tree]
  (let [q (compile* rels tree)]
    (if-some [table (key-ref schema tree)]
      (rekey q (ids table))
      q)))
