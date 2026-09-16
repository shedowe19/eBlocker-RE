/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.moilioncircle.redis.replicator.util.CRC64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.eblocker.persistence.RedisSnapshot.*;
import static org.eblocker.persistence.SqliteMigrationStore.ImportResult.*;
import static org.junit.jupiter.api.Assertions.*;

class RdbImporterTest {
    @TempDir Path temporary;
    private static final long CAPTURE_TIME = 1700000000000L;

    static Path resource(String name) throws Exception {
        return Path.of(RdbImporterTest.class.getResource("/rdb/" + name).toURI());
    }

    @Test void canonicalRealRdbPreservesEveryEncodingAgainstIndependentExpectedValues() throws Exception {
        Path source = resource("canonical-all-encodings.rdb");
        byte[] original = Files.readAllBytes(source);
        RedisSnapshot snapshot = RdbImporter.read(source, null);
        var expected = SnapshotCodec.read(resource("canonical-all-encodings.expected.json"));
        assertEquals(expected, new RedisSnapshot(FORMAT, 1, snapshot.capturedAtEpochMs(), snapshot.databases(), snapshot.entries()));
        assertEquals(27, snapshot.entries().size());
        assertEquals(List.of(0, 2, 7), snapshot.databases());
        assertEquals(Set.of(Type.values()), snapshot.entries().stream().map(Entry::type).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, snapshot.version());
        assertEquals(11, snapshot.provenance().rdbVersion());
        assertEquals("7.2.0", snapshot.provenance().redisVersion());
        assertEquals(RdbDecoder.sha256(original), snapshot.provenance().sourceSha256());
        assertEquals(original.length, snapshot.provenance().sourceBytes());
        assertEquals("RDB_CTIME", snapshot.provenance().captureTimeSource());
        assertEquals("ENCODED_DATABASES", snapshot.provenance().databaseScope());
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void rdbImportAuditExportAndIdenticalRetryKeepProvenanceAndAbsoluteExpiry() throws Exception {
        Path source = resource("canonical-all-encodings.rdb");
        Path target = temporary.resolve("rdb.sqlite");
        assertEquals(IMPORTED, RdbImporter.importRdb(source, target, null));
        var store = new SqliteMigrationStore(target);
        var snapshot = store.exportSnapshot();
        assertEquals(RdbImporter.read(source, null), snapshot);
        var audit = store.audit(CAPTURE_TIME);
        assertEquals(2, audit.schemaVersion());
        assertEquals(snapshot.provenance(), audit.provenance());
        assertEquals(2, audit.expiredEntries());
        assertTrue(store.read(0, "expired-ms".getBytes(StandardCharsets.US_ASCII), CAPTURE_TIME).isEmpty());
        assertTrue(store.read(0, "future".getBytes(StandardCharsets.US_ASCII), 4102444800122L).isPresent());
        assertTrue(store.read(0, "future".getBytes(StandardCharsets.US_ASCII), 4102444800123L).isEmpty());
        assertEquals(ALREADY_IMPORTED, RdbImporter.importRdb(source, target, null));
        byte[] changed = Files.readAllBytes(source);
        // A distinct source with identical logical data is not silently substituted for the recorded source.
        changed[8] = '2';
        fixChecksum(changed);
        var other = RdbDecoder.decode(changed, null);
        assertEquals(snapshot.entries(), other.entries());
        assertNotEquals(snapshot.provenance().sourceSha256(), other.provenance().sourceSha256());
        assertThrows(SQLException.class, () -> store.importSnapshot(other));
        assertEquals(snapshot, store.exportSnapshot());
    }

    @ParameterizedTest @CsvSource({
            "dumpV6.rdb,6,132,true", "dumpV7.rdb,7,19,false", "dumpV9.rdb,9,1,false", "dumpV10.rdb,10,4,false",
            "binarydump.rdb,7,3,false", "non_ascii_values.rdb,7,6,false", "rdb_version_5_with_checksum.rdb,5,6,true",
            "rdb_version_8_with_64b_length_and_scores.rdb,8,2,false", "ziplist_with_integers.rdb,6,1,true", "zipmap_with_big_values.rdb,6,1,true"
    })
    void unmodifiedUpstreamRdbFixturesDecodeAndRoundtrip(String name, int version, int count, boolean operatorTime) throws Exception {
        byte[] bytes = Files.readAllBytes(resource("upstream/" + name));
        var source = RdbDecoder.decode(bytes, operatorTime ? CAPTURE_TIME : null);
        assertEquals(version, source.provenance().rdbVersion());
        assertEquals(count, source.entries().size());
        assertEquals(operatorTime ? "OPERATOR" : "RDB_CTIME", source.provenance().captureTimeSource());
        var store = new SqliteMigrationStore(temporary.resolve(name + ".sqlite"));
        store.importSnapshot(source);
        assertEquals(source, store.exportSnapshot());
        assertEquals(SnapshotCodec.digest(source), store.audit(CAPTURE_TIME).sha256());
    }

    @Test void fixtureManifestPinsEveryBorrowedRdbToItsOriginalBytes() throws Exception {
        var manifest = JsonMapper.builder().build().readTree(Files.readAllBytes(resource("upstream/manifest.json")));
        for (var file : manifest.get("files")) {
            byte[] bytes = Files.readAllBytes(resource("upstream/" + file.get("file").asText()));
            assertEquals(file.get("bytes").asLong(), bytes.length);
            assertEquals(file.get("sha256").asText(), RdbDecoder.sha256(bytes));
            assertEquals(ByteBuffer.wrap(bytes, bytes.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong(),
                    CRC64.crc64(bytes, 0, bytes.length - 8));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dump-stream.rdb", "dump-module-2.rdb", "dump-ttlhash.rdb", "function2.rdb",
            "dump-slot.rdb", "dump-lfu.rdb", "dump-lru.rdb", "dumpV11.rdb", "listpack-bug.rdb"})
    void unsupportedRealRdbStateNeverCreatesOrModifiesDestination(String name) throws Exception {
        Path target = temporary.resolve("untouched.sqlite");
        assertThrows(IOException.class, () -> RdbImporter.importRdb(resource("upstream/" + name), target, null));
        assertFalse(Files.exists(target));
        byte[] sentinel = "do-not-overwrite".getBytes(StandardCharsets.US_ASCII);
        Files.write(target, sentinel);
        assertThrows(IOException.class, () -> RdbImporter.importRdb(resource("upstream/" + name), target, null));
        assertArrayEquals(sentinel, Files.readAllBytes(target));
    }

    @Test void eachTruncatedPrefixAndEverySingleByteCorruptionIsRejected() throws Exception {
        byte[] valid = Files.readAllBytes(resource("canonical-all-encodings.rdb"));
        for (int i = 0; i < valid.length; i++) {
            int size = i;
            assertThrows(IOException.class, () -> RdbDecoder.decode(Arrays.copyOf(valid, size), null), "prefix " + i);
            byte[] damaged = valid.clone(); damaged[i] ^= 1;
            assertThrows(IOException.class, () -> RdbDecoder.decode(damaged, null), "byte " + i);
        }
    }

    @Test void checksumDisabledTrailingJunkPrematureEofAndUnknownVersionsAreRejected() throws Exception {
        byte[] valid = Files.readAllBytes(resource("canonical-all-encodings.rdb"));
        byte[] zero = valid.clone(); Arrays.fill(zero, zero.length - 8, zero.length, (byte) 0);
        assertThrows(IOException.class, () -> RdbDecoder.decode(zero, null));
        for (String header : List.of("REDIS0004", "REDIS0013", "INVALID11")) {
            byte[] wrong = valid.clone(); System.arraycopy(header.getBytes(StandardCharsets.US_ASCII), 0, wrong, 0, 9); fixChecksum(wrong);
            assertThrows(IOException.class, () -> RdbDecoder.decode(wrong, null));
        }
        // Both checksums are valid, but the first EOF is followed by trailing content.
        byte[] extra = concat(valid, new byte[]{0, (byte) 255}, new byte[8]); fixChecksum(extra);
        assertThrows(IOException.class, () -> RdbDecoder.decode(extra, null));
        // A declared string consumes the physical EOF/checksum: swallowed EOF from the parser must still fail closed.
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(new byte[]{(byte) 254, 0, 0, 1, 'k', 63}), CAPTURE_TIME));
    }

    @ParameterizedTest @ValueSource(ints = {6, 7, 8, 15, 19, 21, 22, 23, 244, 245, 246, 247, 248, 249})
    void allUnsupportedTypesAndOpcodesFailBeforeDecoding(int type) {
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(new byte[]{(byte) 254, 0, (byte) type}), CAPTURE_TIME));
    }

    @ParameterizedTest @ValueSource(strings = {"keys", "hash", "set", "sortedSet", "database", "aux"})
    void duplicateLogicalStateIsRejectedInsteadOfCollapsed(String kind) {
        byte[] body = switch (kind) {
            case "keys" -> new byte[]{(byte) 254, 0, 0, 1, 'k', 1, 'a', 0, 1, 'k', 1, 'b'};
            case "hash" -> new byte[]{(byte) 254, 0, 4, 1, 'k', 2, 1, 'f', 1, 'a', 1, 'f', 1, 'b'};
            case "set" -> new byte[]{(byte) 254, 0, 2, 1, 'k', 2, 1, 'a', 1, 'a'};
            case "sortedSet" -> new byte[]{(byte) 254, 0, 3, 1, 'k', 2, 1, 'a', 1, '1', 1, 'a', 1, '2'};
            case "database" -> new byte[]{(byte) 254, 0, (byte) 254, 0};
            case "aux" -> concat(aux("ctime", "1700000000"), aux("ctime", "1700000000"));
            default -> throw new AssertionError();
        };
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(body), CAPTURE_TIME));
    }

    @Test void packedTerminatorsNaNAndInvalidQuicklistContainersFailClosed() {
        // One listpack set member with an invalid terminator, despite a correct outer RDB checksum.
        byte[] packed = new byte[]{10, 0, 0, 0, 1, 0, (byte) 129, 'x', 2, 0};
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(concat(new byte[]{(byte) 254, 0, 20, 1, 'k', 10}, packed)), CAPTURE_TIME));
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(new byte[]{(byte) 254, 0, 3, 1, 'k', 1, 1, 'x', (byte) 253}), CAPTURE_TIME));
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(concat(new byte[]{(byte) 254, 0, 5, 1, 'k', 1, 1, 'x'},
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0x7ff0000000000001L).array())), CAPTURE_TIME));
        assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(new byte[]{(byte) 254, 0, 18, 1, 'k', 1, 3, 1, 'x'}), CAPTURE_TIME));
    }

    @Test void missingCaptureTimeUnknownMetadataAndAofPreamblesRequireExplicitRejection() throws Exception {
        byte[] empty = rdb(new byte[0]);
        assertThrows(IOException.class, () -> RdbDecoder.decode(empty, null));
        var source = RdbDecoder.decode(empty, CAPTURE_TIME);
        assertEquals(List.of(0), source.databases());
        assertTrue(source.entries().isEmpty());
        assertEquals("OPERATOR", source.provenance().captureTimeSource());
        for (byte[] metadata : List.of(aux("unknown", "secret-value"), aux("aof-preamble", "1"), aux("aof-base", "1"),
                aux("ctime", "-1"), aux("ctime", "9223372036854775807"))) {
            IOException failure = assertThrows(IOException.class, () -> RdbDecoder.decode(rdb(metadata), null));
            assertFalse(failure.getMessage().contains("secret-value"));
        }
        assertThrows(IOException.class, () -> RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), CAPTURE_TIME + 1));
    }

    @Test void sourceMustBeRegularBoundedAndCannotBeASymlink() throws Exception {
        Path link = temporary.resolve("source-link.rdb");
        Files.createSymbolicLink(link, resource("canonical-all-encodings.rdb"));
        assertThrows(IOException.class, () -> RdbImporter.read(link, null));
        assertThrows(IOException.class, () -> RdbImporter.read(temporary, null));
        Path empty = Files.createFile(temporary.resolve("empty.rdb"));
        assertThrows(IOException.class, () -> RdbImporter.read(empty, null));
        try (var file = new java.io.RandomAccessFile(empty.toFile(), "rw")) { file.setLength(SnapshotCodec.MAX_SNAPSHOT_BYTES + 1L); }
        assertThrows(IOException.class, () -> RdbImporter.read(empty, null));
        assertThrows(IOException.class, () -> RdbImporter.read(resource("canonical-all-encodings.rdb"), -1L));
    }

    @Test void inflatedCompressedValueIsRejectedInWorkerWithoutAllocatingInParent() throws Exception {
        // Tiny compressed input claims a two-GiB output. Never decode this adversarial fixture in the test JVM.
        byte[] payload = new byte[]{(byte) 254, 0, 0, 1, 'k', (byte) 195, 2, (byte) 128, (byte) 128, 0, 0, 0, 0, 'x'};
        Path source = Files.write(temporary.resolve("inflate.rdb"), rdb(payload));
        Path target = temporary.resolve("never.sqlite");
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(IOException.class, () -> RdbImporter.importRdb(source, target, CAPTURE_TIME)));
        assertFalse(Files.exists(target));
    }

    @Test void deadlineAndInterruptionTerminateWorkerAndLeaveDestinationAbsent() throws Exception {
        Path source = resource("canonical-all-encodings.rdb");
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertThrows(IOException.class, () -> RdbImporter.read(source, null, 1)));
        Path target = temporary.resolve("interrupted.sqlite");
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> RdbImporter.importRdb(source, target, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(target));
        assertEquals(IMPORTED, RdbImporter.importRdb(source, target, null));
    }

    @Test void failedTransactionRollsBackProvenanceTogetherWithAllRows() throws Exception {
        var snapshot = RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), null);
        var store = new SqliteMigrationStore(temporary.resolve("rollback.sqlite"));
        assertThrows(IllegalStateException.class, () -> store.importSnapshot(snapshot, count -> {
            if (count == 20) throw new IllegalStateException("injected");
        }));
        assertFalse(store.audit(CAPTURE_TIME).imported());
        assertNull(store.audit(CAPTURE_TIME).provenance());
        assertEquals(IMPORTED, store.importSnapshot(snapshot));
        assertEquals(snapshot, store.exportSnapshot());
    }

    @Test void provenanceTamperingCannotPassTheCanonicalDigestAudit() throws Exception {
        Path path = temporary.resolve("tampered.sqlite");
        var source = RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), null);
        var store = new SqliteMigrationStore(path); store.importSnapshot(source);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("UPDATE source_provenance SET document=replace(document,'7.2.0','7.2.1')");
        }
        assertThrows(SQLException.class, () -> store.audit(CAPTURE_TIME));
        assertThrows(SQLException.class, () -> store.importSnapshot(source));
    }

    @Test void versionTwoProvenanceRejectsUnknownVersionsMissingFieldsAndInvalidIdentities() throws Exception {
        var mapper = JsonMapper.builder().build();
        byte[] original = SnapshotCodec.encode(RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), null));
        for (String field : List.of("version", "sourceSha256", "sourceBytes", "rdbVersion", "captureTimeSource", "decoder", "databaseScope")) {
            var missing = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
            ((com.fasterxml.jackson.databind.node.ObjectNode) missing.get("provenance")).remove(field);
            Path path = Files.write(temporary.resolve("missing.json"), mapper.writeValueAsBytes(missing));
            assertThrows(IOException.class, () -> SnapshotCodec.read(path), field);
        }
        for (String mutation : List.of("rootVersion", "provenanceVersion", "sourceSha256", "sourceBytes", "rdbVersion", "captureTimeSource", "decoder", "databaseScope", "redisVersion", "unknown")) {
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
            var provenance = (com.fasterxml.jackson.databind.node.ObjectNode) invalid.get("provenance");
            switch (mutation) {
                case "rootVersion" -> invalid.put("version", 1);
                case "provenanceVersion" -> provenance.put("version", 2);
                case "sourceBytes" -> provenance.put("sourceBytes", 0);
                case "rdbVersion" -> provenance.put("rdbVersion", 13);
                default -> provenance.put(mutation, "secret-fixture-only");
            }
            Path path = Files.write(temporary.resolve("invalid.json"), mapper.writeValueAsBytes(invalid));
            var failure = assertThrows(IOException.class, () -> SnapshotCodec.read(path), mutation);
            assertFalse(failure.getMessage().contains("secret-fixture-only"));
        }
    }

    @Test void emptyLegacySchemaRejectsRdbProvenanceAndRollsBackWithoutUpgrading() throws Exception {
        Path path = temporary.resolve("empty-legacy.sqlite");
        var store = new SqliteMigrationStore(path);
        var legacy = SqliteMigrationStoreTest.fixture();
        assertThrows(IllegalStateException.class, () -> store.importSnapshot(legacy, count -> { throw new IllegalStateException("injected"); }));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE source_provenance"); statement.execute("PRAGMA user_version=1");
        }
        var snapshot = RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), null);
        assertThrows(SQLException.class, () -> store.importSnapshot(snapshot));
        assertFalse(store.audit(CAPTURE_TIME).imported());
        assertEquals(1, store.audit(CAPTURE_TIME).schemaVersion());
        assertEquals(0, store.audit(CAPTURE_TIME).entries());
        assertEquals(IMPORTED, store.importSnapshot(legacy));
    }

    @Test void legacySchemaOneRemainsReadableAndRetryableWithoutImplicitUpgrade() throws Exception {
        Path path = temporary.resolve("legacy.sqlite");
        var legacy = SqliteMigrationStoreTest.fixture();
        var store = new SqliteMigrationStore(path); store.importSnapshot(legacy);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE source_provenance"); statement.execute("PRAGMA user_version=1");
        }
        byte[] original = Files.readAllBytes(path);
        assertEquals(legacy, store.exportSnapshot());
        assertEquals(1, store.audit(CAPTURE_TIME).schemaVersion());
        assertNull(store.audit(CAPTURE_TIME).provenance());
        assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals(ALREADY_IMPORTED, store.importSnapshot(legacy));
        var rdb = RdbDecoder.decode(Files.readAllBytes(resource("canonical-all-encodings.rdb")), null);
        assertThrows(SQLException.class, () -> store.importSnapshot(rdb));
        assertEquals(legacy, store.exportSnapshot());
    }

    private static byte[] aux(String name, String value) {
        return concat(new byte[]{(byte) 250, (byte) name.length()}, name.getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) value.length()}, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] rdb(byte[] payload) {
        byte[] result = concat("REDIS0011".getBytes(StandardCharsets.US_ASCII), payload, new byte[]{(byte) 255}, new byte[8]);
        fixChecksum(result); return result;
    }

    private static void fixChecksum(byte[] bytes) {
        ByteBuffer.wrap(bytes, bytes.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(CRC64.crc64(bytes, 0, bytes.length - 8));
    }

    private static byte[] concat(byte[]... values) {
        var bytes = new ByteArrayOutputStream();
        for (byte[] value : values) bytes.writeBytes(value);
        return bytes.toByteArray();
    }
}
