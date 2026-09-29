-- pti_warehouse grants (DOC-17). Repeatable: Flyway re-runs it after the versioned migrations
-- whenever this file changes. It first revokes everything, so it is the complete truth.
-- Blocks marked [P4] are added together with V7__insight.sql.

DO $$
BEGIN
  -- Also done by the compose bootstrap; repeated here because CNPG (k3d) creates the database itself.
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO etl_writer, triage_writer, api_reader, replay_operator, experiment_runner',
                 current_database());
END $$;

REVOKE ALL ON ALL TABLES    IN SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;
REVOKE ALL ON SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;

-- ---------------------------------------------------------------- etl_writer (etl-stream, etl-batch)
GRANT USAGE ON SCHEMA dw, ops, insight, batch, exp TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dw TO etl_writer;
REVOKE INSERT, UPDATE, DELETE ON dw.dim_date FROM etl_writer;
GRANT EXECUTE ON FUNCTION dw.ensure_partitions(TEXT, DATE, DATE), dw.drop_partitions_before(TEXT, DATE) TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO etl_writer;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ops.etl_stream_batch, ops.etl_batch_step, ops.etl_checkpoint,
                                        ops.shedlock, ops.dedup_registry, ops.dq_check_result TO etl_writer;
GRANT SELECT, INSERT, DELETE ON ops.dead_letter TO etl_writer;                    -- DELETE: retention job
GRANT UPDATE (status, stage, error_class, error_message, rule_id, batch_id, replay_count, last_replay_at,
              triage_lease_until, resolved_by, resolved_at, updated_at)
  ON ops.dead_letter TO etl_writer;                                               -- replay upsert, DlqResolveWriter (DOC-22)
GRANT SELECT, INSERT, DELETE ON ops.dlq_action_log TO etl_writer;
GRANT SELECT, DELETE ON ops.replay_request, ops.job_request TO etl_writer;
GRANT UPDATE (status, job_execution_id, started_at, finished_at, stats, message) ON ops.replay_request TO etl_writer;
GRANT UPDATE (status, job_execution_id, started_at, finished_at, message)        ON ops.job_request    TO etl_writer;
GRANT SELECT, INSERT ON ops.alert_event TO etl_writer;
GRANT UPDATE (title, body, severity, resolved_at) ON ops.alert_event TO etl_writer;
GRANT SELECT ON ops.runtime_flag TO etl_writer;

GRANT INSERT, SELECT, TRUNCATE ON ALL TABLES IN SCHEMA exp TO etl_writer;         -- profile experiment only

-- ---------------------------------------------------------------- triage_writer (triage-worker)
GRANT USAGE ON SCHEMA dw, ops, insight TO triage_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO triage_writer;                         -- context building
GRANT SELECT ON ops.dead_letter, ops.runtime_flag, ops.dq_check_result, ops.etl_stream_batch TO triage_writer;
GRANT UPDATE (status, category, category_confidence, severity, severity_confidence, model_version,
              triaged_at, triage_attempts, triage_lease_until, auto_replay_count, updated_at)
  ON ops.dead_letter TO triage_writer;
GRANT SELECT, INSERT ON ops.dlq_action_log, ops.replay_request TO triage_writer;
GRANT SELECT ON ops.alert_event TO triage_writer;                                 -- never inserts alerts (DOC-24 §10)
GRANT UPDATE (audience, title, body, severity) ON ops.alert_event TO triage_writer;  -- severity: ticketing (DOC-24 §7)
GRANT SELECT, INSERT, UPDATE, DELETE ON ops.shedlock TO triage_writer;

-- ---------------------------------------------------------------- api_reader (api, read replica)
GRANT USAGE ON SCHEMA dw, ops, insight TO api_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO api_reader;
GRANT SELECT ON ops.dead_letter, ops.dlq_action_log, ops.replay_request, ops.job_request,
                ops.runtime_flag, ops.dq_check_result, ops.alert_event, ops.etl_stream_batch,
                ops.ops_job_run_v, ops.ops_job_step_v, ops.ops_job_execution_param_v
  TO api_reader;                                                                  -- never batch.* directly

-- ---------------------------------------------------------------- replay_operator (api, primary, DR-20)
GRANT USAGE ON SCHEMA ops, insight TO replay_operator;
GRANT SELECT ON ops.dead_letter TO replay_operator;
GRANT UPDATE (status, edited_payload, resolved_by, resolved_at, updated_at) ON ops.dead_letter TO replay_operator;
GRANT SELECT, INSERT ON ops.replay_request, ops.job_request, ops.dlq_action_log TO replay_operator;
GRANT SELECT, INSERT ON ops.alert_event TO replay_operator;                       -- Alertmanager webhook
GRANT UPDATE (acknowledged_by, acknowledged_at, resolved_at) ON ops.alert_event TO replay_operator;
GRANT SELECT, INSERT ON ops.runtime_flag TO replay_operator;
GRANT UPDATE (value, updated_by, updated_at) ON ops.runtime_flag TO replay_operator;

-- ---------------------------------------------------------------- experiment_runner
GRANT USAGE ON SCHEMA dw, ops, insight, exp TO experiment_runner;
GRANT SELECT ON ALL TABLES IN SCHEMA dw, ops, insight, exp TO experiment_runner;
GRANT TRUNCATE ON ALL TABLES IN SCHEMA exp TO experiment_runner;
REVOKE SELECT ON ops.dedup_registry FROM experiment_runner;
