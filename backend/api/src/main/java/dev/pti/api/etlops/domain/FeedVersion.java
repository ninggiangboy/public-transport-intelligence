package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A GTFS feed version for the feed screen (DOC-32 E-38). {@code runId} is the job that loaded it, when known. */
public record FeedVersion(
        long feedVersionId,
        String feedHash,
        String status,
        @Nullable String publisherName,
        @Nullable String publisherFeedVersion,
        String agencyTimezone,
        @Nullable LocalDate validFrom,
        @Nullable LocalDate validTo,
        Instant loadedAt,
        @Nullable Instant activatedAt,
        @Nullable String runId,
        int validationErrors,
        int validationWarnings) {}
