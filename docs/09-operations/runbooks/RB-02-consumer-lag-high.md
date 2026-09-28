# RB-02: Consumer lag cao

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-02
>
> Alert: `ConsumerLagHigh` (warning, 5 phút) · Dashboard: `pti-overview`, `pti-kafka`, `pti-postgres` · Liên quan: DOC-10 §2–3, DOC-20 §2, §5, §7

## Triệu chứng và ảnh hưởng

- Tổng lag của `etl-stream` vượt ngưỡng: `gtfs.vehicle_positions` > 3.000, `gtfs.trip_updates` > 1.000, `ticketing.*` > 300 (khoảng 25 giây dữ liệu lúc cao điểm). Alert bỏ qua listener đang pause bằng cờ.
- Ảnh hưởng: bản đồ và insight trễ; `EndToEndLatencyHigh` thường bắn theo. Không mất dữ liệu (offset chưa commit thì dữ liệu còn trong Kafka 7 ngày).
- Bị ức chế khi `ConsumerStopped` hoặc `CircuitBreakerOpen{name="warehouse"}` đang bắn (DOC-28 §6.4): khi đó xử lý theo RB-03 / RB-08.

## Kiểm tra

1. Lag có còn tăng không: Grafana `pti-kafka` → lag theo topic × partition. Hoặc `make topics` hai lần cách nhau 30 giây.
   - Lag **tăng đều mọi partition** → pipeline chậm hơn tốc độ vào (bước 2–4).
   - Lag **dồn ở một vài partition** → một consumer thread chậm hoặc bị kẹt (bước 5).
   - Lag **đang giảm** → đang đuổi kịp sau sự cố trước đó (restart, rebalance); theo dõi tới khi hết.
2. Tốc độ vào có bất thường không: `pti-simulator` → hệ số tải (`pti_sim_rate_multiplier`), kịch bản `load-ramp` đang chạy (`pti_sim_scenario_active`). Nếu đang chạy thực nghiệm tải (EXP-05, EXP-07) thì alert là **mong đợi**.
3. Chặng nào chậm: `pti-overview` → bốn chặng DR-57; `pti:chunk_duration:p95_5m`. Chunk p95 > 2 giây kèm lag tăng → `DatabaseBottleneck` (RB-08).
4. ETL có đủ tài nguyên không: `pti-jvm` → CPU `etl-stream` so với limit (2,0), GC pause; `docker stats --no-stream pti-etl-stream-1`.
5. Partition bị kẹt: log `{service="etl-stream", level=~"WARN|ERROR"}`; `pti_etl_listener_paused` (lý do `backoff` → RB-03); rebalance liên tục (`kafka_consumer_coordinator_rebalance_total` tăng) → xem `max.poll.interval.ms` bị vượt do chunk quá lâu.

## Xử lý

| Nguyên nhân | Việc |
| --- | --- |
| Tải vào tăng có chủ đích (demo, thực nghiệm) | Không làm gì; silence nếu cần (≤ thời lượng kịch bản) |
| Tải vào tăng ngoài dự kiến (simulator đặt sai hệ số) | `make sim-rate GTFS=1` |
| DB chậm | RB-08 |
| CPU `etl-stream` chạm limit | Compose: tăng `concurrency` của listener không giúp khi CPU đã đầy; giảm tải hoặc tăng CPU limit trong `compose.yaml` (DOC-39 §3.2) rồi `make up`. k3d: KEDA/HPA đã tự scale (DOC-40); kiểm tra số pod |
| Rebalance liên tục | Kiểm tra pod restart (`make ps`), OOM; chunk quá lâu → RB-08 |
| Một partition kẹt vì backoff | RB-03 |

Không reset offset để "xóa lag": dữ liệu sẽ không được ghi vào warehouse (chỉ còn đường replay từ raw zone).

## Trên k3d

- `etl-stream` tự scale 1→4 pod theo lag (KEDA, DOC-40 §9.1). Kiểm tra: `kubectl -n pti get scaledobject etl-stream` (`READY`, `ACTIVE`, `PAUSED`), `kubectl -n pti get hpa keda-hpa-etl-stream`, dashboard `pti-k8s`.
- Lag cao và đã đủ 4 pod: 12 partition chia cho 4 pod × 3 thread là mức tối đa; thêm pod không giúp. Xử lý như compose (tải hoặc DB).
- Lag cao mà số pod **không tăng**:
  - `pti:chunk_duration:p95_5m > 2`: luật chặn scale khi DB là nút thắt đang giữ số pod (SDD §12.7). Đây là hành vi đúng; xử lý DB theo RB-08.
  - ScaledObject có annotation `autoscaling.keda.sh/paused-replicas` còn sót từ EXP-07 hoặc RB-10: `kubectl -n pti annotate scaledobject etl-stream autoscaling.keda.sh/paused-replicas-`.
  - `READY` là `False`: xem `kubectl -n keda logs deploy/keda-operator --since=15m` (thường do Prometheus không trả được query).
- Pod mới không lên vì thiếu RAM của node (`Pending`, sự kiện `Insufficient memory`): `lite` đang ở giới hạn máy; giảm tải hoặc cắt theo thứ tự ở DOC-40 §5.3.

## Xác nhận đã xong

- `pti:kafka_lag:sum` dưới ngưỡng và có xu hướng giảm hoặc ổn định trong 10 phút; alert `resolved`.
- `pti:e2e_latency:p95_5m` (từ P4) về < 10 giây.

## Phòng ngừa và việc sau sự cố

- Lag cao lặp lại ở tải nền → chạy lại EXP-05 để xem ngưỡng tải đã giảm chưa (regression hiệu năng).
- Ghi hệ số tải và tài nguyên tại thời điểm sự cố vào issue để so với kết quả EXP-05.
