package com.fix42.oms.cache;

import com.fix42.oms.fix.Tags;
import com.fix42.oms.persist.FsyncMode;
import com.fix42.oms.persist.PersistenceConfig;
import com.fix42.oms.persist.SourceCursor;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceRecoveryTest {

    @TempDir
    Path dir;

    @Test
    void recoversLatestStateIndexesHistoryAndCursor() {
        CacheConfig config = persistentConfig(dir, 1);
        Collection<OrderState> before;
        try (InMemoryOrderCache cache = InMemoryOrderCache.recover(config).orderCache()) {
            playTape(cache, 0);
            cache.ingest(fillC2(), SourceCursor.of("file:drop.copy:offset=9"));
            before = cache.snapshot();
            assertThat(cache.getByClOrdId("C1")).isPresent();
            assertThat(cache.getByOrderId("B2")).isPresent();
            assertThat(cache.getHistory(cache.getByOrderId("B1").orElseThrow().getOrderKey())).isNotEmpty();
        }

        Recovery recovery = InMemoryOrderCache.recover(config);
        try (InMemoryOrderCache cache = recovery.orderCache()) {
            assertThat(recovery.sourceCursor()).contains("file:drop.copy:offset=9");
            assertThat(normalized(cache.snapshot())).containsExactlyInAnyOrderElementsOf(normalized(before));
            assertThat(cache.getByClOrdId("C1").orElseThrow().getOrderKey()).isEqualTo("B1");
            assertThat(cache.getByClOrdId("C1b").orElseThrow().getOrderQty()).isEqualTo(300.0);
            assertThat(cache.getByExecId("E2")).isPresent();
            assertThat(cache.getChildren("P1")).hasSize(2);
            assertThat(cache.findByAccount("PROP")).hasSize(3);
            assertThat(cache.getHistory("B1")).isNotEmpty();
            assertThat(cache.rollup("P1").cumQty()).isEqualTo(600.0);
        }
    }

    @Test
    void walTailReplaysAfterSnapshot() {
        CacheConfig config = CacheConfig.builder()
                .persistence(PersistenceConfig.builder()
                        .dataDir(dir)
                        .fsync(FsyncMode.EVERY_RECORD)
                        .snapshotEveryMessages(2)
                        .snapshotEvery(Duration.ZERO)
                        .build())
                .build();
        try (InMemoryOrderCache cache = InMemoryOrderCache.recover(config).orderCache()) {
            cache.ingest(FixMessages.d(
                    Tags.CL_ORD_ID, "C1",
                    Tags.SYMBOL, "IBM",
                    Tags.SIDE, "1",
                    Tags.ORDER_QTY, "10",
                    Tags.ORD_TYPE, "2"
            ));
            cache.ingest(FixMessages.er(
                    Tags.CL_ORD_ID, "C1",
                    Tags.ORDER_ID, "O1",
                    Tags.EXEC_ID, "E1",
                    Tags.EXEC_TRANS_TYPE, "0",
                    Tags.EXEC_TYPE, "0",
                    Tags.ORD_STATUS, "0",
                    Tags.CUM_QTY, "0",
                    Tags.LEAVES_QTY, "10",
                    Tags.AVG_PX, "0"
            ));
            cache.ingest(FixMessages.er(
                    Tags.CL_ORD_ID, "C1",
                    Tags.ORDER_ID, "O1",
                    Tags.EXEC_ID, "E2",
                    Tags.EXEC_TRANS_TYPE, "0",
                    Tags.EXEC_TYPE, "2",
                    Tags.ORD_STATUS, "2",
                    Tags.CUM_QTY, "10",
                    Tags.LEAVES_QTY, "0",
                    Tags.AVG_PX, "5"
            ));
        }
        try (InMemoryOrderCache cache = InMemoryOrderCache.recover(config).orderCache()) {
            OrderState state = cache.getByOrderId("O1").orElseThrow();
            assertThat(state.getCumQty()).isEqualTo(10.0);
            assertThat(state.getOrdStatus()).isEqualTo("2");
            assertThat(cache.getByExecId("E2")).isPresent();
        }
    }

    static CacheConfig persistentConfig(Path dir, int snapshotEvery) {
        return CacheConfig.builder()
                .persistence(PersistenceConfig.builder()
                        .dataDir(dir)
                        .fsync(FsyncMode.EVERY_RECORD)
                        .snapshotEveryMessages(snapshotEvery)
                        .snapshotEvery(Duration.ZERO)
                        .build())
                .build();
    }

    static void playTape(InMemoryOrderCache cache, int fromInclusive) {
        String[] tape = tape();
        for (int i = fromInclusive; i < tape.length; i++) {
            cache.ingest(tape[i], SourceCursor.of("file:drop.copy:offset=" + i));
        }
    }

    static String[] tape() {
        return new String[] {
                FixMessages.d(
                        Tags.CL_ORD_ID, "P1",
                        Tags.ACCOUNT, "PROP",
                        Tags.SYMBOL, "MSFT",
                        Tags.SIDE, "1",
                        Tags.ORDER_QTY, "1000",
                        Tags.ORD_TYPE, "2",
                        Tags.PRICE, "420"
                ),
                FixMessages.d(
                        Tags.CL_ORD_ID, "C1",
                        Tags.ACCOUNT, "PROP",
                        Tags.SYMBOL, "MSFT",
                        Tags.SIDE, "1",
                        Tags.ORDER_QTY, "400",
                        Tags.ORD_TYPE, "2",
                        Tags.PRICE, "420",
                        Tags.PARENT_ORDER_ID, "P1",
                        Tags.PARENT_CL_ORD_ID, "P1"
                ),
                FixMessages.er(
                        Tags.CL_ORD_ID, "C1",
                        Tags.ORDER_ID, "B1",
                        Tags.EXEC_ID, "E1",
                        Tags.EXEC_TRANS_TYPE, "0",
                        Tags.EXEC_TYPE, "0",
                        Tags.ORD_STATUS, "0",
                        Tags.LEAVES_QTY, "400",
                        Tags.CUM_QTY, "0",
                        Tags.AVG_PX, "0",
                        Tags.PARENT_ORDER_ID, "P1"
                ),
                FixMessages.d(
                        Tags.CL_ORD_ID, "C2",
                        Tags.ACCOUNT, "PROP",
                        Tags.SYMBOL, "MSFT",
                        Tags.SIDE, "1",
                        Tags.ORDER_QTY, "600",
                        Tags.ORD_TYPE, "2",
                        Tags.PRICE, "420",
                        Tags.PARENT_ORDER_ID, "P1"
                ),
                fillC2(),
                FixMessages.build("G",
                        Tags.CL_ORD_ID, "C1b",
                        Tags.ORIG_CL_ORD_ID, "C1",
                        Tags.ORDER_ID, "B1",
                        Tags.SYMBOL, "MSFT",
                        Tags.SIDE, "1",
                        Tags.ORD_TYPE, "2",
                        Tags.ORDER_QTY, "300",
                        Tags.PRICE, "421"
                ),
                FixMessages.er(
                        Tags.CL_ORD_ID, "C1b",
                        Tags.ORIG_CL_ORD_ID, "C1",
                        Tags.ORDER_ID, "B1",
                        Tags.EXEC_ID, "E3",
                        Tags.EXEC_TRANS_TYPE, "0",
                        Tags.EXEC_TYPE, "5",
                        Tags.ORD_STATUS, "5",
                        Tags.ORDER_QTY, "300",
                        Tags.LEAVES_QTY, "300",
                        Tags.CUM_QTY, "0",
                        Tags.AVG_PX, "0"
                )
        };
    }

    static String fillC2() {
        return FixMessages.er(
                Tags.CL_ORD_ID, "C2",
                Tags.ORDER_ID, "B2",
                Tags.EXEC_ID, "E2",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "2",
                Tags.ORD_STATUS, "2",
                Tags.LAST_SHARES, "600",
                Tags.LAST_PX, "420",
                Tags.LEAVES_QTY, "0",
                Tags.CUM_QTY, "600",
                Tags.AVG_PX, "420",
                Tags.PARENT_ORDER_ID, "P1"
        );
    }

    static Collection<OrderState> normalized(Collection<OrderState> states) {
        return states.stream()
                .map(s -> s.toBuilder().clearLastUpdateEpochMs().build())
                .toList();
    }
}
