# DuckDB Parquet Archive — Intraday & Historical Query

**The question:** the cache holds the latest state of every order in memory, and
`:oms-persist` can recover it after a crash. Neither answers *"what did this account trade
last Tuesday"*, and neither lets Deephaven, a notebook, or a risk job read the flow without
going through the Java API. This module (`:oms-parquet`) writes the flow to **Parquet**, so
any engine that reads Parquet can query it — intraday from local disk, historically from S3.

**The shape:** two partitioned datasets, batched in memory, materialised by an embedded
DuckDB, compacted at end of day, then moved to an object store.

```
raw FIX  ──► ArchivingOrderCache ──► OrderCache (fold) ──► OrderStateListener
              │                                                     │
              ▼ (audit copy, before the fold)                       ▼ (derived state)
        fix_messages/                                        order_state/
          2026/08/14/ACC1/IBM/*.parquet                        2026/08/14/ACC1/IBM/*.parquet
              │                                                     │
              └──────────────► ParquetCompactor (EOD) ◄─────────────┘
                                        │
                                        ▼
                               EndOfDayArchiver ──► s3://bucket/prefix/...
```

---

## 1. Two datasets, because there are two truths

| Dataset | Directory | One row per | Answers |
|---|---|---|---|
| `Dataset.RAW_FIX_MESSAGES` | `fix_messages/` | captured FIX message (`35=D,G,F,8,9,Q`) | "what exactly did the counterparty send, and when" |
| `Dataset.ORDER_STATE_CHANGES` | `order_state/` | latest-state change folded by the cache | "what did this order look like at any point in the day" |

They are written through separate entry points on purpose.

**Raw capture is an audit obligation**, so it hangs off ingest and happens *before* the fold
(`ArchivingOrderCache`). A message the state machine rejects — malformed, unknown chain, a
bug in the fold — is exactly the message an audit trail most needs. The cost of that
asymmetry is that the archive can hold a message the cache never accepted; `order_state`
records what the cache actually did with it, so the pair is still consistent.

**State changes are the cache's interpretation**, so they arrive through
`OrderStateListener` — the same SPI [docs/08](08-amps-integration.md) uses for AMPS. The
listener runs inside the cache's write critical section, so it only ever buffers a row.

Values are treated differently in each:

- `fix_messages` keeps **wire values** (`side = '1'`, `ord_status = '2'`) and the complete
  original message in `raw_fix`. Nothing is normalised away, including venue codes the
  dictionary does not know.
- `order_state` keeps **decoded names** (`side = 'BUY'`, `ord_status = 'FILLED'`), and an
  unset enum is NULL rather than `UNSPECIFIED` — an analyst filtering `side = 'BUY'` should
  not have to know the protobuf default.

---

## 2. Partitioning

The default is the specified layout, and everything else is a template over the same tokens
(`PartitionScheme`), so changing it is configuration, not code:

```java
config.withPartitionScheme(PartitionScheme.dateAccountSymbol());              // DEFAULT
//    fix_messages/2026/08/14/ACC1/IBM/fix_messages-20260814T133001250-host-7-3.parquet
config.withPartitionScheme(PartitionScheme.hiveDateAccountSymbol());
//    fix_messages/date=2026-08-14/account=ACC1/symbol=IBM/...
config.withPartitionScheme(PartitionScheme.hiveYearMonthDayAccountSymbol());
config.withPartitionScheme(PartitionScheme.dateSymbolAccount());
config.withPartitionScheme(PartitionScheme.dateOnly());
config.withPartitionScheme(PartitionScheme.template("desk/{yyyy}-{MM}/{dd}/{symbol}"));
```

Tokens: `{yyyy}` `{MM}` `{dd}` `{date}` `{account}` `{symbol}`. An unknown token is rejected
at construction, not silently emitted.

**Which is better depends on the reader.** Plain `YYYY/MM/DD/...` is the most portable and
is what a directory-glob reader wants — including a Deephaven watcher pointed at one day's
directory. The Hive variants emit `key=value` segments that DuckDB, Spark and Trino turn
into free partition columns with `hive_partitioning=1`, which pays off when queries filter by
account or symbol *across many days*.

**The partition columns are also stored inside every file.** With the default (non-Hive)
layout a reader cannot recover `event_date`, `account` or `symbol` from the path, so a plain
`read_parquet('<root>/**/*.parquet')` would otherwise lose them. Carrying them as real
columns makes the dataset self-describing under either layout — and means this module's own
scans pass `hive_partitioning=0` deliberately, so a Hive-style path does not try to
re-derive a column the file already has.

**Cardinality is the tradeoff to watch.** Partitioning by account *and* symbol multiplies
directories by (accounts × symbols) per day: excellent for "this account's IBM orders",
poor for wide scans, which end up opening many tiny files. That is what §5 compaction is
for; a very high-cardinality deployment can drop to `dateOnly()` or a symbol-only template
without losing any ability to filter, since the columns are in the files either way.

**Sanitising is lossy, and that is why the columns matter.** Account and symbol come off the
wire and become directory names, so everything outside `[A-Za-z0-9._-]` is replaced:
`BRK.B` keeps its dot, but `A/B` and `A_B` both land in `A_B`. `..`, `.`, a leading dot and
the empty string all map to `_unknown`, so a partition path can never escape the archive
root. The untouched values are always in the file.

**A FIX 4.2 detail worth knowing:** `35=9` (OrderCancelReject) carries neither `Account(1)`
nor `Symbol(55)`. Its raw row therefore lands under `_unknown/_unknown`, while the
`order_state` row for the same chain partitions correctly — the cache knows the account and
symbol from the rest of the chain. Join on `cl_ord_id` / `order_id`, not on the path.

**The trading day is a business decision.** `partitionZone` decides which day a message
belongs to: `America/New_York` files a 21:30 UTC fill under the 14th, `UTC` under the 15th.
`timestampSource` decides which clock is read at all — `ARRIVAL_CLOCK` (default),
`SENDING_TIME` (tag 52) or `TRANSACT_TIME` (tag 60), the latter two falling back to the
clock when the tag is absent or unparseable. Replaying a week-old drop-copy file under
`ARRIVAL_CLOCK` files every message under today; under `SENDING_TIME` it lands on the day it
happened.

---

## 3. Batching: freshness against query performance

`BatchPolicy` is the one knob that trades intraday latency against file quality, and both
directions cost something real.

- Flush often → an intraday reader sees an order within seconds, and the day accumulates
  thousands of tiny files, each with its own footer and none with useful row-group
  statistics.
- Flush rarely → every file is a healthy row group, and a fill can sit in memory for
  minutes.

Defaults target *"a Deephaven intraday table lags by at most a minute"*: **50 000 rows or
60 seconds per partition, whichever comes first**. `BatchPolicy.lowLatency()` (5k / 5s) and
`BatchPolicy.bulkLoad()` (500k, no age flush) are presets for the other two shapes.

Three more triggers exist:

- **`maxBufferedRows`** (default 1M) caps total rows held across *all* partitions and
  force-flushes the largest batch when exceeded. A feed spread over many (account, symbol)
  pairs holds far more in aggregate than any single batch ever reaches.
- **`flush()`** writes everything and blocks until it is on disk.
- **`close()`** flushes, then releases the writer threads and DuckDB instances.

**The ingest thread does almost nothing.** `writeRawMessage` and the state listener take a
lock, append to an `ArrayList`, and return; turning a batch into a file happens on a writer
thread. That matters because the listener runs inside the cache's write critical section — a
DuckDB `COPY` there would stall every order in the process.

**Backpressure is deliberate.** The writer executor has a bounded queue and a caller-runs
policy: when writers fall behind, the submitting thread writes the batch itself. Ingest
slows to writer speed, which is the only alternative to unbounded memory growth or silently
dropping rows.

> **This is an analytics sink, not a durability mechanism.** Rows live in memory until their
> batch flushes; a crash loses whatever had not been written. `:oms-persist`'s write-ahead
> journal is what makes a message *recoverable* — the two are complementary and are meant to
> be used together.

---

## 4. How a file gets written

`DuckDbParquetWriter`, per batch:

1. `CREATE OR REPLACE TABLE stg_… (…)` on a private DuckDB instance.
2. Append the rows through DuckDB's **appender** — the bulk path, roughly an order of
   magnitude faster than `INSERT` batches.
3. `COPY (SELECT * FROM stg_…) TO '<hidden temp file>' (FORMAT PARQUET, COMPRESSION 'zstd',
   ROW_GROUP_SIZE 100000)`. Insertion order is preserved, so rows land in arrival order and
   the `ts` column's row-group statistics stay tight — which is what lets a time-range
   predicate skip row groups.
4. **Atomic rename** into place.

Step 4 is the reason an intraday reader is safe: a `read_parquet` glob or a directory watcher
only ever opens finished files. Temp files are named `.<name>.parquet.tmp` — hidden, and not
matched by `*.parquet`.

**Why an instance per writer thread.** Writing a batch is a catalog write, and concurrent
catalog writes on one DuckDB instance conflict. Each writer thread gets its own in-memory
database instead, which removes the contention entirely; the cost is one buffer pool per
session, which is why `DuckDbConfig.memoryLimit` (default 1 GiB) is **per session**. Sessions
are pooled because opening one costs tens of milliseconds; a session that threw is closed
rather than reused, since a failed statement can leave a transaction or a half-built staging
table behind.

---

## 5. End of day: compaction, then the move

### 5.1 Compaction

`ParquetCompactor` merges a partition's many small files into few large ones. Streaming
capture optimises for freshness and produces a file per batch per partition; by the close of
a busy day, thousands of them. Every one costs a reader an open, a footer parse and a schema
reconcile before a single row is read. Compaction pays that cost once, offline.

```java
try (ParquetCompactor compactor = new ParquetCompactor(config)) {
    compactor.compactAll(LocalDate.of(2026, 8, 14));   // both datasets, one day
}
```

Properties that matter:

- **Idempotent and incremental.** An already-compacted file is just another input to the next
  run, so re-running is safe and a partition that gained files since the last run is merged
  again. A partition already down to one file is skipped rather than rewritten.
- **Verified before anything is deleted.** The merged row count is compared with the sources
  (`CompactionPolicy.verifyRowCounts`, on by default); on a mismatch the outputs are removed
  and the sources are left as the partition's only copy.
- **Totally ordered.** Rows are ordered by `(ts, writer_id, ingest_seq)`. `ts` alone is not
  enough — a busy millisecond holds many rows, and leaving those ties to the scan is merely
  untidy for one output file but actively wrong when the output is **split** (past
  `maxRowsPerOutputFile`): re-deriving the row numbering per output part would duplicate some
  rows and drop others. A split therefore materialises the numbering into a staging table
  once and reads it N times.
- **One window of duplicates.** For each partition the merged output is written, verified,
  moved into place, and only *then* are the sources deleted. Between the move and the deletes
  a reader can see both. The alternative ordering risks losing data outright, so this one is
  chosen deliberately — run compaction after the day closes, or accept that an intraday query
  straddling the swap may double-count.
- **Single writer.** Run one compactor per archive root at a time.

### 5.2 The overnight move

`EndOfDayArchiver` runs compaction and then copies the day to an `ObjectStore`, optionally
reclaiming local disk and pruning the emptied directories:

```java
archive.flush();                                  // everything buffered is on local disk
try (EndOfDayArchiver eod = new EndOfDayArchiver(
        config,
        EndOfDayPolicy.moveToObjectStore(),       // compact + upload + delete local
        new DuckDbS3ObjectStore(S3Config.of("oms-archive", "prod", "us-east-1"), config.duckDb()))) {
    EndOfDayArchiver.Result result = eod.run(yesterday);
}
```

Keys mirror the local layout exactly (`fix_messages/2026/08/14/ACC1/IBM/…`), so the same
`read_parquet` glob works against either side.

**Partial failure is expected and reported, not fatal.** One file that fails to upload does
not abandon the rest; it is counted in `UploadResult.failures()` and sent to the
`ArchiveErrorHandler`. A file is deleted locally only after *its own* upload succeeded, so a
failure always leaves the local copy behind and re-running finishes the move.

**S3 without an AWS SDK.** `DuckDbS3ObjectStore` uses DuckDB's `httpfs` extension and a
`CREATE SECRET` (credential chain by default; explicit key/secret for MinIO and friends).
Because DuckDB has no raw object PUT, an upload is
`COPY (SELECT * FROM read_parquet('<local>')) TO 's3://…'` — the rows are identical but the
file is **re-encoded**, so it is not byte-identical to the local copy. Where byte-for-byte
fidelity is required (a WORM or regulatory copy), implement the `ObjectStore` SPI over the
AWS SDK — that is exactly why it is an SPI. `LocalDirectoryObjectStore` copies bytes verbatim
and covers NFS/S3-mounted paths and staging directories drained by another tool.

`INSTALL httpfs` needs network access on first use; pre-install the extension and construct
with `installExtension = false` in a locked-down environment.

---

## 6. Schemas

Both datasets lead with `event_date`, `ts`, `writer_id`, `ingest_seq`. `(writer_id,
ingest_seq)` is unique, which makes it the dedupe key and the tie-breaker for ordering.

**`fix_messages`** — `msg_type`, `account`, `symbol`, `cl_ord_id`, `orig_cl_ord_id`,
`order_id`, `exec_id`, `exec_ref_id`, `secondary_cl_ord_id`, `side`, `ord_type`,
`order_qty`, `price`, `stop_px`, `time_in_force`, `currency`, `ord_status`, `exec_type`,
`exec_trans_type`, `last_qty`, `last_px`, `last_mkt`, `cum_qty`, `leaves_qty`, `avg_px`,
`text`, `ord_rej_reason`, `cxl_rej_reason`, `cxl_rej_response_to`, `dk_reason`,
`sending_ts`, `transact_ts`, `msg_seq_num`, `sender_comp_id`, `target_comp_id`,
`poss_dup_flag`, `raw_fix`.

An absent or blank tag is **NULL**, never `""` or `0` — NULL is what `IS NULL` and min/max
statistics understand, and it keeps "the venue omitted LastPx" distinguishable from "the
venue sent an empty LastPx". `raw_fix` can be turned off (`captureRawFixText`) to save
space; it is typically the largest column, and turning it off keeps every lifted column and
loses byte-level auditability.

**`order_state`** — `chain_key`, `order_id`, `cl_ord_id`, `orig_cl_ord_id`, `account`,
`symbol`, `side`, `ord_type`, `order_qty`, `price`, `stop_px`, `time_in_force`, `currency`,
`ord_status`, `prev_ord_status`, `last_exec_type`, `is_terminal`, `cum_qty`, `leaves_qty`,
`avg_px`, `last_qty`, `last_px`, `last_mkt`, `text`, `ord_rej_reason`, `cxl_rej_reason`,
`parent_order_id`, `is_parent`, `child_order_count`, `cl_ord_id_chain`, `exec_id_count`,
`update_count`, `first_seen_ts`, `last_update_ts`, `last_msg_type`, `change_kind`,
`cause_msg_type`, `cause_cl_ord_id`, `cause_exec_id`.

- `chain_key` is the chain's **first** `ClOrdID(11)` (falling back to `OrderID(37)` for a
  chain that never carried one), and it is the column to `PARTITION BY` / `lastBy`. It has to
  be stable for the chain's whole life, which rules out both obvious candidates: `OrderID`
  does not exist until the first ExecutionReport, and the current `ClOrdID` changes on every
  cancel/replace. Either of those splits one order's history into two groups, and `last_by`
  then reports a long-filled order as still pending under its old key. `order_id`,
  `cl_ord_id` and `cl_ord_id_chain` are all still there to join and filter on.
- `change_kind` is `CREATE` | `UPDATE` | `PARENT_ROLLUP` | `RECOVERY`. `PARENT_ROLLUP` marks
  a parent refreshed by a child's message; `RECOVERY` marks the synthetic re-announcement
  `:oms-persist` emits after replay (`announceRecoveredStates`).
- `is_terminal` covers `FILLED`, `CANCELED`, `REJECTED`, `EXPIRED`, `DONE_FOR_DAY`.
  `REPLACED` is deliberately excluded: the cache keeps one chain across a cancel/replace, so
  a chain in `REPLACED` is still working under its new ClOrdID.
- Price-like fields (`price`, `stop_px`, `avg_px`, `last_qty`, `last_px`) are NULL when the
  proto default `0.0` means "absent" — a market order has no limit price, an unfilled order
  no average price — so the day's cheapest fill is not reported as zero. Quantities keep
  their zeros: `leaves_qty = 0` is a real, meaningful value.

`TIMESTAMP` columns are UTC rendered without a zone; `event_date` is the trading date in
`partitionZone`. Both are stored because they can legitimately disagree.

---

## 7. Querying

`ParquetArchiveReader` is a thin DuckDB reader; its scan expressions are the ones any other
engine should use.

```java
try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
    // every order's latest state right now, for one account — open orders only
    reader.query("SELECT * FROM ("
               + "  SELECT *, row_number() OVER ("
               + "      PARTITION BY chain_key ORDER BY ts DESC, update_count DESC) AS rn"
               + "  FROM " + reader.scanSql(Dataset.ORDER_STATE_CHANGES)
               + "  WHERE account = 'ACC1') WHERE rn = 1 AND NOT is_terminal");

    // one day only — other days' files are never opened
    reader.count(Dataset.RAW_FIX_MESSAGES, LocalDate.of(2026, 8, 14));
}
```

From anything else:

```sql
-- intraday, straight off local disk
SELECT * FROM read_parquet('/data/oms/order_state/2026/08/14/**/*.parquet', union_by_name=true);
-- historical, from S3 after the overnight move
SELECT * FROM read_parquet('s3://oms-archive/prod/fix_messages/2026/08/**/*.parquet');
```

**Deephaven intraday.** Point a watcher at `order_state/<today>/` and `lastBy("chain_key")`
for the live order blotter; the append-only history means no updates ever have to be
retracted, and the atomic rename means a partially written file is never visible. For a
symbol- or account-scoped table, narrow the glob to the partition — the layout exists
precisely so a reader can skip everything else at the directory level.

---

## 8. Operations

**Wiring.**

```java
ParquetArchive archive = ParquetArchive.open(ParquetArchiveConfig.defaults(Path.of("/data/oms")));
OmsCache cache = new OmsCache(archive.wrap(
        new InMemoryOrderCache(DefaultParentLinkResolver.create(),
                               CacheConfig.defaults(),
                               archive.orderStateListener())));
```

`PersistentOrderCache` composes the same way: pass `archive.orderStateListener()` to
`PersistentOrderCache.open(...)` and wrap the result with `archive.wrap(...)`.

**Two writers, one archive root** is safe only with **different `writerId`s** — it is
stamped into every row and every file name, and is what keeps two processes from colliding
in the same partition. The default is `hostname-pid`.

**Failures on the write path never reach ingest.** A batch flush happens on a writer thread
long after the `process()` call whose rows it carries returned, so there is no caller left to
fail. Failures go to `ArchiveErrorHandler` (default: log at ERROR) and are counted:

```java
ArchiveStats stats = archive.stats();
stats.rowsAccepted(); stats.rowsWritten(); stats.rowsDropped();
stats.filesWritten(); stats.bytesWritten(); stats.flushFailures(); stats.rowsBuffered();
stats.isFullyFlushed();     // everything accepted is written, nothing lost
```

A non-zero `rowsDropped` means Parquet output is incomplete and no longer matches the cache —
alarm on it the way a dropped market-data tick would be alarmed on. A failed batch is not
retried: its rows are already out of the buffer, and re-queueing them behind a persistent
failure (a full disk) would trade lost rows for unbounded memory.

**Configuration reference.** `ParquetArchiveConfig.defaults(root)` plus `with…`:

| Setting | Default | Notes |
|---|---|---|
| `writerId` | `hostname-pid` | stamped into rows and file names |
| `partitionZone` | `UTC` | the trading-day boundary |
| `partitionScheme` | `{yyyy}/{MM}/{dd}/{account}/{symbol}` | §2 |
| `capturedMsgTypes` | `D, G, F, 8, 9, Q` | `H` excluded: a query, not an order event |
| `rawFixDirectory` / `orderStateDirectory` | `fix_messages` / `order_state` | must differ |
| `captureRawFixText` | `true` | the largest column |
| `rawFixDelimiter` | `'\|'` | `FixConstants.SOH` to keep exact wire bytes |
| `timestampSource` | `ARRIVAL_CLOCK` | or `SENDING_TIME` / `TRANSACT_TIME` |
| `unknownPartitionLabel` | `_unknown` | absent account or symbol |
| `batchPolicy` | 50k rows / 60s / 1M buffered / 1 writer | §3 |
| `duckDb` | 1 GiB, 2 threads, ZSTD, 100k row groups | per session |
| `clock` | `Clock.systemUTC()` | inject a fixed clock in tests |
