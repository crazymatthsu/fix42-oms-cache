# Order state machine and cache API

## Latest-state fields

See `order_state.proto` in [04-protobuf-schema.md](04-protobuf-schema.md).
The fields fall into five groups:

1. **Identity** — `order_key`, current and historical `ClOrdID`s, `OrderID`,
   `SecondaryOrderID`, last `ExecID`.
2. **Instrument / account** — `symbol`, `security_id`, `account`, `side`.
3. **Instruction** — `ord_type`, `time_in_force`, `price`, `stop_px`,
   `order_qty`.
4. **Venue progress** — `ord_status`, `exec_type`, `exec_trans_type`,
   `cum_qty`, `leaves_qty`, `last_qty`, `last_px`, `avg_px`.
5. **Graph / audit** — parent ids, child keys, pending flags, DK flag,
   reject reasons, `transact_time`, `version`.

## Per-message apply rules

Blank incoming fields never wipe a populated field. A New that omitted
`Account` followed by an ER that carries `Account` fills it in; an ER that
omits `Account` leaves the New's value.

### `35=D` New Order - Single

- Create the order if unknown; if known (replay), refresh instruction
  fields.
- Set `cl_ord_id`, instrument, qty, price, TIF, account.
- `ord_status` becomes `A` (Pending New) if not already set by an ER.
- `leaves_qty` = `order_qty` if no ER has arrived yet.
- Attach parent link if tags 20001/20002 (or configured) are present.

### `35=8` Execution Report

Authoritative for venue state, subject to the stale-`TransactTime` rule.

- Bind `OrderID`, `ClOrdID`, `OrigClOrdID`, `ExecID`.
- Apply `OrdStatus`, `ExecType`, `ExecTransType`.
- Apply qty/px: `CumQty`, `LeavesQty`, `LastShares`/`LastQty`, `LastPx`,
  `AvgPx`, `OrderQty` (some venues restated it after a replace).
- `ExecTransType=1` (Cancel / bust): still apply the **restated**
  `CumQty`/`LeavesQty` from the ER. We do not invent a reversal.
- `ExecTransType=2` (Correct): same — trust the restated figures.
- `ExecTransType=3` (Status): apply status/qty; do not treat as a new fill
  for `seen_exec_ids` de-dup of *fills* (the `ExecID` is still recorded).
- Clear `pending_cancel` when status is no longer `6`.
- Clear `pending_replace` when status is no longer `E`.
- Terminal statuses (`2` Filled, `4` Canceled, `8` Rejected, `C` Expired)
  clear both pending flags.

### `35=G` Order Cancel/Replace Request

- Resolve via `OrigClOrdID` / `OrderID` / `ClOrdID`.
- Bind the **new** `ClOrdID` to the existing key.
- Set `pending_replace=true`, `ord_status=E` (Pending Replace) unless the
  order is already terminal.
- Update requested `order_qty` / `price` as *requested* values. They become
  confirmed only when a Replaced ER arrives. To keep the API simple, v1
  writes them onto the same fields and lets the ER overwrite if the venue
  differs.

### `35=F` Order Cancel Request

- Resolve the same way.
- Bind the new `ClOrdID`.
- Set `pending_cancel=true`, `ord_status=6` unless terminal.

### `35=9` Order Cancel Reject

- Resolve via `OrderID` / `ClOrdID` / `OrigClOrdID`.
- Apply `OrdStatus (39)` from the reject (FIX requires the last accepted
  status).
- Record `CxlRejReason`, `CxlRejResponseTo`, `Text`.
- Clear the pending flag indicated by `434`.

### `35=H` Order Status Request

- Resolve if possible.
- Do not mutate state. Return whatever is already cached.
- Still appended to history (it is part of the audit tape).

### `35=Q` Don't Know Trade

- Resolve via `OrderID` or `ExecID`.
- Set `dk_trade=true`, record `DKReason` and `Text`.
- Do **not** unwind `CumQty`. A DK is a claim, not a bust. The bust, if
  any, arrives later as `35=8` `ExecTransType=1`.

## Public API (sketch)

```java
public interface OmsCache {
    ProcessResult ingest(String rawFix);

    ProcessResult processNewOrderSingle(NewOrderSingle msg);
    ProcessResult processExecutionReport(ExecutionReport msg);
    ProcessResult processOrderCancelReject(OrderCancelReject msg);
    ProcessResult processOrderCancelRequest(OrderCancelRequest msg);
    ProcessResult processOrderCancelReplaceRequest(OrderCancelReplaceRequest msg);
    ProcessResult processOrderStatusRequest(OrderStatusRequest msg);
    ProcessResult processDontKnowTrade(DontKnowTrade msg);

    Optional<OrderState> getByClOrdId(String clOrdId);
    Optional<OrderState> getByOrderId(String orderId);
    Optional<OrderState> getByExecId(String execId);
    Optional<OrderState> get(String orderKey);

    List<OrderState> findByAccount(String account);
    List<OrderState> findBySymbol(String symbol);

    List<OrderState> getChildren(String parentOrderId);
    Optional<OrderState> getParent(String childOrderKey);
    ChildRollup rollup(String parentOrderId);

    List<String> getHistory(String orderKey);
    Collection<OrderState> snapshot();
    int size();
}

public final class ProcessResult {
    public final String orderKey;
    public final OrderState state;          // after apply
    public final boolean created;
    public final boolean applied;           // false for stale ER / H-only
}

public final class ChildRollup {
    public final int childCount;
    public final double orderQty;
    public final double cumQty;
    public final double leavesQty;
}
```

Lookups by `Account` and `Symbol` return **current** orders whose latest
state matches. An order that changes symbol (rare, usually a replace onto
a different listing) is removed from the old symbol index.

## Versioning

`version` increments on every write that changes any field (including
index-only binds that also stamp `last_update_epoch_ms`). Callers can use
it as an optimistic token. There is no compare-and-swap ingest in v1.
