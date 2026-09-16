/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded offline format. Parser errors intentionally omit keys, values and input excerpts. */
public final class SnapshotCodec {
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxStringLength(((RedisSnapshot.MAX_BINARY_BYTES + 2) / 3) * 4)
                            .maxNestingDepth(16).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private SnapshotCodec() { }

    static String encodeProvenance(RedisSnapshot.Provenance provenance) throws IOException {
        return JSON.writeValueAsString(provenance);
    }

    static RedisSnapshot.Provenance readProvenance(String json) throws IOException {
        if (json == null || json.length() > 2048) throw new IOException("Invalid source provenance");
        try {
            var result = JSON.readValue(json, RedisSnapshot.Provenance.class);
            if (result == null) throw new IOException("Invalid source provenance");
            return result;
        }
        catch (IOException | RuntimeException error) { throw new IOException("Invalid source provenance"); }
    }

    public static RedisSnapshot read(Path input) throws IOException {
        try (InputStream stream = Files.newInputStream(input)) {
            byte[] bytes = stream.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            try {
                if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Offline snapshot exceeds the size limit");
                RedisSnapshot snapshot = JSON.readValue(bytes, RedisSnapshot.class);
                if (snapshot == null) throw new IOException("Invalid offline snapshot");
                return snapshot;
            } catch (IOException | RuntimeException error) {
                throw new IOException("Invalid offline snapshot");
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    public static byte[] encode(RedisSnapshot snapshot) throws IOException {
        if (snapshot == null) throw new IOException("Invalid offline snapshot");
        byte[] bytes = JSON.writeValueAsBytes(snapshot);
        if (bytes.length > MAX_SNAPSHOT_BYTES) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IOException("Offline snapshot exceeds the size limit");
        }
        return bytes;
    }

    public static String digest(RedisSnapshot snapshot) throws IOException {
        byte[] bytes = encode(snapshot);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
