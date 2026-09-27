#!/usr/bin/env bash
# The legs of experiment 6 that need a server started while Redis is down, and the legacy
# sync-write mode with Postgres stopped (rerun after BUG_LOG bug 9 was fixed).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
N="${N:-8000}"
REPEATS="${REPEATS:-3}"
OUT=exp6_dependency.jsonl
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp6 remaining legs"
docker compose up -d --wait kafka redis postgres
burst() { RUN_NOTE="$2" $SIM burst "$1" "$N" "$REPEATS" 1.0 localhost:29100 2000 20 data/work/requests-20130611.bin "$OUT"; }

fresh_state
docker compose stop redis
start_server redis_down_deny --adserve.cap-mode=UNKNOWN_DENY
burst redis_down_deny "Redis stopped before the server started, cap mode unknown_deny: capped campaigns dropped"
start_server redis_down_allow_restart --adserve.cap-mode=UNKNOWN_ALLOW
burst redis_down_allow_cold "Redis stopped before the server started, cap mode unknown_allow"
docker compose start redis && docker compose up -d --wait redis

fresh_state
start_server legacy --adserve.legacy-sync-write=true
burst legacy_all_up "legacy sync-write, every dependency up"
docker compose stop postgres
burst legacy_postgres_down "legacy sync-write with Postgres stopped"
docker compose start postgres && docker compose up -d --wait postgres
