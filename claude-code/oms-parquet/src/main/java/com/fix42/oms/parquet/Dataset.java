package com.fix42.oms.parquet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.fix42.oms.parquet.ColumnType.BIGINT;
import static com.fix42.oms.parquet.ColumnType.BOOLEAN;
import static com.fix42.oms.parquet.ColumnType.DATE;
import static com.fix42.oms.parquet.ColumnType.DOUBLE;
import static com.fix42.oms.parquet.ColumnType.INTEGER;
import static com.fix42.oms.parquet.ColumnType.TIMESTAMP;
import static com.fix42.oms.parquet.ColumnType.VARCHAR;

/**
 * The two Parquet datasets this module writes, and their column schemas.
 *
 * <h2>{@link #RAW_FIX_MESSAGES} — the audit trail</h2>
 * One row per captured raw FIX message ({@code 35=D,G,F,8,9,Q} by default). The full
 * original message is kept verbatim in {@code raw_fix}; the other columns are the tags
 * lifted out of it so a query engine can filter and prune columns without re-parsing FIX.
 * Lifted tag columns hold the <b>wire values</b> ({@code side='1'}, {@code ord_status='2'})
 * — this dataset is an audit copy, so nothing is normalised away, including venue-specific
 * codes the dictionary does not know.
 *
 * <h2>{@link #ORDER_STATE_CHANGES} — the derived state stream</h2>
 * One row per latest-order-state change emitted by the cache
 * ({@code OrderStateChange}) — an append-only, versioned history of every order.
 * The current state of an order is {@code last_by(chain_key ORDER BY ts, update_count)};
 * enum columns hold <b>decoded</b> names ({@code side='BUY'}) because this dataset is the
 * cache's own interpretation, and an unset enum is written as NULL rather than
 * {@code UNSPECIFIED}.
 *
 * <p><b>Why the partition columns are also stored inside the file.</b> The default partition
 * layout ({@code YYYY/MM/DD/account/symbol}) is not Hive-style, so readers do <em>not</em>
 * recover {@code event_date}/{@code account}/{@code symbol} from the directory names.
 * Every dataset therefore carries them as real columns: a plain
 * {@code read_parquet('<root>/**&#47;*.parquet')} is fully self-describing, and a Hive-style
 * scheme (see {@link PartitionScheme}) just adds path-level pruning on top.
 */
public enum Dataset {

    /** Raw captured FIX messages — the audit trail. */
    RAW_FIX_MESSAGES("fix_messages", rawFixColumns()),

    /** Latest-order-state changes folded by the cache — the derived state stream. */
    ORDER_STATE_CHANGES("order_state", orderStateColumns());

    private final String directoryName;
    private final List<ColumnDef> columns;
    private final Map<String, Integer> indexByName;

    Dataset(String directoryName, List<ColumnDef> columns) {
        this.directoryName = directoryName;
        this.columns = List.copyOf(columns);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            if (idx.put(this.columns.get(i).name(), i) != null) {
                throw new IllegalStateException("Duplicate column " + this.columns.get(i).name()
                        + " in dataset " + directoryName);
            }
        }
        this.indexByName = Map.copyOf(idx);
    }

    /** Default directory name under the archive root (overridable per archive config). */
    public String directoryName() {
        return directoryName;
    }

    public List<ColumnDef> columns() {
        return columns;
    }

    public int columnCount() {
        return columns.size();
    }

    /** Position of {@code name} in a row array. */
    public int indexOf(String name) {
        Integer i = indexByName.get(name);
        if (i == null) {
            throw new IllegalArgumentException("No column '" + name + "' in dataset " + directoryName);
        }
        return i;
    }

    /**
     * Name of the DuckDB table a batch is staged in before being copied out to Parquet.
     * Derived from the enum constant, not from the configurable directory name, so two
     * archives with different directory names still stage into the same private instance
     * without surprising each other.
     */
    String stagingTableName() {
        return "stg_" + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code CREATE OR REPLACE TABLE "<table>" (...)} for the DuckDB staging table. */
    public String createTableSql(String table) {
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE TABLE ")
                .append(Sql.identifier(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Sql.identifier(columns.get(i).name())).append(' ').append(columns.get(i).type().sqlType());
        }
        return sb.append(')').toString();
    }

    /**
     * Verify a row matches this schema: right arity, and every non-null slot assignable to
     * its column's Java type. Called once per row on the write path — a cheap guard that
     * turns a mapper/schema drift into an immediate, precise error instead of a confusing
     * DuckDB appender failure thousands of rows later.
     */
    void checkRow(Object[] row) {
        if (row.length != columns.size()) {
            throw new IllegalArgumentException("Dataset " + directoryName + " expects "
                    + columns.size() + " columns but the row has " + row.length);
        }
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v != null && !columns.get(i).type().javaType().isInstance(v)) {
                throw new IllegalArgumentException("Column " + columns.get(i).name() + " of dataset "
                        + directoryName + " expects " + columns.get(i).type().javaType().getSimpleName()
                        + " but got " + v.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------------
    // Schemas
    // ------------------------------------------------------------------

    private static List<ColumnDef> rawFixColumns() {
        List<ColumnDef> c = new ArrayList<>();
        // Partition / bookkeeping columns first: they are the common filter predicates,
        // and leading them keeps the min/max statistics a reader consults contiguous.
        c.add(ColumnDef.of("event_date", DATE, "trading date, in the configured partition zone"));
        c.add(ColumnDef.of("ts", TIMESTAMP, "capture time (UTC), per TimestampSource"));
        c.add(ColumnDef.of("writer_id", VARCHAR, "archive writer identity"));
        c.add(ColumnDef.of("ingest_seq", BIGINT, "monotonic per writer; (writer_id, ingest_seq) is unique"));

        c.add(ColumnDef.of("msg_type", VARCHAR, "tag 35"));
        c.add(ColumnDef.of("account", VARCHAR, "tag 1"));
        c.add(ColumnDef.of("symbol", VARCHAR, "tag 55"));

        c.add(ColumnDef.of("cl_ord_id", VARCHAR, "tag 11"));
        c.add(ColumnDef.of("orig_cl_ord_id", VARCHAR, "tag 41"));
        c.add(ColumnDef.of("order_id", VARCHAR, "tag 37"));
        c.add(ColumnDef.of("exec_id", VARCHAR, "tag 17"));
        c.add(ColumnDef.of("exec_ref_id", VARCHAR, "tag 19"));
        c.add(ColumnDef.of("secondary_cl_ord_id", VARCHAR, "tag 526 (default parent link)"));

        c.add(ColumnDef.of("side", VARCHAR, "tag 54, wire value"));
        c.add(ColumnDef.of("ord_type", VARCHAR, "tag 40, wire value"));
        c.add(ColumnDef.of("order_qty", DOUBLE, "tag 38"));
        c.add(ColumnDef.of("price", DOUBLE, "tag 44"));
        c.add(ColumnDef.of("stop_px", DOUBLE, "tag 99"));
        c.add(ColumnDef.of("time_in_force", VARCHAR, "tag 59, wire value"));
        c.add(ColumnDef.of("currency", VARCHAR, "tag 15"));

        c.add(ColumnDef.of("ord_status", VARCHAR, "tag 39, wire value"));
        c.add(ColumnDef.of("exec_type", VARCHAR, "tag 150, wire value"));
        c.add(ColumnDef.of("exec_trans_type", VARCHAR, "tag 20, wire value"));
        c.add(ColumnDef.of("last_qty", DOUBLE, "tag 32 (LastShares)"));
        c.add(ColumnDef.of("last_px", DOUBLE, "tag 31"));
        c.add(ColumnDef.of("last_mkt", VARCHAR, "tag 30"));
        c.add(ColumnDef.of("cum_qty", DOUBLE, "tag 14"));
        c.add(ColumnDef.of("leaves_qty", DOUBLE, "tag 151"));
        c.add(ColumnDef.of("avg_px", DOUBLE, "tag 6"));

        c.add(ColumnDef.of("text", VARCHAR, "tag 58"));
        c.add(ColumnDef.of("ord_rej_reason", INTEGER, "tag 103"));
        c.add(ColumnDef.of("cxl_rej_reason", INTEGER, "tag 102"));
        c.add(ColumnDef.of("cxl_rej_response_to", VARCHAR, "tag 434"));
        c.add(ColumnDef.of("dk_reason", VARCHAR, "tag 127"));

        c.add(ColumnDef.of("sending_ts", TIMESTAMP, "tag 52 parsed (UTC)"));
        c.add(ColumnDef.of("transact_ts", TIMESTAMP, "tag 60 parsed (UTC)"));
        c.add(ColumnDef.of("msg_seq_num", BIGINT, "tag 34"));
        c.add(ColumnDef.of("sender_comp_id", VARCHAR, "tag 49"));
        c.add(ColumnDef.of("target_comp_id", VARCHAR, "tag 56"));
        c.add(ColumnDef.of("poss_dup_flag", VARCHAR, "tag 43"));

        c.add(ColumnDef.of("raw_fix", VARCHAR, "the complete original message"));
        return c;
    }

    private static List<ColumnDef> orderStateColumns() {
        List<ColumnDef> c = new ArrayList<>();
        c.add(ColumnDef.of("event_date", DATE, "trading date, in the configured partition zone"));
        c.add(ColumnDef.of("ts", TIMESTAMP, "arrival time of the causing message (UTC)"));
        c.add(ColumnDef.of("writer_id", VARCHAR, "archive writer identity"));
        c.add(ColumnDef.of("ingest_seq", BIGINT, "monotonic per writer; (writer_id, ingest_seq) is unique"));

        // chain_key is the identity to group/last_by on. It must be stable for the WHOLE of a
        // chain's life, which rules out both OrderID (absent until the first ExecutionReport)
        // and the current ClOrdID (changes on every replace). The chain's FIRST ClOrdID never
        // changes, so that is the key; a chain that never carried one falls back to OrderID.
        c.add(ColumnDef.of("chain_key", VARCHAR, "first cl_ord_id of the chain, else order_id"));
        c.add(ColumnDef.of("order_id", VARCHAR, "tag 37"));
        c.add(ColumnDef.of("cl_ord_id", VARCHAR, "tag 11, current"));
        c.add(ColumnDef.of("orig_cl_ord_id", VARCHAR, "tag 41, most recent"));
        c.add(ColumnDef.of("account", VARCHAR, "tag 1"));
        c.add(ColumnDef.of("symbol", VARCHAR, "tag 55"));

        c.add(ColumnDef.of("side", VARCHAR, "decoded Side"));
        c.add(ColumnDef.of("ord_type", VARCHAR, "decoded OrdType"));
        c.add(ColumnDef.of("order_qty", DOUBLE, "tag 38, current effective"));
        c.add(ColumnDef.of("price", DOUBLE, "tag 44"));
        c.add(ColumnDef.of("stop_px", DOUBLE, "tag 99"));
        c.add(ColumnDef.of("time_in_force", VARCHAR, "decoded TimeInForce"));
        c.add(ColumnDef.of("currency", VARCHAR, "tag 15"));

        c.add(ColumnDef.of("ord_status", VARCHAR, "decoded OrdStatus after this change"));
        c.add(ColumnDef.of("prev_ord_status", VARCHAR, "decoded OrdStatus before this change"));
        c.add(ColumnDef.of("last_exec_type", VARCHAR, "decoded ExecType"));
        c.add(ColumnDef.of("is_terminal", BOOLEAN, "ord_status is a terminal state"));
        c.add(ColumnDef.of("cum_qty", DOUBLE, "tag 14"));
        c.add(ColumnDef.of("leaves_qty", DOUBLE, "tag 151"));
        c.add(ColumnDef.of("avg_px", DOUBLE, "tag 6"));
        c.add(ColumnDef.of("last_qty", DOUBLE, "tag 32 of the most recent fill"));
        c.add(ColumnDef.of("last_px", DOUBLE, "tag 31 of the most recent fill"));
        c.add(ColumnDef.of("last_mkt", VARCHAR, "tag 30"));

        c.add(ColumnDef.of("text", VARCHAR, "tag 58"));
        c.add(ColumnDef.of("ord_rej_reason", INTEGER, "tag 103"));
        c.add(ColumnDef.of("cxl_rej_reason", INTEGER, "tag 102"));

        c.add(ColumnDef.of("parent_order_id", VARCHAR, "parent link, empty for top-level orders"));
        c.add(ColumnDef.of("is_parent", BOOLEAN, "this chain has children"));
        c.add(ColumnDef.of("child_order_count", INTEGER, "number of linked children"));
        c.add(ColumnDef.of("cl_ord_id_chain", VARCHAR, "every ClOrdID of the chain, comma separated"));
        c.add(ColumnDef.of("exec_id_count", INTEGER, "number of ExecIDs applied"));

        c.add(ColumnDef.of("update_count", BIGINT, "messages folded into this state; the version"));
        c.add(ColumnDef.of("first_seen_ts", TIMESTAMP, "first message of the chain (UTC)"));
        c.add(ColumnDef.of("last_update_ts", TIMESTAMP, "most recent fold (UTC)"));
        c.add(ColumnDef.of("last_msg_type", VARCHAR, "tag 35 of the most recent message"));

        c.add(ColumnDef.of("change_kind", VARCHAR, "CREATE | UPDATE | PARENT_ROLLUP | RECOVERY"));
        c.add(ColumnDef.of("cause_msg_type", VARCHAR, "tag 35 of the triggering message"));
        c.add(ColumnDef.of("cause_cl_ord_id", VARCHAR, "tag 11 of the triggering message"));
        c.add(ColumnDef.of("cause_exec_id", VARCHAR, "tag 17 of the triggering message"));
        return c;
    }
}
