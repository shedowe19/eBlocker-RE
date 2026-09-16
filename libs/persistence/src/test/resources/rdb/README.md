# Offline RDB regression fixtures

These are binary RDB files, not Redis `DUMP` value blobs or JSON disguised as
RDB. Tests never launch Redis or open a host port.

`canonical-all-encodings.rdb` is synthetic, generated deterministically by
[`generate_rdb.py`](../../fixtures/generate_rdb.py). Its paired
`canonical-all-encodings.expected.json` records the intended values independently
of the production decoder. The test writer covers 27 keys across databases 0
and 2, plus an explicitly empty database 7, all five logical types, all accepted
value encodings, binary/empty keys and values, duplicate list values, both TTL
opcodes, signed integer extremes, exact double bits, infinities, and negative
zero. It emits the actual Redis CRC64 trailer. Regenerate from the repository
root with:

```sh
python3 libs/persistence/src/test/fixtures/generate_rdb.py
```

Encoding references are Redis 7.2's official
[`rdb.c`](https://github.com/redis/redis/blob/7.2/src/rdb.c),
[`ziplist.c`](https://github.com/redis/redis/blob/7.2/src/ziplist.c),
[`listpack.c`](https://github.com/redis/redis/blob/7.2/src/listpack.c), and
[`intset.c`](https://github.com/redis/redis/blob/7.2/src/intset.c).
The writer deliberately implements only fixture construction and is never used
for production parsing. This is not claimed to be a backup produced by a live
Redis server.

`upstream/` files are copied **without modification** from the maintained
[redis-replicator v3.11.0 test resources](https://github.com/leonchen83/redis-replicator/tree/v3.11.0/src/test/resources).
[`manifest.json`](upstream/manifest.json) pins source, filename, byte count,
SHA-256, and whether an explicit operator capture time is needed. Tests verify
every manifest entry and each source CRC64. The fixture directory’s original
MIT notice by Sripathi Krishnan is retained in [`LICENSE`](upstream/LICENSE);
the redis-replicator project’s Apache-2.0 text is retained separately in
[`LICENSE.redis-replicator`](upstream/LICENSE.redis-replicator).

| Fixtures | Purpose |
| --- | --- |
| `dumpV6`, `dumpV7`, `dumpV9`, `dumpV10` | Historical actual RDB files; all five types, LZF, packed encodings and multiple databases |
| `binarydump`, `non_ascii_values` | Raw non-text data and non-ASCII values |
| `rdb_version_5_with_checksum` | Oldest accepted checksummed envelope; requires operator capture time |
| `rdb_version_8_with_64b_length_and_scores` | 64-bit lengths and binary double scores |
| `ziplist_with_integers`, `zipmap_with_big_values` | Packed integers and extended zipmap lengths |
| `dumpV11`, `listpack-bug` | Supported keys mixed with streams; the **whole file** must fail |
| `dump-stream`, `dump-module-2`, `dump-ttlhash`, `function2`, `dump-slot` | Unsupported state must never be silently discarded |
| `dump-lfu`, `dump-lru` | Unsupported eviction metadata must reject conversion |

Tests additionally construct small valid-CRC adversarial envelopes: unsupported
opcodes/versions, duplicates, malformed packed terminators, NaN scores, bad
quicklist containers, missing/invalid capture metadata and trailing payload.
Every byte position in the synthetic RDB is flipped and every truncated prefix
is rejected. A compressed size bomb runs only in the bounded decoder subprocess,
never in the test JVM. Worker timeout/interruption, real SQLite transactional
rollback, retries, source-provenance tampering, and old schema compatibility are
also covered. These tests establish this bounded decoder contract, not full
Redis/appliance runtime parity or exhaustive parser fuzzing.
