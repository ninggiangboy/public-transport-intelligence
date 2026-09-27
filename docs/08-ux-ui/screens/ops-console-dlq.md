# Màn hình: Ops console — Dead letters

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35 §5.3, §5.6, DOC-37 §2.3, §2.7, §3, DOC-32 (E-40…E-48, E-52), DOC-33 §5.8, DOC-22 §1–3, DOC-15 §4.3, DOC-31 §8
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
| `tab` | — | `records` \| `confirm` \| `actions` | `records` |
| `status` | records | list trạng thái DOC-15 §4.3 | các trạng thái mở (`NEW`, `TRIAGING`, `TRIAGED`, `AUTO_REPLAY_SCHEDULED`, `PENDING_CONFIRM`, `MANUAL`, `REPLAY_REQUESTED`) |
| `source`, `stage`, `category`, `severity`, `rule` | records | list; `category` có thêm `unclassified` (chưa phân loại); `severity` là `0`–`2` | tất cả |
| `from`, `to` | records | thời điểm | không có (E-40 không giới hạn khoảng) |
| `id` | mọi tab | id dead letter, mở drawer | — |
| `action`, `actor`, `window` | actions | list action; `auto` \| `system` \| `user`; `24h` \| `7d` | tất cả; tất cả; `24h` |

- Tab `confirm` cố định `status=PENDING_CONFIRM`, không có bộ lọc trạng thái; vẫn nhận `source`, `category`.
- Bộ lọc "Open" (mặc định) ghi ra URL là không có `status`; chọn tay thì ghi list cụ thể. Preset "All" ghi `status=all` và UI gửi E-40 không có `status`.

## 3. Wireframe

```text
┌────────────┬──────────────────────────────────────────────────────────────────┐
│ Jobs       │ Dead letters                                           Read-only │
│ Dead lett.◀│ ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────────────┐   │
│  (214)     │ │Open 214  │ │Needs confirm │ │Manual 199│ │New last hour 37  │   │
│ Replay     │ └──────────┘ │      9       │ └──────────┘ └──────────────────┘   │
│ Controls   │              └──────────────┘                                    │
│ Ticketing  │ [Records] [Confirm queue (9)] [Action log]                       │
│            │ [Open ▾] [Source ▾] [Stage ▾] [Category ▾] [Severity ▾] [Rule ▾] │
│            │ [Any time ▾]                                  {n} of {total}+     │
│            │ ☐ Created    Source        Stage   Rule  Error            Status │
│            │ ☐ 5:18:47 PM Vehicle pos.  Schema  DQ-01 $.payload.posi… Manual  │
│            │ ☐ 5:18:40 PM Trip updates  Business DQ-07 Route 999 not… New     │
│            │ … (virtualized)                                                  │
└────────────┴──────────────────────────────────────────────────────────────────┘
```

Drawer (720 px):

```text
┌──────────────────────────────────────────────────────────────┐
│ Dead letter 0192f5b2 ⧉                     ● Manual     [×]  │
│ Vehicle positions · Schema check · DQ-01 · created 5:18:47 PM│
│ SchemaViolationException                                     │
│ $.payload.position.latitude: must be between -90 and 90      │
│ [Edit payload] [Replay] [Discard] [Resolve]                  │
│ ── Triage ─────────────────────────────────────────────────  │
│ Category: Schema violation   [41% · Low confidence]          │
│ Severity: ⬣ High             [66% · Medium]                  │
│ Model jev@0.2.0 · classified 5:19:02 PM · 1 attempt          │
│ ── Payload ──  [Original] [Edited]                           │
│ { "schema_version": 1, "entity_type": "VEHICLE_POSITION", …  │
│ ── Kafka ──  gtfs.vehicle_positions · p7 · offset 881203     │
│ ── History ──                                                │
│ 5:18:47 PM  Created                                          │
│ 5:19:02 PM  Classified · auto · 41%                          │
│ 5:19:02 PM  Sent to manual review · auto                     │
│ ── Replays ── (none)                                         │
└──────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Thẻ tóm tắt | `StatCard` × 4 từ E-41: "Open", "Needs confirmation" (`byStatus.PENDING_CONFIRM`), "Manual review" (`byStatus.MANUAL`), "New in last hour" | Bấm thẻ → lọc tương ứng ("Needs confirmation" mở tab `confirm`) |
| Phân bố | Hai thanh ngang nhỏ (SVG) `openBySource`, `openBySeverity` dưới thẻ | Bấm đoạn → lọc |
| Tab | shadcn `Tabs`, nhãn "Confirm queue" có số | `push` |
| Bộ lọc | `MultiSelectFilter` × 6, `TimeRangePicker` ("Any time" mặc định) | Nhãn enum DOC-37 §3 |
| Bảng records | `DataTable virtual` + `useInfiniteQuery` trang 200 | Cột: chọn (operator), "Created" (`Timestamp` có giây), "Source", "Stage", "Rule", "Error" (`errorMessage` 1 dòng, tooltip đầy đủ), "Category" (+ `ConfidenceChip` nhỏ), "Severity" (`SeverityBadge`), "Replays" (`replayCount`/`autoReplayCount`), "Status" (`StatusPill domain="dlq"`), icon `PencilLine` khi `hasEditedPayload` |
| Đếm | chữ phụ | "{loaded} loaded" và "+" khi còn trang |
| Thanh hành động hàng loạt | thanh dính đáy bảng khi có dòng chọn | Tab records: "Replay selected" (chỉ dòng `NEW`/`MANUAL`), "Discard selected"; tab confirm: "Confirm replay" |
| Bảng confirm | `DataTable` (không virtual; tối đa vài trăm) | Cột: chọn, "Created", "Source", "Error", "Category" + `ConfidenceMeter`, "Suggested" ("Replay"), "Waiting" (`RelativeTime`) |
| Bảng action log | `DataTable virtual` + infinite | Cột: "Time", "Action", "Actor" (`auto` → "Auto-triage", `system:*` → tên dịch vụ, `user:*` → tên), "Confidence", "Dead letter" (`IdText`, bấm mở drawer), "Source", "Current status", "Details" (tóm tắt: `reason`, `note`, `changed_paths`, `replay_request_id`) |
| Drawer | `DetailDrawer` 720 px | §6.1 |
| Trình sửa | `JsonEditor` (lazy) trong drawer, thay khối Payload | §6.2 |
| Dialog | `ConfirmDialog` (có `reason` cho discard, resolve) | §6 |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Tóm tắt | E-41 | `['etl', 'dlq', 'summary']` | `dlq.changed` → invalidate (debounce 2 s); 60 s |
| Danh sách | E-40 `?status&source&stage&category&severity&ruleId&from&to&limit=200` | `['etl', 'dlq', 'list', filters]` (infinite) | `CREATED`/`BULK_UPDATED` → refetch trang đầu (debounce 2 s); `UPDATED` → sửa `status` của dòng cùng `id` (DOC-26 §9) |
| Hàng xác nhận | E-40 `?status=PENDING_CONFIRM&limit=200` | `['etl', 'dlq', 'list', { status: ['PENDING_CONFIRM'], … }]` | như trên |
| Chi tiết | E-42 | `['etl', 'dlq', 'detail', id]` | `UPDATED` cùng `id` → invalidate |
| Nhật ký | E-48 `?from&to&action&actorType&limit=200` | `['etl', 'dlq', 'actions', filters]` (infinite) | `dlq.changed` → refetch trang đầu (debounce 2 s) |
| Replay của record | E-52 (từ `replays[]`) | `['etl', 'replay', id]` | Poll 2 s khi drawer mở và replay chưa kết thúc |
| Kênh SSE | `dlq` | — | Mở khi vào `/ops/dlq` |

- Dòng không còn khớp bộ lọc sau `UPDATED` (ví dụ chuyển sang `REPLAYED` trong khi lọc "Open") **không** bị gỡ ngay: dòng mờ đi, `StatusPill` đổi, và rời danh sách ở lần refetch kế tiếp. Người dùng không mất vị trí (P-5).
- Mỗi mutation dùng một `Idempotency-Key` sinh khi mở dialog hoặc bấm nút, giữ nguyên khi thử lại do lỗi mạng (DOC-31 §8).

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi bộ lọc / tab | URL (`replace` cho bộ lọc, `push` cho tab); danh sách tải lại, giữ dữ liệu cũ | — |
| Bấm dòng / `Enter` | `id=<id>` (`push`), drawer | E-42 404 → "This dead letter no longer exists." (đã bị dọn sau 30 ngày) |
| "Edit payload" | §6.2 | — |
| "Replay" (`allowedActions` có `replay`) | `ConfirmDialog` "Replay this record?" · "The {edited\|original} payload is sent through the normal pipeline. If it fails again, it returns to New." → E-44 lạc quan: `StatusPill` → "Replay requested"; toast "Request sent" + "View replay" | 409 `dlq-invalid-state` → hoàn tác + thông báo DOC-37 §2.3 + refetch; 409 `replay-already-running` → mở replay hiện có; lỗi khác → hoàn tác, toast "Replay failed to start" |
| Replay kết thúc (`dlq.changed` `UPDATED` → `REPLAYED` hoặc `NEW`) | Drawer cập nhật; `REPLAYED` → toast `success` "Record replayed"; về `NEW` → toast `warning` "Replay failed again: {errorMessage}" (lấy từ E-42 sau refetch) | — |
| "Confirm replay" (`confirm`, trạng thái `PENDING_CONFIRM`) | Như Replay với E-45; mô tả nêu "Auto-triage suggested a replay with {confidence} confidence." | Như Replay |
| "Discard" (`discard`) | `ConfirmDialog tone="danger"` "Discard this record?" · "It won't be replayed. The original payload is kept for 30 days." với `reason` "Reason" (3–500) → E-46; toast "Record discarded" | Lỗi hiện trong dialog (DS-07) |
| "Resolve" (`resolve`, trạng thái `MANUAL`) | `ConfirmDialog` "Mark as resolved?" · "Use this when the data was fixed outside the pipeline." với `reason` "Note" (3–500) → E-47; toast "Record resolved" | Như trên |
| Chọn nhiều + "Confirm replay" (tab confirm) | `ConfirmDialog` "Confirm replay for {n} records?" → E-45 cho từng dòng, **tối đa 4 request song song**, mỗi dòng một `Idempotency-Key`; thanh tiến trình "{done} of {n}"; kết thúc: toast "{ok} confirmed, {failed} failed" (DOC-37 §2.7) | Dòng lỗi giữ lựa chọn, icon `CircleAlert` với tooltip lỗi |
| Chọn nhiều + "Replay selected" / "Discard selected" (tab records) | Như trên với E-44 / E-46 (một `reason` chung cho discard); dòng không có trạng thái phù hợp bị bỏ qua và được đếm vào "skipped" | Toast "{ok} replayed, {failed} failed, {skipped} skipped" |
| Chọn tối đa | 100 dòng; checkbox đầu bảng chọn các dòng đã tải (tối đa 100) | Vượt → "You can select up to 100 records at a time." |
| `j` / `k` | Dòng kế / trước; nếu drawer đang mở thì drawer theo dòng mới | — |
| Bấm `batchId` trong drawer | `/ops/batches/<batchId>` | — |
| Bấm một replay trong drawer | `/ops/replay?tab=history&replay=<id>` | — |

### 6.1 Drawer

- **Đầu:** "Dead letter {shortId}" + `CopyButton`; `StatusPill`; dòng phụ "{source} · {stage} · {ruleId} · created {time}"; `errorClass` (mono) và `errorMessage` (giữ xuống dòng, tối đa 10 dòng rồi "Show more").
- **Nút:** chỉ các mục trong `allowedActions`, thứ tự "Edit payload", "Confirm replay" hoặc "Replay", "Discard", "Resolve". Không có mục nào và người dùng là operator → chữ "No actions available in status {status}."
- **Triage:** "Category" + `ConfidenceChip`; "Severity" + `SeverityBadge` + `ConfidenceChip`; "Model {modelVersion} · classified {time} · {n} attempts"; chưa phân loại → "Not yet classified". Tooltip `help.dlqAutoReplay`.
- **Payload:** hai tab "Original" (`rawPayload` qua `JsonViewer`; không parse được thì hiện văn bản thô, mono) và "Edited" (chỉ khi có `editedPayload`). Nút "Copy".
- **Kafka:** "{topic} · partition {p} · offset {o} · {timestamp}"; "Batch {batchId}" (link).
- **History:** `actions` (cũ trước): thời điểm, nhãn action (DOC-37 §3), actor, confidence (nếu có), tóm tắt `details`.
- **Replays:** mỗi `replays[]`: `StatusPill domain="replay"`, "Requested by {name} · {time}", "Finished {time}", link.

### 6.2 Sửa payload

1. "Edit payload" thay khối Payload bằng `JsonEditor`, nội dung ban đầu = `editedPayload` (pretty, 2 dấu cách) nếu có, ngược lại `rawPayload` pretty-print (không parse được thì văn bản gốc).
2. Lint JSON phía client; nút "Save" disable khi JSON sai cú pháp hoặc không đổi so với ban đầu.
3. "Save" → E-43 (≤ 1 MiB; lớn hơn chặn ở client với "Payload is larger than 1 MiB."). Thành công: đóng trình sửa, tab "Edited" được chọn, toast "Payload saved", icon `PencilLine` xuất hiện trên dòng.
4. 422 `invalid-payload`: `errors[]` gắn vào dòng theo JSON Pointer (DS-08), danh sách lỗi dưới trình sửa; 422 `pii-not-allowed` và `business-key-changed`: dải `danger` trên trình sửa với `detail` của API; 409 `dlq-invalid-state`: thông báo DOC-37 §2.3, trình sửa giữ nội dung để sao chép.
5. "Cancel" khi có thay đổi → `ConfirmDialog` "Discard your changes?" (nút "Discard changes" / "Keep editing"). Đóng drawer hoặc chuyển dòng (`j`/`k`) khi đang sửa có cùng hỏi. `Esc` trong trình sửa không đóng drawer.
6. Sau khi lưu, nút "Replay" dùng payload đã sửa; dialog Replay ghi rõ "The edited payload is sent…".

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Thẻ skeleton; bảng 12 dòng skeleton; drawer `PanelSkeleton variant="detail"`; trình sửa: khung cao 360 px với `LoaderCircle` khi chunk CodeMirror đang tải |
| Empty (Open) | "No open dead letters" · "Records that fail validation show up here." |
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
| Thẻ | "Open", "Needs confirmation", "Manual review", "New in last hour" |
| Tab | "Records", "Confirm queue ({n})", "Action log" |
| Bộ lọc | "Status": "Open", "All", từng trạng thái · "Source" · "Stage" · "Category" (+ "Unclassified") · "Severity" · "Rule" · "Any time" · "Action" · "Actor": "Auto-triage", "System", "People" · "Clear filters" |
| Cột | "Created", "Source", "Stage", "Rule", "Error", "Category", "Severity", "Replays", "Status" · confirm: "Suggested", "Waiting" · log: "Time", "Action", "Actor", "Confidence", "Dead letter", "Current status", "Details" |
| Đếm | "{n} loaded" |
| Drawer | "Dead letter {id}" · "Triage" · "Category" · "Severity" · "Model {version} · classified {time} · {n} attempts" · "Not yet classified" · "Payload" · "Original" · "Edited" · "Copy" · "Kafka" · "{topic} · partition {p} · offset {o}" · "Batch {id}" · "History" · "Replays" · "Requested by {name} · {time}" · "No actions available in status {status}." · "Show more" |
| Nút | "Edit payload" · "Save" · "Cancel" · "Replay" · "Confirm replay" · "Discard" · "Resolve" · "Replay selected" · "Discard selected" |
| Dialog | "Replay this record?" · "The edited payload is sent through the normal pipeline. If it fails again, it returns to New." · "The original payload is sent through the normal pipeline. If it fails again, it returns to New." · "Auto-triage suggested a replay with {confidence} confidence." · "Discard this record?" · "It won't be replayed. The original payload is kept for 30 days." · "Reason" · "Mark as resolved?" · "Use this when the data was fixed outside the pipeline." · "Note" · "Confirm replay for {n} records?" · "Discard your changes?" · "Discard changes" · "Keep editing" |
| Phản hồi | "Request sent" + "View replay" · "Record replayed" · "Replay failed again: {error}" · "Replay failed to start" · "Record discarded" · "Record resolved" · "Payload saved" · "{ok} confirmed, {failed} failed" · "{ok} replayed, {failed} failed, {skipped} skipped" · "{done} of {n}" |
| Giới hạn | "You can select up to 100 records at a time." · "Payload is larger than 1 MiB." |
| Không còn | "This dead letter no longer exists." |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given 10.000 dòng DLQ (fixture), When cuộn hết danh sách, Then không có long task > 100 ms và DOM có ≤ 60 dòng.
- **AC-2** Given operator và dòng `NEW` hoặc `MANUAL` có `latitude` ngoài vùng phục vụ, When sửa payload hợp lệ → "Save" → "Replay", Then dòng thành "Replay requested" ngay, rồi "Replayed" khi job xong, và bản ghi xuất hiện trong warehouse (UC-08).
- **AC-3** Given payload sửa sai schema, When "Save", Then lỗi hiện đúng dòng của trường theo JSON Pointer và payload không được lưu.
- **AC-4** Given 5 dòng `PENDING_CONFIRM`, When chọn cả 5 → "Confirm replay", Then tối đa 4 request đồng thời, mỗi request có `Idempotency-Key` khác nhau, và toast tổng hợp đúng số.
- **AC-5** Given viewer, Then không có checkbox, không có nút thao tác trong drawer, có badge "Read-only".
- **AC-6** Given tab "Action log" với `actor=auto`, Then chỉ có hành động của auto-triage kèm confidence (UC-09).
- **AC-7** Given drawer đang sửa có thay đổi, When bấm `Esc` hoặc `j`, Then hỏi "Discard your changes?" thay vì mất bản sửa.
- **AC-8** Given dòng đổi trạng thái do người khác (SSE `UPDATED`), Then `StatusPill` đổi tại chỗ mà danh sách không nhảy.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-DLQ-01 | `viewer`: mở `/ops/dlq`; đổi bộ lọc; mở drawer; axe | AC-5; bộ lọc có trên URL |
| E2E-DLQ-02 | `operator`: `POST /sim/scenarios/bad-data` với `{"ratio": 0.01, "kinds": ["out_of_bbox"], "entityTypes": ["VEHICLE_POSITION"], "duration": "PT1M"}` (DOC-25 §7.4); lọc `stage=QUALITY`, mở một record → "Edit payload" → đổi `latitude` thành chuỗi `"abc"` → "Save" | AC-3 |
| E2E-DLQ-03 | Tiếp E2E-DLQ-02: sửa `latitude`/`longitude` về tọa độ trong Minneapolis (44.97, −93.27) → "Save" → "Replay" → xác nhận; chờ `REPLAYED` | AC-2; toast "Record replayed"; lịch sử có "Payload edited", "Replay requested", "Replayed" |
| E2E-DLQ-04 | `operator`: kịch bản `bad-data` như E2E-DLQ-02 với `kinds: ["unknown_route"]`; chờ triage-worker đưa ≥ 2 record vào `PENDING_CONFIRM`; tab "Confirm queue" → chọn hết → "Confirm replay". **Chạy từ P6** (cần triage-worker, DOC-24); ở P5, AC-4 được kiểm bằng component test với MSW | AC-4 (đếm request bằng `page.on('request')`) |

AC-1 đo ở component test DS-06 và Playwright trace trong nightly (DOC-44 §10).

## 11. Câu hỏi còn mở

Không có.
