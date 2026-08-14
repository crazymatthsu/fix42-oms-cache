# Data-model decisions

The TODO list asked four product questions. This document records the answers
and the alternatives that were rejected.

## 1. Cache value type: protobuf, not `Map`, not JSON

**Decision:** the latest order state is a generated protobuf `OrderState`.
Ingested FIX is first a generic `FixMessage`, then a typed message
(`NewOrderSingle`, `ExecutionReport`, …), then applied onto `OrderState`.

| Option | Why not (or why yes) |
|--------|----------------------|
| `Map<String, Object>` | No schema, no repeating groups, no generated accessors, every caller re-implements tag knowledge. Cheap to write, expensive to live with. |
| JSON document | Same schema problem. Incremental update means parse → mutate → serialize on every message. Merging nested groups is ad hoc. |
| **Protobuf `OrderState`** | Schema is the contract. Binary-efficient, language-portable, unknown fields reserved for evolution. Matches the explicit "FIX 4.2 → proto → FIX 4.2" requirement. |

JSON can still be *produced* (`JsonFormat.printer()`) for logs or HTTP
facades. It is not the store.

Typed FIX messages (`ExecutionReport`, …) are also protobuf. They exist so
the cache API can be `processExecutionReport(ExecutionReport)` rather than
`process(Map)`. Extra / unknown tags live on a repeated `FixField extra`
so we do not drop vendor fields.

## 2. Third-party cache vs build from scratch

**Decision:** build the store. Use `ConcurrentHashMap` plus a
`ReentrantReadWriteLock` for index consistency.

A cache library (Caffeine, Guava Cache, cache2k) solves **eviction and
expiry of a single key**. This product needs:

- five secondary indexes (`ClOrdID`, `OrderID`, `ExecID`, `Account`, `Symbol`)
- a parent → children multimap that must stay consistent with those indexes
- compare-and-apply rules (`TransactTime`, duplicate `ExecID`)
- optional per-order message history

None of those are what Caffeine is for. Wrapping Caffeine and then
maintaining side indexes by hand is the same amount of code as owning the
maps, with a harder consistency story.

Eviction / TTL, if needed later, can sit *in front of* the primary
`order_key` map without changing the API. It is not a v1 requirement.

Restart recovery is a separate problem from eviction: the book must
come back *exactly*, not approximately. That design is in
[08-persistent-recovery.md](08-persistent-recovery.md). Short version:
keep the heap maps as the hot cache; persist a WAL of raw FIX plus a
periodic `OrderState` snapshot; rebuild indexes on load.

## 3. Store original FIX and "join" the tape?

**Decision:** yes, optionally.

- Latest `OrderState` is always maintained. That is the API the TODO
  describes ("latest state of each order").
- Each order also has a bounded deque of raw FIX strings
  (`CacheConfig.historyLimit`, default 32, `0` disables).
- `getHistory(orderKey)` returns that tape in arrival order.

We do **not** reconstruct latest state by replaying the tape on every read.
Replay is a debugging / audit feature, not the source of truth. Replaying
would make `TransactTime` ordering and bust/correct logic run on the hot
path.

"Join all FIX messages for a given orderID" is therefore:

```
getByOrderId(id).map(OrderState::getOrderKey).map(cache::getHistory)
```

## 4. FIX 4.2 ↔ protobuf, including repeating groups

**Decision:** dictionary-driven codec.

- `FixDictionary` knows field types and which tags are `NumInGroup`.
- `FixParser` emits a generic `FixMessage` (header, body fields, nested
  groups, trailer). Unknown tags are kept as string fields.
- `FixSerializer` writes a legal FIX 4.2 string (recomputes `9` and `10`).
- `FixMessageMapper` projects a `FixMessage` onto the typed proto for the
  seven in-scope `MsgType`s.

Repeating groups use the standard FIX delimiter rule: the first field of
the group definition starts a new instance. Nested groups recurse.

Only the tags and groups that appear on `D/8/9/F/G/H/Q` are in the
dictionary. Everything else still **parses** (as an unknown field) and
**serializes**.

## Consequences for the public API

```
ingest(String rawFix)                  // parse → map → dispatch
processNewOrderSingle(...)
processExecutionReport(...)
processOrderCancelReject(...)
processOrderCancelRequest(...)
processOrderCancelReplaceRequest(...)
processOrderStatusRequest(...)         // no-op on state
processDontKnowTrade(...)

getByClOrdId / getByOrderId / getByExecId
findByAccount / findBySymbol
getChildren / getParent / rollup
getHistory / snapshot
```

The process* methods exist because the TODO asked for them. `ingest` is
the drop-copy entry point and is what integration tests drive.
