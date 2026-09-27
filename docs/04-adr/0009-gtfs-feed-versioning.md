# ADR-0009: Phiên bản hóa GTFS static bằng `feed_version` và staging swap

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-10, DR-12, DR-01, FR-01.1, FR-04.2, DOC-14, DOC-21, UC-14

## Bối cảnh

GTFS static thay đổi vài tuần một lần. Feed mới có thể hỏng (thiếu file, tham chiếu sai). Trong lúc nạp feed mới, ETL stream vẫn phải validate realtime theo feed đang dùng. EXP-04 cần dựng lại warehouse, gồm cả dimension, từ raw zone. SDD gốc yêu cầu "đổi phiên bản trong một transaction" nhưng chưa nói dimension có giữ lịch sử hay không.

## Các phương án

1. **Ghi đè tại chỗ (TRUNCATE + nạp lại).** Đơn giản, nhưng có khoảng trống khi đang nạp, và feed hỏng làm mất feed cũ.
2. **SCD Type 2 theo từng dòng** (`valid_from/valid_to` cho mỗi route, stop). Giữ lịch sử chi tiết, nhưng join phức tạp; khó "đổi cả feed trong một transaction".
3. **Mỗi feed là một phiên bản trọn vẹn** (`feed_version_id` trong PK của mọi bảng dimension/lịch), nạp vào trạng thái STAGED, validate, rồi đổi con trỏ ACTIVE trong một transaction.

## Quyết định

Chọn **phương án 3**.

- `dw.gtfs_feed_version(feed_version_id, feed_hash UNIQUE, source_uri, valid_from, valid_to, status STAGED|ACTIVE|RETIRED|REJECTED, loaded_at, activated_at, validation_report JSONB)`, cùng partial unique index `WHERE status = 'ACTIVE'` để bảo đảm chỉ có một bản ACTIVE.
- Mọi bảng `dim_*` (trừ `dim_date`, `dim_vehicle`, `dim_sale_point`) và bảng lịch (`gtfs_trip`, `gtfs_stop_time`, `gtfs_shape`, `route_headway`) có PK `(feed_version_id, <natural id>)`. View `*_current` join với bản ACTIVE.
- `GtfsStaticLoadJob`: tính SHA-256 của zip → nếu trùng bản ACTIVE thì NOOP → lưu zip vào `raw/gtfs-static/<hash>.zip` → nạp STAGED theo chunk → validate toàn feed → sinh `route_headway` → một transaction: bản cũ RETIRED, bản mới ACTIVE → phát `feed.activated`.
- Validate hỏng thì REJECTED kèm `validation_report`; bản cũ vẫn ACTIVE.
- Chỉ giữ 3 phiên bản gần nhất (bản ACTIVE và hai bản RETIRED mới nhất); xóa bản cũ hơn bằng `DELETE … WHERE feed_version_id = ?` theo thứ tự khóa ngoại.
- **Fact giữ natural id** (`route_id TEXT`, `stop_id TEXT`), không có `feed_version_id` và không có khóa ngoại tới dimension. Tham chiếu được kiểm tra bằng rule DQ lúc ghi (so với cache của bản ACTIVE) và bằng rule post-write.
- `dim_vehicle` và `dim_sale_point` không phiên bản hóa, vì chúng đến từ nhiều nguồn (feed và realtime, hoặc CDC).

## Hệ quả

- Đổi feed là thao tác nguyên tử; ETL stream làm mới cache dimension khi nhận `feed.activated`. Trong khoảng rất ngắn giữa commit và lúc làm mới cache, record có thể bị validate theo feed cũ; chấp nhận được, vì record sai đi vào DLQ và replay được.
- Mỗi phiên bản tốn khoảng 1 triệu dòng (chủ yếu `gtfs_stop_time`, 873 nghìn dòng). Giữ 3 bản là đủ nhỏ.
- Truy vấn lịch sử theo feed cũ vẫn làm được bằng `feed_version_id`, dù UI không cần.
- Fact không có khóa ngoại nên nạp nhanh và không phụ thuộc thứ tự nạp, đổi lại cần rule DQ để bắt tham chiếu sai.
