package com.fix42.oms.persist;

import java.util.Objects;

/**
 * Opaque resume token for the drop-copy source (file offset, queue offset, session seq).
 * The cache stores it; it never interprets it.
 */
public final class SourceCursor {
    private final String value;

    private SourceCursor(String value) {
        this.value = Objects.requireNonNull(value, "cursor");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("cursor must not be empty");
        }
    }

    public static SourceCursor of(String value) {
        return new SourceCursor(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceCursor other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
