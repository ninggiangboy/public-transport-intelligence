-- name: otp
-- Daily OTP rows of a range of service days (DOC-32 E-14): at most 130 routes x 31 days, summed in Java. The route
-- types filter goes through the routes of the ACTIVE feed (:fv).
SELECT route_id, service_date, otp_percentage, on_time_count, early_count, late_count,
       observation_count, trip_count, early_tolerance_seconds, late_tolerance_seconds
FROM insight.insight_otp_scorecard
WHERE service_date BETWEEN :fromDate AND :toDate
  AND (cardinality(:routeIds::text[]) = 0 OR route_id = ANY(:routeIds))
  AND (cardinality(:routeTypes::int[]) = 0 OR route_id IN (
        SELECT r.route_id FROM dw.dim_route r WHERE r.feed_version_id = :fv AND r.route_type = ANY(:routeTypes)))
ORDER BY route_id, service_date
