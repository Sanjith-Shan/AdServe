#!/usr/bin/env bash
# Experiment 3: cap violations under duplicated, late and lost beacons, idempotent vs naive counters.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
take_lock "exp3 caps"
docker compose up -d --wait redis
$SIM exp3-caps data/work/campaigns.json data/work/requests-20130611.bin "${VIEWERS:-2000}"
