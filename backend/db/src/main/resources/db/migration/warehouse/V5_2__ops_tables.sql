-- ETL operations: audit, checkpoints, locks, DLQ, replay, dedup, flags, DQ, alerts (DOC-15).

-- Data sources, shared by every table that says "which pipeline".
CREATE DOMAIN ops.etl_source AS TEXT CHECK (VALUE IN (
  'GTFS_RT_VEHICLE_POSITION', 'GTFS_RT_TRIP_UPDATE', 'TICKETING_SALES', 'TICKETING_SALE_POINTS', 'GTFS_STATIC'));

-- One row per streaming micro-batch (DR-22, DR-63). Written inside the chunk transaction, so a
-- rolled-back chunk leaves no row; FAILED rows are written best-effort in a separate transaction.
CREATE TABLE ops.etl_stream_batch (
  batch_id          UUID            PRIMARY KEY,               -- UUIDv7, generated before the transaction
  source            ops.etl_source  NOT NULL,
  listener_id       TEXT            NOT NULL,                  -- e.g. gtfs-rt-vehicle-position
  consumer_group    TEXT            NOT NULL,
  instance_id       TEXT            NOT NULL,                  -- pod name / hostname
  offsets           JSONB           NOT NULL,                  -- {"<topic>-<partition>": [first, last], ...}
  status            TEXT            NOT NULL CHECK (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS', 'FAILED')),
  write_mode        TEXT            NOT NULL CHECK (write_mode IN ('BATCH', 'SCAN')),
  records_read      INT             NOT NULL CHECK (records_read >= 0),
  records_written   INT             NOT NULL CHECK (records_written >= 0),
  records_skipped   INT             NOT NULL CHECK (records_skipped >= 0),
  records_duplicate INT             NOT NULL CHECK (records_duplicate >= 0),
  min_event_ts      TIMESTAMPTZ     NULL,
  max_event_ts      TIMESTAMPTZ     NULL,
  min_record_ts     TIMESTAMPTZ     NULL,                      -- oldest Kafka record timestamp (latency, DR-57)
  started_at        TIMESTAMPTZ     NOT NULL,
  finished_at       TIMESTAMPTZ     NOT NULL,
  error_class       TEXT            NULL,
  error_message     TEXT            NULL,
  CHECK (finished_at >= started_at),
  CHECK ((status = 'FAILED') = (error_class IS NOT NULL)),
  CHECK (status <> 'COMPLETED' OR records_skipped = 0)
);
CREATE INDEX etl_stream_batch_started_idx ON ops.etl_stream_batch (started_at);
CREATE INDEX etl_stream_batch_listener_idx ON ops.etl_stream_batch (listener_id, started_at);
-- Serves ops.ops_job_run_v, which groups stream batches per minute (date_bin is IMMUTABLE, date_trunc on
-- TIMESTAMPTZ is not, so only date_bin can be indexed and pushed down through the GROUP BY).
CREATE INDEX etl_stream_batch_minute_idx
  ON ops.etl_stream_batch (date_bin('1 minute', started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z'));

-- Spring Batch ships no indexes on its foreign keys. Needed by JobRepository lookups, by
-- BatchMetadataCleanupJob deletes and by ops.ops_job_run_v.
CREATE INDEX batch_job_execution_instance_idx ON batch.batch_job_execution (job_instance_id);
CREATE INDEX batch_job_execution_start_idx    ON batch.batch_job_execution ((start_time AT TIME ZONE 'UTC'));
CREATE INDEX batch_job_execution_params_idx   ON batch.batch_job_execution_params (job_execution_id);
CREATE INDEX batch_step_execution_job_idx     ON batch.batch_step_execution (job_execution_id);

-- One row per Spring Batch step execution (DR-63), written by BatchIdStepListener.beforeStep.
CREATE TABLE ops.etl_batch_step (
  batch_id          UUID        PRIMARY KEY,
  job_execution_id  BIGINT      NOT NULL,
  step_execution_id BIGINT      NOT NULL UNIQUE
                                REFERENCES batch.batch_step_execution (step_execution_id) ON DELETE CASCADE,
  job_name          TEXT        NOT NULL,
  step_name         TEXT        NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX etl_batch_step_job_idx ON ops.etl_batch_step (job_execution_id);

-- Business watermarks of batch jobs (e.g. last loaded feed hash).
CREATE TABLE ops.etl_checkpoint (
  checkpoint_key   TEXT        PRIMARY KEY CHECK (checkpoint_key ~ '^[a-z][a-z0-9.-]+$'),
  watermark        TEXT        NOT NULL,
  watermark_ts     TIMESTAMPTZ NULL,
  job_execution_id BIGINT      NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ShedLock (DR-24). Layout required by JdbcTemplateLockProvider; used with usingDbTime().
CREATE TABLE ops.shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMP    NOT NULL,
  locked_at  TIMESTAMP    NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);

-- Dead letter queue (DR-18). raw_payload is PII-scrubbed before insert (DR-60).
CREATE TABLE ops.dead_letter (
  id                  UUID           PRIMARY KEY,               -- UUIDv7
  source              ops.etl_source NOT NULL,
  stage               TEXT           NOT NULL CHECK (stage IN ('DESERIALIZE', 'SCHEMA', 'BUSINESS', 'DEDUP', 'LOAD', 'QUALITY')),
  error_class         TEXT           NOT NULL,
  error_message       TEXT           NOT NULL CHECK (length(error_message) <= 4000),
  rule_id             TEXT           NULL,                      -- DQ-xx for SCHEMA/QUALITY/BUSINESS/DEDUP (DR-69)
  raw_payload         TEXT           NOT NULL CHECK (octet_length(raw_payload) <= 1048576),
  edited_payload      JSONB          NULL,
  kafka_topic         TEXT           NULL,
  kafka_partition     INT            NULL,
  kafka_offset        BIGINT         NULL,
  kafka_timestamp     TIMESTAMPTZ    NULL,
  business_key        TEXT           NULL,
  batch_id            UUID           NOT NULL,
  status              TEXT           NOT NULL DEFAULT 'NEW' CHECK (status IN (
                        'NEW', 'TRIAGING', 'TRIAGED', 'AUTO_REPLAY_SCHEDULED', 'PENDING_CONFIRM', 'MANUAL',
                        'REPLAY_REQUESTED', 'REPLAYED', 'DISCARDED', 'RESOLVED')),
  category            TEXT           NULL CHECK (category IN (
                        'schema_violation', 'referential_integrity', 'upstream_api_error', 'transient_network', 'unknown')),
  category_confidence NUMERIC(4,3)   NULL CHECK (category_confidence BETWEEN 0 AND 1),
  severity            SMALLINT       NULL CHECK (severity BETWEEN 0 AND 2),
  severity_confidence NUMERIC(4,3)   NULL CHECK (severity_confidence BETWEEN 0 AND 1),
  model_version       TEXT           NULL,
  triaged_at          TIMESTAMPTZ    NULL,
  triage_attempts     SMALLINT       NOT NULL DEFAULT 0 CHECK (triage_attempts >= 0),
  triage_lease_until  TIMESTAMPTZ    NULL,                      -- DR-37
  auto_replay_count   SMALLINT       NOT NULL DEFAULT 0 CHECK (auto_replay_count BETWEEN 0 AND 2),  -- hard stop, SDD 9.6
  replay_count        SMALLINT       NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
  last_replay_at      TIMESTAMPTZ    NULL,
  resolved_by         TEXT           NULL,
  resolved_at         TIMESTAMPTZ    NULL,
  created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
  CHECK ((kafka_topic IS NULL) = (kafka_offset IS NULL)),
  CHECK (status <> 'TRIAGING' OR triage_lease_until IS NOT NULL),
  CHECK (status NOT IN ('DISCARDED', 'RESOLVED') OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL)),
  CHECK ((category IS NULL) = (category_confidence IS NULL)),
  CHECK ((severity IS NULL) = (severity_confidence IS NULL))
);
CREATE INDEX dead_letter_triage_queue_idx ON ops.dead_letter (created_at) WHERE status = 'NEW';
CREATE INDEX dead_letter_lease_idx        ON ops.dead_letter (triage_lease_until) WHERE status = 'TRIAGING';
CREATE INDEX dead_letter_list_idx         ON ops.dead_letter (status, created_at DESC);
CREATE INDEX dead_letter_source_idx       ON ops.dead_letter (source, created_at DESC);
CREATE INDEX dead_letter_batch_idx        ON ops.dead_letter (batch_id);
-- One dead letter per Kafka record: redelivery after a crash does not duplicate it; replay updates it (DOC-22 §1.3, DR-70).
CREATE UNIQUE INDEX dead_letter_kafka_pos_uk ON ops.dead_letter (kafka_topic, kafka_partition, kafka_offset)
  WHERE kafka_topic IS NOT NULL;

-- Append-only log of every automatic and manual DLQ action.
CREATE TABLE ops.dlq_action_log (
  id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dead_letter_id UUID         NOT NULL REFERENCES ops.dead_letter (id) ON DELETE CASCADE,
  action         TEXT         NOT NULL CHECK (action IN (
                   'TRIAGED', 'TRIAGE_FAILED', 'AUTO_REPLAY_SCHEDULED', 'CONFIRM_REQUESTED', 'CONFIRMED',
                   'MANUAL_REQUIRED', 'EDITED', 'REPLAY_REQUESTED', 'REPLAYED', 'REPLAY_FAILED',
                   'DISCARDED', 'RESOLVED')),
  actor          TEXT         NOT NULL CHECK (actor ~ '^(auto|system:[a-z-]+|user:.+)$'),
  confidence     NUMERIC(4,3) NULL CHECK (confidence BETWEEN 0 AND 1),
  details        JSONB        NOT NULL DEFAULT '{}'::jsonb,
  at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX dlq_action_log_dead_letter_idx ON ops.dlq_action_log (dead_letter_id, at);
CREATE INDEX dlq_action_log_at_idx          ON ops.dlq_action_log (at);

-- Replay requests: written by api (or triage-worker for auto-replay), executed by etl-batch (ADR-0013).
CREATE TABLE ops.replay_request (
  id                  UUID           PRIMARY KEY,
  kind                TEXT           NOT NULL CHECK (kind IN ('RAW_RANGE', 'DLQ_RECORD')),
  source              ops.etl_source NOT NULL,
  from_ts             TIMESTAMPTZ    NULL,
  to_ts               TIMESTAMPTZ    NULL,
  dead_letter_id      UUID           NULL REFERENCES ops.dead_letter (id) ON DELETE SET NULL,
  recompute_analytics BOOLEAN        NOT NULL DEFAULT false,
  requested_by        TEXT           NOT NULL CHECK (requested_by ~ '^(auto|user:.+)$'),
  idempotency_key     TEXT           NULL,
  requested_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
  status              TEXT           NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
  job_execution_id    BIGINT         NULL,
  started_at          TIMESTAMPTZ    NULL,
  finished_at         TIMESTAMPTZ    NULL,
  stats               JSONB          NULL,
  message             TEXT           NULL,
  CHECK (kind <> 'RAW_RANGE'  OR (from_ts IS NOT NULL AND to_ts IS NOT NULL AND from_ts < to_ts
                                  AND to_ts - from_ts <= interval '7 days' AND dead_letter_id IS NULL)),
  CHECK (kind <> 'DLQ_RECORD' OR (from_ts IS NULL AND to_ts IS NULL AND NOT recompute_analytics)),
  CHECK (status NOT IN ('DONE', 'FAILED') OR finished_at IS NOT NULL),
  UNIQUE (requested_by, idempotency_key)
);
-- FR-12.3: one raw-zone replay in flight per source (second request -> unique violation -> 409).
CREATE UNIQUE INDEX replay_request_one_raw_per_source ON ops.replay_request (source)
  WHERE kind = 'RAW_RANGE' AND status IN ('PENDING', 'RUNNING');
-- A DLQ record cannot be queued twice.
CREATE UNIQUE INDEX replay_request_one_per_record ON ops.replay_request (dead_letter_id)
  WHERE kind = 'DLQ_RECORD' AND status IN ('PENDING', 'RUNNING');
CREATE INDEX replay_request_pending_idx ON ops.replay_request (requested_at) WHERE status = 'PENDING';

-- Manual job control: written by api, executed by etl-batch through JobOperator (DR-62).
CREATE TABLE ops.job_request (
  id                      UUID        PRIMARY KEY,
  kind                    TEXT        NOT NULL CHECK (kind IN ('RUN', 'RESTART', 'STOP')),
  job_name                TEXT        NOT NULL CHECK (job_name ~ '^[A-Z][A-Za-z]+Job$'),
  job_parameters          JSONB       NOT NULL DEFAULT '{}'::jsonb,
  target_job_execution_id BIGINT      NULL,
  requested_by            TEXT        NOT NULL CHECK (requested_by ~ '^user:.+$'),
  idempotency_key         TEXT        NULL,
  requested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  status                  TEXT        NOT NULL DEFAULT 'PENDING'
                                      CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED')),
  job_execution_id        BIGINT      NULL,
  started_at              TIMESTAMPTZ NULL,
  finished_at             TIMESTAMPTZ NULL,
  message                 TEXT        NULL,
  CHECK ((kind = 'RUN') = (target_job_execution_id IS NULL)),
  CHECK (status NOT IN ('DONE', 'FAILED', 'REJECTED') OR finished_at IS NOT NULL),
  UNIQUE (requested_by, idempotency_key)
);
CREATE INDEX job_request_pending_idx ON ops.job_request (requested_at) WHERE status = 'PENDING';

-- Payload hashes seen recently (DR-16). UNLOGGED: an optimization only, correctness does not
-- depend on it, so losing it on crash or failover is acceptable and saves WAL.
CREATE UNLOGGED TABLE ops.dedup_registry (
  source        ops.etl_source NOT NULL,
  payload_hash  CHAR(64)       NOT NULL,
  first_seen_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
  batch_id      UUID           NOT NULL,
  PRIMARY KEY (source, payload_hash)
);
CREATE INDEX dedup_registry_seen_idx ON ops.dedup_registry (first_seen_at);

-- Runtime on/off switches (DR-19). Thresholds stay in configuration files.
CREATE TABLE ops.runtime_flag (
  key         TEXT        PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9-]*(\.[a-z0-9-]+)+$'),
  value       JSONB       NOT NULL,
  description TEXT        NOT NULL,
  updated_by  TEXT        NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ops.runtime_flag (key, value, description, updated_by) VALUES
  ('etl.consumer.gtfs-rt.paused',   'false', 'Pause the GTFS-realtime listeners (vehicle positions and trip updates).', 'migration'),
  ('etl.consumer.ticketing.paused', 'false', 'Pause the ticketing CDC listeners.', 'migration'),
  ('triage.dlq.enabled',            'true',  'Classify new dead letters with the decision model.', 'migration'),
  ('triage.auto-replay.enabled',    'true',  'Allow automatic replay of dead letters above the confidence threshold.', 'migration'),
  ('triage.ticketing.enabled',      'true',  'Classify ticketing anomalies with the decision model.', 'migration'),
  ('triage.disruption.enabled',     'true',  'Enrich service disruptions with the decision model.', 'migration'),
  ('triage.dispatch.enabled',       'true',  'Suggest dispatch actions for bunching episodes.', 'migration');

-- Post-write data quality results (DR-25).
CREATE TABLE ops.dq_check_result (
  id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_id         TEXT        NOT NULL CHECK (rule_id ~ '^DQ-[0-9]{2}$'),
  scope           TEXT        NOT NULL CHECK (scope IN ('BATCH', 'TABLE')),
  table_name      TEXT        NOT NULL,
  batch_id        UUID        NULL,
  violation_count BIGINT      NOT NULL CHECK (violation_count >= 0),
  sample          JSONB       NULL,       -- at most 10 offending keys
  checked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((scope = 'BATCH') = (batch_id IS NOT NULL))
);
CREATE INDEX dq_check_result_rule_idx  ON ops.dq_check_result (rule_id, checked_at DESC);
CREATE INDEX dq_check_result_batch_idx ON ops.dq_check_result (batch_id) WHERE batch_id IS NOT NULL;

-- Unified alert feed (DR-17, ADR-0023).
CREATE TABLE ops.alert_event (
  id              UUID        PRIMARY KEY,
  type            TEXT        NOT NULL CHECK (type IN ('DISRUPTION', 'BUNCHING', 'DLQ_SEVERE', 'FEED_STALE', 'TICKETING_ANOMALY', 'INFRA')),
  severity        SMALLINT    NOT NULL CHECK (severity BETWEEN 0 AND 2),
  audience        TEXT        NOT NULL CHECK (audience IN ('PUBLIC', 'OPERATIONS', 'ENGINEERING')),
  route_id        TEXT        NULL,
  ref_table       TEXT        NULL,       -- e.g. insight.insight_service_disruption
  ref_id          TEXT        NULL,
  title           TEXT        NOT NULL CHECK (length(title) <= 200),
  body            JSONB       NOT NULL DEFAULT '{}'::jsonb,
  dedup_key       TEXT        NOT NULL CONSTRAINT alert_event_dedup_uk UNIQUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  acknowledged_by TEXT        NULL,
  acknowledged_at TIMESTAMPTZ NULL,
  resolved_at     TIMESTAMPTZ NULL,
  CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
  CHECK ((ref_table IS NULL) = (ref_id IS NULL))
);
CREATE INDEX alert_event_feed_idx  ON ops.alert_event (audience, created_at DESC);
CREATE INDEX alert_event_route_idx ON ops.alert_event (route_id, created_at DESC) WHERE route_id IS NOT NULL;
CREATE INDEX alert_event_open_idx  ON ops.alert_event (created_at DESC) WHERE resolved_at IS NULL;
-- Feed across several audiences with a keyset cursor (DOC-32 E-20).
CREATE INDEX alert_event_created_idx ON ops.alert_event (created_at DESC, id DESC);

-- Unified run list for the ops console (DR-63). Spring Batch writes TIMESTAMP without time zone
-- in the JVM zone; every JVM runs with TZ=UTC (DOC-29), so the view reads those values as UTC.
-- Streaming micro-batches are aggregated per listener and minute to keep the list readable.
CREATE VIEW ops.ops_job_run_v AS
  SELECT 'job:' || je.job_execution_id                 AS run_id,
         'BATCH_JOB'                                     AS kind,
         ji.job_name                                     AS name,
         je.status                                       AS status,
         je.exit_code                                    AS exit_code,
         left(je.exit_message, 500)                      AS exit_message,
         je.start_time AT TIME ZONE 'UTC'                AS started_at,
         je.end_time   AT TIME ZONE 'UTC'                AS ended_at,
         coalesce(s.read_count, 0)                       AS read_count,
         coalesce(s.write_count, 0)                      AS write_count,
         coalesce(s.skip_count, 0)                       AS skip_count,
         NULL::bigint                                    AS duplicate_count,
         je.job_execution_id                             AS job_execution_id,
         s.batch_ids                                     AS batch_ids
  FROM batch.batch_job_execution je
  JOIN batch.batch_job_instance ji ON ji.job_instance_id = je.job_instance_id
  LEFT JOIN LATERAL (
    SELECT sum(se.read_count)                                                   AS read_count,
           sum(se.write_count)                                                  AS write_count,
           sum(se.read_skip_count + se.process_skip_count + se.write_skip_count) AS skip_count,
           array_agg(bs.batch_id ORDER BY se.step_execution_id)
             FILTER (WHERE bs.batch_id IS NOT NULL)                             AS batch_ids
    FROM batch.batch_step_execution se
    LEFT JOIN ops.etl_batch_step bs ON bs.step_execution_id = se.step_execution_id
    WHERE se.job_execution_id = je.job_execution_id
  ) s ON true
  UNION ALL
  SELECT 'stream:' || sb.listener_id || ':'
           || to_char(date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z') AT TIME ZONE 'UTC',
                      'YYYY-MM-DD"T"HH24:MI"Z"'),
         'STREAM',
         sb.listener_id,
         CASE WHEN bool_or(sb.status = 'FAILED')              THEN 'FAILED'
              WHEN bool_or(sb.status = 'COMPLETED_WITH_SKIPS') THEN 'COMPLETED_WITH_SKIPS'
              ELSE 'COMPLETED' END,
         NULL,
         NULL,
         date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z'),
         max(sb.finished_at),
         sum(sb.records_read),
         sum(sb.records_written),
         sum(sb.records_skipped),
         sum(sb.records_duplicate),
         NULL,
         array_agg(sb.batch_id ORDER BY sb.started_at)
  FROM ops.etl_stream_batch sb
  GROUP BY sb.listener_id, date_bin('1 minute', sb.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z');

-- Step executions of a batch job for the run detail screen (DOC-32 E-32, E-37).
-- short_context is truncated: the full context can be large and is only needed for diagnosis.
CREATE VIEW ops.ops_job_step_v AS
  SELECT se.job_execution_id,
         se.step_execution_id,
         se.step_name,
         se.status,
         se.exit_code,
         left(se.exit_message, 2000)          AS exit_message,
         se.start_time AT TIME ZONE 'UTC'     AS started_at,
         se.end_time   AT TIME ZONE 'UTC'     AS ended_at,
         se.read_count,
         se.write_count,
         se.filter_count,
         se.commit_count,
         se.rollback_count,
         se.read_skip_count,
         se.process_skip_count,
         se.write_skip_count,
         bs.batch_id,
         left(sc.short_context, 2500)         AS short_context
  FROM batch.batch_step_execution se
  LEFT JOIN ops.etl_batch_step bs ON bs.step_execution_id = se.step_execution_id
  LEFT JOIN batch.batch_step_execution_context sc ON sc.step_execution_id = se.step_execution_id;

-- Job parameters of an execution (DOC-32 E-32).
CREATE VIEW ops.ops_job_execution_param_v AS
  SELECT job_execution_id,
         parameter_name  AS name,
         parameter_type  AS type,
         parameter_value AS value,
         identifying = 'Y' AS identifying
  FROM batch.batch_job_execution_params;
