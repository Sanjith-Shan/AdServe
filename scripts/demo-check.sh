#!/usr/bin/env bash
# Runs scripts/demo.sh under the bench lock, waits for its summary, and leaves it running.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
take_lock "demo check"
trap - EXIT
scripts/demo.sh > build/demo-logs/demo-out.log 2>&1 &
echo $! > build/demo-logs/demo.pid
for i in $(seq 1 120); do grep -q "Press Ctrl-C" build/demo-logs/demo-out.log 2>/dev/null && break; sleep 5; done
cat build/demo-logs/demo-out.log
