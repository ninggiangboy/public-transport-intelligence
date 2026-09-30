package dev.pti.api.transit.domain;

import org.jspecify.annotations.Nullable;

/** One route of the ACTIVE feed, as the route picker, the map colours and the OTP ranking need it (DOC-32 E-01). */
public record RouteSummary(
        String routeId,
        @Nullable String shortName,
        @Nullable String longName,
        String displayName,
        int routeType,
        @Nullable String color,
        @Nullable String textColor,
        @Nullable Integer sortOrder,
        @Nullable Integer typicalHeadwaySeconds) {}
