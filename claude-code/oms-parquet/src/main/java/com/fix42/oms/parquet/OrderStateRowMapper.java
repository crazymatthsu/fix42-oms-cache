package com.fix42.oms.parquet;

import com.fix42.oms.cache.OrderStateChange;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Turns an {@link OrderStateChange} into a {@link Dataset#ORDER_STATE_CHANGES} row.
 *
 * <p>The dataset is an append-only version history, not a mutable last-value table: one row
 * per change, with {@code update_count} as the version. A reader reconstructs the latest
 * state — at the end of the day or as of any instant — with
 * {@code last_by(chain_key ORDER BY ts, update_count)}, which is also exactly the shape
 * Deephaven's {@code lastBy} wants for an intraday blink/append table.
 *
 * <p>Enum columns are decoded to their short names ({@code BUY}, {@code PARTIALLY_FILLED}),
 * and an unset enum is written as NULL rather than {@code UNSPECIFIED} — an analyst
 * filtering {@code side = 'BUY'} should never have to know the protobuf default.
 *
 * <p>Stateless and thread-safe.
 */
final class OrderStateRowMapper {

    /**
     * Statuses after which no further execution is expected. {@code REPLACED} is deliberately
     * excluded: the cache keeps one chain across a cancel/replace, so a chain in
     * {@code REPLACED} is still working under its new ClOrdID.
     */
    private static final Set<OrdStatus> TERMINAL = EnumSet.of(
            OrdStatus.ORD_STATUS_FILLED,
            OrdStatus.ORD_STATUS_CANCELED,
            OrdStatus.ORD_STATUS_REJECTED,
            OrdStatus.ORD_STATUS_EXPIRED,
            OrdStatus.ORD_STATUS_DONE_FOR_DAY);

    private final String writerId;

    OrderStateRowMapper(ParquetArchiveConfig config) {
        this.writerId = config.writerId();
    }

    Object[] toRow(OrderStateChange change, LocalDate eventDate, long ingestSeq) {
        OrderState s = change.current();
        OrderState prev = change.previous();
        FixMessage cause = change.cause();

        Object[] r = new Object[Dataset.ORDER_STATE_CHANGES.columnCount()];
        int i = 0;

        r[i++] = eventDate;
        r[i++] = Timestamps.utc(change.arrivalEpochMillis());
        r[i++] = writerId;
        r[i++] = ingestSeq;

        r[i++] = chainKey(s);
        r[i++] = text(s.getOrderId());
        r[i++] = text(s.getClOrdId());
        r[i++] = text(s.getOrigClOrdId());
        r[i++] = text(s.getAccount());
        r[i++] = text(s.getSymbol());

        r[i++] = enumName(s.getSide().name(), "SIDE_");
        r[i++] = enumName(s.getOrdType().name(), "ORD_TYPE_");
        r[i++] = s.getOrderQty();
        r[i++] = nonZero(s.getPrice());
        r[i++] = nonZero(s.getStopPx());
        r[i++] = enumName(s.getTimeInForce().name(), "TIME_IN_FORCE_");
        r[i++] = text(s.getCurrency());

        r[i++] = enumName(s.getOrdStatus().name(), "ORD_STATUS_");
        r[i++] = (prev == null) ? null : enumName(prev.getOrdStatus().name(), "ORD_STATUS_");
        r[i++] = enumName(s.getLastExecType().name(), "EXEC_TYPE_");
        r[i++] = TERMINAL.contains(s.getOrdStatus());
        r[i++] = s.getCumQty();
        r[i++] = s.getLeavesQty();
        r[i++] = nonZero(s.getAvgPx());
        r[i++] = nonZero(s.getLastQty());
        r[i++] = nonZero(s.getLastPx());
        r[i++] = text(s.getLastMarket());

        r[i++] = text(s.getText());
        r[i++] = (s.getOrdRejReason() == 0) ? null : s.getOrdRejReason();
        r[i++] = (s.getCxlRejReason() == 0) ? null : s.getCxlRejReason();

        r[i++] = text(s.getParentOrderId());
        r[i++] = s.getIsParent();
        r[i++] = s.getChildOrderIdsCount();
        r[i++] = joinOrNull(s.getClOrdIdHistoryList());
        r[i++] = s.getExecIdsCount();

        r[i++] = s.getUpdateCount();
        r[i++] = Timestamps.utcOrNull(s.getFirstSeenEpochMillis());
        r[i++] = Timestamps.utcOrNull(s.getLastUpdateEpochMillis());
        r[i++] = text(s.getLastMsgType());

        r[i++] = changeKind(change);
        r[i++] = (cause == null) ? null : nullIfEmpty(FixSupport.firstValue(cause, Tags.MSG_TYPE));
        r[i++] = (cause == null) ? null : nullIfEmpty(FixSupport.firstValue(cause, Tags.CL_ORD_ID));
        r[i++] = (cause == null) ? null : nullIfEmpty(FixSupport.firstValue(cause, Tags.EXEC_ID));

        if (i != r.length) {
            throw new IllegalStateException("OrderStateRowMapper filled " + i + " of " + r.length + " columns");
        }
        return r;
    }

    /**
     * The grouping key for {@code last_by}, and the one thing about this dataset that has to
     * be got right: it must identify a chain <b>for the chain's whole life</b>.
     *
     * <p>Neither obvious candidate does. OrderID(37) does not exist until the first
     * ExecutionReport, so a NewOrderSingle has none; the current ClOrdID(11) changes on every
     * cancel/replace. Either choice splits one order's history into two groups, and
     * {@code last_by} then reports a long-filled order as still pending under its old key.
     *
     * <p>The chain's <b>first</b> ClOrdID is stable — {@code cl_ord_id_history} is
     * append-only, so element 0 never changes — and is therefore the key. A chain that never
     * carried a ClOrdID at all (a drop-copy that opens on a DK trade, say) falls back to
     * OrderID.
     */
    private static String chainKey(OrderState s) {
        if (s.getClOrdIdHistoryCount() > 0) {
            String first = text(s.getClOrdIdHistory(0));
            if (first != null) {
                return first;
            }
        }
        String clOrdId = text(s.getClOrdId());
        return (clOrdId != null) ? clOrdId : text(s.getOrderId());
    }

    private static String changeKind(OrderStateChange change) {
        if (change.isRecoveryAnnouncement()) {
            return "RECOVERY";
        }
        if (change.parentRollUp()) {
            return "PARENT_ROLLUP";
        }
        return change.isCreate() ? "CREATE" : "UPDATE";
    }

    /** Strip the protobuf enum prefix; {@code UNSPECIFIED}/{@code UNRECOGNIZED} become NULL. */
    private static String enumName(String protoName, String prefix) {
        String name = protoName.startsWith(prefix) ? protoName.substring(prefix.length()) : protoName;
        return ("UNSPECIFIED".equals(name) || "UNRECOGNIZED".equals(name)) ? null : name;
    }

    /** proto3 strings default to {@code ""}; store that as NULL. */
    private static String text(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * Price-like fields: proto3 defaults them to {@code 0.0}, which for a price means
     * "absent" (a market order has no limit price, an unfilled order no average price).
     * Storing NULL keeps those out of the column's min/max statistics, so a query for the
     * cheapest fill of the day is not answered with a zero. Quantities keep their zeros —
     * {@code leaves_qty = 0} is a real, meaningful value.
     */
    private static Double nonZero(double value) {
        return (value == 0.0d) ? null : value;
    }

    private static String nullIfEmpty(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static String joinOrNull(List<String> values) {
        return values.isEmpty() ? null : String.join(",", values);
    }
}
