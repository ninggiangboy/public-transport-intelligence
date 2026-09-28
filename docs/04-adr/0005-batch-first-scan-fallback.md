# ADR-0005: Ghi chunk: batch upsert trước, fallback scan

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-21, DR-23, DR-27, ADR-0002, ADR-0003, ADR-0006, FR-02.2, FR-02.5, NFR-02, EXP-03

## Bối cảnh

SDD gốc muốn cùng lúc hai thứ không đi cùng nhau:

- **Ghi batch** (`JdbcTemplate.batchUpdate`, một round-trip cho cả chunk) để đạt throughput.
- **Savepoint theo từng record** để một record lỗi không kéo cả chunk vào DLQ.

Savepoint cho mỗi record thì mỗi record phải là một câu lệnh riêng, tức là mất lợi thế batch. Batch thì Postgres dừng ở lỗi đầu tiên và hủy cả batch, không biết record nào lỗi ngoài record đầu tiên.

Phần lớn lỗi dữ liệu (parse, schema, DQ) được bắt trong processor, trước khi ghi. Lỗi ở bước ghi (SQLState 22/23: tràn số, vi phạm constraint, FK tới bảng tham chiếu) hiếm khi xảy ra nếu processor làm đủ việc; EXP-03 đo tỷ lệ này.

## Các phương án

1. **Luôn ghi từng record với savepoint.** Đơn giản, đúng, nhưng chậm: ở tải 3× (khoảng 700 message/giây, trung bình 12 dòng mỗi TripUpdate) phải thực hiện gần 9.000 câu lệnh mỗi giây, trong khi batch chỉ cần khoảng 10 round-trip.
2. **Luôn ghi batch; lỗi thì bỏ cả chunk vào DLQ.** Nhanh nhưng làm DLQ đầy record tốt; vi phạm FR-02.5.
3. **Ghi batch trước; chỉ khi lỗi dữ liệu ở bước ghi thì rollback và scan.** Đường bình thường nhanh, đường lỗi đúng.
4. **Kiểm tra trước mọi constraint trong processor** để bước ghi không bao giờ lỗi. Không thể bảo đảm hoàn toàn (ví dụ tràn số ở kiểu cột, lỗi ở giá trị mà rule không lường trước), vẫn cần phương án dự phòng.

## Quyết định

Chọn **phương án 3**, cộng thêm processor bắt càng nhiều lỗi càng tốt (DOC-16) để scan hiếm khi xảy ra.

- **Job batch:** dùng nguyên fault-tolerant chunk step của Spring Batch, dựng bằng builder cũ `chunk(size, tx).faultTolerant()` vì `ChunkOrientedStep` mới của Spring Batch 6.0 làm mất DLQ khi scan (DR-80). Writer ném lỗi `DATA` → rollback → Spring Batch scan (mỗi item một transaction) → item lỗi vào `onSkipInWrite` → DLQ `LOAD`. `processorNonTransactional()` bật, processor thuần (DOC-19 §5).
- **Streaming:** `StreamChunkTemplate` rollback rồi mở **một** transaction mới, ghi từng item với savepoint, item lỗi vào DLQ, commit một lần (DOC-19 §6.2). Savepoint thay cho một transaction mỗi item để micro-batch vẫn chỉ có một commit và offset vẫn được commit một lần.
- **Cùng writer bean** (`FactChunkWriter`) cho cả batch và scan, và cho cả hai chế độ. Scan gọi writer với chunk một phần tử.
- Đơn vị scan là **một message** (`WriteSet`), không phải một dòng: TripUpdate có nhiều phần tử thì các phần tử cùng được ghi hoặc cùng vào DLQ.
- `etl_stream_batch.write_mode` ghi `BATCH` hoặc `SCAN`; metric `pti_etl_chunk_scan_total` đếm số lần scan.
- Lỗi `TRANSIENT_INFRA` ở bước ghi **không** kích hoạt scan (ADR-0006).

## Hệ quả

**Tích cực**

- Đường bình thường: một round-trip mỗi bảng đích cho mỗi chunk.
- Một record lỗi ở bước ghi chỉ làm record đó vào DLQ, record khác vẫn được ghi.
- Hai chế độ cùng thuật toán, cùng writer, nên test B-03 (DOC-19 §12) chạy chung một bộ kỳ vọng.

**Tiêu cực**

- Chunk có lỗi ghi tốn khoảng gấp đôi thời gian (một lần batch hỏng, một lần scan). Chấp nhận được vì hiếm; alert nếu `pti_etl_chunk_scan_total` tăng nhanh bất thường (DOC-28).
- Ở scan, DQ-02 (trùng key trong chunk) không còn gì để so vì mỗi lần ghi chỉ có một item. Guard của upsert vẫn giữ kết quả đúng (ADR-0003); chỉ mất khả năng phát hiện xung đột.
- Postgres đánh dấu transaction là aborted sau lỗi đầu tiên, nên không thể "tiếp tục" batch; bắt buộc rollback toàn bộ rồi làm lại.
