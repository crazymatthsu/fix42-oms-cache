package com.fix42.oms.model;

import com.fix42.oms.proto.FixMessage;

/**
 * Resolves the parent-order identifier that links a child order to its parent.
 *
 * <p>FIX 4.2 has no standard parent-order tag, so parent/child linkage is a
 * deployment convention. Implementations extract the parent identifier from whatever
 * field a given venue/OMS uses. See {@link DefaultParentLinkResolver} for the default
 * (a configurable tag, defaulting to SecondaryClOrdID 526).
 */
@FunctionalInterface
public interface ParentLinkResolver {

    /**
     * @return the parent order's identifier (its OrderID or a ClOrdID) if this message
     * belongs to a child order, or {@code ""} if the message is not a child.
     */
    String resolveParentId(FixMessage message);
}
