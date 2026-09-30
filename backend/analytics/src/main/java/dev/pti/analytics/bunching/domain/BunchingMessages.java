package dev.pti.analytics.bunching.domain;

import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertTitles;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the detector says about an episode outside the episode table: the alert of DOC-23 §10.1 and the
 * {@code bunching.opened} and {@code bunching.closed} events of §10.3. Times are ISO-8601 strings in the payloads, as
 * in the examples of DOC-33 §5.2.
 */
public final class BunchingMessages {

    public static final String OPENED = "bunching.opened";
    public static final String CLOSED = "bunching.closed";

    /** Severity of a bunching alert: always 1 (DOC-23 §10.1). */
    private static final int ALERT_SEVERITY = 1;

    private BunchingMessages() {}

    /**
     * The alert of an episode that has just opened; the episode is the one as it was at the moment it opened.
     *
     * @param routeLabel {@code RouteInfo.label}
     * @param directionLabel the label of the episode's direction
     */
    public static AlertDraft alert(BunchingEpisode opened, String routeLabel, String directionLabel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bunchingId", opened.id().toString());
        body.put("directionId", opened.directionId());
        body.put("vehicleLeader", opened.leader());
        body.put("vehicleFollower", opened.follower());
        body.put("gapSeconds", opened.lastGapSeconds());
        body.put("headwaySeconds", opened.scheduledHeadwaySeconds());
        body.put("stopId", opened.openStopId());
        body.put("episodeStart", opened.episodeStart().toString());
        return AlertDraft.of(
                AlertType.BUNCHING,
                opened.id(),
                ALERT_SEVERITY,
                opened.routeId(),
                AlertTitles.bunching(routeLabel, directionLabel, opened.leader(), opened.follower()),
                body);
    }

    /** The keys that closing an episode adds to the body of its alert. */
    public static Map<String, Object> closePatch(BunchingEpisode closed) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("episodeEnd", String.valueOf(closed.episodeEnd()));
        patch.put("closeReason", String.valueOf(closed.closeReason()));
        patch.put("minGapSeconds", closed.minGapSeconds());
        return patch;
    }

    /** {@code bunching.opened}, keyed by the episode id. */
    public static InsightEvent opened(
            BunchingEpisode opened, @Nullable Instant sourceRecordTs, @Nullable Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", opened.id().toString());
        data.put("routeId", opened.routeId());
        data.put("directionId", opened.directionId());
        data.put("vehicleLeader", opened.leader());
        data.put("vehicleFollower", opened.follower());
        data.put("gapSeconds", opened.lastGapSeconds());
        data.put("headwaySeconds", opened.scheduledHeadwaySeconds());
        data.put("stopId", opened.openStopId());
        data.put("episodeStart", opened.episodeStart().toString());
        return event(OPENED, opened, sourceRecordTs, committedAt, data);
    }

    /** {@code bunching.closed}, keyed by the episode id. */
    public static InsightEvent closed(
            BunchingEpisode closed, @Nullable Instant sourceRecordTs, @Nullable Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", closed.id().toString());
        data.put("routeId", closed.routeId());
        data.put("episodeEnd", String.valueOf(closed.episodeEnd()));
        data.put("closeReason", String.valueOf(closed.closeReason()));
        data.put("minGapSeconds", closed.minGapSeconds());
        return event(CLOSED, closed, sourceRecordTs, committedAt, data);
    }

    private static InsightEvent event(
            String type,
            BunchingEpisode episode,
            @Nullable Instant sourceRecordTs,
            @Nullable Instant committedAt,
            Map<String, Object> data) {
        return new InsightEvent(
                type,
                UiChannel.ALERTS,
                Audience.OPERATIONS,
                episode.id().toString(),
                episode.routeId(),
                sourceRecordTs,
                committedAt,
                data);
    }
}
