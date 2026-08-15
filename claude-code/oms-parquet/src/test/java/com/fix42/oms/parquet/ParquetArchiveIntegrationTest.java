package com.fix42.oms.parquet;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.InMemoryOrderCache;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: a FIX 4.2 stream goes through {@link OmsCache} into a real Parquet dataset on
 * disk, which is then read back with DuckDB — the same way an intraday reader would.
 */
class ParquetArchiveIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private ParquetArchiveConfig config(Path root) {
        return ParquetArchiveConfig.defaults(root).withClock(FIXED).withWriterId("test-writer");
    }

    /** Feed the whole sample stream through a cache wired to {@code archive}. */
    private OmsCache feed(ParquetArchive archive) {
        InMemoryOrderCache inner = new InMemoryOrderCache(
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(FIXED),
                archive.orderStateListener());
        OmsCache cache = new OmsCache(archive.wrap(inner));
        for (String message : SampleFix42.stream()) {
            cache.process(message);
        }
        return cache;
    }

    private static List<Path> parquetFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(ParquetPaths::isParquetFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> allFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void rawMessagesLandInDateAccountSymbolPartitions(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();
        }

        assertTrue(Files.isDirectory(root.resolve("fix_messages/2026/08/04/ACC1/IBM")),
                "expected YYYY/MM/DD/account/symbol partitions");
        assertTrue(Files.isDirectory(root.resolve("fix_messages/2026/08/04/ACC2/MSFT")));
        assertTrue(Files.isDirectory(root.resolve("order_state/2026/08/04/ACC1/IBM")));
        assertFalse(parquetFiles(root).isEmpty());
    }

    @Test
    void everyInScopeMessageIsCapturedAndNothingElseIs(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                assertEquals(SampleFix42.capturedStream().size(), reader.count(Dataset.RAW_FIX_MESSAGES));

                List<Map<String, Object>> byType = reader.query(
                        "SELECT msg_type, count(*) AS n FROM " + reader.scanSql(Dataset.RAW_FIX_MESSAGES)
                                + " GROUP BY msg_type ORDER BY msg_type");
                List<String> types = byType.stream().map(r -> (String) r.get("msg_type")).toList();
                assertEquals(List.of("8", "9", "D", "F", "G", "Q"), types,
                        "35=H is a query about an order, not an event, so it is not archived");
            }
        }
    }

    @Test
    void theOriginalWireTextIsRecoverableFromTheArchive(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                List<Map<String, Object>> rows = reader.query(
                        "SELECT raw_fix FROM " + reader.scanSql(Dataset.RAW_FIX_MESSAGES)
                                + " WHERE exec_id = 'E1'");
                assertEquals(1, rows.size());
                assertEquals(SampleFix42.CHILD1_ACK, rows.get(0).get("raw_fix"));
            }
        }
    }

    @Test
    void liftedColumnsAreQueryableWithoutReparsingFix(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                List<Map<String, Object>> fills = reader.query(
                        "SELECT cl_ord_id, last_qty, last_px, cum_qty FROM "
                                + reader.scanSql(Dataset.RAW_FIX_MESSAGES)
                                + " WHERE msg_type = '8' AND last_qty IS NOT NULL ORDER BY ingest_seq");
                assertEquals(2, fills.size(), "two fills on CHILD1");
                assertEquals(250.0d, (Double) fills.get(0).get("last_qty"));
                assertEquals(185.48d, (Double) fills.get(0).get("last_px"));
                assertEquals(350.0d, (Double) fills.get(1).get("last_qty"));
                assertEquals(600.0d, (Double) fills.get(1).get("cum_qty"));
            }
        }
    }

    @Test
    void latestStateFromParquetMatchesTheCache(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            OmsCache cache = feed(archive);
            archive.flush();

            OrderState child1 = cache.getByOrderId("EX-C1").orElseThrow();
            assertEquals(OrdStatus.ORD_STATUS_FILLED, child1.getOrdStatus());

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                // The documented way to get latest state out of the append-only history.
                List<Map<String, Object>> latest = reader.query(
                        "SELECT chain_key, ord_status, cum_qty, leaves_qty, is_terminal FROM ("
                                + "  SELECT *, row_number() OVER ("
                                + "      PARTITION BY chain_key ORDER BY ts DESC, update_count DESC) AS rn"
                                + "  FROM " + reader.scanSql(Dataset.ORDER_STATE_CHANGES)
                                + ") WHERE rn = 1 AND chain_key = 'CHILD1'");
                assertEquals(1, latest.size(), "one row per chain, not one per identifier it ever had");
                assertEquals("FILLED", latest.get(0).get("ord_status"));
                assertEquals(child1.getCumQty(), (Double) latest.get(0).get("cum_qty"));
                assertEquals(child1.getLeavesQty(), (Double) latest.get(0).get("leaves_qty"));
                assertEquals(Boolean.TRUE, latest.get(0).get("is_terminal"));
            }
        }
    }

    @Test
    void parentRollUpsAreArchivedAlongsideTheirChildren(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                long rollUps = (Long) reader.query("SELECT count(*) AS n FROM "
                        + reader.scanSql(Dataset.ORDER_STATE_CHANGES)
                        + " WHERE change_kind = 'PARENT_ROLLUP'").get(0).get("n");
                assertTrue(rollUps > 0, "a child's fills must roll up into the parent");

                List<Map<String, Object>> parent = reader.query(
                        "SELECT max(cum_qty) AS filled, max(child_order_count) AS children FROM "
                                + reader.scanSql(Dataset.ORDER_STATE_CHANGES) + " WHERE cl_ord_id = 'PARENT1'");
                assertEquals(600.0d, (Double) parent.get(0).get("filled"));
                assertEquals(2, ((Number) parent.get(0).get("children")).intValue());
            }
        }
    }

    @Test
    void readersNeverSeeAPartiallyWrittenFile(@TempDir Path root) {
        ParquetArchiveConfig config = config(root)
                .withBatchPolicy(BatchPolicy.defaults().withMaxRowsPerFile(1));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();
        }
        // Every file that exists is a complete .parquet; no .tmp is left behind.
        assertEquals(parquetFiles(root), allFiles(root));
        assertTrue(parquetFiles(root).size() > 5, "one row per file should produce many files");
    }

    @Test
    void statsAccountForEveryRow(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            ArchiveStats buffered = archive.stats();
            assertTrue(buffered.rowsBuffered() > 0, "rows are batched in memory before flushing");

            archive.flush();
            ArchiveStats flushed = archive.stats();
            assertTrue(flushed.isFullyFlushed(), () -> "expected everything written, got " + flushed);
            assertEquals(0, flushed.rowsDropped());
            assertEquals(0, flushed.flushFailures());
            assertTrue(flushed.bytesWritten() > 0);
            assertEquals(SampleFix42.capturedStream().size() + countStateChanges(), flushed.rowsWritten());
        }
    }

    private int countStateChanges() {
        List<Object> changes = new ArrayList<>();
        InMemoryOrderCache cache = new InMemoryOrderCache(
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(FIXED),
                changes::add);
        for (String message : SampleFix42.stream()) {
            cache.process(com.fix42.oms.fix.FixParser.pipe().parse(message));
        }
        return changes.size();
    }

    @Test
    void hivePartitioningIsAConfigurationChangeNotACodeChange(@TempDir Path root) {
        ParquetArchiveConfig config = config(root)
                .withPartitionScheme(PartitionScheme.hiveDateAccountSymbol());
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();
        }
        assertTrue(Files.isDirectory(root.resolve("fix_messages/date=2026-08-04/account=ACC1/symbol=IBM")));

        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            assertEquals(SampleFix42.capturedStream().size(), reader.count(Dataset.RAW_FIX_MESSAGES));
        }
    }

    @Test
    void sendingTimeSourceFilesMessagesUnderTheirOwnTradingDay(@TempDir Path root) {
        // The clock says 4 August; every sample message says 14 August in tag 52. Replaying a
        // historical file must file it under the day it happened.
        ParquetArchiveConfig config = config(root).withTimestampSource(TimestampSource.SENDING_TIME);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            feed(archive);
            archive.flush();
        }
        assertTrue(Files.isDirectory(root.resolve("fix_messages/2026/08/14/ACC1/IBM")));
        assertFalse(Files.isDirectory(root.resolve("fix_messages/2026/08/04/ACC1/IBM")));
    }

    @Test
    void tradingZoneDecidesTheDayBoundary(@TempDir Path root) {
        // 00:30 UTC on the 5th is still the 4th in New York.
        Clock lateNight = Clock.fixed(Instant.parse("2026-08-05T00:30:00Z"), ZoneOffset.UTC);
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(root)
                .withClock(lateNight)
                .withPartitionZone(ZoneId.of("America/New_York"));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            archive.writeRawMessage(SampleFix42.CHILD1_ACK);
            archive.flush();
        }
        assertTrue(Files.isDirectory(root.resolve("fix_messages/2026/08/04/ACC1/IBM")));
    }

    @Test
    void messagesWithoutAccountOrSymbolStillGetAPartition(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            // A cancel request that carries neither tag 1 nor tag 55.
            assertTrue(archive.writeRawMessage("35=F|11=C1|41=O1|37=EX1|"));
            archive.flush();
        }
        assertTrue(Files.isDirectory(root.resolve("fix_messages/2026/08/04/_unknown/_unknown")));

        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            List<Map<String, Object>> rows = reader.readAll(Dataset.RAW_FIX_MESSAGES);
            assertEquals(1, rows.size());
            // The partition label is a path artefact; the column itself stays honestly NULL.
            assertEquals(null, rows.get(0).get("account"));
            assertEquals("C1", rows.get(0).get("cl_ord_id"));
        }
    }

    @Test
    void outOfScopeMessageTypesAreReportedAsNotCaptured(@TempDir Path root) {
        try (ParquetArchive archive = ParquetArchive.open(config(root))) {
            assertFalse(archive.writeRawMessage(SampleFix42.ORDER_STATUS_REQUEST));
            assertTrue(archive.writeRawMessage(SampleFix42.CHILD1_ACK));
        }
    }

    @Test
    void aFailedFlushIsCountedAndReportedRatherThanThrownAtIngest(@TempDir Path root) throws IOException {
        // Put a regular file where the partition tree needs a directory: the flush cannot
        // create '<root>/fix_messages/2026/...'.
        Files.createDirectories(root.resolve("fix_messages"));
        Files.writeString(root.resolve("fix_messages/2026"), "not a directory");

        List<String> failures = new ArrayList<>();
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config,
                (operation, target, error) -> failures.add(operation))) {
            archive.writeRawMessage(SampleFix42.CHILD1_ACK); // does not throw
            archive.flush();

            assertEquals(List.of("flush"), failures);
            ArchiveStats stats = archive.stats();
            assertEquals(1, stats.flushFailures());
            assertEquals(1, stats.rowsDropped());
            assertEquals(0, stats.rowsWritten());
            assertFalse(stats.isFullyFlushed());
        }
    }

    @Test
    void captureHappensEvenWhenTheCacheRejectsTheMessage(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            ArchivingOrderCache archiving = archive.wrap(new RejectingOrderCache());

            assertThrows(IllegalStateException.class,
                    () -> archiving.process(SampleFix42.parse(SampleFix42.CHILD1_ACK)));
            archive.flush();

            try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
                assertEquals(1, reader.count(Dataset.RAW_FIX_MESSAGES),
                        "the audit trail records what arrived, not only what was accepted");
            }
        }
    }

    @Test
    void archivingCacheDelegatesQueriesAndDoesNotSwallowFoldFailures(@TempDir Path root) {
        try (ParquetArchive archive = ParquetArchive.open(config(root))) {
            InMemoryOrderCache inner = new InMemoryOrderCache(
                    DefaultParentLinkResolver.create(), CacheConfig.defaults().withClock(FIXED));
            ArchivingOrderCache archiving = archive.wrap(inner);

            archiving.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
            assertEquals(inner, archiving.delegate());
            assertEquals(1, archiving.size());
            assertTrue(archiving.getByClOrdId("CHILD1").isPresent());
            assertEquals(1, archiving.findBySymbol("IBM").size());
            assertEquals(1, archiving.findByAccount("ACC1").size());
        }
    }

    /** An {@link com.fix42.oms.cache.OrderCache} whose fold always fails. */
    private static final class RejectingOrderCache implements com.fix42.oms.cache.OrderCache {

        @Override
        public OrderState process(com.fix42.oms.proto.FixMessage message) {
            throw new IllegalStateException("state machine rejected it");
        }

        @Override
        public java.util.Optional<OrderState> getByOrderId(String orderId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<OrderState> getByClOrdId(String clOrdId) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<OrderState> getByExecId(String execId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<OrderState> findByAccount(String account) {
            return List.of();
        }

        @Override
        public List<OrderState> findBySymbol(String symbol) {
            return List.of();
        }

        @Override
        public List<OrderState> getChildren(String parentId) {
            return List.of();
        }

        @Override
        public java.util.Optional<OrderState> getParent(String childId) {
            return java.util.Optional.empty();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public java.util.Collection<OrderState> snapshotAll() {
            return List.of();
        }
    }
}
