# AMPS (60East) and latest-state handlers

Two related questions:

1. Can AMPS replace the file WAL/snapshot as the **persistent cache** (SOW + transaction log)?
2. Or should the library emit **latest order state** and let the client publish to AMPS?

**Short answer:** both are valid. Do **not** take an AMPS Java client dependency in `oms-cache`. Use AMPS as an *adapter* (a `StateStore` or, simpler, an `OrderStateHandler`). The in-memory book stays the hot path.

## How AMPS maps onto this cache

| This library | AMPS equivalent |
|--------------|-----------------|
| Latest `OrderState` per `order_key` | **SOW topic** — last message per SOW key |
| Secondary indexes (`ClOrdID`, account, …) | SOW **content filters** *or* rebuilt in heap after a SOW query |
| File WAL of raw FIX | AMPS **transaction log** + bookmark subscribe |
| File snapshot | Atomic **SOW query** (`sow` command) |
| `SourceCursor` | AMPS **bookmark** (plus your drop-copy file offset, which is a different feed) |

AMPS SOW is a last-value store: publish a message, AMPS keeps one record per configured key and can query it. The transaction log is the durable tape; a bookmark subscription replays from a point. That is the same snapshot + tail shape as `FileStateStore`.

## Option A — AMPS *is* the persistent cache

### Topic sketch

```
Topic: oms-order-state          MessageType: json   (or binary protobuf)
  SOWKey: /order_key            # or AMPS-generated from that field
  Durability: journal / txlog

Topic: oms-order-fix            # optional, for history / audit
  # not a SOW, or a SOW keyed by order_key + seq if you want the ring
```

Publish after each apply:

```json
{
  "order_key": "B1",
  "cl_ord_id": "C1b",
  "cl_ord_id_history": ["C1", "C1b"],
  "order_id": "B1",
  "account": "PROP",
  "symbol": "MSFT",
  "ord_status": "5",
  "cum_qty": 0,
  "leaves_qty": 300,
  "parent_order_id": "P1",
  "version": 7
}
```

JSON is what AMPS content filters (`/account = 'PROP'`, `/parent_order_id = 'P1'`) understand. Protobuf binary in SOW is possible but filters need a message type AMPS can parse.

### Recovery

```
1. sow query  topic=oms-order-state
2. cache.hydrate(orderStates)     # rebuild heap + indexes
3. optional: bookmark subscribe on oms-order-fix from last bookmark
   to catch publishes that landed after the SOW query
```

Or skip the SOW query and replay the **entire** txlog into `ingest` — same as “replay-only,” slower at scale.

### What is awkward

- **`order_key` rekey.** The first ER promotes `C1` → `O9`. A SOW keyed by `order_key` must **delete** the old key and **upsert** the new one. The handler event now carries `previousOrderKey` for that.
- **Secondary IDs.** AMPS will not give you `getByClOrdId` unless you filter (`/cl_ord_id = 'C1' OR /cl_ord_id_history = 'C1'`) or keep the heap indexes. Heap indexes after hydrate are faster and already implemented.
- **History ring.** Not a SOW natural. Either drop it, keep the file WAL, or a second topic.
- **WAL-first / ack.** `Client.publish` is async unless you use a publish store / flush. If AMPS is durability, wait for the server ack before returning from `ingest`.
- **One writer.** Two processes publishing the same SOW key last-write-wins. Same rule as one `dataDir`.
- **Dependency.** The official Java client is not on Maven Central in a form we want as a *library* transitive. Version/license/server coupling belongs in *your* app, not in `oms-cache`.

### When Option A is the right product

You already run AMPS, other services should **subscribe** to live order state, and you do not want a local disk book. Implement `StateStore` against AMPS in the application (or a future optional `:oms-amps` module we do not ship today).

## Option B — handler: library emits state, client publishes (recommended)

The cache stays the system of record for apply/link/index. After each ingest it calls:

```java
void onOrderUpdated(OrderStateEvent event);
```

The client implements that and publishes to AMPS (or Kafka, or a blotter). Recovery of *this* process can still use `FileStateStore`. AMPS is a **downstream SOW** for other consumers.

If the process dies and you **only** have AMPS:

```java
// on start, after sow query
cache.hydrate(statesFromAmps);
```

No AMPS types in this repo. A sketched client:

```java
public final class AmpsOrderPublisher implements OrderStateHandler {
    private final Client amps;
    private final String topic = "oms-order-state";

    @Override
    public void onOrderUpdated(OrderStateEvent event) {
        if (event.state() == null) {
            return;
        }
        if (event.previousOrderKey() != null) {
            amps.sowDelete(topic, "/order_key = '" + event.previousOrderKey() + "'");
        }
        amps.publish(topic, JsonFormat.printer().print(event.state()));
    }
}

CacheConfig config = CacheConfig.builder()
    .orderStateHandler(new AmpsOrderPublisher(client))
    .handlerFailsIngest(true)   // drop-copy is not acked if AMPS publish throws
    .build();
```

`handlerFailsIngest=true` means a failed publish fails `ingest`. Memory already has the order; the caller should not ack the FIX feed so the message is retried (idempotent on `ExecID`).

## Decision

| Question | Answer |
|----------|--------|
| Use AMPS SOW + txlog as *the* store? | Yes, as an **adapter**, not inside `oms-cache`. |
| Add a latest-state handler? | **Yes.** `OrderStateHandler` + `hydrate`. |
| Hard dependency on AMPS? | **No.** |
| File WAL still useful? | Yes, if this process must restart without AMPS or without a rewindable drop copy. File store and handler compose. |
| Query AMPS instead of heap? | Not for the hot path. Hydrate once, keep `getByClOrdId` in memory. |

## Implementation (this change)

- `OrderStateHandler` / `OrderStateEvent`
- `CacheConfig.orderStateHandler` / `handlerFailsIngest`
- `ProcessResult.previousOrderKey` on rekey
- `InMemoryOrderCache.hydrate(...)` for AMPS SOW (or any external book)
- No AMPS client, no new Gradle module
