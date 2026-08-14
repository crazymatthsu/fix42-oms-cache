package com.fix42.oms.model;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStateUpdaterTest {

    private final OrderStateUpdater updater = new OrderStateUpdater();

    private static FixMessage m(String pipe) {
        return FixParser.pipe().parse(pipe);
    }

    @Test
    void newOrderSingleSetsPendingNewAndTerms() {
        OrderState s = updater.apply(null, m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|59=0|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_NEW, s.getOrdStatus());
        assertEquals(1000.0, s.getOrderQty());
        assertEquals(185.5, s.getPrice());
        assertEquals("IBM", s.getSymbol());
        assertEquals("ORD1", s.getClOrdId());
        assertEquals(1, s.getMessageHistoryCount());
    }

    @Test
    void executionReportNewThenPartialFillTracksQuantities() {
        OrderState s = updater.apply(null, m("35=D|11=ORD1|55=IBM|54=1|38=1000|40=2|44=185.5|"));
        s = updater.apply(s, m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|6=0|"));
        assertEquals(OrdStatus.ORD_STATUS_NEW, s.getOrdStatus());
        assertEquals("EX1", s.getOrderId());

        s = updater.apply(s, m("35=8|11=ORD1|37=EX1|17=E2|20=0|150=1|39=1|32=400|31=185.5|151=600|14=400|6=185.5|"));
        assertEquals(OrdStatus.ORD_STATUS_PARTIALLY_FILLED, s.getOrdStatus());
        assertEquals(400.0, s.getCumQty());
        assertEquals(600.0, s.getLeavesQty());
        assertEquals(185.5, s.getAvgPx());
        assertEquals(400.0, s.getLastQty());
        assertEquals(2, s.getExecIdsCount());
    }

    @Test
    void replaceConfirmDrivesStatusFromTag39NotExecType() {
        // ExecType=Replaced (150=5) but OrdStatus=PartiallyFilled (39=1): the order is still
        // working and must NOT be marked terminal REPLACED.
        OrderState s = base();
        s = updater.apply(s, m("35=G|11=R1|41=ORD1|37=EX1|54=1|38=1200|40=2|44=185.5|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_REPLACE, s.getOrdStatus());
        // Terms unchanged until the confirming report.
        assertEquals(1000.0, s.getOrderQty());

        s = updater.apply(s, m("35=8|11=R1|41=ORD1|37=EX1|17=E3|20=0|150=5|39=1|38=1200|151=800|14=400|6=185.5|"));
        assertEquals(OrdStatus.ORD_STATUS_PARTIALLY_FILLED, s.getOrdStatus());
        assertEquals(1200.0, s.getOrderQty()); // new terms applied on confirm
        assertEquals(800.0, s.getLeavesQty());
    }

    @Test
    void cancelRequestThenConfirmReachesCanceled() {
        OrderState s = base();
        s = updater.apply(s, m("35=F|11=C1|41=ORD1|54=1|38=1000|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_CANCEL, s.getOrdStatus());
        s = updater.apply(s, m("35=8|11=C1|41=ORD1|37=EX1|17=E9|20=0|150=4|39=4|151=0|14=0|"));
        assertEquals(OrdStatus.ORD_STATUS_CANCELED, s.getOrdStatus());
        assertFalse(s.getPendingPriorStatusMap().containsKey("C1"));
    }

    @Test
    void cancelRejectRevertsPerRequestForMultipleInFlight() {
        OrderState s = base(); // NEW
        // Two replaces in flight: R1 (prior NEW), then R2 (prior PENDING_REPLACE).
        s = updater.apply(s, m("35=G|11=R1|41=ORD1|37=EX1|54=1|38=1200|40=2|44=185.5|"));
        s = updater.apply(s, m("35=G|11=R2|41=R1|37=EX1|54=1|38=1500|40=2|44=185.5|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_REPLACE, s.getOrdStatus());

        // Reject R2 first -> revert to what preceded R2 (still PENDING_REPLACE from R1).
        s = updater.apply(s, m("35=9|11=R2|41=R1|434=2|102=0|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_REPLACE, s.getOrdStatus());

        // Reject R1 -> revert to NEW (R1's snapshot), proving per-request tracking.
        s = updater.apply(s, m("35=9|11=R1|41=ORD1|434=2|"));
        assertEquals(OrdStatus.ORD_STATUS_NEW, s.getOrdStatus());
    }

    @Test
    void duplicateExecIdIsIdempotent() {
        OrderState s = base();
        String fill = "35=8|11=ORD1|37=EX1|17=E3|20=0|150=1|39=1|32=400|31=185.5|151=600|14=400|6=185.5|";
        s = updater.apply(s, m(fill));
        s = updater.apply(s, m(fill)); // replay
        assertEquals(400.0, s.getCumQty());
        assertEquals(1, countExec(s, "E3"));
    }

    @Test
    void tradeBustRecordsBustedExecIdAndTakesAbsoluteSnapshot() {
        OrderState s = base();
        s = updater.apply(s, m("35=8|11=ORD1|37=EX1|17=E3|20=0|150=1|39=1|32=400|31=185.5|151=600|14=400|6=185.5|"));
        assertEquals(400.0, s.getCumQty());
        // Bust of E3: ExecTransType=Cancel, references E3; venue restates cum=0, leaves=1000.
        s = updater.apply(s, m("35=8|11=ORD1|37=EX1|17=E4|19=E3|20=1|150=1|39=0|151=1000|14=0|6=0|"));
        assertEquals(0.0, s.getCumQty());
        assertEquals(1000.0, s.getLeavesQty());
        assertTrue(s.getBustedExecIdsList().contains("E3"));
    }

    @Test
    void orderStatusRequestDoesNotMutateState() {
        OrderState s = base();
        OrdStatus before = s.getOrdStatus();
        double cumBefore = s.getCumQty();
        s = updater.apply(s, m("35=H|11=ORD1|37=EX1|55=IBM|54=1|"));
        assertEquals(before, s.getOrdStatus());
        assertEquals(cumBefore, s.getCumQty());
    }

    @Test
    void timestampsAndUpdateCountAdvance() {
        OrderStateUpdater fixed = new OrderStateUpdater(
                Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC), 0);
        OrderState s = fixed.apply(null, m("35=D|11=ORD1|55=IBM|54=1|38=1|40=1|"));
        assertEquals(1_000, s.getFirstSeenEpochMillis());
        assertEquals(1_000, s.getLastUpdateEpochMillis());
        assertEquals(1, s.getUpdateCount());
        s = fixed.apply(s, m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|"));
        assertEquals(2, s.getUpdateCount());
    }

    @Test
    void historyCapEvictsOldest() {
        OrderStateUpdater capped = new OrderStateUpdater(Clock.systemUTC(), 2);
        OrderState s = capped.apply(null, m("35=D|11=ORD1|55=IBM|54=1|38=1|40=1|"));
        s = capped.apply(s, m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|"));
        s = capped.apply(s, m("35=8|11=ORD1|37=EX1|17=E2|20=0|150=1|39=1|14=1|151=0|"));
        assertEquals(2, s.getMessageHistoryCount()); // oldest (the D) evicted
    }

    // ---- helpers ----

    /** An order that is NEW: NewOrderSingle + accepting ExecutionReport. */
    private OrderState base() {
        OrderState s = updater.apply(null, m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|59=0|"));
        return updater.apply(s, m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|6=0|"));
    }

    private static int countExec(OrderState s, String execId) {
        int n = 0;
        for (String e : s.getExecIdsList()) {
            if (e.equals(execId)) {
                n++;
            }
        }
        return n;
    }
}
