package com.fix42.oms.model;

import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.fix.FixConstants;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderState;

import java.util.ArrayList;
import java.util.List;

public final class OrderStateUpdater {
    private final CacheConfig config;

    public OrderStateUpdater(CacheConfig config) {
        this.config = config;
    }

    public boolean applyNew(OrderState.Builder state, NewOrderSingle msg) {
        overlayString(state::hasClOrdId, state::setClOrdId, msg.hasClOrdId(), msg.getClOrdId());
        overlayString(state::hasAccount, state::setAccount, msg.hasAccount(), msg.getAccount());
        overlayString(state::hasSymbol, state::setSymbol, msg.hasSymbol(), msg.getSymbol());
        overlayString(state::hasSecurityId, state::setSecurityId, msg.hasSecurityId(), msg.getSecurityId());
        overlayString(state::hasSide, state::setSide, msg.hasSide(), msg.getSide());
        overlayString(state::hasOrdType, state::setOrdType, msg.hasOrdType(), msg.getOrdType());
        overlayString(state::hasTimeInForce, state::setTimeInForce, msg.hasTimeInForce(), msg.getTimeInForce());
        overlayString(state::hasText, state::setText, msg.hasText(), msg.getText());
        overlayDouble(state::hasOrderQty, state::setOrderQty, msg.hasOrderQty(), msg.getOrderQty());
        overlayDouble(state::hasPrice, state::setPrice, msg.hasPrice(), msg.getPrice());
        overlayDouble(state::hasStopPx, state::setStopPx, msg.hasStopPx(), msg.getStopPx());
        if (msg.hasTransactTime()) {
            state.setTransactTime(msg.getTransactTime());
        }
        if (!state.hasOrdStatus()) {
            state.setOrdStatus("A");
        }
        if (msg.hasOrderQty() && !state.hasLeavesQty()) {
            state.setLeavesQty(msg.getOrderQty());
        }
        if (msg.hasOrderQty() && !state.hasCumQty()) {
            state.setCumQty(0);
        }
        rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
        stamp(state, FixConstants.MSG_NEW_ORDER_SINGLE);
        return true;
    }

    public boolean applyExecutionReport(OrderState.Builder state, ExecutionReport msg) {
        if (isDuplicateNewExec(state, msg)) {
            overlayIdentity(state, msg);
            bindExecIdentity(state, msg);
            rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
            return false;
        }
        if (isStale(state, msg.hasTransactTime() ? msg.getTransactTime() : null)) {
            bindExecIdentity(state, msg);
            return false;
        }
        overlayString(state::hasClOrdId, state::setClOrdId, msg.hasClOrdId(), msg.getClOrdId());
        overlayStringAlways(state::setOrigClOrdId, msg.hasOrigClOrdId(), msg.getOrigClOrdId());
        overlayString(state::hasOrderId, state::setOrderId, msg.hasOrderId(), msg.getOrderId());
        overlayString(state::hasSecondaryOrderId, state::setSecondaryOrderId, msg.hasSecondaryOrderId(), msg.getSecondaryOrderId());
        overlayString(state::hasAccount, state::setAccount, msg.hasAccount(), msg.getAccount());
        overlayString(state::hasSymbol, state::setSymbol, msg.hasSymbol(), msg.getSymbol());
        overlayString(state::hasSecurityId, state::setSecurityId, msg.hasSecurityId(), msg.getSecurityId());
        overlayString(state::hasSide, state::setSide, msg.hasSide(), msg.getSide());
        overlayString(state::hasOrdType, state::setOrdType, msg.hasOrdType(), msg.getOrdType());
        overlayString(state::hasTimeInForce, state::setTimeInForce, msg.hasTimeInForce(), msg.getTimeInForce());
        overlayStringAlways(state::setOrdStatus, msg.hasOrdStatus(), msg.getOrdStatus());
        overlayStringAlways(state::setExecType, msg.hasExecType(), msg.getExecType());
        overlayStringAlways(state::setExecTransType, msg.hasExecTransType(), msg.getExecTransType());
        overlayDoubleAlways(state::setOrderQty, msg.hasOrderQty(), msg.getOrderQty());
        overlayDoubleAlways(state::setPrice, msg.hasPrice(), msg.getPrice());
        overlayDoubleAlways(state::setStopPx, msg.hasStopPx(), msg.getStopPx());
        overlayDoubleAlways(state::setLastQty, msg.hasLastShares(), msg.getLastShares());
        overlayDoubleAlways(state::setLastPx, msg.hasLastPx(), msg.getLastPx());
        overlayDoubleAlways(state::setLeavesQty, msg.hasLeavesQty(), msg.getLeavesQty());
        overlayDoubleAlways(state::setCumQty, msg.hasCumQty(), msg.getCumQty());
        overlayDoubleAlways(state::setAvgPx, msg.hasAvgPx(), msg.getAvgPx());
        overlayString(state::hasText, state::setText, msg.hasText(), msg.getText());
        overlayStringAlways(state::setOrdRejReason, msg.hasOrdRejReason(), msg.getOrdRejReason());
        if (msg.hasTransactTime()) {
            state.setTransactTime(msg.getTransactTime());
        }
        bindExecIdentity(state, msg);
        rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
        applyPendingFlags(state, msg.hasOrdStatus() ? msg.getOrdStatus() : state.getOrdStatus());
        stamp(state, FixConstants.MSG_EXECUTION_REPORT);
        return true;
    }

    public boolean applyCancelReplace(OrderState.Builder state, OrderCancelReplaceRequest msg) {
        overlayStringAlways(state::setOrigClOrdId, msg.hasOrigClOrdId(), msg.getOrigClOrdId());
        rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
        if (msg.hasClOrdId()) {
            state.setClOrdId(msg.getClOrdId());
        }
        overlayString(state::hasAccount, state::setAccount, msg.hasAccount(), msg.getAccount());
        overlayString(state::hasSymbol, state::setSymbol, msg.hasSymbol(), msg.getSymbol());
        overlayString(state::hasSide, state::setSide, msg.hasSide(), msg.getSide());
        overlayString(state::hasOrdType, state::setOrdType, msg.hasOrdType(), msg.getOrdType());
        overlayString(state::hasTimeInForce, state::setTimeInForce, msg.hasTimeInForce(), msg.getTimeInForce());
        overlayDoubleAlways(state::setOrderQty, msg.hasOrderQty(), msg.getOrderQty());
        overlayDoubleAlways(state::setPrice, msg.hasPrice(), msg.getPrice());
        overlayDoubleAlways(state::setStopPx, msg.hasStopPx(), msg.getStopPx());
        if (msg.hasTransactTime()) {
            state.setTransactTime(msg.getTransactTime());
        }
        if (!isTerminal(state.hasOrdStatus() ? state.getOrdStatus() : null)) {
            state.setPendingReplace(true);
            state.setOrdStatus("E");
            state.setExecType("E");
        }
        stamp(state, FixConstants.MSG_ORDER_CANCEL_REPLACE);
        return true;
    }

    public boolean applyCancelRequest(OrderState.Builder state, OrderCancelRequest msg) {
        overlayStringAlways(state::setOrigClOrdId, msg.hasOrigClOrdId(), msg.getOrigClOrdId());
        rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
        if (msg.hasClOrdId()) {
            state.setClOrdId(msg.getClOrdId());
        }
        overlayString(state::hasAccount, state::setAccount, msg.hasAccount(), msg.getAccount());
        overlayString(state::hasSymbol, state::setSymbol, msg.hasSymbol(), msg.getSymbol());
        overlayString(state::hasSide, state::setSide, msg.hasSide(), msg.getSide());
        overlayDoubleAlways(state::setOrderQty, msg.hasOrderQty(), msg.getOrderQty());
        if (msg.hasTransactTime()) {
            state.setTransactTime(msg.getTransactTime());
        }
        if (!isTerminal(state.hasOrdStatus() ? state.getOrdStatus() : null)) {
            state.setPendingCancel(true);
            state.setOrdStatus("6");
            state.setExecType("6");
        }
        stamp(state, FixConstants.MSG_ORDER_CANCEL_REQUEST);
        return true;
    }

    public boolean applyCancelReject(OrderState.Builder state, OrderCancelReject msg) {
        overlayStringAlways(state::setOrigClOrdId, msg.hasOrigClOrdId(), msg.getOrigClOrdId());
        rememberClOrdId(state, msg.hasClOrdId() ? msg.getClOrdId() : null);
        if (msg.hasClOrdId()) {
            state.setClOrdId(msg.getClOrdId());
        }
        overlayStringAlways(state::setOrdStatus, msg.hasOrdStatus(), msg.getOrdStatus());
        overlayStringAlways(state::setCxlRejReason, msg.hasCxlRejReason(), msg.getCxlRejReason());
        overlayStringAlways(state::setCxlRejResponseTo, msg.hasCxlRejResponseTo(), msg.getCxlRejResponseTo());
        overlayString(state::hasText, state::setText, msg.hasText(), msg.getText());
        overlayString(state::hasAccount, state::setAccount, msg.hasAccount(), msg.getAccount());
        if (msg.hasTransactTime()) {
            state.setTransactTime(msg.getTransactTime());
        }
        String response = msg.hasCxlRejResponseTo() ? msg.getCxlRejResponseTo() : "";
        if ("1".equals(response)) {
            state.setPendingCancel(false);
        } else if ("2".equals(response)) {
            state.setPendingReplace(false);
        } else {
            state.setPendingCancel(false);
            state.setPendingReplace(false);
        }
        stamp(state, FixConstants.MSG_ORDER_CANCEL_REJECT);
        return true;
    }

    public boolean applyDontKnowTrade(OrderState.Builder state, DontKnowTrade msg) {
        overlayString(state::hasOrderId, state::setOrderId, msg.hasOrderId(), msg.getOrderId());
        overlayString(state::hasSymbol, state::setSymbol, msg.hasSymbol(), msg.getSymbol());
        overlayString(state::hasSide, state::setSide, msg.hasSide(), msg.getSide());
        overlayStringAlways(state::setDkReason, msg.hasDkReason(), msg.getDkReason());
        overlayString(state::hasText, state::setText, msg.hasText(), msg.getText());
        if (msg.hasExecId()) {
            state.setLastExecId(msg.getExecId());
            recordExecId(state, msg.getExecId());
        }
        state.setDkTrade(true);
        stamp(state, FixConstants.MSG_DONT_KNOW_TRADE);
        return true;
    }

    public void stampStatusRequest(OrderState.Builder state) {
        stamp(state, FixConstants.MSG_ORDER_STATUS_REQUEST);
    }

    private static boolean isDuplicateNewExec(OrderState.Builder state, ExecutionReport msg) {
        if (!msg.hasExecId() || msg.getExecId().isEmpty()) {
            return false;
        }
        if (!isNewExecTransType(msg.hasExecTransType() ? msg.getExecTransType() : null)) {
            return false;
        }
        return state.getSeenExecIdsList().contains(msg.getExecId());
    }

    static boolean isNewExecTransType(String execTransType) {
        return execTransType == null || execTransType.isEmpty() || "0".equals(execTransType);
    }

    private void overlayIdentity(OrderState.Builder state, ExecutionReport msg) {
        overlayString(state::hasClOrdId, state::setClOrdId, msg.hasClOrdId(), msg.getClOrdId());
        overlayStringAlways(state::setOrigClOrdId, msg.hasOrigClOrdId(), msg.getOrigClOrdId());
        overlayString(state::hasOrderId, state::setOrderId, msg.hasOrderId(), msg.getOrderId());
        overlayString(state::hasSecondaryOrderId, state::setSecondaryOrderId, msg.hasSecondaryOrderId(), msg.getSecondaryOrderId());
    }

    private void bindExecIdentity(OrderState.Builder state, ExecutionReport msg) {
        if (msg.hasExecId()) {
            state.setLastExecId(msg.getExecId());
            recordExecId(state, msg.getExecId());
        }
    }

    private boolean isStale(OrderState.Builder state, String incomingTx) {
        if (config.applyStaleExecReports()) {
            return false;
        }
        if (incomingTx == null || incomingTx.isEmpty() || !state.hasTransactTime()) {
            return false;
        }
        return incomingTx.compareTo(state.getTransactTime()) < 0;
    }

    private void applyPendingFlags(OrderState.Builder state, String status) {
        if (status == null) {
            return;
        }
        if (!"6".equals(status)) {
            state.setPendingCancel(false);
        }
        if (!"E".equals(status)) {
            state.setPendingReplace(false);
        }
        if (isTerminal(status)) {
            state.setPendingCancel(false);
            state.setPendingReplace(false);
        }
        if ("6".equals(status)) {
            state.setPendingCancel(true);
        }
        if ("E".equals(status)) {
            state.setPendingReplace(true);
        }
    }

    private void rememberClOrdId(OrderState.Builder state, String clOrdId) {
        if (clOrdId == null || clOrdId.isEmpty()) {
            return;
        }
        List<String> history = new ArrayList<>(state.getClOrdIdHistoryList());
        if (!history.contains(clOrdId)) {
            history.add(clOrdId);
            state.clearClOrdIdHistory();
            state.addAllClOrdIdHistory(history);
        }
    }

    private void recordExecId(OrderState.Builder state, String execId) {
        List<String> seen = new ArrayList<>(state.getSeenExecIdsList());
        if (seen.contains(execId)) {
            return;
        }
        seen.add(execId);
        int limit = config.seenExecIdLimit();
        if (seen.size() > limit) {
            seen = new ArrayList<>(seen.subList(seen.size() - limit, seen.size()));
        }
        state.clearSeenExecIds();
        state.addAllSeenExecIds(seen);
    }

    private void stamp(OrderState.Builder state, String msgType) {
        state.setLastMsgType(msgType);
        state.setLastUpdateEpochMs(System.currentTimeMillis());
        state.setVersion(state.getVersion() + 1);
    }

    static boolean isTerminal(String status) {
        return "2".equals(status) || "4".equals(status) || "8".equals(status) || "C".equals(status);
    }

    private static void overlayString(
            java.util.function.BooleanSupplier already,
            java.util.function.Consumer<String> setter,
            boolean present,
            String value) {
        if (present && value != null && !value.isEmpty() && !already.getAsBoolean()) {
            setter.accept(value);
        } else if (present && value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    private static void overlayStringAlways(java.util.function.Consumer<String> setter, boolean present, String value) {
        if (present && value != null && !value.isEmpty()) {
            setter.accept(value);
        }
    }

    private static void overlayDouble(
            java.util.function.BooleanSupplier already,
            java.util.function.DoubleConsumer setter,
            boolean present,
            double value) {
        if (present) {
            setter.accept(value);
        } else if (!already.getAsBoolean()) {
            // leave unset
        }
    }

    private static void overlayDoubleAlways(java.util.function.DoubleConsumer setter, boolean present, double value) {
        if (present) {
            setter.accept(value);
        }
    }
}
