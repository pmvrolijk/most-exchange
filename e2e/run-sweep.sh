#!/usr/bin/env bash
# Load sweep: how far one book goes, and where it stops going there.
#
# Drives one security at a series of rates and reports both latencies at each, so the knee shows up
# as a shape rather than as a single number. `most load` measures the whole client round trip --
# gateway, consensus, the archive write, engine, egress, gateway -- so these are end-to-end capacity
# figures, not the engine's internal budget. Use run-attribution.sh for the split.
#
# Two things this script does that a bare `most load` loop does not, both of them the difference
# between a measurement and a number:
#
#   1. **A discard pass runs first.** A cold JVM stalls the *generator*, not the shard, and the
#      first rate in the list silently wears the cost. Observed: a 50k/s run reporting p99 18.9ms
#      with pacing lateness p99.9 of 10.8ms, against 2.6ms and ~0 for the identical run once warm.
#   2. **Every rate is validated before it is believed.** A run with rejects measured the reject
#      path (a maxOrders too small, or a band outside the collar or the ladder), and a run whose
#      pacing lateness blew out measured the harness. Either marks the row INVALID and fails the
#      script, rather than printing a plausible-looking figure.
#
# Metrics are deliberately OFF. This measures the round trip; instrumentation is node-local
# overhead and belongs in run-attribution.sh, which turns it on for exactly that reason.
#
# The last thing it prints is a row block for docs/Measurements.md. Paste it; do not retype the
# numbers, and do not quote any of them without the conditions that go with them
# (.claude/skills/perf-claim).
#
# Prerequisite:  ./gradlew installDist
#
# Knobs:
#   RATES            orders/sec to sweep, space separated (default "50000 100000 200000 333000")
#   ORDERS           orders per measured rate            (default 300,000)
#   WARMUP_ORDERS    orders in the discarded pass        (default 200,000)
#   MAX_ORDERS       book capacity                       (default 1,000,000)
#   SYMBOL           the security to drive               (default AAPL)
#   PACING_LIMIT_US  p99.9 lateness above which a row is the harness (default 1000)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${SWEEP_DIR:-$ROOT/build/sweep}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"

RATES="${RATES:-50000 100000 200000 333000}"
ORDERS="${ORDERS:-300000}"
WARMUP_ORDERS="${WARMUP_ORDERS:-200000}"
MAX_ORDERS="${MAX_ORDERS:-1000000}"
SYMBOL="${SYMBOL:-AAPL}"
PACING_LIMIT_US="${PACING_LIMIT_US:-1000}"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
MARKETDATA="${MARKETDATA:-$ROOT/market-data/build/install/market-data/bin/market-data}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"

rm -rf "$RUN"; mkdir -p "$LOGS"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  sleep 2
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

# ---------------------------------------------------------------- configuration
# ONE security. The design claims ten per shard and no run has ever driven more than one, which is
# open issue 13 in docs/Status.md -- a sweep of one book must not be quoted as the shard's ceiling.
cat > "$RUN/securities.properties" <<EOF
shard.id=0
shard.securities=1

security.1.symbol=$SYMBOL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=32768
security.1.maxOrders=$MAX_ORDERS
EOF

sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="sweep-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=20,21,22,23
EOF

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
engine.metrics=false
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
gateway.metrics=false
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
echo "== starting cluster host"
$MOST cluster --fresh --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" \
  --participants "$RUN/participants.properties" > "$LOGS/cluster.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/cluster.log" "awaiting shutdown signal" 60 "cluster host" || fail "cluster host"

echo "== starting engine"
$ENGINE "$RUN/engine.properties" > "$LOGS/engine.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/engine.log" "awaiting shutdown signal" 60 "engine" || fail "engine"
FINGERPRINT=$(grep -o "fingerprint=[0-9a-f]*" "$LOGS/engine.log" | head -1)
echo "   $FINGERPRINT"

echo "== starting gateway"
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/gateway.log" "gateway: started" 60 "gateway" || fail "gateway"
grep -q "identity=gw-0" "$LOGS/gateway.log" || fail "the gateway connected without an identity"

echo "== starting market data"
$MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/market-data.log" "market-data: started" 45 "market data" || fail "market data"

echo "== starting discovery"
$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/discovery.log" "discovery: started" 45 "discovery" || fail "discovery"

echo "== opening the market"
# The band below must sit inside this static collar, or every order is PRICE_OUT_OF_BOUNDS and the
# run measures the reject path. 5000bps against a 100.00 reference leaves plenty of room.
$MOST define --symbol "$SYMBOL" --reference 100.00 --static-collar 5000 --dynamic-collar 2000 \
  $CONN || fail "define $SYMBOL"
$MOST session --phase continuous --shard 0 $CONN || fail "session transition"
sleep 2

# ------------------------------------------------------------------- the sweep
run_load() { # run_load <rate> <count> <clordid-base> <outfile>
  $MOST load --symbol "$SYMBOL" --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
    --count "$2" --rate "$1" --participant 20 --participants 4 \
    --clordid-base "$3" --drain-ms 5000 --interval-ms 0 $CONN > "$4" 2>&1
}

echo
echo "== warmup pass ($WARMUP_ORDERS orders, discarded)"
run_load 100000 "$WARMUP_ORDERS" 90000000 "$RUN/load-warmup.out" \
  || fail "the warmup pass did not complete -- the market is not open, or the band is wrong"
grep -E "orders in|pacing" "$RUN/load-warmup.out" | sed 's/^/   /'
sleep 5

num() { echo "${1:-0}" | tr -d ','; }         # 300,000 -> 300000
pick() { # pick <file> <row-regex> <percentile>  e.g. pick f "ack  service" p50
  grep -E "^  $2" "$1" | sed -E "s/.*[^.0-9]$3=([0-9.]+).*/\1/" | head -1
}

ROWS=()
INVALID=0
BASE=100000
for rate in $RATES; do
  echo
  echo "===== $rate/s ====="
  OUT="$RUN/load-$rate.out"
  run_load "$rate" "$ORDERS" "$BASE" "$OUT" || fail "the $rate/s run did not complete"
  BASE=$((BASE + 10000000))

  ACHIEVED=$(grep -oE '\-\- [0-9,]+/s achieved' "$OUT" | head -1 | grep -oE '[0-9,]+')
  SVC_P50=$(pick "$OUT" "ack  service" p50); SVC_P90=$(pick "$OUT" "ack  service" p90)
  SVC_P99=$(pick "$OUT" "ack  service" p99)
  RSP_P50=$(pick "$OUT" "ack  response" p50); RSP_P99=$(pick "$OUT" "ack  response" p99)
  PACE_P999=$(pick "$OUT" "pacing" p99.9)
  REJECTED=$(num "$(grep -oE 'rejected=[0-9,]+' "$OUT" | head -1 | cut -d= -f2)")
  DROPPED=$(num "$(grep -oE 'dropped=[0-9,]+' "$OUT" | head -1 | cut -d= -f2)")
  UNANSWERED=$(num "$(grep -E '^  unanswered' "$OUT" | awk '{print $2}')")

  grep -E "orders in|^  offers|^  reports|^  unanswered|^  pacing|^  ack " "$OUT" | sed 's/^/   /'

  # --- validity. A row that fails either check is not a measurement of the shard.
  VERDICT="ok"
  if [ "$REJECTED" -ne 0 ]; then
    VERDICT="INVALID: $REJECTED rejects -- maxOrders too small, or the band is outside the collar"
    INVALID=1
  elif awk -v p="${PACE_P999:-0}" -v l="$PACING_LIMIT_US" 'BEGIN{exit !(p>l)}'; then
    VERDICT="INVALID: pacing lateness p99.9 ${PACE_P999}us -- the generator stalled, not the shard"
    INVALID=1
  fi
  [ "$VERDICT" = "ok" ] || echo "   ** $VERDICT"

  ROWS+=("$rate|$ACHIEVED|$SVC_P50|$SVC_P90|$SVC_P99|$RSP_P50|$RSP_P99|$PACE_P999|$UNANSWERED|$DROPPED|$VERDICT")
  sleep 3
done

# ---------------------------------------------------------------- the summary
echo
echo "== sweep"
printf "  %-9s %-11s %11s %11s %11s %11s %11s %10s %6s %6s\n" \
  target achieved "svc p50" "svc p90" "svc p99" "rsp p50" "rsp p99" "pace p999" unans drop
for row in "${ROWS[@]}"; do
  IFS='|' read -r rate ach s50 s90 s99 r50 r99 pace un dr verdict <<< "$row"
  printf "  %-9s %-11s %9sus %9sus %9sus %9sus %9sus %8sus %6s %6s%s\n" \
    "$rate" "$ach" "$s50" "$s90" "$s99" "$r50" "$r99" "$pace" "$un" "$dr" \
    "$([ "$verdict" = ok ] || echo "  <- ${verdict%%:*}")"
done

echo
echo "  Service and response agree while the generator keeps up; where they diverge, response is"
echo "  the honest number. Quoting only service time is coordinated omission."

# ------------------------------------------------- a row block for Measurements.md
COMMIT=$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)
DIRTY=$(cd "$ROOT" && git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
DATE=$(date +%Y-%m-%d)
if [ "$(uname)" = "Darwin" ]; then
  MACHINE="$(sysctl -n machdep.cpu.brand_string), macOS $(sw_vers -productVersion)"
  CORES=$(sysctl -n hw.ncpu)
else
  MACHINE="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//'), $(uname -sr)"
  CORES=$(nproc 2>/dev/null || echo '?')
fi
LOADAVG=$(uptime | sed -E 's/.*averages?: //')

echo
echo "== paste into docs/Measurements.md (check the idle column yourself)"
echo
n=0
for row in "${ROWS[@]}"; do
  IFS='|' read -r rate ach s50 s90 s99 r50 r99 pace un dr verdict <<< "$row"
  [ "$verdict" = ok ] || continue
  n=$((n + 1))
  printf '| R?%s | %s | `%s` | %s | %s | ? | JVM | 1 | %s/s | %s/s | %s µs | %s µs | %s µs | %s µs | %s µs | %s µs | %s | %s |\n' \
    "$(printf "\\$(printf '%03o' $((96 + n)))")" \
    "$DATE" "$COMMIT" "$MACHINE" "$CORES" "$rate" "$ach" \
    "$s50" "$s90" "$s99" "$r50" "$r99" "$pace" "$un" "$dr"
done
echo
echo "  conditions: single node, Aeron IPC, JVM start scripts, one security ($SYMBOL),"
echo "              maxOrders=$MAX_ORDERS, band 99.90-100.10 inside a 5000bps static collar,"
echo "              $ORDERS orders per rate, metrics off, $WARMUP_ORDERS-order discard pass first."
echo "              load average at finish: $LOADAVG"
[ "$DIRTY" -eq 0 ] || echo "              WARNING: $DIRTY uncommitted change(s) -- '$COMMIT' does not describe this build"

echo
if [ "$INVALID" -ne 0 ]; then
  echo "FAIL -- at least one rate measured something other than the shard. Do not record those rows."
  exit 1
fi
echo "PASS -- every rate is a measurement of the shard."
