# 6. Troubleshooting and maintenance

## 6.1 First moves

Four questions answer most incidents, in this order.

1. **Do all processes agree on geometry?** Grep every startup log for `fingerprint`. One distinct
   shard value and one distinct registry value, or stop here.
2. **What phase does the exchange think it is in?** `GET /api/status`, which reads L3. Not the
   database, and not the calendar.
3. **Is the directory being broadcast?** `most securities`, or `directory.lastSeenAt` in the status
   response. A `most` command that hangs is almost always waiting for it.
4. **What do the processes' own counters say?** They are printed at shutdown, so a process that was
   `SIGKILL`ed tells you nothing. That is one of the reasons SIGTERM matters (3.8).
5. **What does Aeron say, live?** `most counters --interval-ms 4000 --samples 3` reads the driver's,
   the archive's and every cluster component's counters without attaching a client, so it works on a
   shard that is currently misbehaving (5.8). Read the slopes, not the values.

## 6.2 Startup failures

| Symptom | Cause and remedy |
| --- | --- |
| `missing required configuration key: engine.securitiesFile` | No default exists. Give the process its properties file, or set the key as a system property |
| `shard security file not found: ...` | The `current` symlink points nowhere, or the release was never fetched to this machine |
| `invalid ISIN 'US0378331006' for security 1` | Check-digit validation at boot. The control plane refuses the same value with the same sentence |
| `duplicate symbol in shard: [AAPL]` | Two securities in one shard file share a symbol or an id |
| `engine.participantRegistry is for shard 1, but this node serves shard 0` | A registry from another shard's release. Caught here rather than at the first misrouted report |
| `gateway id 'gw-x' is not in the participant registry` | The gateway's id and the registry disagree. Stops the process rather than becoming a cluster connection the consensus module refuses with no explanation |
| `gateway.gatewayId and one of ... must be set together` | An id with no secret cannot authenticate; a secret with no id has nothing to authenticate as |
| `no Aeron media driver found` | The driver is not running, or `*.aeronDir` points somewhere else |
| `could not reach the cluster` | The consensus module is not running, or `gateway.ingressEndpoints` names the wrong hosts |
| `Active media driver detected` | A previous driver's liveness timeout has not expired, or the Aeron directory was preserved across a restart. It must not be — it is memory-mapped buffers and a `cnc.dat` |
| `active mark file detected` | A node restarted within roughly ten seconds of the previous one stopping. Wait |
| `could not create archive directory` | A volume or directory landed root-owned |
| `channel error - Cannot assign requested address` | A channel names a host whose media driver does not own that address. In the dev stack that means anything other than `shard0` (2.9) |

## 6.3 The engine refuses to restore its snapshot

This is the system working. `loadSnapshot` reconciles the snapshot against the security file the node
just booted from, and refuses rather than losing state.

```
matching-engine: refused to restore its snapshot -- security 2 (MSFT) is not in this shard's
security file but holds 4 resting orders
```

| Refusal | What to do |
| --- | --- |
| A security is gone from the file but holds resting orders | Restore the previous release, drain the book, then apply the change |
| `priceFloor`, `tickSize`, `levelCount` or `maxOrders` changed | Same — geometry cannot change under a populated book |
| An order falls outside the new ladder | Same |
| Resting-order counts do not add up | Do not restart around this. It means the snapshot and the file describe different things |

The two legitimate cases are quiet and are logged with one line each: an **empty** book leaving the
shard, and a new security joining.

::: note A refused restore exits through the shutdown barrier, not a halt
Halting would skip the service container's close, which leaves the cluster mark file carrying a live
timestamp — so the operator who fixes the file and restarts immediately would be met with `active
mark file detected` for the next ten seconds instead of a working node.
:::

::: warning A bad order during a restore does not crash the node
Aeron's `Image.poll` swallows an exception from its fragment handler, hands it to the client error
handler, and advances the position anyway. The book would come back quietly wrong. This is why the
restore range-checks every order itself and reconciles resting-order counts as a backstop — and why
you should not assume a throw on this path would be loud.
:::

## 6.4 Recovering a halted security

A halt sets **one security** to `CLOSED` and leaves the resting book intact. There is no halt phase,
and `VolatilityHalted` is visible on L3 and nowhere else — a depth subscriber cannot tell a halt from
a scheduled close.

Confirm it first:

```json
"securities": [ { "securityId": 1, "phase": "CLOSED",
  "halt": { "at": "...", "collarReference": 10064000000, "attemptedPrice": 10167000000,
            "breachedBound": 100640000, "aggressorSide": "BUY", "clearedAt": null } } ]
```

Then reopen, in one operation:

```sh
curl -u admin:... -X POST localhost:8080/api/shards/0/reopen \
  -H 'Content-Type: application/json' -d '{"securityId":1,"referencePrice":10161000000}'
```

```json
{ "shardId": 0, "securities": ["AAPL", "MSFT"], "succeeded": true,
  "steps": [
    { "command": "define AAPL", "sent": true, "confirmed": false,
      "detail": "seeded reference 101.61 -- not confirmable: no feed acknowledges a
                 SecurityDefinition, so a rejection would be silent" },
    { "command": "session PRE_OPEN shard 0",     "sent": true, "confirmed": true },
    { "command": "session OPEN_AUCTION shard 0", "sent": true, "confirmed": true },
    { "command": "session CONTINUOUS shard 0",   "sent": true, "confirmed": true } ],
  "warning": "a session transition is shard-wide: this moved AAPL, MSFT, not only AAPL" }
```

::: warning Both orderings are load-bearing
**Re-seed the definition first.** `staticReference` is reset only by an *executing* uncross, while
orders are accepted from `PRE_OPEN` onward. If the halt moved price outside the old static band, the
very orders needed to reopen are rejected by the stale collar before the auction that would have
fixed it gets any — a deadlock that looks like nothing happening.

**Walk the phases in full.** The uncross runs only on `OPEN_AUCTION → CONTINUOUS`. Jumping straight
to `CONTINUOUS` is accepted and silently skips the auction.
:::

Doing it by hand is the same four steps:

```sh
most define  --symbol AAPL --reference 101.61 --static-collar 5000 --dynamic-collar 100
most session --phase pre-open     --shard 0
most session --phase open-auction --shard 0
most session --phase continuous   --shard 0
```

The scheduler will not do this for you, by design (5.2).

## 6.5 Order flow problems

| Symptom | Diagnosis |
| --- | --- |
| A client's orders are all `MARKET_CLOSED` | The security is `CLOSED`. Check `/api/status` for a `halt` block — a halt and a scheduled close are indistinguishable from a depth feed |
| A client's orders are all `PRICE_OUT_OF_BOUNDS` | The static collar is anchored on a stale `staticReference`. Re-seed the definition |
| Everything is `BOOK_CAPACITY` | `maxOrders` is too small for the rate. Under a load test this invalidates the run entirely |
| A maker never receives its fills | The participant is not bound to a live session. Either no registry is configured — so routes are learned from traffic only, and a participant that has been quiet since connect is unreachable — or no connected gateway lists it |
| Orders rejected `UNAUTHORIZED_PARTICIPANT` | The gateway does not list that participant, or lists it only as `cancelOnly` (4.3). Check the gateway's startup or reload line, `gateway: identity=… participants=[…] cancelOnly=[…]`, against the registry in force |
| Session transitions, purges or definitions "sent" but nothing changes | They went through a gateway that is not an operator, which consumes them and counts `refusedCommands`. Operator commands are unacknowledged, so nothing else says so. Point the control plane at an operator gateway (4.9) or make that gateway one |
| A gateway will not connect: `session failed authentication` in its log, `presented no credentials` or `failed authentication as gateway` in the cluster host's | No identity configured on a node with a registry, or the wrong secret. Both are refused, never downgraded to anonymous |
| `most: no snapshot -- UNAUTHORISED_ACCESS` | The identity authenticated but is not an `operator=true` entry. Only an operator may request a snapshot through consensus |
| `most: no snapshot -- no answer from the cluster` or a connect timeout at `POLL_RESPONSE` | The egress endpoint is one the cluster cannot send to — `0.0.0.0` is the usual culprit. Name the host of the requester's media driver (4.9) |
| Duplicate orders | Two gateways subscribed to one `client.inbound.channel`. Give each its own (3.2) |
| Reports stop arriving but orders are accepted | `droppedToClient` is climbing: the outbound subscriber is gone or too slow |
| Orders are accepted but nothing matches | The phase is not `CONTINUOUS`. Booking without matching is correct in `PRE_OPEN` and `OPEN_AUCTION` |
| A crossed book that never uncrosses | `CONTINUOUS` was reached from somewhere other than `OPEN_AUCTION`, so the uncross never ran |
| Everything is slow, but nothing is rejected, dropped or unanswered | The shard is past its sustainable rate and you are measuring a backlog. Check the offered rate against 5.7, and check `--driver-threading` — on the default `SHARED` the ceiling is 1.6x lower than it needs to be (4.8). Then check the egress publication's headroom: at zero, with the driver's sender behind, the UDP egress channel is the limit and the engine only looks full (4.8, 5.8) |
| Latency degrades suddenly rather than gradually | Expected. The knee is abrupt: there is no gentle degradation to alert on before it becomes a stall, which is why the offered rate has to be watched rather than inferred from latency |

::: note An anonymous gateway can only reach a node without a registry
A node with a registry refuses a session with no credentials, so the old quiet failure — an
anonymous gateway trading perfectly well and losing only the fills of participants that had gone
quiet — is now a refusal at connect. On a node **without** a registry it still happens, and the
gateway enforces nothing there either.
:::

## 6.6 Market data problems

| Symptom | Diagnosis |
| --- | --- |
| `feedGaps` and `eventsSeen` in `/api/status` track each other | A control plane older than the feed-gap fix, counting every event it did not interpret as a gap. Upgrade; a healthy shard reports zero or near it (5.8) |
| A subscriber shows nothing and says it is waiting | It has not received a snapshot yet. A full pass is `md.snapshot.cycleMs × securities` |
| A subscriber shows nothing after the engine restarted | It restarted too, and has no book. Request a book image (5.3) |
| `gaps` climbing steadily | A subscriber cannot keep up. Expected occasionally under `MaxMulticastFlowControl`; sustained means a real problem, and the fix is not changing flow control |
| The feed stops minutes after everything looked fine | No IGMP querier on the VLAN, so snooping switches aged out the group |
| `foreignShard` non-zero | This market-data process is subscribed to another shard's engine |
| A halt is invisible | It is only on L3. Subscribe to L3, not L1 or L2 |
| `imagesDiscarded` non-zero | Images arrived at an already-synchronised book that was ahead of them. Expected on join; sustained means something is republishing stale images |

## 6.7 Maintenance procedures

### Changing geometry

Geometry changes are applied by restarting, and the snapshot reconciliation (6.3) is what makes that
safe.

```
1. Author the change in the control plane and publish a release.
2. Drain, or confirm empty, every book whose geometry changed or which is leaving the shard.
3. Fetch the release to every machine and move `current`.
4. Restart the cluster host and engine on each node in turn, waiting out the mark-file timeout.
5. Restart gateway, market-data and discovery so they read the new file too.
6. Compare fingerprints.
```

### Changing who speaks for whom

A participant list change does **not** restart a node.

A participant list change restarts **nothing** — not a node, and not a gateway.

```sh
# 1. Publish from the control plane, then point `current` at it on every machine.
ansible shard0,gateways -a "ln -sfn /etc/most/releases/000042 /etc/most/current"

# 2. Watch the node processes adopt it.
journalctl -u most-engine -f | grep -m1 'registry: reloaded'

# 3. And each gateway, which prints its own new grants -- the line that says it is in force.
journalctl -u most-gateway -f | grep -m1 'gateway: now'
```

Check `registryReloadFailures` rather than assuming a silent success — a bad file is ignored, not
fatal (4.3). `engine.participantRegistry.reloadMs=0`, `gateway.participantRegistry.reloadMs=0` and
`--participants-reload-ms 0` turn the poll off if a deployment would rather restart.

**Revoking a participant** is two releases. First move it from `participants` to `cancelOnly` on
each gateway that lists it: new orders are refused at once, and it can still cancel what is resting.
Once its orders are gone, remove it. There is no bulk cancel for it yet (1.7).

### Replacing or adding a gateway

Gateways hold no state, so this is ordinary:

1. Create the gateway in the control plane and record the secret it returns **once**.
2. Publish a release, and deploy the registry to the cluster nodes.
3. Give the new gateway its own client inbound and outbound endpoints — never a peer's.
4. Start it, and point its adapters at it.

Removing one is the reverse; its adapters reconnect elsewhere and its in-flight orders are reported
correctly by whichever gateway the client next uses.

### Rotating a gateway secret

`PUT /api/gateways/{id}/secret` issues a new one and returns the plaintext once. Publish, deploy the
registry, and restart that gateway. The nodes pick the new digest up without restarting, so the
window in which the old secret still works is one poll interval.

### Upgrading a node

Rolling, one member at a time, waiting for the group to be healthy between each:

```sh
most cluster snapshot --ingress 0=shard0-a:20110 \
     --identity operator --secret-file /etc/most/operator.secret   # every member, same position
systemctl stop most-engine most-cluster-host        # on one node only
# deploy the new binaries
sleep 15
systemctl start most-cluster-host most-engine
journalctl -u most-engine | grep 'restored'
```

::: warning A wire change is not a rolling upgrade
Nodes running different schema versions over one log will diverge. Treat any change to
`message-schema.xml` as requiring a coordinated restart of the whole shard, and re-run
`e2e/run-e2e.sh` first — it exists because unit tests cannot see the wire.
:::

### Checking a native image you cannot run

The native build needs `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`. A missing export does
**not** fail the build: it silently omits the class, and the binary dies on its first `UnsafeBuffer` —
and only once a real Aeron control file exists, so a smoke test against a *missing* driver passes and
hides it.

```sh
grep -c 'jdk.internal.misc.Unsafe' /opt/most/bin/engine     # 0 = broken, 3 = correct
```

Starting the binary without a media driver does not check this: it exits on the expected driver
timeout before the first buffer is ever wrapped.

## 6.8 Reading the logs

| Line | Process | Means |
| --- | --- | --- |
| `cluster: started, awaiting shutdown signal` | cluster-host | The consensus module is up. The engine may now attach |
| `matching-engine: started, awaiting shutdown signal` | engine | The service container is attached |
| `matching-engine: restored N resting orders ... from a snapshot` | engine | A **real** recovery, as distinct from a log replay |
| `gateway: identity=gw-0 registry=... participants=[7, 8] cancelOnly=[] operator=true` | gateway | What this gateway will **enforce**: who may place, who may only cancel, whether operator commands pass |
| `gateway: now participants=[...] cancelOnly=[...] operator=...` | gateway | A reloaded registry is in force for this gateway. The line to wait for after publishing a revocation |
| `gateway: no cluster identity` | gateway | Connecting anonymously and enforcing nothing. A node with a registry refuses it |
| `cluster: session N presented no credentials; ...` | cluster-host | An anonymous client refused by a node with a registry |
| `gateway: cluster session closed; orders can no longer be forwarded` | gateway | Terminal for this process |
| `cluster: registry: reloaded` | cluster-host, engine | A new participant registry came into force, with both fingerprints |
| `discovery: version=... shards=[0] securities=2` | discovery | The universe version now being broadcast |

Shutdown lines carry the counters, which is why an orderly SIGTERM matters:

```
gateway: stopped. forwardedToCluster=60000 clusterBackpressure=0 sentToClient=88676
         droppedToClient=0 rejectedLocally=0 unreachableRejects=0
         undeliverableCommands=0 unauthorizedRejects=0 refusedCommands=0 untrackedReports=0

market-data: stopped. gaps=0 missed=0 foreignShard=0 droppedL1=0 droppedL2=0 droppedL3=0
             snapshots=929 droppedSnapshot=0 imagesApplied=1 imagesDiscarded=0 imageOutOfBand=0
```

## 6.9 Consolidated list of what is not implemented

| Area | What is missing | Section |
| --- | --- | --- |
| Order entry | No bulk cancel of one participant's resting orders, so revoking one relies on it withdrawing them from `cancelOnly` | 1.7, 6.7 |
| Cluster host | Member id is hardcoded to 0; no `--member-id` | 3.6 |
| Capacity | With UDP egress a shard sustains ~350,000 orders/s aggregate on the default threading and ~500,000–550,000 with `DEDICATED`. The driver's UDP sender on egress sets that limit. With IPC egress it sustains ~1,500,000, and there the engine thread fills first. The design target of 100,000/s per security across ten is met only with IPC egress, only on one node, and IPC egress needs the gateway on the leader's media driver. Multi-node log replication over UDP is unmeasured | 4.8, 5.7 |
| Gateway placement | Nothing keeps IPC egress working across a failover: a gateway that follows the leader is neither designed nor tested | 3.2, 4.8 |
| Build | `engine.march` is x86-only | 3.10 |
| Discovery | One order-entry channel per shard, so several gateways cannot be advertised individually. Not a capacity lever below ~2,000,000/s: a second gateway raised no ceiling, and the one gateway reaches 93% only at ~2,100,000/s with IPC egress | 3.2, 5.7 |
| Market data | A feed is one channel; multicast and dynamic MDC cannot coexist | 3.7 |
| All processes | No health or metrics endpoint; monitoring is log lines and shutdown counters | 5.8 |
| Boot | Processes do not verify the release fingerprint they read against what was published | 4.11 |
| Engine | `--gc=epsilon` is proven by three measurements, now run by CI on every push, but is off by default pending a soak measured in hours | — |
| Production | None of section 3 has been run as described | 3.10 |
