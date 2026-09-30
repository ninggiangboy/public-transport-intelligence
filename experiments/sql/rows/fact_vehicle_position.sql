-- dw.fact_vehicle_position: business key (DOC-13 §6.2) and an md5 of the columns of checksum/fact_vehicle_position.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT vehicle_id || '|' || to_char(event_timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS bkey,
       md5(concat_ws('|', service_date::text, vehicle_id::text, event_timestamp::text, trip_id::text, route_id::text,
                         direction_id::text, lat::text, lon::text, coalesce(bearing::text, '∅'),
                         coalesce(speed_mps::text, '∅'), current_stop_sequence::text, stop_id::text, current_status::text,
                         coalesce(occupancy_status::text, '∅'), schema_version::text, payload_hash::text)) AS fp,
       vehicle_id, event_timestamp
FROM dw.fact_vehicle_position
