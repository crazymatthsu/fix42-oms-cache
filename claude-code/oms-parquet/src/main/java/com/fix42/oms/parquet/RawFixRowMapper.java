package com.fix42.oms.parquet;

import com.fix42.oms.fix.FixSerializer;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.FixMessage;

import java.time.LocalDate;

/**
 * Turns a captured {@link FixMessage} into a {@link Dataset#RAW_FIX_MESSAGES} row.
 *
 * <p>Lifted tag values are kept exactly as they arrived — no enum decoding, no numeric
 * normalisation — because this dataset is the audit copy. An absent or blank tag becomes
 * SQL NULL rather than {@code ""}: NULL is what a query engine's {@code IS NULL} and
 * min/max statistics understand, and it keeps "the venue omitted LastPx" distinguishable
 * from "the venue sent an empty LastPx".
 *
 * <p>Stateless and thread-safe.
 */
final class RawFixRowMapper {

    private final String writerId;
    private final boolean captureRawText;
    private final FixSerializer serializer;

    RawFixRowMapper(ParquetArchiveConfig config) {
        this.writerId = config.writerId();
        this.captureRawText = config.captureRawFixText();
        this.serializer = FixSerializer.withDelimiter(config.rawFixDelimiter());
    }

    /**
     * @param message   the parsed message
     * @param rawText   the original wire text if the caller still has it; when {@code null}
     *                  the message is re-serialised verbatim (field order preserved, no
     *                  recomputation of BodyLength/CheckSum) using the configured delimiter
     * @param tsMillis  the resolved capture timestamp
     * @param eventDate the trading date {@code tsMillis} falls on, in the partition zone
     * @param ingestSeq this writer's monotonic sequence for the row
     */
    Object[] toRow(FixMessage message, String rawText, long tsMillis, LocalDate eventDate, long ingestSeq) {
        Object[] r = new Object[Dataset.RAW_FIX_MESSAGES.columnCount()];
        int i = 0;

        r[i++] = eventDate;
        r[i++] = Timestamps.utc(tsMillis);
        r[i++] = writerId;
        r[i++] = ingestSeq;

        r[i++] = str(message, Tags.MSG_TYPE);
        r[i++] = str(message, Tags.ACCOUNT);
        r[i++] = str(message, Tags.SYMBOL);

        r[i++] = str(message, Tags.CL_ORD_ID);
        r[i++] = str(message, Tags.ORIG_CL_ORD_ID);
        r[i++] = str(message, Tags.ORDER_ID);
        r[i++] = str(message, Tags.EXEC_ID);
        r[i++] = str(message, Tags.EXEC_REF_ID);
        r[i++] = str(message, Tags.SECONDARY_CL_ORD_ID);

        r[i++] = str(message, Tags.SIDE);
        r[i++] = str(message, Tags.ORD_TYPE);
        r[i++] = dbl(message, Tags.ORDER_QTY);
        r[i++] = dbl(message, Tags.PRICE);
        r[i++] = dbl(message, Tags.STOP_PX);
        r[i++] = str(message, Tags.TIME_IN_FORCE);
        r[i++] = str(message, Tags.CURRENCY);

        r[i++] = str(message, Tags.ORD_STATUS);
        r[i++] = str(message, Tags.EXEC_TYPE);
        r[i++] = str(message, Tags.EXEC_TRANS_TYPE);
        r[i++] = dbl(message, Tags.LAST_SHARES);
        r[i++] = dbl(message, Tags.LAST_PX);
        r[i++] = str(message, Tags.LAST_MKT);
        r[i++] = dbl(message, Tags.CUM_QTY);
        r[i++] = dbl(message, Tags.LEAVES_QTY);
        r[i++] = dbl(message, Tags.AVG_PX);

        r[i++] = str(message, Tags.TEXT);
        r[i++] = integer(message, Tags.ORD_REJ_REASON);
        r[i++] = integer(message, Tags.CXL_REJ_REASON);
        r[i++] = str(message, Tags.CXL_REJ_RESPONSE_TO);
        r[i++] = str(message, Tags.DK_REASON);

        r[i++] = fixTimestamp(message, Tags.SENDING_TIME);
        r[i++] = fixTimestamp(message, Tags.TRANSACT_TIME);
        r[i++] = bigint(message, Tags.MSG_SEQ_NUM);
        r[i++] = str(message, Tags.SENDER_COMP_ID);
        r[i++] = str(message, Tags.TARGET_COMP_ID);
        r[i++] = str(message, Tags.POSS_DUP_FLAG);

        r[i++] = rawText(message, rawText);

        if (i != r.length) {
            throw new IllegalStateException("RawFixRowMapper filled " + i + " of " + r.length + " columns");
        }
        return r;
    }

    private String rawText(FixMessage message, String rawText) {
        if (!captureRawText) {
            return null;
        }
        return (rawText != null) ? rawText : serializer.serializeVerbatim(message);
    }

    // ----- tag readers: absent/blank/unparseable -> null (SQL NULL) -----

    private static String str(FixMessage m, int tag) {
        String v = FixSupport.firstValue(m, tag);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static Double dbl(FixMessage m, int tag) {
        String v = str(m, tag);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(FixMessage m, int tag) {
        String v = str(m, tag);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long bigint(FixMessage m, int tag) {
        String v = str(m, tag);
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.time.LocalDateTime fixTimestamp(FixMessage m, int tag) {
        String v = str(m, tag);
        if (v == null) {
            return null;
        }
        Long millis = Timestamps.parseFixUtcTimestamp(v);
        return (millis == null) ? null : Timestamps.utc(millis);
    }
}
