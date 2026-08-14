package com.fix42.oms.fix;

import com.fix42.oms.proto.FixMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixSerializerTest {

    private static final String SOH = String.valueOf(FixConstants.SOH);

    @Test
    void verbatimRoundTripIsLossless() {
        String raw = "8=FIX.4.2" + SOH + "9=27" + SOH + "35=8" + SOH + "37=EX1" + SOH
                + "58=a=b" + SOH + "10=123" + SOH;
        FixMessage msg = FixParser.standard().parse(raw);
        String out = FixSerializer.standard().serializeVerbatim(msg);
        assertEquals(raw, out);
    }

    @Test
    void verbatimRoundTripPreservesEmbeddedSohInRawData() {
        String data = "x" + SOH + "y";
        String raw = "35=D" + SOH + "95=3" + SOH + "96=" + data + SOH + "10=000" + SOH;
        FixMessage msg = FixParser.standard().parse(raw);
        assertEquals(raw, FixSerializer.standard().serializeVerbatim(msg));
    }

    @Test
    void canonicalSerializeRecomputesBodyLengthAndChecksum() {
        FixMessage msg = FixSupport.message(
                Tags.BEGIN_STRING, "FIX.4.2",
                Tags.MSG_TYPE, "D",
                Tags.CL_ORD_ID, "ORD1",
                Tags.SYMBOL, "IBM",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "100");
        String out = FixSerializer.standard().serialize(msg);

        // BeginString first, CheckSum last, 3-digit checksum.
        assertTrue(out.startsWith("8=FIX.4.2" + SOH + "9="), out);
        assertTrue(out.endsWith(SOH), out);

        // Independently verify BodyLength and CheckSum against the FIX definitions.
        int idxAfter9 = out.indexOf(SOH, out.indexOf("9=") + 2) + 1;
        int idx10 = out.indexOf(SOH + "10=") + 1;
        int bodyLen = out.substring(idxAfter9, idx10).getBytes(StandardCharsets.ISO_8859_1).length;
        String declaredBodyLen = extract(out, "9=");
        assertEquals(bodyLen, Integer.parseInt(declaredBodyLen));

        String preChecksum = out.substring(0, idx10);
        int sum = 0;
        for (byte b : preChecksum.getBytes(StandardCharsets.ISO_8859_1)) {
            sum += (b & 0xFF);
        }
        assertEquals(String.format("%03d", sum % 256), extract(out, "10="));
    }

    @Test
    void canonicalSerializeDropsStaleBodyLengthAndChecksum() {
        // Provide wrong 9 and 10; serialize must replace them with correct values.
        FixMessage msg = FixSupport.message(
                Tags.BEGIN_STRING, "FIX.4.2",
                Tags.BODY_LENGTH, "999",
                Tags.MSG_TYPE, "D",
                Tags.CL_ORD_ID, "ORD1",
                Tags.CHECK_SUM, "007");
        String out = FixSerializer.standard().serialize(msg);
        // Re-parse under strict checksum validation: must be self-consistent.
        FixMessage reparsed = FixParser.standard().validatingChecksum().parse(out);
        assertEquals("ORD1", FixSupport.firstValue(reparsed, Tags.CL_ORD_ID));
        // Only one 9 and one 10 in the output.
        assertEquals(1, countOccurrences(out, SOH + "10="));
    }

    @Test
    void computeCheckSumMatchesManualComputation() {
        FixMessage msg = FixSupport.message(Tags.MSG_TYPE, "0", Tags.CL_ORD_ID, "A");
        String body = "35=0" + SOH + "11=A" + SOH;
        int sum = 0;
        for (byte b : body.getBytes(StandardCharsets.ISO_8859_1)) {
            sum += (b & 0xFF);
        }
        assertEquals(String.format("%03d", sum % 256), FixSerializer.computeCheckSum(msg, FixConstants.SOH));
    }

    private static String extract(String msg, String tagEq) {
        int i = msg.indexOf(tagEq);
        int end = msg.indexOf(FixConstants.SOH, i);
        return msg.substring(i + tagEq.length(), end);
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            count++;
            i += sub.length();
        }
        return count;
    }
}
