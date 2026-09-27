#!/usr/bin/env bash
# One command: dependencies, AdServe, the beacon consumer and the Flink billing job, then
# simulated players on real ad breaks. Uses the committed sample data, so it needs no download.
# Stop everything with Ctrl-C.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
LOGDIR=build/demo-logs
mkdir -p "$LOGDIR"
echo "building"
./gradlew -q :server:bootJar :sim:installDist :beacons:installDist :billing:installDist
echo "starting Kafka, Redis, Postgres, Prometheus, Grafana"
docker compose up -d --wait
"$JAVA_HOME/bin/java" -Xmx1g -jar server/build/libs/server-0.1.0.jar --adserve.seed-file=data/sample/campaigns.json \
  > "$LOGDIR/server.log" 2>&1 &
PIDS=$!
beacons/build/install/beacons/bin/beacons > "$LOGDIR/beacons.log" 2>&1 &
PIDS="$PIDS $!"
BILLING_START=latest billing/build/install/billing/bin/billing > "$LOGDIR/billing.log" 2>&1 &
PIDS="$PIDS $!"
trap 'kill $PIDS 2>/dev/null || true' EXIT
until curl -sf localhost:28080/actuator/health >/dev/null 2>&1; do sleep 1; done
sleep 15
echo "one decision over REST:"
curl -s -X POST localhost:28080/v1/decide -H 'content-type: application/json' \
  -d '{"requestId":"demo-curl","viewerId":"demo-viewer","titleId":"t1","genre":"drama","breakLengthS":90,"device":"tv","region":"US_EAST","priority":"LIVE","tsMs":1370950000000,"geo":"r1"}' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print("  pod:", [(i["creative"]["campaignId"], i["creative"]["category"], i["creative"]["durationS"]) for i in r.get("pod",[])])'
sim/build/install/sim/bin/sim demo "${BREAKS:-2000}"
cat <<MSG

Console:  http://localhost:28080/console/
GraphQL:  http://localhost:28080/graphiql
Grafana:  http://localhost:23000 (dashboard "AdServe")
Press Ctrl-C to stop.
MSG
wait
