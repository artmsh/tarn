# tarn
`tarn` is a Prela-style query language embedded in Clojure. Its data model is the binary relation, a seq of [key value] pairs, and nothing else.

```bash
curl -sLO https://github.com/lerocha/chinook-database/releases/download/v1.4.5/Chinook_Sqlite.sqlite
mv Chinook_Sqlite.sqlite chinook.sqlite
clojure -M:run2
```

will print (milliseconds and bytes vary between runs; every other line is exact)

```
where agrees: line-invoice | brazilian         190 pairs (set =, count =)
where agrees: line-track | track-album        2240 pairs (set =, count =)
where agrees: playlist-tracks | line-track    5508 pairs (set =, count =)
where agrees: track-album | empty                0 pairs (set =, count =)
size    : 7 rows -> lines scanned: 2240
exists? : true -> lines scanned: 129
absent artist (expect false): false
size (Deep Purple)   : 0.26 ms, 412,683 bytes allocated per run
exists? (Deep Purple): 0.03 ms, 20,170 bytes allocated per run
brazilian invoices: 35
lines bought in Brazil: 190
[127 [Admirável Gado Novo Cássia Eller]]
[128 [Mis Penas Lloraba Yo (Ao Vivo) Soy Gitano (Tangos) Cássia Eller]]
[129 [Drifter Deep Purple]]
distinct artists: 60
tracks on Brazilian Music: 39
first 2 pairs, reduced early: [[127 [Admirável Gado Novo Cássia Eller]] [128 [Mis Penas Lloraba Yo (Ao Vivo) Soy Gitano (Tangos) Cássia Eller]]]
take 2 (reduced early): 0.01 ms, 16,554 bytes allocated per run
part 1, seqs + indexes: 3.26 ms, 10,931,474 bytes allocated per run
part 2, drive + probe  : 0.15 ms, 397,288 bytes allocated per run
```

The other checkpoints are `clojure -M:run`, `-M:run3` … `-M:run6`. What tarn does and why is in [docs/features.md](docs/features.md).

## Sources
- https://prela-lang.org/
- https://arxiv.org/abs/2607.26356
- https://remy.wang/blog/cps.html
- https://github.com/remysucre/prela
- https://github.com/lerocha/chinook-database/releases/tag/v1.4.5

