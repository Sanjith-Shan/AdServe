#!/usr/bin/env bash
# Experiment 13: does the auction cost latency? The exp1 burst (8,000 LIVE requests in 2 s, three
# repeats per leg) against three servers, interleaved in rounds so the shared laptop's load drifts
# over all of them alike:
#   auction_second_price  this server, second-price pricing on (the default)
#   auction_first_price   this server, first-price pricing (scoring and assembly identical, no rival search)
#   pre_auction           the server as of commit d208783, before the auction existed, built from git
# All three seed the same campaigns.json (bids derived from the replay), G1, virtual threads.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
SIZES="${SIZES:-8000}"
REPEATS="${REPEATS:-3}"
ROUNDS="${ROUNDS:-2}"
BASE_COMMIT=d208783
BASE_JAR=build/pre-auction/server-$BASE_COMMIT.jar
RESERVE=$(python3 -c 'import json;print(json.load(open("data/work/auction.json"))["reserve_micros"])')

./gradlew -q :server:bootJar :sim:installDist
if [ ! -f "$BASE_JAR" ]; then
  rm -rf build/pre-auction/src && git worktree prune
  git worktree add --detach build/pre-auction/src "$BASE_COMMIT"
  (cd build/pre-auction/src && ./gradlew -q :server:bootJar)
  cp build/pre-auction/src/server/build/libs/server-0.1.0.jar "$BASE_JAR"
  git worktree remove --force build/pre-auction/src
fi
NEW_JAR=$SERVER_JAR

take_lock "exp13 auction latency"
docker compose up -d --wait kafka redis postgres
leg() {
  local label=$1 jar=$2; shift 2
  fresh_state
  SERVER_JAR=$jar start_server "$label" "$@"
  RUN_NOTE="round $r of $ROUNDS; reserve $RESERVE micros" \
    $SIM burst "$label" "$SIZES" "$REPEATS" 1.0 localhost:29100 3000 30 data/work/requests-20130611.bin exp13_latency.jsonl
}
for r in $(seq 1 "$ROUNDS"); do
  leg auction_second_price "$NEW_JAR" --adserve.auction.pricing=second_price --adserve.auction.reserve-micros="$RESERVE"
  leg pre_auction "$BASE_JAR"
  leg auction_first_price "$NEW_JAR" --adserve.auction.pricing=first_price --adserve.auction.reserve-micros="$RESERVE"
done
