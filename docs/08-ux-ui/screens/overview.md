# Màn hình: Overview

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-36
> Phụ thuộc: DOC-34 §4–5, DOC-35 §5.5, §5.9, DOC-37, DOC-32 (E-01, E-02, E-05, E-14, E-20, E-31, E-41, E-60), DOC-26 §8–9, DR-88
> Người dùng chính: P5-16

## 1. Persona, use case, quyền

- **Persona:** PS-2, PS-4 (đầu ca, xem nhanh "mạng lưới có ổn không"); PS-3 (xem xu hướng OTP); PS-5 (màn mở đầu khi demo).
- **Yêu cầu:** FR-11.8.
- **Use case:** điểm vào cho UC-03 (thấy gián đoạn), UC-05 (alert), UC-06 (tuyến kém), UC-07 (pipeline có lỗi). Màn này không có thao tác ghi; mọi khối đều dẫn sang màn chuyên trách.
- **Quyền:** viewer. Là trang mặc định của `/` với viewer và operator (DOC-34 §5.2). Anonymous mở `/overview` → DOC-37 §2.5.

## 2. URL và search params

`/overview` — `period` (`1d` \| `7d` \| `30d`, mặc định `1d`) chỉ áp cho các khối OTP ("On-time performance", "On-time by day", "Routes to watch"). `1d` là **ngày phục vụ gần nhất đã tính xong** (hôm qua theo `businessNow`), vì OTP được tính hằng đêm (DOC-23 §8). Mọi khối khác là dữ liệu live, không theo `period`.

## 3. Wireframe

Prototype (DR-88): [Overview](assets/overview.html).

```text
┌ sidebar ┬──────────────────────────────────────────────────────────────────────────┐
│         │ Metro Transit / Overview                                                  │
│         │ Network overview                         [Yesterday|7 days|30 days] [Open live map] │
│         │ ● Live · Sunday, Sep 28 · 4:32 PM CDT                                     │
│         │ ┌ Vehicles in service ┐┌ On-time performance ┐┌ Active alerts ┐┌ Dead letters ┐│
│         │ │ 598                 ││ 81.2%   ▲ 2.4 pts   ││ 4             ││ 214          ││
│         │ │ on 118 routes       ││ vs previous period  ││ 1 high·2 med… ││ +37 last hour││
│         │ └─────────────────────┘└─────────────────────┘└───────────────┘└──────────────┘│
│         │ ┌ Network pulse ───────────────────────────────┐┌ Needs attention  All alerts ┐│
│         │ │ Every vehicle, coloured by schedule deviation ││ ⚠ Severe delays on Lake St ││
│         │ │ [18] ━●━━●━━━◉◉━━━●━━━━●━━━━○                 ││   [21] Eastbound · 34 min High│
│         │ │ [21] ━━●━━━●━━●━━━━━━●━━━━━○                  ││ ⚠ Bus bunching on Nicollet  ││
│         │ │ [6]  ━━━●━━━━━●━━━●━━━━━━━○                   ││   [18] 2 buses · 4 min  Med ││
│         │ │ ● On time ● Late ● Very late ◉ Bunching       ││ …                           ││
│         │ └───────────────────────────────────────────────┘└─────────────────────────────┘│
│         │ ┌ On-time by day ──────────┐┌ Routes to watch  Scorecard┐┌ Data pipeline  Details┐│
│         │ │ ── this period - - prev. ││ [21] Lake St   64.8% ▬▬▬  ││ Vehicle positions 96/s 3 s││
│         │ │                          ││ [18] Nicollet  71.2% ▬▬▬▬ ││ Trip updates     44/s 4 s ││
│         │ │                          ││ …                         ││ Ticket sales  No data 18m ││
│         │ └──────────────────────────┘└───────────────────────────┘│ Batch jobs  1 failed / 24h││
│         │                                                          └───────────────────────────┘│
└─────────┴──────────────────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Đầu trang | `PageHeader` + `SegmentedControl` `period` + nút "Open live map" | Dòng phụ: `RealtimeStatusDot` + "Live · {weekday}, {date} · {time}" theo `businessNow` (DOC-34 §8) |
| KPI | `KpiCard` × 4, lưới 4 cột | §4.1 |
| Network pulse | `Card` + `LineStrip` ngang × tối đa 6 | §4.2 |
| Needs attention | `Card` + danh sách 4 alert | Mỗi dòng: `ibox` icon theo `type` (tông severity; bunching `tone-bunching`), tiêu đề, `RouteBadge sm`, meta "{summary} · {age}", `SeverityBadge`. Bấm → `/alerts?alert=<id>`. Link đầu thẻ "All alerts" |
| On-time by day | `TimeSeriesChart` (đường + vùng) | OTP toàn mạng theo ngày trong `period` (`1d` dùng 7 ngày gần nhất để có hình); series so sánh là kỳ trước cùng độ dài, nét đứt |
| Routes to watch | `Card` + 5 dòng | Tuyến OTP thấp nhất: `RouteBadge`, `longName`, "{otp}%", thanh ngang tông theo OTP (< 70% `danger`, < 80% `warning`, còn lại `success`). Bấm → `/scorecard?route=<id>`. Link đầu thẻ "Scorecard" |
| Data pipeline | `Card` + 4 dòng | "Vehicle positions", "Trip updates", "Ticket sales": msg/s (E-31, phút gần nhất) + tuổi dữ liệu (E-60 `ageSeconds`), nguồn stale → chữ `warning` "No data for {age}". "Batch jobs": "{n} failed in the last 24 h" (`danger`) hoặc "All succeeded". Link đầu thẻ "Details" → `/ops/jobs` |

### 4.1 KPI

| Thẻ | Giá trị | Dòng phụ / delta | Nguồn |
| --- | --- | --- | --- |
| "Vehicles in service" | số xe đang báo vị trí | "on {n} routes" | E-05 `count`, số `routeId` khác nhau |
| "On-time performance" | OTP toàn mạng của `period`, "%" | Delta "{d} pts" so với kỳ trước cùng độ dài, caption "vs previous period"; đi lên là tốt | E-14 hai lần (kỳ này, kỳ trước); OTP = `Σ onTimeCount × 100 / Σ observationCount` (cùng công thức E-14) |
| "Active alerts" | số alert `open` | "{h} high · {m} medium · {l} low" theo severity 2/1/0 | E-20 `?state=open&limit=100` |
| "Dead letters" | số bản ghi mở | "+{n} in the last hour" | E-41 |

Bấm thẻ → màn tương ứng (`/map`, `/scorecard`, `/alerts`, `/ops/dlq`).

### 4.2 Network pulse

- Chọn tối đa 6 tuyến có nhiều xe đang chạy nhất (E-05), cộng mọi tuyến có episode bunching hoặc gián đoạn đang mở (ưu tiên đứng đầu), cắt ở 6.
- Mỗi tuyến là một `LineStrip` ngang màu tuyến: trạm đầu và cuối của chiều 0 (E-02), xe đặt theo tỉ lệ `currentStopSequence / số trạm`, chấm xe màu lớp trễ (DOC-35 §3.4). Cặp bunching (trường `bunching` của E-05) vẽ vòng `--bunching`; tuyến có gián đoạn mở có đoạn màu `--delay-very-late` trên các trạm bị ảnh hưởng.
- Đây là sơ đồ, không phải bản đồ: không có tọa độ, không zoom. Bấm một tuyến → `/map?route=<id>`.
- Chú giải: "On time", "Late", "Very late", "Bunching".

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch / realtime |
| --- | --- | --- | --- |
| Xe | E-05 (không lọc tuyến) | `['vehicles', 'live', []]` | SSE `vehicles.batch` gom mỗi 5 s (không vẽ lại từng message); polling 5 s khi SSE hỏng |
| Hình tuyến (≤ 6 tuyến) | E-02 | `['routes', routeId, 'detail']` | `staleTime: Infinity` |
| Tuyến | E-01 | `['routes']` | như Live map |
| OTP kỳ này, kỳ trước | E-14 `?fromDate&toDate` × 2 | `['insights', 'otp', { from, to }]` | 5 phút |
| Alert mở | E-20 `?state=open&limit=100` | `['alerts', 'list', { state: 'open' }]` | SSE `alert.*` → invalidate; 60 s |
| DLQ | E-41 | `['etl', 'dlq', 'summary']` | `dlq.changed`; 60 s |
| Throughput, job lỗi | E-31 `?from=now−24h&bucket=1h` và `?from=now−15m&bucket=1m` | `['etl', 'jobs', 'summary', …]` | 60 s |
| Độ tươi | E-60 | `['system', 'freshness']` | Từ khung |
| Kênh SSE | `vehicles`, `alerts`, `dlq` | — | — |

Mỗi khối có query và error boundary riêng (DOC-37 §2.6).

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi `period` | URL (`replace`); chỉ ba khối OTP tải lại, giữ dữ liệu cũ | — |
| Bấm KPI, dòng alert, tuyến, dòng pipeline | Điều hướng theo §4 | — |
| "Open live map" | `/map` | — |
| Alert mới (SSE) | Thẻ "Active alerts" và "Needs attention" cập nhật; dòng mới highlight 2 s | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | KPI: nhãn thật + thanh skeleton; các thẻ `PanelSkeleton` đúng hình |
| Empty | "Needs attention": "Nothing needs attention" · "Open alerts will show up here."; OTP chưa có: "No on-time data yet" · "Scores are computed each night for the previous day."; không có xe: "No vehicles in service right now" |
| Error | Mỗi khối độc lập, DOC-37 §2.3 |
| Stale | `StaleBanner` toàn cục; dòng nguồn stale trong "Data pipeline" tông `warning` |
| Không có quyền | DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Network overview" · breadcrumb "Metro Transit" / "Overview" · "Live · {weekday}, {date} · {time}" |
| Kỳ | "Yesterday", "7 days", "30 days" |
| Nút | "Open live map" |
| KPI | "Vehicles in service" · "on {n} routes" · "On-time performance" · "{d} pts" · "vs previous period" · "Active alerts" · "{h} high · {m} medium · {l} low" · "Dead letters" · "+{n} in the last hour" |
| Network pulse | "Network pulse" · "Every vehicle, coloured by schedule deviation" · "On time", "Late", "Very late", "Bunching" |
| Needs attention | "Needs attention" · "All alerts" · "{summary} · {age}" · "Nothing needs attention" · "Open alerts will show up here." |
| Biểu đồ | "On-time by day" · "This period", "Previous period" |
| Routes to watch | "Routes to watch" · "Scorecard" |
| Pipeline | "Data pipeline" · "Details" · "Vehicle positions", "Trip updates", "Ticket sales", "Batch jobs" · "GTFS-rt · {rate} msg/s" · "No data for {age}" · "{n} failed in the last 24 h" · "All succeeded" |

Nhãn severity trong dòng alert dùng dạng ngắn "High" (2), "Medium" (1), "Low" (0) như prototype; nhãn đầy đủ ở tooltip (DOC-37 §3.1).

## 9. Tiêu chí nghiệm thu

- **AC-1** Given viewer đăng nhập, When mở `/`, Then tới `/overview` và cả 4 KPI có số hoặc trạng thái rỗng trong ≤ 3 s.
- **AC-2** Given kịch bản `disruption` trên tuyến 18, When alert được tạo, Then "Active alerts" tăng và dòng xuất hiện đầu "Needs attention" trong ≤ 10 s mà không reload.
- **AC-3** Given `period=7d`, Then "On-time performance" bằng OTP gộp 7 ngày của E-14 (cộng dồn bộ đếm) và delta so với 7 ngày trước đó.
- **AC-4** Given kịch bản `bunching` trên tuyến 18, Then tuyến 18 có trong "Network pulse" với vòng bunching.
- **AC-5** Given `TICKETING_SALES` stale, Then dòng "Ticket sales" ghi "No data for …" tông `warning`, các dòng khác không đổi.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-OVW-01 | `viewer`: mở `/`; đổi `period` sang "7 days"; bấm "Routes to watch" dòng đầu; axe | AC-1, AC-3; tới `/scorecard?route=…` |
| E2E-OVW-02 | `viewer`: `POST /sim/scenarios/disruption` tuyến 18 rồi `bunching` tuyến 18; chờ trên `/overview` | AC-2, AC-4 |

AC-5 kiểm ở component test với MSW.

## 11. Câu hỏi còn mở

Không có.
