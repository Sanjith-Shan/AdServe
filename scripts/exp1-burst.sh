#!/usr/bin/env bash
# Experiment 1: live-break bursts against AdServe, then the same bursts against the legacy
# sync-write baseline (every decision inserted into Postgres before the response).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
SIZES="${SIZES:-4000,8000,16000,24000}"
REPEATS="${REPEATS:-3}"
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp1 burst"
docker compose up -d --wait kafka redis postgres

fresh_state
start_server adserve
$SIM burst adserve "$SIZES" "$REPEATS" 1.0

fresh_state
start_server legacy --adserve.legacy-sync-write=true
$SIM burst legacy_sync_write "$SIZES" "$REPEATS" 1.0
