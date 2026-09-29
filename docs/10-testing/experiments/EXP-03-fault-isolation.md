# EXP-03: Record lỗi không làm mất record hợp lệ

> Trạng thái: **Approved** · Cập nhật: 2026-09-29 (DR-95) · DOC-45 / EXP-03
>
> Phụ thuộc: [protocol chung](README.md), DOC-16 §1–2 (stage, rule), DOC-20 §5, DOC-22 §1, DOC-25 §7.4 (kịch bản `bad-data`), ADR-0006, DR-27, DR-69
>
> Người dùng chính: P3-07, P3-08 (smoke), P3-10; báo cáo chương đánh giá

## 1. Giả thuyết

- **H1 (NFR-02, FR-02):** khi một tỷ lệ message bị hỏng (tới 20%), **mọi** message hợp lệ vẫn được nạp vào warehouse; pipeline không dừng, không pause vì lỗi dữ liệu.
- **H2 (FR-02.3):** mỗi message hỏng có đúng một dòng DLQ, ở đúng `stage` và đúng `rule_id` theo loại lỗi.
- **H3:** không message hợp lệ nào bị đưa nhầm vào DLQ, và không dữ liệu hỏng nào lọt vào fact.
- **H4 (đối chứng):** baseline `error-mode=fail-batch` mất một phần message hợp lệ tăng theo tỷ lệ lỗi.

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | `ratio` của `bad-data` | 0,01; 0,05; 0,20 |
| Độc lập | Cơ chế | `normal` và `baseline` song song |
| Phụ thuộc | Cô lập | `valid_loaded_ratio`, `dlq_recall`, `stage_accuracy`, `rule_accuracy`, `false_dlq`, `leaked` (§6) |
| Phụ thuộc | Ổn định | Số lần listener dừng hoặc pause, số lần process restart, p95 `pti_etl_kafka_to_commit_seconds` |
| Kiểm soát | `kinds` | Cả 8 loại (DOC-25 §7.4), phân bố đều theo hash |
| Kiểm soát | `entityTypes` | `VEHICLE_POSITION`, `TRIP_UPDATE` |
| Kiểm soát | Thời lượng kịch bản | `PT15M` |
| Kiểm soát | Tải | ×1; giờ nghiệp vụ 13:00–19:00 CDT ngày thường |

Ticketing không có trong phạm vi: simulator không sinh được giao dịch hỏng qua CDC vì DB nguồn có ràng buộc (DOC-13 §5.2). Rule DQ-10…DQ-13 được kiểm bằng integration test (DOC-16 §8).

## 3. Baseline (DR-27)

`etl-stream-baseline` với `error-mode=fail-batch`: một record lỗi làm cả poll bị bỏ (DOC-20 §9). Cơ chế được so sánh: phân loại lỗi theo record và DLQ (ADR-0006) so với "cả lô thất bại". Baseline không ghi DLQ, nên H2 và H3 chỉ đo ở normal.

## 4. Môi trường

README §1, `make up-exp`, profile `observability` bật.

## 5. Các bước

```bash
uv run pti-exp run EXP-03 --ratios 0.01,0.05,0.20 --runs 10 --seed 7000   # 30 runs, ratio order shuffled per block
uv run pti-exp run EXP-03 --ratios 0 --runs 3 --seed 7900                 # control: no bad data
uv run pti-exp analyze EXP-03
```

Một lần chạy:

1. Kiểm cửa sổ giờ (còn ≥ 25 phút), `TRUNCATE exp.*`, `PUT /sim/rate {"gtfsRt": 1, "ticketing": 1}`, chờ 60 giây. Ghi `max(id)` của `ops.dead_letter` làm mốc.
2. Silence 25 phút: `DlqRateHigh`, `DlqBacklogHigh`, `DataQualityCheckFailed`.
3. `POST /sim/scenarios/bad-data {"ratio": r, "duration": "PT15M"}` (mọi `kinds`, cả hai `entityTypes`). `t0` = lúc kịch bản `RUNNING`.
4. Lấy mẫu mỗi 2 giây: lag, `pti_etl_listener_running`, `pti_etl_listener_paused`, trạng thái container.
5. Khi kịch bản `COMPLETED` (`t1`): pause simulator, drain, unpause.
6. Tính chỉ số; xuất ledger và các dòng `ops.dead_letter` có `id > mốc` (không có `raw_payload`, chỉ các cột định vị và phân loại).

Triage-worker **không** chạy (profile `triage` tắt), để `status` của dead letter không đổi trong lúc đo.

**Lần chạy `invalid`:** như README §6.

## 6. Chỉ số và công thức

Tập message hỏng `M_bad` = dòng ledger trong cửa sổ có `intended_invalid = true`; tập dòng DLQ `D` = dòng `ops.dead_letter` mới, nối với ledger theo `(kafka_topic, kafka_partition, kafka_offset)`.

Bảng kỳ vọng (DOC-16 §1–2, DOC-25 §7.4):

| `invalid_kind` | `stage` | `rule_id` chấp nhận |
| --- | --- | --- |
| `malformed_json` | `DESERIALIZE` | NULL |
| `schema_violation` | `SCHEMA` | `DQ-01` |
| `unknown_schema_version` | `SCHEMA` | `DQ-01` |
| `out_of_bbox` | `QUALITY` | `DQ-06` |
| `unknown_route` | `QUALITY` | `DQ-03` (rule theo record dừng ở vi phạm đầu tiên theo thứ tự ID, DOC-16 §2.4, nên DQ-05 không bao giờ được ghi) |
| `unknown_stop` | `QUALITY` | `DQ-04` |
| `future_timestamp` | `QUALITY` | `DQ-07` |
| `delay_out_of_range` | `QUALITY` | `DQ-08` |

| Chỉ số | Công thức |
| --- | --- |
| `valid_loaded_ratio` | README §4.3 (`|K_exp ∩ K_act| / |K_exp|`), theo bảng, cho normal và baseline |
| `dlq_recall` | `|{m ∈ M_bad : ∃ d ∈ D tại vị trí của m}| / |M_bad|`, theo `invalid_kind` |
| `dlq_multiplicity` | Số vị trí Kafka có hơn một dòng DLQ (kỳ vọng 0; DLQ upsert theo vị trí, DOC-22 §1.3) |
| `stage_accuracy` | Tỷ lệ dòng DLQ khớp vị trí của `m ∈ M_bad` có `stage` đúng bảng trên, theo `invalid_kind` |
| `rule_accuracy` | Như trên với `rule_id` thuộc tập chấp nhận |
| `false_dlq` | Số dòng `d ∈ D` mà vị trí ứng với message `intended_invalid = false` (README §4.1 `unexpected`) |
| `leaked` | Chỉ VehiclePosition: số business key của `m ∈ M_bad` có dòng trong `dw.fact_vehicle_position`. Business key của VP là duy nhất theo message, nên message hỏng bị loại thì key không được có. Với TripUpdate, key `(ngày, chuyến, trạm)` còn xuất hiện ở message hợp lệ khác của chuyến đó nên không đo được theo cách này; thay bằng `wrong_value = 0` (dòng TU mang hash của message hợp lệ mới nhất) |
| `stalls` | Số lần `pti_etl_listener_running` về 0 hoặc `pti_etl_listener_paused` lên 1 trong cửa sổ, cộng số lần container restart |
| `commit_p95` | p95 `pti_etl_kafka_to_commit_seconds{source=~"GTFS_RT_.*"}` trong cửa sổ (README §4.4) |
| `baseline_lost_ratio` | `1 − valid_loaded_ratio` của baseline |

## 7. Tiêu chí đạt

| # | Tiêu chí |
| --- | --- |
| C1 | Normal: `valid_loaded_ratio = 1` (không thiếu key hợp lệ nào) và `wrong_value = 0` ở mọi lần chạy, mọi mức |
| C2 | `dlq_recall = 1` và `dlq_multiplicity = 0` ở mọi lần chạy |
| C3 | `stage_accuracy = 1` và `rule_accuracy = 1` cho mọi `invalid_kind` |
| C4 | `false_dlq = 0` và `leaked = 0` |
| C5 | `stalls = 0` |
| C6 | `commit_p95` ở mức 20% không vượt 1,5 lần `commit_p95` của lần chạy đối chứng (ghi DLQ trong cùng transaction không làm chậm đáng kể) |
| C7 | Đối chứng (`ratio = 0`): `valid_loaded_ratio = 1` ở cả normal và baseline |

H4 được báo cáo, không là tiêu chí đạt.

## 8. Phân tích

- Ma trận nhầm lẫn `invalid_kind` × `stage` (gộp mọi lần chạy); phải là ma trận "đường chéo" theo bảng kỳ vọng.
- Biểu đồ đường: `valid_loaded_ratio` normal và baseline theo `ratio` (CI bootstrap).
- Biểu đồ: kích thước poll thực tế (`records` trong `ops.etl_stream_batch`) để giải thích mức mất của baseline (poll càng lớn, xác suất chứa ít nhất một record hỏng càng cao: `1 − (1 − r)^n`). So kết quả baseline với mô hình này làm kiểm tra chéo.
- `commit_p95` theo mức (hộp).

## 9. Mẫu bảng kết quả

| `ratio` | n | Message hỏng | Normal: `valid_loaded_ratio` | `dlq_recall` | `stage_accuracy` | `false_dlq` / `leaked` | `stalls` | `commit_p95` | Baseline: `valid_loaded_ratio` trung vị [CI] |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 3 | 0 | 1 | — | — | 0 / — | 0 | … | 1 |
| 0,01 | 10 | … | 1 | 1 | 1 | 0 / 0 | 0 | … | … |
| 0,05 | 10 | … | | | | | | | |
| 0,20 | 10 | … | | | | | | | |

| `invalid_kind` | Số message | `DESERIALIZE` | `SCHEMA` | `QUALITY` | Khác / không có DLQ |
| --- | --- | --- | --- | --- | --- |
| `malformed_json` | … | … | 0 | 0 | 0 |
| … | | | | | |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Loại lỗi do simulator tạo không bao phủ mọi lỗi thực tế | Tám loại trải đủ bốn stage có thể tạo từ phía nguồn; `LOAD` và `BUSINESS` được test tự động (DOC-16 §8, DOC-19 §12) |
| Lỗi phân bố đều theo hash, không dồn cục như sự cố thật (một producer hỏng trong vài phút) | Mức 20% là trường hợp xấu cho mọi poll; một biến thể dồn cục có thể thêm sau bằng cách chạy `bad-data` ngắn (`PT1M`) với `ratio = 0,5` |
| Baseline mất nhiều do poll lớn, phụ thuộc cấu hình `max.poll.records` | Báo cáo kích thước poll thực tế và mô hình `1 − (1 − r)^n` |
| Một message hỏng có thể vi phạm nhiều rule (route lạ kéo theo chuyến không khớp tuyến) | Thứ tự rule cố định, dừng ở vi phạm đầu tiên (DOC-16 §2.4) và được test ở DOC-16 §8 |

## 11. Kết quả

Điền sau P3-10. Kết quả chuỗi smoke (DR-95) không ghi vào đây.

## 12. Câu hỏi còn mở

Không có.
