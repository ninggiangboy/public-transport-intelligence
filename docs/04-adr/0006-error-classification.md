# ADR-0006: Phân loại lỗi DATA / TRANSIENT_INFRA / FATAL

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-23, DR-69, ADR-0004, ADR-0005, FR-02.3, FR-02.6, FR-02.7, NFR-01, NFR-05, EXP-02

## Bối cảnh

Pipeline gặp ba kiểu lỗi cần hành vi khác hẳn nhau:

- Record hỏng: đi tiếp là đúng, record vào DLQ để người hoặc AI xử lý sau.
- DB hoặc Kafka tạm thời không dùng được: **không được** đưa record vào DLQ, vì record không có lỗi; đưa vào DLQ sẽ làm DLQ đầy hàng nghìn record tốt mỗi lần Postgres restart (EXP-02 kiểm tra đúng điều này).
- Lỗi lập trình hoặc cấu hình: thử lại vô ích, bỏ qua thì âm thầm mất dữ liệu; phải dừng và báo động.

SDD gốc có DLQ nhưng không nói rõ lỗi nào vào DLQ. Cả Spring Batch (`SkipPolicy`, retry) và Spring Kafka (`DefaultErrorHandler`) đều cần một quyết định "loại lỗi nào thì làm gì", và quyết định đó phải giống nhau ở hai chế độ.

## Các phương án

1. **Danh sách exception rải trong cấu hình từng step và listener** (`.skip(X.class).retry(Y.class)`). Dễ lệch nhau giữa các nơi; không xét được SQLState.
2. **Mọi lỗi vào DLQ, retry do người quyết.** Vi phạm yêu cầu ở trên.
3. **Một bộ phân loại duy nhất trả về ba loại**, nối vào `SkipPolicy`, retry policy và error handler của Kafka.

## Quyết định

Chọn **phương án 3**.

`ErrorClassifier implements Classifier<Throwable, ErrorKind>`, bean duy nhất, dùng ở mọi nơi. Thứ tự xét (dừng ở luật khớp đầu tiên):

1. Exception của dự án đã mang loại (`DataException` và lớp con → `DATA`; `TransientInfraException` → `TRANSIENT_INFRA`; `FatalException` → `FATAL`).
2. Đi theo chuỗi `getCause()` tìm `SQLException`; nếu có thì xét SQLState: class `22`, `23` → `DATA`; `08`, `40001`, `40P01`, `53300`, `55P03`, `57P01`, `57P03`, `57014` (statement timeout) → `TRANSIENT_INFRA`; còn lại → `FATAL`.
3. Hierarchy của Spring: `TransientDataAccessException`, `RecoverableDataAccessException`, `CannotGetJdbcConnectionException`, `QueryTimeoutException` → `TRANSIENT_INFRA`; `DataIntegrityViolationException` → `DATA`.
4. Kafka: `RetriableException` → `TRANSIENT_INFRA`.
5. Jackson `JacksonException`, `jakarta.validation.ConstraintViolationException` → `DATA`.
6. Mọi thứ khác → `FATAL`.

Bảng đầy đủ và hành vi từng loại ở DOC-30 §2. Tóm tắt:

| Loại | Job batch | Streaming | DLQ |
| --- | --- | --- | --- |
| `DATA` | Skip (có giới hạn tỷ lệ 20%) | Skip | Có |
| `TRANSIENT_INFRA` | Retry chunk ≤ 5 lần / 60 giây → step `FAILED` → restart | Seek + backoff vô hạn + pause container | **Không** |
| `FATAL` | Step `FAILED`, alert | Dừng container, alert | Không |

## Hệ quả

**Tích cực**

- Một chỗ để sửa khi phát hiện exception bị phân loại sai; test bảng phân loại là test đơn vị thuần.
- Postgres restart không làm DLQ phình (EXP-02 kiểm chứng).
- Mặc định an toàn: lỗi lạ thì dừng lại chứ không âm thầm skip.

**Tiêu cực**

- Mặc định `FATAL` cho lỗi lạ có thể làm consumer dừng vì một exception vô hại chưa được liệt kê. Chấp nhận, vì alert `ConsumerStopped` làm lỗi lộ ra ngay, và sửa chỉ cần thêm một luật.
- Lỗi dữ liệu "ẩn" dưới dạng lỗi hạ tầng (ví dụ một record luôn làm câu lệnh timeout) sẽ bị retry mãi. Có alert lag và runbook RB-03 để người vận hành can thiệp (có thể đưa record vào DLQ bằng tay qua `kafka-consumer-groups --reset-offsets` và replay từ raw zone).
- `SkipLimitExceededException` khi vượt tỷ lệ skip làm cả step dừng, kể cả khi phần lớn record tốt. Đây là chủ ý: một feed hỏng hàng loạt nên dừng lại (FR-02.7).
