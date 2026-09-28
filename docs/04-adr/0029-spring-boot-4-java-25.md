# ADR-0029: Nền tảng Spring Boot 4.1 và Java 25

- Trạng thái: Accepted (S-06 xong 2026-09-28)
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

## Kết quả S-06 (2026-09-28)

App mẫu `spikes/s06-boot41-java25/` (Boot 4.1.1, Java 25.0.4, Gradle 9.8.0) chạy 20 test trên Postgres 17.11 và 18.1, Kafka 4.3.1, SeaweedFS 4.47. Kết quả:

- **Không cần lối lui.** Mọi thư viện trong DOC-11 §1 chạy trên Boot 4.1 và Java 25; ShedLock, Spring Cloud AWS, Resilience4j (đã có `resilience4j-spring-boot4`), springdoc, datasource-micrometer đều có bản cho Boot 4. Công cụ build (Spotless, Checkstyle, SpotBugs 4.10.4, JaCoCo 0.8.15, ArchUnit 1.5.1, Jib 3.5.4) đọc được bytecode Java 25. Phiên bản cụ thể ở DOC-11.
- **Virtual thread:** `spring.threads.virtual.enabled=true` cho request MVC chạy trên virtual thread. Quyết định bật/tắt theo app giữ như DR-74.
- **Spring Batch 6:** `ChunkOrientedStep` mới mất dòng DLQ khi skip ở bước ghi và bỏ sót item khi process chết giữa lúc scan. Dự án dùng builder fault-tolerant cũ (deprecated nhưng đúng) theo DR-80 và DOC-19 §5.
- **Image:** Jib với `eclipse-temurin:25-jre` build và chạy được; app mẫu khởi động 2,7 giây, khoảng 460 MiB RSS khi không giới hạn heap.
