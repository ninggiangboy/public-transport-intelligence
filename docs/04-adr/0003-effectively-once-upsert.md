# ADR-0003: Effectively-once bằng at-least-once cộng upsert theo business key

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DR-13, DR-16, DR-22, DR-63, FR-03, NFR-01, EXP-01, EXP-02, EXP-04

## Bối cảnh

Câu hỏi nghiên cứu yêu cầu pipeline "không mất và không trùng" khi process chết, khi producer gửi lại, và khi replay. Nguồn dữ liệu là Kafka (GTFS-rt, CDC) và file (GTFS static, raw zone), còn đích là PostgreSQL. SDD gốc dựa vào `dedup_registry` (bảng hash) để chống trùng, nhưng cách này có hai vấn đề:

1. Registry phình theo số message, tới hàng triệu dòng mỗi ngày (DOC-10).
2. Replay sau khi sửa lỗi logic sẽ bị registry chặn, vì hash của message không đổi (DR-16).

## Các phương án

1. **Exactly-once của Kafka (transactional producer/consumer).** Chỉ có tác dụng khi đích là Kafka. Đích ở đây là Postgres, nên vẫn phải có cơ chế riêng ở phía DB.
2. **Lưu offset Kafka trong DB, cùng transaction với dữ liệu** (tự quản offset). Bảo đảm chặt, nhưng phải tự viết assignment và seek, mất tính năng của consumer group (rebalance, KEDA), và không áp dụng được cho replay từ file.
3. **Registry hash là cơ chế chính.** Hai vấn đề nêu trên.
4. **At-least-once + ghi idempotent theo business key + guard thứ tự.** Giao lại một message thì kết quả cuối vẫn như cũ.

## Quyết định

Chọn **phương án 4**.

- **Giao nhận at-least-once:** offset Kafka chỉ commit **sau khi** transaction của chunk đã commit (ADR-0004). Job batch restart từ `ExecutionContext` được ghi cùng transaction với chunk (ADR-0002).
- **Ghi idempotent:** mọi fact được ghi bằng `INSERT … ON CONFLICT (business key) DO UPDATE`. Business key của từng bảng nằm ở DOC-09 §9 và DOC-14.
- **Guard thứ tự:** `DO UPDATE … WHERE excluded.event_timestamp > t.event_timestamp` (GTFS-rt) hoặc `excluded.source_lsn > t.source_lsn` (CDC). Event cũ tới muộn không ghi đè event mới. Với `fact_trip_update`, không bao giờ chuyển `is_observed` từ `true` về `false` (DR-13).
- **Insight** cũng idempotent: id là UUIDv5 của khóa tự nhiên theo event time (ADR-0010, P4).
- **`dedup_registry` chỉ là lớp tối ưu:** bỏ qua sớm message gửi lại y hệt và đếm `records_duplicate_total`. Nó được ghi trong cùng transaction với chunk, TTL 1 giờ, và **mọi luồng replay đều bỏ qua registry**. Xóa registry không làm hệ thống sai, chỉ làm metric duplicate kém chính xác.
- Chế độ baseline (DR-27) tắt đúng những cơ chế này để thực nghiệm đo được sự khác biệt.

## Hệ quả

**Tích cực**

- Một cơ chế dùng chung cho streaming, job batch, DLQ replay và raw zone replay.
- Replay sau khi sửa lỗi logic ghi đè đúng dữ liệu cũ, không sinh dòng mới.
- Không phụ thuộc Kafka transaction, nên giữ được consumer group và scale theo lag.

**Tiêu cực**

- Mọi bảng đích đều phải có business key ổn định và UNIQUE constraint. Bảng partitioned thì key phải chứa cột partition (`service_date`, `sale_date`), nên business key được chọn có cột ngày (DOC-09 §9).
- Ghi lại cùng dữ liệu vẫn tốn một lần UPDATE, tức có thêm WAL và dead tuple. Chấp nhận được với khối lượng ở DOC-10.
- "Không trùng" được định nghĩa theo business key. Hai message khác business key nhưng cùng thực thể ngoài đời (lỗi của nguồn) không được phát hiện ở tầng này; rule DQ-02 chỉ bắt trùng trong cùng chunk.
