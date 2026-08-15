package com.fix42.oms.parquet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Queries an archive with DuckDB — the intraday and historical read path, and the thing to
 * point at when checking what the writer actually produced.
 *
 * <pre>{@code
 * try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
 *     // every order's latest state, as of now, for one account
 *     List<Map<String, Object>> open = reader.query(
 *         "SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY chain_key ORDER BY ts DESC, update_count DESC) rn"
 *       + "              FROM " + reader.scanSql(Dataset.ORDER_STATE_CHANGES)
 *       + "              WHERE account = 'ACC1') WHERE rn = 1 AND NOT is_terminal");
 * }
 * }</pre>
 *
 * <p>The scan expressions are the same ones any other engine should use, so they are worth
 * reading: files are listed by glob with {@code union_by_name} (a partition may hold files
 * written before and after a schema addition) and with {@code hive_partitioning=0}
 * (deliberately — {@code account}, {@code symbol} and {@code event_date} are real columns
 * inside every file, so deriving them from a Hive-style path as well would collide).
 *
 * <p>Read-only and independent of any live {@link ParquetArchive}: a separate process can
 * open one over the same directory while capture continues, and will see every file that has
 * been renamed into place.
 */
public final class ParquetArchiveReader implements Closeable {

    private final ParquetArchiveConfig config;
    private final DuckDbSession session;

    public ParquetArchiveReader(ParquetArchiveConfig config) {
        this.config = config;
        this.session = DuckDbSession.open(config.duckDb());
    }

    /** A {@code read_parquet(...)} expression over every file of {@code dataset}. */
    public String scanSql(Dataset dataset) {
        return scanSql(dataset, null);
    }

    /**
     * A {@code read_parquet(...)} expression scoped to one trading day, when the partition
     * scheme starts with date segments — the cheap way to query a single day, because the
     * files of other days are never opened.
     *
     * @param date the trading day, or {@code null} for the whole dataset
     */
    public String scanSql(Dataset dataset, LocalDate date) {
        String prefix = (date == null) ? "" : config.partitionScheme().datePrefix(date);
        String root = config.datasetRoot(dataset).toString();
        String glob = prefix.isEmpty()
                ? root + "/**/*" + ParquetPaths.PARQUET_SUFFIX
                : root + '/' + prefix + "/**/*" + ParquetPaths.PARQUET_SUFFIX;
        return "read_parquet(" + Sql.literal(glob) + ", union_by_name=true, hive_partitioning=0)";
    }

    /** Row count of {@code dataset}, or 0 if nothing has been written yet. */
    public long count(Dataset dataset) {
        return count(dataset, null);
    }

    /** Row count of {@code dataset} for one trading day. */
    public long count(Dataset dataset, LocalDate date) {
        try {
            return session.queryLong("SELECT count(*) FROM " + scanSql(dataset, date));
        } catch (ArchiveException e) {
            // DuckDB fails a glob that matches nothing, and "nothing written yet" is not an
            // error. Confirm the scope really is empty before swallowing it — a corrupt or
            // unreadable file must not be reported as a count of zero.
            if (isEmpty(dataset, date)) {
                return 0L;
            }
            throw e;
        }
    }

    private boolean isEmpty(Dataset dataset, LocalDate date) {
        String prefix = (date == null) ? "" : config.partitionScheme().datePrefix(date);
        Path scope = prefix.isEmpty() ? config.datasetRoot(dataset) : config.datasetRoot(dataset).resolve(prefix);
        if (!Files.isDirectory(scope)) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(scope)) {
            return walk.filter(Files::isRegularFile).noneMatch(ParquetPaths::isParquetFile);
        } catch (IOException e) {
            return false;
        }
    }

    /** Run {@code sql} and materialise the result, column name to value, in column order. */
    public List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= columns; c++) {
                    row.put(meta.getColumnLabel(c), rs.getObject(c));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new ArchiveException("Query failed: " + sql, e);
        }
    }

    /** Every row of {@code dataset}, ordered by capture sequence. */
    public List<Map<String, Object>> readAll(Dataset dataset) {
        return query("SELECT * FROM " + scanSql(dataset) + " ORDER BY ingest_seq");
    }

    @Override
    public void close() {
        session.close();
    }
}
