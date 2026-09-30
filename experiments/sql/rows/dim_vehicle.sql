-- dw.dim_vehicle: business key (DOC-13 §6.2) and an md5 of the columns of checksum/dim_vehicle.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT vehicle_id AS bkey,
       md5(concat_ws('|', vehicle_id::text, coalesce(vehicle_label::text, '∅'), coalesce(vehicle_model::text, '∅'),
                         coalesce(seated_capacity::text, '∅'), coalesce(standing_capacity::text, '∅'),
                         coalesce(capacity::text, '∅'), coalesce(low_floor::text, '∅'),
                         coalesce(wheelchair_access::text, '∅'), coalesce(fuel::text, '∅'), source::text)) AS fp,
       vehicle_id
FROM dw.dim_vehicle
