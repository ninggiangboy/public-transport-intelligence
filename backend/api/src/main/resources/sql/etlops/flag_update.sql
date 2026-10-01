-- E-57: set a runtime flag. The flag must exist (a new flag comes from a migration); the services read it again
-- within five seconds (DOC-19 §2.1).
UPDATE ops.runtime_flag
SET value = CAST(:value AS jsonb), updated_by = :updatedBy, updated_at = now()
WHERE key = :key
RETURNING key, CAST(value AS text) AS value, description, updated_by, updated_at
