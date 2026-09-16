/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Schema changes require a new version and an explicit, separately tested migration. */
final class SqliteSchema {
    static final int APPLICATION_ID = 0x45424c4b;
    static final int VERSION = 2;
    static final String PROVENANCE_TABLE = "CREATE TABLE source_provenance (id INTEGER PRIMARY KEY CHECK(id=1), document TEXT NOT NULL CHECK(length(document)<=2048)) STRICT";
    static final List<String> TABLES = List.of(
            "CREATE TABLE migration_meta (id INTEGER PRIMARY KEY CHECK(id=1), source_sha256 TEXT, captured_at_ms INTEGER CHECK(captured_at_ms>=0), CHECK((source_sha256 IS NULL AND captured_at_ms IS NULL) OR (source_sha256 IS NOT NULL AND length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^0-9a-f]*' AND captured_at_ms IS NOT NULL))) STRICT",
            "CREATE TABLE databases (id INTEGER PRIMARY KEY CHECK(id BETWEEN 0 AND 2147483647)) STRICT",
            "CREATE TABLE redis_keys (database_id INTEGER NOT NULL, key BLOB NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('STRING','HASH','SET','LIST','SORTED_SET')), expires_at_ms INTEGER CHECK(expires_at_ms>=0), PRIMARY KEY(database_id,key), UNIQUE(database_id,key,kind), FOREIGN KEY(database_id) REFERENCES databases(id)) STRICT, WITHOUT ROWID",
            valueTable("string_values", "STRING", "value BLOB NOT NULL", "database_id,key"),
            valueTable("hash_fields", "HASH", "field BLOB NOT NULL, value BLOB NOT NULL", "database_id,key,field"),
            valueTable("set_members", "SET", "member BLOB NOT NULL", "database_id,key,member"),
            valueTable("list_items", "LIST", "position INTEGER NOT NULL CHECK(position>=0), value BLOB NOT NULL", "database_id,key,position"),
            valueTable("sorted_set_members", "SORTED_SET", "member BLOB NOT NULL, score_bits INTEGER NOT NULL CHECK((score_bits & 9218868437227405312)!=9218868437227405312 OR (score_bits & 4503599627370495)=0)", "database_id,key,member")
    );

    private SqliteSchema() { }

    private static String valueTable(String table, String kind, String columns, String primaryKey) {
        return "CREATE TABLE " + table + " (database_id INTEGER NOT NULL, key BLOB NOT NULL, kind TEXT NOT NULL DEFAULT '"
                + kind + "' CHECK(kind='" + kind + "'), " + columns + ", PRIMARY KEY(" + primaryKey
                + "), FOREIGN KEY(database_id,key,kind) REFERENCES redis_keys(database_id,key,kind)) STRICT, WITHOUT ROWID";
    }

    static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : TABLES) statement.execute(sql);
            statement.execute(PROVENANCE_TABLE);
            statement.execute("INSERT INTO migration_meta(id) VALUES(1)");
            statement.execute("PRAGMA application_id=" + APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + VERSION);
        }
    }

    static void verify(Connection connection) throws SQLException {
        long version = integer(connection, "PRAGMA user_version");
        if (integer(connection, "PRAGMA application_id") != APPLICATION_ID || (version != 1 && version != VERSION)) {
            throw new SQLException("Unsupported SQLite database identity or schema version");
        }
        List<String> actual = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY name")) {
            while (result.next()) actual.add(result.getString(1));
        }
        List<String> expected = new ArrayList<>(TABLES);
        if (version == 2) expected.add(PROVENANCE_TABLE);
        if (!actual.stream().sorted().toList().equals(expected.stream().sorted().toList())) {
            throw new SQLException("SQLite schema does not match the migration contract");
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                throw new SQLException("SQLite integrity check failed");
            }
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) throw new SQLException("SQLite foreign key check failed");
        }
        if (integer(connection, "SELECT count(*) FROM migration_meta WHERE id=1") != 1) {
            throw new SQLException("SQLite migration metadata is missing");
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            if (!result.next() || !"wal".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite migration database is not in WAL mode");
            }
        }
    }

    static long integer(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Missing SQLite result");
            return result.getLong(1);
        }
    }
}
