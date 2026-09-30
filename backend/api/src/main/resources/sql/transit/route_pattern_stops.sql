-- E-02 GET /routes/{routeId}: the stops of the representative trip of a direction, in order.
SELECT st.stop_sequence, s.stop_id, s.stop_code, s.stop_name, s.lat, s.lon
FROM dw.gtfs_stop_time st
JOIN dw.dim_stop s ON s.feed_version_id = st.feed_version_id AND s.stop_id = st.stop_id
WHERE st.feed_version_id = :fv AND st.trip_id = :tripId
  AND s.lat IS NOT NULL AND s.lon IS NOT NULL
ORDER BY st.stop_sequence
