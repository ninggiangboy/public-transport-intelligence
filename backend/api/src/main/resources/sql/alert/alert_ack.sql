-- name: alert_ack
-- Acknowledges an alert, once (DOC-32 E-21): the first operator stays. No row back means the alert is unknown or was
-- acknowledged already. The time is the database clock, like created_at. replay_operator may update only
-- acknowledged_by, acknowledged_at and resolved_at of the table (DOC-17).
UPDATE ops.alert_event
SET acknowledged_by = :actor, acknowledged_at = now()
WHERE id = :id AND acknowledged_at IS NULL
RETURNING id, type, severity, audience, route_id, ref_table, ref_id, title, body::text AS body,
          created_at, acknowledged_by, acknowledged_at, resolved_at
