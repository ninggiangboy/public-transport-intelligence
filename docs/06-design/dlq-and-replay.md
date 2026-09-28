# DLQ và replay

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-22
> Phụ thuộc: [DOC-15](../05-data/ops-and-insight-model.md) §3–4, [DOC-16](../05-data/data-quality-rules.md), [DOC-18](../05-data/data-lifecycle.md), [DOC-19](batch-and-chunk-processing.md), [DOC-20](etl-streaming.md), [DOC-21](etl-gtfs-static.md) §6, [DOC-09](../03-architecture/messaging-contracts.md) §7, [ADR-0003](../04-adr/0003-effectively-once-upsert.md), [ADR-0012](../04-adr/0012-raw-zone-s3-sink.md), [ADR-0013](../04-adr/0013-replay-request-api-etl-executes.md), [DR](../00-decision-register.md) (DR-16, 18, 20, 37, 38, 60)
> Người dùng chính: `etl` profile `batch` (P2-16), `api` (DOC-32: endpoint DLQ và replay), `triage-worker` (DOC-24: auto-replay), DOC-36 (Ops console), DOC-42 (RB-10, RB-11)

Tài liệu này mô tả: cái gì được lưu vào DLQ và lưu thế nào, sửa payload, ba cách replay (một record DLQ, một khoảng thời gian từ raw zone, reset offset Kafka), giới hạn đồng thời và cách truy vết. Máy trạng thái của `dead_letter` và `replay_request` nằm ở DOC-15 §4.3–4.4; tài liệu này chỉ nói phần ETL thực thi.

## 1. Ghi vào DLQ

### 1.1 Cái gì vào DLQ

Chỉ lỗi loại `DATA` (ADR-0006) của **dữ liệu streaming và replay**: GTFS-rt và CDC ticketing. Lỗi hạ tầng không bao giờ vào DLQ (FR-02.8). Lỗi dòng của GTFS static không vào DLQ mà vào `validation_report` của feed (DOC-21 §3.2), vì feed được chấp nhận hoặc từ chối nguyên khối.

| Stage | Từ đâu | `error_class` | `rule_id` |
| --- | --- | --- | --- |
| `DESERIALIZE` | JSON hỏng, UTF-8 hỏng, dòng raw zone hỏng | Tên exception rút gọn, ví dụ `JsonParseException`, `MalformedInputException` | NULL |
| `SCHEMA` | JSON Schema, Bean Validation, version lạ | `DQ-01` | `DQ-01` |
| `QUALITY`, `BUSINESS`, `DEDUP` | Rule DQ (DOC-16 §2) | mã rule | mã rule |
| `LOAD` | Lỗi `22xxx`/`23xxx` khi ghi, phát hiện ở scan | `DataIntegrityViolation` | NULL |

### 1.2 Nội dung một dòng

| Cột | Giá trị |
| --- | --- |
| `id` | UUIDv7 |
| `raw_payload` | `PiiScrubber.scrub(value)` (DOC-18 §4.1), UTF-8, `U+0000` thay bằng `U+FFFD` (DOC-20 §4.1). Tối đa 1 MiB; dài hơn thì cắt và thêm hậu tố `…[truncated <n> bytes]` (message thật không bao giờ lớn tới mức này; giới hạn chỉ để chặn dữ liệu rác) |
| `business_key` | Nếu parse được tới mức có key (DOC-09 §9), dạng `vehicle_id|event_timestamp`, `service_date|trip_id`, `sale_date|transaction_id`, `sale_point_id`; không thì NULL |
| `kafka_topic`, `kafka_partition`, `kafka_offset`, `kafka_timestamp` | Của record gốc; với raw zone replay là giá trị lưu trong dòng raw zone |
| `batch_id` | `batch_id` của poll (streaming) hoặc của step execution (job) |
| `error_message` | Tiếng Anh, tối đa 4.000 ký tự, nêu trường và lý do, ví dụ `payload.vehicle_id: must not be blank`, `Route 999 not found in feed version 3` |
| `status` | `NEW` |

### 1.3 Idempotent theo vị trí Kafka

Một poll có thể được giao lại sau khi dữ liệu và DLQ đã commit nhưng offset chưa commit (ADR-0004). Nếu không xử lý, record lỗi sẽ có hai dòng DLQ. Vì vậy `dead_letter` có unique index một phần theo vị trí Kafka (bổ sung vào DOC-15, V5_2):

```sql
CREATE UNIQUE INDEX dead_letter_kafka_pos_uk ON ops.dead_letter (kafka_topic, kafka_partition, kafka_offset)
  WHERE kafka_topic IS NOT NULL;
```

`DeadLetterWriter` dùng hai câu lệnh tùy chế độ:

```sql
-- live streaming: a redelivered record keeps its first dead letter
INSERT INTO ops.dead_letter (…) VALUES (…)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) WHERE kafka_topic IS NOT NULL DO NOTHING;

-- replay (raw zone or DLQ record): refresh the existing row with the latest failure
INSERT INTO ops.dead_letter AS d (…) VALUES (…)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) WHERE kafka_topic IS NOT NULL DO UPDATE SET
  stage = excluded.stage, error_class = excluded.error_class, error_message = excluded.error_message,
  rule_id = excluded.rule_id, batch_id = excluded.batch_id,
  replay_count = d.replay_count + 1, last_replay_at = now(), updated_at = now(),
  status = CASE WHEN d.status IN ('DISCARDED', 'RESOLVED') THEN d.status ELSE 'NEW' END,
  triage_lease_until = CASE WHEN d.status IN ('DISCARDED', 'RESOLVED') THEN d.triage_lease_until ELSE NULL END
RETURNING (xmax = 0) AS inserted;
```

- Dòng đã `DISCARDED`/`RESOLVED` giữ trạng thái, chỉ cập nhật thông tin lỗi mới nhất.
- Dòng đang `TRIAGING` bị đưa về `NEW`; triage-worker gặp 0 dòng khi cập nhật và bỏ kết quả của nó (DOC-15 §4.3, quy tắc điều kiện trạng thái trong `WHERE`).
- Mỗi lần cập nhật do replay ghi `dlq_action_log` `REPLAY_FAILED`, actor `system:etl-batch`, `details` chứa stage và `error_class` cũ.

Mọi dòng DLQ đều có vị trí Kafka (streaming và raw zone đều biết), nên index này bao phủ toàn bộ DLQ.

## 2. Sửa payload (FR-12.1)

Người vận hành sửa payload trong Ops console; API (`PUT /etl/dlq/{id}/payload`, DOC-32) kiểm tra trước khi lưu:

| Kiểm tra | Lỗi → HTTP |
| --- | --- |
| Trạng thái hiện tại thuộc `NEW`, `TRIAGED`, `PENDING_CONFIRM`, `MANUAL` | 409 `dlq-invalid-state` |
| Payload là JSON hợp lệ, ≤ 1 MiB | 422 `invalid-payload` |
| GTFS-rt: envelope đúng, `(entity_type, schema_version)` được hỗ trợ, qua JSON Schema và Bean Validation (DQ-01) | 422 kèm danh sách vi phạm theo trường |
| CDC: qua Bean Validation của `TicketTransactionCdc`/`SalePointCdc` | 422 |
| Không chứa khóa trong `pti.pii.blocklist` (`customer_ref`…, DOC-18 §4) | 422 `pii-not-allowed` |
| `entity_type` và khóa nghiệp vụ không đổi so với `raw_payload` khi `raw_payload` parse được | 422 `business-key-changed`. Đổi khóa sẽ tạo bản ghi mới thay vì sửa bản ghi lỗi; muốn vậy thì discard và để nguồn gửi lại |

- Bộ kiểm tra dùng chung `PayloadValidator` trong module `common`, cùng code với processor của ETL (DOC-09 §10), nên payload qua được API chắc chắn qua bước `SCHEMA` khi replay. Rule DQ-03…13 cần `ReferenceData` và dữ liệu warehouse nên **không** chạy ở API; chúng chạy khi replay.
- Lưu: `edited_payload` (JSONB), `updated_at`; `dlq_action_log` `EDITED`, actor `user:<username>`, `details = {"changed_paths": ["payload.route_id"]}` (tính bằng JSON diff, không lưu giá trị).
- `raw_payload` không bao giờ bị ghi đè.

## 3. Replay một record DLQ

### 3.1 Tạo yêu cầu

Ai tạo và điều kiện nằm ở DOC-15 §4.3 và DOC-32/DOC-24. Trong một transaction: `dead_letter.status → REPLAY_REQUESTED` (có điều kiện trạng thái nguồn) và `INSERT replay_request (kind = 'DLQ_RECORD', source, dead_letter_id, requested_by)`. Index `replay_request_one_per_record` chặn hai yêu cầu đang chờ cho cùng record.

### 3.2 `DlqReplayJob`

| Thuộc tính | Giá trị |
| --- | --- |
| Tham số | `replayRequestId` (định danh), `replay = true` |
| Step `replayRecord` | Chunk 1; reader `JdbcPagingItemReader` đọc đúng dòng `dead_letter` của request; processor = router theo `source` (DOC-19 §4.2); writer = `FactChunkWriter` + `DlqReplayOutcomeWriter` |
| Kết thúc | `ReplayRequestListener.afterJob` cập nhật `replay_request` |

Thuật toán:

1. **Kiểm tra trước** (`beforeStep`): `dead_letter.status = 'REPLAY_REQUESTED'`; khác thì step kết thúc với `FAILED`, `message = "Dead letter <id> is <status>, expected REPLAY_REQUESTED"`.
2. **Dựng `InboundMessage`:** `value` = `edited_payload::text` nếu khác NULL (JSON chuẩn hóa), ngược lại `raw_payload`; `key`, `topic`, `partition`, `offset`, `recordTimestamp` từ các cột Kafka của dòng.
3. **Process** với `RuleContext.replay = true`: bỏ DQ-07, DQ-12 và dedup registry (DOC-16, DR-16).
4. **Thành công** (trong transaction của chunk):
   - `FactChunkWriter` ghi fact với `:replay = true` (ghi đè cả bản bằng thời gian, DOC-14 §8);
   - `DlqReplayOutcomeWriter`: `dead_letter` → `REPLAYED`, `replay_count + 1`, `last_replay_at`; `dlq_action_log` `REPLAYED`, actor `system:etl-batch`, `details = {"replay_request_id": …, "batch_id": …, "rows_written": n}`.
5. **Lỗi dữ liệu lại** (skip ở process hoặc write): `DlqReplaySkipListener` dùng câu lệnh replay ở §1.3, cập nhật chính dòng đó về `NEW` với lỗi mới. Không tạo dòng DLQ mới.
6. **Lỗi hạ tầng:** retry theo DOC-19 §4.4; hết lượt thì job `FAILED`, `replay_request.status = FAILED`, `dead_letter` **giữ** `REPLAY_REQUESTED`. Người vận hành (hoặc triage-worker, DOC-24) tạo yêu cầu mới; không restart job cũ để tránh chạy chồng với yêu cầu mới.

`replay_request.stats` của DLQ replay: `{"outcome": "REPLAYED" | "FAILED_AGAIN", "rows_written": n, "stage": "...", "rule_id": "..."}`.

## 4. Replay khoảng thời gian từ raw zone

### 4.1 Yêu cầu

| Trường | Quy tắc (API kiểm tra, trả 422 nếu sai) |
| --- | --- |
| `source` | `GTFS_RT_VEHICLE_POSITION`, `GTFS_RT_TRIP_UPDATE`, `TICKETING_SALES`, `TICKETING_SALE_POINTS`. `GTFS_STATIC` không hỗ trợ: nạp lại feed bằng `GtfsStaticLoadJob` với `sourceUri = s3://raw/gtfs-static/<hash>.zip` (DOC-21 §1.1) |
| `from_ts`, `to_ts` | **Thời điểm record Kafka (CreateTime, giờ thật, UTC)**, không phải event time. `from_ts < to_ts`, khoảng ≤ 7 ngày (CHECK ở DB) |
| | `to_ts ≤ now − pti.replay.raw-settle` (mặc định 10 phút): S3 sink đóng file tối đa sau 5 phút (DOC-09 §7), nên sau 10 phút mọi object của khoảng đã có mặt và danh sách object không đổi trong lúc replay |
| | `from_ts ≥ now − pti.replay.raw-max-age` (mặc định 29 ngày): tránh object bị lifecycle 30 ngày xóa giữa chừng (DOC-18 §1) |
| `recompute_analytics` | Chỉ nhận `true` từ P4 (khi `AnalyticsRecomputeService` có mặt); trước đó API trả 422 `analytics-recompute-unavailable` |

**Vì sao dùng giờ record thay vì event time:** raw zone được phân vùng theo giờ record (DOC-09 §7), nên chọn object theo giờ record là chính xác. Event time phụ thuộc đồng hồ nghiệp vụ (DR-67), và offset của đồng hồ có thể đã đổi kể từ lúc dữ liệu được sinh, nên suy ngược ra giờ record không tin cậy được. Ops console hiển thị kèm khoảng event time ước tính theo offset hiện tại (DOC-36), chỉ để tham khảo.

### 4.2 `RawZoneReplayJob`

| Step | Loại | Việc |
| --- | --- | --- |
| `listObjects` | tasklet | Liệt kê object (§4.3), lưu danh sách vào job `ExecutionContext` |
| `replayRecords` | chunk 500, fault-tolerant | Đọc, lọc, process, ghi (§4.4) |
| `recomputeAnalytics` | tasklet CONTINUABLE, chỉ khi `recomputeAnalytics = true` | Lần gọi đầu lập kế hoạch bằng `AnalyticsRecomputeService.plan(source, ReplayRange)` với khoảng thu được ở step trước; mỗi lần gọi sau chạy một `WorkItem` (một tuyến, một cửa sổ hoặc một ngày) trong transaction riêng (DOC-23 §11) |

Tham số: `replayRequestId` (định danh), `replay = true`, `source`, `fromTs`, `toTs`, `recomputeAnalytics`.

### 4.3 `listObjects`

1. Topic theo `source`: `gtfs.vehicle_positions`, `gtfs.trip_updates`, `ticketing.sales.cdc`, `ticketing.sale_points.cdc`.
2. Các thư mục giờ `raw/<topic>/dt=<d>/hh=<h>/` với `h` từ `floor_hour(from_ts)` tới `floor_hour(to_ts − 1ms)`. Sink phân thư mục theo CreateTime của từng record (S-04), nên không cần quét thêm giờ kế bên (DOC-09 §7).
3. `ListObjectsV2` từng prefix (Spring Cloud AWS `S3Template.listObjects`), sắp theo `(dt, hh, partition, start_offset)`, lấy từ tên file `<topic>-<partition>-<start_offset>.json.gz` (DOC-18 §2). Tên file không khớp mẫu này thì bỏ qua và ghi `WARN`.
4. Lưu vào `ExecutionContext` của job, key `pti.replay.objects`, dạng gọn `{"<dt>/<hh>": ["<partition>-<start_offset>", …]}` với `start_offset` không đệm số 0. Ước lượng 7 ngày của `gtfs.vehicle_positions` ở tải nền: file đóng khoảng mỗi 2,5 phút (partition nóng nhất đạt 2.000 record trước mốc 5 phút, DOC-09 §7), tức khoảng 48.000 object và khoảng 700 KB JSON. Sau một lần sink dừng lâu rồi chạy bù, số object nhiều hơn (một object mỗi 2.000 record). Vượt `pti.replay.max-objects` (100.000) thì step `FAILED` với thông báo chia nhỏ khoảng.
5. Không có object nào → job `COMPLETED`, `stats.objects = 0`, `message = "No raw objects in range"`.

### 4.4 `replayRecords`

- **Reader:** `MultiResourceItemReader<InboundMessage>` trên danh sách `S3Resource` (theo thứ tự đã lưu), delegate `RawZoneLineItemReader`:
  - mở `GZIPInputStream`, đọc từng dòng JSON (DOC-09 §7), dựng `InboundMessage` với `key`, `value` (**giải base64 thành `byte[]`**, rồi đi qua đúng bước giải mã UTF-8 của `etl-stream`, DOC-20 §4.1), `partition` (**lấy từ tên file**, dòng không có trường này), `offset`, `timestamp` (chuỗi ISO-8601 → `Instant`), `headers`;
  - khử trùng theo `(partition, offset)` trong phạm vi job: sink at-least-once có thể ghi cùng offset vào hai object. Một record luôn nằm trong thư mục giờ của chính nó, nên bản trùng chỉ có thể ở cùng cặp `(giờ, partition)`. Reader giữ offset lớn nhất đã trả của cặp `(giờ, partition)` đang đọc, đặt lại khi sang cặp mới; dòng có offset ≤ giá trị đó bị bỏ và đếm `duplicate`. Cách này đúng vì trong một cặp, object được đọc theo `start_offset` tăng dần và offset trong một object tăng dần. Giá trị này nằm trong `ExecutionContext` của step để restart giữ được;
  - **lọc** dòng có `timestamp ∉ [from_ts, to_ts)` ngay trong reader (không trả item; đếm `filtered`);
  - dòng không parse được, hoặc `value` không phải base64 hợp lệ → `RawZoneLineException` (`DATA`) → skip → DLQ `DESERIALIZE` với `raw_payload` = cả dòng (đã scrub).
  - Restart: `MultiResourceItemReader` lưu `resourceIndex`, delegate lưu số dòng đã đọc của object hiện tại; restart mở lại object đó và bỏ qua số dòng ấy.
- **Processor:** router theo `source`, `RuleContext.replay = true`, `ReferenceData` của feed **ACTIVE hiện tại** (DOC-21 §6.2): dữ liệu cũ được kiểm theo feed hiện tại. Nếu feed đã đổi và tuyến cũ bị xóa, record rơi vào DLQ `QUALITY` `DQ-03`. Đây là hạn chế đã chấp nhận; muốn tránh thì activate lại feed cũ trước khi replay (RB-11).
- **Writer:** `FactChunkWriter` (replay: không registry, `:replay = true`) cộng `DlqResolveWriter`: với mọi message ghi thành công mà có dòng `dead_letter` cùng vị trí Kafka ở trạng thái chưa đóng (`NEW`, `TRIAGED`, `AUTO_REPLAY_SCHEDULED`, `PENDING_CONFIRM`, `MANUAL`), chuyển dòng đó sang `RESOLVED`, `resolved_by = 'system:etl-batch'`, `resolved_at = now()`, và ghi `dlq_action_log` `RESOLVED`. Như vậy replay sau khi sửa lỗi logic tự đóng các dead letter mà nó đã giải quyết.
- **DLQ** khi lỗi dữ liệu: câu lệnh replay ở §1.3 (cập nhật dòng cũ nếu có, không thì tạo mới).
- **Skip policy:** `RatioSkipPolicy` (DOC-19 §4.4). Replay một khoảng có kịch bản `bad-data` 20% vẫn dưới ngưỡng; vượt thì step `FAILED`.
- `ExecutionContext` của step giữ `pti.replay.minEventTs`, `pti.replay.maxEventTs` (cho `recomputeAnalytics`) và bộ đếm của §4.6. Với `TICKETING_SALES`, step giữ thêm `pti.replay.minCreatedAt`, `pti.replay.maxCreatedAt` (cột `created_at` của giao dịch đã ghi), vì cửa sổ ticketing tính theo `created_at` (giờ nghiệp vụ), còn `event_timestamp` của vé là giờ commit ở nguồn (DR-67, DOC-23 §11.1).
- **Hiệu năng:** mục tiêu ≥ 2.000 message/giây trên compose (một thread, chunk 500). EXP-04 đo con số thật.

### 4.5 Tương tác với luồng trực tiếp

Replay có thể chạy song song với `etl-stream` trên cùng bảng:

- Guard của upsert (DOC-14 §8) bảo đảm dữ liệu replay **không đè** dữ liệu mới hơn: TripUpdate cũ hơn bị chặn bởi event time, giao dịch cũ bị chặn bởi LSN, `vehicle_position_latest` không có `:replay` nên xe không bị kéo lùi.
- `:replay = true` chỉ cho phép ghi đè bản **cùng** event time/LSN, tức đúng bản ghi mà replay đang tái xử lý.
- Hai transaction ghi cùng dòng theo cùng thứ tự bảng và khóa (DOC-19 §4.3) nên không deadlock; nếu có `40P01` thì retry.

### 4.6 Kết quả

`ReplayRequestListener.afterJob` ghi `replay_request`:

```json
{
  "objects": 288, "lines_read": 1204332, "filtered": 51200, "processed": 1153132,
  "written": 1150020, "duplicate": 0, "skipped": 3112,
  "dlq_inserted": 12, "dlq_updated": 3100, "dlq_resolved": 0,
  "min_event_ts": "2026-09-29T00:00:03Z", "max_event_ts": "2026-09-29T23:59:58Z",
  "analytics_recomputed": true,
  "analytics": {
    "BUNCHING": { "scopes": 37, "upserted": 12, "deleted": 1 }
  },
  "duration_ms": 612000
}
```

`analytics_recomputed` là `true` khi step `recomputeAnalytics` đã chạy xong. `analytics` chỉ có khi đó, gồm thống kê theo detector (DOC-23 §11.7).

`status = DONE` khi job `COMPLETED`; `FAILED` khi job `FAILED`/`STOPPED`, với `message` là exit description (tiếng Anh).

### 4.7 Khôi phục

- Pod chết giữa replay: `StaleExecutionRecoverer` đánh dấu `FAILED` nhưng **không** tự restart job replay (DOC-19 §7.2), và đặt `replay_request.status = FAILED`, `message = "Execution became stale"`.
- Người vận hành chạy lại bằng `POST /etl/jobs/{executionId}/restart` (DR-43): `job_request` `RESTART` → `JobOperator.restart` → tiếp tục từ object và dòng đã commit. Khi execution mới bắt đầu, `ReplayRequestListener.beforeJob` đặt lại `replay_request` về `RUNNING` với `job_execution_id` mới.
- Không tự restart vì replay dài có thể đang bị dừng có chủ ý (`STOP`), và người vận hành cần quyết định có chạy tiếp hay không.

## 5. Replay bằng reset offset (RB-10)

Dùng khi raw zone không có dữ liệu (connector hỏng) nhưng Kafka vẫn còn trong retention 7 ngày. Các bước ở DOC-42 RB-10; ý chính:

1. Bật cờ tạm dừng listener (DOC-20 §7), rồi dừng `etl-stream` (group phải không còn member).
2. `kafka-consumer-groups --group pti-etl-gtfs-rt --topic gtfs.vehicle_positions --reset-offsets --to-datetime <ISO> --execute`.
3. Khởi động lại `etl-stream`, tắt cờ.

Khác biệt với raw zone replay: dữ liệu đi qua **luồng trực tiếp**, nên dedup registry vẫn bật và `:replay = false`. Kết quả: message đã ghi được coi là trùng và không ghi lại. Cách này chỉ **bù dữ liệu bị thiếu**, không áp dụng logic mới cho dữ liệu đã có. Muốn tái xử lý bằng logic mới thì dùng raw zone replay.

## 6. Đồng thời và giới hạn

| Giới hạn | Cơ chế |
| --- | --- |
| Một `RAW_RANGE` đang chờ hoặc chạy cho mỗi nguồn | `replay_request_one_raw_per_source` (409, FR-12.3) |
| Một `DLQ_RECORD` đang chờ hoặc chạy cho mỗi record | `replay_request_one_per_record` |
| Số job đồng thời trên một pod | Executor của `JobOperator`: tối đa 3 (DOC-19 §3.3) |
| Số request nhận mỗi lần quét | `pti.replay.poller.max-claims` (mặc định 10). Mỗi lần quét chỉ nhận thêm khi executor còn chỗ; `RAW_RANGE` được ưu tiên xếp sau `DLQ_RECORD` cũ hơn theo `requested_at` |
| Tải lên warehouse khi replay lớn | Chunk 500, một thread; replay không làm luồng trực tiếp chậm quá NFR-03 (EXP-04 kiểm tra) |

`ReplayRequestPoller`:

```sql
SELECT * FROM ops.replay_request
WHERE status = 'PENDING'
ORDER BY requested_at
LIMIT :freeSlots
FOR UPDATE SKIP LOCKED;
```

Với mỗi dòng: `JobOperator.start(...)` (bất đồng bộ), `status = RUNNING`, `job_execution_id`, `started_at`, cùng transaction. `JobOperator.start` ném lỗi (tham số sai) thì `FAILED` kèm `message`.

## 7. Truy vết (FR-12.5)

```
replay_request.id
  → replay_request.job_execution_id  = batch.BATCH_JOB_EXECUTION.JOB_EXECUTION_ID
  → batch.BATCH_STEP_EXECUTION (step replayRecords)
  → ops.etl_batch_step (step_execution_id → batch_id)          (DR-63)
  → dw.fact_*.batch_id, ops.dead_letter.batch_id
```

Query mẫu cho Ops console và test:

```sql
SELECT count(*)
FROM dw.fact_vehicle_position f
JOIN ops.etl_batch_step s ON s.batch_id = f.batch_id
JOIN batch.batch_step_execution se ON se.step_execution_id = s.step_execution_id
JOIN ops.replay_request r ON r.job_execution_id = se.job_execution_id
WHERE r.id = :replayRequestId
  AND f.service_date BETWEEN :fromDate AND :toDate;   -- partition pruning
```

Log của job có `batch_id` và `replay_request_id` trong MDC, nên Loki lọc được theo cả hai.

## 8. Cấu hình

| Key | Kiểu | Mặc định | Mô tả |
| --- | --- | --- | --- |
| `pti.replay.raw-settle` | Duration | `10m` | §4.1 |
| `pti.replay.raw-max-age` | Duration | `29d` | §4.1 |
| `pti.replay.max-window` | Duration | `7d` | Trùng CHECK của DB; API kiểm trước để trả 422 rõ ràng |
| `pti.replay.max-objects` | int | `100000` | §4.3 |
| `pti.replay.chunk-size` | int | `500` | = `pti.etl.batch.chunk-size` |
| `pti.replay.poller.interval` | Duration | `5s` | |
| `pti.replay.poller.max-claims` | int | `10` | |
| `pti.dlq.max-payload-bytes` | DataSize | `1MB` | §1.2, §2 |

## 9. Metrics và log

| Metric | Loại | Label |
| --- | --- | --- |
| `pti_dlq_records_total` | counter | `source`, `stage`, `mode` (`live` \| `replay`), `result` (`inserted` \| `updated` \| `ignored`) |
| `pti_dlq_open_records` | gauge | `source`, `status` (tính mỗi 60 giây bởi `etl-batch` từ `dead_letter_list_idx`; nhiều pod thì dashboard dùng `max`) |
| `pti_dlq_resolved_by_replay_total` | counter | `source` |
| `pti_replay_requests_total` | counter | `kind`, `outcome` (`done` \| `failed`) |
| `pti_replay_records_total` | counter | `source`, `outcome` (`written` \| `duplicate` \| `skipped` \| `filtered`) |
| `pti_replay_duration_seconds` | histogram | `kind` |
| `pti_dlq_rule_category_total` | counter | `source`, `category` (từ `DlqRuleClassifier` trong `common`, DOC-24 §10, §15.1). Tăng cùng lúc với `pti_dlq_records_total{result="inserted"}`, chỉ ở chế độ `live` |

Alert liên quan (DOC-28): `DlqRateHigh` (DOC-16 §4), `DlqBacklogHigh` (`pti_dlq_open_records{status=~"NEW|MANUAL|PENDING_CONFIRM"}` > 500 trong 30 phút), `ReplayFailed`, `DlqUpstreamErrorBurst` (luật chặn cuối FR-09.8, dựa trên `pti_dlq_rule_category_total`).

## 10. Lỗi và cách xử lý

| Tình huống | Hành vi |
| --- | --- |
| Record DLQ bị discard trong lúc `DlqReplayJob` đang chờ | Không xảy ra: `REPLAY_REQUESTED → DISCARDED` không có trong máy trạng thái (API trả 409) |
| DLQ replay lỗi dữ liệu lại | Dòng về `NEW` với lỗi mới; triage chạy lại; `auto_replay_count` giới hạn số lần tự động (DOC-15) |
| Object raw zone bị thiếu giữa `listObjects` và `replayRecords` | `NoSuchKey` → `FATAL` → job `FAILED`; message nêu key. Hiếm nhờ `raw-max-age` |
| Object gzip hỏng | Lỗi đọc giữa object → dòng lỗi vào DLQ `DESERIALIZE` rồi object bị bỏ qua phần còn lại (`RawZoneLineItemReader` ghi `WARN` kèm key và số dòng đã đọc) |
| S3 không truy cập được | `TRANSIENT_INFRA`: retry; hết lượt thì job `FAILED`, restart tay |
| Hai replay cùng nguồn | 409 ở API |
| Replay trùng với luồng trực tiếp | §4.5: kết quả đúng nhờ guard |

## 11. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| R-01 | Record `QUALITY` `DQ-03` (route lạ), sửa `route_id`, replay | Fact có dòng với `batch_id` của replay; DLQ `REPLAYED`; log `EDITED` → `REPLAY_REQUESTED` → `REPLAYED` (FR-12.1) |
| R-02 | Replay record mà không sửa, vẫn lỗi | Dòng DLQ về `NEW`, `replay_count = 1`, không có dòng DLQ mới |
| R-03 | Sửa payload thành schema sai / thêm `customer_ref` / đổi `vehicle_id` | 422 với mã tương ứng; `edited_payload` không đổi |
| R-04 | Poll có record lỗi được giao lại sau crash (commit DB, chưa commit offset) | Đúng một dòng DLQ |
| R-05 | Raw zone replay một giờ VP (10.000 message, 1% lỗi), warehouse đã có dữ liệu | Số dòng fact không đổi; mọi dòng có `batch_id` của replay (FR-03.4); DLQ cũ được cập nhật chứ không nhân đôi |
| R-06 | Xóa sạch fact một ngày, raw zone replay ngày đó | Checksum khớp trước khi xóa (FR-12.2, DR-58) |
| R-07 | Sửa một rule DQ để record trước đây lỗi nay hợp lệ, raw zone replay | Dòng DLQ tương ứng `RESOLVED` bởi `system:etl-batch`; fact có dữ liệu |
| R-08 | Kill `etl-batch` giữa `replayRecords`, restart qua `job_request` | Tiếp từ object và dòng đã commit; kết quả cuối như không lỗi; `replay_request` `DONE` |
| R-09 | Hai `RAW_RANGE` cùng nguồn | Cái thứ hai 409 (FR-12.3) |
| R-10 | `to_ts` trong 10 phút gần nhất; khoảng 8 ngày; `from_ts` 40 ngày trước | 422 |
| R-11 | Replay chạy song song với luồng trực tiếp, cùng khoảng giờ gần nhất | `vehicle_position_latest` không lùi; TripUpdate mới hơn không bị đè |
| R-12 | Truy vết từ `replay_request` (query §7) | Đếm đúng số dòng đã ghi (FR-12.5) |
| R-13 | Khoảng không có object | `DONE`, `objects = 0` |
| R-14 | Dòng raw zone hỏng và object gzip hỏng | DLQ `DESERIALIZE`; job hoàn tất; log nêu object |
| R-15 | Object raw zone có dòng với `value` là base64 của byte `0xFF 0xC3` và `0x00` (như test S-08 của DOC-20 §14) | DLQ `DESERIALIZE` với cùng `error_class` (`MalformedInputException`) như khi `etl-stream` nhận message đó trực tiếp |
| R-16 | Hai object cùng `(giờ, partition)` chồng offset (`…-3-100` chứa 100..299, `…-3-150` chứa 150..400) | Mỗi offset được xử lý một lần; `duplicate = 150`; restart giữa object thứ hai vẫn cho cùng kết quả |
| R-17 | Dòng thiếu `partition`, `timestamp` dạng ISO-8601 (đúng định dạng thật của Aiven, DOC-09 §7) | `InboundMessage.partition` lấy từ tên file; lọc `[from_ts, to_ts)` theo `timestamp` đã parse |

## 12. Câu hỏi còn mở

Không có.
