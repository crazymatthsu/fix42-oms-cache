package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetPathsTest {

    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 8, 4, 9, 30, 15, 250_000_000);

    @Test
    void safeCharactersSurviveSanitising() {
        assertEquals("BRK.B", ParquetPaths.sanitizeSegment("BRK.B"));
        assertEquals("ACC-1_2", ParquetPaths.sanitizeSegment("ACC-1_2"));
        assertEquals("IBM", ParquetPaths.sanitizeSegment("  IBM  "));
    }

    @Test
    void separatorsAndTraversalCannotEscapeThePartition() {
        assertEquals("a_b", ParquetPaths.sanitizeSegment("a/b"));
        assertEquals("a_b", ParquetPaths.sanitizeSegment("a\\b"));
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment(".."));
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment("."));
        // A leading dot would hide the directory from a default glob.
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment(".hidden"));
    }

    @Test
    void absentValuesBecomeTheUnknownLabel() {
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment(null));
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment(""));
        assertEquals(ParquetPaths.UNKNOWN, ParquetPaths.sanitizeSegment("   "));
    }

    @Test
    void longSegmentsAreTruncatedNotRejected() {
        String segment = ParquetPaths.sanitizeSegment("X".repeat(500));
        assertEquals(ParquetPaths.MAX_SEGMENT_LENGTH, segment.length());
    }

    @Test
    void batchFileNamesCarryStampWriterAndSequence() {
        String name = ParquetPaths.batchFileName("fix_messages", "host-42", STAMP, 7);
        assertEquals("fix_messages-20260804T093015250-host-42-7.parquet", name);
        assertTrue(ParquetPaths.isParquetFile(Path.of(name)));
    }

    @Test
    void twoFlushesInTheSameMillisecondGetDifferentNames() {
        assertNotEquals(ParquetPaths.batchFileName("fix_messages", "w", STAMP, 1),
                ParquetPaths.batchFileName("fix_messages", "w", STAMP, 2));
    }

    @Test
    void compactedFileNamesAreDistinguishable() {
        assertEquals("order_state-compact-20260804T093015250-0.parquet",
                ParquetPaths.compactedFileName("order_state", STAMP, 0));
    }

    @Test
    void tempSiblingIsHiddenAndNotSeenAsAParquetFile() {
        Path target = Path.of("/data/oms/fix_messages/2026/08/04/ACC1/IBM/f.parquet");
        Path temp = ParquetPaths.tempSibling(target);
        assertEquals(target.getParent(), temp.getParent());
        assertEquals(".f.parquet.tmp", temp.getFileName().toString());
        assertFalse(ParquetPaths.isParquetFile(temp));
    }
}
