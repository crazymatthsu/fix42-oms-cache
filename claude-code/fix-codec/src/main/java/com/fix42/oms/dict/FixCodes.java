package com.fix42.oms.dict;

import com.fix42.oms.proto.CxlRejResponseTo;
import com.fix42.oms.proto.ExecTransType;
import com.fix42.oms.proto.ExecType;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrdType;
import com.fix42.oms.proto.Side;
import com.fix42.oms.proto.TimeInForce;

import java.util.HashMap;
import java.util.Map;

/**
 * Bidirectional mapping between FIX 4.2 single-character wire codes and the typed
 * protobuf enums.
 *
 * <p>Mappings are per FIELD, not per character: the character {@code 'D'} means
 * {@link OrdStatus#ORD_STATUS_ACCEPTED_FOR_BIDDING} in tag 39 but
 * {@link ExecType#EXEC_TYPE_RESTATED} in tag 150. Unknown codes map to the
 * {@code *_UNSPECIFIED} enum value; unknown enums map to an empty string.
 */
public final class FixCodes {

    private FixCodes() {
    }

    // ----- Side (54) -----
    private static final Map<String, Side> SIDE_BY_CODE = new HashMap<>();
    private static final Map<Side, String> SIDE_TO_CODE = new HashMap<>();

    // ----- OrdType (40) -----
    private static final Map<String, OrdType> ORD_TYPE_BY_CODE = new HashMap<>();
    private static final Map<OrdType, String> ORD_TYPE_TO_CODE = new HashMap<>();

    // ----- TimeInForce (59) -----
    private static final Map<String, TimeInForce> TIF_BY_CODE = new HashMap<>();
    private static final Map<TimeInForce, String> TIF_TO_CODE = new HashMap<>();

    // ----- OrdStatus (39) -----
    private static final Map<String, OrdStatus> ORD_STATUS_BY_CODE = new HashMap<>();
    private static final Map<OrdStatus, String> ORD_STATUS_TO_CODE = new HashMap<>();

    // ----- ExecType (150) -----
    private static final Map<String, ExecType> EXEC_TYPE_BY_CODE = new HashMap<>();
    private static final Map<ExecType, String> EXEC_TYPE_TO_CODE = new HashMap<>();

    // ----- ExecTransType (20) -----
    private static final Map<String, ExecTransType> EXEC_TRANS_BY_CODE = new HashMap<>();
    private static final Map<ExecTransType, String> EXEC_TRANS_TO_CODE = new HashMap<>();

    // ----- CxlRejResponseTo (434) -----
    private static final Map<String, CxlRejResponseTo> CXL_REJ_BY_CODE = new HashMap<>();
    private static final Map<CxlRejResponseTo, String> CXL_REJ_TO_CODE = new HashMap<>();

    private static void side(String c, Side v) {
        SIDE_BY_CODE.put(c, v);
        SIDE_TO_CODE.put(v, c);
    }

    private static void ordType(String c, OrdType v) {
        ORD_TYPE_BY_CODE.put(c, v);
        ORD_TYPE_TO_CODE.put(v, c);
    }

    private static void tif(String c, TimeInForce v) {
        TIF_BY_CODE.put(c, v);
        TIF_TO_CODE.put(v, c);
    }

    private static void ordStatus(String c, OrdStatus v) {
        ORD_STATUS_BY_CODE.put(c, v);
        ORD_STATUS_TO_CODE.put(v, c);
    }

    private static void execType(String c, ExecType v) {
        EXEC_TYPE_BY_CODE.put(c, v);
        EXEC_TYPE_TO_CODE.put(v, c);
    }

    private static void execTrans(String c, ExecTransType v) {
        EXEC_TRANS_BY_CODE.put(c, v);
        EXEC_TRANS_TO_CODE.put(v, c);
    }

    private static void cxlRej(String c, CxlRejResponseTo v) {
        CXL_REJ_BY_CODE.put(c, v);
        CXL_REJ_TO_CODE.put(v, c);
    }

    static {
        // Side (54)
        side("1", Side.SIDE_BUY);
        side("2", Side.SIDE_SELL);
        side("3", Side.SIDE_BUY_MINUS);
        side("4", Side.SIDE_SELL_PLUS);
        side("5", Side.SIDE_SELL_SHORT);
        side("6", Side.SIDE_SELL_SHORT_EXEMPT);
        side("7", Side.SIDE_UNDISCLOSED);
        side("8", Side.SIDE_CROSS);
        side("9", Side.SIDE_CROSS_SHORT);

        // OrdType (40)
        ordType("1", OrdType.ORD_TYPE_MARKET);
        ordType("2", OrdType.ORD_TYPE_LIMIT);
        ordType("3", OrdType.ORD_TYPE_STOP);
        ordType("4", OrdType.ORD_TYPE_STOP_LIMIT);
        ordType("5", OrdType.ORD_TYPE_MARKET_ON_CLOSE);
        ordType("6", OrdType.ORD_TYPE_WITH_OR_WITHOUT);
        ordType("7", OrdType.ORD_TYPE_LIMIT_OR_BETTER);
        ordType("8", OrdType.ORD_TYPE_LIMIT_WITH_OR_WITHOUT);
        ordType("9", OrdType.ORD_TYPE_ON_BASIS);
        ordType("A", OrdType.ORD_TYPE_ON_CLOSE);
        ordType("B", OrdType.ORD_TYPE_LIMIT_ON_CLOSE);
        ordType("P", OrdType.ORD_TYPE_PEGGED);

        // TimeInForce (59)
        tif("0", TimeInForce.TIME_IN_FORCE_DAY);
        tif("1", TimeInForce.TIME_IN_FORCE_GTC);
        tif("2", TimeInForce.TIME_IN_FORCE_OPG);
        tif("3", TimeInForce.TIME_IN_FORCE_IOC);
        tif("4", TimeInForce.TIME_IN_FORCE_FOK);
        tif("5", TimeInForce.TIME_IN_FORCE_GTX);
        tif("6", TimeInForce.TIME_IN_FORCE_GTD);

        // OrdStatus (39)  -- 'D' = AcceptedForBidding (valid FIX 4.2)
        ordStatus("0", OrdStatus.ORD_STATUS_NEW);
        ordStatus("1", OrdStatus.ORD_STATUS_PARTIALLY_FILLED);
        ordStatus("2", OrdStatus.ORD_STATUS_FILLED);
        ordStatus("3", OrdStatus.ORD_STATUS_DONE_FOR_DAY);
        ordStatus("4", OrdStatus.ORD_STATUS_CANCELED);
        ordStatus("5", OrdStatus.ORD_STATUS_REPLACED);
        ordStatus("6", OrdStatus.ORD_STATUS_PENDING_CANCEL);
        ordStatus("7", OrdStatus.ORD_STATUS_STOPPED);
        ordStatus("8", OrdStatus.ORD_STATUS_REJECTED);
        ordStatus("9", OrdStatus.ORD_STATUS_SUSPENDED);
        ordStatus("A", OrdStatus.ORD_STATUS_PENDING_NEW);
        ordStatus("B", OrdStatus.ORD_STATUS_CALCULATED);
        ordStatus("C", OrdStatus.ORD_STATUS_EXPIRED);
        ordStatus("D", OrdStatus.ORD_STATUS_ACCEPTED_FOR_BIDDING);
        ordStatus("E", OrdStatus.ORD_STATUS_PENDING_REPLACE);

        // ExecType (150)  -- 'D' = Restated (distinct from OrdStatus 'D')
        execType("0", ExecType.EXEC_TYPE_NEW);
        execType("1", ExecType.EXEC_TYPE_PARTIAL_FILL);
        execType("2", ExecType.EXEC_TYPE_FILL);
        execType("3", ExecType.EXEC_TYPE_DONE_FOR_DAY);
        execType("4", ExecType.EXEC_TYPE_CANCELED);
        execType("5", ExecType.EXEC_TYPE_REPLACED);
        execType("6", ExecType.EXEC_TYPE_PENDING_CANCEL);
        execType("7", ExecType.EXEC_TYPE_STOPPED);
        execType("8", ExecType.EXEC_TYPE_REJECTED);
        execType("9", ExecType.EXEC_TYPE_SUSPENDED);
        execType("A", ExecType.EXEC_TYPE_PENDING_NEW);
        execType("B", ExecType.EXEC_TYPE_CALCULATED);
        execType("C", ExecType.EXEC_TYPE_EXPIRED);
        execType("D", ExecType.EXEC_TYPE_RESTATED);
        execType("E", ExecType.EXEC_TYPE_PENDING_REPLACE);

        // ExecTransType (20)
        execTrans("0", ExecTransType.EXEC_TRANS_TYPE_NEW);
        execTrans("1", ExecTransType.EXEC_TRANS_TYPE_CANCEL);
        execTrans("2", ExecTransType.EXEC_TRANS_TYPE_CORRECT);
        execTrans("3", ExecTransType.EXEC_TRANS_TYPE_STATUS);

        // CxlRejResponseTo (434)
        cxlRej("1", CxlRejResponseTo.CXL_REJ_RESPONSE_TO_ORDER_CANCEL_REQUEST);
        cxlRej("2", CxlRejResponseTo.CXL_REJ_RESPONSE_TO_ORDER_CANCEL_REPLACE_REQUEST);
    }

    // ----- decode (code -> enum) -----

    public static Side sideFromCode(String code) {
        return code == null ? Side.SIDE_UNSPECIFIED : SIDE_BY_CODE.getOrDefault(code, Side.SIDE_UNSPECIFIED);
    }

    public static OrdType ordTypeFromCode(String code) {
        return code == null ? OrdType.ORD_TYPE_UNSPECIFIED : ORD_TYPE_BY_CODE.getOrDefault(code, OrdType.ORD_TYPE_UNSPECIFIED);
    }

    public static TimeInForce timeInForceFromCode(String code) {
        return code == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : TIF_BY_CODE.getOrDefault(code, TimeInForce.TIME_IN_FORCE_UNSPECIFIED);
    }

    public static OrdStatus ordStatusFromCode(String code) {
        return code == null ? OrdStatus.ORD_STATUS_UNSPECIFIED : ORD_STATUS_BY_CODE.getOrDefault(code, OrdStatus.ORD_STATUS_UNSPECIFIED);
    }

    public static ExecType execTypeFromCode(String code) {
        return code == null ? ExecType.EXEC_TYPE_UNSPECIFIED : EXEC_TYPE_BY_CODE.getOrDefault(code, ExecType.EXEC_TYPE_UNSPECIFIED);
    }

    public static ExecTransType execTransTypeFromCode(String code) {
        // Default '0'=New is also the proto3 default; treat null/unknown as NEW.
        return code == null ? ExecTransType.EXEC_TRANS_TYPE_NEW : EXEC_TRANS_BY_CODE.getOrDefault(code, ExecTransType.EXEC_TRANS_TYPE_NEW);
    }

    public static CxlRejResponseTo cxlRejResponseToFromCode(String code) {
        return code == null ? CxlRejResponseTo.CXL_REJ_RESPONSE_TO_UNSPECIFIED : CXL_REJ_BY_CODE.getOrDefault(code, CxlRejResponseTo.CXL_REJ_RESPONSE_TO_UNSPECIFIED);
    }

    // ----- encode (enum -> code) -----

    public static String codeFor(Side v) {
        return SIDE_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(OrdType v) {
        return ORD_TYPE_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(TimeInForce v) {
        return TIF_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(OrdStatus v) {
        return ORD_STATUS_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(ExecType v) {
        return EXEC_TYPE_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(ExecTransType v) {
        return EXEC_TRANS_TO_CODE.getOrDefault(v, "");
    }

    public static String codeFor(CxlRejResponseTo v) {
        return CXL_REJ_TO_CODE.getOrDefault(v, "");
    }
}
