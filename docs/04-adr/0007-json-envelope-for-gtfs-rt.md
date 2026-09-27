# ADR-0007: JSON envelope có `schema_version` cho GTFS-realtime (thay Protobuf)

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-03, DR-04, DR-59, DOC-09, FR-01.5

## Bối cảnh

GTFS-realtime chuẩn là Protobuf `FeedMessage`, một snapshot chứa nhiều entity, thường được lấy qua HTTP polling. Trong dự án, nguồn là simulator do chính mình viết, và pipeline cần:

- tiêm dữ liệu lỗi có chủ đích (JSON hỏng, thiếu trường, sai kiểu) cho EXP-03;
- validate bằng Bean Validation trên DTO, như SDD gốc mô tả;
- minh chứng schema evolution (v1 → v2);
- đọc được payload trực tiếp khi xem DLQ và raw zone.

## Các phương án

1. **Protobuf `FeedMessage` nguyên bản**, mỗi message Kafka là một snapshot. Đúng chuẩn, nhưng một snapshot hỏng làm hỏng mọi entity trong đó; lỗi "JSON hỏng" không mô phỏng được; DLQ khó đọc.
2. **Protobuf, mỗi entity một message.** Vẫn khó tiêm lỗi ở mức byte và khó đọc; cần Schema Registry hoặc tự quản descriptor.
3. **JSON, mỗi entity một message, bọc trong envelope có `schema_version`**, kèm JSON Schema cho từng version.
4. **Avro + Schema Registry.** Thêm một thành phần hạ tầng; JSON hỏng không mô phỏng được.

## Quyết định

Chọn **phương án 3**. Chi tiết hợp đồng ở DOC-09.

- Envelope gồm `schema_version, message_id, entity_type, source, event_timestamp, produced_at, payload`.
- Tên trường bám GTFS-rt (snake_case). Mốc thời gian dùng RFC 3339 UTC thay cho POSIX seconds.
- JSON Schema draft 2020-12 cho từng `(entity_type, schema_version)`, đặt trong `common`, dùng chung cho test của producer và consumer.
- Consumer chọn parser theo `schema_version` trong envelope. Version lạ vào DLQ với `stage=SCHEMA`.
- Mỗi entity một message, key là `route_id` (ADR-0008).

## Hệ quả

- Message lớn hơn Protobuf khoảng 2–3 lần; zstd bù phần lớn. Khối lượng ở DOC-10 vẫn nhỏ.
- Không tương thích trực tiếp với client GTFS-rt chuẩn. Việc xuất ra GTFS-rt chuẩn (nếu cần) là một adapter riêng, nằm ngoài phạm vi.
- Không cần Schema Registry. Tương thích được bảo đảm bằng quy tắc ở DOC-09 §10 và contract test (ADR-0027).
