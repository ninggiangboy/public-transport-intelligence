# RB-07: Độ trễ cao, thông lượng giảm, simulator chậm

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-07
>
> Alert: `EndToEndLatencyHigh` (warning, 5 phút; từ P4-16), `LatencyStageSlow` (info, 5 phút), `ThroughputDrop` (warning, 10 phút), `SimulatorLagging` (warning, 2 phút) · Dashboard: `pti-overview` (bốn chặng), `pti-simulator`, `pti-jvm` · Liên quan: DOC-10 §2 (ngân sách độ trễ), DR-57, DR-68, DOC-25 §6.5

## Triệu chứng và ảnh hưởng

- `EndToEndLatencyHigh`: p95 `pti_end_to_end_latency_seconds` > 10 giây (vi phạm NFR-03). Bản đồ và cảnh báo trên UI tới muộn.
- `LatencyStageSlow` (info): một chặng vượt ngưỡng của nó (`kafka_to_commit` > 3 s, `commit_to_publish` > 2,5 s, `publish_to_emit` > 1,5 s). Thường xuất hiện trước `EndToEndLatencyHigh`, chỉ ra chặng chậm.
- `ThroughputDrop`: tốc độ vào ETL của một nguồn GTFS-rt giảm dưới 50% trung bình 1 giờ trước đó. Nguồn đang yếu dần hoặc ETL đọc chậm lại.
- `SimulatorLagging`: `pti_sim_tick_lag_seconds > 2`, simulator không theo kịp đồng hồ nghiệp vụ. Dữ liệu vẫn đúng nhưng tới trễ và thưa hơn (vòng phát bỏ lượt, DR-68). Kết quả thực nghiệm đo trong lúc này **không hợp lệ** (DOC-45 §8).

## Kiểm tra

1. **Chặng nào chậm?** `pti-overview` → panel bốn chặng: `pti:kafka_to_commit:p95_5m` (theo `source`), `pti:commit_to_publish:p95_5m`, `pti:publish_to_emit:p95_5m`, `pti:e2e_latency:p95_5m`. So với ngân sách DOC-10 §2.
2. **Theo chặng:**

   | Chặng chậm | Kiểm tra |
   | --- | --- |
   | `kafka_to_commit` | Lag (RB-02); `pti:chunk_duration:p95_5m` > 1 s → DB (RB-08); CPU và GC của `etl-stream` (`pti-jvm`) |
   | `commit_to_publish` | Executor analytics đầy: `executor_queued_tasks{application="etl-stream"}` gần `pti.etl.analytics.executor.queue-capacity` (1.000), `pti_analytics_dropped_total` tăng. Detector chạy lâu: span `pti.analytics.run` trong Tempo, lọc theo tag `detector` |
   | `publish_to_emit` | Consumer `pti.events.ui` của `api` bị lag (`kafka_consumer_fetch_manager_records_lag{client_id=~"api-.*"}`); số kết nối SSE (`pti_api_sse_connections`) và CPU `api` |
   | Tổng cao nhưng các chặng bình thường | Đồng hồ các container lệch nhau (không xảy ra trên một máy compose; trên k3d nhiều node, kiểm tra NTP) |

3. **Thông lượng giảm:** so `pti_sim_messages_sent_total` (nguồn phát) với `pti:etl_input:rate5m` (ETL đọc).
   - Nguồn phát giảm theo → nguyên nhân ở nguồn: giờ thấp điểm, kịch bản `load-ramp` kết thúc, `make sim-rate` bị đặt thấp, simulator chậm (bước 4).
   - Nguồn phát bình thường nhưng ETL đọc ít → ETL chậm hoặc pause (RB-02, RB-03); lag sẽ tăng.
   - Giảm vào đầu giờ phục vụ ban đêm (sau 23:00 giờ nghiệp vụ) và lag không tăng → báo nhầm theo khung giờ (DOC-28 §6.3 ghi chú 5).
4. **Simulator chậm:**
   - CPU: `process_cpu_usage{application="source-simulator"}` gần limit (`docker stats`).
   - Backpressure của ledger: `pti_sim_ledger_queue_depth` gần 50.000 và `pti_sim_emissions_skipped_total{reason="backpressure"}` tăng → `pg-source` ghi chậm (RB-08, áp dụng cho `pg-source`).
   - Kafka chậm: `pti_sim_emissions_skipped_total{reason="send_timeout"}`.
   - GC: `jvm_gc_pause_seconds` p99 > 0,5 s.
   - Hệ số tải: `pti_sim_rate_multiplier` cao hơn mức máy chịu được (EXP-05 cho biết ngưỡng).

## Xử lý

| Nguyên nhân | Việc |
| --- | --- |
| DB chậm | RB-08 |
| ETL thiếu CPU | Giảm tải, hoặc tăng CPU limit (DOC-39 §3.2). k3d: kiểm tra KEDA đã scale (DOC-40) |
| Executor analytics đầy | Tìm detector chậm (span `pti.analytics.run`), xem câu SQL chậm của nó trong log Postgres (RB-08, Kiểm tra bước 3). Tạm thời tăng `pti.etl.analytics.executor.threads` (mặc định 2) rồi `make up`. Sự kiện bị bỏ khi queue đầy không làm mất dữ liệu warehouse, chỉ làm insight trễ một chu kỳ (DR-35) |
| Consumer `pti.events.ui` của `api` lag | Restart không giúp nếu CPU đầy; giảm số client SSE thử nghiệm, hoặc thêm replica `api` (k3d) |
| Tải vào giảm có chủ đích hoặc theo giờ | Không làm gì. Báo nhầm lặp lại theo giờ: silence `ThroughputDrop` cho khung 23:00–05:00 giờ nghiệp vụ, comment `night ramp-down` |
| Simulator thiếu CPU hoặc hệ số tải quá cao | `make sim-rate GTFS=1`; đang chạy thực nghiệm thì đánh dấu lần chạy không hợp lệ và chạy lại |
| `pg-source` chậm (ledger) | RB-08 cho `pg-source`: `make psql-sim` → `pg_stat_activity`; autovacuum trên partition ledger |

Không tăng `max.poll.records` hay kích thước chunk để giảm lag: chunk lớn làm tăng chặng 2 và thời gian khóa (DOC-20 §2).

## Trên k3d

- CPU và RAM theo pod: `kubectl top pod -n pti`; giới hạn ở DOC-40 §5.3. `SimulatorLagging` trên `lite` thường do node thiếu CPU khi `etl-stream` đã scale lên 4 pod; kiểm tra bằng `kubectl top node`.
- Thông lượng giảm khi pod `etl-stream` vừa scale (rebalance) là bình thường trong khoảng 30 giây; nếu kéo dài, xem `kafka_consumer_coordinator_rebalance_total` và RB-02 mục "Trên k3d".
- `make sim-rate`, `psql-sim` thêm `PTI_ENV=k3d`. Tăng tài nguyên: sửa values rồi `make k8s-apply`.

## Xác nhận đã xong

- `pti:e2e_latency:p95_5m < 10` và mọi chặng dưới ngưỡng `LatencyStageSlow` trong 10 phút.
- `pti:etl_input:rate5m` của GTFS-rt trở về mức của cùng giờ ngày trước (dashboard `pti-overview`, so sánh `offset 1d`).
- `pti_sim_tick_lag_seconds < 0.5` ổn định.

## Phòng ngừa và việc sau sự cố

- Độ trễ vượt ngưỡng ở tải nền là regression hiệu năng: chạy lại EXP-05 (series `end-to-end`) và so với kết quả đã ghi.
- Ghi con số của bốn chặng vào issue để về sau so sánh được.
