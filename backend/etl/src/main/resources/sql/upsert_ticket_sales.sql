-- name: upsert_fact_ticket_sales (DOC-14 §8.3). The source LSN orders changes of one transaction.
INSERT INTO dw.fact_ticket_sales AS t (
  sale_date, transaction_id, sale_point_id, route_id, stop_id, ticket_type, txn_type, amount, currency, refund_of,
  status, is_deleted, created_at, source_updated_at, source_lsn, event_timestamp, payload_hash, batch_id)
VALUES (
  :sale_date, :transaction_id, :sale_point_id, :route_id, :stop_id, :ticket_type, :txn_type, :amount, :currency, :refund_of,
  :status, :is_deleted, :created_at, :source_updated_at, :source_lsn, :event_timestamp, :payload_hash, :batch_id)
ON CONFLICT ON CONSTRAINT fact_ticket_sales_pk DO UPDATE SET
  sale_point_id = excluded.sale_point_id, route_id = excluded.route_id, stop_id = excluded.stop_id,
  ticket_type = excluded.ticket_type, txn_type = excluded.txn_type, amount = excluded.amount,
  currency = excluded.currency, refund_of = excluded.refund_of, status = excluded.status,
  is_deleted = excluded.is_deleted, source_updated_at = excluded.source_updated_at,
  source_lsn = excluded.source_lsn, event_timestamp = excluded.event_timestamp,
  payload_hash = excluded.payload_hash, batch_id = excluded.batch_id, updated_at = now()
WHERE excluded.source_lsn > t.source_lsn                            -- LSN guard (FR-03.2)
   OR (excluded.source_lsn = t.source_lsn AND :replay)
