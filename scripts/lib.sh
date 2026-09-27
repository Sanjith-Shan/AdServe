# Shared helpers for the experiment scripts. Source it; do not run it.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
JAVA="$JAVA_HOME/bin/java"
LOCK=/tmp/claude-501/bench.lock
SERVER_JAR=server/build/libs/server-0.1.0.jar
SIM=sim/build/install/sim/bin/sim
export SIM_OPTS="${SIM_OPTS:--Xmx1g}"
LOGDIR="${LOGDIR:-build/experiment-logs}"
mkdir -p "$LOGDIR"

# The laptop is shared with another project's benchmarks; take turns through a lock directory.
# mkdir is the atomic test-and-set; a failed mkdir means someone else holds it, so wait.
# The lock is only ever removed by the run whose owner line it carries.
LOCK_OWNER="adserve pid=$$"
# Fairness: after releasing, wait 60 s before taking it again, so the other project gets a turn.
LAST_RELEASE=/tmp/claude-501/adserve.last_release
# Docker Desktop on this laptop has hung and restarted under load; wait for it rather than fail.
wait_docker() {
  local n=0
  until docker info >/dev/null 2>&1 && docker compose ps >/dev/null 2>&1; do
    n=$((n + 1)); [ $((n % 6)) -eq 1 ] && echo "waiting for Docker"
    sleep 10
  done
}
take_lock() {
  mkdir -p /tmp/claude-501
  wait_docker
  if [ -f "$LAST_RELEASE" ]; then
    local since=$(( $(date +%s) - $(stat -f %m "$LAST_RELEASE" 2>/dev/null || stat -c %Y "$LAST_RELEASE") ))
    [ "$since" -lt 60 ] && sleep $(( 60 - since ))
  fi
  local n=0
  until mkdir "$LOCK" 2>/dev/null; do
    n=$((n + 1)); [ $((n % 30)) -eq 1 ] && echo "waiting for bench lock held by $(cat $LOCK/owner 2>/dev/null)"
    sleep 2
  done
  echo "$LOCK_OWNER since=$(date +%H:%M:%S) task=$1" > "$LOCK/owner"
  trap 'stop_server; release_lock' EXIT
  wait_docker
}
release_lock() {
  if [ -f "$LOCK/owner" ] && grep -q "^$LOCK_OWNER " "$LOCK/owner"; then rm -rf "$LOCK"; touch "$LAST_RELEASE"; fi
}

# start_server <name> [extra spring args...]
# G1 is the default collector: experiment 9 measured it against generational ZGC on this machine.
start_server() {
  local name=$1; shift
  stop_server
  export SERVER_JVM_USED="${SERVER_JVM:--XX:+UseG1GC} -Xms2g -Xmx2g"
  "$JAVA" ${SERVER_JVM:--XX:+UseG1GC} -Xms2g -Xmx2g -jar "$SERVER_JAR" \
    --adserve.seed-file=data/work/campaigns.json --adserve.forecast-file=data/work/forecast.json "$@" \
    > "$LOGDIR/server-$name.log" 2>&1 &
  echo $! > "$LOGDIR/server.pid"
  for i in $(seq 1 90); do
    curl -sf localhost:28080/actuator/health >/dev/null 2>&1 && grep -q "seeded" "$LOGDIR/server-$name.log" && return 0
    sleep 1
  done
  echo "server $name did not start"; tail -50 "$LOGDIR/server-$name.log"; return 1
}
stop_server() {
  if [ -f "$LOGDIR/server.pid" ]; then kill "$(cat "$LOGDIR/server.pid")" 2>/dev/null || true; rm -f "$LOGDIR/server.pid"; sleep 2; fi
}
fresh_state() {
  docker compose exec -T redis redis-cli FLUSHALL >/dev/null
  docker compose exec -T postgres psql -U adserve -q -c "truncate decisions_sync" >/dev/null
}
