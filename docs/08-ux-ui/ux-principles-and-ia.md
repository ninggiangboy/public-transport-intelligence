# Nguyên tắc UX và kiến trúc thông tin

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-34
> Phụ thuộc: DOC-02, DOC-03 (FR-11, NFR-11, NFR-12), DOC-04, DOC-26 §8–9, DOC-27 §3, DOC-31, DOC-32, DOC-33, ADR-0020, ADR-0021
> Người dùng chính: P5-01…P5-15; người viết DOC-35, DOC-36, DOC-37

## 1. Mục đích và phạm vi

Tài liệu này chốt những gì mọi màn hình dùng chung:

- nguyên tắc UX;
- sitemap và điều hướng theo role;
- sơ đồ URL, gồm search params cho bộ lọc;
- hành vi responsive và ngân sách hiệu năng;
- kiến trúc mã frontend: thư mục, provider, đăng nhập, client API, cấu hình lúc chạy.

Màu sắc, component và style bản đồ ở DOC-35. Từng màn hình ở `screens/*` (DOC-36). Mẫu trạng thái và toàn bộ microcopy ở DOC-37. Chuỗi UI trong tài liệu này được trích nguyên văn tiếng Anh (DR-48, DR-61).

## 2. Nguyên tắc UX

| ID | Nguyên tắc | Hệ quả cụ thể |
| --- | --- | --- |
| P-1 | **Dữ liệu luôn ghi rõ "tính đến lúc nào".** | Mọi panel dữ liệu có `FreshnessIndicator` (DOC-35 §5) lấy từ header `X-Data-As-Of` (DOC-31 §7.3) hoặc trường thời điểm trong payload. Dữ liệu realtime hiện thời gian tương đối ("Updated 5 s ago"), dữ liệu tổng hợp hiện thời điểm tuyệt đối ("As of Sep 29, 4:05 PM CDT"). |
| P-2 | **Độ tin cậy luôn hiển thị.** | Mọi giá trị dự đoán hoặc do AI sinh ra (ETA, `likelyCause`, `category`, gợi ý điều phối) đi kèm `ConfidenceChip`. Dưới ngưỡng thì ghi rõ "Low confidence" (FR-09.6), không bao giờ ẩn giá trị hay ẩn mức tin cậy. Chưa phân loại thì ghi "Unclassified" (FR-09.7). |
| P-3 | **Không màn hình trắng.** | Mọi query giữ dữ liệu cũ khi refetch lỗi và hiện `ErrorState` dạng dải phía trên dữ liệu. Chỉ khi chưa từng có dữ liệu mới hiện `ErrorState` dạng khối. Tắt API → mọi màn hình vẫn có nội dung giải thích (FR-11.6, DOC-37 §2). |
| P-4 | **URL là nguồn sự thật của bộ lọc và lựa chọn.** | Bộ lọc, tab, khoảng thời gian và bản ghi đang mở (drawer) nằm trong search params (§5). Copy URL gửi người khác thì họ thấy đúng màn hình đó (J-3 bước 2). State cục bộ (Zustand) chỉ dùng cho thứ không cần chia sẻ. |
| P-5 | **Realtime không làm người dùng mất chỗ.** | Dòng mới chèn vào đầu danh sách có hiệu ứng highlight 2 giây, không đổi vị trí cuộn. Khi người dùng đã cuộn xuống hoặc đang mở drawer, dòng mới gom thành nút "N new — show" thay vì chèn ngay. Bản đồ không tự di chuyển camera trừ khi người dùng bấm "Follow". |
| P-6 | **Hành động có phản hồi trong 300 ms.** | Thao tác ghi an toàn (feedback, ack, replay DLQ) cập nhật lạc quan; lỗi thì hoàn tác và hiện toast có `traceId`. Thao tác chờ lâu hiện tiến độ (replay raw zone, job request). |
| P-7 | **Thao tác khó đảo ngược phải xác nhận.** | Discard DLQ, stop job, bật cờ pause, replay khoảng thời gian, dừng mọi kịch bản demo đều qua `ConfirmDialog` nêu rõ hậu quả. Thao tác đảo ngược được (ack, feedback) không hỏi lại. |
| P-8 | **Không dùng màu làm tín hiệu duy nhất.** | Severity, trạng thái và mức tin cậy luôn có chữ hoặc icon cùng màu (DOC-35 §8, WCAG 1.4.1). |
| P-9 | **Hai trục thời gian không lẫn nhau.** | Thời điểm sự kiện (vị trí xe, episode, ETA) tính tương đối theo `businessNow` của server (DR-67). Thời điểm audit (ack, replay, job) tính theo đồng hồ máy người dùng. Xem §8. |
| P-10 | **Ops console ưu tiên mật độ thông tin.** | Bảng dòng 32 px, id hiển thị monospace rút gọn kèm nút copy, số canh phải với `tabular-nums`. Màn hành khách ưu tiên chữ lớn và vùng chạm 44 px. |

## 3. Người dùng và quyền trên UI

| Role | Persona (DOC-02) | Thấy | Làm được |
| --- | --- | --- | --- |
| anonymous | PS-1 | Map, Stops, Alerts (chỉ `PUBLIC`) | Xem |
| `viewer` | PS-3 | Thêm Scorecard, Ops console (Jobs, DLQ, Replay, Controls, Ticketing), overlay bunching và gợi ý trên map, alert mọi audience | Xem |
| `operator` | PS-2, PS-4, PS-5 | Như viewer; thêm Demo khi server bật profile `demo` | Mọi thao tác ghi của DOC-32 |

Quy tắc:

- Role lấy từ `GET /me` (E-61), không tự đọc claim trong token. `/me` là query `['me']`, refetch khi người dùng đổi (đăng nhập, đăng xuất, token mới).
- Viewer **không thấy** nút ghi (ẩn, không phải disable). Header Ops console của viewer có badge "Read-only" giải thích "You can view everything here. Actions require the operator role."
- Ẩn nút chỉ là tiện dụng; server luôn kiểm quyền (DOC-27 §4). Server trả 403 thì UI hiện trạng thái "không có quyền" của DOC-37 §2.6.
- Dữ liệu thay đổi theo role: anonymous nhận projection công khai (E-12, E-20, sự kiện SSE). Component không được giả định trường nào đó luôn có; type sinh từ OpenAPI đánh dấu các trường này là optional.

## 4. Sitemap và điều hướng

```mermaid
flowchart TD
  root["/ → /map"]
  root --> map["/map · Live map"]
  root --> stops["/stops · Find a stop"]
  stops --> stop["/stops/$stopId · Stop detail"]
  root --> alerts["/alerts · Alerts"]
  root --> sc["/scorecard · Route scorecard (viewer)"]
  sc --> scr["/scorecard/$routeId · Route detail (viewer)"]
  root --> ops["/ops → /ops/jobs (viewer)"]
  ops --> jobs["/ops/jobs · Jobs"]
  jobs --> batch["/ops/batches/$batchId · Batch lineage"]
  ops --> dlq["/ops/dlq · Dead letters"]
  ops --> rep["/ops/replay · Replay"]
  ops --> ctl["/ops/controls · Controls"]
  ops --> tix["/ops/ticketing · Ticketing"]
  ops --> demo["/ops/demo · Demo control (operator, profile demo)"]
```

### 4.1 Điều hướng chính

| Mục menu (nhãn UI) | Đích | anonymous | viewer | operator |
| --- | --- | --- | --- | --- |
| "Map" | `/map` | ✓ | ✓ | ✓ |
| "Stops" | `/stops` | ✓ | ✓ | ✓ |
| "Alerts" (kèm số alert chưa ack, chỉ khi đăng nhập) | `/alerts` | ✓ | ✓ | ✓ |
| "Scorecard" | `/scorecard` | — | ✓ | ✓ |
| "Ops" | `/ops/jobs` | — | ✓ | ✓ |
| "Sign in" / menu người dùng | OIDC | ✓ | — | — |

Menu con của Ops (thanh bên trái, ≥ 1280 px): "Jobs", "Dead letters" (kèm số bản ghi mở từ E-41), "Replay", "Controls" (kèm chấm cảnh báo khi có cờ pause đang bật), "Ticketing", "Demo" (chỉ operator và khi `demoControl`, §10).

Anonymous mở trực tiếp URL cần quyền (ví dụ `/ops/dlq` từ email alert) → trang hiện trạng thái "Sign in to view this page" với nút "Sign in"; sau khi đăng nhập quay lại đúng URL cũ (kể cả search params). Không tự chuyển sang Keycloak mà không hỏi.

### 4.2 Bố cục khung

- **≥ 768 px:** thanh trên cùng cao 56 px: logo "PTI" + tên "Public Transport Intelligence" (ẩn chữ dưới 1024 px), menu chính, `RealtimeStatusDot`, nút đổi theme, menu người dùng. Ngay dưới là vùng banner toàn cục (`StaleBanner`, banner offline).
- **< 768 px:** thanh trên cao 48 px chỉ có logo, `RealtimeStatusDot` và menu người dùng; điều hướng chính chuyển xuống **thanh tab dưới** (Map, Stops, Alerts, More). "More" mở sheet chứa Scorecard, Ops, theme, Sign in/out.
- Ops console: thanh bên trái rộng 200 px (thu gọn còn 56 px chỉ icon, lưu lựa chọn trong `localStorage` `pti.opsNav.collapsed`).
- Chi tiết một bản ghi mở trong **drawer bên phải** (rộng 560 px, 720 px cho DLQ) và ghi id vào URL. Ngoại lệ là trang có nhiều nội dung hoặc cần link riêng cho người ngoài ops: stop detail, route detail, batch lineage là trang đầy đủ.

### 4.3 Phím tắt (ops console)

| Phím | Hành động |
| --- | --- |
| `/` | Đặt focus vào ô tìm kiếm hoặc bộ lọc đầu tiên của trang |
| `j` / `k` | Chọn dòng kế tiếp / trước trong bảng đang focus |
| `Enter` | Mở drawer của dòng đang chọn |
| `Esc` | Đóng drawer hoặc dialog đang mở |

Phím tắt không hoạt động khi focus đang ở ô nhập liệu hoặc trình sửa JSON. Có danh sách phím tắt trong menu người dùng ("Keyboard shortcuts").

## 5. Sơ đồ URL

### 5.1 Quy ước search params

- Mỗi route khai báo schema search bằng zod (`validateSearch`). Giá trị sai kiểu hoặc ngoài miền bị **bỏ qua và thay bằng mặc định**, không báo lỗi (link cũ vẫn mở được); URL được viết lại bằng `replace`.
- Tham số bằng giá trị mặc định **không** ghi lên URL.
- Danh sách: phân tách bằng dấu phẩy (`status=NEW,MANUAL`). Router dùng `parseSearch`/`stringifySearch` tự viết trong `src/lib/url.ts` thay cho JSON mặc định của TanStack Router, để URL đọc được và khớp ví dụ trong DOC-04.
- Thời điểm: ISO-8601 UTC rút gọn tới phút (`2026-09-29T20:00Z`). Khoảng tương đối dùng `window` (`15m`, `1h`, `6h`, `24h`, `7d`, `31d` tùy trang). Có `from`/`to` thì bỏ qua `window`.
- Đổi bộ lọc dùng `navigate({ search, replace: true })` để nút Back không phải đi qua từng lần gõ; đổi tab và mở drawer dùng `push`.

### 5.2 Bảng route

| Route | Màn hình (DOC-36) | Quyền | Search params (kiểu · mặc định) |
| --- | --- | --- | --- |
| `/` | — | mọi người | Chuyển tới `/map` (`replace`) |
| `/map` | `live-map.md` | mọi người | `route` (list routeId, ≤ 20 · tất cả) · `vehicle` (vehicleId đang chọn) · `bunching` (episode id, viewer) · `disruption` (episode id) · `c` (`lon,lat,zoom`, 4 chữ số thập phân · vùng của feed) · `view` (`map` \| `list` · `map`) |
| `/stops` | `stop-detail.md` §Tìm trạm | mọi người | `q` (2–100 ký tự) |
| `/stops/$stopId` | `stop-detail.md` | mọi người | — |
| `/alerts` | `alert-feed.md` | mọi người | `state` (`open` \| `unacknowledged` \| `all` · `open`) · `audience` (list) · `type` (list) · `severity` (list 0–2) · `route` (list) · `window` (`24h` \| `7d` · `24h`) · `alert` (id, mở drawer) |
| `/scorecard` | `route-scorecard.md` | viewer | `from`, `to` (date · 7 ngày tới hôm qua) · `routeType` (list) · `sort` (`otp` \| `route` · `otp`) |
| `/scorecard/$routeId` | `route-scorecard.md` §Chi tiết tuyến | viewer | `tab` (`delays` \| `profile` \| `disruptions` · `delays`) · `from`, `to` (date · 7 ngày tới hôm qua) · `dir` (`0` \| `1` · cả hai; tab `profile` mặc định `0`) · `bucket` (`hour-of-week` \| `hour` \| `day` · `hour-of-week`) · `dow`, `hour` (tab `profile` · thứ và giờ hiện tại theo `businessNow`) · `disruption` (id, mở drawer ở tab `disruptions`) |
| `/ops` | — | viewer | Chuyển tới `/ops/jobs` |
| `/ops/jobs` | `ops-console-jobs.md` | viewer | `window` (`15m` \| `1h` \| `6h` \| `24h` · `1h`) · `from`, `to` · `kind` (`BATCH_JOB` \| `STREAM`) · `status` (list) · `name` (list) · `bucket` (`1m` \| `5m` \| `15m` \| `1h` · theo `window`) · `run` (runId, mở drawer) |
| `/ops/batches/$batchId` | `ops-console-jobs.md` §Batch lineage | viewer | — |
| `/ops/dlq` | `ops-console-dlq.md` | viewer | `tab` (`records` \| `confirm` \| `actions` · `records`) · `status`, `source`, `stage`, `category`, `severity`, `rule` (list) · `from`, `to` · `id` (dead letter, mở drawer) · tab `actions`: `action` (list), `actor` (`auto` \| `system` \| `user`), `window` (`24h` \| `7d` · `24h`) |
| `/ops/replay` | `ops-console-replay.md` | viewer | `tab` (`new` \| `history` · `new` với operator, `history` với viewer) · `kind`, `status` (lọc lịch sử) · `replay` (id, mở drawer) · form tab `new` có thể điền sẵn: `source`, `from`, `to`, `recompute` |
| `/ops/controls` | `ops-console-controls.md` | viewer | — |
| `/ops/ticketing` | `ops-console-ticketing.md` | viewer | `window` (`24h` \| `7d` · `24h`) · `from`, `to` · `category` (list, gồm `unclassified`) · `severity` (list) · `trigger` · `salePoint` · `anomaly` (id, mở drawer) |
| `/ops/demo` | `demo-control.md` | operator, `demoControl` | `run` (runId, mở drawer) |
| `/auth/callback` | `shell-and-navigation.md` | — | Tham số OIDC (`code`, `state`) |
| `/_ui` | Danh mục component (DOC-35 §10) | chỉ bản build dev | — |
| mọi URL khác | Trang 404 ("Page not found") | mọi người | — |

`/auth/silent.html` là file tĩnh trong `public/` (không phải route của SPA), dùng cho `signinSilent` trong iframe (§9.3).

### 5.3 Liên kết sâu từ alert (`link` của E-20)

API dựng `link` theo `type` (quy tắc chính thức ở DOC-32 E-20); UI chỉ mở link này, không tự dựng lại:

| `type` | `link` |
| --- | --- |
| `DISRUPTION` | `/map?route=<routeId>&disruption=<refId>` |
| `BUNCHING` | `/map?route=<routeId>&bunching=<refId>` |
| `TICKETING_ANOMALY` | `/ops/ticketing?anomaly=<refId>` |
| `DLQ_SEVERE` | `/ops/dlq?severity=2&status=NEW,MANUAL,PENDING_CONFIRM` |
| `FEED_STALE` | `/ops/jobs?kind=STREAM` |
| `INFRA` | `body.annotations.runbook_url` nếu có (link ngoài, mở tab mới), ngược lại `/ops/jobs` |

## 6. Responsive

| Nhóm | Màn hình | Thiết kế cho | Hành vi |
| --- | --- | --- | --- |
| Hành khách | Map, Stops, Stop detail, Alerts | 360–1440 px, ưu tiên mobile | Một cột dưới 768 px. Map: panel thông tin xe và danh sách là bottom sheet kéo được (3 nấc: 96 px, 50%, 90%). Vùng chạm ≥ 44 × 44 px. Không có hover-only interaction. |
| Quản lý | Scorecard, Route detail | ≥ 1024 px | Dưới 1024 px bảng chuyển sang dạng thẻ, heatmap cuộn ngang trong khung của nó. |
| Vận hành | Mọi trang `/ops/*` | ≥ 1280 px | Dưới 1280 px hiện dải thông báo "The ops console is designed for screens at least 1280 px wide." (đóng được, nhớ trong `sessionStorage`); layout vẫn chạy, bảng cuộn ngang trong khung, drawer chiếm toàn màn hình. |

Breakpoint dùng mặc định của Tailwind (`sm` 640, `md` 768, `lg` 1024, `xl` 1280, `2xl` 1536). Không dùng media query riêng ngoài các mốc này.

## 7. Ngân sách hiệu năng

| Chỉ số | Ngưỡng | Đo bằng |
| --- | --- | --- |
| LCP của `/stops/$stopId`, Moto G Power, 4G mô phỏng | < 2,5 s (NFR-12) | Lighthouse CI nightly (DOC-44 §10) |
| JS tải ban đầu (gzip) của `/stops/$stopId` | ≤ 200 KB | `scripts/check-bundle.mjs` đọc `dist/.vite/manifest.json`, chạy trong CI frontend |
| Chunk `/map` (gồm MapLibre, `pmtiles`) | ≤ 330 KB gzip | như trên |
| Chunk ECharts (dùng chung cho scorecard, jobs, ticketing) | ≤ 160 KB gzip | như trên |
| Chunk CodeMirror (chỉ tải khi mở trình sửa payload DLQ) | ≤ 130 KB gzip | như trên |
| Cuộn bảng DLQ 10.000 dòng | Không có long task > 100 ms | Playwright trace (DOC-44 §10) |
| Cập nhật bản đồ 1.200 xe mỗi giây | ≥ 50 fps trên laptop demo | Performance panel, kiểm tay ở P5-06 |
| Phản hồi thao tác ghi (UI đổi trạng thái) | < 300 ms (J-2 bước 4) | Optimistic update |

Kỹ thuật bắt buộc:

- Code-split theo route (`lazy` route của TanStack Router). Màn hành khách không import ECharts, CodeMirror, TanStack Table.
- Font tự host (`@fontsource-variable/inter`, `@fontsource-variable/jetbrains-mono`), subset `latin`, `font-display: swap`, preload Inter.
- Stop detail render được ngay từ E-07 + E-08 mà không đợi `/me` hay SSE.
- Cập nhật vị trí xe gom theo `requestAnimationFrame` và ghi thẳng vào GeoJSON source của MapLibre (`setData`), không re-render React cho từng xe (DOC-35 §6).

## 8. Thời gian, số và múi giờ

Quy tắc chi tiết và bảng định dạng ở DOC-37 §4. Tóm tắt:

- Hiển thị theo **múi giờ của agency** (`activeFeed.timezone` từ E-60, hiện là `America/Chicago`), kèm viết tắt (`CDT`/`CST`). Chưa có feed ACTIVE thì dùng múi giờ trình duyệt và ghi viết tắt của nó.
- **Thời điểm sự kiện** (vị trí xe, bắt đầu/kết thúc episode, ETA, cửa sổ ticketing): thời gian tương đối tính theo `businessNow` (lấy từ `heartbeat` SSE hoặc E-60; giữa hai lần nhận thì cộng thêm thời gian đã trôi theo `performance.now()`). Lý do: đồng hồ nghiệp vụ có thể lệch giờ thật nhiều giờ (DR-67).
- **Thời điểm audit** (`createdAt` của alert, ack, feedback, job, replay, hành động DLQ): thời gian tương đối tính theo đồng hồ máy người dùng; thời điểm tuyệt đối vẫn theo múi giờ agency.
- Khi `clockOffset` của E-60 khác `PT0S`, footer của mọi trang hiện "Simulated clock: <thời điểm businessNow>" để người xem demo không nhầm.
- Locale `en-US` cho mọi định dạng số, ngày và tiền (DR-48). Dùng `Intl.*` với cache formatter, không thêm thư viện ngày tháng; khoảng thời gian dạng ISO (`PT20M`) chuyển đổi bằng hàm tự viết trong `src/lib/time.ts`.

## 9. Kiến trúc frontend

### 9.1 Thư mục

```text
frontend/
  index.html
  vite.config.ts  playwright.config.ts  tsconfig.json  eslint.config.js
  scripts/check-bundle.mjs
  public/
    env.js                      # dev defaults; replaced by the nginx entrypoint at startup (§10)
    auth/silent.html  auth/silent.js
    map/style-light.json  map/style-dark.json  map/fonts/  map/sprites/
  src/
    main.tsx                    # mounts providers and the router
    env.ts                      # reads and validates window.__PTI_ENV__ (zod)
    app/
      providers.tsx             # Theme → Auth → Query → Freshness → Realtime → Router
      shell/                    # AppShell, TopBar, BottomTabs, OpsNav, UserMenu, GlobalBanners
      guards.tsx                # RequireRole, RequireDemo
    routes/                     # TanStack Router file routes (§5.2)
    features/
      map/  stops/  scorecard/  alerts/
      ops-jobs/  ops-dlq/  ops-replay/  ops-controls/  ops-ticketing/  demo/
        # each: components/, hooks/, search.ts (zod schema), queries.ts, *.test.tsx
    api/
      generated/schema.d.ts     # openapi-typescript output (committed)
      client.ts                 # openapi-fetch instance + middleware (auth, problem, as-of)
      problem.ts                # ProblemDetails type, ApiError class
      keys.ts                   # query key factories (DOC-26 §9)
      sim.ts                    # zod schemas for /sim/** (not in the public OpenAPI)
    realtime/
      RealtimeProvider.tsx  useRealtime.ts  config.ts  handlers.ts
      schemas/                  # zod generated from schemas/ui-events (json-schema-to-zod)
    components/
      ui/                       # shadcn/ui primitives
      ...                       # design-system components (DOC-35 §5)
    lib/  time.ts  format.ts  url.ts  roles.ts
    i18n/en.ts                  # every user-visible string (DOC-37)
    styles/tokens.css  styles/globals.css
    test/                       # MSW handlers, fixtures, render helpers
  e2e/                          # Playwright specs, one file per screen (DOC-44 §4.3)
```

Quy tắc phụ thuộc (ESLint `import/no-restricted-paths`): `features/*` không import lẫn nhau; dùng chung thì đưa lên `components/` hoặc `lib/`. `components/` không import `features/` hay `api/`.

### 9.2 Provider

Thứ tự lồng nhau trong `app/providers.tsx`:

1. `ThemeProvider`: `light` \| `dark` \| `system`, lưu `localStorage` `pti.theme` (bọc try/catch; không đọc được thì theo `system`). Gắn class `dark` lên `<html>`.
2. `AuthProvider` (`react-oidc-context`), cấu hình ở §9.3. Không có `keycloakUrl` trong `env.js` thì bỏ qua provider này và mọi người là anonymous.
3. `QueryClientProvider`: mặc định `staleTime` 10 s, `gcTime` 5 phút, `refetchInterval` 60 s khi trang hiển thị (DR-42), `refetchOnWindowFocus: true`, `retry`: tối đa 2 lần với lỗi mạng và 5xx, không retry 4xx; `placeholderData: keepPreviousData` cho mọi query danh sách.
4. `FreshnessProvider`: query E-60 mỗi 15 s, cung cấp `businessNow`, `timezone`, `stale`, `sources[]` cho `StaleBanner` và bộ định dạng thời gian.
5. `RealtimeProvider`: một kết nối SSE cho cả ứng dụng (DOC-26 §8).
6. `RouterProvider` với `context = { queryClient, auth, me }` để `beforeLoad` kiểm quyền.

### 9.3 Đăng nhập

Cấu hình `oidc-client-ts` (qua `react-oidc-context`):

| Khóa | Giá trị |
| --- | --- |
| `authority` | `${keycloakUrl}/realms/${keycloakRealm}` |
| `client_id` | `keycloakClientId` (`pti-web`) |
| `redirect_uri` | `${origin}/auth/callback` |
| `silent_redirect_uri` | `${origin}/auth/silent.html` |
| `post_logout_redirect_uri` | `${origin}/` |
| `response_type`, `scope` | `code`, `openid profile` |
| `userStore` | `new WebStorageStateStore({ store: new InMemoryWebStorage() })` (token chỉ ở bộ nhớ, DOC-27 §3.2) |
| `automaticSilentRenew` | `true` |
| `monitorSession` | `false` |

Luồng:

- **Khởi động:** gọi `signinSilent()` một lần (iframe tới Keycloak với `prompt=none`). Có phiên Keycloak thì người dùng đăng nhập lại mà không thấy gì; không có thì tiếp tục là anonymous. Trong lúc chờ (tối đa 1,5 s) thanh trên hiện skeleton ở vị trí menu người dùng; nội dung trang công khai vẫn render ngay.
- **Sign in:** `signinRedirect({ state: { returnTo: location.href } })`. `/auth/callback` gọi `signinRedirectCallback`, rồi `navigate` về `returnTo` (chỉ chấp nhận đường dẫn cùng origin).
- **Sign out:** `signoutRedirect()`; xóa cache TanStack Query (`queryClient.clear()`) trước khi chuyển trang để dữ liệu có quyền không còn trong bộ nhớ.
- **Token hết hạn khi đang dùng:** middleware của client API gặp 401 trên request có token → gọi `signinSilent()` một lần rồi gửi lại request. Vẫn 401 → đưa người dùng về anonymous, toast "Your session has expired. Sign in again." (có nút "Sign in"), trang đang mở chuyển sang trạng thái cần đăng nhập nếu cần quyền.
- `silent.html` chỉ tải `silent.js` (CSP `script-src 'self'`, DOC-27 §5.3), trong đó gọi `new UserManager({...}).signinSilentCallback()` từ bản build riêng nhỏ (entry thứ hai của Vite).

### 9.4 Client API

- `openapi-fetch` với `baseUrl: '/api/v1'`, type từ `src/api/generated/schema.d.ts` (`pnpm gen:api` đọc `api/openapi.json`, DR-44).
- Middleware theo thứ tự: gắn `Authorization` khi có token; gắn `Idempotency-Key` (UUIDv4 sinh một lần cho mỗi lần người dùng bấm, giữ nguyên khi retry) cho các request trong danh sách của DOC-31 §8; đọc `X-Data-As-Of`; đổi response lỗi thành `ApiError` chứa Problem Details (DOC-30) và `traceId`.
- Query function trả `{ data, asOf }` để `FreshnessIndicator` dùng (P-1).
- Query key lấy từ `src/api/keys.ts`, khớp bảng DOC-26 §9 (ví dụ `keys.dlq.list(filters)` → `['etl', 'dlq', 'list', filters]`).
- Endpoint `/sim/**` (E-90) không có trong `openapi.json` công khai; `src/api/sim.ts` khai báo schema zod theo DOC-25 §8 và validate response lúc chạy.

### 9.5 Error boundary

- Mỗi route có `errorComponent` hiển thị `ErrorState` (DOC-35) thay vì làm sập cả ứng dụng. Lỗi render (bug) hiện "Something went wrong on this page." với nút "Reload page" và mã lỗi (tên lỗi + 8 ký tự đầu của stack hash) để báo lại.
- Lỗi trong một panel (ví dụ biểu đồ) được bắt bởi `PanelErrorBoundary`, các panel khác vẫn chạy.

## 10. Cấu hình lúc chạy (`env.js`)

Image frontend là file tĩnh dùng chung mọi môi trường. Entrypoint của nginx render `/usr/share/nginx/html/env.js` từ biến môi trường container lúc khởi động (cùng lúc với header CSP, DOC-27 §5.3):

```js
window.__PTI_ENV__ = {
  keycloakUrl: "http://localhost:8180",   // PTI_KEYCLOAK_URL; empty → anonymous-only UI (k3d lite, DOC-27 §3.3)
  keycloakRealm: "pti",                   // PTI_KEYCLOAK_REALM
  keycloakClientId: "pti-web",            // PTI_KEYCLOAK_CLIENT_ID
  mapStyle: "offline",                    // PTI_MAP_STYLE: offline | online (ADR-0021)
  demoControl: false                      // true when PTI_EXTRA_PROFILES contains "demo"
};
```

- `src/env.ts` validate bằng zod; thiếu hoặc sai → dùng mặc định an toàn (anonymous, `offline`, `demoControl: false`) và log `console.warn`.
- `index.html` nạp `<script src="/env.js"></script>` trước bundle chính. nginx trả `env.js` với `Cache-Control: no-store`.
- Dev: `frontend/public/env.js` (commit vào repo) có `mapStyle: "online"` và `demoControl: true`; sửa tay khi cần.
- `demoControl` chỉ quyết định **hiện** mục "Demo". Nếu server không bật profile `demo` thì E-90 trả 404 và màn Demo hiện trạng thái "Demo control is not enabled on this server." (screens/demo-control).

Bảng biến và giá trị mặc định ở DOC-29 §3.5 và DOC-39.

## 11. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| UX-01 | Unit: `parseSearch`/`stringifySearch` với list, thời điểm, giá trị mặc định | Round-trip giữ nguyên; giá trị mặc định không xuất hiện trên URL |
| UX-02 | Component: route `/ops/dlq` với `severity=9&status=FOO` | Trang mở với bộ lọc mặc định; URL được viết lại bỏ hai tham số sai |
| UX-03 | Component: anonymous mở `/ops/jobs` | Trạng thái "Sign in to view this page"; không gọi E-30 |
| UX-04 | Component: viewer mở `/ops/dlq?id=<id>` | Drawer mở, không có nút ghi, có badge "Read-only" |
| UX-05 | Unit: `formatRelative` theo `businessNow` lệch 13 giờ so với đồng hồ máy | "5 s ago" cho vị trí xe 5 s trước `businessNow` |
| UX-06 | Unit: middleware 401 → `signinSilent` thành công → gửi lại một lần | Request thứ hai có token mới; không lặp vô hạn khi vẫn 401 |
| UX-07 | Unit: `env.ts` với `window.__PTI_ENV__` thiếu | Mặc định an toàn, không ném lỗi |
| UX-08 | CI: `scripts/check-bundle.mjs` trên bản build | Mọi chunk trong ngân sách §7; vượt thì fail |
| UX-09 | Component: `link` của từng `type` alert (§5.3) | Mở đúng route và search params; `INFRA` với runbook mở tab mới (`rel="noopener"`) |
| UX-10 | Component: sign out | `queryClient` rỗng sau khi sign out |

Ca E2E của khung (đăng nhập, điều hướng theo role) ở `screens/shell-and-navigation.md`.

## 12. Câu hỏi còn mở

Không có.
