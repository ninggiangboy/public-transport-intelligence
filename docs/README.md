# Tài liệu dự án: Public Transport Intelligence

Tài liệu thiết kế gốc: [`../public-transport-intelligence.md`](../public-transport-intelligence.md) (gọi tắt là **SDD gốc**).

## Bắt đầu từ đây

1. [00-master-plan.md](00-master-plan.md): kế hoạch tổng. Gồm danh sách mọi tài liệu cần viết, công việc theo phase, ma trận truy vết và các template.
2. [00-decision-register.md](00-decision-register.md): sổ quyết định. Mọi quyết định đã chốt.

## Tài liệu đã có

Mọi tài liệu DOC-01…48 trong mục 3 của master plan đã được viết.

| DOC | Tài liệu | Trạng thái |
| --- | --- | --- |
| DOC-01 | [Tầm nhìn và phạm vi](01-product/vision-and-scope.md) | Review |
| DOC-02 | [Persona và hành trình người dùng](01-product/personas-and-journeys.md) | Review |
| DOC-03 | [Yêu cầu](01-product/requirements.md) | Review |
| DOC-04 | [Use case](01-product/use-cases.md) | Review |
| DOC-05 | [Danh mục tính năng](01-product/feature-catalog.md) | Review |
| DOC-06 | [Glossary](02-glossary.md) | Review |
| DOC-07 | [Bối cảnh hệ thống và container](03-architecture/system-context-and-containers.md) | Review |
| DOC-08 | [Luồng dữ liệu](03-architecture/data-flows.md) | Review |
| DOC-09 | [Hợp đồng message](03-architecture/messaging-contracts.md) | Review |
| DOC-10 | [Thuộc tính chất lượng](03-architecture/quality-attributes.md) | Review |
| DOC-11 | [Công nghệ và phiên bản](03-architecture/tech-stack-and-versions.md) | Review |
| DOC-12 | [ADR](04-adr/) (0001–0030) | Accepted |
| DOC-13 | [Dữ liệu nguồn](05-data/source-data.md) | Review |
| DOC-14 | [Mô hình dữ liệu warehouse](05-data/warehouse-model.md) | Review |
| DOC-15 | [Mô hình dữ liệu vận hành và insight](05-data/ops-and-insight-model.md) | Review |
| DOC-16 | [Rule chất lượng dữ liệu](05-data/data-quality-rules.md) | Review |
| DOC-17 | [Role database và phân quyền](05-data/db-roles-and-grants.md) | Review |
| DOC-18 | [Vòng đời dữ liệu](05-data/data-lifecycle.md) | Review |
| DOC-19 | [Xử lý batch và chunk](06-design/batch-and-chunk-processing.md) | Review |
| DOC-20 | [ETL streaming](06-design/etl-streaming.md) | Review |
| DOC-21 | [ETL GTFS static](06-design/etl-gtfs-static.md) | Review |
| DOC-22 | [DLQ và replay](06-design/dlq-and-replay.md) | Review |
| DOC-23 | [Analytics](06-design/analytics.md) | Review |
| DOC-24 | [AI triage](06-design/ai-triage.md) | Review |
| DOC-25 | [Source simulator](06-design/source-simulator.md) | Review |
| DOC-26 | [Giao sự kiện thời gian thực (SSE)](06-design/realtime-delivery.md) | Review |
| DOC-27 | [Bảo mật](06-design/security.md) | Review |
| DOC-28 | [Observability](06-design/observability.md) | Review |
| DOC-29 | [Tham chiếu cấu hình](06-design/configuration-reference.md) | Review |
| DOC-30 | [Xử lý lỗi](06-design/error-handling.md) | Review |
| DOC-48 | [Demo console](06-design/demo-console.md) | Review |
| DOC-31 | [Quy ước API](07-api/api-guidelines.md) | Review |
| DOC-32 | [Danh mục endpoint](07-api/api-endpoints.md) | Review |
| DOC-33 | [Sự kiện SSE](07-api/sse-events.md) | Review |
| DOC-34 | [Nguyên tắc UX và kiến trúc thông tin](08-ux-ui/ux-principles-and-ia.md) | Review |
| DOC-35 | [Design system](08-ux-ui/design-system.md) | Review |
| DOC-36 | [Đặc tả màn hình](08-ux-ui/screens/README.md) (11 file) | Review |
| DOC-37 | [Trạng thái UI và microcopy](08-ux-ui/ui-states-and-copy.md) | Review |
| DOC-38 | [Dev local](09-operations/local-dev.md) | Review |
| DOC-39 | [Triển khai Docker Compose](09-operations/deploy-compose.md) | Review |
| DOC-40 | [Triển khai Kubernetes (k3d)](09-operations/deploy-k8s.md) | Review |
| DOC-41 | [CI/CD](09-operations/ci-cd.md) | Review |
| DOC-42 | [Runbook](09-operations/runbooks/README.md) (RB-01…14, compose và k3d) | Review |
| DOC-43 | [Backup và khôi phục](09-operations/backup-restore.md) | Review |
| DOC-44 | [Chiến lược kiểm thử](10-testing/test-strategy.md) | Review |
| DOC-45 | [Protocol thực nghiệm](10-testing/experiments/README.md) (EXP-01…08) | Review |
| DOC-46 | [Kịch bản demo](10-testing/demo-script.md) | Review |
| DOC-47 | [Ánh xạ báo cáo đồ án](11-report/thesis-mapping.md) | Review |

## Cấu trúc

| Thư mục | Nội dung |
| --- | --- |
| `01-product/` | Tầm nhìn, persona, yêu cầu, use case, danh mục tính năng |
| `02-glossary.md` | Thuật ngữ |
| `03-architecture/` | Context và container, luồng dữ liệu, hợp đồng message, thuộc tính chất lượng, stack |
| `04-adr/` | Quyết định kiến trúc (MADR) |
| `05-data/` | Dữ liệu nguồn, warehouse, bảng vận hành và insight, DQ rule, phân quyền DB, vòng đời dữ liệu |
| `06-design/` | Thiết kế chi tiết từng thành phần |
| `07-api/` | Quy ước API, đặc tả endpoint, sự kiện SSE |
| `08-ux-ui/` | IA, design system, đặc tả màn hình, trạng thái và microcopy |
| `09-operations/` | Dev local, triển khai compose/k8s, CI/CD, runbook, backup |
| `10-testing/` | Chiến lược test, protocol thực nghiệm, kịch bản demo |
| `11-report/` | Ánh xạ sang báo cáo (tùy chọn) |

## Quy ước

- Dòng đầu mỗi file ghi trạng thái (`Draft | Review | Approved | Superseded`) và ngày cập nhật.
- Sơ đồ vẽ bằng Mermaid để render được trên GitHub và VS Code.
- Mã định danh (FR, UC, DOC, ADR, DR, Pn-xx, EXP…) dùng thống nhất như quy định ở mục 0 của master plan.
- Đổi hành vi thì sửa tài liệu trong cùng PR. Đổi quyết định thì viết ADR mới thay thế ADR cũ.
