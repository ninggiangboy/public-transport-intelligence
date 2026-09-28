-- pti_sim, run by sim_owner. Ground truth for loss and duplicate measurement (DR-28, DR-64).

CREATE SCHEMA sim AUTHORIZATION sim_owner;

CREATE TABLE sim.sim_scenario_run (
  run_id            UUID        PRIMARY KEY,
  scenario          TEXT        NOT NULL CHECK (scenario ~ '^[a-z][a-z0-9-]{1,40}$'),
  params            JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status            TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'FAILED')),
  requested_by      TEXT        NOT NULL,  -- 'user:<username>' | 'experiment:<EXP-xx>/<run>' | 'cli'
  experiment_run_id TEXT        NULL,
  started_at        TIMESTAMPTZ NOT NULL,
  planned_end_at    TIMESTAMPTZ NULL,
  ended_at          TIMESTAMPTZ NULL,
  CHECK (ended_at IS NULL OR ended_at >= started_at),
  CHECK ((status = 'RUNNING') = (ended_at IS NULL))
);
CREATE INDEX sim_scenario_run_started_idx ON sim.sim_scenario_run (started_at);

-- One row per message acknowledged by Kafka (written from the producer send callback).
CREATE TABLE sim.sim_ledger (
  produced_at      TIMESTAMPTZ NOT NULL,
  message_id       UUID        NOT NULL,
  entity_type      TEXT        NOT NULL CHECK (entity_type IN ('VEHICLE_POSITION', 'TRIP_UPDATE')),
  kafka_topic      TEXT        NOT NULL,
  kafka_partition  INT         NOT NULL,
  kafka_offset     BIGINT      NOT NULL,
  business_keys    TEXT[]      NOT NULL CHECK (cardinality(business_keys) >= 1),
  event_timestamp  TIMESTAMPTZ NOT NULL,
  schema_version   SMALLINT    NOT NULL,
  payload_hash     CHAR(64)    NULL,      -- NULL only when the payload is deliberately not valid JSON
  intended_invalid BOOLEAN     NOT NULL DEFAULT false,
  invalid_kind     TEXT        NULL,
  is_resend        BOOLEAN     NOT NULL DEFAULT false,
  resend_of        UUID        NULL,
  scenario_run_id  UUID        NULL,
  PRIMARY KEY (produced_at, message_id),
  CHECK (intended_invalid = (invalid_kind IS NOT NULL)),
  CHECK (is_resend = (resend_of IS NOT NULL)),
  CHECK (payload_hash IS NOT NULL OR invalid_kind IS NOT DISTINCT FROM 'malformed_json')
) PARTITION BY RANGE (produced_at);

CREATE TABLE sim.sim_ledger_default PARTITION OF sim.sim_ledger DEFAULT;

-- Partition maintenance, called by source-simulator at startup and hourly (retention
-- pti.sim.ledger.retention, default 2 days). Day boundaries are UTC.
CREATE FUNCTION sim.ensure_ledger_partitions(p_from DATE, p_to DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_day     DATE := p_from;
  v_name    TEXT;
  v_created INT  := 0;
BEGIN
  IF p_to < p_from OR p_to - p_from > 31 THEN
    RAISE EXCEPTION 'invalid range % .. %', p_from, p_to USING ERRCODE = '22023';
  END IF;
  WHILE v_day <= p_to LOOP
    v_name := 'sim_ledger_p' || to_char(v_day, 'YYYYMMDD');
    IF to_regclass('sim.' || v_name) IS NULL THEN
      EXECUTE format(
        'CREATE TABLE sim.%I PARTITION OF sim.sim_ledger FOR VALUES FROM (%L) TO (%L)',
        v_name, v_day::timestamp AT TIME ZONE 'UTC', (v_day + 1)::timestamp AT TIME ZONE 'UTC');
      v_created := v_created + 1;
    END IF;
    v_day := v_day + 1;
  END LOOP;
  RETURN v_created;
END $$;

CREATE FUNCTION sim.drop_ledger_partitions_before(p_cutoff DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
  v_part    RECORD;
  v_dropped INT := 0;
BEGIN
  FOR v_part IN
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'sim.sim_ledger'::regclass
      AND c.relname ~ '^sim_ledger_p[0-9]{8}$'
      AND to_date(substring(c.relname FROM '[0-9]{8}$'), 'YYYYMMDD') < p_cutoff
  LOOP
    EXECUTE format('ALTER TABLE sim.sim_ledger DETACH PARTITION sim.%I', v_part.relname);
    EXECUTE format('DROP TABLE sim.%I', v_part.relname);
    v_dropped := v_dropped + 1;
  END LOOP;
  DELETE FROM sim.sim_ledger_default WHERE produced_at < p_cutoff::timestamp AT TIME ZONE 'UTC';
  RETURN v_dropped;
END $$;

REVOKE ALL ON FUNCTION sim.ensure_ledger_partitions(DATE, DATE)  FROM PUBLIC;
REVOKE ALL ON FUNCTION sim.drop_ledger_partitions_before(DATE)   FROM PUBLIC;

SELECT sim.ensure_ledger_partitions(current_date - 1, current_date + 2);
