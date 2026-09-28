# Thuộc tính chất lượng

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-10
> Phụ thuộc: [DOC-03 §2](../01-product/requirements.md), [DR](../00-decision-register.md) (DR-15, 16, 21–24, 57, 64, 65), [DOC-07](system-context-and-containers.md), [DOC-09](messaging-contracts.md)

Mọi con số ở §2 và §3 **tính từ feed thật** (`sample-data/gtfs/metrotransit-mn-20260926.zip`, ngày thường 2026-09-29), bằng script `sample-data/gtfs/volume_profile.py`. Ngân sách RAM ở §5 là **kế hoạch**; spike S-03 (P0-04) đo thực tế và cập nhật bảng.

## 1. Từ NFR tới cơ chế

| NFR | Chiến thuật | Cơ chế cụ thể | Nơi hiện thực | Kiểm chứng |
| --- | --- | --- | --- | --- |
| NFR-01 Đúng đắn khi lỗi | At-least-once + ghi idempotent | Offset commit sau transaction (`AckMode.BATCH`); upsert theo business key có guard event time/LSN; restart từ `ExecutionContext`; `VERSION` làm fencing | etl (DOC-19, DOC-20), DOC-14 | EXP-01, 02, 04, 08; test tiêm lỗi P2-07 |
| NFR-02 Cô lập lỗi | Tách record lỗi khỏi chunk | Skip `DATA` vào DLQ; scan khi ghi lỗi (Spring Batch scan hoặc savepoint); rule DQ pre-write | DOC-19, DOC-16 | EXP-03 |
| NFR-03 Độ trễ < 10 s | Micro-batch ngắn, fan-out đẩy | `fetch.max.wait.ms=1000`; analytics chạy ngay sau commit trên executor riêng; `pti.events.ui` → SSE; ngân sách ở §2 | etl-stream, api (DOC-26) | EXP-05; metric DR-57 |
| NFR-04 Phục hồi < 60 s | Trạng thái nằm ngoài process | Offset trong Kafka; `CooperativeStickyAssignor`; graceful shutdown; `StaleExecutionRecoverer` cho job batch | DOC-20, DOC-19 | EXP-01 |
| NFR-05 Quan sát được | Tương quan bằng id | `batch_id` (DR-63) và `trace_id` trong MDC; `traceparent` qua Kafka header; log JSON → Loki | Mọi app (DOC-28) | Review, P3-03 |
| NFR-06 Bảo mật | Quyền tối thiểu, không có secret trong repo | Role DB theo DOC-17; JWT; rate limit; gitleaks; blocklist PII cho Jev | DOC-17, DOC-27 | Test grant, test security, CI |
| NFR-07 Tái lập môi trường | Mọi thứ khai báo trong repo | Compose có healthcheck và `depends_on: condition`; `db-migrate`, `kafka-init`, `connect-init` chạy một lần và idempotent; image version cố định | DOC-39 | Đo `make up` trên máy sạch |
| NFR-08 Mở rộng theo tải | Song song theo partition | 12 partition cho `gtfs.*`; KEDA theo lag kèm chặn khi DB là nút thắt; pool nhỏ, PgBouncer | DOC-40 | EXP-07 |
| NFR-09 Chịu lỗi | Phân biệt lỗi dữ liệu và lỗi hạ tầng | `ErrorClassifier`; pause container và backoff vô hạn cho lỗi hạ tầng; circuit breaker; RF 3 / min ISR 2 trên k3d; CNPG failover | DOC-30, DOC-40 | EXP-08 |
| NFR-10 API p95 < 200 ms | Đọc từ bảng đã tổng hợp | `vehicle_position_latest`; bảng insight tính sẵn; cache Caffeine vài giây; keyset pagination; đọc replica | api (DOC-31) | Metric `http_server_requests_seconds`, P4-10 |
| NFR-11 WCAG 2.2 AA | Component có sẵn a11y | Radix/shadcn; không dùng màu làm tín hiệu duy nhất; axe trong Playwright | frontend (DOC-35) | P5-14 |
| NFR-12 LCP < 2,5 s | Tải ít cho màn hành khách | Tách bundle theo route; Stop detail không tải MapLibre; nén brotli ở nginx | frontend | Lighthouse |
| NFR-13 Coverage | Test gần logic rủi ro nhất | Chỉ tiêu theo module (DOC-44); JaCoCo gate trong CI | build-logic | CI |

## 2. Ngân sách độ trễ (NFR-03)

`end_to_end_latency_seconds = thời điểm API phát SSE − Kafka CreateTime của record nguồn` (DR-57). Mục tiêu p95 < 10 giây ở tải nền trên compose.

| # | Chặng | Metric | Ngân sách p95 | Cách giữ ngân sách |
| --- | --- | --- | --- | --- |
| 1 | Broker append → consumer nhận | (một phần của `kafka_to_commit_seconds`) | 1,0 s | `fetch.max.wait.ms=1000`, `fetch.min.bytes=65536` |
| 2 | Process + ghi + commit micro-batch | (phần còn lại của `kafka_to_commit_seconds`) | 1,5 s | Chunk ≤ 500; một round-trip batch upsert; không gọi dịch vụ ngoài |
| 3a | Commit → `vehicles.batch` (kênh vehicles) | (một phần của `commit_to_emit_seconds`) | 1,0 s | Gom mỗi giây |
| 3b | Commit → phát hiện bunching/disruption (kênh alerts) | (một phần của `commit_to_emit_seconds`) | 2,0 s | `AFTER_COMMIT` → executor riêng (DR-35), FR-05.1 |
| 4 | Publish `pti.events.ui` → consumer của pod API | (một phần của `commit_to_emit_seconds`) | 0,5 s | Consumer của API đặt `fetch.max.wait.ms=100` |
| 5 | Ring buffer → ghi ra SSE | (một phần của `commit_to_emit_seconds`) | 0,2 s | Flush ngay sau mỗi sự kiện |
| | **Tổng, kênh vehicles** | `end_to_end_latency_seconds` | **4,2 s** | Dư 5,8 s cho GC, rebalance, lag nhất thời |
| | **Tổng, kênh alerts** | `end_to_end_latency_seconds` | **5,2 s** | Dư 4,8 s |

Ngưỡng cảnh báo trong DOC-28 (alert `LatencyStageSlow`): p95 `pti_etl_kafka_to_commit_seconds` > 3 s, hoặc p95 `pti_ui_commit_to_publish_seconds` > 2,5 s, hoặc p95 `pti_api_publish_to_emit_seconds` > 1,5 s trong 5 phút. `commit_to_emit_seconds` ở bảng trên là tổng của hai metric sau (DR-71).

Độ trễ *phát hiện* bunching (từ lúc hai xe thực sự dồn cụm) còn cộng thêm thời gian chờ 2 lần đánh giá liên tiếp (DR-30). Thời gian này không thuộc NFR-03 mà thuộc tiêu chí của FR-05.4.

## 3. Ước lượng dung lượng

### 3.1 Khối lượng message (đo trên feed)

| Chỉ số | Cao điểm (16:38) | Cả ngày thường | 10× cao điểm (EXP-07) |
| --- | --- | --- | --- |
| Xe đang phục vụ | 606 | trung bình 331 theo 24 giờ; 7.954 xe·giờ | 6.060 |
| Chuyến đang chạy | 472 | 8.028 chuyến; 43,0 trạm/chuyến | 4.720 |
| VehiclePosition | 121 msg/s | 5,73 triệu | 1.212 msg/s |
| TripUpdate định kỳ (30 s) | 15,7 msg/s | 0,72 triệu | 157 msg/s |
| TripUpdate khi tới trạm | 6,3 msg/s | 0,35 triệu | 63 msg/s |
| **Tổng GTFS-rt** | **≈ 143 msg/s** | **≈ 6,8 triệu** | **≈ 1.430 msg/s** |
| Giao dịch vé (FR-13.2) | ≈ 2 txn/s | ≈ 60 nghìn (giả định trung bình 0,7 txn/s) | không nhân (EXP-07 chỉ tăng GTFS-rt) |

Kích thước message ước lượng: VehiclePosition khoảng 0,6 KB; TripUpdate với tối đa 10 trạm phía trước (DR-65) khoảng 2,8 KB.

### 3.2 Dòng ghi vào warehouse

| Bảng | Thao tác | Cao điểm | Cả ngày | Ghi chú |
| --- | --- | --- | --- | --- |
| `fact_vehicle_position` | INSERT (upsert, hầu như luôn là dòng mới) | 121 dòng/s | 5,73 triệu | |
| `vehicle_position_latest` | UPDATE | 121 dòng/s | 5,73 triệu | Chỉ khoảng 1.100 dòng; update HOT |
| `fact_trip_update` | upsert, phần lớn là UPDATE | ≈ 270 dòng/s | ≈ 13 triệu lần ghi trên 345 nghìn dòng | Nếu không có DR-65: 500 dòng/s và 24 triệu lần ghi |
| `dedup_registry` | INSERT, dọn theo TTL | 143 dòng/s | — | TTL 1 giờ → khoảng 520 nghìn dòng thường trực (DR-16) |
| `etl_stream_batch` | INSERT | ≈ 6 dòng/s | ≈ 0,5 triệu | 1 dòng mỗi poll có dữ liệu của mỗi consumer thread |
| `fact_ticket_sales` | upsert | ≈ 2 dòng/s | ≈ 60 nghìn | |
| **Tổng** | | **≈ 660 dòng/s** | | 10×: khoảng 6.600 dòng/s, ghi theo lô 500 |

### 3.3 Dung lượng lưu trữ

Kích thước dòng **đo trên PostgreSQL 17** với DDL của DOC-14 (heap cộng mọi index, sau `VACUUM`; 1,2 triệu dòng VehiclePosition và 480 nghìn dòng TripUpdate sinh tổng hợp theo mẫu feed):

| Bảng | Heap | Index | Tổng mỗi dòng | Ghi chú đo |
| --- | --- | --- | --- | --- |
| `fact_vehicle_position` | 248 B | ≈ 104 B | **≈ 350 B** | PK chiếm ≈ 70 B/dòng vì dòng tới theo thời gian chứ không theo thứ tự PK (page split). Index `batch_id` chỉ ≈ 9 B/dòng nhờ B-tree deduplication (≈ 600 dòng chung một `batch_id`). |
| `fact_trip_update` | 283 B → 637 B | ≈ 92 B | **345 B → 730 B** | 345 B khi mỗi dòng chỉ được insert. 730 B là trường hợp xấu nhất: mọi dòng bị ghi lại hai lần liên tiếp không kịp vacuum; chỉ khoảng 38% update là HOT. Lập kế hoạch với **≈ 500 B/dòng**. |
| `fact_ticket_sales` | — | — | ≈ 300 B (ước lượng) | Chưa đo; khối lượng nhỏ nên không ảnh hưởng tổng. |
| `sim.sim_ledger` (pg-source) | — | — | **≈ 368 B** | Đo trên DDL ở DOC-13 §6. Mỗi message GTFS-rt đã được Kafka xác nhận sinh một dòng. |

| Nơi lưu | Mỗi ngày (chạy 24/7) | Retention mặc định | Trần khi chạy liên tục | Ghi chú |
| --- | --- | --- | --- | --- |
| `fact_vehicle_position` | 2,0 GB | 14 ngày (DR-15) | 28 GB | **Compose dùng 3 ngày** (`PTI_RETENTION_VEHICLE_POSITION=3d`) → 6 GB |
| `fact_trip_update` | 0,17 GB | 90 ngày | 15 GB | Compose dùng 30 ngày → 5 GB |
| `fact_ticket_sales` | 0,02 GB | 365 ngày | 6,6 GB | |
| `sim.sim_ledger` (pg-source) | 2,5 GB | 2 ngày (`pti.sim.ledger.retention`) | 7,5 GB | Luôn tồn tại tối đa 3 partition (hôm nay và 2 ngày trước). Chỉ experiment runner đọc; EXP phải chạy xong trong 48 giờ sau khi kết thúc scenario. |
| Bảng ops, insight, metadata `BATCH_*` | < 0,2 GB | 7–30 ngày | < 2 GB | DOC-18 |
| WAL sinh ra (warehouse) | ≈ 8 GB | tái sử dụng | `max_wal_size=2GB` | Không bật archive WAL |
| Kafka (sau nén zstd, RF 1) | ≈ 1,3 GB | 7 ngày | 9 GB | k3d (RF 3): đặt `retention.ms` = 24 giờ → khoảng 4 GB |
| Raw zone (gzip) | ≈ 1,0 GB | 30 ngày (DOC-18) | 30 GB | Nguồn sự thật để dựng lại, nên giữ lâu nhất |

Thực tế máy dev không chạy simulator 24/7, nên cột "trần" là giới hạn trên. Với retention của compose, tổng trần khoảng 68 GB. **DOC-38 yêu cầu còn trống ít nhất 80 GB ổ đĩa.**

### 3.4 Hệ quả thiết kế rút ra từ số liệu

1. `fact_trip_update` chủ yếu là UPDATE, nên cần HOT update: `fillfactor=80` và không đánh index trên cột thay đổi (DR-65).
2. `vehicle_position_latest` cũng là bảng update nóng (khoảng 1.100 dòng, mỗi dòng cập nhật 5 giây một lần). Đặt `fillfactor=50` và autovacuum mạnh hơn (`autovacuum_vacuum_scale_factor=0.01`).
3. Micro-batch ở tải nền có trung bình khoảng 40–50 record mỗi poll cho mỗi thread (143 msg/s chia cho 3 thread), nên chunk 500 chỉ đầy khi 10× tải hoặc khi đang đuổi lag.
4. Một partition `fact_vehicle_position` theo ngày có tới 5,7 triệu dòng. API `/vehicles/live` không được quét bảng này mà phải đọc `vehicle_position_latest` (DR-14).
5. Update của `fact_trip_update` chỉ HOT được khi trang còn chỗ. Vì vậy mỗi partition ngày đặt `autovacuum_vacuum_scale_factor=0.02` (qua `dw.partition_spec`), để autovacuum dọn tuple chết trong lúc chuyến còn chạy thay vì đợi tới 20% bảng.

## 4. Kết nối database

| App | Pool (Hikari) mỗi pod | Số pod tối đa | Tổng tối đa |
| --- | --- | --- | --- |
| etl-stream | 6 (3 listener thread + analytics executor 2 + 1 dự phòng) | 4 (KEDA; 12 partition ÷ 3 thread mỗi pod) | 24 |
| etl-batch | 6 | 2 | 12 |
| triage-worker | 4 | 3 | 12 |
| api | reader 10 + operator 4 | 6 | 84 |
| source-simulator (pg-source) | 4 cho `ticketing_source` + 4 cho `pti_sim` | 1 | 8 |

PgBouncer trên k3d: `default_pool_size=40` cho mỗi cặp user/database, `max_client_conn=300`, chế độ transaction. Postgres `max_connections=150`. KEDA không scale etl-stream vượt `maxReplicaCount=4`: với 3 thread mỗi pod, pod thứ năm không nhận partition nào (SDD §12.7 giả định một thread mỗi pod nên ghi 12). Bảng kết nối qua PgBouncer theo user ở DOC-40 §9.4. Compose không dùng PgBouncer; `max_connections=100` là đủ cho một pod mỗi app.

## 5. Ngân sách tài nguyên compose (máy 16 GB, 8 CPU)

Giới hạn (`mem_limit`) là con số **kế hoạch**. Cột "Đo được" là RAM đỉnh (theo `docker stats`) trong spike S-03 (§5.1). Java dùng `-XX:MaxRAMPercentage=75 -XX:+UseCompactObjectHeaders` (Java 25, JEP 519).

| Container | Profile | `mem_limit` | Heap (≈75%) | CPU limit | Đo được (S-03) |
| --- | --- | --- | --- | --- | --- |
| kafka | core | 1.024 MB | `KAFKA_HEAP_OPTS=-Xmx512m` | 1,0 | 911 MiB |
| connect | core | 1.280 MB | `-Xmx512m` | 1,0 | 691 MiB (chưa có connector); 1.009 MiB khi S3 sink chạy bù 1 triệu record (S-04) |
| pg-warehouse | core | 1.536 MB | `shared_buffers=512MB` | 2,0 | 570 MiB |
| pg-source | core | 384 MB | `shared_buffers=64MB` | 0,5 | 20 MiB (không tải) |
| seaweedfs (DR-66) | core | 384 MB | — | 0,5 | 196 MiB |
| keycloak | core | 768 MB | mặc định của Keycloak | 1,0 | 501 MiB |
| etl-stream | core | 768 MB | ≈ 576 MB | 2,0 | 256 MiB (app rỗng) |
| etl-batch | core | 640 MB | ≈ 480 MB | 1,0 | 243 MiB (app rỗng) |
| api | core | 640 MB | ≈ 480 MB | 1,0 | 245 MiB (app rỗng) |
| source-simulator | core | 512 MB | ≈ 384 MB | 1,0 | |
| frontend (nginx) | core | 32 MB | — | 0,25 | |
| **Tổng core** | | **≈ 8,0 GB** | | | **≈ 3,8 GB** (chưa có simulator, frontend) |
| triage-worker | triage | 384 MB | ≈ 288 MB | 0,5 | |
| prometheus | observability | 512 MB | — | 0,5 | |
| tempo | observability | 384 MB | — | 0,5 | |
| loki | observability | 384 MB | — | 0,5 | |
| grafana | observability | 192 MB | — | 0,5 | |
| otel-collector | observability | 192 MB | — | 0,5 | |
| alloy | observability | 192 MB | — | 0,25 | |
| alertmanager, mailpit | observability | 64 MB mỗi cái | — | 0,1 | |
| **Tổng khi bật đủ profile** | | **≈ 10,4 GB** | | | chưa đo |

Còn khoảng 6 GB cho macOS, VM của Docker/OrbStack, IDE và trình duyệt. Nếu S-03 cho thấy không đủ thì:

1. Chạy không có profile `observability` khi dev (tiết kiệm khoảng 2 GB).
2. ~~Giảm heap của Kafka và Connect xuống 384 MB.~~ Bỏ, xem §5.1 điểm 3.
3. Chế độ `lite`: gộp `etl-stream` và `etl-batch` vào một container bật cả hai profile (`SPRING_PROFILES_ACTIVE=stream,batch`). Đây là cấu hình được hỗ trợ, vì ShedLock và `spring.batch.job.enabled=false` vẫn giữ đúng ngữ nghĩa.

**k3d:** tổng tài nguyên vượt 16 GB nếu chạy đủ replica như SDD §12.4. Values `lite` (DOC-40) dùng Kafka 3 broker, mỗi broker 768 MB, CNPG 1 primary + 1 replica, API 2 pod, không chạy Keycloak (API dùng JWT ký bằng key tĩnh, chỉ trong values `lite`). Khi chạy k3d thì tắt compose.

### 5.1 Kết quả spike S-03 (2026-09-26)

**Cách đo.** Máy MacBook 16 GB, 8 CPU. Docker chạy trên OrbStack, VM giới hạn **8 GB**. Compose dùng đúng `mem_limit` và CPU ở bảng trên. Ba "JVM rỗng" là một app Spring Boot 4.1.1 gồm web, actuator, JDBC (Hikari pool 6), Kafka (listener concurrency 3), Batch (JobRepository JDBC) và Prometheus, chạy trên `eclipse-temurin:25-jre` với cờ JVM ở trên. Tải trong 6 phút:

- Kafka nhận 150 msg/s, mỗi message 600 B, `acks=all`, zstd, topic 12 partition. Cả ba app cùng tiêu thụ. p99 latency của producer là 58 ms.
- pg-warehouse chạy pgbench 400 tps với 12 client, trên tập dữ liệu khoảng 750 MB, lớn hơn `shared_buffers`. Mục đích là để `shared_buffers` được dùng hết như khi partition VP 2 GB/ngày chạy thật.
- `docker stats` lấy mẫu mỗi 20 giây; cột "Đo được" là giá trị đỉnh.

**Kết luận.**

1. Stack core dùng khoảng 3,8 GB khi các app còn rỗng. Mỗi app thật sẽ tăng thêm (analytics giữ state, cache dimension), nhưng vẫn có dư địa trong `mem_limit`. **Máy 16 GB đủ cho profile core.**
2. VM Docker mặc định 8 GB là **đủ cho core, không đủ khi bật `observability`**. Tổng `mem_limit` của mọi profile (≈ 10,4 GB) vượt 8 GB. `mem_limit` chỉ là trần, không phải bộ nhớ đặt trước, nhưng khi mọi container cùng tăng thì OOM killer của VM sẽ giết container. DOC-38 yêu cầu đặt VM **ít nhất 10 GB, khuyến nghị 12 GB** (OrbStack: `orb config set memory_mib 12288`; Docker Desktop: Settings → Resources) khi chạy đủ profile.
3. Kafka gần chạm trần (911/1.024 MiB) vì cgroup tính cả page cache của log segment. Page cache thu hồi được nên không gây OOM, nhưng **không được hạ heap Kafka xuống 384 MB** (bỏ phương án 2 ở trên).
4. Postgres warehouse dùng khoảng `shared_buffers` + 60 MB khi 12 kết nối hoạt động. Giới hạn 1.536 MB còn dư cho `maintenance_work_mem` và autovacuum; giữ nguyên.
5. Object storage: MinIO không còn image (DR-66). SeaweedFS đạt đỉnh 196 MiB khi upload multipart 12 MB, nên nâng `mem_limit` lên 384 MB.
6. Kafka Connect dùng 691 MiB khi chưa có connector. S-04 đo lại với Debezium và S3 sink cùng chạy: khoảng 590 MiB ở tải thấp, đỉnh 1.009 MiB khi S3 sink chạy bù 1,02 triệu record (heap đỉnh 468/512 MiB). Vì vậy trần được nâng lên 1.280 MB, heap giữ 512 MB. Bộ nhớ của S3 sink tỷ lệ với số file đang mở (một part mỗi file), và được giới hạn bằng `file.max.records=2000` (DR-81), part 1 MiB và commit 30 giây (DR-89).
7. App JVM khởi động trong 3–6 giây. `make up` từ lúc có image tới khi mọi container chạy mất dưới 1 phút; phần lớn thời gian ở lần đầu là kéo image (Debezium Connect 2,25 GB).

Mã spike (compose và app probe) không đưa vào repo. Cấu hình compose thật được viết ở DOC-39.

## 6. Tính khả dụng và phục hồi (mục tiêu)

| Thành phần | Mục tiêu khi có sự cố đơn lẻ trên k3d | Cơ chế |
| --- | --- | --- |
| Ingest GTFS-rt | Không mất; tạm trễ ≤ 60 s (NFR-04) | RF 3, min ISR 2, rebalance hợp tác |
| Warehouse ghi | Tạm dừng trong lúc failover (thường < 30 s), không mất, không trùng | CNPG failover; consumer pause và backoff |
| API đọc | Vẫn phục vụ trong lúc primary failover | Đọc replica |
| API ghi | 503 trong lúc failover, client retry với `Idempotency-Key` | DR-20 |
| CDC ticketing | Không mất; trễ tới khi connector chạy lại | Replication slot giữ WAL; cảnh báo WAL tích tụ |
| AI triage | Không ảnh hưởng pipeline | Bulkhead, circuit breaker, `category = null` |

RPO và RTO của backup nằm ở DOC-43.
