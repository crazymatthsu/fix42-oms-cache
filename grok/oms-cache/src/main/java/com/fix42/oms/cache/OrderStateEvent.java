package com.fix42.oms.cache;

import com.fix42.oms.persist.SourceCursor;
import com.fix42.oms.proto.OrderState;

/**
 * Snapshot of one applied ingest, for {@link com.fix42.oms.api.OrderStateHandler}.
 */
public final class OrderStateEvent {
    private final ProcessResult result;
    private final String rawFix;
    private final SourceCursor cursor;

    public OrderStateEvent(ProcessResult result, String rawFix, SourceCursor cursor) {
        this.result = result;
        this.rawFix = rawFix;
        this.cursor = cursor;
    }

    public ProcessResult result() {
        return result;
    }

    public OrderState state() {
        return result == null ? null : result.state();
    }

    public String orderKey() {
        return result == null ? null : result.orderKey();
    }

    /** Previous {@code order_key} when the first broker {@code OrderID} rekeyed the row. */
    public String previousOrderKey() {
        return result == null ? null : result.previousOrderKey();
    }

    public boolean created() {
        return result != null && result.created();
    }

    public boolean applied() {
        return result != null && result.applied();
    }

    public String rawFix() {
        return rawFix;
    }

    public SourceCursor cursor() {
        return cursor;
    }
}
