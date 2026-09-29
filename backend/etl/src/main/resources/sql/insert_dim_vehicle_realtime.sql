-- name: insert_dim_vehicle_realtime (DOC-20 §4.5). A vehicle seen in realtime data but not in vehicles.txt (FR-01.6).
INSERT INTO dw.dim_vehicle (vehicle_id, source)
VALUES (:vehicle_id, 'REALTIME')
ON CONFLICT (vehicle_id) DO NOTHING
