#!/usr/bin/env bash
# Experiment 15: pacing by bid multiplier (never throttles) against Smart throttling and unpaced,
# under second price with cleared prices charged, with and without the per-node allowance.
# The bid-scaling pacer keeps every campaign in the auction all day, so the in-memory cap store
# holds several times exp2's impressions: it needs more heap than exp2's 3 GB.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
take_lock "exp15 bid-multiplier pacing simulation (CPU only)"
SIM_OPTS="-Xmx8g" $SIM exp15-bid-pacing | grep -E '"pacer"|allowance_split|within_5pct|campaigns_overspent|rmse|impressions|cost_per_expected'
