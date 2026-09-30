-- E-06 GET /stops?q=: ranked text search. The stop code wins, then a name that starts with q, then a name that
-- contains it. :qPrefix and :qContains are q with % _ and \ escaped, plus the wildcards.
SELECT stop_id, stop_code, stop_name, lat, lon, location_type, wheelchair_boarding,
       CASE WHEN stop_code = :q THEN 0
            WHEN stop_name ILIKE :qPrefix ESCAPE '\' THEN 1
            ELSE 2 END AS rank
FROM dw.dim_stop
WHERE feed_version_id = :fv AND location_type IN (0, 1)
  AND (stop_code = :q OR stop_name ILIKE :qContains ESCAPE '\')
ORDER BY rank, stop_name, stop_id
LIMIT :limit
