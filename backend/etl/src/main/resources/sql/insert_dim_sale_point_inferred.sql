-- name: insert_dim_sale_point_inferred (DOC-14 §8.5). Placeholder for a sale that arrives before its sale point.
INSERT INTO dw.dim_sale_point (sale_point_id, source, batch_id)
VALUES (:sale_point_id, 'INFERRED', :batch_id)
ON CONFLICT (sale_point_id) DO NOTHING
