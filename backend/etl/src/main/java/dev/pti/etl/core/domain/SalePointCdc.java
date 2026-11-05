package dev.pti.etl.core.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An unwrapped Debezium event of {@code sale_point} (DOC-09 §5.2). */
public record SalePointCdc(
        String salePointId,
        String name,
        String kind,
        @Nullable String stopId,
        @Nullable String routeId,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt,
        String op,
        Long lsn,
        Long sourceTsMs,
        @Nullable String deleted) {

    public boolean isDelete() {
        return "d".equals(op) || "true".equals(deleted);
    }
}
