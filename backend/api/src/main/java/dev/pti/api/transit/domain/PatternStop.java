package dev.pti.api.transit.domain;

import org.jspecify.annotations.Nullable;

/** A stop of the representative trip of a direction (DOC-32 E-02). */
public record PatternStop(
        String stopId, @Nullable String code, String name, double lat, double lon, int stopSequence) {}
