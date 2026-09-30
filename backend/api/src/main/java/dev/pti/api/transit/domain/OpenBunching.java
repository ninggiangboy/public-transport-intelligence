package dev.pti.api.transit.domain;

import java.util.UUID;

/** An open bunching episode (DOC-32 E-05): the pair of vehicles, the gap between them and the scheduled headway. */
public record OpenBunching(
        UUID episodeId, String leaderVehicleId, String followerVehicleId, int gapSeconds, int headwaySeconds) {}
