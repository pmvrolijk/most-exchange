#!/usr/bin/env bash
# Load sweep: how far a shard goes, and where it stops going there.
#
# Drives SECURITIES securities at a series of rates and reports both latencies at each, so the knee
# shows up as a shape rather than as a single number. `--rate` is the **aggregate** across the
# securities, because that is what the shard sees: SECURITIES=10 RATES=1000000 is the design's
# 100k/s/security at full fan-out (Design.md §2), and the default SECURITIES=1 is the one-book
# sweep every earlier measurement in docs/Measurements.md describes. `most load` measures the whole client round trip --
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
#   MAX_ORDERS       book capacity, per security          (default 1,000,000)
#   SECURITIES       how many securities to drive        (default 1, max 10)
#   PACING_LIMIT_US  p99.9 lateness above which a row is the harness (default 1000)
#   SATURATION_US    response p50 above which the shard is behind, not slow (default 10,000)
#   CLUSTER_HOST     where the consensus log and archive live (default $RUN/cluster-host)
#   INGRESS_TERM     cluster ingress term length, e.g. 64k or 16m   (default: the cluster's own)
#   DRIVER_THREADING   media driver threading: SHARED|SHARED_NETWORK|DEDICATED (default: SHARED)
#   ARCHIVE_THREADING  archive threading: SHARED|DEDICATED                     (default: SHARED)
#   GATEWAYS         gateways serving the shard, 1..5                         (default 1)
#   LOADERS          `most load` processes, each at RATE/LOADERS    (default: GATEWAYS)
#   EGRESS_CHANNEL   the gateway's cluster egress channel (default aeron:udp?endpoint=localhost:0).
#                    aeron:ipc takes execution reports off the driver's UDP sender, since the gateway
#                    and the cluster share one driver here; ...|mtu=8192 keeps UDP with bigger datagrams
#   PIN              a cpus.env (deploy/cloud/): one physical core per spinning thread (default: unset,
#                    no pinning -- the scheduler places everything, as on every run before it existed)
#
# GATEWAYS and LOADERS exist to ask whether the gateway is what caps the shard (Status.md §3 item 2).
# Loader j sends through gateway j % GATEWAYS, as participants 20+4j..23+4j, so every loader's
# participants are disjoint and each gateway's registry entry lists exactly the loaders it carries.
# Gateway i listens on client streams 20+2i / 21+2i; only gw-0 is in the directory and only gw-0 is
# an operator, so `most define` and `most session` still go the way they always did. Splitting the
# gateways also doubles the generators, so the control for GATEWAYS=2 LOADERS=2 is GATEWAYS=1
# LOADERS=2 -- the same generators through one gateway -- and a knee that moves in the first and not
# the second is the gateway's. Every loader shares one gateway's report stream in that control arm,
# and ignores (and counts) the reports for orders it did not send.
#
# Latency with several loaders is the WORST loader's percentile, not a merge: the per-loader files
# are kept beside the summary, and a row is invalid or saturated if any loader's is.
#
# CLUSTER_HOST is separate from the Aeron directory deliberately: `--dir` places the archive and the
# consensus log, `--aeron-dir` places the media driver's buffers. Pointing it at a RAM disk moves the
# durable writes and nothing else, which is how you find out whether storage is what caps the rate.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${SWEEP_DIR:-$ROOT/build/sweep}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"

RATES="${RATES:-50000 100000 200000 333000}"
ORDERS="${ORDERS:-300000}"
WARMUP_ORDERS="${WARMUP_ORDERS:-200000}"
MAX_ORDERS="${MAX_ORDERS:-1000000}"
SECURITIES="${SECURITIES:-1}"
PACING_LIMIT_US="${PACING_LIMIT_US:-1000}"
SATURATION_US="${SATURATION_US:-10000}"
CLUSTER_HOST="${CLUSTER_HOST:-$RUN/cluster-host}"
INGRESS_TERM="${INGRESS_TERM:-}"
DRIVER_THREADING="${DRIVER_THREADING:-}"
ARCHIVE_THREADING="${ARCHIVE_THREADING:-}"
GATEWAYS="${GATEWAYS:-1}"
EGRESS_CHANNEL="${EGRESS_CHANNEL:-aeron:udp?endpoint=localhost:0}"
LOADERS="${LOADERS:-$GATEWAYS}"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
MARKETDATA="${MARKETDATA:-$ROOT/market-data/build/install/market-data/bin/market-data}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"

# Defines ON_HOUSE / ON_LOAD / pin_threads; all three are no-ops when PIN is unset.
. "$ROOT/e2e/pin.sh"

rm -rf "$RUN"; mkdir -p "$LOGS"
# Only what this run owns: CLUSTER_HOST may be a mount point that must not be removed.
rm -rf "${CLUSTER_HOST:?}/archive" "${CLUSTER_HOST:?}/cluster" "${CLUSTER_HOST:?}/driver"
mkdir -p "$CLUSTER_HOST"

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
# The securities, drawn in order from a table of ten -- the design's maximum fan-out (Design.md §2,
# "≤ 10 securities per shard"). Real ISINs with real check digits, because `reference` validates them and a generated
# one would be refused at boot. Every security carries the same geometry and the same reference
# price, so one price band is inside every collar and the securities differ only in id.
SYMBOLS_ALL=(AAPL MSFT GOOGL AMZN NVDA META TSLA JPM JNJ XOM)
ISINS_ALL=(US0378331005 US5949181045 US02079K3059 US0231351067 US67066G1040
           US30303M1027 US88160R1014 US46625H1005 US4781601046 US30231G1022)
NAMES_ALL=("Apple Inc." "Microsoft Corp." "Alphabet Inc." "Amazon.com Inc." "NVIDIA Corp."
           "Meta Platforms Inc." "Tesla Inc." "JPMorgan Chase" "Johnson & Johnson" "Exxon Mobil")

[ "$SECURITIES" -ge 1 ] && [ "$SECURITIES" -le "${#SYMBOLS_ALL[@]}" ] \
  || fail "SECURITIES must be between 1 and ${#SYMBOLS_ALL[@]}, got $SECURITIES"
# Five, because gateway i's client streams are 20+2i and 21+2i, and 30 upward belong to market data.
[ "$GATEWAYS" -ge 1 ] && [ "$GATEWAYS" -le 5 ] || fail "GATEWAYS must be between 1 and 5, got $GATEWAYS"
[ "$LOADERS" -ge "$GATEWAYS" ] || fail "LOADERS ($LOADERS) must be at least GATEWAYS ($GATEWAYS), or a gateway carries nothing"

# `shard.securities` is the comma-separated list of security **ids**, not a count: ids 1..N here.
SYMBOLS=""
IDS=""
for i in $(seq 1 "$SECURITIES"); do IDS="${IDS:+$IDS,}$i"; done
{
  echo "shard.id=0"
  echo "shard.securities=$IDS"
  echo
  for i in $(seq 1 "$SECURITIES"); do
    idx=$((i - 1))
    echo "security.$i.symbol=${SYMBOLS_ALL[$idx]}"
    echo "security.$i.isin=${ISINS_ALL[$idx]}"
    echo "security.$i.name=${NAMES_ALL[$idx]}"
    echo "security.$i.currency=USD"
    echo "security.$i.priceFloor=0"
    echo "security.$i.tickSize=1000000"
    echo "security.$i.levelCount=32768"
    echo "security.$i.maxOrders=$MAX_ORDERS"
    echo
    SYMBOLS="${SYMBOLS:+$SYMBOLS,}${SYMBOLS_ALL[$idx]}"
  done
} > "$RUN/securities.properties"

sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="sweep-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
loader_participants() { # loader_participants <j> -> "20,21,22,23" for j=0
  local b=$((20 + 4 * $1)); echo "$b,$((b + 1)),$((b + 2)),$((b + 3))"
}
{
  echo "shard.id=0"
  GW_IDS=""
  for i in $(seq 0 $((GATEWAYS - 1))); do GW_IDS="${GW_IDS:+$GW_IDS,}gw-$i"; done
  echo "registry.gateways=$GW_IDS"
  for i in $(seq 0 $((GATEWAYS - 1))); do
    LISTED=""
    for j in $(seq 0 $((LOADERS - 1))); do
      [ $((j % GATEWAYS)) -eq "$i" ] && LISTED="${LISTED:+$LISTED,}$(loader_participants "$j")"
    done
    echo "gateway.gw-$i.secret=$(sha256 "$GATEWAY_SECRET")"
    echo "gateway.gw-$i.participants=$LISTED"
    [ "$i" -eq 0 ] && echo "gateway.gw-$i.operator=true"
  done
} > "$RUN/participants.properties"

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$CLUSTER_HOST/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
engine.metrics=false
EOF

for i in $(seq 0 $((GATEWAYS - 1))); do
cat > "$RUN/gateway-$i.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=$EGRESS_CHANNEL
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=$((20 + 2 * i))
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=$((21 + 2 * i))
gateway.metrics=false
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-$i
gateway.credentialTokenFile=$RUN/gateway.secret
EOF
done

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
$ON_HOUSE $MOST cluster --fresh --dir "$CLUSTER_HOST" --aeron-dir "$AERON_DIR" \
  ${INGRESS_TERM:+--ingress-term-length "$INGRESS_TERM"} \
  ${DRIVER_THREADING:+--driver-threading "$DRIVER_THREADING"} \
  ${ARCHIVE_THREADING:+--archive-threading "$ARCHIVE_THREADING"} \
  --participants "$RUN/participants.properties" > "$LOGS/cluster.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/cluster.log" "awaiting shutdown signal" 60 "cluster host" || fail "cluster host"

echo "== starting engine"
$ON_HOUSE $ENGINE "$RUN/engine.properties" > "$LOGS/engine.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/engine.log" "awaiting shutdown signal" 60 "engine" || fail "engine"
FINGERPRINT=$(grep -o "fingerprint=[0-9a-f]*" "$LOGS/engine.log" | head -1)
echo "   $FINGERPRINT"

for i in $(seq 0 $((GATEWAYS - 1))); do
  echo "== starting gateway gw-$i"
  $ON_HOUSE $GATEWAY "$RUN/gateway-$i.properties" > "$LOGS/gateway-$i.log" 2>&1 &
  PIDS+=($!)
  wait_for "$LOGS/gateway-$i.log" "gateway: started" 60 "gateway gw-$i" || fail "gateway gw-$i"
  grep -q "identity=gw-$i" "$LOGS/gateway-$i.log" || fail "gw-$i connected without an identity"
done

echo "== starting market data"
$ON_HOUSE $MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/market-data.log" "market-data: started" 45 "market data" || fail "market data"

echo "== starting discovery"
$ON_HOUSE $DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/discovery.log" "discovery: started" 45 "discovery" || fail "discovery"
pin_threads "${PIDS[@]}" || fail "pinning -- a run with an agent left unpinned is not the run PIN asked for"

echo "== opening the market"
# The band below must sit inside this static collar, or every order is PRICE_OUT_OF_BOUNDS and the
# run measures the reject path. 5000bps against a 100.00 reference leaves plenty of room.
# One definition per security: a SecurityDefinition names one security and nothing acknowledges
# one (open issue 9), so the only confirmation is that the run below does not reject.
for symbol in ${SYMBOLS//,/ }; do
  $MOST define --symbol "$symbol" --reference 100.00 --static-collar 5000 --dynamic-collar 2000 \
    $CONN || fail "define $symbol"
done
$MOST session --phase continuous --shard 0 $CONN || fail "session transition"
sleep 2

# ------------------------------------------------------------------- the sweep
run_load() { # run_load <aggregate rate> <aggregate count> <clordid-base> <out-prefix>
  # Every loader gets an equal share of the rate and the count, its own disjoint clOrdId range and
  # participants, and its own seed, so no two send the same order stream. All start together and the
  # call fails if any of them does. Writes <out-prefix>.<j>.out for each loader j.
  local per_rate=$(($1 / LOADERS)) per_count=$(($2 / LOADERS)) j gw pids=() rc=0
  for j in $(seq 0 $((LOADERS - 1))); do
    gw=$((j % GATEWAYS))
    $ON_LOAD $MOST load --symbol "$SYMBOLS" --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
      --count "$per_count" --rate "$per_rate" --participant $((20 + 4 * j)) --participants 4 \
      --seed $((42 + j)) --clordid-base $(($3 + j * per_count)) --drain-ms 5000 --interval-ms 0 \
      --order-entry-stream $((20 + 2 * gw)) --report-stream $((21 + 2 * gw)) \
      $CONN > "$4.$j.out" 2>&1 &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do wait "$pid" || rc=1; done
  return $rc
}

echo
echo "== warmup pass ($WARMUP_ORDERS orders, discarded)"
run_load 100000 "$WARMUP_ORDERS" 90000000 "$RUN/load-warmup" \
  || fail "the warmup pass did not complete -- the market is not open, or the band is wrong"
cat "$RUN"/load-warmup.*.out | grep -E "orders in|pacing" | sed 's/^/   /'
sleep 5

num() { echo "${1:-0}" | tr -d ','; }         # 300,000 -> 300000
pick() { # pick <file> <row-regex> <percentile>  e.g. pick f "ack  service" p50
  grep -E "^  $2" "$1" | sed -E "s/.*[^.0-9]$3=([0-9.]+).*/\1/" | head -1
}
worst() { # worst <out-prefix> <row-regex> <percentile> -- the highest across the loaders
  local f v m=""
  for f in "$1".*.out; do
    v=$(pick "$f" "$2" "$3")
    [ -n "$v" ] && { [ -z "$m" ] || awk -v a="$v" -v b="$m" 'BEGIN{exit !(a>b)}'; } && m="$v"
  done
  echo "$m"
}
total() { # total <out-prefix> <extended-regex> -- sums the first number in each loader's match
  local f s=0
  for f in "$1".*.out; do s=$((s + $(num "$(grep -oE "$2" "$f" | head -1 | grep -oE '[0-9][0-9,]*' | head -1)"))); done
  echo "$s"
}

ROWS=()
INVALID=0
SATURATED=0
LAST_GOOD=""
BASE=100000
for rate in $RATES; do
  echo
  echo "===== $rate/s ====="
  OUT="$RUN/load-$rate"
  run_load "$rate" "$ORDERS" "$BASE" "$OUT" || fail "the $rate/s run did not complete"
  BASE=$((BASE + 10000000))

  # Summed across loaders. Each ran for the same count at the same share of the rate from a common
  # start, so the sum is the aggregate the shard saw to within their start skew.
  ACHIEVED=$(total "$OUT" '\-\- [0-9,]+/s achieved')
  SVC_P50=$(worst "$OUT" "ack  service" p50); SVC_P90=$(worst "$OUT" "ack  service" p90)
  SVC_P99=$(worst "$OUT" "ack  service" p99)
  RSP_P50=$(worst "$OUT" "ack  response" p50); RSP_P99=$(worst "$OUT" "ack  response" p99)
  PACE_P999=$(worst "$OUT" "pacing" p99.9)
  REJECTED=$(total "$OUT" 'rejected=[0-9,]+')
  DROPPED=$(total "$OUT" 'dropped=[0-9,]+')
  UNANSWERED=$(total "$OUT" '^  unanswered +[0-9,]+')

  for f in "$OUT".*.out; do
    [ "$LOADERS" -gt 1 ] && echo "   -- loader ${f%.out}" | sed "s|$OUT.||"
    grep -E "orders in|^  offers|^  reports|^  unanswered|^  pacing|^  ack " "$f" | sed 's/^/   /'
  done

  # --- validity. A row that fails either of the first two checks is not a measurement of the
  # shard. The third is different: a saturated row IS a measurement, and it is the one the sweep
  # exists to find -- it just must never be read as a latency figure.
  VERDICT="ok"
  if [ "$REJECTED" -ne 0 ]; then
    VERDICT="INVALID: $REJECTED rejects -- maxOrders too small, or the band is outside the collar"
    INVALID=1
  elif awk -v p="${PACE_P999:-0}" -v l="$PACING_LIMIT_US" 'BEGIN{exit !(p>l)}'; then
    VERDICT="INVALID: pacing lateness p99.9 ${PACE_P999}us -- the generator stalled, not the shard"
    INVALID=1
  elif awk -v p="${RSP_P50:-0}" -v l="$SATURATION_US" 'BEGIN{exit !(p>l)}'; then
    # The generator was on schedule (checked above) and every order was answered, so this is not
    # slowness -- it is a queue draining after the offers stopped. Above the knee the "achieved"
    # figure is the offer rate, not a throughput the shard sustained.
    VERDICT="SATURATED: response p50 ${RSP_P50}us with the generator on schedule -- above the knee"
    SATURATED=1
  fi
  [ "$VERDICT" = "ok" ] || echo "   ** $VERDICT"

  [ "$VERDICT" = ok ] && LAST_GOOD="$rate"
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
  # Online CPUs, not nproc: under isolcpus nproc counts only what this shell may be scheduled on.
  CORES=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?')
fi
LOADAVG=$(uptime | sed -E 's/.*averages?: //')
TOP_RATE=$(echo $RATES | tr ' ' '\n' | sort -n | tail -1)
RATE_PER_SECURITY=$((TOP_RATE / SECURITIES))

echo
echo "== paste into docs/Measurements.md (check the idle column yourself)"
echo
n=0
for row in "${ROWS[@]}"; do
  IFS='|' read -r rate ach s50 s90 s99 r50 r99 pace un dr verdict <<< "$row"
  case "$verdict" in INVALID*) continue ;; esac
  n=$((n + 1))
  printf '| R?%s | %s | `%s` | %s | %s | ? | JVM | %s | %s/s | %s/s | %s µs | %s µs | %s µs | %s µs | %s µs | %s µs | %s | %s |\n' \
    "$(printf "\\$(printf '%03o' $((96 + n)))")" \
    "$DATE" "$COMMIT" "$MACHINE" "$CORES" "$SECURITIES" "$rate" "$ach" \
    "$s50" "$s90" "$s99" "$r50" "$r99" "$pace" "$un" "$dr"
  case "$verdict" in SATURATED*) echo "    ^ above the knee: a queue draining, not a round trip" ;; esac
done
echo
echo "  conditions: single node, Aeron IPC, JVM start scripts, $SECURITIES security(s): $SYMBOLS"
echo "              $GATEWAYS gateway(s), $LOADERS load generator(s)$([ "$LOADERS" -gt 1 ] && echo ', latency is the worst loader'),"
echo "              driver threading ${DRIVER_THREADING:-SHARED (default)}, archive threading ${ARCHIVE_THREADING:-SHARED (default)}"
echo "              rates are the AGGREGATE across them ($((RATE_PER_SECURITY))/s/security at the top rate),"
echo "              maxOrders=$MAX_ORDERS per security, band 99.90-100.10 inside a 5000bps static collar,"
echo "              $ORDERS orders per rate, metrics off, $WARMUP_ORDERS-order discard pass first."
echo "              load average at finish: $LOADAVG"
echo "              gateway egress channel: $EGRESS_CHANNEL"
echo "              pinning: $([ -n "${PIN:-}" ] && echo "one core per agent from $PIN (topology $TOPOLOGY), loaders on $LOADER_CPUS" || echo none)"
echo "              log+archive on $(df -h "$CLUSTER_HOST" | tail -1 | awk '{print $1}') ($CLUSTER_HOST)"
echo "              $(grep -m1 'ingress term length' "$LOGS/cluster.log" | sed 's/cluster: //')"
[ "$DIRTY" -eq 0 ] || echo "              WARNING: $DIRTY uncommitted change(s) -- '$COMMIT' does not describe this build"

echo
if [ -n "$LAST_GOOD" ]; then
  echo "  highest rate the shard kept up with: $LAST_GOOD/s aggregate"
  echo "  ($((LAST_GOOD / SECURITIES))/s/security across $SECURITIES). This is the ceiling this run found;"
  echo "  the rates above it are queueing, and their latencies are not round trips."
else
  echo "  the shard kept up with no rate in this sweep -- start lower."
fi

echo
if [ "$INVALID" -ne 0 ]; then
  echo "FAIL -- at least one rate measured something other than the shard. Do not record those rows."
  exit 1
fi
if [ "$SATURATED" -ne 0 ]; then
  echo "PASS -- every rate is a measurement of the shard, and the sweep bracketed the knee."
else
  echo "PASS -- every rate is a measurement of the shard, and none of them reached the knee."
fi
