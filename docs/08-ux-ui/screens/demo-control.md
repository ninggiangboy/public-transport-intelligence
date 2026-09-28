# Màn hình: Demo control

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DR-88, DOC-34, DOC-35, DOC-37 §2.3, DOC-32 E-90, DOC-25 §3, §6.5, §7–8, DOC-30 §3
> Người dùng chính: P5-13

## 1. Persona, use case, quyền

- **Persona:** PS-5 (người trình bày demo, desktop, thường chiếu màn hình).
- **Use case:** UC-16 (chạy kịch bản simulator), demo script DOC-46 bước 2–7.
- **Quan hệ với demo console (DOC-48):** màn này là tính năng sản phẩm, chạy mọi kịch bản với tham số tùy ý. Demo console là công cụ trình diễn ngoài sản phẩm: nó chỉ có các hành động cố định của DOC-46, cộng thao tác hạ tầng, và liên kết sang màn này bằng "Open Demo control". Hai nơi cùng gọi `/sim/*`, nên lần chạy bắt đầu từ console vẫn hiện thành thẻ lần chạy và trong "History" ở đây, với `requestedBy` là `cli` ("Command line").
- **Quyền:** operator **và** `demoControl = true` trong `env.js` (profile `demo`, DOC-34 §12). Thiếu một trong hai: mục "Demo" không có trong menu; mở URL trực tiếp → viewer: DOC-37 §2.5 "You don't have access to this page"; operator khi `demoControl = false` hoặc API trả 404 → trạng thái "Demo control is not enabled on this server."

## 2. URL và search params

`/ops/demo` — `run` (runId, mở drawer của một lần chạy). Form kịch bản không ghi lên URL.

## 3. Wireframe

Prototype (DR-88): [Demo — Scenarios](assets/demo-control.html).

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────────┐
│         │ Operations / Demo                                                              │
│         │ Demo scenarios                                                 [Stop all ▾]    │
│         │ Inject realistic problems into the simulated network and watch the platform react. │
│         │ ┌ Simulator ● Running │ Business time Oct 2 · 4:40 PM (+14 h 30 min) │ Vehicles 598 │
│         │ │ GTFS-rt 141 msg/s │ Ticketing 1.9 sales/s │ Speed [Paused|0.5×|1×|2×|5×|10×|…] ┐│
│         │ └──────────────────────────────────────────────────────────────────────────────┘│
│         │ ┌ ● Running · started 4:27 PM by operator ─────────────────────────── (◔ 12:40) ┐│
│         │ │ [18] Bus bunching · Route 18                                                ││
│         │ │ Northbound · 1 pair · target gap 20% of headway · 20 min                    ││
│         │ │ What happened: ● Bus bunching on route 18 alert · 38 s after start          ││
│         │ │                ● Suggested action ready (74%)                               ││
│         │ │                                                  [Watch on map] [Stop]      ││
│         │ └─────────────────────────────────────────────────────────────────────────────┘│
│         │ Scenarios · Each scenario stops by itself when its duration ends.             │
│         │ ┌ ▒▒ illus. ▒▒ ┐ ┌ ▒▒ illus. ▒▒ ┐ ┌ ▒▒ illus. ▒▒ ┐ ┌ ▒▒ illus. ▒▒ ┐               │
│         │ │ Bus bunching │ │ Service      │ │ Bad data     │ │ Ticket spike │  …           │
│         │ │ Two consec…  │ │ disruption   │ │ burst        │ │ A burst of … │               │
│         │ │ ● Running    │ │ [Start scenario]│[Start scenario]│[Start scenario]│             │
│         │ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘               │
│         │ History                                                                        │
│         │ Scenario   Status     Started   Ended   Requested by   Parameters              │
└─────────┴──────────────────────────────────────────────────────────────────────────────┘
```

Dialog bắt đầu (form sinh từ catalog, §6.1):

```text
┌ Start Service disruption? ──────────────────────┐
│ Slow one segment of a route until a disruption  │
│ episode opens. Stops by itself after 15 min.    │
│ Route        [21 ▾]                             │
│ Direction    [0 ▾]                              │
│ Extra delay  [300] sec                          │
│ Duration     [15] min                           │
├─────────────────────────────────────────────────┤
│                    [Reset] [Cancel] [Start scenario] │
└─────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | `PageHeader` + `DropdownMenu` "Stop all" | "Stop all" liệt kê tên kịch bản đang có lần chạy → `DELETE /sim/scenarios/{name}` |
| Dải "Simulator" | `Card` một hàng, các ô ngăn bằng vạch | Từ `GET /sim/status`: trạng thái ("Running" / "Paused" khi cả hai hệ số là 0), "Business time" (`businessNow` + thời gian trôi, DOC-34 §8) kèm offset, "Service dates" ở tooltip ("{realDate} → feed {feedDate}"), "Vehicles", "GTFS-rt {n} msg/s", "Ticketing {n} sales/s" |
| Tốc độ | `SegmentedControl` "Speed": "Paused" (0), "0.5×", "1×", "2×", "5×", "10×", "…" | Chọn một mức → `PUT /sim/rate` với cả `gtfsRt` và `ticketing`. "…" mở popover "Custom rate" với hai ô số riêng "GTFS-rt", "Ticketing" (0,1–20) + "Apply". Hai hệ số khác nhau thì segmented hiện "Custom". Disable kèm tooltip khi có `load-ramp` đang chạy |
| Kịch bản đang chạy | `ScenarioRunCard` × N (mới nhất trên) | "Running · started {time} by {requester}", `RouteBadge` khi có `routeId`, "{title} · {target}", tóm tắt tham số một dòng, vòng tiến độ theo `startedAt`–`plannedEndAt` + "{mm:ss} left", "What happened" (§4.1), nút "Watch on map"/"Open dead letters"/"Open ticketing"/"Open pipeline" (theo kịch bản, như toast §6) và "Stop" |
| Thư viện kịch bản | `ScenarioCard` × N theo thứ tự catalog, lưới 4 cột ≥ 1440 px, 2 cột nhỏ hơn | Hình minh họa nhỏ vẽ bằng SVG theo tên kịch bản (motif `LineStrip`: hai xe sát nhau, đoạn tuyến đỏ, payload lỗi, cột bán vé tăng vọt…; kịch bản lạ dùng hình chung), `title`, `description`, chip tham số chính với giá trị mặc định, nút "Start scenario" hoặc badge "Running" khi đã có lần chạy cho mục tiêu mặc định |
| Dialog bắt đầu | `Dialog` với form sinh từ catalog | §6.1 |
| Lịch sử | `DataTable` (≤ 100 dòng) | Cột: "Scenario", "Status" (`StatusPill domain="scenarioRun"`), "Started", "Ended", "Requested by", "Parameters" (tóm tắt 1 dòng, tooltip JSON) |
| Drawer lần chạy | `DetailDrawer` 520 px | `GET /sim/scenario-runs/{runId}`: tham số đầy đủ (`JsonViewer`), `progress` khi đang chạy, thời gian, người yêu cầu; nút "Stop", "Run again" |

### 4.1 "What happened"

Danh sách hệ quả quan sát được từ lúc lần chạy bắt đầu, để người xem thấy nền tảng phản ứng (DOC-46): alert mới (E-20 `since=startedAt`, lọc `routeId` hoặc `salePointId` của lần chạy) với tiêu đề và "{duration} after start"; với bunching, gợi ý điều phối khi có ("Suggested action ready ({percent})", E-17 `bunchingId`); với bad-data, "+{n} dead letters" (chênh lệch `open` của E-41 so với lúc bắt đầu). Tối đa 3 dòng; chưa có gì thì "Waiting for the platform to react…".

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
| Chọn "Speed" hoặc "Custom rate" → "Apply" | Hệ số `0` ("Paused") → `ConfirmDialog` "Stop sending {stream} data?" · "Live data will go stale in about 2 minutes. Set the rate back to resume."; khác → không hỏi. `PUT /sim/rate` → khối Simulator cập nhật; toast "Rate updated" | 409 `load-ramp-running` → toast "Rate is controlled by the running load ramp. Stop it first."; 400 `invalid-param` → lỗi dưới ô |
| "Start scenario" trên thẻ | Mở dialog với form (§6.1); "Start scenario" trong dialog kiểm tra theo catalog → `POST /sim/scenarios/{name}` → 201 → dialog đóng, thẻ lần chạy mới xuất hiện trên đầu; toast "{title} started" với nút "Watch on map" (bunching, disruption: `/map?route=<routeId>`), "Open dead letters" (bad-data, late-delivery), "Open ticketing" (ticket-spike, refund-burst), "Open pipeline" (load-ramp, duplicates) | 400 `invalid-param` → lỗi theo trường (`errors[].field`); 409 `scenario-conflict` → "{title} is already running for this target."; 409 `no-eligible-vehicles` → "No suitable buses on this route right now. Try another route or direction."; 404 `unknown-scenario` → "This scenario isn't available." và refetch catalog; 502 `simulator-unavailable` → DOC-37 §2.3 |
| "Stop" trên thẻ lần chạy | Không hỏi (dừng là an toàn) → `DELETE /sim/scenario-runs/{runId}` → thẻ mờ, biến mất khi status khác `RUNNING`; toast "{title} stopped" | 404 `scenario-run-not-found` → gỡ thẻ |
| "Stop all" → chọn kịch bản | `ConfirmDialog` "Stop all {title} runs?" · "{n} runs will stop now." → `DELETE /sim/scenarios/{name}` | Như trên |
| Lần chạy hết giờ | Thẻ lần chạy biến mất; lịch sử có `COMPLETED` | — |
| Bấm dòng lịch sử | `run=<runId>` (`push`), drawer | 404 → "This run no longer exists." |
| "Run again" trong drawer | Mở dialog bắt đầu của kịch bản tương ứng, điền sẵn `params` của lần chạy | — |

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
- Dialog rộng 480 px; mô tả là `description` của catalog cộng "Stops by itself after {duration}."; nút "Reset", "Cancel", "Start scenario". Lỗi 4xx hiện trong dialog, dialog không đóng.

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Dải Simulator skeleton; thẻ kịch bản skeleton |
| Empty (đang chạy) | Không có thẻ lần chạy; dòng chữ "No scenarios running" |
| Empty (lịch sử) | "No scenario runs yet" |
| Error | `GET /sim/status` 502 → dải Simulator `ErrorState` "Simulator isn't responding" + "Retry"; nút "Start scenario" disable. Mỗi khối độc lập (DOC-37 §2.6) |
| Không bật | 404 từ `/sim/*` hoặc `demoControl = false` → trang `EmptyState` "Demo control is not enabled on this server." · "Start the stack with the demo profile to use this page." |
| Không có quyền | DOC-37 §2.5 (cần operator) |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Demo scenarios" · breadcrumb "Operations" / "Demo" · "Inject realistic problems into the simulated network and watch the platform react." |
| Dải Simulator | "Simulator" · "Running" · "Paused" · "Business time" · "{offset}" · "Service dates" · "{realDate} → feed {feedDate}" · "Vehicles" · "GTFS-rt" · "msg/s" · "Ticketing" · "sales/s" · "Speed" · "0.5×", "1×", "2×", "5×", "10×" · "Custom" · "Custom rate" · "Apply" |
| Tooltip | "Rate is controlled by the running load ramp." |
| Đang chạy | "Running · started {time} by {requester}" · "{mm:ss} left" · "What happened" · "{duration} after start" · "Suggested action ready ({percent})" · "+{n} dead letters" · "Waiting for the platform to react…" · "Watch on map" · "Stop" · "Stop all" · "No scenarios running" |
| Thư viện | "Scenarios" · "Each scenario stops by itself when its duration ends." · "Start scenario" · "Running" |
| Dialog bắt đầu | "Start {title}?" · "Stops by itself after {duration}." · "Reset" · "Cancel" · "Start scenario" · "Any" · "Total duration {duration}" · đơn vị "sec", "min", "h" |
| Lịch sử | "History" · cột "Scenario", "Status", "Started", "Ended", "Requested by", "Parameters" · "No scenario runs yet" |
| Drawer | "Scenario run {id}" · "Parameters" · "Progress" · "Requested by {requester}" · "Run again" |
| Dialog | "Stop sending {stream} data?" · "Live data will go stale in about 2 minutes. Set the rate back to resume." · "Stop all {title} runs?" · "{n} runs will stop now." |
| Phản hồi | "Rate updated" · "{title} started" · "{title} stopped" · "Watch on map" · "Open dead letters" · "Open ticketing" · "Open pipeline" |
| Lỗi | "Rate is controlled by the running load ramp. Stop it first." · "{title} is already running for this target." · "No suitable buses on this route right now. Try another route or direction." · "This scenario isn't available." · "This run no longer exists." |
| Không bật | "Demo control is not enabled on this server." · "Start the stack with the demo profile to use this page." |

`requestedBy` hiển thị: `user:<name>` → tên; `experiment:<EXP>/<run>` → "Experiment {EXP}"; `cli` → "Command line".

## 9. Tiêu chí nghiệm thu

- **AC-1** Given operator trên stack `demo`, Then menu Ops có "Demo" và trang hiện giờ nghiệp vụ, số xe, msg/s cập nhật mỗi 2 s.
- **AC-2** Given thẻ "Bus bunching" → "Start scenario" với tuyến 18, Then thẻ lần chạy xuất hiện với thời gian còn lại, "What happened" có alert bunching khi episode mở, và trong ≤ 10 phút Live map có halo bunching trên tuyến 18.
- **AC-3** Given đang chạy `bunching` cho tuyến 18, When bắt đầu lần hai cùng tuyến và chiều, Then lỗi "Bus bunching is already running for this target."
- **AC-4** Given `load-ramp` đang chạy, Then "Speed" disable với tooltip; `PUT /sim/rate` (nếu gửi) trả 409 và có toast đúng.
- **AC-5** Given nhập `pairs = 5`, Then lỗi phía client "Must be at most 3." và không gửi request.
- **AC-6** Given stack không có profile `demo`, When operator mở `/ops/demo`, Then "Demo control is not enabled on this server."
- **AC-7** Given viewer, Then không có mục "Demo" và URL trực tiếp cho "You don't have access to this page".

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-DEMO-01 | `operator`: mở `/ops/demo`; chạy "Service disruption" tuyến 18 `PT5M`; "Watch on map"; quay lại "Stop"; axe | AC-1, lần chạy `STOPPED` trong lịch sử |
| E2E-DEMO-02 | `operator`: chạy "Bus bunching" tuyến 18; chạy lần hai cùng tham số; nhập `pairs = 5` | AC-3, AC-5 |
| E2E-DEMO-03 | `operator`: "Load ramp" với `steps = 1, 2`, `stepDuration = 1 min`; thử đổi "Speed"; "Stop all" → "Load ramp" | AC-4; hệ số trở về giá trị trước |
| E2E-DEMO-04 | `viewer`: mở `/ops/demo` | AC-7 |

AC-2 nằm trong E2E-MAP-02; AC-6 kiểm ở component test (MSW trả 404).

## 11. Câu hỏi còn mở

Không có.
