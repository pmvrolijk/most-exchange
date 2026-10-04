# most-exchange — Inside the Code — speaker notes

Source: a private claude.ai Slides artifact (README.md).

The HTML export drops speaker notes, so they are kept here. This file is generated from
the artifact's slides: regenerate it after changing a slide, never edit it by hand.

## 1. Inside the code

The third deck in the series. The first explained the architecture, the second how it was built. This one opens the repository: the module layout, and then short extracts from the code where the specific design choices actually live — the schema, the order pool, matching, the gateway, the feeds — followed by the Docker stack, the end-to-end scripts and the build. Every extract is quoted from the source, sometimes condensed, and most carry the comment that explains why.

## 2. Nine modules, dependencies point one way

The repository is nine Gradle modules with dependencies that only point downward. At the bottom is sbe: the schema and the codecs generated from it, depending on nothing. Above it, client is the adapter SDK: the feed assembler every consumer uses, the order and status encoders, and the order-entry session an adapter runs on. It is published under Apache-2.0, so adapters in their own repository build on it. Then reference holds the exchange's own domain — the shard's security list, the participant registry and the encoders for operator commands — and builds on client, never the other way round. On top sit the processes: the four core ones that each build to a native binary, and the CLI and control plane for operators. Outside the Gradle build: the Vue console, the Docker stack, the end-to-end scripts and the docs.

## 3. The wire is a schema, not code

Every message on every boundary is defined once, in an SBE XML schema. The build runs the SBE tool to generate flyweight codecs that every process shares, with warnings treated as fatal, so nobody ever writes a byte offset by hand. The schema evolves by appending: when the engine started stating origQty and cumQty on execution reports, they were added as version 3 fields at fixed offsets, and the per-participant reportSeq that makes a resend possible came in version 6. Older messages and snapshots still decode, and an origQty of zero is carried through as "unknown" rather than a made-up number. The schema is at version 7 now, with forty messages.

## 4. Two flat structures, no objects

The order book has no order objects at all. Orders live in one pre-allocated LongArray, eight longs each — exactly one 64-byte cache line — addressed as orders[node * STRIDE + FIELD]. The next and previous links of the per-level queue are packed into a single long. Fields matching never reads, like the original quantity, go in a separate cold array so the hot line stays at 64 bytes. On the right, the price ladder's occupancy bitset: finding the best bid is a leading-zero count over 64-bit words, so 64 empty levels cost a single step.

## 5. Inline, collar first, then self-match

This is the heart of continuous matching, condensed. Three choices stand out. It is an inline function, so the fill and cancel callbacks are pasted into the caller — no lambda object, no boxing — which is what keeps matching allocation-free. Removing that keyword still compiles, and only the allocation test would notice, so it is one of five inline keywords the rules call load-bearing. The dynamic collar is checked per price level, against the reference taken once when the order arrived — never the live value the order's own fills are moving. Then self-match prevention is checked per resting order, before each fill. The queue at each level is walked from its head, which is time priority.

## 6. Kept on every node, then claimed

Everything the engine emits goes into Aeron's buffer through tryClaim, never through a scratch buffer that is then offered. Execution reports have one refinement. Each one is numbered per participant and written into a ring of recent reports first, on every node, whether that node is the leader or not and whether the participant is reachable or not. That is what lets a client ask for a resend after a failover and get the reports a dying leader never delivered. The leader then claims space on the session's publication and copies the report in — still a claim, still in place. The MOCKED_OFFER check comes first because Aeron returns one for a follower, and one would pass a greater-than-zero test. And the old trap is still here: a cluster session reserves its own header in front of the payload, so the copy starts after SESSION_HEADER_LENGTH. Book events follow the same rule for their sequence number: it is taken on every node, and only the leader publishes.

## 7. Reject early, never lose an order

The gateway's job is to refuse early and never lose an order. It polls its inbound subscription with controlledPoll: if the cluster is back-pressured, the handler returns ABORT and the order stays in the subscription to be offered again. The handled path returns COMMIT, never CONTINUE — CONTINUE only commits at the end of the whole poll, so a later ABORT would rewind and resend orders already forwarded. Authorisation comes first, so a participant this gateway may not act for learns nothing about the shard, then validation. Refusals happen here, before the log, because the participant registry is a node-local file: the engine only ever counts, it never rejects on it. A dead cluster session is not retryable, so the client gets GATEWAY_UNAVAILABLE.

## 8. Feeds drop, they never block

Market data is a separate process fed over shared memory: the engine publishes raw book events on an IPC stream, and never formats a feed itself. The market-data process derives L1, L2 and L3 and publishes each once, however many subscribers there are; L3 is the engine's own event forwarded byte for byte. The key line is the greater-or-equal-zero: a failed offer is simply a drop. There is no back-channel to a multicast group, and blocking would let the slowest reader throttle everyone. Aeron's max multicast flow control means the publisher runs at the fastest receiver's pace; a slow subscriber sees a sequence gap and resynchronises from the snapshot feed. The dev stack uses dynamic multi-destination unicast in place of multicast; in production the same properties name multicast groups.

## 9. One sequence number, read first

A subscriber that joins late, or takes a gap, rebuilds each book from the snapshot feed. The publishing side cycles through the securities one per slice, on the poll thread, so the image and the sequence stamped on it can never be torn apart. The important line is reading the L2 sequence number before the first level is written: the subscriber replays every update after that number on top of the image, and a number read afterwards would silently swallow the updates in between. On the consuming side, a synchronised book ignores snapshots, because installing an older image would rewind it — unless the image is at or ahead of everything applied, which is the case that rescues a subscriber that joined on a stale image. Every consumer shares this one assembler, which lives in the adapter SDK so the CLI and every adapter run the same code.

## 10. One shard, three networks

The Docker stack is drawn for production, not convenience. There are three networks: edge is the only one with published ports; aeron, the exchange's inside, and data, the control plane's database, are both internal. The shard's five processes share one media driver through a tmpfs volume, just as processes on one machine share /dev/shm. That driver lives in cluster-host, and the alias shard0 makes it the address of every UDP endpoint the shard exposes. The gateway has no durable volume, because it keeps no state. The control plane joins all three networks with its own media driver; a future FIX adapter would join edge and aeron and be the only bridge between them.

## 11. Seven scripts that run the real thing

Unit tests use fakes; these seven scripts start the real processes. run-e2e.sh starts every process, defines a security, opens the session, crosses two orders and asserts on what the CLI printed. It then asks for the participant's open orders and checks the partly filled sell comes back as leaves 6, cum 4 of 10, starts a book viewer after the depth exists so its book can only have come from the snapshot feed, and finishes with a load run. Each binary path is overridable, so the same script drives the native images, which must pass it unchanged. run-restart.sh is the only check that state survives a restart. run-cluster3.sh and run-failover.sh are the multi-node checks: three members on one machine, an election, failovers, a rejoin, and what a failover costs a client in each gateway placement. Their first run found a determinism defect that no single-node test could. The other three measure: attribution by stage, a throughput sweep, and a zero-allocation soak on a real cluster.

## 12. Gradle: strict, and native in one place

One Gradle build, with a Kotlin toolchain on JDK 21 and versions in a catalog. Two things in the root build file matter most. Warnings are errors in every module, because the zero-allocation profile depends on inline functions actually inlining — a silent warning there would put an allocation on the matching path. And the native-image flags live in one place for all four binaries, since they are a property of the Aeron and Agrona stack. Two of them fail silently when missing, and install-exit-handlers is what lets a native process shut down in order on SIGTERM. The target architecture is pinned from a property rather than native, and the Epsilon collector is a staged switch. GitLab CI runs build, test, verify and measure, and GitHub Actions runs the same jobs on the public mirror, plus the manual job that publishes the adapter SDK.

## 13. Plain arrays on the hot path. Reasons in the comments.

To close: the hot path is deliberately plain — flat arrays, a bitset, inline functions and zero-copy claims into Aeron's buffers. The edges around it follow a few firm rules: refuse early at the gateway, drop rather than block on the feeds, and recover from a sequence number rather than hope. And almost every surprising line in the code carries a comment saying why it is that way, often with the defect that taught it. The best place to start reading is OrderBook.kt, with section 3 of the design next to it.
