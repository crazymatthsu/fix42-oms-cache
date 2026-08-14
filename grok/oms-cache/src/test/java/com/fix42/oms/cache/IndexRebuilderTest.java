package com.fix42.oms.cache;

import com.fix42.oms.fix.Tags;
import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRebuilderTest {

    @Test
    void rebuildsEveryIndexFromOrderStateAlone() {
        InMemoryOrderCache live = InMemoryOrderCache.create();
        live.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "P1",
                Tags.ACCOUNT, "PROP",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "1000",
                Tags.ORD_TYPE, "2"
        ));
        live.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "PROP",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "400",
                Tags.ORD_TYPE, "2",
                Tags.PARENT_ORDER_ID, "P1"
        ));
        live.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0",
                Tags.LEAVES_QTY, "400",
                Tags.AVG_PX, "0",
                Tags.PARENT_ORDER_ID, "P1"
        ));
        live.ingest(FixMessages.build("G",
                Tags.CL_ORD_ID, "C1b",
                Tags.ORIG_CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORD_TYPE, "2",
                Tags.ORDER_QTY, "300"
        ));

        List<OrderState> states = List.copyOf(live.snapshot());

        Map<String, String> clOrd = new HashMap<>();
        Map<String, String> orderId = new HashMap<>();
        Map<String, String> execId = new HashMap<>();
        Map<String, Set<String>> accounts = new LinkedHashMap<>();
        Map<String, Set<String>> symbols = new LinkedHashMap<>();
        Map<String, Set<String>> parentToChildren = new LinkedHashMap<>();
        Map<String, String> childToParent = new HashMap<>();
        IndexRebuilder.rebuild(
                states, clOrd, orderId, execId, accounts, symbols,
                new ParentLinkResolver(parentToChildren, childToParent));

        assertThat(clOrd.get("C1")).isEqualTo("B1");
        assertThat(clOrd.get("C1b")).isEqualTo("B1");
        assertThat(orderId.get("B1")).isEqualTo("B1");
        assertThat(execId.get("E1")).isEqualTo("B1");
        assertThat(accounts.get("PROP")).containsExactlyInAnyOrder("P1", "B1");
        assertThat(symbols.get("MSFT")).containsExactlyInAnyOrder("P1", "B1");
        assertThat(parentToChildren.get("P1")).contains("B1");
        assertThat(childToParent.get("B1")).isEqualTo("P1");
    }
}
