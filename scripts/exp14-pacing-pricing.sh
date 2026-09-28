#!/usr/bin/env bash
# Experiment 14: the exp2 pacing table (every pacer, with and without the per-node allowance,
# 8 nodes, 10 s sync) under second price with winners charged the cleared price, and under first
# price on the same bids. Reserve from data/work/auction.json.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
take_lock "exp14 pacing under pricing simulation (CPU only)"
SIM_OPTS="-Xmx6g" $SIM exp14-pacing-pricing | grep -E '"pacer"|"pricing"|allowance_split|within_5pct|campaigns_overspent|rmse|cost_per_expected'
