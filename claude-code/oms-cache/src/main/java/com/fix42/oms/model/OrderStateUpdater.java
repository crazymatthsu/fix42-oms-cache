package com.fix42.oms.model;

import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.dict.FixCodes;
import com.fix42.oms.proto.ExecTransType;
import com.fix42.oms.proto.ExecType;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;

import java.time.Clock;

/**
 * The order state machine: folds a single FIX message into an {@link OrderState}.
 *
 * <p>Pure and side-effect free — {@link #apply} takes the previous state (or {@code null}
 * for a new chain) and returns the next immutable state. It never touches cache indexes;
 * chain resolution and parent linkage are the cache's responsibility.
 *
 * <p>Key correctness rules (see docs/06-api-and-state-machine.md):
 * <ul>
 *   <li>On an ExecutionReport, {@code ord_status} is driven by <b>tag 39</b>, never forced
 *       from ExecType — so an ExecType=Replaced report that is still working keeps its live
 *       status instead of being marked terminal.</li>
 *   <li>{@code cum_qty}/{@code leaves_qty}/{@code avg_px} are taken as the venue's <b>absolute
 *       restated snapshot</b>; trade busts/corrections (ExecTransType 1/2) record the busted
 *       ExecID for audit but do not re-derive quantities.</li>
 *   <li>Cancel/replace requests record the prior status <b>per request ClOrdID</b>, so several
 *       in-flight F/G do not clobber one another; an OrderCancelReject reverts by its ClOrdID.</li>
 *   <li>Order <b>terms</b> (qty/price/type/TIF) change only on D and ExecutionReport, never on a
 *       request — a replace's new terms take effect on the confirming ExecutionReport.</li>
 *   <li>Duplicate ExecIDs are ignored for economic fields (idempotent replay).</li>
 * </ul>
 */
public final class OrderStateUpdater {

    private final Clock clock;
    private final int historyCap; // 0 = unbounded

    public OrderStateUpdater(Clock clock, int historyCap) {
        this.clock = clock;
        this.historyCap = historyCap;
    }

    public OrderStateUpdater() {
        this(Clock.systemUTC(), 0);
    }

    public OrderState apply(OrderState prev, FixMessage msg) {
        String msgType = FixSupport.msgType(msg);
        OrderState.Builder b = (prev == null) ? OrderState.newBuilder() : prev.toBuilder();

        long now = clock.millis();
        if (prev == null) {
            b.setFirstSeenEpochMillis(now);
        }
        b.setLastUpdateEpochMillis(now);
        b.setUpdateCount(b.getUpdateCount() + 1);
        if (msgType != null) {
            b.setLastMsgType(msgType);
        }

        applyIdentity(b, msg, msgType);

        if (msgType != null) {
            switch (msgType) {
                case "D" -> applyNewOrderSingle(b, msg);
                case "8" -> applyExecutionReport(b, msg);
                case "9" -> applyCancelReject(b, msg);
                case "F" -> applyCancelRequest(b, msg);
                case "G" -> applyReplaceRequest(b, msg);
                case "H" -> { /* OrderStatusRequest: query only, no state mutation */ }
                case "Q" -> applyDontKnow(b, msg);
                default -> { /* unknown message type: recorded in history only */ }
            }
        }

        appendHistory(b, msg);
        return b.build();
    }

    // ------------------------------------------------------------------
    // Identity fields (applied for every message type)
    // ------------------------------------------------------------------

    private void applyIdentity(OrderState.Builder b, FixMessage msg, String msgType) {
        String orderId = FixSupport.firstValue(msg, Tags.ORDER_ID);
        if (notBlank(orderId)) {
            b.setOrderId(orderId);
        }
        String origClOrdId = FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID);
        if (notBlank(origClOrdId)) {
            b.setOrigClOrdId(origClOrdId);
        }
        String clOrdId = FixSupport.firstValue(msg, Tags.CL_ORD_ID);
        if (notBlank(clOrdId)) {
            if (!b.getClOrdIdHistoryList().contains(clOrdId)) {
                b.addClOrdIdHistory(clOrdId);
            }
            // "current" ClOrdID advances on every request/report except a reject,
            // which leaves the working ClOrdID unchanged (the request was refused).
            if (!"9".equals(msgType)) {
                b.setClOrdId(clOrdId);
            }
        }
        String account = FixSupport.firstValue(msg, Tags.ACCOUNT);
        if (notBlank(account)) {
            b.setAccount(account);
        }
        String symbol = FixSupport.firstValue(msg, Tags.SYMBOL);
        if (notBlank(symbol)) {
            b.setSymbol(symbol);
        }
        String side = FixSupport.firstValue(msg, Tags.SIDE);
        if (notBlank(side)) {
            b.setSide(FixCodes.sideFromCode(side));
        }
    }

    /** Order terms — applied only where the venue establishes/confirms them (D and 8). */
    private void applyTerms(OrderState.Builder b, FixMessage msg) {
        String ordType = FixSupport.firstValue(msg, Tags.ORD_TYPE);
        if (notBlank(ordType)) {
            b.setOrdType(FixCodes.ordTypeFromCode(ordType));
        }
        String orderQty = FixSupport.firstValue(msg, Tags.ORDER_QTY);
        if (notBlank(orderQty)) {
            b.setOrderQty(FixSupport.parseDouble(orderQty, b.getOrderQty()));
        }
        String price = FixSupport.firstValue(msg, Tags.PRICE);
        if (notBlank(price)) {
            b.setPrice(FixSupport.parseDouble(price, b.getPrice()));
        }
        String stopPx = FixSupport.firstValue(msg, Tags.STOP_PX);
        if (notBlank(stopPx)) {
            b.setStopPx(FixSupport.parseDouble(stopPx, b.getStopPx()));
        }
        String tif = FixSupport.firstValue(msg, Tags.TIME_IN_FORCE);
        if (notBlank(tif)) {
            b.setTimeInForce(FixCodes.timeInForceFromCode(tif));
        }
        String ccy = FixSupport.firstValue(msg, Tags.CURRENCY);
        if (notBlank(ccy)) {
            b.setCurrency(ccy);
        }
    }

    // ------------------------------------------------------------------
    // Per-message handlers
    // ------------------------------------------------------------------

    private void applyNewOrderSingle(OrderState.Builder b, FixMessage msg) {
        applyTerms(b, msg);
        // Cache assumption until the first ExecutionReport arrives.
        if (b.getOrdStatus() == OrdStatus.ORD_STATUS_UNSPECIFIED) {
            b.setOrdStatus(OrdStatus.ORD_STATUS_PENDING_NEW);
        }
    }

    private void applyExecutionReport(OrderState.Builder b, FixMessage msg) {
        applyTerms(b, msg);

        String execId = FixSupport.firstValue(msg, Tags.EXEC_ID);
        boolean duplicate = notBlank(execId) && b.getExecIdsList().contains(execId);

        ExecTransType transType = FixCodes.execTransTypeFromCode(
                FixSupport.firstValue(msg, Tags.EXEC_TRANS_TYPE));

        if (!duplicate) {
            // Status is driven by tag 39 (OrdStatus), NOT by ExecType.
            String ordStatus = FixSupport.firstValue(msg, Tags.ORD_STATUS);
            if (notBlank(ordStatus)) {
                b.setOrdStatus(FixCodes.ordStatusFromCode(ordStatus));
            }
            String execType = FixSupport.firstValue(msg, Tags.EXEC_TYPE);
            if (notBlank(execType)) {
                b.setLastExecType(FixCodes.execTypeFromCode(execType));
            }
            // Absolute venue snapshots.
            setDoubleIfPresent(msg, Tags.CUM_QTY, b::setCumQty);
            setDoubleIfPresent(msg, Tags.LEAVES_QTY, b::setLeavesQty);
            setDoubleIfPresent(msg, Tags.AVG_PX, b::setAvgPx);
            setDoubleIfPresent(msg, Tags.LAST_SHARES, b::setLastQty);
            setDoubleIfPresent(msg, Tags.LAST_PX, b::setLastPx);
            String lastMkt = FixSupport.firstValue(msg, Tags.LAST_MKT);
            if (notBlank(lastMkt)) {
                b.setLastMarket(lastMkt);
            }
            // Reject details.
            ExecType et = b.getLastExecType();
            if (et == ExecType.EXEC_TYPE_REJECTED
                    || b.getOrdStatus() == OrdStatus.ORD_STATUS_REJECTED) {
                b.setOrdRejReason(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.ORD_REJ_REASON), b.getOrdRejReason()));
                String text = FixSupport.firstValue(msg, Tags.TEXT);
                if (notBlank(text)) {
                    b.setText(text);
                }
            }
            // Trade bust / correction: record the busted ExecID for audit; do not re-derive.
            if (transType == ExecTransType.EXEC_TRANS_TYPE_CANCEL
                    || transType == ExecTransType.EXEC_TRANS_TYPE_CORRECT) {
                String execRefId = FixSupport.firstValue(msg, Tags.EXEC_REF_ID);
                if (notBlank(execRefId) && !b.getBustedExecIdsList().contains(execRefId)) {
                    b.addBustedExecIds(execRefId);
                }
            }
            // Clear the matching in-flight transition on a confirm.
            ExecType confirmType = b.getLastExecType();
            if (confirmType == ExecType.EXEC_TYPE_CANCELED || confirmType == ExecType.EXEC_TYPE_REPLACED) {
                String clOrdId = FixSupport.firstValue(msg, Tags.CL_ORD_ID);
                if (notBlank(clOrdId)) {
                    b.removePendingPriorStatus(clOrdId);
                }
            }
        }

        if (notBlank(execId) && !b.getExecIdsList().contains(execId)) {
            b.addExecIds(execId);
        }
    }

    private void applyCancelRequest(OrderState.Builder b, FixMessage msg) {
        stagePending(b, msg);
        b.setOrdStatus(OrdStatus.ORD_STATUS_PENDING_CANCEL);
    }

    private void applyReplaceRequest(OrderState.Builder b, FixMessage msg) {
        // New terms are NOT applied here — they take effect on the confirming ExecutionReport.
        stagePending(b, msg);
        b.setOrdStatus(OrdStatus.ORD_STATUS_PENDING_REPLACE);
    }

    /** Snapshot the current status keyed by the request's ClOrdID for later revert. */
    private void stagePending(OrderState.Builder b, FixMessage msg) {
        String reqClOrdId = FixSupport.firstValue(msg, Tags.CL_ORD_ID);
        if (notBlank(reqClOrdId)) {
            b.putPendingPriorStatus(reqClOrdId, b.getOrdStatus().getNumber());
        }
    }

    private void applyCancelReject(OrderState.Builder b, FixMessage msg) {
        String reqClOrdId = FixSupport.firstValue(msg, Tags.CL_ORD_ID);
        Integer prior = notBlank(reqClOrdId) ? b.getPendingPriorStatusMap().get(reqClOrdId) : null;
        if (prior != null) {
            OrdStatus reverted = OrdStatus.forNumber(prior);
            b.setOrdStatus(reverted != null ? reverted : OrdStatus.ORD_STATUS_UNSPECIFIED);
            b.removePendingPriorStatus(reqClOrdId);
        } else {
            // No tracked request: fall back to the OrdStatus the reject reports, if any.
            String ordStatus = FixSupport.firstValue(msg, Tags.ORD_STATUS);
            if (notBlank(ordStatus)) {
                b.setOrdStatus(FixCodes.ordStatusFromCode(ordStatus));
            }
        }
        b.setCxlRejReason(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.CXL_REJ_REASON), b.getCxlRejReason()));
        String text = FixSupport.firstValue(msg, Tags.TEXT);
        if (notBlank(text)) {
            b.setText(text);
        }
    }

    private void applyDontKnow(OrderState.Builder b, FixMessage msg) {
        // A DK does not change fill state; capture the reason text for reconciliation.
        String text = FixSupport.firstValue(msg, Tags.TEXT);
        if (notBlank(text)) {
            b.setText(text);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void appendHistory(OrderState.Builder b, FixMessage msg) {
        b.addMessageHistory(msg);
        if (historyCap > 0) {
            while (b.getMessageHistoryCount() > historyCap) {
                b.removeMessageHistory(0);
            }
        }
    }

    private interface DoubleSetter {
        void accept(double v);
    }

    private static void setDoubleIfPresent(FixMessage msg, int tag, DoubleSetter setter) {
        String v = FixSupport.firstValue(msg, tag);
        if (notBlank(v)) {
            setter.accept(FixSupport.parseDouble(v, 0));
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
