# most-exchange — Architecture Tour — speaker notes

Source: a private claude.ai Slides artifact (README.md).

The HTML export drops speaker notes, so they are kept here. This file is generated from
the artifact's slides: regenerate it after changing a slide, never edit it by hand.

## 1. most-exchange

most-exchange is a matching engine for a central limit order book. In the next five minutes: the rules it is built on, the processes and technology, a full production setup with participants trading through FIX adapters, how it is operated, and what has been measured so far.

## 2. A shard is one log, applied on three machines

Before anything else: what a shard is. A shard is one Aeron Cluster — three machines running Raft — hosting up to ten securities. Everything that can change state — orders, cancels, operator commands — is written into a single replicated log and given a position. Each node's archive records that log to disk, and each node's engine applies it in order. Because the engine is deterministic, the same log produces the same books, byte for byte, on every node. We replicate inputs, not state. Aeron Cluster was chosen because it provides consensus, sequencing, the archive and snapshots in one library designed for microsecond latency, on the same transport and wire format as the rest of the system. Its leader epochs make it impossible for a superseded leader to keep publishing — the failure the earlier primary/hot-spare design could not rule out.

## 3. Four rules the whole design answers to

Everything that follows is a consequence of four rules. Determinism is the big one: because every cluster node runs the same single-threaded engine over the same replicated log, they all end up byte-identical — which is what makes failover free. The flip side is that a crash is replicated too, so the engine validates defensively and rejects rather than throws. No wall-clock reads, no allocation on the hot path, and zero-copy publishing keep it both deterministic and fast.

## 4. Eight modules, four processes on the hot path

Eight Gradle modules. Four of them are the processes an order or a price actually passes through: the engine, the gateway, market-data and discovery. The engine runs inside Aeron Cluster, which provides Raft consensus, global sequencing and the archive that records the log. Every message on the wire is SBE, generated from a single schema, so no one hand-writes a byte offset. The control plane is the only part with a database and a web stack, and it is deliberately never on a boot path.

## 5. One order, one cache line

The engine is built around the cache. Each order is packed into eight longs — exactly one 64-byte cache line — so matching an order costs one cache miss. Fields matching never reads, like the original quantity, live in a separate cold array so the hot line stays 64 bytes. Each book pre-allocates a million orders and a direct-mapped price ladder, and a shard hosts up to ten independent books.

## 6. A price is an array index

Each side of each book is a flat array of price levels. A price maps straight to an index: subtract the floor, divide by the tick size. Each level keeps a head and tail pointer into the order pool, so its resting orders form a first-in-first-out queue — that is time priority, with no allocation. Each level also keeps its total quantity and order count, which is what market data and the auction need. A bitset marks which levels are occupied, so finding the next best price after a level empties is a count of leading zeros, 64 levels at a time. Cancels find an order through a primitive hash map from order id to pool slot.

## 7. A full production setup

Here is a full production shard. Participants speak FIX. FIX order-entry adapters sit outside this project: they run the FIX sessions — sequence numbers, resends — and turn NewOrderSingle and cancels into SBE for a gateway. Gateways are stateless, so there can be several, each with its own endpoints. The gateway validates and authorises, then sends the order into the Aeron cluster: three machines running Raft, each with a consensus module, an archive on NVMe, and an identical engine. Only the leader talks to the outside world. The leader's market-data process turns book events into L1, L2 and L3 feeds on UDP multicast, where FIX market-data adapters pick them up and publish snapshots and incremental refreshes to participants. The control plane sits to one side: it sends operator commands through an operator gateway and watches the same feeds.

## 8. The life of one order

Follow one order. The participant's FIX engine sends a NewOrderSingle; the adapter translates it to SBE and passes it to the gateway. The gateway checks the security is on this shard, the participant is allowed on this gateway and may do this — any refusal happens here, before the log. The order then enters the cluster: Raft gives it a position in the log on a majority of nodes and the archive records it. Every node's engine applies it identically. The leader's execution report goes back the same way and arrives as a FIX ExecutionReport. In parallel the leader publishes a book event to market-data, which updates the L1, L2 and L3 feeds everyone sees. Most of the round-trip time is consensus and the wire — the exchange's own code is one to two percent of it.

## 9. Each phase is a step on a path

Each security walks the same path every day: closed, pre-open where orders are booked but nothing trades, an opening auction that publishes an indicative price, and then continuous trading, which begins with a single uncross at the price that maximises volume. Skipping straight to continuous would skip the auction, so the control plane always walks the whole path. If an execution would breach the dynamic collar, the book halts back to closed with its orders intact, and an operator reopens it through the auction.

## 10. What participants see: the order book

This is the central limit order book as a participant sees it — here the control plane's own view, rebuilt from the L2 feed. The engine never formats market data: it emits a raw stream of book events, and a separate market-data process derives three feeds — top of book, price levels, and every individual order — and publishes them once on multicast. Each event carries a per-shard sequence number, so a subscriber that misses a packet knows it and resynchronises from the snapshot feed. A slow subscriber falls behind on its own; it never slows the feed for anyone else.

## 11. The control plane decides; nodes boot from files

Operators run the exchange from the control plane. It authors reference data — securities, shards, participants, gateways — in Postgres and publishes it as files that each process boots from, so the database is never needed for a node to start. Its calendar walks each session through the day. The Status page shows what the exchange is actually doing, read from the L3 feed, and the Operations page sends session transitions, purges, snapshots and reopenings. Commands are not acknowledged, so the console records that they were sent and then watches the feed for the result.

## 12. Losing a machine loses no orders

Because every node is identical, losing one costs nothing: the survivors elect a new leader, which already holds the same books and simply starts talking. Gateways are stateless, so an adapter reconnects to another one. A full restart replays from the latest snapshot, and the engine republishes its books so market-data subscribers resynchronise. And a restart into a configuration that would lose an order refuses to start, loudly. That is also why the three core machines are run by systemd, not Kubernetes — their archive directory is the shard's resumption point.

## 13. Matching is fast; the shared path sets the ceiling

The engine itself is fast: under half a microsecond for a whole new order, with no allocation. The shard's throughput ceiling, though, is around 550 thousand orders a second across ten books with a dedicated media driver — the same aggregate one book reached, which shows the bottleneck is the path every order shares, not matching. That is below the 1 million per second target, and finding what binds there is the next piece of work. All of these are single-node figures on a development machine.

## 14. One thread, one log, every node the same.

To sum up: one thread per shard, one replicated log, and every node byte-identical. Consensus gives the nodes the same input and determinism gives the same output, so failover is free. The gateways and market-data processes hold no state of their own and can be replaced at will — the FIX adapters in front of them do keep session state, which is theirs to manage. Everything that isn't matching lives in its own process. The specification and the operator's manual are in the docs folder.
