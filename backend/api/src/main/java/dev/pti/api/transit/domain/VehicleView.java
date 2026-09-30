package dev.pti.api.transit.domain;

import org.jspecify.annotations.Nullable;

/** A live vehicle as one caller sees it: the bunching overlay is only ever present for a viewer. */
public record VehicleView(LiveVehicle vehicle, @Nullable BunchingOverlay bunching) {}
