package com.fix42.oms.parquet;

import com.fix42.oms.proto.FixMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RawFixRowMapperTest {

    private static final Dataset DATASET = Dataset.RAW_FIX_MESSAGES;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);
    private static final long TS = java.time.Instant.parse("2026-08-04T13:31:00Z").toEpochMilli();

    private static ParquetArchiveConfig config() {
        return ParquetArchiveConfig.defaults(Path.of("/tmp/unused")).withWriterId("test-writer");
    }

    private static Object value(Object[] row, String column) {
        return row[DATASET.indexOf(column)];
    }

    @Test
    void executionReportTagsAreLiftedIntoColumns() {
        FixMessage message = SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL);
        Object[] row = new RawFixRowMapper(config()).toRow(message, null, TS, DATE, 42L);

        assertEquals(DATE, value(row, "event_date"));
        assertEquals(LocalDateTime.of(2026, 8, 4, 13, 31, 0), value(row, "ts"));
        assertEquals("test-writer", value(row, "writer_id"));
        assertEquals(42L, value(row, "ingest_seq"));

        assertEquals("8", value(row, "msg_type"));
        assertEquals("ACC1", value(row, "account"));
        assertEquals("IBM", value(row, "symbol"));
        assertEquals("CHILD1", value(row, "cl_ord_id"));
        assertEquals("EX-C1", value(row, "order_id"));
        assertEquals("E2", value(row, "exec_id"));
        assertEquals("PARENT1", value(row, "secondary_cl_ord_id"));

        assertEquals(250.0d, value(row, "last_qty"));
        assertEquals(185.48d, value(row, "last_px"));
        assertEquals("N", value(row, "last_mkt"));
        assertEquals(250.0d, value(row, "cum_qty"));
        assertEquals(350.0d, value(row, "leaves_qty"));
        assertEquals(185.48d, value(row, "avg_px"));
        assertEquals(600.0d, value(row, "order_qty"));
    }

    @Test
    void enumTagsKeepTheirWireValuesInTheAuditDataset() {
        Object[] row = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL), null, TS, DATE, 1L);
        // Not decoded to BUY / PARTIALLY_FILLED: the raw dataset records what was on the wire,
        // including codes the dictionary would not recognise.
        assertEquals("1", value(row, "side"));
        assertEquals("1", value(row, "ord_status"));
        assertEquals("1", value(row, "exec_type"));
        assertEquals("0", value(row, "exec_trans_type"));
    }

    @Test
    void absentTagsBecomeNullNotEmptyOrZero() {
        Object[] row = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.CHILD1_PARTIAL_FILL), null, TS, DATE, 1L);
        assertNull(value(row, "price"), "tag 44 is absent from this execution report");
        assertNull(value(row, "stop_px"));
        assertNull(value(row, "orig_cl_ord_id"));
        assertNull(value(row, "text"));
        assertNull(value(row, "ord_rej_reason"));
        assertNull(value(row, "dk_reason"));
    }

    @Test
    void newOrderSingleCarriesItsTerms() {
        Object[] row = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.PARENT_NOS), null, TS, DATE, 1L);
        assertEquals("D", value(row, "msg_type"));
        assertEquals("PARENT1", value(row, "cl_ord_id"));
        assertEquals(1000.0d, value(row, "order_qty"));
        assertEquals(185.50d, value(row, "price"));
        assertEquals("2", value(row, "ord_type"));
        assertEquals("0", value(row, "time_in_force"));
        assertEquals("USD", value(row, "currency"));
        assertEquals(1L, value(row, "msg_seq_num"));
        assertEquals("BUYSIDE", value(row, "sender_comp_id"));
        assertEquals("SELLSIDE", value(row, "target_comp_id"));
    }

    @Test
    void rejectAndCancelRejectReasonsAreTyped() {
        Object[] reject = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.MSFT_REJECT), null, TS, DATE, 1L);
        assertEquals(11, value(reject, "ord_rej_reason"));
        assertEquals("Unknown symbol for account", value(reject, "text"));

        Object[] cancelReject = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.CHILD2_CANCEL_REJECT), null, TS, DATE, 2L);
        // 102=0 is CxlRejReason "too late to cancel" — a real zero, not an absent tag.
        assertEquals(0, value(cancelReject, "cxl_rej_reason"));
        assertEquals("1", value(cancelReject, "cxl_rej_response_to"));
        assertEquals("CHILD2R", value(cancelReject, "orig_cl_ord_id"));
    }

    @Test
    void sendingAndTransactTimesAreParsedIntoTimestampColumns() {
        Object[] row = new RawFixRowMapper(config())
                .toRow(SampleFix42.parse(SampleFix42.PARENT_NOS), null, TS, DATE, 1L);
        assertEquals(LocalDateTime.of(2026, 8, 14, 13, 30, 0), value(row, "sending_ts"));
        assertEquals(LocalDateTime.of(2026, 8, 14, 13, 30, 0), value(row, "transact_ts"));
    }

    @Test
    void originalWireTextIsPreservedWhenSupplied() {
        String raw = SampleFix42.CHILD1_ACK;
        Object[] row = new RawFixRowMapper(config()).toRow(SampleFix42.parse(raw), raw, TS, DATE, 1L);
        assertEquals(raw, value(row, "raw_fix"));
    }

    @Test
    void withoutTheOriginalTextTheMessageIsReserialisedVerbatim() {
        String raw = SampleFix42.CHILD1_ACK;
        Object[] row = new RawFixRowMapper(config()).toRow(SampleFix42.parse(raw), null, TS, DATE, 1L);
        // Verbatim re-serialisation keeps field order and values, so a pipe-delimited input
        // round-trips exactly.
        assertEquals(raw, value(row, "raw_fix"));
    }

    @Test
    void rawTextCanBeTurnedOffToSaveSpace() {
        Object[] row = new RawFixRowMapper(config().withCaptureRawFixText(false))
                .toRow(SampleFix42.parse(SampleFix42.CHILD1_ACK), SampleFix42.CHILD1_ACK, TS, DATE, 1L);
        assertNull(value(row, "raw_fix"));
        assertNotNull(value(row, "cl_ord_id"), "lifted columns survive");
    }

    @Test
    void rowsMatchTheDeclaredSchema() {
        for (String raw : SampleFix42.capturedStream()) {
            Object[] row = new RawFixRowMapper(config()).toRow(SampleFix42.parse(raw), raw, TS, DATE, 1L);
            DATASET.checkRow(row);
        }
    }
}
