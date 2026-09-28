# RB-11: Dựng lại warehouse từ raw zone

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-11
>
> Thủ tục (không gắn alert) · UC-18, FR-12.3 · Liên quan: DOC-43 §4.3 (đường B), DOC-22 §4 (replay raw zone), DOC-18 §1.4 và §2 (retention, bố cục raw zone), EXP-04

## Khi nào dùng

- Warehouse hỏng hoặc bị xóa và **không có dump dùng được** (nếu có dump: DOC-43 §4.2, đường A, nhanh hơn).
- Cần dựng lại toàn bộ fact bằng logic mới (sửa bug ảnh hưởng nhiều ngày dữ liệu) mà replay từng khoảng trên warehouse hiện tại không đủ, ví dụ đổi cách tính một cột của mọi dòng.
- Đường C của DOC-43 (mất `pg-source`), trừ phần vé.

**Mất gì:** trạng thái vận hành (`ops.dead_letter` và lịch sử xử lý, `alert_event`, cờ, lịch sử job, feedback insight; DOC-43 §1). Fact cũ hơn retention của raw zone (30 ngày) không dựng lại được: vé cũ hơn 30 ngày mất vĩnh viễn trên compose. Thời gian dự kiến: DOC-43 §2.

## Chuẩn bị

1. **Kiểm tra raw zone có dữ liệu cần:**

   ```bash
   make s3-ls P=gtfs.trip_updates/          # dt=... prefixes present for the days to rebuild
   make s3-ls P=ticketing.sale_points.cdc/  # must reach back to the first snapshot (dt of the initial load)
   make s3-ls P=gtfs-static/                # zip of every feed that was ACTIVE in the window
   ```

   `ticketing.sale_points.cdc` có ít thay đổi; nếu snapshot ban đầu đã quá 30 ngày và bị lifecycle xóa thì điểm bán chỉ còn các thay đổi gần đây. Khi đó `dim_sale_point` được tạo bằng placeholder `INFERRED` từ giao dịch (DOC-20 §4.5) và cần snapshot lại connector để có thuộc tính đầy đủ (RB-09 nhánh D bước 3, không cần xóa slot nếu slot còn tốt: chỉ `stop`, `DELETE offsets`, `resume`).
2. **Xác định feed** đã ACTIVE trong khoảng cần dựng lại. Nếu warehouse cũ còn đọc được: `SELECT feed_hash, activated_at, retired_at FROM dw.gtfs_feed_version WHERE status IN ('ACTIVE','RETIRED') ORDER BY activated_at;`. Nếu không: `manifest.json` của bản dump gần nhất (DOC-43 §3.1) ghi `feed_version` ACTIVE, hoặc lấy zip mới nhất trong `gtfs-static/`.
3. **Chốt khoảng thời gian** theo giờ record Kafka (giờ thật UTC, DR-70): `FROM` = ngày cũ nhất cần dựng (tối đa 30 ngày trước). `TO` = thời điểm chạy `reset-warehouse` ở bước Thực hiện 1 (`t_reset`) cộng 1 phút: dữ liệu sau mốc này do `etl-stream` ghi trực tiếp. `TO` phải cách hiện tại ≥ 10 phút (DR-70), nên yêu cầu của ngày gần nhất chỉ tạo được sau `t_reset` + 11 phút.
4. `make backup` nếu warehouse cũ còn đọc được (để còn đường quay lại).
5. Silence `ConsumerLagHigh`, `DlqRateHigh`, `DataQualityCheckFailed`, `EndToEndLatencyHigh` tối đa 4 giờ, comment `RB-11 <issue>`. Gia hạn nếu replay kéo dài.

## Thực hiện

1. **Tạo lại database và nạp feed:**

   ```bash
   PTI_GTFS_BOOTSTRAP_LOCATION=s3://raw/gtfs-static/<sha256>.zip make reset-warehouse
   ```

   `make reset-warehouse` dừng app, drop và tạo lại `pti_warehouse`, chạy `db-migrate`, rồi khởi động lại app (DOC-39 §6). `GtfsBootstrapRunner` nạp feed từ raw zone (khoảng 3 phút). Offset Kafka giữ nguyên, nên `etl-stream` ghi tiếp dữ liệu trực tiếp ngay khi feed ACTIVE.
2. **Kiểm tra feed:** đúng một phiên bản `ACTIVE` với hash mong muốn (RB-05, Kiểm tra 1).
3. **Tạo partition quá khứ:** `make ensure-partitions FROM=<ngày của FROM>`. Không làm bước này thì dữ liệu cũ rơi vào partition DEFAULT (DQ-21).
4. **Replay theo thứ tự**, mỗi yêu cầu một khoảng tối đa 1 ngày, **từ mới về cũ**:

   ```bash
   # 1) sale points first: sales reference them
   make replay SOURCE=TICKETING_SALE_POINTS FROM=<FROM> TO=<TO>

   # 2) then, per day D from newest to oldest (can run in parallel for the two sources)
   make replay SOURCE=TICKETING_SALES      FROM=<D>T00:00:00Z TO=<D+1>T00:00:00Z
   make replay SOURCE=GTFS_RT_TRIP_UPDATE  FROM=<D>T00:00:00Z TO=<D+1>T00:00:00Z

   # 3) vehicle positions last, only within the VP retention (compose: 3 days)
   make replay SOURCE=GTFS_RT_VEHICLE_POSITION FROM=<D>T00:00:00Z TO=<D+1>T00:00:00Z
   ```

   Mỗi lệnh in `id` của `ops.replay_request`. Mỗi nguồn chỉ có **một** replay raw zone đang chờ hoặc chạy (index `replay_request_one_raw_per_source`, DOC-15), và mỗi yêu cầu tối đa 7 ngày; lệnh thứ hai cho cùng nguồn bị DB từ chối. Vì vậy chạy tuần tự trong từng nguồn bằng `WAIT=1`, song song giữa các nguồn (mỗi nguồn một terminal hoặc một vòng lặp `for` riêng):

   ```bash
   for d in 2026-10-05 2026-10-04 2026-10-03; do   # newest first
     next=$(date -u -j -v+1d -f %F "$d" +%F)   # macOS; on Linux: date -u -d "$d +1 day" +%F
     make replay SOURCE=GTFS_RT_TRIP_UPDATE FROM=${d}T00:00:00Z TO=${next}T00:00:00Z WAIT=1 || break
   done
   ```

   `etl-batch` chạy tối đa `pti.batch.executor.max-size` (3) job cùng lúc (DOC-19 §3.3). Bước 1 (điểm bán) phải `DONE` trước bước 2.
5. **Nhiều feed trong khoảng:** nếu feed đã đổi trong khoảng cần dựng, làm lần lượt cho từng feed, cũ trước (DOC-43 §4.3): kích hoạt feed cũ (RB-05 §"Kích hoạt lại feed cũ"; sau `reset-warehouse` thì phiên bản cũ chưa có, nên dùng lệnh không có `allowReactivate` để nạp mới), replay các ngày của nó, rồi kích hoạt lại feed mới và replay phần còn lại. Trong lúc feed cũ ACTIVE, dữ liệu trực tiếp bị kiểm theo feed cũ: pause GTFS-rt bằng cờ (`make flag KEY=etl.consumer.gtfs-rt.paused VALUE=true`) và nhớ tắt sau cùng.
6. **Theo dõi:**

   ```sql
   SELECT id, source, from_ts, to_ts, status, stats->>'lines_read' AS lines_read, stats->>'written' AS written,
          stats->>'skipped' AS skipped, finished_at
   FROM ops.replay_request WHERE kind = 'RAW_RANGE' ORDER BY requested_at DESC LIMIT 50;
   ```

   `FAILED`: RB-01 (nhánh A với `make job-restart` cho `RawZoneReplayJob`). `skipped` cao bất thường (> 1% của `lines_read`): xem DLQ theo `batch_id` (RB-04), thường là feed không khớp (bước 5).
7. **Từ P4, tính lại insight:** giữ các lệnh ở bước 4 **không** có `RECOMPUTE=true` (nếu không, mỗi ngày replay tính lại cùng các tuyến nhiều lần, DOC-23 §11.5). Khi mọi replay đã `DONE`, chạy `AnalyticsRecomputeJob` cho toàn khoảng, chia thành các đoạn tối đa 7 ngày và chạy tuần tự, cũ trước:

   ```bash
   make job-run NAME=AnalyticsRecomputeJob PARAMS='fromTs=2026-09-01T00:00:00Z,toTs=2026-09-08T00:00:00Z' WAIT=1
   ```

   Theo dõi bằng `pti_analytics_recompute_rows_total` hoặc `ops.job_request`. ETA và OTP có job định kỳ riêng nên có thể bỏ khỏi `detectors`; bảng ETA được tính lại đầy đủ ở lần chạy hằng giờ kế tiếp. Trước P4 chưa có insight nên bỏ bước này.

## Trên k3d

**Dựng lại warehouse.** Không có `make reset-warehouse` cho k3d. Thay bằng:

1. Dừng ghi: pause KEDA `etl-stream` và `triage-worker` về 0, `kubectl -n pti scale deployment etl-batch api --replicas=0` (README §2.1).
2. `kubectl -n pti delete cluster pti-warehouse` (xóa cả PVC), rồi `make k8s-apply`: CNPG tạo lại cluster rỗng, hook `db-migrate` chạy lại migration và grant.
3. Làm tiếp từ bước `ensure-partitions` của phần "Thực hiện" với `PTI_ENV=k3d`. Raw zone nằm trên SeaweedFS trong cluster nên vẫn còn; `make k8s-down` thì mất cả raw zone, không dùng cho mục đích này.
4. Bật lại app bằng cách xóa annotation pause và `make k8s-apply`.

**`BackupJobFailed`** (warning, chỉ k3d, DOC-40 §12):

1. `kubectl -n pti get jobs -l app.kubernetes.io/name=pg-backup`; log của lần lỗi: `kubectl -n pti logs job/<job>`.
2. Nguyên nhân hay gặp: SeaweedFS không chạy (`kubectl -n pti get pods seaweedfs-0`); credential `admin` S3 đã xoay mà Secret chưa cập nhật (RB-12); `pg_dump` không kết nối được `pti-warehouse-rw` (RB-08).
3. Sửa rồi chạy lại ngay: `kubectl -n pti create job --from=cronjob/pg-backup pg-backup-manual-$(date +%s)`, chờ `Complete`, rồi xóa Job lỗi (`kubectl -n pti delete job <job lỗi>`); alert hết khi không còn Job thất bại.
4. Xác nhận: `make s3-ls PTI_ENV=k3d P=backup/` có thư mục mới kèm `manifest.json`; Job `pg-backup-verify` kế tiếp `Complete`.

## Xác nhận đã xong

Theo DOC-43 §5, cụ thể:

- Mọi `ops.replay_request` của lần dựng lại ở `DONE`.
- `SELECT count(*) FROM dw.fact_vehicle_position_default` (và các bảng DEFAULT khác) = 0.
- Số dòng theo ngày hợp lý so với ngày tương tự (ví dụ `fact_trip_update` mỗi ngày phục vụ khoảng 345 nghìn dòng ở tải nền, theo DOC-10 §3.2 và DOC-14 §7.1; ngày chạy tạo tải gấp N lần thì so với ngày có cùng hệ số tải); không có ngày trống giữa khoảng.
- Nếu có checksum trước sự cố (EXP-04 hoặc bản export): chạy `experiments/sql/checksum/*.sql` trên khoảng đã dựng và so khớp.
- Dữ liệu trực tiếp đang vào (`GET /api/v1/system/freshness` từ P4-16, hoặc `max(event_timestamp)` < 60 giây trước); cờ pause đã tắt.

## Phòng ngừa và việc sau sự cố

- Ghi thời gian thực tế của từng bước vào DOC-43 §6 để thay số ước lượng RTO.
- Nếu phải dựng lại vì mất warehouse mà không có dump: kiểm tra lịch `make backup` (compose) hoặc CronJob backup (k3d, DOC-40).
