#!/usr/bin/env bash
# End-to-end smoke test: every process running, a real trade driven through the CLI.
#
# Single node, and IPC rather than multicast throughout -- this proves the components talk to each
# other, not that the network is configured. Production runs 3 or 5 cluster nodes and multicast
# feeds (Design.md §1, §5).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${E2E_DIR:-$ROOT/build/e2e}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"

rm -rf "$RUN"; mkdir -p "$LOGS"

# Each binary is overridable so the same run can be driven against a native image instead of
# the JVM start script -- e.g. ENGINE=engine/build/native/nativeCompile/matching-engine.
# The processes are identical on the wire, so a native binary must pass this unchanged.
MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
MARKETDATA="${MARKETDATA:-$ROOT/market-data/build/install/market-data/bin/market-data}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  sleep 1
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
cat > "$RUN/securities.properties" <<EOF
shard.id=0
shard.securities=1,2

security.1.symbol=AAPL
security.1.isin=US0378331005
security.1.name=Apple Inc.
security.1.currency=USD
security.1.priceFloor=0
security.1.tickSize=1000000
security.1.levelCount=32768
security.1.maxOrders=10000

security.2.symbol=MSFT
security.2.isin=US5949181045
security.2.name=Microsoft Corporation
security.2.currency=USD
security.2.priceFloor=0
security.2.tickSize=1000000
security.2.levelCount=32768
security.2.maxOrders=10000
EOF

cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
engine.metrics=true
engine.metrics.stages=true
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
# Short, because the whole point of the step below is to join late and not wait long for it.
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
$MOST cluster --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" > "$LOGS/cluster.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/cluster.log" "awaiting shutdown signal" 45 "cluster host" || fail "cluster host"

echo "== starting engine"
$ENGINE "$RUN/engine.properties" > "$LOGS/engine.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/engine.log" "awaiting shutdown signal" 45 "engine" || fail "engine"
grep -o "fingerprint=[0-9a-f]*" "$LOGS/engine.log" | head -1

echo "== starting gateway"
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"

echo "== starting market data"
$MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/market-data.log" "market-data: started" 30 "market data" || fail "market data"

echo "== starting discovery"
$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=($!)
wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

# ------------------------------------------------------------------- the tests
echo
echo "== 1. the universe is discoverable"
$MOST securities $CONN > "$RUN/securities.out" 2>&1 || fail "securities command"
cat "$RUN/securities.out"
grep -q "AAPL" "$RUN/securities.out" || fail "AAPL missing from the directory"
grep -q "MSFT" "$RUN/securities.out" || fail "MSFT missing from the directory"

echo
echo "== 2. open the session"
$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  || fail "define AAPL"
$MOST session --phase continuous --shard 0 $CONN || fail "session transition"
sleep 1

echo
echo "== 3. rest a sell order"
$MOST send --symbol AAPL --side sell --price 100.00 --qty 10 --clordid 1001 --participant 7 \
  --follow 3 $CONN > "$RUN/sell.out" 2>&1 || fail "send sell"
cat "$RUN/sell.out"
grep -q "NEW" "$RUN/sell.out" || fail "no NEW acknowledgement for the resting order"

echo
echo "== 4. cross it with a buy"
$MOST send --symbol AAPL --side buy --price 100.00 --qty 4 --clordid 2001 --participant 8 \
  --follow 3 $CONN > "$RUN/buy.out" 2>&1 || fail "send buy"
cat "$RUN/buy.out"
grep -q "TRADE" "$RUN/buy.out" || fail "the crossing order did not trade"
grep -q "cum 4" "$RUN/buy.out" || fail "gateway did not reconstruct cumQty"

echo
echo "== 5. start the book inspector AFTER the depth exists"
# The point of the recovery feed. Every increment that built this book was published before this
# subscriber existed, and a live feed has no replay -- so what it draws can only have come from a
# snapshot. Until the snapshot existed this step had to run before any depth, which meant the one
# case a real operator is always in was the one case never tested.
( $MOST book --symbol AAPL --depth 5 --refresh 500 $CONN > "$RUN/book.out" 2>&1 & echo $! > "$RUN/book.pid" )
sleep 3

echo "== 6. the book shows the remaining depth"
sleep 2
kill "$(cat "$RUN/book.pid")" 2>/dev/null
STRIP="s/\x1b\[[0-9;]*[A-Za-z]//g"
sed -e "$STRIP" "$RUN/book.out" | tail -12
sed -e "$STRIP" "$RUN/book.out" | grep -q "AAPL" || fail "no book rendered"
sed -e "$STRIP" "$RUN/book.out" | grep -q "100.00" || fail "depth missing the resting level"
# 10 sold, 4 traded away, so 6 must remain on the offer.
sed -e "$STRIP" "$RUN/book.out" | grep -qE "100\.00 +6 " || fail "expected 6 remaining on the offer"
sed -e "$STRIP" "$RUN/book.out" | grep -q "last 100.00 x 4" || fail "last trade not shown"

echo
echo "== 7. cancel the remainder"
$MOST cancel --symbol AAPL --side sell --order-id 1 --orig-clordid 1001 --participant 7 \
  --follow 3 $CONN > "$RUN/cancel.out" 2>&1 || fail "cancel"
cat "$RUN/cancel.out"
grep -q "CANCELED" "$RUN/cancel.out" || fail "no cancel confirmation"

echo
echo "== 8. drive load through the shard and measure it"
# Its own participant range and clOrdId base, so nothing collides with the orders above. Small
# enough not to slow the suite, but it exercises tryClaim encoding, correlation and the drain --
# every part of the harness that could silently report zeros.
$MOST load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
  --count 5000 --delay-us 200 --warmup 500 --participant 20 --participants 4 \
  --clordid-base 100000 --drain-ms 3000 $CONN > "$RUN/load.out" 2>&1 || fail "load"
cat "$RUN/load.out"
grep -q "5,000 orders in" "$RUN/load.out" || fail "load did not send every order"
grep -qE "unanswered +0 orders" "$RUN/load.out" || fail "some load orders never saw a report"
grep -qE "fills +0 qty traded" "$RUN/load.out" && fail "the load generated no trades at all"
grep -q "REJECTED" "$RUN/load.out" && fail "the load was rejected -- band or phase is wrong"
grep -qE "ack  service .*p50=" "$RUN/load.out" || fail "no latency percentiles reported"

echo
echo "== 9. the stage timings came back"
# The processes only print their latency summaries on an orderly shutdown, so this asks for one
# and then reads it. It is also the only check in this script that the instrumentation is wired at
# all: a metrics block that silently records nothing looks exactly like a fast engine.
kill -TERM "${PIDS[1]}" "${PIDS[2]}" 2>/dev/null
for _ in $(seq 1 40); do kill -0 "${PIDS[1]}" 2>/dev/null || break; sleep 0.25; done
sleep 1
grep -A12 "matching-engine: latency" "$LOGS/engine.log" | sed 's/^/  /'
grep -A4 "gateway: latency" "$LOGS/gateway.log" | sed 's/^/  /'
grep -q "matching-engine: latency" "$LOGS/engine.log" || fail "engine reported no latency summary"
grep -qE "newOrder +n=[1-9]" "$LOGS/engine.log" || fail "engine recorded no newOrder samples"
grep -qE "newOrder.admit +n=[1-9]" "$LOGS/engine.log" || fail "engine recorded no admit samples"
grep -qE "newOrder.settle +n=[1-9]" "$LOGS/engine.log" || fail "engine recorded no settle samples"
grep -qE "inbound +n=[1-9]" "$LOGS/gateway.log" || fail "gateway recorded no inbound samples"
[ -s "$RUN/engine-latency.hgrm" ] || fail "engine wrote no histogram file"
[ -s "$RUN/gateway-latency.hgrm" ] || fail "gateway wrote no histogram file"
echo "  histograms: $RUN/engine-latency.hgrm, $RUN/gateway-latency.hgrm"

echo
echo "== 10. no process died"
for name in cluster engine gateway market-data discovery; do
  grep -qiE "exception|error" "$LOGS/$name.log" && {
    echo "--- suspicious output in $name.log ---"; grep -iE "exception|error" "$LOGS/$name.log" | head -5; }
done

echo
echo "PASS -- all processes ran, a trade completed end to end, and the stages were measured"
