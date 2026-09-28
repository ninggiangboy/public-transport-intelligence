# Màn hình: Ops console — Pipeline (jobs) và batch lineage

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35 §5, §7, DOC-37 §3.3, §4, DOC-32 (E-30…E-37, E-52), DOC-33 §5.7, DOC-19 §2, DOC-28
> Người dùng chính: P5-09

## 1. Persona, use case, quyền

- **Persona:** PS-4 (desktop ≥ 1280 px, trong lúc xử lý sự cố).
- **Use case:** UC-07 (theo dõi và khởi động lại job), UC-10 bước 4 (theo dõi replay qua lần chạy), FR-12.5 (truy vết `batch_id`).
- **Quyền:** viewer xem. "Restart from failed step", "Stop run" và "Run a job" chỉ operator (DOC-27); viewer thấy badge "Read-only" ở đầu trang.
- **Tên:** nhãn điều hướng và tiêu đề là "Pipeline"; URL và tên file giữ `/ops/jobs`, `ops-console-jobs.md` (DOC-34 §4.1).

## 2. URL và search params

- `/ops/jobs` — `window` (`15m` \| `1h` \| `6h` \| `24h`, mặc định `1h`), `from`, `to` (có thì bỏ qua `window`; tối đa 24 giờ), `kind` (`BATCH_JOB` \| `STREAM`), `status` (list), `name` (list), `bucket` (`1m` \| `5m` \| `15m` \| `1h`), `run` (runId, mở drawer).
- `bucket` mặc định theo khoảng: ≤ 1 giờ → `1m`; ≤ 6 giờ → `5m`; > 6 giờ → `15m`.
- `/ops/batches/$batchId` — trang lineage, không có search params.

## 3. Wireframe

Prototype (DR-88): [Ops — Pipeline](assets/ops-console-jobs.html).

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ Operations / Pipeline                                                          │
│         │ Pipeline                                        [Grafana ↗] [Run a job]        │
│         │ ● All streaming stages healthy · 1 batch job failed in the last 24 h          │
│         │ ┌ Sources ──────┐ 142/s ┌ etl-stream ────┐ 140/s ┌ Warehouse ────┐           │
│         │ │ GTFS-rt &     │──────▶│ 58 batches/min │──────▶│ 139 written/s │           │
│         │ │ ticketing     │       │ p95 3.1 s      │       │ 0.4% skipped  │           │
│         │ │ 142 msg/s     │       │ 0 failed       │       │ ▁▂▃▅▇▇▇       │           │
│         │ │ 1 stale       │       └───────┬────────┘       └───────────────┘           │
│         │ └───────────────┘               ▼ 37/h                                       │
│         │                         ┌ Dead letters ──┐       ┌ Batch jobs ───┐           │
│         │                         │ 214 open       │       │ 1 running     │           │
│         │                         └────────────────┘       │ 1 failed / 24h│           │
│         │                                                  └───────────────┘           │
│         │ ┌ Throughput  [Read|Written]  Last hour ▾ ─┐ ┌ By source ───────────────────┐  │
│         │ │ ‾‾‾‾‾‾‾‾‾‾‾\___/‾‾‾‾‾‾‾‾‾ (gap = idle)  │ │ Vehicle positions 96/s  3 s  │  │
│         │ │ 3:32 PM                          Now     │ │ Trip updates      44/s  4 s  │  │
│         │ └──────────────────────────────────────────┘ │ Ticket sales   No data 18 min│  │
│         │                                              └──────────────────────────────┘  │
│         │ [Job runs] [Batch] [Streaming] [Failed 1]          (Status ▾) (Job ▾) (24 h ▾)   │
│         │ Status     Job                    Trigger          Started  Duration Read Written│
│         │ ● Running  OtpScorecardJob #4131  Manual · operator 4:30 PM 2m 10s  184K 112K   │
│         │ ● Failed   RawZoneReplayJob #4127 Replay · operator 3:39 PM 12m 9s  412K 411K   │
│         │ ● Done     gtfs-rt-vehicle-position 5:18 PM          Streaming 59.9s 7.3K 7.3K   │
│         │ …                                                      [Load older runs]       │
└─────────┴──────────────────────────────────────────────────────────────────────────────┘
```

Drawer lần chạy (680 px):

```text
┌───────────────────────────────────────────────────────────┐
│ ● Failed   Run #4127                                  [×] │
│ RawZoneReplayJob                                          │
│ Replay · operator · started 4:40:02 PM · 12m 9s           │
│ ┌ ⛔ Execution became stale ───────────────────────────┐   │
│ │ Exit: FAILED — {exitMessage}                         │   │
│ └──────────────────────────────────────────────────────┘   │
│ Steps                                                     │
│ ● replayRecords   Failed · read 412K · written 411K 12m 9s│
│   batch 0192f5a1… → · commits 823 · rollbacks 1           │
│   ▸ Execution context                                     │
│ Parameters                                                │
│ replayRequestId*  0192f5a0-…                              │
│ fromTs            2026-09-28T00:00Z                       │
│ Started by replay 0192f5a0… →                             │
├───────────────────────────────────────────────────────────┤
│ [Trace ↗] [Logs ↗] [⧉ Copy run ID]  [Stop run] [Restart from failed step] │
└───────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | `PageHeader` | Dòng phụ tóm tắt: "All streaming stages healthy" (chấm `success`) hoặc "{n} stages need attention" (`warning`), cộng "· {n} batch job failed in the last 24 h" khi có. Nút "Grafana" (link dashboard pipeline, DOC-28 §7) và "Run a job" (operator, → `/ops/controls#run-job`) |
| Sơ đồ chặng | `PipelineStages`: 5 thẻ nối bằng mũi tên có nhãn tốc độ; nét đứt chạy khi rate > 0 (DOC-35 §4.4) | §4.1 |
| Throughput | `TimeSeriesChart` đường + vùng | Mặc định hai series "Read", "Written" cộng mọi nguồn; `SegmentedControl` đổi sang "By source" (cột chồng theo nguồn cho chỉ số đang chọn). Bucket 0 là khoảng trống, không nội suy. Tooltip: batches, failed batches, read, written, skipped, duplicate, p95 batch time. Khoảng theo `window` |
| Theo nguồn | `Card` + 4 dòng | Mỗi `source` stream: tên (DOC-37 §3.1), msg/s bucket cuối, tuổi dữ liệu (E-60, trục event) hoặc "No data for {age}" tông `warning`, skipped/duplicates trong khoảng |
| Tab lần chạy | shadcn `Tabs`: "Job runs" (tất cả), "Batch" (`kind=BATCH_JOB`), "Streaming" (`kind=STREAM`), "Failed" (`status=FAILED`, kèm số) | Ghi `kind`/`status` lên URL; chip "Status", "Job" và chip khoảng thời gian cạnh tab |
| Danh sách lần chạy | `DataTable` virtual (tối đa ~6.000 dòng khi 24 giờ), `useInfiniteQuery` trang 100 | Cột: "Status" (`StatusPill domain="job"`), "Job" (tên mono + "Run #{id}" hoặc "{listener} · {minute}"), "Trigger" ("Schedule", "Manual · {name}", "Replay · {name}", "Streaming"; từ `request`), "Started" (`Timestamp`), "Duration" (đang chạy thì tăng dần), "Read", "Written", "Skipped", "Duplicates" (stream) |
| Drawer | `DetailDrawer` 680 px | §6.1 |
| Trang lineage | `KeyValueList`, `KpiCard`, `DataTable` DQ | §6.2 |

### 4.1 Sơ đồ chặng

| Thẻ | Số chính | Dòng phụ | Tông viền/chấm | Nguồn |
| --- | --- | --- | --- | --- |
| "Sources" ("GTFS-rt & ticketing") | tổng msg/s đọc (bucket 1 phút cuối) | "{n} feeds · {m} stale" | `warning` khi có nguồn stale | E-31, E-60 |
| "etl-stream" | micro-batch/phút | "p95 {t} · {n} failed" | `danger` khi `failedBatches > 0` trong 5 phút; `warning` khi cờ pause đang bật ("Paused") | E-31, E-55 |
| "Warehouse" | record ghi/giây | "{p}% skipped" + sparkline | `neutral` | E-31 |
| "Dead letters" (nhánh dưới etl-stream) | số mở | "+{n}/h" | `warning` khi `createdLastHour` > 0 | E-41 |
| "Batch jobs" | số đang chạy | "{n} failed / 24 h" | `danger` khi có lần `FAILED` | E-31 `batchJobs` |

Bấm thẻ: "Sources" → cuộn tới "By source"; "etl-stream" → tab "Streaming"; "Dead letters" → `/ops/dlq`; "Batch jobs" → tab "Batch"; "Warehouse" không bấm được. Chỉ số Kafka (lag, broker) không có trong API sản phẩm; xem qua nút "Grafana" và demo console (DOC-48).

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Sơ đồ chặng, throughput, theo nguồn | E-31 `?from&to&bucket` | `['etl', 'jobs', 'summary', { from, to, bucket }]` | **10 s** khi trang hiển thị (DOC-33 §5.7); `job.run` → invalidate (tối đa mỗi 10 s) |
| DLQ, cờ, độ tươi | E-41, E-55, E-60 | như khung | Từ khung |
| Danh sách | E-30 `?from&to&kind&status&name&limit=100` | `['etl', 'jobs', 'list', filters]` | SSE `job.run`: thay dòng theo `runId` trong trang đầu, chèn nếu mới và khớp bộ lọc (DOC-26 §9); dòng stream: refetch trang đầu mỗi 60 s |
| Chi tiết | E-32 | `['etl', 'job', runId]` | `job.run` cùng `runId` → invalidate (tối đa mỗi 2 s); lần chạy đang chạy: thêm poll 5 s khi SSE hỏng |
| Yêu cầu restart/stop | E-35, E-36 → E-34 | mutation, rồi `['etl', 'job-request', id]` | Poll E-34 mỗi 2 s tới khi `RUNNING`, `DONE`, `REJECTED` hoặc `FAILED` (tối đa 60 s) |
| Lineage | E-37 | `['etl', 'batch', batchId]` | Không refetch tự động; nút "Refresh" |
| Kênh SSE | `jobs` | — | Mở khi vào `/ops/jobs` hoặc `/ops/batches/*` |

- Khi `window` là khoảng tương đối, `to` = giờ máy hiện tại tại lúc query chạy; mỗi lần refetch tính lại (cửa sổ trượt). Với `from`/`to` cố định thì không trượt.
- Thời gian ở màn này là **trục audit** (DOC-34 §8): tương đối theo đồng hồ máy.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi khoảng, bucket, tab, bộ lọc | URL (`replace` cho bộ lọc, `push` cho tab); sơ đồ, biểu đồ và danh sách tải lại | E-31 400 (quá nhiều bucket) → không xảy ra vì bucket tự nâng; nếu có → inline error kèm gợi ý "Try a larger interval." |
| Kéo chọn vùng trên biểu đồ throughput | Đặt `from`/`to` theo vùng chọn (`push`); nút "Reset zoom" | — |
| Bấm dòng / `Enter` | `run=<runId>` (`push`), drawer | E-32 404 → drawer "This run is no longer available." (metadata đã bị dọn, DOC-19 §9) |
| "Restart from failed step" (operator; chỉ khi `restartable`) | `ConfirmDialog` "Restart {job} #{id}?" · "It continues from the last committed chunk." → E-35 → nút thành "Restart requested…" và theo dõi E-34 | 409 `job-not-restartable` → toast với `detail` của API; E-34 `REJECTED` → toast "Restart was rejected: {message}" |
| "Stop run" (operator; `status ∈ STARTING, STARTED`) | `ConfirmDialog` "Stop {job} #{id}?" · "The job stops at the next chunk boundary and can be restarted later." → E-36 → theo dõi E-34; `StatusPill` hiện `STOPPING` khi có `job.run` | 409 `job-not-running` → toast `detail` |
| E-34 `RUNNING` với `runId` mới (restart tạo execution mới) | Toast "Restarted as run #{id}" với nút "Open" (`run=<runId mới>`) | E-34 quá 60 s vẫn `PENDING` → toast `warning` "The batch service hasn't picked up the request yet." |
| "Trace" / "Logs" | Mở Grafana Explore (tab mới, `links` của E-32) | Không có `links` → ẩn nút |
| Bấm `batchId` | Tới `/ops/batches/<batchId>` | — |
| "Started by replay …" | Tới `/ops/replay?tab=history&replay=<id>` | — |
| "Started by job request …" | Popover chi tiết E-34 (người yêu cầu, thời điểm, tham số) | — |
| "Copy run ID" | Chép `runId` | — |

### 6.1 Drawer lần chạy

- **Đầu drawer:** `StatusPill` + "Run #{jobExecutionId}" (batch) hoặc "{listener} · {minute}" (stream); tên job (mono); "{trigger} · started {time} · {duration}". Khác `COMPLETED` thì `Callout tone="danger"` (hoặc `warning` với `COMPLETED_WITH_SKIPS`) tiêu đề là dòng đầu của `exitMessage`, thân "Exit: {exitCode} — {exitMessage}".
- **Chân drawer:** "Trace", "Logs", "Copy run ID" bên trái; "Stop run", "Restart from failed step" (primary) bên phải, chỉ operator.
- **`BATCH_JOB`:** "Steps" dạng `ActivityTimeline` (chấm tông theo trạng thái step), mỗi step (tên, `StatusPill`, read/write/filter, skip theo 3 loại, commit/rollback, thời lượng, `batchId` là link, "Execution context" thu gọn dùng `JsonViewer` với chuỗi đã cắt kèm ghi chú "Truncated to 2,500 characters" khi dài đúng giới hạn); bảng tham số (dấu `*` cho tham số định danh, cột "Name", "Value", "Type"); khối nguồn gốc (`request`).
- **`STREAM`:** bảng micro-batch (≤ 120): "Batch", "Status", "Write mode" (DOC-37 §3.3), "Instance", "Offsets" (dạng `p3: 1200–1260`), "Read", "Written", "Skipped", "Duplicates", "Event time" (min–max, trục event), "Duration", "Error" (`errorClass` + tooltip `errorMessage`); mỗi dòng có link "Trace"/"Logs" và link `batchId`.

### 6.2 Trang lineage `/ops/batches/$batchId`

- Tiêu đề "Batch {shortId}" + `CopyButton` id đầy đủ; dòng phụ "Stream micro-batch" hoặc "Batch step".
- `KeyValueList`: nguồn gốc (`runId` là link về `/ops/jobs?run=…&from=…&to=…` bao quanh `startedAt`), job/step hoặc listener/source/instance/offsets/write mode, thời gian, replay (link).
- `KpiCard` "Read", "Written", "Skipped", "Dead letters". Bấm "Dead letters" → `/ops/dlq?source=<source>&from=<startedAt>&to=<endedAt + 1m>` (E-40 không lọc theo `batchId`; khoảng thời gian cùng nguồn là xấp xỉ đủ dùng).
- Bảng `deadLetters.byStatus` (`StatusPill domain="dlq"` + số).
- Bảng DQ: "Rule", "Table", "Violations", "Checked"; `violationCount > 0` có tông `warning`.
- Nút "Trace", "Logs".
- 404 → "Batch not found" · "The batch ID may be mistyped, or its metadata was removed after 30 days."

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Thẻ và biểu đồ skeleton; bảng 10 dòng skeleton |
| Empty (danh sách) | "No runs in this period" · "Try a longer period or clear the filters." |
| Empty (throughput) | Biểu đồ trục trống với chữ "No streaming activity in this period" — đây là tín hiệu consumer dừng, nên không ẩn biểu đồ |
| Error | Mỗi vùng độc lập (DOC-37 §2.6) |
| Stale | `StaleBanner` toàn cục |
| Không có quyền | DOC-37 §2.5 cho anonymous |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Pipeline" · breadcrumb "Operations" / "Pipeline" · "All streaming stages healthy" · "{n} stages need attention" · "{n} batch job failed in the last 24 h" · "Grafana" · "Run a job" |
| Sơ đồ | "Sources" · "GTFS-rt & ticketing" · "{n} feeds · {m} stale" · "etl-stream" · "{n} batches/min" · "p95 {t} · {n} failed" · "Paused" · "Warehouse" · "{n} written/s" · "{p}% skipped" · "Dead letters" · "{n} open" · "+{n}/h" · "Batch jobs" · "{n} running" · "{n} failed / 24 h" |
| Theo nguồn | "By source" · "{rate} msg/s" · "No data for {age}" |
| Throughput | "Throughput" · "Read", "Written", "By source", "Skipped" · tooltip "Batches", "Failed batches", "Duplicates", "p95 batch time" · "Reset zoom" · "No streaming activity in this period" |
| Tab và bộ lọc | "Job runs", "Batch", "Streaming", "Failed" · "Status" · "Job" · "Last hour", "Last 6 hours", "Last 24 hours" |
| Cột | "Status", "Job", "Trigger", "Started", "Duration", "Read", "Written", "Skipped", "Duplicates" · "Run #{id}" · trigger "Schedule", "Manual · {name}", "Replay · {name}", "Streaming" |
| Danh sách | "Load older runs" |
| Drawer | "Exit: {code} — {message}" · "Steps" · "Parameters" · "Name", "Value", "Type" · "* Identifying parameter" · "Execution context" · "Truncated to 2,500 characters" · "Micro-batches" · "Write mode" · "Offsets" · "Event time" · "Error" · "Started by replay {id}" · "Started by job request {id}" · "Scheduled" (không có `request`) |
| Nút | "Restart from failed step" · "Stop run" · "Trace" · "Logs" · "Copy run ID" · "Refresh" |
| Xác nhận | "Restart {job} #{id}?" · "It continues from the last committed chunk." · "Stop {job} #{id}?" · "The job stops at the next chunk boundary and can be restarted later." |
| Phản hồi | "Restart requested…" · "Stop requested…" · "Restarted as run #{id}" · "Restart was rejected: {message}" · "Stop was rejected: {message}" · "The batch service hasn't picked up the request yet." |
| Không còn | "This run is no longer available." · "Batch not found" · "The batch ID may be mistyped, or its metadata was removed after 30 days." |
| Empty | "No runs in this period" · "Try a longer period or clear the filters." |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given 1 giờ dữ liệu stream, When mở `/ops/jobs`, Then tiêu đề là "Pipeline", biểu đồ "Throughput" có "Read" và "Written" (chế độ "By source" có một chuỗi cho mỗi nguồn), bucket trống hiện là khoảng trống.
- **AC-2** Given job batch đang chạy, Then dòng của nó cập nhật số đếm qua `job.run` (≤ 2 s một lần) mà không reload.
- **AC-3** Given operator và lần chạy `FAILED` restart được, When "Restart from failed step" → xác nhận, Then trong ≤ 10 s có toast "Restarted as run #…" và lần chạy mới xuất hiện ở đầu danh sách.
- **AC-4** Given lần chạy stream, Then drawer không có nút "Restart from failed step" (UC-07 E1) và có bảng micro-batch.
- **AC-5** Given viewer, Then không có nút "Restart from failed step", "Stop run", "Run a job" và có badge "Read-only".
- **AC-6** Given một `batchId` từ drawer, When bấm, Then trang lineage hiện nguồn gốc, số DLQ và kết quả DQ; link quay về đúng lần chạy.
- **AC-7** Given consumer bị tạm dừng 5 phút, Then biểu đồ throughput có khoảng trống tương ứng và thẻ "etl-stream" ghi "Paused".
- **AC-8** Given một lần chạy batch `FAILED` trong 24 giờ, Then dòng phụ đầu trang có "1 batch job failed in the last 24 h", thẻ "Batch jobs" tông `danger` và tab "Failed" có số 1.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-JOBS-01 | `viewer`: mở `/ops/jobs`; đổi khoảng sang 6 giờ; mở một lần chạy stream; axe | AC-1, AC-4, AC-5 |
| E2E-JOBS-02 | `operator`: E-33 chạy `AnalyticsRecomputeJob` 1 giờ; khi `STARTED` bấm "Stop run" → xác nhận; khi `STOPPED` bấm "Restart from failed step" | AC-2, AC-3; lần chạy mới `COMPLETED` |
| E2E-JOBS-03 | Từ drawer của E2E-JOBS-02 bấm `batchId` → trang lineage → link quay lại | AC-6 |

AC-7 được kiểm trong E2E-CTRL-01 (tạm dừng consumer ở màn Controls).

## 11. Câu hỏi còn mở

Không có.
