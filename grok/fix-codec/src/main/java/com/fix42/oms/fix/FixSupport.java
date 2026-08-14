package com.fix42.oms.fix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class FixSupport {
    private FixSupport() {}

    public static String normalizeSoh(String raw) {
        if (raw == null) {
            throw new FixParseException("FIX message is null");
        }
        if (raw.indexOf(FixConstants.SOH) >= 0) {
            return raw;
        }
        if (raw.indexOf('|') >= 0) {
            return raw.replace('|', FixConstants.SOH);
        }
        return raw;
    }

    public static List<Field> splitFields(String sohMessage) {
        List<Field> fields = new ArrayList<>();
        int start = 0;
        int length = sohMessage.length();
        for (int i = 0; i <= length; i++) {
            if (i == length || sohMessage.charAt(i) == FixConstants.SOH) {
                if (i > start) {
                    fields.add(parseField(sohMessage.substring(start, i), fields.size()));
                }
                start = i + 1;
            }
        }
        return fields;
    }

    public static Field parseField(String pair, int index) {
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            throw new FixParseException("Malformed field at index " + index + ": " + pair);
        }
        int tag;
        try {
            tag = Integer.parseInt(pair.substring(0, eq));
        } catch (NumberFormatException e) {
            throw new FixParseException("Non-numeric tag at index " + index + ": " + pair, e);
        }
        return new Field(tag, pair.substring(eq + 1));
    }

    public static int checksum(byte[] bytes, int endExclusive) {
        int sum = 0;
        for (int i = 0; i < endExclusive; i++) {
            sum += bytes[i] & 0xFF;
        }
        return sum % 256;
    }

    public static String checksum3(int value) {
        return String.format("%03d", value);
    }

    public static int bodyLength(byte[] bytes) {
        int start = indexOf(bytes, "35=");
        if (start < 0) {
            throw new FixParseException("Missing tag 35 for BodyLength");
        }
        int checksum = indexOf(bytes, "10=");
        if (checksum < 0) {
            throw new FixParseException("Missing tag 10 for BodyLength");
        }
        return checksum - start;
    }

    public static int indexOf(byte[] bytes, String token) {
        byte[] needle = token.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static final class Field {
        public final int tag;
        public final String value;

        public Field(int tag, String value) {
            this.tag = tag;
            this.value = value;
        }
    }
}
