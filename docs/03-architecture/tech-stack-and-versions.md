# Công nghệ và phiên bản

> Trạng thái: **Review** · Cập nhật: 2026-09-26 · DOC-11
> Phụ thuộc: [DR-53](../00-decision-register.md), DR-36, DR-46, DR-50, DR-52, DR-54, DR-56, [ADR-0029](../04-adr/0029-spring-boot-4-java-25.md)

## 0. Chính sách phiên bản

1. **Nguồn sự thật duy nhất** cho phiên bản:
   - Java: `gradle/libs.versions.toml` (version catalog).
   - Frontend: `frontend/package.json` + `pnpm-lock.yaml`.
   - Python: `experiments/pyproject.toml` + `uv.lock`.
   - Công cụ dev: `mise.toml`.
   - Image hạ tầng: `deploy/versions.env` (compose đọc qua `env_file`, helmfile đọc cùng file).
2. **Thư viện nằm trong BOM của Spring Boot thì không pin riêng.** Chỉ pin thư viện nằm ngoài BOM. Muốn ghi đè phiên bản của BOM thì phải có ADR.
3. Image hạ tầng pin theo **tag cụ thể kèm digest** (`postgres:17.11@sha256:…`), không dùng `latest`.
4. Dependabot (có sẵn trên GitHub) mở PR mỗi tuần, gom nhóm theo hệ sinh thái. Mỗi phase rà bản patch một lần (DR-53).
5. Cột **Trạng thái** ở các bảng dưới:
   - ✅: đã xác minh khi viết tài liệu.
   - 🔬: dòng phiên bản đã chốt, **số patch cụ thể và tính tương thích được điền ở S-06 (P0-07)**, trước khi mở P1.

## 1. Nền tảng Java

| Thành phần | Phiên bản | Ghi chú | Trạng thái |
| --- | --- | --- | --- |
| JDK | **Temurin 25 LTS** (25.0.4 lúc viết; image `eclipse-temurin:25-jre`) | Toolchain Gradle `languageVersion = 25`. Bật `-XX:+UseCompactObjectHeaders` (JEP 519) | ✅ (S-03: app Spring Boot 4.1.1 chạy được với cờ này) |
| Gradle | **9.7.1** (wrapper mà start.spring.io sinh cho Boot 4.1.1) | Kotlin DSL, configuration cache bật | ✅ (S-03: build chạy trên JDK 25) |
| Spring Boot | **4.1.x** (4.1.1 lúc viết) | BOM quản lý các dòng dưới | ✅ (DR-53) |
| Spring Framework | 7.x (theo BOM) | Có sẵn `@Retryable`, `@ConcurrencyLimit` (`@EnableResilientMethods`), core retry | ✅ |
| Spring Batch | 6.x (theo BOM) | JobRepository JDBC, `JobOperator` (DR-62) | 🔬 |
| Spring Kafka | 4.x (theo BOM), Kafka client 4.x | Batch listener, `DefaultErrorHandler`, `ContainerPausingBackOffHandler` | 🔬 |
| Spring Security | 7.x (theo BOM) | Resource server JWT | ✅ |
| Jackson | **3.x** (theo BOM) | Package mới `tools.jackson.*` (§6) | ✅ |
| Hibernate Validator | theo BOM | Jakarta Validation 3.1 (Jakarta EE 11) | ✅ |
| Micrometer, Micrometer Tracing (bridge OTel), OTLP exporter | theo BOM | Không dùng OTel Java agent (DR-50) | ✅ |
| Flyway (core + `flyway-database-postgresql`) | theo BOM | Chạy trong `db-migrate` | ✅ |
| PostgreSQL JDBC, HikariCP, Caffeine | theo BOM | `prepareThreshold=0` khi đi qua PgBouncer (DR-24) | ✅ |
| Testcontainers (postgresql, kafka, toxiproxy; SeaweedFS dùng `GenericContainer` vì không có module riêng) | theo BOM | Package có thay đổi ở bản 2.x (§6) | 🔬 |
| JUnit Jupiter, AssertJ, Mockito, Awaitility | theo BOM | | ✅ |

### 1.1 Thư viện ngoài BOM (pin trong version catalog)

| Thư viện | Dòng phiên bản | Dùng ở | License | Trạng thái |
| --- | --- | --- | --- | --- |
| `org.springaicommunity:typesafe-java-sdk` | 0.2.x | triage-worker (DR-36) | Apache-2.0 | ✅ (0.2.0) |
| ShedLock (`shedlock-spring`, `shedlock-provider-jdbc-template`) | bản mới nhất hỗ trợ Spring 7 | etl-batch, triage-worker | Apache-2.0 | 🔬 |
| Spring Cloud AWS (`spring-cloud-aws-starter-s3`) | bản hỗ trợ Boot 4 | etl-batch (đọc raw zone) | Apache-2.0 | 🔬; nếu chưa có thì dùng AWS SDK v2 S3 client trực tiếp |
| Resilience4j (`circuitbreaker`, `bulkhead`, `ratelimiter`, `micrometer`) | 2.x, **module core** | triage-worker, etl (circuit breaker DB) | Apache-2.0 | 🔬; nếu starter chưa hỗ trợ Boot 4 thì cấu hình bean thủ công |
| springdoc-openapi (`starter-webmvc-api`) | bản hỗ trợ Boot 4 | api | Apache-2.0 | 🔬 |
| Bucket4j (`bucket4j_jdk17-core`) | 8.x | api (DR-45) | Apache-2.0 | 🔬 |
| `com.networknt:json-schema-validator` | bản hỗ trợ draft 2020-12 | common (contract, DQ-01 lúc chạy), api (validate payload DLQ) | Apache-2.0 | 🔬 |
| `io.github.erdtman:java-json-canonicalization` | 1.x | common (payload hash, RFC 8785) | Apache-2.0 | 🔬 |
| `com.github.f4b6a3:uuid-creator` | 6.x | common (UUIDv7, UUIDv5) | MIT | 🔬 |
| `com.github.f4b6a3:ulid-creator` | 5.x | publisher sự kiện UI | MIT | 🔬 |
| `net.ttddyy.observation:datasource-micrometer-spring-boot` | bản hỗ trợ Boot 4 | span JDBC loại `QUERY` cho etl, api (DOC-28 §5.3); chưa có bản hỗ trợ thì bỏ span JDBC | Apache-2.0 | 🔬 |
| ArchUnit | 1.x | test ranh giới module (DOC-44 §3.3) | Apache-2.0 | 🔬 |
| MockWebServer (`com.squareup.okhttp3:mockwebserver3`) | 5.x | test: giả Jev và Alertmanager (DOC-44 §3.2) | Apache-2.0 | 🔬 |

### 1.2 Gradle plugin

| Plugin | Mục đích | Trạng thái |
| --- | --- | --- |
| `org.springframework.boot`, `io.spring.dependency-management` hoặc `platform(bom)` | BOM | ✅ |
| `com.diffplug.spotless` (palantir-java-format) | Format | 🔬 |
| `checkstyle`, `com.github.spotbugs` | Lint, bug pattern | 🔬 |
| `jacoco` | Coverage gate (NFR-13) | ✅ |
| `com.google.cloud.tools.jib` | Build image (DR-56) | 🔬 (Java 25 base image) |
| `org.owasp.dependencycheck` | Quét CVE (CI main) | 🔬 |
| `org.openapi.generator` *không dùng*; OpenAPI xuất bằng `springdoc-openapi-gradle-plugin` | `openapi.json` (DR-44) | 🔬 |

## 2. Hạ tầng

| Thành phần | Image / phiên bản | Ghi chú | License | Trạng thái |
| --- | --- | --- | --- | --- |
| Kafka | `apache/kafka:4.3.1` | KRaft, một broker trên compose. 4.4.0 đang ở RC lúc viết | Apache-2.0 | ✅ (S-03) |
| Kafka Connect + Debezium | build từ `quay.io/debezium/connect:3.6.3.Final` + S3 sink connector | Dockerfile `connect/Dockerfile`. Image gốc chạy được (S-03); plugin S3 sink xác minh ở S-04 | Apache-2.0 | 🔬 (S-04) |
| S3 sink connector | **Aiven `s3-connector-for-apache-kafka`** (ưu tiên) | Chọn vì license Apache-2.0; phương án dự phòng là Confluent S3 sink (Confluent Community License, được phép dùng trong dự án này). Chốt ở ADR-0012 / S-04 | Apache-2.0 | 🔬 |
| PostgreSQL | `postgres:17.11` | Dùng 18 nếu S-06 xác nhận Debezium và CNPG hỗ trợ ổn định (DR-53) | PostgreSQL | 🔬 |
| Object storage | **SeaweedFS** `chrislusf/seaweedfs:4.47` (`server -s3`); job `s3-init` dùng `amazon/aws-cli` | Thay MinIO vì repository `minio/minio` không còn trên Docker Hub (S-03, DR-66). Dự phòng: RustFS 1.0 (Apache-2.0), đổi không phải sửa code | Apache-2.0 | ✅ (S-03) |
| Keycloak | `quay.io/keycloak/keycloak:26.7.4` | `start-dev --import-realm` | Apache-2.0 | ✅ (S-03) |
| Prometheus / Alertmanager | 3.x / 0.2x | | Apache-2.0 | 🔬 |
| Grafana | 12.x | Provision datasource và dashboard | AGPL-3.0 (chỉ chạy nội bộ) | 🔬 |
| Tempo / Loki / Alloy | 2.x / 3.x / 1.x | | AGPL-3.0 | 🔬 |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib` | Nhận OTLP, xuất sang Tempo | Apache-2.0 | 🔬 |
| Mailpit | `axllent/mailpit:1.x` | | MIT | 🔬 |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.x` | Profile `experiment` | MIT | 🔬 |
| nginx | `nginx:1.2x-alpine` | Phục vụ SPA và PMTiles | BSD-2 | 🔬 |

### 2.1 Kubernetes (P7)

| Thành phần | Phiên bản | Trạng thái |
| --- | --- | --- |
| k3d / k3s | 5.x / 1.3x | 🔬 |
| Helm, helmfile | Helm 3.x hoặc 4.x (theo helmfile hỗ trợ), helmfile 1.x | 🔬 |
| Strimzi | bản hỗ trợ Kafka 4 (chỉ KRaft) | 🔬 |
| CloudNativePG (+ PgBouncer Pooler) | 1.2x | 🔬 |
| KEDA | 2.x | 🔬 |
| Chaos Mesh | 2.x | 🔬 |
| Sealed Secrets | bản mới nhất | 🔬 |
| kube-prometheus-stack | bản mới nhất | 🔬 |
| kubeseal, plugin `kubectl-cnpg` | cùng phiên bản controller Sealed Secrets / CNPG | 🔬 |
| Toxiproxy (`lite`) | 2.x | 🔬 |
| WireMock (`jev-stub`, `lite`) | 3.x | 🔬 |
| Image `quay.io/strimzi/kafka` cho Connect | cùng bản Strimzi và Kafka ở trên | 🔬 |

Mọi phiên bản trong bảng này được pin ở P7-02 vào `deploy/versions.env` (`CHART_*`, tag image) và ghi lại ở đây; tên field của CR được xác minh cùng lúc (DOC-40 §18, ADR-0028).

## 3. Frontend

| Thư viện | Dòng phiên bản | Vai trò | Trạng thái |
| --- | --- | --- | --- |
| Node.js | **24 LTS** | Runtime build | ✅ (DR-53) |
| pnpm | 10.x | Package manager | 🔬 |
| TypeScript | 5.x, `strict: true` | | 🔬 |
| Vite | bản ổn định mới nhất | Build, dev server | 🔬 |
| React / React DOM | **19.x** | | ✅ |
| TanStack Router / Query / Table / Virtual | 1.x / 5.x / 8.x / 3.x | Router có search params định kiểu; data fetching; bảng DLQ | 🔬 |
| Tailwind CSS | 4.x | | 🔬 |
| shadcn/ui (Radix primitives) | CLI mới nhất | Component có a11y | 🔬 |
| Apache ECharts (+ `echarts-for-react`) | bản mới nhất | Biểu đồ | 🔬 |
| MapLibre GL JS / `react-map-gl` / `pmtiles` | 5.x / 8.x / 4.x | Bản đồ offline (DR-47) | 🔬 |
| react-hook-form + zod | 7.x + 4.x | Form | 🔬 |
| CodeMirror 6 (`@uiw/react-codemirror`, `@codemirror/lang-json`) | 6.x | Sửa payload DLQ | 🔬 |
| `react-oidc-context` + `oidc-client-ts` | 3.x | OIDC PKCE | 🔬 |
| Zustand | 5.x | State UI | 🔬 |
| `@microsoft/fetch-event-source` | 2.x | SSE có header Authorization (DR-41) | 🔬 |
| `openapi-typescript` + `openapi-fetch` | 7.x + 0.x | Type sinh từ `openapi.json` (DR-44) | 🔬 |
| Vitest, React Testing Library, MSW | bản mới nhất, 16.x, 2.x | Test | 🔬 |
| Playwright + `@axe-core/playwright` | 1.x + 4.x | E2E, a11y (NFR-11) | 🔬 |
| `@lhci/cli` (Lighthouse CI) | 0.x | Đo LCP trong nightly (NFR-12, DOC-44 §10) | 🔬 |
| `json-schema-to-zod` | 2.x | Sinh zod schema cho sự kiện UI từ `schemas/ui-events/` lúc build (DOC-44 §9.1) | 🔬 |
| `@vitest/coverage-v8` | theo Vitest | Coverage frontend (DOC-44 §7) | 🔬 |
| `lucide-react` | bản mới nhất | Icon (DOC-35 §4) | 🔬 |
| `sonner` | 2.x | Toast (DOC-35 §5) | 🔬 |
| `@fontsource-variable/inter`, `@fontsource-variable/jetbrains-mono` | 5.x | Font tự host, không gọi Google Fonts (DOC-35 §4) | 🔬 |
| `eslint-plugin-i18next` | 6.x | Luật `no-literal-string` (DR-48, DOC-37 CP-02) | 🔬 |
| `protomaps-themes-base` (dev) | 4.x | Sinh `style-light.json`/`style-dark.json` cho bản đồ nền (ADR-0021) | 🔬 |
| ESLint (flat config) + Prettier | 9.x + 3.x | Lint, format | 🔬 |

## 4. Thực nghiệm (Python)

| Thành phần | Phiên bản | Trạng thái |
| --- | --- | --- |
| Python | 3.12 (DR-52) | ✅ |
| uv | bản mới nhất | 🔬 |
| httpx, psycopg[binary] 3, docker (SDK), pandas, matplotlib, typer, pydantic; pytest, ruff (test, lint) | bản mới nhất lúc P3-06 | 🔬 |

## 5. Công cụ dev (`mise.toml`)

`java = "temurin-25"`, `node = "24"`, `pnpm = "10"`, `python = "3.12"`, `uv`, `kubectl`, `k3d`, `helm`, `helmfile`, `kubeseal`, `kubectl-cnpg`, `jq`, `kcat`, `gh`, `pmtiles` (go-pmtiles CLI, dùng cho `make tiles`, ADR-0021). Docker Desktop hoặc OrbStack cài riêng (DOC-38).

## 6. Thay đổi so với Spring Boot 3 cần biết

Người triển khai **không làm theo tài liệu hay ví dụ của Boot 3** ở các điểm sau. Mục nào có 🔬 thì S-06 xác nhận tên chính xác và sửa bảng này.

| Chủ đề | Boot 3 | Boot 4.1 (dự án dùng) | Trạng thái |
| --- | --- | --- | --- |
| Jackson | `com.fasterxml.jackson.databind.ObjectMapper` | Jackson 3: group `tools.jackson.core`, package `tools.jackson.databind`; ưu tiên `JsonMapper.builder()` (bất biến). Annotation vẫn ở `com.fasterxml.jackson.annotation` | ✅ |
| Cấu hình Jackson | `Jackson2ObjectMapperBuilderCustomizer` | Customizer cho `JsonMapper.Builder` của Boot 4 | 🔬 |
| Mock trong test | `@MockBean`, `@SpyBean` | `@MockitoBean`, `@MockitoSpyBean` (bản cũ đã bị xóa) | ✅ |
| Retry | Spring Retry (`spring-retry`) | Core retry trong Spring Framework 7 (`RetryTemplate` mới, `@Retryable` + `@EnableResilientMethods`). Spring Batch 6 dựa trên core retry (DR-53 mục 0a) | 🔬 |
| Chạy job | `JobLauncher.run` | `JobOperator` (DR-62) | 🔬 |
| JobRepository | JDBC mặc định khi có DataSource | Cần bật JDBC tường minh (starter hoặc annotation riêng) để không rơi vào bản resourceless | 🔬 |
| Starter | `spring-boot-starter-web` | Autoconfigure tách module theo công nghệ; tên starter cho Spring MVC, Batch JDBC và test starter theo module cần kiểm tra | 🔬 |
| Null-safety | Annotation của Spring | JSpecify (`@Nullable`, `@NullMarked`) | ✅ |
| Log có cấu trúc | Logback encoder ngoài | `logging.structured.format.console=ecs` (có sẵn từ 3.4) | ✅ |
| Testcontainers | 1.x, `org.testcontainers.containers.*` | 2.x: package và tên artifact theo module thay đổi | 🔬 |

## 7. Những gì không dùng (và vì sao)

| Không dùng | Lý do | Nguồn |
| --- | --- | --- |
| Engine chunk tự xây | Spring Batch đã có và đã được kiểm chứng | ADR-0002 |
| Spring Cloud Stream, Kafka Streams | Thêm tầng trừu tượng, không giải quyết thêm vấn đề gì khi đích ghi là Postgres | ADR-0002 |
| Spring Cloud Data Flow, remote chunking, partitioning | Quá mức cần thiết | ADR-0002 |
| Spring AI | Không cần ChatClient; SDK Jev là đủ | DR-36 |
| Spring Cloud Contract | Nặng, thiên về JVM↔JVM | DR-44 |
| pg_partman | Job bảo trì tự viết đơn giản hơn và test được | DR-15 |
| Protobuf cho GTFS-rt | JSON dễ debug và dễ tiêm dữ liệu lỗi | DR-03 |
| OTel Java agent | Trùng span với Micrometer Tracing | DR-50 |
| Outbox cho sự kiện UI | Sự kiện UI là best-effort; nguồn sự thật nằm ở DB | DR-42 |
