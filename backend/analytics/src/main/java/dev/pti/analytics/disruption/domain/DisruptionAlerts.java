package dev.pti.analytics.disruption.domain;

import dev.pti.analytics.alert.domain.AlertDraft;
import dev.pti.analytics.alert.domain.AlertTitles;
import dev.pti.analytics.alert.domain.AlertType;
import dev.pti.analytics.event.domain.InsightEvent;
import dev.pti.analytics.reference.domain.RouteInfo;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The alert and the UI events of a disruption episode (DOC-23 §10.1, §10.3): what is written into
 * {@code ops.alert_event} when an episode opens, rises in severity and closes, and the {@code disruption.*} events
 * that go with it. Plain maps with camelCase keys; the sink turns them into JSON.
 */
public final class DisruptionAlerts {

    public static final String OPENED = "disruption.opened";
    public static final String CLOSED = "disruption.closed";

    private final double severityHighZ;

    public DisruptionAlerts(double severityHighZ) {
        this.severityHighZ = severityHighZ;
    }

    /** 1, or 2 once the peak z-score has reached {@code severity-high-z} (DOC-23 §10.1). */
    public int severity(DisruptionEpisode episode) {
        return episode.peakZ() >= severityHighZ ? 2 : 1;
    }

    /** The alert of an episode that opens; its severity already follows the peak of the opening bucket. */
    public AlertDraft draft(DisruptionEpisode episode, RouteInfo route) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disruptionId", episode.id().toString());
        body.put("directionId", episode.directionId());
        body.put("episodeStart", episode.start());
        body.put("currentAvgDelaySeconds", episode.currentAvg());
        body.put("baselineMeanSeconds", episode.baselineMean());
        body.put("zScore", episode.currentZ());
        body.put("affectedStopIds", episode.affectedStopIds());
        return AlertDraft.of(
                AlertType.DISRUPTION,
                episode.id(),
                severity(episode),
                episode.routeId(),
                AlertTitles.disruption(route.label(), route.directionLabel(episode.directionId())),
                body);
    }

    /** The dedup key of the alert of an episode: {@code disruption:<id>}. */
    public static String dedupKey(DisruptionEpisode episode) {
        return AlertType.DISRUPTION.dedupKey(episode.id());
    }

    /** Added to the body when the severity goes up. */
    public Map<String, Object> raisePatch(DisruptionEpisode episode) {
        return Map.of("peakZScore", episode.peakZ());
    }

    /** Added to the body when the episode closes. */
    public Map<String, Object> closePatch(DisruptionEpisode episode) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("episodeEnd", Objects.requireNonNull(episode.end()));
        patch.put("closeReason", Objects.requireNonNull(episode.closeReason()).name());
        patch.put("peakZScore", episode.peakZ());
        return patch;
    }

    /** {@code disruption.opened}, addressed to the audience the alert has now (DOC-23 §10.3). */
    public InsightEvent openedEvent(
            DisruptionEpisode episode,
            Audience audience,
            @Nullable Instant sourceRecordTs,
            @Nullable Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", episode.id().toString());
        data.put("routeId", episode.routeId());
        data.put("directionId", episode.directionId());
        data.put("episodeStart", episode.start());
        data.put("currentAvgDelaySeconds", episode.currentAvg());
        data.put("baselineMeanSeconds", episode.baselineMean());
        data.put("zScore", episode.currentZ());
        data.put("affectedStopIds", episode.affectedStopIds());
        return event(OPENED, episode, audience, sourceRecordTs, committedAt, data);
    }

    /** {@code disruption.closed}, addressed to the audience the alert has now, which triage may have narrowed. */
    public InsightEvent closedEvent(
            DisruptionEpisode episode,
            Audience audience,
            @Nullable Instant sourceRecordTs,
            @Nullable Instant committedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", episode.id().toString());
        data.put("routeId", episode.routeId());
        data.put("directionId", episode.directionId());
        data.put("episodeEnd", Objects.requireNonNull(episode.end()));
        data.put("closeReason", Objects.requireNonNull(episode.closeReason()).name());
        data.put("peakZScore", episode.peakZ());
        return event(CLOSED, episode, audience, sourceRecordTs, committedAt, data);
    }

    private static InsightEvent event(
            String type,
            DisruptionEpisode episode,
            Audience audience,
            @Nullable Instant sourceRecordTs,
            @Nullable Instant committedAt,
            Map<String, Object> data) {
        return new InsightEvent(
                type,
                UiChannel.ALERTS,
                audience,
                episode.id().toString(),
                episode.routeId(),
                sourceRecordTs,
                committedAt,
                data);
    }
}
