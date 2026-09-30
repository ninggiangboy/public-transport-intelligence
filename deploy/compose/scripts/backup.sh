#!/usr/bin/env bash
# make backup (DOC-43 §3.1): pg_dump of pti_warehouse (without the VP facts), ticketing_source and pti_sim into
# backups/<UTC timestamp>/, a manifest.json, and only the 7 newest backups kept.
#
# pg_dump runs inside the Postgres containers over the local socket, so it always matches the server version and no
# password is handled here.
#
# Usage: backup.sh docker compose -f ... --env-file ...
set -euo pipefail

compose=("$@")
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="$ROOT/backups/$TS"
KEEP=7
mkdir -p "$DIR"

dump() { # service user database file [pg_dump options…]
  local service="$1" user="$2" database="$3" file="$4"
  shift 4
  local started=$SECONDS
  "${compose[@]}" exec -T "$service" pg_dump -U "$user" -d "$database" -Fc -Z zstd:3 "$@" >"$DIR/$file"
  echo "$file: $(du -h "$DIR/$file" | cut -f1) in $((SECONDS - started)) s"
}

# Row counts and the newest event of each fact table, and the ACTIVE feed (DOC-43 §3.1), read just before the dump:
# live data keeps arriving, so the dump holds at least these rows.
facts="$("${compose[@]}" exec -T pg-warehouse psql -U pti_owner -d pti_warehouse -AtF '|' -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'fact_trip_update', count(*), coalesce(max(event_timestamp)::text, '') FROM dw.fact_trip_update
UNION ALL
SELECT 'fact_ticket_sales', count(*), coalesce(max(event_timestamp)::text, '') FROM dw.fact_ticket_sales
UNION ALL
SELECT 'feed', 0, coalesce((SELECT feed_hash FROM dw.gtfs_feed_version WHERE status = 'ACTIVE'), '');
SQL
)"

started=$SECONDS
dump pg-warehouse pti_owner pti_warehouse pti_warehouse.dump \
  --exclude-table-data='dw.fact_vehicle_position*' \
  --exclude-table-data='ops.dedup_registry' \
  --exclude-table-data='exp.*'
dump pg-source ticketing_owner ticketing_source ticketing_source.dump
dump pg-source sim_owner pti_sim pti_sim.dump

python3 - "$DIR" "$TS" "$((SECONDS - started))" "$facts" <<'EOF'
import hashlib
import json
import sys
from pathlib import Path

directory, ts, seconds, facts = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
files = {}
for path in sorted(directory.glob("*.dump")):
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    files[path.name] = {"bytes": path.stat().st_size, "sha256": digest.hexdigest()}
tables, feed = {}, None
for line in facts.splitlines():
    name, count, newest = line.split("|")
    if name == "feed":
        feed = newest or None
    else:
        tables[name] = {"rows": int(count), "max_event_timestamp": newest or None}
manifest = {"ts": ts, "seconds": seconds, "files": files, "active_feed": feed, "tables": tables}
(directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
EOF

# Keep the newest $KEEP backups (BR-06).
find "$ROOT/backups" -mindepth 1 -maxdepth 1 -type d -name '2*' | sort -r | tail -n +$((KEEP + 1)) |
  while read -r old; do
    echo "Removing old backup $(basename "$old")"
    rm -rf "$old"
  done
echo "Backup $TS written to backups/$TS in $((SECONDS - started)) s"
