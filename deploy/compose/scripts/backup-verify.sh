#!/usr/bin/env bash
# make backup-verify [TS=<dir>] (DOC-43 §3.3, BR-01): SHA-256 against the manifest, pg_restore --list of every dump,
# then a restore of pti_warehouse.dump into pti_warehouse_verify whose row counts must match the manifest.
#
# Usage: [TS=<dir>] backup-verify.sh docker compose -f ... --env-file ...
set -euo pipefail

compose=("$@")
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TS="${TS:-$(find "$ROOT/backups" -mindepth 1 -maxdepth 1 -type d -name '2*' 2>/dev/null | sort | tail -1 | xargs -n1 basename 2>/dev/null || true)}"
DIR="$ROOT/backups/$TS"
[[ -n "$TS" && -f "$DIR/manifest.json" ]] || { echo "No backup with a manifest in backups/${TS}" >&2; exit 2; }
started=$SECONDS
echo "Verifying backups/$TS"

python3 - "$DIR" <<'EOF'
import hashlib
import json
import sys
from pathlib import Path

directory = Path(sys.argv[1])
manifest = json.loads((directory / "manifest.json").read_text())
for name, expected in manifest["files"].items():
    digest = hashlib.sha256()
    with (directory / name).open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    if digest.hexdigest() != expected["sha256"]:
        sys.exit(f"{name}: SHA-256 differs from the manifest")
    print(f"{name}: SHA-256 ok")
EOF

for dump in "$DIR"/*.dump; do
  "${compose[@]}" exec -T pg-warehouse pg_restore --list <"$dump" >/dev/null
  echo "$(basename "$dump"): pg_restore --list ok"
done

psql_su() { "${compose[@]}" exec -T pg-warehouse psql -v ON_ERROR_STOP=1 -U postgres -d postgres -q "$@"; }
psql_su -c "DROP DATABASE IF EXISTS pti_warehouse_verify WITH (FORCE)" \
  -c "CREATE DATABASE pti_warehouse_verify OWNER pti_owner TEMPLATE template0"
trap 'psql_su -c "DROP DATABASE IF EXISTS pti_warehouse_verify WITH (FORCE)"' EXIT

"${compose[@]}" cp "$DIR/pti_warehouse.dump" pg-warehouse:/tmp/pti_warehouse_verify.dump >/dev/null
restore_started=$SECONDS
"${compose[@]}" exec -T pg-warehouse pg_restore -U pti_owner -d pti_warehouse_verify --exit-on-error -j 4 \
  /tmp/pti_warehouse_verify.dump
echo "pg_restore into pti_warehouse_verify: $((SECONDS - restore_started)) s"
"${compose[@]}" exec -T pg-warehouse rm -f /tmp/pti_warehouse_verify.dump

counts="$("${compose[@]}" exec -T pg-warehouse psql -U pti_owner -d pti_warehouse_verify -AtF '|' -v ON_ERROR_STOP=1 \
  -c "SELECT 'fact_trip_update', count(*) FROM dw.fact_trip_update UNION ALL
      SELECT 'fact_ticket_sales', count(*) FROM dw.fact_ticket_sales")"
python3 - "$DIR" "$counts" <<'EOF'
import json
import sys
from pathlib import Path

manifest = json.loads((Path(sys.argv[1]) / "manifest.json").read_text())
for line in sys.argv[2].splitlines():
    table, count = line.split("|")
    want = manifest["tables"][table]["rows"]
    # The manifest counts rows just before the dump; live writes during the dump only add rows.
    if int(count) < want:
        sys.exit(f"{table}: {count} rows restored, fewer than the {want} of the manifest")
    print(f"{table}: {count} rows restored, manifest {want}")
EOF
echo "Backup $TS verified in $((SECONDS - started)) s"
