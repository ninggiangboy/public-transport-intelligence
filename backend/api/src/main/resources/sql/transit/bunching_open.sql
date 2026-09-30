-- E-05 GET /vehicles/live, viewers only: the open bunching episodes (partial index insight_bus_bunching_open_idx).
SELECT id, vehicle_leader, vehicle_follower, last_gap_seconds, scheduled_headway_seconds
FROM insight.insight_bus_bunching
WHERE status = 'OPEN'
