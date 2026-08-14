# AMPS Integration & State Distribution

**The question:** can AMPS (60East) serve as the persistent cache — leveraging SOW topics
and the transaction log? Or should the API provide a latest-order-state-change handler so
clients publish to AMPS themselves?

**The answer:** it's not either/or — the two ideas solve different problems, and the
deciding fact is a duality already established in [docs/07](07-persistence-and-recovery.md):

> Replaying **inputs** through the fold is non-idempotent (exact positioning required, or
> `message_history`/`update_count` silently corrupt). Republishing **derived outputs** is an
> idempotent last-value upsert (at-least-once is fine).

So:

1. **The state-change handler is built** — `OrderStateListener` (§4). It is the right
   integration point in *all* cases, keeps the core zero-dependency, and the AMPS SOW is
   its natural destination: publishing derived latest state to a last-value cache inherits
   the forgiving side of the duality. This is how other applications should *read* the
   cache (`sow_and_subscribe`, filters, conflation, views).
2. **The SOW must not be the recovery store** (§2 — rejected with specifics).
3. **The AMPS transaction log CAN replace the local journal** — but only under the
   conditions in §3, and only when the inbound FIX stream *already* transits AMPS upstream
   of this process. Otherwise durability stays with `:oms-persist`, which is exact, local,
   and dependency-free.

Every AMPS behavioral claim below is either cited to 60East documentation or explicitly
marked **UNVERIFIED** (do not build on those without testing). Key sources:
[SOW](https://crankuptheamps.com/docs/amps-user-guide/sow) ·
[txlog basics](https://crankuptheamps.com/docs/amps-user-guide/txlog/transaction-log-basics) ·
[bookmark replay](https://crankuptheamps.com/docs/amps-user-guide/txlog/replaying-messages-with-bookmark-subscription) ·
[message persistence](https://crankuptheamps.com/docs/amps-user-guide/txlog/understanding-message-persistence) ·
[delta publish](https://crankuptheamps.com/docs/amps-user-guide/delta-publish/understanding-delta-publish) ·
[protobuf message types](https://crankuptheamps.com/docs/amps-user-guide/message-types/protobuf-message-types) ·
[Java HA guide](https://crankuptheamps.com/clients/amps-client-java/high-availability).

---

## 1. AMPS capabilities that matter here (verified)

- **SOW** is a per-topic last-value store with a configurable primary key (server-computed
  from XPath `Key` fields, or publisher-supplied SowKey), optionally persistent across
  server restarts. `sow_and_subscribe` is atomic: snapshot then live, no gap, no duplicate.
- **Transaction log** journals configured topics; **bookmark subscriptions** resume "with
  the first message **following** the bookmark" (strictly-after), with exclusive `(` /
  inclusive `[` range specifiers; replay is "exactly the order in which the instance
  recorded them". Publishers get persisted acks and per-client-name monotonic-sequence
  dedup.
- **delta_publish** merges top-level fields; an updated subdocument/array is **replaced
  wholesale** (no append), and absent fields mean "unchanged" — a repeated field like
  `exec_ids` cannot be incrementally grown or shrunk via deltas.
- **Timestamps on replay**: header timestamps are absent by default; the `timestamp`
  subscribe option adds "the time at which the message was processed on the local
  instance". **UNVERIFIED:** whether a replayed message's header timestamp is byte-stable
  across repeated replays. *Do not build deterministic-fold arrival times on the header
  timestamp* — carry the arrival time inside the payload (§3).
- **Protobuf** is a supported message type (server-side content filtering and SOW keys
  work, configured against the `.proto`), but: proto3 without `optional` fields — which is
  what [our schema](../fix-proto/src/main/proto/order_state.proto) is — does **not**
  support delta publish/subscribe, and a view's *destination* type cannot be protobuf.
- **Java client**: official Maven Central artifact
  [`com.crankuptheamps:amps-client`](https://central.sonatype.com/artifact/com.crankuptheamps/amps-client),
  under 60East's proprietary API license (not OSS). The AMPS **server** is commercial
  (30-day single-machine eval available) — CI against a real server needs a licensed
  instance.

## 2. Rejected: SOW as the recovery store

Publishing derived `OrderState` to a SOW and bulk-loading it on restart **cannot be an
exact recovery mechanism** for this cache. Five independent leaks:

1. **The un-published tail (fatal alone).** State exists only after the fold, so publish
   follows apply; a crash between them loses the last updates with no way to measure the
   gap. Closing it requires replaying the missed *inputs* — i.e., a transaction log — so
   "SOW as recovery" collapses into §3 plus a SOW anyway.
2. **Index non-derivability.** The `clOrdId` index can hold past `OrigClOrdID(41)` values
   present in no final state (mid-stream cancel/replace joins) — indexes rebuilt from SOW
   records are silently missing lookup keys ([docs/07 §2](07-persistence-and-recovery.md)).
3. **`chain_seq` and the config fingerprint have no per-chain home**; a side control
   record can't be updated atomically with the states, and a wrong `chain_seq` collides
   chain ids after recovery.
4. **No multi-record atomicity.** One message can update a child and its parent's
   aggregate; AMPS has no cross-message transaction, so a crash between the two publishes
   leaves the SOW internally inconsistent — tolerable in a *view*, poisonous in a
   *recovery source*.
5. **O(history) write amplification** on the durability path: every fill republishes the
   chain's whole state (`message_history` included) — megabyte-class records late in a
   busy chain's day — versus ~430 B/record once in a journal. (delta_publish does not
   help: repeated fields are replaced wholesale, see §1.)

What the SOW *is* right for: distribution (§4) — precisely because re-upserting derived
state is idempotent.

## 3. Conditional: AMPS transaction log as the WAL ("swap the journal, keep the snapshot")

**Precondition: the inbound FIX stream already transits a journaled AMPS topic *upstream
of this process*.** Then the txlog is already the shop's durable, replayable record; the
cache is just another subscriber, and the local journal duplicates work. The design —
**not implemented; a sibling `:oms-amps` module if adopted** — keeps every other part of
`:oms-persist` (SnapshotStore ceremony, `SettableClock`, fail-stop latch, config
fingerprint) and replaces `JournalWriter/Reader` with:

- **Bookmark-in-snapshot, not a client bookmark store.** `CacheSnapshot` gains (new
  fields ≥ 15, `format_version` 2) the opaque bookmark of the last applied message plus
  topic and replication-group fingerprints. Captured under the same lock that serializes
  `process()`, "state" and "position" stay one atomic durable unit — eliminating the
  client-bookmark-store crash window (discard-before-apply loses a message,
  apply-before-discard duplicates one; both violate the non-idempotent fold).
- **Recovery** = restore snapshot → `bookmark_subscribe` from the stored bookmark
  (documented strictly-after resume; exclusive `(` range) → **boundary guard**: compare
  the first delivered message's bookmark for *equality* with the stored one and skip it if
  equal — strict-after becomes a verified local invariant, not a trusted server behavior.
  (Bookmarks are compared by equality only; client-side *ordering* of two opaque bookmarks
  is **UNVERIFIED**.)
- **Determinism and contiguity live in the payload, not AMPS headers.** The publisher
  stamps `arrival_epoch_millis` and a strictly-increasing per-publisher sequence *inside*
  the published message. The fold's clock reads the payload time on live and replay paths
  identically (the header-timestamp replay-stability being UNVERIFIED, §1); the consumer
  enforces `prev + 1` on the payload sequence — the direct analogue of `JournalReader`'s
  contiguity check — and **refuses to open** on any gap, which is also how expired txlog
  retention is detected (first delivered sequence > expected).
- **Scope of the exactness claim: single AMPS instance, or synchronously-replicated
  topics.** Cross-instance bookmark validity after failover is **UNVERIFIED** — until
  verified, treat failover recovery as not-exact and keep the local journal if that is
  unacceptable.
- **Operational inversion to accept:** locally, compaction is code-gated on snapshot
  durability (oldest *valid* snapshot anchors deletion). Under AMPS, "compaction" is txlog
  retention, which AMPS controls and our snapshots can't veto. The safety property becomes
  a monitored invariant: **txlog retention ≫ newest-snapshot age + recovery MTTR**, with
  an alarm as the age approaches retention.
- **If this process is itself the AMPS publisher** (retrofit scenario), the durability
  point moves: a crash between FIX receipt and the AMPS persisted ack loses the message,
  and an AMPS outage becomes an ingest single-point-of-failure the local journal never
  had. In that topology, gate upstream acks on the AMPS persisted ack — or keep the local
  journal and don't do A2 at all. Publisher restarts must also recover their sequence
  (client publish store or a payload-sequence handoff) so server dedup and the consumer's
  `prev+1` check stay coherent.

**When the feed does NOT already transit AMPS: don't do this.** It would put a commercial
server on the ingest-and-recovery path of a currently self-contained library (~56 k
records/s local replay, zero dependencies) purely to gain distribution — which §4 provides
without any of that coupling.

## 4. Built: the `OrderStateListener` SPI (as shipped)

The handler the question asked about exists, in `:oms-cache`
([OrderStateListener](../oms-cache/src/main/java/com/fix42/oms/cache/OrderStateListener.java),
[OrderStateChange](../oms-cache/src/main/java/com/fix42/oms/cache/OrderStateChange.java)),
with persistence wiring in
[PersistentOrderCache](../oms-persist/src/main/java/com/fix42/oms/persist/PersistentOrderCache.java).
The contract, exactly as implemented:

- **One notification per touched chain per message**, in touch order, fired inside the
  write critical section — total ordering per chain *and* across chains (a child's change
  always precedes the parent roll-up it caused). `parentRollUp` is true iff the notified
  chain is not the chain the message belongs to. A parent refresh that leaves the
  aggregate identical is suppressed entirely (no write, no notification).
- **`arrivalEpochMillis`** is the fold's clock reading as stamped on the processed chain's
  state — under persistence, the journaled arrival time, identical live and replayed. A
  parent roll-up entry carries the triggering child-message arrival.
- **Listener failures never reach the `process()` caller.** The state is applied — and,
  under persistence, journaled — *before* notification; failing the caller there would
  invite a retry, and a re-applied message corrupts the non-idempotent fold durably. A
  throwing handler is routed to `onListenerError(change, error)` (default: ignore;
  override to alarm / mark chains dirty) and the batch continues, so a failed child
  publish cannot strand its parent's update. Regression-tested in both modules.
- **Reentrancy is enforced, not advisory:** `process()` from inside a callback throws
  `IllegalStateException` (surfaced via the error channel; the reentrant mutation is never
  applied). Lock-free *reads* from a callback are safe and permitted.
- **Recovery replay is silent.** With `PersistenceConfig.announceRecoveredStates`, one
  synthetic change per restored chain (`previous == null`, `cause == null` —
  `isRecoveryAnnouncement()`) fires after replay completes. Note this announces the
  **whole cache** — O(cache size) at open, a deliberate belt-and-braces heal for a
  last-value store that missed publishes before a crash; it is safe *only because*
  re-upserting derived state is idempotent. Announce failures go to the error channel:
  **recovery of a healthy local store never depends on the downstream publisher being
  reachable** (test-enforced).
- Handlers must be fast (they run under the lock): enqueue-only, bounded queue, publish
  from a background thread.

```java
OrderStateListener publisher = new AmpsSowPublisher(...);   // your adapter
try (PersistentOrderCache cache = PersistentOrderCache.open(
        dir, DefaultParentLinkResolver.create(), CacheConfig.defaults(),
        PersistenceConfig.defaults().withAnnounceRecoveredStates(true),
        publisher)) {
    cache.asOmsCache().process(rawFix);   // journal -> fold -> notify
}
```

## 5. Writing the AMPS adapter (client-side guidance)

No adapter module ships in this repo — the AMPS client is proprietary and testing needs a
licensed server — but the SPI makes it ~100 lines. Hard rules, each earned above:

- **Enqueue-only handler**: bounded queue drained by a background publisher thread.
  On overflow or AMPS outage, **coalesce to latest-per-chain** (drop intermediate states —
  a last-value store only needs the newest; worst-case buffer = one state per active
  chain). On reconnect, republish dirty chains; `announceRecoveredStates` covers the
  restart case.
- **Never `delta_publish`.** Our protos are proto3 without `optional` (no delta support),
  and delta semantics replace repeated fields wholesale anyway. Always full `publish`.
- **SOW keyspace must be collision-proof.** `chainId` is stable within one cache
  generation but regenerates across fresh journals/business days, and two cache instances
  have independent counters. Key the topic on `{instanceId or businessDate}/{chainId}`;
  enforce a single writer per keyspace (the journal-directory lock does NOT protect the
  topic); purge the keyspace (`sow_delete`) on a cold start that restored nothing; give
  records an expiration or end-of-day purge.
- **Pick the topic message type deliberately:**

  | Option | You get | You give up |
  |---|---|---|
  | protobuf (`OrderState` bytes + server-side `.proto` config) | content filters, SOW keys, zero re-serialization | deltas, views-as-protobuf, enrichment |
  | JSON projection of `OrderState` | full feature set: deltas, views, ad-hoc filters | a mapping layer; decide per-field |
  | composite (json metadata + protobuf payload) | filterable header + exact state | config complexity |

  For the JSON/composite projections, **strip or cap `message_history`** — subscribers
  wanting the audit trail should read the journal/txlog, not the SOW record.
- Handlers must tolerate `isRecoveryAnnouncement()` deliveries (`cause == null`) — don't
  dereference `cause` unconditionally.

## 6. Decision summary

| | SOW as recovery store | AMPS txlog as WAL | Listener SPI + adapter |
|---|---|---|---|
| Recovered ≡ pre-crash | **No** (tail, indexes, chain_seq, atomicity) | Yes, under §3's conditions | Yes — unchanged `:oms-persist` |
| AMPS on ingest/recovery path | Yes | Yes (pre-existing iff feed already transits AMPS) | **No** |
| AMPS down ⇒ | recovery store has holes | ingest stalls (with the whole shop) | distribution lags; coalesce + heal |
| New dependency in core | client + adapter + restore path | client (`:oms-amps` sibling) | **None** (interface only) |
| Serves other consumers | yes | no (inputs, not states) | **yes** |
| Status | **rejected** | **documented option** (build when the precondition holds) | **implemented** |

**Bottom line:** provide the handler — done, it's `OrderStateListener` — and use AMPS SOW
as the *distribution* layer through it. Let AMPS also carry *durability* only when your
drop-copy already flows through a journaled AMPS topic upstream, and then via the
transaction log + bookmark-in-snapshot design of §3 — never via the SOW.
