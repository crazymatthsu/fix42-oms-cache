package com.fix42.oms.parquet;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Path-segment sanitising and Parquet file naming.
 *
 * <p>Account and symbol come off the wire and become directory names, so they are attacker-
 * and accident-adjacent: {@code ../}, an embedded {@code /}, a Windows-reserved name, or a
 * NUL would each turn a partition path into something outside the archive root. Everything
 * outside {@code [A-Za-z0-9._-]} is therefore replaced, and the results {@code "."},
 * {@code ".."} and {@code ""} are mapped to {@link #UNKNOWN}. Sanitising is lossy by design
 * — {@code BRK.B} keeps its dot, but {@code A/B} and {@code A_B} both land in {@code A_B} —
 * which is why the untouched account/symbol values are always written as columns inside
 * the file as well.
 */
public final class ParquetPaths {

    /** Fallback segment for a value that is absent or sanitises away entirely. */
    public static final String UNKNOWN = "_unknown";

    /** Suffix of a file still being written; readers must skip these. */
    public static final String TEMP_SUFFIX = ".tmp";

    /** Suffix of a complete, readable Parquet file. */
    public static final String PARQUET_SUFFIX = ".parquet";

    /** Longest path segment emitted; long symbols/accounts are truncated, not rejected. */
    static final int MAX_SEGMENT_LENGTH = 96;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS", Locale.ROOT);

    private ParquetPaths() {
    }

    /**
     * Make {@code raw} safe to use as a single path segment.
     *
     * @return the sanitised segment, or {@link #UNKNOWN} if nothing usable remains
     */
    public static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(Math.min(trimmed.length(), MAX_SEGMENT_LENGTH));
        for (int i = 0; i < trimmed.length() && sb.length() < MAX_SEGMENT_LENGTH; i++) {
            char ch = trimmed.charAt(i);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            sb.append(safe ? ch : '_');
        }
        String out = sb.toString();
        // "." and ".." are directory references, not names; a leading dot would also hide
        // the directory from a default glob.
        if (out.isEmpty() || ".".equals(out) || "..".equals(out) || out.startsWith(".")) {
            return UNKNOWN;
        }
        return out;
    }

    /**
     * Name for a freshly flushed batch:
     * {@code <dataset>-<yyyyMMddTHHmmssSSS>-<writerId>-<seq>.parquet}.
     *
     * <p>The writer id keeps two processes writing the same partition from colliding, and
     * the sequence keeps two flushes within the same millisecond apart; both are needed
     * because a rename over a live file would be visible to a reader mid-scan.
     */
    public static String batchFileName(String datasetName, String writerId, LocalDateTime utcNow, long sequence) {
        return sanitizeSegment(datasetName) + '-' + FILE_STAMP.format(utcNow)
                + '-' + sanitizeSegment(writerId) + '-' + sequence + PARQUET_SUFFIX;
    }

    /** Name for a compaction output: {@code <dataset>-compact-<stamp>-<part>.parquet}. */
    public static String compactedFileName(String datasetName, LocalDateTime utcNow, int part) {
        return compactedFileName(datasetName, utcNow, part, 0);
    }

    /**
     * As {@link #compactedFileName(String, LocalDateTime, int)}, with a disambiguating
     * {@code attempt} suffix. Needed because a compaction's output lands in the same
     * directory as its inputs — one of which is very likely the <em>previous</em>
     * compaction's output, written under the same {@code (stamp, part)} if both runs fall in
     * the same millisecond.
     */
    public static String compactedFileName(String datasetName, LocalDateTime utcNow, int part, int attempt) {
        return sanitizeSegment(datasetName) + "-compact-" + FILE_STAMP.format(utcNow)
                + '-' + part + (attempt == 0 ? "" : "-" + attempt) + PARQUET_SUFFIX;
    }

    /** The hidden temp sibling a file is written to before its atomic rename into place. */
    public static Path tempSibling(Path target) {
        return target.resolveSibling('.' + target.getFileName().toString() + TEMP_SUFFIX);
    }

    /** {@code true} for a complete Parquet file (and not a temp file). */
    public static boolean isParquetFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(PARQUET_SUFFIX) && !name.startsWith(".");
    }
}
