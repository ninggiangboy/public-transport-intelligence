-- name: freshness_eta
-- Newest ETA computation (DOC-32 E-60). About 3.3 million rows, so the probe runs it every 5 minutes
-- (pti.api.freshness.insight-interval), not every 15 seconds.
SELECT max(computed_at) AS eta_computed_at FROM insight.insight_eta_prediction
