#!/usr/bin/env bash
# JMH microbenchmarks of the decision path; results in results/jmh-hotpath.json.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib.sh
take_lock "jmh"
./gradlew -q :core:jmh
cp core/build/results/jmh/results.json results/jmh-hotpath.json
