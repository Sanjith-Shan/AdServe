#!/usr/bin/env bash
# Finds roughly where burst p99 breaks, to choose experiment 1's sizes and experiment 5's capacity.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
take_lock "calibration"
docker compose up -d --wait kafka redis postgres
fresh_state
start_server calib
$SIM burst calibration "${SIZES:-4000,8000,16000,32000}" 1 1.0 localhost:29100 3000 30 data/work/requests-20130611.bin calibration.jsonl
