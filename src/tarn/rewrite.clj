(ns tarn.rewrite
  "Part 5: rewrites are functions from trees to trees. Nothing here runs a query."
  (:require [clojure.walk :as walk]))

(defn- rule [f tree] (walk/postwalk (fn [n] (if (vector? n) (f n) n)) tree))

(defn reassoc
  "(r.s(a)).s(b) => r.s(a.s(b)). Composition is associative, so this is always
   valid. -> nests left; this nests right, so the leftmost leaf is exposed."
  [tree]
  (rule (fn [[op l b :as node]]
          (if (and (= op :s) (vector? l) (= :s (first l)))
            (reassoc [:s (second l) [:s (nth l 2) b]])
            node))
        tree))

(defn factor
  "r.s(a).and(r.s(b)) => r.s(a.and(b)) wherever the two left sides are equal.
   Valid only when r is functional: one value per key."
  [tree]
  (rule (fn [[op l r :as node]]
          (if (and (= op :both) (vector? l) (vector? r)
                   (= :s (first l) (first r)) (= (second l) (second r)))
            [:s (second l) [:both (nth l 2) (nth r 2)]]
            node))
        tree))

(defn push-eq
  "[:eq [:s r t] v] => [:s r [:eq t v]]. Always valid: (r.s(t))(x) is the union
   of t(y) over the y in r(x), eq keeps the values equal to v, and a filter on
   values distributes over a union, so no condition on r, t or the data holds
   it back (many-valued r, shared y, empty r(x): all the same set, and the same
   bag in SQL, where a row is kept or dropped on its own). Recurses because the
   pushed eq may sit on another :s."
  [tree]
  (rule (fn [[op l v :as node]]
          (if (and (= op :eq) (vector? l) (= :s (first l)))
            [:s (second l) (push-eq [:eq (nth l 2) v])]
            node))
        tree))

(def ^:private ops #{:s :both :eq :ge :where :group :fold})

(defn nodes
  "One per SELECT the printer will emit: every operator and every leaf."
  [tree]
  (cond (keyword? tree) 1
        (and (vector? tree) (ops (first tree)))
        (+ 1 (reduce + (map nodes (filter #(or (keyword? %) (vector? %)) (rest tree)))))
        :else 0))
