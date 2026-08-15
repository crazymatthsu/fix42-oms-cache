package com.fix42.oms.parquet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * An {@link ObjectStore} backed by a directory.
 *
 * <p>Two real uses beyond tests: an archive whose "object store" is an NFS/S3-mounted path,
 * and a staging directory that a separate transfer tool (an AWS CLI sync, say) drains. Files
 * are copied byte-for-byte — unlike {@link DuckDbS3ObjectStore}, nothing is re-encoded — via
 * a hidden temp file and an atomic rename, so a reader on the far side never opens a
 * half-copied Parquet file.
 */
public final class LocalDirectoryObjectStore implements ObjectStore {

    private final Path root;

    public LocalDirectoryObjectStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public void put(Path localFile, String key) {
        Path target = resolveKey(key);
        Path temp = ParquetPaths.tempSibling(target);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(localFile, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best effort.
            }
            throw new ArchiveException("Could not store " + localFile + " as " + key + " under " + root,
                    new UncheckedIOException(e));
        }
    }

    @Override
    public String describe() {
        return root.toString();
    }

    /** Resolve {@code key} under the root, refusing anything that would escape it. */
    private Path resolveKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("key escapes the store root: " + key);
        }
        return resolved;
    }
}
