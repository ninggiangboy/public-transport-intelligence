# EXP-08: Chịu lỗi khi triển khai trên k3d

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-45 / EXP-08
>
> Phụ thuộc: [protocol chung](README.md), [EXP-01](EXP-01-crash-recovery.md), [EXP-04](EXP-04-full-replay.md), [DOC-40](../../09-operations/deploy-k8s.md) §6, §7.2, §9.5, §13, §17 (KD-08, KD-09, KD-10), DOC-10 §6, DOC-19 §7.2, DOC-20 §5, §7, DOC-24 §11 (TG-35), DOC-26 §7, DR-20, DR-24, DR-55, ADR-0003, ADR-0004, ADR-0028
>
> Người dùng chính: P7-07, P7-08, P7-09, P7-10; báo cáo chương đánh giá; DOC-46 bước 7

## 1. Giả thuyết

NFR-09: mỗi sự cố **đơn lẻ** (pod, broker, Postgres primary, Jev) không làm mất hoặc trùng dữ liệu, và hệ thống tự phục hồi không cần người can thiệp.

- **H1 (đúng đắn):** với mọi loại sự cố ở §5, warehouse sau drain chứa mỗi business key hợp lệ đúng một lần với giá trị mới nhất (`lost = 0`, `wrong_value = 0`, `unexpected = 0`, `latest_regressions = 0`), cả GTFS-rt lẫn ticketing.
- **H2 (tự phục hồi):** sau khi sự cố kết thúc (pod mới chạy, broker quay lại, primary mới sẵn sàng, mạng thông), lag quay về mức trước sự cố trong ≤ 60 giây (NFR-04), không cần lệnh thủ công.
- **H3 (cô lập):** sự cố ở một thành phần phụ không làm hỏng luồng chính: Jev lỗi không làm giảm thông lượng ETL (FR-09.7); Postgres failover không làm restart pod ứng dụng (liveness không phụ thuộc hệ thống ngoài, DOC-40 §7.1) và API đọc vẫn phục vụ (DOC-10 §6).
- **H4 (EXP-01 trên k3d):** kết luận của EXP-01 (kill consumer không mất, không trùng) vẫn đúng khi kill pod bằng Kubernetes, với nhiều pod cùng consumer group và rebalance hợp tác.

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Loại sự cố (biến thể) | F1…F9, R1 (§5) |
| Độc lập | Thời điểm tiêm | Ngẫu nhiên đều trong `[120 s, 480 s]` của cửa sổ 10 phút (seed của runner) |
| Phụ thuộc | Đúng đắn | README §4.1 cho `fact_vehicle_position`, `fact_trip_update`, `fact_ticket_sales` |
| Phụ thuộc | Phục hồi | `recovery_seconds` và các mốc thời gian §6 |
| Phụ thuộc | Khả dụng | Tỷ lệ request đọc API thành công, khoảng gián đoạn SSE, lỗi gửi của simulator |
| Phụ thuộc | Tác động phụ | Số lần restart của từng pod, alert bắn (dự kiến và ngoài dự kiến) |
| Kiểm soát | Môi trường | k3d `lite` (DOC-40 §2), compose tắt |
| Kiểm soát | Tải | GTFS-rt ×2, ticketing ×1, như EXP-01 |
| Kiểm soát | Số pod | `etl-stream` cố định 2 pod (`paused-replicas=2`, EXP-07 §4) để mọi lần chạy có cùng cấu trúc group; `api` 2 pod; `etl-batch` 1; `triage-worker` 1 |
| Kiểm soát | Giờ nghiệp vụ | 13:00–19:00 CDT ngày thường |
| Kiểm soát | Kịch bản simulator | Không có, trừ F7 (`bad-data` để có việc cho triage) và F8 (không phát, chỉ replay) |
| Kiểm soát | `triage-worker` | `provider=jev` qua `jev-stub` và Toxiproxy (`lite`) |

## 3. Baseline

Không chạy baseline DR-27. EXP-08 không so sánh hai cơ chế mà kiểm các bất biến dưới sự cố; baseline đã được so ở EXP-01…03. Mỗi chuỗi có biến thể `control` (không tiêm lỗi) để xác nhận ledger và phép so sánh trên k3d (`lost = 0`).

## 4. Môi trường

- Như EXP-07 §4: `make k8s-up ENV=lite`, smoke pass, ấm máy 10 phút; runner dùng `env/k3d.py` (kubectl, port-forward, Prometheus `localhost:9090`, Alertmanager `localhost:9093`, Toxiproxy `localhost:18474`).
- Sự cố được tiêm bằng các manifest trong `chaos/` (DOC-40 §13.1) hoặc bằng lệnh `kubectl`/`kubectl cnpg` nêu trong bảng §5. Runner áp CR, chờ CR `AllInjected`, và xóa CR khi xong lần chạy.
- Thời điểm pod sẵn sàng lấy từ `kubectl get pod -w` (sự kiện `Ready`), thời điểm restart từ `status.containerStatuses[].restartCount`.
- Runner mở 10 kết nối SSE (như README §2) và gửi 5 request/giây `GET /api/v1/vehicles/live` qua `localhost:8080` suốt lần chạy (probe khả dụng).

## 5. Các bước

```bash
uv run pti-exp run EXP-08 --variant control               --runs 5  --seed 8000
uv run pti-exp run EXP-08 --variant F1-etl-pod-kill       --runs 10 --seed 8100
uv run pti-exp run EXP-08 --variant F2-api-pod-failure    --runs 5  --seed 8200
uv run pti-exp run EXP-08 --variant F3-kafka-broker-kill  --runs 10 --seed 8300
uv run pti-exp run EXP-08 --variant F4-pg-failover        --runs 10 --seed 8400
uv run pti-exp run EXP-08 --variant F4b-pg-switchover     --runs 5  --seed 8450
uv run pti-exp run EXP-08 --variant F5-pg-network         --runs 10 --seed 8500   # 5 delay + 5 partition, order shuffled
uv run pti-exp run EXP-08 --variant F6-connect-kill       --runs 5  --seed 8600
uv run pti-exp run EXP-08 --variant F7-jev-timeout        --runs 3  --seed 8700
uv run pti-exp run EXP-08 --variant F8-batch-pod-kill     --runs 5  --seed 8800
uv run pti-exp run EXP-08 --variant R1-etl-rollout        --runs 5  --seed 8900
uv run pti-exp analyze EXP-08
```

Vòng đời chung của một lần chạy (khoảng 17 phút) theo README §2.1:

1. Kiểm cửa sổ giờ; kiểm mọi pod `Ready` và số restart ghi lại làm mốc. `PUT /sim/rate {"gtfsRt": 2, "ticketing": 1}`, chờ 60 giây. `t0` = lúc hết 60 giây.
2. Silence 25 phút các alert dự kiến của biến thể (cột cuối bảng dưới). Alert khác bắn trong lần chạy được ghi là "ngoài dự kiến".
3. Lấy mẫu mỗi 2 giây: lag đã commit (`pti-etl-gtfs-rt`, `pti-etl-ticketing`), trạng thái pod, kết quả probe API; mỗi 15 giây: metric Prometheus.
4. Tiêm lỗi tại `t_fault = t0 + u`:

| Biến thể | Hành động | Kết thúc sự cố (`t_fault_end`) | Alert dự kiến |
| --- | --- | --- | --- |
| `control` | Không làm gì | — | — |
| F1 `etl-pod-kill` | `chaos/pod-kill-etl-stream.yaml` (một pod ngẫu nhiên, `gracePeriod: 0`) | Pod thay thế `Ready` | `ConsumerLagHigh`, `ConsumerStopped`, `TargetDown` |
| F2 `api-pod-failure` | `chaos/pod-failure-api.yaml` (một pod, 60 s) | Hết 60 s và pod `Ready` | `TargetDown` |
| F3 `kafka-broker-kill` | `chaos/kafka-broker-kill.yaml` (một broker ngẫu nhiên) | Broker `Ready` và không còn partition under-replicated (`kafka_topic_partition_under_replicated_partition` = 0) | `ConsumerLagHigh`, `TargetDown` |
| F4 `pg-failover` | `kubectl -n pti delete pod <primary của pti-warehouse> --grace-period=0 --force` | `cluster/pti-warehouse` có primary mới và `readyInstances = 2` | `CircuitBreakerOpen`, `DatabaseBottleneck`, `ConsumerLagHigh`, `ConsumerPaused`, `TargetDown` |
| F4b `pg-switchover` | `kubectl cnpg promote pti-warehouse <replica> -n pti` | Như F4 | Như F4 |
| F5 `pg-network` | `chaos/pg-network-delay.yaml` (200 ms ± 50 ms, 120 s) hoặc `chaos/pg-network-partition.yaml` (60 s) | Hết `duration` | Như F4, trừ `TargetDown` |
| F6 `connect-kill` | `chaos/connect-kill.yaml` | Task của hai connector `RUNNING` lại | `ConnectorDown`, `DebeziumWalRetained` |
| F7 `jev-timeout` | Toxic `timeout` (`timeout: 0`) trên proxy `jev` qua `POST localhost:18474/proxies/jev/toxics`, 5 phút. Trước bước 1, bật `bad-data` (`rate 0.002`) để có dead letter mới cho triage | Xóa toxic | `CircuitBreakerOpen`, `TriageBacklogHigh` |
| F8 `batch-pod-kill` | Trước bước 1, tạo replay raw zone 1 giờ (`POST /etl/replays`, token operator) cho một giờ nghiệp vụ đã qua; khi `RawZoneReplayJob` đã xử lý ≥ 30% item (`pti_replay_records_total`), `kubectl delete pod <etl-batch> --grace-period=0 --force` | `StaleExecutionRecoverer` restart job và job `COMPLETED` | `BatchJobFailed`, `BatchExecutionRecovered`, `TargetDown` |
| R1 `etl-rollout` | `kubectl -n pti rollout restart deploy/etl-stream` (chiến lược DOC-40 §7.2) | Rollout xong (`rollout status`) | `TargetDown` |

   Tên alert lấy từ DOC-28 §6 (và `pti-k8s.yml` của DOC-40 §7.6). Runner đọc danh sách từ file rule lúc chạy và từ chối tên không tồn tại, để bảng này không lệch khỏi DOC-28.

5. Riêng F4 và F4b: trong suốt lần chạy, runner gửi mỗi 5 giây một `POST /api/v1/etl/jobs {"jobName": "PartitionMaintenanceJob"}` với `Idempotency-Key` mới; gặp 503 thì chờ `Retry-After` và gửi lại **cùng** khóa tới khi nhận 202 (DR-20). Job này idempotent và rẻ (DOC-19 §2).
6. Tại `t1 = t0 + 600 s` (hoặc khi F8 job `COMPLETED`, tối đa 30 phút): pause simulator (`kubectl scale deploy/source-simulator --replicas=0`), drain như README §2.1, rồi scale lại 1.
7. Tính chỉ số (§6), xuất ledger, so `ticketing_source`, xóa chaos CR, toxic và silence, ghi `summary.json`.
8. Chờ cluster trở lại trạng thái đầu (mọi pod `Ready`, `readyInstances = 2`, không partition under-replicated) tối đa 5 phút, rồi nghỉ 60 giây.

**Lần chạy `invalid`:** drain không xong trong 10 phút; `pti_sim_tick_lag_seconds > 2` quá 10 giây liên tục; một thành phần **không** phải mục tiêu bị restart vì lý do ngoài sự cố (ví dụ OOMKilled của Prometheus); node `NotReady`; CR chaos không áp được; cluster không về trạng thái đầu ở bước 8 của lần trước; lỗi runner. Pod ứng dụng bị restart **do** sự cố (ví dụ `etl-stream` restart trong F4) không làm lần chạy `invalid`; đó là kết quả, tính vào C3.

## 6. Chỉ số và công thức

Đúng đắn: README §4.1 cho cả cửa sổ `[t0, t1]`. Ticketing so với `ticketing_source` (README §3).

| Chỉ số | Công thức | Biến thể |
| --- | --- | --- |
| `recovery_seconds` | `t_caught_up − t_fault_end`, với `t_caught_up` như README §4.2 (lag ≤ `max(1,1 × lag_ref, 500)` giữ 10 giây) | Mọi biến thể có lỗi |
| `fault_to_caught_up` | `t_caught_up − t_fault` | Mọi biến thể có lỗi |
| `pod_ready_seconds` | `t(pod thay thế Ready) − t_fault` | F1, F2, F8 |
| `failover_seconds` | `t(primary mới nhận ghi) − t_fault`, với "nhận ghi" = lần commit đầu tiên của `etl_writer` sau `t_fault` (`pg_stat_database.xact_commit` trên instance mới) | F4, F4b |
| `write_gap_seconds` | Khoảng dài nhất không có dòng mới trong `dw.fact_vehicle_position` (theo `ingested_at`) quanh `t_fault` | F3, F4, F4b, F5 |
| `app_restarts` | Tổng tăng `restartCount` của các pod ứng dụng **không** phải mục tiêu | Mọi biến thể |
| `read_success_ratio` | Số probe `GET /vehicles/live` trả 200 / tổng probe trong `[t_fault, t_fault_end + 60 s]` | Mọi biến thể |
| `read_gap_seconds` | Chuỗi probe thất bại liên tiếp dài nhất (giây) | Mọi biến thể |
| `write_eventual_success` | Số `Idempotency-Key` cuối cùng nhận 202 / số khóa đã gửi; kèm `max_write_retry_seconds` | F4, F4b |
| `write_duplicates` | Số khóa có > 1 dòng `ops.job_request` (phải là 0 do UNIQUE) | F4, F4b |
| `sse_reconnect_seconds` | Với mỗi kết nối SSE bị đóng: thời gian tới event đầu tiên trên kết nối mới | F2, F4 |
| `sim_send_errors` | `Δ pti_sim_send_errors_total` trong cửa sổ | F3 |
| `under_replicated_seconds` | Thời gian `kafka_topic_partition_under_replicated_partition > 0` | F3 |
| `cdc_lag_max` | Max độ trễ từ `created_at` của giao dịch tới dòng `fact_ticket_sales` tương ứng | F6 |
| `throughput_ratio` | `throughput_out` trong 5 phút có toxic / trong 5 phút trước | F7 |
| `untriaged_during` | Số dead letter mới trong lúc toxic có `category IS NULL` sau khi toxic được xóa 10 phút (phải về 0: được triage lại) | F7 |
| `replay_checksum_match` | Checksum (README §4.5) của cửa sổ replay trước và sau replay bằng nhau (replay idempotent, như EXP-04) | F8 |
| `job_recovered_seconds` | `t(job COMPLETED) − t_fault` | F8 |
| `rebalances` | Tổng tăng `kafka_consumer_coordinator_rebalance_total` | F1, F3, R1 |

## 7. Tiêu chí đạt

| # | Tiêu chí | Biến thể |
| --- | --- | --- |
| C1 | `lost = 0`, `wrong_value = 0`, `unexpected = 0`, `latest_regressions = 0` ở **mọi** lần chạy hợp lệ, cả GTFS-rt và ticketing (H1, NFR-09) | Mọi biến thể |
| C2 | p95 của `recovery_seconds` < 60 giây (H2, NFR-04) | F1, F3, F4, F4b, F5, R1 |
| C3 | `app_restarts = 0` (H3) | Mọi biến thể |
| C4 | `read_success_ratio ≥ 0,99` và `read_gap_seconds ≤ 5` ở F1, F3, F5, F6, F7, F8, R1; `read_success_ratio ≥ 0,95` ở F2, F4, F4b (trong lúc một pod `api` hoặc replica chuyển vai trò) | Như nêu |
| C5 | `write_eventual_success = 1`, `write_duplicates = 0`, `max_write_retry_seconds ≤ 60` (DR-20) | F4, F4b |
| C6 | `sim_send_errors = 0` (`acks=all`, `min.insync.replicas=2`) | F3 |
| C7 | `throughput_ratio ∈ [0,95; 1,05]` và `untriaged_during = 0` (FR-09.7, như TG-35) | F7 |
| C8 | `replay_checksum_match` và job `COMPLETED` không cần thao tác thủ công, `job_recovered_seconds ≤ 5 phút` (DR-24, `stale-after` 2 phút) | F8 |
| C9 | `control`: `lost = 0`, không alert ngoài dự kiến | `control` |

Một vi phạm C1 ở bất kỳ lần chạy hợp lệ nào nghĩa là NFR-09 **không đạt**. Khi đó ghi `keys_diff.csv.gz`, mở issue, sửa, rồi chạy lại cả biến thể (không chỉ lần chạy lỗi).

## 8. Phân tích

`pti_exp/experiments/exp08.py` (`analyze`):

- Bảng C1…C9 theo biến thể; tổng key đã kiểm và cận trên rule of three (README §6).
- Biểu đồ lag căn theo `t_fault` (một đường mỗi lần chạy), mỗi biến thể một ô.
- Hộp `recovery_seconds` theo biến thể, đường ngang 60 giây; F1 đặt cạnh `kill-external` của EXP-01 (compose) để trả lời H4.
- F4/F4b: dòng thời gian gồm `failover_seconds`, `write_gap_seconds`, `read_gap_seconds`, readiness của từng pod `etl-stream` và `api`.
- Danh sách alert đã bắn theo biến thể (dự kiến và ngoài dự kiến), kèm độ trễ từ `t_fault`. Đây cũng là kiểm tra thực tế cho alert rule của DOC-28.

## 9. Mẫu bảng kết quả

| Biến thể | n (hợp lệ / invalid) | Key đã kiểm | Mất / sai / trùng | `recovery_seconds` trung vị / p95 / max | `app_restarts` | `read_success_ratio` min | Đạt |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `control` | 5 / 0 | … | 0 / 0 / 0 | — | 0 | … | … |
| F1 | 10 / … | … | … | … | … | … | … |
| F2 | | | | | | | |
| F3 | | | | | | | |
| F4 | | | | | | | |
| F4b | | | | | | | |
| F5 | | | | | | | |
| F6 | | | | | | | |
| F7 | | | | | | | |
| F8 | | | | | | | |
| R1 | | | | | | | |

| Chỉ số riêng | Giá trị |
| --- | --- |
| F4 `failover_seconds` trung vị / max | … |
| F4 `write_gap_seconds` trung vị / max | … |
| F4 `write_eventual_success`, `max_write_retry_seconds` | … |
| F3 `sim_send_errors`, `under_replicated_seconds` trung vị | … |
| F7 `throughput_ratio` | … |
| F8 `job_recovered_seconds`, `replay_checksum_match` | … |
| F1 (k3d) so với EXP-01 `kill-external` (compose): `recovery_seconds` trung vị | … / … |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Mọi node k3d nằm trên một VM: "node" không độc lập về phần cứng; mất đĩa, mất nguồn, phân vùng mạng giữa máy thật không mô phỏng được (ADR-0028) | Giới hạn kết luận ở "sự cố đơn lẻ ở mức pod, tiến trình và mạng trong cluster"; ghi rõ trong báo cáo |
| Không có biến thể mất cả node (drain hoặc dừng container node k3d): với 13 GB, hai node còn lại không chứa nổi toàn bộ workload | Ngoài phạm vi NFR-09 ("sự cố đơn lẻ" ở mức thành phần); nêu là hướng mở rộng |
| Chaos Mesh tiêm lỗi mạng bằng `tc` trong network namespace của pod; khác lỗi mạng vật lý | Mục tiêu là kiểm đường xử lý lỗi của ứng dụng (retry, circuit breaker, pause), không mô phỏng nguyên nhân |
| `jev-stub` trả lời cố định; hành vi lỗi thật của Jev (429, 5xx, timeout một phần) đa dạng hơn | Các lỗi đó đã có test ở DOC-24 (TG-3x với WireMock); F7 chỉ kiểm cô lập ở mức hệ thống |
| Failover CNPG phụ thuộc thời gian phát hiện của operator, khác Patroni hoặc dịch vụ managed | Báo cáo `failover_seconds` như một con số của cấu hình này, không khái quát |
| Số lần chạy ít hơn EXP-01 (tối đa 10 mỗi biến thể) | Tiêu chí đúng đắn là "0 vi phạm ở mọi lần chạy"; báo cáo cận trên rule of three (10 lần → 30%) và tổng số key đã kiểm |
| Sự cố được tiêm ở tải ×2, không ở tải cao | Có chủ đích: tách ảnh hưởng của sự cố khỏi ảnh hưởng của quá tải (EXP-07) |

## 11. Kết quả

Điền sau P7-10 (`pti-exp report`).

## 12. Câu hỏi còn mở

Không có.
