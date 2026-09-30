-- dw.dim_sale_point (DR-58, DOC-45 §4.5). Excludes batch_id, created_at, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound nothing: the whole table; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', sale_point_id::text, coalesce(name::text, '∅'), coalesce(kind::text, '∅'),
                                        coalesce(stop_id::text, '∅'), coalesce(route_id::text, '∅'), source::text, is_deleted::text,
                                        coalesce(source_lsn::text, '∅')),
                               E'\n' ORDER BY sale_point_id), '')) AS checksum
FROM dw.dim_sale_point;
