package com.fix42.oms.parquet;

import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.InMemoryOrderCache;
import com.fix42.oms.cache.OrderStateChange;
import com.fix42.oms.model.DefaultParentLinkResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStateRowMapperTest {

    private static final Dataset DATASET = Dataset.ORDER_STATE_CHANGES;
    private static final Instant NOW = Instant.parse("2026-08-04T13:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);

    private final List<OrderStateChange> changes = new ArrayList<>();
    private final OrderStateRowMapper mapper =
            new OrderStateRowMapper(ParquetArchiveConfig.defaults(Path.of("/tmp/unused")).withWriterId("test-writer"));

    private InMemoryOrderCache cacheRecordingChanges() {
        return new InMemoryOrderCache(DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(Clock.fixed(NOW, ZoneOffset.UTC)),
                changes::add);
    }

    private static Object value(Object[] row, String column) {
        return row[DATASET.indexOf(column)];
    }

    private Object[] rowFor(OrderStateChange change) {
        Object[] row = mapper.toRow(change, DATE, 1L);
        DATASET.checkRow(row);
        return row;
    }

    @Test
    void firstMessageOfAChainIsACreate() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));

        Object[] row = rowFor(changes.get(0));
        assertEquals("CREATE", value(row, "change_kind"));
        assertEquals(DATE, value(row, "event_date"));
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), value(row, "ts"));
        assertEquals("test-writer", value(row, "writer_id"));
        assertEquals("CHILD1", value(row, "cl_ord_id"));
        assertEquals("ACC1", value(row, "account"));
        assertEquals("IBM", value(row, "symbol"));
        assertEquals("D", value(row, "cause_msg_type"));
        assertEquals("CHILD1", value(row, "cause_cl_ord_id"));
        assertNull(value(row, "prev_ord_status"), "nothing preceded the first message");
    }

    @Test
    void enumsAreDecodedForTheDerivedDataset() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_ACK));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL));

        Object[] row = rowFor(changes.get(changes.size() - 1));
        assertEquals("BUY", value(row, "side"));
        assertEquals("LIMIT", value(row, "ord_type"));
        assertEquals("DAY", value(row, "time_in_force"));
        assertEquals("PARTIALLY_FILLED", value(row, "ord_status"));
        assertEquals("NEW", value(row, "prev_ord_status"));
        assertEquals("PARTIAL_FILL", value(row, "last_exec_type"));
        assertEquals("UPDATE", value(row, "change_kind"));
    }

    @Test
    void chainKeyIsStableForTheWholeLifeOfAChain() {
        // The grouping key must not change when the venue assigns an OrderID, nor when a
        // replace changes the ClOrdID — otherwise last_by() reports one order twice and the
        // stale half never leaves its old status.
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_NOS));
        assertEquals("CHILD2", value(rowFor(changes.get(0)), "chain_key"));

        cache.process(SampleFix42.parse(SampleFix42.CHILD2_ACK));           // OrderID assigned
        assertEquals("CHILD2", value(rowFor(changes.get(changes.size() - 1)), "chain_key"));

        cache.process(SampleFix42.parse(SampleFix42.CHILD2_REPLACE_REQUEST));
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_REPLACED_ACK));  // ClOrdID changed
        Object[] last = rowFor(changes.get(changes.size() - 1));
        assertEquals("CHILD2", value(last, "chain_key"));
        assertEquals("CHILD2R", value(last, "cl_ord_id"), "the current ClOrdID did move on");
        assertEquals("EX-C2", value(last, "order_id"));
    }

    @Test
    void fillEconomicsAndTerminalFlag() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_ACK));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_FILL));

        Object[] row = rowFor(changes.get(changes.size() - 1));
        assertEquals("FILLED", value(row, "ord_status"));
        assertEquals(Boolean.TRUE, value(row, "is_terminal"));
        assertEquals(600.0d, value(row, "cum_qty"));
        assertEquals(0.0d, value(row, "leaves_qty"), "a real zero, kept as zero");
        assertEquals(185.503d, value(row, "avg_px"));
        assertEquals(350.0d, value(row, "last_qty"));
        assertEquals(185.52d, value(row, "last_px"));
        assertEquals(3, value(row, "exec_id_count"));
        assertEquals(4L, value(row, "update_count"));
        assertNotNull(value(row, "first_seen_ts"));
    }

    @Test
    void workingOrdersAreNotTerminal() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_ACK));
        assertEquals(Boolean.FALSE, value(rowFor(changes.get(changes.size() - 1)), "is_terminal"));
    }

    @Test
    void marketOrderHasNoPriceRatherThanAZeroPrice() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.MSFT_NOS)); // 40=1, no tag 44
        Object[] row = rowFor(changes.get(0));
        assertNull(value(row, "price"), "a market order has no limit price");
        assertNull(value(row, "avg_px"), "nothing has filled yet");
        assertEquals("MARKET", value(row, "ord_type"));
        assertEquals("SELL", value(row, "side"));
    }

    @Test
    void rejectReasonIsCarriedThrough() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.MSFT_NOS));
        cache.process(SampleFix42.parse(SampleFix42.MSFT_REJECT));

        Object[] row = rowFor(changes.get(changes.size() - 1));
        assertEquals("REJECTED", value(row, "ord_status"));
        assertEquals(Boolean.TRUE, value(row, "is_terminal"));
        assertEquals(11, value(row, "ord_rej_reason"));
        assertEquals("Unknown symbol for account", value(row, "text"));
    }

    @Test
    void aChildsFillProducesAParentRollUpRow() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.PARENT_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_ACK));
        changes.clear();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL));

        List<Object[]> rows = changes.stream().map(this::rowFor).toList();
        assertTrue(rows.size() >= 2, "expected the child change plus a parent roll-up");
        assertEquals("UPDATE", value(rows.get(0), "change_kind"));
        Object[] rollUp = rows.get(rows.size() - 1);
        assertEquals("PARENT_ROLLUP", value(rollUp, "change_kind"));
        assertEquals(Boolean.TRUE, value(rollUp, "is_parent"));
        assertEquals("PARENT1", value(rollUp, "cl_ord_id"));
        assertEquals(250.0d, value(rollUp, "cum_qty"), "the parent aggregates its children's fills");
    }

    @Test
    void clOrdIdChainRecordsEveryIdentityTheOrderHasHad() {
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_NOS));
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_ACK));
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_REPLACE_REQUEST));
        cache.process(SampleFix42.parse(SampleFix42.CHILD2_REPLACED_ACK));

        Object[] row = rowFor(changes.get(changes.size() - 1));
        assertEquals("CHILD2R", value(row, "cl_ord_id"));
        assertEquals("CHILD2", value(row, "orig_cl_ord_id"));
        assertEquals("CHILD2,CHILD2R", value(row, "cl_ord_id_chain"));
    }

    @Test
    void recoveryAnnouncementsAreLabelled() {
        // PersistentOrderCache re-announces restored states with no causing message.
        InMemoryOrderCache cache = cacheRecordingChanges();
        cache.process(SampleFix42.parse(SampleFix42.CHILD1_NOS));
        var restored = changes.get(0).current();

        Object[] row = rowFor(new OrderStateChange(null, restored, null, false, NOW.toEpochMilli()));
        assertEquals("RECOVERY", value(row, "change_kind"));
        assertNull(value(row, "cause_msg_type"));
    }
}
