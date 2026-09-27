# Luồng dữ liệu

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-08
> Phụ thuộc: [DOC-07](system-context-and-containers.md), [DOC-09](messaging-contracts.md), [DOC-19](../06-design/batch-and-chunk-processing.md), [DOC-20](../06-design/etl-streaming.md), [DOC-21](../06-design/etl-gtfs-static.md), [DOC-22](../06-design/dlq-and-replay.md), [ADR-0003](../04-adr/0003-effectively-once-upsert.md), [ADR-0004](../04-adr/0004-offset-commit-after-transaction.md), [ADR-0013](../04-adr/0013-replay-request-api-etl-executes.md), [DR](../00-decision-register.md) (DR-35, 37, 38, 41, 42, 57)
> Người dùng chính: mọi người đọc cần hình dung hệ thống chạy thế nào trước khi vào tài liệu thiết kế chi tiết

Mỗi mục là một sequence diagram cho một luồng, kèm vài ghi chú về điểm quan trọng và link tới tài liệu chi tiết. Tài liệu này **không** định nghĩa hành vi mới: khi có khác biệt, tài liệu thiết kế được dẫn là nguồn đúng.

Ký hiệu chung: `PG-W` = Postgres warehouse (`pti_warehouse`), `PG-S` = Postgres nguồn (`ticketing_source`, `pti_sim`), `S3` = SeaweedFS bucket `raw`.

## 1. Nạp GTFS-realtime

```mermaid
sequenceDiagram
  autonumber
  participant SIM as source-simulator
  participant K as Kafka
  participant C as Kafka Connect (S3 sink)
  participant S3
  participant ES as etl-stream
  participant PGW as PG-W
  SIM->>K: send VP/TU (key = route_id, acks=all, idempotent)
  K-->>SIM: ack (partition, offset)
  SIM->>SIM: ghi sim_ledger (sau ack, DR-28)
  par Raw zone
    C->>K: poll (group connect-pti-raw-sink)
    C->>S3: PUT raw/#lt;topic#gt;/dt=/hh=/…json.gz (rotate ≤ 5 phút)
  and Warehouse
    ES->>K: poll ≤ 500 records (group pti-etl-gtfs-rt)
    ES->>ES: process (thuần, ngoài transaction): parse, schema, DQ theo record
    ES->>PGW: BEGIN#59; DLQ#59; dim_vehicle placeholder#59; upsert fact + latest#59; etl_stream_batch#59; COMMIT
    ES->>K: commitSync offsets (AckMode.BATCH)
    ES-)ES: MicroBatchCommitted → analytics (§4), vehicles.batch (§9)
  end
```

- Offset chỉ commit sau khi transaction commit (bước 7 → 8, ADR-0004). Lỗi dữ liệu vào DLQ trong cùng transaction; lỗi hạ tầng làm cả poll thử lại (§10).
- Raw zone và warehouse là hai consumer group độc lập: warehouse chết không ảnh hưởng raw zone, và ngược lại.
- Chi tiết: DOC-20 §3–4, DOC-19 §6.

## 2. Nạp CDC ticketing

```mermaid
sequenceDiagram
  autonumber
  participant SIM as source-simulator
  participant PGS as PG-S (ticketing_source)
  participant DBZ as Debezium (Kafka Connect)
  participant K as Kafka
  participant ES as etl-stream
  participant PGW as PG-W
  SIM->>PGS: INSERT/UPDATE/DELETE ticket_transaction, sale_point
  PGS-->>DBZ: WAL qua slot pti_ticketing (pgoutput)
  DBZ->>K: ticketing.sales.cdc / ticketing.sale_points.cdc (unwrap, __op, __lsn)
  DBZ->>PGS: heartbeat mỗi 10 s (giữ slot tiến lên)
  ES->>K: poll (group pti-etl-ticketing)
  ES->>ES: bỏ customer_ref khi đọc JSON#59; DQ-10, 11
  ES->>PGW: BEGIN#59; DQ-02/12/13 (đọc fact gốc của refund)#59; INFERRED sale point#59; upsert guard LSN#59; COMMIT
  ES->>K: commitSync offsets
```

- Guard theo `__lsn`: event cũ không ghi đè event mới (FR-03.2). Delete thành `is_deleted = true`.
- Refund tới trước giao dịch gốc trong 5 phút vẫn được ghi; DQ-22 kiểm lại sau (DOC-16 §2.3).
- Chi tiết: DOC-09 §5, DOC-20 §4.4.

## 3. Nạp GTFS static

```mermaid
sequenceDiagram
  autonumber
  participant T as Trigger (startup / cron / job_request)
  participant EB as etl-batch (GtfsStaticLoadJob)
  participant F as Nguồn feed (file / S3 / https)
  participant S3
  participant PGW as PG-W
  participant ES as etl-stream
  T->>EB: JobOperator.start(runKey, sourceUri)
  EB->>F: tải zip, tính SHA-256
  EB->>PGW: tra feed_hash
  alt đã có
    EB-->>T: exit NOOP
  else mới
    EB->>S3: PUT raw/gtfs-static/#lt;hash#gt;.zip
    EB->>PGW: INSERT gtfs_feed_version (STAGED)
    loop 8 file, chunk 500–1.000
      EB->>PGW: INSERT dòng lịch (feed_version_id = STAGED)#59; commit + ExecutionContext
    end
    EB->>PGW: validate GV-04…15
    alt có lỗi
      EB->>PGW: status = REJECTED, validation_report
    else hợp lệ
      EB->>PGW: finalize (bbox, valid range, route_headway)
      EB->>PGW: activate: RETIRED bản cũ, ACTIVE bản mới (một transaction)
      EB->>PGW: upsert dim_vehicle#59; retire (giữ 3 bản)
      ES->>PGW: (≤ 30 s) phát hiện feed ACTIVE mới, nạp ReferenceData
    end
  end
```

- Người đọc không bao giờ thấy trạng thái "không có feed ACTIVE" (DOC-14 §4).
- Pod chết giữa chừng: restart tiếp từ chunk đã commit (FR-03.6).
- Chi tiết: DOC-21.

## 4. Analytics micro-batch

```mermaid
sequenceDiagram
  autonumber
  participant ES as etl-stream (consumer thread)
  participant AD as AnalyticsDispatcher (executor riêng)
  participant PGW as PG-W
  participant K as Kafka (pti.events.ui)
  ES->>ES: chunk commit xong
  ES-)AD: MicroBatchCommitted(routeIds, event range) — @Async
  ES->>ES: return → commit offset (không chờ analytics)
  AD->>AD: gom yêu cầu theo route (coalesce)
  AD->>PGW: đọc vị trí và TripUpdate gần nhất của các route
  AD->>PGW: BEGIN#59; upsert episode (UUIDv5), alert_event#59; COMMIT
  AD-)K: bunching.opened / disruption.opened / alert.created (best-effort, sau commit)
  Note over AD: tick 30 s đóng episode của route không còn dữ liệu mới (DR-35)
```

- Analytics lỗi không ảnh hưởng ETL: chỉ log và tăng metric. Insight idempotent nên chạy lại cho cùng dữ liệu không sinh bản ghi mới (ADR-0010).
- Chi tiết: DOC-23 (P4).

## 5. Job theo lịch và yêu cầu chạy tay

```mermaid
sequenceDiagram
  autonumber
  participant S1 as etl-batch pod A (@Scheduled)
  participant S2 as etl-batch pod B (@Scheduled)
  participant PGW as PG-W (ops.shedlock, batch.*)
  participant JO as JobOperator
  participant API as api
  S1->>PGW: ShedLock: UPDATE shedlock SET lock_until … (thắng)
  S2->>PGW: ShedLock: UPDATE … (0 dòng → bỏ qua lượt này)
  S1->>JO: start(job, identifying params)
  JO->>PGW: tạo JobInstance/JobExecution (từ chối nếu instance đang chạy)
  JO-)JO: chạy trên executor pti-job-*
  API->>PGW: INSERT job_request (RUN/RESTART/STOP) — người vận hành
  S2->>PGW: JobRequestPoller: SELECT … FOR UPDATE SKIP LOCKED
  S2->>JO: start / restart / stop#59; job_request = RUNNING
  JO->>PGW: kết thúc: BATCH_JOB_EXECUTION + job_request DONE/FAILED
```

- Ba lớp chống chạy trùng: ShedLock, `JobInstance`, fencing `VERSION` (ADR-0015).
- Chi tiết: DOC-19 §2, §7.

## 6. DLQ: triage bằng AI và auto-replay

```mermaid
sequenceDiagram
  autonumber
  participant TW as triage-worker
  participant PGW as PG-W
  participant J as Jev (TypeSafe API)
  participant ES as etl-stream (/actuator/health/sources)
  participant EB as etl-batch
  TW->>PGW: SELECT dead_letter NEW … LIMIT 50 FOR UPDATE SKIP LOCKED#59; → TRIAGING, lease 2 phút
  TW->>J: systemOne(state, {category, severity}) — timeout 2 s, bulkhead 8
  J-->>TW: lựa chọn + confidence, model_version
  TW->>PGW: UPDATE … WHERE status = 'TRIAGING' → TRIAGED#59; dlq_action_log TRIAGED
  alt confidence cao, category cho phép, auto_replay_count < 2
    TW->>PGW: → AUTO_REPLAY_SCHEDULED
    loop tới khi nguồn UP ≥ 60 s (DR-38)
      TW->>ES: GET health nguồn tương ứng
    end
    TW->>PGW: BEGIN#59; → REPLAY_REQUESTED, auto_replay_count + 1#59; INSERT replay_request (requested_by = auto)#59; COMMIT
    EB->>PGW: ReplayRequestPoller nhận → DlqReplayJob (§7, bước 5–8)
  else confidence vùng giữa
    TW->>PGW: → PENDING_CONFIRM (chờ người vận hành)
  else thấp
    TW->>PGW: → MANUAL
  end
```

- Jev chỉ phán đoán; ngưỡng và quyết định hành động do code sở hữu (ADR-0019). `auto_replay_count` có CHECK `≤ 2` ở DB.
- Jev lỗi hoặc timeout: §12.
- Chi tiết: DOC-24 (P6), DOC-15 §4.3.

## 7. DLQ: sửa và replay thủ công

```mermaid
sequenceDiagram
  autonumber
  actor OP as Người vận hành
  participant UI as frontend (Ops console)
  participant API as api (replay_operator)
  participant PGW as PG-W
  participant EB as etl-batch (DlqReplayJob)
  OP->>UI: sửa payload
  UI->>API: PUT /etl/dlq/{id}/payload
  API->>API: PayloadValidator (schema, PII, business key)
  API->>PGW: UPDATE edited_payload#59; dlq_action_log EDITED
  OP->>UI: Replay
  UI->>API: POST /etl/dlq/{id}/replay (Idempotency-Key)
  API->>PGW: BEGIN#59; → REPLAY_REQUESTED (WHERE status IN …)#59; INSERT replay_request#59; COMMIT
  API-->>UI: 202 {replayRequestId}
  EB->>PGW: poll replay_request (SKIP LOCKED) → RUNNING
  EB->>EB: process edited_payload (replay = true)
  alt thành công
    EB->>PGW: BEGIN#59; upsert fact (:replay)#59; dead_letter REPLAYED#59; action log#59; COMMIT
  else lỗi dữ liệu lại
    EB->>PGW: dead_letter → NEW với lỗi mới#59; REPLAY_FAILED
  end
  EB->>PGW: replay_request DONE/FAILED + stats
  UI->>API: GET /etl/replays/{id} (poll 2 s) hoặc SSE dlq.changed
```

- Chi tiết: DOC-22 §2–3, DOC-32.

## 8. Replay khoảng thời gian từ raw zone

```mermaid
sequenceDiagram
  autonumber
  actor OP as Người vận hành
  participant API as api
  participant PGW as PG-W
  participant EB as etl-batch (RawZoneReplayJob)
  participant S3
  OP->>API: POST /etl/replays {source, fromTs, toTs (giờ record Kafka), recomputeAnalytics}
  API->>API: kiểm tra cửa sổ (≤ 7 ngày, to ≤ now − 10 phút, from ≥ now − 29 ngày)
  API->>PGW: INSERT replay_request (unique: một RAW_RANGE mỗi nguồn → 409)
  EB->>PGW: nhận request → RUNNING
  EB->>S3: ListObjectsV2 theo giờ (± 1 giờ), lưu danh sách vào ExecutionContext
  loop mỗi object, mỗi chunk 500 dòng
    EB->>S3: GET object (gzip)
    EB->>EB: lọc theo timestamp record#59; process (replay = true)
    EB->>PGW: BEGIN#59; upsert fact (:replay)#59; DLQ upsert#59; resolve DLQ cũ#59; ExecutionContext#59; COMMIT
  end
  opt recomputeAnalytics
    EB->>PGW: xóa và tính lại insight trong khoảng event time
  end
  EB->>PGW: replay_request DONE + stats
```

- Replay chạy song song với luồng trực tiếp được: guard của upsert không để dữ liệu cũ đè dữ liệu mới (DOC-22 §4.5).
- Chi tiết: DOC-22 §4.

## 9. Sự kiện real-time tới trình duyệt (SSE)

```mermaid
sequenceDiagram
  autonumber
  participant ES as etl-stream / analytics / etl-batch / triage-worker
  participant K as Kafka (pti.events.ui)
  participant API as api pod (group pti-api-sse-#lt;pod#gt;)
  participant B as Trình duyệt
  B->>API: GET /api/v1/stream?channels=vehicles,alerts (Last-Event-ID nếu kết nối lại)
  API-->>B: replay các sự kiện trong ring buffer có id > Last-Event-ID, hoặc resync
  ES-)K: sự kiện (ULID, source_record_ts), sau commit, best-effort
  K->>API: consumer mỗi pod đọc mọi sự kiện (offset latest)
  API->>API: ring buffer 5 phút#59; lọc theo channel, audience (role), routeId
  API-->>B: event: vehicles.batch / alert.created …#59; id: #lt;ULID#gt;
  API->>API: end_to_end_latency_seconds = now − source_record_ts (DR-57)
  loop mỗi 15 s
    API-->>B: heartbeat
  end
```

- Mỗi pod API là một consumer group riêng, nên mọi pod nhận mọi sự kiện và phục vụ được mọi client (ADR-0016).
- Mất sự kiện thì chấp nhận: nguồn sự thật ở DB, client refetch mỗi 60 giây hoặc khi nhận `resync` (DR-42).
- Chi tiết: DOC-26, DOC-33 (P4).

## 10. Lỗi: Postgres chết giữa chunk

```mermaid
sequenceDiagram
  autonumber
  participant ES as etl-stream
  participant CB as Circuit breaker "warehouse"
  participant PGW as PG-W
  participant K as Kafka
  ES->>K: poll 500 records
  ES->>PGW: BEGIN#59; upsert …
  PGW--xES: 08006 connection failure
  Note over ES,PGW: transaction không commit → không có dữ liệu, không có dòng DLQ
  ES->>ES: ErrorClassifier → TRANSIENT_INFRA
  ES->>CB: ghi nhận lỗi (≥ 50% trong 10 lần → OPEN)
  CB-->>ES: OPEN → pause mọi container
  loop backoff 1 → 30 s (vô hạn)
    ES->>K: poll() rỗng (container pause#59; vẫn trong group)
  end
  CB-->>ES: HALF_OPEN sau 10 s → resume, thử lại cùng poll (offset chưa commit)
  ES->>PGW: BEGIN#59; … COMMIT (Postgres đã lên lại)
  ES->>K: commitSync
```

- Không record nào vào DLQ; không mất gì (FR-02.8). Alert `CircuitBreakerOpen` nếu mở quá 1 phút.
- Job batch: retry chunk tối đa 5 lần trong 60 giây, rồi step `FAILED` và restart sau (DOC-19 §4.4).
- Chi tiết: DOC-20 §5, EXP-08.

## 11. Lỗi: pod bị kill sau commit, trước khi commit offset

```mermaid
sequenceDiagram
  autonumber
  participant P1 as etl-stream pod 1
  participant PGW as PG-W
  participant K as Kafka
  participant P2 as etl-stream pod 2 (hoặc pod 1 sau restart)
  P1->>K: poll offsets 1000–1499
  P1->>PGW: BEGIN#59; upsert 500#59; DLQ 3#59; etl_stream_batch#59; COMMIT
  Note over P1: kill -9 trước commitSync
  K->>K: session timeout → rebalance
  P2->>K: poll từ offset đã commit = 1000
  P2->>PGW: BEGIN#59; DLQ ON CONFLICT DO NOTHING#59; dedup_registry: hash đã thấy → bỏ#59; upsert guard → 0 dòng#59; COMMIT
  P2->>K: commitSync 1500
```

- Kết quả cuối giống như không có lỗi: mất 0, trùng 0 (FR-03.5). Phần giao lại được đếm vào `records_duplicate`.
- Nếu pod chết **trước** commit DB thì transaction rollback, và poll được xử lý lại bình thường.
- Chi tiết: ADR-0003, ADR-0004, DOC-22 §1.3, EXP-01.

## 12. Lỗi: Jev timeout hoặc không truy cập được

```mermaid
sequenceDiagram
  autonumber
  participant TW as triage-worker
  participant R4J as Resilience4j (timeout 2 s, CB, bulkhead, rate limiter)
  participant J as Jev
  participant PGW as PG-W
  TW->>PGW: nhận 50 dead letter → TRIAGING (lease 2 phút)
  TW->>R4J: gọi Jev cho từng record
  R4J->>J: POST /v1/systemone
  J--xR4J: không trả lời trong 2 s
  R4J-->>TW: TimeoutException
  TW->>PGW: UPDATE … WHERE status='TRIAGING' → NEW, triage_attempts + 1#59; TRIAGE_FAILED
  Note over R4J: lỗi liên tiếp → circuit OPEN
  R4J-->>TW: CallNotPermitted
  TW->>TW: tạm ngừng nhận việc tới khi circuit HALF_OPEN
  Note over TW,PGW: triage_attempts = 5 → MANUAL. Người vận hành vẫn replay/discard trực tiếp từ NEW
```

- AI lỗi không chặn pipeline: ETL không phụ thuộc Jev. Insight chờ làm giàu thì `enrichment_status` về `PENDING` rồi `FAILED` sau 5 lần; UI hiển thị episode không có "nguyên nhân khả dĩ".
- Adapter `Disabled` (cờ hoặc `pti.triage.provider=disabled`) bỏ qua gọi Jev hoàn toàn.
- Chi tiết: DOC-24 (P6).

## 13. Lỗi: feed GTFS-rt ngừng cập nhật (stale)

```mermaid
sequenceDiagram
  autonumber
  participant SIM as source-simulator
  participant ES as etl-stream
  participant PGW as PG-W
  participant PR as Prometheus / Alertmanager
  participant API as api
  participant B as Trình duyệt
  SIM--xSIM: dừng phát (lỗi, hoặc kịch bản)
  ES->>ES: không còn chunk GTFS-rt → source-gtfs-rt DOWN sau 30 s
  API->>PGW: mỗi 15 s: max(event_timestamp) vehicle_position_latest (DR-71)
  PR->>API: scrape pti_source_last_event_age_seconds
  PR->>PR: > 2 phút → alert GtfsRtFeedStale (khẩn)
  B->>API: GET /system/freshness
  API->>PGW: max(event_timestamp), max(finished_at) etl_stream_batch
  API-->>B: {gtfsRt: {lastEventAt, stale: true}}
  B->>B: stale banner + thời điểm dữ liệu cuối (FR-11.6)
  Note over ES: auto-replay DLQ của nguồn GTFS-rt tạm dừng (DR-38)
```

- Alert dựa trên dữ liệu trong DB, không dựa trên health của pod, để vẫn báo được khi mọi pod `etl-stream` đã chết.
- Chi tiết: DOC-28, RB-06.

## 14. Truy vết sang tài liệu khác

| Luồng | Thiết kế | Yêu cầu | Thực nghiệm |
| --- | --- | --- | --- |
| §1, §2 | DOC-20 | FR-01, FR-02, FR-03 | EXP-01, 02, 03, 05 |
| §3 | DOC-21 | FR-01.1, FR-03.6 | — |
| §4 | DOC-23 | FR-05…FR-08 | — |
| §5 | DOC-19 | FR-03.6, FR-15 | — |
| §6, §12 | DOC-24 | FR-09 | EXP-06 |
| §7, §8 | DOC-22 | FR-12 | EXP-04 |
| §9 | DOC-26, DOC-33 | FR-10, FR-11 | EXP-05 |
| §10, §11 | DOC-19, DOC-20 | FR-02.8, FR-03.5, NFR-01, NFR-04 | EXP-01, EXP-08 |
| §13 | DOC-28 | FR-11.6, FR-14 | — |
