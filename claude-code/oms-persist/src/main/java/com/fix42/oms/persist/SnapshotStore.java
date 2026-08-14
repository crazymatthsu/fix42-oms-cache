package com.fix42.oms.persist;

import com.fix42.oms.proto.CacheSnapshot;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Atomic snapshot files: {@code snapshot-<lastAppliedSeq, zero-padded>.pb}.
 *
 * <p>File format: 8-byte magic {@code FIXSNAP1}, {@code int32 length},
 * protobuf {@link CacheSnapshot} payload, {@code int32 CRC32-of-payload}.
 *
 * <p>Write protocol: write + fsync a {@code .tmp} file, then atomically rename it into
 * place — a crash mid-write leaves only a {@code .tmp} the loader ignores. The loader
 * picks the newest snapshot whose magic/CRC/parse all validate, falling back to older
 * generations, so a corrupt latest snapshot degrades recovery time, never correctness.
 */
final class SnapshotStore {

    static final byte[] MAGIC = "FIXSNAP1".getBytes(StandardCharsets.ISO_8859_1);
    static final String PREFIX = "snapshot-";
    static final String SUFFIX = ".pb";

    private SnapshotStore() {
    }

    static Path write(Path dir, CacheSnapshot snapshot, int keep) throws IOException {
        byte[] payload = snapshot.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + 4 + payload.length + 4);
        buf.put(MAGIC);
        buf.putInt(payload.length);
        buf.put(payload);
        buf.putInt((int) crc.getValue());
        buf.flip();

        String name = PREFIX + String.format("%020d", snapshot.getLastAppliedSequence()) + SUFFIX;
        Path tmp = dir.resolve(name + ".tmp");
        Path target = dir.resolve(name);

        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        prune(dir, keep);
        return target;
    }

    /** Newest snapshot that fully validates, or {@code null} if none. */
    static CacheSnapshot loadLatest(Path dir) throws IOException {
        for (Path p : snapshotsNewestFirst(dir)) {
            CacheSnapshot snap = tryLoad(p);
            if (snap != null) {
                return snap;
            }
        }
        return null;
    }

    private static CacheSnapshot tryLoad(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < MAGIC.length + 8) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            return null;
        }
        int length = buf.getInt();
        if (length < 0 || length != buf.remaining() - 4) {
            return null;
        }
        byte[] payload = new byte[length];
        buf.get(payload);
        int declaredCrc = buf.getInt();
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != declaredCrc) {
            return null;
        }
        try {
            return CacheSnapshot.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    /**
     * The smallest high-water mark among retained snapshots that actually VALIDATE
     * (magic/CRC/parse), or 0 if none. Journal compaction must not delete past THIS
     * point: if the newest snapshot is later found corrupt, the loader falls back to an
     * older one and needs the journal records between the two — so corrupt files never
     * anchor compaction.
     */
    static long oldestValidSequence(Path dir) throws IOException {
        long oldest = 0;
        boolean any = false;
        for (Path p : snapshotsNewestFirst(dir)) {
            CacheSnapshot snap = tryLoad(p);
            if (snap != null) {
                oldest = any ? Math.min(oldest, snap.getLastAppliedSequence()) : snap.getLastAppliedSequence();
                any = true;
            }
        }
        return any ? oldest : 0;
    }

    /**
     * fsync a directory so renames/creates/deletes within it are durable. Returns false
     * where the platform/filesystem cannot fsync a directory — callers that depend on the
     * durability (journal compaction) must then skip the dependent action.
     */
    static boolean fsyncDir(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void prune(Path dir, int keep) throws IOException {
        List<Path> snaps = snapshotsNewestFirst(dir);
        for (int i = keep; i < snaps.size(); i++) {
            Files.deleteIfExists(snaps.get(i));
        }
        // Clear stray .tmp leftovers from crashed snapshot writes.
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith(PREFIX)
                    && f.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static List<Path> snapshotsNewestFirst(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
                    })
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }
}
