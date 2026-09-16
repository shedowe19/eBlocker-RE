/* SPDX-License-Identifier: EUPL-1.2 */
package org.eblocker.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Private staging files, published with an atomic no-clobber hard link on the same filesystem. */
final class PrivateFiles {
    private PrivateFiles() { }

    static Path target(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent) || !parent.equals(parent.toRealPath())) {
            throw new IOException("Migration target requires an existing non-symlink directory");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Migration target must be a regular file");
        }
        return path;
    }

    static Path stage(Path target) throws IOException {
        return Files.createTempFile(target.getParent(), ".eblocker-migration-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    static void publish(Path stage, Path target) throws IOException {
        // Unlike ATOMIC_MOVE, CREATE LINK cannot silently replace a concurrently created target.
        Files.createLink(target, stage);
        try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(target.getParent(),
                java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
    }

    static void writeNew(Path requested, byte[] bytes) throws IOException {
        Path target = target(requested);
        Path stage = stage(target);
        try {
            Files.write(stage, bytes);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(stage,
                    java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            publish(stage, target);
        } finally {
            Files.deleteIfExists(stage);
        }
    }
}
