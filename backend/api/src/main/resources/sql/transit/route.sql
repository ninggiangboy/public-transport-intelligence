-- E-02 GET /routes/{routeId}: the route itself, by primary key.
SELECT route_id, route_short_name, route_long_name, display_name, route_type, route_color,
       route_text_color, route_sort_order, typical_headway_seconds
FROM dw.dim_route
WHERE feed_version_id = :fv AND route_id = :routeId
