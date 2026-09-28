#!/usr/bin/env bash
# Run one CPU-only sim command on the always-on Windows mini PC instead of this laptop, then append
# the rows it wrote to results/ here. One run at a time on the box (a lock directory there).
#
#   scripts/minipc-sim.sh <job> <heap> <results files, comma separated> -- <sim args...>
#   scripts/minipc-sim.sh exp14 6g exp14_pacing_pricing.jsonl -- exp14-pacing-pricing
#
# The box holds a project-local JDK 21 and a copy of data/work under C:\SullaPortal\projects\adserve.
# Result rows carry that machine's description (Machine.java), so they are never mixed up with the
# laptop's; none of these experiments reports latency.
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
cd "$(dirname "$0")/.."
JOB=$1 HEAP=$2 FILES=$3; shift 3
[ "${1:-}" = "--" ] && shift
ROOT='C:\SullaPortal\projects\adserve'
TMP=$(mktemp -d /tmp/claude-501/adserve-minipc.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

# SKIP_BUILD=1 ships the current install dir as is (e.g. while another project times the laptop).
[ -n "${SKIP_BUILD:-}" ] || ./gradlew -q :sim:installDist
tar -czf "$TMP/dist.tgz" -C sim/build/install sim
minipc push "$TMP/dist.tgz" "C:/SullaPortal/projects/adserve/dist-$JOB.tgz" >/dev/null

ARGS=""
for a in "$@"; do ARGS="$ARGS '${a//\'/\'\'}'"; done
PS_FILES=$(echo "$FILES" | sed "s/[^,][^,]*/'&'/g")
cat > "$TMP/job.ps1" <<EOF
\$ErrorActionPreference = 'Stop'
\$root = '$ROOT'
\$lock = Join-Path \$root 'run\\cpu.lock'
while (\$true) {
  try { New-Item -ItemType Directory \$lock -ErrorAction Stop | Out-Null; break }
  catch { Write-Output "waiting for cpu.lock: \$(Get-Content (Join-Path \$lock owner) -ErrorAction SilentlyContinue)"; Start-Sleep 30 }
}
try {
  Set-Content (Join-Path \$lock owner) "$JOB since \$(Get-Date -Format s)"
  \$dist = Join-Path \$root 'dist-$JOB'
  if (Test-Path \$dist) { Remove-Item -Recurse -Force \$dist }
  New-Item -ItemType Directory \$dist | Out-Null
  tar -xzf (Join-Path \$root 'dist-$JOB.tgz') -C \$dist
  \$wd = Join-Path \$root 'run'
  Set-Location \$wd
  New-Item -ItemType Directory -Force results | Out-Null
  foreach (\$f in @($PS_FILES)) { Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path 'results' \$f) }
  \$env:JAVA_HOME = Join-Path \$root 'jdk21'
  \$env:SIM_OPTS = '-Xmx$HEAP'
  # Windows PowerShell turns a native command's stderr into error records; under 'Stop' the
  # first line the sim writes to stderr would end the job.
  \$ErrorActionPreference = 'Continue'
  & (Join-Path \$dist 'sim\\bin\\sim.bat') $ARGS 2>&1 | ForEach-Object { "\$_" }
  \$code = \$LASTEXITCODE
} finally {
  Remove-Item -Recurse -Force \$lock
}
if (\$code -ne 0) { Write-Output "sim exited \$code"; exit \$code }
EOF
minipc push "$TMP/job.ps1" "C:/SullaPortal/projects/adserve/job-$JOB.ps1" >/dev/null
minipc job add "sim-$JOB" manual "powershell -NoProfile -ExecutionPolicy Bypass -File $ROOT\\job-$JOB.ps1" >/dev/null
# `minipc job run` stops following after 30 min, and a run may queue behind the box's lock for
# longer, so start it and follow its log until this run's exit line.
minipc job run "sim-$JOB" --no-wait >/dev/null
STARTED=$(date +%s)
until LOG=$(minipc job logs "sim-$JOB" 2>/dev/null) && echo "$LOG" | tail -1 | grep -q '^=== exit' \
    && [ $(( $(date +%s) - STARTED )) -gt 60 ] \
    && [ "$(echo "$LOG" | head -1 | grep -o '[0-9]\{8\}-[0-9]\{6\}')" \> "$(date -r $((STARTED - 120)) +%Y%m%d-%H%M%S)" ]; do
  sleep 30
done
echo "$LOG" | grep -v '^waiting for cpu.lock'
echo "$LOG" | tail -1 | grep -q '^=== exit 0' || { echo "job sim-$JOB failed"; exit 1; }

IFS=, read -ra LIST <<< "$FILES"
for f in "${LIST[@]}"; do
  minipc pull "C:/SullaPortal/projects/adserve/run/results/$f" "$TMP/$f" >/dev/null
  cat "$TMP/$f" >> "results/$f"
  echo "appended $(wc -l < "$TMP/$f" | tr -d ' ') rows to results/$f"
done
