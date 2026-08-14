package com.fix42.oms.cache;

import com.fix42.oms.proto.OrderState;

public final class ProcessResult {
    private final String orderKey;
    private final String previousOrderKey;
    private final OrderState state;
    private final boolean created;
    private final boolean applied;

    public ProcessResult(String orderKey, OrderState state, boolean created, boolean applied) {
        this(orderKey, state, created, applied, null);
    }

    public ProcessResult(
            String orderKey,
            OrderState state,
            boolean created,
            boolean applied,
            String previousOrderKey) {
        this.orderKey = orderKey;
        this.state = state;
        this.created = created;
        this.applied = applied;
        this.previousOrderKey = previousOrderKey;
    }

    public String orderKey() {
        return orderKey;
    }

    /**
     * Prior {@code order_key} when this apply rekeyed the row (typically
     * {@code ClOrdID} → broker {@code OrderID}). Null if the key did not change.
     */
    public String previousOrderKey() {
        return previousOrderKey;
    }

    public OrderState state() {
        return state;
    }

    public boolean created() {
        return created;
    }

    public boolean applied() {
        return applied;
    }
}
