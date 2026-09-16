/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.eblocker.persistence.RedisSnapshot.*;
import static org.eblocker.persistence.SqliteMigrationStore.ImportResult.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteMigrationStoreTest {
    @TempDir Path temporary;

    static RedisSnapshot fixture() throws Exception {
        return SnapshotCodec.read(Path.of(SqliteMigrationStoreTest.class.getResource("/all-types-v1.json").toURI()));
    }

    @Test void importsRealTypedSQLiteAndReopensWithoutLosingAnyValue() throws Exception {
        Path path = temporary.resolve("store.sqlite");
        RedisSnapshot source = fixture();
        assertEquals(IMPORTED, new SqliteMigrationStore(path).importSnapshot(source));
        SqliteMigrationStore reopened = new SqliteMigrationStore(path);
        assertEquals(source, reopened.exportSnapshot());
        var audit = reopened.audit(1700000000000L);
        assertTrue(audit.imported());
        assertEquals(3, audit.databases());
        assertEquals(8, audit.entries());
        assertEquals(1, audit.expiredEntries());
        assertEquals(Set.of(Type.values()), audit.entriesByType().keySet());
        assertEquals(SnapshotCodec.digest(source), audit.sha256());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        assertEquals("SQLite format 3\0", new String(Files.readAllBytes(path), 0, 16, StandardCharsets.US_ASCII));
        try (Connection connection = jdbc(path)) {
            assertEquals(SqliteSchema.APPLICATION_ID, SqliteSchema.integer(connection, "PRAGMA application_id"));
            assertEquals(2, SqliteSchema.integer(connection, "PRAGMA user_version"));
            assertEquals(4, SqliteSchema.integer(connection, "SELECT count(*) FROM string_values"));
            assertEquals(2, SqliteSchema.integer(connection, "SELECT count(*) FROM hash_fields"));
            assertEquals(3, SqliteSchema.integer(connection, "SELECT count(*) FROM set_members"));
            assertEquals(4, SqliteSchema.integer(connection, "SELECT count(*) FROM list_items"));
            assertEquals(6, SqliteSchema.integer(connection, "SELECT count(*) FROM sorted_set_members"));
            SqliteSchema.verify(connection);
        }
    }

    @Test void absoluteTtlIsNeverExtendedAndForensicExportRetainsExpiredData() throws Exception {
        var store = new SqliteMigrationStore(temporary.resolve("ttl.sqlite"));
        store.importSnapshot(fixture());
        byte[] key = "future".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.read(2, key, 1799999999999L).isPresent());
        assertTrue(store.read(2, key, 1800000000000L).isEmpty());
        assertTrue(store.read(2, key, Long.MAX_VALUE).isEmpty());
        assertTrue(store.read(0, "expired".getBytes(StandardCharsets.UTF_8), 1700000000000L).isEmpty());
        assertTrue(store.read(0, new byte[]{0, (byte) 255, 'b', 'i', 'n', 'a', 'r', 'y'}, Long.MAX_VALUE).isPresent());
        assertEquals(fixture(), store.exportSnapshot());
        assertEquals(2, store.audit(Long.MAX_VALUE).expiredEntries());
        assertThrows(IllegalArgumentException.class, () -> store.audit(-1));
    }

    @Test void identicalRetryIsIdempotentButChangedTargetOrInputCannotBeOverwritten() throws Exception {
        Path path = temporary.resolve("retry.sqlite");
        var store = new SqliteMigrationStore(path);
        var source = fixture();
        store.importSnapshot(source);
        assertEquals(ALREADY_IMPORTED, new SqliteMigrationStore(path).importSnapshot(source));
        var different = new RedisSnapshot(FORMAT, VERSION, source.capturedAtEpochMs() + 1, source.databases(), source.entries());
        assertThrows(SQLException.class, () -> store.importSnapshot(different));
        assertEquals(source, store.exportSnapshot());
        execute(path, "UPDATE string_values SET value=x'1234'");
        assertThrows(SQLException.class, () -> store.importSnapshot(source));
        assertThrows(SQLException.class, store::exportSnapshot);
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 8})
    void failedImportRollsBackEveryTableAndCanRestart(int afterEntry) throws Exception {
        var store = new SqliteMigrationStore(temporary.resolve("rollback.sqlite"));
        assertThrows(IllegalStateException.class, () -> store.importSnapshot(fixture(), count -> {
            if (count == afterEntry) throw new IllegalStateException("Injected failure");
        }));
        assertFalse(store.audit(0).imported());
        assertEquals(0, store.audit(0).entries());
        assertThrows(SQLException.class, store::exportSnapshot);
        assertEquals(IMPORTED, store.importSnapshot(fixture()));
        assertEquals(fixture(), store.exportSnapshot());
    }

    @Test void interruptionBeforeCommitRollsBackAndLeavesRetryPossible() throws Exception {
        var store = new SqliteMigrationStore(temporary.resolve("interrupted.sqlite"));
        try {
            assertThrows(IOException.class, () -> store.importSnapshot(fixture(), ignored -> Thread.currentThread().interrupt()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(store.audit(0).imported());
        assertEquals(IMPORTED, store.importSnapshot(fixture()));
    }

    @Test void abruptProcessTerminationLeavesNoPartialImportAndCanRestart() throws Exception {
        Path path = temporary.resolve("crashed.sqlite");
        Path input = temporary.resolve("crash-input.json");
        Files.write(input, SnapshotCodec.encode(fixture()));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashWorker.class.getName(),
                input.toString(), path.toString()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("crash-worker.log").toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Crash worker did not finish");
            assertEquals(23, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        var store = new SqliteMigrationStore(path);
        assertFalse(store.audit(0).imported());
        assertEquals(IMPORTED, store.importSnapshot(fixture()));
        assertEquals(fixture(), store.exportSnapshot());
    }

    public static final class CrashWorker {
        public static void main(String[] args) throws Exception {
            new SqliteMigrationStore(Path.of(args[1])).importSnapshot(SnapshotCodec.read(Path.of(args[0])), count -> {
                if (count == 2) Runtime.getRuntime().halt(23);
            });
        }
    }

    @Test void independentConcurrentImportersCannotInterleaveOrOverwrite() throws Exception {
        Path path = temporary.resolve("concurrent.sqlite");
        var first = new SqliteMigrationStore(path);
        var second = new SqliteMigrationStore(path);
        var source = fixture();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); start.await(); return first.importSnapshot(source); });
            var b = executor.submit(() -> { ready.countDown(); start.await(); return second.importSnapshot(source); });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(IMPORTED, ALREADY_IMPORTED), Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
            assertEquals(source, first.exportSnapshot());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void preservesEmptyDeclaredDatabasesAndEmptyStrings() throws Exception {
        var store = new SqliteMigrationStore(temporary.resolve("empty.sqlite"));
        var source = new RedisSnapshot(FORMAT, VERSION, 0, List.of(0, 15), List.of());
        store.importSnapshot(source);
        assertEquals(source, store.exportSnapshot());
        assertTrue(store.audit(0).imported());
        assertEquals(0, store.audit(0).entries());
    }

    @ParameterizedTest @ValueSource(strings = {
            "PRAGMA user_version=999",
            "PRAGMA application_id=0",
            "CREATE TABLE unexpected (id INTEGER)",
            "CREATE TABLE sqliteXunexpected (id INTEGER)",
            "CREATE TRIGGER unexpected AFTER INSERT ON databases BEGIN DELETE FROM databases; END",
            "UPDATE string_values SET value=x'1234'",
            "DELETE FROM list_items WHERE position=1",
            "DELETE FROM redis_keys WHERE kind='HASH'"
    })
    void auditAndImportRejectCorruptionWithoutChangingDatabase(String damage) throws Exception {
        Path path = temporary.resolve("damaged.sqlite");
        var source = fixture();
        var store = new SqliteMigrationStore(path);
        store.importSnapshot(source);
        execute(path, damage);
        byte[] before = Files.readAllBytes(path);
        assertThrows(Exception.class, () -> store.audit(0));
        assertThrows(Exception.class, () -> store.importSnapshot(source));
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void unrecognizedExistingFileAndDatabaseAreNeverOverwritten() throws Exception {
        Path text = temporary.resolve("existing");
        byte[] sentinel = "existing-file-sensitive-content".getBytes(StandardCharsets.UTF_8);
        Files.write(text, sentinel);
        assertThrows(SQLException.class, () -> new SqliteMigrationStore(text).importSnapshot(fixture()));
        assertArrayEquals(sentinel, Files.readAllBytes(text));
        Path other = temporary.resolve("other.sqlite");
        execute(other, "CREATE TABLE other_data(value TEXT)");
        byte[] before = Files.readAllBytes(other);
        assertThrows(SQLException.class, () -> new SqliteMigrationStore(other).importSnapshot(fixture()));
        assertArrayEquals(before, Files.readAllBytes(other));
    }

    @Test void auditNeverCreatesMissingDatabaseAndRejectsSymlinkPaths() throws Exception {
        Path absent = temporary.resolve("absent.sqlite");
        assertThrows(IOException.class, () -> new SqliteMigrationStore(absent).audit(0));
        assertFalse(Files.exists(absent));
        Path real = Files.writeString(temporary.resolve("real"), "sentinel");
        Path link = Files.createSymbolicLink(temporary.resolve("link"), real);
        assertThrows(IOException.class, () -> new SqliteMigrationStore(link));
        Path directoryLink = Files.createSymbolicLink(temporary.resolve("directory-link"), temporary);
        assertThrows(IOException.class, () -> new SqliteMigrationStore(directoryLink.resolve("new.sqlite")));
        assertEquals("sentinel", Files.readString(real));
    }

    @Test void exportIsPrivateLosslessAndCannotClobberAnExistingFile() throws Exception {
        var store = new SqliteMigrationStore(temporary.resolve("export.sqlite"));
        store.importSnapshot(fixture());
        Path exported = temporary.resolve("snapshot.json");
        store.exportSnapshot(exported);
        assertEquals(fixture(), SnapshotCodec.read(exported));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(exported));
        assertThrows(IOException.class, () -> store.exportSnapshot(exported));
        assertEquals(fixture(), SnapshotCodec.read(exported));
    }

    @Test void schemaRejectsNaNScoreBitsAndOutOfRangeDatabaseIds() throws Exception {
        Path path = temporary.resolve("constraints.sqlite");
        new SqliteMigrationStore(path).importSnapshot(fixture());
        assertThrows(SQLException.class, () -> execute(path, "UPDATE sorted_set_members SET score_bits=9221120237041090560"));
        assertThrows(SQLException.class, () -> execute(path, "INSERT INTO databases(id) VALUES(4294967296)"));
        assertThrows(SQLException.class, () -> execute(path, "UPDATE migration_meta SET source_sha256=NULL"));
    }

    private static Connection jdbc(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static void execute(Path path, String sql) throws SQLException {
        try (Connection connection = jdbc(path); var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
