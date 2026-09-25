# tarn
`tarn` is a Prela-style query language embedded in Clojure. Its data model is the binary relation, a seq of [key value] pairs, and nothing else.

```bash
curl -sLO https://github.com/lerocha/chinook-database/releases/download/v1.4.5/Chinook_Sqlite.sqlite
mv Chinook_Sqlite.sqlite chinook.sqlite
clojure -M:run
```

will print

```
artists: 275 albums: 347 tracks: 3503
invoice lines: 2240 customers: 59 invoices: 412
brazilian invoices: 35
lines bought in Brazil: 190
[127 [Admirável Gado Novo Cássia Eller]]
[128 [Mis Penas Lloraba Yo (Ao Vivo) Soy Gitano (Tangos) Cássia Eller]]
[129 [Drifter Deep Purple]]
distinct artists: 60
top: ([Os Paralamas Do Sucesso 11] [Pearl Jam 11] [Chico Science & Nação Zumbi 9])
100 runs: 3.45 ms each
```

## Sources
- https://prela-lang.org/
- https://arxiv.org/abs/2607.26356
- https://remy.wang/blog/cps.html
- https://github.com/remysucre/prela
- https://github.com/lerocha/chinook-database/releases/tag/v1.4.5

