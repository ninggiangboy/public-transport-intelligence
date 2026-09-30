#!/usr/bin/env bash
# make reset-warehouse (DOC-38 §4.2, DOC-39 §6): drop and recreate pti_warehouse, migrate it, restart the apps.
# Kafka, the raw zone and pg-source stay. Consumer offsets stay too, unless OFFSETS=earliest.
#
# Usage: [OFFSETS=earliest] reset-warehouse.sh docker compose -f ... --env-file ...
set -euo pipefail

compose=("$@")
apps=(etl-stream etl-batch etl-stream-baseline api triage-worker)
running=()
for app in "${apps[@]}"; do
  if [[ -n "$("${compose[@]}" --profile '*' ps -q --status running "$app" 2>/dev/null)" ]]; then
    running+=("$app")
  fi
done
echo "Stopping ${running[*]:-nothing}"
if ((${#running[@]})); then
  "${compose[@]}" --profile '*' stop "${running[@]}"
fi

# Same statements as postgres/warehouse/10-bootstrap.sh (DOC-17 §3.1); the roles are cluster-wide and survive.
"${compose[@]}" exec -T pg-warehouse psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'
DROP DATABASE IF EXISTS pti_warehouse WITH (FORCE);
CREATE DATABASE pti_warehouse OWNER pti_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE pti_warehouse FROM PUBLIC;
SQL

if [[ "${OFFSETS:-}" == earliest ]]; then
  for group in pti-etl-gtfs-rt pti-etl-ticketing pti-exp-baseline; do
    "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server kafka:9092 --group "$group" --reset-offsets --to-earliest --all-topics --execute \
      >/dev/null 2>&1 || echo "Group $group not reset (it may not exist yet)"
  done
fi

"${compose[@]}" --profile core run --rm --no-deps db-migrate
if ((${#running[@]})); then
  "${compose[@]}" --profile '*' up -d --no-deps "${running[@]}"
fi
echo "pti_warehouse recreated; etl-batch loads the GTFS feed again on startup (DOC-21 §1)."
