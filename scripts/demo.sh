#!/usr/bin/env bash

set -euo pipefail

NS="${NS:-solstice}"
CR="${CR:-solstice-flink-controller}"
JOB="${JOB:-solstice-flink}"
TIMEOUT="${TIMEOUT:-300}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

log()  { printf "\033[1;32m[demo]\033[0m %s\n" "$*"; }
fail() {
  printf "\033[1;31m[fail]\033[0m %s\n" "$*" >&2
  kubectl get solsticecontrollers "$CR" -n "$NS" >&2 || true
  kubectl get flinkdeployment "$JOB" -n "$NS" >&2 || true
  exit 1
}

controller() { kubectl get solsticecontrollers "$CR" -n "$NS" -o jsonpath="$1"; }
flink()      { kubectl get flinkdeployment "$JOB" -n "$NS" -o jsonpath="$1"; }

wait_until() {
  local description="$1" check="$2" deadline=$(( $(date +%s) + TIMEOUT ))
  log "Waiting for $description (up to ${TIMEOUT}s)"
  until eval "$check"; do
    (( $(date +%s) < deadline )) || fail "timed out waiting for $description"
    sleep 5
  done
}

dirty() {
  local was_suspended previous
  was_suspended="$(flink '{.status.lifecycleState}')"
  previous="$(controller '{.status.lastSavepointPath}')"
  "$REPO_ROOT/scripts/simulate-dirty-grid.sh" >/dev/null
  log "Grid set to dirty"
  if [[ "$was_suspended" == "SUSPENDED" ]]; then
    log "Job was already suspended"
  else
    wait_until "a new savepoint to complete" \
      '[[ "$(controller "{.status.savepointPhase}")" == "COMPLETED" && "$(controller "{.status.lastSavepointPath}")" != "$previous" ]]'
  fi
  wait_until "Flink to report the job suspended" \
    '[[ "$(flink "{.status.lifecycleState}")" == "SUSPENDED" ]]'
  log "Suspended with savepoint $(controller '{.status.lastSavepointPath}')"
}

clean() {
  local was_suspended
  was_suspended="$(flink '{.status.lifecycleState}')"
  "$REPO_ROOT/scripts/simulate-clean-grid.sh" >/dev/null
  log "Grid set to clean"
  wait_until "the job to be running" \
    '[[ "$(flink "{.status.jobStatus.state}/{.status.lifecycleState}")" == "RUNNING/STABLE" ]]'

  if [[ "$was_suspended" != "SUSPENDED" ]]; then
    log "Job was not suspended, nothing to restore"
    return
  fi

  local savepoint jobmanager
  savepoint="$(flink '{.spec.job.initialSavepointPath}')"
  jobmanager="$(kubectl get pod -n "$NS" -l "component=jobmanager,app=$JOB" -o name | head -1)"
  wait_until "Flink to restore from $savepoint" \
    'kubectl logs -n "$NS" "$jobmanager" 2>/dev/null | grep "Restoring job .* located at $savepoint" >/dev/null'
  log "Resumed from savepoint $savepoint"
}

case "${1:-}" in
  dirty) dirty ;;
  clean) clean ;;
  *) echo "usage: $0 dirty|clean" >&2; exit 2 ;;
esac
