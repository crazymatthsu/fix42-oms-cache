package com.fix42.oms.cache;

import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.proto.OrderState;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds every secondary index from {@link OrderState} rows. Indexes are
 * derived; they are never the source of truth on disk.
 */
public final class IndexRebuilder {
    private IndexRebuilder() {}

    public static void rebuild(
            Iterable<OrderState> states,
            Map<String, String> clOrdIdToKey,
            Map<String, String> orderIdToKey,
            Map<String, String> execIdToKey,
            Map<String, Set<String>> accountToKeys,
            Map<String, Set<String>> symbolToKeys,
            ParentLinkResolver parents) {
        clOrdIdToKey.clear();
        orderIdToKey.clear();
        execIdToKey.clear();
        accountToKeys.clear();
        symbolToKeys.clear();
        parents.clear();

        for (OrderState state : states) {
            String key = state.getOrderKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (state.hasClOrdId()) {
                clOrdIdToKey.put(state.getClOrdId(), key);
            }
            if (state.hasOrigClOrdId()) {
                clOrdIdToKey.putIfAbsent(state.getOrigClOrdId(), key);
            }
            for (String id : state.getClOrdIdHistoryList()) {
                clOrdIdToKey.putIfAbsent(id, key);
            }
            if (state.hasOrderId()) {
                orderIdToKey.put(state.getOrderId(), key);
            }
            if (state.hasSecondaryOrderId()) {
                orderIdToKey.putIfAbsent(state.getSecondaryOrderId(), key);
            }
            if (state.hasLastExecId()) {
                execIdToKey.put(state.getLastExecId(), key);
            }
            for (String execId : state.getSeenExecIdsList()) {
                execIdToKey.putIfAbsent(execId, key);
            }
            if (state.hasAccount() && !state.getAccount().isEmpty()) {
                accountToKeys.computeIfAbsent(state.getAccount(), k -> new LinkedHashSet<>()).add(key);
            }
            if (state.hasSymbol() && !state.getSymbol().isEmpty()) {
                symbolToKeys.computeIfAbsent(state.getSymbol(), k -> new LinkedHashSet<>()).add(key);
            }
            String parentOrderId = state.hasParentOrderId() ? state.getParentOrderId() : null;
            String parentClOrdId = state.hasParentClOrdId() ? state.getParentClOrdId() : null;
            if ((parentOrderId != null && !parentOrderId.isEmpty())
                    || (parentClOrdId != null && !parentClOrdId.isEmpty())) {
                parents.link(key, emptyToNull(parentOrderId), emptyToNull(parentClOrdId));
            }
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
