#!/usr/bin/env bash
# Experiment 6: the same live-break burst with each dependency stopped in turn, and the legacy
# sync-write mode with Postgres stopped.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
N="${N:-8000}"
REPEATS="${REPEATS:-3}"
OUT=exp6_dependency.jsonl
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp6 dependency"
docker compose up -d --wait kafka redis postgres
burst() { RUN_NOTE="$2" $SIM burst "$1" "$N" "$REPEATS" 1.0 localhost:29100 2000 20 data/work/requests-20130611.bin "$OUT"; }

fresh_state
start_server all_up
burst all_up "every dependency up"

docker compose stop postgres
burst postgres_down "Postgres stopped after the snapshot loaded; decisions come from the in-process snapshot"
docker compose start postgres && docker compose up -d --wait postgres

docker compose stop kafka
burst kafka_down "Kafka stopped; the decision log buffer fills and drops, serving continues"
docker compose start kafka && docker compose up -d --wait kafka

docker compose stop redis
burst redis_down_allow "Redis stopped, cap mode unknown_allow: serve, caps unknown"
start_server redis_down_deny --adserve.cap-mode=UNKNOWN_DENY
burst redis_down_deny "Redis stopped, cap mode unknown_deny: capped campaigns dropped"
docker compose start redis && docker compose up -d --wait redis

fresh_state
start_server legacy --adserve.legacy-sync-write=true
burst legacy_all_up "legacy sync-write, every dependency up"
docker compose stop postgres
burst legacy_postgres_down "legacy sync-write with Postgres stopped"
docker compose start postgres && docker compose up -d --wait postgres
