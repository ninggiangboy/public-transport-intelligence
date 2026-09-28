# Triển khai trên Kubernetes cục bộ (k3d)

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-40
> Phụ thuộc: [DOC-07](../03-architecture/system-context-and-containers.md), [DOC-10](../03-architecture/quality-attributes.md) §3.3–6, [DOC-11](../03-architecture/tech-stack-and-versions.md) §2.1, [DOC-17](../05-data/db-roles-and-grants.md) §3.2, §6, [DOC-18](../05-data/data-lifecycle.md), [DOC-20](../06-design/etl-streaming.md) §7, [DOC-24](../06-design/ai-triage.md) §11.3, [DOC-26](../06-design/realtime-delivery.md) §10, [DOC-27](../06-design/security.md) §3.3, §7, [DOC-28](../06-design/observability.md), [DOC-29](../06-design/configuration-reference.md), [DOC-39](deploy-compose.md), [ADR-0014](../04-adr/0014-deployment-units.md), [ADR-0024](../04-adr/0024-flyway-migration-job.md), [ADR-0028](../04-adr/0028-kubernetes-tooling.md), [DR](../00-decision-register.md) (DR-54, 55, 56, 66, 74)
> Người dùng chính: P7-01…P7-10, P8-02 (deploy k3d trong CI), [EXP-07](../10-testing/experiments/EXP-07-autoscaling.md), [EXP-08](../10-testing/experiments/EXP-08-chaos.md), DOC-46 bước 7, DOC-42 (lệnh k3d)

Compose (DOC-39) là môi trường chính. k3d là môi trường thứ hai, dùng cho những gì compose không làm được: nhiều pod consumer scale theo lag, 3 Kafka broker, Postgres failover, tiêm lỗi bằng Chaos Mesh (SDD §12.4, §12.7, §12.8). Tài liệu này là **đặc tả để viết `deploy/k3d/`, `deploy/helmfile.yaml.gotmpl`, `deploy/helm/`**. Mọi image, mọi cấu hình Spring và mọi file dùng chung (topic, connector, realm, rule, dashboard) giống compose; khác biệt chỉ nằm trong values (§5).

**Khi chạy k3d thì tắt compose** (`make down`): hai môi trường không đủ RAM để chạy cùng lúc (DOC-10 §5).

## 1. Bố cục file

```
deploy/
  versions.env                          # shared with compose: image tags/digests, chart versions (DOC-11)
  topics.yaml                           # shared: topic list (DOC-09 §1)
  k3d/
    cluster.yaml                        # §3.1
    scripts/k8s-up.sh, k8s-down.sh      # §14
    scripts/push-images.sh              # retag local images → k3d-pti-registry:5000
    scripts/seal-secrets.sh             # .env → Secret → kubeseal → sealed/<ns>/*.yaml (§8)
    scripts/smoke.sh                    # §15
    sealed/pti/*.yaml, sealed/monitoring/*.yaml   # committed SealedSecrets
  helmfile.yaml.gotmpl                  # §4: releases, environments dev | staging | lite
  helm/
    operators/<release>.values.yaml     # values of third-party charts (strimzi, cnpg, keda, …)
    pti-infra/                          # Kafka, topics, Connect, CNPG, SeaweedFS, Keycloak, Mailpit, Toxiproxy, jev-stub
      Chart.yaml, values.yaml, values-{dev,staging,lite}.yaml, templates/
      files/ -> symlinks (§1.1)
    pti/                                # apps, db-migrate hook, connectors, rules, dashboards, KEDA, HPA, PDB, NetworkPolicy, backup
      Chart.yaml, values.yaml, values-{dev,staging,lite}.yaml, templates/_app.tpl, templates/*.yaml
      files/ -> symlinks (§1.1)
connect/
  Dockerfile.strimzi                    # §6.3
chaos/                                  # Chaos Mesh manifests for EXP-08 and demo step 7 (§13)
```

### 1.1 File dùng chung với compose

Helm chỉ đọc được file nằm trong thư mục chart. Các file dưới đây được **symlink** vào `files/` của chart (Helm 3 đọc theo symlink khi cài từ thư mục), để compose và k3d luôn dùng cùng một nguồn:

| Symlink trong chart | Trỏ tới | Dùng cho |
| --- | --- | --- |
| `pti-infra/files/topics.yaml` | `deploy/topics.yaml` | `KafkaTopic` (§6.2) |
| `pti-infra/files/realm-pti.json` | `deploy/compose/keycloak/realm-pti.json` | ConfigMap import realm (§6.6) |
| `pti/files/connectors/` | `connect/connectors/` | `KafkaConnector` (§6.3) |
| `pti/files/rules/` | `deploy/compose/observability/prometheus/rules/` | `PrometheusRule` (§7.6) |
| `pti/files/dashboards/` | `deploy/compose/observability/grafana/dashboards/` | ConfigMap `grafana_dashboard=1` (§7.6) |

Test KD-02 (§17) kiểm `helm template` render được mọi file này, nên symlink hỏng làm CI đỏ.

## 2. Môi trường (values)

| | `dev` | `staging` | `lite` |
| --- | --- | --- | --- |
| Mục đích | Chạy thử chart, dev trên k8s | Đủ replica như SDD §12.4 | EXP-07, EXP-08, demo bước 7 trên máy 16 GB |
| Máy tối thiểu | VM Docker 12 GB | VM Docker ≥ 20 GB (máy khác hoặc VM thuê) | VM Docker 13 GB, compose tắt, IDE và trình duyệt đóng bớt |
| Kafka | 1 node (controller + broker), RF 1 | 3 node, RF 3, `min.insync.replicas` 2 | 3 node, RF 3, `min.insync.replicas` 2, mỗi node 768 MB |
| Kafka Connect | 1 worker | 2 worker | 1 worker |
| Warehouse (CNPG) | 1 instance | 1 primary + 1 replica | 1 primary + 1 replica |
| Pooler (PgBouncer) | `rw` 1 | `rw` 2, `ro` 2 | `rw` 1, `ro` 1 |
| Nguồn ticketing (CNPG) | 1 | 1 | 1 (DR-55) |
| `etl-stream` | KEDA 1→4 | KEDA 1→4 | KEDA 1→4 |
| `etl-batch` | 1 | 2 | 1 |
| `api` | 1 (không HPA) | HPA 2→6 | HPA 2→4 |
| `triage-worker` | KEDA 1→3, `provider=fake` | KEDA 1→3, `provider=jev` | KEDA 1→3, `provider=jev` qua `jev-stub` và Toxiproxy (§13.2) |
| `frontend` | 1 | 2 | 1 |
| Keycloak | Có | Có | **Không**; API dùng `static-jwt` (DOC-27 §3.3) |
| Observability | kube-prometheus-stack, Loki, Tempo, Alloy, OTel Collector, Mailpit | Như dev | Chỉ kube-prometheus-stack (Prometheus, Alertmanager, Grafana, kube-state-metrics) và Mailpit; tracing tắt |
| Chaos Mesh | Không | Có | Có |
| Toxiproxy, `jev-stub` | Không | Không | Có |

`demo: true` (mặc định `false`, chỉ hợp lệ với `dev` và `staging`) thêm Spring profile `demo` cho `api`, `source-simulator` và `PTI_DQ_MAX_CLOCK_SKEW=5m` cho `etl-stream`, như `make up-demo` (DOC-39 §2). Với `lite`, chart từ chối `demo: true` (`fail` trong template) vì `static-jwt` không chạy cùng profile `demo` (DOC-27 §3.3). Demo bước 7 dùng `lite` và điều khiển bằng lệnh `make` (DOC-46).

## 3. Cluster

### 3.1 `deploy/k3d/cluster.yaml`

```yaml
apiVersion: k3d.io/v1alpha5
kind: Simple
metadata:
  name: pti
servers: 1
agents: 2
image: rancher/k3s:${K3S_VERSION}            # pinned in versions.env (DOC-11 §2.1)
registries:
  create:
    name: k3d-pti-registry
    host: "0.0.0.0"
    hostPort: "5000"
volumes:
  - volume: ${PTI_REPO}/sample-data/gtfs:/var/lib/pti/feed
    nodeFilters: [all]
  - volume: ${PTI_REPO}/infra/tiles:/var/lib/pti/tiles
    nodeFilters: [all]
ports:
  - port: 127.0.0.1:8080:80                  # Traefik → frontend Ingress
    nodeFilters: [loadbalancer]
  - port: 127.0.0.1:8180:30180               # Keycloak (dev, staging)
    nodeFilters: [server:0]
  - port: 127.0.0.1:8084:30084               # source-simulator
    nodeFilters: [server:0]
  - port: 127.0.0.1:3000:30300               # Grafana
    nodeFilters: [server:0]
  - port: 127.0.0.1:9090:30090               # Prometheus (runner EXP-07/08)
    nodeFilters: [server:0]
  - port: 127.0.0.1:9093:30093               # Alertmanager (runner silences)
    nodeFilters: [server:0]
  - port: 127.0.0.1:8025:30825               # Mailpit UI
    nodeFilters: [server:0]
  - port: 127.0.0.1:18474:30474              # Toxiproxy API (lite)
    nodeFilters: [server:0]
options:
  k3s:
    extraArgs:
      - arg: --kubelet-arg=container-log-max-size=10Mi
        nodeFilters: [all]
      - arg: --kubelet-arg=container-log-max-files=5
        nodeFilters: [all]
  runtime:
    serversMemory: 3g
    agentsMemory: 5g
```

- Ba node: Kafka 3 broker và 2 instance Postgres được rải trên các node khác nhau bằng anti-affinity (§6), nên "một node mất" là một kịch bản có nghĩa.
- `serversMemory`/`agentsMemory` giới hạn RAM mỗi node (tổng 13 GB) và làm kubelet báo đúng dung lượng, để scheduler từ chối pod khi hết chỗ thay vì để OOM killer của VM giết container bất kỳ.
- Cổng chỉ bind `127.0.0.1`, cùng số với compose (DOC-38 §5) để URL, realm và runner không phải đổi.
- Giữ metrics-server có sẵn của k3s: HPA của `api` cần nó để đọc CPU (§9.3). KEDA dùng metrics adapter riêng, không xung đột.
- `PTI_REPO` là đường dẫn tuyệt đối của repo; `k8s-up.sh` đặt biến này rồi gọi `envsubst < cluster.yaml | k3d cluster create --config -`.
- `/var/lib/pti/feed` và `/var/lib/pti/tiles` có trên mọi node; pod đọc qua `hostPath` chỉ đọc (§7.1). Feed GTFS và PMTiles không nằm trong image.

### 3.2 Image

- App: `make k8s-images` = `make images` (Jib, tag `local`) rồi `push-images.sh` gắn tag `k3d-pti-registry:5000/pti-<app>:<git-sha>` và push. Values đặt `image.registry: k3d-pti-registry:5000`, `image.tag: <git-sha>` (helmfile đọc `git rev-parse --short=12 HEAD`). Không dùng tag `latest`.
- CI (DOC-41 §10.3) chạy `make k8s-up K8S_IMAGE_SOURCE=ghcr PTI_IMAGE_TAG=<sha>`: bỏ bước build và push, dùng image GHCR `ghcr.io/<owner>/pti-<app>:<git-sha>` (cả `pti-connect-strimzi`). Package GHCR là public (DR-56) nên không cần `imagePullSecrets`.
- Hạ tầng: image công khai theo `deploy/versions.env`. `k8s-up.sh` chạy `k3d image import` cho các image lớn (Kafka Strimzi, Postgres CNPG, Connect) nếu đã có trong Docker cục bộ, để lần dựng cluster thứ hai không tải lại.

### 3.3 Namespace

| Namespace | Nội dung |
| --- | --- |
| `pti` | Mọi thứ của hệ thống: CR Kafka và Postgres, SeaweedFS, Keycloak, Mailpit, Toxiproxy, app. Runbook dùng `kubectl -n pti` |
| `strimzi` | Strimzi cluster operator (theo dõi namespace `pti`) |
| `cnpg-system` | CloudNativePG operator |
| `keda` | KEDA operator và metrics adapter |
| `chaos-mesh` | Chaos Mesh (controller, daemon, dashboard tắt) |
| `kube-system` | Sealed Secrets controller (`sealed-secrets-controller`), Traefik, metrics-server của k3s |
| `monitoring` | kube-prometheus-stack, Loki, Tempo, Alloy, OTel Collector |

## 4. helmfile

```yaml
# deploy/helmfile.yaml.gotmpl (excerpt)
environments:
  dev:     { values: [ env/dev.yaml ] }
  staging: { values: [ env/staging.yaml ] }
  lite:    { values: [ env/lite.yaml ] }
---
repositories:
  - { name: strimzi,  url: https://strimzi.io/charts/ }
  - { name: cnpg,     url: https://cloudnative-pg.github.io/charts }
  - { name: kedacore, url: https://kedacore.github.io/charts }
  - { name: chaos-mesh, url: https://charts.chaos-mesh.org }
  - { name: sealed-secrets, url: https://bitnami-labs.github.io/sealed-secrets }
  - { name: prometheus-community, url: https://prometheus-community.github.io/helm-charts }
  - { name: grafana, url: https://grafana.github.io/helm-charts }
  - { name: open-telemetry, url: https://open-telemetry.github.io/opentelemetry-helm-charts }

releases:
  - name: sealed-secrets     # installed first; k8s-up.sh has already created the sealing key Secret (§8.1)
    namespace: kube-system
    chart: sealed-secrets/sealed-secrets
    version: {{ env "CHART_SEALED_SECRETS" }}
  - name: strimzi
    namespace: strimzi
    chart: strimzi/strimzi-kafka-operator
    version: {{ env "CHART_STRIMZI" }}
    values: [ helm/operators/strimzi.values.yaml ]     # watchNamespaces: [pti]
  - name: cnpg
    namespace: cnpg-system
    chart: cnpg/cloudnative-pg
    version: {{ env "CHART_CNPG" }}
  - name: keda
    namespace: keda
    chart: kedacore/keda
    version: {{ env "CHART_KEDA" }}
  - name: chaos-mesh
    namespace: chaos-mesh
    chart: chaos-mesh/chaos-mesh
    installed: {{ ne .Environment.Name "dev" }}
    values: [ helm/operators/chaos-mesh.values.yaml ]  # runtime: containerd, socketPath: /run/k3s/containerd/containerd.sock
  - name: kube-prometheus-stack
    namespace: monitoring
    chart: prometheus-community/kube-prometheus-stack
    values: [ helm/operators/kps.values.yaml, helm/operators/kps.{{ .Environment.Name }}.values.yaml ]
  - name: loki          # installed: dev, staging only
  - name: tempo         # installed: dev, staging only
  - name: alloy         # installed: dev, staging only (DaemonSet)
  - name: otel-collector  # installed: dev, staging only
  - name: pti-infra
    namespace: pti
    chart: ./helm/pti-infra
    values: [ helm/pti-infra/values.yaml, helm/pti-infra/values-{{ .Environment.Name }}.yaml ]
    needs: [ strimzi/strimzi, cnpg-system/cnpg, kube-system/sealed-secrets ]
    hooks:
      - events: [ postsync ]
        command: deploy/k3d/scripts/wait-infra.sh     # kubectl wait: kafka/pti Ready, cluster/* Ready, pooler pods Ready, seaweedfs Ready (timeout 15m)
  - name: pti
    namespace: pti
    chart: ./helm/pti
    values: [ helm/pti/values.yaml, helm/pti/values-{{ .Environment.Name }}.yaml ]
    set:
      - { name: image.tag, value: {{ env "PTI_IMAGE_TAG" }} }
    needs: [ pti/pti-infra, keda/keda, monitoring/kube-prometheus-stack ]
```

- Phiên bản chart của mọi release nằm trong `deploy/versions.env` (`CHART_*`) cùng tag image hạ tầng; P7-02 chốt số cụ thể và ghi vào DOC-11 §2.1. Phiên bản Strimzi phải hỗ trợ đúng bản Kafka 4.x mà compose dùng.
- `helmfile -e lite apply` idempotent; `helmfile -e lite diff` trước mỗi thay đổi.
- Helm `--wait` chờ Deployment, StatefulSet và Job nhưng **không** chờ CR (`Kafka`, `Cluster`); `wait-infra.sh` làm việc này để hook Flyway của `pti` (§11.1) không chạy khi Postgres chưa sẵn sàng.

## 5. Values theo app (bảng env của k3d)

Đây là bảng env đầy đủ cho k3d mà DOC-29 §5 tham chiếu. Tên biến và ý nghĩa giống DOC-39 §3.2; chỉ giá trị khác. Mỗi app có ConfigMap `pti-<app>-env` (giá trị thường) và `env[].valueFrom.secretKeyRef` cho mật khẩu.

### 5.1 Env chung cho mọi app Spring

| Biến | Giá trị k3d | Ghi chú |
| --- | --- | --- |
| `TZ`, `JAVA_TOOL_OPTIONS`, `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` | Như compose (`-XX:MaxRAMPercentage=75 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC`, 8080, 9080) | |
| `SPRING_PROFILES_INCLUDE` | `k8s` (+ `demo` khi `demo: true`; + `static-jwt` cho `api` ở `lite`) | Profile `k8s` chỉ đặt `pti.env=k3d` (label `env` của log và metric, DOC-28 §2) |
| `PTI_CLOCK_OFFSET` | `.Values.global.clockOffset` (mặc định `0s`) | Runner EXP-07/08 đặt bằng `helmfile -e lite apply --set global.clockOffset=…` rồi rollout (§14) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `pti-kafka-bootstrap:9092` | Service do Strimzi tạo cho Kafka tên `pti` |
| `PTI_TRACING_ENABLED` | `true` (dev, staging), `false` (lite) | Ánh xạ tới `management.tracing.enabled` (DOC-29 §2) |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | Cột k3d của DOC-28 §5.1 | |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://otel-collector.monitoring:4318/v1/traces` | |

### 5.2 Env riêng

| App | Biến | Giá trị k3d |
| --- | --- | --- |
| `etl-stream` | `SPRING_PROFILES_ACTIVE` | `stream` |
| | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://pti-warehouse-pooler-rw:5432/pti_warehouse?prepareThreshold=0` (PgBouncer transaction mode, DR-24) |
| | `SPRING_DATASOURCE_USERNAME` / `ETL_WRITER_PASSWORD` | `etl_writer` / Secret `pti-db-etl-writer` key `password` |
| | `PTI_DQ_MAX_CLOCK_SKEW` | `1h`; `5m` khi `demo: true` |
| | `PTI_RETENTION_VEHICLE_POSITION` / `PTI_RETENTION_TRIP_UPDATE` | `3d` / `30d` (như compose, DOC-29 §3.3) |
| `etl-batch` | `SPRING_PROFILES_ACTIVE` | `batch` |
| | Datasource | Như `etl-stream` |
| | `PTI_S3_ENDPOINT` | `http://seaweedfs:8333` |
| | `PTI_S3_ACCESS_KEY` / `PTI_S3_SECRET_KEY` | Secret `pti-s3` key `etl-access-key` / `etl-secret-key` |
| | `SPRING_CLOUD_AWS_S3_PATH_STYLE_ACCESS_ENABLED`, `SPRING_CLOUD_AWS_REGION_STATIC` | `true`, `us-east-1` |
| | `PTI_GTFS_BOOTSTRAP_LOCATION` | `file:/feed/metrotransit-mn-20260926.zip` (hostPath `/var/lib/pti/feed` mount `/feed`) |
| `api` | `PTI_DATASOURCE_READER_URL` | `jdbc:postgresql://pti-warehouse-pooler-ro:5432,pti-warehouse-pooler-rw:5432/pti_warehouse?prepareThreshold=0&targetServerType=preferSecondary&hostRecheckSeconds=10` (đọc replica, rơi về primary khi không có replica, §9.5); `dev` không có replica nên chỉ `pooler-rw` |
| | `PTI_DATASOURCE_READER_HIKARI_MAXLIFETIME` | `300000` (5 phút; kết nối đã rơi về primary quay lại replica sau failover, §9.5) |
| | `PTI_DATASOURCE_READER_USERNAME` / `API_READER_PASSWORD` | `api_reader` / Secret `pti-db-api-reader` |
| | `PTI_DATASOURCE_OPERATOR_URL` | `…pooler-rw…` |
| | `PTI_DATASOURCE_OPERATOR_USERNAME` / `REPLAY_OPERATOR_PASSWORD` | `replay_operator` / Secret `pti-db-replay-operator` |
| | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | `http://keycloak:8080/realms/pti/protocol/openid-connect/certs` (dev, staging) |
| | `PTI_API_SECURITY_ISSUER` | `http://localhost:8180/realms/pti` (dev, staging) |
| | `PTI_API_SECURITY_STATIC_JWT_PUBLIC_KEY_FILE` | `/etc/pti/static-jwt/public.pem` (lite; Secret `pti-static-jwt` mount chỉ đọc) |
| | `PTI_API_ALERT_WEBHOOK_TOKEN_FILE` | `/etc/pti/webhook/token` (Secret `pti-alert-webhook` mount) |
| `source-simulator` | `PTI_DATASOURCE_TICKETING_URL` / `PTI_DATASOURCE_SIM_URL` | `jdbc:postgresql://pti-source-rw:5432/ticketing_source` / `…/pti_sim` (không qua pooler) |
| | User / mật khẩu | `source_simulator` / Secret `pti-db-source-simulator` |
| | `PTI_SIM_FEED_LOCATION`, `PTI_SIM_FEED_SHA256` | `file:/feed/metrotransit-mn-20260926.zip`, như compose |
| `triage-worker` | `SPRING_DATASOURCE_URL` / user | `…pooler-rw…` / `triage_writer`, Secret `pti-db-triage-writer` |
| | `PTI_TRIAGE_PROVIDER` | `fake` (dev), `jev` (staging, lite) |
| | `TYPESAFE_API_KEY` | Secret `pti-jev` key `api-key` (staging); `stub` (lite) |
| | `PTI_TRIAGE_JEV_BASE_URL` | Mặc định SDK (staging); `http://toxiproxy:18080` (lite, §13.2) |
| | `PTI_TRIAGE_HEALTH_ETL_URL` | `http://etl-stream:9080` (mặc định; Service `etl-stream` cổng `management`) |
| `frontend` | `PTI_KEYCLOAK_URL` | `http://localhost:8180` (dev, staging); rỗng (lite: ẩn "Sign in") |
| | `PTI_KEYCLOAK_REALM`, `PTI_KEYCLOAK_CLIENT_ID`, `PTI_MAP_STYLE`, `PTI_MAP_TILE_ORIGINS`, `PTI_EXTRA_PROFILES` | Như compose; tiles từ hostPath `/var/lib/pti/tiles` |

`PTI_API_SECURITY_STATIC_JWT_PUBLIC_KEY_FILE` và `PTI_API_ALERT_WEBHOOK_TOKEN_FILE` là env của `pti.api.security.static-jwt.public-key-file` và `pti.api.alert-webhook.token-file` (DOC-27 §13), theo quy ước §1.2 của DOC-29.

### 5.3 Tài nguyên

| Thành phần | Request CPU / RAM | Limit RAM | dev | staging | lite |
| --- | --- | --- | --- | --- | --- |
| `etl-stream` | 500m / 640Mi | 768Mi | 1→4 | 1→4 | 1→4 |
| `etl-batch` | 250m / 512Mi | 640Mi | 1 | 2 | 1 |
| `api` | 250m / 512Mi | 640Mi | 1 | 2→6 | 2→4 |
| `triage-worker` | 100m / 320Mi | 384Mi | 1→3 | 1→3 | 1→3 |
| `source-simulator` | 500m / 448Mi | 512Mi | 1 | 1 | 1 |
| `frontend` | 50m / 24Mi | 32Mi | 1 | 2 | 1 |
| Kafka node (Strimzi) | 500m / 768Mi | 768Mi (`-Xms384m -Xmx384m`) | 1 × 1Gi | 3 × 1Gi | 3 × 768Mi |
| Kafka Connect | 250m / 1Gi | 1280Mi (`-Xmx512m`, DR-81) | 1 | 2 | 1 |
| Strimzi entity operator | 100m / 256Mi | 384Mi | | | |
| Warehouse (CNPG) | 500m / 1Gi | 1Gi (`shared_buffers=256MB`; staging 1,5Gi và 512MB) | 1 | 2 | 2 |
| Nguồn (CNPG) | 100m / 384Mi | 384Mi (`shared_buffers=64MB`) | 1 | 1 | 1 |
| PgBouncer | 50m / 32Mi | 64Mi | 1 | 2 + 2 | 1 + 1 |
| SeaweedFS | 100m / 256Mi | 384Mi | 1 | 1 | 1 |
| Keycloak | 250m / 640Mi | 768Mi | 1 | 1 | — |
| Prometheus | 250m / 640Mi | 1Gi | | | |
| Grafana, Alertmanager, kube-state-metrics, Mailpit | nhỏ | 192Mi / 64Mi / 64Mi / 64Mi | | | |
| Loki, Tempo, Alloy (mỗi node), OTel Collector | | 384Mi / 384Mi / 128Mi / 192Mi | Có | Có | — |

Ước lượng `lite` ở trạng thái cao nhất (4 pod `etl-stream`, 4 pod `api`, 3 pod `triage-worker`): khoảng 11,8 GB tổng limit, gồm khoảng 1,5 GB của operator và k3s. P7-04 đo RAM thật bằng `kubectl top pod -A` dưới tải ×1 và ×10, ghi vào §16. Nếu không vừa 13 GB thì cắt theo thứ tự: `api` HPA 2→3; `triage-worker` 1→2; Kafka Connect limit 896Mi.

## 6. Hạ tầng (chart `pti-infra`)

### 6.1 Kafka (Strimzi)

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata: { name: dual, labels: { strimzi.io/cluster: pti } }
spec:
  replicas: {{ .Values.kafka.replicas }}           # 1 (dev) | 3 (staging, lite)
  roles: [controller, broker]
  storage: { type: persistent-claim, size: 10Gi, class: local-path, deleteClaim: true }
  resources: { requests: { cpu: 500m, memory: 768Mi }, limits: { memory: 768Mi } }
  jvmOptions: { "-Xms": 384m, "-Xmx": 384m }
  template:
    pod:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - topologyKey: kubernetes.io/hostname
              labelSelector: { matchLabels: { strimzi.io/pool-name: dual } }
---
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: pti
  annotations: { strimzi.io/node-pools: enabled, strimzi.io/kraft: enabled }
spec:
  kafka:
    version: {{ .Values.kafka.version }}          # same Kafka 4.x as compose
    listeners:
      - { name: plain, port: 9092, type: internal, tls: false }
    config:
      auto.create.topics.enable: false
      default.replication.factor: {{ .Values.kafka.rf }}          # 1 | 3
      min.insync.replicas: {{ .Values.kafka.minIsr }}             # 1 | 2
      offsets.topic.replication.factor: {{ .Values.kafka.rf }}
      transaction.state.log.replication.factor: {{ .Values.kafka.rf }}
      transaction.state.log.min.isr: {{ .Values.kafka.minIsr }}
      group.initial.rebalance.delay.ms: 0
      log.segment.bytes: 268435456
  entityOperator:
    topicOperator: {}
  kafkaExporter:                                  # consumer-group lag for Grafana and KEDA fallback (DOC-28 §2 principle 2)
    groupRegex: "pti-.*"
    topicRegex: ".*"
```

- Anti-affinity bắt buộc theo node: 3 broker trên 3 node. Với `dev` (1 node Kafka) điều kiện này không ảnh hưởng.
- Producer của app giữ `acks=all` và idempotent (DOC-09); với `min.insync.replicas=2`, một broker chết không làm lỗi ghi.
- PDB do Strimzi tạo (`maxUnavailable: 1`).

### 6.2 Topic

Template đọc `files/topics.yaml` và sinh một `KafkaTopic` cho mỗi topic, với `partitions` giữ nguyên, `replicas` = `.Values.kafka.rf`, `config` giữ nguyên trừ ghi đè sau:

| Topic | Ghi đè trên k3d | Lý do |
| --- | --- | --- |
| `gtfs.vehicle_positions`, `gtfs.trip_updates`, `ticketing.sales.cdc`, `ticketing.sale_points.cdc` | `retention.ms: 86400000` (24 giờ) | RF 3 nhân ba dung lượng (DOC-10 §3.3, DOC-18 §1.3) |
| Mọi topic, khi `rf = 3` | `min.insync.replicas: 2` | |
| `connect-*` | `cleanup.policy: compact`, `replicas` theo `rf` | Strimzi KafkaConnect cần topic này; tạo sẵn như compose |

Topic Operator không giảm partition; đổi số partition chỉ được tăng (như `create-topics.sh`).

### 6.3 Kafka Connect

Strimzi yêu cầu image dựa trên image Kafka của Strimzi, nên image `pti-connect` của compose (dựa trên Debezium) không dùng lại được. `connect/Dockerfile.strimzi`:

```dockerfile
FROM quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-${KAFKA_VERSION}
USER root:root
# Debezium PostgreSQL connector (same version as compose) + Aiven S3 sink (same as S-04). Checksums verified.
COPY --chmod=0644 build/plugins/ /opt/kafka/plugins/
USER 1001
```

`make k8s-images` tải hai plugin (URL và SHA-256 trong `versions.env`, cùng nguồn với `connect/Dockerfile`: Debezium PostgreSQL connector 3.6.3.Final và Aiven S3 sink 3.4.3, DOC-11), giải nén vào `connect/build/plugins/`, build và push `k3d-pti-registry:5000/pti-connect-strimzi:<tag>`.

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnect
metadata:
  name: pti-connect
  annotations: { strimzi.io/use-connector-resources: "true" }
spec:
  replicas: {{ .Values.connect.replicas }}       # 1 | 2
  image: {{ .Values.image.registry }}/pti-connect-strimzi:{{ .Values.connect.imageTag }}
  bootstrapServers: pti-kafka-bootstrap:9092
  config:
    group.id: pti-connect
    config.storage.topic: connect-configs
    offset.storage.topic: connect-offsets
    status.storage.topic: connect-status
    config.storage.replication.factor: -1        # use broker default
    offset.storage.replication.factor: -1
    status.storage.replication.factor: -1
    offset.flush.interval.ms: 300000             # same as compose: raw-zone files close at most every 5 min (DOC-09 §7)
    config.providers: dir
    config.providers.dir.class: org.apache.kafka.common.config.provider.DirectoryConfigProvider
  resources: { requests: { cpu: 250m, memory: 1Gi }, limits: { memory: 1280Mi } }
  jvmOptions: { "-Xms": 256m, "-Xmx": 512m }
  template:
    pod:
      volumes:
        - { name: debezium, secret: { secretName: pti-db-debezium } }
        - { name: s3, secret: { secretName: pti-s3 } }
    connectContainer:
      volumeMounts:
        - { name: debezium, mountPath: /mnt/secrets/debezium, readOnly: true }
        - { name: s3, mountPath: /mnt/secrets/s3, readOnly: true }
```

`KafkaConnector` (trong chart `pti`, vì cần publication `pti_ticketing` và bảng heartbeat do Flyway tạo, §11.1) được sinh từ `files/connectors/*.json`: `spec.class` = `config["connector.class"]`, `spec.tasksMax` = `config["tasks.max"]`, `spec.config` = phần còn lại, với các thay thế:

| Trong file (compose) | Trên k3d |
| --- | --- |
| `${env:DEBEZIUM_PASSWORD}` | `${dir:/mnt/secrets/debezium:password}` |
| `${env:S3_CONNECT_ACCESS_KEY}` / `${env:S3_CONNECT_SECRET_KEY}` | `${dir:/mnt/secrets/s3:connect-access-key}` / `${dir:/mnt/secrets/s3:connect-secret-key}` |
| `"database.hostname": "pg-source"` | `pti-source-rw` |
| `"aws.s3.endpoint": "http://seaweedfs:8333"` | Giữ nguyên (Service `seaweedfs` cùng tên) |

`DirectoryConfigProvider` đọc mỗi key của Secret như một file; DOC-17 §6 và DOC-39 §3.4 gọi chung là "config provider đọc file mount từ Secret". Test KD-06 kiểm `GET /connectors/<name>/config` không chứa mật khẩu dạng rõ.

### 6.4 PostgreSQL (CloudNativePG)

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: pti-warehouse }
spec:
  instances: {{ .Values.warehouse.instances }}      # 1 (dev) | 2 (staging, lite)
  imageName: ghcr.io/cloudnative-pg/postgresql:17.11   # same major/minor as compose (DOC-11)
  primaryUpdateStrategy: unsupervised
  storage: { size: 20Gi, storageClass: local-path }
  walStorage: { size: 4Gi, storageClass: local-path }
  resources: { requests: { cpu: 500m, memory: 1Gi }, limits: { memory: 1Gi } }
  affinity: { enablePodAntiAffinity: true, podAntiAffinityType: required, topologyKey: kubernetes.io/hostname }
  postgresql:
    parameters:
      max_connections: "150"                       # DOC-10 §4
      shared_buffers: 256MB
      work_mem: 16MB
      maintenance_work_mem: 128MB
      max_wal_size: 2GB
      log_min_duration_statement: "500"
      timezone: UTC
  bootstrap:
    initdb:
      database: pti_warehouse
      owner: pti_owner
      secret: { name: pti-db-pti-owner }
      localeCollate: C.UTF-8
      localeCType: C.UTF-8
  managed:
    roles:
      - { name: etl_writer,        ensure: present, login: true, connectionLimit: 60, passwordSecret: { name: pti-db-etl-writer } }
      - { name: triage_writer,     ensure: present, login: true, connectionLimit: 20, passwordSecret: { name: pti-db-triage-writer } }
      - { name: api_reader,        ensure: present, login: true, connectionLimit: 60, passwordSecret: { name: pti-db-api-reader } }
      - { name: replay_operator,   ensure: present, login: true, connectionLimit: 30, passwordSecret: { name: pti-db-replay-operator } }
      - { name: experiment_runner, ensure: present, login: true, connectionLimit: 5,  passwordSecret: { name: pti-db-experiment-runner } }
  monitoring: { enablePodMonitor: true }
```

- `connectionLimit` của từng role lấy theo DOC-17 §3.1; con số trên là **ví dụ về cú pháp**, giá trị thật chép từ `10-bootstrap.sh` (một nguồn). Test KD-07 so hai nơi.
- `statement_timeout` của `api_reader`, `replay_operator` (DOC-31 §9) đặt bằng `ALTER ROLE … SET` trong migration repeatable `R__role_settings.sql` của bộ `warehouse`, vì `managed.roles` không có trường này. Compose đặt cùng giá trị ở bootstrap; migration chạy lại trên compose cũng không hại.
- `pti-source` tương tự với `instances: 1`, database `ticketing_source`/`ticketing_owner`, `wal_level: logical`, `max_replication_slots: "4"`, `max_wal_senders: "4"`, `max_slot_wal_keep_size: 4GB`, `max_connections: "50"`, roles `sim_owner`, `source_simulator`, `debezium` (`replication: true`), `experiment_runner`; database thứ hai bằng resource `Database` `pti_sim` (owner `sim_owner`) như DOC-17 §3.2.
- CNPG tự tạo Service `pti-warehouse-rw`, `-ro`, `-r` và PDB cho primary.
- Tên field của CNPG được xác minh ở P7-03 với phiên bản đã pin; sai lệch ghi vào DOC-17 §3.2.

**Pooler (PgBouncer):**

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Pooler
metadata: { name: pti-warehouse-pooler-rw }
spec:
  cluster: { name: pti-warehouse }
  instances: {{ .Values.warehouse.poolerInstances }}
  type: rw
  pgbouncer:
    poolMode: transaction
    parameters:
      default_pool_size: "40"         # DOC-10 §4
      max_client_conn: "300"
      max_prepared_statements: "0"    # apps use prepareThreshold=0 (DR-24)
```

`pti-warehouse-pooler-ro` giống hệt với `type: ro` (không có ở `dev`). Kiểm tra dung lượng kết nối ở §9.4.

### 6.5 SeaweedFS

StatefulSet một replica chạy đúng lệnh của compose (`server -dir=/data -s3 -s3.config=/etc/seaweedfs/s3.json -s3.port=8333 -master.volumeSizeLimitMB=1024 -volume.max=0`), PVC 30Gi `local-path`, `s3.json` từ Secret `pti-s3-config` (ADR-0028 giải thích vì sao không dùng chart chính thức). Service `seaweedfs` cổng 8333 (S3) và 9333 (master, cho probe).

Job `s3-init` (Helm hook `post-install,post-upgrade`, `hook-delete-policy: before-hook-creation`) chạy cùng `s3-init.sh` của compose (mount qua ConfigMap) với credential `admin`, tạo bucket `raw` và `backup`, bật versioning, đặt lifecycle DOC-18 §1.4 cho `raw` và "hết hạn sau 7 ngày" cho `backup` (§12). Lifecycle `raw` giống compose (DOC-18 §1.4).

### 6.6 Keycloak (`dev`, `staging`)

Deployment 1 replica, `start-dev --import-realm --http-port=8080`, cùng env với compose (`KC_HOSTNAME=http://localhost:8180`, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, `KC_HEALTH_ENABLED=true`), không có database ngoài: realm được import lại từ ConfigMap `pti-realm` (từ `files/realm-pti.json`) mỗi lần pod khởi động, giống compose. `KEYCLOAK_EXPERIMENTS_CLIENT_SECRET` và `KC_BOOTSTRAP_ADMIN_PASSWORD` lấy từ Secret `pti-keycloak`. Service `keycloak` (ClusterIP 8080, cho `api` lấy JWKS) và `keycloak-nodeport` (30180, cho trình duyệt). Probe: `/health/ready` cổng 9000.

Đổi secret của client trên k3d: sửa `.env`, `make k8s-seal`, `helmfile apply`, rồi `kubectl -n pti rollout restart deploy/keycloak` (realm import lại); không cần Admin API (RB-12).

### 6.7 Mailpit, Toxiproxy, `jev-stub`

- **Mailpit:** Deployment, Service `mailpit` (SMTP 1025, UI 8025 → NodePort 30825). Alertmanager gửi email tới `mailpit.pti:1025`.
- **Toxiproxy** (`lite`): Deployment `ghcr.io/shopify/toxiproxy:2.x`, proxy `jev` lắng nghe `18080` tới `jev-stub:8080`; API 8474 → NodePort 30474 (host 18474). Postgres **không** đi qua Toxiproxy trên k3d: lỗi mạng tới Postgres dùng `NetworkChaos` của Chaos Mesh (§13).
- **`jev-stub`** (`lite`): WireMock 3.x với mapping sinh từ fixture S-01 (`triage-worker/src/contractTest/resources/jev/`), trả response hợp lệ với độ trễ cố định 300 ms (median của DR-36). Mục đích: EXP-07/08 dùng đúng đường code `JevDecisionModel` và Resilience4j mà không tốn quota và không phụ thuộc Internet.

## 7. App (chart `pti`)

### 7.1 Template chung `_app.tpl`

Mỗi app trong `.Values.apps.<name>` sinh ra:

| Resource | Nội dung |
| --- | --- |
| `Deployment` | `replicas` (bỏ qua khi có KEDA hoặc HPA), label `app.kubernetes.io/name: <app>`, `app.kubernetes.io/part-of: pti`, `app.kubernetes.io/version: <tag>` |
| `Service` | `http` 8080 (trừ `etl-stream`, `etl-batch`, `triage-worker`: không có HTTP ngoài actuator), `management` 9080 |
| `ConfigMap` `pti-<app>-env` | Env thường §5; annotation checksum trên pod template để đổi ConfigMap là rollout |
| `ServiceMonitor` | Cổng `management`, path `/actuator/prometheus`, interval 15s, label `release: kube-prometheus-stack`; `relabelings` đặt `job` = `pti-<app>` như compose (DOC-28 §2), để alert `TargetDown` (`up{job=~"pti-.*"}`) và query KEDA dùng chung một tên |
| `PodDisruptionBudget` | §10.1 |
| `NetworkPolicy` | §10.2 |

Pod template chung:

```yaml
securityContext: { runAsNonRoot: true, runAsUser: 1000, fsGroup: 1000, seccompProfile: { type: RuntimeDefault } }
terminationGracePeriodSeconds: 45            # > spring.lifecycle.timeout-per-shutdown-phase 30s (DOC-20 §7)
containers:
  - name: app
    image: "{{ .Values.image.registry }}/pti-{{ .image }}:{{ .Values.image.tag }}"
    securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
    ports: [ { name: http, containerPort: 8080 }, { name: management, containerPort: 9080 } ]
    envFrom: [ { configMapRef: { name: "pti-{{ .name }}-env" } } ]
    startupProbe:   { httpGet: { path: /actuator/health/liveness,  port: management }, periodSeconds: 5, failureThreshold: 36 }   # up to 3 min
    livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: management }, periodSeconds: 10, failureThreshold: 3, timeoutSeconds: 3 }
    readinessProbe: { httpGet: { path: /actuator/health/readiness, port: management }, periodSeconds: 5,  failureThreshold: 3, timeoutSeconds: 3 }
    lifecycle:
      preStop: { exec: { command: ["sh", "-c", "sleep 5"] } }   # let Service endpoints update before shutdown (DOC-26 §7)
    volumeMounts:
      - { name: tmp, mountPath: /tmp }                           # readOnlyRootFilesystem
volumes:
  - { name: tmp, emptyDir: { sizeLimit: 256Mi } }
```

- `readOnlyRootFilesystem`: Spring Boot và Tomcat chỉ ghi `/tmp`. `source-simulator` và `etl-batch` mount thêm `hostPath /var/lib/pti/feed` tại `/feed` (`readOnly: true`, `type: Directory`); `frontend` (nginx, user 101) mount `hostPath /var/lib/pti/tiles` tại `/usr/share/nginx/html/tiles` và `emptyDir` cho `/var/cache/nginx`, `/var/run` và thư mục render `env.js`.
- Liveness không phụ thuộc hệ thống ngoài; readiness gồm datasource, Kafka và (etl-stream) listener đã được gán partition, như compose (DOC-39 §4). Nhờ vậy Postgres failover làm readiness `DOWN` nhưng không làm restart pod.
- `frontend` dùng probe `GET /healthz` cổng 80 (nginx).

### 7.2 Rolling update

| App | Chiến lược | Lý do |
| --- | --- | --- |
| `api`, `frontend` | `RollingUpdate`, `maxUnavailable: 0`, `maxSurge: 1` | SDD §12.4; SSE client tự nối lại pod khác (DOC-26 §7) |
| `etl-stream` | `RollingUpdate`, `maxUnavailable: 1`, `maxSurge: 0` | Tránh hai rebalance liên tiếp; `CooperativeStickyAssignor` giữ phần lớn partition (DOC-20 §7). Pod cũ commit offset rồi mới rời group |
| `etl-batch` | `RollingUpdate`, `maxUnavailable: 1`, `maxSurge: 0` | ShedLock đảm bảo một pod chạy job; job dang dở được khôi phục (DR-24) |
| `triage-worker` | `RollingUpdate`, `maxUnavailable: 1`, `maxSurge: 0` | Lease hết hạn trả việc về hàng đợi (DOC-24 §5) |
| `source-simulator` | `Recreate` | Một instance; hai instance cùng lúc sẽ phát trùng và ghi ledger trùng |

P7-07 kiểm rolling update `etl-stream` khi đang có tải ×2: so warehouse với ledger, không mất, không trùng (test KD-10).

### 7.3 Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: frontend
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  rules:
    - host: localhost
      http:
        paths:
          - { path: /, pathType: Prefix, backend: { service: { name: frontend, port: { number: 80 } } } }
```

Nginx của `frontend` proxy `/api/` và `/api/v1/stream` tới `api:8080` như compose (DOC-26 §10), nên Ingress chỉ có một backend và không có middleware nén; SSE không bị Traefik nén vì Traefik không bật compress nếu không khai báo. `/internal/` bị nginx trả 404.

### 7.4 Migration (Flyway)

Job `db-migrate` là Helm hook `pre-install,pre-upgrade`, `hook-weight: "-5"`, `hook-delete-policy: before-hook-creation,hook-succeeded`, `backoffLimit: 2`, `activeDeadlineSeconds: 600`. Image `pti-db-migrate`, chạy ba bộ Flyway như compose (DOC-17 §5) với URL **trực tiếp** tới primary (`pti-warehouse-rw`, `pti-source-rw`), không qua PgBouncer (DDL và advisory lock của Flyway cần session). Credential: Secret `pti-db-pti-owner`, `pti-db-ticketing-owner`, `pti-db-sim-owner`.

Hook chạy trước khi Deployment đổi image, nên theo expand/contract (ADR-0024) pod cũ vẫn chạy đúng trên schema mới. P7-05 kiểm bằng một migration expand thêm cột nullable trong khi rolling update (test KD-11).

### 7.5 Secret mount

| Secret | App | Cách dùng |
| --- | --- | --- |
| `pti-db-<role>` (`kubernetes.io/basic-auth`: `username`, `password`) | Theo §5.2 | `secretKeyRef` → biến `*_PASSWORD` |
| `pti-s3` | `etl-batch`, Connect, `s3-init`, backup | `secretKeyRef` / mount |
| `pti-s3-config` (`s3.json`) | SeaweedFS | mount |
| `pti-alert-webhook` (`token`) | `api` (ns `pti`), Alertmanager (ns `monitoring`) | mount file; `api` đọc lại khi file đổi (DOC-27 §6) |
| `pti-keycloak` | Keycloak | env |
| `pti-jev` (`api-key`) | `triage-worker` (staging) | `secretKeyRef`, `optional: true` |
| `pti-static-jwt` (`public.pem`, `private.pem`) | `api` (chỉ `public.pem`) | mount một key (`items`) |
| `pti-grafana` | Grafana (ns `monitoring`) | `admin.existingSecret` của chart |

### 7.6 Rule, dashboard và Alertmanager

- `PrometheusRule` `pti-rules` sinh từ `files/rules/pti-*.yml` (cùng file với compose, DOC-28 §6), label `release: kube-prometheus-stack`. Thêm một rule riêng k3d trong `pti-k8s.yml` của chart: `BackupJobFailed` (§12).
- Dashboard: mỗi file trong `files/dashboards/` thành một ConfigMap label `grafana_dashboard: "1"` ở namespace `monitoring`; sidecar của Grafana nạp. Ngoài chín dashboard dùng chung với compose (DOC-28 §7), chart có thêm `files/dashboards-k8s/pti-k8s.json` ("Kubernetes scaling", chỉ có trên k3d), dùng cho EXP-07 và demo bước 7 (DOC-46): số pod available/desired của `etl-stream`, `api`, `triage-worker` (`kube_deployment_status_replicas_available`, `kube_horizontalpodautoscaler_status_desired_replicas`); giá trị trigger KEDA (`keda_scaler_metrics_value`); consumer lag theo group; `pti:chunk_duration:p95_5m` với đường ngưỡng 2 s; số broker Kafka sẵn sàng và under-replicated partition; vai trò từng instance CNPG (`cnpg_pg_replication_in_recovery`) và replication lag; tỷ lệ probe API thành công; restart của pod app (`kube_pod_container_status_restarts_total`). Datasource: Prometheus trong cluster, Loki, Tempo (dev, staging), Postgres warehouse (`pti-warehouse-pooler-ro`, user `api_reader`, Secret mount).
- Alertmanager (`kps.values.yaml`): route và receiver giống `alertmanager.yml` của compose; webhook `http://api.pti:8080/internal/alerts/alertmanager` với `authorization.credentials_file: /etc/alertmanager/secrets/pti-alert-webhook/token` (`alertmanagerSpec.secrets: [pti-alert-webhook]`), email tới `mailpit.pti:1025`.
- Runner EXP-07/08 dùng Alertmanager qua NodePort 30093 (host 9093) để tạo silence, như compose.

## 8. Secret (Sealed Secrets)

### 8.1 Khóa niêm phong

- `make k8s-sealing-key` (một lần mỗi máy): sinh cặp khóa RSA 4096 và chứng chỉ tự ký 10 năm vào `~/.config/pti/sealed-secrets/{tls.crt,tls.key}` (ngoài repo, quyền 600).
- `k8s-up.sh` tạo Secret `sealed-secrets-key` trong `kube-system` với label `sealedsecrets.bitnami.com/sealed-secrets-key=active` từ hai file này **trước** khi cài controller. Controller dùng khóa này thay vì tự sinh, nên file niêm phong trong repo giải mã được trên mọi cluster mới.
- `tls.crt` được commit ở `deploy/k3d/sealed/pub-cert.pem` để `kubeseal --cert` chạy được mà không cần cluster.
- `k8s-up.sh` đọc khóa từ `${PTI_SEALING_KEY_DIR:-~/.config/pti/sealed-secrets}`. CI (DOC-41 §10.3) ghi `tls.crt`/`tls.key` từ GitHub Secrets `SEALED_SECRETS_CRT`, `SEALED_SECRETS_KEY` vào thư mục tạm và đặt biến này.
- Mất khóa: sinh khóa mới, `make k8s-seal` niêm phong lại từ `.env` (RB-12).

### 8.2 Sinh và niêm phong

`make k8s-seal` (= `seal-secrets.sh`) đọc `.env` (do `make secrets` sinh, như compose), tạo manifest Secret trong bộ nhớ và niêm phong bằng `kubeseal --cert deploy/k3d/sealed/pub-cert.pem --scope strict`, ghi `deploy/k3d/sealed/<namespace>/<name>.yaml`. File thô không bao giờ ghi ra đĩa.

| Secret | Namespace | Nguồn trong `.env` |
| --- | --- | --- |
| `pti-db-pti-owner`, `pti-db-etl-writer`, `pti-db-triage-writer`, `pti-db-api-reader`, `pti-db-replay-operator`, `pti-db-experiment-runner`, `pti-db-ticketing-owner`, `pti-db-sim-owner`, `pti-db-source-simulator`, `pti-db-debezium` | `pti` | 10 biến mật khẩu của DOC-17 §6; `username` là tên role |
| `pti-s3` | `pti` | `S3_ADMIN_*`, `S3_CONNECT_*`, `S3_ETL_*` (key `admin-access-key`, `connect-access-key`, `etl-access-key`, …) |
| `pti-s3-config` | `pti` | `s3.json` render như `make secrets` |
| `pti-keycloak` | `pti` | `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_EXPERIMENTS_CLIENT_SECRET` |
| `pti-alert-webhook` | `pti` **và** `monitoring` | `ALERTMANAGER_WEBHOOK_TOKEN` (niêm phong hai lần vì `strict` gắn với namespace) |
| `pti-grafana` | `monitoring` | `GRAFANA_ADMIN_PASSWORD` |
| `pti-jev` | `pti` | `TYPESAFE_API_KEY` (bỏ qua nếu trống) |
| `pti-static-jwt` | `pti` | `make k3d-keys` sinh `deploy/k3d/.generated/static-jwt/{private,public}.pem` (gitignored) |

- Chart `pti-infra` cài mọi file trong `sealed/pti/`; `kube-prometheus-stack` nhận `sealed/monitoring/` qua release phụ `monitoring-secrets` (chart `raw` nội bộ).
- Gitleaks bỏ qua `deploy/k3d/sealed/**` (đã mã hóa) nhưng quét `.generated/` nếu lỡ commit (DOC-41).
- Xoay vòng: đổi giá trị trong `.env`, `make k8s-seal`, commit, `helmfile -e <env> apply`; CNPG tự `ALTER ROLE` khi Secret của role đổi (DOC-17 §6); rollout restart app dùng Secret đó (RB-12).

## 9. Scale

### 9.1 `etl-stream` (KEDA, Prometheus trigger)

Giới hạn trên có ích là **4 pod**: `gtfs.*` có 12 partition, mỗi pod có 3 thread cho mỗi listener GTFS-rt (DOC-20 §1). SDD §12.7 nói "tối đa 12 pod" với giả định một thread mỗi pod; thiết kế này dùng 3 thread để ít JVM hơn trên máy 16 GB.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: etl-stream }
spec:
  scaleTargetRef: { name: etl-stream }
  minReplicaCount: 1
  maxReplicaCount: 4
  pollingInterval: 15
  cooldownPeriod: 300
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:   { stabilizationWindowSeconds: 0,   policies: [ { type: Pods, value: 1, periodSeconds: 60 } ] }
        scaleDown: { stabilizationWindowSeconds: 300, policies: [ { type: Pods, value: 1, periodSeconds: 120 } ] }
  triggers:
    - type: prometheus
      metadata:
        serverAddress: http://kube-prometheus-stack-prometheus.monitoring:9090
        threshold: "1500"                 # lag (messages) per pod
        query: |
          (
            sum(kafka_consumergroup_lag{consumergroup="pti-etl-gtfs-rt", topic=~"gtfs\\..*"})
            unless on() (pti:chunk_duration:p95_5m > 2)
          )
          or on()
          (count(up{job="pti-etl-stream"} == 1) * 1500)
```

- Lag lấy từ Kafka Exporter của Strimzi (phía broker) chứ không từ metric client: khi pod chết, metric client mất, nhưng lag vẫn còn (DOC-28 §3.3).
- **Chặn scale khi database là nút thắt (SDD §12.7):** khi `pti:chunk_duration:p95_5m > 2` (cùng điều kiện với alert `DatabaseBottleneck` #7, DOC-28 §6), vế đầu rỗng và query trả `số pod đang chạy × 1500`, nên số pod mong muốn bằng số pod hiện tại: **giữ nguyên**, không tăng. Alert #7 bắn song song. Khi không có dữ liệu (Prometheus mất series), vế sau cũng giữ nguyên.
- `threshold 1500` ≈ 500 message mỗi partition cho 3 partition mỗi pod (SDD §12.7). EXP-07 báo cáo và có thể đề xuất chỉnh.

### 9.2 `triage-worker` (KEDA, PostgreSQL trigger, DR-74)

```yaml
triggers:
  - type: postgresql
    metadata:
      host: pti-warehouse-pooler-ro        # dev: pooler-rw
      port: "5432"
      dbName: pti_warehouse
      userName: api_reader
      sslmode: disable
      targetQueryValue: "100"
      query: >-
        SELECT count(*) FROM ops.dead_letter
        WHERE status = 'NEW' AND triage_attempts < 5
          AND (triage_lease_until IS NULL OR triage_lease_until < now())
    authenticationRef: { name: pti-db-api-reader }   # TriggerAuthentication → Secret pti-db-api-reader key password
minReplicaCount: 1
maxReplicaCount: 3
```

- Dùng `api_reader` (đã có `SELECT` trên `ops.dead_letter`, DOC-17 §4.1): không thêm role mới.
- `maxReplicaCount: 3` giữ tổng lời gọi Jev ≤ 60/giây vì rate limiter là theo pod (DOC-24 §11.3).
- Backlog của insight (ticketing, disruption, dispatch) không tham gia scale: khối lượng nhỏ, một pod đủ.

### 9.3 `api` (HPA)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2
  maxReplicas: {{ .Values.apps.api.hpa.max }}     # 6 staging, 4 lite
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
  behavior:
    scaleDown: { stabilizationWindowSeconds: 300 }
```

SDD §12.7 muốn thêm "p95 latency"; không làm vì cần Prometheus Adapter (thêm một thành phần). EXP-07 đo độ trễ API như biến phụ thuộc thay vì dùng làm tín hiệu scale.

### 9.4 Kết nối database

| User (qua pooler) | App × pod tối đa × pool | Client tối đa | Server connection tối đa |
| --- | --- | --- | --- |
| `etl_writer` | `etl-stream` 4 × 6 + `etl-batch` 2 × 6 | 36 | 36 (≤ `default_pool_size` 40) |
| `triage_writer` | 3 × 4 | 12 | 12 |
| `api_reader` (pooler `ro`) | `api` 6 × 10 | 60 | 40 (client chờ trong pooler) |
| `replay_operator` | `api` 6 × 4 | 24 | 24 |
| Trực tiếp: `pti_owner` (migrate), superuser CNPG, `experiment_runner`, KEDA (`api_reader` qua pooler) | | | ≤ 15 |

Tổng server connection trên primary tối đa khoảng 87 và trên replica khoảng 45, dưới `max_connections=150` (DOC-10 §4). Test KD-13 chạy `SELECT count(*) FROM pg_stat_activity` lúc mọi app ở số pod tối đa.

### 9.5 Đọc khi primary failover

Pooler `ro` chỉ trỏ tới instance đang là replica. Khi primary chết, CNPG promote replica duy nhất; từ lúc đó tới khi instance cũ khởi động lại và nối vào làm replica (thường 30–90 giây), `pti-warehouse-ro` không có endpoint. Để API đọc vẫn phục vụ (DOC-10 §6), URL của datasource reader liệt kê hai host (§5.2):

- `targetServerType=preferSecondary`: pgjdbc thử pooler `ro` trước; không mở được kết nối (PgBouncer không có server) thì dùng pooler `rw`.
- Hikari loại kết nối hỏng và mở kết nối mới theo cùng quy tắc; `maxLifetime` 5 phút đưa kết nối về replica sau khi replica quay lại.
- Trong lúc rơi về primary, `api_reader` chiếm thêm tối đa 40 server connection trên primary (pool của pooler `rw` cho cặp user/database): tổng khoảng 127, vẫn dưới 150.
- Ghi (`replay_operator`) trả 503 trong lúc failover như DOC-10 §6. EXP-08 F4 đo tỷ lệ đọc thành công.

## 10. PDB và NetworkPolicy

### 10.1 PodDisruptionBudget

| Workload | PDB |
| --- | --- |
| `api` | `minAvailable: 1` (khi `replicas ≥ 2`) |
| `etl-stream`, `etl-batch`, `triage-worker` | `maxUnavailable: 1` |
| `frontend` | `minAvailable: 1` khi `replicas ≥ 2`; không có PDB khi 1 |
| Kafka, Postgres | Do Strimzi và CNPG tạo |

### 10.2 NetworkPolicy

Namespace `pti` có `default-deny-ingress`. Egress không bị chặn (app cần DNS, Jev qua Internet ở staging). Các luồng được mở:

| Tới (pod, cổng) | Từ |
| --- | --- |
| `frontend` 80 | Namespace `kube-system` (Traefik) |
| `api` 8080 | `frontend`; namespace `monitoring` pod Alertmanager (`/internal/**`, DOC-27 §5) |
| `source-simulator` 8080 | `api` (Demo control, DR-49); mọi nguồn (NodePort 30084 cho runner) |
| `etl-stream` 9080 | `triage-worker` (health nguồn, DOC-24 §6.6) |
| Mọi app 9080 | Namespace `monitoring` (Prometheus) |
| Pooler `pti-warehouse-pooler-*` 5432 | `etl-stream`, `etl-batch`, `api`, `triage-worker`; namespace `keda`; namespace `monitoring` (Grafana) |
| `pti-warehouse` pods 5432 | Pooler; `db-migrate`; backup; `experiment_runner` qua `kubectl port-forward`; CNPG operator (`cnpg-system`, 8000); các instance cùng cluster |
| `pti-source` pods 5432 | `source-simulator`, Connect, `db-migrate`, backup, CNPG operator |
| Kafka 9092 | `etl-stream`, `etl-batch`, `api`, `source-simulator`, `triage-worker`, Connect; Strimzi operator và entity operator; các broker với nhau (9090, 9091) |
| `seaweedfs` 8333 | Connect, `etl-batch`, `s3-init`, backup |
| `keycloak` 8080 | `api` (JWKS); mọi nguồn (NodePort 30180) |
| `toxiproxy` 18080, 8474 | `triage-worker`; mọi nguồn trên 8474 (runner) |
| `jev-stub` 8080 | `toxiproxy` |
| `mailpit` 1025, 8025 | Namespace `monitoring`; mọi nguồn trên 8025 |

Cổng đã publish qua NodePort cho phép mọi nguồn vì traffic từ host đến với IP của node. NetworkPolicy của k3s do kube-router thực thi. Test KD-12 kiểm một luồng bị chặn (pod `curl` tạm trong `pti` gọi `api:8080/internal/alerts/alertmanager` → timeout) và một luồng được mở.

## 11. Nâng cấp

| Loại thay đổi | Cách làm |
| --- | --- |
| Code app | `make k8s-images` (tag mới) rồi `helmfile -e <env> apply`: hook `db-migrate` chạy trước, rồi rolling update theo §7.2 |
| Migration | Expand/contract (ADR-0024); hook chạy trước rollout |
| Topic | Sửa `deploy/topics.yaml`, `apply`; Topic Operator cập nhật config và tăng partition |
| Connector | Sửa `connect/connectors/*.json`, `apply`; Strimzi cập nhật connector. Đổi `slot.name` hay `topic.prefix` là thay đổi phá vỡ như compose |
| Phiên bản operator hoặc chart | Sửa `versions.env` trong một PR riêng; chạy lại KD-01…KD-05 trên cluster mới |
| Postgres minor | Đổi `imageName`; CNPG rolling update (replica trước, switchover, primary sau) |
| Postgres major | Không hỗ trợ tại chỗ; `pg_dump` → cluster mới → restore (DOC-43) |
| Kafka | Đổi `spec.kafka.version`; Strimzi rolling update từng broker |

## 12. Backup trên k3d

- CronJob `pg-backup` (chart `pti`) lúc 04:00 UTC hằng ngày: image Postgres cùng phiên bản với CNPG, chạy **đúng lệnh `pg_dump`** của DOC-43 §3.1 (host `pti-warehouse-rw`, `pti-source-rw`), rồi upload thư mục `backups/<TS>/` và `manifest.json` lên bucket `backup` của SeaweedFS bằng credential `admin`. Bucket `backup` có lifecycle hết hạn 7 ngày (§6.5), tương đương "giữ 7 bản" của compose.
- Job `pg-backup-verify` chạy sau (CronJob 05:00 UTC): tải bản mới nhất, làm ba bước của DOC-43 §3.3 trên database tạm `pti_warehouse_verify`.
- Alert `BackupJobFailed` (rule riêng k3d, §7.6): `kube_job_status_failed{namespace="pti", job_name=~"pg-backup.*"} > 0`, `for: 5m`, severity `warning`, runbook RB-11. Compose không có alert này.
- CNPG backup bằng barman lên S3 không dùng: `pg_dump` đủ cho mục tiêu (DOC-43) và giữ cùng một quy trình khôi phục trên hai môi trường.

## 13. Tiêm lỗi

### 13.1 Chaos Mesh (`chaos/`)

| File | Resource | Tác động | Dùng ở |
| --- | --- | --- | --- |
| `pod-kill-etl-stream.yaml` | `PodChaos` `pod-kill` | Kill ngẫu nhiên 1 pod `etl-stream` (`gracePeriod: 0`) | EXP-08 F1 |
| `pod-failure-api.yaml` | `PodChaos` `pod-failure`, 60 s | 1 pod `api` không phục vụ | EXP-08 F2 |
| `kafka-broker-kill.yaml` | `PodChaos` `pod-kill` | Kill 1 broker (label `strimzi.io/pool-name: dual`) | EXP-08 F3, demo bước 7 |
| `pg-network-delay.yaml` | `NetworkChaos` `delay` 200 ms ± 50 ms, 120 s | Giữa `etl-stream` và pod warehouse | EXP-08 F5 |
| `pg-network-partition.yaml` | `NetworkChaos` `partition`, 60 s | `etl-stream` không tới được pooler | EXP-08 F5 |
| `connect-kill.yaml` | `PodChaos` `pod-kill` | Kill worker Connect | EXP-08 F6 |

Failover Postgres **không** dùng Chaos Mesh mà dùng lệnh của CNPG, để biết chính xác thời điểm: `kubectl -n pti delete pod <primary> --grace-period=0` (failover không kế hoạch) hoặc `kubectl cnpg promote pti-warehouse <replica>` (switchover). Plugin `kubectl-cnpg` có trong `mise.toml`.

Mọi manifest có `mode: one` và `duration` rõ ràng; runner xóa CR khi xong lần chạy. `selector.namespaces: [pti]` bắt buộc để không đụng hệ thống.

### 13.2 Jev

`lite` gọi `jev-stub` qua Toxiproxy (§6.7). EXP-08 F7 thêm toxic `timeout` (hoặc `latency` 3 s) vào proxy `jev` qua API `http://localhost:18474` trong 5 phút, giống TG-35 trên compose.

## 14. Lệnh

| Lệnh | Việc |
| --- | --- |
| `make k8s-sealing-key` | §8.1 (một lần mỗi máy) |
| `make k3d-keys` | Sinh cặp khóa `static-jwt` (DOC-27 §3.3); sau đó `make k8s-seal` |
| `make k8s-seal` | §8.2 |
| `make k8s-up [ENV=lite]` | Kiểm compose đã dừng; `k3d cluster create`; tạo Secret khóa niêm phong; `make k8s-images`; `helmfile -e $ENV apply`; `make k8s-smoke`. Mặc định `ENV=lite` |
| `make k8s-down` | `k3d cluster delete pti` (xóa mọi PVC). Registry `k3d-pti-registry` được giữ để lần sau khỏi push lại |
| `make k8s-stop` | `k3d cluster stop pti`: dừng mọi node, giữ nguyên PVC và image đã nạp. Giải phóng RAM để chạy compose |
| `make k8s-start` | `k3d cluster start pti`, rồi chờ `kubectl wait --for=condition=Ready` các CR `Kafka`, `Cluster` và mọi pod ở `pti` (timeout 5 phút). Không cần Internet (DOC-46 §6) |
| `make k8s-images` | §3.2 |
| `make k8s-apply [ENV=…]` | `helmfile -e $ENV apply` (sau khi đổi values hoặc image) |
| `make k8s-status` | `kubectl get kafka,kafkanodepool,kafkaconnect,kafkaconnector,cluster,pooler,scaledobject,hpa -n pti` và `kubectl get pods -A` |
| `make k8s-smoke` | §15 |
| `make k8s-psql-wh [Q=…]` | `kubectl cnpg psql pti-warehouse -n pti` với `pti_owner` (qua Secret) |
| `make k8s-logs S=<app>` | `kubectl -n pti logs -l app.kubernetes.io/name=<app> -f --max-log-requests 10` |
| `make k8s-clock-offset AT=<HH:MM>` | Như `make clock-offset` của compose: tính offset, `helmfile -e $ENV apply --skip-deps --selector name=pti --set global.clockOffset=…` (chỉ chart `pti` cục bộ, không kéo chart từ repo nên chạy được khi offline), rollout `source-simulator`, `etl-stream`, `etl-batch`, `api` |
| `make k3d-token ROLE=<role> [TTL=1h]` | Token `static-jwt` (lite, DOC-27 §3.3) |
| `make k8s-load STEPS='1,2,5,10' [STEP=PT5M]` | `POST /sim/scenarios/load-ramp` tới `localhost:8084` (demo bước 7, EXP-07) |

## 15. Smoke test k3d (`make k8s-smoke`)

| # | Kiểm tra | Timeout |
| --- | --- | --- |
| 1 | `kafka/pti`, `cluster/pti-warehouse`, `cluster/pti-source`, `kafkaconnect/pti-connect` `Ready`; hai `KafkaConnector` `Ready` và task `RUNNING` | 600 s |
| 2 | Mọi Deployment trong `pti` có `availableReplicas = replicas` | 300 s |
| 3 | `dw.gtfs_feed_version` có đúng một dòng `ACTIVE` | 180 s |
| 4 | `GET localhost:8084/sim/status` → `activeVehicles > 0` (tự đặt giờ nghiệp vụ như compose) | 60 s |
| 5 | `dw.fact_vehicle_position` có dòng mới trong 60 s; `dw.fact_ticket_sales` có dòng mới | 180 s |
| 6 | `GET localhost:8080/` → 200 HTML có `<div id="root">`; `GET localhost:8080/api/v1/vehicles/live` → 200 danh sách không rỗng (anonymous) | 30 s |
| 7 | Prometheus: `up{namespace="pti"} == 1` cho mọi target app; `PrometheusRule` `pti-rules` đã nạp (`/api/v1/rules`) | 60 s |
| 8 | `kubectl get scaledobject -n pti` → `READY=True` cho `etl-stream`, `triage-worker` | 60 s |

## 16. Kết quả đo tài nguyên

Điền ở P7-04: RAM và CPU thực của từng pod (`kubectl top`) ở `lite` dưới tải ×1 và ×10, thời gian `make k8s-up` từ cluster trống (image đã có trong registry).

## 17. Test bắt buộc

| ID | Kiểm tra | Cách |
| --- | --- | --- |
| KD-01 | `helm lint` và `helm template` cả hai chart với ba values; `kubeconform` (schema K8s và CRD của Strimzi, CNPG, KEDA, Chaos Mesh) | CI job `k8s-render` (DOC-41 §10.2) |
| KD-02 | `helm template` render đủ số `KafkaTopic` = số topic trong `topics.yaml`, đủ `KafkaConnector` = số file connector, `PrometheusRule` chứa mọi rule của compose | CI `k8s-render` (`check-render.py`) |
| KD-03 | `make k8s-up ENV=lite` từ cluster trống → smoke pass | Thủ công P7-04; `full-stack.yml` → `k3d-lite` (DOC-41 §10.3) |
| KD-04 | `helmfile apply` lần hai không đổi gì (`helmfile diff` rỗng) | `full-stack.yml` → `k3d-lite` |
| KD-05 | Xóa cluster, tạo lại, `apply`: SealedSecret giải mã được (khóa BYO) | Thủ công P7-02 |
| KD-06 | `GET /connectors/<name>/config` qua `kubectl exec` không chứa mật khẩu rõ | Thủ công |
| KD-07 | `connectionLimit` của `managed.roles` bằng `CONNECTION LIMIT` trong `10-bootstrap.sh` | CI `k8s-render` (`check-roles.py`) |
| KD-08 | Kill pod `etl-stream` khi có tải: pod mới Ready ≤ 60 s; không mất, không trùng (so ledger) | Trong EXP-08 F1 |
| KD-09 | Postgres failover (xóa pod primary): app không restart (liveness), readiness `DOWN` rồi `UP`; ghi tiếp sau failover | Trong EXP-08 F4 |
| KD-10 | Rolling update `etl-stream` dưới tải ×2 (P7-07): không mất, không trùng | Trong EXP-08 R1 |
| KD-11 | Migration expand + rolling update: pod cũ không lỗi SQL trong lúc rollout | Thủ công P7-05 |
| KD-12 | NetworkPolicy: luồng bị chặn và luồng được mở (§10.2) | Thủ công |
| KD-13 | Số kết nối ở số pod tối đa ≤ bảng §9.4 | Thủ công, trong EXP-07 |
| KD-14 | DB chậm (`pg-network-delay`) trong lúc lag tăng: `etl-stream` không tăng pod; alert `DatabaseBottleneck` bắn | Trong EXP-07 biến thể `db-slow` |
| KD-15 | `pg-backup` và `pg-backup-verify` chạy thành công; làm hỏng `manifest.json` → Job lỗi, `BackupJobFailed` bắn | Thủ công P7 |

## 18. Câu hỏi còn mở

Không có. Tên field của CR (Strimzi, CNPG, KEDA, Chaos Mesh) và phiên bản chart được xác minh khi pin ở P7-02/P7-03; sai lệch cú pháp sửa trực tiếp vào tài liệu này và DOC-11 §2.1, không đổi thiết kế.
