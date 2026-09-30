# ADR-0032: Clean Architecture cho backend Java, refactor code cũ ở Phase R

- Trạng thái: Accepted
- Ngày: 2026-09-30 · Liên quan: DR-104, ADR-0014, ADR-0018, ADR-0026, DOC-49, DOC-44 §3.3, master plan §4, §5 (Phase R)

## Bối cảnh

P1–P3 đã xong với bốn module Java (`common`, `db`, `etl`, `source-simulator`). Code chia package theo feature (master plan §7.3, luật A-05), nhưng trong mỗi feature, logic nghiệp vụ nằm lẫn với cơ chế Spring: listener, writer, `JdbcClient`, Micrometer, `@Transactional` và luật DQ hay chunk nằm trong cùng lớp hoặc gọi thẳng nhau. Hệ quả:

- Muốn test một luật thường phải dựng Spring context hoặc Testcontainers, nên test chậm và khó viết bảng test.
- Thư viện (Spring Batch, Spring Kafka, SDK Jev) lan vào code nghiệp vụ. ADR-0018 đã phải tách riêng cổng `DecisionModel` để cô lập SDK Jev, nhưng đó là ngoại lệ chứ chưa phải quy tắc chung.
- P4–P6 thêm ba module mới (`analytics`, `api`, `triage-worker`) có nhiều logic nghiệp vụ nhất của dự án: state machine bunching, baseline disruption, bảng quyết định auto-replay. Nếu tiếp tục cách cũ, phần này cũng trộn framework.

Refactor code cũ ngay bây giờ có rủi ro. P1–P3 đã chứng minh tính đúng đắn bằng test fault-injection và chuỗi smoke `p3-08-d` (M3). Refactor trước P4 sẽ làm chậm P4 và buộc phải chứng minh lại.

## Các phương án

1. **Giữ nguyên**: package theo feature, không quy định tầng.
2. **Áp ngay cho toàn bộ**: refactor `etl`, `source-simulator`, `common` trước khi bắt đầu P4.
3. **Áp cho code mới từ P4, refactor code cũ ở một phase riêng (Phase R) sau M6, trước P3-10 và P7.** Module cũ bị freeze bằng ArchUnit tới Phase R.
4. **Chỉ ban hành hướng dẫn**, không ép bằng công cụ.

## Quyết định

Chọn **phương án 3**. Quy tắc chi tiết ở DOC-49.

- Trong mỗi feature chia bốn tầng: `domain`, `application` (use case và `application.port`), `adapter.in`/`adapter.out`, `config` (composition root). Package theo feature vẫn ở cấp một.
- `domain` và `application` là **Java thuần**: không import `org.springframework..`, Jackson, Micrometer, Kafka, JDBC hay SDK ngoài. Ranh giới transaction đi qua port `TransactionRunner`. Use case được tạo bằng `@Bean` trong `config`, không dùng `@Service`.
- Luật ArchUnit A-11…A-18 (DOC-44 §3.3) **làm fail build** cho `analytics`, `api`, `triage-worker` từ P4-18.
- Module cũ (`etl`, `source-simulator`, `common`, `db`) chạy cùng các luật qua `FreezingArchRule`. Vi phạm hiện có nằm trong store commit vào repo; store chỉ được giảm. Package **mới** thêm vào module cũ phải tuân thủ ngay.
- **Phase R** (RF-00…RF-08) refactor module cũ, không đổi hành vi bên ngoài, và kết thúc khi store rỗng, `FreezingArchRule` bị xóa, test fault-injection và `pti-exp smoke` đạt như M3.
- Phase R đứng **trước P3-10**, để đợt chạy thực nghiệm đầy đủ đo trên code cuối cùng.
- Ranh giới giữa các module (ADR-0014, A-01…A-04) không đổi. Frontend và `experiments/` ngoài phạm vi.

Lý do không chọn các phương án khác:

- Phương án 1 để logic phức tạp nhất của P4–P6 phụ thuộc framework, khó test bằng bảng test như DOC-23, DOC-24 yêu cầu.
- Phương án 2 đặt refactor lên đường găng ngay sau M3, trong khi code cũ đang chạy đúng và đã được đo. Rủi ro hồi quy lớn, lợi ích tức thời nhỏ.
- Phương án 4 thực tế không giữ được: không có công cụ kiểm tra, vi phạm sẽ tích lại và Phase R không có điểm kết thúc đo được.

## Hệ quả

**Tích cực**

- Logic analytics, triage và use case API test được bằng unit test thuần, không cần Spring context.
- Đổi hạ tầng (ví dụ provider AI, cách kích hoạt analytics trong process sang Kafka theo ADR-0014) chỉ đổi adapter.
- Quy tắc được kiểm tra tự động; tiến độ Phase R đo được bằng kích thước store freeze.

**Tiêu cực**

- Thêm code: port, mapper, cấu hình `@Bean` cho từng use case. Với endpoint đọc đơn giản, use case chỉ chuyển tiếp một lời gọi.
- Tốn thời gian: P4 thêm khoảng 2–3 ngày (P4-18 và làm quen), Phase R khoảng 2–3 tuần. Tổng lộ trình thành khoảng 24–33 tuần.
- Trong P4–P6, codebase có hai kiểu tổ chức song song. Người đọc cần biết module nào đã áp dụng (DOC-49 §1).
- Phase R có rủi ro hồi quy ở phần đúng đắn quan trọng nhất (ETL). Giảm bằng: test trước, từng feature một, hồi quy đầy đủ ở RF-08.

**Việc phát sinh**

- P4-18: hiện thực A-11…A-18, `TransactionRunner` cùng `SpringTransactionRunner`, freeze module cũ.
- Tài liệu thiết kế P4–P6 (DOC-23, 24, 26, 31) thêm bảng ánh xạ tầng; DOC-49 §11 là nguồn chính.
- Phase R trong master plan; Phase R cũng có trong thứ tự cắt giảm (§4.3): nếu bị cắt, code cũ giữ freeze, còn luật cho code mới vẫn giữ.
- Ngoại lệ của luật là danh sách đóng (DOC-49 §10). Thêm ngoại lệ phải có DR.
