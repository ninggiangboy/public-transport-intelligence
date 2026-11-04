# ADR-0033: Xe trong Live map 3D vẽ bằng custom layer của MapLibre

- Trạng thái: Accepted (đã xác minh ở S-07, 2026-11-05)
- Ngày: 2026-11-05 · Liên quan: DR-111, DR-107, ADR-0020, ADR-0021, DOC-35 §6.4, S-07 (`spikes/s07-map-3d/`)

## Bối cảnh

DR-111 thêm chế độ xem 3D cho Live map (`view=3d`): toà nhà nổi khối và xe chạy mượt giữa hai fix GPS. DR-111 để lại một câu hỏi: vẽ xe bằng (a) custom layer WebGL 2 của MapLibre, nội suy trên CPU rồi đẩy một buffer instance mỗi khung hình, hay (b) deck.gl `MapboxOverlay` với `SimpleMeshLayer`. (Phương án `fill-extrusion` cho từng xe đã bị loại ở DR-111: `setData` mỗi khung hình không giữ được 60 fps với 600 xe.) Ngân sách của chunk `/map` là 360 KB (DR-107), mã của 3D phải nằm ngoài chunk đó.

Spike S-07 dựng cả hai trên file `make tiles` thật, cùng một mô-đun nội suy theo luật DR-111, 600 xe trên 40 tuyến giả lập, pitch 60°, MapLibre 6.12.0, deck.gl 9.4.1.

## Các số đo (Apple M2, Chrome 156 có cửa sổ, GPU thật; một máy)

| | (a) custom layer | (b) deck.gl `MapboxOverlay` |
| --- | --- | --- |
| fps trần 60 Hz, 30 s × 2 lần | 60 trung bình, thấp nhất 59 | 60 trung bình, thấp nhất 59–60 |
| fps không giới hạn (`--disable-gpu-vsync`) | 295–301 trung bình, thấp nhất 247–266 | 229–259 trung bình, thấp nhất 199–231 |
| Chunk 3D, gzip -9 | **3.244 B** (nội suy 1.322 + lớp 1.922) | **221.201 B** (nội suy 1.322 + mã nối 805 + deck.gl 219.074) |
| Toà nhà che xe | Có (cùng framebuffer và depth test) | Không ở chế độ `interleaved: false`; `interleaved: true` ném `TypeError` mỗi khung hình trên maplibre-gl 6 |

Tỉ lệ toà nhà có `height` ở zoom 15: **6,2 %** trong khung nhìn trung tâm Minneapolis (4.404 trên 71.546), **8,8 %** trên cả file (96.770 trên 1.104.739). `min_height` hiếm (3 và 15). Trung vị của nhà có `height` là 8 m, p95 là 47 m ở trung tâm, tối đa 241 m.

## Quyết định

Chọn **(a), custom layer của MapLibre**.

- Cả hai đều vượt xa 60 fps với 600 xe, nên fps không phân thắng bại; chunk và độ đúng mới phân. Custom layer nhỏ hơn khoảng 68 lần (3,2 KB so với 221 KB gzip), che xe bằng toà nhà đúng, và không có đường lỗi trên MapLibre 6.
- **Ngân sách chunk 3D:** tối đa **15 KB gzip** cho cả mã riêng của 3D (nội suy, lớp xe, mô hình, nhãn, camera, lối lui), tải bằng `import()` khi vào `view=3d`. Spike đo 3,2 KB với mô hình hộp; mô hình xe buýt/tàu, đĩa nhãn và bắt chọn (picking) sẽ thêm vào. `pnpm check:bundle` kiểm chunk này.
- Không thêm thư viện; deck.gl không vào `package.json`.
- Nội suy là hàm thuần trong một mô-đun riêng (luật DR-111), test được bằng Vitest không cần WebGL.

## Hệ quả

- Phải tự viết: shader, mô hình xe và tàu, đĩa nhãn luôn quay về camera, bắt chọn xe (đọc lại vị trí màn hình của xe để dò click). Spike chưa làm các phần này, nên ngân sách 15 KB cần đo lại ở P5-18.
- MapLibre 6 đổi chữ ký `render(gl, options)`: ma trận chiếu là `options.defaultProjectionData.mainMatrix` (đơn vị mercator). Nâng MapLibre lớn phải chạy lại test của lớp xe.
- **Toà nhà thấp phần lớn:** chỉ 6–9 % toà nhà có `height`, còn lại dùng mặc định 8 m. Trung tâm vẫn có nhà cao nhờ vài trăm toà có tag, nhưng vùng ngoại ô là những khối phẳng đều nhau. Chấp nhận (DR-111 đã đặt mặc định 8 m, và không có nguồn dữ liệu khác offline); AC-12 chỉ đòi toà nhà nổi khối.
- Số đo fps từ một máy (Apple M2). AC-17 (≥ 50 fps) cần chạy lại trên máy demo; lối lui dưới 30 fps của DR-111 vẫn là lưới an toàn.
