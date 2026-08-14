# Implementation plan

Work proceeds in the same order as the Gradle module DAG. Each step is
independently testable.

## PR / step 1 — Build skeleton

- Root Gradle project, wrapper, `libs.versions.toml`, `.gitignore`
- Subprojects `fix-proto`, `fix-codec`, `oms-cache`
- Empty JUnit 5 wiring
- **Depends on:** nothing

## PR / step 2 — Protobuf schemas + generated-code tests

- `enums.proto`, `fix.proto`, `messages.proto`, `order_state.proto`
- Protobuf Gradle plugin
- Unit tests that construct, serialize, and parse each generated type
- **Depends on:** step 1

## PR / step 3 — Dictionary, parser, serializer

- `Tags`, `FixDictionary` (programmatic FIX 4.2 subset)
- `FixParser` with repeating groups and `|`/SOH input
- `FixSerializer` with BodyLength + CheckSum
- Tests: happy path, groups (incl. nested), bad checksum, missing 35,
  round-trip identity for a hand-built `FixMessage`
- **Depends on:** step 2

## PR / step 4 — Typed mapper

- `FixMessageMapper` both directions for D/8/9/F/G/H/Q
- Tests: each message type, groups (`NoAllocs`, `NoContraBrokers`), extra
  tags survive
- **Depends on:** step 3

## PR / step 5 — Cache, linker, state machine

- `OmsCache` / `InMemoryOrderCache`
- `OrderLinker`, `OrderStateUpdater`, `ParentLinkResolver`
- Unit tests: ClOrdID chain, OrderID preferred, stale ER, cancel reject,
  parent/child, indexes, history ring
- **Depends on:** step 4

## PR / step 6 — Integration tests

- A multi-message drop-copy script (parent + two children + replace +
  reject + DK + fill)
- Assert latest state, every index, children, rollup, history, FIX
  round-trip of the last ER
- **Depends on:** step 5

## Verification

```
./gradlew test
```

All modules must pass. Generated protobuf sources are compiled as part of
`:fix-proto:compileJava`; their tests run under `:fix-proto:test`.

## Non-goals of this first implementation

- Persistence (see [08-persistent-recovery.md](08-persistent-recovery.md)
  for the follow-on plan)
- Netty / QuickFIX session adapter
- Metrics
- Full FIX 4.2 data dictionary XML import

## Follow-on — persistence (implemented)

1. Execution Report apply is idempotent on a seen `ExecID` (`ExecTransType=0`)
2. `IndexRebuilder` rebuilds every index from `OrderState`
3. `FileStateStore` — length-prefixed WAL + atomic snapshot
4. `InMemoryOrderCache.recover(config)` and WAL-first ingest
5. Crash-prefix tests copy the data dir after each forced write
6. Optional `ingest(raw, SourceCursor)` persists the feed resume token
