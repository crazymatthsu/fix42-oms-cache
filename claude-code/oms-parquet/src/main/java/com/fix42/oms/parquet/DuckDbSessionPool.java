package com.fix42.oms.parquet;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reuses {@link DuckDbSession}s across flushes.
 *
 * <p>Opening a DuckDB instance costs tens of milliseconds — affordable once, not once per
 * batch. The pool is deliberately soft: it never blocks a borrower, because the borrowers
 * are writer threads plus the occasional ingest thread running a flush inline under
 * backpressure, and making that thread wait for a session would add a second queue behind
 * the one already applying backpressure. It instead caps how many sessions are <em>kept</em>
 * ({@code maxIdle}), so a burst of inline flushes creates short-lived extra instances and
 * gives their memory straight back.
 *
 * <p>A session that failed mid-statement is closed rather than returned — see
 * {@link #discard(DuckDbSession)}.
 */
final class DuckDbSessionPool implements AutoCloseable {

    private final DuckDbConfig config;
    private final int maxIdle;
    private final ConcurrentLinkedQueue<DuckDbSession> idle = new ConcurrentLinkedQueue<>();
    private final AtomicInteger idleCount = new AtomicInteger();
    private volatile boolean closed;

    DuckDbSessionPool(DuckDbConfig config, int maxIdle) {
        this.config = config;
        this.maxIdle = Math.max(1, maxIdle);
    }

    DuckDbSession borrow() {
        if (closed) {
            throw new ArchiveException("DuckDB session pool is closed");
        }
        DuckDbSession session = idle.poll();
        if (session != null) {
            idleCount.decrementAndGet();
            return session;
        }
        return DuckDbSession.open(config);
    }

    /** Return a healthy session; closes it instead if the pool is full or shut down. */
    void release(DuckDbSession session) {
        if (closed || idleCount.get() >= maxIdle) {
            session.close();
            return;
        }
        idle.add(session);
        idleCount.incrementAndGet();
        if (closed) {
            // Lost a race with close(): drain what we just added.
            drain();
        }
    }

    /**
     * Close a session that threw. A failed statement can leave a connection with an aborted
     * transaction or a half-built staging table; the next batch would inherit it, so the
     * instance is thrown away instead of pooled.
     */
    void discard(DuckDbSession session) {
        session.close();
    }

    @Override
    public void close() {
        closed = true;
        drain();
    }

    private void drain() {
        DuckDbSession session;
        while ((session = idle.poll()) != null) {
            idleCount.decrementAndGet();
            session.close();
        }
    }
}
