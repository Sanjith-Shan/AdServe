#!/usr/bin/env bash
# Experiment 16: miscalibrated click-rate predictions in the auction, over the full replay day.
# Leg 1 (unpaced, unlimited budgets) isolates allocation and price; leg 2 (Smart pacer, file
# budgets) shows what the same errors do once budgets bind. Reserve from data/work/auction.json.
#
# Runs on the Windows mini PC through scripts/minipc-sim.sh (CPU only, 4 GB heap there) and
# appends the rows to results/exp16_calibration.jsonl here. LOCAL=1 runs it on this laptop instead,
# under the bench lock.
set -euo pipefail
cd "$(dirname "$0")/.."
THREADS="${THREADS:-4}" SEEDS="${SEEDS:-10}" HEAP="${HEAP:-4g}"
OUT=exp16_calibration.jsonl
if [ "${LOCAL:-0}" = 1 ]; then
  source scripts/lib.sh
  ./gradlew -q :sim:installDist
  take_lock "exp16 calibration simulation (CPU only)"
  export SIM_OPTS="-Xmx$HEAP"
  $SIM exp16-calibration --leg unpaced --threads "$THREADS" --seeds "$SEEDS" "$@"
  $SIM exp16-calibration --leg paced --threads "$THREADS" "$@"
else
  scripts/minipc-sim.sh exp16-unpaced "$HEAP" "$OUT" -- exp16-calibration --leg unpaced --threads "$THREADS" --seeds "$SEEDS" "$@"
  scripts/minipc-sim.sh exp16-paced "$HEAP" "$OUT" -- exp16-calibration --leg paced --threads "$THREADS" "$@"
fi
