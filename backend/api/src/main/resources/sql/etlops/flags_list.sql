-- E-55 GET /etl/flags: every runtime flag (a handful, DOC-15 §3).
SELECT key, CAST(value AS text) AS value, description, updated_by, updated_at
FROM ops.runtime_flag
ORDER BY key
