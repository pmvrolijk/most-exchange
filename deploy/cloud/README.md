# Benchmarking on a cloud host

The laptop's knee is its core count (Measurements.md D1–D2): past ~550k/s the shard has more
spinning threads than performance cores, and the engine thread falls over. This directory holds
what is needed to rerun the same sweep on a host with **a physical core for every spinning thread**,
so the knee there is a property of the shard, not of the machine (Status.md §3 item 3).

Nothing here is a deployment. `ProdDeployment.md` covers the three-node production layout.

## What you get

`linode/cloud-init.yaml` builds an Ubuntu 24.04 host that:

- offlines every SMT sibling at each boot (`most-cpus.service`), leaving one CPU per physical core
- isolates the agents' cores from the scheduler (`isolcpus`, `nohz_full`, `rcu_nocbs`, `idle=poll`)
  and moves interrupts to the two housekeeping cores
- writes the core map to `/etc/most-cpus.env`, which `PIN=` in `e2e/run-sweep.sh` and
  `e2e/run-attribution.sh` reads
- has JDK 21, tmux, `mpstat`, `lstopo-no-graphics` and `tuned` (`latency-performance`)

With `PIN` set, both scripts launch every process on the housekeeping cores and then move each named
agent thread to its own core (`e2e/pin.sh`). The map, for 16 cores:

| core | thread | isolated |
| --- | --- | --- |
| 0–1 | kernel, sshd, IRQs, every JVM's GC, JIT and Aeron client conductor, discovery | no |
| 2, 3, 4 | driver conductor, sender, receiver (`DEDICATED`) | yes |
| 5 | archive conductor | yes |
| 6 | consensus module | yes |
| 7 | **engine service container**: the matching thread | yes |
| 8 | gateway (`gateway-poller`) | yes |
| 9 | market-data (`market-data-poller`) | yes |
| 10 | archive recorder, only with `ARCHIVE_THREADING=DEDICATED` | yes |
| 11–14 | `most load`, two threads per loader | **no** |
| 15 | a second gateway (`GATEWAYS=2`) | yes |

The loaders' cores are deliberately *not* isolated. An isolated CPU is outside load balancing, so
every thread in a multi-CPU isolated mask would pile onto its first CPU. Each agent therefore gets
exactly one isolated CPU, and the loaders, which start fresh at every rate, get CPUs the scheduler
can spread them over.

Pinning refuses to run if an order-path thread (`consensus-module`, `clustered-service`,
`gateway-poller`, `market-data-poller`, the driver) can't be found by name. A run with an agent
left unpinned would otherwise be labelled pinned. After an Aeron upgrade, check the names on the
box first: `bench.sh ssh`, start the shard, and call `pin_threads --list <pid>...` after sourcing
`e2e/pin.sh`.

## Choosing a host

Cloud vCPUs are hardware threads. **16 vCPUs is usually 8 cores**, and the engine is cache-bound,
so a busy SMT sibling repeats the laptop's confound. Take twice the vCPUs you want in cores and let
`most-cpus` offline the siblings. The default is Linode `g7-dedicated-64-32` (32 vCPU, 64 GB,
~$1.04/h, hourly).

If `bench.sh check` reports `TOPOLOGY=flat-unverified`, the hypervisor hid the sibling pairing and
nothing was offlined. Run `bench.sh probe`, which spins two loops and flags pairs that slow each
other down. Write one CPU per core to `/etc/most-cores` on the box, then run
`sudo most-cpus --grub && sudo reboot`. Until that's done, don't call a pinned run "one core per
thread".

## A run, start to finish

Needs `linode-cli` configured, `jq`, and an SSH key (`SSH_KEY`, default `~/.ssh/id_ed25519.pub`).

```sh
deploy/cloud/linode/bench.sh up        # ~5-10 min: create, cloud-init, reboot onto isolcpus; billing starts
deploy/cloud/linode/bench.sh check     # the topology, isolated CPUs and the map -- read it
deploy/cloud/linode/bench.sh sync      # this working tree, .git included, then installDist on the box

# unpinned: the laptop's R7 conditions with only the machine changed
deploy/cloud/linode/bench.sh run 'SWEEP_DIR=build/sweep-unpinned-1 SECURITIES=10 DRIVER_THREADING=DEDICATED \
  RATES="300000 400000 500000 550000 600000 700000 800000 1000000" ./e2e/run-sweep.sh'

# pinned: one core per agent
deploy/cloud/linode/bench.sh run 'SWEEP_DIR=build/sweep-pinned-1 PIN=/etc/most-cpus.env SECURITIES=10 \
  DRIVER_THREADING=DEDICATED RATES="300000 400000 500000 550000 600000 700000 800000 1000000" ./e2e/run-sweep.sh'

# which thread is full, at rates bracketing the pinned knee (D1's procedure)
deploy/cloud/linode/bench.sh run 'for r in 500000 600000 700000 800000; do ATTRIBUTION_DIR=build/attribution-$r \
  PIN=/etc/most-cpus.env SECURITIES=10 DRIVER_THREADING=DEDICATED RATE=$r ORDERS=$((r * 8)) \
  ./e2e/run-attribution.sh || break; done'

deploy/cloud/linode/bench.sh fetch     # build/sweep*, build/attribution* and ~/runs into build/cloud/<date>/
deploy/cloud/linode/bench.sh down      # delete the Linode and firewall; billing stops
```

Run each arm twice. A knee that moves more than one rate step between repeats is noise, and gets
recorded as noise. `run` goes inside tmux, so a dropped connection doesn't stop it: `bench.sh tail`
reattaches. While a pinned run is going, `bench.sh ssh mpstat -P ALL 1` should show every isolated
core either at 100% (a busy-spinning agent) or idle, and nothing else on them.

Every figure goes into `docs/Measurements.md` with its conditions (the `perf-claim` skill). The
sweep's row block already names the CPU model, the core count, the commit and the pinning. The
`idle` column is still yours to fill in, and on a fresh host with nothing else running it's honest to
write "idle".

## Security

The firewall admits SSH from the IPv4 that ran `bench.sh up` and nothing else. Root login and
passwords are off, and the `most` user has your key. Aeron runs on IPC, and no port it opens is
reachable from outside. The Linode is tagged `most-bench`. `bench.sh down` deletes it and its
firewall, and stopping the Linode doesn't stop the billing.
