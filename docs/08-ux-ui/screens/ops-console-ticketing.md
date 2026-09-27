# Màn hình: Ops console — Ticketing anomalies

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35 §5, DOC-37 §2.4, §3, DOC-32 (E-15, E-16, E-60), DOC-23 §9, DOC-25 §7.6–7.7
> Người dùng chính: P5-11

## 1. Persona, use case, quyền

- **Persona:** PS-4, PS-3 (desktop).
- **Use case:** UC-12, UC-05 (mở từ alert `TICKETING_ANOMALY`), FR-09.4, FR-09.6–09.7.
- **Quyền:** viewer. Màn này chỉ đọc; không có thao tác ghi.

## 2. URL và search params

`/ops/ticketing` — theo DOC-34 §5.2:

| Param | Giá trị | Mặc định |
| --- | --- | --- |
| `window` | `24h` \| `7d` | `24h` |
| `from`, `to` | thời điểm (có thì bỏ qua `window`; tối đa 31 ngày) | — |
| `category` | list, gồm `unclassified` | tất cả |
| `severity` | list `0`–`2` | tất cả |
| `trigger` | `VOLUME` \| `REFUND_RATIO` \| `BOTH` | tất cả |
| `salePoint` | `salePointId` | — |
| `anomaly` | id, mở drawer | — |

Khoảng là **trục event** (`detected_at` = cuối cửa sổ, giờ nghiệp vụ). `window` tính từ `businessNow` của E-60, không phải giờ máy.

## 3. Wireframe

```text
┌────────────┬──────────────────────────────────────────────────────────────────┐
│ Jobs       │ Ticketing anomalies                                              │
│ Dead lett. │ ⚠ No ticket sales received for 18 min. Anomaly detection may be… │  SourceStaleNotice
│ Replay     │ [Last 24 hours ▾] [Category ▾] [Severity ▾] [Trigger ▾] [Sale pt]│
│ Controls   │ Detected   Sale point               Window        Trigger  Txns   │
│ Ticketing◀ │ 4:15 PM    Nicollet Mall kiosk 2    4:00–4:15 PM  Refunds  25     │
│            │            Refund 48% · z 3.48 · ⬣ High [69%] · Possible fraud [77%]│
│            │ 3:45 PM    KIOSK-001                3:30–3:45 PM  Volume   96     │
│            │            z 23.7 · Unclassified                                  │
└────────────┴──────────────────────────────────────────────────────────────────┘
```

Drawer (560 px):

```text
┌─────────────────────────────────────────────────┐
│ High refund rate at Nicollet Mall kiosk 2   [×] │
│ SP-0142 · Kiosk · Route [18] · 4:00–4:15 PM CDT │
│ ┌────────┐ ┌────────┐ ┌──────────┐ ┌────────┐   │
│ │Txns 25 │ │Refunds │ │Refund    │ │z 3.48  │   │
│ └────────┘ │  12    │ │rate 48%  │ └────────┘   │
│            └────────┘ └──────────┘              │
│ Previous windows  ▁▁▂▁ █                        │
│ Normal: 14.2 ± 3.1 per window (seasonal, 80)    │
│ AI classification                               │
│  Category  Possible fraud   [77% · Medium]      │
│  Severity  ⬣ High           [69% · Medium]      │
│  Model jev@0.2.0                                │
│ Ticket types  Single 20 · Day 5 · Month 0       │
│ Amount $50.00 · $2.50–$5.00 per sale            │
│ Thresholds: z ≥ 3 and ≥ 20 sales; refunds ≥ 30% │
│ and ≥ 5                                          │
│ [Copy link]                                     │
└─────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Thông báo nguồn | `SourceStaleNotice source="TICKETING_SALES"` | Khi E-60 `TICKETING_SALES.stale` (DOC-37 §2.4) |
| Bộ lọc | `TimeRangePicker` (presets `24h`, `7d`), `MultiSelectFilter` × 2 (category, severity), `Select` trigger, `Combobox` sale point | Gợi ý sale point = các `salePointId`/`salePointName` đã xuất hiện trong trang đã tải; vẫn gõ id tự do được |
| Bảng | `DataTable` + infinite (trang 100); dòng hai tầng | Cột: "Detected" (`Timestamp`), "Sale point" (`salePointName` hoặc id; `RouteBadge` nếu có `routeId`), "Window" (khoảng giờ DOC-37 §4), "Trigger", "Transactions", "Refunds", "Refund rate", "z-score", "Category" + `ConfidenceChip`, "Severity" (`SeverityBadge`) + `ConfidenceChip`. Chưa phân loại → chip `neutral` "Unclassified" (FR-09.7) |
| Drawer | `DetailDrawer` 560 px | §6.1 |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Danh sách | E-15 `?from&to&category&severity&trigger&salePointId&limit=100` | `['insights', 'ticketing', 'list', filters]` (infinite) | SSE `alert.created`/`alert.updated` với `type = TICKETING_ANOMALY` → refetch trang đầu (DOC-32 E-15); 60 s |
| Chi tiết | E-16 | `['insights', 'ticketing', 'detail', id]` | Khi mở drawer; `alert.updated` cùng `refId` → invalidate (triage vừa phân loại) |
| Độ tươi | E-60 | `['system', 'freshness']` | Từ khung |
| Kênh SSE | `alerts` (khung) | — | — |

- Thời điểm trên màn này là **trục event** (DOC-34 §8): tương đối theo `businessNow`.
- `X-Data-As-Of` của E-15 hiển thị "As of {time}" cạnh tiêu đề.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi bộ lọc | URL (`replace`); bảng mờ, giữ dữ liệu cũ | 400 (khoảng > 31 ngày) không xảy ra vì picker giới hạn |
| Bấm dòng / `Enter` | `anomaly=<id>` (`push`), drawer | E-16 404 → "This anomaly is no longer available." (đã bị tính lại) |
| Bấm sale point trong dòng | Lọc `salePoint=<id>` | — |
| Bất thường mới (SSE) | Chèn đầu với nền `info` nhạt 3 s; đã cuộn thì `NewItemsPill` | — |
| Bất thường vừa được phân loại (SSE) | Chip "Unclassified" đổi thành category + confidence tại chỗ | — |
| "Copy link" | Chép URL tuyệt đối `/ops/ticketing?anomaly=<id>` | — |

### 6.1 Drawer

- Tiêu đề: `title` tương ứng với `trigger` (DOC-23 §10.1: "Unusual ticket sales at …", "High refund rate at …", "Unusual sales and refund rate at …").
- Dòng phụ: `salePointId` · loại điểm bán (`summary.salePointKind`) · `RouteBadge` · khoảng cửa sổ.
- `StatCard` "Transactions", "Refunds", "Refund rate", "z-score".
- "Previous windows": `Sparkline` dạng cột của `summary.previousWindows[].txnCount` cộng cửa sổ hiện tại (cột cuối tông `warning`), `aria-label` liệt kê số.
- "Normal: {mean} ± {stddev} per window ({baselineKind}, {windows} windows)".
- "AI classification": category và severity kèm `ConfidenceChip` (< 0,6 → "Low confidence", tông `warning`, FR-09.6); "Model {modelVersion}"; chưa có → "Not yet classified" + tooltip `help.unclassified` (DOC-37 §5).
- "Ticket types", "Amount" (tiền theo `summary.currency`, locale `en-US`), "Thresholds" (từ `summary.thresholds`), "Voided sales" khi `voidCount > 0`.
- Không có dữ liệu cá nhân nào để hiển thị (`summary` đã không có PII, DOC-23 §9.5).

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Bảng 8 dòng skeleton cao 64 px; drawer `PanelSkeleton variant="detail"` |
| Empty | "No ticketing anomalies in this period" · "Anomalies are checked every 5 minutes on 15-minute windows." |
| Empty (có lọc) | "No anomalies match these filters" + "Clear filters" |
| Error | DOC-37 §2.3, §2.6 |
| Stale | `SourceStaleNotice` (ticketing); `StaleBanner` toàn cục vẫn theo GTFS-rt |
| Không có quyền | DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Ticketing anomalies" · "As of {time}" |
| Bộ lọc | "Period": "Last 24 hours", "Last 7 days" · "Category" · "Severity" · "Trigger" · "Sale point" · placeholder "Sale point ID" · "Clear filters" |
| Cột | "Detected", "Sale point", "Window", "Trigger", "Transactions", "Refunds", "Refund rate", "z-score", "Category", "Severity" |
| Drawer | "Transactions", "Refunds", "Refund rate", "z-score" · "Previous windows" · "Normal: {mean} ± {stddev} per window ({kind}, {n} windows)" · "AI classification" · "Category" · "Severity" · "Model {version}" · "Not yet classified" · "Ticket types" · "Single", "Day", "Month" · "Amount {sum} · {min}–{max} per sale" · "Thresholds: z ≥ {z} and ≥ {n} sales; refunds ≥ {ratio} and ≥ {count}" · "Voided sales: {n}" · "Copy link" |
| Loại baseline | `SEASONAL` "same hour on similar days" · `RECENT` "recent windows" · `NONE` → thay cả dòng bằng "No baseline yet" · loại điểm bán `KIOSK` "Kiosk", `ONBOARD` "On board", `APP` "App", null "Unknown" |
| Không còn | "This anomaly is no longer available." |
| Empty | "No ticketing anomalies in this period" · "Anomalies are checked every 5 minutes on 15-minute windows." · "No anomalies match these filters" |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given kịch bản `refund-burst`, Then trong ≤ 20 phút có dòng trigger "Refund ratio" cho điểm bán đó (cửa sổ 15 phút + job mỗi 5 phút).
- **AC-2** Given bất thường chưa phân loại, Then chip "Unclassified"; khi triage phân loại xong, chip đổi tại chỗ mà không reload.
- **AC-3** Given confidence < 0,6, Then chip "Low confidence" tông `warning`.
- **AC-4** Given `TICKETING_SALES` stale, Then `SourceStaleNotice` hiện ở đầu trang và chỉ ở trang này.
- **AC-5** Given lọc `category=unclassified`, Then chỉ còn dòng chưa phân loại (URL giữ bộ lọc).

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-TIX-01 | `viewer`: `POST /sim/scenarios/ticket-spike` với `{"duration": "PT20M"}`; chờ dòng trigger "Sales volume" (tối đa 22 phút, project `late`); mở drawer; axe | AC-1 (biến thể volume); drawer có "Previous windows" |
| E2E-TIX-02 | `viewer`: `POST /sim/scenarios/refund-burst` mặc định; chờ dòng "Refund ratio"; lọc `trigger=REFUND_RATIO` | AC-1 |
| E2E-TIX-03 | Mở `/ops/ticketing?category=unclassified` | AC-5 |

AC-2 cần triage-worker (P6, DOC-24); AC-3, AC-4 kiểm ở component test với MSW.

## 11. Câu hỏi còn mở

Không có.
