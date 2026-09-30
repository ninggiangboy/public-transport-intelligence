package dev.pti.api.transit.domain;

import org.jspecify.annotations.Nullable;

/** A stop or a station of the ACTIVE feed (DOC-32 E-06, E-07). */
public record Stop(
        String stopId,
        @Nullable String code,
        String name,
        double lat,
        double lon,
        int locationType,
        int wheelchairBoarding) {}
