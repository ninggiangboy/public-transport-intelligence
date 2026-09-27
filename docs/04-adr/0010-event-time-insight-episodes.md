# ADR-0010: Khóa insight theo event time, mô hình episode, UUIDv5

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-29, DR-30, DR-31, DR-67, ADR-0003, ADR-0013, DOC-15 §6, DOC-23 §2, FR-05, FR-06, FR-12, EXP-04

## Bối cảnh

SDD gốc lưu insight (bunching, gián đoạn) với UNIQUE chứa `detected_at`, lấy theo giờ đồng hồ lúc phát hiện. Cách này gây ba vấn đề:

1. **Replay không idempotent.** Nạp lại cùng dữ liệu (raw replay, RB-11, EXP-04) sinh `detected_at` mới, nên sinh dòng mới thay vì ghi đè. Kết quả của pipeline phụ thuộc vào lúc chạy, trái với ADR-0003.
2. **Một sự kiện kéo dài thành nhiều dòng.** Hai xe dồn cục 10 phút được đánh giá lại ở mỗi micro-batch, mỗi lần một dòng. UI, alert và phản hồi của operator không có một đối tượng ổn định để bám vào.
3. **Đồng hồ nghiệp vụ lệch giờ máy.** Simulator và ETL chạy theo `businessNow` (DR-67), giờ máy thì khác 12–13 giờ; trộn hai trục làm cửa sổ và watermark sai.

Yêu cầu: tính lại một khoảng thời gian bất kỳ cho ra đúng những dòng đã có (cùng id, cùng giá trị), và phản hồi của operator gắn vào dòng đó không bị mất.

## Các phương án

1. **Giữ `detected_at` theo giờ máy, xóa rồi chèn lại khi replay.** Id đổi sau mỗi lần tính lại, làm mất liên kết từ `alert_event`, phản hồi dispatch và URL mà người dùng đã lưu.
2. **Mỗi lần đánh giá một dòng (dạng sự kiện), gộp khi đọc.** Bảng lớn nhanh, truy vấn phức tạp, không có trạng thái mở/đóng để alert dựa vào.
3. **Episode theo event time, id sinh tất định từ khóa tự nhiên.** Một dòng cho mỗi sự kiện có `episode_start`, `episode_end`, `status OPEN|CLOSED` và các giá trị đỉnh; khóa tự nhiên chứa `episode_start` theo event time; `id = UUIDv5(namespace, khóa tự nhiên)`.

## Quyết định

Chọn **phương án 3**.

- Mọi insight dùng **event time**. Thời điểm hiện tại chỉ dùng ở nhánh idle của watermark, mốc chạy của job và các cột audit (`resolved_at`, `created_at`), theo DOC-23 §2.
- Bunching và gián đoạn lưu theo **episode**. UNIQUE:
  - `insight_bus_bunching (route_id, vehicle_leader, vehicle_follower, episode_start)`; cặp xe chuẩn hóa leader/follower theo tiến độ trên tuyến.
  - `insight_service_disruption (route_id, direction_id, episode_start)`.
- Mở và đóng episode có hysteresis (DR-30, DR-31); episode dài quá `max-episode-duration` (3 giờ) bị đóng cưỡng bức. Giới hạn này cũng cho phép truy vấn chồng lấn thời gian dùng `episode_start >= from − 3h` (DOC-32).
- `id = UUIDv5(NAMESPACE, "<loại>|<các phần của khóa>|<episode_start ISO>")`, với `NAMESPACE = UUIDv5(NAMESPACE_URL, "urn:pti:insight")` là hằng số không bao giờ đổi (DOC-23 §2.3). Các insight khác (dispatch suggestion, ticketing anomaly) theo cùng quy tắc với khóa tự nhiên của chúng.
- Ghi bằng upsert theo UNIQUE. Tính lại một khoảng thời gian thì xóa episode bắt đầu trong khoảng đó rồi tính lại; dòng tái tạo có cùng id, và cột phản hồi của operator được giữ nguyên (DOC-15 §6, DOC-23 §11).

## Hệ quả

**Tích cực**

- Replay và tính lại cho ra đúng những dòng cũ; EXP-04 kiểm được bằng so sánh checksum (tiêu chí C5).
- Một đối tượng ổn định cho UI, alert (`dedup_key = 'disruption:' || id`) và phản hồi của operator.
- Bảng nhỏ: một dòng mỗi episode thay vì một dòng mỗi lần đánh giá.

**Tiêu cực**

- Analytics phải giữ trạng thái giữa các lần đánh giá (`analytics_bunching_pair_state`, `analytics_route_baseline`), và trạng thái này cũng phải tính lại được khi replay.
- Episode đang mở thay đổi theo thời gian, nên người đọc phải xử lý `status = OPEN` với `episode_end` rỗng.
- Đổi định dạng chuỗi khóa hay namespace làm thay đổi toàn bộ id; có test cố định giá trị (DOC-23).
