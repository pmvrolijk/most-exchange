#!/usr/bin/env bash
# Epsilon GC soak: Design.md §7 step 2, the empirical half.
#
# `--gc=epsilon` never collects, so under it any *steady-state* allocation is eventually fatal --
# and because the engine is deterministic it is fatal on every cluster node at the same log
# position. This measures what that allocation actually is.
#
# The method is a slope, not a total. SubstrateVM reports bytes allocated when the process exits,
# but most of that is startup: the order pool and the id map are ~96MB before a single order
# arrives, which would swamp any per-order figure and is exactly the sort of fixed cost that gets
# misreported as a rate. So the whole stack is run twice, at two different order counts, and the
# per-order allocation is the difference divided by the difference. Startup is identical in both
# runs and cancels out.
#
# This is the complement to engine's AllocationTest, not a replacement. That test attributes
# allocation precisely, per call, but cannot reach the book-event publication path, which needs a
# live media driver. This runs the real binary against a real driver, a real cluster and a real
# archive, so it covers what a fake cannot.
#
# Prerequisites:
#   ./gradlew installDist
#   ./gradlew :engine:nativeCompile -Pengine.useEpsilonGc=true
#   cp engine/build/native/nativeCompile/matching-engine \
#      engine/build/native/nativeCompile/matching-engine-epsilon
#
# Knobs (all overridable):
#   ORDERS       orders in the measured run    (default 2,000,000)
#   BASE_ORDERS  orders in the baseline run    (default 100,000)
#   DELAY_US     microseconds between sends    (default 10 => 100k/s)
#   HEAP         engine max heap               (default 256m)
#   MAX_ORDERS   book capacity per security    (default 1,000,000)
#   TOLERANCE    bytes/order the run may not exceed (default 1)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${SOAK_DIR:-$ROOT/build/epsilon-soak}"

ORDERS="${ORDERS:-2000000}"
BASE_ORDERS="${BASE_ORDERS:-100000}"
DELAY_US="${DELAY_US:-10}"
TOLERANCE="${TOLERANCE:-1}"
HEAP="${HEAP:-256m}"
MAX_ORDERS="${MAX_ORDERS:-1000000}"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/native/nativeCompile/matching-engine-epsilon}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
MARKETDATA="${MARKETDATA:-$ROOT/market-data/build/install/market-data/bin/market-data}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"

[ -x "$ENGINE" ] || {
  echo "FAIL: no engine binary at $ENGINE" >&2
  echo "  build one with: ./gradlew :engine:nativeCompile -Pengine.useEpsilonGc=true" >&2
  echo "  then: cp engine/build/native/nativeCompile/matching-engine \\" >&2
  echo "           engine/build/native/nativeCompile/matching-engine-epsilon" >&2
  exit 1
}

rm -rf "$RUN"; mkdir -p "$RUN"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  sleep 1
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; echo "--- logs under $RUN ---" >&2; exit 1; }

# Thousands separators, without depending on the shell's locale being set.
n() { printf "%s" "$1" | sed -e :a -e 's/\(.*[0-9]\)\([0-9]\{3\}\)/\1,\2/;ta'; }

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
# Written per run into that run's own directory, so the two runs share no Aeron dir, cluster dir
# or archive -- the baseline must not leave state that changes what the measured run allocates.
#
# One security, not two: the order pool dominates the heap, and halving it leaves more of the
# bounded heap as headroom for the thing being measured.
write_config() { # write_config <dir> <aeron-dir>
  local dir="$1" aeron="$2"
  cat > "$dir/securities.properties" <<EOF
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

  cat > "$dir/engine.properties" <<EOF
engine.securitiesFile=$dir/securities.properties
engine.aeronDir=$aeron
engine.clusterDir=$dir/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF

  cat > "$dir/gateway.properties" <<EOF
gateway.securitiesFile=$dir/securities.properties
gateway.aeronDir=$aeron
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
EOF

  cat > "$dir/market-data.properties" <<EOF
md.securitiesFile=$dir/securities.properties
md.aeronDir=$aeron
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

  cat > "$dir/discovery.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$dir/securities.properties
discovery.shard.0.orderEntryChannel=aeron:ipc
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=aeron:ipc
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=$aeron
discovery.channel=aeron:ipc
discovery.streamId=100
discovery.intervalMs=1000
EOF
}

# -------------------------------------------------------------------- one run
# Starts the whole stack, drives `orders` through it, stops the engine gracefully so SubstrateVM
# prints its allocation summary, and echoes the bytes it reports. Everything is torn down again,
# so two calls are independent and differ only in the order count.
ALLOCATED=0
run_stack() { # run_stack <orders> <label>
  local orders="$1" label="$2"
  local dir="$RUN/$label"
  local logs="$dir/logs" aeron="$dir/aeron"
  mkdir -p "$logs"
  write_config "$dir" "$aeron"

  local conn="--aeron-dir $aeron --discovery-channel aeron:ipc --discovery-stream 100
      --l1-channel aeron:ipc --l1-stream 31 --l2-channel aeron:ipc --l2-stream 32
      --snapshot-channel aeron:ipc --snapshot-stream 34"

  echo "-- $label: $(n $orders) orders"

  $MOST cluster --dir "$dir/cluster-host" --aeron-dir "$aeron" > "$logs/cluster.log" 2>&1 &
  local cluster_pid=$!; PIDS+=($cluster_pid)
  wait_for "$logs/cluster.log" "awaiting shutdown signal" 45 "cluster host" || fail "cluster host"

  # -XX:+PrintGCSummary is what makes this quantitative: SubstrateVM prints total bytes allocated
  # when the process exits. The engine must therefore be stopped gracefully, never killed --
  # which only works because the image is built with --install-exit-handlers (see build.gradle.kts).
  $ENGINE -Xmx"$HEAP" -XX:+PrintGCSummary "$dir/engine.properties" > "$logs/engine.log" 2>&1 &
  local engine_pid=$!; PIDS+=($engine_pid)
  wait_for "$logs/engine.log" "awaiting shutdown signal" 45 "engine" || fail "engine"

  $GATEWAY "$dir/gateway.properties" > "$logs/gateway.log" 2>&1 &
  PIDS+=($!)
  wait_for "$logs/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"

  $MARKETDATA "$dir/market-data.properties" > "$logs/market-data.log" 2>&1 &
  PIDS+=($!)
  wait_for "$logs/market-data.log" "market-data: started" 30 "market data" || fail "market data"

  $DISCOVERY "$dir/discovery.properties" > "$logs/discovery.log" 2>&1 &
  local discovery_pid=$!; PIDS+=($discovery_pid)
  wait_for "$logs/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

  $MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $conn \
    > "$logs/define.log" 2>&1 || fail "define AAPL"
  $MOST session --phase continuous --shard 0 $conn > "$logs/session.log" 2>&1 || fail "session"
  sleep 1

  $MOST load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
    --count "$orders" --delay-us "$DELAY_US" --warmup 1000 --participant 20 --participants 4 \
    --clordid-base 100000 --drain-ms 20000 $conn > "$dir/load.out" 2>&1 || fail "load"

  # The run is only evidence if the orders actually reached the engine and matched.
  kill -0 "$engine_pid" 2>/dev/null || {
    tail -30 "$logs/engine.log" >&2
    fail "the engine did not survive $label -- steady-state allocation is NOT zero"
  }
  # Not "exactly zero". The gateway's outbound leg drops rather than blocks under pressure and
  # counts what it dropped -- deliberately, because egress must keep being drained or the cluster
  # session dies (CLAUDE.md). At 100k/s that shows up as a handful of orders in millions. What
  # this gate is actually for is catching a run where the load never reached the engine at all,
  # so it bounds the fraction rather than demanding a zero the design does not promise.
  local unanswered
  unanswered=$(grep -oE "unanswered +[0-9,]+ orders" "$dir/load.out" | grep -oE "[0-9,]+" | tr -d ',')
  [ -n "$unanswered" ] || fail "$label: the load run reported no answer count"
  if [ "$((unanswered * 10000))" -gt "$orders" ]; then
    fail "$label: $unanswered of $orders orders unanswered (over 0.01%) -- the run is not evidence"
  fi
  [ "$unanswered" -eq 0 ] || echo "     ($unanswered of $(n "$orders") unanswered: gateway outbound drops, by design)"
  grep -qE "fills +0 qty traded" "$dir/load.out" && fail "$label: no trades, matching never ran"
  grep -q "REJECTED" "$dir/load.out" && fail "$label: orders rejected -- band or phase is wrong"
  grep -qiE "OutOfMemory|heap space" "$logs/engine.log" && fail "$label: heap exhaustion"

  grep -E "achieved|fills " "$dir/load.out" | sed 's/^/     /'

  # Stop the engine alone, gracefully, and wait for its summary.
  kill -TERM "$engine_pid" 2>/dev/null
  for _ in $(seq 1 60); do kill -0 "$engine_pid" 2>/dev/null || break; sleep 0.25; done
  grep -q "shutdown signal received" "$logs/engine.log" ||
    fail "$label: the engine did not shut down orderly, so its summary cannot be trusted"

  local megs
  megs=$(grep -oE "Allocated object bytes: [0-9.]+M" "$logs/engine.log" | tail -1 |
         grep -oE "[0-9.]+")
  [ -n "$megs" ] || fail "$label: no allocation summary in the engine log"
  ALLOCATED=$(awk -v m="$megs" 'BEGIN { printf "%.0f", m * 1048576 }')
  echo "     allocated $megs MB total (startup included)"

  # Tear the whole stack down so the next run is independent.
  for pid in "${PIDS[@]:-}"; do kill -TERM "$pid" 2>/dev/null; done
  sleep 2
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  PIDS=()
  sleep 1
}

# ------------------------------------------------------------------- the soak
echo "== configuration"
echo "  engine     $ENGINE"
echo "  heap       $HEAP (Epsilon: never collected)"
echo "  runs       $(n $BASE_ORDERS) then $(n $ORDERS) orders, ${DELAY_US}us apart"
echo "  maxOrders  $(n $MAX_ORDERS) per security"
echo

run_stack "$BASE_ORDERS" baseline
BASE_ALLOC=$ALLOCATED
echo
run_stack "$ORDERS" measured
FULL_ALLOC=$ALLOCATED

# ------------------------------------------------------------------- the verdict
echo
echo "== verdict"
DELTA_ORDERS=$((ORDERS - BASE_ORDERS))
DELTA_BYTES=$((FULL_ALLOC - BASE_ALLOC))
PER_ORDER=$(awk -v b="$DELTA_BYTES" -v n="$DELTA_ORDERS" 'BEGIN { printf "%.4f", b / n }')

echo "  baseline   $(n $BASE_ORDERS) orders -> $(n $BASE_ALLOC) bytes allocated"
echo "  measured   $(n $ORDERS) orders -> $(n $FULL_ALLOC) bytes allocated"
echo "  slope      $(n $DELTA_BYTES) bytes over $(n $DELTA_ORDERS) extra orders"
echo "             = $PER_ORDER bytes per order"
echo
echo "  Startup is identical in both runs, so the slope is the steady-state cost and the"
echo "  ~$(awk -v b="$BASE_ALLOC" 'BEGIN { printf "%.0f", b / 1048576 }')MB of pools and maps in the intercept is correctly excluded."

WITHIN=$(awk -v p="$PER_ORDER" -v t="$TOLERANCE" 'BEGIN { print (p <= t && p >= -t) ? "yes" : "no" }')
[ "$WITHIN" = yes ] || {
  echo
  echo "FAIL: $PER_ORDER bytes per order exceeds the $TOLERANCE byte tolerance." >&2
  echo "  Under --gc=epsilon that is a heap exhaustion with a deadline, on every node at once." >&2
  exit 1
}

echo
echo "PASS -- steady-state allocation is $PER_ORDER bytes per order, within the $TOLERANCE byte tolerance."
echo "  For scale, the regression engine's AllocationTest catches -- 232 bytes an order, from a"
echo "  match callback that stops being inlined -- would be $(awk -v n="$DELTA_ORDERS" 'BEGIN { printf "%.0f", 232 * n / 1048576 }')MB across this run's extra orders."
