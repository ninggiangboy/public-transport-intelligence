# Yêu cầu

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-03
> Phụ thuộc: SDD gốc §3, [DR](../00-decision-register.md), [Glossary](../02-glossary.md), [DOC-01](vision-and-scope.md)

## 0. Quy ước

- FR-01…FR-12 giữ nguyên mã của SDD gốc. **FR-13…FR-15 là yêu cầu bổ sung**, trước đây chỉ được ngầm định trong SDD.
- Mỗi FR được tách thành các yêu cầu con `FR-xx.y`. Mỗi yêu cầu con có:
  - **Ưu tiên** theo MoSCoW: `M` = Must, `S` = Should, `C` = Could.
  - **Tiêu chí nghiệm thu** viết gọn dạng **G** (Given), **W** (When), **T** (Then).
  - **Kiểm chứng**: loại test hoặc EXP dùng để chứng minh yêu cầu đã đạt.
- Giá trị mặc định (ngưỡng, chu kỳ) đều cấu hình được. Tên key cấu hình nằm ở DOC-29.
- Chuỗi trong ngoặc kép `"…"` là chuỗi thật hiển thị trên UI (tiếng Anh).

---

## 1. Yêu cầu chức năng

### FR-01 · Thu thập dữ liệu

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-01.1 | Nạp GTFS static theo batch từ file zip (raw zone hoặc đường dẫn). Scheduler kiểm tra feed mới mỗi ngày lúc 03:30 (giờ agency); có thể chạy tay | **G** feed Minneapolis chưa có trong warehouse **W** `GtfsStaticLoadJob` chạy **T** tạo `feed_version` ACTIVE; `dim_route` có 127 dòng, `dim_stop` có 8.155 dòng (bằng số liệu của feed); job COMPLETED. **G** chạy lại với cùng file **T** job kết thúc với `exit_status=NOOP`, không ghi thêm dòng nào | M | Integration test |
| FR-01.2 | Tiêu thụ GTFS-rt từ `gtfs.vehicle_positions` và `gtfs.trip_updates` | **G** simulator đang phát ở tải nền **W** một VehiclePosition hợp lệ được publish **T** xuất hiện trong `fact_vehicle_position` và `vehicle_position_latest` trong vòng 10 giây (p95) | M | Integration test, EXP-05 |
| FR-01.3 | Tiêu thụ giao dịch vé qua CDC (`ticketing.sales.cdc`) và điểm bán (`ticketing.sale_points.cdc`) | **G** một INSERT vào `ticket_transaction` **W** Debezium phát event **T** có dòng trong `fact_ticket_sales` trong vòng 10 giây. **W** UPDATE `status=VOIDED` **T** dòng fact được cập nhật. **W** DELETE **T** `is_deleted=true` | M | Integration test (Debezium thật trong Testcontainers) |
| FR-01.4 | Lưu mọi message Kafka của các topic nguồn và mọi file GTFS zip vào raw zone | **G** 10.000 message đã publish **W** chờ rotate interval (≤ 5 phút) **T** đếm số dòng trong các object `raw/<topic>/…` được 10.000; mỗi dòng giữ key, headers, partition, offset, timestamp | M | Integration test, EXP-04 |
| FR-01.5 | Hỗ trợ `schema_version` 1 và 2 cho VehiclePosition | **G** message v2 có `occupancy_status` **T** nạp thành công, lưu giá trị. **G** message v3 **T** vào DLQ với `stage=SCHEMA` | M | Unit test, contract test |
| FR-01.6 | Nạp `dim_vehicle` từ `vehicles.txt`; tự đăng ký `vehicle_id` lạ từ dữ liệu realtime | **G** VehiclePosition có `vehicle_id` chưa có trong `dim_vehicle` **T** tạo dòng với `source=REALTIME`, `capacity=NULL`; **không** coi là lỗi | S | Integration test |

### FR-02 · Kiểm tra dữ liệu và DLQ

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-02.1 | Message không parse được JSON thì vào DLQ với `stage=DESERIALIZE` | **G** chunk có 1 message là JSON hỏng **T** 1 dòng DLQ `DESERIALIZE`, lưu nguyên văn `raw_payload`; các record còn lại được nạp | M | Unit test |
| FR-02.2 | Vi phạm Bean Validation thì vào DLQ với `stage=SCHEMA` | **G** chunk 500 record, record #37 thiếu `vehicle_id` **T** 499 dòng được ghi; 1 dòng DLQ `SCHEMA` có `error_message` nêu tên trường; offset commit tới cuối chunk | M | Unit test, EXP-03 |
| FR-02.3 | Vi phạm rule chất lượng thì vào DLQ. `stage=QUALITY`: route, stop, trip không có trong feed ACTIVE; `event_timestamp` lệch quá ±1 giờ so với đồng hồ nghiệp vụ (trừ khi đang replay); `amount < 0`. `stage=BUSINESS`: refund tham chiếu giao dịch không tồn tại. Danh mục đầy đủ ở DOC-16. *Sửa 2026-09-27 (DR-69): tách QUALITY và BUSINESS* | Mỗi rule có một ca test riêng: **G** record vi phạm **T** DLQ đúng stage của rule, `rule_id` = `error_class` = mã rule (`DQ-xx`) | M | Unit test |
| FR-02.4 | Rule DQ pre-write (DOC-16) chạy trên từng record trước khi ghi | **G** 2 record trùng business key trong cùng chunk, khác `event_timestamp` **T** giữ record có `event_timestamp` lớn hơn, bản kia được gộp và cộng vào `records_duplicate`. **G** cùng key, cùng `event_timestamp`, khác nội dung **T** giữ bản tới sau; bản kia vào DLQ `DEDUP` với rule `DQ-02`. *Sửa 2026-09-27 (DR-69)* | M | Unit test |
| FR-02.5 | Lỗi dữ liệu phát sinh khi ghi (SQLState 22xxx/23xxx) không làm fail cả chunk | **G** batch upsert ném lỗi vì 1 record **W** chuyển sang scan mode **T** ghi được 499 record; 1 record vào DLQ `LOAD`; ở streaming mọi thứ commit trong 1 transaction (savepoint); ở job batch Spring Batch scan mỗi item một transaction (DR-21) | M | Test fault-tolerant step trên cả hai chế độ (P2-03, P2-04) |
| FR-02.6 | Dòng DLQ chứa đủ thông tin để xử lý | Có đủ: `source, stage, error_class, error_message, raw_payload` (đã loại PII), `kafka_topic/partition/offset` (nếu có), `business_key` (nếu parse được), `batch_id`, `created_at`, `status=NEW` | M | Unit test |
| FR-02.7 | Job batch có skip limit theo tỷ lệ (DR-23) | **G** `pti.etl.batch.max-skip-ratio=0.2` **W** tỷ lệ record bị skip của step vượt 20% **T** step FAILED, job FAILED, phát alert "Batch job failed" | M | Test `SkipPolicy` |
| FR-02.8 | Lỗi hạ tầng **không** đưa dữ liệu vào DLQ | **G** Postgres không truy cập được trong 30 giây **T** DLQ không tăng; consumer pause; offset không commit; khi Postgres chạy lại thì dữ liệu được nạp đầy đủ | M | Integration test (P2-14), EXP-08 |

### FR-03 · Chống trùng và idempotency

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-03.1 | Mọi fact được ghi bằng upsert theo business key: `fact_trip_update(service_date, trip_id, stop_sequence)`, `fact_vehicle_position(vehicle_id, event_timestamp)`, `fact_ticket_sales(sale_date, transaction_id)` | **G** cùng một record xử lý 2 lần **T** số dòng không đổi | M | Integration test, EXP-02 |
| FR-03.2 | Event cũ không ghi đè event mới (event-time guard, hoặc guard theo LSN với CDC) | **G** TripUpdate có `event_timestamp=T2` đã nạp **W** TripUpdate cùng key với `T1 < T2` tới sau **T** dòng giữ giá trị của T2 | M | Integration test |
| FR-03.3 | Phát hiện message gửi lại y hệt bằng dedup registry; tăng `records_duplicate_total` | **G** kịch bản `duplicates(pct=10)` **T** metric tăng khoảng 10% số message; số dòng fact bằng số business key duy nhất | M | EXP-02 |
| FR-03.4 | Replay (DLQ và raw zone) bỏ qua dedup registry | **G** message đã có trong registry **W** chạy raw zone replay **T** dòng fact được ghi lại theo logic hiện tại | M | Integration test |
| FR-03.5 | Offset Kafka chỉ commit sau khi transaction của chunk đã commit | **G** consumer bị kill ở mọi điểm trong vòng xử lý chunk **W** khởi động lại **T** mất = 0, trùng = 0 (so với ledger) | M | Test tiêm lỗi (P2-07), EXP-01 |
| FR-03.6 | Job batch bị FAILED chạy lại thì tiếp tục ngay sau chunk cuối đã commit | **G** `GtfsStaticLoadJob` fail ở chunk 40/100 **W** restart **T** bắt đầu từ chunk 41; kết quả cuối giống lần chạy không lỗi | M | Test tiêm lỗi |

### FR-04 · Warehouse

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-04.1 | Warehouse theo star schema ở DOC-14 | Migration chạy sạch trên DB trống; chạy lại lần hai không lỗi; `\d` khớp với DDL trong DOC-14 | M | Test migration |
| FR-04.2 | Phiên bản hóa GTFS: chỉ 1 phiên bản ACTIVE; đổi phiên bản trong 1 transaction; feed không hợp lệ thì bị từ chối | **G** feed thiếu `stops.txt` **T** `feed_version.status=REJECTED` kèm `validation_report`; phiên bản cũ vẫn ACTIVE; ETL stream không bị gián đoạn | M | Integration test |
| FR-04.3 | Partition fact được tạo trước 7 ngày; partition quá retention bị drop | **G** hôm nay là D **T** có partition cho D…D+7. **G** retention vehicle position 14 ngày **T** partition cũ hơn D−14 không còn | M | Integration test |
| FR-04.4 | Mọi dòng fact có `batch_id` và `ingested_at` | Trong cửa sổ retention của metadata, mọi dòng có `batch_id` tham chiếu được tới `etl_stream_batch` hoặc `etl_batch_step` (DR-63) | M | DQ post-write `DQ-20` |

### FR-05 · Phát hiện bunching

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-05.1 | Chạy sau mỗi micro-batch cho các tuyến có dữ liệu mới, và theo tick 30 giây | **G** micro-batch có tuyến R **T** detector chạy cho R trong ≤ 2 giây sau commit | M | Integration test |
| FR-05.2 | Mở episode khi `gap < 0,5 × headway` trong 2 lần đánh giá liên tiếp; đóng khi `gap > 0,7 × headway`; loại 2 trạm đầu và 2 trạm cuối; mặc định chỉ áp cho bus | Bảng test trong DOC-23 §Bunching pass 100% | M | Unit test |
| FR-05.3 | Một episode đúng một dòng; chạy lại cho cùng kết quả (khóa theo event time, id UUIDv5) | **G** chạy detector 2 lần trên cùng dữ liệu **T** số dòng `insight_bus_bunching` không đổi; id giữ nguyên | M | Unit test, EXP-04 |
| FR-05.4 | Mở episode thì tạo `alert_event` (type BUNCHING, audience OPERATIONS) và sự kiện SSE `bunching.opened` | **G** kịch bản `bunching` **T** Alert feed của operator hiện alert trong ≤ 10 giây | M | E2E |

### FR-06 · ETA

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-06.1 | Job hằng giờ tính lại `insight_eta_prediction` trên cửa sổ 28 ngày, chỉ dùng arrival có `is_observed=true` | Kết quả khớp với tính tay trên fixture; chạy lại 2 lần cho kết quả giống hệt | M | Unit test, integration test |
| FR-06.2 | `GET /stops/{id}/arrivals` trả N chuyến sắp tới (mặc định 10, trong 90 phút): giờ theo lịch, `predictedArrival = scheduled + avg_delay`, `sampleCount`, `confidence` (LOW/MEDIUM/HIGH) | **G** khung không có mẫu **T** `predictedArrival = scheduled`, `confidence=NONE` | M | Contract test |
| FR-06.3 | (Mở rộng) Nếu chuyến đang có TripUpdate thì trả thêm `realtimeArrival` | Có trường `realtimeArrival` khi dữ liệu realtime của chuyến mới hơn 2 phút | C | Contract test |

### FR-07 · Phát hiện gián đoạn

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-07.1 | Baseline EWMA theo `(route, direction)`, bucket 1 phút event time, cửa sổ hiện tại 10 phút, tối thiểu 5 mẫu | Unit test công thức EWMA mean và variance theo DR-31 | M | Unit test |
| FR-07.2 | Mở episode khi z > 2,5 trong 2 bucket; đóng khi z < 1,5 trong 3 bucket; không cập nhật baseline khi đang có episode; 60 bucket đầu không báo | Bảng test trong DOC-23 §Disruption pass 100%; kịch bản `disruption` mở đúng 1 episode rồi đóng lại | M | Unit test, E2E |
| FR-07.3 | Tạo `alert_event` với audience **mặc định** là OPERATIONS và PUBLIC; FR-09.5 có thể chuyển thành ENGINEERING | Hành khách thấy banner trên Stop detail của các trạm thuộc tuyến bị ảnh hưởng | M | E2E |

### FR-08 · OTP

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-08.1 | Job 03:00 hằng ngày tính `insight_otp_scorecard` cho hôm qua và tính lại 2 ngày trước đó | Kết quả khớp với tính tay trên fixture; có đủ `otp_percentage`, `observation_count`, `trip_count` | M | Unit test |
| FR-08.2 | Ngưỡng `early-tolerance` và `late-tolerance` cấu hình được (mặc định 300 giây / 300 giây) | Đổi cấu hình rồi chạy lại cho kết quả khác đúng như kỳ vọng | S | Unit test |

### FR-09 · AI triage

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-09.1 | Mọi record DLQ `NEW` được triage (`category`, `severity`, các confidence, `model_version`) | **G** 100 record NEW **T** trong ≤ 60 giây tất cả có kết quả, hoặc `triage_attempts` tăng nếu Jev lỗi | M | Integration test (Fake), manual (Jev) |
| FR-09.2 | Auto-replay theo bảng quyết định: category `transient_network` hoặc `upstream_api_error`, confidence > 0,9, nguồn UP ≥ 60 giây, `auto_replay_count < 2` | Mỗi nhánh của bảng có một ca test; mỗi hành động ghi vào `dlq_action_log` kèm confidence | M | Unit test |
| FR-09.3 | Record có confidence từ 0,5 đến 0,9 thì vào hàng chờ xác nhận (`PENDING_CONFIRM`); `POST /etl/dlq/{id}/confirm` sẽ replay | E2E: bấm "Confirm" → record chuyển sang `REPLAYED` | M | E2E |
| FR-09.4 | Phát hiện bất thường ticketing (DR-34) và phân loại (`fraud_suspect`, `system_error`, `promo_spike`, `normal`) | Kịch bản `ticket-spike` tạo 1 dòng `insight_ticketing_anomaly` có category khác `normal` (với Fake) | S | Integration test |
| FR-09.5 | Làm giàu disruption: `data_issue_probability` (Noul) và `likely_cause` (Choice). Nếu xác suất lỗi dữ liệu > 0,7 thì audience chỉ còn ENGINEERING | **G** disruption xảy ra lúc có DLQ tăng đột biến cho nguồn đó **T** (với Fake) audience = ENGINEERING; hành khách không thấy | M | Integration test |
| FR-09.6 | Gợi ý điều phối cho mỗi episode bunching; confidence < 0,6 thì hiển thị "Low confidence" | Popover hiện gợi ý; nút Accept/Dismiss lưu `operator_feedback` | S | E2E |
| FR-09.7 | Jev lỗi hoặc timeout (2 giây) không ảnh hưởng ETL và analytics; trường kết quả để `null` | Toxiproxy làm Jev timeout → ETL throughput không đổi; UI hiện "Unclassified" | M | Integration test, EXP-08 |
| FR-09.8 | Luật chặn cuối: nếu `upstream_api_error` vượt N/giờ (mặc định 50) thì phát alert khẩn, bất kể Jev trả gì | Prometheus rule kích hoạt trong test | M | Test alert rule |
| FR-09.9 | Tắt được từng use case bằng cấu hình hoặc cờ | Tắt `triage.dlq.enabled` → record giữ `NEW`, hệ thống vẫn chạy bình thường | M | Integration test |
| FR-09.10 | Không gửi dữ liệu cá nhân sang Jev | Test prompt builder: không có trường nào nằm trong blocklist (`customer_ref`…) | M | Unit test |

### FR-10 · API và real-time

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-10.1 | REST API `/api/v1` gồm đủ endpoint ở DOC-32 | Contract test khớp `openapi.json`; mọi endpoint có ví dụ | M | Contract test |
| FR-10.2 | Phân quyền: anonymous, viewer, operator theo ma trận trong DOC-27 | Test security cho **mọi** endpoint × 3 role | M | Integration test |
| FR-10.3 | Mọi danh sách đều có phân trang (keyset cho dữ liệu chuỗi thời gian). Ngoại lệ: `GET /vehicles/live` là snapshot trạng thái hiện tại, trả nguyên, tối đa 1.500 xe (DOC-31 §5) | Không endpoint nào trả quá 500 item mỗi trang, trừ ngoại lệ trên | M | Contract test |
| FR-10.4 | SSE `/api/v1/stream` với các kênh `vehicles`, `alerts`, `jobs`, `dlq`; hỗ trợ `Last-Event-ID` và `resync` | Ngắt kết nối 30 giây rồi nối lại → nhận bù đủ sự kiện; ngắt quá 5 phút → nhận `resync` | M | Integration test |
| FR-10.5 | Endpoint public bị rate limit theo IP | Request thứ 61 trong 1 phút → 429 kèm Problem Details | S | Integration test |
| FR-10.6 | Mọi response có `X-Trace-Id`; response dữ liệu có `X-Data-As-Of` | Kiểm tra header | M | Integration test |

### FR-11 · Dashboard

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-11.1 | Live map: xe theo thời gian thực, gom cụm khi zoom xa, tô nổi bunching, gợi ý điều phối | Tiêu chí trong `screens/live-map.md` | M | E2E |
| FR-11.2 | Stop detail: arrivals kèm mức tin cậy, banner disruption | `screens/stop-detail.md` | M | E2E |
| FR-11.3 | Route scorecard: xếp hạng OTP, biểu đồ trễ, lịch sử gián đoạn | `screens/route-scorecard.md` | M | E2E |
| FR-11.4 | Ops console: jobs, DLQ (lọc, sửa, replay, discard, hàng chờ xác nhận, nhật ký auto-replay), replay raw zone, cờ vận hành, bất thường ticketing | `screens/ops-console-*.md` | M | E2E |
| FR-11.5 | Alert feed theo audience, có ack | `screens/alert-feed.md` | M | E2E |
| FR-11.6 | Không màn hình nào bị trắng khi API hoặc SSE lỗi; có stale banner | Tắt API → mọi màn hình hiện trạng thái lỗi hoặc dữ liệu cũ kèm thời điểm | M | E2E |
| FR-11.7 | Demo control (chỉ ở profile `demo`) | `screens/demo-control.md` | S | E2E |
| FR-11.8 | Overview mạng lưới cho viewer: KPI, alert cần chú ý, tuyến kém, tình trạng pipeline, mỗi khối dẫn sang màn chuyên trách (DR-88) | `screens/overview.md` | S | E2E |

### FR-12 · Replay

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-12.1 | Sửa payload của record DLQ (phải qua validate schema trước khi lưu) và replay nó | **G** record `referential_integrity` **W** sửa `route_id` rồi replay **T** có dòng trong fact; DLQ `REPLAYED`; `dlq_action_log` ghi người thực hiện | M | E2E |
| FR-12.2 | Replay một khoảng thời gian từ raw zone cho một nguồn, có tùy chọn tính lại analytics | **G** EXP-04 **T** checksum mọi bảng khớp | M | EXP-04 |
| FR-12.3 | Mỗi nguồn chỉ có một replay RUNNING tại một thời điểm | Yêu cầu thứ hai → 409 Conflict | M | Integration test |
| FR-12.4 | Có runbook để replay bằng cách reset offset trong thời gian retention của Kafka | Runbook RB-10 chạy được | S | Diễn tập |
| FR-12.5 | Mọi replay đều truy vết được (`replay_request` → `BATCH_JOB_EXECUTION` → `etl_batch_step` → `batch_id` trong fact) | Từ một `replay_request` truy ra được các dòng fact | M | Integration test |

### FR-13 · Source simulator (bổ sung)

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-13.1 | Phát VehiclePosition (mỗi xe đang phục vụ, 5 giây một lần) và TripUpdate (mỗi chuyến đang chạy, 30 giây một lần và khi xe tới trạm) dựa trên lịch của feed và ánh xạ ngày (DR-08) | Vào 16:40 một ngày thường, số xe đang phát khoảng 606 (± 5%) | M | Integration test |
| FR-13.2 | Ghi giao dịch vé vào `ticketing_source` với tốc độ theo giờ trong ngày | Cấu hình được; mặc định khoảng 2 giao dịch/giây vào giờ cao điểm | M | Integration test |
| FR-13.3 | Các kịch bản `bunching`, `disruption`, `bad-data`, `duplicates`, `ticket-spike`, `refund-burst`, `load-ramp` điều khiển được qua REST | Mỗi kịch bản có test kiểm tra dữ liệu sinh ra | M | Integration test |
| FR-13.4 | Ledger ghi mọi message đã phát (DR-28) | Số dòng ledger bằng số message producer ack | M | Integration test |

### FR-14 · Cảnh báo (bổ sung)

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-14.1 | Có 9 alert rule hạ tầng ở SDD §12.2, gửi email (Mailpit) và webhook về `alert_event` | Mỗi rule kích hoạt được bằng kịch bản và gửi tới đúng kênh | M | Test alert |
| FR-14.2 | `alert_event` hợp nhất, định tuyến theo audience, có `dedup_key` chống trùng | Cùng một episode không sinh 2 alert | M | Integration test |
| FR-14.3 | Mọi alert có link runbook (với alert hạ tầng) hoặc link tới màn hình liên quan | Kiểm tra annotation | S | Review |

### FR-15 · Điều khiển vận hành (bổ sung)

| ID | Yêu cầu | Tiêu chí nghiệm thu | Ưu tiên | Kiểm chứng |
| --- | --- | --- | --- | --- |
| FR-15.1 | Tạm dừng và tiếp tục từng consumer bằng `runtime_flag` | Bật cờ → trong ≤ 5 giây consumer pause, lag tăng; tắt cờ → consumer resume | M | Integration test |
| FR-15.2 | `GET /system/freshness` trả độ tươi của từng nguồn | Dùng cho stale banner | M | Contract test |

---

## 2. Yêu cầu phi chức năng

| ID | Yêu cầu | Chỉ tiêu | Cách đo | Kiểm chứng |
| --- | --- | --- | --- | --- |
| NFR-01 | Đúng đắn khi lỗi | Mất = 0, trùng = 0 sau restart, replay hoặc gửi lại | So warehouse với ledger (DR-28); checksum (DR-58) | EXP-01, 02, 04, 08 |
| NFR-02 | Cô lập lỗi | 100% record hợp lệ được nạp khi có 1%, 5%, 20% record lỗi | Đếm theo ledger (`intended_invalid`) | EXP-03 |
| NFR-03 | Độ trễ dữ liệu | p95 `end_to_end_latency_seconds` < 10 giây ở tải nền trên compose | Histogram tại API (DR-57) | EXP-05 |
| NFR-04 | Phục hồi | Consumer tiếp tục từ offset đã commit; thời gian phục hồi p95 < 60 giây sau khi process chạy lại | Thời điểm kill → thời điểm lag về mức trước khi kill | EXP-01 |
| NFR-05 | Quan sát được | 100% log ETL có `batch_id` và `trace_id`; từ một dòng fact truy ra được log và trace | Query Loki/Tempo theo `batch_id` | Review, P3-03 |
| NFR-06 | Bảo mật | Không có secret trong repo (scan); role DB theo nguyên tắc tối thiểu; mọi endpoint ghi yêu cầu `operator` | gitleaks trong CI; test grant; test security | P1-06, P4-09, P8-02 |
| NFR-07 | Tái lập môi trường | `make up` trên máy sạch (16 GB RAM) → healthy trong ≤ 5 phút (chưa tính thời gian pull image) | Đo thời gian | P1-04 |
| NFR-08 | Mở rộng theo tải | Tới 10× tải nền trên k3d vẫn đạt NFR-03, trong giới hạn tài nguyên máy | Load ramp | EXP-07 |
| NFR-09 | Chịu lỗi | Mỗi sự cố đơn lẻ (pod, broker, Postgres primary, Jev) không làm mất hoặc trùng dữ liệu; tự phục hồi | Chaos | EXP-08 |
| NFR-10 (bổ sung) | Hiệu năng API | p95 < 200 ms cho endpoint đọc trên 7 ngày dữ liệu ở tải nền | Metric `http_server_requests_seconds` | P4-10 |
| NFR-11 (bổ sung) | Truy cập | Màn hình hành khách đạt WCAG 2.2 AA (axe không có lỗi nghiêm trọng) | axe trong Playwright | P5-14 |
| NFR-12 (bổ sung) | Hiệu năng UI | Stop detail có LCP < 2,5 giây trên 4G mô phỏng; bảng DLQ cuộn mượt với 10.000 dòng | Lighthouse, Playwright trace | P5 |
| NFR-13 (bổ sung) | Chất lượng code | Coverage: `StreamChunkTemplate`, `ErrorClassifier`, `SkipPolicy` và processor/writer dùng chung ≥ 90%, ETL core ≥ 80%, analytics ≥ 85%; CI xanh trước khi merge | JaCoCo | CI |

---

## 3. Ma trận FR → UC → tính năng → thiết kế

| FR | UC | Tính năng (DOC-05) | Thiết kế |
| --- | --- | --- | --- |
| FR-01 | UC-14 | F-INGEST-01…06 | DOC-09, 13, 20, 21 |
| FR-02 | UC-08 | F-ETL-02…05, F-DLQ-01 | DOC-16, 19, 20 |
| FR-03 | UC-17, UC-18 | F-ETL-06…08 | DOC-19, ADR-0003 |
| FR-04 | UC-14 | F-ETL-09…11 | DOC-14 |
| FR-05 | UC-01, UC-04 | F-ANL-01 | DOC-23 |
| FR-06 | UC-02 | F-ANL-03, F-ANL-06 | DOC-23 |
| FR-07 | UC-03, UC-05 | F-ANL-02 | DOC-23 |
| FR-08 | UC-06 | F-ANL-04 | DOC-23 |
| FR-09 | UC-04, 05, 09, 12, 13 | F-AI-01…07, F-ANL-05 | DOC-24 |
| FR-10 | tất cả | F-API-01…05 | DOC-26, 31–33 |
| FR-11 | UC-01…12, 16 | F-UI-01…08 | DOC-34…37 |
| FR-12 | UC-08, 10, 18 | F-DLQ-02…04, F-REPLAY-01…03 | DOC-22 |
| FR-13 | UC-16, 17 | F-SIM-01…04 | DOC-25 |
| FR-14 | UC-15 | F-OPS-01…03 | DOC-28 |
| FR-15 | UC-11 | F-OPS-04, 05 | DOC-15, 20 |
