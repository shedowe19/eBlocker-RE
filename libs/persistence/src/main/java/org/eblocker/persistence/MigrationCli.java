/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.nio.file.Path;

/** Explicit offline paths only; no Redis connection, service control or runtime cutover. */
public final class MigrationCli {
    private MigrationCli() { }

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    static int run(String[] args, PrintStream out, PrintStream errors) {
        if (args.length == 0 || !(switch (args[0]) {
            case "import", "migrate", "export" -> args.length == 3;
            case "rdb-import", "rdb-export" -> args.length == 3 || args.length == 4;
            case "audit" -> args.length == 2;
            default -> false;
        })) {
            errors.println("Usage: import|migrate <snapshot.json> <new.sqlite> | export <source.sqlite> <new.json> | audit <source.sqlite> | rdb-import <source.rdb> <new.sqlite> [capture-epoch-ms] | rdb-export <source.rdb> <new.json> [capture-epoch-ms]");
            return 2;
        }
        try {
            switch (args[0]) {
                case "rdb-import" -> out.println(RdbImporter.importRdb(Path.of(args[1]), Path.of(args[2]),
                        args.length == 4 ? Long.parseLong(args[3]) : null).name());
                case "rdb-export" -> {
                    RedisSnapshot snapshot = RdbImporter.read(Path.of(args[1]), args.length == 4 ? Long.parseLong(args[3]) : null);
                    byte[] bytes = SnapshotCodec.encode(snapshot);
                    try { PrivateFiles.writeNew(Path.of(args[2]), bytes); }
                    finally { java.util.Arrays.fill(bytes, (byte) 0); }
                    out.println("EXPORTED");
                }
                case "import", "migrate" -> {
                    RedisSnapshot snapshot = SnapshotCodec.read(Path.of(args[1]));
                    var result = new SqliteMigrationStore(Path.of(args[2])).importSnapshot(snapshot);
                    out.println(result.name());
                }
                case "export" -> {
                    new SqliteMigrationStore(Path.of(args[1])).exportSnapshot(Path.of(args[2]));
                    out.println("EXPORTED");
                }
                case "audit" -> out.println(JsonMapper.builder().build().writeValueAsString(
                        new SqliteMigrationStore(Path.of(args[1])).audit(System.currentTimeMillis())));
                default -> throw new IllegalStateException("Unknown operation");
            }
            return 0;
        } catch (Exception error) {
            // JDBC/parser errors can contain secrets, SQL values or paths. Do not forward them.
            errors.println("Migration operation failed; verify the offline format, destination and database integrity.");
            return 1;
        }
    }
}
