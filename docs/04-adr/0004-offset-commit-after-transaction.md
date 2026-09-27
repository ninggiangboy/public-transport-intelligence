# ADR-0004: Commit offset sau khi transaction của chunk commit; ánh xạ poll → chunk

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-22, DR-23, DR-63, ADR-0002, ADR-0003, FR-02, NFR-01, EXP-01

## Bối cảnh

ADR-0003 chọn at-least-once cộng upsert idempotent. At-least-once chỉ đúng nếu **offset Kafka không bao giờ được commit trước khi dữ liệu tương ứng đã nằm trong Postgres**. Nếu thứ tự ngược lại, pod chết giữa hai bước sẽ làm mất message, và không cơ chế idempotent nào cứu được.

Cần chốt thêm:

- Đơn vị giao dịch của streaming: một record, một poll, hay gom nhiều poll.
- Cách commit offset: tự gọi `Acknowledgment.acknowledge()`, `AckMode.RECORD`, `AckMode.BATCH`, hay lưu offset trong DB.
- Hành vi khi DB lỗi tạm thời: không để consumer bị văng khỏi group (vượt `max.poll.interval.ms`) và không để message rơi vào DLQ chỉ vì DB chập chờn.

## Các phương án

1. **Record listener, `AckMode.RECORD`.** Mỗi record một transaction. Đơn giản nhưng khoảng 2.000 commit/giây ở tải gấp 3 (DOC-10), vượt khả năng của một Postgres trên laptop.
2. **Batch listener, `AckMode.MANUAL`**, listener tự gọi `ack.acknowledge()` sau commit. Đúng, nhưng thêm một chỗ dễ quên, và không lợi gì hơn phương án 3 khi xử lý đồng bộ.
3. **Batch listener, `AckMode.BATCH`**, listener xử lý đồng bộ trong một transaction và trả về khi đã commit; container commit offset sau khi listener trả về bình thường.
4. **Lưu offset trong DB cùng transaction** (`ConsumerSeekAware` khi assign). Chặt nhất nhưng tự quản offset, mất công cụ chuẩn (`kafka-consumer-groups`, lag exporter, KEDA). ADR-0003 đã loại.
5. **Kafka transaction (read-process-write).** Không áp dụng vì đích không phải Kafka.

## Quyết định

Chọn **phương án 3**.

- `@KafkaListener(batch = "true")`, `max.poll.records=500`, `fetch.max.wait.ms=1000`, `fetch.min.bytes=65536`. **Mỗi poll là một chunk**, có một `batch_id` (UUIDv7) và một dòng `ops.etl_stream_batch` (DR-63).
- Listener gọi `StreamChunkTemplate.execute` (DOC-19 §6) **đồng bộ**. Khi `execute` trả về, transaction đã commit; container dùng `AckMode.BATCH` commit offset của poll đó ngay sau đó (`commitSync`, `syncCommits=true`).
- `enable.auto.commit=false` (Spring Kafka mặc định). Baseline `offset-commit=auto` (DR-27) là ngoại lệ duy nhất và chỉ có ở profile `experiment`.
- Lỗi dữ liệu **không bao giờ thoát ra khỏi listener**: chúng được skip vào DLQ trong transaction (ADR-0005, ADR-0006).
- Lỗi hạ tầng thoát ra ngoài: `DefaultErrorHandler` seek mọi partition của poll về offset đầu, `ExponentialBackOff` (1 giây, hệ số 2, trần 30 giây, không giới hạn số lần), và `ContainerPausingBackOffHandler` **pause container** trong lúc chờ, nên consumer vẫn gọi `poll()` (trả rỗng) và không bị văng khỏi group.
- Lỗi `FATAL` dừng container (`CommonContainerStoppingErrorHandler` được chọn qua `CommonDelegatingErrorHandler` theo loại exception).
- Không tạo `JobExecution` Spring Batch cho mỗi poll (ADR-0002).
- Sự kiện `MicroBatchCommitted` cho analytics được publish sau khi `execute` trả về, nghĩa là sau commit và trước khi offset được commit. Listener analytics là `@EventListener` + `@Async`. Nếu analytics chạy lại vì poll được giao lại thì kết quả không đổi, vì insight idempotent (ADR-0010).

## Hệ quả

**Tích cực**

- Thứ tự "dữ liệu commit → offset commit" được bảo đảm bởi cấu trúc code (gọi đồng bộ), không phụ thuộc lập trình viên nhớ gọi `ack`.
- Một commit mỗi poll: khoảng 3 đến 10 commit/giây cho mỗi listener thread, thay vì hàng nghìn.
- Giữ nguyên consumer group chuẩn: rebalance, lag metric, scale theo lag bằng KEDA ở P7.

**Tiêu cực**

- Pod chết sau commit DB nhưng trước commit offset thì cả poll (tối đa 500 record) được giao lại. Upsert làm kết quả không đổi, nhưng tốn một lần ghi lặp; `records_duplicate` tăng tương ứng. EXP-01 đo con số này.
- Một record gây lỗi hạ tầng lặp lại (ví dụ câu lệnh luôn timeout) làm cả partition đứng. Có alert `ConsumerLagHigh` và `ConsumerPaused` (DOC-28); runbook RB-03 hướng dẫn xử lý.
- Rebalance giữa lúc đang xử lý: container của Spring Kafka đợi listener trả về trước khi thu hồi partition (trong giới hạn `max.poll.interval.ms` = 5 phút), nên không có hai consumer cùng xử lý một poll trừ khi vượt giới hạn. Nếu vượt, upsert vẫn giữ kết quả đúng.
