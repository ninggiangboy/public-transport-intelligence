# Trạng thái UI và microcopy

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-37
> Phụ thuộc: DOC-34, DOC-35, DOC-15 (enum), DOC-23, DOC-30 (slug lỗi), DOC-32, DOC-33, DR-48, DR-61, DR-88
> Người dùng chính: P5-03…P5-14 (mọi màn hình), người viết `src/i18n/en.ts`

## 1. Mục đích và quy ước

Tài liệu này chốt:

- mẫu hiển thị cho các trạng thái loading, rỗng, lỗi, dữ liệu cũ, offline, không có quyền (FR-11.6);
- **toàn bộ chuỗi tiếng Anh** dùng chung: nhãn enum, thông báo lỗi, tooltip giải thích;
- định dạng số, thời gian, khoảng thời gian.

Chuỗi riêng của từng màn hình nằm trong file màn hình đó (`screens/*`). Mọi chuỗi, chung hay riêng, được đưa vào `src/i18n/en.ts` với khóa theo nhóm (`enum.dlqStatus.MANUAL`, `error.dlq-invalid-state.title`, `screen.dlq.discard.confirm`…). Phần giải thích xung quanh viết tiếng Việt; chuỗi trong ngoặc kép là **đúng chữ hiển thị** (DR-48).

Quy tắc viết:

- Sentence case cho mọi nhãn, nút, tiêu đề ("Start replay", không phải "Start Replay"). Tên riêng giữ nguyên (Metro Transit, GTFS).
- Nút là động từ + tân ngữ ("Replay record", "Acknowledge"). Không dùng "OK"/"Yes".
- Không đổ lỗi cho người dùng; nói điều gì xảy ra và làm gì tiếp theo.
- Placeholder `{name}` được thay bằng giá trị; số nhiều xử lý bằng `Intl.PluralRules('en-US')` (khóa `_one`/`_other`).
- Dấu ba chấm là ký tự `…` (U+2026); dấu gạch khoảng là `–` (U+2013).

## 2. Mẫu trạng thái

Mọi màn hình chỉ dùng các mẫu dưới đây, không tự tạo biến thể riêng. Hình ảnh tham chiếu là bảng "Shared states" của prototype (DR-88): loading, đang xử lý, rỗng (lần đầu và do bộ lọc), lỗi (còn dữ liệu cũ và chưa có gì), không có quyền, độ tươi, toast. Prototype: [Shared states](assets/ui-states-and-copy.html).

### 2.1 Loading

- Lần tải đầu của một panel: `PanelSkeleton` giữ đúng hình dạng nội dung (bảng: tiêu đề cột thật + 8 dòng thanh shimmer; biểu đồ: khung trục; danh sách: 5 dòng; KPI: nhãn thật + thanh số). Skeleton chỉ hiện nếu request chưa xong sau **150 ms** (tránh nhấp nháy); `aria-busy="true"` trên panel.
- Chuyển trang: giữ trang cũ, thanh tiến độ mảnh 2 px ở đỉnh sau 150 ms (`pendingMs` của TanStack Router).
- Refetch nền (SSE, 60 s, đổi bộ lọc): **không** hiện skeleton; dữ liệu cũ giữ nguyên (`keepPreviousData`), bảng mờ 0,6 trong lúc đổi bộ lọc và chấm tiến độ nhỏ cạnh `FreshnessIndicator`.
- Nút đang gửi: spinner thay icon, chữ đổi sang dạng tiếp diễn khi có ("Queuing replay…"), nút disable; có nút "Cancel" nếu request hủy được.
- Thao tác dài (replay, job): tiến độ hiện **tại chỗ** — `Progress` 6 px, "{done} / {total}", dòng phụ "About {n} min left · you can leave this page".
- Không có spinner toàn trang, trừ `/auth/callback` ("Signing you in…").

### 2.2 Rỗng

| Trường hợp | Tiêu đề | Mô tả | Hành động |
| --- | --- | --- | --- |
| Danh sách rỗng, có bộ lọc khác mặc định | "No results match these filters" | "Try a wider time range or remove some filters." | "Clear filters" |
| Danh sách rỗng, bộ lọc mặc định | Theo màn hình (ví dụ "No open dead letters") | Theo màn hình | — |
| Biểu đồ không có điểm dữ liệu | "No data for this period" | "Nothing was recorded between {from} and {to}." | — |
| Trạm không còn chuyến trong khung giờ | "No upcoming departures" | "No trips are scheduled at this stop in the next {horizon}." | — |
| Chưa có feed ACTIVE (E-60 không có `activeFeed`) | "No schedule loaded yet" | "The GTFS schedule hasn't been loaded. Routes and stops will appear once it is." | — |

`EmptyState`: icon trung tính (`Inbox`, `SearchX`…) trong ô 44 px bo 12 px nền `--card` có viền và bóng nhẹ, tiêu đề 14,5 px/600, mô tả tối đa 320 px, một nút nếu có hành động. Không dùng hình minh họa. Rỗng do bộ lọc thì các chip bộ lọc đang áp vẫn hiện phía trên để người dùng thấy lý do.

### 2.3 Lỗi

Nguyên tắc (DOC-34 P-3):

- Đã có dữ liệu → `ErrorState variant="inline"`: dải `warning` bo 10 px phía trên dữ liệu cũ, dạng "{error title}. Showing data from {relative time}." (ví dụ "Can't reach the server. Showing data from 2 min ago.") cùng nút "Retry".
- Chưa có dữ liệu → `ErrorState variant="block"` thay cho nội dung panel: icon, tiêu đề ("Couldn't load {panel}" khi lỗi mạng hoặc 5xx), mô tả, trace id dạng mono kèm nút "Copy", nút "Retry".
- Lỗi của thao tác ghi → toast lỗi (hoặc hiển thị trong dialog nếu thao tác đi qua `ConfirmDialog`).
- Mọi lỗi từ API hiện "Trace ID" kèm nút copy (`traceId` của Problem Details, DOC-30). Lỗi mạng không có trace id.

Nội dung theo loại lỗi (khóa `error.<slug>`). Với slug không có trong bảng, UI dùng `title` và `detail` của Problem Details:

| Slug / tình huống | Tiêu đề | Mô tả | Hành động |
| --- | --- | --- | --- |
| Lỗi mạng (fetch thất bại, không có response) | "Can't reach the server" | "Check your connection. We'll keep trying." | "Retry" |
| `validation-error` (400) | "This request isn't valid" | `detail` của API; nếu có `errors[]` thì liệt kê từng trường | — |
| `unauthorized` (401, sau khi `signinSilent` thất bại) | "Your session has expired" | "Sign in again to continue." | "Sign in" |
| `forbidden` (403) | "You don't have access" | "Your account doesn't have the role needed for this." | — |
| `not-found` (404, trang chi tiết) | "{Thing} not found" | "It may have been removed, or the link is wrong." | "Go back" |
| `conflict` (409) | "This changed while you were working" | "Reload to see the latest state." | "Reload" |
| `dlq-invalid-state` (409) | "This dead letter has moved on" | "Its status is now {currentStatus}. The list has been refreshed." | — |
| `replay-already-running` (409) | "A replay is already running" | "Only one replay per source can run at a time." | "View running replay" (mở `existingReplayId`) |
| `job-not-restartable` (409) | "This run can't be restarted" | `detail` của API | — |
| `job-not-running` (409) | "This run isn't running" | "It may have finished while you were looking." | — |
| `payload-too-large` (413) | "Payload is too large" | "Edited payloads must be 1 MiB or smaller." | — |
| `invalid-payload` (422) | "Payload doesn't match the schema" | "Fix the highlighted fields and save again." | — |
| `pii-not-allowed` (422) | "Personal data isn't allowed" | "Remove the field {field} and save again." | — |
| `business-key-changed` (422) | "The record's identity can't change" | "Keep {fields} as they were in the original payload." | — |
| `replay-window-invalid` (422) | "Check the time range" | Liệt kê `errors[]` dưới ô from/to | — |
| `unsupported-source` (422) | "This source can't be replayed" | "Replay is available for vehicle positions, trip updates and ticket sales." | — |
| `analytics-recompute-unavailable` (422) | "Analytics recompute isn't available yet" | "Replay without recomputing analytics." | — |
| `idempotency-key-reused` (422) | "This request was already sent with different values" | "Refresh the page and try again." | "Reload" |
| `job-not-allowed` (422) | "This job can't be started from here" | `detail` của API | — |
| `invalid-flag-value` (422) | "Invalid value for this flag" | `detail` của API | — |
| `rate-limited` (429) | "Too many requests" | "Try again in {seconds} s." (đếm ngược theo `Retry-After`) | Tự retry khi hết giờ |
| `internal-error` (500) | "Something went wrong" | "An unexpected error occurred. Quote the trace ID when reporting it." | "Retry" |
| `simulator-unavailable` (502) | "Simulator isn't responding" | "Check that the source-simulator container is running." | "Retry" |
| `service-unavailable` (503) | "Service is temporarily unavailable" | "We'll retry in a few seconds." | Tự retry sau `Retry-After` |
| Lỗi render (bug phía client) | "Something went wrong on this page" | "Reload the page. If it happens again, report error {code}." | "Reload page" |

Slug riêng của simulator (qua E-90) nằm ở `screens/demo-control.md`.

### 2.4 Dữ liệu cũ và realtime

`StaleBanner` là một dải duy nhất ở đầu vùng nội dung (trên `PageHeader`, DOC-34 §4.2), ưu tiên theo thứ tự (chỉ hiện điều kiện cao nhất):

| Ưu tiên | Điều kiện | Tông | Nội dung |
| --- | --- | --- | --- |
| 1 | `navigator.onLine === false` | `warning` | "You're offline. Showing the last data received {relative}." |
| 2 | E-60 lỗi mạng, 503, hoặc `probeError: true` | `warning` | "Can't check data freshness right now. Data may be out of date." |
| 3 | E-60 `stale: true` | `warning` | "Live data is delayed. Vehicle positions were last updated {age} ago." (dùng `ageSeconds` lớn hơn của hai nguồn GTFS-rt; chưa từng có dữ liệu: "No live vehicle data has been received yet.") |
| 4 | SSE `polling` (DOC-26 §8.3) | `info` | "Live updates are paused. Refreshing every {interval} s." |
| 5 | SSE `reconnecting` quá 5 s | `info` | "Reconnecting to live updates…" |

- Banner có `role="status"`, không có nút đóng (tự biến mất khi điều kiện hết). Chỉ hiện ở trang có dữ liệu realtime hoặc event time (mọi trang trừ Scorecard, Controls).
- `RealtimeStatusDot` (thẻ "Live feed" ở chân sidebar và thanh trên mobile): "Live" (`success`) · "Reconnecting…" (`warning`) · "Polling" (`neutral`) · "Offline" (`neutral`); chữ phụ lần lượt "updated {relative}", "attempt {n}", "every {interval} s", "last update {relative}". Tooltip: "Receiving live updates." / "Trying to reconnect to live updates." / "Live updates unavailable. Refreshing every {interval} s." / "You're offline."
- `FreshnessIndicator` quá `staleAfterSeconds`: chấm và chữ chuyển `warning`, nhãn "Stale" + "last update {relative}".
- Nguồn ticketing stale (`TICKETING_SALES.stale`) chỉ hiện trên màn Ticketing bằng `SourceStaleNotice`: "No ticket sales received for {age}. Anomaly detection may be behind."

### 2.5 Không có quyền

| Tình huống | Tiêu đề | Mô tả | Hành động |
| --- | --- | --- | --- |
| Anonymous mở trang cần viewer | "Sign in to view this page" | "This page is for operations staff." | "Sign in" |
| Anonymous mở trang cần operator | "Sign in to view this page" | "This page requires the operator role." | "Sign in" |
| Viewer mở trang cần operator | "You don't have access to this page" | "This page requires the operator role. Ask an administrator for access." | "Go to overview" |
| Viewer trong Ops console (badge) | "Read-only" | Tooltip: "You can view everything here. Actions require the operator role." | — |
| UI không có Keycloak (`env.js` rỗng, k3d `lite`) | "Sign-in isn't available" | "This deployment only shows public pages." | — |

### 2.6 Lỗi từng phần

Mỗi panel có query và error boundary riêng; một panel lỗi không ẩn panel khác. Ví dụ Stop detail: E-08 lỗi thì danh sách giờ đến hiện `ErrorState` còn thông tin trạm và banner gián đoạn (E-07) vẫn hiện.

### 2.7 Thao tác và phản hồi

| Tình huống | Hiển thị |
| --- | --- |
| Thao tác lạc quan thành công | Không toast cho ack (dòng đổi ngay tại chỗ là đủ); toast `success` ngắn "Feedback saved" cho phản hồi gợi ý điều phối (panel có thể đã đóng khi request xong); toast `success` cho replay, discard, resolve, confirm, cờ, job |
| Thao tác lạc quan thất bại | Hoàn tác trạng thái, toast lỗi theo §2.3, dòng liên quan nháy `danger` 2 s |
| Thao tác bất đồng bộ (202) | Toast "Request sent" kèm link tới đối tượng theo dõi ("View replay", "View run") |
| Nhiều thao tác một lần (confirm nhiều dòng) | Toast tổng hợp "{ok} confirmed, {failed} failed"; dòng lỗi giữ lựa chọn và có icon lỗi với tooltip là thông báo lỗi |

### 2.8 Phiên đăng nhập

- Đang khôi phục phiên lúc khởi động: skeleton ở vị trí menu người dùng, không có chữ.
- Hết hạn: toast "Your session has expired. Sign in again." với nút "Sign in" (không tự chuyển trang).
- Đăng xuất: không toast; về trang `/` ở trạng thái anonymous.

## 3. Nhãn enum

Nhãn là giá trị trong `en.ts` dưới `enum.<nhóm>.<giá trị>`. `StatusPill`, `SeverityBadge` và bộ lọc dùng chung các nhãn này. Giá trị lạ (enum mới chưa cập nhật UI) hiển thị nguyên văn, không làm lỗi trang.

### 3.1 Chung

| Nhóm | Giá trị → nhãn |
| --- | --- |
| Severity | `0` "Informational" · `1` "Needs attention" · `2` "Urgent" · không có "Unclassified" |
| Nguồn (`source`) | `GTFS_RT_VEHICLE_POSITION` "Vehicle positions" · `GTFS_RT_TRIP_UPDATE` "Trip updates" · `TICKETING_SALES` "Ticket sales" · `TICKETING_SALE_POINTS` "Sale points" · `GTFS_STATIC` "GTFS schedule" |
| Mức tin cậy ETA | `HIGH` "High confidence" · `MEDIUM` "Medium confidence" · `LOW` "Low confidence" · `NONE` "Schedule only" |
| Confidence AI | ≥ 0,6: "{percent} confidence" (ví dụ "82% confidence") · < 0,6: "Low confidence ({percent})" |
| `enrichmentStatus` | `PENDING` "Waiting for AI" · `IN_PROGRESS` "Classifying…" · `DONE` (không hiện nhãn, hiện kết quả) · `FAILED` "Classification failed" · `SKIPPED` "Not classified" |
| Actor | `auto` "Auto-triage" · `system:<svc>` "System ({svc})" · `user:<name>` "{name}" · `migration` "System" · `experiment:<…>` "Experiment" (tooltip là chuỗi đầy đủ) |
| Lớp trễ | `early` "Early" · `on-time` "On time" · `late` "Late" · `very-late` "Very late" · `unknown` "No delay data" |

### 3.2 Vận tải

| Nhóm | Giá trị → nhãn |
| --- | --- |
| `routeType` (GTFS) | `0` "Light rail" · `1` "Subway" · `2` "Rail" · `3` "Bus" · `4` "Ferry" · `5` "Cable tram" · `6` "Aerial lift" · `7` "Funicular" · `11` "Trolleybus" · `12` "Monorail" |
| Nhãn chiều (`label`) | `NB` "Northbound" · `SB` "Southbound" · `EB` "Eastbound" · `WB` "Westbound" · khác: nguyên văn · không có: "Direction {directionId}" |
| `currentStatus` | `INCOMING_AT` "Arriving at {stop}" · `STOPPED_AT` "Stopped at {stop}" · `IN_TRANSIT_TO` "Heading to {stop}" |
| `occupancyStatus` | `EMPTY` "Empty" · `MANY_SEATS_AVAILABLE` "Many seats available" · `FEW_SEATS_AVAILABLE` "Few seats available" · `STANDING_ROOM_ONLY` "Standing room only" · `CRUSHED_STANDING_ROOM_ONLY` "Very crowded" · `FULL` "Full" · `NOT_ACCEPTING_PASSENGERS` "Not accepting passengers" · `NOT_BOARDABLE` "Not boardable" · `NO_DATA_AVAILABLE` hoặc không có: không hiển thị |
| `wheelchairBoarding` | `1` "Wheelchair accessible" · `2` "Not wheelchair accessible" · `0`/không có: không hiển thị |

### 3.3 Insight và alert

| Nhóm | Giá trị → nhãn |
| --- | --- |
| Alert `type` | `DISRUPTION` "Service disruption" · `BUNCHING` "Bus bunching" · `DLQ_SEVERE` "Dead letter spike" · `FEED_STALE` "Feed stale" · `TICKETING_ANOMALY` "Ticketing anomaly" · `INFRA` "Infrastructure" |
| `audience` | `PUBLIC` "Public" · `OPERATIONS` "Operations" · `ENGINEERING` "Engineering" |
| Bộ lọc `state` | `open` "Open" · `unacknowledged` "Unacknowledged" · `all` "All" |
| Trạng thái một alert | chưa resolved, chưa ack: không nhãn · đã ack: "Acknowledged by {name}" · đã resolved: "Resolved {relative}" |
| Episode `status` | `OPEN` "Ongoing" · `CLOSED` "Ended" |
| Bunching `role` | `LEADER` "Leading bus" · `FOLLOWER` "Following bus" |
| Bunching `closeReason` | `GAP_RECOVERED` "Gap recovered" · `PAIR_CHANGED` "Buses changed order" · `SIGNAL_LOST` "Signal lost" · `OUT_OF_ZONE` "Left the detection zone" |
| Disruption `closeReason` | `RECOVERED` "Recovered" · `NO_DATA` "Ended: no data" · `MAX_DURATION` "Ended after 3 hours" |
| `likelyCause` | `traffic` "Traffic" · `vehicle_breakdown` "Vehicle breakdown" · `weather` "Weather" · `event` "Special event" · `data_issue` "Data issue" · `unknown` "Unknown cause" |
| Dispatch `action` (ngắn / đầy đủ) | `hold_follower` "Hold follower" / "Hold the following bus at its next stop" · `skip_stops` "Skip stops" / "Let the following bus skip stops to rebuild the gap" · `no_action` "No action" / "No action needed; the gap should recover" |
| `operatorFeedback` | `accepted` "Accepted" · `ignored` "Dismissed" · không có "No feedback yet" |
| Ticketing `category` | `fraud_suspect` "Possible fraud" · `system_error` "System error" · `promo_spike` "Promotion spike" · `normal` "Normal activity" · `unclassified` "Unclassified" |
| Ticketing `trigger` | `VOLUME` "Sales volume" · `REFUND_RATIO` "Refund ratio" · `BOTH` "Volume and refunds" |

### 3.4 Pipeline

| Nhóm | Giá trị → nhãn |
| --- | --- |
| Job `kind` | `BATCH_JOB` "Batch job" · `STREAM` "Stream" |
| Job `status` | `STARTING` "Starting" · `STARTED` "Running" · `STOPPING` "Stopping" · `STOPPED` "Stopped" · `FAILED` "Failed" · `COMPLETED` "Completed" · `COMPLETED_WITH_SKIPS` "Completed with skips" · `ABANDONED` "Abandoned" · `UNKNOWN` "Unknown" |
| `job_request.kind` | `RUN` "Run" · `RESTART` "Restart" · `STOP` "Stop" |
| `job_request.status` | `PENDING` "Queued" · `RUNNING` "Running" · `DONE` "Done" · `FAILED` "Failed" · `REJECTED` "Rejected" |
| `replay_request.kind` | `RAW_RANGE` "Time range" · `DLQ_RECORD` "Dead letter" |
| `replay_request.status` | `PENDING` "Queued" · `RUNNING` "Running" · `DONE` "Done" · `FAILED` "Failed" |
| Feed `status` | `STAGED` "Staged" · `ACTIVE` "Active" · `RETIRED` "Retired" · `REJECTED` "Rejected" |
| `writeMode` (micro-batch) | `BATCH` "Batch" · `SCAN` "Scan (record by record)" |
| Tên job | Hiển thị nguyên văn (mono), ví dụ `OtpScorecardJob`; tên listener stream nguyên văn (`gtfs-rt-vehicle-position`) |

### 3.5 Dead letter

| Nhóm | Giá trị → nhãn |
| --- | --- |
| `status` | `NEW` "New" · `TRIAGING` "Triaging" · `TRIAGED` "Triaged" · `AUTO_REPLAY_SCHEDULED` "Auto-replay scheduled" · `PENDING_CONFIRM` "Awaiting confirmation" · `MANUAL` "Needs manual review" · `REPLAY_REQUESTED` "Replay requested" · `REPLAYED` "Replayed" · `DISCARDED` "Discarded" · `RESOLVED` "Resolved" |
| `category` | `schema_violation` "Schema violation" · `referential_integrity` "Unknown reference" · `upstream_api_error` "Upstream error" · `transient_network` "Transient network error" · `unknown` "Unknown cause" · `unclassified` "Unclassified" |
| `stage` | `DESERIALIZE` "Deserialize" · `SCHEMA` "Schema check" · `BUSINESS` "Business rules" · `DEDUP` "Deduplication" · `LOAD` "Load" · `QUALITY` "Data quality" |
| Hành động (`dlq_action_log.action`) | `TRIAGED` "Classified" · `TRIAGE_FAILED` "Classification failed" · `AUTO_REPLAY_SCHEDULED` "Auto-replay scheduled" · `CONFIRM_REQUESTED` "Sent for confirmation" · `CONFIRMED` "Confirmed" · `MANUAL_REQUIRED` "Sent to manual review" · `EDITED` "Payload edited" · `REPLAY_REQUESTED` "Replay requested" · `REPLAYED` "Replayed" · `REPLAY_FAILED` "Replay failed" · `DISCARDED` "Discarded" · `RESOLVED` "Resolved" |
| Bộ lọc `actorType` | `auto` "Auto-triage" · `system` "System" · `user` "People" |
| `ruleId` | Nguyên văn (`DQ-01`), tooltip là tên rule của DOC-16 (`dq.<id>` ở `src/i18n/dlq.ts`, tải cùng màn Dead letters, DR-110) |

## 4. Định dạng

Mọi hàm ở `src/lib/format.ts` và `src/lib/time.ts`, locale `en-US`, múi giờ agency (DOC-34 §8). Formatter `Intl` được tạo một lần và cache.

### 4.1 Số

| Loại | Quy tắc | Ví dụ |
| --- | --- | --- |
| Số đếm | Nhóm hàng nghìn | "12,345" |
| Số đếm lớn trong thẻ tóm tắt và trục biểu đồ | `notation: 'compact'` khi ≥ 10.000, một chữ số thập phân | "412K", "1.2M" |
| Phần trăm | Một chữ số thập phân | "78.4%" |
| Confidence AI | Phần trăm nguyên | "82%" |
| Tỉ lệ (`refundRatio`) | Phần trăm nguyên | "48%" |
| Tiền | USD, hai chữ số thập phân | "$50.00" |
| z-score | Hai chữ số thập phân, tiền tố "z = " | "z = 3.98" |
| Tọa độ | 5 chữ số thập phân | "44.94812, −93.27800" |
| Tốc độ | Dặm/giờ, số nguyên (`speedMps × 2.23694`) | "17 mph" |

### 4.2 Thời điểm

| Dạng | Ví dụ | Dùng khi |
| --- | --- | --- |
| Giờ | "4:05 PM" | Danh sách trong ngày (ETA, giờ lịch) |
| Giờ có múi giờ | "4:05 PM CDT" | Thời điểm đứng riêng |
| Giờ có giây (ops) | "4:05:12 PM CDT" | Bảng jobs, DLQ, hành động |
| Ngày giờ | "Sep 29, 4:05 PM CDT" | Khác ngày hôm nay (theo giờ agency) |
| Ngày giờ khác năm | "Sep 29, 2025, 4:05 PM CDT" | Khác năm hiện tại |
| Ngày | "Sep 29, 2026" | Feed, OTP |
| Ngày phục vụ | "Tue, Sep 29" | `serviceDate` |
| Khoảng ngày | "Sep 22 – Sep 28" | Scorecard |
| Khoảng giờ | "8:00 PM – 9:15 PM CDT" | Cửa sổ ticketing, replay |
| Thứ trong tuần (heatmap) | "Mon" … "Sun" | |
| Giờ trong ngày (heatmap) | "12 AM", "1 AM" … "11 PM" | |
| Tooltip mọi `Timestamp` | "2026-09-29T21:05:12Z" | ISO UTC để đối chiếu log |

### 4.3 Thời gian tương đối

| Khoảng | Quá khứ | Tương lai |
| --- | --- | --- |
| < 5 s | "just now" | "now" |
| < 60 s | "{n} s ago" | "in {n} s" |
| < 60 phút | "{n} min ago" | "in {n} min" |
| < 24 giờ | "{n} h ago" | "in {n} h" |
| ≥ 24 giờ | Ngày giờ tuyệt đối (§4.2) | Ngày giờ tuyệt đối |

Trục event dùng `businessNow`, trục audit dùng đồng hồ máy (DOC-34 §8).

### 4.4 ETA và độ trễ (màn hành khách)

| Tình huống | Hiển thị |
| --- | --- |
| Còn ≤ 60 s | "Due" |
| Còn 1–59 phút | "{n} min" (làm tròn xuống) |
| Còn ≥ 60 phút | Giờ tuyệt đối "5:42 PM" |
| Độ trễ \|d\| < 60 s | "On time" |
| Trễ | "{n} min late" (làm tròn tới phút gần nhất) |
| Sớm | "{n} min early" |
| Không có dự đoán (`confidence = NONE`) | Giờ lịch, kèm "Scheduled" |

### 4.5 Khoảng thời gian

| Loại | Ví dụ |
| --- | --- |
| < 1 s | "384 ms" |
| < 60 s | "4.2 s" |
| < 60 phút | "4 min 12 s" |
| ≥ 60 phút | "1 h 5 min" |
| ISO-8601 từ API (`PT20M`, `PT1H30M`) | "20 min", "1 h 30 min" |
| Độ trễ trong ops (giây có dấu) | "+3 min 33 s", "−45 s" |

### 4.6 Id

- UUID và `batchId`: 8 ký tự đầu, mono, tooltip id đầy đủ, nút copy ("Copy ID" → "Copied").
- `runId`: `job:4127` hiển thị "#4127"; `stream:<listener>:<minute>` hiển thị "{listener} · 4:18 PM".
- `vehicleId`: "Bus {label}" (hoặc "Train {label}" với `routeType` 0 và 2).

## 5. Tooltip và văn bản giải thích

| Khóa | Nội dung |
| --- | --- |
| `help.etaConfidence` | "Predicted from {sampleCount} past trips on this route at this stop, on {weekday}s between {hour}. High: 30+ trips. Medium: 10–29. Low: fewer than 10." |
| `help.etaNone` | "No history for this stop at this hour yet, so we show the scheduled time." |
| `help.etaRealtime` | "Based on the latest real-time update from the bus." |
| `help.aiConfidence` | "How sure the model is about this answer ({percent}). Below 60% we mark it as low confidence. Model: {modelVersion}." |
| `help.unclassified` | "The AI hasn't classified this yet. It may be switched off or still working." |
| `help.otp` | "On-time performance: share of observed stop arrivals between {early} early and {late} late." |
| `help.otpMixed` | "The on-time window changed during this period, so days were measured with different thresholds." |
| `help.bunching` | "Two buses on the same route are running closer than {ratio} of the scheduled headway." |
| `help.gap` | "Time between the two buses at the follower's current stop. Scheduled headway: {headway}." |
| `help.disruptionZ` | "Average delay is {z} standard deviations above normal for this route at this time." |
| `help.dataIssue` | "Chance that this disruption is a data problem rather than a real delay: {percent}." |
| `help.replayEstimate` | "Estimated from how many messages were processed in this range. Actual time depends on load." |
| `help.replayRecordTime` | "Replay selects records by the time Kafka received them. Business time is shown for reference using the current simulated clock offset." |
| `help.dlqAutoReplay` | "Auto-triage replays records it is confident are transient. Each action shows the confidence it used." (ở `src/i18n/dlq.ts` `autoReplayHelp`, DR-110) |
| `help.simulatedClock` | "The simulator runs on a shifted clock so the schedule has buses in service. Times on this site follow that clock." |
| `help.staleVehicle` | "Last position received {relative}. The bus may have moved." |
| `help.freshness` | "Newest data included here." |

Caption biểu đồ (đọc bởi screen reader) theo mẫu: "{Chart title}. {n} points from {from} to {to}. Highest {max} at {time}; lowest {min} at {time}."

## 6. Chuỗi chung của khung

| Khóa | Chuỗi |
| --- | --- |
| Tên sản phẩm | "Public Transport Intelligence" (ngắn: "PTI") |
| `document.title` | "{Page} — PTI" (ví dụ "Nicollet Ave & 46th St — PTI"; "Dead letters — PTI") |
| Điều hướng | Nhóm "Network", "Analytics", "Operations" · mục "Overview", "Live map", "Stops", "Alerts", "Scorecard", "Pipeline", "Dead letters", "Replay", "Ticketing", "Controls", "Demo" · mobile: "Map", "Stops", "Alerts", "More" |
| Sidebar | Tên "Transit Intelligence" · agency "Metro Transit · Twin Cities" (`app.agency`) · "Search" + `⌘K` · thẻ "Live feed": "Live feed", "Updated {relative}", "{rate} msg/s" (viewer) · vai trò "Operator · on duty", "Viewer" · `aria-label` "Account menu", "Primary" |
| Tìm kiếm chung | Placeholder "Search pages, routes and stops" · nhóm "Pages", "Routes", "Stops" · rỗng "No matches for "{q}"" |
| Tài khoản | "Sign in", "Sign out", "Signed in as {displayName}", "Role: {role}", "Keyboard shortcuts", "Theme" |
| Theme | "Theme", "Light", "Dark", "System" |
| Nút chung | "Retry", "Reload", "Cancel", "Close", "Save", "Discard changes", "Clear filters", "Load more", "Show", "Copy ID", "Copied", "View as table", "View as chart", "Legend", "Follow", "Stop following", "Go back" |
| Link bỏ qua | "Skip to content" |
| Footer | "Data: Metro Transit GTFS (public domain). Map: © OpenStreetMap contributors · Protomaps." · khi lệch đồng hồ: "Simulated clock: {businessNow}" |
| Thông báo màn hẹp | "The ops console is designed for screens at least 1280 px wide." |
| Kiểm tra form (zod, `src/lib/form-errors.ts`) | "Required" · "Must be at least {min}." · "Must be at most {max}." · "Enter a number." · "Enter a whole number." · "Enter {min}–{max} characters." · "Choose at least one." · "Invalid JSON: {message}" |
| Trang 404 | "Page not found" · "The page you're looking for doesn't exist." · "Go to the map" |

## 7. Test bắt buộc

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| CP-01 | Unit: mọi giá trị enum trong DDL của DOC-15 (đọc từ fixture sinh từ migration) có khóa trong `en.ts` | Không thiếu khóa |
| CP-02 | Lint: ESLint `i18next/no-literal-string` trên `src/` (trừ `i18n/`, test) | Không có chuỗi JSX literal |
| CP-03 | Unit: `formatRelative` các mốc 4 s, 5 s, 59 s, 60 s, 59 phút, 24 giờ, tương lai 30 s | Đúng bảng §4.3 |
| CP-04 | Unit: `formatEta` 45 s, 61 s, 59 phút 59 s, 60 phút; độ trễ 59 s, 90 s, −150 s | "Due", "1 min", "59 min", giờ tuyệt đối; "On time", "2 min late", "3 min early" |
| CP-05 | Unit: định dạng giờ trong ngày đổi giờ (2026-11-01, `America/Chicago`) | Viết tắt đổi từ "CDT" sang "CST" đúng thời điểm |
| CP-06 | Unit: `formatDuration` với `PT20M`, `PT1H30M`, 384 ms, 4.200 ms | "20 min", "1 h 30 min", "384 ms", "4.2 s" |
| CP-07 | Component: `ErrorState` cho mọi slug §2.3 và slug lạ | Đúng tiêu đề; slug lạ dùng `title`/`detail` của API; luôn có trace id khi API trả |
| CP-08 | Component: `StaleBanner` khi đồng thời offline và `stale` | Chỉ hiện điều kiện ưu tiên 1 |
| CP-09 | Component: panel đã có dữ liệu rồi refetch 500 | Dữ liệu cũ còn; dải inline "… Showing data from …" |
| CP-10 | Component: skeleton với response trả sau 100 ms và sau 400 ms | Lần 1 không có skeleton; lần 2 có |

## 8. Câu hỏi còn mở

Không có.
