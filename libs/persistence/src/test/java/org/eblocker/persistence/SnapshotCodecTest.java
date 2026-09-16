/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.eblocker.persistence.RedisSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {
    @TempDir Path temporary;

    @Test void canonicalDigestIgnoresUnorderedInputButPreservesListOrder() throws Exception {
        var source = SqliteMigrationStoreTest.fixture();
        var reversed = new ArrayList<>(source.entries());
        Collections.reverse(reversed);
        var reordered = new RedisSnapshot(FORMAT, VERSION, source.capturedAtEpochMs(), List.of(7, 0, 2), reversed);
        assertEquals(source, reordered);
        assertEquals(SnapshotCodec.digest(source), SnapshotCodec.digest(reordered));
        var list = source.entries().stream().filter(entry -> entry.type() == Type.LIST).findFirst().orElseThrow();
        var values = new ArrayList<>(list.list());
        Collections.reverse(values);
        reversed.remove(list);
        reversed.add(new Entry(list.database(), list.key(), Type.LIST, null, null, null, null, values, null));
        assertNotEquals(SnapshotCodec.digest(source), SnapshotCodec.digest(new RedisSnapshot(FORMAT, VERSION,
                source.capturedAtEpochMs(), source.databases(), reversed)));
        Path path = temporary.resolve("roundtrip.json");
        Files.write(path, SnapshotCodec.encode(source));
        assertEquals(source, SnapshotCodec.read(path));
    }

    @ParameterizedTest @ValueSource(strings = {
            "null", "{}", "[]",
            "{\"format\":\"eblocker-redis-logical\",\"version\":2,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[],\"secret\":\"secret-fixture-only\"}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1,\"version\":1,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":\"1\",\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1.5,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":null,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1,\"capturedAtEpochMs\":0,\"databases\":[0],\"entries\":[]} {}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1,\"databases\":[0],\"entries\":[]}",
            "{\"format\":\"eblocker-redis-logical\",\"version\":1,\"capturedAtEpochMs\":999999999999999999999,\"databases\":[0],\"entries\":[]}"
    })
    void rejectsMalformedOrAmbiguousSnapshotsWithoutExposingInput(String input) throws Exception {
        Path path = Files.writeString(temporary.resolve("invalid.json"), input);
        var error = assertThrows(IOException.class, () -> SnapshotCodec.read(path));
        assertEquals("Invalid offline snapshot", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void rejectsUnknownEntryTypeAndFieldAsWellAsMissingPrimitiveFields() throws Exception {
        var source = new String(SnapshotCodec.encode(SqliteMigrationStoreTest.fixture()), java.nio.charset.StandardCharsets.UTF_8);
        for (String json : List.of(source.replaceFirst("\"STRING\"", "\"STREAM\""),
                source.replaceFirst("\"database\":0,", ""),
                source.replaceFirst("\"type\":\"STRING\"", "\"type\":\"STRING\",\"unexpected\":true"))) {
            Path path = Files.writeString(temporary.resolve("invalid-entry.json"), json);
            assertThrows(IOException.class, () -> SnapshotCodec.read(path));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Zg", "/x==", "!", "AA==\n", "-_=="})
    void binaryEncodingMustBeCanonicalPaddedBase64(String value) {
        assertThrows(IllegalArgumentException.class, () -> binary(value));
    }

    @Test void rejectsDuplicatesWrongPayloadAndEmptyRedisCollections() throws Exception {
        var source = SqliteMigrationStoreTest.fixture();
        Entry entry = source.entries().get(0);
        assertThrows(IllegalArgumentException.class, () -> new RedisSnapshot(FORMAT, VERSION, 0, List.of(0), List.of(entry, entry)));
        assertThrows(IllegalArgumentException.class, () -> new RedisSnapshot(FORMAT, VERSION, 0, List.of(0, 0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.HASH, null, "", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.STRING, null, "", null, List.of(""), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.HASH, null, null, List.of(new HashField("", ""), new HashField("", "AA==")), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.SET, null, null, null, List.of("", ""), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.SORTED_SET, null, null, null, null, null,
                List.of(new ScoredMember("", scoreBits(1)), new ScoredMember("", scoreBits(2)))));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.HASH, null, null, List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.SET, null, null, null, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.LIST, null, null, null, null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, "", Type.SORTED_SET, null, null, null, null, null, List.of()));
    }

    @Test void preservesInfiniteAndSignedZeroScoresButRejectsEveryNaNEncoding() {
        for (double score : new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.0, 0.0, Double.MAX_VALUE, Double.MIN_VALUE}) {
            assertEquals(Double.doubleToRawLongBits(score), Double.doubleToRawLongBits(new ScoredMember("", scoreBits(score)).score()));
        }
        for (String nan : List.of("7ff8000000000000", "7ff0000000000001", "fff8000000000000")) {
            assertThrows(IllegalArgumentException.class, () -> new ScoredMember("", nan));
        }
        assertThrows(IllegalArgumentException.class, () -> scoreBits(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ScoredMember("", "7FF0000000000000"));
    }

    @Test void rejectsOversizedFileAndNullInputInsteadOfTruncating() throws Exception {
        Path path = temporary.resolve("oversized.json");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(SnapshotCodec.MAX_SNAPSHOT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> SnapshotCodec.read(path));
        assertThrows(IOException.class, () -> SnapshotCodec.encode(null));
    }
}
