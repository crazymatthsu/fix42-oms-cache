package com.fix42.oms.persist;

import com.fix42.oms.proto.Checkpoint;
import com.fix42.oms.proto.Snapshot;
import com.fix42.oms.proto.WalRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Length-prefixed protobuf WAL plus atomically replaced snapshots.
 *
 * <pre>
 *   dataDir/wal/wal-0000000001.log
 *   dataDir/snapshot/snap-N.pb
 *   dataDir/snapshot/CURRENT
 *   dataDir/checkpoint.pb
 * </pre>
 */
public final class FileStateStore implements StateStore {
    static final String WAL_DIR = "wal";
    static final String SNAP_DIR = "snapshot";
    static final String CURRENT = "CURRENT";
    static final String CHECKPOINT = "checkpoint.pb";
    private static final Pattern WAL_NAME = Pattern.compile("wal-(\\d+)\\.log");
    private static final Pattern SNAP_NAME = Pattern.compile("snap-(\\d+)\\.pb");
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;

    private final PersistenceConfig config;
    private final Path dataDir;
    private final Path walDir;
    private final Path snapDir;
    private final List<Segment> segments = new ArrayList<>();
    private Segment current;
    private long lastSeq;
    private int recordsSinceForce;
    private long lastForceEpochMs;
    private boolean closed;

    public FileStateStore(PersistenceConfig config) {
        this.config = config;
        this.dataDir = config.dataDir();
        this.walDir = dataDir.resolve(WAL_DIR);
        this.snapDir = dataDir.resolve(SNAP_DIR);
        try {
            Files.createDirectories(walDir);
            Files.createDirectories(snapDir);
            openExisting();
        } catch (IOException e) {
            throw new PersistenceException("Failed to open state store at " + dataDir, e);
        }
    }

    @Override
    public synchronized long append(WalRecord record) {
        ensureOpen();
        long seq = lastSeq + 1;
        WalRecord stamped = record.toBuilder()
                .setSeq(seq)
                .setEpochMs(record.getEpochMs() == 0 ? System.currentTimeMillis() : record.getEpochMs())
                .build();
        try {
            writeRecord(current.channel, stamped);
            current.lastSeq = seq;
            lastSeq = seq;
            maybeForce();
            return seq;
        } catch (IOException e) {
            throw new PersistenceException("WAL append failed at seq " + seq, e);
        }
    }

    @Override
    public synchronized void checkpoint(Snapshot snapshot) {
        ensureOpen();
        long seq = snapshot.getUpToSeq();
        Path tmp = snapDir.resolve("snap-" + seq + ".tmp");
        Path dest = snapDir.resolve("snap-" + seq + ".pb");
        try {
            writeAtomic(tmp, dest, snapshot.toByteArray());
            writeCurrent(dest.getFileName().toString());
            writeCheckpoint(seq, snapshot.hasSourceCursor() ? snapshot.getSourceCursor() : null);
            rotateIfCovered(seq);
            compactWal(seq);
            compactSnapshots(seq);
        } catch (IOException e) {
            throw new PersistenceException("Snapshot checkpoint failed at seq " + seq, e);
        }
    }

    @Override
    public synchronized RecoveryImage recover() {
        ensureOpen();
        Optional<Snapshot> snapshot = loadSnapshot();
        long after = snapshot.map(Snapshot::getUpToSeq).orElse(0L);
        List<WalRecord> all = readAllCompleteRecords();
        List<WalRecord> tail = new ArrayList<>();
        String cursor = snapshot.filter(Snapshot::hasSourceCursor).map(Snapshot::getSourceCursor).orElse(null);
        long maxSeq = after;
        for (WalRecord record : all) {
            if (record.getSeq() > maxSeq) {
                maxSeq = record.getSeq();
            }
            if (record.hasSourceCursor()) {
                cursor = record.getSourceCursor();
            }
            if (record.getSeq() > after) {
                tail.add(record);
            }
        }
        lastSeq = Math.max(lastSeq, maxSeq);
        return new RecoveryImage(snapshot.orElse(null), tail, lastSeq, cursor);
    }

    @Override
    public synchronized long lastSeq() {
        return lastSeq;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            forceCurrent();
            if (current != null) {
                current.channel.close();
            }
        } catch (IOException e) {
            throw new PersistenceException("Failed to close state store", e);
        } finally {
            closed = true;
        }
    }

    private void openExisting() throws IOException {
        List<Path> walFiles = listMatching(walDir, WAL_NAME);
        walFiles.sort(Comparator.comparingLong(FileStateStore::walFirstSeq));
        if (walFiles.isEmpty()) {
            current = openSegment(1L, true);
            segments.add(current);
            lastSeq = 0L;
            return;
        }
        for (int i = 0; i < walFiles.size(); i++) {
            Path path = walFiles.get(i);
            boolean appendable = i == walFiles.size() - 1;
            Segment segment = openSegmentPath(path, walFirstSeq(path), appendable);
            ScanResult scan = scanSegment(segment, appendable);
            segment.lastSeq = scan.lastSeq;
            lastSeq = Math.max(lastSeq, scan.lastSeq);
            segments.add(segment);
            if (appendable) {
                current = segment;
            } else {
                segment.channel.close();
            }
        }
        if (current == null) {
            current = openSegment(lastSeq + 1, true);
            segments.add(current);
        }
    }

    private Segment openSegment(long firstSeq, boolean write) throws IOException {
        Path path = walDir.resolve(String.format(Locale.ROOT, "wal-%010d.log", firstSeq));
        return openSegmentPath(path, firstSeq, write);
    }

    private static Segment openSegmentPath(Path path, long firstSeq, boolean write) throws IOException {
        FileChannel channel;
        if (write) {
            channel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        } else {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        }
        Segment segment = new Segment(path, firstSeq, channel);
        segment.lastSeq = firstSeq - 1;
        return segment;
    }

    private ScanResult scanSegment(Segment segment, boolean truncateTorn) throws IOException {
        FileChannel channel = segment.channel;
        channel.position(0);
        ByteBuffer header = ByteBuffer.allocate(4);
        long lastComplete = segment.firstSeq - 1;
        long lastGoodPosition = 0;
        while (true) {
            header.clear();
            int read = readFully(channel, header);
            if (read == 0) {
                break;
            }
            if (read < 4) {
                break;
            }
            header.flip();
            int length = header.getInt();
            if (length <= 0 || length > MAX_RECORD_BYTES) {
                break;
            }
            ByteBuffer body = ByteBuffer.allocate(length);
            int bodyRead = readFully(channel, body);
            if (bodyRead < length) {
                break;
            }
            WalRecord record = WalRecord.parseFrom(body.array());
            lastComplete = record.getSeq();
            lastGoodPosition = channel.position();
        }
        if (truncateTorn && channel.size() > lastGoodPosition) {
            channel.truncate(lastGoodPosition);
        }
        if (truncateTorn) {
            channel.position(channel.size());
        }
        return new ScanResult(lastComplete);
    }

    private List<WalRecord> readAllCompleteRecords() {
        List<WalRecord> records = new ArrayList<>();
        try {
            List<Path> walFiles = listMatching(walDir, WAL_NAME);
            walFiles.sort(Comparator.comparingLong(FileStateStore::walFirstSeq));
            for (Path path : walFiles) {
                boolean last = path.equals(walFiles.get(walFiles.size() - 1));
                try (FileChannel channel = last && current != null && path.equals(current.path)
                        ? null
                        : FileChannel.open(path, StandardOpenOption.READ)) {
                    FileChannel src = channel;
                    long restore = -1;
                    if (src == null) {
                        src = current.channel;
                        restore = src.position();
                        src.position(0);
                    }
                    try {
                        records.addAll(readComplete(src));
                    } finally {
                        if (restore >= 0) {
                            src.position(restore);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new PersistenceException("Failed to read WAL", e);
        }
        return records;
    }

    private static List<WalRecord> readComplete(FileChannel channel) throws IOException {
        List<WalRecord> records = new ArrayList<>();
        ByteBuffer header = ByteBuffer.allocate(4);
        while (true) {
            header.clear();
            int read = readFully(channel, header);
            if (read < 4) {
                break;
            }
            header.flip();
            int length = header.getInt();
            if (length <= 0 || length > MAX_RECORD_BYTES) {
                break;
            }
            ByteBuffer body = ByteBuffer.allocate(length);
            if (readFully(channel, body) < length) {
                break;
            }
            records.add(WalRecord.parseFrom(body.array()));
        }
        return records;
    }

    private Optional<Snapshot> loadSnapshot() {
        try {
            Optional<Path> named = readCurrent();
            if (named.isPresent() && Files.exists(named.get())) {
                return Optional.of(Snapshot.parseFrom(Files.readAllBytes(named.get())));
            }
            List<Path> snaps = listMatching(snapDir, SNAP_NAME);
            if (snaps.isEmpty()) {
                return Optional.empty();
            }
            snaps.sort(Comparator.comparingLong(FileStateStore::snapSeq));
            Path latest = snaps.get(snaps.size() - 1);
            return Optional.of(Snapshot.parseFrom(Files.readAllBytes(latest)));
        } catch (IOException e) {
            throw new PersistenceException("Failed to load snapshot", e);
        }
    }

    private Optional<Path> readCurrent() throws IOException {
        Path currentFile = snapDir.resolve(CURRENT);
        if (!Files.exists(currentFile)) {
            return Optional.empty();
        }
        String name = Files.readString(currentFile, StandardCharsets.UTF_8).trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapDir.resolve(name));
    }

    private void writeCurrent(String fileName) throws IOException {
        Path tmp = snapDir.resolve(CURRENT + ".tmp");
        Path dest = snapDir.resolve(CURRENT);
        Files.writeString(tmp, fileName + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        forcePath(tmp);
        atomicMove(tmp, dest);
    }

    private void writeCheckpoint(long seq, String cursor) throws IOException {
        Checkpoint.Builder builder = Checkpoint.newBuilder().setLastSeq(seq);
        if (cursor != null && !cursor.isEmpty()) {
            builder.setSourceCursor(cursor);
        }
        Path tmp = dataDir.resolve(CHECKPOINT + ".tmp");
        Path dest = dataDir.resolve(CHECKPOINT);
        writeAtomic(tmp, dest, builder.build().toByteArray());
    }

    private void writeAtomic(Path tmp, Path dest, byte[] bytes) throws IOException {
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        forcePath(tmp);
        atomicMove(tmp, dest);
    }

    private static void atomicMove(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forcePath(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void writeRecord(FileChannel channel, WalRecord record) throws IOException {
        byte[] payload = record.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void maybeForce() throws IOException {
        FsyncMode mode = config.fsync();
        if (mode == FsyncMode.NONE) {
            return;
        }
        if (mode == FsyncMode.EVERY_RECORD) {
            forceCurrent();
            return;
        }
        recordsSinceForce++;
        long now = System.currentTimeMillis();
        if (recordsSinceForce >= config.groupForceEveryMessages()
                || now - lastForceEpochMs >= config.groupForceEvery().toMillis()) {
            forceCurrent();
        }
    }

    private void forceCurrent() throws IOException {
        if (current != null) {
            current.channel.force(false);
        }
        recordsSinceForce = 0;
        lastForceEpochMs = System.currentTimeMillis();
    }

    private void rotateIfCovered(long upToSeq) throws IOException {
        if (current == null) {
            return;
        }
        if (current.lastSeq <= upToSeq && current.lastSeq >= current.firstSeq) {
            current.channel.force(false);
            current.channel.close();
            current = openSegment(lastSeq + 1, true);
            segments.add(current);
        }
    }

    private void compactWal(long upToSeq) throws IOException {
        Iterator<Segment> it = segments.iterator();
        while (it.hasNext()) {
            Segment segment = it.next();
            if (segment == current) {
                continue;
            }
            if (segment.lastSeq > 0 && segment.lastSeq <= upToSeq) {
                Files.deleteIfExists(segment.path);
                it.remove();
            }
        }
    }

    private void compactSnapshots(long keepSeq) throws IOException {
        List<Path> snaps = listMatching(snapDir, SNAP_NAME);
        snaps.sort(Comparator.comparingLong(FileStateStore::snapSeq));
        Path keep = snapDir.resolve("snap-" + keepSeq + ".pb");
        for (Path snap : snaps) {
            if (!snap.equals(keep)) {
                Files.deleteIfExists(snap);
            }
        }
        Files.deleteIfExists(snapDir.resolve("snap-" + keepSeq + ".tmp"));
        Files.deleteIfExists(snapDir.resolve(CURRENT + ".tmp"));
    }

    private static List<Path> listMatching(Path dir, Pattern pattern) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (pattern.matcher(path.getFileName().toString()).matches()) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    private static long walFirstSeq(Path path) {
        Matcher matcher = WAL_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return 0L;
        }
        return Long.parseLong(matcher.group(1));
    }

    private static long snapSeq(Path path) {
        Matcher matcher = SNAP_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return 0L;
        }
        return Long.parseLong(matcher.group(1));
    }

    private static int readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    private void ensureOpen() {
        if (closed) {
            throw new PersistenceException("State store is closed");
        }
    }

    private static final class Segment {
        final Path path;
        final long firstSeq;
        final FileChannel channel;
        long lastSeq;

        Segment(Path path, long firstSeq, FileChannel channel) {
            this.path = path;
            this.firstSeq = firstSeq;
            this.channel = channel;
        }
    }

    private static final class ScanResult {
        final long lastSeq;

        ScanResult(long lastSeq) {
            this.lastSeq = lastSeq;
        }
    }
}
