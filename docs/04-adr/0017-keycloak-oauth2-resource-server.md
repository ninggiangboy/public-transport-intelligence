# ADR-0017: Keycloak làm IdP, API là OAuth2 resource server

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-40, DR-41, DR-45, DR-50, DOC-27, DOC-31 §7, DOC-39, FR-10

## Bối cảnh

SDD gốc nói API là OAuth2 Resource Server nhưng không nói ai phát token. Hệ thống có ba nhóm người dùng:

- **Hành khách:** không đăng nhập, chỉ xem tuyến, trạm, xe và thông báo công khai.
- **Viewer:** xem insight nội bộ và trạng thái ETL.
- **Operator:** thao tác vận hành (replay, discard, cờ, restart job, phản hồi dispatch).

Ngoài ra có hai client không tương tác: smoke test và experiment runner (ADR-0025).

Ràng buộc: chạy được hoàn toàn offline trên máy cá nhân; giống cách làm ở production đủ để đề tài có giá trị; phân quyền kiểm được bằng test (FR-10.2).

## Các phương án

1. **API tự quản lý user (Spring Security form login, session).** Phải tự viết lưu mật khẩu, đăng nhập, đăng xuất; session làm khó scale ngang và SSE; không phải cách làm phổ biến cho SPA.
2. **JWT tự ký bằng secret dùng chung (HS256), endpoint `/login` trong API.** Nhẹ, nhưng API vừa phát vừa kiểm token; không có PKCE, refresh, khóa tài khoản.
3. **IdP bên ngoài (Auth0, Okta, Cognito).** Không chạy offline; phụ thuộc tài khoản bên thứ ba.
4. **Keycloak chạy trong compose, OIDC Authorization Code + PKCE cho SPA; API là resource server kiểm JWT bằng JWKS.**

## Quyết định

Chọn **phương án 4**.

- Keycloak 26 (`start-dev`, import realm `pti` từ JSON mỗi lần tạo container). Realm role `viewer`, `operator` (operator chứa viewer); user demo `viewer/viewer`, `operator/operator`.
- Client: `pti-web` (public, PKCE S256), `pti-smoke` (password grant, chỉ dev), `pti-experiments` (confidential, client credentials, service account có `operator`). Client scope thêm audience `pti-api`.
- API (Spring Security resource server) kiểm chữ ký theo JWKS, `iss`, `aud = pti-api`, `exp`; ánh xạ `realm_access.roles` sang `ROLE_VIEWER`/`ROLE_OPERATOR` với `RoleHierarchy`. Không session, không cookie, CSRF tắt.
- Endpoint hành khách không cần token, chỉ bị rate limit. Ma trận endpoint × role là một bảng duy nhất trong code (`EndpointRules`) và được test sinh ca kiểm thử từ đó (DOC-27 §4).
- Actor audit là `user:<preferred_username>`.
- Values k3d `lite` (không đủ RAM cho Keycloak) dùng JWT ký bằng khóa tĩnh, có guard chặn bật ở môi trường khác (DOC-27 §3.3).

## Hệ quả

**Tích cực**

- Đúng mô hình phổ biến ở production: SPA + PKCE, API stateless kiểm JWT.
- API không lưu mật khẩu; scale ngang và SSE không cần session.
- Client không tương tác dùng cùng cơ chế, nên runner thực nghiệm đi qua đúng đường phân quyền như người dùng.

**Tiêu cực**

- Keycloak tốn khoảng 500–700 MB RAM và thời gian khởi động 20–40 giây (tính trong ngân sách S-03).
- Hai URL cho cùng một issuer: trình duyệt thấy `localhost:8180`, API tải JWKS qua mạng nội bộ; phải cấu hình `KC_HOSTNAME` và `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` đúng (DOC-39).
- User demo mật khẩu yếu và `start-dev` là rủi ro được chấp nhận (DOC-27 §12).
