package com.fix42.oms.persist;

import com.fix42.oms.proto.JournalRecord;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Append-only journal writer.
 *
 * <p>Segment files are named {@code journal-<startSeq, zero-padded>.log} where
 * {@code startSeq} is the sequence of the first record the segment holds — names sort
 * lexically into replay order. Each segment starts with an 8-byte magic; each record is
 * framed {@code [int32 length][protobuf JournalRecord][int32 CRC32-of-payload]} so a torn
 * tail or corrupt record is detectable on replay.
 *
 * <p>Not thread-safe on its own; {@link PersistentOrderCache} serializes access.
 */
final class JournalWriter implements Closeable {

    static final byte[] SEGMENT_MAGIC = "FIXJRNL1".getBytes(StandardCharsets.ISO_8859_1);
    static final String SEGMENT_PREFIX = "journal-";
    static final String SEGMENT_SUFFIX = ".log";

    /**
     * Shared writer/reader record-size bound. The writer REJECTS larger payloads before
     * writing — an oversized record would be acked but unreadable (the reader treats an
     * over-bound frame length as corruption).
     */
    static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private final Path dir;
    private final PersistenceConfig config;
    private FileChannel channel;
    private Path currentSegment;
    private long currentSize;

    JournalWriter(Path dir, long startSequence, PersistenceConfig config) throws IOException {
        this.dir = dir;
        this.config = config;
        openSegment(startSequence);
    }

    static String segmentName(long startSequence) {
        return SEGMENT_PREFIX + String.format("%020d", startSequence) + SEGMENT_SUFFIX;
    }

    /** The startSeq encoded in a segment file name, or -1 if the name doesn't match. */
    static long startSequenceOf(Path segment) {
        String name = segment.getFileName().toString();
        if (!name.startsWith(SEGMENT_PREFIX) || !name.endsWith(SEGMENT_SUFFIX)) {
            return -1;
        }
        try {
            return Long.parseLong(name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void append(JournalRecord record) throws IOException {
        byte[] payload = record.toByteArray();
        if (payload.length > MAX_RECORD_BYTES) {
            throw new IOException("Journal record of " + payload.length
                    + " bytes exceeds the " + MAX_RECORD_BYTES + "-byte record bound");
        }
        int frameSize = 4 + payload.length + 4;

        // Rotate before appending if this frame would overflow the segment
        // (a segment always holds at least one record, however large).
        if (currentSize > SEGMENT_MAGIC.length && currentSize + frameSize > config.maxSegmentBytes()) {
            closeChannel();
            openSegment(record.getSequence());
        }

        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteBuffer frame = ByteBuffer.allocate(frameSize);
        frame.putInt(payload.length);
        frame.put(payload);
        frame.putInt((int) crc.getValue());
        frame.flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
        currentSize += frameSize;

        if (config.fsyncPolicy() == PersistenceConfig.FsyncPolicy.EVERY_RECORD) {
            channel.force(false);
        }
    }

    /** Flush buffered writes to disk regardless of the fsync policy. */
    void sync() throws IOException {
        channel.force(false);
    }

    Path currentSegment() {
        return currentSegment;
    }

    @Override
    public void close() throws IOException {
        closeChannel();
    }

    private void openSegment(long startSequence) throws IOException {
        currentSegment = dir.resolve(segmentName(startSequence));
        channel = FileChannel.open(currentSegment,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        ByteBuffer magic = ByteBuffer.wrap(SEGMENT_MAGIC);
        while (magic.hasRemaining()) {
            channel.write(magic);
        }
        currentSize = SEGMENT_MAGIC.length;
        // Make the new directory entry durable before any record in this segment is
        // acknowledged — otherwise a power loss after rotation could vanish the whole
        // segment. Best-effort where the filesystem can't fsync a directory.
        SnapshotStore.fsyncDir(dir);
    }

    private void closeChannel() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }
}
