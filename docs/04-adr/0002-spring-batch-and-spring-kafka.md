# ADR-0002: Dùng Spring Batch và Spring Kafka thay cho engine chunk tự xây

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-21, DR-22, DR-23, DR-24, DR-26, DR-53, DR-62, DR-84, DOC-19, DOC-20

## Bối cảnh

SDD gốc (mục 6.3) chọn tự xây một engine xử lý chunk: Job, Step, JobRepository, ChunkProcessor, SkipPolicy, RetryPolicy. Engine này tham khảo mô hình của Spring Batch và được coi là đóng góp chính của đồ án. Khi phân tích kỹ, phần engine gần như sao chép lại những gì Spring Batch đã có và đã được kiểm chứng: chunk có transaction, skip và retry, scan khi ghi lỗi, `ExecutionContext` ghi cùng transaction với chunk, restart, metadata và metrics. Tự viết lại những phần này tốn khoảng 1–2 tuần ở P2. Nó cũng là nơi dễ sai nhất (ranh giới transaction, restart), trong khi câu hỏi nghiên cứu nằm ở ngữ nghĩa đúng đắn của cả pipeline chứ không nằm ở việc có một engine riêng.

Pipeline có hai chế độ:

- **Batch:** GTFS static, replay DLQ, replay raw zone, các job định kỳ.
- **Streaming:** GTFS-realtime và CDC ticketing. Mỗi consumer thread tạo khoảng một micro-batch mỗi giây.

## Các phương án

1. **Giữ engine tự xây** (SDD gốc).
   - Ưu: kiểm soát hoàn toàn, có sẵn một "đóng góp kỹ thuật" dễ trình bày.
   - Nhược: tốn thời gian; phải tự chứng minh những điều Spring Batch đã chứng minh; nhiều code hơn đồng nghĩa với nhiều lỗi và nhiều test hơn.
2. **Spring Batch cho mọi thứ, kể cả streaming**, bằng một JobExecution cho mỗi poll hoặc một job chạy mãi với `KafkaItemReader`.
   - Mỗi poll một JobExecution: khoảng 260 nghìn execution mỗi ngày cho mỗi thread, làm phình metadata và thêm độ trễ.
   - `KafkaItemReader`: dùng partition assignment thủ công và lưu offset trong `ExecutionContext`, nên mất consumer group rebalance. Điều này mâu thuẫn với việc scale theo lag bằng KEDA (NFR-08).
   - Chạy step bằng `ResourcelessJobRepository` cho mỗi poll: không được thiết kế cho nhiều thread và phụ thuộc vào chi tiết nội bộ.
3. **Spring Batch cho job batch, Spring Kafka cho streaming, dùng chung thành phần nghiệp vụ.** Streaming chạy trên một lớp mỏng `StreamChunkTemplate` dựng từ `TransactionTemplate` và savepoint của Spring.
4. **Spring Cloud Stream hoặc Kafka Streams cho streaming.**
   - Thêm một tầng trừu tượng hoặc một mô hình xử lý khác (state store, exactly-once trên Kafka), trong khi đích ghi là PostgreSQL. Không giải quyết thêm vấn đề nào so với Spring Kafka.
5. **Apache Spark** (Structured Streaming cho streaming, Spark SQL cho batch và replay raw zone). *Bổ sung 2026-09-28 (DR-84), bị loại:*
   - Khối lượng khoảng 143 msg/s và 2,5 GB mỗi ngày vừa sức một PostgreSQL, và pipeline không có join hay shuffle phân tán.
   - Offset nằm ở checkpoint của Spark, không có skip hay DLQ theo item, nên phải chứng minh lại toàn bộ ngữ nghĩa effectively-once và EXP-01…04 mất baseline.
   - Cần thêm driver và executor, vượt ngân sách bộ nhớ của compose; Scala và Jackson 2 xung đột với Spring Boot 4 và Jackson 3.

## Quyết định

Chọn **phương án 3**.

- **Job batch** chạy trên Spring Batch:
  - fault-tolerant chunk step (skip, retry, scan), `SkipListener` ghi DLQ trong transaction của chunk;
  - `JobRepository` JDBC với bảng `BATCH_*` chuẩn trong schema `batch`;
  - `JobOperator` để start và restart; `Tasklet` cho các job SQL theo tập (ETA, OTP, bảo trì).
- **Streaming** chạy trên Spring Kafka:
  - batch listener, offset commit sau khi transaction commit (`AckMode.BATCH`);
  - `DefaultErrorHandler` cùng `ContainerPausingBackOffHandler` cho lỗi hạ tầng.
- **Dùng chung giữa hai chế độ:** `ItemProcessor`, `ItemWriter` (`JdbcBatchItemWriter` upsert), `ErrorClassifier`, `SkipPolicy`, `DeadLetterWriter`.
- **Các thành phần khác trong hệ sinh thái Spring:**
  - ShedLock trên `@Scheduled` để chỉ một pod kích hoạt job;
  - Spring Cloud AWS S3 để đọc raw zone;
  - observation và metrics có sẵn của Spring Batch và Spring Kafka, xuất qua Micrometer Tracing.
- **Không dùng:** partitioning, remote chunking, Spring Cloud Data Flow.

## Hệ quả

**Tích cực**

- Bỏ module `engine`. P2 ngắn lại khoảng 1–2 tuần.
- Restart, audit, metrics và trace của job batch có sẵn.
- Ops console đọc được trạng thái job từ metadata chuẩn.

**Tiêu cực**

- Thuật toán chunk có hai hiện thực: Spring Batch và `StreamChunkTemplate`. Cần một bộ test chạy trên cả hai chế độ.
- Phải hiểu đúng ngữ nghĩa transaction của Spring Batch 6. Điều này được xác minh ở S-06 và bằng test tiêm lỗi, không tin mặc định.
- Spring Batch không có heartbeat, nên cần `StaleExecutionRecoverer` (DR-24).

**Đổi trọng tâm nghiên cứu**

- Đóng góp của đồ án không còn là "engine tự xây". Nó là thiết kế và kiểm chứng ngữ nghĩa effectively-once, cô lập lỗi, DLQ và replay xuyên suốt hai chế độ, trên nền framework chuẩn.
- Các thực nghiệm EXP-01…04 giữ nguyên và vẫn so với baseline của DR-27.

**Việc phát sinh**

- Viết DR-62 (metadata Spring Batch).
- Viết lại DOC-19 thành `batch-and-chunk-processing.md`.
- Sửa Phase 2 trong master plan và ADR-0015.
