package com.fix42.oms.cache;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryOrderCacheTest {

    private final InMemoryOrderCache cache = new InMemoryOrderCache();

    private static FixMessage m(String pipe) {
        return FixParser.pipe().parse(pipe);
    }

    @Test
    void resolvesOneChainAcrossReplaceAndAllAliases() {
        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|"));
        cache.process(m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|"));
        cache.process(m("35=G|11=R1|41=ORD1|37=EX1|54=1|38=1200|40=2|44=185.5|"));
        cache.process(m("35=8|11=R1|41=ORD1|37=EX1|17=E2|20=0|150=5|39=1|38=1200|151=800|14=400|6=185.5|"));

        assertEquals(1, cache.size());
        String orderId = cache.getByOrderId("EX1").orElseThrow().getOrderId();
        assertEquals(orderId, cache.getByClOrdId("ORD1").orElseThrow().getOrderId());
        assertEquals(orderId, cache.getByClOrdId("R1").orElseThrow().getOrderId());
        assertEquals(orderId, cache.getByExecId("E2").orElseThrow().getOrderId());
        assertEquals(OrdStatus.ORD_STATUS_PARTIALLY_FILLED, cache.getByClOrdId("R1").orElseThrow().getOrdStatus());
    }

    @Test
    void findsPendingOrderByClOrdIdAccountAndSymbolBeforeAnyExecutionReport() {
        // Only a NewOrderSingle — no OrderID assigned yet.
        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|"));

        assertTrue(cache.getByClOrdId("ORD1").isPresent());
        assertEquals(1, cache.findByAccount("ACC").size());
        assertEquals(1, cache.findBySymbol("IBM").size());
        assertTrue(cache.getByOrderId("ORD1").isEmpty()); // OrderID space, not assigned
    }

    @Test
    void searchByAccountAndSymbolGroupsMultipleOrders() {
        cache.process(m("35=D|11=A1|1=ACC|55=IBM|54=1|38=100|40=1|"));
        cache.process(m("35=D|11=A2|1=ACC|55=IBM|54=1|38=200|40=1|"));
        cache.process(m("35=D|11=B1|1=ACC|55=MSFT|54=1|38=300|40=1|"));

        assertEquals(3, cache.findByAccount("ACC").size());
        assertEquals(2, cache.findBySymbol("IBM").size());
        assertEquals(1, cache.findBySymbol("MSFT").size());
    }

    @Test
    void parentChildRollupWhenChildrenArriveFirst() {
        // Children (and their fills) arrive before the parent is seen.
        cache.process(m("35=D|11=CH1|526=PARENT|1=DESK|55=AAPL|54=1|38=300|40=1|"));
        cache.process(m("35=8|11=CH1|526=PARENT|37=CHILD1|17=C1|20=0|150=2|39=2|32=300|31=190.10|151=0|14=300|6=190.10|"));
        cache.process(m("35=D|11=CH2|526=PARENT|1=DESK|55=AAPL|54=1|38=200|40=1|"));
        cache.process(m("35=8|11=CH2|526=PARENT|37=CHILD2|17=C2|20=0|150=2|39=2|32=200|31=190.20|151=0|14=200|6=190.20|"));

        // Parent appears last.
        cache.process(m("35=D|11=PARENT|1=DESK|55=AAPL|54=1|38=500|40=1|"));
        cache.process(m("35=8|11=PARENT|37=PARENT|17=P1|20=0|150=0|39=0|151=500|14=0|"));

        OrderState parent = cache.getByOrderId("PARENT").orElseThrow();
        assertTrue(parent.getIsParent());
        assertEquals(500.0, parent.getCumQty());
        assertEquals(190.14, parent.getAvgPx(), 1e-9);

        List<OrderState> children = cache.getChildren("PARENT");
        assertEquals(2, children.size());
        assertEquals("PARENT", cache.getParent("CHILD1").orElseThrow().getOrderId());
        assertEquals("PARENT", cache.getParent("CHILD2").orElseThrow().getOrderId());
    }

    @Test
    void cancelRejectRevertsWorkingOrder() {
        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|"));
        cache.process(m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|"));
        cache.process(m("35=F|11=C1|41=ORD1|37=EX1|54=1|38=1000|"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_CANCEL, cache.getByOrderId("EX1").orElseThrow().getOrdStatus());
        // Broker rejects the cancel -> back to NEW.
        cache.process(m("35=9|11=C1|41=ORD1|37=EX1|39=0|434=1|102=0|58=too late|"));
        assertEquals(OrdStatus.ORD_STATUS_NEW, cache.getByOrderId("EX1").orElseThrow().getOrdStatus());
    }

    @Test
    void exportThenRestoreSnapshotReproducesStatesAndAllIndexes() {
        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|"));
        cache.process(m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|"));
        cache.process(m("35=G|11=R1|41=ORD1|37=EX1|54=1|38=1200|40=2|44=185.5|"));

        var snapshot = cache.exportSnapshot(42);
        assertEquals(42, snapshot.getLastAppliedSequence());

        InMemoryOrderCache restored = InMemoryOrderCache.fromSnapshot(
                snapshot, com.fix42.oms.model.DefaultParentLinkResolver.create(),
                CacheConfig.defaults());

        assertEquals(cache.size(), restored.size());
        assertEquals(cache.getByOrderId("EX1").orElseThrow(), restored.getByOrderId("EX1").orElseThrow());
        // Every alias index survives, including the replace-chain ClOrdIDs.
        assertTrue(restored.getByClOrdId("ORD1").isPresent());
        assertTrue(restored.getByClOrdId("R1").isPresent());
        assertTrue(restored.getByExecId("E1").isPresent());
        assertEquals(1, restored.findByAccount("ACC").size());
        assertEquals(1, restored.findBySymbol("IBM").size());
        // Restored cache keeps working: confirm the replace on it.
        restored.process(m("35=8|11=R1|41=ORD1|37=EX1|17=E2|20=0|150=5|39=0|38=1200|151=1200|14=0|"));
        assertEquals(1200.0, restored.getByOrderId("EX1").orElseThrow().getOrderQty());
    }

    @Test
    void unknownLookupsReturnEmpty() {
        assertTrue(cache.getByOrderId("nope").isEmpty());
        assertTrue(cache.getByClOrdId("nope").isEmpty());
        assertTrue(cache.getByExecId("nope").isEmpty());
        assertTrue(cache.findByAccount("nope").isEmpty());
        assertTrue(cache.getParent("nope").isEmpty());
        assertTrue(cache.getChildren("nope").isEmpty());
        assertEquals(0, cache.size());
    }
}
