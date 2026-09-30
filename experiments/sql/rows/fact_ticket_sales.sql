-- dw.fact_ticket_sales: business key (DOC-13 §6.2) and an md5 of the columns of checksum/fact_ticket_sales.sql, one row per key.
-- EXP-04 compares these row by row before and after a rebuild (DOC-45 §1.3); run with TimeZone = UTC.
SELECT sale_date::text || '|' || transaction_id::text AS bkey,
       md5(concat_ws('|', sale_date::text, transaction_id::text, sale_point_id::text, coalesce(route_id::text, '∅'),
                         coalesce(stop_id::text, '∅'), ticket_type::text, txn_type::text, amount::text, currency::text,
                         coalesce(refund_of::text, '∅'), status::text, is_deleted::text, created_at::text,
                         source_updated_at::text, source_lsn::text, event_timestamp::text, payload_hash::text)) AS fp,
       sale_date, transaction_id
FROM dw.fact_ticket_sales
