-- E-41: the dead letters created since a moment (createdLastHour).
SELECT count(*) AS n
FROM ops.dead_letter
WHERE created_at >= CAST(:since AS timestamptz)
