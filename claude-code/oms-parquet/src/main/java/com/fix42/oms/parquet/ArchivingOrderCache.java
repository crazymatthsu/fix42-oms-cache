package com.fix42.oms.parquet;

import com.fix42.oms.cache.OrderCache;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrderState;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * An {@link OrderCache} decorator that archives every message it is given, then delegates.
 *
 * <p><b>Capture happens first, deliberately.</b> A message that the state machine rejects —
 * a malformed report, an unknown chain, a bug — is exactly the message an audit trail most
 * needs, so it is written to Parquet before {@code process()} is attempted. The cost is that
 * the archive can hold a message the cache never accepted; that is the correct asymmetry for
 * an audit copy, and {@code order_state} records what the cache actually did with it.
 *
 * <p>An archive failure does not fail ingest by default: it goes to the archive's
 * {@link ArchiveErrorHandler} and processing continues. Construct with
 * {@code failOnArchiveError = true} where a missing audit row is worse than a stalled feed —
 * a regulatory capture, typically.
 */
public final class ArchivingOrderCache implements OrderCache, Closeable {

    private final OrderCache delegate;
    private final ParquetArchive archive;
    private final boolean failOnArchiveError;

    /** Archive failures are reported and swallowed. */
    public ArchivingOrderCache(OrderCache delegate, ParquetArchive archive) {
        this(delegate, archive, false);
    }

    public ArchivingOrderCache(OrderCache delegate, ParquetArchive archive, boolean failOnArchiveError) {
        if (delegate == null || archive == null) {
            throw new IllegalArgumentException("delegate and archive must not be null");
        }
        this.delegate = delegate;
        this.archive = archive;
        this.failOnArchiveError = failOnArchiveError;
    }

    /** The wrapped cache. */
    public OrderCache delegate() {
        return delegate;
    }

    @Override
    public OrderState process(FixMessage message) {
        try {
            archive.writeRawMessage(message);
        } catch (RuntimeException e) {
            if (failOnArchiveError) {
                throw e;
            }
            ArchiveErrorHandler.deliver(archive.errorHandler(), "capture", null, e);
        }
        return delegate.process(message);
    }

    /**
     * Flush the archive and close the delegate if it is {@link Closeable} (a
     * {@code PersistentOrderCache}, say). The archive itself is <b>not</b> closed — it is
     * shared with the state-change listener and is the caller's to close.
     */
    @Override
    public void close() throws IOException {
        try {
            archive.flush();
        } finally {
            if (delegate instanceof Closeable closeable) {
                closeable.close();
            }
        }
    }

    // ----- queries: pure delegation -----

    @Override
    public Optional<OrderState> getByOrderId(String orderId) {
        return delegate.getByOrderId(orderId);
    }

    @Override
    public Optional<OrderState> getByClOrdId(String clOrdId) {
        return delegate.getByClOrdId(clOrdId);
    }

    @Override
    public Optional<OrderState> getByExecId(String execId) {
        return delegate.getByExecId(execId);
    }

    @Override
    public List<OrderState> findByAccount(String account) {
        return delegate.findByAccount(account);
    }

    @Override
    public List<OrderState> findBySymbol(String symbol) {
        return delegate.findBySymbol(symbol);
    }

    @Override
    public List<OrderState> getChildren(String parentId) {
        return delegate.getChildren(parentId);
    }

    @Override
    public Optional<OrderState> getParent(String childId) {
        return delegate.getParent(childId);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Collection<OrderState> snapshotAll() {
        return delegate.snapshotAll();
    }
}
