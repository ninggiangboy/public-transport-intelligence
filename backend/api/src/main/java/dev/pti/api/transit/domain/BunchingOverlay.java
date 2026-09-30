package dev.pti.api.transit.domain;

import java.util.UUID;

/** What a viewer sees on a vehicle that belongs to an open bunching episode (DOC-32 E-05). */
public record BunchingOverlay(
        UUID episodeId, BunchingRole role, String partnerVehicleId, int gapSeconds, int headwaySeconds) {}
