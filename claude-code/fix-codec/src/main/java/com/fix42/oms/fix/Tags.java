package com.fix42.oms.fix;

/**
 * FIX 4.2 tag numbers used by this library.
 *
 * <p>Only the tags relevant to the in-scope message types (D, 8, 9, F, G, H, Q),
 * their headers/trailers, and their repeating groups are declared. The generic
 * {@link com.fix42.oms.proto.FixMessage} model can still carry any tag; these
 * constants exist so the codec, dictionary, and state machine read fields by a
 * named tag rather than a magic number.
 */
public final class Tags {

    private Tags() {
    }

    // ----- Header -----
    public static final int BEGIN_STRING = 8;
    public static final int BODY_LENGTH = 9;
    public static final int MSG_TYPE = 35;
    public static final int MSG_SEQ_NUM = 34;
    public static final int SENDER_COMP_ID = 49;
    public static final int TARGET_COMP_ID = 56;
    public static final int SENDER_SUB_ID = 50;
    public static final int TARGET_SUB_ID = 57;
    public static final int SENDING_TIME = 52;
    public static final int POSS_DUP_FLAG = 43;
    public static final int POSS_RESEND = 97;
    public static final int ON_BEHALF_OF_COMP_ID = 115;
    public static final int DELIVER_TO_COMP_ID = 128;

    // ----- Trailer -----
    public static final int SIGNATURE_LENGTH = 93;
    public static final int SIGNATURE = 89;
    public static final int CHECK_SUM = 10;

    // ----- Order identifiers -----
    public static final int CL_ORD_ID = 11;
    public static final int ORIG_CL_ORD_ID = 41;
    public static final int ORDER_ID = 37;
    public static final int SECONDARY_ORDER_ID = 198;
    public static final int EXEC_ID = 17;
    public static final int EXEC_REF_ID = 19;
    public static final int LIST_ID = 66;
    /** SecondaryClOrdID (FIX 4.2). Default carrier for parent-order linkage. */
    public static final int SECONDARY_CL_ORD_ID = 526;

    // ----- Instrument / order terms -----
    public static final int ACCOUNT = 1;
    public static final int SYMBOL = 55;
    public static final int SIDE = 54;
    public static final int ORD_TYPE = 40;
    public static final int ORDER_QTY = 38;
    public static final int PRICE = 44;
    public static final int STOP_PX = 99;
    public static final int TIME_IN_FORCE = 59;
    public static final int CURRENCY = 15;
    public static final int HANDL_INST = 21;
    public static final int EXPIRE_TIME = 126;
    public static final int TRANSACT_TIME = 60;

    // ----- Execution / lifecycle -----
    public static final int ORD_STATUS = 39;
    public static final int EXEC_TYPE = 150;
    public static final int EXEC_TRANS_TYPE = 20;
    public static final int CUM_QTY = 14;
    public static final int LEAVES_QTY = 151;
    public static final int AVG_PX = 6;
    public static final int LAST_SHARES = 32; // FIX 4.2 name for LastQty
    public static final int LAST_PX = 31;
    public static final int LAST_MKT = 30;

    // ----- Reject / text -----
    public static final int TEXT = 58;
    public static final int ORD_REJ_REASON = 103;
    public static final int CXL_REJ_REASON = 102;
    public static final int CXL_REJ_RESPONSE_TO = 434;
    public static final int DK_REASON = 127;

    // ----- Repeating groups -----
    public static final int NO_ALLOCS = 78;
    public static final int ALLOC_ACCOUNT = 79;
    public static final int ALLOC_SHARES = 80;
    public static final int NO_CONTRA_BROKERS = 382;
    public static final int CONTRA_BROKER = 375;
    public static final int CONTRA_TRADER = 337;
    public static final int CONTRA_TRADE_QTY = 437;
    public static final int CONTRA_TRADE_TIME = 438;

    // ----- Length/data field pairs (value of the length tag = char count of the data tag) -----
    // All FIX 4.2 raw-data / encoded-field pairs. The Encoded* family (347-355)
    // was introduced in FIX 4.2 alongside MessageEncoding(347).
    public static final int SECURE_DATA_LEN = 90;
    public static final int SECURE_DATA = 91;
    public static final int RAW_DATA_LENGTH = 95;
    public static final int RAW_DATA = 96;
    public static final int MESSAGE_ENCODING = 347;
    public static final int ENCODED_ISSUER_LEN = 348;
    public static final int ENCODED_ISSUER = 349;
    public static final int ENCODED_SECURITY_DESC_LEN = 350;
    public static final int ENCODED_SECURITY_DESC = 351;
    public static final int ENCODED_LIST_EXEC_INST_LEN = 352;
    public static final int ENCODED_LIST_EXEC_INST = 353;
    public static final int ENCODED_TEXT_LEN = 354;
    public static final int ENCODED_TEXT = 355;
    // XmlData(212/213) is FIX 4.4+, intentionally NOT treated as a 4.2 length pair.
    public static final int XML_DATA_LEN = 212;
    public static final int XML_DATA = 213;
}
