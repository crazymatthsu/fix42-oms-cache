package com.fix42.oms.model;

import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderStatusRequest;

import java.util.List;

public final class OrderIds {
    public final String clOrdId;
    public final String origClOrdId;
    public final String orderId;
    public final String secondaryOrderId;
    public final String execId;
    public final String execRefId;
    public final String account;
    public final String symbol;
    public final String parentOrderId;
    public final String parentClOrdId;

    public OrderIds(
            String clOrdId,
            String origClOrdId,
            String orderId,
            String secondaryOrderId,
            String execId,
            String execRefId,
            String account,
            String symbol,
            String parentOrderId,
            String parentClOrdId) {
        this.clOrdId = emptyToNull(clOrdId);
        this.origClOrdId = emptyToNull(origClOrdId);
        this.orderId = emptyToNull(orderId);
        this.secondaryOrderId = emptyToNull(secondaryOrderId);
        this.execId = emptyToNull(execId);
        this.execRefId = emptyToNull(execRefId);
        this.account = emptyToNull(account);
        this.symbol = emptyToNull(symbol);
        this.parentOrderId = emptyToNull(parentOrderId);
        this.parentClOrdId = emptyToNull(parentClOrdId);
    }

    public static OrderIds from(NewOrderSingle m, CacheConfig config) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                null,
                null,
                null,
                null,
                null,
                m.hasAccount() ? m.getAccount() : null,
                m.hasSymbol() ? m.getSymbol() : null,
                parentOrderId(m.hasParentOrderId() ? m.getParentOrderId() : null, m.getExtraList(), config),
                parentClOrdId(m.hasParentClOrdId() ? m.getParentClOrdId() : null, m.getExtraList(), config)
        );
    }

    public static OrderIds from(ExecutionReport m, CacheConfig config) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                m.hasOrigClOrdId() ? m.getOrigClOrdId() : null,
                m.hasOrderId() ? m.getOrderId() : null,
                m.hasSecondaryOrderId() ? m.getSecondaryOrderId() : null,
                m.hasExecId() ? m.getExecId() : null,
                m.hasExecRefId() ? m.getExecRefId() : null,
                m.hasAccount() ? m.getAccount() : null,
                m.hasSymbol() ? m.getSymbol() : null,
                parentOrderId(m.hasParentOrderId() ? m.getParentOrderId() : null, m.getExtraList(), config),
                parentClOrdId(m.hasParentClOrdId() ? m.getParentClOrdId() : null, m.getExtraList(), config)
        );
    }

    public static OrderIds from(OrderCancelReject m) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                m.hasOrigClOrdId() ? m.getOrigClOrdId() : null,
                m.hasOrderId() ? m.getOrderId() : null,
                null, null, null,
                m.hasAccount() ? m.getAccount() : null,
                null, null, null
        );
    }

    public static OrderIds from(OrderCancelRequest m, CacheConfig config) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                m.hasOrigClOrdId() ? m.getOrigClOrdId() : null,
                m.hasOrderId() ? m.getOrderId() : null,
                null, null, null,
                m.hasAccount() ? m.getAccount() : null,
                m.hasSymbol() ? m.getSymbol() : null,
                parentOrderId(m.hasParentOrderId() ? m.getParentOrderId() : null, m.getExtraList(), config),
                parentClOrdId(m.hasParentClOrdId() ? m.getParentClOrdId() : null, m.getExtraList(), config)
        );
    }

    public static OrderIds from(OrderCancelReplaceRequest m, CacheConfig config) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                m.hasOrigClOrdId() ? m.getOrigClOrdId() : null,
                m.hasOrderId() ? m.getOrderId() : null,
                null, null, null,
                m.hasAccount() ? m.getAccount() : null,
                m.hasSymbol() ? m.getSymbol() : null,
                parentOrderId(m.hasParentOrderId() ? m.getParentOrderId() : null, m.getExtraList(), config),
                parentClOrdId(m.hasParentClOrdId() ? m.getParentClOrdId() : null, m.getExtraList(), config)
        );
    }

    public static OrderIds from(OrderStatusRequest m) {
        return new OrderIds(
                m.hasClOrdId() ? m.getClOrdId() : null,
                null,
                m.hasOrderId() ? m.getOrderId() : null,
                null, null, null,
                m.hasAccount() ? m.getAccount() : null,
                m.hasSymbol() ? m.getSymbol() : null,
                null, null
        );
    }

    public static OrderIds from(DontKnowTrade m) {
        return new OrderIds(
                null, null,
                m.hasOrderId() ? m.getOrderId() : null,
                null,
                m.hasExecId() ? m.getExecId() : null,
                null, null,
                m.hasSymbol() ? m.getSymbol() : null,
                null, null
        );
    }

    public boolean hasIdentity() {
        return orderId != null || clOrdId != null || origClOrdId != null || execId != null || execRefId != null;
    }

    private static String parentOrderId(String typed, List<FixField> extra, CacheConfig config) {
        if (typed != null && !typed.isEmpty()) {
            return typed;
        }
        return extraValue(extra, config.parentOrderIdTag());
    }

    private static String parentClOrdId(String typed, List<FixField> extra, CacheConfig config) {
        if (typed != null && !typed.isEmpty()) {
            return typed;
        }
        return extraValue(extra, config.parentClOrdIdTag());
    }

    private static String extraValue(List<FixField> extra, int tag) {
        if (tag == Tags.PARENT_ORDER_ID || tag == Tags.PARENT_CL_ORD_ID) {
            // already mapped onto first-class fields when using defaults
        }
        for (FixField field : extra) {
            if (field.getTag() == tag && !field.getValue().isEmpty()) {
                return field.getValue();
            }
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
