# FIX 4.2 OMS Cache — Overview

## Goal

Provide a library that consumes a FIX 4.2 audit / drop-copy stream, maintains the
**latest state of every order** (including parent and child orders), and exposes
a cache API for lookup by the identifiers traders and middle-office systems
actually use.

This is a **library**, not a FIX session engine. It does not speak the FIX
session protocol (logon, heartbeat, resend). Callers already have raw FIX
payloads (`8=FIX.4.2|9=...|35=...|10=...`) and want them turned into a
queryable, incrementally updated order book of record.

## In-scope message types

| MsgType | Name | Role in the cache |
|---------|------|-------------------|
| `D` | New Order - Single | Creates a working order |
| `8` | Execution Report | Authoritative venue / broker state |
| `9` | Order Cancel Reject | Rejects a cancel or replace; reverts pending status |
| `F` | Order Cancel Request | Marks the order pending-cancel |
| `G` | Order Cancel/Replace Request | Marks pending-replace and starts a new `ClOrdID` |
| `H` | Order Status Request | Query only; no state mutation |
| `Q` | Don't Know Trade | Records a DK against an `ExecID` |

Repeating groups that appear on these messages (allocations, contra brokers,
misc fees, trading sessions) are parsed and round-tripped.

## Out of scope (v1)

- FIX session management (initiator/acceptor, sequence numbers as a protocol)
- Replication / clustering (single-process WAL + snapshot recovery is in
  [08-persistent-recovery.md](08-persistent-recovery.md) and is implemented)
- Full-universe FIX 4.2 dictionary (every tag of every message)
- Multi-leg (`AB`/`AC`), list (`E`/`M`/`N`/`L`), or allocation (`J`/`P`) workflows
- Crossing the 4.3+ identifiers (`ClOrdLinkID` 583, `SecondaryClOrdID` 526) as
  first-class tags — they can still ride through as unknown fields

## Module map

```
grok/
  docs/                 analysis and implementation plan
  fix-proto/            .proto schemas + generated Java
  fix-codec/            dictionary, parser, serializer, proto mapper
  oms-cache/            cache API, indexes, state machine
```

Build is Gradle, Java 17. Tests live next to the code they cover
(`src/test/java`) plus an end-to-end drop-copy integration test in `oms-cache`.

## How to build

```bash
./gradlew test
```

## Design principles

1. **Typed state, generic wire.** Cache values are protobuf `OrderState`.
   The wire format is a generic `FixMessage` so unknown tags and groups survive.
2. **Identity is a graph, not a single field.** `ClOrdID`, `OrigClOrdID`,
   `OrderID`, and `ExecID` all index the same order. See
   [02-fix42-identity-and-linking.md](02-fix42-identity-and-linking.md).
3. **Build the store ourselves.** Secondary indexes and parent/child links are
   the product; a generic LRU cache is not. See
   [03-data-model-decisions.md](03-data-model-decisions.md).
4. **History is optional, latest state is not.** Every ingest updates latest
   state. Raw FIX history is a bounded ring buffer behind a flag.
5. **AMPS is a client adapter, not a core dependency.** Latest state can be
   published through `OrderStateHandler`; see
   [09-amps-and-state-handlers.md](09-amps-and-state-handlers.md).
