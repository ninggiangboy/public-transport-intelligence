# ADR-0028: Kubernetes cục bộ bằng k3d, operators cài qua helmfile

- Trạng thái: Accepted
- Ngày: 2026-09-27 · Liên quan: SDD §12.4, §12.7, §12.8, DR-54, DR-55, DR-56, DR-66, DR-74, ADR-0014, ADR-0022, ADR-0024, DOC-10 §4–5, DOC-40, EXP-07, EXP-08

## Bối cảnh

P7 cần một môi trường Kubernetes để chứng minh NFR-08 (mở rộng theo tải) và NFR-09 (chịu lỗi): nhiều pod consumer theo lag, 3 Kafka broker, Postgres có failover, tiêm lỗi có kiểm soát. Ràng buộc:

- Một máy 16 GB, 8 CPU, macOS; Docker chạy trong VM (OrbStack hoặc Docker Desktop). Compose phải tắt khi chạy cluster (DOC-10 §5).
- Một người làm, nên cài đặt phải tái lập được bằng một lệnh và gỡ sạch được.
- Image app build bằng Jib; repo GitHub public, image public trên GHCR (DR-56).
- Secret phải commit được ở dạng mã hóa, không dùng dịch vụ ngoài.

## Các phương án

**Cluster:**

1. **k3d** (k3s trong Docker): khởi động khoảng 30 giây, nhiều node trên một máy, có registry cục bộ tích hợp, có sẵn Traefik và local-path provisioner, giới hạn RAM theo node (`--agents-memory`).
2. **kind:** gần upstream hơn, nhưng không có ingress và storage mặc định, không giới hạn RAM theo node.
3. **minikube:** một node là chính; nhiều node chậm và tốn RAM hơn.

**Hạ tầng trong cluster:**

1. **Operators** (Strimzi, CloudNativePG, KEDA, Chaos Mesh, Sealed Secrets, kube-prometheus-stack): failover, rolling restart broker, managed roles và PgBouncer có sẵn, khai báo bằng CR.
2. **Chart thường** (Bitnami Kafka, Postgres): không có failover Postgres tự động; từ 2025 Bitnami chuyển phần lớn image sang gói trả phí, bản miễn phí không còn được cập nhật.
3. **Manifest tự viết:** toàn quyền nhưng tự làm failover và replication là ngoài phạm vi.

**Cách cài:**

1. **helmfile:** một file khai báo mọi release, thứ tự (`needs`), môi trường (`dev`, `staging`, `lite`), `helmfile apply` idempotent.
2. **Argo CD / Flux:** GitOps đúng nghĩa nhưng thêm controller tốn RAM và cần repo truy cập được từ cluster.
3. **Script `helm install` nối tiếp:** khó giữ idempotent và thứ tự.

**Secret:**

1. **Sealed Secrets:** file mã hóa commit được, controller nhẹ, không phụ thuộc dịch vụ ngoài.
2. **SOPS + age:** mã hóa trong repo, nhưng cần plugin cho helmfile và khóa ở mọi nơi deploy.
3. **External Secrets:** cần một secret store (Vault, cloud), không có trên máy dev.

## Quyết định

- **k3d**, 1 server + 2 agent, giới hạn RAM theo node, registry cục bộ `k3d-pti-registry:5000` (DR-56). Cấu hình trong `deploy/k3d/cluster.yaml`.
- **Operators:** Strimzi (Kafka KRaft, node pool, Kafka Connect, KafkaTopic, KafkaConnector), CloudNativePG (warehouse 1 primary + 1 replica với `Pooler` PgBouncer; nguồn ticketing 1 instance, DR-55), KEDA, Chaos Mesh, Sealed Secrets, kube-prometheus-stack; Loki, Tempo, Alloy và OTel Collector bằng chart chính thức (ADR-0022).
- **SeaweedFS** chạy như compose: một StatefulSet một container `server -s3` (DR-66), không dùng chart chính thức của SeaweedFS vì chart tách master, volume, filer và S3 thành nhiều pod, tốn RAM mà không thêm gì cho mục tiêu thực nghiệm.
- **helmfile** với ba môi trường `dev`, `staging`, `lite`. Ba lớp release: operators → chart `pti-infra` (CR hạ tầng) → chart `pti` (app). Hook `postsync` của `pti-infra` chờ CR `Ready` (`kubectl wait`) trước khi `pti` được cài, vì Helm `--wait` không hiểu trạng thái của CR.
- **Chart `pti`** là umbrella theo nghĩa của DR-54 nhưng hiện thực bằng **một chart với named template dùng chung** (`_app.tpl`) thay vì subchart cho từng app: sáu app giống nhau tới 90% (Deployment, Service, probe, ServiceMonitor, PDB), nên một template dễ giữ đồng bộ hơn sáu subchart.
- **Sealed Secrets** với khóa niêm phong **do người dùng giữ** (bring-your-own key, ngoài repo), cài vào controller trước khi controller khởi động. Nhờ vậy file đã niêm phong trong repo vẫn giải mã được sau khi xóa và tạo lại cluster; CI nhận cùng khóa qua GitHub Secrets (DOC-41).
- Mọi phiên bản operator và chart được pin trong `helmfile.yaml.gotmpl`, cùng nguồn `deploy/versions.env` với compose (DOC-11).

## Hệ quả

**Tích cực**

- `make k8s-up` dựng cluster, operators, hạ tầng và app từ đầu; `make k8s-down` xóa sạch. Mỗi lần thực nghiệm có thể bắt đầu từ cluster mới.
- Failover Postgres, rolling restart broker, scale theo lag và tiêm lỗi đều khai báo bằng CR, nên EXP-07 và EXP-08 điều khiển bằng `kubectl` là đủ.
- Cùng image, cùng cấu hình Spring với compose; khác biệt chỉ nằm ở values (DOC-40 §5).

**Tiêu cực**

- k3d chạy mọi node trên một VM: "node" không độc lập về phần cứng. Sự cố máy thật (mất đĩa, mất mạng giữa máy) không mô phỏng được; ghi vào mối đe dọa của EXP-08.
- Nhiều operator tốn khoảng 1,5 GB RAM chỉ để tồn tại. Values `lite` bỏ Keycloak, Loki, Tempo, Alloy và OTel Collector để vừa máy 16 GB.
- Tên field của CR (Strimzi, CNPG, KEDA) đổi theo phiên bản; phải xác minh lại khi pin phiên bản ở P7-02 và ghi vào DOC-11.
- Mất khóa niêm phong thì phải niêm phong lại mọi Secret từ `.env` (`make k8s-seal`); RB-12 có quy trình.
