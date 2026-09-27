# Màn hình: Route scorecard và chi tiết tuyến

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35 §5.7, §7, DOC-37, DOC-32 (E-01, E-03, E-04, E-12, E-13, E-14), DOC-23 §6–8
> Người dùng chính: P5-08

## 1. Persona, use case, quyền

- **Persona:** PS-3 (laptop, hằng tuần); PS-5 khi demo bước 4 (F-UI-03).
- **Use case:** UC-06.
- **Quyền:** viewer. Anonymous → trạng thái "Sign in to view this page".

## 2. URL và search params

- `/scorecard` — `from`, `to` (ngày, mặc định 7 ngày kết thúc hôm qua theo giờ agency), `routeType` (list), `sort` (`otp` \| `route`).
- `/scorecard/$routeId` — `tab` (`delays` \| `profile` \| `disruptions`), `from`, `to` (**ngày**, cùng mặc định), `dir`, `bucket` (`hour-of-week` \| `hour` \| `day`), `dow`, `hour` (tab `profile`; mặc định thứ và giờ hiện tại theo `businessNow`), `disruption` (id, mở drawer ở tab `disruptions`).
- Khoảng ngày chuyển sang thời điểm cho E-03/E-12: `from` = 00:00 giờ agency của ngày đầu, `to` = 00:00 của ngày sau ngày cuối.
- Khoảng > 31 ngày (sửa tay trên URL) → cắt còn 31 ngày tính ngược từ `to`, dải `info` "Max range is 31 days. Showing {from} – {to}." (UC-06 2a).

## 3. Wireframe

Bảng xếp hạng:

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ Route scorecard                         [Sep 22 – Sep 28 ▾] [All modes ▾]     │
│ On-time window: 5 min early to 5 min late · As of Sep 29, 3:00 AM CDT          │
├────┬───────────────────────────────┬────────┬──────────┬────────┬──────┬──────┤
│ #  │ Route                          │ OTP    │ Trend    │ Early  │ Late │ Obs. │
├────┼───────────────────────────────┼────────┼──────────┼────────┼──────┼──────┤
│ 1  │ [18] Nicollet Av - …           │ 78.4%  │ ╲_╱‾     │ 2.8%   │18.8% │ 76.9K│
│ 2  │ [5]  Chicago Av - Fremont …    │ 80.1%  │ ‾╲_      │ 1.9%   │18.0% │ 81.2K│
└────┴───────────────────────────────┴────────┴──────────┴────────┴──────┴──────┘
```

Chi tiết tuyến, tab "Delays":

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ ← Scorecard   [18] Nicollet Av - Nicollet Mall - 1st Av       [Sep 22 – 28 ▾] │
│ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐                       │
│ │ OTP 78.4% │ │ Early 2.8%│ │ Late 18.8%│ │ Obs 76.9K │                       │
│ └───────────┘ └───────────┘ └───────────┘ └───────────┘                       │
│ [Delays] [Stop profile] [Disruptions]                                         │
│ Direction [Both ▾]  View [Hour × weekday | Hourly | Daily]                    │
│ Average delay by hour and weekday                         [View as table]     │
│      12AM 1AM … 11PM                                                          │
│ Mon  ░░▒▒▓▓…                                                                  │
│ …                                                                              │
│ Daily on-time performance                                                     │
│ ‾‾╲__╱‾‾                                                                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 4. Vùng và component

| Vùng | Component | Ghi chú |
| --- | --- | --- |
| Bộ lọc | `TimeRangePicker granularity="date"` (presets `7d`, `14d`, `31d`; tối đa 31 ngày; ngày cuối ≤ hôm qua), `MultiSelectFilter` "Mode" theo `routeType` | |
| Ghi chú ngưỡng | chữ phụ | Từ `earlyToleranceSeconds`/`lateToleranceSeconds`; có `mixedTolerances` thì "The on-time window changed during this period." + tooltip `help.otpMixed` |
| Bảng xếp hạng | `DataTable` (không virtual) | Cột: "#", "Route" (`RouteBadge` + `longName`), "OTP", "Trend" (`Sparkline` từ `daily`), "Early", "Late", "Observations", "Trips". Dòng là link tới `/scorecard/<routeId>` |
| Thẻ tóm tắt | `StatCard` × 4 | Từ E-14 với `routeId` |
| Tab | shadcn `Tabs` | Đổi tab là `push` |
| Heatmap | `HeatmapChart scale="delay"` 7 × 24 | Ô: `avgDelaySeconds`; tooltip: trung bình, trung vị, p90, số quan sát, OTP |
| Biểu đồ theo giờ | `TimeSeriesChart` (avg, median, p90) | `bucket=hour` |
| Biểu đồ theo ngày | `TimeSeriesChart type="bar"` (avg) + đường OTP | `bucket=day` |
| OTP theo ngày | `TimeSeriesChart` | Từ `daily` của E-14 |
| Hồ sơ trạm | `TimeSeriesChart type="bar"` theo thứ tự trạm + bảng | Mỗi trạm: trung bình, p90, `ConfidenceChip level sampleCount`; trạm `NONE` để trống |
| Danh sách gián đoạn | `DataTable` | Cột: "Started", "Ended", "Direction", "Peak delay", "Peak z", "Cause", "Outcome"; dòng mở drawer |
| Drawer gián đoạn | `DetailDrawer` | E-13 (viewer view) |

## 5. Dữ liệu

| Dữ liệu | Endpoint | Query key | Refetch |
| --- | --- | --- | --- |
| Tuyến | E-01 | `['routes']` | như Live map |
| Xếp hạng OTP | E-14 `?fromDate&toDate&routeType` | `['insights', 'otp', { from, to, routeTypes }]` | 5 phút (dữ liệu tổng hợp theo ngày) |
| OTP một tuyến | E-14 `?routeId=` | `['insights', 'otp', { from, to, routeIds: [id] }]` | 5 phút |
| Độ trễ | E-03 `?from&to&bucket&directionId` | `['routes', id, 'delays', params]` | 5 phút |
| Hồ sơ trạm | E-02 (tên chiều) + E-04 `?directionId&dayOfWeek&hourOfDay` | `['routes', id, 'delay-profile', params]` | 5 phút |
| Gián đoạn | E-12 `?routeId&from&to` (keyset) | `['insights', 'disruption', { routeIds: [id], from, to }]` | 60 s; SSE `disruption.*` → invalidate |
| Chi tiết gián đoạn | E-13 | `['insights', 'disruption', 'detail', id]` | khi mở drawer |

Không có kênh SSE riêng ngoài kênh `alerts` của khung.

## 6. Tương tác

| Hành động | Kết quả | Lỗi |
| --- | --- | --- |
| Đổi khoảng ngày, mode, sort | URL (`replace`); bảng mờ trong lúc tải, giữ dữ liệu cũ | 400 (khoảng sai) không xảy ra vì UI kiểm trước; nếu có → inline error |
| Bấm dòng | Tới `/scorecard/<routeId>` giữ `from`, `to` | — |
| Đổi tab / chiều / kiểu xem | URL; tải dữ liệu tab mới | — |
| Tab "Stop profile": chọn thứ và giờ | `dow`, `hour` trên URL | Chiều không tồn tại (404) → chọn chiều đầu tiên có trong E-02 |
| "View as table" | Bảng số liệu tương đương biểu đồ; nút đổi thành "View as chart" | — |
| Bấm dòng gián đoạn | `disruption=<id>` (`push`), drawer: thời gian, trung bình/đỉnh, baseline, z, trạm bị ảnh hưởng (tên từ E-02), nguyên nhân + `ConfidenceChip`, `dataIssueProbability`, `closeReason`, audience | E-13 404 → "This disruption is no longer available." |
| "Show on map" trong drawer (episode đang mở) | `/map?route=<id>&disruption=<id>` | — |

## 7. Trạng thái

| Trạng thái | Hiển thị |
| --- | --- |
| Loading | Bảng 8 dòng skeleton; biểu đồ `PanelSkeleton variant="chart"` |
| Empty (xếp hạng) | "No on-time data for this period" · "Scores are computed each night for the previous day." |
| Empty (biểu đồ) | DOC-37 §2.2 "No data for this period" |
| Empty (gián đoạn) | "No disruptions on this route in this period" |
| Error | Mỗi panel độc lập (DOC-37 §2.6) |
| Stale | Không hiện `StaleBanner` (dữ liệu tổng hợp); "As of …" từ `X-Data-As-Of` của E-14 |
| Không có quyền | DOC-37 §2.5 |

## 8. Microcopy

| Vị trí | Chuỗi |
| --- | --- |
| Tiêu đề | "Route scorecard" · chi tiết: tên tuyến |
| Ghi chú ngưỡng | "On-time window: {early} early to {late} late" · "The on-time window changed during this period." |
| Bộ lọc | "Date range" · "Mode" · "All modes" · "Sort by" · "Worst on-time first" · "Route number" |
| Cột bảng | "#", "Route", "OTP", "Trend", "Early", "Late", "Observations", "Trips" |
| Thẻ | "On-time performance", "Early", "Late", "Observations" |
| Tab | "Delays", "Stop profile", "Disruptions" |
| Tab Delays | "Direction" · "Both directions" · "View" · "Hour × weekday" · "Hourly" · "Daily" · "Average delay by hour and weekday" · "Delay over time" · "Daily on-time performance" · chú giải "Average", "Median", "90th percentile" |
| Tab Stop profile | "Typical delay at each stop" · "Weekday" · "Hour" · "Based on the last 4 weeks ({windowStart} – {windowEnd})" · cột "Stop", "Average", "90th percentile", "Confidence" |
| Tab Disruptions | cột "Started", "Ended", "Direction", "Peak delay", "Peak z", "Cause", "Outcome" · "Ongoing" |
| Drawer | "Disruption on route {route} {direction}" · "Average delay", "Peak delay", "Normal for this time", "z-score", "Affected stops", "Likely cause", "Cause: not yet classified", "Data issue probability", "Outcome", "Visible to" · "Show on map" |
| Khoảng quá dài | "Max range is 31 days. Showing {from} – {to}." |

## 9. Tiêu chí nghiệm thu

- **AC-1** Given viewer, When mở `/scorecard`, Then bảng sắp OTP tăng dần (tệ nhất lên đầu), mỗi dòng có sparkline theo ngày.
- **AC-2** Given đổi khoảng thành 14 ngày, Then URL có `from`/`to` mới và copy URL sang tab khác cho cùng bảng.
- **AC-3** Given chi tiết tuyến tab "Delays", Then heatmap 7 × 24 theo giờ địa phương; "View as table" cho bảng cùng số liệu.
- **AC-4** Given tab "Stop profile", Then mỗi trạm có mức tin cậy; trạm không có mẫu hiển thị "Schedule only" và không có cột số.
- **AC-5** Given URL `from`/`to` cách 60 ngày, Then UI cắt còn 31 ngày và hiện "Max range is 31 days…".
- **AC-6** Given ngưỡng OTP đổi giữa khoảng (`mixedTolerances`), Then ghi chú ngưỡng đổi thành câu cảnh báo.

## 10. Ca kiểm thử E2E

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-SCORE-01 | Đăng nhập `viewer`; chạy `OtpScorecardJob` cho hôm qua qua E-33 (nếu có dữ liệu); mở `/scorecard` → đổi khoảng → bấm tuyến đầu → tab "Delays" → "View as table" → tab "Stop profile"; axe | AC-1…AC-4 (chấp nhận trạng thái rỗng của bảng xếp hạng khi môi trường chưa có dữ liệu hôm qua) |
| E2E-SCORE-02 | Mở `/scorecard?from=2026-07-01&to=2026-08-30` | AC-5 |

## 11. Câu hỏi còn mở

Không có.
