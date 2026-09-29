# EXP-04: Dựng lại toàn bộ warehouse từ raw zone

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 (DR-95) · DOC-45 / EXP-04
>
> Phụ thuộc: [protocol chung](README.md), DOC-18 §1–2, DOC-21 §1, DOC-22 §4, DOC-39 §6 (`make reset-warehouse`), DOC-43, RB-11, DR-16, DR-58, DR-64, DR-70
>
> Người dùng chính: P3-07, P3-08 (smoke), P3-10; báo cáo chương đánh giá; người viết RB-11

## 1. Giả thuyết

- **H1 (NFR-01, FR-12.2, UC-18):** xóa hẳn warehouse rồi dựng lại chỉ từ raw zone (feed GTFS và bốn topic trong `s3://raw/`) cho ra warehouse **giống hệt** bản được luồng trực tiếp xây dựng, theo checksum DR-58 của mọi bảng dữ liệu, kể cả tập dead letter.
- **H2 (idempotent):** chạy replay lần thứ hai trên warehouse vừa dựng lại không làm thay đổi checksum nào.
- **H3 (vận hành):** thời gian dựng lại đủ nhỏ để RB-11 khả thi. Mục tiêu tham khảo: thông lượng replay ≥ 2.000 message/giây mỗi nguồn (DOC-22 §4.4).

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Cách xây warehouse | Luồng trực tiếp (live) → dựng lại bằng replay (rebuild) → replay lần hai (rebuild-2) |
| Phụ thuộc | Tính đồng nhất | `row_count`, `checksum` từng bảng (README §4.5); tập dead letter |
| Phụ thuộc | Thời gian | `reset_seconds`, `gtfs_load_seconds`, `replay_seconds` theo nguồn, `rebuild_total_seconds`; thông lượng `lines_read / duration` |
| Kiểm soát | Dữ liệu | 30 phút tải ×2 bắt đầu lúc 15:00 CDT, ngày thứ Ba 2026-09-29 (cùng ngày cho mọi lần chạy, vì mỗi lần chạy bắt đầu từ trạng thái trống) |
| Kiểm soát | Nhiễu có chủ đích | `bad-data` 1% (mọi loại **trừ** `future_timestamp`) và `duplicates` 5% (`[PT0S, PT60S]`) chạy suốt 30 phút |
| Kiểm soát | Feed | Như README §1; feed nạp lại từ `s3://raw/gtfs-static/<sha256>.zip` |

**Vì sao bỏ `future_timestamp`:** replay bỏ qua DQ-07 (DOC-16 §2) vì dữ liệu cũ luôn lệch xa `businessNow`. Một message lệch 2 giờ bị luồng trực tiếp đưa vào DLQ nhưng lại được replay nạp vào fact. Đây là khác biệt **theo thiết kế**, không phải lỗi, nên loại khỏi phép so sánh đồng nhất. Hành vi này được test riêng (DOC-16 §8, DOC-22 §11).

## 3. Baseline

Không chạy baseline DR-27. Câu hỏi của EXP-04 là "replay có tái tạo đúng trạng thái không", và mốc so sánh là chính warehouse do luồng trực tiếp xây. Phản chứng "replay không idempotent" (INSERT thuần) cho kết quả hiển nhiên (mỗi lần replay nhân đôi số dòng), nên H2 được đo trực tiếp bằng rebuild-2 thay vì dựng một writer baseline cho job batch.

## 4. Môi trường

README §1, `make up-exp`. Mỗi lần chạy bắt đầu bằng `make reset` (xóa mọi volume) để raw zone và warehouse phủ **đúng cùng** một khoảng dữ liệu. Profile `observability` bật (để quan sát, không ảnh hưởng phép đo đồng nhất).

## 5. Các bước

```bash
uv run pti-exp run EXP-04 --runs 10 --seed 8000
uv run pti-exp analyze EXP-04
```

Một lần chạy (khoảng 80 phút):

1. **Trạng thái trống:** `make reset`, đặt `PTI_CLOCK_OFFSET` sao cho giờ nghiệp vụ = 2026-09-29 14:50 CDT, `make up-exp`, chờ `make smoke`. `t_stack` = lúc stack healthy. Ghi SHA-256 của feed ACTIVE.
2. **Tải:** chờ tới 15:00 giờ nghiệp vụ; `PUT /sim/rate {"gtfsRt": 2, "ticketing": 1}`; bật `bad-data` (`ratio` 0,01, `kinds` bỏ `future_timestamp`, `duration` `PT30M`) và `duplicates` (`ratio` 0,05, `duration` `PT30M`). Silence `DlqRateHigh`, `DlqBacklogHigh`.
3. **Dừng nguồn:** hết 30 phút, `docker stop pti-source-simulator-1` (dừng cả GTFS-rt và ticketing seeder). Drain (README §2.1). `t_stop` = lúc drain xong.
4. **Chờ raw zone ổn định:** đợi tới `t_stop + 12 phút` (S3 sink rotate tối đa 5 phút, replay đòi `to_ts ≤ now − 10 phút`, DR-70). Kiểm tra: số dòng raw zone của mỗi topic (đếm bằng `pti-exp` qua S3) = log-end-offset − offset đầu của topic.
5. **Chụp trạng thái live:** chạy mọi file `sql/checksum/*.sql` (§6), lưu `before.json`. Xuất tập dead letter `(kafka_topic, kafka_partition, kafka_offset, stage, rule_id)` ra `dlq_before.csv.gz`.
6. **Xóa warehouse:** `make reset-warehouse` với `PTI_GTFS_BOOTSTRAP_LOCATION=s3://raw/gtfs-static/<sha256>.zip` (runner ghi đè biến này cho lần khởi động lại `etl-batch`), nên `GtfsBootstrapRunner` nạp feed **từ raw zone** (DOC-21 §1). Offset Kafka giữ nguyên (mặc định của lệnh), nên `etl-stream` không đọc lại gì. Đo `reset_seconds` và `gtfs_load_seconds` (tới khi feed `ACTIVE`).
7. **Replay** bằng `make replay` (DOC-38 §4.3; từ P4 có thể dùng `POST /etl/replays`), khoảng `[t_stack − 1 phút, t_stop + 1 phút]` (giờ record Kafka), `recompute_analytics = false` (cả từ P4, xem bước 7.4):
   1. `TICKETING_SALE_POINTS` trước, chờ `DONE`;
   2. `TICKETING_SALES`, `GTFS_RT_VEHICLE_POSITION`, `GTFS_RT_TRIP_UPDATE` song song (mỗi nguồn một replay RUNNING, DOC-22 §6);
   3. chờ cả ba `DONE`; lấy `replay_request.stats` (DOC-22 §4.6);
   4. **từ P4:** `make job-run NAME=AnalyticsRecomputeJob PARAMS='detectors=BUNCHING+DISRUPTION+TICKETING,fromTs=<t_stack − 1 phút>,toTs=<t_stop + 1 phút>' WAIT=1` (DOC-23 §11.5). Chạy một job sau cùng thay vì `recompute_analytics = true` trên từng replay, vì ba replay chạy song song và bunching cần cả vị trí lẫn feed đã nạp đủ.
8. **So sánh rebuild:** chạy lại checksum → `after.json`, tập dead letter → `dlq_after.csv.gz`.
9. **Rebuild-2:** tạo lại bốn replay (và từ P4 cả job tính lại) y như bước 7, chờ `DONE`, checksum → `after2.json`.
10. Ghi `summary.json`, xóa silence.

Thứ tự ở bước 7.1 để `dim_sale_point` có dòng CDC trước khi giao dịch cần tới. Nếu đảo thứ tự, ETL tạo placeholder `INFERRED` rồi nâng cấp khi dòng CDC tới (DOC-09 §5.3). Kết quả cuối vẫn phải giống, và đó là một biến thể tùy chọn (`--order sales-first`, 3 lần chạy) để kiểm chứng.

**Lần chạy `invalid`:** như README §6; thêm: đếm raw zone ở bước 4 không khớp Kafka (sink mất dữ liệu, không thuộc phạm vi EXP-04; báo cáo riêng); replay `FAILED` vì hạ tầng.

## 6. Chỉ số và công thức

Bảng so sánh (file checksum và cột bị loại ở README §4.5), trên **toàn bảng**, không lọc cửa sổ (warehouse chỉ chứa dữ liệu của lần chạy):

| Nhóm | Bảng |
| --- | --- |
| Fact | `dw.fact_vehicle_position`, `dw.fact_trip_update`, `dw.fact_ticket_sales` |
| Trạng thái | `dw.vehicle_position_latest` |
| Dimension từ luồng | `dw.dim_sale_point`, `dw.dim_vehicle` |
| DLQ | Tập `(kafka_topic, kafka_partition, kafka_offset, stage, rule_id)` của `ops.dead_letter` |
| GTFS | `dw.dim_route`, `dw.dim_stop`, `dw.gtfs_trip`, `dw.gtfs_stop_time`: chỉ so `row_count` (khóa đại diện và `feed_version_id` là identity nên khác giá trị giữa hai lần nạp) |
| Insight (từ P4) | `insight.insight_bus_bunching`, `insight.insight_service_disruption`, `insight.insight_ticketing_anomaly`; cột bị loại thêm theo DOC-23 §11.6 (cột enrichment AI và `created_at`). ETA, OTP, gợi ý điều phối và bảng `analytics_*` không so (DOC-23 §11.6) |

| Chỉ số | Công thức |
| --- | --- |
| `table_match[T]` | `before[T].row_count = after[T].row_count` **và** `before[T].checksum = after[T].checksum` |
| `dlq_symdiff` | `|DLQ_before △ DLQ_after|` |
| `idempotent[T]` | `after[T] = after2[T]` (cả số dòng và checksum) |
| `replay_throughput[source]` | `stats.lines_read / (stats.duration_ms / 1000)` |
| `rebuild_total_seconds` | Từ lúc bắt đầu bước 6 tới khi replay cuối cùng của bước 7 `DONE` |
| `diff_rows[T]` | Chỉ khi `table_match[T]` sai: runner xuất các dòng khác nhau (so theo business key, trước và sau) ra `diff_<T>.csv.gz` để điều tra |

Trạng thái dead letter không được so sánh (live có thể là `NEW`; replay ghi `NEW` hoặc cập nhật), chỉ so tập vị trí và phân loại.

## 7. Tiêu chí đạt

| # | Tiêu chí |
| --- | --- |
| C1 | `table_match = true` cho mọi bảng fact, trạng thái và dimension ở **mọi** lần chạy hợp lệ |
| C2 | `dlq_symdiff = 0` ở mọi lần chạy |
| C3 | `idempotent = true` cho mọi bảng ở mọi lần chạy |
| C4 | Số dòng các bảng GTFS khớp |
| C5 | (Từ P4) `table_match = true` và `idempotent = true` cho ba bảng insight ở §6. Chỉ áp dụng khi pha live thỏa điều kiện hợp lệ của DOC-23 §11.6 (`pti_analytics_late_batches_total` và `pti_analytics_skipped_ticks_total` không tăng; `kafka_to_commit` của `TICKETING_SALES` ≤ 60 giây); nếu không thì ghi `not_applicable` kèm lý do, không tính là trượt |
| C6 | Biến thể `sales-first` (nếu chạy): C1 và C2 vẫn đúng |

H3 được báo cáo (không là tiêu chí đạt): trung vị và p95 của `replay_throughput` theo nguồn, `rebuild_total_seconds`. Nếu thông lượng < 2.000 message/giây, ghi vào DOC-43 (RTO) và mở việc tối ưu.

## 8. Phân tích

- Bảng C1…C4 theo bảng dữ liệu × lần chạy.
- Biểu đồ cột thời gian: reset, nạp GTFS, replay từng nguồn (xếp chồng) cho mỗi lần chạy.
- Ngoại suy thời gian dựng lại theo khối lượng: `rebuild_total_seconds` theo số message; tuyến tính hóa để ước lượng thời gian dựng lại 1 ngày (khoảng 6,9 triệu message) và 7 ngày. Kết quả đưa vào DOC-43 §RTO.

## 9. Mẫu bảng kết quả

| Bảng | Số dòng (trung vị) | Khớp live ↔ rebuild | Khớp rebuild ↔ rebuild-2 |
| --- | --- | --- | --- |
| `fact_vehicle_position` | … | 10/10 | 10/10 |
| `fact_trip_update` | … | | |
| `fact_ticket_sales` | … | | |
| `vehicle_position_latest` | … | | |
| `dim_sale_point` | … | | |
| `dim_vehicle` | … | | |
| Dead letter (tập) | … | 10/10 | — |

| Nguồn | Message | Thời gian replay trung vị [CI] | Thông lượng trung vị (msg/s) |
| --- | --- | --- | --- |
| `GTFS_RT_VEHICLE_POSITION` | … | … | … |
| `GTFS_RT_TRIP_UPDATE` | … | | |
| `TICKETING_SALES` | … | | |
| `TICKETING_SALE_POINTS` | … | | |
| **Tổng dựng lại** (`reset` + GTFS + replay) | | … | |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Khối lượng nhỏ (30 phút) so với tình huống thật (nhiều ngày) | Ngoại suy tuyến tính ở §8; chạy thêm một lần 2 giờ ở tải ×5 nếu thời gian cho phép và báo cáo riêng |
| Checksum dạng text phụ thuộc cách Postgres in số thực và timestamp | Cùng phiên bản Postgres, cùng `TimeZone = UTC` và `extra_float_digits` mặc định cho cả hai lần tính; hai lần tính chạy trên cùng instance |
| Luồng trực tiếp không bị lỗi hạ tầng trong lần chạy, nên không kiểm được "replay sửa được warehouse hỏng" | Ngoài phạm vi; EXP-01 đo đúng đắn của luồng trực tiếp khi có lỗi |
| DQ-07 bị bỏ khi replay tạo khác biệt theo thiết kế | Loại `future_timestamp` khỏi nhiễu (§2) và nêu trong báo cáo |
| Replay dùng feed ACTIVE hiện tại; nếu feed đổi giữa live và replay thì kết quả khác | Cùng một feed trong mọi lần chạy; RB-11 nêu bước kích hoạt lại feed cũ |
| `make reset` mỗi lần chạy làm cache hệ điều hành và JIT lạnh | Thời gian (H3) báo cáo cả trung vị và p95; không so với EXP khác |

## 11. Kết quả

Điền sau P3-10. Kết quả chuỗi smoke (DR-95) không ghi vào đây.

## 12. Câu hỏi còn mở

Không có.
