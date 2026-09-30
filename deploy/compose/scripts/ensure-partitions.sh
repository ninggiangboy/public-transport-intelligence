#!/usr/bin/env bash
# make ensure-partitions FROM=<YYYY-MM-DD> (DOC-43 §4.1, DOC-14 §7.4): create the fact partitions from FROM to
# today + 7 days as pti_owner, before replaying past days; rows of a day without a partition land in DEFAULT.
#
# Usage: FROM=<YYYY-MM-DD> ensure-partitions.sh docker compose -f ... --env-file ...
set -euo pipefail

compose=("$@")
[[ "${FROM:-}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || {
  echo "usage: make ensure-partitions FROM=<YYYY-MM-DD>" >&2
  exit 2
}

"${compose[@]}" exec -T pg-warehouse psql -v ON_ERROR_STOP=1 -U pti_owner -d pti_warehouse -At -v from="$FROM" <<'SQL'
SELECT t || ': ' || dw.ensure_partitions(t, :'from'::date, current_date + 7) || ' partitions created'
FROM unnest(ARRAY['fact_vehicle_position', 'fact_trip_update', 'fact_ticket_sales']) AS t;
SQL
