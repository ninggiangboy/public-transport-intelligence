#!/usr/bin/env bash
# make restore-warehouse TS=<dir> (DOC-43 §4.2 steps 1–4, 6): stop the apps, recreate pti_warehouse, pg_restore the
# dump, apply newer migrations, start the apps again. Then run ensure-partitions and replay the gap (steps 5, 7–8).
#
# Usage: TS=<dir> restore-warehouse.sh docker compose -f ... --env-file ...
set -euo pipefail

compose=("$@")
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DIR="$ROOT/backups/${TS:-}"
[[ -n "${TS:-}" && -f "$DIR/pti_warehouse.dump" ]] || {
  echo "usage: make restore-warehouse TS=<dir under backups/>; run make backup-verify first" >&2
  exit 2
}
started=$SECONDS

apps=(etl-stream etl-batch etl-stream-baseline api triage-worker)
running=()
for app in "${apps[@]}"; do
  if [[ -n "$("${compose[@]}" --profile '*' ps -q --status running "$app" 2>/dev/null)" ]]; then
    running+=("$app")
  fi
done
echo "Stopping ${running[*]:-nothing} at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
if ((${#running[@]})); then
  "${compose[@]}" --profile '*' stop "${running[@]}"
fi

# Same statements as postgres/warehouse/10-bootstrap.sh (DOC-17 §3.1); the roles are cluster-wide and survive.
"${compose[@]}" exec -T pg-warehouse psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
DROP DATABASE IF EXISTS pti_warehouse WITH (FORCE);
CREATE DATABASE pti_warehouse OWNER pti_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE pti_warehouse FROM PUBLIC;
SQL

"${compose[@]}" cp "$DIR/pti_warehouse.dump" pg-warehouse:/tmp/pti_warehouse_restore.dump >/dev/null
restore_started=$SECONDS
"${compose[@]}" exec -T pg-warehouse pg_restore -U pti_owner -d pti_warehouse --exit-on-error -j 4 \
  /tmp/pti_warehouse_restore.dump
echo "pg_restore: $((SECONDS - restore_started)) s"
"${compose[@]}" exec -T pg-warehouse rm -f /tmp/pti_warehouse_restore.dump

# A dump holds no database-level grants (CONNECT), and Flyway skips R__grants.sql while its checksum is unchanged:
# forget that it ran, so that db-migrate applies the grants again (DOC-17).
"${compose[@]}" exec -T pg-warehouse psql -v ON_ERROR_STOP=1 -U pti_owner -d pti_warehouse -q \
  -c "DELETE FROM public.flyway_schema_history WHERE script = 'R__grants.sql'"
"${compose[@]}" --profile core run --rm --no-deps db-migrate
if ((${#running[@]})); then
  "${compose[@]}" --profile '*' up -d --no-deps "${running[@]}"
fi
echo "pti_warehouse restored from $TS in $((SECONDS - started)) s."
echo "Next: make ensure-partitions FROM=<oldest day>, then replay from the dump time − 1 hour (DOC-43 §4.2 steps 5, 7–8)."
