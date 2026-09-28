# Màn hình: Alerts

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34 §4.2, §5.3, DOC-35, DOC-37 §2.7, §3, DOC-32 (E-11, E-13, E-16, E-18, E-20, E-21), DOC-33, DOC-26 §8–9, DOC-23 §10
> Người dùng chính: P5-12

## 1. Persona, use case, quyền

- **Persona:** PS-2, PS-4 (desktop, trong ca); PS-1 xem cảnh báo công khai.
- **Use case:** UC-03 (thấy gián đoạn), UC-04 (phản hồi gợi ý điều phối từ chi tiết bunching), UC-05 (bất thường ticketing), FR-14.3 (liên kết sâu).
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
| `alert` | id alert, mở khung chi tiết | — |

- `state=unacknowledged` với anonymous bị bỏ qua (coi như `open`).
- `window` đổi thành `from = now − window` theo **đồng hồ máy** (trục audit, `created_at`).

## 3. Wireframe

Prototype (DR-88): [Alerts](assets/alert-feed.html).

Desktop (≥ 1024 px), bố cục danh sách + khung chi tiết (`SplitView`, DOC-34 P-11):

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ Alerts                                                                        │
│         │ [Open] [Unacknowledged 3] [All]                                               │
│         │ (Severity ▾) (Type ▾) (Route ▾) (Audience ▾) (Last 24 hours ▾)                │
│         ├───────────────────────────────┬──────────────────────────────────────────────┤
│         │   ↑ 2 new alerts · show       │ ⬣ Urgent  Service disruption · started 3:58 PM · 34 min │
│         │ ┌───────────────────────────┐ │ Delays on route 21 eastbound                 │
│         │ │⚠ Delays on route 21 EB  ● │ │ [21] Hiawatha Ave → Snelling Ave              │
│         │ │  [21] Hiawatha → Snelling │ │          [Show on map] [Acknowledge] [⧉]      │
│         │ │  Urgent · 34 min          │ │ Average delay │ Peak delay │ Stops │ z-score │
│         │ └───────────────────────────┘ │ +6m 05s       │ +6m 50s    │ 9     │ 3.98    │
│         │  ◉ Bus bunching on route 18 ● │ Where                                         │
│         │    [18] NB · gap 0:40         │ [21] ━○━━●━━●━━●━━●━━○  9 of 22 stops          │
│         │    Needs attention · 4 min    │ Delay vs normal [▬▬▬▬▬▬▬▬▬▬|▬▬] normal 1m 01s │
│         │  ▦ High refund rate at kiosk  │ ┌ AI analysis ───────────────── ▮▮▯ 68% ┐    │
│         │    SP-0142 · refunds 48%      │ │ Likely cause: Traffic                  │    │
│         │    Informational · 17 min     │ │ Data issue probability 12%             │    │
│         │                               │ │ disruption-enricher · jev@0.2.0        │    │
│         │                               │ └────────────────────────────────────────┘    │
│         │  [Load older alerts]          │ Activity                                      │
│         │                               │ ● Acknowledged by operator       4:02 PM      │
│         │                               │ ● Cause classified (68%)         4:06 PM      │
│         │                               │ ● Detected automatically         3:58 PM      │
└─────────┴───────────────────────────────┴──────────────────────────────────────────────┘
```

Dưới 1024 px: chỉ có danh sách; chọn một alert mở chi tiết thành trang chồng lên (nút "Back to alerts").

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Tab trạng thái | shadcn `Tabs` cho `state`: "Open", "Unacknowledged" (kèm số từ badge của khung), "All" | Anonymous: không có "Unacknowledged" |
| Bộ lọc | Chip `MultiSelectFilter` "Severity", "Type", "Audience" (viewer), `RouteSelect` dạng chip "Route", chip "Period" | Chip đang áp ghi giá trị ("Severity: Urgent") |
| Pill mục mới | `NewItemsPill` | Khi có alert mới qua SSE mà danh sách đã cuộn khỏi đầu (> 120 px) hoặc đang xem chi tiết |
| Danh sách | `SplitView` cột trái 400 px; `useInfiniteQuery` trang 50 | Mỗi mục: ô icon 32 px theo `type` (tông severity; `BUNCHING` dùng `tone-bunching`), tiêu đề, dòng phụ (`RouteBadge sm` + tóm tắt từ `body`), `SeverityBadge` dạng ngắn + tuổi (`RelativeTime axis="audit"`), chấm `--primary` khi chưa ack (đăng nhập), chữ `muted` khi đã resolved. Mục đang chọn là thẻ trắng có viền |
| Nút tải thêm | `Button variant="outline"` | "Load older alerts" khi có `nextCursor` |
| Khung chi tiết | cột phải | §6.1; không có alert nào được chọn → `EmptyState` "Select an alert to see details" |
| Vùng thông báo | `div aria-live="polite"` ẩn | Đọc "New alert: {title}"; tối đa một lần mỗi 10 s, còn lại gộp "{n} new alerts" |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Danh sách | E-20 `?state&audience&type&severity&routeId&from&limit=50&cursor` | `['alerts', 'list', filters]` (infinite) | SSE `alert.created`/`alert.updated` → `setQueryData` trang đầu (DOC-26 §9); `alert.retracted` → như §6; 60 s chỉ refetch trang đầu |
| Polling dự phòng | E-20 `?since=<createdAt mới nhất>` | — | Khi SSE hỏng (DOC-26 §8.3): 10 s, chèn kết quả vào đầu |
| Chi tiết gián đoạn | E-13 `refId` | `['insights', 'disruption', 'detail', id]` | Khi mở chi tiết; `disruption.*` → invalidate |
| Chi tiết bunching + gợi ý | E-11 `refId` | `['insights', 'bunching', 'detail', id]` | Khi mở chi tiết; `bunching.closed`, `dispatch.suggested` → invalidate |
| Chi tiết ticketing | E-16 `refId` | `['insights', 'ticketing', 'detail', id]` | Khi mở chi tiết |
| Tên và thứ tự trạm ("Where") | E-02 của `routeId` (đã cache ở map/scorecard) | `['routes', routeId, 'detail']` | `staleTime: Infinity` |
| Ack | E-21 | mutation | Xem §6 |
| Kênh SSE | `alerts` (khung đã mở); lọc `routeId` khi `route` có giá trị | — | — |

- Chi tiết của `DLQ_SEVERE`, `FEED_STALE`, `INFRA` không gọi API thêm; dữ liệu nằm trong `body` (payload Alertmanager, DOC-32 E-80).
- Anonymous mở chi tiết gián đoạn: E-13 trả góc nhìn công khai; 404 → nội dung "This alert is no longer available." và đóng dòng khỏi danh sách.
- Mở `/alerts?alert=<id>` khi alert không nằm trong trang đã tải: xem §6.2.
- Không có alert nào trên URL thì desktop tự chọn alert đầu danh sách (`replace`), để khung chi tiết không trống khi mở trang.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi bộ lọc | URL (`replace`); danh sách mờ và giữ dữ liệu cũ trong lúc tải | Anonymous thêm `audience=OPERATIONS` trên URL → 403 → bỏ param, tải lại |
| Bấm mục / `Enter` | `alert=<id>` (`push`), khung chi tiết | — |
| "Acknowledge" (operator) | E-21 lạc quan: nút đổi thành "Acknowledged by you" (tông `success`), chấm chưa đọc trên mục biến mất, badge Alerts giảm 1, Activity thêm dòng; **không** toast (DOC-37 §2.7). Với `state=unacknowledged`, dòng mờ đi rồi rời danh sách sau 2 s | Lỗi → hoàn tác, toast "Couldn't acknowledge the alert"; 404 → gỡ dòng |
| "Show on map" / "Open" | Đi tới `link` (DOC-34 §5.3). `INFRA` có `runbook_url` → tab mới, nhãn "Open runbook" | `link` rỗng → ẩn nút |
| "Copy link" | Chép URL tuyệt đối `/alerts?alert=<id>` | — |
| SSE `alert.created` khớp bộ lọc | Danh sách ở đầu: chèn dòng với nền `info` nhạt 3 s. Đã cuộn: tăng `NewItemsPill` | — |
| Bấm `NewItemsPill` | Cuộn về đầu, pill biến mất | — |
| SSE `alert.updated` | Thay mục theo `id` (severity, tiêu đề, `resolvedAt`, ack); khung chi tiết đang mở của alert đó cập nhật | — |
| SSE `alert.retracted` | Query không gồm audience mới của alert (anonymous luôn vậy) → gỡ mục; khung chi tiết của alert đó thay bằng "This alert is no longer available."; ngược lại → invalidate để thấy audience mới | — |
| `j` / `k`, `Esc` | Chọn mục kế / trước (khung chi tiết theo); `Esc` bỏ chọn (DOC-34 §4.3) | — |

### 6.1 Khung chi tiết theo `type`

Mọi alert có cùng khung:

1. **Đầu:** `SeverityBadge`, dòng phụ "{type} · started {time} · {age}" (audience ở tooltip với viewer), `h2` tiêu đề, `RouteBadge` + "Where" một dòng; bên phải là nút liên kết (theo `type`), "Acknowledge" (operator, khi chưa ack) hoặc badge "Acknowledged by {name}", nút icon "Copy link".
2. **Hàng số:** 4 ô số liền nhau ngăn bằng vạch mảnh (`KpiCard` dạng phẳng).
3. **Where:** `LineStrip` ngang (chỉ `DISRUPTION`, `BUNCHING`).
4. **So sánh:** một khối nhỏ đặt số hiện tại cạnh mức bình thường.
5. **AI analysis:** `Callout tone="primary"` (viewer; anonymous chỉ thấy khi `type = DISRUPTION` và không có phần AI).
6. **Activity:** `ActivityTimeline` dựng từ các mốc có trong dữ liệu (mới nhất trên): "Detected automatically" (`createdAt`), "Cause classified ({percent})" (`enrichedAt`), "Suggestion ready ({percent})" (`suggestion.createdAt`), "Feedback: {operatorFeedback} by {name}", "Acknowledged by {name}" (`acknowledgedAt`), "Resolved" (`resolvedAt`); episode đóng thêm "Ended · {closeReason}".

| `type` | Nguồn | Hàng số | Where / So sánh | AI analysis | Nút liên kết |
| --- | --- | --- | --- | --- | --- |
| `DISRUPTION` | E-13 | "Average delay", "Peak delay", "Stops affected", "z-score" (viewer) | Đoạn trạm bị ảnh hưởng tô `--delay-very-late` trên chiều của tuyến, "{n} of {total} stops"; "Delay vs normal" (`baselineMeanSeconds` ± `baselineStddevSeconds`, viewer) | "Likely cause" + `ConfidenceChip` hoặc "Cause: not yet classified"; "Data issue probability {percent}"; "{modelVersion}" | "Show on map" |
| `BUNCHING` | E-11 | "Headway now", "Scheduled headway", "Smallest gap", "Duration" | Hai xe trên đoạn từ 2 trạm trước tới 2 trạm sau, "Bus {leader} (leading) · Bus {follower} (following)"; thanh "Gap vs headway" | "Suggested action" (`DispatchSuggestionCard` dùng chung với Live map: câu hành động, `ConfidenceChip`, nút "Accept"/"Dismiss" cho operator, E-18, toast "Feedback saved") | "Show on map" |
| `TICKETING_ANOMALY` | E-16 | "Refund rate", "Refunds", "Transactions", "z-score" | "Previous windows" (cột, cửa sổ hiện tại tông `warning`) + "Normal: {mean} ± {stddev} per window" | "Classification": category + `ConfidenceChip`, severity (AI) + `ConfidenceChip`, hoặc "Not yet classified" | "Open in ticketing" |
| `DLQ_SEVERE` | `body` | Không có; thay bằng `annotations.summary` và `annotations.description` | `KeyValueList` của `labels` (`source`, `stage`…); "Started {startsAt}" | — | "Open dead letters" |
| `FEED_STALE` | `body` | Như trên | Như trên; thêm "Last event {relative}" nếu `labels.source` có trong E-60 | — | "Open pipeline" |
| `INFRA` | `body` | Như trên | Như `DLQ_SEVERE`; link "View in Grafana" từ `generatorURL` nếu có | — | "Open runbook" (tab mới) nếu có `runbook_url`, ngược lại "Open pipeline" |

### 6.2 Mở chi tiết từ liên kết

`/alerts?alert=<id>` (từ "Copy link" hoặc toast) mở chi tiết với mục tìm thấy trong các trang đã tải. Không thấy → tải thêm tối đa 3 trang; vẫn không thấy → khung chi tiết ghi "This alert isn't in the current list. It may be older than the selected period." cùng nút "Show last 7 days" (`window=7d`, `state=all`). E-20 không có lọc theo id, và màn này không thêm endpoint mới.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | 8 mục skeleton cao 72 px; khung chi tiết `PanelSkeleton variant="detail"` |
| Empty (`open`, không lọc) | "No open alerts" · "Alerts appear here when delays, bunching or data problems are detected." |
| Empty (có lọc) | "No alerts match these filters" + "Clear filters" |
| Error | DOC-37 §2.3; đã có dữ liệu thì giữ, dải inline |
| Stale | `StaleBanner` toàn cục; danh sách vẫn dùng được |
| Không có quyền | Không áp dụng (trang công khai). Nút ack không render với viewer; badge "Read-only" ở `PageHeader` cho viewer |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Alerts" |
| Tab | "Open", "Unacknowledged", "All" |
| Bộ lọc | "Severity" · "Type" · "Route" · "Audience" · "Period": "Last 24 hours", "Last 7 days" · "{filter}: {value}" · "Clear filters" |
| Mục danh sách | Nhãn severity ngắn "High" (2), "Medium" (1), "Low" (0) · "{age}" · `aria-label` "Unread" cho chấm chưa ack |
| Trạng thái | "Acknowledged by {name}" · "Acknowledged by you" · "Resolved {relative}" |
| Nút | "Acknowledge" · "Show on map" · "Open in ticketing" · "Open dead letters" · "Open pipeline" · "Open runbook" · "View in Grafana" · "Copy link" · "Load older alerts" · "Back to alerts" |
| Pill | "{n} new alert · show" / "{n} new alerts · show" |
| Thông báo screen reader | "New alert: {title}" · "{n} new alerts" |
| Chi tiết | "{type} · started {time} · {age}" · "Where" · "{n} of {total} stops" · "Average delay", "Peak delay", "Stops affected", "z-score" · "Headway now", "Scheduled headway", "Smallest gap", "Duration" · "Refund rate", "Refunds", "Transactions" · "Delay vs normal" · "Gap vs headway" · "Previous windows" · "normal {value}" · "AI analysis" · "Likely cause" · "Suggested action" · "Classification" · "Category", "Severity (AI)", "Not yet classified" · "Data issue probability {percent}" · "Activity" · "Detected automatically" · "Cause classified ({percent})" · "Suggestion ready ({percent})" · "Ended · {closeReason}" · "Last event {relative}" · "Started {time}" · "Select an alert to see details" |
| Không còn | "This alert is no longer available." · "This alert isn't in the current list. It may be older than the selected period." · "Show last 7 days" |
| Lỗi | "Couldn't acknowledge the alert" |
| Empty | "No open alerts" · "Alerts appear here when delays, bunching or data problems are detected." · "No alerts match these filters" |

Nhãn `type`, `audience`, `closeReason`, `likelyCause`, `category`, `trigger` theo DOC-37 §3.

## 9. Tiêu chí nghiệm thu

- **AC-1** Given anonymous, When mở `/alerts`, Then chỉ có alert `PUBLIC`, không có bộ lọc "Audience" và không có nút "Acknowledge".
- **AC-2** Given viewer mở trang, When một gián đoạn mới được mở, Then dòng mới xuất hiện ở đầu trong ≤ 5 s mà không reload (hoặc `NewItemsPill` nếu đã cuộn).
- **AC-3** Given operator, When bấm "Acknowledge", Then nút và mục đổi trong ≤ 300 ms, badge "Alerts" giảm; bấm lại (tab khác) không đổi người ack.
- **AC-4** Given chi tiết gián đoạn, When bấm "Show on map", Then tới `/map?route=<routeId>&disruption=<refId>` và panel gián đoạn mở.
- **AC-5** Given alert `INFRA` có `runbook_url`, Then nút "Open runbook" mở tab mới.
- **AC-6** Given alert gián đoạn bị chuyển sang `ENGINEERING`, Then mục biến mất khỏi danh sách của anonymous trong ≤ 10 s và vẫn còn (audience mới) với viewer.
- **AC-7** Given SSE bị chặn, Then alert mới vẫn xuất hiện qua polling `since` trong ≤ 15 s.
- **AC-8** Given chi tiết bunching, Then "Where" có hai xe đúng thứ tự leader/follower và Activity có "Suggestion ready (…)" khi đã có gợi ý.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-ALERT-01 | Hai context: anonymous và `operator`. `POST /sim/scenarios/disruption` cho tuyến 18; cả hai chờ dòng mới; operator chọn mục → "Acknowledge" → "Show on map"; axe | AC-1, AC-2, AC-3, AC-4 |
| E2E-ALERT-02 | `operator`: `POST /sim/scenarios/bunching` cho tuyến 5; chọn mục bunching → "Accept" | Toast "Feedback saved"; chi tiết ghi "Accepted by operator"; AC-8 |
| E2E-ALERT-03 | `viewer`: chặn `/api/v1/stream` bằng `page.route`; kích hoạt kịch bản `ticket-spike` | AC-7; chi tiết ticketing có "Open in ticketing" |

AC-5 kiểm ở component test (UX-09); AC-6 cần triage-worker (P6) và nằm trong E2E của DOC-24.

## 11. Câu hỏi còn mở

Không có.
