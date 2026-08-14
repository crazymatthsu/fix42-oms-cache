package com.fix42.oms.dict;

import com.fix42.oms.proto.CxlRejResponseTo;
import com.fix42.oms.proto.ExecType;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrdType;
import com.fix42.oms.proto.Side;
import com.fix42.oms.proto.TimeInForce;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixCodesTest {

    @Test
    void ordStatusAndExecTypeDisagreeOnLetterD() {
        // The character 'D' is AcceptedForBidding for OrdStatus(39) but Restated for ExecType(150).
        assertEquals(OrdStatus.ORD_STATUS_ACCEPTED_FOR_BIDDING, FixCodes.ordStatusFromCode("D"));
        assertEquals(ExecType.EXEC_TYPE_RESTATED, FixCodes.execTypeFromCode("D"));
    }

    @Test
    void ordStatusCoreCodes() {
        assertEquals(OrdStatus.ORD_STATUS_NEW, FixCodes.ordStatusFromCode("0"));
        assertEquals(OrdStatus.ORD_STATUS_PARTIALLY_FILLED, FixCodes.ordStatusFromCode("1"));
        assertEquals(OrdStatus.ORD_STATUS_FILLED, FixCodes.ordStatusFromCode("2"));
        assertEquals(OrdStatus.ORD_STATUS_CANCELED, FixCodes.ordStatusFromCode("4"));
        assertEquals(OrdStatus.ORD_STATUS_REPLACED, FixCodes.ordStatusFromCode("5"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_CANCEL, FixCodes.ordStatusFromCode("6"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_NEW, FixCodes.ordStatusFromCode("A"));
        assertEquals(OrdStatus.ORD_STATUS_PENDING_REPLACE, FixCodes.ordStatusFromCode("E"));
    }

    @Test
    void sideRoundTrip() {
        for (Side s : new Side[]{Side.SIDE_BUY, Side.SIDE_SELL, Side.SIDE_SELL_SHORT, Side.SIDE_CROSS}) {
            assertEquals(s, FixCodes.sideFromCode(FixCodes.codeFor(s)));
        }
        assertEquals("1", FixCodes.codeFor(Side.SIDE_BUY));
        assertEquals("5", FixCodes.codeFor(Side.SIDE_SELL_SHORT));
    }

    @Test
    void ordTypeAndTifCodes() {
        assertEquals(OrdType.ORD_TYPE_LIMIT, FixCodes.ordTypeFromCode("2"));
        assertEquals("4", FixCodes.codeFor(OrdType.ORD_TYPE_STOP_LIMIT));
        assertEquals(TimeInForce.TIME_IN_FORCE_IOC, FixCodes.timeInForceFromCode("3"));
        assertEquals("6", FixCodes.codeFor(TimeInForce.TIME_IN_FORCE_GTD));
    }

    @Test
    void cxlRejResponseTo() {
        assertEquals(CxlRejResponseTo.CXL_REJ_RESPONSE_TO_ORDER_CANCEL_REQUEST,
                FixCodes.cxlRejResponseToFromCode("1"));
        assertEquals(CxlRejResponseTo.CXL_REJ_RESPONSE_TO_ORDER_CANCEL_REPLACE_REQUEST,
                FixCodes.cxlRejResponseToFromCode("2"));
    }

    @Test
    void unknownCodesMapToUnspecified() {
        assertEquals(Side.SIDE_UNSPECIFIED, FixCodes.sideFromCode("Z"));
        assertEquals(OrdStatus.ORD_STATUS_UNSPECIFIED, FixCodes.ordStatusFromCode(null));
        assertEquals(OrdType.ORD_TYPE_UNSPECIFIED, FixCodes.ordTypeFromCode(""));
    }
}
