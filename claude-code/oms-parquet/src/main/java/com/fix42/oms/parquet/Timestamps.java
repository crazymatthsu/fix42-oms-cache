package com.fix42.oms.parquet;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Time conversions for the Parquet columns.
 *
 * <p>Every {@code TIMESTAMP} column is written as <b>UTC rendered without a zone</b>, the
 * one convention every Parquet reader in the estate agrees on. The trading date, by
 * contrast, is computed in the archive's configured {@code partitionZone} — so a
 * {@code ts} of {@code 2026-08-14T21:30Z} lands in the {@code 2026/08/14} partition under
 * {@code America/New_York} and {@code 2026/08/14} under UTC too, while
 * {@code 2026-08-15T01:00Z} lands in {@code 2026/08/14} under New York and
 * {@code 2026/08/15} under UTC. That divergence is intentional and is why both the
 * timestamp and the date are stored.
 */
final class Timestamps {

    private Timestamps() {
    }

    /** Epoch millis as a zone-less UTC timestamp for the Parquet {@code TIMESTAMP} columns. */
    static LocalDateTime utc(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    /** As {@link #utc(long)}, but {@code null} for a protobuf-default (unset) 0 timestamp. */
    static LocalDateTime utcOrNull(long epochMillis) {
        return epochMillis == 0L ? null : utc(epochMillis);
    }

    /** The trading date {@code epochMillis} falls on, in {@code zone}. */
    static LocalDate tradingDate(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    /**
     * Parse a FIX UTCTimestamp — {@code YYYYMMDD-HH:MM:SS} with an optional fractional part
     * of any length ({@code .sss}, {@code .ssssss}, ...) — to epoch millis.
     *
     * <p>Hand-rolled rather than {@link java.time.format.DateTimeFormatter} because this runs
     * on every captured message and the format is fixed-width. Returns {@code null} for
     * anything that does not match exactly, so a malformed tag falls back to the arrival
     * clock instead of failing the capture.
     */
    static Long parseFixUtcTimestamp(String value) {
        if (value == null || value.length() < 17 || value.charAt(8) != '-'
                || value.charAt(11) != ':' || value.charAt(14) != ':') {
            return null;
        }
        try {
            int year = digits(value, 0, 4);
            int month = digits(value, 4, 6);
            int day = digits(value, 6, 8);
            int hour = digits(value, 9, 11);
            int minute = digits(value, 12, 14);
            int second = digits(value, 15, 17);

            int millis = 0;
            if (value.length() > 17) {
                if (value.charAt(17) != '.' || value.length() == 18) {
                    return null; // no separator, or a separator with no digits after it
                }
                // Take up to 3 fractional digits (millisecond precision), pad if shorter.
                int end = Math.min(value.length(), 21);
                int scale = 100;
                for (int i = 18; i < end; i++) {
                    char ch = value.charAt(i);
                    if (ch < '0' || ch > '9') {
                        return null;
                    }
                    millis += (ch - '0') * scale;
                    scale /= 10;
                }
                for (int i = 21; i < value.length(); i++) {
                    char ch = value.charAt(i);
                    if (ch < '0' || ch > '9') {
                        return null;
                    }
                }
            }
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC).toEpochMilli() + millis;
        } catch (RuntimeException e) {
            return null; // out-of-range field, non-digit character, ...
        }
    }

    private static int digits(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new NumberFormatException("Not a digit at " + i + " in " + s);
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }
}
