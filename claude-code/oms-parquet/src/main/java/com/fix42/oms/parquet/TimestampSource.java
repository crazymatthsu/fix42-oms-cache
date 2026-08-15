package com.fix42.oms.parquet;

/**
 * Which clock decides a raw message's {@code ts} column and therefore its trading-date
 * partition.
 *
 * <p>The distinction matters the moment a file is replayed: capturing a week-old drop-copy
 * file under {@link #ARRIVAL_CLOCK} files every message under today's date, which is right
 * for "when did we see it" and wrong for "what did the market do that day".
 * {@link #SENDING_TIME} and {@link #TRANSACT_TIME} read the date out of the message instead,
 * falling back to the arrival clock when the tag is absent or unparseable — so a partial
 * feed degrades to a filed message rather than a dropped one.
 *
 * <p>Order-state rows are unaffected: they always carry the cache's arrival time, which
 * under {@code :oms-persist} is the journaled arrival time and identical on replay.
 */
public enum TimestampSource {

    /** The archive's clock at capture time. Default. */
    ARRIVAL_CLOCK,

    /** SendingTime, tag 52, falling back to the arrival clock. */
    SENDING_TIME,

    /** TransactTime, tag 60, falling back to SendingTime and then the arrival clock. */
    TRANSACT_TIME
}
