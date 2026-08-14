package com.fix42.oms.persist;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Clock} whose time is set explicitly by the persistence layer.
 *
 * <p>{@link PersistentOrderCache} wires this into the inner cache's {@code CacheConfig}
 * so the state machine reads the SAME millisecond value that is recorded in the
 * {@code JournalRecord}: on the live path it is set to wall-clock time just before each
 * message is applied; on replay it is set from the record. This makes the recovered
 * {@code OrderState}s equal the pre-crash state ({@code OrderState.equals()},
 * timestamps included).
 */
final class SettableClock extends Clock {

    private final AtomicLong millis = new AtomicLong();

    void set(long epochMillis) {
        millis.set(epochMillis);
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this; // zone is irrelevant for millis-based reads
    }
}
