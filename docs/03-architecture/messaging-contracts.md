# Hợp đồng message

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 · DOC-09
> Phụ thuộc: [DR](../00-decision-register.md) (DR-03, 04, 05, 07, 41, 57, 59, 63, 64), [ADR-0007](../04-adr/0007-json-envelope-for-gtfs-rt.md), [ADR-0008](../04-adr/0008-partition-key-route-id.md), [ADR-0012](../04-adr/0012-raw-zone-s3-sink.md), [DOC-07](system-context-and-containers.md)

Tài liệu này là **hợp đồng** giữa producer và consumer. Thay đổi bất kỳ mục nào ở đây là thay đổi hợp đồng và phải theo quy tắc ở §8.

## 1. Danh sách topic

| Topic | Producer | Key | Partition | Retention | Cleanup | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |
| `gtfs.vehicle_positions` | source-simulator | `route_id` | 12 | 7 ngày | delete | ADR-0008 |
| `gtfs.trip_updates` | source-simulator | `route_id` | 12 | 7 ngày | delete | ADR-0008 |
| `ticketing.sales.cdc` | Debezium | `{"transaction_id": "<uuid>"}` | 6 | 7 ngày | delete | Bảng `ticket_transaction` |
| `ticketing.sale_points.cdc` | Debezium | `{"sale_point_id": "<id>"}` | 1 | 7 ngày | delete | Bảng `sale_point`. *Bổ sung cho DR-05* |
| `ticketing.heartbeat` | Debezium | — | 1 | 1 giờ | delete | Sự kiện từ `debezium_heartbeat` (DR-64). Không ai tiêu thụ, không vào raw zone |
| `pti.events.ui` | etl-stream, etl-batch, triage-worker, api | entity id (xem §6) | 3 | 1 ngày | delete | DR-41, DR-42 |
| `connect-configs`, `connect-offsets`, `connect-status` | Kafka Connect | — | 1 / 25 / 5 | — | compact | Topic nội bộ của Connect |
| `__debezium-heartbeat.ticketing` | Debezium | — | 1 | 1 giờ | delete | Heartbeat nội bộ của connector |

- Dev (compose): RF = 1. k3d: RF = 3, `min.insync.replicas = 2`.
- `auto.create.topics.enable=false`. Topic được tạo bởi `kafka-init` (compose, script `deploy/compose/kafka/create-topics.sh`) và `KafkaTopic` CR (k3d). Hai nguồn này sinh từ cùng một file `deploy/topics.yaml`.
- `message.timestamp.type=CreateTime` cho mọi topic. Timestamp của record chính là mốc cho raw zone (§7) và cho độ trễ đầu-cuối (DR-57).

### 1.1 Consumer group

| Group | App | Topic | `auto.offset.reset` | Ghi chú |
| --- | --- | --- | --- | --- |
| `pti-etl-gtfs-rt` | etl-stream | `gtfs.vehicle_positions`, `gtfs.trip_updates` | `earliest` | Một listener container cho mỗi topic, cùng group |
| `pti-etl-ticketing` | etl-stream | `ticketing.sales.cdc`, `ticketing.sale_points.cdc` | `earliest` | `sale_points` xử lý trước khi tới sales (§5.3) |
| `pti-api-sse-<pod-name>` | api | `pti.events.ui` | `latest` | Mỗi pod một group (ADR-0016). Group cũ bị xóa theo `offsets.retention.minutes` |
| `connect-pti-raw-sink` | Kafka Connect | mọi topic `gtfs.*`, `ticketing.sales.cdc`, `ticketing.sale_points.cdc` | `earliest` | S3 sink |
| `pti-exp-baseline` | etl-stream (profile `experiment`) | như `pti-etl-gtfs-rt` | `earliest` | Chế độ baseline (DR-27), tách group để không đụng pipeline thật |

### 1.2 Cấu hình producer

Producer dữ liệu nguồn (simulator):

| Property | Giá trị | Lý do |
| --- | --- | --- |
| `acks` | `all` | Không mất khi một broker chết (NFR-09) |
| `enable.idempotence` | `true` | Không trùng do producer retry |
| `max.in.flight.requests.per.connection` | `5` | Tối đa cho phép khi bật idempotence, vẫn giữ thứ tự |
| `compression.type` | `zstd` | JSON nén tốt |
| `linger.ms` | `20` | Gom batch mà không làm tăng đáng kể độ trễ |
| `key.serializer` / `value.serializer` | `StringSerializer` | Simulator tự serialize JSON để kịch bản `bad-data` gửi được byte tùy ý |

Publisher sự kiện UI (`KafkaUiEventPublisher`) là ngoại lệ: `acks=1`, `enable.idempotence=false`, `linger.ms=5`, vì sự kiện là best-effort (ADR-0026) và độ trễ quan trọng hơn độ bền (DOC-33 §2.1).

Simulator ghi dòng ledger **sau khi** nhận ack của broker (callback thành công), không ghi khi gửi lỗi (DR-28, FR-13.4).

## 2. Envelope của GTFS-realtime

Mỗi message Kafka là **một entity** (một VehiclePosition hoặc một TripUpdate), bọc trong envelope (DR-03, DR-04).

```json
{
  "schema_version": 1,
  "message_id": "0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e",
  "entity_type": "VEHICLE_POSITION",
  "source": "gtfs-rt-simulator",
  "event_timestamp": "2026-09-29T21:19:05.000Z",
  "produced_at": "2026-09-29T21:19:05.412Z",
  "payload": { }
}
```

| Trường | Kiểu | Bắt buộc | Quy tắc |
| --- | --- | --- | --- |
| `schema_version` | integer | Có | Phiên bản của `payload` cho `entity_type` này. VehiclePosition: 1, 2. TripUpdate: 1 |
| `message_id` | string (UUIDv7) | Có | Sinh tại producer. Gửi lại (kịch bản `duplicates`) thì **sinh id mới**, giống hệ thống thật |
| `entity_type` | enum | Có | `VEHICLE_POSITION` \| `TRIP_UPDATE` |
| `source` | string | Có | `gtfs-rt-simulator` |
| `event_timestamp` | string (RFC 3339, UTC, `Z`, độ chính xác mili giây) | Có | Thời điểm của dữ liệu (event time) |
| `produced_at` | string (RFC 3339, UTC) | Có | Thời điểm publish |
| `payload` | object | Có | Theo JSON Schema của `(entity_type, schema_version)` |

**Quy ước thời gian (lệch GTFS-rt có chủ đích):** GTFS-rt dùng POSIX seconds, còn dự án dùng **chuỗi RFC 3339 UTC cho mọi mốc thời gian** trong envelope lẫn payload. Mục đích là chỉ cần một parser và đọc được trực tiếp khi xem DLQ hay raw zone. Ngày phục vụ (`start_date`) giữ dạng `YYYYMMDD` như GTFS.

**Quy ước tên trường:** snake_case và bám tên của GTFS-rt. JSON của API REST thì dùng camelCase (DR-39); hai quy ước này không trộn lẫn.

## 3. Payload VehiclePosition

### 3.1 Phiên bản 1

| Trường | Kiểu | Bắt buộc | Ràng buộc schema | Ghi chú |
| --- | --- | --- | --- | --- |
| `vehicle_id` | string | Có | 1–64 ký tự | Một `vehicle_id` trong `vehicles.txt`, gán cố định cho mỗi `block_id` trong ngày (DOC-13 §4, DOC-25) |
| `trip_id` | string | Có | 1–128 | Chuyến đang chạy; lúc chờ ở đầu bến thì là chuyến kế tiếp |
| `route_id` | string | Có | 1–64 | Trùng với key Kafka |
| `direction_id` | integer | Có | 0 hoặc 1 | |
| `start_date` | string | Có | `^[0-9]{8}$` | Ngày phục vụ **thật** (DR-08) |
| `lat` | number | Có | −90…90 | Kiểm tra bbox của feed là rule DQ, không phải schema |
| `lon` | number | Có | −180…180 | |
| `bearing` | number | Không | 0…360 | |
| `speed_mps` | number | Không | ≥ 0 | |
| `current_stop_sequence` | integer | Có | ≥ 0 | |
| `stop_id` | string | Có | 1–64 | Trạm ứng với `current_stop_sequence` |
| `current_status` | enum | Có | `INCOMING_AT` \| `STOPPED_AT` \| `IN_TRANSIT_TO` | |

### 3.2 Phiên bản 2

Giống hệt v1, thêm một trường tùy chọn (DR-59):

| Trường | Kiểu | Bắt buộc | Giá trị |
| --- | --- | --- | --- |
| `occupancy_status` | enum | Không | `EMPTY`, `MANY_SEATS_AVAILABLE`, `FEW_SEATS_AVAILABLE`, `STANDING_ROOM_ONLY`, `CRUSHED_STANDING_ROOM_ONLY`, `FULL`, `NOT_ACCEPTING_PASSENGERS`, `NO_DATA_AVAILABLE`, `NOT_BOARDABLE` |

Simulator phát xen kẽ v1 và v2 theo tỷ lệ `pti.sim.vehicle-position.v2-ratio` (mặc định 0,5).

### 3.3 Ví dụ

```json
{
  "schema_version": 2,
  "message_id": "0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e",
  "entity_type": "VEHICLE_POSITION",
  "source": "gtfs-rt-simulator",
  "event_timestamp": "2026-09-29T21:19:05.000Z",
  "produced_at": "2026-09-29T21:19:05.412Z",
  "payload": {
    "vehicle_id": "2050",
    "trip_id": "1361959",
    "route_id": "18",
    "direction_id": 0,
    "start_date": "20260929",
    "lat": 44.82312,
    "lon": -93.28961,
    "bearing": 335.0,
    "speed_mps": 7.8,
    "current_stop_sequence": 15,
    "stop_id": "51821",
    "current_status": "IN_TRANSIT_TO",
    "occupancy_status": "FEW_SEATS_AVAILABLE"
  }
}
```

## 4. Payload TripUpdate (phiên bản 1)

| Trường | Kiểu | Bắt buộc | Ràng buộc |
| --- | --- | --- | --- |
| `trip_id`, `route_id` | string | Có | như VehiclePosition |
| `direction_id` | integer | Có | 0 hoặc 1 |
| `start_date` | string | Có | `YYYYMMDD` |
| `vehicle_id` | string | Có | |
| `stop_time_updates` | array | Có | 1–300 phần tử, tăng dần theo `stop_sequence`, không trùng `stop_sequence` |
| `…[].stop_sequence` | integer | Có | ≥ 0 |
| `…[].stop_id` | string | Có | |
| `…[].arrival` | object | Không* | `{ "time": RFC 3339, "delay": integer giây }` |
| `…[].departure` | object | Không* | cùng cấu trúc |
| `…[].schedule_relationship` | enum | Có | `SCHEDULED` \| `SKIPPED` \| `NO_DATA` |

\* Với `SCHEDULED`, phải có ít nhất một trong `arrival` hoặc `departure`. Với `SKIPPED` và `NO_DATA`, cả hai phải vắng mặt. Ràng buộc chéo này được kiểm tra bằng Bean Validation (JSON Schema diễn đạt được bằng `if/then`, và file schema cũng có).

**Nội dung một TripUpdate** (simulator): các trạm xe **đã đi qua kể từ TripUpdate trước** (với các trạm này `arrival.time ≤ event_timestamp`, tức là giá trị quan sát được) và **tối đa 10 trạm phía trước** (giá trị dự đoán; `pti.sim.trip-update.lookahead-stops`, DR-65). ETL tách mỗi phần tử thành một dòng `fact_trip_update` và đặt `is_observed = arrival.time ≤ event_timestamp` (DR-13).

```json
{
  "schema_version": 1,
  "message_id": "0192f4a6-8a10-7d44-b0c1-22aa4c1e9f10",
  "entity_type": "TRIP_UPDATE",
  "source": "gtfs-rt-simulator",
  "event_timestamp": "2026-09-29T21:19:30.000Z",
  "produced_at": "2026-09-29T21:19:30.107Z",
  "payload": {
    "trip_id": "1361959",
    "route_id": "18",
    "direction_id": 0,
    "start_date": "20260929",
    "vehicle_id": "2050",
    "stop_time_updates": [
      { "stop_sequence": 14, "stop_id": "51631",
        "arrival": { "time": "2026-09-29T21:19:12.000Z", "delay": 192 },
        "departure": { "time": "2026-09-29T21:19:25.000Z", "delay": 205 },
        "schedule_relationship": "SCHEDULED" },
      { "stop_sequence": 15, "stop_id": "51821",
        "arrival": { "time": "2026-09-29T21:20:20.000Z", "delay": 200 },
        "schedule_relationship": "SCHEDULED" }
    ]
  }
}
```

## 5. Hợp đồng CDC (Debezium)

### 5.1 Cấu hình connector (điểm then chốt)

File đầy đủ: `deploy/connect/connectors/debezium-ticketing.json` (P1-12).

| Property | Giá trị |
| --- | --- |
| `connector.class` | `io.debezium.connector.postgresql.PostgresConnector` |
| `plugin.name` | `pgoutput` |
| `database.dbname` | `ticketing_source` |
| `topic.prefix` | `ticketing` |
| `table.include.list` | `public.ticket_transaction,public.sale_point,public.debezium_heartbeat` |
| `publication.name` / `publication.autocreate.mode` | `pti_ticketing` / `disabled`. Publication do migration của `ticketing_source` tạo (DOC-13 §5.3), nên user của Debezium không cần quyền owner trên bảng |
| `slot.name` | `pti_ticketing` |
| `snapshot.mode` | `initial` |
| `heartbeat.interval.ms` | `10000` |
| `heartbeat.action.query` | `UPDATE public.debezium_heartbeat SET ts = now() WHERE id = 1` |
| `decimal.handling.mode` | `string` (tiền không bị sai số dấu phẩy động) |
| `key.converter` / `value.converter` | `JsonConverter`, `schemas.enable=false` |
| `transforms` | `unwrap, routeSales, routeSalePoints, routeHeartbeat` |
| `transforms.unwrap.type` | `io.debezium.transforms.ExtractNewRecordState` |
| `transforms.unwrap.add.fields` | `op,lsn,source.ts_ms` |
| `transforms.unwrap.delete.tombstone.handling.mode` | `rewrite` (thay cho `delete.handling.mode` đã deprecated; tên đã kiểm ở S-04). Event delete có `__deleted = "true"` và `__op = "d"` |
| `transforms.routeSales` | `RegexRouter`: `ticketing\.public\.ticket_transaction` → `ticketing.sales.cdc` |
| `transforms.routeSalePoints` | `RegexRouter`: `ticketing\.public\.sale_point` → `ticketing.sale_points.cdc` |
| `transforms.routeHeartbeat` | `RegexRouter`: `ticketing\.public\.debezium_heartbeat` → `ticketing.heartbeat` |

### 5.2 Message sau unwrap

`ticketing.sales.cdc`, value:

```json
{
  "transaction_id": "5b0e7f2a-3c1d-4e8f-9a6b-1c2d3e4f5a6b",
  "sale_point_id": "KIOSK-017",
  "route_id": "18",
  "stop_id": "51821",
  "ticket_type": "SINGLE",
  "txn_type": "SALE",
  "amount": "2.50",
  "currency": "USD",
  "refund_of": null,
  "customer_ref": "c-000184223",
  "status": "COMPLETED",
  "created_at": "2026-09-29T21:19:02.118Z",
  "updated_at": "2026-09-29T21:19:02.118Z",
  "__op": "c",
  "__lsn": 24681357,
  "__source_ts_ms": 1790716742118,
  "__deleted": "false"
}
```

| Trường thêm | Ý nghĩa | ETL dùng thế nào |
| --- | --- | --- |
| `__op` | `r` (snapshot), `c`, `u`, `d` | `d` → `is_deleted = true`, không xóa vật lý |
| `__lsn` | LSN của thay đổi (số nguyên) | Guard: chỉ upsert khi `__lsn` mới lớn hơn `source_lsn` đang lưu (FR-03.2) |
| `__source_ts_ms` | Thời điểm commit ở DB nguồn | Dùng làm `event_timestamp` của CDC |
| `__deleted` | `"true"` khi là event delete đã rewrite | Cùng ý nghĩa với `__op=d` |

- **`customer_ref` bị loại ngay tại processor** (DR-60), trước khi ghi fact và trước khi ghi DLQ.
- Kiểu dữ liệu của Debezium (đã kiểm ở S-04 với Debezium 3.6.3 trên PostgreSQL 17.11 và 18.6): UUID → string; `NUMERIC` → string (do `decimal.handling.mode=string`, ví dụ `"2.50"`); `TIMESTAMPTZ` → chuỗi ISO-8601 UTC với micro giây (`"2026-09-28T01:12:00.459722Z"`); `__lsn` và `__source_ts_ms` → số; `__deleted` → chuỗi `"true"`/`"false"`. Test Debezium thật ở P1-12 giữ các kiểu này.
- Record CDC mang thêm các header `__debezium.context.connectorLogicalName`, `…taskId`, `…connectorName`, `…runId`. ETL bỏ qua chúng.
- Với snapshot (`__op = r`), `__lsn` là LSN của snapshot; guard vẫn đúng.

### 5.3 Thứ tự giữa hai topic CDC

`fact_ticket_sales.sale_point_id` tham chiếu `dim_sale_point`. Hai topic khác nhau nên không có thứ tự toàn cục. Quy tắc:

- Sale point lạ trong giao dịch **không** là lỗi dữ liệu: ETL tạo `dim_sale_point` tạm với `name = NULL`, `source = 'INFERRED'`; khi event của `ticketing.sale_points.cdc` tới thì ghi đè (tương tự FR-01.6).
- Refund tham chiếu tới giao dịch chưa có (`refund_of` không tồn tại) **là** lỗi `BUSINESS` (FR-02.3). Vì giao dịch gốc và refund có key khác nhau, có thể nằm ở partition khác nhau, nên rule này **cho phép trễ 5 phút**: refund tới trước gốc trong 5 phút thì được giữ lại (retry trong DLQ) thay vì loại ngay. Chi tiết ở DOC-16 (DQ-xx) và DOC-20.

## 6. Sự kiện nội bộ cho UI (`pti.events.ui`)

Best-effort, publish **sau commit** (DR-42). Payload chi tiết của từng loại ở DOC-33.

```json
{
  "id": "01J8ZK3V5Q7X2M4N6P8R0T2V4W",
  "type": "bunching.opened",
  "channel": "alerts",
  "audience": "OPERATIONS",
  "occurred_at": "2026-09-29T21:19:31.020Z",
  "source_record_ts": "2026-09-29T21:19:30.107Z",
  "route_id": "18",
  "data": { "id": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c", "routeId": "18" }
}
```

| Trường | Quy tắc |
| --- | --- |
| `id` | ULID do publisher sinh; dùng làm id sự kiện SSE (DR-41) |
| `type` | Một trong: `vehicles.batch`, `alert.created`, `alert.updated`, `alert.retracted`, `bunching.opened`, `bunching.closed`, `disruption.opened`, `disruption.closed`, `dispatch.suggested`, `job.run`, `dlq.changed` (`resync` và `heartbeat` do API tự sinh, không đi qua Kafka). Publisher của từng kiểu ở DOC-33 §3 |
| `channel` | `vehicles` \| `alerts` \| `jobs` \| `dlq` |
| `audience` | `PUBLIC` \| `OPERATIONS` \| `ENGINEERING`; API lọc theo role của kết nối |
| `source_record_ts` | Timestamp Kafka của record nguồn cũ nhất trong micro-batch sinh ra sự kiện, để đo `end_to_end_latency_seconds` (DR-57). Sự kiện không bắt nguồn từ record Kafka (ví dụ `job.run`) thì để `null` |
| `data` | Payload của từng kiểu (DOC-33 §5). Khác với envelope, khóa trong `data` là **camelCase**, vì API chuyển nguyên văn sang SSE |
| key Kafka | `vehicles.batch`: `route_id`; insight: id của episode (`dispatch.suggested`: id bunching); `alert.*`: id alert; `job.run`: `job:<jobExecutionId>`; `dlq.changed`: `source` khi `CREATED` hoặc `BULK_UPDATED`, `dead_letter.id` khi `UPDATED` |

`vehicles.batch` do etl-stream phát mỗi giây cho mỗi consumer thread. Nó gom vị trí mới nhất của các xe trong những micro-batch đã commit giây đó.

## 7. Raw zone

Mọi topic nguồn (`gtfs.*`, `ticketing.sales.cdc`, `ticketing.sale_points.cdc`) được S3 sink ghi vào bucket `raw` **nguyên văn từng byte** (ADR-0012, DR-81). Cấu hình đã kiểm ở spike S-04.

- **Đường dẫn:** `raw/<topic>/dt=<YYYY-MM-DD>/hh=<HH>/<topic>-<partition>-<start_offset>.json.gz`. `start_offset` đệm 20 chữ số và bằng offset của dòng đầu tiên trong file. Giờ tính theo **timestamp của record** (CreateTime), theo UTC; mọi dòng của một file thuộc cùng một giờ.
- **Mỗi dòng** là một object JSON (thứ tự trường do connector quyết định):
  ```json
  {"headers":[{"key":"schema_version","value":"2"},{"key":"entity_type","value":"VEHICLE_POSITION"}],"offset":918273,"value":"eyJzY2hlbWFfdmVyc2lvbiI6Miwi…","key":"18","timestamp":"2026-09-29T21:19:05.412Z"}
  ```

| Trường | Kiểu | Ghi chú |
| --- | --- | --- |
| `key` | string hoặc `null` | Key của record, giải mã UTF-8 (`StringConverter`). CDC: chuỗi JSON như `{"transaction_id":"…"}` |
| `value` | string | **Base64** của đúng các byte value (`ByteArrayConverter`). Giữ được JSON hỏng của kịch bản `bad-data`, cả byte không phải UTF-8 và `0x00` (DOC-20 §4.1), nên replay tái tạo đúng lỗi (FR-01.4). `null` khi value rỗng (tombstone; hệ thống không phát tombstone) |
| `offset` | số | Offset Kafka |
| `timestamp` | string | CreateTime, ISO-8601 UTC có mili giây. Không phải epoch millis |
| `headers` | mảng `{key, value}` | Value của header giải mã UTF-8. Record của Debezium có thêm bốn header `__debezium.context.*` |

- **Không có trường `partition`.** Reader lấy partition từ tên file (`<topic>-<partition>-<start_offset>.json.gz`).
- Dòng cuối của file **không** có ký tự xuống dòng; đọc theo dòng (`BufferedReader.readLine`) vẫn đúng, nhưng không được nối nhiều file lại rồi mới tách dòng.
- **Rotate:** Aiven 3.4.3 bắt đầu file mới trên mỗi partition sau mỗi 10 giây có dữ liệu (hằng số của connector), hoặc khi một file đạt 2.000 record. File được upload xong ở lần commit kế tiếp: mỗi 30 giây (`offset.flush.interval.ms = 30000` ở worker, DOC-39 §3.4), hoặc sớm hơn khi một file đạt 2.000 record. Sau tối đa khoảng 40 giây, mọi record đã có trong object (DR-89).
- **At-least-once:** sau khi connector restart, một offset có thể xuất hiện trong hai object. Replay khử trùng theo `(topic, partition, offset)` (DOC-22).
- File GTFS static: `raw/gtfs-static/<feed_hash>.zip`, ghi bởi `GtfsStaticLoadJob`.

Cấu hình connector (`deploy/connect/connectors/pti-raw-sink.json`, P1-13):

| Property | Giá trị |
| --- | --- |
| `connector.class` | `io.aiven.kafka.connect.s3.AivenKafkaConnectS3SinkConnector` |
| `tasks.max` | `1` |
| `topics` | `gtfs.vehicle_positions,gtfs.trip_updates,ticketing.sales.cdc,ticketing.sale_points.cdc` |
| `key.converter` / `header.converter` | `org.apache.kafka.connect.storage.StringConverter` |
| `value.converter` | `org.apache.kafka.connect.converters.ByteArrayConverter` |
| `aws.access.key.id` / `aws.secret.access.key` | `${env:S3_CONNECT_ACCESS_KEY}` / `${env:S3_CONNECT_SECRET_KEY}` |
| `aws.s3.bucket.name` / `aws.s3.endpoint` / `aws.s3.region` | `raw` / `http://seaweedfs:8333` / `us-east-1` (SeaweedFS bỏ qua region nhưng SDK bắt buộc có) |
| `file.name.template` | `{{topic}}/dt={{timestamp:unit=yyyy}}-{{timestamp:unit=MM}}-{{timestamp:unit=dd}}/hh={{timestamp:unit=HH}}/{{topic}}-{{partition}}-{{start_offset:padding=true}}.json.gz` |
| `file.name.timestamp.source` / `file.name.timestamp.timezone` | `EVENT` (CreateTime; mặc định `WALLCLOCK` là giờ xử lý) / `UTC` |
| `file.compression.type` | `gzip` |
| `file.max.records` | `2000`. **Không tăng**: mỗi file đang mở giữ một buffer part trên heap, và file lớn hơn part size bị SeaweedFS từ chối (DR-81, DR-89) |
| `format.output.type` | `jsonl` |
| `format.output.fields` | `key,value,offset,timestamp,headers` |
| `format.output.fields.value.encoding` | `base64` |
| `aws.s3.part.size.bytes` | `1048576` (1 MiB). File 2.000 record nhỏ hơn khoảng 400 KB nên luôn nằm trong một part; SeaweedFS chỉ từ chối part nhỏ hơn 5 MiB khi file có nhiều part (DR-89) |

Topic phải tồn tại trước khi đăng ký connector (`kafka-init` chạy trước `kafka-connect-init`). Sink đăng ký trước khi topic có chỉ thấy topic sau lần làm mới metadata kế tiếp của consumer.

Replay theo khoảng `[from, to)` chọn các object trong các thư mục `hh` giao với khoảng, rồi lọc từng dòng theo `timestamp` (DOC-22).

## 8. Kafka headers

| Header | Producer | Giá trị | Consumer dùng để |
| --- | --- | --- | --- |
| `traceparent` | mọi producer (observation của Spring Kafka), **chỉ khi bật tracing** (`PTI_TRACING_ENABLED=true`; compose mặc định tắt, `make up-obs` bật, DOC-39 §3.2). Khi tắt, record không có header này, như ví dụ ở §7 | W3C trace context | Nối trace simulator → etl → api (DR-50). Consumer không được giả định header này luôn có |
| `schema_version` | simulator | `"1"` \| `"2"` | Chỉ để quan sát và lọc trong công cụ; **ETL đọc version từ envelope**, không tin header |
| `entity_type` | simulator | `VEHICLE_POSITION` \| `TRIP_UPDATE` | Như trên |

## 9. Payload hash và business key

**Payload hash** (DR-04, dùng cho `dedup_registry` và `payload_hash` trong fact):

1. Dựng object `{schema_version, entity_type, event_timestamp, payload}` (không có `message_id`, `produced_at`, `source`).
2. Chuẩn hóa `event_timestamp` về dạng `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
3. Serialize theo **RFC 8785 (JSON Canonicalization Scheme)**: sắp key, không khoảng trắng, chuẩn hóa số.
4. `SHA-256`, biểu diễn hex chữ thường (64 ký tự).

Với CDC: hash tính trên value sau unwrap, **bỏ** `__lsn`, `__source_ts_ms` và `customer_ref`.

Test bắt buộc (`common`): cùng dữ liệu nhưng khác thứ tự key, khác khoảng trắng, khác `message_id` thì cho **cùng** hash; khác một trường của payload thì cho **khác** hash.

| Entity | Business key | Bảng đích |
| --- | --- | --- |
| VehiclePosition | `(vehicle_id, event_timestamp)` | `dw.fact_vehicle_position` |
| TripUpdate, mỗi phần tử `stop_time_updates` | `(service_date, trip_id, stop_sequence)`, với `service_date = start_date` | `dw.fact_trip_update` |
| Giao dịch vé | `(sale_date, transaction_id)`, với `sale_date` = ngày của `created_at` theo giờ agency | `dw.fact_ticket_sales` |
| Điểm bán | `sale_point_id` | `dw.dim_sale_point` |

## 10. Quy tắc thay đổi hợp đồng

1. **File JSON Schema đã phát hành thì không sửa.** Mọi thay đổi payload (kể cả thêm trường tùy chọn) đều tạo `schema_version` mới. Schema đặt `additionalProperties: false`; ETL bật `FAIL_ON_UNKNOWN_PROPERTIES`, nên trường lạ sẽ đưa message vào DLQ `SCHEMA`.
2. Consumer hỗ trợ **N và N−1**. Version lạ thì vào DLQ `SCHEMA` (FR-01.5).
3. **Triển khai consumer trước, producer sau.** Producer chỉ chuyển sang phát version mới khi mọi consumer đang chạy đã hỗ trợ version đó.
4. Bỏ một version cũ: ngừng phát ở producer, chờ hết retention Kafka (7 ngày) **và** không còn replay raw zone nào cần tới, rồi mới xóa khỏi consumer. Vì raw zone giữ lâu hơn, trên thực tế **parser của mọi version cũ được giữ lại**, chỉ ngừng hỗ trợ khi có ADR.
5. Hợp đồng CDC thay đổi theo schema DB nguồn. Mọi migration của `ticketing_source` phải kèm test Debezium thật (DR-44).
6. Sự kiện UI: thêm trường thì không cần đổi version (client bỏ qua trường lạ); đổi nghĩa hoặc xóa trường thì phải đổi tên `type`.

File schema: `backend/common/src/main/resources/schemas/{vehicle-position.v1,vehicle-position.v2,trip-update.v1,envelope}.schema.json` (JSON Schema draft 2020-12). Test của simulator và ETL dùng chung các file này (DR-44).
