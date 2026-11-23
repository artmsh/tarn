(ns tarn.like
  "SQL LIKE as a regex. Semantics chosen: SQLite's own. `%` = any run (including
   empty), `_` = exactly one character, everything else literal, matched against
   the whole string, case-insensitive for ASCII only (SQLite folds only ASCII
   unless ICU is loaded). Java's (?i) without UNICODE_CASE is the same fold.
   `(?s)` so `.` crosses newlines, as `%` does. No default ESCAPE character,
   as in SQLite.")

(defn like->regex [^String pat]
  (let [sb (StringBuilder. "(?is)")]
    (loop [i 0 lit (StringBuilder.)]
      (let [flush! (fn [] (when (pos? (.length lit))
                            (.append sb (java.util.regex.Pattern/quote (str lit)))
                            (.setLength lit 0)))]
        (if (= i (count pat))
          (flush!)
          (let [c (.charAt pat i)]
            (case c
              \% (do (flush!) (.append sb ".*"))
              \_ (do (flush!) (.append sb "."))
              (.append lit c))
            (recur (inc i) lit)))))
    (re-pattern (str sb))))

(defn like-pred
  "A predicate with SQL LIKE semantics. NULL (nil) is never LIKE anything."
  [pat]
  (let [re (like->regex pat)]
    (fn [v] (and (some? v) (boolean (re-matches re v))))))
