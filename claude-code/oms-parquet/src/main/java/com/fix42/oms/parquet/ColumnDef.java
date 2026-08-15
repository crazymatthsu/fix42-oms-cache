package com.fix42.oms.parquet;

/**
 * One column of a {@link Dataset}: its Parquet/DuckDB name, its type, and a short note on
 * where the value comes from (the FIX tag, or the derived {@code OrderState} field).
 *
 * @param name    column name as written into the Parquet file
 * @param type    column type
 * @param comment provenance, e.g. {@code "tag 55"} — documentation only, not persisted
 */
public record ColumnDef(String name, ColumnType type, String comment) {

    public ColumnDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Column name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Column type must not be null for " + name);
        }
        comment = (comment == null) ? "" : comment;
    }

    static ColumnDef of(String name, ColumnType type, String comment) {
        return new ColumnDef(name, type, comment);
    }
}
