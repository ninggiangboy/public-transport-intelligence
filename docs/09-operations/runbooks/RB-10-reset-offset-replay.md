# RB-10: Replay bằng cách reset offset của consumer

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-10
>
> Thủ tục (không gắn alert) · FR-12.4, F-REPLAY-03 · Liên quan: DOC-09 §1.1 (consumer group), DOC-14 §8 (guard), DR-16 (dedup registry), DOC-22 §4 (replay từ raw zone), DOC-43 §4.2

## Khi nào dùng

Đọc lại một khoảng dữ liệu **còn trong Kafka** (retention 7 ngày trên compose, 24 giờ trên k3d; DOC-18 §1.3) qua đường streaming bình thường, khi dữ liệu đó **chưa từng được ghi** vào warehouse:

- đã bỏ qua offset bằng tay để vượt một record gây lỗi `FATAL` (RB-03), nay đã sửa lỗi;
- warehouse được khôi phục từ bản sao lưu cũ hơn offset đã commit (DOC-43 §4.2, đường A);
- một listener bị cấu hình sai và đã commit offset mà không ghi (lỗi lập trình hiếm gặp).

**Không dùng** RB-10 khi muốn ghi đè dữ liệu **đã có** bằng logic mới (sửa bug xử lý, đổi quy tắc): đường streaming chạy với `:replay = false`, nên guard (DOC-14 §8) trả 0 dòng cho record có cùng nội dung. Trường hợp đó dùng replay từ raw zone (`make replay`, DOC-22 §4), vì replay đặt `:replay = true`. Dữ liệu cũ hơn 7 ngày cũng chỉ còn trong raw zone.

| Tiêu chí | RB-10 (reset offset) | Replay raw zone (`make replay`) |
| --- | --- | --- |
| Nguồn dữ liệu | Kafka, ≤ 7 ngày (k3d: 24 giờ) | Raw zone, theo retention của bucket `raw` |
| Ghi đè dữ liệu đã có | Không (guard chặn, trừ khi nội dung khác) | Có (`:replay`) |
| Rule DQ-07, DQ-12 | Áp dụng như lúc realtime | Bỏ qua (DOC-22 §4.4) |
| Ảnh hưởng realtime | Dừng mọi listener của `etl-stream` trong lúc reset; sau đó realtime trễ tới khi đuổi kịp | Không dừng realtime; chạy song song trong `etl-batch` |
| Có trên UI | Không (chỉ CLI) | Có (Ops console, từ P5) |

## Chuẩn bị

1. Xác định **consumer group** và **topic**:

   | Nguồn | Group | Topic |
   | --- | --- | --- |
   | VehiclePosition, TripUpdate | `pti-etl-gtfs-rt` | `gtfs.vehicle_positions`, `gtfs.trip_updates` |
   | Vé, điểm bán | `pti-etl-ticketing` | `ticketing.sales.cdc`, `ticketing.sale_points.cdc` |

2. Xác định **mốc bắt đầu** theo **timestamp của record Kafka** (CreateTime = giờ thật lúc producer gửi, UTC). Đây **không** phải giờ nghiệp vụ khi `PTI_CLOCK_OFFSET` khác 0 (DR-67). Tìm mốc: `batch_id` hoặc offset trong `ops.etl_stream_batch` (cột `offsets`, `started_at`), hoặc offset ghi trong issue của RB-03.
3. Kiểm tra dữ liệu còn trong Kafka: offset sớm nhất của mỗi partition phải ≤ offset cần đọc lại.

   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
     --bootstrap-server localhost:9092 --topic gtfs.vehicle_positions --time earliest
   ```

4. `make backup` (nguyên tắc 2 của DOC-42 §2).
5. Tạo silence cho `ConsumerLagHigh`, `ConsumerStopped`, `TargetDown`, `EndToEndLatencyHigh`, `LatencyStageSlow` với matcher `application="etl-stream"` hoặc theo `alertname`, thời hạn ≤ 2 giờ, comment `RB-10 <issue>`.

## Thực hiện

1. **Dừng toàn bộ `etl-stream`.** Kafka chỉ cho reset offset khi group không còn thành viên nào; pause bằng cờ không đủ, vì container vẫn là thành viên.

   ```bash
   docker compose stop etl-stream
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:9092 --describe --group pti-etl-gtfs-rt --state
   ```

   Chờ tới khi `STATE` là `Empty` (tối đa `session.timeout.ms`, 45 giây).
2. **Chạy thử** (không đổi gì), rồi so cột `NEW-OFFSET` với mốc ở bước Chuẩn bị 2:

   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:9092 --group pti-etl-gtfs-rt \
     --topic gtfs.vehicle_positions --topic gtfs.trip_updates \
     --reset-offsets --to-datetime 2026-09-29T19:40:00.000Z --dry-run
   ```

   Có thể thay `--to-datetime` bằng `--to-offset <n>` cho một partition cụ thể (`--topic gtfs.vehicle_positions:3`), hoặc `--shift-by -<n>`.
3. **Thực hiện:** cùng lệnh, thay `--dry-run` bằng `--execute`. Chỉ reset topic cần thiết; các topic khác của group giữ nguyên offset.
4. **Khởi động lại:** `docker compose start etl-stream`. Consumer đọc từ offset mới. Record đã từng được ghi (sau mốc) bị bỏ qua sớm nhờ `dedup_registry` (nếu còn trong TTL 1 giờ) hoặc bị guard trả 0 dòng; record đã từng vào DLQ không tạo dead letter mới (index duy nhất theo vị trí Kafka). Chỉ record chưa từng ghi mới thực sự vào warehouse.
5. **Theo dõi:** lag giảm dần (`make topics`); `pti_etl_duplicates_total{reason}` và số dòng `0` của guard tăng là bình thường. Với 143 msg/s lúc cao điểm và ETL xử lý được vài nghìn msg/s, mỗi giờ dữ liệu cần vài phút để đuổi kịp.

## Trên k3d

- Dừng consumer (thay cho `docker compose stop etl-stream`): `kubectl -n pti annotate scaledobject etl-stream autoscaling.keda.sh/paused-replicas=0 --overwrite`, chờ mọi pod `etl-stream` kết thúc (`kubectl -n pti get pods -l app.kubernetes.io/name=etl-stream`). Group phải rỗng trước khi reset.
- Reset offset (thay cho `docker compose exec kafka …`): `kubectl -n pti exec pti-dual-0 -- bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <group> --reset-offsets …` với cùng tham số như compose (chạy `--dry-run` trước).
- Chạy lại: `kubectl -n pti annotate scaledobject etl-stream autoscaling.keda.sh/paused-replicas-`. KEDA đưa về ≥ 1 pod và scale theo lag.
- `make backup`: README §2.1. `make topics`, `job-run` thêm `PTI_ENV=k3d`.

## Xác nhận đã xong

- Lag của group về mức bình thường; alert đã silence tự `resolved` sau khi xóa silence.
- Khoảng dữ liệu cần bổ sung đã có trong warehouse, ví dụ với VP:

  ```sql
  SELECT date_trunc('minute', event_timestamp) AS m, count(*)
  FROM dw.fact_vehicle_position
  WHERE service_date = :d AND event_timestamp BETWEEN :from AND :to
  GROUP BY 1 ORDER BY 1;
  ```

  Không còn phút trống trong khoảng mà nguồn có phát (so với `pti_sim_messages_sent_total` hoặc ledger nếu đang chạy thực nghiệm).
- Nếu warehouse vừa được khôi phục (từ P4): tính lại insight cho khoảng đó bằng **một** lần `make job-run NAME=AnalyticsRecomputeJob PARAMS='fromTs=<from>,toTs=<to>' WAIT=1` (tối đa 7 ngày mỗi lần; DOC-23 §11.5), rồi chạy `DataQualityJob`. Nếu replay đã tạo với `recompute_analytics = true` thì bỏ bước tính lại.

## Lưu ý

- Trên k3d (P8-03): thay `docker compose stop/start` bằng `kubectl scale deployment etl-stream --replicas=0` rồi trả lại số replica cũ; tắt KEDA ScaledObject trong lúc đó (`kubectl annotate scaledobject etl-stream autoscaling.keda.sh/paused=true`), nếu không KEDA sẽ scale lên lại vì lag.
- Đừng reset **về sau** (`--to-latest`) để "xóa lag": dữ liệu bị bỏ qua chỉ còn cứu được bằng replay raw zone (DOC-42 §2, nguyên tắc 1).
- Mỗi lần dùng RB-10 phải có issue ghi group, topic, offset cũ và mới (đầu ra của `--dry-run`).
