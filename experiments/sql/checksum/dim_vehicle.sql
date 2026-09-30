-- dw.dim_vehicle (DR-58, DOC-45 §4.5). Excludes first_seen_at, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound nothing: the whole table; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', vehicle_id::text, coalesce(vehicle_label::text, '∅'), coalesce(vehicle_model::text, '∅'),
                                        coalesce(seated_capacity::text, '∅'), coalesce(standing_capacity::text, '∅'),
                                        coalesce(capacity::text, '∅'), coalesce(low_floor::text, '∅'),
                                        coalesce(wheelchair_access::text, '∅'), coalesce(fuel::text, '∅'), source::text),
                               E'\n' ORDER BY vehicle_id), '')) AS checksum
FROM dw.dim_vehicle;
