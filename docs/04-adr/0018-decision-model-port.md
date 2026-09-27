# ADR-0018: Cổng `DecisionModel` với adapter Jev, Fake và Disabled

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-36, DR-37, DR-72, ADR-0019, DOC-24, FR-09.7, FR-09.9, F-AI-01

## Bối cảnh

triage-worker hỏi một mô hình quyết định (TypeSafe Jev) cho bốn use case: phân loại dead letter, phân loại bất thường ticketing, làm giàu gián đoạn và gợi ý điều phối. Jev là dịch vụ bên ngoài, có API key, có quota, có thể chậm hoặc không truy cập được. Mặt khác:

- CI, integration test và E2E phải chạy không cần mạng và không tốn tiền, với kết quả tất định để assert.
- Demo phải chạy được offline (DR-47).
- EXP-06 cần một baseline không dùng AI để so sánh chất lượng.
- FR-09.9 yêu cầu tắt được từng use case, và FR-09.7 yêu cầu Jev lỗi không ảnh hưởng ETL.
- Từ 2026-09-21 đã có Java SDK chính thức (`typesafe-java-sdk`) và starter Spring AI (DR-36).

## Các phương án

1. **Gọi `TypeSafeClient` trực tiếp trong code use case.** Ít lớp nhất. Test phải mock SDK; không có baseline; không có chế độ offline; lỗi của SDK lan vào code nghiệp vụ.
2. **Dùng Spring AI (`ChatClient`, starter `spring-ai-starter-typesafe`).** Có sẵn cấu hình và observability của Spring AI. Kéo theo cả stack Spring AI mà dự án không cần (không có chat, không có advisor, không có RAG); trừu tượng của Spring AI không mô tả được ba loại câu hỏi có kiểu (Choice, Score, Noul) tốt hơn chính SDK.
3. **Cổng riêng của dự án (`DecisionModel`) và các adapter.** Interface nhỏ mô tả đúng thứ dự án dùng: một state, nhiều câu hỏi có kiểu, một kết quả có confidence và `model_version`. Adapter `jev` bọc SDK, `fake` là bảng luật tất định, `disabled` tắt hoàn toàn.

## Quyết định

Chọn **phương án 3**.

- Interface `DecisionModel.decide(UseCase, JsonNode state, Map<String, Question>) → Decision` cùng các kiểu `Question` (`Choice`, `Score`, `Noul`) và `Answer` là kiểu của dự án (DOC-24 §4.2). Code use case chỉ phụ thuộc cổng; chỉ package `model.jev` được import SDK (luật ArchUnit).
- Adapter chọn bằng `pti.triage.provider = jev | fake | disabled`. Mặc định `jev` ở dev/staging, `fake` ở CI, compose mặc định và demo offline.
- `FakeDecisionModel` dùng bảng luật tất định theo stage/rule và các chỉ số trong state (DOC-24 §15). Bảng này đồng thời là **baseline luật** của EXP-06 và là nguồn của `DlqRuleClassifier` cho luật chặn cuối FR-09.8.
- Resilience4j (bulkhead, rate limiter, circuit breaker, time limiter) đặt **bên ngoài** adapter, dùng chung cho mọi adapter; retry của SDK tắt (`sdk-retries = 0`). Vòng xử lý quyết định retry bằng lease và backoff trong DB (DOC-24 §5.4).
- Adapter đổi mọi lỗi của SDK và mạng sang bốn nhóm exception của cổng: không khả dụng (tạm thời), thất bại với state này (tính lần thử), cấu hình sai (fatal), PII (fatal) (DOC-24 §4.4, §11.2).
- Không dùng Spring AI.

## Hệ quả

**Tích cực**

- Toàn bộ test, CI, E2E và demo offline chạy với `fake`, tất định và không tốn quota.
- Đổi nhà cung cấp mô hình, hoặc đổi sang API batch khi S-01 xác nhận, chỉ đụng tới một adapter.
- EXP-06 so sánh được Jev với baseline luật bằng cùng một đường code.
- triage-worker nhẹ: không kéo stack Spring AI.

**Tiêu cực**

- Thêm một lớp trừu tượng và bộ kiểu riêng phải giữ đồng bộ với SDK khi SDK đổi (được `JevContractTest` bảo vệ).
- `fake` có thể lệch hành vi thật của Jev (ví dụ phân bố confidence). E2E chỉ kiểm luồng, không kiểm chất lượng; chất lượng được đo riêng ở EXP-06.
- Không tận dụng được observability có sẵn của Spring AI; metric và span phải tự định nghĩa (DOC-24 §18).
