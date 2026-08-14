# Protobuf schema

Four proto files in `fix-proto/src/main/proto`. Java package
`com.fix42.oms.proto`.

## `enums.proto`

Character-valued FIX 4.2 enumerations used by the typed messages and by
`OrderState`. Stored as proto enums whose numeric value is the ASCII code
of the FIX character (`SIDE_BUY = 49` for `'1'`), so a caller can do
`(char) side.getNumber()` and get a legal FIX byte.

Covered:

- `Side`, `OrdType`, `TimeInForce`
- `OrdStatus`, `ExecType`, `ExecTransType`
- `CxlRejResponseTo`, `DKReason`, `HandlInst`

Unrecognised wire values map to `*_UNKNOWN = 0` plus the raw character on
the typed message (`raw_ord_status`). The cache always persists the raw
character so we never invent a status.

## `fix.proto`

Generic lossless FIX envelope.

```
FixField          { tag, value }
FixGroupInstance  { fields[], nested_groups[] }
FixGroup          { num_in_group_tag, instances[] }
FixHeader         { begin_string, body_length, msg_type,
                    sender_comp_id, target_comp_id, msg_seq_num,
                    sending_time, extra[] }
FixTrailer        { check_sum }
FixMessage        { header, fields[], groups[], trailer, raw }
```

`raw` is the original inbound string (optional). It is what the history
ring buffer stores. Serializer ignores `raw` and rebuilds from fields.

## `messages.proto`

One message type per in-scope `35=`:

| Proto | MsgType |
|-------|---------|
| `NewOrderSingle` | D |
| `ExecutionReport` | 8 |
| `OrderCancelReject` | 9 |
| `OrderCancelRequest` | F |
| `OrderCancelReplaceRequest` | G |
| `OrderStatusRequest` | H |
| `DontKnowTrade` | Q |

Shared shape:

- `FixHeader header`
- First-class fields for every tag the state machine reads
  (`cl_ord_id`, `orig_cl_ord_id`, `order_id`, `exec_id`, `account`,
  `symbol`, qty/px, status, parent tags, …)
- Nested messages for repeating groups (`Alloc`, `ContraBroker`,
  `MiscFee`, `TradingSession`)
- `repeated FixField extra` for everything else
- `string raw` for the original FIX

Numeric FIX fields (`OrderQty`, `Price`, `CumQty`, …) are `double` on the
typed messages. FIX is decimal-as-string; the mapper parses with
`BigDecimal` and then stores `double` for the cache hot path. Tests assert
on well-known decimal values (no binary-fraction surprises at 2–4 dp).

## `order_state.proto`

The cache value.

```
message OrderState {
  string order_key = 1;                 // stable identity
  string cl_ord_id = 2;                 // current
  string orig_cl_ord_id = 3;
  repeated string cl_ord_id_history = 4;
  string order_id = 5;
  string secondary_order_id = 6;
  string account = 7;
  string symbol = 8;
  string security_id = 9;
  string side = 10;                     // raw FIX char(s)
  string ord_type = 11;
  string time_in_force = 12;
  string ord_status = 13;
  string exec_type = 14;
  string exec_trans_type = 15;
  string last_exec_id = 16;
  double order_qty = 17;
  double cum_qty = 18;
  double leaves_qty = 19;
  double last_qty = 20;
  double last_px = 21;
  double avg_px = 22;
  double price = 23;
  double stop_px = 24;
  string parent_order_id = 25;
  string parent_cl_ord_id = 26;
  repeated string child_order_keys = 27;
  string transact_time = 28;
  string last_msg_type = 29;
  int64  last_update_epoch_ms = 30;
  int32  version = 31;                  // increments on every applied update
  string text = 32;
  string ord_rej_reason = 33;
  string cxl_rej_reason = 34;
  string cxl_rej_response_to = 35;
  string dk_reason = 36;
  bool   pending_cancel = 37;
  bool   pending_replace = 38;
  bool   dk_trade = 39;
  repeated string seen_exec_ids = 40;
}
```

`child_order_keys` is denormalised onto the parent for cheap
`getChildren`. The authoritative parent→child map still lives in the
cache indexes so a parent row that has not been created yet can already
list children.

## Generation

`fix-proto` applies the `com.google.protobuf` Gradle plugin.
Generated sources are **not** checked in. `./gradlew :fix-proto:build`
emits them under `fix-proto/build/generated/source/proto/main/java`.

Tests for generated types live in `fix-proto/src/test/java` and assert:

- builders round-trip required fields
- enum number ↔ FIX character
- `OrderState` serializes and parses as binary protobuf
