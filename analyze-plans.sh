#!/bin/sh
# ex5-5: does ANALYZE change the plans of the three generated queries?
# Never touches chinook.sqlite: ANALYZE runs on a copy. Needs the generated-*.sql
# files from `clojure -M:run5` and sqlite3 on PATH.
set -eu
cd "$(dirname "$0")"
orig=chinook.sqlite
copy=chinook-analyzed.sqlite
stat() { sqlite3 "$1" "select count(*) from sqlite_master where name like 'sqlite_stat%'"; }

rm -f "$copy"; cp "$orig" "$copy"
echo "sqlite_stat tables before: original $(stat $orig), copy $(stat $copy)"
sqlite3 "$copy" "ANALYZE"
echo "sqlite_stat tables after:  original $(stat $orig), copy $(stat $copy)"
echo "sqlite_stat1 rows: $(sqlite3 $copy 'select count(*) from sqlite_stat1')"
sqlite3 "$copy" "select tbl, idx, stat from sqlite_stat1 where tbl in ('InvoiceLine','Invoice','Customer','Track','Album','Artist') order by tbl, idx"

for f in as-written factored flipped; do
  (echo "EXPLAIN QUERY PLAN"; cat "generated-$f.sql") | sqlite3 "$orig" > "plan-$f.before.txt"
  (echo "EXPLAIN QUERY PLAN"; cat "generated-$f.sql") | sqlite3 "$copy" > "plan-$f.after.txt"
  echo "== $f"
  if diff "plan-$f.before.txt" "plan-$f.after.txt"; then echo "(plan unchanged)"; fi
done
rm -f plan-*.txt
