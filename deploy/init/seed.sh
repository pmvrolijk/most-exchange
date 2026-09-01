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

say "publishing release"
expect_ok "$(api POST '/api/releases?note=dev%20stack')" "release"

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
