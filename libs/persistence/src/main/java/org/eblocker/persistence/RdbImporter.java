/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Captures an immutable bounded copy, then isolates third-party decoding from the importing JVM. */
public final class RdbImporter {
    private RdbImporter() { }

    public static SqliteMigrationStore.ImportResult importRdb(Path source, Path destination, Long capturedAtEpochMs)
            throws IOException, SQLException {
        RedisSnapshot snapshot = read(source, capturedAtEpochMs);
        // No destination is created until checksum, complete decoding and logical validation succeeded.
        return new SqliteMigrationStore(destination).importSnapshot(snapshot);
    }

    public static RedisSnapshot read(Path source, Long capturedAtEpochMs) throws IOException {
        return read(source, capturedAtEpochMs, 30_000);
    }

    static RedisSnapshot read(Path source, Long capturedAtEpochMs, long timeoutMillis) throws IOException {
        if (timeoutMillis < 1 || timeoutMillis > 30_000) throw new IllegalArgumentException("Invalid decoder deadline");
        if (capturedAtEpochMs != null && capturedAtEpochMs < 0) throw new IOException("Invalid RDB capture time");
        BasicFileAttributes before = attributes(source);
        Path directory = Files.createTempDirectory("eblocker-rdb-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path captured = directory.resolve("source.rdb");
        Path decoded = directory.resolve("snapshot.json");
        Process process = null;
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(SnapshotCodec.MAX_SNAPSHOT_BYTES + 1);
            }
            String digest;
            try {
                if (bytes.length != before.size() || bytes.length > SnapshotCodec.MAX_SNAPSHOT_BYTES) throw new IOException("RDB source changed or exceeds its limit");
                digest = RdbDecoder.sha256(bytes);
                PrivateFiles.writeNew(captured, bytes);
            } finally { Arrays.fill(bytes, (byte) 0); }
            unchanged(before, attributes(source));
            var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea", "-Xmx512m", "-XX:MaxDirectMemorySize=32m", "-XX:+ExitOnOutOfMemoryError",
                    "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
                    "-cp", System.getProperty("java.class.path"), RdbDecoder.class.getName(),
                    captured.toString(), decoded.toString(), capturedAtEpochMs == null ? "auto" : capturedAtEpochMs.toString());
            command.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Inherited JVM option variables must not override resource limits or produce sensitive dumps.
            for (String option : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}) command.environment().remove(option);
            process = command.start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                throw new IOException("RDB decoding failed or exceeded its resource limit");
            }
            RedisSnapshot snapshot = SnapshotCodec.read(decoded);
            if (snapshot.provenance() == null || !snapshot.provenance().sourceSha256().equals(digest)
                    || snapshot.provenance().sourceBytes() != before.size()) throw new IOException("RDB provenance mismatch");
            unchanged(before, attributes(source));
            return snapshot;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("RDB import interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    while (process.isAlive() && System.nanoTime() < deadline) {
                        try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                        catch (InterruptedException again) { interrupted = true; }
                    }
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            try (var files = Files.list(directory)) {
                for (Path path : files.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static BasicFileAttributes attributes(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 18 || attributes.size() > SnapshotCodec.MAX_SNAPSHOT_BYTES) {
            throw new IOException("RDB source must be a bounded regular file");
        }
        return attributes;
    }

    private static void unchanged(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("RDB source changed during import");
    }
}
