#!/usr/bin/env bash
# Experiment 11: the replay day priced under second price and first price on the same pods,
# plus exact critical-value prices of the same pods, with viewer-bootstrap 95% intervals.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
# Run from a private copy: other runs may reinstall sim/build/install while this one is going.
SNAP=$(mktemp -d /tmp/claude-501/adserve-sim.XXXXXX); cp -R sim/build/install/sim "$SNAP/"; SIM="$SNAP/sim/bin/sim"
take_lock "exp11 pricing simulation (CPU only)"
SIM_OPTS="-Xmx5g" $SIM exp11-pricing --reps 1000 --seed 20130611 "$@"
rm -rf "$SNAP"
