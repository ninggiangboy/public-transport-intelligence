# RB-04: Tỷ lệ DLQ cao hoặc tồn đọng DLQ

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-04
>
> Alert: `DlqRateHigh` (critical, > 1% trong 5 phút, nguồn có > 0,2 msg/s), `DlqBacklogHigh` (warning, > 500 dead letter mở trong 30 phút); từ P6: `DlqSevereRecords` (critical, có record severity 2), `DlqNeedsAttention` (warning, ≥ 10 record severity 1 trong 30 phút), `DlqUpstreamErrorBurst` (critical, > 50 `upstream_api_error` trong 1 giờ, luật chặn cuối), `TriageBacklogHigh` (warning, hàng đợi triage > 500 trong 15 phút) · Dashboard: `pti-overview` (tỷ lệ DLQ), `pti-batch` (DLQ mở), `pti-triage` · Liên quan: DOC-16, DOC-22, DOC-24

## Triệu chứng và ảnh hưởng

- `DlqRateHigh`: hơn 1% record của một nguồn bị loại vào DLQ. Record hợp lệ vẫn được nạp (NFR-02); record lỗi nằm trong `ops.dead_letter` chờ xử lý. Insight có thể thiếu dữ liệu của các xe/chuyến bị loại.
- `DlqBacklogHigh`: hơn 500 dead letter ở trạng thái cần người (`NEW`, `MANUAL`, `PENDING_CONFIRM`) quá 30 phút. Triage tự động không theo kịp hoặc đang tắt.
- `DlqSevereRecords` / `DlqNeedsAttention`: triage-worker xếp record vào mức "Urgent" / "Needs attention" (DOC-24 §10). Nhãn `source` cho biết nguồn. Đây là phán đoán của mô hình: kiểm lại bằng bước 1–3 trước khi hành động.
- `DlqUpstreamErrorBurst`: hơn 50 record trong 1 giờ bị luật tất định xếp `upstream_api_error` (tọa độ, độ trễ, số tiền, ngày phục vụ bất thường, DOC-24 §15.1). Không phụ thuộc Jev: bắn cả khi triage-worker tắt. Thường là hệ nguồn đang phát dữ liệu sai.
- `TriageBacklogHigh`: triage-worker không theo kịp (Jev chậm, circuit mở, quota, hoặc worker dừng).

## Kiểm tra

1. Phân bố theo stage và rule trong 30 phút qua:

   ```sql
   SELECT source, stage, rule_id, error_class, count(*) AS n, min(created_at), max(created_at)
   FROM ops.dead_letter WHERE created_at > now() - interval '30 minutes'
   GROUP BY 1, 2, 3, 4 ORDER BY n DESC LIMIT 20;
   ```

2. Có kịch bản `bad-data` đang chạy không (`pti-simulator` → kịch bản đang chạy)? Nếu có và tỷ lệ khớp tham số, alert là mong đợi.
3. Mẫu lỗi: `SELECT id, error_message, left(raw_payload, 500) FROM ops.dead_letter WHERE rule_id = :rule ORDER BY created_at DESC LIMIT 5;` (payload đã loại PII, DOC-18 §4).
4. Dấu hiệu nguyên nhân:

   | Dấu hiệu | Nguyên nhân khả dĩ |
   | --- | --- |
   | `DQ-03`/`DQ-04`/`DQ-05` tăng đột ngột ngay sau khi kích hoạt feed mới | Feed mới đổi `route_id`/`stop_id`/`trip_id`, nguồn realtime còn dùng id cũ |
   | `DQ-07` (event time lệch) hàng loạt | Đồng hồ nghiệp vụ lệch giữa simulator và ETL (`PTI_CLOCK_OFFSET` khác nhau, DR-67) |
   | `DQ-09` | Ngày phục vụ lệch: như trên, hoặc chuyến sau nửa đêm |
   | `SCHEMA` với `schema_version` mới | Producer nâng schema trước khi ETL hỗ trợ (DR-59) |
   | `DESERIALIZE` | Producer hỏng hoặc sai serializer |
   | `DQ-12` | Refund tới trước giao dịch gốc quá 5 phút: connector ticketing trễ (RB-09) |
   | `LOAD` | Dữ liệu vi phạm ràng buộc DB mà rule chưa bắt: lỗi thiết kế rule, mở issue |

5. Tồn đọng: `SELECT status, source, count(*) FROM ops.dead_letter WHERE status IN ('NEW','MANUAL','PENDING_CONFIRM') GROUP BY 1, 2;`.
6. Triage (từ P6), trên dashboard `pti-triage` hoặc Prometheus:
   - `pti_triage_paused` khác 0 theo `reason`: `circuit_open` (Jev lỗi hoặc chậm, alert `CircuitBreakerOpen` `name="decision-model"`), `quota` (hết quota, tự thử lại sau 15 phút), `fatal` (API key sai hoặc PII trong state, alert `FatalErrors`), `provider_disabled`.
   - `sum by (outcome) (rate(pti_triage_calls_total[5m]))`: `timeout`/`error` tăng → Jev có vấn đề; `invalid` tăng → response lạ (SDK đổi phiên bản?).
   - Cờ: `SELECT key, value FROM ops.runtime_flag WHERE key LIKE 'triage.%';`
   - Log: `docker compose logs triage-worker --since 15m | grep -E '"log.level":"(WARN|ERROR)"'` (k3d: `kubectl -n pti logs deploy/triage-worker --since=15m`).
   - Record kẹt: `SELECT count(*) FROM ops.dead_letter WHERE status = 'TRIAGING' AND triage_lease_until < now() - interval '1 minute';` > 0 nghĩa là `LeaseSweeper` không chạy (kiểm tra `ops.shedlock` dòng `triage-lease-sweeper`).

## Xử lý

1. **Nguyên nhân ở nguồn** (bad-data có chủ đích, producer hỏng): dừng nguồn lỗi (`make scenario-stop NAME=bad-data`). Dead letter giữ nguyên để xử lý sau.
2. **Đồng hồ lệch:** đồng bộ `PTI_CLOCK_OFFSET` cho mọi app (`make clock-offset …` rồi `make up`). Sau đó replay các dead letter `DQ-07`/`DQ-09` (bước 5).
3. **Feed đổi id:** nếu feed mới sai → kích hoạt lại feed cũ (RB-05). Nếu feed mới đúng và nguồn realtime cần cập nhật → chờ nguồn cập nhật; dead letter trong khoảng này được **discard** có ghi chú, hoặc replay sau khi kích hoạt lại feed cũ cho khoảng đó.
4. **Lỗi logic của rule** (rule loại nhầm record hợp lệ): sửa rule, deploy, rồi replay.
5. **Replay hàng loạt** sau khi đã sửa nguyên nhân: replay khoảng thời gian từ raw zone (`make replay SOURCE=… FROM=… TO=…`, giờ record Kafka). Raw zone replay tự chuyển các dead letter đã giải quyết sang `RESOLVED` (DR-70). Với vài record lẻ: replay từng dead letter trên Ops console (từ P5).
6. **Tồn đọng:** kiểm tra triage-worker theo bước kiểm tra 6. Dead letter không thể sửa (dữ liệu rác có chủ đích) → discard hàng loạt trên Ops console có lý do.
7. **Triage dừng vì `fatal`:** sửa nguyên nhân (đặt lại `TYPESAFE_API_KEY` trong `.env` hoặc Secret `pti-jev` theo RB-12; PII trong state là lỗi code → mở issue và chạy `PTI_TRIAGE_PROVIDER=fake` hoặc `disabled` tạm thời), rồi restart: `docker compose up -d triage-worker` (k3d: `kubectl -n pti rollout restart deploy/triage-worker`). Record đang giữ đã được trả về `NEW` không tính lần thử.
8. **Jev không dùng được lâu:** chuyển `PTI_TRIAGE_PROVIDER=fake` (bảng luật tất định, DOC-24 §15) và restart triage-worker, hoặc tắt cờ `triage.dlq.enabled` (record giữ `NEW`, xử lý tay). Không cần làm gì với ETL: pipeline không phụ thuộc Jev (FR-09.7).
9. **Auto-replay nghi sai:** tắt cờ `triage.auto-replay.enabled` trên màn Controls; trong ≤ 10 giây mọi dòng `AUTO_REPLAY_SCHEDULED` chuyển sang `PENDING_CONFIRM` để người quyết định. Kiểm `dlq_action_log` với `actor = 'auto'` để xem các replay tự động đã tạo.
10. **`DlqSevereRecords` do `LOAD`:** record vi phạm ràng buộc DB mà rule chưa bắt, xem dòng `LOAD` ở bảng dấu hiệu.

Không xóa dòng `dead_letter` bằng SQL: mất `dlq_action_log` và làm sai số liệu.

## Trên k3d

- `make scenario-stop`, `replay`, `flag`, `psql-wh` thêm `PTI_ENV=k3d`. Đồng hồ lệch: `make k8s-clock-offset AT=…` (một giá trị cho mọi app, DOC-40 §14).
- `triage-worker` scale 1→3 theo số dead letter chờ triage (KEDA PostgreSQL scaler, DOC-40 §9.2). `TriageBacklogHigh` khi đã đủ 3 pod: giới hạn là rate limiter Jev theo pod (DOC-24 §11.3), không tăng thêm.
- Đổi provider (bước 7–8): sửa `triage.provider` trong values môi trường (`fake` hoặc `disabled`) rồi `make k8s-apply`; Secret `pti-jev` theo RB-12 mục "k3d".
- `lite` gọi `jev-stub` qua Toxiproxy (DOC-40 §13.2). Lỗi Jev trên `lite` gần như luôn do toxic còn sót: `curl -s localhost:18474/proxies/jev | jq '.toxics'`, gỡ bằng `curl -s -X DELETE localhost:18474/proxies/jev/toxics/<name>`.

## Xác nhận đã xong

- `pti:etl_skipped:rate5m / pti:etl_input:rate5m < 0.01` cho mọi nguồn trong 15 phút.
- Số dead letter mở giảm dưới 500; `ops.replay_request` của các replay đã tạo ở trạng thái `DONE`.
- Từ P6: `pti_triage_paused` = 0 cho mọi `reason`; `pti_triage_backlog` giảm về gần 0; không còn dòng `TRIAGING` quá hạn lease.

## Phòng ngừa và việc sau sự cố

- Lỗi do đổi feed: bổ sung kiểm tra GV-xx (DOC-21 §4) để cảnh báo trước khi activate một feed đổi nhiều id.
- Lỗi do đồng hồ: mọi app phải đọc cùng biến `PTI_CLOCK_OFFSET` từ `.env`; không đặt đè biến này cho riêng một service trong `compose.yaml` hay lệnh `docker compose run`.
