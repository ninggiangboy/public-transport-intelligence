# ADR-0001: Ghi quyết định kiến trúc bằng ADR

- Trạng thái: Accepted
- Ngày: 2026-09-26 · Liên quan: DOC-12, [Decision Register](../00-decision-register.md)

## Bối cảnh

Dự án có nhiều quyết định kiến trúc lệch khỏi SDD gốc (Spring Batch thay engine tự xây, JSON thay Protobuf, dedup registry chỉ là tối ưu…). Người triển khai và hội đồng đánh giá cần biết *vì sao* chọn như vậy, và không để mất lý do khi quyết định được xem lại. Decision Register (DR) gom các khoảng trống cần chốt nhanh, nhưng DR là danh sách làm việc, không phải hồ sơ lâu dài của từng quyết định.

## Các phương án

1. **Chỉ dùng DR.** Nhanh, nhưng mỗi mục DR ngắn và không ghi các phương án bị loại.
2. **ADR theo MADR rút gọn**, mỗi quyết định kiến trúc một file bất biến; DR trỏ sang ADR.
3. Wiki. Không đi cùng code, khó review qua PR.

## Quyết định

Chọn **phương án 2**.

- Thư mục `docs/04-adr/`, tên file `NNNN-kebab-case-title.md`, số tăng dần, không dùng lại số.
- Khung: tiêu đề, Trạng thái, Ngày, Liên quan; các mục **Bối cảnh / Các phương án / Quyết định / Hệ quả**.
- Trạng thái: `Proposed` → `Accepted` → (`Superseded by ADR-XXXX` | `Deprecated`). ADR đã Accepted **không sửa nội dung quyết định**. Muốn đổi thì viết ADR mới và đánh dấu ADR cũ là Superseded. Được sửa lỗi chính tả và cập nhật liên kết.
- Một mục DR ở cấp kiến trúc thì được chuyển thành ADR (cột "Ghi vào" của DR). Quyết định ở mức chi tiết (tham số, tên cột) thì ở lại DR và tài liệu thiết kế.
- Viết bằng tiếng Việt như mọi tài liệu trong `docs/` (DR-61). Tên class, cấu hình, chuỗi sản phẩm giữ nguyên tiếng Anh.
- `README.md` trong thư mục là chỉ mục: số, tiêu đề, trạng thái, gate.
- PR thay đổi kiến trúc phải kèm ADR, hoặc tham chiếu ADR đã có.

## Hệ quả

- Có thêm việc viết tài liệu cho mỗi quyết định lớn; bù lại, báo cáo đồ án có sẵn phần "các lựa chọn thiết kế và lý do".
- ADR bất biến nên lịch sử quyết định đọc được theo thứ tự thời gian.
