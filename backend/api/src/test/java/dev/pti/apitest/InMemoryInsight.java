package dev.pti.apitest;

import dev.pti.api.insight.application.BunchingQuery;
import dev.pti.api.insight.application.DisruptionQuery;
import dev.pti.api.insight.application.SuggestionQuery;
import dev.pti.api.insight.application.TicketingQuery;
import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.application.port.DispatchSuggestionReader;
import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.application.port.OtpReader;
import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.api.insight.domain.BunchingDetail;
import dev.pti.api.insight.domain.BunchingEpisode;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.insight.domain.OtpDay;
import dev.pti.api.insight.domain.OtpScorecard;
import dev.pti.api.insight.domain.TicketingAnomaly;
import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The ports of the insight feature in memory, with the filters and the keyset order of the SQL, so that the web tests
 * run the real use cases and controllers without a database. A test that wants data autowires it, calls {@link
 * #reset()} first, and holds the lock {@code in-memory-insight}: the test classes run concurrently over one context.
 */
public final class InMemoryInsight {

    /** The instant that a recorded feedback gets as {@code feedbackAt}. */
    public static final Instant FEEDBACK_TIME = Instant.parse("2026-09-29T21:14:02Z");

    public final List<BunchingDetail> bunching = new CopyOnWriteArrayList<>();
    public final List<DisruptionEpisode> disruptions = new CopyOnWriteArrayList<>();
    public final List<TicketingAnomaly> anomalies = new CopyOnWriteArrayList<>();
    public final Map<UUID, DispatchSuggestion> suggestions = new ConcurrentHashMap<>();
    public final List<OtpDay> otpDays = new CopyOnWriteArrayList<>();
    public final List<List<Integer>> otpRouteTypesAsked = new CopyOnWriteArrayList<>();
    public volatile Optional<ActiveFeed> activeFeed = Optional.of(feed());

    public static ActiveFeed feed() {
        return new ActiveFeed(
                3,
                "2026-08-23",
                ZoneId.of("America/Chicago"),
                LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-12-12"),
                Instant.parse("2026-09-27T08:34:40Z"));
    }

    public void reset() {
        bunching.clear();
        disruptions.clear();
        anomalies.clear();
        suggestions.clear();
        otpDays.clear();
        otpRouteTypesAsked.clear();
        activeFeed = Optional.of(feed());
    }

    public final ActiveFeedReader activeFeeds = () -> activeFeed;

    public final BunchingReader bunchingReader = new BunchingReader() {
        @Override
        public Page<BunchingEpisode> list(BunchingQuery query, PageRequest request) {
            List<BunchingEpisode> rows = bunching.stream()
                    .map(BunchingDetail::episode)
                    .filter(e -> e.episodeStart().isBefore(query.to()))
                    .filter(e -> e.episodeEnd() == null || !e.episodeEnd().isBefore(query.from()))
                    .filter(e -> query.routeIds().isEmpty() || query.routeIds().contains(e.routeId()))
                    .filter(e -> query.status() == null || query.status().equals(e.status()))
                    .toList();
            return InMemoryPaging.page(rows, request, BunchingEpisode::episodeStart, BunchingEpisode::id);
        }

        @Override
        public Optional<BunchingDetail> find(UUID id) {
            return bunching.stream().filter(d -> d.episode().id().equals(id)).findFirst();
        }
    };

    public final DisruptionReader disruptionReader = new DisruptionReader() {
        @Override
        public Page<DisruptionEpisode> list(DisruptionQuery query, PageRequest request) {
            List<DisruptionEpisode> rows = disruptions.stream()
                    .filter(e -> e.episodeStart().isBefore(query.to()))
                    .filter(e -> e.episodeEnd() == null || !e.episodeEnd().isBefore(query.from()))
                    .filter(e -> query.routeIds().isEmpty() || query.routeIds().contains(e.routeId()))
                    .filter(e -> query.status() == null || query.status().equals(e.status()))
                    .filter(e -> !query.publicOnly() || e.isPublic())
                    .toList();
            return InMemoryPaging.page(
                    rows.stream().map(InMemoryInsight::withoutDetail).toList(),
                    request,
                    DisruptionEpisode::episodeStart,
                    DisruptionEpisode::id);
        }

        @Override
        public Optional<DisruptionEpisode> find(UUID id) {
            return disruptions.stream().filter(e -> e.id().equals(id)).findFirst();
        }
    };

    public final TicketingAnomalyReader ticketingReader = new TicketingAnomalyReader() {
        @Override
        public Page<TicketingAnomaly> list(TicketingQuery query, PageRequest request) {
            List<TicketingAnomaly> rows = anomalies.stream()
                    .filter(a -> !a.detectedAt().isBefore(query.from())
                            && a.detectedAt().isBefore(query.to()))
                    .filter(a ->
                            query.salePointId() == null || query.salePointId().equals(a.salePointId()))
                    .filter(a -> !query.filtersCategory()
                            || (a.category() != null && query.categories().contains(a.category()))
                            || (a.category() == null && query.unclassified()))
                    .filter(a -> query.severities().isEmpty()
                            || (a.severity() != null && query.severities().contains(a.severity())))
                    .filter(a -> query.trigger() == null || query.trigger().equals(a.trigger()))
                    .toList();
            return InMemoryPaging.page(
                    rows.stream().map(InMemoryInsight::withoutDetail).toList(),
                    request,
                    TicketingAnomaly::detectedAt,
                    TicketingAnomaly::id);
        }

        @Override
        public Optional<TicketingAnomaly> find(UUID id) {
            return anomalies.stream().filter(a -> a.id().equals(id)).findFirst();
        }
    };

    public final DispatchSuggestionReader suggestionReader = (query, request) -> {
        List<DispatchSuggestion> rows = new ArrayList<>(suggestions.values())
                .stream()
                        .filter(s -> !s.createdAt().isBefore(query.from())
                                && s.createdAt().isBefore(query.to()))
                        .filter(s ->
                                query.routeIds().isEmpty() || query.routeIds().contains(s.routeId()))
                        .filter(s ->
                                query.bunchingId() == null || query.bunchingId().equals(s.bunchingId()))
                        .filter(s -> matchesFeedback(query, s))
                        .toList();
        return InMemoryPaging.page(rows, request, DispatchSuggestion::createdAt, DispatchSuggestion::id);
    };

    /** A list row has none of the members that only the detail reads, as in the SQL. */
    private static DisruptionEpisode withoutDetail(DisruptionEpisode e) {
        return new DisruptionEpisode(
                e.id(),
                e.routeId(),
                e.directionId(),
                e.episodeStart(),
                e.episodeEnd(),
                e.status(),
                e.severity(),
                e.audience(),
                e.baselineMeanSeconds(),
                e.baselineStddevSeconds(),
                e.currentAvgDelaySeconds(),
                e.currentZScore(),
                e.peakAvgDelaySeconds(),
                e.peakZScore(),
                e.sampleCount(),
                e.affectedStopIds(),
                e.lastBucket(),
                e.enrichmentStatus(),
                e.dataIssueProbability(),
                e.likelyCause(),
                e.causeConfidence(),
                e.modelVersion(),
                null,
                null,
                null);
    }

    private static TicketingAnomaly withoutDetail(TicketingAnomaly a) {
        return new TicketingAnomaly(
                a.id(),
                a.salePointId(),
                a.salePointName(),
                a.routeId(),
                a.windowStart(),
                a.windowEnd(),
                a.detectedAt(),
                a.trigger(),
                a.txnCount(),
                a.refundCount(),
                a.refundRatio(),
                a.amountSum(),
                a.baselineMean(),
                a.baselineStddev(),
                a.zScore(),
                a.enrichmentStatus(),
                a.category(),
                a.categoryConfidence(),
                a.severity(),
                a.severityConfidence(),
                a.modelVersion(),
                null,
                null);
    }

    private static boolean matchesFeedback(SuggestionQuery query, DispatchSuggestion suggestion) {
        if (query.feedback() == null) {
            return true;
        }
        return query.feedback().equals("none")
                ? suggestion.operatorFeedback() == null
                : suggestion.operatorFeedback() != null
                        && suggestion.operatorFeedback().wireName().equals(query.feedback());
    }

    public final DispatchFeedbackStore feedbackStore = new DispatchFeedbackStore() {
        @Override
        public Optional<DispatchSuggestion> find(UUID id) {
            return Optional.ofNullable(suggestions.get(id));
        }

        @Override
        public Optional<DispatchSuggestion> record(UUID id, Feedback feedback, String actor) {
            return Optional.ofNullable(suggestions.computeIfPresent(
                    id,
                    (key, current) -> new DispatchSuggestion(
                            current.id(),
                            current.bunchingId(),
                            current.routeId(),
                            current.action(),
                            current.actionConfidence(),
                            current.modelVersion(),
                            current.createdAt(),
                            current.stateSnapshot(),
                            feedback,
                            actor,
                            FEEDBACK_TIME)));
        }
    };

    public final OtpReader otpReader = (feed, fromDate, toDate, routeIds, routeTypes) -> {
        otpRouteTypesAsked.add(routeTypes);
        List<OtpDay> days = otpDays.stream()
                .filter(d ->
                        !d.serviceDate().isBefore(fromDate) && !d.serviceDate().isAfter(toDate))
                .filter(d -> routeIds.isEmpty() || routeIds.contains(d.routeId()))
                .toList();
        return OtpScorecard.of(fromDate, toDate, days);
    };
}
