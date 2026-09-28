# Màn hình: Ops console — Controls

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35, DOC-37 §2.3, §2.7, §3, DOC-32 (E-31, E-33, E-34, E-38, E-55, E-57, E-60), DOC-15 §3, DOC-19 §2, DOC-20 §7, DOC-21
> Người dùng chính: P5-11

## 1. Persona, use case, quyền

- **Persona:** PS-4 (desktop ≥ 1280 px).
- **Use case:** UC-11 (tạm dừng và tiếp tục consumer), UC-14 (nạp feed GTFS), UC-07/UC-13 (chạy job theo yêu cầu), FR-15.1.
- **Quyền:** viewer xem mọi khối. Switch, nút "Run job" và form chỉ bật với operator; viewer thấy switch ở trạng thái disable kèm tooltip "Requires the operator role." và badge "Read-only".

## 2. URL và search params

`/ops/controls` — không có search params. Bốn khối xếp dọc với mục lục bên trái; neo `#pipeline`, `#ai`, `#feeds`, `#run-job` cho link từ runbook (neo cũ `#flags` chuyển tới `#pipeline`).

## 3. Wireframe

Prototype (DR-88): [Ops — Controls](assets/ops-console-controls.html).

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ Operations / Controls                                                          │
│         │ Controls                                                                       │
│         │ Changes apply within 5 seconds and record who made them.                       │
│         │ ┌──────────────┐ ┌ Pipeline ─────────────────────────────────────────────────┐ │
│         │ │ Pipeline   ◀ │ │ Pause consumers during maintenance. Messages keep landing  │ │
│         │ │ AI features  │ │ in Kafka and are processed on resume.                      │ │
│         │ │ Schedule feed│ │ ┌───────────────────────────────────────────────────────┐ │ │
│         │ │ Run a job    │ │ │ GTFS-realtime consumer                  Paused [━━○]  │ │ │
│         │ └──────────────┘ │ │ Vehicle positions and trip updates from the feed.     │ │ │
│         │                  │ │ Vehicle positions · last processed 2 min ago          │ │ │
│         │                  │ │ Trip updates · last processed 2 min ago               │ │ │
│         │                  │ │ ┌ ⚠ Paused by operator 2 min ago. The live map will ┐ │ │ │
│         │                  │ │ │   show vehicles as stale after about 2 minutes.    │ │ │ │
│         │                  │ │ └────────────────────────────────────────────────────┘ │ │ │
│         │                  │ │ Ticketing consumer                     Running [●━━]   │ │ │
│         │                  │ └───────────────────────────────────────────────────────┘ │ │
│         │                  │ ┌ AI features ──────────────────────────────────────────┐ │ │
│         │                  │ │ Classify new dead letters                    [●━━]    │ │ │
│         │                  │ │ New dead letters get a category, severity and route.  │ │ │
│         │                  │ │ Automatic replay of dead letters             [●━━]    │ │ │
│         │                  │ │ …                                                     │ │ │
│         │                  │ ┌ Schedule feed ────────────────────────────────────────┐ │ │
│         │                  │ │ [▤] 2026-08-23  ● Active   0 errors · 14 warnings     │ │ │
│         │                  │ │     Valid Aug 23 – Dec 12 · loaded Sep 27, 3:00 AM by run #3810 │ │
│         │                  │ │ Load the next feed  [https://… GTFS zip URL ] [Load feed] │ │
│         │                  │ │ History  ID Status Version Valid Loaded Checks Run    │ │ │
│         │                  │ ┌ Run a job ────────────────────────────────────────────┐ │ │
│         │                  │ │ Job [OtpScorecardJob ▾]  Service dates [Sep 27×][Sep 28×][+ Add date] │
│         │                  │ │ Recompute on-time performance for the selected days. [Run job] │
│         │                  │ │ Recent requests: OtpScorecardJob · ● Running · run #4130 → │ │
└─────────┴──────────────────┴────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Mục lục | danh sách neo dính bên trái (180 px) | Mục đang trong khung nhìn được tô như mục nav đang chọn (IntersectionObserver) |
| Thẻ "Pipeline" | `Card` + `ConsumerRow` × 2 (`etl.consumer.gtfs-rt.paused`, `etl.consumer.ticketing.paused`) | Mỗi hàng: tên consumer, `description` (E-55), danh sách listener, và `Switch` biểu diễn **"đang chạy"** (bật = `paused: false`), nhãn cạnh switch "Running" / "Paused". `aria-describedby` trỏ tới description |
| Listener | dòng phụ | "{source} · last processed {relative}" = `bucketStart` cuối có `batches > 0` trong E-31 (15 phút, `1m`); không có → "No batches in the last 15 min" (tông `warning` khi consumer đang chạy). GTFS-rt và ticketing sales có thêm "latest event {relative}" từ E-60 (trục event); nguồn stale ghi "(source quiet)" |
| Callout pause | `Callout tone="warning"` trong hàng đang pause | "Paused by {name} {relative}." + hệ quả; quá 1 giờ thêm chip "Paused for {duration}" |
| Thẻ "AI features" | `Card` + `FlagRow` × 5 (`triage.*`) | Mỗi hàng: nhãn (§8), một câu mô tả việc tính năng làm, `Switch`; tắt quá 1 giờ → chip `warning` "Off for {duration}" (theo `updatedAt`, trục audit); "Changed by {name} · {relative}" |
| Thẻ "Schedule feed" | `Card` | Feed `ACTIVE`: icon, "{publisherFeedVersion}", `StatusPill domain="feed"`, "Valid {from} – {to} · loaded {time} by run #{id}" (link), "{e} errors · {w} warnings" (`errors > 0` tông `danger`). Khối "Load the next feed" (operator): ô "GTFS zip URL" + nút "Load feed" = chạy `GtfsStaticLoadJob` với `sourceUri` (§6.1). Dưới cùng là bảng "History" (≤ 50 dòng): "ID", "Status", "Version", "Publisher", "Valid", "Loaded", "Activated", "Checks", "Run" |
| Thẻ "Run a job" | react-hook-form + zod, form động theo job | §6.1 |
| Yêu cầu gần đây | danh sách ≤ 5 trong thẻ "Run a job" | Các yêu cầu tạo trong phiên (Zustand store), mỗi dòng theo dõi E-34 |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Cờ | E-55 | `['etl', 'flags']` | 30 s (chung với khung); invalidate sau E-57 |
| Hoạt động listener | E-31 `?from=now−15m&to=now&bucket=1m` | `['etl', 'jobs', 'summary', { window: '15m', bucket: '1m' }]` | 10 s |
| Sự kiện mới nhất | E-60 | `['system', 'freshness']` | Từ khung (15 s) |
| Feed | E-38 | `['etl', 'feeds']` | 60 s; invalidate khi yêu cầu `GtfsStaticLoadJob` chuyển `DONE` |
| Chạy job | E-33 | mutation | — |
| Theo dõi yêu cầu | E-34 | `['etl', 'job-request', id]` | 2 s tới khi `RUNNING`/`DONE`/`REJECTED`/`FAILED`; sau `RUNNING` chuyển sang theo dõi E-32 qua link |

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Tắt switch "Running" (bật cờ pause) | `ConfirmDialog` "Pause the {listener group} consumer?" · "New messages stay in Kafka and are processed when you resume. Live data will go stale after about 2 minutes." → E-57 `{"value": true}` → switch đổi ngay (lạc quan), toast "Consumer paused. Takes effect within 5 seconds." | Lỗi → hoàn tác, toast theo DOC-37 §2.3 |
| Bật lại switch (tắt cờ pause) | Không hỏi (khôi phục trạng thái bình thường) → E-57 → toast "Consumer resumed" | Như trên |
| Tắt cờ `triage.*` | `ConfirmDialog` "Turn off {feature}?" · mô tả hệ quả theo cờ (§8) → E-57 → toast "{feature} turned off" | Như trên |
| Bật cờ `triage.*` | Không hỏi → E-57 → toast "{feature} turned on" | Như trên |
| Người khác đổi cờ | Lần refetch kế tiếp (≤ 30 s) cập nhật switch và dòng "Paused by {name}" | — |
| Chọn job | Form tham số đổi theo §6.1; giá trị cũ bị xóa | — |
| "Run job" | `ConfirmDialog` "Run {job}?" với mô tả hệ quả (§8) và bảng tóm tắt tham số → E-33 (`Idempotency-Key` sinh khi mở dialog) → dòng mới trong "Recent requests" với `StatusPill domain="jobRequest"`; toast "Request sent" + "View run" (bật khi có `runId`) | 400 → lỗi gắn theo trường (`errors[].field`); 422 `job-not-allowed` → dải lỗi với `detail` |
| Yêu cầu chuyển `REJECTED`/`FAILED` | Dòng hiện `message`; toast `danger` "{job} request was rejected: {message}" | — |
| Bấm "Run #…" ở bảng feed hoặc yêu cầu | `/ops/jobs?run=<runId>` | — |
| "Load feed" | Như "Run job" với `GtfsStaticLoadJob` và `sourceUri` đã nhập; dòng yêu cầu hiện trong "Recent requests" | URI sai scheme → lỗi dưới ô "Use an https, s3 or file URI." |

### 6.1 Form "Run a job"

Danh sách job = danh sách cho phép của E-33 (hằng số trong `src/features/ops/controls/jobs.ts`, cùng thứ tự). Kiểm tra phía client lặp lại quy tắc của E-33; ngày giờ tính theo `businessNow` của E-60.

| Job | Trường | Kiểm tra phía client |
| --- | --- | --- |
| `GtfsStaticLoadJob` | "Source URI" (text, tùy chọn; trống = nguồn cấu hình), "Allow reactivating an older feed" (checkbox) | Scheme `https`, `s3` hoặc `file` |
| `EtaAggregationJob` | "Hour" (chọn ngày + giờ, tùy chọn; trống = giờ vừa qua), "Recompute even if already done" (`force`) | Đầu giờ; ≤ `businessNow` |
| `OtpScorecardJob` | "Service dates" (chọn nhiều ngày, 1–31) | Mỗi ngày < hôm nay (giờ agency theo `businessNow`) |
| `AnalyticsRecomputeJob` | "Detectors" (checkbox `BUNCHING`, `DISRUPTION`, `TICKETING`, mặc định cả ba), "From", "To" (bắt buộc) | `from < to ≤ businessNow`; ≤ 7 ngày |
| `PartitionMaintenanceJob` | không có | — |

Mỗi job có một dòng mô tả dưới ô chọn (§8). `AnalyticsRecomputeJob` hiện thêm ghi chú "Times are business time (simulated clock)." khi `clockOffset ≠ PT0S`.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | 7 hàng skeleton trong hai thẻ đầu; thẻ feed skeleton; form hiện ngay |
| Empty (feed) | "No GTFS feeds loaded yet" · "Run GtfsStaticLoadJob to load the schedule." |
| Error | Mỗi khối độc lập (DOC-37 §2.6). E-55 lỗi → khối cờ `ErrorState`, **không** hiện switch với giá trị đoán |
| Stale | Không hiện `StaleBanner` (DOC-37 §2.4); dòng listener tự thể hiện việc dừng xử lý |
| Không có quyền | Viewer: switch disable, không có khối "Load the next feed" và nút "Run job"; anonymous: DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Controls" |
| Đầu trang | "Controls" · "Changes apply within 5 seconds and record who made them." |
| Khối | "Pipeline" · "Pause consumers during maintenance. Messages keep landing in Kafka and are processed on resume." · "AI features" · "Every AI decision is logged with its confidence. Turning a feature off stops new decisions; nothing already decided changes." · "Schedule feed" · "The static GTFS timetable every prediction and scorecard is measured against." · "Run a job" · "Recent requests" |
| Consumer | "GTFS-realtime consumer" · "Vehicle positions and trip updates from the Metro Transit feed." · "Ticketing consumer" · "Ticket sales and sale-point updates." · "Running" · "Paused" · "(source quiet)" |
| Mô tả tính năng AI | `triage.dlq.enabled` "New dead letters get a category, severity and routing decision." · `triage.auto-replay.enabled` "Confident transient failures are replayed without a person." · `triage.ticketing.enabled` "Ticketing anomalies get a category and severity." · `triage.disruption.enabled` "Disruptions get a likely cause and a data-issue check." · `triage.dispatch.enabled` "Bunching episodes get a suggested dispatch action." |
| Feed | "Valid {from} – {to} · loaded {time} by run #{id}" · "{e} errors · {w} warnings" · "Load the next feed" · "GTFS zip URL" · "Paste the URL of a GTFS zip (https, s3 or file). It is validated before it can be activated." · "Load feed" · "History" |
| Nhãn cờ | `etl.consumer.gtfs-rt.paused` → hàng "GTFS-realtime consumer" · `etl.consumer.ticketing.paused` → hàng "Ticketing consumer" · `triage.dlq.enabled` "Classify new dead letters" · `triage.auto-replay.enabled` "Automatic replay of dead letters" · `triage.ticketing.enabled` "Classify ticketing anomalies" · `triage.disruption.enabled` "Enrich service disruptions" · `triage.dispatch.enabled` "Suggest dispatch actions" |
| Trạng thái cờ | "Paused by {name} {relative}." · "The live map will show vehicles as stale after about 2 minutes." · "Running" · "Changed by {name} · {relative}" · "Paused for {duration}" · "Off for {duration}" · "Requires the operator role." |
| Listener | "Last processed {relative}" · "latest event {relative}" · "No batches in the last 15 min" |
| Xác nhận pause | "Pause the {group} consumer?" (`group`: "GTFS-realtime", "ticketing") · "New messages stay in Kafka and are processed when you resume. Live data will go stale after about 2 minutes." · nút "Pause" |
| Xác nhận tắt triage | "Turn off {feature}?" · `triage.dlq.enabled`: "New dead letters will wait in New until you turn it back on." · `triage.auto-replay.enabled`: "Dead letters will need a manual replay or confirmation." · `triage.ticketing.enabled`: "New ticketing anomalies will show as Unclassified." · `triage.disruption.enabled`: "Disruptions won't get a likely cause or data-issue check, so all stay visible to riders." · `triage.dispatch.enabled`: "Bunching episodes won't get a suggested action." · nút "Turn off" |
| Phản hồi | "Consumer paused. Takes effect within 5 seconds." · "Consumer resumed" · "{feature} turned off" · "{feature} turned on" · "Request sent" + "View run" · "{job} request was rejected: {message}" |
| Bảng feed | "ID", "Status", "Version", "Publisher", "Valid", "Loaded", "Activated", "Checks", "Run" · "{e} errors, {w} warnings" |
| Form job | "Job" · "Run job" · "Run {job}?" · mô tả: `GtfsStaticLoadJob` "Download, validate and activate a GTFS schedule." · `EtaAggregationJob` "Rebuild typical delays for one hour." · `OtpScorecardJob` "Recompute on-time performance for the selected days." · `AnalyticsRecomputeJob` "Re-run bunching, disruption and ticketing detection for a period." · `PartitionMaintenanceJob` "Create upcoming partitions and drop expired ones." |
| Trường | "Source URI" · "Allow reactivating an older feed" · "Hour" · "Recompute even if already done" · "Service dates" · "Detectors" · "From" · "To" · "Times are business time (simulated clock)." |
| Lỗi trường | "Use an https, s3 or file URI." · "Pick the start of an hour." · "Can't be in the future." · "Dates must be before today." · "Pick 1 to 31 dates." · "End must be after start." · "Max range is 7 days." |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given operator, When tắt switch của "GTFS-realtime consumer" → xác nhận, Then trong ≤ 5 s không còn micro-batch mới (dòng listener ngừng cập nhật), và trong ≤ 3 phút khung hiện `StaleBanner` (UC-11).
- **AC-2** Given cờ đã bật, Then hàng hiện nhãn "Paused" và callout "Paused by operator {relative}.", và mọi trang `/ops/*` có chip "Paused" (shell AC-7).
- **AC-3** Given cờ pause bật quá 1 giờ, Then chip "Paused for 1h …" tông `warning`.
- **AC-4** Given viewer, Then mọi switch disable với tooltip "Requires the operator role.", không có nút "Run job" và "Load feed".
- **AC-5** Given operator chạy `OtpScorecardJob` cho hôm qua, Then "Recent requests" đi qua "Queued" → "Running" với link tới lần chạy.
- **AC-6** Given chọn ngày hôm nay cho `OtpScorecardJob`, Then lỗi "Dates must be before today." và không gửi request.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-CTRL-01 | `operator`: tắt switch "GTFS-realtime consumer" (pause); chờ 5 phút; mở `/ops/jobs` kiểm timeline; quay lại tắt cờ | AC-1, AC-2; Jobs AC-7; dòng listener lại cập nhật sau khi tắt |
| E2E-CTRL-02 | `operator`: "Run a job" → `OtpScorecardJob` với hôm qua → xác nhận; thử thêm ngày hôm nay | AC-5, AC-6 |
| E2E-CTRL-03 | `viewer`: mở `/ops/controls`; axe | AC-4 |

E2E-CTRL-01 chạy trong project `late` của Playwright (như E2E-REPLAY-01) vì làm dữ liệu live stale trong vài phút. AC-3 kiểm ở component test (MSW trả `updatedAt` cũ hơn 1 giờ).

## 11. Câu hỏi còn mở

Không có.
