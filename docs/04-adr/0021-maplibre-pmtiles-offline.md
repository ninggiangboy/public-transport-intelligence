# ADR-0021: Bản đồ MapLibre và PMTiles offline

- Trạng thái: Accepted (xác minh S-05)
- Ngày: 2026-09-27 · Liên quan: DR-47, DR-01, ADR-0020, DOC-27 §5.3, DOC-35 §6, DOC-39, S-05, FR-11.1

## Bối cảnh

Live map là màn hình trung tâm của demo (J-5): hội đồng xem xe chạy, cặp bunching được tô nổi. Buổi bảo vệ có thể không có internet ổn định, nên bản đồ nền không được phụ thuộc dịch vụ bên ngoài. Vùng cần phủ là Twin Cities, bbox `-93.730, 44.707, -92.806, 45.330` (DR-01, S-05).

Yêu cầu:

- Hiển thị được hoàn toàn offline sau `make up`.
- Vector tile (xoay, zoom mượt; đổi style sáng/tối không cần tải lại tile).
- Không cần API key, license cho phép phân phối lại.
- File không đưa vào git (kích thước lớn), nhưng lấy về được bằng một lệnh.

## Các phương án

1. **Tile server bên ngoài (OpenFreeMap, MapTiler, Mapbox).** Đơn giản nhất nhưng cần internet; MapTiler và Mapbox cần key.
2. **Tự chạy tile server (TileServer GL, Martin) với file MBTiles.** Chạy offline được, nhưng thêm một container và cổng.
3. **Raster tile cắt sẵn.** Nhiều file nhỏ, không đổi style được, nặng hơn vector.
4. **Một file PMTiles cắt theo bbox, phục vụ tĩnh bằng nginx của frontend qua HTTP Range; MapLibre đọc bằng protocol `pmtiles://`.**

## Quyết định

Chọn **phương án 4**; phương án 1 (OpenFreeMap) chỉ dùng khi dev.

- **Tạo file:** `make tiles` tải bản build hằng ngày của Protomaps basemap rồi chạy `pmtiles extract <build> infra/tiles/twin-cities.pmtiles --bbox=-93.730,44.707,-92.806,45.330 --maxzoom=15`. Kết quả khoảng 60–90 MB (xác nhận ở S-05). File nằm trong `.gitignore`; `SHA256SUMS` và ngày build được ghi vào `infra/tiles/README.md`. Lần chạy sau so checksum và không tải lại nếu đã có.
- **Phục vụ:** compose mount `infra/tiles/` vào `/usr/share/nginx/html/tiles/` của container `frontend` (chỉ đọc). Nginx trả `Accept-Ranges: bytes` mặc định; thêm `location /tiles/ { add_header Cache-Control "public, max-age=86400"; }`.
- **Style:** `frontend/public/map/style-light.json` và `style-dark.json` dựng từ `protomaps-themes-base` (flavor `light`/`dark`, nhãn tiếng Anh), đã chỉnh cho nền trung tính để màu tuyến và màu trạng thái nổi bật (DOC-35 §6). Glyph (`/map/fonts/{fontstack}/{range}.pbf`, Noto Sans Regular/Medium) và sprite (`/map/sprites/`) nằm trong `public/`, nên không có request nào ra ngoài.
- **Chọn nguồn lúc chạy:** biến `PTI_MAP_STYLE` của container `frontend` (`offline` mặc định, hoặc `online`) được render vào `env.js`. `online` dùng `https://tiles.openfreemap.org/styles/positron` và yêu cầu `PTI_MAP_TILE_ORIGINS=https://tiles.openfreemap.org` cho CSP (DOC-27 §5.3). Vite dev server dùng `frontend/public/env.js` đã commit với `mapStyle: "online"` (DOC-29 §3.5); muốn thử offline khi dev thì sửa thành `offline` và chạy `make tiles` (Vite phục vụ `/tiles/` qua symlink `public/tiles → ../../infra/tiles`, cũng gitignored).
- **Thiếu file:** frontend gọi `HEAD /tiles/twin-cities.pmtiles` lúc khởi tạo bản đồ; 404 thì hiển thị bản đồ không có nền (chỉ tuyến, trạm, xe trên nền màu trơn) kèm thông báo "Base map unavailable. Run `make tiles` to download it." (DOC-37), không chặn các lớp dữ liệu.
- **Attribution:** "© OpenStreetMap contributors · Protomaps" luôn hiển thị ở góc bản đồ (yêu cầu của ODbL).

## Hệ quả

**Tích cực**

- Demo chạy hoàn toàn offline; không có key hay tài khoản bên thứ ba.
- Không thêm container: nginx của frontend vừa phục vụ SPA vừa phục vụ tile.
- Vector tile cho phép style sáng/tối và nhãn tiếng Anh thống nhất với UI.

**Tiêu cực**

- File tile 60–90 MB phải tải một lần bằng `make tiles`; máy mới không có internet thì chỉ có bản đồ không nền.
- Tile là ảnh chụp tại một thời điểm, không tự cập nhật (chấp nhận được cho demo).
- `pmtiles extract` cần cài CLI `go-pmtiles` (thêm vào `mise`, DOC-38) hoặc chạy qua image Docker `protomaps/go-pmtiles`.
