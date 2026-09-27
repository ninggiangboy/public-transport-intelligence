# Môi trường dev cục bộ

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-38
> Phụ thuộc: [DOC-10](../03-architecture/quality-attributes.md) §5, [DOC-11](../03-architecture/tech-stack-and-versions.md), [DOC-17](../05-data/db-roles-and-grants.md) §6, [DOC-25](../06-design/source-simulator.md), [DOC-39](deploy-compose.md), [DR](../00-decision-register.md) (DR-40, 56, 61, 66, 67)
> Người dùng chính: mọi người phát triển; P1-01, P1-15; kiểm chứng NFR-07

Mục tiêu: một người mới clone repo, chạy **hai lệnh** (`make secrets`, `make up`), sau vài phút có hệ thống chạy đủ trên máy (NFR-07). Tài liệu này nói **máy cần gì, lệnh nào làm gì, cổng nào ở đâu và khi hỏng thì xem gì**. Chi tiết của từng service trong compose nằm ở DOC-39.

## 1. Yêu cầu máy

| Hạng mục | Tối thiểu | Khuyến nghị | Ghi chú |
| --- | --- | --- | --- |
| RAM máy | 16 GB | 16 GB trở lên | S-03 đo trên MacBook 16 GB (DOC-10 §5.1) |
| RAM cấp cho VM Docker | 8 GB (chỉ profile `core`) | **12 GB** khi bật `observability` và `triage` | Tổng `mem_limit` khi bật đủ profile khoảng 10,1 GB. Với 8 GB, VM sẽ OOM-kill container khi mọi thứ cùng tăng |
| CPU | 8 nhân | 8 nhân trở lên | Compose đặt tổng CPU limit khoảng 12 nhưng hiếm khi dùng hết cùng lúc |
| Ổ đĩa trống | **80 GB** | 100 GB | Trần dữ liệu với retention của compose khoảng 68 GB (DOC-10 §3.3), cộng image khoảng 6 GB |
| Hệ điều hành | macOS 14+ (Apple Silicon hoặc Intel), Linux x86_64/arm64 | macOS + OrbStack | Windows dùng WSL2; chưa kiểm thử, không hỗ trợ chính thức |
| Mạng | Lần đầu cần Internet để kéo image (khoảng 6 GB, riêng Debezium Connect 2,25 GB) và dependency | | Sau đó chạy offline được, trừ Jev (dùng `provider=fake`) |

Đặt RAM cho VM:

- **OrbStack:** `orb config set memory_mib 12288`, rồi `orb restart`.
- **Docker Desktop:** Settings → Resources → Memory = 12 GB → Apply & restart.
- Kiểm tra: `docker info --format '{{.MemTotal}}'` phải ≥ 12.000.000.000 khi định bật đủ profile. `make doctor` kiểm tra việc này.

## 2. Cài công cụ

Mọi công cụ dòng lệnh được ghim phiên bản trong `mise.toml` ở gốc repo (DOC-11 §5). Docker (OrbStack hoặc Docker Desktop), `make`, `git` và `openssl` cài riêng.

```bash
# 1. mise (một lần cho máy)
curl https://mise.run | sh
echo 'eval "$(~/.local/bin/mise activate zsh)"' >> ~/.zshrc && exec zsh

# 2. Trong thư mục repo: cài đúng phiên bản Java 25, Node 24, pnpm, Python 3.12, uv, kubectl, k3d, helm, helmfile, kubeseal, kubectl-cnpg, jq, kcat, gh
mise trust && mise install

# 3. Kiểm tra toàn bộ môi trường
make doctor
```

`make doctor` in ra một bảng `OK`/`FAIL` cho: phiên bản Java, Node, pnpm, Python, uv; Docker chạy được; RAM của VM; ổ trống ≥ 80 GB; các cổng ở §5 còn trống; có file `.env`. Lệnh trả mã khác 0 nếu có mục `FAIL`.

IDE: IntelliJ IDEA hoặc VS Code đều được. Code format bằng Spotless (`./gradlew spotlessApply`), không cần cài plugin format riêng. Trình duyệt cho frontend: Chrome hoặc Firefox bản mới.

## 3. Chạy lần đầu

```bash
git clone git@github.com:<owner>/public-transport-intelligence.git
cd public-transport-intelligence
mise install
make secrets          # tạo .env từ .env.example, sinh mật khẩu ngẫu nhiên, sinh s3.json (DOC-39 §5)
make up               # build image (Jib + Dockerfile), khởi động profile core, chờ tới khi healthy
```

`make up` lần đầu mất khoảng 10–15 phút (kéo image, tải dependency Gradle và pnpm, build). Từ lần thứ hai, khi image đã có, hệ thống healthy trong **dưới 5 phút** (NFR-07). Sau khi `make up` báo xong:

| Việc | Cách kiểm tra |
| --- | --- |
| Mọi container healthy hoặc đã hoàn tất | `make ps` |
| GTFS static đã nạp (lần đầu mất 1–2 phút sau khi `etl-batch` chạy, DOC-21) | `make psql-wh` → `SELECT status, loaded_at FROM dw.gtfs_feed_version;` có một dòng `ACTIVE` |
| Simulator đang phát | `make sim-status` có `activeVehicles > 0` (nếu bằng 0, xem §7 mục "Không có xe") |
| Có message trên Kafka | `make tail-gtfs.vehicle_positions` |
| CDC chạy | `make connectors` → hai connector `RUNNING` |
| Raw zone có file (sau khoảng 5 phút, DOC-09 §7) | `make s3-ls` |
| UI | Mở `http://localhost:8080`, đăng nhập `operator` / `operator` |

### 3.1 Giờ Chicago và `make clock-offset`

Feed dùng giờ Minneapolis. Ban ngày ở Việt Nam là ban đêm ở đó, lúc gần như không có xe. Trước khi demo hay chạy thực nghiệm, dời **đồng hồ nghiệp vụ** (DR-67):

```bash
make clock-offset AT=16:30   # giờ nghiệp vụ lúc này = 16:30 ở Chicago (cao điểm chiều); ghi PTI_CLOCK_OFFSET vào .env
make up                      # tạo lại các container app để nhận offset mới
make clock-offset AT=now     # về lại giờ thật (offset 0)
```

Offset được làm tròn tới phút. Mọi app dùng chung một giá trị. Đổi offset khi đang có dữ liệu cũ thì dữ liệu mới có thể "nhảy" về quá khứ hoặc tương lai so với dữ liệu cũ; khi cần sạch thì `make reset-warehouse` (§6).

## 4. Các lệnh `make`

`Makefile` ở gốc repo gọi `docker compose -f deploy/compose/compose.yaml --env-file .env`. Biến `S=<service>` chọn service cho các lệnh có service.

### 4.1 Vòng đời

| Lệnh | Việc |
| --- | --- |
| `make doctor` | Kiểm tra môi trường (§2) |
| `make tiles` | Cắt bản đồ nền Twin Cities bằng `pmtiles extract` vào `infra/tiles/twin-cities.pmtiles` (~60–90 MB, gitignored; ADR-0021). Bỏ qua nếu file đã có |
| `make secrets` | Tạo `.env` nếu chưa có; điền biến trống bằng `openssl rand -base64 24`; sinh `deploy/compose/.generated/s3.json`. Chạy lại không ghi đè giá trị đã có |
| `make images` | Build mọi image: `./gradlew jibDockerBuild` (các app Java), `docker build` cho `connect/` và `frontend/` |
| `make up` | `images` (nếu code đổi) rồi `docker compose --profile core up -d --wait`. Lệnh chỉ trả về khi mọi service healthy và mọi job một lần (`db-migrate`, `kafka-init`, `s3-init`, `kafka-connect-init`) đã xong |
| `make up-obs` | Thêm profile `observability` |
| `make up-triage` | Thêm profile `triage` (triage-worker; cần `TYPESAFE_API_KEY` hoặc `PTI_TRIAGE_PROVIDER=fake`) |
| `make up-all` | `core` + `observability` + `triage` |
| `make up-exp` | `core` + `experiment` + `observability`, với `PTI_WAREHOUSE_HOST=toxiproxy` cho `etl-stream` và `etl-stream-baseline` (DOC-39 §3.2, §3.6). Dùng cho EXP-01…04 (DOC-45) |
| `make up-demo` | Như `up-all`, thêm Spring profile `demo` cho `api` và `source-simulator`, và `PTI_DQ_MAX_CLOCK_SKEW=5m` cho `etl-stream` (DOC-39 §2) |
| `make down` | Dừng và xóa container, **giữ volume** |
| `make reset` | `down -v`: xóa mọi volume (warehouse, source, Kafka, raw zone). Giữ `.env` |
| `make reset-warehouse` | Chỉ xóa và tạo lại database `pti_warehouse` rồi chạy `db-migrate`. Giữ Kafka, raw zone, `pg-source` (ledger còn nguyên). Dùng cho UC-18, EXP-04 |
| `make restart S=<svc>` | Khởi động lại một service |

### 4.2 Quan sát

| Lệnh | Việc |
| --- | --- |
| `make ps` | Trạng thái và health của các container |
| `make logs [S=<svc>]` | Theo dõi log (mặc định mọi app). Log là JSON; `make logs S=etl-stream PRETTY=1` định dạng lại bằng `jq` |
| `make psql-wh [SU=1] [Q='<sql>']` | `psql` vào `pti_warehouse` với user `pti_owner` (`SU=1`: superuser `postgres`). Có `Q` thì chạy `psql -c "$Q"` rồi thoát. Ba lệnh `psql-*` đều nhận `SU` và `Q` |
| `make psql-src` | `psql` vào `ticketing_source` với `ticketing_owner` |
| `make psql-sim` | `psql` vào `pti_sim` với `sim_owner` |
| `make topics` | Liệt kê topic, số partition, lag của từng consumer group |
| `make tail-<topic>` | In message mới của topic, kèm key và headers. Ví dụ `make tail-gtfs.trip_updates`, `make tail-pti.events.ui` |
| `make connectors` | Trạng thái connector và task của Kafka Connect |
| `make s3-ls [P=<prefix>]` | Liệt kê object trong bucket `raw` (credential `admin`) |
| `make sim-status` | `GET /sim/status` (DOC-25 §8) |

### 4.3 Simulator và dữ liệu

| Lệnh | Việc |
| --- | --- |
| `make clock-offset AT=<HH:MM\|now>` | §3.1 |
| `make scenario NAME=<name> [ARGS='<json>']` | `POST /sim/scenarios/<name>`. Ví dụ `make scenario NAME=bunching ARGS='{"routeId":"18","duration":"PT15M"}'` |
| `make scenario-stop NAME=<name>` | `DELETE /sim/scenarios/<name>` |
| `make sim-rate GTFS=<x> [TICKETING=<y>]` | `PUT /sim/rate` |
| `make gtfs-load` | Ghi `job_request` để `etl-batch` chạy `GtfsStaticLoadJob` với feed đã cấu hình (DOC-21) |
| `make flag KEY=<key> VALUE=true\|false` | Upsert `ops.runtime_flag` bằng `pti_owner` (`updated_by = 'user:cli'`); hiệu lực trong ≤ 5 giây (DR-19). Tương đương `PUT /etl/flags/{key}` (P4) |
| `make job-run NAME=<job> [PARAMS='k=v,…'] [WAIT=1]` | Ghi `ops.job_request` `RUN` (DOC-19 §7.3); tham số được kiểm theo danh sách cho phép của job. Giá trị là danh sách thì các phần tử cách nhau bằng `+` (ví dụ `detectors=BUNCHING+DISRUPTION`), vì `,` đã ngăn cách các cặp. `WAIT=1`: chờ tới khi yêu cầu kết thúc (`DONE`, `REJECTED`, `FAILED`) rồi in kết quả; thoát mã 1 nếu không phải `DONE` |
| `make job-restart ID=<jobExecutionId>` | Ghi `ops.job_request` `RESTART`. Tương đương `POST /etl/jobs/{id}/restart` (P4) |
| `make job-stop ID=<jobExecutionId>` | Ghi `ops.job_request` `STOP` (DOC-19 §7.3): job dừng sau chunk đang chạy, trạng thái `STOPPED` |
| `make stop-apps` / `make start-apps` | Dừng / khởi động `etl-stream`, `etl-batch`, `api`, `triage-worker`, giữ hạ tầng (DOC-43 §4.1) |
| `make backup` / `make backup-verify [TS=…]` | `pg_dump` ba database vào `backups/<TS>/`, giữ 7 bản; kiểm tra bản sao lưu (DOC-43 §3) |
| `make restore-warehouse TS=<thư mục>` | Khôi phục `pti_warehouse` từ dump (DOC-43 §4.2) |
| `make ensure-partitions FROM=<YYYY-MM-DD>` | Tạo partition fact từ ngày `FROM` tới hôm nay + 7 (DOC-43 §4.1), cần trước khi replay ngày quá khứ |
| `make s3-shell` | Shell `amazon/aws-cli` với credential `admin`, endpoint SeaweedFS (DOC-43 §4.5) |
| `make replay SOURCE=<etl_source> FROM=<ISO> TO=<ISO> [RECOMPUTE=true] [WAIT=1]` | Ghi một dòng `ops.replay_request` `RAW_RANGE` (`requested_by = 'user:cli'`, `id` UUIDv7 sinh bằng `uuidgen`) bằng `pti_owner`, rồi in `id`. `RECOMPUTE` mặc định `false` như cột của DB; chỉ đặt `true` từ P4 (DOC-22 §4.1). `WAIT=1`: chờ tới khi yêu cầu `DONE`/`FAILED` rồi in `stats` (thoát mã 1 nếu `FAILED`). `FROM`/`TO` là giờ record Kafka (DR-70). Dùng trước khi có API (P3: EXP-04, RB-11) và khi API không chạy. Ràng buộc của DB (khoảng ≤ 7 ngày, một replay mỗi nguồn) vẫn áp dụng; ràng buộc `to_ts ≤ now − 10 phút` của API thì lệnh tự kiểm trước khi ghi |

### 4.4 Build và test

| Lệnh | Việc |
| --- | --- |
| `make fmt` | `./gradlew spotlessApply` và `pnpm -C frontend format` |
| `make lint` | `spotlessCheck`, Checkstyle, ESLint, `tsc --noEmit` |
| `make test` | Unit test của mọi module (Gradle `test`, Vitest) |
| `make it` | Integration test (Testcontainers; cần Docker, không cần compose chạy) |
| `make e2e` | Playwright trên compose đang chạy (`make up-demo` trước) |
| `make smoke` | Smoke test trên compose đang chạy (`deploy/compose/smoke.sh`, DOC-39 §8) |
| `make openapi` | Sinh `api/openapi.json` và type TypeScript cho frontend (DR-44) |

### 4.5 Kubernetes cục bộ (k3d, P7)

Chạy k3d thì dừng compose trước (`make down`); hai môi trường không đủ RAM để chạy cùng lúc. Chi tiết từng lệnh ở DOC-40 §14.

| Lệnh | Việc |
| --- | --- |
| `make k8s-sealing-key` | Sinh khóa niêm phong Sealed Secrets vào `~/.config/pti/sealed-secrets/` (một lần mỗi máy) |
| `make k3d-keys` | Sinh cặp khóa cho profile `static-jwt` (values `lite`) |
| `make k8s-seal` | Niêm phong Secret từ `.env` vào `deploy/k3d/sealed/` |
| `make k8s-up [ENV=lite\|dev\|staging]` | Tạo cluster, push image, `helmfile apply`, smoke. Mặc định `lite` |
| `make k8s-down` | Xóa cluster (giữ registry cục bộ) |
| `make k8s-stop` / `make k8s-start` | Dừng / khởi động lại cluster đã có, giữ dữ liệu và image (không cần Internet khi start) |
| `make k8s-images` | Build và push image app cùng `pti-connect-strimzi` vào `k3d-pti-registry:5000` |
| `make k8s-apply [ENV=…]` | `helmfile -e $ENV apply` |
| `make k8s-status` | Trạng thái CR hạ tầng, ScaledObject, HPA và mọi pod |
| `make k8s-smoke` | Smoke test k3d (DOC-40 §15) |
| `make k8s-psql-wh [Q=…]` | `psql` vào warehouse trên k3d với `pti_owner` |
| `make k8s-logs S=<app>` | Log của mọi pod một app |
| `make k8s-clock-offset AT=<HH:MM>` | Như `make clock-offset` cho k3d |
| `make k3d-token ROLE=<role> [TTL=1h]` | Token `static-jwt` cho test tải và chaos |
| `make k8s-load STEPS='1,2,5,10' [STEP=PT5M]` | Chạy kịch bản `load-ramp` trên k3d |

### 4.6 Demo (P8)

Kịch bản, thứ tự dùng và phương án dự phòng ở DOC-46.

| Lệnh | Việc |
| --- | --- |
| `make demo-reset` | `reset`, `clock-offset AT=16:20`, `up-demo`, `smoke` |
| `make demo-prewarm` | Bật trước `bunching` (tuyến 18, `PT45M`) và `disruption` (tuyến 21, `PT40M`) qua `/sim/scenarios` (DOC-46 §1.4) |
| `make demo-preflight` | Kiểm tra sẵn sàng trước buổi demo (E2E-DEMO-11), in `READY` hoặc danh sách thiếu |
| `make demo-kill-consumer` | `docker compose kill -s KILL etl-stream`, chờ 5 giây, `docker compose start etl-stream` |
| `make demo-check [ENV=compose\|k3d] SINCE=<duration>` | `uv run pti-exp check` (DOC-45 §2): đối chiếu ledger với warehouse, in `PASS`/`FAIL` |
| `make demo-switch-k3d` | `down`, `k8s-start`, `k8s-clock-offset AT=16:40`, chờ pod `Ready`, `k8s-smoke` |
| `make demo-switch-compose` | `k8s-stop`, `up-demo`, `smoke` |
| `make demo-pg-failover` | Xóa pod primary của `pti-warehouse` trên k3d và theo dõi tới khi có primary mới |

## 5. Bảng cổng

Mọi cổng chỉ bind vào `127.0.0.1`, không mở ra mạng LAN. Trong mạng compose, mọi app Spring Boot dùng cổng **8080** (ứng dụng) và **9080** (Actuator, `management.server.port`) để `/actuator/**` không lộ qua cổng ứng dụng (DOC-07).

| Service | Profile | Cổng host → container | Dùng để |
| --- | --- | --- | --- |
| frontend (nginx) | core | **8080** → 80 | SPA; nginx proxy `/api/` sang `api:8080` (cùng origin, không cần CORS) |
| api | core | **8081** → 8080, 9081 → 9080 | REST `/api/v1`, SSE; Actuator |
| etl-stream | core | 9082 → 9080 | Actuator (không có REST) |
| etl-batch | core | 9083 → 9080 | Actuator |
| source-simulator | core | **8084** → 8080, 9084 → 9080 | API `/sim/**` (DOC-25 §8); Actuator |
| triage-worker | triage | 9085 → 9080 | Actuator |
| keycloak | core | **8180** → 8080 | Đăng nhập, console admin `http://localhost:8180/admin` |
| kafka | core | 19092 → 19092 | Listener `EXTERNAL` quảng bá `localhost:19092`, cho kcat và app chạy từ IDE |
| kafka-connect | core | 18083 → 8083 | REST của Kafka Connect |
| pg-warehouse | core | 15432 → 5432 | `pti_warehouse` |
| pg-source | core | 15433 → 5432 | `ticketing_source`, `pti_sim` |
| seaweedfs | core | 18333 → 8333, 18888 → 8888 | S3 endpoint (path-style); filer UI |
| grafana | observability | **3000** → 3000 | Dashboard |
| prometheus | observability | 9090 → 9090 | |
| alertmanager | observability | 9093 → 9093 | |
| mailpit | observability | **8025** → 8025 | Hộp thư nhận email cảnh báo |
| alloy | observability | 12345 → 12345 | UI debug pipeline log |
| tempo, loki, otel-collector | observability | không mở ra host | Grafana đọc qua mạng compose |
| toxiproxy | experiment | 8474 → 8474 | API điều khiển proxy lỗi mạng |
| kafka-ui | tools | 8088 → 8080 | Xem topic và message qua web (tùy chọn) |
| Vite dev server | — (chạy ngoài compose) | 5173 | `pnpm dev` |

Nếu một cổng đã bị chiếm (ví dụ có Postgres cài sẵn ở 5432 thì không sao vì dự án dùng 15432), đổi cổng host bằng biến trong `.env` (`HOST_PORT_<SERVICE>`, DOC-39 §5). Không đổi cổng trong container.

## 6. Tài khoản và dữ liệu

### 6.1 Tài khoản

| Hệ thống | User | Mật khẩu | Ghi chú |
| --- | --- | --- | --- |
| UI / API (realm `pti`) | `viewer` | `viewer` | Role `viewer`: xem ops console, không thao tác ghi |
| UI / API (realm `pti`) | `operator` | `operator` | Role `viewer` + `operator`: replay, sửa DLQ, đổi cờ, Demo control |
| Keycloak admin (realm `master`) | `admin` | `KEYCLOAK_ADMIN_PASSWORD` trong `.env` | |
| Grafana | `admin` | `GRAFANA_ADMIN_PASSWORD` trong `.env` | Datasource và dashboard được provision sẵn |
| PostgreSQL | các role ở DOC-17 §2 | biến tương ứng trong `.env` | `make psql-*` tự lấy mật khẩu |
| SeaweedFS | `admin`, `connect`, `etl` | `S3_*` trong `.env` | DOC-39 §3.5 |

Mật khẩu `viewer`/`operator` cố định trong file realm (`deploy/compose/keycloak/realm-pti.json`) vì chỉ dùng cho dev và demo. Hành khách không cần đăng nhập (DR-40).

### 6.2 Dữ liệu có sẵn sau `make up`

| Dữ liệu | Nguồn | Khi nào có |
| --- | --- | --- |
| Dimension và lịch (GTFS static) | `sample-data/gtfs/metrotransit-mn-20260926.zip`, `GtfsStaticLoadJob` tự chạy khi chưa có feed `ACTIVE` (DOC-21) | 1–2 phút sau khi `etl-batch` healthy |
| `dim_date` 2024–2030 | Migration | Ngay sau `db-migrate` |
| Điểm bán (187) | Simulator (DOC-25 §9.1) | Khi simulator khởi động |
| Fact GTFS-rt, giao dịch vé | Simulator → Kafka/CDC → ETL | Liên tục |
| `runtime_flag` | Migration V5_2 (DOC-29 §4) | Ngay sau `db-migrate` |

Không có bản dump dữ liệu mẫu nào được commit. Muốn có vài giờ dữ liệu để phát triển analytics hay UI thì để hệ thống chạy, có thể tăng tốc bằng `make sim-rate GTFS=5` (nhiều message hơn nhưng không nhiều chuyến hơn, DR-68).

### 6.3 Reset

| Muốn | Lệnh | Giữ lại |
| --- | --- | --- |
| Khởi động lại sạch hoàn toàn | `make reset && make up` | `.env` |
| Chỉ làm lại warehouse (thử replay, UC-18) | `make reset-warehouse` | Kafka (7 ngày), raw zone, `ticketing_source`, ledger |
| Xóa DLQ và lịch sử job, giữ fact | Không có lệnh. Dùng SQL trong `make psql-wh` nếu thật sự cần | |
| Xóa ledger | Ledger tự xóa sau 2 ngày (DR-28). `make reset` nếu cần ngay | |

## 7. Chạy một app từ IDE

Để debug một app, dừng container của nó rồi chạy từ IDE với profile `local`. Profile `local` (`application-local.yml` trong mỗi app) trỏ tới cổng host ở §5.

```bash
docker compose -f deploy/compose/compose.yaml stop etl-stream
set -a && source .env && set +a         # nạp mật khẩu vào shell (hoặc dùng plugin EnvFile của IDE)
./gradlew :etl:bootRun --args='--spring.profiles.active=stream,local'
```

| App | Profile khi chạy từ IDE | Lưu ý |
| --- | --- | --- |
| etl | `stream,local` hoặc `batch,local` | Đừng chạy cùng lúc bản trong container và bản IDE với cùng profile `stream` nếu muốn debug ổn định: hai consumer chia partition với nhau |
| api | `local` | Kiểm tra JWT với issuer `http://localhost:8180/realms/pti` |
| source-simulator | `local` | Chỉ nên có một simulator chạy tại một thời điểm, nếu không message sẽ bị phát hai lần |
| frontend | `pnpm -C frontend dev` | Vite proxy `/api` sang `http://localhost:8081`; Keycloak client cho phép redirect `http://localhost:5173/*` |

## 8. Lỗi thường gặp

| Triệu chứng | Nguyên nhân thường gặp | Cách xử lý |
| --- | --- | --- |
| Container thoát với mã 137, `make ps` báo `OOMKilled` | VM Docker không đủ RAM | Tăng RAM VM lên 12 GB (§1); hoặc chỉ chạy `make up` (core) |
| `make up` treo ở `kafka-connect` | Lần đầu kéo image 2,25 GB; hoặc Connect khởi động chậm (30–60 giây) | Chờ; xem `make logs S=kafka-connect` |
| `db-migrate` thoát mã khác 0 | Sửa một migration đã chạy (checksum mismatch) hoặc SQL lỗi | Không sửa migration đã merge. Trên máy dev, `make reset` rồi `make up`. Xem log `make logs S=db-migrate` |
| `password authentication failed` | `.env` đổi sau khi volume Postgres đã được tạo (bootstrap chỉ chạy một lần) | `make reset`, hoặc đổi mật khẩu bằng `ALTER ROLE` cho khớp |
| API trả 401 dù đã đăng nhập | Truy cập UI bằng `127.0.0.1` thay vì `localhost`, nên issuer trong token khác cấu hình | Luôn dùng `http://localhost:8080` |
| Không có xe trên bản đồ, `activeVehicles = 0` | Giờ Chicago đang là 02:00–04:30 | `make clock-offset AT=16:30 && make up` (§3.1) |
| Xe có nhưng bản đồ không có nền | Chưa có file PMTiles | `make tiles` (tải file đã cắt ở S-05) hoặc dùng style online khi dev (DR-47) |
| `make s3-ls` báo `InvalidAccessKeyId` | Chưa sinh `s3.json` hoặc sinh trước khi đổi `.env` | `make secrets && make restart S=seaweedfs` |
| Ổ đĩa đầy dần | Partition vehicle position (khoảng 2 GB/ngày) hoặc WAL bị replication slot giữ khi Connect dừng | `make connectors`; kiểm tra retention (DOC-10 §3.3); `make reset` nếu cần |
| `etl-stream` log `Consumer paused` liên tục | Postgres chậm hoặc circuit breaker mở (DOC-20) | Xem `make logs S=pg-warehouse`, Grafana dashboard "Pipeline" |
| Cổng đã bị chiếm | Service khác trên máy dùng cổng đó | Đổi `HOST_PORT_*` trong `.env` |
| Image không chạy trên Apple Silicon | Image chỉ có bản amd64 | Mọi image đã ghim ở DOC-11 đều có arm64 (kiểm tra ở S-03). Image app build bằng Jib cho cả `linux/amd64` và `linux/arm64` |

## 9. Quy ước làm việc với repo

- Nhánh `main` luôn build được; làm việc trên `feat/…`, `fix/…`, `docs/…`; PR squash merge (master plan §7.3). Chi tiết trong `CONTRIBUTING.md` (tạo ở P1-01).
- Commit và tiêu đề PR theo Conventional Commits, tiếng Anh. Tài liệu trong `docs/` viết tiếng Việt (DR-61).
- `.env`, `deploy/compose/.generated/` và `backups/` (DOC-43) nằm trong `.gitignore`. CI chạy gitleaks (NFR-06).
- Trước khi mở PR: `make fmt lint test`.
