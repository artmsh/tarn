# tarn
`tarn` is a Prela-style query language embedded in Clojure. Its data model is the binary relation, a seq of [key value] pairs, and nothing else.

```bash
curl -sLO https://github.com/lerocha/chinook-database/releases/download/v1.4.5/Chinook_Sqlite.sqlite
mv Chinook_Sqlite.sqlite chinook.sqlite
clojure -M:run2
```

will print

```
brazilian invoices: 35
lines bought in Brazil: 190
[127 [Admirável Gado Novo Cássia Eller]]
[128 [Mis Penas Lloraba Yo (Ao Vivo) Soy Gitano (Tangos) Cássia Eller]]
[129 [Drifter Deep Purple]]
distinct artists: 60
tracks on Brazilian Music: 39
first 2 pairs, reduced early: [[127 [Admirável Gado Novo Cássia Eller]] [128 [Mis Penas Lloraba Yo (Ao Vivo) Soy Gitano (Tangos) Cássia Eller]]]
part 1, seqs + indexes: 2.84 ms, 11,991,751 bytes allocated per run
part 2, drive + probe  : 0.15 ms, 396,787 bytes allocated per run
```

## Sources
- https://prela-lang.org/
- https://arxiv.org/abs/2607.26356
- https://remy.wang/blog/cps.html
- https://github.com/remysucre/prela
- https://github.com/lerocha/chinook-database/releases/tag/v1.4.5

