# 1. Introduction

## 1.1 What this system is

most-exchange is a matching engine for continuously traded, order-driven markets. It runs as one or
more **shards**; each shard hosts up to ten securities, holds an independent limit order book for
each of them, and is replicated across an odd number of machines by Aeron Cluster's Raft
implementation.

A shard is a **deterministic single-threaded state machine**. One thread consumes a replicated log
of commands and applies them in order; every node runs the identical code over the identical log and
must arrive at byte-identical state. Everything an operator can do to a running market is expressed
as a message in that log, and nothing an operator does is applied on one node and not another.

The design targets 100,000 orders per second per security across ten securities on a shard — a
one-microsecond aggregate budget per order. The engine's own share of that, measured on a
development machine, is 0.42 µs at the median for a whole message.

::: term Shard
The unit of replication and of scale. One shard is one Raft group, one replicated log, one matching
thread, and at most ten securities with fully independent books. There is no cross-instrument
matching, so capacity is added by running more shards, never by adding securities to one.
:::

::: term Determinism
The property that the same log produces the same state on every node. It is why wall-clock reads,
unbounded allocation and unvalidated array indexing are prohibited on the engine's path: a crash or
a divergence does not affect one node, it affects every node at the same log position
simultaneously.
:::

## 1.2 The processes

A shard is five processes plus a control plane. They are separate processes on purpose — the media
driver allocates memory and the engine must not, market data fan-out must never touch the matching
thread, and the gateway is restarted on a different schedule from the Raft group.

| Process | Binary | Role |
| --- | --- | --- |
| Cluster host | `most cluster` | Aeron media driver, archive and consensus module in one process. Owns the replicated log, and authenticates connecting gateways. |
| Engine | `engine` | The Aeron `ClusteredService` — books, matching, auctions, snapshots. Attaches to a consensus module that must already exist. |
| Gateway | `gateway` | Order entry. Validates `securityId`, forwards to the cluster, translates execution reports back out. Holds no per-order state. |
| Market data | `market-data` | Consumes the engine's book event stream and derives L1, L2 and L3 plus an L2 recovery snapshot feed. |
| Discovery | `discovery` | Broadcasts the tradable universe — symbol to shard to gateway endpoints — on a repeating cycle. |
| Control plane | `control` | Authors reference data in Postgres, publishes immutable release artifacts, drives operator commands, runs the trading calendar, and serves the admin UI. |

![The five shard processes, the control plane, and what flows between them.](assets/fig-dataflow.svg)

Only the control plane is authenticated, and the reason is worth stating plainly: it is the only
process that *decides* something. The other five apply a replicated log or forward bytes.

## 1.3 Two boundaries, both binary

Order entry and market data are both **SBE over Aeron**. There is no FIX anywhere in this project.
Protocol adapters that speak FIX or a proprietary session protocol sit upstream of the gateway and
downstream of market data, and are outside this codebase.

The wire schema is `sbe/src/main/resources/message-schema.xml`; codecs are generated from it and
byte offsets are never written by hand.

::: term SBE
Simple Binary Encoding — a fixed-layout binary message format with generated codecs. Messages are
encoded directly into the Aeron log buffer through `tryClaim`, so publishing a message copies
nothing.
:::

## 1.4 The two kinds of configuration

Everything an operator sets falls into exactly one of two categories, and confusing them is the
most common source of a shard that starts but is subtly wrong.

**Geometry and topology** — which securities exist, on which shard, with what tick size, price floor,
ladder size and order capacity; which gateway speaks for which participant; where the gateway's
client endpoints are. This is authored in the control plane, published as an immutable numbered
**release**, and read from a *file* at boot by every process that needs it. Every process prints a
`fingerprint()` of what it read at startup, and those fingerprints must agree.

**Node-local settings** — Aeron directories, cluster directories, feed channels, metrics switches,
thread and idle settings. These live in each process's own properties file and are templated per
host. They are deliberately not in the database and not in a release.

![A release is authored in Postgres, frozen into files, and read from files. The database is never on a boot path.](assets/fig-release.svg)

::: term Fingerprint
A short hash over a shard's published geometry, printed by the engine, gateway, market-data and
discovery processes at startup. Three nodes printing three different values is a divergence waiting
to happen on the first order, and consensus cannot catch it — this is the one misconfiguration Raft
will not save you from. The engine catches it instead. The leader writes its fingerprint into the log
at each term start, and a node that disagrees refuses to go on.
:::

::: term Release
An immutable, numbered directory of rendered configuration artifacts produced by
`POST /api/releases`. Republishing never overwrites; it allocates the next version. Deployments
point a `current` symlink at a release directory, and processes read through that symlink.
:::

## 1.5 Prices, quantities and identifiers

Prices and quantities are fixed-point signed 64-bit integers with **eight implied decimals**. There
is no floating point anywhere in pricing or matching arithmetic, and no operator-facing interface
should introduce one.

| Displayed | On the wire |
| --- | --- |
| `100.00` | `10000000000` |
| `0.01` (a tick) | `1000000` |
| `101.30` | `10130000000` |

`securityId` is a 32-bit integer and is the primary identity of an instrument everywhere on the
wire; symbols exist for humans and for routing lookups. `participantId` identifies the trading firm.
`exchangeOrderId` is allocated by the engine and is what a cancel refers to.

## 1.6 Session phases

Every security is in one of four phases. Orders are **accepted and booked in every phase except
`CLOSED`**; only *matching* is gated on `CONTINUOUS`. A crossed book outside continuous trading is
therefore normal, and is resolved by the uncrossing algorithm.

![The session lifecycle. Phases are walked, not jumped: the uncross runs on exactly one transition.](assets/fig-phases.svg)

The transition that matters is `OPEN_AUCTION → CONTINUOUS`, which runs the uncross. Sending
`CONTINUOUS` from any other phase is accepted and silently skips the auction, leaving a crossed
resting book crossed until an aggressor happens to arrive.

A **session transition is shard-wide**. There is no per-security session command, so moving one
security's phase moves every book on that shard.

## 1.7 What is not built

The manual marks unimplemented behaviour inline, in *todo* callouts, at the point where an operator
would otherwise expect it to work. The consolidated list is in 6.9.

## 1.8 How to read this manual

Section 2 brings the whole system up on one machine and is the fastest way to acquire the vocabulary
the rest of the manual uses. Section 3 is the production topology. Section 4 is the complete
configuration reference and is meant to be consulted rather than read. Section 5 is the daily
operational procedure — order flow, market data, the trading day, and what to measure. Section 6 is
diagnosis and maintenance. Section 7 indexes the terms and commands by section.

Command transcripts in this manual are real output from a running system, not illustrations.

Companion documents in the repository, which this manual summarises rather than replaces:
`docs/Design.md` is the authoritative specification, `docs/ControlPlane.md` covers the control plane
in depth, `docs/ProdDeployment.md` the production tier, `docs/LocalTesting.md` the manual
walkthrough, and `deploy/README.md` the development stack.
