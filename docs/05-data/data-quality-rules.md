# Rule chất lượng dữ liệu

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-16
> Phụ thuộc: [DOC-03](../01-product/requirements.md) (FR-02, FR-03, FR-04.4), [DOC-09](../03-architecture/messaging-contracts.md), [DOC-14](warehouse-model.md), [DOC-15](ops-and-insight-model.md), [DOC-19](../06-design/batch-and-chunk-processing.md), [DOC-20](../06-design/etl-streaming.md), [DOC-25](../06-design/source-simulator.md) §7.3, [DR](../00-decision-register.md) (DR-13, 23, 25, 63, 67, 69)
> Người dùng chính: `etl` (P2-04, P2-15), `analytics`, DOC-28 (alert), DOC-45 (EXP-03)

Tài liệu này là **danh mục rule DQ** (DQ-xx) và quy định rule nào chạy ở đâu, ra kết quả gì. Theo DR-25 có hai lớp:

- **Pre-write:** chạy trong processor, trên từng record hoặc trên cả chunk, **trước khi ghi**. Record vi phạm đi vào DLQ và không được ghi.
- **Post-write:** câu SQL chạy sau khi dữ liệu đã ghi. Kết quả vào `ops.dq_check_result`, sinh metric và alert. Lớp này **không bao giờ sửa hay xóa dữ liệu**.

Kiểm tra toàn feed khi nạp GTFS static (GV-xx) nằm ở DOC-21, không thuộc danh mục này.

## 1. Stage của DLQ

`dead_letter.stage` cho biết record bị loại ở bước nào. Quy tắc gán stage (DR-69):

| Stage | Nghĩa | Rule / nguồn lỗi |
| --- | --- | --- |
| `DESERIALIZE` | Không parse được thành JSON, hoặc envelope thiếu `schema_version`/`entity_type` | Jackson ném `JsonProcessingException` |
| `SCHEMA` | Sai cấu trúc: thiếu trường bắt buộc, sai kiểu, sai enum, trường lạ, `schema_version` không hỗ trợ | DQ-01 (JSON Schema và Bean Validation) |
| `QUALITY` | Cấu trúc đúng nhưng giá trị không hợp lý, xét trên **chính record đó** cùng dữ liệu tham chiếu tĩnh (feed ACTIVE, đồng hồ nghiệp vụ) | DQ-03…DQ-11 |
| `BUSINESS` | Vi phạm quy tắc phụ thuộc vào **record khác** trong warehouse (trạng thái giao dịch) | DQ-12, DQ-13 |
| `DEDUP` | Hai record cùng business key, cùng mốc thời gian nhưng khác nội dung trong cùng chunk | DQ-02 |
| `LOAD` | Lỗi dữ liệu khi ghi DB (SQLState 22xxx, 23xxx) ở scan mode | Không có rule; do DB báo (DR-21) |

Với stage `QUALITY`, `BUSINESS` và `DEDUP`: `rule_id` = mã rule, và `error_class` cũng bằng mã rule (khớp FR-02.3), còn `error_message` là câu tiếng Anh nêu giá trị sai. Với `DESERIALIZE`, `SCHEMA` và `LOAD`: `error_class` là tên lớp exception rút gọn (`JsonParseException`, `SchemaViolation`, `DataIntegrityViolation`), `rule_id` bằng `DQ-01` cho `SCHEMA` và `NULL` cho hai stage còn lại.

## 2. Rule pre-write

Ký hiệu nguồn: **VP** = VehiclePosition, **TU** = TripUpdate (mỗi phần tử `stop_time_updates`), **TX** = giao dịch vé (CDC), **SP** = điểm bán (CDC).

| ID | Nguồn | Stage | Mô tả | Điều kiện vi phạm | Bỏ qua khi replay? |
| --- | --- | --- | --- | --- | --- |
| DQ-01 | tất cả | SCHEMA | Đúng cấu trúc | GTFS-rt: không qua JSON Schema của `(entity_type, schema_version)` hoặc Bean Validation của DTO (DOC-09 §3–4). CDC: Bean Validation của `TicketTransactionCdc`/`SalePointCdc` (§2.2) | Không |
| DQ-02 | VP, TU, TX, SP | DEDUP | Xung đột key trong chunk | Hai record cùng business key, **cùng** `event_timestamp` (TX/SP: cùng `__lsn`) nhưng `payload_hash` khác nhau. Xem §2.1 | Không |
| DQ-03 | VP, TU | QUALITY | Tuyến tồn tại | `route_id` không có trong `dw.dim_route` của feed ACTIVE | Không |
| DQ-04 | VP, TU | QUALITY | Trạm tồn tại | `stop_id` không có trong `dw.dim_stop` của feed ACTIVE | Không |
| DQ-05 | VP, TU | QUALITY | Chuyến tồn tại và khớp tuyến | `trip_id` không có trong `dw.gtfs_trip` của feed ACTIVE, **hoặc** `route_id`/`direction_id` của record khác với của chuyến trong lịch | Không |
| DQ-06 | VP | QUALITY | Tọa độ trong vùng phục vụ | `lat`/`lon` nằm ngoài bbox của feed ACTIVE nới thêm `pti.dq.bbox-margin` (mặc định 0,1°, khoảng 8–11 km) ở mỗi phía | Không |
| DQ-07 | VP, TU | QUALITY | Event time hợp lý | `|event_timestamp − businessNow| > pti.dq.max-clock-skew` (mặc định 1 giờ). `businessNow` là đồng hồ nghiệp vụ (DR-67), **không** phải giờ thật | **Có** |
| DQ-08 | TU | QUALITY | Độ trễ hợp lý | Có `arrival.delay` hoặc `departure.delay` ngoài `±pti.dq.max-delay` (mặc định 7.200 giây). Vi phạm ở một phần tử thì **cả TripUpdate** vào DLQ (một dòng DLQ), vì các phần tử được sinh cùng một mô hình | Không |
| DQ-09 | VP, TU | QUALITY | Ngày phục vụ khớp event time | `start_date` không thuộc `{D − 1, D}` với `D` = ngày của `event_timestamp` theo giờ agency. Chuyến sau nửa đêm vẫn thuộc ngày hôm trước, nên `D − 1` hợp lệ. Rule này cũng ngăn dòng rơi vào partition DEFAULT | Không |
| DQ-10 | TX | QUALITY | Số tiền hợp lý | `amount < 0`, hoặc `amount > pti.dq.max-ticket-amount` (mặc định 500.00), hoặc `currency ≠ 'USD'` | Không |
| DQ-11 | TX | QUALITY | Loại giao dịch nhất quán | `txn_type = 'REFUND'` mà `refund_of` NULL, hoặc `txn_type = 'SALE'` mà `refund_of` khác NULL | Không |
| DQ-12 | TX | BUSINESS | Hoàn vé tham chiếu giao dịch có thật | Refund có `refund_of` không có trong `dw.fact_ticket_sales` và không có trong chunk hiện tại, **và** `businessNow − created_at > pti.dq.refund-grace` (mặc định 5 phút), **và** `__op ∈ {c, u}`. Xem §2.3 | **Có** |
| DQ-13 | TX | BUSINESS | Hoàn vé không vượt giá gốc | Giao dịch gốc tìm thấy nhưng `amount` của refund > `amount` của gốc, hoặc gốc là `REFUND` | Không |

Với TripUpdate, rule được đánh giá trên từng phần tử của `stop_time_updates`, nhưng một phần tử vi phạm thì **cả message** vào DLQ (một dòng) và không phần tử nào được ghi. Các phần tử của một TripUpdate cùng sinh từ một lần quan sát, nên ghi một phần sẽ để lại trạng thái chuyến nửa vời.

Không có rule cho `sale_point_id` lạ trong giao dịch: ETL tạo dòng `dim_sale_point` `INFERRED` (DOC-09 §5.3). `vehicle_id` lạ cũng không phải lỗi: ETL tạo `dim_vehicle` với `source = 'REALTIME'` (FR-01.6).

### 2.1 DQ-02: nhiều record cùng key trong một chunk

Trong một chunk, record được gom theo business key (DOC-09 §9). Với mỗi nhóm có nhiều hơn một record:

| Trường hợp | Xử lý | Đếm vào |
| --- | --- | --- |
| Cùng `payload_hash` | Giữ một, bỏ các bản còn lại (message gửi lại y hệt) | `records_duplicate` |
| Khác `event_timestamp` (TX/SP: khác `__lsn`) | Giữ bản có mốc lớn nhất, bỏ các bản cũ hơn. Đây là trạng thái bị thay thế chứ không phải lỗi: TripUpdate của cùng chuyến tới hai lần trong một chunk là chuyện bình thường khi consumer đang đuổi lag | `records_duplicate` |
| Cùng mốc thời gian, khác `payload_hash` | Giữ bản tới **sau** (offset lớn hơn), đưa các bản còn lại vào DLQ `DEDUP` với `rule_id = DQ-02`. Hai nội dung khác nhau cho cùng một thời điểm là dấu hiệu lỗi ở producer | `records_skipped` |

Bước này chạy **trước** khi ghi, vì một câu `INSERT … ON CONFLICT DO UPDATE` trong cùng lệnh batch không được chạm cùng một dòng hai lần (lỗi `21000`, *ON CONFLICT DO UPDATE command cannot affect row a second time*) khi writer gộp nhiều dòng trong một câu lệnh. Với `JdbcBatchItemWriter` mỗi item là một câu riêng nên không gặp lỗi này, nhưng khử trước vẫn giảm số lần ghi.

Sửa FR-02.4 (2026-09-27): bản cũ đưa **mọi** bản cũ hơn vào DLQ `QUALITY`. Khi consumer đuổi lag, cách đó làm DLQ đầy các TripUpdate hoàn toàn hợp lệ. Bản mới chỉ đưa xung đột thật vào DLQ, với stage `DEDUP`.

### 2.2 DTO của CDC

`TicketTransactionCdc` (record Java trong `etl`, Bean Validation):

| Trường | Ràng buộc |
| --- | --- |
| `transactionId` | `@NotNull` UUID |
| `salePointId` | `@NotBlank`, `@Pattern("^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$")` |
| `ticketType` | `@NotNull`, enum `SINGLE|DAY|MONTH` |
| `txnType` | `@NotNull`, enum `SALE|REFUND` |
| `amount` | `@NotNull` BigDecimal, scale ≤ 2 |
| `currency` | `@NotBlank`, 3 ký tự |
| `status` | `@NotNull`, enum `COMPLETED|VOIDED` |
| `createdAt`, `updatedAt` | `@NotNull` Instant |
| `op` (`__op`) | `@NotNull`, một trong `r, c, u, d` |
| `lsn` (`__lsn`) | `@NotNull`, `@PositiveOrZero` |
| `sourceTsMs` (`__source_ts_ms`) | `@NotNull` |

`customer_ref` **không có** trong DTO: Jackson bỏ qua trường này (`@JsonIgnoreProperties({"customer_ref"})`), để nó không bao giờ đi tiếp (DR-60). Các trường lạ khác làm message vào DLQ `SCHEMA` như GTFS-rt.

### 2.3 DQ-12: vì sao có thời gian chờ

Giao dịch gốc và refund có key khác nhau, nên có thể nằm ở hai partition khác nhau của `ticketing.sales.cdc` và được hai thread xử lý song song. Refund có thể tới trước bản gốc vài giây. Quy tắc:

1. Tìm `refund_of` trong chunk hiện tại, rồi trong `dw.fact_ticket_sales` (một câu `WHERE transaction_id = ANY(:ids)` cho cả chunk, dùng `fact_ticket_sales_txn_idx`).
2. Tìm thấy: kiểm tra DQ-13.
3. Không tìm thấy, và refund đã quá 5 phút tính từ `created_at` (theo đồng hồ nghiệp vụ): DLQ `BUSINESS`. Sau 5 phút mà bản gốc vẫn chưa tới thì không còn là chuyện thứ tự.
4. Không tìm thấy nhưng còn trong 5 phút: **vẫn ghi** refund. `fact_ticket_sales` không có FK, nên việc này an toàn. DQ-22 (post-write) kiểm tra lại sau đó và cảnh báo nếu bản gốc không bao giờ tới.
5. Snapshot (`__op = r`) và replay: bỏ qua rule, vì snapshot đọc bảng theo thứ tự PK (UUID ngẫu nhiên), nên refund thường tới trước bản gốc. DQ-22 vẫn bắt được refund mồ côi.

Simulator sinh refund sau khi bán 1–30 phút (DOC-25 §9), nên ở tải bình thường nhánh 4 hầu như không xảy ra.

### 2.4 Thứ tự chạy trong processor

```
parse envelope (DESERIALIZE)
  → validate schema (DQ-01, SCHEMA)
  → map sang record ghi (tách TripUpdate thành nhiều dòng)
  → rule theo record: DQ-03…DQ-11 (dừng ở vi phạm đầu tiên, theo thứ tự ID)
  → rule theo chunk: DQ-02, DQ-12, DQ-13
  → dedup registry (chỉ khi không replay, DR-16)
  → writer
```

Một record chỉ sinh **một** dòng DLQ, ứng với vi phạm đầu tiên. Thứ tự này cố định để kết quả EXP-03 lặp lại được: kịch bản `bad-data` sinh đúng một lỗi cho mỗi message, và stage kỳ vọng ở DOC-25 §7.4 khớp với bảng §2.

## 3. Rule post-write

Chạy bởi `DataQualityJob` (etl-batch, tasklet, `@Scheduled` + ShedLock, DOC-19). Mỗi rule là một file SQL `backend/etl/src/main/resources/sql/dq/DQ-xx.sql` trả về `violation_count` và tối đa 10 dòng mẫu.

| ID | Bảng | Phạm vi | Lịch | Mô tả | Ngưỡng alert |
| --- | --- | --- | --- | --- | --- |
| DQ-20 | mọi `dw.fact_*` | TABLE, dữ liệu 24 giờ gần nhất (theo `ingested_at`) | Mỗi giờ | `batch_id` không có trong `ops.etl_stream_batch ∪ ops.etl_batch_step` (DR-63) | > 0 |
| DQ-21 | `dw.fact_*_default` | TABLE | Mỗi giờ | Partition DEFAULT có dòng (job bảo trì partition không chạy, hoặc có dữ liệu ngày quá xa) | > 0 |
| DQ-22 | `dw.fact_ticket_sales` | TABLE, `created_at` trong 24 giờ gần nhất | Mỗi 5 phút | Refund có `refund_of` không tìm thấy, và `created_at` đã quá `refund-grace` | > 0 |
| DQ-23 | `dw.fact_trip_update` | TABLE, `service_date` hôm nay và hôm qua | Mỗi giờ | `(trip_id, stop_sequence)` không có trong `gtfs_stop_time` của feed đang ACTIVE, hoặc `stop_id` khác với trong lịch | > 0,1% số dòng |
| DQ-24 | `dw.fact_trip_update` | TABLE, như trên | Mỗi giờ | Vi phạm bất biến của DR-13: `is_observed = true` nhưng `coalesce(arrival_time, departure_time) > event_timestamp` | > 0 |
| DQ-25 | `dw.vehicle_position_latest` | TABLE | Mỗi 5 phút | Dòng có `event_timestamp` mới hơn dòng mới nhất trong `fact_vehicle_position` của cùng xe (bảng latest và fact lệch nhau) | > 0 |
| DQ-26 | `dw.dim_vehicle` | TABLE | Mỗi ngày | Số xe `source = 'REALTIME'` (xe không có trong `vehicles.txt`) | > 5% số xe (chỉ ghi nhận, severity 0) |
| DQ-27 | fact do job batch ghi | BATCH (theo `batch_id` của step) | `afterStep` của mọi step ghi fact (replay, DOC-22) | `write_count` của step bằng số dòng có `batch_id` đó trong bảng đích, trừ số dòng bị guard chặn | ≠ 0 |

Ví dụ `DQ-22.sql`:

```sql
-- DQ-22: refunds whose original sale never arrived. :now is the business clock (DR-67),
-- :grace is pti.dq.refund-grace. Returns the count and up to 10 sample keys.
WITH orphans AS (
  SELECT r.transaction_id, r.refund_of, r.created_at
  FROM dw.fact_ticket_sales r
  WHERE r.txn_type = 'REFUND'
    AND NOT r.is_deleted
    AND r.created_at >= :now - interval '24 hours'
    AND r.created_at <  :now - :grace
    AND NOT EXISTS (SELECT 1 FROM dw.fact_ticket_sales s WHERE s.transaction_id = r.refund_of)
)
SELECT (SELECT count(*) FROM orphans) AS violation_count,
       (SELECT coalesce(jsonb_agg(to_jsonb(o)), '[]'::jsonb)
        FROM (SELECT * FROM orphans ORDER BY created_at LIMIT 10) o) AS sample;
```

Quy ước chung của SQL post-write:

- Chạy với `etl_writer`, chỉ đọc. Kết quả được ghi vào `ops.dq_check_result` trong transaction riêng.
- Mọi mốc "bây giờ" là tham số `:now` lấy từ `BusinessClock` (DR-67), không dùng `now()` của DB, để rule vẫn đúng khi đặt `PTI_CLOCK_OFFSET`.
- Mỗi câu phải lọc theo cột partition (`service_date`, `sale_date`) hoặc cột có index, và chạy dưới 5 giây trên dữ liệu 7 ngày ở tải nền. Test hiệu năng ở DOC-44.
- `statement_timeout = 30s` cho kết nối của job. Rule quá thời gian thì ghi log `WARN` và metric `pti_dq_check_errors_total{rule}`, không làm job fail.

## 4. Kết quả, metric và alert

| Lớp | Ghi vào | Metric (DOC-28) | Alert |
| --- | --- | --- | --- |
| Pre-write | `ops.dead_letter` (stage, `rule_id`) | `pti_dq_violations_total{source, stage, rule}` (counter) | `DlqRateHigh`: tỷ lệ DLQ của một nguồn > 1% trong 5 phút |
| Post-write | `ops.dq_check_result` (một dòng mỗi lần chạy mỗi rule, kể cả khi `violation_count = 0`) | `pti_dq_check_violations{rule}` (gauge, giá trị lần chạy gần nhất); `pti_dq_check_breached{rule}` (1 khi vượt ngưỡng ở §3, job tự tính cả ngưỡng tương đối); `pti_dq_check_last_run_timestamp_seconds{rule}`, `pti_dq_check_interval_seconds{rule}`. Các gauge đọc từ `ops.dq_check_result` mỗi 60 giây nên đúng cả sau restart | `DataQualityCheckFailed`: `pti_dq_check_breached == 1` |

Ghi cả lần chạy không có vi phạm giúp phân biệt "không có lỗi" với "rule không chạy". Alert `DataQualityCheckStale` bắn khi một rule không chạy quá 3 lần chu kỳ của nó.

- Chỉ rule có lịch (scope `TABLE`) mới phát `pti_dq_check_interval_seconds` và `pti_dq_check_last_run_timestamp_seconds`. DQ-27 (scope `BATCH`, chạy theo step) không có hai gauge này nên không bao giờ gây `DataQualityCheckStale`.
- Rule bị tắt bằng `pti.dq.rules.<ID>.enabled=false` không phát gauge nào, để tắt một rule không làm `DataQualityCheckStale` bắn.
- DQ-26 có severity 0 (chỉ ghi nhận): kết quả vẫn ghi vào `dq_check_result` và `pti_dq_check_violations`, nhưng `pti_dq_check_breached` luôn là 0.

## 5. Thành phần và interface

Đặt trong `common` (dùng chung cho `etl` và test):

```java
package dev.pti.common.dq;

public enum DlqStage { DESERIALIZE, SCHEMA, BUSINESS, DEDUP, LOAD, QUALITY }

/** Outcome of a failed rule; message is English, includes the offending value. */
public record Violation(String ruleId, DlqStage stage, String message) {}

/** Everything a rule may read besides the record itself. Built once per chunk. */
public record RuleContext(
    Instant businessNow,            // BusinessClock.now(), DR-67
    boolean replay,                 // job parameter replay=true, DR-16
    ReferenceData reference) {}     // snapshot of the ACTIVE feed, see DOC-21 §6

/** Per-record rule. Must be pure: no I/O, no clock reads outside the context. */
public interface RecordRule<T> {
  String id();
  boolean appliesDuringReplay();
  Optional<Violation> check(T record, RuleContext ctx);
}

/** Rules that need the whole chunk (DQ-02) or one batched lookup (DQ-12, DQ-13). */
public interface ChunkRule<T> {
  String id();
  ChunkRuleResult<T> apply(List<T> records, RuleContext ctx);
}

public record ChunkRuleResult<T>(List<T> kept, List<Rejected<T>> rejected, int collapsedDuplicates) {}
public record Rejected<T>(T record, Violation violation) {}
```

`ReferenceData` (DOC-21 §6) giữ tập `route_id`, `stop_id`, map `trip_id → (route_id, direction_id)` và bbox của feed ACTIVE. Tất cả là cấu trúc bất biến, được thay nguyên khối khi feed đổi phiên bản. Với feed Minneapolis, `ReferenceData` chiếm khoảng 15 MB heap.

`RuleEngine` (trong `etl`) nhận danh sách `RecordRule` và `ChunkRule` qua Spring, chạy theo thứ tự ở §2.4, trả về `(kept, rejected)`. `rejected` đi vào `DeadLetterWriter` trong cùng transaction với chunk (DR-21).

DQ-12 và DQ-13 cần đọc DB. Chúng được hiện thực bằng `ChunkRule` có `JdbcTemplate`, gọi đúng một câu SQL cho cả chunk, và chạy trong transaction của chunk (đọc `READ COMMITTED`).

## 6. Cấu hình

| Key | Kiểu | Mặc định | Rule |
| --- | --- | --- | --- |
| `pti.dq.max-clock-skew` | Duration | `1h` | DQ-07 |
| `pti.dq.max-delay` | Duration | `2h` | DQ-08 |
| `pti.dq.bbox-margin` | double (độ) | `0.1` | DQ-06 |
| `pti.dq.max-ticket-amount` | BigDecimal | `500.00` | DQ-10 |
| `pti.dq.refund-grace` | Duration | `5m` | DQ-12, DQ-22 |
| `pti.dq.post-write.enabled` | boolean | `true` | Tắt toàn bộ `DataQualityJob` |
| `pti.dq.post-write.statement-timeout` | Duration | `30s` | §3 |
| `pti.dq.rules.<ID>.enabled` | boolean | `true` | Tắt riêng một rule (không áp cho DQ-01) |

Các key được thêm vào DOC-29 §3.

## 7. Lỗi và cách xử lý

| Tình huống | Xử lý |
| --- | --- |
| Chưa có feed ACTIVE (lần chạy đầu, `GtfsStaticLoadJob` chưa xong) | DQ-03…06 không đánh giá được. Listener GTFS-rt **không khởi động** cho tới khi có feed ACTIVE (readiness `DOWN` với lý do `no active feed`), thay vì đưa mọi record vào DLQ. Xem DOC-20 |
| Đổi phiên bản feed khi đang chạy | `ReferenceData` được thay nguyên khối giữa hai chunk. Chunk đang chạy dùng snapshot cũ tới hết |
| Rule ném exception ngoài dự kiến (lỗi lập trình) | `FATAL` theo DR-23: không coi là vi phạm dữ liệu |
| Câu SQL của DQ-12/13 gặp lỗi hạ tầng | `TRANSIENT_INFRA`: cả chunk thử lại (DR-23) |
| Rule post-write chạy quá lâu | §3: timeout, ghi lỗi, không fail job |

## 8. Test bắt buộc

Mỗi rule có unit test với bảng dữ liệu bên dưới (fixture là tuyến 18 của feed thu nhỏ trong `backend/common/src/testFixtures`, DOC-44). Mốc `businessNow = 2026-09-29T21:20:00Z`.

| # | Rule | Input | Kỳ vọng |
| --- | --- | --- | --- |
| 1 | DQ-01 | VP thiếu `vehicle_id` | DLQ `SCHEMA`, `error_message` chứa `vehicle_id` |
| 2 | DQ-01 | VP `schema_version: 3` | DLQ `SCHEMA` |
| 3 | DQ-01 | TX có trường lạ `foo` | DLQ `SCHEMA` |
| 4 | DQ-01 | TX có `customer_ref` | Hợp lệ; `customer_ref` không có trong record đã map và không có trong `raw_payload` nếu record này vào DLQ vì lý do khác |
| 5 | DQ-02 | 2 VP y hệt | 1 dòng ghi, `records_duplicate = 1`, DLQ 0 |
| 6 | DQ-02 | 2 TU cùng `(trip, stop_seq)`, event 21:19:00 và 21:19:30 | Giữ bản 21:19:30, `records_duplicate = 1`, DLQ 0 |
| 7 | DQ-02 | 2 VP cùng key, cùng event, khác `lat` | Giữ bản offset lớn hơn; bản kia DLQ `DEDUP`, `rule_id = DQ-02` |
| 8 | DQ-03 | VP `route_id = R-UNKNOWN-1` | DLQ `QUALITY`, `DQ-03` |
| 9 | DQ-04 | TU có một phần tử `stop_id = 999999` | Cả TripUpdate: một dòng DLQ `QUALITY`, `DQ-04`; không phần tử nào được ghi |
| 10 | DQ-05 | VP `trip_id` của tuyến 18 nhưng `route_id = 5` | DLQ `DQ-05` |
| 11 | DQ-06 | VP `lat 40.7128, lon -74.0060` | DLQ `DQ-06` |
| 12 | DQ-06 | VP cách mép bbox 0,05° | Hợp lệ |
| 13 | DQ-07 | VP event 23:20:00 (+2 giờ) | DLQ `DQ-07` |
| 14 | DQ-07 | VP event 20:25:00 (−55 phút) | Hợp lệ |
| 15 | DQ-07 | Như ca 13 nhưng `replay = true` | Hợp lệ |
| 16 | DQ-07 | `PTI_CLOCK_OFFSET = -12h`, event = giờ thật − 12 giờ | Hợp lệ (so với đồng hồ nghiệp vụ) |
| 17 | DQ-08 | TU có `delay = 9000` | DLQ `DQ-08` |
| 18 | DQ-09 | VP `start_date = 20260929`, event `2026-09-30T06:30:00Z` (01:30 CDT ngày 30) | Hợp lệ (chuyến sau nửa đêm) |
| 19 | DQ-09 | VP `start_date = 20260925` | DLQ `DQ-09` |
| 20 | DQ-10 | TX `amount = -2.50` | DLQ `DQ-10` |
| 21 | DQ-11 | TX `REFUND`, `refund_of = null` | DLQ `DQ-11` |
| 22 | DQ-12 | Refund, gốc có trong fact | Hợp lệ |
| 23 | DQ-12 | Refund và gốc trong cùng chunk | Hợp lệ |
| 24 | DQ-12 | Refund, không có gốc, `created_at` = now − 1 phút | Ghi; DLQ 0 |
| 25 | DQ-12 | Refund, không có gốc, `created_at` = now − 10 phút, `op = c` | DLQ `BUSINESS`, `DQ-12` |
| 26 | DQ-12 | Như ca 25 nhưng `op = r` | Ghi; DLQ 0 |
| 27 | DQ-13 | Refund 5.00 cho giao dịch 2.50 | DLQ `BUSINESS`, `DQ-13` |
| 28 | Thứ tự | VP vừa sai `route_id` vừa sai bbox | Một dòng DLQ, `rule_id = DQ-03` |
| 29 | DQ-20…27 | Mỗi rule: một fixture có đúng 1 vi phạm, một fixture sạch | `violation_count` 1 và 0; dòng `dq_check_result` được ghi ở cả hai lần |
| 30 | DQ-22 | Refund mồ côi `created_at` = now − 3 phút | Không tính (chưa hết thời gian chờ) |

Ngoài ra: test hiệu năng cho mọi rule post-write trên dữ liệu 7 ngày (§3), và EXP-03 kiểm tra rằng mỗi message lỗi của kịch bản `bad-data` rơi vào đúng stage ở DOC-25 §7.3.

## 9. Câu hỏi còn mở

Không có.
