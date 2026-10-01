-- E-50, E-53: the raw zone replay of a source that is waiting or running (replay_request_one_raw_per_source).
SELECT id
FROM ops.replay_request
WHERE kind = 'RAW_RANGE' AND CAST(source AS text) = :source AND status IN ('PENDING', 'RUNNING')
LIMIT 1
