#!/usr/bin/env bash
# Three-node test: does the shard survive losing its leader, and does a lost member come back?
#
# Every other script runs one node, so until this one existed the reasons Aeron Cluster was chosen
# -- election, replication, a snapshot taken through consensus restoring on another member -- had
# never once been exercised. Three members on one machine, each its own media driver, archive,
# consensus module and engine, on its own ports and directories. The gateway is independent: it
# runs on a fourth media driver and lists all three members, the way ProdDeployment.md §2 draws it.
#
# What this does not say: anything about throughput. Three nodes on one laptop share its cores and
# its loopback, so a rate measured here is a property of the laptop.
#
#   1. three members elect a leader
#   2. trade through the gateway; snapshot through consensus; rest an order after the snapshot
#   3. stop the leader's node -- a new leader is elected and the book is intact on it
#   4. restart the stopped member -- it restores from its snapshot and rejoins as a follower
#   5. stop the second leader as well -- the two left elect again, the book is still right
#
# LEADER_HEARTBEAT_TIMEOUT (e.g. 2s) shortens elections for a quick run; unset, Aeron's default.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${E2E_DIR:-$ROOT/build/e2e-cluster3}"
LOGS="$RUN/logs"
CLIENT_AERON="$RUN/client-aeron"

rm -rf "$RUN"; mkdir -p "$LOGS"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"
JAVA="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"

# Aeron reads its configuration from system properties, so one variable reaches every process.
if [ -n "${LEADER_HEARTBEAT_TIMEOUT:-}" ]; then
  export JAVA_OPTS="${JAVA_OPTS:-} -Daeron.cluster.leader.heartbeat.timeout=$LEADER_HEARTBEAT_TIMEOUT"
fi

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
  echo "--- $what did not happen; tail of $file ---" >&2
  tail -30 "$file" >&2 2>/dev/null
  return 1
}

# ---------------------------------------------------------------- configuration
MEMBERS="0,localhost:20110,localhost:20220,localhost:20330,localhost:20440,localhost:8010"
MEMBERS="$MEMBERS|1,localhost:20111,localhost:20221,localhost:20331,localhost:20441,localhost:8011"
MEMBERS="$MEMBERS|2,localhost:20112,localhost:20222,localhost:20332,localhost:20442,localhost:8012"
INGRESS="0=localhost:20110,1=localhost:20111,2=localhost:20112"

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

sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="cluster3-e2e-secret"
CONTROL_SECRET="cluster3-e2e-operator"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
printf '%s\n' "$CONTROL_SECRET" > "$RUN/control.secret"
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-0,control
gateway.gw-0.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-0.participants=7,8,9
gateway.gw-0.operator=true
gateway.control.secret=$(sha256 "$CONTROL_SECRET")
gateway.control.operator=true
EOF

for n in 0 1 2; do
  cat > "$RUN/engine-$n.properties" <<EOF
engine.securitiesFile=$RUN/securities.properties
engine.participantRegistry=$RUN/participants.properties
engine.aeronDir=$RUN/node$n/driver
engine.clusterDir=$RUN/node$n/cluster
engine.bookEvent.channel=aeron:ipc
engine.bookEvent.streamId=12
EOF
done

cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$CLIENT_AERON
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=$INGRESS
gateway.egressChannel=aeron:udp?endpoint=localhost:0
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-0
gateway.credentialTokenFile=$RUN/gateway.secret
EOF

cat > "$RUN/discovery.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$RUN/securities.properties
discovery.shard.0.orderEntryChannel=aeron:ipc
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=aeron:ipc
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=$CLIENT_AERON
discovery.channel=aeron:ipc
discovery.streamId=100
discovery.intervalMs=1000
EOF

CONN="--aeron-dir $CLIENT_AERON --discovery-channel aeron:ipc --discovery-stream 100"

# -------------------------------------------------------------------- processes
CLUSTER_PID=(); ENGINE_PID=()

start_node() { # start_node <member> <log suffix>
  local n="$1" i=0
  while [ "$i" -lt 6 ]; do
    $MOST cluster --dir "$RUN/node$n" --member-id "$n" --members "$MEMBERS" \
      --participants "$RUN/participants.properties" > "$LOGS/cluster-$n-$2.log" 2>&1 &
    CLUSTER_PID[$n]=$!; PIDS+=("${CLUSTER_PID[$n]}")
    if wait_for "$LOGS/cluster-$n-$2.log" "awaiting shutdown signal" 20 "cluster host $n"; then break; fi
    grep -q "active mark file detected\|Active media driver" "$LOGS/cluster-$n-$2.log" \
      || fail "cluster host $n"
    echo "  (waiting for member $n's mark files to age out)"
    kill "${CLUSTER_PID[$n]}" 2>/dev/null; wait "${CLUSTER_PID[$n]}" 2>/dev/null
    sleep 5; i=$((i + 1))
  done
  $ENGINE "$RUN/engine-$n.properties" > "$LOGS/engine-$n-$2.log" 2>&1 &
  ENGINE_PID[$n]=$!; PIDS+=("${ENGINE_PID[$n]}")
}

# A node is two processes and both go (CLAUDE.md, "Never stop the service container alone").
stop_node() { # stop_node <member>
  kill "${ENGINE_PID[$1]}" "${CLUSTER_PID[$1]}" 2>/dev/null
  wait "${ENGINE_PID[$1]}" "${CLUSTER_PID[$1]}" 2>/dev/null
}

# The engine prints `engine: role LEADER` from onRoleChange. The newest role line in a live
# member's current log is that member's role.
leader_of() { # leader_of <log suffix> <member>...
  local suffix="$1"; shift
  for n in "$@"; do
    local log="$LOGS/engine-$n-$suffix.log"
    [ -f "$log" ] && [ "$(grep -o 'engine: role [A-Z]*' "$log" | tail -1)" = "engine: role LEADER" ] \
      && { echo "$n"; return 0; }
  done
  return 1
}

await_leader() { # await_leader <seconds> <log suffix> <member>...
  local secs="$1" i=0; shift
  while [ "$i" -lt "$((secs * 4))" ]; do
    leader_of "$@" && return 0
    sleep 0.25; i=$((i + 1))
  done
  return 1
}

# ------------------------------------------------------------------------- 1
echo "== 1. three members elect a leader"
for n in 0 1 2; do start_node "$n" first; done
for n in 0 1 2; do
  wait_for "$LOGS/engine-$n-first.log" "awaiting shutdown signal" 45 "engine $n" || fail "engine $n"
done
LEADER=$(await_leader 30 first 0 1 2) || fail "no leader elected within 30 s"
pass "member $LEADER leads"
for n in 0 1 2; do
  [ "$n" = "$LEADER" ] && continue
  wait_for "$LOGS/engine-$n-first.log" "engine: role FOLLOWER" 10 "member $n following" \
    || fail "member $n never became a follower"
done
pass "the other two follow"
# Each member checks the leader's configuration from the log (Design.md §7).
for n in 0 1 2; do
  grep -q "refus" "$LOGS/engine-$n-first.log" && fail "member $n refused the leader's configuration"
done

"$JAVA" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "$ROOT/tools/build/install/most/lib/*" -Daeron.dir="$CLIENT_AERON" -Daeron.dir.delete.on.start=true \
  io.aeron.driver.MediaDriver > "$LOGS/client-driver.log" 2>&1 &
PIDS+=("$!")
sleep 2
$GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
PIDS+=("$!")
wait_for "$LOGS/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"
$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=("$!")
wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

# ------------------------------------------------------------------------- 2
echo
echo "== 2. trade, snapshot through consensus, rest an order after it"
$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  || fail "define AAPL"
$MOST session --phase continuous --shard 0 $CONN || fail "session transition"
sleep 1
$MOST send --symbol AAPL --side sell --price 101.00 --qty 10 --clordid 1001 --participant 7 \
  --follow 2 $CONN > "$RUN/sell1.out" 2>&1 || fail "send sell 1"
$MOST send --symbol AAPL --side buy --price 101.00 --qty 4 --clordid 1002 --participant 8 \
  --follow 2 $CONN > "$RUN/buy1.out" 2>&1 || fail "send buy 1"
grep -q "TRADE" "$RUN/buy1.out" || { cat "$RUN/buy1.out"; fail "the crossing order did not trade"; }
pass "sell 10 @ 101.00 rests, partially filled for 4"

$MOST cluster snapshot --ingress "$INGRESS" --aeron-dir "$CLIENT_AERON" \
  --identity control --secret-file "$RUN/control.secret" > "$RUN/snapshot.out" 2>&1
cat "$RUN/snapshot.out"
grep -q "snapshot taken" "$RUN/snapshot.out" || fail "the cluster did not confirm the snapshot"
pass "every member snapshotted at one log position (the cluster answered)"

# In the log tail only: a member restored from the snapshot has to replay or catch up to see it.
$MOST send --symbol AAPL --side sell --price 102.00 --qty 20 --clordid 1003 --participant 7 \
  --follow 2 $CONN > "$RUN/sell2.out" 2>&1 || fail "send sell 2"
pass "sell 20 @ 102.00 rests, after the snapshot"

# ------------------------------------------------------------------------- 3
echo
echo "== 3. stop the leader's node (member $LEADER)"
FIRST_LEADER=$LEADER
STOPPED_AT=$(date +%s)
stop_node "$FIRST_LEADER"
SURVIVORS=$(for n in 0 1 2; do [ "$n" != "$FIRST_LEADER" ] && printf '%s ' "$n"; done)
# shellcheck disable=SC2086
LEADER=$(await_leader 60 first $SURVIVORS) || fail "no new leader within 60 s"
echo "  member $LEADER leads, $(( $(date +%s) - STOPPED_AT )) s after the stop"
pass "a new leader was elected from the two survivors"

# The gateway follows the leader on its own, from the NewLeaderEvent. Retried, because an order
# sent while it is still reconnecting is rejected GATEWAY_UNAVAILABLE, which is correct.
crossed=0
for _ in $(seq 1 20); do
  $MOST send --symbol AAPL --side buy --price 102.00 --qty 16 --clordid "$((2000 + RANDOM % 1000))" \
    --participant 9 --follow 2 $CONN > "$RUN/cross1.out" 2>&1
  grep -q "TRADE" "$RUN/cross1.out" && { crossed=1; break; }
  sleep 1
done
cat "$RUN/cross1.out"
[ "$crossed" = 1 ] || fail "no trade through the gateway after the failover"
# 6 left at 101.00 (10 less the 4 traded before the snapshot), then 10 of the 20 at 102.00.
grep -q "cum 16" "$RUN/cross1.out" || fail "the new leader's book was not the old one's"
pass "the new leader's book matched for exactly 16: 6 @ 101.00, 10 @ 102.00"

# ------------------------------------------------------------------------- 4
echo
echo "== 4. restart member $FIRST_LEADER -- it restores, then catches up as a follower"
start_node "$FIRST_LEADER" rejoin
wait_for "$LOGS/engine-$FIRST_LEADER-rejoin.log" "restored 1 resting orders" 60 "the restore report" \
  || fail "member $FIRST_LEADER did not restore from its snapshot"
grep -o "matching-engine: restored .*" "$LOGS/engine-$FIRST_LEADER-rejoin.log"
# The book event sequence is replicated state (Design.md §5): a session transition, a rest and a
# trade had been generated before the snapshot, on every member. A member that numbered only what
# it published -- the leader's job alone -- would have snapshotted 1 here, and as a new leader
# would have restarted the feed's sequence. The first run of this script found exactly that.
SEQ=$(grep -o "nextBookEventSeqNum=[0-9]*" "$LOGS/engine-$FIRST_LEADER-rejoin.log" | head -1 | cut -d= -f2)
[ "${SEQ:-1}" -gt 1 ] || fail "the snapshot carried nextBookEventSeqNum=${SEQ:-?}: the sequence was not advanced on every node"
wait_for "$LOGS/engine-$FIRST_LEADER-rejoin.log" "engine: role FOLLOWER" 60 "member rejoining" \
  || fail "member $FIRST_LEADER did not rejoin as a follower"
pass "member $FIRST_LEADER restored the one order the snapshot held and follows"

# ------------------------------------------------------------------------- 5
echo
echo "== 5. stop the second leader (member $LEADER) -- the two left must agree"
SECOND_LEADER=$LEADER
stop_node "$SECOND_LEADER"
REMAINING=$(for n in 0 1 2; do [ "$n" != "$SECOND_LEADER" ] && printf '%s ' "$n"; done)
# The rejoined member logs to its `rejoin` file, the other survivor to `first`.
LEADER=""
for _ in $(seq 1 240); do
  for n in $REMAINING; do
    suffix=first; [ "$n" = "$FIRST_LEADER" ] && suffix=rejoin
    leader_of "$suffix" "$n" > /dev/null && { LEADER=$n; break 2; }
  done
  sleep 0.25
done
[ -n "$LEADER" ] || fail "no leader from the two remaining members within 60 s"
pass "member $LEADER leads$([ "$LEADER" = "$FIRST_LEADER" ] && echo ' -- the member that restored and caught up')"

# 10 left at 102.00. The member that restored from the snapshot holds the 102.00 order only if it
# caught up on the log tail, and holds 10 of it only if it applied the failover's trade too.
crossed=0
for _ in $(seq 1 20); do
  $MOST send --symbol AAPL --side buy --price 102.00 --qty 10 --clordid "$((3000 + RANDOM % 1000))" \
    --participant 9 --follow 2 $CONN > "$RUN/cross2.out" 2>&1
  grep -q "TRADE" "$RUN/cross2.out" && { crossed=1; break; }
  sleep 1
done
cat "$RUN/cross2.out"
[ "$crossed" = 1 ] || fail "no trade through the gateway after the second failover"
grep -q "cum 10" "$RUN/cross2.out" || fail "the book after the second failover was wrong"
pass "the last 10 @ 102.00 matched exactly"

echo
echo "PASS: three members elected, failed over twice, and a member rejoined from its snapshot"
