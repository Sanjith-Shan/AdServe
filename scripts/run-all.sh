#!/usr/bin/env bash
# Every experiment in order. Each script takes the shared bench lock for its own duration.
set -uo pipefail
cd "$(dirname "$0")/.."
for s in "$@"; do
  echo "=== $s $(date +%H:%M:%S)"
  "scripts/$s.sh" || echo "!!! $s failed"
done
echo "=== done $(date +%H:%M:%S)"
