package com.fix42.oms.cache;

import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrderState;

/**
 * One order's state transition, delivered to an {@link OrderStateListener}.
 *
 * <p>Each processed message produces at most one change per touched chain: the chain the
 * message belongs to, plus (when parent roll-up is enabled) any parent chain whose
 * aggregate it refreshed.
 *
 * @param previous            the state before this message, or {@code null} when the
 *                            chain was first created (or on a recovery announcement)
 * @param current             the state after this message (immutable snapshot)
 * @param cause               the message that triggered the change, or {@code null} for a
 *                            recovery announcement (see
 *                            {@code PersistenceConfig.announceRecoveredStates})
 * @param parentRollUp        {@code true} when {@code current} is a PARENT chain updated
 *                            by a child's message rather than by its own
 * @param arrivalEpochMillis  the fold's clock reading for the triggering message (under
 *                            persistence this is the journaled arrival time, identical on
 *                            live and replayed paths)
 */
public record OrderStateChange(OrderState previous,
                               OrderState current,
                               FixMessage cause,
                               boolean parentRollUp,
                               long arrivalEpochMillis) {

    /** Convenience: {@code true} when this chain was first seen. */
    public boolean isCreate() {
        return previous == null && cause != null;
    }

    /** Convenience: {@code true} for a post-recovery re-announcement of a restored state. */
    public boolean isRecoveryAnnouncement() {
        return cause == null;
    }
}
