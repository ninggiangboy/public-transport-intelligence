-- name: alert_get
-- One alert, read on the primary when an acknowledgement found the alert already acknowledged, or none (DOC-32 E-21).
SELECT a.id, a.type, a.severity, a.audience, a.route_id, a.ref_table, a.ref_id, a.title, a.body::text AS body,
       a.created_at, a.acknowledged_by, a.acknowledged_at, a.resolved_at
FROM ops.alert_event a
WHERE a.id = :id
