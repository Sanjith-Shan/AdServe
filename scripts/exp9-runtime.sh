#!/usr/bin/env bash
# Experiment 9: the same live-break bursts under three JVM configurations: generational ZGC with
# a virtual thread per request (the default), G1 with virtual threads, and generational ZGC with a
# fixed pool of 64 platform threads.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
SIZES="${SIZES:-8000,12000}"
REPEATS="${REPEATS:-3}"
./gradlew -q :server:bootJar :sim:installDist
take_lock "exp9 runtime"
docker compose up -d --wait kafka redis postgres
run() {
  local label=$1 jvm=$2; shift 2
  fresh_state
  SERVER_JVM="$jvm" start_server "$label" "$@"
  $SIM burst "$label" "$SIZES" "$REPEATS" 1.0 localhost:29100 3000 30 data/work/requests-20130611.bin exp9_runtime.jsonl
}
run zgc_virtual "-XX:+UseZGC -XX:+ZGenerational"
run g1_virtual "-XX:+UseG1GC"
run zgc_platform64 "-XX:+UseZGC -XX:+ZGenerational" --adserve.executor=platform:64
