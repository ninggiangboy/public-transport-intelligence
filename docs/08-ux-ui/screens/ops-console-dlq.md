# Màn hình: Ops console — Dead letters

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35 §5.3, §5.6, DOC-37 §2.3, §2.7, §3, DOC-32 (E-40…E-48, E-52), DOC-33 §5.8, DOC-22 §1–3, DOC-15 §4.3, DOC-31 §8
> Người dùng chính: P5-10

## 1. Persona, use case, quyền

- **Persona:** PS-4 (desktop ≥ 1280 px).
- **Use case:** UC-08 (sửa và replay một record), UC-09 (xem auto-triage, xác nhận hàng loạt), FR-12.1, FR-09.3.
- **Quyền:** viewer xem mọi thứ, kể cả payload (đã làm sạch PII khi ghi). Nút thao tác chỉ render theo `allowedActions` của E-42 (viewer luôn `[]`) và checkbox chọn nhiều chỉ có với operator. Viewer thấy badge "Read-only".
- **Hiệu năng:** cuộn 10.000 dòng không có long task > 100 ms (DOC-34 §7).

## 2. URL và search params

`/ops/dlq` — theo DOC-34 §5.2:

| Param | Tab | Giá trị | Mặc định |
| --- | --- | --- | --- |
| `tab` | — | `review` \| `confirm` \| `closed` \| `actions` | `review` |
| `status` | review, closed | list trạng thái DOC-15 §4.3 | review: các trạng thái mở (`NEW`, `TRIAGING`, `TRIAGED`, `AUTO_REPLAY_SCHEDULED`, `PENDING_CONFIRM`, `MANUAL`, `REPLAY_REQUESTED`); closed: `REPLAYED`, `DISCARDED`, `RESOLVED` |
| `source`, `stage`, `category`, `severity`, `rule` | review, confirm, closed | list; `category` có thêm `unclassified` (chưa phân loại); `severity` là `0`–`2` | tất cả |
| `from`, `to` | review, closed | thời điểm | không có (E-40 không giới hạn khoảng) |
| `id` | mọi tab | id dead letter, mở khung chi tiết | — |
| `action`, `actor`, `window` | actions | list action; `auto` \| `system` \| `user`; `24h` \| `7d` | tất cả; tất cả; `24h` |

- Tab `confirm` cố định `status=PENDING_CONFIRM`, không có bộ lọc trạng thái; vẫn nhận `source`, `category`.
- Tab `review` với trạng thái mặc định ghi ra URL là không có `status`; chọn tay (chip "Status") thì ghi list cụ thể, giới hạn trong nhóm trạng thái của tab.
- Tab "Review" gộp mọi trạng thái còn mở, gồm cả `PENDING_CONFIRM`; tab "Confirm" là lối tắt tới riêng nhóm đó để xác nhận hàng loạt.

## 3. Wireframe

Prototype (DR-88): [Ops — Dead letters](assets/ops-console-dlq.html).

Bố cục danh sách + khung chi tiết (`SplitView`, DOC-34 P-11):

```text
┌ sidebar ┬──────────────────────────────┬───────────────────────────────────────────────┐
│         │ Dead letters                 │ 0192f5a8 ⧉   DQ-01 · Schema check    ● Manual   │
│         │ 214 open · 37 new/h          │ Vehicle positions · received 4:29:41 PM ·      │
│         │ [Review 214][Confirm 9]      │ 1 replay                                       │
│         │ [Closed][Action log]         │          [Discard] [Edit payload] [Replay]     │
│         │ (Source ▾)(Error ▾)(AI ▾)    │ ┌ AI triage · Schema violation ──── ▮▯▯ 41% ┐   │
│         │ ┌──────────────────────────┐ │ │ Severity ⬣ Urgent (66%) · Sent to manual   │   │
│         │ │ 9 records wait for       │ │ │ review · jev@0.2.0 · classified 4:29:42 PM │   │
│         │ │ confirmation · Review ›  │ │ └────────────────────────────────────────────┘   │
│         │ └──────────────────────────┘ │ Payload        [Edited|Original]    ⧉ Copy     │
│         │ ☐ DQ-01          4:29:41 PM  │ ┌ vehicle_position.json ───────────────────┐   │
│         │   Vehicle positions · 0192f5a8│ │ 1 {                                     │   │
│         │   position.latitude must be … │ │ 2   "vehicle": { "id": "1742" },        │   │
│         │   Schema violation 41% ✎     │ │ 3 − "timestamp": 1790630980412,         │   │
│         │ ☐ DQ-07          4:29:40 PM  │ │ 3 + "timestamp": 1790630980,            │   │
│         │   Trip updates · 0192f5a7     │ │ …                                       │   │
│         │   Route 999 not in feed       │ └─────────────────────────────────────────┘   │
│         │   Unknown reference 88%       │ Validation                                    │
│         │ … (virtualized)              │ ✕ DQ-01 · Schema check                         │
│         │ ┌ 2 selected ──────────────┐ │   $.payload.position.latitude: must be …      │
│         │ │ [Replay selected][Discard]│ │ Kafka   Topic gtfs.vehicle_positions · p3 ·   │
│         │ └──────────────────────────┘ │         offset 18,204,771 · batch 0192f5a1 →  │
│         │                              │ History ● Sent to manual review · auto 4:29:42│
│         │                              │         ● Classified · auto · 41%    4:29:42  │
│         │                              │         ● Created                    4:29:41  │
│         │                              │ Replays (none)                                │
└─────────┴──────────────────────────────┴───────────────────────────────────────────────┘
```

Dialog discard:

```text
┌ Discard this record? ───────────────────────┐
│ It won't be replayed. The original payload  │
│ is kept for 30 days.                        │
│ Reason                                      │
│ (•) Duplicate of a processed record         │
│ ( ) Test data                               │
│ ( ) Unrecoverable                           │
│ ( ) Other                                   │
│ Note (optional)                             │
│ [                                         ] │
├─────────────────────────────────────────────┤
│                     [Cancel] [Discard record]│
└─────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu cột danh sách | `h1` "Dead letters" + dòng "{open} open · {createdLastHour} new/h" (E-41) | Badge "Read-only" cho viewer |
| Tab | shadcn `Tabs`: "Review" ({số mở}), "Confirm" (`byStatus.PENDING_CONFIRM`), "Closed", "Action log" | `push` |
| Bộ lọc | Chip "Source", "Error" (popover gồm stage, rule), "AI verdict" (category, gồm "Unclassified"), "Severity", "Status" (trong nhóm của tab), khoảng thời gian | Nhãn enum DOC-37 §3 |
| Dải gợi ý | `Callout tone="primary"` | Tab "Review" và có `PENDING_CONFIRM` > 0: "{n} records wait for confirmation" + "Review" (→ tab "Confirm"). Tab "Confirm": "Auto-triage suggested a replay for these records." |
| Danh sách | `SplitView` cột trái 400 px; `DataTable virtual` một cột ghép (giữ virtualization và `aria-rowcount`, DS-06) + `useInfiniteQuery` trang 200 | Mỗi mục: checkbox (operator), mã lỗi (`ruleId` hoặc tên ngắn của `errorClass`, mono) + thời điểm (có giây), "{source} · {shortId}", `errorMessage` 1 dòng (tooltip đầy đủ), chip AI (category + `ConfidenceChip`, hoặc "Unclassified"), icon `PencilLine` khi `hasEditedPayload`, `StatusPill` nhỏ khi trạng thái khác nhóm mặc định của tab |
| Đếm | chữ phụ cuối danh sách | "{loaded} loaded" và "+" khi còn trang |
| Thanh hàng loạt | thanh dính đáy cột danh sách khi có mục chọn | "{n} selected"; tab Review: "Replay selected" (chỉ mục `NEW`/`MANUAL`), "Discard selected"; tab Confirm: "Confirm replay" |
| Tab Action log | `DataTable virtual` toàn chiều rộng (không có khung chi tiết cố định; bấm dòng mở chi tiết dead letter) | Cột: "Time", "Action", "Actor" (`auto` → "Auto-triage", `system:*` → tên dịch vụ, `user:*` → tên), "Confidence", "Dead letter" (`IdText`), "Source", "Current status", "Details" (tóm tắt: `reason`, `note`, `changed_paths`, `replay_request_id`) |
| Khung chi tiết | cột phải | §6.1; chưa chọn → `EmptyState` "Select a record to see details" |
| Trình sửa | `JsonEditor` (lazy) thay khối Payload | §6.2 |
| Dialog | `ConfirmDialog` | Replay, Confirm replay; discard có lựa chọn lý do + ghi chú (§6); resolve có `reason` "Note" |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Tóm tắt | E-41 | `['etl', 'dlq', 'summary']` | `dlq.changed` → invalidate (debounce 2 s); 60 s |
| Danh sách | E-40 `?status&source&stage&category&severity&ruleId&from&to&limit=200` | `['etl', 'dlq', 'list', filters]` (infinite) | `CREATED`/`BULK_UPDATED` → refetch trang đầu (debounce 2 s); `UPDATED` → sửa `status` của dòng cùng `id` (DOC-26 §9) |
| Hàng xác nhận | E-40 `?status=PENDING_CONFIRM&limit=200` | `['etl', 'dlq', 'list', { status: ['PENDING_CONFIRM'], … }]` | như trên |
| Chi tiết | E-42 | `['etl', 'dlq', 'detail', id]` | `UPDATED` cùng `id` → invalidate |
| Nhật ký | E-48 `?from&to&action&actorType&limit=200` | `['etl', 'dlq', 'actions', filters]` (infinite) | `dlq.changed` → refetch trang đầu (debounce 2 s) |
| Replay của record | E-52 (từ `replays[]`) | `['etl', 'replay', id]` | Poll 2 s khi khung chi tiết mở và replay chưa kết thúc |
| Kênh SSE | `dlq` | — | Mở khi vào `/ops/dlq` |

- Dòng không còn khớp bộ lọc sau `UPDATED` (ví dụ chuyển sang `REPLAYED` khi đang ở tab "Review") **không** bị gỡ ngay: dòng mờ đi, `StatusPill` đổi, và rời danh sách ở lần refetch kế tiếp. Người dùng không mất vị trí (P-5).
- Mỗi mutation dùng một `Idempotency-Key` sinh khi mở dialog hoặc bấm nút, giữ nguyên khi thử lại do lỗi mạng (DOC-31 §8).

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi bộ lọc / tab | URL (`replace` cho bộ lọc, `push` cho tab); danh sách tải lại, giữ dữ liệu cũ | — |
| Bấm mục / `Enter` | `id=<id>` (`push`), khung chi tiết | E-42 404 → "This dead letter no longer exists." (đã bị dọn sau 30 ngày) |
| "Edit payload" | §6.2 | — |
| "Replay" (`allowedActions` có `replay`) | `ConfirmDialog` "Replay this record?" · "The {edited\|original} payload is sent through the normal pipeline. If it fails again, it returns to New." → E-44 lạc quan: `StatusPill` → "Replay requested"; toast "Request sent" + "View replay" | 409 `dlq-invalid-state` → hoàn tác + thông báo DOC-37 §2.3 + refetch; 409 `replay-already-running` → mở replay hiện có; lỗi khác → hoàn tác, toast "Replay failed to start" |
| Replay kết thúc (`dlq.changed` `UPDATED` → `REPLAYED` hoặc `NEW`) | Khung chi tiết cập nhật; `REPLAYED` → toast `success` "Record replayed"; về `NEW` → toast `warning` "Replay failed again: {errorMessage}" (lấy từ E-42 sau refetch) | — |
| "Confirm replay" (`confirm`, trạng thái `PENDING_CONFIRM`) | Như Replay với E-45; mô tả nêu "Auto-triage suggested a replay with {confidence} confidence." | Như Replay |
| "Discard" (`discard`) | `ConfirmDialog tone="danger"` "Discard this record?" · "It won't be replayed. The original payload is kept for 30 days." với nhóm radio "Reason" ("Duplicate of a processed record", "Test data", "Unrecoverable", "Other") và ô "Note" (tùy chọn; bắt buộc ≥ 3 ký tự khi chọn "Other"). Gửi E-46 với `reason` = "{lựa chọn}" hoặc "{lựa chọn}: {note}" (3–500 ký tự); toast "Record discarded" | Lỗi hiện trong dialog (DS-07) |
| "Resolve" (`resolve`, trạng thái `MANUAL`) | `ConfirmDialog` "Mark as resolved?" · "Use this when the data was fixed outside the pipeline." với `reason` "Note" (3–500) → E-47; toast "Record resolved" | Như trên |
| Chọn nhiều + "Confirm replay" (tab Confirm) | `ConfirmDialog` "Confirm replay for {n} records?" → E-45 cho từng dòng, **tối đa 4 request song song**, mỗi dòng một `Idempotency-Key`; thanh tiến trình "{done} of {n}"; kết thúc: toast "{ok} confirmed, {failed} failed" (DOC-37 §2.7) | Dòng lỗi giữ lựa chọn, icon `CircleAlert` với tooltip lỗi |
| Chọn nhiều + "Replay selected" / "Discard selected" (tab review) | Như trên với E-44 / E-46 (một `reason` chung cho discard); dòng không có trạng thái phù hợp bị bỏ qua và được đếm vào "skipped" | Toast "{ok} replayed, {failed} failed, {skipped} skipped" |
| Chọn tối đa | 100 dòng; checkbox đầu bảng chọn các dòng đã tải (tối đa 100) | Vượt → "You can select up to 100 records at a time." |
| `j` / `k` | Mục kế / trước; khung chi tiết theo mục mới | — |
| Bấm `batchId` trong chi tiết | `/ops/batches/<batchId>` | — |
| Bấm một replay trong chi tiết | `/ops/replay?tab=history&replay=<id>` | — |
| Dải gợi ý "Review" | Chuyển tab "Confirm" | — |

### 6.1 Khung chi tiết

- **Đầu:** `shortId` (mono) + `CopyButton`; `ruleId` · stage; `StatusPill`; dòng phụ "{source} · received {time} · {n} replays" (`replayCount`); `errorClass` (mono) và `errorMessage` (giữ xuống dòng, tối đa 10 dòng rồi "Show more") nằm trong khối "Validation" bên dưới.
- **Nút** (góc phải đầu khung): chỉ các mục trong `allowedActions`, thứ tự "Discard" (viền, chữ `danger`), "Resolve", "Edit payload", rồi nút primary "Confirm replay" hoặc "Replay". Không có mục nào và người dùng là operator → chữ "No actions available in status {status}."
- **AI triage:** `Callout tone="primary"` tiêu đề "AI triage · {category}" + `ConfidenceChip`; dòng "Severity {SeverityBadge} ({confidence})" và quyết định định tuyến (hành động triage cuối trong `actions`: "Auto-replay scheduled", "Sent for confirmation", "Sent to manual review"); "{modelVersion} · classified {time} · {n} attempts". Chưa phân loại → "Not yet classified". Tooltip `help.dlqAutoReplay`. Triage **không** đề xuất payload sửa; khối này chỉ phân loại và định tuyến (DOC-24).
- **Payload:** `JsonViewer` khối code tối, tên file "{entity_type}.json". Có `editedPayload` thì `SegmentedControl` "Edited" / "Original"; "Edited" hiện diff so với `rawPayload` (DOC-35 §5.6). Không parse được thì hiện văn bản thô. Nút "Copy".
- **Validation:** stage, `ruleId` + tên rule (`dq.<id>`), `errorClass`, `errorMessage`. Chỉ có rule đã hỏng (API không trả kết quả các rule khác).
- **Kafka:** `KeyValueList` "Topic", "Partition", "Offset", "Timestamp", "Batch" (link).
- **History:** `ActivityTimeline` từ `actions` (mới nhất trên): nhãn action (DOC-37 §3.5), actor, confidence (nếu có), tóm tắt `details`, thời điểm.
- **Replays:** mỗi `replays[]`: `StatusPill domain="replay"`, "Requested by {name} · {time}", "Finished {time}", link.

### 6.2 Sửa payload

1. "Edit payload" thay khối Payload bằng `JsonEditor` (cùng khối code tối), nội dung ban đầu = `editedPayload` (pretty, 2 dấu cách) nếu có, ngược lại `rawPayload` pretty-print (không parse được thì văn bản gốc).
2. Lint JSON phía client; dưới trình sửa ghi "Valid JSON" (chấm `success`) hoặc lỗi cú pháp. Nút "Save" và "Save & replay" disable khi JSON sai cú pháp hoặc không đổi so với ban đầu.
3. "Save" → E-43 (≤ 1 MiB; lớn hơn chặn ở client với "Payload is larger than 1 MiB."). Thành công: đóng trình sửa, "Edited" được chọn (diff), toast "Payload saved", icon `PencilLine` xuất hiện trên mục. "Save & replay" (khi `allowedActions` có `replay`) làm như "Save" rồi mở ngay dialog Replay; lưu lỗi thì không mở dialog.
4. 422 `invalid-payload`: `errors[]` gắn vào dòng theo JSON Pointer (DS-08), danh sách lỗi dưới trình sửa; 422 `pii-not-allowed` và `business-key-changed`: dải `danger` trên trình sửa với `detail` của API; 409 `dlq-invalid-state`: thông báo DOC-37 §2.3, trình sửa giữ nội dung để sao chép.
5. "Cancel" khi có thay đổi → `ConfirmDialog` "Discard your changes?" (nút "Discard changes" / "Keep editing"). Chọn mục khác (bấm hoặc `j`/`k`) khi đang sửa có cùng hỏi. `Esc` trong trình sửa không bỏ chọn mục.
6. Sau khi lưu, nút "Replay" dùng payload đã sửa; dialog Replay ghi rõ "The edited payload is sent…".

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Dòng tóm tắt skeleton; danh sách 12 mục skeleton; khung chi tiết `PanelSkeleton variant="detail"`; trình sửa: khung cao 360 px với `LoaderCircle` khi chunk CodeMirror đang tải |
| Empty (Review) | "No open dead letters" · "Records that fail validation show up here." + "View closed" |
| Empty (Closed) | "No closed dead letters" |
| Empty (confirm) | "Nothing to confirm" · "Auto-triage sends records here when it isn't sure a replay will succeed." |
| Empty (có lọc) | "No dead letters match these filters" + "Clear filters" |
| Empty (action log) | "No actions in this period" |
| Error | DOC-37 §2.3, §2.6; lỗi chunk CodeMirror → "Couldn't load the editor." + "Retry" |
| Stale | `StaleBanner` toàn cục |
| Không có quyền | DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Dead letters" |
| Tóm tắt | "{n} open · {m} new/h" · "{n} records wait for confirmation" · "Review" · "Auto-triage suggested a replay for these records." · "{n} selected" |
| Tab | "Review", "Confirm", "Closed", "Action log" |
| Bộ lọc | "Source" · "Error" ("Stage", "Rule") · "AI verdict" (category + "Unclassified") · "Severity" · "Status" · "Any time" · "Action" · "Actor": "Auto-triage", "System", "People" · "Clear filters" |
| Cột (action log) | "Time", "Action", "Actor", "Confidence", "Dead letter", "Source", "Current status", "Details" |
| Đếm | "{n} loaded" |
| Chi tiết | "{source} · received {time} · {n} replays" · "AI triage · {category}" · "Severity" · "{model} · classified {time} · {n} attempts" · "Not yet classified" · "Payload" · "Original" · "Edited" · "Copy" · "Valid JSON" · "Validation" · "Kafka" · "Topic", "Partition", "Offset", "Timestamp", "Batch" · "History" · "Replays" · "Requested by {name} · {time}" · "No actions available in status {status}." · "Show more" · "Select a record to see details" |
| Nút | "Edit payload" · "Save" · "Save & replay" · "Cancel" · "Replay" · "Confirm replay" · "Discard" · "Discard record" · "Resolve" · "Replay selected" · "Discard selected" · "View closed" |
| Dialog | "Replay this record?" · "The edited payload is sent through the normal pipeline. If it fails again, it returns to New." · "The original payload is sent through the normal pipeline. If it fails again, it returns to New." · "Auto-triage suggested a replay with {confidence} confidence." · "Discard this record?" · "It won't be replayed. The original payload is kept for 30 days." · "Reason" · "Duplicate of a processed record", "Test data", "Unrecoverable", "Other" · "Note (optional)" · "Mark as resolved?" · "Use this when the data was fixed outside the pipeline." · "Note" · "Confirm replay for {n} records?" · "Discard your changes?" · "Discard changes" · "Keep editing" |
| Phản hồi | "Request sent" + "View replay" · "Record replayed" · "Replay failed again: {error}" · "Replay failed to start" · "Record discarded" · "Record resolved" · "Payload saved" · "{ok} confirmed, {failed} failed" · "{ok} replayed, {failed} failed, {skipped} skipped" · "{done} of {n}" |
| Giới hạn | "You can select up to 100 records at a time." · "Payload is larger than 1 MiB." |
| Không còn | "This dead letter no longer exists." |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given 10.000 dòng DLQ (fixture), When cuộn hết danh sách, Then không có long task > 100 ms và DOM có ≤ 60 dòng.
- **AC-2** Given operator và dòng `NEW` hoặc `MANUAL` có `latitude` ngoài vùng phục vụ, When sửa payload hợp lệ → "Save" → "Replay", Then dòng thành "Replay requested" ngay, rồi "Replayed" khi job xong, và bản ghi xuất hiện trong warehouse (UC-08).
- **AC-3** Given payload sửa sai schema, When "Save", Then lỗi hiện đúng dòng của trường theo JSON Pointer và payload không được lưu.
- **AC-4** Given 5 dòng `PENDING_CONFIRM`, When chọn cả 5 → "Confirm replay", Then tối đa 4 request đồng thời, mỗi request có `Idempotency-Key` khác nhau, và toast tổng hợp đúng số.
- **AC-5** Given viewer, Then không có checkbox, không có nút thao tác trong khung chi tiết, có badge "Read-only".
- **AC-6** Given tab "Action log" với `actor=auto`, Then chỉ có hành động của auto-triage kèm confidence (UC-09).
- **AC-7** Given đang sửa payload có thay đổi, When bấm `j` hoặc chọn mục khác, Then hỏi "Discard your changes?" thay vì mất bản sửa; `Esc` trong trình sửa không bỏ chọn mục.
- **AC-8** Given mục đổi trạng thái do người khác (SSE `UPDATED`), Then `StatusPill` đổi tại chỗ mà danh sách không nhảy.
- **AC-9** Given discard với lý do "Test data" và ghi chú "load test", Then E-46 nhận `reason` = "Test data: load test"; chọn "Other" mà ghi chú trống thì nút "Discard record" disable.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-DLQ-01 | `viewer`: mở `/ops/dlq`; đổi bộ lọc; chọn một mục; axe | AC-5; bộ lọc có trên URL |
| E2E-DLQ-02 | `operator`: `POST /sim/scenarios/bad-data` với `{"ratio": 0.01, "kinds": ["out_of_bbox"], "entityTypes": ["VEHICLE_POSITION"], "duration": "PT1M"}` (DOC-25 §7.4); lọc `stage=QUALITY`, mở một record → "Edit payload" → đổi `latitude` thành chuỗi `"abc"` → "Save" | AC-3 |
| E2E-DLQ-03 | Tiếp E2E-DLQ-02: sửa `latitude`/`longitude` về tọa độ trong Minneapolis (44.97, −93.27) → "Save" → "Replay" → xác nhận; chờ `REPLAYED` | AC-2; toast "Record replayed"; lịch sử có "Payload edited", "Replay requested", "Replayed" |
| E2E-DLQ-04 | `operator`: kịch bản `bad-data` như E2E-DLQ-02 với `kinds: ["unknown_route"]`; chờ triage-worker đưa ≥ 2 record vào `PENDING_CONFIRM`; tab "Confirm" → chọn hết → "Confirm replay". **Chạy từ P6** (cần triage-worker, DOC-24); ở P5, AC-4 được kiểm bằng component test với MSW | AC-4 (đếm request bằng `page.on('request')`) |

AC-1 đo ở component test DS-06 và Playwright trace trong nightly (DOC-44 §10).

## 11. Câu hỏi còn mở

Không có.
