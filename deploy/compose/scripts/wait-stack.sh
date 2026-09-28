#!/usr/bin/env bash
# Waits until the compose stack has settled (make up, DOC-38 §4): every long-running service is healthy (or running,
# when it has no healthcheck) and every one-shot job in $JOBS has exited 0. `docker compose up --wait` cannot do this:
# once a one-shot job that no other service depends on exits, it reports a failure, even for exit code 0.
#
# Usage: JOBS="kafka-init ..." wait-stack.sh docker compose -f ... --profile '*'
set -euo pipefail

timeout="${WAIT_TIMEOUT_SECONDS:-300}"
jobs=" ${JOBS:-} "
deadline=$((SECONDS + timeout))

while :; do
  pending=()
  failed=()
  # '|' rather than a tab: read collapses consecutive tabs and would drop an empty Health column.
  while IFS='|' read -r service state health exit_code; do
    if [[ "$jobs" == *" $service "* ]]; then
      case "$state" in
        exited) [[ "$exit_code" == 0 ]] || failed+=("$service (exit code $exit_code)") ;;
        dead) failed+=("$service (dead)") ;;
        *) pending+=("$service") ;;
      esac
    else
      case "$state/$health" in
        running/healthy | running/) ;;
        */unhealthy) failed+=("$service (unhealthy)") ;;
        exited/* | dead/*) failed+=("$service ($state, exit code $exit_code)") ;;
        *) pending+=("$service") ;;
      esac
    fi
  done < <("$@" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}')

  if ((${#failed[@]})); then
    echo "Failed: ${failed[*]}. See 'make logs S=<service>'." >&2
    exit 1
  fi
  if ((${#pending[@]} == 0)); then
    echo "Stack is up: services healthy, one-shot jobs finished."
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    echo "Not settled after ${timeout}s: ${pending[*]}" >&2
    exit 1
  fi
  sleep 2
done
