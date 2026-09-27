# Màn hình: Live map

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35 §6, DOC-37, DOC-32 (E-01, E-02, E-05, E-07, E-11, E-12, E-13, E-18), DOC-33 §5.1–5.6, DOC-26 §8–9, ADR-0021
> Người dùng chính: P5-06

## 1. Persona, use case, quyền

- **Persona:** PS-1 (điện thoại), PS-2 (desktop, cả ca), PS-5 (máy chiếu khi demo).
- **Use case:** UC-01, UC-03, UC-04 (bước 2–4), UC-05 (xem trên bản đồ). Journey J-1 bước 4, J-2 bước 2–5.
- **Quyền:** anonymous xem xe, tuyến, gián đoạn công khai. Viewer thêm overlay bunching và gợi ý điều phối, chi tiết gián đoạn đầy đủ. Operator thêm nút "Accept"/"Dismiss" cho gợi ý.

## 2. URL và search params

`/map` — DOC-34 §5.2: `route`, `vehicle`, `bunching`, `disruption`, `c`, `view`.

- `c` được cập nhật khi người dùng dừng kéo/zoom (sự kiện `moveend`, debounce 500 ms, `replace`). Có `vehicle`, `bunching` hoặc `disruption` khi mở trang thì camera ưu tiên đối tượng đó, bỏ qua `c`.
- `bunching` với anonymous bị bỏ qua (không có dữ liệu overlay).
- Chọn xe, cặp bunching hay gián đoạn đều là `push`, nên nút Back đóng panel.

## 3. Wireframe

Desktop (≥ 1024 px):

```text
┌────────────────────────────────────────────────────────────────────────────────┐
│ top bar                                                                         │
├──────────────────────────────┬─────────────────────────────────────────────────┤
│ Routes [18 ×][21 ×] [+ Add]   │                                   [Map|List]    │
│ 48 vehicles on 2 routes       │          ▲ ▲       ◉◉ (fuchsia halo + dash)      │
│ Updated 2 s ago               │       ▲      ▲                                  │
│ ⚠ 1 active disruption  ›      │   (6)       ▲   ○ ○ ○ stops (zoom ≥ 14)          │
│──────────────────────────────│        ▲                                   [+]  │
│ Bus 1203 · Route 18           │                                            [−]  │
│ Downtown Minneapolis          │                                          [⌖]   │
│ Heading to Nicollet & 44th St │                                     [Legend]    │
│ 2 min late · Arriving 4:21 PM │                                                 │
│ Many seats available · 17 mph │                                                 │
│ Updated 3 s ago               │                                                 │
│ [Follow] [Stop details →]     │  © OpenStreetMap contributors · Protomaps       │
└──────────────────────────────┴─────────────────────────────────────────────────┘
```

Mobile: bản đồ toàn màn hình; phần bên trái trở thành bottom sheet (3 nấc: 96 px chỉ có bộ chọn tuyến và số xe, 50%, 90%). Chọn xe thì sheet lên nấc 50%.

Panel bunching (viewer), thay cho panel xe:

```text
Bus bunching · Route 18 Northbound            [×]
Bus 1187 (leading) · Bus 1203 (following)
Gap 1 min 52 s · scheduled headway 10 min
Since 4:12 PM (7 min)
─────────────────────────────────────────────
Suggested action
Hold the following bus at its next stop
[■■■■■■■■□□ 82% confidence]
[Accept]  [Dismiss]            (operator only)
Accepted by operator · 4:14 PM
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Bản đồ | `MapCanvas` + các lớp DOC-35 §6.2 | `aria-label` "Live vehicle map" |
| Bộ chọn tuyến | `RouteSelect` (≤ 20) | Chip `RouteBadge` có nút gỡ |
| Tóm tắt | chữ + `FreshnessIndicator axis="event"` | "{n} vehicles" / "{n} vehicles on {m} routes" |
| Chip gián đoạn | nút `warning` | "{n} active disruption(s)", mở danh sách gián đoạn đang diễn ra |
| Panel xe | `VehiclePanel` | §6 |
| Panel bunching | `BunchingPanel` | viewer; khối gợi ý là `DispatchSuggestionCard`, dùng chung với drawer Alerts |
| Panel gián đoạn | `DisruptionPanel` | nội dung theo role |
| Điều khiển bản đồ | nút zoom, "Locate vehicles" (fit mọi xe đang lọc), "Legend", "Map"/"List" | Vùng chạm 44 px trên mobile |
| Chế độ danh sách | `VehicleList` | Thay bản đồ khi `view=list` |
| Toast gián đoạn/bunching | `sonner` | §6 |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Danh sách tuyến, màu | E-01 | `['routes']` | `staleTime: Infinity`; invalidate khi `feedVersionId` của E-60 đổi |
| Hình tuyến và trạm (tuyến đang chọn) | E-02 cho từng `routeId` | `['routes', routeId, 'detail']` | `staleTime: Infinity` |
| Xe | E-05 `?routeId=…` | `['vehicles', 'live', routeIds]` | SSE `vehicles.batch` (`setQueryData`, DOC-26 §9); polling 5 s khi SSE hỏng; 60 s |
| Overlay bunching | trường `bunching` của E-05 (viewer) | như trên | `bunching.opened`/`closed` |
| Gián đoạn đang diễn ra | E-12 `?status=OPEN&routeId=…&limit=50` | `['insights', 'disruption', { status: 'OPEN', routeIds }]` | `disruption.*`, `alert.retracted` → invalidate |
| Chi tiết gián đoạn | E-13 | `['insights', 'disruption', 'detail', id]` | như trên |
| Chi tiết bunching + gợi ý | E-11 | `['insights', 'bunching', 'detail', id]` | `bunching.closed`, `dispatch.suggested` → invalidate |
| Tên trạm hiện tại của xe (khi tuyến chưa được chọn) | E-07 | `['stops', stopId, 'detail']` | khi mở panel |
| Kênh SSE | E-70 `channels=vehicles,alerts&routeId=…` | — | Không chọn tuyến thì không gửi `routeId` |

- E-05 không có `delaySeconds` mới trong `vehicles.batch`; panel xe hiện độ trễ từ snapshot, và refetch E-05 khi mở panel để có số mới.
- `businessNow` lấy từ `heartbeat` (DOC-33 §5.10) để làm mờ xe cũ hơn 90 s và ẩn xe cũ hơn 5 phút.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Mở trang | Bản đồ nền + tuyến (nếu có `route`) + xe. Không có `c` thì fit bbox của feed | Tile thiếu → nền trơn + dải "Base map unavailable. Run `make tiles` to download it." |
| Chọn tuyến | URL `route=…` (`replace`), vẽ tuyến và trạm, lọc xe, mở lại SSE với `routeId` (debounce 500 ms), fit camera vào các tuyến | E-02 lỗi → tuyến không vẽ, chip tuyến có icon lỗi, tooltip lỗi |
| Bấm xe | `vehicle=<id>` (`push`), panel xe, vòng chọn trên xe | Xe biến mất (mất tín hiệu) khi đang chọn → panel ghi "This bus is no longer reporting its position." |
| "Follow" | Camera bám xe mỗi lần vị trí đổi; nút đổi thành "Stop following" | Kéo bản đồ bằng tay → tự tắt follow |
| "Stop details" | Tới `/stops/<stopId>` | — |
| Bấm xe có halo bunching (viewer) | `bunching=<id>`, panel bunching, camera fit hai xe, xe khác mờ 0,5 | E-11 404 (episode đã bị tính lại) → panel "This bunching episode is no longer available." |
| "Accept" / "Dismiss" (operator) | E-18 lạc quan: nút được chọn đổi trạng thái ngay, toast "Feedback saved"; bấm nút còn lại thì ghi đè (UC-04 3b) | Lỗi → hoàn tác, toast "Couldn't save feedback" (UC-04 E1) |
| Chip gián đoạn | Danh sách gián đoạn (popover desktop, sheet mobile); chọn một → `disruption=<id>`, panel gián đoạn, tô các trạm bị ảnh hưởng, fit camera vào các trạm đó | — |
| SSE `alert.created`, `type = DISRUPTION`, tuyến khớp bộ lọc (hoặc không lọc) | Toast `warning` "Delays on Route {route} (avg ~{n} min late)" với nút "Show" (mở panel gián đoạn) (UC-03) | — |
| SSE `alert.updated` có `resolvedAt` cho gián đoạn đang hiện | Panel và chip đổi thành "Service on Route {route} is back to normal", tự ẩn sau 2 phút | — |
| SSE `alert.created`, `type = BUNCHING` (viewer) | Toast "Bus bunching on route {route}" với nút "Show on map" (`bunching=<refId>`) | — |
| `bunching.closed` khi panel đang mở | Halo biến mất; panel ghi "Ended {relative} · {closeReason}"; lịch sử vẫn xem được | — |
| "Map" / "List" | `view=list`: bảng xe gom theo tuyến (nhóm thu gọn, bấm để mở), cùng thông tin với panel xe | — |
| Phím mũi tên / `+` / `−` khi bản đồ có focus | Pan / zoom (MapLibre) | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Bản đồ nền hiện ngay; chip "Loading vehicles…" ở vùng tóm tắt; panel chi tiết dùng `PanelSkeleton variant="detail"` |
| Empty | Không có xe: "No vehicles in service right now" · "Buses appear here as soon as real-time data arrives." Có lọc tuyến: "No vehicles on route {route} right now" |
| Error | E-05 lỗi lần đầu: chip `danger` "Couldn't load vehicles" + "Retry"; đã có dữ liệu: giữ xe cũ, dải inline (DOC-37 §2.3) |
| Stale | `StaleBanner` toàn cục; xe cũ hơn 90 s mờ đi (DOC-35 §6.2) |
| Không có quyền | Không áp dụng; `bunching=<id>` với anonymous bị bỏ qua |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tóm tắt | "{n} vehicles" · "{n} vehicles on {m} routes" |
| Bộ chọn tuyến | Placeholder "Filter by route" · nút "Add route" · "Clear" · giới hạn: "You can select up to 20 routes." |
| Chip gián đoạn | "{n} active disruption" / "{n} active disruptions" |
| Panel xe | Tiêu đề "Bus {label}" · "Route {route}" · `headsign` · "{currentStatus} {stop}" (DOC-37 §3.2) · "{delay}" (DOC-37 §4.4) · "Arriving {time}" · occupancy · "{speed} mph" · "Updated {relative}" · "Follow" / "Stop following" · "Stop details" |
| Xe mất tín hiệu | "This bus is no longer reporting its position." |
| Panel bunching | Tiêu đề "Bus bunching · Route {route} {direction}" · "Bus {leader} (leading) · Bus {follower} (following)" · "Gap {gap} · scheduled headway {headway}" · "Since {time} ({duration})" · "Suggested action" · "No suggestion available" · "Accept" · "Dismiss" · trạng thái: "Accepted by {name} · {time}" / "Dismissed by {name} · {time}" |
| Panel gián đoạn (mọi người) | Tiêu đề "Delays on route {route} {direction}" · "Average delay {delay}" · "Peak {delay}" · "{n} stops affected" · "Since {time}" |
| Panel gián đoạn (viewer thêm) | "Normal for this time: {baseline}" · "z = {z}" · "Likely cause: {cause}" + `ConfidenceChip` · "Cause: not yet classified" · "Data issue probability: {percent}" |
| Gián đoạn kết thúc | "Service on Route {route} is back to normal" |
| Toast | "Delays on Route {route} (avg ~{n} min late)" + "Show" · "Bus bunching on route {route}" + "Show on map" · "Feedback saved" · "Couldn't save feedback" |
| Điều khiển | "Locate vehicles" · "Legend" · "Map" · "List" · `aria-label` nút zoom: "Zoom in", "Zoom out" |
| Chú giải | "Early", "On time", "Late", "Very late", "No delay data", "Bunched buses", "Affected stop", "Last seen over 90 s ago", "Group of vehicles" |
| Bản đồ nền thiếu | "Base map unavailable. Run `make tiles` to download it." |
| Danh sách | Tiêu đề cột "Route", "Bus", "Destination", "Next stop", "Delay", "Updated"; nhóm: "Route {route} · {n} vehicles" |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given simulator đang chạy, When anonymous mở `/map`, Then trong ≤ 3 s có xe trên bản đồ và vị trí đổi ít nhất mỗi 2 s mà không reload.
- **AC-2** Given zoom < 12, Then xe gom cụm có số; Given zoom ≥ 12, Then từng xe hiện với mũi tên theo `bearing` và màu theo lớp trễ.
- **AC-3** Given chọn tuyến 18, Then URL có `route=18`, chỉ xe tuyến 18 hiện, đường tuyến được vẽ; mở URL đó ở tab mới cho cùng kết quả.
- **AC-4** Given viewer và kịch bản `bunching` đang chạy trên tuyến 18, When episode mở, Then trong ≤ 10 s hai xe có halo và đường nối; anonymous cùng lúc không thấy halo.
- **AC-5** Given operator mở panel bunching có gợi ý, When bấm "Accept", Then nút đổi trạng thái trong < 300 ms và E-11 sau đó trả `operatorFeedback = accepted`.
- **AC-6** Given gợi ý có `lowConfidence = true`, Then chip ghi "Low confidence (…)", nút vẫn bấm được.
- **AC-7** Given SSE bị chặn, Then `RealtimeStatusDot` chuyển "Polling" sau 5 s và vị trí xe vẫn cập nhật mỗi 5 s.
- **AC-8** Given anonymous và kịch bản `disruption` trên tuyến 18, When alert `PUBLIC` được tạo, Then toast "Delays on Route 18 (avg ~… min late)" hiện; "Show" mở panel và tô các trạm bị ảnh hưởng.
- **AC-9** Given `view=list`, Then mọi thông tin của panel xe có trong bảng, điều khiển được bằng bàn phím, axe không có vi phạm nghiêm trọng.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-MAP-01 | Anonymous mở `/map`, chờ xe, chọn tuyến qua `RouteSelect`, bấm một xe, chuyển `view=list`; axe ở cả hai chế độ | AC-1, AC-3, AC-9 |
| E2E-MAP-02 | `POST /sim/scenarios/bunching` trên tuyến có nhiều xe; đăng nhập `operator`; chờ toast bunching → "Show on map" → panel; (từ P6) "Accept" | AC-4, AC-5 (phần gợi ý từ P6; P5 kiểm "No suggestion available") |
| E2E-MAP-03 | Playwright `route.abort` cho `/api/v1/stream`; đo vị trí một xe qua 12 s | AC-7 |
| E2E-MAP-04 | Anonymous mở `/map`; `POST /sim/scenarios/disruption` trên tuyến 18; chờ toast; bấm "Show" | AC-8 |

## 11. Câu hỏi còn mở

Không có.
