-- name: upsert_vehicle_position_latest (DOC-14 §8.4). No :replay: replaying old data never moves a bus back.
INSERT INTO dw.vehicle_position_latest AS t (
  vehicle_id, service_date, route_id, trip_id, direction_id, lat, lon, bearing, speed_mps,
  current_stop_sequence, stop_id, current_status, occupancy_status, event_timestamp, batch_id)
VALUES (
  :vehicle_id, :service_date, :route_id, :trip_id, :direction_id, :lat, :lon, :bearing, :speed_mps,
  :current_stop_sequence, :stop_id, :current_status, :occupancy_status, :event_timestamp, :batch_id)
ON CONFLICT (vehicle_id) DO UPDATE SET
  service_date = excluded.service_date, route_id = excluded.route_id, trip_id = excluded.trip_id,
  direction_id = excluded.direction_id, lat = excluded.lat, lon = excluded.lon, bearing = excluded.bearing,
  speed_mps = excluded.speed_mps, current_stop_sequence = excluded.current_stop_sequence,
  stop_id = excluded.stop_id, current_status = excluded.current_status,
  occupancy_status = excluded.occupancy_status, event_timestamp = excluded.event_timestamp,
  batch_id = excluded.batch_id, updated_at = now()
WHERE excluded.event_timestamp > t.event_timestamp
