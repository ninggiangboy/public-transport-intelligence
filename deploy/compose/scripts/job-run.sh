#!/usr/bin/env bash
# make job-run (DOC-38 §4.3, DOC-19 §7.3): queue a RUN job request for etl-batch, as pti_owner. PARAMS is k=v,k=v;
# a list value separates its items with '+'. WAIT=1 waits for DONE, REJECTED or FAILED and exits 1 unless DONE.
#
# Usage: NAME=<Job> [PARAMS='k=v,…'] [WAIT=1] job-run.sh docker compose ...
set -euo pipefail

compose=("$@")
: "${NAME:?NAME is required}"
psql() { "${compose[@]}" exec -T pg-warehouse psql -X -q -t -A -v ON_ERROR_STOP=1 -U pti_owner -d pti_warehouse "$@"; }

params=$(python3 -c 'import json, sys
pairs = [p for p in sys.argv[1].split(",") if p]
print(json.dumps({k: v for k, v in (p.split("=", 1) for p in pairs)}))' "${PARAMS:-}")
id=$(python3 -c 'import uuid; print(uuid.uuid4())')
psql -v id="$id" -v name="$NAME" -v params="$params" <<'SQL'
INSERT INTO ops.job_request (id, kind, job_name, job_parameters, requested_by)
VALUES (:'id', 'RUN', :'name', :'params'::jsonb, 'user:cli');
SQL
echo "$id"
[[ "${WAIT:-}" == 1 ]] || exit 0

while :; do
  status=$(psql -c "SELECT status FROM ops.job_request WHERE id = '$id'")
  case "$status" in
    DONE | FAILED | REJECTED) break ;;
  esac
  sleep 2
done
psql -c "SELECT status || coalesce(': ' || message, '') FROM ops.job_request WHERE id = '$id'"
[[ "$status" == DONE ]]
