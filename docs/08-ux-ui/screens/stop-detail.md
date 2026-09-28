# Màn hình: Tìm trạm và chi tiết trạm

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35, DOC-37 §4.4, §5, DOC-32 (E-04, E-06, E-07, E-08), DOC-33 §5.3–5.6, DOC-23 §7
> Người dùng chính: P5-07

## 1. Persona, use case, quyền

- **Persona:** PS-1 (điện thoại, 4G, vội).
- **Use case:** UC-02 (giờ đến dự kiến), UC-03 (banner gián đoạn). Journey J-1 bước 1–3, 5.
- **Quyền:** mọi người. Anonymous chỉ thấy gián đoạn `PUBLIC` (E-07 lọc sẵn).
- **Hiệu năng:** LCP < 2,5 s trên 4G (NFR-12). JS ban đầu **không** có MapLibre, ECharts hay TanStack Table (DOC-34 §7). Bản đồ nhỏ trên desktop tải lười sau khi danh sách giờ đến đã render (§4).

## 2. URL và search params

- `/stops?q=<text>` — tìm trạm; `q` rỗng thì hiện "Saved stops" và "Recent stops".
- `/stops/$stopId` — chi tiết trạm. `route` (list routeId, lọc bảng giờ đến) và `dir` (`0` \| `1`, chỉ khi trạm có cả hai chiều) theo DOC-34 §5.1; không ghi khi là tất cả.

## 3. Wireframe

Prototype (DR-88): [Stop detail — desktop](assets/stop-detail.html) · [Stop detail — mobile](assets/stop-detail-mobile.html).

Chi tiết trạm, desktop (≥ 1024 px):

```text
┌ sidebar ┬───────────────────────────────────────────────────────────────────────────┐
│         │ Stops / 51405                                                              │
│         │ ┌──────┐ Nicollet Ave & 46th St                    [☆ Save stop] [View on map]│
│         │ │ STOP │ #51405 · ♿ Step-free · 2 routes                                    │
│         │ └──────┘                                                                   │
│         │ ┌ Departures ─────────────────────────────┐ ┌ ⚠ Delays on Route 18 NB ──────┐│
│         │ │ Predictions as of 4:32:08 PM             │ │ Buses are running up to 5 min │││
│         │ │ [All][18][46]        [Northbound|Southbd]│ │ late toward Lake St since 3:58│││
│         │ │ [18] Downtown Minneapolis     6 min      │ │ See alert ›                   │││
│         │ │      ● Live · 1 min late ▮▮▮  4:38 PM    │ └───────────────────────────────┘│
│         │ │ [18] Downtown Minneapolis    16 min      │ ┌ mini map ─────────────────────┐│
│         │ │      Schedule only             4:48 PM   │ │   ○ stop + routes through it  ││
│         │ │ [46] 46th St – Ford Pkwy     21 min      │ └───────────────────────────────┘│
│         │ │      On time ▮▮▯               4:53 PM   │ ┌ Reliability here ─────────────┐│
│         │ │ Times are predictions. Schedule-only …   │ │ Tuesdays around 4 PM          ││
│         │ │ [Show later departures]                  │ │ [18] avg +1m 04s · p90 +2m 50s││
│         │ └──────────────────────────────────────────┘ │ [46] avg +0m 12s · p90 +1m 30s││
│         │                                              └───────────────────────────────┘│
└─────────┴───────────────────────────────────────────────────────────────────────────┘
```

Chi tiết trạm, mobile (375 px):

```text
┌────────────────────────────────┐
│ ← Stops                    ☆   │
│ Nicollet Ave & Lake St         │  h1
│ Stop 51408 · Northbound · ♿    │
├────────────────────────────────┤
│ ⚠ Route 18 is running up to    │  callout (warning; danger if severity 2)
│   5 min late · Since 3:58 PM   │
├────────────────────────────────┤
│ [All] [18] [21]                │
│ Departures        As of 4:32 PM│
│ [18] Downtown          2  min  │  ETA 34 px
│      Live · 1 min late ▮▮▮     │
│ [18] Downtown          3  min  │
│ [21] Uptown            7  min  │
│      Schedule only             │
│ [Show later departures]        │
├────────────────────────────────┤
│  Map   Stops   Alerts²  More   │
└────────────────────────────────┘
```

Tìm trạm:

```text
┌────────────────────────────────┐
│ Find a stop                    │
│ [🔍 Stop name or number      ] │
│ Saved stops                    │
│  Nicollet Ave & 46th St  51405 │
│ Recent stops                   │
│  Lake St & 1st Ave       17864 │
│ ─ or results ─                 │
│  Nicollet Ave & 46th St  51405 │
│  [18] [46]                     │
└────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | "Biển trạm": ô "STOP" nền `--foreground` chữ `--card`, `h1` tên trạm, dòng phụ "#{code}", trạng thái xe lăn ("Step-free" khi `wheelchairBoarding = 1`, DOC-37 §3.2), "{n} routes" | Nút "Save stop" (lưu `localStorage` `pti.savedStops`, tối đa 10, try/catch; đã lưu thì "Saved") và "View on map" → `/map?c=<lon>,<lat>,16` |
| Tuyến qua trạm | Chip lọc `RouteBadge` + "All" | Lọc bảng giờ đến (`route` trên URL). Xem tuyến trên bản đồ qua bản đồ nhỏ hoặc "View on map" |
| Chiều | `SegmentedControl` theo nhãn chiều (DOC-37 §3.2) | Chỉ khi các chuyến qua trạm có hơn một `directionId` |
| Bảng giờ đến | `ArrivalRow` × N trong `Card` "Departures" | `ul`, mỗi mục là `li` |
| Độ tươi | `FreshnessIndicator axis="event" mode="absolute"` theo `X-Data-As-Of` của E-08 | "Predictions as of …" |
| Gián đoạn | `Callout` `warning` (severity 1) hoặc `danger` (severity 2) | Một callout cho mỗi phần tử `activeDisruptions` (tối đa 3, còn lại "and {n} more"); tiêu đề là tiêu đề alert, câu mô tả "Buses are running up to {delay} late since {time}.", link "See alert" |
| Bản đồ nhỏ (desktop) | `MapCanvas` tĩnh (không tương tác trừ zoom), tải bằng `React.lazy` sau khi E-08 xong | Trạm ở giữa, đường các tuyến qua trạm; bấm → `/map?c=…` |
| Reliability here (desktop; mobile ở cuối trang) | `Card` + dòng mỗi tuyến | E-04 cho từng tuyến qua trạm (tối đa 4) với chiều của tuyến tại trạm, thứ và giờ hiện tại theo `businessNow`; lấy phần tử của trạm này: "avg {delay} · p90 {delay}" + `ConfidenceChip level`; `NONE` → "Not enough history yet" |
| Ô tìm kiếm | `input type="search"` có label ẩn | Debounce 250 ms, tối thiểu 2 ký tự |
| Trạm đã lưu, gần đây | danh sách link | `pti.savedStops`, `pti.recentStops` (5 mục) |

`ArrivalRow`: `RouteBadge size="lg"` + `headsign`; dòng phụ: trạng thái nguồn ("Live" với chấm xanh khi có `realtimeArrival`, tooltip `help.etaRealtime`; "Schedule only" khi `confidence = NONE`; còn lại không ghi), độ trễ ("1 min late", "On time"; DOC-37 §4.4), `ConfidenceChip`. Bên phải: ETA lớn (34 px/600 desktop, 28 px mobile, dạng "6" + "min" nhỏ; "Due"; hoặc giờ tuyệt đối khi ≥ 60 phút) và giờ dự đoán nhỏ bên dưới; "Scheduled {time}" khi khác giờ dự đoán. Dưới danh sách: ghi chú "Times are predictions. Schedule-only trips have no prediction history yet."

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Tìm trạm | E-06 `?q=` (limit 20) | `['stops', 'search', q]` | Không refetch; `staleTime` 5 phút |
| Thông tin trạm, gián đoạn | E-07 | `['stops', stopId, 'detail']` | SSE `disruption.*`, `alert.updated`, `alert.retracted` của tuyến qua trạm → invalidate; 60 s |
| Reliability here | E-04 `?directionId` cho từng tuyến (≤ 4) | `['routes', routeId, 'delay-profile', params]` | `staleTime` 1 giờ; tải sau E-08 |
| Bản đồ nhỏ | E-02 các tuyến qua trạm (desktop) | `['routes', routeId, 'detail']` | `staleTime: Infinity` |
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
| "Show later departures" | `limit=30` | — |
| Chip tuyến, chiều | `route`, `dir` trên URL (`replace`); lọc phía client trên dữ liệu đã tải | — |
| "Save stop" | Thêm/bỏ khỏi `pti.savedStops`; nút đổi "Saved"; toast không có | `localStorage` không dùng được → nút ẩn |
| SSE `alert.created`/`disruption.opened` trên tuyến qua trạm | Callout mới xuất hiện (refetch E-07); vùng `aria-live="polite"` đọc tiêu đề | — |
| Gián đoạn kết thúc | Callout đổi thành `success` "Service on Route {route} is back to normal", ẩn sau 2 phút (UC-03 bước 3) | — |
| `alert.retracted` (FR-09.5) | Callout bị gỡ ngay, không có thông báo | — |
| "See alert" | Mở `/map?route=<routeId>&disruption=<disruptionId>` | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Đầu trang skeleton 2 dòng; danh sách 4 dòng skeleton cao 72 px |
| Empty (giờ đến) | "No upcoming departures" · "No trips are scheduled at this stop in the next 90 min." |
| Empty (tìm kiếm) | "No stops match "{q}"" · "Try the stop number shown on the sign, or part of the street name." |
| Error | E-08 lỗi: danh sách thành `ErrorState`, phần đầu trang và callout vẫn hiện; E-04 lỗi: thẻ "Reliability here" ẩn. E-07 lỗi (không phải 404): `ErrorState variant="block"` cho toàn trang, có "Retry" |
| Stale | `StaleBanner`; nếu `X-Data-As-Of` của E-08 cũ hơn 2 giờ thì "Predictions as of …" có tông `warning` |
| Không có quyền | Không áp dụng |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề trang tìm | "Find a stop" |
| Ô tìm kiếm | Label "Search stops" · placeholder "Stop name or number" |
| Trạm đã lưu, gần đây | "Saved stops" · "Recent stops" · "Clear" · "Save stop" · "Saved" |
| Không tìm thấy | "No stops match "{q}"" · "Try the stop number shown on the sign, or part of the street name." |
| Đầu trang | "STOP" · "#{code}" (mobile: "Stop {code}") · "Step-free" · "{n} routes" · "View on map" · link quay lại "Stops" |
| Danh sách | Tiêu đề "Departures" · "Predictions as of {time}" (mobile: "As of {time}") · "All" · "Show later departures" · "Times are predictions. Schedule-only trips have no prediction history yet." |
| Dòng giờ đến | ETA (DOC-37 §4.4) · "{delay}" · "Scheduled {time}" · "Live" · "Schedule only" |
| Mức tin cậy | "High confidence · {n} trips" · "Medium confidence · {n} trips" · "Low confidence · {n} trips" · "Schedule only" |
| Gián đoạn | tiêu đề alert (từ API) · "Buses are running up to {delay} late since {time}." · "See alert" · "and {n} more" · "Service on Route {route} is back to normal" |
| Reliability here | "Reliability here" · "{weekday}s around {hour}" · "avg {delay} · p90 {delay}" · "Not enough history yet" |
| 404 | "Stop not found" · "Check the stop number, or search for the stop by name." |

`aria-label` của mỗi dòng giờ đến ghép đủ câu cho screen reader: "Route 18 to Downtown Minneapolis, in 6 minutes, 4:25 PM, 1 minute late, high confidence based on 36 trips".

## 9. Tiêu chí nghiệm thu

- **AC-1** Given trạm có chuyến trong 90 phút, When mở `/stops/<id>`, Then mỗi chuyến có ETA, giờ dự đoán và chip mức tin cậy; chuyến không có lịch sử ghi "Schedule only".
- **AC-2** Given 4G mô phỏng (Lighthouse mobile), Then LCP < 2,5 s và JS ban đầu ≤ 200 KB gzip.
- **AC-3** Given gián đoạn `PUBLIC` đang mở trên tuyến qua trạm, Then callout hiện; Given alert bị chuyển sang `ENGINEERING`, Then callout biến mất với anonymous trong ≤ 10 s và vẫn hiện với viewer.
- **AC-4** Given trang mở 5 phút, Then ETA giảm dần mà không reload và danh sách được refetch mỗi 30 s.
- **AC-5** Given `stopId` không tồn tại, Then "Stop not found" kèm ô tìm kiếm.
- **AC-6** Given tìm "nicollet", Then kết quả có trạm chứa "Nicollet", trạm có mã trùng khớp đứng đầu khi tìm bằng mã.
- **AC-7** Given bấm chip tuyến 18, Then chỉ còn chuyến tuyến 18, URL có `route=18`; bản đồ nhỏ không nằm trong JS ban đầu (kiểm bằng `check-bundle`).

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-STOP-01 | Anonymous ở viewport 375 px: `/stops` → gõ "nicollet" → chọn kết quả → danh sách giờ đến; axe | AC-1, AC-6; không vi phạm axe `serious`/`critical` |
| E2E-STOP-02 | Mở trạm trên tuyến 18; `POST /sim/scenarios/disruption` cho tuyến 18; chờ callout; `DELETE /sim/scenario-runs/{runId}` rồi chờ episode đóng | AC-3 (phần hiện), callout "back to normal" |
| E2E-STOP-03 | Mở `/stops/does-not-exist` | AC-5 |

LCP (AC-2) đo bằng Lighthouse CI trong nightly (DOC-44 §10), không phải ca E2E.

## 11. Câu hỏi còn mở

Không có.
