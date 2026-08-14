package com.fix42.oms.api;

import com.fix42.oms.cache.ChildRollup;
import com.fix42.oms.cache.ProcessResult;
import com.fix42.oms.persist.SourceCursor;
import com.fix42.oms.proto.DontKnowTrade;
import com.fix42.oms.proto.ExecutionReport;
import com.fix42.oms.proto.FixMessage;
import com.fix42.oms.proto.NewOrderSingle;
import com.fix42.oms.proto.OrderCancelReject;
import com.fix42.oms.proto.OrderCancelReplaceRequest;
import com.fix42.oms.proto.OrderCancelRequest;
import com.fix42.oms.proto.OrderState;
import com.fix42.oms.proto.OrderStatusRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OmsCache extends AutoCloseable {
    ProcessResult ingest(String rawFix);

    ProcessResult ingest(String rawFix, SourceCursor cursor);

    ProcessResult ingest(FixMessage message);

    ProcessResult ingest(FixMessage message, SourceCursor cursor);

    ProcessResult processNewOrderSingle(NewOrderSingle msg);

    ProcessResult processExecutionReport(ExecutionReport msg);

    ProcessResult processOrderCancelReject(OrderCancelReject msg);

    ProcessResult processOrderCancelRequest(OrderCancelRequest msg);

    ProcessResult processOrderCancelReplaceRequest(OrderCancelReplaceRequest msg);

    ProcessResult processOrderStatusRequest(OrderStatusRequest msg);

    ProcessResult processDontKnowTrade(DontKnowTrade msg);

    Optional<OrderState> get(String orderKey);

    Optional<OrderState> getByClOrdId(String clOrdId);

    Optional<OrderState> getByOrderId(String orderId);

    Optional<OrderState> getByExecId(String execId);

    List<OrderState> findByAccount(String account);

    List<OrderState> findBySymbol(String symbol);

    List<OrderState> getChildren(String parentOrderId);

    Optional<OrderState> getParent(String childOrderKey);

    ChildRollup rollup(String parentOrderId);

    List<String> getHistory(String orderKey);

    Collection<OrderState> snapshot();

    int size();

    /**
     * Install latest states from an external book (AMPS SOW query, etc.)
     * and rebuild indexes. Does not write the file WAL.
     */
    void hydrate(Iterable<OrderState> states);

    @Override
    default void close() {}
}
