# ADR-0008: Partition key `route_id`, 12 partition cho topic GTFS-rt

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-05, SDD §12.7, NFR-08, DOC-09, DOC-10

## Bối cảnh

Bunching và disruption tính theo `(route_id, direction_id)` và cần thấy các event của một tuyến theo đúng thứ tự trong cùng một consumer. Topic `gtfs.*` là nơi song song hóa chính của ETL (KEDA scale theo lag). Feed Minneapolis có 127 tuyến; cao điểm khoảng 143 message/giây (DOC-10).

## Các phương án

1. **Key `vehicle_id`.** Phân tán đều nhất, nhưng các xe của cùng một tuyến rơi vào nhiều consumer, nên detector phải gom lại qua DB.
2. **Key `route_id`.** Mọi event của một tuyến vào cùng partition, giữ thứ tự theo tuyến. Rủi ro partition nóng với tuyến đông xe.
3. **Key `route_id + direction_id`.** Phân tán tốt hơn một chút, nhưng một số phép tính cấp tuyến (tick đóng episode, baseline theo tuyến) cần cả hai chiều.
4. **Không key (round-robin).** Mất thứ tự.

## Quyết định

- Key **`route_id`** cho `gtfs.vehicle_positions` và `gtfs.trip_updates`.
- **12 partition** cho mỗi topic. Đây cũng là trần số pod consumer có ích (KEDA `maxReplicaCount = 12`).
- Thứ tự chỉ cần trong phạm vi một tuyến; ETL **không** giả định thứ tự giữa các tuyến. Upsert có guard event time (ADR-0003) nên kể cả khi thứ tự bị đảo (rebalance, replay) thì dữ liệu vẫn đúng.
- Theo dõi lag **theo từng partition**. Nếu một partition nóng làm vi phạm NFR-03 ở EXP-07 thì chuyển key sang `route_id + direction_id` bằng ADR mới. Việc chuyển key không làm sai dữ liệu nhờ upsert.

## Hệ quả

- Phân bố không đều: 127 tuyến chia vào 12 partition bằng hash, và các tuyến có số xe rất khác nhau. EXP-07 đo độ lệch thực tế.
- Bunching detector làm việc trên dữ liệu của tuyến trong DB (`vehicle_position_latest`), nên việc giữ thứ tự chủ yếu giúp micro-batch của một tuyến đi liền nhau và giảm tranh chấp ghi, không phải điều kiện đúng đắn.
- Tăng số partition sau này làm đổi ánh xạ key → partition; chỉ làm khi đã dừng producer hoặc chấp nhận một giai đoạn đảo thứ tự (vẫn đúng nhờ guard).
