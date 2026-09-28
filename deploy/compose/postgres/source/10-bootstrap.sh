#!/usr/bin/env bash
# Bootstrap for pg-source. Runs once, as the superuser, when the data directory is empty.
# The server must run with wal_level=logical (set in the compose command / CNPG parameters).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v ticketing_owner_pw="$TICKETING_OWNER_PASSWORD" \
  -v sim_owner_pw="$SIM_OWNER_PASSWORD" \
  -v source_simulator_pw="$SOURCE_SIMULATOR_PASSWORD" \
  -v debezium_pw="$DEBEZIUM_PASSWORD" \
  -v experiment_runner_pw="$EXPERIMENT_RUNNER_PASSWORD" <<'SQL'
CREATE ROLE ticketing_owner   LOGIN PASSWORD :'ticketing_owner_pw';
CREATE ROLE sim_owner         LOGIN PASSWORD :'sim_owner_pw';
CREATE ROLE source_simulator  LOGIN PASSWORD :'source_simulator_pw'  CONNECTION LIMIT 10;
-- REPLICATION can only be granted by a superuser, which is why roles live in the bootstrap.
CREATE ROLE debezium          LOGIN REPLICATION PASSWORD :'debezium_pw' CONNECTION LIMIT 5;
CREATE ROLE experiment_runner LOGIN PASSWORD :'experiment_runner_pw' CONNECTION LIMIT 5;

CREATE DATABASE ticketing_source OWNER ticketing_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
CREATE DATABASE pti_sim          OWNER sim_owner       ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE ticketing_source FROM PUBLIC;
REVOKE ALL ON DATABASE pti_sim FROM PUBLIC;
SQL
