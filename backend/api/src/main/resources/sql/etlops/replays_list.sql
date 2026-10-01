-- E-51 GET /etl/replays: replays of a window, newest first, keyset (requested_at, id). The table is small (a few
-- thousand rows a month, DOC-18), so it is scanned by requested_at without an index of its own.
SELECT id, kind, source, from_ts, to_ts, dead_letter_id, recompute_analytics, requested_by, idempotency_key,
       requested_at, status, job_execution_id, started_at, finished_at, CAST(stats AS text) AS stats, message
FROM ops.replay_request
WHERE requested_at >= CAST(:from AS timestamptz) AND requested_at < CAST(:to AS timestamptz)
  AND (CAST(:kind AS text) IS NULL OR kind = CAST(:kind AS text))
  AND (cardinality(CAST(:statuses AS text[])) = 0 OR status = ANY(CAST(:statuses AS text[])))
  AND (CAST(:source AS text) IS NULL OR CAST(source AS text) = CAST(:source AS text))
  AND (CAST(:requestedBy AS text) IS NULL OR requested_by = CAST(:requestedBy AS text))
  AND (CAST(:cursorTs AS timestamptz) IS NULL
       OR (requested_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
ORDER BY requested_at DESC, id DESC
LIMIT :limit
