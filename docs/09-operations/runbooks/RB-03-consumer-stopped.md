# RB-03: Consumer dừng, bị pause, hoặc gặp lỗi FATAL

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-42 / RB-03
>
> Alert: `ConsumerStopped` (critical, 1 phút), `ConsumerPaused` (warning, 5 phút, lý do `backoff`/`circuit`), `FatalErrors` (critical) · Dashboard: `pti-overview` (trạng thái listener) · Liên quan: DOC-20 §5–7, DOC-30 §2

## Triệu chứng và ảnh hưởng

- `ConsumerStopped`: container của một listener đã dừng (`pti_etl_listener_running = 0`). Nguyên nhân thường gặp: lỗi `FATAL` (container dừng có chủ đích, DOC-20 §5.3), hoặc listener GTFS-rt chưa từng được bật vì chưa có feed ACTIVE (DOC-20 §6).
- `ConsumerPaused`: listener bị pause vì `backoff` (đang retry lỗi hạ tầng) hoặc `circuit` (mạch `warehouse` mở) quá 5 phút.
- `FatalErrors`: `pti_errors_total{kind="fatal"}` tăng ở `etl`, `triage-worker` hoặc `source-simulator`.
- Ảnh hưởng: nguồn tương ứng không được ghi vào warehouse; lag tăng; bản đồ hoặc doanh thu ngừng cập nhật. **Không mất dữ liệu**: offset của poll lỗi không được commit.

## Kiểm tra

1. Listener nào, lý do gì: `pti_etl_listener_running`, `pti_etl_listener_paused{reason}`; `curl -s localhost:9082/actuator/health/sources | jq` (cổng management của `etl-stream`, DOC-39).
2. Lỗi `FATAL`:

   ```logql
   {service=~"etl-.*|triage-worker|source-simulator"} | json | pti_error_kind="FATAL"
   ```

   Ghi lại lớp exception, `batch_id`, `trace_id`, `listener`. Mở trace trong Tempo để xem span lỗi (`pti.etl.write`, `pti.etl.process`…).
3. Nếu là listener GTFS-rt và log có `no active feed`: xem RB-05.
4. Nếu pause vì `backoff`/`circuit`: lỗi hạ tầng; xem `resilience4j_circuitbreaker_state{name="warehouse"}` và RB-08. Kafka lỗi: `make logs S=kafka`.

## Xử lý

**Lỗi `FATAL` ở `etl-stream`.** Container dừng để không bỏ qua dữ liệu một cách âm thầm.

| Nguyên nhân (theo exception / SQLState, DOC-30 §2.3) | Việc |
| --- | --- |
| `42501` insufficient_privilege, `42P01`/`42703` bảng/cột không tồn tại | Migration hoặc grant chưa chạy: `make up` chạy lại `db-migrate`; kiểm `flyway_schema_history`. Sau đó `make restart S=etl-stream` |
| `21000` cardinality_violation | Lỗi khử trùng trong chunk (DOC-16 §2.1): lỗi lập trình. Mở issue với `batch_id`; tạm thời có thể pause nguồn bằng cờ trong lúc sửa |
| Jackson exception ở phase `WRITE`, `NullPointerException`, `IllegalStateException` | Lỗi lập trình với một record cụ thể. Tìm record: offset trong dòng `ops.etl_stream_batch` có `batch_id` đó (cột `offsets`). Sửa code, deploy |
| Không rõ | Mở issue với log và trace; giữ container dừng |

Sau khi sửa: `make restart S=etl-stream` (mọi container của process khởi động lại; poll lỗi được giao lại từ offset đã commit).

**Nếu cần chạy tiếp các nguồn khác ngay trong lúc chờ sửa:** các listener khác vẫn chạy (lỗi `FATAL` chỉ dừng container của listener đó). Không cần làm gì thêm.

**Không** bỏ qua record lỗi bằng cách tăng offset thủ công. Nếu buộc phải đi tiếp trước khi có bản sửa (hiếm), dùng RB-10 để đặt offset qua record đó, **ghi rõ offset bị bỏ** vào issue, rồi replay lại khoảng đó từ raw zone sau khi đã sửa.

**`FATAL` ở `triage-worker`** (từ P6): worker dừng vòng lặp, readiness `DOWN`; pipeline không bị ảnh hưởng. Xem log, sửa, `make restart S=triage-worker`.

**`FATAL` ở `source-simulator`:** kịch bản bị dừng; nguồn ngừng phát → `GtfsRtFeedStale` theo sau. Xem log, `make restart S=source-simulator`.

**Pause vì `backoff`/`circuit`:** không restart (restart không sửa được DB hay Kafka); xử lý hạ tầng theo RB-08. Container tự resume khi hết lý do pause.

## Trên k3d

- Kiểm tra listener: `kubectl -n pti port-forward deploy/etl-stream 9082:9080` rồi dùng cùng lệnh `curl` ở bước 1 (mỗi lần port-forward chỉ tới một pod; lặp theo từng pod khi có nhiều pod: `kubectl -n pti port-forward pod/<pod> 9082:9080`).
- Khi có nhiều pod, một record gây `FATAL` sẽ dừng listener ở pod đang giữ partition đó. Partition được chia lại cho pod khác qua rebalance và gặp lại đúng record đó, nên lần lượt các pod đều dừng listener GTFS-rt. Đây là hành vi mong muốn (không bỏ qua dữ liệu âm thầm); `ConsumerStopped` bắn một lần cho cả job.
- Khởi động lại sau khi sửa: `kubectl -n pti rollout restart deployment/etl-stream` (tương tự `triage-worker`, `source-simulator`). Migration hoặc grant thiếu: `make k8s-apply` (hook `db-migrate`).
- Đặt offset qua record lỗi (hiếm): RB-10 mục "Trên k3d".

## Xác nhận đã xong

- `min by (listener) (pti_etl_listener_running) == 1` cho mọi listener; không còn `pti_etl_listener_paused{reason=~"backoff|circuit"} == 1`.
- `/actuator/health/sources` trả `UP` cho `source-gtfs-rt` và `source-ticketing`.
- Lag giảm dần (RB-02); không có lỗi `FATAL` mới trong 15 phút.

## Phòng ngừa và việc sau sự cố

- Mỗi lỗi `FATAL` do lập trình phải có test tái hiện trước khi đóng issue (DOC-44).
- Nếu nguyên nhân là migration chưa chạy: kiểm tra thứ tự `depends_on` của `db-migrate` (DOC-39 §4).
