package com.fix42.oms.dict;

import com.fix42.oms.fix.FixConstants;
import com.fix42.oms.fix.Tags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Programmatic FIX 4.2 subset covering D/8/9/F/G/H/Q plus their repeating groups.
 */
public final class FixDictionary {
    private final Map<Integer, FieldDef> fields;
    private final Map<String, MessageDef> messages;
    private final Map<Integer, GroupDef> groupsByCountTag;

    private FixDictionary(
            Map<Integer, FieldDef> fields,
            Map<String, MessageDef> messages,
            Map<Integer, GroupDef> groupsByCountTag) {
        this.fields = Collections.unmodifiableMap(fields);
        this.messages = Collections.unmodifiableMap(messages);
        this.groupsByCountTag = Collections.unmodifiableMap(groupsByCountTag);
    }

    public FieldDef field(int tag) {
        return fields.get(tag);
    }

    public MessageDef message(String msgType) {
        return messages.get(msgType);
    }

    public GroupDef group(int countTag) {
        return groupsByCountTag.get(countTag);
    }

    public boolean isNumInGroup(int tag) {
        return groupsByCountTag.containsKey(tag);
    }

    public static FixDictionary fix42() {
        return new Builder().standard42().build();
    }

    public static final class Builder {
        private final Map<Integer, FieldDef> fields = new LinkedHashMap<>();
        private final Map<Integer, GroupBuilder> groups = new LinkedHashMap<>();
        private final Map<String, Set<Integer>> messageGroups = new LinkedHashMap<>();

        public Builder field(int tag, String name, FieldType type) {
            fields.put(tag, new FieldDef(tag, name, type));
            return this;
        }

        public GroupBuilder group(int countTag, String name, int delimiterTag) {
            field(countTag, name, FieldType.NUMINGROUP);
            GroupBuilder gb = new GroupBuilder(this, countTag, name, delimiterTag);
            groups.put(countTag, gb);
            return gb;
        }

        public Builder message(String msgType, int... groupCountTags) {
            Set<Integer> set = new LinkedHashSet<>();
            for (int tag : groupCountTags) {
                set.add(tag);
            }
            messageGroups.put(msgType, set);
            return this;
        }

        public Builder standard42() {
            field(Tags.ACCOUNT, "Account", FieldType.STRING);
            field(Tags.AVG_PX, "AvgPx", FieldType.PRICE);
            field(Tags.BEGIN_STRING, "BeginString", FieldType.STRING);
            field(Tags.BODY_LENGTH, "BodyLength", FieldType.INT);
            field(Tags.CHECK_SUM, "CheckSum", FieldType.STRING);
            field(Tags.CL_ORD_ID, "ClOrdID", FieldType.STRING);
            field(Tags.CUM_QTY, "CumQty", FieldType.QTY);
            field(Tags.EXEC_ID, "ExecID", FieldType.STRING);
            field(Tags.EXEC_REF_ID, "ExecRefID", FieldType.STRING);
            field(Tags.EXEC_TRANS_TYPE, "ExecTransType", FieldType.CHAR);
            field(Tags.HANDL_INST, "HandlInst", FieldType.CHAR);
            field(Tags.LAST_PX, "LastPx", FieldType.PRICE);
            field(Tags.LAST_SHARES, "LastShares", FieldType.QTY);
            field(Tags.MSG_SEQ_NUM, "MsgSeqNum", FieldType.INT);
            field(Tags.MSG_TYPE, "MsgType", FieldType.STRING);
            field(Tags.ORDER_ID, "OrderID", FieldType.STRING);
            field(Tags.ORDER_QTY, "OrderQty", FieldType.QTY);
            field(Tags.ORD_STATUS, "OrdStatus", FieldType.CHAR);
            field(Tags.ORD_TYPE, "OrdType", FieldType.CHAR);
            field(Tags.ORIG_CL_ORD_ID, "OrigClOrdID", FieldType.STRING);
            field(Tags.PRICE, "Price", FieldType.PRICE);
            field(Tags.SECURITY_ID, "SecurityID", FieldType.STRING);
            field(Tags.SENDER_COMP_ID, "SenderCompID", FieldType.STRING);
            field(Tags.SENDING_TIME, "SendingTime", FieldType.UTCTIMESTAMP);
            field(Tags.SIDE, "Side", FieldType.CHAR);
            field(Tags.SYMBOL, "Symbol", FieldType.STRING);
            field(Tags.TARGET_COMP_ID, "TargetCompID", FieldType.STRING);
            field(Tags.TEXT, "Text", FieldType.STRING);
            field(Tags.TIME_IN_FORCE, "TimeInForce", FieldType.CHAR);
            field(Tags.TRANSACT_TIME, "TransactTime", FieldType.UTCTIMESTAMP);
            field(Tags.STOP_PX, "StopPx", FieldType.PRICE);
            field(Tags.CXL_REJ_REASON, "CxlRejReason", FieldType.INT);
            field(Tags.ORD_REJ_REASON, "OrdRejReason", FieldType.INT);
            field(Tags.EXEC_TYPE, "ExecType", FieldType.CHAR);
            field(Tags.LEAVES_QTY, "LeavesQty", FieldType.QTY);
            field(Tags.SECONDARY_ORDER_ID, "SecondaryOrderID", FieldType.STRING);
            field(Tags.CXL_REJ_RESPONSE_TO, "CxlRejResponseTo", FieldType.CHAR);
            field(Tags.DK_REASON, "DKReason", FieldType.CHAR);
            field(Tags.PARENT_ORDER_ID, "ParentOrderID", FieldType.STRING);
            field(Tags.PARENT_CL_ORD_ID, "ParentClOrdID", FieldType.STRING);
            field(Tags.ALLOC_ACCOUNT, "AllocAccount", FieldType.STRING);
            field(Tags.ALLOC_SHARES, "AllocShares", FieldType.QTY);
            field(Tags.ALLOC_PRICE, "AllocPrice", FieldType.PRICE);
            field(Tags.CONTRA_BROKER, "ContraBroker", FieldType.STRING);
            field(Tags.CONTRA_TRADER, "ContraTrader", FieldType.STRING);
            field(Tags.CONTRA_TRADE_QTY, "ContraTradeQty", FieldType.QTY);
            field(Tags.CONTRA_TRADE_TIME, "ContraTradeTime", FieldType.UTCTIMESTAMP);
            field(Tags.MISC_FEE_AMT, "MiscFeeAmt", FieldType.PRICE);
            field(Tags.MISC_FEE_CURR, "MiscFeeCurr", FieldType.STRING);
            field(Tags.MISC_FEE_TYPE, "MiscFeeType", FieldType.CHAR);
            field(Tags.TRADING_SESSION_ID, "TradingSessionID", FieldType.STRING);

            group(Tags.NO_MISC_FEES, "NoMiscFees", Tags.MISC_FEE_AMT)
                    .field(Tags.MISC_FEE_AMT)
                    .field(Tags.MISC_FEE_CURR)
                    .field(Tags.MISC_FEE_TYPE);

            group(Tags.NO_ALLOCS, "NoAllocs", Tags.ALLOC_ACCOUNT)
                    .field(Tags.ALLOC_ACCOUNT)
                    .field(Tags.ALLOC_SHARES)
                    .field(Tags.ALLOC_PRICE)
                    .field(Tags.PROCESS_CODE)
                    .nested(Tags.NO_MISC_FEES);

            group(Tags.NO_CONTRA_BROKERS, "NoContraBrokers", Tags.CONTRA_BROKER)
                    .field(Tags.CONTRA_BROKER)
                    .field(Tags.CONTRA_TRADER)
                    .field(Tags.CONTRA_TRADE_QTY)
                    .field(Tags.CONTRA_TRADE_TIME);

            group(Tags.NO_TRADING_SESSIONS, "NoTradingSessions", Tags.TRADING_SESSION_ID)
                    .field(Tags.TRADING_SESSION_ID);

            message(FixConstants.MSG_NEW_ORDER_SINGLE, Tags.NO_ALLOCS, Tags.NO_TRADING_SESSIONS);
            message(FixConstants.MSG_EXECUTION_REPORT, Tags.NO_CONTRA_BROKERS, Tags.NO_MISC_FEES, Tags.NO_ALLOCS);
            message(FixConstants.MSG_ORDER_CANCEL_REJECT);
            message(FixConstants.MSG_ORDER_CANCEL_REQUEST);
            message(FixConstants.MSG_ORDER_CANCEL_REPLACE, Tags.NO_ALLOCS, Tags.NO_TRADING_SESSIONS);
            message(FixConstants.MSG_ORDER_STATUS_REQUEST);
            message(FixConstants.MSG_DONT_KNOW_TRADE);
            return this;
        }

        public FixDictionary build() {
            Map<Integer, GroupDef> builtGroups = new LinkedHashMap<>();
            for (GroupBuilder gb : groups.values()) {
                builtGroups.put(gb.countTag, gb.build(groups));
            }
            Map<String, MessageDef> msgs = new LinkedHashMap<>();
            for (Map.Entry<String, Set<Integer>> e : messageGroups.entrySet()) {
                Map<Integer, GroupDef> msgGroups = new LinkedHashMap<>();
                for (int tag : e.getValue()) {
                    GroupDef def = builtGroups.get(tag);
                    if (def != null) {
                        msgGroups.put(tag, def);
                    }
                }
                msgs.put(e.getKey(), new MessageDef(e.getKey(), e.getKey(), msgGroups));
            }
            return new FixDictionary(new LinkedHashMap<>(fields), msgs, builtGroups);
        }
    }

    public static final class GroupBuilder {
        private final Builder parent;
        private final int countTag;
        private final String name;
        private final int delimiterTag;
        private final Set<Integer> fieldTags = new LinkedHashSet<>();
        private final Set<Integer> nestedCountTags = new LinkedHashSet<>();

        private GroupBuilder(Builder parent, int countTag, String name, int delimiterTag) {
            this.parent = parent;
            this.countTag = countTag;
            this.name = name;
            this.delimiterTag = delimiterTag;
            this.fieldTags.add(delimiterTag);
        }

        public GroupBuilder field(int tag) {
            fieldTags.add(tag);
            return this;
        }

        public GroupBuilder nested(int countTag) {
            nestedCountTags.add(countTag);
            return this;
        }

        public Builder done() {
            return parent;
        }

        public GroupBuilder group(int countTag, String name, int delimiterTag) {
            return parent.group(countTag, name, delimiterTag);
        }

        public Builder message(String msgType, int... groupCountTags) {
            return parent.message(msgType, groupCountTags);
        }

        public Builder standard42() {
            return parent.standard42();
        }

        public FixDictionary build() {
            return parent.build();
        }

        private GroupDef build(Map<Integer, GroupBuilder> all) {
            Map<Integer, GroupDef> nested = new LinkedHashMap<>();
            for (int nestedTag : nestedCountTags) {
                GroupBuilder child = all.get(nestedTag);
                if (child != null) {
                    nested.put(nestedTag, child.build(all));
                }
            }
            return new GroupDef(countTag, name, delimiterTag, fieldTags, nested);
        }
    }
}
