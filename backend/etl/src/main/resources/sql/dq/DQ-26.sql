-- name: DQ-26 (DOC-16 §3). Vehicles seen only in realtime data, not in vehicles.txt. Recorded, never alerted.
SELECT count(*) FILTER (WHERE source = 'REALTIME') AS violation_count,
       count(*) AS population,
       (SELECT coalesce(jsonb_agg(vehicle_id ORDER BY vehicle_id), '[]'::jsonb)
        FROM (SELECT vehicle_id FROM dw.dim_vehicle WHERE source = 'REALTIME' ORDER BY vehicle_id LIMIT 10) v)::text
         AS sample
FROM dw.dim_vehicle
