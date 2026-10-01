-- E-37: the dead letters a batch made, by current status (dead_letter_batch_idx).
SELECT status, count(*) AS n
FROM ops.dead_letter
WHERE batch_id = CAST(:batchId AS uuid)
GROUP BY status
