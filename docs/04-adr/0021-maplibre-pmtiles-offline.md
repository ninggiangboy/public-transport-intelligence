# ADR-0021: Bản đồ MapLibre và PMTiles offline

- Trạng thái: Accepted (đã xác minh ở S-05, 2026-09-28)
- Ngày: 2026-09-27 · Liên quan: DR-47, DR-01, ADR-0020, DOC-27 §5.3, DOC-35 §6, DOC-39, S-05, DR-82, FR-11.1

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

- **Tạo file:** `make tiles` chạy `infra/tiles/fetch.sh`, script này làm ba việc:
  - Chọn bản build hằng ngày mới nhất của Protomaps: thử `https://build.protomaps.com/<YYYYMMDD>.pmtiles` từ hôm qua lùi tối đa 7 ngày, lấy bản đầu tiên trả 200. Có thể ép bằng `BUILD=<YYYYMMDD>`.
  - Chạy `pmtiles extract <build> infra/tiles/twin-cities.pmtiles --bbox=-93.730,44.707,-92.806,45.330 --maxzoom=15`. S-05 đo được khoảng **84 MB** (9.403 tile), mất khoảng 23 giây.
  - Tải font và sprite từ `protomaps/basemaps-assets` tại commit pin `028c18f713baecad011301ff7a69acc39bcc2ae7` vào `infra/tiles/fonts/` (Noto Sans Regular, Medium, Italic, đủ mọi dải glyph, khoảng 13 MB, license OFL) và `infra/tiles/sprites/v4/` (khoảng 180 KB).

  Mọi thứ nằm trong `.gitignore`. Ngày build và `SHA256SUMS` được ghi vào `infra/tiles/BUILD.txt`; lần chạy sau thấy file đã có thì không tải lại.
- **Phục vụ:** compose mount `infra/tiles/` vào `/usr/share/nginx/html/tiles/` của container `frontend` (chỉ đọc). Nginx trả `Accept-Ranges: bytes` mặc định; thêm `location /tiles/ { add_header Cache-Control "public, max-age=86400"; }`.
- **Style:** dựng lúc chạy bằng `@protomaps/basemaps` 5.x (thư viện runtime, khoảng 7 KB gzip): `layers("protomaps", namedFlavor(flavor), { lang: "en" })`, với `flavor` là **`grayscale`** cho theme sáng và **`black`** cho theme tối. Đây là hai flavor trung tính có sẵn nên không cần tự ghi đè màu, và màu tuyến cùng màu trạng thái nổi bật trên nền (DOC-35 §6; S-05 đã chụp cả hai). Code nằm ở `frontend/src/features/map/baseStyle.ts`. Glyph lấy từ `/tiles/fonts/{fontstack}/{range}.pbf`, sprite từ `/tiles/sprites/v4/<flavor>`. Không có request nào ra ngoài (S-05).
- **Worker của MapLibre 6:** MapLibre 6 chỉ phát hành dạng ESM, và mặc định tìm worker ở `./maplibre-gl-worker.mjs` cạnh bundle. Sau khi Vite build thì file này không tồn tại. `main.tsx` gọi `maplibregl.setWorkerUrl(workerUrl)` với `import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url"`: Vite gom worker cùng phần code dùng chung thành một asset cùng origin (S-05).
- **Chọn nguồn lúc chạy:** biến `PTI_MAP_STYLE` của container `frontend` (`offline` mặc định, hoặc `online`) được render vào `env.js`. `online` dùng `https://tiles.openfreemap.org/styles/positron` và yêu cầu `PTI_MAP_TILE_ORIGINS=https://tiles.openfreemap.org` cho CSP (DOC-27 §5.3). Vite dev server dùng `frontend/public/env.js` đã commit với `mapStyle: "online"` (DOC-29 §3.5); muốn thử offline khi dev thì sửa thành `offline` và chạy `make tiles` (Vite phục vụ `/tiles/` qua symlink `public/tiles → ../../infra/tiles`, cũng gitignored).
- **Thiếu file:** frontend gọi `HEAD /tiles/twin-cities.pmtiles` và `HEAD /tiles/sprites/v4/grayscale.json` lúc khởi tạo bản đồ; một trong hai trả 404 thì hiển thị bản đồ không có nền (chỉ tuyến, trạm, xe trên nền màu trơn) kèm thông báo "Base map unavailable. Run `make tiles` to download it." (DOC-37), không chặn các lớp dữ liệu.
- **Attribution:** "© OpenStreetMap contributors · Protomaps" luôn hiển thị ở góc bản đồ (yêu cầu của ODbL).

## Kết quả spike S-05

Spike chạy ngày 2026-09-28 trong `spikes/s05-pmtiles/`, với go-pmtiles 1.31.2, bản build `20260927` (tile basemap v4.15.2), maplibre-gl 6.11.2, pmtiles 4.5.0, @protomaps/basemaps 5.7.2, Vite 8.3.1, `nginx:1.30-alpine` (1.30.5).

| Câu hỏi | Kết quả |
| --- | --- |
| Kích thước | 83.916.795 byte (80,0 MiB), 9.403 tile, maxzoom 15 |
| Offline | Headless Chrome với mọi tên miền ngoài `localhost` bị chặn: flavor `light`, `dark`, `grayscale`, `black`, zoom 9,7 (toàn bbox) tới 18 (overzoom từ tile z15) đều render xong, không lỗi, **0 request ra ngoài** |
| Range request qua nginx | `Accept-Ranges: bytes`, 206; khoảng 3–14 range request cho một khung nhìn |
| CSP | Chạy với `worker-src 'self'` và `img-src 'self' data:`, không cần `blob:` (DR-82) |
| Build bằng Vite | Cần `setWorkerUrl` như trên; không có thì worker 404. Chunk chính (MapLibre, pmtiles, basemaps) 289,5 KB gzip, dưới ngân sách 330 KB (DOC-34 §7) |
| Glyph | Nhãn Latin chỉ cần dải `0-255` và `8192-8447`; vẫn giữ đủ các dải để nhãn không phải chữ Latin không mất ký tự |

## Hệ quả

**Tích cực**

- Demo chạy hoàn toàn offline; không có key hay tài khoản bên thứ ba.
- Không thêm container: nginx của frontend vừa phục vụ SPA vừa phục vụ tile.
- Vector tile cho phép style sáng/tối và nhãn tiếng Anh thống nhất với UI.

**Tiêu cực**

- Khoảng 97 MB (tile 84 MB, font 13 MB) phải tải một lần bằng `make tiles`; máy mới không có internet thì chỉ có bản đồ không nền.
- Bản build hằng ngày của Protomaps chỉ được giữ trên server một thời gian ngắn, nên không tái tạo được đúng một bản cũ; checksum trong `BUILD.txt` chỉ để biết bản đang dùng.
- Tile là ảnh chụp tại một thời điểm, không tự cập nhật (chấp nhận được cho demo).
- `pmtiles extract` cần cài CLI `go-pmtiles` (thêm vào `mise`, DOC-38) hoặc chạy qua image Docker `protomaps/go-pmtiles`.
