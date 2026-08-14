package com.fix42.oms.api;

import com.fix42.oms.cache.OrderStateEvent;
import com.fix42.oms.proto.OrderState;

import java.util.Collection;

/**
 * Downstream hook after the in-memory book changes. Implement this to publish
 * latest {@link OrderState} to AMPS SOW, Kafka, a blotter, etc.
 *
 * <p>This is not the durability store ({@code StateStore}). If the handler
 * throws and {@code handlerFailsIngest} is true, {@code ingest} fails so the
 * caller can withhold the drop-copy ack.
 */
@FunctionalInterface
public interface OrderStateHandler {
    void onOrderUpdated(OrderStateEvent event);

    /**
     * Called once after a file-store recovery or {@code hydrate}, with the
     * full book. Default is a no-op; an AMPS publisher may reconcile SOW.
     */
    default void onRecovered(Collection<OrderState> snapshot) {}
}
