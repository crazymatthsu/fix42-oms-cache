package com.fix42.oms.fix;

import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read/build helpers for the generic {@link FixMessage} model.
 *
 * <p>FIX fields are ordered and a tag may repeat (both across repeating groups and,
 * rarely, at the top level). "first*" helpers return the first occurrence, which is
 * the correct choice for the non-repeating top-level fields the cache reads.
 */
public final class FixSupport {

    private FixSupport() {
    }

    /** @return the value of the first field with {@code tag}, or {@code null} if absent. */
    public static String firstValue(FixMessage msg, int tag) {
        for (FixField f : msg.getFieldsList()) {
            if (f.getTag() == tag) {
                return f.getValue();
            }
        }
        return null;
    }

    public static Optional<String> first(FixMessage msg, int tag) {
        return Optional.ofNullable(firstValue(msg, tag));
    }

    /** @return {@code true} if the message contains at least one field with {@code tag}. */
    public static boolean has(FixMessage msg, int tag) {
        for (FixField f : msg.getFieldsList()) {
            if (f.getTag() == tag) {
                return true;
            }
        }
        return false;
    }

    /** @return all values for {@code tag} in message order (empty if none). */
    public static List<String> allValues(FixMessage msg, int tag) {
        List<String> out = new ArrayList<>();
        for (FixField f : msg.getFieldsList()) {
            if (f.getTag() == tag) {
                out.add(f.getValue());
            }
        }
        return out;
    }

    /** MsgType (tag 35) of the message, or {@code null} if absent. */
    public static String msgType(FixMessage msg) {
        return firstValue(msg, Tags.MSG_TYPE);
    }

    /** Parse a FIX qty/price string to double; returns {@code dflt} for null/blank/invalid. */
    public static double parseDouble(String s, double dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Parse an int string; returns {@code dflt} for null/blank/invalid. */
    public static int parseInt(String s, int dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ----- builders -----

    public static FixField field(int tag, String value) {
        return FixField.newBuilder().setTag(tag).setValue(value == null ? "" : value).build();
    }

    /** Convenience: build a FixMessage from alternating tag/value pairs (values stringified). */
    public static FixMessage message(Object... tagThenValue) {
        if ((tagThenValue.length & 1) != 0) {
            throw new IllegalArgumentException("Expected an even number of tag/value arguments");
        }
        FixMessage.Builder b = FixMessage.newBuilder();
        for (int i = 0; i < tagThenValue.length; i += 2) {
            int tag = ((Number) tagThenValue[i]).intValue();
            String value = String.valueOf(tagThenValue[i + 1]);
            b.addFields(field(tag, value));
        }
        return b.build();
    }
}
