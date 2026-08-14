package com.fix42.oms.cache;

import com.fix42.oms.api.OrderStateHandler;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.persist.SourceCursor;
import com.fix42.oms.proto.OrderState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateHandlerTest {

    @Test
    void publishesLatestStateAndPreviousKeyOnRekey() {
        RecordingHandler handler = new RecordingHandler();
        InMemoryOrderCache cache = new InMemoryOrderCache(
                CacheConfig.builder().orderStateHandler(handler).build());

        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "MSFT",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "10",
                Tags.ORD_TYPE, "2"
        ));
        cache.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "O9",
                Tags.EXEC_ID, "E1",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0",
                Tags.LEAVES_QTY, "10",
                Tags.AVG_PX, "0"
        ), SourceCursor.of("off=2"));

        assertThat(handler.events).hasSize(2);
        assertThat(handler.events.get(0).orderKey()).isEqualTo("C1");
        assertThat(handler.events.get(0).previousOrderKey()).isNull();
        assertThat(handler.events.get(0).created()).isTrue();

        OrderStateEvent er = handler.events.get(1);
        assertThat(er.orderKey()).isEqualTo("O9");
        assertThat(er.previousOrderKey()).isEqualTo("C1");
        assertThat(er.state().getOrdStatus()).isEqualTo("0");
        assertThat(er.cursor().value()).isEqualTo("off=2");
        assertThat(er.rawFix()).contains("35=");
    }

    @Test
    void handlerFailureFailsIngestByDefault() {
        OrderStateHandler failing = event -> {
            throw new IllegalStateException("amps down");
        };
        InMemoryOrderCache cache = new InMemoryOrderCache(
                CacheConfig.builder().orderStateHandler(failing).build());
        assertThatThrownBy(() -> cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "X",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "1",
                Tags.ORD_TYPE, "1"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amps down");
        assertThat(cache.getByClOrdId("C1")).isPresent();
    }

    @Test
    void handlerFailureCanBeSwallowed() {
        OrderStateHandler failing = event -> {
            throw new IllegalStateException("ignored");
        };
        InMemoryOrderCache cache = new InMemoryOrderCache(
                CacheConfig.builder()
                        .orderStateHandler(failing)
                        .handlerFailsIngest(false)
                        .build());
        cache.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.SYMBOL, "X",
                Tags.SIDE, "1",
                Tags.ORDER_QTY, "1",
                Tags.ORD_TYPE, "1"
        ));
        assertThat(cache.getByClOrdId("C1")).isPresent();
    }

    @Test
    void hydrateRebuildsIndexesAndNotifiesRecovered() {
        RecordingHandler handler = new RecordingHandler();
        InMemoryOrderCache source = InMemoryOrderCache.create();
        source.ingest(FixMessages.d(
                Tags.CL_ORD_ID, "C1",
                Tags.ACCOUNT, "ACC",
                Tags.SYMBOL, "IBM",
                Tags.SIDE, "2",
                Tags.ORDER_QTY, "5",
                Tags.ORD_TYPE, "1",
                Tags.PARENT_ORDER_ID, "P1"
        ));
        source.ingest(FixMessages.er(
                Tags.CL_ORD_ID, "C1",
                Tags.ORDER_ID, "B1",
                Tags.EXEC_ID, "E9",
                Tags.EXEC_TRANS_TYPE, "0",
                Tags.EXEC_TYPE, "0",
                Tags.ORD_STATUS, "0",
                Tags.CUM_QTY, "0",
                Tags.LEAVES_QTY, "5",
                Tags.AVG_PX, "0",
                Tags.PARENT_ORDER_ID, "P1"
        ));

        InMemoryOrderCache sink = new InMemoryOrderCache(
                CacheConfig.builder().orderStateHandler(handler).build());
        sink.hydrate(source.snapshot());

        assertThat(sink.getByClOrdId("C1").orElseThrow().getOrderKey()).isEqualTo("B1");
        assertThat(sink.getByOrderId("B1")).isPresent();
        assertThat(sink.getByExecId("E9")).isPresent();
        assertThat(sink.findByAccount("ACC")).hasSize(1);
        assertThat(sink.getChildren("P1")).hasSize(1);
        assertThat(handler.recovered).hasSize(1);
        assertThat(handler.events).isEmpty();
    }

    private static final class RecordingHandler implements OrderStateHandler {
        final List<OrderStateEvent> events = new ArrayList<>();
        Collection<OrderState> recovered;

        @Override
        public void onOrderUpdated(OrderStateEvent event) {
            events.add(event);
        }

        @Override
        public void onRecovered(Collection<OrderState> snapshot) {
            recovered = snapshot;
        }
    }
}
