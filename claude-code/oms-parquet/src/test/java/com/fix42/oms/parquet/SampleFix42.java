package com.fix42.oms.parquet;

import com.fix42.oms.fix.FixParser;
import com.fix42.oms.proto.FixMessage;

import java.util.List;

/**
 * A realistic FIX 4.2 drop-copy stream used across the Parquet tests.
 *
 * <p>Covers every in-scope MsgType and the awkward cases that matter to the archive: a
 * parent order worked as two children (linked by tag 526), a cancel/replace that changes the
 * ClOrdID mid-chain, a cancel that is rejected, a DK trade, a second account/symbol so more
 * than one partition is exercised, and an OrderStatusRequest ({@code 35=H}) that must be
 * captured by the cache but <b>not</b> by the raw dataset.
 *
 * <p>Pipe-delimited so a test can assert the {@code raw_fix} column round-trips exactly:
 * re-serialising a parsed message verbatim with the {@code '|'} delimiter reproduces the
 * input string character for character.
 */
final class SampleFix42 {

    static final String ACCOUNT_1 = "ACC1";
    static final String ACCOUNT_2 = "ACC2";
    static final String SYMBOL_IBM = "IBM";
    static final String SYMBOL_MSFT = "MSFT";

    // ----- parent order: 1000 IBM, worked as two children -----
    static final String PARENT_NOS =
            "8=FIX.4.2|9=0|35=D|34=1|49=BUYSIDE|56=SELLSIDE|52=20260814-13:30:00.000|"
                    + "11=PARENT1|1=ACC1|55=IBM|54=1|38=1000|40=2|44=185.50|59=0|15=USD|21=1|"
                    + "60=20260814-13:30:00.000|10=000|";

    static final String CHILD1_NOS =
            "35=D|34=2|49=BUYSIDE|56=SELLSIDE|52=20260814-13:30:01.000|"
                    + "11=CHILD1|526=PARENT1|1=ACC1|55=IBM|54=1|38=600|40=2|44=185.50|59=0|";
    static final String CHILD1_ACK =
            "35=8|34=3|49=SELLSIDE|56=BUYSIDE|52=20260814-13:30:01.100|"
                    + "11=CHILD1|526=PARENT1|37=EX-C1|17=E1|20=0|150=0|39=0|1=ACC1|55=IBM|54=1|38=600|151=600|14=0|";
    static final String CHILD1_PARTIAL_FILL =
            "35=8|34=4|49=SELLSIDE|56=BUYSIDE|52=20260814-13:31:00.000|"
                    + "11=CHILD1|526=PARENT1|37=EX-C1|17=E2|20=0|150=1|39=1|1=ACC1|55=IBM|54=1|38=600|"
                    + "32=250|31=185.48|30=N|151=350|14=250|6=185.48|";
    static final String CHILD1_FILL =
            "35=8|34=5|49=SELLSIDE|56=BUYSIDE|52=20260814-13:32:00.000|"
                    + "11=CHILD1|526=PARENT1|37=EX-C1|17=E3|20=0|150=2|39=2|1=ACC1|55=IBM|54=1|38=600|"
                    + "32=350|31=185.52|30=N|151=0|14=600|6=185.503|";

    static final String CHILD2_NOS =
            "35=D|34=6|49=BUYSIDE|56=SELLSIDE|52=20260814-13:32:30.000|"
                    + "11=CHILD2|526=PARENT1|1=ACC1|55=IBM|54=1|38=400|40=2|44=185.50|59=0|";
    static final String CHILD2_ACK =
            "35=8|34=7|49=SELLSIDE|56=BUYSIDE|52=20260814-13:32:30.100|"
                    + "11=CHILD2|526=PARENT1|37=EX-C2|17=E4|20=0|150=0|39=0|1=ACC1|55=IBM|54=1|38=400|151=400|14=0|";

    /** Replace CHILD2 down to 300 shares: new ClOrdID, OrigClOrdID points at the old one. */
    static final String CHILD2_REPLACE_REQUEST =
            "35=G|34=8|49=BUYSIDE|56=SELLSIDE|52=20260814-13:33:00.000|"
                    + "11=CHILD2R|41=CHILD2|37=EX-C2|1=ACC1|55=IBM|54=1|38=300|40=2|44=185.45|";
    static final String CHILD2_REPLACED_ACK =
            "35=8|34=9|49=SELLSIDE|56=BUYSIDE|52=20260814-13:33:00.200|"
                    + "11=CHILD2R|41=CHILD2|37=EX-C2|17=E5|20=0|150=5|39=0|1=ACC1|55=IBM|54=1|38=300|"
                    + "44=185.45|151=300|14=0|";

    /** Cancel CHILD2R, and have the venue reject the cancel (too late). */
    static final String CHILD2_CANCEL_REQUEST =
            "35=F|34=10|49=BUYSIDE|56=SELLSIDE|52=20260814-13:34:00.000|"
                    + "11=CHILD2C|41=CHILD2R|37=EX-C2|1=ACC1|55=IBM|54=1|38=300|";
    static final String CHILD2_CANCEL_REJECT =
            "35=9|34=11|49=SELLSIDE|56=BUYSIDE|52=20260814-13:34:00.300|"
                    + "11=CHILD2C|41=CHILD2R|37=EX-C2|39=0|434=1|102=0|58=Too late to cancel|";

    /** A DK on the second child's fill. */
    static final String DK_TRADE =
            "35=Q|34=12|49=SELLSIDE|56=BUYSIDE|52=20260814-13:35:00.000|"
                    + "37=EX-C2|17=E5|127=A|1=ACC1|55=IBM|54=1|38=300|32=100|31=185.44|58=Unknown trade|";

    /** Not captured by the raw dataset: a status query, not an order event. */
    static final String ORDER_STATUS_REQUEST =
            "35=H|34=13|49=BUYSIDE|56=SELLSIDE|52=20260814-13:36:00.000|"
                    + "11=CHILD2R|37=EX-C2|1=ACC1|55=IBM|54=1|54=1|";

    /** A second account and symbol, so the archive has to build more than one partition. */
    static final String MSFT_NOS =
            "35=D|34=14|49=BUYSIDE|56=SELLSIDE|52=20260814-13:40:00.000|"
                    + "11=MSFT1|1=ACC2|55=MSFT|54=2|38=500|40=1|59=0|";
    static final String MSFT_REJECT =
            "35=8|34=15|49=SELLSIDE|56=BUYSIDE|52=20260814-13:40:00.500|"
                    + "11=MSFT1|37=EX-M1|17=E6|20=0|150=8|39=8|1=ACC2|55=MSFT|54=2|38=500|151=0|14=0|"
                    + "103=11|58=Unknown symbol for account|";

    private SampleFix42() {
    }

    /** The whole stream, in arrival order. */
    static List<String> stream() {
        return List.of(
                PARENT_NOS,
                CHILD1_NOS, CHILD1_ACK, CHILD1_PARTIAL_FILL, CHILD1_FILL,
                CHILD2_NOS, CHILD2_ACK,
                CHILD2_REPLACE_REQUEST, CHILD2_REPLACED_ACK,
                CHILD2_CANCEL_REQUEST, CHILD2_CANCEL_REJECT,
                DK_TRADE,
                ORDER_STATUS_REQUEST,
                MSFT_NOS, MSFT_REJECT);
    }

    /** Messages of {@link #stream()} the raw dataset is expected to capture (everything but 35=H). */
    static List<String> capturedStream() {
        return stream().stream().filter(m -> !m.contains("|35=H|") && !m.startsWith("35=H|")).toList();
    }

    static FixMessage parse(String raw) {
        return FixParser.pipe().parse(raw);
    }
}
