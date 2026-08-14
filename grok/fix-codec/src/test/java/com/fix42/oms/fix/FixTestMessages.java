package com.fix42.oms.fix;

import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixGroup;
import com.fix42.oms.proto.FixGroupInstance;
import com.fix42.oms.proto.FixHeader;
import com.fix42.oms.proto.FixMessage;

import java.util.ArrayList;
import java.util.List;

public final class FixTestMessages {
    private FixTestMessages() {}

    public static String d(Object... tagsAndValues) {
        return build(FixConstants.MSG_NEW_ORDER_SINGLE, tagsAndValues);
    }

    public static String er(Object... tagsAndValues) {
        return build(FixConstants.MSG_EXECUTION_REPORT, tagsAndValues);
    }

    public static String build(String msgType, Object... tagsAndValues) {
        FixMessage.Builder b = FixMessage.newBuilder();
        b.setHeader(FixHeader.newBuilder()
                .setBeginString(FixConstants.BEGIN_STRING_42)
                .setMsgType(msgType)
                .setSenderCompId("SEND")
                .setTargetCompId("TARG")
                .setMsgSeqNum(1)
                .setSendingTime("20260115-12:00:00"));
        FixGroup.Builder currentGroup = null;
        List<FixGroup> groups = new ArrayList<>();
        for (int i = 0; i < tagsAndValues.length; i += 2) {
            int tag = (Integer) tagsAndValues[i];
            String value = String.valueOf(tagsAndValues[i + 1]);
            if (tag == Tags.NO_ALLOCS || tag == Tags.NO_CONTRA_BROKERS
                    || tag == Tags.NO_MISC_FEES || tag == Tags.NO_TRADING_SESSIONS) {
                if (currentGroup != null) {
                    groups.add(currentGroup.build());
                }
                currentGroup = FixGroup.newBuilder().setNumInGroupTag(tag);
                continue;
            }
            if (currentGroup != null && isGroupField(currentGroup.getNumInGroupTag(), tag)) {
                if (isDelimiter(currentGroup.getNumInGroupTag(), tag)
                        || currentGroup.getInstancesCount() == 0) {
                    currentGroup.addInstances(FixGroupInstance.newBuilder().addFields(ff(tag, value)));
                } else {
                    FixGroupInstance last = currentGroup.getInstances(currentGroup.getInstancesCount() - 1);
                    currentGroup.setInstances(
                            currentGroup.getInstancesCount() - 1,
                            last.toBuilder().addFields(ff(tag, value)));
                }
            } else {
                if (currentGroup != null) {
                    groups.add(currentGroup.build());
                    currentGroup = null;
                }
                b.addFields(ff(tag, value));
            }
        }
        if (currentGroup != null) {
            groups.add(currentGroup.build());
        }
        b.addAllGroups(groups);
        return FixSerializer.serialize(b.build());
    }

    public static String pipe(String sohMessage) {
        return sohMessage.replace(FixConstants.SOH, '|');
    }

    private static boolean isDelimiter(int groupTag, int tag) {
        return switch (groupTag) {
            case Tags.NO_ALLOCS -> tag == Tags.ALLOC_ACCOUNT;
            case Tags.NO_CONTRA_BROKERS -> tag == Tags.CONTRA_BROKER;
            case Tags.NO_MISC_FEES -> tag == Tags.MISC_FEE_AMT;
            case Tags.NO_TRADING_SESSIONS -> tag == Tags.TRADING_SESSION_ID;
            default -> false;
        };
    }

    private static boolean isGroupField(int groupTag, int tag) {
        return switch (groupTag) {
            case Tags.NO_ALLOCS -> tag == Tags.ALLOC_ACCOUNT || tag == Tags.ALLOC_SHARES || tag == Tags.ALLOC_PRICE;
            case Tags.NO_CONTRA_BROKERS -> tag == Tags.CONTRA_BROKER || tag == Tags.CONTRA_TRADER
                    || tag == Tags.CONTRA_TRADE_QTY || tag == Tags.CONTRA_TRADE_TIME;
            case Tags.NO_MISC_FEES -> tag == Tags.MISC_FEE_AMT || tag == Tags.MISC_FEE_CURR || tag == Tags.MISC_FEE_TYPE;
            case Tags.NO_TRADING_SESSIONS -> tag == Tags.TRADING_SESSION_ID;
            default -> false;
        };
    }

    private static FixField ff(int tag, String value) {
        return FixField.newBuilder().setTag(tag).setValue(value).build();
    }
}
