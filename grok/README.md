# fix42-oms-cache

In-memory cache for FIX 4.2 audit / drop-copy streams. Parses `D`, `8`, `9`,
`F`, `G`, `H`, and `Q`, keeps the latest state of every order (including
parent/child graphs), and looks them up by `ClOrdID`, `OrderID`, `ExecID`,
account, or symbol.

Design notes live in [`docs/`](docs/).

```bash
./gradlew test
```

```java
OmsCache cache = InMemoryOrderCache.create();
cache.ingest(rawFix);
OrderState state = cache.getByClOrdId("C1").orElseThrow();
```

To survive a process restart, point the cache at a data directory. Restart
reloads the last snapshot, rebuilds indexes, and replays the WAL tail.

```java
CacheConfig config = CacheConfig.builder()
    .persistence(PersistenceConfig.builder()
        .dataDir(Path.of("/var/lib/oms-cache"))
        .build())
    .build();
Recovery recovery = InMemoryOrderCache.recover(config);
try (OmsCache cache = recovery.cache()) {
    recovery.sourceCursor().ifPresent(offset -> { /* resume the feed */ });
    cache.ingest(rawFix, SourceCursor.of("file:drop.copy:offset=123"));
}
```

See [`docs/08-persistent-recovery.md`](docs/08-persistent-recovery.md).

To fan out latest state (for example to an AMPS SOW), implement
`OrderStateHandler`. The library does not depend on the AMPS client.

```java
CacheConfig config = CacheConfig.builder()
    .orderStateHandler(event -> {
        if (event.previousOrderKey() != null) {
            // sowDelete old key, then publish event.state()
        }
        amps.publish("oms-order-state", toJson(event.state()));
    })
    .build();
// On start from AMPS: cache.hydrate(statesFromSowQuery);
```

See [`docs/09-amps-and-state-handlers.md`](docs/09-amps-and-state-handlers.md).
