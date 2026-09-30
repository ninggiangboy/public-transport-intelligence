-- dw.vehicle_position_latest: business key (DOC-13 §6.2) and an md5 of the columns of checksum/vehicle_position_latest.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT vehicle_id AS bkey,
       md5(concat_ws('|', vehicle_id::text, service_date::text, route_id::text, trip_id::text, direction_id::text, lat::text,
                         lon::text, coalesce(bearing::text, '∅'), coalesce(speed_mps::text, '∅'),
                         current_stop_sequence::text, stop_id::text, current_status::text,
                         coalesce(occupancy_status::text, '∅'), event_timestamp::text)) AS fp,
       vehicle_id
FROM dw.vehicle_position_latest
