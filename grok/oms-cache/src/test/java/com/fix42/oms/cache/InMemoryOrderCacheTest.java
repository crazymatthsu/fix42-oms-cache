package com.fix42.oms.cache;

import com.fix42.oms.api.UnidentifiableOrderException;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryOrderCacheTest {

    @Test
    void newThenErBindsClOrdIdOrderIdAndExecId() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "ACC",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "100",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "10"
        ));
        ProcessResult er = cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.LEAVES_QTY, "100",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0"
        ));

        assertThat(er.orderKey()).isEqualTo("O9");
        assertThat(cache.getByClOrdId("C1")).isPresent();
        assertThat(cache.getByOrderId("O9")).isPresent();
        assertThat(cache.getByExecId("E1")).isPresent();
        assertThat(cache.getByClOrdId("C1").orElseThrow().getOrderKey()).isEqualTo("O9");
        assertThat(cache.getByClOrdId("C1").orElseThrow().getOrdStatus()).isEqualTo("0");
        assertThat(cache.findByAccount("ACC")).hasSize(1);
        assertThat(cache.findBySymbol("MSFT")).hasSize(1);
    }

    @Test
    void cancelReplaceChainsClOrdIdsOntoSameOrder() {
        InMemoryOrderCache cache = seeded();
        cache.ingest(FixMessages.build("G",
                Tags.CL_ORD_ID, "C2",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORD_TYPE, "2",
                Tags.ORDER_QTY, "80",
                Tags.PRICE, "11"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C2",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "5",
                Tags.ORD_STATUS, "5",
                Tags.ORDER_QTY, "80",
                Tags.LEAVES_QTY, "80",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0"
        ));

        OrderState state = cache.getByClOrdId("C1").orElseThrow();
        assertThat(cache.getByClOrdId("C2").orElseThrow().getOrderKey()).isEqualTo(state.getOrderKey());
        assertThat(state.getClOrdId()).isEqualTo("C2");
        assertThat(state.getOrderQty()).isEqualTo(80.0);
        assertThat(state.getClOrdIdHistoryList()).contains("C1", "C2");
        assertThat(state.getPendingReplace()).isFalse();
    }

    @Test
    void cancelRejectRestoresAcceptedStatus() {
        InMemoryOrderCache cache = seeded();
        cache.ingest(FixMessages.build("F",
                Tags.CL_ORD_ID, "C3",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1"
        ));
        assertThat(cache.getByOrderId("O9").orElseThrow().getPendingCancel()).isTrue();
        assertThat(cache.getByOrderId("O9").orElseThrow().getOrdStatus()).isEqualTo("6");

        cache.ingest(FixMessages.build("9",
                Tags.CL_ORD_ID, "C3",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.ORD_STATUS, "0",
                Tags.CXL_REJ_RESPONSE_TO, "1",
                Tags.CXL_REJ_REASON, "0",
                Tags.TEXT, "too late"
        ));
        OrderState state = cache.getByOrderId("O9").orElseThrow();
        assertThat(state.getPendingCancel()).isFalse();
        assertThat(state.getOrdStatus()).isEqualTo("0");
        assertThat(state.getCxlRejReason()).isEqualTo("0");
        assertThat(cache.getByClOrdId("C3")).isPresent();
    }

    @Test
    void staleExecutionReportDoesNotOverwriteNewerState() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "1",
                Tags.ORD_STATUS, "1",
                Tags.CUM_QTY, "50",
                Tags.LEAVES_QTY, "50",
                Tags.AVG_PX, "10",
                Tags.TRANSACT_TIME, "20260115-12:00:10"
        ));
        ProcessResult stale = cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0",
                Tags.LEAVES_QTY, "100",
                Tags.AVG_PX, "0",
                Tags.TRANSACT_TIME, "20260115-12:00:01"
        ));
        assertThat(stale.applied()).isFalse();
        OrderState state = cache.getByOrderId("O9").orElseThrow();
        assertThat(state.getCumQty()).isEqualTo(50.0);
        assertThat(state.getOrdStatus()).isEqualTo("1");
        assertThat(cache.getByExecId("E1")).isPresent();
    }

    @Test
    void statusRequestDoesNotMutateBusinessFields() {
        InMemoryOrderCache cache = seeded();
        OrderState before = cache.getByOrderId("O9").orElseThrow();
        ProcessResult result = cache.ingest(FixMessages.build("H",
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1"
        ));
        assertThat(result.applied()).isFalse();
        OrderState after = cache.getByOrderId("O9").orElseThrow();
        assertThat(after.getOrdStatus()).isEqualTo(before.getOrdStatus());
        assertThat(after.getLastMsgType()).isEqualTo("H");
        assertThat(cache.getHistory(after.getOrderKey())).isNotEmpty();
    }

    @Test
    void dontKnowTradeFlagsOrderWithoutUnwindingQty() {
        InMemoryOrderCache cache = seeded();
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.LAST_SHARES, "100",
                Tags.LAST_PX, "10",
                Tags.CUM_QTY, "100",
                Tags.LEAVES_QTY, "0",
                Tags.AVG_PX, "10"
        ));
        cache.ingest(FixMessages.build("Q",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.DK_REASON, "D",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1"
        ));
        OrderState state = cache.getByOrderId("O9").orElseThrow();
        assertThat(state.getDkTrade()).isTrue();
        assertThat(state.getDkReason()).isEqualTo("D");
        assertThat(state.getCumQty()).isEqualTo(100.0);
    }

    @Test
    void parentChildGraphAndRollup() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "P1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "1000",
                Tags.ORD_TYPE, "2"
        ));
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "400",
                Tags.ORD_TYPE, "2",
                Tags.PARENT_ORDER_ID, "P1",
                Tags.PARENT_CL_ORD_ID, "P1"
        ));
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C2",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "600",
                Tags.ORD_TYPE, "2",
                Tags.PARENT_ORDER_ID, "P1"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C2",
                Tags.ORDER_ID, "B2",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.CUM_QTY, "600",
                Tags.LEAVES_QTY, "0",
                Tags.AVG_PX, "420",
                Tags.PARENT_ORDER_ID, "P1"
        ));

        assertThat(cache.getChildren("P1")).hasSize(2);
        ChildRollup rollup = cache.rollup("P1");
        assertThat(rollup.childCount()).isEqualTo(2);
        assertThat(rollup.orderQty()).isEqualTo(1000.0);
        assertThat(rollup.cumQty()).isEqualTo(600.0);
        assertThat(cache.getParent(cache.getByClOrdId("C1").orElseThrow().getOrderKey())).isPresent();
        assertThat(cache.getByClOrdId("P1").orElseThrow().getChildOrderKeysList()).isNotEmpty();
    }

    @Test
    void historyIsBoundedRingBuffer() {
        InMemoryOrderCache cache = new InMemoryOrderCache(CacheConfig.builder().historyLimit(2).build());
        cache.ingest(FixMessages.d(Tags.CL_ORD_ID, "C1", Tags.SYMBOL, "X", Tags.SIDE, "1", Tags.ORDER_QTY, "1", Tags.ORD_TYPE, "1"));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1", Tags.ORDER_ID, "O1", Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0", Tags.EXEC_TYPE, "0", Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0", Tags.LEAVES_QTY, "1", Tags.AVG_PX, "0"
        ));
        cache.ingest(FixMessages.build("H", Tags.CL_ORD_ID, "C1", Tags.SYMBOL, "X", Tags.SIDE, "1"));
        String key = cache.getByOrderId("O1").orElseThrow().getOrderKey();
        assertThat(cache.getHistory(key)).hasSize(2);
        assertThat(cache.getHistory(key).get(1)).contains("35=");
    }

    @Test
    void missingIdentityThrows() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        assertThatThrownBy(() -> cache.ingest(FixMessages.build("Q",
                Tags.DK_REASON, "A", Tags.SYMBOL, "X", Tags.SIDE, "1")))
                .isInstanceOf(UnidentifiableOrderException.class);
    }

    @Test
    void duplicateNewExecIdDoesNotReapplyQty() {
        InMemoryOrderCache cache = seeded();
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.CUM_QTY, "100",
                Tags.LEAVES_QTY, "0",
                Tags.AVG_PX, "10"
        ));
        ProcessResult replay = cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.CUM_QTY, "1",
                Tags.LEAVES_QTY, "99",
                Tags.AVG_PX, "99"
        ));
        assertThat(replay.applied()).isFalse();
        OrderState state = cache.getByOrderId("O9").orElseThrow();
        assertThat(state.getCumQty()).isEqualTo(100.0);
        assertThat(state.getLeavesQty()).isEqualTo(0.0);
        assertThat(state.getAvgPx()).isEqualTo(10.0);
    }

    @Test
    void bustExecStillAppliesRestatedQty() {
        InMemoryOrderCache cache = seeded();
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.CUM_QTY, "100",
                Tags.LEAVES_QTY, "0",
                Tags.AVG_PX, "10"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E3",
                Tags.EXEC_REF_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "1",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0",
                Tags.LEAVES_QTY, "100",
                Tags.AVG_PX, "0"
        ));
        OrderState state = cache.getByOrderId("O9").orElseThrow();
        assertThat(state.getCumQty()).isEqualTo(0.0);
        assertThat(state.getLeavesQty()).isEqualTo(100.0);
        assertThat(state.getExecTransType()).isEqualTo("1");
    }

    @Test
    void processMethodsMatchIngest() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        cache.processNewOrderSingle(com.fix42.oms.mapper.FixMessageMapper.toNewOrderSingle(
                new com.fix42.oms.fix.FixParser(com.fix42.oms.dict.FixDictionary.fix42())
                        .parse(FixMessages.d(
                                Tags.CL_ORD_ID, "C1",
                                Tags.SYMBOL, "IBM",
                                Tags.SIDE, "2",
                                Tags.ORDER_QTY, "5",
                                Tags.ORD_TYPE, "1"
                        ))));
        assertThat(cache.getByClOrdId("C1")).isPresent();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.snapshot()).hasSize(1);
    }

    private static InMemoryOrderCache seeded() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "ACC",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "100",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "10"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.LEAVES_QTY, "100",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0"
        ));
        return cache;
    }
}
