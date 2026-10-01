-- name: alert_resolve
-- Resolves the alert of a resolved Alertmanager notice (DOC-32 E-80). No row back: there is no such alert, or it was
-- resolved already. The time is the database clock, like created_at.
UPDATE ops.alert_event
SET resolved_at = now()
WHERE dedup_key = :dedupKey AND resolved_at IS NULL
RETURNING id, type, severity, audience, route_id, ref_table, ref_id, title, body::text AS body,
          created_at, acknowledged_by, acknowledged_at, resolved_at
