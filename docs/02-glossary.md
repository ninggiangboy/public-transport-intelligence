# Glossary: thuật ngữ

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 · DOC-06
> Phụ thuộc: [00-decision-register.md](00-decision-register.md)

Mọi tài liệu và mọi dòng code đều dùng đúng các thuật ngữ trong bảng này. Nếu cần một từ mới, hãy thêm vào đây trước.

- Cột **Thuật ngữ** là tên dùng trong code, UI, API và log (tiếng Anh, theo DR-61).
- Cột **Tiếng Việt** là cách gọi trong tài liệu. Nếu để trống thì tài liệu cũng dùng nguyên tên tiếng Anh.
- Cột **Liên quan** chỉ nơi định nghĩa chi tiết hoặc nơi dùng chính.

---

## 1. Miền giao thông công cộng (GTFS)

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| GTFS | — | General Transit Feed Specification. Chuẩn mở mô tả lịch chạy của hệ thống giao thông công cộng, gồm hai phần: static và realtime | gtfs.org |
| GTFS static / GTFS Schedule | lịch tĩnh | Bộ file CSV đóng gói zip, mô tả lịch **theo kế hoạch**: tuyến, trạm, chuyến, giờ đến từng trạm | DOC-13 |
| GTFS-realtime (GTFS-rt) | dữ liệu thời gian thực | Phần mở rộng của GTFS mô tả trạng thái **thực tế**: vị trí xe (VehiclePosition) và cập nhật giờ đến (TripUpdate). Hệ thống này dùng JSON envelope thay cho Protobuf (DR-03) | DOC-09 |
| feed | — | Một bản phát hành GTFS static của một đơn vị vận hành, tức một file zip | DR-01 |
| feed version | phiên bản feed | Một feed đã được nạp vào warehouse, định danh bằng `feed_version_id` và `feed_hash` (SHA-256 của file zip). Chỉ một phiên bản ở trạng thái `ACTIVE` | DR-10, DOC-21 |
| agency | đơn vị vận hành | Công ty hoặc tổ chức chạy tuyến (`agency.txt`). Feed Minneapolis có 8 agency | DOC-13 |
| route | tuyến | Một tuyến có tên công bố cho hành khách, ví dụ "Route 21" hay "METRO Blue Line" | `routes.txt` |
| route type | loại phương tiện | Mã GTFS: `3` = bus, `0` = light rail… | `routes.txt` |
| direction (`direction_id`) | chiều | `0` hoặc `1`, phân biệt hai chiều chạy của cùng một tuyến | `trips.txt` |
| trip | chuyến | Một lần xe chạy từ đầu đến cuối một tuyến theo lịch, định danh bằng `trip_id` | `trips.txt` |
| block (`block_id`) | ca xe | Chuỗi các chuyến nối tiếp nhau do **cùng một xe** chạy trong ngày. Simulator gán `vehicle_id` theo block | DOC-25 |
| stop | trạm | Điểm đón trả khách, có tọa độ | `stops.txt` |
| stop time | giờ tại trạm | Giờ đến và giờ đi **theo lịch** của một chuyến tại một trạm | `stop_times.txt` |
| stop sequence | thứ tự trạm | Số thứ tự tăng dần của trạm trong một chuyến. Cùng `trip_id` và `service_date` tạo thành khóa của `fact_trip_update` | DR-13 |
| shape | hình dạng tuyến | Chuỗi tọa độ mô tả đường xe đi trên bản đồ | `shapes.txt` |
| shape_dist_traveled | quãng đường tích lũy | Khoảng cách từ đầu shape tới một điểm. Dùng để tính tiến độ của xe trên tuyến | DR-30 |
| service date | ngày phục vụ | Ngày vận hành mà chuyến thuộc về, tính theo giờ địa phương của agency. Một chuyến bắt đầu lúc 23:50 vẫn thuộc service date của ngày hôm đó dù chạy sang ngày hôm sau | DR-09 |
| GTFS time (> 24:00) | giờ vượt ngày | Giờ trong GTFS có thể lớn hơn 24:00:00 (ví dụ `25:10:00`) cho chuyến chạy qua nửa đêm; giờ được tính từ "trưa trừ 12 giờ" của service date | DR-09 |
| calendar / calendar dates | lịch phục vụ | Quy định service nào chạy vào ngày nào (`calendar.txt`) và các ngày ngoại lệ (`calendar_dates.txt`) | DOC-13 |
| day type | loại ngày | `WEEKDAY`, `SATURDAY`, `SUNDAY_HOLIDAY`. Dùng để tra headway và baseline | DR-11 |
| headway | giãn cách | Khoảng thời gian giữa hai xe liên tiếp cùng tuyến và cùng chiều. **Scheduled headway** là giãn cách theo lịch, lưu trong `route_headway` | DR-12 |
| layover | chờ đầu bến | Thời gian xe đứng ở trạm đầu hoặc cuối giữa hai chuyến. Bị loại khỏi phát hiện bunching | DR-30 |
| vehicle | xe | Một phương tiện vật lý, định danh bằng `vehicle_id`, có sức chứa (`vehicles.txt`) | `dim_vehicle` |
| capacity | sức chứa | `seated_capacity + standing_capacity` | `vehicles.txt` |

## 2. Dữ liệu thời gian thực và chỉ số vận hành

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| VehiclePosition | vị trí xe | Message GTFS-rt cho biết vị trí, hướng, tốc độ và trạm kế tiếp của một xe tại một thời điểm | DOC-09 |
| TripUpdate | cập nhật chuyến | Message GTFS-rt cho biết giờ đến và độ trễ của một chuyến tại các trạm, gồm trạm đã qua và trạm sắp tới | DOC-09 |
| scheduled arrival | giờ đến theo lịch | Giờ đến trạm ghi trong `stop_times` | — |
| observed arrival | giờ đến thực tế | Giờ đến trạm **đã xảy ra**, tức `arrival_time ≤ event_timestamp`. Có `is_observed = true` | DR-13 |
| predicted arrival | giờ đến dự đoán | Giờ đến trạm chưa xảy ra trong một TripUpdate. Không dùng cho ETA, OTP hay disruption | DR-13 |
| delay | độ trễ | `actual_or_predicted_time − scheduled_time`, tính bằng giây. Số âm nghĩa là xe đến sớm | — |
| ETA | giờ đến dự kiến | Estimated Time of Arrival. Trong hệ thống này: giờ theo lịch cộng độ trễ trung bình lịch sử của khung `(route, stop, day_of_week, hour)` | DR-32 |
| sample count | số mẫu | Số lần đến trạm đã quan sát được dùng để tính ETA của một khung. Quyết định mức tin cậy: `<10` là LOW, `10–29` là MEDIUM, `≥30` là HIGH | DR-32 |
| OTP (on-time performance) | độ đúng giờ | Tỷ lệ phần trăm lần đến trạm có độ trễ nằm trong `[−early_tolerance, +late_tolerance]` (mặc định ±300 giây) | DR-33 |
| bunching | dồn chuyến | Hai xe liên tiếp cùng tuyến và cùng chiều chạy sát nhau, với gap < 0,5 × headway theo lịch | DR-30 |
| leader / follower | xe trước / xe sau | Trong một cặp bunching: xe đi trước và xe bám theo sau | DR-30 |
| gap | khoảng cách thời gian | Thời gian từ lúc leader qua trạm kế tiếp của follower tới thời điểm hiện tại của follower | DR-30 |
| disruption | gián đoạn dịch vụ | Tình trạng một tuyến trễ bất thường so với chính nó, được phát hiện bằng z-score trên baseline EWMA | DR-31 |
| episode | đợt | Một khoảng thời gian liên tục một sự kiện (bunching, disruption) đang diễn ra, có `episode_start`, `episode_end` và `status` OPEN hoặc CLOSED. Mỗi đợt tương ứng một dòng insight | DR-29 |
| baseline | đường nền | Giá trị "bình thường" của một chỉ số dùng để so sánh. Với disruption là EWMA của độ trễ; với ticketing là trung bình theo khung giờ | DR-31, DR-34 |
| EWMA | trung bình trượt mũ | `μ_new = α·x + (1−α)·μ_old`. Cho trọng số lớn hơn cho giá trị gần đây | DR-31 |
| z-score | — | `(x − μ) / σ`. Cho biết giá trị hiện tại lệch bao nhiêu độ lệch chuẩn so với baseline | DR-31 |
| hysteresis | ngưỡng trễ | Dùng hai ngưỡng khác nhau để mở và đóng episode (ví dụ mở khi z > 2,5, đóng khi z < 1,5), tránh bật tắt liên tục | DR-30, DR-31 |
| warm-up | khởi động | Giai đoạn đầu khi baseline chưa đủ dữ liệu; không phát cảnh báo | DR-31 |
| bucket | ô thời gian | Khoảng thời gian cố định (1 phút, 15 phút) để gom dữ liệu theo event time | DR-31, DR-34 |
| dispatch suggestion | gợi ý điều phối | Đề xuất `hold_follower`, `skip_stops` hoặc `no_action` cho một cặp bunching. Chỉ hiển thị, không gửi lệnh tới xe | SDD 9.5 |
| feed freshness | độ tươi dữ liệu | Thời gian kể từ event GTFS-rt mới nhất. Vượt 2 phút thì gọi là **feed stale** | DR-38 |

## 3. Ticketing

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| sale point | điểm bán | Nơi phát sinh giao dịch vé: `KIOSK` (máy bán vé tại trạm), `ONBOARD` (trên xe), `APP` (ứng dụng) | DR-06 |
| transaction | giao dịch | Một lần bán (`SALE`) hoặc hoàn vé (`REFUND`), định danh bằng `transaction_id` (UUID) | DR-06 |
| refund | hoàn vé | Giao dịch có `txn_type = REFUND`, tham chiếu giao dịch gốc qua `refund_of` | DR-06 |
| ticketing anomaly | bất thường ticketing | Cửa sổ 15 phút tại một điểm bán có số giao dịch hoặc tỷ lệ hoàn vé vượt baseline | DR-34 |
| customer_ref | mã khách hàng | Dữ liệu cá nhân mô phỏng. Bị loại tại ETL, không vào warehouse và không gửi sang Jev | DR-60 |

## 4. Streaming, CDC, Kafka

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| Kafka topic | — | Luồng message có tên, chia thành nhiều partition | DR-05 |
| partition | — | Đơn vị song song và đơn vị giữ thứ tự trong một topic. Message cùng key luôn vào cùng partition | ADR-0008 |
| offset | — | Vị trí của một message trong một partition | — |
| consumer group | nhóm consumer | Tập các consumer chia nhau đọc các partition của một topic | — |
| consumer lag | độ trễ đọc | Số message đã có trong partition mà consumer group chưa commit | DOC-28 |
| rebalance | phân bổ lại | Kafka chia lại partition giữa các consumer khi có consumer vào hoặc ra khỏi group | DOC-20 |
| KRaft | — | Chế độ Kafka tự quản lý metadata, không cần ZooKeeper (bắt buộc từ Kafka 4) | DOC-11 |
| idempotent producer | producer idempotent | Producer có `enable.idempotence=true` và `acks=all`, bảo đảm retry không tạo message trùng **trong Kafka** | DOC-25 |
| record timestamp (CreateTime) | — | Thời điểm producer tạo record. Dùng làm mốc đo NFR-03 | DR-57 |
| envelope | vỏ message | Cấu trúc JSON bọc payload, gồm `schema_version`, `message_id`, `event_timestamp`… | DR-04 |
| schema version | phiên bản schema | Số nguyên trong envelope cho biết cấu trúc payload. Parser hỗ trợ v1 và v2; phiên bản không biết thì vào DLQ | DR-59 |
| event time | thời gian sự kiện | Thời điểm của dữ liệu (`event_timestamp`), đối lập với **processing time** (lúc hệ thống xử lý). Mọi insight tính theo event time | DR-29 |
| CDC | thu thay đổi dữ liệu | Change Data Capture. Đọc thay đổi từ log của database nguồn và phát thành event | DR-07 |
| Debezium | — | Công cụ CDC chạy trên Kafka Connect | DR-07 |
| Kafka Connect | — | Framework chạy connector nguồn (Debezium) và đích (S3 sink) | DOC-07 |
| WAL | nhật ký ghi trước | Write-Ahead Log của PostgreSQL. Debezium đọc thay đổi từ đây | — |
| replication slot | — | Cơ chế của PostgreSQL giữ lại WAL cho tới khi consumer (Debezium) xác nhận. Tích tụ quá lớn sẽ đầy đĩa | SDD 12.2 |
| publication | — | Danh sách bảng mà PostgreSQL phát qua logical replication (`pgoutput`) | DR-07 |
| LSN | — | Log Sequence Number, vị trí trong WAL. Dùng làm guard để chỉ upsert khi thay đổi mới hơn | DR-07 |
| op (`c/u/d/r`) | — | Loại thay đổi CDC: create, update, delete, read (snapshot) | DR-07 |
| S3 sink | — | Connector ghi message từ Kafka vào object storage (SeaweedFS, DR-66) | ADR-0012 |

## 5. ETL và độ tin cậy

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| at-least-once | ít nhất một lần | Mỗi message được xử lý ít nhất một lần, và có thể nhiều lần khi có lỗi | ADR-0003 |
| effectively-once | hiệu quả như một lần | Kết quả cuối cùng trong warehouse giống như mỗi message được xử lý đúng một lần. Đạt được bằng at-least-once kết hợp ghi idempotent | ADR-0003 |
| idempotency | tính idempotent | Thực hiện một thao tác nhiều lần cho cùng kết quả như thực hiện một lần | — |
| business key | khóa nghiệp vụ | Tập trường xác định duy nhất một bản ghi theo nghĩa nghiệp vụ, ví dụ `(trip_id, stop_sequence, service_date)` | SDD 6.2 |
| natural key | khóa tự nhiên | Khóa lấy từ dữ liệu nguồn (ví dụ `route_id`), đối lập với surrogate key do hệ thống sinh | DR-10 |
| upsert | — | `INSERT … ON CONFLICT DO UPDATE`: chèn mới hoặc cập nhật nếu đã tồn tại | DOC-14 |
| event-time guard | chốt thời gian | Điều kiện trong upsert: chỉ ghi đè khi event mới hơn bản đang có (theo `event_timestamp` hoặc `lsn`) | DR-13, DR-07 |
| payload hash | băm payload | SHA-256 của JSON đã chuẩn hóa (canonical), bỏ `message_id` và `produced_at` | DR-04 |
| dedup registry | sổ chống trùng | Bảng `dedup_registry` lưu payload hash đã xử lý trong 24 giờ. Là lớp tối ưu, không phải cơ chế bảo đảm đúng đắn; bị bỏ qua khi replay | DR-16 |
| DLQ (dead letter queue) | hàng đợi lỗi | Bảng `dead_letter` chứa record lỗi dữ liệu kèm payload gốc, stage và lý do | DR-18 |
| stage | giai đoạn lỗi | Bước pipeline nơi record hỏng: `DESERIALIZE`, `SCHEMA`, `BUSINESS`, `QUALITY`, `DEDUP`, `LOAD` | DR-18 |
| error class | loại lỗi | Phân loại kỹ thuật: `DATA` (vào DLQ), `TRANSIENT_INFRA` (retry, pause), `FATAL` (dừng step) | DR-23 |
| poison message | message độc | Message luôn gây lỗi mỗi khi xử lý. Được phân loại là `DATA` và vào DLQ ngay, không retry vô hạn | SDD 12.8 |
| job | — | Định nghĩa một luồng xử lý batch trong Spring Batch (ví dụ `GtfsStaticLoadJob`) | DOC-19, ADR-0002 |
| step | bước | Một bước trong job: chunk step (reader, processor, writer) hoặc tasklet step | DOC-19 |
| tasklet | — | Step chỉ gồm một đoạn code chạy trong transaction, dùng cho việc SQL theo tập (ETA, OTP, bảo trì partition) | DOC-19 |
| job parameters | tham số job | Tham số truyền khi chạy job. Tham số *định danh* (identifying) quyết định job instance, ví dụ `serviceDate`, `feedHash`, `replayRequestId` | DR-24 |
| job instance | thể hiện job | Một job ứng với một bộ tham số định danh. Mỗi instance chỉ có tối đa một execution đang chạy | DR-24 |
| job execution / step execution | lần chạy | Một lần chạy cụ thể của job hoặc step, có `BatchStatus`, bộ đếm read/write/skip và `VERSION`; lưu trong `batch.BATCH_JOB_EXECUTION` và `batch.BATCH_STEP_EXECUTION` | DR-62 |
| JobRepository | kho metadata job | Thành phần Spring Batch lưu metadata job vào các bảng `BATCH_*` (bản JDBC, schema `batch`) | DR-62 |
| JobOperator | — | API Spring Batch để start, restart, stop job. Chỉ pod `etl-batch` gọi; API chỉ ghi yêu cầu vào bảng | DR-62, ADR-0013 |
| fault-tolerant step | step chịu lỗi | Chunk step bật skip, retry và scan: item lỗi dữ liệu bị bỏ qua (vào DLQ), lỗi tạm thời được retry | DR-21, DR-23 |
| skip listener | — | `SkipListener` nhận item bị skip ở read/process/write; dự án dùng nó để ghi DLQ trong transaction của chunk | DR-21 |
| stream chunk template | — | `StreamChunkTemplate`: lớp mỏng của dự án chạy thuật toán chunk (process → ghi batch → scan bằng savepoint → DLQ) cho luồng streaming, dùng lại processor và writer của job batch | ADR-0002, DR-21 |
| etl_stream_batch | — | Bảng ghi mỗi micro-batch streaming một dòng: `batch_id`, consumer, partition/offset đầu-cuối, bộ đếm, thời lượng. Giữ 7 ngày | DR-22 |
| batch_id | — | UUID định danh một **đơn vị ghi**: một micro-batch (`etl_stream_batch`) hoặc một step execution (`etl_batch_step`). Mọi dòng fact, record DLQ và log đều mang `batch_id` của đơn vị đã ghi ra nó | DR-63, NFR-05 |
| chunk | lô | N item được đọc, xử lý và ghi trong **một transaction**. Mặc định N = 500 | DR-21 |
| micro-batch | lô nhỏ | Một chunk của luồng streaming, bằng một lần poll Kafka (khoảng 1 giây). Mỗi micro-batch là một dòng `etl_stream_batch`, **không** tạo job execution | DR-22 |
| execution context | ngữ cảnh chạy | `ExecutionContext` của Spring Batch: map (serialize JSON) chứa vị trí đã commit (dòng, object raw zone, watermark). Được ghi **trong cùng transaction** với dữ liệu của chunk | DR-62, DOC-19 |
| ItemStream | — | Interface của reader/writer có trạng thái (`open`/`update`/`close`), cho phép lưu vị trí vào execution context và chạy tiếp khi restart | DOC-19 |
| checkpoint | điểm khôi phục | Vị trí đã commit mà từ đó có thể chạy tiếp: offset Kafka (streaming) hoặc execution context/watermark (batch) | SDD 6.2 |
| watermark | mốc nước | Giá trị đánh dấu "đã xử lý tới đây" của một nguồn batch, ví dụ `feed_hash` hoặc `max(event_timestamp)`. Lưu trong `etl_checkpoint` | — |
| skip policy | chính sách bỏ qua | `SkipPolicy`: quy tắc lỗi nào được bỏ qua (và đưa vào DLQ). Chỉ lỗi `DATA` được skip | DR-23 |
| skip limit | giới hạn bỏ qua | Ngưỡng skip tối đa của một step; dự án dùng **tỷ lệ** (`pti.etl.batch.max-skip-ratio`, mặc định 20%), vượt thì step FAILED | DR-23 |
| retry policy | chính sách thử lại | Quy tắc retry lỗi tạm thời: số lần, backoff, jitter | DOC-19 |
| backoff / jitter | giãn lùi / nhiễu | Tăng dần thời gian chờ giữa các lần retry, cộng thêm một khoảng ngẫu nhiên để các client không retry cùng lúc | — |
| savepoint | điểm lưu | Mốc trong transaction PostgreSQL có thể rollback về mà không hủy cả transaction | DR-21 |
| scan mode | chế độ dò | Khi ghi cả chunk thất bại vì lỗi dữ liệu: ghi lại từng item để tách item lỗi ra. Job batch dùng scan có sẵn của Spring Batch (mỗi item một transaction); streaming dùng savepoint trong một transaction | DR-21 |
| ShedLock | — | Thư viện khóa cho `@Scheduled`: chỉ một pod kích hoạt một tác vụ tại một thời điểm. Dùng bảng `shedlock` và UPDATE thường nên chạy được qua PgBouncer | DR-24 |
| stale execution | execution kẹt | Job execution còn STARTED nhưng `LAST_UPDATED` cũ hơn `pti.batch.stale-after` (2 phút) vì pod đã chết. `StaleExecutionRecoverer` đánh dấu FAILED rồi restart | DR-24 |
| fencing (bằng `VERSION`) | rào ghi | Spring Batch cập nhật `BATCH_STEP_EXECUTION` với kiểm tra `VERSION` trong transaction của chunk. Sau khi execution kẹt bị đánh dấu FAILED, pod cũ còn sống sẽ gặp `OptimisticLockingFailureException` và bị rollback | DR-24 |
| raw zone | vùng dữ liệu thô | Bucket `raw` trên SeaweedFS, lưu nguyên bản mọi message và feed GTFS. Là nguồn sự thật để dựng lại warehouse | ADR-0012 |
| replay | chạy lại | Xử lý lại dữ liệu đã có. Ba loại: **DLQ replay** (một record đã sửa), **raw zone replay** (một khoảng thời gian từ raw zone), **offset reset** (đọc lại từ Kafka) | DOC-22 |
| data quality rule (DQ rule) | quy tắc chất lượng | Kiểm tra dữ liệu. **Pre-write** thì loại record vào DLQ; **post-write** thì ghi kết quả vào `dq_check_result` và cảnh báo | DR-25 |
| backpressure | áp lực ngược | Cơ chế giảm tốc độ đọc khi phía ghi chậm (pause listener, giới hạn `max.poll.records`) | SDD 6.2 |
| pause / resume | tạm dừng / tiếp tục | Dừng hoặc tiếp tục poll của Kafka listener container mà không rời consumer group | DOC-20 |
| graceful shutdown | tắt mềm | Khi nhận SIGTERM: dừng poll, hoàn tất chunk đang dở, commit, rời group rồi mới thoát | DOC-20 |
| baseline mode | chế độ đối chứng | Cấu hình cố ý tắt các cơ chế tin cậy (auto-commit, insert thường, fail cả batch) để so sánh trong thực nghiệm. Chỉ bật được trong profile `experiment` | DR-27 |

## 6. Warehouse và dữ liệu

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| warehouse | kho dữ liệu | Database PostgreSQL `pti_warehouse` chứa dimension, fact, bảng vận hành và bảng insight | DOC-14 |
| star schema | lược đồ hình sao | Mô hình gồm bảng fact ở giữa, tham chiếu tới các bảng dimension | DOC-14 |
| dimension | chiều | Bảng mô tả đối tượng: tuyến, trạm, xe, ngày | DOC-14 |
| fact | sự kiện | Bảng ghi các sự kiện đo được: vị trí xe, cập nhật chuyến, giao dịch vé | DOC-14 |
| grain | độ mịn | Mỗi dòng của một bảng fact đại diện cho cái gì | DOC-14 |
| insight table | bảng insight | Bảng `insight_*` chứa kết quả analytics | DOC-15 |
| partition (PostgreSQL) | phân vùng | Chia một bảng lớn theo khoảng thời gian (declarative partitioning) để truy vấn và xóa dữ liệu cũ nhanh | DR-15 |
| retention | thời hạn lưu | Thời gian giữ dữ liệu trước khi xóa | DOC-18 |
| expand/contract | mở rộng/thu hẹp | Cách đổi schema không gián đoạn: thêm cấu trúc mới, chuyển code sang dùng nó, sau đó mới xóa cấu trúc cũ | ADR-0024 |
| ground truth / ledger | dữ liệu chuẩn / sổ cái | Bảng `sim_ledger` do simulator ghi, liệt kê mọi message đã phát. Dùng để đo mất và trùng trong thực nghiệm | DR-28 |

## 7. AI triage

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| Jev | — | Mô hình của TypeSafe AI, trả về quyết định có kiểu kèm xác suất, không sinh văn bản | DR-36 |
| TypeSafeClient | — | Client của `typesafe-java-sdk`. Hàm chính là `systemOne(state, questions)` | DR-36 |
| state | trạng thái | Ngữ cảnh gửi cho Jev (JSON object). Không được là số hoặc boolean đứng riêng | DR-36 |
| Choice | — | Câu hỏi chọn một nhãn; trả về nhãn, xác suất từng nhãn và confidence | DR-36 |
| Score | — | Câu hỏi đặt giá trị lên một thang có thứ tự; trả về giá trị liên tục, xác suất từng mức và confidence | DR-36 |
| Noul | — | Câu hỏi có hoặc không; trả về một số trong [0,1] | DR-36 |
| DecisionModel | cổng quyết định | Interface nội bộ bọc Jev, có các adapter `jev`, `fake`, `disabled` | ADR-0018 |
| triage | phân loại | Gán `category` và `severity` cho record DLQ hoặc cho bất thường | DOC-24 |
| category | nhóm lỗi | Với DLQ: `schema_violation`, `referential_integrity`, `upstream_api_error`, `transient_network`, `unknown` | SDD 9.2 |
| severity | mức nghiêm trọng | `0` Informational (chỉ ghi log), `1` Needs attention (thông báo kênh vận hành), `2` Urgent (cảnh báo khẩn) | SDD 9.2 |
| confidence | độ tin cậy | Xác suất mà Jev gán cho câu trả lời được chọn | — |
| confidence gate | cổng tin cậy | Quy tắc do code sở hữu: chỉ tự động hành động khi confidence vượt ngưỡng | ADR-0019 |
| auto-replay | tự chạy lại | Hệ thống tự replay record DLQ khi thỏa bảng quyết định (tối đa 2 lần mỗi record) | SDD 9.2 |
| confirm queue | hàng chờ xác nhận | Các đề xuất có confidence từ 0,5 đến 0,9, chờ người xác nhận bằng một cú bấm | UC-09 |
| hard-rule backstop | luật chặn cuối | Luật cứng luôn chạy bất kể Jev trả gì, ví dụ quá N lỗi `upstream_api_error` mỗi giờ | SDD 9.6 |
| model version | phiên bản model | Chuỗi ghi kèm mọi quyết định của Jev để truy vết | DR-36 |

## 8. API, real-time, UI

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| SSE | — | Server-Sent Events. Kênh HTTP một chiều server → client | DOC-26 |
| channel | kênh | Nhóm sự kiện SSE: `vehicles`, `alerts`, `jobs`, `dlq` | DR-41 |
| Last-Event-ID | — | Header client gửi khi kết nối lại, để server phát bù các sự kiện đã lỡ | DR-41 |
| resync | đồng bộ lại | Sự kiện SSE báo client phải refetch qua REST vì khoảng bị lỡ vượt quá buffer | DR-41 |
| ring buffer | bộ đệm vòng | Bộ nhớ đệm các sự kiện 5 phút gần nhất ở mỗi pod API | DR-41 |
| ULID | — | Định danh 128 bit có thứ tự theo thời gian, dùng làm id của sự kiện UI | DR-41 |
| Problem Details | — | Định dạng lỗi HTTP theo RFC 9457 (`type`, `title`, `status`, `detail`) | DR-39 |
| keyset pagination | phân trang theo khóa | Phân trang bằng con trỏ (`cursor`) dựa trên khóa sắp xếp, thay vì dùng offset | DR-39 |
| audience | đối tượng nhận | Ai được thấy một cảnh báo: `PUBLIC`, `OPERATIONS`, `ENGINEERING` | DR-17 |
| alert event | sự kiện cảnh báo | Một dòng trong `alert_event`, hiển thị trên Alert feed | DR-17 |
| data as of | dữ liệu tính đến | Thời điểm của dữ liệu mới nhất mà một màn hình hoặc response đang hiển thị (header `X-Data-As-Of`) | DR-39 |
| stale banner | dải cảnh báo dữ liệu cũ | Thông báo trên UI khi feed stale hoặc pipeline có sự cố | DOC-37 |
| role | vai trò | `anonymous` (hành khách), `viewer`, `operator` | DR-40 |
| IdP | nhà cung cấp danh tính | Keycloak, phát JWT cho SPA và API | ADR-0017 |

## 9. Vận hành và hạ tầng

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| trace_id / span | — | Định danh một chuỗi xử lý xuyên service (OpenTelemetry), và một bước trong chuỗi đó | DOC-28 |
| circuit breaker | cầu dao | Ngừng gọi một phụ thuộc đang lỗi trong một khoảng thời gian, sau đó thử lại | DOC-20 |
| bulkhead | vách ngăn | Giới hạn số lời gọi đồng thời tới một phụ thuộc, để phụ thuộc đó lỗi không kéo cả hệ thống | DOC-24 |
| rate limiter | bộ giới hạn tốc độ | Giới hạn số lời gọi trong một đơn vị thời gian | DR-45 |
| graceful degradation | suy giảm có kiểm soát | Khi một phần hỏng, phần còn lại vẫn phục vụ, kèm thông báo rõ ràng | SDD 12.8 |
| profile (compose / Spring) | hồ sơ cấu hình | Nhóm service hoặc cấu hình bật theo mục đích: `core`, `observability`, `triage`, `demo`, `experiment` | DOC-39 |
| runtime flag | cờ vận hành | Cờ bật/tắt trong bảng `runtime_flag`, có hiệu lực trong ≤ 5 giây mà không cần restart | DR-19 |
| k3d | — | Chạy cluster Kubernetes (k3s) trong Docker, dùng cho staging cục bộ | DOC-40 |
| operator (Kubernetes) | — | Controller quản lý một phần mềm phức tạp trên K8s. **Không nhầm với role `operator` của người dùng** | DOC-40 |
| Strimzi / CloudNativePG (CNPG) / KEDA | — | Operator cho Kafka / PostgreSQL / autoscaling theo event | ADR-0028 |
| PgBouncer | — | Connection pooler cho PostgreSQL | DR-24 |
| HPA / PDB | — | Horizontal Pod Autoscaler / PodDisruptionBudget | DOC-40 |
| Toxiproxy / Chaos Mesh | — | Công cụ tiêm lỗi mạng / tiêm sự cố cho Kubernetes | EXP-08 |

## 10. Thực nghiệm và mô phỏng

| Thuật ngữ | Tiếng Việt | Định nghĩa | Liên quan |
| --- | --- | --- | --- |
| source simulator | bộ mô phỏng nguồn | Ứng dụng đóng vai hệ thống nguồn: phát GTFS-rt và ghi giao dịch vé | DOC-25 |
| scenario | kịch bản | Chế độ của simulator để tái hiện một tình huống: `bunching`, `disruption`, `bad-data`, `duplicates`, `ticket-spike`, `refund-burst`, `load-ramp` | DOC-25 |
| base load | tải nền | Tải của lịch thật vào giờ cao điểm ngày thường của feed Minneapolis: khoảng 606 xe đang phục vụ, mỗi xe phát 1 VehiclePosition mỗi 5 giây, và 472 chuyến đang chạy, mỗi chuyến phát 1 TripUpdate mỗi 30 giây và thêm 1 TripUpdate mỗi lần xe tới trạm (khoảng 6,3 lần/giây toàn mạng). Tổng khoảng **143 message/giây** (121 + 16 + 6) | DR-01, DOC-10, EXP-07 |
| load multiplier | hệ số tải | Hệ số nhân tốc độ phát event (1× … 10×) trong kịch bản `load-ramp` | EXP-05, EXP-07 |
| experiment run | lần chạy thực nghiệm | Một lần chạy một EXP, lưu trong `experiments/results/<EXP>/<run_id>/` | DR-52 |
| experiment series | chuỗi thực nghiệm | Các lần chạy do một lệnh `pti-exp run` sinh ra, cùng `series` trong `config.json` và cùng `git_sha`; là đơn vị để gói và lưu trữ file kết quả nặng | DOC-45 §1.1, §7.1 |
| experiment machine | máy thực nghiệm | Máy cố định, dành riêng cho các lần chạy chính thức (16 GB RAM, CPU không chia sẻ); không phải máy dev, không phải runner CI | DR-94, DOC-45 §1.2 |
| results archive | kho kết quả | GitHub Release `exp-results` chứa file nặng của từng chuỗi, mỗi chuỗi một file nén có SHA-256 ghi trong manifest ở git | DR-94, DOC-45 §7.1 |
| loss / duplicate rate | tỷ lệ mất / trùng | Tính bằng cách so ledger với warehouse theo DR-28 | DOC-45 |
| end-to-end latency | độ trễ đầu-cuối | `thời điểm API phát SSE − Kafka CreateTime` của event gốc | DR-57 |
