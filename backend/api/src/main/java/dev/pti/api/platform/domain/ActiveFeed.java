package dev.pti.api.platform.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * The ACTIVE GTFS feed version (DOC-31 §10.2). Every GTFS query of a request takes {@code feedVersionId} and the
 * {@code :tz} of the feed from one value of this record, read once at the start of the request.
 */
public record ActiveFeed(
        long feedVersionId,
        @Nullable String publisherFeedVersion,
        ZoneId timezone,
        LocalDate validFrom,
        @Nullable LocalDate validTo,
        Instant activatedAt) {}
