/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationCliTest {
    @TempDir Path temporary;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

    @Test void completeOfflineCliRoundtripAndRetry() throws Exception {
        Path source = temporary.resolve("input.json");
        Files.write(source, SnapshotCodec.encode(SqliteMigrationStoreTest.fixture()));
        Path database = temporary.resolve("output.sqlite");
        Path exported = temporary.resolve("output.json");
        assertEquals(0, run("import", source.toString(), database.toString()));
        assertEquals("IMPORTED\n", output.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(0, run("migrate", source.toString(), database.toString()));
        assertEquals("ALREADY_IMPORTED\n", output.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(0, run("audit", database.toString()));
        var audit = JsonMapper.builder().build().readTree(output.toByteArray());
        assertTrue(audit.get("imported").asBoolean());
        assertEquals(8, audit.get("entries").asInt());
        assertEquals(5, audit.get("entriesByType").size());
        assertEquals(0, run("export", database.toString(), exported.toString()));
        assertEquals(SnapshotCodec.read(source), SnapshotCodec.read(exported));
        assertEquals(1, run("export", database.toString(), exported.toString()));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("secret-fixture-only"));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret-fixture-only"));
    }

    @Test void invalidArgumentsAndSensitiveParserFailuresHaveStableSafeOutput() throws Exception {
        assertEquals(2, run());
        assertEquals(2, run("unknown"));
        assertEquals(2, run("import", "missing"));
        output.reset(); errors.reset();
        Path invalid = Files.writeString(temporary.resolve("secret-fixture-only.json"), "{\"secret-fixture-only\":");
        assertEquals(1, run("import", invalid.toString(), temporary.resolve("unused.sqlite").toString()));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret-fixture-only"));
        assertFalse(Files.exists(temporary.resolve("unused.sqlite")));
    }

    @Test void rdbCommandsImportExportAuditAndRejectOverwriteWithoutPrintingData() throws Exception {
        Path source = RdbImporterTest.resource("canonical-all-encodings.rdb");
        Path database = temporary.resolve("rdb.sqlite");
        Path logical = temporary.resolve("rdb.json");
        assertEquals(0, run("rdb-export", source.toString(), logical.toString()));
        assertEquals(2, SnapshotCodec.read(logical).version());
        assertEquals(1, run("rdb-export", source.toString(), logical.toString()));
        assertEquals(0, run("rdb-import", source.toString(), database.toString()));
        output.reset();
        assertEquals(0, run("rdb-import", source.toString(), database.toString(), "1700000000000"));
        assertEquals("ALREADY_IMPORTED\n", output.toString(StandardCharsets.UTF_8));
        output.reset();
        assertEquals(0, run("audit", database.toString()));
        var audit = JsonMapper.builder().build().readTree(output.toByteArray());
        assertEquals(11, audit.get("provenance").get("rdbVersion").asInt());
        assertEquals(27, audit.get("entries").asInt());
        assertEquals(SnapshotCodec.read(logical), new SqliteMigrationStore(database).exportSnapshot());
        assertEquals(1, run("rdb-import", source.toString(), temporary.resolve("bad.sqlite").toString(), "secret-fixture-only"));
        assertFalse(Files.exists(temporary.resolve("bad.sqlite")));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret-fixture-only"));
        assertEquals(2, run("rdb-import", source.toString()));
        assertEquals(2, run("rdb-export", source.toString(), "one", "two", "three"));
    }

    private int run(String... args) {
        return MigrationCli.run(args, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
    }
}
