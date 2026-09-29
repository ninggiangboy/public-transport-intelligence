# EXP-05: Ngưỡng tải còn đạt NFR-03 trên compose

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 (DR-95) · DOC-45 / EXP-05
>
> Phụ thuộc: [protocol chung](README.md), DOC-10 §2 (ngân sách độ trễ), §3.1 (khối lượng), §5 (tài nguyên), DOC-25 §6.5, §7.8 (`load-ramp`), DOC-28 §3, §5.1, DR-57, DR-68, DR-71
>
> Người dùng chính: P3-07, P3-08 (smoke), P3-10 (loạt `etl-only` và `end-to-end`); báo cáo chương đánh giá; EXP-07 (so sánh với k3d)

## 1. Giả thuyết và câu hỏi

- **H1 (NFR-03):** ở tải nền (×1, cao điểm chiều), p95 `pti_end_to_end_latency_seconds{channel="vehicles"}` < 10 giây trên compose.
- **Q1:** mức tải cao nhất (bội số của tải nền) mà hệ thống trên một máy 16 GB vẫn giữ được p95 < 10 giây và lag không tăng liên tục là bao nhiêu?
- **Q2:** tài nguyên nào bão hòa trước (CPU `etl-stream`, CPU hoặc IO `pg-warehouse`, Kafka, simulator)? Kết quả này là đầu vào cho EXP-07 (mở rộng trên k3d).

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Bội số tải GTFS-rt | Bậc `[1, 2, 3, 5, 7, 10]`, mỗi bậc 5 phút, không ramp-down |
| Phụ thuộc | Độ trễ | p50/p95/p99 end-to-end (loạt `end-to-end`) hoặc `pti_etl_kafka_to_commit_seconds` (loạt `etl-only`); p95 từng chặng DR-71 |
| Phụ thuộc | Thông lượng | `throughput_in`, `throughput_out` (msg/s), `lag_end`, `lag_slope` (README §4.4) |
| Phụ thuộc | Tài nguyên | CPU và RAM từng container (`docker stats` mỗi 5 giây), `hikaricp_connections_pending`, `pg_stat_database.xact_commit` rate, tỷ lệ bậc có circuit breaker mở |
| Kiểm soát | Giờ nghiệp vụ | Bắt đầu mỗi lần chạy trong 15:30–17:30 CDT ngày thường (≥ 500 xe đang chạy); `active_vehicles` ghi theo bậc |
| Kiểm soát | Ticketing | ×1 (`includeTicketing = false`) |
| Kiểm soát | Kịch bản khác | Không |
| Kiểm soát | Tracing | `management.tracing.sampling.probability = 0.1` cho `etl-*` và `api` (DOC-28 §5.1) |
| Kiểm soát | Cấu hình ETL | Mặc định (DOC-29); không chỉnh tay giữa các lần chạy |

## 3. Baseline

Không chạy baseline DR-27. Baseline đo cơ chế đúng đắn (commit, upsert, phân loại lỗi), không phải hiệu năng. Chạy song song nó sẽ nhân đôi tải ghi DB và làm sai lệch chính phép đo độ trễ. Mốc so sánh của EXP-05 là ngân sách độ trễ DOC-10 §2 và, sau P7, kết quả EXP-07 trên k3d.

## 4. Môi trường

- `make up-obs` (profile `core` + `observability`). **Không** bật profile `experiment`: không có `etl-stream-baseline`, và `etl-stream` nối thẳng `pg-warehouse` không qua Toxiproxy.
- Máy thực nghiệm theo README §1.2 (16 GB RAM, CPU không chia sẻ); Docker có ≥ 12 GB (DOC-10 §5.1 điểm 2). Không chạy ứng dụng khác trên máy.
- Runner scrape trực tiếp `/actuator/prometheus` (README §4.4). Prometheus và Grafana chỉ để quan sát.

## 5. Các bước

Hai loạt, cùng quy trình, khác chỉ số chính:

| Loạt | Khi nào | Chỉ số độ trễ chính | Ngưỡng |
| --- | --- | --- | --- |
| `etl-only` | P3-10; bản rút gọn trong chuỗi smoke (README §1.3) | p95 `pti_etl_kafka_to_commit_seconds{source=~"GTFS_RT_.*"}` | 3 giây (ngưỡng `LatencyStageSlow` cho chặng 1–2, DOC-10 §2) |
| `end-to-end` | P3-10 (cần P4-14) | p95 `pti_end_to_end_latency_seconds{channel="vehicles"}`; báo cáo thêm kênh `alerts` | 10 giây (NFR-03) |

```bash
uv run pti-exp run EXP-05 --series etl-only --runs 10 --seed 9000
uv run pti-exp run EXP-05 --series end-to-end --runs 10 --seed 9100
uv run pti-exp analyze EXP-05
```

Một lần chạy (khoảng 35 phút):

1. Kiểm cửa sổ giờ (bắt đầu trong 15:30–17:30 CDT ngày thường, nếu không thì nhảy đồng hồ, README §1.1). `PUT /sim/rate {"gtfsRt": 1, "ticketing": 1}`, chờ 3 phút ổn định.
2. Loạt `end-to-end`: mở 10 kết nối SSE (README §2) và giữ tới hết lần chạy.
3. Silence 40 phút: `ConsumerLagHigh`, `EndToEndLatencyHigh`, `LatencyStageSlow`, `ThroughputDrop`, `DatabaseBottleneck`, `SimulatorLagging` (đây chính là những gì thực nghiệm muốn gây ra; chúng vẫn được ghi lại trong `summary.json`).
4. `POST /sim/scenarios/load-ramp {"steps": [1, 2, 3, 5, 7, 10], "stepDuration": "PT5M", "rampDown": false, "includeTicketing": false}`. `t_step[i]` = thời điểm `pti_sim_rate_multiplier{stream="gtfsRt"}` đổi sang bậc `i`.
5. Lấy mẫu: lag mỗi 2 giây; scrape metric mỗi 5 giây; `docker stats --no-stream` mỗi 5 giây; `pg_stat_database` và `pg_stat_activity` (bằng `experiment_runner`) mỗi 10 giây.
6. Kịch bản kết thúc: tốc độ trở về ×1. Chờ lag về mức trước khi bắt đầu (tối đa 10 phút) và ghi `drain_seconds`.
7. Tính chỉ số theo bậc (§6), ghi `summary.json`.
8. Nghỉ 5 phút ở ×1 trước lần chạy kế tiếp.

**Bậc `invalid`** (không làm cả lần chạy `invalid`): `pti_sim_tick_lag_seconds > 2` quá 10% thời gian đo của bậc, hoặc `throughput_in < 0,9 × kỳ vọng` (kỳ vọng = tốc độ đo ở bậc ×1 nhân bội số, hiệu chỉnh theo `active_vehicles`). Nghĩa là simulator không sinh đủ tải và bậc đó không nói gì về pipeline. Các bậc cao hơn một bậc `invalid` cũng bị bỏ.

## 6. Chỉ số và công thức

Mỗi bậc đo trên đoạn `[t_step[i] + 60 s, t_step[i+1])` (bỏ 60 giây chuyển tiếp):

| Chỉ số | Công thức |
| --- | --- |
| `latency_p50/p95/p99` | Từ hiệu bucket histogram giữa đầu và cuối đoạn (README §4.4) |
| `stage_p95` | p95 của `pti_etl_kafka_to_commit_seconds`, `pti_ui_commit_to_publish_seconds`, `pti_api_publish_to_emit_seconds` (DR-71) |
| `throughput_in`, `throughput_out`, `lag_end`, `lag_slope` | README §4.4 |
| `cpu[c]`, `mem[c]` | Trung bình và max của `docker stats` cho mỗi container `c`, CPU tính theo nhân (1,0 = một nhân) |
| `cpu_util[c]` | `cpu[c] / cpu_limit[c]` (limit ở DOC-10 §5) |
| `db_tps` | Tốc độ `xact_commit` của `pti_warehouse` |
| `pool_wait` | Max `hikaricp_connections_pending{application="etl-stream"}` |
| `step_pass` | `latency_p95 < ngưỡng` của loạt **và** `lag_slope ≤ 0,02 × throughput_in` **và** không có process restart **và** circuit breaker không mở |
| `threshold_multiplier` | Bậc cao nhất `m` sao cho mọi bậc ≤ `m` đều `step_pass` và hợp lệ. Nếu mọi bậc đều đạt thì ghi `≥ 10` |
| `threshold_msgs` | `throughput_in` tại bậc `threshold_multiplier` (msg/s), số tuyệt đối để so với EXP-07 |
| `bottleneck` | Container có `cpu_util` cao nhất ở bậc đầu tiên không đạt (hoặc bậc cuối nếu đạt hết); kèm `pool_wait` và `db_tps` để phân biệt nghẽn ở ETL hay DB |

## 7. Tiêu chí đạt

| # | Tiêu chí | Loạt |
| --- | --- | --- |
| C1 | Bậc ×1 đạt `step_pass` ở mọi lần chạy (H1; NFR-03 cho loạt `end-to-end`) | cả hai |
| C2 | `threshold_multiplier` xác định được ở ≥ 8/10 lần chạy (các bậc tới ngưỡng đều hợp lệ) | cả hai |
| C3 | Không mất dữ liệu dưới tải: `lost = 0` với ledger của cả lần chạy (README §4.1), kể cả ở các bậc không đạt độ trễ | cả hai |
| C4 | Sau khi về ×1, lag về mức ban đầu trong ≤ 10 phút (`drain_seconds`) | cả hai |

Tiêu chí nghiệm thu P3-10 ("EXP-05 xác định được ngưỡng tải") = C1 và C2 của loạt `etl-only` (DR-95; trước đây là tiêu chí thoát M3). Chuỗi smoke chỉ kiểm C3. NFR-03 chính thức = C1 của loạt `end-to-end`.

## 8. Phân tích

- Biểu đồ chính: p95 độ trễ theo bội số tải (trung vị qua 10 lần chạy, dải CI bootstrap), đường ngang ở ngưỡng. Loạt `end-to-end` vẽ chồng bốn chặng DR-71.
- `throughput_out` theo `throughput_in` (đường chéo = theo kịp), đánh dấu bậc bắt đầu lệch.
- Heatmap `cpu_util` theo container × bậc để trả lời Q2.
- Phân phối `threshold_multiplier` qua các lần chạy (cột tần suất).
- Hồi quy: `latency_p95` theo `throughput_in` và `active_vehicles`, để tách ảnh hưởng của số xe (kích thước poll) khỏi bội số.

## 9. Mẫu bảng kết quả

| Bậc | `throughput_in` (msg/s) | p50 / p95 / p99 (s) | `lag_slope` | CPU `etl-stream` / `pg-warehouse` / `kafka` | `step_pass` (số lần chạy) |
| --- | --- | --- | --- | --- | --- |
| ×1 | … | … / … / … | … | … / … / … | 10/10 |
| ×2 | | | | | |
| ×3 | | | | | |
| ×5 | | | | | |
| ×7 | | | | | |
| ×10 | | | | | |

| Chỉ số | `etl-only` | `end-to-end` |
| --- | --- | --- |
| `threshold_multiplier` trung vị (min–max) | … | … |
| `threshold_msgs` trung vị | … | … |
| Tài nguyên nghẽn trước (số lần chạy) | … | … |
| `drain_seconds` trung vị | … | … |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Simulator và pipeline cùng máy, tranh CPU; ở bậc cao simulator có thể là thứ nghẽn | Tiêu chí bậc `invalid` (§5); báo cáo CPU của simulator |
| Kích thước bảng tăng theo thời gian (partition ngày nghiệp vụ lớn dần) làm các lần chạy sau chậm hơn | Mỗi lần chạy thường sang ngày nghiệp vụ mới (partition mới); kiểm xu hướng theo thứ tự lần chạy, báo cáo nếu có |
| Bậc 5 phút có thể chưa đủ để thấy lag tăng chậm | `lag_slope` thay vì `lag_end`; một lần chạy phụ với bậc 15 phút quanh ngưỡng tìm được |
| Tracing 0,1 và profile `observability` tốn tài nguyên | Giữ cố định trong mọi lần chạy; DOC-28 §5.1 |
| Ngưỡng tải phụ thuộc máy thực nghiệm (số nhân, Docker chạy thẳng trên Linux hay trong VM) | Ghi cấu hình máy trong `config.json` và báo cáo ngưỡng kèm cấu hình; mọi lần chạy trên cùng một máy (README §1.2); EXP-07 trên k3d là phép đo thứ hai |
| Tải chỉ tăng GTFS-rt, không tăng ticketing | Đúng phạm vi NFR-08 (DOC-10 §3.1); ticketing ×1 là hằng số |

## 11. Kết quả

Cả hai loạt: điền sau P3-10. Kết quả chuỗi smoke (DR-95) không ghi vào đây.

## 12. Câu hỏi còn mở

Không có.
