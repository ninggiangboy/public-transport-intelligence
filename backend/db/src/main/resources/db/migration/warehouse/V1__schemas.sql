-- pti_warehouse, run by pti_owner. Login roles already exist (bootstrap, DOC-17 §3);
-- this migration only creates schemas. Grants live in R__grants.sql.
CREATE SCHEMA dw      AUTHORIZATION pti_owner;  -- GTFS static, dimensions, facts
CREATE SCHEMA ops     AUTHORIZATION pti_owner;  -- ETL operations, DLQ, replay, alerts
CREATE SCHEMA insight AUTHORIZATION pti_owner;  -- analytics output and state
CREATE SCHEMA batch   AUTHORIZATION pti_owner;  -- Spring Batch metadata (DR-62)
CREATE SCHEMA exp     AUTHORIZATION pti_owner;  -- baseline shadow tables (DR-27)

-- flyway_schema_history stays in public; nobody else may use that schema.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
