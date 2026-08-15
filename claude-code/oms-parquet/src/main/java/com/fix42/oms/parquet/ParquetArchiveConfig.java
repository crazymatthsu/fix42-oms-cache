package com.fix42.oms.parquet;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Everything the {@link ParquetArchive} needs: where files land, how they are partitioned,
 * which messages are captured, when batches flush, and how DuckDB is tuned.
 *
 * @param localRoot           archive root on local disk; each dataset gets a subdirectory
 * @param writerId            identity of this writer, stamped into every row and file name.
 *                            Two processes may safely write the same archive root <b>only</b>
 *                            with different writer ids.
 * @param partitionZone       zone the trading date is computed in. This is the trading-day
 *                            boundary, so it is a business decision, not a formatting one:
 *                            {@code America/New_York} files a 17:00 ET fill under that day,
 *                            {@code UTC} files it under the next.
 * @param partitionScheme     directory layout under each dataset root
 * @param capturedMsgTypes    MsgType(35) values written to the raw dataset. Default
 *                            {@code D, G, F, 8, 9, Q}; {@code H} (OrderStatusRequest) is a
 *                            query, not an order event, so it is excluded until asked for.
 * @param rawFixDirectory     dataset directory name for raw messages
 * @param orderStateDirectory dataset directory name for order-state changes
 * @param captureRawFixText   write the whole original message into {@code raw_fix}. It is
 *                            typically the largest column; turning it off keeps the lifted
 *                            tag columns and loses byte-level auditability.
 * @param rawFixDelimiter     delimiter used when the raw text has to be rebuilt from the
 *                            parsed message. {@code '|'} by default because SOH renders as a
 *                            control character in every query tool; pass
 *                            {@code FixConstants.SOH} to keep the exact wire bytes.
 * @param timestampSource     which clock decides {@code ts} (and therefore the partition)
 * @param unknownPartitionLabel directory segment for an absent account or symbol
 * @param batchPolicy         when in-memory batches become files
 * @param duckDb              embedded-engine tuning
 * @param clock               arrival-time source; inject a fixed clock in tests
 */
public record ParquetArchiveConfig(Path localRoot,
                                   String writerId,
                                   ZoneId partitionZone,
                                   PartitionScheme partitionScheme,
                                   Set<String> capturedMsgTypes,
                                   String rawFixDirectory,
                                   String orderStateDirectory,
                                   boolean captureRawFixText,
                                   char rawFixDelimiter,
                                   TimestampSource timestampSource,
                                   String unknownPartitionLabel,
                                   BatchPolicy batchPolicy,
                                   DuckDbConfig duckDb,
                                   Clock clock) {

    /** The in-scope message types: NewOrderSingle, Replace, Cancel, ExecReport, CancelReject, DK. */
    public static final Set<String> DEFAULT_CAPTURED_MSG_TYPES =
            Set.of("D", "G", "F", "8", "9", "Q");

    public ParquetArchiveConfig {
        if (localRoot == null) {
            throw new IllegalArgumentException("localRoot must not be null");
        }
        localRoot = localRoot.toAbsolutePath().normalize();
        if (writerId == null || writerId.isBlank()) {
            throw new IllegalArgumentException("writerId must not be blank");
        }
        writerId = ParquetPaths.sanitizeSegment(writerId);
        if (partitionZone == null) {
            partitionZone = ZoneId.of("UTC");
        }
        if (partitionScheme == null) {
            partitionScheme = PartitionScheme.dateAccountSymbol();
        }
        capturedMsgTypes = (capturedMsgTypes == null || capturedMsgTypes.isEmpty())
                ? DEFAULT_CAPTURED_MSG_TYPES
                : Set.copyOf(capturedMsgTypes);
        rawFixDirectory = blankTo(rawFixDirectory, Dataset.RAW_FIX_MESSAGES.directoryName());
        orderStateDirectory = blankTo(orderStateDirectory, Dataset.ORDER_STATE_CHANGES.directoryName());
        if (rawFixDirectory.equals(orderStateDirectory)) {
            throw new IllegalArgumentException(
                    "rawFixDirectory and orderStateDirectory must differ (both '" + rawFixDirectory + "')");
        }
        if (timestampSource == null) {
            timestampSource = TimestampSource.ARRIVAL_CLOCK;
        }
        unknownPartitionLabel = ParquetPaths.sanitizeSegment(
                blankTo(unknownPartitionLabel, ParquetPaths.UNKNOWN));
        if (batchPolicy == null) {
            batchPolicy = BatchPolicy.defaults();
        }
        if (duckDb == null) {
            duckDb = DuckDbConfig.defaults();
        }
        if (clock == null) {
            clock = Clock.systemUTC();
        }
    }

    /** Defaults for everything but the root: UTC dates, {@code YYYY/MM/DD/account/symbol}. */
    public static ParquetArchiveConfig defaults(Path localRoot) {
        return new ParquetArchiveConfig(
                localRoot,
                defaultWriterId(),
                ZoneId.of("UTC"),
                PartitionScheme.dateAccountSymbol(),
                DEFAULT_CAPTURED_MSG_TYPES,
                Dataset.RAW_FIX_MESSAGES.directoryName(),
                Dataset.ORDER_STATE_CHANGES.directoryName(),
                true,
                '|',
                TimestampSource.ARRIVAL_CLOCK,
                ParquetPaths.UNKNOWN,
                BatchPolicy.defaults(),
                DuckDbConfig.defaults(),
                Clock.systemUTC());
    }

    /** Root directory of {@code dataset}, honouring the configured directory names. */
    public Path datasetRoot(Dataset dataset) {
        return localRoot.resolve(datasetDirectory(dataset));
    }

    /** Configured directory name of {@code dataset}. */
    public String datasetDirectory(Dataset dataset) {
        return switch (dataset) {
            case RAW_FIX_MESSAGES -> rawFixDirectory;
            case ORDER_STATE_CHANGES -> orderStateDirectory;
        };
    }

    /** {@code true} if messages of this MsgType(35) are written to the raw dataset. */
    public boolean captures(String msgType) {
        return msgType != null && capturedMsgTypes.contains(msgType);
    }

    /**
     * {@code hostname-pid}, sanitised — unique per process, and stable across restarts on
     * the same host so a restarted writer's files stay attributable to it.
     */
    public static String defaultWriterId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | RuntimeException e) {
            host = System.getenv().getOrDefault("HOSTNAME", "local");
        }
        String sanitized = ParquetPaths.sanitizeSegment(host);
        if (ParquetPaths.UNKNOWN.equals(sanitized)) {
            sanitized = "local";
        }
        return sanitized + '-' + ProcessHandle.current().pid();
    }

    // ------------------------------------------------------------------
    // withers
    // ------------------------------------------------------------------

    public ParquetArchiveConfig withWriterId(String id) {
        return new ParquetArchiveConfig(localRoot, id, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withPartitionZone(ZoneId zone) {
        return new ParquetArchiveConfig(localRoot, writerId, zone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withPartitionScheme(PartitionScheme scheme) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, scheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withCapturedMsgTypes(String... msgTypes) {
        return withCapturedMsgTypes(new LinkedHashSet<>(Set.of(msgTypes)));
    }

    public ParquetArchiveConfig withCapturedMsgTypes(Set<String> msgTypes) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, msgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withDatasetDirectories(String rawDir, String stateDir) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawDir, stateDir, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withCaptureRawFixText(boolean on) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, on, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withRawFixDelimiter(char delimiter) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, delimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withTimestampSource(TimestampSource source) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, source,
                unknownPartitionLabel, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withUnknownPartitionLabel(String label) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                label, batchPolicy, duckDb, clock);
    }

    public ParquetArchiveConfig withBatchPolicy(BatchPolicy policy) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, policy, duckDb, clock);
    }

    public ParquetArchiveConfig withDuckDb(DuckDbConfig config) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, config, clock);
    }

    public ParquetArchiveConfig withClock(Clock c) {
        return new ParquetArchiveConfig(localRoot, writerId, partitionZone, partitionScheme, capturedMsgTypes,
                rawFixDirectory, orderStateDirectory, captureRawFixText, rawFixDelimiter, timestampSource,
                unknownPartitionLabel, batchPolicy, duckDb, c);
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
