# ADR-0016: SSE qua topic nội bộ, consumer group riêng cho mỗi pod, ring buffer

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-41, DR-42, DR-45, ADR-0026, DOC-26, DOC-33, FR-11, NFR về độ trễ UI (DOC-10)

## Bối cảnh

UI cần cập nhật gần thời gian thực: vị trí xe, cảnh báo mới, trạng thái job, thay đổi DLQ. Sự kiện phát sinh ở `etl-stream`, `etl-batch`, `triage-worker` và chính `api`, còn client kết nối tới một pod `api` bất kỳ (compose có một pod, k3d có thể nhiều pod sau load balancer, không sticky).

Yêu cầu:

- Mọi pod phải thấy mọi sự kiện, vì client của một tuyến có thể nằm ở pod nào cũng được.
- Client mất kết nối ngắn (đổi mạng, pod restart) phải nhận bù được những gì đã lỡ, hoặc biết rằng phải tải lại.
- Kênh xe có tần suất cao (khoảng 1.000 vị trí mỗi 15 giây, gấp N lần khi tạo tải) không được làm nghẽn trình duyệt.
- Không thêm hạ tầng mới ngoài Kafka và PostgreSQL đã có.

## Các phương án

1. **WebSocket, broker STOMP (RabbitMQ hoặc relay).** Hai chiều là không cần; thêm broker; proxy và xác thực phức tạp hơn.
2. **SSE, pod đọc DB định kỳ (polling).** Đơn giản, nhưng N pod × M kênh truy vấn liên tục; độ trễ bằng chu kỳ poll; vị trí xe thay đổi quá nhanh.
3. **SSE, PostgreSQL `LISTEN/NOTIFY`.** Payload giới hạn 8 KB, không giữ lại sự kiện cho client kết nối lại, gắn API vào primary.
4. **SSE, topic Kafka `pti.events.ui`; mỗi pod một consumer group riêng, không commit offset; ring buffer trong bộ nhớ để phát bù.**

## Quyết định

Chọn **phương án 4**.

- Publisher ghi sự kiện đã chuẩn hóa (envelope DOC-33 §2) vào `pti.events.ui` (3 partition, retention 1 ngày) sau khi transaction commit (ADR-0026). Id sự kiện là ULID, nên có thứ tự thời gian.
- Mỗi pod `api` dùng consumer group `pti-api-sse-<pod>`, không commit offset; khi khởi động thì seek về `now − 5 phút` để nạp sẵn ring buffer. Nhờ vậy client chuyển sang pod khác vẫn phát bù được.
- Ring buffer theo pod giữ kênh `alerts`, `jobs`, `dlq` trong 5 phút (tối đa 10.000 sự kiện). Kênh `vehicles` không vào buffer: trạng thái mới nhất lấy lại bằng REST.
- Kết nối lại với `Last-Event-ID`: tìm đúng id; không thấy thì lùi theo thời gian của ULID (−5 giây); ngoài buffer thì gửi `resync` để client refetch qua REST.
- Kênh `vehicles` được throttle thành `vehicles.batch` tối đa một lần mỗi giây mỗi tuyến (leading và trailing).
- Mỗi kết nối có hàng đợi riêng (1.000 sự kiện) và một virtual thread ghi; client chậm bị đóng với lý do `SLOW_CLIENT` thay vì làm chậm các client khác.
- Kênh công khai (`vehicles`, `alerts` audience PUBLIC) không cần token; `jobs`, `dlq` cần token. SPA dùng `@microsoft/fetch-event-source` để gửi header `Authorization`.

Chi tiết thiết kế ở DOC-26, hợp đồng sự kiện ở DOC-33.

## Hệ quả

**Tích cực**

- Không thêm hạ tầng; mọi pod thấy mọi sự kiện; scale ngang không cần sticky session.
- Kết nối lại ngắn không mất sự kiện, kết nối lại dài có đường về rõ ràng (`resync` → REST).
- SSE đi qua HTTP thường, proxy nginx và xác thực bearer như REST.

**Tiêu cực**

- Mỗi pod đọc toàn bộ topic (chấp nhận được với lưu lượng hiện tại; kênh xe là phần lớn nhất và đã được gộp).
- Consumer group mới cho mỗi lần pod khởi động; nhóm cũ phải được Kafka dọn (`offsets.retention.minutes`), và tên nhóm hiện trong công cụ quản trị.
- Ring buffer mất khi pod restart, chỉ nạp lại được trong phạm vi 5 phút.
- Giới hạn kết nối và rate limit tính theo pod (DR-45).
