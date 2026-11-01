#!/usr/bin/env bash
# Upgrade test: version 7 to version 8 (Design.md §5, "Client order ids").
#
# Version 8 changed the layout of orders, cancels and reports in place, so nothing in a version 7
# log can be read as version 8. The upgrade is therefore `most cluster shutdown` on version 7, which
# leaves a snapshot at the end of the log, and a restart on version 8, which restores it. This
# script is that procedure on real processes, and the refusal that guards it:
#
#   1. orders rest on a version 7 shard, one partially filled;
#   2. `most cluster shutdown` on version 7;
#   3. version 8 restores the book: the integer ids come back as their decimal strings, a mass status
#      names them, a cancel by the old id works, and a new order with a text id trades against a
#      restored one;
#   4. a version 7 log tail with no snapshot at its end is refused by version 8, never read.
#
# Prerequisites: `./gradlew installDist` here, and a version 7 build of the same tree (any commit
# before version 8, e.g. `git worktree add ../v7 bd25eb1 && (cd ../v7 && ./gradlew installDist)`).
#   OLD=/path/to/that/tree ./e2e/run-upgrade.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${E2E_DIR:-$ROOT/build/e2e-upgrade}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"
OLD="${OLD:?set OLD to a version 7 tree with installDist done}"

rm -rf "$RUN"; mkdir -p "$LOGS"

bins() { # bins <tree>: the five binaries of one build
  MOST="$1/tools/build/install/most/bin/most"
  ENGINE="$1/engine/build/install/engine/bin/engine"
  GATEWAY="$1/gateway/build/install/gateway/bin/gateway"
  MARKETDATA="$1/market-data/build/install/market-data/bin/market-data"
  DISCOVERY="$1/discovery/build/install/discovery/bin/discovery"
  for b in "$MOST" "$ENGINE" "$GATEWAY" "$MARKETDATA" "$DISCOVERY"; do
    [ -x "$b" ] || { echo "FAIL: $b is missing; run installDist in $1" >&2; exit 1; }
  done
}

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  sleep 1
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; echo "--- logs in $LOGS ---" >&2; exit 1; }
pass() { echo "  ok: $*"; }

wait_for() { # wait_for <file> <pattern> <seconds> <what>
  local file="$1" pattern="$2" secs="$3" what="$4" i=0
  while [ "$i" -lt "$((secs * 4))" ]; do
    [ -f "$file" ] && grep -q "$pattern" "$file" && return 0
    sleep 0.25; i=$((i + 1))
  done
  echo "--- $what did not appear; tail of $file ---" >&2
  tail -30 "$file" >&2 2>/dev/null
  return 1
}

# ---------------------------------------------------------------- configuration
cat > "$RUN/securities.properties" <<EOF
shard.id=0
shard.securities=1

security.1.symbol=AAPL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=32768
security.1.maxOrders=10000
EOF

sha256() {
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="upgrade-e2e-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=7,8,9
gateway.gw-0.operator=true
EOF

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF

cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-0
gateway.credentialTokenFile=$RUN/gateway.secret
EOF

cat > "$RUN/market-data.properties" <<EOF
md.securitiesFile=$RUN/securities.properties
md.aeronDir=$AERON_DIR
md.bookEvent.channel=aeron:ipc
md.bookEvent.streamId=12
md.l1.channel=aeron:ipc
md.l1.streamId=31
md.l2.channel=aeron:ipc
md.l2.streamId=32
md.l3.channel=aeron:ipc
md.l3.streamId=33
md.snapshot.channel=aeron:ipc
md.snapshot.streamId=34
md.snapshot.cycleMs=500
EOF

cat > "$RUN/discovery.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$RUN/securities.properties
discovery.shard.0.orderEntryChannel=aeron:ipc
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=aeron:ipc
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=$AERON_DIR
discovery.channel=aeron:ipc
discovery.streamId=100
discovery.intervalMs=1000
EOF

CONN="--aeron-dir $AERON_DIR --discovery-channel aeron:ipc --discovery-stream 100
      --l1-channel aeron:ipc --l1-stream 31 --l2-channel aeron:ipc --l2-stream 32
      --snapshot-channel aeron:ipc --snapshot-stream 34"

# -------------------------------------------------------------------- processes
start_cluster() { # start_cluster <attempt> [--fresh]
  # Retried: a node cannot restart until the previous one's mark files age out (~10 s).
  local i=0
  while [ "$i" -lt 6 ]; do
    $MOST cluster --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" ${2:-} \
      --participants "$RUN/participants.properties" > "$LOGS/cluster-$1.log" 2>&1 &
    CLUSTER_PID=$!; PIDS+=("$CLUSTER_PID")
    if wait_for "$LOGS/cluster-$1.log" "awaiting shutdown signal" 20 "cluster host"; then return 0; fi
    grep -q "active mark file detected\|Active media driver" "$LOGS/cluster-$1.log" || break
    echo "  (waiting for the previous node's mark files to age out)"
    kill "$CLUSTER_PID" 2>/dev/null; wait "$CLUSTER_PID" 2>/dev/null
    sleep 5
    i=$((i + 1))
  done
  fail "cluster host"
}

start_engine() { # start_engine <attempt>
  $ENGINE "$RUN/engine.properties" > "$LOGS/engine-$1.log" 2>&1 &
  ENGINE_PID=$!; PIDS+=("$ENGINE_PID")
}

start_support() { # start_support <attempt>
  $GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway-$1.log" 2>&1 &
  GATEWAY_PID=$!; PIDS+=("$GATEWAY_PID")
  wait_for "$LOGS/gateway-$1.log" "gateway: started" 45 "gateway" || fail "gateway"
  $MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data-$1.log" 2>&1 &
  MARKETDATA_PID=$!; PIDS+=("$MARKETDATA_PID")
  wait_for "$LOGS/market-data-$1.log" "market-data: started" 30 "market data" || fail "market data"
  $DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery-$1.log" 2>&1 &
  DISCOVERY_PID=$!; PIDS+=("$DISCOVERY_PID")
  wait_for "$LOGS/discovery-$1.log" "discovery: started" 30 "discovery" || fail "discovery"
}

stop_support() {
  kill "$GATEWAY_PID" "$MARKETDATA_PID" "$DISCOVERY_PID" 2>/dev/null
  wait "$GATEWAY_PID" "$MARKETDATA_PID" "$DISCOVERY_PID" 2>/dev/null
}

# SIGTERM to both processes of the node: no snapshot is taken.
stop_node() {
  kill "$ENGINE_PID" 2>/dev/null; wait "$ENGINE_PID" 2>/dev/null
  sleep 1
  kill "$CLUSTER_PID" 2>/dev/null; wait "$CLUSTER_PID" 2>/dev/null
  sleep 1
}

open_market() {
  $MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
    || fail "define AAPL"
  $MOST session --phase continuous --shard 0 $CONN || fail "session transition"
  sleep 1
}

# -------------------------------------------------------------------------- 1
echo "== 1. rest orders on a version 7 shard"
bins "$OLD"
start_cluster v7 --fresh
start_engine v7
wait_for "$LOGS/engine-v7.log" "awaiting shutdown signal" 45 "engine" || fail "version 7 engine"
start_support v7
open_market

$MOST send --symbol AAPL --side sell --price 101.00 --qty 10 --clordid 1001 --participant 7 \
  --follow 2 $CONN > "$RUN/sell1.out" 2>&1 || fail "send sell 1"
$MOST send --symbol AAPL --side sell --price 102.00 --qty 20 --clordid 1002 --participant 7 \
  --follow 2 $CONN > "$RUN/sell2.out" 2>&1 || fail "send sell 2"
# Crosses 1001 for 4 of its 10, so a partially filled order is part of what is upgraded.
$MOST send --symbol AAPL --side buy --price 101.00 --qty 4 --clordid 1004 --participant 9 \
  --follow 2 $CONN > "$RUN/buy1.out" 2>&1 || fail "send buy"
grep -q "TRADE" "$RUN/buy1.out" || fail "the crossing order did not trade on version 7"
pass "two sells resting on version 7, one of them partially filled"

# -------------------------------------------------------------------------- 2
echo
echo "== 2. most cluster shutdown on version 7: a snapshot at the end of the log"
stop_support
$MOST cluster shutdown --dir "$RUN/cluster-host" || fail "cluster shutdown"
# The consensus module snapshots and terminates, which removes its counters. Neither process exits
# by itself: no termination hook is wired to their shutdown barriers (found 2026-11-01, Status.md),
# so the operator's SIGTERM follows, as it does after `cluster snapshot`.
for _ in $(seq 1 120); do
  $MOST counters --aeron-dir "$AERON_DIR" --match "Cluster node role" 2>/dev/null | grep -q "Cluster node role" || break
  sleep 0.25
done
$MOST counters --aeron-dir "$AERON_DIR" --match "Cluster node role" 2>/dev/null | grep -q "Cluster node role" \
  && fail "the consensus module did not terminate after cluster shutdown"
stop_node
pass "version 7 node stopped with a snapshot at the end of its log"

# -------------------------------------------------------------------------- 3
echo
echo "== 3. restart on version 8"
bins "$ROOT"
start_cluster v8
start_engine v8
wait_for "$LOGS/engine-v8.log" "restored 2 resting orders" 45 "the restore report" \
  || fail "version 8 did not restore the version 7 snapshot"
grep -o "matching-engine: restored .*" "$LOGS/engine-v8.log"
# loadSnapshot's lines come before the tail is replayed (CLAUDE.md): assert the node survived too.
wait_for "$LOGS/engine-v8.log" "awaiting shutdown signal" 45 "engine" || fail "version 8 engine"
start_support v8
sleep 2
kill -0 "$ENGINE_PID" 2>/dev/null || fail "the version 8 engine exited after its restore"
pass "version 8 restored the version 7 book"

$MOST status --participant 7 $CONN > "$RUN/status.out" 2>&1 || fail "mass status"
cat "$RUN/status.out"
grep -q "ORDER_STATUS .*clOrdId=1001 .*leaves 6 cum 4 of 10" "$RUN/status.out" \
  || fail "the partially filled order did not come back as clOrdId 1001 with its quantities"
grep -q "ORDER_STATUS .*clOrdId=1002 " "$RUN/status.out" || fail "order 1002 did not come back"
pass "the integer ids came back as their decimal strings, quantities intact"

ORDER_ID=$(grep "clOrdId=1002 " "$RUN/status.out" | grep -oE "orderId=[0-9]+" | cut -d= -f2)
$MOST cancel --symbol AAPL --side sell --order-id "$ORDER_ID" --orig-clordid 1002 --clordid CXL-1002 \
  --participant 7 --follow 2 $CONN > "$RUN/cancel.out" 2>&1 || fail "cancel"
cat "$RUN/cancel.out"
grep -q "CANCELED .*clOrdId=CXL-1002 origClOrdId=1002" "$RUN/cancel.out" \
  || fail "a cancel naming the old id did not cancel it, or its report lost an id"
pass "a cancel by the old id, as a decimal string, cancelled the restored order"

$MOST send --symbol AAPL --side buy --price 101.00 --qty 6 --clordid UPG-1/a.b --participant 9 \
  --follow 3 $CONN > "$RUN/cross.out" 2>&1 || fail "send crossing order"
cat "$RUN/cross.out"
grep -q "TRADE .*clOrdId=UPG-1/a.b .*cum 6" "$RUN/cross.out" \
  || fail "a text-id order did not fill against the restored order"
pass "an order with a text id traded against a restored one"

# -------------------------------------------------------------------------- 4
echo
echo "== 4. a version 7 log tail without a snapshot at its end is refused"
stop_support
stop_node
rm -rf "$RUN/cluster-host" "$AERON_DIR"
bins "$OLD"
start_cluster tail --fresh
start_engine tail
wait_for "$LOGS/engine-tail.log" "awaiting shutdown signal" 45 "engine" || fail "version 7 engine"
start_support tail
open_market
$MOST send --symbol AAPL --side sell --price 101.00 --qty 10 --clordid 3001 --participant 7 \
  --follow 2 $CONN > "$RUN/tail1.out" 2>&1 || fail "send before the snapshot"
$MOST cluster snapshot --dir "$RUN/cluster-host" || fail "snapshot request"
sleep 2
# After the snapshot, so it lives only in the log tail.
$MOST send --symbol AAPL --side sell --price 102.00 --qty 10 --clordid 3002 --participant 7 \
  --follow 2 $CONN > "$RUN/tail2.out" 2>&1 || fail "send after the snapshot"
stop_support
stop_node # SIGTERM: no snapshot at the end of the log

bins "$ROOT"
start_cluster refuse
start_engine refuse
wait_for "$LOGS/engine-refuse.log" "refused to go on" 45 "the refusal" || {
  tail -20 "$LOGS/engine-refuse.log" >&2
  fail "version 8 read a version 7 log tail instead of refusing it"
}
grep -A2 "refused to go on" "$LOGS/engine-refuse.log"
grep -q "version 7 NewOrderSingle" "$LOGS/engine-refuse.log" || fail "the refusal does not name the version 7 order"
for _ in $(seq 1 40); do kill -0 "$ENGINE_PID" 2>/dev/null || break; sleep 0.25; done
kill -0 "$ENGINE_PID" 2>/dev/null && fail "the refusing engine is still running"
wait "$ENGINE_PID"; CODE=$?
[ "$CODE" -ne 0 ] || fail "the refusing engine exited 0"
pass "version 8 refused the version 7 tail and exited $CODE"

echo
echo "PASS -- version 7 upgraded to version 8 through a snapshot, and a version 7 tail was refused"
