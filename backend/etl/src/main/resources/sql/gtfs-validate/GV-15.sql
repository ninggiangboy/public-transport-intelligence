-- name: GV-15 (DOC-21 §4), warning. The number of routes, stops or trips differs by more than 50% from the ACTIVE
-- version (:activeFeedVersionId is -1 when there is none).
WITH n AS (
  SELECT 'routes' AS what,
         (SELECT count(*) FROM dw.dim_route WHERE feed_version_id = :feedVersionId) AS staged,
         (SELECT count(*) FROM dw.dim_route WHERE feed_version_id = :activeFeedVersionId) AS active
  UNION ALL
  SELECT 'stops',
         (SELECT count(*) FROM dw.dim_stop WHERE feed_version_id = :feedVersionId),
         (SELECT count(*) FROM dw.dim_stop WHERE feed_version_id = :activeFeedVersionId)
  UNION ALL
  SELECT 'trips',
         (SELECT count(*) FROM dw.gtfs_trip WHERE feed_version_id = :feedVersionId),
         (SELECT count(*) FROM dw.gtfs_trip WHERE feed_version_id = :activeFeedVersionId)
)
SELECT count(*) OVER () AS total,
       jsonb_build_object('table', what, 'staged', staged, 'active', active,
                          'message', 'Differs by more than 50% from the ACTIVE version')::text AS sample
FROM n
WHERE :activeFeedVersionId <> -1 AND :activeFeedVersionId <> :feedVersionId AND active > 0
  AND abs(staged - active) > 0.5 * active
ORDER BY what
LIMIT 20
