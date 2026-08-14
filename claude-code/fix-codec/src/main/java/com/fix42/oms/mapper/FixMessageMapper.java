package com.fix42.oms.mapper;

import com.fix42.oms.dict.FixCodes;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.Alloc;
import com.fix42.oms.proto.ContraBroker;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixHeader;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.FixTrailer;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderStatusRequest;

import java.util.List;

/**
 * Dictionary-informed mapping between the generic {@link FixMessage} and the typed
 * protobuf views of the seven in-scope message types.
 *
 * <p>The typed layer is a convenience/round-trip view. It normalizes numeric formatting
 * (qty/price become doubles) and, because proto3 scalars have no field presence, only
 * re-emits numeric fields whose value is non-zero, string/enum fields that are set. The
 * generic {@code FixMessage} remains the lossless carrier for exact round-trips.
 */
public final class FixMessageMapper {

    // ---------------------------------------------------------------------
    // Header / trailer
    // ---------------------------------------------------------------------

    public static FixHeader toHeader(FixMessage msg) {
        FixHeader.Builder h = FixHeader.newBuilder();
        setIf(FixSupport.firstValue(msg, Tags.BEGIN_STRING), h::setBeginString);
        h.setBodyLength(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.BODY_LENGTH), 0));
        setIf(FixSupport.firstValue(msg, Tags.MSG_TYPE), h::setMsgType);
        setIf(FixSupport.firstValue(msg, Tags.SENDER_COMP_ID), h::setSenderCompId);
        setIf(FixSupport.firstValue(msg, Tags.TARGET_COMP_ID), h::setTargetCompId);
        h.setMsgSeqNum(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.MSG_SEQ_NUM), 0));
        setIf(FixSupport.firstValue(msg, Tags.SENDING_TIME), h::setSendingTime);
        h.setPossDupFlag("Y".equals(FixSupport.firstValue(msg, Tags.POSS_DUP_FLAG)));
        h.setPossResend("Y".equals(FixSupport.firstValue(msg, Tags.POSS_RESEND)));
        setIf(FixSupport.firstValue(msg, Tags.ON_BEHALF_OF_COMP_ID), h::setOnBehalfOfCompId);
        setIf(FixSupport.firstValue(msg, Tags.DELIVER_TO_COMP_ID), h::setDeliverToCompId);
        return h.build();
    }

    public static FixTrailer toTrailer(FixMessage msg) {
        FixTrailer.Builder t = FixTrailer.newBuilder();
        setIf(FixSupport.firstValue(msg, Tags.SIGNATURE), t::setSignature);
        setIf(FixSupport.firstValue(msg, Tags.CHECK_SUM), t::setCheckSum);
        return t.build();
    }

    private static void appendHeader(FixMessage.Builder b, FixHeader h, String msgType) {
        add(b, Tags.BEGIN_STRING, h.getBeginString().isEmpty() ? "FIX.4.2" : h.getBeginString());
        add(b, Tags.MSG_TYPE, msgType);
        if (h.getMsgSeqNum() > 0) {
            add(b, Tags.MSG_SEQ_NUM, Long.toString(h.getMsgSeqNum()));
        }
        addIf(b, Tags.SENDER_COMP_ID, h.getSenderCompId());
        addIf(b, Tags.TARGET_COMP_ID, h.getTargetCompId());
        addIf(b, Tags.SENDING_TIME, h.getSendingTime());
        if (h.getPossDupFlag()) {
            add(b, Tags.POSS_DUP_FLAG, "Y");
        }
        if (h.getPossResend()) {
            add(b, Tags.POSS_RESEND, "Y");
        }
        addIf(b, Tags.ON_BEHALF_OF_COMP_ID, h.getOnBehalfOfCompId());
        addIf(b, Tags.DELIVER_TO_COMP_ID, h.getDeliverToCompId());
    }

    private static void appendTrailer(FixMessage.Builder b, FixTrailer t) {
        addIf(b, Tags.SIGNATURE, t.getSignature());
        // CheckSum is recomputed by the serializer; do not emit a stale one here.
    }

    // ---------------------------------------------------------------------
    // 35=D NewOrderSingle
    // ---------------------------------------------------------------------

    public static NewOrderSingle toNewOrderSingle(FixMessage msg) {
        NewOrderSingle.Builder o = NewOrderSingle.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        setIf(FixSupport.firstValue(msg, Tags.HANDL_INST), o::setHandlInst);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setOrderQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.ORDER_QTY), 0));
        o.setOrdType(FixCodes.ordTypeFromCode(FixSupport.firstValue(msg, Tags.ORD_TYPE)));
        o.setPrice(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.PRICE), 0));
        o.setStopPx(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.STOP_PX), 0));
        o.setTimeInForce(FixCodes.timeInForceFromCode(FixSupport.firstValue(msg, Tags.TIME_IN_FORCE)));
        setIf(FixSupport.firstValue(msg, Tags.CURRENCY), o::setCurrency);
        setIf(FixSupport.firstValue(msg, Tags.TRANSACT_TIME), o::setTransactTime);
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        for (List<FixField> e : Groups.extract(msg, com.fix42.oms.dict.FixDictionary.fix42()
                .message("D").orElseThrow().groups().get(0))) {
            o.addAllocs(toAlloc(e));
        }
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromNewOrderSingle(NewOrderSingle o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "D");
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.HANDL_INST, o.getHandlInst());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        addNum(b, Tags.ORDER_QTY, o.getOrderQty());
        addEnum(b, Tags.ORD_TYPE, FixCodes.codeFor(o.getOrdType()));
        addNum(b, Tags.PRICE, o.getPrice());
        addNum(b, Tags.STOP_PX, o.getStopPx());
        addEnum(b, Tags.TIME_IN_FORCE, FixCodes.codeFor(o.getTimeInForce()));
        addIf(b, Tags.CURRENCY, o.getCurrency());
        addIf(b, Tags.TRANSACT_TIME, o.getTransactTime());
        addIf(b, Tags.TEXT, o.getText());
        appendAllocs(b, o.getAllocsList());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=8 ExecutionReport
    // ---------------------------------------------------------------------

    public static ExecutionReport toExecutionReport(FixMessage msg) {
        ExecutionReport.Builder o = ExecutionReport.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID), o::setOrigClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.EXEC_ID), o::setExecId);
        o.setExecTransType(FixCodes.execTransTypeFromCode(FixSupport.firstValue(msg, Tags.EXEC_TRANS_TYPE)));
        setIf(FixSupport.firstValue(msg, Tags.EXEC_REF_ID), o::setExecRefId);
        o.setExecType(FixCodes.execTypeFromCode(FixSupport.firstValue(msg, Tags.EXEC_TYPE)));
        o.setOrdStatus(FixCodes.ordStatusFromCode(FixSupport.firstValue(msg, Tags.ORD_STATUS)));
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setOrderQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.ORDER_QTY), 0));
        o.setPrice(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.PRICE), 0));
        o.setLastQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.LAST_SHARES), 0));
        o.setLastPx(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.LAST_PX), 0));
        setIf(FixSupport.firstValue(msg, Tags.LAST_MKT), o::setLastMarket);
        o.setLeavesQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.LEAVES_QTY), 0));
        o.setCumQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.CUM_QTY), 0));
        o.setAvgPx(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.AVG_PX), 0));
        setIf(FixSupport.firstValue(msg, Tags.CURRENCY), o::setCurrency);
        o.setOrdRejReason(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.ORD_REJ_REASON), 0));
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        setIf(FixSupport.firstValue(msg, Tags.TRANSACT_TIME), o::setTransactTime);
        for (List<FixField> e : Groups.extract(msg, com.fix42.oms.dict.FixDictionary.fix42()
                .message("8").orElseThrow().groups().get(0))) {
            o.addContraBrokers(toContraBroker(e));
        }
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromExecutionReport(ExecutionReport o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "8");
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.ORIG_CL_ORD_ID, o.getOrigClOrdId());
        addIf(b, Tags.EXEC_ID, o.getExecId());
        addEnum(b, Tags.EXEC_TRANS_TYPE, FixCodes.codeFor(o.getExecTransType()));
        addIf(b, Tags.EXEC_REF_ID, o.getExecRefId());
        addEnum(b, Tags.EXEC_TYPE, FixCodes.codeFor(o.getExecType()));
        addEnum(b, Tags.ORD_STATUS, FixCodes.codeFor(o.getOrdStatus()));
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        addNum(b, Tags.ORDER_QTY, o.getOrderQty());
        addNum(b, Tags.PRICE, o.getPrice());
        addNum(b, Tags.LAST_SHARES, o.getLastQty());
        addNum(b, Tags.LAST_PX, o.getLastPx());
        addIf(b, Tags.LAST_MKT, o.getLastMarket());
        addNum(b, Tags.LEAVES_QTY, o.getLeavesQty());
        addNum(b, Tags.CUM_QTY, o.getCumQty());
        addNum(b, Tags.AVG_PX, o.getAvgPx());
        addIf(b, Tags.CURRENCY, o.getCurrency());
        if (o.getOrdRejReason() != 0) {
            add(b, Tags.ORD_REJ_REASON, Integer.toString(o.getOrdRejReason()));
        }
        addIf(b, Tags.TEXT, o.getText());
        addIf(b, Tags.TRANSACT_TIME, o.getTransactTime());
        appendContraBrokers(b, o.getContraBrokersList());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=9 OrderCancelReject
    // ---------------------------------------------------------------------

    public static OrderCancelReject toOrderCancelReject(FixMessage msg) {
        OrderCancelReject.Builder o = OrderCancelReject.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID), o::setOrigClOrdId);
        o.setOrdStatus(FixCodes.ordStatusFromCode(FixSupport.firstValue(msg, Tags.ORD_STATUS)));
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        o.setCxlRejResponseTo(FixCodes.cxlRejResponseToFromCode(FixSupport.firstValue(msg, Tags.CXL_REJ_RESPONSE_TO)));
        o.setCxlRejReason(FixSupport.parseInt(FixSupport.firstValue(msg, Tags.CXL_REJ_REASON), 0));
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        setIf(FixSupport.firstValue(msg, Tags.TRANSACT_TIME), o::setTransactTime);
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromOrderCancelReject(OrderCancelReject o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "9");
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.ORIG_CL_ORD_ID, o.getOrigClOrdId());
        addEnum(b, Tags.ORD_STATUS, FixCodes.codeFor(o.getOrdStatus()));
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addEnum(b, Tags.CXL_REJ_RESPONSE_TO, FixCodes.codeFor(o.getCxlRejResponseTo()));
        if (o.getCxlRejReason() != 0) {
            add(b, Tags.CXL_REJ_REASON, Integer.toString(o.getCxlRejReason()));
        }
        addIf(b, Tags.TEXT, o.getText());
        addIf(b, Tags.TRANSACT_TIME, o.getTransactTime());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=F OrderCancelRequest
    // ---------------------------------------------------------------------

    public static OrderCancelRequest toOrderCancelRequest(FixMessage msg) {
        OrderCancelRequest.Builder o = OrderCancelRequest.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID), o::setOrigClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setOrderQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.ORDER_QTY), 0));
        setIf(FixSupport.firstValue(msg, Tags.TRANSACT_TIME), o::setTransactTime);
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromOrderCancelRequest(OrderCancelRequest o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "F");
        addIf(b, Tags.ORIG_CL_ORD_ID, o.getOrigClOrdId());
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        addNum(b, Tags.ORDER_QTY, o.getOrderQty());
        addIf(b, Tags.TRANSACT_TIME, o.getTransactTime());
        addIf(b, Tags.TEXT, o.getText());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=G OrderCancelReplaceRequest
    // ---------------------------------------------------------------------

    public static OrderCancelReplaceRequest toOrderCancelReplaceRequest(FixMessage msg) {
        OrderCancelReplaceRequest.Builder o = OrderCancelReplaceRequest.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID), o::setOrigClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        setIf(FixSupport.firstValue(msg, Tags.HANDL_INST), o::setHandlInst);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setOrderQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.ORDER_QTY), 0));
        o.setOrdType(FixCodes.ordTypeFromCode(FixSupport.firstValue(msg, Tags.ORD_TYPE)));
        o.setPrice(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.PRICE), 0));
        o.setStopPx(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.STOP_PX), 0));
        o.setTimeInForce(FixCodes.timeInForceFromCode(FixSupport.firstValue(msg, Tags.TIME_IN_FORCE)));
        setIf(FixSupport.firstValue(msg, Tags.CURRENCY), o::setCurrency);
        setIf(FixSupport.firstValue(msg, Tags.TRANSACT_TIME), o::setTransactTime);
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        for (List<FixField> e : Groups.extract(msg, com.fix42.oms.dict.FixDictionary.fix42()
                .message("G").orElseThrow().groups().get(0))) {
            o.addAllocs(toAlloc(e));
        }
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromOrderCancelReplaceRequest(OrderCancelReplaceRequest o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "G");
        addIf(b, Tags.ORIG_CL_ORD_ID, o.getOrigClOrdId());
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addIf(b, Tags.HANDL_INST, o.getHandlInst());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        addNum(b, Tags.ORDER_QTY, o.getOrderQty());
        addEnum(b, Tags.ORD_TYPE, FixCodes.codeFor(o.getOrdType()));
        addNum(b, Tags.PRICE, o.getPrice());
        addNum(b, Tags.STOP_PX, o.getStopPx());
        addEnum(b, Tags.TIME_IN_FORCE, FixCodes.codeFor(o.getTimeInForce()));
        addIf(b, Tags.CURRENCY, o.getCurrency());
        addIf(b, Tags.TRANSACT_TIME, o.getTransactTime());
        addIf(b, Tags.TEXT, o.getText());
        appendAllocs(b, o.getAllocsList());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=H OrderStatusRequest
    // ---------------------------------------------------------------------

    public static OrderStatusRequest toOrderStatusRequest(FixMessage msg) {
        OrderStatusRequest.Builder o = OrderStatusRequest.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.CL_ORD_ID), o::setClOrdId);
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.ACCOUNT), o::setAccount);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromOrderStatusRequest(OrderStatusRequest o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "H");
        addIf(b, Tags.CL_ORD_ID, o.getClOrdId());
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.ACCOUNT, o.getAccount());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // 35=Q DontKnowTrade
    // ---------------------------------------------------------------------

    public static DontKnowTrade toDontKnowTrade(FixMessage msg) {
        DontKnowTrade.Builder o = DontKnowTrade.newBuilder();
        o.setHeader(toHeader(msg));
        setIf(FixSupport.firstValue(msg, Tags.ORDER_ID), o::setOrderId);
        setIf(FixSupport.firstValue(msg, Tags.EXEC_ID), o::setExecId);
        setIf(FixSupport.firstValue(msg, Tags.DK_REASON), o::setDkReason);
        setIf(FixSupport.firstValue(msg, Tags.SYMBOL), o::setSymbol);
        o.setSide(FixCodes.sideFromCode(FixSupport.firstValue(msg, Tags.SIDE)));
        o.setOrderQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.ORDER_QTY), 0));
        o.setLastQty(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.LAST_SHARES), 0));
        o.setLastPx(FixSupport.parseDouble(FixSupport.firstValue(msg, Tags.LAST_PX), 0));
        setIf(FixSupport.firstValue(msg, Tags.TEXT), o::setText);
        o.setTrailer(toTrailer(msg));
        return o.build();
    }

    public static FixMessage fromDontKnowTrade(DontKnowTrade o) {
        FixMessage.Builder b = FixMessage.newBuilder();
        appendHeader(b, o.getHeader(), "Q");
        addIf(b, Tags.ORDER_ID, o.getOrderId());
        addIf(b, Tags.EXEC_ID, o.getExecId());
        addIf(b, Tags.DK_REASON, o.getDkReason());
        addIf(b, Tags.SYMBOL, o.getSymbol());
        addEnum(b, Tags.SIDE, FixCodes.codeFor(o.getSide()));
        addNum(b, Tags.ORDER_QTY, o.getOrderQty());
        addNum(b, Tags.LAST_SHARES, o.getLastQty());
        addNum(b, Tags.LAST_PX, o.getLastPx());
        addIf(b, Tags.TEXT, o.getText());
        appendTrailer(b, o.getTrailer());
        return b.build();
    }

    // ---------------------------------------------------------------------
    // Group element mapping
    // ---------------------------------------------------------------------

    private static Alloc toAlloc(List<FixField> entry) {
        Alloc.Builder a = Alloc.newBuilder();
        setIf(Groups.value(entry, Tags.ALLOC_ACCOUNT), a::setAllocAccount);
        a.setAllocShares(FixSupport.parseDouble(Groups.value(entry, Tags.ALLOC_SHARES), 0));
        return a.build();
    }

    private static void appendAllocs(FixMessage.Builder b, List<Alloc> allocs) {
        if (allocs.isEmpty()) {
            return;
        }
        add(b, Tags.NO_ALLOCS, Integer.toString(allocs.size()));
        for (Alloc a : allocs) {
            add(b, Tags.ALLOC_ACCOUNT, a.getAllocAccount()); // delimiter, always emitted
            addNum(b, Tags.ALLOC_SHARES, a.getAllocShares());
        }
    }

    private static ContraBroker toContraBroker(List<FixField> entry) {
        ContraBroker.Builder c = ContraBroker.newBuilder();
        setIf(Groups.value(entry, Tags.CONTRA_BROKER), c::setContraBroker);
        setIf(Groups.value(entry, Tags.CONTRA_TRADER), c::setContraTrader);
        c.setContraTradeQty(FixSupport.parseDouble(Groups.value(entry, Tags.CONTRA_TRADE_QTY), 0));
        setIf(Groups.value(entry, Tags.CONTRA_TRADE_TIME), c::setContraTradeTime);
        return c.build();
    }

    private static void appendContraBrokers(FixMessage.Builder b, List<ContraBroker> list) {
        if (list.isEmpty()) {
            return;
        }
        add(b, Tags.NO_CONTRA_BROKERS, Integer.toString(list.size()));
        for (ContraBroker c : list) {
            add(b, Tags.CONTRA_BROKER, c.getContraBroker()); // delimiter, always emitted
            addIf(b, Tags.CONTRA_TRADER, c.getContraTrader());
            addNum(b, Tags.CONTRA_TRADE_QTY, c.getContraTradeQty());
            addIf(b, Tags.CONTRA_TRADE_TIME, c.getContraTradeTime());
        }
    }

    // ---------------------------------------------------------------------
    // small helpers
    // ---------------------------------------------------------------------

    private interface StrSetter {
        void accept(String s);
    }

    private static void setIf(String value, StrSetter setter) {
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    private static void add(FixMessage.Builder b, int tag, String value) {
        b.addFields(FixField.newBuilder().setTag(tag).setValue(value == null ? "" : value).build());
    }

    private static void addIf(FixMessage.Builder b, int tag, String value) {
        if (value != null && !value.isEmpty()) {
            add(b, tag, value);
        }
    }

    private static void addEnum(FixMessage.Builder b, int tag, String code) {
        if (code != null && !code.isEmpty()) {
            add(b, tag, code);
        }
    }

    private static void addNum(FixMessage.Builder b, int tag, double value) {
        if (value != 0.0) {
            add(b, tag, fmt(value));
        }
    }

    /** Format a double as FIX would: integers without a decimal point. */
    static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private FixMessageMapper() {
    }
}
