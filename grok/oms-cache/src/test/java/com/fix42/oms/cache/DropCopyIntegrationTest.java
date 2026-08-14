package com.fix42.oms.cache;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSerializer;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.mapper.FixMessageMapper;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end drop-copy tape: parent + two children, replace, reject, fill, DK.
 */
class DropCopyIntegrationTest {

    @Test
    void processesFullAuditTape() {
        InMemoryOrderCache cache = InMemoryOrderCache.create();

        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "P1",
                Tags.ACCOUNT, "PROP",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "1000",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "420"
        ));

        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "PROP",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "400",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "420",
                Tags.PARENT_ORDER_ID, "P1",
                Tags.PARENT_CL_ORD_ID, "P1"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.LEAVES_QTY, "400",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0",
                Tags.PARENT_ORDER_ID, "P1"
        ));

        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C2",
                Tags.ACCOUNT, "PROP",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "600",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "420",
                Tags.PARENT_ORDER_ID, "P1"
        ));
        String fill = FixMessages.er(
                Tags.CL_ORD_ID, "C2",
                Tags.ORDER_ID, "B2",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.LAST_SHARES, "600",
                Tags.LAST_PX, "420",
                Tags.LEAVES_QTY, "0",
                Tags.CUM_QTY, "600",
                Tags.AVG_PX, "420",
                Tags.PARENT_ORDER_ID, "P1"
        );
        cache.ingest(fill);

        cache.ingest(FixMessages.build("G",
                Tags.CL_ORD_ID, "C1b",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORD_TYPE, "2",
                Tags.ORDER_QTY, "300",
                Tags.PRICE, "421"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1b",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.EXEC_ID, "E3",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "5",
                Tags.ORD_STATUS, "5",
                Tags.ORDER_QTY, "300",
                Tags.LEAVES_QTY, "300",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0"
        ));

        cache.ingest(FixMessages.build("F",
                Tags.CL_ORD_ID, "C1c",
                Tags.ORIG_CL_ORD_ID, "C1b",
                Tags.ORDER_ID, "B1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1"
        ));
        cache.ingest(FixMessages.build("9",
                Tags.CL_ORD_ID, "C1c",
                Tags.ORIG_CL_ORD_ID, "C1b",
                Tags.ORDER_ID, "B1",
                Tags.ORD_STATUS, "5",
                Tags.CXL_REJ_RESPONSE_TO, "1",
                Tags.CXL_REJ_REASON, "0"
        ));

        cache.ingest(FixMessages.build("Q",
                Tags.ORDER_ID, "B2",
                Tags.EXEC_ID, "E2",
                Tags.DK_REASON, "E",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1"
        ));

        OrderState child1 = cache.getByClOrdId("C1").orElseThrow();
        assertThat(child1.getOrderKey()).isEqualTo("B1");
        assertThat(cache.getByClOrdId("C1b").orElseThrow().getOrderKey()).isEqualTo("B1");
        assertThat(cache.getByOrderId("B1").orElseThrow().getClOrdId()).isEqualTo("C1c");
        assertThat(child1.getOrderQty()).isEqualTo(300.0);
        assertThat(cache.getByOrderId("B1").orElseThrow().getPendingCancel()).isFalse();
        assertThat(cache.getByOrderId("B1").orElseThrow().getOrdStatus()).isEqualTo("5");

        OrderState child2 = cache.getByOrderId("B2").orElseThrow();
        assertThat(child2.getCumQty()).isEqualTo(600.0);
        assertThat(child2.getDkTrade()).isTrue();
        assertThat(cache.getByExecId("E2").orElseThrow().getOrderKey()).isEqualTo("B2");

        List<OrderState> children = cache.getChildren("P1");
        assertThat(children).hasSize(2);
        ChildRollup rollup = cache.rollup("P1");
        assertThat(rollup.cumQty()).isEqualTo(600.0);
        assertThat(rollup.leavesQty()).isEqualTo(300.0);

        assertThat(cache.findByAccount("PROP")).hasSize(3);
        assertThat(cache.findBySymbol("MSFT")).hasSize(3);

        List<String> history = cache.getHistory("B1");
        assertThat(history.size()).isGreaterThanOrEqualTo(4);

        ExecutionReport lastFill = FixMessageMapper.toExecutionReport(FixParser.fix42().parse(fill));
        String rewritten = FixSerializer.serialize(FixMessageMapper.fromExecutionReport(lastFill));
        ExecutionReport again = FixMessageMapper.toExecutionReport(FixParser.fix42().parse(rewritten));
        assertThat(again.getExecId()).isEqualTo("E2");
        assertThat(again.getCumQty()).isEqualTo(600.0);
        assertThat(again.getParentOrderId()).isEqualTo("P1");
    }
}
