# Architecture Decision Records

> DOC-12 · Quy ước: [ADR-0001](0001-record-architecture-decisions.md) · Nguồn: [Decision Register](../00-decision-register.md), [Master plan §3.2](../00-master-plan.md)

Cột **Gate** là phase cần ADR ở trạng thái Accepted trước khi bắt đầu. Mọi ADR 0001–0030 đã có file.

| ADR | Tiêu đề | Trạng thái | Gate | Nguồn |
| --- | --- | --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Ghi quyết định kiến trúc bằng ADR | Accepted | P1 | — |
| [0002](0002-spring-batch-and-spring-kafka.md) | Dùng Spring Batch và Spring Kafka thay cho engine chunk tự xây | Accepted | P1 | SDD 6.3, DR-62 |
| [0003](0003-effectively-once-upsert.md) | Effectively-once bằng at-least-once cộng upsert theo business key | Accepted | P1 | DR-16 |
| [0004](0004-offset-commit-after-transaction.md) | Commit offset sau khi transaction của chunk commit; ánh xạ poll → chunk | Accepted | P2 | DR-22 |
| [0005](0005-batch-first-scan-fallback.md) | Ghi chunk: batch upsert trước, fallback scan | Accepted | P2 | DR-21 |
| [0006](0006-error-classification.md) | Phân loại lỗi DATA / TRANSIENT_INFRA / FATAL | Accepted | P2 | DR-23 |
| [0007](0007-json-envelope-for-gtfs-rt.md) | JSON envelope có `schema_version` cho GTFS-realtime | Accepted | P1 | DR-03 |
| [0008](0008-partition-key-route-id.md) | Partition key `route_id`, 12 partition | Accepted | P1 | DR-05 |
| [0009](0009-gtfs-feed-versioning.md) | Phiên bản hóa GTFS static bằng `feed_version` và staging swap | Accepted | P1 | DR-10 |
| [0010](0010-event-time-insight-episodes.md) | Khóa insight theo event time, mô hình episode, UUIDv5 | Accepted | P4 | DR-29 |
| [0011](0011-fact-partitioning.md) | Partition bảng fact theo ngày và job bảo trì partition | Accepted | P1 | DR-15 |
| [0012](0012-raw-zone-s3-sink.md) | Raw zone bằng S3 sink, JSON gzip, phân vùng theo giờ | Accepted (đã xác minh S-04) | P1 | SDD 4.1, S-04, DR-81 |
| [0013](0013-replay-request-api-etl-executes.md) | Replay: API ghi yêu cầu, ETL thực thi | Accepted | P2 | DR-18 |
| [0014](0014-deployment-units.md) | Một image ETL hai profile; analytics là thư viện | Accepted | P1 | DR-26, 35 |
| [0015](0015-job-exclusivity-and-recovery.md) | Chống chạy trùng job: ShedLock, JobInstance, `VERSION` làm fencing | Accepted | P2 | DR-24 |
| [0016](0016-sse-per-pod-consumer-ring-buffer.md) | SSE qua topic nội bộ, consumer group riêng cho mỗi pod, ring buffer | Accepted | P4 | DR-41 |
| [0017](0017-keycloak-oauth2-resource-server.md) | Keycloak làm IdP, API là OAuth2 resource server | Accepted | P4 | DR-40 |
| [0018](0018-decision-model-port.md) | Cổng `DecisionModel` với adapter Jev/Fake/Disabled | Accepted | P6 | DR-36 |
| [0019](0019-code-owned-automation-thresholds.md) | Ngưỡng tự động hóa do code sở hữu, AI chỉ phán đoán | Accepted | P6 | SDD 9.6 |
| [0020](0020-frontend-stack.md) | Stack frontend: React SPA + Vite, TanStack, shadcn/ui | Accepted | P5 | DR-46 |
| [0021](0021-maplibre-pmtiles-offline.md) | Bản đồ MapLibre và PMTiles offline | Accepted (đã xác minh S-05) | P5 | DR-47, DR-82 |
| [0022](0022-observability-stack.md) | Observability: Prometheus, Tempo, Loki, Alloy | Accepted | P3 | DR-50, DR-71 |
| [0023](0023-unified-alert-event.md) | Bảng `alert_event` hợp nhất, định tuyến theo audience | Accepted | P4 | DR-17 |
| [0024](0024-flyway-migration-job.md) | Flyway chạy như job riêng; expand/contract | Accepted | P1 | SDD 12.4 |
| [0025](0025-python-experiment-runner.md) | Experiment runner bằng Python | Accepted | P3 | DR-52 |
| [0026](0026-no-outbox-for-ui-events.md) | Không dùng outbox cho sự kiện UI | Accepted | P4 | DR-42 |
| [0027](0027-contract-testing.md) | Contract testing bằng JSON Schema + OpenAPI diff | Accepted | P2 | DR-44 |
| [0028](0028-kubernetes-tooling.md) | K8s: k3d, helmfile, Strimzi, CNPG, KEDA, Chaos Mesh | Accepted | P7 | DR-54 |
| [0029](0029-spring-boot-4-java-25.md) | Nền tảng Spring Boot 4.1 và Java 25 | Accepted (xác minh S-06) | P1 | DR-53 |
| [0030](0030-monorepo-layout.md) | Monorepo, chia thư mục gốc theo stack: `backend/`, `frontend/`, `deploy/` | Accepted | P1 | DR-85, DR-26 |

Gate P1 đã đủ: 0001, 0002, 0003, 0007, 0008, 0009, 0011, 0012, 0014, 0024, 0029, 0030.

Gate P2 đã đủ: 0004, 0005, 0006, 0013, 0015, 0027.

Gate P3 đã đủ: 0022, 0025.

Gate P4 đã đủ: 0010, 0016, 0017, 0023, 0026.

Gate P5 đã đủ: 0020, 0021.

Gate P6 đã đủ: 0018, 0019.

Gate P7 đã đủ: 0028.
