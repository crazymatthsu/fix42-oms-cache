package com.fix42.oms.mapper;

import com.fix42.oms.fix.FixConstants;
import com.fix42.oms.fix.FixParseException;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.Alloc;
import com.fix42.oms.proto.ContraBroker;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixGroup;
import com.fix42.oms.proto.FixGroupInstance;
import com.fix42.oms.proto.FixHeader;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.MiscFee;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderStatusRequest;
import com.fix42.oms.proto.TradingSession;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Projects a generic {@link FixMessage} onto the seven in-scope typed messages and back.
 */
public final class FixMessageMapper {
    private FixMessageMapper() {}

    public static String msgTypeOf(FixMessage message) {
        if (message.hasHeader() && message.getHeader().hasMsgType()) {
            return message.getHeader().getMsgType();
        }
        return field(message, Tags.MSG_TYPE);
    }

    public static Object toTyped(FixMessage message) {
        String msgType = msgTypeOf(message);
        if (msgType == null) {
            throw new FixParseException("Cannot map FIX message without MsgType");
        }
        return switch (msgType) {
            case FixConstants.MSG_NEW_ORDER_SINGLE -> toNewOrderSingle(message);
            case FixConstants.MSG_EXECUTION_REPORT -> toExecutionReport(message);
            case FixConstants.MSG_ORDER_CANCEL_REJECT -> toOrderCancelReject(message);
            case FixConstants.MSG_ORDER_CANCEL_REQUEST -> toOrderCancelRequest(message);
            case FixConstants.MSG_ORDER_CANCEL_REPLACE -> toOrderCancelReplaceRequest(message);
            case FixConstants.MSG_ORDER_STATUS_REQUEST -> toOrderStatusRequest(message);
            case FixConstants.MSG_DONT_KNOW_TRADE -> toDontKnowTrade(message);
            default -> throw new UnsupportedMessageTypeException(msgType);
        };
    }

    public static NewOrderSingle toNewOrderSingle(FixMessage m) {
        NewOrderSingle.Builder b = NewOrderSingle.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.HANDL_INST, b::setHandlInst);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SECURITY_ID, b::setSecurityId);
        setStr(m, Tags.SIDE, b::setSide);
        setStr(m, Tags.TRANSACT_TIME, b::setTransactTime);
        setDbl(m, Tags.ORDER_QTY, b::setOrderQty);
        setStr(m, Tags.ORD_TYPE, b::setOrdType);
        setDbl(m, Tags.PRICE, b::setPrice);
        setDbl(m, Tags.STOP_PX, b::setStopPx);
        setStr(m, Tags.TIME_IN_FORCE, b::setTimeInForce);
        setStr(m, Tags.TEXT, b::setText);
        setStr(m, Tags.PARENT_ORDER_ID, b::setParentOrderId);
        setStr(m, Tags.PARENT_CL_ORD_ID, b::setParentClOrdId);
        b.addAllAllocs(allocs(m));
        b.addAllTradingSessions(sessions(m));
        extras(m, known(
                Tags.CL_ORD_ID, Tags.ACCOUNT, Tags.HANDL_INST, Tags.SYMBOL, Tags.SECURITY_ID,
                Tags.SIDE, Tags.TRANSACT_TIME, Tags.ORDER_QTY, Tags.ORD_TYPE, Tags.PRICE,
                Tags.STOP_PX, Tags.TIME_IN_FORCE, Tags.TEXT, Tags.PARENT_ORDER_ID,
                Tags.PARENT_CL_ORD_ID, Tags.NO_ALLOCS, Tags.NO_TRADING_SESSIONS
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static ExecutionReport toExecutionReport(FixMessage m) {
        ExecutionReport.Builder b = ExecutionReport.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ORIG_CL_ORD_ID, b::setOrigClOrdId);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.SECONDARY_ORDER_ID, b::setSecondaryOrderId);
        setStr(m, Tags.EXEC_ID, b::setExecId);
        setStr(m, Tags.EXEC_REF_ID, b::setExecRefId);
        setStr(m, Tags.EXEC_TRANS_TYPE, b::setExecTransType);
        setStr(m, Tags.EXEC_TYPE, b::setExecType);
        setStr(m, Tags.ORD_STATUS, b::setOrdStatus);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SECURITY_ID, b::setSecurityId);
        setStr(m, Tags.SIDE, b::setSide);
        setDbl(m, Tags.ORDER_QTY, b::setOrderQty);
        setStr(m, Tags.ORD_TYPE, b::setOrdType);
        setDbl(m, Tags.PRICE, b::setPrice);
        setDbl(m, Tags.STOP_PX, b::setStopPx);
        setStr(m, Tags.TIME_IN_FORCE, b::setTimeInForce);
        setDbl(m, Tags.LAST_SHARES, b::setLastShares);
        setDbl(m, Tags.LAST_PX, b::setLastPx);
        setDbl(m, Tags.LEAVES_QTY, b::setLeavesQty);
        setDbl(m, Tags.CUM_QTY, b::setCumQty);
        setDbl(m, Tags.AVG_PX, b::setAvgPx);
        setStr(m, Tags.TRANSACT_TIME, b::setTransactTime);
        setStr(m, Tags.TEXT, b::setText);
        setStr(m, Tags.ORD_REJ_REASON, b::setOrdRejReason);
        setStr(m, Tags.PARENT_ORDER_ID, b::setParentOrderId);
        setStr(m, Tags.PARENT_CL_ORD_ID, b::setParentClOrdId);
        b.addAllContraBrokers(contras(m));
        b.addAllMiscFees(fees(m));
        b.addAllAllocs(allocs(m));
        extras(m, known(
                Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.ORDER_ID, Tags.SECONDARY_ORDER_ID,
                Tags.EXEC_ID, Tags.EXEC_REF_ID, Tags.EXEC_TRANS_TYPE, Tags.EXEC_TYPE,
                Tags.ORD_STATUS, Tags.ACCOUNT, Tags.SYMBOL, Tags.SECURITY_ID, Tags.SIDE,
                Tags.ORDER_QTY, Tags.ORD_TYPE, Tags.PRICE, Tags.STOP_PX, Tags.TIME_IN_FORCE,
                Tags.LAST_SHARES, Tags.LAST_PX, Tags.LEAVES_QTY, Tags.CUM_QTY, Tags.AVG_PX,
                Tags.TRANSACT_TIME, Tags.TEXT, Tags.ORD_REJ_REASON, Tags.PARENT_ORDER_ID,
                Tags.PARENT_CL_ORD_ID, Tags.NO_CONTRA_BROKERS, Tags.NO_MISC_FEES, Tags.NO_ALLOCS
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static OrderCancelReject toOrderCancelReject(FixMessage m) {
        OrderCancelReject.Builder b = OrderCancelReject.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ORIG_CL_ORD_ID, b::setOrigClOrdId);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.ORD_STATUS, b::setOrdStatus);
        setStr(m, Tags.CXL_REJ_RESPONSE_TO, b::setCxlRejResponseTo);
        setStr(m, Tags.CXL_REJ_REASON, b::setCxlRejReason);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.TEXT, b::setText);
        setStr(m, Tags.TRANSACT_TIME, b::setTransactTime);
        extras(m, known(
                Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.ORDER_ID, Tags.ORD_STATUS,
                Tags.CXL_REJ_RESPONSE_TO, Tags.CXL_REJ_REASON, Tags.ACCOUNT, Tags.TEXT,
                Tags.TRANSACT_TIME
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static OrderCancelRequest toOrderCancelRequest(FixMessage m) {
        OrderCancelRequest.Builder b = OrderCancelRequest.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ORIG_CL_ORD_ID, b::setOrigClOrdId);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SIDE, b::setSide);
        setDbl(m, Tags.ORDER_QTY, b::setOrderQty);
        setStr(m, Tags.TRANSACT_TIME, b::setTransactTime);
        setStr(m, Tags.TEXT, b::setText);
        setStr(m, Tags.PARENT_ORDER_ID, b::setParentOrderId);
        setStr(m, Tags.PARENT_CL_ORD_ID, b::setParentClOrdId);
        extras(m, known(
                Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.SYMBOL,
                Tags.SIDE, Tags.ORDER_QTY, Tags.TRANSACT_TIME, Tags.TEXT,
                Tags.PARENT_ORDER_ID, Tags.PARENT_CL_ORD_ID
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static OrderCancelReplaceRequest toOrderCancelReplaceRequest(FixMessage m) {
        OrderCancelReplaceRequest.Builder b = OrderCancelReplaceRequest.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ORIG_CL_ORD_ID, b::setOrigClOrdId);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.HANDL_INST, b::setHandlInst);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SIDE, b::setSide);
        setStr(m, Tags.TRANSACT_TIME, b::setTransactTime);
        setDbl(m, Tags.ORDER_QTY, b::setOrderQty);
        setStr(m, Tags.ORD_TYPE, b::setOrdType);
        setDbl(m, Tags.PRICE, b::setPrice);
        setDbl(m, Tags.STOP_PX, b::setStopPx);
        setStr(m, Tags.TIME_IN_FORCE, b::setTimeInForce);
        setStr(m, Tags.TEXT, b::setText);
        setStr(m, Tags.PARENT_ORDER_ID, b::setParentOrderId);
        setStr(m, Tags.PARENT_CL_ORD_ID, b::setParentClOrdId);
        b.addAllAllocs(allocs(m));
        b.addAllTradingSessions(sessions(m));
        extras(m, known(
                Tags.CL_ORD_ID, Tags.ORIG_CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.HANDL_INST,
                Tags.SYMBOL, Tags.SIDE, Tags.TRANSACT_TIME, Tags.ORDER_QTY, Tags.ORD_TYPE,
                Tags.PRICE, Tags.STOP_PX, Tags.TIME_IN_FORCE, Tags.TEXT, Tags.PARENT_ORDER_ID,
                Tags.PARENT_CL_ORD_ID, Tags.NO_ALLOCS, Tags.NO_TRADING_SESSIONS
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static OrderStatusRequest toOrderStatusRequest(FixMessage m) {
        OrderStatusRequest.Builder b = OrderStatusRequest.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.CL_ORD_ID, b::setClOrdId);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.ACCOUNT, b::setAccount);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SIDE, b::setSide);
        extras(m, known(Tags.CL_ORD_ID, Tags.ORDER_ID, Tags.ACCOUNT, Tags.SYMBOL, Tags.SIDE))
                .forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static DontKnowTrade toDontKnowTrade(FixMessage m) {
        DontKnowTrade.Builder b = DontKnowTrade.newBuilder();
        copyHeader(m, b::setHeader);
        setStr(m, Tags.ORDER_ID, b::setOrderId);
        setStr(m, Tags.EXEC_ID, b::setExecId);
        setStr(m, Tags.DK_REASON, b::setDkReason);
        setStr(m, Tags.SYMBOL, b::setSymbol);
        setStr(m, Tags.SIDE, b::setSide);
        setDbl(m, Tags.ORDER_QTY, b::setOrderQty);
        setDbl(m, Tags.LAST_SHARES, b::setLastShares);
        setDbl(m, Tags.LAST_PX, b::setLastPx);
        setStr(m, Tags.TEXT, b::setText);
        extras(m, known(
                Tags.ORDER_ID, Tags.EXEC_ID, Tags.DK_REASON, Tags.SYMBOL, Tags.SIDE,
                Tags.ORDER_QTY, Tags.LAST_SHARES, Tags.LAST_PX, Tags.TEXT
        )).forEach(b::addExtra);
        if (m.hasRaw()) {
            b.setRaw(m.getRaw());
        }
        return b.build();
    }

    public static FixMessage fromNewOrderSingle(NewOrderSingle m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_NEW_ORDER_SINGLE, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.HANDL_INST, m.hasHandlInst(), m.getHandlInst());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SECURITY_ID, m.hasSecurityId(), m.getSecurityId());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        put(b, Tags.TRANSACT_TIME, m.hasTransactTime(), m.getTransactTime());
        putDbl(b, Tags.ORDER_QTY, m.hasOrderQty(), m.getOrderQty());
        put(b, Tags.ORD_TYPE, m.hasOrdType(), m.getOrdType());
        putDbl(b, Tags.PRICE, m.hasPrice(), m.getPrice());
        putDbl(b, Tags.STOP_PX, m.hasStopPx(), m.getStopPx());
        put(b, Tags.TIME_IN_FORCE, m.hasTimeInForce(), m.getTimeInForce());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        put(b, Tags.PARENT_ORDER_ID, m.hasParentOrderId(), m.getParentOrderId());
        put(b, Tags.PARENT_CL_ORD_ID, m.hasParentClOrdId(), m.getParentClOrdId());
        addAllocGroup(b, m.getAllocsList());
        addSessionGroup(b, m.getTradingSessionsList());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromExecutionReport(ExecutionReport m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_EXECUTION_REPORT, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ORIG_CL_ORD_ID, m.hasOrigClOrdId(), m.getOrigClOrdId());
        put(b, Tags.SECONDARY_ORDER_ID, m.hasSecondaryOrderId(), m.getSecondaryOrderId());
        put(b, Tags.EXEC_ID, m.hasExecId(), m.getExecId());
        put(b, Tags.EXEC_REF_ID, m.hasExecRefId(), m.getExecRefId());
        put(b, Tags.EXEC_TRANS_TYPE, m.hasExecTransType(), m.getExecTransType());
        put(b, Tags.EXEC_TYPE, m.hasExecType(), m.getExecType());
        put(b, Tags.ORD_STATUS, m.hasOrdStatus(), m.getOrdStatus());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SECURITY_ID, m.hasSecurityId(), m.getSecurityId());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        putDbl(b, Tags.ORDER_QTY, m.hasOrderQty(), m.getOrderQty());
        put(b, Tags.ORD_TYPE, m.hasOrdType(), m.getOrdType());
        putDbl(b, Tags.PRICE, m.hasPrice(), m.getPrice());
        putDbl(b, Tags.STOP_PX, m.hasStopPx(), m.getStopPx());
        put(b, Tags.TIME_IN_FORCE, m.hasTimeInForce(), m.getTimeInForce());
        putDbl(b, Tags.LAST_SHARES, m.hasLastShares(), m.getLastShares());
        putDbl(b, Tags.LAST_PX, m.hasLastPx(), m.getLastPx());
        putDbl(b, Tags.LEAVES_QTY, m.hasLeavesQty(), m.getLeavesQty());
        putDbl(b, Tags.CUM_QTY, m.hasCumQty(), m.getCumQty());
        putDbl(b, Tags.AVG_PX, m.hasAvgPx(), m.getAvgPx());
        put(b, Tags.TRANSACT_TIME, m.hasTransactTime(), m.getTransactTime());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        put(b, Tags.ORD_REJ_REASON, m.hasOrdRejReason(), m.getOrdRejReason());
        put(b, Tags.PARENT_ORDER_ID, m.hasParentOrderId(), m.getParentOrderId());
        put(b, Tags.PARENT_CL_ORD_ID, m.hasParentClOrdId(), m.getParentClOrdId());
        addContraGroup(b, m.getContraBrokersList());
        addFeeGroup(b, m.getMiscFeesList());
        addAllocGroup(b, m.getAllocsList());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromOrderCancelReject(OrderCancelReject m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_ORDER_CANCEL_REJECT, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ORIG_CL_ORD_ID, m.hasOrigClOrdId(), m.getOrigClOrdId());
        put(b, Tags.ORD_STATUS, m.hasOrdStatus(), m.getOrdStatus());
        put(b, Tags.CXL_REJ_RESPONSE_TO, m.hasCxlRejResponseTo(), m.getCxlRejResponseTo());
        put(b, Tags.CXL_REJ_REASON, m.hasCxlRejReason(), m.getCxlRejReason());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        put(b, Tags.TRANSACT_TIME, m.hasTransactTime(), m.getTransactTime());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromOrderCancelRequest(OrderCancelRequest m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_ORDER_CANCEL_REQUEST, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ORIG_CL_ORD_ID, m.hasOrigClOrdId(), m.getOrigClOrdId());
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        putDbl(b, Tags.ORDER_QTY, m.hasOrderQty(), m.getOrderQty());
        put(b, Tags.TRANSACT_TIME, m.hasTransactTime(), m.getTransactTime());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        put(b, Tags.PARENT_ORDER_ID, m.hasParentOrderId(), m.getParentOrderId());
        put(b, Tags.PARENT_CL_ORD_ID, m.hasParentClOrdId(), m.getParentClOrdId());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromOrderCancelReplaceRequest(OrderCancelReplaceRequest m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_ORDER_CANCEL_REPLACE, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ORIG_CL_ORD_ID, m.hasOrigClOrdId(), m.getOrigClOrdId());
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.HANDL_INST, m.hasHandlInst(), m.getHandlInst());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        put(b, Tags.TRANSACT_TIME, m.hasTransactTime(), m.getTransactTime());
        putDbl(b, Tags.ORDER_QTY, m.hasOrderQty(), m.getOrderQty());
        put(b, Tags.ORD_TYPE, m.hasOrdType(), m.getOrdType());
        putDbl(b, Tags.PRICE, m.hasPrice(), m.getPrice());
        putDbl(b, Tags.STOP_PX, m.hasStopPx(), m.getStopPx());
        put(b, Tags.TIME_IN_FORCE, m.hasTimeInForce(), m.getTimeInForce());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        put(b, Tags.PARENT_ORDER_ID, m.hasParentOrderId(), m.getParentOrderId());
        put(b, Tags.PARENT_CL_ORD_ID, m.hasParentClOrdId(), m.getParentClOrdId());
        addAllocGroup(b, m.getAllocsList());
        addSessionGroup(b, m.getTradingSessionsList());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromOrderStatusRequest(OrderStatusRequest m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_ORDER_STATUS_REQUEST, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.CL_ORD_ID, m.hasClOrdId(), m.getClOrdId());
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.ACCOUNT, m.hasAccount(), m.getAccount());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromDontKnowTrade(DontKnowTrade m) {
        FixMessage.Builder b = base(m.hasHeader() ? m.getHeader() : null, FixConstants.MSG_DONT_KNOW_TRADE, m.hasRaw() ? m.getRaw() : null);
        put(b, Tags.ORDER_ID, m.hasOrderId(), m.getOrderId());
        put(b, Tags.EXEC_ID, m.hasExecId(), m.getExecId());
        put(b, Tags.DK_REASON, m.hasDkReason(), m.getDkReason());
        put(b, Tags.SYMBOL, m.hasSymbol(), m.getSymbol());
        put(b, Tags.SIDE, m.hasSide(), m.getSide());
        putDbl(b, Tags.ORDER_QTY, m.hasOrderQty(), m.getOrderQty());
        putDbl(b, Tags.LAST_SHARES, m.hasLastShares(), m.getLastShares());
        putDbl(b, Tags.LAST_PX, m.hasLastPx(), m.getLastPx());
        put(b, Tags.TEXT, m.hasText(), m.getText());
        b.addAllFields(m.getExtraList());
        return b.build();
    }

    public static FixMessage fromTyped(Object typed) {
        if (typed instanceof NewOrderSingle m) {
            return fromNewOrderSingle(m);
        }
        if (typed instanceof ExecutionReport m) {
            return fromExecutionReport(m);
        }
        if (typed instanceof OrderCancelReject m) {
            return fromOrderCancelReject(m);
        }
        if (typed instanceof OrderCancelRequest m) {
            return fromOrderCancelRequest(m);
        }
        if (typed instanceof OrderCancelReplaceRequest m) {
            return fromOrderCancelReplaceRequest(m);
        }
        if (typed instanceof OrderStatusRequest m) {
            return fromOrderStatusRequest(m);
        }
        if (typed instanceof DontKnowTrade m) {
            return fromDontKnowTrade(m);
        }
        throw new IllegalArgumentException("Unsupported typed message: " + typed);
    }

    public static class UnsupportedMessageTypeException extends RuntimeException {
        private final String msgType;

        public UnsupportedMessageTypeException(String msgType) {
            super("Unsupported FIX MsgType: " + msgType);
            this.msgType = msgType;
        }

        public String msgType() {
            return msgType;
        }
    }

    private static FixMessage.Builder base(FixHeader header, String msgType, String raw) {
        FixMessage.Builder b = FixMessage.newBuilder();
        FixHeader.Builder hb = header == null ? FixHeader.newBuilder() : header.toBuilder();
        if (!hb.hasBeginString()) {
            hb.setBeginString(FixConstants.BEGIN_STRING_42);
        }
        hb.setMsgType(msgType);
        b.setHeader(hb);
        if (raw != null) {
            b.setRaw(raw);
        }
        return b;
    }

    private static void copyHeader(FixMessage m, java.util.function.Consumer<FixHeader> sink) {
        if (m.hasHeader()) {
            sink.accept(m.getHeader());
        }
    }

    private static void setStr(FixMessage m, int tag, java.util.function.Consumer<String> sink) {
        String v = field(m, tag);
        if (v != null) {
            sink.accept(v);
        }
    }

    private static void setDbl(FixMessage m, int tag, java.util.function.DoubleConsumer sink) {
        String v = field(m, tag);
        if (v != null && !v.isEmpty()) {
            sink.accept(parseDecimal(v, tag));
        }
    }

    private static void put(FixMessage.Builder b, int tag, boolean present, String value) {
        if (present && value != null) {
            b.addFields(FixField.newBuilder().setTag(tag).setValue(value));
        }
    }

    private static void putDbl(FixMessage.Builder b, int tag, boolean present, double value) {
        if (present) {
            b.addFields(FixField.newBuilder().setTag(tag).setValue(formatDecimal(value)));
        }
    }

    static String field(FixMessage m, int tag) {
        for (FixField f : m.getFieldsList()) {
            if (f.getTag() == tag) {
                return f.getValue();
            }
        }
        return null;
    }

    private static Set<Integer> known(int... tags) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int tag : tags) {
            set.add(tag);
        }
        return set;
    }

    private static List<FixField> extras(FixMessage m, Set<Integer> known) {
        List<FixField> extra = new ArrayList<>();
        for (FixField f : m.getFieldsList()) {
            if (!known.contains(f.getTag())) {
                extra.add(f);
            }
        }
        return extra;
    }

    private static List<Alloc> allocs(FixMessage m) {
        List<Alloc> result = new ArrayList<>();
        FixGroup group = group(m, Tags.NO_ALLOCS);
        if (group == null) {
            return result;
        }
        for (FixGroupInstance inst : group.getInstancesList()) {
            Alloc.Builder a = Alloc.newBuilder();
            for (FixField f : inst.getFieldsList()) {
                switch (f.getTag()) {
                    case Tags.ALLOC_ACCOUNT -> a.setAllocAccount(f.getValue());
                    case Tags.ALLOC_SHARES -> a.setAllocShares(parseDecimal(f.getValue(), f.getTag()));
                    case Tags.ALLOC_PRICE -> a.setAllocPrice(parseDecimal(f.getValue(), f.getTag()));
                    default -> a.addExtra(f);
                }
            }
            result.add(a.build());
        }
        return result;
    }

    private static List<TradingSession> sessions(FixMessage m) {
        List<TradingSession> result = new ArrayList<>();
        FixGroup group = group(m, Tags.NO_TRADING_SESSIONS);
        if (group == null) {
            return result;
        }
        for (FixGroupInstance inst : group.getInstancesList()) {
            TradingSession.Builder s = TradingSession.newBuilder();
            for (FixField f : inst.getFieldsList()) {
                if (f.getTag() == Tags.TRADING_SESSION_ID) {
                    s.setTradingSessionId(f.getValue());
                } else {
                    s.addExtra(f);
                }
            }
            result.add(s.build());
        }
        return result;
    }

    private static List<ContraBroker> contras(FixMessage m) {
        List<ContraBroker> result = new ArrayList<>();
        FixGroup group = group(m, Tags.NO_CONTRA_BROKERS);
        if (group == null) {
            return result;
        }
        for (FixGroupInstance inst : group.getInstancesList()) {
            ContraBroker.Builder c = ContraBroker.newBuilder();
            for (FixField f : inst.getFieldsList()) {
                switch (f.getTag()) {
                    case Tags.CONTRA_BROKER -> c.setContraBroker(f.getValue());
                    case Tags.CONTRA_TRADER -> c.setContraTrader(f.getValue());
                    case Tags.CONTRA_TRADE_QTY -> c.setContraTradeQty(parseDecimal(f.getValue(), f.getTag()));
                    case Tags.CONTRA_TRADE_TIME -> c.setContraTradeTime(f.getValue());
                    default -> c.addExtra(f);
                }
            }
            result.add(c.build());
        }
        return result;
    }

    private static List<MiscFee> fees(FixMessage m) {
        List<MiscFee> result = new ArrayList<>();
        FixGroup group = group(m, Tags.NO_MISC_FEES);
        if (group == null) {
            return result;
        }
        for (FixGroupInstance inst : group.getInstancesList()) {
            MiscFee.Builder f = MiscFee.newBuilder();
            for (FixField field : inst.getFieldsList()) {
                switch (field.getTag()) {
                    case Tags.MISC_FEE_AMT -> f.setMiscFeeAmt(parseDecimal(field.getValue(), field.getTag()));
                    case Tags.MISC_FEE_CURR -> f.setMiscFeeCurr(field.getValue());
                    case Tags.MISC_FEE_TYPE -> f.setMiscFeeType(field.getValue());
                    default -> f.addExtra(field);
                }
            }
            result.add(f.build());
        }
        return result;
    }

    private static FixGroup group(FixMessage m, int countTag) {
        for (FixGroup g : m.getGroupsList()) {
            if (g.getNumInGroupTag() == countTag) {
                return g;
            }
        }
        return null;
    }

    private static void addAllocGroup(FixMessage.Builder b, List<Alloc> allocs) {
        if (allocs.isEmpty()) {
            return;
        }
        FixGroup.Builder g = FixGroup.newBuilder().setNumInGroupTag(Tags.NO_ALLOCS);
        for (Alloc a : allocs) {
            FixGroupInstance.Builder inst = FixGroupInstance.newBuilder();
            if (a.hasAllocAccount()) {
                inst.addFields(ff(Tags.ALLOC_ACCOUNT, a.getAllocAccount()));
            }
            if (a.hasAllocShares()) {
                inst.addFields(ff(Tags.ALLOC_SHARES, formatDecimal(a.getAllocShares())));
            }
            if (a.hasAllocPrice()) {
                inst.addFields(ff(Tags.ALLOC_PRICE, formatDecimal(a.getAllocPrice())));
            }
            inst.addAllFields(a.getExtraList());
            g.addInstances(inst);
        }
        b.addGroups(g);
    }

    private static void addSessionGroup(FixMessage.Builder b, List<TradingSession> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        FixGroup.Builder g = FixGroup.newBuilder().setNumInGroupTag(Tags.NO_TRADING_SESSIONS);
        for (TradingSession s : sessions) {
            FixGroupInstance.Builder inst = FixGroupInstance.newBuilder();
            if (s.hasTradingSessionId()) {
                inst.addFields(ff(Tags.TRADING_SESSION_ID, s.getTradingSessionId()));
            }
            inst.addAllFields(s.getExtraList());
            g.addInstances(inst);
        }
        b.addGroups(g);
    }

    private static void addContraGroup(FixMessage.Builder b, List<ContraBroker> contras) {
        if (contras.isEmpty()) {
            return;
        }
        FixGroup.Builder g = FixGroup.newBuilder().setNumInGroupTag(Tags.NO_CONTRA_BROKERS);
        for (ContraBroker c : contras) {
            FixGroupInstance.Builder inst = FixGroupInstance.newBuilder();
            if (c.hasContraBroker()) {
                inst.addFields(ff(Tags.CONTRA_BROKER, c.getContraBroker()));
            }
            if (c.hasContraTrader()) {
                inst.addFields(ff(Tags.CONTRA_TRADER, c.getContraTrader()));
            }
            if (c.hasContraTradeQty()) {
                inst.addFields(ff(Tags.CONTRA_TRADE_QTY, formatDecimal(c.getContraTradeQty())));
            }
            if (c.hasContraTradeTime()) {
                inst.addFields(ff(Tags.CONTRA_TRADE_TIME, c.getContraTradeTime()));
            }
            inst.addAllFields(c.getExtraList());
            g.addInstances(inst);
        }
        b.addGroups(g);
    }

    private static void addFeeGroup(FixMessage.Builder b, List<MiscFee> fees) {
        if (fees.isEmpty()) {
            return;
        }
        FixGroup.Builder g = FixGroup.newBuilder().setNumInGroupTag(Tags.NO_MISC_FEES);
        for (MiscFee f : fees) {
            FixGroupInstance.Builder inst = FixGroupInstance.newBuilder();
            if (f.hasMiscFeeAmt()) {
                inst.addFields(ff(Tags.MISC_FEE_AMT, formatDecimal(f.getMiscFeeAmt())));
            }
            if (f.hasMiscFeeCurr()) {
                inst.addFields(ff(Tags.MISC_FEE_CURR, f.getMiscFeeCurr()));
            }
            if (f.hasMiscFeeType()) {
                inst.addFields(ff(Tags.MISC_FEE_TYPE, f.getMiscFeeType()));
            }
            inst.addAllFields(f.getExtraList());
            g.addInstances(inst);
        }
        b.addGroups(g);
    }

    private static FixField ff(int tag, String value) {
        return FixField.newBuilder().setTag(tag).setValue(value).build();
    }

    static double parseDecimal(String raw, int tag) {
        try {
            return new BigDecimal(raw).doubleValue();
        } catch (NumberFormatException e) {
            throw new FixParseException("Invalid decimal for tag " + tag + ": " + raw, e);
        }
    }

    static String formatDecimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
