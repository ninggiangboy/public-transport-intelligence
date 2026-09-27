# Public Transport Intelligence — Thiết kế hệ thống

Sep 25, 2026 · @KhanhHa

## 1. Giới thiệu

### 1.1 Bối cảnh

Hệ thống giao thông công cộng vận hành dựa trên nhiều nguồn dữ liệu tách rời: lịch chạy theo kế hoạch (GTFS static), vị trí xe và cập nhật chuyến theo thời gian thực (GTFS-realtime), và dữ liệu bán vé từ hệ thống ticketing. Mỗi nguồn có định dạng, tần suất và mức độ tin cậy khác nhau. Trước khi dữ liệu này có thể phục vụ bất kỳ phân tích hay quyết định nào, nó cần được thu thập, làm sạch và hợp nhất một cách đáng tin cậy.

### 1.2 Vấn đề thực tế

- **Thông tin cho hành khách không phản ánh thực tế.** Lịch GTFS static chỉ cho giờ theo kế hoạch; hành khách không biết xe đang trễ bao nhiêu và phải chờ trong mơ hồ.
- **Dữ liệu rời rạc, khó ra quyết định.** Vị trí xe, bán vé và sự cố vận hành nằm ở các hệ thống riêng. Muốn trả lời "tuyến nào đang có vấn đề" phải tra cứu thủ công nhiều nguồn.
- **Bus bunching không được phát hiện chủ động.** Hai xe cùng tuyến dồn sát nhau gây lãng phí năng lực và kéo dài thời gian chờ ở các trạm khác, nhưng thường chỉ được biết khi hành khách phàn nàn.
- **Lỗi dữ liệu lan âm thầm.** Khi feed lỗi, API nguồn gián đoạn hoặc schema thay đổi, pipeline thiếu cơ chế cô lập và cảnh báo sẽ để dữ liệu sai hoặc thiếu đi thẳng lên dashboard mà không ai hay biết.

### 1.3 Tầm nhìn

Public Transport Intelligence biến dữ liệu vận hành rời rạc thành ba lớp giá trị:

1. **Dữ liệu sạch và đáng tin** — nhờ một ETL pipeline có đầy đủ cơ chế vận hành thực tế: idempotency, dead letter queue, checkpoint, replay.
2. **Insight hành động được** — phát hiện bunching, gián đoạn dịch vụ, dự đoán giờ đến, đánh giá độ đúng giờ, thay vì chỉ hiển thị số liệu thô.
3. **Giao diện phục vụ đúng người** — hành khách thấy giờ xe sát thực tế; người vận hành thấy tình trạng tuyến và sức khỏe pipeline; đội kỹ thuật biết ngay lỗi nào cần xử lý trước.

## 2. Mục tiêu, phạm vi và trọng tâm nghiên cứu

### 2.1 Câu hỏi nghiên cứu

> Làm thế nào để thiết kế một ETL pipeline vừa xử lý được dữ liệu không đồng nhất (batch và gần thời gian thực), vừa đảm bảo tính đúng đắn khi xảy ra lỗi hoặc gián đoạn — không mất dữ liệu, không tạo bản ghi trùng, tự phục hồi được — áp dụng cho dữ liệu giao thông công cộng?

### 2.2 Đóng góp chính và mức độ đầu tư theo module

| Module | Vai trò trong đồ án | Mức độ đầu tư |
| --- | --- | --- |
| ETL pipeline | Đóng góp chính: thiết kế và kiểm chứng ngữ nghĩa effectively-once, cô lập lỗi, DLQ và replay trên cả luồng streaming lẫn batch (dựng trên Spring Batch và Spring Kafka), trả lời trực tiếp câu hỏi nghiên cứu | Đào sâu, có thực nghiệm đo đạc |
| Analytics layer | Chứng minh dữ liệu sạch tạo ra insight có giá trị | Đào sâu |
| Dashboard | Giao diện người dùng và công cụ minh chứng trực quan cho thực nghiệm ETL | Đào sâu |
| AI-assisted triage (Jev) | Phân loại lỗi và bất thường, tự xử lý DLQ theo ngưỡng, làm giàu cảnh báo, gợi ý điều phối | Triển khai có kiểm soát, đánh giá tùy chọn |
| Data warehouse, Backend API | Hạ tầng phục vụ | Triển khai chắc chắn, không đào sâu |

### 2.3 Phạm vi

**Trong phạm vi**

- Hệ thống standalone, chỉ phục vụ domain giao thông công cộng (không phải nền tảng ETL dùng chung).
- Toàn bộ hạ tầng chạy như production: Kafka, CDC (Debezium), ETL, warehouse, analytics, API, dashboard, observability.
- Ba nguồn dữ liệu: GTFS static, GTFS-realtime, ticketing.
- Triển khai bằng Docker Compose (dev) và Kubernetes cục bộ k3d (staging, thực nghiệm mở rộng và chịu lỗi).

**Ngoài phạm vi**

- Kết nối với hệ thống GTFS hoặc ticketing thật của một đơn vị vận hành — thay bằng source simulator (mục 5).
- Triển khai production trên cloud, multi-region; Kubernetes chỉ chạy cục bộ để kiểm chứng thiết kế.
- Mô hình machine learning phức tạp; analytics dùng phương pháp thống kê có thể giải thích được.
- Ứng dụng di động cho hành khách.

## 3. Người dùng và yêu cầu

### 3.1 Nhóm người dùng

- **Hành khách** — cần biết giờ xe dự kiến sát thực tế và được cảnh báo khi tuyến gặp bất thường để chọn phương án khác.
- **Người vận hành / quản lý tuyến** — cần thấy tuyến nào đang trễ bất thường, xe nào đang bunching, tuyến nào có độ đúng giờ kém theo thời gian, mà không phải tự tổng hợp từ nhiều nguồn.
- **Đội kỹ thuật / data** — cần một pipeline tin cậy; khi có lỗi phải biết ngay mức độ nghiêm trọng và có công cụ xử lý (xem, phân loại, replay).

### 3.2 Yêu cầu chức năng

| Mã | Yêu cầu |
| --- | --- |
| FR-01 | Thu thập dữ liệu GTFS static theo batch, GTFS-realtime qua Kafka, ticketing qua CDC. |
| FR-02 | Validate schema và business rule; record lỗi được đưa vào dead letter queue, không làm dừng batch. |
| FR-03 | Loại trùng theo business key; chạy lại cùng dữ liệu không tạo bản ghi trùng. |
| FR-04 | Nạp dữ liệu vào warehouse dạng star schema. |
| FR-05 | Phát hiện bus bunching gần thời gian thực. |
| FR-06 | Dự đoán ETA dựa trên độ trễ lịch sử theo tuyến, trạm, khung giờ. |
| FR-07 | Phát hiện gián đoạn dịch vụ bằng baseline thích ứng. |
| FR-08 | Tính on-time performance theo tuyến và ngày. |
| FR-09 | Phân loại và đánh giá mức độ nghiêm trọng cho record trong DLQ và bất thường ticketing. |
| FR-10 | Cung cấp REST API và kênh real-time (SSE/WebSocket) cho dashboard. |
| FR-11 | Dashboard: bản đồ xe, cảnh báo, scorecard, bảng điều khiển vận hành ETL. |
| FR-12 | Replay record trong DLQ và replay dữ liệu từ raw zone. |

### 3.3 Yêu cầu phi chức năng

| Mã | Yêu cầu | Chỉ tiêu |
| --- | --- | --- |
| NFR-01 | Tính đúng đắn khi lỗi | Không mất dữ liệu, tỷ lệ trùng = 0 sau khi restart hoặc replay |
| NFR-02 | Cô lập lỗi | Record lỗi không làm fail các record hợp lệ trong cùng batch |
| NFR-03 | Độ trễ dữ liệu | Từ lúc event vào Kafka đến lúc hiện trên dashboard dưới 10 giây (môi trường demo) |
| NFR-04 | Khả năng phục hồi | Consumer tự tiếp tục từ offset đã commit sau khi bị dừng đột ngột |
| NFR-05 | Quan sát được | Mọi batch truy vết được qua `batch_id` trong log, metrics, trace |
| NFR-06 | Bảo mật | Không hard-code secret; phân quyền DB theo nguyên tắc tối thiểu |
| NFR-07 | Tái lập môi trường | Toàn bộ hệ thống khởi chạy bằng một lệnh `docker compose up` |
| NFR-08 | Mở rộng theo tải | Tự scale đến 10 lần tải nền mà vẫn đạt NFR-03 (kiểm chứng bằng EXP-07) |
| NFR-09 | Chịu lỗi | Một sự cố đơn lẻ (pod, broker, database primary, dịch vụ ngoài) không gây mất hoặc trùng dữ liệu; tự phục hồi không cần can thiệp (EXP-08) |

## 4. Kiến trúc tổng thể và công nghệ

### 4.1 Các thành phần

1. **Source simulator** — đóng vai hệ thống nguồn: publish GTFS-realtime vào Kafka, ghi giao dịch vé vào database nguồn.
2. **Streaming backbone** — Kafka (KRaft) và Kafka Connect với Debezium (CDC từ database ticketing) và S3 sink (lưu raw event vào MinIO).
3. **ETL pipeline** — consumer streaming (Spring Kafka) cho GTFS-realtime và ticketing; job batch (Spring Batch) cho GTFS static, replay và các job định kỳ; validate, dedup, load theo chunk có transaction, DLQ, checkpoint.
4. **Data warehouse** — PostgreSQL, star schema cùng các bảng vận hành và bảng insight.
5. **Analytics layer** — bunching và disruption chạy sau mỗi micro-batch; ETA và OTP chạy theo lịch.
6. **AI-assisted triage** — worker bất đồng bộ gọi Jev cho triage và tự xử lý DLQ, bất thường ticketing, làm giàu cảnh báo gián đoạn, gợi ý điều phối bunching.
7. **Backend API** — Spring Boot, REST và SSE, là cửa duy nhất để frontend đọc dữ liệu.
8. **Dashboard** — React/TypeScript.
9. **Observability** — Prometheus, Grafana, Alertmanager, OpenTelemetry.

### 4.2 Luồng dữ liệu

```
Source simulator
 ├─ GTFS-realtime ──────────────> Kafka topic gtfs.*
 └─ Ticketing DB ── Debezium ───> Kafka topic ticketing.sales.cdc
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                  ▼
              S3 sink → MinIO   ETL consumers      (GTFS static: batch job)
              (raw zone)        validate → dedup → load (chunk tx)
                                      │            └─> DLQ ──> Jev triage
                                      ▼
                              PostgreSQL warehouse
                                      │
                              Analytics jobs → bảng insight_*
                                      │
                              Backend API (REST + SSE)
                                      │
                              Dashboard React
```

Nguyên tắc xuyên suốt: mọi cơ chế đảm bảo độ tin cậy (idempotency, retry, DLQ, checkpoint) được hiện thực một lần tại tầng ETL. Các tầng phía sau chỉ đọc dữ liệu đã qua ETL, không truy cập nguồn thô.

### 4.3 Công nghệ

| Lớp | Công nghệ |
| --- | --- |
| Streaming | Apache Kafka (KRaft), Kafka Connect, Debezium PostgreSQL connector, S3 sink connector |
| Lưu trữ | PostgreSQL (warehouse và database nguồn ticketing), MinIO (raw zone) |
| ETL | Java và Spring Boot (phiên bản chốt ở DR-53), Spring Batch, Spring Kafka, Jakarta Bean Validation (Hibernate Validator), Spring JDBC (`JdbcBatchItemWriter`, `NamedParameterJdbcTemplate`), Spring Cloud AWS S3 (đọc raw zone trên MinIO) |
| Orchestration | Spring Batch (Job, Step, JobRepository JDBC, fault-tolerant chunk, restart) cho job batch; lịch chạy bằng Spring `@Scheduled` kèm ShedLock để chỉ một pod kích hoạt; consumer streaming là Spring Kafka batch listener chạy trong service Spring Boot riêng |
| Resilience | Retry và skip của Spring Batch cho job batch; `DefaultErrorHandler` + `ContainerPausingBackOffHandler` của Spring Kafka cho streaming; Resilience4j (circuit breaker, bulkhead, rate limiter) cho DB và lời gọi Jev |
| Migration | Flyway |
| API | Spring Boot 3 (Spring Web, Spring Security, springdoc-openapi) |
| Frontend | React, TypeScript, TanStack Query, thư viện bản đồ (MapLibre hoặc Leaflet) |
| AI triage | Jev (TypeSafe AI), gọi REST API bằng Java HttpClient hoặc SDK Java cộng đồng |
| Observability | Micrometer (metrics và observation có sẵn của Spring Batch, Spring Kafka, Spring MVC), Micrometer Tracing với bridge OpenTelemetry xuất OTLP, Prometheus, Grafana, Alertmanager |
| Triển khai | Gradle (multi-module), Docker Compose, Kubernetes (k3d) + Helm, Strimzi, CloudNativePG, KEDA, Chaos Mesh, Toxiproxy, GitHub Actions |

## 5. Nguồn dữ liệu và source simulator

### 5.1 Nguồn dữ liệu

| Nguồn | Nội dung | Cơ chế đưa vào hệ thống | Vai trò |
| --- | --- | --- | --- |
| GTFS static | routes, stops, trips, stop\_times, calendar | Batch job định kỳ, đọc feed zip | Dimension, lịch theo kế hoạch |
| GTFS-realtime | vehicle positions, trip updates | Kafka topic `gtfs.vehicle_positions`, `gtfs.trip_updates` | Fact thời gian thực |
| Ticketing | giao dịch bán vé, hoàn vé | Debezium CDC → topic `ticketing.sales.cdc` | Fact bán vé |

GTFS static dùng một feed công khai có sẵn (có thể lấy feed mẫu của một thành phố đã công bố GTFS) để lịch trình, tuyến và trạm có cấu trúc thật.

### 5.2 Source simulator

Đồ án không kết nối hệ thống nguồn thật, nên thay bằng source simulator. Đây là ranh giới duy nhất không "như thật"; từ Kafka trở đi toàn bộ là hạ tầng thật.

- `GtfsRealtimeProducer` — dựa trên lịch GTFS static, mô phỏng xe chạy trên tuyến kèm độ trễ ngẫu nhiên có kiểm soát, publish vehicle position và trip update vào Kafka mỗi vài giây.
- `TicketingSeeder` — ghi giao dịch vào database `ticketing_source`; Debezium bắt thay đổi và phát thành Kafka event, đúng cơ chế CDC thật.

Cả hai nằm trong một ứng dụng Spring Boot riêng (module `source-simulator`), điều khiển kịch bản qua REST endpoint nội bộ.

### 5.3 Kịch bản điều khiển được

Simulator có các chế độ để tái hiện tình huống cần demo và thực nghiệm:

- **Bunching** — ép hai xe cùng tuyến chạy sát nhau.
- **Disruption** — tăng mạnh độ trễ trên một tuyến trong một khoảng thời gian.
- **Dữ liệu lỗi** — sinh N% message sai schema, thiếu trường bắt buộc, hoặc tham chiếu route/stop không tồn tại.
- **Trùng lặp** — gửi lại cùng message để kiểm tra dedup.
- **Bất thường ticketing** — tạo đột biến giao dịch tại một điểm bán hoặc chuỗi hoàn vé dồn dập.

## 6. ETL pipeline

### 6.1 Luồng xử lý

```
Spring Kafka batch listener (streaming)  hoặc  Spring Batch chunk step (GTFS static, replay)
  → deserialize + validate schema (Jakarta Bean Validation, theo schema_version)   ┐ ItemProcessor
  → business validation + data quality check                                       │ dùng chung
  → dedup (business key + hash)                                                    ┘ cho hai chế độ
  → load theo chunk trong một transaction (upsert bằng JdbcBatchItemWriter)          ItemWriter dùng chung
  → streaming: commit Kafka offset sau khi transaction commit
    batch: Spring Batch ghi ExecutionContext + StepExecution trong cùng transaction
  → ghi audit (streaming: etl_stream_batch; batch: bảng metadata BATCH_* của Spring Batch)
    + phát metrics (Micrometer)
  → kích hoạt analytics micro-batch (sự kiện after-commit)
Record lỗi ở bất kỳ bước nào → skip → dead_letter (+ lý do, stage) → chunk tiếp tục
```

Ngữ nghĩa giao nhận: Kafka cung cấp at-least-once; kết hợp upsert theo business key và dedup cho kết quả **effectively-once** tại warehouse. Offset chỉ được commit sau khi transaction của chunk đã commit, nên dừng đột ngột ở bất kỳ thời điểm nào cũng chỉ dẫn tới xử lý lại, không mất dữ liệu.

### 6.2 Cơ chế đảm bảo độ tin cậy

| Cơ chế | Hiện thực |
| --- | --- |
| Idempotency | Mỗi lần xử lý có `batch_id` (UUID). Business key `(trip_id, stop_sequence, service_date)` cho trip update, `(vehicle_id, event_timestamp)` cho vehicle position, `transaction_id` cho ticketing. Load bằng `INSERT ... ON CONFLICT DO UPDATE`. |
| Retry | Chỉ áp dụng cho lỗi tạm thời (timeout, mất kết nối DB/Kafka), exponential backoff có jitter. Batch: retry của fault-tolerant step trong Spring Batch (`retry(...)`, `retryLimit`, backoff). Streaming: `DefaultErrorHandler` của Spring Kafka seek lại đầu batch và thử lại, `ContainerPausingBackOffHandler` pause container trong lúc chờ để không vượt `max.poll.interval.ms`. Lỗi schema và logic không retry mà đi thẳng vào DLQ. |
| Dead letter queue | Bảng `dead_letter` lưu payload gốc, stage, thông báo lỗi. Batch: `SkipListener` của Spring Batch ghi DLQ trong chính transaction của chunk. Streaming: record bị skip được ghi DLQ trong cùng transaction với dữ liệu hợp lệ. Một record lỗi không làm fail các record khác trong chunk. |
| Checkpoint | Streaming: Kafka consumer offset, container commit sau khi listener trả về, tức sau khi transaction của chunk đã commit. Batch: `JobRepository` JDBC của Spring Batch (`ExecutionContext` của các reader `ItemStream` ghi cùng transaction với chunk, restart từ chunk cuối đã commit) cùng bảng `etl_checkpoint(source, last_watermark)` cho watermark nghiệp vụ. |
| Transaction | Chunk mặc định 500 record, mỗi chunk là một transaction. Ghi batch trước; nếu lỗi dữ liệu khi ghi thì chuyển sang scan mode để tách record lỗi mà không mất cả chunk (batch: scan có sẵn của Spring Batch; streaming: savepoint theo record qua `TransactionStatus`). |
| Validation | Schema validation (Jakarta Bean Validation trên record DTO) ngay khi deserialize; business validation (thời gian hợp lệ, route/stop tồn tại, giá vé không âm). |
| Deduplication | Business key làm khóa chính logic; thêm hash SHA-256 của payload trong `dedup_registry` để nhận diện message gửi lại y hệt. |
| Incremental load | Streaming theo offset; ticketing qua CDC; GTFS static theo watermark phiên bản feed. |
| Data quality | Bộ rule chất lượng (SQL assertion chạy sau mỗi chunk và mỗi job): null ở trường bắt buộc, trùng business key, toàn vẹn tham chiếu với dimension. Vi phạm được đưa vào DLQ. |
| Schema evolution | Message mang `schema_version`; parser hỗ trợ nhiều phiên bản; chỉ chấp nhận thay đổi tương thích ngược (thêm trường optional). |
| Backpressure | Giới hạn `max.poll.records`, kích thước connection pool và số chunk xử lý song song; listener container tạm dừng (pause/resume) khi DB chậm. |
| Audit | Job batch: bảng metadata của Spring Batch (`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`: read/write/skip/commit/rollback count, thời gian, trạng thái). Streaming: bảng `etl_stream_batch` ghi số record đọc, ghi, lỗi, dải offset cho mỗi micro-batch. View `ops_job_run_v` hợp nhất hai nguồn cho ops console. |
| Recovery và replay | Ba mức: retry tại chỗ → restart từ offset/checkpoint → replay từ raw zone (MinIO) hoặc reset offset trong thời gian retention của Kafka. Record DLQ có thể replay riêng lẻ sau khi sửa. |

### 6.3 Xử lý chunk trên Spring Batch và Spring Kafka

Đồ án không tự xây engine xử lý chunk. Job batch chạy trên **Spring Batch**; luồng streaming chạy trên **Spring Kafka**. Hai chế độ dùng chung các thành phần nghiệp vụ (processor, writer, phân loại lỗi, DLQ). Đóng góp của đồ án nằm ở thiết kế ngữ nghĩa đúng đắn xuyên suốt hai chế độ (business key, guard theo event time, phân loại lỗi, DLQ, replay) và ở thực nghiệm chứng minh nó; phần hạ tầng chunk, restart và metadata dùng lại thứ đã được kiểm chứng trong hệ sinh thái Spring (ADR-0002).

| Nhu cầu | Thành phần Spring dùng lại | Ghi chú |
| --- | --- | --- |
| Job, Step, trạng thái lần chạy | `Job`, `Step`, `JobExecution`, `StepExecution`, `BatchStatus` của Spring Batch | Flow có điều kiện (`JobExecutionDecider`) cho nhánh chấp nhận hoặc từ chối feed GTFS |
| Đọc, xử lý, ghi item | `ItemReader`/`ItemProcessor`/`ItemWriter`; reader có sẵn: `FlatFileItemReader` (CSV GTFS), `JdbcPagingItemReader` (DLQ, warehouse), `MultiResourceItemReader` (raw zone) | Processor và writer là bean dùng chung với streaming |
| Ghi upsert theo batch | `JdbcBatchItemWriter` với `INSERT … ON CONFLICT DO UPDATE … WHERE` (guard event time), `assertUpdates(false)` vì guard có thể làm 0 dòng bị cập nhật; `CompositeItemWriter` khi một item ghi nhiều bảng | |
| Chunk có transaction, skip, retry, scan | Fault-tolerant chunk step: skip theo `SkipPolicy`, retry lỗi tạm thời có backoff; khi ghi lỗi thì tự chuyển sang scan từng item | `SkipPolicy` dựa trên `ErrorClassifier` (DR-23) |
| DLQ | `SkipListener` (`onSkipInRead/Process/Write`) gọi `DeadLetterWriter`, chạy trong transaction của chunk | |
| Checkpoint, restart | `JobRepository` JDBC; `ExecutionContext` của reader `ItemStream` ghi cùng transaction với chunk; `JobOperator.restart` | Bảng `BATCH_*` trong schema `batch`, tạo bằng Flyway |
| Chống chạy trùng giữa các pod | ShedLock trên `@Scheduled`; Spring Batch từ chối chạy song song cùng một JobInstance; optimistic locking trên `BATCH_STEP_EXECUTION.VERSION` chặn pod "zombie" commit chunk | Thay cho lease table tự viết (DR-24) |
| Streaming | Spring Kafka batch listener, commit offset sau khi listener trả về; `DefaultErrorHandler` + `ContainerPausingBackOffHandler` cho lỗi hạ tầng | Phần tự viết duy nhất là `StreamChunkTemplate` (mục dưới) |
| Metrics, trace | Metrics và observation có sẵn của Spring Batch (`spring.batch.job`, `spring.batch.step`, `spring.batch.item.*`, `spring.batch.chunk.write`) và Spring Kafka | Thêm metric nghiệp vụ (`records_duplicate_total`, `dlq_size`…) |

**Ngữ nghĩa restart (batch).** Spring Batch cập nhật `ExecutionContext` và `StepExecution` trong cùng transaction với dữ liệu của chunk. Khi restart một JobExecution FAILED với cùng job parameters, reader khôi phục vị trí từ context và đọc tiếp ngay sau chunk cuối đã commit. Nếu một chunk bị xử lý lại, upsert theo business key đảm bảo không tạo bản ghi trùng. Execution bị kẹt ở trạng thái STARTED do pod chết được đánh dấu FAILED bởi bước khôi phục khi khởi động (DR-24) rồi mới restart.

**Một bộ thành phần, hai chế độ.** Job batch (GTFS static, replay DLQ, replay raw zone) chạy fault-tolerant chunk step của Spring Batch. Listener streaming không tạo một JobExecution cho mỗi poll (khoảng một lần mỗi giây mỗi thread, sẽ làm phình metadata và thêm độ trễ), mà gọi `StreamChunkTemplate`: một lớp nhỏ trong module `etl` dùng lại đúng các bean `ItemProcessor`, `ItemWriter`, `ErrorClassifier`, `DeadLetterWriter` và `TransactionTemplate` của Spring. Thuật toán: process từng record (lỗi dữ liệu → danh sách skip), ghi batch trong một transaction cùng DLQ; nếu ghi gặp lỗi dữ liệu thì mở transaction mới và ghi từng record với savepoint (`TransactionStatus.createSavepoint`/`rollbackToSavepoint`). Lỗi hạ tầng được ném ra để error handler của Spring Kafka xử lý.

**Job analytics theo lịch.** ETA, OTP, ticketing anomaly, bảo trì partition và dọn dẹp là các job Spring Batch gồm `Tasklet` step chạy SQL theo tập (set-based), không cần chunk. Spring Batch vẫn cho audit, restart và chống chạy trùng.

**Phạm vi có chủ đích.** Không dùng partitioning, remote chunking hay Spring Cloud Data Flow; mỗi step chạy một luồng. Phần cấu hình và `StreamChunkTemplate` có bộ test tiêm lỗi riêng, và tính đúng đắn khi restart được kiểm chứng bằng EXP-01.

## 7. Mô hình dữ liệu

### 7.1 Warehouse (star schema)

| Bảng | Loại | Nội dung chính |
| --- | --- | --- |
| `dim_route` | Dimension | route\_id, tên, loại phương tiện, headway theo lịch |
| `dim_stop` | Dimension | stop\_id, tên, tọa độ |
| `dim_vehicle` | Dimension | vehicle\_id, sức chứa |
| `dim_time` | Dimension | ngày, giờ, thứ trong tuần, cờ ngày lễ |
| `fact_trip_update` | Fact | trip\_id, route\_id, stop\_id, service\_date, scheduled\_time, actual\_time, delay\_seconds, batch\_id |
| `fact_vehicle_position` | Fact | vehicle\_id, route\_id, trip\_id, lat, lon, event\_timestamp, batch\_id |
| `fact_ticket_sales` | Fact | transaction\_id, route\_id, stop\_id, sale\_point, amount, type (bán/hoàn), event\_time, batch\_id |

### 7.2 Bảng vận hành ETL

| Bảng | Mục đích |
| --- | --- |
| `batch.BATCH_*` | Metadata chuẩn của Spring Batch (job instance, job/step execution, execution context, job parameters): audit và dữ liệu restart của job batch |
| `etl_stream_batch` | Audit từng micro-batch streaming: listener, dải offset theo partition, thời gian, trạng thái, rows\_read/written/skipped/duplicate |
| `ops_job_run_v` | View hợp nhất `BATCH_JOB_EXECUTION` và `etl_stream_batch` cho ops console và API |
| `etl_checkpoint` | Watermark nghiệp vụ cho các nguồn và job batch (ví dụ hash phiên bản feed GTFS) |
| `shedlock` | Khóa của ShedLock cho các tác vụ `@Scheduled` |
| `dead_letter` | Record lỗi: payload gốc, stage, lỗi, cùng kết quả triage (category, severity, confidence), trạng thái xử lý, số lần auto-replay |
| `dedup_registry` | Hash payload đã xử lý |

### 7.3 Bảng insight

```sql
CREATE TABLE insight_bus_bunching (
  id UUID PRIMARY KEY,
  route_id TEXT NOT NULL,
  vehicle_id_1 TEXT NOT NULL,
  vehicle_id_2 TEXT NOT NULL,
  gap_seconds INT NOT NULL,
  threshold_seconds INT NOT NULL,
  detected_at TIMESTAMPTZ NOT NULL,
  batch_id UUID NOT NULL,
  UNIQUE (route_id, vehicle_id_1, vehicle_id_2, detected_at)
);

CREATE TABLE insight_dispatch_suggestion (
  id UUID PRIMARY KEY,
  bunching_id UUID NOT NULL REFERENCES insight_bus_bunching (id),
  action TEXT,                    -- hold_follower | skip_stops | no_action
  action_confidence NUMERIC,
  model_version TEXT,
  operator_feedback TEXT,         -- accepted | ignored | null
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (bunching_id)
);

CREATE TABLE insight_eta_prediction (
  route_id TEXT NOT NULL,
  stop_id TEXT NOT NULL,
  day_of_week SMALLINT NOT NULL,
  hour_of_day SMALLINT NOT NULL,
  avg_delay_seconds NUMERIC NOT NULL,
  sample_count INT NOT NULL,
  computed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (route_id, stop_id, day_of_week, hour_of_day)
);

CREATE TABLE insight_service_disruption (
  id UUID PRIMARY KEY,
  route_id TEXT NOT NULL,
  detected_at TIMESTAMPTZ NOT NULL,
  current_avg_delay NUMERIC NOT NULL,
  baseline_avg_delay NUMERIC NOT NULL,
  z_score NUMERIC NOT NULL,
  data_issue_probability NUMERIC,  -- Jev: xác suất là lỗi dữ liệu
  likely_cause TEXT,               -- Jev: nguyên nhân khả dĩ
  cause_confidence NUMERIC,
  model_version TEXT,
  batch_id UUID NOT NULL,
  UNIQUE (route_id, detected_at)
);

CREATE TABLE insight_otp_scorecard (
  route_id TEXT NOT NULL,
  service_date DATE NOT NULL,
  otp_percentage NUMERIC NOT NULL,
  trip_count INT NOT NULL,
  computed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (route_id, service_date)
);

CREATE TABLE insight_ticketing_anomaly (
  id UUID PRIMARY KEY,
  sale_point TEXT NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,
  summary JSONB NOT NULL,
  category TEXT,
  severity SMALLINT,
  severity_confidence NUMERIC,
  model_version TEXT,
  detected_at TIMESTAMPTZ NOT NULL,
  UNIQUE (sale_point, window_start)
);
```

Mọi bảng insight có ràng buộc UNIQUE hoặc khóa chính tự nhiên để tính toán lại (khi replay) cũng idempotent như tầng ETL.

## 8. Analytics layer

Bốn module dùng phương pháp thống kê có thể giải thích được, không cần mô hình học máy. Tất cả đọc từ warehouse, ghi vào bảng `insight_*`, và idempotent khi chạy lại.

| Module | Lịch chạy | Câu hỏi trả lời | Người dùng chính |
| --- | --- | --- | --- |
| Bus bunching | Sau mỗi micro-batch | Hai xe cùng tuyến có đang dồn sát nhau không? | Người vận hành, hành khách |
| Service disruption | Sau mỗi micro-batch | Tuyến này có đang trễ bất thường so với chính nó không? | Người vận hành |
| ETA prediction | Hàng giờ | Xe sẽ đến trạm này lúc mấy giờ? | Hành khách |
| OTP scorecard | Hàng ngày | Tuyến nào đúng giờ kém theo thời gian? | Quản lý tuyến |

### 8.1 Bus bunching detection

- Với mỗi tuyến, lấy các xe cùng chiều tại thời điểm micro-batch, sắp theo vị trí trên tuyến và tính khoảng cách thời gian giữa hai xe liên tiếp.
- Nếu `gap_seconds < 0.5 × headway theo lịch` thì ghi sự kiện vào `insight_bus_bunching`.
- Ràng buộc UNIQUE đảm bảo chạy lại cùng batch không tạo sự kiện trùng.

### 8.2 ETA prediction

- Đọc `fact_trip_update` mới kể từ watermark riêng của job trong `etl_checkpoint`.
- Gom theo `(route_id, stop_id, day_of_week, hour_of_day)`, cập nhật trung bình độ trễ và `sample_count`.
- ETA = giờ theo lịch + độ trễ trung bình của khung tương ứng. `sample_count` được trả kèm để giao diện thể hiện độ tin cậy.

### 8.3 Service disruption detection

- Mỗi tuyến duy trì baseline EWMA của độ trễ: `baseline_new = α × current_avg + (1 − α) × baseline_old`, cùng độ lệch chuẩn EWMA.
- Sau mỗi micro-batch tính z-score của độ trễ hiện tại so với baseline; `z_score > 2.5` (tham số điều chỉnh được) thì ghi sự kiện gián đoạn.
- Khác với ETA (baseline tĩnh theo khung giờ), baseline thích ứng giúp phát hiện bất thường ngay cả trong khung giờ vốn ổn định.

### 8.4 On-time performance scorecard

- Một chuyến được coi là đúng giờ nếu `|actual_time − scheduled_time| ≤ 5 phút` (ngưỡng phổ biến trong ngành, có thể cấu hình).
- Job hàng ngày tính `otp_percentage` theo `(route_id, service_date)`.
- Phục vụ góc nhìn quản lý dài hạn mà bunching và ETA (tính theo thời điểm) không trả lời được.

### 8.5 Lịch chạy

```
ETL consumer commit chunk ─┬─> BunchingDetector       (mỗi micro-batch)
                           └─> DisruptionDetector     (mỗi micro-batch)
@Scheduled + ShedLock ─────┬─> EtaAggregationJob      (hàng giờ, Spring Batch)
                           ├─> OtpScorecardJob        (hàng ngày, Spring Batch)
                           └─> GtfsStaticLoadJob      (khi có feed mới, Spring Batch)
```

## 9. AI-assisted triage với Jev

### 9.1 Jev là gì và dùng ở đâu

Jev (TypeSafe AI) là mô hình trả về quyết định có kiểu — `Choice` (chọn một phương án), `Score` (thang điểm có thứ tự), `Noul` (xác suất có/không) — kèm xác suất, thay vì sinh văn bản. Nó phù hợp với phân loại, định tuyến, chấm điểm; không phù hợp với tính toán số học.

Vì vậy trong hệ thống này:

- **Không dùng Jev** cho bunching, ETA, disruption detection, OTP. Đó là phép so sánh và thống kê thuần; dùng mô hình ở đây chỉ làm chậm, tốn chi phí và khó kiểm chứng hơn.
- **Dùng Jev** cho những chỗ cần phán đoán phân loại mà luật cứng khó bao quát, theo một mẫu chung: code chuẩn bị state, Jev trả quyết định có xác suất, code áp ngưỡng và quyết định hành động.

| Use case | Pattern | Đầu ra | Hành động |
| --- | --- | --- | --- |
| DLQ triage và tự xử lý (9.2) | Triage + confidence gate | category, severity | Tự replay khi đủ tin cậy, còn lại cho người xác nhận |
| Bất thường ticketing (9.3) | Triage | category, severity | Cảnh báo theo mức độ |
| Làm giàu cảnh báo gián đoạn (9.4) | Confidence gate | Gián đoạn thật hay lỗi dữ liệu, nguyên nhân khả dĩ | Định tuyến cảnh báo đến đúng đội |
| Gợi ý điều phối khi bunching (9.5) | Real-time control | Giữ xe, bỏ trạm, hoặc không làm gì | Hiển thị gợi ý, không tự ra lệnh |

### 9.2 DLQ triage và tự xử lý theo ngưỡng

Mỗi record trong `dead_letter` được hỏi hai câu trong một request (nhiều record gom chung một lần gọi):

```java
JevResult result = jevClient.evaluate(
    "error: " + record.errorMsg() + "\npayload: " + record.rawPayload(),
    Map.of(
        "category", Question.choice("schema_violation", "referential_integrity",
                                    "upstream_api_error", "transient_network", "unknown"),
        "severity", Question.score(3)
    ));
```

Đoạn trên mang tính minh họa: Jev chỉ có SDK chính thức cho Python và JavaScript, nên phía Java dùng một client mỏng tự viết trên `java.net.http.HttpClient` (hoặc SDK Java cộng đồng), bọc Resilience4j timeout và circuit breaker.

Kết quả (`category`, `severity`, `severity_confidence`) được ghi lại vào `dead_letter`. Định tuyến cảnh báo: severity 2 → cảnh báo khẩn, severity 1 → thông báo kênh vận hành, severity 0 → chỉ ghi log.

**Tự xử lý theo ngưỡng tin cậy.** Ngưỡng nằm trong cấu hình, do code quyết định, không phải Jev:

| Điều kiện | Hành động |
| --- | --- |
| Category `transient_network` hoặc `upstream_api_error`, confidence > 0.9, nguồn đã hồi phục (health check thành công) | Tự động replay; ghi nhật ký "auto-replay" kèm xác suất |
| Confidence từ 0.5 đến 0.9 | Đưa vào hàng chờ xác nhận trên ops console, xác nhận bằng một cú bấm |
| Confidence < 0.5, hoặc category `schema_violation`, `referential_integrity`, `unknown` | Luôn để người xử lý |

Tự động hóa an toàn được vì replay là thao tác idempotent: replay nhầm một record không tạo trùng hay sai dữ liệu, tệ nhất là record quay lại DLQ. Mỗi record chỉ được auto-replay tối đa hai lần; lần thứ ba chuyển sang người xử lý.

### 9.3 Phân loại bất thường ticketing

- Bước phát hiện dùng thống kê: đếm giao dịch và hoàn vé theo điểm bán trong cửa sổ thời gian, đánh dấu cửa sổ vượt ngưỡng so với baseline.
- Bước phân loại dùng Jev: tóm tắt cửa sổ bất thường được hỏi `Choice(["fraud_suspect", "system_error", "promo_spike", "normal"])` và `Score(levels=3)`.
- Kết quả ghi vào `insight_ticketing_anomaly`.

### 9.4 Làm giàu cảnh báo gián đoạn

Disruption detector (mục 8.3) chỉ biết một tuyến đang trễ bất thường, không biết vì sao. Khi có sự kiện gián đoạn, code tóm tắt state thành văn bản: tuyến, các trạm bị ảnh hưởng, mức trễ so với baseline, sự kiện bunching gần đây, khung giờ, độ tươi của feed, số record DLQ mới của nguồn đó. Jev trả lời hai câu trong một request:

- `Noul`: đây là gián đoạn thật hay lỗi dữ liệu từ feed?
- `Choice`: nguyên nhân khả dĩ — `traffic`, `vehicle_breakdown`, `weather`, `event`, `data_issue`, `unknown`.

Nếu xác suất lỗi dữ liệu > 0.7, cảnh báo được chuyển cho đội kỹ thuật thay vì đội vận hành tuyến và không hiện lên phía hành khách. Ngược lại, cảnh báo gửi đội vận hành kèm nguyên nhân khả dĩ. Kết quả ghi vào `insight_service_disruption`.

### 9.5 Gợi ý điều phối khi có bunching

Khi phát hiện bunching (mục 8.1), code dựng state: khoảng cách giữa hai xe, headway theo lịch, vị trí trên tuyến, số trạm còn lại, lượng khách ước tính từ dữ liệu vé theo khung giờ. Jev trả `Choice`: `hold_follower` (giữ xe sau ở trạm kế tiếp), `skip_stops` (xe trước bỏ qua vài trạm vắng), hoặc `no_action`.

Gợi ý chỉ hiển thị trên live map cho người điều phối, không gửi lệnh tới xe. Gợi ý có confidence < 0.6 được hiển thị là "không đủ tin cậy". Độ trễ Jev vài trăm ms phù hợp với chu kỳ micro-batch vài giây. Kết quả ghi vào `insight_dispatch_suggestion`; người điều phối đánh dấu chấp nhận hoặc bỏ qua để làm dữ liệu đánh giá.

### 9.6 Nguyên tắc an toàn

- Jev chỉ phán đoán, code quyết định. Mọi ngưỡng nằm trong cấu hình và có thể thay đổi không cần sửa code.
- Hành động tự động chỉ áp dụng cho thao tác idempotent và có giới hạn (auto-replay tối đa hai lần mỗi record). Không tự xóa dữ liệu, không tự gửi lệnh điều phối.
- Gọi Jev là bước bất đồng bộ sau khi ghi dữ liệu; Jev lỗi hoặc timeout thì trường kết quả để `null`, luồng ETL và analytics không bị ảnh hưởng.
- Luật cứng làm chốt chặn cuối: ví dụ số lỗi `upstream_api_error` vượt N mỗi giờ thì cảnh báo khẩn bất kể Jev trả gì.
- Mọi quyết định của Jev được lưu kèm xác suất và phiên bản model (`jev-1.13.0`) để truy vết và đánh giá lại.
- Có thể tắt từng use case bằng cấu hình; hệ thống vẫn hoạt động đầy đủ, chỉ thiếu phần tự động hóa tương ứng.

## 10. Backend API

Backend Spring Boot là cửa duy nhất để frontend đọc dữ liệu; frontend không truy cập warehouse trực tiếp. API chỉ dùng role database `api_reader` (chỉ SELECT), riêng thao tác replay dùng một service account có quyền hạn hẹp.

### 10.1 Endpoint

| Nhóm | Endpoint | Mô tả |
| --- | --- | --- |
| Vận tải | `GET /routes` | Danh sách tuyến |
|  | `GET /routes/{id}/delays?from=&to=` | Độ trễ theo thời gian của tuyến |
|  | `GET /vehicles/live` | Vị trí xe hiện tại kèm ETA dự đoán |
|  | `GET /stops/{id}/arrivals` | Giờ đến dự kiến tại trạm kèm `sample_count` |
| Insight | `GET /insights/bunching?route_id=&from=&to=` | Sự kiện bunching |
|  | `GET /insights/disruption?route_id=&from=&to=` | Sự kiện gián đoạn |
|  | `GET /insights/otp?route_id=&from=&to=` | On-time performance |
|  | `GET /insights/ticketing-anomalies` | Bất thường ticketing kèm phân loại |
|  | `GET /insights/dispatch-suggestions?route_id=` | Gợi ý điều phối khi có bunching |
|  | `POST /insights/dispatch-suggestions/{id}/feedback` | Người điều phối chấp nhận hoặc bỏ qua gợi ý |
| Vận hành ETL | `GET /etl/jobs` | Lịch sử và trạng thái các batch |
|  | `GET /etl/dlq?category=&severity=&status=` | Record lỗi kèm kết quả triage |
|  | `POST /etl/dlq/{id}/replay` | Đưa record đã sửa quay lại pipeline |
|  | `POST /etl/dlq/{id}/confirm` | Xác nhận đề xuất tự xử lý trong hàng chờ |
|  | `POST /etl/replay?source=&from=&to=` | Replay một khoảng dữ liệu từ raw zone |
| Real-time | `GET /stream` (SSE) | Đẩy vị trí xe, cảnh báo, trạng thái job |

### 10.2 Quy ước

- Xác thực bằng JWT qua Spring Security (OAuth2 Resource Server); hai vai trò `viewer` (xem) và `operator` (được replay).
- Rate limit cho endpoint công khai; phân trang cho mọi endpoint trả danh sách.
- OpenAPI sinh tự động bằng springdoc-openapi; kiểu TypeScript cho frontend sinh từ OpenAPI để frontend và backend không lệch hợp đồng.
- SSE dùng SseEmitter, nhận sự kiện từ Kafka topic nội bộ để đẩy xuống client. Mọi request mang `trace_id` để nối trace với tầng ETL khi cần điều tra.

## 11. Dashboard

Dashboard React/TypeScript phục vụ hai đối tượng: người theo dõi dịch vụ (hành khách, người vận hành tuyến) và người vận hành hệ thống dữ liệu. Nó đồng thời là công cụ minh chứng trực quan cho các thực nghiệm ở mục 13.

### 11.1 Màn hình

| Màn hình | Nội dung | Phục vụ |
| --- | --- | --- |
| Live map | Vị trí xe theo thời gian thực, gom cụm khi zoom xa, xe bunching được tô nổi và gắn badge cảnh báo kèm gợi ý điều phối | Người vận hành, hành khách |
| Chi tiết trạm | Giờ đến dự kiến (lịch + độ trễ trung bình) kèm mức tin cậy theo `sample_count` | Hành khách |
| Route scorecard | Xếp hạng OTP theo tuyến, biểu đồ độ trễ theo giờ và theo ngày, lịch sử gián đoạn | Quản lý tuyến |
| Ops console | Timeline các batch ETL, số record đọc/ghi/lỗi, bảng DLQ lọc theo category/severity/trạng thái, nút replay, hàng chờ xác nhận đề xuất tự xử lý, nhật ký auto-replay, bất thường ticketing | Đội kỹ thuật |
| Alert feed | Trung tâm thông báo nhận cảnh báo disruption (kèm nguyên nhân khả dĩ) và DLQ nghiêm trọng theo thời gian thực | Tất cả |

### 11.2 Kỹ thuật

- Kết nối SSE qua hook `useRealtime`; sự kiện mới cập nhật trực tiếp vào cache của TanStack Query, không cần polling. Khi mất kết nối thì tự kết nối lại và tạm chuyển sang polling.
- Nút replay dùng optimistic update: giao diện đổi trạng thái ngay, hoàn tác nếu API trả lỗi.
- Bảng DLQ lớn dùng virtualized list.
- Kiểu dữ liệu sinh từ OpenAPI của backend.
- Trạng thái rỗng, đang tải và lỗi được thiết kế rõ ràng; khi pipeline gặp sự cố, dashboard hiển thị cảnh báo dữ liệu có thể chậm thay vì trắng trang.

### 11.3 Vai trò trong bảo vệ

Các kịch bản thực nghiệm (dừng consumer giữa chừng, bơm dữ liệu lỗi, replay) được quan sát trực tiếp trên ops console: batch chuyển sang failed, DLQ tăng, consumer phục hồi, số record khớp lại sau replay. Kịch bản này cũng được tự động hóa bằng E2E test (Playwright).

## 12. Vận hành, triển khai và chịu lỗi

### 12.1 Observability

- **Log**: JSON có cấu trúc, luôn mang `batch_id` và `trace_id`.
- **Metrics** (Micrometer → Prometheus): `records_processed_total`, `records_failed_total`, `dlq_size`, `batch_duration_seconds`, `consumer_lag`, `end_to_end_latency_seconds`, cùng metrics có sẵn của Spring Batch (`spring.batch.job`, `spring.batch.step`, `spring.batch.item.read`, `spring.batch.item.process`, `spring.batch.chunk.write`), Spring Kafka và API.
- **Trace** (Micrometer Observation → Micrometer Tracing, bridge OpenTelemetry, xuất OTLP): observation có sẵn của Spring Kafka (lan truyền `traceparent` qua header), Spring Batch (job, step), Spring MVC và JDBC; thêm observation riêng cho validate → dedup → load → analytics, nối tiếp sang request API. Không dùng thêm OpenTelemetry Java agent để tránh span trùng.
- **Dashboard Grafana** cho tình trạng pipeline; dashboard React chỉ hiển thị phần liên quan đến nghiệp vụ và DLQ.

### 12.2 Alerting

| Cảnh báo | Điều kiện | Mức |
| --- | --- | --- |
| Batch thất bại | Metric `spring.batch.job` có `status=FAILED`, hoặc micro-batch streaming FAILED trong `etl_stream_batch` | Khẩn |
| Consumer lag cao | `consumer_lag` vượt ngưỡng trong 5 phút | Cảnh báo |
| DLQ tăng nhanh | Tốc độ ghi DLQ vượt ngưỡng, hoặc có record severity 2 | Khẩn |
| Độ trễ đầu-cuối | p95 `end_to_end_latency_seconds` vượt chỉ tiêu NFR-03 | Cảnh báo |
| Throughput sụt | Số record xử lý giảm đột ngột so với baseline | Cảnh báo |
| Feed stale | Không có event GTFS-realtime mới quá 2 phút | Khẩn |
| Database là nút thắt | Độ trễ ghi vượt ngưỡng trong khi consumer lag tăng | Cảnh báo |
| Circuit breaker mở | Circuit breaker tới Postgres hoặc Jev ở trạng thái OPEN quá 1 phút | Cảnh báo |
| WAL tích tụ | Dung lượng WAL giữ lại bởi replication slot của Debezium vượt ngưỡng | Cảnh báo |

### 12.3 Bảo mật

- Secret quản lý qua Docker secrets / file `.env` không commit; có thể thay bằng Vault khi triển khai thật.
- Phân quyền database: `etl_writer` (ghi warehouse), `api_reader` (chỉ đọc), `replay_operator` (ghi hạn chế vào bảng vận hành), `debezium` (replication trên database nguồn).
- TLS giữa các service ở mức có thể bật được trong compose; JWT cho API.
- Payload gửi sang Jev không chứa thông tin cá nhân của hành khách.

### 12.4 Triển khai

Hệ thống có hai môi trường dùng chung một bộ image (build bằng Jib):

- **Dev / demo nhanh** — Docker Compose, một replica cho mỗi service, Kafka một broker. Dùng khi phát triển và khi máy demo yếu.
- **Staging / thực nghiệm** — Kubernetes cục bộ (k3d), triển khai bằng Helm chart. Dùng cho demo scale và chaos (EXP-07, EXP-08).

**Workload trên Kubernetes**

| Thành phần | Triển khai | Số replica | Ghi chú |
| --- | --- | --- | --- |
| Kafka | Strimzi, KRaft | 3 broker | replication factor 3, `min.insync.replicas = 2` |
| Kafka Connect + Debezium | Strimzi KafkaConnect | 2 worker | Connector tự chuyển sang worker còn sống |
| PostgreSQL | CloudNativePG | 1 primary + 1 replica | Tự failover; PgBouncer làm connection pooler; API đọc từ replica |
| MinIO | StatefulSet | 1 (4 nếu đủ tài nguyên) | Bật versioning |
| ETL consumer | Deployment | 1 → số partition | Tự scale theo consumer lag (KEDA) |
| Batch và scheduler | Deployment | 2 | Chỉ một pod kích hoạt job nhờ ShedLock; Spring Batch chặn chạy song song cùng một JobInstance |
| Triage worker | Deployment | 0 → 3 | Scale theo số record DLQ chưa triage; bị giới hạn bởi quota Jev |
| API | Deployment | 2 → 6 | HPA theo CPU và độ trễ request |
| Frontend | Deployment (Nginx) | 2 | Static file |
| Observability | kube-prometheus-stack | — | Prometheus, Grafana, Alertmanager |

**Quy ước cho mọi service Spring Boot**

- **Probe**: startup, liveness và readiness qua Actuator health groups. Readiness chỉ phản ánh khả năng phục vụ của chính pod (ví dụ API mất kết nối replica thì readiness = false); liveness không phụ thuộc hệ thống ngoài để tránh restart dây chuyền.
- **Tài nguyên**: khai báo requests/limits; JVM đặt `-XX:MaxRAMPercentage=75`.
- **Tắt mềm**: `server.shutdown=graceful`; consumer nhận SIGTERM thì dừng poll, hoàn tất chunk đang xử lý, commit offset, rời consumer group rồi mới thoát.
- **PodDisruptionBudget** cho API, ETL consumer, Kafka, Postgres để nâng cấp node không làm gián đoạn.
- **Cấu hình và secret**: ConfigMap, Secret (Sealed Secrets); NetworkPolicy chỉ mở đúng luồng cần thiết.

**Migration và nâng cấp**

- Flyway chạy như Kubernetes Job trong Helm pre-upgrade hook, không chạy trong từng pod.
- Thay đổi schema theo kiểu expand/contract: thêm cột hoặc bảng trước, triển khai code dùng cấu trúc mới, xóa cấu trúc cũ ở phiên bản sau. Nhờ vậy rolling update không làm lỗi pod phiên bản cũ đang chạy song song.
- Rolling update với `maxUnavailable = 0` cho API; với ETL consumer, rebalance dùng `CooperativeStickyAssignor` để giảm thời gian dừng.

### 12.5 CI/CD

GitHub Actions với Gradle: Spotless/Checkstyle → unit test → integration test (Testcontainers) → contract test → quét bảo mật (SpotBugs, OWASP Dependency-Check, Trivy) → build image bằng Jib → E2E trên compose → publish image → helm upgrade lên cluster staging (k3d) → smoke test.

### 12.6 Backup và khôi phục

- MinIO bật versioning, là nguồn sự thật để dựng lại warehouse.
- `pg_dump` warehouse định kỳ.
- Kịch bản khôi phục: dựng warehouse trống → chạy migration → replay từ raw zone; tính đúng đắn của kịch bản này chính là một thực nghiệm ở mục 13.

### 12.7 Mở rộng theo tải

Mọi service đều stateless; trạng thái nằm ở Kafka, PostgreSQL và MinIO. Vì vậy mở rộng chủ yếu là thêm pod, với ba giới hạn cần thiết kế trước: số partition, sức chứa database và quota dịch vụ ngoài.

**Tầng streaming**

- Đơn vị song song là partition. Topic `gtfs.*` tạo sẵn 12 partition, key là `route_id` để mọi event của một tuyến đi vào cùng một consumer, giữ đúng thứ tự cho bunching và disruption. Tối đa 12 consumer pod có ích.
- KEDA Kafka scaler: `minReplicaCount = 1`, `maxReplicaCount = 12`, ngưỡng lag khoảng 500 message mỗi partition, có cooldown để tránh scale lên xuống liên tục.
- Tuyến quá đông có thể tạo partition nóng. Cách xử lý: chọn số partition dư từ đầu, theo dõi lag theo từng partition, và nếu cần thì tách key thành `route_id + direction`.

**Không scale mù khi database là nút thắt**

Khi Postgres chậm, consumer tự pause, lag tăng; nếu KEDA chỉ nhìn lag thì sẽ thêm pod và làm database quá tải hơn. Vì vậy:

- `maxReplicaCount × kích thước pool mỗi pod` không vượt quá giới hạn kết nối của PgBouncer.
- KEDA kết hợp thêm một trigger Prometheus: chỉ cho scale lên khi độ trễ ghi của database dưới ngưỡng; ngược lại giữ nguyên số pod và phát cảnh báo "database là nút thắt".
- Ghi theo batch upsert (JdbcTemplate batch) và chunk cố định để giữ số round-trip thấp.

**Tầng lưu trữ**

- Bảng fact partition theo `service_date` (declarative partitioning của Postgres); xóa hoặc lưu trữ partition cũ theo chính sách retention.
- API đọc từ replica; endpoint insight có cache ngắn hạn (Caffeine, TTL vài giây) vì dữ liệu cập nhật theo micro-batch.

**Tầng API và real-time**

- HPA theo CPU và p95 latency, từ 2 đến 6 pod.
- SSE fan-out: mỗi pod API đọc topic sự kiện nội bộ bằng consumer group riêng của pod, nên client kết nối vào pod nào cũng nhận đủ sự kiện; không cần sticky session.

**Dịch vụ ngoài**

- Triage worker có rate limiter (Resilience4j) khớp với quota Jev; khi DLQ tăng đột biến, số pod tăng nhưng tổng tốc độ gọi Jev không vượt quota, phần dư chờ lượt sau.

**Mục tiêu tải cho thực nghiệm (EXP-07)**

Tải nền mô phỏng 500 xe, mỗi xe gửi vị trí 5 giây một lần (khoảng 100 event/giây). Thực nghiệm tăng dần đến 10 lần tải nền và đo: số consumer pod, consumer lag, độ trễ đầu-cuối p95, thời gian hệ thống trở về ổn định sau khi tải giảm. Các con số này là mục tiêu thiết kế, sẽ được hiệu chỉnh theo tài nguyên máy chạy thực nghiệm.

### 12.8 Chịu lỗi

Nguyên tắc chung: lỗi dữ liệu và lỗi hạ tầng được xử lý khác nhau. Record sai đi vào DLQ để batch chạy tiếp; còn khi hạ tầng (database, Kafka, dịch vụ ngoài) gặp sự cố thì hệ thống dừng tiến lên, không commit offset, không đẩy dữ liệu đúng vào DLQ, và tự tiếp tục khi hạ tầng hồi phục. Không sự cố đơn lẻ nào được phép gây mất hoặc trùng dữ liệu.

**Sự cố của thành phần nội bộ**

| Sự cố | Phát hiện | Hành vi hệ thống | Phục hồi |
| --- | --- | --- | --- |
| Pod ETL consumer bị kill hoặc crash | Kubernetes, consumer group rebalance | Partition được chuyển cho consumer khác; chunk đang dở chưa commit | Tiếp tục từ offset đã commit; upsert chống trùng |
| PostgreSQL primary chết | CloudNativePG, lỗi kết nối | Consumer retry có backoff rồi mở circuit breaker và pause listener; API vẫn đọc từ replica, endpoint ghi (replay) trả 503 | Failover sang replica; circuit breaker thử lại, consumer resume |
| Một Kafka broker chết | Strimzi, metric under-replicated | Vẫn đọc ghi bình thường nhờ RF 3, min ISR 2; producer dùng `acks=all` và idempotent producer | Broker quay lại tự đồng bộ |
| Kafka Connect / Debezium dừng | Trạng thái connector, lag replication slot | Không có event ticketing mới; thay đổi vẫn nằm trong WAL nhờ replication slot | Connector chạy lại từ LSN đã lưu, không mất thay đổi; cảnh báo khi WAL tích tụ quá ngưỡng |
| MinIO không khả dụng | Lỗi S3 sink connector | Đường ETL chính không bị ảnh hưởng; raw zone tạm chậm | Kafka retention (7 ngày) làm vùng đệm; sink tự bắt kịp |
| Pod batch chết giữa job | `BATCH_STEP_EXECUTION.LAST_UPDATED` không đổi quá ngưỡng trong khi khóa ShedLock đã hết hạn | Bước khôi phục đánh dấu execution FAILED (tăng `VERSION`, nên pod cũ nếu còn sống không commit được chunk nữa) | Pod còn lại nhận khóa, `JobOperator.restart` từ execution context |
| Pod API chết | Readiness, Service | Traffic chuyển sang pod khác | Client SSE tự kết nối lại với `Last-Event-ID` |
| Poison message (lỗi lặp lại mãi) | Phân loại lỗi non-retryable | Không retry vô hạn: vào DLQ ngay, offset tiến tiếp | Sửa và replay từ DLQ |
| Lỗi logic làm sai dữ liệu hàng loạt | Rule chất lượng, alert | Tạm dừng consumer bằng feature flag | Sửa code, replay khoảng thời gian bị ảnh hưởng từ raw zone (EXP-04) |

**Sự cố của dịch vụ và nguồn bên ngoài**

| Sự cố | Phát hiện | Hành vi hệ thống | Phục hồi |
| --- | --- | --- | --- |
| Jev chậm, lỗi hoặc hết quota | Timeout 2 giây, circuit breaker | Bulkhead riêng nên không ảnh hưởng ETL; record giữ `category = null`, hiển thị "chưa phân loại"; luật cứng vẫn cảnh báo | Worker thử lại khi circuit breaker đóng |
| Nguồn GTFS-realtime ngừng phát | Metric độ tươi dữ liệu (thời gian từ event cuối) | Cảnh báo "feed stale"; dashboard hiện dữ liệu cũ kèm thời điểm cập nhật cuối | Tự tiếp tục khi nguồn phát lại |
| Event đến trễ hoặc sai thứ tự | So `event_timestamp` | Upsert chỉ ghi đè khi event mới hơn bản đang có | Không cần can thiệp |
| Feed GTFS static mới bị hỏng | Validate toàn bộ feed trước khi áp dụng | Nạp vào bảng staging; lỗi thì giữ nguyên phiên bản cũ | Đổi phiên bản trong một transaction khi feed hợp lệ |
| Database nguồn ticketing down | Trạng thái Debezium connector | Không có dữ liệu vé mới, phần còn lại chạy bình thường | Debezium tự kết nối lại và đọc tiếp |
| Kênh gửi cảnh báo (Slack, email) lỗi | Alertmanager | Alertmanager retry; alert feed trên dashboard vẫn hoạt động | Tự gửi bù khi kênh hồi phục |

**Các pattern dùng chung** (Spring Batch, Spring Kafka, Resilience4j): timeout cho mọi lời gọi ra ngoài, retry có jitter chỉ cho lỗi tạm thời (retry của Spring Batch và `DefaultErrorHandler` của Spring Kafka), circuit breaker, bulkhead tách luồng gọi Jev, rate limiter theo quota, idempotent producer, commit offset sau transaction, và graceful degradation ở API và dashboard.

## 13. Kiểm thử và đánh giá

### 13.1 Chiến lược kiểm thử

| Loại | Phạm vi | Công cụ |
| --- | --- | --- |
| Unit | Hàm transform, validation, dedup, thuật toán analytics | JUnit 5, AssertJ, Mockito, Vitest |
| Integration | Consumer với Kafka và Postgres thật | Testcontainers |
| Contract | Schema GTFS-realtime, schema CDC của Debezium, hợp đồng OpenAPI giữa API và frontend | JSON Schema, Spring Cloud Contract |
| Data quality | Bộ rule chất lượng cho từng bảng fact | SQL assertion chạy bằng JUnit và trong pipeline |
| E2E | Từ simulator đến dashboard, kể cả kịch bản sự cố | Playwright trên Docker Compose |

### 13.2 Thực nghiệm dùng để trả lời câu hỏi nghiên cứu

Mỗi thực nghiệm chạy lặp nhiều lần, ghi lại số liệu và so sánh với một baseline không có cơ chế tương ứng.

| Mã | Thực nghiệm | Cách làm | Chỉ số | Kỳ vọng |
| --- | --- | --- | --- | --- |
| EXP-01 | Dừng đột ngột | Kill consumer tại thời điểm ngẫu nhiên trong lúc xử lý chunk, khởi động lại | Tỷ lệ mất dữ liệu, tỷ lệ trùng, thời gian phục hồi | Mất = 0, trùng = 0 |
| EXP-02 | Gửi lại message | Simulator gửi lại X% message đã gửi | Số bản ghi trùng tại warehouse | 0 |
| EXP-03 | Cô lập lỗi | Bơm 1%, 5%, 20% message lỗi vào luồng | Tỷ lệ record hợp lệ được nạp, so với baseline "một lỗi làm fail cả batch" | 100% record hợp lệ được nạp |
| EXP-04 | Replay toàn bộ | Xóa warehouse, replay từ raw zone | Mức khớp giữa kết quả replay và kết quả gốc (số dòng, checksum theo bảng) | Khớp hoàn toàn |
| EXP-05 | Chịu tải | Tăng dần tốc độ event từ simulator | Throughput, consumer lag, độ trễ đầu-cuối p95 | Xác định ngưỡng đáp ứng NFR-03 |
| EXP-07 | Mở rộng theo tải | Trên k3d, tăng tải từ 1 đến 10 lần tải nền rồi giảm về | Số pod, consumer lag, độ trễ đầu-cuối p95, thời gian ổn định lại | Đạt NFR-03 trong giới hạn tài nguyên |
| EXP-08 | Chaos | Khi đang có tải: kill pod consumer và API, tắt một Kafka broker, failover Postgres, chặn hoặc làm chậm mạng tới Jev (Chaos Mesh, Toxiproxy) | Mất và trùng dữ liệu, thời gian phục hồi, cảnh báo phát ra đúng | Mất = 0, trùng = 0, tự phục hồi |
| EXP-06 (tùy chọn) | Chất lượng quyết định của Jev | Gán nhãn tay tập mẫu cho từng use case (DLQ, gián đoạn thật hay lỗi dữ liệu, gợi ý điều phối); so sánh Jev với bộ luật | Tỷ lệ tự động hóa ở precision ≥ 95%, calibration, độ trễ, chi phí | Báo cáo so sánh, không đặt kỳ vọng trước |

Kết quả EXP-01 đến EXP-04 là bằng chứng trực tiếp cho tính đúng đắn của pipeline; EXP-05 và EXP-07 cho biết giới hạn hiệu năng và khả năng mở rộng; EXP-08 kiểm chứng khả năng chịu lỗi khi triển khai; EXP-06 đánh giá giá trị thực tế của tầng AI thay vì chỉ khẳng định.

## 14. Kế hoạch triển khai

Thứ tự ưu tiên đi theo trọng tâm nghiên cứu: làm chắc ETL trước, rồi mới mở rộng. Mỗi giai đoạn kết thúc bằng một kết quả chạy được.

| Giai đoạn | Nội dung | Kết quả đầu ra |
| --- | --- | --- |
| 1. Nền tảng | Docker Compose (Postgres, Kafka, Kafka Connect, MinIO), Flyway migration cho toàn bộ schema, nạp GTFS static, source simulator cơ bản | `docker compose up` chạy được; dimension có dữ liệu; event xuất hiện trên Kafka |
| 2. ETL cốt lõi | Cấu hình Spring Batch (JobRepository JDBC, fault-tolerant step, skip/retry, ShedLock) và `StreamChunkTemplate` cho Spring Kafka; consumer GTFS-realtime và ticketing (Debezium), validate, dedup, load theo chunk, DLQ, offset/checkpoint, audit, S3 sink sang raw zone | Dữ liệu vào warehouse; restart không trùng, không mất |
| 3. Thực nghiệm độ tin cậy | Kịch bản điều khiển trong simulator, script chạy EXP-01 đến EXP-05, observability và alerting | Bảng số liệu thực nghiệm; dashboard Grafana |
| 4. Analytics và API | Bunching, disruption, ETA, OTP; Spring Boot API với REST và SSE | Endpoint trả insight thật từ dữ liệu simulator |
| 5. Dashboard | Live map, chi tiết trạm, scorecard, ops console, alert feed | Giao diện hoàn chỉnh, kết nối real-time |
| 6. AI triage | Worker Jev: triage và tự xử lý DLQ theo ngưỡng, ticketing anomaly, làm giàu cảnh báo gián đoạn, gợi ý điều phối bunching; luật chặn cuối; EXP-06 | Nhãn triage, hàng chờ xác nhận, cảnh báo có nguyên nhân, gợi ý điều phối trên dashboard |
| 7. Triển khai và chịu lỗi | Helm chart trên k3d (Strimzi, CloudNativePG, KEDA); probe, graceful shutdown, PodDisruptionBudget; circuit breaker, bulkhead, rate limiter; chạy EXP-07, EXP-08 | Hệ thống tự scale và tự phục hồi trên k3d; số liệu scale và chaos |
| 8. Hoàn thiện | E2E test, CI/CD, tài liệu, kịch bản demo, báo cáo | Sẵn sàng bảo vệ |

Nếu thiếu thời gian, thứ tự cắt giảm là: gợi ý điều phối bunching → EXP-06 → ticketing anomaly → OTP scorecard → môi trường Kubernetes (khi đó EXP-08 chạy trên Docker Compose bằng cách dừng container). Giai đoạn 1–3 không được cắt vì là phần trả lời câu hỏi nghiên cứu.

### 14.1 Kịch bản demo

1. Khởi động hệ thống bằng một lệnh; dashboard hiển thị xe chạy theo thời gian thực.
2. Bật chế độ bunching trong simulator → cảnh báo và gợi ý điều phối xuất hiện trên live map.
3. Bật chế độ disruption trên một tuyến → alert feed báo gián đoạn kèm nguyên nhân khả dĩ, scorecard phản ánh.
4. Bơm dữ liệu lỗi → DLQ tăng, record được phân loại, batch vẫn chạy tiếp; record lỗi mạng tạm thời được tự replay khi nguồn hồi phục.
5. Kill consumer giữa chừng → ops console hiển thị batch failed → consumer tự phục hồi → đối chiếu số dòng không trùng.
6. Sửa một record trong DLQ và replay → dữ liệu xuất hiện trong warehouse.
7. Trên k3d, tăng tải simulator → số consumer pod tăng theo lag; tắt một Kafka broker và failover Postgres → hệ thống tiếp tục chạy, đối chiếu không mất, không trùng dữ liệu.

## 15. Rủi ro và giới hạn

| Rủi ro / giới hạn | Ảnh hưởng | Cách xử lý |
| --- | --- | --- |
| Dữ liệu từ simulator không phản ánh đầy đủ hành vi thật | Kết quả analytics có thể quá "đẹp" | Dùng GTFS static thật làm khung; đưa nhiễu và độ trễ có phân phối vào simulator; nêu rõ giới hạn trong báo cáo |
| Phạm vi rộng so với thời gian đồ án | Không kịp hoàn thiện | Thứ tự ưu tiên và cắt giảm ở mục 14 |
| Tài nguyên máy khi chạy toàn bộ stack | Compose nặng, khó demo trên laptop | Kafka single-node KRaft, giới hạn bộ nhớ từng container và heap JVM, gộp các service Spring Boot nhẹ khi cần, profile compose tách phần tùy chọn (observability, triage) |
| Jev trả kết quả sai hoặc thiếu hiệu chỉnh | Phân loại lỗi không chính xác | Chỉ dùng cho gợi ý, không tự động hành động; có luật chặn cuối; hiển thị confidence; EXP-06 đo thực tế |
| Phụ thuộc dịch vụ bên ngoài (Jev API) | Mất mạng hoặc hết quota khi demo | Tầng triage tắt được; hệ thống vẫn đầy đủ chức năng khi không có Jev |
| Ngưỡng analytics (bunching, z-score, OTP) chọn theo kinh nghiệm | Có thể báo động nhầm hoặc bỏ sót | Đưa ngưỡng vào cấu hình; trình bày độ nhạy theo ngưỡng trong báo cáo |
| Effectively-once phụ thuộc business key đúng | Chọn khóa sai sẽ gây trùng hoặc ghi đè | Contract test cho business key; EXP-02 kiểm chứng |
| Hiểu sai ngữ nghĩa transaction của Spring Batch (thời điểm gọi `SkipListener`, scan mode, restart) hoặc khác biệt giữa các phiên bản lớn | Có thể ghi DLQ ngoài transaction hoặc restart sai vị trí | Chốt phiên bản ở S-06; test tiêm lỗi cho từng ranh giới transaction trước khi viết job thật; kiểm chứng bằng EXP-01, EXP-02 |
| Streaming không chạy trên Spring Batch nên thuật toán chunk có hai hiện thực | Hai chế độ có thể lệch hành vi | `StreamChunkTemplate` giữ ở mức tối thiểu và dùng chung processor, writer, phân loại lỗi, DLQ; một bộ test hợp đồng chạy trên cả hai chế độ |
| Cluster Kubernetes cục bộ nặng (3 Kafka broker, 2 Postgres instance) | Máy không đủ tài nguyên cho EXP-07, EXP-08 | Values Helm rút gọn cho demo (1 broker, 1 Postgres); chạy thực nghiệm trên máy mạnh hơn hoặc VM cloud thuê tạm; EXP-08 có phương án dự phòng trên Docker Compose |

## Phụ lục: Cấu trúc repo

```
public-transport-intelligence/          # Gradle multi-module
  settings.gradle.kts
  build-logic/                          # convention plugin: Java 21, Spotless, SpotBugs, Jib
  common/                               # DTO, schema theo schema_version, business key, tiện ích chung
  source-simulator/                     # Spring Boot app
    GtfsRealtimeProducer.java
    TicketingSeeder.java
    scenario/                           # Bunching, Disruption, BadData, Duplicates, TicketSpike, LoadRamp
  etl/                                  # Spring Boot app: Spring Batch + Spring Kafka
    consumer/                           # GtfsRealtimeListener, TicketingCdcListener, StreamChunkTemplate
    batch/                              # cấu hình Spring Batch, GtfsStaticLoadJob, DlqReplayJob, RawZoneReplayJob,
                                        #   PartitionMaintenanceJob, cleanup, khôi phục execution kẹt
    core/                               # ItemProcessor, UpsertWriter (JdbcBatchItemWriter), DeadLetterWriter,
                                        #   DeadLetterSkipListener, ErrorClassifier, Deduplicator
    quality/                            # rule chất lượng (SQL assertion)
    resilience/                         # DefaultErrorHandler, circuit breaker, ShedLock
  analytics/
    BunchingDetector.java
    DisruptionDetector.java
    EtaAggregationJob.java
    OtpScorecardJob.java
  triage/
    JevClient.java                      # HttpClient + Resilience4j (timeout, circuit breaker, bulkhead, rate limiter)
    DlqClassifier.java
    TicketingAnomalyClassifier.java
  api/                                  # Spring Boot app: controller, security, SSE
  frontend/                             # React + TypeScript (Vite)
    src/
      features/
        live-map/
        stop-detail/
        scorecard/
        ops-console/
        alerts/
      hooks/useRealtime.ts
      api/                              # client sinh từ OpenAPI
  connect/                              # cấu hình Debezium và S3 sink connector
  db/migration/                         # Flyway
  observability/                        # prometheus, alert rules, grafana dashboards
  deploy/
    compose/                            # docker-compose.yml cho dev
    k3d/                                # cấu hình cluster cục bộ
    helm/pti/                           # Helm chart: values-dev.yaml, values-staging.yaml, values-lite.yaml
    operators/                          # Strimzi, CloudNativePG, KEDA, Chaos Mesh
  chaos/                                # kịch bản Chaos Mesh và Toxiproxy cho EXP-08
  experiments/                          # runner EXP-01 đến EXP-08 và phân tích kết quả
  .github/workflows/ci.yml
```

Test nằm trong từng module (`src/test/java`: unit và integration với Testcontainers); E2E Playwright nằm trong `frontend/e2e`.
