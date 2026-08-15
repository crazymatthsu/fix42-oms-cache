package com.fix42.oms.parquet;

import java.time.LocalDate;

/**
 * The values a {@link PartitionScheme} renders into a directory path: the trading date
 * (in the archive's configured partition zone), the account (tag 1) and the symbol (tag 55).
 *
 * <p>{@code account}/{@code symbol} are the raw FIX values; sanitising them into safe path
 * segments is the scheme's job, so the key stays a faithful record of what was on the wire.
 * Absent values are normalised to {@code ""} here and rendered as the archive's
 * "unknown" label in the path.
 */
public record PartitionKey(LocalDate date, String account, String symbol) {

    public PartitionKey {
        if (date == null) {
            throw new IllegalArgumentException("Partition date must not be null");
        }
        account = (account == null) ? "" : account;
        symbol = (symbol == null) ? "" : symbol;
    }
}
