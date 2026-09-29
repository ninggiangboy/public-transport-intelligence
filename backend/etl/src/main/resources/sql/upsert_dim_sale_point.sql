-- name: upsert_dim_sale_point (DOC-14 §8.5). A CDC row always replaces an INFERRED placeholder.
INSERT INTO dw.dim_sale_point AS t (
  sale_point_id, name, kind, stop_id, route_id, source, is_deleted, source_lsn, batch_id)
VALUES (
  :sale_point_id, :name, :kind, :stop_id, :route_id, 'CDC', :is_deleted, :source_lsn, :batch_id)
ON CONFLICT (sale_point_id) DO UPDATE SET
  name = excluded.name, kind = excluded.kind, stop_id = excluded.stop_id, route_id = excluded.route_id,
  source = 'CDC', is_deleted = excluded.is_deleted, source_lsn = excluded.source_lsn,
  batch_id = excluded.batch_id, updated_at = now()
WHERE t.source_lsn IS NULL                                          -- INFERRED placeholder
   OR excluded.source_lsn > t.source_lsn
   OR (excluded.source_lsn = t.source_lsn AND :replay)
