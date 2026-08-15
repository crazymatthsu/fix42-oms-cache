package com.fix42.oms.parquet;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The column types a {@link Dataset} may declare.
 *
 * <p>Deliberately small: every type maps 1:1 onto a DuckDB SQL type, a Parquet physical
 * type, and one DuckDB appender call, so a row is a plain {@code Object[]} of boxed values
 * with {@code null} meaning SQL NULL. No nested/complex types — a FIX audit row is flat,
 * and flat columns are what give Parquet readers (Deephaven, DuckDB, Spark, Trino) working
 * predicate pushdown and column pruning.
 */
public enum ColumnType {

    VARCHAR(String.class, "VARCHAR"),
    DOUBLE(Double.class, "DOUBLE"),
    BIGINT(Long.class, "BIGINT"),
    INTEGER(Integer.class, "INTEGER"),
    BOOLEAN(Boolean.class, "BOOLEAN"),
    /** Calendar date, no time zone — used for the trading-date column. */
    DATE(LocalDate.class, "DATE"),
    /** Microsecond timestamp without time zone; this library always writes UTC. */
    TIMESTAMP(LocalDateTime.class, "TIMESTAMP");

    private final Class<?> javaType;
    private final String sqlType;

    ColumnType(Class<?> javaType, String sqlType) {
        this.javaType = javaType;
        this.sqlType = sqlType;
    }

    /** The boxed Java type a row slot of this column must hold (or {@code null}). */
    public Class<?> javaType() {
        return javaType;
    }

    /** The DuckDB/Parquet SQL type name. */
    public String sqlType() {
        return sqlType;
    }
}
