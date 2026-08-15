package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetArchiveConfigTest {

    private static final Path ROOT = Path.of("/data/oms-archive");

    @Test
    void defaultsCaptureTheSixInScopeMessageTypes() {
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(ROOT);
        for (String msgType : new String[]{"D", "G", "F", "8", "9", "Q"}) {
            assertTrue(config.captures(msgType), "expected " + msgType + " to be captured");
        }
        // A status request is a query about an order, not an event in its life.
        assertFalse(config.captures("H"));
        assertFalse(config.captures(null));
        assertFalse(config.captures("A"));
    }

    @Test
    void capturedTypesAreConfigurable() {
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(ROOT).withCapturedMsgTypes("D", "8", "H");
        assertTrue(config.captures("H"));
        assertFalse(config.captures("Q"));
    }

    @Test
    void datasetRootsHangOffTheArchiveRoot() {
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(ROOT);
        assertEquals(ROOT.resolve("fix_messages"), config.datasetRoot(Dataset.RAW_FIX_MESSAGES));
        assertEquals(ROOT.resolve("order_state"), config.datasetRoot(Dataset.ORDER_STATE_CHANGES));
    }

    @Test
    void datasetDirectoriesCanBeRenamed() {
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(ROOT).withDatasetDirectories("raw", "state");
        assertEquals(ROOT.resolve("raw"), config.datasetRoot(Dataset.RAW_FIX_MESSAGES));
        assertEquals("state", config.datasetDirectory(Dataset.ORDER_STATE_CHANGES));
    }

    @Test
    void datasetsMayNotShareADirectory() {
        // Two schemas in one directory would make every read_parquet glob ambiguous.
        assertThrows(IllegalArgumentException.class,
                () -> ParquetArchiveConfig.defaults(ROOT).withDatasetDirectories("same", "same"));
    }

    @Test
    void writerIdIsSanitisedBecauseItBecomesPartOfEveryFileName() {
        assertEquals("host_1-9", ParquetArchiveConfig.defaults(ROOT).withWriterId("host/1-9").writerId());
        assertThrows(IllegalArgumentException.class, () -> ParquetArchiveConfig.defaults(ROOT).withWriterId(" "));
    }

    @Test
    void defaultWriterIdEndsWithThisProcessId() {
        assertTrue(ParquetArchiveConfig.defaultWriterId().endsWith("-" + ProcessHandle.current().pid()));
    }

    @Test
    void withersLeaveEverythingElseAlone() {
        ParquetArchiveConfig base = ParquetArchiveConfig.defaults(ROOT);
        ParquetArchiveConfig tuned = base
                .withPartitionZone(ZoneId.of("America/New_York"))
                .withPartitionScheme(PartitionScheme.hiveDateAccountSymbol())
                .withTimestampSource(TimestampSource.SENDING_TIME)
                .withBatchPolicy(BatchPolicy.lowLatency())
                .withCaptureRawFixText(false);

        assertEquals(ZoneId.of("America/New_York"), tuned.partitionZone());
        assertEquals(TimestampSource.SENDING_TIME, tuned.timestampSource());
        assertEquals(5_000, tuned.batchPolicy().maxRowsPerFile());
        assertFalse(tuned.captureRawFixText());
        // untouched
        assertEquals(base.localRoot(), tuned.localRoot());
        assertEquals(base.writerId(), tuned.writerId());
        assertEquals(Set.copyOf(base.capturedMsgTypes()), Set.copyOf(tuned.capturedMsgTypes()));
        // and the original is unchanged
        assertEquals(ZoneId.of("UTC"), base.partitionZone());
    }

    @Test
    void batchPolicyRejectsSelfContradictoryLimits() {
        // A per-file limit above the global buffer cap could never be reached.
        assertThrows(IllegalArgumentException.class, () -> BatchPolicy.defaults().withMaxBufferedRows(10));
        assertThrows(IllegalArgumentException.class, () -> BatchPolicy.defaults().withWriterThreads(0));
        assertThrows(IllegalArgumentException.class, () -> BatchPolicy.defaults().withMaxRowsPerFile(0));
    }

    @Test
    void duckDbConfigValidatesRowGroupSize() {
        assertThrows(IllegalArgumentException.class, () -> DuckDbConfig.defaults().withRowGroupSize(10));
        assertEquals("zstd", DuckDbConfig.defaults().compression().duckDbName());
    }

    @Test
    void compactionPolicyNeedsAtLeastTwoFilesToBeWorthDoing() {
        assertThrows(IllegalArgumentException.class, () -> CompactionPolicy.defaults().withMinFilesToCompact(1));
    }
}
