# ADR-0031: Không dùng Redis

- Trạng thái: Accepted
- Ngày: 2026-09-30 · Liên quan: DR-103, DR-45, ADR-0003, ADR-0015, ADR-0016, ADR-0017, ADR-0026, DOC-10 §5, DOC-31 §8, §10.3, §11

## Bối cảnh

Khi bắt đầu P4 (app `api` chạy nhiều pod trên k3d, SSE, cache, rate limit), cần trả lời: có nên thêm Redis vào stack không? Redis thường được thêm vào hệ thống nhiều pod để làm một hoặc nhiều việc sau: pub/sub giữa các pod, cache dùng chung, rate limit chung, lưu session, khóa phân tán, idempotency key, hàng đợi công việc.

PTI đã có giải pháp cho từng việc đó, dựa trên Kafka và PostgreSQL:

| Việc Redis hay làm | PTI dùng | Vì sao đủ |
| --- | --- | --- |
| Pub/sub, fan-out giữa các pod | Kafka `pti.events.ui`, mỗi pod `api` một consumer group, ring buffer (ADR-0016) | Topic giữ sự kiện 1 ngày, nên pod mới seek theo thời gian để phát bù được; Redis pub/sub không giữ sự kiện |
| Cache | Caffeine trong từng pod, TTL 2 giây tới 10 phút (DOC-31 §10.3) | Chỉ cache dữ liệu đọc công khai; không có thao tác ghi nào cần xóa cache trên pod khác (endpoint vận hành và response có token là `no-store`) |
| Rate limit | Bucket4j trong bộ nhớ, tính theo pod (DR-45, DOC-31 §11) | Mục đích là chặn lạm dụng thô; hạn mức thực gấp N lần với N pod (k3d: 2→4 ở `lite`, 2→6 ở `full`) được chấp nhận |
| Session | Không có: `api` stateless, JWT của Keycloak (ADR-0017), SPA giữ token trong bộ nhớ | Không có trạng thái phiên phía server |
| Khóa phân tán | ShedLock trên PostgreSQL, fencing bằng `VERSION` của Spring Batch (ADR-0015) | Job chạy theo chu kỳ phút hoặc giờ; khóa và fencing nằm cùng DB với dữ liệu job |
| Idempotency key | Cột UNIQUE ở `replay_request`, `job_request` (DOC-31 §8) | Lưu khóa và tạo yêu cầu trong **cùng một transaction** |
| Hàng đợi công việc | Kafka cho luồng dữ liệu; lease trên dòng PostgreSQL cho triage (DOC-24) | Lưu lượng triage nhỏ; lease nằm trên chính dòng cần xử lý nên không lệch trạng thái |
| Chống trùng, trạng thái xử lý luồng | Upsert có điều kiện (ADR-0003); bảng `analytics_*` (DR-31, DR-35) | Phải commit cùng dữ liệu và dựng lại được từ raw zone (EXP-04) |

## Các phương án

1. **Thêm Redis ngay ở P4** cho cache dùng chung và rate limit chung (và có thể thay Kafka cho SSE fan-out).
2. **Không dùng Redis**; giữ các giải pháp ở bảng trên, ghi rõ khi nào xem lại.

## Quyết định

Chọn **phương án 2**. Lý do:

1. **Không có việc nào cần tới Redis.** Mọi vai trò ở bảng trên đã có giải pháp đủ cho quy mô của đồ án: một agency, khoảng 1.000 xe, 2–6 pod `api`.
2. **Trái ràng buộc đã chốt.** ADR-0016 yêu cầu không thêm hạ tầng ngoài Kafka và PostgreSQL.
3. **Đúng đắn cần transaction chung.** Idempotency, khóa job, con trỏ và baseline analytics phải commit cùng dữ liệu nghiệp vụ. Đặt chúng ở Redis sinh ra trạng thái "DB đã commit, Redis chưa" (hoặc ngược lại), trái NFR-01 (mất = 0, trùng = 0).
4. **Mọi trạng thái đều dựng lại được.** Hiện trạng thái chỉ nằm ở Kafka và PostgreSQL, và EXP-04 chứng minh dựng lại được từ raw zone. Redis là một nơi giữ trạng thái nữa phải backup (DOC-43) và giải thích khi dựng lại.
5. **Thêm bề mặt chịu lỗi.** NFR-09 và EXP-08 yêu cầu mỗi sự cố đơn lẻ tự phục hồi. Redis kéo theo kịch bản chaos, alert, runbook mới, quyết định fail-open hay fail-closed, và trên k3d là Sentinel hoặc replica.
6. **Ngân sách RAM.** Profile core của compose đã đặt khoảng 8 GB, bật đủ profile khoảng 10,4 GB trên máy 16 GB (DOC-10 §5, NFR-07).
7. **Nút nghẽn không nằm ở chỗ Redis giải quyết.** NFR-10 (p95 < 200 ms) dựa vào bảng tính sẵn, `vehicle_position_latest`, keyset pagination và replica; NFR-03 phụ thuộc ETL và Kafka. Không NFR nào bị chặn bởi cache hay rate limit.

## Hệ quả

**Tích cực**

- Không thêm container, dependency, secret hay runbook.
- Mọi trạng thái nằm ở hai hệ thống đã được backup, giám sát và thử chịu lỗi.

**Tiêu cực**

- Rate limit tính theo pod (đã ghi ở DR-45, DOC-27 AR-05).
- Mỗi pod tự nạp cache: với N pod, một key bị truy vấn tối đa N lần trong một TTL, và cache nguội sau mỗi lần deploy.
- Trong một TTL, hai pod có thể trả dữ liệu lệch nhau (ví dụ tối đa 30 giây sau khi đổi feed, DOC-31 §10.2).
- Ring buffer SSE mất khi pod restart, chỉ nạp lại trong 5 phút (ADR-0016).

**Việc phát sinh**

- Cache nóng TTL ngắn nạp theo kiểu single-flight trong pod (DOC-31 §10.3).
- Kiểm tra bucket `public` với số request thật của SPA trên compose ở P5 (DOC-31 §11).

## Xem lại khi

Luôn thử phương án không cần Redis trước.

| Tình huống | Dấu hiệu đo được | Thử trước | Cân nhắc Redis khi |
| --- | --- | --- | --- |
| Cần rate limit hay quota chính xác cho cả cụm (API công khai có API key, quota theo tenant) | Có FR về quota, hoặc số pod đủ lớn để hạn mức gấp N mất ý nghĩa | `bucket4j-postgresql` trên DB đang có; giới hạn ở ingress | Việc kiểm tra rate limit làm primary nóng lên (mỗi request thêm một lần ghi) |
| Truy vấn nặng mà cache theo pod không đỡ được | p95 `http_server_requests_seconds` vượt 200 ms dù đã tính sẵn; tải DB tăng theo số pod; p95 nhảy sau mỗi lần deploy | Bảng tổng hợp hoặc materialized view; `proxy_cache` ở nginx cho response `public`; thêm replica | Cần cache dùng chung, xóa chủ động được và sống qua deploy |
| Có thao tác ghi cần mọi pod thấy ngay | Operator sửa dữ liệu hiển thị công khai và yêu cầu hết độ trễ TTL | Phát sự kiện xóa cache qua `pti.events.ui` (mọi pod đều đọc) | Xóa cache dày và phức tạp |
| SSE vượt quá cách mỗi pod đọc toàn topic | Số pod `api` lớn, hoặc lưu lượng `pti.events.ui` tốn CPU và băng thông đáng kể ở mỗi pod; cần gửi sự kiện riêng cho từng người dùng | Chia topic theo kênh; phát bù thẳng từ Kafka thay cho ring buffer | Cần định tuyến tới đúng pod giữ kết nối của một người dùng |
| Chuyển sang BFF, session phía server | Quyết định giữ token ở server | Spring Session JDBC | Lưu lượng session lớn |
| Bộ đếm hoặc trạng thái thời gian thực ghi rất dày | Nhiều pod cùng upsert một dòng hàng nghìn lần mỗi giây, tranh chấp khóa trên PostgreSQL | Gom trong bộ nhớ rồi ghi theo lô | Nhiều pod cần đọc và ghi chung một giá trị với độ trễ thấp |
| Cần khóa hay điều phối ở mức giây trở xuống | Nhiều pod tranh cùng tài nguyên với tần suất cao | Khóa trên dòng, `SELECT … FOR UPDATE SKIP LOCKED` | Độ trễ của khóa trên DB thành nút nghẽn |
| Mở rộng sang nhiều agency hoặc tenant | Tải gấp nhiều lần, quota theo tenant | Các dòng trên | Nhiều dòng ở trên cùng xảy ra |

Nếu thêm Redis, các nguyên tắc sau là điều kiện của ADR mới thay thế ADR này:

- Redis chỉ giữ trạng thái tạm, suy ra được; mất sạch Redis thì hệ thống vẫn đúng, chỉ chậm hơn.
- Cache và rate limit **fail-open**: Redis chết thì quay về Caffeine và Bucket4j trong bộ nhớ, không trả 5xx.
- Không đưa vào Redis thứ gì cần transaction cùng dữ liệu nghiệp vụ (idempotency, khóa job, con trỏ và baseline analytics).
- Có metric, alert, runbook, kịch bản Redis chết trong EXP-08, và dòng mới trong ngân sách RAM của DOC-10 §5.
