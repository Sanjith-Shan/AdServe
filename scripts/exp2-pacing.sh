#!/usr/bin/env bash
# Experiment 2: every pacer over the full replay day, with and without the per-node allowance.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
take_lock "exp2 pacing simulation (CPU only)"
SIM_OPTS="-Xmx3g" $SIM exp2-pacing | grep -E '"pacer"|allowance_split|aggregate_delivered|rmse'
