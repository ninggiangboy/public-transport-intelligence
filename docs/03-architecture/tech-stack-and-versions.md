# Công nghệ và phiên bản

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-11
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
   - 🔬: dòng phiên bản đã chốt, số patch và tính tương thích xác minh sau (spike hoặc phase ghi trong ô).
   - Thư viện Java đã được S-06 (2026-09-28) xác minh bằng app mẫu `spikes/s06-boot41-java25/` (20 test trên Postgres 17.11 và 18.1, Kafka 4.2.1, SeaweedFS 4.47). Số phiên bản ghi trong bảng là bản đã chạy thử; P1-02 chép vào `gradle/libs.versions.toml`.

## 1. Nền tảng Java

| Thành phần | Phiên bản | Ghi chú | Trạng thái |
| --- | --- | --- | --- |
| JDK | **Temurin 25 LTS** (25.0.4 lúc viết; image `eclipse-temurin:25-jre`) | Toolchain Gradle `languageVersion = 25`. Bật `-XX:+UseCompactObjectHeaders` (JEP 519) | ✅ (S-03: app Spring Boot 4.1.1 chạy được với cờ này) |
| Gradle | **9.8.0** | Kotlin DSL, configuration cache bật | ✅ (S-06) |
| Spring Boot | **4.1.x** (4.1.1 lúc viết) | BOM quản lý các dòng dưới | ✅ (DR-53) |
| Spring Framework | 7.0.9 (theo BOM của Boot 4.1.1) | Có sẵn `@Retryable`, `@ConcurrencyLimit` (`@EnableResilientMethods`), core retry | ✅ (S-06) |
| Spring Batch | 6.0.5 (theo BOM) | JobRepository JDBC qua `spring-boot-starter-batch-jdbc`, `JobOperator` (DR-62). **Chunk step fault-tolerant dùng builder cũ `chunk(size, tx).faultTolerant()`**, không dùng `ChunkOrientedStep` mới (DR-80, DOC-19 §5). Builder cũ dựa trên Spring Retry 2.0.x (`org.springframework.retry`, kéo theo từ spring-batch-core) | ✅ (S-06) |
| Spring Kafka | 4.1.1 (theo BOM), Kafka client 4.2.1 | Batch listener, `DefaultErrorHandler(null, backOff, new ContainerPausingBackOffHandler(new ListenerContainerPauseService(registry, scheduler)))` | ✅ (S-06: listener lỗi tạm thời, container pause rồi nhận lại) |
| Spring Security | 7.x (theo BOM) | Resource server JWT | ✅ |
| Jackson | **3.1.5** (theo BOM) | Package mới `tools.jackson.*` (§6) | ✅ (S-06) |
| Hibernate Validator | theo BOM | Jakarta Validation 3.1 (Jakarta EE 11) | ✅ |
| Micrometer 1.17.1, Micrometer Tracing 1.7.1 (bridge OTel), OpenTelemetry 1.62.0 | theo BOM, qua `spring-boot-starter-opentelemetry`; `/actuator/prometheus` cần thêm `io.micrometer:micrometer-registry-prometheus` | Không dùng OTel Java agent (DR-50) | ✅ (S-06: `OtelTracer`) |
| Flyway 12.4.0 (`spring-boot-starter-flyway` + `flyway-database-postgresql`) | theo BOM | Chạy trong `db-migrate` | ✅ (S-06) |
| PostgreSQL JDBC 42.7.13, HikariCP, Caffeine | theo BOM | `prepareThreshold=0` khi đi qua PgBouncer (DR-24) | ✅ |
| Testcontainers 2.0.5 (`testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-toxiproxy`, `testcontainers-junit-jupiter`; SeaweedFS dùng `GenericContainer` vì không có module riêng) | theo BOM | Package mới (§6). Với OrbStack, đặt `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` và `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (DOC-38 §2) | ✅ (S-06) |
| JUnit Jupiter, AssertJ, Mockito, Awaitility | theo BOM | | ✅ |

### 1.1 Thư viện ngoài BOM (pin trong version catalog)

| Thư viện | Dòng phiên bản | Dùng ở | License | Trạng thái |
| --- | --- | --- | --- | --- |
| `org.springaicommunity:typesafe-java-sdk` | 0.2.x | triage-worker (DR-36) | Apache-2.0 | ✅ (0.2.0) |
| ShedLock (`shedlock-spring`, `shedlock-provider-jdbc-template`) | 7.10.1 | etl-batch, triage-worker | Apache-2.0 | ✅ (S-06, `usingDbTime()`) |
| Spring Cloud AWS (`spring-cloud-aws-starter-s3`, BOM `spring-cloud-aws-dependencies`) | 4.1.1 | etl-batch (đọc raw zone) | Apache-2.0 | ✅ (S-06: `S3Template` với SeaweedFS, path-style; cần `spring.cloud.aws.region.static`) |
| Resilience4j (`resilience4j-spring-boot4`, `resilience4j-micrometer`) | 2.4.0 | triage-worker, etl (circuit breaker DB) | Apache-2.0 | ✅ (S-06: đã có starter cho Boot 4; metric `resilience4j_circuitbreaker_state`) |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-api`) | 3.1.1 | api | Apache-2.0 | ✅ (S-06: sinh OpenAPI 3.1.0) |
| Bucket4j (`bucket4j_jdk17-core`) | 8.20.0 | api (DR-45) | Apache-2.0 | ✅ (S-06) |
| `com.networknt:json-schema-validator` | 3.0.7 (dùng Jackson 3; API mới `SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(…)`, `Schema.validate(JsonNode)` trả `List<Error>`) | common (contract, DQ-01 lúc chạy), api (validate payload DLQ) | Apache-2.0 | ✅ (S-06) |
| `io.github.erdtman:java-json-canonicalization` | 1.1 | common (payload hash, RFC 8785) | Apache-2.0 | ✅ (S-06) |
| `com.github.f4b6a3:uuid-creator` | 6.1.1 | common (UUIDv7, UUIDv5) | MIT | ✅ (S-06) |
| `com.github.f4b6a3:ulid-creator` | 5.2.4 | publisher sự kiện UI | MIT | ✅ (S-06) |
| `net.ttddyy.observation:datasource-micrometer-spring-boot` | 2.3.0 | span JDBC loại `QUERY` cho etl, api (DOC-28 §5.3) | Apache-2.0 | ✅ (S-06: DataSource được bọc proxy) |
| ArchUnit (`archunit-junit5`) | 1.5.1 | test ranh giới module (DOC-44 §3.3) | Apache-2.0 | ✅ (S-06: đọc được bytecode Java 25) |
| MockWebServer (`com.squareup.okhttp3:mockwebserver3`) | 5.5.0 (package `mockwebserver3`, `MockResponse.Builder`) | test: giả Jev và Alertmanager (DOC-44 §3.2) | Apache-2.0 | ✅ (S-06) |

### 1.2 Gradle plugin

| Plugin | Mục đích | Trạng thái |
| --- | --- | --- |
| `org.springframework.boot`, `io.spring.dependency-management` hoặc `platform(bom)` | BOM | ✅ |
| `com.diffplug.spotless` 8.10.3 (palantir-java-format) | Format | ✅ (S-06) |
| `checkstyle` (tool 14.3.0), `com.github.spotbugs` 6.5.12 (SpotBugs 4.10.4) | Lint, bug pattern. SpotBugs báo `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` ở mọi bean nhận dependency qua constructor, nên `config/spotbugs/exclude.xml` loại hai pattern này | ✅ (S-06: chạy trên bytecode Java 25) |
| `jacoco` (tool 0.8.15) | Coverage gate (NFR-13) | ✅ (S-06: đọc được bytecode Java 25) |
| `com.google.cloud.tools.jib` 3.5.4 | Build image (DR-56), base `eclipse-temurin:25-jre`. `jibDockerBuild` chỉ nạp một platform vào Docker cục bộ: mặc định theo máy (`linux/arm64` trên máy dev); CI đẩy cả `linux/amd64,linux/arm64` bằng `jib` (DOC-41 §5). Image amd64 chạy giả lập trên Mac khởi động chậm gấp 4 lần (10,6 s so với 2,7 s) | ✅ (S-06) |
| `org.owasp.dependencycheck` 13.0.0 | Quét CVE (CI main) | 🔬 (áp dụng được; lần chạy đầy đủ cần `NVD_API_KEY`, kiểm ở P1-03) |
| `org.openapi.generator` *không dùng*; OpenAPI xuất bằng `org.springdoc.openapi-gradle-plugin` 1.9.0 | `openapi.json` (DR-44) | 🔬 (kiểm ở P4 khi có module `api`) |

## 2. Hạ tầng

| Thành phần | Image / phiên bản | Ghi chú | License | Trạng thái |
| --- | --- | --- | --- | --- |
| Kafka | `apache/kafka:4.3.1` | KRaft, một broker trên compose. 4.4.0 đang ở RC lúc viết | Apache-2.0 | ✅ (S-03) |
| Kafka Connect + Debezium | build từ `quay.io/debezium/connect:3.6.3.Final` (runtime Kafka Connect 4.3.0) + S3 sink connector | Dockerfile `connect/Dockerfile` (DOC-39 §3.4). Debezium PostgreSQL connector chạy được trên PostgreSQL 17.11 và 18.6 (S-04) | Apache-2.0 | ✅ (S-04) |
| S3 sink connector | **Aiven `s3-sink-connector-for-apache-kafka` 3.4.3** | Tải từ `https://github.com/Aiven-Open/cloud-storage-connectors-for-apache-kafka/releases/download/v3.4.3/s3-sink-connector-for-apache-kafka-3.4.3.tar`, SHA-256 `85661c4d3d49b85f4a65170a5c27464e4359760aa7140a24628e45323b6d7329`. Cấu hình ở DOC-09 §7; `file.max.records=2000` là bắt buộc để không OOM (DR-81). Không cần phương án dự phòng Confluent (ADR-0012) | Apache-2.0 | ✅ (S-04) |
| PostgreSQL | `postgres:17.11` | 18.6 đã chạy được với Debezium (S-04) và Spring Batch (S-06). Còn chờ CNPG ở P7-01 rồi mới đổi cả compose lẫn k3d sang 18 trong cùng một thay đổi (DR-53). Lưu ý khi đổi: image 18 đặt `PGDATA=/var/lib/postgresql/18/docker` và khai báo volume ở `/var/lib/postgresql` (image 17 là `/var/lib/postgresql/data`), nên phải sửa mount của volume | PostgreSQL | ✅ 17 / 🔬 18 |
| Object storage | **SeaweedFS** `chrislusf/seaweedfs:4.47` (`server -s3`); job `s3-init` dùng `amazon/aws-cli` | Thay MinIO vì repository `minio/minio` không còn trên Docker Hub (S-03, DR-66). Dự phòng: RustFS 1.0 (Apache-2.0), đổi không phải sửa code | Apache-2.0 | ✅ (S-03) |
| Keycloak | `quay.io/keycloak/keycloak:26.7.4` | `start-dev --import-realm` | Apache-2.0 | ✅ (S-03) |
| Prometheus / Alertmanager | 3.x / 0.2x | | Apache-2.0 | 🔬 |
| Grafana | 12.x | Provision datasource và dashboard | AGPL-3.0 (chỉ chạy nội bộ) | 🔬 |
| Tempo / Loki / Alloy | 2.x / 3.x / 1.x | | AGPL-3.0 | 🔬 |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib` | Nhận OTLP, xuất sang Tempo | Apache-2.0 | 🔬 |
| Mailpit | `axllent/mailpit:1.x` | | MIT | 🔬 |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.x` | Profile `experiment` | MIT | 🔬 |
| nginx | `nginx:1.30-alpine` (1.30.5, nhánh stable) | Phục vụ SPA và PMTiles (range request đã kiểm ở S-05). `mime.types` không có `.mjs`; build Vite chỉ ra `.js` nên không ảnh hưởng | BSD-2 | ✅ (S-05) |

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
| Vite | 8.x (S-05 build được với 8.3.1) | Build, dev server | ✅ (S-05) |
| React / React DOM | **19.x** | | ✅ |
| TanStack Router / Query / Table / Virtual | 1.x / 5.x / 8.x / 3.x | Router có search params định kiểu; data fetching; bảng DLQ | 🔬 |
| Tailwind CSS | 4.x | | 🔬 |
| shadcn/ui (Radix primitives) | CLI mới nhất | Component có a11y | 🔬 |
| Apache ECharts (+ `echarts-for-react`) | bản mới nhất | Biểu đồ | 🔬 |
| MapLibre GL JS / `react-map-gl` / `pmtiles` / `@protomaps/basemaps` | **6.x** / 8.x / 4.x / 5.x | Bản đồ offline (DR-47, DR-82). S-05 chạy với 6.11.2 / — / 4.5.0 / 5.7.2. MapLibre 6 chỉ có ESM; worker đặt bằng `setWorkerUrl` (ADR-0021) | ✅ (S-05) |
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
| ESLint (flat config) + Prettier | 9.x + 3.x | Lint, format | 🔬 |

## 4. Thực nghiệm (Python)

| Thành phần | Phiên bản | Trạng thái |
| --- | --- | --- |
| Python | 3.12 (DR-52) | ✅ |
| uv | bản mới nhất | 🔬 |
| httpx, psycopg[binary] 3, docker (SDK), pandas, matplotlib, typer, pydantic; pytest, ruff (test, lint) | bản mới nhất lúc P3-06 | 🔬 |

## 5. Công cụ dev (`mise.toml`)

`java = "temurin-25"`, `node = "24"`, `pnpm = "10"`, `python = "3.12"`, `uv`, `kubectl`, `k3d`, `helm`, `helmfile`, `kubeseal`, `kubectl-cnpg`, `jq`, `kcat`, `gh`, `pmtiles = "1.31.2"` (go-pmtiles CLI, dùng cho `make tiles`, ADR-0021; S-05). Docker Desktop hoặc OrbStack cài riêng (DOC-38).

## 6. Thay đổi so với Spring Boot 3 cần biết

Người triển khai **không làm theo tài liệu hay ví dụ của Boot 3** ở các điểm sau. Mục nào có 🔬 thì S-06 xác nhận tên chính xác và sửa bảng này.

| Chủ đề | Boot 3 | Boot 4.1 (dự án dùng) | Trạng thái |
| --- | --- | --- | --- |
| Jackson | `com.fasterxml.jackson.databind.ObjectMapper` | Jackson 3: group `tools.jackson.core`, package `tools.jackson.databind`; ưu tiên `JsonMapper.builder()` (bất biến). Annotation vẫn ở `com.fasterxml.jackson.annotation` | ✅ |
| Cấu hình Jackson | `Jackson2ObjectMapperBuilderCustomizer` | `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` | ✅ (S-06) |
| Mock trong test | `@MockBean`, `@SpyBean` | `@MockitoBean`, `@MockitoSpyBean` (bản cũ đã bị xóa) | ✅ |
| Retry | Spring Retry (`spring-retry`) | Code của dự án dùng core retry của Spring Framework 7 (`RetryTemplate` mới, `@Retryable` + `@EnableResilientMethods`). **Ngoại lệ:** retry trong chunk step dùng builder cũ của Spring Batch 6, nhận `org.springframework.retry.RetryPolicy`/`BackOffPolicy` của Spring Retry 2.0.x (DR-80) | ✅ (S-06) |
| Chạy job | `JobLauncher.run` | `JobOperator.start(Job, JobParameters)`. `JobOperator.restart(JobExecution)` tìm job trong `JobRegistry`, nên mọi job phải là bean đã đăng ký; job chưa đăng ký cho lỗi gây hiểu nhầm "job execution already running". Execution kẹt ở `STARTED`: `JobOperator.recover(JobExecution)` (DOC-19 §7.2) | ✅ (S-06) |
| JobRepository | JDBC mặc định khi có DataSource | `spring-boot-starter-batch-jdbc` (starter `spring-boot-starter-batch` cho bản resourceless). `spring.batch.jdbc.table-prefix=batch.BATCH_`, `initialize-schema=never`. Bean `JacksonExecutionContextStringSerializer` được dùng tự động (context lưu dạng JSON) | ✅ (S-06) |
| Starter | `spring-boot-starter-web` | `spring-boot-starter-webmvc`, `-batch-jdbc`, `-kafka`, `-flyway`, `-opentelemetry`, `-security-oauth2-resource-server`, `-restclient`; test: `spring-boot-starter-test`, `-batch-jdbc-test`, `spring-boot-testcontainers` | ✅ (S-06) |
| Null-safety | Annotation của Spring | JSpecify (`@Nullable`, `@NullMarked`) | ✅ |
| Log có cấu trúc | Logback encoder ngoài | `logging.structured.format.console=ecs` (có sẵn từ 3.4) | ✅ |
| Spring Batch package | `org.springframework.batch.item.*`, `org.springframework.batch.core.*` phẳng | Batch 6: `org.springframework.batch.infrastructure.item.*`; `org.springframework.batch.core.job.*`, `.step.*`, `.launch.*`, `.listener.*` | ✅ (S-06) |
| Testcontainers | 1.x, `org.testcontainers.containers.*` | 2.x: artifact `testcontainers-<module>`; `org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer` (image `apache/kafka`); `GenericContainer` vẫn ở `org.testcontainers.containers` | ✅ (S-06) |

## 7. Những gì không dùng (và vì sao)

| Không dùng | Lý do | Nguồn |
| --- | --- | --- |
| Engine chunk tự xây | Spring Batch đã có và đã được kiểm chứng | ADR-0002 |
| Spring Cloud Stream, Kafka Streams | Thêm tầng trừu tượng, không giải quyết thêm vấn đề gì khi đích ghi là Postgres | ADR-0002 |
| Spring Cloud Data Flow, remote chunking, partitioning | Quá mức cần thiết | ADR-0002 |
| Apache Spark | Dữ liệu vừa sức một Postgres; mô hình checkpoint của Spark thay ngữ nghĩa effectively-once, skip và DLQ mà đồ án cần chứng minh; tốn RAM và xung đột Jackson 3. Cần phân tích lịch sử dài thì thử DuckDB trước | DR-84 |
| Spring AI | Không cần ChatClient; SDK Jev là đủ | DR-36 |
| Spring Cloud Contract | Nặng, thiên về JVM↔JVM | DR-44 |
| pg_partman | Job bảo trì tự viết đơn giản hơn và test được | DR-15 |
| Protobuf cho GTFS-rt | JSON dễ debug và dễ tiêm dữ liệu lỗi | DR-03 |
| OTel Java agent | Trùng span với Micrometer Tracing | DR-50 |
| Outbox cho sự kiện UI | Sự kiện UI là best-effort; nguồn sự thật nằm ở DB | DR-42 |
