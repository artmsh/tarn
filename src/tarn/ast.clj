(ns tarn.ast
  "Part 4: the same names as tarn.cps, but they build a tree instead of closures.
   Refer this namespace instead of tarn.cps and the query text doesn't change.")

(defn s     [r t] [:s r t])
(defn both  [r t] [:both r t])
(defn eq    [r v] [:eq r v])
(defn ge    [r v] [:ge r v])
(defn where [r t] [:where r t])
(defn group [r t] [:group r t])
(defn fold  [q agg] [:fold q agg])
