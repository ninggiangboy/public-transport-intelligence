# Màn hình: Demo control

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35, DOC-37 §2.3, DOC-32 E-90, DOC-25 §3, §6.5, §7–8, DOC-30 §3
> Người dùng chính: P5-13

## 1. Persona, use case, quyền

- **Persona:** PS-5 (người trình bày demo, desktop, thường chiếu màn hình).
- **Use case:** UC-16 (chạy kịch bản simulator), demo script DOC-46 bước 2–7.
- **Quyền:** operator **và** `demoControl = true` trong `env.js` (profile `demo`, DOC-34 §12). Thiếu một trong hai: mục "Demo" không có trong menu; mở URL trực tiếp → viewer: DOC-37 §2.5 "You don't have access to this page"; operator khi `demoControl = false` hoặc API trả 404 → trạng thái "Demo control is not enabled on this server."

## 2. URL và search params

`/ops/demo` — `run` (runId, mở drawer của một lần chạy). Form kịch bản không ghi lên URL.

## 3. Wireframe

```text
┌────────────┬──────────────────────────────────────────────────────────────────┐
│ …          │ Demo control                                                     │
│ Demo     ◀ │ ┌ Simulator ───────────────────────────────────────────────────┐ │
│            │ │ Business time  Oct 2, 4:40 PM CDT (offset +14h 30m)          │ │
│            │ │ Service dates  Oct 1 → feed Sep 30 · Oct 2 → feed Oct 1       │ │
│            │ │ Vehicles 598 · trips 466 · 141 msg/s · ticketing 1.9/s        │ │
│            │ │ Rate  GTFS-rt [1.0 ▾]  Ticketing [1.0 ▾]      [Apply]         │ │
│            │ └──────────────────────────────────────────────────────────────┘ │
│            │ Running scenarios                              [Stop all ▾]      │
│            │ ● bunching · route 18 · 12:40 left [███████░░░]      [Stop]      │
│            │ Scenarios                                                        │
│            │ ┌ Bus bunching ─────────────┐ ┌ Service disruption ─────────┐    │
│            │ │ Route [18 ▾] Dir [0 ▾]    │ │ Route [21 ▾] …              │    │
│            │ │ Pairs [1] Gap ratio [0.2] │ │                             │    │
│            │ │ Duration [20 min]         │ │                             │    │
│            │ │               [Start]     │ │               [Start]       │    │
│            │ └───────────────────────────┘ └─────────────────────────────┘    │
│            │ … bad data, duplicates, ticket spike, refund burst, load ramp   │
│            │ History                                                          │
│            │ Scenario   Status     Started   Ended   Requested by   Params    │
└────────────┴──────────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Khối "Simulator" | `KeyValueList` + hai `Select` hệ số | Từ `GET /sim/status`. Giờ nghiệp vụ chạy theo `businessNow` + thời gian trôi (như khung, DOC-34 §8) |
| Hệ số tốc độ | `Select` giá trị `0` ("Paused"), `0.5`, `1`, `2`, `5`, `10`, `20` và "Custom…" (ô số 0,1–20) | Nút "Apply" chỉ bật khi giá trị đổi; disable kèm tooltip khi có `load-ramp` đang chạy |
| Kịch bản đang chạy | danh sách `ScenarioRunRow` | Tên, tham số chính (route/sale point), `Progress` theo `startedAt`–`plannedEndAt`, "{mm:ss} left", nút "Stop" |
| "Stop all" | `DropdownMenu` | Mỗi mục là một tên kịch bản đang có lần chạy → `DELETE /sim/scenarios/{name}` |
| Thẻ kịch bản | `ScenarioCard` × 7, form **sinh từ catalog** | §6.1 |
| Lịch sử | `DataTable` (≤ 100 dòng) | Cột: "Scenario", "Status" (`StatusPill domain="scenarioRun"`), "Started", "Ended", "Requested by", "Parameters" (tóm tắt 1 dòng, tooltip JSON) |
| Drawer lần chạy | `DetailDrawer` 560 px | `GET /sim/scenario-runs/{runId}`: tham số đầy đủ (`JsonViewer`), `progress` khi đang chạy, thời gian, người yêu cầu; nút "Stop" |

## 5. Dữ liệu

Mọi request đi qua proxy `/api/v1/sim/**` (E-90). Schema zod viết tay trong `src/api/sim.ts` (không có trong `openapi.json` công khai).

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Trạng thái | `GET /sim/status` | `['sim', 'status']` | **2 s** khi trang hiển thị |
| Catalog | `GET /sim/scenarios` | `['sim', 'scenarios']` | `staleTime: Infinity` trong phiên |
| Lịch sử | `GET /sim/scenario-runs?limit=50` | `['sim', 'runs']` | 5 s; invalidate sau start/stop |
| Một lần chạy | `GET /sim/scenario-runs/{runId}` | `['sim', 'run', runId]` | 2 s khi `RUNNING` và drawer mở |
| Gợi ý tuyến | E-01 | `['routes']` | Như Live map |
| Đổi tốc độ | `PUT /sim/rate` `{"gtfsRt": 2.0}` (một hoặc cả hai khóa) | mutation | — |
| Bắt đầu | `POST /sim/scenarios/{name}` | mutation | — |
| Dừng | `DELETE /sim/scenario-runs/{runId}`, `DELETE /sim/scenarios/{name}` | mutation | — |

Catalog (định nghĩa ở DOC-25 §8):

```json
{
  "items": [
    {
      "name": "bunching",
      "title": "Bus bunching",
      "description": "Make two consecutive buses on a route run close together.",
      "concurrency": "PER_TARGET",
      "params": [
        { "name": "routeId", "label": "Route", "type": "ROUTE", "required": true },
        { "name": "directionId", "label": "Direction", "type": "ENUM", "options": ["0", "1"], "default": "0" },
        { "name": "pairs", "label": "Pairs", "type": "INT", "default": 1, "min": 1, "max": 3 },
        { "name": "targetGapRatio", "label": "Target gap (share of headway)", "type": "DOUBLE", "default": 0.2, "min": 0.05, "max": 0.45 },
        { "name": "duration", "label": "Duration", "type": "DURATION", "default": "PT20M", "min": "PT1M", "max": "PT2H" }
      ]
    }
  ]
}
```

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi hệ số → "Apply" | Hệ số `0` → `ConfirmDialog` "Stop sending {stream} data?" · "Live data will go stale in about 2 minutes. Set the rate back to resume."; khác → không hỏi. `PUT /sim/rate` → khối Simulator cập nhật; toast "Rate updated" | 409 `load-ramp-running` → toast "Rate is controlled by the running load ramp. Stop it first."; 400 `invalid-param` → lỗi dưới ô |
| "Start" trên thẻ | Kiểm tra theo catalog (§6.1) → `POST /sim/scenarios/{name}` → 201 → dòng mới trong "Running scenarios"; toast "{title} started" với nút "Open map" (bunching, disruption: `/map?route=<routeId>`), "Open dead letters" (bad-data, late-delivery), "Open ticketing" (ticket-spike, refund-burst), "Open jobs" (load-ramp, duplicates) | 400 `invalid-param` → lỗi theo trường (`errors[].field`); 409 `scenario-conflict` → "{title} is already running for this target."; 409 `no-eligible-vehicles` → "No suitable buses on this route right now. Try another route or direction."; 404 `unknown-scenario` → "This scenario isn't available." và refetch catalog; 502 `simulator-unavailable` → DOC-37 §2.3 |
| "Stop" trên dòng | Không hỏi (dừng là an toàn) → `DELETE /sim/scenario-runs/{runId}` → dòng mờ, rời danh sách khi status khác `RUNNING`; toast "{title} stopped" | 404 `scenario-run-not-found` → gỡ dòng |
| "Stop all" → chọn kịch bản | `ConfirmDialog` "Stop all {title} runs?" · "{n} runs will stop now." → `DELETE /sim/scenarios/{name}` | Như trên |
| Lần chạy hết giờ | Dòng rời "Running scenarios"; lịch sử có `COMPLETED` | — |
| Bấm dòng lịch sử | `run=<runId>` (`push`), drawer | 404 → "This run no longer exists." |
| "Run again" trong drawer | Điền thẻ kịch bản tương ứng bằng `params` của lần chạy, cuộn tới thẻ | — |

### 6.1 Form sinh từ catalog

Mỗi `params[]` sinh một trường theo `type`:

| `type` | Trường | Giá trị gửi |
| --- | --- | --- |
| `STRING` | `Input`; có `suggestions` thì `Combobox` gợi ý (vẫn gõ tự do được) | chuỗi |
| `INT`, `DOUBLE` | `Input type="number"` với `min`/`max`, bước 1 hoặc 0,01 | số |
| `BOOLEAN` | `Switch` | boolean |
| `DURATION` | Ô số + đơn vị ("sec", "min", "h") | ISO-8601 (`PT20M`) |
| `ENUM` | `Select` với `options`; `nullable` thêm lựa chọn "Any" | chuỗi hoặc số theo `options`; "Any" → `null` |
| `ENUM_LIST` | Nhóm `Checkbox` theo `options` | mảng |
| `DOUBLE_LIST` | Ô văn bản "1, 2, 5, 10" | mảng số |
| `ROUTE` | `RouteSelect` một tuyến (E-01) | `routeId` |

- zod schema dựng lúc chạy từ catalog: `required`, `min`, `max`, `nullable`, `options`. Trường không `required` và để trống thì **không** gửi (simulator dùng mặc định).
- Giá trị mặc định điền sẵn từ `default`. Nhãn trường là `label` của catalog (tiếng Anh, do simulator cung cấp); mô tả thẻ là `description`.
- Nút "Reset" trả form về mặc định.
- `load-ramp` hiện thêm dòng "Total duration {duration}" tính từ `steps`, `stepDuration`, `rampDown` (DOC-25 §7.8).
- Thứ tự thẻ theo thứ tự catalog; lưới 2 cột ≥ 1280 px, 1 cột nhỏ hơn.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Khối Simulator skeleton; thẻ kịch bản skeleton 4 dòng |
| Empty (đang chạy) | "No scenarios running" |
| Empty (lịch sử) | "No scenario runs yet" |
| Error | `GET /sim/status` 502 → khối Simulator `ErrorState` "Simulator isn't responding" + "Retry"; thẻ kịch bản disable. Mỗi khối độc lập (DOC-37 §2.6) |
| Không bật | 404 từ `/sim/*` hoặc `demoControl = false` → trang `EmptyState` "Demo control is not enabled on this server." · "Start the stack with the demo profile to use this page." |
| Không có quyền | DOC-37 §2.5 (cần operator) |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Demo control" |
| Khối Simulator | "Simulator" · "Business time" · "(offset {duration})" · "Service dates" · "{realDate} → feed {feedDate}" · "Vehicles {n} · trips {n} · {n} msg/s · ticketing {n}/s" · "Rate" · "GTFS-rt" · "Ticketing" · "Paused" · "Custom…" · "Apply" |
| Tooltip | "Rate is controlled by the running load ramp." |
| Đang chạy | "Running scenarios" · "{mm:ss} left" · "Stop" · "Stop all" · "No scenarios running" |
| Thẻ | "Start" · "Reset" · "Any" · "Total duration {duration}" · đơn vị "sec", "min", "h" |
| Lịch sử | "History" · cột "Scenario", "Status", "Started", "Ended", "Requested by", "Parameters" · "No scenario runs yet" |
| Drawer | "Scenario run {id}" · "Parameters" · "Progress" · "Requested by {requester}" · "Run again" |
| Dialog | "Stop sending {stream} data?" · "Live data will go stale in about 2 minutes. Set the rate back to resume." · "Stop all {title} runs?" · "{n} runs will stop now." |
| Phản hồi | "Rate updated" · "{title} started" · "{title} stopped" · "Open map" · "Open dead letters" · "Open ticketing" · "Open jobs" |
| Lỗi | "Rate is controlled by the running load ramp. Stop it first." · "{title} is already running for this target." · "No suitable buses on this route right now. Try another route or direction." · "This scenario isn't available." · "This run no longer exists." |
| Không bật | "Demo control is not enabled on this server." · "Start the stack with the demo profile to use this page." |

`requestedBy` hiển thị: `user:<name>` → tên; `experiment:<EXP>/<run>` → "Experiment {EXP}"; `cli` → "Command line".

## 9. Tiêu chí nghiệm thu

- **AC-1** Given operator trên stack `demo`, Then menu Ops có "Demo" và trang hiện giờ nghiệp vụ, số xe, msg/s cập nhật mỗi 2 s.
- **AC-2** Given thẻ "Bus bunching" với tuyến 18 → "Start", Then dòng đang chạy xuất hiện với thời gian còn lại, và trong ≤ 10 phút Live map có halo bunching trên tuyến 18.
- **AC-3** Given đang chạy `bunching` cho tuyến 18, When bắt đầu lần hai cùng tuyến và chiều, Then lỗi "Bus bunching is already running for this target."
- **AC-4** Given `load-ramp` đang chạy, Then ô hệ số disable với tooltip; `PUT /sim/rate` (nếu gửi) trả 409 và có toast đúng.
- **AC-5** Given nhập `pairs = 5`, Then lỗi phía client "Must be at most 3." và không gửi request.
- **AC-6** Given stack không có profile `demo`, When operator mở `/ops/demo`, Then "Demo control is not enabled on this server."
- **AC-7** Given viewer, Then không có mục "Demo" và URL trực tiếp cho "You don't have access to this page".

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-DEMO-01 | `operator`: mở `/ops/demo`; chạy "Service disruption" tuyến 18 `PT5M`; "Open map"; quay lại "Stop"; axe | AC-1, lần chạy `STOPPED` trong lịch sử |
| E2E-DEMO-02 | `operator`: chạy "Bus bunching" tuyến 18; chạy lần hai cùng tham số; nhập `pairs = 5` | AC-3, AC-5 |
| E2E-DEMO-03 | `operator`: "Load ramp" với `steps = 1, 2`, `stepDuration = 1 min`; thử đổi hệ số; "Stop all" → "Load ramp" | AC-4; hệ số trở về giá trị trước |
| E2E-DEMO-04 | `viewer`: mở `/ops/demo` | AC-7 |

AC-2 nằm trong E2E-MAP-02; AC-6 kiểm ở component test (MSW trả 404).

## 11. Câu hỏi còn mở

Không có.
