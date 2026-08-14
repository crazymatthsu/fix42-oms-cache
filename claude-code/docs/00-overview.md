# fix42-oms-cache — Design Overview (authoritative)

This is the **source-of-truth** design document. It answers the questions in
[`TODO.md`](../TODO.md), describes the architecture as actually implemented, and records the
correctness decisions that were reconciled after an adversarial design review. The detailed
draft documents [`01`](01-fix42-domain-and-linking.md)–[`06`](06-api-and-state-machine.md)
are the design exploration; where they differ from this overview, **this overview wins**
(see [§10 Corrections](#10-corrections-applied-vs-the-exploratory-drafts)).

---

## 1. What this is

A Java library that consumes **FIX 4.2** audit-trail / drop-copy message streams and maintains
an in-memory cache of the **latest state of every order** — parent and child — plus the full
joined message history per order. It also provides a **FIX ⇄ protobuf codec**: parse a FIX
string into protobuf objects, and serialize protobuf back to a FIX string.

In-scope message types: `35=D` NewOrderSingle, `35=8` ExecutionReport, `35=9` OrderCancelReject,
`35=F` OrderCancelRequest, `35=G` OrderCancelReplaceRequest, `35=H` OrderStatusRequest,
`35=Q` DontKnowTrade.

## 2. Module map (Gradle multi-module)

```
:fix-proto   .proto schema + generated Java (protobuf plugin applied here only)
     ▲
     │ api
:fix-codec   FIX parser/serializer, embedded 4.2 dictionary, enum⇄code, typed mappers
     ▲
     │ api
:oms-cache   order state machine, in-memory cache + indexes, parent linkage, OmsCache API
     ▲                ▲
     │ implementation │ api
:examples             :oms-persist   write-ahead journal + snapshots: crash recovery
```

Root package `com.fix42.oms`. Java 23, Gradle 9.2.1, protobuf-java 4.35.1, JUnit 5. The only
runtime dependency is `protobuf-java`.

### Package ownership

| Package | Module | Contents |
|---|---|---|
| `com.fix42.oms.proto` | fix-proto | generated: `FixMessage`, `FixField`, `OrderState`, enums, 7 typed messages |
| `com.fix42.oms.fix` | fix-codec | `FixParser`, `FixSerializer`, `FixSupport`, `Tags`, `FixConstants` |
| `com.fix42.oms.dict` | fix-codec | `FixDictionary` (`.fix42()`), `FieldDef`/`GroupDef`/`MessageDef`, `FixCodes` |
| `com.fix42.oms.mapper` | fix-codec | `FixMessageMapper`, `Groups` |
| `com.fix42.oms.model` | oms-cache | `OrderStateUpdater`, `ParentLinkResolver`, `DefaultParentLinkResolver` |
| `com.fix42.oms.cache` | oms-cache | `OrderCache`, `InMemoryOrderCache`, `CacheConfig`, `OrderStateListener`/`OrderStateChange` (state-change SPI) |
| `com.fix42.oms.api` | oms-cache | `OmsCache` (facade) |
| `com.fix42.oms.persist` | oms-persist | `PersistentOrderCache`, `PersistenceConfig` (journal + snapshot crash recovery) |

## 3. Build, test, run

```bash
./gradlew build           # compile + run all tests (55 tests)
./gradlew :examples:run    # run the end-to-end demo
```

## 4. Data model — three layers

1. **Generic, lossless wire model** — `FixMessage { repeated FixField(tag,value) }`. Preserves
   field order exactly (header, body, trailer), so `parse → serializeVerbatim` is byte-for-byte
   faithful and repeating groups are retained positionally. This is the round-trip vehicle.
2. **Typed message views** — `NewOrderSingle`, `ExecutionReport`, … (7 types) plus shared
   `FixHeader`/`FixTrailer` and group sub-messages (`Alloc`, `ContraBroker`). Produced by
   dictionary-driven mappers (`FixMessageMapper`). A convenience/round-trip layer that normalizes
   numeric formatting; the generic model remains the lossless carrier.
3. **`OrderState`** — the derived "latest state" folded from every message in an order's chain.
   This is the value stored in the cache; it also carries the full ordered `message_history`.

## 5. FIX identifier & linking model

| Tag | Name | Role |
|---|---|---|
| 11 | ClOrdID | new per D/F/G request; the "current" client id |
| 41 | OrigClOrdID | on F/G, points at the ClOrdID being amended (the chain link) |
| 37 | OrderID | sell-side id; stable across the chain; first seen on the first ExecutionReport |
| 17 | ExecID | unique per ExecutionReport; `19 ExecRefID` + `20 ExecTransType` handle bust/correct |
| 39 / 150 | OrdStatus / ExecType | drive lifecycle state |

**Chain keying (as implemented).** Each order chain is stored under an opaque internal
`chainId`. Every identifier a chain touches — OrderID(37), each ClOrdID(11)/OrigClOrdID(41),
ExecID(17) — is indexed to that `chainId`. Because the primary key is the always-present internal
id (not OrderID, which only appears on the first ExecutionReport), a brand-new order from a
NewOrderSingle is **immediately searchable** by ClOrdID, Account and Symbol, with no separate
"pending" store and no null-OrderID index entries.

Indexes (all maintained atomically under the cache write lock):
`orderId→chain`, `clOrdId→chain` (covers OrigClOrdID too), `execId→chain`,
`account→{chains}`, `symbol→{chains}`, `parentId→{childChains}`.

## 6. Order state machine (`OrderStateUpdater`)

`apply(prev, msg) → next` is pure. Rules:

| Msg | Effect |
|---|---|
| `D` | create chain; set terms; `OrdStatus = PENDING_NEW` (cache assumption until first 8) |
| `8` | `OrdStatus` from **tag 39** (never forced by ExecType); `cum/leaves/avg` = venue's **absolute** snapshot; append ExecID (dedup); on bust/correct (ExecTransType 1/2) record the busted ExecID for audit, no re-derivation; clear the matching in-flight transition on a Canceled/Replaced confirm |
| `F` | snapshot current status under the request's ClOrdID; `OrdStatus = PENDING_CANCEL` |
| `G` | snapshot current status under the request's ClOrdID; `OrdStatus = PENDING_REPLACE`; **new terms are not applied until the confirming 8** |
| `9` | revert to the prior status tracked for the reject's ClOrdID (per-request), so multiple in-flight F/G don't clobber each other; set `cxl_rej_reason`/`text` |
| `H` | no state mutation (recorded in history) |
| `Q` | no fill-state change; capture reason text for reconciliation |

Order **terms** (qty/price/type/TIF) change only on `D` and `8` — a replace's new terms take
effect on its confirming ExecutionReport. Duplicate ExecIDs are ignored for economic fields.

## 7. Concurrency

All mutations (chain resolution, state folding, every index write, parent roll-up) run under a
single `ReentrantLock`, so each processed message is applied **atomically** and indexes never
diverge from the primary map. Reads are lock-free over `ConcurrentHashMap` and return immutable
`OrderState` snapshots. Write throughput is serialized — appropriate for an audit/drop-copy feed;
per-chain lock striping is a documented future optimization.

## 8. Parent / child

FIX 4.2 has no standard parent-order tag, so linkage is a configurable convention via
`ParentLinkResolver`. The default reads a configurable tag (default **526 SecondaryClOrdID** —
note: 526 is formally a FIX 4.3 tag, used here as a 4.2 convention). Children link to the parent
by that id in any arrival order; the parent's `OrderState` aggregates child fills
(`cum_qty` = Σ child cum, weighted `avg_px`, guarded against divide-by-zero). Query with
`getChildren(parentId)` / `getParent(childId)`.

## 9. Answers to the TODO questions

1. **protobuf vs `Map<String,Object>` vs JSON?** → **Typed protobuf.** Type safety, schema
   evolution, compact storage, generated builders for direct field updates. JSON makes partial
   updates awkward and stringly-typed maps lose type information. We keep a *generic* `FixMessage`
   proto for lossless wire round-trips and a typed `OrderState` for the cache value.
2. **3rd-party cache vs build from scratch?** → **Build a thin in-memory cache** on
   `ConcurrentHashMap` (`InMemoryOrderCache`) — zero-dependency, deterministic, fast, and behind
   the `OrderCache` interface so it is swappable. Caffeine is an optional add-on for TTL eviction
   of terminal orders (not required).
3. **Store all original FIX messages + join per orderId?** → **Yes.** `OrderState.message_history`
   keeps every original message (parsed; the raw string is reconstructable via the serializer),
   in arrival order, optionally capped by `CacheConfig.historyCap`.
4. **FIX ⇄ protobuf incl. repeating groups?** → Generic `FixMessage` round-trip (lossless,
   length-aware for raw/encoded data) + dictionary-driven typed mappers that project groups
   (NoContraBrokers, NoAllocs) into typed sub-messages.
5. **Latest-state fields + how each message updates them?** → `OrderState` (§4) updated by the
   state machine (§6).
6. **Search by Account / Symbol / ClOrdID / OrderID / ExecID?** → secondary indexes (§5), all
   returning immutable snapshots; account/symbol/ClOrdID work even for pre-ack orders.

## 10. Corrections applied vs. the exploratory drafts

An adversarial FIX-domain review of drafts 01–06 found real defects. All are fixed in the code
and reflected above:

- **Terminal-REPLACED bug (critical):** ExecutionReport `OrdStatus` is driven by tag 39, never
  forced to `REPLACED` from ExecType — a still-working replace-confirm keeps its live status.
  (`OrderStateUpdater.applyExecutionReport`)
- **Multiple in-flight cancel/replace:** prior status is tracked **per request ClOrdID**
  (`OrderState.pending_prior_status`), and `35=9` reverts by its ClOrdID — draft's single
  `prior_status` snapshot was replaced.
- **Concurrency:** one write lock for all mutations, replacing the drafts' mixed
  `compute()`/monitor lock domains; ExecID dedup happens inside that lock (no TOCTOU).
- **Bust/correct:** treat CumQty/AvgPx as the venue's restated absolute snapshot; record the
  busted ExecID for audit; do **not** re-derive (drafts double-corrected).
- **Pending-order search:** chain keying makes ClOrdID/Account/Symbol lookups find D-only orders;
  no null-OrderID index entries.
- **FIX facts:** OrdStatus `D` = AcceptedForBidding is valid 4.2 (distinct from ExecType `D` =
  Restated); EncodedText(354/355) and the Encoded* family **are** FIX 4.2 length/data pairs
  (only XmlData 212/213 is 4.4+); tag 526 documented as a 4.3 tag used by convention.
- **Parent roll-up:** guarded against divide-by-zero and performed in the single write-lock domain.

## 11. Persistence & crash recovery

The optional `:oms-persist` module makes the cache crash-recoverable with **zero new
dependencies**: every processed message is written to a CRC-framed write-ahead journal
(with its sequence and arrival time), and periodic atomic snapshots checkpoint the full
cache (states + all six indexes + the chain counter + a config fingerprint). Recovery
loads the newest valid snapshot, replays the journal tail with strict sequence-contiguity
checks, heals torn tails, refuses mid-journal corruption, and reproduces the pre-crash
state exactly (`OrderState.equals()`, timestamps included — replay re-injects each
record's journaled arrival time through the same injected clock the live path used).
Full analysis, failure-mode table, and operational guidance:
[07 — Persistence & Crash Recovery](07-persistence-and-recovery.md).

## 12. Document index

- [01 — FIX 4.2 Domain & Message Linking](01-fix42-domain-and-linking.md)
- [02 — Architecture & Gradle Multi-Module Build](02-architecture-and-build.md)
- [03 — Protobuf Schema Design](03-protobuf-schema.md)
- [04 — FIX Parser, Serializer & Embedded 4.2 Dictionary](04-fix-parser-and-dictionary.md)
- [05 — Cache Design, Indexes & Concurrency](05-cache-and-indexes.md)
- [06 — Public API & Order-State Machine](06-api-and-state-machine.md)
- [07 — Persistence & Crash Recovery](07-persistence-and-recovery.md) *(authoritative, as-built)*
- [08 — AMPS Integration & State Distribution](08-amps-integration.md) *(listener SPI as-built; AMPS options analyzed)*

## 13. Test coverage

95 tests, all passing:

- **fix-codec (34):** parser (SOH/pipe/custom delimiter, first-`=` splitting, length-aware
  raw-data with embedded SOH, strict checksum validation, malformed input), serializer (verbatim
  lossless round-trip, canonical BodyLength/CheckSum recomputation), enum⇄code (incl. the `D`
  ambiguity), dictionary (groups, header/trailer), typed mapper round-trips (incl. groups).
- **oms-cache (30):** state machine (all 7 message types, replace-confirm status, per-request
  reject revert with multiple in-flight, ExecID dedup, bust snapshot, terms-only-on-D/8, history
  cap), cache (chain resolution across replace, pending-order search, account/symbol grouping,
  parent/child roll-up with children-first ordering, cancel/reject, snapshot export/restore),
  listener SPI (create/update payloads, parent-roll-up flagging, error-channel isolation,
  enforced reentrancy rejection, no-op roll-up suppression, ordering), integration (full
  lifecycle from raw FIX, DK, typed-proto overloads, 200-order × 8-thread concurrency stress).
- **oms-persist (31):** journal round-trip/rotation/torn-tail/CRC/oversized/unknown-magic,
  snapshot store atomicity + corrupt-fallback, crash-recovery integration (exact-state recovery
  journal-only and snapshot+tail, double-crash torn-tail healing, mid-journal corruption and
  sequence-gap refusal, config-fingerprint enforcement, restart continuity without duplicate
  applies, directory locking, throughput smoke), and listener wiring (silent replay, one-per-chain
  recovery announcements, listener failure never fail-stops, recovery succeeds with the
  downstream publisher unreachable).
