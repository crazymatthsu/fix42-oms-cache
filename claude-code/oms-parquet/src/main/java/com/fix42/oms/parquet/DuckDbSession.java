package com.fix42.oms.parquet;

import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One private, in-memory DuckDB instance.
 *
 * <p><b>Private, not shared.</b> Each session opens its own {@code jdbc:duckdb:} database
 * rather than duplicating a connection onto a shared one. Writing a batch means
 * {@code CREATE OR REPLACE TABLE} + append + {@code COPY}, and concurrent catalog writes on
 * a shared DuckDB instance conflict; giving each writer thread its own instance removes the
 * contention entirely. The cost is one buffer pool per session, which is why
 * {@link DuckDbConfig#memoryLimit()} is per-session.
 *
 * <p>Not thread-safe: a session belongs to exactly one thread at a time, enforced by
 * {@link DuckDbSessionPool}.
 */
final class DuckDbSession implements AutoCloseable {

    private final DuckDBConnection connection;

    private DuckDbSession(DuckDBConnection connection) {
        this.connection = connection;
    }

    /** Open a fresh in-memory instance and apply {@code config}. */
    static DuckDbSession open(DuckDbConfig config) {
        DuckDBConnection connection;
        try {
            connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        } catch (SQLException e) {
            throw new ArchiveException("Could not open an embedded DuckDB instance", e);
        }
        DuckDbSession session = new DuckDbSession(connection);
        try {
            if (config.memoryLimit() != null) {
                session.execute("SET memory_limit=" + Sql.literal(config.memoryLimit()));
            }
            if (config.threads() > 0) {
                session.execute("SET threads=" + config.threads());
            }
            if (config.tempDirectory() != null) {
                session.execute("SET temp_directory=" + Sql.literal(config.tempDirectory()));
            }
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    DuckDBConnection connection() {
        return connection;
    }

    void execute(String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new ArchiveException("DuckDB statement failed: " + sql, e);
        }
    }

    /** Run {@code sql} and return its single {@code BIGINT} result. */
    long queryLong(String sql) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new ArchiveException("DuckDB query returned no rows: " + sql);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new ArchiveException("DuckDB query failed: " + sql, e);
        }
    }

    /** Run {@code sql} and return the first column of every row as strings. */
    List<String> queryStrings(String sql) {
        List<String> out = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new ArchiveException("DuckDB query failed: " + sql, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Closing an in-memory database has nothing left to lose; releasing the pool
            // slot matters more than the failure.
        }
    }
}
