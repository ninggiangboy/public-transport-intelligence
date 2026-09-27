# ADR-0014: Đơn vị triển khai: một image ETL hai profile, analytics là thư viện

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-26, DR-35, ADR-0002, DOC-07, SDD §12.4

## Bối cảnh

SDD §12.4 tách workload thành ETL consumer (scale theo lag), batch/scheduler (2 pod), triage worker và API. Analytics micro-batch (bunching, disruption) cần chạy ngay sau khi chunk commit, với độ trễ thấp (DOC-10 §2). Làm một mình nên số artifact cần build và vận hành càng ít càng tốt. Máy dev có 16 GB RAM.

## Các phương án

1. **Mỗi chức năng một service** (consumer GTFS-rt, consumer CDC, batch, analytics, triage, API). Tách biệt tốt, nhưng nhiều image, nhiều JVM (RAM), và analytics phải nhận sự kiện qua Kafka nên tăng độ trễ.
2. **Một monolith** chạy mọi thứ. Không scale riêng consumer được; scheduler chạy trên mọi replica.
3. **Một image `etl` với hai profile (`stream`, `batch`)**, analytics là thư viện nhúng vào `etl`; `triage-worker`, `api`, `source-simulator` là app riêng.

## Quyết định

Chọn **phương án 3**.

- Gradle module: `build-logic`, `common`, `analytics` (thư viện), `etl` (app), `triage-worker` (app), `api` (app), `source-simulator` (app), `db` (Flyway runner). Không có module `engine`.
- `etl` profile `stream`: Kafka listener, `StreamChunkTemplate`, analytics micro-batch qua `MicroBatchCommitted` (in-process, AFTER_COMMIT), publisher sự kiện UI. **Không** bật `@Scheduled`.
- `etl` profile `batch`: Spring Batch, `@Scheduled` + ShedLock, job batch và replay, job analytics theo lịch (ETA, OTP, ticketing), xử lý `job_request`/`replay_request`.
- Cả hai profile: `spring.batch.job.enabled=false`. Bật cả hai cùng lúc (`stream,batch`) là cấu hình hợp lệ cho chế độ `lite` (DOC-10 §5).
- `analytics` không phụ thuộc Spring Kafka hay Spring Batch; chỉ nhận `DataSource`/`JdbcClient` và tham số. Nhờ vậy analytics test được độc lập và chạy lại được từ job replay.
- `api` không phụ thuộc `etl` hay `analytics`; chỉ dùng chung `common` (DTO, hằng số).
- Ranh giới module được kiểm tra bằng ArchUnit.

## Hệ quả

- Hai image Java cho ETL được thay bằng một image, bớt thời gian build CI.
- Scale độc lập: `etl-stream` 1→4 pod (KEDA; 12 partition, 3 thread mỗi pod, DOC-40 §9.1), `etl-batch` cố định 2 pod.
- Analytics micro-batch dùng chung JVM với consumer, nên cần executor riêng có giới hạn (bulkhead) để không làm chậm consumer; lỗi analytics chỉ log và tăng metric (DR-35).
- Muốn tách analytics ra service riêng về sau thì chỉ cần đổi cơ chế kích hoạt (in-process → Kafka), không phải viết lại logic.
