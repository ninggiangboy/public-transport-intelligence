# Màn hình: Tìm trạm và chi tiết trạm

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35, DOC-37 §4.4, §5, DOC-32 (E-06, E-07, E-08), DOC-33 §5.3–5.6, DOC-23 §7
> Người dùng chính: P5-07

## 1. Persona, use case, quyền

- **Persona:** PS-1 (điện thoại, 4G, vội).
- **Use case:** UC-02 (giờ đến dự kiến), UC-03 (banner gián đoạn). Journey J-1 bước 1–3, 5.
- **Quyền:** mọi người. Anonymous chỉ thấy gián đoạn `PUBLIC` (E-07 lọc sẵn).
- **Hiệu năng:** LCP < 2,5 s trên 4G (NFR-12). Trang **không** tải MapLibre, ECharts hay TanStack Table (DOC-34 §7).

## 2. URL và search params

- `/stops?q=<text>` — tìm trạm; `q` rỗng thì hiện "Recent stops".
- `/stops/$stopId` — chi tiết trạm. Không có search params.

## 3. Wireframe

Chi tiết trạm (mobile, 375 px):

```text
┌────────────────────────────────┐
│ ← Stops                        │
│ Nicollet Ave & 46th St         │  h1
│ Stop 51405 · ♿ Wheelchair acc. │
│ [18] [46]          View on map │
├────────────────────────────────┤
│ ⚠ Delays on route 18 northbound│  banner (warning; danger if severity 2)
│   Since 3:58 PM                │
├────────────────────────────────┤
│ Upcoming departures            │
│ Predictions as of 4:05 PM CDT  │
│ ┌────────────────────────────┐ │
│ │[18] Downtown Minneapolis   │ │
│ │     6 min        ▮▮▮ High  │ │
│ │     4:25 PM · 1 min late   │ │
│ │     Scheduled 4:24 PM      │ │
│ ├────────────────────────────┤ │
│ │[18] Downtown Minneapolis   │ │
│ │     16 min       Schedule  │ │
│ │     4:34 PM · Scheduled    │ │
│ └────────────────────────────┘ │
│ [Show more departures]         │
└────────────────────────────────┘
```

Tìm trạm:

```text
┌────────────────────────────────┐
│ Find a stop                    │
│ [🔍 Stop name or number      ] │
│ Recent stops                   │
│  Nicollet Ave & 46th St  51405 │
│ ─ or results ─                 │
│  Nicollet Ave & 46th St  51405 │
│  [18] [46]                     │
└────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | `h1` tên trạm; dòng phụ mã trạm, trạng thái xe lăn (DOC-37 §3.2) | Link "View on map" → `/map?c=<lon>,<lat>,16` |
| Tuyến qua trạm | `RouteBadge` (từ `routes` của E-07) | Bấm → `/map?route=<routeId>` |
| Banner gián đoạn | Dải `warning` (severity 1) hoặc `danger` (severity 2) với `SeverityBadge` | Một banner cho mỗi phần tử `activeDisruptions` (tối đa 3, còn lại "and {n} more") |
| Danh sách giờ đến | `ArrivalRow` × N | `ul` với mỗi mục là `li` |
| Mức tin cậy | `ConfidenceChip level sampleCount` | Tooltip `help.etaConfidence` / `help.etaNone` |
| Độ tươi | `FreshnessIndicator axis="event" mode="absolute"` theo `X-Data-As-Of` của E-08 | "Predictions as of …" |
| Ô tìm kiếm | `input type="search"` có label ẩn | Debounce 250 ms, tối thiểu 2 ký tự |
| Trạm gần đây | danh sách link | `localStorage` `pti.recentStops` (5 mục, try/catch) |

`ArrivalRow`: `RouteBadge` + `headsign`; ETA lớn (`text-2xl`, DOC-37 §4.4); giờ dự đoán và độ trễ; "Scheduled {time}" khi khác giờ dự đoán; `ConfidenceChip`. Có `realtimeArrival` thì ETA theo giờ đó, kèm icon `Radio` và chữ "Live" (tooltip `help.etaRealtime`).

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Tìm trạm | E-06 `?q=` (limit 20) | `['stops', 'search', q]` | Không refetch; `staleTime` 5 phút |
| Thông tin trạm, gián đoạn | E-07 | `['stops', stopId, 'detail']` | SSE `disruption.*`, `alert.updated`, `alert.retracted` của tuyến qua trạm → invalidate; 60 s |
| Giờ đến | E-08 `?limit=10` (bấm "Show more" → 30) | `['stops', stopId, 'arrivals', limit]` | **30 s** (UC-02 bước 4); không có SSE |
| Kênh SSE | `alerts` với `routeId` = các tuyến của trạm (bỏ nếu > 20) | — | — |

- Hai query chạy song song; trang render phần có trước (DOC-37 §2.6).
- ETA đếm lùi theo `businessNow` (DOC-34 §8), render lại mỗi 15 s; không đợi refetch. Chuyến có ETA đã qua (< −60 s) bị ẩn cho tới lần refetch sau.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Gõ vào ô tìm kiếm | `q` trên URL (`replace`), danh sách kết quả | 400 (q < 2 ký tự sau trim) không gửi; lỗi mạng → inline error |
| Chọn kết quả | Tới `/stops/<stopId>`; thêm vào "Recent stops" | — |
| Mở `/stops/<stopId>` | Tải E-07 và E-08 | E-07 404 → trạng thái "Stop not found" kèm ô tìm kiếm (UC-02 E1) |
| "Show more departures" | `limit=30` | — |
| SSE `alert.created`/`disruption.opened` trên tuyến qua trạm | Banner mới xuất hiện (refetch E-07); vùng `aria-live="polite"` đọc tiêu đề | — |
| Gián đoạn kết thúc | Banner đổi thành `success` "Service on Route {route} is back to normal", ẩn sau 2 phút (UC-03 bước 3) | — |
| `alert.retracted` (FR-09.5) | Banner bị gỡ ngay, không có thông báo | — |
| Bấm banner | Mở `/map?route=<routeId>&disruption=<disruptionId>` | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Đầu trang skeleton 2 dòng; danh sách 4 dòng skeleton cao 72 px |
| Empty (giờ đến) | "No upcoming departures" · "No trips are scheduled at this stop in the next 90 min." |
| Empty (tìm kiếm) | "No stops match "{q}"" · "Try the stop number shown on the sign, or part of the street name." |
| Error | E-08 lỗi: danh sách thành `ErrorState`, phần đầu trang và banner vẫn hiện. E-07 lỗi (không phải 404): `ErrorState variant="block"` cho toàn trang, có "Retry" |
| Stale | `StaleBanner`; nếu `X-Data-As-Of` của E-08 cũ hơn 2 giờ thì "Predictions as of …" có tông `warning` |
| Không có quyền | Không áp dụng |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề trang tìm | "Find a stop" |
| Ô tìm kiếm | Label "Search stops" · placeholder "Stop name or number" |
| Trạm gần đây | "Recent stops" · "Clear" |
| Không tìm thấy | "No stops match "{q}"" · "Try the stop number shown on the sign, or part of the street name." |
| Đầu trang | "Stop {code}" · "View on map" · link quay lại "Stops" |
| Danh sách | Tiêu đề "Upcoming departures" · "Predictions as of {time}" · "Show more departures" |
| Dòng giờ đến | ETA (DOC-37 §4.4) · "{time} · {delay}" · "Scheduled {time}" · "Live" |
| Mức tin cậy | "High confidence · {n} trips" · "Medium confidence · {n} trips" · "Low confidence · {n} trips" · "Schedule only" |
| Banner | tiêu đề alert (từ API) · "Since {time}" · "and {n} more" · "Service on Route {route} is back to normal" |
| 404 | "Stop not found" · "Check the stop number, or search for the stop by name." |

`aria-label` của mỗi dòng giờ đến ghép đủ câu cho screen reader: "Route 18 to Downtown Minneapolis, in 6 minutes, 4:25 PM, 1 minute late, high confidence based on 36 trips".

## 9. Tiêu chí nghiệm thu

- **AC-1** Given trạm có chuyến trong 90 phút, When mở `/stops/<id>`, Then mỗi chuyến có ETA, giờ dự đoán và chip mức tin cậy; chuyến không có lịch sử ghi "Schedule only".
- **AC-2** Given 4G mô phỏng (Lighthouse mobile), Then LCP < 2,5 s và JS ban đầu ≤ 200 KB gzip.
- **AC-3** Given gián đoạn `PUBLIC` đang mở trên tuyến qua trạm, Then banner hiện; Given alert bị chuyển sang `ENGINEERING`, Then banner biến mất với anonymous trong ≤ 10 s và vẫn hiện với viewer.
- **AC-4** Given trang mở 5 phút, Then ETA giảm dần mà không reload và danh sách được refetch mỗi 30 s.
- **AC-5** Given `stopId` không tồn tại, Then "Stop not found" kèm ô tìm kiếm.
- **AC-6** Given tìm "nicollet", Then kết quả có trạm chứa "Nicollet", trạm có mã trùng khớp đứng đầu khi tìm bằng mã.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-STOP-01 | Anonymous ở viewport 375 px: `/stops` → gõ "nicollet" → chọn kết quả → danh sách giờ đến; axe | AC-1, AC-6; không vi phạm axe `serious`/`critical` |
| E2E-STOP-02 | Mở trạm trên tuyến 18; `POST /sim/scenarios/disruption` cho tuyến 18; chờ banner; `DELETE /sim/scenario-runs/{runId}` rồi chờ episode đóng | AC-3 (phần hiện), banner "back to normal" |
| E2E-STOP-03 | Mở `/stops/does-not-exist` | AC-5 |

LCP (AC-2) đo bằng Lighthouse CI trong nightly (DOC-44 §10), không phải ca E2E.

## 11. Câu hỏi còn mở

Không có.
