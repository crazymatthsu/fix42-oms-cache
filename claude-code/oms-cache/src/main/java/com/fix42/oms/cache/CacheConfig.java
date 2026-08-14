package com.fix42.oms.cache;

import java.time.Clock;

/**
 * Configuration for {@link InMemoryOrderCache}.
 *
 * @param historyCap   max messages retained per order in {@code message_history};
 *                     {@code 0} means unbounded (keep the full audit trail)
 * @param rollUpParents whether to aggregate child fills into the parent OrderState
 * @param clock        time source for {@code first_seen}/{@code last_update} timestamps
 *                     (inject a fixed clock in tests for determinism)
 */
public record CacheConfig(int historyCap, boolean rollUpParents, Clock clock) {

    public CacheConfig {
        if (historyCap < 0) {
            throw new IllegalArgumentException("historyCap must be >= 0");
        }
        if (clock == null) {
            clock = Clock.systemUTC();
        }
    }

    /** Defaults: unbounded history, parent roll-up on, system UTC clock. */
    public static CacheConfig defaults() {
        return new CacheConfig(0, true, Clock.systemUTC());
    }

    public CacheConfig withHistoryCap(int cap) {
        return new CacheConfig(cap, rollUpParents, clock);
    }

    public CacheConfig withClock(Clock clock) {
        return new CacheConfig(historyCap, rollUpParents, clock);
    }

    public CacheConfig withRollUpParents(boolean on) {
        return new CacheConfig(historyCap, on, clock);
    }
}
