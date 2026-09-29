-- name: upsert_fact_vehicle_position (DOC-14 §8.1). 1 = written, 0 = blocked by the guard (duplicate).
INSERT INTO dw.fact_vehicle_position AS t (
  service_date, vehicle_id, event_timestamp, trip_id, route_id, direction_id, lat, lon, bearing, speed_mps,
  current_stop_sequence, stop_id, current_status, occupancy_status, schema_version, payload_hash, batch_id)
VALUES (
  :service_date, :vehicle_id, :event_timestamp, :trip_id, :route_id, :direction_id, :lat, :lon, :bearing, :speed_mps,
  :current_stop_sequence, :stop_id, :current_status, :occupancy_status, :schema_version, :payload_hash, :batch_id)
ON CONFLICT ON CONSTRAINT fact_vehicle_position_pk DO UPDATE SET
  trip_id = excluded.trip_id, route_id = excluded.route_id, direction_id = excluded.direction_id,
  lat = excluded.lat, lon = excluded.lon, bearing = excluded.bearing, speed_mps = excluded.speed_mps,
  current_stop_sequence = excluded.current_stop_sequence, stop_id = excluded.stop_id,
  current_status = excluded.current_status, occupancy_status = excluded.occupancy_status,
  schema_version = excluded.schema_version, payload_hash = excluded.payload_hash,
  batch_id = excluded.batch_id, updated_at = now()
WHERE t.payload_hash <> excluded.payload_hash   -- same key, different content: a corrected replay
   OR :replay                                   -- replay rewrites even identical content (new logic)
