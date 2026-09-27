#!/usr/bin/env bash
# Experiment 7: AdServe, the beacon consumer and the Flink billing job running together; simulated
# players fire beacons with duplicates, losses and misroutes; the audit checks the billing table
# against the truth and against the beacon consumer's counters.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
./gradlew -q :server:bootJar :sim:installDist :beacons:installDist :billing:installDist
take_lock "exp7 billing"
docker compose up -d --wait kafka redis postgres
fresh_state
docker compose exec -T postgres psql -U adserve -q -c "truncate billing_events" >/dev/null
start_server billing
beacons/build/install/beacons/bin/beacons > "$LOGDIR/beacons.log" 2>&1 &
BEACONS=$!
BILLING_START=latest billing/build/install/billing/bin/billing > "$LOGDIR/billing.log" 2>&1 &
BILLING=$!
trap 'kill $BEACONS $BILLING 2>/dev/null || true; stop_server; release_lock' EXIT
sleep 20
for dup in 0.05 0.10 0.20; do
  $SIM exp7-billing "${BREAKS:-5000}" "$dup" | grep -E "impressions_served|duplicate_beacons_sent|billing_rows|duplicate_rows|missing|without|agreement"
done
