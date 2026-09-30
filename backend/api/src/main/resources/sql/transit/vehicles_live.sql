-- E-05 GET /vehicles/live: the newest position of every vehicle that reported recently, with the prediction at the
-- stop it is heading for. One row per vehicle. The trip update is a primary key lookup per vehicle, written as a
-- LATERAL subquery with LIMIT 1 (which also keeps the planner from flattening it into a hash join that reads every
-- partition of the fact table).
SELECT v.vehicle_id, dv.vehicle_label, v.route_id, v.trip_id, v.direction_id, t.trip_headsign,
       v.lat, v.lon, v.bearing, v.speed_mps, v.current_status, v.stop_id, v.current_stop_sequence,
       v.occupancy_status, v.event_timestamp,
       tu.delay_seconds, coalesce(tu.arrival_time, tu.departure_time) AS stop_arrival_at
FROM dw.vehicle_position_latest v
LEFT JOIN dw.dim_vehicle dv ON dv.vehicle_id = v.vehicle_id
LEFT JOIN dw.gtfs_trip t    ON t.feed_version_id = :fv AND t.trip_id = v.trip_id
LEFT JOIN LATERAL (SELECT f.delay_seconds, f.arrival_time, f.departure_time
                   FROM dw.fact_trip_update f
                   WHERE f.service_date = v.service_date AND f.trip_id = v.trip_id
                     AND f.stop_sequence = v.current_stop_sequence
                   LIMIT 1) tu ON true
WHERE v.event_timestamp >= CAST(:now AS timestamptz) - make_interval(secs => :maxAgeSeconds)
  AND (cardinality(CAST(:routeIds AS text[])) = 0 OR v.route_id = ANY(CAST(:routeIds AS text[])))
ORDER BY v.event_timestamp DESC, v.vehicle_id
LIMIT :limit
