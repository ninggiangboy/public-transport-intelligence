# RB-05: Feed GTFS tĩnh bị từ chối, sắp hết hạn, hoặc không nạp được lần đầu

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-05
>
> Alert: `GtfsFeedRejected` (warning), `GtfsFeedExpiring` (warning, 1 giờ), bootstrap thất bại (qua `BatchJobFailed`) · Dashboard: `pti-batch` · Liên quan: DOC-13, DOC-14 §6, DOC-21, DOC-25 §3.2

## Triệu chứng và ảnh hưởng

- `GtfsFeedRejected`: một lần chạy `GtfsStaticLoadJob` kết thúc `REJECTED` (lỗi GV-01…GV-10, DOC-21 §4). Feed ACTIVE hiện tại **vẫn chạy**; không mất dữ liệu realtime. Feed mới chỉ không được dùng.
- `GtfsFeedExpiring`: `pti_gtfs_active_feed_days_to_expiry < 7`, tức `valid_to` của feed ACTIVE còn dưới 7 ngày theo giờ nghiệp vụ. Hệ thống vẫn chạy sau khi hết hạn nhờ ánh xạ ngày `auto` của simulator (DR-08, DOC-25 §3.2), nhưng lịch không còn là lịch thật của ngày đó. Thực nghiệm phải ở trong khoảng lịch (DOC-45 §1.1).
- **Bootstrap thất bại** (chưa có feed ACTIVE): `etl-stream` không bật listener GTFS-rt (readiness `DOWN`, `ConsumerStopped`), simulator không phát. Hệ thống không có dữ liệu realtime nào. Đây là trường hợp nặng nhất.

## Kiểm tra

1. Trạng thái các phiên bản feed:

   ```sql
   SELECT feed_version_id, left(feed_hash, 12) AS hash, status, valid_from, valid_to,
          loaded_at, activated_at, retired_at, source_uri
   FROM dw.gtfs_feed_version ORDER BY loaded_at DESC LIMIT 10;
   ```

2. Với `REJECTED`: đọc báo cáo kiểm tra.

   ```sql
   SELECT jsonb_pretty(validation_report) FROM dw.gtfs_feed_version WHERE feed_version_id = :id;
   ```

   Mục `errors` có `check` (GV-xx), `count` và tối đa 20 mẫu (DOC-21 §4). Từ P5 xem trên Ops console → Jobs.

3. Với bootstrap: log `{service="etl-batch"} | json | job="GtfsStaticLoadJob"` và execution gần nhất (RB-01 bước 1–2). Các nguyên nhân hay gặp:

   | Dấu hiệu | Nguyên nhân |
   | --- | --- |
   | `NoSuchFileException /feed/…` | Chưa có file zip trong `sample-data/gtfs/` (chưa chạy `git lfs pull`, hoặc volume `/feed` chưa mount) |
   | `sourceUri not allowed` | `PTI_GTFS_BOOTSTRAP_LOCATION` trỏ ra ngoài `pti.gtfs.static.allowed-dirs` hoặc bucket/prefix không cho phép (DOC-21 §1.1) |
   | `SHA-256 mismatch` | `pti.gtfs.static.expected-sha256` khác hash file (file tải hỏng hoặc bị thay) |
   | `NoSuchKey` với `s3://raw/gtfs-static/…` | Raw zone đã mất zip đó (sau `make reset`, hoặc lifecycle) |
   | `REJECTED` | Như mục 2 |
   | Lỗi `TRANSIENT_INFRA` (Postgres) | RB-08; job restart được |

4. Với `GtfsFeedExpiring`: ngày nghiệp vụ hiện tại (`make sim-status` → `businessNow`) và `valid_to` của feed ACTIVE. Trên compose với feed ghim, alert bắn từ ngày nghiệp vụ 2026-11-07 (feed hết lịch 2026-11-13).

## Xử lý

**A. Feed mới bị từ chối.**
1. Lỗi cấu trúc (GV-01…GV-03): feed thiếu file/cột hoặc zip hỏng. Tải lại từ agency; nếu vẫn lỗi thì giữ feed hiện tại và ghi issue.
2. Lỗi nội dung (GV-04…GV-10): đọc mẫu lỗi. Nếu lỗi nằm ở dữ liệu agency (ví dụ trip thiếu `stop_times`) thì **không sửa zip bằng tay**, vì hash đổi và mất tính tái lập. Chờ bản feed kế tiếp của agency.
3. Nếu kiểm tra từ chối nhầm một feed hợp lệ (lỗi ở kiểm tra): sửa kiểm tra, thêm test (DOC-21 §10), deploy, rồi chạy lại: `make job-run NAME=GtfsStaticLoadJob PARAMS='sourceUri=<uri>'`. Dòng `REJECTED` cũ có cùng hash làm job kết thúc `NOOP` (DOC-21 §3.1), nên phải xóa dòng đó trước: `DELETE FROM dw.gtfs_feed_version WHERE feed_version_id = :id AND status = 'REJECTED';` (dòng REJECTED không có dữ liệu lịch; đây là lệnh ghi tay duy nhất runbook này cho phép).

**B. Feed sắp hết hạn.**
- Compose với feed ghim, ngày nghiệp vụ nằm ngoài lịch **có chủ đích** (thử `GtfsFeedExpiring`, demo ngày khác): silence alert với comment nêu lý do, thời hạn ≤ 4 giờ. Hoặc đưa đồng hồ về trong lịch: `make clock-offset AT=now` với ngày hợp lệ (DOC-38 §3.1).
- Muốn dùng lịch mới: tải feed mới của Metro Transit, đặt vào `sample-data/gtfs/`, cập nhật `SHA256SUMS`, README và bảng DOC-13 §2.1, rồi `make job-run NAME=GtfsStaticLoadJob PARAMS='sourceUri=file:/feed/<file>.zip'`. Feed mới được activate; `etl-stream` nhận bản mới trong ≤ 35 giây (DOC-21 §6.2). Simulator đọc feed từ file lúc khởi động và không theo feed ACTIVE, nên đồng thời đặt `PTI_SIM_FEED_LOCATION=file:/data/gtfs/<file>.zip` và `PTI_SIM_FEED_SHA256=<sha256>` trong `.env`, rồi `make up` (tạo lại container simulator). Sau đó chạy lại G-03 cho feed mới trước khi dùng cho thực nghiệm.
- Môi trường có `pti.gtfs.static.source` (lịch hằng ngày): kiểm tra job 03:30 có chạy không (`pti-batch`), vì feed agency thường được cập nhật trước khi hết hạn.

**C. Bootstrap thất bại.**
1. Sửa nguyên nhân theo bảng ở bước Kiểm tra 3 (đặt file vào `sample-data/gtfs/`, sửa biến trong `.env`).
2. `make restart S=etl-batch`: `GtfsBootstrapRunner` chạy lại vì vẫn chưa có feed ACTIVE (DOC-21 §1). Hoặc `make gtfs-load` nếu không muốn restart.
3. Khi feed ACTIVE, `etl-stream` tự bật listener GTFS-rt trong ≤ 35 giây (DOC-20 §6). Simulator nạp feed từ file riêng của nó (DOC-25 §4.1); nếu simulator cũng lỗi vì thiếu file thì `make restart S=source-simulator` sau khi đặt file.

### Kích hoạt lại feed cũ

Dùng khi feed mới activate xong mới phát hiện sai (DQ-03/DQ-04 tăng vọt, RB-04), hoặc khi cần replay dữ liệu cũ theo feed của thời điểm đó (RB-01 nhánh C, RB-11).

1. Tìm hash của phiên bản cần quay về: truy vấn ở bước Kiểm tra 1 (`status = 'RETIRED'`). Chỉ còn `pti.gtfs.static.keep-versions` = 3 phiên bản gần nhất (DOC-21 §5).
2. Kích hoạt lại:

   ```bash
   make job-run NAME=GtfsStaticLoadJob \
     PARAMS='sourceUri=s3://raw/gtfs-static/<sha256>.zip,allowReactivate=true'
   ```

   Job tìm thấy hash `RETIRED`, đi nhánh `REACTIVATE`: activate lại phiên bản đó (không nạp lại dữ liệu), rồi retire phiên bản đang ACTIVE (DOC-21 §3.1). Metric `pti_gtfs_load_total{outcome="reactivated"}` tăng 1.
   Nếu hệ thống đang nhận realtime (không phải chỉ replay), đổi luôn feed của simulator như nhánh B để hai bên khớp nhau.
3. Nếu phiên bản đó đã bị xóa khỏi warehouse (cũ hơn 3 bản): cùng lệnh, bỏ `allowReactivate`. Job không thấy hash nên nạp lại đầy đủ từ zip trong raw zone (khoảng 3 phút).
4. Quay lại feed mới sau khi xong việc: lặp lại bước 2 với hash của feed mới.

Không sửa `status` trong `dw.gtfs_feed_version` bằng SQL: bỏ qua `loadVehicles`, bỏ qua cache tham chiếu của `etl-stream` và simulator, và có thể để lại hai feed ACTIVE.

## Trên k3d

- File feed nằm ở `sample-data/gtfs/` trên máy, được mount vào node k3d và pod (`/feed`) như compose (DOC-40 §3.1), nên đường dẫn `file:/feed/<file>.zip` giữ nguyên. File mới chép vào thư mục đó thấy được ngay trong pod.
- `make job-run`, `gtfs-load`, `sim-status`, `psql-wh` thêm `PTI_ENV=k3d`. Khởi động lại: `kubectl -n pti rollout restart deployment/etl-batch` hoặc `deployment/source-simulator`.
- Đổi đồng hồ nghiệp vụ: `make k8s-clock-offset AT=…` thay cho `make clock-offset` rồi `make up`.
- Kiểm tra nhánh C: port-forward như RB-03 mục "Trên k3d".

## Xác nhận đã xong

- Đúng một dòng `status = 'ACTIVE'` trong `dw.gtfs_feed_version`, và đó là phiên bản mong muốn.
- `pti_etl_reference_feed_version` của `etl-stream` bằng `feed_version_id` đó; `feed.sha256` trong `make sim-status` khớp hash (trừ khi chủ ý để lệch trong lúc replay).
- `pti_gtfs_active_feed_days_to_expiry ≥ 7` (nhánh B), hoặc silence có thời hạn đang hoạt động.
- Nhánh C: `curl -s localhost:9082/actuator/health/sources | jq` trả `UP` cho `source-gtfs-rt`; bản đồ có xe.

## Phòng ngừa và việc sau sự cố

- Mỗi lần đổi feed ghim phải chạy G-03 và cập nhật con số trong DOC-13.
- Bootstrap thất bại lúc dựng môi trường mới: thêm kiểm tra file feed vào `make doctor` nếu nguyên nhân là thiếu file LFS.
- Sau mỗi lần kích hoạt lại feed cũ, ghi vào issue lý do và thời điểm, vì replay sau đó kiểm tra theo feed ACTIVE (DOC-22 §4.4).
