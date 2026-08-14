package com.fix42.oms.mapper;

import com.fix42.oms.dict.FixDictionary;
import com.fix42.oms.fix.FixParser;
import com.fix42.oms.fix.FixSupport;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.proto.ExecType;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrdStatus;
import com.fix42.oms.proto.OrdType;
import com.fix42.oms.proto.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixMessageMapperTest {

    @Test
    void newOrderSingleToTypedReadsScalarsAndEnums() {
        FixMessage m = FixParser.pipe().parse(
                "8=FIX.4.2|35=D|11=ORD1|1=ACC|21=1|55=IBM|54=1|38=1000|40=2|44=185.5|59=0|60=T|10=000|");
        NewOrderSingle o = FixMessageMapper.toNewOrderSingle(m);

        assertEquals("ORD1", o.getClOrdId());
        assertEquals("ACC", o.getAccount());
        assertEquals("IBM", o.getSymbol());
        assertEquals(Side.SIDE_BUY, o.getSide());
        assertEquals(OrdType.ORD_TYPE_LIMIT, o.getOrdType());
        assertEquals(1000.0, o.getOrderQty());
        assertEquals(185.5, o.getPrice());
    }

    @Test
    void newOrderSingleTypedRoundTripPreservesModeledFields() {
        FixMessage m = FixParser.pipe().parse(
                "8=FIX.4.2|35=D|11=ORD1|1=ACC|21=1|55=IBM|54=2|38=500|40=2|44=99.25|59=1|10=000|");
        NewOrderSingle typed = FixMessageMapper.toNewOrderSingle(m);
        FixMessage back = FixMessageMapper.fromNewOrderSingle(typed);
        NewOrderSingle typed2 = FixMessageMapper.toNewOrderSingle(back);

        assertEquals(typed.getClOrdId(), typed2.getClOrdId());
        assertEquals(typed.getSymbol(), typed2.getSymbol());
        assertEquals(typed.getSide(), typed2.getSide());
        assertEquals(typed.getOrdType(), typed2.getOrdType());
        assertEquals(typed.getOrderQty(), typed2.getOrderQty());
        assertEquals(typed.getPrice(), typed2.getPrice());
        assertEquals(typed.getHandlInst(), typed2.getHandlInst());
        assertEquals("D", FixSupport.msgType(back));
    }

    @Test
    void executionReportWithContraBrokersGroupRoundTrips() {
        FixMessage m = FixParser.pipe().parse(
                "8=FIX.4.2|35=8|37=EX1|17=E1|20=0|150=2|39=2|55=IBM|54=1|38=100|32=100|31=50|151=0|14=100|6=50|"
                        + "382=2|375=BRKA|337=T1|437=60|438=t1|375=BRKB|337=T2|437=40|438=t2|10=000|");

        ExecutionReport er = FixMessageMapper.toExecutionReport(m);
        assertEquals(ExecType.EXEC_TYPE_FILL, er.getExecType());
        assertEquals(OrdStatus.ORD_STATUS_FILLED, er.getOrdStatus());
        assertEquals(2, er.getContraBrokersCount());
        assertEquals("BRKA", er.getContraBrokers(0).getContraBroker());
        assertEquals("T1", er.getContraBrokers(0).getContraTrader());
        assertEquals(60.0, er.getContraBrokers(0).getContraTradeQty());
        assertEquals("BRKB", er.getContraBrokers(1).getContraBroker());

        // Round-trip: typed -> generic -> re-extract the group.
        FixMessage back = FixMessageMapper.fromExecutionReport(er);
        ExecutionReport er2 = FixMessageMapper.toExecutionReport(back);
        assertEquals(2, er2.getContraBrokersCount());
        assertEquals("BRKB", er2.getContraBrokers(1).getContraBroker());
        assertEquals(40.0, er2.getContraBrokers(1).getContraTradeQty());
    }

    @Test
    void groupExtractStopsAtFirstNonMemberTag() {
        FixMessage m = FixParser.pipe().parse(
                "35=D|78=2|79=ACCT_A|80=600|79=ACCT_B|80=400|55=IBM|10=000|");
        List<List<com.fix42.oms.proto.FixField>> entries = Groups.extract(
                m, FixDictionary.fix42().message("D").orElseThrow().groups().get(0));
        assertEquals(2, entries.size());
        assertEquals("ACCT_A", Groups.value(entries.get(0), Tags.ALLOC_ACCOUNT));
        assertEquals("600", Groups.value(entries.get(0), Tags.ALLOC_SHARES));
        assertEquals("ACCT_B", Groups.value(entries.get(1), Tags.ALLOC_ACCOUNT));
    }

    @Test
    void numericFormattingDropsTrailingDotZero() {
        assertEquals("1000", FixMessageMapper.fmt(1000.0));
        assertEquals("185.5", FixMessageMapper.fmt(185.5));
        assertEquals("0", FixMessageMapper.fmt(0.0));
    }
}
