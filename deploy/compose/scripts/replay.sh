#!/usr/bin/env bash
# make replay (DOC-38 §4.3, DOC-22): queue a RAW_RANGE replay request for etl-batch, as pti_owner.
# FROM and TO are Kafka record times (DR-70). WAIT=1 waits for DONE or FAILED and prints the stats.
#
# Usage: SOURCE=<etl_source> FROM=<ISO> TO=<ISO> [RECOMPUTE=true] [WAIT=1] replay.sh docker compose ...
set -euo pipefail

compose=("$@")
: "${SOURCE:?SOURCE is required}" "${FROM:?FROM is required}" "${TO:?TO is required}"
psql() { "${compose[@]}" exec -T pg-warehouse psql -X -q -t -A -v ON_ERROR_STOP=1 -U pti_owner -d pti_warehouse "$@"; }

# The raw zone needs time to settle before a range can be replayed (DR-70).
if ! python3 -c 'import sys, datetime as d; t = d.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"));
sys.exit(0 if t <= d.datetime.now(d.timezone.utc) - d.timedelta(minutes=10) else 1)' "$TO"; then
  echo "TO must be at least 10 minutes in the past (DR-70)" >&2
  exit 2
fi

id=$(python3 -c 'import uuid; print(uuid.uuid4())')
psql -v id="$id" -v source="$SOURCE" -v from="$FROM" -v to="$TO" -v recompute="${RECOMPUTE:-false}" <<'SQL'
INSERT INTO ops.replay_request (id, kind, source, from_ts, to_ts, recompute_analytics, requested_by)
VALUES (:'id', 'RAW_RANGE', :'source', :'from', :'to', :'recompute', 'user:cli');
SQL
echo "$id"
[[ "${WAIT:-}" == 1 ]] || exit 0

while :; do
  status=$(psql -c "SELECT status FROM ops.replay_request WHERE id = '$id'")
  case "$status" in
    DONE | FAILED) break ;;
  esac
  sleep 2
done
psql -c "SELECT coalesce(stats::text, '{}') || coalesce(' ' || message, '') FROM ops.replay_request WHERE id = '$id'"
[[ "$status" == DONE ]]
