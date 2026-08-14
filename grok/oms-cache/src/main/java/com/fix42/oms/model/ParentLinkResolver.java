package com.fix42.oms.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Maintains parent → children multimaps keyed by the parent identifiers
 * carried on child messages (ParentOrderID / ParentClOrdID).
 */
public final class ParentLinkResolver {
    private final Map<String, Set<String>> parentToChildren;
    private final Map<String, String> childToParent;

    public ParentLinkResolver(Map<String, Set<String>> parentToChildren, Map<String, String> childToParent) {
        this.parentToChildren = parentToChildren;
        this.childToParent = childToParent;
    }

    public void link(String childKey, String parentOrderId, String parentClOrdId) {
        if (parentOrderId == null && parentClOrdId == null) {
            return;
        }
        String primary = parentOrderId != null ? parentOrderId : parentClOrdId;
        childToParent.put(childKey, primary);
        add(primary, childKey);
        if (parentClOrdId != null && !parentClOrdId.equals(primary)) {
            add(parentClOrdId, childKey);
        }
        if (parentOrderId != null && !parentOrderId.equals(primary)) {
            add(parentOrderId, childKey);
        }
    }

    public void rekeyChild(String oldKey, String newKey) {
        String parent = childToParent.remove(oldKey);
        if (parent != null) {
            childToParent.put(newKey, parent);
        }
        for (Set<String> children : parentToChildren.values()) {
            if (children.remove(oldKey)) {
                children.add(newKey);
            }
        }
    }

    public List<String> childrenOf(String parentRef, Function<String, String> aliasResolver) {
        Set<String> keys = new LinkedHashSet<>();
        addAll(keys, parentRef);
        if (aliasResolver != null) {
            String alias = aliasResolver.apply(parentRef);
            if (alias != null && !alias.equals(parentRef)) {
                addAll(keys, alias);
            }
        }
        return new ArrayList<>(keys);
    }

    public String parentOf(String childKey) {
        return childToParent.get(childKey);
    }

    public void clear() {
        parentToChildren.clear();
        childToParent.clear();
    }

    private void add(String parentRef, String childKey) {
        parentToChildren.computeIfAbsent(parentRef, k -> new LinkedHashSet<>()).add(childKey);
    }

    private void addAll(Set<String> dest, String parentRef) {
        Set<String> kids = parentToChildren.get(parentRef);
        if (kids != null) {
            dest.addAll(kids);
        }
    }
}
