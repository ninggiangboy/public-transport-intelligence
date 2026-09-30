# Sổ quyết định mở (Decision Register)

> Trạng thái tài liệu: **Approved**, toàn bộ DR đã chốt ngày 2026-09-26 · Cập nhật: 2026-09-30 · Nguồn: phân tích `public-transport-intelligence.md` (gọi tắt là **SDD gốc**)

Tài liệu gốc mô tả tốt *cái gì* và *vì sao*, nhưng còn nhiều chỗ chưa trả lời *chính xác như thế nào*. Nếu không chốt trước, người triển khai sẽ phải dừng lại hỏi hoặc tự đoán, và đoán sai ở tầng dữ liệu thì rất tốn công sửa. Sổ này liệt kê từng khoảng trống, mỗi mục kèm **một phương án đề xuất** để có thể duyệt nhanh.

**Cách dùng**

- Mỗi mục có trạng thái: `Đề xuất` → (người sở hữu duyệt) → `Chốt` hoặc `Đổi` (ghi phương án thay thế).
- Mục nào đã chốt thì chuyển vào tài liệu đích (cột *Ghi vào*). Mục ở cấp kiến trúc sẽ thành ADR trong `docs/04-adr/`.
- Mục đánh dấu **⚠ lệch SDD gốc** là chỗ đề xuất khác với tài liệu gốc; lý do được ghi kèm.
- Mục đánh dấu **🔬 spike** cần thử nghiệm ngắn (≤ 1 ngày) trước khi chốt.

## Nhật ký chốt

| Ngày | Người chốt | Nội dung | Mục bị ảnh hưởng |
| --- | --- | --- | --- |
| 2026-09-26 | Owner | **Phạm vi: làm đầy đủ**, không cắt module nào. Thứ tự cắt giảm chỉ giữ lại làm phương án dự phòng | Master plan §4.3 |
| 2026-09-26 | Owner | **Hạ tầng: chạy full stack** (Keycloak, observability đầy đủ), không dùng bản rút gọn | DR-40, DR-50 |
| 2026-09-26 | Owner | **Phiên bản: dùng bản mới nhất** → Spring Boot 4.1.x | DR-53 |
| 2026-09-26 | Owner | **Ngôn ngữ: tài liệu `docs/` viết tiếng Việt, mọi thứ khác dùng tiếng Anh** (UI, code, comment, log, commit, PR, thông báo lỗi API) | DR-48, DR-61 (mới) |
| 2026-09-26 | Owner | **Repo: GitHub private** (đã đổi thành public ngày 2026-09-27) | DR-56 |
| 2026-09-26 | Owner | **Jev: dùng TypeSafe Jev qua Java SDK của spring-ai-community** (theo bài blog Spring 2026-09-21) | DR-36, DR-37 |
| 2026-09-26 | Owner | **ETL dùng Spring Batch (job batch) và Spring Kafka (streaming) thay cho engine chunk tự xây**; tận dụng hệ sinh thái Spring (ShedLock, Spring Cloud AWS S3, Micrometer Tracing) | ADR-0002, DR-15, 16, 21–24, 26, 27, 43, 50, 53, 62, 63 (mới) |
| 2026-09-26 | Owner | **Feed GTFS: một thành phố nước ngoài cỡ vừa** → sau spike S-02 chốt Metro Transit, Minneapolis–St. Paul | DR-01, 06, 09, 11, 48 |
| 2026-09-26 | Owner | **Chấp nhận toàn bộ các đề xuất còn lại.** Mọi DR chuyển sang trạng thái Chốt; các điểm 🔬 vẫn cần spike để xác minh chi tiết, nhưng hướng đi đã cố định | Tất cả |
| 2026-09-26 | Owner | **Bổ sung khi viết tài liệu Phase 0:** DR-63 (`batch_id` ở hai chế độ), DR-64 (bố cục database/schema), DR-65 (giới hạn trạm trong TripUpdate); điều chỉnh số liệu DR-15 và TTL của DR-16 theo số đo trên feed | DR-15, 16, 20, 63, 64, 65 |
| 2026-09-26 | Owner | **Chấp nhận các quyết định phát sinh khi viết mô hình dữ liệu** (DOC-13, 14, 15, 17): role tạo ở bootstrap superuser, Flyway chỉ tạo object và grant, `R__grants.sql` revoke quyền database của PUBLIC; mọi migration thêm object phải sửa `R__grants.sql`; headway theo trạm tham chiếu; gán xe theo nửa đội xe chẵn/lẻ ngày; ledger giữ 2 ngày; `dedup_registry` UNLOGGED; `REPLICA IDENTITY FULL`; khóa disruption gồm `direction_id`; autovacuum theo partition cho `fact_trip_update`, fillfactor 50 cho `vehicle_position_latest`; cửa sổ `GET /ops/job-runs` tối đa 24 giờ; retention mặc định (DOC-15 §3.1, DOC-29 §3.3); bảng giá và mã điểm bán mô phỏng; máy dev cần 80 GB ổ trống | DR-02, 06, 09, 12, 16, 17, 18, 28, 29, 64, 65 |
| 2026-09-26 | Claude (Owner ủy quyền) | **Object storage: SeaweedFS thay MinIO** sau spike S-03, vì image MinIO không còn phát hành | DR-66 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Đồng hồ nghiệp vụ** `pti.clock.offset` dùng chung cho simulator, etl, api (thay `pti.sim.time-offset`), vì giờ Chicago lệch 12–13 giờ so với Việt Nam; **tạo tải gấp N lần bằng tần suất phát**, không nhân bản xe | DR-08, DR-67, DR-68 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Quy tắc gán stage DLQ** theo loại rule (SCHEMA / QUALITY / BUSINESS / DEDUP); bản cũ hơn của cùng key trong một chunk được gộp, không vào DLQ | DR-69, FR-02.3, FR-02.4 |
| 2026-09-27 | Claude (Owner ủy quyền) | Làm rõ DR-22: analytics nhận `MicroBatchCommitted` bằng `@EventListener` + `@Async` vì sự kiện được phát sau khi transaction đã commit | DR-22, DOC-19 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Replay raw zone theo giờ record Kafka**; DLQ idempotent theo vị trí Kafka; replay thành công tự đóng dead letter; `GtfsStaticLoadJob` định danh bằng `runKey` | DR-70 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Analytics chạy theo lưới event time có con trỏ trong DB** (bunching 15 giây, gián đoạn bucket 1 phút, ticketing cửa sổ 15 phút), để trực tiếp và tính lại cho cùng kết quả; làm rõ DR-30 (thời điểm leader qua trạm lấy từ lịch sử vị trí); `AnalyticsRecomputeJob` | DR-30, DR-31, DR-34, DOC-23 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Metric thay cho exporter** (độ tươi nguồn phát từ `api`, WAL của slot từ simulator, trạng thái connector từ `etl-stream`); DR-57 chia thành bốn histogram theo chặng; chốt số runbook | DR-71, DR-57 |
| 2026-09-27 | Claude (Owner ủy quyền) | **API, SSE và bảo mật (tài liệu P4):** bỏ tiền tố `/ops/**`, mọi endpoint vận hành nằm dưới `/etl/**`; `/vehicles/live` là snapshot không phân trang (ngoại lệ FR-10.3); `api` cũng publish lên `pti.events.ui` và có thêm kiểu `alert.retracted`; replay estimate dựa trên nhật ký micro-batch vì API không có credential S3; SSE phát bù qua pod khác nhờ mỗi pod nạp sẵn buffer từ Kafka; token frontend chỉ giữ trong bộ nhớ; `anyRequest().denyAll()` cho endpoint chưa khai báo; k3d `lite` dùng JWT khóa tĩnh | DR-40–45, FR-10.3, DOC-26, 27, 31, 32, 33 |
| 2026-09-27 | Claude (Owner ủy quyền) | **UX/UI (tài liệu P5):** SPA React + Vite, route và bộ lọc nằm trên URL; token chỉ trong bộ nhớ với `signinSilent`; cấu hình runtime qua `env.js` do entrypoint nginx render; bản đồ nền PMTiles cục bộ (`make tiles`), dev dùng OpenFreeMap; toast cho phản hồi gợi ý điều phối nhưng không cho ack; `link` của alert do API tính theo bảng cố định và có cả trong SSE; catalog kịch bản simulator có định dạng tham số để UI sinh form; `requested_by` của simulator là `user:<username>`; màn Controls tách riêng khỏi Jobs; E2E cần dữ liệu cũ chạy trong Playwright project `late` | DR-46–49, DOC-34–37, ADR-0020, ADR-0021 |
| 2026-09-27 | Claude (Owner ủy quyền) | **AI triage (tài liệu P6):** lease kiêm backoff; hành động do bảng quyết định và guard trong code sở hữu; triage-worker không ghi `alert_event`, cảnh báo DLQ qua Prometheus; luật chặn cuối bằng `DlqRuleClassifier` trong etl; demo auto-replay bằng kịch bản `late-delivery` và `PTI_DQ_MAX_CLOCK_SKEW=5m`; định dạng `model_version`; KEDA PostgreSQL scaler cho triage-worker (1→3); virtual thread cho `api` và `triage-worker` | DR-37, DR-72, DR-73, DR-74, ADR-0018, ADR-0019 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Kubernetes và CI (tài liệu P7–P8):** API đọc qua JDBC nhiều host (`-ro` rồi `-rw`) để không mất đọc khi failover; `etl-stream` tối đa 4 pod; CI toàn stack chạy tuần một lần trên self-hosted runner (sau đó đổi sang runner GitHub hằng đêm khi repo chuyển public); test k3d mang mã `KD-xx` | DR-75, DR-76 |
| 2026-09-27 | Claude (Owner ủy quyền) | **Demo và báo cáo (tài liệu P8):** demo hai phần (compose rồi k3d dựng sẵn và dừng), kịch bản bunching/gián đoạn gieo trước; lệnh `make` vận hành dùng chung cho k3d qua `PTI_ENV`; báo cáo lấy số liệu duy nhất từ `pti-exp report` | DR-77, DR-78, DR-79 |
| 2026-09-28 | Owner | **Không dùng Quartz Scheduler**; giữ `@Scheduled` + ShedLock cho lịch job batch | DR-83 (mới), ADR-0015 |
| 2026-09-28 | Owner | **Không dùng Apache Spark**; giữ Spring Batch + Spring Kafka cho cả batch lẫn streaming. Khi cần phân tích trên lịch sử dài thì thử DuckDB trước | DR-84 (mới), ADR-0002 |
| 2026-09-27 | Owner | **Repo chuyển sang GitHub public** (thay quyết định private): runner chuẩn của GitHub đủ 16 GB để chạy E2E và k3d, không cần self-hosted runner; image GHCR để public | DR-56, DR-76 |
| 2026-09-28 | Owner | **Hoãn S-01 (spike Jev) tới đầu P6** vì chưa có API key; S-01 không còn là điều kiện thoát M0, chỉ là điều kiện của P6-01. DOC-24 đã có phương án chốt cho mọi kết quả của S-01 | DR-36 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-06 xong:** Boot 4.1.1 + Java 25 dùng được với mọi thư viện đã chọn, không cần lối lui. Chunk step của job batch dựng bằng builder fault-tolerant cũ của Spring Batch 6, vì `ChunkOrientedStep` mới làm mất DLQ và bỏ sót item khi crash giữa lúc scan | DR-53, DR-80 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-04 xong:** image Connect = Debezium 3.6.3 + Aiven S3 sink 3.4.3. Raw zone lưu value dạng base64 để giữ đúng từng byte; thư mục giờ theo CreateTime; `file.max.records=2000` và `mem_limit` 1.280 MB để S3 sink không OOM khi chạy bù. Debezium chạy được trên PostgreSQL 18.6; vẫn dùng 17.11 tới khi kiểm xong CNPG | DR-81, DR-53, DR-66 |
| 2026-09-28 | Claude (Owner ủy quyền) | **S-05 xong:** bản đồ nền Twin Cities 84 MB, render offline không có request ra ngoài. Dùng MapLibre 6 (worker cùng origin, CSP không cần `blob:`); font và sprite tải bằng `make tiles` thay vì commit vào repo | DR-47, DR-82 |
| 2026-09-28 | Owner | **Monorepo:** backend, frontend, hạ tầng và thực nghiệm chung một repo. Gốc chia theo stack: Gradle build gom vào `backend/`, mọi thứ hạ tầng (kể cả `connect/`, `chaos/`, bản đồ nền) gom vào `deploy/`, bỏ thư mục `infra/` | DR-85 (mới), DR-26, ADR-0030 |
| 2026-09-28 | Owner | **Simulator mặc định không phát:** `make up` dựng simulator ở hệ số 0; `make sim-start` bật khi cần dữ liệu. `make up-demo`, `make up-exp` và `make smoke` tự bật; tạm dừng có chủ đích không làm bắn `GtfsRtFeedStale` | DR-86 (mới), DR-68, DOC-25, DOC-28, DOC-38, DOC-39 |
| 2026-09-28 | Owner | **Demo console:** thêm trang web chạy trên host (`pti-exp console`, cổng 8095) để bấm nút kích hoạt các bước demo, kể cả thao tác hạ tầng, và xem topology sống, timeline, chỉ số. Dùng lại adapter của runner. Demo control trong sản phẩm giữ nguyên; lệnh `make demo-*` vẫn là phương án dự phòng | DR-87 (mới), DOC-48 (mới), DOC-46 |
| 2026-09-28 | Owner | **Giao diện theo prototype "Wayfinding":** token mới (Geist, canvas xám nhạt, một màu nhấn indigo, motif route shield và line-and-stop strip), sidebar chung thay thanh trên, thêm màn Overview, danh sách + khung chi tiết cho Alerts, Dead letters, Ticketing. Dữ liệu vẫn theo DOC-32; khối minh họa không có dữ liệu thì thay hoặc bỏ | DR-88 (mới), DOC-34–37, DOC-48 |
| 2026-09-28 | Owner | **Duyệt toàn bộ tài liệu:** master plan và DOC-01…48 chuyển từ Review sang Approved, gồm cả các gate tài liệu P0-08…14 và P2-00…P8-00 | Master plan §5, `docs/README.md` |
| 2026-09-28 | Claude (Owner ủy quyền) | **S3 sink OOM khi chạy live (P1-14):** Aiven 3.4.3 cắt file mỗi 10 giây trên mỗi partition và giữ buffer của writer tới lần commit, nên với commit 5 phút task chết sau vài phút có traffic. Giữ 3.4.3; `aws.s3.part.size.bytes` = 1 MiB, worker commit mỗi 30 giây. Số object raw zone tăng khoảng 15 lần; replay (P3) phải xem lại `pti.replay.max-objects` | DR-89 (mới), sửa DR-81, ADR-0012, DOC-09 §7, DOC-39 §3.4, DOC-40, DOC-22 §4.3 |
| 2026-09-29 | Claude (Owner ủy quyền) | **Phase 2 xong:** claim yêu cầu job/replay commit trước khi gọi `JobOperator`; replay raw zone liệt kê object theo giờ thay vì lưu danh sách (đóng mục mở của DR-89); hoãn DQ-27 sang P3; các chi tiết nhỏ khác | DR-90, DR-91, DR-92, DR-93 (mới) |
| 2026-09-29 | Owner | **Máy thực nghiệm và lưu kết quả:** thực nghiệm chính thức chạy trên một máy riêng cố định 16 GB (không gắn với máy cụ thể), không chạy trên máy dev hay GitHub Actions; file kết quả nhỏ commit vào git, file lớn gói theo chuỗi lên GitHub Release `exp-results` | DR-94 (mới) |
| 2026-09-30 | Owner | **Lưu kết quả smoke chốt milestone:** commit nguyên chuỗi `p3-08-d` (cấu hình và kết quả); `.gitignore` gốc thôi bỏ qua `experiments/results/` | DR-102 (mới), DOC-45 §1.3 |
| 2026-09-30 | Claude (Owner ủy quyền) | **Backup và khôi phục (P3-09):** `pg_dump` qua `docker compose exec`; manifest đếm trước khi dump; khôi phục áp lại `R__grants.sql`; thêm `make s3-shell` | DR-101 (mới), DOC-43 |
| 2026-09-30 | Claude (Owner ủy quyền) | **DQ-07 khi replay và runner thực nghiệm (P3-06…08):** replay kiểm DQ-07 theo lúc publish thay vì bỏ qua; EXP-04 smoke chỉ so key TripUpdate có đủ lịch sử trong cửa sổ; đóng cửa sổ bằng hệ số 0; bỏ DQ-27 | DR-100 (mới), DR-16, DR-92, DOC-16 §2, EXP-04, DOC-45 §1.3 |
| 2026-09-30 | Claude (Owner ủy quyền) | **Alert (P3-05):** alert đếm sự kiện rời rạc tính cả giá trị đầu tiên của series mới (`events()`); `CircuitBreakerOpen` tính cả `half_open`; gauge phụ thuộc DB/Connect không được chặn scrape; kết quả O-08 ghi ở DOC-42 §4 | DR-99 (mới), DOC-28 §6.1, §6.3, §9, DOC-42 §4 |
| 2026-09-29 | Claude (Owner ủy quyền) | **Instrumentation (P3-03):** `pti.etl.poll` là span gốc có link tới span producer; không làm span `pti.etl.dedup` riêng; metric có label phụ thuộc dữ liệu chỉ xuất hiện sau sự kiện đầu tiên; catalog metric nằm trong test resources của từng module; key OTLP mới của Spring Boot 4.1 | DR-98 (mới), DOC-28 §5.2, §8, §9 |
| 2026-09-29 | Claude (Owner ủy quyền) | **Profile observability (P3-02):** Grafana 13 và Tempo 3 thay 12.x và 2.x theo nguyên tắc dùng bản mới nhất; Loki, Tempo, OTel Collector không có healthcheck vì image distroless, Prometheus scrape chúng thay thế; app của profile tùy chọn được Prometheus tìm qua DNS | DR-97 (mới), DOC-11 §2, DOC-39 §3.7, §4 |
| 2026-09-29 | Claude (Owner ủy quyền) | **Kịch bản simulator (P3-01):** bunching ghép xe theo trạm chung đầu tiên phía trước thay vì `dist`, và giữ follower ở đúng khoảng cách mục tiêu; kiểu gây hỏng chỉ chọn trong các loại áp dụng được cho entity type; hàng đợi gửi lại và hoàn vé còn chờ vẫn chạy tiếp sau khi lần chạy kết thúc; lỗi của hook kết thúc lần chạy ở tick kế tiếp | DR-96 (mới), DOC-25 §7 |
| 2026-09-29 | Owner | **Thực nghiệm hai bước:** P3 viết đủ runner và chạy một chuỗi smoke ≤ 30 phút (mỗi EXP-01…05 một lần chạy rút gọn) trên máy dev; đợt chạy đầy đủ trên máy thực nghiệm dời thành P3-10, làm sau M6 và trước P7. Chuỗi smoke chạy lại khi chốt M4 và M6 | DR-95 (mới), master plan P3, DOC-45 |
| 2026-09-30 | Owner | **Không dùng Redis:** SSE fan-out qua Kafka, cache và rate limit theo pod, khóa và idempotency trên PostgreSQL. Ghi rõ dấu hiệu cần xem lại và phương án không cần Redis cần thử trước | DR-103 (mới), ADR-0031 (mới) |
| 2026-09-30 | Owner | **Clean Architecture cho backend Java:** code mới từ P4 (`analytics`, `api`, `triage-worker`) chia tầng `domain`/`application`/`adapter`/`config`, domain và application là Java thuần, ArchUnit fail build. Code P1–P3 giữ nguyên, bị freeze bằng ArchUnit, refactor ở Phase R sau M6 và trước P3-10 | DR-104 (mới), ADR-0032 (mới), DOC-49 (mới), master plan §4, §5 |

---

## A. Dữ liệu nguồn và hợp đồng message

### DR-01 · Chọn feed GTFS static nào — **Chốt: Metro Transit (Minneapolis–St. Paul)**
- **Kết quả spike S-02** (2026-09-26): đã tải và đo 7 feed. Script đo nằm ở `sample-data/gtfs/profile_feed.py`.

  | Feed | Tuyến | Trạm | Xe đồng thời cao điểm¹ | `shape_dist_traveled` | `block_id` | Ghi chú |
  | --- | --- | --- | --- | --- | --- | --- |
  | **Metro Transit, Minneapolis** | 127 | 8.155 | **472 chuyến / 606 xe** | 100% | 100% | Tiếng Anh, public domain, có `vehicles.txt` (sức chứa) |
  | OC Transpo, Ottawa | 182 | 5.782 | 437 | 100% | ~100% | Tiếng Anh, Open Government Licence; `stop_times` 2,7 triệu dòng (nặng) |
  | Edmonton ETS | 242 | 6.580 | 700 | 0% | 100% | Không có `calendar.txt`; 2,8 triệu dòng |
  | Adelaide Metro | 701 | 9.029 | 715 | 100% | 100% | Quá nhiều tuyến (có 164 tuyến xe học sinh) |
  | Metlink, Wellington | 245 | 3.131 | 372 | 0% | 0% | Không có block, không có shape_dist |
  | Nysse, Tampere | 105 | 3.423 | 264 | 0% | 100% | Tên trạm tiếng Phần Lan |
  | Metro Transit, Madison | 29 | 1.662 | 92 | 100% | 100% | Quá nhỏ |
  | TriMet, Portland | — | — | — | — | — | Không kết nối được từ máy chạy spike |

  ¹ Số chuyến đang chạy vào ngày thường. Với Minneapolis, số xe đang phục vụ (tính theo `block_id`, gồm cả thời gian chờ đầu bến) là 606.
- **Quyết định:** dùng **Metro Transit (Minneapolis–St. Paul, MN, Mỹ)**, bản chụp ngày 2026-09-26, lưu tại `sample-data/gtfs/metrotransit-mn-20260926.zip` (có SHA-256 trong `SHA256SUMS` và thông số trong `README.md` cùng thư mục).
- **Lý do:**
  1. Tải cao điểm khoảng 470–600 xe, đúng với "tải nền 500 xe" của EXP-07 mà không phải nhân bản chuyến.
  2. `shape_dist_traveled` đủ 100%, nên thuật toán bunching tính tiến độ trên tuyến trực tiếp (DR-30).
  3. `block_id` đủ 100%, nên simulator gán `vehicle_id` theo block và một xe chạy nối các chuyến như ngoài thực tế.
  4. Có `vehicles.txt` với sức chứa, dùng để nạp `dim_vehicle.capacity` và ước lượng tải khách cho gợi ý điều phối (SDD 9.5).
  5. Tên tiếng Anh, khớp với UI tiếng Anh (DR-61). License là public domain (Minnesota Government Data Practices Act).
  6. Kích thước vừa phải: zip 19 MB, khoảng 873 nghìn dòng `stop_times`.
- **Hệ quả:**
  - Múi giờ `America/Chicago` có DST, và DST kết thúc ngày 2026-11-01, nên test DR-09 phải có ca chuyển giờ.
  - Tiền tệ đổi sang USD (DR-06). Ngày lễ theo lịch Mỹ và bang Minnesota (DR-11). Locale là `en-US` (DR-48).
  - Vùng bản đồ cho PMTiles (S-05) có bbox `-93.730, 44.707, -92.806, 45.330`.
  - Có 3 tuyến light rail (`route_type=0`). Simulator vẫn cho chạy; phát hiện bunching mặc định chỉ áp cho bus (cấu hình được).
  - Lịch trong feed hết hạn ngày 2026-11-13, nên ánh xạ ngày ở DR-08 là bắt buộc.
- **Ghi vào:** DOC-13, ADR-0009.

### DR-02 · Dùng những file GTFS nào
- **Vấn đề:** SDD gốc chỉ liệt kê routes, stops, trips, stop_times, calendar. Tuy vậy bunching cần biết xe đi cùng chiều (`trips.direction_id`) và vị trí trên tuyến (`shapes.txt`); bản đồ cần hình dạng tuyến; lịch chạy cần tính cả ngày ngoại lệ (`calendar_dates.txt`).
- **Quyết định:** Bắt buộc có `agency, routes, stops, trips, stop_times, calendar, calendar_dates, shapes`. Dùng thêm `feed_info` và `frequencies` nếu feed có. Các file còn lại bỏ qua.
- *Điều chỉnh 2026-09-26 (theo feed Metro Transit đã chốt ở DR-01):* feed **không có** `frequencies.txt`. Feed có `vehicles.txt` (1.233 xe buýt, có sức chứa), nên dùng thêm file này để nạp `dim_vehicle` và để simulator gán xe cho từng `block_id` (DOC-13 §4). `levels`, `pathways`, `linked_datasets` và các file còn lại vẫn bỏ qua.
- **Ghi vào:** DOC-13.

### DR-03 · Định dạng GTFS-realtime trên Kafka — ⚠ lệch SDD gốc (làm rõ)
- **Vấn đề:** GTFS-rt chuẩn dùng Protobuf `FeedMessage` (một snapshot chứa nhiều entity). SDD gốc lại nói validate bằng Bean Validation trên DTO và có `schema_version`, tức ngầm hiểu dữ liệu là JSON.
- **Quyết định:** Simulator publish **JSON, mỗi entity một message** (một VehiclePosition hoặc một TripUpdate) bọc trong envelope (DR-04). Tên trường bám GTFS-rt (snake_case). Có JSON Schema cho từng `schema_version`. Protobuf nằm ngoài phạm vi và được ghi lý do trong ADR: dễ debug, dễ bơm dữ liệu lỗi, và contract test đơn giản hơn.
- **Ghi vào:** ADR-0007, DOC-09.

### DR-04 · Cấu trúc envelope
- **Quyết định:**
  ```json
  {
    "schema_version": 1,
    "message_id": "0192f4a6-…",            // UUID v7, sinh tại producer
    "entity_type": "VEHICLE_POSITION",       // | TRIP_UPDATE
    "source": "gtfs-rt-simulator",
    "event_timestamp": "2026-09-26T01:15:30Z", // thời điểm của dữ liệu (event time)
    "produced_at": "2026-09-26T01:15:30.412Z",  // thời điểm publish
    "payload": { … }
  }
  ```
  - VehiclePosition payload: `vehicle_id, trip_id, route_id, direction_id, start_date, lat, lon, bearing, speed_mps, current_stop_sequence, stop_id, current_status`.
  - TripUpdate payload: `trip_id, route_id, direction_id, start_date, vehicle_id, stop_time_updates[{stop_sequence, stop_id, arrival:{time, delay}, departure:{time, delay}, schedule_relationship}]`.
  - **Hash dedup chỉ tính trên `schema_version + entity_type + event_timestamp + payload` đã chuẩn hóa** (canonical JSON). Không đưa `message_id` và `produced_at` vào hash, để một message bị gửi lại vẫn cho ra cùng hash.
  - Kafka headers: `traceparent` (W3C), `schema_version`.
- **Ghi vào:** DOC-09, JSON Schema trong `backend/common/src/main/resources/schemas/`.

### DR-05 · Danh sách topic, key, partition
- **Quyết định:**

  | Topic | Key | Partition | Retention | Ghi chú |
  | --- | --- | --- | --- | --- |
  | `gtfs.vehicle_positions` | `route_id` | 12 | 7 ngày | delete |
  | `gtfs.trip_updates` | `route_id` | 12 | 7 ngày | delete |
  | `ticketing.sales.cdc` | `transaction_id` (PK) | 6 | 7 ngày | Debezium route bằng RegexRouter |
  | `pti.events.ui` | entity id | 3 | 1 ngày | Sự kiện nội bộ cho SSE (DR-41) |
  | `connect-*` | — | mặc định | — | Topic nội bộ của Kafka Connect |

  Dev: RF = 1. k3d: RF = 3, `min.insync.replicas = 2`. Tạo topic bằng script khởi tạo (compose) và bằng `KafkaTopic` CR (k8s), không bật auto-create.
- **Ghi vào:** DOC-09, ADR-0008.

### DR-06 · Schema database nguồn ticketing
- **Vấn đề:** SDD gốc chưa định nghĩa schema này.
- **Quyết định:** Database `ticketing_source`, schema `public`:
  - `sale_point(sale_point_id TEXT PK, name, kind KIOSK|ONBOARD|APP, stop_id NULL, created_at)` — *bổ sung 2026-09-26:* thêm `route_id NULL` (bắt buộc với `ONBOARD`) và `updated_at`; `sale_point_id` theo mẫu `^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$`. Cả hai bảng đặt `REPLICA IDENTITY FULL` để event xóa vẫn mang `created_at` (cần cho `sale_date`). DDL đầy đủ ở DOC-13 §5.
  - `ticket_transaction(transaction_id UUID PK, sale_point_id FK, route_id NULL, stop_id NULL, ticket_type SINGLE|DAY|MONTH, txn_type SALE|REFUND, amount NUMERIC(10,2) CHECK ≥ 0, currency 'USD' (giá vé mô phỏng theo bảng giá của Metro Transit, ví dụ $2.00/$2.50, day pass $5.00), refund_of UUID NULL, customer_ref TEXT, status COMPLETED|VOIDED, created_at, updated_at)`
  - `customer_ref` là dữ liệu cá nhân mô phỏng. ETL bỏ trường này ngay khi đọc, không nạp vào warehouse và không gửi sang Jev (DR-60).
- **Ghi vào:** DOC-13, migration `backend/db/src/main/resources/db/migration/ticketing/`.

### DR-07 · Định dạng event CDC
- **Quyết định:** Debezium PostgreSQL connector, plugin `pgoutput`, publication chỉ gồm `ticket_transaction` (và `sale_point` nếu cần dimension), `snapshot.mode=initial`. Dùng SMT `ExtractNewRecordState` (unwrap) với `add.fields=op,lsn,source.ts_ms` và `delete.handling.mode=rewrite`, sau đó `RegexRouter` để đổi tên topic thành `ticketing.sales.cdc`. ETL chỉ upsert khi `lsn` mới hơn bản đang lưu. Gặp `op=d` thì đánh dấu `is_deleted=true`, không xóa vật lý.
- **Ghi vào:** DOC-09, `deploy/connect/connectors/debezium-ticketing.json`.

### DR-08 · Đồng hồ mô phỏng và ngày phục vụ
- **Vấn đề:** Lịch trong feed thật thường đã hết hạn. Simulator cần biết "hôm nay" ứng với ngày nào trong feed.
- **Quyết định:** Simulator chạy **thời gian thực** (1 giây mô phỏng = 1 giây thật) để NFR-03 có ý nghĩa. Ngày thật được ánh xạ sang một ngày trong khoảng hiệu lực của feed có cùng thứ trong tuần (cấu hình `pti.sim.service-date-mapping=auto|fixed:<date>`). Trường `start_date`/`service_date` phát ra là **ngày thật**, còn ánh xạ chỉ dùng để chọn chuyến. Ngoài ra có tham số `pti.sim.time-offset` để demo giờ cao điểm vào bất kỳ lúc nào.
  - *Sửa 2026-09-27:* offset chỉ áp cho simulator sẽ làm lệch dữ liệu với lịch trong warehouse (headway theo giờ, rule DQ so `event_timestamp` với giờ hiện tại). Thay bằng đồng hồ nghiệp vụ dùng chung `pti.clock.offset` (DR-67).
- **Ghi vào:** DOC-25.

### DR-09 · Múi giờ và giờ GTFS vượt 24:00
- **Quyết định:** Mọi cột thời điểm trong DB dùng `TIMESTAMPTZ`, lưu UTC. `service_date` là `DATE` theo múi giờ của agency (`agency_timezone`). Giờ GTFS dạng `25:10:00` được quy đổi bằng công thức **`(service_date 12:00 local) − 12h + offset`** đúng như đặc tả GTFS ("noon minus 12h"). *Sửa 2026-09-26:* bản trước ghi `service_date 00:00 local + offset`, công thức này sai vào ngày chuyển giờ: ngày 2026-11-01, "08:00:00" phải ra 14:00Z (08:00 CST), còn cách cũ cho 13:00Z. Bảng ví dụ kiểm thử ở DOC-13 §3. Phần tính toán này đặt trong `common` và **bắt buộc có unit test cho ngày chuyển giờ**, vì feed đã chọn dùng `America/Chicago` và DST kết thúc ngày 2026-11-01. `hour_of_day` và `day_of_week` trong analytics đều tính theo giờ địa phương của agency.
- **Ghi vào:** DOC-13, DOC-14.

---

## B. Mô hình dữ liệu

### DR-10 · Phiên bản hóa GTFS static (dimension)
- **Vấn đề:** SDD gốc có hai yêu cầu: "đổi phiên bản trong một transaction" và "watermark theo phiên bản feed". Tuy nhiên chưa nói dimension có giữ lịch sử hay không.
- **Quyết định:**
  - Bảng `gtfs_feed_version(feed_version_id BIGSERIAL, feed_hash CHAR(64) UNIQUE, source_uri, valid_from, valid_to, status STAGED|ACTIVE|RETIRED|REJECTED, loaded_at, validation_report JSONB)` cùng partial unique index bảo đảm chỉ có một bản `ACTIVE`.
  - Mọi bảng `dim_*` và bảng lịch (`gtfs_trip`, `gtfs_stop_time`, `gtfs_shape`, `route_headway`) đều có `feed_version_id`, PK là `(feed_version_id, <natural id>)`. Kèm view `*_current` lọc theo bản ACTIVE.
  - Job nạp feed vào với status `STAGED`, validate toàn bộ, sau đó trong **một transaction** đổi bản cũ sang `RETIRED` và bản mới sang `ACTIVE`. Chỉ giữ 3 phiên bản gần nhất.
  - Bảng fact giữ natural id (`route_id TEXT`…) như SDD gốc, không dùng surrogate key, cho đơn giản và khớp với các bảng insight.
- **Ghi vào:** ADR-0009, DOC-14, DOC-21.

### DR-11 · `dim_time` → `dim_date`
- **Quyết định:** Thay `dim_time` bằng `dim_date(date_key INT yyyymmdd, date, day_of_week, is_weekend, is_holiday, holiday_name, day_type WEEKDAY|SATURDAY|SUNDAY_HOLIDAY)`, sinh sẵn cho 2024–2030 trong migration. Danh sách ngày lễ lấy từ file cấu hình; mặc định là ngày lễ liên bang Mỹ theo lịch của Metro Transit (New Year, Memorial Day, July 4, Labor Day, Thanksgiving, Christmas…). Giờ trong ngày suy ra từ timestamp, không cần bảng riêng. **⚠ lệch SDD gốc** (chỉ đổi tên và grain).
- **Ghi vào:** DOC-14.

### DR-12 · Headway theo lịch
- **Vấn đề:** Một cột "headway" duy nhất trong `dim_route` là không đủ, vì headway thay đổi theo chiều, giờ và loại ngày.
- **Quyết định:** Thêm bảng `route_headway(feed_version_id, route_id, direction_id, day_type, hour_of_day, scheduled_headway_seconds, trip_count)`, tính khi nạp feed từ giờ xuất phát tại trạm đầu (lấy median của hiệu hai chuyến liên tiếp). `dim_route.typical_headway_seconds` chỉ dùng để hiển thị.
- *Điều chỉnh 2026-09-26 (đo trên feed):* **không dùng trạm đầu** mà dùng **trạm tham chiếu**: trạm được nhiều chuyến của `(route, direction)` phục vụ nhất trong một ngày đại diện. Lý do: tuyến có nhánh bắt đầu ở nhiều trạm khác nhau (tuyến 18 có 4 trạm đầu), nên hiệu giờ xuất phát tại "trạm đầu" của từng chuyến lẫn các nhánh với nhau và cho headway sai. Với trạm tham chiếu, kết quả khớp lịch công bố: Blue Line 12 phút, Green Line 12, A Line 10, tuyến 18 10, tuyến 5 60. Headway là NULL khi giờ đó có ít hơn 2 chuyến (380/8.177 dòng). SQL ở DOC-14 §6.
- **Ghi vào:** DOC-14, DOC-21.

### DR-13 · Ngữ nghĩa `fact_trip_update`: dự đoán hay thực tế
- **Vấn đề:** Một TripUpdate chứa cả giờ đã qua (thực tế) lẫn giờ dự đoán cho các trạm phía trước. Nếu ETA và OTP lấy trung bình trên cả dự đoán thì kết quả sẽ sai.
- **Quyết định:** Mỗi dòng ứng với `(trip_id, stop_sequence, service_date)`. Các cột gồm `route_id, direction_id, stop_id, vehicle_id, scheduled_arrival, arrival_time, delay_seconds, is_observed, event_timestamp, payload_hash, batch_id, ingested_at, updated_at`. Đặt `is_observed = arrival_time ≤ event_timestamp`. Khi upsert chỉ ghi đè nếu `excluded.event_timestamp > current.event_timestamp`, và không bao giờ chuyển từ `is_observed = true` về `false`. **ETA, OTP và disruption chỉ đọc các dòng có `is_observed = true`.**
- **Ghi vào:** DOC-14, DOC-20.

### DR-14 · Bảng vị trí hiện tại
- **Quyết định:** Thêm `vehicle_position_latest(vehicle_id PK, route_id, trip_id, direction_id, lat, lon, bearing, current_stop_sequence, event_timestamp, updated_at)`, upsert có guard theo `event_timestamp`. API `/vehicles/live` đọc bảng này, không quét bảng fact.
- **Ghi vào:** DOC-14.

### DR-15 · Partition và retention
- **Ước lượng:** ~~Tải nền 100 event/giây, tức khoảng 8,6 triệu dòng vehicle position mỗi ngày.~~ *Điều chỉnh 2026-09-26 theo số đo trên feed (DOC-10 §3):* cao điểm 121 VehiclePosition/giây; cả ngày khoảng **5,7 triệu** dòng vehicle position, vì số xe trung bình theo 24 giờ chỉ là 331.
- **Quyết định:**
  - `fact_vehicle_position` và `fact_trip_update` partition theo ngày của `service_date`. `fact_ticket_sales` partition theo tháng.
  - Retention mặc định: vehicle position 14 ngày, trip update 90 ngày, ticket 365 ngày (cấu hình được). Raw zone giữ lâu hơn.
  - Không dùng pg_partman. `PartitionMaintenanceJob` là job Spring Batch gồm một `Tasklet` step: tạo trước partition cho 7 ngày tới và drop partition quá hạn.
- **Ghi vào:** ADR-0011, DOC-18.

### DR-16 · Vai trò và vòng đời của `dedup_registry` — ⚠ làm rõ
- **Vấn đề 1:** Với 8,6 triệu message mỗi ngày, bảng hash sẽ phình rất nhanh.
- **Vấn đề 2 (quan trọng):** Khi replay từ raw zone sau khi đã sửa lỗi logic, message có hash trùng sẽ bị bỏ qua. Như vậy replay mất tác dụng.
- **Quyết định:** Tính đúng đắn (không trùng) **chỉ dựa vào upsert theo business key**. `dedup_registry` là lớp tối ưu, dùng để bỏ qua sớm message gửi lại y hệt và đếm metric `records_duplicate_total`.
  - PK `(source, payload_hash)`, có `first_seen_at` và `batch_id`. ~~TTL 24 giờ~~ **TTL 1 giờ** (cấu hình được, `pti.etl.dedup.ttl`), dọn bằng job xóa theo lô mỗi 5 phút.
  - *Điều chỉnh 2026-09-26 (DOC-10 §3):* với TTL 24 giờ, registry giữ khoảng 12 triệu hash (khoảng 1,7 GB gồm index), index không còn nằm trong `shared_buffers` và mỗi lần chèn phải đọc ngẫu nhiên từ đĩa. Các tình huống gửi lại mà registry cần bắt (producer retry, kịch bản `duplicates` gửi lại trong vòng 60 giây) đều nằm trong vài phút. TTL 1 giờ giữ khoảng 520 nghìn dòng (khoảng 70 MB). Tính đúng đắn không đổi vì nó dựa vào upsert.
  - **Mọi luồng replay (DLQ và raw zone) đều bỏ qua registry** (job parameter `replay=true` của Spring Batch, processor đọc qua `@StepScope`).
  - *Điều chỉnh 2026-09-30 (DR-100):* replay không còn bỏ DQ-07 mà kiểm theo lúc publish (`produced_at`), chỉ chiều tương lai.
- **Ghi vào:** ADR-0003, DOC-19, DOC-22.

### DR-17 · Bảng cảnh báo hợp nhất
- **Vấn đề:** Alert feed cần có lịch sử, và cần định tuyến theo đối tượng nhận (hành khách / vận hành / kỹ thuật) như SDD gốc mô tả ở mục 9.4. Tuy nhiên SDD gốc chưa có bảng nào cho việc này.
- **Quyết định:** `alert_event(id UUID, type DISRUPTION|BUNCHING|DLQ_SEVERE|FEED_STALE|TICKETING_ANOMALY|INFRA, severity 0..2, audience PUBLIC|OPERATIONS|ENGINEERING, route_id NULL, ref_table, ref_id, title, body JSONB, created_at, acknowledged_by, acknowledged_at, dedup_key UNIQUE)`. *Bổ sung 2026-09-26:* thêm `resolved_at` (Alertmanager gửi `resolved`, episode đóng), để feed lọc được cảnh báo đang mở. Alertmanager gửi webhook về API, API ghi vào cùng bảng này (DR-51).
- **Ghi vào:** ADR-0023, DOC-15.

### DR-18 · Bảng phục vụ DLQ và replay
- **Quyết định:**
  - `dead_letter`: `id, source, stage (DESERIALIZE|SCHEMA|BUSINESS|DEDUP|LOAD|QUALITY), error_class, error_message, raw_payload TEXT, edited_payload JSONB, kafka_topic, kafka_partition, kafka_offset, business_key, batch_id, created_at, status, category, category_confidence, severity, severity_confidence, model_version, triaged_at, triage_attempts, auto_replay_count, last_replay_at, resolved_by, resolved_at`.
  - Máy trạng thái `status`: `NEW → TRIAGING → TRIAGED → {AUTO_REPLAY_SCHEDULED | PENDING_CONFIRM | MANUAL} → REPLAY_REQUESTED → {REPLAYED | NEW (lỗi lại)}`; ngoài ra có `DISCARDED` và `RESOLVED`.
  - `dlq_action_log(id, dead_letter_id, action, actor (user hoặc 'auto'), confidence, details JSONB, at)` là nhật ký auto-replay và thao tác tay.
  - `replay_request(id, kind RAW_RANGE|DLQ_RECORD, source, from_ts, to_ts, dead_letter_id, requested_by, requested_at, status PENDING|RUNNING|DONE|FAILED, job_execution_id (trỏ tới `BATCH_JOB_EXECUTION`), stats JSONB)`.
  - *Bổ sung 2026-09-26 (khi viết DDL ở DOC-15):* `dead_letter` thêm `rule_id` (rule DQ hoặc validation gây lỗi), `kafka_timestamp`, `triage_lease_until` (lease khi triage-worker đang xử lý, để pod chết thì dòng được nhận lại), `replay_count` (tổng số lần replay, khác `auto_replay_count` giới hạn 0..2) và `updated_at`. `replay_request` thêm `recompute_analytics`, `idempotency_key` (UNIQUE cùng `requested_by`), `started_at`, `finished_at`, `message`. Chỉ một `RAW_RANGE` đang `PENDING`/`RUNNING` cho mỗi nguồn (partial unique index; API trả 409, FR-12.3), cửa sổ tối đa 7 ngày.
- **Ghi vào:** DOC-15, DOC-22.

### DR-19 · Cờ vận hành lúc chạy
- **Vấn đề:** SDD gốc có nhắc "tạm dừng consumer bằng feature flag" nhưng chưa nói cơ chế.
- **Quyết định:** Bảng `runtime_flag(key PK, value JSONB, updated_by, updated_at)`, ví dụ `etl.consumer.gtfs-rt.paused` hoặc `triage.dlq.enabled`. Service đọc bảng này mỗi 5 giây. Ngưỡng thuật toán **vẫn nằm trong file cấu hình** (đổi thì restart, không cần sửa code), còn cờ bật/tắt tức thời thì nằm trong bảng.
- **Ghi vào:** DOC-15, DOC-29.

### DR-20 · Quyền ghi của API — ⚠ mâu thuẫn trong SDD gốc
- **Vấn đề:** SDD gốc nói API chỉ dùng `api_reader` (chỉ SELECT). Nhưng `POST /dispatch-suggestions/{id}/feedback`, confirm DLQ và ack alert đều phải ghi.
- **Quyết định:** API có hai datasource. `api_reader` đọc từ replica. `replay_operator` ghi vào primary, với quyền hẹp tới mức cột: `UPDATE(status, edited_payload, resolved_by, resolved_at)` trên `dead_letter`; `INSERT` trên `replay_request`, `job_request` (yêu cầu restart hoặc chạy tay job batch, DR-62) và `dlq_action_log`; `UPDATE(operator_feedback, feedback_by, feedback_at)` trên `insight_dispatch_suggestion`; `UPDATE(acknowledged_*)` trên `alert_event`; `INSERT/UPDATE` trên `runtime_flag`. Với trường hợp cần đọc ngay dữ liệu vừa ghi, response trả lại bản ghi đọc từ primary.
- **Ghi vào:** DOC-17.

---

## C. ETL (Spring Batch và Spring Kafka)

### DR-21 · Chiến lược ghi chunk: batch trước, fallback scan
- **Vấn đề:** SDD gốc muốn cùng lúc "JdbcTemplate batch upsert" (một round-trip) và "savepoint theo từng record". Hai cách này không dùng được đồng thời.
- **Quyết định:** Ghi batch trước, lỗi dữ liệu khi ghi thì chuyển sang scan. Cụ thể theo từng chế độ:
  - **Job batch (Spring Batch):** dùng nguyên fault-tolerant chunk step, không tự viết thuật toán.
    1. Process từng item; exception mà `SkipPolicy` cho skip thì item bị loại khỏi chunk.
    2. `JdbcBatchItemWriter` ghi cả chunk trong một transaction. `SkipListener` ghi DLQ và Spring Batch ghi `ExecutionContext`/`StepExecution` trong cùng transaction đó.
    3. Nếu writer ném exception có thể skip: Spring Batch rollback rồi tự **scan** (ghi lại từng item, mỗi item một transaction), item lỗi đi vào `onSkipInWrite` → DLQ.
    4. Cần `processorNonTransactional()` hoặc processor không có side effect, vì scan chạy lại processor; processor của dự án là hàm thuần nên đáp ứng.
  - **Streaming (`StreamChunkTemplate`):** cùng thuật toán, hiện thực bằng các thành phần Spring:
    1. Process từng record; lỗi dữ liệu vào danh sách skip.
    2. `TransactionTemplate`: gọi cùng bean `ItemWriter` với `Chunk` các item hợp lệ, `DeadLetterWriter` cho các item skip, ghi `etl_stream_batch`, commit.
    3. Nếu writer ném **lỗi dữ liệu** (SQLState class 22/23): rollback, mở transaction mới; với mỗi item thì `status.createSavepoint()`, ghi đơn lẻ, lỗi thì `status.rollbackToSavepoint(sp)` và đưa item vào DLQ; cuối cùng commit. Dùng savepoint thay vì một transaction cho mỗi item để micro-batch vẫn chỉ tốn một lần commit.
  - Nếu bước ghi ném **lỗi hạ tầng**: xem DR-23.
- **Ghi vào:** ADR-0005, DOC-19, DOC-20.

### DR-22 · Ánh xạ poll sang chunk, và ack
- **Quyết định:** Dùng batch listener của Spring Kafka (`@KafkaListener(batch = "true")`, nhận `List<ConsumerRecord>`), `max.poll.records=500`, `fetch.max.wait.ms=1000` và `fetch.min.bytes` đủ lớn để gom micro-batch khoảng 1 giây. Mỗi poll là một chunk và một dòng `etl_stream_batch` (có `batch_id` riêng). **Không tạo JobExecution Spring Batch cho mỗi poll** (ADR-0002).
  - Ack: `AckMode.BATCH` (mặc định). Listener gọi `StreamChunkTemplate` đồng bộ, transaction đã commit khi listener trả về, nên offset luôn được commit sau transaction mà không cần tự gọi `ack()`.
  - Lỗi hạ tầng: listener ném exception; `DefaultErrorHandler` seek về offset đầu batch của từng partition và thử lại với `ExponentialBackOff` (không giới hạn số lần, trần 30 giây); `ContainerPausingBackOffHandler` pause container trong lúc chờ để consumer không bị văng khỏi group. Lỗi dữ liệu không bao giờ tới error handler vì đã được xử lý trong listener.
  - Kích hoạt analytics bằng sự kiện `MicroBatchCommitted` phát **sau khi** transaction commit. *Làm rõ 2026-09-27 (DOC-19 §6.2):* `StreamChunkTemplate` publish sự kiện sau khi `TransactionTemplate.execute` trả về, lúc đó không còn transaction nên listener là `@EventListener` + `@Async` (executor riêng), không phải `@TransactionalEventListener` (listener này bị bỏ qua khi không có transaction, trừ khi đặt `fallbackExecution`). Ngữ nghĩa "chỉ sau commit" giữ nguyên.
  - Listener concurrency mặc định là 3 (cấu hình được), mỗi thread chạy một chuỗi chunk tuần tự.
- **Hệ quả:** `etl_stream_batch` sinh khoảng 1 dòng/giây cho mỗi consumer thread, nên giữ 7 ngày. Ops console hiển thị dữ liệu gộp theo phút.
- **Ghi vào:** ADR-0004, DOC-20.

### DR-23 · Phân loại lỗi
- **Quyết định:** `ErrorClassifier` (hiện thực `Classifier<Throwable, ErrorKind>` của Spring, dựa trên `DataAccessException` hierarchy và `SQLErrorCodeSQLExceptionTranslator`) trả về một trong ba loại, và cùng một bảng phân loại được nối vào cả hai chế độ:

  | Loại | Ví dụ | Job batch (Spring Batch) | Streaming (Spring Kafka) |
  | --- | --- | --- | --- |
  | `DATA` | lỗi deserialize, vi phạm Bean Validation, vi phạm business rule, SQLState 22xxx/23xxx | `SkipPolicy` cho skip → `SkipListener` ghi DLQ | Skip trong `StreamChunkTemplate` → DLQ |
  | `TRANSIENT_INFRA` | SQLState 08xxx, 40001, 40P01, 57P01, 53300; timeout; Kafka retriable | Retry của fault-tolerant step (tối đa 5 lần, backoff có jitter, tổng ≤ 60 giây), hết lượt thì step FAILED để restart sau; **không vào DLQ** | Ném ra ngoài → `DefaultErrorHandler` seek và thử lại vô hạn có backoff, pause container; circuit breaker DB mở thì dừng poll; **không ack, không vào DLQ** |
  | `FATAL` | lỗi cấu hình hoặc lỗi lập trình (NPE trong writer…) | Không skip, không retry → step FAILED, alert khẩn | Dừng container (`CommonContainerStoppingErrorHandler` cho loại này), alert khẩn |

  Skip limit: `SkipPolicy` cho skip `DATA` tới khi tỷ lệ skip của step vượt `pti.etl.batch.max-skip-ratio` (mặc định 20%), vượt thì step FAILED, vì một feed sai hàng loạt nên dừng lại thay vì nạp một nửa.
- **Ghi vào:** ADR-0006, DOC-30.

### DR-24 · Chống chạy trùng job giữa các pod batch
- **Vấn đề:** Hai pod `etl-batch` cùng chạy scheduler. Advisory lock của PostgreSQL gắn với session nên hỏng khi đi qua PgBouncer ở chế độ transaction pooling. Spring Batch không có heartbeat, nên execution của pod chết sẽ kẹt ở STARTED.
- **Quyết định:** Ba lớp, đều dùng thứ có sẵn thay cho lease table tự viết:
  1. **Kích hoạt:** mỗi `@Scheduled` có `@SchedulerLock` của ShedLock (`JdbcTemplateLockProvider`, bảng `shedlock`, dùng UPDATE thường nên chạy được qua PgBouncer). `lockAtMostFor` lớn hơn thời gian chạy dài nhất của job; job dài thì dùng `KeepAliveLockProvider` để gia hạn.
  2. **Một JobInstance chỉ một execution:** Spring Batch ném `JobExecutionAlreadyRunningException` khi đã có execution đang chạy cho cùng job parameters. Job parameters định danh được thiết kế sao cho mỗi lần chạy hợp lệ là một instance (ví dụ `serviceDate`, `runKey`, `replayRequestId`). *Làm rõ 2026-09-27 (DOC-21 §1):* `GtfsStaticLoadJob` dùng `runKey` thay cho `feedHash`, vì hash chỉ biết sau khi đọc file; tính duy nhất theo feed do `gtfs_feed_version_hash_uk` bảo đảm.
  3. **Fencing:** Spring Batch cập nhật `BATCH_STEP_EXECUTION` với kiểm tra `VERSION` trong chính transaction của chunk. Bước khôi phục đánh dấu execution kẹt là FAILED (tăng `VERSION`), nên nếu pod cũ còn sống thì lần commit chunk kế tiếp của nó gặp `OptimisticLockingFailureException` và bị rollback. `VERSION` đóng vai fencing token.
  - **Khôi phục execution kẹt:** tác vụ `StaleExecutionRecoverer` (chạy lúc khởi động và mỗi phút, dưới ShedLock) tìm execution STARTED có `LAST_UPDATED` cũ hơn `pti.batch.stale-after` (mặc định 2 phút, lớn hơn thời gian một chunk dài nhất) và không còn khóa ShedLock của job đó; đánh dấu step và job execution FAILED qua `JobRepository` (hoặc API khôi phục có sẵn nếu Spring Batch 6 cung cấp, xác minh ở S-06), rồi `JobOperator.restart`.
  - JDBC đặt `prepareThreshold=0` hoặc dùng PgBouncer ≥ 1.21 với `max_prepared_statements`.
- **Ghi vào:** ADR-0015, DOC-19.

### DR-25 · Data quality: trước hay sau khi ghi — ⚠ làm rõ
- **Quyết định:** Chia hai lớp:
  - **Pre-write** (trong processor, theo từng record): null ở trường bắt buộc, trùng business key trong cùng chunk, tham chiếu route/stop so với cache dimension. Vi phạm thì vào DLQ với `stage=QUALITY`. Lớp này là "vi phạm được đưa vào DLQ" mà SDD gốc nhắc tới.
  - **Post-write** (SQL assertion theo `batch_id` sau mỗi chunk batch, và toàn bảng sau mỗi job): kết quả ghi vào `dq_check_result(id, rule_id, scope, batch_id, violation_count, sample JSONB, checked_at)`, phát metric và alert. **Lớp này không tự xóa dữ liệu.**
- **Ghi vào:** DOC-16.

### DR-26 · Đơn vị triển khai
- **Quyết định:**
  - Các Gradle module, đặt trong `backend/` (DR-85): `build-logic, common, analytics (thư viện), etl (app), triage-worker (app), api (app), source-simulator (app), db (migration + Flyway runner)`. Thêm `frontend/` (pnpm) và `experiments/` (Python). **Không còn module `engine`** (ADR-0002).
  - `etl` dùng một image với hai profile: `stream` (Spring Kafka consumer + analytics micro-batch; không bật scheduler) và `batch` (Spring Batch, `@Scheduled` + ShedLock, các job batch, replay). Cả hai profile đặt `spring.batch.job.enabled=false` để không job nào tự chạy lúc khởi động. Cách này khớp với bảng workload K8s trong SDD gốc.
  - Flyway chạy như một container/job riêng (`db-migrate`), các app không tự migrate.
- **Ghi vào:** ADR-0014, ADR-0024, DOC-07.

### DR-27 · Chế độ baseline cho thực nghiệm
- **Vấn đề:** SDD gốc nói mỗi EXP "so sánh với baseline không có cơ chế tương ứng" nhưng chưa nói baseline được dựng ra sao.
- **Quyết định:** Thêm profile `experiment` với các cờ (chỉ bật được trong profile này):
  - `pti.etl.baseline.offset-commit=auto`: auto-commit trước khi ghi DB.
  - `write-mode=insert` vào bảng bóng `exp_fact_*` **không có ràng buộc UNIQUE**, để đếm được số dòng trùng.
  - `error-mode=fail-batch`: một lỗi làm fail cả chunk.
  - `dedup=off`.
- **Ghi vào:** DOC-45, DOC-19.
- **Ghi chú:** `error-mode=fail-batch` ở job batch được dựng bằng cách không bật `faultTolerant()` (step thường của Spring Batch fail ngay ở lỗi đầu tiên); ở streaming bằng cờ trong `StreamChunkTemplate`.

### DR-28 · Ground truth để đo mất và trùng
- **Quyết định:** Simulator ghi **ledger** vào schema `sim` riêng: `sim_ledger(message_id, entity_type, business_key, event_timestamp, intended_invalid BOOL, is_resend BOOL, produced_at)`. *Bổ sung 2026-09-26:* ledger chỉ ghi message đã được Kafka xác nhận (ghi trong callback của producer, kèm partition và offset). Một TripUpdate sinh nhiều business key nên cột là `business_keys TEXT[]`. Partition theo ngày của `produced_at`, retention 2 ngày (khoảng 2,5 GB/ngày). DDL ở DOC-13 §6. Tập kỳ vọng tại warehouse là các business key trong ledger có `intended_invalid = false`. Số liệu tính như sau: mất = kỳ vọng − thực có; trùng = `count(*) − count(distinct business_key)` (chỉ khác 0 ở bảng bóng của baseline); sai giá trị = so sánh trường theo key. Với ticketing, ground truth chính là DB nguồn.
- **Ghi vào:** DOC-25, DOC-45.

### DR-62 · Cấu hình metadata và hạ tầng Spring Batch
- **Vấn đề:** Dùng Spring Batch thì phải chốt nơi đặt bảng metadata, ai tạo schema, cách lưu `ExecutionContext` và vòng đời của metadata.
- **Quyết định:**
  - Bảng `BATCH_*` nằm trong schema `batch` của warehouse, `spring.batch.jdbc.table-prefix=batch.BATCH_`. DDL lấy từ `schema-postgresql.sql` trong jar `spring-batch-core` đúng phiên bản đã chốt, đưa vào migration Flyway (V5). `spring.batch.jdbc.initialize-schema=never`. Nâng phiên bản Spring Batch thì kiểm tra script migration của Spring Batch và viết migration Flyway tương ứng.
  - JobRepository phải là **JDBC**, không phải bản resourceless/in-memory (ở Spring Boot 4 / Spring Batch 6 cần đúng starter hoặc annotation cho JDBC; xác minh ở S-06).
  - `ExecutionContext` serialize bằng serializer JSON (Jackson) thay cho mặc định, để đọc được khi điều tra và không phụ thuộc Java serialization. Chỉ lưu kiểu đơn giản (số, chuỗi, map).
  - `JobOperator` là API duy nhất để start, restart, stop job; `JobExplorer`/`JobRepository` để đọc trạng thái. API không gọi Spring Batch trực tiếp: nó đọc view `ops_job_run_v` và ghi yêu cầu (restart, replay) vào bảng, pod `etl-batch` thực thi (ADR-0013).
  - `BatchMetadataCleanupJob` (Tasklet, hằng ngày) xóa execution cũ hơn 30 ngày theo đúng thứ tự khóa ngoại (step context → step execution → job context → job params → job execution → job instance). Execution FAILED chưa được restart thì giữ lại.
  - Cô lập quyền: `etl_writer` có quyền trên schema `batch`; `api_reader` chỉ SELECT qua view.
- **Ghi vào:** ADR-0002, DOC-15, DOC-17, DOC-18, DOC-19.

### DR-63 · Ý nghĩa của `batch_id` ở hai chế độ
- **Vấn đề:** Trước ADR-0002, `batch_id` trỏ tới một dòng `etl_job_run` của engine tự xây. Sau khi đổi sang Spring Batch, khóa của metadata là `BIGINT` (`STEP_EXECUTION_ID`), còn streaming dùng `etl_stream_batch`. Cần một định danh chung để fact, DLQ, log và DQ post-write cùng trỏ về được.
- **Quyết định:** `batch_id` là UUID định danh **một đơn vị ghi**:
  - **Streaming:** một micro-batch, tức một dòng `etl_stream_batch(batch_id PK)`. Sinh bằng UUIDv7 trước khi mở transaction.
  - **Job batch:** một **step execution**. `BatchIdStepListener.beforeStep` sinh UUIDv7, ghi một dòng `etl_batch_step(batch_id PK, job_execution_id, step_execution_id UNIQUE, job_name, step_name, created_at)` và đặt giá trị vào step `ExecutionContext` với key `pti.batchId`; processor/writer đọc qua `@StepScope`. Restart tạo step execution mới nên có `batch_id` mới; các dòng đã commit trước đó giữ `batch_id` cũ.
  - Tasklet job (ETA, OTP, bảo trì) cũng dùng cùng listener.
  - Truy vết replay: `replay_request.job_execution_id` → `etl_batch_step` → `batch_id` trong fact (FR-12.5).
  - DQ-20 kiểm tra `batch_id` thuộc `etl_stream_batch ∪ etl_batch_step`, **chỉ trong cửa sổ retention** của hai bảng này (7 ngày và 30 ngày, DOC-18). Fact cũ hơn giữ `batch_id` nhưng không còn tham chiếu được.
  - `ops_job_run_v` hợp nhất `BATCH_JOB_EXECUTION`/`BATCH_STEP_EXECUTION` (qua `etl_batch_step`) và `etl_stream_batch` cho Ops console.
- **Ghi vào:** DOC-15, DOC-19, DOC-16, DOC-28.

### DR-64 · Bố cục instance, database và schema PostgreSQL
- **Vấn đề:** SDD gốc chỉ nêu tên `ticketing_source` và schema `batch`/`sim`. Chưa chốt các bảng còn lại nằm ở schema nào, và ledger nằm ở đâu. Ledger phải còn nguyên khi EXP-04 và UC-18 xóa rồi dựng lại warehouse.
- **Quyết định:**

  | Instance | Database | Schema | Nội dung | Owner (migration) |
  | --- | --- | --- | --- | --- |
  | `pg-warehouse` (CNPG trên k3d: 1 primary + 1 replica) | `pti_warehouse` | `dw` | `gtfs_feed_version`, `dim_*`, `gtfs_*`, `route_headway`, `fact_*`, `vehicle_position_latest` | `pti_owner` |
  | | | `ops` | `etl_stream_batch`, `etl_batch_step`, `etl_checkpoint`, `shedlock`, `dead_letter`, `dlq_action_log`, `replay_request`, `job_request`, `dedup_registry`, `runtime_flag`, `dq_check_result`, `alert_event`, view `ops_job_run_v` | `pti_owner` |
  | | | `insight` | `insight_*`, `analytics_*` | `pti_owner` |
  | | | `batch` | `BATCH_*` của Spring Batch (DR-62) | `pti_owner` |
  | | | `exp` | Bảng bóng `exp_fact_*` không có UNIQUE (DR-27) | `pti_owner` |
  | `pg-source` (đơn lẻ, không failover, DR-55) | `ticketing_source` | `public` | `sale_point`, `ticket_transaction`, `debezium_heartbeat` | `ticketing_owner` |
  | | `pti_sim` | `sim` | `sim_ledger`, `sim_scenario_run` | `sim_owner` |

  - Tách hai instance để tải ghi của warehouse và WAL của replication slot không ảnh hưởng nhau, và để failover warehouse (EXP-08) không chạm tới slot của Debezium.
  - Ledger nằm trong `pti_sim` trên `pg-source`, nên xóa `pti_warehouse` không làm mất ground truth. Runner thực nghiệm đọc cả hai database và so sánh bằng Python (nạp business key vào bảng tạm của warehouse khi số lượng lớn).
  - Publication `pti_ticketing` của Debezium chỉ gồm `public.sale_point`, `public.ticket_transaction`, `public.debezium_heartbeat`. Publication do migration `ticketing` tạo (connector đặt `publication.autocreate.mode=disabled`), nên user `debezium` chỉ cần `REPLICATION` và `SELECT`, không cần quyền owner. Connector đặt `heartbeat.interval.ms=10000` và `heartbeat.action.query` cập nhật `debezium_heartbeat`, để slot vẫn tiến lên khi ticketing ít giao dịch trong khi `pti_sim` ghi nhiều (WAL là của cả instance).
  - SQL trong code luôn ghi rõ schema (`dw.fact_trip_update`), không dựa vào `search_path`.
  - Keycloak chạy `start-dev` với database nhúng (dev-file), realm import từ JSON lúc khởi động, nên không cần database riêng.
- **Ghi vào:** DOC-07, DOC-13, DOC-14, DOC-15, DOC-17.

### DR-65 · Phạm vi trạm trong một TripUpdate và chi phí upsert
- **Vấn đề:** Đo trên feed (DOC-10 §3): nếu mỗi TripUpdate chứa mọi trạm phía trước (trung bình khoảng 22 trạm) thì `fact_trip_update` nhận khoảng 24 triệu lần upsert mỗi ngày cho chỉ 345 nghìn dòng, khoảng 500 lần/giây lúc cao điểm và 5.000 lần/giây ở EXP-07. Phần lớn là cập nhật giá trị *dự đoán*, trong khi ETA, OTP và disruption chỉ đọc giá trị *quan sát* (DR-13).
- **Quyết định:**
  - Simulator đưa vào mỗi TripUpdate: các trạm đã đi qua kể từ TripUpdate trước, cộng **tối đa `pti.sim.trip-update.lookahead-stops` trạm phía trước (mặc định 10)**. Nhiều feed thật cũng giới hạn tầm dự đoán như vậy. Upsert giảm còn khoảng 12 dòng mỗi TripUpdate, tức khoảng 13 triệu dòng mỗi ngày và khoảng 270 dòng/giây lúc cao điểm.
  - `fact_trip_update` đặt `fillfactor = 80` và **chỉ đánh index trên các cột không đổi** (business key, `route_id`, `stop_id`). Như vậy cập nhật dự đoán là HOT update, không sinh thêm bản ghi index và ít bloat. `is_observed` và `arrival_time` không nằm trong index nào; truy vấn analytics lọc chúng sau khi quét theo partition và `route_id`.
  - F-ANL-06 (`realtimeArrival`) chỉ có giá trị cho 10 trạm tới của mỗi chuyến (khoảng 15–20 phút); xa hơn thì dùng ETA lịch sử.
- **Ghi vào:** DOC-09, DOC-10, DOC-14, DOC-25.


### DR-70 · Trục thời gian của replay raw zone và DLQ idempotent
- **Vấn đề:** (1) `replay_request.from_ts/to_ts` chưa nói là event time hay giờ record Kafka; hai trục lệch nhau bởi đồng hồ nghiệp vụ (DR-67), và offset có thể đã đổi từ lúc dữ liệu được sinh. (2) Poll được giao lại sau khi DB đã commit nhưng offset chưa commit (ADR-0004) làm record lỗi có hai dòng `dead_letter`. (3) Replay sau khi sửa logic không đóng các dead letter mà nó đã giải quyết.
- **Quyết định:**
  1. `from_ts/to_ts` của `RAW_RANGE` là **giờ record Kafka (CreateTime, giờ thật)**, cùng trục với phân vùng raw zone. `to_ts ≤ now − 10 phút` (chờ S3 sink rotate), `from_ts ≥ now − 29 ngày` (trước lifecycle 30 ngày). UI hiển thị thêm khoảng event time ước tính, chỉ để tham khảo.
  2. Unique index một phần `dead_letter (kafka_topic, kafka_partition, kafka_offset)`. Luồng trực tiếp `ON CONFLICT DO NOTHING`; replay `ON CONFLICT DO UPDATE` (lỗi mới nhất, `replay_count + 1`, về `NEW` trừ khi đã `DISCARDED`/`RESOLVED`).
  3. Raw zone replay ghi thành công một record có dead letter chưa đóng thì chuyển dead letter đó sang `RESOLVED` (`resolved_by = 'system:etl-batch'`).
  4. `GtfsStaticLoadJob` định danh bằng `runKey` thay vì `feedHash` (DOC-21 §1).
- **Ghi vào:** DOC-15, DOC-19, DOC-21, DOC-22, DOC-32, DOC-36.

### DR-90 · Nhận `job_request`/`replay_request` và khôi phục execution kẹt khi triển khai — **Chốt** (P2)
- **Vấn đề:** DOC-19 §7.3 và DOC-22 §6 muốn gọi `JobOperator` trong cùng transaction với câu `SELECT … FOR UPDATE SKIP LOCKED`. Spring Batch 6 (`validateTransactionState`) từ chối tạo execution bên trong transaction của người gọi, và nếu tắt kiểm tra đó thì thread của job cập nhật một execution chưa commit. DOC-19 §7.2 cũng muốn `StaleExecutionRecoverer` bỏ qua execution khi khóa ShedLock của job còn giữ, nhưng lịch chỉ khởi chạy job bất đồng bộ rồi trả khóa ngay, nên khóa không phản ánh việc job còn chạy.
- **Quyết định:**
  - Claim là một transaction riêng (`PENDING → RUNNING`, `started_at`) đã commit; sau đó mới gọi `JobOperator`, rồi ghi `job_execution_id`. Listener của job tìm yêu cầu theo `job_execution_id` hoặc tham số không định danh `jobRequestId`/`replayRequestId`, nên job kết thúc trước khi poller kịp ghi `job_execution_id` vẫn được ghi nhận.
  - Yêu cầu `RUNNING` mà chưa có `job_execution_id` sau 2 phút (pod chết giữa claim và start) bị đặt `FAILED` với lời nhắn gửi lại.
  - `StaleExecutionRecoverer` chỉ dựa vào `LAST_UPDATED` (2 phút) và fencing `VERSION`; không đọc `ops.shedlock`, không dùng `KeepAliveLockProvider`. Hai execution cùng instance tạo đồng thời (READ_COMMITTED) nhận `DuplicateKeyException`, được coi như `JobExecutionAlreadyRunningException`.
- **Ghi vào:** DOC-19 §7.3, DOC-22 §6.

### DR-91 · Replay raw zone không lưu danh sách object — **Chốt** (đóng mục mở của DR-89)
- **Vấn đề:** Sau DR-89 một tuần VehiclePosition có khoảng 730.000 object; lưu danh sách trong `ExecutionContext` (DOC-22 §4.3 bản cũ) vừa quá lớn vừa buộc `pti.replay.max-objects` thấp.
- **Quyết định:** `listObjects` chỉ đếm và kiểm giới hạn; `RawZoneReader` liệt kê lại từng thư mục giờ khi tới giờ đó (sau `raw-settle` danh sách không đổi). Vị trí restart là `(giờ, object trong giờ, số dòng đã đọc)` cùng offset lớn nhất của cặp `(giờ, partition)` đang đọc, lưu trong step context sau mỗi chunk. `pti.replay.max-objects` nâng lên 1.000.000.
- **Đã kiểm:** IT R-08 và kiểm tra M2 trên compose: `docker kill etl-batch` sau 1.500 dòng của một giờ 27.301 dòng, recoverer đánh dấu `STALE`, restart qua `job_request` đọc tiếp 25.801 dòng; 27.301 dòng fact, không trùng, không mất.
- **Ghi vào:** DOC-22 §4.3, §4.4, §8, DOC-19 §3.2, DOC-29.

### DR-92 · Hoãn DQ-27 sang P3 — **Chốt: bỏ DQ-27** (P2, đóng ở DR-100)
- **Vấn đề:** DQ-27 so `write_count` của step với số dòng mang `batch_id` của step, trừ dòng bị guard chặn. Writer chỉ đếm theo message; một TripUpdate ghi nhiều dòng và replay ghi lại cùng dòng nhiều lần (`:replay`), nên phép so sánh báo vi phạm giả với mọi replay TripUpdate. Muốn đúng thì `FactChunkWriter` phải trả số dòng khác nhau thực sự đổi.
- **Quyết định:** P2 làm DQ-20…26; DQ-27 làm ở P3 cùng EXP-04, khi checksum của replay kiểm được cùng tính chất một cách chặt hơn.
- **Ghi vào:** DOC-16 §3 (chú thích), master plan P2-15.

### DR-93 · Chi tiết nhỏ khác khi làm Phase 2 — **Chốt** (P2)
- Workspace của `GtfsStaticLoadJob` đặt theo **job instance** (`<work-dir>/<jobInstanceId>`) thay vì job execution, để restart trên cùng pod dùng lại file; pod khác thì `FeedWorkspaceListener` tải lại từ raw zone như DOC-21 §3.1.
- Luồng của `GtfsStaticLoadJob` rẽ nhánh theo exit code của step (`NOOP`, `REACTIVATE`, `REJECTED`) thay vì một `JobExecutionDecider` riêng; kết quả như DOC-21 §2.
- DQ-23 lưu `{population, rows}` vào `dq_check_result.sample` để ngưỡng tương đối vẫn tính được sau restart.
- Ví dụ contract nằm ở `backend/common/src/testFixtures/resources/contract-examples/` (thay `src/test/resources`) để `etl` đọc được qua test fixtures.
- TripUpdate: DQ-02 gộp theo từng trạm; `ErrorClassifier` nhận exception của thư viện theo tên lớp; `etl_writer` có thêm quyền `UPDATE` các cột DLQ mà replay và `DlqResolveWriter` cần, và `DELETE` trên `ops.alert_event` cho `OpsRetentionJob` (DOC-17).
- Sự kiện UI (`vehicles.batch`), analytics sau commit và span tracing của listener chưa làm ở P2; lần lượt thuộc P4 và P3-03.
- `RatioSkipPolicy` tính tỷ lệ trên step execution hiện tại, không lưu `pti.skip.data`.
- Container của nhóm baseline có id hậu tố `-baseline` (DOC-20 §9).
- **Ghi vào:** DOC-17, DOC-21, DOC-44.

### DR-104 · Clean Architecture cho backend Java, refactor code cũ ở Phase R — **Chốt** (P4; sửa DR-95)
- **Vấn đề:** Code P1–P3 chia package theo feature nhưng trộn logic nghiệp vụ với cơ chế Spring (listener, writer, `JdbcClient`, Micrometer, `@Transactional`). P4–P6 thêm `analytics`, `api`, `triage-worker`, nơi có nhiều logic nghiệp vụ nhất. Owner muốn cả dự án theo Clean Architecture, nhưng không đổi kiến trúc phần đã xong và đã được đo ở M3.
- **Các phương án:** (1) giữ nguyên; (2) refactor toàn bộ trước P4; (3) **áp cho code mới từ P4, freeze code cũ bằng ArchUnit, refactor code cũ ở Phase R sau M6 và trước P3-10**; (4) chỉ ban hành hướng dẫn, không có công cụ kiểm tra.
- **Quyết định:** Chọn (3) (ADR-0032, quy tắc ở DOC-49).
  - Phạm vi: chỉ backend Java. Frontend giữ cấu trúc của DOC-34/35; `experiments/` ngoài phạm vi.
  - Trong mỗi feature chia bốn tầng `domain`, `application` (use case và `application.port`), `adapter.in`/`adapter.out`, `config`. `domain` và `application` là Java thuần, không import Spring hay thư viện hạ tầng; transaction qua port `TransactionRunner`; use case tạo bằng `@Bean` trong `config`.
  - Luật ArchUnit A-11…A-18 fail build cho `analytics`, `api`, `triage-worker` (P4-18). `etl`, `source-simulator`, `common`, `db` chạy qua `FreezingArchRule`, store commit vào repo và chỉ được giảm. Package mới thêm vào module cũ (ví dụ `dev.pti.etl.analytics`) tuân thủ ngay.
  - Phase R (RF-00…RF-08) đứng sau M6, trước P3-10, để thực nghiệm đầy đủ đo trên code cuối cùng. Phase R không đổi hành vi bên ngoài. Tiêu chí thoát MR: store rỗng, test fault-injection xanh, `pti-exp smoke` đạt như M3.
  - Ngoại lệ là danh sách đóng (DOC-49 §10): cây JSON Jackson trong `triage..application` (X-01, ADR-0018), transaction chunk của Spring Batch (X-02).
- **Hệ quả:** P4 thêm P4-18 (khoảng 2–3 ngày); Phase R khoảng 2–3 tuần, tổng lộ trình khoảng 24–33 tuần. Tài liệu thiết kế P4–P6 viết trước quyết định này được bổ sung bảng ánh xạ tầng; package nối analytics trong `etl` đổi từ `dev.pti.etl.stream.analytics` thành `dev.pti.etl.analytics`. Phase R nằm trong thứ tự cắt giảm (§4.3): nếu bị cắt, code cũ giữ freeze, luật cho code mới vẫn giữ.
- **Ghi vào:** ADR-0032, DOC-49, DOC-44 §3.3 và §13, DOC-23 §1.1 và §4.1, DOC-24 §4.1, DOC-26 §3, DOC-31 §10.1, DOC-07, DR-95 (điểm 4: P3-10 làm sau MR), DOC-45 (`experiments/README.md`), master plan §0, §2, §3.1, §3.2, §4, §5 (P4, P6, Phase R, P3-10), §6, §7.2, §7.3, §8, `CONTRIBUTING.md`, `README.md`.

---

## D. Analytics

### DR-29 · Khóa insight theo event time, mô hình episode — ⚠ quan trọng
- **Vấn đề:** Hiện UNIQUE dùng `detected_at`. Nếu `detected_at` lấy giờ đồng hồ thì replay sẽ sinh bản ghi mới, phá vỡ tính idempotent. Ngoài ra mỗi micro-batch sẽ sinh một dòng cho cùng một sự kiện kéo dài.
- **Quyết định:**
  - Mọi insight dùng **event time** (thời gian của dữ liệu), không dùng wall clock.
  - Bunching và disruption lưu theo **episode**: thêm các cột `episode_start, episode_end, status OPEN|CLOSED, peak_*` (ví dụ `min_gap_seconds`, `max_z_score`). UNIQUE đổi thành `(route_id, vehicle_leader, vehicle_follower, episode_start)` và `(route_id, episode_start)`. *Sửa 2026-09-26:* UNIQUE của disruption là `(route_id, direction_id, episode_start)`, vì baseline (DR-31) tính theo `(route_id, direction_id)`, nên hai chiều có thể mở episode cùng một bucket. Cặp xe được chuẩn hóa theo thứ tự leader/follower.
  - `id` = UUIDv5(namespace, natural key), đảm bảo tính lại thì được cùng id.
- **Ghi vào:** ADR-0010, DOC-15, DOC-23.

### DR-30 · Thuật toán bunching cụ thể
- **Quyết định:** Với mỗi `(route_id, direction_id)`, xét các xe có vị trí trong vòng 2 phút, sắp theo tiến độ trên tuyến (`shape_dist_traveled` nội suy, nếu thiếu thì dùng `current_stop_sequence`). Với từng cặp liên tiếp (leader L, follower F): **gap = t_event(F) − thời điểm L đi qua trạm kế tiếp của F** (lấy từ trip update đã quan sát). Nếu không có dữ liệu đó thì dùng khoảng cách chia cho tốc độ trung bình. Mở episode khi `gap < 0.5 × headway(route, dir, day_type, hour)` trong 2 lần đánh giá liên tiếp. Đóng episode khi `gap > 0.7 × headway` (hysteresis). Loại trừ 2 trạm đầu và 2 trạm cuối để tránh báo nhầm lúc xe chờ ở đầu bến. Mọi hệ số đều cấu hình được.
- **Làm rõ (2026-09-27, DOC-23 §5.1):**
  - Thời điểm L qua trạm được lấy từ **lịch sử vị trí** của L: `STOPPED_AT` tại trạm, hoặc nội suy giữa hai vị trí. Nếu không có thì nội suy theo lịch (đánh dấu `ESTIMATED`). Lý do: dòng TripUpdate bị ghi đè, nên không biết được lúc nó tới, và vì thế không tái tạo được khi tính lại.
  - `gap = (t_F + thời gian còn lại theo lịch của F tới trạm kế) − pass_L`.
  - Đánh giá theo lưới event time 15 giây, không theo micro-batch.
  - Nhánh "khoảng cách / tốc độ" được bỏ.
- **Ghi vào:** DOC-23.

### DR-31 · Thuật toán disruption cụ thể
- **Vấn đề:** Nếu EWMA cập nhật "mỗi micro-batch" thì ý nghĩa của α sẽ thay đổi theo tải, vì tải cao thì micro-batch dày hơn.
- **Quyết định:** Gom theo **bucket 1 phút event time**. Giá trị hiện tại là trung bình delay của các arrival đã quan sát trong cửa sổ trượt 10 phút, cần tối thiểu 5 mẫu. Baseline EWMA cập nhật một lần mỗi bucket với α = 0,1. Phương sai EW tính bằng `var = (1−α)(var + α·(x−μ)²)`. `z = (x−μ)/max(σ, 30 s)`. Mở episode khi z > 2,5 trong 2 bucket liên tiếp, đóng khi z < 1,5 trong 3 bucket. **Không cập nhật baseline trong khi episode đang mở**, để baseline không "học" luôn sự cố. Warm-up 60 bucket đầu không báo. Trạng thái lưu ở `analytics_route_baseline(route_id, direction_id, ewma_mean, ewma_var, last_bucket, open_episode_id)`. Khi replay một khoảng thời gian, xóa insight trong khoảng đó rồi tính lại tuần tự, bắt đầu từ snapshot baseline theo giờ gần nhất (`analytics_baseline_snapshot`).
- **Ghi vào:** DOC-23.

### DR-32 · ETA: tính lại toàn cửa sổ thay vì cộng dồn
- **Vấn đề:** Cộng dồn trung bình theo watermark sẽ đếm trùng mẫu khi replay.
- **Quyết định:** Job hàng giờ **tính lại hoàn toàn** trên cửa sổ 28 ngày (cấu hình được) từ các dòng `is_observed = true`, GROUP BY `(route, stop, dow, hour)` theo giờ địa phương. Sau đó upsert kết quả và xóa key không còn mẫu. Watermark chỉ dùng để bỏ qua lần chạy khi không có dữ liệu mới. Mức tin cậy: `sample_count < 10` là thấp, 10–29 là trung bình, ≥ 30 là cao. Việc kết hợp thêm độ trễ hiện tại của chuyến đang chạy là tính năng mở rộng tùy chọn (F-ANL-06, FR-06.3).
- **Ghi vào:** DOC-23.

### DR-33 · Định nghĩa OTP
- **Quyết định:** Đơn vị đo là **từng lần xe đến trạm đã quan sát được**. Một lần đến được coi là đúng giờ nếu `−early_tolerance ≤ delay ≤ late_tolerance`, mặc định cả hai là 300 giây theo SDD gốc. `otp_percentage = on_time / observations × 100`. Thêm cột `observation_count`; `trip_count` là số chuyến có ít nhất một lần quan sát. Job chạy 03:00 hằng ngày, tính cho hôm qua và tính lại 2 ngày trước đó để bắt dữ liệu đến trễ.
- **Ghi vào:** DOC-23.

### DR-34 · Phát hiện bất thường ticketing (phần thống kê)
- **Quyết định:** Dùng cửa sổ tumbling 15 phút theo `sale_point`, với các chỉ số `txn_count, refund_count, refund_ratio, amount_sum`. Baseline là trung bình và độ lệch chuẩn theo `(sale_point, day_type, hour)` trên 4 tuần. Cờ bất thường bật khi `z(txn_count) > 3` và `txn_count ≥ 20`, hoặc `refund_ratio > 0.3` và `refund_count ≥ 5`. Job chạy mỗi 5 phút trên các cửa sổ đã đóng (cho phép dữ liệu trễ 2 phút).
- **Ghi vào:** DOC-23.

### DR-35 · Cơ chế kích hoạt analytics micro-batch
- **Quyết định:** Sau khi chunk commit, ETL phát một in-process event `MicroBatchCommitted(route_ids, max_event_time)`. `AnalyticsDispatcher` gom các yêu cầu theo route và chạy trên executor riêng, nên không chặn consumer. Insight được ghi trong transaction riêng. Nếu analytics lỗi thì chỉ log và tăng metric, ETL không bị ảnh hưởng. Thêm một tick mỗi 30 giây để đóng các episode của những tuyến không còn dữ liệu mới.
- **Ghi vào:** DOC-23, ADR-0014.

---

## E. AI triage

### DR-36 · Hợp đồng với Jev — **Chốt** (còn 3 điểm cần spike) · ⚠ lệch SDD gốc
- **Nguồn:** [Spring blog 2026-09-21: Spring AI and TypeSafe Jev](https://spring.io/blog/2026/09/21/spring-ai-typesafe-structured-judgment/) và [tài liệu Spring AI TypeSafe](https://spring-ai-community.github.io/spring-ai-typesafe/latest/).
- **Những gì đã biết:**
  - **Đã có Java SDK** `org.springaicommunity:typesafe-java-sdk` (bản mới nhất lúc viết là 0.2.0; yêu cầu Java 17+). Starter `spring-ai-starter-typesafe` yêu cầu Spring Boot 4.x. SDD gốc nói "chỉ có SDK Python/JS, phải tự viết client HttpClient"; điều này **không còn đúng nữa**.
  - Client chính là `TypeSafeClient.builder().build()`. API key đọc từ biến môi trường `TYPESAFE_API_KEY`, hoặc từ property `spring.ai.typesafe.api-key` khi dùng starter.
  - Lời gọi: `client.systemOne(String state, Map<String, ?> typedQuestions)` → `SystemOneResponse`. Mỗi lời gọi nhận **một state** kèm nhiều câu hỏi. REST tương ứng là `POST /v1/systemone`.
  - Ba loại câu hỏi:
    - `Noul.of(...)` / `Noul.builder().instructions().whenTrue().whenFalse()`: kết quả là một số trong [0,1].
    - `Choice.builder().instructions().option(label, mô tả)`: kết quả là label, xác suất từng lựa chọn và `confidence()`.
    - `Score.of(câu hỏi, mức1, mức2, …)`: kết quả là một giá trị liên tục, xác suất từng mức và confidence.
  - Đọc kết quả bằng `noulValue(k)`, `choiceValue(k)`, `choice(k).confidence()`, `scoreValue(k)`.
  - SDK tự có retry, batch và exception có kiểu. Jev không stream. State chỉ được là string, object, array hoặc null; nếu là số hoặc boolean đứng riêng thì bị lỗi 422.
  - Hiệu năng theo bài blog: median khoảng 275 ms cho 1 câu hỏi, khoảng 310 ms cho 3 câu hỏi; chi phí rất thấp (khoảng $0.00004 cho một lời gọi 14 câu hỏi). Như vậy quota và chi phí không phải rủi ro chính.
- **Quyết định:**
  - Vẫn giữ cổng `DecisionModel` (ADR-0018) để test, CI và demo offline chạy được bằng `FakeDecisionModel`.
  - `JevDecisionModel` bọc `TypeSafeClient` của `typesafe-java-sdk`. **Không dùng Spring AI** (không cần ChatClient hay advisor), nên triage-worker vẫn nhẹ.
  - Câu hỏi của SDD gốc được ánh xạ như sau:
    - `category` → `Choice` (mỗi option có mô tả).
    - `severity` → `Score.of("…", "Informational", "Needs attention", "Urgent")`, giá trị liên tục được làm tròn về 0/1/2, và `severity_confidence` lấy từ confidence của Score.
    - Câu "gián đoạn thật hay lỗi dữ liệu" → `Noul`.
  - **State gửi dưới dạng JSON object có cấu trúc**, không nối chuỗi như ví dụ trong SDD gốc.
  - Resilience4j (timeout 2 s, circuit breaker, bulkhead, rate limiter) đặt **bên ngoài** SDK. **Tắt retry của SDK** (hoặc để SDK retry một lần) để tránh retry chồng lên nhau.
  - Chọn adapter bằng `pti.triage.provider=jev|fake|disabled`. Mặc định là `jev` ở dev/staging và `fake` ở CI.
- **Còn phải xác minh trong spike S-01 (tối đa nửa ngày):**
  1. Response có trả phiên bản model không (header hay field)? Nếu không, lưu `model_version = "jev@<sdk-version>"`.
  2. API batch của SDK ("score many items at once") dùng như thế nào?
  3. Mã lỗi khi bị giới hạn tốc độ hoặc hết quota, và các lớp exception tương ứng.
- **Kết quả spike S-01:** _chưa chạy; hoãn tới đầu P6 (2026-09-28) vì chưa có API key Jev, không chặn M0._ Khi chạy xong, ghi vào đây: (1) phiên bản model lấy từ đâu và nhánh `model_version` nào được dùng (DR-73); (2) API batch có dùng được không, nếu có thì kích thước lô; (3) bảng mã lỗi và lớp exception của SDK, đối chiếu với DOC-24 §11.2; (4) tên phương thức builder để tắt retry; (5) latency median/p95 đo được. Response mẫu đã che API key được lưu làm fixture (DOC-24 §3).
- **Ghi vào:** ADR-0018, DOC-24, DOC-11.

### DR-37 · Cách triage worker lấy việc
- **Quyết định:** Mỗi vòng, worker chạy `SELECT … FROM dead_letter WHERE status='NEW' AND triage_attempts < 5 ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED` trong một transaction ngắn và đánh dấu `TRIAGING` kèm lease 2 phút. Gọi Jev **bên ngoài transaction**, sau đó ghi kết quả. Record nào lease hết hạn thì quay lại `NEW`. Worker phát metric `dlq_untriaged` để KEDA scale.
- **Cập nhật theo DR-36:** mỗi record DLQ là **một state, gửi trong một lời gọi** gồm hai câu hỏi `category` và `severity`. Các lời gọi chạy song song trong giới hạn của bulkhead (mặc định 8) và rate limiter. Nếu S-01 cho thấy API batch của SDK dùng được thì chuyển sang gom tối đa 20 record mỗi lần gọi.
- **Cập nhật theo DR-72, DR-74:** lease kiêm mốc backoff; KEDA dùng PostgreSQL scaler thay cho metric `dlq_untriaged`.
- **Ghi vào:** DOC-24.

### DR-38 · "Nguồn đã hồi phục" nghĩa là gì
- **Quyết định:** Mỗi nguồn có một health indicator trong ETL (`/actuator/health/source-gtfs-rt`, `source-ticketing`, `warehouse-db`), dựa trên độ tươi của feed (< 30 giây), trạng thái circuit breaker và trạng thái connector. Auto-replay chỉ chạy khi indicator của đúng nguồn đó là `UP` liên tục ít nhất 60 giây.
- **Ghi vào:** DOC-24.


### DR-72 · Vòng xử lý triage và luật chặn cuối — **Chốt**
- **Vấn đề:** DR-37 mới mô tả cách lấy việc; chưa chốt retry khi Jev lỗi, cách tránh gọi lại vô hạn, ai quyết định hành động, và "luật chặn cuối không phụ thuộc AI" của SDD §9.6 cụ thể là gì. SDD gốc để triage-worker tự ghi `alert_event` cho record severity 2, trùng vai trò với Alertmanager.
- **Quyết định:**
  - Cột lease kiêm luôn mốc "không xử lý trước": claim chỉ lấy dòng có lease rỗng hoặc đã qua. Lỗi tính lần thử cộng `triage_attempts` và lùi `30 s · 2^(n−1)` (tối đa 15 phút); lần thứ 5 → `MANUAL` (DLQ) hoặc `FAILED` (insight). Lỗi hạ tầng tạm thời (circuit mở, quota, timeout) chỉ lùi 30 giây, không tính lần thử, và tạm dừng vòng. `LeaseSweeper` trả dòng hết lease về hàng đợi mỗi 30 giây (ShedLock).
  - Hành động do bảng quyết định trong code (`DlqDecisionTable`) và `AutoReplayGuard` sở hữu (ADR-0019, DOC-24 §6.3–6.4).
  - triage-worker **không ghi** `alert_event`. Cảnh báo theo severity DLQ đi qua Prometheus → Alertmanager → webhook API (DR-51): alert `DlqSevereRecords`, `DlqNeedsAttention`, `DlqUpstreamErrorBurst`, `TriageBacklogHigh`.
  - Luật chặn cuối: etl-stream gán category tất định bằng `DlqRuleClassifier` ngay khi ghi DLQ và phát `pti_dlq_rule_category_total`; alert `DlqUpstreamErrorBurst` dựa trên metric này nên vẫn bắn khi triage-worker hoặc Jev chết.
- **Ghi vào:** DOC-24, DOC-15, DOC-17, DOC-22, DOC-28, ADR-0018, ADR-0019.

### DR-73 · Đường auto-replay trong demo và định dạng `model_version` — **Chốt**
- **Vấn đề:** (1) Với ngưỡng lệch giờ mặc định 1 giờ, demo khó sinh ra record mà guard cho phép auto-replay (DQ-07 đến muộn). (2) S-01 chưa xác nhận response Jev có trả phiên bản model.
- **Quyết định:**
  - Simulator có kịch bản `late-delivery` (DOC-25 §7.9, mặc định trễ `PT6M`). `make up-demo` đặt `PTI_DQ_MAX_CLOCK_SKEW=5m` cho etl-stream, nên record trễ 6 phút vào DQ-07 và được auto-replay khi nguồn `UP` ≥ 60 giây. Compose thường giữ `1h`.
  - `model_version`: `jev:<id>` nếu response có id model; nếu không thì `jev@<sdk-version>` (ví dụ `jev@0.2.0`); `fake@2026.09` cho `FakeDecisionModel`. Kết quả S-01 quyết định nhánh nào được dùng.
- **Ghi vào:** DOC-24 §13, §20, DOC-25, DOC-39, DOC-46, DOC-32, DOC-33.

### DR-74 · Scale triage-worker và virtual thread — **Chốt** · thay một phần DR-37
- **Vấn đề:** DR-37 cho worker phát metric `dlq_untriaged` để KEDA scale. Metric của pod không có khi mọi pod đã về 0 và lệch giữa các pod; hàng đợi thật nằm trong Postgres.
- **Quyết định:**
  - KEDA dùng **PostgreSQL scaler** truy vấn thẳng số dòng `ops.dead_letter` `NEW` đủ điều kiện claim; `minReplicaCount: 1`, `maxReplicaCount: 3` (giữ tổng lời gọi Jev ≤ 60/giây vì rate limiter theo pod). Scaler dùng role chỉ đọc (DOC-40). Không phát `dlq_untriaged`; gauge `pti_triage_backlog` chỉ để quan sát và alert.
  - Mỗi pod triage có Hikari pool 4, nên 3 pod dùng tối đa 12 kết nối.
  - Virtual thread (`spring.threads.virtual.enabled`): `api` và `triage-worker` bật; `etl` và `source-simulator` tắt (DOC-29 §2). S-06 chỉ có thể dẫn tới tắt, không bật thêm.
- **Ghi vào:** DOC-24, DOC-29, DOC-40, DOC-07.

---

## F. API và real-time

### DR-39 · Quy ước API
- **Quyết định:** Prefix `/api/v1`. JSON dùng camelCase. Lỗi trả theo RFC 9457 Problem Details. Danh sách chuỗi thời gian phân trang bằng keyset (`?limit=50&cursor=…` → `{items, nextCursor}`); danh sách nhỏ trả hết. Tham số thời gian theo ISO-8601 có offset, mặc định 24 giờ gần nhất, khoảng tối đa 31 ngày. Mỗi response có header `X-Trace-Id` và `X-Data-As-Of` (thời điểm dữ liệu mới nhất).
- **Ghi vào:** DOC-31.

### DR-40 · Xác thực và nhà cung cấp danh tính (IdP) — **Chốt** (full stack)
- **Vấn đề:** SDD gốc có OAuth2 Resource Server nhưng chưa nói ai phát token. Ngoài ra hành khách không cần đăng nhập.
- **Quyết định:** Chạy Keycloak trong compose (`start-dev`, import realm `pti` từ JSON), với realm role `viewer` và `operator`, cùng user demo `viewer/viewer` và `operator/operator`. SPA đăng nhập theo OIDC Authorization Code + PKCE. Endpoint cho hành khách (`/routes/**`, `/stops/**`, `/vehicles/live`, disruption bản public, SSE kênh public) không cần đăng nhập, chỉ bị rate limit. Endpoint `/insights/**` loại nội bộ và `/etl/**` cần `viewer` để đọc và `operator` để ghi. Keycloak tốn khoảng 500–700 MB RAM; con số này được tính trong ngân sách tài nguyên (S-03).
- **Ghi vào:** ADR-0017, DOC-27.

### DR-41 · SSE: kênh, id sự kiện, kết nối lại
- **Quyết định:**
  - `GET /api/v1/stream?channels=vehicles,alerts,jobs,dlq&routeId=…`. Kênh `vehicles` và `alerts` (phần public) không cần xác thực. Kênh `jobs` và `dlq` cần token. Vì `EventSource` không gửi được header, SPA dùng `@microsoft/fetch-event-source` cho kênh cần xác thực.
  - Publisher gán id sự kiện là ULID (có thứ tự thời gian). Mỗi pod API giữ ring buffer 5 phút. Khi client kết nối lại với `Last-Event-ID`, pod phát bù các sự kiện có id lớn hơn. Nếu vượt quá buffer thì gửi sự kiện `resync` để client refetch qua REST.
  - Kênh `vehicles` được gộp mỗi giây thành một sự kiện `vehicles.batch`, không đẩy từng vị trí riêng lẻ.
- **Ghi vào:** ADR-0016, DOC-26, DOC-33.

### DR-42 · Phát sự kiện UI: không dùng outbox
- **Quyết định:** ETL/analytics publish vào `pti.events.ui` **sau commit**, theo kiểu best-effort. Nếu mất một sự kiện thì chấp nhận được, vì nguồn sự thật nằm ở DB (`alert_event`, các bảng insight) và client refetch định kỳ 60 giây hoặc khi nhận `resync`.
- **Ghi vào:** ADR-0026.

### DR-43 · Endpoint còn thiếu trong SDD gốc
- **Quyết định bổ sung:** `GET /routes/{id}` (chi tiết, kèm shape GeoJSON), `GET /stops?bbox=&q=`, `GET /stops/{id}`, `GET /alerts?audience=&since=`, `POST /alerts/{id}/ack`, `GET /etl/jobs/{id}` (kèm step execution và số liệu read/write/skip từ metadata Spring Batch), `POST /etl/jobs/{id}/restart` (chỉ cho job batch FAILED; API ghi yêu cầu, `etl-batch` gọi `JobOperator.restart`), `GET /etl/jobs/summary?bucket=1m`, `GET /etl/dlq/{id}`, `PUT /etl/dlq/{id}/payload`, `POST /etl/dlq/{id}/discard`, `GET /etl/dlq/actions` (nhật ký auto-replay), `GET /etl/replays`, `GET /etl/replays/{id}`, `GET/PUT /etl/flags/{key}`, `GET /system/freshness`, `GET /me`. Proxy điều khiển simulator `/sim/**` chỉ có ở profile demo.
- **Ghi vào:** DOC-32.

### DR-44 · Contract testing — ⚠ lệch SDD gốc
- **Quyết định:** Không dùng Spring Cloud Contract (nặng và thiên về JVM↔JVM). Thay bằng:
  1. JSON Schema cho message Kafka, dùng chung cho test producer (simulator) và test consumer (ETL).
  2. Chạy Debezium thật trong Testcontainers để kiểm tra parser CDC.
  3. Sinh `openapi.json` khi build và commit vào repo; CI chạy `openapi-diff` chặn breaking change; frontend sinh type bằng `openapi-typescript` và CI chạy typecheck.
- **Ghi vào:** ADR-0027, DOC-44.

### DR-45 · Rate limit
- **Quyết định:** Dùng Bucket4j in-memory, giới hạn theo IP cho endpoint public (60 request/phút, tối đa 5 kết nối SSE đồng thời mỗi IP). Giới hạn tính theo từng pod; ghi rõ hạn chế này trong tài liệu.
- **Ghi vào:** DOC-31.

### DR-103 · Không dùng Redis — **Chốt** (P4)
- **Vấn đề:** App `api` chạy nhiều pod trên k3d (HPA 2→4 ở `lite`, 2→6 ở `full`), có SSE, cache và rate limit. Có nên thêm Redis làm backplane cho SSE, cache dùng chung hoặc rate limit chung không?
- **Quyết định:** Không dùng Redis. Mọi vai trò Redis hay đảm nhận đã có giải pháp trên Kafka và PostgreSQL: fan-out SSE qua `pti.events.ui` với consumer group riêng mỗi pod (ADR-0016), cache Caffeine theo pod (DOC-31 §10.3), rate limit Bucket4j theo pod (DR-45), `api` stateless với JWT (ADR-0017), khóa job bằng ShedLock và `VERSION` (ADR-0015), idempotency key bằng cột UNIQUE (DOC-31 §8), lease triage trên dòng (DR-37). Lý do:
  1. Không có việc nào cần tới Redis ở quy mô đồ án (một agency, khoảng 1.000 xe, 2–6 pod `api`).
  2. ADR-0016 đã đặt ràng buộc không thêm hạ tầng ngoài Kafka và PostgreSQL.
  3. Idempotency, khóa job, con trỏ và baseline analytics phải commit cùng dữ liệu nghiệp vụ; đặt ở Redis sinh trạng thái lệch giữa hai hệ thống, trái NFR-01.
  4. Mọi trạng thái hiện dựng lại được từ raw zone (EXP-04); Redis là một nơi giữ trạng thái nữa phải backup và giải thích.
  5. Thêm bề mặt chịu lỗi cho NFR-09 và EXP-08 (chaos, alert, runbook, fail-open hay fail-closed, Sentinel trên k3d).
  6. Ngân sách RAM compose đã khoảng 8 GB ở profile core, 10,4 GB khi bật đủ (DOC-10 §5).
  7. NFR-10 dựa vào dữ liệu tính sẵn và replica, không dựa vào cache dùng chung.
- **Hệ quả:** Rate limit và cache tính theo pod: hạn mức thực gấp N lần; một key bị nạp tối đa N lần mỗi TTL; hai pod có thể lệch nhau trong một TTL. Hai việc phát sinh: cache nóng TTL ngắn nạp single-flight trong pod (DOC-31 §10.3); đo số request của SPA anonymous trên compose ở P5 so với bucket `public` (DOC-31 §11).
- **Xem lại khi:** cần quota chính xác cho cả cụm, truy vấn nặng mà cache theo pod không đỡ được (p95 vượt NFR-10 dù đã tính sẵn), cần xóa cache ngay trên mọi pod, SSE cần định tuyến theo người dùng hoặc số pod lớn tới mức đọc toàn topic tốn kém, chuyển sang session phía server, bộ đếm ghi rất dày, hoặc nhiều tenant. Mỗi trường hợp thử phương án không cần Redis trước (ví dụ `bucket4j-postgresql`, `proxy_cache` của nginx, Spring Session JDBC); bảng đầy đủ và nguyên tắc khi thêm Redis ở ADR-0031.
- **Ghi vào:** ADR-0031, DOC-11 §7, DOC-31 §10.3 và §11.

---

## G. Frontend và UX

### DR-46 · Stack frontend — **Chốt**
- **Quyết định:** Vite, React 19, TypeScript strict, pnpm. TanStack Router (search params có kiểu, tiện đồng bộ bộ lọc lên URL). TanStack Query. Tailwind CSS cùng shadcn/ui (Radix). Apache ECharts cho biểu đồ chuỗi thời gian. TanStack Table + TanStack Virtual cho bảng DLQ. MapLibre GL JS qua `react-map-gl/maplibre`. react-hook-form + zod. CodeMirror 6 để sửa payload JSON. `react-oidc-context`. Zustand cho state UI. Test bằng Vitest, React Testing Library, MSW và Playwright.
- **Ghi vào:** ADR-0020, DOC-11.

### DR-47 · Bản đồ khi demo offline — **Chốt** (đã xác minh ở S-05)
- **Quyết định:** Dùng MapLibre với file PMTiles cắt riêng vùng thành phố của feed, serve qua nginx của frontend, để demo không phụ thuộc internet. Khi dev có thể dùng style từ nhà cung cấp tile miễn phí (ví dụ OpenFreeMap).
- **Kết quả spike S-05** (2026-09-28): file cắt cho bbox của feed ở maxzoom 15 nặng 84 MB. Bản đồ render hoàn toàn offline, không có request ra ngoài, ở cả nền sáng lẫn tối. Chi tiết ở ADR-0021; các thay đổi kéo theo ở DR-82.
- **Ghi vào:** ADR-0021.

### DR-48 · Ngôn ngữ và hiển thị thời gian — **Chốt**
- **Quyết định:** **Giao diện hoàn toàn bằng tiếng Anh.** Chuỗi hiển thị gom vào `src/i18n/en.ts`, nhưng chưa dựng framework i18n. Thời gian hiển thị theo múi giờ của agency, có nhãn múi giờ. Số, ngày và tiền định dạng theo `en-US` (khớp với feed Minneapolis ở DR-01). Microcopy trong DOC-37 và `screens/*` ghi đúng chuỗi tiếng Anh sẽ hiện trên UI; phần giải thích xung quanh vẫn viết tiếng Việt.
- **Ghi vào:** DOC-37, DR-61.

### DR-49 · Màn điều khiển kịch bản demo — **Chốt**
- **Quyết định:** Thêm tab "Demo control" trong ops console, chỉ hiện khi bật profile `demo`, để bật và tắt kịch bản simulator mà không phải dùng curl. Việc này giúp buổi bảo vệ trơn tru hơn.
- **Ghi vào:** DOC-36 (`screens/demo-control.md`: route `/ops/demo`, cần role operator và `demoControl` trong `env.js`).

---

## H. Vận hành, hạ tầng, thực nghiệm

### DR-50 · Nơi lưu trữ log và trace
- **Vấn đề:** SDD gốc có OpenTelemetry nhưng chưa có nơi lưu trace, và log JSON cũng chưa có nơi lưu.
- **Quyết định:** Metrics: Micrometer → Prometheus (scrape), gồm metric có sẵn của Spring Batch và Spring Kafka. Traces: Micrometer Observation → Micrometer Tracing (bridge OpenTelemetry) → OTLP → OTel Collector → Grafana Tempo; bật observation của Spring Kafka (`observation-enabled` cho listener và template) để `traceparent` đi qua Kafka header, và observation của Spring Batch cho job/step. Không dùng OTel Java agent song song để tránh span trùng. Logs: JSON có cấu trúc (structured logging có sẵn của Spring Boot) ra stdout → Grafana Alloy → Loki, `trace_id`/`span_id` tự vào MDC nhờ Micrometer Tracing. Tất cả nằm trong compose profile `observability`.
- **Ghi vào:** ADR-0022, DOC-28.

### DR-51 · Kênh cảnh báo khi demo
- **Quyết định:** Alertmanager gửi tới (1) Mailpit (SMTP giả, có web UI) và (2) webhook `POST /internal/alerts/alertmanager` của API. API ghi cảnh báo vào `alert_event` với `audience=ENGINEERING`, để alert feed trên dashboard có cả cảnh báo hạ tầng. Slack là tùy chọn.
- **Ghi vào:** DOC-28.

### DR-52 · Công cụ chạy thực nghiệm
- **Quyết định:** Python 3.12 quản lý bằng uv, dùng httpx, psycopg, docker SDK và `kubectl` (subprocess), pandas, matplotlib. Mỗi lần chạy lưu `experiments/results/<EXP>/<run_id>/` gồm `config.json` (git SHA, tham số, môi trường), `raw.csv`, `summary.json` và biểu đồ.
- **Ghi vào:** ADR-0025, DOC-45.

### DR-53 · Phiên bản công nghệ — **Chốt: dùng bản mới nhất** · ⚠ lệch SDD gốc
- **Quyết định:**
  - **Spring Boot 4.1.x** (bản mới nhất lúc viết là 4.1.1, được hỗ trợ tới 2027-07-31; nguồn: [spring.io](https://spring.io/blog/2026/06/10/spring-boot-4/), [endoflife.date](https://endoflife.date/spring-boot)). Kéo theo Spring Framework 7, Jakarta EE 11, Spring Kafka 4.x, Spring Security 7.
  - **Java 25 LTS** (đề xuất thay cho Java 21 của SDD gốc, cho khớp với nguyên tắc "mới nhất"; Boot 4.1 hỗ trợ tới Java 26).
  - Kafka 4.x (chỉ còn KRaft), Debezium 3.x, PostgreSQL 17 (dùng 18 nếu CNPG và Debezium đã hỗ trợ ổn định), Node 24 LTS, React 19, `typesafe-java-sdk` 0.2.x.
  - Ghi version cố định vào `backend/gradle/libs.versions.toml`, `frontend/package.json` và `mise.toml`. Mỗi phase kiểm tra lại bản patch mới nhất một lần.
- **Cần xác minh trong spike S-06:**
  0. **Spring Batch 6.x (đi kèm Boot 4.1) cho ETL (ADR-0002):** (a) API của fault-tolerant chunk step (skip, retry, scan) và cơ chế retry nó dựa vào (Spring Framework 7 core retry thay cho Spring Retry); (b) `SkipListener` vẫn được gọi trong transaction của chunk; (c) cách bật JobRepository JDBC thay cho bản resourceless; (d) có API khôi phục execution kẹt ở STARTED hay phải tự cập nhật (DR-24); (e) `JobOperator` thay cho `JobLauncher`; (f) ShedLock, Spring Cloud AWS S3 và `ContainerPausingBackOffHandler` của Spring Kafka 4 có bản tương thích. Một app mẫu phải chạy được test "lỗi ghi ở item thứ 37 → 499 dòng ghi, 1 dòng DLQ, restart đọc tiếp đúng vị trí".
  1. Resilience4j đã hỗ trợ Spring Boot 4 chưa. Nếu chưa, thử cơ chế resilience có sẵn trong Spring Framework 7 (`@Retryable`, `@ConcurrencyLimit`), còn circuit breaker và rate limiter vẫn dùng Resilience4j core, cấu hình thủ công không qua starter.
  2. springdoc-openapi, Testcontainers, Micrometer Tracing (bridge OTel, DR-50) và jib-gradle có tương thích với Boot 4.1 và Java 25 không.
  3. Spring Boot 4 đổi package (Jackson 3, tách module autoconfigure). Ghi các thay đổi này vào DOC-11 để người triển khai không làm theo tài liệu cũ của Boot 3.
- **Kết quả spike S-06** (2026-09-28, app mẫu `spikes/s06-boot41-java25/`, 20 test trên Postgres 17.11 và 18.1, Kafka 4.3.1, SeaweedFS 4.47):
  - 0(a) Batch 6.0.5 có hai builder chunk step. `ChunkOrientedStepBuilder` mới dùng core retry của Spring Framework 7; builder cũ `chunk(size, tx).faultTolerant()` (deprecated for removal) vẫn dùng Spring Retry 2.0.x. (b) Skip listener chạy trong transaction của chunk và dòng DLQ được commit **chỉ với builder cũ**; step mới rollback dòng DLQ. Thêm nữa, step mới bỏ sót item khi process chết giữa lúc scan. Chọn builder cũ, xem DR-80. (c) `spring-boot-starter-batch-jdbc` + `spring.batch.jdbc.table-prefix=batch.BATCH_`; context lưu JSON qua bean `JacksonExecutionContextStringSerializer`. (d) Có `JobOperator.recover(JobExecution)`: đưa execution kẹt về `FAILED`, tăng `VERSION`; bản giữ `VERSION` cũ cập nhật thì nhận `OptimisticLockingFailureException`. (e) `JobOperator.start` thay `JobLauncher.run`; `restart` cần job đăng ký trong `JobRegistry`. (f) ShedLock 7.10.1, Spring Cloud AWS 4.1.1, `ContainerPausingBackOffHandler` của Spring Kafka 4.1.1 đều chạy được. Test "lỗi ghi ở item 37" đạt 499 dòng, 1 DLQ; restart đọc tiếp đúng từ item 701.
  - 1. Resilience4j 2.4.0 có module `resilience4j-spring-boot4`; không cần cấu hình thủ công.
  - 2. springdoc 3.1.1, Testcontainers 2.0.5, Micrometer Tracing (bridge OTel qua `spring-boot-starter-opentelemetry`) và Jib 3.5.4 tương thích.
  - 3. Khác biệt so với Boot 3 ghi ở DOC-11 §6.
  - PostgreSQL 18.1 chạy được với schema Spring Batch và toàn bộ test; việc đổi sang 18 còn chờ S-04 (Debezium) và CNPG.
- **Kết quả spike S-04** (2026-09-28): Debezium 3.6.3 chạy đúng trên PostgreSQL 18.6 (snapshot, insert, update, delete, heartbeat; slot `pgoutput`). Chỉ còn CNPG chưa kiểm. **Quyết định: giữ 17.11 cho P1–P6.** P7-01 dựng CNPG với image 18; nếu chạy được thì đổi compose và k3d sang 18 trong cùng một thay đổi. Lúc đổi phải sửa mount volume, vì image 18 đặt dữ liệu ở `/var/lib/postgresql/18/docker` (DOC-11 §2). Dữ liệu dev dựng lại được bằng `make reset`, nên không cần `pg_upgrade`.
- **Ghi vào:** ADR-0029 (mới), DOC-11.

### DR-54 · Công cụ Kubernetes
- **Quyết định:** k3d cùng registry cục bộ. Cài operators bằng `helmfile` (Strimzi, CloudNativePG, KEDA, Chaos Mesh, Sealed Secrets, kube-prometheus-stack). Ứng dụng đóng thành một umbrella chart `pti` với các values `dev`, `staging`, `lite`.
- **Ghi vào:** ADR-0028, DOC-40.

### DR-55 · Debezium khi Postgres failover
- **Vấn đề:** Replication slot logic có thể mất sau failover nếu không được đồng bộ sang replica.
- **Quyết định:** Database nguồn ticketing là một instance **riêng, đơn lẻ**, không nằm trong kịch bản failover. EXP-08 chỉ failover **warehouse**. Ghi rõ giới hạn này. Nếu còn thời gian, thử failover slot trên PG17 + CNPG như một spike bổ sung.
- **Ghi vào:** DOC-40, DOC-45 (EXP-08).

### DR-56 · Repo và registry — **Chốt: GitHub public** (đổi từ private ngày 2026-09-27)
- **Quyết định:** Repo GitHub **public**. Image đẩy lên GHCR, package để public, theo dạng `ghcr.io/<owner>/pti-<app>:<git-sha>`, build cho `linux/amd64` và `linux/arm64`. Khi dev trên k3d thì dùng registry cục bộ `k3d-pti-registry:5000`. Image build bằng Jib; image Kafka Connect có plugin build bằng Dockerfile riêng.
- **Lý do đổi:** với repo private, runner chuẩn của GitHub chỉ có 2 vCPU và 7 GB RAM và số phút có hạn, nên E2E và k3d phải chạy trên self-hosted runner là máy dev. Với repo public, runner chuẩn có 4 vCPU, 16 GB RAM và không giới hạn phút (số liệu lúc chốt), đủ cho compose đủ profile hoặc k3d `lite`.
- **Hệ quả:**
  - Mọi workflow chạy trên runner của GitHub; không dùng self-hosted runner, vì GitHub khuyến cáo không dùng self-hosted runner cho repo public (PR từ fork có thể chạy code trên máy).
  - Mỗi PR: format, build, unit test, integration test cho các module có thay đổi (lọc theo path, để phản hồi nhanh). Khi merge vào `main`: thêm contract test, quét bảo mật, build image. E2E và k3d: `full-stack.yml` hằng đêm và bấm tay.
  - Code, tài liệu, log Actions và artifact đều công khai: không dùng `pull_request_target`, secret deploy nằm trong environment giới hạn `main`, bật secret scanning và push protection (DOC-41 §9.1). Cần kiểm tra quy định của trường về việc công khai mã nguồn đồ án trước khi bảo vệ.
  - Secret `TYPESAFE_API_KEY` lưu trong GitHub Secrets; CI dùng `provider=fake` nên không cần key.
- **Ghi vào:** DOC-41, DOC-40 §3.2, DOC-01 §8.
- **Tình trạng thực tế:** tới hết P1 repo vẫn private. Ngày 2026-09-29 đã chuyển sang public, đồng thời bật secret scanning và push protection (DOC-41 §9.1). Trước đó gitleaks đã quét toàn bộ lịch sử, không có secret; file duy nhất trông giống credential là `spikes/s04-kafka-connect/s3.json`, chứa credential giả của spike. Chưa có package GHCR nào, nên phần "package để public" áp dụng khi job `images` đẩy image đầu tiên.

### DR-57 · Cách đo NFR-03 (độ trễ đầu-cuối)
- **Quyết định:** `end_to_end_latency_seconds` là histogram đo **tại API ngay lúc phát SSE**, bằng `now − Kafka record timestamp (CreateTime)` của event gốc. Timestamp gốc được mang theo trong sự kiện UI. Thêm hai metric chặng để biết chậm ở đâu: `kafka_to_commit_seconds` (tại ETL) và `commit_to_emit_seconds` (tại API). Trên compose/k3d, mọi thành phần chạy chung một máy nên chung đồng hồ. Playwright đo thêm độ trễ hiển thị cho phần demo.
- **Ghi vào:** DOC-10, DOC-28.

### DR-58 · Cách đo "khớp hoàn toàn" ở EXP-04
- **Quyết định:** So sánh từng bảng theo hai tiêu chí: (1) số dòng; (2) `md5(string_agg(<các cột nghiệp vụ>::text, '|' ORDER BY <business key>))`. Loại khỏi phép so sánh các cột `batch_id, ingested_at, updated_at, computed_at`. Id của insight là UUIDv5 tất định nên vẫn được đưa vào so sánh.
- **Ghi vào:** DOC-45.

### DR-59 · Minh chứng schema evolution
- **Quyết định:** Tạo sẵn `schema_version=2` cho VehiclePosition, thêm trường optional `occupancy_status`. Simulator phát xen kẽ v1 và v2. Test chứng minh parser đọc được cả hai phiên bản và message v3 (chưa biết) sẽ vào DLQ với `stage=SCHEMA`.
- **Ghi vào:** DOC-09, DOC-20.

### DR-60 · Dữ liệu cá nhân (PII)
- **Quyết định:** Trường `customer_ref` bị loại tại processor của ETL, trước bước ghi và trước bước ghi DLQ (payload lưu vào DLQ cũng phải đã được làm sạch). Prompt builder của triage có test khẳng định không có trường nào nằm trong danh sách chặn. Raw zone (SeaweedFS, DR-66) vẫn giữ nguyên bản gốc, và quyền truy cập bucket được giới hạn.
- **Ghi vào:** DOC-18, DOC-27.

### DR-61 · Quy ước ngôn ngữ — **Chốt**
- **Quyết định:** **Tài liệu trong `docs/` viết tiếng Việt. Mọi thứ khác dùng tiếng Anh:** chuỗi trên UI, tên biến và class, comment, log, thông báo lỗi API (`title`/`detail` của Problem Details), tên metric, nhãn dashboard Grafana, mô tả alert, commit message, tiêu đề và nội dung PR, tên test, README trong từng module code, nội dung gửi sang Jev (state và mô tả option).
- **Hệ quả:** trong các tài liệu tiếng Việt, mọi chuỗi sẽ xuất hiện trong sản phẩm (microcopy, thông báo lỗi, tên alert) được ghi nguyên văn bằng tiếng Anh.
- **Ghi vào:** DOC-37, master plan §7.3.

### DR-66 · Object storage cho raw zone: SeaweedFS thay MinIO — **Chốt** · ⚠ lệch SDD gốc
- **Vấn đề:** SDD gốc dùng MinIO. Spike S-03 (2026-09-26) xác nhận repository `minio/minio` không còn trên Docker Hub (`pull access denied … repository does not exist`), Quay cũng không có tag nào. Một máy sạch không kéo được image, nên vi phạm NFR-07.
- **Các phương án đã thử** (cùng bộ kiểm tra S3: tạo bucket, versioning, ghi hai phiên bản, lifecycle có `NoncurrentVersionExpiration`, multipart 12 MB, delete marker):
  - **SeaweedFS 4.47** (Apache-2.0, từ 2012, phát hành hằng tuần): đạt cả bộ kiểm tra. Credential riêng theo identity (`s3.json`), có quyền theo bucket: 9/9 trường hợp đúng kỳ vọng (connector chỉ ghi `raw`, etl chỉ đọc, sai key bị từ chối). RAM đỉnh 196 MiB.
  - **RustFS 1.0.0** (Apache-2.0, tương thích MinIO): đạt cả bộ kiểm tra, RAM đỉnh 132 MiB. Tuy nhiên bản 1.0 mới phát hành 10 ngày trước spike.
  - Garage: AGPL, không thử.
- **Quyết định:** Dùng **SeaweedFS**, pin `chrislusf/seaweedfs:4.47`, chạy `server -s3` (master, volume, filer và S3 gateway trong một container), `mem_limit` 384 MB. Credential của `connect` (Read/Write/List trên `raw`), `etl` (Read/List trên `raw`, Write chỉ trên `raw/gtfs-static/*`; S-04 xác nhận SeaweedFS hỗ trợ quyền theo prefix, DOC-18 §3) và `admin` (chỉ job `s3-init`) nằm trong `s3.json` sinh từ `.env`. Job `s3-init` dùng `amazon/aws-cli` để tạo bucket, bật versioning và đặt lifecycle. Code chỉ dùng API S3 chuẩn với path-style access; RustFS là phương án dự phòng, đổi không phải sửa code.
- **Ghi vào:** DOC-07, DOC-10, DOC-11, ADR-0012, DOC-18, DOC-39, DOC-40.

### DR-67 · Đồng hồ nghiệp vụ và độ lệch giờ — **Chốt**
- **Vấn đề:** Feed dùng giờ `America/Chicago`, lệch 12 giờ (CDT) hoặc 13 giờ (CST) so với Việt Nam. Demo hay thực nghiệm lúc 14:00 ở Hà Nội rơi vào khoảng 02:00 ở Minneapolis, khi hầu như không có xe chạy; EXP-05/07 cần tải nền khoảng 500 xe. `pti.sim.time-offset` của DR-08 chỉ dời đồng hồ của simulator, nên dữ liệu sẽ lệch với phần còn lại: headway theo giờ (DR-12) tra theo giờ thật và báo bunching nhầm hàng loạt, rule DQ "`event_timestamp` lệch quá ±1 giờ so với hiện tại" loại mọi message, API "24 giờ gần nhất" bỏ sót dữ liệu mới.
- **Quyết định:**
  - Một **đồng hồ nghiệp vụ** `businessNow = giờ hệ thống + pti.clock.offset`, dùng chung cho simulator, etl (cả hai profile) và api; env `PTI_CLOCK_OFFSET`, Duration, mặc định `0s`, làm tròn tới phút, trong khoảng ±24 giờ. Compose lấy từ một biến trong `.env`; `make clock-offset AT=16:30` tính và ghi giá trị.
  - Mỗi app có bean `java.time.Clock` (`BusinessClock` trong `common`). Logic nghiệp vụ chỉ lấy "bây giờ" từ bean này. SQL không so cột event time với `now()` của DB mà nhận tham số `:now`.
  - Theo giờ nghiệp vụ: `event_timestamp` của GTFS-rt, `created_at` của giao dịch vé (simulator đặt tường minh), ngày phục vụ, partition theo ngày, rule DQ về thời gian, độ tươi dữ liệu, khoảng thời gian mặc định của API.
  - Theo giờ thật: timestamp record Kafka (CreateTime), `produced_at`, `__source_ts_ms` của Debezium, cột audit (`ingested_at`, `updated_at`), log và trace, `sim_ledger.produced_at`.
  - Replay theo khoảng thời gian đổi giờ nghiệp vụ sang giờ record bằng offset hiện tại; không đổi offset giữa lúc ghi dữ liệu và lúc replay (EXP-04 chạy với một offset cố định). Frontend tính "cách đây bao lâu" theo giờ server.
  - Offset được ghi vào `config.json` của mỗi lần chạy thực nghiệm.
- **Ghi vào:** DOC-25, DOC-16, DOC-22, DOC-26, DOC-29, DOC-38, DOC-39, DOC-45.

### DR-68 · Tạo tải gấp N lần — **Chốt**
- **Vấn đề:** DOC-13 §4 dự kiến nhân bản chuyến và xe (`<vehicle_id>-X<k>`) để tạo tải. Chuyến nhân bản cần `trip_id` mới (nếu giữ `trip_id` gốc thì business key của TripUpdate trùng và ghi đè nhau), nên rule DQ tham chiếu trip sẽ loại chúng; xe nhân bản chạy lệch pha cũng làm bunching báo nhầm hàng loạt.
- **Quyết định:** Tải gấp N lần được tạo bằng cách **chia chu kỳ phát** VehiclePosition và TripUpdate cho N (`rateMultiplier.gtfsRt`, `PUT /sim/rate`, kịch bản `load-ramp`). Mọi tham chiếu vẫn hợp lệ, analytics không đổi ngữ nghĩa, còn số message, số lần upsert và lag tăng đúng N lần. Ở N = 10, mỗi xe phát vị trí mỗi 0,5 giây (khoảng 1.200 VehiclePosition/giây lúc cao điểm). Không nhân bản xe.
- **Ghi vào:** DOC-13 §4, DOC-25, DOC-45 (EXP-05, EXP-07).

### DR-69 · Gán stage DLQ cho rule chất lượng — **Chốt**
- **Vấn đề:** FR-02.3 xếp "route, stop, trip không có trong feed" và "`event_timestamp` lệch ±1 giờ" vào stage `BUSINESS`, trong khi DR-25 và DOC-25 §7.3 xếp chúng vào `QUALITY`. FR-02.4 đưa mọi bản cũ hơn của cùng business key trong một chunk vào DLQ, nhưng khi consumer đuổi lag thì hai TripUpdate của cùng chuyến trong một chunk là chuyện bình thường; DLQ sẽ đầy record hợp lệ. Stage `DEDUP` có trong DDL nhưng chưa rule nào dùng.
- **Quyết định:**
  - `SCHEMA`: sai cấu trúc (DQ-01). `QUALITY`: giá trị không hợp lý, xét trên chính record cùng dữ liệu tham chiếu tĩnh (feed ACTIVE, đồng hồ nghiệp vụ). `BUSINESS`: phụ thuộc record khác trong warehouse (refund tham chiếu giao dịch gốc). `DEDUP`: cùng key, cùng mốc thời gian nhưng khác nội dung trong một chunk. `LOAD`: lỗi dữ liệu do DB báo khi ghi.
  - Bản cũ hơn của cùng key trong một chunk được **gộp** (giữ bản mới nhất, cộng vào `records_duplicate`), không vào DLQ.
  - Với stage do rule sinh ra, `rule_id` và `error_class` đều bằng mã rule.
- **Ghi vào:** DOC-16, DOC-03 (sửa FR-02.3, FR-02.4), DOC-20.

### DR-71 · Metric thay cho exporter, và chia DR-57 theo chặng — **Chốt**
- **Vấn đề:** (1) DOC-39 không chạy exporter riêng cho Kafka và Postgres (giữ ngân sách RAM), nhưng các alert `GtfsRtFeedStale` (dựa trên DB), `DebeziumWalRetained` (WAL giữ bởi slot trên `pg-source`) và trạng thái connector cần số liệu mà app chưa phát. DOC-08 §13 lại vẽ "postgres exporter". (2) DR-57 đặt `commit_to_emit_seconds` đo tại API, nhưng sự kiện UI chỉ mang `source_record_ts` và `occurred_at`, API không biết thời điểm commit. (3) Tên runbook đã bị các tài liệu trước dùng rải rác (RB-03, RB-05, RB-06, RB-10…12), không theo thứ tự 9 alert của SDD.
- **Quyết định:**
  1. Không thêm exporter trên compose. Số liệu thiếu do app phát, tính từ nguồn sự thật:
     - `api` phát `pti_source_last_event_age_seconds{source}` (truy vấn DB mỗi 15 giây, theo đồng hồ nghiệp vụ). `api` độc lập với `etl-stream` nên alert vẫn bắn khi mọi pod ETL đã chết.
     - `source-simulator` (đã kết nối `pg-source`) phát `pti_source_replication_slot_retained_bytes{slot}` từ `pg_replication_slots` mỗi 30 giây. Trên hệ thật, việc này thuộc về người vận hành DB nguồn; ở đây simulator đóng vai hệ nguồn.
     - `etl-stream` phát `pti_connect_connector_running{connector}` từ Kafka Connect REST (cùng poller với health `source-ticketing`, DOC-20 §6.1).
     - Trên k3d (P7) có thêm exporter của Strimzi và CNPG, nhưng alert vẫn dùng metric của app để hai môi trường có cùng bộ luật.
  2. DR-57 được hiện thực bằng bốn histogram: `pti_etl_kafka_to_commit_seconds` (ETL, chặng 1–2), `pti_ui_commit_to_publish_seconds` (ETL, chặng 3), `pti_api_publish_to_emit_seconds` (API, chặng 4–5, `emit − occurred_at`) và `pti_end_to_end_latency_seconds` (API, `emit − source_record_ts`). "`commit_to_emit_seconds`" của DR-57 và DOC-10 là tổng của chặng 3 và chặng 4–5, không phải một metric riêng. Không đổi hợp đồng sự kiện UI.
  3. Danh mục alert và số runbook chốt ở DOC-28 §6: RB-01…09 và RB-13, 14 cho alert, RB-10…12 cho thao tác (giữ nguyên các số đã được tài liệu trước dùng).
- **Ghi vào:** DOC-28, DOC-08 (§13), DOC-10, DOC-20, DOC-25, DOC-42.

### DR-94 · Máy thực nghiệm và nơi lưu kết quả — **Chốt** (P3)
- **Vấn đề:** DOC-45 §1 cho thực nghiệm chạy trên "máy dev tham chiếu". P3-08 cần khoảng 60 giờ máy chạy nối tiếp (EXP-01 riêng đã khoảng 17 giờ), trong lúc máy dev vẫn phải dùng để code, và máy dev đang cấp cho Docker ít hơn 12 GB mà DOC-45 yêu cầu. DOC-45 §7 cũng commit toàn bộ thư mục kết quả, gồm ledger và chuỗi thời gian của khoảng 150 lần chạy, tức hàng trăm MB trong git.
- **Phương án đã cân nhắc:** (1) máy dev: chiếm máy nhiều ngày, thiếu RAM cho Docker; (2) runner GitHub Actions chạy song song theo matrix: nhanh nhưng mỗi job một máy khác, hiệu năng dao động, không hợp EXP-05 và các số đo thời gian, còn giới hạn 6 giờ mỗi job và artifact hết hạn sau tối đa 90 ngày; (3) một máy riêng cố định.
- **Quyết định:**
  1. Thực nghiệm chính thức (EXP-01…05, và EXP-06…08 về sau) chạy trên một **máy thực nghiệm** dành riêng: 16 GB RAM, CPU không chia sẻ (tối thiểu 4 nhân, khuyến nghị 8), SSD còn trống ≥ 100 GB, chỉ mở SSH. Tài liệu không gắn với một máy cụ thể (máy cá nhân hay máy thuê đều được); mọi lần chạy chính thức của một đợt dùng cùng một máy, cấu hình ghi trong `config.json`. Runner có `--resume` để chuỗi dài ngày chạy tiếp được sau khi bị ngắt.
  2. Kết quả chia hai nhóm. File nhẹ (`config.json`, `summary.json`, `keys_diff.csv.gz`, manifest lưu trữ, `results/report/`) commit vào git. File nặng (`timeseries.csv.gz`, `ledger.csv.gz`, biểu đồ của từng lần chạy) gói thành một file cho mỗi chuỗi (`pti-exp archive`) và đính vào GitHub Release `exp-results`, kèm manifest có SHA-256 trong git; `pti-exp fetch` tải lại và kiểm tra. `pti-exp report` chỉ cần file nhẹ.
  3. Commit kết quả sau khi chuỗi kết thúc, trong một commit chỉ chạm `experiments/results/`; kiểm tra cây làm việc sạch của runner bỏ qua thư mục kết quả.
- **Ghi vào:** DOC-45 §1, §1.2, §2, §7, §7.1, §8; EXP-05 §4; EXP-06; ADR-0025; master plan P3-06, P3-08.

### DR-95 · Chuỗi smoke 30 phút ở P3, đợt chạy đầy đủ dời sau M6 — **Chốt** (P3)
- **Vấn đề:** Đợt chạy đầy đủ EXP-01…05 (≈ 150 lần chạy, khoảng 60 giờ máy, DR-94) cần máy thực nghiệm riêng và chặn P4 thêm vài tuần, trong khi P4–P6 không phụ thuộc số liệu thực nghiệm; chỉ P7 (EXP-07/08 dùng lại runner) và báo cáo cần. Nếu dời hết thực nghiệm thì lỗi mất hoặc trùng dữ liệu chỉ lộ ra sau khi P4–P6 đã dựng trên ETL (kiểm tra tay ở M2 đã lộ một lỗi như vậy, `f25585b`), và runner chưa được thử trên stack thật.
- **Phương án đã cân nhắc:** (1) giữ nguyên P3-08: chậm P4 vài tuần; (2) dời toàn bộ P3-06…09: không có kiểm tra đúng đắn tự động nào tới cuối dự án; (3) viết đủ runner ngay, chạy một chuỗi rút gọn, dời đợt chạy đầy đủ.
- **Quyết định:** phương án 3.
  1. P3 viết đủ runner EXP-01…05 (P3-06, P3-07), thêm profile tham số `smoke` và lệnh `pti-exp smoke` chạy **một chuỗi ≤ 30 phút** trên máy dev: mỗi EXP một lần chạy rút gọn, nối tiếp trên cùng stack, thứ tự EXP-03 → EXP-02 → EXP-01 → EXP-05 → EXP-04. Tham số ở DOC-45 §1.3.
  2. EXP-04 trong chuỗi không `make reset` và không phát tải riêng 30 phút: nó dựng lại cửa sổ dữ liệu của EXP-03 và EXP-02 vừa chạy (có sẵn dữ liệu lỗi và bản gửi lại), sau `make reset-warehouse`. Thứ tự trên để cửa sổ đó đã cũ hơn 10 phút khi replay, đúng ràng buộc `raw-settle` của DR-70 mà không cần đổi cấu hình.
  3. Chuỗi smoke kiểm các tiêu chí đúng đắn có tính nhị phân (mất, trùng, sai giá trị, DLQ nhầm, checksum khớp) và phải đạt; các tiêu chí thống kê (p95 kèm CI, tỷ lệ lần chạy, ngưỡng tải của EXP-05, H3) chỉ ghi lại, không kết luận. Kết quả smoke không vào báo cáo và không commit (`experiments/results/smoke/` bị git-ignore); kết quả lần chạy chốt M3 ghi thành bảng trong master plan như M1, M2.
  4. Đợt chạy đầy đủ theo DOC-45 (số lần lặp §6, máy thực nghiệm DR-94, `archive`/`fetch`, `report`) thành việc P3-10, làm **sau M6 và trước P7-00** (DR-104 dời điểm bắt đầu thành sau MR, tức sau Phase R). Khi đó hệ thống được đo đã có analytics (P4) và triage (P6); `config.json` ghi commit nên báo cáo nói rõ phiên bản được đo. Loạt `etl-only` và `end-to-end` của EXP-05 chạy cùng đợt.
  5. Chuỗi smoke chạy lại ở tiêu chí thoát M4 và M6 để bắt hồi quy của các thay đổi trong đường ETL (analytics sau commit, auto-replay).
  6. `archive` và `fetch` chuyển từ P3-06 sang P3-10, vì chỉ đợt chạy đầy đủ cần.
- **Hệ quả:** M3 không còn "có số liệu EXP-01…05"; tiêu chí "EXP-05 xác định được ngưỡng tải" chuyển sang P3-10. P7-00 phụ thuộc thêm P3-10. EXP-04 C5 của P4-17 (bảng insight) chỉ kiểm ở P3-10, vì chuỗi smoke không so sánh bảng insight. DQ-27 (DR-92) vẫn làm ở P3 cùng runner EXP-04.
- **Ghi vào:** master plan §4.1, §4.2, §4.3, P3, P4-17, M4, M6, P7-00; DOC-45 (README §1, §1.3, §2, §6, §7; EXP-01…05 phần đầu và mục "Kết quả"); README gốc (Roadmap).

### DR-96 · Chi tiết khi làm kịch bản simulator (P3-01) — **Chốt** (P3)
- **Bunching ghép xe theo trạm chung.** DOC-25 §7.2 sắp xe theo `dist`, nhưng `dist` đo trên shape riêng của từng chuyến. Tuyến 18 có nhiều nhánh và chuyến chạy ngắn, nên trên mini feed không có cặp nào được chọn. Leader của một xe là xe có giờ theo lịch muộn nhất mà vẫn sớm hơn nó tại trạm chung đầu tiên từ trạm hiện tại của nó trở đi; headway và `gap` cũng đo tại trạm chung. Khi `gap ≤ target`, follower bị giữ ở đúng `target` thay vì chép `eps` của leader (hai chuyến lấy mẫu đoạn ở hai thời điểm khác nhau).
- **Chọn kiểu gây hỏng theo entity type.** `bad-data` chỉ chọn trong các `kinds` áp dụng được cho message (`out_of_bbox` chỉ VehiclePosition, `delay_out_of_range` chỉ TripUpdate); không loại nào áp dụng thì gửi nguyên. `unknown_schema_version` đổi cả header và `schema_version` trong ledger.
- **Hiệu ứng kéo dài sau lần chạy.** Bản gửi lại đã xếp hàng, hoàn vé còn chờ của `refund-burst` và phần hồi phục của `disruption` vẫn chạy sau khi lần chạy chuyển `COMPLETED`/`STOPPED`; hook tự gỡ khi xong. `ticket-spike` không sinh hoàn vé, hủy, xóa cho giao dịch của nó.
- **Lỗi của hook.** Hook ném lỗi bị gỡ ngay cùng mọi hook của lần chạy đó; lần chạy chuyển `FAILED` ở tick kế tiếp của thread `sim-scenarios` (500 ms), không ngay trên thread của hook, để tránh khóa chéo với `Emitter`.
- **Lệnh `make`.** Thêm `make scenarios` (liệt kê catalog) cạnh `make scenario` và `make scenario-stop` của DOC-38 §4.3.
- **Ghi vào:** DOC-25 §7.2–7.7, §8, §14 (T-14); DOC-38 §4.3.

### DR-97 · Chi tiết khi dựng profile observability (P3-02) — **Chốt** (P3)
- **Phiên bản.** DOC-11 ghi Grafana 12.x và Tempo 2.x, nhưng lúc làm P3-02 đã có Grafana 13.2.3 và Tempo 3.0.3. Theo nguyên tắc dùng bản mới nhất (nhật ký chốt 2026-09-26), dự án dùng hai bản này. Tempo 3 chạy single binary trên local storage như trước; retention 3 ngày đặt bằng `overrides.defaults.compaction.block_retention`. Worker nền của Tempo 3 log `no jobs found` ở mức error vài lần mỗi phút khi không có việc; đây không phải lỗi.
- **Healthcheck.** Image của Loki, Tempo và OTel Collector là distroless, không có shell hay HTTP client, nên không viết được healthcheck trong container. Ba service này chỉ cần `running`; Prometheus scrape cả ba (và mọi service observability khác), nên `up` cho biết chúng còn trả lời không. Grafana chờ Prometheus `healthy`, còn Loki và Tempo chỉ cần đã khởi động.
- **Target của Prometheus.** App của profile `core` là target tĩnh, để container bị dừng hiện ra là `up = 0` (cho alert `TargetDown` ở P3-05). App của profile tùy chọn (`etl-stream-baseline`, từ P6 là `triage-worker`) tìm qua DNS của Docker, nên không thành target chết khi profile tắt.
- **Webhook của Alertmanager** trỏ tới `api` như DOC-28 §6.4 ngay từ P3; cho tới P4 mỗi lần gửi webhook báo lỗi trong log của Alertmanager, email tới Mailpit vẫn đi bình thường.
- **Tài nguyên.** Grafana 13 cần 256 MB thay 192 MB. Tổng giới hạn RAM của profile là khoảng 2 GB.
- **Ghi vào:** DOC-11 §2, DOC-39 §3.7, §4, §5, §6.

### DR-98 · Chi tiết khi làm instrumentation (P3-03) — **Chốt** (P3)
- **Span của một poll.** `StreamChunkTemplate` mở `pti.etl.poll` làm span gốc (không có cha), gắn link tới tối đa `pti.etl.trace.max-links` (20) span producer khác nhau đọc từ header `traceparent`, rồi mở các span con `pti.etl.process`, `pti.etl.write`, `pti.etl.dlq.write` (chỉ khi có dead letter) và `pti.etl.commit` (từ `beforeCommit` tới khi transaction xong). Không làm span `pti.etl.dedup` riêng: câu tra `dedup_registry` đã hiện thành span JDBC `query` dưới `pti.etl.write`. Span JDBC chỉ bật loại `QUERY`, không ghi giá trị tham số.
- **Bỏ observation không cần.** Request vào `/actuator/**` và tác vụ `@Scheduled` không tạo observation, nên không tạo span gốc mỗi lần Prometheus scrape hay mỗi lần poller chạy; timer của chúng cũng mất theo, không dashboard nào dùng.
- **Metric xuất hiện khi nào.** Gauge và counter không label hoặc có label cố định được đăng ký lúc khởi động; counter có label phụ thuộc dữ liệu (`source`, `outcome`, `stage`, `type`…) xuất hiện với sự kiện đầu tiên. Catalog cho O-01/O-02 đánh dấu nhóm sau là `lazy` và nằm trong `src/integrationTest/resources` của từng module, vì test backend chỉ đọc ba vị trí ngoài `backend/` (ADR-0030).
- **`pti_errors_total`.** Lỗi dữ liệu đếm khi dead letter commit (cả stream lẫn batch); lỗi hạ tầng và lỗi fatal đếm khi một poll thất bại; simulator đếm lỗi gửi Kafka là `transient_infra`. `type` là tên lớp rút gọn.
- **Metric mới của `etl-batch`** đọc từ DB mỗi phút: `pti_dlq_open_records` (mọi nguồn × 7 trạng thái chưa đóng), `pti_gtfs_active_feed_info`, `pti_gtfs_active_feed_days_to_expiry`, `pti_gtfs_validation_issues` (theo báo cáo của feed nạp gần nhất). `pti_replay_*` và `pti_dlq_resolved_by_replay_total` ghi khi một replay kết thúc.
- **Key OTLP.** Spring Boot 4.1 dùng `management.opentelemetry.tracing.export.otlp.endpoint`; biến compose đổi thành `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`. Log không xuất qua OTLP (`management.logging.export.otlp.enabled=false`).
- **Đo trên stack thật:** trace `pti.etl.poll` có link tới span `gtfs.vehicle_positions send` của simulator, con là `process`, `write`, `commit` và span JDBC; dòng log trong Loki mang cả `trace_id` và `batch_id`.
- **Ghi vào:** DOC-28 §5.2, §8, §9; DOC-39 §3.2.

### DR-99 · Chi tiết khi làm alert (P3-05) — **Chốt** (P3)
- **Sự kiện đầu tiên của một counter.** Counter có label phụ thuộc dữ liệu xuất hiện lần đầu với giá trị 1 (DR-98), và `increase()` cần hai mẫu nên đọc là 0: `BatchJobFailed` không bắn với job lỗi đầu tiên sau khi `etl-batch` khởi động (đo trên compose). Mọi alert đếm sự kiện rời rạc (`BatchJobFailed`, `FatalErrors`, `ReplayFailed`, `BatchExecutionRecovered`, `GtfsFeedRejected`, `DlqSevereRecords`, `DlqNeedsAttention`, `DlqUpstreamErrorBurst`) dùng `(X unless X offset w) or increase(X[w])`, viết tắt `events(X, w)` ở DOC-28 §6.1. Không bật `created-timestamp-zero-ingestion` của Prometheus vì còn là feature flag và cần scrape protobuf. Test promtool của `BatchJobFailed`, `FatalErrors`, `GtfsFeedRejected` mô tả series mới xuất hiện (`_ _ 1 1`).
- **`CircuitBreakerOpen`.** Khi warehouse chết, breaker đi vòng `open` (30 giây) → `half_open` → `open`, nên `state="open"` với `for: 1m` không bao giờ bắn. Rule dùng `state=~"open|half_open"`.
- **Gauge không chặn scrape.** `pti_source_health` gọi `WarehouseHealthIndicator.ping()` đồng bộ; khi `pg-warehouse` dừng, `/actuator/prometheus` của `etl-stream` treo tới timeout của Hikari, Prometheus báo `TargetDown` thay vì `CircuitBreakerOpen`. Sửa: `isUp()` chỉ đọc trạng thái đã cache (breaker và kết quả ping gần nhất), ping chạy nền không chồng nhau, cache 5 giây. `ConnectorStatus` (gauge `pti_connect_connector_running`) đã có timeout 2 giây và cache 15 giây nên không đổi.
- **Baseline không phải pipeline.** `etl-stream-baseline` (DR-27) cùng `application="etl-stream"` nhưng chỉ có listener GTFS-rt và group riêng: trong chuỗi smoke `ConsumerStopped` bắn cho `ticketing-sales` và `ticketing-sale-points` của baseline, còn `pti:kafka_lag:sum` cộng cả lag của group baseline. Recording rule dựa trên metric của `etl-stream` và alert `ConsumerStopped`, `ConsumerPaused` loại `job="pti-etl-stream-baseline"`.
- **Kết quả O-08 trên compose:** `TargetDown`, `ConnectorDown`, `DlqRateHigh`, `CircuitBreakerOpen`, `BatchJobFailed`, `GtfsFeedRejected`, `DataQualityCheckStale` tới Mailpit và tự hết. Các alert khác chỉ dựa vào `promtool test rules` (60 ca) ở P3; bảng chi tiết ở DOC-42 §4.
- **Ghi vào:** DOC-28 §6.1, §6.3, §9; DOC-42 §4; `deploy/compose/observability/prometheus/rules/`, `tests/`; CI `pr.yml` chạy `promtool check config`, `check rules`, `test rules`.

### DR-100 · DQ-07 khi replay, và chi tiết của runner thực nghiệm (P3-06…08) — **Chốt** (P3; sửa DR-16)
- **Vấn đề:** DR-16 cho replay bỏ hẳn DQ-07, vì dữ liệu cũ luôn lệch xa `businessNow`. Chuỗi smoke đầu tiên (P3-08) cho thấy hệ quả: EXP-04 replay cửa sổ của EXP-03 và ghi 154 message `future_timestamp` (event time +2 giờ) thành fact. Guard event-time sau đó chặn mọi cập nhật hợp lệ của cùng key trong 2 giờ, nên chuỗi kế tiếp trên cùng stack có `wrong_value` ở `fact_trip_update`. EXP-04 bản đầy đủ đã né bằng cách bỏ `future_timestamp` khỏi nhiễu, nhưng lỗi vẫn có trong vận hành thật (RB-11, replay theo khoảng).
- **Quyết định:**
  - DQ-07 chạy cả khi replay, nhưng so event time với **lúc message được publish** (`produced_at` của envelope cộng offset hiện tại) thay vì `businessNow`, và chỉ chiều tương lai: `event_timestamp − (produced_at + offset) > pti.dq.max-clock-skew`. Offset không bao giờ lùi (DOC-45 §1.1), nên đọc `produced_at` bằng offset hiện tại chỉ làm lúc publish muộn hơn thật: record hợp lệ không bao giờ bị loại nhầm, record ở tương lai bị loại trừ khi đồng hồ đã nhảy tới ≥ 1 giờ sau lúc publish (chỉ xảy ra ở dev). Chiều quá khứ vẫn bỏ khi replay. Record `late-delivery` (DQ-07 vì đến muộn) vẫn được auto-replay, vì event time của nó trước lúc publish. `RuleContext` có thêm `clockOffset`, `RealtimeFacts` có thêm `producedAt`.
  - EXP-04 đầy đủ giữ `future_timestamp` trong nhiễu; runner chỉ bỏ dead letter DQ-12 (replay bỏ DQ-12 theo DR-16) khỏi phép so sánh.
  - **EXP-04 trong chuỗi smoke** chỉ so key TripUpdate có toàn bộ lịch sử nằm trong cửa sổ replay: dòng TripUpdate giữ những gì message trước đã quan sát (`is_observed` không quay về false, `scheduled_arrival` dùng coalesce, DR-13), nên replay một cửa sổ không thể tái tạo key đã có message từ trước cửa sổ. Key có message sau cửa sổ cũng bị loại như trước. EXP-04 đầy đủ bắt đầu từ `make reset` nên không bị ảnh hưởng.
  - **Đóng cửa sổ bằng hệ số 0.** Runner dừng phát bằng `PUT /sim/rate` về 0 rồi chờ 3 giây cho tick đang gửi, thay cho `docker pause` simulator (DOC-45 §2.1): pause giữ producer giữa chừng và làm lag Kafka của simulator khó đoán. Runner để simulator ở hệ số 0 cho tới khi đo xong ground truth; lệnh gọi (`smoke`, `run`) đưa về hệ số 1 sau đó. Trước đây runner bật lại tải trong `finally`, trước khi đo, và TripUpdate mới ghi đè đúng dòng mà cửa sổ cần.
  - **DQ-27 bỏ** (đóng DR-92): tính chất "mỗi dòng replay ghi đúng một lần và đúng nội dung" được EXP-04 kiểm chặt hơn bằng fingerprint từng key (C1) và tập dead letter (C2). Muốn DQ-27 đúng thì writer phải đếm số dòng khác nhau thực sự đổi qua mọi chunk của step, tốn bộ nhớ theo số key của replay. ID DQ-27 giữ lại, không dùng.
  - Lag đã commit đọc bằng `docker exec pti-kafka-1 kafka-consumer-groups.sh` (DOC-45 §4.2). Runner ghi `PTI_CLOCK_OFFSET` vào `.env` và tạo lại `source-simulator`, `etl-*` với cùng biến của `make up-exp` khi phải nhảy đồng hồ.
  - `sql/rows/<bảng>.sql` bên cạnh `sql/checksum/<bảng>.sql`: cùng danh sách cột, nhưng trả md5 theo từng business key để EXP-04 chỉ ra key nào khác.
- **Ghi vào:** DR-16, DR-92, DOC-16 §2 (DQ-07, ca test 15–15c), EXP-04 §2 và §9, DOC-45 §1.3, §2.1, master plan P3-06…08.

### DR-101 · Chi tiết khi làm backup và khôi phục (P3-09) — **Chốt** (P3)
- `pg_dump` chạy trong chính container Postgres (`docker compose exec`) qua socket cục bộ và ghi ra host qua stdout, thay cho `docker compose run` trên image: luôn cùng phiên bản với server và không cần mật khẩu. `pg_restore -j 4` cần file nên script chép dump vào container (`docker compose cp`) rồi xóa sau khi khôi phục.
- Manifest đếm số dòng **trước** khi dump; `backup-verify` kiểm số dòng khôi phục ≥ manifest. Đếm sau dump thì dữ liệu live chảy vào trong lúc dump làm số lệch (đo được 50 dòng TU).
- Dump không chứa quyền mức database (`GRANT CONNECT`), và Flyway không chạy lại `R__grants.sql` khi checksum không đổi, nên sau `pg_restore` mọi app bị từ chối kết nối. `make restore-warehouse` xóa dòng `R__grants.sql` trong `flyway_schema_history` trước `db-migrate`, để quyền được áp lại từ đúng một nguồn.
- Thêm `make s3-shell` (AWS CLI với credential admin, biến `$S3` là endpoint) mà DOC-43 §4.5 đã nhắc. `make stop-apps` / `start-apps` chỉ tác động lên app đã có container (`api`, `triage-worker` vào compose ở P4, P6).
- Số đo và kết quả BR-01…06 ở DOC-43 §6, §7.
- **Ghi vào:** DOC-43 §3.1, §3.3, §4.2, §4.5, §6, §7; `deploy/compose/scripts/{backup,backup-verify,restore-warehouse,ensure-partitions}.sh`; `Makefile`.

### DR-102 · Lưu kết quả của chuỗi smoke chốt milestone — **Chốt** (P3; sửa DOC-45 §1.3)
- **Vấn đề:** DOC-45 §1.3 để kết quả chuỗi smoke chỉ nằm trên máy (git-ignore), nên chỉ còn bảng tóm tắt trong master plan; cấu hình và số đo gốc của lần chốt M3 mất khi dọn máy. Thêm nữa, `.gitignore` gốc bỏ qua cả `experiments/results/`, trái với DOC-45 §7 (file nhẹ của đợt chạy đầy đủ phải được commit).
- **Quyết định:** Chuỗi smoke dùng để chốt milestone được commit nguyên vẹn, kể cả `timeseries.csv.gz` (cả chuỗi `p3-08-d` khoảng 30 KB), bằng dòng ngoại lệ `!results/smoke/<series>/` trong `experiments/.gitignore`. Các chuỗi smoke khác vẫn chỉ nằm trên máy. `.gitignore` gốc không còn bỏ qua `experiments/results/`; quy tắc file nặng của §7.1 nằm trong `experiments/.gitignore`.
- **Ghi vào:** DOC-45 §1.3, `.gitignore`, `experiments/.gitignore`, `experiments/results/smoke/p3-08-d/`.

---

## I. Triển khai k3d, demo và báo cáo

### DR-75 · Đọc warehouse khi failover trên k3d — **Chốt**
- **Vấn đề:** `api` đọc qua Pooler `-ro` (chỉ replica). Khi replica duy nhất bị promote hoặc chết, Pooler `-ro` không còn đích và API đọc lỗi trong lúc CNPG dựng lại replica, dù primary vẫn chạy.
- **Quyết định:** URL datasource `reader` trên k3d là JDBC nhiều host `pti-warehouse-pooler-ro, pti-warehouse-pooler-rw` với `targetServerType=preferSecondary` và `hostRecheckSeconds=10`; `maxLifetime` 5 phút để kết nối quay về replica sau khi có lại. Compose giữ một host.
- **Ghi vào:** DOC-40 §5.2, §9.5; DOC-29 §3.1; EXP-08 (F4).

### DR-76 · CI toàn stack và mã test k3d — **Chốt** (điều chỉnh theo DR-56 ngày 2026-09-27)
- **Vấn đề:** E2E trên compose đủ profile (≈ 10 GB) và k3d `lite` (13 GB) cần runner lớn; hai môi trường không vừa một máy cùng lúc. Mã test `K-xx` của k3d trùng với test Debezium `K-01…K-08` (DOC-44 §9.2).
- **Quyết định:** Workflow `full-stack.yml` chạy hằng đêm và bằng tay trên runner `ubuntu-24.04` của GitHub (16 GB với repo public), hai job `e2e-compose` và `k3d-lite` song song trên hai runner, mỗi job dọn đĩa và kiểm tài nguyên trước. Release đòi một lần chạy thành công trên đúng SHA. PR chỉ chạy job không cần stack, cộng `k8s-render`. Test k3d dùng mã `KD-01…KD-15`. Ban đầu (khi repo còn private) workflow này chạy trên self-hosted runner là máy dev; phương án đó đã bỏ.
- **Ghi vào:** DOC-41 §1, §7, §10.3–10.4; DOC-40 §17; DOC-44.

### DR-77 · Demo hai phần — **Chốt**
- **Vấn đề:** Máy 16 GB không chạy compose và k3d cùng lúc (DOC-10 §5); dựng k3d từ đầu mất khoảng 15 phút và cần Internet. Bunching cần 5–10 phút để hình thành.
- **Quyết định:** Phần A (bước 1–6) trên `make up-demo`; phần B (bước 7) trên k3d `lite` được dựng từ hôm trước rồi `make k8s-stop`, lúc demo chỉ `make k8s-start` (offline). Bunching và gián đoạn được gieo trước 12 phút (`make demo-prewarm`) và người trình bày nói rõ điều đó. Đối chiếu "không mất, không trùng" tại chỗ bằng `pti-exp check`.
- **Ghi vào:** DOC-46; DOC-38 §4.5–4.6; DOC-40 §14; DOC-45 §2.

### DR-78 · Lệnh vận hành dùng chung cho k3d — **Chốt**
- **Vấn đề:** Runbook viết cho compose bằng lệnh `make`; viết lại mọi lệnh cho k3d thì hai bản dễ lệch nhau.
- **Quyết định:** Các lệnh `make` đọc và ghi dữ liệu (`psql-*`, `topics`, `tail-*`, `connectors`, `s3-ls`, `sim-*`, `scenario*`, `flag`, `job-*`, `replay`, `gtfs-load`, `ensure-partitions`) nhận `PTI_ENV=k3d` và chạy qua `kubectl`. Runbook chỉ ghi phần khác biệt trong mục "Trên k3d". Thay đổi connector trên k3d đi qua CR `KafkaConnector`, không qua REST.
- **Ghi vào:** DOC-42 §2.1 và từng RB.

### DR-79 · Nguồn số liệu của báo cáo — **Chốt**
- **Vấn đề:** Số liệu chép tay vào báo cáo dễ sai và không truy lại được; SDD §15 yêu cầu trình bày độ nhạy ngưỡng analytics mà chưa có cách làm.
- **Quyết định:** Mọi bảng và biểu đồ thực nghiệm trong báo cáo lấy từ `pti-exp report` trên commit đã tag. Độ nhạy ngưỡng là phân tích mô tả (không phải EXP) bằng `pti-exp sensitivity`: quét một tham số mỗi lần, tính lại analytics trên cùng dữ liệu, so với kịch bản đã gieo.
- **Ghi vào:** DOC-47; DOC-45 §2.

### DR-80 · Cách dựng chunk step của Spring Batch 6 — **Chốt** (sau S-06)
- **Vấn đề:** Spring Batch 6.0 có `ChunkOrientedStep` mới và đánh dấu builder cũ (`SimpleStepBuilder`/`FaultTolerantStepBuilder`) là deprecated for removal. S-06 chạy cùng một job trên cả hai (6.0.5, Postgres thật). Step mới: (1) khi scan, rollback transaction của item bị skip **sau** `onSkipInWrite`, nên dòng DLQ và `writeSkipCount` mất; (2) mỗi transaction của scan lưu vị trí reader của cả chunk, nên process chết giữa scan rồi restart thì các item chưa scan bị bỏ qua (298/499 dòng). Cả hai điểm vi phạm FR-02.5 và NFR-01.
- **Các phương án:** (a) builder cũ, đúng ở mọi test nhưng sẽ bị xóa ở bản major sau; (b) step mới, sai; (c) bỏ skip/scan của Spring Batch, tự scan trong writer bằng savepoint như `StreamChunkTemplate`: đúng, nhưng phải tự làm retry cả chunk và skip ở processor.
- **Quyết định:** (a). Mọi chunk step dựng bằng `chunk(size, tx).faultTolerant()` với `@SuppressWarnings("removal")`. Test B-05, B-18 và luật ArchUnit B-19 (DOC-19 §12) giữ lựa chọn này. Khi nâng lên bản Spring Batch không còn builder cũ: nếu step mới đã sửa (B-05, B-18 xanh trên step mới) thì chuyển sang, nếu chưa thì làm (c). Nên báo hai lỗi này lên issue tracker của Spring Batch kèm app mẫu.
- **Ghi vào:** DOC-19 §4.4, §5, §7.2, §12; ADR-0005; DOC-11.

### DR-81 · Định dạng value và giới hạn bộ nhớ của S3 sink — **Chốt** (sau S-04)
- **Vấn đề:** S-04 chạy Aiven S3 sink 3.4.3 với cấu hình dự kiến của ADR-0012 và phát hiện hai điểm.
  1. **Mất byte.** Với `StringConverter`, byte không phải UTF-8 bị thay bằng U+FFFD. `etl-stream` có nhánh riêng cho byte như vậy (DOC-20 §4.1, test S-08): ghi DLQ `DESERIALIZE`. Simulator hiện không sinh loại lỗi này, nhưng một producer bất kỳ thì có thể, và replay từ raw zone lại thấy một chuỗi UTF-8 hợp lệ. Nếu byte hỏng nằm trong một trường chuỗi, record có thể được ghi vào fact, tức replay cho kết quả khác luồng trực tiếp (vi phạm FR-01.4, EXP-04).
  2. **OOM khi chạy bù.** Mỗi file đang mở giữ một buffer multipart 5 MiB trên heap tới lần commit kế tiếp. SeaweedFS từ chối part nhỏ hơn 5 MiB (`EntityTooSmall`), nên không giảm được buffer. Với `file.max.records=10000`, khi dồn 480 nghìn record rồi cho sink chạy bù, 96–115 file mở cùng lúc và task chết vì `OutOfMemoryError` ở cả heap 512 MB lẫn 768 MB. Task không tự khởi động lại, còn nếu khởi động lại thì gặp đúng tải đó lần nữa.
- **Các phương án:**
  - Định dạng: (a) `StringConverter`, chấp nhận mất byte hỏng; (b) `ByteArrayConverter` + `format.output.fields.value.encoding=base64`.
  - Bộ nhớ: (c) tăng heap lên khoảng 1 GB (đã thử: chạy qua với đỉnh 972/1.024 MiB, không còn dư); (d) giảm `file.max.records` xuống 2.000; (e) part size 1 MiB (bị SeaweedFS từ chối khi file lớn hơn 1 MiB).
- **Quyết định:** (b) và (d).
  - Value lưu base64. Replay giải base64 thành `byte[]` rồi đi qua đúng bước giải mã của `etl-stream` (DOC-22 §4.4).
  - `file.max.records=2000`. Connector yêu cầu commit ngay khi một file đạt ngưỡng, và commit đóng mọi file đang mở. Đo được: tối đa 49 file mở; chạy bù 1,02 triệu record trên 30 partition trong khoảng 15 giây ở `-Xmx512m`; heap đỉnh 468 MiB, container đỉnh 1.009 MiB. Không OOM, không mất, không trùng.
  - `kafka-connect` có `mem_limit` 1.280 MB (k3d: limit 1280Mi), heap giữ 512 MB. Worker đặt `offset.flush.interval.ms=300000` (**đã sửa ở DR-89:** 30 giây, part 1 MiB, vì sink cắt file mỗi 10 giây khi chạy live).
  - **Không tăng `file.max.records`**, cũng không thêm topic nhiều partition vào sink mà không đo lại (test C-10 của DOC-39).
- **Hệ quả:** File raw zone không đọc được bằng mắt, nên thêm `make raw-cat`. Số object nhiều hơn: VehiclePosition khoảng 48.000 object mỗi 7 ngày ở tải nền, nên `pti.replay.max-objects` tăng lên 100.000. Aiven không ghi trường `partition`, nên reader lấy partition từ tên file.
- **Ghi vào:** ADR-0012, DOC-09 §7, DOC-10 §5, DOC-11, DOC-18 §2, DOC-22 §4.3–4.4, DOC-38, DOC-39 §3.4, DOC-40 §6.3.


### DR-82 · MapLibre 6, font bản đồ và CSP — **Chốt** (sau S-05)
- **Vấn đề:** DOC-11 ghi MapLibre 5.x. Lúc spike, bản mới nhất là 6.11.2 (6.0.0 ra ngày 2026-07-22). Bản 6 chỉ phát hành ESM và tải worker từ một file riêng, nên hành vi khác bản 5 ở hai điểm: đường dẫn worker sau khi Vite build, và CSP (bản 5 tạo worker từ blob URL, nên DOC-27 phải mở `worker-src blob:`). Ngoài ra, ADR-0021 định commit font và sprite vào `frontend/public/map/`, nhưng ba font Noto Sans đủ mọi dải glyph nặng 13 MB (771 file).
- **Quyết định:**
  - Dùng **MapLibre GL JS 6.x** (theo DR-53). `@vis.gl/react-maplibre` 8.1.3 (phần maplibre của `react-map-gl`) chấp nhận `maplibre-gl >=4`. Worker được đặt bằng `setWorkerUrl` với import `?worker&url` của Vite (ADR-0021).
  - CSP bỏ `blob:` khỏi `worker-src`, `child-src` và `img-src`. S-05 chạy được dưới CSP chặt hơn này.
  - Font và sprite không commit. `make tiles` tải chúng từ `protomaps/basemaps-assets` (commit pin) vào `deploy/tiles/`; nginx phục vụ cùng chỗ với file PMTiles (`/tiles/`).
  - Style dựng lúc chạy bằng `@protomaps/basemaps` 5.x, không sinh file JSON lúc build. Theme sáng dùng flavor `grayscale`, theme tối dùng `black`; cả hai là nền không màu có sẵn nên không cần tự chỉnh màu.
- **Ghi vào:** ADR-0021, DOC-11, DOC-27 §5.3, DOC-34 §9, DOC-35 §6, DOC-38.

### DR-83 · Không dùng Quartz Scheduler cho lịch job batch — **Chốt**
- **Vấn đề:** Có nên thay `@Scheduled` + ShedLock (DR-24, ADR-0015) bằng Quartz Scheduler (`spring-boot-starter-quartz`, `JobStoreTX` chế độ cluster) cho các lịch của `etl-batch` không? Lý do cân nhắc: Quartz có sẵn cluster, lưu trigger xuống DB, xử lý lượt bị lỡ (misfire) và cho phép lên lịch động.
- **Quyết định:** Không dùng Quartz, giữ nguyên DR-24. Lý do:
  1. **Cơ chế khôi phục của Quartz không chạm tới Spring Batch.** Job được khởi chạy bất đồng bộ qua `JobOperator.start`, nên Quartz job kết thúc ngay khi execution vào executor, và Quartz không biết job Spring Batch chạy bao lâu hay chết lúc nào. Nếu Quartz fire lại (`requestsRecovery`) thì execution cũ vẫn ở `STARTED`, nên lần fire đó nhận `JobExecutionAlreadyRunningException`. Phần khó (execution kẹt, pod zombie) vẫn cần `StaleExecutionRecoverer` và fencing bằng `VERSION`.
  2. **Thêm một nguồn sự thật thứ hai.** Quartz mang theo 11 bảng `QRTZ_*` với máy trạng thái trigger riêng (`WAITING`, `ACQUIRED`, `BLOCKED`, `ERROR`…), phải khớp với `batch.BATCH_*` và `ops.job_request`. UI (`ops_job_run_v`), API và EXP-08 đều dựa trên hai nguồn kia, còn Quartz không cung cấp thêm thông tin nào mà chúng cần.
  3. **Trùng với thứ đã có.** Chỉ một pod kích hoạt: ShedLock. Chạy tay, restart, stop: `job_request` + `JobRequestPoller`. Lưu trạng thái chạy: JobRepository JDBC.
  4. **Bảo đảm về thời gian yếu hơn.** Quartz cluster so thời gian bằng đồng hồ của từng node và yêu cầu các node đồng bộ giờ. ShedLock với `usingDbTime()` so bằng giờ của Postgres.
  5. **Không có nhu cầu mà chỉ Quartz đáp ứng.** Lịch là cron cố định trong cấu hình (`pti.batch.schedule.<job>`). Không có lịch người dùng sửa lúc chạy, calendar loại trừ ngày lễ, hay trigger hẹn giờ chạy một lần. `AUTO_REPLAY_SCHEDULED` chờ theo điều kiện (nguồn UP ≥ 60 giây), không chờ theo giờ.
  6. **Chi phí đổi.** ShedLock 7.10.1 đã được xác minh với Boot 4.1 ở S-06, còn Quartz thì chưa. Đổi sang Quartz phải spike lại và sửa ADR-0015, DOC-19 (§2, §7, §12), glossary và kịch bản EXP-08.
  - PgBouncer **không** phải lý do loại: khóa cluster của Quartz (`SELECT … FOR UPDATE` trên `QRTZ_LOCKS`) nằm trong transaction nên vẫn chạy được ở chế độ transaction pooling.
- **Hệ quả:** Điểm duy nhất Quartz làm tốt hơn là chạy bù lượt lịch bị lỡ khi mọi pod `etl-batch` cùng tắt đúng lúc cron bắn. Hiện đã có bù cho `GtfsStaticLoadJob` (lúc khởi động, khi chưa có feed ACTIVE), `PartitionMaintenanceJob` (lúc khởi động) và `TicketingAnomalyJob` (con trỏ `max-catch-up`). Chưa có bù cho `OtpScorecardJob`, `EtaAggregationJob`, và `GtfsStaticLoadJob` khi đã có feed ACTIVE. Nếu cần bù thì làm được mà không cần Quartz: tham số định danh của lượt tự động đã tất định (`scheduled:<runDate>`, `scheduled:<hour>`), nên một bước chạy lúc khởi động có thể tra `BATCH_JOB_INSTANCE` để biết lượt nào bị lỡ. Bước này chưa được thiết kế trong DR này.
- **Xem lại khi:** cần lịch do người dùng sửa lúc chạy, calendar loại trừ, hoặc nhiều trigger hẹn giờ chạy một lần. Khi đó so thêm với thư viện chỉ dùng một bảng (ví dụ db-scheduler), không mặc định chọn Quartz.
- **Ghi vào:** ADR-0015 (phương án 6).

### DR-84 · Không dùng Apache Spark cho xử lý batch hay streaming — **Chốt**
- **Vấn đề:** Có nên dùng Apache Spark (Structured Streaming cho GTFS-rt và CDC, Spark SQL cho job batch và replay raw zone) thay cho Spring Batch và Spring Kafka (ADR-0002) không?
- **Quyết định:** Không dùng Spark, giữ nguyên ADR-0002. Lý do:
  1. **Khối lượng dữ liệu nhỏ so với bài toán Spark giải.** Tải nền khoảng 143 msg/s, cả ngày khoảng 6,8 triệu message và 2,5 GB; 10× cao điểm (EXP-07) khoảng 1.430 msg/s (DOC-10 §3.1). Một PostgreSQL với batch upsert chịu được mức này. Pipeline cũng không có join hay shuffle phân tán: mỗi message ghi thẳng vào bảng theo business key, còn job tổng hợp (ETA, OTP) là SQL theo tập chạy trong Postgres.
  2. **Đổi mô hình đúng đắn mà đồ án cần chứng minh.** Đóng góp của đồ án là ngữ nghĩa effectively-once, skip/retry/scan theo từng item, DLQ ghi trong transaction của chunk và replay (ADR-0002, ADR-0003, ADR-0004). Structured Streaming có mô hình riêng: offset lưu ở checkpoint của Spark chứ không commit vào consumer group, exactly-once dựa vào sink idempotent, và lỗi ở một record làm hỏng cả micro-batch vì không có skip hay DLQ theo item. Dùng Spark thì phải thiết kế và chứng minh lại từ đầu, còn EXP-01…04 mất baseline của DR-27.
  3. **Replay raw zone phải đi qua cùng pipeline.** EXP-04 dựng lại warehouse từ raw zone bằng chính `ItemProcessor` và `ItemWriter` dùng chung. Nếu replay chạy bằng Spark thì đó là một hiện thực thứ ba của logic biến đổi, và thực nghiệm không còn kiểm chứng pipeline thật.
  4. **Không vừa ngân sách tài nguyên.** Profile core của compose đã dùng khoảng 8 GB trên máy 16 GB (DOC-10 §5). Spark cần driver và executor, mỗi thứ thường từ 1 GB trở lên, và phải có thêm chỗ trên profile `lite` của k3d.
  5. **Khó sống chung với stack.** Spark 4.x chạy trên Scala 2.13 và Jackson 2, còn dự án dùng Spring Boot 4.1, Jackson 3 và Java 25 (ADR-0029). Tương thích Java 25 của Spark chưa được kiểm. Nhúng Spark vào app Spring sẽ xung đột classpath; tách riêng thì thêm một deployment unit với kiểu vận hành khác (submit job, Spark UI, quản lý checkpoint), trái với ADR-0014.
  6. **Không có nhu cầu mà chỉ Spark đáp ứng.** Không có phân tích ad-hoc trên nhiều tháng lịch sử, không có huấn luyện ML, và không có dữ liệu vượt quá một node.
- **Hệ quả:** Không thêm dependency hay container nào. Kafka Streams và Spring Cloud Stream đã bị loại ở ADR-0002 vì cùng lý do (đích ghi là Postgres).
- **Xem lại khi:** cần tính lại chỉ số trên nhiều tháng dữ liệu raw zone mà job SQL theo tập chạy quá lâu, hoặc cần phân tích hay huấn luyện ML trên lịch sử dạng cột. Khi đó thử DuckDB trước (đọc thẳng file raw zone trên S3, chạy trong runner Python của `experiments/`, ADR-0025), chỉ cân nhắc Spark khi dữ liệu vượt quá một máy.
- **Ghi vào:** ADR-0002 (phương án 5), DOC-11 §7.

### DR-85 · Monorepo và bố cục thư mục gốc — **Chốt** · ⚠ lệch SDD gốc
- **Vấn đề:** Phụ lục "Cấu trúc repo" của SDD gốc đặt mọi module Gradle ở gốc repo, ngang hàng với `frontend/`, `connect/`, `chaos/`, `observability/` và `deploy/`. Tài liệu viết sau đó còn thêm `infra/tiles` (DR-82), nên hạ tầng nằm rải ở bốn thư mục gốc, và một chỗ trong DOC-27 ghi nhầm `infra/compose/…`. Chưa tài liệu nào chốt backend, frontend và hạ tầng ở chung một repo hay tách ra.
- **Quyết định:**
  - Một **monorepo** cho backend, frontend, hạ tầng và thực nghiệm. Không tách repo, không dùng công cụ điều phối monorepo (Nx, Turborepo, Bazel).
  - Gốc repo chia theo stack: **`backend/`** chứa toàn bộ Gradle build (settings, wrapper, `gradle/libs.versions.toml`, `build-logic` và 7 module của DR-26); **`frontend/`**; **`deploy/`** là gốc duy nhất cho hạ tầng (compose, k3d, Helm, helmfile, `connect/`, `chaos/`, `tiles/`, `versions.env`, `topics.yaml`); **`experiments/`**. Không có thư mục gốc `infra/`.
  - Lệnh `./gradlew` chạy trong `backend/`. Makefile gọi `backend/gradlew -p backend`, CI đặt `working-directory: backend`.
  - Test backend chỉ đọc file ngoài `backend/` (`deploy/topics.yaml`, `deploy/connect/`, `sample-data/gtfs/`) qua system property `pti.repo-root`.
- **Hệ quả:** Tên module, đường dẫn Gradle và tên image giữ nguyên, chỉ đổi đường dẫn file. Path filter `backend` của CI gồm `backend/**`, `deploy/topics.yaml` và `deploy/connect/**`. Dependabot dùng `/backend` và `/deploy/connect`. SDD gốc giữ nguyên, ADR-0030 thay cho phụ lục "Cấu trúc repo" của nó.
- **Ghi vào:** ADR-0030, DR-26, ADR-0014, ADR-0021, DOC-07 §3, DOC-38, DOC-39 §1, DOC-40 §1, DOC-41 §2 và §8, DOC-44, master plan P1-01, P1-02; đường dẫn file trong mọi DOC.

### DR-86 · Simulator mặc định không phát dữ liệu — **Chốt**
- **Vấn đề:** Trước đây `make up` bật simulator ở hệ số 1,0, nên stack chạy là có khoảng 120 VehiclePosition/giây lúc cao điểm, cộng giao dịch vé qua CDC, dù người dev chỉ đang làm UI, API hay một job batch. Dữ liệu dồn vào warehouse (khoảng 2 GB/ngày partition vehicle position), raw zone và Kafka, làm tốn CPU và ổ đĩa. Muốn dừng thì phải nhớ gọi `make sim-rate GTFS=0 TICKETING=0`.
- **Quyết định:**
  - Container `source-simulator` vẫn thuộc profile `core` và khởi động như cũ (nạp feed, seed 187 điểm bán, readiness `UP`, API `/sim/**` dùng được), nhưng **hệ số khởi đầu của cả hai luồng là 0** trên compose: compose đặt `PTI_SIM_RATE_MULTIPLIER_GTFS_RT` và `PTI_SIM_RATE_MULTIPLIER_TICKETING` bằng `${PTI_SIM_START_RATE:-0}`. Không tách simulator ra profile riêng, không tắt container: Demo control, `/sim/status` và kịch bản vẫn cần process đang sống.
  - Default của app (`pti.sim.rate-multiplier.*` = `1.0`) giữ nguyên, nên k3d (demo phần B, EXP-07, EXP-08) không đổi.
  - Bật và tắt lúc chạy bằng `make sim-start [GTFS=<x>] [TICKETING=<y>]` (mặc định 1 và 1) và `make sim-stop` (0 và 0), đều gọi `PUT /sim/rate`. Trạng thái không lưu lại: simulator restart hay container được tạo lại thì quay về hệ số khởi đầu.
  - Các lệnh cần dữ liệu tự bật: `make up-demo` và `make up-exp` đặt `PTI_SIM_START_RATE=1` (như cách đặt `PTI_DQ_MAX_CLOCK_SKEW`); `make smoke` gọi `sim-start` nếu `rate.gtfsRt = 0` và in ra việc đó. Runner thực nghiệm vẫn tự `PUT /sim/rate` ở bước chuẩn bị như trước.
  - Tạm dừng có chủ đích không phải sự cố: `GtfsRtFeedStale` và `ThroughputDrop` thêm vế `unless on () (max(pti_sim_rate_multiplier{stream="gtfs-rt"}) == 0)`. Simulator chết thì gauge vắng mặt, nên alert vẫn bắn như cũ (cách gây ra của P3-05 không đổi). `StaleBanner` và `GET /system/freshness` vẫn báo `stale`, vì đúng là không có dữ liệu mới.
- **Hệ quả:** Tiêu chí M1 và P1-14, P2-20 chuyển thành `make up && make sim-start`. NFR-07 và G9 không đổi, vì `make smoke` tự bật simulator. Kịch bản bắt đầu khi hệ số của luồng liên quan bằng 0 vẫn được nhận nhưng không có tác dụng nhìn thấy được, cho tới khi bật lại.
- **Ghi vào:** DOC-25 §6.5, §10, DOC-28 §6.3, DOC-29 §3.2, DOC-38 §3, §4, §6.2, §8, DOC-39 §3.2, §5, §8, RB-06, master plan P1-14, P2-20, M1.

### DR-87 · Demo console: trang web kích hoạt kịch bản và hiển thị trạng thái hệ thống — **Chốt**
- **Vấn đề:** Kịch bản demo (DOC-46) điều khiển bằng lệnh `make demo-*` và `kubectl` trong terminal, rồi quan sát qua Grafana. Khán giả không thấy trực tiếp những gì đang xảy ra ở tầng hạ tầng: container bị kill rồi sống lại (bước 5), pod được tạo thêm (7a), broker mất và được tạo lại (7b), primary Postgres đổi (7c). Lệnh watch ở bước 7 chỉ lọc `etl-stream` và `api`, nên không thấy pod broker hay pod Postgres. Trạng thái `up{…}` của Prometheus trễ theo chu kỳ scrape, nên dễ không kịp thấy một container chỉ chết 5 giây.
- **Các phương án:** (1) dashboard Grafana dạng Canvas vẽ topology: đẹp, không tốn thêm tài nguyên, nhưng không kích hoạt được hành động và vẫn trễ theo scrape; (2) UI Kubernetes như Headlamp hoặc k9s: chỉ dùng được cho k3d và mang dáng công cụ kỹ thuật; (3) thêm trang vào sản phẩm: API phải giữ docker socket hoặc quyền cluster, trái với ranh giới của ADR-0025; (4) **một web console riêng trong `experiments/`**, dùng lại adapter của runner.
- **Quyết định:** Chọn phương án 4 (thiết kế ở DOC-48):
  - Backend là lệnh `pti-exp console` (FastAPI) trong dự án uv `experiments/`. Nó dùng lại `env/compose.py`, `env/k3d.py`, `sim.py` và `check` của runner, và chạy trên host, chỉ bind `127.0.0.1:8095`.
  - Frontend là entry Vite thứ hai trong `frontend/` (`frontend/console/`), dùng chung design system. Image `pti-frontend` không chứa entry này.
  - Hành động nằm trong một danh mục cố định lấy theo DOC-46, không nhận lệnh tùy ý. Trạng thái container và pod lấy từ docker events và watch của `kubectl` (tức thì). Chỉ số lấy từ Prometheus.
  - Màn Demo control (`/ops/demo`, FR-11.7, UC-16) **giữ nguyên** trong sản phẩm, cho kịch bản simulator với tham số tùy ý. Console chỉ có các hành động của kịch bản demo, cộng thao tác hạ tầng, và liên kết sang Demo control khi cần kịch bản khác.
  - Lệnh `make demo-*` vẫn là đường chính thức và là phương án dự phòng: mỗi nút của console có lệnh `make` tương đương (DOC-48 §7).
- **Hệ quả:** Thêm hai thư viện Python (`fastapi`, `uvicorn`), một cổng `8095` trên host, và rủi ro chấp nhận AR-13 (console không xác thực, được chặn CSRF và DNS rebinding bằng header riêng, kiểm `Origin` và `Host`). Việc P8-08 là **mục cắt đầu tiên** trong thứ tự cắt giảm: không có console thì demo vẫn chạy theo DOC-46 bằng terminal và Grafana.
- **Ghi vào:** DOC-48; DOC-46 §1.3, §3–§4, §6, §9; DOC-38 §4.6, §5; DOC-27 §6, §11, §12; DOC-34 §9.1; DOC-44 §13; DOC-45 §2; DOC-36 Demo control §1; master plan §3.1, §3.2, §4.3, P8-08.

### DR-88 · Hướng thị giác "Wayfinding" và bố cục màn hình theo prototype — **Chốt**
- **Vấn đề:** DOC-35 chốt token theo bảng màu Tailwind (nhấn `sky-700`, Inter, bo 4–8 px) và khung có thanh trên cộng thanh bên riêng cho ops, nhưng chưa có bản hình ảnh nào. Prototype high-fidelity đầu tiên dựng trên Claude Design bám sát tài liệu và bị Owner đánh giá là nhạt, tương phản gắt, nhiều khoảng trắng, bố cục quá đơn giản. Owner yêu cầu thiết kế lại từ đầu, không bị ràng buộc bởi tài liệu, rồi duyệt bản "Wayfinding" (canvas "PTI Screen Designs" và artifact "PTI Design System"). Cần đưa bản này ngược vào tài liệu mà không phá hợp đồng API (DOC-32) và mô hình domain.
- **Các phương án:** (1) giữ tài liệu, prototype chỉ để tham khảo: hai nguồn sự thật lệch nhau ngay từ P5; (2) chép nguyên prototype, kể cả số liệu và các khối minh họa: nhiều khối không có dữ liệu thật, buộc thêm endpoint hoặc bịa nghiệp vụ; (3) **lấy hệ thị giác, khung, bố cục và microcopy của prototype; dữ liệu vẫn theo DOC-32; khối không có dữ liệu thì thay bằng dữ liệu sẵn có hoặc bỏ, và ghi rõ**.
- **Quyết định:** Chọn phương án 3.
  - **Token (DOC-35 §2–4):** Geist và Geist Mono tự host; màu là mã hex riêng (canvas `#F4F5F7`, bề mặt trắng, một màu nhấn indigo `#4F57D9`), bảy tông dạng nền nhạt, thang độ trễ, màu bunching riêng, thang heat, chart, màu bản đồ nền; bo 6/9/12/16 px; bốn mức bóng. Ba motif lấy từ biển báo giao thông: route shield, line-and-stop strip, số lớn kiểu bảng giờ tàu. Dark là theme hạng nhất (demo console dùng mặc định).
  - **Khung (DOC-34 §4):** một sidebar 232 px cho mọi trang desktop, ba nhóm "Network", "Analytics", "Operations", ô tìm kiếm `⌘K` (trang, tuyến, trạm), thẻ "Live feed", tài khoản ở chân. Thay thanh trên và thanh bên riêng của ops. Mobile giữ thanh tab dưới.
  - **IA:** thêm màn **Overview** `/overview` cho viewer, là trang mặc định của `/` khi đã đăng nhập (FR-11.8, F-UI-09, P5-16). "Jobs" đổi nhãn thành **"Pipeline"**; URL giữ `/ops/jobs` vì `link` của alert và runbook trỏ tới đó. Màn Demo control đổi tiêu đề thành "Demo scenarios".
  - **Bố cục:** nguyên tắc P-11 mới: màn xử lý bản ghi liên tiếp (Alerts, Dead letters, Ticketing) dùng danh sách + khung chi tiết; Pipeline, Replay, Scorecard (drawer tóm tắt tuyến, `route=`) và Demo dùng drawer. Tab của Dead letters đổi thành `review`, `confirm`, `closed`, `actions`. Live map tràn hết vùng nội dung với panel nổi, thêm chế độ tô màu xe `colour` (độ trễ, tuyến, độ đông), chú giải có số đếm, "Trip progress" dạng line-and-stop; mobile có "Nearby stops" (E-06 `bbox`). Demo console chia ba cột: runbook, topology + timeline, cột chỉ số (DOC-48 §4.1).
  - **Không đưa vào** (prototype chỉ minh họa, DOC-32 không có dữ liệu hay thao tác tương ứng): KPI "Prediction error" và số xe theo lịch ở Overview; loại alert "Prediction quality"; "Riders affected"; nhiều phương án phục hồi cùng nút "Send to dispatch"/"Notify riders" (API chỉ có một gợi ý và phản hồi accept/dismiss, E-17/E-18); ghi chú trong alert; biểu đồ xu hướng 60 phút của episode; heatmap toàn mạng và preset "Quarter" ở Scorecard (E-03 theo tuyến, E-14 tối đa 31 ngày); lag Kafka theo topic trên màn Pipeline (xem ở Grafana và demo console); AI đề xuất payload sửa và "Review batch" ở Dead letters (triage chỉ phân loại và định tuyến, DOC-24); "Dry run" và xếp hàng replay (FR-12.3 từ chối replay trùng nguồn); biểu đồ doanh số so với khoảng kỳ vọng, "Mark as expected", "Lock refunds" ở Ticketing; số quyết định và tỉ lệ chấp nhận của AI, "Audit log", tải file GTFS lên ở Controls (thay bằng ô URL chạy `GtfsStaticLoadJob`); "Remind me" ở mobile; tiện ích trạm ngoài xe lăn. Tên sai trong dữ liệu mẫu (Flink, MinIO, `ReplayRawRangeJob`, job không có trong DOC-19) không được chép; nhãn chú giải độ trễ theo ngưỡng DOC-35 §3.4, không theo prototype.
- **Hệ quả:** Không đổi API, không thêm endpoint. Đổi package font (DOC-11). Thêm test DS-11, UX-11 và các AC mới trong `screens/*`. Prototype và artifact design system là tham chiếu hình ảnh nằm ngoài repo; khi lệch nhau, tài liệu thắng. Màn Overview gọi thêm E-05, E-14 (hai lần), E-31, E-41; sidebar của viewer gọi thêm E-31, E-51, E-15 để hiện số đếm (60 s một lần).
- **Ghi vào:** DOC-35 (§1–§11), DOC-34 (§2, §3, §4, §5.2, §6, §7, §9.1, §11), DOC-37 (§2, §6), DOC-36 (mọi file trong `screens/`, thêm `overview.md`), DOC-48 §4, DOC-46 §1.3, §4–§6, DOC-03 FR-11.8, DOC-05 F-UI-04, F-UI-08, F-UI-09, DOC-11, master plan P5-14, P5-16.
### DR-89 · S3 sink: part 1 MiB và commit 30 giây — **Chốt** (sửa DR-81)
- **Vấn đề:** Khi đưa simulator vào compose (P1-14), task `pti-raw-sink` chết vì `OutOfMemoryError` sau vài phút có traffic live, dù backlog chỉ vài chục nghìn record. Đọc mã nguồn Aiven 3.4.3 (`S3SinkTask`): `put()` ghi dữ liệu đang đệm ra S3 khi đủ 10 giây (`S3_WRITE_INTERVAL_MS`, hằng số, không cấu hình được) hoặc 60 MiB. Mỗi lần như vậy, mỗi partition có dữ liệu bắt đầu một file mới (`start_offset` mới) và một writer mới giữ buffer bằng `aws.s3.part.size.bytes` trên heap, tới lần commit kế tiếp. Với commit 5 phút và khoảng 31 partition có traffic: tới 30 × 31 ≈ 900 writer × 5 MiB. S-04 chỉ đo backlog tĩnh, nơi ngưỡng 2.000 record gọi commit liên tục, nên không thấy. Bản 3.4.2 thì ngược lại: đệm mọi record tới lần commit rồi ghi từng file một, không có ngưỡng gọi commit, nên RAM không bị chặn khi chạy bù.
- **Các phương án:** (a) quay về 3.4.2: live ổn, nhưng chạy bù sau một lần sink dừng lâu thì đệm cả backlog trên heap, OOM rồi kẹt; (b) giữ 3.4.3, giảm part size và chu kỳ commit; (c) đổi sang Confluent S3 sink: đổi license, làm lại S-04.
- **Quyết định:** (b).
  - `aws.s3.part.size.bytes=1048576`. Một file có tối đa 2.000 record; đo trên stack thật: TripUpdate khoảng 200 byte/record sau gzip (2.000 record ≈ 400 KB), VehiclePosition khoảng 75 byte. File luôn nằm trong một part, nên SeaweedFS không báo `EntityTooSmall` (lỗi ở DR-81 (e) chỉ xảy ra khi file lớn hơn part).
  - Worker `offset.flush.interval.ms=30000`. Số writer mở tối đa khoảng 3 × số partition có traffic × 1 MiB ≈ 100 MiB. Offset nguồn của Debezium cũng được lưu mỗi 30 giây (phát lại ít hơn khi Connect chết).
  - Heap giữ 512 MB, `file.max.records` giữ 2.000.
  - Đo trên compose (2026-09-28): 6 phút live ở hệ số 1 và 10, rồi dồn 329 nghìn record (sink pause, simulator hệ số 20 trong 150 giây) và resume: task không lỗi, chạy bù xong trong khoảng 7 giây, heap dưới 500 MiB (phần lớn là rác chưa GC, sau GC khoảng 110–200 MiB).
- **Hệ quả:**
  - File raw zone đóng **mỗi 10 giây** trên mỗi partition có traffic (hoặc khi đạt 2.000 record), không còn mỗi 5 phút. VehiclePosition ở tải nền: khoảng 104.000 object mỗi ngày (12 partition × 8.640), tức khoảng 730.000 mỗi 7 ngày, gấp khoảng 15 lần ước lượng của DR-81.
  - **Mở (đã đóng ở DR-91):** replay (DOC-22 §4.3) lưu danh sách object trong `ExecutionContext` và giới hạn `pti.replay.max-objects` 100.000, nên một replay VehiclePosition dài hơn khoảng 1 ngày sẽ bị từ chối. Phải xem lại khi làm replay (P3): nâng giới hạn và không lưu cả danh sách trong context (liệt kê lại theo giờ khi restart), hoặc gộp file nhỏ bằng một job compact. Nếu Aiven cho cấu hình chu kỳ ghi (hiện là hằng số), cân nhắc quay lại file 5 phút.
  - Test C-10 của DOC-39 đo thêm pha live ở hệ số 10 sau khi chạy bù.
- **Ghi vào:** DR-81, ADR-0012, DOC-09 §7, DOC-39 §3.4, DOC-40 (values Connect), DOC-22 §4.3, `deploy/connect/connectors/pti-raw-sink.json`, `deploy/compose/compose.yaml`.

---

## Tổng hợp theo mức ảnh hưởng

| Mức | Mục | Lý do cần chốt sớm |
| --- | --- | --- |
| Chặn P1 | DR-01, 02, 03, 04, 05, 06, 09, 10, 11, 26, 53, 64, 66, 67, 68, 81, 85, 86, 89 | Quyết định schema, contract và cấu trúc repo |
| Chặn P2 | DR-07, 13, 14, 15, 16, 18, 21, 22, 23, 24, 25, 62, 63, 65, 69, 70, 80, 83, 84 | Quyết định ngữ nghĩa đúng đắn của pipeline |
| Chặn P3 | DR-27, 28, 50, 57, 58, 71 | Thiếu thì không đo được thực nghiệm |
| Chặn P4 | DR-12, 17, 19, 20, 29–35, 39–45, 103, 104 | Analytics và API |
| Chặn P5 | DR-46–49, 82, 88 | Frontend |
| Chặn P6 | DR-36, 37, 38, 60, 72, 73, 74 | AI triage |
| Chặn P7 | DR-54, 55, 56, 75, 76 | Kubernetes |
| Chặn P8 | DR-77, 78, 79, 87 | Demo, demo console, runbook k3d, báo cáo |
