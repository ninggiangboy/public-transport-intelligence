-- name: alert_insert
-- Inserts the alert of a firing Alertmanager notice (DOC-32 E-80). The same notice again has the same dedup_key and
-- changes nothing: no row back. ref_table and ref_id stay empty, as there is no row they point to.
INSERT INTO ops.alert_event (id, type, severity, audience, route_id, title, body, dedup_key)
VALUES (:id, :type, :severity, :audience, :routeId, :title, :body::jsonb, :dedupKey)
ON CONFLICT ON CONSTRAINT alert_event_dedup_uk DO NOTHING
RETURNING id, type, severity, audience, route_id, ref_table, ref_id, title, body::text AS body,
          created_at, acknowledged_by, acknowledged_at, resolved_at
