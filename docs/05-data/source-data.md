# Dữ liệu nguồn

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-13
> Phụ thuộc: [DR](../00-decision-register.md) (DR-01, 02, 06, 08, 09, 28, 60, 64), [DOC-09](../03-architecture/messaging-contracts.md), [DOC-14](warehouse-model.md), [DOC-17](db-roles-and-grants.md)
> Người dùng chính: người viết `common` (P1-07), simulator (P1-08…P1-11), `GtfsStaticLoadJob` (P2), experiment runner (P3)

Tài liệu này mô tả **mọi dữ liệu đi vào hệ thống**: feed GTFS static, message GTFS-realtime do simulator phát, database ticketing nguồn, và ledger làm ground truth. Mọi con số trong tài liệu đều **đo trên feed đã chốt**, không phải ước lượng. Mọi đoạn DDL đều đã chạy thử trên PostgreSQL 17.

## 1. Tổng quan

| Nguồn | Nơi ở | Ai ghi | Ai đọc | Đường vào warehouse |
| --- | --- | --- | --- | --- |
| GTFS static | `sample-data/gtfs/*.zip` → raw zone `raw/gtfs-static/<sha256>.zip` | Người vận hành (chốt snapshot) | simulator, `GtfsStaticLoadJob` | Job batch, §2 |
| GTFS-realtime | Topic `gtfs.vehicle_positions`, `gtfs.trip_updates` | source-simulator | etl-stream, S3 sink | Spring Kafka, §7 |
| Ticketing | `pg-source` / `ticketing_source` | source-simulator (TicketingSeeder) | Debezium | CDC, §5, §7.3 |
| Ledger | `pg-source` / `pti_sim` / schema `sim` | source-simulator | experiment runner | Không vào warehouse, §6 |

## 2. GTFS static

### 2.1 Danh tính feed

| Thuộc tính | Giá trị |
| --- | --- |
| Nhà cung cấp | Metro Transit / Metropolitan Council, Minneapolis–St. Paul (DR-01) |
| File | `sample-data/gtfs/metrotransit-mn-20260926.zip` |
| SHA-256 | `2cfc4a1b6efd7e860cbc0ee4bc90487fb8a5eb3f7946b35df021569d032ee23b` |
| `feed_info.feed_version` | `1790175878` |
| `feed_start_date` … `feed_end_date` | 2026-09-26 … 2026-12-04 |
| Khoảng lịch thực tế (`calendar` + `calendar_dates`) | 2026-09-26 … 2026-11-13. Đây là `valid_from`/`valid_to` của `gtfs_feed_version` |
| Múi giờ | `America/Chicago` (một múi giờ cho cả 8 agency). DST kết thúc 2026-11-01, bắt đầu lại 2027-03-14 |
| Bản quyền và ghi công | "Schedule data: Metro Transit / Metropolitan Council" (hiện ở footer UI và README) |

Feed được **ghim** (không tải lại lúc chạy) để thực nghiệm tái lập được. Muốn đổi feed thì tạo file zip mới, cập nhật `SHA256SUMS`, README và bảng này.

### 2.2 Thống kê (đo bằng `sample-data/gtfs/profile_feed.py` và `volume_profile.py`)

| File | Số dòng | Ghi chú |
| --- | --- | --- |
| `agency.txt` | 8 | |
| `routes.txt` | 127 | 124 bus (`route_type` 3), 3 tuyến `route_type` 0: 901 METRO Blue Line, 902 METRO Green Line, 906 Airport Shuttle (tàu giữa hai nhà ga sân bay). `route_id` là chuỗi số, ví dụ `18`, `901`. Không có tuyến 21 |
| `stops.txt` | 8.183 | 8.155 `location_type` 0, 20 loại 3 (generic node), 6 loại 2 (entrance), 2 loại 1 (station) |
| `trips.txt` | 20.220 | `trip_id` là chuỗi số (ví dụ `1361959`). Có cột không chuẩn `direction` (NB/SB/EB/WB) |
| `stop_times.txt` | 872.717 | Tối đa 141 trạm mỗi chuyến; giờ lớn nhất là 26:xx. `timepoint`: 713.887 dòng = 0, 158.830 dòng = 1 |
| `shapes.txt` | 320.611 điểm | 600 shape |
| `calendar.txt` | 19 `service_id` | |
| `calendar_dates.txt` | 25 | Toàn bộ `exception_type = 2` (bỏ chạy) |
| `vehicles.txt` | 1.233 | Chỉ có xe buýt, kèm sức chứa ngồi và đứng |
| `feed_info.txt` | 1 | |
| Block | 2.580 | `block_id` là duy nhất theo `service_id` (không block nào thuộc hai service) |

| Chỉ số theo ngày | Thứ Ba 2026-09-29 | Thứ Bảy 2026-10-03 | Chủ nhật 2026-10-04 |
| --- | --- | --- | --- |
| Chuyến chạy trong ngày | 8.028 | 5.960 | 5.259 |
| Block chạy trong ngày (bus / LRT) | 1.118 (1.094 / 24) | 667 (643 / 24) | 596 (572 / 24) |
| Số xe buýt cần khi gán theo §4 | 597 | 380 | 336 |
| Xe đồng thời lúc cao điểm | 606 (16:38) | 396 | 353 |
| Block sớm nhất bắt đầu / muộn nhất kết thúc | 02:32 / 26:28 | 02:32 / 26:34 | 02:32 / 26:29 |

Bounding box của trạm (`location_type = 0`): lon −93,730 … −92,806; lat 44,707 … 45,330.

### 2.3 File được dùng và file bị bỏ qua

DR-02 đã được điều chỉnh theo feed này: feed không có `frequencies.txt`, nhưng có `vehicles.txt`.

| File | Dùng | Bảng đích (DOC-14) | Ai dùng thêm |
| --- | --- | --- | --- |
| `agency.txt` | Có | `dw.dim_agency` | |
| `routes.txt` | Có | `dw.dim_route` | simulator |
| `stops.txt` | Có | `dw.dim_stop` | simulator |
| `trips.txt` | Có | `dw.gtfs_trip` | simulator |
| `stop_times.txt` | Có | `dw.gtfs_stop_time` | simulator |
| `calendar.txt` | Có | `dw.gtfs_calendar` | simulator |
| `calendar_dates.txt` | Có | `dw.gtfs_calendar_date` | simulator |
| `shapes.txt` | Có | `dw.gtfs_shape` | simulator (nội suy vị trí) |
| `feed_info.txt` | Có | các cột `publisher_*` của `dw.gtfs_feed_version` | |
| `vehicles.txt` | Có | `dw.dim_vehicle` (`source = 'FEED'`) | simulator (§4) |
| `levels.txt`, `pathways.txt` | Không | — | Chỉ phục vụ chỉ đường trong nhà ga |
| `linked_datasets.txt` | Không | — | Trỏ tới feed realtime thật, dự án dùng simulator |
| File khác không có trong bảng | Không | — | `GtfsStaticLoadJob` ghi tên file vào `validation_report.ignored_files` |

### 2.4 Ánh xạ cột

Quy tắc chung khi nạp:

1. Đọc CSV bằng UTF-8, bỏ BOM ở đầu file. Tên cột so khớp chính xác; cột thừa bị bỏ qua và liệt kê trong `validation_report`.
2. Chuỗi rỗng thành `NULL`. Riêng cột có giá trị mặc định theo đặc tả GTFS (`location_type`, `wheelchair_boarding`, `wheelchair_accessible`, `pickup_type`, `drop_off_type`) thì rỗng thành giá trị mặc định (0).
3. Ngày `YYYYMMDD` thành `DATE`. Giờ `HH:MM:SS` thành **số giây kể từ "noon minus 12h"** (§3), có thể ≥ 86.400.
4. Màu (`route_color`, `route_text_color`) **chuyển thành chữ hoa**, vì feed ghi chữ thường mà cột có CHECK `^[0-9A-F]{6}$`.
5. `timepoint`: `0` thành `false`, rỗng hoặc `1` thành `true`.
6. Boolean của `vehicles.txt` (`low_floor`) là `True`/`False`.

| File.cột | Cột đích | Chuyển đổi |
| --- | --- | --- |
| `agency.*` | `dim_agency.*` cùng tên | `agency_email` bỏ qua |
| `routes.route_id, agency_id, route_short_name, route_long_name, route_desc, route_type, route_sort_order` | `dim_route` cùng tên | |
| `routes.route_color, route_text_color` | `dim_route` cùng tên | chữ hoa |
| (suy ra) | `dim_route.display_name` | `route_short_name`, nếu rỗng thì `route_long_name`, nếu rỗng nữa thì `route_id` |
| (suy ra) | `dim_route.typical_headway_seconds` | DOC-14 §6 |
| `routes.route_url` | — | bỏ qua |
| `stops.stop_lat, stop_lon` | `dim_stop.lat, lon` | NULL được phép khi `location_type` là 3 hoặc 4 |
| `stops.stop_id, stop_code, stop_name, stop_desc, location_type, parent_station, wheelchair_boarding, platform_code` | `dim_stop` cùng tên | |
| `stops.stop_url, level_id, zone_id` | — | bỏ qua |
| `trips.direction` | `gtfs_trip.direction_label` | cột không chuẩn, chỉ để hiển thị |
| `trips.route_id, service_id, trip_id, trip_headsign, direction_id, block_id, shape_id, wheelchair_accessible` | `gtfs_trip` cùng tên | |
| `trips.branch_letter, boarding_type` | — | bỏ qua |
| `stop_times.arrival_time, departure_time` | `gtfs_stop_time.arrival_seconds, departure_seconds` | giây, §3 |
| `stop_times.trip_id, stop_id, stop_sequence, pickup_type, drop_off_type, timepoint, shape_dist_traveled` | `gtfs_stop_time` cùng tên | |
| `shapes.shape_pt_lat, shape_pt_lon` | `gtfs_shape.lat, lon` | |
| `calendar.*`, `calendar_dates.*` | `gtfs_calendar`, `gtfs_calendar_date` | `0/1` thành boolean |
| `vehicles.vehicle_description` | `dim_vehicle.vehicle_model` | ví dụ `40LFBUS` |
| `vehicles.vehicle_id, vehicle_label, seated_capacity, standing_capacity, low_floor, wheelchair_access, fuel` | `dim_vehicle` cùng tên | `capacity` là cột sinh (ngồi + đứng) |
| `vehicles.door_count, door_width, bike_capacity, num_carriages` | — | bỏ qua |
| `feed_info.feed_publisher_name, feed_version` | `gtfs_feed_version.publisher_name, publisher_feed_version` | |

`dim_vehicle` không theo phiên bản feed: nạp feed mới thì upsert theo `vehicle_id`, không xóa xe cũ. Xe xuất hiện trong realtime mà không có trong `vehicles.txt` được thêm với `source = 'REALTIME'` và sức chứa NULL (FR-01.6).

### 2.5 Điều kiện feed hợp lệ

`GtfsStaticLoadJob` (DOC-21) từ chối feed (`status = 'REJECTED'`) nếu gặp một trong các lỗi sau. Mọi lỗi ghi vào `validation_report`:

- Thiếu một file bắt buộc của DR-02, hoặc thiếu cột bắt buộc theo đặc tả GTFS.
- Có nhiều hơn một `agency_timezone`.
- Vi phạm ràng buộc của DOC-14 (khóa trùng, khóa ngoại trip → route, stop_time → stop, giờ không parse được, màu sai định dạng).
- Không tính được `valid_from`/`valid_to`, hoặc có một thứ trong tuần mà không ngày nào trong khoảng hiệu lực có chuyến. Khi đó ánh xạ ngày `auto` của simulator (DR-08) không chọn được ngày.

Feed đã hết hạn (`valid_to` ở quá khứ) **không** bị từ chối, vì ánh xạ ngày của DR-08 vẫn chọn được một ngày cùng thứ trong khoảng hiệu lực. Job chỉ ghi cảnh báo `feed_expired` vào `validation_report`.

## 3. Thời gian và múi giờ

Quy tắc (DR-09):

1. Mọi cột thời điểm trong DB là `TIMESTAMPTZ`, lưu UTC. Mọi JVM chạy với `TZ=UTC` (DOC-29), vì Spring Batch ghi `TIMESTAMP` không có múi giờ.
2. `service_date` là `DATE` theo giờ địa phương của agency. Trong message, `start_date` (`YYYYMMDD`) chính là `service_date`.
3. Giờ GTFS là số giây kể từ **12:00 trưa địa phương của `service_date` trừ 12 giờ**, không phải nửa đêm địa phương. Hai mốc này khác nhau vào ngày đổi giờ.
4. Code Java: `GtfsTime.toInstant(LocalDate serviceDate, int seconds, ZoneId zone)` trong `common`:
   ```java
   return serviceDate.atTime(LocalTime.NOON).atZone(zone).toInstant()
       .minus(Duration.ofHours(12)).plusSeconds(seconds);
   ```
   SQL tương ứng: `dw.gtfs_time_to_ts(date, int, text)` (DOC-14 §3).
5. `hour_of_day` và `day_of_week` trong analytics tính theo giờ địa phương của agency: `extract(hour FROM ts AT TIME ZONE 'America/Chicago')`.

Bảng ví dụ dưới đây là **test bắt buộc** cho cả bản Java lẫn bản SQL. Bản SQL đã chạy thử và cho đúng cả 6 dòng.

| `service_date` | Giờ GTFS | Giây | Kết quả UTC | Giờ địa phương | Ghi chú |
| --- | --- | --- | --- | --- | --- |
| 2026-09-29 | 16:16:00 | 58.560 | 2026-09-29T21:16:00Z | 16:16 CDT | Ngày thường (tuyến 18, trip 1361959, trạm 51631) |
| 2026-09-26 | 25:10:00 | 90.600 | 2026-09-27T06:10:00Z | 01:10 CDT ngày 27 | Giờ vượt 24:00 |
| 2026-11-01 | 01:30:00 | 5.400 | 2026-11-01T07:30:00Z | 01:30 CST | Ngày lùi giờ: đây là lần 01:30 **thứ hai** |
| 2026-11-01 | 08:00:00 | 28.800 | 2026-11-01T14:00:00Z | 08:00 CST | Tính từ nửa đêm sẽ ra 13:00Z, **sai** |
| 2027-03-14 | 02:30:00 | 9.000 | 2027-03-14T07:30:00Z | 01:30 CST | Ngày tiến giờ: 02:30 không tồn tại; theo đặc tả ra 01:30 CST |
| 2027-03-14 | 08:00:00 | 28.800 | 2027-03-14T13:00:00Z | 08:00 CDT | |

## 4. Gán xe cho block

GTFS static không nói xe nào chạy chuyến nào. Simulator cần một `vehicle_id` ổn định cho mỗi block để VehiclePosition liền mạch giữa các chuyến của cùng một xe, và để bunching so sánh đúng từng cặp xe. Quy tắc dưới đây là **tất định**: cùng feed và cùng ngày thì luôn cho cùng kết quả.

1. **Tập block của ngày D:** các block có ít nhất một chuyến thuộc `service_ids_on(D)`. Mỗi block có `start` = giờ xuất phát sớm nhất và `end` = giờ đến muộn nhất trong các chuyến của block (giây GTFS).
2. **Block xe buýt** (mọi chuyến có `route_type ≠ 0`):
   - Sắp danh sách `vehicles.txt` theo `vehicle_id` (so sánh chuỗi). Chia làm hai nửa: nửa đầu gồm `ceil(n/2)` xe (617 xe), nửa sau gồm phần còn lại (616 xe).
   - Ngày có `D.toEpochDay()` chẵn dùng nửa đầu, ngày lẻ dùng nửa sau. **Lý do:** block muộn nhất của ngày D kết thúc lúc 26:34 (02:34 ngày D+1), còn block sớm nhất của ngày D+1 bắt đầu lúc 02:32. Nếu hai ngày dùng chung một tập xe thì một xe có thể chạy hai block cùng lúc. Hai nửa rời nhau nên không cần lưu trạng thái qua ngày.
   - Sắp block theo `(start, block_id)`. Với mỗi block, lấy xe có `vehicle_id` nhỏ nhất trong nửa đang dùng thỏa `end_của_block_trước + min_layover ≤ start`. `min_layover` mặc định 600 giây (`pti.sim.vehicle.min-layover=10m`).
   - Nếu nửa đội xe không đủ, block còn lại dùng id tổng hợp `BUS-<block_id>`. Simulator ghi log WARN một lần mỗi ngày và tăng metric `pti_sim_synthetic_vehicles`. Với feed hiện tại, ngày thường cần 597 xe nên trường hợp này không xảy ra.
3. **Block light rail** (`route_type = 0`; không block nào trộn bus và LRT): `vehicles.txt` không có tàu điện, nên dùng id tổng hợp `LRV-<A|B>-<block_id>`, trong đó `A` cho ngày chẵn và `B` cho ngày lẻ, cùng lý do như trên. Các xe này không có trong `dim_vehicle` từ feed. ETL tạo chúng với `source = 'REALTIME'` và sức chứa NULL, nên luồng này luôn kiểm thử được FR-01.6.
4. **Hệ số tải > 1** (kịch bản `load-ramp`, EXP-05, EXP-07): *sửa 2026-09-27 theo DR-68*, không nhân bản xe hay chuyến. Simulator chia chu kỳ phát cho hệ số tải (DOC-25 §6.5), nên tập xe và phép gán ở trên không đổi.

Test bắt buộc (simulator, trên feed thật): (a) ngày thường 2026-09-29 dùng đúng 597 xe buýt và 24 LRV; (b) trong ba ngày liên tiếp không có `vehicle_id` nào chạy hai block chồng thời gian; (c) chạy lại hai lần cho cùng kết quả.

## 5. Database ticketing nguồn

### 5.1 Vị trí và vai trò

`ticketing_source` nằm trên instance `pg-source` (`wal_level=logical`), owner là `ticketing_owner` (DR-64). Database này mô phỏng hệ thống bán vé thật: simulator là "ứng dụng bán vé", Debezium đọc WAL qua publication `pti_ticketing`. Role, database và mật khẩu do script bootstrap tạo (DOC-17 §3). Migration Flyway bộ `ticketing` chỉ tạo object.

### 5.2 DDL

File `backend/db/src/main/resources/db/migration/ticketing/V1__ticketing_schema.sql`:

```sql
-- ticketing_source, run by ticketing_owner. Simulated ticketing system of record (DR-06).

CREATE FUNCTION public.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

CREATE TABLE public.sale_point (
  sale_point_id TEXT        PRIMARY KEY CHECK (sale_point_id ~ '^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$'),
  name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  kind          TEXT        NOT NULL CHECK (kind IN ('KIOSK', 'ONBOARD', 'APP')),
  stop_id       TEXT        NULL,
  route_id      TEXT        NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (kind <> 'KIOSK'   OR stop_id  IS NOT NULL),
  CHECK (kind <> 'ONBOARD' OR route_id IS NOT NULL)
);

CREATE TABLE public.ticket_transaction (
  transaction_id UUID          PRIMARY KEY,
  sale_point_id  TEXT          NOT NULL REFERENCES public.sale_point (sale_point_id),
  route_id       TEXT          NULL,
  stop_id        TEXT          NULL,
  ticket_type    TEXT          NOT NULL CHECK (ticket_type IN ('SINGLE', 'DAY', 'MONTH')),
  txn_type       TEXT          NOT NULL CHECK (txn_type IN ('SALE', 'REFUND')),
  amount         NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  currency       CHAR(3)       NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
  refund_of      UUID          NULL REFERENCES public.ticket_transaction (transaction_id),
  customer_ref   TEXT          NULL,  -- simulated PII, dropped by the ETL (DR-60)
  status         TEXT          NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'VOIDED')),
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CHECK ((txn_type = 'REFUND') = (refund_of IS NOT NULL)),
  CHECK (refund_of IS DISTINCT FROM transaction_id)
);

CREATE INDEX ticket_transaction_created_at_idx ON public.ticket_transaction (created_at);
CREATE INDEX ticket_transaction_sale_point_idx ON public.ticket_transaction (sale_point_id, created_at);
CREATE INDEX ticket_transaction_refund_of_idx  ON public.ticket_transaction (refund_of) WHERE refund_of IS NOT NULL;

CREATE TRIGGER sale_point_updated_at BEFORE UPDATE ON public.sale_point
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER ticket_transaction_updated_at BEFORE UPDATE ON public.ticket_transaction
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Debezium heartbeat target (DR-64): keeps the replication slot moving when ticketing is idle.
CREATE TABLE public.debezium_heartbeat (
  id SMALLINT    PRIMARY KEY CHECK (id = 1),
  ts TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO public.debezium_heartbeat (id) VALUES (1);

-- Full row images so that UPDATE and DELETE events carry created_at, from which the
-- warehouse business key (sale_date, transaction_id) is derived.
ALTER TABLE public.sale_point         REPLICA IDENTITY FULL;
ALTER TABLE public.ticket_transaction REPLICA IDENTITY FULL;

-- Created here rather than by Debezium (publication.autocreate.mode=disabled), so the
-- connector user does not need to own the tables.
CREATE PUBLICATION pti_ticketing
  FOR TABLE public.sale_point, public.ticket_transaction, public.debezium_heartbeat;
```

Giải thích các lựa chọn:

- **`REPLICA IDENTITY FULL`:** với mặc định (chỉ PK), event `UPDATE`/`DELETE` chỉ mang khóa cũ, nên event xóa không có `created_at`. Warehouse cần `created_at` để tính `sale_date`, là một phần của khóa `(sale_date, transaction_id)`. Chi phí: WAL của mỗi update/delete lớn hơn một dòng. Với khoảng 60 nghìn giao dịch mỗi ngày thì không đáng kể.
- **`CHECK ((txn_type = 'REFUND') = (refund_of IS NOT NULL))`:** hoàn vé bắt buộc trỏ tới giao dịch gốc, và giao dịch bán không được có `refund_of`.
- **`customer_ref`:** dữ liệu cá nhân mô phỏng (chuỗi dạng `cust-<8 hex>`). ETL loại bỏ trường này ngay ở processor (DR-60).
- **`debezium_heartbeat`:** giữ cho replication slot tiến lên khi ticketing ít giao dịch, trong khi `pti_sim` trên cùng instance ghi WAL liên tục (DR-64).

### 5.3 Publication và quyền của Debezium

Publication `pti_ticketing` do migration tạo, connector đặt `publication.autocreate.mode=disabled` (DOC-09 §5.1). User `debezium` có `LOGIN REPLICATION` (script bootstrap), `SELECT` trên ba bảng và `UPDATE (ts)` trên `debezium_heartbeat`. Quyền này đủ để chụp snapshot ban đầu, đọc slot và chạy `heartbeat.action.query`. User này không có quyền ghi dữ liệu nghiệp vụ.

File `backend/db/src/main/resources/db/migration/ticketing/R__grants.sql`:

```sql
-- ticketing_source grants (DOC-17). Repeatable: Flyway re-runs it whenever this file changes.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO source_simulator, debezium, experiment_runner',
                 current_database());
END $$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM source_simulator, debezium, experiment_runner;
GRANT USAGE ON SCHEMA public TO source_simulator, debezium, experiment_runner;

-- source-simulator: the application that "owns" the business data.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.sale_point, public.ticket_transaction TO source_simulator;

-- Debezium: snapshot (SELECT) and heartbeat.action.query (UPDATE on the heartbeat row).
GRANT SELECT ON public.sale_point, public.ticket_transaction, public.debezium_heartbeat TO debezium;
GRANT UPDATE (ts) ON public.debezium_heartbeat TO debezium;

-- Experiment runner: ground truth for ticketing (DR-28).
GRANT SELECT ON public.sale_point, public.ticket_transaction TO experiment_runner;
```

Đã kiểm tra trên PostgreSQL 17: `debezium` tạo được slot `pgoutput`, đọc được thay đổi qua publication, cập nhật được heartbeat, **không** INSERT được vào `ticket_transaction` và **không** kết nối được `pti_sim`. `source_simulator` **không** ghi được `debezium_heartbeat` và **không** DROP hoặc tạo được bảng.

### 5.4 Dữ liệu mô phỏng

| Thuộc tính | Quy ước |
| --- | --- |
| `sale_point_id` | `KIOSK-<nnn>`: kiosk tại trạm, `nnn` từ `001`, đặt tại các trạm có nhiều lượt khởi hành nhất trong ngày thường (mặc định 60 kiosk). `ONBOARD-<route_id>`: máy bán vé trên xe, một máy cho mỗi tuyến bus (124). `APP-IOS`, `APP-ANDROID`, `APP-WEB`: bán qua ứng dụng |
| `stop_id` / `route_id` của giao dịch | Kiosk: `stop_id` của kiosk. Onboard: `route_id` của máy và `stop_id` NULL. App: cả hai NULL |
| `ticket_type`, `amount` (USD, mô phỏng theo bảng giá Metro Transit) | `SINGLE`: $2.00, riêng giờ cao điểm (06:00–09:00 và 15:00–18:30 ngày thường) là $2.50. `DAY`: $5.00. `MONTH`: `pti.sim.ticketing.fare.month`, mặc định $76.00 |
| Tỉ lệ loại vé | SINGLE 80%, DAY 15%, MONTH 5% (cấu hình được) |
| Hoàn vé | 1% giao dịch bán được hoàn trong vòng 30 phút: sinh dòng `REFUND` mới với `amount` bằng giao dịch gốc |
| `VOIDED` | 0,2% giao dịch bị hủy ngay sau khi tạo (UPDATE `status`) để có event `u` |
| Xóa vật lý | 0,05% giao dịch bị DELETE sau 1–5 phút để có event `d` (FR-03.3) |
| Tốc độ | Theo giờ trong ngày, cao điểm khoảng 2 giao dịch/giây, trung bình khoảng 0,7 (FR-13.2). Hai kịch bản `ticket-spike` và `refund-burst` nhân tốc độ (DOC-25) |

## 6. Ledger của simulator (ground truth)

### 6.1 Vai trò

Ledger trả lời câu hỏi "đáng lẽ warehouse phải có những gì" (DR-28). Ledger nằm trong `pti_sim` trên `pg-source`, nên xóa và dựng lại warehouse (EXP-04, UC-18) không làm mất ground truth (DR-64).

Quy tắc ghi:

1. **Chỉ ghi message đã được Kafka xác nhận.** Simulator ghi ledger trong callback thành công của `KafkaTemplate.send`, kèm topic, partition và offset. Message gửi thất bại không có trong ledger, nên không bị tính là "mất".
2. Ghi theo lô (mặc định 500 dòng hoặc 200 ms) bằng `INSERT` nhiều dòng. Hàng đợi ledger đầy (mặc định 50.000 dòng) thì producer chậm lại (back-pressure), **không bao giờ bỏ dòng**.
3. Một TripUpdate sinh nhiều business key (mỗi `stop_time_update` một key), nên cột là `business_keys TEXT[]`.
4. Message cố ý sai (`intended_invalid = true`) vẫn được ghi, kèm `invalid_kind` (`malformed_json`, `schema_violation`, `out_of_bbox`, `unknown_route`, `future_timestamp`, `unknown_schema_version`; danh sách đầy đủ ở DOC-25). Với `malformed_json` thì không tính được hash nên `payload_hash` là NULL.
5. Message gửi lại (kịch bản `duplicates`) có `is_resend = true` và `resend_of` trỏ tới `message_id` gốc.

### 6.2 Định dạng business key

Chuỗi này dùng chung cho ledger và experiment runner. Class `BusinessKey` trong `common` là nơi duy nhất sinh ra nó:

| Entity | Định dạng | Ví dụ |
| --- | --- | --- |
| VehiclePosition | `<vehicle_id>\|<event_timestamp RFC 3339, mili giây, Z>` | `2050\|2026-09-29T21:19:05.000Z` |
| TripUpdate (mỗi `stop_time_update`) | `<service_date YYYY-MM-DD>\|<trip_id>\|<stop_sequence>` | `2026-09-29\|1361959\|14` |
| Giao dịch vé | `<sale_date YYYY-MM-DD>\|<transaction_id>` | `2026-09-29\|3f2b8c1e-7d4a-4e51-9b0c-2a6f1d8e9c01` |

Ticketing không ghi ledger. Ground truth của ticketing chính là `ticketing_source` (DR-28).

### 6.3 DDL

File `backend/db/src/main/resources/db/migration/sim/V1__sim_ledger.sql`:

```sql
-- pti_sim, run by sim_owner. Ground truth for loss and duplicate measurement (DR-28, DR-64).

CREATE SCHEMA sim AUTHORIZATION sim_owner;

CREATE TABLE sim.sim_scenario_run (
  run_id            UUID        PRIMARY KEY,
  scenario          TEXT        NOT NULL CHECK (scenario ~ '^[a-z][a-z0-9-]{1,40}$'),
  params            JSONB       NOT NULL DEFAULT '{}'::jsonb,
  status            TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'FAILED')),
  requested_by      TEXT        NOT NULL,  -- 'user:<username>' | 'experiment:<EXP-xx>/<run>' | 'cli'
  experiment_run_id TEXT        NULL,
  started_at        TIMESTAMPTZ NOT NULL,
  planned_end_at    TIMESTAMPTZ NULL,
  ended_at          TIMESTAMPTZ NULL,
  CHECK (ended_at IS NULL OR ended_at >= started_at),
  CHECK ((status = 'RUNNING') = (ended_at IS NULL))
);
CREATE INDEX sim_scenario_run_started_idx ON sim.sim_scenario_run (started_at);

-- One row per message acknowledged by Kafka (written from the producer send callback).
CREATE TABLE sim.sim_ledger (
  produced_at      TIMESTAMPTZ NOT NULL,
  message_id       UUID        NOT NULL,
  entity_type      TEXT        NOT NULL CHECK (entity_type IN ('VEHICLE_POSITION', 'TRIP_UPDATE')),
  kafka_topic      TEXT        NOT NULL,
  kafka_partition  INT         NOT NULL,
  kafka_offset     BIGINT      NOT NULL,
  business_keys    TEXT[]      NOT NULL CHECK (cardinality(business_keys) >= 1),
  event_timestamp  TIMESTAMPTZ NOT NULL,
  schema_version   SMALLINT    NOT NULL,
  payload_hash     CHAR(64)    NULL,      -- NULL only when the payload is deliberately not valid JSON
  intended_invalid BOOLEAN     NOT NULL DEFAULT false,
  invalid_kind     TEXT        NULL,
  is_resend        BOOLEAN     NOT NULL DEFAULT false,
  resend_of        UUID        NULL,
  scenario_run_id  UUID        NULL,
  PRIMARY KEY (produced_at, message_id),
  CHECK (intended_invalid = (invalid_kind IS NOT NULL)),
  CHECK (is_resend = (resend_of IS NOT NULL)),
  CHECK (payload_hash IS NOT NULL OR invalid_kind IS NOT DISTINCT FROM 'malformed_json')
) PARTITION BY RANGE (produced_at);

CREATE TABLE sim.sim_ledger_default PARTITION OF sim.sim_ledger DEFAULT;

-- Partition maintenance, called by source-simulator at startup and hourly (retention
-- pti.sim.ledger.retention, default 2 days). Day boundaries are UTC.
CREATE FUNCTION sim.ensure_ledger_partitions(p_from DATE, p_to DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_day     DATE := p_from;
  v_name    TEXT;
  v_created INT  := 0;
BEGIN
  IF p_to < p_from OR p_to - p_from > 31 THEN
    RAISE EXCEPTION 'invalid range % .. %', p_from, p_to USING ERRCODE = '22023';
  END IF;
  WHILE v_day <= p_to LOOP
    v_name := 'sim_ledger_p' || to_char(v_day, 'YYYYMMDD');
    IF to_regclass('sim.' || v_name) IS NULL THEN
      EXECUTE format(
        'CREATE TABLE sim.%I PARTITION OF sim.sim_ledger FOR VALUES FROM (%L) TO (%L)',
        v_name, v_day::timestamp AT TIME ZONE 'UTC', (v_day + 1)::timestamp AT TIME ZONE 'UTC');
      v_created := v_created + 1;
    END IF;
    v_day := v_day + 1;
  END LOOP;
  RETURN v_created;
END $$;

CREATE FUNCTION sim.drop_ledger_partitions_before(p_cutoff DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
  v_part    RECORD;
  v_dropped INT := 0;
BEGIN
  FOR v_part IN
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'sim.sim_ledger'::regclass
      AND c.relname ~ '^sim_ledger_p[0-9]{8}$'
      AND to_date(substring(c.relname FROM '[0-9]{8}$'), 'YYYYMMDD') < p_cutoff
  LOOP
    EXECUTE format('ALTER TABLE sim.sim_ledger DETACH PARTITION sim.%I', v_part.relname);
    EXECUTE format('DROP TABLE sim.%I', v_part.relname);
    v_dropped := v_dropped + 1;
  END LOOP;
  DELETE FROM sim.sim_ledger_default WHERE produced_at < p_cutoff::timestamp AT TIME ZONE 'UTC';
  RETURN v_dropped;
END $$;

REVOKE ALL ON FUNCTION sim.ensure_ledger_partitions(DATE, DATE)  FROM PUBLIC;
REVOKE ALL ON FUNCTION sim.drop_ledger_partitions_before(DATE)   FROM PUBLIC;

SELECT sim.ensure_ledger_partitions(current_date - 1, current_date + 2);
```

File `backend/db/src/main/resources/db/migration/sim/R__grants.sql`:

```sql
-- pti_sim grants (DOC-17). Repeatable.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO source_simulator, experiment_runner', current_database());
END $$;

GRANT USAGE ON SCHEMA sim TO source_simulator, experiment_runner;

GRANT SELECT, INSERT, UPDATE ON sim.sim_scenario_run TO source_simulator;
GRANT INSERT ON sim.sim_ledger TO source_simulator;
GRANT EXECUTE ON FUNCTION sim.ensure_ledger_partitions(DATE, DATE), sim.drop_ledger_partitions_before(DATE)
  TO source_simulator;

GRANT SELECT ON sim.sim_scenario_run, sim.sim_ledger TO experiment_runner;
```

- `source_simulator` chỉ có `INSERT` trên ledger, không có `UPDATE`/`DELETE`. Ledger là bản ghi chỉ thêm.
- Partition theo ngày UTC của `produced_at`. Simulator gọi `ensure_ledger_partitions(today − 1, today + 2)` khi khởi động và mỗi giờ, và gọi `drop_ledger_partitions_before(today − retention)`.
- `pti.sim.ledger.retention` mặc định là **2 ngày**. Thực nghiệm phải đọc ledger trong vòng 48 giờ sau khi kịch bản kết thúc. Runner sao chép phần ledger cần dùng vào thư mục kết quả của lần chạy (DOC-45).

### 6.4 Kích thước (đã đo)

Khoảng **368 byte mỗi dòng** (heap cộng PK). Ngày thường có khoảng 6,8 triệu message GTFS-rt, tức khoảng 2,5 GB mỗi ngày. Với retention 2 ngày thì tối đa 3 partition cùng tồn tại, khoảng 7,5 GB (DOC-10 §3.3).

## 7. Ánh xạ message → cột fact

### 7.1 VehiclePosition → `dw.fact_vehicle_position` và `dw.vehicle_position_latest`

| Nguồn (DOC-09 §2–3) | Cột | Ghi chú |
| --- | --- | --- |
| `payload.start_date` | `service_date` | `YYYYMMDD` → `DATE` |
| `payload.vehicle_id` | `vehicle_id` | Nếu chưa có trong `dim_vehicle` thì thêm với `source = 'REALTIME'` |
| `event_timestamp` (envelope) | `event_timestamp` | Làm tròn tới mili giây |
| `payload.trip_id, route_id, direction_id, lat, lon, bearing, speed_mps, current_stop_sequence, stop_id, current_status` | cùng tên | |
| `payload.occupancy_status` | `occupancy_status` | Chỉ có từ `schema_version` 2; v1 thì NULL |
| `schema_version` (envelope) | `schema_version` | |
| hash (DOC-09 §9) | `payload_hash` | |
| micro-batch hiện tại | `batch_id` | DR-63 |

`vehicle_position_latest` nhận cùng dữ liệu (trừ `schema_version`, `payload_hash`), trong cùng transaction với fact.

### 7.2 TripUpdate → `dw.fact_trip_update`

Mỗi phần tử của `stop_time_updates` thành một dòng:

| Nguồn | Cột | Quy tắc |
| --- | --- | --- |
| `payload.start_date` | `service_date` | |
| `payload.trip_id, route_id, direction_id, vehicle_id` | cùng tên | Lặp lại cho mọi dòng của message |
| `[].stop_sequence, stop_id, schedule_relationship` | cùng tên | |
| `[].arrival.time` | `arrival_time` | NULL nếu không có `arrival` |
| `[].departure.time` | `departure_time` | |
| `[].arrival.delay`, nếu không có thì `[].departure.delay` | `delay_seconds` | |
| `arrival.time − arrival.delay`, nếu không có thì tính từ `departure` | `scheduled_arrival` | Không cần tra lịch; khớp `dw.gtfs_time_to_ts` của lịch |
| `coalesce(arrival.time, departure.time) ≤ event_timestamp` | `is_observed` | DR-13 |
| `event_timestamp` (envelope) | `event_timestamp` | |
| hash của cả message | `payload_hash` | Mọi dòng của một message dùng chung hash |

Ví dụ với message ở DOC-09 §4 (event 21:19:30Z): dòng seq 14 có `arrival_time` 21:19:12Z, `delay_seconds` 192, `scheduled_arrival` 21:16:00Z, `is_observed = true`. Dòng seq 15 có `arrival_time` 21:20:20Z, `is_observed = false`.

### 7.3 CDC ticketing → `dw.fact_ticket_sales` và `dw.dim_sale_point`

| Nguồn (DOC-09 §5.2) | Cột | Quy tắc |
| --- | --- | --- |
| `created_at` | `sale_date` | `(created_at AT TIME ZONE 'America/Chicago')::date` |
| `transaction_id, sale_point_id, route_id, stop_id, ticket_type, txn_type, currency, refund_of, status, created_at` | cùng tên | |
| `amount` (chuỗi, `decimal.handling.mode=string`) | `amount` | `new BigDecimal(s)`, không qua `double` |
| `updated_at` | `source_updated_at` | |
| `__lsn` | `source_lsn` | Guard thứ tự (DOC-14 §7) |
| `__source_ts_ms` | `event_timestamp` | |
| `__op = 'd'` hoặc `__deleted = "true"` | `is_deleted = true` | Không xóa vật lý |
| `customer_ref` | — | **Bỏ tại processor** (DR-60), không vào hash, DLQ hay log |

`ticketing.sale_points.cdc` → `dim_sale_point` theo cùng quy tắc `__lsn` và `__op`. Nếu một giao dịch đến trước điểm bán của nó thì ETL tạo dòng `INFERRED` giữ chỗ (DOC-09 §5.3, DOC-14 §7).

## 8. Checklist cho người triển khai

- [ ] `common`: `GtfsTime` pass bảng test §3; `BusinessKey` sinh đúng định dạng §6.2; hash khớp test ở DOC-09 §9.
- [ ] Parser GTFS (dùng chung cho simulator và `GtfsStaticLoadJob`) đọc đúng số dòng ở §2.2 trên feed thật.
- [ ] Simulator: gán xe theo §4 pass cả ba test.
- [ ] Migration `ticketing` và `sim` chạy sạch trên DB trống qua `db-migrate`, chạy lại không lỗi.
- [ ] Debezium chụp snapshot và stream được với đúng quyền ở §5.3, không cần thêm quyền nào.
- [ ] Ledger có đúng số dòng bằng số message đã được Kafka xác nhận (P1-11).
