package com.fix42.oms.parquet;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The overnight job: compact a finished trading day, move it to the object store, and
 * optionally reclaim the local disk.
 *
 * <pre>{@code
 * archive.flush();                       // everything buffered is on local disk
 * try (EndOfDayArchiver eod = new EndOfDayArchiver(
 *         config, EndOfDayPolicy.moveToObjectStore(),
 *         new DuckDbS3ObjectStore(S3Config.of("oms-archive", "prod", "us-east-1"), config.duckDb()))) {
 *     EndOfDayArchiver.Result result = eod.run(LocalDate.now(config.partitionZone()).minusDays(1));
 * }
 * }</pre>
 *
 * <p><b>Flush first.</b> This job only sees files, so anything still buffered in a live
 * {@link ParquetArchive} is not part of the day it moves. Call {@link ParquetArchive#flush()}
 * before {@link #run(LocalDate)}, and run against a day that is closed — a day still being
 * written will keep growing after the job has moved it.
 *
 * <p><b>Partial failure is expected and reported, not fatal.</b> One file that fails to
 * upload does not abandon the rest; it is counted in {@link UploadResult#failures()} and sent
 * to the {@link ArchiveErrorHandler}. A file is deleted locally only after its own upload
 * succeeded, so a failed upload always leaves the local copy behind — re-running the job
 * finishes the move.
 */
public final class EndOfDayArchiver implements Closeable {

    /** Outcome of the upload phase. */
    public record UploadResult(String destination,
                               int filesUploaded,
                               long bytesUploaded,
                               int filesDeleted,
                               int failures) {

        static UploadResult none() {
            return new UploadResult("", 0, 0L, 0, 0);
        }
    }

    /** Outcome of a whole overnight run. */
    public record Result(LocalDate date,
                         List<ParquetCompactor.CompactionResult> compactions,
                         UploadResult upload) {

        public Result {
            compactions = List.copyOf(compactions);
        }

        /** Files removed by compaction across every dataset (before minus after). */
        public int filesMerged() {
            return compactions.stream()
                    .mapToInt(c -> c.filesBefore() - c.filesAfter())
                    .sum();
        }
    }

    private final ParquetArchiveConfig config;
    private final EndOfDayPolicy policy;
    private final ObjectStore objectStore;
    private final ArchiveErrorHandler errorHandler;

    public EndOfDayArchiver(ParquetArchiveConfig config, EndOfDayPolicy policy, ObjectStore objectStore) {
        this(config, policy, objectStore, ArchiveErrorHandler.LOGGING);
    }

    /**
     * @param objectStore destination for the upload phase; may be {@code null} when
     *                    {@link EndOfDayPolicy#upload()} is off
     */
    public EndOfDayArchiver(ParquetArchiveConfig config, EndOfDayPolicy policy,
                            ObjectStore objectStore, ArchiveErrorHandler errorHandler) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.policy = (policy != null) ? policy : EndOfDayPolicy.defaults();
        this.objectStore = objectStore;
        this.errorHandler = (errorHandler != null) ? errorHandler : ArchiveErrorHandler.LOGGING;
        if (this.policy.upload() && objectStore == null) {
            throw new IllegalArgumentException("policy.upload() is set but no ObjectStore was given");
        }
    }

    /** Compact then upload {@code date}, per the policy. */
    public Result run(LocalDate date) {
        List<ParquetCompactor.CompactionResult> compactions =
                policy.compact() ? compact(date) : List.of();
        UploadResult upload = policy.upload() ? upload(date) : UploadResult.none();
        return new Result(date, compactions, upload);
    }

    /** Compaction phase only. */
    public List<ParquetCompactor.CompactionResult> compact(LocalDate date) {
        try (ParquetCompactor compactor = new ParquetCompactor(config, policy.compaction(), errorHandler)) {
            return compactor.compactAll(date);
        }
    }

    /**
     * Upload phase only: every Parquet file of {@code date}, in both datasets, keyed as
     * {@code <dataset>/<partition path>/<file>} so the object store mirrors the local layout
     * exactly — the same {@code read_parquet} glob works against either.
     */
    public UploadResult upload(LocalDate date) {
        if (objectStore == null) {
            throw new IllegalStateException("No ObjectStore configured");
        }
        int uploaded = 0;
        int deleted = 0;
        int failures = 0;
        long bytes = 0L;

        for (Dataset dataset : Dataset.values()) {
            Path root = config.datasetRoot(dataset);
            String prefix = config.partitionScheme().datePrefix(date);
            Path scope = prefix.isEmpty() ? root : root.resolve(prefix);

            for (Path file : parquetFilesUnder(scope)) {
                String key = config.datasetDirectory(dataset) + '/'
                        + root.relativize(file).toString().replace('\\', '/');
                long size;
                try {
                    size = Files.size(file);
                    objectStore.put(file, key);
                } catch (IOException e) {
                    failures++;
                    ArchiveErrorHandler.deliver(errorHandler, "upload", file, new UncheckedIOException(e));
                    continue;
                } catch (RuntimeException e) {
                    failures++;
                    ArchiveErrorHandler.deliver(errorHandler, "upload", file, e);
                    continue;
                }
                uploaded++;
                bytes += size;

                if (policy.deleteLocalAfterUpload()) {
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                    } catch (IOException e) {
                        ArchiveErrorHandler.deliver(errorHandler, "upload-delete", file, new UncheckedIOException(e));
                    }
                }
            }
            if (policy.deleteLocalAfterUpload()) {
                pruneEmptyDirectories(scope, root);
            }
        }
        return new UploadResult(objectStore.describe(), uploaded, bytes, deleted, failures);
    }

    @Override
    public void close() {
        if (objectStore != null) {
            try {
                objectStore.close();
            } catch (RuntimeException e) {
                ArchiveErrorHandler.deliver(errorHandler, "object-store-close", null, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // filesystem helpers
    // ------------------------------------------------------------------

    private List<Path> parquetFilesUnder(Path scope) {
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(scope)) {
            return walk.filter(Files::isRegularFile)
                    .filter(ParquetPaths::isParquetFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ArchiveException("Could not walk " + scope, new UncheckedIOException(e));
        }
    }

    /**
     * Remove directories left empty by the deletes, deepest first, never above {@code stopAt}.
     * A day's tree is (accounts x symbols) directories wide; leaving them behind would make
     * every later listing slower for no reason.
     */
    private void pruneEmptyDirectories(Path scope, Path stopAt) {
        if (!Files.isDirectory(scope)) {
            return;
        }
        List<Path> directories;
        try (Stream<Path> walk = Files.walk(scope)) {
            directories = walk.filter(Files::isDirectory)
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        } catch (IOException e) {
            ArchiveErrorHandler.deliver(errorHandler, "prune", scope, new UncheckedIOException(e));
            return;
        }
        List<Path> removable = new ArrayList<>(directories);
        for (Path directory : removable) {
            if (directory.equals(stopAt) || !directory.startsWith(stopAt)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) {
                    Files.deleteIfExists(directory);
                }
            } catch (IOException e) {
                // Another writer may have re-created the partition; leave it alone.
            }
        }
    }
}
