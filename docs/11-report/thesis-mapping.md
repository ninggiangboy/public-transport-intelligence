# Ánh xạ báo cáo đồ án

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-47
> Phụ thuộc: SDD §2, §13.2, §15; [DOC-01](../01-product/vision-and-scope.md), [DOC-03](../01-product/requirements.md), [DOC-45](../10-testing/experiments/README.md), EXP-01…08, [DOC-46](../10-testing/demo-script.md), mọi DOC được dẫn trong §2
> Người dùng chính: người viết báo cáo, P8-06

Báo cáo đồ án được viết bằng tiếng Việt, ngoài repo, theo mẫu trình bày của trường. Tài liệu này quy định:

- báo cáo gồm những chương nào;
- mỗi mục lấy nội dung, hình và bảng từ tài liệu hay kết quả nào;
- luận điểm nào được chứng minh bằng bằng chứng nào.

Nhờ vậy báo cáo không phải nghĩ lại thiết kế, và mọi con số đều truy về được `experiments/results/`.

Nếu mẫu của trường đặt tên hoặc đánh số chương khác, chỉ đổi cột "Chương/mục"; cột nguồn giữ nguyên.

## 1. Nguyên tắc

1. **Tóm tắt, không chép.** Báo cáo trình bày lập luận và kết quả; chi tiết hiện thực (tên bảng, cấu hình, endpoint) chỉ nêu khi cần cho lập luận, còn lại dẫn tới phụ lục hoặc repo.
2. **Mọi số liệu thực nghiệm lấy từ `experiments/results/report/`** do `uv run pti-exp report` sinh (DOC-45 §7), trên commit đã tag cho bảo vệ. Không gõ tay số liệu; bảng trong báo cáo chép từ file Markdown sinh ra, kèm `git_sha` và ngày chạy ở chú thích.
3. **Kết quả được báo cáo đầy đủ**, kể cả giả thuyết không đạt và lần chạy `invalid` (DOC-45 §8, "Người chạy chọn lọc kết quả"). Tiêu chí không đạt thì báo cáo nguyên nhân và mức ảnh hưởng, không sửa tiêu chí sau khi đã có số liệu.
4. **Tên giao diện giữ nguyên tiếng Anh** như trên UI, đặt trong ngoặc kép (ví dụ "Needs manual review"), để khớp với ảnh chụp màn hình.
5. **Thuật ngữ** theo DOC-06. Lần đầu dùng một thuật ngữ tiếng Anh thì kèm giải thích tiếng Việt; bảng thuật ngữ đặt ở phụ lục.
6. **Mỗi hình và bảng có nguồn**: sơ đồ Mermaid (tài liệu và mục), biểu đồ (`charts/*.png` của EXP nào), hoặc ảnh chụp màn hình (§5.3).

## 2. Khung chương

| Chương/mục | Nội dung | Nguồn | Hình, bảng chính |
| --- | --- | --- | --- |
| **Mở đầu** | Bối cảnh, vấn đề, câu hỏi nghiên cứu, đóng góp, phạm vi, bố cục báo cáo | SDD §1–2; DOC-01 §1–6 | Bảng đóng góp theo module (SDD §2.2) |
| **1. Tổng quan** | | | |
| 1.1 Dữ liệu giao thông công cộng | GTFS static, GTFS-realtime, dữ liệu vé; đặc điểm không đồng nhất (batch và gần thời gian thực) | DOC-13 §1–4; SDD §5.1 | Bảng nguồn dữ liệu |
| 1.2 Bài toán ETL tin cậy | At-least-once, exactly-once, effectively-once; mất và trùng khi lỗi; DLQ; replay | ADR-0003, ADR-0004, DOC-06 | Hình: các điểm lỗi trong một chunk (DOC-19 §7.1) |
| 1.3 Các hướng tiếp cận liên quan | Kafka transactions, idempotent sink, outbox, stream processor (Flink, Kafka Streams) và lý do chọn Spring Batch và Spring Kafka | ADR-0002, ADR-0003, ADR-0026 (mục "Các phương án") | Bảng so sánh phương án |
| 1.4 Phân tích vận hành xe buýt | Bunching, gián đoạn, OTP, ETA lịch sử | DOC-23 §5–8; DR-30…33 | — |
| 1.5 Mô hình quyết định có cấu trúc | Jev, câu trả lời có xác suất, vì sao ngưỡng do code giữ | DOC-24 §1–3; ADR-0018, ADR-0019 | — |
| **2. Phân tích yêu cầu** | | | |
| 2.1 Người dùng | Persona, hành trình | DOC-02 | Bảng persona |
| 2.2 Yêu cầu chức năng | FR theo nhóm, ưu tiên | DOC-03 §2; DOC-05 | Bảng FR rút gọn (chỉ tên và ưu tiên; chi tiết ở phụ lục) |
| 2.3 Yêu cầu phi chức năng | NFR-01…13, cách đo, thực nghiệm kiểm chứng | DOC-03 §3 | Bảng NFR (giữ nguyên cột "Kiểm chứng bằng") |
| 2.4 Use case | Sơ đồ use case và 6 use case chính (UC-01, UC-04, UC-08, UC-10, UC-13, UC-18) | DOC-04 | Hình sơ đồ use case |
| **3. Kiến trúc** | | | |
| 3.1 Bối cảnh và container | C4 mức 1–2, đơn vị triển khai | DOC-07; ADR-0014 | Hình C4 context, container |
| 3.2 Luồng dữ liệu | Streaming, batch, CDC, raw zone, replay | DOC-08 | Hình luồng dữ liệu tổng thể |
| 3.3 Hợp đồng message | Envelope, khóa partition, phiên bản schema | DOC-09; ADR-0007, ADR-0008 | Bảng topic |
| 3.4 Thuộc tính chất lượng | Ngân sách độ trễ, tài nguyên, kết nối DB | DOC-10 | Bảng ngân sách độ trễ (DR-57) |
| 3.5 Công nghệ | Stack và phiên bản | DOC-11; ADR-0029 | Bảng stack |
| 3.6 Quyết định kiến trúc | Tóm tắt ADR quan trọng: 0002–0006, 0010, 0012, 0013, 0018, 0019, 0028 | DOC-12 | Bảng ADR (tiêu đề, quyết định một dòng) |
| **4. Thiết kế dữ liệu** | | | |
| 4.1 Warehouse | Star schema, business key, partition | DOC-14; ADR-0011 | Hình ERD warehouse |
| 4.2 Dữ liệu vận hành và insight | `etl_batch`, `etl_stream_batch`, dead letter, replay, episode insight | DOC-15 | Hình ERD vận hành |
| 4.3 Chất lượng dữ liệu | Rule DQ theo tầng, cách phân loại lỗi | DOC-16; ADR-0006 | Bảng rule (nhóm) |
| 4.4 Vòng đời và bảo mật dữ liệu | Retention, raw zone, role tối thiểu | DOC-18; DOC-17 | Bảng role |
| **5. Thiết kế ETL (đóng góp chính)** | | | |
| 5.1 Mô hình chunk dùng chung | `StreamChunkTemplate` và Spring Batch dùng chung processor, writer, phân loại lỗi | DOC-19 §1–6; SDD §6.3 | Hình kiến trúc chunk hai chế độ |
| 5.2 Effectively-once | Upsert theo business key, commit offset sau transaction, fencing | DOC-19 §7; DOC-20 §3; ADR-0003, ADR-0004, ADR-0015 | Hình sequence một chunk streaming (DOC-20) |
| 5.3 Cô lập lỗi | Scan fallback, skip policy, DLQ trong cùng transaction | DOC-19 §5; ADR-0005; DOC-22 §1 | Hình sequence chunk lỗi |
| 5.4 Khôi phục | Restart, execution mồ côi, circuit breaker, pause/resume | DOC-19 §7.2; DOC-20 §5; DOC-30 | Hình máy trạng thái listener |
| 5.5 GTFS static và phiên bản feed | Nạp, kiểm tra, kích hoạt | DOC-21; ADR-0009 | — |
| 5.6 DLQ và replay | Vòng đời dead letter, replay từng record và theo khoảng từ raw zone | DOC-22; ADR-0012, ADR-0013 | Hình máy trạng thái dead letter |
| **6. Analytics và AI triage** | | | |
| 6.1 Nguyên tắc event time | Lưới đánh giá, watermark, tính lại tất định | DOC-23 §2; ADR-0010 | — |
| 6.2 Bunching, gián đoạn, ETA, OTP, ticketing | Định nghĩa, công thức, máy trạng thái | DOC-23 §5–9 | Hình máy trạng thái cặp bunching |
| 6.3 Độ nhạy theo ngưỡng | §6 của tài liệu này | §6 | Hình đường cong độ nhạy |
| 6.4 AI triage | Bốn use case, bảng quyết định DLQ, luật chặn cuối, fallback | DOC-24 §5–11 | Hình luồng auto-replay (DOC-24 §6.4); bảng quyết định |
| **7. Hiện thực và giao diện** | | | |
| 7.1 API và thời gian thực | Quy ước REST, SSE, bảo mật | DOC-31, DOC-32, DOC-33, DOC-26, DOC-27; ADR-0016, ADR-0017 | Bảng nhóm endpoint |
| 7.2 Giao diện | IA, bốn màn hình đại diện (Live map, Alerts, Dead letters, Jobs) | DOC-34, DOC-36 | Ảnh chụp màn hình (§5.3) |
| 7.3 Simulator và ground truth | Sinh dữ liệu từ lịch thật, ledger, kịch bản | DOC-25; DR-28 | Hình simulator và ledger |
| **8. Triển khai và vận hành** | | | |
| 8.1 Docker Compose | Profile, tài nguyên | DOC-39 | — |
| 8.2 Kubernetes (k3d) | Operators, KEDA, CNPG, Strimzi, Chaos Mesh | DOC-40; ADR-0028 | Hình triển khai k3d |
| 8.3 Observability và cảnh báo | Metric, log, trace, alert, runbook | DOC-28; DOC-42; ADR-0022, ADR-0023 | Ảnh dashboard `pti-overview` |
| 8.4 CI/CD và kiểm thử tự động | Tháp kiểm thử, workflow | DOC-41; DOC-44 | Bảng tháp kiểm thử và số test |
| **9. Thực nghiệm và đánh giá** | §4 | DOC-45; EXP-01…08 | §4 |
| **10. Kết luận** | Trả lời câu hỏi nghiên cứu, đóng góp, giới hạn, hướng phát triển | §3, §7 | Bảng luận điểm và bằng chứng (§3) |
| **Phụ lục** | §8 | | |

## 3. Câu hỏi nghiên cứu, luận điểm và bằng chứng

Câu hỏi nghiên cứu (SDD §2.1) được tách thành các luận điểm. Chương 10 trả lời từng luận điểm theo bảng này; mỗi luận điểm chỉ được coi là chứng minh khi tiêu chí đạt của thực nghiệm tương ứng đạt.

| # | Luận điểm | Cơ chế | Bằng chứng | Kết luận khi đạt |
| --- | --- | --- | --- | --- |
| L1 | Pipeline không mất và không trùng dữ liệu khi process chết đột ngột | Commit offset sau transaction; upsert theo business key; fencing batch | EXP-01 (so với baseline commit trước ghi); NFR-01 | "Mất = 0, trùng = 0" trên mọi lần chạy hợp lệ, trong khi baseline có mất hoặc trùng |
| L2 | Message giao lại không tạo bản trùng | Upsert idempotent; bảo vệ ghi đè bằng dữ liệu cũ | EXP-02; NFR-01 | `duplicates = 0`, `wrong_value = 0` |
| L3 | Record lỗi không kéo theo record hợp lệ | Scan fallback, skip policy, DLQ cùng transaction | EXP-03 (so với baseline fail cả batch); NFR-02 | 100% record hợp lệ được nạp ở 1%, 5%, 20% lỗi |
| L4 | Warehouse tái tạo được hoàn toàn từ raw zone | Raw zone bất biến, replay theo khoảng, tính lại tất định | EXP-04 | Checksum theo bảng khớp (DR-58) |
| L5 | Khôi phục tự động và nhanh | Restart từ offset đã commit; dọn execution mồ côi | EXP-01 `recovery_seconds`; NFR-04 | p95 < 60 giây |
| L6 | Độ trễ đầu-cuối đáp ứng thời gian thực ở tải nền và ngưỡng tải được xác định | Chunk nhỏ, pool kết nối có kiểm soát | EXP-05; NFR-03 | p95 < 10 giây ở tải nền; ngưỡng tải tối đa được báo cáo |
| L7 | Hệ thống mở rộng theo tải mà vẫn đúng | KEDA theo lag, luật chặn khi DB là nút thắt | EXP-07; NFR-08 | Đạt NFR-03 tới ×10 trong giới hạn máy; không mất, không trùng |
| L8 | Mỗi sự cố hạ tầng đơn lẻ không làm mất hay trùng dữ liệu | RF 3 và min ISR 2, failover CNPG, circuit breaker, Jev tắt được | EXP-08; NFR-09 | C1–C9 của EXP-08 |
| L9 | Tầng AI giảm việc tay mà không đánh đổi độ an toàn | Ngưỡng do code giữ, luật chặn cuối, fallback `fake` và `disabled` | EXP-06 (tùy chọn); DOC-24 TG-xx | Tỷ lệ tự động hóa ở precision ≥ 95% được báo cáo, không đặt kỳ vọng trước |
| L10 | Dữ liệu sạch cho insight dùng được | Analytics theo event time, tính lại tất định | §6 (độ nhạy); demo bước 2–3 (DOC-46) | Báo cáo mô tả, không có tiêu chí đạt |

Câu trả lời cho câu hỏi nghiên cứu là tổng hợp L1–L5 (tính đúng và tự phục hồi), L6–L8 (giữ được khi tải và khi hạ tầng lỗi). L9 và L10 là giá trị gia tăng, không phải điều kiện của câu trả lời.

## 4. Chương thực nghiệm

Mỗi thực nghiệm là một mục trong chương 9, cùng một cấu trúc:

1. **Mục tiêu và giả thuyết:** chép giả thuyết từ mục 1 của file EXP.
2. **Thiết kế:** biến, baseline, môi trường, số lần lặp; tóm tắt các bước (một đoạn, dẫn tới phụ lục).
3. **Kết quả:** bảng theo mẫu ở mục "Mẫu bảng kết quả" của file EXP, lấy từ `experiments/results/report/<EXP>.md`; biểu đồ chính.
4. **Kiểm định:** kết quả phân tích theo mục "Phân tích" của file EXP (CI, kiểm định cặp, DOC-45 §6).
5. **Thảo luận:** giả thuyết đạt hay không; giải thích bất thường; lần chạy `invalid` và lý do.
6. **Mối đe dọa:** chép bảng "Mối đe dọa" của EXP, cộng DOC-45 §8.

| Mục | Bảng chính | Biểu đồ chính (trong `charts/` của report) | Điểm phải thảo luận |
| --- | --- | --- | --- |
| 9.1 Môi trường và phương pháp chung | Cấu hình máy, phiên bản, ground truth, công thức chỉ số (DOC-45 §1, §3, §4) | — | Vì sao ledger là ground truth; giới hạn máy đơn |
| 9.2 EXP-01 Dừng đột ngột | Theo biến thể: mất/sai/trùng normal và baseline; `recovery_seconds` | Phân phối `recovery_seconds` theo biến thể; lag quanh thời điểm kill (một lần chạy điển hình) | Vì sao SIGKILL không để lại dòng `FAILED` (DOC-15 §4.2); H3 (độ nhạy của phép đo) |
| 9.3 EXP-02 Gửi lại | Trùng theo tỷ lệ gửi lại | Số bản giao lại so với số bản ghi | Vai trò của business key |
| 9.4 EXP-03 Cô lập lỗi | Tỷ lệ nạp hợp lệ theo tỷ lệ lỗi, normal và baseline | Tỷ lệ nạp theo tỷ lệ lỗi; thông lượng khi có lỗi | Chi phí của scan fallback |
| 9.5 EXP-04 Replay | Số dòng và checksum theo bảng | Thời gian replay theo ngày dữ liệu | Tính tất định của tính lại analytics |
| 9.6 EXP-05 Chịu tải | Thông lượng, lag, p95 theo mức tải | p95 đầu-cuối theo mức tải với đường 10 s; bốn chặng xếp chồng | Nút thắt đầu tiên và vì sao |
| 9.7 EXP-07 Mở rộng | Số pod, p95, thời gian ổn định theo bước tải và biến thể | Số pod và lag theo thời gian (`autoscale` so với `fixed-1`, `fixed-4`) | Luật chặn khi DB chậm (`db-slow`) |
| 9.8 EXP-08 Chaos | Theo sự cố: mất/trùng, thời gian phục hồi, alert đúng | Timeline từng sự cố (một lần chạy điển hình) | Khác biệt giữa failover và switchover; giới hạn của k3d (ADR-0028) |
| 9.9 EXP-06 AI (tùy chọn) | Tỷ lệ tự động hóa theo ngưỡng, calibration, kappa | Đường precision theo tỷ lệ tự động hóa; reliability diagram | So với bộ luật; chi phí và độ trễ |
| 9.10 Tổng hợp | Bảng §3 với cột "Đạt/Không đạt" | — | Trả lời câu hỏi nghiên cứu |

## 5. Hình, bảng và ảnh chụp

### 5.1 Sơ đồ

Sơ đồ trong báo cáo được xuất từ Mermaid của tài liệu gốc, không vẽ lại. Xuất bằng `npx @mermaid-js/mermaid-cli -i <file>.mmd -o <file>.svg -t neutral -b transparent` sau khi chép khối Mermaid ra file `.mmd`. Nếu cần sửa cho gọn (bỏ nhánh phụ), sửa ở tài liệu gốc trước để hai nơi không lệch nhau.

### 5.2 Biểu đồ thực nghiệm

Chỉ dùng `charts/*.png` do `pti-exp report` sinh (DOC-45 §7). Biểu đồ cần thêm thì thêm vào `report` của runner rồi chạy lại, không vẽ tay bằng công cụ khác, để luôn sinh lại được từ `results/`.

### 5.3 Ảnh chụp màn hình

- Chụp ở lần diễn tập cuối (DOC-46 §1.1), stack `make up-demo`, trình duyệt 1440×900, zoom 100%, theme sáng, dữ liệu đã chạy ít nhất 30 phút.
- Chụp đúng trạng thái của kịch bản demo: Live map với panel "Bus bunching"; Alerts với drawer "Service disruption"; Dead letters với drawer và "History"; Jobs với "Streaming throughput"; Grafana `pti-overview` và `pti-k8s`.
- Lưu PNG ở `docs/11-report/figures/<chương>-<tên>.png`, ví dụ `07-live-map-bunching.png`. Không chụp màn hình có token, mật khẩu hay địa chỉ email.

## 6. Độ nhạy theo ngưỡng analytics (tùy chọn)

SDD §15 yêu cầu trình bày độ nhạy của ngưỡng bunching và gián đoạn, vì các ngưỡng này chọn theo kinh nghiệm. Đây là phân tích mô tả, không có tiêu chí đạt, nên không đặt thành EXP.

**Dữ liệu.** Một lần chạy 6 giờ (giờ nghiệp vụ 06:00–12:00) trên `make up-exp`, `seed` 42, có lịch kịch bản cố định do runner bật: 12 lần `bunching` và 8 lần `disruption` trên các tuyến chọn ngẫu nhiên theo `seed`, không chồng nhau trên cùng tuyến. Mỗi lần chạy kịch bản trong `sim.sim_scenario_run` là một sự kiện "được gieo" với tuyến, chiều và khoảng thời gian.

**Quét ngưỡng.** Tính lại analytics trên cùng dữ liệu với từng giá trị (DOC-23 §11 cho kết quả tất định):

| Tham số | Giá trị quét | Mặc định |
| --- | --- | --- |
| `pti.analytics.bunching.open-ratio` | 0,3 · 0,4 · 0,5 · 0,6 · 0,7 | 0,5 |
| `pti.analytics.bunching.open-consecutive` | 1 · 2 · 3 | 2 |
| `pti.analytics.disruption.open-z` | 1,5 · 2,0 · 2,5 · 3,0 · 3,5 | 2,5 |
| `pti.analytics.disruption.open-consecutive` | 1 · 2 · 3 | 2 |

Mỗi lần quét đổi một tham số, giữ các tham số khác ở mặc định.

**Cách chạy** (`uv run pti-exp sensitivity`, thêm vào runner, DOC-45 §2):

1. `docker compose stop etl-stream api triage-worker` (dừng luồng trực tiếp để không ghi vào insight; `etl-batch` vẫn chạy).
2. Với mỗi giá trị: ghi biến môi trường tương ứng (ví dụ `PTI_ANALYTICS_BUNCHING_OPEN_RATIO=0.4`) vào `.env`, `make restart S=etl-batch`, `make job-run NAME=AnalyticsRecomputeJob PARAMS='detectors=BUNCHING+DISRUPTION,fromTs=<t0>,toTs=<t1>' WAIT=1`, rồi xuất `insight_bus_bunching` và `insight_service_disruption` của khoảng đó ra CSV.
3. Trả mọi tham số về mặc định và chạy lại lần cuối để warehouse trở về trạng thái gốc.

**Chỉ số theo từng giá trị:**

- **Tỷ lệ phát hiện:** số sự kiện được gieo có ít nhất một episode cùng tuyến và chiều giao với `[started_at, ended_at + 10 phút]`, chia cho số sự kiện được gieo.
- **Độ trễ phát hiện:** trung vị của `episode_start − started_at` với các sự kiện được phát hiện.
- **Episode ngoài kịch bản mỗi giờ:** episode không giao với sự kiện được gieo nào. Simulator có nhiễu tự nhiên nên không gọi đây là báo động sai; chỉ số này cho biết ngưỡng "ồn" tới mức nào.

**Trình bày.** Một hình cho mỗi detector: trục x là giá trị ngưỡng, hai trục y là tỷ lệ phát hiện và episode ngoài kịch bản mỗi giờ, đánh dấu giá trị mặc định. Thảo luận: giá trị mặc định nằm ở đâu trên đường cong, và tại sao chọn nó. Giới hạn: dữ liệu mô phỏng, nên kết luận chỉ nói về hành vi của detector, không nói về giao thông thật (DOC-45 §8).

## 7. Giới hạn và hướng phát triển

Chương 10 nêu giới hạn theo ba nhóm, lấy từ các nguồn sau:

| Nhóm | Nguồn |
| --- | --- |
| Tính hợp lệ của thực nghiệm | DOC-45 §8; mục "Mối đe dọa" của từng EXP |
| Giới hạn của môi trường | ADR-0028 "Tiêu cực" (k3d một VM); DOC-10 §5 (máy 16 GB); DOC-40 §2 (`lite`) |
| Giới hạn thiết kế và phạm vi | SDD §2.3 và DOC-01 §6 (ngoài phạm vi), SDD §15; ADR-0026 (không dùng outbox cho sự kiện UI); DOC-24 §2 (AI chỉ gợi ý) |

Hướng phát triển lấy từ các phương án bị loại có giá trị trong ADR (ví dụ Kafka transactions, GitOps với Argo CD) và từ phần ngoài phạm vi của DOC-01 §6. Không hứa những việc chưa có căn cứ trong tài liệu.

## 8. Phụ lục báo cáo

| Phụ lục | Nội dung | Nguồn |
| --- | --- | --- |
| A | Bảng thuật ngữ | DOC-06 |
| B | Danh sách FR đầy đủ | DOC-03 |
| C | Danh sách ADR (tiêu đề, trạng thái, quyết định một dòng) | DOC-12 |
| D | Danh mục endpoint | DOC-32 (bảng tổng) |
| E | Rule chất lượng dữ liệu | DOC-16 |
| F | Protocol thực nghiệm chi tiết | DOC-45, EXP-01…08 (bản rút gọn: giả thuyết, biến, các bước, tiêu chí) |
| G | Một runbook mẫu | RB-03 |
| H | Kịch bản demo | DOC-46 §2–4 |
| I | Hướng dẫn chạy lại | DOC-38 §1–4; `make up-demo`, `uv run pti-exp run …` |

## 9. Quy trình chuẩn bị (P8-06)

1. Tag commit dùng cho bảo vệ (DOC-41 §10.4). Mọi kết quả chính thức phải có `git_sha` là commit đó hoặc commit trước đó mà giữa hai commit không đổi code của `etl-*`, `analytics`, `source-simulator` (kiểm bằng `git diff --stat <sha> <tag> -- <thư mục>`). Nếu có đổi thì chạy lại thực nghiệm bị ảnh hưởng.
2. `uv run pti-exp report`; chép bảng tổng hợp vào mục "Kết quả" của từng file EXP (DOC-45 §7) và vào chương 9.
3. Chạy phân tích độ nhạy (§6) nếu làm.
4. Xuất sơ đồ (§5.1) và chụp màn hình (§5.3).
5. Viết chương 10 theo bảng §3, điền cột "Đạt/Không đạt".
6. Rà lại: mọi số trong báo cáo có trong `experiments/results/report/`; mọi hình có nguồn; mọi thuật ngữ có trong phụ lục A.

## 10. Test bắt buộc

Không có test tự động. Bước 6 của §9 là kiểm tra thủ công trước khi nộp.

## 11. Câu hỏi còn mở

Không có.
