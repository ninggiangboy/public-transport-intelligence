# Role database và phân quyền

> Trạng thái: **Review** · Cập nhật: 2026-09-26 · DOC-17
> Phụ thuộc: [DR](../00-decision-register.md) (DR-20, 62, 64), [ADR-0024](../04-adr/0024-flyway-migration-job.md), [DOC-10](../03-architecture/quality-attributes.md) §4, [DOC-13](source-data.md), [DOC-14](warehouse-model.md), [DOC-15](ops-and-insight-model.md)
> Người dùng chính: module `db` (P1-05, P1-06), compose (P1-04), Helm/CNPG (P7), DOC-27

## 1. Nguyên tắc

1. **Quyền tối thiểu (NFR-06):** mỗi app có role riêng. Không app runtime nào có quyền DDL, `TRUNCATE` trên bảng thật, hay quyền owner.
2. **Hai tầng:**
   - **Bootstrap** (superuser, một lần khi tạo cluster): tạo login role, mật khẩu, database và owner.
   - **Flyway** (owner): tạo schema, object và grant.
   - Lý do tách (ADR-0024): `REPLICATION` cho `debezium` chỉ superuser mới cấp được; owner không cần `CREATEROLE`; mật khẩu không đi qua placeholder của Flyway nên không lọt vào log hay `flyway_schema_history`.
3. **Grant là nguồn sự thật duy nhất:** mỗi database có một file `R__grants.sql`. File này revoke hết rồi grant lại, nên chạy lại bao nhiêu lần cũng cho cùng kết quả, và đọc file là biết toàn bộ quyền.
4. **Quyền tới mức cột** ở những nơi nhiều app cùng ghi một bảng (`dead_letter`, `alert_event`, `runtime_flag`, `insight_dispatch_suggestion`). DB chặn được việc một app sửa cột không thuộc phần việc của nó.
5. **Thao tác đặc quyền đi qua hàm `SECURITY DEFINER`** do owner sở hữu (quản lý partition). Role runtime chỉ có `EXECUTE`.

## 2. Danh sách role

### 2.1 `pg-warehouse`

| Role | Loại | `CONNECTION LIMIT` | Dùng bởi | Kết nối tới |
| --- | --- | --- | --- | --- |
| `postgres` | superuser | — | Chỉ bootstrap và người vận hành khi sự cố | — |
| `pti_owner` | owner của `pti_warehouse` và mọi schema | không giới hạn | `db-migrate` | primary, trực tiếp (không qua PgBouncer) |
| `etl_writer` | runtime | 100 | etl-stream, etl-batch | primary |
| `triage_writer` | runtime | 20 | triage-worker | primary |
| `api_reader` | runtime, chỉ đọc | 80 | api (datasource `reader`) | replica (k3d), primary (compose) |
| `replay_operator` | runtime, ghi hẹp (DR-20) | 30 | api (datasource `operator`) | primary |
| `experiment_runner` | công cụ | 5 | experiment runner (Python) | primary |

Giới hạn kết nối lớn hơn tổng pool tối đa ở DOC-10 §4 (ví dụ `etl_writer`: 72 + 12 = 84 < 100). Nhờ vậy lỗi cấu hình pool bị chặn tại DB thay vì làm cạn `max_connections`.

### 2.2 `pg-source`

| Role | Loại | `CONNECTION LIMIT` | Dùng bởi | Database |
| --- | --- | --- | --- | --- |
| `ticketing_owner` | owner | không giới hạn | `db-migrate` | `ticketing_source` |
| `sim_owner` | owner | không giới hạn | `db-migrate` | `pti_sim` |
| `source_simulator` | runtime | 10 | source-simulator (hai pool, mỗi pool 4) | `ticketing_source`, `pti_sim` |
| `debezium` | `LOGIN REPLICATION` | 5 | Kafka Connect (một kết nối replication và một kết nối cho snapshot và heartbeat) | `ticketing_source` |
| `experiment_runner` | công cụ | 5 | experiment runner | `ticketing_source`, `pti_sim` |

Debezium luôn kết nối trực tiếp, không qua PgBouncer, vì PgBouncer không hỗ trợ giao thức replication.

## 3. Bootstrap

### 3.1 Compose

Script nằm trong `deploy/compose/postgres/<cluster>/` và được mount vào `/docker-entrypoint-initdb.d`. Image `postgres:17` chỉ chạy script khi thư mục data còn trống. Mật khẩu lấy từ biến môi trường của container (§6).

File `deploy/compose/postgres/warehouse/10-bootstrap.sh`:

```bash
#!/usr/bin/env bash
# Bootstrap for pg-warehouse. Runs once, as the superuser, when the data directory is empty
# (docker-entrypoint-initdb.d). On k3d the same roles are declared in the CNPG Cluster
# (spec.managed.roles) and the database in spec.bootstrap.initdb.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v pti_owner_pw="$PTI_OWNER_PASSWORD" \
  -v etl_writer_pw="$ETL_WRITER_PASSWORD" \
  -v triage_writer_pw="$TRIAGE_WRITER_PASSWORD" \
  -v api_reader_pw="$API_READER_PASSWORD" \
  -v replay_operator_pw="$REPLAY_OPERATOR_PASSWORD" \
  -v experiment_runner_pw="$EXPERIMENT_RUNNER_PASSWORD" <<'SQL'
CREATE ROLE pti_owner         LOGIN PASSWORD :'pti_owner_pw';
CREATE ROLE etl_writer        LOGIN PASSWORD :'etl_writer_pw'        CONNECTION LIMIT 100;
CREATE ROLE triage_writer     LOGIN PASSWORD :'triage_writer_pw'     CONNECTION LIMIT 20;
CREATE ROLE api_reader        LOGIN PASSWORD :'api_reader_pw'        CONNECTION LIMIT 80;
CREATE ROLE replay_operator   LOGIN PASSWORD :'replay_operator_pw'   CONNECTION LIMIT 30;
CREATE ROLE experiment_runner LOGIN PASSWORD :'experiment_runner_pw' CONNECTION LIMIT 5;

CREATE DATABASE pti_warehouse OWNER pti_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE pti_warehouse FROM PUBLIC;
SQL
```

File `deploy/compose/postgres/source/10-bootstrap.sh`:

```bash
#!/usr/bin/env bash
# Bootstrap for pg-source. Runs once, as the superuser, when the data directory is empty.
# The server must run with wal_level=logical (set in the compose command / CNPG parameters).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v ticketing_owner_pw="$TICKETING_OWNER_PASSWORD" \
  -v sim_owner_pw="$SIM_OWNER_PASSWORD" \
  -v source_simulator_pw="$SOURCE_SIMULATOR_PASSWORD" \
  -v debezium_pw="$DEBEZIUM_PASSWORD" \
  -v experiment_runner_pw="$EXPERIMENT_RUNNER_PASSWORD" <<'SQL'
CREATE ROLE ticketing_owner   LOGIN PASSWORD :'ticketing_owner_pw';
CREATE ROLE sim_owner         LOGIN PASSWORD :'sim_owner_pw';
CREATE ROLE source_simulator  LOGIN PASSWORD :'source_simulator_pw'  CONNECTION LIMIT 10;
-- REPLICATION can only be granted by a superuser, which is why roles live in the bootstrap.
CREATE ROLE debezium          LOGIN REPLICATION PASSWORD :'debezium_pw' CONNECTION LIMIT 5;
CREATE ROLE experiment_runner LOGIN PASSWORD :'experiment_runner_pw' CONNECTION LIMIT 5;

CREATE DATABASE ticketing_source OWNER ticketing_owner ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
CREATE DATABASE pti_sim          OWNER sim_owner       ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
REVOKE ALL ON DATABASE ticketing_source FROM PUBLIC;
REVOKE ALL ON DATABASE pti_sim FROM PUBLIC;
SQL
```

- Collation `C.UTF-8` với `template0`: sắp xếp theo byte, ổn định giữa các máy và các phiên bản glibc. Index trên `TEXT` không bị hỏng khi đổi image.
- Đổi mật khẩu sau khi cluster đã tạo không chạy lại script. Khi đó dùng `ALTER ROLE … PASSWORD` hoặc `make reset` (xóa volume).
- Database bảo trì `postgres` giữ quyền `CONNECT` mặc định cho PUBLIC. Database này không chứa dữ liệu, và công cụ giám sát cần nó.

### 3.2 k3d (CloudNativePG)

Cùng nội dung, khai báo trong Helm chart (DOC-40):

| Việc | Compose | CNPG |
| --- | --- | --- |
| Login role và mật khẩu | `10-bootstrap.sh` | `spec.managed.roles[]`, mỗi role có `passwordSecret` (Secret loại `kubernetes.io/basic-auth`), `connectionLimit`; `debezium` có `replication: true` |
| Database đầu tiên và owner | `CREATE DATABASE` | `spec.bootstrap.initdb.database` / `owner` (`pti_warehouse`/`pti_owner`, `ticketing_source`/`ticketing_owner`) |
| Database thứ hai trên `pg-source` (`pti_sim`) | `CREATE DATABASE` | Resource `Database` (`spec.name: pti_sim`, `spec.owner: sim_owner`) |
| `wal_level=logical` | tham số `command` của container | `spec.postgresql.parameters` |
| `REVOKE ALL ON DATABASE … FROM PUBLIC` | `10-bootstrap.sh` | `R__grants.sql` làm lại việc này (§5), nên không cần bước riêng |

Tên field của CNPG phải được xác minh lại với phiên bản operator đã chốt ở P7 (DOC-11).

## 4. Ma trận quyền

Ký hiệu: S = SELECT, I = INSERT, U = UPDATE, D = DELETE, T = TRUNCATE, X = EXECUTE, `U(cột…)` = UPDATE chỉ trên các cột đó. Ô trống là không có quyền.

### 4.1 `pti_warehouse`

| Đối tượng | `etl_writer` | `triage_writer` | `api_reader` | `replay_operator` | `experiment_runner` |
| --- | --- | --- | --- | --- | --- |
| `dw.*` (mọi bảng, view) | SIUD | S | S | | S |
| `dw.dim_date` | S | S | S | | S |
| `dw.ensure_partitions`, `dw.drop_partitions_before` | X | | | | |
| `batch.*` (bảng, sequence) | SIUD, sequence: USAGE, SELECT | | | | |
| `ops.etl_stream_batch`, `etl_batch_step`, `etl_checkpoint`, `dq_check_result` | SIUD | `etl_stream_batch`, `dq_check_result`: S | `etl_stream_batch`, `dq_check_result`: S | | S |
| `ops.shedlock` | SIUD | SIUD | | | S |
| `ops.dedup_registry` | SIUD | | | | |
| `ops.dead_letter` | S I D, U(status, stage, error_class, error_message, replay_count, last_replay_at, updated_at) | S, U(status, category, category_confidence, severity, severity_confidence, model_version, triaged_at, triage_attempts, triage_lease_until, auto_replay_count, updated_at) | S | S, U(status, edited_payload, resolved_by, resolved_at, updated_at) | S |
| `ops.dlq_action_log` | S I D | S I | S | S I | S |
| `ops.replay_request` | S D, U(status, job_execution_id, started_at, finished_at, stats, message) | S I | S | S I | S |
| `ops.job_request` | S D, U(status, job_execution_id, started_at, finished_at, message) | | S | S I | S |
| `ops.runtime_flag` | S | S | S | S I, U(value, updated_by, updated_at) | S |
| `ops.alert_event` | S I, U(title, body, severity, resolved_at) | S, U(audience, title, body, severity) | S | S I, U(acknowledged_by, acknowledged_at, resolved_at) | S |
| `ops.ops_job_run_v`, `ops.ops_job_step_v`, `ops.ops_job_execution_param_v` | | | S | | S |
| `insight.insight_*` (trừ dispatch) | SIUD | S, U(các cột enrichment, xem file) | S | | S |
| `insight.insight_dispatch_suggestion` | S | S I, U(action, action_confidence, state_snapshot, model_version) | S | S, U(operator_feedback, feedback_by, feedback_at) | S |
| `insight.analytics_*` | SIUD | S | | | S |
| `exp.*` | S I T | | | | S T |

Ghi chú:

- `etl_writer` có `DELETE` trên các bảng `ops` để các job retention chạy được (DOC-18). Role này không có `TRUNCATE` trên bảng thật.
- `api_reader` **không** đọc được `batch.*`, `ops.dedup_registry` và `insight.analytics_*`. API đọc metadata job qua các view `ops_job_run_v`, `ops_job_step_v` và `ops_job_execution_param_v` (DR-62, DOC-15 §5). `experiment_runner` có sẵn quyền đọc các view này qua `GRANT SELECT ON ALL TABLES IN SCHEMA ops`, vì view được tạo trong V5_2, trước khi `R__grants.sql` chạy.
- `replay_operator` **không** đọc được `dw.*`. Khi cần trả về bản ghi vừa ghi, API đọc bằng chính kết nối `operator` trên các bảng `ops`/`insight` mà role này có quyền `SELECT` (DR-20).
- Bảng có cột identity (`dlq_action_log`, `dq_check_result`) **không cần** quyền trên sequence: PostgreSQL không kiểm tra quyền sequence khi sinh giá trị identity. Đã kiểm tra với `etl_writer` và `replay_operator` (§7).

### 4.2 `ticketing_source` và `pti_sim`

| Đối tượng | `source_simulator` | `debezium` | `experiment_runner` |
| --- | --- | --- | --- |
| `public.sale_point`, `public.ticket_transaction` | SIUD | S (snapshot) | S |
| `public.debezium_heartbeat` | | S, U(ts) | |
| Publication `pti_ticketing`, replication slot | | Đọc qua `REPLICATION` | |
| `sim.sim_scenario_run` | S I U | | S |
| `sim.sim_ledger` | I | | S |
| `sim.ensure_ledger_partitions`, `sim.drop_ledger_partitions_before` | X | | |

File grant của hai database này nằm ở DOC-13 §5.3 và §6.3.

## 5. Flyway và `db-migrate`

| Thiết lập | `warehouse` | `ticketing` | `sim` |
| --- | --- | --- | --- |
| URL | `jdbc:postgresql://<pg-warehouse primary>:5432/pti_warehouse` | `jdbc:postgresql://<pg-source>:5432/ticketing_source` | `jdbc:postgresql://<pg-source>:5432/pti_sim` |
| User | `pti_owner` | `ticketing_owner` | `sim_owner` |
| `locations` | `classpath:db/migration/warehouse` | `classpath:db/migration/ticketing` | `classpath:db/migration/sim` |
| `defaultSchema` / `schemas` | `public` | `public` | `public` |

Chung cho cả ba:

- `createSchemas=false`: schema do V1 tạo, với `AUTHORIZATION` rõ ràng.
- `flyway_schema_history` nằm trong `public`. Từ PostgreSQL 15, `public` thuộc `pg_database_owner`, tức owner của database, nên owner ghi được vào đó. V1 của warehouse `REVOKE ALL ON SCHEMA public FROM PUBLIC`, nên role runtime không nhìn thấy bảng lịch sử.
- `placeholderReplacement=false`: không dùng placeholder, tránh việc Flyway hiểu nhầm chuỗi `${…}` trong SQL.
- `cleanDisabled=true`, `baselineOnMigrate=false`, `outOfOrder=false`, `validateOnMigrate=true`.
- Mỗi migration versioned chạy trong một transaction. Test thủ công bằng psql phải dùng `psql -1 -v ON_ERROR_STOP=1 -f …`.
- **Quy tắc cho `R__grants.sql`:** Flyway chỉ chạy lại migration repeatable khi checksum của nó đổi. Vì vậy **mọi migration có thêm bảng, view hay hàm đều phải sửa `R__grants.sql` trong cùng PR**, kể cả khi chỉ cần thêm dòng grant cho object mới. Test ở §7.3 bắt lỗi quên này.
- Khối `[P4] insight` của `R__grants.sql` warehouse chỉ được thêm cùng lúc với `V7__insight.sql` (P4-01). Nếu thêm sớm hơn, câu lệnh grant tham chiếu tới bảng chưa tồn tại và migration sẽ lỗi.

`db-migrate` là một app Java nhỏ trong module `db`. App chạy lần lượt ba bộ migration rồi thoát với mã 0; bộ nào lỗi thì thoát với mã khác 0 và ghi log bằng tiếng Anh. Mật khẩu của ba owner lấy từ biến môi trường `PTI_OWNER_PASSWORD`, `TICKETING_OWNER_PASSWORD`, `SIM_OWNER_PASSWORD`.

File `db/src/main/resources/db/migration/warehouse/R__grants.sql`:

```sql
-- pti_warehouse grants (DOC-17). Repeatable: Flyway re-runs it after the versioned migrations
-- whenever this file changes. It first revokes everything, so it is the complete truth.
-- Blocks marked [P4] are added together with V7__insight.sql.

DO $$
BEGIN
  -- Also done by the compose bootstrap; repeated here because CNPG (k3d) creates the database itself.
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO etl_writer, triage_writer, api_reader, replay_operator, experiment_runner',
                 current_database());
END $$;

REVOKE ALL ON ALL TABLES    IN SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;
REVOKE ALL ON SCHEMA dw, ops, insight, batch, exp
  FROM etl_writer, triage_writer, api_reader, replay_operator, experiment_runner;

-- ---------------------------------------------------------------- etl_writer (etl-stream, etl-batch)
GRANT USAGE ON SCHEMA dw, ops, insight, batch, exp TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dw TO etl_writer;
REVOKE INSERT, UPDATE, DELETE ON dw.dim_date FROM etl_writer;
GRANT EXECUTE ON FUNCTION dw.ensure_partitions(TEXT, DATE, DATE), dw.drop_partitions_before(TEXT, DATE) TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO etl_writer;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO etl_writer;

GRANT SELECT, INSERT, UPDATE, DELETE ON ops.etl_stream_batch, ops.etl_batch_step, ops.etl_checkpoint,
                                        ops.shedlock, ops.dedup_registry, ops.dq_check_result TO etl_writer;
GRANT SELECT, INSERT, DELETE ON ops.dead_letter TO etl_writer;                    -- DELETE: retention job
GRANT UPDATE (status, stage, error_class, error_message, replay_count, last_replay_at, updated_at)
  ON ops.dead_letter TO etl_writer;
GRANT SELECT, INSERT, DELETE ON ops.dlq_action_log TO etl_writer;
GRANT SELECT, DELETE ON ops.replay_request, ops.job_request TO etl_writer;
GRANT UPDATE (status, job_execution_id, started_at, finished_at, stats, message) ON ops.replay_request TO etl_writer;
GRANT UPDATE (status, job_execution_id, started_at, finished_at, message)        ON ops.job_request    TO etl_writer;
GRANT SELECT, INSERT ON ops.alert_event TO etl_writer;
GRANT UPDATE (title, body, severity, resolved_at) ON ops.alert_event TO etl_writer;
GRANT SELECT ON ops.runtime_flag TO etl_writer;

GRANT INSERT, SELECT, TRUNCATE ON ALL TABLES IN SCHEMA exp TO etl_writer;         -- profile experiment only

-- ---------------------------------------------------------------- triage_writer (triage-worker)
GRANT USAGE ON SCHEMA dw, ops, insight TO triage_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO triage_writer;                         -- context building
GRANT SELECT ON ops.dead_letter, ops.runtime_flag, ops.dq_check_result, ops.etl_stream_batch TO triage_writer;
GRANT UPDATE (status, category, category_confidence, severity, severity_confidence, model_version,
              triaged_at, triage_attempts, triage_lease_until, auto_replay_count, updated_at)
  ON ops.dead_letter TO triage_writer;
GRANT SELECT, INSERT ON ops.dlq_action_log, ops.replay_request TO triage_writer;
GRANT SELECT ON ops.alert_event TO triage_writer;                                 -- never inserts alerts (DOC-24 §10)
GRANT UPDATE (audience, title, body, severity) ON ops.alert_event TO triage_writer;  -- severity: ticketing (DOC-24 §7)
GRANT SELECT, INSERT, UPDATE, DELETE ON ops.shedlock TO triage_writer;

-- ---------------------------------------------------------------- api_reader (api, read replica)
GRANT USAGE ON SCHEMA dw, ops, insight TO api_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO api_reader;
GRANT SELECT ON ops.dead_letter, ops.dlq_action_log, ops.replay_request, ops.job_request,
                ops.runtime_flag, ops.dq_check_result, ops.alert_event, ops.etl_stream_batch,
                ops.ops_job_run_v, ops.ops_job_step_v, ops.ops_job_execution_param_v
  TO api_reader;                                                                  -- never batch.* directly

-- ---------------------------------------------------------------- replay_operator (api, primary, DR-20)
GRANT USAGE ON SCHEMA ops, insight TO replay_operator;
GRANT SELECT ON ops.dead_letter TO replay_operator;
GRANT UPDATE (status, edited_payload, resolved_by, resolved_at, updated_at) ON ops.dead_letter TO replay_operator;
GRANT SELECT, INSERT ON ops.replay_request, ops.job_request, ops.dlq_action_log TO replay_operator;
GRANT SELECT, INSERT ON ops.alert_event TO replay_operator;                       -- Alertmanager webhook
GRANT UPDATE (acknowledged_by, acknowledged_at, resolved_at) ON ops.alert_event TO replay_operator;
GRANT SELECT, INSERT ON ops.runtime_flag TO replay_operator;
GRANT UPDATE (value, updated_by, updated_at) ON ops.runtime_flag TO replay_operator;

-- ---------------------------------------------------------------- experiment_runner
GRANT USAGE ON SCHEMA dw, ops, insight, exp TO experiment_runner;
GRANT SELECT ON ALL TABLES IN SCHEMA dw, ops, insight, exp TO experiment_runner;
GRANT TRUNCATE ON ALL TABLES IN SCHEMA exp TO experiment_runner;
REVOKE SELECT ON ops.dedup_registry FROM experiment_runner;

-- ---------------------------------------------------------------- [P4] insight
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA insight TO etl_writer;
REVOKE INSERT, UPDATE, DELETE ON insight.insight_dispatch_suggestion FROM etl_writer;

GRANT SELECT ON ALL TABLES IN SCHEMA insight TO triage_writer;
GRANT UPDATE (enrichment_status, enrichment_attempts, enrichment_lease_until)
  ON insight.insight_bus_bunching TO triage_writer;
GRANT UPDATE (data_issue_probability, likely_cause, cause_confidence, model_version, enriched_at,
              enrichment_status, enrichment_attempts, enrichment_lease_until)
  ON insight.insight_service_disruption TO triage_writer;
GRANT UPDATE (category, category_confidence, severity, severity_confidence, model_version, enriched_at,
              enrichment_status, enrichment_attempts, enrichment_lease_until)
  ON insight.insight_ticketing_anomaly TO triage_writer;
GRANT INSERT, UPDATE (action, action_confidence, state_snapshot, model_version)
  ON insight.insight_dispatch_suggestion TO triage_writer;

GRANT SELECT ON insight.insight_bus_bunching, insight.insight_dispatch_suggestion, insight.insight_eta_prediction,
                insight.insight_service_disruption, insight.insight_otp_scorecard, insight.insight_ticketing_anomaly
  TO api_reader;

GRANT SELECT ON insight.insight_dispatch_suggestion TO replay_operator;
GRANT UPDATE (operator_feedback, feedback_by, feedback_at) ON insight.insight_dispatch_suggestion TO replay_operator;
```

## 6. Mật khẩu và secret

| Môi trường | Nơi lưu | Cách sinh | Ai đọc |
| --- | --- | --- | --- |
| Compose | `.env` ở gốc repo (có trong `.gitignore`). `.env.example` liệt kê tên biến, không có giá trị | `make secrets`: sinh mỗi biến bằng `openssl rand -base64 24` nếu biến còn trống | Container Postgres (bootstrap), `db-migrate`, từng app qua biến `SPRING_DATASOURCE_*` |
| k3d | Sealed Secret cho mỗi role (Secret loại `kubernetes.io/basic-auth`) | Người vận hành sinh rồi `kubeseal`; file đã mã hóa được commit | CNPG (`managed.roles[].passwordSecret`), Deployment của app (`secretKeyRef`), Kafka Connect (`DirectoryConfigProvider` đọc file mount từ Secret, DOC-40 §6.3) |

Danh sách biến: `PTI_OWNER_PASSWORD`, `ETL_WRITER_PASSWORD`, `TRIAGE_WRITER_PASSWORD`, `API_READER_PASSWORD`, `REPLAY_OPERATOR_PASSWORD`, `EXPERIMENT_RUNNER_PASSWORD`, `TICKETING_OWNER_PASSWORD`, `SIM_OWNER_PASSWORD`, `SOURCE_SIMULATOR_PASSWORD`, `DEBEZIUM_PASSWORD`. Hai cluster dùng chung `EXPERIMENT_RUNNER_PASSWORD`.

- Không bao giờ ghi mật khẩu vào log, vào `application.yml` đã commit, hay vào connector config dạng plain text.
- Đổi mật khẩu trên k3d: cập nhật Sealed Secret; CNPG tự `ALTER ROLE`; sau đó rollout restart các Deployment liên quan. Runbook RB-12 (DOC-42).

## 7. Kiểm thử quyền bắt buộc

Mỗi thao tác chạy trong một subtransaction luôn bị rollback, nên bộ test không để lại dữ liệu. Kết quả được phân loại: **được phép** (câu lệnh chạy xong), **bị từ chối** (lỗi `42501 insufficient_privilege`), hay lỗi khác. Lỗi khác nghĩa là bản thân test sai (ví dụ dữ liệu mẫu vi phạm `CHECK`) và làm test thất bại. P1-06 chuyển bộ này thành integration test (Testcontainers) trong module `db`, dùng đúng các trường hợp dưới đây.

### 7.1 `pti_warehouse` (46 trường hợp; 1–41 đã pass ở spike, 42–44 thêm khi viết DOC-32, 45–46 thêm khi viết DOC-24)

| # | Role | Thao tác | Kỳ vọng |
| --- | --- | --- | --- |
| 1 | `api_reader` | select fact | được phép |
| 2 | `api_reader` | select ops view | được phép |
| 3 | `api_reader` | select batch.* | **bị từ chối** |
| 4 | `api_reader` | insert fact | **bị từ chối** |
| 5 | `api_reader` | update dead_letter | **bị từ chối** |
| 6 | `api_reader` | select dedup_registry | **bị từ chối** |
| 7 | `api_reader` | select analytics state | **bị từ chối** |
| 8 | `etl_writer` | drop fact | **bị từ chối** |
| 9 | `etl_writer` | truncate fact | **bị từ chối** |
| 10 | `etl_writer` | create table in dw | **bị từ chối** |
| 11 | `etl_writer` | create table in public | **bị từ chối** |
| 12 | `etl_writer` | write dim_date | **bị từ chối** |
| 13 | `etl_writer` | insert dead_letter | được phép |
| 14 | `etl_writer` | etl edits triage cols | **bị từ chối** |
| 15 | `etl_writer` | dlq_action_log identity insert | được phép |
| 16 | `etl_writer` | dq_check_result identity insert | được phép |
| 17 | `etl_writer` | write runtime_flag | **bị từ chối** |
| 18 | `etl_writer` | insert dispatch suggestion | **bị từ chối** |
| 19 | `etl_writer` | batch sequence | được phép |
| 20 | `etl_writer` | ensure_partitions | được phép |
| 21 | `triage_writer` | triage update | được phép |
| 22 | `triage_writer` | triage edits payload | **bị từ chối** |
| 23 | `triage_writer` | triage resolves | **bị từ chối** |
| 24 | `triage_writer` | insert fact | **bị từ chối** |
| 25 | `triage_writer` | ensure_partitions | **bị từ chối** |
| 26 | `triage_writer` | write runtime_flag | **bị từ chối** |
| 27 | `replay_operator` | resolve dead letter | được phép |
| 28 | `replay_operator` | edit triage cols | **bị từ chối** |
| 29 | `replay_operator` | delete dead letter | **bị từ chối** |
| 30 | `replay_operator` | toggle flag | được phép |
| 31 | `replay_operator` | rename flag | **bị từ chối** |
| 32 | `replay_operator` | action log identity insert | được phép |
| 33 | `replay_operator` | read facts | **bị từ chối** |
| 34 | `replay_operator` | job status update | **bị từ chối** |
| 35 | `replay_operator` | feedback | được phép |
| 36 | `replay_operator` | rewrite suggestion | **bị từ chối** |
| 37 | `experiment_runner` | read facts | được phép |
| 38 | `experiment_runner` | truncate exp | được phép |
| 39 | `experiment_runner` | truncate dw | **bị từ chối** |
| 40 | `experiment_runner` | read dedup | **bị từ chối** |
| 41 | `experiment_runner` | read batch | **bị từ chối** |
| 42 | `api_reader` | select `ops_job_step_v` | được phép |
| 43 | `api_reader` | select `ops_job_execution_param_v` | được phép |
| 44 | `experiment_runner` | select `ops_job_step_v` | được phép |
| 45 | `triage_writer` | insert `alert_event` | **bị từ chối** |
| 46 | `triage_writer` | update `alert_event.severity`, `audience`, `body` | được phép |

### 7.2 `pg-source` (19 trường hợp, đã pass cả 19)

| # | Role | Database | Thao tác | Kỳ vọng |
| --- | --- | --- | --- | --- |
| 1 | `source_simulator` | `ticketing_source` | insert sale point + sale | được phép |
| 2 | `source_simulator` | `ticketing_source` | write heartbeat | **bị từ chối** |
| 3 | `source_simulator` | `ticketing_source` | drop table | **bị từ chối** |
| 4 | `source_simulator` | `ticketing_source` | create table | **bị từ chối** |
| 5 | `source_simulator` | `ticketing_source` | alter publication | **bị từ chối** |
| 6 | `debezium` | `ticketing_source` | snapshot select | được phép |
| 7 | `debezium` | `ticketing_source` | heartbeat action query | được phép |
| 8 | `debezium` | `ticketing_source` | insert business row | **bị từ chối** |
| 9 | `debezium` | `ticketing_source` | create pgoutput slot | được phép |
| 10 | `experiment_runner` | `ticketing_source` | read ground truth | được phép |
| 11 | `experiment_runner` | `ticketing_source` | write | **bị từ chối** |
| 12 | `source_simulator` | `pti_sim` | insert ledger | được phép |
| 13 | `source_simulator` | `pti_sim` | update ledger | **bị từ chối** |
| 14 | `source_simulator` | `pti_sim` | delete ledger | **bị từ chối** |
| 15 | `source_simulator` | `pti_sim` | drop old ledger partitions | được phép |
| 16 | `source_simulator` | `pti_sim` | create table | **bị từ chối** |
| 17 | `experiment_runner` | `pti_sim` | read ledger | được phép |
| 18 | `experiment_runner` | `pti_sim` | call partition fn | **bị từ chối** |
| 19 | `debezium` | `pti_sim` | connect | **bị từ chối** |

### 7.3 Test tính đầy đủ

Ngoài các trường hợp trên, module `db` có hai test đọc catalog:

1. **Mọi bảng và view** trong `dw`, `ops`, `insight` có ít nhất một grant cho một role runtime. Test này bắt lỗi thêm bảng mà quên sửa `R__grants.sql` (§5).
2. **Không role runtime nào** có quyền `TRUNCATE` (ngoài `exp`), `REFERENCES`, `TRIGGER`, `CREATE` trên schema, hay là owner của bất kỳ object nào.

## 8. Checklist cho người triển khai

- [ ] Hai script bootstrap nằm trong `deploy/compose/postgres/`, compose mount đúng và truyền đủ biến môi trường.
- [ ] `db-migrate` chạy cả ba bộ migration; chạy lại lần hai không lỗi.
- [ ] Integration test cho 46 + 19 trường hợp ở §7 và hai test ở §7.3.
- [ ] Mỗi app dùng đúng role ở §2. API có hai datasource `reader`/`operator` (DR-20).
- [ ] `.env.example` và `make secrets` có đủ 10 biến ở §6.
