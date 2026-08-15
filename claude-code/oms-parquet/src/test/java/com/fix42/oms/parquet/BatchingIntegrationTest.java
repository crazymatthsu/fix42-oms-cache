package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batching pipeline: what makes a batch become a file, and that nothing is lost on the
 * way there.
 */
class BatchingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:30:00Z");

    private ParquetArchiveConfig config(Path root) {
        return ParquetArchiveConfig.defaults(root)
                .withClock(Clock.fixed(NOW, ZoneOffset.UTC))
                .withWriterId("test-writer");
    }

    /** Poll until {@code condition} holds; the writer thread works asynchronously. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /** A distinct message per index, all in the same (account, symbol) partition. */
    private static String message(int i) {
        return message(i, "ACC1", "IBM");
    }

    private static String message(int i, String account, String symbol) {
        return "35=8|11=ORD" + i + "|37=EX" + i + "|17=E" + i + "|20=0|150=0|39=0|"
                + "1=" + account + "|55=" + symbol + "|54=1|38=100|151=100|14=0|";
    }

    @Test
    void aFullBatchIsWrittenWithoutWaitingForAnExplicitFlush(@TempDir Path root) {
        ParquetArchiveConfig config = config(root)
                .withBatchPolicy(BatchPolicy.defaults().withMaxRowsPerFile(2));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            for (int i = 0; i < 4; i++) {
                archive.writeRawMessage(message(i));
            }
            await("two full batches to be written", () -> archive.stats().filesWritten() == 2);
            assertEquals(4, archive.stats().rowsWritten());
        }
    }

    @Test
    void anAgingBatchIsFlushedEvenWhileIngestIsIdle(@TempDir Path root) {
        // A real clock, so the batch can actually age; a partial batch must not sit in memory
        // indefinitely or an intraday reader would never see the last few messages of a quiet
        // symbol.
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(root)
                .withBatchPolicy(BatchPolicy.defaults()
                        .withMaxRowsPerFile(10_000)
                        .withMaxBatchAgeMillis(50)
                        .withFlushCheckIntervalMillis(10));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            archive.writeRawMessage(message(1));
            await("the aged batch to be flushed", () -> archive.stats().filesWritten() == 1);
            assertEquals(1, archive.stats().rowsWritten());
        }
    }

    @Test
    void theMemoryGuardFlushesBeforeBufferedRowsRunAway(@TempDir Path root) {
        // Rows are split across two partitions, 60 each, so no single batch ever reaches the
        // 100-row file threshold. Only the global cap can produce a file here — which is the
        // point: many small partitions can hold far more in aggregate than any one of them.
        ParquetArchiveConfig config = config(root)
                .withBatchPolicy(BatchPolicy.defaults()
                        .withMaxRowsPerFile(100)
                        .withMaxBufferedRows(100));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            for (int i = 0; i < 60; i++) {
                archive.writeRawMessage(message(i, "ACC1", "IBM"));
                archive.writeRawMessage(message(i, "ACC2", "MSFT"));
            }
            await("the memory guard to force a flush", () -> archive.stats().filesWritten() >= 1);

            archive.flush();
            assertEquals(120, archive.stats().rowsWritten());
            assertEquals(0, archive.stats().rowsDropped());
        }
    }

    @Test
    void closingFlushesWhatIsStillBuffered(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            for (int i = 0; i < 5; i++) {
                archive.writeRawMessage(message(i));
            }
            assertEquals(0, archive.stats().filesWritten(), "nothing is due yet");
        } // close() flushes

        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            assertEquals(5, reader.count(Dataset.RAW_FIX_MESSAGES));
        }
    }

    @Test
    void closingIsIdempotentAndFurtherWritesAreRefused(@TempDir Path root) {
        ParquetArchive archive = ParquetArchive.open(config(root));
        archive.writeRawMessage(message(1));
        archive.close();
        archive.close();

        // Refused loudly rather than silently dropped — a listener failure is routed to the
        // cache's error channel, and a direct caller learns immediately.
        assertThrows(ArchiveException.class, () -> archive.writeRawMessage(message(2)));
    }

    @Test
    void concurrentIngestLosesNothing(@TempDir Path root) throws Exception {
        ParquetArchiveConfig config = config(root)
                .withBatchPolicy(BatchPolicy.defaults().withMaxRowsPerFile(50).withMaxQueuedFlushes(4));
        int threads = 4;
        int perThread = 250;

        try (ParquetArchive archive = ParquetArchive.open(config)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t * perThread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        archive.writeRawMessage(message(offset + i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            archive.flush();

            ArchiveStats stats = archive.stats();
            assertEquals((long) threads * perThread, stats.rowsWritten());
            assertEquals(0, stats.rowsDropped());
            assertTrue(stats.isFullyFlushed());
        }

        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            assertEquals((long) threads * perThread, reader.count(Dataset.RAW_FIX_MESSAGES));
            assertEquals((long) threads * perThread,
                    ((Number) reader.query("SELECT count(DISTINCT ingest_seq) AS n FROM "
                            + reader.scanSql(Dataset.RAW_FIX_MESSAGES)).get(0).get("n")).longValue(),
                    "ingest_seq must be unique per writer");
        }
    }

    @Test
    void flushIsIdempotentAndCheapWhenThereIsNothingToDo(@TempDir Path root) {
        try (ParquetArchive archive = ParquetArchive.open(config(root))) {
            archive.flush();
            archive.writeRawMessage(message(1));
            archive.flush();
            archive.flush();
            assertEquals(1, archive.stats().filesWritten());
            assertEquals(1, archive.stats().rowsWritten());
        }
    }
}
