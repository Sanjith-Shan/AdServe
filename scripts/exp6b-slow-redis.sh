#!/usr/bin/env bash
# Experiment 6b: Redis slow instead of down. Redis is reached through Toxiproxy; the same burst
# runs with 0, 5 and 50 ms added to every Redis response. The cap check has a 20 ms deadline.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
N="${N:-8000}"
REPEATS="${REPEATS:-3}"
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp6b slow redis"
docker compose up -d --wait kafka redis postgres
docker compose --profile chaos up -d toxiproxy
sleep 3
API=http://localhost:28474
curl -s -X DELETE $API/proxies/redis >/dev/null || true
curl -sf -X POST $API/proxies -d '{"name":"redis","listen":"0.0.0.0:26380","upstream":"redis:6379"}' >/dev/null
fresh_state
start_server slow_redis --adserve.redis.uri=redis://localhost:26380
for ms in 0 5 50; do
  curl -s -X DELETE $API/proxies/redis/toxics/lat >/dev/null || true
  if [ "$ms" != 0 ]; then
    curl -sf -X POST $API/proxies/redis/toxics -d "{\"name\":\"lat\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$ms}}" >/dev/null
  fi
  RUN_NOTE="Redis behind Toxiproxy with $ms ms added per response; cap check deadline 20 ms, cap mode unknown_allow" \
    $SIM burst "redis_plus_${ms}ms" "$N" "$REPEATS" 1.0 localhost:29100 2000 20 data/work/requests-20130611.bin exp6_dependency.jsonl
done
curl -s -X DELETE $API/proxies/redis/toxics/lat >/dev/null || true
docker compose --profile chaos stop toxiproxy
