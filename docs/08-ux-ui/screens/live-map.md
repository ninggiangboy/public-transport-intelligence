# Màn hình: Live map

> Trạng thái: **Approved** · Cập nhật: 2026-10-11 (thêm chế độ 3D, DR-111) · DOC-36
> Phụ thuộc: DR-88, DR-111, DOC-34, DOC-35 §5.8–5.9, §6, DOC-37, DOC-32 (E-01, E-02, E-05, E-07, E-11, E-12, E-13, E-18), DOC-33 §5.1–5.6, DOC-26 §8–9, ADR-0021
> Người dùng chính: P5-06

## 1. Persona, use case, quyền

- **Persona:** PS-1 (điện thoại), PS-2 (desktop, cả ca), PS-5 (máy chiếu khi demo).
- **Use case:** UC-01, UC-03, UC-04 (bước 2–4), UC-05 (xem trên bản đồ). Journey J-1 bước 4, J-2 bước 2–5.
- **Quyền:** anonymous xem xe, tuyến, gián đoạn công khai. Viewer thêm overlay bunching và gợi ý điều phối, chi tiết gián đoạn đầy đủ. Operator thêm nút "Accept"/"Dismiss" cho gợi ý.

## 2. URL và search params

`/map` — DOC-34 §5.2: `route`, `vehicle`, `bunching`, `disruption`, `c`, `view`, `colour`.

- `c` được cập nhật khi người dùng dừng kéo/zoom (sự kiện `moveend`, debounce 500 ms, `replace`). Có `vehicle`, `bunching` hoặc `disruption` khi mở trang thì camera ưu tiên đối tượng đó, bỏ qua `c`.
- `bunching` với anonymous bị bỏ qua (không có dữ liệu overlay).
- Chọn xe, cặp bunching hay gián đoạn đều là `push`, nên nút Back đóng panel.
- `colour` (`delay` \| `route` \| `crowding`) đổi bằng `replace`; không ghi khi là `delay`.
- `view` (`map` \| `3d` \| `list`) đổi bằng `replace`; không ghi khi là `map`. `3d` chỉ có trên desktop (≥ 1024 px) có WebGL 2; ngoài điều kiện đó `view=3d` hiện như `map` và URL không bị sửa (DR-111).
- Ở `view=3d`, `c` là `lon,lat,zoom,bearing,pitch` (hai số cuối là số nguyên độ). Ở 2D, `c` chỉ ghi `lon,lat,zoom` và bỏ qua hai số cuối nếu có.

## 3. Wireframe

Prototype (DR-88): [Live map — desktop](assets/live-map.html) · [Live map — mobile](assets/live-map-mobile.html).

Desktop (≥ 1024 px). Bản đồ tràn hết vùng nội dung; mọi điều khiển là panel nổi (DOC-34 §4.2):

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ ┌─────────────────────────────────┐   ┌─────────────────────┐ ┌───────────────┐│
│         │ │ 🔍 Search the map            /  │   │● 598 vehicles ·     │ │ [Vehicle]     ││
│         │ │ [18 ×] [21 ×] [+ Route]         │   │  Updated 3 s ago    │ │ [18] Nicollet ││
│         │ │ Colour vehicles by              │   └─────────────────────┘ │ Ave to Downtwn││
│         │ │ [Delay|Route|Crowding]          │                           │ Bus 1742 · NB ││
│         │ │ ⚠ 1 active disruption  ›        │      ▲  ▲    ◉◉           │ 3 min late    ││
│         │ └─────────────────────────────────┘   ▲      ▲  (bunching)    │ Bunching      ││
│         │                                   (6)     ▲   ○○○ stops       │ Speed  Next   ││
│         │                                        ▲                      │ 14 mph 2 min  ││
│         │                                                        [+]    │ Trip progress ││
│         │                                                        [−]    │ ○ 46th St 4:24││
│         │ ┌ Legend ─────────────── ˅ ┐                           [⌖]    │ ● Bus 1742 now││
│         │ │● Early          41       │                           [≡]    │ ○ Lake St     ││
│         │ │● On time       402       │                                  │ ○ 31st St     ││
│         │ │● Late          118       │                                  │ ┌ Suggested ┐ ││
│         │ │● Very late      37       │                                  │ │ action 74%│ ││
│         │ │◉ Bunching        2       │                                  │ └───────────┘ ││
│         │ │! Disruption      1       │  © OpenStreetMap · Protomaps     │[Accept][Dism.]││
│         │ └──────────────────────────┘                                  └───────────────┘│
└─────────┴──────────────────────────────────────────────────────────────────────────────┘
```

Mobile (< 768 px): bản đồ toàn màn hình dưới thanh trên; ô tìm kiếm và chip tuyến nổi ở đỉnh; phía dưới là **bottom sheet** 3 nấc (96 px: tay nắm + "Nearby stops" + số xe; 50%; 90%). Sheet mặc định liệt kê "Nearby stops" (§4.2); chọn xe, cặp bunching hay gián đoạn thì nội dung panel tương ứng thay vào sheet ở nấc 50%.

```text
┌──────────────────────────────┐
│ [🔍 Search stops or routes ] │
│ [Nearby][18 Nicollet][21 …]  │
│        map                   │
│   ▲    ▲   ◉◉                │
├───── ▬▬ ─────────────────────┤
│ Nearby stops  ● updated 3 s  │
│ Nicollet Ave & Lake St 120 m │
│  [18] 2 min  [18] 3 min [21] 7 min │
│  ⚠ Route 18 northbound late  │
│ Lake St & 1st Ave      210 m │
├──────────────────────────────┤
│  Map   Stops   Alerts²  More │
└──────────────────────────────┘
```

Panel bunching (viewer), thay cho panel xe:

```text
[Bunching]                                         [×]
Bunching on Route 18
Northbound near Lake St · detected 4:28 PM
┌ Headway now ┐ ┌ Scheduled ┐ ┌ Smallest gap ┐
│ 0:40        │ │ 8:00      │ │ 0:36         │
└─────────────┘ └───────────┘ └──────────────┘
[18] ━━○━━━●(1738)●(1742)━━━○━━━○  46th St → Franklin Ave
┌ Suggested action ─────────────────── ▮▮▯ 74% ┐
│ Hold the following bus at its next stop      │
│ dispatch-advisor · jev@0.2.0                 │
└──────────────────────────────────────────────┘
[Accept]  [Dismiss]            (operator only)
Accepted by operator · 4:14 PM
[Open alert]
```

Panel gián đoạn:

```text
[21] Delays on route 21 eastbound                  [×]
Hiawatha Ave → Snelling Ave · ⬣ Urgent · Since 3:58 PM
┌ Avg delay ┐ ┌ Peak delay ┐ ┌ Peak z ┐
│ +6m 05s   │ │ +6m 50s    │ │ 3.98   │   (z: viewer)
└───────────┘ └────────────┘ └────────┘
Delay vs normal  [▬▬▬▬▬▬▬▬▬▬▬▬▬|▬▬]  normal 1m 01s
[21] ━○━━●━━●━━●━━●━━○  9 of 22 stops affected
┌ Likely cause ────────────────── ▮▮▯ 68% ┐   (viewer)
│ Traffic                                 │
│ Data issue probability 12%              │
└─────────────────────────────────────────┘
[Open alert]
```

### 3.1 Chế độ 3D (`view=3d`, desktop, DR-111)

Prototype: canvas "PTI Screen Designs", artboard "Live map — 3D" (có tương tác: xe chạy, camera, chọn xe) và "Live map — 3D motion & fallbacks" (luật chuyển động, camera, lối lui). Hai artboard nằm ngoài repo như DR-88.

Bố cục giữ nguyên 2D: panel điều khiển, pill tóm tắt, chú giải, panel chi tiết. Khác biệt: bản đồ nghiêng (mặc định 58°) có toà nhà nổi khối và sương ở chân trời; xe là mô hình nhỏ có nhãn tuyến dạng đĩa đứng trên que; cụm điều khiển camera thay "Locate vehicles"/"List view"; panel xe có thêm khối "Camera".

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ ┌ Search the map ─────────────────┐              [2D|3D]  ┌ Vehicle ──── × ┐│
│         │ │ [18] [21] [+ Route]             │   ▓▓ ▓ ▓▓▓ (sương)    │ [18] Nicollet  ││
│         │ │ Colour vehicles by [Delay|…]    │  ▓▓█ ▓██ ▓  ▓█        │ Bus 1742 · NB  ││
│         │ └─────────────────────────────────┘    ⑱      ㉑          │ +3 min Bunching││
│         │ (● 598 vehicles · Updated 1 s ago)    ╿  ▄▄  ╿            │ Speed Next Occ.││
│         │                                      ▄▆▄  ⑱  ▄▆▄          │ ⌖ Position est-││
│         │                                         ◎▄▆▄  (đang chọn) │ imated · last  ││
│         │                                     ╱  ┊bunching┊  ╲      │ fix 6 s ago    ││
│         │                                   ╱ ░░░ disruption ░░  ╲  │ Camera         ││
│         │ ┌ Legend ──────────── ˄ ┐       ╱                       ╲ │ [Free|Follow|  ││
│         │ │● Early …  ● Very late │                          [▲ 58°▼]│  Chase]        ││
│         │ │◉ Bunching ! Disruption│                          [⊙][↺][↻]│ Trip progress  ││
│         │ │Buildings        [on ] │                          [+][−]   │ …              ││
│         │ │Last GPS fix     [off] │                                   │[Stop details]  ││
│         │ └───────────────────────┘                                   └────────────────┘│
└─────────┴──────────────────────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Bản đồ | `MapCanvas` + các lớp DOC-35 §6.2 | `aria-label` "Live vehicle map"; tràn hết vùng nội dung |
| Panel điều khiển (trái trên) | `MapFloatPanel` rộng 340 px | Ô "Search the map" (tuyến và trạm, như `CommandSearch` nhưng chọn trạm thì bay camera tới trạm và mở popover trạm có link "Stop details"), chip `RouteBadge` có nút gỡ + chip "+ Route" mở `RouteSelect` (≤ 20), `SegmentedControl` "Colour vehicles by", chip gián đoạn |
| Pill tóm tắt (trên, giữa) | `FreshnessIndicator axis="event"` + số xe | "{n} vehicles · Updated {relative}" |
| Chú giải (trái dưới) | `MapFloatPanel` thu gọn được | DOC-35 §6.3: mỗi lớp có số xe hiện tại |
| Điều khiển bản đồ (phải) | `MapControls` | "Zoom in", "Zoom out", "Locate vehicles" (fit mọi xe đang lọc), "List view" (`view=list`). Vùng chạm 44 px trên mobile |
| Panel chi tiết (phải) | `MapFloatPanel` rộng 380 px, cao theo nội dung (tối đa hết chiều cao trừ 16 px lề) | Nội dung theo đối tượng chọn: `VehiclePanel`, `BunchingPanel`, `DisruptionPanel` (§4.1). Không có gì được chọn thì không hiện |
| Chế độ danh sách | `VehicleList` | Thay bản đồ khi `view=list` |
| Nút chế độ (trên phải, cạnh panel chi tiết) | `MapViewSwitch` | Segmented "2D" \| "3D", là link đổi `view`; chỉ desktop. Không có WebGL 2: "3D" disabled kèm tooltip (§8) |
| Điều khiển camera (phải, chỉ 3D) | `CameraControls` | Ba cụm: "Tilt up"/"Tilt down" kèm số độ; la bàn ("Reset bearing to north", kim xoay theo bearing), "Rotate left"/"Rotate right" (±20°); "Zoom in"/"Zoom out". Thay "Locate vehicles" và "List view" của 2D |
| Lớp 3D | `Map3DLayers` (tải bằng `import()` khi vào `view=3d`) | Toà nhà, xe 3D, nhãn đĩa, vòng chọn, bunching, gián đoạn, điểm fix cuối (DOC-35 §6.4) |
| Bottom sheet (mobile) | `BottomSheet` 3 nấc | §4.2 |
| Toast gián đoạn/bunching | `sonner` | §6 |

### 4.1 Nội dung panel

**`VehiclePanel`** (mọi người):

- Đầu: `RouteBadge size="lg"`, `headsign` ("Nicollet Ave to Downtown" = "{routeLongName} to {headsign}" khi có), dòng phụ "Bus {label} · {direction} · trip {tripShortId}"; `DelayBadge variant="chip"`; badge "Bunching" (`tone-bunching`, viewer) khi xe thuộc cặp bunching đang mở.
- Hàng ba số: "Speed" ("{n} mph"), "Next stop" (ETA tới trạm hiện tại theo `stopArrivalAt`, DOC-37 §4.4), "Occupancy" (DOC-37 §3.2; không có dữ liệu thì ẩn ô).
- "Trip progress": `LineStrip` dọc màu tuyến với 1 trạm đã qua, trạm hiện tại/kế tiếp và tối đa 4 trạm sau, lấy thứ tự trạm của chiều tương ứng từ E-02 và vị trí từ `currentStopSequence`. Xe là mốc "Bus {label} · now" giữa hai trạm (`IN_TRANSIT_TO`) hoặc tại trạm (`STOPPED_AT`). Chỉ trạm kế tiếp có giờ ("Arriving {time}" + độ trễ); trạm cuối có nhãn "Terminus"; trạm có tuyến khác đi qua có meta "Transfer to {routes}". Góc thẻ "Updated {relative}".
- Xe thuộc cặp bunching (viewer): `Callout tone="bunching"` "Bus {other} is {gap} behind. Scheduled headway is {headway}." và khối gợi ý (`DispatchSuggestionCard`, như panel bunching).
- Chân: "Follow" / "Stop following", "Stop details" (trạm hiện tại).

**`BunchingPanel`** (viewer): tiêu đề "Bunching on Route {route}", dòng phụ "{direction} near {stop} · detected {time}" (`openStopId`, `episodeStart`); ba `KpiCard` nhỏ "Headway now" (`lastGapSeconds`), "Scheduled" (`scheduledHeadwaySeconds`), "Smallest gap" (`minGapSeconds`), dạng "m:ss"; `LineStrip` ngang từ 2 trạm trước tới 2 trạm sau hai xe; `DispatchSuggestionCard`: `Callout tone="primary"` nhãn "Suggested action", câu hành động đầy đủ (DOC-37 §3.3), `ConfidenceChip`, "{model}", nút "Accept"/"Dismiss" (operator) và trạng thái phản hồi. Chân: "Open alert" (`/alerts?alert=<alertId>`).

**`DisruptionPanel`**: tiêu đề "Delays on route {route} {direction}", dòng phụ "{firstAffectedStop} → {lastAffectedStop}" + `SeverityBadge` + "Since {time}". `KpiCard` nhỏ "Avg delay" (`currentAvgDelaySeconds`, "+6m 05s"), "Peak delay", "Peak z" (viewer). "Delay vs normal": thanh ngang so `currentAvgDelaySeconds` với `baselineMeanSeconds` ± `baselineStddevSeconds` (viewer; anonymous không có baseline nên ẩn). `LineStrip` ngang với đoạn trạm bị ảnh hưởng tô `--delay-very-late`, "{n} of {total} stops affected". Viewer thêm khối AI `Callout tone="primary"` "Likely cause" + `ConfidenceChip` + "Data issue probability {percent}", hoặc "Cause: not yet classified". Chân: "Open alert".

### 4.2 Bottom sheet mobile

- Nấc 96 px: tay nắm, "Nearby stops", `FreshnessIndicator`.
- "Nearby stops": xin quyền vị trí khi người dùng bấm chip "Nearby" (không hỏi tự động). Có vị trí → E-06 `bbox` quanh vị trí (≈ 400 m), sắp theo khoảng cách, tối đa 5 trạm; mỗi trạm: tên, khoảng cách ("120 m"), tối đa 3 giờ đến gần nhất dạng `RouteBadge` + "{n} min" (E-08 `limit=3`), dải cảnh báo nếu trạm có gián đoạn. Không có quyền vị trí → danh sách "Saved" và "Recent stops" (`localStorage`, `screens/stop-detail.md`).
- Chip trên đỉnh: "Nearby", các tuyến đã chọn (`RouteBadge` + tên ngắn), "Saved".

### 4.3 Khác biệt ở chế độ 3D

- **`VehiclePanel`** thêm, ngay dưới hàng ba số: dòng "Position estimated between GPS fixes · last fix {n} s ago" (icon ghim, `--muted-foreground`; `n` là tuổi của fix cuối theo `businessNow`). Nút "Follow"/"Stop following" của 2D được thay bằng khối **"Camera"**: `SegmentedControl` "Free", "Follow", "Chase" và một dòng mô tả chế độ đang chọn (§8). Mọi phần khác giữ nguyên.
- **Chú giải** thêm hai dòng có `Switch`: "Buildings" (bật mặc định) và "Last GPS fix" (tắt mặc định). Hai lựa chọn này lưu ở `localStorage` (`pti.map3d.layers`), không lên URL.
- **Chuyển động:** vị trí xe vẽ là ước tính giữa hai fix theo DOC-35 §6.4; panel, danh sách và chú giải vẫn dùng số từ dữ liệu. Camera "Follow"/"Chase" bám vị trí đang vẽ.
- Bunching, gián đoạn, chọn xe, toast và phân quyền giống 2D; chỉ cách vẽ khác (DOC-35 §6.4).

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
| Trạm của chiều xe đang chạy (Trip progress, tên trạm hiện tại) | E-02 của tuyến của xe | `['routes', routeId, 'detail']` | `staleTime: Infinity`; tải khi mở panel nếu tuyến chưa được chọn |
| Trạm của tuyến (lớp `stops`, "Transfer to …") | E-06 `?routeId=…&limit=500` | `['stops', 'route', routeId]` | `staleTime` 1 giờ |
| Alert gián đoạn/bunching đang mở (toast, "Open alert") | E-20 `?state=open&type=DISRUPTION,BUNCHING&routeId=…` | `['alerts', 'list', {…}]` | `alert.*` vá cache như mọi danh sách alert; id mới xuất hiện sau lần tải đầu là toast (DOC-26 §8.4) |
| Kênh SSE | E-70 `channels=vehicles,alerts&routeId=…` | — | Không chọn tuyến thì không gửi `routeId` |
| Toà nhà (3D) | Lớp `buildings` của PMTiles (ADR-0021), không qua API | — | Tĩnh; `height` thiếu thì 8 m (DR-111) |
| Shape để nội suy (3D) | E-02 của các tuyến có xe trong khung nhìn, chỉ khi zoom ≥ 14, tối đa 20 tuyến | `['routes', routeId, 'detail']` | `staleTime: Infinity`; tuyến chưa có shape thì ước tính theo `bearing` |

- E-05 không có `delaySeconds` mới trong `vehicles.batch`; panel xe hiện độ trễ từ snapshot, và refetch E-05 khi mở panel để có số mới.
- `businessNow` lấy từ `heartbeat` (DOC-33 §5.10) để làm mờ xe cũ hơn 90 s và ẩn xe cũ hơn 5 phút.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Mở trang | Bản đồ nền + tuyến (nếu có `route`) + xe. Không có `c` thì fit bbox của feed. Chú giải mở trên desktop, đóng trên mobile | Tile thiếu → nền trơn + dải "Base map unavailable. Run `make tiles` to download it." |
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
| "Colour vehicles by" | `colour` trên URL; lớp `vehicles` đổi `icon-color`, chú giải đổi danh mục và số đếm | — |
| "List view" / "Map view" | `view=list`: bảng xe gom theo tuyến (nhóm thu gọn, bấm để mở), cùng thông tin với panel xe | — |
| Kéo tay nắm bottom sheet (mobile) | Chuyển nấc 96 px / 50% / 90%; bàn phím: nút tay nắm có `aria-label` "Resize panel", `Enter` chuyển nấc kế | — |
| Chip "Nearby" (mobile) | Xin quyền vị trí, tải "Nearby stops" | Từ chối → "Location is off. Showing saved and recent stops." |
| Phím mũi tên / `+` / `−` khi bản đồ có focus | Pan / zoom (MapLibre) | — |
| "3D" (`MapViewSwitch`) | `view=3d` (`replace`); camera bay tới pitch 58° trong 600 ms, giữ tâm, zoom, bearing; tải `Map3DLayers` lần đầu (pill tóm tắt ghi "Loading 3D…" khi đang tải) | Không có WebGL 2: nút disabled. Tải chunk lỗi: toast "Couldn't load the 3D view." + "Retry", vẫn ở 2D |
| "2D" | Bỏ `view`; camera về pitch 0, bearing 0 trong 600 ms; xe về cách vẽ 2D | — |
| Kéo chuột phải, Ctrl + kéo, Shift + phím mũi tên (3D) | Xoay và nghiêng; nghiêng trong 0–70°. Thả chuột thì cập nhật `c` như `moveend` | — |
| "Tilt up"/"Tilt down", "Rotate left"/"Rotate right", la bàn (3D) | ±10° nghiêng, ±20° xoay, la bàn đưa bearing về 0; đang "Chase" thì chuyển "Free" trước | — |
| "Camera" → "Free" / "Follow" / "Chase" (3D) | Free: camera đứng yên. Follow: tâm camera bám xe mỗi khung hình. Chase: sau xe, pitch 64°, hướng đi chỉ lên. Kéo bản đồ → "Free" | Xe biến mất khi đang Follow/Chase → "Free" tại chỗ, panel như 2D |
| Công tắc "Buildings" / "Last GPS fix" (3D) | Bật/tắt lớp toà nhà / điểm fix cuối của mọi xe | — |
| Khung hình < 30 fps liên tục 5 s (3D) | Tắt toà nhà, toast "3D was running slowly, so buildings are off." + "Turn back on" | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Bản đồ nền hiện ngay; pill tóm tắt ghi "Loading vehicles…"; panel chi tiết dùng `PanelSkeleton variant="detail"` |
| Empty | Không có xe: "No vehicles in service right now" · "Buses appear here as soon as real-time data arrives." Có lọc tuyến: "No vehicles on route {route} right now" |
| Error | E-05 lỗi lần đầu: pill tóm tắt tông `danger` "Couldn't load vehicles" + "Retry"; đã có dữ liệu: giữ xe cũ, pill chuyển dạng inline (DOC-37 §2.3) |
| Stale | `StaleBanner` toàn cục; xe cũ hơn 90 s mờ đi (DOC-35 §6.2) |
| Không có quyền | Không áp dụng; `bunching=<id>` với anonymous bị bỏ qua |
| 3D không dùng được | Không có WebGL 2: nút "3D" disabled, tooltip "3D needs a browser with WebGL 2."; màn < 1024 px: không có nút; `view=3d` hiện như 2D |
| 3D chậm | Như §6: tắt toà nhà và toast; công tắc "Buildings" về tắt |
| Reduced motion (3D) | Không ước tính, không easing: xe đổi chỗ theo từng fix (mờ 150 ms); camera cắt thay vì bay; "Follow"/"Chase" nhảy theo fix |
| Tab bị ẩn (3D) | Dừng vòng vẽ; quay lại thì xe nhảy tới vị trí ước tính hiện tại |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tóm tắt | "{n} vehicles · Updated {relative}" · "{n} vehicles on {m} routes" |
| Panel điều khiển | Placeholder "Search the map" · "+ Route" · "Colour vehicles by" · "Delay", "Route", "Crowding" |
| Bộ chọn tuyến | Placeholder "Filter by route" · nút "Add route" · "Clear" · giới hạn: "You can select up to 20 routes." |
| Chip gián đoạn | "{n} active disruption" / "{n} active disruptions" |
| Panel xe | "{longName} to {headsign}" · "Bus {label} · {direction} · trip {trip}" · "{delay}" (DOC-37 §4.4) · "Bunching" · "Speed", "{n} mph" · "Next stop" · "Occupancy" · "Trip progress" · "Bus {label} · now" · "Arriving {time}" · "Transfer to {routes}" · "Terminus" · "Updated {relative}" · "Bus {other} is {gap} behind. Scheduled headway is {headway}." · "Follow" / "Stop following" · "Stop details" |
| Xe mất tín hiệu | "This bus is no longer reporting its position." |
| Panel bunching | "Bunching on Route {route}" · "{direction} near {stop} · detected {time}" · "Headway now", "Scheduled", "Smallest gap" · "Bus {leader} (leading) · Bus {follower} (following)" · "Suggested action" · "No suggestion available" · "Accept" · "Dismiss" · trạng thái: "Accepted by {name} · {time}" / "Dismissed by {name} · {time}" · "Open alert" |
| Panel gián đoạn (mọi người) | Tiêu đề "Delays on route {route} {direction}" · "{from} → {to}" · "Since {time}" · "Avg delay", "Peak delay" · "{n} of {total} stops affected" · "Open alert" |
| Panel gián đoạn (viewer thêm) | "Peak z" · "Delay vs normal" · "normal {baseline}" · "Likely cause" + `ConfidenceChip` · "Cause: not yet classified" · "Data issue probability {percent}" |
| Gián đoạn kết thúc | "Service on Route {route} is back to normal" |
| Toast | "Delays on Route {route} (avg ~{n} min late)" + "Show" · "Bus bunching on route {route}" + "Show on map" · "Feedback saved" · "Couldn't save feedback" |
| Điều khiển | "Locate vehicles" · "Legend" · "List view" · "Map view" · `aria-label` nút zoom: "Zoom in", "Zoom out" |
| Mobile | Placeholder "Search stops or routes" · "Nearby" · "Saved" · "Nearby stops" · "{n} m" · "Location is off. Showing saved and recent stops." · `aria-label` "Resize panel" |
| Chú giải | "Legend" · chế độ Delay: "Early", "On time", "Late (5–10 min)", "Very late (10+ min)", "No delay data" (ngưỡng DOC-35 §3.4) · chế độ Crowding: nhãn `occupancyStatus` (DOC-37 §3.2) · "Bunching", "Disruption", "Last seen over 90 s ago", "Group of vehicles" |
| Bản đồ nền thiếu | "Base map unavailable. Run `make tiles` to download it." |
| Danh sách | Tiêu đề cột "Route", "Bus", "Destination", "Next stop", "Delay", "Updated"; nhóm: "Route {route} · {n} vehicles" |
| Chế độ 3D | `aria-label` "Map view" · "2D", "3D" · tooltip "3D needs a browser with WebGL 2." · "Loading 3D…" · "Couldn't load the 3D view." + "Retry" · `aria-label` "Tilt up", "Tilt down", "Reset bearing to north", "Rotate left", "Rotate right" · "{n}°" · "Camera" · "Free", "Follow", "Chase" · "Drag to pan. Right-drag or Ctrl + drag to rotate and tilt." · "Keeps {vehicle} in the centre as it moves. Dragging the map switches back to Free." · "Rides behind {vehicle} with the direction of travel pointing up." (`{vehicle}` = "bus {label}" hoặc "train {label}") · "Position estimated between GPS fixes · last fix {n} s ago" · chú giải "Buildings", "Last GPS fix" · toast "3D was running slowly, so buildings are off." + "Turn back on" · `aria-label` bản đồ "3D vehicle map" · đĩa nhãn: "{Bus|Train} {label}, route {route}, {delay}" · thẻ xe chọn "{Bus|Train} {label}" / "{delay} · {n} mph" |

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
- **AC-10** Given `colour=route`, Then xe tô theo màu tuyến, chú giải liệt kê các tuyến đang hiện kèm số xe; copy URL sang tab khác cho cùng chế độ.
- **AC-11** Given bấm một xe, Then "Trip progress" có trạm kế tiếp đúng `currentStopSequence` của E-05 và giờ "Arriving …" theo `stopArrivalAt`.
- **AC-12** Given desktop có WebGL 2, When bấm "3D", Then URL có `view=3d`, bản đồ nghiêng 58° với toà nhà nổi khối trong ≤ 1 s và xe vẫn cập nhật; F5 giữ chế độ và camera (`c` có bearing, pitch).
- **AC-13** Given `view=3d` và xe nhận fix mỗi 10–30 s, Then marker di chuyển liên tục giữa các fix, không lùi dọc tuyến, và không nhảy quá 5 px giữa hai khung hình liên tiếp trừ khi fix lệch hơn 150 m (khi đó mờ đi rồi hiện lại).
- **AC-14** Given chọn một xe và "Follow", Then xe nằm giữa khung nhìn khi di chuyển; kéo bản đồ → khối Camera chuyển "Free".
- **AC-15** Given `prefers-reduced-motion: reduce` và `view=3d`, Then marker chỉ đổi chỗ khi có fix, camera không bay.
- **AC-16** Given trình duyệt không có WebGL 2, hoặc màn hình < 1024 px, Then nút "3D" disabled hoặc không có, và mở `view=3d` cho bản đồ 2D.
- **AC-17** Given 600 xe, `view=3d`, pitch 60° trên máy dev (cấu hình máy ghi trong ADR của S-07), Then trung bình ≥ 50 fps trong 30 s; nếu dưới 30 fps liên tục 5 s thì toà nhà tắt và toast hiện.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-MAP-01 | Anonymous mở `/map`, chờ xe, chọn tuyến qua `RouteSelect`, bấm một xe, chuyển `view=list`; axe ở cả hai chế độ | AC-1, AC-3, AC-9 |
| E2E-MAP-02 | `POST /sim/scenarios/bunching` trên tuyến có nhiều xe; đăng nhập `operator`; chờ toast bunching → "Show on map" → panel; (từ P6) "Accept" | AC-4, AC-5 (phần gợi ý từ P6; P5 kiểm "No suggestion available") |
| E2E-MAP-03 | Playwright `route.abort` cho `/api/v1/stream`; đo vị trí một xe qua 12 s | AC-7 |
| E2E-MAP-04 | Anonymous mở `/map`; `POST /sim/scenarios/disruption` trên tuyến 18; chờ toast; bấm "Show" | AC-8 |
| E2E-MAP-05 | Mở `/map`, bấm "3D"; kiểm URL, reload; chọn xe, "Follow", kéo bản đồ → "Free"; axe | AC-12, AC-14 |
| E2E-MAP-06 | `view=3d`; đọc vị trí màn hình của một xe qua hook test mỗi khung hình trong 30 s; với `reducedMotion: 'reduce'` lặp lại | AC-13, AC-15 |
| E2E-MAP-07 | Chromium với `--disable-webgl2` (hoặc viewport 800 px) mở `/map?view=3d` | AC-16 |

AC-17 đo tay trên bản build (Performance panel của Chrome) ở S-07 và khi xong P5-18; kết quả ghi vào ADR của S-07.

## 11. Câu hỏi còn mở

- Cách vẽ xe 3D (custom layer của MapLibre hay deck.gl) chốt sau spike S-07 (P5-17, DR-111). Các ngưỡng chuyển động (1 s, 20 s, 150 m, 50 m) là đề xuất từ prototype; S-07 và P5-18 có thể chỉnh.
