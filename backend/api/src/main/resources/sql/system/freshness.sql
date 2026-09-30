-- name: freshness_sources
-- Freshness probe (DOC-32 E-60), one round trip every 15 seconds: the newest event of each source and the newest OTP
-- computation. The ETA table is read apart (freshness-eta.sql) because max(computed_at) scans it.
SELECT
  (SELECT max(event_timestamp) FROM dw.vehicle_position_latest)                                AS vp_last,
  (SELECT max(max_event_ts) FROM ops.etl_stream_batch
    WHERE source = 'GTFS_RT_TRIP_UPDATE' AND started_at >= now() - interval '1 hour')          AS tu_last,
  (SELECT max(created_at) FROM dw.fact_ticket_sales)                                           AS sales_last,
  (SELECT max(computed_at) FROM insight.insight_otp_scorecard
    WHERE service_date >= current_date - 7)                                                    AS otp_computed_at
