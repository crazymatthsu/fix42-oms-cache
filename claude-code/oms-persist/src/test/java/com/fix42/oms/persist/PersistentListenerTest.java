package com.fix42.oms.persist;

import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.OrderStateChange;
import com.fix42.oms.cache.OrderStateListener;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.proto.OrdStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentListenerTest {

    @TempDir
    Path dir;

    private PersistentOrderCache open(OrderStateListener listener, boolean announce) throws IOException {
        return PersistentOrderCache.open(dir, DefaultParentLinkResolver.create(),
                CacheConfig.defaults().withClock(Clock.systemUTC()),
                PersistenceConfig.defaults().withAnnounceRecoveredStates(announce),
                listener);
    }

    private static void feed(PersistentOrderCache cache, String... messages) {
        for (String m : messages) {
            cache.process(FixParser.pipe().parse(m));
        }
    }

    @Test
    void liveNotificationsFlowThroughPersistence() throws IOException {
        List<OrderStateChange> changes = new ArrayList<>();
        try (PersistentOrderCache cache = open(changes::add, false)) {
            feed(cache,
                    "35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|",
                    "35=8|11=ORD1|37=EX1|17=E1|20=0|150=2|39=2|32=100|31=50|151=0|14=100|6=50|");
        }
        assertEquals(2, changes.size());
        assertTrue(changes.get(0).isCreate());
        assertEquals(OrdStatus.ORD_STATUS_FILLED, changes.get(1).current().getOrdStatus());
    }

    @Test
    void recoveryReplayIsSilentByDefault() throws IOException {
        try (PersistentOrderCache cache = open(OrderStateListener.NOOP, false)) {
            feed(cache,
                    "35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|",
                    "35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=100|14=0|");
        }

        List<OrderStateChange> changes = new ArrayList<>();
        try (PersistentOrderCache recovered = open(changes::add, false)) {
            assertEquals(2, recovered.recoveredSequence());
            assertTrue(changes.isEmpty(), "replay must not re-fire history");
            // Live processing after recovery notifies again.
            feed(recovered, "35=8|11=ORD1|37=EX1|17=E2|20=0|150=2|39=2|32=100|31=50|151=0|14=100|6=50|");
            assertEquals(1, changes.size());
        }
    }

    @Test
    void announceRecoveredStatesFiresOncePerChainAfterReplay() throws IOException {
        try (PersistentOrderCache cache = open(OrderStateListener.NOOP, false)) {
            feed(cache,
                    "35=D|11=A|1=ACC|55=IBM|54=1|38=100|40=1|",
                    "35=D|11=B|1=ACC|55=MSFT|54=1|38=200|40=1|",
                    "35=8|11=A|37=XA|17=E1|20=0|150=0|39=0|151=100|14=0|");
        }

        List<OrderStateChange> changes = new ArrayList<>();
        try (PersistentOrderCache recovered = open(changes::add, true)) {
            assertEquals(2, changes.size(), "one announcement per chain, not per message");
            for (OrderStateChange c : changes) {
                assertTrue(c.isRecoveryAnnouncement());
                assertNull(c.previous());
                assertNull(c.cause());
            }
            assertEquals(2, recovered.size());
        }
    }

    @Test
    void listenerFailureNeverReachesTheCallerAndNeverFailStops() throws IOException {
        AtomicBoolean throwOnce = new AtomicBoolean(true);
        List<Throwable> errors = new ArrayList<>();
        OrderStateListener flaky = new OrderStateListener() {
            @Override
            public void onOrderStateChange(OrderStateChange change) {
                if (throwOnce.getAndSet(false)) {
                    throw new IllegalStateException("AMPS connection lost");
                }
            }

            @Override
            public void onListenerError(OrderStateChange change, Throwable error) {
                errors.add(error);
            }
        };
        try (PersistentOrderCache cache = open(flaky, false)) {
            // First message: listener throws — routed to the error channel; the caller's
            // process() succeeds (a surfaced failure would invite a corrupting retry).
            feed(cache, "35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|");
            assertEquals(1, errors.size());
            assertTrue(cache.getByClOrdId("ORD1").isPresent());

            // Second message: not fail-stopped, processes and notifies normally.
            feed(cache, "35=8|11=ORD1|37=EX1|17=E1|20=0|150=0|39=0|151=100|14=0|");
            assertEquals(OrdStatus.ORD_STATUS_NEW, cache.getByOrderId("EX1").orElseThrow().getOrdStatus());
        }

        // Both records journaled exactly once despite the listener failure.
        try (PersistentOrderCache recovered = open(OrderStateListener.NOOP, false)) {
            assertEquals(2, recovered.recoveredSequence());
            assertEquals(2, recovered.getByOrderId("EX1").orElseThrow().getMessageHistoryCount());
        }
    }

    @Test
    void recoveryOfHealthyStoreSucceedsEvenWhenAnnouncingListenerAlwaysThrows() throws IOException {
        try (PersistentOrderCache cache = open(OrderStateListener.NOOP, false)) {
            feed(cache, "35=D|11=ORD1|1=ACC|55=IBM|54=1|38=100|40=1|");
        }

        // The downstream publisher is "unreachable" (throws on every announce): recovery
        // of a healthy local store must still succeed — durability never depends on
        // distribution availability.
        List<Throwable> errors = new ArrayList<>();
        OrderStateListener down = new OrderStateListener() {
            @Override
            public void onOrderStateChange(OrderStateChange change) {
                throw new IllegalStateException("AMPS unreachable");
            }

            @Override
            public void onListenerError(OrderStateChange change, Throwable error) {
                errors.add(error);
            }
        };
        try (PersistentOrderCache recovered = open(down, true)) {
            assertEquals(1, recovered.recoveredSequence());
            assertEquals(1, recovered.size());
            assertEquals(1, errors.size(), "announce failure reported via the error channel");
        }
    }
}
