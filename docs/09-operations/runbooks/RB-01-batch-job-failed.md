# RB-01: Job batch thất bại

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-01
>
> Alert: `BatchJobFailed` (critical), `ReplayFailed` (warning), `BatchExecutionRecovered` (info) · Dashboard: `pti-batch` · Liên quan: DOC-19 §7, DOC-22 §3–4, DOC-30 §2

## Triệu chứng và ảnh hưởng

- `BatchJobFailed`: một job batch (không phải replay) kết thúc `FAILED` trong 10 phút qua. Tùy job:
  - `GtfsStaticLoadJob`: feed mới không được kích hoạt, feed cũ vẫn chạy; nếu là lần nạp đầu thì `etl-stream` không nhận GTFS-rt (xem thêm RB-05).
  - `PartitionMaintenanceJob`: partition ngày tới chưa được tạo; nếu kéo dài quá 7 ngày, dữ liệu rơi vào DEFAULT (DQ-21).
  - Job analytics (từ P4): insight của khoảng đó thiếu cho tới khi chạy lại.
  - Job dọn dẹp: bảng phình dần, không ảnh hưởng ngay.
- `ReplayFailed`: yêu cầu replay (`DLQ_RECORD` hoặc `RAW_RANGE`) kết thúc `FAILED`. Người yêu cầu thấy trạng thái trên Ops console.
- `BatchExecutionRecovered`: `StaleExecutionRecoverer` phát hiện execution mồ côi (pod chết giữa chừng) và đã đánh dấu `FAILED`. Job theo lịch được **tự restart**; job replay thì không (DOC-19 §7.2).

## Kiểm tra

1. Execution thất bại gần nhất:

   ```sql
   SELECT e.job_execution_id, i.job_name, e.status, e.exit_code, left(e.exit_message, 400) AS exit_message,
          e.start_time, e.end_time
   FROM batch.batch_job_execution e JOIN batch.batch_job_instance i USING (job_instance_id)
   WHERE e.status IN ('FAILED', 'STOPPED') ORDER BY e.create_time DESC LIMIT 10;
   ```

2. Step thất bại và số đếm:

   ```sql
   SELECT step_name, status, read_count, write_count, process_skip_count + write_skip_count AS skips,
          rollback_count, left(exit_message, 400)
   FROM batch.batch_step_execution WHERE job_execution_id = :id ORDER BY step_execution_id;
   ```

3. Log: `{service="etl-batch", level=~"WARN|ERROR"} | json | job="<job_name>"` quanh `end_time`. Dòng `ERROR` cuối cùng có `pti_error_kind` và lớp exception.
4. Với replay: `SELECT id, kind, source, status, message, stats FROM ops.replay_request WHERE status = 'FAILED' ORDER BY finished_at DESC LIMIT 10;`
5. Xếp loại theo `exit_message` / `pti_error_kind` (DOC-30 §2):

   | Dấu hiệu | Loại | Nhánh xử lý |
   | --- | --- | --- |
   | `STALE` | Pod chết giữa chừng | A |
   | `SQLState 08…`, `57P…`, `Connection refused`, `S3Exception 5xx`, hết lượt retry | `TRANSIENT_INFRA` | B |
   | `SkipLimitExceededException` / `Skip ratio … exceeded` | Dữ liệu lỗi quá ngưỡng 20% (DR-23) | C |
   | `42501`, `42P01`, `NullPointerException`, `IllegalStateException`… | `FATAL` (lỗi lập trình, migration, quyền) | D |
   | `NoSuchKey`, `AccessDenied` (S3) | `FATAL` do tham số hoặc quyền | D |

## Xử lý

**A. Execution mồ côi (`STALE`).**
- Job theo lịch: đã được tự restart; kiểm tra execution mới `COMPLETED`. Không làm gì thêm.
- `RawZoneReplayJob`: quyết định có chạy tiếp không. Có → `make job-restart ID=<job_execution_id>` (tiếp tục từ object và dòng đã commit, DOC-22 §4.7). Không → để `FAILED`.
- `DlqReplayJob`: không restart; tạo yêu cầu replay mới cho dead letter đó (DOC-22 §3).

**B. Lỗi hạ tầng.** Sửa hạ tầng trước (RB-08 cho Postgres, `make ps` / `make restart S=seaweedfs` cho S3). Sau đó:
- job theo lịch: `make job-restart ID=<id>`, hoặc chờ lần chạy theo lịch kế tiếp nếu job idempotent theo ngày (`PartitionMaintenanceJob`, job dọn dẹp);
- `GtfsStaticLoadJob`: `make job-restart ID=<id>` (tiếp tục từ step lỗi, DOC-21 §3);
- replay: như nhánh A.

**C. Quá ngưỡng skip.** Replay hoặc job đã gặp hơn 20% record lỗi, thường là do logic sai hoặc feed đổi (DOC-22 §4.4).
1. Xem phân loại lỗi của lần chạy: `SELECT stage, rule_id, count(*) FROM ops.dead_letter WHERE batch_id = :batch_id GROUP BY 1, 2 ORDER BY 3 DESC;` (`batch_id` từ `ops.etl_batch_step` của execution).
2. Nếu là `DQ-03`/`DQ-04` hàng loạt khi replay dữ liệu cũ: feed ACTIVE khác feed lúc dữ liệu được sinh. Kích hoạt lại feed cũ (RB-05 §"Kích hoạt lại feed cũ"), replay, rồi kích hoạt lại feed mới.
3. Nếu là lỗi logic: sửa code, deploy, rồi tạo replay mới (không restart execution cũ, vì bản cũ đã ghi một phần DLQ với lỗi sai).

**D. Lỗi `FATAL`.** Không restart khi chưa sửa nguyên nhân (restart sẽ lỗi lại).
1. Lỗi quyền hoặc bảng thiếu: kiểm tra `db-migrate` đã chạy hết (`SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;`) và `R__grants` (DOC-17 §7).
2. Lỗi lập trình: mở issue kèm `trace_id`, sửa, deploy, rồi `make job-restart ID=<id>`.
3. Tham số sai (`sourceUri` không tồn tại): không restart; chạy lại với tham số đúng (`make job-run`).

## Trên k3d

- Lệnh `make job-run`, `job-restart`, `job-stop`, `psql-wh` thêm `PTI_ENV=k3d` (README §2.1). Log: `make k8s-logs S=etl-batch`; execution chết theo pod: `kubectl -n pti logs <pod> --previous`.
- `STALE` trên k3d thường do pod bị kill khi rolling update, OOMKilled hoặc bị evict. Xem lý do bằng `kubectl -n pti get pod -l app.kubernetes.io/name=etl-batch -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].lastState.terminated.reason}{"\n"}{end}'`. Với `staging` (2 pod `etl-batch`), pod còn sống phát hiện execution mồ côi theo DOC-19 §7.2; với `lite` (1 pod), pod mới làm việc đó khi khởi động.
- S3 lỗi (nhánh B): `kubectl -n pti rollout restart statefulset/seaweedfs`, chờ `Ready`.
- Phòng ngừa OOM: tăng `resources.limits.memory` của `etl-batch` trong values môi trường (DOC-40 §5.3) rồi `make k8s-apply`.

## Xác nhận đã xong

- Execution mới (hoặc restart) `COMPLETED`; với replay: `ops.replay_request.status = 'DONE'`.
- `increase(spring_batch_job_seconds_count{spring_batch_job_status="FAILED"}[10m]) == 0` và alert đã `resolved` trong Mailpit.
- Với `PartitionMaintenanceJob`: `SELECT count(*) FROM dw.fact_vehicle_position_default` = 0 và partition của 7 ngày tới tồn tại.

## Phòng ngừa và việc sau sự cố

- Lỗi `FATAL` lặp lại ở cùng job: thêm test tái hiện (B-xx trong DOC-19 §12) trước khi đóng issue.
- `STALE` thường xuyên: kiểm tra OOM (`docker inspect pti-etl-batch-1 --format '{{.State.OOMKilled}}'`) và `mem_limit` (DOC-10 §5).
- Replay dữ liệu cũ sau khi đổi feed: luôn kiểm tra feed ACTIVE trước (RB-11).
