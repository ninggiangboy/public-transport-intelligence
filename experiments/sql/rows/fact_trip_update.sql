-- dw.fact_trip_update: business key (DOC-13 §6.2) and an md5 of the columns of checksum/fact_trip_update.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT service_date::text || '|' || trip_id || '|' || stop_sequence AS bkey,
       md5(concat_ws('|', service_date::text, trip_id::text, stop_sequence::text, route_id::text, direction_id::text,
                         stop_id::text, vehicle_id::text, schedule_relationship::text,
                         coalesce(scheduled_arrival::text, '∅'), coalesce(arrival_time::text, '∅'),
                         coalesce(departure_time::text, '∅'), coalesce(delay_seconds::text, '∅'), is_observed::text,
                         event_timestamp::text, payload_hash::text)) AS fp,
       service_date, trip_id, stop_sequence
FROM dw.fact_trip_update
