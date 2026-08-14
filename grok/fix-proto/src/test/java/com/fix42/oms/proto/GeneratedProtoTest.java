package com.fix42.oms.proto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedProtoTest {

    @Test
    void enumNumbersMatchFixAscii() {
        assertThat(Side.SIDE_BUY.getNumber()).isEqualTo('1');
        assertThat(Side.SIDE_SELL.getNumber()).isEqualTo('2');
        assertThat(OrdType.ORD_TYPE_LIMIT.getNumber()).isEqualTo('2');
        assertThat(TimeInForce.TIF_DAY.getNumber()).isEqualTo('0');
        assertThat(OrdStatus.ORD_STATUS_PENDING_NEW.getNumber()).isEqualTo('A');
        assertThat(OrdStatus.ORD_STATUS_PENDING_REPLACE.getNumber()).isEqualTo('E');
        assertThat(ExecType.EXEC_TYPE_PARTIAL_FILL.getNumber()).isEqualTo('1');
        assertThat(ExecTransType.EXEC_TRANS_TYPE_CANCEL.getNumber()).isEqualTo('1');
        assertThat(CxlRejResponseTo.CXL_REJ_RESPONSE_TO_REPLACE.getNumber()).isEqualTo('2');
        assertThat(DKReason.DK_REASON_NO_MATCHING_ORDER.getNumber()).isEqualTo('D');
        assertThat((char) Side.SIDE_BUY.getNumber()).isEqualTo('1');
    }

    @Test
    void orderStateBinaryRoundTrip() throws Exception {
        OrderState original = OrderState.newBuilder()
                .setOrderKey("O9")
                .setClOrdId("C2")
                .setOrigClOrdId("C1")
                .addClOrdIdHistory("C1")
                .addClOrdIdHistory("C2")
                .setOrderId("O9")
                .setAccount("ACC")
                .setSymbol("MSFT")
                .setSide("1")
                .setOrdStatus("1")
                .setOrderQty(400)
                .setCumQty(100)
                .setLeavesQty(300)
                .setParentOrderId("P1")
                .setVersion(3)
                .build();

        OrderState parsed = OrderState.parseFrom(original.toByteArray());
        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getClOrdIdHistoryList()).containsExactly("C1", "C2");
        assertThat(parsed.hasOrderQty()).isTrue();
        assertThat(parsed.getOrderQty()).isEqualTo(400.0);
    }

    @Test
    void executionReportOptionalPresence() throws Exception {
        ExecutionReport empty = ExecutionReport.getDefaultInstance();
        assertThat(empty.hasPrice()).isFalse();
        assertThat(empty.hasClOrdId()).isFalse();

        ExecutionReport filled = ExecutionReport.newBuilder()
                .setClOrdId("C1")
                .setOrderId("O1")
                .setExecId("E1")
                .setPrice(420.5)
                .addContraBrokers(ContraBroker.newBuilder().setContraBroker("GS").setContraTradeQty(10))
                .build();
        assertThat(filled.hasPrice()).isTrue();
        assertThat(filled.getContraBrokersCount()).isEqualTo(1);
        assertThat(ExecutionReport.parseFrom(filled.toByteArray())).isEqualTo(filled);
    }

    @Test
    void persistSnapshotRoundTrip() throws Exception {
        Snapshot snapshot = Snapshot.newBuilder()
                .setUpToSeq(9)
                .setSourceCursor("off=9")
                .addOrders(PersistedOrder.newBuilder()
                        .setState(OrderState.newBuilder().setOrderKey("O1").setClOrdId("C1"))
                        .addHistory("8=FIX.4.2"))
                .build();
        assertThat(Snapshot.parseFrom(snapshot.toByteArray())).isEqualTo(snapshot);
        WalRecord wal = WalRecord.newBuilder().setSeq(9).setRawFix("35=8").setMsgType("8").build();
        assertThat(WalRecord.parseFrom(wal.toByteArray()).getSeq()).isEqualTo(9);
    }

    @Test
    void genericFixMessageRoundTrip() throws Exception {
        FixMessage message = FixMessage.newBuilder()
                .setHeader(FixHeader.newBuilder().setBeginString("FIX.4.2").setMsgType("D").setMsgSeqNum(7))
                .addFields(FixField.newBuilder().setTag(11).setValue("C1"))
                .addGroups(FixGroup.newBuilder()
                        .setNumInGroupTag(78)
                        .addInstances(FixGroupInstance.newBuilder()
                                .addFields(FixField.newBuilder().setTag(79).setValue("ACC1"))))
                .setTrailer(FixTrailer.newBuilder().setCheckSum("000"))
                .build();
        assertThat(FixMessage.parseFrom(message.toByteArray())).isEqualTo(message);
    }
}
