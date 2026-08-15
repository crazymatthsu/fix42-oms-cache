package com.fix42.oms.parquet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the tests advance by hand, so capture timestamps are deterministic but not equal. */
final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    TestClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** Move time forward and return the new reading. */
    Instant advance(java.time.Duration by) {
        instant = instant.plus(by);
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new TestClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
