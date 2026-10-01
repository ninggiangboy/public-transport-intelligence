-- E-44, E-45, E-50: a PENDING replay request for etl-batch's ReplayRequestPoller (ADR-0013). Nothing is inserted, and no
-- row returned, when the actor already has a request with the same idempotency key. replay_request_one_raw_per_source
-- and replay_request_one_per_record still raise a unique violation, which the adapter turns into ActiveReplayConflict.
INSERT INTO ops.replay_request (id, kind, source, from_ts, to_ts, dead_letter_id, recompute_analytics, requested_by,
                                idempotency_key, requested_at)
VALUES (CAST(:id AS uuid), :kind, :source, CAST(:fromTs AS timestamptz), CAST(:toTs AS timestamptz),
        CAST(:deadLetterId AS uuid), :recomputeAnalytics, :requestedBy, :idempotencyKey,
        CAST(:requestedAt AS timestamptz))
ON CONFLICT (requested_by, idempotency_key) DO NOTHING
RETURNING id, kind, source, from_ts, to_ts, dead_letter_id, recompute_analytics, requested_by, idempotency_key,
          requested_at, status, job_execution_id, started_at, finished_at, CAST(stats AS text) AS stats, message
