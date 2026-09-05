#!/usr/bin/env bash
# Restart and geometry-reconciliation test: does the shard come back with its book?
#
# This is the check that nothing else makes. `run-e2e.sh` proves the processes talk to each other
# within one run; it wipes everything and starts fresh, so it has never once shown that state
# survives a restart. Until this script existed the answer was that it did not -- the cluster host
# deleted its archive and cluster directory on every start, and nothing ever asked for a snapshot,
# so a restart began from an empty book and said nothing about it.
#
# Seven steps. Step 4 proves the mechanism; steps 5 to 7 are the geometry rules, which is what makes
# reapplying geometry safe rather than quietly destructive.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${E2E_DIR:-$ROOT/build/e2e-restart}"
LOGS="$RUN/logs"
AERON_DIR="$RUN/aeron"

rm -rf "$RUN"; mkdir -p "$LOGS"

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
pass() { echo "  ok: $*"; }

wait_for() { # wait_for <file> <pattern> <seconds> <what>
  local file="$1" pattern="$2" secs="$3" what="$4" i=0
  while [ "$i" -lt "$((secs * 4))" ]; do
    [ -f "$file" ] && grep -q "$pattern" "$file" && return 0
    sleep 0.25; i=$((i + 1))
  done
  echo "--- $what did not start; tail of $file ---" >&2
  tail -30 "$file" >&2 2>/dev/null
  return 1
}

# ---------------------------------------------------------------- configuration
# Three security files: the one the shard starts on, one with a security removed, and one with a
# security added. Publishing a new file is exactly how geometry is reapplied.
security_block() { # security_block <id> <symbol> <isin> <name>
  cat <<EOF

security.$1.symbol=$2
security.$1.isin=$3
security.$1.name=$4
security.$1.currency=USD
security.$1.priceFloor=0
security.$1.tickSize=1000000
security.$1.levelCount=32768
security.$1.maxOrders=10000
EOF
}

{ echo "shard.id=0"; echo "shard.securities=1,2"
  security_block 1 AAPL US0378331005 "Apple Inc."
  security_block 2 MSFT US5949181045 "Microsoft Corporation"
} > "$RUN/securities.properties"

# MSFT removed. Legal only while its book is empty, which is the whole point of steps 5 and 6.
{ echo "shard.id=0"; echo "shard.securities=1"
  security_block 1 AAPL US0378331005 "Apple Inc."
} > "$RUN/securities-without-msft.properties"

# A third security joins the shard.
{ echo "shard.id=0"; echo "shard.securities=1,2,3"
  security_block 1 AAPL US0378331005 "Apple Inc."
  security_block 2 MSFT US5949181045 "Microsoft Corporation"
  security_block 3 GOOG US02079K3059 "Alphabet Inc."
} > "$RUN/securities-with-goog.properties"

write_engine_config() { # write_engine_config <securities file>
  cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$1
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF
}

cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.clientInboundChannel=aeron:ipc
gateway.clientInboundStreamId=20
gateway.clientOutboundChannel=aeron:ipc
gateway.clientOutboundStreamId=21
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
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
start_cluster() { # start_cluster <attempt>
  # NO --fresh: the archive and cluster directory are the resumption point, and keeping them is
  # what this script exists to exercise.
  #
  # Retried, because a node cannot restart immediately after the previous one stopped: Aeron's
  # archive and cluster mark files carry a liveness timestamp, and a new process refuses with
  # "active mark file detected" until it ages out. Measured at roughly ten seconds here even
  # after a clean shutdown. That is a fact about restarting a node, not about this script.
  local i=0
  while [ "$i" -lt 6 ]; do
    $MOST cluster --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" > "$LOGS/cluster-$1.log" 2>&1 &
    CLUSTER_PID=$!
    PIDS+=("$CLUSTER_PID")
    if wait_for "$LOGS/cluster-$1.log" "awaiting shutdown signal" 20 "cluster host"; then return 0; fi
    grep -q "active mark file detected\|Active media driver" "$LOGS/cluster-$1.log" || break
    echo "  (waiting for the previous node's mark files to age out)"
    kill "$CLUSTER_PID" 2>/dev/null; wait "$CLUSTER_PID" 2>/dev/null
    sleep 5
    i=$((i + 1))
  done
  fail "cluster host"
}

start_engine() { # start_engine <log suffix>
  $ENGINE "$RUN/engine.properties" > "$LOGS/engine-$1.log" 2>&1 &
  ENGINE_PID=$!
  PIDS+=("$ENGINE_PID")
}

# A separate log file per attempt, deliberately. `wait_for` greps a whole file, so appending to one
# log would leave the previous attempt's "started" marker in it and every later wait would return
# instantly -- including for a process that never came up.
start_support() { # start_support <attempt>
  $GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway-$1.log" 2>&1 &
  GATEWAY_PID=$!; PIDS+=("$GATEWAY_PID")
  wait_for "$LOGS/gateway-$1.log" "gateway: started" 45 "gateway" || fail "gateway"
  # Market data too: the book inspector reads L2 and the snapshot stream, both of which this
  # process derives. Without it there is no depth to compare across the restart.
  $MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data-$1.log" 2>&1 &
  MARKETDATA_PID=$!; PIDS+=("$MARKETDATA_PID")
  wait_for "$LOGS/market-data-$1.log" "market-data: started" 30 "market data" || fail "market data"
  $DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery-$1.log" 2>&1 &
  DISCOVERY_PID=$!; PIDS+=("$DISCOVERY_PID")
  wait_for "$LOGS/discovery-$1.log" "discovery: started" 30 "discovery" || fail "discovery"
}

# The whole node, consensus module included. Restarting only the service container is not a
# recovery: the consensus module keeps running and replays the log to the new service from the
# beginning, which rebuilds the same books by a completely different route and takes as long as
# the session is old. The case this script is about -- reapplying geometry, or losing every node
# at once -- always restarts the consensus module too.
stop_node() {
  kill "$ENGINE_PID" "$GATEWAY_PID" "$MARKETDATA_PID" "$DISCOVERY_PID" 2>/dev/null
  wait "$ENGINE_PID" 2>/dev/null
  sleep 1
  kill "$CLUSTER_PID" 2>/dev/null
  wait "$CLUSTER_PID" 2>/dev/null
  sleep 1
}

echo "== starting the shard"
start_cluster first
write_engine_config "$RUN/securities.properties"
start_engine first
wait_for "$LOGS/engine-first.log" "awaiting shutdown signal" 45 "engine" || fail "engine"
FINGERPRINT=$(grep -o "fingerprint=[0-9a-f]*" "$LOGS/engine-first.log" | head -1)
echo "  $FINGERPRINT"
start_support first

# --------------------------------------------------------------------- 1 and 2
echo
echo "== 1. rest orders on both sides of AAPL, and partially fill one"
$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  || fail "define AAPL"
$MOST define --symbol MSFT --reference 200.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  || fail "define MSFT"
$MOST session --phase continuous --shard 0 $CONN || fail "session transition"
sleep 1

$MOST send --symbol AAPL --side sell --price 101.00 --qty 10 --clordid 1001 --participant 7 \
  --follow 2 $CONN > "$RUN/sell1.out" 2>&1 || fail "send sell 1"
$MOST send --symbol AAPL --side sell --price 102.00 --qty 20 --clordid 1002 --participant 7 \
  --follow 2 $CONN > "$RUN/sell2.out" 2>&1 || fail "send sell 2"
$MOST send --symbol AAPL --side buy  --price  99.00 --qty 15 --clordid 1003 --participant 8 \
  --follow 2 $CONN > "$RUN/buy1.out" 2>&1 || fail "send buy 1"
# Crosses the 101.00 sell for 4 of its 10, leaving a partially filled resting order -- the case
# where leavesQty being a stored field rather than a derived one actually matters on restore.
$MOST send --symbol AAPL --side buy  --price 101.00 --qty 4 --clordid 1004 --participant 9 \
  --follow 2 $CONN > "$RUN/buy2.out" 2>&1 || fail "send buy 2"
grep -q "TRADE" "$RUN/buy2.out" || fail "the crossing order did not trade"
pass "three orders resting on AAPL, one of them partially filled"

echo
echo "== 2. record the book"
STRIP="s/\x1b\[[0-9;]*[A-Za-z]//g"
capture_book() { # capture_book <output file>
  ( $MOST book --symbol AAPL --depth 5 --refresh 500 $CONN > "$1.raw" 2>&1 & echo $! > "$RUN/book.pid" )
  sleep 4
  kill "$(cat "$RUN/book.pid")" 2>/dev/null
  # The distinct ladder rows, not the frames. The inspector redraws on a timer, so how many
  # identical frames land in a fixed window is a property of timing rather than of the book, and
  # comparing frame counts makes the check fail on a slow machine for no reason.
  sed -e "$STRIP" "$1.raw" | grep -E "^\s*[0-9]" | sort -u > "$1"
}
capture_book "$RUN/book-before.txt"
cat "$RUN/book-before.txt"
[ -s "$RUN/book-before.txt" ] || fail "no book rendered before the restart"

# ------------------------------------------------------------------------- 3
echo
echo "== 3. snapshot and stop the node"
# `cluster shutdown` snapshots and then stops, which is the point of having it: SIGTERM to the
# cluster host leaves no snapshot at all, and the next start replays from wherever the last one was.
$MOST cluster snapshot --dir "$RUN/cluster-host" || fail "snapshot request"
sleep 2
stop_node
pass "node stopped with a snapshot taken"

# ------------------------------------------------------------------------- 4
echo
echo "== 4. restart on the SAME security file -- the book must come back"
start_cluster same
write_engine_config "$RUN/securities.properties"
start_engine same
wait_for "$LOGS/engine-same.log" "awaiting shutdown signal" 45 "engine" || fail "engine did not restart"
start_support same
sleep 2

# The engine's own report, not a rendered book. A book rebuilt by replaying the whole log from
# genesis looks identical from outside and is a different operational event with a very different
# recovery time -- this line is the only thing that tells them apart.
grep -q "restored 3 resting orders" "$LOGS/engine-same.log" \
  || { tail -20 "$LOGS/engine-same.log" >&2; fail "the book was not restored from a snapshot"; }
grep -o "matching-engine: restored .*" "$LOGS/engine-same.log"
pass "three resting orders restored from the snapshot, with both sequences carried"

# Market data derives its books entirely from the book event stream, and a restore publishes no
# events for the orders it restored -- so without the book image the engine now sends on recovery,
# this comes back empty and stays empty until the next order arrives on that security. When this
# script was first written it reported that gap here instead of asserting it.
#
# Captured before the crossing order below, because that trade changes the book.
capture_book "$RUN/book-after.txt"
cat "$RUN/book-after.txt"
if ! diff -q "$RUN/book-before.txt" "$RUN/book-after.txt" > /dev/null; then
  echo "--- before ---"; cat "$RUN/book-before.txt"
  echo "--- after ----"; cat "$RUN/book-after.txt"
  fail "market data's book did not come back; the engine published no book image"
fi
pass "market data rebuilt the same book from the engine's image"

# The strongest evidence available: trade against a restored order. A resting sell of 10 was filled
# for 4 before the snapshot, so exactly 6 remain. If price, side, leavesQty or the ladder position
# came back wrong, this either does not trade or trades the wrong quantity.
$MOST send --symbol AAPL --side buy --price 101.00 --qty 6 --clordid 2001 --participant 9 \
  --follow 3 $CONN > "$RUN/cross.out" 2>&1 || fail "send crossing order"
cat "$RUN/cross.out"
grep -q "TRADE" "$RUN/cross.out" || fail "the crossing order did not trade against a restored order"
grep -q "cum 6" "$RUN/cross.out" || fail "the restored order did not carry its true leavesQty"
pass "a restored, partially filled order matched for exactly its remaining 6"

echo
echo "== 4a. restart ONLY market data -- an image on request is the way back"
# The other half, and the reason RequestBookImage exists. The engine is still running and has
# published its recovery image long ago, so a market data process starting now has missed it and
# has no snapshot of its own to fall back on. Nothing but asking will recover it.
# The book as it stands now, after that trade consumed the 101.00 offer.
capture_book "$RUN/book-live.txt"
[ -s "$RUN/book-live.txt" ] || fail "no book to compare against"

kill "$MARKETDATA_PID" 2>/dev/null; wait "$MARKETDATA_PID" 2>/dev/null; sleep 1
$MARKETDATA "$RUN/market-data.properties" > "$LOGS/market-data-alone.log" 2>&1 &
MARKETDATA_PID=$!; PIDS+=("$MARKETDATA_PID")
wait_for "$LOGS/market-data-alone.log" "market-data: started" 30 "market data" || fail "market data"
sleep 2

capture_book "$RUN/book-orphaned.txt"
if [ -s "$RUN/book-orphaned.txt" ] && diff -q "$RUN/book-live.txt" "$RUN/book-orphaned.txt" > /dev/null; then
  fail "market data somehow had the book already; this step is testing nothing"
fi
pass "a market data process that restarted alone has no book, as expected"

$MOST image --shard 0 $CONN > "$RUN/image.out" 2>&1 || fail "request book image"
cat "$RUN/image.out"
# The engine publishes on its next duty cycle, and market data republishes on receipt, so this is
# not a race in the system -- it is giving the CLI's own JVM time to start after the fact.
sleep 3
capture_book "$RUN/book-recovered.txt"
cat "$RUN/book-recovered.txt"
if ! diff -q "$RUN/book-live.txt" "$RUN/book-recovered.txt" > /dev/null; then
  echo "--- expected ---"; cat "$RUN/book-live.txt"
  echo "--- got --------"; cat "$RUN/book-recovered.txt"
  fail "the requested image did not rebuild the book"
fi
pass "most image rebuilt it without restarting the engine"

$MOST cluster snapshot --dir "$RUN/cluster-host" || fail "snapshot request"
sleep 2
stop_node

# ------------------------------------------------------------------------- 5
echo
echo "== 5. restart with MSFT removed while AAPL still holds orders -- must refuse"
# MSFT's book is empty, so removing MSFT is legal. Remove AAPL instead: it holds orders, and
# dropping them silently is the failure this whole change exists to prevent.
{ echo "shard.id=0"; echo "shard.securities=2"
  security_block 2 MSFT US5949181045 "Microsoft Corporation"
} > "$RUN/securities-without-aapl.properties"
start_cluster refuse
write_engine_config "$RUN/securities-without-aapl.properties"
start_engine refuse
sleep 6
if kill -0 "$ENGINE_PID" 2>/dev/null; then
  tail -20 "$LOGS/engine-refuse.log" >&2
  fail "the engine started despite dropping a book that holds orders"
fi
# Non-zero, so a supervisor or an orchestrator treats it as the failure it is rather than a
# clean stop. `wait` on an already-exited background job still yields its status.
wait "$ENGINE_PID" 2>/dev/null
REFUSE_STATUS=$?
[ "$REFUSE_STATUS" -ne 0 ] || fail "the engine exited 0 after refusing to restore"
grep -q "refused to restore its snapshot" "$LOGS/engine-refuse.log" \
  || { tail -20 "$LOGS/engine-refuse.log" >&2; fail "no refusal report"; }
grep -q "security 1 is no longer on this shard" "$LOGS/engine-refuse.log" \
  || { tail -20 "$LOGS/engine-refuse.log" >&2; fail "the refusal did not name the security"; }
cat "$LOGS/engine-refuse.log"
pass "refused, named the security, and exited"

# ------------------------------------------------------------------------- 6
echo
echo "== 6. restart with MSFT removed -- its book is empty, so this is allowed"
# A fresh consensus module each time, for the reason stop_node explains: a service container
# restarting against a live one replays the log rather than loading the snapshot, so it would
# never reach the reconciliation this step is about.
kill "$CLUSTER_PID" 2>/dev/null; wait "$CLUSTER_PID" 2>/dev/null; sleep 1
start_cluster drop
write_engine_config "$RUN/securities-without-msft.properties"
start_engine drop
wait_for "$LOGS/engine-drop.log" "awaiting shutdown signal" 45 "engine" || {
  tail -20 "$LOGS/engine-drop.log" >&2; fail "the engine refused to drop an empty book"; }
grep -q "security 2 left the shard; its book was empty" "$LOGS/engine-drop.log" \
  || { tail -20 "$LOGS/engine-drop.log" >&2; fail "the drop was not reported"; }
pass "an emptied security left the shard, with a line saying so"
kill "$ENGINE_PID" 2>/dev/null; wait "$ENGINE_PID" 2>/dev/null
kill "$CLUSTER_PID" 2>/dev/null; wait "$CLUSTER_PID" 2>/dev/null; sleep 1

# ------------------------------------------------------------------------- 7
echo
echo "== 7. restart with GOOG added -- it starts with an empty book"
start_cluster add
write_engine_config "$RUN/securities-with-goog.properties"
start_engine add
wait_for "$LOGS/engine-add.log" "awaiting shutdown signal" 45 "engine" || {
  tail -20 "$LOGS/engine-add.log" >&2; fail "the engine refused a newly added security"; }
grep -q "security 3 joined the shard; its book starts empty" "$LOGS/engine-add.log" \
  || { tail -20 "$LOGS/engine-add.log" >&2; fail "the addition was not reported"; }
pass "a new security joined the shard with an empty book"

echo
echo "PASS: state survives a restart, and a geometry change that would destroy it does not."
