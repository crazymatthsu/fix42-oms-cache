package com.fix42.oms.parquet;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes a batch of rows to a Parquet file through an embedded DuckDB instance.
 *
 * <p><b>Staging table, then {@code COPY}.</b> Rows go into a per-session staging table via
 * DuckDB's appender — the fast bulk path, roughly an order of magnitude quicker than
 * {@code INSERT} batches — and the table is then copied out with
 * {@code COPY (SELECT * FROM stg) TO '<file>' (FORMAT PARQUET, ...)}. Insertion order is
 * preserved, so rows land in the file in arrival order and the {@code ts} column's row-group
 * statistics stay tight, which is what makes time-range predicates skip row groups.
 *
 * <p><b>Nothing incomplete is ever visible.</b> Every file is written to a hidden
 * {@code .<name>.tmp} sibling and moved into place atomically. An intraday reader watching
 * the directory (a Deephaven table, a {@code read_parquet} glob) therefore only ever opens
 * finished files — the alternative, a reader hitting a half-written Parquet footer, is not a
 * recoverable error for most engines.
 */
final class DuckDbParquetWriter {

    private final DuckDbConfig config;
    private final DuckDbSessionPool pool;

    DuckDbParquetWriter(DuckDbConfig config, DuckDbSessionPool pool) {
        this.config = config;
        this.pool = pool;
    }

    /** Outcome of one file write. */
    record WriteResult(Path file, long rows, long bytes) {
    }

    /**
     * Stage {@code rows} and write them to {@code target}.
     *
     * @throws ArchiveException if staging, the copy, or the rename fails
     */
    WriteResult write(Dataset dataset, List<Object[]> rows, Path target) {
        DuckDbSession session = pool.borrow();
        boolean healthy = false;
        try {
            String table = dataset.stagingTableName();
            session.execute(dataset.createTableSql(table));
            appendRows(session, dataset, table, rows);
            long bytes = copyToParquet(session, "SELECT * FROM " + Sql.identifier(table), target);
            // Release the staged rows now rather than at the next flush: the session is
            // pooled and would otherwise hold a whole batch of memory until reused.
            session.execute("DROP TABLE IF EXISTS " + Sql.identifier(table));
            healthy = true;
            return new WriteResult(target, rows.size(), bytes);
        } finally {
            if (healthy) {
                pool.release(session);
            } else {
                pool.discard(session);
            }
        }
    }

    /**
     * Run {@code selectSql} and write its result to {@code target} as Parquet, via a hidden
     * temp file and an atomic rename.
     *
     * @return the size of the finished file in bytes
     */
    long copyToParquet(DuckDbSession session, String selectSql, Path target) {
        Path temp = ParquetPaths.tempSibling(target);
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            throw new ArchiveException("Could not prepare " + target.getParent() + " for writing", e);
        }
        try {
            session.execute("COPY (" + selectSql + ") TO " + Sql.literal(temp) + " (" + copyOptions() + ")");
            return moveIntoPlace(temp, target);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    /** The {@code COPY ... TO} option list, shared by streaming writes and compaction. */
    String copyOptions() {
        return "FORMAT PARQUET, COMPRESSION " + Sql.literal(config.compression().duckDbName())
                + ", ROW_GROUP_SIZE " + config.rowGroupSize();
    }

    private static long moveIntoPlace(Path temp, Path target) {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network filesystems cannot do an atomic rename; a plain replace still
                // narrows the window a reader could see a partial file to the copy itself.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.size(target);
        } catch (IOException e) {
            throw new ArchiveException("Could not move " + temp + " into place at " + target,
                    new UncheckedIOException(e));
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best effort: the temp file is hidden and will be overwritten by the next attempt.
        }
    }

    private static void appendRows(DuckDbSession session, Dataset dataset, String table, List<Object[]> rows) {
        DuckDBConnection connection = session.connection();
        List<ColumnDef> columns = dataset.columns();
        // Rows were shape-checked against the schema when they were accepted (see
        // BatchingRowSink.add), which reports drift with the ingest caller's stack rather
        // than a writer thread's.
        try (DuckDBAppender appender = connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, table)) {
            for (Object[] row : rows) {
                appender.beginRow();
                for (int c = 0; c < columns.size(); c++) {
                    appendValue(appender, columns.get(c).type(), row[c]);
                }
                appender.endRow();
            }
            appender.flush();
        } catch (SQLException e) {
            throw new ArchiveException("Could not stage " + rows.size() + " rows into " + table, e);
        }
    }

    private static void appendValue(DuckDBAppender appender, ColumnType type, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
            return;
        }
        switch (type) {
            case VARCHAR -> appender.append((String) value);
            case DOUBLE -> appender.append(((Double) value).doubleValue());
            case BIGINT -> appender.append(((Long) value).longValue());
            case INTEGER -> appender.append(((Integer) value).intValue());
            case BOOLEAN -> appender.append(((Boolean) value).booleanValue());
            case DATE -> appender.append((LocalDate) value);
            case TIMESTAMP -> appender.append((LocalDateTime) value);
        }
    }
}
