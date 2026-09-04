#!/usr/bin/env bash
# Latency attribution: where a client's round trip actually goes.
#
# `most load` measures the whole client round trip and nothing smaller, so a p50 of ~56us says
# nothing about whether the engine is fast or the plumbing is slow. This runs the same load with
# the in-process instrumentation on (Design.md §2, §7), stops each process so it prints what it
# measured, and subtracts: whatever the round trip did not spend in the gateway or the engine, it
# spent in consensus, the archive write and the wire.
#
# Run it before and after a change to the core -- persistence, recovery, a new gate -- and diff the
# two. The .hgrm files are written for exactly that; they are the format `most load` already emits.
#
# Prerequisite:  ./gradlew installDist
#
# Knobs:
#   ORDERS     orders in the measured run    (default 2,000,000)
#   DELAY_US   microseconds between sends    (default 10 => 100k/s)
#   MAX_ORDERS book capacity per security    (default 1,000,000)
#   STAGES     split a new order into admit/match/settle (default true)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${ATTRIBUTION_DIR:-$ROOT/build/attribution}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"

ORDERS="${ORDERS:-2000000}"
DELAY_US="${DELAY_US:-10}"
MAX_ORDERS="${MAX_ORDERS:-1000000}"
STAGES="${STAGES:-true}"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
MARKETDATA="${MARKETDATA:-$ROOT/market-data/build/install/market-data/bin/market-data}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"

rm -rf "$RUN"; mkdir -p "$LOGS"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; echo "--- logs in $LOGS ---" >&2; exit 1; }

wait_for() { # wait_for <file> <pattern> <seconds> <what>
  local file="$1" pattern="$2" secs="$3" what="$4" i=0
  while [ "$i" -lt "$((secs * 4))" ]; do
    [ -f "$file" ] && grep -q "$pattern" "$file" && return 0
    sleep 0.25; i=$((i + 1))
  done
  echo "--- $what did not start; tail of $file ---" >&2
  tail -20 "$file" >&2 2>/dev/null
  return 1
}

stop_and_wait() { # stop_and_wait <pid>
  kill -TERM "$1" 2>/dev/null
  for _ in $(seq 1 60); do kill -0 "$1" 2>/dev/null || return 0; sleep 0.25; done
  return 1
}

# One security: the point is to attribute one order's path, and a second book only adds noise.
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
security.1.maxOrders=$MAX_ORDERS
EOF

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
engine.metrics=true
engine.metrics.stages=$STAGES
engine.metrics.file=$RUN/engine-latency.hgrm
EOF

cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
gateway.metrics=true
gateway.metrics.file=$RUN/gateway-latency.hgrm
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

echo "== starting the shard ($ORDERS orders, ${DELAY_US}us apart, stages=$STAGES)"
$MOST cluster --fresh --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" > "$LOGS/cluster.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/cluster.log" "awaiting shutdown signal" 45 "cluster host" || fail "cluster host"

$ENGINE "$RUN/engine.properties" > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!; PIDS+=($ENGINE_PID)
wait_for "$LOGS/engine.log" "awaiting shutdown signal" 45 "engine" || fail "engine"

$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
GATEWAY_PID=$!; PIDS+=($GATEWAY_PID)
wait_for "$LOGS/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"

$MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/market-data.log" "market-data: started" 30 "market data" || fail "market data"

$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  > "$LOGS/define.log" 2>&1 || fail "define AAPL"
$MOST session --phase continuous --shard 0 $CONN > "$LOGS/session.log" 2>&1 || fail "session"
sleep 1

echo "== driving the load"
$MOST load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
  --count "$ORDERS" --delay-us "$DELAY_US" --warmup 1000 --participant 20 --participants 4 \
  --clordid-base 100000 --drain-ms 20000 --histogram-file "$RUN/client-latency.hgrm" \
  $CONN > "$RUN/load.out" 2>&1 || fail "load"

grep -qE "fills +0 qty traded" "$RUN/load.out" && fail "no trades: the matching path never ran"
grep -q "REJECTED" "$RUN/load.out" && fail "orders were rejected -- band or phase is wrong"

# The summaries only exist on an orderly shutdown, which in a native build depends on
# --install-exit-handlers, and in the engine on the prints living inside the barrier block.
echo "== stopping the instrumented processes for their summaries"
stop_and_wait "$ENGINE_PID" || fail "engine did not stop"
stop_and_wait "$GATEWAY_PID" || fail "gateway did not stop"

grep -q "matching-engine: latency" "$LOGS/engine.log" || fail "engine reported no latency summary"
grep -q "gateway: latency" "$LOGS/gateway.log" || fail "gateway reported no latency summary"

# ------------------------------------------------------------------ the attribution
p50() { # p50 <file> <label>  -> microseconds, as printed
  grep -E "^ +$2 +n=" "$1" | sed -E 's/.*p50=([0-9.]+).*/\1/' | head -1
}

CLIENT=$(grep -E "^  ack  service" "$RUN/load.out" | sed -E 's/.*p50=([0-9.]+).*/\1/')
IN=$(p50 "$LOGS/gateway.log" inbound)
OUT=$(p50 "$LOGS/gateway.log" outbound)
ENG=$(p50 "$LOGS/engine.log" newOrder)

echo
grep -E "achieved|^  ack  service" "$RUN/load.out" | sed 's/^/  /'
echo
grep -A12 "matching-engine: latency" "$LOGS/engine.log" | sed 's/^/  /'
grep -A4 "gateway: latency" "$LOGS/gateway.log" | sed 's/^/  /'

echo
echo "== attribution at the median"
awk -v c="$CLIENT" -v i="$IN" -v e="$ENG" -v o="$OUT" 'BEGIN {
  proc = i + e + o
  rest = c - proc
  printf "  client round trip        %8.1f us\n", c
  printf "  gateway inbound          %8.1f us\n", i
  printf "  engine (whole message)   %8.1f us\n", e
  printf "  gateway outbound         %8.1f us\n", o
  printf "  ------------------------------------\n"
  printf "  in this shard'"'"'s processes %8.1f us  (%.1f%%)\n", proc, 100 * proc / c
  printf "  everything else          %8.1f us  (%.1f%%)\n", rest, 100 * rest / c
  printf "\n  \"Everything else\" is Raft consensus, the archive write, the IPC hops and the\n"
  printf "  poller wake-ups. It is not idle time: it is the cost of being a replicated log.\n"
}'

echo
echo "  histograms: $RUN/{client,engine,gateway}-latency.hgrm"
echo
echo "PASS -- the round trip is attributed."
