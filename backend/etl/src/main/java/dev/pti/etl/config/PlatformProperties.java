package dev.pti.etl.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings shared by every part of the app: topic and object prefixes used by tests (DOC-44 §6.2), the PII
 * blocklist (DOC-18 §4) and payload limits (DOC-22 §1.2).
 */
@ConfigurationProperties("pti")
@Validated
public record PlatformProperties(Kafka kafka, S3 s3, Pii pii, Dlq dlq, Partition partition) {

    public PlatformProperties {
        kafka = kafka == null ? new Kafka("") : kafka;
        s3 = s3 == null ? new S3("raw", "") : s3;
        pii = pii == null ? new Pii(List.of("customer_ref")) : pii;
        dlq = dlq == null ? new Dlq(DataSize.ofMegabytes(1)) : dlq;
        partition = partition == null ? new Partition(7) : partition;
    }

    public record Kafka(String topicPrefix) {

        public Kafka {
            topicPrefix = topicPrefix == null ? "" : topicPrefix;
        }
    }

    public record S3(@NotBlank String bucket, String rawPrefix) {

        public S3 {
            rawPrefix = rawPrefix == null ? "" : rawPrefix;
        }
    }

    public record Pii(@NotNull List<String> blocklist) {}

    public record Dlq(@NotNull DataSize maxPayloadBytes) {}

    public record Partition(@Min(1) int precreateDays) {}
}
