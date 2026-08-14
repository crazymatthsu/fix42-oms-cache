package com.fix42.oms.api;

import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmsCacheIntegrationTest {

    @Test
    void fullLifecycleFromRawSohStrings() {
        OmsCache cache = OmsCache.inMemory();
        String SOH = "";

        cache.process("8=FIX.4.2" + SOH + "35=D" + SOH + "11=ORD1" + SOH + "1=ACC" + SOH
                + "55=IBM" + SOH + "54=1" + SOH + "38=1000" + SOH + "40=2" + SOH + "44=185.50" + SOH + "10=000" + SOH);
        cache.process("35=8|11=ORD1|37=EX1|17=E1|20=0|150=A|39=A|151=1000|14=0|");
        cache.process("35=8|11=ORD1|37=EX1|17=E2|20=0|150=0|39=0|151=1000|14=0|");
        cache.process("35=8|11=ORD1|37=EX1|17=E3|20=0|150=1|39=1|32=400|31=185.50|151=600|14=400|6=185.50|");
        cache.process("35=8|11=ORD1|37=EX1|17=E4|20=0|150=2|39=2|32=600|31=185.55|151=0|14=1000|6=185.53|");

        OrderState s = cache.getByOrderId("EX1").orElseThrow();
        assertEquals(OrdStatus.ORD_STATUS_FILLED, s.getOrdStatus());
        assertEquals(1000.0, s.getCumQty());
        assertEquals(0.0, s.getLeavesQty());
        assertEquals(4, s.getExecIdsCount());
        assertEquals(5, s.getMessageHistoryCount());
    }

    @Test
    void perTypeMethodRejectsWrongMsgType() {
        OmsCache cache = OmsCache.inMemory();
        var wrong = com.fix42.oms.fix.FixParser.pipe().parse("35=8|11=ORD1|37=EX1|17=E1|150=0|39=0|");
        assertTrue(assertThrowsIllegalArgument(() -> cache.processNewOrderSingle(wrong)));
    }

    @Test
    void dontKnowTradeIsRecordedWithoutChangingFillState() {
        OmsCache cache = OmsCache.inMemory();
        cache.process("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|");
        cache.process("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=100|14=0|");
        OrderState before = cache.getByOrderId("EX1").orElseThrow();
        // Counterparty DKs an execution referencing this order/exec.
        cache.process("35=Q|37=EX1|17=E1|127=A|55=IBM|54=1|38=100|32=100|31=50|58=unknown trade|");
        OrderState after = cache.getByOrderId("EX1").orElseThrow();
        assertEquals(before.getCumQty(), after.getCumQty());
        assertEquals(before.getOrdStatus(), after.getOrdStatus());
        assertEquals("unknown trade", after.getText());
    }

    @Test
    void typedProtoConvenienceOverloadFeedsCache() {
        OmsCache cache = OmsCache.inMemory();
        var nos = com.fix42.oms.proto.NewOrderSingle.newBuilder()
                .setClOrdId("ORD9")
                .setAccount("ACC")
                .setSymbol("IBM")
                .setSide(com.fix42.oms.proto.Side.SIDE_BUY)
                .setOrderQty(250)
                .setOrdType(com.fix42.oms.proto.OrdType.ORD_TYPE_LIMIT)
                .setPrice(100.25)
                .build();
        OrderState s = cache.process(nos);
        assertEquals("ORD9", s.getClOrdId());
        assertEquals(250.0, s.getOrderQty());
        assertTrue(cache.getByClOrdId("ORD9").isPresent());
    }

    @Test
    void concurrentIndependentOrdersAreAllCachedCorrectly() throws Exception {
        OmsCache cache = OmsCache.inMemory();
        int orders = 200;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger errors = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < orders; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    try {
                        String cl = "O" + id;
                        String ex = "X" + id;
                        cache.process("35=D|11=" + cl + "|1=ACC|55=IBM|54=1|38=100|40=1|");
                        cache.process("35=8|11=" + cl + "|37=" + ex + "|17=" + ex + "N|20=0|150=0|39=0|151=100|14=0|");
                        cache.process("35=8|11=" + cl + "|37=" + ex + "|17=" + ex + "F|20=0|150=2|39=2|32=100|31=50|151=0|14=100|6=50|");
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, errors.get());
        assertEquals(orders, cache.size());
        for (int i = 0; i < orders; i++) {
            OrderState s = cache.getByOrderId("X" + i).orElseThrow();
            assertEquals(OrdStatus.ORD_STATUS_FILLED, s.getOrdStatus());
            assertEquals(100.0, s.getCumQty());
        }
    }

    private static boolean assertThrowsIllegalArgument(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
