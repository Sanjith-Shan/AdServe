#!/usr/bin/env bash
# Experiment 5: LIVE p99 and VOD rejection at 1.5x and 2x measured capacity, with priority
# shedding and without. CAPACITY comes from experiment 1 (the highest rate whose p99 held).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
: "${CAPACITY:?set CAPACITY to the measured decisions per second}"
MULTS="${MULTS:-1.0,1.5,2.0}"
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp5 shedding"
docker compose up -d --wait kafka redis postgres

fresh_state
start_server shedding --adserve.shedding.enabled=true --adserve.shedding.capacity-per-second="$CAPACITY"
$SIM overload shedding "$CAPACITY" "$MULTS" 15 2 0.4

fresh_state
start_server no_shedding --adserve.shedding.enabled=false
$SIM overload no_shedding "$CAPACITY" "$MULTS" 15 2 0.4
