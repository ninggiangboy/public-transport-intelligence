-- name: upsert_fact_trip_update (DOC-14 §8.2). An observed stop is never overwritten by a prediction (DR-13).
INSERT INTO dw.fact_trip_update AS t (
  service_date, trip_id, stop_sequence, route_id, direction_id, stop_id, vehicle_id, schedule_relationship,
  scheduled_arrival, arrival_time, departure_time, delay_seconds, is_observed, event_timestamp, payload_hash, batch_id)
VALUES (
  :service_date, :trip_id, :stop_sequence, :route_id, :direction_id, :stop_id, :vehicle_id, :schedule_relationship,
  :scheduled_arrival, :arrival_time, :departure_time, :delay_seconds, :is_observed, :event_timestamp, :payload_hash, :batch_id)
ON CONFLICT ON CONSTRAINT fact_trip_update_pk DO UPDATE SET
  vehicle_id            = excluded.vehicle_id,
  schedule_relationship = CASE WHEN t.is_observed AND NOT excluded.is_observed THEN t.schedule_relationship ELSE excluded.schedule_relationship END,
  scheduled_arrival     = coalesce(excluded.scheduled_arrival, t.scheduled_arrival),
  arrival_time          = CASE WHEN t.is_observed AND NOT excluded.is_observed THEN t.arrival_time   ELSE excluded.arrival_time   END,
  departure_time        = CASE WHEN t.is_observed AND NOT excluded.is_observed THEN t.departure_time ELSE excluded.departure_time END,
  delay_seconds         = CASE WHEN t.is_observed AND NOT excluded.is_observed THEN t.delay_seconds  ELSE excluded.delay_seconds  END,
  is_observed           = t.is_observed OR excluded.is_observed,   -- never true -> false (DR-13)
  event_timestamp       = excluded.event_timestamp,
  payload_hash          = excluded.payload_hash,
  batch_id              = excluded.batch_id,
  updated_at            = now()
WHERE excluded.event_timestamp > t.event_timestamp                  -- event-time guard (FR-03.2)
   OR (excluded.event_timestamp = t.event_timestamp
       AND (excluded.payload_hash <> t.payload_hash OR :replay))
