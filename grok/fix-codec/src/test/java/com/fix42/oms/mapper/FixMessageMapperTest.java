package com.fix42.oms.mapper;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSerializer;
import com.fix42.oms.fix.FixTestMessages;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixMessageMapperTest {
    private final FixParser parser = FixParser.fix42();

    @Test
    void mapsNewOrderSingleIncludingExtraAndAllocs() {
        String raw = FixTestMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "ACC",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "100",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "10.25",
                Tags.PARENT_ORDER_ID, "P1",
                58, "note",
                99, "9.5",
                207, "XNAS",
                Tags.NO_ALLOCS, 1,
                Tags.ALLOC_ACCOUNT, "A1",
                Tags.ALLOC_SHARES, "100"
        );
        NewOrderSingle nos = FixMessageMapper.toNewOrderSingle(parser.parse(raw));
        assertThat(nos.getClOrdId()).isEqualTo("C1");
        assertThat(nos.getPrice()).isEqualTo(10.25);
        assertThat(nos.getParentOrderId()).isEqualTo("P1");
        assertThat(nos.getAllocsCount()).isEqualTo(1);
        assertThat(nos.getAllocs(0).getAllocAccount()).isEqualTo("A1");
        assertThat(nos.getExtraList()).anyMatch(f -> f.getTag() == 207 && f.getValue().equals("XNAS"));

        FixMessage back = FixMessageMapper.fromNewOrderSingle(nos);
        NewOrderSingle again = FixMessageMapper.toNewOrderSingle(parser.parse(FixSerializer.serialize(back)));
        assertThat(again.getClOrdId()).isEqualTo("C1");
        assertThat(again.getAllocs(0).getAllocShares()).isEqualTo(100.0);
        assertThat(again.getParentOrderId()).isEqualTo("P1");
    }

    @Test
    void mapsExecutionReportWithContraBrokers() {
        String raw = FixTestMessages.er(
                Tags.ORDER_ID, "O1",
                Tags.CL_ORD_ID, "C1",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.LAST_SHARES, "100",
                Tags.LAST_PX, "10",
                Tags.LEAVES_QTY, "0",
                Tags.CUM_QTY, "100",
                Tags.AVG_PX, "10",
                Tags.NO_CONTRA_BROKERS, 1,
                Tags.CONTRA_BROKER, "GS",
                Tags.CONTRA_TRADE_QTY, "100"
        );
        ExecutionReport er = FixMessageMapper.toExecutionReport(parser.parse(raw));
        assertThat(er.getExecType()).isEqualTo("2");
        assertThat(er.getLastShares()).isEqualTo(100.0);
        assertThat(er.getContraBrokersCount()).isEqualTo(1);
        assertThat(er.getContraBrokers(0).getContraBroker()).isEqualTo("GS");
    }

    @Test
    void mapsCancelReject() {
        String raw = FixTestMessages.build("9",
                Tags.ORDER_ID, "O1",
                Tags.CL_ORD_ID, "C2",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORD_STATUS, "0",
                Tags.CXL_REJ_RESPONSE_TO, "1",
                Tags.CXL_REJ_REASON, "0"
        );
        OrderCancelReject rej = FixMessageMapper.toOrderCancelReject(parser.parse(raw));
        assertThat(rej.getCxlRejResponseTo()).isEqualTo("1");
        assertThat(rej.getOrdStatus()).isEqualTo("0");
    }

    @Test
    void unknownMsgTypeIsRejected() {
        String raw = FixTestMessages.build("A", Tags.CL_ORD_ID, "C1");
        assertThatThrownBy(() -> FixMessageMapper.toTyped(parser.parse(raw)))
                .isInstanceOf(FixMessageMapper.UnsupportedMessageTypeException.class)
                .hasMessageContaining("A");
    }

    @Test
    void typedDispatchCoversAllInScopeTypes() {
        assertThat(FixMessageMapper.toTyped(parser.parse(FixTestMessages.build("F",
                Tags.CL_ORD_ID, "C2", Tags.ORIG_CL_ORD_ID, "C1", Tags.SYMBOL, "X", Tags.SIDE, "1"))))
                .isInstanceOf(com.fix42.oms.proto.OrderCancelRequest.class);
        assertThat(FixMessageMapper.toTyped(parser.parse(FixTestMessages.build("G",
                Tags.CL_ORD_ID, "C2", Tags.ORIG_CL_ORD_ID, "C1", Tags.SYMBOL, "X", Tags.SIDE, "1",
                Tags.ORD_TYPE, "2", Tags.ORDER_QTY, "10"))))
                .isInstanceOf(com.fix42.oms.proto.OrderCancelReplaceRequest.class);
        assertThat(FixMessageMapper.toTyped(parser.parse(FixTestMessages.build("H",
                Tags.CL_ORD_ID, "C1", Tags.SYMBOL, "X", Tags.SIDE, "1"))))
                .isInstanceOf(com.fix42.oms.proto.OrderStatusRequest.class);
        assertThat(FixMessageMapper.toTyped(parser.parse(FixTestMessages.build("Q",
                Tags.ORDER_ID, "O1", Tags.EXEC_ID, "E1", Tags.DK_REASON, "D", Tags.SYMBOL, "X", Tags.SIDE, "1"))))
                .isInstanceOf(com.fix42.oms.proto.DontKnowTrade.class);
    }
}
