package com.fix42.oms.parquet;

/**
 * What the overnight job does with a finished trading day.
 *
 * @param compact                merge the day's small files before moving them. Worth doing
 *                               first: uploading thousands of tiny objects costs a request
 *                               each and leaves S3 slow to query.
 * @param compaction             compaction tuning, used when {@code compact} is set
 * @param upload                 copy the day's files to the object store
 * @param deleteLocalAfterUpload delete each local file once its upload succeeded, and prune
 *                               the directories left empty. This is the "moved to S3
 *                               overnight" half — leave it off for a first run, or where
 *                               local disk doubles as a hot cache for intraday queries.
 */
public record EndOfDayPolicy(boolean compact,
                             CompactionPolicy compaction,
                             boolean upload,
                             boolean deleteLocalAfterUpload) {

    public EndOfDayPolicy {
        if (compaction == null) {
            compaction = CompactionPolicy.defaults();
        }
    }

    /** Compact and upload, keeping the local copy. */
    public static EndOfDayPolicy defaults() {
        return new EndOfDayPolicy(true, CompactionPolicy.defaults(), true, false);
    }

    /** Compact, upload, then reclaim the local disk — the full overnight move. */
    public static EndOfDayPolicy moveToObjectStore() {
        return new EndOfDayPolicy(true, CompactionPolicy.defaults(), true, true);
    }

    /** Compact only; no object store involved. */
    public static EndOfDayPolicy compactOnly() {
        return new EndOfDayPolicy(true, CompactionPolicy.defaults(), false, false);
    }

    public EndOfDayPolicy withCompaction(CompactionPolicy policy) {
        return new EndOfDayPolicy(compact, policy, upload, deleteLocalAfterUpload);
    }

    public EndOfDayPolicy withUpload(boolean on) {
        return new EndOfDayPolicy(compact, compaction, on, deleteLocalAfterUpload);
    }

    public EndOfDayPolicy withDeleteLocalAfterUpload(boolean on) {
        return new EndOfDayPolicy(compact, compaction, upload, on);
    }
}
