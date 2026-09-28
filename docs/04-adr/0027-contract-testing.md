# ADR-0027: Contract testing bằng JSON Schema + OpenAPI diff

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-44, ADR-0007, NFR-07, DOC-09, DOC-31, DOC-41, DOC-44

## Bối cảnh

Hệ thống có ba loại hợp đồng giữa các thành phần:

1. **Message Kafka** giữa simulator, Debezium và ETL: GTFS-rt JSON envelope (ADR-0007), CDC event của Debezium, `insight.events`.
2. **REST API** giữa `api` và frontend (và client bên ngoài ở mức public).
3. **SSE** giữa `api` và frontend.

SDD gốc đề xuất Spring Cloud Contract. Nhưng công cụ đó thiên về cặp producer/consumer đều là JVM, sinh stub từ DSL Groovy/YAML, và không tự nhiên cho frontend TypeScript hay cho Debezium (producer không do dự án viết).

## Các phương án

1. **Spring Cloud Contract.** Nặng, JVM↔JVM; không phủ được Debezium và frontend.
2. **Pact.** Consumer-driven, có broker; tốt cho nhiều team, quá nặng cho một repo một người, và hỗ trợ message Kafka qua plugin.
3. **Schema làm nguồn sự thật, kiểm ở cả hai phía:** JSON Schema cho message, OpenAPI sinh từ code cho REST, và CI chặn thay đổi phá vỡ.

## Quyết định

Chọn **phương án 3**.

- **Message Kafka:** JSON Schema (draft 2020-12) ở `backend/common/src/main/resources/schemas/` là nguồn sự thật (DOC-09 §10). Fixture hợp lệ và không hợp lệ ở `backend/common/src/test/resources/contract-examples/<schema>/`.
  - Producer test (simulator): mọi message sinh ra trong test đều được validate theo schema.
  - Consumer test (ETL): fixture hợp lệ phải map được; fixture không hợp lệ phải vào DLQ `SCHEMA`.
  - File schema đã phát hành thì bất biến: mọi thay đổi payload tạo `schema_version` mới (ADR-0007, DOC-09 §10). Job CI `contract` so thư mục schema với `main` và fail nếu một file đã có ở `main` bị sửa.
- **CDC:** chạy Debezium thật trong Testcontainers (Postgres nguồn + Kafka + Connect) và cho event thật đi qua parser; không mock định dạng Debezium.
- **REST:** `openapi.json` được sinh khi build (springdoc) và commit vào `backend/api/openapi.json`. CI:
  - kiểm tra file đã commit khớp với file sinh ra (quên cập nhật thì fail);
  - `openapi-diff` với `backend/api/openapi.json` của tag phát hành gần nhất, fail nếu có breaking change mà PR không có nhãn `breaking-api` (DOC-41);
  - frontend sinh type bằng `openapi-typescript` và chạy `tsc --noEmit`.
- **SSE:** payload mỗi loại event có JSON Schema ở `backend/common/src/main/resources/schemas/ui-events/`, được validate trong test của `api` và dùng để sinh type cho frontend.
- Chi tiết job CI ở DOC-41, chiến lược test ở DOC-44.

## Hệ quả

**Tích cực**

- Mọi hợp đồng là file văn bản trong repo, review được trong PR.
- Phủ được cả producer không do dự án viết (Debezium) và consumer không phải JVM (frontend).
- Không cần broker hay hạ tầng thêm.

**Tiêu cực**

- Không có "consumer-driven": producer không biết consumer dùng trường nào. Chấp nhận vì mọi consumer nằm trong cùng repo và cùng PR.
- Schema và code có thể lệch nếu test không phủ đủ trường; giảm thiểu bằng việc bật `additionalProperties: false` ở schema của message do dự án phát (không áp dụng cho Debezium).
- Phụ thuộc chất lượng của `openapi-diff` trong việc phân loại breaking change; nhãn `breaking-api` là lối thoát có chủ đích.
