# Kịch bản demo

> Trạng thái: **Approved** · Cập nhật: 2026-09-28 · DOC-46
> Phụ thuộc: SDD §14.1, [DOC-25](../06-design/source-simulator.md) §7, [DOC-24](../06-design/ai-triage.md) §6.4, [DOC-27](../06-design/security.md) §3.1, [DOC-36](../08-ux-ui/screens/README.md) (Live map, Alert feed, Route scorecard, Dead letters, Pipeline, Demo scenarios), [DOC-38](../09-operations/local-dev.md) §4, [DOC-39](../09-operations/deploy-compose.md) §2, [DOC-40](../09-operations/deploy-k8s.md) §13–14, [DOC-45](experiments/README.md), [EXP-07](experiments/EXP-07-autoscaling.md), [EXP-08](experiments/EXP-08-chaos.md), [DOC-48](../06-design/demo-console.md), [DR](../00-decision-register.md) (DR-47, DR-49, DR-87)
> Người dùng chính: người trình bày (PS-5), P8-04, P8-05, P8-08

Buổi demo có hai phần trên cùng một máy 16 GB:

- **Phần A** (bước 1–6, compose `make up-demo`, khoảng 18 phút);
- **Phần B** (bước 7, k3d `lite`, khoảng 8 phút, sau khi chuyển môi trường).

Hai môi trường không chạy cùng lúc được (DOC-10 §5). Cluster k3d được dựng sẵn từ trước rồi **dừng** (`make k8s-stop`); lúc chuyển chỉ cần khởi động lại cluster đã có, nhanh hơn và không cần Internet (§6).

Màn hình chiếu chính là **demo console** (DOC-48, `http://localhost:8095`): runbook các bước ở bên trái, sơ đồ kiến trúc sống ở giữa, timeline sự kiện ở dưới. Mỗi thao tác trong tài liệu này ghi nút trên console, kèm lệnh `make` tương đương trong ngoặc. Nếu console không có (P8-08 bị cắt) hoặc hỏng giữa buổi, dùng lệnh `make` trong terminal và Grafana thay thế (§5). Các bước 2, 3, 4 và 6 vẫn chiếu trên màn hình sản phẩm, vì đó là thứ đang được đánh giá; console có liên kết sang đúng màn hình ở từng bước.

Giao diện hiển thị tiếng Anh; chuỗi trong ngoặc kép là chuỗi đúng trên UI. Lời thoại viết tiếng Việt cho buổi bảo vệ.

## 1. Chuẩn bị

### 1.1 Một ngày trước

- [ ] Commit sạch, đã tag phiên bản demo; `full-stack.yml` xanh trên đúng commit (DOC-41 §10.3).
- [ ] `make images` và `make k8s-images` đã chạy trên commit đó; `make tiles` đã có `deploy/tiles/twin-cities.pmtiles`.
- [ ] Dựng k3d một lần: `make down && make k8s-up ENV=lite`, `make k8s-smoke` pass, rồi `make k8s-stop`.
- [ ] `make demo-console` mở được console trên compose, và "Switch to k3d" chạy được khi cluster đang bật (DC-13, DC-14 đạt ở lần diễn tập gần nhất).
- [ ] Diễn tập toàn bộ ít nhất một lần theo §7 (P8-05 yêu cầu ba lần trước buổi thật); ghi thời gian từng bước vào §8.
- [ ] Bản ghi màn hình bước 7 (phương án dự phòng, §5) đã quay ở lần diễn tập gần nhất, lưu ở `~/pti-demo/recordings/step7.mp4` (ngoài repo).
- [ ] Máy cắm sạc; tắt thông báo hệ điều hành, tắt cập nhật tự động; màn hình chiếu 1920×1080, zoom trình duyệt 110%.

### 1.2 Bốn mươi lăm phút trước

| Mốc | Việc | Kiểm tra |
| --- | --- | --- |
| T−45 | `make demo-reset`: `make reset`, `make clock-offset AT=16:20` (giờ nghiệp vụ cao điểm chiều, ≥ 500 xe; DOC-38 §3.1), `make up-demo`, `make smoke` | `make smoke` pass; `make sim-status` có `activeVehicles ≥ 500` |
| T−40 | `make demo-console` trong một terminal riêng; mở sẵn các tab trình duyệt (§1.3); đăng nhập `operator` / `operator` (DOC-27 §3.1) ở cửa sổ chính, cửa sổ ẩn danh để xem góc nhìn hành khách | UI hiện "Demo Operator" ở góc phải; console hiện "compose", mọi node xanh, không có chấm đỏ ở thanh trên |
| T−30 | Ấm máy: để hệ thống chạy ở ×1 | Grafana "Pipeline overview": lag ổn định, p95 end-to-end < 10 s |
| T−12 | Console: "Seed scenarios" (`make demo-prewarm`, §1.4) | Timeline có hai dòng "Scenario … started"; Demo scenarios (`/ops/demo`) có hai thẻ lần chạy đang chạy |
| T−2 | Chạy E2E-DEMO-11 (§9): `make demo-preflight` | In "READY" |

### 1.3 Tab mở sẵn

0. `http://localhost:8095` (Demo console), tab chiếu chính.
1. `http://localhost:8080/map?route=18` (Live map, tuyến 18).
2. `http://localhost:8080/ops/demo` (Demo scenarios).
3. `http://localhost:8080/alerts`.
4. `http://localhost:8080/scorecard/21?tab=disruptions` (Route scorecard, tuyến 21).
5. `http://localhost:8080/ops/dlq`.
6. `http://localhost:8080/ops/jobs` (Pipeline).
7. `http://localhost:3000/d/pti-overview` (Grafana "Pipeline overview").
8. Terminal ở thư mục repo, font lớn, lịch sử lệnh đã xóa. Dùng cho bước 6 và khi phải quay về lệnh `make` (§5).
9. Cửa sổ ẩn danh: `http://localhost:8080/map` (anonymous).

### 1.4 Gieo kịch bản trước ("Seed scenarios" hoặc `make demo-prewarm`)

`bunching` cần 5–10 phút để hai xe sát nhau (DOC-25 §7.2) và `disruption` cần vài phút để vượt ngưỡng z-score. Chờ trực tiếp trong lúc trình bày là quá lâu. Vì vậy hai kịch bản được bật trước và người trình bày nói rõ điều đó.

Nút "Seed scenarios" và `make demo-prewarm` gọi cùng các request tới simulator (`X-Requested-By: cli`):

```json
POST /sim/scenarios/bunching   {"routeId": "18", "directionId": 0, "pairs": 1, "targetGapRatio": 0.15, "duration": "PT45M"}
POST /sim/scenarios/disruption {"routeId": "21", "extraDelayPerStop": "PT60S", "maxExtraDelay": "PT15M", "duration": "PT40M"}
```

Nếu `bunching` trả 409 `no-eligible-vehicles`, lệnh thử lần lượt các tuyến `5`, `21`, `6` và in tuyến đã chọn. Người trình bày dùng tuyến đó ở bước 2 thay cho 18.

## 2. Tổng quan thời gian

| Bước | Bắt đầu | Thời lượng | Màn hình chính |
| --- | --- | --- | --- |
| 1. Khởi động và live map | T+0 | 2 phút | Demo console, Live map |
| 2. Bunching và gợi ý điều phối | T+2 | 3 phút | Demo console, Live map |
| 3. Gián đoạn | T+5 | 3 phút | Alerts, Route scorecard |
| 4. Dữ liệu lỗi, DLQ, auto-replay | T+8 | 4 phút | Demo console, Dead letters, Pipeline |
| 5. Kill consumer, phục hồi, đối chiếu | T+12 | 3 phút | Demo console, Pipeline |
| 6. Sửa record và replay | T+15 | 3 phút | Dead letters, terminal |
| Chuyển sang k3d (trình bày kết quả EXP trong lúc chờ) | T+18 | ≤ 5 phút | Slide |
| 7. Scale và chịu lỗi trên k3d | T+23 | 8 phút | Demo console, Grafana, Live map |

## 3. Phần A: compose

### Bước 1. Khởi động bằng một lệnh, xe chạy thời gian thực

**Thao tác**

1. Tab 0 (Demo console): sơ đồ kiến trúc với mọi node xanh, mũi tên Simulator → Kafka → etl-stream đang chạy với msg/s. Chỉ vào lệnh `make up-demo` đã chạy trong terminal (không chạy lại).
2. Tab 1 (Live map).

**Kết quả mong đợi**

- Bản đồ Twin Cities với vài trăm xe di chuyển mỗi 5 giây.
- Góc trên có giờ nghiệp vụ "CDT", không có banner "stale".

**Lời thoại**

> "Toàn bộ hệ thống, gồm Kafka, Postgres, ETL, API và giao diện, được dựng bằng một lệnh `make up-demo`. Dữ liệu là lịch chạy GTFS thật của Metro Transit, còn vị trí xe do simulator sinh theo lịch đó. Mỗi chấm là một xe. Từ lúc message vào Kafka tới lúc lên bản đồ mất dưới 10 giây. Đây là NFR-03, đã đo ở EXP-05."

### Bước 2. Bunching → cảnh báo và gợi ý điều phối

**Thao tác**

1. Tab 0 (Demo console): chỉ vào dòng "Scenario bunching · route 18 started" trên timeline, đã gieo từ T−12.
2. Để minh họa thao tác, bấm "Start bunching" (tuyến `5`, mặc định). Timeline có "Scenario bunching · route 5 started". Làm thay trên tab 2 (Demo scenarios) cũng được: thẻ "Bus bunching" → "Start scenario", tuyến `5` trong dialog → "Start scenario".
3. **Đồng thời** (không nói tới lúc này): bấm "Start late delivery" (`ratio` `0.005`, `delay` 6 min, `duration` 1 min; trên Demo control là thẻ "Late delivery"). Kịch bản này phục vụ bước 4.
4. Tab 1, tuyến 18. Bấm xe có vòng halo. Panel "Bus bunching · Route 18 …" mở, với dòng "Suggested action".
5. Bấm "Accept". Toast "Feedback saved".

**Kết quả mong đợi**

- Hai xe tuyến 18 sát nhau, có halo.
- Panel ghi "Gap {gap} · scheduled headway {headway}".
- Có gợi ý điều phối; sau khi bấm, nút đổi thành "Accepted by Demo Operator · {time}".

**Lời thoại**

> "Bunching là khi hai xe cùng tuyến dồn sát nhau. Kịch bản này cần 5 đến 10 phút để hình thành, nên tôi đã bật trước cho tuyến 18 lúc bắt đầu buổi. Tuyến 5 vừa bật sẽ xuất hiện sau vài phút. Hệ thống phát hiện bằng khoảng cách thời gian so với headway theo lịch. Gợi ý điều phối do mô hình quyết định đưa ra. Người vận hành chấp nhận hay bỏ qua, và phản hồi này được lưu lại để đánh giá."

### Bước 3. Gián đoạn → alert feed có nguyên nhân khả dĩ, scorecard phản ánh

**Thao tác**

1. Tab 3 (Alerts). Chọn mục "Service disruption" trên tuyến 21 (audience ở tooltip), khung chi tiết bên phải: "Average delay", "Peak delay", "z-score", dải "Where", khối "AI analysis" với "Likely cause" kèm độ tin cậy và "Data issue probability".
2. Cửa sổ ẩn danh (Live map anonymous): toast "Delays on Route 21 (avg ~{n} min late)". Hành khách thấy cùng gián đoạn.
3. Tab 4 (Route scorecard, tuyến 21), tab "Disruptions": dòng "Ongoing" với "Peak delay", "Peak z", "Cause".

**Kết quả mong đợi**

- Có alert gián đoạn tuyến 21, nguyên nhân đã phân loại (không phải "Cause: not yet classified").
- Anonymous thấy alert vì audience là `PUBLIC`.
- Scorecard có episode đang mở.

**Lời thoại**

> "Gián đoạn được phát hiện theo event time, bằng z-score của độ trễ so với mức bình thường cùng giờ. Lớp AI chỉ làm giàu cảnh báo: nguyên nhân khả dĩ và xác suất đây là lỗi dữ liệu. Nếu xác suất lỗi dữ liệu trên 0,7, cảnh báo sẽ không hiện với hành khách. Ngưỡng này do code quyết định, không phải mô hình (ADR-0019). Chỉ số OTP được tính theo ngày ở job hằng đêm, nên hôm nay scorecard phản ánh gián đoạn qua tab Disruptions."

### Bước 4. Bơm dữ liệu lỗi → DLQ tăng, phân loại, batch vẫn chạy; record lỗi mạng tạm thời được tự replay

**Thao tác**

1. Tab 0: bấm "Inject bad data" (`ratio` `0.01`, `kinds` `out_of_bbox`, `schema_violation`, `unknown_route`, `duration` 3 min; trên Demo control là thẻ "Bad data"). Ô "DLQ open" bắt đầu tăng và timeline có "DLQ +{n}". Bấm liên kết bước 4 sang Dead letters.
2. Tab 5 (Dead letters). Thẻ "Open" và "New in last hour" tăng. Trong vài giây cột "Category" chuyển từ "Unclassified" sang "Schema violation" / "Unknown reference". Trạng thái "Needs manual review" hoặc "Awaiting confirmation".
3. Tab 6 (Pipeline): biểu đồ "Throughput" vẫn đều; chuyển "By source" với chỉ số "Skipped" thì thấy cột skipped. Pipeline không dừng vì record lỗi.
4. Quay lại Dead letters, tab "Action log", lọc "Actor" = "Auto-triage". Các record của `late-delivery` (bật ở bước 2, trễ 6 phút) có "Auto-replay scheduled" rồi "Replayed".

**Kết quả mong đợi**

- DLQ tăng khoảng 1% thông lượng GTFS-rt trong 3 phút.
- Không record hợp lệ nào vào DLQ.
- Record DQ-07 (đến muộn) được tự replay trong ≤ 90 giây sau khi vào DLQ (E2E-TRIAGE-01).

**Lời thoại**

> "Tôi làm hỏng 1% message. Record lỗi được tách ra dead letter queue ngay tại chunk, còn phần còn lại của batch vẫn ghi bình thường. Đó là NFR-02, đã đo ở EXP-03. Mỗi dead letter được phân loại. Chỉ những loại mà replay chắc chắn sửa được mới tự động: ở đây là message đến muộn quá ngưỡng lệch giờ. Khi nguồn đã ổn định ít nhất 60 giây, hệ thống tự replay, tối đa hai lần mỗi record. Lỗi schema luôn phải có người xem."

### Bước 5. Kill consumer giữa chừng → phục hồi → đối chiếu không mất, không trùng

**Thao tác**

1. Tab 0: bấm "Kill etl-stream", rồi "Confirm: Kill etl-stream" (`make demo-kill-consumer`, tức `docker compose kill -s KILL etl-stream`, chờ 5 giây, rồi `docker compose start etl-stream`, như EXP-01 `kill-external`).
2. Vẫn ở tab 0: node `etl-stream` chuyển đỏ, cạnh Kafka → etl-stream hiện "consumer down"; timeline có "etl-stream killed (SIGKILL, exit 137)". Sau 5 giây node chuyển vàng ("etl-stream started"), rồi xanh với dòng "etl-stream healthy · {n} messages waited". Lag hiện lại ở mức cao rồi giảm về mức cũ trong khoảng 20–40 giây. Không có console thì xem tab 7 (Grafana "Pipeline overview").
3. Tab 6 (Pipeline): bucket phút vừa qua trên biểu đồ "Throughput" thấp hơn hẳn. Tooltip các bucket ngay sau đó có "Duplicates" > 0: message đã được giao lại và bị bỏ qua vì trùng.
4. Tab 0: bấm "Reconcile" (`make demo-check SINCE=10m`). Kết quả hiện thành bảng trên console.

**Kết quả mong đợi**

- `etl-stream` chạy lại, không cần thao tác thêm.
- "Reconcile" (hoặc `demo-check`) hiện bảng theo bảng đích với `expected`, `lost 0`, `duplicates 0`, `wrong_value 0`, `unexpected 0`, và "PASS".

**Lời thoại**

> "Tôi kill tiến trình ETL bằng SIGKILL giữa lúc đang xử lý, tương đương mất điện. Chunk đang dở bị rollback nên không để lại gì trong kho. Offset chỉ được commit sau khi transaction thành công, nên Kafka giao lại đúng những message đó. Upsert theo business key làm cho lần ghi lại không tạo bản trùng. Cột Duplicates chính là những message được giao lại. Lệnh cuối so từng business key trong kho với sổ cái của simulator: không mất, không trùng. EXP-01 lặp lại việc này 30 lần cho mỗi biến thể."

Ghi chú: SDD viết "ops console hiển thị batch failed". Với SIGKILL, transaction bị rollback nên không có dòng `FAILED` (DOC-15 §4.2 chỉ ghi `FAILED` khi tiến trình còn sống). Dấu vết trên ops console là khoảng trống trên biểu đồ throughput và cột "Duplicates" sau khi chạy lại.

### Bước 6. Sửa một record trong DLQ và replay → dữ liệu vào warehouse

**Thao tác**

1. Tab 5, tab "Review", lọc "Status" = "Needs manual review" và "Error" → "Rule" = rule bbox (DOC-16). Chọn một record `out_of_bbox`.
2. "Edit payload": đổi `latitude`, `longitude` thành `44.9778`, `-93.2650` (trung tâm Minneapolis). Bấm "Save", toast "Payload saved".
3. "Replay", dialog "Replay this record?", bấm "Replay". Toast "Request sent", rồi "Record replayed". "History" có "Payload edited", "Replay requested", "Replayed".
4. Terminal, chép `vehicle_id` và `event_timestamp` từ payload:
   `make psql-wh Q="SELECT vehicle_id, event_timestamp, lat, lon, batch_id FROM dw.fact_vehicle_position WHERE vehicle_id = '<id>' AND event_timestamp = '<ts>'"`

**Kết quả mong đợi**

Một dòng với tọa độ đã sửa, và `batch_id` của lần replay (khác batch của stream).

**Lời thoại**

> "Với lỗi cần người xử lý, người vận hành sửa payload ngay trên giao diện rồi replay. Record đi qua đúng pipeline bình thường, cùng validation và cùng upsert, nên không có đường ghi tắt nào vào kho. Mọi thao tác đều nằm trong lịch sử của record."

## 4. Phần B: k3d

### Chuyển môi trường (T+18, trong lúc trình bày slide kết quả EXP-01…05)

1. Terminal: `make demo-switch-k3d`, gồm `make down` (giữ volume compose), `make k8s-start` (khởi động cluster `pti` đã dừng), `make k8s-clock-offset AT=16:40` (cluster dựng từ hôm trước nên offset cũ đã lệch), chờ mọi pod `Ready`, rồi `make k8s-smoke`. Mục tiêu ≤ 5 phút; số đo thật ghi ở §8.
2. Tab 0: bấm "Switch to k3d". Topology đổi sang dạng k3d: 3 chấm broker, 4 ô `etl-stream`, 2 ô Warehouse có nhãn "primary"/"replica".
3. Mở `http://localhost:3000/d/pti-k8s` (Grafana "Kubernetes scaling", DOC-40 §7.6) và `http://localhost:8080/map` (anonymous; `lite` không có Keycloak).
4. Dự phòng khi không có console, terminal thứ hai: `kubectl -n pti get pods -w`. Lệnh không lọc label, để thấy cả pod ứng dụng, pod broker `pti-dual-N` và pod `pti-warehouse-N`.

### Bước 7. Tăng tải → pod tăng theo lag; tắt một broker và failover Postgres → không mất, không trùng

**Thao tác 7a: scale theo lag**

1. Tab 0: bấm "Load ×10" (`make k8s-load STEPS='1,10' STEP=PT8M`): tải GTFS-rt nhảy lên ×10.
2. Tab 0: lag trên cạnh Kafka → etl-stream chuyển vàng rồi đỏ; các ô `etl-stream` sáng dần 1 → 2 → 3 → 4, mỗi bước khoảng 60 giây (DOC-40 §9.1), và timeline ghi "etl-stream scaled {a} → {b} (lag {n})". Lag giảm khi đủ pod; ô "p95 end-to-end" quay về dưới 10 giây. Grafana `pti-k8s` cho thấy cùng diễn biến dạng biểu đồ ("Desired replicas").

**Thao tác 7b: mất một Kafka broker**

1. Tab 0: bấm "Kill a Kafka broker", rồi xác nhận (`kubectl apply -f deploy/chaos/kafka-broker-kill.yaml`).
2. Tab 0: một chấm broker chuyển đỏ, node Kafka chuyển vàng ("degraded") với "{n} under-replicated"; timeline ghi "Pod pti-dual-N deleted", rồi "created", rồi "ready" khi Strimzi tạo lại. Mũi tên Simulator → Kafka vẫn chạy; bản đồ vẫn cập nhật.
3. Grafana "Kafka": lỗi producer của simulator bằng 0; under-replicated partition tăng rồi về 0.

**Thao tác 7c: failover Postgres**

1. Tab 0: bấm "Fail over Postgres", rồi xác nhận (`make demo-pg-failover`: xóa pod primary của `pti-warehouse` bằng `--grace-period=0 --force`, rồi theo dõi `kubectl cnpg status pti-warehouse -n pti`).
2. Tab 0: ô primary chuyển đỏ; sau khoảng 10–30 giây nhãn "primary" chuyển sang ô còn lại và timeline ghi "Postgres primary → pti-warehouse-N". Các ô `etl-stream` chuyển vàng rồi xanh lại (readiness), **RESTARTS vẫn 0**. Bản đồ vẫn hiện xe vì API đọc qua replica hoặc tự rơi về primary (DOC-40 §9.5).

**Thao tác 7d: đối chiếu**

1. Tab 0: bấm "Back to ×1" (`make k8s-load STEPS='1' STEP=PT1M`), chờ lag về mức nền.
2. Tab 0: bấm "Reconcile" (`make demo-check ENV=k3d SINCE=15m`): "PASS", `lost 0`, `duplicates 0`.

**Kết quả mong đợi**

- Số pod tăng theo lag và không vượt 4.
- Không pod ứng dụng nào restart.
- Đối chiếu PASS.

**Lời thoại**

> "Đây là cùng image và cấu hình, triển khai trên Kubernetes với 3 Kafka broker và Postgres có replica. KEDA scale consumer theo lag, tối đa 4 pod vì topic có 12 partition và mỗi pod có 3 thread. Nếu database chậm, luật chặn sẽ giữ nguyên số pod thay vì dồn thêm tải vào DB. Tôi tắt một broker: nhờ replication factor 3 và min ISR 2, producer không lỗi. Tôi xóa primary Postgres: ETL tạm dừng ghi, không restart, rồi ghi tiếp vào primary mới. Đối chiếu lại với sổ cái: không mất, không trùng. EXP-07 và EXP-08 đo các kịch bản này nhiều lần."

## 5. Phương án dự phòng

| Sự cố | Nhận biết | Xử lý tại chỗ |
| --- | --- | --- |
| `make demo-reset` lỗi hoặc `smoke` fail ở T−45 | Lệnh thoát mã khác 0 | `make down && make up-demo`; vẫn lỗi thì RB-14; tới T−15 chưa xong thì trình bày bằng bản ghi của lần diễn tập (toàn bộ phần A, `~/pti-demo/recordings/partA.mp4`) |
| Không có xe (0 xe) | `sim-status` `activeVehicles = 0` | `make clock-offset AT=16:20`, chờ 1 phút |
| Bunching tuyến 18 chưa hình thành ở T+2 | Không có halo | Mở Alerts, lọc "Bus bunching": nếu tuyến khác đã có (từ lần thử ở §1.4) thì dùng tuyến đó; nếu không thì nói rõ "kịch bản đang hình thành", sang bước 3 và quay lại cuối phần A |
| Gợi ý điều phối không có | Panel "No suggestion available" | Gợi ý được tạo trong vòng `dispatch.max-age` 5 phút sau khi episode mở (DOC-24 §5.5); episode gieo trước quá 5 phút mà chưa có gợi ý thì `triage-worker` không chạy lúc đó. Kiểm `make ps`, rồi dùng tuyến 5 vừa bật ở bước 2 (episode mới sẽ có gợi ý). Gợi ý có confidence < 0,6 vẫn hiện, kèm chip "Low confidence" |
| Alert gián đoạn chưa có ở T+5 | Alerts trống | Tab scorecard tuyến 21, tab "Delays" cho thấy trễ đang tăng; quay lại Alerts cuối phần A |
| Auto-replay chưa xảy ra ở T+8…12 | Action log không có "Auto-replay scheduled" | Lọc "Status" = "Auto-replay scheduled"; nếu record còn "New" thì triage-worker chưa chạy: `make restart S=triage-worker`; nếu không có record DQ-07 thì `late-delivery` chưa được bật ở bước 2: bật ngay và quay lại sau 7 phút |
| `etl-stream` không chạy lại sau kill | `make ps` báo `exited` | `make restart S=etl-stream`. `demo-kill-consumer` luôn gọi `docker start` sau 5 giây, kể cả khi restart policy đã tự khởi động lại (như EXP-01 `kill-external`); nếu container vẫn `exited` thì xem log bằng `make logs S=etl-stream` |
| `demo-check` báo `unexpected > 0` với rule DQ-07 | Dòng DQ-07 trong bảng lỗi | Record `late-delivery` chưa replay xong; chờ 1 phút rồi chạy lại. Các lỗi khác: dừng, ghi lại, không che giấu; trình bày kết quả EXP-01 |
| Replay record không thành công | Toast "Replay failed again: {error}" | Đọc lỗi (thường là sửa tọa độ sai định dạng); sửa lại và replay |
| `demo-switch-k3d` quá 5 phút hoặc lỗi | Pod không `Ready` | Tiếp tục slide; quá 8 phút thì chiếu `step7.mp4` và trình bày số liệu EXP-07/08 |
| Pod không scale ở 7a | "Desired replicas" không tăng sau 3 phút | Kiểm `kubectl -n pti get scaledobject etl-stream` (`READY`, `ACTIVE`); có thể đang bị luật chặn DB (`pti:chunk_duration:p95_5m > 2`): giải thích đây là luật chặn của SDD §12.7 và chiếu phần 7a của bản ghi |
| Failover chưa xong sau 60 giây | `kubectl cnpg status` chưa có primary | Chờ thêm; quá 2 phút thì RB-08 và chiếu phần 7c của bản ghi |
| Demo console không phản hồi, hoặc trạng thái trên console khác thực tế | Trang báo "Reconnecting…" quá 10 giây, hoặc nút báo lỗi mà terminal cho thấy lệnh chạy được | Không dừng buổi demo: làm tiếp bằng lệnh `make` tương đương (DOC-48 §7) và tab Grafana; trong lúc nói, `Ctrl-C` rồi chạy lại `make demo-console` ở terminal riêng |
| Mất Wi-Fi hoặc máy chiếu chặn mạng | — | Không ảnh hưởng: mọi thứ chạy offline (§6) |

## 6. Chế độ offline (P8-04)

Demo không cần Internet nếu các điều kiện sau đã đúng từ hôm trước:

| Thành phần | Điều kiện | Kiểm |
| --- | --- | --- |
| Image compose | Đã build hoặc pull mọi image (`make images`, `docker compose pull` cho image hạ tầng) | `make demo-preflight` kiểm `docker image inspect` từng image trong `deploy/versions.env` |
| Bản đồ | PMTiles, font và sprite nằm trong `deploy/tiles` (DR-47, ADR-0021) | Tắt Wi-Fi, mở Live map: nền bản đồ đầy đủ |
| Đăng nhập | Keycloak cục bộ, realm import từ file | — |
| Demo console | Entry `frontend/console/` đã build (`dist-console/`), môi trường uv của `experiments/` đã `uv sync`; console chỉ gọi `localhost` | Tắt Wi-Fi, chạy `make demo-console`: trang mở và topology có dữ liệu |
| AI triage | `PTI_TRIAGE_PROVIDER=fake`, mặc định của `make up-demo` (ADR-0018) | Drawer Dead letters có "Model fake@2026.09 · …" (DOC-24 §4.3) |
| k3d | Cluster đã dựng rồi `make k8s-stop`: image nằm sẵn trong node và registry cục bộ; `make k8s-start` không kéo image hay chart nào. `lite` gọi `jev-stub` trong cluster, không gọi Jev thật | Tắt Wi-Fi ở lần diễn tập cuối, chạy `make demo-switch-k3d` |

Diễn tập cuối cùng (P8-05) phải làm với Wi-Fi tắt từ đầu tới cuối.

## 7. Reset giữa các lần diễn tập

1. Phần B: `make k8s-load STEPS='1' STEP=PT1M`, xóa chaos CR còn sót (`kubectl -n pti delete podchaos,networkchaos --all`), `make k8s-stop`.
2. Phần A: `make demo-reset` (xóa mọi volume compose, dựng lại, smoke, đặt đồng hồ).
3. Nếu cluster k3d hỏng: `make k8s-down && make k8s-up ENV=lite && make k8s-stop` (cần Internet, khoảng 15 phút).

Reset không động tới `.env` và khóa niêm phong.

## 8. Nhật ký diễn tập

Thời gian từng bước lấy từ nhật ký hành động của console (`~/pti-demo/console-log/*.jsonl`, DOC-48 §11); bước không bấm trên console thì bấm giờ tay.

| Lần | Ngày | Offline | Thời gian từng bước (1…7, chuyển môi trường) | Lỗi gặp | Sửa |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |

## 9. Lệnh và test

### 9.1 Lệnh `make` riêng cho demo (DOC-38 §4.6)

| Lệnh | Việc |
| --- | --- |
| `make demo-reset` | `reset`, `clock-offset AT=16:20`, `up-demo`, `smoke` |
| `make demo-prewarm` | §1.4 |
| `make demo-preflight` | E2E-DEMO-11 |
| `make demo-kill-consumer` | `docker compose kill -s KILL etl-stream`, chờ 5 s, `docker compose start etl-stream`, in thời điểm `Ready` |
| `make demo-check [ENV=compose\|k3d] SINCE=<duration>` | `uv run pti-exp check --env $ENV --since $SINCE` (DOC-45 §2): so ledger với warehouse theo README §4.1 trên cửa sổ `[now − SINCE, now − 60 s]`, in bảng và PASS/FAIL |
| `make demo-switch-k3d` | `down`, `k8s-start`, `k8s-clock-offset AT=16:40`, chờ `Ready`, `k8s-smoke` |
| `make demo-switch-compose` | `k8s-stop`, `up-demo`, `smoke` |
| `make demo-pg-failover` | Xóa pod primary `pti-warehouse`, theo dõi `kubectl cnpg status` tới khi có primary mới và `readyInstances = 2` |
| `make demo-console [ENV=compose\|k3d]` | Mở demo console ở `http://localhost:8095` (DOC-48 §3.2). Mỗi nút trên console tương đương một lệnh trong bảng này (DOC-48 §7) |

### 9.2 Test bắt buộc

| ID | Kiểm tra | Cách |
| --- | --- | --- |
| E2E-DEMO-11 | Sẵn sàng trình bày: ≥ 300 xe trên `GET /vehicles/live`; có episode bunching đang mở trên tuyến được gieo (E-10); có gián đoạn đang mở trên tuyến 21 (E-12); `triage-worker` `UP`; mọi image offline có sẵn | `make demo-preflight` (script, không phải Playwright), in "READY" hoặc danh sách thiếu |
| E2E-DEMO-12 | Bước 5 tự động: `demo-kill-consumer` rồi `demo-check SINCE=10m` → PASS; `etl-stream` `Ready` ≤ 60 s | Script trong `full-stack.yml` → `e2e-compose` (DOC-41 §10.3) |
| E2E-DEMO-13 | Bước 7 tự động trên k3d `lite`: `demo-pg-failover` rồi `demo-check ENV=k3d SINCE=10m` → PASS; `RESTARTS` của pod app không đổi | Script trong `full-stack.yml` → `k3d-lite` sau `k8s-smoke` |

Ba ca trên là script shell (`scripts/demo/*.sh`), không phải Playwright hay JUnit, nên `testIdReport` (DOC-44 §4.3) không tìm thấy trong code. Chúng được ghi trong `docs/10-testing/test-id-exemptions.txt` với lý do "script in full-stack.yml"; script in dòng `E2E-DEMO-1x PASS` hoặc `FAIL` để log CI tra được.

Các bước khác đã có test ở màn hình tương ứng, nên không viết lại:

| Bước | Test đã có |
| --- | --- |
| 1 | E2E-MAP-01, E2E-SHELL-01 |
| 2 | E2E-MAP-02, E2E-ALERT-02, E2E-DEMO-02 |
| 3 | E2E-ALERT-01, E2E-MAP-04, E2E-TRIAGE-02 |
| 4 | E2E-DLQ-02, E2E-TRIAGE-01 |
| 6 | E2E-DLQ-03 |
| 7 | KD-03, EXP-07, EXP-08 F3, F4 |

## 10. Câu hỏi còn mở

Không có.
