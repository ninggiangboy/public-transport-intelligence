-- E-08 GET /stops/{stopId}/arrivals: DOC-23 §7.4. The scheduled calls of yesterday and today in the window, with the
-- historical delay and the trip update. The seconds are computed per service date in Java so the stop_time index
-- (feed_version_id, stop_id, departure_seconds) is used; the exact window is the last condition.
WITH cand AS (
  SELECT d.service_date, st.trip_id, st.stop_sequence, t.route_id, t.direction_id, t.trip_headsign,
         dw.gtfs_time_to_ts(d.service_date, st.arrival_seconds, :tz) AS scheduled
  FROM (VALUES (CAST(:today AS date) - 1, CAST(:secFromYesterday AS int), CAST(:secToYesterday AS int)),
               (CAST(:today AS date),     CAST(:secFromToday AS int),     CAST(:secToToday AS int)))
       AS d(service_date, sec_from, sec_to)
  JOIN dw.gtfs_stop_time st
    ON st.feed_version_id = :fv AND st.stop_id = :stopId
   AND st.departure_seconds BETWEEN d.sec_from AND d.sec_to + 3600
  JOIN dw.gtfs_trip t ON t.feed_version_id = st.feed_version_id AND t.trip_id = st.trip_id
  WHERE st.pickup_type <> 1
    AND t.service_id IN (SELECT dw.service_ids_on(:fv, d.service_date))
    AND dw.gtfs_time_to_ts(d.service_date, st.arrival_seconds, :tz)
        BETWEEN CAST(:now AS timestamptz) - interval '30 minutes'
            AND CAST(:now AS timestamptz) + make_interval(secs => :horizonSeconds)
)
SELECT c.service_date, c.trip_id, c.stop_sequence, c.route_id, c.direction_id, c.trip_headsign, c.scheduled,
       e.avg_delay_seconds, e.sample_count,
       tu.is_observed, tu.schedule_relationship, coalesce(tu.arrival_time, tu.departure_time) AS rt_time,
       tu.event_timestamp AS rt_event_ts
FROM cand c
LEFT JOIN insight.insight_eta_prediction e
  ON e.route_id = c.route_id AND e.stop_id = :stopId
 AND e.day_of_week = extract(isodow FROM c.scheduled AT TIME ZONE :tz)::smallint
 AND e.hour_of_day = extract(hour FROM c.scheduled AT TIME ZONE :tz)::smallint
LEFT JOIN dw.fact_trip_update tu
  ON tu.service_date = c.service_date AND tu.trip_id = c.trip_id AND tu.stop_sequence = c.stop_sequence
