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

(def ^:private ops #{:s :both :eq :ge :where :group :fold :inv :coalesce :fmap})

(defn nodes
  "One per SELECT the printer will emit: every operator and every leaf."
  [tree]
  (cond (keyword? tree) 1
        (and (vector? tree) (ops (first tree)))
        (+ 1 (reduce + (map nodes (filter #(or (keyword? %) (vector? %)) (rest tree)))))
        :else 0))
