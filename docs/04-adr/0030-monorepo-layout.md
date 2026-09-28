# ADR-0030: Monorepo, chia thư mục gốc theo stack: `backend/`, `frontend/`, `deploy/`

- Trạng thái: Accepted
- Ngày: 2026-09-28 · Liên quan: DR-85, DR-26, DR-44, DR-56, ADR-0014, ADR-0021, ADR-0027, ADR-0028, DOC-38, DOC-39, DOC-40, DOC-41, SDD phụ lục "Cấu trúc repo"

## Bối cảnh

Dự án có bốn loại code với bốn công cụ build khác nhau: Java (Gradle, 8 module theo DR-26), frontend (pnpm, Vite), hạ tầng (compose, k3d, Helm, Dockerfile của Kafka Connect, manifest Chaos Mesh) và runner thực nghiệm (Python, uv). Ba thứ đầu có hợp đồng dùng chung:

- `openapi.json` do `api` sinh ra, frontend sinh type từ file này (DR-44).
- JSON Schema của message nằm trong `common`, contract test so với producer và consumer (ADR-0027).
- `topics.yaml` và cấu hình connector được compose, Helm chart và test backend cùng đọc (DOC-39 §1, DOC-40 §1.1, DOC-44 §9.2).

Phụ lục "Cấu trúc repo" của SDD gốc đặt mọi module Gradle ngay ở gốc, ngang hàng với `frontend/`, `connect/`, `chaos/`, `observability/` và `deploy/`. Phụ lục này đã cũ (Java 21, module `triage/`, một workflow `ci.yml`). Tài liệu viết sau đó còn đặt thêm thư mục `infra/` cho bản đồ nền (DR-82), nên hạ tầng bị chia ra bốn thư mục gốc. Người làm chỉ có một, nên muốn một repo duy nhất, một lệnh `make` ở gốc, và thấy ngay mỗi thư mục thuộc stack nào.

## Các phương án

1. **Monorepo, module Gradle ở gốc** (theo phụ lục SDD). Gốc repo có khoảng 15 thư mục lẫn giữa Java, frontend và hạ tầng. Path filter của CI phải liệt kê từng module. Mở gốc repo trong IDE thì Gradle import luôn cả `frontend/` và `deploy/`.
2. **Monorepo, chia gốc theo stack:** `backend/` (toàn bộ Gradle build), `frontend/`, `deploy/` (mọi thứ hạ tầng), `experiments/`. Mỗi stack có một thư mục, và mỗi thư mục có đúng một công cụ build.
3. **Polyrepo** (backend, frontend, infra mỗi thứ một repo). Ba hợp đồng ở trên phải đồng bộ giữa các repo, nên một thay đổi API kèm sửa frontend phải tách thành nhiều PR. Helm chart không symlink được sang repo khác. Contract test Debezium cần Dockerfile và file connector của repo infra. CI phải kéo chéo repo. Một người làm thì chỉ thấy thêm chi phí.
4. **Monorepo với công cụ điều phối** (Nx, Turborepo, Bazel). Có cache và affected graph xuyên stack, nhưng Gradle, pnpm và uv đã tự làm phần đó cho stack của mình. Thêm một lớp công cụ phải học và cấu hình, trong khi `Makefile` cộng path filter của CI đã đủ.

## Quyết định

Chọn **phương án 2**.

```
public-transport-intelligence/
  backend/                          # Gradle build (root project); no Gradle file at repo root
    settings.gradle.kts             # includeBuild("build-logic"), include(common, analytics, etl, …)
    gradle.properties
    gradlew, gradlew.bat, gradle/wrapper/
    gradle/libs.versions.toml       # version catalog (DOC-11)
    build-logic/                    # convention plugins: Java 25, Spotless, Checkstyle, SpotBugs, JaCoCo, Jib
    common/                         # envelope, DTO, JSON Schema, business key, test fixtures
    analytics/                      # library (ADR-0014)
    etl/                            # app, profiles stream | batch
    triage-worker/                  # app
    api/                            # app; api/openapi.json is generated and committed (DR-44)
    source-simulator/               # app
    db/                             # Flyway migrations + runner (ADR-0024)
  frontend/                         # pnpm + Vite (ADR-0020): package.json, pnpm-lock.yaml, src/, e2e/, Dockerfile
  deploy/                           # the only infrastructure root
    versions.env                    # image tags/digests, chart versions (DOC-11)
    topics.yaml                     # topic list (DOC-09 §1)
    compose/                        # DOC-39
    k3d/                            # DOC-40
    helmfile.yaml.gotmpl
    helm/                           # charts pti-infra, pti; operator values
    connect/                        # Dockerfile, Dockerfile.strimzi, connectors/*.json
    chaos/                          # Chaos Mesh manifests (EXP-08)
    tiles/                          # fetch.sh; PMTiles, fonts, sprites are gitignored (ADR-0021)
  experiments/                      # Python runner pti-exp (ADR-0025)
  docs/                             # Vietnamese documentation
  spikes/                           # S-xx spikes, standalone, not part of any build
  sample-data/gtfs/                 # real feed and SHA256SUMS
  .github/                          # workflows/, dependabot.yml, PULL_REQUEST_TEMPLATE.md
  Makefile                          # single entry point for every stack (DOC-38 §4)
  mise.toml                         # pinned tool versions (DOC-11 §5)
  .editorconfig, .gitignore, .gitleaks.toml, README.md, CONTRIBUTING.md, LICENSE
  public-transport-intelligence.md  # original SDD, kept unchanged
```

Quy tắc:

- **Gradle build nằm trọn trong `backend/`.** Gốc repo không có `settings.gradle.kts` hay `gradlew`. Makefile gọi `backend/gradlew -p backend`; job CI đặt `working-directory: backend`. Trong mọi tài liệu, lệnh `./gradlew …` được hiểu là chạy trong `backend/`. Tên module và đường dẫn Gradle (`:etl`, `:api`…) giữ nguyên, tên image cũng vậy (`pti-etl`, `pti-api`…).
- **Mọi thứ hạ tầng nằm dưới `deploy/`.** Không có thư mục gốc `infra/`, `connect/`, `chaos/` hay `observability/`. Symlink của Helm chart (DOC-40 §1.1) chỉ trỏ trong `deploy/`, nên cả thư mục mang đi được nguyên khối.
- **Backend đọc file ngoài `backend/` qua một điểm duy nhất.** `build-logic` đặt system property `pti.repo-root` (thư mục cha của `backend/`) cho mọi task test. Test chỉ đọc qua property này, và chỉ đọc ba chỗ: `deploy/topics.yaml`, `deploy/connect/` (contract test Debezium) và `sample-data/gtfs/` (test `slow`). Path filter `backend` của CI gồm `backend/**`, `deploy/topics.yaml` và `deploy/connect/**` (DOC-41 §2).
- **Frontend chỉ đọc một file của backend:** `pnpm gen:api` sinh type từ `../backend/api/openapi.json`. Frontend không import hay build gì khác từ `backend/`.
- **`experiments/` đứng riêng ở gốc**, vì nó không thuộc stack nào: runner gọi API, `kubectl` và đọc Prometheus của cả hai môi trường.
- **Mọi thay đổi xuyên stack đi trong một PR**, ví dụ sửa endpoint cùng type frontend, hay thêm topic cùng consumer. Đây là lý do chính để chọn monorepo.

## Hệ quả

**Tích cực**

- Gốc repo chỉ còn bốn thư mục code, nhìn tên là biết stack. Path filter của CI rút từ mười mẫu xuống còn một mẫu cho mỗi stack.
- IDE import `backend/` như một Gradle project, không kéo theo `node_modules` hay chart Helm. `frontend/` và `experiments/` mở thành project riêng.
- Dependabot chỉ cần một thư mục cho mỗi ecosystem (`/backend`, `/frontend`, `/experiments`, `/deploy/connect`).
- Hợp đồng dùng chung (OpenAPI, JSON Schema, `topics.yaml`) có một bản duy nhất, và đổi hợp đồng cùng với code dùng nó trong một commit.

**Tiêu cực**

- Không gõ `./gradlew` ở gốc repo được, phải `cd backend` hoặc dùng `make`. Bù lại, mọi thao tác hằng ngày đều có lệnh `make` (DOC-38 §4).
- Đường dẫn file Java dài thêm một cấp (`backend/etl/src/main/…`).
- Test backend phụ thuộc ba chỗ ngoài `backend/`. Nếu quên property `pti.repo-root` và đọc bằng đường dẫn tương đối thì test chạy được trong IDE nhưng hỏng khi Gradle chạy với thư mục làm việc khác. `build-logic` fail sớm nếu thiếu property.
- Lệch phụ lục "Cấu trúc repo" của SDD gốc. ADR này thay cho phụ lục đó; SDD gốc giữ nguyên (DR-85).
