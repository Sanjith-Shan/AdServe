#!/usr/bin/env bash
# 30-second load smoke used by CI: dependencies in Docker, server on the host, open-loop load.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d --wait kafka redis postgres
./gradlew -q :server:bootJar :sim:installDist
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx1g -jar server/build/libs/server-0.1.0.jar --adserve.seed-file=data/sample/campaigns.json > smoke-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
for i in $(seq 1 60); do
  curl -sf localhost:28080/actuator/health >/dev/null && break
  sleep 1
done
sleep 3
sim/build/install/sim/bin/sim smoke data/sample/requests-20130611-first20k.bin "${SMOKE_RATE:-300}" "${SMOKE_SECONDS:-30}"
