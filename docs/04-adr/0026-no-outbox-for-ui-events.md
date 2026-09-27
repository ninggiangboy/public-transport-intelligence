# ADR-0026: Không dùng outbox cho sự kiện UI

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-42, DR-41, ADR-0016, ADR-0023, DOC-26, DOC-33, FR-11

## Bối cảnh

Sau khi ghi DB (insight, alert, trạng thái job, dead letter), các service phát một sự kiện lên `pti.events.ui` để UI cập nhật qua SSE (ADR-0016). Ghi DB và gửi Kafka là hai hệ thống, không có transaction chung, nên có thể:

- DB commit, gửi Kafka thất bại → UI không nhận sự kiện.
- Gửi Kafka trước, DB rollback → UI thấy thứ không tồn tại.

Cách chuẩn để đảm bảo cả hai là **transactional outbox**: ghi sự kiện vào bảng trong cùng transaction, rồi một relay (hoặc Debezium) đẩy lên Kafka.

## Các phương án

1. **Transactional outbox + Debezium.** Đảm bảo at-least-once; thêm bảng outbox ở mỗi schema ghi, thêm connector, thêm độ trễ, thêm thứ cần giám sát.
2. **Transactional outbox + relay tự viết (poll bảng).** Như trên nhưng thêm một vòng poll và code dọn bảng.
3. **Kafka transaction chung với DB (chained transaction).** Không nguyên tử thật; phức tạp; Spring đã bỏ `ChainedTransactionManager`.
4. **Publish best-effort sau commit.** Mất sự kiện khi Kafka lỗi hoặc tiến trình chết giữa commit và gửi; bù bằng việc UI luôn coi DB là nguồn sự thật.

## Quyết định

Chọn **phương án 4**.

- Publisher (`UiEventPublisher`, DOC-33 §2) chỉ được gọi **sau khi transaction commit** (`TransactionSynchronization.afterCommit` hoặc sau khi method `@Transactional` trả về). Sự kiện không bao giờ nói về dữ liệu chưa commit.
- Producer `acks=1`, không idempotence, `linger.ms=5`. Gửi lỗi thì log `WARN`, tăng metric, không thử lại, không làm hỏng nghiệp vụ.
- Sự kiện chỉ là **tín hiệu để làm mới**; nguồn sự thật là DB (`alert_event`, bảng insight, `dead_letter`, metadata Spring Batch). UI:
  - refetch mọi query định kỳ (60 giây cho dữ liệu vận hành, DOC-26 §9);
  - refetch khi nhận `resync` hoặc khi kết nối SSE lại mà không phát bù được;
  - chuyển sang polling khi SSE không kết nối được.
- Sự kiện nghiệp vụ quan trọng không đi qua kênh này: cảnh báo được lưu (ADR-0023), replay đi qua bảng yêu cầu (ADR-0013).

## Hệ quả

**Tích cực**

- Không thêm bảng, connector hay relay; code publisher ngắn và dễ test.
- Độ trễ thấp nhất (gửi ngay sau commit).
- Lỗi Kafka không làm hỏng ghi dữ liệu nghiệp vụ.

**Tiêu cực**

- Có thể mất sự kiện; UI có thể chậm tới một chu kỳ refetch (60 giây) trong trường hợp đó. Chấp nhận vì đây là dữ liệu hiển thị, không phải dữ liệu nghiệp vụ.
- Thứ tự sự kiện chỉ đảm bảo trong một Kafka key; client phải xử lý sự kiện đến muộn bằng cách so `updatedAt` hoặc refetch.
- Khi cần đảm bảo mạnh hơn (ví dụ tích hợp hệ thống bên ngoài), phải xem lại quyết định này và dùng outbox.
