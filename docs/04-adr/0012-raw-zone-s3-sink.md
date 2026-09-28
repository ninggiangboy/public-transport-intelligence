# ADR-0012: Raw zone bằng S3 sink, JSON gzip, phân vùng theo giờ của record

- Trạng thái: Accepted (đã xác minh ở S-04, 2026-09-28)
- Ngày: 2026-09-26 · Liên quan: SDD §4.1, §12.6, FR-01.4, FR-12.2, EXP-04, DOC-09 §7, DOC-18, DOC-22, DR-81

## Bối cảnh

Raw zone là **nguồn sự thật** để dựng lại warehouse (EXP-04, UC-18) và để replay sau khi sửa lỗi logic. Kafka chỉ giữ 7 ngày. Yêu cầu:

1. Lưu **mọi** message của các topic nguồn, **kể cả message không parse được** (kịch bản `bad-data`), nguyên văn từng byte.
2. Giữ key, headers, partition, offset, timestamp.
3. Chọn được dữ liệu theo khoảng thời gian mà không phải quét toàn bộ.
4. Độc lập với ETL: ETL chết thì raw zone vẫn được ghi.
5. License mã nguồn mở, dùng được trong một repo public mà không phát sinh nghĩa vụ ngoài việc giữ thông báo license.

## Các phương án

1. **ETL tự ghi raw zone.** Phụ thuộc ETL (vi phạm yêu cầu 4); thêm việc cho đường nóng.
2. **Confluent S3 sink connector.** Trưởng thành, có `TimeBasedPartitioner` theo record timestamp; license Confluent Community License (dùng được cho dự án này, nhưng không phải OSI).
3. **Aiven S3 sink connector** (`s3-connector-for-apache-kafka`). Apache-2.0; output JSON lines với các trường chọn được (key, value, offset, timestamp, headers); tên file theo template có timestamp.
4. **Kafka tiered storage.** Không cho ra file đọc được độc lập với Kafka.

## Quyết định

Chọn **phương án 3 (Aiven)**; phương án 2 là dự phòng nếu S-04 thấy Aiven không đáp ứng được yêu cầu 1–3.

- Connector: Aiven `s3-sink-connector-for-apache-kafka` **3.4.3**, tên `pti-raw-sink`, `tasks.max = 1`. Cấu hình đầy đủ ở DOC-09 §7.
- Converter (DR-81): **`ByteArrayConverter` cho value**, ghi ra dưới dạng **base64** (`format.output.fields.value.encoding=base64`). Nhờ vậy mọi byte được giữ nguyên, kể cả UTF-8 hỏng và `0x00`; `StringConverter` sẽ thay byte hỏng bằng U+FFFD. Key và headers dùng `StringConverter`, vì mọi producer của hệ thống đều ghi key và header dạng chuỗi ASCII.
- Định dạng: JSON lines, nén gzip. Mỗi dòng gồm `key, value, offset, timestamp, headers`. Aiven không có trường `partition`, nên partition được lấy từ tên file (DOC-09 §7).
- Đường dẫn: `raw/<topic>/dt=YYYY-MM-DD/hh=HH/<topic>-<partition>-<start_offset>.json.gz`, với `start_offset` đệm 20 chữ số. Giờ tính theo **timestamp của record** (CreateTime), UTC (`file.name.timestamp.source=EVENT`). Mỗi dòng nằm đúng thư mục giờ của nó, nên replay chỉ cần liệt kê các giờ giao với khoảng cần replay.
- Rotate: file đóng ở mỗi lần commit của connector, tức mỗi 5 phút (`offset.flush.interval.ms = 300000` của worker) hoặc sớm hơn khi một file bất kỳ đạt 2.000 record (`file.max.records`). Ngưỡng 2.000 giữ RAM của connector trong giới hạn (DR-81). **Sửa bởi DR-89:** Aiven 3.4.3 cắt file mỗi 10 giây trên mỗi partition khi chạy live; commit mỗi 30 giây và part 1 MiB.
- Bucket `raw` bật versioning. Chỉ connector (ghi) và `etl-batch` (đọc, ghi `raw/gtfs-static/`) có credential.
- File GTFS static: `raw/gtfs-static/<feed_hash>.zip`, do `GtfsStaticLoadJob` ghi.
- Consumer group của sink: `connect-pti-raw-sink`. Lag của group này được giám sát như mọi consumer khác.
- Delivery của sink là at-least-once: file có thể chứa bản ghi trùng `(topic, partition, offset)` sau khi connector restart. Replay **khử trùng theo `(topic, partition, offset)`**, và dù không khử trùng thì upsert vẫn đúng.

## Kết quả spike S-04

Spike chạy ngày 2026-09-28 trong `spikes/s04-kafka-connect/`, với `quay.io/debezium/connect:3.6.3.Final` + Aiven 3.4.3, `apache/kafka:4.3.1`, `postgres:17.11` và `18.6`, SeaweedFS 4.47.

| Câu hỏi | Kết quả |
| --- | --- |
| Template tên file | `file.name.template` với `{{topic}}`, `{{partition}}`, `{{start_offset:padding=true}}` (20 chữ số), `{{timestamp:unit=yyyy\|MM\|dd\|HH}}` |
| Nguồn timestamp | `file.name.timestamp.source=EVENT` dùng CreateTime của record. Kiểm trên 1.020.001 record: không record nào nằm sai thư mục giờ. Mặc định (`WALLCLOCK`) dùng giờ xử lý, nên sau khi sink dừng lâu rồi chạy bù, record sẽ rơi vào giờ khác xa giờ gốc |
| Headers | Có, dạng mảng `[{"key", "value"}]`. Debezium tự thêm bốn header `__debezium.context.*` |
| JSON hỏng | Lưu nguyên văn |
| Byte không phải UTF-8 | `StringConverter` biến `0xFF 0xC3` thành hai U+FFFD. `ByteArrayConverter` + base64 giữ đúng từng byte → chọn cách này (DR-81) |
| gzip | `file.compression.type=gzip`. Dòng cuối của mỗi file **không** có ký tự xuống dòng |
| SeaweedFS | Chạy với `aws.s3.endpoint` + `aws.s3.region`, không cần cấu hình path-style riêng |
| At-least-once | `kill -9` Connect giữa cửa sổ commit: đủ mọi offset, không trùng. Trùng vẫn có thể xảy ra, nên replay vẫn khử trùng |
| RAM | Mỗi file đang mở trong một cửa sổ commit giữ một buffer 5 MiB trên heap. SeaweedFS từ chối part nhỏ hơn 5 MiB (`EntityTooSmall`), nên không giảm buffer được. Với `file.max.records=10000`, sink bị OOM khi chạy bù 480 nghìn record (heap 512 MB và 768 MB; 96–115 file mở cùng lúc). Với 2.000: tối đa 49 file mở, chạy bù 1,02 triệu record trên 30 partition trong khoảng 15 giây ở `-Xmx512m`; heap đỉnh 468 MiB, container đỉnh 1.009 MiB (DR-81). Bài đo này chỉ có backlog tĩnh; khi chạy live, sink mở file mới mỗi 10 giây và OOM với commit 5 phút (DR-89) |

Phương án 2 (Confluent) không cần đến.

## Hệ quả

- Raw zone tăng khoảng 1 GB mỗi ngày khi chạy 24/7 (DOC-10). Retention ở DOC-18.
- `value` là base64, nên không đọc được bằng mắt. Muốn xem thì dùng `make raw-cat KEY=<object>` (giải nén, giải base64 từng dòng, DOC-38). Bù lại replay tái tạo đúng từng byte, và đi qua đúng đường giải mã của `etl-stream` (`ByteArrayDeserializer`, DOC-20 §4.1).
- `kafka-connect` cần `mem_limit` 1.280 MB (heap vẫn 512 MB) vì buffer multipart nằm trên heap và phần native của JVM (DOC-10 §5).
- Với 2.000 record mỗi file, chạy bù sau khi sink dừng lâu tạo ra nhiều object nhỏ hơn. Ở tải nền, file của VehiclePosition đóng khoảng mỗi 2–3 phút vì partition nóng nhất đạt 2.000 record trước mốc 5 phút.
- Nếu object storage chết thì sink dừng và tự bắt kịp khi nó chạy lại; Kafka retention 7 ngày là vùng đệm.
