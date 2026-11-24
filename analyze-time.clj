;; ex5-5: the three generated queries on chinook.sqlite and on chinook-analyzed.sqlite
;; (run ./analyze-plans.sh first), same JVM, interleaved, median of 7 rounds.
;; clojure -J--enable-native-access=ALL-UNNAMED -M analyze-time.clj
(require '[next.jdbc :as jdbc] '[next.jdbc.result-set :as rs])
(def plain    (jdbc/get-datasource {:dbtype "sqlite" :dbname "chinook.sqlite"}))
(def analyzed (jdbc/get-datasource {:dbtype "sqlite" :dbname "chinook-analyzed.sqlite"}))
(defn run-q [ds q] (count (jdbc/execute! ds [q] {:builder-fn rs/as-arrays})))
(defn ms [f n] (dotimes [_ (* 4 n)] (f))
  (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (/ (- (System/nanoTime) t0) n 1e6)))
(defn median [xs] (nth (sort xs) (quot (count xs) 2)))
(doseq [f ["as-written" "factored" "flipped"]]
  (let [q (slurp (str "generated-" f ".sql"))
        rounds (repeatedly 7 (fn [] [(ms #(run-q plain q) 200) (ms #(run-q analyzed q) 200)]))]
    (println (format "%-10s rows %d / %d | before %.3f ms, after ANALYZE %.3f ms"
                     f (run-q plain q) (run-q analyzed q)
                     (median (map first rounds)) (median (map second rounds))))))
