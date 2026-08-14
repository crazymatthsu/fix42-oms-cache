package com.fix42.oms.persist;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentOrderCacheTest {

    @TempDir
    Path dir;

    /** Deterministic wall clock: advances 1 ms per read, so every arrival time is distinct. */
    private static final class TickingClock extends Clock {
        private long t;

        TickingClock(long start) {
            this.t = start;
        }

        @Override
        public long millis() {
            return ++t;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final List<String> LIFECYCLE = List.of(
            "35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.50|59=0|",
            "35=8|11=ORD1|37=EX1|17=E1|20=0|150=A|39=A|151=1000|14=0|",
            "35=8|11=ORD1|37=EX1|17=E2|20=0|150=0|39=0|151=1000|14=0|",
            "35=8|11=ORD1|37=EX1|17=E3|20=0|150=1|39=1|32=400|31=185.50|30=XNYS|151=600|14=400|6=185.50|",
            "35=G|11=R1|41=ORD1|37=EX1|1=ACC|55=IBM|54=1|38=1200|40=2|44=185.50|",
            "35=8|11=R1|41=ORD1|37=EX1|17=E4|20=0|150=5|39=1|38=1200|151=800|14=400|6=185.50|",
            "35=8|11=R1|37=EX1|17=E5|20=0|150=2|39=2|32=800|31=185.55|151=0|14=1200|6=185.5333|");

    private static final List<String> BASKET = List.of(
            "35=D|11=CH1|526=PAR|1=DESK|55=AAPL|54=1|38=300|40=1|",
            "35=8|11=CH1|526=PAR|37=CHILD1|17=C1|20=0|150=2|39=2|32=300|31=190.10|151=0|14=300|6=190.10|",
            "35=D|11=PAR|1=DESK|55=AAPL|54=1|38=500|40=1|",
            "35=8|11=PAR|37=PAR|17=P1|20=0|150=0|39=0|151=500|14=0|",
            "35=D|11=CH2|526=PAR|1=DESK|55=AAPL|54=1|38=200|40=1|",
            "35=8|11=CH2|526=PAR|37=CHILD2|17=C2|20=0|150=2|39=2|32=200|31=190.20|151=0|14=200|6=190.20|");

    private static void feed(PersistentOrderCache cache, List<String> messages) {
        for (String m : messages) {
            cache.process(FixParser.pipe().parse(m));
        }
    }

    /** Keyed snapshot of every chain's state for exact comparison. */
    private static Map<String, OrderState> statesByKey(PersistentOrderCache cache) {
        Map<String, OrderState> out = new HashMap<>();
        for (OrderState s : cache.snapshotAll()) {
            out.put(s.getOrderId() + "/" + s.getClOrdId(), s);
        }
        return out;
    }

    private PersistentOrderCache open(Clock wallClock) throws IOException {
        return PersistentOrderCache.open(dir, DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(wallClock), PersistenceConfig.defaults());
    }

    @Test
    void journalOnlyRecoveryReproducesExactState() throws IOException {
        Map<String, OrderState> before;
        try (PersistentOrderCache cache = open(new TickingClock(1_000_000))) {
            feed(cache, LIFECYCLE);
            before = statesByKey(cache);
        } // "crash": no snapshot was ever taken

        try (PersistentOrderCache recovered = open(new TickingClock(9_999_999))) {
            assertEquals(7, recovered.recoveredSequence());
            // exact (OrderState.equals): statuses, quantities, exec ids, history, timestamps
            assertEquals(before, statesByKey(recovered));
            OrderState s = recovered.getByOrderId("EX1").orElseThrow();
            assertEquals(OrdStatus.ORD_STATUS_FILLED, s.getOrdStatus());
            assertEquals(1200.0, s.getCumQty());
            assertEquals(7, s.getMessageHistoryCount());
        }
    }

    @Test
    void snapshotPlusTailRecoveryReproducesExactState() throws IOException {
        Map<String, OrderState> before;
        try (PersistentOrderCache cache = open(new TickingClock(2_000_000))) {
            feed(cache, LIFECYCLE.subList(0, 4));
            cache.snapshot();                       // checkpoint mid-stream
            feed(cache, LIFECYCLE.subList(4, 7));   // tail lives only in the journal
            before = statesByKey(cache);
        }

        try (PersistentOrderCache recovered = open(new TickingClock(1))) {
            assertEquals(7, recovered.recoveredSequence());
            assertEquals(before, statesByKey(recovered));
            // The chain is fully queryable through every alias after restore.
            assertTrue(recovered.getByClOrdId("ORD1").isPresent());
            assertTrue(recovered.getByClOrdId("R1").isPresent());
            assertTrue(recovered.getByExecId("E5").isPresent());
            assertEquals(1, recovered.findByAccount("ACC").size());
        }
    }

    @Test
    void parentChildRelationshipsSurviveRestart() throws IOException {
        try (PersistentOrderCache cache = open(new TickingClock(3_000_000))) {
            feed(cache, BASKET);
            cache.snapshot();
        }

        try (PersistentOrderCache recovered = open(new TickingClock(1))) {
            OrderState parent = recovered.getByOrderId("PAR").orElseThrow();
            assertTrue(parent.getIsParent());
            assertEquals(500.0, parent.getCumQty());
            assertEquals(190.14, parent.getAvgPx(), 1e-9);
            assertEquals(2, recovered.getChildren("PAR").size());
            assertEquals("PAR", recovered.getParent("CHILD1").orElseThrow().getOrderId());
        }
    }

    @Test
    void sequenceContinuesAcrossRestartsWithoutDuplicateApplies() throws IOException {
        try (PersistentOrderCache c1 = open(new TickingClock(4_000_000))) {
            feed(c1, LIFECYCLE.subList(0, 2));
        }
        try (PersistentOrderCache c2 = open(new TickingClock(4_100_000))) {
            assertEquals(2, c2.recoveredSequence());
            feed(c2, LIFECYCLE.subList(2, 4)); // sequences 3,4 in a new segment
        }
        try (PersistentOrderCache c3 = open(new TickingClock(4_200_000))) {
            assertEquals(4, c3.recoveredSequence());
            OrderState s = c3.getByOrderId("EX1").orElseThrow();
            // Each message applied exactly once across three generations.
            assertEquals(4, s.getMessageHistoryCount());
            assertEquals(4, s.getUpdateCount());
            assertEquals(3, s.getExecIdsCount()); // E1,E2,E3
            assertEquals(400.0, s.getCumQty());
        }
    }

    @Test
    void corruptNewestSnapshotStillRecoversFully() throws IOException {
        try (PersistentOrderCache cache = open(new TickingClock(5_000_000))) {
            feed(cache, LIFECYCLE.subList(0, 3));
            cache.snapshot();                      // snapshot @3
            feed(cache, LIFECYCLE.subList(3, 6));
            cache.snapshot();                      // snapshot @6 (newest)
            feed(cache, LIFECYCLE.subList(6, 7));  // seq 7 in journal only
        }

        // Corrupt the newest snapshot; recovery must fall back to @3 and replay 4..7,
        // which is only possible because compaction preserved records past the OLDEST
        // retained snapshot.
        Path newest = Files.list(dir)
                .filter(p -> p.getFileName().toString().startsWith(SnapshotStore.PREFIX)
                        && p.getFileName().toString().endsWith(SnapshotStore.SUFFIX))
                .sorted(java.util.Comparator.reverseOrder())
                .findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(newest);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(newest, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (PersistentOrderCache recovered = open(new TickingClock(1))) {
            assertEquals(7, recovered.recoveredSequence());
            OrderState s = recovered.getByOrderId("EX1").orElseThrow();
            assertEquals(OrdStatus.ORD_STATUS_FILLED, s.getOrdStatus());
            assertEquals(1200.0, s.getCumQty());
            assertEquals(7, s.getMessageHistoryCount());
        }
    }

    @Test
    void directoryLockPreventsConcurrentSecondOpen() throws IOException {
        try (PersistentOrderCache first = open(Clock.systemUTC())) {
            assertThrows(IllegalStateException.class, () -> open(Clock.systemUTC()));
        }
        // After close the directory is reusable.
        try (PersistentOrderCache again = open(Clock.systemUTC())) {
            assertEquals(0, again.recoveredSequence());
        }
    }

    @Test
    void omsCacheFacadeWorksOverPersistence() throws IOException {
        try (PersistentOrderCache cache = open(new TickingClock(6_000_000))) {
            OmsCache oms = cache.asOmsCache();
            oms.process("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|");
            oms.process("35=8|11=ORD1|37=EX1|17=E1|20=0|150=2|39=2|32=100|31=50|151=0|14=100|6=50|");
        }
        try (PersistentOrderCache recovered = open(new TickingClock(1))) {
            assertEquals(OrdStatus.ORD_STATUS_FILLED,
                    recovered.asOmsCache().getByClOrdId("ORD1").orElseThrow().getOrdStatus());
        }
    }

    @Test
    void doubleCrashWithTornTailRecoversAllAckedRecords() throws IOException {
        // Crash #1 leaves a torn tail in segment A.
        try (PersistentOrderCache cache = open(new TickingClock(8_000_000))) {
            feed(cache, LIFECYCLE.subList(0, 4)); // seq 1..4
        }
        Path segmentA = JournalReader.segments(dir).get(0);
        Files.write(segmentA, new byte[]{0, 0, 0, 100, 1, 2, 3}, StandardOpenOption.APPEND);

        // Recovery #1 must HEAL the tear (truncate), then ack seq 5 into a new segment B.
        try (PersistentOrderCache healed = open(new TickingClock(8_100_000))) {
            assertEquals(4, healed.recoveredSequence());
            feed(healed, LIFECYCLE.subList(4, 5)); // seq 5, acked
        }

        // Recovery #2 (the double-crash scenario): the tear in A is gone, so B's acked
        // record MUST be applied — before healing existed, replay stopped at A's tear
        // and silently dropped everything in B.
        try (PersistentOrderCache recovered = open(new TickingClock(8_200_000))) {
            assertEquals(5, recovered.recoveredSequence());
            OrderState s = recovered.getByOrderId("EX1").orElseThrow();
            assertEquals(5, s.getMessageHistoryCount());
            assertEquals(OrdStatus.ORD_STATUS_PENDING_REPLACE, s.getOrdStatus()); // seq 5 = the G
        }
    }

    @Test
    void midJournalCorruptionRefusesToOpen() throws IOException {
        // Small segments + padded messages -> several segments.
        PersistenceConfig tiny = PersistenceConfig.defaults().withMaxSegmentBytes(1024);
        try (PersistentOrderCache cache = PersistentOrderCache.open(dir,
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(new TickingClock(8_300_000)), tiny)) {
            for (int i = 1; i <= 4; i++) {
                cache.process(FixParser.pipe().parse(
                        "35=D|11=P" + i + "|1=A|55=IBM|54=1|38=100|40=1|58=" + "x".repeat(700) + "|"));
            }
        }
        List<Path> segments = JournalReader.segments(dir);
        assertTrue(segments.size() >= 3, "expected several segments, got " + segments.size());

        // Corrupt a NON-final segment: acked records live beyond it, so recovery must
        // refuse rather than silently drop them.
        Path nonFinal = segments.get(0);
        byte[] bytes = Files.readAllBytes(nonFinal);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(nonFinal, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IOException.class, () -> open(new TickingClock(1)));
    }

    @Test
    void sequenceGapFromMissingSegmentRefusesToOpen() throws IOException {
        PersistenceConfig tiny = PersistenceConfig.defaults().withMaxSegmentBytes(1024);
        try (PersistentOrderCache cache = PersistentOrderCache.open(dir,
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(new TickingClock(8_400_000)), tiny)) {
            for (int i = 1; i <= 4; i++) {
                cache.process(FixParser.pipe().parse(
                        "35=D|11=Q" + i + "|1=A|55=IBM|54=1|38=100|40=1|58=" + "x".repeat(700) + "|"));
            }
        }
        List<Path> segments = JournalReader.segments(dir);
        assertTrue(segments.size() >= 3);
        Files.delete(segments.get(1)); // silent middle gap must not replay as if complete

        assertThrows(IOException.class, () -> open(new TickingClock(1)));
    }

    @Test
    void configFingerprintMismatchFailsLoudly() throws IOException {
        try (PersistentOrderCache cache = open(new TickingClock(8_500_000))) {
            feed(cache, LIFECYCLE.subList(0, 2));
            cache.snapshot(); // snapshot carries historyCap=0 fingerprint
        }
        // Reopening with a different historyCap would silently diverge the fold.
        assertThrows(IllegalArgumentException.class, () ->
                PersistentOrderCache.open(dir, DefaultParentLinkResolver.create(),
                        CacheConfig.defaults().withHistoryCap(5).withClock(new TickingClock(1)),
                        PersistenceConfig.defaults()));
    }

    @Test
    void recoveryThroughputSmoke() throws IOException {
        int orders = 2_000; // 3 messages each = 6,000 journal records
        PersistenceConfig fast = PersistenceConfig.defaults()
                .withFsyncPolicy(PersistenceConfig.FsyncPolicy.OS_BUFFERED);
        try (PersistentOrderCache cache = PersistentOrderCache.open(dir,
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(new TickingClock(7_000_000)), fast)) {
            for (int i = 0; i < orders; i++) {
                String cl = "O" + i;
                String ex = "X" + i;
                cache.process(FixParser.pipe().parse("35=D|11=" + cl + "|1=A|55=IBM|54=1|38=100|40=1|"));
                cache.process(FixParser.pipe().parse("35=8|11=" + cl + "|37=" + ex + "|17=" + ex + "n|20=0|150=0|39=0|151=100|14=0|"));
                cache.process(FixParser.pipe().parse("35=8|11=" + cl + "|37=" + ex + "|17=" + ex + "f|20=0|150=2|39=2|32=100|31=50|151=0|14=100|6=50|"));
            }
        }

        long t0 = System.nanoTime();
        try (PersistentOrderCache recovered = PersistentOrderCache.open(dir,
                DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(new TickingClock(1)), fast)) {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(orders, recovered.size());
            assertEquals(3L * orders, recovered.recoveredSequence());
            System.out.printf("recovery: %d records in %d ms (%.0f rec/s)%n",
                    3 * orders, elapsedMs, 3.0 * orders / Math.max(1, elapsedMs) * 1000);
        }
    }
}
