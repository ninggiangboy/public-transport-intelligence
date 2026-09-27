# RB-08: Nghẽn database và circuit breaker mở

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-42 / RB-08
>
> Alert: `DatabaseBottleneck` (warning, 5 phút), `CircuitBreakerOpen` (warning, 1 phút) · Dashboard: `pti-postgres`, `pti-overview`, `pti-jvm` (Hikari) · Liên quan: DOC-10 §4 (pool), DOC-20 §5.2 (circuit breaker), DOC-18 (vacuum, partition), DOC-39 §3.6 (Toxiproxy)

## Triệu chứng và ảnh hưởng

- `DatabaseBottleneck`: p95 thời gian chunk của `etl-stream` > 2 giây **và** tổng lag đang tăng. Warehouse ghi không kịp tốc độ vào.
- `CircuitBreakerOpen{name="warehouse"}`: hơn 50% trong 10 lần ghi gần nhất lỗi hạ tầng (DOC-20 §5.2). Mọi listener của `etl-stream` bị pause; mạch thử lại sau mỗi 10 giây. Ức chế `DatabaseBottleneck`, `ConsumerPaused`, `ConsumerLagHigh`, `EndToEndLatencyHigh`, `LatencyStageSlow` (DOC-28 §6.4).
- `CircuitBreakerOpen{name="decision-model"}` (triage-worker, từ P6): Jev lỗi hoặc chậm; triage tạm dừng nhận việc, ETL không bị ảnh hưởng. Xử lý theo RB-04 bước kiểm tra 6 và bước xử lý 8, không theo runbook này.
- Ảnh hưởng: dữ liệu dồn trong Kafka (không mất); bản đồ và insight trễ; API có thể chậm hoặc lỗi 5xx nếu cùng Postgres quá tải (RB-14).

## Kiểm tra

1. **Postgres còn sống không?** `make ps` (`pg-warehouse` healthy?), `make psql-wh Q='SELECT 1'`. Không kết nối được → nhánh A.
2. **Đang chạy thực nghiệm lỗi mạng?** `curl -s localhost:8474/proxies | jq` (profile `experiment`): proxy `pg-warehouse` `enabled: false` hoặc có toxic → do Toxiproxy (nhánh E).
3. **Câu lệnh chậm:** Postgres ghi mọi câu > 500 ms (`log_min_duration_statement=500`, DOC-39 §3.1). Loki: `{service="pg-warehouse"} |= "duration:"`. Ghi lại câu nào, bao lâu.
4. **Ai đang chiếm kết nối và chờ gì:**

   ```sql
   SELECT usename, application_name, state, wait_event_type, wait_event,
          now() - xact_start AS xact_age, now() - query_start AS query_age, left(query, 120)
   FROM pg_stat_activity WHERE datname = 'pti_warehouse' AND state <> 'idle'
   ORDER BY xact_start NULLS LAST;
   ```

   - Nhiều dòng `wait_event_type = 'Lock'` → nhánh B.
   - Một transaction `idle in transaction` hay chạy rất lâu (thường là psql tay, job replay lớn, hoặc truy vấn của Grafana) → nhánh B.
   - `wait_event_type = 'IO'` nhiều → nhánh C.
5. **Khóa:** ai chặn ai.

   ```sql
   SELECT blocked.pid AS blocked_pid, left(blocked.query, 80) AS blocked_query,
          blocking.pid AS blocking_pid, blocking.usename, left(blocking.query, 80) AS blocking_query,
          now() - blocking.xact_start AS blocking_age
   FROM pg_stat_activity blocked
   JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(pid) ON true
   JOIN pg_stat_activity blocking ON blocking.pid = b.pid;
   ```

6. **Pool của app:** `hikaricp_connections_pending{application="etl-stream"}` > 0 kéo dài, hoặc `hikaricp_connections_active` bằng max (6) → app chờ kết nối. `hikaricp_connections_timeout_total` tăng → lỗi `TRANSIENT_INFRA` và có thể mở mạch.
7. **Bảng phình, autovacuum:**

   ```sql
   SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
   FROM pg_stat_user_tables WHERE schemaname IN ('dw', 'ops')
   ORDER BY n_dead_tup DESC LIMIT 10;
   ```

   `dw.vehicle_position_latest` bị update liên tục (khoảng 121 lần/giây lúc cao điểm) nên dễ phình nếu autovacuum không theo kịp (DOC-18). `ops.dedup_registry` cũng vậy.
8. **Tài nguyên:** `docker stats --no-stream pti-pg-warehouse-1` (CPU so với limit 2,0; RAM so với 1.536 MB); `docker system df` (đĩa).

## Xử lý

**A. Postgres chết hoặc không nhận kết nối.** `make logs S=pg-warehouse`.
- OOM (`docker inspect pti-pg-warehouse-1 --format '{{.State.OOMKilled}}'`) hoặc crash: `make restart S=pg-warehouse`. Postgres tự recovery từ WAL; app tự kết nối lại, mạch đóng sau lần thử thành công.
- Đĩa đầy: giải phóng đĩa của Docker (`docker system prune` với image/volume **không** thuộc dự án), rồi restart. Không xóa file trong volume dữ liệu.
- `too many connections` (`max_connections=100`): tìm app giữ nhiều kết nối (`SELECT usename, application_name, count(*) FROM pg_stat_activity GROUP BY 1, 2 ORDER BY 3 DESC;`), so với bảng pool DOC-10 §4.
- Không khởi động được (dữ liệu hỏng): DOC-43 §4 (khôi phục).

**B. Khóa hoặc transaction dài.** Nếu transaction chặn là phiên tay hoặc truy vấn phân tích: `SELECT pg_cancel_backend(<pid>);`, không được thì `pg_terminate_backend(<pid>)`. Nếu là job batch (replay lớn): cân nhắc dừng job: `make job-stop ID=<jobExecutionId>` (job dừng sau chunk đang chạy, trạng thái `STOPPED`, restart được sau bằng `make job-restart`). Không terminate phiên của `etl-stream` (nó tự retry, nhưng việc đó không giải quyết nguyên nhân).

**C. IO hoặc CPU cạn.** Tìm nguồn tải lạ: replay đang chạy song song với tải cao, dashboard Grafana có panel SQL nặng mở ở nhiều tab, test hiệu năng. Giảm tải: tạm dừng replay, giảm `make sim-rate`. Lâu dài: tăng `mem_limit`/CPU của `pg-warehouse` (DOC-39 §3.1) hoặc xem lại index của câu chậm (bước 3).

**D. Autovacuum không theo kịp.** Chạy tay cho bảng phình nhất: `VACUUM (VERBOSE, ANALYZE) dw.vehicle_position_latest;` (không dùng `VACUUM FULL`: khóa bảng). Nếu lặp lại, chỉnh `autovacuum_vacuum_scale_factor` cho bảng đó trong migration (DOC-18).

**E. Toxiproxy.** Nếu không có thực nghiệm nào đang chạy mà proxy vẫn tắt hoặc còn toxic (runner chết giữa chừng): `curl -s -X POST localhost:8474/proxies/pg-warehouse -d '{"enabled": true}'` và xóa toxic: `curl -s -X DELETE localhost:8474/proxies/pg-warehouse/toxics/<name>`. Đánh dấu lần chạy thực nghiệm đó là không hợp lệ.

**Circuit breaker:** không cần reset tay. Khi DB hồi phục, lần thử ở trạng thái `HALF_OPEN` thành công sẽ đóng mạch và resume listener. Không restart `etl-stream` để "đóng mạch": restart không sửa DB, và mạch sẽ mở lại.

## Trên k3d

- Trạng thái cluster: `kubectl cnpg status pti-warehouse -n pti` (primary, replica, replication lag, WAL, dung lượng PVC).
- Postgres trên k3d không đi qua Toxiproxy. Trước tiên kiểm tra lỗi được tiêm còn sót: `kubectl -n pti get networkchaos,podchaos`; gỡ bằng `kubectl -n pti delete networkchaos,podchaos --all`.
- Kết nối đi qua PgBouncer (`pti-warehouse-pooler-rw`, `-ro`): client chờ trong pooler thấy qua `cnpg_pgbouncer_pools_cl_waiting` (Prometheus); Hikari của app vẫn là chỉ số chính.
- Nhánh A (không kết nối được): CNPG tự failover khi primary chết; kiểm tra primary mới có chưa. Chỉ can thiệp tay khi CNPG không tự làm được sau 2 phút: `kubectl cnpg promote pti-warehouse <pod replica> -n pti`. Không xóa PVC của primary cũ.
- Khởi động lại (thay cho `make restart S=pg-warehouse`): `kubectl cnpg restart pti-warehouse -n pti`.
- `DatabaseBottleneck` trên k3d còn chặn KEDA scale `etl-stream` (RB-02 mục "Trên k3d"); đó là hành vi mong muốn.
- Tăng tài nguyên: `postgresql.parameters`, `resources` của `Cluster` trong values rồi `make k8s-apply` (CNPG rolling restart, replica trước).

## Xác nhận đã xong

- `resilience4j_circuitbreaker_state{name="warehouse", state="closed"} == 1` trên mọi pod `etl-stream`.
- `pti:chunk_duration:p95_5m < 1` và lag đang giảm (RB-02).
- Không còn khóa chờ lâu (truy vấn bước 5 trả rỗng) và `hikaricp_connections_pending` = 0.

## Phòng ngừa và việc sau sự cố

- Câu chậm mới xuất hiện: thêm test hiệu năng SQL (DOC-44) hoặc index qua migration.
- Truy vấn tay trên warehouse: luôn đặt `SET statement_timeout = '30s';` và không để phiên `idle in transaction`.
- Ghi p95 chunk, số kết nối và `n_dead_tup` lúc sự cố vào issue.
