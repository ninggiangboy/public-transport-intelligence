# ADR-0024: Flyway chạy như job riêng; schema thay đổi theo expand/contract

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: SDD §12.4, DR-26, DR-62, DR-64, DOC-14, DOC-15, DOC-17, DOC-39, DOC-40

## Bối cảnh

Nhiều app (etl hai profile, triage-worker, api, simulator) dùng chung database; trên k3d mỗi app có nhiều pod. Nếu từng app tự migrate lúc khởi động thì các pod tranh nhau chạy migration, app runtime phải có quyền DDL (vi phạm quyền tối thiểu, NFR-06), và rolling update có thể làm pod cũ lỗi khi schema đổi.

## Các phương án

1. **Mỗi app tự chạy Flyway khi khởi động.** Đơn giản, nhưng gặp đủ các vấn đề trên.
2. **Một app "sở hữu" migration** (ví dụ `etl`). Vẫn phải có quyền DDL ở runtime; thứ tự khởi động bị ràng buộc.
3. **Container/Job `db-migrate` riêng**, chạy xong rồi thoát, dùng user owner; app runtime không có quyền DDL.

## Quyết định

Chọn **phương án 3**.

- Module `db` đóng gói Flyway và toàn bộ script; image `pti-db-migrate`. Script nằm ở `db/src/main/resources/db/migration/<bộ>/`, Flyway đọc qua `classpath:db/migration/<bộ>`.
- Ba bộ migration, mỗi bộ có `flyway_schema_history` riêng (đặt trong schema `public`):
  - `warehouse` → `pti_warehouse` (schema `dw`, `ops`, `insight`, `batch`, `exp`), user `pti_owner`.
  - `ticketing` → `ticketing_source`, user `ticketing_owner`.
  - `sim` → `pti_sim`, user `sim_owner`.
- **Tách hai tầng quyền (sửa 2026-09-26):**
  - **Bootstrap (superuser, chạy một lần khi tạo cluster):** tạo login role, đặt mật khẩu, tạo database và chủ sở hữu. Compose dùng script `deploy/compose/postgres/<cluster>/10-bootstrap.sh` trong `docker-entrypoint-initdb.d`. k3d dùng `managed.roles` và `bootstrap.initdb` của CloudNativePG, mật khẩu lấy từ Sealed Secret.
  - **Flyway (user owner):** chỉ tạo schema, object và grant. Grant nằm trong migration repeatable `R__grants.sql`, file này revoke hết rồi grant lại nên luôn là nguồn sự thật duy nhất.
  - Lý do: `REPLICATION` (cho user `debezium`) chỉ superuser mới cấp được; owner không cần `CREATEROLE`; mật khẩu không đi qua placeholder của Flyway nên không lọt vào log hay `flyway_schema_history`. Chi tiết ở DOC-17.
- DDL của Spring Batch lấy nguyên từ `schema-postgresql.sql` của đúng phiên bản `spring-batch-core`, đặt thành một migration (DR-62).
- Compose: `db-migrate` chạy sau khi Postgres healthy; các app `depends_on: db-migrate: condition: service_completed_successfully`.
- k3d: Kubernetes Job trong Helm pre-install/pre-upgrade hook.
- App runtime đặt `spring.flyway.enabled=false`.
- Mỗi migration versioned chạy trong một transaction (mặc định của Flyway với PostgreSQL). Test bằng psql phải dùng `psql -1` để có cùng hành vi.
- **Expand/contract:** mỗi thay đổi phá vỡ tương thích được tách thành: (1) migration thêm cấu trúc mới; (2) release code dùng cấu trúc mới và vẫn chịu được cấu trúc cũ; (3) migration ở release sau xóa cấu trúc cũ. Không `RENAME` cột trong một bước.
- Migration phải chạy lại được trên DB đã migrate (Flyway bỏ qua) và có test Testcontainers chạy trên DB trống.

## Hệ quả

- Thêm một container chạy một lần. Thời gian `make up` tăng vài giây.
- App runtime không cần quyền DDL, nên test grant bảo vệ được điều này (P1-06).
- Riêng partition được tạo và xóa lúc runtime (ADR-0011). Việc này đi qua hai hàm `SECURITY DEFINER` do `pti_owner` sở hữu, nên `etl_writer` vẫn không có quyền DDL, chỉ có `EXECUTE` trên hai hàm đó (DOC-17).
