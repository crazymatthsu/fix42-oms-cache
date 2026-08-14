package com.fix42.oms.cache;

import com.fix42.oms.api.OmsCache;

import java.util.Optional;

public final class Recovery {
    private final InMemoryOrderCache cache;
    private final String sourceCursor;

    Recovery(InMemoryOrderCache cache, String sourceCursor) {
        this.cache = cache;
        this.sourceCursor = sourceCursor;
    }

    public OmsCache cache() {
        return cache;
    }

    public InMemoryOrderCache orderCache() {
        return cache;
    }

    public Optional<String> sourceCursor() {
        return Optional.ofNullable(sourceCursor);
    }
}
