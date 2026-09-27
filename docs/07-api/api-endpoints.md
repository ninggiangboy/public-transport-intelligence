# Danh mục endpoint API

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-32
>
> Phụ thuộc: [DOC-31](api-guidelines.md) (quy ước chung), [DOC-33](sse-events.md), [DOC-26](../06-design/realtime-delivery.md), [DOC-27](../06-design/security.md), [DOC-14](../05-data/warehouse-model.md), [DOC-15](../05-data/ops-and-insight-model.md), [DOC-17](../05-data/db-roles-and-grants.md), [DOC-19](../06-design/batch-and-chunk-processing.md), [DOC-22](../06-design/dlq-and-replay.md), [DOC-23](../06-design/analytics.md), [DOC-30](../06-design/error-handling.md) §3, DR-39, DR-43, ADR-0013
>
> Người dùng chính: người viết app `api` (P4-09…P4-16), frontend (P5), contract test (FR-10.1)

## 1. Cách đọc

- Mỗi endpoint có một mã `E-xx`. Mã được dùng trong DOC-36 (màn hình), test và OpenAPI (`operationId` là tên camelCase ghi ở từng mục).
- Mọi path có tiền tố `/api/v1`, được lược bớt trong tài liệu (trừ `/internal/**`).
- Chỉ ghi phần **khác** hoặc **cụ thể hơn** DOC-31. Các điều sau áp dụng cho mọi endpoint nên không nhắc lại: `X-Trace-Id`, Problem Details, `401`/`403`/`429`/`503`, tham số lạ bị từ chối, phân trang keyset chuẩn (`limit` 1–500, mặc định 50, `cursor`).
- **Quyền:** `anonymous` < `viewer` < `operator` (role hierarchy, DOC-27 §3). "viewer" nghĩa là viewer **hoặc** operator.
- **Trục** là trục thời gian của tham số `from`/`to` (DOC-31 §4.2): `event` (giờ nghiệp vụ), `audit` (giờ thật), `record` (giờ record Kafka).
- **SQL** là câu truy vấn chính, đặt trong `api/src/main/resources/sql/<nhóm>/<tên>.sql`. `:fv` là `feedVersionId` của feed ACTIVE (DOC-31 §10.2), `:tz` là `agency_timezone` của nó.
- **p95** là mục tiêu ở tải nền trên 7 ngày dữ liệu (NFR-10), đo bằng `http_server_requests_seconds{uri}` và kiểm ở P4-10 bằng k6 (DOC-44).

## 2. Tổng quan

| Mã | Method và path | Quyền | UC / FR | Cache (DOC-31 §10.3) | p95 |
| --- | --- | --- | --- | --- | --- |
| E-01 | `GET /routes` | anonymous | UC-01, UC-06 | `routes` | 30 ms |
| E-02 | `GET /routes/{routeId}` | anonymous | UC-01, UC-06 | `route-detail` | 50 ms |
| E-03 | `GET /routes/{routeId}/delays` | viewer | UC-06, FR-11.3 | `route-delays` | 200 ms (7 ngày), 800 ms (31 ngày) |
| E-04 | `GET /routes/{routeId}/delay-profile` | anonymous | UC-02, UC-06 | `delay-profile` | 50 ms |
| E-05 | `GET /vehicles/live` | anonymous (overlay bunching: viewer) | UC-01, UC-03, FR-11.1 | `vehicles-live` | 100 ms |
| E-06 | `GET /stops` | anonymous | UC-02 | `stops-search` | 80 ms |
| E-07 | `GET /stops/{stopId}` | anonymous | UC-02, FR-07.3 | `stop-detail` | 50 ms |
| E-08 | `GET /stops/{stopId}/arrivals` | anonymous | UC-02, FR-06.2 | `arrivals` | 150 ms |
| E-10 | `GET /insights/bunching` | viewer | UC-03, FR-05 | — | 100 ms |
| E-11 | `GET /insights/bunching/{id}` | viewer | UC-03, UC-04 | — | 50 ms |
| E-12 | `GET /insights/disruption` | anonymous (rút gọn) / viewer | UC-02, UC-06, FR-07 | `public-disruptions` (anonymous) | 100 ms |
| E-13 | `GET /insights/disruption/{id}` | anonymous (rút gọn) / viewer | UC-06 | — | 50 ms |
| E-14 | `GET /insights/otp` | viewer | UC-06, FR-08 | `otp` | 150 ms |
| E-15 | `GET /insights/ticketing-anomalies` | viewer | UC-12, FR-09.4 | — | 100 ms |
| E-16 | `GET /insights/ticketing-anomalies/{id}` | viewer | UC-12 | — | 50 ms |
| E-17 | `GET /insights/dispatch-suggestions` | viewer | UC-04, FR-09.6 | — | 100 ms |
| E-18 | `POST /insights/dispatch-suggestions/{id}/feedback` | operator | UC-04 | — | 100 ms |
| E-20 | `GET /alerts` | anonymous (PUBLIC) / viewer | UC-05, FR-11.5 | — | 100 ms |
| E-21 | `POST /alerts/{id}/ack` | operator | UC-05 | — | 100 ms |
| E-30 | `GET /etl/jobs` | viewer | UC-07 | — | 150 ms |
| E-31 | `GET /etl/jobs/summary` | viewer | UC-07 | — | 150 ms |
| E-32 | `GET /etl/jobs/{runId}` | viewer | UC-07 | — | 100 ms |
| E-33 | `POST /etl/jobs` | operator | UC-07, UC-13 | — | 100 ms |
| E-34 | `GET /etl/job-requests/{id}` | viewer | UC-07 | — | 50 ms |
| E-35 | `POST /etl/jobs/{runId}/restart` | operator | UC-07 E1 | — | 100 ms |
| E-36 | `POST /etl/jobs/{runId}/stop` | operator | UC-07 | — | 100 ms |
| E-37 | `GET /etl/batches/{batchId}` | viewer | UC-07, FR-12.5 | — | 100 ms |
| E-38 | `GET /etl/feeds` | viewer | UC-13 | — | 50 ms |
| E-40 | `GET /etl/dlq` | viewer | UC-08, UC-09 | — | 100 ms |
| E-41 | `GET /etl/dlq/summary` | viewer | UC-08 | — | 100 ms |
| E-42 | `GET /etl/dlq/{id}` | viewer | UC-08 | — | 50 ms |
| E-43 | `PUT /etl/dlq/{id}/payload` | operator | UC-08, FR-12.1 | — | 150 ms |
| E-44 | `POST /etl/dlq/{id}/replay` | operator | UC-08, FR-12.2 | — | 100 ms |
| E-45 | `POST /etl/dlq/{id}/confirm` | operator | UC-09, FR-09.3 | — | 100 ms |
| E-46 | `POST /etl/dlq/{id}/discard` | operator | UC-08 | — | 100 ms |
| E-47 | `POST /etl/dlq/{id}/resolve` | operator | UC-08 | — | 100 ms |
| E-48 | `GET /etl/dlq/actions` | viewer | UC-09, FR-11.4 | — | 100 ms |
| E-50 | `POST /etl/replays` | operator | UC-10, FR-12.3 | — | 100 ms |
| E-51 | `GET /etl/replays` | viewer | UC-10 | — | 50 ms |
| E-52 | `GET /etl/replays/{id}` | viewer | UC-10 | — | 50 ms |
| E-53 | `GET /etl/replays/estimate` | operator | UC-10 | — | 150 ms |
| E-55 | `GET /etl/flags` | viewer | UC-11 | — | 30 ms |
| E-56 | `GET /etl/flags/{key}` | viewer | UC-11 | — | 30 ms |
| E-57 | `PUT /etl/flags/{key}` | operator | UC-11, FR-15.1 | — | 100 ms |
| E-60 | `GET /system/freshness` | anonymous | UC-15, FR-15.2 | `freshness` | 20 ms |
| E-61 | `GET /me` | anonymous | P5-04 | — | 10 ms |
| E-70 | `GET /stream` | anonymous (kênh `jobs`, `dlq`: viewer) | FR-10.4 | — | — |
| E-80 | `POST /internal/alerts/alertmanager` | webhook token | UC-15, FR-14.1 | — | 100 ms |
| E-90 | `GET/PUT/POST/DELETE /sim/**` | operator, profile `demo` | UC-16, FR-11.7 | — | 300 ms |

Các endpoint cũ được đổi tên (DR-43, cập nhật ở tài liệu liên quan):

| Tên cũ | Tên mới |
| --- | --- |
| `GET /ops/job-runs` | E-30 `GET /etl/jobs` |
| `GET /ops/batches/{id}` | E-37 `GET /etl/batches/{batchId}` |
| `GET /ops/replays/{id}` | E-52 `GET /etl/replays/{id}` |
| `POST /etl/jobs/gtfs-static/run` | E-33 `POST /etl/jobs` với `jobName = GtfsStaticLoadJob` |
| `GET /etl/jobs?type=BATCH` | E-30 với `kind=BATCH_JOB` |

## 3. Vận tải

### E-01 `GET /routes` · `listRoutes`

- **Mục đích:** danh sách tuyến của feed ACTIVE cho bộ chọn tuyến, màu tuyến trên bản đồ và bảng xếp hạng OTP. Danh sách nhỏ (≈ 130 tuyến, tối đa 2.000), không phân trang.
- **Query:**

| Tên | Kiểu | Bắt buộc | Mặc định | Ràng buộc |
| --- | --- | --- | --- | --- |
| `routeType` | int, lặp được | Không | tất cả | Giá trị `route_type` GTFS |

- **200:**

```json
{
  "feedVersionId": 3,
  "items": [
    {
      "routeId": "18",
      "shortName": "18",
      "longName": "Nicollet Av - Nicollet Mall - 1st Av",
      "displayName": "18",
      "routeType": 3,
      "color": "0053A0",
      "textColor": "FFFFFF",
      "sortOrder": 18,
      "typicalHeadwaySeconds": 600
    }
  ]
}
```

- **SQL** (`transit/routes.sql`):

```sql
SELECT route_id, route_short_name, route_long_name, display_name, route_type, route_color,
       route_text_color, route_sort_order, typical_headway_seconds
FROM dw.dim_route
WHERE feed_version_id = :fv
ORDER BY route_sort_order NULLS LAST, display_name, route_id
```

  Index: khóa chính `(feed_version_id, route_id)`. Lọc `routeType` làm trong Java trên kết quả đã cache.
- **`X-Data-As-Of`:** `activatedAt` của feed. **SSE:** không.

### E-02 `GET /routes/{routeId}` · `getRoute`

- **Mục đích:** chi tiết tuyến cho trang tuyến và lớp tuyến trên bản đồ: hai chiều, mỗi chiều có đường đi (GeoJSON) và danh sách trạm theo thứ tự.
- **Path:** `routeId` (text, khớp `dim_route.route_id` của feed ACTIVE).
- **Cách chọn "mẫu" của một chiều:** một tuyến có nhiều biến thể lộ trình. API chọn **shape phổ biến nhất** của mỗi `direction_id` (nhiều chuyến nhất; hòa thì `shape_id` nhỏ hơn), rồi **chuyến đại diện** là chuyến có shape đó với nhiều stop time nhất (hòa thì `trip_id` nhỏ hơn). `label` và `headsign` là giá trị xuất hiện nhiều nhất trong các chuyến có shape đó. Feed không có `shapes.txt` thì đường đi nối tọa độ các trạm của chuyến đại diện (`geometrySource = "STOPS"`).
- **Hình học:** tọa độ `[lon, lat]` làm tròn 6 chữ số, đơn giản hóa Douglas–Peucker với sai số 0,00002° (≈ 2 m) lúc nạp cache. Shape 3.000 điểm còn khoảng 400 điểm.
- **200:**

```json
{
  "routeId": "18",
  "feedVersionId": 3,
  "shortName": "18",
  "longName": "Nicollet Av - Nicollet Mall - 1st Av",
  "displayName": "18",
  "routeType": 3,
  "color": "0053A0",
  "textColor": "FFFFFF",
  "typicalHeadwaySeconds": 600,
  "directions": [
    {
      "directionId": 0,
      "label": "NB",
      "headsign": "Downtown Minneapolis",
      "tripCount": 312,
      "shapeId": "180077",
      "geometrySource": "SHAPE",
      "geometry": { "type": "LineString", "coordinates": [[-93.278123, 44.923411], [-93.278090, 44.925002]] },
      "stops": [
        { "stopId": "51405", "code": "51405", "name": "Nicollet Ave & 46th St", "lat": 44.920401, "lon": -93.278012, "stopSequence": 1 }
      ]
    }
  ]
}
```

- **Lỗi:** 404 `not-found` (tuyến không có trong feed ACTIVE).
- **SQL** (`transit/route_patterns.sql`, sau đó `route_pattern_stops.sql` và `shape_points.sql` theo `shape_id`/`trip_id` đã chọn):

```sql
WITH t AS (
  SELECT trip_id, direction_id, shape_id, direction_label, trip_headsign
  FROM dw.gtfs_trip
  WHERE feed_version_id = :fv AND route_id = :routeId          -- gtfs_trip_route_idx
),
shape_rank AS (
  SELECT DISTINCT ON (direction_id) direction_id, shape_id, count(*) AS trip_count
  FROM t GROUP BY direction_id, shape_id
  ORDER BY direction_id, count(*) DESC, shape_id NULLS LAST
),
rep AS (
  SELECT DISTINCT ON (t.direction_id) t.direction_id, t.trip_id, n.stop_count
  FROM t
  JOIN shape_rank s ON s.direction_id = t.direction_id AND s.shape_id IS NOT DISTINCT FROM t.shape_id
  CROSS JOIN LATERAL (SELECT count(*) AS stop_count FROM dw.gtfs_stop_time st
                      WHERE st.feed_version_id = :fv AND st.trip_id = t.trip_id) n
  ORDER BY t.direction_id, n.stop_count DESC, t.trip_id
)
SELECT s.direction_id, s.shape_id, s.trip_count, r.trip_id AS representative_trip_id,
       (SELECT mode() WITHIN GROUP (ORDER BY direction_label) FROM t
         WHERE t.direction_id = s.direction_id AND t.shape_id IS NOT DISTINCT FROM s.shape_id) AS label,
       (SELECT mode() WITHIN GROUP (ORDER BY trip_headsign) FROM t
         WHERE t.direction_id = s.direction_id AND t.shape_id IS NOT DISTINCT FROM s.shape_id) AS headsign
FROM shape_rank s JOIN rep r USING (direction_id)
ORDER BY s.direction_id
```

  Truy vấn nặng nhất ≈ 40 ms (tuyến 1.000 chuyến), chỉ chạy khi cache trống. Lần đọc sau p95 < 5 ms.
- **Kết quả dùng lại:** mẫu tuyến (danh sách trạm theo chiều) được E-04 và E-06 dùng qua cache `route-detail`.
- **`X-Data-As-Of`:** `activatedAt` của feed. **SSE:** không.

### E-03 `GET /routes/{routeId}/delays` · `getRouteDelays`

- **Mục đích:** độ trễ quan sát được của một tuyến theo thời gian, cho heatmap giờ × thứ và biểu đồ trễ của Route scorecard (UC-06).
- **Quyền:** viewer. **Trục:** event, tính theo **giờ theo lịch** (`scheduled_arrival`) của từng lần tới trạm.
- **Query:**

| Tên | Kiểu | Bắt buộc | Mặc định | Ràng buộc |
| --- | --- | --- | --- | --- |
| `from`, `to` | thời điểm | Không | `to = businessNow`, `from = to − 7d` | Khoảng ≤ 31 ngày |
| `bucket` | enum | Không | `hour` | `hour` \| `day` \| `hour-of-week` |
| `directionId` | int | Không | cả hai | `0` \| `1` |

- **Chỉ số của mỗi bucket:** chỉ tính arrival quan sát (`is_observed`), `schedule_relationship = 'SCHEDULED'`, `delay_seconds` khác NULL (giống OTP, DOC-23 §8.1). `onTimePercentage` dùng cùng ngưỡng với OTP (`pti.analytics.otp.early-tolerance`/`late-tolerance`, api đọc chung cấu hình).
- **200** (`bucket=hour`):

```json
{
  "routeId": "18",
  "bucket": "hour",
  "from": "2026-09-22T21:00:00Z",
  "to": "2026-09-29T21:00:00Z",
  "earlyToleranceSeconds": 300,
  "lateToleranceSeconds": 300,
  "items": [
    {
      "bucketStart": "2026-09-29T20:00:00Z",
      "avgDelaySeconds": 142.6,
      "medianDelaySeconds": 118,
      "p90DelaySeconds": 391,
      "observationCount": 1204,
      "onTimePercentage": 81.23
    }
  ]
}
```

  Với `bucket=day`, mỗi phần tử có `serviceDate` thay cho `bucketStart`. Với `bucket=hour-of-week`, có `dayOfWeek` (1 = thứ Hai … 7 = Chủ nhật, ISO) và `hourOfDay` (0–23, giờ địa phương). Bucket không có quan sát thì không xuất hiện. Danh sách nhỏ: tối đa 744 phần tử (31 ngày × 24 giờ).
- **Lỗi:** 404 `not-found` (tuyến), 400 (`bucket`, khoảng thời gian).
- **SQL** (`transit/route_delays.sql`; `:bucketExpr` là một trong ba biểu thức cố định, chọn trong Java, không bao giờ ghép chuỗi từ input):

```sql
-- :bucketExpr ∈ { date_trunc('hour', tu.scheduled_arrival),
--                 (tu.scheduled_arrival AT TIME ZONE :tz)::date,
--                 extract(isodow FROM tu.scheduled_arrival AT TIME ZONE :tz) * 100
--                   + extract(hour FROM tu.scheduled_arrival AT TIME ZONE :tz) }
SELECT :bucketExpr                                                   AS bucket,
       round(avg(tu.delay_seconds), 1)                               AS avg_delay_seconds,
       percentile_disc(0.5) WITHIN GROUP (ORDER BY tu.delay_seconds) AS median_delay_seconds,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY tu.delay_seconds) AS p90_delay_seconds,
       count(*)                                                      AS observation_count,
       round(100.0 * count(*) FILTER (WHERE tu.delay_seconds BETWEEN -:early AND :late) / count(*), 2)
                                                                     AS on_time_percentage
FROM dw.fact_trip_update tu
WHERE tu.service_date BETWEEN :fromDate - 1 AND :toDate          -- partition pruning; fact_trip_update_route_idx
  AND tu.route_id = :routeId
  AND tu.scheduled_arrival >= :from AND tu.scheduled_arrival < :to
  AND tu.is_observed AND tu.schedule_relationship = 'SCHEDULED' AND tu.delay_seconds IS NOT NULL
  AND (:directionId::smallint IS NULL OR tu.direction_id = :directionId)
GROUP BY 1
ORDER BY 1
```

  `:fromDate`/`:toDate` = ngày địa phương của `from`/`to`. Khối lượng: tuyến đông ≈ 3.000 quan sát/ngày, 31 ngày ≈ 93.000 dòng. `percentile_disc` trả số nguyên như cột nguồn.
- **`X-Data-As-Of`:** `lastEventAt` của `GTFS_RT_TRIP_UPDATE`. **SSE:** không.

### E-04 `GET /routes/{routeId}/delay-profile` · `getRouteDelayProfile`

- **Mục đích:** độ trễ lịch sử (bảng ETA, DOC-23 §7) tại từng trạm của một chiều, cho một khung thứ × giờ. Trang tuyến hiển thị "xe thường trễ bao nhiêu ở trạm nào vào giờ này".
- **Query:**

| Tên | Kiểu | Bắt buộc | Mặc định | Ràng buộc |
| --- | --- | --- | --- | --- |
| `directionId` | int | Có | — | `0` \| `1`; chiều phải tồn tại |
| `dayOfWeek` | int | Không | thứ của `businessNow` (giờ địa phương) | 1–7 (ISO) |
| `hourOfDay` | int | Không | giờ của `businessNow` (giờ địa phương) | 0–23 |

- **200:**

```json
{
  "routeId": "18",
  "directionId": 0,
  "dayOfWeek": 2,
  "hourOfDay": 16,
  "windowStart": "2026-09-01",
  "windowEnd": "2026-09-28",
  "computedAt": "2026-09-29T21:05:12Z",
  "items": [
    {
      "stopId": "51405",
      "name": "Nicollet Ave & 46th St",
      "stopSequence": 1,
      "avgDelaySeconds": 64.2,
      "medianDelaySeconds": 51,
      "p90DelaySeconds": 170,
      "sampleCount": 36,
      "confidence": "HIGH"
    },
    { "stopId": "51406", "name": "Nicollet Ave & 44th St", "stopSequence": 2, "sampleCount": 0, "confidence": "NONE" }
  ]
}
```

  Thứ tự là thứ tự trạm của mẫu tuyến (E-02). Trạm không có dòng ETA có `sampleCount = 0`, `confidence = NONE` và không có trường delay. `confidence` theo DOC-23 §7.3. `windowStart`/`windowEnd`/`computedAt` lấy từ dòng có `computed_at` lớn nhất; vắng mặt khi không có dòng nào.
- **Lỗi:** 404 (tuyến hoặc chiều), 400.
- **SQL** (`transit/route_delay_profile.sql`):

```sql
SELECT stop_id, avg_delay_seconds, median_delay_seconds, p90_delay_seconds, sample_count,
       window_start, window_end, computed_at
FROM insight.insight_eta_prediction
WHERE route_id = :routeId AND day_of_week = :dayOfWeek AND hour_of_day = :hourOfDay
```

  Dùng khóa chính `(route_id, stop_id, day_of_week, hour_of_day)` theo tiền tố `route_id` (≤ 150 trạm × 168 khung mỗi tuyến). Ghép với danh sách trạm trong Java.
- **`X-Data-As-Of`:** `max(computed_at)` của bảng ETA. **SSE:** không.

### E-05 `GET /vehicles/live` · `listLiveVehicles`

- **Mục đích:** snapshot vị trí mới nhất của mọi xe đang chạy, để vẽ bản đồ lần đầu và sau mỗi lần SSE nối lại (UC-01), và cho polling khi SSE hỏng (mỗi 5 giây).
- **Ngoại lệ phân trang:** snapshot, không phân trang, chặn cứng 1.500 phần tử (DOC-31 §5.2).
- **Query:**

| Tên | Kiểu | Bắt buộc | Mặc định | Ràng buộc |
| --- | --- | --- | --- | --- |
| `routeId` | text, lặp được | Không | tất cả | ≤ 20 giá trị; tuyến không tồn tại chỉ đơn giản không có xe |

- **Quy tắc:** chỉ trả xe có `event_timestamp ≥ businessNow − pti.api.vehicles.max-age` (5 phút). Xe mất tín hiệu lâu hơn thì biến khỏi bản đồ. `delaySeconds` và `stopArrivalAt` lấy từ dòng TripUpdate của **trạm hiện tại** của xe (`current_stop_sequence`); không có dòng thì vắng mặt.
- **Overlay bunching** (chỉ viewer): xe thuộc một episode bunching đang mở có thêm trường `bunching`. Anonymous không bao giờ nhận trường này (episode bunching có audience `OPERATIONS`).
- **200:**

```json
{
  "businessNow": "2026-09-29T21:19:35Z",
  "count": 1,
  "items": [
    {
      "vehicleId": "1203",
      "label": "1203",
      "routeId": "18",
      "tripId": "27371245-AUG26-MVS-BUS-Weekday-01",
      "directionId": 0,
      "headsign": "Downtown Minneapolis",
      "lat": 44.948121,
      "lon": -93.278004,
      "bearing": 358.0,
      "speedMps": 7.4,
      "currentStatus": "IN_TRANSIT_TO",
      "stopId": "51420",
      "currentStopSequence": 14,
      "occupancyStatus": "MANY_SEATS_AVAILABLE",
      "eventTimestamp": "2026-09-29T21:19:30Z",
      "delaySeconds": 95,
      "stopArrivalAt": "2026-09-29T21:20:41Z",
      "bunching": {
        "episodeId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c",
        "role": "FOLLOWER",
        "partnerVehicleId": "1187",
        "gapSeconds": 112,
        "headwaySeconds": 600
      }
    }
  ]
}
```

- **SQL** (`transit/vehicles_live.sql`, `transit/bunching_open.sql`):

```sql
SELECT v.vehicle_id, dv.vehicle_label, v.route_id, v.trip_id, v.direction_id, t.trip_headsign,
       v.lat, v.lon, v.bearing, v.speed_mps, v.current_status, v.stop_id, v.current_stop_sequence,
       v.occupancy_status, v.event_timestamp,
       tu.delay_seconds, coalesce(tu.arrival_time, tu.departure_time) AS stop_arrival_at
FROM dw.vehicle_position_latest v
LEFT JOIN dw.dim_vehicle dv ON dv.vehicle_id = v.vehicle_id
LEFT JOIN dw.gtfs_trip t    ON t.feed_version_id = :fv AND t.trip_id = v.trip_id
LEFT JOIN dw.fact_trip_update tu                                   -- primary key lookup per vehicle
       ON tu.service_date = v.service_date AND tu.trip_id = v.trip_id
      AND tu.stop_sequence = v.current_stop_sequence
WHERE v.event_timestamp >= :businessNow - :maxAge
  AND (cardinality(:routeIds::text[]) = 0 OR v.route_id = ANY(:routeIds))
ORDER BY v.event_timestamp DESC, v.vehicle_id
LIMIT :maxItems + 1;

-- bunching_open.sql (viewer only; partial index insight_bus_bunching_open_idx)
SELECT id, route_id, vehicle_leader, vehicle_follower, last_gap_seconds, scheduled_headway_seconds
FROM insight.insight_bus_bunching
WHERE status = 'OPEN';
```

  Một xe thuộc hai episode (vừa là follower vừa là leader) thì lấy episode có `last_gap_seconds` nhỏ hơn. Thứ tự trả về là `vehicleId` tăng dần (sắp lại trong Java cho ổn định).
- **Cache:** snapshot chung 2 s theo tập `routeId`; overlay bunching 2 s, ghép sau khi lấy từ cache.
- **`X-Data-As-Of`:** `lastEventAt` của `GTFS_RT_VEHICLE_POSITION`. **SSE:** `vehicles.batch` (kênh `vehicles`) cập nhật từng xe; `bunching.opened`/`bunching.closed` (kênh `alerts`, viewer) bật/tắt overlay.

### E-06 `GET /stops` · `searchStops`

- **Mục đích:** tìm trạm theo tên hoặc mã (UC-02 1a), và lấy trạm trong khung bản đồ.
- **Query** (cần đúng một trong `q`, `bbox`, `routeId`; `bbox` và `routeId` dùng chung được):

| Tên | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `q` | text | — | 2–100 ký tự sau khi trim |
| `bbox` | `minLon,minLat,maxLon,maxLat` | — | `min < max`; diện tích ≤ 0,25 độ² (≈ 40 × 28 km ở Minneapolis) |
| `routeId` | text | — | Trạm có ít nhất một chuyến của tuyến |
| `limit` | int | 20 (`q`), 200 (`bbox`/`routeId`) | `q`: 1–50; còn lại: 1–500 |
| `cursor` | text | — | Chỉ với `bbox`/`routeId` |

- **Hai chế độ:**
  - `q`: xếp hạng, **không phân trang** (tối đa 50): mã trạm trùng khớp → tên bắt đầu bằng `q` → tên chứa `q`; trong cùng hạng sắp theo tên rồi `stop_id`. Không phân biệt hoa thường; `%` và `_` trong `q` được escape.
  - `bbox`/`routeId`: keyset theo `stop_id`.
- Chỉ trả `location_type` 0 (trạm) và 1 (nhà ga).
- **200:**

```json
{
  "items": [
    {
      "stopId": "51405",
      "code": "51405",
      "name": "Nicollet Ave & 46th St",
      "lat": 44.920401,
      "lon": -93.278012,
      "locationType": 0,
      "wheelchairBoarding": 1,
      "routeIds": ["18"]
    }
  ],
  "nextCursor": "eyJ2IjoxLCJrIjpbIjUxNDA1Il0sImYiOiI3YzFhIn0"
}
```

- **Lỗi:** 400 (không có hoặc có cả `q` lẫn `bbox`/`routeId`, `bbox` sai, quá rộng).
- **SQL** (`transit/stops_search.sql`, `transit/stops_bbox.sql`):

```sql
-- q
SELECT stop_id, stop_code, stop_name, lat, lon, location_type, parent_station, wheelchair_boarding,
       CASE WHEN stop_code = :q THEN 0
            WHEN stop_name ILIKE :qPrefix ESCAPE '\' THEN 1
            ELSE 2 END AS rank
FROM dw.dim_stop
WHERE feed_version_id = :fv AND location_type IN (0, 1)
  AND (stop_code = :q OR stop_name ILIKE :qContains ESCAPE '\')
ORDER BY rank, stop_name, stop_id
LIMIT :limit;

-- bbox
SELECT stop_id, stop_code, stop_name, lat, lon, location_type, parent_station, wheelchair_boarding
FROM dw.dim_stop
WHERE feed_version_id = :fv AND location_type IN (0, 1)
  AND lon BETWEEN :minLon AND :maxLon AND lat BETWEEN :minLat AND :maxLat
  AND (:afterStopId::text IS NULL OR stop_id > :afterStopId)
ORDER BY stop_id
LIMIT :limit + 1;
```

  Feed có ≈ 12.000 trạm; quét tuần tự một phiên bản feed mất < 10 ms nên không cần index trigram hay GiST. `routeIds` và bộ lọc `routeId` lấy từ bản đồ `stopId → routeIds` của feed, dựng một lần mỗi feed (câu `SELECT DISTINCT st.stop_id, t.route_id FROM dw.gtfs_stop_time st JOIN dw.gtfs_trip t …`, ≈ 2 giây, cache `stop-routes`, TTL theo feed).
- **`X-Data-As-Of`:** `activatedAt` của feed. **SSE:** không.

### E-07 `GET /stops/{stopId}` · `getStop`

- **Mục đích:** thông tin trạm, các tuyến đi qua, và banner gián đoạn đang diễn ra trên các tuyến đó (FR-07.3, FR-11.2).
- **200:**

```json
{
  "stopId": "51405",
  "code": "51405",
  "name": "Nicollet Ave & 46th St",
  "lat": 44.920401,
  "lon": -93.278012,
  "locationType": 0,
  "wheelchairBoarding": 1,
  "routes": [
    { "routeId": "18", "displayName": "18", "color": "0053A0", "textColor": "FFFFFF", "headsigns": ["Downtown Minneapolis", "Nicollet & 66th St"] }
  ],
  "activeDisruptions": [
    {
      "alertId": "0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b",
      "disruptionId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a",
      "routeId": "18",
      "directionId": 0,
      "severity": 1,
      "title": "Delays on route 18 northbound",
      "startedAt": "2026-09-29T20:58:00Z"
    }
  ]
}
```

- **Quy tắc `activeDisruptions`:** alert `type = 'DISRUPTION'` chưa `resolved_at`, `route_id` thuộc các tuyến của trạm, audience trong tập mà người gọi được xem (anonymous: chỉ `PUBLIC`; DOC-31 §7.3, ADR-0023). Tối đa 20, sắp theo `severity` giảm rồi `created_at` giảm. `startedAt` = `body.episodeStart`.
- **Lỗi:** 404.
- **SQL:** phần tĩnh từ `dim_stop` theo khóa chính và bản đồ `stop-routes`; phần động (`transit/stop_disruptions.sql`, không cache):

```sql
SELECT id, ref_id, route_id, (body->>'directionId')::smallint AS direction_id, severity, title,
       (body->>'episodeStart')::timestamptz AS started_at
FROM ops.alert_event
WHERE resolved_at IS NULL AND type = 'DISRUPTION'                 -- alert_event_open_idx
  AND route_id = ANY(:routeIds) AND audience = ANY(:audiences)
ORDER BY severity DESC, created_at DESC
LIMIT 20
```

- **`X-Data-As-Of`:** `activatedAt` của feed. **SSE:** `disruption.opened`/`disruption.closed`, `alert.updated`, `alert.retracted` (kênh `alerts`) → frontend refetch E-07.

### E-08 `GET /stops/{stopId}/arrivals` · `listStopArrivals`

- **Mục đích:** N chuyến sắp tới tại trạm, kèm giờ dự đoán và mức tin cậy (FR-06.2, FR-06.3). Công thức ở DOC-23 §7.4; api hiện thực nguyên văn.
- **Query:**

| Tên | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `limit` | int | `pti.analytics.eta.arrivals.default-limit` (10) | 1–30 |
| `horizon` | duration | `pti.analytics.eta.arrivals.horizon` (`PT90M`) | `PT15M`–`PT3H` |

- **200:**

```json
{
  "stopId": "51405",
  "businessNow": "2026-09-29T21:19:35Z",
  "realtimeEnabled": false,
  "items": [
    {
      "tripId": "27371245-AUG26-MVS-BUS-Weekday-01",
      "routeId": "18",
      "directionId": 0,
      "headsign": "Downtown Minneapolis",
      "serviceDate": "2026-09-29",
      "scheduledArrival": "2026-09-29T21:24:00Z",
      "predictedArrival": "2026-09-29T21:25:04Z",
      "predictedDelaySeconds": 64,
      "sampleCount": 36,
      "confidence": "HIGH"
    },
    {
      "tripId": "27371302-AUG26-MVS-BUS-Weekday-01",
      "routeId": "18",
      "directionId": 0,
      "headsign": "Downtown Minneapolis",
      "serviceDate": "2026-09-29",
      "scheduledArrival": "2026-09-29T21:34:00Z",
      "predictedArrival": "2026-09-29T21:34:00Z",
      "predictedDelaySeconds": 0,
      "sampleCount": 0,
      "confidence": "NONE"
    }
  ]
}
```

  Khi `realtimeEnabled = true` (cờ cấu hình `pti.analytics.eta.realtime-enabled`, F-ANL-06), phần tử có thể có thêm `realtimeArrival`. Không có chuyến nào → `items: []` (UI hiện trạng thái rỗng, DOC-37).
- **Lỗi:** 404 (trạm), 400.
- **SQL:** `transit/arrivals.sql` ở DOC-23 §7.4. Index `gtfs_stop_time_stop_idx (feed_version_id, stop_id, departure_seconds)`, khóa chính `insight_eta_prediction`, khóa chính `fact_trip_update`.
- **Cache:** 5 s theo `(stopId, limit, horizon)`. Giờ dự đoán làm tròn về giây nên cache 5 giây không làm sai thứ tự chuyến.
- **`X-Data-As-Of`:** `max(computed_at)` của bảng ETA; khi `realtimeEnabled` thì `lastEventAt` của `GTFS_RT_TRIP_UPDATE`. **SSE:** không; frontend refetch mỗi 30 giây khi màn hình mở.

## 4. Insight

Quy tắc chung của nhóm insight:

- Danh sách episode (E-10, E-12) lọc theo **giao với khoảng**: `episode_start < :to AND coalesce(episode_end, 'infinity') >= :from`. Vì episode dài nhất là 3 giờ (gián đoạn `MAX_DURATION`, DOC-23 §6.4; bunching ngắn hơn nhiều), câu truy vấn thêm `episode_start >= :from − interval '3 hours'` để index giới hạn được phạm vi quét.
- Trường AI (`likelyCause`, `category`, `action`…) vắng mặt khi chưa được làm giàu. `enrichmentStatus` luôn có để UI hiện "Unclassified" (FR-09.7). `*Confidence` là số trong `[0, 1]`; UI hiện "Low confidence" khi < 0,6 (FR-09.6).
- Index mới cho nhóm này (thêm vào V7, DOC-15 §6): `insight_bus_bunching_start_idx (episode_start DESC, id DESC)`, `insight_service_disruption_start_idx (episode_start DESC, id DESC)`, `insight_dispatch_suggestion_created_idx (created_at DESC, id DESC)`.

### E-10 `GET /insights/bunching` · `listBunchingEpisodes`

- **Quyền:** viewer. **Trục:** event.
- **Query:** `from`, `to` (mặc định 24 giờ), `routeId` (lặp được, ≤ 20), `status` (`OPEN` \| `CLOSED`), `limit`, `cursor`. Thứ tự: `episodeStart` giảm, `id` giảm.
- **200:**

```json
{
  "items": [
    {
      "id": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c",
      "routeId": "18",
      "directionId": 0,
      "vehicleLeader": "1187",
      "vehicleFollower": "1203",
      "episodeStart": "2026-09-29T21:12:15Z",
      "status": "OPEN",
      "scheduledHeadwaySeconds": 600,
      "thresholdSeconds": 300,
      "minGapSeconds": 96,
      "lastGapSeconds": 112,
      "openStopId": "51418",
      "evaluationCount": 29,
      "lastEvaluatedAt": "2026-09-29T21:19:30Z",
      "suggestion": { "id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d", "action": "hold_follower", "actionConfidence": 0.82 }
    }
  ],
  "nextCursor": "…"
}
```

  Episode đóng có thêm `episodeEnd`, `closeReason`. `suggestion` có khi đã có gợi ý điều phối.
- **SQL** (`insight/bunching_list.sql`):

```sql
SELECT b.*, s.id AS suggestion_id, s.action, s.action_confidence
FROM insight.insight_bus_bunching b
LEFT JOIN insight.insight_dispatch_suggestion s ON s.bunching_id = b.id   -- insight_dispatch_suggestion_bunching_uk
WHERE b.episode_start >= :from - interval '3 hours' AND b.episode_start < :to
  AND coalesce(b.episode_end, 'infinity') >= :from
  AND (cardinality(:routeIds::text[]) = 0 OR b.route_id = ANY(:routeIds))
  AND (:status::text IS NULL OR b.status = :status)
  AND (:cursorTs::timestamptz IS NULL OR (b.episode_start, b.id) < (:cursorTs, :cursorId))
ORDER BY b.episode_start DESC, b.id DESC
LIMIT :limit + 1
```

  Index: `insight_bus_bunching_route_idx` khi có `routeId`, ngược lại `insight_bus_bunching_start_idx`.
- **`X-Data-As-Of`:** `lastEventAt` của `GTFS_RT_VEHICLE_POSITION`. **SSE:** `bunching.opened`, `bunching.closed`, `dispatch.suggested`.

### E-11 `GET /insights/bunching/{id}` · `getBunchingEpisode`

- **Quyền:** viewer. Như một phần tử của E-10, thêm `batchId`, `tripLeader`, `tripFollower`, `enrichmentStatus` và `suggestion` đầy đủ (như E-17). 404 nếu không có.

### E-12 `GET /insights/disruption` · `listDisruptionEpisodes`

- **Quyền:** anonymous thấy **góc nhìn công khai**; viewer thấy đầy đủ.
- **Trục:** event. **Query:** `from`, `to` (mặc định 24 giờ), `routeId` (≤ 20), `status`, `limit`, `cursor`. Thứ tự: `episodeStart` giảm, `id` giảm.
- **Góc nhìn công khai:** chỉ episode có alert với `audience = 'PUBLIC'` (tức chưa bị FR-09.5 chuyển sang `ENGINEERING`); chỉ các trường `id`, `routeId`, `directionId`, `episodeStart`, `episodeEnd`, `status`, `currentAvgDelaySeconds`, `peakAvgDelaySeconds`, `affectedStopIds`, `severity`.
- **200** (viewer):

```json
{
  "items": [
    {
      "id": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a",
      "routeId": "18",
      "directionId": 0,
      "episodeStart": "2026-09-29T20:58:00Z",
      "status": "OPEN",
      "severity": 1,
      "audience": "PUBLIC",
      "baselineMeanSeconds": 61.4,
      "baselineStddevSeconds": 38.0,
      "currentAvgDelaySeconds": 212.7,
      "currentZScore": 3.98,
      "peakAvgDelaySeconds": 230.1,
      "peakZScore": 4.44,
      "sampleCount": 57,
      "affectedStopIds": ["51418", "51420", "51422"],
      "lastBucket": "2026-09-29T21:19:00Z",
      "enrichmentStatus": "DONE",
      "dataIssueProbability": 0.12,
      "likelyCause": "traffic",
      "causeConfidence": 0.71,
      "modelVersion": "jev@0.2.0"
    }
  ],
  "nextCursor": "…"
}
```

- **SQL** (`insight/disruption_list.sql`):

```sql
SELECT d.*, a.audience, a.severity
FROM insight.insight_service_disruption d
JOIN ops.alert_event a ON a.dedup_key = 'disruption:' || d.id::text     -- alert_event_dedup_uk
WHERE d.episode_start >= :from - interval '3 hours' AND d.episode_start < :to
  AND coalesce(d.episode_end, 'infinity') >= :from
  AND (cardinality(:routeIds::text[]) = 0 OR d.route_id = ANY(:routeIds))
  AND (:status::text IS NULL OR d.status = :status)
  AND (NOT :publicOnly OR a.audience = 'PUBLIC')
  AND (:cursorTs::timestamptz IS NULL OR (d.episode_start, d.id) < (:cursorTs, :cursorId))
ORDER BY d.episode_start DESC, d.id DESC
LIMIT :limit + 1
```

  Mọi episode đều có alert (ghi cùng transaction, DOC-23 §10.2), nên `JOIN` không làm mất dòng.
- **Cache:** chỉ góc nhìn công khai, 5 s.
- **`X-Data-As-Of`:** `lastEventAt` của `GTFS_RT_TRIP_UPDATE`. **SSE:** `disruption.opened`, `disruption.closed`, `alert.retracted`.

### E-13 `GET /insights/disruption/{id}` · `getDisruptionEpisode`

- Như một phần tử của E-12, theo cùng quy tắc góc nhìn. Episode không công khai trả **404** cho anonymous (không để lộ sự tồn tại). Viewer có thêm `batchId`, `closeReason`, `enrichedAt`.

### E-14 `GET /insights/otp` · `getOtpScorecard`

- **Mục đích:** bảng xếp hạng OTP theo tuyến (UC-06 bước 2) cùng chuỗi theo ngày cho sparkline.
- **Quyền:** viewer. **Trục:** ngày phục vụ.
- **Query:**

| Tên | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `fromDate`, `toDate` | date | 7 ngày kết thúc **hôm qua** (`localDate(businessNow) − 1`), bao gồm cả hai đầu | `fromDate ≤ toDate`, ≤ 31 ngày |
| `routeId` | text, lặp được | tất cả | ≤ 20 |
| `routeType` | int, lặp được | tất cả | |

- **Công thức tổng hợp** nhiều ngày: cộng dồn bộ đếm rồi mới chia (`sum(on_time_count) × 100 / sum(observation_count)`), không lấy trung bình của các phần trăm.
- **200** (danh sách nhỏ, ≤ số tuyến; sắp theo `otpPercentage` tăng, tức tệ nhất lên đầu, rồi `routeId`):

```json
{
  "fromDate": "2026-09-22",
  "toDate": "2026-09-28",
  "items": [
    {
      "routeId": "18",
      "otpPercentage": 78.41,
      "onTimeCount": 60311,
      "earlyCount": 2120,
      "lateCount": 14487,
      "observationCount": 76918,
      "tripCount": 2170,
      "earlyToleranceSeconds": 300,
      "lateToleranceSeconds": 300,
      "daily": [
        { "serviceDate": "2026-09-22", "otpPercentage": 80.02, "observationCount": 11020 }
      ]
    }
  ]
}
```

  `earlyToleranceSeconds`/`lateToleranceSeconds` chỉ có khi mọi ngày trong khoảng dùng cùng ngưỡng; nếu khác nhau (đã đổi cấu hình giữa chừng, FR-08.2) thì có `mixedTolerances: true` thay vào đó.
- **SQL** (`insight/otp.sql`):

```sql
SELECT route_id, service_date, otp_percentage, on_time_count, early_count, late_count,
       observation_count, trip_count, early_tolerance_seconds, late_tolerance_seconds
FROM insight.insight_otp_scorecard
WHERE service_date BETWEEN :fromDate AND :toDate                  -- insight_otp_scorecard_date_idx
  AND (cardinality(:routeIds::text[]) = 0 OR route_id = ANY(:routeIds))
ORDER BY route_id, service_date
```

  Tối đa 130 tuyến × 31 ngày ≈ 4.000 dòng; gộp trong Java. `routeType` lọc qua `dim_route` đã cache.
- **`X-Data-As-Of`:** `max(computed_at)` của bảng OTP. **SSE:** không.

### E-15 `GET /insights/ticketing-anomalies` · `listTicketingAnomalies`

- **Quyền:** viewer. **Trục:** event (`detected_at` = cuối cửa sổ).
- **Query:** `from`, `to` (mặc định 24 giờ), `salePointId`, `category` (lặp được; thêm giá trị đặc biệt `unclassified` = chưa có category), `severity` (0–2, lặp được), `trigger`, `limit`, `cursor`. Thứ tự: `detectedAt` giảm, `id` giảm.
- **200:**

```json
{
  "items": [
    {
      "id": "c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5f",
      "salePointId": "SP-0142",
      "salePointName": "Nicollet Mall Station kiosk 2",
      "routeId": "18",
      "windowStart": "2026-09-29T21:00:00Z",
      "windowEnd": "2026-09-29T21:15:00Z",
      "detectedAt": "2026-09-29T21:15:00Z",
      "trigger": "REFUND_RATIO",
      "txnCount": 25,
      "refundCount": 12,
      "refundRatio": 0.4800,
      "amountSum": 50.00,
      "baselineMean": 14.20,
      "baselineStddev": 3.10,
      "zScore": 3.48,
      "enrichmentStatus": "DONE",
      "category": "fraud_suspect",
      "categoryConfidence": 0.77,
      "severity": 2,
      "severityConfidence": 0.69,
      "modelVersion": "jev@0.2.0"
    }
  ],
  "nextCursor": "…"
}
```

- **SQL** (`insight/ticketing_list.sql`): `insight_ticketing_anomaly` `LEFT JOIN dw.dim_sale_point` (tên, tuyến), `WHERE detected_at >= :from AND detected_at < :to` + bộ lọc, keyset `(detected_at, id) < (…)`. Index `insight_ticketing_anomaly_detected_idx`.
- **`X-Data-As-Of`:** `lastEventAt` của `TICKETING_SALES`. **SSE:** `alert.created` với `type = TICKETING_ANOMALY` → frontend refetch.

### E-16 `GET /insights/ticketing-anomalies/{id}` · `getTicketingAnomaly`

- Như một phần tử của E-15, thêm `summary` (JSON không có PII, DOC-23 §9.5) và `batchId`. 404 nếu không có.

### E-17 `GET /insights/dispatch-suggestions` · `listDispatchSuggestions`

- **Quyền:** viewer. **Trục:** audit (`created_at`).
- **Query:** `from`, `to` (mặc định 24 giờ), `routeId` (≤ 20), `bunchingId`, `feedback` (`accepted` \| `ignored` \| `none`), `limit`, `cursor`. Thứ tự: `createdAt` giảm, `id` giảm.
- **200:**

```json
{
  "items": [
    {
      "id": "3b2a1c0d-9e8f-4a7b-8c6d-5e4f3a2b1c0d",
      "bunchingId": "6f1c2a9e-4b1d-5c8e-9a2f-3d4e5f6a7b8c",
      "routeId": "18",
      "action": "hold_follower",
      "actionConfidence": 0.82,
      "lowConfidence": false,
      "modelVersion": "jev@0.2.0",
      "createdAt": "2026-09-29T21:12:47Z",
      "stateSnapshot": {
        "task": "Suggest one dispatch action for a pair of buses running too close together on the same route.",
        "route": { "routeId": "18", "shortName": "18", "directionId": 0, "directionName": "Northbound" },
        "episode": { "gapSeconds": 118, "scheduledHeadwaySeconds": 600, "gapRatio": 0.2, "minGapSeconds": 104, "durationSeconds": 180 },
        "leader": { "vehicleId": "1432", "nextStopId": "56043", "stopsRemaining": 20, "delaySeconds": 240 },
        "follower": { "vehicleId": "1437", "nextStopId": "56041", "stopsRemaining": 22, "delaySeconds": -40 },
        "timeOfDay": { "localTime": "16:12", "dayType": "WEEKDAY", "peak": true },
        "demand": { "level": "HIGH", "recentTicketSales60m": 412, "typicalTicketSales60m": 290 }
      }
    }
  ],
  "nextCursor": "…"
}
```

  `lowConfidence = actionConfidence < pti.api.dispatch.low-confidence` (0,6, FR-09.6). Đã có phản hồi thì thêm `operatorFeedback`, `feedbackBy`, `feedbackAt`.
- **SQL:** `insight_dispatch_suggestion` theo `route_idx` hoặc `created_idx`, keyset `(created_at, id)`.
- **SSE:** `dispatch.suggested`.

### E-18 `POST /insights/dispatch-suggestions/{id}/feedback` · `submitDispatchFeedback`

- **Quyền:** operator. **Body:** `{"feedback": "accepted"}`; giá trị `accepted` (nút "Accept") hoặc `ignored` (nút "Dismiss"), đúng như cột DB.
- **Hành vi:** ghi đè phản hồi trước đó (người sau thắng; mỗi lần đều log `INFO` `dispatch feedback recorded`). Cùng giá trị với cùng người → 200 không ghi.

```sql
UPDATE insight.insight_dispatch_suggestion
SET operator_feedback = :feedback, feedback_by = :actor, feedback_at = now()
WHERE id = :id
RETURNING *
```

- **200:** phần tử như E-17. **Lỗi:** 404, 400 (giá trị lạ).
- **Datasource:** `operator` (DR-20). **p95** < 100 ms (UX: < 300 ms, DOC-02 bước 4).

## 5. Cảnh báo

### E-20 `GET /alerts` · `listAlerts`

- **Quyền:** anonymous chỉ thấy `audience = PUBLIC`; viewer thấy mọi audience (ADR-0023: audience là mức hiển thị tối thiểu).
- **Trục:** audit (`created_at`).
- **Query:**

| Tên | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `audience` | enum, lặp được | mọi audience người gọi được xem | Anonymous xin `OPERATIONS`/`ENGINEERING` → 403 |
| `type` | enum, lặp được | tất cả | 6 giá trị của DOC-15 |
| `severity` | int, lặp được | tất cả | 0–2 |
| `routeId` | text, lặp được | tất cả | ≤ 20 |
| `state` | enum | `all` | `open` (chưa `resolved_at`) \| `unacknowledged` (open và chưa ack) \| `all` |
| `since` | thời điểm | — | Thay cho `from`: chỉ alert `created_at > since`; dùng cho polling fallback (DOC-26 §8) |
| `from`, `to`, `limit`, `cursor` | | 24 giờ | Chuẩn |

- **200:**

```json
{
  "items": [
    {
      "id": "0a4c7e1f-2b3d-5e6f-8a9b-1c2d3e4f5a6b",
      "type": "DISRUPTION",
      "severity": 1,
      "audience": "PUBLIC",
      "routeId": "18",
      "refTable": "insight.insight_service_disruption",
      "refId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a",
      "title": "Delays on route 18 northbound",
      "body": { "disruptionId": "9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a", "directionId": 0, "currentAvgDelaySeconds": 212.7 },
      "createdAt": "2026-09-29T20:59:31Z",
      "acknowledgedBy": "user:operator",
      "acknowledgedAt": "2026-09-29T21:02:10Z",
      "link": "/map?route=18&disruption=9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a"
    }
  ],
  "nextCursor": "…"
}
```

  - Với anonymous, `body` chỉ giữ các khóa trong danh sách cho phép theo `type` (`DISRUPTION`: `disruptionId`, `directionId`, `episodeStart`, `episodeEnd`, `currentAvgDelaySeconds`, `affectedStopIds`); bỏ `acknowledgedBy`, `acknowledgedAt` và các khóa do triage-worker thêm.
  - `resolvedAt` có mặt khi alert đã resolved (episode đóng hoặc Alertmanager `resolved`).
  - `link` là đường dẫn màn hình UI (FR-14.3), do `AlertLinkBuilder` của API tính lúc đọc (không lưu trong DB), theo DOC-34 §5.3:

| `type` | `link` |
| --- | --- |
| `DISRUPTION` | `/map?route=<routeId>&disruption=<refId>` |
| `BUNCHING` | `/map?route=<routeId>&bunching=<refId>` |
| `TICKETING_ANOMALY` | `/ops/ticketing?anomaly=<refId>` |
| `DLQ_SEVERE` | `/ops/dlq?severity=2&status=NEW,MANUAL,PENDING_CONFIRM` |
| `FEED_STALE` | `/ops/jobs?kind=STREAM` |
| `INFRA` | `body.annotations.runbook_url` nếu có (URL tuyệt đối), ngược lại `/ops/jobs` |

  Cùng hàm được dùng khi API chiếu `alert.created`/`alert.updated` ra SSE (DOC-33 §5.5), nên sự kiện và REST có cùng `link`.
- **SQL** (`alert/alerts_list.sql`): `WHERE created_at >= :from AND created_at < :to AND audience = ANY(:audiences)` + bộ lọc, keyset `(created_at, id) < (…)`. Index: `alert_event_feed_idx (audience, created_at DESC)` khi chỉ một audience; `alert_event_created_idx (created_at DESC, id DESC)` (mới, V5_2) khi nhiều audience; `alert_event_route_idx` khi có `routeId`.
- **`X-Data-As-Of`:** `max(created_at)` trong trang. **SSE:** `alert.created`, `alert.updated`, `alert.retracted`.

### E-21 `POST /alerts/{id}/ack` · `acknowledgeAlert`

- **Quyền:** operator. **Body:** không có.
- **Hành vi:** idempotent. Đã ack thì trả 200 với bản ghi hiện tại (giữ người ack đầu tiên). Alert đã resolved vẫn ack được.

```sql
UPDATE ops.alert_event
SET acknowledged_by = :actor, acknowledged_at = now()
WHERE id = :id AND acknowledged_at IS NULL
RETURNING *;
-- 0 rows: SELECT * FROM ops.alert_event WHERE id = :id  (404 if absent, else 200 as-is)
```

- **200:** alert như E-20. **Lỗi:** 404.
- **Sau commit:** publish `alert.updated` lên `pti.events.ui` (audience = audience của dòng; DOC-33). Chỉ khi UPDATE trả về một dòng.

## 6. Job

### E-30 `GET /etl/jobs` · `listJobRuns`

- **Mục đích:** danh sách lần chạy (job batch và micro-batch gộp theo phút) cho Ops console "Jobs" (UC-07).
- **Quyền:** viewer. **Trục:** audit (`started_at`).
- **Query:**

| Tên | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `from`, `to` | thời điểm | 1 giờ gần nhất | ≤ 24 giờ (DOC-15 §5) |
| `kind` | enum | cả hai | `BATCH_JOB` \| `STREAM` |
| `name` | text, lặp được | tất cả | Tên job hoặc `listener_id` |
| `status` | text, lặp được | tất cả | Trạng thái Spring Batch (`STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `FAILED`, `COMPLETED`, `ABANDONED`, `UNKNOWN`) hoặc của stream (`COMPLETED`, `COMPLETED_WITH_SKIPS`, `FAILED`) |
| `limit`, `cursor` | | | Chuẩn |

- **200:**

```json
{
  "items": [
    {
      "runId": "job:4127",
      "kind": "BATCH_JOB",
      "name": "RawZoneReplayJob",
      "status": "FAILED",
      "exitCode": "FAILED",
      "exitMessage": "Execution became stale",
      "startedAt": "2026-09-29T20:40:02Z",
      "endedAt": "2026-09-29T20:52:11Z",
      "durationMs": 729000,
      "readCount": 412000,
      "writeCount": 411050,
      "skipCount": 950,
      "jobExecutionId": 4127,
      "batchIds": ["0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d"],
      "restartable": true
    },
    {
      "runId": "stream:gtfs-rt-vehicle-position:2026-09-29T21:18Z",
      "kind": "STREAM",
      "name": "gtfs-rt-vehicle-position",
      "status": "COMPLETED_WITH_SKIPS",
      "startedAt": "2026-09-29T21:18:00Z",
      "endedAt": "2026-09-29T21:18:59.870Z",
      "durationMs": 59870,
      "readCount": 7272,
      "writeCount": 7268,
      "skipCount": 4,
      "duplicateCount": 0,
      "batchCount": 60
    }
  ],
  "nextCursor": "…"
}
```

  - `restartable` (chỉ `BATCH_JOB`): `status ∈ {FAILED, STOPPED}` và job có "Restart được = Có" trong DOC-19 §2. Micro-batch không có `restartable` (UC-07 E1).
  - `batchIds` của `BATCH_JOB` tối đa 20 phần tử (xem đủ ở E-32). `STREAM` trả `batchCount` thay vì danh sách.
- **SQL** (`etl/job_runs.sql`):

```sql
SELECT run_id, kind, name, status, exit_code, exit_message, started_at, ended_at,
       read_count, write_count, skip_count, duplicate_count, job_execution_id,
       batch_ids[1:20] AS batch_ids, cardinality(batch_ids) AS batch_count
FROM ops.ops_job_run_v
WHERE started_at >= :from AND started_at < :to
  AND (:kind::text IS NULL OR kind = :kind)
  AND (cardinality(:names::text[]) = 0 OR name = ANY(:names))
  AND (cardinality(:statuses::text[]) = 0 OR status = ANY(:statuses))
  AND (:cursorTs::timestamptz IS NULL OR (started_at, run_id) < (:cursorTs, :cursorRunId))
ORDER BY started_at DESC, run_id DESC
LIMIT :limit + 1
```

  Điều kiện `started_at` được đẩy xuống cả hai nhánh `UNION ALL` của view: nhánh job dùng `batch_job_execution_start_idx`, nhánh stream dùng `etl_stream_batch_minute_idx` (DOC-15 §5). 24 giờ ≈ 1.440 phút × 4 listener ≈ 5.800 dòng stream.
- **SSE:** `job.run` (kênh `jobs`).

### E-31 `GET /etl/jobs/summary` · `getJobSummary`

- **Mục đích:** timeline read/written/skipped theo nguồn (UC-07 bước 1).
- **Quyền:** viewer. **Trục:** audit.
- **Query:** `from`, `to` (mặc định 1 giờ, tối đa 24 giờ); `bucket` ∈ `1m` \| `5m` \| `15m` \| `1h` (mặc định `1m`). Số bucket × số nguồn ≤ 1.440 × 4, nếu vượt → 400 và gợi ý bucket lớn hơn.
- **200:**

```json
{
  "bucket": "1m",
  "from": "2026-09-29T20:20:00Z",
  "to": "2026-09-29T21:20:00Z",
  "stream": [
    {
      "source": "GTFS_RT_VEHICLE_POSITION",
      "points": [
        { "bucketStart": "2026-09-29T21:18:00Z", "batches": 60, "failedBatches": 0, "read": 7272, "written": 7268,
          "skipped": 4, "duplicate": 0, "p95BatchMs": 212 }
      ]
    }
  ],
  "batchJobs": { "running": 1, "completed": 14, "failed": 1, "stopped": 0 }
}
```

  Bucket không có micro-batch nào vẫn có mặt với giá trị 0 (dùng `generate_series`), để biểu đồ thể hiện được khoảng consumer dừng.
- **SQL** (`etl/job_summary.sql`):

```sql
SELECT b.source,
       date_bin(:bucket::interval, b.started_at, TIMESTAMPTZ '2000-01-01 00:00:00Z') AS bucket_start,
       count(*)                                        AS batches,
       count(*) FILTER (WHERE b.status = 'FAILED')     AS failed_batches,
       sum(b.records_read)      AS read,      sum(b.records_written)   AS written,
       sum(b.records_skipped)   AS skipped,   sum(b.records_duplicate) AS duplicate,
       percentile_disc(0.95) WITHIN GROUP (
         ORDER BY extract(epoch FROM b.finished_at - b.started_at) * 1000)::int AS p95_batch_ms
FROM ops.etl_stream_batch b
WHERE b.started_at >= :from AND b.started_at < :to                -- etl_stream_batch_started_idx
GROUP BY 1, 2;

SELECT status, count(*) FROM ops.ops_job_run_v
WHERE kind = 'BATCH_JOB' AND started_at >= :from AND started_at < :to
GROUP BY status;
```

  `batchJobs.running` gộp `STARTING`, `STARTED`, `STOPPING`. Lấp các bucket trống làm trong Java.
- **SSE:** `job.run` → frontend refetch tối đa mỗi 10 giây.

### E-32 `GET /etl/jobs/{runId}` · `getJobRun`

- **Mục đích:** chi tiết một lần chạy (UC-07 bước 3).
- **Path:** `runId` khớp `^job:\d+$` hoặc `^stream:[a-z0-9-]+:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z$`; khác → 404.
- **200** (`BATCH_JOB`):

```json
{
  "runId": "job:4127",
  "kind": "BATCH_JOB",
  "name": "RawZoneReplayJob",
  "status": "FAILED",
  "exitCode": "FAILED",
  "exitMessage": "Execution became stale",
  "startedAt": "2026-09-29T20:40:02Z",
  "endedAt": "2026-09-29T20:52:11Z",
  "jobExecutionId": 4127,
  "restartable": true,
  "parameters": [
    { "name": "replayRequestId", "type": "java.lang.String", "value": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a", "identifying": true },
    { "name": "fromTs", "type": "java.time.Instant", "value": "2026-09-28T00:00:00Z", "identifying": false }
  ],
  "steps": [
    {
      "stepExecutionId": 99121,
      "stepName": "replayRecords",
      "status": "FAILED",
      "exitCode": "FAILED",
      "exitMessage": "org.springframework.dao.QueryTimeoutException: …",
      "startedAt": "2026-09-29T20:40:05Z",
      "endedAt": "2026-09-29T20:52:11Z",
      "readCount": 412000, "writeCount": 411050, "filterCount": 0,
      "readSkipCount": 0, "processSkipCount": 950, "writeSkipCount": 0,
      "commitCount": 823, "rollbackCount": 1,
      "batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d",
      "executionContext": "{\"pti.replay.objectIndex\":112,\"pti.replay.lineOffset\":4000}"
    }
  ],
  "request": { "type": "replay", "id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a" },
  "links": {
    "trace": "http://localhost:3000/explore?…",
    "logs": "http://localhost:3000/explore?…"
  }
}
```

  - `executionContext` là chuỗi JSON đã cắt 2.500 ký tự (ExecutionContext dùng Jackson, DOC-19 §3.1). UI hiển thị nguyên văn.
  - `request` cho biết lần chạy xuất phát từ `replay_request` hay `job_request` nào (tra theo `job_execution_id`), để UI nối tới E-52/E-34.
  - `links`: URL Grafana Explore dựng từ `pti.api.links.grafana-url` (mặc định `http://localhost:3000`). `trace`: TraceQL `{ span.pti.batch_id = "<batchId đầu tiên>" }`; `logs`: LogQL `{service_name=~"etl.*"} | batch_id = "<…>"` trong khoảng `startedAt − 1m … endedAt + 1m` (DOC-28). Vắng mặt khi không có `batchId`.
- **200** (`STREAM`): trường chung như E-30, thêm `batches` (≤ 120 phần tử) gồm `batchId`, `status`, `writeMode`, `instanceId`, `offsets`, `recordsRead/Written/Skipped/Duplicate`, `minEventTs`, `maxEventTs`, `startedAt`, `finishedAt`, `errorClass`, `errorMessage`, mỗi phần tử có `links`.
- **SQL:** `ops_job_run_v` theo `run_id`; `ops.ops_job_step_v` và `ops.ops_job_execution_param_v` (view mới, DOC-15 §5) theo `job_execution_id`; `replay_request`/`job_request` theo `job_execution_id`; nhánh stream đọc `etl_stream_batch` theo `listener_id` và `date_bin(...) = :minute` (`etl_stream_batch_minute_idx`).
- **SSE:** `job.run` cùng `runId` → refetch.

### E-33 `POST /etl/jobs` · `requestJobRun`

- **Mục đích:** chạy một job theo yêu cầu (UC-07, UC-13). API chỉ ghi `ops.job_request` `RUN`; `JobRequestPoller` của `etl-batch` gọi `JobOperator.start` trong ≤ 5 giây (ADR-0013, DOC-19 §7.3).
- **Quyền:** operator. **Header:** `Idempotency-Key` (khuyến nghị).
- **Body:**

```json
{ "jobName": "OtpScorecardJob", "parameters": { "serviceDates": ["2026-09-27", "2026-09-28"] } }
```

- **Danh sách cho phép và kiểm tra ở API** (sai → 422 `job-not-allowed` hoặc 400 `validation-error`). Tham số định danh `runKey = manual:<requestId>` do `etl-batch` tự thêm; client không gửi.

| `jobName` | Tham số | Kiểm tra ở API |
| --- | --- | --- |
| `GtfsStaticLoadJob` | `sourceUri` (tùy chọn), `allowReactivate` (boolean, mặc định `false`) | `sourceUri` có scheme `https`, `s3` hoặc `file`; phần còn lại (danh sách thư mục cho phép) do `etl-batch` kiểm (DOC-21 §1.1) |
| `EtaAggregationJob` | `hour` (thời điểm, tùy chọn, phải là đầu giờ), `force` (boolean) | `hour ≤ businessNow` |
| `OtpScorecardJob` | `serviceDates` (mảng date, 1–31 phần tử) | Mỗi ngày `< localDate(businessNow)` |
| `AnalyticsRecomputeJob` | `detectors` (mảng con của `BUNCHING`, `DISRUPTION`, `TICKETING`; mặc định cả ba), `fromTs`, `toTs` (bắt buộc) | `fromTs < toTs ≤ businessNow`, khoảng ≤ 7 ngày (DOC-23 §11) |
| `PartitionMaintenanceJob` | không có | — |

  Tham số không có trong bảng → 400. API chuẩn hóa sang dạng chuỗi mà `JobRequestPoller` hiểu (`serviceDates` và `detectors` nối bằng `+`, như `make job-run`, DOC-38) và lưu vào `job_parameters`.
- **202:**

```json
{
  "id": "0192f6c3-4d5e-7f6a-8b9c-0d1e2f3a4b5c",
  "kind": "RUN",
  "jobName": "OtpScorecardJob",
  "parameters": { "serviceDates": "2026-09-27+2026-09-28" },
  "status": "PENDING",
  "requestedBy": "user:operator",
  "requestedAt": "2026-09-29T21:20:03Z"
}
```

  Header `Location: /api/v1/etl/job-requests/{id}`. UI poll E-34 mỗi 2 giây cho tới khi `RUNNING` rồi chuyển sang E-32.
- **SQL:** `INSERT INTO ops.job_request (id, kind, job_name, job_parameters, requested_by, idempotency_key) VALUES (:id, 'RUN', …)`; `id` là UUIDv7.
- **Lỗi:** 400, 422 `job-not-allowed`, 422 `idempotency-key-reused`.

### E-34 `GET /etl/job-requests/{id}` · `getJobRequest`

- **Quyền:** viewer. **200:** như 202 của E-33, thêm (khi có) `jobExecutionId`, `runId` (`job:<jobExecutionId>`), `startedAt`, `finishedAt`, `message` (lý do `REJECTED`/`FAILED`), `targetRunId` (với `RESTART`/`STOP`). 404 nếu không có.

### E-35 `POST /etl/jobs/{runId}/restart` · `requestJobRestart`

- **Quyền:** operator. **Header:** `Idempotency-Key`. **Body:** không có.
- **Kiểm tra trước** (đọc `ops_job_run_v`): `runId` dạng `job:<n>` và tồn tại (404); `status ∈ {FAILED, STOPPED}` và job restart được (DOC-19 §2) → ngược lại 409 `job-not-restartable` (`detail` nêu lý do: `Streaming runs cannot be restarted.`, `Only FAILED or STOPPED executions can be restarted.`, `<job> is not restartable.`). `etl-batch` vẫn kiểm lại và có thể `REJECTED` (trạng thái đổi giữa chừng).
- **Ghi:** `job_request` `kind = 'RESTART'`, `job_name`, `target_job_execution_id = n`.
- **202:** job request như E-34, `Location` như E-33.

### E-36 `POST /etl/jobs/{runId}/stop` · `requestJobStop`

- Như E-35, với `kind = 'STOP'`. Kiểm tra: `status ∈ {STARTING, STARTED}` → ngược lại 409 `job-not-running`. Dừng là cooperative: Spring Batch dừng ở ranh giới chunk hoặc lần gọi tasklet kế tiếp, nên `STOPPED` có thể xuất hiện sau vài giây.

### E-37 `GET /etl/batches/{batchId}` · `getBatchLineage`

- **Mục đích:** truy vết một `batch_id` (FR-12.5, DR-63): nó thuộc micro-batch hay step nào, tạo bao nhiêu DLQ, kết quả DQ.
- **Quyền:** viewer.
- **200:**

```json
{
  "batchId": "0192f5a1-7c1e-7d3a-9b2c-4e5f6a7b8c9d",
  "origin": "BATCH_STEP",
  "runId": "job:4127",
  "jobName": "RawZoneReplayJob",
  "stepName": "replayRecords",
  "stepExecutionId": 99121,
  "startedAt": "2026-09-29T20:40:05Z",
  "endedAt": "2026-09-29T20:52:11Z",
  "counts": { "read": 412000, "written": 411050, "skipped": 950 },
  "replayRequestId": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a",
  "deadLetters": { "total": 950, "byStatus": { "NEW": 12, "RESOLVED": 938 } },
  "dataQuality": [
    { "ruleId": "DQ-14", "tableName": "dw.fact_trip_update", "violationCount": 0, "checkedAt": "2026-09-29T20:52:30Z" }
  ],
  "links": { "trace": "…", "logs": "…" }
}
```

  `origin = "STREAM"` thì có `listenerId`, `source`, `instanceId`, `offsets`, `writeMode` thay cho phần job/step.
- **SQL:** thử `ops.etl_stream_batch` theo khóa chính, rồi `ops.ops_job_step_v` theo `batch_id`; không thấy → 404. `dead_letter` đếm theo `dead_letter_batch_idx`; `dq_check_result` theo `dq_check_result_batch_idx`. Không đếm dòng fact (có thể hàng triệu); số đã ghi lấy từ bộ đếm của batch hoặc step.

### E-38 `GET /etl/feeds` · `listFeedVersions`

- **Mục đích:** các phiên bản feed GTFS static cho UC-13 (feed nào ACTIVE, lần nạp gần nhất, lỗi kiểm tra).
- **Quyền:** viewer. Danh sách nhỏ: 50 phiên bản mới nhất theo `loaded_at`.
- **200:**

```json
{
  "items": [
    {
      "feedVersionId": 3,
      "feedHash": "9f2c…",
      "status": "ACTIVE",
      "publisherName": "Metro Transit",
      "publisherFeedVersion": "2026-08-23",
      "agencyTimezone": "America/Chicago",
      "validFrom": "2026-08-23",
      "validTo": "2026-12-12",
      "loadedAt": "2026-09-27T08:31:12Z",
      "activatedAt": "2026-09-27T08:34:40Z",
      "runId": "job:3810",
      "validation": { "errors": 0, "warnings": 14 }
    }
  ]
}
```

  `validation` tóm tắt từ `validation_report` (DOC-21 §4). Báo cáo đầy đủ không trả qua API; xem trong log của job.

## 7. Dead letter

Quy tắc chung: mọi thao tác ghi dùng datasource `operator`, một transaction, UPDATE có điều kiện trạng thái nguồn (DOC-15 §4.3), rồi `INSERT` vào `dlq_action_log` với `actor = user:<username>`. UPDATE trả 0 dòng → đọc lại trạng thái hiện tại → 409 `dlq-invalid-state` kèm `currentStatus`. Sau commit, publish `dlq.changed` (`kind = UPDATED`, DOC-33).

| Thao tác | Trạng thái nguồn cho phép | Trạng thái đích | `dlq_action_log.action` |
| --- | --- | --- | --- |
| E-43 sửa payload | `NEW`, `TRIAGED`, `PENDING_CONFIRM`, `MANUAL` | giữ nguyên | `EDITED` |
| E-44 replay | `NEW`, `MANUAL` | `REPLAY_REQUESTED` | `REPLAY_REQUESTED` |
| E-45 confirm | `PENDING_CONFIRM` | `REPLAY_REQUESTED` | `CONFIRMED`, rồi `REPLAY_REQUESTED` |
| E-46 discard | `NEW`, `MANUAL`, `PENDING_CONFIRM` | `DISCARDED` | `DISCARDED` |
| E-47 resolve | `MANUAL` | `RESOLVED` | `RESOLVED` |

### E-40 `GET /etl/dlq` · `listDeadLetters`

- **Quyền:** viewer. **Trục:** audit (`created_at`).
- **Query:** `status`, `source`, `stage`, `category` (thêm `unclassified`), `severity`, `ruleId` (đều lặp được); `from`, `to` **tùy chọn, không có mặc định và không giới hạn độ dài khoảng** (ngoại lệ DOC-31 §4.3: danh sách được index và phân trang, và bản ghi `MANUAL` cũ vẫn phải thấy được); `limit`, `cursor`. Thứ tự: `createdAt` giảm, `id` giảm.
- **200:**

```json
{
  "items": [
    {
      "id": "0192f5b2-8d9e-7a0b-9c1d-2e3f4a5b6c7d",
      "source": "GTFS_RT_VEHICLE_POSITION",
      "stage": "SCHEMA",
      "ruleId": "DQ-01",
      "errorClass": "SchemaViolationException",
      "errorMessage": "$.payload.position.latitude: must be between -90 and 90",
      "status": "MANUAL",
      "category": "schema_violation",
      "categoryConfidence": 0.412,
      "severity": 2,
      "severityConfidence": 0.655,
      "businessKey": "1203|2026-09-29T21:18:45Z",
      "payloadPreview": "{\"schema_version\":1,\"entity_type\":\"VEHICLE_POSITION\",\"event_timestamp\":\"2026-09-29T21:18:45Z\",\"payload\":{\"vehicle_id\":\"1203\",…",
      "hasEditedPayload": false,
      "replayCount": 0,
      "autoReplayCount": 0,
      "createdAt": "2026-09-29T21:18:47Z",
      "updatedAt": "2026-09-29T21:19:02Z"
    }
  ],
  "nextCursor": "…"
}
```

  `payloadPreview` = 200 ký tự đầu của `raw_payload`.
- **SQL:** `ops.dead_letter` với bộ lọc và keyset `(created_at, id) < (…)`. Index: `dead_letter_list_idx (status, created_at DESC)` khi có `status`, `dead_letter_source_idx` khi có `source`; lọc khác áp trên kết quả. Không đọc cột `raw_payload` đầy đủ (chỉ `left(raw_payload, 200)`).
- **SSE:** `dlq.changed` (kênh `dlq`).

### E-41 `GET /etl/dlq/summary` · `getDeadLetterSummary`

- **Quyền:** viewer. Không có tham số.
- **200:**

```json
{
  "open": 214,
  "byStatus": { "NEW": 3, "TRIAGING": 1, "TRIAGED": 0, "AUTO_REPLAY_SCHEDULED": 2, "PENDING_CONFIRM": 9, "MANUAL": 199, "REPLAY_REQUESTED": 0 },
  "openBySource": { "GTFS_RT_VEHICLE_POSITION": 180, "GTFS_RT_TRIP_UPDATE": 30, "TICKETING_SALES": 4 },
  "openBySeverity": { "0": 20, "1": 60, "2": 120, "unclassified": 14 },
  "createdLastHour": 37
}
```

  "Mở" = trạng thái khác `REPLAYED`, `DISCARDED`, `RESOLVED` (cùng định nghĩa với gauge `pti_dlq_open_records`, DOC-28).
- **SQL:** một câu `GROUP BY status, source, severity` trên `WHERE status NOT IN ('REPLAYED','DISCARDED','RESOLVED')` (dùng `dead_letter_list_idx`), và `count(*) WHERE created_at >= now() − 1h`.

### E-42 `GET /etl/dlq/{id}` · `getDeadLetter`

- **Quyền:** viewer.
- **200:** mọi trường của E-40 (trừ `payloadPreview`), thêm:
  - `rawPayload` (chuỗi nguyên văn, đã làm sạch PII khi ghi, DOC-18 §4), `editedPayload` (object, nếu có);
  - `kafka`: `topic`, `partition`, `offset`, `timestamp`;
  - `batchId`, `modelVersion`, `triagedAt`, `triageAttempts`, `lastReplayAt`, `resolvedBy`, `resolvedAt`;
  - `actions`: 100 dòng `dlq_action_log` gần nhất (`at`, `action`, `actor`, `confidence`, `details`), cũ trước;
  - `replays`: các `replay_request` `DLQ_RECORD` của record (`id`, `status`, `requestedBy`, `requestedAt`, `finishedAt`);
  - `allowedActions`: tập con của `edit`, `replay`, `confirm`, `discard`, `resolve` theo bảng đầu §7 **và** role của người gọi (viewer luôn nhận `[]`). UI dùng để bật nút, không tự suy luận.

### E-43 `PUT /etl/dlq/{id}/payload` · `editDeadLetterPayload`

- **Quyền:** operator. **Body:** chính payload đã sửa (JSON object, đúng dạng message nguồn: envelope GTFS-rt hoặc value CDC). ≤ 1 MiB.
- **Kiểm tra:** theo bảng DOC-22 §2 (trạng thái → 409 `dlq-invalid-state`; JSON, schema → 422 `invalid-payload` kèm `errors[]` với `field` là JSON Pointer; PII → 422 `pii-not-allowed`; khóa nghiệp vụ → 422 `business-key-changed`).
- **Ghi:** `edited_payload`, `updated_at`; `dlq_action_log` `EDITED` với `details.changed_paths` (DOC-22 §2).
- **200:** bản ghi như E-42. Sửa lại lần nữa ghi đè `edited_payload`; `raw_payload` không đổi.

### E-44 `POST /etl/dlq/{id}/replay` · `replayDeadLetter`

- **Quyền:** operator. **Header:** `Idempotency-Key` (UC-08). **Body:** không có. Payload replay là `edited_payload` nếu có, ngược lại `raw_payload` (DOC-22 §3.2).
- **Transaction:**

```sql
UPDATE ops.dead_letter SET status = 'REPLAY_REQUESTED', updated_at = now()
WHERE id = :id AND status IN ('NEW', 'MANUAL')
RETURNING source;
INSERT INTO ops.replay_request (id, kind, source, dead_letter_id, requested_by, idempotency_key)
VALUES (:replayId, 'DLQ_RECORD', :source, :id, :actor, :key);
INSERT INTO ops.dlq_action_log (dead_letter_id, action, actor, details)
VALUES (:id, 'REPLAY_REQUESTED', :actor, jsonb_build_object('replay_request_id', :replayId));
```

  Vi phạm `replay_request_one_per_record` (không xảy ra khi trạng thái đúng, nhưng vẫn bắt) → 409 `replay-already-running` với `existingReplayId`.
- **202:** replay request như E-52, `Location: /api/v1/etl/replays/{id}`.

### E-45 `POST /etl/dlq/{id}/confirm` · `confirmDeadLetterReplay`

- Như E-44 với trạng thái nguồn `PENDING_CONFIRM`, và ghi thêm `dlq_action_log` `CONFIRMED` (`confidence` = `category_confidence` của dòng) trước `REPLAY_REQUESTED` (FR-09.3). Xác nhận nhiều dòng: UI gọi lần lượt, tối đa 4 request song song, mỗi request một `Idempotency-Key`; kết quả từng dòng hiển thị riêng.

### E-46 `POST /etl/dlq/{id}/discard` · `discardDeadLetter`

- **Quyền:** operator. **Body:** `{"reason": "Duplicate of an already corrected record"}`, `reason` 3–500 ký tự, bắt buộc.
- **Ghi:** `status = 'DISCARDED'`, `resolved_by = :actor`, `resolved_at = now()`; `dlq_action_log` `DISCARDED` với `details = {"reason": …}`.
- **200:** bản ghi như E-42. Đã `DISCARDED` bởi cùng người → 200 không ghi; trạng thái khác → 409.

### E-47 `POST /etl/dlq/{id}/resolve` · `resolveDeadLetter`

- Như E-46 với trạng thái nguồn `MANUAL`, đích `RESOLVED`, body `{"note": "…"}` (3–500 ký tự), `dlq_action_log` `RESOLVED` với `details = {"note": …}`. Dùng khi đã xử lý ngoài hệ thống (ví dụ sửa dữ liệu nguồn và nguồn đã gửi lại).

### E-48 `GET /etl/dlq/actions` · `listDeadLetterActions`

- **Mục đích:** nhật ký hành động, gồm nhật ký auto-replay (FR-11.4, UC-09).
- **Quyền:** viewer. **Trục:** audit (`at`).
- **Query:** `from`, `to` (mặc định 24 giờ), `action` (lặp được), `actorType` (`auto` \| `system` \| `user`), `deadLetterId`, `limit`, `cursor`. Thứ tự: `at` giảm, `id` giảm.
- **200:** `items` gồm `id`, `deadLetterId`, `action`, `actor`, `confidence`, `details`, `at`, cùng `source` và `status` hiện tại của dead letter (JOIN theo khóa chính).
- **SQL:** `dlq_action_log_at_idx (at)`; với `deadLetterId` dùng `dlq_action_log_dead_letter_idx`. `actorType` ánh xạ sang `actor = 'auto'`, `actor LIKE 'system:%'`, `actor LIKE 'user:%'`.

## 8. Replay khoảng thời gian

### E-50 `POST /etl/replays` · `requestRawReplay`

- **Quyền:** operator. **Header:** `Idempotency-Key`.
- **Body:**

```json
{ "source": "GTFS_RT_TRIP_UPDATE", "fromTs": "2026-09-28T00:00:00Z", "toTs": "2026-09-29T00:00:00Z", "recomputeAnalytics": true }
```

- **Kiểm tra:** DOC-22 §4.1 (trục **record**, giờ thật). `source = GTFS_STATIC` → 422 `unsupported-source`; vi phạm khoảng → 422 `replay-window-invalid` kèm `errors[]`. `recomputeAnalytics` mặc định `false`.
- **Ghi:** `INSERT ops.replay_request (kind = 'RAW_RANGE', …)`. Vi phạm `replay_request_one_raw_per_source` (SQLState `23505`, tên constraint) → 409 `replay-already-running` với `existingReplayId` (FR-12.3).
- **202:** như E-52, `Location: /api/v1/etl/replays/{id}`. UI poll E-52 mỗi 2 giây khi đang mở (ADR-0013).

### E-51 `GET /etl/replays` · `listReplays`

- **Quyền:** viewer. **Trục:** audit (`requested_at`).
- **Query:** `kind` (`RAW_RANGE` \| `DLQ_RECORD`), `status` (lặp được), `source`, `requestedBy`, `from`, `to` (mặc định 7 ngày, tối đa 31), `limit`, `cursor`. Thứ tự: `requestedAt` giảm, `id` giảm.
- **200:** `items` như E-52 nhưng không có `stats` và `progress`.
- **SQL:** `ops.replay_request` quét theo `requested_at`. Bảng nhỏ (vài nghìn dòng mỗi tháng, retention DOC-18) nên không cần thêm index.

### E-52 `GET /etl/replays/{id}` · `getReplay`

- **Quyền:** viewer.
- **200:**

```json
{
  "id": "0192f5a0-1b2c-7d3e-8f4a-5b6c7d8e9f0a",
  "kind": "RAW_RANGE",
  "source": "GTFS_RT_TRIP_UPDATE",
  "fromTs": "2026-09-28T00:00:00Z",
  "toTs": "2026-09-29T00:00:00Z",
  "recomputeAnalytics": true,
  "status": "RUNNING",
  "requestedBy": "user:operator",
  "requestedAt": "2026-09-29T20:39:58Z",
  "startedAt": "2026-09-29T20:40:02Z",
  "runId": "job:4127",
  "progress": { "step": "replayRecords", "readCount": 412000, "writeCount": 411050, "skipCount": 950, "updatedAt": "2026-09-29T20:47:11Z" }
}
```

  - `progress` chỉ có khi `RUNNING`, lấy từ step đang chạy trong `ops_job_step_v` (bộ đếm Spring Batch cập nhật sau mỗi commit chunk).
  - Khi kết thúc có `finishedAt`, `message`, `stats` (JSON của DOC-22 §4.6, đổi khóa sang camelCase).
  - `DLQ_RECORD` có `deadLetterId` thay cho `fromTs`/`toTs`.

### E-53 `GET /etl/replays/estimate` · `estimateRawReplay`

- **Mục đích:** ước lượng trước khi bấm "Start replay" (UC-10 bước 2). API không có quyền S3 (TB-2, DOC-27), nên ước lượng dựa trên nhật ký micro-batch thay vì liệt kê object.
- **Quyền:** operator. **Query:** `source`, `fromTs`, `toTs` (cùng kiểm tra như E-50, lỗi trả 400 thay vì 422 vì đây là GET).
- **Cách tính:** `estimatedMessages = sum(records_read)` của `etl_stream_batch` cùng nguồn có `started_at ∈ [fromTs, toTs + 5 phút)` (micro-batch xử lý record trong vài giây sau khi nó được ghi). `estimatedDurationSeconds = ceil(estimatedMessages / pti.api.replay-estimate.throughput)` (mặc định 3.000 message/giây, hiệu chỉnh theo EXP-04 ở P4).
- **200:**

```json
{
  "source": "GTFS_RT_TRIP_UPDATE",
  "fromTs": "2026-09-28T00:00:00Z",
  "toTs": "2026-09-29T00:00:00Z",
  "estimatedMessages": 1152000,
  "estimatedDurationSeconds": 384,
  "basis": "STREAM_BATCH_LOG",
  "coverage": 1.0,
  "warnings": []
}
```

  - `coverage` = tỷ lệ số phút trong khoảng có ít nhất một micro-batch. `< 0,9` thì thêm cảnh báo `Some minutes in this range have no processing history; the estimate may be low.`
  - Khoảng cũ hơn thời hạn lưu `etl_stream_batch` (DOC-18) thì `basis = "UNAVAILABLE"`, không có hai trường ước lượng, và có cảnh báo `No processing history for this range.` Người vận hành vẫn replay được.
  - Có replay `RAW_RANGE` đang chờ hoặc chạy cho nguồn đó thì thêm cảnh báo `A replay for this source is already running.` (E-50 sẽ trả 409).

## 9. Cờ vận hành

### E-55 `GET /etl/flags` · `listRuntimeFlags`

- **Quyền:** viewer. Danh sách nhỏ (7 cờ, DOC-15 §3), sắp theo `key`.
- **200:**

```json
{
  "items": [
    {
      "key": "etl.consumer.gtfs-rt.paused",
      "value": false,
      "description": "Pause the GTFS-realtime listeners (vehicle positions and trip updates).",
      "updatedBy": "migration",
      "updatedAt": "2026-09-27T08:30:00Z"
    }
  ]
}
```

### E-56 `GET /etl/flags/{key}` · `getRuntimeFlag`

- Một phần tử như E-55. 404 khi không có.

### E-57 `PUT /etl/flags/{key}` · `updateRuntimeFlag`

- **Quyền:** operator. **Body:** `{"value": true}`.
- **Kiểm tra:** cờ phải tồn tại (404; API không tạo cờ mới, cờ mới đến từ migration); kiểu JSON của `value` phải trùng kiểu hiện tại (boolean với boolean, number với number, string với string) → ngược lại 422 `invalid-flag-value`.
- **Ghi:** `UPDATE ops.runtime_flag SET value = :value::jsonb, updated_by = :actor, updated_at = now() WHERE key = :key`. Cùng giá trị → 200 không ghi. Log `INFO` `runtime flag changed` với `key`, `from`, `to`, `actor`.
- **200:** cờ sau khi đổi. Hiệu lực trong ≤ 5 giây (chu kỳ `RuntimeFlagRefresher`, DOC-19 §2.1; FR-15.1).

## 10. Hệ thống

### E-60 `GET /system/freshness` · `getFreshness`

- **Mục đích:** độ tươi dữ liệu cho stale banner (FR-15.2, FR-11.6), đồng hồ nghiệp vụ và feed ACTIVE cho frontend.
- **Quyền:** anonymous.
- **Freshness probe:** bean `FreshnessProbe` chạy mỗi `pti.observability.freshness-probe.interval` (15 s), đọc bằng datasource `reader`. Kết quả dùng chung cho endpoint này, header `X-Data-As-Of` (DOC-31 §7.3) và gauge `pti_source_last_event_age_seconds` (DOC-28, DR-71). Endpoint chỉ đọc kết quả probe, không truy vấn DB.
- **200:**

```json
{
  "businessNow": "2026-09-29T21:19:35Z",
  "clockOffset": "PT0S",
  "checkedAt": "2026-09-29T21:19:30Z",
  "stale": false,
  "activeFeed": {
    "feedVersionId": 3,
    "publisherFeedVersion": "2026-08-23",
    "timezone": "America/Chicago",
    "validFrom": "2026-08-23",
    "validTo": "2026-12-12",
    "activatedAt": "2026-09-27T08:34:40Z"
  },
  "sources": [
    { "source": "GTFS_RT_VEHICLE_POSITION", "lastEventAt": "2026-09-29T21:19:30Z", "ageSeconds": 5, "staleAfterSeconds": 120, "stale": false },
    { "source": "GTFS_RT_TRIP_UPDATE", "lastEventAt": "2026-09-29T21:19:02Z", "ageSeconds": 33, "staleAfterSeconds": 120, "stale": false },
    { "source": "TICKETING_SALES", "lastEventAt": "2026-09-29T21:18:51Z", "ageSeconds": 44, "staleAfterSeconds": 900, "stale": false }
  ],
  "insights": {
    "etaComputedAt": "2026-09-29T21:05:12Z",
    "otpComputedAt": "2026-09-29T08:00:41Z"
  }
}
```

  - `ageSeconds = businessNow − lastEventAt` (làm tròn xuống). `stale = ageSeconds > staleAfterSeconds`; nguồn chưa có dữ liệu thì `lastEventAt` vắng mặt và `stale = true`.
  - Ngưỡng: `pti.api.freshness.stale-after.gtfs-rt` = 120 s (DR-38, khớp alert `GtfsRtFeedStale`), `.ticketing` = 900 s (ticketing có giờ thấp điểm gần như không có giao dịch).
  - `stale` ở gốc = một trong hai nguồn GTFS-rt stale. Frontend hiện stale banner theo trường này (DOC-35); ticketing stale chỉ hiện trên màn hình ticketing.
  - Probe lỗi (DB không đọc được): giữ kết quả cũ, thêm `probeError: true`; kết quả cũ hơn 60 giây thì endpoint trả 503.
  - Chưa có feed ACTIVE thì không có `activeFeed`, và endpoint vẫn trả 200.
- **SQL** (`system/freshness.sql`, một round-trip):

```sql
SELECT
  (SELECT max(event_timestamp) FROM dw.vehicle_position_latest)                  AS vp_last,
  (SELECT max(max_event_ts) FROM ops.etl_stream_batch
    WHERE source = 'GTFS_RT_TRIP_UPDATE' AND started_at >= now() - interval '1 hour') AS tu_last,
  (SELECT max(created_at) FROM dw.fact_ticket_sales)                               AS sales_last,
  (SELECT max(computed_at) FROM insight.insight_eta_prediction)                    AS eta_computed_at,
  (SELECT max(computed_at) FROM insight.insight_otp_scorecard
    WHERE service_date >= current_date - 7)                                         AS otp_computed_at
```

  - VehiclePosition: `vehicle_position_latest` có ≈ 1.100 dòng.
  - TripUpdate: `max_event_ts` của micro-batch tương đương `max(event_timestamp)` của fact nhưng không phải quét partition hôm nay (≈ 345.000 dòng); `etl_stream_batch_started_idx`. Không có micro-batch trong 1 giờ → NULL → stale.
  - Vé: `max(created_at)` theo DOC-28 §3.5, dùng `fact_ticket_sales_created_idx` (V7) theo từng partition.
  - `insight_eta_prediction` ≈ 130 tuyến × 150 trạm × 168 khung ≈ 3,3 triệu dòng; `max(computed_at)` quét toàn bảng (≈ 300 ms). Vì vậy probe đọc giá trị này mỗi **5 phút** (`pti.api.freshness.insight-interval`), không phải mỗi 15 giây.
- **Cache-Control:** `no-cache`. **SSE:** `heartbeat` mang `businessNow` (DOC-33).

### E-61 `GET /me` · `getCurrentUser`

- **Quyền:** anonymous (không có token → `authenticated: false`).
- **200:**

```json
{ "authenticated": true, "username": "operator", "displayName": "Demo Operator", "roles": ["operator", "viewer"], "tokenExpiresAt": "2026-09-29T21:24:35Z" }
```

  Anonymous: `{"authenticated": false, "roles": []}`. `roles` là các role hiệu lực sau role hierarchy (operator bao gồm viewer), sắp theo tên. Frontend dùng để chọn menu (P5-04), không thay cho kiểm tra quyền ở server.

## 11. Real-time, webhook, simulator

### E-70 `GET /stream` · `openEventStream`

- `text/event-stream`. Query `channels` (tập con của `vehicles`, `alerts`, `jobs`, `dlq`; mặc định `vehicles,alerts`), `routeId` (≤ 20, lọc kênh `vehicles` và sự kiện có `routeId` trên kênh `alerts`). Header `Last-Event-ID`.
- Kênh `jobs`, `dlq` cần viewer: thiếu token → 401, token không đủ role → 403, **trước** khi stream mở. Kênh `alerts` với anonymous chỉ nhận sự kiện `audience = PUBLIC`.
- Giới hạn kết nối: DOC-31 §11. Toàn bộ hành vi ở DOC-26, sự kiện ở DOC-33.

### E-80 `POST /internal/alerts/alertmanager` · (không có trong OpenAPI công khai)

- **Truy cập:** chỉ trong mạng nội bộ; nginx của frontend **không** proxy `/internal/` (DOC-27). Header `Authorization: Bearer <ALERTMANAGER_WEBHOOK_TOKEN>`, so sánh hằng thời gian (`MessageDigest.isEqual`). Sai → 401. Không chịu rate limit.
- **Body:** payload webhook của Alertmanager (version `4`): `status`, `alerts[]` với `status`, `labels`, `annotations`, `startsAt`, `endsAt`, `fingerprint`.
- **Xử lý từng phần tử `alerts[]`** (một transaction cho cả request):

| Điều kiện | Hành động |
| --- | --- |
| `status = firing` | `INSERT … ON CONFLICT ON CONSTRAINT alert_event_dedup_uk DO NOTHING RETURNING *`, với `dedup_key = am:<fingerprint>:<startsAt>` |
| `status = resolved` | `UPDATE ops.alert_event SET resolved_at = now() WHERE dedup_key = :dedupKey AND resolved_at IS NULL RETURNING *` |

  Ánh xạ cột:

| Cột | Giá trị |
| --- | --- |
| `id` | `InsightIds.alert(dedup_key)` (UUIDv5, như DOC-23 §10.1) |
| `type` | `alertname = GtfsRtFeedStale` → `FEED_STALE`; `alertname ∈ {DlqSevereRecords, DlqUpstreamErrorBurst}` (record DLQ severity 2 và luật chặn cuối FR-09.8, DOC-24 §10) → `DLQ_SEVERE`; còn lại (kể cả `DlqNeedsAttention`) → `INFRA` |
| `severity` | label `severity`: `critical` → 2, `warning` → 1, `info` → 0 |
| `audience` | `ENGINEERING` |
| `route_id` | label `route_id` nếu có, ngược lại NULL |
| `title` | annotation `summary`, cắt 200 ký tự; thiếu thì `alertname` |
| `body` | `{"alertname", "labels", "annotations", "startsAt", "generatorURL"}` |

- **204** khi xử lý xong (kể cả khi mọi phần tử đều trùng). Body sai dạng → 400; Alertmanager sẽ gửi lại theo cơ chế của nó.
- **Sau commit:** mỗi dòng INSERT trả về → `alert.created`; mỗi dòng UPDATE → `alert.updated` (audience `ENGINEERING`).
- **Metric:** `pti_alert_webhook_total{outcome = created | duplicate | resolved | unknown_resolved | rejected}`.

### E-90 `/sim/**` · proxy điều khiển simulator

- **Chỉ có ở profile `demo`** (`@Profile("demo")`; profile khác trả 404 vì route không tồn tại). **Quyền:** operator.
- Ánh xạ 1-1: `/api/v1/sim/<path>` → `http://source-simulator:8080/sim/<path>` (`pti.api.sim.base-url`), giữ method, query, body và `Content-Type`. Danh sách endpoint của simulator ở DOC-25 §8; proxy chỉ chấp nhận các path ở đó (danh sách cho phép, khác → 404).
- API thêm header `X-Requested-By: user:<username>` (simulator ghi vào `requestedBy`) và `traceparent`. Header `Authorization` **không** được chuyển tiếp.
- Response của simulator (kể cả Problem Details với slug riêng của simulator như `unknown-scenario`, `scenario-conflict`) được trả nguyên văn; `Location` được viết lại thành `/api/v1/sim/...`.
- Timeout kết nối 1 s, đọc 5 s. Simulator không trả lời → 502 `simulator-unavailable`.
- Không có trong `openapi.json` công khai; có trong nhóm OpenAPI `demo` riêng (springdoc `GroupedOpenApi`).

## 12. Thay đổi kéo theo ở tài liệu khác

| Tài liệu | Thay đổi |
| --- | --- |
| DOC-15 | V5_2: view `ops.ops_job_step_v`, `ops.ops_job_execution_param_v`; index `alert_event_created_idx`. V7: ba index ở §4. Đổi tên endpoint ở §5 |
| DOC-17 | `GRANT SELECT` hai view mới cho `api_reader` và `experiment_runner`; test quyền |
| DOC-30 §3.2 | Slug mới: `job-not-allowed` (422), `job-not-running` (409), `invalid-flag-value` (422), `simulator-unavailable` (502) |
| DOC-09 §6 | `api` là producer của `pti.events.ui`; kiểu `alert.retracted` (DOC-33) |
| DOC-03 | FR-10.3: ngoại lệ snapshot `/vehicles/live` |
| DOC-04 | UC-04 (giá trị feedback), UC-07 (`kind=BATCH_JOB`), UC-10 (nguồn, ước lượng), UC-13 (`POST /etl/jobs`) |
| DOC-14 §… | `/ops/batches/{id}` → `/etl/batches/{batchId}` |
| ADR-0013 | `/ops/replays/{id}` → `/etl/replays/{id}` |

## 13. Test bắt buộc

Ngoài bộ test chung của DOC-31 §16 và ma trận quyền của DOC-27 §7 (mọi endpoint × 3 role, FR-10.2):

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| EP-01 | Contract: `openapi.json` sinh ra khớp bản đã commit; mọi `operationId` ở §2 có mặt, mỗi operation có ví dụ response | Pass (FR-10.1) |
| EP-02 | E-02 với tuyến có 3 shape ở chiều 0 (fixture) | Chọn shape nhiều chuyến nhất; `stops` theo chuyến đại diện; `label`/`headsign` là mode |
| EP-03 | E-02 với feed không có `shapes.txt` | `geometrySource = "STOPS"`, LineString qua tọa độ trạm |
| EP-04 | E-03 `bucket=hour-of-week` trên fixture 14 ngày | Giá trị khớp tính tay; `dayOfWeek` theo ISO và giờ địa phương Chicago (kể cả ngày đổi giờ DST) |
| EP-05 | E-05 với xe có `event_timestamp` cũ hơn 5 phút | Không có trong kết quả |
| EP-06 | E-05 anonymous và viewer khi có episode bunching mở | Anonymous không có `bunching`; viewer có ở cả leader và follower |
| EP-07 | E-06 `q` = mã trạm chính xác, `q` có `%` | Mã trùng lên đầu; `%` được hiểu là ký tự thường |
| EP-08 | E-06 `bbox` rộng 1 độ × 1 độ | 400 |
| EP-09 | E-07 khi alert gián đoạn của tuyến đi qua trạm có audience `PUBLIC`, rồi bị đổi sang `ENGINEERING` | Anonymous thấy rồi không thấy; viewer luôn thấy |
| EP-10 | E-08 theo bảng test AN-E của DOC-23 §18.4 | Pass |
| EP-11 | E-12/E-13 anonymous với episode không công khai | Không có trong danh sách; chi tiết trả 404 |
| EP-12 | E-12 anonymous | Không có trường AI, baseline, z-score, `audience` |
| EP-13 | E-14 hai ngày có ngưỡng khác nhau | `mixedTolerances: true`; phần trăm tính từ tổng bộ đếm |
| EP-14 | E-18 `accepted` rồi `ignored` | Giá trị sau cùng là `ignored`, `feedbackBy` là người sau |
| EP-15 | E-20 anonymous với `audience=OPERATIONS` | 403 |
| EP-16 | E-21 hai lần | Lần hai 200, `acknowledgedBy` giữ người đầu; chỉ một sự kiện `alert.updated` |
| EP-17 | E-30 khoảng 25 giờ; `kind=STREAM` | 400; chỉ dòng `stream:` |
| EP-18 | E-32 với `runId` sai dạng, `job:999999` | 404 cả hai |
| EP-19 | E-33 `jobName = DedupRegistryCleanupJob`; `OtpScorecardJob` với ngày hôm nay | 422 `job-not-allowed`; 400 |
| EP-20 | E-33 hợp lệ, etl-batch chạy thật (Testcontainers) | `job_request` DONE; E-34 có `runId` |
| EP-21 | E-35 với execution `COMPLETED`; với run `stream:` | 409 `job-not-restartable` cả hai |
| EP-22 | E-37 với `batch_id` của micro-batch và của step replay | `origin` đúng; step replay có `replayRequestId` |
| EP-23 | E-43 theo bảng R-03 của DOC-22 §11 | Mã lỗi đúng; `edited_payload` không đổi khi lỗi |
| EP-24 | E-44 trên dòng `REPLAYED` | 409 `dlq-invalid-state`, `currentStatus = REPLAYED` |
| EP-25 | E-44 song song hai request (khác key) trên cùng dòng `MANUAL` | Một 202, một 409; một `replay_request` |
| EP-26 | E-45 trên `PENDING_CONFIRM` | Log có `CONFIRMED` rồi `REPLAY_REQUESTED`; FR-09.3 E2E chuyển `REPLAYED` |
| EP-27 | E-46 thiếu `reason` | 400 |
| EP-28 | E-50 hai lần cùng nguồn (khác key) khi lần đầu còn `PENDING` | 409 `replay-already-running`, `existingReplayId` = id lần đầu (FR-12.3) |
| EP-29 | E-50 `toTs` trong 10 phút gần nhất; khoảng 8 ngày; `GTFS_STATIC` | 422 tương ứng |
| EP-30 | E-53 với khoảng có đủ lịch sử và khoảng không có lịch sử | `coverage = 1.0`; `basis = UNAVAILABLE` |
| EP-31 | E-57 `{"value": "yes"}` cho cờ boolean; key không tồn tại | 422 `invalid-flag-value`; 404 |
| EP-32 | E-57 bật `etl.consumer.gtfs-rt.paused` | Listener pause trong ≤ 5 giây (FR-15.1, integration với etl-stream) |
| EP-33 | E-60 khi dừng simulator 3 phút (`PTI_CLOCK_OFFSET` bất kỳ) | Hai nguồn GTFS-rt `stale = true`, `stale` gốc `true`; gauge cùng giá trị |
| EP-34 | E-61 không token, token viewer, token operator | `authenticated`/`roles` đúng |
| EP-35 | E-80 token sai; firing hai lần cùng fingerprint/startsAt; resolved | 401; một dòng; `resolved_at` được đặt, `alert.updated` phát một lần |
| EP-36 | E-80 `alertname = GtfsRtFeedStale` | `type = FEED_STALE`, `audience = ENGINEERING`, `severity = 2` |
| EP-37 | E-90 ở profile không phải `demo`; viewer ở profile `demo`; path không có trong danh sách | 404; 403; 404 |
| EP-38 | k6 tải nền (DOC-44): 50 người dùng mô phỏng trên E-01…E-08, E-12, E-20, E-60 | p95 từng endpoint ≤ cột p95 ở §2 (NFR-10) |
| EP-39 | E-20 và SSE `alert.created` với mỗi `type` (fixture 6 alert, một `INFRA` có và một không có `runbook_url`) | `link` đúng bảng ở E-20; cùng giá trị ở REST và SSE; alert resolved có `resolvedAt` |

## 14. Câu hỏi còn mở

Không có.
