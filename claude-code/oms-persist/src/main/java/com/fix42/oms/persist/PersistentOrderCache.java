package com.fix42.oms.persist;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.InMemoryOrderCache;
import com.fix42.oms.cache.OrderCache;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.model.ParentLinkResolver;
import com.fix42.oms.proto.CacheSnapshot;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.JournalRecord;
import com.fix42.oms.proto.OrderState;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A crash-recoverable {@link OrderCache}: write-ahead journal + snapshots around an
 * {@link InMemoryOrderCache}.
 *
 * <p><b>Write path.</b> {@code process()} assigns the next sequence, appends a
 * {@link JournalRecord} (fsynced per {@link PersistenceConfig.FsyncPolicy}), then folds the
 * message into the in-memory cache using the recorded arrival time. Once {@code process()}
 * returns, the message is recoverable.
 *
 * <p><b>Recovery.</b> {@link #open} loads the newest valid snapshot (falling back to older
 * generations on corruption), restores the cache, replays every journal record with
 * {@code sequence > snapshot.lastAppliedSequence} — exact positioning with strict
 * sequence-contiguity verification, never overlapping, because re-applying a message would
 * duplicate {@code message_history}/{@code update_count} — heals a torn tail in the final
 * segment by truncating it, refuses mid-journal corruption, and resumes journaling in a
 * fresh segment. Replayed records re-inject their recorded arrival time, so recovered
 * {@code OrderState}s equal the pre-crash state ({@code OrderState.equals()}, timestamps
 * included).
 *
 * <p><b>Single owner.</b> A file lock ({@code oms-persist.lock}) guards the directory;
 * a second concurrent {@code open()} of the same directory fails fast.
 *
 * <p>Thread-safe. IO failures on the write path surface as {@link UncheckedIOException};
 * after one, close and re-{@code open()} the cache — recovery re-establishes consistency.
 */
public final class PersistentOrderCache implements OrderCache, Closeable {

    private final InMemoryOrderCache inner;
    private final JournalWriter journal;
    private final SettableClock stateClock;
    private final Clock wallClock;
    private final Path dir;
    private final PersistenceConfig config;
    private final FileChannel lockChannel;
    private final FileLock dirLock;
    private final ReentrantLock lock = new ReentrantLock();

    /** Next sequence to assign; sequences already durable are < this. */
    private long nextSequence;
    private long recordsSinceSnapshot;
    private final long recoveredSequence;
    /**
     * Fail-stop latch. Set when (a) a journal append fails — a partial frame may be on
     * disk, and appending after it would strand later records behind the garbage — or
     * (b) the in-memory apply throws after a durable append — memory and journal now
     * disagree. Either way this instance refuses further writes; close and re-{@link #open}:
     * recovery re-establishes consistency from the durable state.
     */
    private volatile boolean failed;

    private PersistentOrderCache(InMemoryOrderCache inner, JournalWriter journal,
                                 SettableClock stateClock, Clock wallClock,
                                 Path dir, PersistenceConfig config,
                                 FileChannel lockChannel, FileLock dirLock,
                                 long nextSequence, long recoveredSequence) {
        this.inner = inner;
        this.journal = journal;
        this.stateClock = stateClock;
        this.wallClock = wallClock;
        this.dir = dir;
        this.config = config;
        this.lockChannel = lockChannel;
        this.dirLock = dirLock;
        this.nextSequence = nextSequence;
        this.recoveredSequence = recoveredSequence;
    }

    /** Open with default parent linkage, cache config, and persistence config. */
    public static PersistentOrderCache open(Path dir) throws IOException {
        return open(dir, DefaultParentLinkResolver.create(), CacheConfig.defaults(), PersistenceConfig.defaults());
    }

    public static PersistentOrderCache open(Path dir,
                                            ParentLinkResolver parentResolver,
                                            CacheConfig cacheConfig,
                                            PersistenceConfig persistenceConfig) throws IOException {
        return open(dir, parentResolver, cacheConfig, persistenceConfig, null);
    }

    /**
     * Open with an {@link com.fix42.oms.cache.OrderStateListener} for downstream
     * publication (e.g. to an AMPS SOW topic). Recovery replay itself is silent; when
     * {@code PersistenceConfig.announceRecoveredStates} is set, one synthetic change per
     * restored chain is delivered after replay completes (idempotent last-value heal).
     */
    public static PersistentOrderCache open(Path dir,
                                            ParentLinkResolver parentResolver,
                                            CacheConfig cacheConfig,
                                            PersistenceConfig persistenceConfig,
                                            com.fix42.oms.cache.OrderStateListener listener) throws IOException {
        Files.createDirectories(dir);

        FileChannel lockChannel = FileChannel.open(dir.resolve("oms-persist.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock dirLock;
        try {
            // tryLock() returns null when ANOTHER process holds the lock and throws
            // OverlappingFileLockException when THIS JVM already holds it.
            dirLock = lockChannel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lockChannel.close();
            throw new IllegalStateException("Journal directory already in use by this process: " + dir, e);
        }
        if (dirLock == null) {
            lockChannel.close();
            throw new IllegalStateException("Journal directory already in use by another process: " + dir);
        }

        try {
            // Wire a settable clock into the inner cache so state-machine timestamps come
            // from journal arrival times (live and replayed paths identically).
            SettableClock stateClock = new SettableClock();
            Clock wallClock = cacheConfig.clock();
            stateClock.set(wallClock.millis());
            CacheConfig innerConfig = cacheConfig.withClock(stateClock);

            // The listener stays disconnected during replay (recovery must not re-fire
            // history), then is switched on after — optionally after announcing the
            // restored final states once each.
            SwitchableListener switchable = new SwitchableListener();

            CacheSnapshot snapshot = SnapshotStore.loadLatest(dir);
            InMemoryOrderCache inner = (snapshot != null)
                    ? InMemoryOrderCache.fromSnapshot(snapshot, parentResolver, innerConfig, switchable)
                    : new InMemoryOrderCache(parentResolver, innerConfig, switchable);

            long afterSequence = (snapshot != null) ? snapshot.getLastAppliedSequence() : 0L;
            JournalReader.Replay replay = JournalReader.replay(dir, afterSequence, record -> {
                stateClock.set(record.getArrivalEpochMillis());
                inner.process(record.getMessage());
            });

            if (!replay.cleanTail()) {
                JournalReader.Corruption c = replay.corruption();
                if (!c.finalSegment()) {
                    // Mid-journal corruption is NOT a crash artifact: acknowledged records
                    // beyond it would be silently dropped. Refuse rather than mis-recover.
                    throw new IOException("Journal corrupted mid-stream in " + c.segment()
                            + " (" + c.reason() + "); refusing to open — records after the "
                            + "corruption would be lost");
                }
                // A tear at the physical tail of the LAST segment is the expected crash
                // artifact. Truncate it away (and fsync) so the tear cannot masquerade as
                // mid-journal corruption after the NEXT crash.
                healTornTail(c);
            }

            long recovered = Math.max(afterSequence, replay.lastSequence());
            // Always start a fresh segment: never append after a possibly-torn tail. If a
            // segment with this start already exists it yielded NO recoverable records
            // (empty, or its first frame is torn) — quarantine it for forensics.
            quarantineIfPresent(dir, recovered + 1);
            JournalWriter journal = new JournalWriter(dir, recovered + 1, persistenceConfig);

            if (listener != null && persistenceConfig.announceRecoveredStates()) {
                // Best-effort by design: failures go to the listener's error channel, and
                // recovery of a healthy local store NEVER depends on the downstream
                // publisher being reachable. Note this announces the WHOLE cache (one
                // synthetic change per chain, O(cache size) at open) — an idempotent
                // belt-and-braces heal for a last-value store that missed publishes.
                for (OrderState s : inner.snapshotAll()) {
                    com.fix42.oms.cache.OrderStateListener.deliver(listener,
                            new com.fix42.oms.cache.OrderStateChange(
                                    null, s, null, false, s.getLastUpdateEpochMillis()));
                }
            }
            switchable.target = listener; // live notifications from here on

            return new PersistentOrderCache(inner, journal, stateClock, wallClock, dir,
                    persistenceConfig, lockChannel, dirLock, recovered + 1, recovered);
        } catch (IOException | RuntimeException e) {
            dirLock.release();
            lockChannel.close();
            throw e;
        }
    }

    /**
     * Listener indirection: disconnected ({@code target == null}) during recovery replay,
     * connected to the user's listener afterwards.
     */
    private static final class SwitchableListener implements com.fix42.oms.cache.OrderStateListener {
        volatile com.fix42.oms.cache.OrderStateListener target;

        @Override
        public void onOrderStateChange(com.fix42.oms.cache.OrderStateChange change) {
            com.fix42.oms.cache.OrderStateListener t = target;
            if (t != null) {
                t.onOrderStateChange(change);
            }
        }

        @Override
        public void onListenerError(com.fix42.oms.cache.OrderStateChange change, Throwable error) {
            com.fix42.oms.cache.OrderStateListener t = target;
            if (t != null) {
                t.onListenerError(change, error);
            }
        }
    }

    /**
     * Truncate a torn tail in the journal's final segment at the last valid frame
     * boundary. If nothing valid remains (torn header / first frame), the file is moved
     * aside instead. Either way the tear is gone, so a subsequent crash-recovery never
     * sees it as (fatal) mid-journal corruption.
     */
    private static void healTornTail(JournalReader.Corruption c) throws IOException {
        if (c.goodBytesEnd() <= JournalWriter.SEGMENT_MAGIC.length) {
            Path quarantine = c.segment().resolveSibling(c.segment().getFileName() + ".torn");
            int n = 1;
            while (Files.exists(quarantine)) {
                quarantine = c.segment().resolveSibling(c.segment().getFileName() + ".torn-" + n++);
            }
            Files.move(c.segment(), quarantine);
        } else {
            try (FileChannel ch = FileChannel.open(c.segment(), StandardOpenOption.WRITE)) {
                ch.truncate(c.goodBytesEnd());
                ch.force(true);
            }
        }
        SnapshotStore.fsyncDir(c.segment().getParent());
    }

    /**
     * Move an existing segment named for {@code startSequence} out of the way. Reached
     * only when that segment contributed no recoverable records; the {@code .recovered}
     * suffix keeps its bytes for forensics while excluding it from replay.
     */
    private static void quarantineIfPresent(Path dir, long startSequence) throws IOException {
        Path target = dir.resolve(JournalWriter.segmentName(startSequence));
        if (!Files.exists(target)) {
            return;
        }
        Path quarantine = dir.resolve(target.getFileName() + ".recovered");
        int n = 1;
        while (Files.exists(quarantine)) {
            quarantine = dir.resolve(target.getFileName() + ".recovered-" + n++);
        }
        Files.move(target, quarantine);
    }

    /** Highest journal sequence that was recovered (snapshot + replay) at open time. */
    public long recoveredSequence() {
        return recoveredSequence;
    }

    /** Wrap in the standard {@link OmsCache} facade. */
    public OmsCache asOmsCache() {
        return new OmsCache(this);
    }

    // ------------------------------------------------------------------
    // Write path
    // ------------------------------------------------------------------

    @Override
    public OrderState process(FixMessage message) {
        lock.lock();
        try {
            if (failed) {
                throw new IllegalStateException(
                        "Cache is fail-stopped after an earlier write failure; close and re-open to recover");
            }
            long seq = nextSequence;
            long arrival = wallClock.millis();
            try {
                journal.append(JournalRecord.newBuilder()
                        .setSequence(seq)
                        .setArrivalEpochMillis(arrival)
                        .setMessage(message)
                        .build());
            } catch (IOException e) {
                // Sequence not consumed, nothing applied — but a partial frame may be on
                // disk, so no further appends are allowed on this instance.
                failed = true;
                throw new UncheckedIOException("Journal append failed (seq " + seq + ")", e);
            }
            nextSequence = seq + 1;

            stateClock.set(arrival);
            OrderState result;
            try {
                result = inner.process(message);
            } catch (RuntimeException | Error e) {
                // The record is durable but memory now disagrees with the journal:
                // fail-stop. Recovery will re-apply the record deterministically.
                failed = true;
                throw e;
            }

            if (config.snapshotEveryRecords() > 0
                    && ++recordsSinceSnapshot >= config.snapshotEveryRecords()) {
                try {
                    snapshotInternal();
                } catch (IOException e) {
                    // The journal is intact; only checkpointing failed. Surface it —
                    // operators must treat repeated snapshot failures as an alarm
                    // (recovery time grows unboundedly while snapshots fail).
                    throw new UncheckedIOException("Automatic snapshot failed", e);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Take a snapshot now and delete journal segments it fully covers. Recovery time is
     * proportional to the journal written since the last snapshot.
     *
     * <p>Runs under the same lock as {@code process()}: ingest stalls for the duration of
     * the snapshot write (serialization + fsync). Prefer calling this from a housekeeping
     * thread at quiet moments (or end-of-day) over {@code snapshotEveryRecords}, which
     * pays the same stall inside a {@code process()} call. Snapshot size is dominated by
     * {@code message_history}; with {@code historyCap=0} on a large day it can approach
     * protobuf's 2 GiB single-message limit — set a {@code historyCap} for persistent
     * deployments.
     */
    public void snapshot() {
        lock.lock();
        try {
            snapshotInternal();
        } catch (IOException e) {
            throw new UncheckedIOException("Snapshot failed", e);
        } finally {
            lock.unlock();
        }
    }

    private void snapshotInternal() throws IOException {
        long highWater = nextSequence - 1;
        // exportSnapshot stamps created_epoch_millis from the inner (settable) clock,
        // which under persistence is the last arrival time — restamp with wall time.
        CacheSnapshot snapshot = inner.exportSnapshot(highWater).toBuilder()
                .setCreatedEpochMillis(wallClock.millis())
                .build();
        SnapshotStore.write(dir, snapshot, config.snapshotsToKeep());
        // Journal segments may only be deleted once the snapshot's directory entry is
        // DURABLE — otherwise a power loss could lose both the snapshot (rename not yet
        // on disk) and the journal (segments already unlinked). Where the filesystem
        // can't fsync a directory, skip compaction rather than risk it.
        if (SnapshotStore.fsyncDir(dir)) {
            // Compact only up to the OLDEST retained snapshot that VALIDATES: if the
            // newest is later found corrupt, recovery falls back to an older generation
            // and still needs the journal records between the two.
            deleteCoveredSegments(SnapshotStore.oldestValidSequence(dir));
        }
        recordsSinceSnapshot = 0;
    }

    /**
     * A segment is fully covered by high-water {@code hw} iff the NEXT segment starts at
     * {@code hw + 1} or earlier (segments are contiguous). The active (last) segment is
     * never deleted.
     */
    private void deleteCoveredSegments(long highWater) throws IOException {
        List<Path> segments = JournalReader.segments(dir);
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (JournalWriter.startSequenceOf(segments.get(i + 1)) <= highWater + 1) {
                Files.deleteIfExists(segments.get(i));
            }
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            journal.close();
            dirLock.release();
            lockChannel.close();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Queries — delegate to the inner cache (lock-free snapshots)
    // ------------------------------------------------------------------

    @Override
    public Optional<OrderState> getByOrderId(String orderId) {
        return inner.getByOrderId(orderId);
    }

    @Override
    public Optional<OrderState> getByClOrdId(String clOrdId) {
        return inner.getByClOrdId(clOrdId);
    }

    @Override
    public Optional<OrderState> getByExecId(String execId) {
        return inner.getByExecId(execId);
    }

    @Override
    public List<OrderState> findByAccount(String account) {
        return inner.findByAccount(account);
    }

    @Override
    public List<OrderState> findBySymbol(String symbol) {
        return inner.findBySymbol(symbol);
    }

    @Override
    public List<OrderState> getChildren(String parentId) {
        return inner.getChildren(parentId);
    }

    @Override
    public Optional<OrderState> getParent(String childId) {
        return inner.getParent(childId);
    }

    @Override
    public int size() {
        return inner.size();
    }

    @Override
    public Collection<OrderState> snapshotAll() {
        return inner.snapshotAll();
    }
}
