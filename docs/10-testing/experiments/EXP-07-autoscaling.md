# EXP-07: Mở rộng theo tải trên k3d

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-45 / EXP-07
>
> Phụ thuộc: [protocol chung](README.md), [EXP-05](EXP-05-load.md), [DOC-40](../../09-operations/deploy-k8s.md) §2, §5.3, §9, §13, DOC-10 §2–4, DOC-20 §1, §7, DOC-25 §7.8 (`load-ramp`), DOC-28 §3, §6 (alert #7), ADR-0028, DR-74
>
> Người dùng chính: P7-06, P7-09, P7-10; báo cáo chương đánh giá; DOC-46 bước 7

## 1. Giả thuyết và câu hỏi

- **H1 (NFR-08):** trên k3d `lite`, với KEDA bật (`etl-stream` 1→4 pod), mọi bậc tải từ ×1 tới ×10 đều đạt `step_pass` (p95 end-to-end < 10 giây, lag không tăng liên tục) sau khi scale xong.
- **H2 (cơ chế scale):** khi lag vượt ngưỡng, KEDA thêm pod và pod mới nhận partition trong ≤ 120 giây; khi tải về ×1, số pod về 1 trong ≤ 20 phút; rebalance trong lúc scale lên và xuống không làm mất, không làm trùng dữ liệu.
- **H3 (chặn khi DB là nút thắt, SDD §12.7):** khi độ trễ ghi DB vượt ngưỡng (`pti:chunk_duration:p95_5m > 2`), KEDA **không** tăng thêm pod dù lag tăng, và alert `DatabaseBottleneck` bắn.
- **Q1:** scale ngang giúp được bao nhiêu trên một máy? So sánh ngưỡng tải của `autoscale` với `fixed-1` (một pod) và `fixed-4` (bốn pod cố định), và với `threshold_msgs` của EXP-05 trên compose.
- **Q2:** hiệu suất scale `E(n) = throughput_out(n) / (n × throughput_out(1))` là bao nhiêu, và tài nguyên nào bão hòa trước (CPU của VM, `pti-warehouse`, Kafka)?

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Bội số tải GTFS-rt | Bậc `[1, 2, 3, 5, 7, 10]`, mỗi bậc 10 phút (dài hơn EXP-05 để có thời gian scale), rồi về ×1 trong 25 phút |
| Độc lập | Chế độ scale (biến thể) | `autoscale`, `fixed-1`, `fixed-4`, `db-slow` (§5) |
| Phụ thuộc | Độ trễ | p50/p95/p99 `pti_end_to_end_latency_seconds{channel="vehicles"}` theo bậc; p95 từng chặng DR-71 |
| Phụ thuộc | Thông lượng | `throughput_in`, `throughput_out`, `lag_end`, `lag_slope` (README §4.4) |
| Phụ thuộc | Scale | Số pod `etl-stream` sẵn sàng và mong muốn theo thời gian; `scale_reaction_seconds`; `scale_down_seconds`; số rebalance |
| Phụ thuộc | Tài nguyên | CPU và RAM từng pod (cAdvisor), CPU của VM, số kết nối DB theo user, OOMKilled và eviction |
| Phụ thuộc | Đúng đắn | `lost`, `wrong_value`, `unexpected`, `latest_regressions` (README §4.1) trên cả lần chạy |
| Kiểm soát | Môi trường | k3d `lite` (DOC-40 §2), compose tắt, không chạy ứng dụng khác |
| Kiểm soát | Giờ nghiệp vụ | Bắt đầu mỗi lần chạy trong 15:00–16:00 CDT ngày thường, để cả chuỗi bậc rơi vào giờ cao điểm chiều; `active_vehicles` ghi theo bậc |
| Kiểm soát | Ticketing | ×1 (`includeTicketing = false`) |
| Kiểm soát | `api` | HPA 2→4 như `lite`; chỉ ghi nhận số pod, không phải biến được kiểm định (§10) |
| Kiểm soát | `triage-worker` | `provider=fake` trong EXP-07 (`--set apps.triage-worker.provider=fake`), để Jev không tham gia |
| Kiểm soát | Tracing | Tắt (`lite`) |

## 3. Baseline

Không chạy baseline DR-27 (lý do như EXP-05 §3). Các mốc so sánh:

- `fixed-1`: cùng cluster, KEDA tạm dừng ở 1 pod. Trả lời "không scale thì chịu được tới đâu".
- `fixed-4`: KEDA tạm dừng ở 4 pod (số pod tối đa có ích, DOC-40 §9.1). Là cận trên của thông lượng mà `autoscale` có thể đạt.
- EXP-05 (compose, một tiến trình `etl-stream`): so bằng `threshold_msgs` (số tuyệt đối), không so bội số vì `active_vehicles` khác nhau.

## 4. Môi trường

- `make k8s-up ENV=lite` từ cluster trống, `make k8s-smoke` pass, rồi ấm máy 10 phút ở ×1 trước lần chạy đầu tiên của chuỗi.
- Runner chạy trên host, dùng `env/k3d.py` (README §2):
  - Kafka lag: `kubectl exec pti-dual-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group pti-etl-gtfs-rt` mỗi 2 giây. Nếu pod `pti-dual-0` không chạy thì dùng broker khác.
  - Metric: Prometheus trong cluster qua `http://localhost:9090` (query API), vì tập pod thay đổi trong lần chạy nên không scrape trực tiếp từng pod như README §4.4. Scrape interval 15 giây; phân vị tính từ `increase(..._bucket[<đoạn>])` cộng mọi pod.
  - DB: `kubectl port-forward svc/pti-warehouse-rw 15432:5432` và `svc/pti-source-rw 15433:5432` với role `experiment_runner` (Secret `pti-db-experiment-runner`).
  - Simulator: `http://localhost:8084`. SSE: `http://localhost:8080/api/v1/stream` (5 kênh `vehicles` công khai, 5 kênh `alerts` với token của `make k3d-token ROLE=operator TTL=3h`).
  - Alertmanager silence: `http://localhost:9093`.
  - Đồng hồ nghiệp vụ: runner đặt `global.clockOffset` bằng `helmfile -e lite apply --set` rồi chờ rollout (quy tắc "không lùi đồng hồ" của README §1.1 giữ nguyên).
- Biến thể dùng annotation của KEDA để tạm dừng scale: `kubectl annotate scaledobject etl-stream autoscaling.keda.sh/paused-replicas=<n> --overwrite`; xóa annotation để bật lại.

## 5. Các bước

```bash
uv run pti-exp run EXP-07 --variant autoscale --runs 5 --seed 7000
uv run pti-exp run EXP-07 --variant fixed-1   --runs 3 --seed 7100
uv run pti-exp run EXP-07 --variant fixed-4   --runs 3 --seed 7200
uv run pti-exp run EXP-07 --variant db-slow   --runs 5 --seed 7300
uv run pti-exp analyze EXP-07
```

Một lần chạy (khoảng 95 phút):

1. Kiểm cửa sổ giờ; kiểm `etl-stream` đang ở 1 pod (`autoscale`, `db-slow`) hoặc đặt `paused-replicas` (`fixed-*`) và chờ số pod ổn định 2 phút. `PUT /sim/rate {"gtfsRt": 1, "ticketing": 1}`, chờ 3 phút.
2. Mở 10 kết nối SSE và giữ tới hết lần chạy.
3. Silence 100 phút: `ConsumerLagHigh`, `EndToEndLatencyHigh`, `LatencyStageSlow`, `ThroughputDrop`, `SimulatorLagging`, `TargetDown` (pod bị xóa khi scale xuống). `DatabaseBottleneck` **không** bị silence ở `db-slow` vì là biến quan sát của H3; ở biến thể khác thì silence.
4. `POST /sim/scenarios/load-ramp {"steps": [1, 2, 3, 5, 7, 10], "stepDuration": "PT10M", "rampDown": false, "includeTicketing": false}`. `t_step[i]` như EXP-05.
5. Riêng `db-slow`: tại `t_step[3]` (đầu bậc ×5) áp `chaos/pg-network-delay.yaml` với `duration: 20m` và độ trễ 150 ms ± 30 ms. Độ trễ được chọn ở P7-10 bằng một lần chạy thử sao cho `pti:chunk_duration:p95_5m` vượt 2 giây trong khi lag tăng. Giá trị cuối cùng ghi vào `config.json` và §11.
6. Lấy mẫu:
   - mỗi 2 giây: lag đã commit;
   - mỗi 15 giây (Prometheus): số pod `kube_deployment_status_replicas_available{deployment="etl-stream"}`, số pod mong muốn `kube_horizontalpodautoscaler_status_desired_replicas{horizontalpodautoscaler="keda-hpa-etl-stream"}`, giá trị trigger `keda_scaler_metrics_value{scaledObject="etl-stream"}`, `pti:chunk_duration:p95_5m`, CPU và RAM từng pod, `hikaricp_connections_pending`, `kafka_consumer_coordinator_rebalance_total`, số pod `api`;
   - mỗi 10 giây: `pg_stat_activity` nhóm theo `usename` trên cả hai instance.
7. Khi kịch bản kết thúc (`t_end`, sau bậc ×10), tốc độ về ×1. Chờ 25 phút để quan sát drain và scale xuống. `t1 = t_end + 25 min`; drain và pause simulator như README §2.1.
8. Tính chỉ số (§6), so ledger trên cả cửa sổ `[t0, t1]`, ghi `summary.json`, xóa silence và chaos CR, xóa annotation `paused-replicas`.
9. Nghỉ 10 phút ở ×1 trước lần chạy kế tiếp (số pod về 1).

**Bậc `invalid`:** như EXP-05 §5 (simulator tụt hậu hoặc `throughput_in < 0,9 × kỳ vọng`).

**Lần chạy `invalid`:** node k3d `NotReady`; pod hạ tầng (Kafka, Postgres, Prometheus) restart ngoài kế hoạch; drain không xong trong 10 phút sau `t1`; lỗi runner. Pod `etl-stream` bị OOMKilled hoặc bị evict **không** làm lần chạy `invalid`: đó là kết quả, tính vào C5.

## 6. Chỉ số và công thức

Mỗi bậc đo trên đoạn `[t_step[i] + 180 s, t_step[i+1])`: bỏ 3 phút đầu cho scale và rebalance. Ba phút đầu được báo cáo riêng là `transient_p95`.

| Chỉ số | Công thức |
| --- | --- |
| `latency_p50/p95/p99`, `stage_p95` | Như EXP-05 §6, tính từ `increase` của histogram trên Prometheus, cộng mọi pod |
| `transient_p95` | p95 trên `[t_step[i], t_step[i] + 180 s)` |
| `throughput_in`, `throughput_out`, `lag_end`, `lag_slope` | README §4.4 |
| `step_pass` | Như EXP-05 §6 (ngưỡng 10 giây của NFR-03) |
| `threshold_multiplier`, `threshold_msgs` | Như EXP-05 §6 |
| `replicas_avg[i]`, `replicas_max[i]` | Trung bình và max số pod sẵn sàng trong đoạn đo của bậc `i` |
| `scale_reaction_seconds` | Với mỗi lần số pod mong muốn tăng từ `n` lên `n+1`: `t(available = n+1) − t(desired = n+1)`. Báo cáo thêm `trigger_to_desired` = `t(desired = n+1) − t(trigger > n × 1500 lần đầu)` |
| `partition_spread` | Sau mỗi lần scale ổn định: số partition `gtfs.*` lớn nhất và nhỏ nhất mà một pod giữ (từ `kafka-consumer-groups --describe --members --verbose`) |
| `rebalances` | Tổng tăng của `kafka_consumer_coordinator_rebalance_total` (group `pti-etl-gtfs-rt`) |
| `scale_down_seconds` | `t(available = 1) − t_end` |
| `efficiency[n]` | `throughput_out` trung bình khi `n` pod và lag ổn định, chia `n × throughput_out` của bậc ×1 ở `fixed-1`. Chỉ tính cho các bậc mà `fixed-1` đã bão hòa |
| `db_conn_max[user]` | Max số kết nối của từng user trên primary và replica |
| `oom_or_evicted` | Số lần `kube_pod_container_status_last_terminated_reason{reason="OOMKilled"}` tăng cộng số pod `Evicted` trong namespace `pti` |
| `vm_cpu_util` | Tổng CPU mọi node / số nhân của VM |
| `hold_violations` (`db-slow`) | Số lần số pod mong muốn **tăng** trong khoảng `pti:chunk_duration:p95_5m > 2` (sau 30 giây trễ của vòng polling KEDA) |
| `bottleneck_alert_delay` (`db-slow`) | `t(DatabaseBottleneck firing) − t(điều kiện của alert đúng lần đầu)` |

## 7. Tiêu chí đạt

| # | Tiêu chí | Biến thể |
| --- | --- | --- |
| C1 | Mọi bậc ×1…×10 hợp lệ đạt `step_pass` ở ≥ 4/5 lần chạy (H1, NFR-08) | `autoscale` |
| C2 | `lost = 0`, `wrong_value = 0`, `unexpected = 0`, `latest_regressions = 0` ở mọi lần chạy hợp lệ, kể cả lúc scale lên và xuống | Mọi biến thể |
| C3 | p95 của `scale_reaction_seconds` ≤ 120 giây; `scale_down_seconds` ≤ 20 phút ở mọi lần chạy | `autoscale` |
| C4 | `hold_violations = 0` và `DatabaseBottleneck` bắn trong ≤ `for` (5 phút) + 2 phút sau khi điều kiện đúng, ở mọi lần chạy (H3) | `db-slow` |
| C5 | `oom_or_evicted = 0`; `db_conn_max` không vượt bảng DOC-40 §9.4 | Mọi biến thể |

Nếu `fixed-1` cũng đạt ×10 thì H1 vẫn đúng, nhưng Q1 kết luận rằng ở quy mô dữ liệu này scale ngang không cần thiết. Kết luận đó được báo cáo, không coi là thất bại.

Nếu C1 không đạt vì CPU của VM bão hòa (`vm_cpu_util > 0,9` ở bậc đầu tiên không đạt), báo cáo NFR-08 là "không đạt do giới hạn phần cứng một máy" kèm `threshold_multiplier` của `autoscale` so với `fixed-1`. Đây là cách đọc "trong giới hạn tài nguyên máy" của NFR-08.

## 8. Phân tích

`pti_exp/experiments/exp07.py` (`analyze`):

- Biểu đồ chính: theo thời gian (một lần chạy điển hình của mỗi biến thể), ba trục đồng bộ: bội số tải, lag, số pod (sẵn sàng và mong muốn), p95 end-to-end theo cửa sổ 1 phút.
- p95 theo bậc: bốn biến thể trên cùng biểu đồ, kèm EXP-05 `end-to-end` (compose) quy về `throughput_in` trên trục hoành.
- `efficiency[n]` theo `n` (cột), đường tham chiếu 1,0.
- Heatmap CPU theo pod × bậc, thêm dòng `vm_cpu_util`, để trả lời Q2.
- `db-slow`: overlay `pti:chunk_duration:p95_5m`, trigger KEDA và số pod mong muốn; đánh dấu lúc alert bắn.
- Bảng `scale_reaction_seconds` và `partition_spread`.

## 9. Mẫu bảng kết quả

| Bậc | `throughput_in` (msg/s) | `autoscale` p95 (s) / pod TB | `fixed-1` p95 (s) | `fixed-4` p95 (s) | `autoscale` `step_pass` |
| --- | --- | --- | --- | --- | --- |
| ×1 | … | … / … | … | … | …/5 |
| ×2 | | | | | |
| ×3 | | | | | |
| ×5 | | | | | |
| ×7 | | | | | |
| ×10 | | | | | |

| Chỉ số | Giá trị |
| --- | --- |
| `threshold_msgs`: EXP-05 compose / `fixed-1` / `fixed-4` / `autoscale` (trung vị) | … / … / … / … |
| `scale_reaction_seconds` trung vị, p95, max | … |
| `scale_down_seconds` trung vị, max | … |
| `efficiency[2]`, `[3]`, `[4]` | … |
| Tổng key đã kiểm / lần chạy có mất hoặc sai | … / 0 |
| `db-slow`: `hold_violations`, `bottleneck_alert_delay` trung vị | … |
| Tài nguyên nghẽn trước | … |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Mọi node k3d chạy chung một VM: thêm pod không thêm CPU vật lý, nên lợi ích scale bị chặn bởi số nhân của VM | Báo cáo `vm_cpu_util` và `efficiency`; kết luận tách "cơ chế scale đúng" (H2, H3, C2) khỏi "thông lượng tăng" (Q1); ghi rõ trong báo cáo |
| Simulator chạy cùng cluster, cạnh tranh CPU ở bậc cao | Tiêu chí bậc `invalid`; báo cáo CPU của simulator |
| Scale phụ thuộc số partition: 12 partition, 3 thread mỗi pod nên chỉ tới 4 pod có ích | Đúng thiết kế (DOC-40 §9.1); `fixed-4` là cận trên |
| Độ trễ 150 ms của `db-slow` là nhân tạo, khác một DB quá tải thật | Mục tiêu là kiểm luật chặn scale, không mô phỏng nguyên nhân; một lần chạy phụ ở `fixed-4` × tải ×10 cho thấy DB có bão hòa thật hay không |
| Metric qua Prometheus có độ phân giải 15 giây, thô hơn runner scrape 5 giây của EXP-05 | Đoạn đo ≥ 7 phút mỗi bậc; histogram tính bằng hiệu bucket nên không phụ thuộc độ phân giải |
| Ít lần chạy (5/3/3/5) do mỗi lần chạy khoảng 95 phút | Báo cáo từng lần chạy; không dùng kiểm định có giả định phân phối |
| HPA của `api` không bị kích bởi tải đọc thật (chỉ 10 SSE) | Nằm ngoài NFR-08 (tải là GTFS-rt); ghi số pod `api` để tham khảo |
| KEDA polling 15 giây và cửa sổ 5 phút của recording rule làm luật chặn phản ứng chậm | Đo `bottleneck_alert_delay` và thời điểm chặn thực tế; ghi rõ độ trễ này trong báo cáo |

## 11. Kết quả

Điền sau P7-10 (`pti-exp report`).

## 12. Câu hỏi còn mở

Không có.
