package com.fix42.oms.cache;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrdStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStateListenerTest {

    private static FixMessage m(String pipe) {
        return FixParser.pipe().parse(pipe);
    }

    private static InMemoryOrderCache cacheWith(OrderStateListener listener) {
        return new InMemoryOrderCache(DefaultParentLinkResolver.create(), CacheConfig.defaults(), listener);
    }

    @Test
    void createAndUpdateNotificationsCarryPreviousAndCurrent() {
        List<OrderStateChange> changes = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(changes::add);

        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.5|"));
        cache.process(m("35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=1000|14=0|"));

        assertEquals(2, changes.size());

        OrderStateChange create = changes.get(0);
        assertTrue(create.isCreate());
        assertNull(create.previous());
        assertEquals(OrdStatus.ORD_STATUS_PENDING_NEW, create.current().getOrdStatus());
        assertFalse(create.parentRollUp());
        assertEquals("D", com.fix42.oms.fix.FixSupport.msgType(create.cause()));

        OrderStateChange update = changes.get(1);
        assertEquals(OrdStatus.ORD_STATUS_PENDING_NEW, update.previous().getOrdStatus());
        assertEquals(OrdStatus.ORD_STATUS_NEW, update.current().getOrdStatus());
        assertEquals("EX1", update.current().getOrderId());
    }

    @Test
    void oneNotificationPerTouchedChainAndParentRollUpIsFlagged() {
        List<OrderStateChange> changes = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(changes::add);

        // Parent exists first.
        cache.process(m("35=D|11=PAR|1=DESK|55=AAPL|54=1|38=500|40=1|"));
        cache.process(m("35=8|11=PAR|37=PAR|17=P1|20=0|150=0|39=0|151=500|14=0|"));
        changes.clear();

        // A child's fill must produce exactly two notifications: the child chain
        // (parentRollUp=false) and the refreshed parent (parentRollUp=true), in order.
        cache.process(m("35=D|11=CH1|526=PAR|1=DESK|55=AAPL|54=1|38=300|40=1|"));
        cache.process(m("35=8|11=CH1|526=PAR|37=CHILD1|17=C1|20=0|150=2|39=2|32=300|31=190.10|151=0|14=300|6=190.10|"));

        List<OrderStateChange> forFill = changes.subList(changes.size() - 2, changes.size());
        OrderStateChange child = forFill.get(0);
        OrderStateChange parent = forFill.get(1);

        assertFalse(child.parentRollUp());
        assertEquals("CHILD1", child.current().getOrderId());
        assertTrue(parent.parentRollUp());
        assertEquals("PAR", parent.current().getOrderId());
        assertEquals(300.0, parent.current().getCumQty());
        // Same triggering message on both notifications.
        assertEquals(child.cause(), parent.cause());
    }

    @Test
    void listenerFailureRoutesToErrorChannelAndNeverReachesTheCaller() {
        // A failure surfaced to the caller would invite a retry, and re-applying a
        // message corrupts the non-idempotent fold — so process() must succeed.
        List<Throwable> errors = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(new OrderStateListener() {
            @Override
            public void onOrderStateChange(OrderStateChange change) {
                throw new IllegalStateException("publisher down");
            }

            @Override
            public void onListenerError(OrderStateChange change, Throwable error) {
                errors.add(error);
            }
        });

        cache.process(m("35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|")); // must not throw

        assertEquals(1, errors.size());
        assertEquals("publisher down", errors.get(0).getMessage());
        assertTrue(cache.getByClOrdId("ORD1").isPresent());
        assertEquals(1, cache.findByAccount("ACC").size());
    }

    @Test
    void failingChildNotificationStillDeliversTheParentRollUp() {
        // Per-notification isolation: a throw on the child's change must not strand the
        // parent aggregate un-published (a last-value store would hold stale-parent forever).
        List<String> delivered = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(new OrderStateListener() {
            @Override
            public void onOrderStateChange(OrderStateChange change) {
                if (!change.parentRollUp()) {
                    throw new IllegalStateException("child publish failed");
                }
                delivered.add(change.current().getOrderId());
            }
        });

        cache.process(m("35=D|11=PAR|1=DESK|55=AAPL|54=1|38=500|40=1|"));
        cache.process(m("35=8|11=PAR|37=PAR|17=P1|20=0|150=0|39=0|151=500|14=0|"));
        cache.process(m("35=D|11=CH1|526=PAR|1=DESK|55=AAPL|54=1|38=300|40=1|"));
        cache.process(m("35=8|11=CH1|526=PAR|37=CHILD1|17=C1|20=0|150=2|39=2|32=300|31=190.10|151=0|14=300|6=190.10|"));

        assertTrue(delivered.contains("PAR"), "parent roll-up must be delivered despite child failure");
    }

    @Test
    void reentrantProcessFromListenerIsRejectedViaErrorChannel() {
        List<Throwable> errors = new ArrayList<>();
        InMemoryOrderCache[] holder = new InMemoryOrderCache[1];
        holder[0] = cacheWith(new OrderStateListener() {
            @Override
            public void onOrderStateChange(OrderStateChange change) {
                if (change.current().getClOrdId().equals("A")) {
                    holder[0].process(m("35=D|11=EVIL|1=X|55=IBM|54=1|38=1|40=1|"));
                }
            }

            @Override
            public void onListenerError(OrderStateChange change, Throwable error) {
                errors.add(error);
            }
        });

        holder[0].process(m("35=D|11=A|1=X|55=IBM|54=1|38=1|40=1|")); // outer call succeeds

        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof IllegalStateException);
        assertTrue(holder[0].getByClOrdId("EVIL").isEmpty(), "reentrant mutation must not be applied");
        assertTrue(holder[0].getByClOrdId("A").isPresent());
    }

    @Test
    void noOpParentRefreshDoesNotNotify() {
        List<OrderStateChange> changes = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(changes::add);

        cache.process(m("35=D|11=PAR|1=DESK|55=AAPL|54=1|38=500|40=1|"));
        cache.process(m("35=D|11=CH1|526=PAR|1=DESK|55=AAPL|54=1|38=300|40=1|"));
        cache.process(m("35=8|11=CH1|526=PAR|37=CHILD1|17=C1|20=0|150=2|39=2|32=300|31=190.10|151=0|14=300|6=190.10|"));
        changes.clear();

        // A status request against the child changes the child chain but leaves the
        // parent aggregate identical: exactly one notification, no spurious roll-up.
        cache.process(m("35=H|11=CH1|526=PAR|37=CHILD1|55=AAPL|54=1|"));

        assertEquals(1, changes.size());
        assertFalse(changes.get(0).parentRollUp());
        assertEquals("CHILD1", changes.get(0).current().getOrderId());
    }

    @Test
    void notificationsArriveInProcessingOrder() {
        List<String> order = new ArrayList<>();
        InMemoryOrderCache cache = cacheWith(c -> order.add(c.current().getClOrdId()));

        cache.process(m("35=D|11=A|1=X|55=IBM|54=1|38=1|40=1|"));
        cache.process(m("35=D|11=B|1=X|55=IBM|54=1|38=1|40=1|"));
        cache.process(m("35=D|11=C|1=X|55=IBM|54=1|38=1|40=1|"));

        assertEquals(List.of("A", "B", "C"), order);
    }

    @Test
    void listenerMayQueryTheCacheReentrantly() {
        List<Integer> sizesSeen = new ArrayList<>();
        InMemoryOrderCache[] holder = new InMemoryOrderCache[1];
        holder[0] = cacheWith(c -> sizesSeen.add(holder[0].size())); // lock-free read from inside the callback

        holder[0].process(m("35=D|11=A|1=X|55=IBM|54=1|38=1|40=1|"));
        holder[0].process(m("35=D|11=B|1=X|55=IBM|54=1|38=1|40=1|"));

        assertEquals(List.of(1, 2), sizesSeen);
    }
}
