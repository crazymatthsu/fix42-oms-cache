package com.fix42.oms.cache;

import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.model.OrderStateUpdater;
import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.proto.CacheSnapshot;
import com.fix42.oms.proto.ChainIdSet;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrderState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory {@link OrderCache}.
 *
 * <h2>Chain keying</h2>
 * Every order chain is stored under an opaque internal {@code chainId}. All of a chain's
 * FIX identifiers — OrderID(37), each ClOrdID(11)/OrigClOrdID(41), ExecID(17) — are
 * indexed to that {@code chainId}. Because the primary key is the always-present internal
 * id (not the sell-side OrderID, which appears only on the first ExecutionReport), a
 * brand-new order from a NewOrderSingle is immediately searchable by ClOrdID, Account and
 * Symbol — no separate "pending" store and no null-OrderID index entries.
 *
 * <h2>Concurrency</h2>
 * All mutations (chain resolution, state folding, every index write, parent roll-up) run
 * under a single {@link ReentrantLock}, so each processed message is applied atomically and
 * indexes never diverge from the primary map. Reads are lock-free over concurrent maps and
 * return immutable {@link OrderState} snapshots. Write throughput is serialized, which suits
 * an audit/drop-copy feed; per-chain striping is a possible future optimization.
 */
public final class InMemoryOrderCache implements OrderCache {

    private final OrderStateUpdater updater;
    private final ParentLinkResolver parentResolver;
    private final CacheConfig config;
    private final OrderStateListener listener;

    private final AtomicLong chainSeq = new AtomicLong();
    private final ReentrantLock writeLock = new ReentrantLock();

    private final ConcurrentHashMap<String, OrderState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> orderIdToChain = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> clOrdIdToChain = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> execIdToChain = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> accountToChains = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> symbolToChains = new ConcurrentHashMap<>();
    // Keyed by the raw parent identifier string carried on child orders.
    private final ConcurrentHashMap<String, Set<String>> parentIdToChildChains = new ConcurrentHashMap<>();

    public InMemoryOrderCache() {
        this(DefaultParentLinkResolver.create(), CacheConfig.defaults());
    }

    public InMemoryOrderCache(ParentLinkResolver parentResolver, CacheConfig config) {
        this(parentResolver, config, OrderStateListener.NOOP);
    }

    public InMemoryOrderCache(ParentLinkResolver parentResolver, CacheConfig config,
                              OrderStateListener listener) {
        this.parentResolver = parentResolver;
        this.config = config;
        this.listener = (listener != null) ? listener : OrderStateListener.NOOP;
        this.updater = new OrderStateUpdater(config.clock(), config.historyCap());
    }

    @Override
    public OrderState process(FixMessage message) {
        writeLock.lock();
        try {
            if (writeLock.getHoldCount() > 1) {
                // Mutations from inside a listener callback are enforced-fatal: they would
                // interleave notification order and nest folds. Lock-free reads are fine.
                throw new IllegalStateException(
                        "Reentrant process() from an OrderStateListener is not allowed");
            }
            // Collects every state replacement this message causes, at most one entry per
            // chain: [0] = state before this message, [1] = latest state after it.
            java.util.LinkedHashMap<String, OrderState[]> changes = new java.util.LinkedHashMap<>();

            String chainId = resolveChainId(message);
            OrderState prev = states.get(chainId);
            OrderState next = updater.apply(prev, message);
            states.put(chainId, next);
            noteChange(changes, chainId, prev, next);
            indexIdentifiers(chainId, message, next);

            next = linkAsChild(chainId, message, next, changes);
            linkAsParent(chainId, next, changes);

            OrderState result = states.get(chainId);
            fireListener(changes, chainId, message);
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    private static void noteChange(java.util.Map<String, OrderState[]> changes,
                                   String chainId, OrderState prev, OrderState current) {
        changes.compute(chainId, (k, v) -> (v == null)
                ? new OrderState[]{prev, current}
                : new OrderState[]{v[0], current}); // keep the earliest prev, latest current
    }

    /**
     * Deliver at most one {@link OrderStateChange} per touched chain, in touch order,
     * still inside the write lock (total ordering; handlers must not block). A change to
     * any chain other than the processed one is a parent roll-up. Handler failures are
     * routed to {@link OrderStateListener#onListenerError} and the batch continues —
     * they never propagate to the {@code process()} caller, because the state is already
     * applied and a retried message would corrupt the non-idempotent fold.
     */
    private void fireListener(java.util.Map<String, OrderState[]> changes,
                              String processedChainId, FixMessage cause) {
        if (listener == OrderStateListener.NOOP || changes.isEmpty()) {
            return;
        }
        // The fold's clock reading for this message, as stamped on the processed chain.
        // Parent roll-up entries carry the same (child-message) arrival time.
        OrderState[] processed = changes.get(processedChainId);
        long arrival = (processed != null)
                ? processed[1].getLastUpdateEpochMillis()
                : config.clock().millis();
        for (java.util.Map.Entry<String, OrderState[]> e : changes.entrySet()) {
            OrderStateListener.deliver(listener, new OrderStateChange(
                    e.getValue()[0], e.getValue()[1], cause,
                    !e.getKey().equals(processedChainId), arrival));
        }
    }

    // ------------------------------------------------------------------
    // Chain resolution + indexing (write-lock held)
    // ------------------------------------------------------------------

    private String resolveChainId(FixMessage msg) {
        String orderId = FixSupport.firstValue(msg, Tags.ORDER_ID);
        String clOrdId = FixSupport.firstValue(msg, Tags.CL_ORD_ID);
        String origClOrdId = FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID);
        String execId = FixSupport.firstValue(msg, Tags.EXEC_ID);

        String chainId = lookup(orderIdToChain, orderId);
        if (chainId == null) {
            chainId = lookup(clOrdIdToChain, origClOrdId); // prefer the chain being amended
        }
        if (chainId == null) {
            chainId = lookup(clOrdIdToChain, clOrdId);
        }
        if (chainId == null) {
            chainId = lookup(execIdToChain, execId);
        }
        if (chainId == null) {
            chainId = "chain-" + chainSeq.incrementAndGet();
        }
        return chainId;
    }

    private void indexIdentifiers(String chainId, FixMessage msg, OrderState state) {
        putId(orderIdToChain, FixSupport.firstValue(msg, Tags.ORDER_ID), chainId);
        putId(clOrdIdToChain, FixSupport.firstValue(msg, Tags.CL_ORD_ID), chainId);
        putId(clOrdIdToChain, FixSupport.firstValue(msg, Tags.ORIG_CL_ORD_ID), chainId);
        putId(execIdToChain, FixSupport.firstValue(msg, Tags.EXEC_ID), chainId);

        if (notBlank(state.getAccount())) {
            accountToChains.computeIfAbsent(state.getAccount(), k -> ConcurrentHashMap.newKeySet()).add(chainId);
        }
        if (notBlank(state.getSymbol())) {
            symbolToChains.computeIfAbsent(state.getSymbol(), k -> ConcurrentHashMap.newKeySet()).add(chainId);
        }
    }

    // ------------------------------------------------------------------
    // Parent / child linkage (write-lock held)
    // ------------------------------------------------------------------

    /** If this message's order is a child, record the link and refresh the parent aggregate. */
    private OrderState linkAsChild(String chainId, FixMessage msg, OrderState state,
                                   java.util.Map<String, OrderState[]> changes) {
        String parentId = parentResolver.resolveParentId(msg);
        if (!notBlank(parentId) || isOwnId(state, parentId)) {
            return state;
        }
        if (!parentId.equals(state.getParentOrderId())) {
            OrderState updated = state.toBuilder().setParentOrderId(parentId).build();
            states.put(chainId, updated);
            noteChange(changes, chainId, state, updated);
            state = updated;
        }
        parentIdToChildChains.computeIfAbsent(parentId, k -> ConcurrentHashMap.newKeySet()).add(chainId);

        String parentChain = resolveChainForId(parentId);
        if (parentChain != null && !parentChain.equals(chainId)) {
            refreshParent(parentChain, changes);
        }
        return state;
    }

    /** If earlier children reference this chain, mark it a parent and refresh its aggregate. */
    private void linkAsParent(String chainId, OrderState state,
                              java.util.Map<String, OrderState[]> changes) {
        for (String id : ownIds(state)) {
            if (parentIdToChildChains.containsKey(id)) {
                refreshParent(chainId, changes);
                return;
            }
        }
    }

    private void refreshParent(String parentChainId, java.util.Map<String, OrderState[]> changes) {
        if (!config.rollUpParents()) {
            return;
        }
        OrderState parent = states.get(parentChainId);
        if (parent == null) {
            return;
        }
        // Sorted iteration makes the aggregate independent of hash-set iteration order
        // (child_order_ids ordering and floating-point summation order are then stable
        // across live runs, journal replays, and snapshot restores).
        Set<String> childChains = new java.util.TreeSet<>(collectChildChains(parent));
        childChains.remove(parentChainId);

        double cumQty = 0;
        double notional = 0;
        double leavesQty = 0;
        List<String> childOrderIds = new ArrayList<>();
        for (String cc : childChains) {
            OrderState child = states.get(cc);
            if (child == null) {
                continue;
            }
            cumQty += child.getCumQty();
            notional += child.getCumQty() * child.getAvgPx();
            leavesQty += child.getLeavesQty();
            childOrderIds.add(notBlank(child.getOrderId()) ? child.getOrderId() : child.getClOrdId());
        }
        double avgPx = cumQty > 0 ? notional / cumQty : 0.0;

        OrderState updated = parent.toBuilder()
                .setIsParent(true)
                .clearChildOrderIds()
                .addAllChildOrderIds(childOrderIds)
                .setCumQty(cumQty)
                .setLeavesQty(leavesQty)
                .setAvgPx(avgPx)
                .build();
        if (updated.equals(parent)) {
            return; // aggregate unchanged: no write, no spurious notification
        }
        states.put(parentChainId, updated);
        noteChange(changes, parentChainId, parent, updated);
    }

    private Set<String> collectChildChains(OrderState parent) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : ownIds(parent)) {
            Set<String> kids = parentIdToChildChains.get(id);
            if (kids != null) {
                result.addAll(kids);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Queries (lock-free)
    // ------------------------------------------------------------------

    @Override
    public Optional<OrderState> getByOrderId(String orderId) {
        return byIndex(orderIdToChain, orderId);
    }

    @Override
    public Optional<OrderState> getByClOrdId(String clOrdId) {
        return byIndex(clOrdIdToChain, clOrdId);
    }

    @Override
    public Optional<OrderState> getByExecId(String execId) {
        return byIndex(execIdToChain, execId);
    }

    @Override
    public List<OrderState> findByAccount(String account) {
        return byChainSet(accountToChains.get(account));
    }

    @Override
    public List<OrderState> findBySymbol(String symbol) {
        return byChainSet(symbolToChains.get(symbol));
    }

    @Override
    public List<OrderState> getChildren(String parentId) {
        if (!notBlank(parentId)) {
            return List.of();
        }
        Set<String> childChains = new LinkedHashSet<>();
        Set<String> direct = parentIdToChildChains.get(parentId);
        if (direct != null) {
            childChains.addAll(direct);
        }
        // Also resolve via the parent chain's other identifiers.
        String parentChain = resolveChainForId(parentId);
        if (parentChain != null) {
            OrderState parent = states.get(parentChain);
            if (parent != null) {
                childChains.addAll(collectChildChains(parent));
            }
        }
        return byChainSet(childChains);
    }

    @Override
    public Optional<OrderState> getParent(String childId) {
        String childChain = resolveChainForId(childId);
        if (childChain == null) {
            return Optional.empty();
        }
        OrderState child = states.get(childChain);
        if (child == null || !notBlank(child.getParentOrderId())) {
            return Optional.empty();
        }
        String parentChain = resolveChainForId(child.getParentOrderId());
        return parentChain == null ? Optional.empty() : Optional.ofNullable(states.get(parentChain));
    }

    @Override
    public int size() {
        return states.size();
    }

    @Override
    public Collection<OrderState> snapshotAll() {
        return new ArrayList<>(states.values());
    }

    // ------------------------------------------------------------------
    // Snapshot export / restore (persistence support, see :oms-persist)
    // ------------------------------------------------------------------

    /**
     * Export a full checkpoint of this cache, taken atomically under the write lock.
     * {@code lastAppliedSequence} is the caller's journal high-water mark: every input
     * with a sequence at or below it is reflected in the returned snapshot.
     */
    /** Snapshot format version written by {@link #exportSnapshot}. */
    public static final int SNAPSHOT_FORMAT_VERSION = 1;

    public CacheSnapshot exportSnapshot(long lastAppliedSequence) {
        writeLock.lock();
        try {
            int parentTag = (parentResolver instanceof com.fix42.oms.model.DefaultParentLinkResolver d)
                    ? d.parentTag() : -1;
            CacheSnapshot.Builder b = CacheSnapshot.newBuilder()
                    .setLastAppliedSequence(lastAppliedSequence)
                    .setCreatedEpochMillis(config.clock().millis())
                    .setChainSeq(chainSeq.get())
                    .setFormatVersion(SNAPSHOT_FORMAT_VERSION)
                    .setHistoryCap(config.historyCap())
                    .setRollUpParents(config.rollUpParents())
                    .setParentLinkTag(parentTag);
            b.putAllStates(states);
            b.putAllOrderIdIndex(orderIdToChain);
            b.putAllClOrdIdIndex(clOrdIdToChain);
            b.putAllExecIdIndex(execIdToChain);
            accountToChains.forEach((k, v) ->
                    b.putAccountIndex(k, ChainIdSet.newBuilder().addAllChainIds(v).build()));
            symbolToChains.forEach((k, v) ->
                    b.putSymbolIndex(k, ChainIdSet.newBuilder().addAllChainIds(v).build()));
            parentIdToChildChains.forEach((k, v) ->
                    b.putParentIndex(k, ChainIdSet.newBuilder().addAllChainIds(v).build()));
            return b.build();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reconstruct a cache from a snapshot. The result is behaviorally identical to the
     * exporting instance: all states, every identifier index (persisted verbatim — the
     * clOrdId index can hold past OrigClOrdID values not derivable from final states),
     * and the internal chain-id counter (so new chains never collide with restored ones).
     */
    public static InMemoryOrderCache fromSnapshot(CacheSnapshot snapshot,
                                                  ParentLinkResolver parentResolver,
                                                  CacheConfig config) {
        return fromSnapshot(snapshot, parentResolver, config, OrderStateListener.NOOP);
    }

    public static InMemoryOrderCache fromSnapshot(CacheSnapshot snapshot,
                                                  ParentLinkResolver parentResolver,
                                                  CacheConfig config,
                                                  OrderStateListener listener) {
        validateFingerprint(snapshot, parentResolver, config);
        InMemoryOrderCache c = new InMemoryOrderCache(parentResolver, config, listener);
        c.states.putAll(snapshot.getStatesMap());
        c.orderIdToChain.putAll(snapshot.getOrderIdIndexMap());
        c.clOrdIdToChain.putAll(snapshot.getClOrdIdIndexMap());
        c.execIdToChain.putAll(snapshot.getExecIdIndexMap());
        snapshot.getAccountIndexMap().forEach((k, v) ->
                c.accountToChains.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet())
                        .addAll(v.getChainIdsList()));
        snapshot.getSymbolIndexMap().forEach((k, v) ->
                c.symbolToChains.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet())
                        .addAll(v.getChainIdsList()));
        snapshot.getParentIndexMap().forEach((k, v) ->
                c.parentIdToChildChains.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet())
                        .addAll(v.getChainIdsList()));
        c.chainSeq.set(snapshot.getChainSeq());
        return c;
    }

    /**
     * The fold is deterministic only under the SAME configuration; a mismatch would
     * silently diverge the recovered state, so it fails loudly instead. format_version 0
     * (a pre-fingerprint snapshot) skips the fingerprint checks.
     */
    private static void validateFingerprint(CacheSnapshot snapshot,
                                            ParentLinkResolver parentResolver,
                                            CacheConfig config) {
        int version = snapshot.getFormatVersion();
        if (version > SNAPSHOT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Snapshot format version " + version
                    + " is newer than supported version " + SNAPSHOT_FORMAT_VERSION);
        }
        if (version == 0) {
            return; // legacy snapshot without a fingerprint
        }
        if (snapshot.getHistoryCap() != config.historyCap()) {
            throw new IllegalArgumentException("Snapshot was written with historyCap="
                    + snapshot.getHistoryCap() + " but config has historyCap=" + config.historyCap());
        }
        if (snapshot.getRollUpParents() != config.rollUpParents()) {
            throw new IllegalArgumentException("Snapshot was written with rollUpParents="
                    + snapshot.getRollUpParents() + " but config has rollUpParents=" + config.rollUpParents());
        }
        int configTag = (parentResolver instanceof com.fix42.oms.model.DefaultParentLinkResolver d)
                ? d.parentTag() : -1;
        if (snapshot.getParentLinkTag() > 0 && snapshot.getParentLinkTag() != configTag) {
            throw new IllegalArgumentException("Snapshot was written with parent-link tag "
                    + snapshot.getParentLinkTag() + " but the configured resolver uses " + configTag);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Optional<OrderState> byIndex(ConcurrentHashMap<String, String> index, String id) {
        if (!notBlank(id)) {
            return Optional.empty();
        }
        String chain = index.get(id);
        return chain == null ? Optional.empty() : Optional.ofNullable(states.get(chain));
    }

    private List<OrderState> byChainSet(Set<String> chains) {
        if (chains == null || chains.isEmpty()) {
            return List.of();
        }
        List<OrderState> out = new ArrayList<>(chains.size());
        for (String c : chains) {
            OrderState s = states.get(c);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private String resolveChainForId(String id) {
        if (!notBlank(id)) {
            return null;
        }
        String chain = orderIdToChain.get(id);
        if (chain == null) {
            chain = clOrdIdToChain.get(id);
        }
        return chain;
    }

    private static Set<String> ownIds(OrderState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (notBlank(state.getOrderId())) {
            ids.add(state.getOrderId());
        }
        ids.addAll(state.getClOrdIdHistoryList());
        if (notBlank(state.getClOrdId())) {
            ids.add(state.getClOrdId());
        }
        return ids;
    }

    private static boolean isOwnId(OrderState state, String id) {
        return ownIds(state).contains(id);
    }

    private static String lookup(ConcurrentHashMap<String, String> index, String id) {
        return notBlank(id) ? index.get(id) : null;
    }

    private static void putId(ConcurrentHashMap<String, String> index, String id, String chainId) {
        if (notBlank(id)) {
            index.put(id, chainId);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
