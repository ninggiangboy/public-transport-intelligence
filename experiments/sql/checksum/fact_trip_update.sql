-- dw.fact_trip_update (DR-58, DOC-45 §4.5). Excludes batch_id, ingested_at, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound event_timestamp; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', service_date::text, trip_id::text, stop_sequence::text, route_id::text, direction_id::text,
                                        stop_id::text, vehicle_id::text, schedule_relationship::text,
                                        coalesce(scheduled_arrival::text, '∅'), coalesce(arrival_time::text, '∅'),
                                        coalesce(departure_time::text, '∅'), coalesce(delay_seconds::text, '∅'), is_observed::text,
                                        event_timestamp::text, payload_hash::text),
                               E'\n' ORDER BY service_date, trip_id, stop_sequence), '')) AS checksum
FROM dw.fact_trip_update
WHERE event_timestamp >= :from AND event_timestamp < :to;
