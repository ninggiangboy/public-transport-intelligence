# Màn hình: Ops console — Replay raw zone

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35, DOC-37 §2.3, §2.7, §3, DOC-32 (E-50…E-53, E-32, E-35, E-60), DOC-22 §4, §6, ADR-0013
> Người dùng chính: P5-11

## 1. Persona, use case, quyền

- **Persona:** PS-4 (desktop ≥ 1280 px), sau khi sửa lỗi logic hoặc khi thiếu dữ liệu.
- **Use case:** UC-10 (replay một khoảng thời gian), FR-12.2, FR-12.3.
- **Quyền:** viewer xem lịch sử và chi tiết. Tab "New replay" và E-53 chỉ operator; viewer thấy tab bị ẩn và badge "Read-only". Viewer mở `tab=new` → chuyển sang `history`.

## 2. URL và search params

`/ops/replay`:

| Param | Giá trị | Mặc định |
| --- | --- | --- |
| `tab` | `new` \| `history` | operator: `new`; viewer: `history` |
| `source`, `from`, `to`, `recompute` | điền sẵn form (tab `new`); `recompute` là `1`/`0` | — |
| `kind` | `RAW_RANGE` \| `DLQ_RECORD` (history) | tất cả |
| `status` | list trạng thái replay (history) | tất cả |
| `replay` | id replay, mở drawer | — |

- Form đọc param **một lần** khi mở; sửa form không ghi ngược lên URL (form là trạng thái tạm, P-4 áp cho bộ lọc và đối tượng đang xem). Nguồn tạo link điền sẵn: runbook RB-11 và trang lineage.
- `from`, `to` là thời điểm ISO UTC (DOC-34 §5.1).

## 3. Wireframe

Prototype (DR-88): [Ops — Replay](assets/ops-console-replay.html).

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ Operations / Replay                                                            │
│         │ Replay from the raw zone                                                       │
│         │ Re-run stored messages through the pipeline after a fix, an outage or a schema change. │
│         │ [New replay] [History ● 1 running]                                             │
│         │ ┌ ① What to replay ──────────────────────────────┐ ┌ Estimated work ─────────┐ │
│         │ │ ┌ Trip updates ●┐┌ Vehicle pos. ┐┌ Ticket sales┐│ │ ≈ 1.15M messages        │ │
│         │ │ │gtfs.trip_upd… ││gtfs.vehicle… ││ticketing.…  ││ │ Source   Trip updates   │ │
│         │ │ │3.8M / 24 h    ││8.3M / 24 h   ││164K / 24 h  ││ │ Range    24 hours       │ │
│         │ │ └───────────────┘└──────────────┘└─────────────┘│ │ Duration about 6 min    │ │
│         │ │ + Sale points · To reload a GTFS schedule, …    │ │ Raw zone coverage 100%  │ │
│         │ ├ ② Record time range ────────────────────────────┤ │ Then recompute  Yes     │ │
│         │ │ [Last hour][Last 24 hours][Yesterday]           │ │ ┌ ⚠ A replay for this  ┐│ │
│         │ │ From [Sep 28, 12:00 AM] → To [Sep 29, 12:00 AM] CDT │ │ source is already…   ││ │
│         │ │ Processed per hour · last 24 h  ▂▃▅▇▇▆▅▃▂▂▃▅▆  │ │ └──────────────────────┘│ │
│         │ │ Matches business time Sep 28, 2:30 PM → Sep 29, │ │ [Start replay]          │ │
│         │ │ 2:30 PM (offset +14 h 30 min).                  │ └─────────────────────────┘ │
│         │ ├ ③ Options ──────────────────────────────────────┤                             │
│         │ │ Recompute analytics for this period   [●━━ On]  │                             │
│         │ │ Re-runs bunching, disruption and ticketing …    │                             │
│         │ └─────────────────────────────────────────────────┘                             │
└─────────┴──────────────────────────────────────────────────────────────────────────────┘
```

Tab "History":

```text
│ Status    Kind        Source            Range                    Progress       Recompute Requested         │
│ ● Running Time range  Trip updates      Sep 28 00:00 → Sep 29 …  ▬▬▬▭▭ 412K/1.15M Yes     operator · 3:39 PM│
│ ● Done    Dead letter Vehicle positions 0192f5b2                 1 written      No      operator · 2:10 PM│
│ ● Failed  Time range  Ticket sales      Sep 27 00:00 → Sep 28 …  —              Yes     viewer… · 9:02 AM │
```

Drawer (520 px):

```text
┌──────────────────────────────────────────────┐
│ ● Running  Replay 0192f5a0 ⧉            [×]  │
│ Time range · Trip updates                    │
│ Sep 28 12:00 AM → Sep 29 12:00 AM CDT        │
│ Requested by operator · 3:39 PM              │
│ Step: replayRecords                          │
│ [███████████░░░░░░░] ~36%   412K of ~1.15M   │
│ Written 411K · Skipped 950 · updated 4s ago  │
│ Run #4127 →                                  │
└──────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | `PageHeader` | Tiêu đề "Replay from the raw zone" + một câu giải thích. Tab "History" có badge "{n} running" khi có replay `PENDING`/`RUNNING` |
| Form | react-hook-form + zod, ba thẻ đánh số ①②③ | Schema `rawReplayForm` trong `src/features/ops/replay/schema.ts` |
| ① Nguồn | Nhóm thẻ radio (`RadioGroup` hiển thị dạng thẻ) | 4 nguồn của DOC-22 §4.1 (nhãn DOC-37 §3), mỗi thẻ có topic (mono) và "{n} / 24 h" (tổng `read` của nguồn trong 24 giờ, E-31). `GTFS_STATIC` không có; ghi chú "To reload a GTFS schedule, run GtfsStaticLoadJob from Controls." (link `/ops/controls#run-job`) |
| ② Khoảng | Chip preset + `TimeRangePicker granularity="minute"` | Presets "Last hour", "Last 24 hours", "Yesterday" (theo giờ agency). Biểu đồ cột nhỏ "Processed per hour · last 24 h" (E-31 `bucket=1h` của nguồn đang chọn), tô đậm phần giao với khoảng đã chọn; khoảng nằm ngoài 24 giờ thì không tô. Dòng phụ khoảng event time ước tính = khoảng record + `clockOffset` của E-60, tooltip `help.replayRecordTime` |
| ③ Tùy chọn | `Switch` + mô tả | "Recompute analytics for this period", mặc định bật |
| Ước lượng | thẻ dính bên phải (320 px): số lớn "≈ {messages}", `KeyValueList` (Source, Range, Duration, Raw zone coverage, Then recompute) + `Callout warning` cho từng `warnings[]` | Tự tải lại khi form hợp lệ và thay đổi (debounce 500 ms) |
| Nút | `Button` primary "Start replay" trong thẻ ước lượng | Disable khi form sai, đang gửi, hoặc E-53 báo đang có replay cùng nguồn |
| Lịch sử | `DataTable` + infinite (trang 50) | Cột: "Status" (`StatusPill domain="replay"`), "Kind", "Source", "Range" (record time; `DLQ_RECORD` → `IdText` của dead letter), "Progress" (`Progress` + "{read} / {estimate}" khi đang chạy; "{written} written" khi xong; lỗi thì `message` rút gọn), "Recompute", "Requested" ("{name} · {time}") |
| Drawer | `DetailDrawer` 520 px | §6.1 |

Kiểm tra phía client (lặp lại quy tắc của API để phản hồi ngay; API vẫn là nơi quyết định):

| Quy tắc | Thông báo lỗi dưới trường |
| --- | --- |
| `from < to` | "End must be after start." |
| Khoảng ≤ 7 ngày | "Max range is 7 days." |
| `to ≤ now − 10 min` (giờ thật) | "End must be at least 10 minutes ago, so all raw files are written." |
| `from ≥ now − 29 days` | "Start must be within the last 29 days." |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Ước lượng | E-53 `?source&fromTs&toTs` | `['etl', 'replay', 'estimate', params]` | `staleTime` 30 s; không refetch nền |
| Khối lượng theo nguồn, biểu đồ theo giờ | E-31 `?from=now−24h&bucket=1h` | `['etl', 'jobs', 'summary', { window: '24h', bucket: '1h' }]` | 5 phút |
| Tạo | E-50 | mutation | — |
| Lịch sử | E-51 `?kind&status&from&to&limit=50` (mặc định 7 ngày) | `['etl', 'replays', filters]` (infinite) | 60 s; khi có replay `PENDING`/`RUNNING` trong trang đầu: 5 s |
| Chi tiết | E-52 | `['etl', 'replay', id]` | **2 s** khi `PENDING`/`RUNNING` và drawer mở (ADR-0013); dừng khi `DONE`/`FAILED` |
| Lệch đồng hồ | E-60 `clockOffset` | `['system', 'freshness']` | Từ khung |
| Lần chạy | E-32 (link) | — | — |

Không có kênh SSE riêng: `job.run` của `RawZoneReplayJob` không mang `replayRequestId`, nên màn này poll E-52.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Điền form hợp lệ | Thẻ "Estimated work" tải E-53: "≈ {messages}", "about {duration}", "Raw zone coverage {percent}" và mọi `warnings[]` nguyên văn | `basis = UNAVAILABLE` → "No estimate available" + cảnh báo của API; E-53 lỗi → "Couldn't estimate this replay." (vẫn cho gửi) |
| "Start replay" | `ConfirmDialog` "Replay {source} from {from} to {to}?" · "About {messages} messages will be reprocessed. Newer data is never overwritten." (+ "Analytics for this period will be recomputed." khi bật) → E-50 với `Idempotency-Key` sinh khi mở dialog → toast "Request sent" + "View replay"; chuyển `tab=history&replay=<id>` | 409 `replay-already-running` → dialog đổi thành "A replay is already running" + "View running replay" (`replay=<existingReplayId>`); 422 `replay-window-invalid` → lỗi gắn vào trường theo `errors[].field`; 422 `unsupported-source`, `analytics-recompute-unavailable` → dải lỗi trên form với `detail` |
| Bấm dòng lịch sử | `replay=<id>` (`push`), drawer | 404 → "This replay no longer exists." |
| Lọc lịch sử | `kind`, `status` trên URL | — |
| Replay `FAILED` với `runId` | Drawer có nút "Restart run" (operator) → cùng luồng E-35 như màn Jobs (DOC-22 §4.7) | 409 → toast `detail` |
| "Replay again" (drawer, `RAW_RANGE`) | `tab=new` với `source`, `from`, `to`, `recompute` điền sẵn | — |

### 6.1 Drawer replay

- Đầu: "Replay {shortId}" + `CopyButton`; `StatusPill`; "{kind} · {source}"; khoảng record time; "Requested by {name} · {time}"; "Recompute analytics: Yes/No".
- `RUNNING`: "Step: {step}"; `Progress` với phần trăm = `readCount / estimatedMessages` của E-53 cho cùng tham số (gọi một lần khi mở drawer; không có ước lượng → thanh không xác định, chỉ hiện số đếm); "{read} of ~{estimate}"; "Written {n} · Skipped {n} · updated {relative}".
- `DONE`/`FAILED`: "Finished {time} · {duration}"; `message` (nếu có); bảng `stats`: "Objects", "Lines read", "Outside range", "Processed", "Written", "Duplicates", "Skipped", "New dead letters", "Updated dead letters", "Resolved dead letters", "Event time range", và theo detector khi có `analytics` ("{detector}: {scopes} scopes, {upserted} updated, {deleted} removed").
- `DLQ_RECORD`: link tới dead letter (`/ops/dlq?id=<deadLetterId>`).
- Link "Run #{id}" → `/ops/jobs?run=<runId>`.
- `resolved > 0` → link "View resolved dead letters" → `/ops/dlq?status=RESOLVED&source=<source>&from=<startedAt>`.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Form hiện ngay; khối ước lượng skeleton 2 dòng; lịch sử 6 dòng skeleton |
| Empty (lịch sử) | "No replays in the last 7 days" · "Replays you start appear here." |
| Error | DOC-37 §2.3, §2.6 |
| Stale | Không hiện `StaleBanner` riêng ngoài khung |
| Không có quyền | Viewer: tab "New replay" ẩn; anonymous: DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Replay from the raw zone" · "Re-run stored messages through the pipeline after a fix, an outage or a schema change." · "{n} running" |
| Tab | "New replay", "History" |
| Form | "What to replay" · "{n} / 24 h" · "Record time range" · "From", "To" · "Processed per hour · last 24 h" · "Matches business time {from} → {to} (offset {offset})." · "Options" · "Re-runs bunching, disruption and ticketing detection for the replayed period." · "Last hour", "Last 24 hours", "Yesterday" · "Recompute analytics for this period" · "To reload a GTFS schedule, run GtfsStaticLoadJob from Controls." · "Start replay" |
| Lỗi trường | "End must be after start." · "Max range is 7 days." · "End must be at least 10 minutes ago, so all raw files are written." · "Start must be within the last 29 days." |
| Ước lượng | "Estimated work" · "≈ {messages}" · "messages" · "Source", "Range", "Duration", "Raw zone coverage", "Then recompute" · "about {duration}" · "No estimate available" · "Couldn't estimate this replay." |
| Dialog | "Replay {source} from {from} to {to}?" · "About {messages} messages will be reprocessed. Newer data is never overwritten." · "Analytics for this period will be recomputed." · "Start replay" |
| Phản hồi | "Request sent" + "View replay" · "A replay is already running" + "View running replay" |
| Lịch sử | cột "Status", "Kind", "Source", "Range", "Progress", "Recompute", "Requested" · "{read} / {estimate}" · "{n} written" |
| Drawer | "Replay {id}" · "Requested by {name} · {time}" · "Recompute analytics: {yes/no}" · "Step: {step}" · "{read} of ~{estimate}" · "Written {n} · Skipped {n} · updated {relative}" · "Finished {time} · {duration}" · nhãn `stats` ở §6.1 · "Run #{id}" · "Restart run" · "Replay again" · "View resolved dead letters" |
| Không còn | "This replay no longer exists." |
| Empty | "No replays in the last 7 days" · "Replays you start appear here." |

Tooltip `help.replayRecordTime` và `help.replayEstimate` ở DOC-37 §5.

## 9. Tiêu chí nghiệm thu

- **AC-1** Given operator điền nguồn và khoảng hợp lệ, Then ước lượng hiện trong ≤ 1 s sau khi ngừng gõ, kèm mọi cảnh báo của API.
- **AC-2** Given `to` là 5 phút trước, Then lỗi "End must be at least 10 minutes ago…" hiện dưới trường và nút bị disable.
- **AC-3** Given "Start replay" → xác nhận, Then drawer mở với trạng thái "Queued" rồi "Running" và thanh tiến trình tăng mỗi 2 s.
- **AC-4** Given một replay đang chạy cho cùng nguồn, When gửi lần hai, Then dialog "A replay is already running" có link tới replay hiện có (FR-12.3).
- **AC-5** Given replay xong, Then drawer có bảng `stats` và link tới lần chạy.
- **AC-6** Given viewer, Then chỉ có tab "History".
- **AC-7** Given chọn nguồn "Trip updates" và "Last 24 hours", Then biểu đồ "Processed per hour" tô đậm toàn bộ 24 cột và thẻ ước lượng có "Raw zone coverage".

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-REPLAY-01 | `operator`: nguồn `GTFS_RT_TRIP_UPDATE`, khoảng `[stackStart + 1 min, now − 11 min]` → chờ ước lượng → "Start replay" → chờ `DONE` | AC-1, AC-3, AC-5 |
| E2E-REPLAY-02 | Trong khi E2E-REPLAY-01 đang chạy, gửi lại cùng nguồn và khoảng | AC-4 |
| E2E-REPLAY-03 | `operator`: `to` = now − 5 min | AC-2; không có request E-50 |

E2E-REPLAY-01/02 cần dữ liệu raw zone cũ hơn 10 phút (`pti.replay.raw-settle`). Chúng nằm trong Playwright project `late`, chạy sau mọi project khác (`dependencies`); `globalSetup` ghi `stackStart` (thời điểm `make smoke` pass) vào `e2e/.state.json`, và test chờ tới khi `now − 11 min ≥ stackStart + 2 min` (tối đa 15 phút, `test.slow()`). E2E không đổi cấu hình settle để giữ đúng hành vi thật.

## 11. Câu hỏi còn mở

Không có.
