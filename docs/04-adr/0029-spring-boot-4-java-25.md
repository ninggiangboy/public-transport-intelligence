# ADR-0029: Nền tảng Spring Boot 4.1 và Java 25

- Trạng thái: Accepted (bảng tương thích hoàn thiện ở S-06)
- Ngày: 2026-09-26 · Liên quan: DR-53, DOC-11, P0-07

## Bối cảnh

SDD gốc ghi Spring Boot 3 và Java 21. Owner chốt nguyên tắc "dùng bản mới nhất" (Nhật ký chốt 2026-09-26). Lúc viết, Spring Boot mới nhất là 4.1.x (hỗ trợ tới 2027-07-31) và Java LTS mới nhất là 25. Dự án kéo dài 23–29 tuần; nếu bắt đầu trên Boot 3.x thì sẽ phải nâng cấp giữa chừng khi Boot 3 hết hỗ trợ OSS. `typesafe-java-sdk` và starter của nó nhắm tới Boot 4.x.

## Các phương án

1. **Boot 3.5 + Java 21** (như SDD gốc). Hệ sinh thái đầy đủ nhất, nhưng sắp hết hỗ trợ và lệch nguyên tắc đã chốt.
2. **Boot 4.1 + Java 21.** An toàn hơn về thư viện bên thứ ba, nhưng không dùng được các cải tiến JVM của Java 25.
3. **Boot 4.1 + Java 25.**

## Quyết định

Chọn **phương án 3**, với lối lui là phương án 2.

- Java toolchain 25 (Temurin), bật `-XX:+UseCompactObjectHeaders` để giảm heap (quan trọng với ngân sách 16 GB, DOC-10 §5).
- Spring Boot 4.1.x; mọi thư viện trong BOM theo BOM (DOC-11 §0).
- Kéo theo: Spring Framework 7, Jakarta EE 11, Jackson 3, Spring Batch 6, Spring Kafka 4, Spring Security 7, JSpecify.
- Không dùng API preview của Java; không dùng virtual thread cho listener Kafka và step Spring Batch cho tới khi S-06 xác nhận không có vấn đề pinning với JDBC driver. API (MVC) được bật `spring.threads.virtual.enabled=true` nếu S-06 đo thấy ổn.
- **S-06 (P0-07)** dựng một app mẫu và điền bảng tương thích trong DOC-11: Spring Batch 6 (fault-tolerant step, JobRepository JDBC, restart), ShedLock, Spring Cloud AWS S3, Resilience4j, springdoc, Testcontainers 2, Micrometer Tracing, Jib với Java 25.
- **Lối lui:** nếu một thư viện bắt buộc không chạy trên Java 25 thì hạ toolchain xuống 21 (Boot 4.1 vẫn hỗ trợ). Nếu một thư viện chưa hỗ trợ Boot 4 thì cấu hình thủ công không qua starter, hoặc thay bằng thứ tương đương (ví dụ AWS SDK v2 thay Spring Cloud AWS). Mỗi lần dùng lối lui thì ghi vào DOC-11.

## Hệ quả

- Tài liệu và ví dụ trên mạng phần lớn vẫn viết cho Boot 3. DOC-11 §6 liệt kê các khác biệt để người triển khai không làm theo tài liệu cũ.
- Một số thư viện bên thứ ba có thể trễ hỗ trợ Boot 4. Rủi ro này đã có trong master plan §8.
- Toàn bộ image dùng base image Java 25; Jib cần cấu hình `from.image` tương ứng.
