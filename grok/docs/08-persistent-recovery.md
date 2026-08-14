# Persistent cache and process-restart recovery

v1 keeps every order in heap maps. A JVM exit — clean or `kill -9` — loses
the book. This note is the recovery design: what must survive, what can be
rebuilt, which persistence style to use, and how it sits on top of the
current `InMemoryOrderCache` without changing the ingest API.

It does **not** add clustering or a hot standby. The goal is: **one process
dies, the same process (or a replacement on the same disk) comes back with
the same latest order state**.

## 1. What actually has to survive

`InMemoryOrderCache` holds two kinds of data.

### Must persist (source of truth)

| Data | Why |
|------|-----|
| `OrderState` per `order_key` | Latest book. Already a protobuf; this is the natural snapshot record. |
| Per-order FIX history ring | Not on `OrderState`. Lost unless stored beside the snapshot or rebuilt from a WAL. |
| Monotonic apply sequence | So recovery knows “snapshot is current through seq N”. |
| Optional source cursor | Drop-copy file offset, queue offset, or FIX session `MsgSeqNum`, so the *feed* can resume without a full-day replay. |

### Must **not** persist (rebuild on load)

These are derived and will drift if stored separately from `OrderState`:

| In-memory index | Rebuilt from |
|-----------------|--------------|
| `byKey` | `OrderState.order_key` |
| `clOrdIdToKey` | `cl_ord_id` + `orig_cl_ord_id` + `cl_ord_id_history` |
| `orderIdToKey` | `order_id` + `secondary_order_id` |
| `execIdToKey` | `last_exec_id` + `seen_exec_ids` |
| `accountToKeys` / `symbolToKeys` | `account` / `symbol` |
| `parentToChildren` / `childToParent` | `parent_order_id`, `parent_cl_ord_id`, `child_order_keys` |

If a restart only reloads `OrderState` rows and runs `IndexRebuilder`,
lookups behave as they did before the crash. That is the whole point of
keeping identity history *on the proto* (`cl_ord_id_history`,
`seen_exec_ids`).

### Must not be treated as durable

- The `ReentrantReadWriteLock`, parser, mapper, `CacheConfig`
- `last_update_epoch_ms` (wall clock of the last apply; useful, not a
  recovery key)
- In-flight `ingest` that never reached the WAL

## 2. Two recovery strategies (use both)

Drop-copy recovery is always some mix of **replay the tape** and
**reload the book**.

```
                    ┌─────────────────────────────┐
   live FIX  ──►    │  in-memory cache (hot path) │
                    └─────────────┬───────────────┘
                                  │ after each applied ingest
                    ┌─────────────▼───────────────┐
                    │  WAL  (every raw FIX + seq) │
                    │  Snapshot (all OrderState)  │
                    │  Checkpoint (seq + cursor)  │
                    └─────────────▲───────────────┘
                                  │ on start
                    load snapshot → rebuild indexes
                    → replay WAL where seq > snapshot.seq
                    → resume feed from checkpoint.cursor
```

### Strategy A — Replay the drop-copy source

If the feed is a file, Kafka topic, or a broker that will resend from a
sequence number, you *can* recover with no local store: start empty and
re-ingest.

That only works when:

1. The source is still there and readable from a known offset.
2. Ingest is **idempotent** for duplicates (same `ExecID` + same
   `ExecTransType` must not double-count qty).
3. A full-day replay is fast enough for your RTO.

Many drop-copy sessions are **not** replayable after a disconnect
(no resend, or the session is gone). Then A alone is insufficient.

### Strategy B — Persist the book locally

Reload `OrderState` from disk. Independent of the feed. Required when
the source cannot rewind, and desirable even when it can (seconds to
recover vs replaying millions of ERs).

**Recommendation:** B is the default durability path. A is the *safety
net* for the gap between the last durable seq and the last message the
source actually delivered. Persist a **source cursor** next to the
snapshot so A can fill that gap.

## 3. Options considered

| Option | Recover RTO | Crash safety | Ops | Fit |
|--------|-------------|--------------|-----|-----|
| Periodic full JSON/file dump | Slow; loses the last interval | Easy to tear a file | None | Fine for a toy, not a drop-copy book |
| Snapshot only, after every ingest | OK at small N | Need atomic replace | None | Simple; write amplification grows with book size |
| **WAL of raw FIX + periodic snapshot** | Fast (snapshot + short tail) | Append is the natural crash unit | None | Best first implementation; no new native deps |
| SQLite / H2 one row per order | Fine | Transactions | JDBC | Fine if you already run a DB; heavier than we need |
| RocksDB / Chronicle Map as primary | Fast | Engine fsync | Native lib | Right *after* the SPI exists, if ingest rate demands it |
| Redis / remote cache | Fast | Another process to run | Network + Redis | Solves multi-process sharing, not “this JVM restarted” |
| Replay-only, no local store | = full tape | None locally | Source must retain | Keep as a mode (`persistence=none`) for tests |

Rejected as the *only* store:

- **Caffeine / Guava Cache + writer listener** — still no secondary
  indexes, and a listener is not a WAL.
- **Java serialization of `InMemoryOrderCache`** — brittle across
  versions; indexes would be snapshotted instead of rebuilt.

## 4. Recommended design

Keep the hot path exactly as it is: apply in memory under the write
lock, serve reads from the maps.

Add a `StateStore` behind the lock (or immediately after apply, still
on the writer thread):

```
public interface StateStore extends Closeable {
    long append(WalRecord record);          // returns seq
    void checkpoint(Snapshot snapshot);     // atomic replace
    Optional<RecoveryImage> recover();      // latest snapshot + WAL tail
}
```

### 4.1 On-disk layout (zero extra dependencies)

```
dataDir/
  wal/
    wal-00000001.log          # length-prefixed protobuf records
    wal-00000002.log
  snapshot/
    snap-00000042.tmp         # written then fsync'd
    snap-00000042.pb          # renamed into place (atomic on POSIX)
    CURRENT                   # text: snap-00000042.pb
  checkpoint.pb               # last seq + optional source cursor
```

A `WalRecord` is small and append-only:

```
message WalRecord {
  int64  seq = 1;
  int64  epoch_ms = 2;
  string order_key = 3;          // after apply / rekey
  string raw_fix = 4;            // original inbound string
  string msg_type = 5;
  optional string source_cursor = 6;   // caller-supplied
}
```

A `Snapshot` is the whole book plus enough to skip the WAL prefix:

```
message PersistedOrder {
  OrderState state = 1;
  repeated string history = 2;   // same ring as memory
}

message Snapshot {
  int64  up_to_seq = 1;
  int64  taken_epoch_ms = 2;
  repeated PersistedOrder orders = 3;
  optional string source_cursor = 4;
}
```

`CURRENT` + `rename` is the crash contract: a torn `.tmp` is ignored;
recovery always opens the last complete snapshot named by `CURRENT`,
then replays WAL records with `seq > up_to_seq`.

### 4.2 Write path (ingest)

```
1. parse + apply in memory          (today)
2. wal.append(raw, order_key, cursor)
3. if wal is durable (fsync policy): only then return ProcessResult
4. every N messages or T ms: write Snapshot, rotate WAL
```

Order of 1 then 2 means a crash *after apply, before append* can lose
that message unless the source replays it. That is the right trade-off
only if the caller can rewind.

Safer default for a fire-and-forget drop copy:

```
1. wal.append(raw)                  // durable first
2. parse + apply
3. if apply throws: the WAL has a record that recovery will replay
```

Prefer **WAL-first** when `CacheConfig.durableIngest=true` (default
once persistence is on). Apply-first is a test/dev flag.

`fsync` policy (config, not a new store):

| Mode | Meaning | Use |
|------|---------|-----|
| `EVERY_RECORD` | `FileChannel.force(false)` after each append | Default for drop copy you cannot rewind |
| `GROUP` | force every N ms / M records | High-rate feeds; RPO = the group window |
| `NONE` | OS flush only | Dev / tests |

### 4.3 Recovery path (process start)

```
image = store.recover()
cache = new InMemoryOrderCache(config)
if image.snapshot present:
    for order in snapshot.orders:
        install(order.state)          // no state-machine re-run
        restore history ring
    IndexRebuilder.rebuild(cache)     // all five indexes + parent map
for rec in wal where rec.seq > snapshot.up_to_seq:
    cache.ingest(rec.raw_fix)         // goes through the real updater
return cache, image.checkpoint.cursor
```

`install` is a new package-private path: put `OrderState` into `byKey`
without incrementing `version` and without writing the WAL again
(recovery must not append). A `replaying` flag on the cache suppresses
persistence while the tail is applied.

After recovery the caller resumes the feed at `source_cursor` if it
has one. If not, it just continues; the WAL already holds everything
the process had accepted.

### 4.4 Idempotency (required for WAL replay)

Replay will deliver the same `ExecID` again. Today a duplicate ER still
overwrites qty unless `TransactTime` is older. That is not enough:
two ERs can share a clock second.

Tighten `OrderStateUpdater.applyExecutionReport`:

- If `exec_id` is already in `seen_exec_ids` **and**
  `exec_trans_type` is `0` (New) → bind identifiers only, do not
  re-apply qty/status (`applied=false`).
- `ExecTransType=1/2` (bust/correct) still apply: they are new events
  that reuse `ExecRefID`, not `ExecID`.
- Snapshot install does not go through the updater, so it cannot
  double-apply.

WAL-tail replay then becomes safe even if a snapshot was taken in the
middle of a group-commit window and the same record is seen twice.

### 4.5 Rekey

`order_key` can change from `ClOrdID` to `OrderID` on the first ER.
The WAL stores the **post-apply** key. Snapshots store the current
key only. Recovery never has to know about old keys: indexes are
rebuilt from `cl_ord_id_history`.

Do **not** persist the in-memory maps. A rekey mid-snapshot would
otherwise leave a stale `byKey` entry on disk.

### 4.6 History

Two equivalent choices; pick one and keep it:

1. **Snapshot carries the ring** (recommended). Recovery of a snapshot
   without a WAL tail restores `getHistory` exactly.
2. History is *only* the WAL slice for that `order_key`, truncated to
   `historyLimit`. Cheaper snapshots, slower history rebuild.

Use (1). History is already capped at 32 strings; it is cheap.

### 4.7 Compaction

When a snapshot at `up_to_seq=S` is durable:

- delete WAL segments whose last seq ≤ S
- keep at least one previous snapshot until the new `CURRENT` is
  fsync'd

This is the only retention the library needs. Multi-day books should
also let the caller drop **terminal** orders (`2/4/8/C`) older than a
horizon; that is a later API (`evict(predicate)`), not part of
recovery.

## 5. API shape (no change to ingest callers)

```java
CacheConfig config = CacheConfig.builder()
    .persistence(PersistenceConfig.builder()
        .dataDir(Path.of("/var/lib/oms-cache"))
        .fsync(FsyncMode.EVERY_RECORD)
        .snapshotEveryMessages(1_000)
        .snapshotEvery(Duration.ofSeconds(5))
        .build())
    .build();

Recovery recovery = InMemoryOrderCache.recover(config);
OmsCache cache = recovery.cache();
Optional<String> cursor = recovery.sourceCursor();
// resume drop-copy from cursor, then:
cache.ingest(rawFix, SourceCursor.of("file:dropcopy.log:offset=12345"));
```

`ingest(String)` stays. An overload carries the cursor so the
checkpoint can move with the feed. If the caller never passes a
cursor, recovery still restores the book; only feed-resume is lost.

`StateStore` lives in a new package `com.fix42.oms.persist` inside
`oms-cache` (or a `:oms-persist` module later if RocksDB is added).
v1 of persistence is files + protobuf, no new Gradle dependency.

## 6. Failure modes

| Crash moment | Result after restart |
|--------------|----------------------|
| After WAL force, before apply | Record replayed via `ingest`; idempotent |
| After apply, before WAL (if apply-first) | Message gone unless source replays from cursor |
| Torn WAL tail (partial last record) | Length prefix fails; drop the torn record (it was never forced) |
| Torn `snap-*.tmp` | Ignored; previous `CURRENT` snapshot + WAL tail |
| Snapshot rename succeeded, `CURRENT` not updated | Previous snapshot loads; WAL tail is longer; still correct |
| Disk full on append | `ingest` throws; in-memory apply rolled back **or** process refuses further ingest until append works. Prefer fail the ingest (WAL-first) so memory and disk cannot diverge. |
| Schema change of `OrderState` | Protobuf unknown-field retention; add fields with new numbers only |

Memory vs disk divergence is the one unforgivable bug. WAL-first +
fail the call on I/O error keeps them aligned.

## 7. What this is not

- **Not a cluster.** Two processes must not share one `dataDir`.
  Fencing is “one writer, one directory.”
- **Not a FIX session store.** `MsgSeqNum` / resend request stay in
  the session engine. We only keep an opaque `source_cursor` the
  caller understands.
- **Not historical tick reconstruction.** The WAL is a recovery log,
  compacted after snapshots. If you need the full day tape, keep the
  drop-copy file.

## 8. Implementation plan

Follow-on to the v1 library. Each step ships with crash tests.

1. **Idempotent ER apply** — skip qty/status when `ExecID` already
   seen and `ExecTransType=0`. Unit tests with a replayed fill.
2. **`IndexRebuilder`** — given a collection of `OrderState`,
   populate every map. Test: build a book, discard indexes, rebuild,
   every lookup matches.
3. **`WalRecord` / `Snapshot` protos + `FileStateStore`** — append,
   force, atomic snapshot, torn-tail ignore. Tests use a temp dir and
   a truncated last record.
4. **`InMemoryOrderCache.recover` + WAL-first ingest** — install
   snapshot, replay tail, suppress WAL during replay. Integration:
   ingest the drop-copy tape, close, `kill` not required; open a new
   cache on the same dir; `snapshot()` equals the pre-restart
   snapshot (ignore `last_update_epoch_ms`).
5. **Crash-image test** — write N messages, copy the data dir after
   each, recover each copy, assert book == prefix of the tape.
6. **Optional `ingest(raw, cursor)`** — persist and return the
   cursor from `Recovery`.

Suggested module layout when this lands:

```
fix-proto/     + persist.proto (WalRecord, Snapshot, Checkpoint)
oms-cache/     + persist/FileStateStore, IndexRebuilder, recover()
```

No RocksDB until a profiler says file WAL is the limit.

## 9. Decision

| Question | Answer |
|----------|--------|
| Persist what? | `OrderState` + history ring + apply seq + optional source cursor |
| Persist how? | Append-only WAL of raw FIX, periodic atomic snapshot |
| Rebuild what? | Every secondary index and the parent/child multimaps |
| When is a message durable? | After WAL `force` (default), then apply |
| How do we restart? | Load snapshot → rebuild indexes → replay WAL tail → resume feed at cursor |
| New dependencies? | None for the file store |
| Change to current ingest API? | None required; optional cursor overload |

The in-memory cache stays the product. Persistence is a
**write-behind log of already-typed state plus the raw tape needed to
catch up**, not a second source of truth that the read path hits.
