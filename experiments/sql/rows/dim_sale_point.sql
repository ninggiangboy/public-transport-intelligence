-- dw.dim_sale_point: business key (DOC-13 §6.2) and an md5 of the columns of checksum/dim_sale_point.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT sale_point_id AS bkey,
       md5(concat_ws('|', sale_point_id::text, coalesce(name::text, '∅'), coalesce(kind::text, '∅'),
                         coalesce(stop_id::text, '∅'), coalesce(route_id::text, '∅'), source::text, is_deleted::text,
                         coalesce(source_lsn::text, '∅'))) AS fp,
       sale_point_id
FROM dw.dim_sale_point
