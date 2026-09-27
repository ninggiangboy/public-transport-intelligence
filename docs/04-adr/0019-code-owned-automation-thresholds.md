# ADR-0019: Ngưỡng tự động hóa do code sở hữu, AI chỉ phán đoán

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: SDD §9.6, DR-36, DR-38, DR-73, ADR-0018, ADR-0013, DOC-24 §6–10, FR-09.2, FR-09.3, FR-09.5, FR-09.8

## Bối cảnh

Một số kết quả của mô hình dẫn tới hành động có hệ quả: replay dữ liệu vào kho (auto-replay), ẩn cảnh báo khỏi hành khách (đổi audience gián đoạn), đổi mức nghiêm trọng của cảnh báo ticketing, và gửi cảnh báo khẩn cho kỹ sư. Mô hình có thể sai, có thể tự tin sai, và có thể đổi hành vi khi nhà cung cấp cập nhật mà dự án không biết. SDD §9.6 yêu cầu có giới hạn cứng: tối đa 2 lần auto-replay mỗi record và một luật chặn cuối không phụ thuộc AI.

Câu hỏi: ai quyết định hành động, mô hình hay code?

## Các phương án

1. **Hỏi thẳng mô hình hành động** ("có nên replay không?", dùng `Noul` hoặc `Choice` với các hành động). Đơn giản; nhưng logic quyết định nằm trong prompt, không test được bằng unit test, không có giới hạn cứng, và mỗi lần mô hình đổi là hành vi hệ thống đổi.
2. **Mô hình phán đoán, code quyết định.** Mô hình chỉ trả nhãn mô tả (category, severity, xác suất lỗi dữ liệu) kèm confidence. Bảng quyết định trong code, ngưỡng trong cấu hình, điều kiện an toàn do code kiểm, giới hạn cứng trong DB.
3. **Không tự động hóa gì**; mọi kết quả AI chỉ để hiển thị. An toàn nhất nhưng bỏ mục tiêu auto-replay của SDD.

## Quyết định

Chọn **phương án 2**.

- Mô hình chỉ trả lời câu hỏi mô tả. Mọi hành động do lớp thuần trong code quyết định:
  - DLQ: `DlqDecisionTable` (DOC-24 §6.3), luật đầu tiên khớp. `schema_violation` và `unknown` luôn vào `MANUAL`; confidence < 0,5 vào `MANUAL`; chỉ `transient_network`, `upstream_api_error` với confidence > 0,9 mới được auto; còn lại vào `PENDING_CONFIRM`.
  - Ticketing: severity của cảnh báo chỉ đổi khi confidence đủ ngưỡng (DOC-24 §7).
  - Gián đoạn: audience chỉ hạ sang `ENGINEERING` khi xác suất lỗi dữ liệu > 0,7 (FR-09.5).
- **Guard do code sở hữu** (`AutoReplayGuard`, DOC-24 §6.4): auto-replay chỉ được phép với các rule mà replay có thể sửa được (DQ-07 khi record đến muộn, DQ-12 khi giao dịch gốc đã có), bất kể mô hình tự tin đến đâu. Guard kiểm hai lần: lúc quyết định và ngay trước khi tạo `replay_request`.
- **Giới hạn cứng nhiều lớp**: `auto_replay_count` có `CHECK 0..2` trong DB; validator cấu hình từ chối `max-per-record > 2`; tối đa 60 auto-replay mỗi phút; chờ nguồn `UP` ≥ 60 giây (DR-38).
- **Luật chặn cuối không phụ thuộc AI** (FR-09.8): category tất định do etl gán lúc ghi DLQ (`DlqRuleClassifier`), alert Prometheus `DlqUpstreamErrorBurst`. Cảnh báo theo severity cũng đi qua Prometheus/Alertmanager, không phải triage-worker tự ghi (DOC-24 §10).
- **Người luôn giành lại được quyền**: tắt cờ `triage.auto-replay.enabled` chuyển mọi dòng đang chờ auto-replay sang `PENDING_CONFIRM` trong ≤ 10 giây.
- Mỗi hành động tự động ghi `dlq_action_log` với actor `auto`, confidence và `reason`.

## Hệ quả

**Tích cực**

- Mọi nhánh quyết định có unit test (TG-01, TG-02) và giải thích được bằng `reason`.
- Đổi mô hình hoặc prompt không đổi được hành động nguy hiểm: guard và giới hạn cứng vẫn giữ.
- Ngưỡng hiệu chỉnh được bằng dữ liệu (EXP-06 đo precision theo ngưỡng) mà không đụng code.

**Tiêu cực**

- Tự động hóa hẹp hơn khả năng của mô hình: phần lớn dead letter vẫn cần người (`PENDING_CONFIRM`, `MANUAL`). Chấp nhận; mở rộng guard là quyết định có chủ đích, phải kèm test và số liệu EXP-06.
- Có hai nơi mô tả category (mô tả option gửi mô hình và bảng luật của Fake/`DlqRuleClassifier`) phải giữ nhất quán về nghĩa.
- Cách đọc chữ của FR-09.2/09.3 được làm chặt hơn (`schema_violation` không vào hàng chờ xác nhận, `referential_integrity` không auto); ghi rõ ở DOC-24 §6.3.
