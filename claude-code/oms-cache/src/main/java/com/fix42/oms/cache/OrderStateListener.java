package com.fix42.oms.cache;

/**
 * Callback for latest-order-state changes — the integration point for publishing the
 * cache's derived state to an external system (an AMPS SOW topic, Kafka, a metrics
 * pipeline, ...).
 *
 * <p><b>Contract.</b> Invoked synchronously INSIDE the cache's write critical section,
 * after the state and every index are updated — so notifications arrive in exact
 * processing order, both per chain and across chains (a child's change is always
 * delivered before the parent roll-up it caused). The flip side: a slow handler stalls
 * ingest. Handlers must be fast — hand off to a bounded queue or an async publisher
 * (e.g. the AMPS client's async publish) rather than doing blocking I/O inline.
 *
 * <p><b>Reads are safe, writes are enforced-fatal.</b> The handler may call the cache's
 * query methods (they are lock-free and all mutations complete before notification), but
 * calling {@code process()} re-entrantly is rejected with {@link IllegalStateException}.
 *
 * <p><b>Errors never reach the {@code process()} caller.</b> A message's state change is
 * applied (and, under persistence, journaled) BEFORE notification; failing the caller at
 * that point would invite a retry, and re-applying a message corrupts the non-idempotent
 * fold. So {@link #onOrderStateChange} failures are routed to {@link #onListenerError}
 * and the notification batch continues — a distribution failure is surfaced, never
 * allowed to corrupt state or block recovery. Override {@code onListenerError} to alarm,
 * count, or mark chains dirty for republish; publishing derived latest state is an
 * idempotent last-value upsert, so re-sending a chain's current state later fully heals
 * a missed notification.
 *
 * <p>On recovery ({@code PersistenceConfig.announceRecoveredStates}), synthetic changes
 * with {@code cause == null} re-announce restored states — handlers must tolerate
 * {@link OrderStateChange#isRecoveryAnnouncement()} deliveries.
 */
@FunctionalInterface
public interface OrderStateListener {

    void onOrderStateChange(OrderStateChange change);

    /**
     * Invoked when {@link #onOrderStateChange} threw for {@code change}. Default: ignore.
     * Must not throw; anything it throws is swallowed to protect ingest and recovery.
     */
    default void onListenerError(OrderStateChange change, Throwable error) {
    }

    /** A listener that ignores every change. */
    OrderStateListener NOOP = change -> {
    };

    /**
     * Deliver {@code change} to {@code listener}, routing a handler failure to
     * {@link #onListenerError} instead of propagating it. {@link Error}s still propagate.
     */
    static void deliver(OrderStateListener listener, OrderStateChange change) {
        try {
            listener.onOrderStateChange(change);
        } catch (RuntimeException e) {
            try {
                listener.onListenerError(change, e);
            } catch (RuntimeException suppressed) {
                // The error channel itself failed; nothing further to do safely.
            }
        }
    }
}
