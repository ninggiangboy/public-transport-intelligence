package dev.pti.api.system.adapter.in.web;

import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.ApiTime;
import dev.pti.api.system.domain.Freshness;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Response of {@code GET /system/freshness} (DOC-32 E-60). {@code probeError} is only present when it is true. */
public record FreshnessResponse(
        String businessNow,
        String clockOffset,
        String checkedAt,
        boolean stale,
        @Nullable ActiveFeedResponse activeFeed,
        List<SourceResponse> sources,
        InsightsResponse insights,
        @Nullable Boolean probeError) {

    /** The ACTIVE feed, as the frontend needs it to show times in the feed's timezone (DR-48). */
    public record ActiveFeedResponse(
            long feedVersionId,
            @Nullable String publisherFeedVersion,
            String timezone,
            String validFrom,
            @Nullable String validTo,
            String activatedAt) {}

    /** One source; {@code lastEventAt} and {@code ageSeconds} are absent when it never had data. */
    public record SourceResponse(
            String source,
            @Nullable String lastEventAt,
            @Nullable Long ageSeconds,
            long staleAfterSeconds,
            boolean stale) {}

    /** When the insight tables were last computed. */
    public record InsightsResponse(
            @Nullable String etaComputedAt, @Nullable String otpComputedAt) {}

    static FreshnessResponse from(Freshness freshness) {
        List<SourceResponse> sources = freshness.sources().stream()
                .map(source -> new SourceResponse(
                        source.source().name(),
                        format(source.lastEventAt()),
                        source.ageSeconds(),
                        source.staleAfterSeconds(),
                        source.stale()))
                .toList();
        return new FreshnessResponse(
                ApiTime.format(freshness.businessNow()),
                freshness.clockOffset().toString(),
                ApiTime.format(freshness.checkedAt()),
                freshness.stale(),
                feed(freshness.activeFeed()),
                sources,
                new InsightsResponse(format(freshness.etaComputedAt()), format(freshness.otpComputedAt())),
                freshness.probeError() ? Boolean.TRUE : null);
    }

    private static @Nullable ActiveFeedResponse feed(@Nullable ActiveFeed feed) {
        if (feed == null) {
            return null;
        }
        LocalDate validTo = feed.validTo();
        return new ActiveFeedResponse(
                feed.feedVersionId(),
                feed.publisherFeedVersion(),
                feed.timezone().getId(),
                feed.validFrom().toString(),
                validTo != null ? validTo.toString() : null,
                ApiTime.format(feed.activatedAt()));
    }

    private static @Nullable String format(@Nullable Instant instant) {
        return instant != null ? ApiTime.format(instant) : null;
    }
}
