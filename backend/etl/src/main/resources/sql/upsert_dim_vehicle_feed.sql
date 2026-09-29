-- name: upsert_dim_vehicle_feed (DOC-21 §3.3)
INSERT INTO dw.dim_vehicle AS t (vehicle_id, vehicle_label, vehicle_model, seated_capacity, standing_capacity,
                                 low_floor, wheelchair_access, fuel, source)
VALUES (:vehicle_id, :vehicle_label, :vehicle_model, :seated_capacity, :standing_capacity,
        :low_floor, :wheelchair_access, :fuel, 'FEED')
ON CONFLICT (vehicle_id) DO UPDATE SET
  vehicle_label = excluded.vehicle_label, vehicle_model = excluded.vehicle_model,
  seated_capacity = excluded.seated_capacity, standing_capacity = excluded.standing_capacity,
  low_floor = excluded.low_floor, wheelchair_access = excluded.wheelchair_access, fuel = excluded.fuel,
  source = 'FEED', updated_at = now()
