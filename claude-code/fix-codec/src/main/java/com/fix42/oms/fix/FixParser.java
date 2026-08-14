package com.fix42.oms.fix;

import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixMessage;

import java.util.Map;

/**
 * Parses a FIX 4.2 message string into the generic, lossless {@link FixMessage}.
 *
 * <p>The parser preserves field order exactly (header, body, trailer) so that
 * {@link FixSerializer#serializeVerbatim} reproduces the original byte-for-byte.
 * It is length-aware: when it encounters a "length" tag (e.g. RawDataLength 95,
 * SignatureLength 93) it reads exactly that many characters for the following
 * "data" tag, so embedded SOH / '=' inside binary data does not corrupt parsing.
 *
 * <p>Instances are immutable and thread-safe. Use {@link #standard()} for the
 * SOH-delimited wire format or {@link #pipe()} for '|'-delimited log/test input.
 */
public final class FixParser {

    /**
     * length-tag -> data-tag pairs where the length tag's value is the exact character
     * count of the following data tag. These are the FIX 4.2 raw-data / encoded-field
     * pairs; XmlData(212/213) is intentionally excluded as it is FIX 4.4+.
     */
    private static final Map<Integer, Integer> LENGTH_TO_DATA = Map.of(
            Tags.SECURE_DATA_LEN, Tags.SECURE_DATA,           // 90  -> 91
            Tags.SIGNATURE_LENGTH, Tags.SIGNATURE,            // 93  -> 89
            Tags.RAW_DATA_LENGTH, Tags.RAW_DATA,              // 95  -> 96
            Tags.ENCODED_ISSUER_LEN, Tags.ENCODED_ISSUER,     // 348 -> 349
            Tags.ENCODED_SECURITY_DESC_LEN, Tags.ENCODED_SECURITY_DESC, // 350 -> 351
            Tags.ENCODED_LIST_EXEC_INST_LEN, Tags.ENCODED_LIST_EXEC_INST, // 352 -> 353
            Tags.ENCODED_TEXT_LEN, Tags.ENCODED_TEXT          // 354 -> 355
    );

    private final char delimiter;
    private final boolean validateChecksum;

    private FixParser(char delimiter, boolean validateChecksum) {
        this.delimiter = delimiter;
        this.validateChecksum = validateChecksum;
    }

    /** SOH-delimited parser, lenient (no checksum validation). */
    public static FixParser standard() {
        return new FixParser(FixConstants.SOH, false);
    }

    /** '|'-delimited parser for logs/docs, lenient. */
    public static FixParser pipe() {
        return new FixParser(FixConstants.PIPE, false);
    }

    public static FixParser withDelimiter(char delimiter) {
        return new FixParser(delimiter, false);
    }

    /** @return a copy of this parser that verifies the CheckSum(10) field when present. */
    public FixParser validatingChecksum() {
        return new FixParser(this.delimiter, true);
    }

    public char delimiter() {
        return delimiter;
    }

    /**
     * Parse {@code raw} into a {@link FixMessage}. A single trailing delimiter (and any
     * trailing CR/LF) is tolerated. Throws {@link FixParseException} on malformed input.
     */
    public FixMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new FixParseException("Cannot parse null/empty FIX message");
        }
        // Trim a trailing newline but do NOT trim leading content (order matters).
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == '\n' || raw.charAt(end - 1) == '\r')) {
            end--;
        }

        FixMessage.Builder msg = FixMessage.newBuilder();
        int i = 0;
        int expectedDataTag = -1;
        int expectedDataLen = -1;

        while (i < end) {
            // Skip stray leading delimiters.
            if (raw.charAt(i) == delimiter) {
                i++;
                continue;
            }
            // ---- tag ----
            int eq = raw.indexOf('=', i);
            if (eq < 0 || eq >= end) {
                throw new FixParseException("Missing '=' in field starting at index " + i);
            }
            int tag = parseTag(raw.substring(i, eq));
            i = eq + 1;

            // ---- value ----
            String value;
            if (tag == expectedDataTag && expectedDataLen >= 0) {
                if (i + expectedDataLen > end) {
                    throw new FixParseException(
                            "Data field tag " + tag + " declares length " + expectedDataLen
                                    + " but only " + (end - i) + " chars remain");
                }
                value = raw.substring(i, i + expectedDataLen);
                i += expectedDataLen;
                // The data field must be terminated by a delimiter.
                if (i < end && raw.charAt(i) == delimiter) {
                    i++;
                }
                expectedDataTag = -1;
                expectedDataLen = -1;
            } else {
                int soh = raw.indexOf(delimiter, i);
                int valueEnd = (soh < 0 || soh > end) ? end : soh;
                value = raw.substring(i, valueEnd);
                i = (valueEnd < end) ? valueEnd + 1 : end;
                // If this is a length tag, arm length-aware reading for the next field.
                Integer dataTag = LENGTH_TO_DATA.get(tag);
                if (dataTag != null) {
                    expectedDataTag = dataTag;
                    expectedDataLen = FixSupport.parseInt(value, -1);
                }
            }
            msg.addFields(FixField.newBuilder().setTag(tag).setValue(value).build());
        }

        FixMessage result = msg.build();
        if (validateChecksum) {
            verifyChecksum(result);
        }
        return result;
    }

    private int parseTag(String tagStr) {
        String t = tagStr.trim();
        if (t.isEmpty()) {
            throw new FixParseException("Empty tag");
        }
        try {
            int tag = Integer.parseInt(t);
            if (tag <= 0) {
                throw new FixParseException("Non-positive tag: " + tag);
            }
            return tag;
        } catch (NumberFormatException e) {
            throw new FixParseException("Non-numeric tag: '" + tagStr + "'", e);
        }
    }

    private void verifyChecksum(FixMessage msg) {
        String declared = FixSupport.firstValue(msg, Tags.CHECK_SUM);
        if (declared == null) {
            throw new FixParseException("Strict mode: CheckSum(10) missing");
        }
        String computed = FixSerializer.computeCheckSum(msg, delimiter);
        if (!computed.equals(declared.trim())) {
            throw new FixParseException("CheckSum mismatch: declared=" + declared + " computed=" + computed);
        }
    }
}
