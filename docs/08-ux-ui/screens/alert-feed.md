# Màn hình: Alerts

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34 §5.3, DOC-35, DOC-37 §2.7, §3, DOC-32 (E-11, E-13, E-16, E-18, E-20, E-21), DOC-33, DOC-26 §8–9, DOC-23 §10
> Người dùng chính: P5-12

## 1. Persona, use case, quyền

- **Persona:** PS-2, PS-4 (desktop, trong ca); PS-1 xem cảnh báo công khai.
- **Use case:** UC-03 (thấy gián đoạn), UC-04 (phản hồi gợi ý điều phối từ drawer bunching), UC-05 (bất thường ticketing), FR-14.3 (liên kết sâu).
- **Quyền:** mọi người xem. Anonymous chỉ thấy `PUBLIC` (E-20 lọc sẵn), không có bộ lọc audience, không có cột ack. Viewer thấy mọi audience. Chỉ operator thấy nút "Acknowledge" và nút phản hồi gợi ý.

## 2. URL và search params

`/alerts` — theo DOC-34 §5.2, thêm `alert`:

| Param | Giá trị | Mặc định |
| --- | --- | --- |
| `state` | `open` \| `unacknowledged` \| `all` | `open` |
| `audience` | list `PUBLIC`, `OPERATIONS`, `ENGINEERING` (viewer) | tất cả |
| `type` | list 6 giá trị | tất cả |
| `severity` | list `0`–`2` | tất cả |
| `route` | list `routeId` (≤ 20) | tất cả |
| `window` | `24h` \| `7d` | `24h` |
| `alert` | id alert, mở drawer | — |

- `state=unacknowledged` với anonymous bị bỏ qua (coi như `open`).
- `window` đổi thành `from = now − window` theo **đồng hồ máy** (trục audit, `created_at`).

## 3. Wireframe

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ Alerts                                                                       │
│ [Open ▾] [All audiences ▾] [All types ▾] [All severities ▾] [Routes ▾] [24h ▾]│
│                                ↑ 2 new alerts                                │  NewItemsPill
├───┬──────────────────────────────────────────────┬──────────┬────────┬───────┤
│ ⬣ │ Delays on route 18 northbound                │ PUBLIC   │ 3 min  │ [Ack] │
│   │ Disruption · Route [18]                      │          │ ago    │       │
├───┼──────────────────────────────────────────────┼──────────┼────────┼───────┤
│ ▲ │ Bus bunching on route 5 southbound: …        │ OPS      │ 7 min  │ ✓ op. │
│   │ Bunching · Route [5]                         │          │ ago    │       │
├───┼──────────────────────────────────────────────┼──────────┼────────┼───────┤
│ ▲ │ High refund rate at Nicollet Mall kiosk 2 ✔Resolved                 …     │
└───┴──────────────────────────────────────────────┴──────────┴────────┴───────┘
                              [Load older alerts]
```

Drawer (560 px, gián đoạn):

```text
┌─────────────────────────────────────────┐
│ ⬣ Delays on route 18 northbound     [×] │
│ Disruption · PUBLIC · created 4:01 PM   │
│ ┌─────────┐ ┌─────────┐ ┌─────────┐     │
│ │Avg 3m32s│ │Peak 3m50│ │z 3.98   │     │
│ └─────────┘ └─────────┘ └─────────┘     │
│ Normal for this time: 1 min             │
│ Affected stops: Nicollet & 46th, …      │
│ Likely cause: Traffic  [71% · Medium]   │
│ Since 3:58 PM (21 min)                  │
│ Acknowledged by operator · 4:02 PM      │
│ [Acknowledge]  [Show on map]  [Copy link]│
└─────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Bộ lọc | `MultiSelectFilter` × 4, `RouteSelect`, `Select` cho `state` và `window` | Anonymous: không có "Audience", `state` chỉ "Open"/"All" |
| Pill mục mới | `NewItemsPill` | Khi có alert mới qua SSE mà danh sách đã cuộn khỏi đầu (> 120 px) |
| Danh sách | `DataTable` (không virtual; trang 50 dòng, `useInfiniteQuery`) | Cột: severity (`SeverityBadge` dạng icon, có `aria-label`), "Alert" (tiêu đề + dòng phụ `type` và `RouteBadge`), "Audience" (viewer), "Created" (`RelativeTime axis="audit"`), "Status" (chip "Resolved" `success` khi có `resolvedAt`; "Acknowledged" + người ack khi có), cột hành động (operator) |
| Nút tải thêm | `Button variant="outline"` | "Load older alerts" khi có `nextCursor` |
| Drawer | `DetailDrawer` 560 px | Nội dung theo `type` (§6.1) |
| Vùng thông báo | `div aria-live="polite"` ẩn | Đọc "New alert: {title}"; tối đa một lần mỗi 10 s, còn lại gộp "{n} new alerts" |

Dòng alert chưa ack và chưa resolved có viền trái 3 px màu `--tone-X-solid` của severity (DOC-35 §3). Dòng đã resolved có chữ `muted`.

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Danh sách | E-20 `?state&audience&type&severity&routeId&from&limit=50&cursor` | `['alerts', 'list', filters]` (infinite) | SSE `alert.created`/`alert.updated` → `setQueryData` trang đầu (DOC-26 §9); `alert.retracted` → như §6; 60 s chỉ refetch trang đầu |
| Polling dự phòng | E-20 `?since=<createdAt mới nhất>` | — | Khi SSE hỏng (DOC-26 §8.3): 10 s, chèn kết quả vào đầu |
| Chi tiết gián đoạn | E-13 `refId` | `['insights', 'disruption', 'detail', id]` | Khi mở drawer; `disruption.*` → invalidate |
| Chi tiết bunching + gợi ý | E-11 `refId` | `['insights', 'bunching', 'detail', id]` | Khi mở drawer; `bunching.closed`, `dispatch.suggested` → invalidate |
| Chi tiết ticketing | E-16 `refId` | `['insights', 'ticketing', 'detail', id]` | Khi mở drawer |
| Tên trạm bị ảnh hưởng | E-02 của `routeId` (đã cache ở map/scorecard) | `['routes', routeId, 'detail']` | `staleTime: Infinity` |
| Ack | E-21 | mutation | Xem §6 |
| Kênh SSE | `alerts` (khung đã mở); lọc `routeId` khi `route` có giá trị | — | — |

- Drawer của `DLQ_SEVERE`, `FEED_STALE`, `INFRA` không gọi API thêm; dữ liệu nằm trong `body` (payload Alertmanager, DOC-32 E-80).
- Anonymous mở drawer gián đoạn: E-13 trả góc nhìn công khai; 404 → nội dung "This alert is no longer available." và đóng dòng khỏi danh sách.
- Mở `/alerts?alert=<id>` khi alert không nằm trong trang đã tải: xem §6.2.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi bộ lọc | URL (`replace`); danh sách mờ và giữ dữ liệu cũ trong lúc tải | Anonymous thêm `audience=OPERATIONS` trên URL → 403 → bỏ param, tải lại |
| Bấm dòng / `Enter` | `alert=<id>` (`push`), drawer | — |
| "Acknowledge" (operator) | E-21 lạc quan: dòng đổi thành "Acknowledged by you", badge Alerts giảm 1; **không** toast (DOC-37 §2.7). Với `state=unacknowledged`, dòng mờ đi rồi rời danh sách sau 2 s | Lỗi → hoàn tác, toast "Couldn't acknowledge the alert"; 404 → gỡ dòng |
| "Show on map" / "Open" | Đi tới `link` (DOC-34 §5.3). `INFRA` có `runbook_url` → tab mới, nhãn "Open runbook" | `link` rỗng → ẩn nút |
| "Copy link" | Chép URL tuyệt đối `/alerts?alert=<id>` | — |
| SSE `alert.created` khớp bộ lọc | Danh sách ở đầu: chèn dòng với nền `info` nhạt 3 s. Đã cuộn: tăng `NewItemsPill` | — |
| Bấm `NewItemsPill` | Cuộn về đầu, pill biến mất | — |
| SSE `alert.updated` | Thay dòng theo `id` (severity, tiêu đề, `resolvedAt`, ack); drawer đang mở của alert đó cập nhật | — |
| SSE `alert.retracted` | Query không gồm audience mới của alert (anonymous luôn vậy) → gỡ dòng, đóng drawer của alert đó kèm dòng "This alert is no longer available."; ngược lại → invalidate để thấy audience mới | — |
| `j` / `k`, `Esc` | Chọn dòng kế / trước; đóng drawer (DOC-34 §4.3) | — |

### 6.1 Nội dung drawer theo `type`

Mọi drawer có: `SeverityBadge`, tiêu đề, dòng phụ "{type} · {audience} · created {time}", trạng thái ack/resolved, các nút ở chân ("Acknowledge" cho operator khi chưa ack; nút liên kết; "Copy link").

| `type` | Nguồn | Nội dung |
| --- | --- | --- |
| `DISRUPTION` | E-13 | `StatCard` "Average delay", "Peak delay", "z-score" (viewer); "Normal for this time" (viewer); "Affected stops" (tên, tối đa 5 rồi "and {n} more"); "Likely cause" + `ConfidenceChip` hoặc "Cause: not yet classified" (viewer); "Data issue probability" (viewer); "Since {time} ({duration})" theo `episodeStart` (trục event); khi đóng: "Ended {time} · {closeReason}". Nút "Show on map" |
| `BUNCHING` | E-11 | "Bus {leader} (leading) · Bus {follower} (following)"; "Gap {gap} · scheduled headway {headway}"; "Smallest gap {minGap}"; "Since {time}"; khối gợi ý dùng chung `DispatchSuggestionCard` với Live map (nút "Accept"/"Dismiss" cho operator, E-18, toast "Feedback saved"). Nút "Show on map" |
| `TICKETING_ANOMALY` | E-16 | "Sale point {name}"; "Window {start} – {end}"; "Transactions", "Refunds", "Refund rate", "z-score"; "Category" + `ConfidenceChip`, "Severity (AI)" + `ConfidenceChip`, hoặc "Not yet classified". Nút "Open in ticketing" |
| `DLQ_SEVERE` | `body` | `annotations.summary`, `annotations.description`; bảng `KeyValueList` của `labels` (`source`, `stage`…); "Started {startsAt}". Nút "Open dead letters" |
| `FEED_STALE` | `body` | Như trên; thêm "Last event {relative}" nếu `labels.source` có trong E-60. Nút "Open jobs" |
| `INFRA` | `body` | Như `DLQ_SEVERE`; nút "Open runbook" (tab mới) nếu có `runbook_url`, ngược lại "Open jobs"; link "View in Grafana" từ `generatorURL` nếu có |

### 6.2 Mở drawer từ liên kết

`/alerts?alert=<id>` (từ "Copy link" hoặc toast) mở drawer với dòng tìm thấy trong các trang đã tải. Không thấy → tải thêm tối đa 3 trang; vẫn không thấy → drawer ghi "This alert isn't in the current list. It may be older than the selected period." cùng nút "Show last 7 days" (`window=7d`, `state=all`). E-20 không có lọc theo id, và màn này không thêm endpoint mới.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | 8 dòng skeleton cao 56 px; drawer `PanelSkeleton variant="detail"` |
| Empty (`open`, không lọc) | "No open alerts" · "Alerts appear here when delays, bunching or data problems are detected." |
| Empty (có lọc) | "No alerts match these filters" + "Clear filters" |
| Error | DOC-37 §2.3; đã có dữ liệu thì giữ, dải inline |
| Stale | `StaleBanner` toàn cục; danh sách vẫn dùng được |
| Không có quyền | Không áp dụng (trang công khai). Nút ack không render với viewer; badge "Read-only" ở đầu trang cho viewer |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Alerts" |
| Bộ lọc | "State": "Open", "Unacknowledged", "All" · "Audience" · "All audiences" · "Type" · "All types" · "Severity" · "All severities" · "Routes" · "Period": "Last 24 hours", "Last 7 days" · "Clear filters" |
| Cột | "Alert", "Audience", "Created", "Status" |
| Trạng thái dòng | "Acknowledged by {name}" · "Acknowledged by you" · "Resolved {relative}" |
| Nút | "Acknowledge" · "Show on map" · "Open in ticketing" · "Open dead letters" · "Open jobs" · "Open runbook" · "View in Grafana" · "Copy link" · "Load older alerts" |
| Pill | "{n} new alert" / "{n} new alerts" |
| Thông báo screen reader | "New alert: {title}" · "{n} new alerts" |
| Drawer | "created {time}" · "Since {time} ({duration})" · "Ended {time} · {closeReason}" · "and {n} more" · "Sale point {name}" · "Window {start} – {end}" · "Transactions", "Refunds", "Refund rate" · "Category", "Severity (AI)", "Not yet classified" · "Last event {relative}" · "Started {time}" |
| Không còn | "This alert is no longer available." · "This alert isn't in the current list. It may be older than the selected period." · "Show last 7 days" |
| Lỗi | "Couldn't acknowledge the alert" |
| Empty | "No open alerts" · "Alerts appear here when delays, bunching or data problems are detected." · "No alerts match these filters" |

Nhãn `type`, `audience`, `closeReason`, `likelyCause`, `category`, `trigger` theo DOC-37 §3.

## 9. Tiêu chí nghiệm thu

- **AC-1** Given anonymous, When mở `/alerts`, Then chỉ có alert `PUBLIC`, không có bộ lọc "Audience" và không có nút "Acknowledge".
- **AC-2** Given viewer mở trang, When một gián đoạn mới được mở, Then dòng mới xuất hiện ở đầu trong ≤ 5 s mà không reload (hoặc `NewItemsPill` nếu đã cuộn).
- **AC-3** Given operator, When bấm "Acknowledge", Then dòng đổi trong ≤ 300 ms, badge "Alerts" giảm; bấm lại (tab khác) không đổi người ack.
- **AC-4** Given drawer gián đoạn, When bấm "Show on map", Then tới `/map?route=<routeId>&disruption=<refId>` và panel gián đoạn mở.
- **AC-5** Given alert `INFRA` có `runbook_url`, Then nút "Open runbook" mở tab mới.
- **AC-6** Given alert gián đoạn bị chuyển sang `ENGINEERING`, Then dòng biến mất khỏi danh sách của anonymous trong ≤ 10 s và vẫn còn (audience mới) với viewer.
- **AC-7** Given SSE bị chặn, Then alert mới vẫn xuất hiện qua polling `since` trong ≤ 15 s.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-ALERT-01 | Hai context: anonymous và `operator`. `POST /sim/scenarios/disruption` cho tuyến 18; cả hai chờ dòng mới; operator mở drawer → "Acknowledge" → "Show on map"; axe | AC-1, AC-2, AC-3, AC-4 |
| E2E-ALERT-02 | `operator`: `POST /sim/scenarios/bunching` cho tuyến 5; mở dòng bunching → "Accept" | Toast "Feedback saved"; drawer ghi "Accepted by operator" |
| E2E-ALERT-03 | `viewer`: chặn `/api/v1/stream` bằng `page.route`; kích hoạt kịch bản `ticket-spike` | AC-7; drawer ticketing có "Open in ticketing" |

AC-5 kiểm ở component test (UX-09); AC-6 cần triage-worker (P6) và nằm trong E2E của DOC-24.

## 11. Câu hỏi còn mở

Không có.
