package com.fix42.oms.parquet;

import java.nio.file.Path;

/**
 * SQL text helpers.
 *
 * <p>DuckDB is driven here through statement text rather than bound parameters: {@code COPY
 * ... TO '<file>'} and {@code read_parquet('<file>')} take their paths as SQL <em>literals</em>,
 * not parameters. Those paths come from user configuration, so every one of them goes through
 * {@link #literal(Path)} — a path containing a quote must not be able to terminate the string
 * and continue as SQL.
 */
final class Sql {

    private Sql() {
    }

    /** Quote a SQL string literal, escaping embedded single quotes. */
    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Quote a filesystem path as a SQL string literal. */
    static String literal(Path path) {
        return literal(path.toString());
    }

    /** Quote an identifier, escaping embedded double quotes. */
    static String identifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
