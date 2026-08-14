# FIX 4.2 Identity and Message Linking

Drop-copy streams do not give you one stable primary key. They give you a
**family of identifiers** that appear, change, and chain as the order lives.
The cache's first job is to collapse that family onto one `order_key`.

## The identifier tags

| Tag | Name | Who assigns it | Lifetime |
|-----|------|----------------|----------|
| 11 | `ClOrdID` | Client / OMS | Changes on every cancel/replace |
| 41 | `OrigClOrdID` | Client / OMS | Previous `ClOrdID` (not the day's first id) |
| 37 | `OrderID` | Broker / venue | Stable across replaces for the same working order |
| 198 | `SecondaryOrderID` | Broker / venue | Optional second broker id; also stable |
| 17 | `ExecID` | Broker / venue | Unique per execution report (new / correct / cancel) |
| 19 | `ExecRefID` | Broker / venue | Points at the `ExecID` being busted or corrected |

`ClOrdID` is **required** on electronically submitted orders. `OrderID` is
**required** on Execution Report and is the only identifier that is *not*
required to change when the client amends the order.

## How messages join onto one order

```
client                    broker                         cache identity
------                    ------                         --------------
D  11=C1                  8  11=C1 37=O9 17=E1 150=0     key=O9
                                                         ClOrdID C1 → O9
                                                         OrderID O9 → O9
                                                         ExecID  E1 → O9

G  11=C2 41=C1            8  11=C2 41=C1 37=O9 150=E     key=O9 (same)
                                                         ClOrdID C2 → O9
                                                         C1 still → O9

F  11=C3 41=C2            9  11=C3 41=C2 37=O9 434=1     key=O9 (same)
                                                         ClOrdID C3 → O9
                                                         status reverts
```

Resolution order when a message arrives:

1. If `OrderID (37)` is present **and** already mapped → that `order_key`.
2. Else if `ClOrdID (11)` is present **and** already mapped → that `order_key`.
3. Else if `OrigClOrdID (41)` is present **and** already mapped → that `order_key`.
4. Else create a new order:
   - `order_key = OrderID` if present, otherwise `ClOrdID`.
5. Bind every identifier present on the message to that `order_key`.

This is deliberately **idempotent**. Replay of the same drop-copy file must
converge on the same keys.

### Why `OrderID` wins

`ClOrdID` / `OrigClOrdID` form a chain. If the stream is missing an
intermediate replace, the chain breaks. `OrderID` does not: the broker keeps
it constant for the working order. Preferring `OrderID` heals a broken chain
as soon as the next Execution Report arrives.

### `ExecID` is not an order key

`ExecID` identifies an **event**, not an order. The cache indexes
`ExecID → order_key` so `getByExecId` works and so a Don't Know Trade (`35=Q`)
or a bust (`ExecTransType=1`) can find the order. It never becomes `order_key`.

`ExecRefID` is resolved the same way: look up the referenced `ExecID`, then
the order.

## Cancel / replace chaining

FIX 4.2 requires:

- On `F` and `G`, `OrigClOrdID` is the **current** `ClOrdID` of the working
  order (not the day's original id).
- The request's own `ClOrdID` is a **new** client id.
- On the resulting Execution Report:
  - `ExecType=E` (Pending Replace) / `6` (Pending Cancel) echo the new id.
  - `ExecType=5` (Replaced) makes the new `ClOrdID` current.
  - `ExecType=4` (Canceled) is terminal.
- `Order Cancel Reject (9)` carries `CxlRejResponseTo (434)`:
  - `1` = reject of a cancel (`F`)
  - `2` = reject of a replace (`G`)
  and the last accepted `OrdStatus`.

The cache therefore:

- On `G`/`F`: attach the **new** `ClOrdID` to the existing key immediately
  (so later ERs that only carry the new id still join).
- Set a **pending** status locally (`Pending Cancel` / `Pending Replace`).
- On `8` with `150=5` or `150=4`: take the ER as authoritative.
- On `9`: restore `OrdStatus` from the reject message (tag 39) and clear
  the pending flag.

## Parent / child orders

FIX 4.2 has **no standard parent-order tag**. `ClOrdLinkID (583)` arrives in
4.3. Drop-copy vendors invent a user-defined field.

This cache treats parentage as a first-class, **configurable** link:

| Default tag | Name | Meaning |
|-------------|------|---------|
| 20001 | `ParentOrderID` | Broker id of the parent (`OrderID` of the parent) |
| 20002 | `ParentClOrdID` | Client id of the parent (`ClOrdID` of the parent) |

A child message that carries either tag is linked:

- `parent_order_id → {child order_key, ...}`
- `child order_key → parent_order_id` (and `parent_cl_ord_id` if present)

Parent state and child state are **independent rows**. A parent that also
trades (or receives its own ERs) is updated from those messages. Children
never overwrite the parent's `CumQty` / `OrdStatus`.

The API additionally exposes a **rollup** view (sum of child `CumQty`,
`LeavesQty`, fill count) computed on read. It is not stored.

If a child is seen before its parent, the parent index still records the
child. When the parent message later arrives, it reuses the same
`ParentOrderID` / resolved `ClOrdID` and the children are already attached.

## Out-of-order and duplicate drop copy

Drop copy is not a sequenced session from the cache's point of view.

- **Duplicates:** same `ExecID` + same `ExecTransType` is a no-op for
  quantity fields; identifiers are still rebound (cheap, makes indexes whole).
- **Stale ERs:** if `TransactTime (60)` is present and is **older** than the
  order's last applied `TransactTime`, the ER does not overwrite status/qty.
  Identifiers are still bound. This is configurable (`applyStaleExecReports`).
- **Missing New:** an ER or cancel that arrives with no prior `D` still
  creates the order. Audit streams often start mid-day.

## Worked example

```
# parent algo
8=FIX.4.2|35=D|11=P1|55=MSFT|54=1|38=1000|40=2|44=420|

# child 1
8=FIX.4.2|35=D|11=C1|55=MSFT|54=1|38=400|40=2|44=420|20001=P1|20002=P1|
8=FIX.4.2|35=8|11=C1|37=B1|17=E1|150=0|39=0|14=0|151=400|6=0|

# child 2 + fill
8=FIX.4.2|35=D|11=C2|55=MSFT|54=1|38=600|40=2|44=420|20001=P1|
8=FIX.4.2|35=8|11=C2|37=B2|17=E2|150=2|39=2|32=600|31=420|14=600|151=0|6=420|

# replace child 1
8=FIX.4.2|35=G|11=C1b|41=C1|37=B1|38=300|44=421|
8=FIX.4.2|35=8|11=C1b|41=C1|37=B1|17=E3|150=5|39=5|14=0|151=300|6=0|
```

After this stream:

- `getByClOrdId("C1")`, `getByClOrdId("C1b")`, `getByOrderId("B1")` all
  return the same child, current `ClOrdID=C1b`, `OrderQty=300`.
- `getChildren("P1")` returns `{B1, B2}` (or `C2` if B2 not yet assigned —
  whatever `order_key` each child settled on).
- Parent rollup: filled 600, working 300.
