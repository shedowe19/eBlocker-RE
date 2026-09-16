/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import static org.eblocker.persistence.RedisSnapshot.*;

/** Offline migration store. It deliberately does not implement the live Redis/Jedis API. */
public final class SqliteMigrationStore {
    public enum ImportResult { IMPORTED, ALREADY_IMPORTED }
    public record Audit(int schemaVersion, boolean imported, String sha256, long capturedAtEpochMs,
                        int databases, int entries, long expiredEntries, Map<Type, Long> entriesByType,
                        Provenance provenance) { }
    private final Path database;

    public SqliteMigrationStore(Path database) throws IOException {
        this.database = PrivateFiles.target(database);
    }

    public ImportResult importSnapshot(RedisSnapshot snapshot) throws IOException, SQLException {
        return importSnapshot(snapshot, ignored -> { });
    }

    ImportResult importSnapshot(RedisSnapshot snapshot, IntConsumer afterEntry) throws IOException, SQLException {
        String digest = SnapshotCodec.digest(snapshot);
        initializeIfAbsent();
        try (Connection connection = connect(false)) {
            connection.setAutoCommit(false); // IMMEDIATE: concurrent importers cannot interleave.
            try {
                SqliteSchema.verify(connection);
                String previous = previousDigest(connection);
                if (previous != null) {
                    if (!previous.equals(digest) || !SnapshotCodec.digest(snapshot(connection)).equals(previous)) {
                        throw new SQLException("Target contains a different or modified dataset; overwriting is forbidden");
                    }
                    connection.rollback();
                    return ImportResult.ALREADY_IMPORTED;
                }
                if (SqliteSchema.integer(connection, "SELECT count(*) FROM redis_keys") != 0
                        || SqliteSchema.integer(connection, "SELECT count(*) FROM databases") != 0
                        || readProvenance(connection) != null) {
                    throw new SQLException("Target is not an empty initialized migration store");
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO databases(id) VALUES(?)")) {
                    for (int id : snapshot.databases()) { insert.setInt(1, id); insert.executeUpdate(); }
                }
                int imported = 0;
                for (Entry entry : snapshot.entries()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Migration interrupted");
                    insert(connection, entry);
                    afterEntry.accept(++imported);
                }
                if (snapshot.provenance() != null) {
                    if (SqliteSchema.integer(connection, "PRAGMA user_version") < 2) {
                        throw new SQLException("RDB provenance requires a new version 2 database");
                    }
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO source_provenance(id,document) VALUES(1,?)")) {
                        insert.setString(1, SnapshotCodec.encodeProvenance(snapshot.provenance()));
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE migration_meta SET source_sha256=?, captured_at_ms=? WHERE id=1")) {
                    update.setString(1, digest);
                    update.setLong(2, snapshot.capturedAtEpochMs());
                    update.executeUpdate();
                }
                // Re-read typed rows before committing; no opaque DUMP/JSON blob is the database.
                if (!SnapshotCodec.digest(snapshot(connection)).equals(digest)) {
                    throw new SQLException("Imported SQLite dataset does not round-trip");
                }
                if (Thread.currentThread().isInterrupted()) throw new IOException("Migration interrupted");
                connection.commit();
                return ImportResult.IMPORTED;
            } catch (IOException | SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public RedisSnapshot exportSnapshot() throws IOException, SQLException {
        try (Connection connection = connect(true)) {
            connection.setAutoCommit(false);
            SqliteSchema.verify(connection);
            RedisSnapshot result = snapshot(connection);
            if (!SnapshotCodec.digest(result).equals(previousDigest(connection))) {
                throw new SQLException("SQLite dataset digest does not match its import");
            }
            return result;
        }
    }

    public void exportSnapshot(Path target) throws IOException, SQLException {
        byte[] bytes = SnapshotCodec.encode(exportSnapshot());
        try { PrivateFiles.writeNew(target, bytes); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    /** Reads preserve absolute expiration; exports retain expired records for forensic round-trip. */
    public Optional<Entry> read(int databaseId, byte[] key, long nowEpochMs) throws IOException, SQLException {
        if (nowEpochMs < 0) throw new IllegalArgumentException("Invalid read time");
        String encoded = encode(key);
        return exportSnapshot().entries().stream()
                .filter(entry -> entry.database() == databaseId && entry.key().equals(encoded))
                .filter(entry -> !entry.expiredAt(nowEpochMs)).findFirst();
    }

    public Audit audit(long nowEpochMs) throws IOException, SQLException {
        if (nowEpochMs < 0) throw new IllegalArgumentException("Invalid audit time");
        try (Connection connection = connect(true)) {
            connection.setAutoCommit(false);
            SqliteSchema.verify(connection);
            int version = (int) SqliteSchema.integer(connection, "PRAGMA user_version");
            String digest = previousDigest(connection);
            if (digest == null) {
                if (SqliteSchema.integer(connection, "SELECT count(*) FROM redis_keys") != 0
                        || SqliteSchema.integer(connection, "SELECT count(*) FROM databases") != 0
                        || readProvenance(connection) != null) {
                    throw new SQLException("Incomplete migration metadata");
                }
                return new Audit(version, false, null, 0, 0, 0, 0, Map.of(), null);
            }
            RedisSnapshot snapshot = snapshot(connection);
            if (!SnapshotCodec.digest(snapshot).equals(digest)) throw new SQLException("SQLite dataset digest mismatch");
            Map<Type, Long> counts = new EnumMap<>(Type.class);
            for (Entry entry : snapshot.entries()) counts.merge(entry.type(), 1L, Long::sum);
            return new Audit(version, true, digest, snapshot.capturedAtEpochMs(),
                    snapshot.databases().size(), snapshot.entries().size(),
                    snapshot.entries().stream().filter(entry -> entry.expiredAt(nowEpochMs)).count(), Map.copyOf(counts), snapshot.provenance());
        }
    }

    private void initializeIfAbsent() throws IOException, SQLException {
        if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) return;
        Path stage = PrivateFiles.stage(database);
        try {
            try (Connection connection = connect(stage, false)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                }
                connection.setAutoCommit(false);
                SqliteSchema.initialize(connection);
                connection.commit();
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
                SqliteSchema.verify(connection);
            }
            try { PrivateFiles.publish(stage, database); }
            catch (FileAlreadyExistsException concurrentCreator) { /* The winner is validated before any import. */ }
        } finally {
            Files.deleteIfExists(stage);
            Files.deleteIfExists(Path.of(stage + "-wal"));
            Files.deleteIfExists(Path.of(stage + "-shm"));
        }
    }

    private Connection connect(boolean readOnly) throws IOException, SQLException {
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SQLite migration store does not exist");
        return connect(database, readOnly);
    }

    private static Connection connect(Path path, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(3000);
        config.enforceForeignKeys(true);
        config.setTransactionMode(readOnly ? SQLiteConfig.TransactionMode.DEFERRED : SQLiteConfig.TransactionMode.IMMEDIATE);
        config.setReadOnly(readOnly);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString()
                + "?mode=" + (readOnly ? "ro" : "rw"), config.toProperties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA trusted_schema=OFF");
            statement.execute(readOnly ? "PRAGMA query_only=ON" : "PRAGMA synchronous=FULL");
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }

    private static String previousDigest(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT source_sha256 FROM migration_meta WHERE id=1")) {
            if (!result.next()) throw new SQLException("Missing migration metadata");
            return result.getString(1);
        }
    }

    private static void insert(Connection connection, Entry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO redis_keys(database_id,key,kind,expires_at_ms) VALUES(?,?,?,?)")) {
            key(statement, entry);
            statement.setString(3, entry.type().name());
            if (entry.expiresAtEpochMs() == null) statement.setNull(4, Types.BIGINT);
            else statement.setLong(4, entry.expiresAtEpochMs());
            statement.executeUpdate();
        }
        switch (entry.type()) {
            case STRING -> value(connection, "INSERT INTO string_values(database_id,key,value) VALUES(?,?,?)", entry, entry.value());
            case SET -> {
                for (String member : entry.set()) value(connection, "INSERT INTO set_members(database_id,key,member) VALUES(?,?,?)", entry, member);
            }
            case HASH -> {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO hash_fields(database_id,key,field,value) VALUES(?,?,?,?)")) {
                    for (HashField field : entry.hash()) { key(statement, entry); statement.setBytes(3, binary(field.field())); statement.setBytes(4, binary(field.value())); statement.executeUpdate(); }
                }
            }
            case LIST -> {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO list_items(database_id,key,position,value) VALUES(?,?,?,?)")) {
                    for (int i = 0; i < entry.list().size(); i++) { key(statement, entry); statement.setInt(3, i); statement.setBytes(4, binary(entry.list().get(i))); statement.executeUpdate(); }
                }
            }
            case SORTED_SET -> {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sorted_set_members(database_id,key,member,score_bits) VALUES(?,?,?,?)")) {
                    for (ScoredMember member : entry.sortedSet()) { key(statement, entry); statement.setBytes(3, binary(member.member())); statement.setLong(4, Long.parseUnsignedLong(member.scoreBits(), 16)); statement.executeUpdate(); }
                }
            }
        }
    }

    private static void value(Connection connection, String sql, Entry entry, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            key(statement, entry); statement.setBytes(3, binary(value)); statement.executeUpdate();
        }
    }

    private static void key(PreparedStatement statement, Entry entry) throws SQLException {
        statement.setInt(1, entry.database()); statement.setBytes(2, binary(entry.key()));
    }

    private static RedisSnapshot snapshot(Connection connection) throws SQLException, IOException {
        Long capturedAt;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT captured_at_ms FROM migration_meta WHERE id=1 AND source_sha256 IS NOT NULL")) {
            if (!result.next()) throw new SQLException("Migration has not imported a dataset");
            capturedAt = nullableLong(result, 1);
            if (capturedAt == null) throw new SQLException("Missing snapshot timestamp");
        }
        List<Integer> databases = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id FROM databases ORDER BY id")) {
            while (result.next()) databases.add(result.getInt(1));
        }
        List<Entry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT database_id,key,kind,expires_at_ms FROM redis_keys ORDER BY database_id,key")) {
            while (result.next()) {
                if (entries.size() >= MAX_ENTRIES) throw new SQLException("SQLite dataset exceeds migration limit");
                int db = result.getInt(1); byte[] key = result.getBytes(2);
                Type type = Type.valueOf(result.getString(3)); Long expires = nullableLong(result, 4);
                entries.add(readValue(connection, db, key, type, expires));
            }
        }
        Provenance provenance = readProvenance(connection);
        return new RedisSnapshot(FORMAT, provenance == null ? VERSION : 2, capturedAt, databases, entries, provenance);
    }

    private static Provenance readProvenance(Connection connection) throws SQLException, IOException {
        if (SqliteSchema.integer(connection, "PRAGMA user_version") < 2) return null;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT document FROM source_provenance WHERE id=1")) {
            return result.next() ? SnapshotCodec.readProvenance(result.getString(1)) : null;
        }
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Entry readValue(Connection connection, int db, byte[] key, Type type, Long expires) throws SQLException {
        String sql = switch (type) {
            case STRING -> "SELECT value FROM string_values WHERE database_id=? AND key=?";
            case HASH -> "SELECT field,value FROM hash_fields WHERE database_id=? AND key=? ORDER BY field";
            case SET -> "SELECT member FROM set_members WHERE database_id=? AND key=? ORDER BY member";
            case LIST -> "SELECT value,position FROM list_items WHERE database_id=? AND key=? ORDER BY position";
            case SORTED_SET -> "SELECT member,score_bits FROM sorted_set_members WHERE database_id=? AND key=? ORDER BY member";
        };
        String string = null; List<HashField> hash = new ArrayList<>(); List<String> values = new ArrayList<>(); List<ScoredMember> sorted = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, db); statement.setBytes(2, key);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    switch (type) {
                        case STRING -> string = encode(result.getBytes(1));
                        case HASH -> hash.add(new HashField(encode(result.getBytes(1)), encode(result.getBytes(2))));
                        case SET -> values.add(encode(result.getBytes(1)));
                        case LIST -> {
                            if (result.getLong(2) != values.size()) throw new SQLException("SQLite list positions are not contiguous");
                            values.add(encode(result.getBytes(1)));
                        }
                        case SORTED_SET -> sorted.add(new ScoredMember(encode(result.getBytes(1)),
                                String.format(java.util.Locale.ROOT, "%016x", result.getLong(2))));
                    }
                }
            }
        }
        return new Entry(db, encode(key), type, expires, type == Type.STRING ? string : null,
                type == Type.HASH ? hash : null, type == Type.SET ? values : null,
                type == Type.LIST ? values : null, type == Type.SORTED_SET ? sorted : null);
    }
}
