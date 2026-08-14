package com.fix42.oms.fix;

/** Wire-level constants for FIX 4.2. */
public final class FixConstants {

    private FixConstants() {
    }

    /** The real FIX field delimiter: ASCII SOH (Start of Heading), 0x01. */
    public static final char SOH = (char) 0x01;

    /**
     * A human-readable delimiter ('|') commonly substituted for SOH in log files
     * and documentation. Supported by the parser/serializer for convenience.
     */
    public static final char PIPE = '|';

    /** BeginString value identifying FIX 4.2. */
    public static final String BEGIN_STRING_FIX42 = "FIX.4.2";
}
