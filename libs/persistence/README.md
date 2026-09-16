# Offline SQLite migration

This Java 17 module converts an immutable Redis RDB backup into a validated
logical snapshot, imports it into a real SQLite database, and exports the same
dataset again. Existing logical JSON snapshots can also be imported directly.
It is an **offline migration building block**, not the server's active datastore. Redis and all existing
application behavior remain in place until runtime parity is demonstrated.

## Build and run

From the repository root, with a JDK 17 or newer:

```sh
./mvnw -f libs/persistence/pom.xml verify
java -jar libs/persistence/target/eblocker-persistence-4.0.3-jar-with-dependencies.jar rdb-export /private/existing-directory/immutable-dump.rdb /private/existing-directory/decoded.json
java -jar libs/persistence/target/eblocker-persistence-4.0.3-jar-with-dependencies.jar rdb-import /private/existing-directory/immutable-dump.rdb /private/existing-directory/example.sqlite
java -jar libs/persistence/target/eblocker-persistence-4.0.3-jar-with-dependencies.jar audit /private/existing-directory/example.sqlite
java -jar libs/persistence/target/eblocker-persistence-4.0.3-jar-with-dependencies.jar export /private/existing-directory/example.sqlite /private/existing-directory/export.json
```

The directory must already exist and be trusted. Use local Linux filesystems
supporting POSIX permissions, hard links and directory synchronization. The
[`canonical-all-encodings.rdb`](src/test/resources/rdb/canonical-all-encodings.rdb)
and [`all-types-v1.json`](src/test/resources/all-types-v1.json) fixtures contain
synthetic data only. `import <snapshot.json> <new.sqlite>` accepts validated
logical JSON; `migrate` is an alias for `import`.
Exit codes are `0` for success, `1` for a rejected operation, and `2` for invalid
arguments. Import prints `IMPORTED` or `ALREADY_IMPORTED`; export prints
`EXPORTED`. Audit prints JSON with schema version, canonical SHA-256, capture
time, RDB provenance where present, and counts, never keys or values. Error
output deliberately contains no parser/JDBC details, file paths or data excerpts.

The executable is self-contained, including the SQLite native driver. The Maven
module pins `org.xerial:sqlite-jdbc:3.53.4.0`, checked against the
[official Xerial release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0).
RDB decoding pins `com.moilioncircle:redis-replicator:3.11.0` from its
[official release](https://github.com/leonchen83/redis-replicator/releases/tag/v3.11.0)
under Apache-2.0. Only its SLF4J API dependency is included at runtime; upstream
test-only Jedis/Log4j dependencies are not brought into this module. Jackson,
SLF4J and build plugins use the repository's pinned parent versions.
This version check is not a claim of a completed vulnerability audit. Native
loading and packaging still require validation on the target appliance.

## RDB decoding boundary

`rdb-export <source.rdb> <new.json> [capture-epoch-ms]` validates and decodes the
entire source before publishing logical JSON. `rdb-import` runs the same decoder
before opening or creating any SQLite destination. Neither command connects to
Redis, starts a Redis service, opens a listening socket, or changes the input.

The accepted envelope is RDB version **5 through 12**, with a nonzero matching
Redis CRC64 checksum and exactly one final EOF/checksum. Checksum-disabled RDBs,
versions before 5 or after 12, AOF preambles, unknown opcodes and trailing data
are rejected. The maintained upstream parser performs the actual value
conversion; this module supplies strict type/metadata gates, duplicate checks,
whole-file framing/checksum checks and the existing logical snapshot validation.

| Logical type | Accepted RDB value encodings |
| --- | --- |
| String | Raw bytes, integer-encoded strings, LZF-compressed strings |
| Hash | Plain hash, zipmap, ziplist, listpack |
| Set | Plain set, intset with 16/32/64-bit integers, listpack |
| List | Plain list, ziplist, quicklist, quicklist2 packed and plain nodes |
| Sorted set | Text scores, binary double scores, ziplist, listpack |

Binary keys/values, list duplicates/order, whole-key second/millisecond expiry,
all encoded database selectors and exact double score bits are retained. The
synthetic fixture covers every encoding in this table, and unmodified upstream
RDB fixtures cover versions 5, 6, 7, 8, 9 and 10. Version 11 set-listpack and a
version 12 envelope are tested with synthetic data. This is a tested bounded
subset, not a claim to decode every Redis feature or arbitrary dataset size.

Streams, module values/AUX, functions, hash-field TTL, slot metadata, and
per-key LRU/LFU metadata are rejected **for the entire file**, even when supported
keys precede them. Unknown/repeated AUX fields are also rejected. Recognized
source AUX fields are `redis-ver`, `redis-bits`, `ctime`, `used-mem`,
`repl-stream-db`, `repl-id`, `repl-offset`, `aof-preamble`, `aof-base`, and
`cow-size`; AOF flags must be zero. Allocation/replication hints are accepted
as source metadata, not restored as SQLite runtime behavior.

Capture time comes from RDB `ctime` (seconds converted to milliseconds). An RDB
without `ctime` requires the optional nonnegative `capture-epoch-ms` argument,
which records an **operator assertion**, not an inferred time. If both are
provided they must agree. File modification time and import time are never
substituted. Expiry timestamps are not extended or filtered during conversion.
Redis RDB does not enumerate all configured empty databases: explicitly encoded
empty selectors are retained, and an entirely empty RDB represents database 0.
Provenance marks this scope as `ENCODED_DATABASES`; the original configured
number of empty databases cannot be reconstructed from RDB alone.

The source must be a regular non-symlink file of 18 bytes to 64 MiB. It is copied
into a private directory (`0700`, files `0600`), with identity/size/mtime checks
before and after decoding. Use a trusted immutable offline copy: these checks
do not turn a changing live file into a consistent snapshot, or defend against
an actor controlling the source directory and deliberately restoring metadata.

Decoding runs in a separate same-JDK JVM with a 512 MiB heap, 32 MiB direct-memory
limit and 30-second deadline. Worker output is discarded, inherited Java option
variables are removed, JVM heap/core/error dumps are disabled, and errors expose
no input excerpts. Timeout, interruption, invalid output or resource exhaustion
reject the operation and terminate the worker. Private temporary files are
removed on normal exit/failure; abrupt parent termination can leave a private
temporary directory. Process separation bounds parser resources; it is **not**
an operating-system security sandbox. Input and canonical JSON are each limited
to 64 MiB, binary items to 16 MiB, and a collection to 1,000,000 members. Limits
are rejection ceilings, not a promise that every dataset below them fits the
worker's memory/time budget. No partial conversion is published.

## Snapshot contracts, versions 1 and 2

The complete, runnable example is
[`all-types-v1.json`](src/test/resources/all-types-v1.json). The root object has
these fields (version 2 adds the provenance object described below):

| Field | Meaning |
| --- | --- |
| `format` | Exactly `eblocker-redis-logical` |
| `version` | `1` without provenance, or `2` with validated RDB provenance |
| `capturedAtEpochMs` | Nonnegative absolute source capture time, not import time |
| `databases` | Unique nonnegative 32-bit database IDs, including empty databases |
| `entries` | Unique `(database, key)` entries |

Each entry contains `database`, `key`, `type`, optional `expiresAtEpochMs`, and
exactly one payload matching its type:

| Type | Payload | Preservation |
| --- | --- | --- |
| `STRING` | `value`: Base64 string | Arbitrary bytes, including empty values |
| `HASH` | `hash`: `[{field, value}]` | Binary fields and values; unique fields |
| `SET` | `set`: Base64 string array | Binary members; unique membership |
| `LIST` | `list`: Base64 string array | Exact order and duplicate members |
| `SORTED_SET` | `sortedSet`: `[{member, scoreBits}]` | Binary unique members and exact IEEE-754 score bits |

Keys, values, hash fields and collection members use canonical padded Base64.
Empty binary keys are supported. Collections must be nonempty, matching Redis
key semantics. Unknown fields/types, duplicate JSON properties, trailing JSON,
implicit scalar conversions and duplicate keys/members are rejected.

Sorted-set `scoreBits` is exactly 16 lowercase hexadecimal digits representing
the raw 64-bit double. This preserves negative zero and precision without
SQLite `REAL` normalization or ambiguous JSON infinity notation. Positive and
negative infinity are accepted; all NaN encodings are rejected, matching
[Redis sorted-set score restrictions](https://redis.io/docs/latest/commands/zadd/).
Ordering of unordered collections is canonicalized for hashing; list order is
unchanged. The canonical SHA-256 is a round-trip/corruption check, **not** a
signature or proof of source completeness.

`expiresAtEpochMs` is an absolute millisecond timestamp. Omission or `null`
means no expiration. Import never recalculates it from a relative TTL, even
when the original expiration is already past. `read(database, key, now)` hides
expired entries at the expiration boundary; audit counts them and forensic
export retains them unchanged. Importing later therefore never grants extra
lifetime. Logical snapshot exports retain the recorded source capture time.

Both versions are intentionally bounded: the UTF-8 JSON file and canonical export
are at most 64 MiB, each decoded binary item at most 16 MiB, at most 1,000,000
entries and 65,536 declared databases. Oversized datasets are rejected, never
truncated. Parsing/canonicalization is in memory; larger deployments need a
separately versioned streaming importer. The convenience `read` method audits
and materializes the complete dataset, so it is not a runtime query API.

RDB output uses logical version **2**, adding a required `provenance` object
with its own `version: 1`, original file `sourceSha256`/`sourceBytes`,
`rdbVersion`, optional `redisVersion`, `captureTimeSource` (`RDB_CTIME` or
`OPERATOR`), pinned `decoder` identity, and `databaseScope: ENCODED_DATABASES`.
The provenance is included in the canonical digest and stored atomically with
all imported rows. The source SHA-256 identifies the exact original bytes;
neither it nor the logical digest authenticates their origin. Two RDB files
with identical logical keys but different bytes remain distinct imports.
Version 1 JSON output omits provenance and retains its existing canonical digest.

## Storage and failure behavior

The schema is identified by `PRAGMA application_id=0x45424c4b` and
`user_version=2` for new stores. Exact version 1 databases remain readable and
allow identical retries without being upgraded. Importing provenance into a
version 1 target requires a new destination. The importer accepts only these
exact supported schemas; extra tables, indexes or triggers and future versions are rejected. Each open checks
the schema, SQLite integrity and foreign keys. The data lives in typed tables:

| Table | Data |
| --- | --- |
| `migration_meta` | Source canonical digest and capture time |
| `source_provenance` | Versioned RDB origin metadata (schema 2 only; no key/value payload) |
| `databases` | Declared database IDs, including empty databases |
| `redis_keys` | Binary key, type and absolute expiration |
| `string_values` | Binary string values |
| `hash_fields` | Binary field/value pairs |
| `set_members` | Binary unique members |
| `list_items` | Contiguous positions and binary values |
| `sorted_set_members` | Binary members and signed SQL integers containing raw double bits |

These are constrained `STRICT` tables with foreign keys, rather than Redis
`DUMP` blobs or a JSON document stored in one column. Databases use
[WAL](https://sqlite.org/wal.html), `synchronous=FULL`, a three-second lock wait
and `BEGIN IMMEDIATE` import transactions. Future schema changes require an
explicit migration; this module does not silently upgrade unknown databases.
See the [SQLite pragma reference](https://sqlite.org/pragma.html) for the
identity, version and integrity checks.

A new empty schema is created in a private staged file, checkpointed and
published through an atomic hard link that cannot replace an existing target.
The parent directory is synchronized after publication. New databases and
export files have mode `0600`; no export overwrites an existing pathname.
Symlink targets and symlink parent paths are rejected. The supplied directory
must not be concurrently controlled by an untrusted actor; these checks are
not a sandbox against directory replacement races.

All imported rows and metadata are committed together only after a complete
typed read-back matches the source digest. Failure or interruption rolls the
transaction back. A terminated process can leave an empty initialized store,
which may be retried; a crash before publication can leave an unused private
staging file. Existing identical imports return `ALREADY_IMPORTED` only after
checking the stored data again. Different datasets, changed data and unrelated
files are never overwritten. There is no implicit merge or force flag.

Audit/export use a read-only SQLite connection and a consistent read
transaction. SQLite may create/use WAL coordination sidecars; “read-only” means
no modification of application rows or schema, not zero filesystem activity.
Use this store offline, not alongside arbitrary SQL writers. WAL recovery,
concurrent identical importers and abrupt JVM termination are covered with
real temporary SQLite databases. Hardware power-loss durability still needs
appliance testing.

Snapshots, SQLite files and SQLite sidecars can contain credentials or private
configuration. The module does not encrypt them. Keep their directory private
and retain existing backup access controls; do not treat Base64 as encryption.

## Runtime inventory and the remaining cutover boundary

The repository currently persists more than Java entities:

| Existing source | Observed data/behavior |
| --- | --- |
| [`JedisEntityRepository`](../../apps/server/src/main/java/org/eblocker/server/common/data/JedisEntityRepository.java), [`JedisDataSource`](../../apps/server/src/main/java/org/eblocker/server/common/data/JedisDataSource.java) | JSON/string entities, ID counters, settings, hashes and sets |
| [`JedisDeviceRepository`](../../apps/server/src/main/java/org/eblocker/server/common/data/JedisDeviceRepository.java), [`JedisNetworkConfiguration`](../../apps/server/src/main/java/org/eblocker/server/common/data/JedisNetworkConfiguration.java) | Device hashes and network strings/hashes |
| [`JedisEventDataSource`](../../apps/server/src/main/java/org/eblocker/server/common/data/events/JedisEventDataSource.java), [`JedisDnsDataSource`](../../apps/server/src/main/java/org/eblocker/server/common/data/dns/JedisDnsDataSource.java) | Event and resolver lists |
| [`JedisDomainRecordingDataSource`](../../apps/server/src/main/java/org/eblocker/server/common/data/JedisDomainRecordingDataSource.java) | JSON strings with absolute `PEXPIREAT` expiration |
| [`filterstats.go`](../../platform/dns/coredns/filterstats/filterstats.go), [`configupdater.go`](../../platform/dns/coredns/configupdater.go) | Redis incremented counters and configuration Pub/Sub |

The format additionally supports sorted sets without assuming that the
currently inspected Java paths use them. Streams, module-specific Redis types,
hash-field expiration and unrecognized Redis metadata are outside this version
and are rejected by the RDB converter, not silently discarded. Pub/Sub,
blocking operations, increment semantics, transactions and cross-process
coordination are runtime behavior, not snapshot data; none is replaced here.

There are two different existing backup families:

* [`RedisBackupService`](../../apps/server/src/main/java/org/eblocker/server/common/data/RedisBackupService.java)
  and [`redis-backup`](../../apps/server/src/main/package/scripts/redis-backup)
  manage copies of `/var/lib/redis/dump.rdb`. A verified immutable copy is now
  accepted by `rdb-import`/`rdb-export` within the explicit decoder boundary above.
* [`ConfigurationBackupService`](../../apps/server/src/main/java/org/eblocker/server/http/service/ConfigurationBackupService.java)
  reads/writes JAR archives with manifests and per-provider configuration;
  [`EncryptedContainer`](../../apps/server/src/main/java/org/eblocker/server/http/backup/EncryptedContainer.java)
  protects sensitive provider payloads. These are selective configuration
  exports, not complete Redis datasets. Their decryption/import is not
  implemented by this module.

The RDB decoder and transactional offline SQLite import are now implemented.
Source-consistent backup acquisition, live semantic repositories, cutover,
rollback and Redis removal remain separate work. In particular, replacing only
[`BaseModule`](../../apps/server/src/main/java/org/eblocker/server/common/BaseModule.java)'s
`DataSource → JedisDataSource` binding would leave active Redis consumers behind:
[`EblockerModule`](../../apps/server/src/main/java/org/eblocker/server/common/EblockerModule.java)
separately binds DNS, events, domain recording and filter statistics;
[`JedisPubSubService`](../../apps/server/src/main/java/org/eblocker/server/common/pubsub/JedisPubSubService.java)
delivers process events. Go DNS uses configuration subscriptions, incremented
counters and trimmed resolver lists. Native
[`arp_read.c`](../../platform/native/network-tools/arp_read.c) and
[`arp_write.c`](../../platform/native/network-tools/arp_write.c) use `arp:in/out`,
`dhcp:in` and `ip6:in/out` channels. The
[Ruby Redis client](../../platform/native/redis-client/) remains a separate
consumer boundary. Snapshot equality cannot prove these runtime behaviors.

A concrete next cutover sequence is:

1. Implement domain-specific SQLite repositories behind the existing Java
   interfaces and compare reads against Redis in an isolated captured dataset.
   Settings/entities are the first bounded slice; devices require cache
   publication, conflict handling and listener behavior to be tested together.
   The archival `SqliteMigrationStore.read()` is intentionally not that runtime
   interface: it audits and materializes the whole database on each call.
2. Specify and test atomic counters, expiry, ordered/trimmed lists, transactions
   and every cross-process notification. Add an explicit IPC/event replacement
   for Go/native/Ruby consumers; a SQLite table alone does not replace Pub/Sub.
   Do not treat independent Redis/SQLite dual writes as an atomic migration.
3. In an appliance maintenance window, quiesce **all** writers and acquire a
   consistent completed Redis backup through the controlled backup procedure.
   Keep an immutable original. A live `SCAN` followed by independent reads is
   not a consistent snapshot. Do not restore into a Redis instance that drops
   expired keys and then claim lossless archival conversion.
4. Run `rdb-export`, `rdb-import`, `audit`, and SQLite `export`; compare canonical
   snapshots, counts, expiry and source provenance. Change runtime bindings only
   after the Java/Go/native integration and appliance tests pass. A feature flag
   must refuse startup on a missing, unaudited or incompatible SQLite dataset.
5. Rehearse restart/crash recovery and rollback before releasing. The retained
   Redis backup is a rollback point only while no newer SQLite writes exist;
   after activation, rollback needs an explicitly validated reverse migration
   or a documented lossless write journal. This module does not supply either.

Building or running this module does not switch the existing runtime. No
production/host Redis service, native network operation or appliance was used
for its tests. See the [fixture provenance](src/test/resources/rdb/README.md)
for the real upstream files, synthetic writer and regression scope.
