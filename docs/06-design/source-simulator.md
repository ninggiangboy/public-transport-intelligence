# Source simulator

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-25
> Phụ thuộc: [DR](../00-decision-register.md) (DR-01, 03, 04, 05, 08, 28, 59, 60, 64, 65, 67, 68, 86), [DOC-09](../03-architecture/messaging-contracts.md), [DOC-13](../05-data/source-data.md), [DOC-17](../05-data/db-roles-and-grants.md), [DOC-29](configuration-reference.md)
> Người dùng chính: P1-08…P1-11, P1-14 (phần cơ bản), P3-01 (kịch bản), experiment runner (DOC-45), màn Demo control (DOC-36)

## 1. Mục đích

Simulator là **ranh giới duy nhất không "như thật"** của hệ thống (SDD §5.2). Nó đóng ba vai:

1. **Hệ thống AVL của đơn vị vận hành:** phát GTFS-realtime (VehiclePosition, TripUpdate) vào Kafka, dựa trên lịch thật của feed Metro Transit.
2. **Ứng dụng bán vé:** ghi giao dịch vào `ticketing_source`, để Debezium bắt thay đổi đúng như CDC thật.
3. **Nguồn sự thật cho thực nghiệm:** ghi ledger các message đã được Kafka xác nhận (DR-28), và cung cấp API điều khiển kịch bản cho demo và cho `pti-exp`.

Mọi thứ phía sau Kafka và `ticketing_source` không biết simulator tồn tại.

## 2. Phạm vi

| Trong phạm vi | Ngoài phạm vi |
| --- | --- |
| Đọc thẳng file GTFS zip (không phụ thuộc warehouse, master plan §1.3) | Đọc feed GTFS-rt thật |
| Mô hình chuyển động theo `stop_times` + `shapes`, mô hình trễ có tương quan theo tuyến và giờ | Mô phỏng giao thông, đèn tín hiệu, hành khách lên xuống |
| Gán xe cho block (DOC-13 §4) | Deadhead (xe chạy rỗng về bãi) |
| TicketingSeeder với tốc độ theo giờ, hoàn vé, void, xóa | Thanh toán, thẻ, tài khoản khách hàng |
| Ledger, API kịch bản, điều chỉnh tốc độ phát | Giao diện riêng (UI nằm trong ops console, DR-49) |

**Phần cơ bản (gate P1):** §3–§6, §7 (trừ kịch bản), §8, §10–§13. **Phần kịch bản (gate P3):** §7.

## 3. Đồng hồ và ngày phục vụ

### 3.1 Đồng hồ nghiệp vụ (DR-67)

Feed dùng giờ `America/Chicago`, lệch 12 giờ (CDT) hoặc 13 giờ (CST) so với Việt Nam. Nếu chạy theo giờ thật, buổi demo hay thực nghiệm lúc 14:00 ở Hà Nội sẽ rơi vào 02:00 ở Minneapolis, lúc hầu như không có xe. Vì vậy dự án có một **đồng hồ nghiệp vụ** dùng chung:

```
businessNow = systemClock.instant() + pti.clock.offset
```

- `pti.clock.offset` (env `PTI_CLOCK_OFFSET`, Duration, mặc định `0s`, làm tròn tới phút, trong khoảng ±24 giờ) được đặt **cùng một giá trị** cho simulator, etl (`stream`, `batch`) và api. Compose lấy từ một biến trong `.env` (DOC-39). `make clock-offset AT=16:30` tính offset sao cho giờ nghiệp vụ lúc đó là 16:30 ở Chicago rồi ghi vào `.env` (DOC-38).
- Mỗi app có một bean `java.time.Clock` tên `businessClock` (lớp `BusinessClock` trong `common`). **Mọi logic nghiệp vụ lấy "bây giờ" từ bean này**, không gọi `Instant.now()` và không dùng `now()` của Postgres để so với cột event time. SQL cần "bây giờ" thì nhận tham số `:now`.
- Các mốc sau vẫn theo **giờ thật**, vì chúng do hạ tầng đặt: timestamp của record Kafka (CreateTime), `ingested_at`/`updated_at` (audit), `__source_ts_ms` của Debezium, thời điểm trong log và trace.
- Hệ quả:
  - Độ trễ đầu-cuối (DR-57) đo bằng CreateTime so với giờ thật nên không bị ảnh hưởng.
  - Replay theo khoảng thời gian: API đổi khoảng giờ nghiệp vụ sang giờ record bằng cách trừ offset hiện tại (DOC-22). Vì vậy **không đổi offset giữa lúc ghi dữ liệu và lúc replay khoảng đó**; EXP-04 chạy với một offset cố định.
  - Frontend không dùng đồng hồ của trình duyệt để tính "cách đây bao lâu" mà dùng giờ server gửi kèm (DOC-26, DOC-34).
- Offset được ghi vào `config.json` của mỗi lần chạy thực nghiệm (DOC-45).

### 3.2 Ánh xạ ngày (DR-08)

Mọi giá trị ngày trong message là **ngày thật theo đồng hồ nghiệp vụ**. Ánh xạ chỉ dùng để chọn chuyến chạy trong feed.

`ServiceDateMapper.feedDateFor(LocalDate realDate)`:

- `pti.sim.service-date-mapping=fixed:<YYYY-MM-DD>`: luôn trả ngày đó (dùng trong test và khi cần tái lập tuyệt đối).
- `auto` (mặc định):
  1. Nếu `realDate` nằm trong `[valid_from, valid_to]` của feed thì trả chính `realDate`.
  2. Ngược lại: lấy mọi ngày trong khoảng hiệu lực có cùng thứ trong tuần với `realDate`. Nhóm các ngày này theo **tập `service_id` hoạt động** (tính cả `calendar_dates`). Chọn nhóm có nhiều ngày nhất (tức lịch "bình thường" của thứ đó, loại ngày lễ), hòa thì chọn nhóm chứa ngày sớm hơn. Trả ngày sớm nhất của nhóm.
- Kết quả được cache theo `realDate` và log INFO một lần: `Service date mapping: real=2026-12-15 feed=2026-09-29 (TUESDAY, 6 service_ids)`.
- Với feed đã chốt: thứ Ba ánh xạ về 2026-09-29, thứ Bảy về 2026-10-03, Chủ nhật về 2026-10-04 (khớp bảng thống kê DOC-13 §2.2).

### 3.3 Ngày phục vụ đang chạy

Tại một thời điểm `t`, gọi `D` là ngày theo giờ Chicago của `t`. Chuyến có giờ GTFS tới 26:34 (DOC-13 §2.2), nên simulator xét **hai ngày phục vụ** `D − 1` và `D`. Với mỗi ngày phục vụ thật `S`:

```
feedDate    = mapper.feedDateFor(S)
secondsOfS  = (t − GtfsTime.toInstant(S, 0, zone)).getSeconds()   // "noon minus 12h" (DOC-13 §3)
```

Chuyến thuộc `S` đang chạy nếu `start ≤ secondsOfS ≤ end` của block chứa nó (§4.2). Message phát ra có `start_date = S`.

## 4. Nạp feed và chỉ mục lịch

### 4.1 Nạp feed

- File từ `pti.sim.feed.location` (Spring `Resource`, mặc định `file:/data/gtfs/metrotransit-mn-20260926.zip`, compose mount `sample-data/gtfs/` chỉ đọc). Nếu `pti.sim.feed.sha256` có giá trị thì so SHA-256; sai thì app dừng khởi động.
- Parser dùng chung trong `common` (`GtfsZipReader`), cùng quy tắc ánh xạ cột với `GtfsStaticLoadJob` (DOC-13 §2.4).
- Lưu trong bộ nhớ dạng mảng nguyên thủy theo từng chuyến. Ước lượng: 873 nghìn `stop_times` × (2 `int` giờ + 1 `float` khoảng cách + 1 `int` chỉ số trạm + 1 `short` sequence + cờ timepoint) ≈ 16 MB; 320 nghìn điểm shape × (2 `double` + 1 `float`) ≈ 7 MB. Tổng dưới 64 MB heap.
- Readiness là `DOWN` cho tới khi nạp xong (khoảng 5–10 giây).

### 4.2 Chỉ mục theo ngày

`ScheduleIndex.forFeedDate(LocalDate feedDate)` trả về danh sách block của ngày đó, sắp theo `(start, block_id)`. Mỗi block gồm các chuyến sắp theo giờ xuất phát, `start` = giờ xuất phát sớm nhất, `end` = giờ đến muộn nhất (giây GTFS). Chỉ mục được dựng cho `S − 1`, `S`, `S + 1` và dựng lại mỗi khi sang ngày mới theo giờ Chicago.

### 4.3 Gán xe

Theo đúng DOC-13 §4. Tính chẵn/lẻ dùng **ngày phục vụ thật** `S.toEpochDay()`, không dùng ngày feed, vì hai ngày thật liên tiếp có thể ánh xạ về cùng một ngày feed.

### 4.4 Xe "đang phục vụ"

Một xe phát VehiclePosition từ `start` tới `end` của block. Giữa hai chuyến liên tiếp:

- Nếu thời gian chờ ≤ `pti.sim.vehicle.max-layover-emit` (mặc định `30m`): xe đứng ở trạm đầu của chuyến kế tiếp, `trip_id` là chuyến kế tiếp, `current_status = STOPPED_AT` (DOC-09 §3.1).
- Nếu dài hơn: xe ngừng phát (coi như về bãi) và phát lại khi còn `pti.sim.vehicle.max-layover-emit` trước chuyến kế tiếp.

## 5. Mô hình chuyển động và mô hình trễ

### 5.1 Ý tưởng

Mỗi chuyến chạy theo **lịch cộng độ trễ**. Độ trễ thay đổi theo từng đoạn giữa hai trạm, được lấy mẫu **tất định** từ seed, nên chạy lại với cùng seed và cùng thời điểm cho cùng quỹ đạo. Vị trí trên đoạn được nội suy tuyến tính theo thời gian, rồi theo `shape_dist_traveled` trên polyline của shape.

### 5.2 Trạng thái một chuyến

```java
final class TripRun {
  final TripSchedule schedule;      // arrays: stopSeq, stopIds, arr, dep, dist, timepoint
  final LocalDate serviceDate;      // real service date S
  final String vehicleId;
  int segment;                      // index i: vehicle is between stop i and stop i+1, or dwelling at i+1
  Instant actualDep;                // actual departure from stop i
  Instant actualArr;                // actual arrival at stop i+1
  Instant actualNextDep;            // actual departure from stop i+1
  double arrDelay;                  // D_{i+1}: delay at arrival of stop i+1 (seconds)
  int lastReportedIndex;            // last stop included as observed in a TripUpdate
}
```

`TripSchedule` là bất biến và dùng chung cho mọi `TripRun` của cùng `trip_id`.

### 5.3 Lấy mẫu độ trễ cho một đoạn

Khi xe rời trạm `i` (bắt đầu đoạn `i → i+1`):

```
rng     = SplittableRandom(hash(seed, S, trip_id, stopSeq[i]))
period  = PEAK nếu giờ Chicago của schedTime(dep[i]) thuộc 06:00–09:00 hoặc 15:00–18:30 ngày thường, ngược lại OFF_PEAK
c       = routeFactor(S, route_id, direction_id, bucket5m(actualDep))        // §5.4
eps     = normal(rng, drift[period] × (1 + c), sd[period])                   // giây
eps    += Σ overlay.segmentDelta(run, i, actualDep)                          // kịch bản, §7
D_next  = clamp(D_i + eps, earlyLimit, lateLimit)
travelSched = arr[i+1] − dep[i]
travelActual = max(travelSched + (D_next − D_i), minSpeedRatio × travelSched, 1 s)
actualArr = actualDep + travelActual
dwellExtra = exponential(rng, meanDwell[period])     // chỉ khi pickup_type hoặc drop_off_type ≠ 1
depDelay   = D_next + dwellExtra
nếu timepoint[i+1] và depDelay < 0: depDelay = 0     // không rời timepoint sớm (giữ giờ)
actualNextDep = schedInstant(dep[i+1]) + depDelay, và ≥ actualArr
```

- `D_0` (trễ khi rời trạm đầu) lấy từ `normal(initialMean[period], initialSd)`, cắt ở `[0, lateLimit]` (xe không rời bến sớm).
- `D_next` được clamp trước khi tính `travelActual`; `travelActual` không nhỏ hơn `minSpeedRatio` (mặc định 0,5) lần thời gian theo lịch, tức xe không chạy nhanh hơn gấp đôi lịch.
- Nếu hai trạm liên tiếp có cùng `arr` (thường gặp ở GTFS khi giờ làm tròn tới phút), `travelSched = 0`; khi đó dùng `max(eps, 15 s)` làm thời gian di chuyển.

Tham số mặc định (DOC-29 §3.2):

| Tham số | PEAK | OFF_PEAK | Key |
| --- | --- | --- | --- |
| `initialMean` / `initialSd` | 60 s / 45 s | 20 s / 30 s | `pti.sim.delay.initial-mean.*`, `initial-sd.*` |
| `drift` mỗi đoạn | −8 s | −5 s | `pti.sim.delay.drift.*` |
| `sd` mỗi đoạn | 12 s | 8 s | `pti.sim.delay.segment-sd.*` |
| `meanDwell` thêm | 8 s | 5 s | `pti.sim.delay.dwell-mean.*` |
| `earlyLimit` / `lateLimit` | −120 s / +1.200 s | như PEAK | `pti.sim.delay.early-limit`, `late-limit` |
| `minSpeedRatio` | 0,5 | 0,5 | `pti.sim.delay.min-speed-ratio` |

**Mục tiêu hiệu chỉnh** (test P1-09, T-07): trên một ngày thường mô phỏng, tỷ lệ lần đến có `−300 ≤ delay ≤ 300` nằm trong 70–90% (tương đương OTP của một hệ thống bus đô thị). Nếu lệch thì chỉnh `drift`, không đổi cấu trúc mô hình.

Kết quả hiệu chỉnh ở P1-09 (feed đã chốt, ngày 2026-09-29, seed 42, 337 nghìn lần đến): với `drift` ban đầu +4 s / +1 s, chỉ 45% lần đến đúng giờ và trễ trung bình 419 s. Nguyên nhân là `dwellExtra` (8 s / 5 s) cộng dồn ở **mọi** trạm có đón trả khách: tuyến 40 trạm tích thêm khoảng 320 s, còn timepoint chỉ chặn phần sớm chứ không kéo phần trễ về. Vì vậy `drift` phải âm: xe bù lại thời gian trên đường chạy và mất thời gian ở trạm. Kết quả quét:

| `drift` PEAK / OFF_PEAK | Đúng giờ | Trễ trung bình |
| --- | --- | --- |
| +4 s / +1 s | 45,1% | 419 s |
| 0 / 0 | 52,8% | 348 s |
| −4 s / −4 s | 70,3% | 236 s |
| **−8 s / −5 s (chốt)** | **80,0%** | **181 s** |
| −10 s / −6 s | 84,8% | 152 s |

Giá trị chốt nằm giữa khoảng mục tiêu và cho trễ trung bình khoảng 3 phút. Vì `drift` âm nên hệ số tuyến `c` (§5.4) nhân vào một giá trị âm: dấu của tác động đảo lại, nhưng các xe cùng tuyến cùng chiều vẫn trễ lên hoặc xuống cùng nhau, đúng mục đích của §5.4. Biên độ nhỏ, khoảng ±2 s mỗi đoạn.

### 5.4 Tương quan theo tuyến

`routeFactor(S, route, dir, bucket)` là một chuỗi AR(1) theo bucket 5 phút:

```
c_0 = 0
c_k = φ · c_{k−1} + normal(hash(seed, S, route, dir, k), σ_c)      φ = 0.8, σ_c = 0.15
```

Giá trị được tính tất định từ đầu ngày và cache theo `(S, route, dir)`. Nhờ đó các xe cùng tuyến cùng chiều trễ lên hoặc xuống cùng nhau, đúng như tắc đường thật. Biến động này nhỏ hơn nhiều so với kịch bản `disruption` (§7.3), nên baseline EWMA của DR-31 không báo nhầm trong điều kiện thường. P4 kiểm tra điều này: chạy 4 giờ không có kịch bản thì số episode disruption mở là 0 hoặc rất ít (ghi số liệu vào DOC-23).

### 5.5 Vị trí tại thời điểm `t`

```
nếu t < actualArr:                                     // đang chạy trên đoạn i → i+1
  f = (t − actualDep) / (actualArr − actualDep)
  d = dist[i] + f · (dist[i+1] − dist[i])
  (lat, lon, bearing) = shape.pointAt(d)               // tìm nhị phân trên khoảng cách tích lũy
  speed = (dist[i+1] − dist[i]) / (actualArr − actualDep)
  status = (actualArr − t ≤ 30 s hoặc dist[i+1] − d ≤ 200 m) ? INCOMING_AT : IN_TRANSIT_TO
  stop = i+1
ngược lại nếu t < actualNextDep:                        // đang đỗ ở trạm i+1
  (lat, lon) = shape.pointAt(dist[i+1]); bearing = hướng của đoạn kế; speed = 0
  status = STOPPED_AT; stop = i+1
ngược lại:
  chuyển sang đoạn i+1 (lấy mẫu §5.3), lặp lại
```

- `shape.pointAt` dùng `shape_dist_traveled` của `shapes.txt` (feed có đủ 100%). Nếu feed thiếu, dự phòng bằng cách chiếu tọa độ trạm lên polyline khi nạp feed; code phải có nhánh này và test cho nó, dù feed hiện tại không cần.
- Nhiễu GPS: cộng nhiễu chuẩn `σ = pti.sim.gps-noise` (mặc định `5` mét) vào vị trí, seed theo `(vehicle_id, event_timestamp)`. Tốc độ cộng nhiễu ±5%. Bearing làm tròn 1 độ.
- Khoảng cách trong feed Metro Transit tính bằng mét (kiểm tra ở P1-08 bằng cách so với khoảng cách haversine giữa hai trạm; nếu là km hoặc dặm thì thêm hệ số trong `GtfsZipReader`).

### 5.6 `occupancy_status` (schema v2)

Chỉ có trong message v2 (DR-59). Lấy mẫu tất định theo `(vehicle_id, trip_id, stopSeq)`: PEAK có xác suất `MANY_SEATS_AVAILABLE` 30%, `FEW_SEATS_AVAILABLE` 40%, `STANDING_ROOM_ONLY` 25%, `CRUSHED_STANDING_ROOM_ONLY` 5%; OFF_PEAK lần lượt 70%, 25%, 5%, 0%. Trạng thái giữ nguyên trong một đoạn.

## 6. Sinh message GTFS-realtime

### 6.1 Lịch phát

| Loại | Chu kỳ gốc | Kích hoạt thêm | Khóa lịch |
| --- | --- | --- | --- |
| VehiclePosition | `pti.sim.vehicle-position.interval` = 5 s | — | Mỗi xe có pha riêng `hash(vehicle_id) mod interval`, để 606 xe rải đều trong 5 giây |
| TripUpdate | `pti.sim.trip-update.interval` = 30 s | Khi xe tới một trạm (FR-13.1) | Mỗi chuyến có pha `hash(trip_id) mod interval`. Nếu TripUpdate theo chu kỳ rơi vào trong 5 giây sau một TripUpdate do tới trạm thì bỏ lần theo chu kỳ đó |

Vòng lặp phát chạy mỗi `pti.sim.tick` (mặc định `200ms`) trên một thread riêng (`sim-emitter`). Ở mỗi tick, các lượt phát có mốc ≤ `businessNow` được thực hiện. **`event_timestamp` của VehiclePosition là mốc của lượt phát** (pha cộng bội số chu kỳ), không phải lúc thread chạy tới. Nhờ vậy business key `(vehicle_id, event_timestamp)` rời rạc và tất định.

Chu kỳ thực tế bằng chu kỳ gốc chia cho `rateMultiplier.gtfsRt` (§6.5, DR-68).

### 6.2 VehiclePosition

Theo DOC-09 §3. `schema_version` là 2 nếu `hash(vehicle_id, event_timestamp) mod 1000 < v2-ratio × 1000`, ngược lại là 1. `stop_id` và `current_stop_sequence` là trạm đang tới hoặc đang đỗ (§5.5).

### 6.3 TripUpdate

Theo DOC-09 §4 và DR-65. Nội dung khi phát tại thời điểm `t`:

1. **Các trạm đã qua kể từ TripUpdate trước** (chỉ số `lastReportedIndex + 1 … k`, với `k` là trạm cuối cùng có `actualArr ≤ t`): `arrival.time` là giờ đến thực tế, `departure.time` là giờ rời thực tế nếu đã rời, `delay` là độ lệch so với lịch. TripUpdate đầu tiên của chuyến không có phần này.
2. **Tối đa `lookahead-stops` trạm phía trước** (mặc định 10): giờ dự đoán = giờ theo lịch + độ trễ hiện tại (`arrDelay` nếu đang chạy, `depDelay` nếu đang đỗ). Không mô hình hóa việc trễ giảm dần.
3. Trạm đang đỗ: `arrival` là giá trị quan sát, `departure` là giá trị dự đoán.
4. Mọi phần tử là `SCHEDULED`, trừ khi kịch bản `disruption` bật `skipStops` (§7.3). Simulator không phát `NO_DATA`; parser của ETL vẫn phải hỗ trợ.

Bất biến (test bắt buộc): `stop_sequence` tăng dần và không trùng; mọi phần tử quan sát có `arrival.time ≤ event_timestamp`; mọi phần tử dự đoán có `arrival.time > event_timestamp`; số phần tử dự đoán ≤ `lookahead-stops`; phần tử `SCHEDULED` có ít nhất `arrival` hoặc `departure`.

`event_timestamp` của TripUpdate là mốc tick (theo chu kỳ) hoặc giờ đến thực tế của trạm vừa tới, làm tròn lên tới mili giây. Business key: mỗi phần tử một key `S|trip_id|stop_sequence` (DOC-13 §6.2).

TripUpdate chứa `lastReportedIndex` mới; nếu gửi thất bại (§10), `lastReportedIndex` **vẫn** tiến lên, vì một hệ thống nguồn thật cũng không gửi lại dữ liệu cũ. Các trạm đó thiếu giá trị quan sát trong warehouse; ledger không có key đó nên không bị tính là mất.

### 6.4 Gửi và ledger

```java
public interface MessageSink {
  /** Sends asynchronously; the ledger entry is written only from the success callback. */
  void send(OutboundMessage message);
}

public record OutboundMessage(
    String topic, String key, String value,                  // value = raw JSON string (may be deliberately invalid)
    Map<String, String> headers,                              // schema_version, entity_type
    LedgerEntry ledger) {}

public record LedgerEntry(
    UUID messageId, String entityType, List<String> businessKeys, Instant eventTimestamp,
    Instant producedAt, int schemaVersion, @Nullable String payloadHash,
    @Nullable String invalidKind, @Nullable UUID resendOf, @Nullable UUID scenarioRunId) {}
```

- `KafkaMessageSink` dùng `KafkaTemplate<String, String>` với cấu hình producer ở DOC-09 §1.2, thêm `max.block.ms=1000` để vòng phát không bị treo lâu khi Kafka chậm, và `delivery.timeout.ms=120000`.
- Key là `route_id`. Header `schema_version`, `entity_type`; `traceparent` do observation của Spring Kafka thêm (DR-50).
- `payload_hash` tính bằng `PayloadHasher` của `common` (DOC-09 §9) trên message **đã qua** các interceptor (§7.1), để khớp với hash ETL tính được. `malformed_json` thì hash là NULL.
- Callback thành công → `Ledger.record(entry, topic, partition, offset)` (cài đặt là `LedgerWriter`). Callback lỗi → tăng `pti_sim_send_errors_total`, log WARN có lấy mẫu (tối đa 1 dòng mỗi 10 giây), **không ghi ledger**.

`LedgerWriter`:

- Hàng đợi có giới hạn `pti.sim.ledger.queue-capacity` (mặc định 50.000). Thread `sim-ledger` gom tối đa `pti.sim.ledger.batch-size` (500) dòng hoặc chờ `pti.sim.ledger.flush-interval` (200 ms), rồi `INSERT` nhiều dòng một lần (JDBC batch với `reWriteBatchedInserts=true`), autocommit.
- Hàng đợi đầy thì `enqueue` chặn thread callback của producer. Producer không nhận thêm ack, `buffer.memory` đầy dần, `send` bị chặn tối đa `max.block.ms` rồi ném lỗi; vòng phát bỏ lượt đó và tăng `pti_sim_emissions_skipped_total{reason="backpressure"}`. **Không bao giờ bỏ dòng ledger của message đã được ack.**
- Lỗi DB khi flush: retry với backoff 200 ms → 5 s, không giới hạn số lần; trong lúc đó hàng đợi đầy dần và cơ chế ở trên làm vòng phát chậm lại.
- Khi khởi động và mỗi giờ: gọi `sim.ensure_ledger_partitions(today − 1, today + 2)` và `sim.drop_ledger_partitions_before(today − retention)` (DOC-13 §6.3). "today" theo ngày UTC của **giờ thật**, vì `produced_at` là giờ thật (§3.1).

`produced_at` trong envelope và trong ledger là **giờ thật** lúc gọi `send`. `event_timestamp` là giờ nghiệp vụ.

### 6.5 Điều chỉnh tốc độ phát (DR-68)

`rateMultiplier` gồm hai giá trị độc lập:

| Luồng | Tác dụng | Khoảng cho phép |
| --- | --- | --- |
| `gtfsRt` | Chia chu kỳ VehiclePosition và TripUpdate theo chu kỳ (trigger khi tới trạm không đổi). `0` = tạm dừng phát GTFS-rt, mô phỏng feed stale | 0 hoặc 0,1…20 |
| `ticketing` | Nhân tốc độ giao dịch của TicketingSeeder. `0` = dừng bán vé | 0 hoặc 0,1…20 |

Tải gấp N lần (EXP-05, EXP-07) được tạo bằng cách **tăng tần suất phát** chứ không nhân bản chuyến hay xe. Ở hệ số 10, mỗi xe phát vị trí mỗi 0,5 giây, khoảng 1.200 VehiclePosition/giây lúc cao điểm. Cách này giữ mọi tham chiếu (route, trip, stop, vehicle) hợp lệ, không làm thay đổi kết quả analytics (không sinh bunching giả), và vẫn tăng đúng các đại lượng cần đo: số message, số lần upsert, lag. Khi đổi chu kỳ, pha của từng xe giữ nguyên (tính lại theo chu kỳ mới), nên business key không trùng.

Giá trị khởi đầu lấy từ `pti.sim.rate-multiplier.gtfs-rt` và `pti.sim.rate-multiplier.ticketing` (mặc định 1,0); đổi lúc chạy qua `PUT /sim/rate` (§8), không lưu lại khi restart.

Trên compose, hệ số khởi đầu của cả hai luồng là **0** (DR-86): `make up` dựng simulator ở trạng thái tạm dừng, `make sim-start` bật phát, `make sim-stop` dừng lại (DOC-38 §4.3). `make up-demo` và `make up-exp` khởi động ở 1,0. k3d dùng default của app. Khi tạm dừng, simulator vẫn tick và giữ trạng thái xe, nên lúc bật lại xe xuất hiện ngay đúng vị trí. Kịch bản bắt đầu khi hệ số của luồng liên quan bằng 0 vẫn được nhận, nhưng không có tác dụng nhìn thấy được cho tới khi bật lại.

## 7. Kịch bản (gate P3)

### 7.1 Cơ chế chung

```java
public interface Scenario<P extends ScenarioParams> {
  String name();                                 // kebab-case, e.g. "bad-data"
  Class<P> paramsType();                         // record validated with Bean Validation
  ScenarioHandle start(ScenarioContext ctx, P params, UUID runId);
}

public interface ScenarioHandle {
  void stop();                                   // idempotent
  ScenarioProgress progress();                   // free-form counters for /sim/status
}

/** Hooks a scenario may register while it runs. */
public interface SegmentDelayOverlay {           // bunching, disruption
  double segmentDelta(TripRun run, int fromStopIndex, Instant departure);
}
public interface MessageInterceptor {            // bad-data, duplicates
  List<OutboundMessage> intercept(OutboundMessage message, Instant businessNow);
}
public interface TicketingOverlay {              // ticket-spike, refund-burst
  void onTick(TicketingContext ctx, Instant businessNow);
}
```

- Mỗi lần chạy có `runId` (UUIDv7) và một dòng `sim.sim_scenario_run` (DOC-13 §6.3): `RUNNING` khi bắt đầu; `COMPLETED` khi hết `duration`; `STOPPED` khi bị dừng qua API; `FAILED` khi ném lỗi. Lúc khởi động, simulator đổi mọi dòng `RUNNING` còn sót sang `FAILED` với `ended_at` = thời điểm khởi động, vì trạng thái kịch bản chỉ nằm trong bộ nhớ.
- `duration` bắt buộc, từ 1 phút tới `pti.sim.scenario.max-duration` (mặc định `2h`). `requested_by` lấy từ header `X-Requested-By` (API proxy điền `user:<username>`, DOC-32 E-90, runner điền `experiment:<EXP>/<run>`), thiếu thì là `cli`.
- Mọi message **bị kịch bản thay đổi hoặc sinh thêm** có `scenario_run_id` trong ledger. Message của xe bị kịch bản `bunching` hoặc `disruption` tác động cũng được đánh dấu, từ lúc overlay có hiệu lực tới khi kịch bản kết thúc.
- Chạy đồng thời: được phép chạy nhiều kịch bản khác loại. Hai lần chạy **cùng loại** chồng lên nhau chỉ được phép nếu chúng nhắm tới tuyến hoặc điểm bán khác nhau; ngược lại API trả 409. `bad-data` và `duplicates` mỗi loại chỉ một lần chạy tại một thời điểm.
- Thứ tự interceptor cố định: `duplicates` chạy **trước** `bad-data`. Như vậy bản gửi lại luôn là bản sạch, còn bản bị làm hỏng thì không được gửi lại, giúp tính số liệu EXP-02 và EXP-03 độc lập với nhau.

### 7.2 `bunching`

Ép hai xe liên tiếp cùng tuyến cùng chiều chạy sát nhau (FR-05, demo bước 2).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `routeId` | string | — (bắt buộc) | Có trong feed, `route_type ≠ 0` (bunching chỉ áp cho bus, DR-01) |
| `directionId` | 0 \| 1 | 0 | |
| `pairs` | int | 1 | 1–3 |
| `targetGapRatio` | double | 0,2 | 0,05–0,45; khoảng cách mục tiêu tính theo headway theo lịch |
| `duration` | ISO-8601 | `PT20M` | |

Thuật toán:

1. Lúc bắt đầu, lấy các xe đang chạy trên `(routeId, directionId)`, sắp theo tiến độ trên tuyến (`dist`). Bỏ xe đang ở 2 trạm đầu hoặc 2 trạm cuối (giống quy tắc loại trừ của DR-30). Chọn `pairs` cặp liên tiếp (leader L đi trước, follower F đi sau) có nhiều trạm phía trước nhất. Không đủ cặp thì trả 409 `no-eligible-vehicles`.
2. Headway `H` lấy từ lịch: hiệu giờ đi qua trạm hiện tại của F giữa chuyến của F và chuyến của L.
3. Mỗi khi F bắt đầu đoạn mới, overlay cộng `−min(0.5 × travelSched, gap − target)`, với `gap` là khoảng thời gian dự kiến giữa hai xe tại trạm kế tiếp của F và `target = targetGapRatio × H`. Mỗi khi L đỗ, dwell cộng thêm `min(60 s, gap − target)`. Giới hạn `minSpeedRatio` ở §5.3 vẫn áp dụng.
4. Khi `gap ≤ target`, hai xe giữ cùng nhịp: F nhận cùng `eps` với L. Với headway 10 phút, cặp xe thường sát nhau sau 5–10 phút.
5. Khi kịch bản kết thúc, overlay bị gỡ; hai xe dần tách ra theo mô hình trễ thường.

Nếu L hoặc F kết thúc chuyến trước khi hết `duration` thì cặp đó dừng; kịch bản không chọn cặp mới.

### 7.3 `disruption`

Tăng mạnh độ trễ trên một tuyến (FR-07, demo bước 3).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `routeId` | string | — (bắt buộc) | Có trong feed |
| `directionId` | 0 \| 1 \| null | null (cả hai chiều) | |
| `extraDelayPerStop` | ISO-8601 | `PT60S` | 10 s–5 phút |
| `maxExtraDelay` | ISO-8601 | `PT15M` | ≤ 1 giờ |
| `skipStops` | bool | false | Nếu true, mỗi chuyến bỏ 20% số trạm còn lại (`SKIPPED`), mô phỏng đổi lộ trình |
| `duration` | ISO-8601 | `PT20M` | |

Overlay cộng `extraDelayPerStop` cho mỗi đoạn của mọi chuyến trên tuyến (và chiều) cho tới khi phần trễ thêm của chuyến đạt `maxExtraDelay`. Sau khi kết thúc: các chuyến đang chạy nhận `−30 s` mỗi đoạn cho tới khi phần trễ thêm về 0, chuyến mới không bị ảnh hưởng. Với mặc định, trễ trung bình quan sát được trên tuyến tăng khoảng 5 phút trong 5 trạm, lớn hơn nhiều ngưỡng `z > 2,5` của DR-31.

### 7.4 `bad-data`

Làm hỏng một tỷ lệ message (FR-02, EXP-03, demo bước 4).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `ratio` | double | 0,05 | 0,001–0,5 |
| `kinds` | list | mọi loại dưới đây | Không rỗng |
| `entityTypes` | list | `VEHICLE_POSITION`, `TRIP_UPDATE` | |
| `duration` | ISO-8601 | `PT10M` | |

Message được chọn khi `hash(seed, message_id) mod 10⁶ < ratio × 10⁶`, nên tỷ lệ đúng theo kỳ vọng và tái lập được. Message bị làm hỏng **thay thế** message gốc (bản sạch không được gửi). Ledger ghi `intended_invalid = true`, `invalid_kind`, và **business key của message gốc**.

| `invalid_kind` | Cách làm hỏng | ETL phải xếp vào `stage` (DOC-16, DOC-30) |
| --- | --- | --- |
| `malformed_json` | Cắt value ở vị trí ngẫu nhiên trong nửa sau chuỗi JSON | `DESERIALIZE` |
| `schema_violation` | Một trong: xóa một trường bắt buộc; đổi kiểu (`direction_id: "0"`); thêm trường lạ; `lat: 123.4`; với TripUpdate thì đảo thứ tự hai `stop_sequence` | `SCHEMA` |
| `unknown_schema_version` | `schema_version: 3` (DR-59) | `SCHEMA` |
| `out_of_bbox` | Đặt `lat`/`lon` ra ngoài bbox của feed nhưng vẫn hợp lệ theo schema (ví dụ 40.7128, −74.0060) | `QUALITY` |
| `unknown_route` | `route_id` không có trong feed (`R-UNKNOWN-<n>`); key Kafka giữ nguyên `route_id` gốc | `QUALITY` |
| `unknown_stop` | `stop_id` không có trong feed | `QUALITY` |
| `future_timestamp` | `event_timestamp` = giờ nghiệp vụ + 2 giờ | `QUALITY` |
| `delay_out_of_range` | Chỉ TripUpdate: một `delay` = ±9.000 giây (vượt ±2 giờ) | `QUALITY` |

Rule DQ tương ứng (id `DQ-xx`) được chốt ở DOC-16; bảng ở trên là hợp đồng mà test của ETL phải khớp.

### 7.5 `duplicates`

Gửi lại message đã gửi (FR-03, EXP-02).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `ratio` | double | 0,1 | 0,001–1,0 |
| `minDelay` / `maxDelay` | ISO-8601 | `PT0S` / `PT60S` | `maxDelay ≤ PT2H` |
| `entityTypes` | list | cả hai | |
| `duration` | ISO-8601 | `PT10M` | |

Message được chọn (theo hash như §7.4) được đưa vào hàng đợi gửi lại với độ trễ ngẫu nhiên đều trong `[minDelay, maxDelay]`. Bản gửi lại **giống hệt** bản gốc ở mọi trường trừ `message_id` (UUIDv7 mới) và `produced_at` (DOC-09 §2). Ledger: `is_resend = true`, `resend_of` = `message_id` gốc, cùng business key và cùng `payload_hash`. Hàng đợi tối đa 200.000 message; đầy thì bỏ lượt gửi lại và tăng metric (lượt bị bỏ không có trong ledger nên không ảnh hưởng số liệu).

`maxDelay` vượt TTL của `dedup_registry` (1 giờ, DR-16) là có chủ đích: kiểm chứng rằng không trùng là nhờ upsert, không phải nhờ registry.

### 7.6 `ticket-spike`

Tăng đột biến giao dịch tại một điểm bán (FR-09.4, DR-34).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `salePointId` | string | kiosk có nhiều lượt khởi hành nhất (`KIOSK-001`) | Tồn tại trong `sale_point` |
| `extraPerMinute` | double | 6 | 1–120 |
| `duration` | ISO-8601 | `PT30M` | ≥ `PT15M` để phủ trọn một cửa sổ 15 phút |

Mỗi phút sinh thêm `extraPerMinute` giao dịch `SALE` (Poisson) tại điểm bán đó, loại vé và giá theo quy tắc thường. Với mặc định, mỗi cửa sổ 15 phút có khoảng 90 giao dịch thêm, vượt `txn_count ≥ 20` và `z > 3` của DR-34.

### 7.7 `refund-burst`

Chuỗi hoàn vé dồn dập (FR-09.4).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `salePointId` | string | như §7.6 | |
| `salesPerMinute` | double | 2 | 0,5–60 |
| `refundRatio` | double | 0,8 | 0,31–1,0 |
| `refundDelay` | ISO-8601 | `PT2M` | 0–30 phút |
| `duration` | ISO-8601 | `PT30M` | ≥ `PT15M` |

Sinh `salesPerMinute` giao dịch bán tại điểm bán, và hoàn `refundRatio` trong số đó sau `refundDelay` (hoàn vé luôn trỏ tới giao dịch có thật, đúng ràng buộc DB ở DOC-13 §5.2). Tỉ lệ hoàn của DOC-23 §9.1 tính trên **mọi** giao dịch của điểm bán trong cửa sổ, gồm cả 13–25 giao dịch thường của một kiosk. Mặc định cho khoảng 30 bán và 24 hoàn của kịch bản mỗi 15 phút, tức `refund_ratio ≈ 24 / 50 ≈ 0,48 > 0,3` và `refund_count ≥ 5`. Với 0,5 như bản trước, tỉ lệ chỉ khoảng 15 / 50 = 0,3 và không vượt ngưỡng (DOC-23 §16).

### 7.8 `load-ramp`

Tăng giảm tải theo bậc (EXP-05, EXP-07, demo bước 7).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `steps` | list double | `[1, 2, 5, 10]` | Mỗi giá trị 0,1–20 |
| `stepDuration` | ISO-8601 | `PT5M` | ≥ 1 phút |
| `rampDown` | bool | true | Sau bậc cuối thì đi ngược lại các bậc rồi về 1 |
| `includeTicketing` | bool | false | Áp cùng hệ số cho `ticketing` |

`duration` được suy ra (`stepDuration × số bậc`, gấp đôi trừ một nếu `rampDown`), không nhận từ request. Kịch bản đặt `rateMultiplier.gtfsRt` ở đầu mỗi bậc; khi kết thúc hoặc bị dừng thì trả về giá trị trước khi bắt đầu. Không chạy song song với `PUT /sim/rate`: trong lúc `load-ramp` chạy, `PUT /sim/rate` trả 409.

### 7.9 `late-delivery` (P6)

Giữ lại một tỷ lệ message rồi gửi muộn với **event time gốc**, mô phỏng mạng hoặc gateway của nguồn bị nghẽn (DOC-24 §6.4, demo bước 4 nhánh auto-replay).

| Tham số | Kiểu | Mặc định | Ràng buộc |
| --- | --- | --- | --- |
| `ratio` | double | 0,002 | 0,0001–0,1 |
| `delay` | ISO-8601 | `PT6M` | `PT1M`–`PT2H`; phải lớn hơn `pti.dq.max-clock-skew` của ETL thì message mới vào DLQ (DQ-07). Simulator không đọc cấu hình ETL nên không kiểm; DOC-46 dùng `PT6M` với skew `5m` do `make up-demo` đặt (DOC-39 §3.2) |
| `entityTypes` | list | `VEHICLE_POSITION` | `VEHICLE_POSITION`, `TRIP_UPDATE` |
| `duration` | ISO-8601 | `PT2M` | |

- Message được chọn theo hash như §7.4, **không** được gửi đúng giờ mà vào hàng đợi trễ (dùng chung cơ chế với `duplicates`, tối đa 200.000 message). Tới hạn thì gửi nguyên văn: cùng `message_id`, `event_timestamp`, payload; `produced_at` và timestamp Kafka là lúc gửi thật.
- Ledger ghi dòng khi message thực sự được ack (như mọi message), với `scenario_run_id` và `intended_invalid = false`: message hợp lệ, chỉ đến muộn. Vì vậy không chạy `late-delivery` cùng lúc với EXP-03 (ledger coi message này là hợp lệ, còn ETL đưa nó vào DLQ cho tới khi auto-replay).
- Kịch bản kết thúc khi hết `duration` **và** hàng đợi trễ đã gửi hết; `DELETE` giữa chừng thì gửi ngay các message còn giữ (không để mất message).
- `concurrency = SINGLE`. Catalog: `title` "Late delivery", `description` "Hold back some vehicle messages and send them late with their original timestamps."

## 8. API điều khiển

App lắng nghe cổng ứng dụng 8080 trong container (DOC-38 §5). API là **nội bộ** (TB-2, DOC-07 §5), không xác thực; compose chỉ bind ra `127.0.0.1`. Người dùng UI đi qua proxy `/api/v1/sim/**` của API ở profile `demo`, cần role `operator` (DR-43, DOC-32). JSON dùng camelCase, lỗi trả RFC 9457 Problem Details với `type` dạng `urn:pti:problem:<slug>` (DOC-30 §3).

| Method | Path | Mô tả | Thành công | Lỗi |
| --- | --- | --- | --- | --- |
| GET | `/sim/status` | Trạng thái tổng | 200 | — |
| PUT | `/sim/rate` | Đổi `rateMultiplier` | 200 (trả status) | 400 `invalid-param`, 409 `load-ramp-running` |
| GET | `/sim/scenarios` | Danh mục kịch bản: tên, tham số, mặc định, ràng buộc | 200 | — |
| POST | `/sim/scenarios/{name}` | Bắt đầu một lần chạy | 201, header `Location: /sim/scenario-runs/{runId}` | 400 `invalid-param`, 404 `unknown-scenario`, 409 `scenario-conflict` / `no-eligible-vehicles` |
| DELETE | `/sim/scenarios/{name}` | Dừng mọi lần chạy đang `RUNNING` của kịch bản đó | 204 | 404 `unknown-scenario` |
| GET | `/sim/scenario-runs?status=&limit=` | Lịch sử (đọc `sim_scenario_run`), mới nhất trước, `limit` ≤ 100 | 200 | — |
| GET | `/sim/scenario-runs/{runId}` | Một lần chạy, kèm `progress` nếu đang chạy | 200 | 404 `scenario-run-not-found` |
| DELETE | `/sim/scenario-runs/{runId}` | Dừng một lần chạy; idempotent | 204 | 404 |

`PUT /sim/rate` nhận một hoặc cả hai khóa: `{"gtfsRt": 2.0}`, `{"ticketing": 0}`, `{"gtfsRt": 5.0, "ticketing": 5.0}`. Body rỗng hoặc giá trị ngoài khoảng của §6.5 → 400 `invalid-param`.

`GET /sim/scenarios` trả catalog để UI sinh form (DOC-36 `demo-control.md` §6.1). Catalog sinh từ `paramsType()` của từng `Scenario` cộng với annotation `@ScenarioParam(label, type, suggestions)` trên từng thành phần record; thứ tự như §7.2–7.9.

```json
{
  "items": [
    {
      "name": "ticket-spike",
      "title": "Ticket sales spike",
      "description": "Add extra sales at one sale point.",
      "concurrency": "PER_TARGET",
      "params": [
        { "name": "salePointId", "label": "Sale point", "type": "STRING", "required": false, "nullable": false,
          "default": "KIOSK-001", "suggestions": ["KIOSK-001", "KIOSK-002"] },
        { "name": "extraPerMinute", "label": "Extra sales per minute", "type": "DOUBLE", "required": false, "default": 6, "min": 1, "max": 120 },
        { "name": "duration", "label": "Duration", "type": "DURATION", "required": false, "default": "PT30M", "min": "PT15M", "max": "PT2H" }
      ]
    }
  ]
}
```

| Trường | Ý nghĩa |
| --- | --- |
| `name`, `title`, `description` | Tên kebab-case (path của POST), tiêu đề và mô tả tiếng Anh cho UI |
| `concurrency` | `PER_TARGET` (bunching, disruption, ticket-spike, refund-burst: chạy song song khi khác tuyến hoặc điểm bán) \| `SINGLE` (bad-data, duplicates, load-ramp, late-delivery) |
| `params[].type` | `STRING` \| `INT` \| `DOUBLE` \| `BOOLEAN` \| `DURATION` (ISO-8601) \| `ENUM` \| `ENUM_LIST` \| `DOUBLE_LIST` \| `ROUTE` (một `route_id` của feed) |
| `required`, `nullable`, `default` | `default` vắng mặt khi không có mặc định; `nullable: true` cho `disruption.directionId` (null = cả hai chiều) |
| `min`, `max` | Số, hoặc chuỗi ISO-8601 với `DURATION`; với `DOUBLE_LIST` áp cho từng phần tử |
| `options` | Với `ENUM`/`ENUM_LIST`: giá trị cho phép dạng chuỗi (`directionId`: `"0"`, `"1"`; UI gửi số khi mọi option là số) |
| `suggestions` | Chỉ `salePointId`: tối đa 20 `sale_point_id` đang hoạt động từ bảng `sale_point`, sắp theo số giao dịch 7 ngày gần nhất |

- `duration` có `max` = `pti.sim.scenario.max-duration`. `load-ramp` **không** có tham số `duration` (§7.8).
- Kiểm tra thật vẫn là Bean Validation lúc `POST`; catalog chỉ để UI phản hồi sớm. Test T-18 của §14 so catalog với ràng buộc của record.

Ví dụ:

```http
POST /sim/scenarios/bad-data
Content-Type: application/json
X-Requested-By: experiment:EXP-03/20261002T101500Z-r07

{ "ratio": 0.05, "kinds": ["malformed_json", "schema_violation", "unknown_route"], "duration": "PT10M" }
```

```json
{
  "runId": "0192f7b1-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
  "scenario": "bad-data",
  "status": "RUNNING",
  "params": { "ratio": 0.05, "kinds": ["malformed_json", "schema_violation", "unknown_route"], "entityTypes": ["VEHICLE_POSITION", "TRIP_UPDATE"], "duration": "PT10M" },
  "requestedBy": "experiment:EXP-03/20261002T101500Z-r07",
  "startedAt": "2026-10-02T10:15:03.120Z",
  "plannedEndAt": "2026-10-02T10:25:03.120Z"
}
```

`GET /sim/status`:

```json
{
  "clock": {
    "businessNow": "2026-10-02T21:40:00.000Z",
    "offset": "PT14H30M",
    "agencyTimeZone": "America/Chicago",
    "serviceDates": [
      { "realDate": "2026-10-01", "feedDate": "2026-09-30" },
      { "realDate": "2026-10-02", "feedDate": "2026-10-01" }
    ]
  },
  "feed": { "sha256": "2cfc4a1b…", "validFrom": "2026-09-26", "validTo": "2026-11-13" },
  "rate": { "gtfsRt": 1.0, "ticketing": 1.0 },
  "activeVehicles": 598,
  "activeTrips": 466,
  "messagesPerSecond": { "gtfs.vehicle_positions": 119.6, "gtfs.trip_updates": 21.8 },
  "ticketingPerSecond": 1.9,
  "ledger": { "queueDepth": 42, "lastFlushAt": "2026-10-02T07:10:00.180Z" },
  "runningScenarios": [ { "runId": "…", "scenario": "bunching", "plannedEndAt": "…" } ]
}
```

`businessNow` là giờ nghiệp vụ; `lastFlushAt` là giờ thật (§3.1). `messagesPerSecond` (message đã được broker ack, theo topic) và `ticketingPerSecond` (giao dịch bán đã ghi) là trung bình trên 10 giây thật gần nhất, không tính giây hiện tại; số chính xác xem ở Prometheus (§12).

Lỗi `invalid-param` có `title` "Invalid parameter" và `errors[]` theo trường khi lỗi nằm ở một khóa cụ thể, ví dụ `PUT /sim/rate` với `{"gtfsRt": 25}` → `"errors": [{"field": "gtfsRt", "message": "must be 0 or between 0.1 and 20"}]`. Body rỗng, `{}` hoặc JSON sai kiểu → 400 `invalid-param` với `errors` rỗng. `traceId` chỉ có khi bật tracing (`PTI_TRACING_ENABLED`).

## 9. TicketingSeeder

### 9.1 Điểm bán

Khi khởi động, seeder upsert (`INSERT … ON CONFLICT (sale_point_id) DO NOTHING`) các điểm bán theo DOC-13 §5.4:

| Loại | Số lượng | Tên hiển thị (tiếng Anh, DR-61) | Cách chọn |
| --- | --- | --- | --- |
| `KIOSK-001…060` | `pti.sim.ticketing.kiosk-count` | `Kiosk – <stop_name>` | Các trạm có nhiều lượt khởi hành nhất trong ngày feed 2026-09-29, sắp giảm dần rồi theo `stop_id` |
| `ONBOARD-<route_id>` | 124 (mọi tuyến bus) | `Onboard validator – Route <route_short_name>` | |
| `APP-IOS`, `APP-ANDROID`, `APP-WEB` | 3 | `Mobile app (iOS)`, `Mobile app (Android)`, `Web store` | |

Lần khởi động đầu tiên sinh 187 event `c` trên `ticketing.sale_points.cdc`. Các lần sau không sinh event nào.

### 9.2 Tốc độ theo giờ

Giao dịch `SALE` đến theo quá trình Poisson với cường độ `λ(h) × dayFactor × rateMultiplier.ticketing`, trong đó `h` là giờ Chicago theo đồng hồ nghiệp vụ:

| Giờ | 0–4 | 5 | 6 | 7 | 8 | 9 | 10–13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| λ (giao dịch/giây) | 0,05 | 0,3 | 1,2 | 2,0 | 1,6 | 0,9 | 0,8 | 0,9 | 1,4 | 2,0 | 1,8 | 1,1 | 0,7 | 0,5 | 0,35 | 0,25 | 0,1 |

`dayFactor` = 1,0 ngày thường, 0,6 thứ Bảy, Chủ nhật và ngày lễ (theo `dim_date`/lịch lễ của DR-11, simulator đọc cùng file cấu hình ngày lễ). Trung bình ngày thường khoảng 0,77 giao dịch/giây, khoảng 67 nghìn giao dịch mỗi ngày, cao điểm 2/giây (FR-13.2). Bảng λ là `pti.sim.ticketing.rate-profile` (DOC-29).

### 9.3 Nội dung một giao dịch

| Trường | Quy tắc |
| --- | --- |
| `transaction_id` | UUIDv7 |
| `sale_point_id` | Kiosk 45%, onboard 35%, app 20%. Kiosk chọn theo trọng số lượt khởi hành tại trạm; onboard chọn theo số chuyến của tuyến trong ngày; app: iOS 45%, Android 45%, Web 10% |
| `route_id`, `stop_id` | Theo DOC-13 §5.4 |
| `ticket_type`, `amount` | Tỷ lệ và bảng giá DOC-13 §5.4; giờ cao điểm theo giờ nghiệp vụ |
| `customer_ref` | App: luôn có. Kiosk và onboard: 60% có (thẻ), 40% NULL (tiền mặt). Dạng `cust-<8 hex>` sinh từ một tập 50.000 khách mô phỏng |
| `created_at` | **Đặt tường minh bằng giờ nghiệp vụ** (không dùng `DEFAULT now()`), để `sale_date` khớp với dữ liệu GTFS-rt khi có offset |
| `updated_at` | Trigger đặt bằng `now()` của DB (giờ thật) khi UPDATE; khi INSERT thì bằng `created_at` |

Các hành động tiếp theo (hàng đợi trễ trong bộ nhớ, mất khi restart; chấp nhận được vì chỉ là tỷ lệ nhỏ):

| Hành động | Tỷ lệ | Thời điểm | SQL |
| --- | --- | --- | --- |
| Hoàn vé | `refund-ratio` 1% số giao dịch bán | Ngẫu nhiên đều trong 1–30 phút sau | `INSERT` dòng `REFUND`, `refund_of` = giao dịch gốc, cùng `amount` và `sale_point_id` |
| Hủy | `void-ratio` 0,2% | 1–5 giây sau | `UPDATE … SET status = 'VOIDED'` |
| Xóa | `delete-ratio` 0,05% | 1–5 phút sau | `DELETE` |

Ba tập này rời nhau (một giao dịch chỉ nhận tối đa một hành động). Giao dịch đã có hoàn vé thì không bị xóa, tránh vi phạm khóa ngoại `refund_of`.

### 9.4 Thực thi

Thread `sim-ticketing` tick mỗi 200 ms, sinh số giao dịch theo Poisson cho khoảng thời gian đó rồi ghi mỗi giao dịch bằng một câu `INSERT` (autocommit). Lỗi DB thì log WARN, tăng `pti_sim_ticketing_errors_total`, bỏ các giao dịch của tick đó (hệ thống bán vé "đang sập") và thử lại ở tick sau. Ticketing không ghi ledger; ground truth là chính `ticketing_source` (DR-28).

## 10. Transaction, đồng thời và vòng đời

| Thread | Việc | Chia sẻ |
| --- | --- | --- |
| `sim-emitter` (1) | Tiến các `TripRun`, dựng VehiclePosition/TripUpdate, gọi `MessageSink.send` | `ActiveSet` (chỉ thread này ghi) |
| Thread I/O của Kafka producer | Callback → `LedgerWriter.enqueue` | Hàng đợi ledger (`ArrayBlockingQueue`) |
| `sim-ledger` (1) | Flush ledger | Hàng đợi ledger |
| `sim-ticketing` (1) | TicketingSeeder và các overlay ticketing | Hàng đợi hành động trễ (chỉ thread này dùng) |
| `sim-resend` (1) | Hàng đợi gửi lại của `duplicates` | `MessageSink` (thread-safe) |
| Thread HTTP | API | `ScenarioRegistry` (`ConcurrentHashMap`), overlay đăng ký qua `CopyOnWriteArrayList` |

- Ledger: mỗi lần flush là một câu `INSERT` autocommit. Ledger không cần cùng transaction với Kafka; quy tắc "chỉ ghi sau ack" là đủ cho DR-28.
- `sim_scenario_run`: cập nhật trạng thái bằng câu `UPDATE … WHERE run_id = ? AND status = 'RUNNING'`, nên dừng hai lần là idempotent.
- **Khởi động:** nạp feed → dựng chỉ mục → `ensure_ledger_partitions` → đánh dấu run `RUNNING` cũ là `FAILED` → seed điểm bán → dựng lại trạng thái các chuyến đang chạy (lấy mẫu tất định từ đầu mỗi chuyến tới `businessNow`, tối đa 141 đoạn mỗi chuyến) → readiness `UP` → bắt đầu các thread, phát theo hệ số khởi đầu (§6.5; bằng 0 thì thread vẫn tick nhưng không gửi). Sau restart, xe xuất hiện đúng vị trí mà mô hình tất định cho ra; `lastReportedIndex` đặt bằng trạm đã qua gần nhất (TripUpdate đầu tiên sau restart không có phần quan sát).
- **Tắt (SIGTERM, `server.shutdown=graceful`):** dừng `sim-emitter`, `sim-ticketing`, `sim-resend` → `KafkaTemplate.flush()` → chờ callback (tối đa 10 giây) → flush ledger tới rỗng (tối đa 20 giây) → đóng. `spring.lifecycle.timeout-per-shutdown-phase=30s`.
- **Thực nghiệm không được kill simulator.** Nếu simulator chết đột ngột, các message đã ack nhưng chưa kịp vào ledger sẽ xuất hiện trong warehouse mà không có trong ledger; runner đánh dấu lần chạy đó là không hợp lệ (DOC-45).

## 11. Cấu hình

Toàn bộ key nằm ở DOC-29 §3.2. Key mới do tài liệu này chốt:

| Key | Mặc định |
| --- | --- |
| `pti.clock.offset` (mọi app, DR-67; thay `pti.sim.time-offset`) | `0s` |
| `pti.sim.seed` | `42` |
| `pti.sim.feed.location` / `pti.sim.feed.sha256` | `file:/data/gtfs/metrotransit-mn-20260926.zip` / SHA-256 ở DOC-13 §2.1 |
| `pti.sim.tick` | `200ms` |
| `pti.sim.gps-noise` | `5` (mét) |
| `pti.sim.vehicle.max-layover-emit` | `30m` |
| `pti.sim.delay.*` | bảng §5.3 |
| `pti.sim.route-factor.phi` / `sigma` / `bucket` | `0.8` / `0.15` / `5m` |
| `pti.sim.rate-multiplier.gtfs-rt` / `ticketing` | `1.0` / `1.0` (compose đặt `0` trừ khi có `PTI_SIM_START_RATE`, DR-86) |
| `pti.sim.ledger.queue-capacity` / `batch-size` / `flush-interval` | `50000` / `500` / `200ms` |
| `pti.sim.scenario.max-duration` | `2h` |
| `pti.sim.duplicates.queue-capacity` | `200000` |
| `pti.sim.ticketing.rate-profile` | bảng §9.2 (24 giá trị) |
| `pti.sim.ticketing.weekend-factor` | `0.6` |
| `pti.sim.ticketing.customer-pool` | `50000` |

## 12. Metrics và log

Metric theo quy ước Micrometer (tên có dấu chấm), Prometheus xuất thành dạng gạch dưới. Danh mục tổng ở DOC-28.

| Metric (Prometheus) | Loại | Label | Ý nghĩa |
| --- | --- | --- | --- |
| `pti_sim_messages_sent_total` | counter | `topic`, `entity_type`, `kind` (`valid`, `invalid`, `resend`) | Message đã được ack |
| `pti_sim_send_errors_total` | counter | `topic` | Callback lỗi |
| `pti_sim_emissions_skipped_total` | counter | `reason` (`backpressure`, `send_timeout`, `resend_queue_full`) | Lượt phát bị bỏ |
| `pti_sim_active_vehicles`, `pti_sim_active_trips` | gauge | — | |
| `pti_sim_synthetic_vehicles` | gauge | — | Số block dùng id xe tổng hợp (DOC-13 §4) |
| `pti_sim_ledger_queue_depth` | gauge | — | |
| `pti_sim_resend_queue_depth` | gauge | — | Số bản gửi lại đang chờ của kịch bản `duplicates` (§7.5). Runner EXP-02 chờ gauge về 0 trước khi đóng cửa sổ |
| `pti_sim_ledger_rows_total` | counter | — | Dòng ledger đã ghi |
| `pti_sim_tick_lag_seconds` | gauge | — | `businessNow` − mốc tick đang xử lý; > 2 s nghĩa là simulator không theo kịp |
| `pti_sim_rate_multiplier` | gauge | `stream` | |
| `pti_sim_ticket_transactions_total` | counter | `txn_type`, `action` (`insert`, `void`, `delete`) | |
| `pti_sim_ticketing_errors_total` | counter | — | |
| `pti_sim_scenario_active` | gauge | `scenario` | Số lần chạy đang `RUNNING` |
| `pti_source_replication_slot_retained_bytes` | gauge | `slot` | WAL mà replication slot của Debezium giữ trên `pg-source`: `pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)` từ `pg_replication_slots`, mỗi `pti.observability.slot-probe.interval` (30 giây). Alert `DebeziumWalRetained` (DR-71, DOC-28) |
| `pti_sim_slot_probe_errors_total` | counter | — | Truy vấn slot lỗi |

Log (JSON, tiếng Anh): khởi động (`Feed loaded: sha256=…, trips=20220, stopTimes=872717 in 6.2s`), ánh xạ ngày (§3.2), bắt đầu và kết thúc kịch bản (`Scenario started: name=bad-data runId=… params=…`), đổi tốc độ, cảnh báo backpressure (lấy mẫu). Không log từng message.

## 13. Lỗi và cách xử lý

| Tình huống | Hành vi |
| --- | --- |
| File feed thiếu hoặc sai SHA-256 | Không khởi động (FATAL), log lỗi rõ ràng |
| Ngày thật không ánh xạ được (feed thiếu một thứ trong tuần) | Không xảy ra với feed đã được `GtfsStaticLoadJob` chấp nhận (DOC-13 §2.5); nếu xảy ra thì không phát cho ngày đó, log ERROR mỗi giờ |
| Kafka không khả dụng | Send lỗi sau `max.block.ms`, bỏ lượt, không ghi ledger; tự tiếp tục khi Kafka quay lại |
| `pti_sim` không khả dụng | Ledger retry, hàng đợi đầy dần → backpressure → bỏ lượt phát; tự tiếp tục |
| `ticketing_source` không khả dụng | Bỏ giao dịch của tick, retry ở tick sau |
| Tham số kịch bản sai | 400 Problem Details liệt kê từng trường sai (`errors[]`) |
| Kịch bản ném lỗi khi chạy | Gỡ overlay, `status = FAILED`, log ERROR; simulator tiếp tục chạy bình thường |
| Simulator không theo kịp (`tick_lag` > 2 s) | Metric và log WARN; lượt phát trễ vẫn được thực hiện với `event_timestamp` là mốc đúng của nó |

## 14. Test bắt buộc

| # | Tầng | Test |
| --- | --- | --- |
| T-01 | Unit | `ServiceDateMapper`: `fixed`; `auto` trong và ngoài khoảng hiệu lực; thứ Ba → 2026-09-29, thứ Bảy → 2026-10-03, Chủ nhật → 2026-10-04; ngày lễ trong feed không bị chọn |
| T-02 | Unit | `BusinessClock`: offset áp đúng; offset không phải số phút tròn hoặc vượt ±24 giờ thì bị từ chối khi bind cấu hình |
| T-03 | Unit (feed thật) | Vào 16:40 ngày 2026-09-29, số xe đang phục vụ nằm trong 606 ± 5% (FR-13.1) |
| T-04 | Unit (feed thật) | Gán xe: ba test của DOC-13 §4 |
| T-05 | Unit | Chuyển động: `dist` không giảm theo thời gian; tại `actualArr` vị trí trùng trạm (sai số ≤ 1 m trước nhiễu); `STOPPED_AT` trong khoảng dwell; không rời timepoint sớm; tốc độ ≤ 2 lần tốc độ theo lịch |
| T-06 | Unit | Tất định: cùng seed, cùng `businessNow` → cùng vị trí và cùng TripUpdate; khác seed → khác |
| T-07 | Unit | Hiệu chỉnh mô hình trễ: mô phỏng một ngày thường (không phát Kafka) → tỷ lệ `|delay| ≤ 300` nằm trong 70–90% |
| T-08 | Unit | Bất biến TripUpdate ở §6.3, gồm TripUpdate đầu chuyến và sau restart |
| T-09 | Contract | Mọi message hợp lệ do simulator sinh pass JSON Schema trong `common` (DR-44); mỗi `invalid_kind` **không** pass schema khi kỳ vọng `DESERIALIZE`/`SCHEMA`, và pass schema khi kỳ vọng `QUALITY` |
| T-10 | Unit | `PayloadHasher` của simulator và ETL cho cùng hash trên cùng message, kể cả bản gửi lại |
| T-11 | Integration (Kafka + Postgres Testcontainers) | Chạy 2 phút với `rate=5`: số dòng ledger = số message được ack (đếm từ offset các partition); không có dòng ledger cho message gửi lỗi (tiêm lỗi bằng cách dừng Kafka 10 giây) |
| T-12 | Integration | Backpressure: làm `pti_sim` chậm (khóa bảng) → không mất dòng ledger nào của message đã ack; `emissions_skipped_total` tăng |
| T-13 | Integration | TicketingSeeder: tốc độ nằm trong ±15% của λ sau 10 phút với `rate=10`; mọi `REFUND` trỏ tới giao dịch có thật; có đủ `u` và `d` |
| T-14 | Integration | Mỗi kịch bản ở §7 (P3-01): `bunching` → trong 15 phút có cặp xe với gap ≤ `targetGapRatio × H`; `disruption` → trễ trung bình trên tuyến tăng ≥ 4 phút; `bad-data` → tỷ lệ message hỏng trong ledger nằm trong ±20% của `ratio` và mỗi loại xuất hiện; `duplicates` → mỗi bản gửi lại có cùng hash và business key với bản gốc; `ticket-spike`, `refund-burst` → điều kiện của DR-34 thỏa trên DB nguồn; `load-ramp` → tốc độ phát ở mỗi bậc bằng hệ số × tốc độ nền (±10%) |
| T-15 | Web (MockMvc) | API: 201/400/404/409 theo bảng §8; `X-Requested-By` được lưu; `DELETE` idempotent |
| T-16 | Integration | Restart simulator khi đang có kịch bản chạy → dòng `RUNNING` chuyển `FAILED`; xe xuất hiện lại đúng vị trí tất định |
| T-17 | Integration | = DOC-28 O-07: gauge slot đọc được bằng role `source_simulator` và tăng khi không có consumer đọc slot |
| T-18 | Unit | Catalog `GET /sim/scenarios`: mọi thành phần record của 8 kịch bản có mặt với `type`, `min`, `max`, `default` khớp Bean Validation; `load-ramp` không có `duration`; `suggestions` của `salePointId` ≤ 20. `PUT /sim/rate` với body rỗng → 400 |
| T-19 | Integration | `late-delivery` với `ratio` 0,01, `delay` `PT2M`, `duration` `PT1M` (đồng hồ tăng tốc ×1 trong test): message được chọn tới Kafka muộn 2 phút ± 5 giây với `event_timestamp` gốc; số message chọn nằm trong ±20% của `ratio`; `DELETE` giữa chừng gửi hết hàng đợi; ledger không có `intended_invalid = true` |

## 15. Câu hỏi còn mở

Không còn. Các quyết định phát sinh khi viết tài liệu này đã ghi thành DR-67 (đồng hồ nghiệp vụ) và DR-68 (tạo tải bằng tần suất phát).
