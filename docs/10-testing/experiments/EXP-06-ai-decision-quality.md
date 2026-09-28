# EXP-06: Chất lượng quyết định của Jev so với bộ luật

> Trạng thái: **Review** · Cập nhật: 2026-09-28 · DOC-45 / EXP-06
>
> Phụ thuộc: [protocol chung](README.md), [DOC-24](../../06-design/ai-triage.md) §6, §7, §8, §11, §13, §14.1, §15, [DOC-25](../../06-design/source-simulator.md) §7.3, §7.4, §7.6, §7.7, §7.9, [ADR-0018](../../04-adr/0018-decision-model-port.md), [ADR-0019](../../04-adr/0019-code-owned-automation-thresholds.md), DR-36, DR-52, DR-73, G8 (DOC-01)
>
> Người dùng chính: P6-11; báo cáo chương đánh giá (phần AI)

EXP-06 là thực nghiệm **tùy chọn** (master plan P6-11, thứ tự cắt giảm ở DOC-01). Nó cần API key Jev (giả định A2). Không có key thì bỏ thực nghiệm, ghi rõ trong báo cáo; không có gì khác phụ thuộc vào kết quả.

Khác EXP-01…05, EXP-06 **không đo pipeline khi đang chạy**. Tập dữ liệu được tạo một lần, cố định thành file, gán nhãn tay, rồi mọi provider được chạy trên cùng file bằng profile `eval` của triage-worker (DOC-24 §14.1). Vì vậy mọi khác biệt giữa các provider chỉ đến từ mô hình.

## 1. Giả thuyết

- **H1 (an toàn, ADR-0019):** ở mức hệ thống (mô hình + bảng quyết định + guard), **không** dead letter nào mà replay không sửa được bị đưa vào auto-replay.
- **H2 (G8):** có ngưỡng confidence τ sao cho các record mô hình đề xuất tự động (`category ∈ auto-replay.categories` và `p > τ`) có precision ≥ 95% theo nhãn "replay được"; tỷ lệ tự động hóa tại τ đó được báo cáo.
- **H3 (FR-09.1, DOC-24 §11.3):** Jev đủ nhanh: p95 độ trễ một lời gọi ≤ 1,5 giây, và 100 record xong trong ≤ 60 giây với 8 lời gọi song song.
- **H4 (calibration):** confidence của Jev được hiệu chỉnh đủ tốt để dùng làm ngưỡng: ECE ≤ 0,10.
- **H5 (so với bộ luật):** trên category DLQ, macro-F1 của Jev không thấp hơn baseline luật quá 0,05 (không kém hơn); đồng thời Jev vẫn phân loại được khi thiếu `ruleId` (biến thể `no-rule`), việc mà bộ luật không làm được.

H1 là tiêu chí đạt. H2…H5 được báo cáo; kết luận của báo cáo dựa trên chúng nhưng không chặn phase nào.

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | Provider | `jev` (model version ghi theo DOC-24 §13), `fake` (baseline luật, `fake@2026.09`) |
| Độc lập | Biến thể state | `full` (state như DOC-24 §6.1), `no-rule` (xóa `record.ruleId`, `record.ruleDescription`, `record.errorClass`; chỉ DLQ, chỉ `jev`) |
| Độc lập | Ngưỡng τ (phân tích sau) | 0,50 đến 0,99, bước 0,01 |
| Phụ thuộc | Chất lượng | Accuracy, macro-F1, precision/recall theo lớp, severity MAE và κ có trọng số (§7) |
| Phụ thuộc | Tự động hóa | `auto_precision(τ)`, `automation_rate(τ)`, `harmful_auto`, `missed_auto` (§7.2) |
| Phụ thuộc | Calibration | ECE, Brier score, biểu đồ độ tin cậy |
| Phụ thuộc | Hiệu năng | Độ trễ p50/p95/p99, thời gian xử lý 100 record, tỷ lệ lỗi, chi phí trên 1.000 record |
| Phụ thuộc | Ổn định | Tỷ lệ record có cùng label top-1 qua 3 lần chạy Jev |
| Kiểm soát | Tập dữ liệu | Các file `*-states.jsonl` cố định (§5), SHA-256 ghi vào `config.json` |
| Kiểm soát | Câu hỏi và mô tả option | Theo commit của triage-worker; đổi mô tả là một lần chạy mới (DOC-24 §6.2) |
| Kiểm soát | Resilience4j | Cấu hình DOC-24 §11.1, không đổi |

## 3. Baseline

Baseline là `FakeDecisionModel` (DOC-24 §15): bảng luật tất định theo stage, rule và vài chỉ số trong state. Đây là "hệ thống không có AI" hợp lý nhất: cùng thông tin, cùng bảng quyết định, cùng guard. Nó cũng là nguồn của `DlqRuleClassifier` (luật chặn cuối).

Baseline có lợi thế: nó được viết khi đã biết các loại lỗi mà simulator sinh ra. Vì vậy H5 chỉ đòi Jev "không kém hơn", và biến thể `no-rule` được thêm để đo khả năng mà bộ luật không có.

Baseline DR-27 (`etl-stream-baseline`) không liên quan tới thực nghiệm này.

## 4. Môi trường

- Tạo tập dữ liệu: compose `make up-exp` (README §1) có thêm profile `triage` nhưng triage-worker chạy với `pti.triage.provider=disabled`, để dead letter giữ `NEW` và state export không bị ảnh hưởng bởi triage. etl-stream chạy với `PTI_DQ_MAX_CLOCK_SKEW=5m` (DR-73) để record của `late-delivery` vào DQ-07.
- Chạy `decide`: chỉ cần image triage-worker và mạng tới Jev; không cần stack. Runner chạy `decide` trên máy dev tham chiếu, không chạy việc khác cùng lúc (độ trễ đo từ máy này tới Jev, gồm cả mạng Internet).
- `config.json` ghi thêm: `provider`, `modelVersion` (lấy từ dòng đầu của kết quả), phiên bản `typesafe-java-sdk`, SHA-256 của file states và file nhãn, commit của triage-worker, giờ bắt đầu (UTC) và vị trí mạng (tên mạng, không ghi IP).

## 5. Tập dữ liệu và gán nhãn

### 5.1 Tạo dead letter (một lần)

```bash
uv run pti-exp run EXP-06 --phase generate --seed 6000
```

Runner làm tuần tự, giờ nghiệp vụ 13:00–19:00 CDT ngày thường, tải ×1:

| Bước | Kịch bản | Tham số | Vùng lỗi thu được |
| --- | --- | --- | --- |
| 1 | `bad-data` (DOC-25 §7.4) | `ratio` 0,002, mọi `kinds`, cả hai `entityTypes`, `duration` `PT20M` | `DESERIALIZE`; `SCHEMA` DQ-01 (hai loại); `QUALITY` DQ-03, DQ-04, DQ-06, DQ-07 (tương lai), DQ-08 |
| 2 | `late-delivery` (DOC-25 §7.9) | `ratio` 0,002, `delay` `PT6M`, `duration` `PT10M` | `QUALITY` DQ-07 (đến muộn) |
| 3 | `ticket-spike` (§7.6) ×15 và `refund-burst` (§7.7) ×15 | Mỗi lần một điểm bán khác nhau, cách nhau 20 phút | Anomaly ticketing |
| 4 | `disruption` (§7.3) ×20 tuyến; 10 lần trong số đó chạy cùng `bad-data` `ratio` 0,05 trên toàn nguồn | Mỗi lần 20 phút | Gián đoạn thật và gián đoạn trong lúc dữ liệu có vấn đề |

Ghi `max(id)` của `ops.dead_letter` trước bước 1 làm mốc. Bước 3 và 4 chờ analytics đóng cửa sổ và tạo insight (DOC-23).

### 5.2 Export và lấy mẫu

```bash
uv run pti-exp run EXP-06 --phase export
```

1. Gọi profile `eval` (DOC-24 §14.1): `export-dlq` từ mốc (`limit` 5000), `export-ticketing` và `export-disruption` từ lúc bắt đầu bước 3.
2. Nối mỗi dòng DLQ với ledger theo vị trí Kafka để có `invalid_kind` (bad-data) hoặc `scenario = late-delivery`. Cột này **chỉ runner giữ**, không nằm trong file states và không hiện khi gán nhãn.
3. Lấy mẫu phân tầng DLQ theo `(stage, ruleId, invalid_kind hoặc late)`: tối đa 35 dòng mỗi tầng, chọn ngẫu nhiên bằng seed. Chín tầng, khoảng 315 dòng.
4. Ghi `experiments/datasets/EXP-06/dlq-states.jsonl`, `ticketing-states.jsonl`, `disruption-states.jsonl` và `strata.csv` (`itemId`, tầng, `invalid_kind`). Tập dữ liệu được commit (payload đã qua `PiiScrubber` và `PiiGuard`; runner chạy lại kiểm PII trước khi ghi và từ chối nếu thấy khóa trong danh sách chặn).

Tầng không có trong tập: `LOAD`, `DEDUP` DQ-02, `BUSINESS` DQ-12 và DQ-13, vì simulator không sinh được qua luồng thường (DOC-25 §7.4, DOC-13 §5.2). Các nhánh này được kiểm bằng TG-01, TG-02 và DOC-16 §8. Báo cáo nêu rõ giới hạn này (§11).

### 5.3 Gán nhãn tay

```bash
uv run pti-exp label EXP-06 --set dlq          # then --set ticketing, --set disruption
```

Công cụ gán nhãn là CLI của runner: hiện state JSON đã định dạng, thứ tự ngẫu nhiên, **không** hiện kết quả của provider nào và không hiện `invalid_kind`. Người gán nhãn nhập:

| Tập | Cột nhãn | Giá trị |
| --- | --- | --- |
| DLQ | `category` | 5 label của DOC-24 §6.2 |
| DLQ | `severity` | 0, 1, 2 |
| DLQ | `replayable` | `true` nếu replay **nguyên văn, sau này** sẽ ghi được dữ liệu **đúng** vào kho; ngược lại `false` |
| Ticketing | `category`, `severity` | 4 label của DOC-24 §7; 0, 1, 2 |
| Disruption | `data_issue` | `true` nếu độ trễ quan sát được chủ yếu là do dữ liệu hỏng hoặc thiếu |
| Tất cả | `note` | Tùy chọn, tiếng Anh |

Hướng dẫn gán nhãn (dùng nguyên văn mô tả option của DOC-24 §6.2 làm định nghĩa):

- `severity`: 2 khi lỗi cho thấy mất dữ liệu hàng loạt hoặc lỗi hệ thống (ví dụ `recentSimilar.sameSourceLast15m` lớn so với `sameSourceInputLast15m`); 1 khi cần người xem nhưng không gấp; 0 khi là lỗi lẻ tẻ tự hết.
- `replayable`: chỉ xét record, không xét guard. Record đến muộn nhưng hợp lệ là `true`. Tọa độ sai, trễ ngoài khoảng, giờ ở tương lai là `false` (replay sẽ ghi dữ liệu sai). Schema hỏng là `false`.

File nhãn: `experiments/datasets/EXP-06/labels-<set>.csv` với cột `item_id, category, severity, replayable, data_issue, labeller, labelled_at, note`.

**Độ tin cậy của nhãn:** sau ít nhất 7 ngày, người gán nhãn gán lại 20% mẫu ngẫu nhiên (seed 6100) mà không xem nhãn cũ. Báo cáo Cohen's κ cho `category` và `replayable`, κ có trọng số bậc hai cho `severity`. Nếu κ(`category`) < 0,8 thì sửa hướng dẫn, gán lại các dòng bất đồng và ghi lại cả hai lần.

**Đối chiếu sau khi gán nhãn:** runner so nhãn với bảng kỳ vọng suy từ `invalid_kind` (DOC-25 §7.4: ví dụ `out_of_bbox` → `upstream_api_error`, `replayable = false`; `late-delivery` → `transient_network`, `replayable = true`) và liệt kê các dòng lệch. Người gán nhãn xem lại từng dòng; chỉ sửa khi nhận ra nhầm lẫn, ghi lý do vào `note`. Số dòng sửa được báo cáo.

## 6. Các bước

```bash
uv run pti-exp run EXP-06 --phase decide --provider fake --variant full
uv run pti-exp run EXP-06 --phase decide --provider jev  --variant full --repeat 3
uv run pti-exp run EXP-06 --phase decide --provider jev  --variant no-rule
uv run pti-exp run EXP-06 --phase throughput --provider jev --items 100 --parallelism 8
uv run pti-exp analyze EXP-06
```

- `decide`: gọi `decide` của profile `eval` với `parallelism` 1 cho mỗi file states. `--repeat 3` chạy lại ba lần, cách nhau ít nhất 1 giờ. `no-rule`: runner tạo `dlq-states-no-rule.jsonl` bằng cách xóa ba trường khỏi `state.record` rồi chạy `decide` trên file đó.
- `throughput`: lấy 100 dòng đầu của `dlq-states.jsonl` theo thứ tự trộn (seed 6200), chạy `decide` với `parallelism` 8. Thời gian xử lý là từ lúc process bắt đầu gọi tới lúc ghi dòng cuối (log `Eval finished` có `elapsedMs`).
- Chi phí: runner ghi số lời gọi thành công. Nếu S-01 xác nhận có API xem usage thì đọc usage trước và sau; nếu không, chi phí = số lời gọi × đơn giá công bố tại ngày chạy (ghi nguồn đơn giá vào `config.json`).

**Lần chạy `invalid`:** tỷ lệ `outcome ≠ ok` > 5% (sự cố của nhà cung cấp hoặc mạng); commit của triage-worker khác nhau giữa các provider được so sánh; SHA-256 của file states không khớp. Lần chạy `invalid` được giữ và liệt kê như README §6.

## 7. Chỉ số và công thức

Chỉ tính trên các dòng `outcome = ok`; tỷ lệ còn lại được báo cáo riêng.

### 7.1 Phân loại

| Chỉ số | Công thức |
| --- | --- |
| `accuracy` | Số dòng có label top-1 bằng nhãn / số dòng |
| `precision_c`, `recall_c`, `F1_c` | Theo lớp `c` |
| `macro_F1` | Trung bình `F1_c` trên các lớp có trong nhãn |
| `severity_mae` | Trung bình `|level − nhãn|`, `level = round(Σ i·p_i)` (DOC-24 §4.2) |
| `severity_kappa` | κ có trọng số bậc hai giữa `level` và nhãn |
| `stability` | Tỷ lệ dòng có cùng label top-1 trong cả 3 lần chạy `jev`; độ lệch chuẩn trung bình của confidence top-1 |

CI 95% bằng bootstrap 10.000 lần theo dòng (README §6).

### 7.2 Tự động hóa (DLQ)

Với `A = auto-replay.categories = {transient_network, upstream_api_error}` (DOC-24 §17):

| Chỉ số | Công thức |
| --- | --- |
| `auto_set(τ)` | Các dòng có label ∈ `A` và `p > τ` (chỉ mô hình, chưa có guard) |
| `auto_precision(τ)` | Tỷ lệ dòng trong `auto_set(τ)` có `replayable = true`; kèm cận dưới Wilson 95% |
| `automation_rate(τ)` | `|auto_set(τ)| / số dòng` |
| `τ*` | τ nhỏ nhất có `auto_precision(τ) ≥ 0,95`; không có thì ghi "không đạt" |
| `harmful_auto` | Số dòng có `decision.status = AUTO_REPLAY_SCHEDULED` (mô hình + bảng quyết định + guard, ngưỡng cấu hình 0,9) mà `replayable = false` |
| `missed_auto` | Số dòng `replayable = true` mà `decision.status ≠ AUTO_REPLAY_SCHEDULED`, kèm `reason` |
| `system_automation_rate` | Tỷ lệ dòng có `decision.status = AUTO_REPLAY_SCHEDULED` |
| `status_mix` | Phân bố `MANUAL` / `PENDING_CONFIRM` / `AUTO_REPLAY_SCHEDULED` theo `reason` |

`auto_precision` và `automation_rate` được tính cả khi coi `A = {transient_network}` để thấy vai trò của guard với `upstream_api_error`.

### 7.3 Calibration

- ECE = `Σ_b (n_b / n) · |acc_b − conf_b|` trên 10 khoảng confidence top-1 rộng bằng nhau, cho `category` DLQ.
- Brier đa lớp = trung bình `Σ_c (p_c − y_c)²` dùng xác suất từng lựa chọn trong `answers`.
- Biểu đồ độ tin cậy (reliability diagram) cho `jev` và `fake`. Với `fake` confidence là hằng số theo luật nên biểu đồ chỉ minh họa.

### 7.4 Hiệu năng và chi phí

| Chỉ số | Công thức |
| --- | --- |
| `latency_p50/p95/p99` | Phân vị của `latencyMs` (gồm Resilience4j và mạng) ở `parallelism` 1 |
| `batch100_seconds` | Thời gian xử lý 100 dòng ở `parallelism` 8 |
| `error_rate` | Tỷ lệ `outcome ≠ ok`, tách `failed` và `unavailable` |
| `cost_per_1000` | Chi phí / số lời gọi thành công × 1000 (USD) |

## 8. Tiêu chí đạt

| # | Tiêu chí | Giả thuyết |
| --- | --- | --- |
| C1 | `harmful_auto = 0` cho mọi lần chạy `jev` và `fake`, cả `full` và `no-rule` | H1 |
| C2 | κ(`category`) của việc gán lại ≥ 0,8 (sau tối đa một vòng sửa hướng dẫn) | Điều kiện để các chỉ số khác có nghĩa |

C1 không đạt là **lỗi thiết kế** (guard cho qua một record không replay được): mở issue, thêm test tái hiện vào TG-01 và TG-02 và sửa guard trước khi báo cáo.

Chỉ báo cáo, không là tiêu chí đạt: G8 (H2: `τ*` và `automation_rate(τ*)`), H3 (`latency_p95 ≤ 1,5 s`, `batch100_seconds ≤ 60`), H4 (`ECE ≤ 0,10`), H5 (hiệu `macro_F1` của `jev` trừ `fake` với CI; `macro_F1` của `no-rule`).

Nếu `τ*` khác xa 0,9 (lớn hơn 0,97 hoặc nhỏ hơn 0,8), báo cáo đề xuất giá trị mới cho `pti.triage.auto-replay.min-confidence`; đổi cấu hình là một quyết định riêng (ghi DR), không tự động theo kết quả.

## 9. Phân tích và mẫu bảng kết quả

`pti-exp analyze EXP-06` sinh:

- Ma trận nhầm lẫn category (nhãn × dự đoán) cho `jev full`, `fake full`, `jev no-rule`.
- Đường `auto_precision(τ)` và `automation_rate(τ)` theo τ, đánh dấu τ = 0,9 (cấu hình) và `τ*`.
- Biểu đồ độ tin cậy; biểu đồ phân bố confidence theo đúng/sai.
- Histogram độ trễ; bảng lỗi theo lớp exception.
- Danh sách mọi dòng `harmful_auto` hoặc bị guard chặn (`GUARD_BLOCKED`), để thảo luận định tính.

| Tập / biến thể | Provider | n | Accuracy [CI] | Macro-F1 [CI] | Severity MAE / κ | ECE | Brier | Lỗi |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DLQ full | fake | … | | | | — | — | 0 |
| DLQ full | jev | … | | | | | | |
| DLQ no-rule | jev | … | | | | | | |
| Ticketing | fake / jev | … | | | | | | |
| Disruption (`data_issue`, ngưỡng 0,7) | fake / jev | … | | | — | | | |

| Provider | τ = 0,9: `auto_precision` [Wilson] | `automation_rate` | `τ*` | `automation_rate(τ*)` | `harmful_auto` | `system_automation_rate` | `missed_auto` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| fake | | | | | 0 | | |
| jev | | | | | 0 | | |

| Provider | p50 | p95 | p99 | `batch100_seconds` | `error_rate` | `cost_per_1000` | `stability` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jev | | | | | | | |

## 10. Đo API batch (từ S-01)

DOC-24 §3 để API batch của SDK ngoài phạm vi P6 (vẫn gọi từng state). Nếu S-01 cho thấy batch dùng được, mục này ghi kết quả đo trên chính `dlq-states.jsonl`:

| Cách gọi | Kích thước lô | Số lời gọi HTTP | Thời gian cho 100 dòng | p95 mỗi lô | Kết quả có khác gọi từng state? |
| --- | --- | --- | --- | --- | --- |
| Từng state, `parallelism` 8 | 1 | 100 | | | — |
| Batch | 20 | 5 | | | Tỷ lệ dòng khác label top-1 |

Đo bằng một test tay ngoài `decide` (script trong `experiments/pti_exp/experiments/exp06_batch.py` gọi SDK qua jshell hoặc một main class trong `backend/triage-worker/src/contractTest`), vì profile `eval` chỉ có đường gọi từng state. Nếu S-01 kết luận batch không dùng được, ghi "Không áp dụng" cùng lý do.

## 11. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Một người gán nhãn, cũng là người viết hệ thống | Gán nhãn mù (không thấy kết quả mô hình và `invalid_kind`); κ gán lại; hướng dẫn gán nhãn viết trước; mọi sửa nhãn sau đối chiếu có lý do |
| Lỗi do simulator tạo đơn giản và dễ đoán hơn lỗi thật | Nêu rõ trong báo cáo; biến thể `no-rule` làm bài toán khó hơn; kết luận chỉ về khả năng dùng trong hệ thống này |
| Baseline luật được viết khi đã biết các loại lỗi | H5 chỉ đòi "không kém hơn"; báo cáo nói rõ lợi thế này |
| Thiếu các tầng `LOAD`, `DEDUP`, `BUSINESS` | Nêu giới hạn; các nhánh đó có test tự động |
| Mô hình của nhà cung cấp đổi giữa các lần chạy | Ghi `modelVersion` và ngày; ba lần lặp đo độ ổn định; nếu `modelVersion` khác nhau giữa các lần lặp thì báo cáo tách riêng |
| Độ trễ phụ thuộc mạng Internet của máy chạy | Ghi mạng và giờ chạy; không so độ trễ với các thực nghiệm khác |
| Cỡ mẫu nhỏ (khoảng 35 dòng mỗi tầng) | CI bootstrap và cận Wilson; không kết luận theo từng tầng nếu CI quá rộng |
| Ticketing và disruption: nhãn phụ thuộc vào ý đồ kịch bản | Chỉ báo cáo mô tả; không dùng cho H1–H5 |

## 12. Kết quả

Điền sau P6-11.

## 13. Câu hỏi còn mở

Không có.
