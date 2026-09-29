-- name: gtfs_activate (DOC-14 §6.2)
-- GtfsStaticLoadJob, step "activate": one transaction. Retire first, then activate, because the
-- partial unique index gtfs_feed_version_one_active allows a single ACTIVE row at any instant.
UPDATE dw.gtfs_feed_version SET status = 'RETIRED', retired_at = now()
WHERE status = 'ACTIVE' AND feed_version_id <> :feedVersionId;

UPDATE dw.gtfs_feed_version SET status = 'ACTIVE', activated_at = now(), retired_at = NULL
WHERE feed_version_id = :feedVersionId
  AND (status = 'STAGED' OR (:allowRetired AND status = 'RETIRED'));  -- 0 rows -> the job fails the step
