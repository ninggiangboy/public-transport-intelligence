-- E-37: the data quality results of a batch (dq_check_result_batch_idx).
SELECT rule_id, table_name, violation_count, checked_at
FROM ops.dq_check_result
WHERE batch_id = CAST(:batchId AS uuid)
ORDER BY checked_at, id
