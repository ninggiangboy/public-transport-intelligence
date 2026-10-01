-- name: bunching_list
-- Bunching episodes that intersect [:from, :to) (DOC-32 E-10), newest first, with the short form of the dispatch
-- suggestion. Episodes last at most 3 hours, so the lower bound on episode_start lets insight_bus_bunching_start_idx
-- (or _route_idx when routes are given) limit the scan. The join uses insight_dispatch_suggestion_bunching_uk.
SELECT b.id, b.route_id, b.direction_id, b.vehicle_leader, b.vehicle_follower, b.trip_leader, b.trip_follower,
       b.episode_start, b.episode_end, b.status, b.close_reason, b.scheduled_headway_seconds, b.threshold_seconds,
       b.min_gap_seconds, b.last_gap_seconds, b.open_stop_id, b.evaluation_count, b.last_evaluated_at,
       b.enrichment_status, b.batch_id,
       s.id AS suggestion_id, s.action, s.action_confidence
FROM insight.insight_bus_bunching b
LEFT JOIN insight.insight_dispatch_suggestion s ON s.bunching_id = b.id
WHERE b.episode_start >= :from - interval '3 hours' AND b.episode_start < :to
  AND coalesce(b.episode_end, 'infinity') >= :from
  AND (cardinality(:routeIds::text[]) = 0 OR b.route_id = ANY(:routeIds))
  AND (:status::text IS NULL OR b.status = :status)
  AND (:cursorTs::timestamptz IS NULL OR (b.episode_start, b.id) < (:cursorTs::timestamptz, :cursorId::uuid))
ORDER BY b.episode_start DESC, b.id DESC
LIMIT :limit
