# EXP-02: Gửi lại message không tạo bản ghi trùng

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-45 / EXP-02
>
> Phụ thuộc: [protocol chung](README.md), DOC-14 §8 (upsert và guard), DOC-25 §7.5 (kịch bản `duplicates`), DR-16, DR-27, DR-28, ADR-0003
>
> Người dùng chính: P3-07, P3-08; báo cáo chương đánh giá

## 1. Giả thuyết

- **H1 (NFR-01, FR-03.2):** khi nguồn gửi lại message (giống hệt nội dung, khác `message_id` và `produced_at`) ở tỷ lệ tới 50%, mỗi business key có đúng một dòng trong warehouse, với giá trị của message mới nhất.
- **H2 (DR-16):** tính không trùng **không phụ thuộc** `dedup_registry`: khi bản gửi lại tới sau TTL của registry (1 giờ), kết quả vẫn không trùng nhờ upsert và guard.
- **H3:** `vehicle_position_latest` không bao giờ bị kéo lùi về vị trí cũ hơn khi bản gửi lại tới muộn.
- **H4 (đối chứng):** baseline (INSERT thuần, không registry) có số dòng thừa xấp xỉ số bản gửi lại.

## 2. Biến

| Loại | Biến | Giá trị |
| --- | --- | --- |
| Độc lập | `ratio` của `duplicates` | 0,01; 0,10; 0,50 |
| Độc lập | Độ trễ gửi lại | `short`: `[PT0S, PT60S]`; `long`: `[PT65M, PT75M]` (vượt TTL 1 giờ) |
| Độc lập | Cơ chế | `normal` và `baseline` song song |
| Phụ thuộc | Đúng đắn | `lost`, `duplicates`, `wrong_value`, `latest_regressions` (README §4.1) |
| Phụ thuộc | Đường xử lý bản gửi lại | `registry_hits`, `guard_blocks`, `in_chunk_collapses` (Δ `pti_etl_duplicates_total` theo `reason`, §6) |
| Kiểm soát | Tải | ×1 (≈ 143 msg/s lúc 16:38) |
| Kiểm soát | Giờ nghiệp vụ | 13:00–19:00 CDT ngày thường |
| Kiểm soát | Kịch bản khác | Không |
| Kiểm soát | `pti.etl.dedup.ttl` | `1h` (mặc định) |

## 3. Baseline (DR-27)

`etl-stream-baseline` với `write-mode=insert`, `dedup=off` (và `offset-commit=auto`, không ảnh hưởng vì không có kill). Cơ chế được so sánh: upsert có guard (ADR-0003) cộng registry, so với INSERT thuần không registry. Baseline không có `vehicle_position_latest` nên H3 chỉ đo ở normal.

## 4. Môi trường

README §1, `make up-exp`, profile `observability` bật.

## 5. Các bước

```bash
uv run pti-exp run EXP-02 --variant short --ratios 0.01,0.10,0.50 --runs 10 --seed 5000   # 30 runs, ratio order shuffled per block
uv run pti-exp run EXP-02 --variant long  --ratios 0.10 --runs 5 --seed 6000
uv run pti-exp analyze EXP-02
```

Một lần chạy `short`:

1. Kiểm cửa sổ giờ (còn ≥ 20 phút), `TRUNCATE exp.*`, `PUT /sim/rate {"gtfsRt": 1, "ticketing": 1}`, chờ 60 giây. `t0` = lúc này.
2. `POST /sim/scenarios/duplicates {"ratio": r, "minDelay": "PT0S", "maxDelay": "PT60S", "duration": "PT10M"}` với `X-Requested-By: experiment:EXP-02/<run_id>`.
3. Lấy mẫu mỗi 2 giây như EXP-01.
4. Kịch bản kết thúc ở `t0 + 10 phút`; chờ hàng đợi gửi lại trống (`pti_sim_resend_queue_depth = 0`, DOC-25 §12; khoảng 60 giây). `t1` = lúc hàng đợi trống.
5. Pause simulator, drain, unpause, tính chỉ số.

Một lần chạy `long`:

1. Như trên, với `minDelay = PT65M`, `maxDelay = PT75M`, `duration = PT10M`.
2. Simulator tiếp tục phát bình thường tới khi hàng đợi gửi lại trống (khoảng 85 phút sau `t0`). Cửa sổ `[t0, t1]` bao cả phần dữ liệu thường phát trong 85 phút đó, nên tập kỳ vọng lớn hơn; chỉ số chính chỉ tính trên các key có bản gửi lại (§6).
3. Giờ nghiệp vụ phải còn ≥ 95 phút trong khung khi bắt đầu.

Không có silence nào: kịch bản không được làm bắn alert. Alert bắn trong lần chạy được ghi vào `alerts.unexpected_fired` của `summary.json` (DOC-45 §7) và được báo cáo.

**Lần chạy `invalid`:** như README §6; thêm: `pti_sim_emissions_skipped_total{reason="resend_queue_full"}` tăng (hàng đợi gửi lại đầy, 200.000 message, DOC-25 §7.5).

## 6. Chỉ số và công thức

README §4.1, cộng:

| Chỉ số | Công thức |
| --- | --- |
| `resends` | Số dòng ledger `is_resend = true` trong cửa sổ, theo `entity_type` |
| `resend_keys` | Tập business key của các dòng đó (`K_resend ⊆ K_exp`) |
| `lost_resend`, `wrong_resend` | `lost`, `wrong_value` giới hạn trên `K_resend` (chỉ số chính cho `long`) |
| `registry_hits` | Δ `pti_etl_duplicates_total{mode="stream", reason="registry"}` trong cửa sổ (DOC-19 §10), GTFS-rt. `short`: kỳ vọng ≈ `resends`; `long`: kỳ vọng ≈ 0 vì registry đã hết hạn |
| `guard_blocks` | Δ `pti_etl_duplicates_total{reason="guard"}`: writer trả về 0 dòng (DOC-14 §8). `long`: kỳ vọng ≈ số dòng fact mà bản gửi lại chạm tới |
| `in_chunk_collapses` | Δ `pti_etl_duplicates_total{reason="in_chunk"}`: DQ-02 gộp hai bản trong cùng chunk (bản gốc và bản gửi lại gần như tức thì, hoặc TripUpdate bị thay thế) |
| `total_duplicate` | Δ `pti_etl_records_total{outcome="duplicate"}`; bằng tổng ba số trên (kiểm tính nhất quán của metric) |
| `baseline_extra_rows` | `Σ_k (rows_baseline(k) − 1)` trên `K_resend` |
| `baseline_extra_ratio` | `baseline_extra_rows / resends` (kỳ vọng ≈ 1) |
| `latest_regressions` | README §4.1, kiểm **sau** khi bản gửi lại cuối cùng được xử lý |

Mọi bản gửi lại có cùng `payload_hash` với bản gốc, nên `wrong_value` so với "message mới nhất theo event time" cũng chính là so với bản gốc.

## 7. Tiêu chí đạt

| # | Tiêu chí |
| --- | --- |
| C1 | Normal: `lost = 0`, `duplicates = 0`, `wrong_value = 0`, `unexpected = 0` ở mọi lần chạy, mọi mức `ratio`, cả `short` và `long` |
| C2 | `latest_regressions = 0` ở mọi lần chạy (H3) |
| C3 | `long`: `lost_resend = 0`, `wrong_resend = 0` và `registry_hits / resends < 0,01` (xác nhận bản gửi lại thật sự đi tới upsert, không bị registry chặn, nên không trùng là nhờ guard) |
| C4 | `short`: `registry_hits + in_chunk_collapses ≥ 0,99 × resends` (registry và DQ-02 bắt gần hết bản gửi lại sớm; phần còn lại do biên cửa sổ) |
| C5 | `total_duplicate = registry_hits + guard_blocks + in_chunk_collapses` ở mọi lần chạy |

H4 được báo cáo, không là tiêu chí đạt. Nếu `baseline_extra_ratio` ≪ 1, phép so sánh trùng không nhạy và cần kiểm lại cấu hình baseline.

## 8. Phân tích

- Bảng C1…C4 theo biến thể × `ratio`.
- Biểu đồ cột: `resends`, `registry_hits`, `in_chunk_collapses`, `guard_blocks`, `baseline_extra_rows` theo mức `ratio` (trung bình ± CI).
- Biểu đồ: phân phối độ trễ gửi lại thực tế (`produced_at` bản gửi lại − bản gốc) để kiểm kịch bản đúng tham số.
- Chi phí của gửi lại: so p95 `pti_etl_kafka_to_commit_seconds` giữa `ratio = 0,01` và `0,50` (Wilcoxon hai mẫu, chỉ mô tả).

## 9. Mẫu bảng kết quả

| Biến thể | `ratio` | n | Bản gửi lại (tổng) | Normal: mất / trùng / sai | `latest_regressions` | `registry_hits` / `guard_blocks` | Baseline dòng thừa (tỷ lệ) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `short` | 0,01 | 10 | … | 0 / 0 / 0 | 0 | … / … | … (…) |
| `short` | 0,10 | 10 | … | | | | |
| `short` | 0,50 | 10 | … | | | | |
| `long` | 0,10 | 5 | … | | | | |

## 10. Mối đe dọa tới tính hợp lệ

| Mối đe dọa | Giảm thiểu |
| --- | --- |
| Bản gửi lại của kịch bản giống hệt bản gốc, không mô phỏng trường hợp nguồn gửi lại với nội dung **đã sửa** | Trường hợp nội dung khác cùng key được test tự động (DOC-14 §8.6, test U-xx của writer); EXP-02 chỉ đo gửi lại y hệt như FR-03.2 yêu cầu |
| Biến thể `long` kéo dài 85 phút làm giờ nghiệp vụ đổi và số xe thay đổi | Chỉ số chính tính trên `K_resend`; ghi `active_vehicles` |
| Phân tách theo `reason` dựa vào metric tự viết | C5 kiểm tính nhất quán; `WriteStatsCollector` có unit test theo từng nhánh |
| Kafka producer của simulator tự retry (idempotent producer) cũng tạo trùng ở mức broker | Producer idempotent nên broker loại trùng; không ảnh hưởng ledger vì ledger ghi sau ack |

## 11. Kết quả

Điền sau P3-08.

## 12. Câu hỏi còn mở

Không có.
