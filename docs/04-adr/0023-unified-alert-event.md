# ADR-0023: Bảng `alert_event` hợp nhất, định tuyến theo audience

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-17, DR-51, ADR-0010, ADR-0016, DOC-15 §3, DOC-23 §10, DOC-28, DOC-32 (E-20, E-21, E-80), FR-07, FR-09

## Bối cảnh

Cảnh báo sinh ra từ nhiều nguồn:

- Analytics: episode gián đoạn, bunching, bất thường vé.
- Triage (P6): DLQ nghiêm trọng.
- Alertmanager: feed GTFS-rt ngừng cập nhật, lỗi hạ tầng.

Người nhận cũng khác nhau: hành khách chỉ nên thấy gián đoạn dịch vụ; điều phối viên thấy bunching và gợi ý; kỹ sư thấy lỗi pipeline. SDD gốc mô tả định tuyến theo đối tượng (mục 9.4) nhưng không có bảng lưu cảnh báo, nên không có lịch sử, không ack được và không lọc được cảnh báo đang mở.

## Các phương án

1. **Không lưu, chỉ đẩy qua SSE.** Mất khi không có client; không có lịch sử, không ack.
2. **Mỗi nguồn một bảng cảnh báo riêng.** Feed phải `UNION` nhiều bảng; mỗi bảng tự làm dedup, ack, resolve.
3. **Dùng thẳng Alertmanager cho mọi cảnh báo.** Alertmanager không có khái niệm hành khách, không có API cho UI, và cảnh báo nghiệp vụ không phải metric.
4. **Một bảng `ops.alert_event` cho mọi loại, có `type`, `severity`, `audience`, `dedup_key` UNIQUE, trỏ về đối tượng gốc bằng `ref_table`/`ref_id`.**

## Quyết định

Chọn **phương án 4**.

- Cột chính: `type DISRUPTION|BUNCHING|DLQ_SEVERE|FEED_STALE|TICKETING_ANOMALY|INFRA`, `severity 0..2`, `audience PUBLIC|OPERATIONS|ENGINEERING`, `route_id` (có thể rỗng), `ref_table`, `ref_id`, `title`, `body JSONB`, `created_at`, `acknowledged_by/at`, `resolved_at`, `dedup_key UNIQUE`. DDL ở DOC-15 §3. `id` của alert cũng là UUIDv5 tính từ `dedup_key`.
- **Audience do nguồn quyết định lúc ghi** và là **mức hiển thị tối thiểu**: `PUBLIC` hiện cho mọi người, `OPERATIONS` và `ENGINEERING` chỉ hiện cho người đã đăng nhập. Ví dụ: episode gián đoạn là `PUBLIC`; bunching và bất thường vé là `OPERATIONS`; DLQ và hạ tầng là `ENGINEERING`. Mỗi sự kiện chỉ sinh một dòng, không nhân bản theo audience.
- **Dedup bằng `dedup_key`** tất định: `disruption:<episode id>`, `bunching:<episode id>`, `ticketing:<anomaly id>` (id UUIDv5 theo ADR-0010), `am:<fingerprint>:<startsAt>` cho Alertmanager. Ghi lại cùng sự kiện (replay, Alertmanager gửi lại) là no-op; đóng episode hoặc Alertmanager gửi `resolved` thì cập nhật `resolved_at`.
- Alertmanager gửi webhook tới `POST /internal/alerts/alertmanager` của API; API ánh xạ `alertname` sang `type` và ghi vào cùng bảng (DOC-32 E-80).
- Sau khi ghi, nguồn phát `alert.created`/`alert.updated`/`alert.retracted` lên `pti.events.ui` (DOC-33); SSE lọc theo audience của người xem.
- Quyền đọc: anonymous chỉ `PUBLIC`; viewer và operator đọc mọi audience; chỉ operator ack.

## Hệ quả

**Tích cực**

- Một feed, một cơ chế dedup, ack và resolve cho mọi nguồn; UI chỉ cần một endpoint.
- Lịch sử cảnh báo nằm trong DB, không phụ thuộc client có đang kết nối hay không (ADR-0026).
- Góc nhìn công khai của gián đoạn được lọc bằng JOIN qua `dedup_key` và `audience`, không cần cột riêng trên bảng insight.

**Tiêu cực**

- Cột `ref_table`/`ref_id` không có khóa ngoại thật (trỏ tới nhiều bảng); tính nhất quán dựa vào code và test.
- Hai nơi ghi (analytics trong ETL, API cho webhook) phải thống nhất định dạng `dedup_key`.
- Bảng tăng theo thời gian; retention do DOC-18 quy định.
