package com.fix42.oms.fix;

import com.fix42.oms.dict.FixDictionary;
import com.fix42.oms.proto.FixField;
import com.fix42.oms.proto.FixGroup;
import com.fix42.oms.proto.FixMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixParserSerializerTest {
    private final FixParser parser = FixParser.fix42();

    @Test
    void parsesNewOrderSingleAndRoundTrips() {
        String raw = FixTestMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.HANDL_INST, "1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.TRANSACT_TIME, "20260115-12:00:01",
                Tags.ORDER_QTY, "100",
                Tags.ORD_TYPE, "2",
                Tags.PRICE, "420.5",
                Tags.ACCOUNT, "ACC"
        );
        FixMessage parsed = parser.parse(raw);
        assertThat(parsed.getHeader().getMsgType()).isEqualTo("D");
        assertThat(value(parsed, Tags.CL_ORD_ID)).isEqualTo("C1");
        assertThat(value(parsed, Tags.SYMBOL)).isEqualTo("MSFT");
        assertThat(value(parsed, Tags.PRICE)).isEqualTo("420.5");

        String again = FixSerializer.serialize(parsed);
        FixMessage reparsed = parser.parse(again);
        assertThat(value(reparsed, Tags.CL_ORD_ID)).isEqualTo("C1");
        assertThat(value(reparsed, Tags.PRICE)).isEqualTo("420.5");
        assertThat(reparsed.getHeader().getMsgType()).isEqualTo("D");
    }

    @Test
    void acceptsPipeDelimitedInput() {
        String stamped = FixTestMessages.er(
                Tags.ORDER_ID, "O1",
                Tags.CL_ORD_ID, "C1",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.SYMBOL, "AAPL",
                Tags.SIDE, "1",
                Tags.LEAVES_QTY, "10",
                Tags.CUM_QTY, "0",
                Tags.AVG_PX, "0"
        );
        String piped = FixTestMessages.pipe(stamped);
        assertThat(piped).contains("|").doesNotContain("\u0001");
        FixMessage parsed = parser.parse(piped);
        assertThat(parsed.getHeader().getMsgType()).isEqualTo("8");
        assertThat(value(parsed, Tags.ORDER_ID)).isEqualTo("O1");
    }

    @Test
    void parsesRepeatingAllocGroup() {
        String raw = FixTestMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "100",
                Tags.ORD_TYPE, "2",
                Tags.NO_ALLOCS, 2,
                Tags.ALLOC_ACCOUNT, "A1",
                Tags.ALLOC_SHARES, "40",
                Tags.ALLOC_ACCOUNT, "A2",
                Tags.ALLOC_SHARES, "60"
        );
        FixMessage parsed = parser.parse(raw);
        FixGroup group = parsed.getGroupsList().stream()
                .filter(g -> g.getNumInGroupTag() == Tags.NO_ALLOCS)
                .findFirst()
                .orElseThrow();
        assertThat(group.getInstancesCount()).isEqualTo(2);
        assertThat(group.getInstances(0).getFields(0).getValue()).isEqualTo("A1");
        assertThat(group.getInstances(1).getFields(0).getValue()).isEqualTo("A2");
    }

    @Test
    void parsesNestedMiscFeeInsideAlloc() {
        FixMessage.Builder b = FixMessage.newBuilder();
        b.getHeaderBuilder().setBeginString("FIX.4.2").setMsgType("D");
        b.addFields(FixField.newBuilder().setTag(Tags.CL_ORD_ID).setValue("C1"));
        b.addGroups(FixGroup.newBuilder()
                .setNumInGroupTag(Tags.NO_ALLOCS)
                .addInstances(com.fix42.oms.proto.FixGroupInstance.newBuilder()
                        .addFields(FixField.newBuilder().setTag(Tags.ALLOC_ACCOUNT).setValue("A1"))
                        .addNestedGroups(FixGroup.newBuilder()
                                .setNumInGroupTag(Tags.NO_MISC_FEES)
                                .addInstances(com.fix42.oms.proto.FixGroupInstance.newBuilder()
                                        .addFields(FixField.newBuilder().setTag(Tags.MISC_FEE_AMT).setValue("1.5"))
                                        .addFields(FixField.newBuilder().setTag(Tags.MISC_FEE_TYPE).setValue("1"))))));
        String raw = FixSerializer.serialize(b.build());
        FixMessage parsed = parser.parse(raw);
        assertThat(parsed.getGroupsCount()).isEqualTo(1);
        assertThat(parsed.getGroups(0).getInstances(0).getNestedGroupsCount()).isEqualTo(1);
        assertThat(parsed.getGroups(0).getInstances(0).getNestedGroups(0).getInstances(0).getFields(0).getValue())
                .isEqualTo("1.5");
    }

    @Test
    void rejectsBadChecksum() {
        String good = FixTestMessages.d(Tags.CL_ORD_ID, "C1", Tags.SYMBOL, "X");
        String bad = good.substring(0, good.length() - 4) + "000" + FixConstants.SOH;
        assertThatThrownBy(() -> parser.parse(bad))
                .isInstanceOf(FixParseException.class)
                .hasMessageContaining("CheckSum");
    }

    @Test
    void rejectsMissingMsgTypeWhenStrict() {
        String raw = "8=FIX.4.2\u00019=5\u000111=C1\u000110=000\u0001";
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(FixParseException.class);
    }

    @Test
    void laxParserSkipsChecksum() {
        FixParser lax = new FixParser(FixDictionary.fix42(), false, false);
        FixMessage parsed = lax.parse("8=FIX.4.2|35=H|11=C1|55=MSFT|54=1|");
        assertThat(parsed.getHeader().getMsgType()).isEqualTo("H");
        assertThat(value(parsed, Tags.CL_ORD_ID)).isEqualTo("C1");
    }

    @Test
    void dictionaryKnowsInScopeMessagesAndGroups() {
        FixDictionary dict = FixDictionary.fix42();
        assertThat(dict.message("D")).isNotNull();
        assertThat(dict.message("8").group(Tags.NO_CONTRA_BROKERS)).isNotNull();
        assertThat(dict.isNumInGroup(Tags.NO_ALLOCS)).isTrue();
        assertThat(dict.field(Tags.CL_ORD_ID).name()).isEqualTo("ClOrdID");
    }

    private static String value(FixMessage message, int tag) {
        return message.getFieldsList().stream()
                .filter(f -> f.getTag() == tag)
                .map(FixField::getValue)
                .findFirst()
                .orElse(null);
    }
}
