# ADR-0025: Chạy thực nghiệm bằng Python (uv), tách khỏi mã ứng dụng

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: DR-27, DR-28, DR-52, DR-58, NFR-01…04, NFR-08, NFR-09, DOC-45

## Bối cảnh

EXP-01…08 phải chạy lặp nhiều lần (EXP-01 ≥ 30 lần), mỗi lần điều khiển nhiều thứ ngoài JVM: kill container hay pod, đổi tải simulator, xóa warehouse, gọi API replay, silence alert, rồi so sánh warehouse với ledger (DR-28) và tính checksum (DR-58). Kết quả cần thống kê (trung bình, khoảng tin cậy, phân vị) và biểu đồ cho báo cáo. Yêu cầu:

- Tái lập được: mỗi lần chạy ghi lại git SHA, tham số, môi trường.
- Không lẫn vào mã ứng dụng (không có đường code "thực nghiệm" trong app ngoài profile `experiment`, DR-27).
- Điều khiển được cả Docker Compose và k3d.

## Các phương án

1. **Python 3.12 + uv** với httpx, psycopg 3, docker SDK, `kubectl` qua subprocess, pandas, matplotlib, typer.
2. **JUnit/Testcontainers trong Gradle.** Cùng ngôn ngữ với app, nhưng test framework không hợp với tác vụ chạy dài 10–60 phút, thống kê và vẽ biểu đồ; Testcontainers không điều khiển được compose đang chạy hay k3d.
3. **Shell script + k6.** k6 tốt cho tạo tải HTTP, nhưng tải ở đây do simulator sinh qua Kafka; shell khó bảo trì khi logic so sánh ledger phức tạp.
4. **Chaos framework (Chaos Mesh, Litmus) làm runner.** Có trên k3d (EXP-08 dùng Chaos Mesh để tiêm lỗi), nhưng không có trên compose và không tính được chỉ số mất/trùng.

## Quyết định

Chọn **phương án 1**, đặt trong thư mục `experiments/` (dự án uv riêng, `pyproject.toml` + `uv.lock`, Python 3.12):

- CLI `pti-exp` (typer): `pti-exp run EXP-01 --variant kill-external --runs 30 --seed 1000 [--env compose|k3d]`, `pti-exp analyze EXP-01`, `pti-exp report`. Chế độ normal và baseline chạy **song song** trong cùng một lần chạy (DR-27, DOC-45 README §5), nên không có cờ chọn chế độ.
- Thư viện chung `pti_exp/`: điều khiển simulator (API `/sim/*`), điều khiển hạ tầng (docker SDK cho compose, `kubectl` và CR Chaos Mesh cho k3d), truy vấn ledger và warehouse (psycopg), checksum DR-58, Alertmanager silence, Grafana annotation, đọc lag bằng `kafka-consumer-groups.sh` qua `docker exec`/`kubectl exec`.
- Mỗi lần chạy lưu `experiments/results/<EXP>/<run_id>/` gồm `config.json` (git SHA, tham số, môi trường, digest image), `timeseries.csv.gz`, `ledger.csv.gz`, `summary.json`, biểu đồ PNG (DOC-45 README §7). Với các lần chạy chính thức (P3-08), file nhỏ được commit để báo cáo trích dẫn được, file lớn được gói theo chuỗi và lưu ở GitHub Release (DR-94, DOC-45 README §7.1).
- Test logic của runner bằng pytest trên dữ liệu giả; CI chạy `ruff` và `pytest` (DOC-41 §2).
- Thực nghiệm chính thức chạy trên một máy thực nghiệm dành riêng (DR-94, DOC-45 README §1.2); không chạy trên runner CI, vì runner dùng chung hạ tầng với người khác nên số đo hiệu năng không ổn định. CI chỉ dùng lệnh `check` (E2E-DEMO-12, 13), vốn chỉ kiểm đúng hay sai, không đo thời gian.

## Hệ quả

**Tích cực**

- Thống kê và biểu đồ dùng thư viện chuẩn (pandas, matplotlib), kết quả đưa thẳng vào báo cáo.
- Cùng một runner cho compose và k3d; chỉ lớp điều khiển hạ tầng khác nhau.
- App không chứa code thực nghiệm; ranh giới là API simulator, API public và cờ của profile `experiment`.

**Tiêu cực**

- Thêm một ngôn ngữ và toolchain (uv, ruff, pytest) vào repo và CI.
- Công thức checksum DR-58 có hai bản hiện thực (Java trong `WarehouseAssert` của test, SQL gọi từ Python); cả hai phải dùng cùng file SQL `experiments/sql/checksum/<table>.sql` để không lệch (DOC-45).
- Runner cần quyền Docker/kubectl. Với DB, runner chỉ dùng role chỉ đọc `experiment_runner` (thêm `TRUNCATE exp.*`); thao tác phá hủy hoặc ghi warehouse đi qua lệnh `make reset-warehouse` và `make replay`, dùng credential bootstrap trong `.env` (DOC-38 §4). Vì vậy runner chỉ chạy trên máy có `.env` của môi trường thử, không bao giờ trỏ vào môi trường có dữ liệu thật.
