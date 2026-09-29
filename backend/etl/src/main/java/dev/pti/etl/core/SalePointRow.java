package dev.pti.etl.core;

import org.jspecify.annotations.Nullable;

/** One CDC row of {@code dw.dim_sale_point} (DOC-14 §8.5). */
public record SalePointRow(
        String salePointId,
        String name,
        String kind,
        @Nullable String stopId,
        @Nullable String routeId,
        boolean deleted,
        long sourceLsn) {}
