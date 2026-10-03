# Production Deployment

**Status: starter document.** The topology and the reasoning are settled; several of the
configuration knobs it needs do not exist in the code yet. §11 lists them, and nothing here has
been run.

The shape is a **split**, and it is the same seam `deploy/docker-compose.yml` already draws between
the `aeron` network and everything else:

| Tier | Where | Why |
| --- | --- | --- |
| Cluster core — driver, archive, consensus, engine, gateway, market-data, discovery | **Three dedicated Linux x86-64 machines** | Latency is a property of the machine, and Raft already does the failover an orchestrator would duplicate |
| Control plane, Postgres, web SPA, FIX order-entry adapters | **Kubernetes** | Stateless-ish request/response work that benefits from rolling deploys, secrets, ingress and HPA |
| FIX market-data adapters | **Either, with a caveat** | They need multicast, which vanilla Kubernetes networking cannot carry. See §7.3 |

---

## 1. The rule that shapes everything: the shard is not orchestrated

`docs/ControlPlane.md` §1 says the database is not on the boot path. This document's equivalent is:
**nothing schedules a cluster node except an operator.**

Kubernetes' core value is noticing a pod is unhealthy and starting it somewhere else. For an Aeron
Raft group that reflex is wrong in three separate ways:

- **Failover is already handled, faster.** A node that dies is covered by the remaining two in
  milliseconds, by consensus, with no orchestrator involved. Anything scheduling on top can only add
  latency to a recovery that already happened.
- **A rescheduled node is not the same node.** The archive and cluster directories *are* the shard's
  resumption point. A pod restarted onto a fresh host has an empty archive and replays the log from
  genesis — which is precisely the cost `docs/Design.md` §1 says the snapshot exists to avoid.
- **A node cannot restart immediately anyway.** Aeron's archive and cluster mark files carry a
  liveness timestamp, and a new process refuses to start until it ages out — roughly ten seconds,
  even after a clean shutdown. A liveness probe with a shorter period than that will restart-loop a
  healthy node forever.

So the three core machines are managed with `systemd` and Ansible, and the control plane is the
thing that knows about trading state. Kubernetes runs the tier where a five-millisecond scheduling
hiccup is invisible.

---

## 2. Topology

```
                        ┌───────────────────────── Kubernetes ─────────────────────────┐
                        │                                                              │
                        │   web (nginx SPA)      control (+ media driver sidecar)      │
                        │   Deployment           Deployment, 1 replica                 │
                        │        │                     │                               │
                        │        └──── /api ───────────┤                               │
                        │                              │      fix-oe-adapter           │
                        │   postgres (CNPG or          │      Deployment, N replicas   │
                        │   managed)  ─────────────────┘             │                 │
                        └────────────────────────────────────────────┼─────────────────┘
                                                                     │
                        ══════════════ trading VLAN (L2, routed UDP) ═╪═══════════════════
                                                                     │
                    ┌────────────────────┐  ┌────────────────────┐   │
   order entry ───► │  gw-a              │  │  gw-b              │ ◄─┘  each on its own
   (partitioned     │  gateway           │  │  gateway           │      client endpoints,
    across them)    │   + media driver   │  │   + media driver   │      no state, any number
                    └─────────┬──────────┘  └─────────┬──────────┘
                              └───── cluster ingress ─┴──────────┐
                                                                 │
        ┌──────────────────────┐  ┌──────────────────────┐  ┌────┴─────────────────┐
        │  shard0-a  (member 0)│  │  shard0-b  (member 1)│  │  shard0-c (member 2) │
        │                      │  │                      │  │                      │
        │  cluster-host        │  │  cluster-host        │  │  cluster-host        │
        │   ├ media driver     │  │   ├ media driver     │  │   ├ media driver     │
        │   ├ archive  ──NVMe  │  │   ├ archive  ──NVMe  │  │   ├ archive  ──NVMe  │
        │   └ consensus module │◄─┼──┤ consensus module  │◄─┼──┤ consensus module  │
        │  engine (service)    │  │  engine (service)    │  │  engine (service)    │
        │  market-data         │  │  market-data         │  │  market-data         │
        │  discovery           │  │  discovery           │  │  discovery           │
        └──────────────────────┘  └──────────────────────┘  └──────────────────────┘
                 │                                                     │
                 └──────── L1/L2/L3 + snapshot, multicast ─────────────┘
                                      │
                          fix-md-adapter (bare metal, or k8s + Multus)
```

### 2.1 What runs on all three, and what does not

**Every node runs `cluster-host` and `engine`.** That is what a Raft group is.

**Every node runs `market-data`, and only one of them emits.** This falls out of the design rather
than needing coordination: the book event stream is `aeron:ipc` and is **leader-only**, muted
explicitly on `onRoleChange` because it is a plain publication and not egress. So a follower's
market-data process is subscribed to a silent stream and publishes nothing. On failover the new
leader's engine starts publishing and its co-located market-data picks up. No standby logic to
write, and no cross-machine hop for a stream that was deliberately made shared-memory.

**`gateway` is not on these machines at all, and is not active/standby.** It was, and that was the
weakest part of the design: one gateway per shard could be active, because its memory-mapped journal
was the only place `origQty` lived, so a standby on another machine had no copy and a failover
marked every in-flight order `UNKNOWN`. The engine holds `origQty` now (Design.md §3.1) and states
both quantities on every execution report, so a gateway holds no state. Two things follow:

- **Co-locating a gateway with a cluster node buys nothing.** An `AeronCluster` client lists every
  member and follows the leader, so a gateway on its own machine reaches the leader exactly as one
  sitting beside it does. It is its own tier, sized and restarted independently of the Raft group.
- **What must be disjoint is endpoints, not state.** `gateway.client.inbound.channel` is a single
  address; two gateways subscribed to *one* would each receive every order and forward both, which
  is duplicate orders rather than redundancy. So each gateway gets its own client endpoints and
  adapters are partitioned across them. Losing one costs its adapters a reconnect and nothing else —
  a replacement, or a peer, reports correctly on orders it never saw. `e2e/run-restart.sh` §4d is
  the check: an order placed through one gateway, cancelled through another, reporting the right
  `cumQty`.

The gap that remains is advertisement, not correctness: `ShardEntry` carries **one** order-entry
channel per shard, so the directory cannot name several gateways for one shard. A virtual address in
front of the tier is the interim answer. See §11.

**`discovery` may run on all three.** It is a stateless repeating broadcast and a duplicate cycle is
harmless — each cycle is staged and replaces the table wholesale.

---

## 3. Machine preparation

The three machines should be identical, and **must** be identical in geometry — `ShardSpec.fingerprint()`
and `EngineConfig.fingerprint()` exist because that is the one misconfiguration consensus cannot
catch (`docs/Design.md` §7).

### 3.1 Hardware

| | Recommendation | Why |
| --- | --- | --- |
| CPU | 16+ physical cores, x86-64-v3 or better, one socket | ~12 threads need cores; one socket avoids NUMA on the hot path |
| RAM | 64 GB minimum | ~0.9 GB/shard of pools, plus Aeron log buffers, plus page cache for the archive |
| Storage | NVMe SSD, dedicated to the archive | The archive writes every replicated message; it is the single largest contributor to round-trip latency |
| Network | Dual 10 GbE, one for the trading VLAN | Multicast market data and consensus should not share a link with management traffic |

Single socket matters more than it looks. The engine is one thread whose working set is the order
pool and the ladders; a cross-socket memory access on that path costs more than everything the
matching logic does.

### 3.2 Kernel and boot

```
# /etc/default/grub — GRUB_CMDLINE_LINUX
isolcpus=2-13 nohz_full=2-13 rcu_nocbs=2-13
hugepagesz=1G hugepages=8 default_hugepagesz=1G
intel_idle.max_cstate=0 processor.max_cstate=1 idle=poll
mce=ignore_ce
```

Huge pages are **mandatory, not optional** (`docs/Design.md` §2). A 0.9 GB order pool on 4 KB pages
is ~230,000 TLB entries for a structure that was cache-line packed specifically to cost one miss per
order; the TLB miss would undo the whole design.

`idle=poll` and the C-state pinning trade power for the absence of wake-up latency. On a machine
that exists to run one exchange, that is the right trade — but it is also why these machines should
not run anything else.

```sh
# tuned profile, applied on top
tuned-adm profile latency-performance

# NIC: disable interrupt coalescing on the trading interface, pin IRQs to housekeeping cores
ethtool -C ens1f0 rx-usecs 0 rx-frames 1
ethtool -G ens1f0 rx 4096 tx 4096
```

### 3.3 Core allocation

Cores 0–1 are housekeeping (kernel, IRQs, ssh, node_exporter). Cores 2–13 are isolated and assigned:

| Cores | Thread |
| --- | --- |
| 2, 3, 4 | Media driver conductor, sender, receiver (`ThreadingMode.DEDICATED`) |
| 5, 6 | Archive conductor, recorder (`ArchiveThreadingMode.DEDICATED`) |
| 7 | Consensus module |
| 8 | **Engine service container** — the matching thread |
| 9, 10 | Gateway, and its Aeron client conductor |
| 11, 12 | Market-data, and its client conductor |
| 13 | Discovery |

Core 8 is the one that matters. Everything else is plumbing around it.

**On idle strategies:** with dedicated cores, `BusySpinIdleStrategy` is correct for the driver
threads, the consensus module and the service container. Without them it is actively harmful — four
spinning threads on four cores starve the kernel's own softirq processing, which is what the e2e run
on a shared 4-core box demonstrated. Do not copy production idle strategies onto a development
machine.

### 3.4 Aeron buffer sizing

Defaults are sized for throughput, not for a fixed memory budget. On 64 GB the UDP defaults are
fine; the IPC default is worth a look, because a 64 MB IPC term means a ~192 MB log buffer for the
book event stream alone.

```properties
aeron.term.buffer.length=16m
aeron.ipc.term.buffer.length=16m
aeron.socket.so_sndbuf=2m
aeron.socket.so_rcvbuf=2m
aeron.rcv.initial.window.length=2m
```

An Aeron driver that cannot map a buffer does not fail politely — it takes SIGBUS on the next page
touch and reports `InternalError: a fault occurred in an unsafe memory access operation`, which
reads like a JVM bug rather than a sizing problem. The `client-aeron` comment in
`deploy/docker-compose.yml` records the same lesson from the dev stack.

---

## 4. Cluster configuration

### 4.1 Member string

Aeron's format is `memberId,ingress,consensus,log,catchup,archiveControl`, members separated by `|`.
The ports below are `ClusterCommand`'s existing defaults, which are fine to keep — the three members
are on different machines, so they do not need to differ.

```
0,shard0-a:20110,shard0-a:20220,shard0-a:20330,shard0-a:20440,shard0-a:8010|\
1,shard0-b:20110,shard0-b:20220,shard0-b:20330,shard0-b:20440,shard0-b:8010|\
2,shard0-c:20110,shard0-c:20220,shard0-c:20330,shard0-c:20440,shard0-c:8010
```

Pass it identically to all three with `most cluster --members`, varying only the member id.

> **Blocker.** `ClusterCommand.runClusterHost` hardcodes `.clusterMemberId(0)` and takes no
> `--member-id` argument, so today all three nodes would claim to be member 0. It also hardcodes
> `ThreadingMode.SHARED` and `ArchiveThreadingMode.SHARED`, which is right for a laptop and wrong
> for §3.3. See §11.

### 4.2 Port map

Everything the shard exposes, in one place. Every one of these is bound by the **media driver** on
its own machine — the same fact the compose file encodes by giving the driver's container the
`shard0` alias.

| Port | Bound by | Purpose |
| --- | --- | --- |
| 20110 | consensus | Cluster ingress — gateway and control connect here |
| 20220 | consensus | Consensus (member-to-member) |
| 20330 | consensus | Log replication |
| 20440 | consensus | Catchup |
| 8010 | archive | Archive control |
| 20001 | gateway | **Client inbound** — adapters publish orders here |
| 20002 | gateway | **Client outbound** control address — execution reports |
| 40000 | discovery | Directory broadcast |
| 40001 / 40002 / 40003 | market-data | L1 / L2 / L3 |
| 40004 | market-data | L2 recovery snapshot feed |

20110, 20001 and 20002 are the only ports anything outside the trading VLAN needs to reach. The rest
should be firewalled to the three machines and the market-data subscriber group.

### 4.3 Gateway ingress

The gateway lists every member so its cluster client can find the leader:

```properties
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=shard0-a:20110,1=shard0-b:20110,2=shard0-c:20110
```

This is what the comment in `deploy/config/gateway.properties` anticipates when it says "One member
here; production lists three or five."

### 4.4 The gateway needs no storage

There is nothing to configure here, and that is the point. A gateway keeps no per-order state: the
engine holds `origQty` and states it, with `cumQty`, on every execution report (Design.md §3.1). It
used to want a memory-mapped journal on the NVMe — `gateway.journalFile`, sized to the shard's own
order pool — and those keys no longer exist. If you are migrating a configuration that sets them,
delete them; the gateway rejects nothing but they do nothing.

The registry is the one file it reads, and it reads it once:

```properties
gateway.participantRegistry=/etc/most/current/shard-0-participants.properties
gateway.gatewayId=gw-a
gateway.credentialTokenFile=/etc/most/gw-a.secret
```

`current` is a symlink to a published release directory. The consensus module and the engine re-read
the same path while they run (§9.5), so a change to who speaks for whom costs a gateway restart and
no node restart.

---

## 5. Market data: multicast, at last

The dev stack uses dynamic MDC because a Docker bridge does not route multicast. On a real VLAN,
switch to what the design actually specifies:

```properties
# market-data.properties, production
md.l1.channel=aeron:udp?endpoint=239.10.0.1:40001|interface=10.20.0.0/24
md.l1.streamId=1
md.l2.channel=aeron:udp?endpoint=239.10.0.2:40002|interface=10.20.0.0/24
md.l2.streamId=2
md.l3.channel=aeron:udp?endpoint=239.10.0.3:40003|interface=10.20.0.0/24
md.l3.streamId=3
md.snapshot.channel=aeron:udp?endpoint=239.10.0.4:40004|interface=10.20.0.0/24
md.snapshot.streamId=4
md.snapshot.cycleMs=1000
```

The `interface=` qualifier is not optional on a multi-homed machine — without it Aeron picks an
interface and it may not be the trading VLAN.

**Configure IGMP snooping on the switches, and an IGMP querier on the VLAN.** Without a querier,
snooping switches age out group membership and the feed silently stops reaching subscribers, usually
minutes after everything looked fine.

**Do not change Aeron's flow control.** It defaults to `MaxMulticastFlowControl` — the fastest
receiver governs — and that is correct here. `MinMulticastFlowControl` would let the slowest
subscriber throttle the publisher, reintroducing exactly the coupling that moving market data out of
the engine was meant to remove. The trade-off is real and accepted: slow subscribers take
unrecoverable gaps, and gap detection plus snapshot resynchronisation are subscriber
responsibilities (`FeedSequenceTracker` and `DepthFeedAssembler` in `reference`).

---

## 6. Process management

One `systemd` unit per process. The engine's is the template; the rest differ only in `ExecStart`
and `CPUAffinity`.

```ini
# /etc/systemd/system/most-engine.service
[Unit]
Description=most-exchange matching engine (shard 0)
After=most-cluster-host.service
Requires=most-cluster-host.service

[Service]
Type=simple
User=most
ExecStart=/opt/most/bin/engine /etc/most/engine.properties
CPUAffinity=8
LimitMEMLOCK=infinity
LimitNOFILE=1048576

# SIGTERM, and time for it. The whole shutdown path -- snapshot, counters, service container
# close -- hangs off an orderly SIGTERM reaching ShutdownSignalBarrier. SIGKILL leaves the mark
# file live and the next start refuses for ten seconds.
KillSignal=SIGTERM
TimeoutStopSec=60
Restart=on-failure
RestartSec=15s

[Install]
WantedBy=multi-user.target
```

`RestartSec=15s` is not arbitrary: it is longer than the ~10 second mark-file liveness timeout, so
an automatic restart does not fail on "active mark file detected" and then back off.

For the native binaries, `--install-exit-handlers` is load-bearing and is already set in the root
`build.gradle.kts`. Without it SIGTERM kills a native image outright, `ShutdownSignalBarrier` never
releases, and every shutdown counter — the gateway's `droppedToClient`, market-data's `gaps`,
`pendingOrders` — is lost. `e2e/run-e2e.sh` only checks that nothing died *during* a run, so it
passed throughout the period when this was broken.

### 6.1 Configuration management

Ansible, with two kinds of file that must not be confused:

- **Released artifacts** — `shard-0-securities.properties` and the discovery registry — are
  published by the control plane as immutable numbered releases. Ansible fetches a release and
  drops it; it never authors one.
- **Node-local config** — `engine.properties`, `gateway.properties`, Aeron directories, cluster
  directories, feed channels — lives in the repo and is templated per host.

That line is `docs/ControlPlane.md` §1 restated as a deployment practice: releases carry topology,
and each process's own config carries everything about the machine it is on.

---

## 7. The Kubernetes tier

### 7.1 What goes there

| Workload | Kind | Notes |
| --- | --- | --- |
| `postgres` | CNPG cluster or managed service | Off the boot path by design, so an outage cannot stop a node starting |
| `control` | Deployment, **1 replica** | Holds the calendar and the L3 subscription; two replicas would double every operator command |
| `web` | Deployment + Ingress | Must be same-origin with `/api` — the session and CSRF cookies are same-origin mechanisms |
| `fix-oe-adapter` | Deployment, N replicas | Order entry. Needs no multicast — see §7.3 |
| `fix-md-adapter` | Deployment + Multus, **or bare metal** | Needs multicast — see §7.3 |

`control` at one replica is a real constraint, not caution. It drives the trading calendar, and its
tick **reconciles** the phase the calendar wants against the phase L3 reports. Two replicas would
each emit `SessionTransition` commands, and since the difference between phases is a path rather
than a destination, two schedulers walking it concurrently could interleave into a sequence that
skips the uncross.

### 7.2 The media driver sidecar

Every k8s workload that speaks Aeron needs its own media driver. This maps cleanly onto a pod:

```yaml
spec:
  containers:
    - name: control
      image: most/control:VERSION
      env:
        - { name: CONTROL_AERON_DIR, value: /aeron/driver }
      volumeMounts:
        - { name: aeron, mountPath: /aeron }
    - name: media-driver
      image: most/tools:VERSION
      command: ["java", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "-Daeron.dir=/aeron/driver", "-Daeron.dir.delete.on.start=true",
                "-cp", "/opt/most/tools/lib/*", "io.aeron.driver.MediaDriver"]
      volumeMounts:
        - { name: aeron, mountPath: /aeron }
  volumes:
    - name: aeron
      emptyDir: { medium: Memory, sizeLimit: 1Gi }
```

`emptyDir: {medium: Memory}` is the pod-scoped equivalent of the `shard-aeron` tmpfs volume — same
idea, same reason, and the same rule applies: this is the *client side's* driver and must never be
the shard's.

The `--add-opens` flags are required on any JVM running this code (Agrona 2.x buffer intrinsics);
without them the first `UnsafeBuffer` throws `IllegalAccessError`.

### 7.3 The multicast question, and why order entry escapes it

This is the one place the tier split imposes a real cost, and it is worth being precise about who
is affected.

**Order entry needs no multicast.** An adapter publishes orders to `gateway.client.inbound`, a
plain unicast endpoint, and subscribes to execution reports over **dynamic MDC** — the publisher
adds a destination per subscriber, all unicast. Both work over ordinary routed IP. So
`fix-oe-adapter` and `control`'s command path run on vanilla Kubernetes with no CNI heroics.

**Market data does.** L1/L2/L3 and the snapshot feed are multicast in production, and no standard
CNI carries multicast — not Calico, not Cilium, not flannel, not the AWS VPC CNI. It is absent from
the datapath, not a flag. Three options:

1. **Multus + macvlan** (recommended). Give the market-data-consuming pods a second interface
   directly on the trading VLAN. Works, is what telco NFV workloads do, and keeps one mechanism
   everywhere. Cost: those pods need a `nodeSelector` for hosts wired to that VLAN, which means
   they are no longer freely schedulable — Kubernetes with a bare-metal-shaped constraint.
2. **Run market-data consumers on bare metal.** Put `fix-md-adapter` on the three core machines or
   on a fourth. Simplest, lowest latency, and the FIX market-data tier is exactly the workload that
   benefits from being close to the feed anyway.
3. **Keep dynamic MDC for market data.** Works anywhere with no special networking. Cost: the
   publisher does one send per subscriber, so fan-out becomes O(n) work on the market-data process.
   Fine at 5–20 subscribers, wrong at 200.

**`control` is affected too** — it subscribes to L1, L2, L3 and the snapshot feed to draw the
console and to detect halts. So whichever option is chosen, control needs it as well.

A caveat on mixing them: a feed is configured as **one channel**. `market-data` cannot currently
publish L2 as both multicast and dynamic MDC, so "multicast for the fast path, MDC for control" is
not available without a code change (§11). Pick one mechanism per feed.

---

## 8. Bringing it up

Order matters, and most of it is the mark-file timeout in disguise.

```sh
# 1. All three cluster hosts. Within a few seconds of each other -- a member that starts
#    minutes late will catch up, but the group will not form until two are present.
systemctl start most-cluster-host    # on shard0-a, -b, -c

# 2. Wait for a leader. Not optional: the engine's service container attaches to a
#    consensus module that must already exist.
journalctl -u most-cluster-host -f | grep -m1 'Leader'

# 3. Engine service containers, all three.
systemctl start most-engine

# 4. The rest, on their assigned nodes.
systemctl start most-market-data     # all three; followers stay silent
systemctl start most-discovery       # all three is safe: a duplicate cycle is harmless

# 5. Gateways, on their own machines. Order does not matter and neither does how many:
#    they hold no state and each has its own client endpoints.
systemctl start most-gateway

# 6. Control plane last -- it is a client of everything above.
kubectl -n most rollout status deploy/control
```

**A cold cluster has emitted no `SessionChanged`, so its phase is unknown.** The scheduler waits
rather than assuming `CLOSED`, because a control-plane restart mid-session looks identical from the
outside and assuming would shut a live market. One session command establishes the baseline — issue
it deliberately as the last step of bringing a cold shard up.

### 8.1 Verifying geometry before trusting anything

Every process prints `ShardSpec.fingerprint()` at startup. Three nodes printing three values is a
divergence waiting to happen on the first order, and Raft will not catch it.

```sh
ansible shard0 -a "journalctl -u most-engine -n 200 --no-pager" | grep -i fingerprint | sort -u
```

One distinct value, or stop and fix it.

---

## 9. Operating the shard

### 9.1 Snapshots

**Nothing takes a snapshot unless something asks.** Without one, a restart replays the log from
genesis. Three things ask:

- `most cluster snapshot --ingress … --identity ID --secret-file F` — through consensus, so every
  member snapshots. This is the one to use in production. The identity must be an `operator=true`
  entry of the shard's registry: the consensus module grants a snapshot request to nothing else
  (Aeron's own default grants none at all), and the command prints the cluster's **answer**. The
  control plane and its scheduler present `control.cluster.identity.<shard>` the same way.
- `most cluster snapshot --dir` — the local control toggle, one member only.
- `most cluster shutdown` — snapshot then stop, which plain SIGTERM does *not* do.
- The control plane's scheduler, at each session close.

Leave the scheduler enabled in production. A daily snapshot at session close is what keeps restart
time bounded.

### 9.2 Restarting a node

```sh
most cluster shutdown --ingress          # or let the scheduler's close snapshot stand
systemctl stop most-engine most-cluster-host
sleep 15                                 # mark-file liveness; ~10s, 15 for margin
systemctl start most-cluster-host most-engine
journalctl -u most-engine | grep 'restored'
```

That last line is the check. The engine prints `restored N resting orders ... from a snapshot`
specifically so that a real recovery is distinguishable from a node that found no snapshot and
replayed its whole log, which rebuilds the same books by a completely different route and takes as
long as the session is old. `e2e/run-restart.sh` was originally written asserting
on the rendered book and passed for exactly that wrong reason.

**Never restart only the service container.** If you stop `most-engine` and leave `most-cluster-host`
running, the consensus module sees the engine's Aeron client close and terminates the cluster, which
closes every gateway session with it. A `most-engine` started afterwards is told "expected
termination" and exits. Restart a node's two units together, `most-cluster-host` first (Rationale §5,
measured on Aeron 1.53).

### 9.3 Changing geometry

A geometry change is reapplied by restarting, and `loadSnapshot` is where it is made safe. It
reconciles the snapshot against the booted `ShardSpec` and **refuses to start** rather than lose
state: a security gone from the shard while holding resting orders, a changed
`priceFloor`/`tickSize`/`levelCount`/`maxOrders`, an order outside the new ladder, or counts that do
not add up. The two legitimate cases are quiet — an empty book leaving the shard, and a new security
joining — each logged with a line.

So the procedure is: publish the release, drain or confirm the affected books are empty, then
restart. A refusal is the system working.

### 9.4 Changing who speaks for whom

A participant list change restarts nothing. Publish the release, move the symlink, and the
consensus module, the engine **and the gateways** pick it up within their poll interval. The gateway
is where the registry is enforced (Design.md §1), and it prints its own new grants.

```sh
# 1. Publish from the control plane, then point `current` at it on every machine.
ansible shard0,gateways -a "ln -sfn /etc/most/releases/000042 /etc/most/current"

# 2. Watch the node processes adopt it.
journalctl -u most-engine -f | grep -m1 'registry: reloaded'

# 3. And each gateway -- this line is when a revocation is in force.
journalctl -u most-gateway -f | grep -m1 'gateway: now'
```

To revoke a participant, move it to `cancelOnly` first (it can withdraw its resting orders but not
place), and remove it in a later release once its orders are gone.

A registry that cannot be parsed, or one for another shard, is reported and **ignored** — the one in
force keeps authenticating. That is deliberate: standing down on a bad file would turn a typo into a
shard that authenticates nobody. Check `registryReloadFailures` rather than assuming a silent
success. `--participants-reload-ms 0` and `engine.participantRegistry.reloadMs=0` turn the poll off
if a deployment would rather restart.

### 9.5 What to alarm on

| Signal | Where | Meaning |
| --- | --- | --- |
| `droppedToClient` | gateway | Undeliverable execution reports. The outbound leg drops and counts by design; a rising rate means a subscriber is gone |
| `untrackedReports` | gateway | Reports the engine could not state an `origQty` for. Only orders restored from a pre-v3 snapshot should produce these; anything else is a version mismatch |
| `gaps` | market-data | Feed sequence gaps. Expected occasionally under `MaxMulticastFlowControl`; sustained means a subscriber cannot keep up |
| `rejectedDefinitions` | engine | A `SecurityDefinition` was refused. Operator commands are unacknowledged, so this counter is the only signal |
| `registryReloadFailures` | engine, cluster-host | A participant registry that could not be read or was for another shard. The one in force still applies, so this is a quiet wrong rather than an outage |
| `rejectedSessions` | cluster-host, at shutdown | Sessions refused at connect — a wrong secret, or no credentials on a node with a registry |
| `unauthorizedRejects` | gateway | Orders and cancels refused `UNAUTHORIZED_PARTICIPANT`: someone reached this gateway as a participant it does not serve |
| `refusedCommands` | gateway | Operator commands consumed because this gateway is not an operator. With markets not moving, the control plane is pointed at the wrong gateway |
| `undeclaredParticipantMessages` | engine | Orders and cancels from a gateway that does not list the participant — a gateway not enforcing the registry the engine holds |
| Leader changes | consensus | Any unexplained one is worth a look |

`operator_audit` in the control plane records who asked for every market-moving command. Like the
REST layer, it records **`sent`, not `applied`** — the engine acknowledges nothing, so sent is all
that can be claimed.

---

## 10. What this buys, measured

Re-run `e2e/run-attribution.sh` on the real topology and diff the `.hgrm` files against the
development baseline. The number to watch is not the round trip — it is the *share*: the gateway and
engine owned 0.8 µs of a 55 µs round trip (1.4%) on a development machine. Consensus over a real
network with three members and an NVMe archive will change the denominator substantially, in both
directions: real network hops add, dedicated cores and busy-spin idle strategies subtract.

**Always read both latencies from `most load`.** Service time is measured from the actual send,
response time from the scheduled send; quoting only the first is coordinated omission and hides
exactly the queueing that appears at the rate you are trying to find. **Check `pacing lateness`
first** — if it is not small, the generator was the bottleneck and the run says nothing about the
exchange. Run the load generator on a fourth machine, never on a cluster node.

---

## 11. Gaps — what must be built before any of this runs

Honest list. None of these are large; all of them are blocking.

1. **`most cluster` cannot be a member other than 0.** `ClusterCommand.runClusterHost` hardcodes
   `.clusterMemberId(0)` and exposes no `--member-id`. Blocking for §4.1.
2. **Threading modes are hardcoded `SHARED`.** The driver and archive both. Correct for a laptop,
   wrong for §3.3; needs `--threading-mode` or config, defaulting to today's behaviour.
3. **Idle strategies are not configurable.** Busy-spin is right on isolated cores and harmful
   without them, so this has to be a knob rather than a constant.
4. **`engine.march` is x86-only.** `gradle.properties` documents `x86-64-v3`; the build passes it
   straight to `native-image`. Fine for this deployment, but it means the property cannot be set
   globally once a second architecture exists.
5. **The directory advertises one gateway per shard.** Gateway HA itself is solved — the gateways
   hold no state, run in their own tier, and `e2e/run-restart.sh` §4d places an order through one
   and cancels it through another — but `ShardEntry` carries a single order-entry channel and
   `DirectoryClient` keeps one `ShardRoute` per shard, so several gateways for one shard cannot all
   be advertised. A virtual address in front of the tier works today; naming them individually is a
   wire change.
6. **A feed is one channel.** `market-data` cannot publish the same feed as both multicast and
   dynamic MDC, which is what forces the single choice in §7.3.
7. **No health endpoint on the core processes.** Everything above alarms on log lines and
   shutdown-time counters. A scrape endpoint per process would make §9.4 mechanisable.
8. **Nothing here has been run.** The port map, the member string and the systemd units are derived
   from the code and the dev stack, not from a working deployment.

---

## See also

- `docs/Design.md` — the authoritative specification. §1 determinism, §2 the performance budget and
  memory footprint, §5 the wire and the feeds, §7 native build and operator commands.
- `docs/ControlPlane.md` — the control plane, its schema and releases; §4 covers authentication.
- `docs/LocalTesting.md` — the manual walkthrough; §9 is the benchmarking procedure.
- `deploy/README.md` — the Docker dev stack, and where several of the network decisions here were
  first worked out.
