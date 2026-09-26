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

# Who speaks for whom. The consensus module authenticates the gateway against this and stamps
# `gw-0` on the session as its encoded principal; the engine turns that principal back into a
# participant list at session open. Step 4c is what it buys: a maker that has said nothing since
# the gateway restarted is still sent its own fill.
sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="restart-e2e-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0,gw-1
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=7,8,9,11,12,13
gateway.gw-0.operator=true
gateway.gw-1.secret=$(sha256 "$GATEWAY_SECRET-b")
gateway.gw-1.participants=7,14
# §4d cancels participant 7 through gw-1, so gw-1 lists it too; gw-0 stays where its fills go.
participant.7.primary=gw-0
EOF
printf '%s\n' "$GATEWAY_SECRET-b" > "$RUN/gateway-b.secret"

write_engine_config() { # write_engine_config <securities file>
  cat > "$RUN/engine.properties" <<EOF
engine.securitiesFile=$1
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$AERON_DIR
engine.clusterDir=$RUN/cluster-host/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF
}

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

# A second gateway on the same shard, with its own client endpoints. Two gateways subscribed to
# ONE inbound channel would each receive every order and forward both -- duplicate orders, not
# redundancy -- so the endpoints are what has to be disjoint, not the state. There is no state.
cat > "$RUN/gateway-b.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$AERON_DIR
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=40
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=41
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=0=localhost:20110
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-1
gateway.credentialTokenFile=$RUN/gateway-b.secret
EOF

# Its own directory cycle, on its own stream, advertising gateway B's endpoints. The directory
# carries one order-entry channel per shard, so advertising two gateways for one shard is a second
# discovery rather than a second entry -- see docs/ProdDeployment.md 11.
cat > "$RUN/discovery-b.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$RUN/securities.properties
discovery.shard.0.orderEntryChannel=aeron:ipc
discovery.shard.0.orderEntryStreamId=40
discovery.shard.0.executionReportChannel=aeron:ipc
discovery.shard.0.executionReportStreamId=41
discovery.aeronDir=$AERON_DIR
discovery.channel=aeron:ipc
discovery.streamId=101
discovery.intervalMs=1000
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
    $MOST cluster --dir "$RUN/cluster-host" --aeron-dir "$AERON_DIR" \
      --participants "$RUN/participants.properties" > "$LOGS/cluster-$1.log" 2>&1 &
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
grep -q "participantRegistry=none" "$LOGS/engine-first.log" \
  && fail "the engine did not load the participant registry"
start_support first
grep -q "identity=gw-0" "$LOGS/gateway-first.log" \
  || { tail -10 "$LOGS/gateway-first.log" >&2; fail "the gateway has no cluster identity"; }

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
wait_for "$LOGS/engine-same.log" "restored 3 resting orders" 30 "the restore report" \
  || fail "the book was not restored from a snapshot"
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

# ------------------------------------------------------------------------- 4b
echo
echo "== 4b. restart ONLY the gateway -- a replacement holds no state and needs none"
# The engine states origQty and cumQty on every execution report (Design.md 3.1), so a gateway that
# has never seen an order still reports it correctly. This step used to prove the opposite thing:
# that the gateway's own memory-mapped journal handed the numbers back across its restart. That
# journal is gone, and the assertion below is the same one -- which is the point. The gateway is
# configured with no state file of any kind, so there is nothing here that could be recovering it.
# The partially filled order is the AGGRESSOR'"'"'s remainder rather than the resting side, which used
# to be forced: a maker'"'"'s fill was routed by participantId to the session that participant last
# spoke on, so a maker that had gone quiet across the restart was never told about its own fill.
# Step 4c is that case; this step keeps the aggressor shape so the two are measured separately.
# Participant 11, which has traded nothing here: the offer it will cross belongs to participant 7,
# and an aggressor that shares an smpId with the resting side is cancelled rather than filled.
# Sized past the whole offer, so what is left over rests with a real cumQty behind it.
$MOST send --symbol AAPL --side buy --price 102.00 --qty 26 --clordid 3002 --participant 11 \
  --follow 3 $CONN > "$RUN/rest.out" 2>&1 || fail "send aggressor"
cat "$RUN/rest.out"
grep -q "TRADE" "$RUN/rest.out" || { cat "$RUN/rest.out" >&2; fail "no partial fill"; }
REST_ID=$(grep -o "orderId=[0-9]*" "$RUN/rest.out" | head -1 | cut -d= -f2)
[ -n "$REST_ID" ] || { cat "$RUN/rest.out" >&2; fail "no orderId for the resting order"; }
pass "order $REST_ID rests with 6 of 26 remaining and 20 filled"

kill "$GATEWAY_PID" 2>/dev/null; wait "$GATEWAY_PID" 2>/dev/null; sleep 1
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway-alone.log" 2>&1 &
GATEWAY_PID=$!; PIDS+=("$GATEWAY_PID")
wait_for "$LOGS/gateway-alone.log" "gateway: started" 45 "gateway" || fail "gateway did not restart"
sleep 1

# Cancelling is the cheapest way to make the engine report on that order again. The reply has to
# carry both numbers: what was ordered, and how much of it filled. Neither is derivable from the
# report'"'"'s own leavesQty, which a cancel sets to zero whatever happened.
$MOST cancel --symbol AAPL --side buy --order-id "$REST_ID" --orig-clordid 3002 --participant 11 \
  --follow 3 $CONN > "$RUN/cancel.out" 2>&1 || fail "cancel after gateway restart"
cat "$RUN/cancel.out"
grep -q "CANCELED" "$RUN/cancel.out" || fail "the order was not cancelled"
grep -q "cum unknown" "$RUN/cancel.out" \
  && fail "the engine did not state origQty; a replacement gateway cannot invent it"
grep -q "cum 20 of 26" "$RUN/cancel.out" \
  || { cat "$RUN/cancel.out" >&2; fail "origQty and cumQty did not survive the gateway restart"; }
pass "the cancel reports cum 20 of 26 -- from a gateway that never saw the order"

# ------------------------------------------------------------------------- 4c
echo
echo "== 4c. a maker that has been quiet since the gateway restarted is still sent its fill"
# The other half of 4b, and the one holding origQty never addressed: a report has to reach a
# gateway at all. Participant 12 rests an offer and stops listening; the gateway
# is then restarted, so the session the engine learned participant 12 on is gone. Its route comes
# back only because the replacement session authenticates as gw-0 and the engine binds every
# participant the registry gives that gateway at session open.
$MOST send --symbol AAPL --side sell --price 103.00 --qty 10 --clordid 4001 --participant 12 \
  --follow 3 $CONN > "$RUN/quiet-maker.out" 2>&1 || fail "send the quiet maker's offer"
QUIET_ID=$(grep -o "orderId=[0-9]*" "$RUN/quiet-maker.out" | head -1 | cut -d= -f2)
[ -n "$QUIET_ID" ] || { cat "$RUN/quiet-maker.out" >&2; fail "no orderId for the quiet maker"; }

# Wait out the client publication's linger before restarting the gateway, or this step measures
# nothing. `most send` exits and closes its publication, but the media driver keeps it for the
# linger period (5s), so a gateway subscription created inside that window gets an image starting
# at the publication's *initial* position and re-forwards the order. The engine then re-learns the
# route from that replay and the fill is delivered whether or not anything was bound at session
# open -- which is exactly how the first version of this step passed with the registry removed.
sleep 7

kill "$GATEWAY_PID" 2>/dev/null; wait "$GATEWAY_PID" 2>/dev/null; sleep 1
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway-quiet.log" 2>&1 &
GATEWAY_PID=$!; PIDS+=("$GATEWAY_PID")
wait_for "$LOGS/gateway-quiet.log" "gateway: started" 45 "gateway" || fail "gateway did not restart"
sleep 1

# Participant 13, so self-match prevention does not cancel the aggressor instead of filling it.
$MOST send --symbol AAPL --side buy --price 103.00 --qty 4 --clordid 4002 --participant 13 \
  --follow 3 $CONN > "$RUN/quiet-taker.out" 2>&1 || fail "send the aggressor"
grep -q "TRADE" "$RUN/quiet-taker.out" || { cat "$RUN/quiet-taker.out" >&2; fail "no fill"; }
sleep 1

# The maker's own fill is not visible to anyone watching -- it stopped following. What proves it
# arrived is the gateway's arithmetic afterwards: cancelling the remainder reports the cumQty the
# gateway could only have accumulated from a fill report it was actually sent. Before the binding
# this read `cum 0 of 10`.
$MOST cancel --symbol AAPL --side sell --order-id "$QUIET_ID" --orig-clordid 4001 --participant 12 \
  --follow 3 $CONN > "$RUN/quiet-cancel.out" 2>&1 || fail "cancel the quiet maker's remainder"
cat "$RUN/quiet-cancel.out"
grep -q "cum 4 of 10" "$RUN/quiet-cancel.out" \
  || { cat "$RUN/quiet-cancel.out" >&2
       grep -ho "undeliverableReports=[0-9]*" "$LOGS"/engine-*.log >&2
       fail "the quiet maker was never told about its fill"; }
pass "the maker's fill reached the gateway across a restart it slept through"

# ------------------------------------------------------------------------- 4d
echo
echo "== 4d. two gateways at once -- place through one, cancel through the other"
# The check that gateway HA is actually solved rather than merely documented. Nothing in this repo
# ran two gateways against one shard before, because it could not usefully: origQty lived in one
# gateway's memory-mapped journal, so a second could only ever report UNKNOWN for an order the
# first had taken. The engine states both quantities now, so the second reports the same numbers
# as the first without having seen the order.
$GATEWAY "$RUN/gateway-b.properties" > "$LOGS/gateway-b.log" 2>&1 &
GATEWAY_B_PID=$!; PIDS+=("$GATEWAY_B_PID")
wait_for "$LOGS/gateway-b.log" "gateway: started" 45 "gateway B" || fail "second gateway"
$DISCOVERY "$RUN/discovery-b.properties" > "$LOGS/discovery-b.log" 2>&1 &
DISCOVERY_B_PID=$!; PIDS+=("$DISCOVERY_B_PID")
sleep 2

CONN_B="--aeron-dir $AERON_DIR --discovery-channel aeron:ipc --discovery-stream 101"

# Placed through gateway A, partially filled, then cancelled through gateway B.
$MOST send --symbol AAPL --side sell --price 104.00 --qty 12 --clordid 5001 --participant 7 \
  --follow 3 $CONN > "$RUN/two-a.out" 2>&1 || fail "send through gateway A"
TWO_ID=$(grep -o "orderId=[0-9]*" "$RUN/two-a.out" | head -1 | cut -d= -f2)
[ -n "$TWO_ID" ] || { cat "$RUN/two-a.out" >&2; fail "no orderId from gateway A"; }
$MOST send --symbol AAPL --side buy --price 104.00 --qty 5 --clordid 5002 --participant 13 \
  --follow 3 $CONN > "$RUN/two-fill.out" 2>&1 || fail "cross it"
grep -q "TRADE" "$RUN/two-fill.out" || { cat "$RUN/two-fill.out" >&2; fail "no fill"; }

$MOST cancel --symbol AAPL --side sell --order-id "$TWO_ID" --orig-clordid 5001 --participant 7 \
  --follow 3 $CONN_B > "$RUN/two-b.out" 2>&1 || fail "cancel through gateway B"
cat "$RUN/two-b.out"
grep -q "cum unknown" "$RUN/two-b.out" \
  && fail "gateway B could not report on an order gateway A took"
grep -q "cum 5 of 12" "$RUN/two-b.out" \
  || { cat "$RUN/two-b.out" >&2; fail "gateway B reported the wrong quantities"; }
pass "gateway B reported cum 5 of 12 for an order it never saw gateway A take"

# ------------------------------------------------------------------------- 4e
echo
echo "== 4e. rotate a gateway secret while the node runs -- no node restart"
# The consensus module and the engine re-read the registry on a poll. Rotating gw-1's secret and
# restarting only gateway B is the whole procedure; before this, changing who speaks for whom meant
# restarting the cluster host and the engine as well, which is a maintenance event on the cluster
# in order to add a client to it.
kill "$GATEWAY_B_PID" 2>/dev/null; wait "$GATEWAY_B_PID" 2>/dev/null

ROTATED="$GATEWAY_SECRET-rotated"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0,gw-1
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=7,8,9,11,12,13
gateway.gw-0.operator=true
gateway.gw-1.secret=$(sha256 "$ROTATED")
gateway.gw-1.participants=7,14
# §4d cancels participant 7 through gw-1, so gw-1 lists it too; gw-0 stays where its fills go.
participant.7.primary=gw-0
EOF

# Both node processes must say so. Waiting on the line rather than on a sleep is the point: a
# reload that happened silently would be invisible at exactly the moment someone is debugging one.
wait_for "$LOGS/cluster-same.log" "reloaded" 30 "cluster host registry reload" \
  || fail "the consensus module did not reload the registry"
wait_for "$LOGS/engine-same.log" "reloaded" 30 "engine registry reload" \
  || fail "the engine did not reload the registry"
grep -o "cluster: reloaded .*" "$LOGS/cluster-same.log" | tail -1

# The negative control, and without it this step is a guess: the OLD secret must now be refused.
# If the consensus module were still holding the file it booted with, this would connect happily.
printf '%s\n' "$GATEWAY_SECRET-b" > "$RUN/gateway-b.secret"
$GATEWAY "$RUN/gateway-b.properties" > "$LOGS/gateway-b-stale.log" 2>&1 &
STALE_PID=$!; PIDS+=("$STALE_PID")
i=0
while [ "$i" -lt 60 ]; do
  grep -q "gateway: started" "$LOGS/gateway-b-stale.log" 2>/dev/null \
    && fail "the rotated secret was not in force: a gateway presenting the old one still connected"
  sleep 0.25; i=$((i + 1))
done
kill "$STALE_PID" 2>/dev/null; wait "$STALE_PID" 2>/dev/null
pass "a gateway presenting the superseded secret is refused"

printf '%s\n' "$ROTATED" > "$RUN/gateway-b.secret"
$GATEWAY "$RUN/gateway-b.properties" > "$LOGS/gateway-b-rotated.log" 2>&1 &
GATEWAY_B_PID=$!; PIDS+=("$GATEWAY_B_PID")
wait_for "$LOGS/gateway-b-rotated.log" "gateway: started" 45 "gateway B (rotated secret)" \
  || { tail -10 "$LOGS/gateway-b-rotated.log" >&2
       fail "the rotated secret does not authenticate"; }
sleep 1
$MOST send --symbol AAPL --side sell --price 105.00 --qty 3 --clordid 6001 --participant 14 \
  --follow 3 $CONN_B > "$RUN/rotated.out" 2>&1 || fail "send through the rotated gateway B"
grep -q "NEW" "$RUN/rotated.out" \
  || { cat "$RUN/rotated.out" >&2; fail "the rotated gateway could not trade"; }
pass "the new secret authenticates and trades, with no node restarted"
ROTATED_ID=$(grep -o "orderId=[0-9]*" "$RUN/rotated.out" | head -1 | cut -d= -f2)

# ------------------------------------------------------------------------- 4f
echo
echo "== 4f. the gateway enforces the registry -- before the log, and without a restart"
# Design.md §1, "Enforcement, at the gateway". gw-1 lists 7 and 14 and is not an operator. Each
# refusal below is the gateway's own REJECTED; none of it reaches the cluster, which is what makes
# it legal to decide from a file the gateway re-reads on its own schedule.
$MOST send --symbol AAPL --side buy --price 99.00 --qty 1 --clordid 7001 --participant 8 \
  --follow 2 $CONN_B > "$RUN/unlisted.out" 2>&1 || fail "send an unlisted participant through B"
grep -q "REJECTED.*UNAUTHORIZED_PARTICIPANT" "$RUN/unlisted.out" \
  || { cat "$RUN/unlisted.out" >&2; fail "gateway B accepted a participant it does not list"; }
pass "participant 8 is not on gw-1, and gw-1 refused it UNAUTHORIZED_PARTICIPANT"

# An operator command through a gateway that is not an operator is consumed and counted. There is
# no client report for it, so the evidence is the counter at shutdown (below).
$MOST image --shard 0 $CONN_B > "$RUN/refused-image.out" 2>&1 || fail "send an image request through B"

# Revocation, gracefully: move 14 to cancelOnly on gw-1 and publish. The gateway must pick it up
# without a restart, refuse 14's new order, and still let 14 cancel what it has resting.
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0,gw-1
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=7,8,9,11,12,13
gateway.gw-0.operator=true
gateway.gw-1.secret=$(sha256 "$ROTATED")
gateway.gw-1.participants=7
gateway.gw-1.cancelOnly=14
participant.7.primary=gw-0
EOF
wait_for "$LOGS/gateway-b-rotated.log" "now participants=\\[7\\] cancelOnly=\\[14\\]" 30 "gateway B registry reload" \
  || fail "gateway B did not reload the registry"
$MOST send --symbol AAPL --side sell --price 105.00 --qty 2 --clordid 6002 --participant 14 \
  --follow 2 $CONN_B > "$RUN/revoked.out" 2>&1 || fail "send as a cancel-only participant"
grep -q "REJECTED.*UNAUTHORIZED_PARTICIPANT" "$RUN/revoked.out" \
  || { cat "$RUN/revoked.out" >&2; fail "a cancel-only participant could still place"; }
$MOST cancel --symbol AAPL --side sell --order-id "$ROTATED_ID" --orig-clordid 6001 --participant 14 \
  --follow 3 $CONN_B > "$RUN/revoked-cancel.out" 2>&1 || fail "cancel as a cancel-only participant"
grep -q "CANCELED" "$RUN/revoked-cancel.out" \
  || { cat "$RUN/revoked-cancel.out" >&2; fail "a cancel-only participant could not cancel"; }
pass "moved to cancelOnly by a reload, 14 could no longer place and could still cancel"

kill "$GATEWAY_B_PID" "$DISCOVERY_B_PID" 2>/dev/null
wait "$GATEWAY_B_PID" "$DISCOVERY_B_PID" 2>/dev/null
grep -q "refusedCommands=1 " "$LOGS/gateway-b-rotated.log" \
  || { grep "gateway: stopped" "$LOGS/gateway-b-rotated.log" >&2
       fail "gw-1 is not an operator, yet did not refuse the image request"; }
grep -q "unauthorizedRejects=2 " "$LOGS/gateway-b-rotated.log" \
  || { grep "gateway: stopped" "$LOGS/gateway-b-rotated.log" >&2
       fail "gw-1 did not count its two refusals"; }
pass "gw-1 refused the operator command and counted both refusals"

$MOST cluster snapshot --dir "$RUN/cluster-host" || fail "snapshot request"
sleep 2
stop_node
# The engine's own view of 4a-4f, printed at shutdown: every order and cancel it received came from a
# gateway that lists the participant. A gateway that forwarded one it should have refused would
# show here -- counted, never rejected, since the engine must not decide on a node-local file.
grep -q "undeclaredParticipantMessages=0 " "$LOGS/engine-same.log" \
  || { grep -o "undeclaredParticipantMessages=[0-9]*" "$LOGS/engine-same.log" >&2
       fail "the engine saw a participant its sending gateway does not list"; }
pass "the engine received nothing for a participant its gateway does not list"

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
# Waited for, not grepped once: the container prints "awaiting shutdown signal" as soon as it has
# launched, and the snapshot is loaded afterwards on the service's own thread. On a fast machine
# the restore line happens to land first; on a CI runner it did not.
wait_for "$LOGS/engine-drop.log" "security 2 left the shard; its book was empty" 30 "the drop report" \
  || fail "the drop was not reported"
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
wait_for "$LOGS/engine-add.log" "security 3 joined the shard; its book starts empty" 30 "the addition report" \
  || fail "the addition was not reported"
pass "a new security joined the shard with an empty book"

echo
echo "PASS: state survives a restart, and a geometry change that would destroy it does not."
