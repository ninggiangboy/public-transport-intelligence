# Màn hình: Khung ứng dụng và điều hướng

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DOC-34 §3–4, §9.3, DOC-35, DOC-37 §2.4–2.5, §6, DOC-32 (E-01, E-06, E-15, E-20, E-31, E-41, E-51, E-55, E-60, E-61), DOC-26 §8, DR-88
> Người dùng chính: P5-04

## 1. Persona, use case, quyền

- **Persona:** mọi persona (PS-1…PS-5).
- **Use case:** nền cho mọi UC; FR-11.6 (không màn hình trắng, stale banner).
- **Quyền:** mọi người. Nội dung menu đổi theo role (DOC-34 §4.1).

## 2. URL và search params

Khung bọc mọi route. Route riêng: `/auth/callback` (không search params của ứng dụng), trang 404 cho URL không khớp.

## 3. Wireframe

Prototype (DR-88): [Sidebar (shared component)](assets/shell-and-navigation.html).

Desktop (≥ 1280 px), operator đang ở Dead letters:

```text
┌──────────────────────┬───────────────────────────────────────────────────────────────┐
│ [◆] Transit          │ ⚠ Live data is delayed. Vehicle positions were last updated…   │ ← StaleBanner
│     Intelligence  ⇅  │                                                               │
│     Metro Transit ·… │ Operations / Dead letters                                     │
│ [🔍 Search      ⌘K]  │ Dead letters                                    [actions]     │ ← PageHeader
│                      │ 214 open · 37 new/h                                           │
│ Network              │                                                               │
│  ◫ Overview          │                                                               │
│  ◎ Live map          │                    <main> page content                        │
│  ⚑ Stops             │                                                               │
│  ⚠ Alerts        (3) │                                                               │
│ Analytics            │                                                               │
│  ▤ Scorecard         │                                                               │
│ Operations           │                                                               │
│  ⇶ Pipeline        • │                                                               │
│  ⊠ Dead letters  214 │ ◀ active item: white card, border, indigo icon                │
│  ↺ Replay          1 │                                                               │
│  ▦ Ticketing       2 │                                                               │
│  ⚙ Controls  Paused  │                                                               │
│  ▶ Demo              │                                                               │
│ ┌ Live feed ───────┐ │                                                               │
│ │● Updated 3 s ago │ │                                                               │
│ │▁▂▃▄▅▅▆▆▇ 142/s   │ │                                                               │
│ └──────────────────┘ │                                                               │
│ (LT) Linh Tran    ⋯  │ Data: Metro Transit GTFS … · Simulated clock: Sep 29, 4:19 PM │ ← footer
│      Operator · on duty                                                              │
└──────────────────────┴───────────────────────────────────────────────────────────────┘
```

Mobile (< 768 px):

```text
┌──────────────────────────────┐
│ [◆]          ● Live  🔍  (LT) │
├──────────────────────────────┤
│ ⚠ Live data is delayed. …    │
│                              │
│        page content          │
│                              │
├──────────────────────────────┤
│  Map   Stops   Alerts²  More │  ← bottom tabs
└──────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Link bỏ qua | `<a href="#main">` | Phần tử focus đầu tiên, chỉ hiện khi focus |
| Sidebar | `AppSidebar` (`app/shell/`) | 232 px, nền `--surface` (DOC-34 §4.2). `nav aria-label="Primary"`; tiêu đề nhóm là `h2` ẩn cấp bậc thị giác. Mục đang chọn có `aria-current="page"` |
| Thương hiệu | logo 30 px (nền `--foreground`) + "Transit Intelligence" + dòng agency | Logo là link về trang mặc định theo role (`/overview` hoặc `/map`) |
| Tìm kiếm | `CommandSearch` (shadcn `Command` trong `Dialog`) | Ô giả trong sidebar + phím `⌘K`/`Ctrl K`. Kết quả nhóm "Pages" (theo quyền), "Routes" (E-01 cache, tìm `shortName`/`longName`), "Stops" (E-06 `q`, debounce 250 ms, ≥ 2 ký tự) |
| Nhóm menu | `NavGroup` × 3 | Theo DOC-34 §4.1. Anonymous chỉ có "Network" (không có "Overview") |
| Số đếm | `NavCount` | Số thường là chữ `--muted-foreground`; số cần chú ý (Alerts chưa ack) là pill `tone-danger`. Chấm `danger` cạnh "Pipeline", chip "Paused" `tone-warning` cạnh "Controls" |
| Badge "Read-only" | badge `neutral` cạnh tiêu đề nhóm "Operations" | Chỉ viewer |
| Thẻ "Live feed" | `LiveFeedCard` | `RealtimeStatusDot` + "Updated {relative}" (event time của vị trí xe, E-60). Viewer thêm "{rate} msg/s" và sparkline 24 phút (E-31) |
| Tài khoản | `AccountMenu` (shadcn `DropdownMenu`) | Avatar chữ tắt, tên, vai trò. Menu: "Signed in as {displayName}", "Role: {role}", "Theme" (Light/Dark/System), "Keyboard shortcuts", "Sign out". Anonymous: nút "Sign in" (primary) + nút theme |
| Vùng banner | `StaleBanner`, dải màn hẹp của ops | Đầu vùng nội dung, trên `PageHeader` (DOC-37 §2.4, §6) |
| Đầu trang | `PageHeader` | Breadcrumb nhóm / trang, `h1`, dòng phụ, nút thao tác. Live map không có |
| Footer | chữ nhỏ `text-xs` cuối vùng nội dung | DOC-37 §6; dòng "Simulated clock" chỉ khi `clockOffset ≠ PT0S`. Live map đặt attribution ở góc bản đồ thay cho footer |
| Thanh trên mobile | `MobileTopBar` | < 1024 px: logo, `RealtimeStatusDot`, nút tìm kiếm, avatar hoặc "Sign in"; 768–1023 px thêm nút menu mở sidebar dạng `Sheet` |
| Thanh tab dưới | `BottomTabs` | < 768 px; "More" mở `Sheet` gồm Overview, Scorecard, nhóm Operations (theo role), Theme, Sign in/out |
| Toast | `sonner` `Toaster` | Góc dưới phải (desktop), trên cùng (mobile) |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Người dùng, role | E-61 `GET /me` | `['me']` | Khi token đổi; không poll |
| Độ tươi, `businessNow`, múi giờ | E-60 `GET /system/freshness` | `['system', 'freshness']` | 15 s |
| Số alert chưa ack (đăng nhập) | E-20 `GET /alerts?state=unacknowledged&limit=100` (24 giờ mặc định) | `['alerts', 'badge']` | SSE `alert.*` → invalidate; 60 s |
| Số DLQ mở (viewer) | E-41 `GET /etl/dlq/summary` | `['etl', 'dlq', 'summary']` | SSE `dlq.changed` (DOC-26 §9); 60 s |
| Cờ vận hành (viewer) | E-55 `GET /etl/flags` | `['etl', 'flags']` | 30 s; invalidate sau E-57 |
| Chấm "Pipeline", msg/s và sparkline "Live feed" (viewer) | E-31 `?from=now−24h&to=now&bucket=1h` (lần chạy batch `FAILED`) và `?from=now−24m&bucket=1m` (msg/s) | `['etl', 'jobs', 'summary', …]` | 60 s |
| Số replay đang chạy (viewer) | E-51 `?status=RUNNING,PENDING&limit=10` | `['etl', 'replays', { status: [...] }]` | 60 s |
| Số bất thường ticketing 24 giờ (viewer) | E-15 `?limit=100` (24 giờ mặc định) | `['insights', 'ticketing', 'badge']` | SSE `alert.created` loại `TICKETING_ANOMALY` → invalidate; 60 s |
| Tìm kiếm | E-01 (cache), E-06 `?q=&limit=8` | `['routes']`, `['stops', 'search', q]` | Khi gõ |
| Kết nối SSE | E-70 | — | Kênh do các trang khai báo; khung luôn thêm `alerts` |

- Số đếm chỉ tải với role có quyền xem trang đích; anonymous không gọi E-31, E-41, E-51, E-15, E-55.
- Số đếm hiện tối đa "99+". Không có API đếm riêng; `limit=100` là đủ cho badge.
- E-60 lỗi không chặn render; `FreshnessProvider` giữ giá trị cũ và `StaleBanner` hiện ưu tiên 2.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Tải trang lần đầu | Render khung ngay; `signinSilent` chạy nền (≤ 1,5 s), menu người dùng là skeleton trong lúc chờ | Keycloak không phản hồi → anonymous, không báo lỗi |
| Bấm "Sign in" | `signinRedirect` với `returnTo` = URL hiện tại | Keycloak không mở được → toast "Couldn't reach the sign-in service. Try again." |
| `/auth/callback` | "Signing you in…" rồi về `returnTo` | `state` sai hoặc lỗi → trang lỗi "Sign-in didn't complete" + nút "Try again" (quay lại `/`) |
| Bấm "Sign out" | Xóa cache query, `signoutRedirect` | — |
| Token hết hạn giữa chừng | DOC-34 §9.3 | Toast "Your session has expired. Sign in again." |
| Đổi theme | Áp ngay, lưu `localStorage` | Lưu thất bại → vẫn áp cho phiên |
| Bấm mục menu | Điều hướng; focus chuyển tới `h1` của trang mới | — |
| `⌘K` / `Ctrl K` hoặc bấm ô "Search" | Mở `CommandSearch`; mũi tên chọn, `Enter` điều hướng, `Esc` đóng | E-06 lỗi → nhóm "Stops" ghi "Couldn't search stops" |
| Thu gọn sidebar (1024–1279 px) | Thanh icon 64 px; nhãn thành tooltip; lựa chọn lưu `pti.sidebar.collapsed` | — |
| Chọn "Keyboard shortcuts" trong menu người dùng | Dialog liệt kê phím tắt của DOC-34 §4.3 | — |
| Cửa sổ < 1280 px dưới `/ops` | Dải "The ops console is designed for screens at least 1280 px wide." (đóng được) | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Chỉ skeleton vùng tài khoản ở chân sidebar khi đang khôi phục phiên; số đếm chưa có thì không hiện (không hiện "0") |
| Empty | — |
| Error | Lỗi render trong route → `errorComponent` (DOC-34 §9.5); khung vẫn hiện, điều hướng vẫn dùng được |
| Stale | `StaleBanner` (DOC-37 §2.4) |
| Không có quyền | Route cần quyền → DOC-37 §2.5, render trong vùng `main` |
| Không có Keycloak (`env.js` rỗng) | Ẩn "Sign in"; mở trang cần quyền → "Sign-in isn't available" |

## 8. Microcopy

Chuỗi chung ở DOC-37 §6. Riêng màn này:

| Vị trí | Chuỗi |
| --- | --- |
| Callback | "Signing you in…" |
| Callback lỗi | "Sign-in didn't complete" · "Something went wrong while signing you in." · "Try again" |
| Keycloak lỗi | "Couldn't reach the sign-in service. Try again." |
| Dialog phím tắt | Tiêu đề "Keyboard shortcuts"; dòng: "Focus search or filters" (`/`), "Next / previous row" (`j` / `k`), "Open selected row" (`Enter`), "Close panel or dialog" (`Esc`) |
| Sidebar | Nhóm "Network", "Analytics", "Operations" · "Search" · "Live feed" · "Updated {relative}" · "{rate} msg/s" · "Read-only" · "Paused" |
| Tìm kiếm | Placeholder "Search pages, routes and stops" · nhóm "Pages", "Routes", "Stops" · "No matches for "{q}"" · "Couldn't search stops" |
| Nhãn cho screen reader | `aria-label` "Primary" (sidebar), "Account menu", "Open search", "Collapse sidebar" / "Expand sidebar" |
| Badge alert (screen reader) | "{n} unacknowledged alerts" |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given anonymous, When mở `/map`, Then sidebar chỉ có nhóm "Network" với "Live map", "Stops", "Alerts" và nút "Sign in".
- **AC-2** Given đăng nhập `viewer`, When mở `/`, Then chuyển tới `/overview`; sidebar có thêm "Overview", "Scorecard", nhóm "Operations" với badge "Read-only"; không có "Demo".
- **AC-3** Given đăng nhập `operator` và `demoControl = true`, When mở `/ops/jobs`, Then nhóm "Operations" có "Demo" và mục "Pipeline" đang chọn.
- **AC-4** Given anonymous đang ở `/ops/dlq?severity=2`, When bấm "Sign in" và đăng nhập `viewer`, Then quay về đúng `/ops/dlq?severity=2`.
- **AC-5** Given đã đăng nhập, When reload trang, Then vẫn đăng nhập (qua `signinSilent`) mà không thấy màn Keycloak.
- **AC-6** Given E-60 trả `stale: true`, When mở `/map`, Then `StaleBanner` hiện trong ≤ 15 s; When E-60 hết stale, Then banner biến mất.
- **AC-7** Given viewer và cờ `etl.consumer.gtfs-rt.paused = true`, When mở bất kỳ trang, Then mục "Controls" có chip "Paused".
- **AC-8** Given màn hình 375 px, When mở `/map`, Then điều hướng ở thanh tab dưới, không cuộn ngang trang.
- **AC-9** Given viewer, When bấm `⌘K` và gõ "18", Then có tuyến 18 trong nhóm "Routes"; `Enter` mở `/map?route=18`.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-SHELL-01 | Anonymous, rồi đăng nhập `viewer`, rồi `operator` (Keycloak thật); chạy axe ở mỗi bước | AC-1, AC-2, AC-3; không vi phạm axe `serious`/`critical` |
| E2E-SHELL-02 | Mở `/ops/dlq?severity=2` khi chưa đăng nhập → "Sign in" → đăng nhập; reload | AC-4, AC-5 |
| E2E-SHELL-03 | Bật cờ `etl.consumer.gtfs-rt.paused` qua E-57; chờ tới khi E-60 báo `stale` (tối đa 3 phút); tắt cờ | AC-6, AC-7; banner biến mất sau khi tắt cờ |

## 11. Câu hỏi còn mở

Không có.
