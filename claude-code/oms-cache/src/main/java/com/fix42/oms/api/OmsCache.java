package com.fix42.oms.api;

import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.InMemoryOrderCache;
import com.fix42.oms.cache.OrderCache;
import com.fix42.oms.fix.FixConstants;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.mapper.FixMessageMapper;
import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderState;
import com.fix42.oms.proto.OrderStatusRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Public facade for the FIX 4.2 OMS cache.
 *
 * <p>Feed messages in (as raw FIX strings, generic {@link FixMessage}s, or typed protos)
 * and query the latest state of any order by OrderID, ClOrdID, ExecID, Account or Symbol,
 * plus parent/child relationships. Every {@code process*} call returns the updated
 * {@link OrderState} snapshot.
 *
 * <p>Thread-safe: mutations are serialized per the underlying {@link OrderCache}; reads are
 * consistent immutable snapshots. Construct via {@link #inMemory()} for defaults.
 */
public final class OmsCache {

    private final OrderCache cache;

    public OmsCache(OrderCache cache) {
        this.cache = cache;
    }

    /** Default in-memory cache: unbounded history, parent roll-up, default parent-link tag. */
    public static OmsCache inMemory() {
        return new OmsCache(new InMemoryOrderCache());
    }

    public static OmsCache inMemory(ParentLinkResolver parentResolver, CacheConfig config) {
        return new OmsCache(new InMemoryOrderCache(parentResolver, config));
    }

    /** In-memory cache that notifies {@code listener} on every latest-state change. */
    public static OmsCache inMemory(ParentLinkResolver parentResolver, CacheConfig config,
                                    com.fix42.oms.cache.OrderStateListener listener) {
        return new OmsCache(new InMemoryOrderCache(parentResolver, config, listener));
    }

    /** The underlying cache, for advanced use. */
    public OrderCache cache() {
        return cache;
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    /** Parse a raw FIX string (SOH- or '|'-delimited, auto-detected) and process it. */
    public OrderState process(String rawFix) {
        FixMessage msg = parserFor(rawFix).parse(rawFix);
        return cache.process(msg);
    }

    /** Process a generic message; dispatch is by tag 35 inside the state machine. */
    public OrderState process(FixMessage message) {
        return cache.process(message);
    }

    public OrderState processNewOrderSingle(FixMessage message) {
        return processExpecting(message, "D");
    }

    public OrderState processExecutionReport(FixMessage message) {
        return processExpecting(message, "8");
    }

    public OrderState processOrderCancelReject(FixMessage message) {
        return processExpecting(message, "9");
    }

    public OrderState processOrderCancelRequest(FixMessage message) {
        return processExpecting(message, "F");
    }

    public OrderState processOrderCancelReplaceRequest(FixMessage message) {
        return processExpecting(message, "G");
    }

    public OrderState processOrderStatusRequest(FixMessage message) {
        return processExpecting(message, "H");
    }

    public OrderState processDontKnowTrade(FixMessage message) {
        return processExpecting(message, "Q");
    }

    // Typed-proto convenience overloads (map to a generic FixMessage, then process).

    public OrderState process(NewOrderSingle o) {
        return cache.process(FixMessageMapper.fromNewOrderSingle(o));
    }

    public OrderState process(ExecutionReport o) {
        return cache.process(FixMessageMapper.fromExecutionReport(o));
    }

    public OrderState process(OrderCancelReject o) {
        return cache.process(FixMessageMapper.fromOrderCancelReject(o));
    }

    public OrderState process(OrderCancelRequest o) {
        return cache.process(FixMessageMapper.fromOrderCancelRequest(o));
    }

    public OrderState process(OrderCancelReplaceRequest o) {
        return cache.process(FixMessageMapper.fromOrderCancelReplaceRequest(o));
    }

    public OrderState process(OrderStatusRequest o) {
        return cache.process(FixMessageMapper.fromOrderStatusRequest(o));
    }

    public OrderState process(DontKnowTrade o) {
        return cache.process(FixMessageMapper.fromDontKnowTrade(o));
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    public Optional<OrderState> getByOrderId(String orderId) {
        return cache.getByOrderId(orderId);
    }

    public Optional<OrderState> getByClOrdId(String clOrdId) {
        return cache.getByClOrdId(clOrdId);
    }

    public Optional<OrderState> getByExecId(String execId) {
        return cache.getByExecId(execId);
    }

    public List<OrderState> findByAccount(String account) {
        return cache.findByAccount(account);
    }

    public List<OrderState> findBySymbol(String symbol) {
        return cache.findBySymbol(symbol);
    }

    public List<OrderState> getChildren(String parentId) {
        return cache.getChildren(parentId);
    }

    public Optional<OrderState> getParent(String childId) {
        return cache.getParent(childId);
    }

    public int size() {
        return cache.size();
    }

    public Collection<OrderState> snapshotAll() {
        return cache.snapshotAll();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private OrderState processExpecting(FixMessage message, String expectedMsgType) {
        String actual = FixSupport.msgType(message);
        if (actual != null && !actual.isEmpty() && !actual.equals(expectedMsgType)) {
            throw new IllegalArgumentException(
                    "Expected MsgType(35)=" + expectedMsgType + " but got " + actual);
        }
        return cache.process(message);
    }

    private static FixParser parserFor(String raw) {
        if (raw != null && raw.indexOf(FixConstants.SOH) >= 0) {
            return FixParser.standard();
        }
        if (raw != null && raw.indexOf(FixConstants.PIPE) >= 0) {
            return FixParser.pipe();
        }
        return FixParser.standard();
    }
}
