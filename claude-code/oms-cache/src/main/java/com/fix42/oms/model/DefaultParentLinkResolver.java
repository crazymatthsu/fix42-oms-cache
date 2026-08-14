package com.fix42.oms.model;

import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.FixMessage;

/**
 * Default {@link ParentLinkResolver}: reads the parent identifier from a single,
 * configurable tag. The default tag is {@code 526 SecondaryClOrdID}.
 *
 * <p><b>FIX version note:</b> tag 526 (SecondaryClOrdID) was introduced in FIX 4.3.
 * In strict FIX 4.2 it is a user-defined/custom field, and it is used here purely as a
 * configurable convention for carrying the parent order id on child orders. Deployments
 * that use a different custom tag (or a different linkage scheme) supply their own tag
 * via {@link #ofTag(int)} or their own {@link ParentLinkResolver}.
 */
public final class DefaultParentLinkResolver implements ParentLinkResolver {

    private final int parentTag;

    private DefaultParentLinkResolver(int parentTag) {
        this.parentTag = parentTag;
    }

    /** Resolver reading the default parent tag (526 SecondaryClOrdID). */
    public static DefaultParentLinkResolver create() {
        return new DefaultParentLinkResolver(Tags.SECONDARY_CL_ORD_ID);
    }

    /** Resolver reading a custom parent-link tag. */
    public static DefaultParentLinkResolver ofTag(int parentTag) {
        return new DefaultParentLinkResolver(parentTag);
    }

    public int parentTag() {
        return parentTag;
    }

    @Override
    public String resolveParentId(FixMessage message) {
        String v = FixSupport.firstValue(message, parentTag);
        return v == null ? "" : v;
    }
}
