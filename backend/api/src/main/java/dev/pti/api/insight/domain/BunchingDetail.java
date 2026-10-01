package dev.pti.api.insight.domain;

import org.jspecify.annotations.Nullable;

/** A bunching episode with its whole dispatch suggestion, if it has one (DOC-32 E-11). */
public record BunchingDetail(
        BunchingEpisode episode, @Nullable DispatchSuggestion suggestion) {}
