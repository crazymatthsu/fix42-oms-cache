package com.fix42.oms.cache;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.api.OrderStateHandler;
import com.fix42.oms.api.UnidentifiableOrderException;
import com.fix42.oms.dict.FixDictionary;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSerializer;
import com.fix42.oms.mapper.FixMessageMapper;
import com.fix42.oms.model.OrderIds;
import com.fix42.oms.model.OrderStateUpdater;
import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.persist.FileStateStore;
import com.fix42.oms.persist.PersistenceConfig;
import com.fix42.oms.persist.RecoveryImage;
import com.fix42.oms.persist.SourceCursor;
import com.fix42.oms.persist.StateStore;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderState;
import com.fix42.oms.proto.OrderStatusRequest;
import com.fix42.oms.proto.PersistedOrder;
import com.fix42.oms.proto.Snapshot;
import com.fix42.oms.proto.WalRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryOrderCache implements OmsCache {
    private final CacheConfig config;
    private final FixParser parser;
    private final OrderStateUpdater updater;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<String, OrderRecord> byKey = new LinkedHashMap<>();
    private final Map<String, String> clOrdIdToKey = new HashMap<>();
    private final Map<String, String> orderIdToKey = new HashMap<>();
    private final Map<String, String> execIdToKey = new HashMap<>();
    private final Map<String, Set<String>> accountToKeys = new HashMap<>();
    private final Map<String, Set<String>> symbolToKeys = new HashMap<>();
    private final Map<String, Set<String>> parentToChildren = new HashMap<>();
    private final Map<String, String> childToParent = new HashMap<>();
    private final ParentLinkResolver parents;
    private final StateStore store;
    private boolean replaying;
    private boolean closed;
    private String lastCursor;
    private int messagesSinceSnapshot;
    private long lastSnapshotEpochMs = System.currentTimeMillis();

    public InMemoryOrderCache() {
        this(CacheConfig.defaults());
    }

    public InMemoryOrderCache(CacheConfig config) {
        this.config = config;
        this.parser = new FixParser(FixDictionary.fix42(), config.validateChecksum(), config.strictHeader());
        this.updater = new OrderStateUpdater(config);
        this.parents = new ParentLinkResolver(parentToChildren, childToParent);
        PersistenceConfig persistence = config.persistence();
        this.store = persistence == null ? null : new FileStateStore(persistence);
        if (store != null) {
            loadFromStore();
        }
    }

    public static InMemoryOrderCache create() {
        return new InMemoryOrderCache();
    }

    public static Recovery recover(CacheConfig config) {
        InMemoryOrderCache cache = new InMemoryOrderCache(config);
        return new Recovery(cache, cache.lastCursor);
    }

    @Override
    public ProcessResult ingest(String rawFix) {
        return ingest(rawFix, null);
    }

    @Override
    public ProcessResult ingest(String rawFix, SourceCursor cursor) {
        FixMessage parsed = parser.parse(rawFix);
        return ingest(parsed, cursor);
    }

    @Override
    public ProcessResult ingest(FixMessage message) {
        return ingest(message, null);
    }

    @Override
    public ProcessResult ingest(FixMessage message, SourceCursor cursor) {
        Object typed = FixMessageMapper.toTyped(message);
        String raw = message.hasRaw() ? message.getRaw() : FixSerializer.pipe(message);
        return dispatch(typed, raw, cursor);
    }

    @Override
    public ProcessResult processNewOrderSingle(NewOrderSingle msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processExecutionReport(ExecutionReport msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processOrderCancelReject(OrderCancelReject msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processOrderCancelRequest(OrderCancelRequest msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processOrderCancelReplaceRequest(OrderCancelReplaceRequest msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processOrderStatusRequest(OrderStatusRequest msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public ProcessResult processDontKnowTrade(DontKnowTrade msg) {
        return dispatch(msg, rawOrSerialized(msg.hasRaw() ? msg.getRaw() : null, msg), null);
    }

    @Override
    public Optional<OrderState> get(String orderKey) {
        lock.readLock().lock();
        try {
            OrderRecord rec = byKey.get(orderKey);
            return rec == null ? Optional.empty() : Optional.of(rec.snapshot());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<OrderState> getByClOrdId(String clOrdId) {
        lock.readLock().lock();
        try {
            return lookup(clOrdIdToKey.get(clOrdId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<OrderState> getByOrderId(String orderId) {
        lock.readLock().lock();
        try {
            return lookup(orderIdToKey.get(orderId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<OrderState> getByExecId(String execId) {
        lock.readLock().lock();
        try {
            return lookup(execIdToKey.get(execId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<OrderState> findByAccount(String account) {
        lock.readLock().lock();
        try {
            return listFrom(accountToKeys.get(account));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<OrderState> findBySymbol(String symbol) {
        lock.readLock().lock();
        try {
            return listFrom(symbolToKeys.get(symbol));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<OrderState> getChildren(String parentOrderId) {
        lock.readLock().lock();
        try {
            List<String> keys = parents.childrenOf(parentOrderId, this::resolveAlias);
            OrderRecord parent = findParentRecord(parentOrderId);
            if (parent != null) {
                for (String child : parent.state.getChildOrderKeysList()) {
                    if (!keys.contains(child)) {
                        keys.add(child);
                    }
                }
            }
            return listFrom(keys);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<OrderState> getParent(String childOrderKey) {
        lock.readLock().lock();
        try {
            String parentRef = parents.parentOf(childOrderKey);
            if (parentRef == null) {
                OrderRecord child = byKey.get(childOrderKey);
                if (child != null && child.state.hasParentOrderId()) {
                    parentRef = child.state.getParentOrderId();
                } else if (child != null && child.state.hasParentClOrdId()) {
                    parentRef = child.state.getParentClOrdId();
                }
            }
            if (parentRef == null) {
                return Optional.empty();
            }
            OrderRecord parent = findParentRecord(parentRef);
            return parent == null ? Optional.empty() : Optional.of(parent.snapshot());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ChildRollup rollup(String parentOrderId) {
        List<OrderState> children = getChildren(parentOrderId);
        double orderQty = 0;
        double cumQty = 0;
        double leavesQty = 0;
        for (OrderState child : children) {
            if (child.hasOrderQty()) {
                orderQty += child.getOrderQty();
            }
            if (child.hasCumQty()) {
                cumQty += child.getCumQty();
            }
            if (child.hasLeavesQty()) {
                leavesQty += child.getLeavesQty();
            }
        }
        return new ChildRollup(children.size(), orderQty, cumQty, leavesQty);
    }

    @Override
    public List<String> getHistory(String orderKey) {
        lock.readLock().lock();
        try {
            OrderRecord rec = byKey.get(orderKey);
            if (rec == null) {
                return List.of();
            }
            return List.copyOf(rec.history);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<OrderState> snapshot() {
        lock.readLock().lock();
        try {
            List<OrderState> all = new ArrayList<>(byKey.size());
            for (OrderRecord rec : byKey.values()) {
                all.add(rec.snapshot());
            }
            return Collections.unmodifiableList(all);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        lock.readLock().lock();
        try {
            return byKey.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void hydrate(Iterable<OrderState> states) {
        lock.writeLock().lock();
        try {
            for (OrderState state : states) {
                if (state == null || state.getOrderKey().isEmpty()) {
                    continue;
                }
                install(PersistedOrder.newBuilder().setState(state).build());
            }
            rebuildIndexes();
            notifyRecovered();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (store == null || closed) {
                return;
            }
            try {
                if (!replaying) {
                    checkpointNow();
                }
            } finally {
                store.close();
                closed = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ProcessResult dispatch(Object typed, String raw, SourceCursor cursor) {
        lock.writeLock().lock();
        try {
            validateIdentity(typed);
            boolean persist = store != null && !replaying;
            boolean walFirst = persist && config.persistence().durableIngest();
            if (walFirst) {
                appendWal(typed, raw, cursor);
            }
            ProcessResult result = applyTyped(typed, raw);
            if (persist && !walFirst) {
                appendWal(typed, raw, cursor);
            }
            if (persist) {
                maybeCheckpoint();
            }
            notifyHandler(result, raw, cursor);
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ProcessResult applyTyped(Object typed, String raw) {
        if (typed instanceof NewOrderSingle msg) {
            return apply(OrderIds.from(msg, config), raw, (rec, created) -> updater.applyNew(rec.state, msg));
        }
        if (typed instanceof ExecutionReport msg) {
            return apply(OrderIds.from(msg, config), raw, (rec, created) -> updater.applyExecutionReport(rec.state, msg));
        }
        if (typed instanceof OrderCancelReject msg) {
            return apply(OrderIds.from(msg), raw, (rec, created) -> updater.applyCancelReject(rec.state, msg));
        }
        if (typed instanceof OrderCancelRequest msg) {
            return apply(OrderIds.from(msg, config), raw, (rec, created) -> updater.applyCancelRequest(rec.state, msg));
        }
        if (typed instanceof OrderCancelReplaceRequest msg) {
            return apply(OrderIds.from(msg, config), raw, (rec, created) -> updater.applyCancelReplace(rec.state, msg));
        }
        if (typed instanceof OrderStatusRequest msg) {
            return applyStatus(OrderIds.from(msg), raw);
        }
        if (typed instanceof DontKnowTrade msg) {
            return apply(OrderIds.from(msg), raw, (rec, created) -> updater.applyDontKnowTrade(rec.state, msg));
        }
        throw new IllegalArgumentException("Unsupported typed message " + typed.getClass());
    }

    private ProcessResult applyStatus(OrderIds ids, String raw) {
        String key = resolve(ids);
        if (key == null) {
            return new ProcessResult(null, null, false, false);
        }
        OrderRecord rec = byKey.get(key);
        recordHistory(rec, raw);
        updater.stampStatusRequest(rec.state);
        return new ProcessResult(rec.key, rec.snapshot(), false, false);
    }

    private ProcessResult apply(OrderIds ids, String raw, Applier applier) {
        if (!ids.hasIdentity()) {
            throw new UnidentifiableOrderException("Message has neither ClOrdID, OrderID nor ExecID");
        }
        String existing = resolve(ids);
        boolean created = existing == null;
        OrderRecord rec;
        if (created) {
            String key = newKey(ids);
            rec = new OrderRecord(key);
            rec.state.setOrderKey(key);
            byKey.put(key, rec);
        } else {
            rec = byKey.get(existing);
        }
        String previousKey = rec.key;
        boolean applied = applier.apply(rec, created);
        bindIdentifiers(rec, ids);
        attachParent(rec, ids);
        recordHistory(rec, raw);
        String previous = previousKey.equals(rec.key) ? null : previousKey;
        return new ProcessResult(rec.key, rec.snapshot(), created, applied, previous);
    }

    private String resolve(OrderIds ids) {
        if (ids.orderId != null) {
            String key = orderIdToKey.get(ids.orderId);
            if (key != null) {
                return key;
            }
        }
        if (ids.clOrdId != null) {
            String key = clOrdIdToKey.get(ids.clOrdId);
            if (key != null) {
                return key;
            }
        }
        if (ids.origClOrdId != null) {
            String key = clOrdIdToKey.get(ids.origClOrdId);
            if (key != null) {
                return key;
            }
        }
        if (ids.execId != null) {
            String key = execIdToKey.get(ids.execId);
            if (key != null) {
                return key;
            }
        }
        if (ids.execRefId != null) {
            String key = execIdToKey.get(ids.execRefId);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static String newKey(OrderIds ids) {
        if (ids.orderId != null) {
            return ids.orderId;
        }
        if (ids.clOrdId != null) {
            return ids.clOrdId;
        }
        if (ids.origClOrdId != null) {
            return ids.origClOrdId;
        }
        throw new UnidentifiableOrderException("Cannot allocate order_key without ClOrdID or OrderID");
    }

    private void bindIdentifiers(OrderRecord rec, OrderIds ids) {
        if (ids.orderId != null) {
            maybeRekey(rec, ids.orderId);
            rec.state.setOrderId(ids.orderId);
            orderIdToKey.put(ids.orderId, rec.key);
        }
        if (ids.secondaryOrderId != null) {
            rec.state.setSecondaryOrderId(ids.secondaryOrderId);
            orderIdToKey.putIfAbsent(ids.secondaryOrderId, rec.key);
        }
        if (ids.clOrdId != null) {
            clOrdIdToKey.put(ids.clOrdId, rec.key);
        }
        if (ids.origClOrdId != null) {
            clOrdIdToKey.putIfAbsent(ids.origClOrdId, rec.key);
        }
        if (ids.execId != null) {
            execIdToKey.put(ids.execId, rec.key);
        }
        if (ids.execRefId != null) {
            execIdToKey.putIfAbsent(ids.execRefId, rec.key);
        }
        reindex(accountToKeys, rec.account, ids.account, rec.key);
        if (ids.account != null) {
            rec.account = ids.account;
        }
        reindex(symbolToKeys, rec.symbol, ids.symbol, rec.key);
        if (ids.symbol != null) {
            rec.symbol = ids.symbol;
        }
    }

    private void maybeRekey(OrderRecord rec, String orderId) {
        if (orderId.equals(rec.key)) {
            return;
        }
        String existing = orderIdToKey.get(orderId);
        if (existing != null && !existing.equals(rec.key)) {
            return;
        }
        String old = rec.key;
        byKey.remove(old);
        rec.key = orderId;
        rec.state.setOrderKey(orderId);
        byKey.put(orderId, rec);
        replaceMapValue(clOrdIdToKey, old, orderId);
        replaceMapValue(orderIdToKey, old, orderId);
        replaceMapValue(execIdToKey, old, orderId);
        replaceInSets(accountToKeys, old, orderId);
        replaceInSets(symbolToKeys, old, orderId);
        parents.rekeyChild(old, orderId);
        for (OrderRecord other : byKey.values()) {
            List<String> children = new ArrayList<>(other.state.getChildOrderKeysList());
            boolean changed = false;
            for (int i = 0; i < children.size(); i++) {
                if (old.equals(children.get(i))) {
                    children.set(i, orderId);
                    changed = true;
                }
            }
            if (changed) {
                other.state.clearChildOrderKeys();
                other.state.addAllChildOrderKeys(children);
            }
        }
    }

    private void attachParent(OrderRecord rec, OrderIds ids) {
        if (ids.parentOrderId != null) {
            rec.state.setParentOrderId(ids.parentOrderId);
        }
        if (ids.parentClOrdId != null) {
            rec.state.setParentClOrdId(ids.parentClOrdId);
        }
        if (ids.parentOrderId != null || ids.parentClOrdId != null) {
            parents.link(rec.key, ids.parentOrderId, ids.parentClOrdId);
            OrderRecord parent = findParentRecord(
                    ids.parentOrderId != null ? ids.parentOrderId : ids.parentClOrdId);
            if (parent != null && !parent.state.getChildOrderKeysList().contains(rec.key)) {
                parent.state.addChildOrderKeys(rec.key);
            }
        }
        // If this row is itself a parent that children already pointed at, copy them on.
        Set<String> already = new LinkedHashSet<>();
        already.addAll(parentToChildren.getOrDefault(rec.key, Set.of()));
        if (rec.state.hasClOrdId()) {
            already.addAll(parentToChildren.getOrDefault(rec.state.getClOrdId(), Set.of()));
        }
        if (rec.state.hasOrderId()) {
            already.addAll(parentToChildren.getOrDefault(rec.state.getOrderId(), Set.of()));
        }
        for (String child : already) {
            if (!rec.state.getChildOrderKeysList().contains(child)) {
                rec.state.addChildOrderKeys(child);
            }
        }
    }

    private void recordHistory(OrderRecord rec, String raw) {
        if (config.historyLimit() <= 0 || raw == null) {
            return;
        }
        rec.history.addLast(raw);
        while (rec.history.size() > config.historyLimit()) {
            rec.history.removeFirst();
        }
    }

    private Optional<OrderState> lookup(String key) {
        if (key == null) {
            return Optional.empty();
        }
        OrderRecord rec = byKey.get(key);
        return rec == null ? Optional.empty() : Optional.of(rec.snapshot());
    }

    private List<OrderState> listFrom(Iterable<String> keys) {
        if (keys == null) {
            return List.of();
        }
        List<OrderState> result = new ArrayList<>();
        for (String key : keys) {
            OrderRecord rec = byKey.get(key);
            if (rec != null) {
                result.add(rec.snapshot());
            }
        }
        return result;
    }

    private OrderRecord findParentRecord(String parentRef) {
        if (parentRef == null) {
            return null;
        }
        OrderRecord direct = byKey.get(parentRef);
        if (direct != null) {
            return direct;
        }
        String key = orderIdToKey.get(parentRef);
        if (key != null) {
            return byKey.get(key);
        }
        key = clOrdIdToKey.get(parentRef);
        if (key != null) {
            return byKey.get(key);
        }
        return null;
    }

    private String resolveAlias(String parentRef) {
        OrderRecord rec = findParentRecord(parentRef);
        return rec == null ? null : rec.key;
    }

    private static void reindex(Map<String, Set<String>> index, String oldValue, String newValue, String key) {
        if (newValue == null || newValue.equals(oldValue)) {
            if (newValue != null) {
                index.computeIfAbsent(newValue, k -> new LinkedHashSet<>()).add(key);
            }
            return;
        }
        if (oldValue != null) {
            Set<String> set = index.get(oldValue);
            if (set != null) {
                set.remove(key);
                if (set.isEmpty()) {
                    index.remove(oldValue);
                }
            }
        }
        index.computeIfAbsent(newValue, k -> new LinkedHashSet<>()).add(key);
    }

    private static void replaceMapValue(Map<String, String> map, String oldKey, String newKey) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (oldKey.equals(e.getValue())) {
                e.setValue(newKey);
            }
        }
    }

    private static void replaceInSets(Map<String, Set<String>> index, String oldKey, String newKey) {
        for (Set<String> set : index.values()) {
            if (set.remove(oldKey)) {
                set.add(newKey);
            }
        }
    }

    private String rawOrSerialized(String raw, Object typed) {
        if (raw != null && !raw.isEmpty()) {
            return raw;
        }
        return FixSerializer.pipe(FixMessageMapper.fromTyped(typed));
    }

    private void loadFromStore() {
        replaying = true;
        try {
            RecoveryImage image = store.recover();
            lastCursor = image.sourceCursor().orElse(null);
            image.snapshot().ifPresent(snapshot -> {
                for (PersistedOrder order : snapshot.getOrdersList()) {
                    install(order);
                }
                rebuildIndexes();
            });
            for (WalRecord record : image.walTail()) {
                if (record.getRawFix().isEmpty()) {
                    continue;
                }
                FixMessage parsed = parser.parse(record.getRawFix());
                Object typed = FixMessageMapper.toTyped(parsed);
                String raw = parsed.hasRaw() ? parsed.getRaw() : record.getRawFix();
                applyTyped(typed, raw);
            }
        } finally {
            replaying = false;
        }
        notifyRecovered();
    }

    void install(PersistedOrder order) {
        OrderState state = order.getState();
        if (state.getOrderKey().isEmpty()) {
            return;
        }
        OrderRecord rec = new OrderRecord(state.getOrderKey());
        rec.state.mergeFrom(state);
        rec.history.addAll(order.getHistoryList());
        rec.account = state.hasAccount() ? state.getAccount() : null;
        rec.symbol = state.hasSymbol() ? state.getSymbol() : null;
        byKey.put(rec.key, rec);
    }

    void rebuildIndexes() {
        List<OrderState> states = new ArrayList<>(byKey.size());
        for (OrderRecord rec : byKey.values()) {
            states.add(rec.snapshot());
        }
        IndexRebuilder.rebuild(
                states,
                clOrdIdToKey,
                orderIdToKey,
                execIdToKey,
                accountToKeys,
                symbolToKeys,
                parents);
        for (OrderRecord rec : byKey.values()) {
            rec.account = rec.state.hasAccount() ? rec.state.getAccount() : rec.account;
            rec.symbol = rec.state.hasSymbol() ? rec.state.getSymbol() : rec.symbol;
        }
    }

    private void validateIdentity(Object typed) {
        if (typed instanceof OrderStatusRequest) {
            return;
        }
        OrderIds ids = idsOf(typed);
        if (ids != null && !ids.hasIdentity()) {
            throw new UnidentifiableOrderException("Message has neither ClOrdID, OrderID nor ExecID");
        }
    }

    private OrderIds idsOf(Object typed) {
        if (typed instanceof NewOrderSingle msg) {
            return OrderIds.from(msg, config);
        }
        if (typed instanceof ExecutionReport msg) {
            return OrderIds.from(msg, config);
        }
        if (typed instanceof OrderCancelReject msg) {
            return OrderIds.from(msg);
        }
        if (typed instanceof OrderCancelRequest msg) {
            return OrderIds.from(msg, config);
        }
        if (typed instanceof OrderCancelReplaceRequest msg) {
            return OrderIds.from(msg, config);
        }
        if (typed instanceof OrderStatusRequest msg) {
            return OrderIds.from(msg);
        }
        if (typed instanceof DontKnowTrade msg) {
            return OrderIds.from(msg);
        }
        return null;
    }

    private void appendWal(Object typed, String raw, SourceCursor cursor) {
        WalRecord.Builder builder = WalRecord.newBuilder()
                .setRawFix(raw == null ? "" : raw)
                .setMsgType(msgTypeOf(typed))
                .setEpochMs(System.currentTimeMillis());
        OrderIds ids = idsOf(typed);
        if (ids != null && ids.orderId != null) {
            builder.setOrderKey(ids.orderId);
        } else if (ids != null && ids.clOrdId != null) {
            builder.setOrderKey(ids.clOrdId);
        }
        if (cursor != null) {
            builder.setSourceCursor(cursor.value());
            lastCursor = cursor.value();
        }
        store.append(builder.build());
    }

    private static String msgTypeOf(Object typed) {
        if (typed instanceof NewOrderSingle) {
            return "D";
        }
        if (typed instanceof ExecutionReport) {
            return "8";
        }
        if (typed instanceof OrderCancelReject) {
            return "9";
        }
        if (typed instanceof OrderCancelRequest) {
            return "F";
        }
        if (typed instanceof OrderCancelReplaceRequest) {
            return "G";
        }
        if (typed instanceof OrderStatusRequest) {
            return "H";
        }
        if (typed instanceof DontKnowTrade) {
            return "Q";
        }
        return "";
    }

    private void maybeCheckpoint() {
        PersistenceConfig persistence = config.persistence();
        messagesSinceSnapshot++;
        long now = System.currentTimeMillis();
        boolean countDue = messagesSinceSnapshot >= persistence.snapshotEveryMessages();
        boolean timeDue = !persistence.snapshotEvery().isZero()
                && now - lastSnapshotEpochMs >= persistence.snapshotEvery().toMillis();
        if (countDue || timeDue) {
            checkpointNow();
        }
    }

    private void notifyHandler(ProcessResult result, String raw, SourceCursor cursor) {
        OrderStateHandler handler = config.orderStateHandler();
        if (handler == null || replaying || result == null || result.state() == null) {
            return;
        }
        try {
            handler.onOrderUpdated(new OrderStateEvent(result, raw, cursor));
        } catch (RuntimeException e) {
            if (config.handlerFailsIngest()) {
                throw e;
            }
        }
    }

    private void notifyRecovered() {
        OrderStateHandler handler = config.orderStateHandler();
        if (handler == null) {
            return;
        }
        try {
            handler.onRecovered(copyStates());
        } catch (RuntimeException e) {
            if (config.handlerFailsIngest()) {
                throw e;
            }
        }
    }

    private Collection<OrderState> copyStates() {
        List<OrderState> all = new ArrayList<>(byKey.size());
        for (OrderRecord rec : byKey.values()) {
            all.add(rec.snapshot());
        }
        return all;
    }

    private void checkpointNow() {
        Snapshot.Builder snapshot = Snapshot.newBuilder()
                .setUpToSeq(store.lastSeq())
                .setTakenEpochMs(System.currentTimeMillis());
        if (lastCursor != null) {
            snapshot.setSourceCursor(lastCursor);
        }
        for (OrderRecord rec : byKey.values()) {
            snapshot.addOrders(PersistedOrder.newBuilder()
                    .setState(rec.snapshot())
                    .addAllHistory(rec.history));
        }
        store.checkpoint(snapshot.build());
        messagesSinceSnapshot = 0;
        lastSnapshotEpochMs = System.currentTimeMillis();
    }

    @FunctionalInterface
    private interface Applier {
        boolean apply(OrderRecord rec, boolean created);
    }

    static final class OrderRecord {
        String key;
        final OrderState.Builder state = OrderState.newBuilder();
        final ArrayDeque<String> history = new ArrayDeque<>();
        String account;
        String symbol;

        OrderRecord(String key) {
            this.key = key;
        }

        OrderState snapshot() {
            return state.build();
        }
    }
}
