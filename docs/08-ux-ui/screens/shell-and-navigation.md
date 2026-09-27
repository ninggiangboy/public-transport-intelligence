# Màn hình: Khung ứng dụng và điều hướng

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34 §3–4, §9.3, DOC-35, DOC-37 §2.4–2.5, §6, DOC-32 (E-01, E-41, E-55, E-60, E-61), DOC-26 §8
> Người dùng chính: P5-04

## 1. Persona, use case, quyền

- **Persona:** mọi persona (PS-1…PS-5).
- **Use case:** nền cho mọi UC; FR-11.6 (không màn hình trắng, stale banner).
- **Quyền:** mọi người. Nội dung menu đổi theo role (DOC-34 §4.1).

## 2. URL và search params

Khung bọc mọi route. Route riêng: `/auth/callback` (không search params của ứng dụng), trang 404 cho URL không khớp.

## 3. Wireframe

Desktop (≥ 768 px):

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ [PTI] Public Transport Intelligence   Map  Stops  Alerts(3)  Scorecard  Ops  │
│                                             ● Live   ◐ Theme   [Operator ▾]   │
├──────────────────────────────────────────────────────────────────────────────┤
│ ⚠ Live data is delayed. Vehicle positions were last updated 3 min ago.        │  ← StaleBanner
├────────────┬─────────────────────────────────────────────────────────────────┤
│ Jobs       │                                                                 │
│ Dead lett. │                <main> page content                               │
│   (214)    │                                                                 │
│ Replay     │                                                                 │
│ Controls ● │                                                                 │
│ Ticketing  │                                                                 │
│ Demo       │                                                                 │
│  (ops nav, only under /ops)                                                  │
├────────────┴─────────────────────────────────────────────────────────────────┤
│ Data: Metro Transit GTFS (public domain). Map: © OpenStreetMap … · Simulated clock: Sep 29, 4:19 PM CDT │
└──────────────────────────────────────────────────────────────────────────────┘
```

Mobile (< 768 px):

```text
┌──────────────────────────────┐
│ [PTI]            ● Live  [≡] │
├──────────────────────────────┤
│ ⚠ Live data is delayed. …    │
│                              │
│        page content          │
│                              │
├──────────────────────────────┤
│  Map   Stops   Alerts  More  │  ← bottom tabs
└──────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Link bỏ qua | `<a href="#main">` | Phần tử focus đầu tiên, chỉ hiện khi focus |
| Thanh trên | `TopBar` (`app/shell/`) | Logo là link về `/map`. Menu chính theo DOC-34 §4.1. Mục đang chọn có `aria-current="page"` |
| Badge "Alerts" | số alert chưa ack | Chỉ khi đăng nhập (§5) |
| `RealtimeStatusDot` | DOC-35 §5.2 | |
| Nút theme | Menu "Theme": "Light", "Dark", "System" | Anonymous: nút riêng trên thanh; đã đăng nhập: trong menu người dùng |
| Menu người dùng | shadcn `DropdownMenu` | "Signed in as {displayName}", "Role: {role}" (role cao nhất), "Theme", "Keyboard shortcuts", "Sign out" |
| Vùng banner | `StaleBanner`, dải màn hẹp của ops | DOC-37 §2.4, §6 |
| Thanh bên Ops | `OpsNav` | Chỉ dưới `/ops/*`. Badge số bản ghi DLQ mở (E-41 `open`); chấm `warning` cạnh "Controls" và chip "Paused" khi có cờ `*.paused` bằng `true` (E-55) |
| Footer | chữ nhỏ `text-xs` | DOC-37 §6; dòng "Simulated clock" chỉ khi `clockOffset ≠ PT0S` |
| Thanh tab dưới | `BottomTabs` | < 768 px; "More" mở `Sheet` gồm Scorecard, Ops (viewer), Theme, Sign in/out |
| Toast | `sonner` `Toaster` | Góc dưới phải (desktop), trên cùng (mobile) |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Người dùng, role | E-61 `GET /me` | `['me']` | Khi token đổi; không poll |
| Độ tươi, `businessNow`, múi giờ | E-60 `GET /system/freshness` | `['system', 'freshness']` | 15 s |
| Số alert chưa ack (đăng nhập) | E-20 `GET /alerts?state=unacknowledged&limit=100` (24 giờ mặc định) | `['alerts', 'badge']` | SSE `alert.*` → invalidate; 60 s |
| Số DLQ mở (viewer, dưới `/ops`) | E-41 `GET /etl/dlq/summary` | `['etl', 'dlq', 'summary']` | SSE `dlq.changed` (DOC-26 §9) |
| Cờ vận hành (viewer, dưới `/ops`) | E-55 `GET /etl/flags` | `['etl', 'flags']` | 30 s; invalidate sau E-57 |
| Kết nối SSE | E-70 | — | Kênh do các trang khai báo; khung luôn thêm `alerts` |

- Badge Alerts hiện số phần tử trả về (tối đa "99+"). Không có API đếm riêng; 100 là đủ cho badge.
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
| Chọn "Keyboard shortcuts" trong menu người dùng | Dialog liệt kê phím tắt của DOC-34 §4.3 | — |
| Cửa sổ < 1280 px dưới `/ops` | Dải "The ops console is designed for screens at least 1280 px wide." (đóng được) | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Chỉ skeleton menu người dùng khi đang khôi phục phiên |
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
| Nhãn nav cho screen reader | `aria-label` "Main" (menu chính), "Ops" (thanh bên) |
| Badge alert (screen reader) | "{n} unacknowledged alerts" |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given anonymous, When mở `/map`, Then menu chỉ có "Map", "Stops", "Alerts" và nút "Sign in".
- **AC-2** Given đăng nhập `viewer`, When mở bất kỳ trang, Then có thêm "Scorecard", "Ops"; không có "Demo".
- **AC-3** Given đăng nhập `operator` và `demoControl = true`, When mở `/ops/jobs`, Then thanh bên có "Demo".
- **AC-4** Given anonymous đang ở `/ops/dlq?severity=2`, When bấm "Sign in" và đăng nhập `viewer`, Then quay về đúng `/ops/dlq?severity=2`.
- **AC-5** Given đã đăng nhập, When reload trang, Then vẫn đăng nhập (qua `signinSilent`) mà không thấy màn Keycloak.
- **AC-6** Given E-60 trả `stale: true`, When mở `/map`, Then `StaleBanner` hiện trong ≤ 15 s; When E-60 hết stale, Then banner biến mất.
- **AC-7** Given cờ `etl.consumer.gtfs-rt.paused = true`, When mở mọi trang `/ops/*`, Then "Controls" có chấm cảnh báo và chip "Paused".
- **AC-8** Given màn hình 375 px, When mở `/map`, Then điều hướng ở thanh tab dưới, không cuộn ngang trang.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-SHELL-01 | Anonymous, rồi đăng nhập `viewer`, rồi `operator` (Keycloak thật); chạy axe ở mỗi bước | AC-1, AC-2, AC-3; không vi phạm axe `serious`/`critical` |
| E2E-SHELL-02 | Mở `/ops/dlq?severity=2` khi chưa đăng nhập → "Sign in" → đăng nhập; reload | AC-4, AC-5 |
| E2E-SHELL-03 | Bật cờ `etl.consumer.gtfs-rt.paused` qua E-57; chờ tới khi E-60 báo `stale` (tối đa 3 phút); tắt cờ | AC-6, AC-7; banner biến mất sau khi tắt cờ |

## 11. Câu hỏi còn mở

Không có.
