# S-04: Kafka Connect image (Debezium + Aiven S3 sink) on SeaweedFS

Answers P0-05 / ADR-0012: can one Connect image run the Debezium PostgreSQL source and an Apache-2.0 S3 sink
against SeaweedFS, with the raw-zone layout from DOC-09 §7, within the compose memory budget?

## Stack

| Component | Version |
| --- | --- |
| Connect base image | `quay.io/debezium/connect:3.6.3.Final` (Kafka Connect 4.3.0 runtime) |
| S3 sink | Aiven `s3-sink-connector-for-apache-kafka` 3.4.3, SHA-256 `85661c4d3d49b85f4a65170a5c27464e4359760aa7140a24628e45323b6d7329` |
| Broker | `apache/kafka:4.3.1` |
| Source DB | `postgres:17.11` and `postgres:18.6` (`wal_level=logical`) |
| Object storage | `chrislusf/seaweedfs:4.47` |

## Run

```sh
docker compose up -d --build                     # PG_IMAGE=postgres:18.6 to try PG 18
for f in connectors/*.json; do
  curl -s -X PUT -H 'Content-Type: application/json' \
    --data "$(python3 -c "import json;print(json.dumps(json.load(open('$f'))['config']))")" \
    "localhost:18083/connectors/$(python3 -c "import json;print(json.load(open('$f'))['name'])")/config"
done
```

Load generator (compiled against `kafka-clients` from the broker image, run inside the broker container):

```sh
docker compose cp kafka:/opt/kafka/libs/ /tmp/kafkalibs
javac --release 21 -d producer/classes -cp /tmp/kafkalibs/kafka-clients-4.3.1.jar producer/*.java
docker compose cp producer/classes/. kafka:/tmp/producer
# 20,000 records to partition 3; BOUNDARY=1 puts the first half in an earlier hour; RANDOM_PAD=1 makes values incompressible
docker compose exec -e BOUNDARY=1 kafka java -cp '/tmp/producer:/opt/kafka/libs/*' \
  RawProducer kafka:9092 gtfs.vehicle_positions 3 20000 2026-09-28T00:40:00Z
docker compose exec kafka java -cp '/tmp/producer:/opt/kafka/libs/*' BadBytesProducer kafka:9092 gtfs.vehicle_positions
```

Inspect the bucket: `docker compose run --rm -v $PWD/dump.sh:/dump.sh:ro -v /tmp/out:/out -e AWS_ACCESS_KEY_ID=admin
-e AWS_SECRET_ACCESS_KEY=admin-secret -e AWS_DEFAULT_REGION=us-east-1 --entrypoint sh s3-init /dump.sh`.
SeaweedFS lifecycle, versioning and prefix permissions: `docker compose run --rm -v $PWD:/work:ro --entrypoint sh
s3-init /work/s3-check.sh`.

## Results

1. **Debezium** 3.6.3 on PG 17.11 and 18.6: snapshot (`r`), insert, update and delete all arrive on
   `ticketing.sales.cdc` with `__op`, `__lsn`, `__source_ts_ms` and `__deleted`.
   `transforms.unwrap.delete.tombstone.handling.mode=rewrite` is the correct property name.
   `NUMERIC` is emitted as a string and `TIMESTAMPTZ` as an ISO-8601 string with microseconds.
   Heartbeat rows reach `ticketing.heartbeat`.
   Debezium adds `__debezium.context.*` headers to every record.
2. **Layout**:
   - `file.name.template` with `{{timestamp:unit=…}}` produces `dt=YYYY-MM-DD/hh=HH`.
   - `file.name.timestamp.source=EVENT` uses the record CreateTime, so every line lands in the directory of its own
     hour; this was checked on 1,020,001 records.
   - `{{start_offset:padding=true}}` pads to 20 digits and equals the first offset in the file.
3. **Line format**: `{"headers":[{"key","value"}],"offset","value","key","timestamp"}`.
   - `timestamp` is an ISO-8601 string (`2026-09-28T01:12:01.736Z`), not epoch millis.
   - There is no `partition` field; the partition must be read from the file name.
   - The last line of each file has no trailing newline.
4. **Byte fidelity**: `StringConverter` replaces invalid UTF-8 with U+FFFD. `ByteArrayConverter` with
   `format.output.fields.value.encoding=base64` stores the exact bytes; this was checked with `0xFF 0xC3` and `0x00`.
5. **Memory**:
   - Each file open within a commit window holds a 5 MiB heap buffer (`aws.s3.part.size.bytes`).
   - SeaweedFS rejects parts under 5 MiB (`EntityTooSmall`), so the part size cannot be lowered.
   - With `file.max.records=10000` the sink hit OOM while catching up a 480k-record backlog, at both 512 MiB and
     768 MiB heap (96–115 open writers).
   - With `file.max.records=2000`, a commit is requested whenever any file reaches 2,000 records. That bounds the open
     writers to 49, and a 1.02M-record backlog on 30 partitions caught up in about 15 s at `-Xmx512m`: heap peak
     468 MiB, container peak 1,009 MiB, live set after GC about 90 MiB.
6. **At-least-once**: after `kill -9` of Connect mid-window, all offsets were present with no duplicates.
7. **Rotation**: files close at every commit, which happens at `offset.flush.interval.ms` (worker-wide, set to
   300000) or when any file reaches `file.max.records`.
8. **SeaweedFS 4.47**:
   - The lifecycle rules from DOC-18 §1.4, plus `AbortIncompleteMultipartUpload`, are accepted and read back.
   - Versioning keeps the noncurrent version on overwrite.
   - Prefix-scoped write works as `Write:raw/gtfs-static/*` (the form without `/*` denies everything).
9. **Topic subscription**: a sink started before its topic exists only picks the topic up after a consumer metadata
   refresh. Topics must exist before connectors are registered.
