# Tham chiếu cấu hình

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-29
> Phụ thuộc: [DR](../00-decision-register.md), [DOC-10](../03-architecture/quality-attributes.md), [DOC-13](../05-data/source-data.md), [DOC-15](../05-data/ops-and-insight-model.md), [DOC-17](../05-data/db-roles-and-grants.md)
> Người dùng chính: mọi app, compose (DOC-39), Helm (DOC-40)

> Đã đủ key của mọi tài liệu thiết kế tới P8. Key mới phát sinh khi triển khai vẫn phải thêm vào đây trong cùng PR.

Tài liệu này được cập nhật liên tục. Mỗi PR thêm key cấu hình phải thêm dòng tương ứng ở đây (master plan §7). P8-03 rà lại để không thiếu key nào.

Cột **Trạng thái** của từng key:

- **Chốt:** tên và giá trị mặc định đã được quyết định ở DR hoặc ở tài liệu được dẫn.
- **Khung:** tên dự kiến. Tài liệu thiết kế của phase tương ứng sẽ chốt tên và giá trị.

## 1. Quy ước

1. **Prefix.** Mọi key riêng của dự án nằm dưới `pti.*`, nhóm theo app hoặc theo mảng: `pti.sim`, `pti.etl`, `pti.batch`, `pti.gtfs`, `pti.retention`, `pti.analytics`, `pti.triage`, `pti.api`. Key của Spring và thư viện (`spring.*`, `management.*`, `resilience4j.*`) giữ nguyên tên gốc.
2. **Biến môi trường.** Dùng relaxed binding của Spring Boot: đổi `.` và `-` thành `_`, viết hoa toàn bộ. Ví dụ `pti.retention.vehicle-position` ↔ `PTI_RETENTION_VEHICLE_POSITION`. Compose và Helm chỉ truyền cấu hình qua biến môi trường; không mount file `application.yml` riêng.
3. **Kiểu.** Thời lượng dùng `java.time.Duration` (`5s`, `2m`, `14d`). Tỷ lệ là số thực trong [0, 1]. Tiền là `BigDecimal` (USD). Mỗi nhóm key được bind vào một `record` có `@ConfigurationProperties` và `@Validated`; giá trị sai thì app không khởi động được.
4. **Ngưỡng và cờ.** Ngưỡng thuật toán nằm trong cấu hình; đổi thì restart app. Cờ bật/tắt tức thời nằm trong bảng `ops.runtime_flag` (§4). Hai thứ này không lẫn vào nhau (DR-19).
5. **Bí mật** (mật khẩu, API key) chỉ đến từ biến môi trường hoặc Secret, không bao giờ nằm trong file đã commit (DOC-17 §6).

## 2. Cấu hình chung cho mọi JVM

| Thiết lập | Giá trị | Lý do | Trạng thái |
| --- | --- | --- | --- |
| `pti.clock.offset` (env `PTI_CLOCK_OFFSET`) | `0s`; cùng giá trị cho simulator, etl, api | Đồng hồ nghiệp vụ, để demo và thực nghiệm từ Việt Nam rơi vào giờ có xe chạy ở Chicago (DR-67, DOC-25 §3.1) | Chốt |
| `TZ` (env) và `-Duser.timezone` | `UTC` | Spring Batch ghi `TIMESTAMP` không có múi giờ theo múi giờ JVM; view `ops_job_run_v` đọc các cột này như UTC (DOC-15) | Chốt |
| `spring.threads.virtual.enabled` | `api`: `true`; `triage-worker`: `true`; `etl`, `source-simulator`: `false` | `api` cần virtual thread cho request và SSE (DOC-26, DOC-31 §14); triage-worker chỉ gọi mạng và JDBC ngắn (DOC-24 §5.1). Listener Kafka và step Spring Batch giữ platform thread (ADR-0029). S-06 chỉ có thể **tắt** giá trị `true` nếu phát hiện pinning với driver JDBC; khi đó ghi vào DOC-11 | Chốt |
| `spring.flyway.enabled` | `false` | Migration do `db-migrate` chạy (ADR-0024) | Chốt |
| `spring.batch.job.enabled` | `false` (app `etl`) | Không job nào tự chạy lúc khởi động (DR-26) | Chốt |
| `spring.profiles.active` | `etl`: `stream` hoặc `batch`, có thể thêm `experiment` (DR-27). `api`, `source-simulator`: có thể thêm `demo` (DR-49) | | Chốt |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | | Chốt (DOC-28 §8) |
| `management.server.port` | `9080` | Tách actuator khỏi cổng app | Chốt |
| `management.tracing.enabled` / `.sampling.probability` | `${PTI_TRACING_ENABLED:false}` / theo app (DOC-28 §5.1) | | Chốt |
| `logging.structured.format.console` | `ecs` | DOC-28 §4 | Chốt |
| `spring.kafka.template.observation-enabled`, `spring.kafka.listener.observation-enabled` | `true` | DR-50 | Chốt |
| `pti.observability.freshness-probe.interval` (api), `.slot-probe.interval` (simulator), `.connector-probe.interval` (etl-stream) | `15s` / `30s` / `15s` | Gauge thay exporter (DR-71) | Chốt |
| `pti.observability.freshness-probe.enabled` (api) | `true` | Tắt lịch của probe; chỉ dùng trong test cần tự nạp kết quả probe | Chốt (P4-09) |

## 3. Cấu hình theo app

### 3.1 Datasource

Mỗi app kết nối bằng đúng role của nó (DOC-17 §2). Kích thước pool lấy từ DOC-10 §4.

| App | Datasource | Role | Env của URL, user, mật khẩu | Pool tối đa |
| --- | --- | --- | --- | --- |
| etl (`stream`, `batch`) | mặc định | `etl_writer` | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `ETL_WRITER_PASSWORD` | 6 |
| triage-worker | mặc định | `triage_writer` | `SPRING_DATASOURCE_*`, `TRIAGE_WRITER_PASSWORD` | 4 |
| api | `pti.datasource.reader` | `api_reader` | `PTI_DATASOURCE_READER_*`, `API_READER_PASSWORD` | 10 |
| api | `pti.datasource.operator` | `replay_operator` | `PTI_DATASOURCE_OPERATOR_*`, `REPLAY_OPERATOR_PASSWORD` | 4 |
| source-simulator | `pti.datasource.ticketing` | `source_simulator` | `PTI_DATASOURCE_TICKETING_*`, `SOURCE_SIMULATOR_PASSWORD` | 4 |
| source-simulator | `pti.datasource.sim` | `source_simulator` | `PTI_DATASOURCE_SIM_*`, `SOURCE_SIMULATOR_PASSWORD` | 4 |
| db-migrate | ba bộ Flyway | `pti_owner`, `ticketing_owner`, `sim_owner` | DOC-17 §5 | — |

Trạng thái: role và pool là **Chốt**; tên key `pti.datasource.*` của source-simulator là **Chốt** từ P1-10 (`pti.datasource.ticketing`, `pti.datasource.sim`); của api là **Khung** (P4).

Mỗi datasource `pti.datasource.<name>` gồm `url`, `username`, `password` (bind vào `DataSourceProperties`) và `hikari.*` (bind vào `HikariDataSource`, ví dụ `pti.datasource.reader.hikari.maximum-pool-size`). Trên k3d, URL của `reader` liệt kê hai host (pooler `ro` rồi `rw`, `targetServerType=preferSecondary`) và `hikari.max-lifetime` là 5 phút để đọc vẫn chạy khi primary failover (DOC-40 §5.2, §9.5).

### 3.2 source-simulator (`pti.sim.*`, DOC-25)

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.sim.service-date-mapping` | `auto` \| `fixed:<date>` | `auto` | Ánh xạ ngày thật sang ngày trong feed có cùng thứ | DR-08 | Chốt |
| `pti.sim.vehicle-position.interval` | Duration | `5s` | Chu kỳ phát VehiclePosition cho mỗi xe | F-SIM-01 | Chốt |
| `pti.sim.trip-update.interval` | Duration | `30s` | Chu kỳ phát TripUpdate cho mỗi chuyến | F-SIM-01 | Chốt |
| `pti.sim.trip-update.lookahead-stops` | int | `10` | Số trạm dự đoán tối đa trong một TripUpdate | DR-65 | Chốt |
| `pti.sim.vehicle-position.v2-ratio` | ratio | `0.5` | Tỷ lệ message dùng schema v2 | DOC-09 | Chốt |
| `pti.sim.vehicle.min-layover` | Duration | `10m` | Thời gian nghỉ tối thiểu giữa hai block của cùng một xe | DOC-13 §4 | Chốt |
| `pti.sim.ledger.retention` | Duration | `2d` | Số ngày giữ ledger | DR-28 | Chốt |
| `pti.sim.ticketing.fare.single` | USD | `2.00` | Giá vé lượt | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.fare.single-peak` | USD | `2.50` | Giá vé lượt giờ cao điểm ngày thường | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.fare.day` | USD | `5.00` | Vé ngày | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.fare.month` | USD | `76.00` | Vé tháng | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.mix` | map loại vé → ratio | `SINGLE: 0.80, DAY: 0.15, MONTH: 0.05` | Tỷ lệ loại vé | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.refund-ratio` | ratio | `0.01` | Tỷ lệ giao dịch được hoàn | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.void-ratio` | ratio | `0.002` | Tỷ lệ giao dịch bị hủy | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.delete-ratio` | ratio | `0.0005` | Tỷ lệ giao dịch bị xóa vật lý | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.kiosk-count` | int | `60` | Số kiosk | DOC-13 §5.4 | Chốt |
| `pti.sim.ticketing.rate-profile` | list 24 số (giao dịch/giây theo giờ Chicago) | bảng DOC-25 §9.2 | Tốc độ giao dịch theo giờ (cao điểm 2/giây) | FR-13.2 | Chốt |
| `pti.sim.ticketing.weekend-factor` | double | `0.6` | Hệ số cho thứ Bảy, Chủ nhật, ngày lễ | DOC-25 §9.2 | Chốt |
| `pti.sim.ticketing.customer-pool` | int | `50000` | Số khách mô phỏng cho `customer_ref` | DOC-25 §9.3 | Chốt |
| `pti.sim.rate-multiplier.gtfs-rt` | double | `1.0` | Hệ số tần suất phát GTFS-rt lúc khởi động; đổi lúc chạy qua `PUT /sim/rate`. Compose đặt `${PTI_SIM_START_RATE:-0}`, nên simulator mặc định tạm dừng (DOC-39 §3.2) | DR-68, DR-86 | Chốt |
| `pti.sim.rate-multiplier.ticketing` | double | `1.0` | Hệ số tốc độ bán vé lúc khởi động. Compose đặt như trên | DOC-25 §6.5, DR-86 | Chốt |
| `pti.sim.seed` | long | `42` | Seed của mọi phép lấy mẫu tất định | DOC-25 §5 | Chốt |
| `pti.sim.feed.location` | Resource | `file:/data/gtfs/metrotransit-mn-20260926.zip` | File GTFS zip | DOC-25 §4.1 | Chốt |
| `pti.sim.feed.sha256` | string | SHA-256 ở DOC-13 §2.1 | Rỗng thì không kiểm tra | DOC-25 §4.1 | Chốt |
| `pti.sim.tick` | Duration | `200ms` | Chu kỳ vòng phát | DOC-25 §6.1 | Chốt |
| `pti.sim.gps-noise` | double (mét) | `5` | Độ lệch chuẩn nhiễu vị trí | DOC-25 §5.5 | Chốt |
| `pti.sim.vehicle.max-layover-emit` | Duration | `30m` | Chờ đầu bến lâu hơn thì xe ngừng phát | DOC-25 §4.4 | Chốt |
| `pti.sim.delay.initial-mean.peak` / `.off-peak` | Duration | `60s` / `20s` | Trễ trung bình khi rời bến | DOC-25 §5.3 | Chốt |
| `pti.sim.delay.initial-sd.peak` / `.off-peak` | Duration | `45s` / `30s` | | DOC-25 §5.3 | Chốt |
| `pti.sim.delay.drift.peak` / `.off-peak` | Duration | `-8s` / `-5s` | Trễ thay đổi trung bình mỗi đoạn chạy (âm: xe bù giờ trên đường, bù cho thời gian đỗ thêm) | DOC-25 §5.3 (hiệu chỉnh ở P1-09) | Chốt |
| `pti.sim.delay.segment-sd.peak` / `.off-peak` | Duration | `12s` / `8s` | | DOC-25 §5.3 | Chốt |
| `pti.sim.delay.dwell-mean.peak` / `.off-peak` | Duration | `8s` / `5s` | Thời gian đỗ thêm trung bình | DOC-25 §5.3 | Chốt |
| `pti.sim.delay.early-limit` / `.late-limit` | Duration | `-120s` / `1200s` | | DOC-25 §5.3 | Chốt |
| `pti.sim.delay.min-speed-ratio` | ratio | `0.5` | Thời gian chạy tối thiểu so với lịch | DOC-25 §5.3 | Chốt |
| `pti.sim.route-factor.phi` / `.sigma` / `.bucket` | double / double / Duration | `0.8` / `0.15` / `5m` | Tương quan trễ theo tuyến | DOC-25 §5.4 | Chốt |
| `pti.sim.ledger.queue-capacity` / `.batch-size` / `.flush-interval` | int / int / Duration | `50000` / `500` / `200ms` | | DOC-13 §6.1, DOC-25 §6.4 | Chốt |
| `pti.sim.scenario.max-duration` | Duration | `2h` | | DOC-25 §7.1 | Chốt |
| `pti.sim.duplicates.queue-capacity` | int | `200000` | | DOC-25 §7.5 | Chốt |

### 3.3 etl (`pti.etl.*`, `pti.batch.*`, `pti.gtfs.*`, `pti.replay.*`, `pti.dq.*`, `pti.retention.*`)

Đã điền đủ theo DOC-16, DOC-18, DOC-19, DOC-20, DOC-21, DOC-22 (2026-09-27). Cột **Nguồn** trỏ tới mục mô tả chi tiết.

**Streaming (`etl-stream`, DOC-20)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.etl.listener.<id>.concurrency` | int | `gtfs-rt-vehicle-position` 3, `gtfs-rt-trip-update` 3, `ticketing-sales` 2, `ticketing-sale-points` 1 | Số thread của một listener | DOC-20 §1 | Chốt |
| `pti.etl.consumer.max-poll-records` | int | `500` | = kích thước micro-batch tối đa | DOC-20 §2 | Chốt |
| `pti.etl.consumer.fetch-max-wait` | Duration | `1s` | | DOC-20 §2 | Chốt |
| `pti.etl.consumer.fetch-min-bytes` | int | `65536` | | DOC-20 §2 | Chốt |
| `pti.etl.retry.initial-interval` / `.multiplier` / `.max-interval` | Duration / double / Duration | `1s` / `2.0` / `30s` | Backoff lỗi hạ tầng. Streaming thử vô hạn; batch dùng thêm hai key dưới | DOC-19 §9, DOC-20 §11 | Chốt |
| `pti.etl.retry.max-attempts` / `.max-elapsed` | int / Duration | `5` / `60s` | Chỉ áp cho job batch | DOC-19 §9 | Chốt |
| `resilience4j.circuitbreaker.instances.warehouse.*` | | cửa sổ 10 lần gọi, tối thiểu 5, ngưỡng 50%, mở 10 giây | Circuit breaker trước warehouse | DOC-20 §5.2 | Chốt |
| `pti.etl.reference.refresh-interval` | Duration | `30s` | Chu kỳ làm mới `ReferenceData` | DOC-21 §6.2 | Chốt |
| `pti.etl.reference.extra-days` | int | `3` | Số ngày ngoài hôm nay và hôm qua được cache `stop_times` | DOC-21 §6.1 | Chốt |
| `pti.etl.health.freshness` | Duration | `30s` | Ngưỡng tươi của health indicator nguồn (DR-38) | DOC-20 §6.1 | Chốt |
| `pti.etl.health.connect-url` | URI | `http://kafka-connect:8083` | Kafka Connect REST cho health `source-ticketing` | DOC-20 §6.1 | Chốt |
| `pti.etl.trace.max-links` | int | `20` | Số span link tối đa mỗi micro-batch | DOC-20 §3 | Chốt |
| `pti.etl.known-key-cache.max-size` | int | `20000` | `KnownKeyCache` cho placeholder `dim_vehicle` | DOC-20 §4.5 | Chốt |
| `pti.etl.ui-events.enabled` / `.flush-interval` | bool / Duration | `true` / `1s` | Phát `vehicles.batch` lên `pti.events.ui` | DOC-20 §8 | Chốt |
| `pti.etl.ui-events.job-throttle` | Duration | `2s` | `job.run` tối đa một lần mỗi khoảng này cho mỗi job execution | DOC-33 §5 | Chốt |
| `pti.etl.analytics.executor.threads` / `.queue-capacity` | int | `2` / `1000` | Executor chạy analytics sau commit | DOC-20 §8, DR-22 | Chốt |
| `pti.etl.baseline.offset-commit` / `.error-mode` / `.write-mode` / `.dedup` | enum | `manual` / `skip` / `upsert` / `on` | Chế độ baseline; chỉ đổi được trong profile `experiment` | DR-27, DOC-20 §9 | Chốt |
| `pti.etl.dedup.enabled` | bool | `true` | Bật dedup registry | F-ETL-07 | Chốt |
| `pti.etl.dedup.ttl` | Duration | `1h` | Thời gian giữ một dòng trong `dedup_registry` | DR-16 | Chốt |

**Batch và điều phối job (`etl-batch`, DOC-19)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.etl.batch.chunk-size` | int | `500` | Chunk của `RawZoneReplayJob` (`DlqReplayJob` luôn là 1) | DOC-19 §9 | Chốt |
| `pti.etl.batch.max-skip-ratio` | ratio | `0.2` | Vượt thì step `FAILED` | DR-23 | Chốt |
| `pti.etl.batch.skip-min-sample` | int | `100` | Số item tối thiểu trước khi xét tỷ lệ skip | DOC-19 §4.4 | Chốt |
| `pti.batch.stale-after` | Duration | `2m` | Ngưỡng coi một execution `STARTED` là kẹt | DR-24 | Chốt |
| `pti.batch.executor.core-size` / `.max-size` / `.queue-capacity` | int | `2` / `3` / `20` | Executor khởi chạy job | DOC-19 §3.3 | Chốt |
| `pti.batch.poller.interval` | Duration | `5s` | `JobRequestPoller` | DOC-19 §7 | Chốt |
| `pti.batch.schedule.<job>` | cron | bảng DOC-19 §2 | `-` để tắt lịch của một job | DOC-19 §2 | Chốt |

**GTFS static (DOC-21)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.gtfs.bootstrap-location` | URI | — (compose: `file:/feed/metrotransit-mn-20260926.zip`) | Nguồn cho lần nạp đầu | DOC-21 §1 | Chốt |
| `pti.gtfs.static.source` | URI | rỗng | Nguồn cho lịch hằng ngày; rỗng thì không đăng ký lịch | DOC-21 §1 | Chốt |
| `pti.gtfs.static.cron` / `.zone` | cron / ZoneId | `0 30 3 * * *` / `America/Chicago` | FR-01.1 | DOC-21 §1 | Chốt |
| `pti.gtfs.static.allowed-dirs` | list | `/feed` | Thư mục được phép cho `file:` | DOC-21 §1.1 | Chốt |
| `pti.gtfs.static.allow-http` | bool | `false` | Cho phép nguồn `https` | DOC-21 §1.1 | Chốt |
| `pti.gtfs.static.expected-sha256` | string | rỗng | | DOC-21 §3.1 | Chốt |
| `pti.gtfs.static.work-dir` | path | `/tmp/pti-gtfs` | | DOC-21 §3.1 | Chốt |
| `pti.gtfs.static.max-uncompressed-size` / `.max-entries` | DataSize / int | `1GB` / `100` | Chống zip bomb | DOC-21 §3.1 | Chốt |
| `pti.gtfs.static.max-row-errors` | int | `10000` | | DOC-21 §3.2 | Chốt |
| `pti.gtfs.static.report-samples` | int | `20` | Số mẫu mỗi check trong `validation_report` | DOC-21 §4 | Chốt |
| `pti.gtfs.static.keep-versions` | int | `3` | | DOC-21 §5, DOC-18 | Chốt |
| `pti.gtfs.static.rejected-retention` | Duration | `7d` | | DOC-21 §5 | Chốt |

**DLQ và replay (DOC-22)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.replay.raw-settle` | Duration | `10m` | `to` phải ≤ now − giá trị này | DOC-22 §4.1 | Chốt |
| `pti.replay.raw-max-age` | Duration | `29d` | `from` phải ≥ now − giá trị này | DOC-22 §4.1 | Chốt |
| `pti.replay.max-window` | Duration | `7d` | | DOC-22 §4.1 | Chốt |
| `pti.replay.max-objects` | int | `1000000` | Giới hạn an toàn; danh sách object không lưu trong `ExecutionContext` (DR-91). 7 ngày VehiclePosition khoảng 730.000 object | DOC-22 §4.3, DR-91 | Chốt |
| `pti.replay.chunk-size` | int | `500` | = `pti.etl.batch.chunk-size` | DOC-22 §8 | Chốt |
| `pti.replay.poller.interval` / `.max-claims` | Duration / int | `5s` / `10` | `ReplayRequestPoller` | DOC-22 §6 | Chốt |
| `pti.dlq.max-payload-bytes` | DataSize | `1MB` | Giới hạn payload lưu và payload sửa | DOC-22 §1.2 | Chốt |

**Chất lượng dữ liệu và PII (DOC-16, DOC-18)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.dq.max-clock-skew` | Duration | `1h` | DQ-07 | DOC-16 | Chốt |
| `pti.dq.max-delay` | Duration | `2h` | DQ-08 | DOC-16 | Chốt |
| `pti.dq.bbox-margin` | double (độ) | `0.1` | DQ-06 | DOC-16 | Chốt |
| `pti.dq.max-ticket-amount` | BigDecimal | `500.00` | DQ-10 | DOC-16 | Chốt |
| `pti.dq.refund-grace` | Duration | `5m` | DQ-12, DQ-22 | DOC-16 | Chốt |
| `pti.dq.post-write.enabled` / `.statement-timeout` | bool / Duration | `true` / `30s` | `DataQualityJob` | DOC-16 §3 | Chốt |
| `pti.dq.rules.<ID>.enabled` | bool | `true` | Tắt riêng một rule (không áp cho DQ-01) | DOC-16 | Chốt |
| `pti.pii.blocklist` | list | `[customer_ref]` | Trường bị loại khỏi DLQ, log, prompt (mọi app) | DOC-18 §4 | Chốt |

**Retention và partition (DOC-18)**

| Key | Kiểu | Mặc định | Mô tả | Nguồn | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| `pti.retention.vehicle-position` | Duration | `14d` (compose `3d`) | Retention partition `fact_vehicle_position` | DR-15, DOC-10 §3.3 | Chốt |
| `pti.retention.trip-update` | Duration | `90d` (compose `30d`) | Retention `fact_trip_update` | DOC-10 §3.3 | Chốt |
| `pti.retention.ticket-sales` | Duration | `365d` | Retention `fact_ticket_sales` | DOC-10 §3.3 | Chốt |
| `pti.retention.etl-stream-batch` | Duration | `7d` | | DR-22, DOC-15 §3.1 | Chốt |
| `pti.retention.dead-letter-resolved` | Duration | `30d` | Tính từ `resolved_at`; dòng chưa đóng thì giữ | DOC-15 §3.1 | Chốt |
| `pti.retention.replay-request`, `job-request`, `dq-check-result` | Duration | `30d` | | DOC-15 §3.1 | Chốt |
| `pti.retention.alert-event` | Duration | `90d` | | DOC-15 §3.1 | Chốt |
| `pti.retention.batch-metadata` | Duration | `30d` | Metadata `BATCH_*` | DR-62, DOC-18 | Chốt |
| `pti.retention.insight` | Duration | `365d` | Bảng `insight_*` (trừ ETA) | DOC-18, DOC-23 §12.3 | Chốt |
| `pti.retention.baseline-snapshot` | Duration | `30d` | `insight.analytics_baseline_snapshot`; phải ≥ khoảng tính lại tối đa (7 ngày) | DOC-18, DOC-23 §12.3 | Chốt |
| `pti.partition.precreate-days` | int | `7` | Số ngày partition được tạo trước | F-ETL-10 | Chốt |

**Chỉ dùng trong test và thực nghiệm (DOC-19 §8, DOC-44 §6.2)**

| Key | Kiểu | Mặc định | Mô tả | Trạng thái |
| --- | --- | --- | --- | --- |
| `pti.test.fault.<point>` / `pti.test.fault.after-n` / `pti.test.fault.times` | enum / int / int | — / `0` / `1` | Tiêm lỗi: cho qua `after-n` lần rồi làm hỏng `times` lần; chỉ có hiệu lực ở profile `test`, `experiment` | Chốt |
| `pti.kafka.topic-prefix` | string | rỗng | Ghép trước tên topic (DOC-09) ở mọi producer và listener; chỉ đổi trong integration test (mọi app) | Chốt |
| `pti.s3.raw-prefix` | string | rỗng | Prefix trong bucket `raw` khi đọc/ghi raw zone; chỉ đổi trong integration test | Chốt |

### 3.4 analytics, triage-worker, api

**`pti.analytics.*`** được liệt kê đầy đủ (khóa, kiểu, mặc định, ràng buộc) ở [DOC-23 §13](analytics.md#13-cấu-hình); bảng này không chép lại để tránh lệch. Một số khóa hay dùng:

| Key | Mặc định | Nguồn |
| --- | --- | --- |
| `pti.analytics.enabled` | `true` | Tắt toàn bộ analytics trực tiếp (chế độ baseline của EXP-04) |
| `pti.analytics.bunching.open-ratio` / `close-ratio` | `0.5` / `0.7` | DR-30 |
| `pti.analytics.disruption.bucket` / `warm-up-buckets` | `1m` / `60` | DR-31 |
| `pti.analytics.eta.window` / `realtime-enabled` | `28d` / `false` | DR-32, F-ANL-06; `realtime-enabled` và `eta.arrivals.*` do `api` đọc |
| `pti.analytics.otp.early-tolerance` / `late-tolerance` | `300s` / `300s` | DR-33 |
| `pti.analytics.ticketing.window` | `15m` | DR-34 |

Các nhóm còn lại:

**`pti.triage.*`** được liệt kê đầy đủ ở [DOC-24 §17](ai-triage.md#17-cấu-hình) (kèm validator); các khóa hay dùng:

| Key | Mặc định | Nguồn |
| --- | --- | --- |
| `pti.triage.provider` | `jev` (dev, staging); `fake` (CI, compose mặc định, demo offline) | DR-36 |
| `pti.triage.jev.sdk-retries` / `http-timeout` | `0` / `3s` | DOC-24 §4.3 |
| `pti.triage.dlq.claim-batch-size` / `lease` / `max-attempts` / `parallelism` | `50` / `2m` / `5` / `8` | DR-37 |
| `pti.triage.auto-replay.min-confidence` / `confirm-min-confidence` / `max-per-record` | `0.9` / `0.5` / `2` | FR-09.2, FR-09.3, ADR-0019 |
| `pti.triage.auto-replay.source-up-for` / `max-wait` / `max-per-minute` | `60s` / `6h` / `60` | DR-38, DOC-24 §6.7 |
| `pti.triage.health.etl-url` | `http://etl-stream:9080` | DOC-24 §6.6 |
| `pti.triage.disruption.data-issue-threshold` | `0.7` | FR-09.5 |
| `pti.triage.dispatch.max-age` / `disruption.max-age` / `ticketing.max-age` | `5m` / `30m` / `24h` | DOC-24 §5.5 |
| `pti.triage.quota-pause` | `15m` | DOC-24 §11.2 |
| `pti.triage.guard.pii-extra-keys` | rỗng | FR-09.10, DOC-24 §12 |
| `pti.triage.eval.*` | — | Chỉ profile `eval` cho EXP-06 (DOC-24 §14.1, §17) |
| `resilience4j.bulkhead.instances.decision-model.*` | `max-concurrent-calls: 8`, `max-wait-duration: 1s` | DOC-24 §11.1 |
| `resilience4j.ratelimiter.instances.decision-model.*` | `20` / `1s`, timeout `5s` | DOC-24 §11.1 |
| `resilience4j.circuitbreaker.instances.decision-model.*` | cửa sổ 20, tối thiểu 10, lỗi 50%, chậm 1.500 ms / 80%, mở 30 s, half-open 3 | DOC-24 §11.1 |
| `resilience4j.timelimiter.instances.decision-model.timeout-duration` | `2s` | FR-09.7 |

`TYPESAFE_API_KEY` là bí mật, chỉ đến từ env hoặc Secret (`pti-jev` trên k3d, DOC-40).

**`pti.api.*`** được liệt kê đầy đủ ở tài liệu thiết kế của từng phần; bảng dưới là chỉ mục và các khóa lẻ:

| Nhóm | Nơi định nghĩa | Ghi chú |
| --- | --- | --- |
| `pti.api.paging.*`, `pti.api.time.*`, `pti.api.cache.*`, `pti.api.rate-limit.*`, `pti.api.vehicles.*`, `pti.api.max-body-size` | [DOC-31 §14](../07-api/api-guidelines.md) | Chốt |
| `pti.api.sse.*` | [DOC-26 §11](realtime-delivery.md) | Chốt |
| `pti.api.security.*`, `pti.api.alert-webhook.token-file`, `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | [DOC-27 §13](security.md) | Chốt |
| `pti.api.freshness.stale-after.gtfs-rt` / `.ticketing` | DOC-32 E-60 | `120s` / `900s` (DR-38) |
| `pti.api.freshness.insight-interval` | DOC-32 E-60 | `5m` |
| `pti.api.freshness.max-probe-age` | DOC-32 E-60 | `60s`; kết quả probe cũ hơn thì `/system/freshness` trả 503 (P4-09) |
| `pti.api.dispatch.low-confidence` | DOC-32 E-17 | `0.6` (FR-09.6) |
| `pti.api.replay-estimate.throughput` | DOC-32 E-53 | `3000` message/giây, hiệu chỉnh theo EXP-04 |
| `pti.api.links.grafana-url` | DOC-32 E-32 | `http://localhost:3000` |
| `pti.api.sim.base-url` | DOC-32 E-90 | `http://source-simulator:8080`, chỉ profile `demo` |

### 3.5 frontend (`env.js`, DOC-34 §12)

Frontend không có file cấu hình Spring. Entrypoint của image `pti-frontend` đọc biến môi trường, render `/env.js` (`window.__PTI_ENV__`, `Cache-Control: no-store`) và header CSP (DOC-27 §5.3). `src/env.ts` kiểm bằng zod; thiếu hoặc sai thì dùng mặc định an toàn (UX-07).

| Biến môi trường | Khóa `env.js` | Mặc định | Ý nghĩa |
| --- | --- | --- | --- |
| `PTI_KEYCLOAK_URL` | `keycloakUrl` | rỗng | URL Keycloak mà **trình duyệt** gọi được (compose: `http://localhost:8180`). Rỗng → ẩn "Sign in" (DOC-37 §2.5) |
| `PTI_KEYCLOAK_REALM` | `keycloakRealm` | `pti` | |
| `PTI_KEYCLOAK_CLIENT_ID` | `keycloakClientId` | `pti-web` | Client public, PKCE |
| `PTI_MAP_STYLE` | `mapStyle` | `offline` | `offline` (PMTiles cục bộ `/tiles/`) \| `online` (OpenFreeMap, ADR-0021) |
| `PTI_MAP_TILE_ORIGINS` | — (chỉ CSP) | rỗng | Origin tile thêm vào `connect-src`/`img-src` khi `online` |
| `PTI_EXTRA_PROFILES` | `demoControl` | rỗng | Chứa `demo` → `demoControl: true` (hiện màn Demo control) |

Dev server (`pnpm dev`) dùng `frontend/public/env.js` đã commit: `mapStyle: "online"`, `demoControl: true`, Keycloak `http://localhost:8180`.

## 4. Cờ tức thời (`ops.runtime_flag`)

Mọi service đọc bảng mỗi 5 giây (DR-19). Người vận hành đổi cờ qua API (role `replay_operator`). Migration V5_2 tạo sẵn các cờ sau:

| Key | Mặc định | Tác dụng | Service đọc |
| --- | --- | --- | --- |
| `etl.consumer.gtfs-rt.paused` | `false` | Tạm dừng listener VehiclePosition và TripUpdate | etl-stream |
| `etl.consumer.ticketing.paused` | `false` | Tạm dừng listener CDC ticketing | etl-stream |
| `triage.dlq.enabled` | `true` | Phân loại dead letter mới bằng decision model | triage-worker |
| `triage.auto-replay.enabled` | `true` | Cho phép tự replay khi confidence vượt ngưỡng | triage-worker |
| `triage.ticketing.enabled` | `true` | Phân loại bất thường ticketing | triage-worker |
| `triage.disruption.enabled` | `true` | Làm giàu disruption | triage-worker |
| `triage.dispatch.enabled` | `true` | Gợi ý điều phối cho episode bunching | triage-worker |

Muốn thêm cờ thì viết migration mới chèn dòng vào bảng, rồi cập nhật bảng này.

## 5. Còn phải điền

- [x] §2: `spring.threads.virtual.enabled` (chốt theo app, S-06 chỉ có thể tắt; 2026-09-27).
- [x] §3.3: key của DOC-16, DOC-18, DOC-19 đến DOC-22 (2026-09-27).
- [x] §3.4: key của DOC-23, DOC-26, DOC-27, DOC-31, DOC-32 (2026-09-27).
- [x] §3.4: key của DOC-24 (2026-09-27).
- [x] Keycloak (DOC-27 §3.1, 2026-09-27).
- [x] Cấu hình frontend (`env.js`, §3.5, 2026-09-27).
- [x] Bảng env theo service: DOC-39 §3.2 (env riêng từng service) và §5 (`.env`) là bảng đầy đủ cho compose; `values-*.yaml` của DOC-40 §5 là bảng cho k3d. Mọi biến trong đó ánh xạ tới key ở đây theo quy ước §1.2 (2026-09-27).
- [x] Biến `PTI_DQ_MAX_CLOCK_SKEW` cho profile demo (DOC-39 §3.2, DOC-46).
