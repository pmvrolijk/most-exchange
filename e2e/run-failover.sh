#!/usr/bin/env bash
# Failover by gateway placement: what does losing the leader cost a client, in each placement?
#
#   PLACEMENT=independent  one gateway on its own media driver, listing every member. Aeron's client
#                          follows the leader; execution reports come back over UDP.
#   PLACEMENT=colocated    a gateway on every node's media driver (gateway.placement=colocated),
#                          active only while that node leads, IPC both ways. Clients hold every
#                          node's endpoints and move on GATEWAY_UNAVAILABLE (Design.md §7).
#
# Three members on one machine, as in run-cluster3.sh. Two failovers:
#
#   1. a maker rests before the first one and is filled after it -- its fill must reach it, which in
#      the co-located placement means the new node's gateway took its route at session open
#   2. load runs straight through the second one -- what it cost, from the client's side: orders
#      refused GATEWAY_UNAVAILABLE, gateway switches, orders never answered, and the reports per
#      second either side of the stop
#
# Single-machine, so nothing here is a latency or a throughput figure: it is how a failover behaves.
# LEADER_HEARTBEAT_TIMEOUT (e.g. 2s) shortens elections; unset, Aeron's default (10 s) is the floor
# of every gap this prints.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLACEMENT="${PLACEMENT:-colocated}"
case "$PLACEMENT" in independent|colocated) ;; *) echo "PLACEMENT must be independent or colocated" >&2; exit 2;; esac
RUN="${E2E_DIR:-$ROOT/build/e2e-failover-$PLACEMENT}"
LOGS="$RUN/logs"
CLIENT_AERON="$RUN/client-aeron"
RATE="${RATE:-2000}"            # orders per second through the second failover
LOAD_SECONDS="${LOAD_SECONDS:-20}"
STOP_AFTER="${STOP_AFTER:-5}"   # seconds into the load before the leader is stopped
EGRESS_CHANNEL="${EGRESS_CHANNEL:-aeron:udp?endpoint=localhost:0}"   # independent only

rm -rf "$RUN"; mkdir -p "$LOGS"

MOST="${MOST:-$ROOT/tools/build/install/most/bin/most}"
ENGINE="${ENGINE:-$ROOT/engine/build/install/engine/bin/engine}"
GATEWAY="${GATEWAY:-$ROOT/gateway/build/install/gateway/bin/gateway}"
DISCOVERY="${DISCOVERY:-$ROOT/discovery/build/install/discovery/bin/discovery}"
JAVA="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"

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
security.1.maxOrders=100000
EOF

sha256() { # sha256 <text>
  if command -v sha256sum > /dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi
}
GATEWAY_SECRET="failover-e2e-secret"
printf '%s\n' "$GATEWAY_SECRET" > "$RUN/gateway.secret"
# One identity for the shard's gateway, whichever node it runs on: in the co-located placement the
# three node gateways present it in turn, and being the participants' primary is what lets the one
# that has just connected take their routes at once (Design.md §1, §7).
cat > "$RUN/participants.properties" <<EOF
shard.id=0
registry.gateways=gw-shard
gateway.gw-shard.secret=$(sha256 "$GATEWAY_SECRET")
gateway.gw-shard.participants=7,9,20,21,22,23
gateway.gw-shard.operator=true
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

# A gateway's client endpoints, and the channel a client subscribes to its reports on.
gw_in()  { echo "aeron:udp?endpoint=localhost:2100$1"; }
gw_out() { echo "aeron:udp?control=localhost:2200$1|control-mode=dynamic"; }
gw_sub() { echo "aeron:udp?endpoint=localhost:0|control=localhost:2200$1|control-mode=dynamic"; }

if [ "$PLACEMENT" = colocated ]; then
  for n in 0 1 2; do
    cat > "$RUN/gateway-$n.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.placement=colocated
gateway.aeronDir=$RUN/node$n/driver
gateway.client.inbound.channel=$(gw_in "$n")
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=$(gw_out "$n")
gateway.client.outbound.streamId=21
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-shard
gateway.credentialTokenFile=$RUN/gateway.secret
EOF
  done
  ROUTE_IN=$(gw_in 0); ROUTE_OUT=$(gw_sub 0)
  ALL_GATEWAYS="--order-entry-channel $(gw_in 0),$(gw_in 1),$(gw_in 2) --report-channel $(gw_sub 0),$(gw_sub 1),$(gw_sub 2)"
else
  cat > "$RUN/gateway.properties" <<EOF
gateway.securitiesFile=$RUN/securities.properties
gateway.aeronDir=$CLIENT_AERON
gateway.client.inbound.channel=aeron:ipc
gateway.client.inbound.streamId=20
gateway.client.outbound.channel=aeron:ipc
gateway.client.outbound.streamId=21
gateway.ingressChannel=aeron:udp
gateway.ingressEndpoints=$INGRESS
gateway.egressChannel=$EGRESS_CHANNEL
gateway.participantRegistry=$RUN/participants.properties
gateway.gatewayId=gw-shard
gateway.credentialTokenFile=$RUN/gateway.secret
EOF
  ROUTE_IN=aeron:ipc; ROUTE_OUT=aeron:ipc
  ALL_GATEWAYS=""
fi

cat > "$RUN/discovery.properties" <<EOF
discovery.shards=0
discovery.shard.0.securitiesFile=$RUN/securities.properties
discovery.shard.0.orderEntryChannel=$ROUTE_IN
discovery.shard.0.orderEntryStreamId=20
discovery.shard.0.executionReportChannel=$ROUTE_OUT
discovery.shard.0.executionReportStreamId=21
discovery.aeronDir=$CLIENT_AERON
discovery.channel=aeron:ipc
discovery.streamId=100
discovery.intervalMs=1000
EOF

CONN="--aeron-dir $CLIENT_AERON --discovery-channel aeron:ipc --discovery-stream 100"

# The one gateway that can act right now: the leader's node's, or the independent one.
via_leader() { # via_leader <member>
  if [ "$PLACEMENT" = colocated ]; then echo "--order-entry-channel $(gw_in "$1") --report-channel $(gw_sub "$1")"; fi
}

# -------------------------------------------------------------------- processes
CLUSTER_PID=(); ENGINE_PID=(); GATEWAY_PID=()

start_node() { # start_node <member> <log suffix>
  local n="$1" i=0 ipc=""
  [ "$PLACEMENT" = colocated ] && ipc="--ipc-ingress"
  while [ "$i" -lt 6 ]; do
    $MOST cluster --dir "$RUN/node$n" --member-id "$n" --members "$MEMBERS" $ipc \
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
  if [ "$PLACEMENT" = colocated ]; then
    $GATEWAY "$RUN/gateway-$n.properties" > "$LOGS/gateway-$n-$2.log" 2>&1 &
    GATEWAY_PID[$n]=$!; PIDS+=("${GATEWAY_PID[$n]}")
  fi
}

# The machine goes: its gateway too, when it has one. Engine first, so it prints its counters.
stop_node() { # stop_node <member>
  kill "${ENGINE_PID[$1]}" 2>/dev/null; wait "${ENGINE_PID[$1]}" 2>/dev/null
  kill "${CLUSTER_PID[$1]}" "${GATEWAY_PID[$1]:-}" 2>/dev/null
  wait "${CLUSTER_PID[$1]}" "${GATEWAY_PID[$1]:-}" 2>/dev/null
}

SUFFIX=(first first first)

leader_among() { # leader_among <member>...
  for n in "$@"; do
    local log="$LOGS/engine-$n-${SUFFIX[$n]}.log"
    [ -f "$log" ] && [ "$(grep -o 'engine: role [A-Z]*' "$log" | tail -1)" = "engine: role LEADER" ] \
      && { echo "$n"; return 0; }
  done
  return 1
}

await_leader() { # await_leader <seconds> <member>...
  local secs="$1" i=0; shift
  while [ "$i" -lt "$((secs * 4))" ]; do
    leader_among "$@" && return 0
    sleep 0.25; i=$((i + 1))
  done
  return 1
}

others() { for n in 0 1 2; do [ "$n" != "$1" ] && printf '%s ' "$n"; done; }

# ------------------------------------------------------------------------- 0
echo "== 0. three members, placement=$PLACEMENT"
"$JAVA" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "$ROOT/tools/build/install/most/lib/*" -Daeron.dir="$CLIENT_AERON" -Daeron.dir.delete.on.start=true \
  io.aeron.driver.MediaDriver > "$LOGS/client-driver.log" 2>&1 &
PIDS+=("$!")
for n in 0 1 2; do start_node "$n" first; done
LEADER=$(await_leader 45 0 1 2) || fail "no leader elected"
pass "member $LEADER leads"

if [ "$PLACEMENT" = colocated ]; then
  wait_for "$LOGS/gateway-$LEADER-first.log" "gateway: active" 30 "the leader's gateway activating" \
    || fail "the leader's gateway did not connect"
  for n in $(others "$LEADER"); do
    grep -q "gateway: active" "$LOGS/gateway-$n-first.log" && fail "member $n's gateway is active on a follower"
  done
  pass "only the leader's gateway holds a session; the other two stand by"
else
  $GATEWAY "$RUN/gateway.properties" > "$LOGS/gateway.log" 2>&1 &
  PIDS+=("$!")
  wait_for "$LOGS/gateway.log" "gateway: started" 45 "gateway" || fail "gateway"
fi
$DISCOVERY "$RUN/discovery.properties" > "$LOGS/discovery.log" 2>&1 &
PIDS+=("$!")
wait_for "$LOGS/discovery.log" "discovery: started" 30 "discovery" || fail "discovery"

# shellcheck disable=SC2046
$MOST define --symbol AAPL --reference 100.00 --static-collar 5000 --dynamic-collar 2000 $CONN \
  $(via_leader "$LEADER") || fail "define AAPL"
# shellcheck disable=SC2046
$MOST session --phase continuous --shard 0 $CONN $(via_leader "$LEADER") || fail "session transition"
sleep 1

if [ "$PLACEMENT" = colocated ]; then
  # A follower's gateway refuses rather than forwarding into a follower, and says why.
  STANDBY=$(others "$LEADER" | cut -d' ' -f1)
  # shellcheck disable=SC2046
  $MOST send --symbol AAPL --side buy --price 99.00 --qty 1 --clordid 500 --participant 9 \
    --follow 2 $CONN $(via_leader "$STANDBY") > "$RUN/standby.out" 2>&1
  grep -q "GATEWAY_UNAVAILABLE" "$RUN/standby.out" || { cat "$RUN/standby.out"; fail "a standby gateway did not refuse"; }
  pass "member $STANDBY's gateway refused GATEWAY_UNAVAILABLE"
fi

# ------------------------------------------------------------------------- 1
echo
echo "== 1. a maker rests, the leader goes, the maker is filled by the new one"
# shellcheck disable=SC2046
$MOST send --symbol AAPL --side sell --price 101.00 --qty 10 --clordid 1001 --participant 7 \
  --follow 2 $CONN $(via_leader "$LEADER") > "$RUN/maker.out" 2>&1 || fail "send maker"
grep -q "NEW" "$RUN/maker.out" || { cat "$RUN/maker.out"; fail "the maker did not rest"; }

FIRST=$LEADER
T0=$(date +%s)
stop_node "$FIRST"
# shellcheck disable=SC2046
LEADER=$(await_leader 60 $(others "$FIRST")) || fail "no new leader"
echo "  member $LEADER leads, $(( $(date +%s) - T0 )) s after the stop"
SUFFIX[$FIRST]=rejoin

crossed=0
for _ in $(seq 1 30); do
  # shellcheck disable=SC2046
  $MOST send --symbol AAPL --side buy --price 101.00 --qty 10 --clordid "$((2000 + RANDOM % 1000))" \
    --participant 9 --follow 2 $CONN $(via_leader "$LEADER") > "$RUN/taker.out" 2>&1
  grep -q "TRADE" "$RUN/taker.out" && { crossed=1; break; }
  sleep 0.5
done
cat "$RUN/taker.out"
[ "$crossed" = 1 ] || fail "nothing traded through the gateway after the failover"
echo "  first trade $(( $(date +%s) - T0 )) s after the stop"
pass "the maker's pre-failover order traded after it"

echo "  (restarting member $FIRST so the shard can lose another)"
start_node "$FIRST" rejoin
wait_for "$LOGS/engine-$FIRST-rejoin.log" "engine: role FOLLOWER" 60 "member $FIRST rejoining" \
  || fail "member $FIRST did not rejoin"
sleep 2

# ------------------------------------------------------------------------- 2
echo
echo "== 2. load through a second failover: ${RATE}/s for ${LOAD_SECONDS} s, member $LEADER stopped at ${STOP_AFTER} s"
COUNT=$((RATE * LOAD_SECONDS))
DELAY_US=$((1000000 / RATE))
# shellcheck disable=SC2086
$MOST load --symbol AAPL --price-min 99.90 --price-max 100.10 --qty-min 1 --qty-max 10 \
  --count "$COUNT" --delay-us "$DELAY_US" --warmup 100 --participant 20 --participants 4 \
  --clordid-base 100000 --drain-ms 5000 --interval-ms 1000 $CONN $ALL_GATEWAYS > "$RUN/load.out" 2>&1 &
LOAD_PID=$!; PIDS+=("$LOAD_PID")
sleep "$STOP_AFTER"
SECOND=$LEADER
stop_node "$SECOND"
# shellcheck disable=SC2046
LEADER=$(await_leader 60 $(others "$SECOND")) || fail "no leader after the second stop"
echo "  member $LEADER leads"
wait "$LOAD_PID"
cat "$RUN/load.out"

# The first new leader stopped in an orderly way, so it printed what it could not deliver. A fill
# for a participant whose route still named the old gateway's session would be counted here.
UNDELIVERABLE=$(grep -o "undeliverableReports=[0-9]*" "$LOGS/engine-$SECOND-${SUFFIX[$SECOND]}.log" | tail -1 | cut -d= -f2)
echo "  member $SECOND's engine: undeliverableReports=${UNDELIVERABLE:-?}"
[ "${UNDELIVERABLE:-1}" = 0 ] || fail "member $SECOND could not deliver ${UNDELIVERABLE:-?} reports -- a route still named the old session"
pass "every report the first new leader generated was delivered, the maker's fill among them"

grep -qE "unanswered" "$RUN/load.out" || fail "the load printed no summary"

# Design.md §5, "Report sequence and resend": after the fence, every order the load sent is either
# answered or proven never sequenced. None may be left unknown, and a complete resend may leave
# nothing missing -- an order resting or filled without the client hearing is the one outcome
# this exists to rule out.
grep -q "STILL MISSING" "$RUN/load.out" && fail "a complete resend left reports missing"
UNKNOWN=$(grep -oE "unknown +[0-9,]+" "$RUN/load.out" | grep -oE "[0-9,]+" | tr -d , || true)
[ "${UNKNOWN:-0}" = 0 ] || fail "$UNKNOWN orders were left with no outcome after the failover"
grep -q "recovery " "$RUN/load.out" || fail "no resend fence was sent across the failover"
pass "every order the load sent was answered, or proven never sequenced by a resend fence"
echo
echo "PASS ($PLACEMENT): two failovers, routes followed the active gateway, and no order was left unknown"
