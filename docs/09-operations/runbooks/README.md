# Runbook

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-42
>
> Phụ thuộc: DOC-28 §6 (alert), DOC-38 §4 (lệnh `make`), DOC-40 (k3d), DOC-43 (backup và khôi phục), DOC-22 (DLQ, replay), DOC-24 (triage)
>
> Người dùng chính: người vận hành (kỹ sư trực), P3-05, P8-03

Mỗi alert trong DOC-28 §6.3 trỏ tới một runbook qua annotation `runbook_url`. Một runbook có thể gom nhiều alert cùng nguyên nhân. Mỗi file theo template phụ lục A.7 của master plan.

## 1. Danh mục

| Runbook | Alert / tình huống | Mức |
| --- | --- | --- |
| [RB-01](RB-01-batch-job-failed.md) | `BatchJobFailed`, `ReplayFailed`, `BatchExecutionRecovered` | critical / warning / info |
| [RB-02](RB-02-consumer-lag-high.md) | `ConsumerLagHigh` | warning |
| [RB-03](RB-03-consumer-stopped.md) | `ConsumerStopped`, `ConsumerPaused`, `FatalErrors` | critical / warning / critical |
| [RB-04](RB-04-dlq-rate-high.md) | `DlqRateHigh`, `DlqBacklogHigh`, `DlqSevereRecords`, `DlqNeedsAttention`, `DlqUpstreamErrorBurst`, `TriageBacklogHigh` | critical / warning |
| [RB-05](RB-05-gtfs-feed.md) | `GtfsFeedRejected`, `GtfsFeedExpiring`, bootstrap feed thất bại | warning |
| [RB-06](RB-06-gtfs-rt-feed-stale.md) | `GtfsRtFeedStale` | critical |
| [RB-07](RB-07-latency-throughput.md) | `EndToEndLatencyHigh`, `ThroughputDrop`, `LatencyStageSlow`, `SimulatorLagging` | warning / info |
| [RB-08](RB-08-database-bottleneck.md) | `DatabaseBottleneck`, `CircuitBreakerOpen` | warning |
| [RB-09](RB-09-debezium.md) | `DebeziumWalRetained`, `ConnectorDown` | warning–critical |
| [RB-10](RB-10-reset-offset-replay.md) | Thủ tục: replay bằng reset offset | — |
| [RB-11](RB-11-rebuild-warehouse.md) | Thủ tục: dựng lại warehouse từ raw zone; `BackupJobFailed` (k3d) | — / warning |
| [RB-12](RB-12-secret-rotation.md) | Thủ tục: xoay vòng secret | — |
| [RB-13](RB-13-data-quality-check.md) | `DataQualityCheckFailed`, `DataQualityCheckStale` | warning |
| [RB-14](RB-14-api-and-targets.md) | `ApiErrorRateHigh`, `TargetDown` | warning / critical |

Các bước chính viết cho compose. Trên k3d, lệnh `make` dùng chung chạy được với `PTI_ENV=k3d` (§2.1); phần khác biệt của từng runbook nằm ở mục "Trên k3d" trong file đó. Alert của triage (P6) nằm ở RB-04 và RB-08; `BackupJobFailed` (chỉ k3d) ở RB-11.

## 2. Quy ước chung

**Công cụ** (compose; DOC-38 §4):

| Việc | Lệnh |
| --- | --- |
| Trạng thái container | `make ps` |
| Log một service | `make logs S=<service> PRETTY=1`, hoặc Grafana Explore → Loki: `{service="<service>", level=~"WARN\|ERROR"}` |
| Log theo `batch_id` / trace | `{service=~"etl-.*"} \| json \| batch_id="<uuid>"`; từ dòng log bấm `trace_id` để mở Tempo |
| SQL warehouse | `make psql-wh` (user `pti_owner`, cẩn thận: có quyền ghi) |
| Lag, topic | `make topics` |
| Connector | `make connectors` |
| Cờ vận hành | Ops console → Controls (từ P5), `PUT /etl/flags/{key}` (từ P4), hoặc `make flag KEY=… VALUE=…` |
| Job batch | `make job-run`, `make job-restart ID=…` (hoặc API `POST /etl/jobs/{id}/restart` từ P4) |
| Replay | `make replay …` (hoặc Ops console / `POST /etl/replays` từ P4) |
| Dashboard | Grafana `http://localhost:3000`: `pti-overview`, `pti-kafka`, `pti-postgres`, `pti-batch`, `pti-api`, `pti-jvm`, `pti-simulator`, `pti-triage` (DOC-28 §7); thêm `pti-k8s` trên k3d |

**Nguyên tắc:**

1. **Không mất dữ liệu là ưu tiên hơn độ trễ.** Offset chỉ commit sau khi ghi DB (ADR-0004), nên dừng một consumer không làm mất gì; đừng reset offset hay xóa dead letter để "làm xanh" dashboard.
2. Trước thao tác có thể gây mất dữ liệu (xóa database, reset offset, xóa connector, đổi mật khẩu) thì chạy `make backup` (DOC-43 §3.1) nếu warehouse còn đọc được.
3. Lệnh SQL ghi tay chỉ dùng khi runbook ghi rõ. Mọi thay đổi dữ liệu nghiệp vụ đi qua API hoặc job, để có `dlq_action_log` và `batch_id`.
4. Sau mỗi sự cố critical: tạo GitHub issue nhãn `incident` với dòng thời gian, nguyên nhân, lệnh đã chạy, và việc phòng ngừa (mục "Phòng ngừa" của runbook).
5. Silence trên Alertmanager chỉ dùng khi đã biết nguyên nhân và đang xử lý, có `comment` và thời hạn ≤ 4 giờ.

### 2.1 Trên k3d (DOC-40)

Mọi thứ của hệ thống nằm trong namespace `pti`; Grafana (`:3000`), Prometheus (`:9090`), Alertmanager (`:9093`) và Mailpit (`:8025`) dùng cùng cổng host như compose (DOC-40 §3.1). Values `lite` không có Loki và Tempo: log đọc bằng `kubectl logs`, không có trace.

**Lệnh `make` dùng chung.** Các lệnh sau nhận `PTI_ENV=k3d` (mặc định `compose`) và chạy cùng việc trên cluster, nên runbook không phải viết lại từng lệnh: `psql-wh`, `psql-src`, `psql-sim` (`kubectl cnpg psql`, user như compose), `topics` và `tail-<topic>` (`kubectl exec pti-dual-0 -- bin/kafka-*.sh`), `connectors` (`kubectl get kafkaconnector` kèm trạng thái task), `s3-ls` (`kubectl exec` vào `seaweedfs-0`), `sim-status`, `scenario`, `scenario-stop`, `sim-rate` (simulator qua NodePort `localhost:8084`), `flag`, `job-run`, `job-restart`, `job-stop`, `replay`, `gtfs-load`, `ensure-partitions` (SQL bằng `pti_owner` qua `kubectl cnpg psql`). `make k8s-psql-wh` (DOC-40 §14) là tên ngắn của `make psql-wh PTI_ENV=k3d`. Ví dụ: `make replay PTI_ENV=k3d SOURCE=GTFS_RT_VEHICLE_POSITION FROM=… TO=…`.

**Lệnh khác nhau:**

| Việc | Compose | k3d |
| --- | --- | --- |
| Trạng thái | `make ps` | `make k8s-status`; `kubectl -n pti get pods -o wide` |
| Log | `make logs S=<svc>`, Loki | `make k8s-logs S=<app>`; pod đã chết: `kubectl -n pti logs <pod> --previous` |
| Khởi động lại app | `make restart S=<app>` | `kubectl -n pti rollout restart deployment/<app>` (rolling, không mất dữ liệu vì offset commit sau DB) |
| Khởi động lại Kafka | `make restart S=kafka` | Xóa từng pod một: `kubectl -n pti delete pod pti-dual-<n>`, chờ `Ready` rồi mới tới pod sau (Strimzi tạo lại, RF 3 giữ dữ liệu) |
| Khởi động lại Postgres | `make restart S=pg-warehouse` | `kubectl cnpg restart pti-warehouse -n pti` (rolling: replica trước, primary sau bằng switchover) |
| Chuyển primary | — | `kubectl cnpg promote pti-warehouse <pod replica> -n pti` |
| OOM, lý do chết | `docker inspect pti-<app>-1 --format '{{.State.OOMKilled}}'` | `kubectl -n pti get pod <pod> -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'` (`OOMKilled`) |
| CPU, RAM | `docker stats --no-stream` | `kubectl top pod -n pti` |
| Dừng app giữ hạ tầng | `make stop-apps` | Tạm dừng KEDA về 0: `kubectl -n pti annotate scaledobject <app> autoscaling.keda.sh/paused-replicas=0 --overwrite`; `api` (HPA): `kubectl -n pti scale deployment api --replicas=0`. Bật lại: xóa annotation (`autoscaling.keda.sh/paused-replicas-`) hoặc `make k8s-apply` |
| Chạy lại migration, áp lại cấu hình | `make up` | `make k8s-apply` (hook `db-migrate`, DOC-40 §7.4) |
| Đồng hồ nghiệp vụ | `make clock-offset AT=…` rồi `make up` | `make k8s-clock-offset AT=…` |
| Connect REST | `localhost:18083` | `kubectl -n pti port-forward svc/pti-connect-connect-api 18083:8083`, rồi dùng cùng lệnh `curl` **chỉ để đọc**. Thay đổi đi qua CR `KafkaConnector` vì Strimzi ghi đè thay đổi REST: restart `kubectl -n pti annotate kafkaconnector <name> strimzi.io/restart=true`; dừng/tạm dừng `spec.state: stopped \| paused` (`kubectl patch … --type merge`) |
| Toxiproxy trước Postgres | `localhost:8474` | Không có. Lỗi mạng tới Postgres dùng `NetworkChaos` (DOC-40 §13); gỡ: `kubectl -n pti delete networkchaos --all` |
| Backup | `make backup` | `kubectl -n pti create job --from=cronjob/pg-backup pg-backup-manual-$(date +%s)` (DOC-40 §12) |
| Xóa sạch, dựng lại | `make reset` | `make k8s-down && make k8s-up` |

## 3. Kiểm thử runbook

P3-05: mỗi alert được kích hoạt thử một lần theo cột "Cách gây ra" dưới đây, kiểm tra alert tới Mailpit (và `ops.alert_event` từ P4-16), rồi làm theo runbook tới bước "Xác nhận". Các alert phụ thuộc `api` (`EndToEndLatencyHigh`, `GtfsRtFeedStale`, `ApiErrorRateHigh`) được thử lại sau P4-16 (DOC-28 §6.3, ghi chú "Alert phụ thuộc `api`"). Kết quả mỗi lần thử ghi vào §4.

| Alert | Cách gây ra trên compose |
| --- | --- |
| `BatchJobFailed` | `make job-run NAME=GtfsStaticLoadJob PARAMS='sourceUri=s3://raw/gtfs-static/missing.zip'` (lỗi `NoSuchKey` → `FATAL`) |
| `ReplayFailed` | `make replay` rồi `docker stop pti-seaweedfs-1` giữa chừng |
| `BatchExecutionRecovered` | `docker kill pti-etl-batch-1` giữa `RawZoneReplayJob` rồi `docker start` |
| `ConsumerLagHigh` | `make sim-rate GTFS=10` trong 10 phút lúc cao điểm, hoặc `docker pause pti-etl-stream-1` 6 phút (kèm `TargetDown`) |
| `ConsumerStopped`, `FatalErrors` | Profile `experiment`: `pti.test.fault.before-write=throw-fatal` |
| `ConsumerPaused`, `CircuitBreakerOpen`, `DatabaseBottleneck` | Toxiproxy: tắt proxy `pg-warehouse` 6 phút; hoặc thêm toxic `latency` 3.000 ms |
| `DlqRateHigh` | `make scenario NAME=bad-data ARGS='{"ratio":0.05,"duration":"PT10M"}'` |
| `DlqBacklogHigh` | Như trên với `ratio` 0,2, triage tắt, chờ 30 phút |
| `GtfsFeedRejected` | `cp sample-data/gtfs/metrotransit-mn-20260926.zip /tmp/broken.zip && zip -d /tmp/broken.zip stop_times.txt`, chép vào `sample-data/gtfs/` (mount `/feed`), rồi `make job-run NAME=GtfsStaticLoadJob PARAMS='sourceUri=file:/feed/broken.zip'`. Xóa file sau khi thử |
| `GtfsFeedExpiring` | `PTI_CLOCK_OFFSET` đặt ngày nghiệp vụ 2026-11-08 (feed hết lịch 2026-11-13) |
| `GtfsRtFeedStale` | Từ P4-16: `docker stop pti-source-simulator-1` 3 phút. Ở P3 cùng thao tác đó (12 phút) gây `ThroughputDrop` |
| `EndToEndLatencyHigh`, `LatencyStageSlow` | Toxiproxy toxic `latency` 2.000 ms trên `pg-warehouse` (từ P4 cho `EndToEndLatencyHigh`) |
| `ThroughputDrop` | `make sim-rate GTFS=0.3` sau ≥ 1 giờ chạy ổn định |
| `SimulatorLagging` | `docker update --cpus 0.2 pti-source-simulator-1` rồi `make sim-rate GTFS=5` |
| `DebeziumWalRetained`, `ConnectorDown` | `PUT /connectors/debezium-ticketing/pause` rồi `make sim-rate TICKETING=20` (WAL tăng; ngưỡng warning cần nhiều giờ, dùng `promtool test rules` thay cho chờ thật) |
| `DataQualityCheckFailed` | `refund-burst` với bản gốc bị xóa ở nguồn (DQ-22), hoặc chèn tay một dòng vào `dw.fact_vehicle_position_default` |
| `DataQualityCheckStale` | Dừng `etl-batch` 3 × chu kỳ của rule ngắn nhất |
| `ApiErrorRateHigh` | Từ P4: `docker stop pti-pg-warehouse-1` 6 phút trong khi `make e2e` chạy |
| `TargetDown` | `docker stop pti-api-1` 3 phút |

### 3.1 Trên k3d (P8-03)

Chỉ thử lại các alert có đường đi khác compose (scrape qua ServiceMonitor, rule qua `PrometheusRule`, Alertmanager của kube-prometheus-stack). Mỗi alert tới Mailpit (`localhost:8025`) và `ops.alert_event` như compose.

| Alert | Cách gây ra trên k3d `lite` |
| --- | --- |
| `TargetDown` | `kubectl -n pti scale deployment triage-worker --replicas=0` sau khi pause KEDA (§2.1), 3 phút; job `pti-triage-worker` mất target |
| `ConsumerLagHigh` | `kubectl -n pti annotate scaledobject etl-stream autoscaling.keda.sh/paused-replicas=1 --overwrite` rồi `make k8s-load STEPS='10' STEP=PT10M` |
| `DatabaseBottleneck`, `CircuitBreakerOpen` | `kubectl apply -f deploy/chaos/pg-network-delay.yaml` với `latency` 3 s, `duration` 8m |
| `DlqRateHigh` | `make scenario PTI_ENV=k3d NAME=bad-data ARGS='{"ratio":0.05,"duration":"PT10M"}'` |
| `ConnectorDown` | `kubectl -n pti patch kafkaconnector debezium-ticketing --type merge -p '{"spec":{"state":"paused"}}'` 3 phút, rồi `running` |
| `BatchJobFailed` | `make job-run PTI_ENV=k3d NAME=GtfsStaticLoadJob PARAMS='sourceUri=s3://raw/gtfs-static/missing.zip'` |
| `BackupJobFailed` | `kubectl -n pti create job pg-backup-test --image=busybox -- /bin/false` (tên khớp `pg-backup.*`). Job thất bại hẳn sau khoảng 10 phút (6 lần thử mặc định), alert bắn sau 5 phút nữa. Xóa Job sau khi thử (`kubectl -n pti delete job pg-backup-test`), vì alert còn bắn khi Job thất bại còn tồn tại |

## 4. Kết quả kiểm thử

Điền khi làm P3-05 (và bổ sung sau P4-16, P6, P8-03). P8-03 thử lại trên k3d `lite` các alert có cách gây ra ở §3.1.

| Alert | Ngày thử | Phase | Tới Mailpit | Tới `alert_event` | Runbook làm được tới "Xác nhận" | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |
| _(chưa chạy)_ | | | | | | |

## 5. Câu hỏi còn mở

Không có.
