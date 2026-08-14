package com.fix42.oms.fix;

import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixMessage;

import java.nio.charset.StandardCharsets;

/**
 * Serializes a {@link FixMessage} back to a FIX 4.2 string.
 *
 * <p>Two contracts:
 * <ul>
 *   <li>{@link #serializeVerbatim} writes every field in order exactly as stored,
 *       recomputing nothing. This is the lossless inverse of {@link FixParser#parse}:
 *       {@code serializeVerbatim(parse(s)) == s} for any well-formed {@code s} that
 *       ends with the delimiter.</li>
 *   <li>{@link #serialize} produces a canonical, transmit-ready message: it moves
 *       BeginString(8)/BodyLength(9) to the front and CheckSum(10) to the end, and
 *       recomputes BodyLength and CheckSum. Body field order is otherwise preserved.</li>
 * </ul>
 *
 * <p>Byte counting for BodyLength and CheckSum uses ISO-8859-1 (Latin-1), matching the
 * single-byte-per-char FIX wire convention and keeping encoded/raw data faithful.
 */
public final class FixSerializer {

    private final char delimiter;

    private FixSerializer(char delimiter) {
        this.delimiter = delimiter;
    }

    /** SOH-delimited serializer (wire format). */
    public static FixSerializer standard() {
        return new FixSerializer(FixConstants.SOH);
    }

    /** '|'-delimited serializer (human-readable logs/tests). */
    public static FixSerializer pipe() {
        return new FixSerializer(FixConstants.PIPE);
    }

    public static FixSerializer withDelimiter(char delimiter) {
        return new FixSerializer(delimiter);
    }

    public char delimiter() {
        return delimiter;
    }

    /** Write all fields in order, verbatim (no recomputation). Lossless inverse of parse. */
    public String serializeVerbatim(FixMessage msg) {
        StringBuilder sb = new StringBuilder(64);
        for (FixField f : msg.getFieldsList()) {
            sb.append(f.getTag()).append('=').append(f.getValue()).append(delimiter);
        }
        return sb.toString();
    }

    /**
     * Produce a canonical message with recomputed BodyLength(9) and CheckSum(10).
     * BeginString(8) and BodyLength(9) are emitted first, CheckSum(10) last; all other
     * fields keep their relative order. Any 8/9/10 already present in {@code msg} are
     * dropped in favor of the canonical/ recomputed versions.
     */
    public String serialize(FixMessage msg) {
        String beginString = FixSupport.firstValue(msg, Tags.BEGIN_STRING);
        if (beginString == null || beginString.isEmpty()) {
            beginString = FixConstants.BEGIN_STRING_FIX42;
        }

        StringBuilder body = new StringBuilder(64);
        for (FixField f : msg.getFieldsList()) {
            int tag = f.getTag();
            if (tag == Tags.BEGIN_STRING || tag == Tags.BODY_LENGTH || tag == Tags.CHECK_SUM) {
                continue; // recomputed / repositioned below
            }
            body.append(tag).append('=').append(f.getValue()).append(delimiter);
        }
        String bodyStr = body.toString();
        int bodyLength = bodyStr.getBytes(StandardCharsets.ISO_8859_1).length;

        StringBuilder head = new StringBuilder(32);
        head.append(Tags.BEGIN_STRING).append('=').append(beginString).append(delimiter);
        head.append(Tags.BODY_LENGTH).append('=').append(bodyLength).append(delimiter);

        String preChecksum = head + bodyStr;
        String checksum = checksum3(preChecksum);
        return preChecksum + Tags.CHECK_SUM + "=" + checksum + delimiter;
    }

    /**
     * Compute the 3-digit CheckSum(10) value for {@code msg} as it would appear on the
     * wire: the modulo-256 byte sum of every field except CheckSum itself, rendered in
     * stored order with the given delimiter. Used by strict-mode parse validation.
     */
    public static String computeCheckSum(FixMessage msg, char delimiter) {
        StringBuilder sb = new StringBuilder(64);
        for (FixField f : msg.getFieldsList()) {
            if (f.getTag() == Tags.CHECK_SUM) {
                continue;
            }
            sb.append(f.getTag()).append('=').append(f.getValue()).append(delimiter);
        }
        return checksum3(sb.toString());
    }

    /** Compute BodyLength(9) for {@code msg}: byte count of the body (all fields except 8/9/10). */
    public static int computeBodyLength(FixMessage msg, char delimiter) {
        StringBuilder body = new StringBuilder(64);
        for (FixField f : msg.getFieldsList()) {
            int tag = f.getTag();
            if (tag == Tags.BEGIN_STRING || tag == Tags.BODY_LENGTH || tag == Tags.CHECK_SUM) {
                continue;
            }
            body.append(tag).append('=').append(f.getValue()).append(delimiter);
        }
        return body.toString().getBytes(StandardCharsets.ISO_8859_1).length;
    }

    private static String checksum3(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        int sum = 0;
        for (byte b : bytes) {
            sum += (b & 0xFF);
        }
        int cs = sum % 256;
        return String.format("%03d", cs);
    }
}
