# ADR-0012: Raw zone bằng S3 sink, JSON gzip, phân vùng theo giờ của record

- Trạng thái: Accepted (chi tiết connector xác minh ở S-04)
- Ngày: 2026-09-26 · Liên quan: SDD §4.1, §12.6, FR-01.4, FR-12.2, EXP-04, DOC-09 §7, DOC-18, DOC-22

## Bối cảnh

Raw zone là **nguồn sự thật** để dựng lại warehouse (EXP-04, UC-18) và để replay sau khi sửa lỗi logic. Kafka chỉ giữ 7 ngày. Yêu cầu:

1. Lưu **mọi** message của các topic nguồn, **kể cả message không parse được** (kịch bản `bad-data`), nguyên văn từng byte.
2. Giữ key, headers, partition, offset, timestamp.
3. Chọn được dữ liệu theo khoảng thời gian mà không phải quét toàn bộ.
4. Độc lập với ETL: ETL chết thì raw zone vẫn được ghi.
5. License mã nguồn mở, dùng được trong một repo public mà không phát sinh nghĩa vụ ngoài việc giữ thông báo license.

## Các phương án

1. **ETL tự ghi raw zone.** Phụ thuộc ETL (vi phạm yêu cầu 4); thêm việc cho đường nóng.
2. **Confluent S3 sink connector.** Trưởng thành, có `TimeBasedPartitioner` theo record timestamp; license Confluent Community License (dùng được cho dự án này, nhưng không phải OSI).
3. **Aiven S3 sink connector** (`s3-connector-for-apache-kafka`). Apache-2.0; output JSON lines với các trường chọn được (key, value, offset, timestamp, headers); tên file theo template có timestamp.
4. **Kafka tiered storage.** Không cho ra file đọc được độc lập với Kafka.

## Quyết định

Chọn **phương án 3 (Aiven)**; phương án 2 là dự phòng nếu S-04 thấy Aiven không đáp ứng được yêu cầu 1–3.

- Converter: **`StringConverter` cho key và value** (không parse JSON), để message hỏng vẫn được lưu nguyên văn. Headers giữ nguyên.
- Định dạng: JSON lines, nén gzip. Mỗi dòng gồm `key, value, partition, offset, timestamp, headers` (DOC-09 §7).
- Đường dẫn: `raw/<topic>/dt=YYYY-MM-DD/hh=HH/<topic>-<partition>-<start_offset>.json.gz`, giờ tính theo **timestamp của record** (CreateTime), UTC. Nếu connector chỉ hỗ trợ giờ theo wall clock thì vẫn chấp nhận được, vì replay luôn quét thêm một giờ mỗi phía rồi lọc theo `timestamp` của từng dòng (DOC-22).
- Rotate: 5 phút hoặc 10.000 record.
- Bucket `raw` bật versioning. Chỉ connector (ghi) và `etl-batch` (đọc, ghi `raw/gtfs-static/`) có credential.
- File GTFS static: `raw/gtfs-static/<feed_hash>.zip`, do `GtfsStaticLoadJob` ghi.
- Consumer group của sink: `connect-pti-raw-sink`. Lag của group này được giám sát như mọi consumer khác.
- Delivery của sink là at-least-once: file có thể chứa bản ghi trùng `(topic, partition, offset)` sau khi connector restart. Replay **khử trùng theo `(topic, partition, offset)`**, và dù không khử trùng thì upsert vẫn đúng.

**S-04 phải xác minh:** tên property của Aiven cho template tên file, nguồn timestamp (record hay wall clock), output headers, `StringConverter` với JSON hỏng, gzip; chạy được với SeaweedFS (path-style access, DR-66).

## Hệ quả

- Raw zone tăng khoảng 1 GB mỗi ngày khi chạy 24/7 (DOC-10). Retention ở DOC-18.
- `value` là chuỗi đã escape bên trong JSON, nên file khó đọc bằng mắt hơn; bù lại replay tái tạo đúng từng byte.
- Nếu object storage chết thì sink dừng và tự bắt kịp khi nó chạy lại; Kafka retention 7 ngày là vùng đệm.
