/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Versioned logical export of an offline Redis dataset, including binary data and absolute expiry. */
public record RedisSnapshot(@JsonProperty(required = true) String format,
                            @JsonProperty(required = true) int version,
                            @JsonProperty(required = true) long capturedAtEpochMs,
                            @JsonProperty(required = true) List<Integer> databases,
                            @JsonProperty(required = true) List<Entry> entries,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Provenance provenance) {
    public static final String FORMAT = "eblocker-redis-logical";
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 1_000_000;
    public static final int MAX_BINARY_BYTES = 16 * 1024 * 1024;

    public RedisSnapshot(String format, int version, long capturedAtEpochMs, List<Integer> databases, List<Entry> entries) {
        this(format, version, capturedAtEpochMs, databases, entries, null);
    }

    /** Version 2 snapshots retain immutable RDB provenance in the same SQLite transaction. */
    public record Provenance(@JsonProperty(required = true) int version,
                             @JsonProperty(required = true) String sourceSha256,
                             @JsonProperty(required = true) long sourceBytes,
                             @JsonProperty(required = true) int rdbVersion,
                             String redisVersion,
                             @JsonProperty(required = true) String captureTimeSource,
                             @JsonProperty(required = true) String decoder,
                             @JsonProperty(required = true) String databaseScope) {
        public Provenance {
            require(version == 1 && sourceSha256 != null && sourceSha256.matches("[0-9a-f]{64}"));
            require(sourceBytes >= 18 && sourceBytes <= SnapshotCodec.MAX_SNAPSHOT_BYTES);
            require(rdbVersion >= 5 && rdbVersion <= 12);
            require(redisVersion == null || redisVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-a-zA-Z0-9._]+)?"));
            require("RDB_CTIME".equals(captureTimeSource) || "OPERATOR".equals(captureTimeSource));
            require("redis-replicator/3.11.0;eblocker-rdb/1".equals(decoder));
            require("ENCODED_DATABASES".equals(databaseScope));
        }
    }

    public RedisSnapshot {
        require(FORMAT.equals(format) && capturedAtEpochMs >= 0);
        require((version == VERSION && provenance == null) || (version == 2 && provenance != null));
        require(databases != null && !databases.isEmpty() && databases.size() <= 65536);
        require(databases.stream().allMatch(db -> db != null && db >= 0));
        databases = uniqueSorted(databases, Function.identity(), Comparator.naturalOrder());
        require(entries != null && entries.size() <= MAX_ENTRIES);
        Set<Integer> declared = Set.copyOf(databases);
        require(entries.stream().allMatch(entry -> entry != null && declared.contains(entry.database())));
        entries = uniqueSorted(entries, entry -> new Key(entry.database(), entry.key()),
                Comparator.comparingInt(Entry::database).thenComparing(Entry::key));
    }

    public enum Type { STRING, HASH, SET, LIST, SORTED_SET }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(@JsonProperty(required = true) int database,
                        @JsonProperty(required = true) String key,
                        @JsonProperty(required = true) Type type,
                        Long expiresAtEpochMs, String value,
                        List<HashField> hash, List<String> set, List<String> list,
                        List<ScoredMember> sortedSet) {
        public Entry {
            require(database >= 0 && type != null && (expiresAtEpochMs == null || expiresAtEpochMs >= 0));
            binary(key);
            require((value != null ? 1 : 0) + (hash != null ? 1 : 0) + (set != null ? 1 : 0)
                    + (list != null ? 1 : 0) + (sortedSet != null ? 1 : 0) == 1);
            switch (type) {
                case STRING -> binary(value);
                case HASH -> {
                    require(hash != null && !hash.isEmpty());
                    hash = uniqueSorted(hash, HashField::field, Comparator.comparing(HashField::field));
                }
                case SET -> {
                    require(set != null && !set.isEmpty());
                    set.forEach(RedisSnapshot::binary);
                    set = uniqueSorted(set, Function.identity(), Comparator.naturalOrder());
                }
                case LIST -> {
                    require(list != null && !list.isEmpty());
                    list.forEach(RedisSnapshot::binary);
                    list = List.copyOf(list);
                }
                case SORTED_SET -> {
                    require(sortedSet != null && !sortedSet.isEmpty());
                    sortedSet = uniqueSorted(sortedSet, ScoredMember::member, Comparator.comparing(ScoredMember::member));
                }
            }
        }

        public boolean expiredAt(long nowEpochMs) { return expiresAtEpochMs != null && expiresAtEpochMs <= nowEpochMs; }
        @Override public String toString() { return "RedisSnapshot.Entry[REDACTED]"; }
    }

    public record HashField(@JsonProperty(required = true) String field,
                            @JsonProperty(required = true) String value) {
        public HashField { binary(field); binary(value); }
        @Override public String toString() { return "RedisSnapshot.HashField[REDACTED]"; }
    }

    /** Raw IEEE-754 bits avoid rounding, SQLite -0 normalization and JSON infinity ambiguity. */
    public record ScoredMember(@JsonProperty(required = true) String member,
                               @JsonProperty(required = true) String scoreBits) {
        public ScoredMember {
            binary(member);
            require(scoreBits != null && scoreBits.matches("[0-9a-f]{16}"));
            require(!Double.isNaN(Double.longBitsToDouble(Long.parseUnsignedLong(scoreBits, 16))));
        }
        public double score() { return Double.longBitsToDouble(Long.parseUnsignedLong(scoreBits, 16)); }
        @Override public String toString() { return "RedisSnapshot.ScoredMember[REDACTED]"; }
    }

    public static String encode(byte[] bytes) {
        require(bytes != null && bytes.length <= MAX_BINARY_BYTES);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] binary(String encoded) {
        require(encoded != null && encoded.length() <= ((MAX_BINARY_BYTES + 2L) / 3) * 4);
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            require(bytes.length <= MAX_BINARY_BYTES && Base64.getEncoder().encodeToString(bytes).equals(encoded));
            return bytes;
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    public static String scoreBits(double score) {
        require(!Double.isNaN(score));
        return String.format(java.util.Locale.ROOT, "%016x", Double.doubleToRawLongBits(score));
    }

    private static <T, K> List<T> uniqueSorted(List<T> values, Function<T, K> key, Comparator<T> order) {
        Set<K> found = new HashSet<>();
        for (T value : values) require(value != null && found.add(key.apply(value)));
        return values.stream().sorted(order).toList();
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid offline snapshot"); }
    private record Key(int database, String key) { }
    @Override public String toString() { return "RedisSnapshot[version=" + version + ", entries=" + entries.size() + "]"; }
}
