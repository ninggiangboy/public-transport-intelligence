# ADR-0013: Replay: API ghi yêu cầu, ETL thực thi

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-16, DR-18, DR-20, DR-62, ADR-0003, ADR-0014, FR-03, FR-12, UC-08, UC-09

## Bối cảnh

Có hai loại replay:

- **DLQ replay:** chạy lại một record trong `dead_letter` (có thể với payload đã sửa), do operator bấm hoặc triage-worker tự động (P6).
- **Raw zone replay:** nạp lại một khoảng thời gian của một nguồn từ raw zone S3, sau khi sửa lỗi logic hoặc sau khi phục hồi warehouse (RB-11).

Câu hỏi: tiến trình nào thực thi replay, và các tiến trình liên lạc với nhau thế nào? Ràng buộc:

- API chỉ có quyền ghi hẹp (DR-20), không có quyền ghi fact, không có credential S3.
- Replay phải đi qua **đúng** processor và writer của pipeline chính (ADR-0003), nếu không thì kết quả replay khác kết quả chạy trực tiếp.
- Replay phải restart được sau crash và không chạy trùng.

## Các phương án

1. **API tự thực thi replay** bằng cách nhúng thư viện ETL. Phải cấp cho API quyền ghi fact và quyền đọc S3; hai tiến trình có cùng logic ghi; request HTTP dài hoặc phải tự quản thread nền.
2. **API gọi ETL qua HTTP nội bộ.** Thêm một API nội bộ phải bảo mật; ETL down thì request lỗi, cần tự thử lại.
3. **API publish lệnh lên topic Kafka, ETL consume.** Thêm topic và một consumer; trạng thái vẫn cần một bảng để UI đọc.
4. **API ghi một dòng `replay_request` (outbox dạng bảng); ETL quét bảng và chạy job Spring Batch.** Trạng thái và lệnh nằm cùng một chỗ.

## Quyết định

Chọn **phương án 4**.

- API (`replay_operator`) chỉ `INSERT` vào `ops.replay_request` (và `UPDATE` trạng thái `dead_letter` sang `REPLAY_REQUESTED`) trong một transaction, trả `202 Accepted` kèm `id` của request. Có `idempotency_key` để request lặp lại không tạo dòng mới.
- `ReplayRequestPoller` trong `etl-batch` quét mỗi 5 giây với `FOR UPDATE SKIP LOCKED` dưới ShedLock, rồi khởi chạy:
  - `DLQ_RECORD` → `DlqReplayJob(replayRequestId)`;
  - `RAW_RANGE` → `RawZoneReplayJob(replayRequestId, source, fromTs, toTs, recomputeAnalytics)`.
- Cả hai job dùng **cùng** `MessageProcessor` và `FactChunkWriter` với streaming (DOC-19 §4), với `replay = true`: bỏ qua `dedup_registry`, bỏ qua DQ-07 và DQ-12, và guard của upsert cho phép ghi đè bản bằng thời gian (DOC-14 §8).
- Tiến độ và kết quả ghi ngược vào `replay_request` (`status`, `job_execution_id`, `stats`, `message`); UI đọc từ đó. Không cần topic hay API nội bộ.
- Mỗi nguồn chỉ có một `RAW_RANGE` đang chạy (partial unique index, API trả 409). Cửa sổ tối đa 7 ngày.
- Chi tiết luồng, máy trạng thái và lỗi ở DOC-22.

## Hệ quả

**Tích cực**

- API không cần quyền ghi fact hay credential S3; bề mặt tấn công nhỏ.
- Một đường ghi duy nhất cho dữ liệu: replay cho kết quả giống hệt xử lý trực tiếp.
- Replay có đủ tính năng của Spring Batch: restart, chống chạy trùng (ADR-0015), metric.
- `etl-batch` down thì request nằm ở `PENDING` và được chạy khi service lên lại.

**Tiêu cực**

- Độ trễ bắt đầu tối đa khoảng 5 giây (chu kỳ quét). Chấp nhận với thao tác vận hành.
- Quét định kỳ tốn một câu truy vấn mỗi 5 giây trên index partial `status = 'PENDING'`; không đáng kể.
- Tiến độ không được đẩy riêng cho replay: UI poll `GET /etl/replays/{id}` mỗi 2 giây khi màn hình đang mở và replay chưa kết thúc (DOC-32 E-50); SSE `job.run` của lần chạy tương ứng chỉ giúp làm mới sớm hơn.
