/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.RedisRdbReplicator;
import com.moilioncircle.redis.replicator.event.Event;
import com.moilioncircle.redis.replicator.event.PostRdbSyncEvent;
import com.moilioncircle.redis.replicator.io.RedisInputStream;
import com.moilioncircle.redis.replicator.rdb.BaseRdbParser;
import com.moilioncircle.redis.replicator.rdb.datatype.DB;
import com.moilioncircle.redis.replicator.rdb.datatype.ExpiredType;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyStringValueString;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyValuePair;
import com.moilioncircle.redis.replicator.rdb.datatype.ZSetEntry;
import com.moilioncircle.redis.replicator.rdb.iterable.ValueIterableRdbVisitor;
import com.moilioncircle.redis.replicator.rdb.iterable.datatype.KeyStringValueByteArrayIterator;
import com.moilioncircle.redis.replicator.rdb.iterable.datatype.KeyStringValueMapEntryIterator;
import com.moilioncircle.redis.replicator.rdb.iterable.datatype.KeyStringValueZSetEntryIterator;
import com.moilioncircle.redis.replicator.util.CRC64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.eblocker.persistence.RedisSnapshot.*;

/** Worker entry point. Only RdbImporter exposes untrusted input to this dependency. */
public final class RdbDecoder {
    static final String DECODER = "redis-replicator/3.11.0;eblocker-rdb/1";
    private static final Set<String> AUXILIARY_FIELDS = Set.of("redis-ver", "redis-bits", "ctime", "used-mem",
            "repl-stream-db", "repl-id", "repl-offset", "aof-preamble", "aof-base", "cow-size");
    private final List<Entry> entries = new ArrayList<>();
    private final Set<Integer> databases = new TreeSet<>();
    private final Map<String, String> auxiliary = new HashMap<>();
    private long encodedBytes;
    private boolean eof;
    private int version;

    private RdbDecoder() { }

    public static void main(String[] args) {
        try {
            if (args.length != 3) System.exit(1);
            byte[] source = Files.readAllBytes(Path.of(args[0]));
            try {
                RedisSnapshot snapshot = decode(source, "auto".equals(args[2]) ? null : Long.parseLong(args[2]));
                byte[] result = SnapshotCodec.encode(snapshot);
                try { PrivateFiles.writeNew(Path.of(args[1]), result); }
                finally { Arrays.fill(result, (byte) 0); }
            } finally { Arrays.fill(source, (byte) 0); }
        } catch (Throwable rejected) {
            // The parent accepts only a successful exit and validated bounded output.
            // In particular dependency parser messages/assertions must never reveal input.
            System.exit(1);
        }
    }

    static RedisSnapshot decode(byte[] source, Long captureTime) throws IOException {
        if (source.length < 18 || source.length > SnapshotCodec.MAX_SNAPSHOT_BYTES) throw invalid();
        String header = new String(source, 0, 9, StandardCharsets.US_ASCII);
        if (!header.matches("REDIS00(?:0[5-9]|1[0-2])") || source[source.length - 9] != (byte) 255) throw invalid();
        long expected = ByteBuffer.wrap(source, source.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (expected == 0 || expected != CRC64.crc64(source, 0, source.length - 8)) throw invalid();
        RdbDecoder decoder = new RdbDecoder();
        Configuration configuration = Configuration.defaultSetting().setVerbose(false).setUseDefaultExceptionListener(false);
        RedisRdbReplicator parser = new RedisRdbReplicator(new ByteArrayInputStream(source), configuration);
        parser.setRdbVisitor(decoder.new Visitor(parser, source.length));
        parser.addExceptionListener((replicator, failure, event) -> { throw new UncheckedIOException(invalid()); });
        parser.addEventListener((replicator, event) -> {
            try {
                if (event instanceof PostRdbSyncEvent) decoder.eof = true;
                else if (event instanceof KeyValuePair<?, ?> pair) decoder.entry(pair);
            } catch (IOException | RuntimeException failure) { throw new UncheckedIOException(invalid()); }
        });
        try {
            parser.open();
            if (!decoder.eof) throw invalid(); // The dependency intentionally swallows premature EOF.
            Long created = decoder.auxiliary.containsKey("ctime")
                    ? Math.multiplyExact(Long.parseLong(decoder.auxiliary.get("ctime")), 1000) : null;
            if (created != null && captureTime != null && !created.equals(captureTime)) throw invalid();
            Long captured = created == null ? captureTime : created;
            if (captured == null || captured < 0) throw invalid();
            if (decoder.databases.isEmpty()) decoder.databases.add(0); // Empty RDB has no SELECTDB records.
            Provenance provenance = new Provenance(1, sha256(source), source.length, decoder.version,
                    decoder.auxiliary.get("redis-ver"), created == null ? "OPERATOR" : "RDB_CTIME", DECODER, "ENCODED_DATABASES");
            RedisSnapshot result = new RedisSnapshot(FORMAT, 2, captured, new ArrayList<>(decoder.databases), decoder.entries, provenance);
            SnapshotCodec.digest(result); // Canonical encoded size is a hard limit as well.
            return result;
        } catch (RuntimeException | AssertionError failure) { throw invalid(); }
        finally { parser.close(); }
    }

    private void entry(KeyValuePair<?, ?> pair) throws IOException {
        if (entries.size() >= MAX_ENTRIES || pair.getDb() == null || !(pair.getKey() instanceof byte[])) throw invalid();
        int database = Math.toIntExact(pair.getDb().getDbNumber());
        if (!databases.contains(database)) throw invalid();
        Long expiry = pair.getExpiredType() == ExpiredType.NONE ? null : pair.getExpiredValue();
        if (pair.getExpiredType() == ExpiredType.SECOND) expiry = Math.multiplyExact(expiry, 1000);
        String key = binaryValue((byte[]) pair.getKey());
        String string = null;
        List<HashField> hash = null;
        List<String> values = null;
        List<ScoredMember> sorted = null;
        Type type;
        if (pair instanceof KeyStringValueString value) {
            type = Type.STRING; string = binaryValue(value.getValue());
        } else if (pair instanceof KeyStringValueMapEntryIterator value) {
            type = Type.HASH; hash = new ArrayList<>();
            var iterator = value.getValue();
            while (iterator.hasNext()) {
                var field = iterator.next();
                hash.add(new HashField(binaryValue(field.getKey()), binaryValue(field.getValue())));
                collectionLimit(hash.size());
            }
        } else if (pair instanceof KeyStringValueZSetEntryIterator value) {
            type = Type.SORTED_SET; sorted = new ArrayList<>();
            var iterator = value.getValue();
            while (iterator.hasNext()) {
                ZSetEntry member = iterator.next();
                sorted.add(new ScoredMember(binaryValue(member.getElement()), scoreBits(member.getScore())));
                collectionLimit(sorted.size());
            }
        } else if (pair instanceof KeyStringValueByteArrayIterator value) {
            type = Set.of(2, 11, 20).contains(pair.getValueRdbType()) ? Type.SET : Type.LIST;
            values = new ArrayList<>();
            var iterator = value.getValue();
            while (iterator.hasNext()) { values.add(binaryValue(iterator.next())); collectionLimit(values.size()); }
        } else throw invalid();
        entries.add(new Entry(database, key, type, expiry, string, hash, type == Type.SET ? values : null,
                type == Type.LIST ? values : null, sorted));
    }

    private static void collectionLimit(int size) throws IOException { if (size > MAX_ENTRIES) throw invalid(); }

    private String binaryValue(byte[] value) throws IOException {
        if (value == null || value.length > MAX_BINARY_BYTES) throw invalid();
        encodedBytes += ((value.length + 2L) / 3) * 4 + 4;
        if (encodedBytes > SnapshotCodec.MAX_SNAPSHOT_BYTES) throw invalid();
        return encode(value);
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static IOException invalid() { return new IOException("Invalid, unsupported or oversized offline RDB snapshot"); }

    private final class Visitor extends ValueIterableRdbVisitor {
        private final long sourceBytes;
        Visitor(RedisRdbReplicator parser, long sourceBytes) { super(parser); this.sourceBytes = sourceBytes; }

        @Override public int applyVersion(RedisInputStream input) throws IOException {
            version = super.applyVersion(input); return version;
        }

        @Override public int applyType(RedisInputStream input) throws IOException {
            int type = super.applyType(input);
            // Reject unsupported state before the dependency can skip or decode it.
            if (!Set.of(0, 1, 2, 3, 4, 5, 9, 10, 11, 12, 13, 14, 16, 17, 18, 20, 250, 251, 252, 253, 254, 255).contains(type)) throw invalid();
            if ((type == 5 && version < 8) || (type == 14 && version < 7)
                    || (type >= 16 && type <= 18 && version < 10) || (type == 20 && version < 11)
                    || ((type == 250 || type == 251) && version < 7)) throw invalid();
            return type;
        }

        @Override public DB applySelectDB(RedisInputStream input, int version) throws IOException {
            DB database = super.applySelectDB(input, version);
            int id = Math.toIntExact(database.getDbNumber());
            if (id < 0 || databases.size() >= 65536 || !databases.add(id)) throw invalid();
            return database;
        }

        @Override public Event applyAux(RedisInputStream input, int version) throws IOException {
            // DefaultRdbVisitor logs AUX values. Read them with upstream primitives without logging.
            BaseRdbParser parser = new BaseRdbParser(input);
            byte[] keyBytes = parser.rdbLoadEncodedStringObject().first();
            byte[] valueBytes = parser.rdbLoadEncodedStringObject().first();
            if (keyBytes.length > 64 || valueBytes.length > 256) throw invalid();
            String key = new String(keyBytes, StandardCharsets.US_ASCII);
            String value = new String(valueBytes, StandardCharsets.US_ASCII);
            if (!AUXILIARY_FIELDS.contains(key) || auxiliary.putIfAbsent(key, value) != null) throw invalid();
            if ((key.equals("aof-preamble") || key.equals("aof-base")) && !value.equals("0")) throw invalid();
            return null;
        }

        @Override public long applyEof(RedisInputStream input, int version) throws IOException {
            long checksum = super.applyEof(input, version);
            // total() includes read-ahead bytes. The unread buffer must not hide trailing content.
            if (input.total() - (input.tail() - input.head()) != sourceBytes) throw invalid();
            return checksum;
        }
    }
}
