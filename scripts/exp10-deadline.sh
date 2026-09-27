#!/usr/bin/env bash
# Experiment 10: the cap check's deadline against latency and cap enforcement. The same
# 8,000-request burst with a 20, 50 and 100 ms deadline on the counter fetch, with the server on
# the host (Redis reached through Docker Desktop's port forwarder) and with the server in a
# container on the same Docker network as Redis. The share of decisions that missed the deadline
# (and were served in the unknown-allow mode) is recorded next to the latency.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
N="${N:-8000}"
REPEATS="${REPEATS:-3}"
OUT=exp10_deadline.jsonl
./gradlew -q :server:bootJar :sim:installDist
docker compose --profile app build adserve
take_lock "exp10 deadline"
docker compose up -d --wait kafka redis postgres
burst() { RUN_NOTE="$2" $SIM burst "$1" "$N" "$REPEATS" 1.0 localhost:29100 3000 30 data/work/requests-20130611.bin "$OUT"; }
for t in 20 50 100; do
  fresh_state
  start_server "host_${t}ms" --adserve.redis.cap-timeout-ms=$t
  burst "host_deadline_${t}ms" "server on the host, cap check deadline $t ms"
done
stop_server
trap 'docker compose --profile app stop adserve; release_lock' EXIT
for t in 20 50 100; do
  fresh_state
  CAP_TIMEOUT_MS=$t docker compose --profile app up -d --force-recreate adserve
  for i in $(seq 1 90); do curl -sf localhost:28080/actuator/health >/dev/null 2>&1 && break; sleep 1; done
  sleep 5
  burst "container_deadline_${t}ms" "server in a container on the Redis network, cap check deadline $t ms"
done
docker compose --profile app stop adserve
