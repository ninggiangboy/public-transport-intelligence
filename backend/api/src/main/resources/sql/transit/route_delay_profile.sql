-- E-04 GET /routes/{routeId}/delay-profile: the historical ETA of every stop of a route for one weekday and hour.
-- Primary key (route_id, stop_id, day_of_week, hour_of_day), read by its route_id prefix.
SELECT stop_id, avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count,
       window_start, window_end, computed_at
FROM insight.insight_eta_prediction
WHERE route_id = :routeId AND day_of_week = :dayOfWeek AND hour_of_day = :hourOfDay
