package com.fix42.oms.examples;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.cache.CacheConfig;
import com.fix42.oms.cache.InMemoryOrderCache;
import com.fix42.oms.model.DefaultParentLinkResolver;
import com.fix42.oms.parquet.ArchiveStats;
import com.fix42.oms.parquet.Dataset;
import com.fix42.oms.parquet.EndOfDayArchiver;
import com.fix42.oms.parquet.EndOfDayPolicy;
import com.fix42.oms.parquet.LocalDirectoryObjectStore;
import com.fix42.oms.parquet.ParquetArchive;
import com.fix42.oms.parquet.ParquetArchiveConfig;
import com.fix42.oms.parquet.ParquetArchiveReader;
import com.fix42.oms.parquet.ParquetCompactor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * End-to-end demo of the Parquet archive: feed a FIX 4.2 stream through the cache, watch the
 * partitioned files appear, query them the way an intraday reader would, then run the
 * end-of-day compaction and the overnight move.
 *
 * <p>Writes to a temporary directory that is deleted on exit.
 *
 * <p>Run: {@code ./gradlew :examples:run -PmainClass=com.fix42.oms.examples.ParquetArchiveDemo}
 */
public final class ParquetArchiveDemo {

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("oms-parquet-demo");
        Path bucket = Files.createTempDirectory("oms-parquet-demo-bucket");
        try {
            run(root, bucket);
        } finally {
            deleteRecursively(root);
            deleteRecursively(bucket);
        }
    }

    private static void run(Path root, Path bucket) {
        // One file per message, so the demo shows the small-file problem that end-of-day
        // compaction exists to solve. Production defaults are 50k rows or 60 seconds.
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(root)
                .withBatchPolicy(com.fix42.oms.parquet.BatchPolicy.defaults().withMaxRowsPerFile(1));

        LocalDate today;
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            // wrap() archives every raw message before the fold; orderStateListener()
            // archives every latest-state change the fold produces.
            OmsCache cache = new OmsCache(archive.wrap(new InMemoryOrderCache(
                    DefaultParentLinkResolver.create(),
                    CacheConfig.defaults(),
                    archive.orderStateListener())));

            System.out.println("=== Capturing a FIX 4.2 stream to " + root + " ===");
            for (String message : stream()) {
                cache.process(message);
            }
            archive.flush();

            ArchiveStats stats = archive.stats();
            System.out.printf("rows=%d files=%d bytes=%d dropped=%d%n",
                    stats.rowsWritten(), stats.filesWritten(), stats.bytesWritten(), stats.rowsDropped());
            today = LocalDate.now(config.partitionZone());
        }

        System.out.println();
        System.out.println("=== Partition layout (YYYY/MM/DD/account/symbol) ===");
        listFiles(root).stream().limit(6).forEach(p -> System.out.println("  " + root.relativize(p)));
        System.out.println("  ... " + listFiles(root).size() + " files in total");

        System.out.println();
        System.out.println("=== Intraday query: latest state per order, straight off the Parquet files ===");
        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            List<Map<String, Object>> latest = reader.query(
                    "SELECT chain_key, symbol, ord_status, cum_qty, leaves_qty, is_terminal FROM ("
                            + "  SELECT *, row_number() OVER ("
                            + "      PARTITION BY chain_key ORDER BY ts DESC, update_count DESC) AS rn"
                            + "  FROM " + reader.scanSql(Dataset.ORDER_STATE_CHANGES) + ')'
                            + " WHERE rn = 1 ORDER BY chain_key");
            for (Map<String, Object> row : latest) {
                System.out.printf("  %-10s %-6s %-18s cum=%-6s leaves=%-6s terminal=%s%n",
                        row.get("chain_key"), row.get("symbol"), row.get("ord_status"),
                        row.get("cum_qty"), row.get("leaves_qty"), row.get("is_terminal"));
            }

            System.out.println();
            System.out.println("=== The raw audit trail is queryable too ===");
            for (Map<String, Object> row : reader.query(
                    "SELECT msg_type, count(*) AS messages FROM " + reader.scanSql(Dataset.RAW_FIX_MESSAGES)
                            + " GROUP BY msg_type ORDER BY msg_type")) {
                System.out.printf("  35=%-2s %s messages%n", row.get("msg_type"), row.get("messages"));
            }
        }

        System.out.println();
        System.out.println("=== End of day: compact the small files ===");
        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            for (ParquetCompactor.CompactionResult result : compactor.compactAll(today)) {
                System.out.printf("  %-20s %d files -> %d files (%d rows, %d -> %d bytes)%n",
                        result.dataset(), result.filesBefore(), result.filesAfter(),
                        result.rows(), result.bytesBefore(), result.bytesAfter());
            }
        }

        System.out.println();
        System.out.println("=== Overnight: move the day to the object store ===");
        // A LocalDirectoryObjectStore stands in for S3 here; swap in DuckDbS3ObjectStore
        // (or your own ObjectStore over the AWS SDK) for a real bucket.
        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
                new LocalDirectoryObjectStore(bucket))) {
            EndOfDayArchiver.Result result = eod.run(today);
            System.out.printf("  uploaded %d files (%d bytes) to %s, deleted %d locally, %d failures%n",
                    result.upload().filesUploaded(), result.upload().bytesUploaded(),
                    result.upload().destination(), result.upload().filesDeleted(),
                    result.upload().failures());
        }

        System.out.println();
        System.out.println("=== Historical query against the moved copy ===");
        try (ParquetArchiveReader reader = new ParquetArchiveReader(ParquetArchiveConfig.defaults(bucket))) {
            System.out.println("  raw messages:  " + reader.count(Dataset.RAW_FIX_MESSAGES));
            System.out.println("  state changes: " + reader.count(Dataset.ORDER_STATE_CHANGES));
        }
        System.out.println("  local disk after the move: " + listFiles(root).size() + " files");
    }

    /** A parent order worked as two children, a replace, a cancel reject and a reject. */
    private static List<String> stream() {
        return List.of(
                "8=FIX.4.2|35=D|49=BUY|56=SELL|34=1|52=20260814-13:30:00|"
                        + "11=PARENT1|1=ACC1|55=IBM|54=1|38=1000|40=2|44=185.50|59=0|",
                "35=D|34=2|11=CHILD1|526=PARENT1|1=ACC1|55=IBM|54=1|38=600|40=2|44=185.50|59=0|",
                "35=8|34=3|11=CHILD1|526=PARENT1|37=EX-C1|17=E1|20=0|150=0|39=0|1=ACC1|55=IBM|54=1|38=600|151=600|14=0|",
                "35=8|34=4|11=CHILD1|526=PARENT1|37=EX-C1|17=E2|20=0|150=1|39=1|1=ACC1|55=IBM|54=1|38=600|"
                        + "32=250|31=185.48|30=N|151=350|14=250|6=185.48|",
                "35=8|34=5|11=CHILD1|526=PARENT1|37=EX-C1|17=E3|20=0|150=2|39=2|1=ACC1|55=IBM|54=1|38=600|"
                        + "32=350|31=185.52|30=N|151=0|14=600|6=185.503|",
                "35=D|34=6|11=CHILD2|526=PARENT1|1=ACC1|55=IBM|54=1|38=400|40=2|44=185.50|59=0|",
                "35=8|34=7|11=CHILD2|526=PARENT1|37=EX-C2|17=E4|20=0|150=0|39=0|1=ACC1|55=IBM|54=1|38=400|151=400|14=0|",
                "35=G|34=8|11=CHILD2R|41=CHILD2|37=EX-C2|1=ACC1|55=IBM|54=1|38=300|40=2|44=185.45|",
                "35=8|34=9|11=CHILD2R|41=CHILD2|37=EX-C2|17=E5|20=0|150=5|39=0|1=ACC1|55=IBM|54=1|38=300|"
                        + "44=185.45|151=300|14=0|",
                "35=F|34=10|11=CHILD2C|41=CHILD2R|37=EX-C2|1=ACC1|55=IBM|54=1|38=300|",
                "35=9|34=11|11=CHILD2C|41=CHILD2R|37=EX-C2|39=0|434=1|102=0|58=Too late to cancel|",
                "35=D|34=12|11=MSFT1|1=ACC2|55=MSFT|54=2|38=500|40=1|59=0|",
                "35=8|34=13|11=MSFT1|37=EX-M1|17=E6|20=0|150=8|39=8|1=ACC2|55=MSFT|54=2|38=500|151=0|14=0|"
                        + "103=11|58=Unknown symbol for account|");
    }

    private static List<Path> listFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Temp directory cleanup is best effort.
                }
            });
        } catch (IOException e) {
            // Nothing to clean up.
        }
    }

    private ParquetArchiveDemo() {
    }
}
