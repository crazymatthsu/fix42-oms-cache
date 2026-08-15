package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimestampsTest {

    @Test
    void secondPrecisionUtcTimestampParses() {
        Long millis = Timestamps.parseFixUtcTimestamp("20260804-13:30:00");
        assertEquals(Instant.parse("2026-08-04T13:30:00Z").toEpochMilli(), millis);
    }

    @Test
    void millisecondPrecisionUtcTimestampParses() {
        Long millis = Timestamps.parseFixUtcTimestamp("20260804-13:30:00.250");
        assertEquals(Instant.parse("2026-08-04T13:30:00.250Z").toEpochMilli(), millis);
    }

    @Test
    void subMillisecondPrecisionTruncatesToMillis() {
        // FIX 4.4+ feeds and some venues send micros; the archive keeps millisecond
        // resolution rather than rejecting the field.
        assertEquals(Instant.parse("2026-08-04T13:30:00.250Z").toEpochMilli(),
                Timestamps.parseFixUtcTimestamp("20260804-13:30:00.250999"));
    }

    @Test
    void shortFractionsArePaddedNotMisread() {
        assertEquals(Instant.parse("2026-08-04T13:30:00.200Z").toEpochMilli(),
                Timestamps.parseFixUtcTimestamp("20260804-13:30:00.2"));
        assertEquals(Instant.parse("2026-08-04T13:30:00.250Z").toEpochMilli(),
                Timestamps.parseFixUtcTimestamp("20260804-13:30:00.25"));
    }

    @Test
    void malformedTimestampsReturnNullSoCaptureFallsBackToTheClock() {
        assertNull(Timestamps.parseFixUtcTimestamp(null));
        assertNull(Timestamps.parseFixUtcTimestamp(""));
        assertNull(Timestamps.parseFixUtcTimestamp("20260804"));
        assertNull(Timestamps.parseFixUtcTimestamp("2026-08-04T13:30:00Z"));
        assertNull(Timestamps.parseFixUtcTimestamp("20260804-13:30:00."));
        assertNull(Timestamps.parseFixUtcTimestamp("2026080X-13:30:00"));
        assertNull(Timestamps.parseFixUtcTimestamp("20261304-13:30:00")); // month 13
    }

    @Test
    void timestampColumnsAreUtc() {
        long millis = Instant.parse("2026-08-04T21:30:00Z").toEpochMilli();
        assertEquals(LocalDateTime.of(2026, 8, 4, 21, 30, 0), Timestamps.utc(millis));
    }

    @Test
    void unsetProtobufTimestampsBecomeNull() {
        assertNull(Timestamps.utcOrNull(0L));
        assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0, 0, 1_000_000), Timestamps.utcOrNull(1L));
    }

    @Test
    void tradingDateFollowsThePartitionZoneNotUtc() {
        // 01:00 UTC is still the previous trading day in New York — the reason the trading
        // zone is configurable rather than assumed.
        long millis = Instant.parse("2026-08-05T01:00:00Z").toEpochMilli();
        assertEquals(LocalDate.of(2026, 8, 5), Timestamps.tradingDate(millis, ZoneId.of("UTC")));
        assertEquals(LocalDate.of(2026, 8, 4), Timestamps.tradingDate(millis, ZoneId.of("America/New_York")));
    }
}
