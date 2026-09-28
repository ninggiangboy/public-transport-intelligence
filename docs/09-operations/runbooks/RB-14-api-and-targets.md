# RB-14: API lỗi nhiều, target không scrape được

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-14
>
> Alert: `ApiErrorRateHigh` (warning, 5 phút; từ P4-16), `TargetDown` (critical, 2 phút) · Dashboard: `pti-api`, `pti-overview`, `pti-jvm` · Liên quan: DOC-30 §3 (Problem Details), DOC-31, DOC-32, DOC-39 §3–4 (container, healthcheck)

## Triệu chứng và ảnh hưởng

- `ApiErrorRateHigh`: hơn 1% request của `api` trả 5xx trong 5 phút (khi có > 0,2 request/giây). UI hiện lỗi hoặc dữ liệu cũ; Ops console không thao tác được.
- `TargetDown{job="pti-<app>"}`: Prometheus không scrape được `/actuator/prometheus` của một app trong 2 phút. Hoặc app chết, hoặc cổng management không trả lời (treo, GC dài, hết thread). Ức chế các alert dựa trên metric của chính app đó (DOC-28 §6.4). Riêng `pti-api` down thì `GtfsRtFeedStale` cũng không có dữ liệu.
- Trong EXP-01 và EXP-08, runner đã silence `TargetDown` trước khi kill (DOC-45): nếu nhận được alert trong lúc đó, runner hoặc silence có vấn đề.

## Kiểm tra

**`TargetDown`:**

1. Container: `make ps`. Trạng thái `restarting`, `exited`, hay `unhealthy`?
2. Lý do dừng: `docker inspect pti-<app>-1 --format '{{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}'`. Exit 137 kèm `OOMKilled=true` → hết bộ nhớ (limit ở DOC-39 §3.2).
3. Log lúc dừng: `make logs S=<app>` hoặc Loki `{service="<app>"}` trong 5 phút trước alert. Lỗi khởi động hay gặp: không kết nối được DB (RB-08), Kafka (RB-06 nhánh B), Keycloak (`api`: JWK set không tải được), migration chưa chạy (RB-03).
4. App còn chạy mà không scrape được: `curl -s -o /dev/null -w '%{http_code}\n' localhost:<cổng management>/actuator/health` (cổng: `api` 9081, `etl-stream` 9082, `etl-batch` 9083, `source-simulator` 9084, `triage-worker` 9085; DOC-38 §5). Treo → lấy thread dump trước khi restart: `docker compose exec <app> jcmd 1 Thread.print > /tmp/<app>-threads.txt`.
5. Prometheus: `http://localhost:9090/targets` hiện lỗi scrape cụ thể (timeout, connection refused, 404 do đổi đường dẫn).

**`ApiErrorRateHigh`:**

1. Endpoint và loại lỗi: dashboard `pti-api` → 5xx theo `uri` và theo Problem slug (`pti_api_problems_total{status=~"5.."}` theo `type`).
2. Log: `{service="api", level="ERROR"} | json` → lớp exception, `trace_id`; mở trace trong Tempo.
3. Phân loại theo slug (DOC-30 §3.2; hai slug 5xx là `internal-error` và `service-unavailable`):

   | Slug / dấu hiệu | Nguyên nhân | Nhánh |
   | --- | --- | --- |
   | `service-unavailable`, `CannotGetJdbcConnectionException`, `SQLTransientConnectionException` | Postgres chậm hoặc hết pool (`api_reader` 10, `replay_operator` 4) | RB-08 |
   | `service-unavailable` tập trung ở một `uri`, log có `QueryTimeoutException` | Truy vấn chậm của endpoint đó (thiếu index, khoảng thời gian quá rộng) | Sửa truy vấn |
   | `internal-error` với `NullPointerException`… | Lỗi lập trình | Issue |
   | 502/504 ở frontend nginx nhưng `api` không có lỗi | `api` không chạy hoặc restart liên tục | Nhánh `TargetDown` |

## Xử lý

| Nguyên nhân | Việc |
| --- | --- |
| OOM | `make restart S=<app>`. Lặp lại → tăng `mem_limit` hoặc `-XX:MaxRAMPercentage` (DOC-39 §3.2), ghi heap trước khi tăng: thêm `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp` tạm thời qua `JAVA_TOOL_OPTIONS` |
| Phụ thuộc chưa sẵn sàng khi khởi động (DB, Kafka, Keycloak) | Sửa phụ thuộc theo runbook tương ứng; container có `restart: unless-stopped` nên tự lên lại |
| App treo | Thread dump (Kiểm tra 4), rồi `make restart S=<app>`; mở issue với thread dump |
| Lỗi DB ở `api` | RB-08; `api` tự hồi phục khi pool có kết nối lại |
| Lỗi lập trình ở một endpoint | Issue kèm `trace_id`. Nếu endpoint đó làm hỏng trải nghiệm chính (live map), cân nhắc rollback image về tag trước (`PTI_IMAGE_TAG=<tag cũ> make up`) |
| Prometheus đổi cấu hình scrape sai | Sửa `deploy/compose/observability/prometheus.yml`, `make restart S=prometheus` |

## Trên k3d

- Pod không `Ready` hoặc restart liên tục: `kubectl -n pti get pods`, `kubectl -n pti describe pod <pod>` (sự kiện: probe fail, `OOMKilled`, `ImagePullBackOff`), `kubectl -n pti logs <pod> --previous`.
- `TargetDown` trên k3d tính theo từng pod (`instance`). `api` chạy 2 pod trở lên (HPA), nên một pod chết vẫn bắn alert trong khi người dùng không bị ảnh hưởng; PDB giữ ít nhất 1 pod khi rolling (DOC-40 §10).
- Target không có trong Prometheus (`localhost:9090/targets`): kiểm ServiceMonitor `kubectl -n pti get servicemonitor` và label `release: kube-prometheus-stack`; job phải có tên `pti-<app>` (DOC-40 §7.1).
- Khởi động lại app: `kubectl -n pti rollout restart deployment/<app>`. Prometheus: `kubectl -n monitoring rollout restart statefulset/prometheus-kube-prometheus-stack-prometheus`.
- Xác nhận: `make k8s-smoke` pass (DOC-40 §15) thay cho `make smoke`.

## Xác nhận đã xong

- `up{job="pti-<app>"} == 1` trên `http://localhost:9090/targets`; alert `resolved`.
- Tỷ lệ 5xx < 1% trong 15 phút; `make smoke` pass (DOC-39 §8).
- Sau khi `api` quay lại: SSE client nối lại được và nhận `resync` nếu mất quá buffer (DOC-26).

## Phòng ngừa và việc sau sự cố

- OOM lặp lại: đo lại ngân sách bộ nhớ (DOC-10 §5) với tải hiện tại.
- Mỗi lỗi 5xx do lập trình: thêm test contract hoặc integration tái hiện (DOC-44).
