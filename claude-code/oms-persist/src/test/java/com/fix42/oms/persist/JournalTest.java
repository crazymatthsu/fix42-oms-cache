package com.fix42.oms.persist;

import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.JournalRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {

    @TempDir
    Path dir;

    private static JournalRecord record(long seq) {
        return JournalRecord.newBuilder()
                .setSequence(seq)
                .setArrivalEpochMillis(1_000 + seq)
                .setMessage(FixSupport.message(Tags.MSG_TYPE, "D", Tags.CL_ORD_ID, "ORD" + seq))
                .build();
    }

    private void write(long startSeq, int count, PersistenceConfig cfg) throws IOException {
        try (JournalWriter w = new JournalWriter(dir, startSeq, cfg)) {
            for (long s = startSeq; s < startSeq + count; s++) {
                w.append(record(s));
            }
        }
    }

    @Test
    void writeThenReplayRoundTrips() throws IOException {
        write(1, 10, PersistenceConfig.defaults());

        List<JournalRecord> got = new ArrayList<>();
        JournalReader.Replay replay = JournalReader.replay(dir, 0, got::add);

        assertTrue(replay.cleanTail());
        assertEquals(10, replay.recordsApplied());
        assertEquals(10, replay.lastSequence());
        for (int i = 0; i < 10; i++) {
            assertEquals(record(i + 1), got.get(i)); // proto equality: full fidelity
        }
    }

    @Test
    void replaySkipsRecordsAtOrBelowAfterSequence() throws IOException {
        write(1, 10, PersistenceConfig.defaults());

        List<JournalRecord> got = new ArrayList<>();
        JournalReader.Replay replay = JournalReader.replay(dir, 7, got::add);

        assertEquals(3, replay.recordsApplied());
        assertEquals(8, got.get(0).getSequence());
        assertEquals(10, replay.lastSequence());
    }

    @Test
    void rotationSplitsIntoMultipleSegmentsAndReplayCrossesThem() throws IOException {
        // Pad each record so a 1 KiB segment fits only one frame -> rotation on every append.
        try (JournalWriter w = new JournalWriter(dir, 1,
                PersistenceConfig.defaults().withMaxSegmentBytes(1024))) {
            for (long s = 1; s <= 8; s++) {
                JournalRecord big = record(s).toBuilder()
                        .setMessage(FixSupport.message(Tags.MSG_TYPE, "D", Tags.TEXT, "x".repeat(700)))
                        .build();
                w.append(big);
            }
        }

        List<Path> segments = JournalReader.segments(dir);
        assertTrue(segments.size() > 1, "expected rotation, got " + segments.size() + " segment(s)");

        List<JournalRecord> got = new ArrayList<>();
        JournalReader.Replay replay = JournalReader.replay(dir, 0, got::add);
        assertTrue(replay.cleanTail());
        assertEquals(8, replay.recordsApplied());
        assertEquals(8, replay.lastSequence());
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, got.get(i).getSequence()); // order preserved across segments
        }
    }

    @Test
    void tornTailStopsCleanlyAndKeepsCompleteRecords() throws IOException {
        write(1, 3, PersistenceConfig.defaults());
        // Simulate a crash mid-append: write a length prefix with no payload.
        Path last = JournalReader.segments(dir).get(JournalReader.segments(dir).size() - 1);
        Files.write(last, new byte[]{0, 0, 0, 42}, StandardOpenOption.APPEND);

        List<JournalRecord> got = new ArrayList<>();
        JournalReader.Replay replay = JournalReader.replay(dir, 0, got::add);

        assertFalse(replay.cleanTail());
        assertEquals(3, replay.recordsApplied()); // all complete records survive
        assertEquals(3, replay.lastSequence());
    }

    @Test
    void corruptCrcStopsReplayAtTheBadRecord() throws IOException {
        write(1, 3, PersistenceConfig.defaults());
        Path segment = JournalReader.segments(dir).get(0);
        byte[] bytes = Files.readAllBytes(segment);
        // Flip one byte in the middle of the file (inside record 2's payload area).
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(segment, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        List<JournalRecord> got = new ArrayList<>();
        JournalReader.Replay replay = JournalReader.replay(dir, 0, got::add);

        assertFalse(replay.cleanTail());
        assertTrue(replay.recordsApplied() < 3, "corruption must stop replay early");
    }

    @Test
    void writerRejectsRecordExceedingSharedBound() throws IOException {
        JournalRecord oversized = JournalRecord.newBuilder()
                .setSequence(1)
                .setMessage(FixSupport.message(Tags.MSG_TYPE, "D",
                        Tags.TEXT, "x".repeat(JournalWriter.MAX_RECORD_BYTES)))
                .build();
        try (JournalWriter w = new JournalWriter(dir, 1, PersistenceConfig.defaults())) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> w.append(oversized));
            // The rejected record must not poison the segment for later appends.
            w.append(record(1));
        }
        JournalReader.Replay replay = JournalReader.replay(dir, 0, r -> { });
        assertTrue(replay.cleanTail());
        assertEquals(1, replay.recordsApplied());
    }

    @Test
    void unknownSegmentMagicIsRefusedNotTreatedAsTornTail() throws IOException {
        Files.write(dir.resolve(JournalWriter.segmentName(1)),
                "FIXJRNL9________".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> JournalReader.replay(dir, 0, r -> { }));
    }

    @Test
    void emptyDirectoryReplaysNothing() throws IOException {
        JournalReader.Replay replay = JournalReader.replay(dir, 0, r -> {
            throw new AssertionError("no records expected");
        });
        assertTrue(replay.cleanTail());
        assertEquals(0, replay.lastSequence());
        assertEquals(0, replay.recordsApplied());
    }
}
