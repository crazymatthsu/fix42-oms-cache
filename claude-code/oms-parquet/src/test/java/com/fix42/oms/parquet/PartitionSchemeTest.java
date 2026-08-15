package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionSchemeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);

    @Test
    void defaultSchemeIsYearMonthDayAccountSymbol() {
        PartitionScheme scheme = PartitionScheme.dateAccountSymbol();
        assertEquals("2026/08/04/ACC1/IBM",
                scheme.relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
    }

    @Test
    void monthAndDayAreZeroPadded() {
        assertEquals("2026/01/02/A/B", PartitionScheme.dateAccountSymbol()
                .relativeDirectory(new PartitionKey(LocalDate.of(2026, 1, 2), "A", "B")));
    }

    @Test
    void hiveSchemesEmitKeyValueSegments() {
        assertEquals("date=2026-08-04/account=ACC1/symbol=IBM",
                PartitionScheme.hiveDateAccountSymbol().relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
        assertEquals("year=2026/month=08/day=04/account=ACC1/symbol=IBM",
                PartitionScheme.hiveYearMonthDayAccountSymbol()
                        .relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
    }

    @Test
    void symbolFirstAndDateOnlyVariants() {
        assertEquals("2026/08/04/IBM/ACC1",
                PartitionScheme.dateSymbolAccount().relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
        assertEquals("2026/08/04",
                PartitionScheme.dateOnly().relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
    }

    @Test
    void customTemplateIsHonoured() {
        PartitionScheme scheme = PartitionScheme.template("desk/{yyyy}-{MM}/{dd}/{symbol}");
        assertEquals("desk/2026-08/04/IBM",
                scheme.relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
    }

    @Test
    void pathUnsafeAccountAndSymbolAreSanitised() {
        PartitionScheme scheme = PartitionScheme.dateAccountSymbol();
        // A dot is legal in a symbol and is kept; a slash would create a directory level and
        // is not; traversal must be impossible.
        assertEquals("2026/08/04/ACC_1/BRK.B",
                scheme.relativeDirectory(new PartitionKey(DATE, "ACC/1", "BRK.B")));
        assertEquals("2026/08/04/_unknown/_unknown",
                scheme.relativeDirectory(new PartitionKey(DATE, "..", "")));
    }

    @Test
    void datePrefixCoversTheLeadingDateSegments() {
        assertEquals("2026/08/04", PartitionScheme.dateAccountSymbol().datePrefix(DATE));
        assertEquals("date=2026-08-04", PartitionScheme.hiveDateAccountSymbol().datePrefix(DATE));
        assertEquals("year=2026/month=08/day=04",
                PartitionScheme.hiveYearMonthDayAccountSymbol().datePrefix(DATE));
        assertEquals("2026/08/04", PartitionScheme.dateOnly().datePrefix(DATE));
    }

    @Test
    void datePrefixIsEmptyWhenTheSchemeDoesNotLeadWithDates() {
        // Account first means a day's files are scattered across every account directory, so
        // there is no prefix to scope compaction by; the compactor then walks everything.
        assertEquals("", PartitionScheme.template("{account}/{yyyy}/{MM}/{dd}").datePrefix(DATE));
    }

    @Test
    void unknownTokensAreRejectedAtConstruction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PartitionScheme.template("{yyyy}/{venue}"));
        assertTrue(e.getMessage().contains("{venue}"), e.getMessage());
    }

    @Test
    void malformedTemplatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PartitionScheme.template("{yyyy"));
        assertThrows(IllegalArgumentException.class, () -> PartitionScheme.template("  "));
        assertThrows(IllegalArgumentException.class, () -> PartitionScheme.template("///"));
    }

    @Test
    void leadingAndTrailingSlashesAreNormalisedAway() {
        assertEquals("2026/08/04/ACC1/IBM", PartitionScheme.template("/{yyyy}/{MM}/{dd}/{account}/{symbol}/")
                .relativeDirectory(new PartitionKey(DATE, "ACC1", "IBM")));
    }

    @Test
    void describeReturnsTheTemplate() {
        assertEquals("{yyyy}/{MM}/{dd}/{account}/{symbol}", PartitionScheme.dateAccountSymbol().describe());
    }
}
