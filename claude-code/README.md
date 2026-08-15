# fix42-oms-cache

An in-memory **cache API for FIX 4.2 order state**. Feed it a FIX 4.2 audit-trail / drop-copy
stream and it maintains the latest state of every order — parent and child — joined across the
whole order lifecycle, plus a **FIX ⇄ protobuf** codec.

- Processes `35=D` (NewOrderSingle), `35=8` (ExecutionReport), `35=9` (OrderCancelReject),
  `35=F` (OrderCancelRequest), `35=G` (OrderCancelReplaceRequest), `35=H` (OrderStatusRequest),
  `35=Q` (DontKnowTrade).
- Links messages into one order **chain** via ClOrdID(11) / OrigClOrdID(41) / OrderID(37) /
  ExecID(17), tracking cancel/replace lifecycles correctly.
- Search the latest state by **OrderID, ClOrdID, ExecID, Account, or Symbol**.
- Parent ↔ child order aggregation.
- Optional **crash recovery** (`:oms-persist`): write-ahead journal + snapshots restore the
  exact pre-crash state on restart.
- **State-change listener SPI** (`OrderStateListener`): every latest-state change (including
  parent roll-ups) is delivered in processing order — the integration point for publishing
  to AMPS SOW topics, Kafka, or any downstream store.
- Optional **Parquet archive** (`:oms-parquet`): batched DuckDB writers put the raw FIX
  messages and every order-state change on disk as partitioned Parquet
  (`YYYY/MM/DD/account/symbol` by default), for intraday queries from Deephaven or DuckDB,
  end-of-day small-file compaction, and an overnight move to S3.
- Zero runtime dependencies beyond `protobuf-java` (the Parquet module adds DuckDB).

See **[docs/00-overview.md](docs/00-overview.md)** for the authoritative design and the answers
to every question in [TODO.md](TODO.md).

## Modules

| Module | Responsibility |
|---|---|
| `:fix-proto` | `.proto` schema + generated protobuf classes |
| `:fix-codec` | FIX parser/serializer, embedded FIX 4.2 dictionary, enum⇄code, typed mappers |
| `:oms-cache` | order state machine, in-memory cache + indexes, parent linkage, `OmsCache` API |
| `:oms-persist` | crash recovery: write-ahead journal + atomic snapshots (`PersistentOrderCache`) |
| `:oms-parquet` | DuckDB Parquet archive: partitioned datasets, EOD compaction, S3 move (`ParquetArchive`) |
| `:examples` | runnable end-to-end demo |

## Quick start

```bash
./gradlew build           # compile + run all 216 tests
./gradlew :examples:run    # run the end-to-end demo
./gradlew :examples:run -PmainClass=com.fix42.oms.examples.ParquetArchiveDemo   # Parquet archive demo
```

## Usage

```java
import com.fix42.oms.api.OmsCache;
import com.fix42.oms.proto.OrderState;

OmsCache cache = OmsCache.inMemory();

// Feed raw FIX strings ('|' or SOH-delimited, auto-detected)...
cache.process("8=FIX.4.2|35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.50|");
cache.process("8=FIX.4.2|35=8|11=ORD1|37=EX9001|17=E1|20=0|150=0|39=0|151=1000|14=0|");
cache.process("8=FIX.4.2|35=8|11=ORD1|37=EX9001|17=E2|20=0|150=1|39=1|32=400|31=185.50|151=600|14=400|6=185.50|");

// ...then query the latest state by any identifier.
OrderState s = cache.getByClOrdId("ORD1").orElseThrow();
System.out.println(s.getOrdStatus()      // ORD_STATUS_PARTIALLY_FILLED
        + " cum=" + s.getCumQty()          // 400
        + " leaves=" + s.getLeavesQty());  // 600

cache.findByAccount("ACC");   // all orders for an account
cache.findBySymbol("IBM");    // all orders for a symbol
```

You can also feed generic `FixMessage`s or typed protos (`NewOrderSingle`, `ExecutionReport`, …);
`FixParser`/`FixSerializer` and `FixMessageMapper` in `:fix-codec` handle FIX ⇄ protobuf both ways.

### Crash recovery

```java
import com.fix42.oms.persist.PersistentOrderCache;

try (PersistentOrderCache cache = PersistentOrderCache.open(Path.of("/var/oms/journal"))) {
    cache.asOmsCache().process("8=FIX.4.2|35=D|11=ORD1|…"); // journaled, then applied
    cache.snapshot();                                        // periodic checkpoint
}
// after a crash or restart: open() replays snapshot + journal tail —
// the recovered state equals the pre-crash state exactly.
```

### Parquet archive

```java
import com.fix42.oms.parquet.*;

ParquetArchive archive = ParquetArchive.open(ParquetArchiveConfig.defaults(Path.of("/data/oms")));

// wrap() captures every raw message; orderStateListener() captures every state change
OmsCache cache = new OmsCache(archive.wrap(
        new InMemoryOrderCache(DefaultParentLinkResolver.create(),
                               CacheConfig.defaults(),
                               archive.orderStateListener())));

cache.process("8=FIX.4.2|35=D|11=ORD1|1=ACC|55=IBM|54=1|38=1000|40=2|44=185.50|");
archive.flush();
// /data/oms/fix_messages/2026/08/14/ACC/IBM/fix_messages-…-0.parquet
// /data/oms/order_state/2026/08/14/ACC/IBM/order_state-…-1.parquet
```

Query it intraday with anything that reads Parquet, then compact and move the finished day
to S3:

```java
try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
        new DuckDbS3ObjectStore(S3Config.of("oms-archive", "prod", "us-east-1"), config.duckDb()))) {
    eod.run(LocalDate.now(config.partitionZone()).minusDays(1));
}
```

## Requirements

Java 23, Gradle 9.2.1 (a wrapper is included: use `./gradlew`).

## Documentation

- [00 — Overview (authoritative)](docs/00-overview.md)
- [01 — FIX 4.2 Domain & Message Linking](docs/01-fix42-domain-and-linking.md)
- [02 — Architecture & Gradle Build](docs/02-architecture-and-build.md)
- [03 — Protobuf Schema](docs/03-protobuf-schema.md)
- [04 — Parser, Serializer & Dictionary](docs/04-fix-parser-and-dictionary.md)
- [05 — Cache Design, Indexes & Concurrency](docs/05-cache-and-indexes.md)
- [06 — Public API & Order-State Machine](docs/06-api-and-state-machine.md)
- [07 — Persistence & Crash Recovery](docs/07-persistence-and-recovery.md)
- [08 — AMPS Integration & State Distribution](docs/08-amps-integration.md)
- [09 — DuckDB Parquet Archive (intraday & historical query)](docs/09-parquet-archive.md)
