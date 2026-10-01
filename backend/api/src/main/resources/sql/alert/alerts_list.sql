-- name: alerts_list
-- The alert feed (DOC-32 E-20), newest first, for the audiences the caller may see. One audience uses
-- alert_event_feed_idx, several use alert_event_created_idx, a route uses alert_event_route_idx. :since, when given,
-- excludes the alerts created at that instant or before (the polling fallback of DOC-26 §8).
SELECT a.id, a.type, a.severity, a.audience, a.route_id, a.ref_table, a.ref_id, a.title, a.body::text AS body,
       a.created_at, a.acknowledged_by, a.acknowledged_at, a.resolved_at
FROM ops.alert_event a
WHERE a.created_at >= :from AND a.created_at < :to
  AND a.audience = ANY(:audiences::text[])
  AND (cardinality(:types::text[]) = 0 OR a.type = ANY(:types::text[]))
  AND (cardinality(:severities::int[]) = 0 OR a.severity = ANY(:severities::int[]))
  AND (cardinality(:routeIds::text[]) = 0 OR a.route_id = ANY(:routeIds::text[]))
  AND (:state::text = 'all' OR (a.resolved_at IS NULL AND (:state::text = 'open' OR a.acknowledged_at IS NULL)))
  AND (:since::timestamptz IS NULL OR a.created_at > :since::timestamptz)
  AND (:cursorTs::timestamptz IS NULL OR (a.created_at, a.id) < (:cursorTs::timestamptz, :cursorId::uuid))
ORDER BY a.created_at DESC, a.id DESC
LIMIT :limit
