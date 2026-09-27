# Đặc tả màn hình

> Trạng thái: **Review** · Cập nhật: 2026-09-27 · DOC-36
> Phụ thuộc: DOC-34, DOC-35, DOC-37, DOC-32, DOC-33, DOC-26 §8–9
> Người dùng chính: P5-04…P5-14

Mỗi màn hình một file, theo template phụ lục A.5 của master plan. Quy ước chung cho mọi file:

- Chuỗi trong ngoặc kép là chữ hiển thị thật (tiếng Anh, DR-48). Nhãn enum, lỗi, trạng thái chung **không** lặp lại ở đây mà tham chiếu DOC-37.
- Component tham chiếu DOC-35 §5; token màu DOC-35 §3.
- "Dữ liệu" ghi query key theo DOC-26 §9 và `src/api/keys.ts`. Mọi query có `refetchInterval` 60 s khi trang hiển thị (DOC-34 §9.2), trừ khi file ghi khác.
- Tiêu chí nghiệm thu viết dạng Given/When/Then, mã `AC-<n>` riêng trong từng file. Ca E2E có ID `E2E-<MÀN>-<nn>` (DOC-44 §4.3), chạy trên compose với Keycloak thật và dựng trạng thái qua API simulator.
- Người dùng demo của realm `pti` (DOC-27 §3.1): `viewer`, `operator`.

| File | Màn hình | Route | Quyền | FR / UC | Việc |
| --- | --- | --- | --- | --- | --- |
| [shell-and-navigation.md](shell-and-navigation.md) | Khung, điều hướng, đăng nhập | mọi route | mọi người | FR-11.6, P5-04 | P5-04 |
| [live-map.md](live-map.md) | Live map | `/map` | mọi người | FR-11.1; UC-01, UC-03, UC-04 | P5-06 |
| [stop-detail.md](stop-detail.md) | Tìm trạm, chi tiết trạm | `/stops`, `/stops/$stopId` | mọi người | FR-11.2; UC-02, UC-03 | P5-07 |
| [route-scorecard.md](route-scorecard.md) | Scorecard, chi tiết tuyến | `/scorecard`, `/scorecard/$routeId` | viewer | FR-11.3; UC-06 | P5-08 |
| [alert-feed.md](alert-feed.md) | Alerts | `/alerts` | mọi người | FR-11.5; UC-03, UC-04, UC-05 | P5-12 |
| [ops-console-jobs.md](ops-console-jobs.md) | Jobs, batch lineage | `/ops/jobs`, `/ops/batches/$batchId` | viewer | FR-11.4; UC-07 | P5-09 |
| [ops-console-dlq.md](ops-console-dlq.md) | Dead letters | `/ops/dlq` | viewer | FR-11.4; UC-08, UC-09 | P5-10 |
| [ops-console-replay.md](ops-console-replay.md) | Replay raw zone | `/ops/replay` | viewer | FR-11.4; UC-10 | P5-11 |
| [ops-console-controls.md](ops-console-controls.md) | Cờ vận hành, feed, chạy job | `/ops/controls` | viewer | FR-11.4, FR-15.1; UC-11, UC-14 | P5-11 |
| [ops-console-ticketing.md](ops-console-ticketing.md) | Bất thường ticketing | `/ops/ticketing` | viewer | FR-11.4; UC-12 | P5-11 |
| [demo-control.md](demo-control.md) | Điều khiển kịch bản | `/ops/demo` | operator, profile `demo` | FR-11.7; UC-16 | P5-13 |

Việc P5-14 (trạng thái cho mọi màn hình, axe) kiểm các mục "Trạng thái" của từng file.
