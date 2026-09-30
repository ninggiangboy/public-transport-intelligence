-- E-07 GET /stops/{stopId}: the open disruptions on some routes that the caller may see. Not cached.
SELECT id, ref_id, route_id, (body->>'directionId')::smallint AS direction_id, severity, title,
       (body->>'episodeStart')::timestamptz AS started_at
FROM ops.alert_event
WHERE resolved_at IS NULL AND type = 'DISRUPTION'                 -- alert_event_open_idx
  AND route_id = ANY(CAST(:routeIds AS text[])) AND audience = ANY(CAST(:audiences AS text[]))
ORDER BY severity DESC, created_at DESC
LIMIT 20
