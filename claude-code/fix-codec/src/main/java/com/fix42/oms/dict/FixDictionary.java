package com.fix42.oms.dict;

import com.fix42.oms.fix.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A compact, hand-authored FIX 4.2 data dictionary covering the seven in-scope
 * message types (D, 8, 9, F, G, H, Q), their commonly-used fields, and their
 * repeating groups.
 *
 * <p>This is deliberately not the full FIX42.xml: only what the codec and cache need
 * is modelled. The generic {@code FixMessage} still carries any tag losslessly; the
 * dictionary exists to drive repeating-group parsing and typed mapping.
 *
 * <p>Obtain the shared instance via {@link #fix42()}.
 */
public final class FixDictionary {

    private static final FixDictionary FIX42 = build();

    private final Map<Integer, FieldDef> fields;
    private final Map<String, MessageDef> messages;
    private final Set<Integer> headerTags;
    private final Set<Integer> trailerTags;

    private FixDictionary(Map<Integer, FieldDef> fields,
                          Map<String, MessageDef> messages,
                          Set<Integer> headerTags,
                          Set<Integer> trailerTags) {
        this.fields = Collections.unmodifiableMap(fields);
        this.messages = Collections.unmodifiableMap(messages);
        this.headerTags = Collections.unmodifiableSet(headerTags);
        this.trailerTags = Collections.unmodifiableSet(trailerTags);
    }

    /** The shared FIX 4.2 dictionary instance. */
    public static FixDictionary fix42() {
        return FIX42;
    }

    public Optional<FieldDef> field(int tag) {
        return Optional.ofNullable(fields.get(tag));
    }

    public String fieldName(int tag) {
        FieldDef d = fields.get(tag);
        return d != null ? d.name() : ("Tag" + tag);
    }

    public Optional<MessageDef> message(String msgType) {
        return Optional.ofNullable(messages.get(msgType));
    }

    public boolean isKnownMessage(String msgType) {
        return messages.containsKey(msgType);
    }

    public boolean isHeaderTag(int tag) {
        return headerTags.contains(tag);
    }

    public boolean isTrailerTag(int tag) {
        return trailerTags.contains(tag);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private static FixDictionary build() {
        Map<Integer, FieldDef> f = new LinkedHashMap<>();
        Builder b = new Builder(f);

        // Header
        b.field(Tags.BEGIN_STRING, "BeginString", FieldType.STRING);
        b.field(Tags.BODY_LENGTH, "BodyLength", FieldType.LENGTH);
        b.field(Tags.MSG_TYPE, "MsgType", FieldType.STRING);
        b.field(Tags.MSG_SEQ_NUM, "MsgSeqNum", FieldType.INT);
        b.field(Tags.SENDER_COMP_ID, "SenderCompID", FieldType.STRING);
        b.field(Tags.TARGET_COMP_ID, "TargetCompID", FieldType.STRING);
        b.field(Tags.SENDER_SUB_ID, "SenderSubID", FieldType.STRING);
        b.field(Tags.TARGET_SUB_ID, "TargetSubID", FieldType.STRING);
        b.field(Tags.SENDING_TIME, "SendingTime", FieldType.UTC_TIMESTAMP);
        b.field(Tags.POSS_DUP_FLAG, "PossDupFlag", FieldType.BOOLEAN);
        b.field(Tags.POSS_RESEND, "PossResend", FieldType.BOOLEAN);
        b.field(Tags.ON_BEHALF_OF_COMP_ID, "OnBehalfOfCompID", FieldType.STRING);
        b.field(Tags.DELIVER_TO_COMP_ID, "DeliverToCompID", FieldType.STRING);

        // Trailer
        b.field(Tags.SIGNATURE_LENGTH, "SignatureLength", FieldType.LENGTH);
        b.field(Tags.SIGNATURE, "Signature", FieldType.DATA);
        b.field(Tags.CHECK_SUM, "CheckSum", FieldType.STRING);

        // Identifiers
        b.field(Tags.CL_ORD_ID, "ClOrdID", FieldType.STRING);
        b.field(Tags.ORIG_CL_ORD_ID, "OrigClOrdID", FieldType.STRING);
        b.field(Tags.ORDER_ID, "OrderID", FieldType.STRING);
        b.field(Tags.SECONDARY_ORDER_ID, "SecondaryOrderID", FieldType.STRING);
        b.field(Tags.EXEC_ID, "ExecID", FieldType.STRING);
        b.field(Tags.EXEC_REF_ID, "ExecRefID", FieldType.STRING);
        b.field(Tags.LIST_ID, "ListID", FieldType.STRING);
        b.field(Tags.SECONDARY_CL_ORD_ID, "SecondaryClOrdID", FieldType.STRING);

        // Order terms
        b.field(Tags.ACCOUNT, "Account", FieldType.STRING);
        b.field(Tags.SYMBOL, "Symbol", FieldType.STRING);
        b.field(Tags.SIDE, "Side", FieldType.CHAR);
        b.field(Tags.ORD_TYPE, "OrdType", FieldType.CHAR);
        b.field(Tags.ORDER_QTY, "OrderQty", FieldType.QTY);
        b.field(Tags.PRICE, "Price", FieldType.PRICE);
        b.field(Tags.STOP_PX, "StopPx", FieldType.PRICE);
        b.field(Tags.TIME_IN_FORCE, "TimeInForce", FieldType.CHAR);
        b.field(Tags.CURRENCY, "Currency", FieldType.CURRENCY);
        b.field(Tags.HANDL_INST, "HandlInst", FieldType.CHAR);
        b.field(Tags.EXPIRE_TIME, "ExpireTime", FieldType.UTC_TIMESTAMP);
        b.field(Tags.TRANSACT_TIME, "TransactTime", FieldType.UTC_TIMESTAMP);

        // Execution / lifecycle
        b.field(Tags.ORD_STATUS, "OrdStatus", FieldType.CHAR);
        b.field(Tags.EXEC_TYPE, "ExecType", FieldType.CHAR);
        b.field(Tags.EXEC_TRANS_TYPE, "ExecTransType", FieldType.CHAR);
        b.field(Tags.CUM_QTY, "CumQty", FieldType.QTY);
        b.field(Tags.LEAVES_QTY, "LeavesQty", FieldType.QTY);
        b.field(Tags.AVG_PX, "AvgPx", FieldType.PRICE);
        b.field(Tags.LAST_SHARES, "LastShares", FieldType.QTY);
        b.field(Tags.LAST_PX, "LastPx", FieldType.PRICE);
        b.field(Tags.LAST_MKT, "LastMkt", FieldType.EXCHANGE);

        // Reject / text
        b.field(Tags.TEXT, "Text", FieldType.STRING);
        b.field(Tags.ORD_REJ_REASON, "OrdRejReason", FieldType.INT);
        b.field(Tags.CXL_REJ_REASON, "CxlRejReason", FieldType.INT);
        b.field(Tags.CXL_REJ_RESPONSE_TO, "CxlRejResponseTo", FieldType.CHAR);
        b.field(Tags.DK_REASON, "DKReason", FieldType.CHAR);

        // Group members
        b.field(Tags.NO_ALLOCS, "NoAllocs", FieldType.NUM_IN_GROUP);
        b.field(Tags.ALLOC_ACCOUNT, "AllocAccount", FieldType.STRING);
        b.field(Tags.ALLOC_SHARES, "AllocShares", FieldType.QTY);
        b.field(Tags.NO_CONTRA_BROKERS, "NoContraBrokers", FieldType.NUM_IN_GROUP);
        b.field(Tags.CONTRA_BROKER, "ContraBroker", FieldType.STRING);
        b.field(Tags.CONTRA_TRADER, "ContraTrader", FieldType.STRING);
        b.field(Tags.CONTRA_TRADE_QTY, "ContraTradeQty", FieldType.QTY);
        b.field(Tags.CONTRA_TRADE_TIME, "ContraTradeTime", FieldType.UTC_TIMESTAMP);

        // Encoded / raw data
        b.field(Tags.MESSAGE_ENCODING, "MessageEncoding", FieldType.STRING);
        b.field(Tags.ENCODED_TEXT_LEN, "EncodedTextLen", FieldType.LENGTH);
        b.field(Tags.ENCODED_TEXT, "EncodedText", FieldType.DATA);

        // Repeating groups
        GroupDef allocs = new GroupDef(Tags.NO_ALLOCS, Tags.ALLOC_ACCOUNT,
                List.of(Tags.ALLOC_ACCOUNT, Tags.ALLOC_SHARES));
        GroupDef contraBrokers = new GroupDef(Tags.NO_CONTRA_BROKERS, Tags.CONTRA_BROKER,
                List.of(Tags.CONTRA_BROKER, Tags.CONTRA_TRADER, Tags.CONTRA_TRADE_QTY, Tags.CONTRA_TRADE_TIME));

        Map<String, MessageDef> m = new LinkedHashMap<>();

        // 35=D NewOrderSingle
        m.put("D", new MessageDef("D", "NewOrderSingle",
                tags(Tags.CL_ORD_ID, Tags.ACCOUNT, Tags.HANDL_INST, Tags.SYMBOL, Tags.SIDE,
                        Tags.ORDER_QTY, Tags.ORD_TYPE, Tags.PRICE, Tags.STOP_PX, Tags.TIME_IN_FORCE,
                        Tags.CURRENCY, Tags.TRANSACT_TIME, Tags.TEXT, Tags.SECONDARY_CL_ORD_ID,
                        Tags.NO_ALLOCS, Tags.ALLOC_ACCOUNT, Tags.ALLOC_SHARES),
                List.of(allocs)));

        // 35=8 ExecutionReport
        m.put("8", new MessageDef("8", "ExecutionReport",
                tags(Tags.ORDER_ID, Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.EXEC_ID,
                        Tags.EXEC_TRANS_TYPE, Tags.EXEC_REF_ID, Tags.EXEC_TYPE, Tags.ORD_STATUS,
                        Tags.ACCOUNT, Tags.SYMBOL, Tags.SIDE, Tags.ORDER_QTY, Tags.PRICE,
                        Tags.LAST_SHARES, Tags.LAST_PX, Tags.LAST_MKT, Tags.LEAVES_QTY, Tags.CUM_QTY,
                        Tags.AVG_PX, Tags.CURRENCY, Tags.ORD_REJ_REASON, Tags.TEXT, Tags.TRANSACT_TIME,
                        Tags.SECONDARY_CL_ORD_ID, Tags.NO_CONTRA_BROKERS, Tags.CONTRA_BROKER,
                        Tags.CONTRA_TRADER, Tags.CONTRA_TRADE_QTY, Tags.CONTRA_TRADE_TIME),
                List.of(contraBrokers)));

        // 35=9 OrderCancelReject
        m.put("9", new MessageDef("9", "OrderCancelReject",
                tags(Tags.ORDER_ID, Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.ORD_STATUS,
                        Tags.ACCOUNT, Tags.CXL_REJ_RESPONSE_TO, Tags.CXL_REJ_REASON, Tags.TEXT,
                        Tags.TRANSACT_TIME, Tags.SECONDARY_CL_ORD_ID),
                List.of()));

        // 35=F OrderCancelRequest
        m.put("F", new MessageDef("F", "OrderCancelRequest",
                tags(Tags.ORIG_CL_ORD_ID, Tags.CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.SYMBOL,
                        Tags.SIDE, Tags.ORDER_QTY, Tags.TRANSACT_TIME, Tags.TEXT, Tags.SECONDARY_CL_ORD_ID),
                List.of()));

        // 35=G OrderCancelReplaceRequest
        m.put("G", new MessageDef("G", "OrderCancelReplaceRequest",
                tags(Tags.ORIG_CL_ORD_ID, Tags.CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.HANDL_INST,
                        Tags.SYMBOL, Tags.SIDE, Tags.ORDER_QTY, Tags.ORD_TYPE, Tags.PRICE, Tags.STOP_PX,
                        Tags.TIME_IN_FORCE, Tags.CURRENCY, Tags.TRANSACT_TIME, Tags.TEXT,
                        Tags.SECONDARY_CL_ORD_ID, Tags.NO_ALLOCS, Tags.ALLOC_ACCOUNT, Tags.ALLOC_SHARES),
                List.of(allocs)));

        // 35=H OrderStatusRequest
        m.put("H", new MessageDef("H", "OrderStatusRequest",
                tags(Tags.CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.SYMBOL, Tags.SIDE,
                        Tags.SECONDARY_CL_ORD_ID),
                List.of()));

        // 35=Q DontKnowTrade
        m.put("Q", new MessageDef("Q", "DontKnowTrade",
                tags(Tags.ORDER_ID, Tags.EXEC_ID, Tags.DK_REASON, Tags.SYMBOL, Tags.SIDE,
                        Tags.ORDER_QTY, Tags.LAST_SHARES, Tags.LAST_PX, Tags.TEXT, Tags.SECONDARY_CL_ORD_ID),
                List.of()));

        Set<Integer> header = tags(Tags.BEGIN_STRING, Tags.BODY_LENGTH, Tags.MSG_TYPE, Tags.MSG_SEQ_NUM,
                Tags.SENDER_COMP_ID, Tags.TARGET_COMP_ID, Tags.SENDER_SUB_ID, Tags.TARGET_SUB_ID,
                Tags.SENDING_TIME, Tags.POSS_DUP_FLAG, Tags.POSS_RESEND, Tags.ON_BEHALF_OF_COMP_ID,
                Tags.DELIVER_TO_COMP_ID);
        Set<Integer> trailer = tags(Tags.SIGNATURE_LENGTH, Tags.SIGNATURE, Tags.CHECK_SUM);

        return new FixDictionary(f, m, header, trailer);
    }

    private static Set<Integer> tags(int... t) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int x : t) {
            s.add(x);
        }
        return s;
    }

    private static final class Builder {
        private final Map<Integer, FieldDef> fields;

        Builder(Map<Integer, FieldDef> fields) {
            this.fields = fields;
        }

        void field(int tag, String name, FieldType type) {
            fields.put(tag, new FieldDef(tag, name, type));
        }
    }

    /** All modelled message types, for diagnostics/tests. */
    public List<String> knownMessageTypes() {
        return new ArrayList<>(messages.keySet());
    }
}
