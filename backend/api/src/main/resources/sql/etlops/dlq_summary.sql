-- E-41 GET /etl/dlq/summary: the open dead letters by status, source and severity. Open is the definition of the gauge
-- pti_dlq_open_records: not REPLAYED, DISCARDED or RESOLVED (dead_letter_list_idx).
SELECT status, source, severity, count(*) AS n
FROM ops.dead_letter
WHERE status NOT IN ('REPLAYED', 'DISCARDED', 'RESOLVED')
GROUP BY status, source, severity
