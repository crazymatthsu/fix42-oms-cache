package com.fix42.oms.fix;

import java.util.Set;

public final class FixConstants {
    public static final char SOH = '\u0001';
    public static final String SOH_STR = "\u0001";
    public static final String BEGIN_STRING_42 = "FIX.4.2";

    public static final String MSG_NEW_ORDER_SINGLE = "D";
    public static final String MSG_EXECUTION_REPORT = "8";
    public static final String MSG_ORDER_CANCEL_REJECT = "9";
    public static final String MSG_ORDER_CANCEL_REQUEST = "F";
    public static final String MSG_ORDER_CANCEL_REPLACE = "G";
    public static final String MSG_ORDER_STATUS_REQUEST = "H";
    public static final String MSG_DONT_KNOW_TRADE = "Q";

    public static final Set<Integer> HEADER_TAGS = Set.of(
            Tags.BEGIN_STRING,
            Tags.BODY_LENGTH,
            Tags.MSG_TYPE,
            Tags.SENDER_COMP_ID,
            Tags.TARGET_COMP_ID,
            Tags.MSG_SEQ_NUM,
            Tags.SENDING_TIME,
            Tags.POSS_DUP_FLAG,
            97, // PossResend
            Tags.ORIG_SENDING_TIME,
            Tags.ON_BEHALF_OF_COMP_ID,
            Tags.DELIVER_TO_COMP_ID,
            90, // SecureDataLen
            91, // SecureData
            Tags.SENDER_SUB_ID,
            Tags.SENDER_LOCATION_ID,
            Tags.TARGET_SUB_ID,
            Tags.TARGET_LOCATION_ID,
            Tags.ON_BEHALF_OF_SUB_ID,
            Tags.ON_BEHALF_OF_LOCATION_ID,
            Tags.DELIVER_TO_SUB_ID,
            Tags.DELIVER_TO_LOCATION_ID,
            Tags.XML_DATA_LEN,
            Tags.XML_DATA
    );

    public static final Set<Integer> TRAILER_TAGS = Set.of(
            Tags.CHECK_SUM,
            Tags.SIGNATURE,
            Tags.SIGNATURE_LENGTH
    );

    private FixConstants() {}
}
