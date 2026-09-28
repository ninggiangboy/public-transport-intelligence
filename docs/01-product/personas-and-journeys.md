# Persona và hành trình người dùng

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-02
> Phụ thuộc: [DOC-01](vision-and-scope.md), [Glossary](../02-glossary.md)

Tên persona và các câu trích dẫn bên dưới chỉ dùng để minh họa. Các chuỗi nằm trong ngoặc kép kiểu `"…"` là chuỗi thật sẽ hiện trên UI (tiếng Anh, DR-61).

## 1. Tổng quan

| ID | Persona | Role trong hệ thống | Thiết bị chính | Mức kỹ thuật | Tần suất dùng |
| --- | --- | --- | --- | --- | --- |
| PS-1 | Hành khách | `anonymous` | Điện thoại | Thấp | Vài lần mỗi ngày, mỗi lần dưới 1 phút |
| PS-2 | Điều phối viên | `operator` | Desktop, 2 màn hình | Trung bình | Liên tục trong ca 8 giờ |
| PS-3 | Quản lý tuyến | `viewer` | Laptop | Trung bình | Hằng tuần |
| PS-4 | Kỹ sư dữ liệu | `operator` | Laptop | Cao | Hằng ngày, và khi có alert |
| PS-5 | Người đánh giá / researcher | `operator` | Laptop, máy chiếu | Cao | Khi chạy thực nghiệm và khi demo |

## 2. Chi tiết persona

### PS-1 · Hành khách: "Maya, đi làm bằng Route 21"

- **Mục tiêu:** biết chính xác xe tiếp theo đến trạm lúc mấy giờ; biết sớm khi tuyến có sự cố để đổi phương án.
- **Nỗi đau:** app hiện giờ theo lịch, trong khi xe trễ 12 phút; không biết con số ETA đáng tin tới đâu.
- **Cần từ PTI:** màn Stop detail tải nhanh trên mobile; ETA kèm mức tin cậy; dải cảnh báo disruption của tuyến.
- **Không cần:** đăng nhập, số liệu kỹ thuật, cảnh báo nghi là lỗi dữ liệu (những cảnh báo này đã được định tuyến sang ENGINEERING).
- **Màn hình:** Live map (chế độ công khai), Stop detail, Alert feed (audience PUBLIC).

### PS-2 · Điều phối viên: "Daniel, trực bàn điều phối ca chiều"

- **Mục tiêu:** phát hiện sớm bunching và gián đoạn, rồi ra quyết định giữ xe hoặc bỏ trạm.
- **Nỗi đau:** chỉ biết bunching khi tài xế báo qua radio; không có gợi ý nên xử lý tùy theo kinh nghiệm.
- **Cần từ PTI:** Live map tô nổi các cặp bunching; gợi ý điều phối kèm độ tin cậy; nút "Accept" / "Dismiss" để phản hồi; alert disruption kèm nguyên nhân khả dĩ.
- **Màn hình:** Live map (chế độ operator), Alert feed (OPERATIONS).

### PS-3 · Quản lý tuyến: "Priya, quản lý mạng lưới phía nam"

- **Mục tiêu:** biết tuyến nào đúng giờ kém theo thời gian để điều chỉnh lịch.
- **Nỗi đau:** báo cáo OTP phải tự tổng hợp thủ công mỗi tháng.
- **Cần từ PTI:** bảng xếp hạng OTP, biểu đồ trễ theo giờ và theo ngày, lịch sử gián đoạn.
- **Màn hình:** Route scorecard.

### PS-4 · Kỹ sư dữ liệu: "Tom, người trực pipeline"

- **Mục tiêu:** pipeline không mất và không trùng dữ liệu; khi có lỗi thì biết ngay lỗi nào cần xử lý trước.
- **Nỗi đau:** DLQ có hàng nghìn record chưa phân loại; replay phải viết script tay; không biết batch nào ghi ra dòng dữ liệu nào.
- **Cần từ PTI:** Ops console gồm job timeline, DLQ đã được triage, replay bằng một cú bấm, hàng chờ xác nhận auto-replay, replay raw zone theo khoảng thời gian, tạm dừng consumer; Grafana và runbook.
- **Màn hình:** Ops console (Jobs, DLQ, Replay, Ticketing), Alert feed (ENGINEERING), Grafana.

### PS-5 · Người đánh giá / researcher

- **Mục tiêu:** chứng kiến hoặc tái lập các tuyên bố về độ tin cậy.
- **Cần từ PTI:** điều khiển kịch bản simulator trên UI (Demo control); thấy số liệu thay đổi theo thời gian thực; kết quả thực nghiệm có số liệu và biểu đồ.
- **Màn hình:** Demo control, Ops console, Grafana; CLI `pti-exp`.

## 3. Hành trình (journey)

### J-1 · Hành khách kiểm tra xe trước khi ra trạm (PS-1)

| Bước | Hành động | Điểm chạm | Cảm xúc | Yêu cầu |
| --- | --- | --- | --- | --- |
| 1 | Mở link trạm đã lưu trên điện thoại | Stop detail | Vội | Hiện nội dung đầu tiên trong < 2 s trên 4G |
| 2 | Thấy "Route 21 · 6 min · High confidence" | Danh sách arrivals | Yên tâm | ETA kèm mức tin cậy (DR-32) |
| 3 | Thấy dải "Delays on Route 21 (~9 min)" | Banner disruption | Lo | Chỉ hiện cảnh báo PUBLIC |
| 4 | Mở Live map để xem xe đang ở đâu | Live map | Chủ động | Vị trí xe cập nhật mỗi giây, không cần reload |
| 5 | Mạng chập chờn | Stale banner | Hơi khó chịu | Hiện "Last updated 40 s ago" thay vì màn trắng |

### J-2 · Điều phối viên xử lý bunching (PS-2)

| Bước | Hành động | Điểm chạm | Yêu cầu |
| --- | --- | --- | --- |
| 1 | Alert "Bunching on Route 5 (northbound)" xuất hiện | Alert feed, toast | Độ trễ đầu-cuối < 10 s |
| 2 | Bấm vào alert, bản đồ zoom tới cặp xe | Live map | Cặp xe được tô nổi, có badge |
| 3 | Đọc gợi ý "Hold follower at next stop · 82%" | Popover gợi ý | Hiện confidence; nếu < 60% thì ghi "Low confidence" |
| 4 | Bấm "Accept" | Nút feedback | Lưu `operator_feedback`; phản hồi trong < 300 ms |
| 5 | Episode tự đóng khi gap đã hồi phục | Live map | Badge biến mất; lịch sử vẫn còn |

### J-3 · Kỹ sư xử lý DLQ tăng đột biến (PS-4)

| Bước | Hành động | Điểm chạm | Yêu cầu |
| --- | --- | --- | --- |
| 1 | Nhận email alert "DLQ growth rate high" | Mailpit, runbook RB | Link tới runbook và tới Ops console |
| 2 | Mở tab DLQ, lọc `severity=2` | Ops console / DLQ | Bộ lọc đồng bộ lên URL; bảng mượt với 10k dòng |
| 3 | Thấy 300 record `transient_network`, có ghi chú auto-replay | Nhật ký auto-replay | Mỗi hành động ghi rõ confidence |
| 4 | Thấy 12 record `referential_integrity` chờ người xử lý | Chi tiết record | Payload, stage, lỗi, triage |
| 5 | Sửa `route_id` trong payload, bấm "Replay" | Payload editor | Optimistic update; hoàn tác nếu lỗi |
| 6 | Kiểm tra record trong warehouse | Chi tiết record: "Replayed · batch …" | Có liên kết tới `batch_id` |

### J-4 · Kỹ sư khôi phục sau lỗi logic (PS-4)

1. Alert DQ post-write cho thấy `delay_seconds` bất thường từ 09:00.
2. Bật cờ `etl.consumer.gtfs-rt.paused` trên Ops console (UC-11).
3. Sửa code, deploy.
4. Tạo yêu cầu raw zone replay cho khoảng 09:00–10:30 (UC-10), có chọn "Recompute analytics".
5. Theo dõi tiến độ replay; tắt cờ pause; đối chiếu checksum.

### J-5 · Demo trước hội đồng (PS-5)

Kịch bản đầy đủ ở DOC-46. Tóm tắt: bấm `make up`, mở dashboard, lần lượt bật các kịch bản trên Demo control (bunching → disruption → bad data → kill consumer → replay → scale trên k3d), và mỗi bước đều có số liệu hiển thị trên UI.
