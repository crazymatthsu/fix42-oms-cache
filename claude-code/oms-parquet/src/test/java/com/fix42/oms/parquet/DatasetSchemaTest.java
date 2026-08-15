package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetSchemaTest {

    @Test
    void everyDatasetCarriesThePartitionColumnsAsRealColumns() {
        // The default partition layout is not Hive-style, so a reader cannot recover these
        // from the path; they have to be inside the file for the dataset to be self-describing.
        for (Dataset dataset : Dataset.values()) {
            for (String required : new String[]{"event_date", "ts", "account", "symbol", "writer_id", "ingest_seq"}) {
                assertTrue(dataset.columns().stream().anyMatch(c -> c.name().equals(required)),
                        dataset + " is missing column " + required);
            }
        }
    }

    @Test
    void columnNamesAreUniqueWithinADataset() {
        for (Dataset dataset : Dataset.values()) {
            Set<String> names = new HashSet<>();
            for (ColumnDef column : dataset.columns()) {
                assertTrue(names.add(column.name()), "duplicate column " + column.name() + " in " + dataset);
            }
            assertEquals(dataset.columnCount(), names.size());
        }
    }

    @Test
    void firstColumnsAreTheCommonFilterPredicates() {
        for (Dataset dataset : Dataset.values()) {
            assertEquals(0, dataset.indexOf("event_date"));
            assertEquals(1, dataset.indexOf("ts"));
        }
    }

    @Test
    void rawDatasetKeepsTheWholeOriginalMessage() {
        assertTrue(Dataset.RAW_FIX_MESSAGES.columns().stream()
                .anyMatch(c -> c.name().equals("raw_fix") && c.type() == ColumnType.VARCHAR));
    }

    @Test
    void orderStateDatasetCarriesTheVersioningColumns() {
        // last_by(chain_key ORDER BY ts, update_count) is the documented way to get latest
        // state; all three columns have to exist for that to work.
        for (String required : new String[]{"chain_key", "update_count", "change_kind", "is_terminal"}) {
            assertTrue(Dataset.ORDER_STATE_CHANGES.columns().stream().anyMatch(c -> c.name().equals(required)),
                    "missing " + required);
        }
    }

    @Test
    void createTableSqlQuotesIdentifiersAndDeclaresEveryColumn() {
        String sql = Dataset.RAW_FIX_MESSAGES.createTableSql("stg");
        assertTrue(sql.startsWith("CREATE OR REPLACE TABLE \"stg\" ("), sql);
        assertTrue(sql.contains("\"event_date\" DATE"), sql);
        assertTrue(sql.contains("\"ts\" TIMESTAMP"), sql);
        assertTrue(sql.contains("\"order_qty\" DOUBLE"), sql);
        assertTrue(sql.contains("\"ord_rej_reason\" INTEGER"), sql);
        assertEquals(Dataset.RAW_FIX_MESSAGES.columnCount(), sql.split(",").length);
    }

    @Test
    void unknownColumnLookupFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> Dataset.RAW_FIX_MESSAGES.indexOf("nope"));
    }

    @Test
    void rowsOfTheWrongShapeAreRejectedBeforeTheyReachDuckDb() {
        Dataset dataset = Dataset.ORDER_STATE_CHANGES;
        assertThrows(IllegalArgumentException.class, () -> dataset.checkRow(new Object[3]));

        Object[] row = new Object[dataset.columnCount()];
        row[dataset.indexOf("event_date")] = "2026-08-04"; // String where a LocalDate belongs
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> dataset.checkRow(row));
        assertTrue(e.getMessage().contains("event_date"), e.getMessage());
    }

    @Test
    void allNullRowIsValid() {
        // Every column is nullable: a message may legitimately carry none of the lifted tags.
        Dataset.RAW_FIX_MESSAGES.checkRow(new Object[Dataset.RAW_FIX_MESSAGES.columnCount()]);
    }

    @Test
    void correctlyTypedRowPasses() {
        Dataset dataset = Dataset.RAW_FIX_MESSAGES;
        Object[] row = new Object[dataset.columnCount()];
        row[dataset.indexOf("event_date")] = LocalDate.of(2026, 8, 4);
        row[dataset.indexOf("ts")] = LocalDateTime.of(2026, 8, 4, 13, 30);
        row[dataset.indexOf("ingest_seq")] = 1L;
        row[dataset.indexOf("order_qty")] = 100.0d;
        row[dataset.indexOf("ord_rej_reason")] = 11;
        row[dataset.indexOf("symbol")] = "IBM";
        dataset.checkRow(row);
    }

    @Test
    void stagingTableNamesAreDistinctPerDataset() {
        assertEquals("stg_raw_fix_messages", Dataset.RAW_FIX_MESSAGES.stagingTableName());
        assertEquals("stg_order_state_changes", Dataset.ORDER_STATE_CHANGES.stagingTableName());
    }
}
