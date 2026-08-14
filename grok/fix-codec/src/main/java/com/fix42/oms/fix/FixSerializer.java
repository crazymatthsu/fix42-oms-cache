package com.fix42.oms.fix;

import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixGroup;
import com.fix42.oms.proto.FixGroupInstance;
import com.fix42.oms.proto.FixHeader;
import com.fix42.oms.proto.FixMessage;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FixSerializer {
    private FixSerializer() {}

    public static String serialize(FixMessage message) {
        return serialize(message, true);
    }

    public static String serialize(FixMessage message, boolean useSoh) {
        char sep = useSoh ? FixConstants.SOH : '|';
        List<String> pairs = new ArrayList<>();
        FixHeader header = message.hasHeader() ? message.getHeader() : FixHeader.getDefaultInstance();
        String begin = header.hasBeginString() ? header.getBeginString() : FixConstants.BEGIN_STRING_42;
        String msgType = header.hasMsgType() ? header.getMsgType() : "";
        if (msgType.isEmpty()) {
            throw new FixParseException("Cannot serialize FIX message without MsgType (35)");
        }

        pairs.add("35=" + msgType);
        if (header.hasSenderCompId()) {
            pairs.add("49=" + header.getSenderCompId());
        }
        if (header.hasTargetCompId()) {
            pairs.add("56=" + header.getTargetCompId());
        }
        if (header.hasMsgSeqNum()) {
            pairs.add("34=" + header.getMsgSeqNum());
        }
        if (header.hasSendingTime()) {
            pairs.add("52=" + header.getSendingTime());
        }
        for (FixField extra : header.getExtraList()) {
            if (extra.getTag() == Tags.BEGIN_STRING
                    || extra.getTag() == Tags.BODY_LENGTH
                    || extra.getTag() == Tags.MSG_TYPE
                    || extra.getTag() == Tags.CHECK_SUM) {
                continue;
            }
            pairs.add(extra.getTag() + "=" + extra.getValue());
        }

        Set<Integer> groupCountTags = new LinkedHashSet<>();
        for (FixGroup group : message.getGroupsList()) {
            groupCountTags.add(group.getNumInGroupTag());
        }
        for (FixField field : message.getFieldsList()) {
            if (field.getTag() == Tags.BEGIN_STRING
                    || field.getTag() == Tags.BODY_LENGTH
                    || field.getTag() == Tags.MSG_TYPE
                    || field.getTag() == Tags.CHECK_SUM
                    || groupCountTags.contains(field.getTag())) {
                continue;
            }
            pairs.add(field.getTag() + "=" + field.getValue());
        }
        for (FixGroup group : message.getGroupsList()) {
            appendGroup(pairs, group);
        }

        String body = String.join(String.valueOf(sep), pairs) + sep;
        byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
        String prefix = "8=" + begin + sep + "9=" + bodyBytes.length + sep;
        String withoutChecksum = prefix + body;
        byte[] all = withoutChecksum.getBytes(StandardCharsets.US_ASCII);
        String checksum = FixSupport.checksum3(FixSupport.checksum(all, all.length));
        return withoutChecksum + "10=" + checksum + sep;
    }

    public static String pipe(FixMessage message) {
        return serialize(message, false);
    }

    private static void appendGroup(List<String> pairs, FixGroup group) {
        pairs.add(group.getNumInGroupTag() + "=" + group.getInstancesCount());
        for (FixGroupInstance instance : group.getInstancesList()) {
            for (FixField field : instance.getFieldsList()) {
                pairs.add(field.getTag() + "=" + field.getValue());
            }
            for (FixGroup nested : instance.getNestedGroupsList()) {
                appendGroup(pairs, nested);
            }
        }
    }
}
