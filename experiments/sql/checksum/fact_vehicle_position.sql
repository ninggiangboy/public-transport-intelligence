-- dw.fact_vehicle_position (DR-58, DOC-45 §4.5). Excludes batch_id, ingested_at, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound event_timestamp; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', service_date::text, vehicle_id::text, event_timestamp::text, trip_id::text, route_id::text,
                                        direction_id::text, lat::text, lon::text, coalesce(bearing::text, '∅'),
                                        coalesce(speed_mps::text, '∅'), current_stop_sequence::text, stop_id::text, current_status::text,
                                        coalesce(occupancy_status::text, '∅'), schema_version::text, payload_hash::text),
                               E'\n' ORDER BY vehicle_id, event_timestamp), '')) AS checksum
FROM dw.fact_vehicle_position
WHERE event_timestamp >= :from AND event_timestamp < :to;
