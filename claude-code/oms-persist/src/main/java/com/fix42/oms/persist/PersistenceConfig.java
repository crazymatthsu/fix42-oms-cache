package com.fix42.oms.persist;

/**
 * Configuration for {@link PersistentOrderCache}.
 *
 * @param fsyncPolicy          when journal writes are forced to disk.
 *                             {@link FsyncPolicy#EVERY_RECORD} guarantees a message is durable
 *                             before {@code process()} returns (~ms-level latency per message on
 *                             typical SSDs); {@link FsyncPolicy#OS_BUFFERED} leaves flushing to
 *                             the OS page cache (microsecond appends, may lose the last unflushed
 *                             writes on an OS/power crash — process crashes lose nothing).
 * @param maxSegmentBytes      journal segment rotation threshold.
 * @param snapshotsToKeep      how many snapshot generations to retain (older ones give fallback
 *                             if the newest is corrupt). Minimum 1.
 * @param snapshotEveryRecords automatic snapshot every N processed messages; {@code 0} disables
 *                             automatic snapshots (call {@link PersistentOrderCache#snapshot()}
 *                             manually, e.g. on a timer or at end-of-day).
 * @param announceRecoveredStates after recovery completes, deliver one synthetic
 *                             {@code OrderStateChange} per restored chain ({@code previous}
 *                             and {@code cause} both null) to the configured listener.
 *                             Because publishing derived latest state is an idempotent
 *                             last-value upsert, this heals an external store (e.g. an AMPS
 *                             SOW topic) that missed updates between the last publish and a
 *                             crash. Replay itself never notifies — only the final states are
 *                             announced, once each.
 */
public record PersistenceConfig(FsyncPolicy fsyncPolicy,
                                long maxSegmentBytes,
                                int snapshotsToKeep,
                                long snapshotEveryRecords,
                                boolean announceRecoveredStates) {

    public enum FsyncPolicy {
        /** fsync after every appended record: durable once process() returns. */
        EVERY_RECORD,
        /** rely on the OS page cache; fsync only on rotation/snapshot/close. */
        OS_BUFFERED
    }

    public PersistenceConfig {
        if (maxSegmentBytes < 1024) {
            throw new IllegalArgumentException("maxSegmentBytes must be >= 1024");
        }
        if (snapshotsToKeep < 1) {
            throw new IllegalArgumentException("snapshotsToKeep must be >= 1");
        }
        if (snapshotEveryRecords < 0) {
            throw new IllegalArgumentException("snapshotEveryRecords must be >= 0");
        }
    }

    /** Defaults: fsync every record, 64 MiB segments, keep 2 snapshots, manual snapshots, no announcements. */
    public static PersistenceConfig defaults() {
        return new PersistenceConfig(FsyncPolicy.EVERY_RECORD, 64L * 1024 * 1024, 2, 0, false);
    }

    public PersistenceConfig withFsyncPolicy(FsyncPolicy policy) {
        return new PersistenceConfig(policy, maxSegmentBytes, snapshotsToKeep, snapshotEveryRecords, announceRecoveredStates);
    }

    public PersistenceConfig withMaxSegmentBytes(long bytes) {
        return new PersistenceConfig(fsyncPolicy, bytes, snapshotsToKeep, snapshotEveryRecords, announceRecoveredStates);
    }

    public PersistenceConfig withSnapshotsToKeep(int n) {
        return new PersistenceConfig(fsyncPolicy, maxSegmentBytes, n, snapshotEveryRecords, announceRecoveredStates);
    }

    public PersistenceConfig withSnapshotEveryRecords(long n) {
        return new PersistenceConfig(fsyncPolicy, maxSegmentBytes, snapshotsToKeep, n, announceRecoveredStates);
    }

    public PersistenceConfig withAnnounceRecoveredStates(boolean on) {
        return new PersistenceConfig(fsyncPolicy, maxSegmentBytes, snapshotsToKeep, snapshotEveryRecords, on);
    }
}
