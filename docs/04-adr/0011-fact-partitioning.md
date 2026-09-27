# ADR-0011: Partition bảng fact theo ngày và job bảo trì partition

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-15, DR-65, FR-04.3, DOC-10, DOC-14, DOC-18

## Bối cảnh

`fact_vehicle_position` nhận khoảng 5,7 triệu dòng mỗi ngày thường (DOC-10). Dữ liệu cũ phải xóa theo retention (14/90/365 ngày). `DELETE` hàng triệu dòng sinh nhiều WAL và bloat. Truy vấn analytics và API gần như luôn lọc theo khoảng ngày.

## Các phương án

1. **Không partition, xóa bằng DELETE theo lô.** Đơn giản, nhưng tốn và làm bảng phình.
2. **Declarative partitioning theo RANGE ngày, bảo trì bằng pg_partman.** Thêm extension; khó test trong Testcontainers với image chuẩn.
3. **Declarative partitioning theo RANGE ngày, bảo trì bằng job của dự án** (Spring Batch tasklet).
4. TimescaleDB. Thêm extension và license riêng; quá mức cần thiết.

## Quyết định

Chọn **phương án 3**.

| Bảng | Cột partition | Độ rộng | Retention mặc định |
| --- | --- | --- | --- |
| `dw.fact_vehicle_position` | `service_date` | 1 ngày | 14 ngày (compose: 3 ngày) |
| `dw.fact_trip_update` | `service_date` | 1 ngày | 90 ngày (compose: 30 ngày) |
| `dw.fact_ticket_sales` | `sale_date` | 1 tháng | 365 ngày |

- Tên partition: `<bảng>_pYYYYMMDD` (ngày) hoặc `<bảng>_pYYYYMM` (tháng).
- Có **partition DEFAULT** để record có ngày ngoài dự kiến không làm hỏng chunk. Rule post-write cảnh báo khi partition DEFAULT có dòng, và job bảo trì chuyển các dòng đó sang đúng partition.
- `PartitionMaintenanceJob` (tasklet, chạy 00:15 giờ agency và lúc khởi động `etl-batch`, dưới ShedLock):
  1. Gọi `dw.ensure_partitions(table, from_date, to_date)` để tạo trước partition cho D…D+7 (idempotent).
  2. Gọi `dw.drop_partitions_before(table, cutoff_date)` để `DETACH` rồi `DROP` các partition cũ hơn retention. Không dùng `CONCURRENTLY` vì lệnh này không chạy được trong hàm; partition cũ không còn ai ghi nên khóa trên bảng cha chỉ giữ trong vài mili giây.
  3. Ghi kết quả vào `ExecutionContext` và metric.
- Hai hàm trên là `SECURITY DEFINER`, thuộc sở hữu của `pti_owner`, chỉ nhận tên bảng trong danh sách cho phép và tự sinh tên partition. `etl_writer` chỉ có quyền `EXECUTE`, không có quyền DDL (DOC-17).
- Migration V4 tạo partition cho 7 ngày quanh ngày chạy migration, để hệ thống chạy được ngay cả khi job chưa chạy lần nào.
- Business key của bảng partitioned phải chứa cột partition (ràng buộc của PostgreSQL), nên key là `(service_date, …)` và `(sale_date, …)` (ADR-0003).
- Retention cấu hình qua `pti.retention.*` (DOC-29).

## Hệ quả

- Xóa dữ liệu cũ gần như tức thời, không sinh bloat.
- Truy vấn có điều kiện theo `service_date` được partition pruning.
- Logic tạo và xóa partition nằm trong SQL (hai hàm), nên test được bằng Testcontainers mà không cần chạy job.
- Có thêm một job phải được test (tạo, drop, idempotent khi chạy lại).
