# EXP-01: Kill consumer giữa chừng, phục hồi không mất và không trùng

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 (DR-95) · DOC-45 / EXP-01
>
> Phụ thuộc: [protocol chung](README.md), DOC-19 §8 (`FaultPoint`), DOC-20 §3, §5, §9, §10, DOC-39 §3.6 (Toxiproxy), ADR-0003, ADR-0004, DR-27, DR-28
>
> Người dùng chính: P3-06, P3-08 (smoke), P3-10; báo cáo chương đánh giá

## 1. Giả thuyết

- **H1 (NFR-01):** khi tiến trình `etl-stream` bị kill ở một thời điểm bất kỳ trong lúc có tải, sau khi chạy lại, warehouse chứa **mỗi** business key hợp lệ đúng một lần, với giá trị của message mới nhất. Không mất, không trùng, không ghi đè bằng dữ liệu cũ hơn.
- **H2 (NFR-04):** thời gian từ lúc process chạy lại tới lúc bắt kịp (`recovery_seconds`, README §4.2) có p95 < 60 giây.
- **H3 (đối chứng):** baseline (auto commit, INSERT thuần) trong cùng lần kill có mất dữ liệu **hoặc** trùng dữ liệu ở một tỷ lệ lần chạy khác 0. Nếu H3 sai, thực nghiệm không đủ nhạy để phân biệt hai cơ chế và phải tăng tải hoặc số lần chạy (§10).

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Loại lỗi (biến thể) | `kill-external`, `halt-<point>`, `db-outage` (§5) |
| Độc lập | Thời điểm lỗi trong cửa sổ | Ngẫu nhiên đều trong `[60 s, 540 s]` (seed của runner) |
| Độc lập | Cơ chế | `normal` và `baseline`, chạy **song song** trên cùng dữ liệu (README §5) |
| Phụ thuộc | Đúng đắn | `lost`, `duplicates`, `wrong_value`, `unexpected`, `latest_regressions` (README §4.1) |
| Phụ thuộc | Phục hồi | `recovery_seconds`, `kill_to_caught_up`, `start_to_ready`, số message được giao lại (`Δ pti_etl_records_total{outcome="duplicate"}`) |
| Kiểm soát | Tải | `rateMultiplier.gtfsRt = 2` (≈ 280 msg/s lúc 16:30), ticketing ×1 |
| Kiểm soát | Giờ nghiệp vụ | 13:00–19:00 CDT ngày thường (thứ Ba–thứ Sáu); `active_vehicles` ghi làm hiệp biến |
| Kiểm soát | Kịch bản khác | Không có (không `bad-data`, không `duplicates`) để mọi `duplicate` quan sát được đều do giao lại |
| Kiểm soát | Cấu hình ETL | Mặc định (DOC-29): `max.poll.records` 500, 3 consumer thread cho GTFS-rt |

## 3. Baseline (DR-27)

`etl-stream-baseline` chạy song song, chỉ GTFS-rt, với `offset-commit=auto`, `write-mode=insert`, `dedup=off`, `error-mode=fail-batch` (không tác động ở EXP-01 vì không có dữ liệu lỗi). Cơ chế được so sánh:

- **Auto commit** (commit mỗi giây, có thể trước khi ghi xong) so với commit sau transaction (ADR-0004): khả năng **mất** dữ liệu.
- **INSERT thuần** so với upsert (ADR-0003): khả năng **trùng** khi message bị giao lại.

Cả hai container bị kill **cùng một lệnh** (`docker kill` nhận nhiều tên), nên cùng một thời điểm với cùng dữ liệu.

## 4. Môi trường

Theo README §1: compose `core` + `experiment` (`make up-exp`), profile `observability` bật (tải ×2 nằm trong ngân sách). Toxiproxy chỉ tác động ở biến thể `db-outage`.

## 5. Các bước

```bash
uv run pti-exp run EXP-01 --variant kill-external --runs 30 --seed 1000
uv run pti-exp run EXP-01 --variant halt --runs 24 --seed 2000      # 4 points × 6 runs, order shuffled
uv run pti-exp run EXP-01 --variant db-outage --runs 10 --seed 3000
uv run pti-exp run EXP-01 --variant control --runs 5 --seed 4000     # no fault: validates the ledger comparison
uv run pti-exp analyze EXP-01
```

Mỗi lần chạy (theo vòng đời README §2.1):

1. Kiểm cửa sổ giờ nghiệp vụ (còn ≥ 25 phút trong khung), `TRUNCATE exp.exp_fact_vehicle_position, exp.exp_fact_trip_update`.
2. Silence các alert dự kiến trong 20 phút, matcher theo `alertname` (không theo service, vì các alert tổng hợp không mang nhãn service): `TargetDown`, `ConsumerStopped`, `ConsumerLagHigh`, `EndToEndLatencyHigh`, `ThroughputDrop`, `LatencyStageSlow`; biến thể `db-outage` thêm `CircuitBreakerOpen`, `DatabaseBottleneck`.
3. `PUT /sim/rate {"gtfsRt": 2, "ticketing": 1}`, chờ 60 giây ổn định. `t0` = thời điểm hết 60 giây đó.
4. Lấy mẫu mỗi 2 giây (lag đã commit của `pti-etl-gtfs-rt`, `pti-etl-ticketing`, `pti-exp-baseline`; trạng thái container; `pti_sim_tick_lag_seconds`).
5. Tiêm lỗi tại `t0 + u`, `u ~ U(60, 540)` giây:

   | Biến thể | Hành động |
   | --- | --- |
   | `kill-external` | `docker kill -s KILL pti-etl-stream-1 pti-etl-stream-baseline-1`; sau 5 giây `docker start` cả hai. Nếu Docker đã tự khởi động lại theo restart policy thì lệnh `start` không làm gì; `t_start` luôn lấy từ Docker event `start` |
   | `halt-<point>` | Trước bước 3, tạo lại **chỉ** `etl-stream` với `PTI_ETL_EXTRA_PROFILES=,experiment` (bật `ConfigurableFaultInjector`, DOC-19 §8; không bật chế độ baseline vì các key `pti.etl.baseline.*` giữ mặc định) và `SPRING_APPLICATION_JSON={"pti":{"test":{"fault":{"<point>":"halt","after-n":N}}}}`, `N` tính từ `u` và tốc độ poll đo được (runner ước lượng `N = round(u × polls_per_second)`). Tiến trình tự thoát với mã 137. Runner tạo lại container **không** có cấu hình lỗi ngay khi thấy exit. Baseline không bị tác động (không có hook) nên không có số đo đối chứng cho biến thể này |
   | `db-outage` | Toxiproxy: `POST /proxies/pg-warehouse {"enabled": false}` trong 30 giây rồi bật lại. Không kill tiến trình; đo khả năng chịu lỗi hạ tầng (retry, circuit breaker, pause container, DOC-20 §5) |
   | `control` | Không làm gì |

   `<point>` ∈ `before-process`, `before-write`, `after-write-before-commit`, `after-commit-before-ack` (các điểm có ở streaming, DOC-19 §8).
6. Tại `t1 = t0 + 600 s`: `docker pause` source-simulator (đóng cửa sổ), drain (README §2.1), rồi `docker unpause`.
7. Tính chỉ số (§6), xuất ledger, xóa silence, ghi `summary.json`.
8. Nghỉ 60 giây trước lần chạy kế tiếp; nhảy đồng hồ nếu cần (README §1.1).

Một lần chạy mất khoảng 15 phút. Cả EXP-01 (69 lần chạy) mất khoảng 17 giờ, chia làm nhiều chuỗi.

**Lần chạy `invalid`** (viết trước, README §6): drain không xong trong 10 phút; `pti_sim_tick_lag_seconds > 2` quá 10 giây liên tục; container khác ngoài mục tiêu bị restart; biến thể `halt` mà tiến trình không thoát trong cửa sổ (N quá lớn); lỗi của runner.

## 6. Chỉ số và công thức

Theo README §4.1–4.2, tính riêng cho `fact_vehicle_position`, `fact_trip_update` (cả normal và baseline) và `fact_ticket_sales` (chỉ normal; ground truth là `ticketing_source`).

Thêm cho EXP-01:

| Chỉ số | Công thức | Ý nghĩa |
| --- | --- | --- |
| `redelivered` | `Δ pti_etl_records_total{outcome="duplicate"}` trong cửa sổ (scrape runner, cộng dồn qua các lần khởi động vì counter reset về 0 khi process chạy lại) | Có giao lại thật, tức kill đã rơi vào giữa chunk. Nếu `redelivered = 0` ở một lần chạy `kill-external` thì kill rơi vào khoảng nghỉ giữa hai poll; lần chạy vẫn hợp lệ nhưng được đánh dấu `no_inflight` |
| `baseline_lost_rate`, `baseline_dup_rate` | README §4.1 trên `exp.*` | Cho H3 |
| `outage_to_caught_up` | Với `db-outage`: `t_caught_up − t_proxy_enabled` | Phục hồi sau lỗi DB |

## 7. Tiêu chí đạt

| # | Tiêu chí | Áp dụng |
| --- | --- | --- |
| C1 | Normal: `lost = 0`, `wrong_value = 0`, `unexpected = 0`, `latest_regressions = 0` ở **mọi** lần chạy hợp lệ, mọi bảng | Mọi biến thể |
| C2 | Normal: số dòng của mỗi business key = 1 (PK bảo đảm; kiểm lại để phát hiện lỗi định dạng key) | Mọi biến thể |
| C3 | p95 của `recovery_seconds` < 60 giây, và cận trên CI 95% bootstrap của p95 < 60 giây | `kill-external`, `halt` |
| C4 | `outage_to_caught_up` p95 < 60 giây; không có tiến trình nào bị restart | `db-outage` |
| C5 | `control`: `lost = 0` ở **cả** normal và baseline (xác nhận ledger và phép so sánh đúng) | `control` |
| C6 | Tỷ lệ lần chạy `kill-external` mà `redelivered > 0` ≥ 50% (lỗi thật sự rơi giữa chunk đủ thường) | `kill-external` |

H3 không phải tiêu chí đạt của hệ thống mà là kiểm tra độ nhạy; kết quả H3 được báo cáo dù đúng hay sai.

## 8. Phân tích

`pti_exp/experiments/exp01.py` (`analyze`):

- Bảng C1/C2 theo biến thể và bảng; cận trên rule of three (README §6).
- Biểu đồ: phân phối `recovery_seconds` (hộp theo biến thể); lag theo thời gian quanh `t_kill` (một dòng mỗi lần chạy, căn theo `t_kill`); `baseline_lost_rate` so với vị trí kill trong chu kỳ auto commit (`(t_kill − t_last_commit) mod 1 s`, nếu đo được từ log baseline).
- Wilcoxon ghép cặp `lost_normal` và `lost_baseline` (một phía, H3).

## 9. Mẫu bảng kết quả

| Biến thể | n (hợp lệ / invalid) | Key đã kiểm | Normal mất / sai / trùng | Baseline: lần chạy có mất | Baseline: lần chạy có trùng | `recovery_seconds` trung vị [CI] | p95 [CI] | max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `kill-external` | 30 / 0 | 2.750.000 | 0 / 0 / 0 | 21/30 | 9/30 | 18,2 s [16,9; 19,5] | 27,4 s [24,1; 33,0] | 35,1 s |
| `halt-before-write` | 6 / 0 | … | 0 / 0 / 0 | — | — | … | … | … |
| … | | | | | | | | |

(Số liệu trong bảng là ví dụ định dạng, không phải kết quả.)

| Chỉ số phụ | Giá trị |
| --- | --- |
| Cận trên 95% xác suất một lần chạy normal có mất dữ liệu | 3/n = 10% (n = 30) |
| Tổng `redelivered` / số lần chạy có `redelivered > 0` | … |
| Wilcoxon `lost_baseline > lost_normal` | W = …, p = … |

## 10. Mối đe dọa tới tính hợp lệ

Ngoài README §8:

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Kill rơi ngoài chunk nên không kiểm được đường giao lại | Biến thể `halt` bảo đảm lỗi rơi đúng từng điểm; C6 đo tỷ lệ của `kill-external` |
| `docker kill` khác `kill -9` của K8s (OOMKilled, node chết) | EXP-08 lặp lại trên k3d bằng Chaos Mesh `PodChaos` |
| Baseline chưa kịp commit lần nào trước khi kill (mất ít) hoặc commit đúng lúc (không mất), khiến H3 dao động | Báo cáo tỷ lệ và phân phối, không chỉ trung bình; tăng tải lên ×5 cho một chuỗi phụ nếu H3 < 20% lần chạy |
| Thời gian khởi động JVM phụ thuộc cache hệ điều hành | Báo cáo tách `start_to_ready` và `ready_to_caught_up` |
| Kill cùng lúc hai container làm DB bớt tải và phục hồi nhanh hơn thực tế | Chấp nhận; ghi chú trong báo cáo |

## 11. Kết quả

Điền sau P3-10 (`pti-exp report`). Kết quả chuỗi smoke (DR-95) không ghi vào đây.

## 12. Câu hỏi còn mở

Không có.
