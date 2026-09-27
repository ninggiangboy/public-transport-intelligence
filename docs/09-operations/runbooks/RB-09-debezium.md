# RB-09: Debezium: WAL bị giữ lại, connector dừng

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-09
>
> Alert: `DebeziumWalRetained` (warning > 2 GB, critical > 3,2 GB; 5 phút), `ConnectorDown` (critical, 2 phút) · Dashboard: `pti-kafka` (connector), `pti-postgres` (slot) · Liên quan: DOC-09 §5 (cấu hình connector), DOC-13 §5.3, DOC-14 §8.3 (guard LSN), DOC-43 §4.4

## Triệu chứng và ảnh hưởng

- `ConnectorDown`: connector `debezium-ticketing` hoặc task của nó không ở `RUNNING` (`pti_connect_connector_running = 0`, do `etl-stream` đọc Connect REST mỗi 15 giây). Hoặc Connect không trả lời. Giao dịch vé mới không tới Kafka; doanh thu trên dashboard đứng yên. `source-ticketing` health `DOWN`. Ức chế `DebeziumWalRetained` mức warning.
- `DebeziumWalRetained`: replication slot `pti_ticketing` giữ quá nhiều WAL trên `pg-source` (gauge `pti_source_replication_slot_retained_bytes` do simulator đo). Nghĩa là slot không tiến: connector dừng, chậm, hoặc slot mồ côi.
- **Nguy cơ chính:** vượt `max_slot_wal_keep_size = 4GB` thì Postgres **vô hiệu hóa slot** (`wal_status = 'lost'`). Connector không thể đọc tiếp; phải snapshot lại, và mọi thay đổi trong khoảng mất (đặc biệt là lệnh xóa) không còn tới warehouse. Ngưỡng critical 3,2 GB là 80% giới hạn đó.
- Không vượt giới hạn thì **không mất dữ liệu**: khi connector chạy lại, nó đọc tiếp từ slot.

## Kiểm tra

1. Trạng thái connector và task: `make connectors`, hoặc

   ```bash
   curl -s localhost:18083/connectors/debezium-ticketing/status | jq
   ```

   Task `FAILED` có trường `trace` (stack trace Java). Connect không trả lời → `make ps`, `make logs S=kafka-connect`.
2. Trạng thái slot (`make psql-src`):

   ```sql
   SELECT slot_name, active, active_pid, wal_status, safe_wal_size,
          pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained,
          confirmed_flush_lsn
   FROM pg_replication_slots;
   ```

   - `wal_status = 'lost'` → nhánh D ngay.
   - `active = false` với `slot_name` khác `pti_ticketing` → slot mồ côi (nhánh C).
   - `active = true` nhưng `retained` vẫn tăng → connector đọc được nhưng không commit offset (bước 3).
3. Connector chạy nhưng slot không tiến: offset của Connect chỉ được xác nhận về Postgres sau khi record đã ghi vào Kafka và offset đã flush. Kiểm tra Kafka (`make ps`, lỗi `TimeoutException` trong log Connect) và heartbeat (`make tail-ticketing.heartbeat`: phải có message mỗi 10 giây).
4. Lỗi hay gặp trong `trace`:

   | Lỗi | Nguyên nhân |
   | --- | --- |
   | `password authentication failed for user "debezium"` | Mật khẩu đổi mà connector chưa cập nhật (RB-12) |
   | `Connection refused` tới `pg-source` | `pg-source` dừng hoặc đang restart |
   | `replication slot "pti_ticketing" is active for PID …` | Hai task hoặc hai worker cùng đọc một slot (sau restart không sạch) |
   | `publication "pti_ticketing" does not exist` | `pg-source` được tạo lại mà chưa chạy migration `ticketing` |
   | `cannot read from logical replication slot` + `lost` | Slot đã bị vô hiệu hóa (nhánh D) |
   | `TimeoutException`, `NotLeaderOrFollowerException` | Kafka có vấn đề |

## Xử lý

**A. Task `FAILED` do lỗi tạm thời** (mạng, `pg-source` hay Kafka vừa restart). Sau khi nguyên nhân đã hết:

```bash
curl -s -X POST 'localhost:18083/connectors/debezium-ticketing/restart?includeTasks=true&onlyFailed=true'
```

Kiểm tra lại `status` sau 30 giây. Connector đọc tiếp từ offset đã lưu, không mất dữ liệu.

**B. Cấu hình hoặc quyền sai.** Mật khẩu: RB-12 (phần Debezium). Publication thiếu: `make up` (chạy lại `db-migrate` cho `ticketing_source`). Đổi cấu hình connector: sửa `connect/connectors/debezium-ticketing.json` rồi `make up` (`kafka-connect-init` PUT lại config, DOC-39). Sau đó làm như nhánh A.

**C. Slot mồ côi** (tên khác `pti_ticketing`, `active = false`, thường do thử nghiệm cũ): xóa để giải phóng WAL. Xóa slot cần quyền `REPLICATION` hoặc superuser, nên chạy bằng superuser của `pg-source`: `make psql-src SU=1`.

```sql
SELECT pg_drop_replication_slot('<slot_name>');
```

Không bao giờ xóa slot `pti_ticketing` khi `wal_status` khác `lost`.

**D. Slot bị vô hiệu hóa (`lost`) hoặc sắp bị** (critical, `retained` > 3,2 GB và connector không thể chạy lại trong ít phút).
1. Nếu vẫn còn cứu được (chưa `lost`): ưu tiên sửa nguyên nhân để connector chạy lại (nhánh A/B). Connector đọc WAL nhanh hơn tốc độ sinh, nên `retained` giảm dần.
2. Nếu đã `lost`: ghi lại thời điểm (log `pg-source`: `invalidating obsolete replication slot`) và `confirmed_flush_lsn` cuối cùng. Khoảng từ LSN đó tới lúc snapshot là khoảng mất thay đổi.
3. Dừng connector, xóa slot và offset, rồi snapshot lại:

   ```bash
   curl -s -X PUT localhost:18083/connectors/debezium-ticketing/stop
   make psql-src SU=1 Q="SELECT pg_drop_replication_slot('pti_ticketing');"
   curl -s -X DELETE localhost:18083/connectors/debezium-ticketing/offsets
   curl -s -X PUT localhost:18083/connectors/debezium-ticketing/resume
   ```

   Connector không có offset nên tạo slot mới và snapshot toàn bộ `ticket_transaction` và `sale_point` (`__op = r`). Snapshot trên cùng instance có LSN lớn hơn mọi LSN đã lưu, nên guard LSN (DOC-14 §8.3) cho phép cập nhật trạng thái mới nhất. Giao dịch mới xuất hiện đầy đủ.
4. **Giới hạn:** dòng bị **xóa** ở nguồn trong khoảng mất không có trong snapshot, nên warehouse vẫn giữ chúng với `is_deleted = false`. Đối soát:

   ```bash
   make psql-src Q="\copy (SELECT transaction_id FROM ticket_transaction) TO '/tmp/src_ids.csv' CSV"
   make psql-wh  Q="\copy (SELECT transaction_id FROM dw.fact_ticket_sales WHERE NOT is_deleted AND created_at >= '<thời điểm mất>') TO '/tmp/wh_ids.csv' CSV"
   ```

   (`psql` chạy trong container, nên file nằm trong container; lấy ra bằng `docker cp pti-pg-source-1:/tmp/src_ids.csv .` và tương tự cho `pti-pg-warehouse-1`, rồi so bằng `comm -13 <(sort src_ids.csv) <(sort wh_ids.csv)`). Id có ở warehouse mà không có ở nguồn là dòng bị xóa trong khoảng mất. Ghi danh sách vào issue. Không sửa tay warehouse; nếu số lượng đáng kể thì dựng lại theo DOC-43 §4.4 bước 4.
5. Ghi sự cố như mất dữ liệu nguồn (RPO bị vi phạm) trong issue `incident`.

## Trên k3d

- Connector là CR `KafkaConnector` (`debezium-ticketing`, và connector S3 sink) do Strimzi quản lý. Trạng thái: `kubectl -n pti get kafkaconnector` hoặc `make connectors PTI_ENV=k3d`; chi tiết lỗi task: `kubectl -n pti get kafkaconnector debezium-ticketing -o jsonpath='{.status.connectorStatus}' | jq`.
- REST của Connect chỉ dùng để đọc (port-forward, README §2.1). Mọi thay đổi đi qua CR, vì Strimzi ghi đè thay đổi REST:
  - restart connector và task lỗi: `kubectl -n pti annotate kafkaconnector debezium-ticketing strimzi.io/restart=true` (task: `strimzi.io/restart-task=<id>`);
  - dừng / chạy lại: `kubectl -n pti patch kafkaconnector debezium-ticketing --type merge -p '{"spec":{"state":"stopped"}}'` (`running` để chạy lại);
  - xóa offset (thay cho `DELETE …/offsets`): khi connector đang `stopped`, `kubectl -n pti annotate kafkaconnector debezium-ticketing strimzi.io/connector-offsets=reset`.
- SQL trên nguồn: `make psql-src PTI_ENV=k3d [SU=1]`. `psql` chạy trong pod, nên file của `\copy` nằm trong pod; lấy ra bằng `kubectl -n pti cp pti-source-1:/tmp/src_ids.csv ./src_ids.csv` (thay cho `docker cp`).
- Config connector đổi trong `connect/connectors/*.json` rồi `make k8s-apply`.
- `pti-source` chỉ có 1 instance (DR-55), nên không có failover làm mất replication slot.

## Xác nhận đã xong

- `make connectors`: connector và task `RUNNING`; `pti_connect_connector_running{connector="debezium-ticketing"} == 1`.
- `pti_source_replication_slot_retained_bytes` giảm dần về dưới 100 MB (heartbeat 10 giây giữ slot tiến ngay cả khi không có giao dịch).
- `/actuator/health/sources` (cổng 9082) báo `source-ticketing` `UP`; `pti_source_last_event_age_seconds{source="TICKETING_SALES"}` (từ P4-16) nhỏ.

## Phòng ngừa và việc sau sự cố

- `ConnectorDown` phải được xử lý trước khi slot chạm 4 GB. Tốc độ tăng WAL của `pg-source` phụ thuộc tải vé và ledger của simulator (cùng instance); đo `deriv(pti_source_replication_slot_retained_bytes[30m])` khi sự cố xảy ra, ghi vào issue, và dùng nó để ước lượng thời gian còn lại cũng như chỉnh ngưỡng alert.
- Trước khi đổi mật khẩu `debezium` hoặc restart `pg-source` có kế hoạch: xem RB-12 để cập nhật connector cùng lúc.
- Sau nhánh D: chạy lại `DataQualityJob` (DQ-22) để phát hiện refund mồ côi do mất bản gốc.
