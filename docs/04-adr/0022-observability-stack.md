# ADR-0022: Observability bằng Micrometer, Prometheus, Tempo, Loki và Alloy

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-50, DR-51, DR-57, DR-71, NFR-03, NFR-05, DOC-28, DOC-39 §3.7

## Bối cảnh

SDD gốc yêu cầu log JSON có `batch_id`/`trace_id`, metric Micrometer và trace qua Micrometer Tracing xuất OTLP, nhưng chưa chọn nơi lưu trace và log. Ràng buộc:

- Chạy được trên một laptop 16 GB cùng toàn bộ hệ thống (NFR-07, ngân sách RAM ở DOC-10 §5); trên k3d cũng phải vừa.
- Phải đi được từ một dòng fact → log → trace (NFR-05), và đo được độ trễ đầu-cuối theo từng chặng (DR-57).
- Không có span trùng; không thêm agent chạy song song với instrumentation của Spring.
- Mọi thành phần là mã nguồn mở, có image chính thức, cấu hình được bằng file trong repo.

## Các phương án

1. **Grafana LGTM (Prometheus + Loki + Tempo + Grafana) với Alloy thu log, OTel Collector nhận trace.** Mỗi tín hiệu một công cụ chuyên dụng; Grafana nối log ↔ trace bằng derived field.
2. **Elastic stack (Elasticsearch + Kibana + APM).** Mạnh ở tìm kiếm log, nhưng Elasticsearch cần 1–2 GB heap, vượt ngân sách; APM agent chồng lên Micrometer.
3. **OpenTelemetry Java agent + backend bất kỳ.** Instrumentation tự động rộng, nhưng tạo span trùng với observation của Spring Kafka/Batch và khó kiểm soát tên, tag.
4. **Chỉ Prometheus + log file.** Nhẹ nhất, nhưng không đáp ứng NFR-05 (không có trace) và khó tra log theo `batch_id`.
5. **SaaS (Grafana Cloud, Datadog).** Không chạy offline khi demo, phụ thuộc tài khoản ngoài.

## Quyết định

Chọn **phương án 1**:

- **Metric:** Micrometer → `/actuator/prometheus` (cổng 9080) → Prometheus 3.x scrape 15 giây; rule và Alertmanager (DR-51: Mailpit + webhook API).
- **Trace:** Micrometer Observation → Micrometer Tracing (bridge OTel) → OTLP HTTP → OTel Collector (contrib) → Tempo 2.x. Bật observation của Spring Kafka (template và listener) và Spring Batch. Không dùng OTel Java agent.
- **Log:** structured logging ECS có sẵn của Spring Boot ra stdout → Alloy (`loki.source.docker` trên compose, DaemonSet trên k3d) → Loki 3.x; `trace_id`, `batch_id` là structured metadata, không phải label.
- **Grafana 12.x** là giao diện duy nhất cho kỹ sư; dashboard và datasource provision từ repo.
- **Không chạy exporter** cho Kafka và Postgres trên compose; số liệu còn thiếu do app phát (DR-71).
- Toàn bộ nằm trong compose profile `observability` (tổng khoảng 1,9 GB RAM, DOC-10 §5), không bắt buộc cho `make up`.

## Hệ quả

**Tích cực**

- Một mô hình instrumentation (Micrometer Observation) cho cả metric và trace, nên tên và tag thống nhất.
- Đi từ `batch_id` → Loki → `trace_id` → Tempo trong Grafana mà không cần sửa schema DB.
- Cùng bộ công cụ trên compose và k3d (kube-prometheus-stack trên k3d), alert rule dùng chung.
- Chạy offline hoàn toàn khi demo.

**Tiêu cực**

- Năm container thêm (Prometheus, Loki, Tempo, OTel Collector, Alloy) cộng Grafana và Alertmanager; máy 16 GB phải tắt profile khác khi chạy EXP-05 ở tải cao (DOC-45).
- Batch listener của Spring Kafka không có observation theo record, nên phải tự tạo span `pti.etl.poll` và link tới span producer (DOC-20 §3); code tự viết cần test (DOC-28 O-04).
- Không có exporter nên thiếu một số số liệu hạ tầng sâu (ví dụ ISR của broker trên compose); chấp nhận vì compose chỉ có một broker.
- Tempo và Loki dùng filesystem storage với retention ngắn (3 và 7 ngày); kết quả thực nghiệm phải được runner xuất ra file (DR-52), không dựa vào Prometheus để lưu lâu dài.
