-- E-38 GET /etl/feeds: the newest feed versions with the totals of their validation report (DOC-21 §4). A report
-- element is {"check", "count", "samples"}; the total of a list is the sum of its counts.
SELECT feed_version_id, feed_hash, status, publisher_name, publisher_feed_version, agency_timezone, valid_from, valid_to,
       loaded_at, activated_at, job_execution_id,
       coalesce((SELECT sum(CAST(e ->> 'count' AS int)) FROM jsonb_array_elements(validation_report -> 'errors') e), 0)
         AS validation_errors,
       coalesce((SELECT sum(CAST(w ->> 'count' AS int)) FROM jsonb_array_elements(validation_report -> 'warnings') w), 0)
         AS validation_warnings
FROM dw.gtfs_feed_version
ORDER BY loaded_at DESC, feed_version_id DESC
LIMIT :limit
