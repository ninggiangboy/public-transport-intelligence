-- E-03 GET /routes/{routeId}/delays. {{bucket}} is one of three fixed expressions chosen in Java from the bucket
-- parameter (see JdbcRouteDelayReader); it is never built from input.
SELECT {{bucket}}                                                    AS bucket,
       round(avg(tu.delay_seconds), 1)                               AS avg_delay_seconds,
       percentile_disc(0.5) WITHIN GROUP (ORDER BY tu.delay_seconds) AS median_delay_seconds,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY tu.delay_seconds) AS p90_delay_seconds,
       count(*)                                                      AS observation_count,
       round(100.0 * count(*) FILTER (WHERE tu.delay_seconds BETWEEN -:early AND :late) / count(*), 2)
                                                                     AS on_time_percentage
FROM dw.fact_trip_update tu
WHERE tu.service_date BETWEEN CAST(:fromDate AS date) - 1 AND CAST(:toDate AS date)   -- partition pruning; fact_trip_update_route_idx
  AND tu.route_id = :routeId
  AND tu.scheduled_arrival >= :from AND tu.scheduled_arrival < :to
  AND tu.is_observed AND tu.schedule_relationship = 'SCHEDULED' AND tu.delay_seconds IS NOT NULL
  AND (CAST(:directionId AS smallint) IS NULL OR tu.direction_id = :directionId)
GROUP BY 1
ORDER BY 1
