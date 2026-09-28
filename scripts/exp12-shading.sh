#!/usr/bin/env bash
# Experiment 12: each advertiser shades its bid 0 to 50% against unchanged rivals, under second
# price and first price. Every break of one viewer in four (by viewer-id hash) of the replay day.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :sim:installDist
# Run from a private copy: other runs may reinstall sim/build/install while this one is going.
SNAP=$(mktemp -d /tmp/claude-501/adserve-sim.XXXXXX); cp -R sim/build/install/sim "$SNAP/"; SIM="$SNAP/sim/bin/sim"
take_lock "exp12 shading simulation (CPU only)"
SIM_OPTS="-Xmx6g" $SIM exp12-shading --viewer-sample 4 --threads 8 "$@" | grep -v ' s$'
python3 scripts/charts.py shading
rm -rf "$SNAP"
