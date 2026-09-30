-- E-01 GET /routes: the routes of a feed version. The route_type filter is applied in Java on the cached list.
SELECT route_id, route_short_name, route_long_name, display_name, route_type, route_color,
       route_text_color, route_sort_order, typical_headway_seconds
FROM dw.dim_route
WHERE feed_version_id = :fv
ORDER BY route_sort_order NULLS LAST, display_name, route_id
