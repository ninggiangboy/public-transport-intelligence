-- E-02 GET /routes/{routeId}: the points of the chosen shape, in order.
SELECT lat, lon
FROM dw.gtfs_shape
WHERE feed_version_id = :fv AND shape_id = :shapeId
ORDER BY shape_pt_sequence
