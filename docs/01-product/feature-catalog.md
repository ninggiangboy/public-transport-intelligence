# Danh mục tính năng

> Trạng thái: **Review** · Cập nhật: 2026-09-26 · DOC-05
> Phụ thuộc: [DOC-03](requirements.md), [DOC-04](use-cases.md), [Master plan §4–5](../00-master-plan.md), [DR](../00-decision-register.md)

## 0. Quy ước

- Mã tính năng: `F-<NHÓM>-<số>`. Có 11 nhóm: INGEST, ETL, DLQ, REPLAY, ANL (analytics), AI, API, UI, SIM, OPS.
- **Phase** là phase *hoàn thành* tính năng. Nếu một tính năng làm dần qua nhiều phase thì ghi dạng `P1→P3`.
- **Cờ**: key cấu hình (`pti.*`, cần restart) hoặc `runtime_flag` (có hiệu lực ngay). `—` nghĩa là tính năng luôn bật.
- **Cắt ở bước**: vị trí trong thứ tự cắt giảm dự phòng (DOC-01 §9). Cột này chỉ dùng khi một milestone trễ quá 50%. `—` nghĩa là không được cắt.
- Nhiều tính năng cùng chạy trong app `etl`. Cột **App** ghi đơn vị triển khai (DR-26).

## 1. Tổng quan

| Nhóm | Số tính năng | Phase chính | App |
| --- | --- | --- | --- |
| INGEST | 6 | P1–P2 | simulator, Kafka Connect, etl |
| ETL | 11 | P2 | etl |
| DLQ | 4 | P2, P5 | etl, api |
| REPLAY | 3 | P2 | etl |
| ANL | 6 | P4 | etl (thư viện `analytics`) |
| AI | 7 | P6 | triage-worker, etl |
| API | 5 | P4 | api |
| UI | 8 | P5 | frontend |
| SIM | 4 | P1, P3 | source-simulator |
| OPS | 5 | P3, P4 | observability, api, etl |

---

## 2. INGEST: thu thập dữ liệu

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-INGEST-01 | Nạp GTFS static | `GtfsStaticLoadJob`: lấy zip, lưu raw zone, staging, validate, swap phiên bản (DR-10). Chạy 03:30 và chạy tay | FR-01.1, FR-04.2 | UC-14 | P2 | M | `pti.gtfs.static.source`, `pti.gtfs.static.cron` | F-ETL-01, F-ETL-09 | — |
| F-INGEST-02 | Consumer GTFS-realtime | Batch listener cho `gtfs.vehicle_positions` và `gtfs.trip_updates`, parser theo `schema_version` 1 và 2 | FR-01.2, FR-01.5 | UC-01 | P2 | M | `runtime_flag etl.consumer.gtfs-rt.paused` | F-ETL-02 | — |
| F-INGEST-03 | CDC ticketing | Debezium (pgoutput) → `ticketing.sales.cdc`, `ticketing.sale_points.cdc`; consumer upsert có guard theo LSN; DELETE → `is_deleted` | FR-01.3 | — | P1→P2 | M | `runtime_flag etl.consumer.ticketing.paused` | F-SIM-02 | — |
| F-INGEST-04 | Raw zone | Kafka Connect S3 sink ghi mọi topic nguồn vào bucket `raw` (JSON gzip, theo giờ, giữ key và headers); zip GTFS lưu vào `raw/gtfs-static/` | FR-01.4 | UC-18 | P1 | M | — | — | — |
| F-INGEST-05 | Tiến hóa schema | Nhận song song v1 và v2 của VehiclePosition; version lạ vào DLQ `SCHEMA` (DR-59) | FR-01.5 | — | P2 | M | — | F-INGEST-02 | — |
| F-INGEST-06 | Tự đăng ký xe | Nạp `dim_vehicle` từ `vehicles.txt`; `vehicle_id` lạ từ realtime được tạo với `source=REALTIME` | FR-01.6 | — | P2 | S | — | F-INGEST-01 | — |

## 3. ETL: xử lý và nạp

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-ETL-01 | Hạ tầng Spring Batch | JobRepository JDBC (schema `batch`), `JobOperator`, `ExecutionContext` dạng JSON, ShedLock, `StaleExecutionRecoverer`, `BatchIdStepListener` (DR-24, 62, 63) | FR-03.6 | UC-07 | P2 | M | `pti.batch.stale-after` | — | — |
| F-ETL-02 | `StreamChunkTemplate` | Thuật toán chunk cho streaming: process → ghi batch → scan bằng savepoint → DLQ, trong một transaction; ghi `etl_stream_batch` | FR-02.5, FR-03.5 | — | P2 | M | — | — | — |
| F-ETL-03 | Validator | Bean Validation và business rule (route/stop/trip trong feed ACTIVE, lệch thời gian ±1 giờ, `amount ≥ 0`, refund có gốc) | FR-02.2, FR-02.3 | — | P2 | M | — | F-ETL-09 | — |
| F-ETL-04 | Rule DQ pre-write | Not-null, trùng key trong chunk (giữ event mới hơn), bbox của feed; vi phạm vào DLQ `QUALITY` | FR-02.4 | — | P2 | M | — | F-ETL-03 | — |
| F-ETL-05 | Phân loại lỗi và skip policy | `ErrorClassifier` DATA / TRANSIENT_INFRA / FATAL; `SkipPolicy` theo tỷ lệ; retry có backoff; pause container khi hạ tầng lỗi | FR-02.5, FR-02.7, FR-02.8 | — | P2 | M | `pti.etl.batch.max-skip-ratio` | — | — |
| F-ETL-06 | Upsert idempotent | `JdbcBatchItemWriter` upsert theo business key, guard theo `event_timestamp` hoặc LSN | FR-03.1, FR-03.2 | — | P2 | M | — | — | — |
| F-ETL-07 | Dedup registry | Bỏ qua sớm message gửi lại y hệt, tăng `records_duplicate_total`; bị bỏ qua khi replay; TTL 1 giờ (DR-16) | FR-03.3, FR-03.4 | — | P2 | M | `pti.etl.dedup.enabled`, `pti.etl.dedup.ttl` | F-ETL-06 | — |
| F-ETL-08 | Offset sau commit và restart | `AckMode.BATCH` sau khi transaction commit; job batch restart từ chunk cuối đã commit | FR-03.5, FR-03.6 | UC-17 | P2 | M | — | F-ETL-01, F-ETL-02 | — |
| F-ETL-09 | Mô hình warehouse | Migration Flyway: dimension, bảng lịch GTFS, fact partitioned, `vehicle_position_latest`, `route_headway` | FR-04.1 | — | P1→P2 | M | — | — | — |
| F-ETL-10 | Bảo trì partition và dọn dẹp | `PartitionMaintenanceJob` (tạo trước 7 ngày, drop theo retention), dọn `dedup_registry`, `etl_stream_batch`, metadata `BATCH_*` | FR-04.3 | — | P2 | M | `pti.retention.*` | F-ETL-01 | — |
| F-ETL-11 | Truy vết `batch_id` và DQ post-write | `batch_id` trên mọi dòng fact, DLQ, log; rule post-write ghi `dq_check_result` và phát alert | FR-04.4 | UC-07 | P2 | M | — | F-ETL-01 | — |

## 4. DLQ

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-DLQ-01 | Ghi DLQ | `DeadLetterWriter` ghi `dead_letter` trong transaction của chunk: payload gốc (đã loại PII), stage, lỗi, vị trí Kafka, `batch_id` | FR-02.1, FR-02.6 | UC-08 | P2 | M | — | F-ETL-05 | — |
| F-DLQ-02 | Tra cứu và lọc DLQ | API keyset theo source, stage, status, category, severity, khoảng thời gian | FR-12.1 | UC-08 | P4 | M | — | F-API-01 | — |
| F-DLQ-03 | Sửa payload | Editor trên UI; server validate theo JSON Schema trước khi lưu `edited_payload`; ghi `dlq_action_log` | FR-12.1 | UC-08 | P4→P5 | M | — | F-DLQ-02 | — |
| F-DLQ-04 | Thao tác trên record | Replay, discard (bắt buộc lý do), confirm và reject đề xuất; máy trạng thái DR-18; `Idempotency-Key` | FR-12.1, FR-09.3 | UC-08, UC-09 | P4→P5 | M | — | F-REPLAY-01 | — |

## 5. REPLAY

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-REPLAY-01 | Replay record DLQ | `DlqReplayJob` đọc `dead_letter` có status `REPLAY_REQUESTED`, chạy qua cùng processor và writer, bỏ qua dedup | FR-12.1, FR-03.4 | UC-08 | P2 | M | — | F-DLQ-01 | — |
| F-REPLAY-02 | Replay raw zone | `RawZoneReplayJob` đọc object trong raw zone theo khoảng giờ, restart được; tùy chọn tính lại analytics; mỗi nguồn một replay RUNNING | FR-12.2, FR-12.3, FR-12.5 | UC-10, UC-18 | P2 | M | — | F-INGEST-04 | — |
| F-REPLAY-03 | Replay bằng reset offset | Runbook RB-10: dừng consumer, `kafka-consumer-groups --reset-offsets`, chạy lại | FR-12.4 | — | P3 | S | — | — | — |

## 6. ANL: analytics

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-ANL-01 | Phát hiện bunching | Episode theo `(route, direction, cặp xe)`; gap so với headway theo `shape_dist_traveled`; mở < 0,5, đóng > 0,7 (DR-30) | FR-05 | UC-01, UC-04 | P4 | M | `pti.analytics.bunching.*` | F-ETL-09 | — |
| F-ANL-02 | Phát hiện gián đoạn | EWMA theo `(route, direction)`, bucket 1 phút, z-score có hysteresis, warm-up 60 bucket (DR-31) | FR-07 | UC-03, UC-05 | P4 | M | `pti.analytics.disruption.*` | F-ETL-06 | — |
| F-ANL-03 | ETA lịch sử | Job hằng giờ tính lại trên cửa sổ 28 ngày từ arrival `is_observed`, kèm mức tin cậy (DR-32) | FR-06.1, FR-06.2 | UC-02 | P4 | M | `pti.analytics.eta.*` | F-ETL-06 | — |
| F-ANL-04 | OTP scorecard | Job 03:00 tính OTP ±300 giây cho hôm qua và tính lại 2 ngày trước đó (DR-33) | FR-08 | UC-06 | P4 | M | `pti.analytics.otp.*` | F-ETL-06 | Bước 4 |
| F-ANL-05 | Phát hiện bất thường ticketing | So sánh thống kê theo điểm bán và cửa sổ (DR-34); kết quả được AI phân loại (F-AI-03) | FR-09.4 | UC-12 | P4 | S | `pti.analytics.ticketing.*` | F-INGEST-03 | Bước 3 |
| F-ANL-06 | ETA kết hợp realtime | Thêm `realtimeArrival` khi chuyến có TripUpdate mới hơn 2 phút | FR-06.3 | UC-02 | P4 | C | `pti.analytics.eta.realtime-enabled` | F-ANL-03 | Không làm nếu P4 trễ |

## 7. AI: phán đoán bằng Jev

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AI-01 | Cổng `DecisionModel` | Interface cùng adapter `jev`, `fake`, `disabled`; Resilience4j (timeout 2 giây, circuit breaker, bulkhead 8, rate limiter); lưu `model_version` | FR-09.7, FR-09.9 | — | P6 | M | `pti.triage.provider` | — | — |
| F-AI-02 | Triage DLQ | `triage-worker` lấy record `NEW` bằng SKIP LOCKED, phân loại category và severity kèm confidence | FR-09.1 | UC-13 | P6 | M | `runtime_flag triage.dlq.enabled` | F-AI-01, F-DLQ-01 | — |
| F-AI-03 | Phân loại bất thường ticketing | Gán category `fraud_suspect`, `system_error`, `promo_spike` hoặc `normal` cho kết quả của F-ANL-05 | FR-09.4 | UC-12 | P6 | S | `runtime_flag triage.ticketing.enabled` | F-AI-01, F-ANL-05 | Bước 3 |
| F-AI-04 | Làm giàu disruption | `data_issue_probability` và `likely_cause`; chuyển audience sang ENGINEERING khi > 0,7 | FR-09.5 | UC-05 | P6 | M | `runtime_flag triage.disruption.enabled` | F-AI-01, F-ANL-02 | — |
| F-AI-05 | Gợi ý điều phối | Gợi ý hành động cho mỗi episode bunching kèm confidence; lưu phản hồi của operator | FR-09.6 | UC-04 | P6 | S | `runtime_flag triage.dispatch.enabled` | F-AI-01, F-ANL-01 | **Bước 1** |
| F-AI-06 | Auto-replay có ngưỡng | Bảng quyết định do code sở hữu (ADR-0019): category, confidence > 0,9, nguồn UP ≥ 60 giây, tối đa 2 lần; hàng chờ xác nhận cho 0,5–0,9; ghi `dlq_action_log` | FR-09.2, FR-09.3 | UC-09, UC-13 | P6 | M | `runtime_flag triage.auto-replay.enabled` | F-AI-02, F-REPLAY-01 | — |
| F-AI-07 | Luật chặn và bảo vệ PII | Luật chặn cuối (`upstream_api_error` > N/giờ → alert khẩn); blocklist trường PII khi dựng state | FR-09.8, FR-09.10 | UC-13 | P6 | M | `pti.triage.guard.*` | F-AI-01 | — |

## 8. API

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-API-01 | REST đọc dữ liệu | Endpoint tuyến, trạm, xe, arrivals, insight, jobs, DLQ; phân trang keyset; `X-Data-As-Of`; cache Caffeine | FR-10.1, FR-10.3, FR-10.6 | Tất cả | P4 | M | — | F-ETL-09 | — |
| F-API-02 | REST thao tác | Feedback, ack alert, thao tác DLQ, tạo replay, cờ vận hành, restart job; ghi qua datasource `replay_operator` (DR-20) | FR-10.1 | UC-04, 08–11 | P4 | M | — | F-API-01 | — |
| F-API-03 | Xác thực và phân quyền | Keycloak realm `pti`, resource server JWT; anonymous / viewer / operator | FR-10.2 | Tất cả | P4 | M | — | — | — |
| F-API-04 | SSE real-time | `/stream` với các kênh, ring buffer, `Last-Event-ID`, `resync`, heartbeat 15 giây | FR-10.4 | UC-01, 03, 04, 07, 08 | P4 | M | — | F-API-01 | — |
| F-API-05 | Bảo vệ API | Rate limit theo IP cho endpoint public (Bucket4j), Problem Details (RFC 9457), OpenAPI xuất khi build | FR-10.5 | — | P4 | S | `pti.api.rate-limit.*` | F-API-01 | — |

## 9. UI: dashboard

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-UI-01 | Live map | MapLibre + PMTiles; xe real-time, gom cụm, lọc tuyến; ở chế độ operator có tô nổi bunching và gợi ý | FR-11.1 | UC-01, UC-04 | P5 | M | — | F-API-04 | — |
| F-UI-02 | Stop detail | Arrivals kèm mức tin cậy, banner disruption, tìm trạm; ưu tiên mobile | FR-11.2 | UC-02, UC-03 | P5 | M | — | F-API-01 | — |
| F-UI-03 | Route scorecard | Xếp hạng OTP, heatmap delay theo giờ × thứ, lịch sử disruption | FR-11.3 | UC-06 | P5 | M | — | F-ANL-04 | Bước 4 |
| F-UI-04 | Ops console: Jobs | Timeline micro-batch, danh sách job batch, chi tiết step, link trace/log, restart | FR-11.4 | UC-07 | P5 | M | — | F-API-01 | — |
| F-UI-05 | Ops console: DLQ và Replay | Bảng DLQ virtualized, chi tiết, sửa payload, replay, discard, confirm queue, nhật ký auto-replay, form replay raw zone | FR-11.4 | UC-08–10 | P5 | M | — | F-DLQ-04, F-REPLAY-02 | — |
| F-UI-06 | Ops console: Controls và Ticketing | Switch cờ vận hành, bảng bất thường ticketing | FR-11.4 | UC-11, UC-12 | P5 | M | — | F-OPS-04 | Bước 3 (phần ticketing) |
| F-UI-07 | Alert feed | Danh sách alert theo audience, toast, ack | FR-11.5 | UC-03, 04, 05 | P5 | M | — | F-OPS-03 | — |
| F-UI-08 | Demo control và trạng thái lỗi | Màn điều khiển kịch bản (profile `demo`); stale banner, trạng thái lỗi và rỗng trên mọi màn hình | FR-11.6, FR-11.7 | UC-16 | P5 | S (demo) / M (trạng thái) | profile `demo` | F-SIM-03 | — |

## 10. SIM: simulator

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SIM-01 | Phát GTFS-realtime | Nội suy vị trí theo `stop_times` và `shape`, mô hình trễ, ánh xạ ngày (DR-08); VehiclePosition 5 giây, TripUpdate 30 giây | FR-13.1 | — | P1 | M | `pti.sim.*` | — | — |
| F-SIM-02 | Sinh giao dịch vé | Ghi vào DB `ticketing_source` theo tốc độ theo giờ, có tỷ lệ hoàn vé và void | FR-13.2 | — | P1 | M | `pti.sim.ticketing.*` | — | — |
| F-SIM-03 | Kịch bản điều khiển được | REST: `bunching`, `disruption`, `bad-data`, `duplicates`, `ticket-spike`, `refund-burst`, `load-ramp` | FR-13.3 | UC-16 | P3 | M | — | F-SIM-01, F-SIM-02 | — |
| F-SIM-04 | Ledger | Schema `sim`: mỗi message đã được ack một dòng, có `intended_invalid` và `is_resend` (DR-28) | FR-13.4 | UC-17 | P1 | M | — | F-SIM-01 | — |

## 11. OPS: vận hành và quan sát

| ID | Tính năng | Mô tả | FR | UC | Phase | MoSCoW | Cờ | Phụ thuộc | Cắt ở bước |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-OPS-01 | Metrics, trace, log | Micrometer → Prometheus; Micrometer Tracing → OTLP → Tempo; log JSON → Alloy → Loki; `batch_id` và `trace_id` trong log | NFR-05 | UC-07 | P3 | M | — | — | — |
| F-OPS-02 | Alert hạ tầng | 9 rule PromQL (SDD §12.2), Alertmanager → Mailpit và webhook về API; mỗi rule có runbook | FR-14.1, FR-14.3 | UC-15 | P3 | M | — | F-OPS-01 | — |
| F-OPS-03 | Alert hợp nhất | Bảng `alert_event` có `dedup_key`, định tuyến theo audience; nguồn gồm analytics, triage, Alertmanager | FR-14.2 | UC-03, 05, 15 | P4 | M | — | — | — |
| F-OPS-04 | Cờ vận hành | Bảng `runtime_flag`, đọc mỗi 5 giây; pause/resume consumer; bật/tắt từng use case AI | FR-15.1 | UC-11 | P4 | M | — | — | — |
| F-OPS-05 | Độ tươi dữ liệu | Gauge `feed_freshness_seconds` theo nguồn, `GET /system/freshness`, stale banner | FR-15.2 | UC-15 | P4 | M | — | F-OPS-01 | — |

---

## 12. Thứ tự cắt giảm, ánh xạ sang tính năng

| Bước | Cắt | Tính năng bị ảnh hưởng |
| --- | --- | --- |
| 1 | Gợi ý điều phối | F-AI-05; phần gợi ý trong F-UI-01 |
| 2 | EXP-06 | Không cắt tính năng nào; chỉ bỏ thực nghiệm |
| 3 | Bất thường ticketing | F-ANL-05, F-AI-03; phần ticketing trong F-UI-06 |
| 4 | OTP | F-ANL-04, F-UI-03 (còn lại biểu đồ delay nếu kịp) |
| 5 | k3d | Không cắt tính năng nào; EXP-07 bỏ, EXP-08 chuyển sang compose |
