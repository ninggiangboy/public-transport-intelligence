#!/usr/bin/env bash
# Bootstrap for pg-warehouse. Runs once, as the superuser, when the data directory is empty
# (docker-entrypoint-initdb.d). On k3d the same roles are declared in the CNPG Cluster
# (spec.managed.roles) and the database in spec.bootstrap.initdb.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v pti_owner_pw="$PTI_OWNER_PASSWORD" \
  -v etl_writer_pw="$ETL_WRITER_PASSWORD" \
  -v triage_writer_pw="$TRIAGE_WRITER_PASSWORD" \
  -v api_reader_pw="$API_READER_PASSWORD" \
  -v replay_operator_pw="$REPLAY_OPERATOR_PASSWORD" \
  -v experiment_runner_pw="$EXPERIMENT_RUNNER_PASSWORD" <<'SQL'
CREATE ROLE pti_owner         LOGIN PASSWORD :'pti_owner_pw';
CREATE ROLE etl_writer        LOGIN PASSWORD :'etl_writer_pw'        CONNECTION LIMIT 100;
CREATE ROLE triage_writer     LOGIN PASSWORD :'triage_writer_pw'     CONNECTION LIMIT 20;
CREATE ROLE api_reader        LOGIN PASSWORD :'api_reader_pw'        CONNECTION LIMIT 80;
CREATE ROLE replay_operator   LOGIN PASSWORD :'replay_operator_pw'   CONNECTION LIMIT 30;
CREATE ROLE experiment_runner LOGIN PASSWORD :'experiment_runner_pw' CONNECTION LIMIT 5;

CREATE DATABASE pti_warehouse OWNER pti_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE pti_warehouse FROM PUBLIC;
SQL
