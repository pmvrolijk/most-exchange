# Order entry adapters: requirements

An **adapter** is the process between a client's own protocol (FIX, or a proprietary session protocol)
and this exchange's order entry gateway (Design.md §1, "Order Entry Gateway"). It is **outside this
project**. The gateway speaks binary SBE on both legs and knows nothing about FIX. This document is
the contract an adapter must meet so that its clients never have an order whose fate they cannot
learn. It is the starting point for the adapter framework (Status.md §3).

`most load` (`tools/.../LoadCommand.kt`, `ReportLedger.kt`) and `most status` (`StatusCommand.kt`)
already implement every rule below, and are the worked examples. The encoders an adapter needs live in
`reference`: `ParticipantRequests` for the resend and status requests. **MUST** and **SHOULD** are
used in their usual sense.

---

## 1. Connecting

- **Find the gateway.** The directory (Design.md §7, "Tradable Universe Discovery") names one order
  entry and one report endpoint per shard, and every security's shard and geometry. A participant
  served by another gateway learns its endpoints out of band, as member connectivity usually works.
  An adapter MUST route each order to the shard that hosts its security.
- **Co-located gateways** (`gateway.placement=colocated`, Design.md §7) are one per node, and only the
  leader's is active. An adapter for such a shard MUST hold every node's endpoints, and MUST subscribe
  to every node's report stream, since the one answering moves.
- **Both legs before the first message.** Wait until the order publication *and* the report
  subscription are connected. A subscription comes up asynchronously, and an acknowledgement can
  beat it.
- **Each adapter has its own client endpoints.** Two adapters on one inbound channel would each
  receive every order and forward both, which is duplicates, not redundancy (ProdDeployment.md
  §2.1).
- **The adapter authenticates its clients; the gateway does not.** The gateway enforces which
  *participants* it may act for (`UNAUTHORIZED_PARTICIPANT`). Who may use an adapter's endpoint is
  the adapter's problem (Design.md §1, "Enforcement, at the gateway").
- **On a reconnect, a restart or a logon, run a mass status** (§4) before trading, to learn what is
  open.

## 2. Orders, identifiers and back-pressure

- **`clOrdId` MUST be unique per participant for at least the trading day.** The engine does not
  check it. A report carries `clOrdId`, and a duplicate makes two orders indistinguishable to the
  client. Map the client's own identifier (FIX ClOrdID) to it one to one.
- **Keep `exchangeOrderId`** from the first report. A cancel names both `exchangeOrderId` and
  `origClOrdId`.
- **Prices and quantities are fixed-point `int64` with 8 implied decimals** (`PriceCodec`). Never pass
  a floating-point value through.
- **Back-pressure is not loss.** A publication offer that returns `BACK_PRESSURED` or `ADMIN_ACTION`
  MUST be retried, not dropped. The gateway itself holds a message the cluster cannot take yet. Only
  `NOT_CONNECTED` / `CLOSED` mean the gateway is gone (§3).
- **Operator commands do not belong in an adapter.** Session transitions, purges, definitions, images
  and bulk cancels go through an operator gateway, which a participant's gateway refuses.

## 3. Failover and gateway switching

- **`GATEWAY_UNAVAILABLE` means "try another gateway"**, never "the order was refused by the market".
  With co-located gateways, move to the next node's gateway on it.
- **A disconnect means the same.** A co-located gateway dies with its node and sends no reject. When
  the publication to the gateway in use reports `NOT_CONNECTED`, move to the next connected one.
  Orders sent in that blind window (Aeron's publication connection timeout, 5 s by default) are lost
  without a reply, and §4 settles them.
- **After any switch, fence before the next order** (§4). An independent gateway's failover is
  invisible except as `GATEWAY_UNAVAILABLE` rejects and a `reportSeq` gap, which is why the gap is a
  trigger too.

## 4. Keeping track: report sequence, resend, status

Design.md §5, "Report sequence and resend" and "Order mass status", is normative. What an adapter
MUST do:

- **Keep, per order, everything needed to answer the client**: the client's identifier,
  `clOrdId`, `exchangeOrderId` once known, and whether any report has arrived. An order with no
  report yet is **in flight**.
- **Keep, per participant, which `reportSeq` values have arrived.** They run from 1, gap-free, across
  all of the participant's orders and all gateways. A report with `reportSeq` 0 was made by the
  gateway (a local reject) or is an `ORDER_STATUS`, and is outside the sequence. Count each sequenced
  report **once**: a replay resends some you already have.
- **On a gap, or after a gateway switch, send a `ReportResendRequest`** per participant, from its first
  missing `reportSeq`, through the active gateway. It is sequenced like an order, so it is a
  **fence**:
  - on `ReportResendComplete` with `COMPLETE`, every report below `nextSeq` has been sent. **An
    in-flight order sent before the request that still has no report was never sequenced.** Reject
    it to the client, or send it again. Either is safe, and it will execute at most once;
  - on `UNAUTHORIZED_PARTICIPANT` or `GATEWAY_UNAVAILABLE`, nothing was sent. Switch, and fence
    again.
- **On `TRUNCATED`, send an `OrderMassStatusRequest`** for that participant. It answers one
  `ORDER_STATUS` per open order, with the engine's `origQty`, `cumQty` and `leavesQty`, then
  `OrderMassStatusComplete` with `nextSeq`.
  - **Resume the participant's sequence from `nextSeq`**: the status stands in for the lost reports.
  - An in-flight order found open is resting, with those quantities.
  - One **not** found is **ambiguous**: never sequenced, or finished (filled, cancelled, expired) in
    the window the ring no longer reached. At most `oldestRetainedSeq − fromSeq` of them were
    sequenced. Report them to the client as unknown, and reconcile fills from trade records. Never
    present them as rejected.
- **Run a mass status at the open, and after any restart of the adapter.** It is how an adapter that
  lost its own state learns what is open.

## 5. What an adapter must keep, and for how long

| What | Why | How long |
| --- | --- | --- |
| Per order: client id ↔ `clOrdId` ↔ `exchangeOrderId`, answered or not | Answering the client, cancelling, settling in-flight orders at a fence | Until terminal, and the trading day for `clOrdId` uniqueness |
| Per participant: which `reportSeq` arrived, first missing | Gap detection, resend `fromSeq`, deduplicating replays | The session; across an adapter restart if possible |
| Per participant: the last fence's position in its own order stream | Which in-flight orders a completion settles | Until the completion |
| Every gateway endpoint of the shard | Switching on a reject or a disconnect | Configuration |

**Persisting the first two across an adapter restart** turns a restart into a resend from where it
stopped. Without it, an adapter restarts on a mass status: open orders are recovered exactly, and
anything that finished while it was down is learned only from trade records.

## 6. Not yet provided by the exchange

These belong in the adapter framework's plan:
- **No trade record (drop copy).** Fills of orders that finished inside a truncated window, or while
  an adapter with no persisted state was down, cannot be recovered from the exchange (Design.md §8;
  roadmap: persisted TradeReports).
- **No order-status query by `clOrdId`.** The mass status covers open orders only.
- **A participant's last report, if dropped, is noticed only at its next report or request.** An
  adapter SHOULD fence when a client order has gone unanswered for longer than a failover takes.
- **The directory names one gateway per shard** (open issue 6). Co-located endpoints are
  configuration.
