#!/usr/bin/env bash
# CPU profile of a warm server under steady load (JFR). Output: build/experiment-logs/profile.jfr
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
take_lock "profile"
docker compose up -d --wait kafka redis postgres
fresh_state
start_server profile "$@"
PID=$(cat "$LOGDIR/server.pid")
$SIM smoke data/sample/requests-20130611-first20k.bin "${RATE:-3000}" 20 >/dev/null || true
"$JAVA_HOME/bin/jcmd" "$PID" JFR.start name=p settings=profile filename="$PWD/$LOGDIR/profile.jfr" >/dev/null
$SIM smoke data/sample/requests-20130611-first20k.bin "${RATE:-3000}" 20 | grep -E "p50|p99\"|achieved_ok_per_s\""
"$JAVA_HOME/bin/jcmd" "$PID" JFR.stop name=p >/dev/null
