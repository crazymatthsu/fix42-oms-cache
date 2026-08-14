package com.fix42.oms.persist;

import com.fix42.oms.proto.JournalRecord;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Replays journal segments written by {@link JournalWriter}, enforcing the format's
 * safety invariants:
 *
 * <ul>
 *   <li><b>Strict contiguity.</b> Record sequences must increase by exactly 1 across the
 *       whole journal, and each segment's first record must match its filename. A gap
 *       (e.g. a manually deleted segment) throws — a silent gap would recover a wrong
 *       cache.</li>
 *   <li><b>Version refusal.</b> A complete-but-unknown segment header throws (a newer
 *       format must never be misread as a torn tail).</li>
 *   <li><b>Torn/corrupt data</b> stops replay and is reported with its position
 *       ({@link Corruption}); the caller decides whether it is a healable tail tear
 *       (final segment) or unrecoverable mid-journal corruption.</li>
 * </ul>
 *
 * <p>Records with {@code sequence <= afterSequence} are read (and contiguity-checked)
 * but not delivered — they are covered by the snapshot being recovered from.
 */
final class JournalReader {

    /**
     * Where and why replay stopped early.
     *
     * @param segment       the segment containing the bad data
     * @param goodBytesEnd  file offset of the end of the last valid frame (segment header
     *                      length if no frame was valid; 0 if the header itself is short)
     * @param finalSegment  whether the bad data is in the journal's last segment
     * @param reason        human-readable description
     */
    record Corruption(Path segment, long goodBytesEnd, boolean finalSegment, String reason) {
    }

    /**
     * @param lastSequence   highest record sequence seen (0 if none)
     * @param recordsApplied records delivered to the consumer
     * @param corruption     null when the journal read cleanly to its end
     */
    record Replay(long lastSequence, long recordsApplied, Corruption corruption) {
        boolean cleanTail() {
            return corruption == null;
        }
    }

    static Replay replay(Path dir, long afterSequence, Consumer<JournalRecord> consumer) throws IOException {
        long lastSequence = 0;
        long applied = 0;
        long expectedNext = -1; // -1 until the first record fixes the base

        List<Path> segments = segments(dir);
        for (int i = 0; i < segments.size(); i++) {
            Path segment = segments.get(i);
            boolean finalSegment = (i == segments.size() - 1);
            long segmentStart = JournalWriter.startSequenceOf(segment);
            boolean firstRecordInSegment = true;
            long offset = 0;

            try (InputStream in = new BufferedInputStream(Files.newInputStream(segment));
                 DataInputStream data = new DataInputStream(in)) {

                byte[] magic = new byte[JournalWriter.SEGMENT_MAGIC.length];
                int got = data.readNBytes(magic, 0, magic.length);
                if (got < magic.length) {
                    // Header torn mid-creation: no record was ever acked from this segment.
                    return new Replay(lastSequence, applied,
                            new Corruption(segment, 0, finalSegment, "short segment header"));
                }
                if (!Arrays.equals(magic, JournalWriter.SEGMENT_MAGIC)) {
                    // A complete-but-different header is a format/version mismatch, never
                    // a tear: refuse outright rather than discarding it as corruption.
                    throw new IOException("Unrecognized journal segment format in " + segment
                            + " (magic " + new String(magic, java.nio.charset.StandardCharsets.ISO_8859_1)
                            + "); refusing to replay");
                }
                offset = magic.length;

                while (true) {
                    int length;
                    try {
                        length = data.readInt();
                    } catch (EOFException e) {
                        break; // clean end of segment
                    }
                    if (length <= 0 || length > JournalWriter.MAX_RECORD_BYTES) {
                        return new Replay(lastSequence, applied,
                                new Corruption(segment, offset, finalSegment, "invalid frame length " + length));
                    }
                    byte[] payload = new byte[length];
                    if (data.readNBytes(payload, 0, length) != length) {
                        return new Replay(lastSequence, applied,
                                new Corruption(segment, offset, finalSegment, "torn frame payload"));
                    }
                    int declaredCrc;
                    try {
                        declaredCrc = data.readInt();
                    } catch (EOFException e) {
                        return new Replay(lastSequence, applied,
                                new Corruption(segment, offset, finalSegment, "torn frame trailer"));
                    }
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(payload);
                    if ((int) crc.getValue() != declaredCrc) {
                        return new Replay(lastSequence, applied,
                                new Corruption(segment, offset, finalSegment, "CRC mismatch"));
                    }
                    JournalRecord record;
                    try {
                        record = JournalRecord.parseFrom(payload);
                    } catch (InvalidProtocolBufferException e) {
                        return new Replay(lastSequence, applied,
                                new Corruption(segment, offset, finalSegment, "unparseable record"));
                    }

                    long seq = record.getSequence();
                    if (firstRecordInSegment && segmentStart >= 0 && seq != segmentStart) {
                        throw new IOException("Journal segment " + segment + " starts with sequence "
                                + seq + " but its name declares " + segmentStart);
                    }
                    if (expectedNext >= 0 && seq != expectedNext) {
                        throw new IOException("Journal sequence gap: expected " + expectedNext
                                + " but found " + seq + " in " + segment
                                + " — a segment is missing or reordered; refusing to replay");
                    }
                    firstRecordInSegment = false;
                    expectedNext = seq + 1;
                    lastSequence = seq;
                    offset += 4L + length + 4;

                    if (seq > afterSequence) {
                        consumer.accept(record);
                        applied++;
                    }
                }
            }
        }
        return new Replay(lastSequence, applied, null);
    }

    /** All journal segments in {@code dir}, in replay (sequence) order. */
    static List<Path> segments(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> JournalWriter.startSequenceOf(p) >= 0)
                    .sorted() // zero-padded names sort into sequence order
                    .toList();
        }
    }
}
