package com.fix42.oms.cache;

import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrderState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stores the latest {@link OrderState} of every order chain and the indexes needed to
 * search it. Implementations resolve which chain an incoming message belongs to, fold
 * the message into that chain's state, and maintain the secondary indexes.
 *
 * <p>Point lookups return {@link Optional}; searches return a (possibly empty)
 * {@link List}. All returned {@code OrderState} values are immutable snapshots.
 */
public interface OrderCache {

    /**
     * Resolve the order chain for {@code message}, fold it into that chain's state, update
     * all indexes and parent/child linkage, and return the resulting snapshot.
     */
    OrderState process(FixMessage message);

    Optional<OrderState> getByOrderId(String orderId);

    Optional<OrderState> getByClOrdId(String clOrdId);

    Optional<OrderState> getByExecId(String execId);

    List<OrderState> findByAccount(String account);

    List<OrderState> findBySymbol(String symbol);

    /** Child order states for a parent, identified by the parent's OrderID or ClOrdID. */
    List<OrderState> getChildren(String parentId);

    /** The parent order state for a child, identified by the child's OrderID or ClOrdID. */
    Optional<OrderState> getParent(String childId);

    /** Number of distinct order chains held. */
    int size();

    /** Immutable snapshot of every order chain's current state. */
    Collection<OrderState> snapshotAll();
}
