# Quy ước API

> Trạng thái: **Approved** · Cập nhật: 2026-09-30 (DR-103: nạp cache single-flight, bucket `public` trên compose; DR-104: Clean Architecture) · DOC-31
>
> Phụ thuộc: DR-20, DR-39, DR-43, DR-45, DR-48, DR-61, DR-67, DR-103, ADR-0013, ADR-0017, ADR-0031, [DOC-10](../03-architecture/quality-attributes.md) §4, [DOC-17](../05-data/db-roles-and-grants.md), [DOC-27](../06-design/security.md), [DOC-30](../06-design/error-handling.md) §3
>
> Người dùng chính: người viết app `api` (P4-09…P4-16), frontend (P5-02), DOC-32 (mỗi endpoint tuân theo tài liệu này)

## 1. Mục đích và phạm vi

Tài liệu gom mọi quy ước chung của REST API trong app `api`: đường dẫn, JSON, thời gian, phân trang, lọc, header, idempotency, cache, rate limit, datasource, phiên bản. DOC-32 mô tả từng endpoint và chỉ ghi điểm khác với tài liệu này. SSE có thêm quy ước riêng ở DOC-26 và DOC-33. Phân quyền nằm ở DOC-27.

Ngoài phạm vi: API điều khiển của simulator (DOC-25 §8; `api` chỉ proxy nó ở profile `demo`, DOC-32 §10) và Actuator (cổng quản trị 9080, DOC-28).

## 2. Đường dẫn và đặt tên

| Quy tắc | Ví dụ |
| --- | --- |
| Tiền tố `/api/v1` cho mọi endpoint công khai (DR-39) | `/api/v1/routes` |
| Danh từ số nhiều, chữ thường, nối bằng `-` | `/insights/ticketing-anomalies`, `/insights/dispatch-suggestions` |
| Ngoại lệ giữ theo SDD gốc | `/insights/bunching`, `/insights/disruption`, `/insights/otp` (không đổi để khớp UC và SDD) |
| Id trong path đúng như khóa tự nhiên của nguồn | `/routes/18`, `/stops/51405`, `/etl/dlq/0192f5a1-…`, `/etl/jobs/job:4127` |
| Hành động không phải CRUD là `POST` vào sub-resource dạng động từ | `/etl/dlq/{id}/replay`, `/alerts/{id}/ack`, `/etl/jobs/{runId}/restart` |
| Query param camelCase | `routeId`, `directionId`, `fromTs` |
| Endpoint nội bộ (không qua nginx, không có tiền tố) | `/internal/alerts/alertmanager` |

Nhóm path và package Java tương ứng (`dev.pti.api.*`):

| Nhóm | Path | Package |
| --- | --- | --- |
| Vận tải | `/routes/**`, `/stops/**`, `/vehicles/**` | `transit` |
| Insight | `/insights/**` | `insight` |
| Cảnh báo | `/alerts/**` | `alert` |
| Vận hành ETL | `/etl/**` | `etl` |
| Hệ thống | `/system/**`, `/me` | `system` |
| Real-time | `/stream` | `stream` (DOC-26) |
| Webhook | `/internal/**` | `internal` |
| Proxy simulator | `/sim/**` | `sim` (chỉ profile `demo`) |

`/ops/**` **không** tồn tại. Các tài liệu cũ nhắc `/ops/job-runs`, `/ops/batches/{id}`, `/ops/replays/{id}` được thay bằng `/etl/jobs`, `/etl/batches/{batchId}`, `/etl/replays/{id}` (DOC-32).

## 3. JSON

- `Content-Type: application/json` cho request có body và mọi response thành công; lỗi là `application/problem+json` (DOC-30 §3).
- Tên trường camelCase. Cột DB `snake_case` được ánh xạ tường minh trong record DTO, không dùng naming strategy toàn cục.
- **Trường null bị bỏ** (`@JsonInclude(NON_NULL)` toàn cục). OpenAPI đánh dấu các trường này là không bắt buộc. Mảng không bao giờ null: không có phần tử thì trả `[]`.
- Enum trả **nguyên giá trị trong DB**: phần lớn là `UPPER_SNAKE` (`OPEN`, `REPLAY_REQUESTED`, `GTFS_RT_VEHICLE_POSITION`). Riêng nhãn phân loại của AI giữ chữ thường như DB (`schema_violation`, `fraud_suspect`, `hold_follower`), vì chúng là nhãn gửi Jev (DOC-15 §3.2). Request nhận đúng các giá trị này, phân biệt hoa thường.
- Id: UUID dạng chuỗi chữ thường; id của Spring Batch là số; id chạy (`runId`) là chuỗi `job:<n>` hoặc `stream:<listener>:<minute>` (DOC-15 §5).
- Số thập phân (delay, z-score, tỷ lệ, tiền) là JSON number, giữ nguyên số chữ số của cột DB. Tỷ lệ nằm trong `[0, 1]` (ví dụ `refundRatio: 0.4800`); phần trăm chỉ dùng khi tên trường có `Percentage` (`otpPercentage: 87.42`).
- Payload DLQ gốc (`rawPayload`) là **chuỗi** (có thể không phải JSON hợp lệ); `editedPayload` là JSON object.
- Jackson 3 (`tools.jackson.*`, DOC-11 §6) với `JsonMapper` của Boot; `FAIL_ON_UNKNOWN_PROPERTIES = true` cho request (trường lạ → 400 `validation-error`), để lỗi gõ tên trường không bị bỏ qua lặng lẽ.
- Body request tối đa 1 MiB (`spring.servlet.multipart` không dùng; giới hạn bằng filter, vượt → 413 `payload-too-large`).

## 4. Thời gian

### 4.1 Định dạng

| Loại | Định dạng | Ví dụ |
| --- | --- | --- |
| Thời điểm (response) | ISO-8601 UTC, hậu tố `Z`, tối đa 3 chữ số phần lẻ giây, bỏ phần lẻ khi bằng 0 | `2026-09-29T21:19:30Z`, `2026-09-29T21:19:30.107Z` |
| Thời điểm (request) | ISO-8601 **bắt buộc có offset** (`Z` hoặc `±hh:mm`); không offset → 400 | `2026-09-29T16:19:30-05:00` |
| Thời điểm tương đối (request) | `-<n>m`, `-<n>h`, `-<n>d` so với "bây giờ" của trục thời gian (§4.2) | `from=-60m` |
| Ngày phục vụ, ngày địa phương | `yyyy-MM-dd` (không có múi giờ; hiểu theo múi giờ của feed ACTIVE) | `serviceDate=2026-09-29` |
| Khoảng thời lượng | ISO-8601 duration | `PT90M` |

- Cột `TIMESTAMPTZ` đọc ra `Instant`, cắt về mili giây bằng `ApiTime.truncate` trước khi serialize (Postgres lưu micro giây).
- API **không** đổi thời điểm sang giờ địa phương. Frontend hiển thị theo `activeFeed.timezone` của `GET /system/freshness` (DR-48). Riêng các trường nhóm theo ngày/giờ địa phương (`dayOfWeek`, `hourOfDay`, `serviceDate`, bucket `day` của delays) đã tính theo múi giờ feed ở server.

### 4.2 Trục thời gian

Hệ thống có hai đồng hồ (DR-67): **giờ nghiệp vụ** (`businessNow = giờ thật + pti.clock.offset`, dùng cho event time của dữ liệu) và **giờ thật** (cột audit như `created_at`, `requested_at`, `started_at` của job). Mỗi tham số thời gian thuộc một trục, và DOC-32 ghi trục cho từng endpoint:

| Trục | Tham số áp dụng | "Bây giờ" khi dùng dạng tương đối và giá trị mặc định |
| --- | --- | --- |
| Event time | Vị trí xe, delay, episode insight, anomaly, ETA, OTP | `BusinessClock.now()` |
| Audit | Lần chạy job, dead letter, replay, job request, alert | `Clock.systemUTC()` |
| Giờ record Kafka | `fromTs`/`toTs` của replay raw zone (DR-70) | Giờ thật |

Khi `pti.clock.offset = 0` (mặc định) hai trục trùng nhau.

### 4.3 Khoảng thời gian

| Quy tắc | Giá trị |
| --- | --- |
| Cặp tham số | `from` (bao gồm) và `to` (không bao gồm): `[from, to)` |
| Mặc định | `to = now` của trục; `from = to − 24h` (DR-39). Endpoint có mặc định khác ghi ở DOC-32 (ví dụ `/etl/jobs` 1 giờ, OTP 7 ngày phục vụ) |
| Tối đa | 31 ngày (DR-39). Ngoại lệ: `/etl/jobs`, `/etl/jobs/summary` tối đa 24 giờ (DOC-15 §5) |
| Vi phạm | `from ≥ to`, vượt tối đa, `to` quá `now + 1d` → 400 `validation-error`, `errors[].field` = `from`/`to` |

## 5. Phân trang

### 5.1 Keyset (danh sách chuỗi thời gian và danh sách lớn)

Request: `?limit=50&cursor=<opaque>`. Response:

```json
{
  "items": [ … ],
  "nextCursor": "eyJ2IjoxLCJrIjpbIjIwMjYtMDktMjlUMjE6MTk6MzBaIiwiYzY5YmNjNTUtLi4uIl0sImYiOiI5ZjJjIn0"
}
```

| Quy tắc | Giá trị |
| --- | --- |
| `limit` | Mặc định 50, tối đa **500** (FR-10.3); ngoài `[1, 500]` → 400 |
| Thứ tự | Cố định cho từng endpoint (DOC-32), luôn kết thúc bằng một cột duy nhất (thường là `id`) để không có hai dòng cùng khóa |
| `nextCursor` | Có khi còn trang sau; vắng mặt ở trang cuối. Server đọc `limit + 1` dòng để biết còn hay không, không đếm tổng |
| Nội dung cursor | `base64url(JSON {"v": 1, "k": [<khóa sắp xếp của dòng cuối>], "f": "<4 ký tự đầu SHA-256 của bộ lọc đã chuẩn hóa>"})`. Không ký, vì cursor không mở ra dữ liệu nào ngoài quyền của người gọi |
| Cursor sai | Giải mã lỗi, `v` lạ, hoặc `f` không khớp bộ lọc hiện tại (dùng cursor của truy vấn khác) → 400 `validation-error`, field `cursor` |
| Tổng số | Không trả. Màn hình cần đếm dùng endpoint `…/summary` riêng |

Câu SQL keyset với thứ tự giảm dần theo thời gian:

```sql
WHERE (created_at, id) < (:cursorTs, :cursorId)   -- trang sau
ORDER BY created_at DESC, id DESC
LIMIT :limit + 1
```

### 5.2 Danh sách nhỏ

Endpoint mà số phần tử bị chặn bởi bản chất dữ liệu trả toàn bộ trong `{"items": [...]}`, không có `limit`/`cursor`: tuyến của feed (≈ 130), cờ vận hành, phiên bản feed, OTP theo tuyến, bảng tóm tắt. DOC-32 ghi rõ "danh sách nhỏ" và giới hạn trên.

**Ngoại lệ FR-10.3:** `GET /vehicles/live` là **snapshot** vị trí, không phải danh sách phân trang: số phần tử bị chặn bởi số xe đang chạy (606 ở cao điểm, DR-01) và phân trang một snapshot đang thay đổi sẽ cho kết quả không nhất quán. Server chặn cứng ở `pti.api.vehicles.max-items` (1.500); vượt thì trả 1.500 xe mới nhất và log `WARN`. Tiêu chí của FR-10.3 được sửa tương ứng (DOC-03).

## 6. Lọc và sắp xếp

- Lọc bằng query param cùng tên với trường trong response: `routeId`, `status`, `source`, `severity`…
- Nhiều giá trị: lặp tham số hoặc phân tách bằng dấu phẩy, hai cách tương đương: `status=NEW&status=MANUAL` = `status=NEW,MANUAL`. Tối đa 20 giá trị mỗi tham số.
- Giá trị enum sai → 400 `validation-error` kèm danh sách giá trị hợp lệ trong `errors[].message`.
- **Không có** tham số `sort` tùy ý: mỗi endpoint có một thứ tự cố định được index phục vụ (DOC-32). Cách này giữ p95 dưới 200 ms (NFR-10) và giữ keyset đúng.
- Tìm kiếm văn bản chỉ có ở `GET /stops?q=` (DOC-32).

## 7. Header

### 7.1 Request

| Header | Dùng khi | Ghi chú |
| --- | --- | --- |
| `Authorization: Bearer <JWT>` | Endpoint cần đăng nhập; tùy chọn ở endpoint công khai (có token thì nhận thêm dữ liệu theo role) | DOC-27. Token sai hoặc hết hạn ở endpoint công khai vẫn trả 401, không hạ xuống anonymous, để lỗi cấu hình client lộ ra sớm |
| `Idempotency-Key` | `POST` tạo yêu cầu (§8) | 1–100 ký tự `[A-Za-z0-9_-]`; frontend dùng UUIDv4 |
| `Last-Event-ID` | `GET /stream` | DOC-26 |
| `traceparent` | Tùy chọn | W3C; có thì span của request nối vào trace của client |
| `Accept` | Tùy chọn | Chỉ `application/json` (và `text/event-stream` cho `/stream`); khác → 406 |

### 7.2 Response

| Header | Khi nào | Giá trị |
| --- | --- | --- |
| `X-Trace-Id` | Mọi response, kể cả lỗi và SSE (FR-10.6) | Trace id của request (32 hex). Có ngay cả khi tracing tắt (`MANAGEMENT_TRACING_EXPORT_ENABLED=false`): khi đó filter tự sinh id ngẫu nhiên và đưa vào MDC |
| `X-Data-As-Of` | Response dữ liệu (nhóm vận tải, insight, cảnh báo, freshness) | Thời điểm (ISO-8601 UTC, event time) của dữ liệu mới nhất mà response phản ánh; quy tắc ở §7.3. Không có ở endpoint vận hành ETL và response ghi |
| `Cache-Control` | Mọi response | §10.3 |
| `Location` | `201`/`202` tạo tài nguyên | URL tuyệt đối-đường dẫn (`/api/v1/etl/replays/{id}`) |
| `Retry-After` | `429`, `503` | Số giây |
| `X-RateLimit-Limit`, `X-RateLimit-Remaining` | Request chịu rate limit (§11) | |
| `X-Accel-Buffering: no` | `/stream` | Để nginx không đệm SSE (DOC-26) |
| `Vary: Authorization` | Endpoint trả khác nhau theo role | Cho cache trung gian |

### 7.3 Quy tắc `X-Data-As-Of`

Giá trị lấy từ **freshness probe** (DOC-32 E-60, chạy mỗi 15 giây), không truy vấn thêm cho mỗi request. Vì vậy nó có thể cũ hơn thực tế tối đa 15 giây, và luôn cùng nghĩa với stale banner.

| Endpoint dựa trên | `X-Data-As-Of` |
| --- | --- |
| Vị trí xe (`/vehicles/live`, bunching) | `lastEventAt` của `GTFS_RT_VEHICLE_POSITION` |
| TripUpdate (`/routes/{id}/delays`, disruption, arrivals khi bật realtime) | `lastEventAt` của `GTFS_RT_TRIP_UPDATE` |
| Bảng ETA (`/stops/{id}/arrivals` khi tắt realtime, `/routes/{id}/delay-profile`) | `max(computed_at)` của `insight_eta_prediction` (probe đọc mỗi 5 phút, DOC-32 E-60) |
| OTP | `max(computed_at)` của `insight_otp_scorecard` |
| Vé (`/insights/ticketing-anomalies`) | `lastEventAt` của `TICKETING_SALES` |
| Dữ liệu tĩnh GTFS (`/routes`, `/stops`, `/routes/{id}`) | `activatedAt` của feed ACTIVE |
| `/alerts` | `max(created_at)` của `alert_event` trong kết quả (giờ thật; không có dòng thì bỏ header) |

Nguồn chưa từng có dữ liệu thì bỏ header.

## 8. Idempotency

`POST` tạo yêu cầu bất đồng bộ nhận `Idempotency-Key`: `POST /etl/replays`, `/etl/dlq/{id}/replay`, `/etl/dlq/{id}/confirm`, `POST /etl/jobs`, `/etl/jobs/{runId}/restart`, `/etl/jobs/{runId}/stop`. Khóa được lưu ở cột `idempotency_key` của `replay_request`/`job_request`, UNIQUE cùng `requested_by` (DOC-15).

| Tình huống | Kết quả |
| --- | --- |
| Không có header | Xử lý bình thường; mỗi lần gửi là một yêu cầu mới (hoặc 409 do ràng buộc nghiệp vụ) |
| Khóa mới | Tạo yêu cầu, lưu khóa |
| Khóa đã dùng, **cùng** nội dung (so sánh các trường nghiệp vụ của dòng đã lưu: `kind`, `source`, `from_ts`, `to_ts`, `dead_letter_id`, `recompute_analytics` hoặc `kind`, `job_name`, `job_parameters`, `target_job_execution_id`) | Trả **lại response cũ**: `202` với trạng thái hiện tại của yêu cầu cũ. Không ghi gì thêm, không ghi `dlq_action_log` |
| Khóa đã dùng, **khác** nội dung | 422 `idempotency-key-reused` |
| Hai request cùng khóa tới đồng thời | Request thua vi phạm UNIQUE → bắt lỗi, đọc lại dòng thắng, áp dụng hai dòng trên |

Các thao tác còn lại tự idempotent theo ngữ nghĩa nên không cần khóa: `PUT` (payload, cờ), `POST /alerts/{id}/ack` (đã ack thì trả 200 với bản ghi hiện tại), feedback gợi ý điều phối (ghi đè giá trị), `discard`/`resolve` (đã ở trạng thái đích với cùng người thực hiện thì trả 200).

## 9. Lỗi và kiểm tra đầu vào

- Mọi lỗi theo DOC-30 §3 (Problem Details, slug `urn:pti:problem:<slug>`, `traceId`, `errors`).
- Kiểm tra theo thứ tự: xác thực (401) → phân quyền (403) → rate limit (429) → cú pháp và Bean Validation (400) → tồn tại (404) → trạng thái nghiệp vụ (409) → quy tắc nghiệp vụ (422). Nhờ vậy người không có quyền không dò được id nào tồn tại.
- Tham số query không khai báo bị **từ chối** (400, `errors[].field` = tên tham số), bắt bằng một `HandlerInterceptor` so với danh sách tham số của handler. Tránh trường hợp client gõ sai `routeID` mà vẫn nhận dữ liệu không lọc.
- Id sai định dạng (UUID hỏng, `runId` không khớp mẫu) → 404 `not-found`, không phải 400: với client, id không hợp lệ và id không tồn tại là như nhau.

## 10. Truy cập dữ liệu

### 10.1 Hai datasource (DR-20)

| Datasource | Role DB | Đích | Pool (DOC-10 §4) | Dùng cho |
| --- | --- | --- | --- | --- |
| `reader` (`@Primary`) | `api_reader` | Replica (k3d), primary (compose) | 10 | Mọi `GET` |
| `operator` | `replay_operator` | Primary | 4 | Mọi request ghi, và **đọc lại** bản ghi vừa ghi để trả về (tránh độ trễ replica) |

- Code truy cập bằng `JdbcClient` với SQL đặt trong `src/main/resources/sql/<nhóm>/<tên>.sql`, nạp một lần lúc khởi động. Không dùng JPA.
- Mỗi repository được gắn với đúng một datasource qua constructor (`@Qualifier("reader")`/`@Qualifier("operator")`); không có routing datasource động. Test kiến trúc (ArchUnit) chặn repository ghi dùng `reader`.
- Theo Clean Architecture (DOC-49, ADR-0032): repository là hiện thực của port, nằm ở `<feature>.adapter.out.jdbc`; controller ở `<feature>.adapter.in.web` chỉ map DTO và gọi use case ở `<feature>.application`, kể cả với endpoint chỉ đọc. Security, Problem Details, rate limit, cache và hai datasource thuộc feature `platform`. Bảng feature của `api` ở DOC-49 §11.2.
- Transaction: `GET` chạy `readOnly = true` trên `reader`; request ghi chạy một transaction `READ COMMITTED` trên `operator`. Không có transaction nào trải trên hai datasource. Use case mở transaction qua port `TransactionRunner`; `config` truyền bean `readerTx` hoặc `operatorTx` tương ứng (DOC-49 §5.1), nên không có `@Transactional` trong `application`.
- `statement_timeout`: đặt ở mức role trong bootstrap (DOC-17 §3): `ALTER ROLE api_reader SET statement_timeout = '5s'`, `ALTER ROLE replay_operator SET statement_timeout = '5s'`. Cách này đúng cả khi đi qua PgBouncer transaction mode (k3d), nơi tham số khởi động của JDBC bị bỏ. Hết thời gian → SQLState `57014` → `TRANSIENT_INFRA` → 503 `service-unavailable` (DOC-30 §2.3).
- Hikari: `connection-timeout` 2 s (hết → 503), `maximum-pool-size` như bảng, `minimum-idle` 2.

### 10.2 Đọc feed ACTIVE

Các truy vấn GTFS dùng view `*_current` hoặc tham số `:feedVersionId` lấy từ cache `active-feed` (§10.3, TTL 30 giây). Khi feed đổi (DOC-21 activate), trong tối đa 30 giây API có thể trả dữ liệu của feed cũ; mọi dữ liệu trong một response đến từ cùng một `feedVersionId` vì nó được đọc một lần ở đầu request.

Chưa có feed ACTIVE (hệ thống mới khởi động) → endpoint nhóm vận tải trả 503 `service-unavailable` với `detail` `No active GTFS feed yet.`, `Retry-After: 30`.

### 10.3 Cache

Caffeine trong bộ nhớ của từng pod (không dùng cache phân tán, DR-103; mỗi pod tự hết hạn). Tên cache là tên metric `cache` (DOC-28).

| Cache | Khóa | TTL | Kích thước tối đa | `Cache-Control` của response |
| --- | --- | --- | --- | --- |
| `active-feed` | — | 30 s | 1 | — |
| `routes` | `feedVersionId` | 10 phút | 4 | `public, max-age=60` |
| `route-detail` | `feedVersionId`, `routeId` | 10 phút | 300 | `public, max-age=300` |
| `stop-detail` | `feedVersionId`, `stopId` (phần tĩnh) | 10 phút | 10.000 | `no-cache` (có phần cảnh báo động) |
| `stops-search` | bộ tham số chuẩn hóa | 60 s | 2.000 | `public, max-age=60` |
| `stop-routes` | `feedVersionId` (bản đồ `stopId → routeIds`) | Theo feed | 2 | — |
| `vehicles-live` | tập `routeId` đã sắp (rỗng = tất cả) | 2 s | 256 | `no-cache` |
| `arrivals` | `stopId`, `limit`, `horizon` | 5 s | 5.000 | `no-cache` |
| `route-delays` | toàn bộ tham số | 60 s | 1.000 | `private, max-age=60` |
| `delay-profile` | `routeId`, `directionId` | 10 phút | 300 | `public, max-age=300` |
| `otp` | toàn bộ tham số | 5 phút | 200 | `private, max-age=60` |
| `public-disruptions` | bộ tham số (chỉ góc nhìn anonymous) | 5 s | 1.000 | `no-cache` |
| `freshness` | — | Do probe làm mới mỗi 15 s | 1 | `no-cache` |

- Endpoint có phần dữ liệu phụ thuộc role (overlay bunching của `/vehicles/live`, trường enrichment của disruption) chỉ cache **phần chung**; phần theo role được thêm sau khi lấy từ cache.
- Endpoint vận hành ETL, insight nội bộ và mọi response khi có `Authorization` mà không nằm trong bảng: `Cache-Control: no-store`.
- Cache GTFS bị xóa toàn bộ khi `active-feed` đổi `feedVersionId` (so sánh ở lần làm mới 30 giây).
- **Nạp single-flight:** mọi cache nạp qua `Cache.get(key, loader)` của Caffeine (hoặc `@Cacheable(sync = true)`), để nhiều request cùng miss một key trong một pod chỉ sinh một truy vấn. Quan trọng nhất với cache TTL ngắn bị gọi dồn: `vehicles-live`, `arrivals`, `public-disruptions`. Giữa các pod không gộp: với N pod, mỗi key bị truy vấn tối đa N lần mỗi TTL (ADR-0031).
- `Cache-Control: public` chỉ đặt cho response giống nhau với mọi người gọi; nginx của frontend **không** bật `proxy_cache` (DOC-39), header chỉ phục vụ cache của trình duyệt.

## 11. Rate limit (DR-45)

Bucket4j (`bucket4j_jdk17-core`) trong bộ nhớ, token bucket nạp đều (`refillGreedy`), bucket lưu trong Caffeine (hết hạn sau 10 phút không dùng, tối đa 100.000 bucket).

| Bucket | Áp cho | Giới hạn | Khóa |
| --- | --- | --- | --- |
| `public` | Request REST **không** có token | 60 request / phút (FR-10.5) | IP client |
| `authenticated` | Request REST có token hợp lệ | 600 request / phút | `sub` của JWT |
| `write` | `POST`/`PUT` (cộng thêm vào bucket trên) | 30 request / phút | `sub` |
| `sse-anonymous` | Kết nối `/stream` đồng thời, không token | 5 (DR-45) | IP client |
| `sse-user` | Kết nối `/stream` đồng thời, có token | 10 | `sub` |

- Không áp cho `/internal/**` (đã có token webhook) và cổng quản trị.
- Vượt → 429 `rate-limited`, `Retry-After` = số giây tới khi có token, thành phần `retryAfterSeconds`. Metric `pti_api_rate_limited_total{bucket}`.
- **IP client:** nginx của frontend proxy `/api/` và đặt `X-Forwarded-For`. `api` bật `server.forward-headers-strategy=native` và `server.tomcat.remoteip.internal-proxies` = dải mạng riêng (`10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|192\.168\.\d+\.\d+|127\.0\.0\.1`). Chỉ header do proxy tin cậy đặt mới được dùng; client gửi thẳng tới cổng 8081 không giả được IP.
- **Hạn chế đã biết:** giới hạn tính theo từng pod. Với N pod sau load balancer, giới hạn thực tế tối đa gấp N lần (k3d chạy 2 pod). Chấp nhận được vì mục đích là chặn lạm dụng thô, không phải hạn ngạch chính xác (DR-45). Trên compose mọi trình duyệt cùng máy có chung IP `127.0.0.1`/gateway: đủ cho demo một người; runner thực nghiệm dùng token (bucket `authenticated`) cho các kết nối cần nhiều hơn (DOC-45).
- **Kiểm ở P5 (DR-103):** trên compose, mọi tab anonymous trên cùng máy chia chung bucket `public` (60 request/phút) và `sse-anonymous` (5 kết nối). Ngoài lượt tải trang, `refetchInterval` 60 giây và `refetchOnWindowFocus`, khi SSE hỏng màn Live map còn poll xe mỗi 5 giây (12 request/phút, DOC-36 `live-map.md`, DOC-34 §9.2). Đo số request mỗi phút của SPA anonymous ở các màn công khai, cả khi SSE chạy và khi đang polling, với 1 và 3 tab. Nếu vượt thì nâng `pti.api.rate-limit.public-per-minute` và `.sse-per-ip` ở cấu hình compose, không đổi mặc định cho k3d.
- Rate limit chung cho cả cụm không nằm trong phạm vi; ADR-0031 ghi khi nào xem lại và phương án thử trước (`bucket4j-postgresql`).

## 12. Phiên bản và OpenAPI

- Phiên bản nằm trong path (`/api/v1`). Thay đổi **tương thích** được làm tại chỗ: thêm endpoint, thêm trường response không bắt buộc, thêm tham số query tùy chọn, thêm giá trị enum ở trường response mà frontend đã có nhánh mặc định. Thay đổi **phá vỡ** (xóa hoặc đổi tên trường, đổi kiểu, thêm tham số bắt buộc, siết validation, xóa giá trị enum) cần `/api/v2` cho endpoint đó.
- `backend/api/openapi.json` được commit vào repo. `OpenApiSnapshotTest` (unit test của `api`, chạy trong `./gradlew build`) khởi động app với springdoc bật, so tài liệu được phục vụ (khóa đã sắp xếp) với file; `./gradlew :api:updateOpenApi` ghi lại file. Job `openapi-diff` của PR so file với nhánh đích và chặn thay đổi phá vỡ, trừ khi PR có nhãn `breaking-api` (DR-44, DOC-41). Frontend sinh type bằng `openapi-typescript` (P5-02).
- Mọi endpoint có `@Operation(summary)` tiếng Anh, `@ApiResponse` cho từng mã lỗi ở DOC-32, và ít nhất một ví dụ response (FR-10.1). Ví dụ lấy từ DOC-32.
- Phần còn lại của tài liệu do `OpenApiConfiguration` suy ra, không viết bằng annotation: schema của response thành công lấy từ kiểu trả về của handler (bỏ lớp `ResponseEntity`), media type `application/json`; mọi response 4xx và 5xx là `Problem` dạng `application/problem+json`; thành phần record không có `@Nullable` (jspecify) là `required`, vì Jackson ghi `non_null` nên trường nullable vắng mặt khi rỗng (`RequiredRecordComponents`); `Caller` không phải tham số; `JsonNode` là object tự do. Nhờ vậy type sinh ở frontend phân biệt được trường luôn có và trường có thể vắng, và mọi ví dụ được `tsc` kiểm theo schema (`frontend/scripts/gen-api-examples.mjs`).
- Swagger UI (`/swagger-ui.html`) và `/v3/api-docs` chỉ bật ở profile `dev`; ở profile khác `springdoc.api-docs.enabled=false`.

## 13. Log và trace của request

- Mỗi request một dòng log `INFO` khi kết thúc (`http request completed`) với `method`, `uri` (template, không phải path thật), `status`, `durationMs`, `user` (`sub` hoặc `anonymous`), `clientIp`. Query string không được log (có thể chứa `q` do người dùng gõ).
- Request ghi thành công có thêm dòng `INFO` nghiệp vụ (`replay requested`, `runtime flag changed`, `dead letter discarded`…) với `actor` = `user:<preferred_username>` (DOC-27 §3.3).
- Span của Spring MVC observation; trace id đưa vào MDC `trace_id` và header `X-Trace-Id`.

## 14. Cấu hình

| Khóa | Kiểu | Mặc định | Ý nghĩa |
| --- | --- | --- | --- |
| `pti.api.paging.default-limit` / `.max-limit` | int | `50` / `500` | §5 |
| `pti.api.time.max-range` / `.ops-max-range` | Duration | `31d` / `24h` | §4.3 |
| `pti.api.cache.<name>.ttl` / `.max-size` | Duration / int | Bảng §10.3 | Chỉnh riêng từng cache |
| `pti.api.rate-limit.enabled` | boolean | `true` | Tắt trong test hiệu năng nội bộ |
| `pti.api.rate-limit.public-per-minute` / `.authenticated-per-minute` / `.write-per-minute` | int | `60` / `600` / `30` | §11 |
| `pti.api.rate-limit.sse-per-ip` / `.sse-per-user` | int | `5` / `10` | §11 |
| `pti.api.vehicles.max-items` | int | `1500` | §5.2 |
| `pti.api.vehicles.max-age` | Duration | `5m` | Xe cũ hơn thì không trả (DOC-32 E-05) |
| `pti.api.max-body-size` | DataSize | `1MB` | §3 |
| `server.forward-headers-strategy`, `server.tomcat.remoteip.internal-proxies` | — | §11 | |
| `spring.threads.virtual.enabled` | boolean | `true` | Mỗi request một virtual thread (Java 25); SSE cũng dùng (DOC-26) |

## 15. Metrics

Bổ sung vào DOC-28 §3.5 (đã có `pti_api_problems_total`, `pti_api_rate_limited_total`, `cache_*`):

| Metric | Loại | Label | Ý nghĩa |
| --- | --- | --- | --- |
| `http_server_requests_seconds` | timer | `method`, `uri`, `status`, `outcome` | Có sẵn. Histogram bật cho `uri` thuộc nhóm đọc để đo NFR-10 |
| `pti_api_write_requests_total` | counter | `operation` (`replay_raw`, `replay_dlq`, `confirm`, `discard`, `resolve`, `edit_payload`, `job_run`, `job_restart`, `job_stop`, `flag`, `ack`, `feedback`), `outcome` (`created`, `idempotent`, `rejected`) | Thao tác ghi của người vận hành |
| `pti_api_db_query_seconds` | timer | `datasource`, `query` (tên file SQL) | Đo trong `JdbcClient` wrapper |

## 16. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| AG-01 | `GET /routes` và một lỗi 404 bất kỳ | Cả hai có `X-Trace-Id` 32 hex; response dữ liệu có `X-Data-As-Of` |
| AG-02 | Trang keyset: 120 dòng, `limit=50` | 3 trang 50/50/20; trang 3 không có `nextCursor`; không trùng, không sót (so với truy vấn không phân trang) |
| AG-03 | Dùng `nextCursor` của `status=NEW` cho request `status=MANUAL` | 400 `validation-error`, field `cursor` |
| AG-04 | `limit=501`, `limit=0` | 400 |
| AG-05 | `from=2026-09-29T10:00:00` (không offset) | 400, field `from` |
| AG-06 | `from=-60m` trên endpoint event time, `PTI_CLOCK_OFFSET=-12h` | `from` được hiểu là `businessNow − 60 phút` |
| AG-07 | Khoảng 32 ngày; `/etl/jobs` khoảng 25 giờ | 400 |
| AG-08 | Tham số lạ `routeID=18` | 400, field `routeID` |
| AG-09 | `POST /etl/replays` hai lần cùng `Idempotency-Key`, cùng body | Lần 2: 202, cùng `id`, DB có một dòng |
| AG-10 | Như trên, khác `toTs` | 422 `idempotency-key-reused` |
| AG-11 | Hai request cùng khóa gửi đồng thời (CountDownLatch) | Một dòng; cả hai nhận cùng `id` |
| AG-12 | 61 request anonymous trong 1 phút từ một IP (qua `X-Forwarded-For` từ proxy tin cậy) | Request 61 → 429, `Retry-After` > 0 |
| AG-13 | `X-Forwarded-For` giả, gửi thẳng từ IP không nằm trong `internal-proxies` | Header bị bỏ qua, bucket theo IP thật |
| AG-14 | Truy vấn chạy quá 5 s (`pg_sleep` trong test SQL) | 503 `service-unavailable`, `Retry-After` |
| AG-15 | Repository ghi dùng datasource `reader` | ArchUnit test fail |
| AG-16 | Response có trường null (ví dụ `episodeEnd` của episode mở) | Trường không có trong JSON |
| AG-17 | Body có trường lạ | 400 `validation-error` |
| AG-18 | Feed đổi (`feedVersionId` mới) | Sau ≤ 30 s `/routes` trả dữ liệu feed mới; cache `routes` và `route-detail` của feed cũ bị xóa |
| AG-19 | Chưa có feed ACTIVE | Nhóm vận tải trả 503, `Retry-After: 30` |
| AG-20 | `openapi.json` sinh ra khác bản đã commit | Build fail với thông báo chạy `./gradlew :api:updateOpenApi` |

## 17. Câu hỏi còn mở

Không có.
