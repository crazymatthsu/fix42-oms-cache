# Architecture and build

## Gradle layout

```
settings.gradle          root project name 'fix42-oms-cache'
build.gradle             shared Java 17 + JUnit 5
gradle/libs.versions.toml
gradlew                  wrapper (Gradle 9)

fix-proto/               protobuf-java, proto plugin
fix-codec/               depends on :fix-proto
oms-cache/               depends on :fix-codec
```

No Spring, no FIX engine, no database. The artifact a caller depends on is
`oms-cache`, which transitively brings codec + proto.

## Runtime flow

```
          raw FIX string
                │
                ▼
           FixParser  ── dictionary ──► FixMessage
                │
                ▼
        FixMessageMapper ─────────────► typed proto (D/8/9/F/G/H/Q)
                │
                ▼
             OmsCache.ingest
                │
     ┌──────────┼──────────┐
     ▼          ▼          ▼
 OrderLinker  StateUpdater  Indexes
     │          │            │
     └──────────┴────────────┘
                │
                ▼
           OrderState (proto)
```

### `fix-codec`

| Class | Responsibility |
|-------|----------------|
| `Tags` | Integer constants for every tag we name in code |
| `FixConstants` | SOH, BeginString `FIX.4.2`, MsgType chars |
| `FieldType` | `STRING`, `INT`, `QTY`, `PRICE`, `CHAR`, `UTCTIMESTAMP`, `NUMINGROUP` |
| `FieldDef` / `GroupDef` / `MessageDef` | Dictionary entries |
| `FixDictionary` | FIX 4.2 subset used by the seven messages |
| `FixParser` | SOH-split, header/body/trailer, group assembly, optional 9/10 checks |
| `FixSerializer` | Rebuilds `9` and `10`, stable-enough tag order (header, body insertion order, groups, trailer) |
| `FixParseException` | Malformed input |
| `FixMessageMapper` | `FixMessage` ↔ typed proto |

Parser rules:

- Input may use `|` or SOH (`\u0001`). `|` is accepted for tests and logs;
  output always uses SOH.
- `8` must be first, `9` second, `35` third, `10` last (FIX session
  requirement). We enforce this when `strictHeader=true` (default).
- BodyLength (`9`) and CheckSum (`10`) are validated when
  `validateChecksum=true` (default). Tests that hand-write messages can
  disable both, or use `FixSerializer` to stamp them.

### `oms-cache`

| Class | Responsibility |
|-------|----------------|
| `OmsCache` | Public façade |
| `CacheConfig` | history limit, stale-ER policy, parent tags, validation flags |
| `InMemoryOrderCache` | Maps + lock + dispatch |
| `OrderLinker` | Identifier resolution described in doc 02 |
| `OrderStateUpdater` | Field-level apply of each message type |
| `ParentLinkResolver` | Reads parent tags, maintains the multimap |

Thread safety: one `ReentrantReadWriteLock`. Ingest takes the write lock;
lookups take the read lock. Drop-copy is typically one writer. The lock
keeps the five indexes and the parent multimap consistent with
`OrderState`.

## Error handling

- Unparseable FIX → `FixParseException`. `ingest` does not swallow it.
- Unknown `MsgType` → `UnsupportedMessageTypeException`. The raw string is
  not stored.
- Missing both `ClOrdID` and `OrderID` on a state-changing message →
  `UnidentifiableOrderException`.
- `35=H` is accepted and ignored (returns the existing state if any).

## Configuration defaults

```
historyLimit            = 32
validateChecksum        = true
strictHeader            = true
applyStaleExecReports   = false
parentOrderIdTag        = 20001
parentClOrdIdTag        = 20002
```
