package com.fix42.oms.dict;

import com.fix42.oms.fix.Tags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixDictionaryTest {

    private final FixDictionary dict = FixDictionary.fix42();

    @Test
    void allSevenInScopeMessagesArePresent() {
        for (String t : List.of("D", "8", "9", "F", "G", "H", "Q")) {
            assertTrue(dict.isKnownMessage(t), "missing message type " + t);
        }
        assertFalse(dict.isKnownMessage("A")); // Logon not modeled
    }

    @Test
    void executionReportHasContraBrokersGroup() {
        MessageDef exec = dict.message("8").orElseThrow();
        GroupDef g = exec.groupByCountTag(Tags.NO_CONTRA_BROKERS).orElseThrow();
        assertEquals(Tags.CONTRA_BROKER, g.delimiterTag());
        assertEquals(List.of(Tags.CONTRA_BROKER, Tags.CONTRA_TRADER, Tags.CONTRA_TRADE_QTY, Tags.CONTRA_TRADE_TIME),
                g.memberTags());
    }

    @Test
    void newOrderSingleAndReplaceHaveAllocsGroup() {
        GroupDef d = dict.message("D").orElseThrow().groupByCountTag(Tags.NO_ALLOCS).orElseThrow();
        GroupDef g = dict.message("G").orElseThrow().groupByCountTag(Tags.NO_ALLOCS).orElseThrow();
        assertEquals(Tags.ALLOC_ACCOUNT, d.delimiterTag());
        assertEquals(List.of(Tags.ALLOC_ACCOUNT, Tags.ALLOC_SHARES), d.memberTags());
        assertEquals(d.memberTags(), g.memberTags());
    }

    @Test
    void fieldNamesResolve() {
        assertEquals("ClOrdID", dict.fieldName(Tags.CL_ORD_ID));
        assertEquals("OrderID", dict.fieldName(Tags.ORDER_ID));
        assertEquals("ExecType", dict.fieldName(Tags.EXEC_TYPE));
        assertEquals("Tag99999", dict.fieldName(99999));
    }

    @Test
    void headerAndTrailerTagsClassified() {
        assertTrue(dict.isHeaderTag(Tags.MSG_TYPE));
        assertTrue(dict.isHeaderTag(Tags.SENDER_COMP_ID));
        assertTrue(dict.isTrailerTag(Tags.CHECK_SUM));
        assertFalse(dict.isHeaderTag(Tags.CL_ORD_ID));
    }

    @Test
    void cancelRequestHasNoGroups() {
        assertTrue(dict.message("F").orElseThrow().groups().isEmpty());
        assertTrue(dict.message("H").orElseThrow().groups().isEmpty());
        assertTrue(dict.message("Q").orElseThrow().groups().isEmpty());
    }
}
