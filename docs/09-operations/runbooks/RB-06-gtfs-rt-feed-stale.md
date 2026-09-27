# RB-06: Feed GTFS-realtime không còn dữ liệu mới

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-06
>
> Alert: `GtfsRtFeedStale` (critical, bắn ngay; có từ P4-16) · Dashboard: `pti-overview` (độ tươi theo nguồn), `pti-simulator` · Liên quan: DR-67, DR-71, DOC-20 §6, DOC-25 §6, §8

## Triệu chứng và ảnh hưởng

- `pti_source_last_event_age_seconds{source=~"GTFS_RT_.*"} > 120`: bản ghi mới nhất trong warehouse của `GTFS_RT_VEHICLE_POSITION` hoặc `GTFS_RT_TRIP_UPDATE` cũ hơn 2 phút theo đồng hồ nghiệp vụ. Gauge do `api` tính từ DB (DR-71), nên alert vẫn đúng khi mọi pod `etl-stream` đã chết.
- Ảnh hưởng: bản đồ đứng yên, `StaleBanner` hiện trên UI (FR-15.2), bunching và disruption không được phát hiện. Ức chế `ThroughputDrop` và `EndToEndLatencyHigh` (DOC-28 §6.4).
- Trước P4-16 alert này không có dữ liệu; tình huống tương tự được `ThroughputDrop` bắt (RB-07).

## Kiểm tra

Đi theo đường dữ liệu từ nguồn tới DB và dừng ở chặng đầu tiên bị hỏng.

1. **Nguồn có phát không?** `make sim-status`: `running`, `businessNow`, `ratePerSecond`; Grafana `pti-simulator` → `pti_sim_messages_sent_total` theo topic.
   - Container dừng hoặc không healthy (`make ps`) → nhánh A.
   - Đang chạy nhưng không gửi: kịch bản có đặt hệ số tải 0 (`pti_sim_rate_multiplier`), hoặc lỗi gửi Kafka (`pti_sim_send_errors_total`, log `{service="source-simulator", level="ERROR"}`).
2. **Kafka có nhận không?** `make tail-gtfs.vehicle_positions` trong 10 giây. Có message → chặng nguồn tốt. Không có và simulator báo lỗi gửi → nhánh B.
3. **ETL có đọc và ghi không?** `pti_etl_listener_running`, `pti_etl_listener_paused{reason}` và lag (`make topics`).
   - Listener dừng hoặc pause vì `backoff`/`circuit` → RB-03 / RB-08.
   - Pause vì `flag` → nhánh C.
   - Readiness `DOWN` với `no active feed` → RB-05 nhánh C.
   - Đang chạy và lag nhỏ, nhưng DB vẫn cũ → bước 4.
4. **Dữ liệu có bị loại hết vào DLQ không?** `pti:etl_skipped:rate5m` so với `pti:etl_input:rate5m` cho nguồn GTFS-rt. Gần 100% → RB-04 (thường là đồng hồ lệch, `DQ-07`/`DQ-09`).
5. **Đồng hồ có lệch không?** So `businessNow` của `make sim-status` với `GET /api/v1/system/freshness` (trường `businessNow`, DOC-32). Hai app đọc `PTI_CLOCK_OFFSET` khác nhau thì tuổi tính ra sai dù dữ liệu vẫn tươi → nhánh D.
6. **Có phải giờ không có xe không?** Block sớm nhất bắt đầu 02:32 và muộn nhất kết thúc 26:34 (02:34 hôm sau) theo giờ Chicago (DOC-13 §2.2). Quanh 02:30 giờ nghiệp vụ rất ít xe; nếu `pti_sim_active_vehicles` bằng 0 thì alert là đúng về dữ liệu nhưng không phải sự cố.

## Xử lý

**A. Simulator dừng.** `make logs S=source-simulator` tìm lỗi khởi động (thiếu file feed, sai SHA-256, không kết nối được `pg-source`: RB-05 nhánh C, RB-08). Sửa rồi `make restart S=source-simulator`. Lỗi `FATAL` trong lúc chạy: RB-03.

**B. Kafka không nhận.** `make ps` và `make logs S=kafka`. Kafka KRaft một node trên compose: đầy đĩa (`docker system df`), OOM, hoặc chưa khởi động xong sau khi restart máy. `make restart S=kafka`, chờ healthy, rồi kiểm tra simulator phát tiếp. Producer có retry: message chưa gửi được nằm trong bộ đệm, quá `delivery.timeout.ms` (120 giây) thì bị bỏ và tăng `pti_sim_send_errors_total`. Message bị bỏ **không có dòng ledger** (ledger chỉ ghi khi Kafka ack, DOC-25 §6.4), nên không tính là mất trong thực nghiệm.

**C. Listener bị pause bằng cờ.** Kiểm tra ai đặt và vì sao: `SELECT key, value, updated_by, updated_at FROM ops.runtime_flag WHERE key LIKE 'etl.consumer.%';`. Nếu không còn lý do (ví dụ quên tắt sau RB-10): `make flag KEY=etl.consumer.gtfs-rt.paused VALUE=false`.

**D. Đồng hồ lệch.** Đặt lại cùng offset cho mọi app: `make clock-offset AT=…` rồi `make up` (tạo lại container để đọc `.env`). Dữ liệu đã bị loại vào DLQ vì lệch giờ: RB-04 bước 5.

**E. Giờ không có xe.** Silence `GtfsRtFeedStale` tới 02:45 giờ nghiệp vụ với comment `no scheduled service`. Nếu tình huống này lặp lại khi demo, đổi `PTI_CLOCK_OFFSET` sang khung giờ có xe (DOC-25 §3.1).

## Trên k3d

- Kafka có 3 broker (RF 3, `min.insync.replicas` 2): mất một broker không làm simulator ngừng gửi. Nếu nguồn ngừng vì Kafka thì thường đã mất từ hai broker: `kubectl -n pti get pods -l strimzi.io/cluster=pti`, `kubectl -n pti get kafka pti -o jsonpath='{.status.conditions}'`.
- Đĩa broker: `kubectl -n pti exec pti-dual-0 -- df -h /var/lib/kafka`. Đầy đĩa thì tăng `storage.size` của node pool trong values rồi `make k8s-apply` (local-path không mở rộng được PVC tại chỗ; phải xóa từng pod cùng PVC để Strimzi tạo lại, mỗi lần một broker).
- Simulator: `make k8s-logs S=source-simulator`, `kubectl -n pti rollout restart deployment/source-simulator`. `make sim-status`, `topics`, `tail-<topic>`, `flag` thêm `PTI_ENV=k3d`.
- Đồng hồ: `make k8s-clock-offset AT=…`.

## Xác nhận đã xong

- `pti_source_last_event_age_seconds{source=~"GTFS_RT_.*"} < 30` trong 5 phút; alert `resolved`.
- `GET /api/v1/system/freshness` trả `stale = false` cho cả hai nguồn GTFS-rt; bản đồ có xe di chuyển.
- Lag của `pti-etl-gtfs-rt` đã về mức bình thường (RB-02).

## Phòng ngừa và việc sau sự cố

- Mọi thao tác dừng simulator có chủ đích (thực nghiệm, bảo trì) phải có silence đi kèm.
- Nếu nguyên nhân là cờ quên tắt: ghi lại trong issue; từ P5 màn hình Controls hiển thị cờ đang bật lâu hơn 1 giờ (DOC-36).
