# Vòng đời dữ liệu

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-18
> Phụ thuộc: [DOC-09](../03-architecture/messaging-contracts.md) §1 và §7, [DOC-10](../03-architecture/quality-attributes.md) §3.3, [DOC-14](warehouse-model.md) §7.4, [DOC-15](ops-and-insight-model.md) §3.1, [DOC-17](db-roles-and-grants.md), [DOC-39](../09-operations/deploy-compose.md) §3.5, [ADR-0011](../04-adr/0011-fact-partitioning.md), [ADR-0012](../04-adr/0012-raw-zone-s3-sink.md), [DR](../00-decision-register.md) (DR-15, 16, 22, 28, 60, 62, 63, 66, 67)
> Người dùng chính: `etl` (P2-17), `source-simulator`, DOC-43 (backup), DOC-40 (values k3d)

Tài liệu này trả lời: **mỗi loại dữ liệu nằm ở đâu, giữ bao lâu, ai xóa, xóa bằng cách nào**, và dữ liệu cá nhân được giới hạn ra sao.

## 1. Bảng retention

Cột "Compose" là giá trị trong `.env.example`, nhỏ hơn mặc định để vừa ổ đĩa máy dev (DOC-10 §3.3). Mọi mốc thời gian của retention tính theo **cột nêu trong bảng**.

### 1.1 Warehouse (`pti_warehouse`)

| Dữ liệu | Mặc định | Compose | Tính theo | Cách xóa | Key cấu hình |
| --- | --- | --- | --- | --- | --- |
| `dw.fact_vehicle_position` | 14 ngày | 3 ngày | `service_date` | Drop partition ngày (`PartitionMaintenanceJob`) | `pti.retention.vehicle-position` |
| `dw.fact_trip_update` | 90 ngày | 30 ngày | `service_date` | Drop partition ngày | `pti.retention.trip-update` |
| `dw.fact_ticket_sales` | 365 ngày | 365 ngày | `sale_date` | Drop partition tháng (chỉ khi cả tháng đã quá hạn) | `pti.retention.ticket-sales` |
| `dw.vehicle_position_latest` | — | — | — | Không xóa; một dòng mỗi xe | — |
| Dimension và lịch GTFS (`dim_*`, `gtfs_*`, `route_headway`) | 3 phiên bản feed gần nhất | như mặc định | `gtfs_feed_version.loaded_at` | `GtfsStaticLoadJob`, bước retire (DOC-21) | `pti.gtfs.static.keep-versions` |
| `dw.dim_vehicle`, `dw.dim_sale_point` | Không xóa | | | Không có phiên bản; số dòng nhỏ | — |
| `dw.dim_date` | Cố định 2024–2030 | | | Migration | — |
| `ops.etl_stream_batch` | 7 ngày | 7 ngày | `started_at` | `OpsRetentionJob`, xóa theo lô | `pti.retention.etl-stream-batch` |
| `ops.etl_batch_step` | Theo metadata Spring Batch | | | Xóa dây chuyền (`ON DELETE CASCADE` theo `batch_step_execution`) | — |
| Metadata `batch.BATCH_*` | 30 ngày | 30 ngày | `BATCH_JOB_EXECUTION.CREATE_TIME` | `BatchMetadataCleanupJob` (DR-62) | `pti.retention.batch-metadata` |
| `ops.dead_letter` | 30 ngày sau `resolved_at` | như mặc định | `resolved_at` | `OpsRetentionJob`. Dòng chưa đóng (`status` khác `REPLAYED`, `DISCARDED`, `RESOLVED`) **không bao giờ bị xóa tự động** | `pti.retention.dead-letter-resolved` |
| `ops.dlq_action_log` | Theo `dead_letter` | | | Xóa dây chuyền | — |
| `ops.replay_request`, `ops.job_request` | 30 ngày | | `finished_at` (dòng chưa xong thì giữ) | `OpsRetentionJob` | `pti.retention.replay-request`, `pti.retention.job-request` |
| `ops.dq_check_result` | 30 ngày | | `checked_at` | `OpsRetentionJob` | `pti.retention.dq-check-result` |
| `ops.alert_event` | 90 ngày | | `created_at` | `OpsRetentionJob` | `pti.retention.alert-event` |
| `ops.dedup_registry` | 1 giờ | 1 giờ | `first_seen_at` | `DedupRegistryCleanupJob` | `pti.etl.dedup.ttl` |
| `ops.etl_checkpoint`, `ops.shedlock`, `ops.runtime_flag` | Không xóa | | | Số dòng cố định | — |
| `insight.insight_bus_bunching`, `insight.insight_service_disruption` | 365 ngày | | `episode_end`; chỉ dòng `CLOSED` (episode đang mở thì giữ) | `OpsRetentionJob` | `pti.retention.insight` |
| `insight.insight_ticketing_anomaly`, `insight.insight_dispatch_suggestion` | 365 ngày | | `detected_at` / `created_at` | `OpsRetentionJob` | `pti.retention.insight` |
| `insight.insight_otp_scorecard` | 365 ngày | | `service_date` | `OpsRetentionJob` | `pti.retention.insight` |
| `insight.insight_eta_prediction` | Không xóa | | | Số dòng giới hạn; job ETA thay toàn bộ (DOC-23 §7) | — |
| `insight.analytics_baseline_snapshot` | 30 ngày | | `snapshot_hour` | `OpsRetentionJob` | `pti.retention.baseline-snapshot` |
| `insight.analytics_bunching_cursor` | 7 ngày | | `last_tick`, và tuyến không còn `pair_state` | `OpsRetentionJob` | — |
| `insight.analytics_route_baseline`, `insight.analytics_bunching_pair_state` | Không xóa | | | Trạng thái đang chạy; `pair_state` do tick dọn (DOC-23 §5) | — |
| `exp.exp_fact_*` | Tới lần chạy EXP kế tiếp | | | Runner `TRUNCATE` (DOC-45) | — |

### 1.2 Nguồn và simulator (`pg-source`)

| Dữ liệu | Giữ | Ai xóa | Ghi chú |
| --- | --- | --- | --- |
| `ticketing_source.public.ticket_transaction` | Không xóa tự động | — | Xóa ở nguồn sinh event `d` qua CDC và làm sai lịch sử doanh thu; xem §1.2.1 |
| `ticketing_source.public.sale_point` | Không xóa | | 187 dòng |
| `pti_sim.sim.sim_ledger` | 2 ngày theo `produced_at` | `source-simulator` (drop partition ngày, DR-28) | `pti.sim.ledger.retention` |
| `pti_sim.sim.sim_scenario_run` | 30 ngày theo `finished_at` | `source-simulator` | Dòng đang chạy không bị xóa |

#### 1.2.1 Không dọn `ticket_transaction` tự động

Mọi lệnh `DELETE` trên bảng nguồn đều đi qua WAL và Debezium phát event xóa. Nếu simulator dọn dữ liệu cũ, warehouse sẽ nhận hàng loạt event `d` và đánh dấu `is_deleted = true` cho giao dịch cũ, làm sai doanh thu lịch sử. Hệ thống bán vé thật cũng không xóa giao dịch. Vì vậy:

- `ticket_transaction` **không có retention tự động**. Ở tải trung bình 0,77 giao dịch/giây, bảng tăng khoảng 66 nghìn dòng mỗi ngày (khoảng 20 MB), chạy liên tục một năm cũng chỉ khoảng 7 GB.
- Dọn thủ công bằng `make reset` (xóa volume) khi cần. Các giao dịch bị xóa do kịch bản `delete-ratio` (0,05%) là hành vi nghiệp vụ có chủ đích, không phải retention.

### 1.3 Kafka

| Topic | Retention | Ghi chú |
| --- | --- | --- |
| `gtfs.vehicle_positions`, `gtfs.trip_updates`, `ticketing.sales.cdc`, `ticketing.sale_points.cdc` | 7 ngày (k3d: 24 giờ, DOC-10 §3.3) | Cửa sổ để replay bằng reset offset (RB-10). Quá cửa sổ thì replay từ raw zone |
| `pti.events.ui` | 1 ngày | SSE chỉ cần vài phút gần nhất (DR-41) |
| `ticketing.heartbeat`, `__debezium-heartbeat.ticketing` | 1 giờ | |
| `connect-configs`, `connect-offsets`, `connect-status` | compact | Không xóa |
| `__consumer_offsets` | `offsets.retention.minutes` mặc định (7 ngày) | Group SSE của pod đã chết tự hết hạn (DOC-09 §1.1) |

Retention nằm trong `deploy/topics.yaml` (nguồn duy nhất cho cả compose và k3d).

### 1.4 Raw zone (bucket `raw`, SeaweedFS)

| Prefix | Giữ | Ghi chú |
| --- | --- | --- |
| `gtfs.vehicle_positions/`, `gtfs.trip_updates/` | 30 ngày | Theo thời điểm tạo object |
| `ticketing.sales.cdc/`, `ticketing.sale_points.cdc/` | 30 ngày | Chứa `customer_ref` (§4) |
| `gtfs-static/` | Không hết hạn | Khoảng 19 MB mỗi phiên bản feed; cần cho EXP-04 dựng lại dimension |
| Phiên bản cũ (noncurrent) của mọi object | 7 ngày | Versioning bật để khôi phục khi ghi đè hay xóa nhầm (DR-66) |
| Delete marker không còn phiên bản nào phía sau | Dọn tự động | |

**Cách áp retention.** `s3-init` (compose) hoặc Job Helm (k3d) đặt lifecycle rule bằng API S3 chuẩn:

```json
{
  "Rules": [
    { "ID": "gtfs-rt-30d",     "Filter": { "Prefix": "gtfs." },      "Status": "Enabled", "Expiration": { "Days": 30 } },
    { "ID": "ticketing-30d",   "Filter": { "Prefix": "ticketing." }, "Status": "Enabled", "Expiration": { "Days": 30 } },
    { "ID": "noncurrent-7d",   "Filter": { "Prefix": "" },           "Status": "Enabled",
      "NoncurrentVersionExpiration": { "NoncurrentDays": 7 },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 },
      "Expiration": { "ExpiredObjectDeleteMarker": true } }
  ]
}
```

`AbortIncompleteMultipartUpload` dọn các multipart upload dở dang: S3 sink mở một multipart upload cho mỗi file và chỉ hoàn tất khi commit, nên connector chết giữa chừng để lại upload dở (DOC-09 §7).

`s3-init` đọc lại cấu hình bằng `get-bucket-lifecycle-configuration` để chắc rằng SeaweedFS đã nhận. S-04 (2026-09-28) xác nhận SeaweedFS 4.47 nhận và trả lại đủ ba rule, kể cả `AbortIncompleteMultipartUpload`. Spike chỉ kiểm được rằng rule được lưu, chưa kiểm được việc xóa sau 30 ngày. Vì vậy test L-09 kiểm việc xóa bằng rule `Days: 1` trên một bucket thử. Nếu rule không có tác dụng, phương án dự phòng là TTL gốc của SeaweedFS theo đường dẫn: `weed shell` → `fs.configure -locationPrefix=/buckets/raw/gtfs. -ttl=30d -apply` (tương tự cho `ticketing.`).

### 1.5 Observability

| Dữ liệu | Compose | k3d |
| --- | --- | --- |
| Prometheus TSDB | 7 ngày | 7 ngày |
| Loki | 7 ngày | 3 ngày |
| Tempo | 3 ngày | 3 ngày |
| Log container (json-file) | 3 file × 20 MB mỗi container | Theo kubelet (`containerLogMaxSize` 10 MB, 5 file) |

## 2. Bố cục raw zone

Theo DOC-09 §7 và ADR-0012:

```
raw/
  gtfs.vehicle_positions/dt=2026-09-29/hh=21/gtfs.vehicle_positions-7-00000000000000918273.json.gz
  gtfs.trip_updates/dt=2026-09-29/hh=21/gtfs.trip_updates-3-00000000000000120044.json.gz
  ticketing.sales.cdc/dt=2026-09-29/hh=21/ticketing.sales.cdc-2-00000000000000004410.json.gz
  ticketing.sale_points.cdc/dt=2026-09-26/hh=08/ticketing.sale_points.cdc-0-00000000000000000000.json.gz
  gtfs-static/<feed_hash>.zip
```

- `dt` và `hh` lấy từ **timestamp của record Kafka** (CreateTime, giờ thật, UTC), không phải `event_timestamp`. Khi đặt `PTI_CLOCK_OFFSET`, hai mốc này lệch nhau đúng bằng offset; replay đổi giờ nghiệp vụ sang giờ record trước khi chọn thư mục (DR-67, DOC-22).
- `start_offset` được đệm 20 chữ số (`{{start_offset:padding=true}}` của connector) nên sắp theo tên cũng là sắp theo offset. Partition **không** đệm, nên muốn sắp theo partition thì phải parse tên file.
- Mỗi dòng trong file là JSON `{key, value, offset, timestamp, headers}`; `value` là base64 của đúng các byte gốc, partition lấy từ tên file (DOC-09 §7).
- Chỉ Kafka Connect ghi các prefix topic; chỉ `etl-batch` ghi `gtfs-static/` (§3).

## 3. Quyền trên raw zone

| Identity (`s3.json`) | Quyền | Dùng bởi |
| --- | --- | --- |
| `connect` | `Read:raw`, `Write:raw`, `List:raw` | S3 sink connector |
| `etl` | `Read:raw`, `List:raw`, `Write:raw/gtfs-static` | `etl-batch`: replay đọc mọi prefix; `GtfsStaticLoadJob` ghi zip |
| `admin` | Toàn quyền | Chỉ `s3-init`, `make s3-ls` |

Bước 4 của `s3-init` kiểm tra rằng `etl` **không** ghi được ngoài `gtfs-static/` (DOC-39 §3.5). S-04 xác nhận SeaweedFS 4.47 hỗ trợ quyền theo prefix với cú pháp **`Write:raw/gtfs-static/*`**: `etl` ghi được `raw/gtfs-static/…`, bị từ chối ở `raw/gtfs.vehicle_positions/…` và `raw/gtfs-staticX/…`. Viết thiếu `/*` (`Write:raw/gtfs-static/`) thì mọi lệnh ghi đều bị từ chối.

## 4. Dữ liệu cá nhân (DR-60)

Dữ liệu cá nhân duy nhất là `customer_ref` (mã khách hàng mô phỏng) trong giao dịch vé.

| Nơi | Có `customer_ref`? | Kiểm soát |
| --- | --- | --- |
| `ticketing_source.ticket_transaction` | Có | Chỉ `ticketing_owner`, `source_simulator`, `debezium`, `experiment_runner` đọc được (DOC-17 §4.2) |
| Topic `ticketing.sales.cdc` | Có, 7 ngày | Chỉ mạng nội bộ; không có consumer nào ngoài ETL và S3 sink |
| Raw zone `ticketing.sales.cdc/` | Có, 30 ngày | Chỉ ba identity ở §3 |
| `dw.fact_ticket_sales` | **Không** | DTO không có trường này (DOC-16 §2.2) |
| `ops.dead_letter.raw_payload` | **Không** | `PiiScrubber` xóa trường trước khi ghi DLQ, kể cả khi message không parse được thành DTO (§4.1) |
| Log ứng dụng | **Không** | ETL không log payload CDC ở mức INFO. Ở DEBUG, payload đi qua `PiiScrubber` trước |
| Prompt gửi Jev | **Không** | Test chặn PII của prompt builder (DOC-24, FR-09.10) |
| Trace (span attribute) | **Không** | Không đưa payload vào span |

### 4.1 `PiiScrubber`

Đặt trong `common`. Nhận chuỗi `raw_payload`:

1. Parse được JSON: xóa mọi thuộc tính có tên trong danh sách chặn (`customer_ref`), ở mọi độ sâu, rồi serialize lại.
2. Không parse được JSON (message hỏng): thay mọi đoạn khớp regex `"customer_ref"\s*:\s*"[^"]*"` bằng `"customer_ref":"[REDACTED]"`. Không phải đoạn hỏng nào cũng khớp regex; phần còn lại được giữ để còn điều tra được. Rủi ro này chấp nhận được vì dữ liệu là mô phỏng và topic CDC không nhận message hỏng từ Debezium.

Danh sách chặn là `pti.pii.blocklist` (mặc định `[customer_ref]`), dùng chung cho DLQ, log và prompt builder.

### 4.2 Yêu cầu xóa dữ liệu của một khách

Nằm ngoài phạm vi, vì dữ liệu là mô phỏng. Nếu cần, quy trình là: xóa ở nguồn (event `d` đi qua CDC; warehouse không có trường này), rồi chờ Kafka và raw zone hết hạn (tối đa 30 ngày cộng 7 ngày phiên bản cũ). Ghi nhận giới hạn này trong DOC-27.

## 5. Job dọn dẹp

Chi tiết cài đặt của từng job (step, `@SchedulerLock`, job parameters) ở DOC-19 §2. Tóm tắt lịch:

| Job | Chạy ở | Lịch (giờ thật, UTC) | Việc |
| --- | --- | --- | --- |
| `PartitionMaintenanceJob` | etl-batch | Lúc khởi động và 01:15 hằng ngày | `dw.ensure_partitions(table, businessToday − 1, businessToday + 7)` (vé: `+ 40`); `dw.drop_partitions_before(table, businessToday − retention)` |
| `OpsRetentionJob` | etl-batch | 02:00 hằng ngày | Xóa theo lô các bảng `ops` (và `insight` từ P4) quá hạn ở §1.1 |
| `BatchMetadataCleanupJob` | etl-batch | 02:30 hằng ngày | Xóa metadata Spring Batch cũ hơn 30 ngày (DR-62) |
| `DedupRegistryCleanupJob` | etl-batch | Mỗi 15 phút | `DELETE FROM ops.dedup_registry WHERE first_seen_at < :now − ttl` theo lô |
| Ledger maintenance | source-simulator | Lúc khởi động và mỗi giờ | `sim.ensure_ledger_partitions`, `sim.drop_ledger_partitions_before` (DOC-13 §6) |

Quy tắc chung:

- **Xóa theo lô**: `DELETE … WHERE ctid IN (SELECT ctid FROM … WHERE <điều kiện> LIMIT 5000)`, lặp tới khi hết, mỗi lô một transaction. Không có lệnh xóa nào giữ khóa lâu hay sinh một transaction lớn.
- Mốc "bây giờ" lấy từ `BusinessClock` cho dữ liệu tính theo giờ nghiệp vụ (partition fact, `sale_date`) và từ giờ thật cho cột audit (`started_at`, `created_at`, `first_seen_at`), theo đúng phân loại ở DR-67.
- Mỗi job ghi số dòng hoặc partition đã xóa vào `ExitStatus` description và metric `pti_retention_deleted_total{table}`.
- Retention dùng quyền `DELETE` của `etl_writer` (DOC-17 §4.1). Drop partition đi qua hàm `SECURITY DEFINER` (DOC-14 §7.4).

## 6. Backup

Tóm tắt; quy trình đầy đủ ở DOC-43.

| Dữ liệu | Cách | Lịch | Giữ |
| --- | --- | --- | --- |
| `pti_warehouse` | `pg_dump -Fc` (loại trừ dữ liệu của `dw.fact_vehicle_position`, `ops.dedup_registry`, `exp.*`) | Compose: `make backup` khi cần. k3d: CronJob 04:00 hằng ngày | 7 bản |
| `ticketing_source`, `pti_sim` | `pg_dump -Fc` | Như trên | 7 bản |
| Raw zone | Versioning trong bucket (không sao lưu ra ngoài) | Liên tục | §1.4 |
| Cấu hình | Git (compose, Helm, realm Keycloak, dashboard) | Mỗi commit | — |

Warehouse dựng lại được từ raw zone bằng replay (EXP-04), nên dump chỉ là đường khôi phục nhanh. `fact_vehicle_position` không nằm trong dump vì chiếm phần lớn dung lượng và replay lại được.

## 7. Test bắt buộc

| ID | Kiểm tra |
| --- | --- |
| L-01 | `PartitionMaintenanceJob` với `PTI_CLOCK_OFFSET = -12h`: partition được tạo theo ngày nghiệp vụ |
| L-02 | `OpsRetentionJob`: dòng `dead_letter` chưa đóng cũ hơn 30 ngày vẫn còn; dòng đã đóng quá 30 ngày bị xóa cùng `dlq_action_log` |
| L-03 | `OpsRetentionJob` xóa 120.000 dòng `etl_stream_batch`: không transaction nào quá 5.000 dòng (kiểm tra bằng số lần commit) |
| L-04 | `BatchMetadataCleanupJob`: execution FAILED chưa restart không bị xóa; thứ tự xóa không vi phạm khóa ngoại; `etl_batch_step` tương ứng bị xóa dây chuyền |
| L-05 | `DedupRegistryCleanupJob`: dòng cũ hơn TTL bị xóa, dòng mới còn |
| L-06 | `PiiScrubber`: JSON hợp lệ, JSON lồng nhau, JSON hỏng có và không có `customer_ref` |
| L-07 | Lifecycle của bucket `raw`: `get-bucket-lifecycle-configuration` trả đúng ba rule (hoặc TTL dự phòng đã áp) |
| L-08 | Quyền `etl` trên raw zone (bước 4 của `s3-init`): ghi được `gtfs-static/`, bị từ chối ở prefix topic và ở `gtfs-staticX/` |
| L-09 | Bucket thử có rule `Expiration: {Days: 1}` và một object; sau khi đồng hồ vượt 1 ngày (chạy thủ công một lần ở P1, SeaweedFS không cho giả lập giờ) object biến mất. Nếu không, chuyển sang TTL dự phòng (§1.4) |

## 8. Câu hỏi còn mở

Không có.
