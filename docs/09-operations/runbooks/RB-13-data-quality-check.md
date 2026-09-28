# RB-13: Kiểm tra chất lượng dữ liệu sau khi ghi thất bại hoặc không chạy

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-13
>
> Alert: `DataQualityCheckFailed` (warning, bắn ngay), `DataQualityCheckStale` (warning, 5 phút) · Dashboard: `pti-batch` (DQ) · Liên quan: DOC-16 §3–4 (DQ-20…27), DOC-14 §7.4 (partition), DOC-19 (`DataQualityJob`)

## Triệu chứng và ảnh hưởng

- `DataQualityCheckFailed{rule}`: lần chạy gần nhất của rule post-write vượt ngưỡng (DOC-16 §3). Dữ liệu **đã nằm trong warehouse** và có thể sai hoặc thiếu nhất quán; pipeline vẫn chạy.
- `DataQualityCheckStale{rule}`: rule không chạy quá 3 lần chu kỳ của nó. Có thể có vi phạm mà không ai biết. Thường do `etl-batch` dừng hoặc `DataQualityJob` bị kẹt.
- DQ-26 có severity 0 nên không làm alert bắn; DQ-27 chạy theo step, không có lịch nên không gây `Stale` (DOC-16 §4).

## Kiểm tra

1. Kết quả gần nhất của rule và mẫu vi phạm:

   ```sql
   SELECT rule_id, scope, table_name, batch_id, violation_count, jsonb_pretty(sample), checked_at
   FROM ops.dq_check_result WHERE rule_id = :rule ORDER BY checked_at DESC LIMIT 5;
   ```

   Vi phạm tăng dần theo thời gian hay chỉ một lần? Mẫu (tối đa 10 khóa) chỉ ra dòng cụ thể.
2. Với `Stale`:
   - `make ps`: `etl-batch` có chạy không?
   - Log `{service="etl-batch"} | json | job="DataQualityJob"`: lỗi hoặc `statement timeout` (`pti_dq_check_errors_total{rule}` tăng).
   - ShedLock: `SELECT name, lock_until, locked_at, locked_by FROM ops.shedlock WHERE name = 'dataQuality';`. `lock_until` ở tương lai xa hơn 4 phút là bất thường.
   - `pti.dq.post-write.enabled` có bị đặt `false` không (`docker compose exec etl-batch env | grep PTI_DQ`).

## Xử lý theo rule

| Rule | Ý nghĩa | Nguyên nhân thường gặp | Việc |
| --- | --- | --- | --- |
| DQ-20 | Dòng fact có `batch_id` không có trong lineage | Ghi tay bằng SQL; lineage bị xóa sớm (retention `etl_stream_batch` bị giảm dưới 24 giờ) | Tìm dòng theo mẫu, xác định ai ghi (log Postgres, `pg_stat_activity` lúc đó). Dòng ghi tay: xóa hoặc thay bằng replay có lineage. Sửa cấu hình retention |
| DQ-21 | Partition DEFAULT có dòng | `PartitionMaintenanceJob` không chạy (RB-01); replay ngày quá khứ mà quên `make ensure-partitions`; dữ liệu có `service_date` quá xa (lẽ ra bị DQ-09 chặn) | `make ensure-partitions FROM=<ngày nhỏ nhất trong DEFAULT>`: hàm tạo partition và chuyển dòng ra khỏi DEFAULT (DOC-14 §7.4). Tìm ngày: `SELECT service_date, count(*) FROM dw.fact_vehicle_position_default GROUP BY 1;` (tương tự cho bảng khác, `sale_date` với vé) |
| DQ-22 | Refund mà giao dịch gốc không tới sau 5 phút | Kịch bản `refund-burst` có xóa bản gốc ở nguồn (mong đợi, DOC-25 §7.7); connector bị gián đoạn (RB-09); bản gốc vào DLQ | Kiểm tra bản gốc ở nguồn: `make psql-src Q="SELECT transaction_id, created_at FROM ticket_transaction WHERE transaction_id = '<refund_of>'"`. Có ở nguồn mà không có trong warehouse → tìm trong DLQ (`SELECT … FROM ops.dead_letter WHERE source = 'TICKETING_SALES' AND raw_payload LIKE '%<id>%'`), hoặc replay `TICKETING_SALES` quanh `created_at`. Không có ở nguồn → ghi nhận (dữ liệu nguồn không nhất quán) |
| DQ-23 | TripUpdate trỏ `(trip_id, stop_sequence)` không có trong feed ACTIVE | Feed đổi trong ngày (dữ liệu hôm qua theo feed cũ); simulator dùng feed khác warehouse (RB-05) | So `feed.sha256` của `make sim-status` với feed ACTIVE. Khác → RB-05 nhánh B. Nếu vừa đổi feed có chủ đích: vi phạm tự hết sau 2 ngày phục vụ; silence tới lúc đó |
| DQ-24 | `is_observed = true` nhưng thời gian quan sát ở tương lai so với event | Lỗi logic trong processor TripUpdate (DR-13) | Lỗi lập trình: mở issue với mẫu; sửa, deploy, replay `GTFS_RT_TRIP_UPDATE` cho các ngày bị ảnh hưởng (`make replay`, guard `:replay` ghi đè) |
| DQ-25 | `vehicle_position_latest` mới hơn fact của cùng xe | Sau khi khôi phục từ dump (đường A): dump có `vehicle_position_latest` nhưng không có `fact_vehicle_position` (DOC-43 §3.1); lỗi writer | Đang ở đường A: mong đợi, tự hết khi replay VP xong (DOC-43 §4.2 bước 8). Ngoài ra: lỗi lập trình, mở issue |
| DQ-27 | Số dòng ghi của step batch không khớp số dòng có `batch_id` đó | Lỗi đếm của writer, hoặc guard chặn mà không trừ | Mở issue với `batch_id` (mẫu); so `batch.batch_step_execution.write_count` với `SELECT count(*) … WHERE batch_id = …`. Dữ liệu không sai nếu chênh là do đếm |

**Tạm tắt một rule** khi đã hiểu nguyên nhân và đang chờ sửa: `PTI_DQ_RULES_DQ_23_ENABLED=false` trong `.env` rồi `make up`; rule tắt không phát gauge nên không gây `Stale` (DOC-16 §4). Ghi rõ trong issue và bật lại khi xong.

## Xử lý `DataQualityCheckStale`

| Nguyên nhân | Việc |
| --- | --- |
| `etl-batch` dừng | `make restart S=etl-batch`; lỗi khởi động → RB-03 / RB-08 |
| Rule timeout (`statement timeout`) | Câu SQL chậm trên dữ liệu hiện tại: xem kế hoạch (`EXPLAIN (ANALYZE, BUFFERS)` với `:now` thay bằng giá trị cụ thể), kiểm tra lọc theo cột partition (DOC-16 §3 quy ước). Sửa SQL hoặc index qua migration |
| Lock ShedLock kẹt (pod chết khi giữ lock) | Lock tự hết sau `lockAtMostFor` (4 phút). Nếu vẫn kẹt: `UPDATE ops.shedlock SET lock_until = now() WHERE name = 'dataQuality';` (lệnh ghi tay được phép duy nhất ở runbook này) |
| `pti.dq.post-write.enabled=false` | Bật lại nếu không có lý do tắt |

## Trên k3d

- `make psql-wh`, `psql-src`, `ensure-partitions`, `replay`, `sim-status` thêm `PTI_ENV=k3d`.
- `DataQualityCheckStale`: xem pod `etl-batch` (`kubectl -n pti get pods -l app.kubernetes.io/name=etl-batch`), `CrashLoopBackOff` hoặc `Pending`; khởi động lại bằng `kubectl -n pti rollout restart deployment/etl-batch`. Với `staging` có 2 pod, lịch chạy được giữ bằng ShedLock nên chỉ một pod chạy `DataQualityJob`; kiểm `ops.shedlock`.

## Xác nhận đã xong

- Lần chạy kế tiếp của rule có `violation_count` dưới ngưỡng; `pti_dq_check_breached{rule} == 0`; alert `resolved`.
- Với `Stale`: `time() - pti_dq_check_last_run_timestamp_seconds{rule} < pti_dq_check_interval_seconds{rule}`.
- DQ-21: mọi bảng `dw.fact_*_default` có 0 dòng.

## Phòng ngừa và việc sau sự cố

- Vi phạm do lỗi lập trình (DQ-24, DQ-25, DQ-27): thêm test tái hiện vào bảng test của DOC-16 §8 trước khi đóng issue.
- DQ-21 lặp lại: kiểm tra `PartitionMaintenanceJob` chạy hằng ngày (`pti-batch`), và mọi quy trình replay ngày quá khứ có bước `make ensure-partitions` (RB-11).
