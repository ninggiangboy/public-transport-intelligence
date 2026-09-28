# ADR-0015: Chống chạy trùng job: ShedLock, JobInstance, `VERSION` làm fencing

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-24, DR-62, DR-83, ADR-0002, FR-03.6, NFR-01, NFR-04

## Bối cảnh

`etl-batch` có thể chạy nhiều replica (compose chạy 1, K8s ở P7 chạy 2). Mỗi replica có scheduler riêng, nên cùng một cron sẽ kích hoạt ở mọi replica. Ngoài ra:

- Spring Batch không có heartbeat. Pod bị `kill -9` để lại `JobExecution` ở trạng thái `STARTED` mãi mãi, và instance đó không restart được cho tới khi có người sửa tay.
- Advisory lock của Postgres gắn với session, nên không an toàn khi đi qua PgBouncer ở chế độ transaction pooling (P7).
- Pod "zombie" (treo GC, mất mạng tạm thời) có thể tỉnh dậy sau khi đã bị coi là chết và ghi tiếp, trong khi execution thay thế đang chạy.

## Các phương án

1. **Một replica duy nhất cho `etl-batch`.** Đơn giản, nhưng vẫn cần khôi phục execution kẹt sau crash, và mất khả năng HA ở P7.
2. **Bảng lease tự viết có heartbeat và fencing token.** Làm được, nhưng là thêm một cơ chế đồng bộ phân tán phải tự test.
3. **Advisory lock.** Không hợp với PgBouncer transaction pooling.
4. **Kết hợp thứ có sẵn:** ShedLock cho lịch, tính duy nhất của `JobInstance` của Spring Batch, và cột `VERSION` (optimistic lock) mà Spring Batch đã cập nhật ở mỗi chunk làm fencing token.
5. **Leader election (Spring Integration `LockRegistryLeaderInitiator`)** để chỉ một pod chạy scheduler. Vẫn cần lớp 2 và 3 cho trường hợp leader đổi giữa chừng.
6. **Quartz Scheduler cluster (`JobStoreTX`)** thay cho `@Scheduled` + ShedLock. *Bổ sung 2026-09-28 (DR-83), bị loại:* recovery của Quartz chỉ fire lại trigger, không đưa execution Spring Batch đang kẹt ở `STARTED` về `FAILED`, nên vẫn cần lớp 2, lớp 3 và `StaleExecutionRecoverer`. Quartz còn thêm 11 bảng `QRTZ_*` với trạng thái riêng phải khớp với `BATCH_*` và `job_request`, và so thời gian bằng đồng hồ của từng node. Các nhu cầu nó phục vụ (lịch động, calendar loại trừ, trigger hẹn giờ một lần) không có trong dự án.

## Quyết định

Chọn **phương án 4**. Chi tiết ở DOC-19 §7.2.

1. **ShedLock** (`JdbcTemplateLockProvider`, `usingDbTime()`, bảng `ops.shedlock`) trên mọi `@Scheduled`. Job dài dùng `KeepAliveLockProvider`.
2. **Tham số định danh** được thiết kế để mỗi lần chạy hợp lệ là một `JobInstance` (`runKey`, `runDate`, `slot`, `replayRequestId`…). Spring Batch từ chối execution thứ hai đang chạy của cùng instance.
3. **Fencing bằng `VERSION`:** Spring Batch cập nhật `BATCH_STEP_EXECUTION` với điều kiện `VERSION` trong transaction của mỗi chunk. Khi bước khôi phục đánh dấu execution cũ `FAILED` (tăng `VERSION`), lần commit kế tiếp của pod zombie gặp `OptimisticLockingFailureException` và rollback cả chunk.
4. **`StaleExecutionRecoverer`** chạy lúc khởi động và mỗi phút (dưới ShedLock): execution `STARTED` có `LAST_UPDATED` cũ hơn 2 phút và không còn giữ ShedLock của job thì bị đánh dấu `FAILED`/`STALE`, rồi được restart nếu job restart được và không phải job replay.
5. Không dùng advisory lock ở bất cứ đâu. JDBC đặt `prepareThreshold=0` khi đi qua PgBouncer (P7).

## Hệ quả

**Tích cực**

- Không có code đồng bộ phân tán tự viết; mọi lớp là thư viện đã được kiểm chứng.
- Crash giữa job được tự khôi phục trong khoảng 1 đến 3 phút, tiếp tục từ chunk đã commit cuối (FR-03.6).
- Chạy được qua PgBouncer transaction pooling.

**Tiêu cực**

- Fencing chỉ có hiệu lực ở **ranh giới commit chunk**. Tasklet ghi dữ liệu nhiều lần trong một `execute` không được bảo vệ giữa các lần ghi đó; quy tắc là tasklet phải idempotent (mọi tasklet hiện có đều idempotent: tạo partition `IF NOT EXISTS`, xóa theo điều kiện thời gian).
- `stale-after` phải lớn hơn chunk dài nhất; tasklet dài phải chia nhỏ bằng `RepeatStatus.CONTINUABLE` (DOC-19 §7.2).
- Có độ trễ khôi phục tối thiểu 2 phút sau crash (bù lại bằng việc chạy recoverer ngay lúc pod mới khởi động).
- Isolation khi tạo execution hạ xuống `READ_COMMITTED` để tránh lỗi serialization giữa các pod; tính duy nhất vẫn được giữ nhờ ShedLock và kiểm tra `JobExecutionAlreadyRunningException`.
