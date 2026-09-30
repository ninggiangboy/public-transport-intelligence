-- E-06, E-07: which routes call at which stops, with the headsigns of their trips there. About 2 seconds for a whole
-- feed, so it runs once per feed version (cache stop-routes).
SELECT DISTINCT st.stop_id, t.route_id, t.trip_headsign
FROM dw.gtfs_stop_time st
JOIN dw.gtfs_trip t ON t.feed_version_id = st.feed_version_id AND t.trip_id = st.trip_id
WHERE st.feed_version_id = :fv
