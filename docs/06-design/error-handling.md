# Xử lý lỗi

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-30
> Phụ thuộc: [ADR-0006](../04-adr/0006-error-classification.md), [DOC-19](batch-and-chunk-processing.md), [DOC-20](etl-streaming.md), [DOC-22](dlq-and-replay.md), [DR](../00-decision-register.md) (DR-23, 39, 45, 61, 69)
> Người dùng chính: mọi module Java (P2-02, P4-10…), DOC-31/32 (API), DOC-28 (log, metric)

Tài liệu gồm bốn phần: (1) hệ exception của dự án; (2) bảng phân loại lỗi `DATA` / `TRANSIENT_INFRA` / `FATAL` dùng cho ETL và các worker; (3) ánh xạ lỗi của API sang HTTP status và Problem Details (RFC 9457); (4) quy ước log lỗi. Mọi thông báo lỗi (message của exception, `detail` của Problem, `error_message` của DLQ) viết bằng tiếng Anh.

## 1. Hệ exception

Package `dev.pti.common.error` (module `common`):

```java
public abstract sealed class PtiException extends RuntimeException
    permits DataException, TransientInfraException, FatalException, ApiException { … }

/** A problem with one record. Skipped and dead-lettered; never retried. */
public non-sealed class DataException extends PtiException {
  public DataException(DlqStage stage, @Nullable String ruleId, String message, @Nullable Throwable cause) { … }
  public DlqStage stage();
  public @Nullable String ruleId();
}
//   DeserializationException   stage DESERIALIZE
//   SchemaViolationException   stage SCHEMA, ruleId DQ-01, carries List<FieldViolation>
//   RuleViolationException     stage QUALITY | BUSINESS | DEDUP, ruleId DQ-xx
//   RawZoneLineException       stage DESERIALIZE (DOC-22)
//   GtfsRowException           GTFS static row error, collected into validation_report (DOC-21)

/** The record is fine, the infrastructure is not. Retried; never dead-lettered. */
public non-sealed class TransientInfraException extends PtiException { … }

/** A bug or misconfiguration. Stops the step or the listener container. */
public non-sealed class FatalException extends PtiException { … }
//   ConfigurationException, InvariantViolationException

/** API-only: carries the Problem type (section 3). */
public abstract non-sealed class ApiException extends PtiException {
  public abstract ProblemType type();
}
```

Quy tắc:

- Code nghiệp vụ ném exception **của dự án** khi biết rõ loại lỗi. Exception của thư viện (Jackson, JDBC, Kafka) được để nguyên và `ErrorClassifier` phân loại (§2).
- Không bắt `Exception` chung rồi nuốt. Chỗ nào bắt thì phải hoặc xử lý hẳn (skip vào DLQ, trả Problem), hoặc gói lại thành exception của dự án kèm `cause`.
- `DataException` không cần stack trace: constructor gọi `super(message, cause, false, false)` (tắt stack trace và suppression). Pipeline có thể tạo hàng nghìn exception mỗi giây ở kịch bản `bad-data`, nên tắt stack trace tiết kiệm đáng kể CPU.

## 2. Phân loại lỗi (ETL, triage-worker, simulator)

### 2.1 Thuật toán `ErrorClassifier`

`ErrorClassifier implements Classifier<Throwable, ErrorKind>`, bean trong `common`. Duyệt chuỗi `cause` từ ngoài vào trong (tối đa 10 tầng) và dừng ở luật khớp đầu tiên theo thứ tự các bảng dưới đây. Không khớp luật nào thì trả `FATAL` (ADR-0006).

### 2.2 Exception của dự án và của thư viện

| Thứ tự | Exception (kể cả lớp con) | Loại |
| --- | --- | --- |
| 1 | `DataException` | `DATA` |
| 2 | `TransientInfraException` | `TRANSIENT_INFRA` |
| 3 | `FatalException` | `FATAL` |
| 4 | `SkipLimitExceededException`, `OptimisticLockingFailureException` (của `JobRepository`) | `FATAL` (step phải dừng; restart xử lý tiếp) |
| 5 | `java.sql.SQLException` hoặc `DataAccessException` có `SQLException` bên trong | Theo SQLState, §2.3 |
| 6 | `CannotGetJdbcConnectionException`, `TransientDataAccessResourceException`, `QueryTimeoutException`, `RecoverableDataAccessException`, `CannotAcquireLockException`, `PessimisticLockingFailureException` | `TRANSIENT_INFRA` |
| 7 | `DataIntegrityViolationException`, `EmptyResultDataAccessException` không có SQLState | `DATA` |
| 8 | `io.github.resilience4j.circuitbreaker.CallNotPermittedException` | `TRANSIENT_INFRA` (DOC-20 §5.2) |
| 9 | `org.apache.kafka.common.errors.RetriableException`, `org.apache.kafka.common.errors.TimeoutException`, `org.springframework.kafka.KafkaException` bọc một trong hai | `TRANSIENT_INFRA` |
| 10 | `software.amazon.awssdk.core.exception.SdkClientException` (lỗi mạng), `S3Exception` với HTTP 5xx hoặc 429 | `TRANSIENT_INFRA` |
| 11 | `S3Exception` với HTTP 4xx khác (`NoSuchKey`, `AccessDenied`) | `FATAL` |
| 12 | `tools.jackson.core.JacksonException` / `com.fasterxml.jackson.core.JacksonException`, `jakarta.validation.ConstraintViolationException`, `java.nio.charset.CharacterCodingException`, `java.time.format.DateTimeParseException`, `NumberFormatException` | `DATA` |
| 13 | `java.net.SocketTimeoutException`, `java.net.ConnectException`, `java.io.InterruptedIOException` | `TRANSIENT_INFRA` |
| 14 | Mọi thứ khác (`NullPointerException`, `IllegalStateException`, `ClassCastException`…) | `FATAL` |

Luật 12 chỉ đúng khi exception xảy ra **trong processor** (đang đọc dữ liệu vào). Jackson ném lỗi trong writer hay khi đọc cấu hình là lỗi lập trình. Vì vậy processor bọc mọi lỗi parse thành `DeserializationException`/`SchemaViolationException` ngay tại chỗ, và `ErrorClassifier` chỉ áp luật 12 cho exception **không** đi qua writer (xác định bằng `ClassificationContext.phase` ∈ {`READ`, `PROCESS`, `WRITE`, `OTHER`}). Ở `WRITE` và `OTHER`, luật 12 trả `FATAL`.

### 2.3 SQLState (PostgreSQL)

| SQLState | Tên | Loại | Ghi chú |
| --- | --- | --- | --- |
| `08000`, `08001`, `08003`, `08004`, `08006`, `08007`, `08P01` | connection exception | `TRANSIENT_INFRA` | Postgres restart, mạng |
| `40001` | serialization_failure | `TRANSIENT_INFRA` | |
| `40P01` | deadlock_detected | `TRANSIENT_INFRA` | Hiếm nhờ thứ tự ghi cố định (DOC-19 §4.3) |
| `53000`, `53100`, `53200`, `53300` | insufficient resources, disk full, out of memory, too many connections | `TRANSIENT_INFRA` | `53100` (disk full) kéo dài sẽ lộ qua alert lag và alert disk |
| `55P03` | lock_not_available | `TRANSIENT_INFRA` | `lock_timeout` |
| `57014` | query_canceled | `TRANSIENT_INFRA` | `statement_timeout` |
| `57P01`, `57P02`, `57P03` | admin_shutdown, crash_shutdown, cannot_connect_now | `TRANSIENT_INFRA` | |
| `58000`, `58030` | system_error, io_error | `TRANSIENT_INFRA` | |
| `22xxx` | data exception (`22001` chuỗi quá dài, `22003` tràn số, `22007` ngày sai, `22P02` sai kiểu…) | `DATA` | |
| `23xxx` | integrity constraint (`23502` NOT NULL, `23503` FK, `23505` UNIQUE, `23514` CHECK, `23P01` EXCLUSION) | `DATA` | Riêng `23505` trên index không liên quan dữ liệu (ví dụ `replay_request_one_raw_per_source` trong API) được xử lý tại chỗ, §3.3 |
| `21000` | cardinality_violation | `FATAL` | `ON CONFLICT DO UPDATE` chạm một dòng hai lần: lỗi khử trùng trong chunk (DOC-16 §2.1) |
| `25P02` | in_failed_sql_transaction | `FATAL` | Lỗi lập trình: dùng tiếp transaction đã hỏng |
| `42xxx` | syntax error, undefined table/column, `42501` insufficient_privilege | `FATAL` | Sai migration hoặc grant |
| `0A000` | feature_not_supported | `FATAL` | |
| `P0001` | raise_exception | `DATA` | Chỉ từ function của dự án kiểm tra dữ liệu |
| khác | | `FATAL` | |

### 2.4 Hành động theo loại

| Loại | Job batch | Streaming | triage-worker | source-simulator |
| --- | --- | --- | --- | --- |
| `DATA` | Skip (`RatioSkipPolicy`), `DeadLetterSkipListener` → DLQ | Skip trong `StreamChunkTemplate` → DLQ | Dòng DLQ/insight bị đánh dấu lỗi xử lý, `triage_attempts + 1` (DOC-24) | Không áp dụng (simulator sinh dữ liệu) |
| `TRANSIENT_INFRA` | Retry chunk: 5 lần, backoff 1 s × 2 có jitter, tổng ≤ 60 s → step `FAILED` | Seek + backoff 1 → 30 s vô hạn + pause (DOC-20 §5) | Nhả lease, thử lại sau (DOC-24) | Producer tự retry (`delivery.timeout.ms`); lỗi cuối cùng thì không ghi ledger, tăng `pti_sim_send_errors_total` |
| `FATAL` | Step `FAILED`, alert `BatchJobFailed` | Dừng container, alert `ConsumerStopped` | Dừng worker loop, readiness `DOWN`, alert | Dừng kịch bản, log `ERROR` |

## 3. API: HTTP status và Problem Details

### 3.1 Định dạng

Mọi lỗi của `api` trả `application/problem+json` (DR-39):

```json
{
  "type": "urn:pti:problem:replay-already-running",
  "title": "Replay already running",
  "status": 409,
  "detail": "A raw-zone replay for GTFS_RT_VEHICLE_POSITION is already pending or running.",
  "instance": "/api/v1/etl/replays",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "existingReplayId": "0192f5a1-2b3c-7d4e-8f90-a1b2c3d4e5f6"
}
```

- `type` là URN `urn:pti:problem:<slug>`. Dùng URN thay vì URL để không phụ thuộc tên miền; danh sách slug ở §3.2 là hợp đồng với frontend, và frontend chọn microcopy theo slug (DOC-35).
- `title` cố định theo `type`. `detail` cụ thể cho lần lỗi, **không** chứa SQL, tên lớp Java, stack trace, hay dữ liệu người dùng khác.
- `traceId` luôn có (cùng giá trị với header `X-Trace-Id`).
- Thành phần mở rộng tùy loại: `errors` (lỗi theo trường), `existingReplayId`, `retryAfterSeconds`, `currentStatus`.
- Lỗi theo trường:
  ```json
  "errors": [{"field": "toTs", "message": "must be at least 10 minutes in the past"}]
  ```
  `field` dùng tên camelCase của request (DR-39); lỗi trong payload JSON dùng JSON Pointer, ví dụ `/payload/route_id`.

### 3.2 Danh mục Problem type

| Slug | HTTP | `title` | Khi nào |
| --- | --- | --- | --- |
| `validation-error` | 400 | Invalid request | Tham số query/path sai kiểu hoặc thiếu; body không đọc được; vi phạm Bean Validation của DTO request |
| `unauthorized` | 401 | Authentication required | Thiếu token, token hết hạn hoặc sai chữ ký. Header `WWW-Authenticate: Bearer` |
| `forbidden` | 403 | Access denied | Có token nhưng thiếu role (`viewer`, `operator`) |
| `not-found` | 404 | Resource not found | Id không tồn tại, hoặc path không có |
| `method-not-allowed` | 405 | Method not allowed | |
| `not-acceptable` | 406 | Not acceptable | |
| `conflict` | 409 | Conflict | Xung đột trạng thái chung (UPDATE có điều kiện trạng thái cập nhật 0 dòng) |
| `dlq-invalid-state` | 409 | Dead letter in wrong state | Thao tác không hợp lệ với trạng thái hiện tại (DOC-15 §4.3); có `currentStatus` |
| `replay-already-running` | 409 | Replay already running | `replay_request_one_raw_per_source` hoặc `replay_request_one_per_record` (FR-12.3); có `existingReplayId` |
| `job-not-restartable` | 409 | Job cannot be restarted | Restart execution không ở `FAILED`/`STOPPED` hoặc job không restart được |
| `job-not-running` | 409 | Job is not running | Yêu cầu dừng execution không ở `STARTING`/`STARTED` (DOC-32 E-36) |
| `payload-too-large` | 413 | Payload too large | Body > 1 MiB |
| `unsupported-media-type` | 415 | Unsupported media type | |
| `invalid-payload` | 422 | Invalid payload | Sửa payload DLQ không qua kiểm tra schema (DOC-22 §2); có `errors` |
| `pii-not-allowed` | 422 | Personal data not allowed | Payload chứa khóa trong blocklist |
| `business-key-changed` | 422 | Business key cannot change | DOC-22 §2 |
| `replay-window-invalid` | 422 | Invalid replay window | Vi phạm quy tắc thời gian của DOC-22 §4.1; có `errors` |
| `unsupported-source` | 422 | Unsupported source | Ví dụ `RAW_RANGE` cho `GTFS_STATIC` |
| `analytics-recompute-unavailable` | 422 | Analytics recompute unavailable | DOC-22 §4.1, trước P4 |
| `idempotency-key-reused` | 422 | Idempotency key reused | Cùng `Idempotency-Key` nhưng body khác lần đầu |
| `job-not-allowed` | 422 | Job cannot be started manually | `jobName` hoặc tham số ngoài danh sách cho phép (DOC-32 E-33) |
| `invalid-flag-value` | 422 | Invalid flag value | Kiểu JSON của `value` khác kiểu hiện tại của cờ, hoặc ngoài miền cho phép (DOC-32 E-57) |
| `rate-limited` | 429 | Too many requests | DR-45; header `Retry-After`, thành phần `retryAfterSeconds` |
| `internal-error` | 500 | Internal error | Lỗi không lường trước. `detail` chung chung: `An unexpected error occurred. Quote the trace id when reporting.` |
| `simulator-unavailable` | 502 | Simulator unavailable | Proxy `/sim/**` không kết nối được hoặc simulator không trả lời kịp (DOC-32 E-90, chỉ profile `demo`) |
| `service-unavailable` | 503 | Service temporarily unavailable | DB hoặc dependency lỗi tạm thời (`TRANSIENT_INFRA`); header `Retry-After: 5` |

Thêm slug mới là thay đổi hợp đồng: cập nhật bảng này, `ProblemType` enum và microcopy (DOC-35) trong cùng PR.

### 3.3 Ánh xạ exception → Problem

`ApiExceptionHandler` (`@RestControllerAdvice`, kế thừa `ResponseEntityExceptionHandler`, `spring.mvc.problemdetails.enabled=true`):

| Exception | Problem |
| --- | --- |
| `ApiException` (của dự án: `NotFoundException`, `InvalidStateException`, `ReplayAlreadyRunningException`, `InvalidPayloadException`, …) | `type()` của exception |
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `HttpMessageNotReadableException` | `validation-error`, kèm `errors` |
| `NoResourceFoundException`, `NoHandlerFoundException` | `not-found` |
| `HttpRequestMethodNotSupportedException`, `HttpMediaTypeNotAcceptableException`, `HttpMediaTypeNotSupportedException`, `MaxUploadSizeExceededException` | 405 / 406 / 415 / 413 tương ứng |
| `AuthenticationException` (qua `AuthenticationEntryPoint` của Spring Security) | `unauthorized` |
| `AccessDeniedException` (qua `AccessDeniedHandler`) | `forbidden` |
| `DuplicateKeyException` / `DataIntegrityViolationException` có tên constraint trong `ConstraintProblemMap` | Slug theo map, ví dụ `replay_request_one_raw_per_source` → `replay-already-running` |
| `DataIntegrityViolationException` khác | `conflict` (log `WARN`: constraint chưa được map là thiếu sót cần sửa) |
| `RequestNotPermitted` (Bucket4j/Resilience4j rate limiter) | `rate-limited` |
| Exception mà `ErrorClassifier` xếp `TRANSIENT_INFRA` | `service-unavailable` |
| Mọi thứ khác | `internal-error` |

- `ConstraintProblemMap` là map tường minh từ tên constraint sang slug, nằm cạnh repository ghi dữ liệu; tên constraint lấy từ `PSQLException.getServerErrorMessage().getConstraint()`.
- Với `Idempotency-Key` đã dùng: cùng body thì trả **lại response cũ** (201/202 với bản ghi cũ), không phải lỗi; khác body thì `idempotency-key-reused`.
- Lỗi xảy ra khi đang stream SSE (response đã commit) không trả Problem được: server đóng kết nối, client kết nối lại theo DR-41.

### 3.4 Lỗi ở frontend

Frontend đọc `type` để chọn thông báo (DOC-35); không hiển thị `detail` thô, trừ `errors` theo trường trong form. Mất mạng hoặc response không phải `problem+json` (proxy trả HTML) được coi là `service-unavailable` phía client.

## 4. Quy ước log lỗi

### 4.1 Định dạng

- Log JSON có cấu trúc bằng structured logging của Spring Boot, định dạng ECS (`logging.structured.format.console=ecs`).
- Trường chuẩn của ECS: `@timestamp`, `log.level`, `log.logger`, `message`, `error.type`, `error.message`, `error.stack_trace`, `trace.id`, `span.id`, `service.name`.
- Trường riêng (qua MDC hoặc `StructuredLoggingJsonMembersCustomizer`): `pti.batch_id`, `pti.source`, `pti.job`, `pti.listener`, `pti.error_kind`, `pti.stage`, `pti.rule_id`, `pti.replay_request_id`, `pti.problem_type`.

### 4.2 Quy tắc

1. **Log một lần, ở nơi xử lý.** Không "log rồi ném lại". Nơi bắt và quyết định (skip, retry, trả Problem) mới log.
2. **Mức log theo loại lỗi:**

| Tình huống | Mức | Stack trace |
| --- | --- | --- |
| `DATA`: từng record | `DEBUG` | Không |
| `DATA`: tổng kết chunk có skip | `INFO` (`Chunk committed with 3 skipped records`) | Không |
| `TRANSIENT_INFRA`: mỗi lần thử lại | `WARN` kèm `attempt`, `next_backoff_ms` | Không (chỉ `error.type`, `error.message`) |
| `TRANSIENT_INFRA`: hết lượt retry (job batch) | `ERROR` | Có |
| `FATAL` | `ERROR` | Có |
| API 4xx | `DEBUG` (401/403: `INFO` không kèm token, để kiểm tra truy cập) | Không |
| API 5xx | `ERROR` | Có |
| Analytics, sự kiện UI thất bại (best-effort) | `WARN` | Không |

3. **Không bao giờ log:** token, mật khẩu, API key, header `Authorization`, giá trị của khóa trong `pti.pii.blocklist`. Payload message chỉ log ở `DEBUG` và sau khi qua `PiiScrubber` (DOC-18 §4).
4. **Message tiếng Anh, bắt đầu bằng chữ hoa, không có dấu chấm cuối**, nêu **cái gì** và **ở đâu**: `Failed to write chunk to warehouse`, `Listener container stopped after fatal error`.
5. Exception được log bằng tham số cuối (`log.error("…", ex)`), không nối `ex.getMessage()` vào chuỗi.
6. Bộ lặp: lỗi giống nhau lặp nhiều lần (ví dụ Postgres chết) được giới hạn bằng `RateLimitedLogger` (tối đa 1 dòng mỗi 10 giây cho mỗi `(logger, error.type)`, kèm số lần bị bỏ qua).

## 5. Metrics

| Metric | Loại | Label | App |
| --- | --- | --- | --- |
| `pti_errors_total` | counter | `app`, `kind` (`data` \| `transient_infra` \| `fatal`), `type` (tên lớp exception rút gọn) | etl, triage-worker, source-simulator |
| `pti_api_problems_total` | counter | `type` (slug), `status` | api |
| `http_server_requests_seconds` | timer | `status`, `uri`, `outcome` | api (Micrometer) |

Alert liên quan ở DOC-28: `ApiErrorRateHigh` (5xx > 1% trong 5 phút), `FatalErrors` (`pti_errors_total{kind="fatal"}` tăng).

## 6. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E-01 | Test tham số hóa `ErrorClassifier` với mọi dòng của §2.2 và §2.3 (tạo `PSQLException` với SQLState tương ứng, bọc qua `DataAccessException` và `KafkaException`) | Đúng loại |
| E-02 | Jackson exception ở phase `WRITE` | `FATAL` |
| E-03 | Chuỗi cause sâu 12 tầng | Không lặp vô hạn; kết quả `FATAL` nếu không khớp trong 10 tầng đầu |
| E-04 | `DataException` không có stack trace | `getStackTrace().length == 0` |
| E-05 | Mỗi slug §3.2 có ít nhất một test MockMvc | `Content-Type: application/problem+json`, đúng `type`, `status`, `title`, có `traceId` |
| E-06 | Endpoint ném `NullPointerException` | 500 `internal-error`, body không chứa tên lớp hay stack trace; log `ERROR` có stack trace và `trace.id` |
| E-07 | Postgres dừng khi gọi API | 503 `service-unavailable`, `Retry-After: 5` |
| E-08 | Tạo replay thứ hai cùng nguồn | 409 `replay-already-running`, có `existingReplayId` |
| E-09 | Log của kịch bản `bad-data` 20% ở `INFO` | Không có dòng nào chứa payload; số dòng log ≤ số chunk + số lần đổi trạng thái |
| E-10 | `RateLimitedLogger` khi Postgres chết 60 giây | ≤ 7 dòng `WARN` cho mỗi `(logger, error.type)` |

## 7. Câu hỏi còn mở

Không có.
