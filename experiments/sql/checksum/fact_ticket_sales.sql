-- dw.fact_ticket_sales (DR-58, DOC-45 §4.5). Excludes batch_id, ingested_at, updated_at. Every nullable column is coalesced to '∅'.
-- Parameters :from and :to bound created_at; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', sale_date::text, transaction_id::text, sale_point_id::text, coalesce(route_id::text, '∅'),
                                        coalesce(stop_id::text, '∅'), ticket_type::text, txn_type::text, amount::text, currency::text,
                                        coalesce(refund_of::text, '∅'), status::text, is_deleted::text, created_at::text,
                                        source_updated_at::text, source_lsn::text, event_timestamp::text, payload_hash::text),
                               E'\n' ORDER BY sale_date, transaction_id), '')) AS checksum
FROM dw.fact_ticket_sales
WHERE created_at >= :from AND created_at < :to;
