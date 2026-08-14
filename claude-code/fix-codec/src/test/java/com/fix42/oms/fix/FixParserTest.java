package com.fix42.oms.fix;

import com.fix42.oms.proto.FixMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixParserTest {

    private static final String SOH = String.valueOf(FixConstants.SOH);

    @Test
    void parsesBasicSohMessageInOrder() {
        String raw = "8=FIX.4.2" + SOH + "9=12" + SOH + "35=D" + SOH + "11=ORD1" + SOH + "10=000" + SOH;
        FixMessage msg = FixParser.standard().parse(raw);

        assertEquals(5, msg.getFieldsCount());
        assertEquals(8, msg.getFields(0).getTag());
        assertEquals("FIX.4.2", msg.getFields(0).getValue());
        assertEquals("D", FixSupport.msgType(msg));
        assertEquals("ORD1", FixSupport.firstValue(msg, Tags.CL_ORD_ID));
    }

    @Test
    void parsesPipeDelimited() {
        FixMessage msg = FixParser.pipe().parse("8=FIX.4.2|35=8|17=E1|58=hello|10=001|");
        assertEquals("E1", FixSupport.firstValue(msg, Tags.EXEC_ID));
        assertEquals("hello", FixSupport.firstValue(msg, Tags.TEXT));
    }

    @Test
    void splitsOnlyOnFirstEqualsSoValueMayContainEquals() {
        FixMessage msg = FixParser.pipe().parse("35=8|58=a=b=c|10=000|");
        assertEquals("a=b=c", FixSupport.firstValue(msg, Tags.TEXT));
    }

    @Test
    void toleratesTrailingNewline() {
        FixMessage msg = FixParser.pipe().parse("35=D|11=X|10=000|\r\n");
        assertEquals("X", FixSupport.firstValue(msg, Tags.CL_ORD_ID));
        assertEquals(3, msg.getFieldsCount());
    }

    @Test
    void lengthAwareReadingKeepsEmbeddedSohInsideRawData() {
        // RawData(96) value is "x<SOH>y" (length 3) and must not be split at the embedded SOH.
        String data = "x" + SOH + "y";
        String raw = "35=D" + SOH + "95=3" + SOH + "96=" + data + SOH + "55=IBM" + SOH + "10=000" + SOH;

        FixMessage msg = FixParser.standard().parse(raw);

        assertEquals("3", FixSupport.firstValue(msg, Tags.RAW_DATA_LENGTH));
        assertEquals(data, FixSupport.firstValue(msg, Tags.RAW_DATA));
        assertEquals("IBM", FixSupport.firstValue(msg, Tags.SYMBOL));
        // 35, 95, 96, 55, 10 -> exactly 5 fields (embedded SOH did not create extra fields).
        assertEquals(5, msg.getFieldsCount());
    }

    @Test
    void lengthAwareReadingHandlesSignatureTrailer() {
        String sig = "AB" + SOH + "CD"; // length 5, contains SOH
        String raw = "35=8" + SOH + "93=5" + SOH + "89=" + sig + SOH + "10=000" + SOH;
        FixMessage msg = FixParser.standard().parse(raw);
        assertEquals(sig, FixSupport.firstValue(msg, Tags.SIGNATURE));
        assertEquals("000", FixSupport.firstValue(msg, Tags.CHECK_SUM));
    }

    @Test
    void throwsOnMissingEquals() {
        assertThrows(FixParseException.class, () -> FixParser.pipe().parse("35=D|foo|10=000|"));
    }

    @Test
    void throwsOnNonNumericTag() {
        assertThrows(FixParseException.class, () -> FixParser.pipe().parse("35=D|ab=1|10=000|"));
    }

    @Test
    void throwsOnEmptyInput() {
        assertThrows(FixParseException.class, () -> FixParser.standard().parse(""));
    }

    @Test
    void strictModeAcceptsCorrectChecksum() {
        // Build a canonical message (correct 9 and 10), then strict-parse it.
        FixMessage built = FixSupport.message(
                Tags.MSG_TYPE, "D", Tags.CL_ORD_ID, "ORD1", Tags.SYMBOL, "IBM", Tags.SIDE, "1");
        String canonical = FixSerializer.standard().serialize(built);
        FixMessage parsed = FixParser.standard().validatingChecksum().parse(canonical);
        assertEquals("ORD1", FixSupport.firstValue(parsed, Tags.CL_ORD_ID));
    }

    @Test
    void strictModeRejectsCorruptChecksum() {
        FixMessage built = FixSupport.message(Tags.MSG_TYPE, "D", Tags.CL_ORD_ID, "ORD1");
        String canonical = FixSerializer.standard().serialize(built);
        // Corrupt a body byte so the declared checksum no longer matches.
        String corrupt = canonical.replace("ORD1", "ORD2");
        assertThrows(FixParseException.class, () -> FixParser.standard().validatingChecksum().parse(corrupt));
    }

    @Test
    void withDelimiterAutoWorksForCustomChar() {
        FixMessage msg = FixParser.withDelimiter('^').parse("35=D^11=Z^10=000^");
        assertTrue(FixSupport.has(msg, Tags.CL_ORD_ID));
        assertEquals("Z", FixSupport.firstValue(msg, Tags.CL_ORD_ID));
    }
}
