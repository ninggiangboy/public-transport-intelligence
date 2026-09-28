# Tầm nhìn và phạm vi

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-01
> Phụ thuộc: SDD gốc §1–2, [DR](../00-decision-register.md), [Glossary](../02-glossary.md)

## 1. Bối cảnh

Hệ thống giao thông công cộng vận hành dựa trên ba nguồn dữ liệu tách rời, mỗi nguồn khác nhau về định dạng, tần suất và độ tin cậy:

| Nguồn | Tính chất | Tần suất |
| --- | --- | --- |
| GTFS static | Lịch theo kế hoạch | Vài tuần một lần |
| GTFS-realtime | Vị trí xe, giờ đến thực tế | Vài giây một lần |
| Ticketing | Giao dịch bán vé và hoàn vé | Liên tục, theo từng giao dịch |

Trước khi dữ liệu phục vụ được phân tích hay ra quyết định, nó phải được thu thập, làm sạch và hợp nhất **một cách đáng tin cậy**.

## 2. Vấn đề

1. **Thông tin cho hành khách không phản ánh thực tế.** Lịch tĩnh chỉ có giờ theo kế hoạch.
2. **Dữ liệu rời rạc, khó ra quyết định.** Muốn biết "tuyến nào đang có vấn đề" phải tra nhiều hệ thống.
3. **Bunching không được phát hiện chủ động.** Thường chỉ biết khi hành khách phàn nàn.
4. **Lỗi dữ liệu lan âm thầm.** Feed lỗi hoặc schema đổi thì dữ liệu sai đi thẳng lên dashboard.

## 3. Tầm nhìn

Public Transport Intelligence (**PTI**) biến dữ liệu vận hành rời rạc thành ba lớp giá trị:

1. **Dữ liệu sạch và đáng tin**: ETL pipeline có idempotency, DLQ, checkpoint và replay.
2. **Insight hành động được**: phát hiện bunching và gián đoạn, dự đoán ETA, đo độ đúng giờ.
3. **Giao diện phục vụ đúng người**: hành khách, điều phối viên, quản lý tuyến và kỹ sư dữ liệu mỗi người thấy đúng thứ mình cần.

## 4. Câu hỏi nghiên cứu

> Làm thế nào để thiết kế một ETL pipeline vừa xử lý được dữ liệu không đồng nhất (batch và gần thời gian thực), vừa bảo đảm tính đúng đắn khi xảy ra lỗi hoặc gián đoạn (không mất dữ liệu, không tạo bản ghi trùng, tự phục hồi được), áp dụng cho dữ liệu giao thông công cộng?

Đóng góp chính của đồ án là **thiết kế và kiểm chứng ngữ nghĩa effectively-once, cô lập lỗi, DLQ và replay xuyên suốt hai chế độ** (Spring Batch cho job batch, Spring Kafka cho streaming; ADR-0002), cùng bộ **thực nghiệm đo được** (EXP-01…08) chứng minh các tính chất trên.

## 5. Mục tiêu đo được

| # | Mục tiêu | Chỉ số | Ngưỡng đạt | Kiểm chứng |
| --- | --- | --- | --- | --- |
| G1 | Không mất dữ liệu khi có sự cố | loss rate (so với ledger) | = 0 | EXP-01, 04, 08 |
| G2 | Không trùng dữ liệu khi gửi lại hoặc replay | duplicate rate | = 0 | EXP-01, 02, 04, 08 |
| G3 | Lỗi dữ liệu không làm hỏng dữ liệu tốt | % record hợp lệ được nạp khi có 1/5/20% record lỗi | = 100% | EXP-03 |
| G4 | Dựng lại warehouse từ raw zone | checksum từng bảng khớp | 100% bảng khớp | EXP-04 |
| G5 | Dữ liệu tươi trên dashboard | p95 end-to-end latency ở tải nền | < 10 s | EXP-05 |
| G6 | Mở rộng theo tải | p95 latency ở 10× tải nền trên k3d | < 10 s (trong giới hạn tài nguyên) | EXP-07 |
| G7 | Tự phục hồi | Thời gian phục hồi sau từng loại sự cố; không cần can thiệp tay | Có số liệu; mất = trùng = 0 | EXP-08 |
| G8 | AI có ích thật | Tỷ lệ tự động hóa khi precision ≥ 95% | Báo cáo so sánh với bộ luật | EXP-06 |
| G9 | Khởi động một lệnh | `make up` trên máy sạch 16 GB RAM | ≤ 5 phút tới khi healthy | P1-04 |

## 6. Phạm vi

### 6.1 Trong phạm vi (chốt: làm đầy đủ)

- Hệ thống standalone, chỉ phục vụ miền giao thông công cộng.
- Ba nguồn dữ liệu: GTFS static (feed Metro Transit Minneapolis, DR-01), GTFS-realtime và ticketing, đều do simulator sinh ra.
- Toàn bộ hạ tầng chạy như production: Kafka, Kafka Connect/Debezium, object storage S3 (SeaweedFS, DR-66), PostgreSQL, Keycloak, observability đầy đủ.
- ETL (Spring Batch + Spring Kafka), analytics (bunching, disruption, ETA, OTP, ticketing anomaly), AI triage bằng Jev, REST/SSE API, dashboard React.
- Hai môi trường: Docker Compose (dev/demo) và k3d (staging, thực nghiệm scale và chaos).
- Tám thực nghiệm EXP-01…08.

### 6.2 Ngoài phạm vi

- Kết nối với hệ thống GTFS hoặc ticketing thật.
- Triển khai trên cloud production, multi-region.
- Mô hình machine learning phức tạp (analytics chỉ dùng thống kê giải thích được).
- Ứng dụng di động (UI hành khách là web responsive).
- Gửi lệnh điều phối tới xe (chỉ hiển thị gợi ý).
- GTFS-rt dạng Protobuf (DR-03), Service Alerts của GTFS-rt.
- Đa ngôn ngữ trên UI (UI chỉ có tiếng Anh, DR-61).

## 7. Giả định

| # | Giả định | Nếu sai thì |
| --- | --- | --- |
| A1 | Máy phát triển và demo có 16 GB RAM, 8 CPU (đã kiểm tra trên máy owner) | Dùng values `lite` và tắt bớt profile |
| A2 | Có API key TypeSafe (Jev) cho dev và demo | Dùng `provider=fake`; bỏ EXP-06 |
| A3 | Simulator chạy theo thời gian thực, ánh xạ ngày thật vào lịch của feed | — |
| A4 | Owner làm một mình, toàn thời gian, khoảng 23–29 tuần | Hiệu chỉnh sau mỗi milestone |
| A5 | Demo có thể không có internet | Chế độ offline (PMTiles, `fake`, image đã pull sẵn) |

## 8. Ràng buộc

- Ngôn ngữ: `docs/` viết tiếng Việt, mọi thứ khác viết tiếng Anh (DR-61).
- Công nghệ: Java 25, Spring Boot 4.1, React 19, PostgreSQL 17, Kafka 4 (DR-53).
- Repo: GitHub public; CI chạy trên runner chuẩn của GitHub (DR-56).
- ETL dựng trên Spring Batch (job batch) và Spring Kafka (streaming), không tự viết engine chunk (ADR-0002).

## 9. Thứ tự cắt giảm (dự phòng)

Đã chốt làm đầy đủ. Chỉ khi một milestone trễ quá 50% mới cắt theo thứ tự: gợi ý điều phối → EXP-06 → ticketing anomaly → OTP → k3d (EXP-08 chuyển sang compose). **P1–P3 không được cắt.**
