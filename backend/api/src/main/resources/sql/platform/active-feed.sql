-- name: active_feed
-- The ACTIVE GTFS feed version (DOC-31 §10.2). The partial unique index gtfs_feed_version_one_active guarantees at
-- most one row.
SELECT feed_version_id,
       publisher_feed_version,
       agency_timezone,
       valid_from,
       valid_to,
       activated_at
FROM dw.gtfs_feed_version
WHERE status = 'ACTIVE'
