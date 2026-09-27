# Bảo mật

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-27
>
> Phụ thuộc: [DOC-07](../03-architecture/system-context-and-containers.md) §5, [DOC-17](../05-data/db-roles-and-grants.md), [DOC-18](../05-data/data-lifecycle.md) §4, [DOC-31](../07-api/api-guidelines.md), [DOC-32](../07-api/api-endpoints.md), [DOC-26](realtime-delivery.md), [DOC-39](../09-operations/deploy-compose.md), [DOC-41](../09-operations/ci-cd.md), DR-20, DR-40, DR-45, DR-60, [ADR-0017](../04-adr/0017-keycloak-oauth2-resource-server.md)
>
> Người dùng chính: người viết app `api` (P4-08), người cấu hình Keycloak và nginx (P4-08, P5-15), người review bảo mật trước demo (P8)

## 1. Mục đích và phạm vi

Tài liệu mô tả cách hệ thống xác thực và phân quyền người dùng, bảo vệ ranh giới tin cậy, xử lý secret và dữ liệu cá nhân, và những rủi ro được chấp nhận có chủ ý. Đây là dự án demo và nghiên cứu chạy trên máy cá nhân (compose, k3d), không phục vụ người dùng thật; mục tiêu là **thiết kế đúng cách làm ở production** ở những chỗ rẻ, và ghi rõ chỗ nào cố ý nới lỏng.

Ngoài phạm vi: bảo mật của Jev (bên thứ ba, DOC-24), hardening hệ điều hành và Docker host.

## 2. Tài sản và ranh giới tin cậy

| Tài sản | Vì sao cần bảo vệ | Nơi ở |
| --- | --- | --- |
| Warehouse và ops (`pti_warehouse`) | Toàn vẹn dữ liệu là mục tiêu của đề tài (EXP-01…04) | Postgres |
| Thao tác vận hành (replay, discard, cờ, restart job) | Làm sai dữ liệu hoặc dừng pipeline | api → `ops.*` |
| `customer_ref` | Dữ liệu cá nhân (mô phỏng) duy nhất | `ticketing_source`, Kafka topic CDC, raw zone (DR-60) |
| Secret (mật khẩu DB, secret client, token webhook, API key Jev) | Mở toàn bộ các tài sản trên | `.env` (compose), Sealed Secret (k3d) |
| Tính sẵn sàng của API công khai | Màn hình hành khách | api |

Ranh giới TB-0…TB-3 theo DOC-07 §5. Tài liệu này chi tiết hóa TB-0 → TB-1 (§3–§6) và các kiểm soát chung (§7–§9).

## 3. Xác thực

### 3.1 Keycloak (realm `pti`)

Keycloak 26.7.4 chạy `start-dev`, import `infra/compose/keycloak/realm-pti.json` mỗi lần container được tạo (DOC-39). Nội dung realm:

| Thành phần | Cấu hình |
| --- | --- |
| Realm | `pti`; `sslRequired: "none"` ở compose (HTTP localhost), `"external"` ở profile `tls`; `bruteForceProtected: true` (khóa 1 phút sau 5 lần sai); `registrationAllowed: false`; `loginWithEmailAllowed: false` |
| Realm role | `viewer`, `operator` (composite: `operator` chứa `viewer`) |
| User demo | `viewer` / `viewer` (role `viewer`), `operator` / `operator` (role `operator`); `firstName` là `Demo Viewer`/`Demo Operator`; không bắt đổi mật khẩu |
| Client scope `pti-api-audience` | Audience mapper thêm `pti-api` vào `aud` của access token; gán mặc định cho mọi client dưới đây |
| Token | Access token 5 phút; SSO session idle 30 phút, max 10 giờ; refresh token xoay vòng (`revokeRefreshToken: true`); thuật toán `RS256` |

Client:

| Client | Loại | Luồng | Cấu hình | Dùng bởi |
| --- | --- | --- | --- | --- |
| `pti-web` | public | Authorization Code + PKCE (`S256` bắt buộc) | Redirect `http://localhost:8080/*`, `http://localhost:5173/*` (dev server Vite); web origins tương ứng; post-logout redirect `http://localhost:8080/`; không có direct access grant | SPA (`react-oidc-context`, `oidc-client-ts`) |
| `pti-smoke` | public | Direct access grant (password) | Chỉ có trong realm dev; không có redirect | Smoke test compose (DOC-39 §8), integration test |
| `pti-experiments` | confidential | Client credentials | Service account có realm role `operator`; secret `${KEYCLOAK_EXPERIMENTS_CLIENT_SECRET}` thay lúc import | Runner thực nghiệm (DOC-45 §2) |

Username của service account là `service-account-pti-experiments`, nên actor audit của runner là `user:service-account-pti-experiments`.

### 3.2 API là OAuth2 resource server

```java
package dev.pti.api.security;

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
    return http
        .securityMatcher("/api/**")
        .csrf(csrf -> csrf.disable())                         // bearer tokens only, no cookies (§5.1)
                .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(EndpointRules::apply)          // §4, single source of truth
        .oauth2ResourceServer(o -> o
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
            .authenticationEntryPoint(problemEntryPoint)      // 401 as Problem Details
            .accessDeniedHandler(problemDeniedHandler))       // 403 as Problem Details
        .anonymous(Customizer.withDefaults())
        .build();
  }

  @Bean
  SecurityFilterChain internal(HttpSecurity http) throws Exception { /* §6: /internal/** with webhook token */ }

  @Bean
  static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy("ROLE_OPERATOR > ROLE_VIEWER");
  }
}
```

| Kiểm tra | Cách làm |
| --- | --- |
| Chữ ký | JWKS từ `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (mạng nội bộ, `http://keycloak:8080/realms/pti/protocol/openid-connect/certs`); cache JWKS của Nimbus (5 phút, tự tải lại khi gặp `kid` lạ) |
| `iss` | Bằng `pti.api.security.issuer` (`http://localhost:8180/realms/pti`, URL trình duyệt thấy, DOC-39 §3) |
| `aud` | Chứa `pti-api` (`JwtClaimValidator`) |
| `exp`, `nbf` | Mặc định của Spring Security, lệch đồng hồ cho phép 30 giây |
| Thuật toán | Chỉ `RS256` |
| Role | `realm_access.roles` → `ROLE_VIEWER`/`ROLE_OPERATOR` (converter tự viết; role khác bị bỏ qua) |

- Principal name (`JwtAuthenticationConverter.setPrincipalClaimName`) = `preferred_username`. **Actor audit** ghi vào DB (`requested_by`, `resolved_by`, `acknowledged_by`, `feedback_by`, `updated_by`, `dlq_action_log.actor`) = `user:<preferred_username>` (DOC-15 CHECK `^user:.+$`).
- Token sai ở endpoint công khai trả 401, không hạ xuống anonymous (DOC-31 §7.1).
- Không có session phía server, không có cookie xác thực. Frontend giữ token trong bộ nhớ (`oidc-client-ts` `InMemoryWebStorage`), không trong `localStorage`, để giảm thiệt hại khi có XSS. Tải lại trang thì `signinSilent` bằng iframe (session Keycloak còn) để lấy lại token.

### 3.3 Chế độ không có Keycloak (k3d values `lite`)

Values `lite` không chạy Keycloak để tiết kiệm RAM (DOC-10 §5). API bật profile `static-jwt`:

- `JwtDecoder` = `NimbusJwtDecoder.withPublicKey(<RSA public key từ Secret pti-static-jwt>)`; kiểm `iss = pti-static`, `aud` chứa `pti-api`.
- Token được ký bằng private key trong cùng Secret qua `make k3d-token ROLE=operator TTL=1h` (script `scripts/mint-jwt.py`), chỉ dùng cho test tải và chaos (EXP-07, EXP-08).
- UI trong chế độ này chỉ có tính năng anonymous; nút "Sign in" bị ẩn (`env.js` không có `PTI_KEYCLOAK_URL`).
- `static-jwt` không thể bật cùng profile khác ngoài `k8s` (`StaticJwtGuard` ném lỗi lúc khởi động nếu có `PTI_KEYCLOAK_URL` hoặc profile `demo`).

## 4. Phân quyền: ma trận endpoint × role

`EndpointRules.apply` là **nguồn duy nhất** của bảng này; test SEC-01 sinh ca kiểm thử từ chính bảng dưới (FR-10.2). Quy tắc cuối cùng là `anyRequest().denyAll()`: endpoint mới quên khai báo sẽ bị chặn thay vì mở.

| Endpoint (DOC-32) | anonymous | viewer | operator | Ghi chú |
| --- | --- | --- | --- | --- |
| E-01…E-02, E-04 `GET /routes/**` (trừ delays) | ✓ | ✓ | ✓ | |
| E-03 `GET /routes/{id}/delays` | 401 | ✓ | ✓ | |
| E-05 `GET /vehicles/live` | ✓ (không có overlay) | ✓ | ✓ | Kiểm trong service |
| E-06…E-08 `GET /stops/**` | ✓ | ✓ | ✓ | Alert theo audience |
| E-10, E-11 bunching | 401 | ✓ | ✓ | |
| E-12, E-13 disruption | ✓ (góc nhìn công khai) | ✓ | ✓ | Kiểm trong service |
| E-14…E-17 OTP, ticketing, dispatch | 401 | ✓ | ✓ | |
| E-18 feedback | 401 | 403 | ✓ | |
| E-20 `GET /alerts` | ✓ (PUBLIC) | ✓ | ✓ | Xin audience khác khi anonymous → 403 |
| E-21 ack | 401 | 403 | ✓ | |
| E-30…E-32, E-34, E-37, E-38 `GET /etl/**` | 401 | ✓ | ✓ | |
| E-33, E-35, E-36 job request | 401 | 403 | ✓ | |
| E-40…E-42, E-48 `GET /etl/dlq/**` | 401 | ✓ | ✓ | |
| E-43…E-47 thao tác DLQ | 401 | 403 | ✓ | |
| E-50 `POST /etl/replays` | 401 | 403 | ✓ | |
| E-51, E-52 | 401 | ✓ | ✓ | |
| E-53 estimate | 401 | 403 | ✓ | Chỉ có ích cho người tạo replay |
| E-55, E-56 `GET /etl/flags/**` | 401 | ✓ | ✓ | |
| E-57 `PUT /etl/flags/{key}` | 401 | 403 | ✓ | |
| E-60 freshness, E-61 `/me` | ✓ | ✓ | ✓ | |
| E-70 `/stream` kênh `vehicles`, `alerts` | ✓ (PUBLIC) | ✓ | ✓ | |
| E-70 `/stream` kênh `jobs`, `dlq` | 401 | ✓ | ✓ | |
| E-90 `/sim/**` (profile `demo`) | 401 | 403 | ✓ | |
| `/v3/api-docs/**`, `/swagger-ui/**` (profile `dev`) | ✓ | ✓ | ✓ | Tắt ở profile khác |
| Mọi path khác dưới `/api/**` | 404 hoặc 401 | 404 | 404 | `denyAll` cho path có handler nhưng chưa khai báo |

- "Góc nhìn công khai" và lọc audience là **kiểm tra dữ liệu trong service**, không phải quy tắc URL; có test riêng (EP-06, EP-09, EP-11, EP-12, SE-03, SE-04).
- Operator ⊇ viewer nhờ `RoleHierarchy`; không có endpoint nào chỉ cho viewer mà cấm operator.

## 5. Bảo vệ ở tầng HTTP

### 5.1 CSRF

Tắt. API chỉ nhận xác thực bằng header `Authorization: Bearer`, không có cookie phiên, nên trình duyệt không tự đính kèm thông tin xác thực vào request giả mạo. Keycloak tự bảo vệ form đăng nhập của nó.

### 5.2 CORS

Không cấu hình CORS ở mọi profile: trình duyệt luôn gọi API cùng origin.

- Compose: frontend và API cùng origin `http://localhost:8080` (nginx proxy `/api/`, DOC-26 §10).
- Dev: Vite dev server (`http://localhost:5173`) proxy `/api` sang `http://localhost:8081` (DOC-38), nên request vẫn cùng origin với trang.
- Spring Security giữ mặc định (không có `CorsConfigurationSource`), nên preflight từ origin khác không nhận được header `Access-Control-Allow-*` và trình duyệt chặn.

### 5.3 Header bảo mật

Đặt ở nginx của frontend cho mọi response (kể cả `/api/`):

```nginx
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "geolocation=(self), camera=(), microphone=(), payment=()" always;
add_header Cross-Origin-Opener-Policy "same-origin" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; worker-src 'self' blob:; child-src blob:; connect-src 'self' ${PTI_KEYCLOAK_URL} ${PTI_MAP_TILE_ORIGINS}; frame-src ${PTI_KEYCLOAK_URL}; frame-ancestors 'none'; base-uri 'self'; form-action 'self' ${PTI_KEYCLOAK_URL}; object-src 'none'" always;
```

- `worker-src blob:` và `child-src blob:`: MapLibre tạo web worker từ blob URL.
- `style-src 'unsafe-inline'`: MapLibre và ECharts đặt style inline trên phần tử; chấp nhận vì script vẫn bị khóa chặt.
- `connect-src` và `frame-src` có origin Keycloak (`PTI_KEYCLOAK_URL`, mặc định `http://localhost:8180`) cho `signinSilent` và gọi token endpoint. `PTI_MAP_TILE_ORIGINS` rỗng khi dùng PMTiles cục bộ (DR-47), là origin tile server khi dev.
- Giá trị `${…}` được entrypoint của image frontend thay lúc khởi động (cùng cơ chế với `env.js`, DOC-39).
- `Strict-Transport-Security` chỉ bật ở profile `tls` (§5.5).
- API tự đặt `X-Content-Type-Options: nosniff` và `Cache-Control` (DOC-31 §10.3) cho trường hợp gọi thẳng cổng 8081.

### 5.4 Lỗi không lộ thông tin

- Problem Details không bao giờ chứa stack trace, tên class, câu SQL hay giá trị tham số (DOC-30 §3). Lỗi 500 chỉ có `traceId`.
- `server.error.include-*` = `never`; trang lỗi whitelabel tắt.
- Header `Server` của nginx: `server_tokens off`.

### 5.5 TLS

Compose chạy HTTP trên `localhost`, vì trình duyệt coi `http://localhost` là secure context (Web Crypto cho PKCE vẫn hoạt động). Profile `tls` (tùy chọn, dùng khi demo trên máy khác): nginx phục vụ `https://pti.local:8443` bằng chứng chỉ `mkcert`, Keycloak `KC_HOSTNAME=https://pti.local:8443/auth` qua cùng nginx, redirect URI tương ứng trong realm, bật HSTS `max-age=300` (ngắn, vì là môi trường thử). Bên trong mạng compose vẫn là HTTP (TB-2).

### 5.6 Rate limit và giới hạn tài nguyên

DOC-31 §11 (rate limit), DOC-26 §7 (kết nối SSE), DOC-31 §3 (body ≤ 1 MiB), DOC-31 §10.1 (`statement_timeout` 5 s, pool nhỏ). Các giới hạn này cùng nhau chặn việc một client làm cạn DB hay bộ nhớ của API.

## 6. Endpoint nội bộ

- `/internal/**` có `SecurityFilterChain` riêng: không OAuth2; filter `WebhookTokenFilter` so `Authorization: Bearer <token>` với nội dung file `pti.api.alert-webhook.token-file` bằng `MessageDigest.isEqual` (hằng thời gian); đọc file một lần lúc khởi động và khi file đổi (`WatchService`), để xoay token không cần restart (RB-12).
- Nginx trả 404 cho `/internal/` (DOC-26 §10); compose không publish cổng nào khác của `api` ngoài 8081 (bind `127.0.0.1`) và 9081 (Actuator). Trên k3d, NetworkPolicy chỉ cho `alertmanager` gọi `/internal/**` (không phân biệt path ở L4, nên kiểm tra token vẫn là lớp chính).
- Actuator chỉ ở cổng quản trị (`management.server.port=9080`), expose `health`, `info`, `prometheus`; `health` hiện chi tiết chỉ khi `show-details=when-authorized` và không có ai được authorized (tức chỉ trạng thái tổng). Prometheus scrape qua mạng nội bộ.
- API điều khiển simulator không xác thực (DOC-25 §8) nhưng chỉ bind `127.0.0.1` trên host; người dùng UI đi qua proxy E-90 (operator, profile `demo`).

## 7. Secret

| Secret | Sinh | Lưu | Xoay |
| --- | --- | --- | --- |
| Mật khẩu role DB (10 biến, DOC-17 §6) | `make secrets` (`openssl rand -base64 24`) | `.env` (trong `.gitignore`); Sealed Secret trên k3d | RB-12 |
| `KEYCLOAK_ADMIN_PASSWORD` | `make secrets` | `.env` | Tạo lại container Keycloak |
| `KEYCLOAK_EXPERIMENTS_CLIENT_SECRET` | `make secrets` | `.env`, thay vào realm lúc import | Sửa `.env`, `make restart S=keycloak`, restart runner |
| `ALERTMANAGER_WEBHOOK_TOKEN` | `make secrets` (32 byte ngẫu nhiên, hex) | File mount cho Alertmanager và api | Ghi file mới; api đọc lại tự động (§6); Alertmanager reload |
| `TYPESAFE_API_KEY` | Người dùng nhập (P6) | `.env`; Sealed Secret `pti-jev` (DOC-40) | Theo nhà cung cấp |
| Credential S3 (connector, etl-batch) | `make secrets` | `s3.json` của SeaweedFS sinh từ `.env` | RB-12 |
| Key `static-jwt` (k3d `lite`) | `make k3d-keys` (RSA 2048) | Sealed Secret | Sinh lại, redeploy |

Quy tắc:

- Không commit secret. `gitleaks` chạy ở pre-commit và CI (DOC-41); `.env.example` chỉ có tên biến.
- Không log secret: logback có `MaskingPatternLayout`-tương đương trong encoder JSON (thay giá trị của khóa `password`, `secret`, `token`, `authorization`, `apiKey` bằng `***`), có unit test (SEC-12).
- Config Spring nhận secret qua biến môi trường hoặc file (`spring.config.import=optional:configtree:/run/secrets/`), không qua tham số dòng lệnh (lộ trong `ps`).

## 8. Dữ liệu cá nhân và quyền dữ liệu

- `customer_ref` chỉ tồn tại ở `ticketing_source`, topic CDC và raw zone 30 ngày (DR-60, DOC-18 §4). ETL loại nó trước khi ghi warehouse và DLQ; prompt gửi Jev có test blocklist. API không bao giờ thấy trường này, và `PUT /etl/dlq/{id}/payload` từ chối payload chứa nó (`pii-not-allowed`).
- Quyền DB tối thiểu theo từng app (DOC-17): `api_reader` chỉ SELECT; `replay_operator` chỉ UPDATE đúng các cột nó cần. Lỗi logic trong API không thể sửa fact hay xóa dead letter.
- Bucket `raw` (SeaweedFS): chỉ connector (ghi) và etl-batch (đọc) có credential. API không có credential S3 (vì vậy E-53 ước lượng từ nhật ký micro-batch).
- Log request không có query string (DOC-31 §13), vì `q` là nội dung người dùng gõ.

## 9. Kiểm tra đầu vào và chống injection

| Rủi ro | Kiểm soát |
| --- | --- |
| SQL injection | Mọi SQL dùng tham số bind (`JdbcClient`). Đoạn SQL động (biểu thức bucket E-03) chọn từ danh sách hằng trong Java, không ghép từ input. `LIKE` escape `%`, `_`, `\` (E-06) |
| JSON quá lớn hoặc lồng sâu | Body ≤ 1 MiB; Jackson `StreamReadConstraints` `maxNestingDepth = 64`, `maxStringLength = 1 MiB` |
| SSRF qua `sourceUri` của `GtfsStaticLoadJob` | API chỉ kiểm scheme; `etl-batch` kiểm danh sách host và thư mục cho phép (DOC-21 §1.1) trước khi mở URI |
| SSRF qua proxy simulator | Đích cố định (`pti.api.sim.base-url`), path theo danh sách cho phép (E-90) |
| Tham số job tùy ý | Danh sách job và tham số cho phép (E-33); `etl-batch` kiểm lại |
| Payload DLQ độc hại hiển thị trên UI | `rawPayload` là chuỗi, UI hiển thị trong CodeMirror (không render HTML) |
| XSS nói chung | React escape mặc định; cấm `dangerouslySetInnerHTML` bằng ESLint rule; CSP §5.3 |
| Open redirect sau đăng nhập | Redirect URI cố định trong Keycloak; SPA chỉ điều hướng tới path nội bộ lưu trong `state` |

## 10. Chuỗi cung ứng

Theo DOC-41: `gitleaks`; SpotBugs (+ FindSecBugs); OWASP Dependency-Check (chặn merge khi CVSS ≥ 9); `pnpm audit --audit-level high`; Trivy quét image (chặn lỗ hổng `CRITICAL` có bản sửa); Dependabot hằng tuần cho Gradle, pnpm, Docker, GitHub Actions. Image nền dùng tag cố định kèm digest.

## 11. STRIDE

| Ranh giới | Mối đe dọa | Ví dụ | Kiểm soát |
| --- | --- | --- | --- |
| TB-0 → TB-1 | **S**poofing | Giả token operator | Chữ ký RS256, `iss`, `aud`, `exp` (§3.2) |
| | **T**ampering | Sửa body replay giữa đường | TLS ở profile `tls`; compose là localhost |
| | **R**epudiation | Chối đã discard dead letter | `dlq_action_log`, cột `*_by` với actor `user:<username>`; log `INFO` nghiệp vụ có `trace_id` |
| | **I**nformation disclosure | Anonymous đọc alert nội bộ, episode bị ẩn | Lọc audience trong service (§4), 404 cho episode không công khai, projection SSE |
| | **D**enial of service | Spam `/vehicles/live`, mở nhiều SSE | Rate limit, giới hạn kết nối, cache, `statement_timeout` |
| | **E**levation of privilege | Viewer gọi `PUT /etl/flags` | `EndpointRules`, `denyAll` mặc định, test ma trận (SEC-01) |
| TB-0 → Keycloak | S, I | Dò mật khẩu, đánh cắp code | Brute-force protection, PKCE S256, redirect cố định |
| TB-1 → TB-2 | E, T | Lỗ hổng API dẫn tới sửa fact | Role DB tối thiểu (DOC-17), replica cho đọc |
| Trong TB-2 | S, T | Container khác gửi webhook giả | Token webhook, NetworkPolicy (k3d) |
| | I | Đọc raw zone có `customer_ref` | Credential S3 riêng, API không có |
| TB-2 → TB-3 | I | Rò PII sang Jev | Blocklist có test, state PII-free (DR-60) |
| | D | Jev chậm làm nghẽn pipeline | Timeout 2 s, circuit breaker, bulkhead (DOC-24) |

## 12. Rủi ro được chấp nhận

| # | Rủi ro | Vì sao chấp nhận | Giảm thiểu |
| --- | --- | --- | --- |
| AR-01 | User demo có mật khẩu yếu công khai (`viewer/viewer`, `operator/operator`) | Mục đích demo; chỉ bind `localhost` | Keycloak brute-force protection; không dùng ngoài máy cá nhân |
| AR-02 | Keycloak `start-dev`, H2, HTTP | Tiết kiệm tài nguyên; realm import lại mỗi lần | Profile `tls` khi cần demo qua mạng |
| AR-03 | Client `pti-experiments` có role `operator` và secret dài hạn | Runner cần tạo replay và mở SSE không tương tác | Secret sinh ngẫu nhiên, chỉ trong `.env`; xoay bằng RB-12 |
| AR-04 | `pti-smoke` cho password grant | Chỉ realm dev; smoke test cần token không qua trình duyệt | Không có trong realm của profile `tls` |
| AR-05 | Rate limit theo từng pod (giới hạn thực gấp N lần) | DR-45 | Đủ để chặn lạm dụng thô |
| AR-06 | Raw zone giữ `customer_ref` 30 ngày, không mã hóa ở mức object | Cần bản gốc để replay đúng (FR-01.4); dữ liệu mô phỏng | Credential riêng; lifecycle 30 ngày (DOC-18) |
| AR-07 | SeaweedFS có thể không hỗ trợ policy giới hạn theo prefix | Hạn chế của sản phẩm (DR-66) | Credential theo bucket; chỉ hai client có quyền |
| AR-08 | Không có bản sao offsite của raw zone và backup | Máy cá nhân | Backup cục bộ (DOC-43); dữ liệu có thể sinh lại bằng simulator |
| AR-09 | Token webhook Alertmanager là bearer token dùng chung | Alertmanager không hỗ trợ mTLS đơn giản ở compose | Mạng nội bộ, nginx chặn `/internal/`, so sánh hằng thời gian, xoay được |
| AR-10 | k3d `lite` dùng JWT ký bằng key tĩnh | Không đủ RAM cho Keycloak khi test tải | Chỉ values `lite`; `StaticJwtGuard`; token có TTL |
| AR-11 | Viewer đọc được payload DLQ | Cần để điều tra; payload đã loại PII khi ghi | DOC-18 §4 |
| AR-12 | API công khai không có CAPTCHA hay xác thực | Hành khách không đăng nhập (DR-40) | Rate limit, dữ liệu công khai theo bản chất |

## 13. Cấu hình

| Khóa | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `http://keycloak:8080/realms/pti/protocol/openid-connect/certs` | §3.2 |
| `pti.api.security.issuer` | `http://localhost:8180/realms/pti` | §3.2 |
| `pti.api.security.audience` | `pti-api` | §3.2 |
| `pti.api.security.clock-skew` | `30s` | §3.2 |
| `pti.api.alert-webhook.token-file` | `/run/secrets/alertmanager-webhook-token` | §6 |
| `pti.api.security.static-jwt.public-key-file` | — (chỉ profile `static-jwt`) | §3.3 |

## 14. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| SEC-01 | Ma trận §4: mọi endpoint × {anonymous, viewer, operator} (MockMvc, JWT giả ký bằng key test) | Mã trạng thái đúng từng ô (FR-10.2) |
| SEC-02 | Token hết hạn, sai chữ ký, sai `iss`, thiếu `aud`, `alg=none`, `HS256` | 401 cho tất cả, kể cả ở endpoint công khai |
| SEC-03 | Handler mới không khai báo trong `EndpointRules` (test cố ý) | 403/401 do `denyAll` |
| SEC-04 | Viewer gọi `GET /alerts?audience=ENGINEERING`; anonymous gọi như vậy | 200; 403 |
| SEC-05 | Actor audit sau `POST /etl/replays` bởi `operator` | `requested_by = 'user:operator'` |
| SEC-06 | Webhook: không token, token sai độ dài, token đúng | 401, 401, 204 |
| SEC-07 | Request tới `http://localhost:8080/internal/alerts/alertmanager` qua nginx | 404 |
| SEC-08 | Header bảo mật trên `index.html` và `/api/v1/routes` qua nginx | Có đủ header §5.3 |
| SEC-09 | Preflight `OPTIONS` từ origin `http://evil.example` | Không có header `Access-Control-Allow-Origin` |
| SEC-10 | Lỗi 500 cố ý | Problem Details chỉ có `traceId`, không có stack trace |
| SEC-11 | `q` chứa `' OR 1=1 --`, `%`, `\` | Tìm theo nghĩa đen, không lỗi |
| SEC-12 | Log một object có khóa `password`, `authorization` | Giá trị bị thay bằng `***` |
| SEC-13 | Profile `static-jwt` cùng `PTI_KEYCLOAK_URL` | App không khởi động |
| SEC-14 | E2E Playwright: đăng nhập `operator` qua Keycloak (PKCE), tải lại trang | Vẫn đăng nhập (silent renew); token không có trong `localStorage` |
| SEC-15 | CI: `gitleaks` trên commit có chuỗi giống secret | Pipeline fail |

## 15. Câu hỏi còn mở

Không có.
