# 3. Production deployment

## 3.1 The rule that shapes everything

**A shard is not orchestrated.** The three cluster nodes are long-lived machines with stable
hostnames, isolated cores, huge pages and a dedicated NVMe device for the archive. They are not
pods, they are not autoscaled, and they are not rescheduled. Everything with no realtime requirement
— the control plane, its database, the admin UI, protocol adapters — goes to an ordinary
orchestrated tier.

That line is not a preference. The engine is a single thread whose working set is a cache-line
packed order pool; a rescheduled workload, a shared core or a cross-socket memory access costs more
than everything the matching logic does.

::: warning Nothing in this section has been run end to end
The port map, member string and unit files below are derived from the code and from the development
stack, not from a working production deployment. 3.10 lists the specific code changes that are
blocking, and every one of them is small.
:::

## 3.2 Topology

![The production topology: three cluster nodes on bare metal, a separate stateless gateway tier, and everything without a realtime requirement in Kubernetes.](assets/fig-prod-topology.svg)

### What runs where

**Every node runs `cluster-host` and `engine`.** That is what a Raft group is.

**Every node runs `market-data`, and only one of them emits.** This falls out of the design rather
than needing coordination: the book event stream is `aeron:ipc` and is leader-only, muted explicitly
on role change because it is a plain publication and not cluster egress. A follower's market-data
process is subscribed to a silent stream and publishes nothing. On failover the new leader's engine
starts publishing and its co-located market-data picks up. There is no standby logic and no
cross-machine hop for a stream that was deliberately made shared-memory.

**Every node may run `discovery`.** It is a stateless repeating broadcast and a duplicate cycle is
harmless — each cycle is staged and replaces the routing table wholesale, so a truncated cycle never
replaces a good table.

**`gateway` is not on these machines and is not active/standby.** A gateway holds no per-order state,
so co-locating one with a cluster node buys nothing: an `AeronCluster` client lists every member and
follows the leader whether it sits beside it or a rack away. It is its own tier, sized and restarted
independently of the Raft group.

::: warning Gateways must have disjoint client endpoints
`gateway.client.inbound.channel` is a single address. Two gateways subscribed to **one** inbound
channel each receive every order and forward both — that is duplicate orders, not redundancy. Give
each gateway its own client endpoints and partition adapters across them. Losing one costs its
adapters a reconnect and nothing else; a peer reports correctly on orders it never saw.
:::

::: todo The directory advertises one gateway per shard
`ShardEntry` carries a single order-entry channel and `DirectoryClient` keeps one route per shard, so
several gateways for one shard cannot all be advertised. A virtual address in front of the gateway
tier is the interim answer; naming them individually is a wire change.
:::

## 3.3 Machine preparation

### Hardware

| | Recommendation | Why |
| --- | --- | --- |
| CPU | 16+ physical cores, x86-64-v3 or better, **one socket** | ~12 threads need cores; one socket avoids NUMA on the hot path |
| RAM | 64 GB minimum | ~0.9 GB per shard of pools, plus Aeron log buffers, plus page cache for the archive |
| Storage | NVMe SSD dedicated to the archive | The archive writes every replicated message and is the single largest contributor to round-trip latency |
| Network | Dual 10 GbE, one dedicated to the trading VLAN | Multicast market data and consensus must not share a link with management traffic |

Single socket matters more than it looks. A cross-socket memory access on the matching path costs
more than the matching itself.

### Kernel and boot

```
# /etc/default/grub — GRUB_CMDLINE_LINUX
isolcpus=2-13 nohz_full=2-13 rcu_nocbs=2-13
hugepagesz=1G hugepages=8 default_hugepagesz=1G
intel_idle.max_cstate=0 processor.max_cstate=1 idle=poll
mce=ignore_ce
```

::: warning Huge pages are mandatory, not optional
A 0.9 GB order pool on 4 KB pages is roughly 230,000 TLB entries, for a structure that was
cache-line packed specifically to cost one cache miss per order. The TLB miss undoes the entire
design.
:::

`idle=poll` and the C-state pinning trade power for the absence of wake-up latency. On a machine
whose only job is one exchange that is the right trade — and it is also why these machines should
run nothing else.

```sh
tuned-adm profile latency-performance

# NIC: no interrupt coalescing on the trading interface, IRQs pinned to housekeeping cores
ethtool -C ens1f0 rx-usecs 0 rx-frames 1
ethtool -G ens1f0 rx 4096 tx 4096
```

## 3.4 Core allocation

Cores 0–1 are housekeeping — kernel, IRQs, ssh, metrics agents. Cores 2–13 are isolated.

| Cores | Thread |
| --- | --- |
| 2, 3, 4 | Media driver conductor, sender, receiver (`ThreadingMode.DEDICATED`) |
| 5, 6 | Archive conductor, recorder (`ArchiveThreadingMode.DEDICATED`) |
| 7 | Consensus module |
| **8** | **Engine service container — the matching thread** |
| 9, 10 | Gateway, and its Aeron client conductor |
| 11, 12 | Market-data, and its client conductor |
| 13 | Discovery |

Core 8 is the one that matters. Everything else is plumbing around it.

::: warning Idle strategies are not portable between machines
With dedicated cores, `BusySpinIdleStrategy` is correct for the driver threads, the consensus module
and the service container. **Without** isolated cores it is actively harmful — spinning threads
starve the kernel's own softirq processing. Never copy production idle strategies onto a development
machine.
:::

The two driver-side modes in that table are the ones you must ask for. `most cluster` defaults to
`SHARED` for both, which is right for a laptop and wrong here:

```sh
most cluster --members "$MEMBERS" --host shard0-a \
     --driver-threading DEDICATED --archive-threading DEDICATED \
     --participants /etc/most/current/participants-shard0.properties
```

**`--driver-threading DEDICATED` is worth 1.6x of throughput** and 81x of median latency at the edge
(4.8). `--archive-threading DEDICATED` earns its place only *with* it and only with the cores above
isolated — on shared cores it is measurably worse than leaving it alone.

Idle strategies are configuration. The engine, gateway and market-data each take one —
`engine.idleStrategy`, `gateway.idleStrategy`, `md.idleStrategy` (4.4–4.6) — and **all three default to
`busyspin`**, which is right for the layout above and needs nothing set. The cluster host's threads use
Aeron's own settings, which back off by default; on isolated cores, make them spin with Aeron's system
properties in the cluster host's `JAVA_OPTS`:

```sh
JAVA_OPTS="-Daeron.conductor.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
           -Daeron.sender.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
           -Daeron.receiver.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
           -Daeron.archive.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
           -Daeron.archive.recorder.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy \
           -Daeron.cluster.idle.strategy=org.agrona.concurrent.BusySpinIdleStrategy"
```

On a machine **without** a core per thread, set `backoff` on anything that is mostly idle. A spinning
loop at 13% of its real work still takes 100% of a core, and on a development laptop that core was
the difference between keeping up and not (5.8).

::: note This also means CPU% tells you very little about these processes
Because the engine, gateway and market-data busy-spin, each reads ~100% of a core whether it is
working or idling. `top` cannot tell you whether one of them is the constraint. **Their duty-cycle
counters can** — the share of its core each thread actually spends working, live (5.8).
:::

## 3.5 Aeron buffer sizing

Aeron's defaults are sized for throughput, not for a fixed memory budget. On 64 GB the UDP defaults
are fine; the IPC default deserves attention, because a 64 MB IPC term means roughly a 192 MB log
buffer for the book event stream alone.

```properties
aeron.term.buffer.length=16m
aeron.ipc.term.buffer.length=16m
aeron.socket.so_sndbuf=2m
aeron.socket.so_rcvbuf=2m
aeron.rcv.initial.window.length=2m
```

::: warning A driver that cannot map a buffer does not fail politely
It takes SIGBUS on the next page touch and reports
`InternalError: a fault occurred in an unsafe memory access operation`, which reads like a JVM bug
rather than a sizing problem. Size the shared-memory mount for the number of subscriptions the
process will hold: every subscription image is a log buffer of three terms.
:::

## 3.6 Cluster configuration

### Member string

Aeron's format is `memberId,ingress,consensus,log,catchup,archiveControl`, members separated by `|`.
The ports are the CLI's existing defaults and are fine to keep — the three members are on different
machines, so they do not need to differ.

```
0,shard0-a:20110,shard0-a:20220,shard0-a:20330,shard0-a:20440,shard0-a:8010|\
1,shard0-b:20110,shard0-b:20220,shard0-b:20330,shard0-b:20440,shard0-b:8010|\
2,shard0-c:20110,shard0-c:20220,shard0-c:20330,shard0-c:20440,shard0-c:8010
```

Pass it identically to all three with `most cluster --members`, varying only the member id.

::: todo `most cluster` cannot be a member other than 0
The cluster host hardcodes member id 0 and exposes no `--member-id`, so all three nodes would claim
to be member 0. This is blocking for a multi-node deployment.
:::

### Port map

Every one of these is bound by the **media driver** on its own machine.

| Port | Bound by | Purpose |
| --- | --- | --- |
| 20110 | consensus | Cluster ingress — gateways and the control plane connect here |
| 20220 | consensus | Consensus, member to member |
| 20330 | consensus | Log replication |
| 20440 | consensus | Catchup |
| 8010 | archive | Archive control |
| 20001 | gateway | **Client inbound** — adapters publish orders here |
| 20002 | gateway | **Client outbound** control address — execution reports |
| 40000 | discovery | Directory broadcast |
| 40001 / 40002 / 40003 | market-data | L1 / L2 / L3 |
| 40004 | market-data | L2 recovery snapshot feed |

20110, 20001 and 20002 are the only ports anything outside the trading VLAN needs to reach. Firewall
the rest to the three machines and the market-data subscriber group.

### Gateway ingress

The gateway lists every member so its cluster client can find the leader:

```properties
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=shard0-a:20110,1=shard0-b:20110,2=shard0-c:20110
```

## 3.7 Market data: multicast

The development stack uses dynamic MDC because a Docker bridge does not route multicast. On a real
VLAN, use what the design specifies:

```properties
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

::: warning Configure IGMP snooping and a querier on the VLAN
Without an IGMP querier, snooping switches age out group membership and the feed silently stops
reaching subscribers, usually minutes after everything looked fine.
:::

::: note Do not change Aeron's flow control
It defaults to `MaxMulticastFlowControl` — the fastest receiver governs — and that is correct here.
`MinMulticastFlowControl` would let the slowest subscriber throttle the publisher, reintroducing
exactly the coupling that moving market data out of the engine was meant to remove. The accepted
trade-off is that slow subscribers take unrecoverable gaps; gap detection and snapshot
re-synchronisation are subscriber responsibilities, and `reference` provides both
(`FeedSequenceTracker`, `DepthFeedAssembler`).
:::

::: todo A feed is one channel
`market-data` cannot publish the same feed as both multicast and dynamic MDC, so a subscriber that
cannot receive multicast cannot be served alongside one that can.
:::

## 3.8 Process management

One `systemd` unit per process. The engine's is the template; the rest differ only in `ExecStart` and
`CPUAffinity`.

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

KillSignal=SIGTERM
TimeoutStopSec=60
Restart=on-failure
RestartSec=15s

[Install]
WantedBy=multi-user.target
```

`RestartSec=15s` is not arbitrary: it is longer than the roughly ten-second mark-file liveness
timeout, so an automatic restart does not fail on `active mark file detected` and then back off.

::: warning The whole shutdown path hangs off an orderly SIGTERM
Snapshot counters, latency summaries and the service container's own close all run inside a shutdown
barrier that SIGTERM releases. `SIGKILL` skips them and leaves the mark file carrying a live
timestamp, so the next start refuses for ten seconds. For native images `--install-exit-handlers` is
load-bearing and is set in the root build; without it SIGTERM kills the image outright.
:::

### Configuration management

Two kinds of file that must not be confused:

- **Released artifacts** — `shard-N-securities.properties`, `shard-N-participants.properties`, the
  discovery registry. Published by the control plane as immutable numbered releases. Your
  configuration-management tool *fetches* a release and points `current` at it; it never authors one.
- **Node-local config** — `engine.properties`, `gateway.properties`, Aeron and cluster directories,
  feed channels. Lives in the repository and is templated per host.

## 3.9 Bringing a shard up

Order matters, and most of it is the mark-file timeout in disguise.

```sh
# 1. All three cluster hosts, within a few seconds of each other. A member that starts minutes
#    late will catch up, but the group will not form until two are present.
systemctl start most-cluster-host          # on shard0-a, -b, -c

# 2. Wait for a leader. Not optional: the engine's service container attaches to a consensus
#    module that must already exist.
journalctl -u most-cluster-host -f | grep -m1 'Leader'

# 3. Engine service containers, all three.
systemctl start most-engine

# 4. The rest, on their assigned nodes.
systemctl start most-market-data           # all three; followers stay silent
systemctl start most-discovery             # all three is safe

# 5. Gateways, on their own machines. Order does not matter and neither does how many.
systemctl start most-gateway

# 6. Control plane last — it is a client of everything above.
kubectl -n most rollout status deploy/control
```

### Verify geometry before trusting anything

```sh
ansible shard0 -a "journalctl -u most-engine -n 200 --no-pager" | grep -i fingerprint | sort -u
```

One distinct value, or stop and fix it. Raft will not catch this.

### Establish the session baseline

A cold cluster has emitted no `SessionChanged`, so its phase is genuinely unknown to anything
watching the feed. The scheduler **waits** rather than assuming `CLOSED`, because a control-plane
restart mid-session looks identical from the outside and assuming would shut a live market.

**Issue one session command deliberately as the last step of bringing a cold shard up.** After that,
the feed keeps the control plane current and the calendar takes over.

## 3.10 Blocking gaps

An honest list. None of these are large; all of them block a production deployment.

| # | Gap | Blocks |
| --- | --- | --- |
| 1 | `most cluster` hardcodes member id 0 and exposes no `--member-id` | 3.6 — a multi-node cluster |
| 2 | Driver and archive threading modes are hardcoded `SHARED` | 3.4 — dedicated threads |
| 3 | Idle strategies are constants, not configuration | 3.4 — busy-spin only where cores are isolated |
| 4 | `engine.march` is x86-only and passed straight to `native-image` | A second architecture |
| 5 | The directory advertises one gateway per shard | 3.2 — naming several gateways |
| 6 | A feed is one channel; multicast and MDC cannot coexist | 3.7 — mixed subscribers |
| 7 | No health endpoint on the core processes | 6.8 — mechanised alerting; today everything is log lines and shutdown counters |
| 8 | None of this has been run as described | All of section 3 |
