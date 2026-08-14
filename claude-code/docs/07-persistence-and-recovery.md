# Persistence & Crash Recovery (`:oms-persist`)

How the cache survives a process restart. This document is **authoritative and as-built**:
it records the analysis (strategy survey, recovery-time arithmetic), the chosen design
(write-ahead journal + snapshots), the failure-mode contract, and the hardening applied
after an adversarial design review. The implementation lives in the `:oms-persist` module
(`com.fix42.oms.persist`), with the on-disk payload formats in
[`persistence.proto`](../fix-proto/src/main/proto/persistence.proto).

---

## 1. Problem statement

`InMemoryOrderCache` holds the latest `OrderState` of every order chain purely in memory:
a crash or restart loses everything. We need the cache to come back with **exactly** the
state it had — statuses, quantities, exec-id dedup sets, message history, parent/child
links, and every identifier index — without re-requesting the day's stream from upstream.

## 2. The two facts that shape the whole design

The cache is **derived state**: every `OrderState` is a deterministic fold
(`OrderStateUpdater.apply`) of the message stream, given (a) message order and (b) the
per-message arrival time read from the injected `java.time.Clock`. Two properties of the
existing fold dominate the design space:

1. **Replaying the same message twice is NOT idempotent.** Economic fields dedupe on
   ExecID, but `message_history` and `update_count` grow on every apply. Any recovery
   scheme that can re-deliver an already-applied message corrupts the audit trail —
   so recovery positioning must be **exact** (strictly greater than a high-water mark),
   which favors an internally *sequenced* journal over at-least-once external feeds.
2. **`OrderState` carries its own history**, so the value grows with the chain. Anything
   that persists the full state *per update* (embedded KV, compacted topics) pays
   O(history) write amplification per message. A journal writes each message once.

One more subtlety discovered during analysis: the `clOrdId` index can hold past
`OrigClOrdID(41)` values that are **not recoverable from final states** (mid-stream
cancel/replace joins) — indexes are state, not a derivable view, so snapshots persist all
six index maps verbatim.

## 3. Strategy survey

| Dimension | (a) Upstream replay | (b) Journal-only WAL | **(c) Snapshot + journal** | (d) Embedded KV (RocksDB/MapDB…) | (e) External (Redis/Kafka/Chronicle…) |
|---|---|---|---|---|---|
| Recovery time | Bound by upstream retention + transfer; full re-fold | Grows with **lifetime** volume | **Flat**: snapshot load + one snapshot-interval of fold | Fast open, but full scan to rebuild heap maps | Fast if remote survives; network rebuild scan |
| Write-path cost | Zero | 1 append (+fsync per policy) | Same as (b); snapshots off the hot path | Full-state rewrite per msg + compaction amplification | Network RTT + full-state amplification |
| Exactness | **Not exact**: re-stamped arrival times; FIX resends/at-least-once double-append history | Exact (`OrderState.equals()`) | **Exact** (`OrderState.equals()`), incl. indexes + `chain_seq` | Exact only with one atomic multi-key batch per message (state + up to 6 index writes + parent roll-up) | Same atomicity problem + partition ambiguity |
| Dependencies | None locally | **None** (protobuf already present) | **None** (protobuf already present) | JNI native lib / extra jars | A second service to run |
| Storage | None | Unbounded append | Bounded (compaction) | Engine-managed | Server-managed |
| Audit fit | Trail lives upstream only | Journal *is* a lossless applied-message log | **Best**: applied-message log + point-in-time checkpoints | Final states only | Depends on flavor |

**Recovery-time arithmetic.** Assume ~430 B/journal record; a busy drop-copy feed of
10 M msgs/day ≈ 4.3 GB/day of journal. Replay is fold-bound, not I/O-bound. The
integration smoke test measures **~56 k records/s** end-to-end recovery (journal read +
CRC + parse + fold, Apple-silicon laptop, OS-buffered). At that rate:

- journal-only, 10 M msgs: **~3 min** — and it grows with every retained day;
- snapshot hourly: snapshot load + ≤420 k msgs ≈ **≤10 s**, flat forever.

Pick the snapshot interval from your recovery budget: `interval ≈ budget × fold_rate`.

**Recommendation: (c) snapshot + journal.** Zero new dependencies, exact recovery, flat
recovery time, and the journal doubles as a lossless audit log. See §9 for when the
alternatives win.

## 4. As-built design

```
        live path                                   restart
process(msg)                                   open(dir, …)
  │ assign seq (gap-free)                        │ load newest VALID snapshot (fallback older)
  │ append JournalRecord{seq, arrivalMs, msg}    │ restore states + 6 indexes + chain_seq
  │   └ fsync per policy                         │ verify config fingerprint (fail on mismatch)
  │ stateClock.set(arrivalMs)                    │ replay journal seq > highWater
  │ inner.process(msg)                           │   └ strict contiguity; re-inject arrivalMs
  ▼                                              │ heal torn tail (truncate) / refuse mid-corruption
returns ⇒ message is recoverable                 │ start FRESH segment at maxSeq+1
                                                 ▼
                                            state ≡ pre-crash (OrderState.equals())
```

### 4.1 Journal (`JournalWriter` / `JournalReader`)

- Segment files `journal-<startSeq, zero-padded-20>.log`; names sort into replay order.
- Segment = 8-byte magic `FIXJRNL1`, then frames `[int32 len][JournalRecord proto][int32 CRC32(payload)]`.
- `JournalRecord{sequence, arrival_epoch_millis, FixMessage}` — the **parsed** message is
  journaled (raw string reconstructable via `FixSerializer`), plus the arrival time that
  the fold's clock read, so replay reproduces timestamps exactly.
- Sequences are strictly increasing and gap-free; the writer enforces a shared 64 MiB
  record bound **before** writing (an oversized record would be acked but unreadable).
- Rotation at `maxSegmentBytes`; the directory is fsynced when a segment is created so a
  power loss cannot vanish a rotated segment's directory entry after records were acked.
- Fsync policy: `EVERY_RECORD` (durable when `process()` returns) or `OS_BUFFERED`
  (microsecond appends; an OS/power crash may lose the unflushed suffix — a mere process
  crash loses nothing).

### 4.2 Snapshot (`SnapshotStore`, `CacheSnapshot`)

- `snapshot-<lastAppliedSeq>.pb` = magic `FIXSNAP1` + length + `CacheSnapshot` proto + CRC32.
- Contents: every `OrderState`, **all six index maps verbatim**, the internal `chain_seq`
  counter, the journal high-water mark, and a **config fingerprint**
  (`format_version`, `historyCap`, `rollUpParents`, parent-link tag).
- Written atomically: tmp file + fsync → atomic rename → directory fsync. The loader picks
  the newest snapshot whose magic/CRC/parse validate, falling back to older generations —
  a corrupt snapshot degrades recovery time, never correctness.
- `InMemoryOrderCache.exportSnapshot()/fromSnapshot()` run under the cache's write lock;
  the persist layer calls them under its own process-serializing lock, so the captured
  high-water mark can never race a concurrent `process()`.

### 4.3 Recovery (`PersistentOrderCache.open`)

1. Acquire the exclusive `oms-persist.lock` file (fail fast if another process — or this
   JVM — already has the directory open).
2. Load the newest valid snapshot; **verify the config fingerprint** — recovering with a
   different `historyCap`/`rollUpParents`/parent-tag would silently diverge the fold, so
   it fails loudly instead.
3. Replay every journal record with `sequence > highWater`, re-injecting each record's
   arrival time via the `SettableClock`. The reader enforces **strict contiguity**: each
   sequence must be exactly `previous + 1` and each segment's first record must match its
   filename — a missing or reordered segment refuses to open rather than recovering a
   silently wrong cache. A complete-but-unknown segment magic is a version refusal, never
   treated as corruption.
4. **Heal or refuse corruption** (see §5): a tear at the physical tail of the *final*
   segment is the expected crash artifact — truncate it at the last valid frame and fsync,
   so the tear can never masquerade as mid-journal corruption after the *next* crash.
   Corruption anywhere else refuses to open (acked records beyond it would be lost).
5. Resume journaling at `maxSeq + 1` in a **fresh segment** (never append after a healed
   tail); a leftover same-named segment — possible only when it contributed no recoverable
   records — is quarantined aside.

### 4.4 Compaction

After a snapshot is written *and its directory entry is durable* (directory fsync — if the
filesystem can't do that, compaction is skipped rather than risked), journal segments
fully below the **oldest retained snapshot that still validates** are deleted. Anchoring
on the oldest *valid* generation (not the newest) is what makes snapshot-corruption
fallback safe: the records between the two generations are still on disk. A segment is
fully covered iff the next segment's `startSeq ≤ highWater + 1` (segments are contiguous);
the active segment is never deleted.

### 4.5 Determinism (why recovered state is exact)

- The fold's only time source is the injected clock; `PersistentOrderCache` wires a
  `SettableClock` into the inner cache and sets it from the journaled arrival time on both
  the live and replay paths — recovered timestamps equal pre-crash timestamps.
- `chain_seq` is restored from the snapshot, so chain ids never collide; journal-only
  replay from empty regenerates identical ids (same message order ⇒ same ids).
- Parent roll-up iterates child chains in **sorted order**, so `child_order_ids` ordering
  and floating-point summation order are independent of hash-set iteration internals
  across live runs, replays, and restores.
- The contract is **`OrderState.equals()` equality**, not byte-identity: protobuf map
  fields (`pending_prior_status`) have no canonical serialization order.

## 5. Failure-mode decision table

| Situation | Behavior |
|---|---|
| Crash between journal append and in-memory apply | Replay applies the record — consistent (in-memory state died anyway) |
| Torn frame / CRC fail / unparseable record at the tail of the **final** segment | Expected crash artifact: truncate at last valid frame + fsync, continue |
| Torn segment header (final segment) | Quarantine the file (`.torn`); no record from it was ever acked |
| Corruption in a **non-final** segment | **Refuse to open** — acked records beyond it would be silently lost |
| Sequence gap or segment/filename mismatch | **Refuse to open** — journal was tampered with or a segment is missing |
| Complete-but-unknown segment magic | **Refuse** — a newer format is never misread as a torn tail |
| Newest snapshot corrupt | Fall back to an older generation + longer journal replay (compaction anchored on oldest *valid* snapshot guarantees the records exist) |
| Snapshot format version newer than supported / config fingerprint mismatch | **Refuse to open** with the exact mismatch |
| `IOException` on journal append | Nothing applied, sequence not consumed; instance **fail-stops** (a partial frame may be on disk) — close and re-open |
| In-memory apply throws after a durable append | Instance **fail-stops** (memory ≠ journal); recovery re-applies deterministically |
| Second `open()` of the same directory (other process or same JVM) | `IllegalStateException` via the `oms-persist.lock` file lock |

## 6. Hardening from the adversarial review

An adversarial critique of the initial design/implementation found real defects; all are
fixed and regression-tested:

1. **Torn tail after a second crash (critical).** "Stop at first bad record" + "new
   segment per restart" silently dropped every acked record *after* an old tear on the
   second recovery. Fix: recovery **physically truncates** the torn tail (§4.3 step 4);
   mid-journal corruption now always refuses. Test: `doubleCrashWithTornTailRecoversAllAckedRecords`.
2. **Compaction racing snapshot durability (critical).** Segments were deletable before
   the snapshot's rename was durable. Fix: directory fsync is a *precondition* of
   compaction; compaction anchors on the oldest **validating** snapshot. Tests:
   `corruptNewestSnapshotStillRecoversFully`, `pruneKeepsOnlyNewestN`.
3. **No sequence-contiguity verification (critical).** A manually deleted middle segment
   replayed as a silent gap. Fix: strict `expected = prev + 1` and filename checks.
   Test: `sequenceGapFromMissingSegmentRefusesToOpen`.
4. **Config fingerprint.** Recovering under a different `historyCap`/`rollUpParents`/
   parent-tag silently diverges the fold — now recorded in the snapshot and enforced.
   Test: `configFingerprintMismatchFailsLoudly`.
5. **Writer/reader record-bound mismatch**; **unknown magic misread as tear**; **stray
   `.tmp` cleanup**; **fail-stop latch** after write-path errors; **"byte-identical"
   overclaim** downgraded to `OrderState.equals()`; **hash-order-dependent parent
   roll-up** made order-insensitive (sorted).

## 7. Operational guidance

- **Fsync**: `EVERY_RECORD` for at-most-ms durability per message; `OS_BUFFERED` when the
  upstream can replay the last few seconds after a power failure (process crashes lose
  nothing either way).
- **Snapshot cadence**: from your recovery budget (§3). `snapshot()` runs under the same
  lock as `process()` — call it from a housekeeping thread at quiet moments or end-of-day
  rather than `snapshotEveryRecords` (which pays the same stall inside a `process()` call).
- **Set a `historyCap` for persistent deployments.** Snapshot size is dominated by
  `message_history`; `historyCap=0` on a large day can approach protobuf's 2 GiB
  single-message limit, at which point snapshots fail while the journal (and recovery
  time) grow unboundedly. The journal itself preserves the full audit trail regardless.
- **Monitor**: snapshot failures (recovery time grows while they fail), `.torn` /
  `.recovered` quarantine files (crash artifacts; investigate if frequent), journal
  directory size (compaction working?).
- The directory must be on a local filesystem (`FileLock` and directory fsync semantics
  are unreliable on NFS).

## 8. Usage

```java
import com.fix42.oms.api.OmsCache;
import com.fix42.oms.persist.PersistentOrderCache;

try (PersistentOrderCache cache = PersistentOrderCache.open(Path.of("/var/oms/journal"))) {
    OmsCache oms = cache.asOmsCache();          // same facade API as the in-memory cache
    oms.process("8=FIX.4.2|35=D|11=ORD1|…");    // journaled, then applied
    cache.snapshot();                            // checkpoint (e.g. from a timer / EOD)
}
// restart:
try (PersistentOrderCache recovered = PersistentOrderCache.open(Path.of("/var/oms/journal"))) {
    recovered.getByClOrdId("ORD1");              // state ≡ pre-crash
}
```

Custom wiring: `PersistentOrderCache.open(dir, parentResolver, cacheConfig, persistenceConfig)`
— the same `ParentLinkResolver`/`CacheConfig` as the in-memory cache plus
`PersistenceConfig` (fsync policy, segment size, snapshots to keep, auto-snapshot cadence).

## 9. When the alternatives win

- **Upstream replay** (FIX session store / Kafka offset rewind): when the feed is already
  durable+replayable *and* you don't need exact timestamps or the local audit trail —
  then run stateless and re-fold on start (this library supports that trivially: feed the
  day's log again into a fresh `OmsCache`). Note FIX resends (`PossDup`) re-deliver
  messages, which double-appends `message_history` — exactness is lost.
- **Embedded KV (RocksDB)**: when state must exceed RAM. You'd still want this journal for
  the audit trail, and every message's index+state updates must become one atomic batch.
- **External stores (Redis/Kafka/Chronicle)**: when *another process* must read the state
  concurrently — that's a distribution requirement, not a durability one.

## 10. Limitations / future work

- Write throughput is serialized by one lock (matches the in-memory cache; per-chain
  striping is a shared future optimization).
- Snapshots are synchronous; a background snapshot thread (export under the lock, write
  outside it, single in-flight) would remove the ingest stall.
- A streamed multi-frame snapshot format would lift the 2 GiB protobuf ceiling without
  requiring `historyCap`.
- Group-commit fsync (batch several appends per `force`) would raise `EVERY_RECORD`
  throughput at the cost of bounded ack latency.
- `exec_ids` dedup is an O(n) list scan per ExecutionReport — long-lived chains with very
  many fills degrade both live and replay throughput.

## 11. Test coverage (26 tests in `:oms-persist`, plus 1 in `:oms-cache`)

- **Journal**: round-trip fidelity, skip-below-high-water, rotation across segments, torn
  tail keeps complete records, CRC corruption stops replay, oversized-record rejection,
  unknown-magic refusal, empty dir.
- **Snapshots**: round-trip, newest-wins, prune-to-N, corrupt-newest fallback, `.tmp`
  hygiene, oldest-valid anchor.
- **Recovery integration**: journal-only and snapshot+tail recovery reproduce the exact
  pre-crash state (`OrderState.equals()`, timestamps included, via a deterministic ticking
  clock); parent/child aggregates survive restart; sequences continue across three
  generations with zero duplicate applies; corrupt-newest-snapshot full recovery;
  double-crash torn-tail healing; mid-journal corruption and sequence-gap refusal; config
  fingerprint mismatch; directory lock; facade round-trip; recovery throughput smoke
  (~56 k records/s on the dev machine).
- **`:oms-cache`**: `exportSnapshot`/`fromSnapshot` reproduces states and all six indexes
  and keeps folding correctly afterwards.
