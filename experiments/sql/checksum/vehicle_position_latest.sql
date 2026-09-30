-- dw.vehicle_position_latest (DR-58, DOC-45 §4.5). Excludes batch_id, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound event_timestamp; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', vehicle_id::text, service_date::text, route_id::text, trip_id::text, direction_id::text, lat::text,
                                        lon::text, coalesce(bearing::text, '∅'), coalesce(speed_mps::text, '∅'),
                                        current_stop_sequence::text, stop_id::text, current_status::text,
                                        coalesce(occupancy_status::text, '∅'), event_timestamp::text),
                               E'\n' ORDER BY vehicle_id), '')) AS checksum
FROM dw.vehicle_position_latest
WHERE event_timestamp >= :from AND event_timestamp < :to;
