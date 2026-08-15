package com.fix42.oms.parquet;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;

/**
 * Where background archive failures go.
 *
 * <p>A batch flush happens on a writer thread, long after the {@code process()} call whose
 * rows it carries returned. There is no caller left to fail, so a failure has exactly two
 * honest outcomes: the rows are lost, and somebody is told. This interface is the "somebody
 * is told" half — wire it to the same alerting a dropped market-data tick would use.
 * {@link ArchiveStats#flushFailures()} and {@link ArchiveStats#rowsDropped()} count the
 * other half.
 *
 * <p>Implementations must not throw; anything they throw is swallowed to protect the
 * writer thread.
 */
@FunctionalInterface
public interface ArchiveErrorHandler {

    /**
     * @param operation what failed, e.g. {@code "flush"}, {@code "compact"}, {@code "upload"}
     * @param target    the file the operation was producing, or {@code null}
     * @param error     the failure
     */
    void onArchiveError(String operation, Path target, Throwable error);

    /** Default: log at ERROR to {@code System.Logger}. */
    ArchiveErrorHandler LOGGING = new ArchiveErrorHandler() {
        private final Logger log = System.getLogger("com.fix42.oms.parquet");

        @Override
        public void onArchiveError(String operation, Path target, Throwable error) {
            log.log(Level.ERROR, "Parquet archive " + operation + " failed"
                    + (target == null ? "" : " for " + target), error);
        }
    };

    /** Deliver to {@code handler}, swallowing anything the handler itself throws. */
    static void deliver(ArchiveErrorHandler handler, String operation, Path target, Throwable error) {
        try {
            handler.onArchiveError(operation, target, error);
        } catch (RuntimeException suppressed) {
            // The error channel itself failed; nothing further to do safely.
        }
    }
}
