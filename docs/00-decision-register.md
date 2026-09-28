# Sổ quyết định mở (Decision Register)

> Trạng thái tài liệu: **Approved**, toàn bộ DR đã chốt ngày 2026-09-26 · Cập nhật: 2026-09-28 · Nguồn: phân tích `public-transport-intelligence.md` (gọi tắt là **SDD gốc**)

Tài liệu gốc mô tả tốt *cái gì* và *vì sao*, nhưng còn nhiều chỗ chưa trả lời *chính xác như thế nào*. Nếu không chốt trước, người triển khai sẽ phải dừng lại hỏi hoặc tự đoán, và đoán sai ở tầng dữ liệu thì rất tốn công sửa. Sổ này liệt kê từng khoảng trống, mỗi mục kèm **một phương án đề xuất** để có thể duyệt nhanh.

**Cách dùng**

- Mỗi mục có trạng thái: `Đề xuất` → (người sở hữu duyệt) → `Chốt` hoặc `Đổi` (ghi phương án thay thế).
- Mục nào đã chốt thì chuyển vào tài liệu đích (cột *Ghi vào*). Mục ở cấp kiến trúc sẽ thành ADR trong `docs/04-adr/`.
- Mục đánh dấu **⚠ lệch SDD gốc** là chỗ đề xuất khác với tài liệu gốc; lý do được ghi kèm.
- Mục đánh dấu **🔬 spike** cần thử nghiệm ngắn (≤ 1 ngày) trước khi chốt.

## Nhật ký chốt

| Ngày | Người chốt | Nội dung | Mục bị ảnh hưởng |
| --- | --- | --- | --- |
| 2026-09-26 | Owner | **Phạm vi: làm đầy đủ**, không cắt module nào. Thứ tự cắt giảm chỉ giữ lại làm phương án dự phòng | Master plan §4.3 |
| 2026-09-26 | Owner | **Hạ tầng: chạy full stack** (Keycloak, observability đầy đủ), không dùng bản rút gọn | DR-40, DR-50 |
| 2026-09-26 | Owner | **Phiên bản: dùng bản mới nhất** → Spring Boot 4.1.x | DR-53 |
| 2026-09-26 | Owner | **Ngôn ngữ: tài liệu `docs/` viết tiếng Việt, mọi thứ khác dùng tiếng Anh** (UI, code, comment, log, commit, PR, thông báo lỗi API) | DR-48, DR-61 (mới) |
| 2026-09-26 | Owner | **Repo: GitHub private** (đã đổi thành public ngày 2026-09-27) | DR-56 |
| 2026-09-26 | Owner | **Jev: dùng TypeSafe Jev qua Java SDK của spring-ai-community** (theo bài blog Spring 2026-09-21) | DR-36, DR-37 |
| 2026-09-26 | Owner | **ETL dùng Spring Batch (job batch) và Spring Kafka (streaming) thay cho engine chunk tự xây**; tận dụng hệ sinh thái Spring (ShedLock, Spring Cloud AWS S3, Micrometer Tracing) | ADR-0002, DR-15, 16, 21–24, 26, 27, 43, 50, 53, 62, 63 (mới) |
| 2026-09-26 | Owner | **Feed GTFS: một thành phố nước ngoài cỡ vừa** → sau spike S-02 chốt Metro Transit, Minneapolis–St. Paul | DR-01, 06, 09, 11, 48 |
| 2026-09-26 | Owner | **Chấp nhận toàn bộ các đề xuất còn lại.** Mọi DR chuyển sang trạng thái Chốt; các điểm 🔬 vẫn cần spike để xác minh chi tiết, nhưng hướng đi đã cố định | Tất cả |
| 2026-09-26 | Owner | **Bổ sung khi viết tài liệu Phase 0:** DR-63 (`batch_id` ở hai chế độ), DR-64 (bố cục database/schema), DR-65 (giới hạn trạm trong TripUpdate); điều chỉnh số liệu DR-15 và TTL của DR-16 theo số đo trên feed | DR-15, 16, 20, 63, 64, 65 |
| 2026-09-26 | Owner | **Chấp nhận các quyết định phát sinh khi viết mô hình dữ liệu** (DOC-13, 14, 15, 17): role tạo ở bootstrap superuser, Flyway chỉ tạo object và grant, `R__grants.sql` revoke quyền database của PUBLIC; mọi migration thêm object phải sửa `R__grants.sql`; headway theo trạm tham chiếu; gán xe theo nửa đội xe chẵn/lẻ ngày; ledger giữ 2 ngày; `dedup_registry` UNLOGGED; `REPLICA IDENTITY FULL`; khóa disruption gồm `direction_id`; autovacuum theo partition cho `fact_trip_update`, fillfactor 50 cho `vehicle_position_latest`; cửa sổ `GET /ops/job-runs` tối đa 24 giờ; retention mặc định (DOC-15 §3.1, DOC-29 §3.3); bảng giá và mã điểm bán mô phỏng; máy dev cần 80 GB ổ trống | DR-02, 06, 09, 12, 16, 17, 18, 28, 29, 64, 65 |
| 2026-09-26 | Claude (Owner ủy quyền) | **Object storage: SeaweedFS thay MinIO** sau spike S-03, vì image MinIO không còn phát hành | DR-66 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Đồng hồ nghiệp vụ** `pti.clock.offset` dùng chung cho simulator, etl, api (thay `pti.sim.time-offset`), vì giờ Chicago lệch 12–13 giờ so với Việt Nam; **tạo tải gấp N lần bằng tần suất phát**, không nhân bản xe | DR-08, DR-67, DR-68 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Quy tắc gán stage DLQ** theo loại rule (SCHEMA / QUALITY / BUSINESS / DEDUP); bản cũ hơn của cùng key trong một chunk được gộp, không vào DLQ | DR-69, FR-02.3, FR-02.4 |
| 2026-09-27 | Claude (Owner ủy quyền) | Làm rõ DR-22: analytics nhận `MicroBatchCommitted` bằng `@EventListener` + `@Async` vì sự kiện được phát sau khi transaction đã commit | DR-22, DOC-19 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Replay raw zone theo giờ record Kafka**; DLQ idempotent theo vị trí Kafka; replay thành công tự đóng dead letter; `GtfsStaticLoadJob` định danh bằng `runKey` | DR-70 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Analytics chạy theo lưới event time có con trỏ trong DB** (bunching 15 giây, gián đoạn bucket 1 phút, ticketing cửa sổ 15 phút), để trực tiếp và tính lại cho cùng kết quả; làm rõ DR-30 (thời điểm leader qua trạm lấy từ lịch sử vị trí); `AnalyticsRecomputeJob` | DR-30, DR-31, DR-34, DOC-23 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Metric thay cho exporter** (độ tươi nguồn phát từ `api`, WAL của slot từ simulator, trạng thái connector từ `etl-stream`); DR-57 chia thành bốn histogram theo chặng; chốt số runbook | DR-71, DR-57 |
| 2026-09-27 | Claude (Owner ủy quyền) | **API, SSE và bảo mật (tài liệu P4):** bỏ tiền tố `/ops/**`, mọi endpoint vận hành nằm dưới `/etl/**`; `/vehicles/live` là snapshot không phân trang (ngoại lệ FR-10.3); `api` cũng publish lên `pti.events.ui` và có thêm kiểu `alert.retracted`; replay estimate dựa trên nhật ký micro-batch vì API không có credential S3; SSE phát bù qua pod khác nhờ mỗi pod nạp sẵn buffer từ Kafka; token frontend chỉ giữ trong bộ nhớ; `anyRequest().denyAll()` cho endpoint chưa khai báo; k3d `lite` dùng JWT khóa tĩnh | DR-40–45, FR-10.3, DOC-26, 27, 31, 32, 33 |
| 2026-09-27 | Claude (Owner ủy quyền) | **UX/UI (tài liệu P5):** SPA React + Vite, route và bộ lọc nằm trên URL; token chỉ trong bộ nhớ với `signinSilent`; cấu hình runtime qua `env.js` do entrypoint nginx render; bản đồ nền PMTiles cục bộ (`make tiles`), dev dùng OpenFreeMap; toast cho phản hồi gợi ý điều phối nhưng không cho ack; `link` của alert do API tính theo bảng cố định và có cả trong SSE; catalog kịch bản simulator có định dạng tham số để UI sinh form; `requested_by` của simulator là `user:<username>`; màn Controls tách riêng khỏi Jobs; E2E cần dữ liệu cũ chạy trong Playwright project `late` | DR-46–49, DOC-34–37, ADR-0020, ADR-0021 |
| 2026-09-27 | Claude (Owner ủy quyền) | **AI triage (tài liệu P6):** lease kiêm backoff; hành động do bảng quyết định và guard trong code sở hữu; triage-worker không ghi `alert_event`, cảnh báo DLQ qua Prometheus; luật chặn cuối bằng `DlqRuleClassifier` trong etl; demo auto-replay bằng kịch bản `late-delivery` và `PTI_DQ_MAX_CLOCK_SKEW=5m`; định dạng `model_version`; KEDA PostgreSQL scaler cho triage-worker (1→3); virtual thread cho `api` và `triage-worker` | DR-37, DR-72, DR-73, DR-74, ADR-0018, ADR-0019 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Kubernetes và CI (tài liệu P7–P8):** API đọc qua JDBC nhiều host (`-ro` rồi `-rw`) để không mất đọc khi failover; `etl-stream` tối đa 4 pod; CI toàn stack chạy tuần một lần trên self-hosted runner (sau đó đổi sang runner GitHub hằng đêm khi repo chuyển public); test k3d mang mã `KD-xx` | DR-75, DR-76 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Demo và báo cáo (tài liệu P8):** demo hai phần (compose rồi k3d dựng sẵn và dừng), kịch bản bunching/gián đoạn gieo trước; lệnh `make` vận hành dùng chung cho k3d qua `PTI_ENV`; báo cáo lấy số liệu duy nhất từ `pti-exp report` | DR-77, DR-78, DR-79 |
| 2026-09-27 | Owner | **Repo chuyển sang GitHub public** (thay quyết định private): runner chuẩn của GitHub đủ 16 GB để chạy E2E và k3d, không cần self-hosted runner; image GHCR để public | DR-56, DR-76 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-06 xong:** Boot 4.1.1 + Java 25 dùng được với mọi thư viện đã chọn, không cần lối lui. Chunk step của job batch dựng bằng builder fault-tolerant cũ của Spring Batch 6, vì `ChunkOrientedStep` mới làm mất DLQ và bỏ sót item khi crash giữa lúc scan | DR-53, DR-80 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-04 xong:** image Connect = Debezium 3.6.3 + Aiven S3 sink 3.4.3. Raw zone lưu value dạng base64 để giữ đúng từng byte; thư mục giờ theo CreateTime; `file.max.records=2000` và `mem_limit` 1.280 MB để S3 sink không OOM khi chạy bù. Debezium chạy được trên PostgreSQL 18.6; vẫn dùng 17.11 tới khi kiểm xong CNPG | DR-81, DR-53, DR-66 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-05 xong:** bản đồ nền Twin Cities 84 MB, render offline không có request ra ngoài. Dùng MapLibre 6 (worker cùng origin, CSP không cần `blob:`); font và sprite tải bằng `make tiles` thay vì commit vào repo | DR-47, DR-82 |

---

## A. Dữ liệu nguồn và hợp đồng message

### DR-01 · Chọn feed GTFS static nào — **Chốt: Metro Transit (Minneapolis–St. Paul)**
- **Kết quả spike S-02** (2026-09-26): đã tải và đo 7 feed. Script đo nằm ở `sample-data/gtfs/profile_feed.py`.

  | Feed | Tuyến | Trạm | Xe đồng thời cao điểm¹ | `shape_dist_traveled` | `block_id` | Ghi chú |
  | --- | --- | --- | --- | --- | --- | --- |
  | **Metro Transit, Minneapolis** | 127 | 8.155 | **472 chuyến / 606 xe** | 100% | 100% | Tiếng Anh, public domain, có `vehicles.txt` (sức chứa) |
  | OC Transpo, Ottawa | 182 | 5.782 | 437 | 100% | ~100% | Tiếng Anh, Open Government Licence; `stop_times` 2,7 triệu dòng (nặng) |
  | Edmonton ETS | 242 | 6.580 | 700 | 0% | 100% | Không có `calendar.txt`; 2,8 triệu dòng |
  | Adelaide Metro | 701 | 9.029 | 715 | 100% | 100% | Quá nhiều tuyến (có 164 tuyến xe học sinh) |
  | Metlink, Wellington | 245 | 3.131 | 372 | 0% | 0% | Không có block, không có shape_dist |
  | Nysse, Tampere | 105 | 3.423 | 264 | 0% | 100% | Tên trạm tiếng Phần Lan |
  | Metro Transit, Madison | 29 | 1.662 | 92 | 100% | 100% | Quá nhỏ |
  | TriMet, Portland | — | — | — | — | — | Không kết nối được từ máy chạy spike |

  ¹ Số chuyến đang chạy vào ngày thường. Với Minneapolis, số xe đang phục vụ (tính theo `block_id`, gồm cả thời gian chờ đầu bến) là 606.
- **Quyết định:** dùng **Metro Transit (Minneapolis–St. Paul, MN, Mỹ)**, bản chụp ngày 2026-09-26, lưu tại `sample-data/gtfs/metrotransit-mn-20260926.zip` (có SHA-256 trong `SHA256SUMS` và thông số trong `README.md` cùng thư mục).
- **Lý do:**
  1. Tải cao điểm khoảng 470–600 xe, đúng với "tải nền 500 xe" của EXP-07 mà không phải nhân bản chuyến.
  2. `shape_dist_traveled` đủ 100%, nên thuật toán bunching tính tiến độ trên tuyến trực tiếp (DR-30).
  3. `block_id` đủ 100%, nên simulator gán `vehicle_id` theo block và một xe chạy nối các chuyến như ngoài thực tế.
  4. Có `vehicles.txt` với sức chứa, dùng để nạp `dim_vehicle.capacity` và ước lượng tải khách cho gợi ý điều phối (SDD 9.5).
  5. Tên tiếng Anh, khớp với UI tiếng Anh (DR-61). License là public domain (Minnesota Government Data Practices Act).
  6. Kích thước vừa phải: zip 19 MB, khoảng 873 nghìn dòng `stop_times`.
- **Hệ quả:**
  - Múi giờ `America/Chicago` có DST, và DST kết thúc ngày 2026-11-01, nên test DR-09 phải có ca chuyển giờ.
  - Tiền tệ đổi sang USD (DR-06). Ngày lễ theo lịch Mỹ và bang Minnesota (DR-11). Locale là `en-US` (DR-48).
  - Vùng bản đồ cho PMTiles (S-05) có bbox `-93.730, 44.707, -92.806, 45.330`.
  - Có 3 tuyến light rail (`route_type=0`). Simulator vẫn cho chạy; phát hiện bunching mặc định chỉ áp cho bus (cấu hình được).
  - Lịch trong feed hết hạn ngày 2026-11-13, nên ánh xạ ngày ở DR-08 là bắt buộc.
- **Ghi vào:** DOC-13, ADR-0009.

### DR-02 · Dùng những file GTFS nào
- **Vấn đề:** SDD gốc chỉ liệt kê routes, stops, trips, stop_times, calendar. Tuy vậy bunching cần biết xe đi cùng chiều (`trips.direction_id`) và vị trí trên tuyến (`shapes.txt`); bản đồ cần hình dạng tuyến; lịch chạy cần tính cả ngày ngoại lệ (`calendar_dates.txt`).
- **Quyết định:** Bắt buộc có `agency, routes, stops, trips, stop_times, calendar, calendar_dates, shapes`. Dùng thêm `feed_info` và `frequencies` nếu feed có. Các file còn lại bỏ qua.
- *Điều chỉnh 2026-09-26 (theo feed Metro Transit đã chốt ở DR-01):* feed **không có** `frequencies.txt`. Feed có `vehicles.txt` (1.233 xe buýt, có sức chứa), nên dùng thêm file này để nạp `dim_vehicle` và để simulator gán xe cho từng `block_id` (DOC-13 §4). `levels`, `pathways`, `linked_datasets` và các file còn lại vẫn bỏ qua.
- **Ghi vào:** DOC-13.

### DR-03 · Định dạng GTFS-realtime trên Kafka — ⚠ lệch SDD gốc (làm rõ)
- **Vấn đề:** GTFS-rt chuẩn dùng Protobuf `FeedMessage` (một snapshot chứa nhiều entity). SDD gốc lại nói validate bằng Bean Validation trên DTO và có `schema_version`, tức ngầm hiểu dữ liệu là JSON.
- **Quyết định:** Simulator publish **JSON, mỗi entity một message** (một VehiclePosition hoặc một TripUpdate) bọc trong envelope (DR-04). Tên trường bám GTFS-rt (snake_case). Có JSON Schema cho từng `schema_version`. Protobuf nằm ngoài phạm vi và được ghi lý do trong ADR: dễ debug, dễ bơm dữ liệu lỗi, và contract test đơn giản hơn.
- **Ghi vào:** ADR-0007, DOC-09.

### DR-04 · Cấu trúc envelope
- **Quyết định:**
  ```json
  {
    "schema_version": 1,
    "message_id": "0192f4a6-…",            // UUID v7, sinh tại producer
    "entity_type": "VEHICLE_POSITION",       // | TRIP_UPDATE
    "source": "gtfs-rt-simulator",
    "event_timestamp": "2026-09-26T01:15:30Z", // thời điểm của dữ liệu (event time)
    "produced_at": "2026-09-26T01:15:30.412Z",  // thời điểm publish
    "payload": { … }
  }
  ```
  - VehiclePosition payload: `vehicle_id, trip_id, route_id, direction_id, start_date, lat, lon, bearing, speed_mps, current_stop_sequence, stop_id, current_status`.
  - TripUpdate payload: `trip_id, route_id, direction_id, start_date, vehicle_id, stop_time_updates[{stop_sequence, stop_id, arrival:{time, delay}, departure:{time, delay}, schedule_relationship}]`.
  - **Hash dedup chỉ tính trên `schema_version + entity_type + event_timestamp + payload` đã chuẩn hóa** (canonical JSON). Không đưa `message_id` và `produced_at` vào hash, để một message bị gửi lại vẫn cho ra cùng hash.
  - Kafka headers: `traceparent` (W3C), `schema_version`.
- **Ghi vào:** DOC-09, JSON Schema trong `common/src/main/resources/schemas/`.

### DR-05 · Danh sách topic, key, partition
- **Quyết định:**

  | Topic | Key | Partition | Retention | Ghi chú |
  | --- | --- | --- | --- | --- |
  | `gtfs.vehicle_positions` | `route_id` | 12 | 7 ngày | delete |
  | `gtfs.trip_updates` | `route_id` | 12 | 7 ngày | delete |
  | `ticketing.sales.cdc` | `transaction_id` (PK) | 6 | 7 ngày | Debezium route bằng RegexRouter |
  | `pti.events.ui` | entity id | 3 | 1 ngày | Sự kiện nội bộ cho SSE (DR-41) |
  | `connect-*` | — | mặc định | — | Topic nội bộ của Kafka Connect |

  Dev: RF = 1. k3d: RF = 3, `min.insync.replicas = 2`. Tạo topic bằng script khởi tạo (compose) và bằng `KafkaTopic` CR (k8s), không bật auto-create.
- **Ghi vào:** DOC-09, ADR-0008.

### DR-06 · Schema database nguồn ticketing
- **Vấn đề:** SDD gốc chưa định nghĩa schema này.
- **Quyết định:** Database `ticketing_source`, schema `public`:
  - `sale_point(sale_point_id TEXT PK, name, kind KIOSK|ONBOARD|APP, stop_id NULL, created_at)` — *bổ sung 2026-09-26:* thêm `route_id NULL` (bắt buộc với `ONBOARD`) và `updated_at`; `sale_point_id` theo mẫu `^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$`. Cả hai bảng đặt `REPLICA IDENTITY FULL` để event xóa vẫn mang `created_at` (cần cho `sale_date`). DDL đầy đủ ở DOC-13 §5.
  - `ticket_transaction(transaction_id UUID PK, sale_point_id FK, route_id NULL, stop_id NULL, ticket_type SINGLE|DAY|MONTH, txn_type SALE|REFUND, amount NUMERIC(10,2) CHECK ≥ 0, currency 'USD' (giá vé mô phỏng theo bảng giá của Metro Transit, ví dụ $2.00/$2.50, day pass $5.00), refund_of UUID NULL, customer_ref TEXT, status COMPLETED|VOIDED, created_at, updated_at)`
  - `customer_ref` là dữ liệu cá nhân mô phỏng. ETL bỏ trường này ngay khi đọc, không nạp vào warehouse và không gửi sang Jev (DR-60).
- **Ghi vào:** DOC-13, migration `db/src/main/resources/db/migration/ticketing/`.

### DR-07 · Định dạng event CDC
- **Quyết định:** Debezium PostgreSQL connector, plugin `pgoutput`, publication chỉ gồm `ticket_transaction` (và `sale_point` nếu cần dimension), `snapshot.mode=initial`. Dùng SMT `ExtractNewRecordState` (unwrap) với `add.fields=op,lsn,source.ts_ms` và `delete.handling.mode=rewrite`, sau đó `RegexRouter` để đổi tên topic thành `ticketing.sales.cdc`. ETL chỉ upsert khi `lsn` mới hơn bản đang lưu. Gặp `op=d` thì đánh dấu `is_deleted=true`, không xóa vật lý.
- **Ghi vào:** DOC-09, `connect/connectors/debezium-ticketing.json`.

### DR-08 · Đồng hồ mô phỏng và ngày phục vụ
- **Vấn đề:** Lịch trong feed thật thường đã hết hạn. Simulator cần biết "hôm nay" ứng với ngày nào trong feed.
- **Quyết định:** Simulator chạy **thời gian thực** (1 giây mô phỏng = 1 giây thật) để NFR-03 có ý nghĩa. Ngày thật được ánh xạ sang một ngày trong khoảng hiệu lực của feed có cùng thứ trong tuần (cấu hình `pti.sim.service-date-mapping=auto|fixed:<date>`). Trường `start_date`/`service_date` phát ra là **ngày thật**, còn ánh xạ chỉ dùng để chọn chuyến. Ngoài ra có tham số `pti.sim.time-offset` để demo giờ cao điểm vào bất kỳ lúc nào.
  - *Sửa 2026-09-27:* offset chỉ áp cho simulator sẽ làm lệch dữ liệu với lịch trong warehouse (headway theo giờ, rule DQ so `event_timestamp` với giờ hiện tại). Thay bằng đồng hồ nghiệp vụ dùng chung `pti.clock.offset` (DR-67).
- **Ghi vào:** DOC-25.

### DR-09 · Múi giờ và giờ GTFS vượt 24:00
- **Quyết định:** Mọi cột thời điểm trong DB dùng `TIMESTAMPTZ`, lưu UTC. `service_date` là `DATE` theo múi giờ của agency (`agency_timezone`). Giờ GTFS dạng `25:10:00` được quy đổi bằng công thức **`(service_date 12:00 local) − 12h + offset`** đúng như đặc tả GTFS ("noon minus 12h"). *Sửa 2026-09-26:* bản trước ghi `service_date 00:00 local + offset`, công thức này sai vào ngày chuyển giờ: ngày 2026-11-01, "08:00:00" phải ra 14:00Z (08:00 CST), còn cách cũ cho 13:00Z. Bảng ví dụ kiểm thử ở DOC-13 §3. Phần tính toán này đặt trong `common` và **bắt buộc có unit test cho ngày chuyển giờ**, vì feed đã chọn dùng `America/Chicago` và DST kết thúc ngày 2026-11-01. `hour_of_day` và `day_of_week` trong analytics đều tính theo giờ địa phương của agency.
- **Ghi vào:** DOC-13, DOC-14.

---

## B. Mô hình dữ liệu

### DR-10 · Phiên bản hóa GTFS static (dimension)
- **Vấn đề:** SDD gốc có hai yêu cầu: "đổi phiên bản trong một transaction" và "watermark theo phiên bản feed". Tuy nhiên chưa nói dimension có giữ lịch sử hay không.
- **Quyết định:**
  - Bảng `gtfs_feed_version(feed_version_id BIGSERIAL, feed_hash CHAR(64) UNIQUE, source_uri, valid_from, valid_to, status STAGED|ACTIVE|RETIRED|REJECTED, loaded_at, validation_report JSONB)` cùng partial unique index bảo đảm chỉ có một bản `ACTIVE`.
  - Mọi bảng `dim_*` và bảng lịch (`gtfs_trip`, `gtfs_stop_time`, `gtfs_shape`, `route_headway`) đều có `feed_version_id`, PK là `(feed_version_id, <natural id>)`. Kèm view `*_current` lọc theo bản ACTIVE.
  - Job nạp feed vào với status `STAGED`, validate toàn bộ, sau đó trong **một transaction** đổi bản cũ sang `RETIRED` và bản mới sang `ACTIVE`. Chỉ giữ 3 phiên bản gần nhất.
  - Bảng fact giữ natural id (`route_id TEXT`…) như SDD gốc, không dùng surrogate key, cho đơn giản và khớp với các bảng insight.
- **Ghi vào:** ADR-0009, DOC-14, DOC-21.

### DR-11 · `dim_time` → `dim_date`
- **Quyết định:** Thay `dim_time` bằng `dim_date(date_key INT yyyymmdd, date, day_of_week, is_weekend, is_holiday, holiday_name, day_type WEEKDAY|SATURDAY|SUNDAY_HOLIDAY)`, sinh sẵn cho 2024–2030 trong migration. Danh sách ngày lễ lấy từ file cấu hình; mặc định là ngày lễ liên bang Mỹ theo lịch của Metro Transit (New Year, Memorial Day, July 4, Labor Day, Thanksgiving, Christmas…). Giờ trong ngày suy ra từ timestamp, không cần bảng riêng. **⚠ lệch SDD gốc** (chỉ đổi tên và grain).
- **Ghi vào:** DOC-14.

### DR-12 · Headway theo lịch
- **Vấn đề:** Một cột "headway" duy nhất trong `dim_route` là không đủ, vì headway thay đổi theo chiều, giờ và loại ngày.
- **Quyết định:** Thêm bảng `route_headway(feed_version_id, route_id, direction_id, day_type, hour_of_day, scheduled_headway_seconds, trip_count)`, tính khi nạp feed từ giờ xuất phát tại trạm đầu (lấy median của hiệu hai chuyến liên tiếp). `dim_route.typical_headway_seconds` chỉ dùng để hiển thị.
- *Điều chỉnh 2026-09-26 (đo trên feed):* **không dùng trạm đầu** mà dùng **trạm tham chiếu**: trạm được nhiều chuyến của `(route, direction)` phục vụ nhất trong một ngày đại diện. Lý do: tuyến có nhánh bắt đầu ở nhiều trạm khác nhau (tuyến 18 có 4 trạm đầu), nên hiệu giờ xuất phát tại "trạm đầu" của từng chuyến lẫn các nhánh với nhau và cho headway sai. Với trạm tham chiếu, kết quả khớp lịch công bố: Blue Line 12 phút, Green Line 12, A Line 10, tuyến 18 10, tuyến 5 60. Headway là NULL khi giờ đó có ít hơn 2 chuyến (380/8.177 dòng). SQL ở DOC-14 §6.
- **Ghi vào:** DOC-14, DOC-21.

### DR-13 · Ngữ nghĩa `fact_trip_update`: dự đoán hay thực tế
- **Vấn đề:** Một TripUpdate chứa cả giờ đã qua (thực tế) lẫn giờ dự đoán cho các trạm phía trước. Nếu ETA và OTP lấy trung bình trên cả dự đoán thì kết quả sẽ sai.
- **Quyết định:** Mỗi dòng ứng với `(trip_id, stop_sequence, service_date)`. Các cột gồm `route_id, direction_id, stop_id, vehicle_id, scheduled_arrival, arrival_time, delay_seconds, is_observed, event_timestamp, payload_hash, batch_id, ingested_at, updated_at`. Đặt `is_observed = arrival_time ≤ event_timestamp`. Khi upsert chỉ ghi đè nếu `excluded.event_timestamp > current.event_timestamp`, và không bao giờ chuyển từ `is_observed = true` về `false`. **ETA, OTP và disruption chỉ đọc các dòng có `is_observed = true`.**
- **Ghi vào:** DOC-14, DOC-20.

### DR-14 · Bảng vị trí hiện tại
- **Quyết định:** Thêm `vehicle_position_latest(vehicle_id PK, route_id, trip_id, direction_id, lat, lon, bearing, current_stop_sequence, event_timestamp, updated_at)`, upsert có guard theo `event_timestamp`. API `/vehicles/live` đọc bảng này, không quét bảng fact.
- **Ghi vào:** DOC-14.

### DR-15 · Partition và retention
- **Ước lượng:** ~~Tải nền 100 event/giây, tức khoảng 8,6 triệu dòng vehicle position mỗi ngày.~~ *Điều chỉnh 2026-09-26 theo số đo trên feed (DOC-10 §3):* cao điểm 121 VehiclePosition/giây; cả ngày khoảng **5,7 triệu** dòng vehicle position, vì số xe trung bình theo 24 giờ chỉ là 331.
- **Quyết định:**
  - `fact_vehicle_position` và `fact_trip_update` partition theo ngày của `service_date`. `fact_ticket_sales` partition theo tháng.
  - Retention mặc định: vehicle position 14 ngày, trip update 90 ngày, ticket 365 ngày (cấu hình được). Raw zone giữ lâu hơn.
  - Không dùng pg_partman. `PartitionMaintenanceJob` là job Spring Batch gồm một `Tasklet` step: tạo trước partition cho 7 ngày tới và drop partition quá hạn.
- **Ghi vào:** ADR-0011, DOC-18.

### DR-16 · Vai trò và vòng đời của `dedup_registry` — ⚠ làm rõ
- **Vấn đề 1:** Với 8,6 triệu message mỗi ngày, bảng hash sẽ phình rất nhanh.
- **Vấn đề 2 (quan trọng):** Khi replay từ raw zone sau khi đã sửa lỗi logic, message có hash trùng sẽ bị bỏ qua. Như vậy replay mất tác dụng.
- **Quyết định:** Tính đúng đắn (không trùng) **chỉ dựa vào upsert theo business key**. `dedup_registry` là lớp tối ưu, dùng để bỏ qua sớm message gửi lại y hệt và đếm metric `records_duplicate_total`.
  - PK `(source, payload_hash)`, có `first_seen_at` và `batch_id`. ~~TTL 24 giờ~~ **TTL 1 giờ** (cấu hình được, `pti.etl.dedup.ttl`), dọn bằng job xóa theo lô mỗi 5 phút.
  - *Điều chỉnh 2026-09-26 (DOC-10 §3):* với TTL 24 giờ, registry giữ khoảng 12 triệu hash (khoảng 1,7 GB gồm index), index không còn nằm trong `shared_buffers` và mỗi lần chèn phải đọc ngẫu nhiên từ đĩa. Các tình huống gửi lại mà registry cần bắt (producer retry, kịch bản `duplicates` gửi lại trong vòng 60 giây) đều nằm trong vài phút. TTL 1 giờ giữ khoảng 520 nghìn dòng (khoảng 70 MB). Tính đúng đắn không đổi vì nó dựa vào upsert.
  - **Mọi luồng replay (DLQ và raw zone) đều bỏ qua registry** (job parameter `replay=true` của Spring Batch, processor đọc qua `@StepScope`).
- **Ghi vào:** ADR-0003, DOC-19, DOC-22.

### DR-17 · Bảng cảnh báo hợp nhất
- **Vấn đề:** Alert feed cần có lịch sử, và cần định tuyến theo đối tượng nhận (hành khách / vận hành / kỹ thuật) như SDD gốc mô tả ở mục 9.4. Tuy nhiên SDD gốc chưa có bảng nào cho việc này.
- **Quyết định:** `alert_event(id UUID, type DISRUPTION|BUNCHING|DLQ_SEVERE|FEED_STALE|TICKETING_ANOMALY|INFRA, severity 0..2, audience PUBLIC|OPERATIONS|ENGINEERING, route_id NULL, ref_table, ref_id, title, body JSONB, created_at, acknowledged_by, acknowledged_at, dedup_key UNIQUE)`. *Bổ sung 2026-09-26:* thêm `resolved_at` (Alertmanager gửi `resolved`, episode đóng), để feed lọc được cảnh báo đang mở. Alertmanager gửi webhook về API, API ghi vào cùng bảng này (DR-51).
- **Ghi vào:** ADR-0023, DOC-15.

### DR-18 · Bảng phục vụ DLQ và replay
- **Quyết định:**
  - `dead_letter`: `id, source, stage (DESERIALIZE|SCHEMA|BUSINESS|DEDUP|LOAD|QUALITY), error_class, error_message, raw_payload TEXT, edited_payload JSONB, kafka_topic, kafka_partition, kafka_offset, business_key, batch_id, created_at, status, category, category_confidence, severity, severity_confidence, model_version, triaged_at, triage_attempts, auto_replay_count, last_replay_at, resolved_by, resolved_at`.
  - Máy trạng thái `status`: `NEW → TRIAGING → TRIAGED → {AUTO_REPLAY_SCHEDULED | PENDING_CONFIRM | MANUAL} → REPLAY_REQUESTED → {REPLAYED | NEW (lỗi lại)}`; ngoài ra có `DISCARDED` và `RESOLVED`.
  - `dlq_action_log(id, dead_letter_id, action, actor (user hoặc 'auto'), confidence, details JSONB, at)` là nhật ký auto-replay và thao tác tay.
  - `replay_request(id, kind RAW_RANGE|DLQ_RECORD, source, from_ts, to_ts, dead_letter_id, requested_by, requested_at, status PENDING|RUNNING|DONE|FAILED, job_execution_id (trỏ tới `BATCH_JOB_EXECUTION`), stats JSONB)`.
  - *Bổ sung 2026-09-26 (khi viết DDL ở DOC-15):* `dead_letter` thêm `rule_id` (rule DQ hoặc validation gây lỗi), `kafka_timestamp`, `triage_lease_until` (lease khi triage-worker đang xử lý, để pod chết thì dòng được nhận lại), `replay_count` (tổng số lần replay, khác `auto_replay_count` giới hạn 0..2) và `updated_at`. `replay_request` thêm `recompute_analytics`, `idempotency_key` (UNIQUE cùng `requested_by`), `started_at`, `finished_at`, `message`. Chỉ một `RAW_RANGE` đang `PENDING`/`RUNNING` cho mỗi nguồn (partial unique index; API trả 409, FR-12.3), cửa sổ tối đa 7 ngày.
- **Ghi vào:** DOC-15, DOC-22.

### DR-19 · Cờ vận hành lúc chạy
- **Vấn đề:** SDD gốc có nhắc "tạm dừng consumer bằng feature flag" nhưng chưa nói cơ chế.
- **Quyết định:** Bảng `runtime_flag(key PK, value JSONB, updated_by, updated_at)`, ví dụ `etl.consumer.gtfs-rt.paused` hoặc `triage.dlq.enabled`. Service đọc bảng này mỗi 5 giây. Ngưỡng thuật toán **vẫn nằm trong file cấu hình** (đổi thì restart, không cần sửa code), còn cờ bật/tắt tức thời thì nằm trong bảng.
- **Ghi vào:** DOC-15, DOC-29.

### DR-20 · Quyền ghi của API — ⚠ mâu thuẫn trong SDD gốc
- **Vấn đề:** SDD gốc nói API chỉ dùng `api_reader` (chỉ SELECT). Nhưng `POST /dispatch-suggestions/{id}/feedback`, confirm DLQ và ack alert đều phải ghi.
- **Quyết định:** API có hai datasource. `api_reader` đọc từ replica. `replay_operator` ghi vào primary, với quyền hẹp tới mức cột: `UPDATE(status, edited_payload, resolved_by, resolved_at)` trên `dead_letter`; `INSERT` trên `replay_request`, `job_request` (yêu cầu restart hoặc chạy tay job batch, DR-62) và `dlq_action_log`; `UPDATE(operator_feedback, feedback_by, feedback_at)` trên `insight_dispatch_suggestion`; `UPDATE(acknowledged_*)` trên `alert_event`; `INSERT/UPDATE` trên `runtime_flag`. Với trường hợp cần đọc ngay dữ liệu vừa ghi, response trả lại bản ghi đọc từ primary.
- **Ghi vào:** DOC-17.

---

## C. ETL (Spring Batch và Spring Kafka)

### DR-21 · Chiến lược ghi chunk: batch trước, fallback scan
- **Vấn đề:** SDD gốc muốn cùng lúc "JdbcTemplate batch upsert" (một round-trip) và "savepoint theo từng record". Hai cách này không dùng được đồng thời.
- **Quyết định:** Ghi batch trước, lỗi dữ liệu khi ghi thì chuyển sang scan. Cụ thể theo từng chế độ:
  - **Job batch (Spring Batch):** dùng nguyên fault-tolerant chunk step, không tự viết thuật toán.
    1. Process từng item; exception mà `SkipPolicy` cho skip thì item bị loại khỏi chunk.
    2. `JdbcBatchItemWriter` ghi cả chunk trong một transaction. `SkipListener` ghi DLQ và Spring Batch ghi `ExecutionContext`/`StepExecution` trong cùng transaction đó.
    3. Nếu writer ném exception có thể skip: Spring Batch rollback rồi tự **scan** (ghi lại từng item, mỗi item một transaction), item lỗi đi vào `onSkipInWrite` → DLQ.
    4. Cần `processorNonTransactional()` hoặc processor không có side effect, vì scan chạy lại processor; processor của dự án là hàm thuần nên đáp ứng.
  - **Streaming (`StreamChunkTemplate`):** cùng thuật toán, hiện thực bằng các thành phần Spring:
    1. Process từng record; lỗi dữ liệu vào danh sách skip.
    2. `TransactionTemplate`: gọi cùng bean `ItemWriter` với `Chunk` các item hợp lệ, `DeadLetterWriter` cho các item skip, ghi `etl_stream_batch`, commit.
    3. Nếu writer ném **lỗi dữ liệu** (SQLState class 22/23): rollback, mở transaction mới; với mỗi item thì `status.createSavepoint()`, ghi đơn lẻ, lỗi thì `status.rollbackToSavepoint(sp)` và đưa item vào DLQ; cuối cùng commit. Dùng savepoint thay vì một transaction cho mỗi item để micro-batch vẫn chỉ tốn một lần commit.
  - Nếu bước ghi ném **lỗi hạ tầng**: xem DR-23.
- **Ghi vào:** ADR-0005, DOC-19, DOC-20.

### DR-22 · Ánh xạ poll sang chunk, và ack
- **Quyết định:** Dùng batch listener của Spring Kafka (`@KafkaListener(batch = "true")`, nhận `List<ConsumerRecord>`), `max.poll.records=500`, `fetch.max.wait.ms=1000` và `fetch.min.bytes` đủ lớn để gom micro-batch khoảng 1 giây. Mỗi poll là một chunk và một dòng `etl_stream_batch` (có `batch_id` riêng). **Không tạo JobExecution Spring Batch cho mỗi poll** (ADR-0002).
  - Ack: `AckMode.BATCH` (mặc định). Listener gọi `StreamChunkTemplate` đồng bộ, transaction đã commit khi listener trả về, nên offset luôn được commit sau transaction mà không cần tự gọi `ack()`.
  - Lỗi hạ tầng: listener ném exception; `DefaultErrorHandler` seek về offset đầu batch của từng partition và thử lại với `ExponentialBackOff` (không giới hạn số lần, trần 30 giây); `ContainerPausingBackOffHandler` pause container trong lúc chờ để consumer không bị văng khỏi group. Lỗi dữ liệu không bao giờ tới error handler vì đã được xử lý trong listener.
  - Kích hoạt analytics bằng sự kiện `MicroBatchCommitted` phát **sau khi** transaction commit. *Làm rõ 2026-09-27 (DOC-19 §6.2):* `StreamChunkTemplate` publish sự kiện sau khi `TransactionTemplate.execute` trả về, lúc đó không còn transaction nên listener là `@EventListener` + `@Async` (executor riêng), không phải `@TransactionalEventListener` (listener này bị bỏ qua khi không có transaction, trừ khi đặt `fallbackExecution`). Ngữ nghĩa "chỉ sau commit" giữ nguyên.
  - Listener concurrency mặc định là 3 (cấu hình được), mỗi thread chạy một chuỗi chunk tuần tự.
- **Hệ quả:** `etl_stream_batch` sinh khoảng 1 dòng/giây cho mỗi consumer thread, nên giữ 7 ngày. Ops console hiển thị dữ liệu gộp theo phút.
- **Ghi vào:** ADR-0004, DOC-20.

### DR-23 · Phân loại lỗi
- **Quyết định:** `ErrorClassifier` (hiện thực `Classifier<Throwable, ErrorKind>` của Spring, dựa trên `DataAccessException` hierarchy và `SQLErrorCodeSQLExceptionTranslator`) trả về một trong ba loại, và cùng một bảng phân loại được nối vào cả hai chế độ:

  | Loại | Ví dụ | Job batch (Spring Batch) | Streaming (Spring Kafka) |
  | --- | --- | --- | --- |
  | `DATA` | lỗi deserialize, vi phạm Bean Validation, vi phạm business rule, SQLState 22xxx/23xxx | `SkipPolicy` cho skip → `SkipListener` ghi DLQ | Skip trong `StreamChunkTemplate` → DLQ |
  | `TRANSIENT_INFRA` | SQLState 08xxx, 40001, 40P01, 57P01, 53300; timeout; Kafka retriable | Retry của fault-tolerant step (tối đa 5 lần, backoff có jitter, tổng ≤ 60 giây), hết lượt thì step FAILED để restart sau; **không vào DLQ** | Ném ra ngoài → `DefaultErrorHandler` seek và thử lại vô hạn có backoff, pause container; circuit breaker DB mở thì dừng poll; **không ack, không vào DLQ** |
  | `FATAL` | lỗi cấu hình hoặc lỗi lập trình (NPE trong writer…) | Không skip, không retry → step FAILED, alert khẩn | Dừng container (`CommonContainerStoppingErrorHandler` cho loại này), alert khẩn |

  Skip limit: `SkipPolicy` cho skip `DATA` tới khi tỷ lệ skip của step vượt `pti.etl.batch.max-skip-ratio` (mặc định 20%), vượt thì step FAILED, vì một feed sai hàng loạt nên dừng lại thay vì nạp một nửa.
- **Ghi vào:** ADR-0006, DOC-30.

### DR-24 · Chống chạy trùng job giữa các pod batch
- **Vấn đề:** Hai pod `etl-batch` cùng chạy scheduler. Advisory lock của PostgreSQL gắn với session nên hỏng khi đi qua PgBouncer ở chế độ transaction pooling. Spring Batch không có heartbeat, nên execution của pod chết sẽ kẹt ở STARTED.
- **Quyết định:** Ba lớp, đều dùng thứ có sẵn thay cho lease table tự viết:
  1. **Kích hoạt:** mỗi `@Scheduled` có `@SchedulerLock` của ShedLock (`JdbcTemplateLockProvider`, bảng `shedlock`, dùng UPDATE thường nên chạy được qua PgBouncer). `lockAtMostFor` lớn hơn thời gian chạy dài nhất của job; job dài thì dùng `KeepAliveLockProvider` để gia hạn.
  2. **Một JobInstance chỉ một execution:** Spring Batch ném `JobExecutionAlreadyRunningException` khi đã có execution đang chạy cho cùng job parameters. Job parameters định danh được thiết kế sao cho mỗi lần chạy hợp lệ là một instance (ví dụ `serviceDate`, `runKey`, `replayRequestId`). *Làm rõ 2026-09-27 (DOC-21 §1):* `GtfsStaticLoadJob` dùng `runKey` thay cho `feedHash`, vì hash chỉ biết sau khi đọc file; tính duy nhất theo feed do `gtfs_feed_version_hash_uk` bảo đảm.
  3. **Fencing:** Spring Batch cập nhật `BATCH_STEP_EXECUTION` với kiểm tra `VERSION` trong chính transaction của chunk. Bước khôi phục đánh dấu execution kẹt là FAILED (tăng `VERSION`), nên nếu pod cũ còn sống thì lần commit chunk kế tiếp của nó gặp `OptimisticLockingFailureException` và bị rollback. `VERSION` đóng vai fencing token.
  - **Khôi phục execution kẹt:** tác vụ `StaleExecutionRecoverer` (chạy lúc khởi động và mỗi phút, dưới ShedLock) tìm execution STARTED có `LAST_UPDATED` cũ hơn `pti.batch.stale-after` (mặc định 2 phút, lớn hơn thời gian một chunk dài nhất) và không còn khóa ShedLock của job đó; đánh dấu step và job execution FAILED qua `JobRepository` (hoặc API khôi phục có sẵn nếu Spring Batch 6 cung cấp, xác minh ở S-06), rồi `JobOperator.restart`.
  - JDBC đặt `prepareThreshold=0` hoặc dùng PgBouncer ≥ 1.21 với `max_prepared_statements`.
- **Ghi vào:** ADR-0015, DOC-19.

### DR-25 · Data quality: trước hay sau khi ghi — ⚠ làm rõ
- **Quyết định:** Chia hai lớp:
  - **Pre-write** (trong processor, theo từng record): null ở trường bắt buộc, trùng business key trong cùng chunk, tham chiếu route/stop so với cache dimension. Vi phạm thì vào DLQ với `stage=QUALITY`. Lớp này là "vi phạm được đưa vào DLQ" mà SDD gốc nhắc tới.
  - **Post-write** (SQL assertion theo `batch_id` sau mỗi chunk batch, và toàn bảng sau mỗi job): kết quả ghi vào `dq_check_result(id, rule_id, scope, batch_id, violation_count, sample JSONB, checked_at)`, phát metric và alert. **Lớp này không tự xóa dữ liệu.**
- **Ghi vào:** DOC-16.

### DR-26 · Đơn vị triển khai
- **Quyết định:**
  - Các Gradle module: `build-logic, common, analytics (thư viện), etl (app), triage-worker (app), api (app), source-simulator (app), db (migration + Flyway runner)`. Thêm `frontend/` (pnpm) và `experiments/` (Python). **Không còn module `engine`** (ADR-0002).
  - `etl` dùng một image với hai profile: `stream` (Spring Kafka consumer + analytics micro-batch; không bật scheduler) và `batch` (Spring Batch, `@Scheduled` + ShedLock, các job batch, replay). Cả hai profile đặt `spring.batch.job.enabled=false` để không job nào tự chạy lúc khởi động. Cách này khớp với bảng workload K8s trong SDD gốc.
  - Flyway chạy như một container/job riêng (`db-migrate`), các app không tự migrate.
- **Ghi vào:** ADR-0014, ADR-0024, DOC-07.

### DR-27 · Chế độ baseline cho thực nghiệm
- **Vấn đề:** SDD gốc nói mỗi EXP "so sánh với baseline không có cơ chế tương ứng" nhưng chưa nói baseline được dựng ra sao.
- **Quyết định:** Thêm profile `experiment` với các cờ (chỉ bật được trong profile này):
  - `pti.etl.baseline.offset-commit=auto`: auto-commit trước khi ghi DB.
  - `write-mode=insert` vào bảng bóng `exp_fact_*` **không có ràng buộc UNIQUE**, để đếm được số dòng trùng.
  - `error-mode=fail-batch`: một lỗi làm fail cả chunk.
  - `dedup=off`.
- **Ghi vào:** DOC-45, DOC-19.
- **Ghi chú:** `error-mode=fail-batch` ở job batch được dựng bằng cách không bật `faultTolerant()` (step thường của Spring Batch fail ngay ở lỗi đầu tiên); ở streaming bằng cờ trong `StreamChunkTemplate`.

### DR-28 · Ground truth để đo mất và trùng
- **Quyết định:** Simulator ghi **ledger** vào schema `sim` riêng: `sim_ledger(message_id, entity_type, business_key, event_timestamp, intended_invalid BOOL, is_resend BOOL, produced_at)`. *Bổ sung 2026-09-26:* ledger chỉ ghi message đã được Kafka xác nhận (ghi trong callback của producer, kèm partition và offset). Một TripUpdate sinh nhiều business key nên cột là `business_keys TEXT[]`. Partition theo ngày của `produced_at`, retention 2 ngày (khoảng 2,5 GB/ngày). DDL ở DOC-13 §6. Tập kỳ vọng tại warehouse là các business key trong ledger có `intended_invalid = false`. Số liệu tính như sau: mất = kỳ vọng − thực có; trùng = `count(*) − count(distinct business_key)` (chỉ khác 0 ở bảng bóng của baseline); sai giá trị = so sánh trường theo key. Với ticketing, ground truth chính là DB nguồn.
- **Ghi vào:** DOC-25, DOC-45.

### DR-62 · Cấu hình metadata và hạ tầng Spring Batch
- **Vấn đề:** Dùng Spring Batch thì phải chốt nơi đặt bảng metadata, ai tạo schema, cách lưu `ExecutionContext` và vòng đời của metadata.
- **Quyết định:**
  - Bảng `BATCH_*` nằm trong schema `batch` của warehouse, `spring.batch.jdbc.table-prefix=batch.BATCH_`. DDL lấy từ `schema-postgresql.sql` trong jar `spring-batch-core` đúng phiên bản đã chốt, đưa vào migration Flyway (V5). `spring.batch.jdbc.initialize-schema=never`. Nâng phiên bản Spring Batch thì kiểm tra script migration của Spring Batch và viết migration Flyway tương ứng.
  - JobRepository phải là **JDBC**, không phải bản resourceless/in-memory (ở Spring Boot 4 / Spring Batch 6 cần đúng starter hoặc annotation cho JDBC; xác minh ở S-06).
  - `ExecutionContext` serialize bằng serializer JSON (Jackson) thay cho mặc định, để đọc được khi điều tra và không phụ thuộc Java serialization. Chỉ lưu kiểu đơn giản (số, chuỗi, map).
  - `JobOperator` là API duy nhất để start, restart, stop job; `JobExplorer`/`JobRepository` để đọc trạng thái. API không gọi Spring Batch trực tiếp: nó đọc view `ops_job_run_v` và ghi yêu cầu (restart, replay) vào bảng, pod `etl-batch` thực thi (ADR-0013).
  - `BatchMetadataCleanupJob` (Tasklet, hằng ngày) xóa execution cũ hơn 30 ngày theo đúng thứ tự khóa ngoại (step context → step execution → job context → job params → job execution → job instance). Execution FAILED chưa được restart thì giữ lại.
  - Cô lập quyền: `etl_writer` có quyền trên schema `batch`; `api_reader` chỉ SELECT qua view.
- **Ghi vào:** ADR-0002, DOC-15, DOC-17, DOC-18, DOC-19.

### DR-63 · Ý nghĩa của `batch_id` ở hai chế độ
- **Vấn đề:** Trước ADR-0002, `batch_id` trỏ tới một dòng `etl_job_run` của engine tự xây. Sau khi đổi sang Spring Batch, khóa của metadata là `BIGINT` (`STEP_EXECUTION_ID`), còn streaming dùng `etl_stream_batch`. Cần một định danh chung để fact, DLQ, log và DQ post-write cùng trỏ về được.
- **Quyết định:** `batch_id` là UUID định danh **một đơn vị ghi**:
  - **Streaming:** một micro-batch, tức một dòng `etl_stream_batch(batch_id PK)`. Sinh bằng UUIDv7 trước khi mở transaction.
  - **Job batch:** một **step execution**. `BatchIdStepListener.beforeStep` sinh UUIDv7, ghi một dòng `etl_batch_step(batch_id PK, job_execution_id, step_execution_id UNIQUE, job_name, step_name, created_at)` và đặt giá trị vào step `ExecutionContext` với key `pti.batchId`; processor/writer đọc qua `@StepScope`. Restart tạo step execution mới nên có `batch_id` mới; các dòng đã commit trước đó giữ `batch_id` cũ.
  - Tasklet job (ETA, OTP, bảo trì) cũng dùng cùng listener.
  - Truy vết replay: `replay_request.job_execution_id` → `etl_batch_step` → `batch_id` trong fact (FR-12.5).
  - DQ-20 kiểm tra `batch_id` thuộc `etl_stream_batch ∪ etl_batch_step`, **chỉ trong cửa sổ retention** của hai bảng này (7 ngày và 30 ngày, DOC-18). Fact cũ hơn giữ `batch_id` nhưng không còn tham chiếu được.
  - `ops_job_run_v` hợp nhất `BATCH_JOB_EXECUTION`/`BATCH_STEP_EXECUTION` (qua `etl_batch_step`) và `etl_stream_batch` cho Ops console.
- **Ghi vào:** DOC-15, DOC-19, DOC-16, DOC-28.

### DR-64 · Bố cục instance, database và schema PostgreSQL
- **Vấn đề:** SDD gốc chỉ nêu tên `ticketing_source` và schema `batch`/`sim`. Chưa chốt các bảng còn lại nằm ở schema nào, và ledger nằm ở đâu. Ledger phải còn nguyên khi EXP-04 và UC-18 xóa rồi dựng lại warehouse.
- **Quyết định:**

  | Instance | Database | Schema | Nội dung | Owner (migration) |
  | --- | --- | --- | --- | --- |
  | `pg-warehouse` (CNPG trên k3d: 1 primary + 1 replica) | `pti_warehouse` | `dw` | `gtfs_feed_version`, `dim_*`, `gtfs_*`, `route_headway`, `fact_*`, `vehicle_position_latest` | `pti_owner` |
  | | | `ops` | `etl_stream_batch`, `etl_batch_step`, `etl_checkpoint`, `shedlock`, `dead_letter`, `dlq_action_log`, `replay_request`, `job_request`, `dedup_registry`, `runtime_flag`, `dq_check_result`, `alert_event`, view `ops_job_run_v` | `pti_owner` |
  | | | `insight` | `insight_*`, `analytics_*` | `pti_owner` |
  | | | `batch` | `BATCH_*` của Spring Batch (DR-62) | `pti_owner` |
  | | | `exp` | Bảng bóng `exp_fact_*` không có UNIQUE (DR-27) | `pti_owner` |
  | `pg-source` (đơn lẻ, không failover, DR-55) | `ticketing_source` | `public` | `sale_point`, `ticket_transaction`, `debezium_heartbeat` | `ticketing_owner` |
  | | `pti_sim` | `sim` | `sim_ledger`, `sim_scenario_run` | `sim_owner` |

  - Tách hai instance để tải ghi của warehouse và WAL của replication slot không ảnh hưởng nhau, và để failover warehouse (EXP-08) không chạm tới slot của Debezium.
  - Ledger nằm trong `pti_sim` trên `pg-source`, nên xóa `pti_warehouse` không làm mất ground truth. Runner thực nghiệm đọc cả hai database và so sánh bằng Python (nạp business key vào bảng tạm của warehouse khi số lượng lớn).
  - Publication `pti_ticketing` của Debezium chỉ gồm `public.sale_point`, `public.ticket_transaction`, `public.debezium_heartbeat`. Publication do migration `ticketing` tạo (connector đặt `publication.autocreate.mode=disabled`), nên user `debezium` chỉ cần `REPLICATION` và `SELECT`, không cần quyền owner. Connector đặt `heartbeat.interval.ms=10000` và `heartbeat.action.query` cập nhật `debezium_heartbeat`, để slot vẫn tiến lên khi ticketing ít giao dịch trong khi `pti_sim` ghi nhiều (WAL là của cả instance).
  - SQL trong code luôn ghi rõ schema (`dw.fact_trip_update`), không dựa vào `search_path`.
  - Keycloak chạy `start-dev` với database nhúng (dev-file), realm import từ JSON lúc khởi động, nên không cần database riêng.
- **Ghi vào:** DOC-07, DOC-13, DOC-14, DOC-15, DOC-17.

### DR-65 · Phạm vi trạm trong một TripUpdate và chi phí upsert
- **Vấn đề:** Đo trên feed (DOC-10 §3): nếu mỗi TripUpdate chứa mọi trạm phía trước (trung bình khoảng 22 trạm) thì `fact_trip_update` nhận khoảng 24 triệu lần upsert mỗi ngày cho chỉ 345 nghìn dòng, khoảng 500 lần/giây lúc cao điểm và 5.000 lần/giây ở EXP-07. Phần lớn là cập nhật giá trị *dự đoán*, trong khi ETA, OTP và disruption chỉ đọc giá trị *quan sát* (DR-13).
- **Quyết định:**
  - Simulator đưa vào mỗi TripUpdate: các trạm đã đi qua kể từ TripUpdate trước, cộng **tối đa `pti.sim.trip-update.lookahead-stops` trạm phía trước (mặc định 10)**. Nhiều feed thật cũng giới hạn tầm dự đoán như vậy. Upsert giảm còn khoảng 12 dòng mỗi TripUpdate, tức khoảng 13 triệu dòng mỗi ngày và khoảng 270 dòng/giây lúc cao điểm.
  - `fact_trip_update` đặt `fillfactor = 80` và **chỉ đánh index trên các cột không đổi** (business key, `route_id`, `stop_id`). Như vậy cập nhật dự đoán là HOT update, không sinh thêm bản ghi index và ít bloat. `is_observed` và `arrival_time` không nằm trong index nào; truy vấn analytics lọc chúng sau khi quét theo partition và `route_id`.
  - F-ANL-06 (`realtimeArrival`) chỉ có giá trị cho 10 trạm tới của mỗi chuyến (khoảng 15–20 phút); xa hơn thì dùng ETA lịch sử.
- **Ghi vào:** DOC-09, DOC-10, DOC-14, DOC-25.


### DR-70 · Trục thời gian của replay raw zone và DLQ idempotent
- **Vấn đề:** (1) `replay_request.from_ts/to_ts` chưa nói là event time hay giờ record Kafka; hai trục lệch nhau bởi đồng hồ nghiệp vụ (DR-67), và offset có thể đã đổi từ lúc dữ liệu được sinh. (2) Poll được giao lại sau khi DB đã commit nhưng offset chưa commit (ADR-0004) làm record lỗi có hai dòng `dead_letter`. (3) Replay sau khi sửa logic không đóng các dead letter mà nó đã giải quyết.
- **Quyết định:**
  1. `from_ts/to_ts` của `RAW_RANGE` là **giờ record Kafka (CreateTime, giờ thật)**, cùng trục với phân vùng raw zone. `to_ts ≤ now − 10 phút` (chờ S3 sink rotate), `from_ts ≥ now − 29 ngày` (trước lifecycle 30 ngày). UI hiển thị thêm khoảng event time ước tính, chỉ để tham khảo.
  2. Unique index một phần `dead_letter (kafka_topic, kafka_partition, kafka_offset)`. Luồng trực tiếp `ON CONFLICT DO NOTHING`; replay `ON CONFLICT DO UPDATE` (lỗi mới nhất, `replay_count + 1`, về `NEW` trừ khi đã `DISCARDED`/`RESOLVED`).
  3. Raw zone replay ghi thành công một record có dead letter chưa đóng thì chuyển dead letter đó sang `RESOLVED` (`resolved_by = 'system:etl-batch'`).
  4. `GtfsStaticLoadJob` định danh bằng `runKey` thay vì `feedHash` (DOC-21 §1).
- **Ghi vào:** DOC-15, DOC-19, DOC-21, DOC-22, DOC-32, DOC-36.

---

## D. Analytics

### DR-29 · Khóa insight theo event time, mô hình episode — ⚠ quan trọng
- **Vấn đề:** Hiện UNIQUE dùng `detected_at`. Nếu `detected_at` lấy giờ đồng hồ thì replay sẽ sinh bản ghi mới, phá vỡ tính idempotent. Ngoài ra mỗi micro-batch sẽ sinh một dòng cho cùng một sự kiện kéo dài.
- **Quyết định:**
  - Mọi insight dùng **event time** (thời gian của dữ liệu), không dùng wall clock.
  - Bunching và disruption lưu theo **episode**: thêm các cột `episode_start, episode_end, status OPEN|CLOSED, peak_*` (ví dụ `min_gap_seconds`, `max_z_score`). UNIQUE đổi thành `(route_id, vehicle_leader, vehicle_follower, episode_start)` và `(route_id, episode_start)`. *Sửa 2026-09-26:* UNIQUE của disruption là `(route_id, direction_id, episode_start)`, vì baseline (DR-31) tính theo `(route_id, direction_id)`, nên hai chiều có thể mở episode cùng một bucket. Cặp xe được chuẩn hóa theo thứ tự leader/follower.
  - `id` = UUIDv5(namespace, natural key), đảm bảo tính lại thì được cùng id.
- **Ghi vào:** ADR-0010, DOC-15, DOC-23.

### DR-30 · Thuật toán bunching cụ thể
- **Quyết định:** Với mỗi `(route_id, direction_id)`, xét các xe có vị trí trong vòng 2 phút, sắp theo tiến độ trên tuyến (`shape_dist_traveled` nội suy, nếu thiếu thì dùng `current_stop_sequence`). Với từng cặp liên tiếp (leader L, follower F): **gap = t_event(F) − thời điểm L đi qua trạm kế tiếp của F** (lấy từ trip update đã quan sát). Nếu không có dữ liệu đó thì dùng khoảng cách chia cho tốc độ trung bình. Mở episode khi `gap < 0.5 × headway(route, dir, day_type, hour)` trong 2 lần đánh giá liên tiếp. Đóng episode khi `gap > 0.7 × headway` (hysteresis). Loại trừ 2 trạm đầu và 2 trạm cuối để tránh báo nhầm lúc xe chờ ở đầu bến. Mọi hệ số đều cấu hình được.
- **Làm rõ (2026-09-27, DOC-23 §5.1):**
  - Thời điểm L qua trạm được lấy từ **lịch sử vị trí** của L: `STOPPED_AT` tại trạm, hoặc nội suy giữa hai vị trí. Nếu không có thì nội suy theo lịch (đánh dấu `ESTIMATED`). Lý do: dòng TripUpdate bị ghi đè, nên không biết được lúc nó tới, và vì thế không tái tạo được khi tính lại.
  - `gap = (t_F + thời gian còn lại theo lịch của F tới trạm kế) − pass_L`.
  - Đánh giá theo lưới event time 15 giây, không theo micro-batch.
  - Nhánh "khoảng cách / tốc độ" được bỏ.
- **Ghi vào:** DOC-23.

### DR-31 · Thuật toán disruption cụ thể
- **Vấn đề:** Nếu EWMA cập nhật "mỗi micro-batch" thì ý nghĩa của α sẽ thay đổi theo tải, vì tải cao thì micro-batch dày hơn.
- **Quyết định:** Gom theo **bucket 1 phút event time**. Giá trị hiện tại là trung bình delay của các arrival đã quan sát trong cửa sổ trượt 10 phút, cần tối thiểu 5 mẫu. Baseline EWMA cập nhật một lần mỗi bucket với α = 0,1. Phương sai EW tính bằng `var = (1−α)(var + α·(x−μ)²)`. `z = (x−μ)/max(σ, 30 s)`. Mở episode khi z > 2,5 trong 2 bucket liên tiếp, đóng khi z < 1,5 trong 3 bucket. **Không cập nhật baseline trong khi episode đang mở**, để baseline không "học" luôn sự cố. Warm-up 60 bucket đầu không báo. Trạng thái lưu ở `analytics_route_baseline(route_id, direction_id, ewma_mean, ewma_var, last_bucket, open_episode_id)`. Khi replay một khoảng thời gian, xóa insight trong khoảng đó rồi tính lại tuần tự, bắt đầu từ snapshot baseline theo giờ gần nhất (`analytics_baseline_snapshot`).
- **Ghi vào:** DOC-23.

### DR-32 · ETA: tính lại toàn cửa sổ thay vì cộng dồn
- **Vấn đề:** Cộng dồn trung bình theo watermark sẽ đếm trùng mẫu khi replay.
- **Quyết định:** Job hàng giờ **tính lại hoàn toàn** trên cửa sổ 28 ngày (cấu hình được) từ các dòng `is_observed = true`, GROUP BY `(route, stop, dow, hour)` theo giờ địa phương. Sau đó upsert kết quả và xóa key không còn mẫu. Watermark chỉ dùng để bỏ qua lần chạy khi không có dữ liệu mới. Mức tin cậy: `sample_count < 10` là thấp, 10–29 là trung bình, ≥ 30 là cao. Việc kết hợp thêm độ trễ hiện tại của chuyến đang chạy là tính năng mở rộng tùy chọn (F-ANL-06, FR-06.3).
- **Ghi vào:** DOC-23.

### DR-33 · Định nghĩa OTP
- **Quyết định:** Đơn vị đo là **từng lần xe đến trạm đã quan sát được**. Một lần đến được coi là đúng giờ nếu `−early_tolerance ≤ delay ≤ late_tolerance`, mặc định cả hai là 300 giây theo SDD gốc. `otp_percentage = on_time / observations × 100`. Thêm cột `observation_count`; `trip_count` là số chuyến có ít nhất một lần quan sát. Job chạy 03:00 hằng ngày, tính cho hôm qua và tính lại 2 ngày trước đó để bắt dữ liệu đến trễ.
- **Ghi vào:** DOC-23.

### DR-34 · Phát hiện bất thường ticketing (phần thống kê)
- **Quyết định:** Dùng cửa sổ tumbling 15 phút theo `sale_point`, với các chỉ số `txn_count, refund_count, refund_ratio, amount_sum`. Baseline là trung bình và độ lệch chuẩn theo `(sale_point, day_type, hour)` trên 4 tuần. Cờ bất thường bật khi `z(txn_count) > 3` và `txn_count ≥ 20`, hoặc `refund_ratio > 0.3` và `refund_count ≥ 5`. Job chạy mỗi 5 phút trên các cửa sổ đã đóng (cho phép dữ liệu trễ 2 phút).
- **Ghi vào:** DOC-23.

### DR-35 · Cơ chế kích hoạt analytics micro-batch
- **Quyết định:** Sau khi chunk commit, ETL phát một in-process event `MicroBatchCommitted(route_ids, max_event_time)`. `AnalyticsDispatcher` gom các yêu cầu theo route và chạy trên executor riêng, nên không chặn consumer. Insight được ghi trong transaction riêng. Nếu analytics lỗi thì chỉ log và tăng metric, ETL không bị ảnh hưởng. Thêm một tick mỗi 30 giây để đóng các episode của những tuyến không còn dữ liệu mới.
- **Ghi vào:** DOC-23, ADR-0014.

---

## E. AI triage

### DR-36 · Hợp đồng với Jev — **Chốt** (còn 3 điểm cần spike) · ⚠ lệch SDD gốc
- **Nguồn:** [Spring blog 2026-09-21: Spring AI and TypeSafe Jev](https://spring.io/blog/2026/09/21/spring-ai-typesafe-structured-judgment/) và [tài liệu Spring AI TypeSafe](https://spring-ai-community.github.io/spring-ai-typesafe/latest/).
- **Những gì đã biết:**
  - **Đã có Java SDK** `org.springaicommunity:typesafe-java-sdk` (bản mới nhất lúc viết là 0.2.0; yêu cầu Java 17+). Starter `spring-ai-starter-typesafe` yêu cầu Spring Boot 4.x. SDD gốc nói "chỉ có SDK Python/JS, phải tự viết client HttpClient"; điều này **không còn đúng nữa**.
  - Client chính là `TypeSafeClient.builder().build()`. API key đọc từ biến môi trường `TYPESAFE_API_KEY`, hoặc từ property `spring.ai.typesafe.api-key` khi dùng starter.
  - Lời gọi: `client.systemOne(String state, Map<String, ?> typedQuestions)` → `SystemOneResponse`. Mỗi lời gọi nhận **một state** kèm nhiều câu hỏi. REST tương ứng là `POST /v1/systemone`.
  - Ba loại câu hỏi:
    - `Noul.of(...)` / `Noul.builder().instructions().whenTrue().whenFalse()`: kết quả là một số trong [0,1].
    - `Choice.builder().instructions().option(label, mô tả)`: kết quả là label, xác suất từng lựa chọn và `confidence()`.
    - `Score.of(câu hỏi, mức1, mức2, …)`: kết quả là một giá trị liên tục, xác suất từng mức và confidence.
  - Đọc kết quả bằng `noulValue(k)`, `choiceValue(k)`, `choice(k).confidence()`, `scoreValue(k)`.
  - SDK tự có retry, batch và exception có kiểu. Jev không stream. State chỉ được là string, object, array hoặc null; nếu là số hoặc boolean đứng riêng thì bị lỗi 422.
  - Hiệu năng theo bài blog: median khoảng 275 ms cho 1 câu hỏi, khoảng 310 ms cho 3 câu hỏi; chi phí rất thấp (khoảng $0.00004 cho một lời gọi 14 câu hỏi). Như vậy quota và chi phí không phải rủi ro chính.
- **Quyết định:**
  - Vẫn giữ cổng `DecisionModel` (ADR-0018) để test, CI và demo offline chạy được bằng `FakeDecisionModel`.
  - `JevDecisionModel` bọc `TypeSafeClient` của `typesafe-java-sdk`. **Không dùng Spring AI** (không cần ChatClient hay advisor), nên triage-worker vẫn nhẹ.
  - Câu hỏi của SDD gốc được ánh xạ như sau:
    - `category` → `Choice` (mỗi option có mô tả).
    - `severity` → `Score.of("…", "Informational", "Needs attention", "Urgent")`, giá trị liên tục được làm tròn về 0/1/2, và `severity_confidence` lấy từ confidence của Score.
    - Câu "gián đoạn thật hay lỗi dữ liệu" → `Noul`.
  - **State gửi dưới dạng JSON object có cấu trúc**, không nối chuỗi như ví dụ trong SDD gốc.
  - Resilience4j (timeout 2 s, circuit breaker, bulkhead, rate limiter) đặt **bên ngoài** SDK. **Tắt retry của SDK** (hoặc để SDK retry một lần) để tránh retry chồng lên nhau.
  - Chọn adapter bằng `pti.triage.provider=jev|fake|disabled`. Mặc định là `jev` ở dev/staging và `fake` ở CI.
- **Còn phải xác minh trong spike S-01 (tối đa nửa ngày):**
  1. Response có trả phiên bản model không (header hay field)? Nếu không, lưu `model_version = "jev@<sdk-version>"`.
  2. API batch của SDK ("score many items at once") dùng như thế nào?
  3. Mã lỗi khi bị giới hạn tốc độ hoặc hết quota, và các lớp exception tương ứng.
- **Kết quả spike S-01:** _chưa chạy._ Khi chạy xong, ghi vào đây: (1) phiên bản model lấy từ đâu và nhánh `model_version` nào được dùng (DR-73); (2) API batch có dùng được không, nếu có thì kích thước lô; (3) bảng mã lỗi và lớp exception của SDK, đối chiếu với DOC-24 §11.2; (4) tên phương thức builder để tắt retry; (5) latency median/p95 đo được. Response mẫu đã che API key được lưu làm fixture (DOC-24 §3).
- **Ghi vào:** ADR-0018, DOC-24, DOC-11.

### DR-37 · Cách triage worker lấy việc
- **Quyết định:** Mỗi vòng, worker chạy `SELECT … FROM dead_letter WHERE status='NEW' AND triage_attempts < 5 ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED` trong một transaction ngắn và đánh dấu `TRIAGING` kèm lease 2 phút. Gọi Jev **bên ngoài transaction**, sau đó ghi kết quả. Record nào lease hết hạn thì quay lại `NEW`. Worker phát metric `dlq_untriaged` để KEDA scale.
- **Cập nhật theo DR-36:** mỗi record DLQ là **một state, gửi trong một lời gọi** gồm hai câu hỏi `category` và `severity`. Các lời gọi chạy song song trong giới hạn của bulkhead (mặc định 8) và rate limiter. Nếu S-01 cho thấy API batch của SDK dùng được thì chuyển sang gom tối đa 20 record mỗi lần gọi.
- **Cập nhật theo DR-72, DR-74:** lease kiêm mốc backoff; KEDA dùng PostgreSQL scaler thay cho metric `dlq_untriaged`.
- **Ghi vào:** DOC-24.

### DR-38 · "Nguồn đã hồi phục" nghĩa là gì
- **Quyết định:** Mỗi nguồn có một health indicator trong ETL (`/actuator/health/source-gtfs-rt`, `source-ticketing`, `warehouse-db`), dựa trên độ tươi của feed (< 30 giây), trạng thái circuit breaker và trạng thái connector. Auto-replay chỉ chạy khi indicator của đúng nguồn đó là `UP` liên tục ít nhất 60 giây.
- **Ghi vào:** DOC-24.


### DR-72 · Vòng xử lý triage và luật chặn cuối — **Chốt**
- **Vấn đề:** DR-37 mới mô tả cách lấy việc; chưa chốt retry khi Jev lỗi, cách tránh gọi lại vô hạn, ai quyết định hành động, và "luật chặn cuối không phụ thuộc AI" của SDD §9.6 cụ thể là gì. SDD gốc để triage-worker tự ghi `alert_event` cho record severity 2, trùng vai trò với Alertmanager.
- **Quyết định:**
  - Cột lease kiêm luôn mốc "không xử lý trước": claim chỉ lấy dòng có lease rỗng hoặc đã qua. Lỗi tính lần thử cộng `triage_attempts` và lùi `30 s · 2^(n−1)` (tối đa 15 phút); lần thứ 5 → `MANUAL` (DLQ) hoặc `FAILED` (insight). Lỗi hạ tầng tạm thời (circuit mở, quota, timeout) chỉ lùi 30 giây, không tính lần thử, và tạm dừng vòng. `LeaseSweeper` trả dòng hết lease về hàng đợi mỗi 30 giây (ShedLock).
  - Hành động do bảng quyết định trong code (`DlqDecisionTable`) và `AutoReplayGuard` sở hữu (ADR-0019, DOC-24 §6.3–6.4).
  - triage-worker **không ghi** `alert_event`. Cảnh báo theo severity DLQ đi qua Prometheus → Alertmanager → webhook API (DR-51): alert `DlqSevereRecords`, `DlqNeedsAttention`, `DlqUpstreamErrorBurst`, `TriageBacklogHigh`.
  - Luật chặn cuối: etl-stream gán category tất định bằng `DlqRuleClassifier` ngay khi ghi DLQ và phát `pti_dlq_rule_category_total`; alert `DlqUpstreamErrorBurst` dựa trên metric này nên vẫn bắn khi triage-worker hoặc Jev chết.
- **Ghi vào:** DOC-24, DOC-15, DOC-17, DOC-22, DOC-28, ADR-0018, ADR-0019.

### DR-73 · Đường auto-replay trong demo và định dạng `model_version` — **Chốt**
- **Vấn đề:** (1) Với ngưỡng lệch giờ mặc định 1 giờ, demo khó sinh ra record mà guard cho phép auto-replay (DQ-07 đến muộn). (2) S-01 chưa xác nhận response Jev có trả phiên bản model.
- **Quyết định:**
  - Simulator có kịch bản `late-delivery` (DOC-25 §7.9, mặc định trễ `PT6M`). `make up-demo` đặt `PTI_DQ_MAX_CLOCK_SKEW=5m` cho etl-stream, nên record trễ 6 phút vào DQ-07 và được auto-replay khi nguồn `UP` ≥ 60 giây. Compose thường giữ `1h`.
  - `model_version`: `jev:<id>` nếu response có id model; nếu không thì `jev@<sdk-version>` (ví dụ `jev@0.2.0`); `fake@2026.09` cho `FakeDecisionModel`. Kết quả S-01 quyết định nhánh nào được dùng.
- **Ghi vào:** DOC-24 §13, §20, DOC-25, DOC-39, DOC-46, DOC-32, DOC-33.

### DR-74 · Scale triage-worker và virtual thread — **Chốt** · thay một phần DR-37
- **Vấn đề:** DR-37 cho worker phát metric `dlq_untriaged` để KEDA scale. Metric của pod không có khi mọi pod đã về 0 và lệch giữa các pod; hàng đợi thật nằm trong Postgres.
- **Quyết định:**
  - KEDA dùng **PostgreSQL scaler** truy vấn thẳng số dòng `ops.dead_letter` `NEW` đủ điều kiện claim; `minReplicaCount: 1`, `maxReplicaCount: 3` (giữ tổng lời gọi Jev ≤ 60/giây vì rate limiter theo pod). Scaler dùng role chỉ đọc (DOC-40). Không phát `dlq_untriaged`; gauge `pti_triage_backlog` chỉ để quan sát và alert.
  - Mỗi pod triage có Hikari pool 4, nên 3 pod dùng tối đa 12 kết nối.
  - Virtual thread (`spring.threads.virtual.enabled`): `api` và `triage-worker` bật; `etl` và `source-simulator` tắt (DOC-29 §2). S-06 chỉ có thể dẫn tới tắt, không bật thêm.
- **Ghi vào:** DOC-24, DOC-29, DOC-40, DOC-07.

---

## F. API và real-time

### DR-39 · Quy ước API
- **Quyết định:** Prefix `/api/v1`. JSON dùng camelCase. Lỗi trả theo RFC 9457 Problem Details. Danh sách chuỗi thời gian phân trang bằng keyset (`?limit=50&cursor=…` → `{items, nextCursor}`); danh sách nhỏ trả hết. Tham số thời gian theo ISO-8601 có offset, mặc định 24 giờ gần nhất, khoảng tối đa 31 ngày. Mỗi response có header `X-Trace-Id` và `X-Data-As-Of` (thời điểm dữ liệu mới nhất).
- **Ghi vào:** DOC-31.

### DR-40 · Xác thực và nhà cung cấp danh tính (IdP) — **Chốt** (full stack)
- **Vấn đề:** SDD gốc có OAuth2 Resource Server nhưng chưa nói ai phát token. Ngoài ra hành khách không cần đăng nhập.
- **Quyết định:** Chạy Keycloak trong compose (`start-dev`, import realm `pti` từ JSON), với realm role `viewer` và `operator`, cùng user demo `viewer/viewer` và `operator/operator`. SPA đăng nhập theo OIDC Authorization Code + PKCE. Endpoint cho hành khách (`/routes/**`, `/stops/**`, `/vehicles/live`, disruption bản public, SSE kênh public) không cần đăng nhập, chỉ bị rate limit. Endpoint `/insights/**` loại nội bộ và `/etl/**` cần `viewer` để đọc và `operator` để ghi. Keycloak tốn khoảng 500–700 MB RAM; con số này được tính trong ngân sách tài nguyên (S-03).
- **Ghi vào:** ADR-0017, DOC-27.

### DR-41 · SSE: kênh, id sự kiện, kết nối lại
- **Quyết định:**
  - `GET /api/v1/stream?channels=vehicles,alerts,jobs,dlq&routeId=…`. Kênh `vehicles` và `alerts` (phần public) không cần xác thực. Kênh `jobs` và `dlq` cần token. Vì `EventSource` không gửi được header, SPA dùng `@microsoft/fetch-event-source` cho kênh cần xác thực.
  - Publisher gán id sự kiện là ULID (có thứ tự thời gian). Mỗi pod API giữ ring buffer 5 phút. Khi client kết nối lại với `Last-Event-ID`, pod phát bù các sự kiện có id lớn hơn. Nếu vượt quá buffer thì gửi sự kiện `resync` để client refetch qua REST.
  - Kênh `vehicles` được gộp mỗi giây thành một sự kiện `vehicles.batch`, không đẩy từng vị trí riêng lẻ.
- **Ghi vào:** ADR-0016, DOC-26, DOC-33.

### DR-42 · Phát sự kiện UI: không dùng outbox
- **Quyết định:** ETL/analytics publish vào `pti.events.ui` **sau commit**, theo kiểu best-effort. Nếu mất một sự kiện thì chấp nhận được, vì nguồn sự thật nằm ở DB (`alert_event`, các bảng insight) và client refetch định kỳ 60 giây hoặc khi nhận `resync`.
- **Ghi vào:** ADR-0026.

### DR-43 · Endpoint còn thiếu trong SDD gốc
- **Quyết định bổ sung:** `GET /routes/{id}` (chi tiết, kèm shape GeoJSON), `GET /stops?bbox=&q=`, `GET /stops/{id}`, `GET /alerts?audience=&since=`, `POST /alerts/{id}/ack`, `GET /etl/jobs/{id}` (kèm step execution và số liệu read/write/skip từ metadata Spring Batch), `POST /etl/jobs/{id}/restart` (chỉ cho job batch FAILED; API ghi yêu cầu, `etl-batch` gọi `JobOperator.restart`), `GET /etl/jobs/summary?bucket=1m`, `GET /etl/dlq/{id}`, `PUT /etl/dlq/{id}/payload`, `POST /etl/dlq/{id}/discard`, `GET /etl/dlq/actions` (nhật ký auto-replay), `GET /etl/replays`, `GET /etl/replays/{id}`, `GET/PUT /etl/flags/{key}`, `GET /system/freshness`, `GET /me`. Proxy điều khiển simulator `/sim/**` chỉ có ở profile demo.
- **Ghi vào:** DOC-32.

### DR-44 · Contract testing — ⚠ lệch SDD gốc
- **Quyết định:** Không dùng Spring Cloud Contract (nặng và thiên về JVM↔JVM). Thay bằng:
  1. JSON Schema cho message Kafka, dùng chung cho test producer (simulator) và test consumer (ETL).
  2. Chạy Debezium thật trong Testcontainers để kiểm tra parser CDC.
  3. Sinh `openapi.json` khi build và commit vào repo; CI chạy `openapi-diff` chặn breaking change; frontend sinh type bằng `openapi-typescript` và CI chạy typecheck.
- **Ghi vào:** ADR-0027, DOC-44.

### DR-45 · Rate limit
- **Quyết định:** Dùng Bucket4j in-memory, giới hạn theo IP cho endpoint public (60 request/phút, tối đa 5 kết nối SSE đồng thời mỗi IP). Giới hạn tính theo từng pod; ghi rõ hạn chế này trong tài liệu.
- **Ghi vào:** DOC-31.

---

## G. Frontend và UX

### DR-46 · Stack frontend — **Chốt**
- **Quyết định:** Vite, React 19, TypeScript strict, pnpm. TanStack Router (search params có kiểu, tiện đồng bộ bộ lọc lên URL). TanStack Query. Tailwind CSS cùng shadcn/ui (Radix). Apache ECharts cho biểu đồ chuỗi thời gian. TanStack Table + TanStack Virtual cho bảng DLQ. MapLibre GL JS qua `react-map-gl/maplibre`. react-hook-form + zod. CodeMirror 6 để sửa payload JSON. `react-oidc-context`. Zustand cho state UI. Test bằng Vitest, React Testing Library, MSW và Playwright.
- **Ghi vào:** ADR-0020, DOC-11.

### DR-47 · Bản đồ khi demo offline — **Chốt** (đã xác minh ở S-05)
- **Quyết định:** Dùng MapLibre với file PMTiles cắt riêng vùng thành phố của feed, serve qua nginx của frontend, để demo không phụ thuộc internet. Khi dev có thể dùng style từ nhà cung cấp tile miễn phí (ví dụ OpenFreeMap).
- **Kết quả spike S-05** (2026-09-28): file cắt cho bbox của feed ở maxzoom 15 nặng 84 MB. Bản đồ render hoàn toàn offline, không có request ra ngoài, ở cả nền sáng lẫn tối. Chi tiết ở ADR-0021; các thay đổi kéo theo ở DR-82.
- **Ghi vào:** ADR-0021.

### DR-48 · Ngôn ngữ và hiển thị thời gian — **Chốt**
- **Quyết định:** **Giao diện hoàn toàn bằng tiếng Anh.** Chuỗi hiển thị gom vào `src/i18n/en.ts`, nhưng chưa dựng framework i18n. Thời gian hiển thị theo múi giờ của agency, có nhãn múi giờ. Số, ngày và tiền định dạng theo `en-US` (khớp với feed Minneapolis ở DR-01). Microcopy trong DOC-37 và `screens/*` ghi đúng chuỗi tiếng Anh sẽ hiện trên UI; phần giải thích xung quanh vẫn viết tiếng Việt.
- **Ghi vào:** DOC-37, DR-61.

### DR-49 · Màn điều khiển kịch bản demo — **Chốt**
- **Quyết định:** Thêm tab "Demo control" trong ops console, chỉ hiện khi bật profile `demo`, để bật và tắt kịch bản simulator mà không phải dùng curl. Việc này giúp buổi bảo vệ trơn tru hơn.
- **Ghi vào:** DOC-36 (`screens/demo-control.md`: route `/ops/demo`, cần role operator và `demoControl` trong `env.js`).

---

## H. Vận hành, hạ tầng, thực nghiệm

### DR-50 · Nơi lưu trữ log và trace
- **Vấn đề:** SDD gốc có OpenTelemetry nhưng chưa có nơi lưu trace, và log JSON cũng chưa có nơi lưu.
- **Quyết định:** Metrics: Micrometer → Prometheus (scrape), gồm metric có sẵn của Spring Batch và Spring Kafka. Traces: Micrometer Observation → Micrometer Tracing (bridge OpenTelemetry) → OTLP → OTel Collector → Grafana Tempo; bật observation của Spring Kafka (`observation-enabled` cho listener và template) để `traceparent` đi qua Kafka header, và observation của Spring Batch cho job/step. Không dùng OTel Java agent song song để tránh span trùng. Logs: JSON có cấu trúc (structured logging có sẵn của Spring Boot) ra stdout → Grafana Alloy → Loki, `trace_id`/`span_id` tự vào MDC nhờ Micrometer Tracing. Tất cả nằm trong compose profile `observability`.
- **Ghi vào:** ADR-0022, DOC-28.

### DR-51 · Kênh cảnh báo khi demo
- **Quyết định:** Alertmanager gửi tới (1) Mailpit (SMTP giả, có web UI) và (2) webhook `POST /internal/alerts/alertmanager` của API. API ghi cảnh báo vào `alert_event` với `audience=ENGINEERING`, để alert feed trên dashboard có cả cảnh báo hạ tầng. Slack là tùy chọn.
- **Ghi vào:** DOC-28.

### DR-52 · Công cụ chạy thực nghiệm
- **Quyết định:** Python 3.12 quản lý bằng uv, dùng httpx, psycopg, docker SDK và `kubectl` (subprocess), pandas, matplotlib. Mỗi lần chạy lưu `experiments/results/<EXP>/<run_id>/` gồm `config.json` (git SHA, tham số, môi trường), `raw.csv`, `summary.json` và biểu đồ.
- **Ghi vào:** ADR-0025, DOC-45.

### DR-53 · Phiên bản công nghệ — **Chốt: dùng bản mới nhất** · ⚠ lệch SDD gốc
- **Quyết định:**
  - **Spring Boot 4.1.x** (bản mới nhất lúc viết là 4.1.1, được hỗ trợ tới 2027-07-31; nguồn: [spring.io](https://spring.io/blog/2026/06/10/spring-boot-4/), [endoflife.date](https://endoflife.date/spring-boot)). Kéo theo Spring Framework 7, Jakarta EE 11, Spring Kafka 4.x, Spring Security 7.
  - **Java 25 LTS** (đề xuất thay cho Java 21 của SDD gốc, cho khớp với nguyên tắc "mới nhất"; Boot 4.1 hỗ trợ tới Java 26).
  - Kafka 4.x (chỉ còn KRaft), Debezium 3.x, PostgreSQL 17 (dùng 18 nếu CNPG và Debezium đã hỗ trợ ổn định), Node 24 LTS, React 19, `typesafe-java-sdk` 0.2.x.
  - Ghi version cố định vào `gradle/libs.versions.toml`, `frontend/package.json` và `.tool-versions` (mise). Mỗi phase kiểm tra lại bản patch mới nhất một lần.
- **Cần xác minh trong spike S-06:**
  0. **Spring Batch 6.x (đi kèm Boot 4.1) cho ETL (ADR-0002):** (a) API của fault-tolerant chunk step (skip, retry, scan) và cơ chế retry nó dựa vào (Spring Framework 7 core retry thay cho Spring Retry); (b) `SkipListener` vẫn được gọi trong transaction của chunk; (c) cách bật JobRepository JDBC thay cho bản resourceless; (d) có API khôi phục execution kẹt ở STARTED hay phải tự cập nhật (DR-24); (e) `JobOperator` thay cho `JobLauncher`; (f) ShedLock, Spring Cloud AWS S3 và `ContainerPausingBackOffHandler` của Spring Kafka 4 có bản tương thích. Một app mẫu phải chạy được test "lỗi ghi ở item thứ 37 → 499 dòng ghi, 1 dòng DLQ, restart đọc tiếp đúng vị trí".
  1. Resilience4j đã hỗ trợ Spring Boot 4 chưa. Nếu chưa, thử cơ chế resilience có sẵn trong Spring Framework 7 (`@Retryable`, `@ConcurrencyLimit`), còn circuit breaker và rate limiter vẫn dùng Resilience4j core, cấu hình thủ công không qua starter.
  2. springdoc-openapi, Testcontainers, Micrometer Tracing (bridge OTel, DR-50) và jib-gradle có tương thích với Boot 4.1 và Java 25 không.
  3. Spring Boot 4 đổi package (Jackson 3, tách module autoconfigure). Ghi các thay đổi này vào DOC-11 để người triển khai không làm theo tài liệu cũ của Boot 3.
- **Kết quả spike S-06** (2026-09-28, app mẫu `spikes/s06-boot41-java25/`, 20 test trên Postgres 17.11 và 18.1, Kafka 4.3.1, SeaweedFS 4.47):
  - 0(a) Batch 6.0.5 có hai builder chunk step. `ChunkOrientedStepBuilder` mới dùng core retry của Spring Framework 7; builder cũ `chunk(size, tx).faultTolerant()` (deprecated for removal) vẫn dùng Spring Retry 2.0.x. (b) Skip listener chạy trong transaction của chunk và dòng DLQ được commit **chỉ với builder cũ**; step mới rollback dòng DLQ. Thêm nữa, step mới bỏ sót item khi process chết giữa lúc scan. Chọn builder cũ, xem DR-80. (c) `spring-boot-starter-batch-jdbc` + `spring.batch.jdbc.table-prefix=batch.BATCH_`; context lưu JSON qua bean `JacksonExecutionContextStringSerializer`. (d) Có `JobOperator.recover(JobExecution)`: đưa execution kẹt về `FAILED`, tăng `VERSION`; bản giữ `VERSION` cũ cập nhật thì nhận `OptimisticLockingFailureException`. (e) `JobOperator.start` thay `JobLauncher.run`; `restart` cần job đăng ký trong `JobRegistry`. (f) ShedLock 7.10.1, Spring Cloud AWS 4.1.1, `ContainerPausingBackOffHandler` của Spring Kafka 4.1.1 đều chạy được. Test "lỗi ghi ở item 37" đạt 499 dòng, 1 DLQ; restart đọc tiếp đúng từ item 701.
  - 1. Resilience4j 2.4.0 có module `resilience4j-spring-boot4`; không cần cấu hình thủ công.
  - 2. springdoc 3.1.1, Testcontainers 2.0.5, Micrometer Tracing (bridge OTel qua `spring-boot-starter-opentelemetry`) và Jib 3.5.4 tương thích.
  - 3. Khác biệt so với Boot 3 ghi ở DOC-11 §6.
  - PostgreSQL 18.1 chạy được với schema Spring Batch và toàn bộ test; việc đổi sang 18 còn chờ S-04 (Debezium) và CNPG.
- **Kết quả spike S-04** (2026-09-28): Debezium 3.6.3 chạy đúng trên PostgreSQL 18.6 (snapshot, insert, update, delete, heartbeat; slot `pgoutput`). Chỉ còn CNPG chưa kiểm. **Quyết định: giữ 17.11 cho P1–P6.** P7-01 dựng CNPG với image 18; nếu chạy được thì đổi compose và k3d sang 18 trong cùng một thay đổi. Lúc đổi phải sửa mount volume, vì image 18 đặt dữ liệu ở `/var/lib/postgresql/18/docker` (DOC-11 §2). Dữ liệu dev dựng lại được bằng `make reset`, nên không cần `pg_upgrade`.
- **Ghi vào:** ADR-0029 (mới), DOC-11.

### DR-54 · Công cụ Kubernetes
- **Quyết định:** k3d cùng registry cục bộ. Cài operators bằng `helmfile` (Strimzi, CloudNativePG, KEDA, Chaos Mesh, Sealed Secrets, kube-prometheus-stack). Ứng dụng đóng thành một umbrella chart `pti` với các values `dev`, `staging`, `lite`.
- **Ghi vào:** ADR-0028, DOC-40.

### DR-55 · Debezium khi Postgres failover
- **Vấn đề:** Replication slot logic có thể mất sau failover nếu không được đồng bộ sang replica.
- **Quyết định:** Database nguồn ticketing là một instance **riêng, đơn lẻ**, không nằm trong kịch bản failover. EXP-08 chỉ failover **warehouse**. Ghi rõ giới hạn này. Nếu còn thời gian, thử failover slot trên PG17 + CNPG như một spike bổ sung.
- **Ghi vào:** DOC-40, DOC-45 (EXP-08).

### DR-56 · Repo và registry — **Chốt: GitHub public** (đổi từ private ngày 2026-09-27)
- **Quyết định:** Repo GitHub **public**. Image đẩy lên GHCR, package để public, theo dạng `ghcr.io/<owner>/pti-<app>:<git-sha>`, build cho `linux/amd64` và `linux/arm64`. Khi dev trên k3d thì dùng registry cục bộ `k3d-pti-registry:5000`. Image build bằng Jib; image Kafka Connect có plugin build bằng Dockerfile riêng.
- **Lý do đổi:** với repo private, runner chuẩn của GitHub chỉ có 2 vCPU và 7 GB RAM và số phút có hạn, nên E2E và k3d phải chạy trên self-hosted runner là máy dev. Với repo public, runner chuẩn có 4 vCPU, 16 GB RAM và không giới hạn phút (số liệu lúc chốt), đủ cho compose đủ profile hoặc k3d `lite`.
- **Hệ quả:**
  - Mọi workflow chạy trên runner của GitHub; không dùng self-hosted runner, vì GitHub khuyến cáo không dùng self-hosted runner cho repo public (PR từ fork có thể chạy code trên máy).
  - Mỗi PR: format, build, unit test, integration test cho các module có thay đổi (lọc theo path, để phản hồi nhanh). Khi merge vào `main`: thêm contract test, quét bảo mật, build image. E2E và k3d: `full-stack.yml` hằng đêm và bấm tay.
  - Code, tài liệu, log Actions và artifact đều công khai: không dùng `pull_request_target`, secret deploy nằm trong environment giới hạn `main`, bật secret scanning và push protection (DOC-41 §9.1). Cần kiểm tra quy định của trường về việc công khai mã nguồn đồ án trước khi bảo vệ.
  - Secret `TYPESAFE_API_KEY` lưu trong GitHub Secrets; CI dùng `provider=fake` nên không cần key.
- **Ghi vào:** DOC-41, DOC-40 §3.2, DOC-01 §8.

### DR-57 · Cách đo NFR-03 (độ trễ đầu-cuối)
- **Quyết định:** `end_to_end_latency_seconds` là histogram đo **tại API ngay lúc phát SSE**, bằng `now − Kafka record timestamp (CreateTime)` của event gốc. Timestamp gốc được mang theo trong sự kiện UI. Thêm hai metric chặng để biết chậm ở đâu: `kafka_to_commit_seconds` (tại ETL) và `commit_to_emit_seconds` (tại API). Trên compose/k3d, mọi thành phần chạy chung một máy nên chung đồng hồ. Playwright đo thêm độ trễ hiển thị cho phần demo.
- **Ghi vào:** DOC-10, DOC-28.

### DR-58 · Cách đo "khớp hoàn toàn" ở EXP-04
- **Quyết định:** So sánh từng bảng theo hai tiêu chí: (1) số dòng; (2) `md5(string_agg(<các cột nghiệp vụ>::text, '|' ORDER BY <business key>))`. Loại khỏi phép so sánh các cột `batch_id, ingested_at, updated_at, computed_at`. Id của insight là UUIDv5 tất định nên vẫn được đưa vào so sánh.
- **Ghi vào:** DOC-45.

### DR-59 · Minh chứng schema evolution
- **Quyết định:** Tạo sẵn `schema_version=2` cho VehiclePosition, thêm trường optional `occupancy_status`. Simulator phát xen kẽ v1 và v2. Test chứng minh parser đọc được cả hai phiên bản và message v3 (chưa biết) sẽ vào DLQ với `stage=SCHEMA`.
- **Ghi vào:** DOC-09, DOC-20.

### DR-60 · Dữ liệu cá nhân (PII)
- **Quyết định:** Trường `customer_ref` bị loại tại processor của ETL, trước bước ghi và trước bước ghi DLQ (payload lưu vào DLQ cũng phải đã được làm sạch). Prompt builder của triage có test khẳng định không có trường nào nằm trong danh sách chặn. Raw zone (SeaweedFS, DR-66) vẫn giữ nguyên bản gốc, và quyền truy cập bucket được giới hạn.
- **Ghi vào:** DOC-18, DOC-27.

### DR-61 · Quy ước ngôn ngữ — **Chốt**
- **Quyết định:** **Tài liệu trong `docs/` viết tiếng Việt. Mọi thứ khác dùng tiếng Anh:** chuỗi trên UI, tên biến và class, comment, log, thông báo lỗi API (`title`/`detail` của Problem Details), tên metric, nhãn dashboard Grafana, mô tả alert, commit message, tiêu đề và nội dung PR, tên test, README trong từng module code, nội dung gửi sang Jev (state và mô tả option).
- **Hệ quả:** trong các tài liệu tiếng Việt, mọi chuỗi sẽ xuất hiện trong sản phẩm (microcopy, thông báo lỗi, tên alert) được ghi nguyên văn bằng tiếng Anh.
- **Ghi vào:** DOC-37, master plan §7.3.

### DR-66 · Object storage cho raw zone: SeaweedFS thay MinIO — **Chốt** · ⚠ lệch SDD gốc
- **Vấn đề:** SDD gốc dùng MinIO. Spike S-03 (2026-09-26) xác nhận repository `minio/minio` không còn trên Docker Hub (`pull access denied … repository does not exist`), Quay cũng không có tag nào. Một máy sạch không kéo được image, nên vi phạm NFR-07.
- **Các phương án đã thử** (cùng bộ kiểm tra S3: tạo bucket, versioning, ghi hai phiên bản, lifecycle có `NoncurrentVersionExpiration`, multipart 12 MB, delete marker):
  - **SeaweedFS 4.47** (Apache-2.0, từ 2012, phát hành hằng tuần): đạt cả bộ kiểm tra. Credential riêng theo identity (`s3.json`), có quyền theo bucket: 9/9 trường hợp đúng kỳ vọng (connector chỉ ghi `raw`, etl chỉ đọc, sai key bị từ chối). RAM đỉnh 196 MiB.
  - **RustFS 1.0.0** (Apache-2.0, tương thích MinIO): đạt cả bộ kiểm tra, RAM đỉnh 132 MiB. Tuy nhiên bản 1.0 mới phát hành 10 ngày trước spike.
  - Garage: AGPL, không thử.
- **Quyết định:** Dùng **SeaweedFS**, pin `chrislusf/seaweedfs:4.47`, chạy `server -s3` (master, volume, filer và S3 gateway trong một container), `mem_limit` 384 MB. Credential của `connect` (Read/Write/List trên `raw`), `etl` (Read/List trên `raw`, Write chỉ trên `raw/gtfs-static/*`; S-04 xác nhận SeaweedFS hỗ trợ quyền theo prefix, DOC-18 §3) và `admin` (chỉ job `s3-init`) nằm trong `s3.json` sinh từ `.env`. Job `s3-init` dùng `amazon/aws-cli` để tạo bucket, bật versioning và đặt lifecycle. Code chỉ dùng API S3 chuẩn với path-style access; RustFS là phương án dự phòng, đổi không phải sửa code.
- **Ghi vào:** DOC-07, DOC-10, DOC-11, ADR-0012, DOC-18, DOC-39, DOC-40.

### DR-67 · Đồng hồ nghiệp vụ và độ lệch giờ — **Chốt**
- **Vấn đề:** Feed dùng giờ `America/Chicago`, lệch 12 giờ (CDT) hoặc 13 giờ (CST) so với Việt Nam. Demo hay thực nghiệm lúc 14:00 ở Hà Nội rơi vào khoảng 02:00 ở Minneapolis, khi hầu như không có xe chạy; EXP-05/07 cần tải nền khoảng 500 xe. `pti.sim.time-offset` của DR-08 chỉ dời đồng hồ của simulator, nên dữ liệu sẽ lệch với phần còn lại: headway theo giờ (DR-12) tra theo giờ thật và báo bunching nhầm hàng loạt, rule DQ "`event_timestamp` lệch quá ±1 giờ so với hiện tại" loại mọi message, API "24 giờ gần nhất" bỏ sót dữ liệu mới.
- **Quyết định:**
  - Một **đồng hồ nghiệp vụ** `businessNow = giờ hệ thống + pti.clock.offset`, dùng chung cho simulator, etl (cả hai profile) và api; env `PTI_CLOCK_OFFSET`, Duration, mặc định `0s`, làm tròn tới phút, trong khoảng ±24 giờ. Compose lấy từ một biến trong `.env`; `make clock-offset AT=16:30` tính và ghi giá trị.
  - Mỗi app có bean `java.time.Clock` (`BusinessClock` trong `common`). Logic nghiệp vụ chỉ lấy "bây giờ" từ bean này. SQL không so cột event time với `now()` của DB mà nhận tham số `:now`.
  - Theo giờ nghiệp vụ: `event_timestamp` của GTFS-rt, `created_at` của giao dịch vé (simulator đặt tường minh), ngày phục vụ, partition theo ngày, rule DQ về thời gian, độ tươi dữ liệu, khoảng thời gian mặc định của API.
  - Theo giờ thật: timestamp record Kafka (CreateTime), `produced_at`, `__source_ts_ms` của Debezium, cột audit (`ingested_at`, `updated_at`), log và trace, `sim_ledger.produced_at`.
  - Replay theo khoảng thời gian đổi giờ nghiệp vụ sang giờ record bằng offset hiện tại; không đổi offset giữa lúc ghi dữ liệu và lúc replay (EXP-04 chạy với một offset cố định). Frontend tính "cách đây bao lâu" theo giờ server.
  - Offset được ghi vào `config.json` của mỗi lần chạy thực nghiệm.
- **Ghi vào:** DOC-25, DOC-16, DOC-22, DOC-26, DOC-29, DOC-38, DOC-39, DOC-45.

### DR-68 · Tạo tải gấp N lần — **Chốt**
- **Vấn đề:** DOC-13 §4 dự kiến nhân bản chuyến và xe (`<vehicle_id>-X<k>`) để tạo tải. Chuyến nhân bản cần `trip_id` mới (nếu giữ `trip_id` gốc thì business key của TripUpdate trùng và ghi đè nhau), nên rule DQ tham chiếu trip sẽ loại chúng; xe nhân bản chạy lệch pha cũng làm bunching báo nhầm hàng loạt.
- **Quyết định:** Tải gấp N lần được tạo bằng cách **chia chu kỳ phát** VehiclePosition và TripUpdate cho N (`rateMultiplier.gtfsRt`, `PUT /sim/rate`, kịch bản `load-ramp`). Mọi tham chiếu vẫn hợp lệ, analytics không đổi ngữ nghĩa, còn số message, số lần upsert và lag tăng đúng N lần. Ở N = 10, mỗi xe phát vị trí mỗi 0,5 giây (khoảng 1.200 VehiclePosition/giây lúc cao điểm). Không nhân bản xe.
- **Ghi vào:** DOC-13 §4, DOC-25, DOC-45 (EXP-05, EXP-07).

### DR-69 · Gán stage DLQ cho rule chất lượng — **Chốt**
- **Vấn đề:** FR-02.3 xếp "route, stop, trip không có trong feed" và "`event_timestamp` lệch ±1 giờ" vào stage `BUSINESS`, trong khi DR-25 và DOC-25 §7.3 xếp chúng vào `QUALITY`. FR-02.4 đưa mọi bản cũ hơn của cùng business key trong một chunk vào DLQ, nhưng khi consumer đuổi lag thì hai TripUpdate của cùng chuyến trong một chunk là chuyện bình thường; DLQ sẽ đầy record hợp lệ. Stage `DEDUP` có trong DDL nhưng chưa rule nào dùng.
- **Quyết định:**
  - `SCHEMA`: sai cấu trúc (DQ-01). `QUALITY`: giá trị không hợp lý, xét trên chính record cùng dữ liệu tham chiếu tĩnh (feed ACTIVE, đồng hồ nghiệp vụ). `BUSINESS`: phụ thuộc record khác trong warehouse (refund tham chiếu giao dịch gốc). `DEDUP`: cùng key, cùng mốc thời gian nhưng khác nội dung trong một chunk. `LOAD`: lỗi dữ liệu do DB báo khi ghi.
  - Bản cũ hơn của cùng key trong một chunk được **gộp** (giữ bản mới nhất, cộng vào `records_duplicate`), không vào DLQ.
  - Với stage do rule sinh ra, `rule_id` và `error_class` đều bằng mã rule.
- **Ghi vào:** DOC-16, DOC-03 (sửa FR-02.3, FR-02.4), DOC-20.

### DR-71 · Metric thay cho exporter, và chia DR-57 theo chặng — **Chốt**
- **Vấn đề:** (1) DOC-39 không chạy exporter riêng cho Kafka và Postgres (giữ ngân sách RAM), nhưng các alert `GtfsRtFeedStale` (dựa trên DB), `DebeziumWalRetained` (WAL giữ bởi slot trên `pg-source`) và trạng thái connector cần số liệu mà app chưa phát. DOC-08 §13 lại vẽ "postgres exporter". (2) DR-57 đặt `commit_to_emit_seconds` đo tại API, nhưng sự kiện UI chỉ mang `source_record_ts` và `occurred_at`, API không biết thời điểm commit. (3) Tên runbook đã bị các tài liệu trước dùng rải rác (RB-03, RB-05, RB-06, RB-10…12), không theo thứ tự 9 alert của SDD.
- **Quyết định:**
  1. Không thêm exporter trên compose. Số liệu thiếu do app phát, tính từ nguồn sự thật:
     - `api` phát `pti_source_last_event_age_seconds{source}` (truy vấn DB mỗi 15 giây, theo đồng hồ nghiệp vụ). `api` độc lập với `etl-stream` nên alert vẫn bắn khi mọi pod ETL đã chết.
     - `source-simulator` (đã kết nối `pg-source`) phát `pti_source_replication_slot_retained_bytes{slot}` từ `pg_replication_slots` mỗi 30 giây. Trên hệ thật, việc này thuộc về người vận hành DB nguồn; ở đây simulator đóng vai hệ nguồn.
     - `etl-stream` phát `pti_connect_connector_running{connector}` từ Kafka Connect REST (cùng poller với health `source-ticketing`, DOC-20 §6.1).
     - Trên k3d (P7) có thêm exporter của Strimzi và CNPG, nhưng alert vẫn dùng metric của app để hai môi trường có cùng bộ luật.
  2. DR-57 được hiện thực bằng bốn histogram: `pti_etl_kafka_to_commit_seconds` (ETL, chặng 1–2), `pti_ui_commit_to_publish_seconds` (ETL, chặng 3), `pti_api_publish_to_emit_seconds` (API, chặng 4–5, `emit − occurred_at`) và `pti_end_to_end_latency_seconds` (API, `emit − source_record_ts`). "`commit_to_emit_seconds`" của DR-57 và DOC-10 là tổng của chặng 3 và chặng 4–5, không phải một metric riêng. Không đổi hợp đồng sự kiện UI.
  3. Danh mục alert và số runbook chốt ở DOC-28 §6: RB-01…09 và RB-13, 14 cho alert, RB-10…12 cho thao tác (giữ nguyên các số đã được tài liệu trước dùng).
- **Ghi vào:** DOC-28, DOC-08 (§13), DOC-10, DOC-20, DOC-25, DOC-42.

---

## I. Triển khai k3d, demo và báo cáo

### DR-75 · Đọc warehouse khi failover trên k3d — **Chốt**
- **Vấn đề:** `api` đọc qua Pooler `-ro` (chỉ replica). Khi replica duy nhất bị promote hoặc chết, Pooler `-ro` không còn đích và API đọc lỗi trong lúc CNPG dựng lại replica, dù primary vẫn chạy.
- **Quyết định:** URL datasource `reader` trên k3d là JDBC nhiều host `pti-warehouse-pooler-ro, pti-warehouse-pooler-rw` với `targetServerType=preferSecondary` và `hostRecheckSeconds=10`; `maxLifetime` 5 phút để kết nối quay về replica sau khi có lại. Compose giữ một host.
- **Ghi vào:** DOC-40 §5.2, §9.5; DOC-29 §3.1; EXP-08 (F4).

### DR-76 · CI toàn stack và mã test k3d — **Chốt** (điều chỉnh theo DR-56 ngày 2026-09-27)
- **Vấn đề:** E2E trên compose đủ profile (≈ 10 GB) và k3d `lite` (13 GB) cần runner lớn; hai môi trường không vừa một máy cùng lúc. Mã test `K-xx` của k3d trùng với test Debezium `K-01…K-08` (DOC-44 §9.2).
- **Quyết định:** Workflow `full-stack.yml` chạy hằng đêm và bằng tay trên runner `ubuntu-24.04` của GitHub (16 GB với repo public), hai job `e2e-compose` và `k3d-lite` song song trên hai runner, mỗi job dọn đĩa và kiểm tài nguyên trước. Release đòi một lần chạy thành công trên đúng SHA. PR chỉ chạy job không cần stack, cộng `k8s-render`. Test k3d dùng mã `KD-01…KD-15`. Ban đầu (khi repo còn private) workflow này chạy trên self-hosted runner là máy dev; phương án đó đã bỏ.
- **Ghi vào:** DOC-41 §1, §7, §10.3–10.4; DOC-40 §17; DOC-44.

### DR-77 · Demo hai phần — **Chốt**
- **Vấn đề:** Máy 16 GB không chạy compose và k3d cùng lúc (DOC-10 §5); dựng k3d từ đầu mất khoảng 15 phút và cần Internet. Bunching cần 5–10 phút để hình thành.
- **Quyết định:** Phần A (bước 1–6) trên `make up-demo`; phần B (bước 7) trên k3d `lite` được dựng từ hôm trước rồi `make k8s-stop`, lúc demo chỉ `make k8s-start` (offline). Bunching và gián đoạn được gieo trước 12 phút (`make demo-prewarm`) và người trình bày nói rõ điều đó. Đối chiếu "không mất, không trùng" tại chỗ bằng `pti-exp check`.
- **Ghi vào:** DOC-46; DOC-38 §4.5–4.6; DOC-40 §14; DOC-45 §2.

### DR-78 · Lệnh vận hành dùng chung cho k3d — **Chốt**
- **Vấn đề:** Runbook viết cho compose bằng lệnh `make`; viết lại mọi lệnh cho k3d thì hai bản dễ lệch nhau.
- **Quyết định:** Các lệnh `make` đọc và ghi dữ liệu (`psql-*`, `topics`, `tail-*`, `connectors`, `s3-ls`, `sim-*`, `scenario*`, `flag`, `job-*`, `replay`, `gtfs-load`, `ensure-partitions`) nhận `PTI_ENV=k3d` và chạy qua `kubectl`. Runbook chỉ ghi phần khác biệt trong mục "Trên k3d". Thay đổi connector trên k3d đi qua CR `KafkaConnector`, không qua REST.
- **Ghi vào:** DOC-42 §2.1 và từng RB.

### DR-79 · Nguồn số liệu của báo cáo — **Chốt**
- **Vấn đề:** Số liệu chép tay vào báo cáo dễ sai và không truy lại được; SDD §15 yêu cầu trình bày độ nhạy ngưỡng analytics mà chưa có cách làm.
- **Quyết định:** Mọi bảng và biểu đồ thực nghiệm trong báo cáo lấy từ `pti-exp report` trên commit đã tag. Độ nhạy ngưỡng là phân tích mô tả (không phải EXP) bằng `pti-exp sensitivity`: quét một tham số mỗi lần, tính lại analytics trên cùng dữ liệu, so với kịch bản đã gieo.
- **Ghi vào:** DOC-47; DOC-45 §2.

### DR-80 · Cách dựng chunk step của Spring Batch 6 — **Chốt** (sau S-06)
- **Vấn đề:** Spring Batch 6.0 có `ChunkOrientedStep` mới và đánh dấu builder cũ (`SimpleStepBuilder`/`FaultTolerantStepBuilder`) là deprecated for removal. S-06 chạy cùng một job trên cả hai (6.0.5, Postgres thật). Step mới: (1) khi scan, rollback transaction của item bị skip **sau** `onSkipInWrite`, nên dòng DLQ và `writeSkipCount` mất; (2) mỗi transaction của scan lưu vị trí reader của cả chunk, nên process chết giữa scan rồi restart thì các item chưa scan bị bỏ qua (298/499 dòng). Cả hai điểm vi phạm FR-02.5 và NFR-01.
- **Các phương án:** (a) builder cũ, đúng ở mọi test nhưng sẽ bị xóa ở bản major sau; (b) step mới, sai; (c) bỏ skip/scan của Spring Batch, tự scan trong writer bằng savepoint như `StreamChunkTemplate`: đúng, nhưng phải tự làm retry cả chunk và skip ở processor.
- **Quyết định:** (a). Mọi chunk step dựng bằng `chunk(size, tx).faultTolerant()` với `@SuppressWarnings("removal")`. Test B-05, B-18 và luật ArchUnit B-19 (DOC-19 §12) giữ lựa chọn này. Khi nâng lên bản Spring Batch không còn builder cũ: nếu step mới đã sửa (B-05, B-18 xanh trên step mới) thì chuyển sang, nếu chưa thì làm (c). Nên báo hai lỗi này lên issue tracker của Spring Batch kèm app mẫu.
- **Ghi vào:** DOC-19 §4.4, §5, §7.2, §12; ADR-0005; DOC-11.

### DR-81 · Định dạng value và giới hạn bộ nhớ của S3 sink — **Chốt** (sau S-04)
- **Vấn đề:** S-04 chạy Aiven S3 sink 3.4.3 với cấu hình dự kiến của ADR-0012 và phát hiện hai điểm.
  1. **Mất byte.** Với `StringConverter`, byte không phải UTF-8 bị thay bằng U+FFFD. `etl-stream` có nhánh riêng cho byte như vậy (DOC-20 §4.1, test S-08): ghi DLQ `DESERIALIZE`. Simulator hiện không sinh loại lỗi này, nhưng một producer bất kỳ thì có thể, và replay từ raw zone lại thấy một chuỗi UTF-8 hợp lệ. Nếu byte hỏng nằm trong một trường chuỗi, record có thể được ghi vào fact, tức replay cho kết quả khác luồng trực tiếp (vi phạm FR-01.4, EXP-04).
  2. **OOM khi chạy bù.** Mỗi file đang mở giữ một buffer multipart 5 MiB trên heap tới lần commit kế tiếp. SeaweedFS từ chối part nhỏ hơn 5 MiB (`EntityTooSmall`), nên không giảm được buffer. Với `file.max.records=10000`, khi dồn 480 nghìn record rồi cho sink chạy bù, 96–115 file mở cùng lúc và task chết vì `OutOfMemoryError` ở cả heap 512 MB lẫn 768 MB. Task không tự khởi động lại, còn nếu khởi động lại thì gặp đúng tải đó lần nữa.
- **Các phương án:**
  - Định dạng: (a) `StringConverter`, chấp nhận mất byte hỏng; (b) `ByteArrayConverter` + `format.output.fields.value.encoding=base64`.
  - Bộ nhớ: (c) tăng heap lên khoảng 1 GB (đã thử: chạy qua với đỉnh 972/1.024 MiB, không còn dư); (d) giảm `file.max.records` xuống 2.000; (e) part size 1 MiB (bị SeaweedFS từ chối khi file lớn hơn 1 MiB).
- **Quyết định:** (b) và (d).
  - Value lưu base64. Replay giải base64 thành `byte[]` rồi đi qua đúng bước giải mã của `etl-stream` (DOC-22 §4.4).
  - `file.max.records=2000`. Connector yêu cầu commit ngay khi một file đạt ngưỡng, và commit đóng mọi file đang mở. Đo được: tối đa 49 file mở; chạy bù 1,02 triệu record trên 30 partition trong khoảng 15 giây ở `-Xmx512m`; heap đỉnh 468 MiB, container đỉnh 1.009 MiB. Không OOM, không mất, không trùng.
  - `kafka-connect` có `mem_limit` 1.280 MB (k3d: limit 1280Mi), heap giữ 512 MB. Worker đặt `offset.flush.interval.ms=300000`.
  - **Không tăng `file.max.records`**, cũng không thêm topic nhiều partition vào sink mà không đo lại (test C-10 của DOC-39).
- **Hệ quả:** File raw zone không đọc được bằng mắt, nên thêm `make raw-cat`. Số object nhiều hơn: VehiclePosition khoảng 48.000 object mỗi 7 ngày ở tải nền, nên `pti.replay.max-objects` tăng lên 100.000. Aiven không ghi trường `partition`, nên reader lấy partition từ tên file.
- **Ghi vào:** ADR-0012, DOC-09 §7, DOC-10 §5, DOC-11, DOC-18 §2, DOC-22 §4.3–4.4, DOC-38, DOC-39 §3.4, DOC-40 §6.3.


### DR-82 · MapLibre 6, font bản đồ và CSP — **Chốt** (sau S-05)
- **Vấn đề:** DOC-11 ghi MapLibre 5.x. Lúc spike, bản mới nhất là 6.11.2 (6.0.0 ra ngày 2026-07-22). Bản 6 chỉ phát hành ESM và tải worker từ một file riêng, nên hành vi khác bản 5 ở hai điểm: đường dẫn worker sau khi Vite build, và CSP (bản 5 tạo worker từ blob URL, nên DOC-27 phải mở `worker-src blob:`). Ngoài ra, ADR-0021 định commit font và sprite vào `frontend/public/map/`, nhưng ba font Noto Sans đủ mọi dải glyph nặng 13 MB (771 file).
- **Quyết định:**
  - Dùng **MapLibre GL JS 6.x** (theo DR-53). `@vis.gl/react-maplibre` 8.1.3 (phần maplibre của `react-map-gl`) chấp nhận `maplibre-gl >=4`. Worker được đặt bằng `setWorkerUrl` với import `?worker&url` của Vite (ADR-0021).
  - CSP bỏ `blob:` khỏi `worker-src`, `child-src` và `img-src`. S-05 chạy được dưới CSP chặt hơn này.
  - Font và sprite không commit. `make tiles` tải chúng từ `protomaps/basemaps-assets` (commit pin) vào `infra/tiles/`; nginx phục vụ cùng chỗ với file PMTiles (`/tiles/`).
  - Style dựng lúc chạy bằng `@protomaps/basemaps` 5.x, không sinh file JSON lúc build. Theme sáng dùng flavor `grayscale`, theme tối dùng `black`; cả hai là nền không màu có sẵn nên không cần tự chỉnh màu.
- **Ghi vào:** ADR-0021, DOC-11, DOC-27 §5.3, DOC-34 §9, DOC-35 §6, DOC-38.
---

## Tổng hợp theo mức ảnh hưởng

| Mức | Mục | Lý do cần chốt sớm |
| --- | --- | --- |
| Chặn P1 | DR-01, 02, 03, 04, 05, 06, 09, 10, 11, 26, 53, 64, 66, 67, 68, 81 | Quyết định schema, contract và cấu trúc repo |
| Chặn P2 | DR-07, 13, 14, 15, 16, 18, 21, 22, 23, 24, 25, 62, 63, 65, 69, 70, 80 | Quyết định ngữ nghĩa đúng đắn của pipeline |
| Chặn P3 | DR-27, 28, 50, 57, 58, 71 | Thiếu thì không đo được thực nghiệm |
| Chặn P4 | DR-12, 17, 19, 20, 29–35, 39–45 | Analytics và API |
| Chặn P5 | DR-46–49, 82 | Frontend |
| Chặn P6 | DR-36, 37, 38, 60, 72, 73, 74 | AI triage |
| Chặn P7 | DR-54, 55, 56, 75, 76 | Kubernetes |
| Chặn P8 | DR-77, 78, 79 | Demo, runbook k3d, báo cáo |
