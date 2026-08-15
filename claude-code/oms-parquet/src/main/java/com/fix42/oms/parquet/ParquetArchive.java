package com.fix42.oms.parquet;

import com.fix42.oms.cache.OrderCache;
import com.fix42.oms.cache.OrderStateChange;
import com.fix42.oms.cache.OrderStateListener;
import com.fix42.oms.fix.FixConstants;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.OrderState;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes FIX 4.2 order flow to a partitioned Parquet dataset on local disk, batched in
 * memory and materialised by an embedded DuckDB.
 *
 * <p>Two datasets are produced side by side (see {@link Dataset}):
 * <ul>
 *   <li>{@code fix_messages} — the raw in-scope messages ({@code 35=D,G,F,8,9,Q}), one row
 *       each, the original text preserved in {@code raw_fix};</li>
 *   <li>{@code order_state} — every latest-order-state change the cache folds, an
 *       append-only version history of each order.</li>
 * </ul>
 *
 * <p><b>Wiring.</b> The two datasets have separate entry points because they answer to
 * different truths. Raw capture is an audit obligation and must record a message even if
 * the cache rejects it, so it hangs off ingest — either {@link #writeRawMessage} directly or
 * {@link #wrap(OrderCache)}, which captures before delegating. State changes are the cache's
 * interpretation and arrive through {@link #orderStateListener()}:
 *
 * <pre>{@code
 * ParquetArchive archive = ParquetArchive.open(ParquetArchiveConfig.defaults(Path.of("/data/oms")));
 * OmsCache cache = new OmsCache(archive.wrap(
 *         new InMemoryOrderCache(DefaultParentLinkResolver.create(),
 *                                CacheConfig.defaults(),
 *                                archive.orderStateListener())));
 *
 * cache.process("8=FIX.4.2|35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.50|");
 * // ... at end of day, or on a timer:
 * archive.flush();
 * }</pre>
 *
 * <p><b>Durability.</b> This is an analytics sink, not a persistence mechanism: rows live in
 * memory until their batch flushes, so a crash loses whatever had not yet been written.
 * {@code :oms-persist}'s write-ahead journal is what makes a message recoverable; the two
 * are complementary and are meant to be used together.
 *
 * <p>Thread-safe. {@link #writeRawMessage} and {@link #orderStateListener()} may be called
 * concurrently from ingest threads; only buffering happens there, and file writing is handed
 * to writer threads.
 */
public final class ParquetArchive implements Closeable {

    private final ParquetArchiveConfig config;
    private final ArchiveErrorHandler errorHandler;
    private final ArchiveCounters counters = new ArchiveCounters();

    private final DuckDbSessionPool sessionPool;
    private final ThreadPoolExecutor flushExecutor;
    private final ScheduledExecutorService flushScheduler;

    private final BatchingRowSink rawSink;
    private final BatchingRowSink stateSink;
    private final RawFixRowMapper rawMapper;
    private final OrderStateRowMapper stateMapper;

    private final AtomicLong ingestSequence = new AtomicLong();
    private volatile boolean closed;

    private ParquetArchive(ParquetArchiveConfig config, ArchiveErrorHandler errorHandler) {
        this.config = config;
        this.errorHandler = errorHandler;

        BatchPolicy policy = config.batchPolicy();
        this.sessionPool = new DuckDbSessionPool(config.duckDb(), policy.writerThreads() + 1);
        DuckDbParquetWriter writer = new DuckDbParquetWriter(config.duckDb(), sessionPool);

        this.flushExecutor = new ThreadPoolExecutor(
                policy.writerThreads(), policy.writerThreads(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(policy.maxQueuedFlushes()),
                daemonThreadFactory("oms-parquet-writer"),
                // Backpressure: when writers fall behind, the submitting thread writes the
                // batch itself rather than the queue growing without bound.
                new ThreadPoolExecutor.CallerRunsPolicy());

        this.rawMapper = new RawFixRowMapper(config);
        this.stateMapper = new OrderStateRowMapper(config);
        this.rawSink = new BatchingRowSink(Dataset.RAW_FIX_MESSAGES, config, writer, counters, errorHandler, flushExecutor);
        this.stateSink = new BatchingRowSink(Dataset.ORDER_STATE_CHANGES, config, writer, counters, errorHandler, flushExecutor);

        if (policy.maxBatchAgeMillis() > 0) {
            this.flushScheduler = Executors.newSingleThreadScheduledExecutor(
                    daemonThreadFactory("oms-parquet-flusher"));
            this.flushScheduler.scheduleWithFixedDelay(this::flushAgedQuietly,
                    policy.flushCheckIntervalMillis(), policy.flushCheckIntervalMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.flushScheduler = null;
        }
    }

    /** Open an archive with the default (logging) error handler. */
    public static ParquetArchive open(ParquetArchiveConfig config) {
        return open(config, ArchiveErrorHandler.LOGGING);
    }

    /**
     * Open an archive, creating the dataset roots.
     *
     * @throws ArchiveException if the archive root cannot be created
     */
    public static ParquetArchive open(ParquetArchiveConfig config, ArchiveErrorHandler errorHandler) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        ArchiveErrorHandler handler = (errorHandler != null) ? errorHandler : ArchiveErrorHandler.LOGGING;
        try {
            for (Dataset dataset : Dataset.values()) {
                Files.createDirectories(config.datasetRoot(dataset));
            }
        } catch (IOException e) {
            throw new ArchiveException("Could not create archive root " + config.localRoot(), e);
        }
        return new ParquetArchive(config, handler);
    }

    public ParquetArchiveConfig config() {
        return config;
    }

    /** Counter snapshot; see {@link ArchiveStats}. */
    public ArchiveStats stats() {
        return counters.snapshot();
    }

    // ------------------------------------------------------------------
    // Raw message capture
    // ------------------------------------------------------------------

    /**
     * Archive a raw FIX message, re-serialising it for the {@code raw_fix} column.
     *
     * @return {@code true} if the message was captured, {@code false} if its MsgType(35) is
     *         outside {@link ParquetArchiveConfig#capturedMsgTypes()}
     */
    public boolean writeRawMessage(FixMessage message) {
        return writeRawMessage(message, null);
    }

    /**
     * Archive a raw FIX message, preserving {@code rawText} byte-for-byte in
     * {@code raw_fix}. Prefer this overload wherever the original wire text is still to
     * hand — a re-serialised message is faithful field-for-field, but only the original is
     * evidence of exactly what the counterparty sent.
     */
    public boolean writeRawMessage(FixMessage message, String rawText) {
        ensureOpen();
        String msgType = FixSupport.msgType(message);
        if (!config.captures(msgType)) {
            return false;
        }
        long arrival = config.clock().millis();
        long ts = resolveTimestamp(message, arrival);
        LocalDate date = Timestamps.tradingDate(ts, config.partitionZone());
        PartitionKey key = partitionKey(date,
                FixSupport.firstValue(message, Tags.ACCOUNT),
                FixSupport.firstValue(message, Tags.SYMBOL));
        rawSink.add(key, rawMapper.toRow(message, rawText, ts, date, ingestSequence.incrementAndGet()));
        return true;
    }

    /** Parse a raw FIX string (SOH- or '|'-delimited, auto-detected) and archive it verbatim. */
    public boolean writeRawMessage(String rawFix) {
        ensureOpen();
        FixMessage message = parserFor(rawFix).parse(rawFix);
        return writeRawMessage(message, rawFix);
    }

    // ------------------------------------------------------------------
    // Order-state capture
    // ------------------------------------------------------------------

    /**
     * Archive one latest-state change. Partitioned by the state's account/symbol and by the
     * trading date of {@link OrderStateChange#arrivalEpochMillis()}, so a change belongs to
     * the day the message arrived, not the day it is written.
     */
    public void writeOrderStateChange(OrderStateChange change) {
        ensureOpen();
        OrderState state = change.current();
        LocalDate date = Timestamps.tradingDate(change.arrivalEpochMillis(), config.partitionZone());
        PartitionKey key = partitionKey(date, state.getAccount(), state.getSymbol());
        stateSink.add(key, stateMapper.toRow(change, date, ingestSequence.incrementAndGet()));
    }

    /**
     * An {@link OrderStateListener} that archives every change.
     *
     * <p>The cache invokes listeners inside its write critical section, so this one only
     * buffers. Failures (including a closed archive) surface through the cache's
     * {@code onListenerError} channel and never fail the {@code process()} caller.
     */
    public OrderStateListener orderStateListener() {
        return this::writeOrderStateChange;
    }

    /**
     * Decorate {@code delegate} so every processed message is archived raw before being
     * folded — the one-line way to capture the audit trail.
     */
    public ArchivingOrderCache wrap(OrderCache delegate) {
        return new ArchivingOrderCache(delegate, this);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Write every buffered row out and block until all in-flight writes have finished.
     * After this returns, everything accepted so far is readable as Parquet.
     */
    public void flush() {
        rawSink.flushAll();
        stateSink.flushAll();
    }

    /**
     * Flush and release the writer threads and DuckDB instances. Idempotent.
     *
     * <p>Does not close a cache passed to {@link #wrap(OrderCache)} — the archive does not
     * own it.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
        }
        try {
            // Flush BEFORE shutting the executor down, so batches go to the writer threads
            // rather than being run inline by the closing thread.
            rawSink.flushAll();
            stateSink.flushAll();
        } finally {
            flushExecutor.shutdown();
            try {
                if (!flushExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    flushExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            sessionPool.close();
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    ArchiveErrorHandler errorHandler() {
        return errorHandler;
    }

    private void ensureOpen() {
        if (closed) {
            throw new ArchiveException("Parquet archive at " + config.localRoot() + " is closed");
        }
    }

    private void flushAgedQuietly() {
        long now = config.clock().millis();
        try {
            rawSink.flushAged(now);
            stateSink.flushAged(now);
        } catch (RuntimeException e) {
            // Never let this escape: an exception from a scheduleWithFixedDelay task
            // cancels all future runs, silently disabling age-based flushing.
            ArchiveErrorHandler.deliver(errorHandler, "scheduled-flush", null, e);
        }
    }

    private PartitionKey partitionKey(LocalDate date, String account, String symbol) {
        return new PartitionKey(date, orUnknown(account), orUnknown(symbol));
    }

    private String orUnknown(String value) {
        return (value == null || value.isBlank()) ? config.unknownPartitionLabel() : value;
    }

    private long resolveTimestamp(FixMessage message, long arrivalMillis) {
        return switch (config.timestampSource()) {
            case ARRIVAL_CLOCK -> arrivalMillis;
            case SENDING_TIME -> firstNonNull(tagTimestamp(message, Tags.SENDING_TIME), arrivalMillis);
            case TRANSACT_TIME -> firstNonNull(tagTimestamp(message, Tags.TRANSACT_TIME),
                    firstNonNull(tagTimestamp(message, Tags.SENDING_TIME), arrivalMillis));
        };
    }

    private static Long tagTimestamp(FixMessage message, int tag) {
        String value = FixSupport.firstValue(message, tag);
        return (value == null || value.isEmpty()) ? null : Timestamps.parseFixUtcTimestamp(value);
    }

    private static long firstNonNull(Long value, long fallback) {
        return (value != null) ? value : fallback;
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

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + '-' + counter.incrementAndGet());
            // Daemon: the archive must never be the reason a JVM refuses to exit. Anything
            // not yet flushed at that point was already at risk — close() is the ordered path.
            thread.setDaemon(true);
            return thread;
        };
    }
}
