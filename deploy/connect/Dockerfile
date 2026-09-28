# Kafka Connect: Debezium PostgreSQL source + Aiven S3 sink (Apache-2.0), verified in spike S-04 (ADR-0012).
# The sink archive is checked against its SHA-256 at build time.
FROM quay.io/debezium/connect:3.6.3.Final
ARG AIVEN_S3_VERSION=3.4.3
ARG AIVEN_S3_SHA256=85661c4d3d49b85f4a65170a5c27464e4359760aa7140a24628e45323b6d7329
RUN curl -fsSL -o /tmp/s3.tar \
      "https://github.com/Aiven-Open/cloud-storage-connectors-for-apache-kafka/releases/download/v${AIVEN_S3_VERSION}/s3-sink-connector-for-apache-kafka-${AIVEN_S3_VERSION}.tar" \
 && echo "${AIVEN_S3_SHA256}  /tmp/s3.tar" | sha256sum -c - \
 && mkdir -p /kafka/connect/aiven-s3 && tar -xf /tmp/s3.tar -C /kafka/connect/aiven-s3 --strip-components=1 \
 && rm /tmp/s3.tar
