-- E-56, E-57: one runtime flag.
SELECT key, CAST(value AS text) AS value, description, updated_by, updated_at
FROM ops.runtime_flag
WHERE key = :key
