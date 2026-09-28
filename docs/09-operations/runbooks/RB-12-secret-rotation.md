# RB-12: Xoay vòng secret

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-12
>
> Thủ tục (không gắn alert) · NFR-06 · Liên quan: DOC-17 §3.1 và §6 (role, mật khẩu), DOC-39 §5 (biến môi trường), DOC-27 (Keycloak, token webhook), DOC-18 §3 (identity S3)

## Khi nào dùng

- Nghi ngờ lộ secret: gitleaks báo trong CI (DOC-41), `.env` bị chia sẻ nhầm, log in ra giá trị.
- Trước khi mở môi trường cho người khác (demo trên máy dùng chung, bàn giao).
- Định kỳ trên k3d (P8): mỗi 90 ngày cho mật khẩu role DB và key S3.

Mỗi lần xoay vòng ghi vào issue: secret nào, lý do, thời điểm; **không** ghi giá trị.

## Nguyên tắc chung (compose)

1. Chỉ xoay một nhóm mỗi lần, xác nhận xong mới sang nhóm khác.
2. Sinh giá trị mới: xóa giá trị của biến trong `.env` (để `VAR=`), rồi `make secrets`. Lệnh chỉ điền biến trống và render lại `deploy/compose/.generated/` (DOC-39 §5).
3. Áp dụng: `make up` tạo lại những container có biến môi trường đổi. File trong `.generated/` đổi thì compose **không** tự nhận ra: dùng `make restart S=<service>`.
4. Mật khẩu không bao giờ xuất hiện trên dòng lệnh hay trong lịch sử shell: dùng `\password` của `psql` hoặc `psql -v`.
5. Có thể có vài giây lỗi kết nối giữa lúc đổi ở phía máy chủ và lúc app khởi động lại. Chấp nhận trên compose; `etl-stream` retry và không mất dữ liệu (RB-03 §pause `backoff`).

## Theo nhóm secret

### 1. Mật khẩu role Postgres (`*_PASSWORD` của DOC-17 §6)

Ví dụ `ETL_WRITER_PASSWORD`:

1. Sinh giá trị mới vào `.env` (nguyên tắc 2), rồi đọc lại: `grep ^ETL_WRITER_PASSWORD= .env`.
2. Đổi ở Postgres, bằng superuser (dán giá trị khi được hỏi):

   ```bash
   make psql-wh SU=1
   # pti_warehouse=# \password etl_writer
   ```

   Role của `pg-source` (`TICKETING_OWNER_PASSWORD`, `SIM_OWNER_PASSWORD`, `SOURCE_SIMULATOR_PASSWORD`, `DEBEZIUM_PASSWORD`, và `EXPERIMENT_RUNNER_PASSWORD` phía nguồn) đổi bằng `make psql-src SU=1`. `experiment_runner` có ở cả hai instance: đổi cả hai.
3. `make up`: tạo lại các container dùng biến đó.

| Biến | Container phải tạo lại / việc thêm |
| --- | --- |
| `PTI_OWNER_PASSWORD` | `db-migrate` (chạy ở lần `make up` kế tiếp); lệnh `make psql-wh`, `make flag`, `make replay` đọc từ `.env` |
| `ETL_WRITER_PASSWORD` | `etl-stream`, `etl-batch`, `etl-stream-baseline` (profile `experiment`) |
| `TRIAGE_WRITER_PASSWORD` | `triage-worker` |
| `API_READER_PASSWORD` | `api`, **và** `grafana` (datasource Postgres dùng `api_reader`, DOC-28 §1) |
| `REPLAY_OPERATOR_PASSWORD` | `api` |
| `EXPERIMENT_RUNNER_PASSWORD` | Không có container; runner đọc `.env` ở lần chạy sau |
| `TICKETING_OWNER_PASSWORD`, `SIM_OWNER_PASSWORD` | `db-migrate` |
| `SOURCE_SIMULATOR_PASSWORD` | `source-simulator` |
| `DEBEZIUM_PASSWORD` | `kafka-connect` (connector đọc `${env:DEBEZIUM_PASSWORD}`). Sau khi container chạy lại, kiểm tra task `RUNNING` (RB-09 nhánh A nếu `FAILED`) |

### 2. Superuser Postgres (`PG_WAREHOUSE_SUPERUSER_PASSWORD`, `PG_SOURCE_SUPERUSER_PASSWORD`)

Biến `POSTGRES_PASSWORD` chỉ có tác dụng lúc khởi tạo volume, nên đổi `.env` thôi là không đủ. Sinh giá trị mới, rồi dùng mật khẩu **cũ** để đăng nhập và đổi: `make psql-wh SU=1` → `\password postgres` (tương tự `pg-source`). Sau đó `.env` và DB khớp nhau; không container nào cần tạo lại.

### 3. S3 (`S3_ADMIN_*`, `S3_CONNECT_*`, `S3_ETL_*`)

1. Sinh key mới (nguyên tắc 2); `make secrets` render lại `.generated/s3.json`.
2. `make restart S=seaweedfs` (đọc lại identity; object không bị ảnh hưởng).
3. `make up`: tạo lại `kafka-connect` (S3 sink, `S3_CONNECT_*`) và `etl-batch` (`S3_ETL_*`).
4. Kiểm tra: `make s3-ls` chạy được; connector `pti-raw-sink` `RUNNING` (`make connectors`); object mới xuất hiện dưới `raw/gtfs.vehicle_positions/dt=<hôm nay>/` trong ≤ 5 phút.

### 4. Keycloak (`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_EXPERIMENTS_CLIENT_SECRET`)

Keycloak trên compose không có volume và import lại realm mỗi lần tạo container (DOC-39 §3). Sinh giá trị mới rồi `make up` (container `keycloak` được tạo lại). Hệ quả: mọi phiên đăng nhập và token hiện có mất hiệu lực; người dùng đăng nhập lại. Runner thực nghiệm đọc secret mới ở lần chạy sau.

### 5. Token webhook Alertmanager (`ALERTMANAGER_WEBHOOK_TOKEN`)

Sinh giá trị mới (`make secrets` ghi cả `.generated/webhook-token`), rồi `make restart S=alertmanager` và `make up` (tạo lại `api`). Kiểm tra: `pti_alert_webhook_total{outcome="unauthorized"}` không tăng; gây thử một alert (DOC-42 §3) và thấy dòng mới trong `ops.alert_event`.

### 6. Grafana (`GRAFANA_ADMIN_PASSWORD`)

Biến chỉ áp dụng khi Grafana khởi tạo lần đầu. Đổi bằng:

```bash
docker compose exec grafana grafana cli admin reset-admin-password "$(grep ^GRAFANA_ADMIN_PASSWORD= .env | cut -d= -f2-)"
```

### 7. Jev (`TYPESAFE_API_KEY`)

Tạo key mới ở phía nhà cung cấp, thu hồi key cũ, điền vào `.env`, `make up` (tạo lại `triage-worker`). Kiểm tra: `pti_triage_calls_total{outcome="ok"}` tăng (DOC-24); circuit breaker `decision-model` đóng (DOC-24 §11).

### 8. Không xoay vòng

`KAFKA_CLUSTER_ID` không phải secret; đổi giá trị làm Kafka không khởi động được với volume hiện có.

## k3d

Nguồn giá trị trên k3d vẫn là `.env`; Secret trong cluster được sinh từ đó bằng `make k8s-seal` (DOC-40 §8.2).

1. Đổi giá trị trong `.env` như bước tương ứng ở trên (không chạy `make up`).
2. `make k8s-seal`, kiểm `git diff deploy/k3d/sealed/` chỉ đổi đúng Secret mong muốn, commit.
3. `make k8s-apply ENV=<env>`. Sealed Secrets controller cập nhật Secret.
4. Theo loại secret:

| Secret | Việc sau khi Secret đổi |
| --- | --- |
| Mật khẩu role (`pti-db-<role>`) | CNPG tự `ALTER ROLE` (DOC-17 §6). `kubectl -n pti rollout restart deploy/<app>` cho app dùng role đó; `debezium`: `kubectl -n pti annotate kafkaconnect pti-connect strimzi.io/restart=true` rồi chờ connector `RUNNING` |
| `pti-s3` | Rollout restart `etl-batch`; restart Kafka Connect như trên; SeaweedFS đọc `pti-s3-config`: `kubectl -n pti rollout restart statefulset/seaweedfs` |
| `pti-keycloak` | `kubectl -n pti rollout restart deploy/keycloak`: Keycloak trên k3d chạy `start-dev` không có database và import lại realm mỗi lần khởi động (DOC-40 §6.6), giống compose. Không dùng Admin API |
| `pti-alert-webhook` | Không cần restart: `api` và Alertmanager đọc lại file mount khi đổi (DOC-27 §6). Kiểm `pti_alert_webhook_total{outcome="ok"}` tăng |
| `pti-grafana` | `kubectl -n monitoring rollout restart deploy/kube-prometheus-stack-grafana` |
| `pti-jev` | `kubectl -n pti rollout restart deploy/triage-worker` |
| `pti-static-jwt` | `make k3d-keys`, `make k8s-seal`, apply, rollout restart `api`; token cũ mất hiệu lực |
| Khóa niêm phong (mất hoặc lộ) | `make k8s-sealing-key` sinh cặp mới, cập nhật `deploy/k3d/sealed/pub-cert.pem` và GitHub Secrets `SEALED_SECRETS_CRT`/`SEALED_SECRETS_KEY`, `make k8s-seal` niêm phong lại mọi Secret, rồi dựng lại cluster (`make k8s-down && make k8s-up`) vì controller cũ giữ khóa cũ (DOC-40 §8.1) |

5. Xác nhận: `make k8s-smoke` pass; `kubectl -n pti logs` không có `password authentication failed` trong 10 phút.

## Xác nhận đã xong

- `make ps`: mọi service healthy; không có lỗi `password authentication failed` hay `AccessDenied` trong Loki 10 phút sau khi đổi (`{service=~".+"} |~ "authentication failed|AccessDenied|InvalidAccessKeyId|401"`).
- Luồng dữ liệu chạy: lag không tăng (RB-02), connector `RUNNING`, `make smoke` (DOC-39 §8) pass.
- Secret cũ không còn dùng được: thử đăng nhập bằng giá trị cũ (với role DB: `psql` với mật khẩu cũ bị từ chối).

## Phòng ngừa và việc sau sự cố

- Secret bị lộ vào git: xoay vòng ngay, rồi xóa khỏi lịch sử (`git filter-repo`) và force-push nhánh bị ảnh hưởng **sau khi hỏi Owner** (thao tác khó đảo ngược).
- Bổ sung pattern vào cấu hình gitleaks nếu loại secret đó chưa được bắt.
