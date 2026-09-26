#!/bin/sh
# Bring the dev stack from "processes are running" to "there is a market".
#
# Everything here goes through the control plane's REST API rather than touching Postgres or the
# engine directly, so this file is also a worked example of driving the exchange the supported way.
#
# It is written to be re-runnable: `docker compose up` a second time must not fail because shard 0
# already exists.
set -eu

API="${CONTROL_URL:-http://control:8080}"
AUTH="-u ${CONTROL_ADMIN_USER:-admin}:${CONTROL_ADMIN_PASSWORD:-most-dev-password}"
JSON="-H Content-Type:application/json"

say() { echo "seed: $*"; }

# curl with basic auth: exempt from CSRF by design, because a browser attaches cookies to a
# cross-site request automatically and an Authorization header never.
api() { # api METHOD PATH [BODY]
  if [ $# -ge 3 ]; then
    curl -sS $AUTH $JSON -X "$1" "$API$2" -d "$3" -w "\n%{http_code}"
  else
    curl -sS $AUTH -X "$1" "$API$2" -w "\n%{http_code}"
  fi
}

status_of() { echo "$1" | tail -1; }
body_of()   { echo "$1" | sed '$d'; }

# 2xx, or 409/400 when the thing already exists -- a second `up` must be a no-op, not a failure.
expect_ok() { # expect_ok <response> <what>
  code=$(status_of "$1")
  case "$code" in
    2*) say "$2: $code" ;;
    409|400) say "$2: $code (already present, continuing) -- $(body_of "$1")" ;;
    *) say "$2: FAILED $code -- $(body_of "$1")"; exit 1 ;;
  esac
}

say "waiting for the control plane at $API"
i=0
until curl -sS -o /dev/null "$API/api/auth/me" 2>/dev/null; do
  i=$((i + 1))
  [ "$i" -gt 60 ] && { say "control plane never answered"; exit 1; }
  sleep 2
done
say "control plane is up"

# ---------------------------------------------------------------- topology
# The channels here must match deploy/config/gateway.properties and discovery.properties exactly.
# The control plane sends operator commands to the orderEntryChannel it finds in the DATABASE, and
# compares that against what discovery broadcasts -- a mismatch shows up as routing drift on
# /api/status rather than as a silent failure to reach the gateway.
say "creating shard 0"
expect_ok "$(api POST /api/shards '{
  "shardId": 0,
  "orderEntryChannel": "aeron:udp?endpoint=shard0:20001",
  "orderEntryStreamId": 20,
  "executionReportChannel": "aeron:udp?endpoint=0.0.0.0:0|control=shard0:20002|control-mode=dynamic",
  "executionReportStreamId": 21
}')" "shard 0"

# Imported from the very file the four processes booted from, rather than retyped. The import
# parses through ShardSpec.from, so a file that would not have booted is not accepted, and the
# fingerprint the control plane reports is recomputed from what was stored -- if it matches what
# the engine printed at startup, the database and the running shard genuinely agree.
say "importing the shard security file the processes booted from"
resp=$(curl -sS $AUTH -H 'Content-Type: text/plain' -X POST "$API/api/import" \
  --data-binary @/config/shard-0-securities.properties -w "\n%{http_code}")
expect_ok "$resp" "import"
say "import said: $(body_of "$resp")"

# ------------------------------------------------------- participants and gateways
# Exactly what the running stack enforces: config/shard-0-participants.properties, which the cluster
# host, the engine and the gateway booted from. Authored here so the control plane describes the
# shard as it is -- before this, the database had no gateways at all, and the release it published
# said shard 0's clients connect anonymously while the cluster was refusing anonymous sessions.
#
# 7 and 8 are the README's traders, 20-23 are what `most load` sends as.
for p in "7 North" "8 South" "20 Load-A" "21 Load-B" "22 Load-C" "23 Load-D"; do
  id=${p%% *}
  expect_ok "$(api POST /api/participants "{\"participantId\": $id, \"name\": \"${p#* }\"}")" \
    "participant $id"
done

# Create (a second run answers 409), then PUT the row so a re-run converges on this definition,
# then set the secret to the one the process presents -- read from the same file it is mounted
# from, so the value exists in exactly one place. Create alone would issue a random one.
gateway() { # gateway <id> <secret file> <json row>
  expect_ok "$(api POST /api/gateways "$3")" "gateway $1"
  expect_ok "$(api PUT "/api/gateways/$1" "$3")" "gateway $1 grants"
  expect_ok "$(api PUT "/api/gateways/$1/secret" "{\"secret\": \"$(tr -d '\n' < "$2")\"}")" \
    "gateway $1 secret"
}
# gw-0 is also the operator gateway: the CLI's and this control plane's session transitions,
# purges, definitions and image requests go through it (Design.md §1).
gateway gw-0 /config/gateway-0.secret '{
  "gatewayId": "gw-0", "shardId": 0, "enabled": true, "operator": true,
  "participants": [7, 8, 20, 21, 22, 23], "cancelOnly": [], "primaryFor": []
}'
# The control plane's own identity, for the snapshots it sends straight to the cluster. Operator
# only: it speaks for nobody.
gateway control /config/control.secret '{
  "gatewayId": "control", "shardId": 0, "enabled": true, "operator": true,
  "participants": [], "cancelOnly": [], "primaryFor": []
}'

say "publishing release"
resp=$(api POST '/api/releases?note=dev%20stack')
expect_ok "$resp" "release"
# The check that the database and the running shard agree about who may do what: this must equal
# the registry fingerprint cluster-host, engine and gateway print at startup
# (`docker compose logs cluster-host | grep participants=`).
say "release registry fingerprint for shard 0: $(body_of "$resp" \
  | sed -n 's/.*"registryFingerprints":{"0":"\([0-9a-f]*\)".*/\1/p')"

# ---------------------------------------------------------------- the calendar
# The one in docs/ControlPlane.md. Assigned, but the scheduler is OFF in this stack
# (CONTROL_SCHEDULER_ENABLED=false) -- it opens and closes markets on the wall clock, which is a
# surprising thing for a dev stack to do while you read its logs. Turn it on to watch it reconcile.
say "creating the equities schedule"
expect_ok "$(api PUT /api/schedules/equities '{
  "name": "equities", "zone": "Europe/Amsterdam",
  "weekdays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
  "purgeTime": "07:00",
  "entries": [
    { "at": "08:00", "phase": "pre-open" },
    { "at": "08:55", "phase": "open-auction" },
    { "at": "09:00", "phase": "continuous" },
    { "at": "17:30", "phase": "closed" }
  ]
}')" "schedule equities"
expect_ok "$(api PUT /api/shards/0/schedule '{"scheduleName": "equities"}')" "shard 0 schedule"

# ------------------------------------------------------------- reference prices
# NOT geometry, and deliberately not in the security file: these arrive at runtime as
# SecurityDefinition commands through the replicated log, because every node must apply them at the
# same log position. Nothing acknowledges them -- a 202 means the bytes were sent, no more.
for id in 1 2; do
  say "seeding definition for security $id"
  expect_ok "$(api POST "/api/securities/$id/definition" \
    '{"referencePrice": 10000000000, "staticCollarBps": 5000, "dynamicCollarBps": 2000}')" \
    "definition $id"
done

# ------------------------------------------------------------------- open the market
# The difference between two phases is a PATH, not a destination. The uncross runs only on
# OPEN_AUCTION -> CONTINUOUS, so jumping straight to CONTINUOUS is accepted and silently skips the
# auction, leaving any crossed resting book crossed. Walk it.
for phase in PRE_OPEN OPEN_AUCTION CONTINUOUS; do
  say "session -> $phase"
  expect_ok "$(api POST /api/shards/0/session "{\"phase\":\"$phase\"}")" "session $phase"
  sleep 1
done

say "done -- shard 0 is CONTINUOUS with AAPL and MSFT at 100.00"
