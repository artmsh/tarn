# tarn features

tarn serves a Clojure programmer who queries a relational database and wants every subquery to be a value: something they can bind to a name, compose, run in-process, print as SQL, compare and rewrite. They also want the plan to be the query they wrote, not a planner's guess. The same person maintains tarn, and is its only integrator so far: [silt](https://fg.jo/art/silt) runs tarn trees under babashka, and vitrine, a photo gallery, uses silt for two of its page queries. Scope: JVM Clojure 1.12.6 with SQLite through next.jdbc 1.3.1118 / sqlite-jdbc 3.53.4.0, where both interpreters (closures and SQL) are available, and babashka through `silt.tarn`, where only the SQL interpreter is. Numbers are measured on Chinook v1.4.5 unless a line names vitrine's database.

tarn is built from the Lathe tutorial *Compositional Query DSL in Clojure* (`~/.lathe/tutorials/compositional-query-dsl-clojure`, requested in lathe-tutorials#47 "build perfect SQL DB DSL for Clojure", ref prela-lang.org). It ports Prela, Remy Wang's implementation of Tarski's Algebra of Relations (arXiv 2607.26356, remysucre/prela@96229da). Parts 1–5 are implemented. All exercises are on `main` except ex1-4, ex1-5, ex2-2 and ex4-5. `src/tarn/part6.clj` is **not** a tutorial part. It was written in a vitrine session on 2026-11-23 to give vitrine the operators it needed, and it is cited below by its commit, `fc20c92`.

Every line completes the sentence **"As a query author I want to …"**. Where the speaker is the maintainer adding to tarn, or the integrator embedding it, the line says so. Each line ends with its origin:

- a tutorial part (`P<n>`) or an exercise (`ex<n>-<m>`);
- a commit in this repository;
- a directive in a coding-agent session (`vitrine session 11-23`, `lathe-tutorials session 11-24`), with the date 2026-MM-DD;
- the Brain note `~/Brain/projects/lathe-tutorials/compositional-query-dsl-clojure-tutorial.md` (`note`).

Known limits, open contradictions and wanted-but-missing behaviour are collected at the end, so that an intention is not read as a guarantee. Numbers marked *exact* are row counts checked against the `sqlite3` CLI. Milliseconds and bytes vary between runs. Read them as ratios measured in one JVM, not as constants.

## Terms

A *relation* is a bag of `[key value]` pairs, the only data model tarn has. A *leaf* is a relation loaded from one table column; in a tree it is a keyword, and a *schema* maps that keyword to `{:table :key :val}` plus optional flags:

- `:entry`: the leaf is the identity on the table's key (`:line` is `InvoiceLineId → InvoiceLineId`), so a query rooted there is keyed by ids.
- `:ref "T"`: the leaf's values are ids of table T.
- `:functional`: at most one value per key.
- `:pk`: the key column is the table's primary key.
- `:composite :packed`: a two-column key packed into one integer.

A *row#* is a 0-based position in a loaded vector. An *id* is the table's own key. The closure interpreter speaks row numbers internally; SQL only ever speaks ids. A `Query` (in `tarn.cps`) answers three calls. *drive* emits every pair. *probe x* emits every value under one key. *member x* says whether the key exists.

Several names mean different things in different namespaces. Read the namespace first.

| Name | In one namespace | In another |
|---|---|---|
| `index` | `tarn.core`: a per-query `{y [z …]}` map | `tarn.cps`: materializes a `Query` into a probe-able `many` |
| `fk` | `tarn.chinook`: resolves ids to row numbers through a map | `tarn.part2`: resolves them by arithmetic |
| `nodes` | `tarn.rewrite`: counts the SELECTs the printer will emit | `tarn.cse`: lists the subtrees, with their paths |

The library is `tarn.core` (Part 1's in-memory operators), `tarn.cps` (the closure engine), `tarn.ast`, `tarn.run`, `tarn.sql`, `tarn.rewrite`, `tarn.like` and `tarn.cse`. These names are kept stable, because the tutorial's future parts (`/lathe-extend`) and silt build on them. silt and vitrine use only `tarn.ast` and `tarn.sql`. `tarn.chinook` and `tarn.part1` … `tarn.part6` are the Chinook-bound harness: they load data, hold the schemas and run the checkpoints. They are not an API. (note)

## Writing queries

- … write a query as a chain of binary relations, `(-> invoice-customer (s country) (eq "Brazil"))`, in order never to write a join condition. Composition `s` is a key/foreign-key join with the key projected away. That is why "album's artist's name" is three `s` calls and not three `ON a.x = b.y` clauses. JOB 16b needs 11 of its 13 conjuncts only to state such joins. (P1)
- … have four operators carry almost the whole language, in order that the core stays small enough to read in one sitting. They are `s` (relative product), `both` (product on the key, which is how a query returns several columns while staying binary), `eq` (predicate) and `where` (left semijoin). The in-memory core is 29 non-blank lines. The names follow Prela's, with two changes: Prela's `.and` is `both` because Clojure's `and` is a macro, and `.with` is `where`, which Clojure does not reserve as Rust does. (P1)
- … bind any subexpression with `def` and reuse it, in order to get a CTE without a CTE syntax. `brazilian` (35 invoices) is a complete query on its own and also a part of `bought-in-brazil` (190 lines, *exact*). (P1)
- … have relations be bags, not maps, in order that one-to-many relations survive composition. A version built on `into {}` returns 1 track for the "Brazilian Music" playlist, where SQLite returns 39, because a map keeps only the last pair per key. `index` therefore keeps every value under its key. (P1)
- … have a foreign key that points nowhere, or a predicate that matches nothing, give an empty result rather than an error, in order to keep SQL's inner-join semantics. Use the oracle to tell a typo from an empty answer: `(eq country "Brasil")` returns 0 pairs without complaint. (P1)
- … flip a relation with `inv`, in order to walk a foreign key backwards: "artists with at least one album" is `(-> artist (where (index (inv album-artist))))`, which gives 204 (*exact*), and 71 artists have none. (ex1-1, P3, `fc20c92`)
- … filter on any predicate with `filt`, in order to express what `eq` cannot. Examples: lines for tracks longer than 10 minutes (137, *exact*), and a `HAVING` on a fold's value. (ex1-2, P2, P3)
- … get flat multi-column rows with `cols` in the Part 1 core, in order not to unpick `both`'s nesting `[a [b c]]` by hand. (ex1-3)

## Loading data

- … load each table once into one relation per column, in order to move work from query time to load time. That is what a database's load step is for. Each column becomes a vector indexed by row#, and each table gets an id → row# entry relation. A foreign key is resolved to the target's row# at load, in `fk`, where Prela resolves it at query time through its types. (P1, P2)
- … have one-to-many and junction relations (`PlaylistTrack`, 8,715 rows under 18 playlists) indexed once at load as a `many`, in order that a query never builds an index. Part 1 built 8 hash maps holding 12,202 entries for every run of the Brazil query. (P2)
- … resolve Chinook's ids by arithmetic (row# = id − 1), checked once at load by `dense!`, in order to drop the id maps for tables whose ids really are dense. The assertion turns the assumption into a checked fact: a table with a deleted row fails at load, not with wrong rows. Building the leaves went from about 1.9 ms to 0.45 ms. The query time did not move, because ids are touched only at the leaves. Allocation rose by about 10 %, because every key is now a freshly boxed `Long`. (ex2-5)

- … run every alias with `--enable-native-access=ALL-UNNAMED`, in order that sqlite-jdbc's native library loads without four `WARNING: A restricted method …` lines on JDK 24+. The JDK says such calls will be blocked in a future release, so the flag goes in now. (P1, note)
- *(maintainer)* … know that `tarn.chinook` opens `chinook.sqlite` relative to the working directory and reads every table when the namespace loads, in order to run checkpoints from the project root. A missing file is not an error at first: sqlite-jdbc silently creates an empty database of that name, and the load then fails with `no such table: Artist`. Delete that empty file before retrying. Requiring any `tarn.partN` triggers the load. `tarn.ast`, `tarn.sql` and `tarn.rewrite` do not, which is why silt can use them on its own databases. (P1, note)

## Running in-process

- … run a query as continuations, where `s` drives its left side and probes its right side at each value, in order to build no intermediate relation at all. For the Brazil query this cut allocation from 10.9 MB to 397 KB per run and time from 3.3 ms to 0.15 ms, with the same 190 rows. A column's probe is an array read, so the 3,503-entry index over `track-name` is gone and exactly 190 slots are read. The protocol is the paper's §4 Figure 6, ported line for line. (P2)
- … consume a query with `into`, `transduce`, `eduction` and any transducer, in order not to learn a second collection API. `Query` implements `IReduceInit`, `clojure.core.protocols` extends `CollReduce` to it, and `into` goes through `reduce`. Transducers alone could not have been the engine: a transducer sees one input at a time and cannot ask *another* relation what it holds under a key. `probe` is that missing half. (P2)
- … stop a query early, with `(into [] (take 2) q)` or a continuation that returns truthy, in order to pay only for what I read. `take 2` went from 0.14 ms / 347 KB, the cost of the full query, to 0.01 ms / 16.5 KB. The stop travels back up through every combinator to the leaf loop, so `probe-any` (and with it `where`'s `member`) also returns on the first hit. Every leaf type honours the protocol, including the ones added later (`dense-ids`, `from-map`, `from-pairs-sorted`, `from-dense`). An audit after the exercise merges drove all 21 trees in the repository with a stop on the second row; each stopped after exactly 2 continuation calls. (ex2-1, `b2ae0d3`, `33d3464`)
- … ask `exists?`, in order to answer a yes/no question without producing the rows. "Did anyone in Brazil buy Deep Purple?" scans 129 of the 2,240 lines, not all of them (0.03 ms against 0.26 ms for `size`). It unwinds with a per-call `ex-info`, matched by identity, so a nested `exists?` or an unrelated exception cannot be mistaken for its own. (ex2-4)
- … count a query with `size`, not `count`, in order to drive it. `count` on a `Query` returns 3, the number of record fields. (P2)
- … have a probe with `nil`, `-1` or an index past the end call nothing, rather than throw a `NullPointerException` from `Character.charValue`, in order that an unresolved foreign key drops its row as an inner join would. (P2)
- … wrap any Part 1 seq of pairs as a `Query` with `from-pairs`, in order to check the two engines against each other. Part 1's `where` and Part 2's agree as sets and counts on four inputs (190, 2,240, 5,508 and 0 pairs). (ex2-3)

## Aggregating

- … group with `group` and aggregate with `fold`, in order to express `GROUP BY … HAVING` as combinators rather than a second language. `group` drives the set and probes the key relation, emitting `(key, row)`, as Prela's engine does. The paper's definition, `t.inv().with(r.inv())`, would need two indexes. `fold` is the one honest materialization: a transient hash map sized by groups (60 artists bought in Brazil), not by rows. `HAVING` is `filt` on the folded value. "Artists with at least 9 tracks bought in Brazil" gives 4 rows and 60 groups, and revenue by country gives 24 groups, with the USA at 523.06 (both *exact*). (P3)
- … have `inv` and `group` refuse `probe` and `member` with a message naming the fix (`index` it, or `fold` it first), in order that no operator hides a full scan per probe behind an innocent call. (P3)
- … keep a folded result in a `def`, in order to use it as a materialized view. Re-reading it costs 0.00 ms and 3.9 KB; folding it on every call costs 0.25 ms and 398 KB. Both numbers are real but answer different questions, and mixing them up is how one comes to believe aggregation is free. (P3)
- … collect values per key with `gather`, and map values with `fmap`, in order to have Prela's `.gather` and `.map`. It is `fmap` because shadowing `clojure.core/map` breaks the first `(map inc xs)` written in that namespace. (P3)
- … count distinct values per key with `distinct-count`, in order to have Prela's `count_distinct`. Distinct tracks bought per country match SQLite for all 24 countries, for example USA 486 and Brazil 190. (ex3-1)
- … take a minimum of strings or dates with a `compare`-based `cmin`, in order to aggregate `InvoiceDate`, which sqlite-jdbc returns as a `String`: `clojure.core/min` is numeric and throws `ClassCastException`. Folding with `cmin` beats `gather` then `fmap min` (68 KB against 110 KB), because it never builds the per-key vectors. The first order per country later than 2010-01-01 is Argentina and Hungary (*exact*). (ex3-2, ex4-2)
- … fold a key-sorted stream with `sorted-fold` and no map, in order to aggregate input that already arrives in key order. It allocates 53 KB against 92 KB for the hash fold, but sorting first costs 332 KB, so it pays only on input that is already clustered. (ex3-3)
- … fold into an array indexed by row# with `dense-fold`, in order to avoid hashing when most rows get a group. On Chinook it saves under 1 % (368 KB against 371 KB), because the stream of group pairs dominates. For 3 million artists with 60 groups touched it would allocate about 12 MB per call, so the map stays the default. A nil or out-of-range key throws. (ex3-4)
- … have money summed exactly, in order that a revenue total is not off in its 13th digit. `invoice-total` is converted to `BigDecimal` once, at load, from the `Double` sqlite-jdbc hands over. The USA total is exactly `523.06M`, where the double sum gives `523.0600000000003` and its last digits depend on drive order. The revenue fold costs about 23 % more allocation. (ex3-5)
- … pick the argument of a minimum per key with `fold [:argmin]` over `[arg order]` pairs, in order to write "the shortest track per album" or "the cover photo per collection" without a self-join. Ties go to the least argument. Values are ordered across types as SQLite orders them: `nil` < numbers < text < blobs. (`fc20c92`)
- … substitute a fallback for `nil` with `coalesce`, in order to express SQL's `coalesce` over a left join, for example "the composer, or the album's artist where there is none" (3,503 rows). (`fc20c92`)
- … group on a nullable column and keep its `nil` group, in order to match SQL, which considers NULLs equal for grouping: the result is `[[0 [0 2]] [nil [1]]]`. A nullable *foreign key* walked through `s` loses its rows instead. In short, `nil` is a value until you probe with it. (P3)

## The query as data

- … write the same query text against `tarn.ast` instead of `tarn.cps` and get a tree, `[:eq [:s :invoice-customer :country] "Brazil"]`, in order that one query can be printed, hashed, compared, rewritten and run by more than one interpreter. `->` rewrites the call shape before anything is evaluated, so only the `:require` changes. Leaves become keywords, because a tree that holds 3,503-element vectors is not data one wants to print or hash. (P4)
- … run one tree through both interpreters, closures (`tarn.run`) and SQL (`tarn.sql` through next.jdbc), and compare the results as sets, in order that each interpreter checks the other on every run. Brazil gives 190 = 190, big-in-Brazil 4 = 4, and revenue 24 = 24 to the cent. Every new operator has to land in both `case` expressions; otherwise the check breaks. (P4)
- … get ids, not row numbers, out of the closure interpreter, in order that its keys match SQL's. `run` translates keys once, at the exit, using `key-ref` over the schema's `:entry`/`:ref`. Translating at a `group` instead gave the right counts with the wrong names: `both :artist-name` probed a row-keyed column with ids, and Chico Science (row 17, id 18) came out as another artist. Row numbers belong to one interpreter and must not escape it. (P4)
- *(maintainer)* … load closure leaves either way, in order to use whichever the data supports:
  - *row# leaves* built from vectors (Parts 2–5): fast array probes, with a `rekey` at the exit through per-table `ids` vectors;
  - *id leaves* loaded by running each leaf's own SQL into a `many` (`part6`, where `ids` is `(constantly identity)`): no translation at all. A composite key needs this form, because it has no row number.
  (P4, `fc20c92`)
- … have an operator that has no meaning in SQL rejected by the printer, in order that it refuses rather than guesses. The examples are a `filt` holding a closure, an unknown `fmap` function, `argmin` over anything but two value columns, `inv`/`coalesce`/`fmap` over more than one value column, and a `:packed` key of other than two columns. Predicates are data (`[:like pat]`, `ge`, `gt`), and `fmap` names a function (`:nil?`) from a table both interpreters share, so neither side runs a closure the other cannot see. (P4, ex4-1, `fc20c92`)

## Printing SQL

- … have every node print as one subquery with columns `x, v1 … vn`, with literals bound as `?` parameters in visiting order, in order to keep the printer one `case` arm per operator and never splice a value into the SQL string. (P4)
- … have `where` print as `IN (SELECT t.x …)`, in order that a semijoin never multiplies rows as a join would, and that SQLite builds the list once and reuses it. `EXISTS (SELECT 1 …)` is available with `(binding [tarn.sql/*where-style* :exists] …)`. It became the default in ex4-3 so the two forms could be compared; the default went back to `IN` on the user's direction. (P4, ex4-3, lathe-tutorials session 11-24, `dcec301`)
- … filter with `[:filt r [:like pat]]`, `ge` and `gt`, and fold with `[:count]`, `[:sum]` and `[:min]`, in order to cover `WHERE`, `HAVING` and the common aggregates in both interpreters. On the closure side, `LIKE` follows SQLite's semantics: whole string, `%` and `_`, ASCII-only case folding, no default `ESCAPE`. The regex and `sqlite3` agree on 20 patterns over all 3,503 track names. A plain case-sensitive `re-find` would have returned 5 Brazilian "Live" lines instead of 6, and would have dropped "Alive". (P4, ex4-1, ex4-2)
- *(integrator)* … key a leaf by two columns, as text `a || ',' || b` or with `:composite :packed` as `(a << 32) | b`, in order to address a junction row that has no id of its own. vitrine's `collection_photo` carries the position of a photo within a collection. Packed keys join as integers: vitrine's covers query took 0.93 s with them, against 1.35 s with text keys. Packing is exact only for non-negative integers below 2^32, which SQLite rowids are in practice, and tarn does not check it. A composite leaf is always materialized. When it was flattened, Chinook's 8,715-row join ran as a nested loop over a string concatenation and took 8.7 s; materialized, it gets an automatic index and takes 12 ms. (`fc20c92`, vitrine session 11-23)
- … have the subqueries of `s`, `both`, `inv`, `where` and `coalesce` end in `LIMIT -1`, so that SQLite never flattens them, in order that a deep tree still finishes. Flattened, vitrine's covers tree (15 leaves over a 187,000-row junction table) became one many-way join whose order SQLite got badly wrong, and it ran past 60 s. Materialized, each join gets an automatic index and the tree runs in 1.3 s. The price is paid on every query; see *Open contradictions*. (`fc20c92`)
- … have `[:both a b]` whose sides start at leaves of the same table, by its primary key, printed as one `SELECT` over that table, in order that the factored Brazil plan reads `Track` once instead of twice. The key must be unique (`:pk`). Otherwise joining a table to itself on `x` pairs every row of a key with every other row of it. (ex5-2)

## Rewriting trees

- … rewrite a tree with plain functions from tree to tree (`reassoc`, `factor`, `push-eq`), each built on `clojure.walk/postwalk`, in order to test every rewrite against both interpreters before trusting it. `reassoc` is always valid because composition is associative. It exists because `->` nests to the left and so hides the leftmost leaf. (P5)
- … factor `r.s(a).and(r.s(b))` into `r.s(a.and(b))` only when `r` is provably functional, in order that the rewrite never changes the answer. On Brazil it takes the tree from 20 to 18 nodes with the same 190 rows. Part 5's unguarded `factor` also rewrote the playlist query over the many-valued `playlist-tracks`, and turned its 1,521 rows (39 names × 39 albums under one playlist key) into 39. Both interpreters agreed on both numbers, which is how the harness exposed the rewrite as a change of meaning. Since ex5-1, `factor` takes the schema and derives `functional?` through the tree, so it leaves that tree alone: the result stays 1,521 in both interpreters. The 39 rows the author almost certainly meant come from writing the tracks once, `(s :playlist-tracks (both :track-name :track-album))`. A functional left side alone is not enough: `[:s :line-invoice :invoice-lines]` starts at one invoice per line and still returns many lines. (P5, ex5-1)
- … push `[:eq [:s r t] v]` down to `[:s r [:eq t v]]`, in order to filter closer to the data. The rewrite is always valid because a filter on values distributes over the union that composition is. Rows, nodes and `SELECT`s are unchanged on all four Brazil trees, and so is closure time. (ex5-3)
- … find repeated subtrees and shared left operands across a forest with `tarn.cse`, in order to see what a common-subexpression rewrite could share. Equal trees are equal values, and closures cannot be compared at all. Alone, the Brazil query repeats only the leaf `:line-track`, as the left operand of two different `:s` nodes. Across the three Part 4 queries, `[:s :invoice-customer :country]` appears 3 times. tarn reports these and does not rewrite them. (ex4-4)
- … reorder joins by hand, in order to drive from the selective side: start from the 35 Brazilian invoices and walk back to their lines through `:invoice-lines`, which is `(index (inv line-invoice))` built at load. This is the one rewrite `postwalk` cannot make, because it needs a statistic (35 against 2,240), not a fact about the schema. tarn leaves that statistic to the author, as Prela does. In the closures it takes 3.6× fewer probes (2,189 against 7,860) and runs 3.3× faster (0.045 ms against 0.147 ms). (P5, ex5-4)

## Measuring and checking

- … run every checkpoint with `clojure -M:run` … `-M:run6`, in order to re-check each layer against `sqlite3` after any change. The `-main`s print row counts, set equality between the interpreters, and the oracle results. `run6` exits 1 if any of its nine queries disagree. Exercises were written on separate branches and merged; `b2ae0d3`, `33d3464` and `86b6d76` each fix an interaction between two of them (a leaf added by one exercise that did not honour another's stop protocol, and Part 4's SQL experiments silently switching to `EXISTS`). (P1–P5, `fc20c92`)
- … measure time and allocated bytes per run with `bench` (`ThreadMXBean.getThreadAllocatedBytes`, 50 warm-up and 100 timed runs), and with `time-ms`/`median` interleaved over several rounds, in order to tell a real effect from JIT warm-up order. Part 5's first factored-vs-as-written gap (0.22 against 0.14 ms) was the order in which the bench ran, not the rewrite. (P2, P5)
- … count probes into `column` and `many` leaves with `count-probes`, in order to have a cost model that is deterministic, unlike milliseconds. Counting is off by default, because a boxed increment per probe would move every byte figure. The probe ratio (3.59) predicts the time ratio (3.25) for the flipped tree. (ex5-4)
- … see SQLite's plan for any tree (`plan`, `reads`, the `EXPLAIN QUERY PLAN` printouts) and keep the printed SQL in `generated.sql` and `generated-{as-written,factored,flipped}.sql`, in order to read and diff what SQLite was asked to run. `analyze-plans.sh` runs `ANALYZE` on a copy, `chinook-analyzed.sqlite`, so the original keeps no `sqlite_stat` tables and every other number stays comparable. `analyze-time.clj` times both databases. (P4, P5, ex5-5)

## Using tarn from other projects

- *(integrator)* … run a `tarn.ast` tree on a babashka SQLite connection with `silt.tarn/query`, in order to use tarn in bb scripts. Rows come back as `[x v1 … vn]`, the shape `tarn.part4/flat` gives the closure interpreter. Only `tarn.sql` is used, because `tarn.cps` is not known to load under bb. silt depends on tarn through `:local/root "../tarn"`, only for `silt.tarn`, so `silt.sqlite` users do not pull in tarn. silt's `bb test` runs Part 4's three queries against `../tarn/chinook.sqlite`. (vitrine session 11-23)
- *(integrator)* … describe an application's tables as a tarn schema and write its page queries as trees, in order to share subqueries such as `live`, `by-hand` and `live-memberships` by name. vitrine's `covers` and `in-use-tags` return the same rows as the SQL they replaced on the real 28,312-collection database, and the `/` page is byte-identical. (vitrine session 11-23, vitrine `ed66020`)

## Known limits

- The two interpreters agree on every query in the repository, but not on every query that can be written:
  - `ge` compares with `>=` in the closures, so `[:ge :country "M"]` throws `ClassCastException` there while SQL returns 23 rows.
  - `gt` and the `:min` fold use `compare`, which throws on mixed types where SQLite orders them; only `argmin` uses `sql-compare`.
  - The closures' `:sum` folds from `0.0`, so the tree-form revenue is a `Double` (`523.0600000000003`) even though Part 3's direct fold over the same `BigDecimal` column is exact. They agree with SQL only "to the cent".
- An unknown leaf throws `unknown relation` in the closures, but SQL prints `SELECT null AS x, null AS v1 FROM null` and fails only later, in SQLite, with `near "null": syntax error`.
- The printer emits SQLite: `LIMIT -1`, `(a << 32) | b`, `||`, the window function behind `argmin`. Nothing has been run on PostgreSQL (ex1-5 and ex4-5 are not done).
- The language has no `ORDER BY`; a result is a set, so sort on the way out. It also has no outer join beyond `coalesce`, and no `minus` or `union`. `filt` reaches SQL only as `:like`, and `fmap` only as `:nil?`.
- `tarn.rewrite/nodes` counts a `:filt` or `:gt` subtree as 0, so node counts undercount any tree that contains one. `functional?` answers false for `:gt`, `:filt`, `:inv`, `:coalesce` and `:fmap`, which is safe for `factor` but makes it miss valid factorings. `tarn.cse/nodes*` ignores `coalesce`'s right operand.
- The single-scan `both` and `factor`'s check depend on the `:pk` and `:functional` flags, and only Part 5's schema sets them. Part 4's, `part6`'s and vitrine's schemas get neither rule: the factored Brazil tree prints 19 `SELECT`s under Part 4's schema and 16 under Part 5's.
- `count-probes` keeps its counters in global volatiles, so it is for single-threaded measurement only.
- `dense!` is an `assert`, so the dense-id guarantee disappears when `*assert*` is false.
- There is no test namespace. The checks are the `-main` printouts and asserts, and only `run6` (on a disagreement) and `run2` (through its asserts) exit non-zero.

## Open contradictions

Unless a line says otherwise, these rest on the `-M:run4`, `-M:run5` and `-M:run6` checkpoints run on `main` on 2026-11-24, one JVM per alias, and on `EXPLAIN QUERY PLAN` of the committed `generated-*.sql` files.

- **Part 4 argued that the SQL back end borrows SQLite's optimizer, and `fc20c92` turned that off.** In Part 4, SQLite flattened the 21 nested `SELECT`s into the plan a human would write (generated 0.62 ms, hand-written 0.50 ms through JDBC). With every `s`/`both`/`where` subquery materialized, the generated Brazil SQL now takes 3.0–3.4 ms against 0.55 ms hand-written, and the text grew from 1,172 to 1,244 characters. The `fc20c92` message records 1.2 → 2.7 ms. The trade bought a vitrine query that finishes at all (over 60 s → 1.3 s), but vitrine's `/` still takes 1.15 s against 0.40 s with the SQL it replaced. Materializing only where it pays (composite-key joins, or past some depth) is the open alternative.
- **Part 5 found that flipping the join order "helps both interpreters". On `main` it helps only the closures.** The flipped tree is 3.3× faster as closures but slower in SQLite: 3.98 ms against 2.86 ms as written. The plans show why. In both trees SQLite materializes the whole output side, every `InvoiceLine` joined to `Track`, `Album` and `Artist`, before joining it to the Brazilian lines, because a filter cannot reach into a `LIMIT -1` subquery. Starting from the 35 invoices therefore saves nothing, and the flipped tree's extra `both :line-id` adds one more materialized level and one more `SCAN InvoiceLine`.
- **Several exercise findings were measured before `fc20c92` landed and no longer hold:**
  - `dcec301` justifies `IN` over `EXISTS` by "1.6–2.7× slower". Today the two are 3.05 and 3.13 ms, within noise. `IN` stays the default by decision, and its plan still builds the list once.
  - ex4-4's "forcing `MATERIALIZED` is slower": today, as generated 2.95 ms, CTE materialized 2.92 ms, CTE not materialized 2.67 ms.
  - ex5-3's "SQLite plan identical" under `push-eq`: today `plan same: false` for all four trees, with rows still identical.
  - ex5-5's `ANALYZE` speedup (0.48 → 0.37 ms): the audit after the 2026-11-24 merge re-measured it on `main` and found `ANALYZE` made as-written and factored slightly *slower* (2.85 → 3.42 ms and 2.65 → 2.91 ms). The plan change itself held: the Brazil subquery starts at `SCAN Customer`. The flipped tree was not re-measured, because `analyze-time.clj` timed out.
- **The tutorial text and the code have drifted apart.** The tutorial is not edited here, because `/lathe-extend` needs its names and numbers stable.
  - Part 2 says `probe-any` cannot short-circuit; since ex2-1 it does.
  - Part 4 says 1,172 characters; the SQL is now 1,244.
  - Part 5 calls `(factor tree)`; since ex5-1 it is `(factor schema tree)`.
  - Part 4's design note keeps id maps rather than trust dense ids; ex2-5 adopted arithmetic ids in Part 2's leaves, behind the `dense!` check.
- **Some functions are duplicates of others.**
  - `exists?` (exception) and the stop protocol (`(drive q (fn [_ _] true))`) are two early-exit mechanisms. `exists?` predates the protocol, and since ex2-1 the protocol alone would do.
  - `from-pairs` is `many`.
  - `via-sql-rows` exists three times (`part4`, `part5`'s `sql-rows`, `part6`), because each is bound to its own schema. Calling one with another part's schema prints `FROM null`.
  - `part5`'s `functional-leaves` and `pk-leaves` are equal sets that mean different things.
  - `tarn.part4` defines `sqlite3` twice.

## Wanted, not built

- Postgres as a second SQL target (ex1-5, ex4-5), which needs at least an identifier-quoting rule and a replacement for `LIMIT -1`.
- Timing `s-broken` against `s` (ex1-4). Timing `probe-any`'s short-circuit on the "Drifter" query (ex2-2): the behaviour exists, the measurement does not.
- A cost model that chooses the join order: probe counts predict closure time well, but nothing uses them to choose.
- Applying `cse`'s findings: tarn reports shared subtrees and does not rewrite them.
- Running `tarn.cps` under babashka, which would give silt both interpreters.
